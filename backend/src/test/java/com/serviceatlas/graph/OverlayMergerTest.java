package com.serviceatlas.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.serviceatlas.graph.model.Confidence;
import com.serviceatlas.graph.model.DependencyGraph;
import com.serviceatlas.graph.model.EdgeType;
import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.GraphEdge;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.graph.model.NodeType;
import com.serviceatlas.graph.model.SignalSource;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-4.4 — manual edits survive re-scans, and disagreements are surfaced rather than hidden. */
class OverlayMergerTest {

    private final DependencyGraph parsed = new DependencyGraph(
            List.of(
                    GraphNode.builder("a", "service-a", NodeType.SERVICE).build(),
                    GraphNode.builder("b", "service-b", NodeType.SERVICE).build()),
            List.of(GraphEdge.of("a", "b", EdgeType.HTTP, Confidence.HIGH,
                    List.of(Evidence.of(SignalSource.CONFIG_REFERENCE, "conf/application.conf", 3, "url", "found")))));

    @Test
    @DisplayName("A manually added edge appears with HIGH confidence and its own evidence")
    void addsManualEdges() {
        OverlaySet overlay = new OverlaySet();
        overlay.addedEdges().put(
                "b->a:UNKNOWN", new OverlaySet.ManualEdge(null, "b", "a", EdgeType.UNKNOWN, "callback"));

        OverlayMerger.MergeResult result = OverlayMerger.merge(parsed, overlay);

        GraphEdge manual = result.graph().edges().stream()
                .filter(edge -> edge.sourceKey().equals("b"))
                .findFirst()
                .orElseThrow();
        assertThat(manual.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(manual.label()).isEqualTo("callback");
        assertThat(manual.evidence()).singleElement()
                .satisfies(evidence -> assertThat(evidence.source()).isEqualTo(SignalSource.MANUAL));
    }

    @Test
    @DisplayName("A hidden edge stays hidden after a re-scan, and the reappearance is reported")
    void hiddenEdgesStayHiddenAndSurfaceAConflict() {
        OverlaySet overlay = new OverlaySet();
        overlay.hiddenEdges().add("a->b:HTTP");

        OverlayMerger.MergeResult result = OverlayMerger.merge(parsed, overlay);

        assertThat(result.graph().edges()).isEmpty();
        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.kind()).isEqualTo(OverlayMerger.ConflictKind.HIDDEN_EDGE_REAPPEARED);
            assertThat(conflict.target()).isEqualTo("a->b:HTTP");
        });
    }

    @Test
    @DisplayName("Hiding a node also removes the edges that would dangle")
    void hidingANodePrunesItsEdges() {
        OverlaySet overlay = new OverlaySet();
        overlay.hiddenNodes().add("b");

        OverlayMerger.MergeResult result = OverlayMerger.merge(parsed, overlay);

        assertThat(result.graph().nodes()).extracting(GraphNode::key).containsExactly("a");
        assertThat(result.graph().edges()).isEmpty();
    }

    @Test
    @DisplayName("A manual edge the scanner now finds on its own is reported as redundant")
    void redundantManualEdgeIsReported() {
        OverlaySet overlay = new OverlaySet();
        overlay.addedEdges().put(
                "a->b:HTTP", new OverlaySet.ManualEdge("a->b:HTTP", "a", "b", EdgeType.HTTP, null));

        OverlayMerger.MergeResult result = OverlayMerger.merge(parsed, overlay);

        assertThat(result.graph().edges()).hasSize(1);
        assertThat(result.conflicts()).singleElement().satisfies(conflict ->
                assertThat(conflict.kind()).isEqualTo(OverlayMerger.ConflictKind.MANUAL_EDGE_CONFIRMED));
    }

    @Test
    @DisplayName("A manual node is added, and its note reaches the node metadata")
    void addsManualNodesAndNotes() {
        OverlaySet overlay = new OverlaySet();
        overlay.addedNodes().put("legacy", new OverlaySet.ManualNode(
                "legacy", "mainframe", NodeType.EXTERNAL, "Not in git"));
        overlay.nodeNotes().put("a", "Owned by the payments team");

        OverlayMerger.MergeResult result = OverlayMerger.merge(parsed, overlay);

        assertThat(result.graph().node("legacy")).isPresent()
                .hasValueSatisfying(node -> assertThat(node.type()).isEqualTo(NodeType.EXTERNAL));
        assertThat(result.graph().node("a").orElseThrow().metadata())
                .containsEntry("note", "Owned by the payments team");
    }

    @Test
    @DisplayName("An overlay pointing at something the scan no longer produces is reported, not dropped silently")
    void staleOverlayIsReported() {
        OverlaySet overlay = new OverlaySet();
        overlay.nodeNotes().put("removed-service", "used to exist");

        OverlayMerger.MergeResult result = OverlayMerger.merge(parsed, overlay);

        assertThat(result.conflicts()).singleElement().satisfies(conflict ->
                assertThat(conflict.kind()).isEqualTo(OverlayMerger.ConflictKind.OVERLAY_TARGET_MISSING));
    }

    @Test
    @DisplayName("An empty overlay changes nothing")
    void emptyOverlayIsANoOp() {
        OverlayMerger.MergeResult result = OverlayMerger.merge(parsed, new OverlaySet());

        assertThat(result.graph().nodes()).hasSize(2);
        assertThat(result.graph().edges()).hasSize(1);
        assertThat(result.conflicts()).isEmpty();
    }
}
