package com.serviceatlas.api.dto;

import com.serviceatlas.persistence.ScanEntity;
import com.serviceatlas.persistence.ScanStatus;
import com.serviceatlas.persistence.WorkspaceEntity;
import com.serviceatlas.workspace.WorkspaceSettings;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Request and response shapes for the workspace endpoints (§5). */
public final class WorkspaceDtos {

    private WorkspaceDtos() {
    }

    /** {@code POST /workspaces} (FR-1.1). */
    public record CreateWorkspaceRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 1000) String rootPath,
            WorkspaceSettings settings) {
    }

    public record UpdateWorkspaceRequest(
            @Size(max = 200) String name,
            @Size(max = 1000) String rootPath,
            WorkspaceSettings settings) {
    }

    /** {@code POST /workspaces/preview} (FR-1.2). */
    public record PreviewRequest(@NotBlank String rootPath, WorkspaceSettings settings) {
    }

    public record WorkspaceResponse(
            Long id,
            String name,
            String rootPath,
            WorkspaceSettings settings,
            Instant createdAt,
            Instant updatedAt,
            ScanSummary latestScan) {

        public static WorkspaceResponse of(WorkspaceEntity entity, WorkspaceSettings settings, ScanSummary scan) {
            return new WorkspaceResponse(
                    entity.getId(),
                    entity.getName(),
                    entity.getRootPath(),
                    settings,
                    entity.getCreatedAt(),
                    entity.getUpdatedAt(),
                    scan);
        }
    }

    /** Compact scan description used inside workspace responses. */
    public record ScanSummary(
            Long id,
            ScanStatus status,
            Instant startedAt,
            Instant finishedAt,
            int repoCount,
            int errorCount,
            String message) {

        public static ScanSummary of(ScanEntity scan) {
            return new ScanSummary(
                    scan.getId(),
                    scan.getStatus(),
                    scan.getStartedAt(),
                    scan.getFinishedAt(),
                    scan.getRepoCount(),
                    scan.getErrorCount(),
                    scan.getMessage());
        }
    }

    /** {@code GET /workspaces/{id}} detail, including scan history (FR-4.3). */
    public record WorkspaceDetailResponse(WorkspaceResponse workspace, List<ScanSummary> scans) {
    }
}
