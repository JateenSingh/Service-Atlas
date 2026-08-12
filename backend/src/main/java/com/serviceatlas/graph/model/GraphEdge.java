package com.serviceatlas.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A directed dependency: {@code source} depends on / calls {@code target} (FR-4.2).
 *
 * <p>The identity of an edge is (source, target, type) — several signals of the same type collapse
 * into one edge that accumulates evidence and takes the strongest confidence.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphEdge(
        String id,
        String sourceKey,
        String targetKey,
        EdgeType type,
        Confidence confidence,
        String label,
        List<Evidence> evidence) {

    public GraphEdge {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (id == null) {
            id = edgeId(sourceKey, targetKey, type);
        }
    }

    public static String edgeId(String sourceKey, String targetKey, EdgeType type) {
        return sourceKey + "->" + targetKey + ":" + type;
    }

    public static GraphEdge of(String sourceKey, String targetKey, EdgeType type, Confidence confidence,
                               List<Evidence> evidence) {
        return new GraphEdge(null, sourceKey, targetKey, type, confidence, null, evidence);
    }

    /** Folds another edge with the same identity into this one. */
    public GraphEdge merge(GraphEdge other) {
        List<Evidence> combined = new ArrayList<>(new LinkedHashSet<>(this.evidence));
        for (Evidence e : other.evidence) {
            if (!combined.contains(e)) {
                combined.add(e);
            }
        }
        return new GraphEdge(
                id,
                sourceKey,
                targetKey,
                type,
                Confidence.strongest(this.confidence, other.confidence),
                this.label != null ? this.label : other.label,
                combined);
    }

    public GraphEdge withLabel(String newLabel) {
        return new GraphEdge(id, sourceKey, targetKey, type, confidence, newLabel, evidence);
    }

    @JsonIgnore
    public boolean isSelfEdge() {
        return sourceKey.equals(targetKey);
    }
}
