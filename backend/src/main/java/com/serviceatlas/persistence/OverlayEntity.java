package com.serviceatlas.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A user edit that outlives scans (FR-4.4): a manual node or edge, a hidden element, an annotation,
 * or a dragged position (FR-5.3).
 *
 * <p>Overlays belong to the <em>workspace</em>, not to a scan. That is the whole point: re-scanning
 * replaces parser output but leaves the user's work in place.
 */
@Entity
@Table(name = "overlay")
public class OverlayEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OverlayKind kind;

    /** Node key or edge id this overlay applies to; null for whole-graph overlays. */
    @Column(name = "target_key", length = 400)
    private String targetKey;

    @Column(name = "payload_json", nullable = false, length = 1_000_000)
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OverlayEntity() {
    }

    public OverlayEntity(Long workspaceId, OverlayKind kind, String targetKey, String payloadJson) {
        this.workspaceId = workspaceId;
        this.kind = kind;
        this.targetKey = targetKey;
        this.payloadJson = payloadJson;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public OverlayKind getKind() {
        return kind;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
        this.createdAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
