package com.serviceatlas.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.serviceatlas.graph.model.NodeType;

/** Persisted form of a {@link com.serviceatlas.graph.model.GraphNode}, per scan (§6). */
@Entity
@Table(name = "service_node")
public class ServiceNodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_id", nullable = false)
    private Long scanId;

    @Column(name = "node_key", nullable = false, length = 400)
    private String nodeKey;

    @Column(name = "display_name", nullable = false, length = 400)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NodeType type;

    @Column(length = 100)
    private String framework;

    @Column(name = "scala_version", length = 40)
    private String scalaVersion;

    @Column(name = "sbt_version", length = 40)
    private String sbtVersion;

    @Column(name = "repo_path", length = 1000)
    private String repoPath;

    @Column(name = "parent_key", length = 400)
    private String parentKey;

    /** Endpoints, warnings and free-form metadata, serialised together. */
    @Column(name = "metadata_json", length = 1_000_000)
    private String metadataJson;

    protected ServiceNodeEntity() {
    }

    public ServiceNodeEntity(Long scanId, String nodeKey, String displayName, NodeType type) {
        this.scanId = scanId;
        this.nodeKey = nodeKey;
        this.displayName = displayName;
        this.type = type;
    }

    public Long getId() {
        return id;
    }

    public Long getScanId() {
        return scanId;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public NodeType getType() {
        return type;
    }

    public String getFramework() {
        return framework;
    }

    public void setFramework(String framework) {
        this.framework = framework;
    }

    public String getScalaVersion() {
        return scalaVersion;
    }

    public void setScalaVersion(String scalaVersion) {
        this.scalaVersion = scalaVersion;
    }

    public String getSbtVersion() {
        return sbtVersion;
    }

    public void setSbtVersion(String sbtVersion) {
        this.sbtVersion = sbtVersion;
    }

    public String getRepoPath() {
        return repoPath;
    }

    public void setRepoPath(String repoPath) {
        this.repoPath = repoPath;
    }

    public String getParentKey() {
        return parentKey;
    }

    public void setParentKey(String parentKey) {
        this.parentKey = parentKey;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }
}
