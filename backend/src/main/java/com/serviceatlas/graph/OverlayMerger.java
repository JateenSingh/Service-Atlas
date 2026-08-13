package com.serviceatlas.graph;

import com.serviceatlas.graph.model.Confidence;
import com.serviceatlas.graph.model.DependencyGraph;
import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.GraphEdge;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.graph.model.SignalSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a workspace's {@link OverlaySet} to a scan's parser output (FR-4.4).
 *
 * <p>Re-scan merges are where this earns its keep: parser output is replaced wholesale by each
 * scan, while user edits persist. When the two disagree — the user hid an edge the parser has found
 * again, or added one the parser has since discovered on its own — the user's decision wins for
 * rendering, and the disagreement is reported as a {@link Conflict} so the UI can surface it
 * instead of silently dropping either side.
 */
public final class OverlayMerger {

    private OverlayMerger() {
    }

    /**
     * @param graph     the merged graph the user should see
     * @param conflicts disagreements between the newest parse and the stored overlay
     */
    public record MergeResult(DependencyGraph graph, List<Conflict> conflicts) {
    }

    /**
     * @param kind    what disagreed
     * @param target  node key or edge id
     * @param message human-readable explanation for the UI
     */
    public record Conflict(ConflictKind kind, String target, String message) {
    }

    public enum ConflictKind {
        /** The parser found an edge again that the user had hidden. */
        HIDDEN_EDGE_REAPPEARED,
        /** The parser now finds an edge the user had added manually. */
        MANUAL_EDGE_CONFIRMED,
        /** A hidden or annotated node is no longer produced by the parser. */
        OVERLAY_TARGET_MISSING
    }

    public static MergeResult merge(DependencyGraph parsed, OverlaySet overlay) {
        List<Conflict> conflicts = new ArrayList<>();
        Map<String, GraphNode> nodes = new LinkedHashMap<>(parsed.nodesByKey());

        // Nodes the user added, unless the parser has since produced a node with that key.
        for (OverlaySet.ManualNode manual : overlay.addedNodes().values()) {
            nodes.computeIfAbsent(manual.key(), key -> GraphNode.builder(key, manual.displayName(), manual.type())
                    .metadata("manual", true)
                    .metadata("note", manual.note())
                    .build());
        }

        // Annotations attach to whatever node is there, parsed or manual.
        for (Map.Entry<String, String> note : overlay.nodeNotes().entrySet()) {
            GraphNode node = nodes.get(note.getKey());
            if (node == null) {
                conflicts.add(new Conflict(
                        ConflictKind.OVERLAY_TARGET_MISSING,
                        note.getKey(),
                        "Your note is attached to '" + note.getKey() + "', which the latest scan did not find."));
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(node.metadata());
            metadata.put("note", note.getValue());
            nodes.put(node.key(), withMetadata(node, metadata));
        }

        for (String hidden : overlay.hiddenNodes()) {
            if (nodes.remove(hidden) == null) {
                conflicts.add(new Conflict(
                        ConflictKind.OVERLAY_TARGET_MISSING,
                        hidden,
                        "You hid '" + hidden + "', which the latest scan no longer produces."));
            }
        }

        Map<String, GraphEdge> edges = new LinkedHashMap<>();
        for (GraphEdge edge : parsed.edges()) {
            edges.put(edge.id(), edge);
        }

        for (OverlaySet.ManualEdge manual : overlay.addedEdges().values()) {
            GraphEdge existing = edges.get(manual.id());
            if (existing != null) {
                conflicts.add(new Conflict(
                        ConflictKind.MANUAL_EDGE_CONFIRMED,
                        manual.id(),
                        "The scan now finds '" + manual.sourceKey() + " → " + manual.targetKey()
                                + "' on its own; your manual edge is redundant."));
                continue;
            }
            Evidence evidence = Evidence.of(
                    SignalSource.MANUAL,
                    "(manual)",
                    0,
                    manual.label(),
                    "Added by you, not found by the scanner");
            edges.put(manual.id(), new GraphEdge(
                    manual.id(),
                    manual.sourceKey(),
                    manual.targetKey(),
                    manual.type(),
                    Confidence.HIGH,
                    manual.label(),
                    List.of(evidence)));
        }

        for (String hidden : overlay.hiddenEdges()) {
            if (edges.remove(hidden) != null) {
                conflicts.add(new Conflict(
                        ConflictKind.HIDDEN_EDGE_REAPPEARED,
                        hidden,
                        "The latest scan found '" + hidden + "' again. It stays hidden until you unhide it."));
            }
        }

        for (Map.Entry<String, String> note : overlay.edgeNotes().entrySet()) {
            GraphEdge edge = edges.get(note.getKey());
            if (edge != null) {
                edges.put(edge.id(), edge.withLabel(note.getValue()));
            }
        }

        DependencyGraph merged = new DependencyGraph(List.copyOf(nodes.values()), List.copyOf(edges.values()))
                .pruneDanglingEdges();
        return new MergeResult(merged, List.copyOf(conflicts));
    }

    private static GraphNode withMetadata(GraphNode node, Map<String, Object> metadata) {
        return new GraphNode(
                node.key(),
                node.displayName(),
                node.type(),
                node.framework(),
                node.scalaVersion(),
                node.sbtVersion(),
                node.repoPath(),
                node.parentKey(),
                node.description(),
                node.endpoints(),
                node.warnings(),
                metadata);
    }
}
