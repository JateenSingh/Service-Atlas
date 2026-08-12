package com.serviceatlas.persistence;

import com.serviceatlas.graph.model.Confidence;
import com.serviceatlas.graph.model.EdgeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persisted form of a {@link com.serviceatlas.graph.model.GraphEdge}, per scan (§6). */
@Entity
@Table(name = "dependency_edge")
public class DependencyEdgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_id", nullable = false)
    private Long scanId;

    @Column(name = "source_key", nullable = false, length = 400)
    private String sourceKey;

    @Column(name = "target_key", nullable = false, length = 400)
    private String targetKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "edge_type", nullable = false, length = 20)
    private EdgeType edgeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Confidence confidence;

    @Column(length = 400)
    private String label;

    @Column(name = "evidence_json", length = 1_000_000)
    private String evidenceJson;

    protected DependencyEdgeEntity() {
    }

    public DependencyEdgeEntity(Long scanId, String sourceKey, String targetKey, EdgeType edgeType,
                                Confidence confidence) {
        this.scanId = scanId;
        this.sourceKey = sourceKey;
        this.targetKey = targetKey;
        this.edgeType = edgeType;
        this.confidence = confidence;
    }

    public Long getId() {
        return id;
    }

    public Long getScanId() {
        return scanId;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public EdgeType getEdgeType() {
        return edgeType;
    }

    public Confidence getConfidence() {
        return confidence;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public void setEvidenceJson(String evidenceJson) {
        this.evidenceJson = evidenceJson;
    }
}
