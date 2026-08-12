package com.serviceatlas.api;

import com.serviceatlas.graph.GraphService;
import com.serviceatlas.graph.model.DependencyGraph;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Graph read endpoints (§5, FR-4). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class GraphController {

    private final GraphService graphs;

    public GraphController(GraphService graphs) {
        this.graphs = graphs;
    }

    @GetMapping("/graph")
    public GraphResponse graph(@PathVariable Long workspaceId) {
        GraphService.GraphView view = graphs.forWorkspace(workspaceId);
        return GraphResponse.of(view);
    }

    @GetMapping("/scans/{scanId}/graph")
    public GraphResponse graphForScan(@PathVariable Long workspaceId, @PathVariable Long scanId) {
        return GraphResponse.of(graphs.forScan(workspaceId, scanId));
    }

    /**
     * @param scanId    which scan produced this graph, null if the workspace has never been scanned
     * @param nodeCount convenience counts so the UI can show totals without walking the arrays
     */
    public record GraphResponse(Long scanId, int nodeCount, int edgeCount, DependencyGraph graph) {

        static GraphResponse of(GraphService.GraphView view) {
            return new GraphResponse(
                    view.scanId(), view.graph().nodeCount(), view.graph().edgeCount(), view.graph());
        }
    }
}
