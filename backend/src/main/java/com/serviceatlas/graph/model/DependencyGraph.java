package com.serviceatlas.graph.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The whole architecture graph for one scan, after overlay merge (FR-4.3, FR-4.4).
 *
 * <p>Nodes are keyed for O(1) lookup; the record keeps ordered lists so serialisation is stable
 * and diffs between scans stay readable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DependencyGraph(List<GraphNode> nodes, List<GraphEdge> edges) {

    public DependencyGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static DependencyGraph empty() {
        return new DependencyGraph(List.of(), List.of());
    }

    public Map<String, GraphNode> nodesByKey() {
        Map<String, GraphNode> byKey = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            byKey.put(node.key(), node);
        }
        return byKey;
    }

    public Optional<GraphNode> node(String key) {
        return nodes.stream().filter(n -> n.key().equals(key)).findFirst();
    }

    /** Drops edges whose endpoints are not present, which keeps the canvas from dangling. */
    public DependencyGraph pruneDanglingEdges() {
        Map<String, GraphNode> byKey = nodesByKey();
        List<GraphEdge> kept = new ArrayList<>();
        for (GraphEdge edge : edges) {
            if (byKey.containsKey(edge.sourceKey()) && byKey.containsKey(edge.targetKey())) {
                kept.add(edge);
            }
        }
        return new DependencyGraph(nodes, kept);
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }
}
