package com.serviceatlas.api;

import com.serviceatlas.api.dto.WorkspaceDtos.CreateWorkspaceRequest;
import com.serviceatlas.api.dto.WorkspaceDtos.PreviewRequest;
import com.serviceatlas.api.dto.WorkspaceDtos.ScanSummary;
import com.serviceatlas.api.dto.WorkspaceDtos.UpdateWorkspaceRequest;
import com.serviceatlas.api.dto.WorkspaceDtos.WorkspaceDetailResponse;
import com.serviceatlas.api.dto.WorkspaceDtos.WorkspaceResponse;
import com.serviceatlas.persistence.WorkspaceEntity;
import com.serviceatlas.scan.ScanService;
import com.serviceatlas.workspace.WorkspaceService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Workspace endpoints (§5, FR-1). */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaces;
    private final ScanService scans;

    public WorkspaceController(WorkspaceService workspaces, ScanService scans) {
        this.workspaces = workspaces;
        this.scans = scans;
    }

    @PostMapping
    public ResponseEntity<WorkspaceResponse> create(@Valid @RequestBody CreateWorkspaceRequest request) {
        WorkspaceEntity created = workspaces.create(request.name(), request.rootPath(), request.settings());
        return ResponseEntity
                .created(URI.create("/api/v1/workspaces/" + created.getId()))
                .body(toResponse(created));
    }

    @GetMapping
    public List<WorkspaceResponse> list() {
        return workspaces.list().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public WorkspaceDetailResponse get(@PathVariable Long id) {
        WorkspaceEntity workspace = workspaces.get(id);
        List<ScanSummary> history = scans.historyFor(id).stream().map(ScanSummary::of).toList();
        return new WorkspaceDetailResponse(toResponse(workspace), history);
    }

    @PatchMapping("/{id}")
    public WorkspaceResponse update(@PathVariable Long id, @Valid @RequestBody UpdateWorkspaceRequest request) {
        return toResponse(workspaces.update(id, request.name(), request.rootPath(), request.settings()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        workspaces.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** FR-1.2 — validate a folder and count candidate repos before creating anything. */
    @PostMapping("/preview")
    public WorkspaceService.RootPathPreview preview(@Valid @RequestBody PreviewRequest request) {
        return workspaces.preview(request.rootPath(), request.settings());
    }

    private WorkspaceResponse toResponse(WorkspaceEntity entity) {
        ScanSummary latest = scans.latest(entity.getId()).map(ScanSummary::of).orElse(null);
        return WorkspaceResponse.of(entity, workspaces.settingsOf(entity), latest);
    }
}
