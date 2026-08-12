package com.serviceatlas.api;

import com.serviceatlas.graph.GraphService;
import com.serviceatlas.graph.OverlayMerger;
import com.serviceatlas.graph.OverlayService;
import com.serviceatlas.graph.OverlaySet;
import com.serviceatlas.graph.model.DependencyGraph;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Graph read and overlay endpoints (§5, FR-4). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class GraphController {

    private final GraphService graphs;
    private final OverlayService overlays;

    public GraphController(GraphService graphs, OverlayService overlays) {
        this.graphs = graphs;
        this.overlays = overlays;
    }

    @GetMapping("/graph")
    public GraphResponse graph(@PathVariable Long workspaceId) {
        return GraphResponse.of(graphs.forWorkspace(workspaceId));
    }

    @GetMapping("/scans/{scanId}/graph")
    public GraphResponse graphForScan(@PathVariable Long workspaceId, @PathVariable Long scanId) {
        return GraphResponse.of(graphs.forScan(workspaceId, scanId));
    }

    /**
     * FR-4.4, FR-5.3 — applies manual edits and layout overrides, then returns the merged graph so
     * the caller never has to guess what the edit produced.
     */
    @PatchMapping("/graph/overlay")
    public GraphResponse patchOverlay(
            @PathVariable Long workspaceId, @RequestBody OverlayService.OverlayPatch patch) {
        overlays.patch(workspaceId, patch);
        return GraphResponse.of(graphs.forWorkspace(workspaceId));
    }

    /**
     * @param scanId    which scan produced this graph, null if the workspace has never been scanned
     * @param positions user-pinned node positions
     * @param conflicts overlay/parser disagreements worth surfacing (FR-4.4)
     */
    public record GraphResponse(
            Long scanId,
            int nodeCount,
            int edgeCount,
            DependencyGraph graph,
            Map<String, OverlaySet.Position> positions,
            List<OverlayMerger.Conflict> conflicts) {

        static GraphResponse of(GraphService.GraphView view) {
            return new GraphResponse(
                    view.scanId(),
                    view.graph().nodeCount(),
                    view.graph().edgeCount(),
                    view.graph(),
                    view.positions(),
                    view.conflicts());
        }
    }
}
