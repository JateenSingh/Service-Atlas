package com.serviceatlas.graph;

import com.serviceatlas.api.ApiException;
import com.serviceatlas.graph.model.DependencyGraph;
import com.serviceatlas.persistence.ScanEntity;
import com.serviceatlas.scan.ScanService;
import com.serviceatlas.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Serves the graph for a workspace (§5 {@code GET /workspaces/{id}/graph}).
 *
 * <p>"The graph" means the newest completed scan's parser output with the workspace's overlay
 * merged on top (FR-4.4) — never raw parser output, because the user's edits are part of the truth
 * they expect to see.
 */
@Service
public class GraphService {

    private final GraphStore graphStore;
    private final OverlayService overlays;
    private final ScanService scans;
    private final WorkspaceService workspaces;

    public GraphService(GraphStore graphStore, OverlayService overlays, ScanService scans,
                        WorkspaceService workspaces) {
        this.graphStore = graphStore;
        this.overlays = overlays;
        this.scans = scans;
        this.workspaces = workspaces;
    }

    /** The merged graph for a workspace, or an empty graph when it has never been scanned. */
    public GraphView forWorkspace(Long workspaceId) {
        workspaces.get(workspaceId); // 404 for an unknown workspace rather than an empty graph
        Optional<ScanEntity> scan = scans.latestCompleted(workspaceId);
        DependencyGraph parsed = scan.map(entity -> graphStore.load(entity.getId()))
                .orElseGet(DependencyGraph::empty);
        return view(workspaceId, scan.map(ScanEntity::getId).orElse(null), parsed);
    }

    /** The graph of one specific scan, for comparing against history (FR-4.3). */
    public GraphView forScan(Long workspaceId, Long scanId) {
        ScanEntity scan = scans.get(scanId);
        if (!scan.getWorkspaceId().equals(workspaceId)) {
            throw ApiException.notFound("Scan", scanId);
        }
        return view(workspaceId, scanId, graphStore.load(scanId));
    }

    /** Raw parser output for a scan, before any overlay — what the bundle stores (FR-1.4). */
    public DependencyGraph parsedGraph(Long scanId) {
        return graphStore.load(scanId);
    }

    private GraphView view(Long workspaceId, Long scanId, DependencyGraph parsed) {
        OverlaySet overlay = overlays.load(workspaceId);
        OverlayMerger.MergeResult merged = OverlayMerger.merge(parsed, overlay);
        return new GraphView(scanId, merged.graph(), overlay.positions(), merged.conflicts());
    }

    /**
     * @param scanId    the scan the parser output came from, or null when nothing has been scanned
     * @param graph     parser output with the overlay applied
     * @param positions user-pinned node positions (FR-5.3)
     * @param conflicts disagreements between the newest parse and the overlay (FR-4.4)
     */
    public record GraphView(
            Long scanId,
            DependencyGraph graph,
            Map<String, OverlaySet.Position> positions,
            List<OverlayMerger.Conflict> conflicts) {
    }
}
