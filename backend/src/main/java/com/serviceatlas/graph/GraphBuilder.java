package com.serviceatlas.graph;

import com.serviceatlas.graph.model.Confidence;
import com.serviceatlas.graph.model.DependencyGraph;
import com.serviceatlas.graph.model.EdgeType;
import com.serviceatlas.graph.model.GraphEdge;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.graph.model.NodeType;
import com.serviceatlas.parser.DependencySignal;
import com.serviceatlas.parser.ParsedRepo;
import com.serviceatlas.parser.common.NameNormalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns per-repository parse results into one architecture graph (FR-4).
 *
 * <p>This is the correlation step the parsers cannot do: only here is the full set of discovered
 * services known, so only here can "this repo mentions {@code log-quote-svc}" become an edge to a
 * specific node. Parsers stay language-specific and dumb about each other; this class stays
 * language-agnostic (FR-3.8).
 */
@Component
public class GraphBuilder {

    /** Hosts that never denote another service. */
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "0.0.0.0", "::1", "host.docker.internal");

    public DependencyGraph build(Collection<ParsedRepo> parsedRepos) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        AliasIndex aliases = new AliasIndex();

        for (ParsedRepo repo : parsedRepos) {
            for (GraphNode node : repo.nodes()) {
                nodes.putIfAbsent(node.key(), node);
            }
            GraphNode primary = repo.primaryNode();
            if (primary != null) {
                List<String> names = new ArrayList<>(repo.aliases());
                names.add(primary.displayName());
                names.add(primary.key());
                aliases.register(primary.key(), names);
            }
            for (GraphNode node : repo.nodes()) {
                if (node.type() == NodeType.SUB_MODULE) {
                    aliases.register(node.key(), List.of(node.displayName()));
                }
            }
        }

        Map<String, GraphEdge> edges = new LinkedHashMap<>();
        for (ParsedRepo repo : parsedRepos) {
            for (DependencySignal signal : repo.signals()) {
                if (signal.isMessaging()) {
                    addMessagingEdges(signal, nodes, edges);
                } else {
                    addDependencyEdge(signal, aliases, nodes, edges);
                }
            }
        }

        return new DependencyGraph(List.copyOf(nodes.values()), List.copyOf(edges.values()))
                .pruneDanglingEdges();
    }

    private void addDependencyEdge(DependencySignal signal, AliasIndex aliases,
                                   Map<String, GraphNode> nodes, Map<String, GraphEdge> edges) {
        Optional<String> resolved = aliases.resolve(signal.targetHint());
        String targetKey;

        if (resolved.isPresent()) {
            targetKey = resolved.get();
        } else if (signal.source().createsExternalNodes() && isPlausibleExternalService(signal.targetHint())) {
            targetKey = externalNode(signal.targetHint(), nodes);
        } else {
            return; // third-party library, local host, or unrecognisable reference: not an edge
        }

        if (targetKey.equals(signal.sourceNodeKey())) {
            return; // a service referencing itself is not architecture
        }
        putEdge(edges, new GraphEdge(
                null,
                signal.sourceNodeKey(),
                targetKey,
                signal.edgeType(),
                signal.confidence(),
                pathLabel(signal.targetHint()),
                List.of(signal.evidence())));
    }

    /** FR-3.6 — producer → topic → consumer, with the topic as its own node. */
    private void addMessagingEdges(DependencySignal signal, Map<String, GraphNode> nodes,
                                   Map<String, GraphEdge> edges) {
        String topicKey = topicNode(signal.topicName(), nodes);
        boolean producing = signal.source() == com.serviceatlas.graph.model.SignalSource.MESSAGING_PRODUCER;

        String source = producing ? signal.sourceNodeKey() : topicKey;
        String target = producing ? topicKey : signal.sourceNodeKey();
        putEdge(edges, new GraphEdge(
                null, source, target, EdgeType.MESSAGING, signal.confidence(),
                signal.topicName(), List.of(signal.evidence())));
    }

    private void putEdge(Map<String, GraphEdge> edges, GraphEdge edge) {
        edges.merge(edge.id(), edge, GraphEdge::merge);
    }

    private String externalNode(String rawReference, Map<String, GraphNode> nodes) {
        String derived = NameNormalizer.serviceLabelOf(rawReference);
        String label = derived.isBlank() ? rawReference : derived;
        String key = "external:" + NameNormalizer.canonical(label);
        nodes.computeIfAbsent(key, k -> GraphNode.builder(k, label, NodeType.EXTERNAL)
                .metadata("reference", NameNormalizer.hostOf(rawReference))
                .build());
        return key;
    }

    private String topicNode(String topicName, Map<String, GraphNode> nodes) {
        String key = "topic:" + NameNormalizer.canonical(topicName);
        nodes.computeIfAbsent(key, k -> GraphNode.builder(k, topicName, NodeType.TOPIC)
                .metadata("topic", topicName)
                .build());
        return key;
    }

    /**
     * Whether an unresolved reference is worth drawing as an external service. Rejects localhost,
     * bare IPs, and anything too short or generic to be a service name.
     */
    private boolean isPlausibleExternalService(String rawReference) {
        String host = NameNormalizer.hostOf(rawReference);
        if (host.isBlank() || LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return false; // a raw IP tells the reader nothing
        }
        return NameNormalizer.isMatchable(NameNormalizer.canonical(NameNormalizer.serviceLabelOf(host)));
    }

    /** Uses the path portion of a URL reference as the edge label, e.g. {@code /quotes}. */
    private String pathLabel(String rawReference) {
        if (rawReference == null) {
            return null;
        }
        int scheme = rawReference.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        String afterScheme = rawReference.substring(scheme + 3);
        int slash = afterScheme.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String path = afterScheme.substring(slash).strip();
        if (path.isBlank() || path.equals("/")) {
            return null;
        }
        return path.length() > 40 ? path.substring(0, 40) + "…" : path;
    }

    /**
     * Attaches route catalogues to their nodes (FR-3.4) and labels HTTP edges whose path matches one
     * of the callee's routes — the labelling use the spec calls for.
     */
    public DependencyGraph withEndpointLabels(DependencyGraph graph) {
        Map<String, GraphNode> byKey = graph.nodesByKey();
        List<GraphEdge> labelled = new ArrayList<>(graph.edges().size());
        for (GraphEdge edge : graph.edges()) {
            if (edge.type() != EdgeType.HTTP || edge.label() == null) {
                labelled.add(edge);
                continue;
            }
            GraphNode target = byKey.get(edge.targetKey());
            if (target == null || target.endpoints().isEmpty()) {
                labelled.add(edge);
                continue;
            }
            labelled.add(matchingRoute(target, edge.label())
                    .map(edge::withLabel)
                    .orElse(edge));
        }
        return new DependencyGraph(graph.nodes(), labelled);
    }

    /** Finds the callee route that best matches a called path, ignoring path parameters. */
    private Optional<String> matchingRoute(GraphNode target, String calledPath) {
        String[] calledSegments = calledPath.split("/");
        String best = null;
        int bestScore = 0;
        for (com.serviceatlas.graph.model.Endpoint endpoint : target.endpoints()) {
            String[] routeSegments = endpoint.path().split("/");
            int score = 0;
            for (int i = 0; i < Math.min(calledSegments.length, routeSegments.length); i++) {
                String routeSegment = routeSegments[i];
                if (routeSegment.startsWith(":") || routeSegment.startsWith("*") || routeSegment.startsWith("$")) {
                    score++; // a parameter matches anything
                } else if (routeSegment.equalsIgnoreCase(calledSegments[i])) {
                    score += 2;
                } else {
                    score = 0;
                    break;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = endpoint.signature();
            }
        }
        return Optional.ofNullable(best);
    }

    /** Convenience for tests and callers that want a confidence-filtered view. */
    public static DependencyGraph filterByConfidence(DependencyGraph graph, Confidence threshold) {
        List<GraphEdge> kept = graph.edges().stream()
                .filter(edge -> edge.confidence().atLeast(threshold))
                .toList();
        return new DependencyGraph(graph.nodes(), kept);
    }

    /** Adds a warning badge to a node, creating a placeholder if the repo failed entirely (FR-7.2). */
    public static DependencyGraph withNodeWarning(DependencyGraph graph, String nodeKey, String warning) {
        List<GraphNode> nodes = graph.nodes().stream()
                .map(node -> node.key().equals(nodeKey) ? node.withWarning(warning) : node)
                .toList();
        return new DependencyGraph(nodes, graph.edges());
    }
}
