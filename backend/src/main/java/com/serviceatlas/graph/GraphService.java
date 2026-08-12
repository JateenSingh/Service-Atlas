package com.serviceatlas.graph;

import com.serviceatlas.api.ApiException;
import com.serviceatlas.graph.model.DependencyGraph;
import com.serviceatlas.persistence.ScanEntity;
import com.serviceatlas.scan.ScanService;
import com.serviceatlas.workspace.WorkspaceService;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Serves the graph for a workspace (§5 {@code GET /workspaces/{id}/graph}).
 *
 * <p>"The graph" means the newest completed scan's parser output with the workspace's user overlays
 * merged on top (FR-4.4) — never raw parser output, because the user's edits are part of the truth
 * they expect to see.
 */
@Service
public class GraphService {

    private final GraphStore graphStore;
    private final ScanService scans;
    private final WorkspaceService workspaces;

    public GraphService(GraphStore graphStore, ScanService scans, WorkspaceService workspaces) {
        this.graphStore = graphStore;
        this.scans = scans;
        this.workspaces = workspaces;
    }

    /** The merged graph for a workspace, or an empty graph when it has never been scanned. */
    public GraphView forWorkspace(Long workspaceId) {
        workspaces.get(workspaceId); // 404 for an unknown workspace rather than an empty graph
        Optional<ScanEntity> scan = scans.latestCompleted(workspaceId);
        if (scan.isEmpty()) {
            return new GraphView(null, DependencyGraph.empty());
        }
        return new GraphView(scan.get().getId(), graphStore.load(scan.get().getId()));
    }

    /** The graph of one specific scan, for comparing against history (FR-4.3). */
    public GraphView forScan(Long workspaceId, Long scanId) {
        ScanEntity scan = scans.get(scanId);
        if (!scan.getWorkspaceId().equals(workspaceId)) {
            throw ApiException.notFound("Scan", scanId);
        }
        return new GraphView(scanId, graphStore.load(scanId));
    }

    /**
     * @param scanId the scan the parser output came from, or null when nothing has been scanned yet
     */
    public record GraphView(Long scanId, DependencyGraph graph) {
    }
}
