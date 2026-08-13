package com.serviceatlas.api;

import com.serviceatlas.api.dto.ScanDtos.RepoProgress;
import com.serviceatlas.api.dto.ScanDtos.ScanStatusResponse;
import com.serviceatlas.api.dto.WorkspaceDtos.ScanSummary;
import com.serviceatlas.persistence.ScanEntity;
import com.serviceatlas.scan.ScanProgressBroker;
import com.serviceatlas.scan.ScanService;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Scan lifecycle endpoints (§5, FR-7). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/scans")
public class ScanController {

    private final ScanService scans;
    private final ScanProgressBroker progress;

    public ScanController(ScanService scans, ScanProgressBroker progress) {
        this.scans = scans;
        this.progress = progress;
    }

    /**
     * Triggers an asynchronous scan and returns immediately with the new scan id (FR-7.1).
     *
     * @param force re-parse everything instead of reusing unchanged repositories (FR-7.3, default: true)
     */
    @PostMapping
    public ResponseEntity<ScanSummary> start(
            @PathVariable Long workspaceId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "true") boolean force) {
        ScanEntity scan = scans.startScan(workspaceId, force);
        return ResponseEntity
                .accepted()
                .location(URI.create("/api/v1/workspaces/" + workspaceId + "/scans/" + scan.getId()))
                .body(ScanSummary.of(scan));
    }

    @GetMapping
    public List<ScanSummary> list(@PathVariable Long workspaceId) {
        return scans.historyFor(workspaceId).stream().map(ScanSummary::of).toList();
    }

    @GetMapping("/{scanId}")
    public ScanStatusResponse status(@PathVariable Long workspaceId, @PathVariable Long scanId) {
        ScanEntity scan = requireScanOfWorkspace(workspaceId, scanId);
        List<RepoProgress> repos = scans.repoProgress(scanId).stream().map(RepoProgress::of).toList();
        return new ScanStatusResponse(ScanSummary.of(scan), repos);
    }

    /** Live progress stream (FR-7.1). Polling {@code /{scanId}} remains available. */
    @GetMapping(value = "/{scanId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long workspaceId, @PathVariable Long scanId) {
        requireScanOfWorkspace(workspaceId, scanId);
        SseEmitter emitter = progress.subscribe(scanId);
        // Send the current state immediately so a late subscriber is never blank.
        progress.publish(scanId, "scan", scans.scanSnapshot(scanId));
        return emitter;
    }

    private ScanEntity requireScanOfWorkspace(Long workspaceId, Long scanId) {
        ScanEntity scan = scans.get(scanId);
        if (!scan.getWorkspaceId().equals(workspaceId)) {
            throw ApiException.notFound("Scan", scanId);
        }
        return scan;
    }
}
