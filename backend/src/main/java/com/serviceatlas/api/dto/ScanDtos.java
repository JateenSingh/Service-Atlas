package com.serviceatlas.api.dto;

import com.serviceatlas.api.dto.WorkspaceDtos.ScanSummary;
import com.serviceatlas.persistence.RepoScanStatus;
import com.serviceatlas.persistence.ScanRepoEntity;
import java.util.List;

/** Scan progress shapes (FR-7.1). */
public final class ScanDtos {

    private ScanDtos() {
    }

    /** One row in the live per-repository progress list (UX-3). */
    public record RepoProgress(
            String repoPath,
            String displayName,
            RepoScanStatus status,
            String message,
            Long durationMs) {

        public static RepoProgress of(ScanRepoEntity entity) {
            return new RepoProgress(
                    entity.getRepoPath(),
                    entity.getDisplayName(),
                    entity.getStatus(),
                    entity.getMessage(),
                    entity.getDurationMs());
        }
    }

    /** {@code GET /workspaces/{id}/scans/{scanId}}. */
    public record ScanStatusResponse(ScanSummary scan, List<RepoProgress> repos) {
    }
}
