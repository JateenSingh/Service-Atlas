package com.serviceatlas.graph.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node in the architecture graph (FR-4.1).
 *
 * <p>{@code key} is the stable identity used by edges, overlays and layout overrides. It is
 * derived from the service name and therefore survives re-scans, unlike a database id.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphNode(
        String key,
        String displayName,
        NodeType type,
        String framework,
        String scalaVersion,
        String sbtVersion,
        String repoPath,
        String parentKey,
        List<Endpoint> endpoints,
        List<String> warnings,
        Map<String, Object> metadata) {

    public GraphNode {
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static Builder builder(String key, String displayName, NodeType type) {
        return new Builder(key, displayName, type);
    }

    public boolean isExternal() {
        return type == NodeType.EXTERNAL;
    }

    /** Returns a copy with the given warning appended (FR-7.2 warning badges). */
    public GraphNode withWarning(String warning) {
        List<String> merged = new ArrayList<>(warnings);
        if (!merged.contains(warning)) {
            merged.add(warning);
        }
        return new GraphNode(
                key, displayName, type, framework, scalaVersion, sbtVersion, repoPath, parentKey,
                endpoints, merged, metadata);
    }

    public GraphNode withEndpoints(List<Endpoint> newEndpoints) {
        return new GraphNode(
                key, displayName, type, framework, scalaVersion, sbtVersion, repoPath, parentKey,
                newEndpoints, warnings, metadata);
    }

    public static final class Builder {
        private final String key;
        private final String displayName;
        private final NodeType type;
        private String framework;
        private String scalaVersion;
        private String sbtVersion;
        private String repoPath;
        private String parentKey;
        private List<Endpoint> endpoints = List.of();
        private List<String> warnings = List.of();
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String key, String displayName, NodeType type) {
            this.key = key;
            this.displayName = displayName;
            this.type = type;
        }

        public Builder framework(String framework) {
            this.framework = framework;
            return this;
        }

        public Builder scalaVersion(String scalaVersion) {
            this.scalaVersion = scalaVersion;
            return this;
        }

        public Builder sbtVersion(String sbtVersion) {
            this.sbtVersion = sbtVersion;
            return this;
        }

        public Builder repoPath(String repoPath) {
            this.repoPath = repoPath;
            return this;
        }

        public Builder parentKey(String parentKey) {
            this.parentKey = parentKey;
            return this;
        }

        public Builder endpoints(List<Endpoint> endpoints) {
            this.endpoints = endpoints;
            return this;
        }

        public Builder warnings(List<String> warnings) {
            this.warnings = warnings;
            return this;
        }

        public Builder metadata(String name, Object value) {
            if (value != null) {
                this.metadata.put(name, value);
            }
            return this;
        }

        public GraphNode build() {
            return new GraphNode(
                    key, displayName, type, framework, scalaVersion, sbtVersion, repoPath, parentKey,
                    endpoints, warnings, metadata);
        }
    }
}
