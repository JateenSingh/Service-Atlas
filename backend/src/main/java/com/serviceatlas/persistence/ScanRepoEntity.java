package com.serviceatlas.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Progress and outcome for one repository within a scan (FR-7.1, FR-7.2), plus the content hash
 * that lets the next scan skip it if nothing relevant changed (FR-7.3).
 */
@Entity
@Table(name = "scan_repo")
public class ScanRepoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_id", nullable = false)
    private Long scanId;

    @Column(name = "repo_path", nullable = false, length = 1000)
    private String repoPath;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RepoScanStatus status;

    @Column(length = 2000)
    private String message;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "duration_ms")
    private Long durationMs;

    protected ScanRepoEntity() {
    }

    public ScanRepoEntity(Long scanId, String repoPath, String displayName) {
        this.scanId = scanId;
        this.repoPath = repoPath;
        this.displayName = displayName;
        this.status = RepoScanStatus.DISCOVERED;
    }

    public Long getId() {
        return id;
    }

    public Long getScanId() {
        return scanId;
    }

    public String getRepoPath() {
        return repoPath;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public RepoScanStatus getStatus() {
        return status;
    }

    public void setStatus(RepoScanStatus status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message == null || message.length() <= 2000 ? message : message.substring(0, 2000);
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }
}
