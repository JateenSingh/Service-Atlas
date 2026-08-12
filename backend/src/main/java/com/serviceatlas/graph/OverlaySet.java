package com.serviceatlas.graph;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.serviceatlas.graph.model.EdgeType;
import com.serviceatlas.graph.model.NodeType;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A workspace's accumulated manual edits (FR-4.4) and dragged positions (FR-5.3), in the shape the
 * API speaks.
 *
 * <p>Mutable and built up from stored overlay rows, then applied to a scan's parser output. Kept
 * separate from {@link com.serviceatlas.graph.model.DependencyGraph} on purpose: the parser output
 * for a scan is immutable history, and the overlay is the living layer on top of it.
 *
 * <p>The explicit Jackson creator and property names matter: this type is serialised whole into
 * {@code .atlas} bundles (FR-1.4), and the accessors are not JavaBean-shaped.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OverlaySet {

    private final Map<String, Position> positions = new LinkedHashMap<>();
    private final Map<String, ManualNode> addedNodes = new LinkedHashMap<>();
    private final Set<String> hiddenNodes = new LinkedHashSet<>();
    private final Map<String, ManualEdge> addedEdges = new LinkedHashMap<>();
    private final Set<String> hiddenEdges = new LinkedHashSet<>();
    private final Map<String, String> nodeNotes = new LinkedHashMap<>();
    private final Map<String, String> edgeNotes = new LinkedHashMap<>();

    public OverlaySet() {
    }

    @JsonCreator
    public OverlaySet(
            @JsonProperty("positions") Map<String, Position> positions,
            @JsonProperty("addedNodes") Map<String, ManualNode> addedNodes,
            @JsonProperty("hiddenNodes") Set<String> hiddenNodes,
            @JsonProperty("addedEdges") Map<String, ManualEdge> addedEdges,
            @JsonProperty("hiddenEdges") Set<String> hiddenEdges,
            @JsonProperty("nodeNotes") Map<String, String> nodeNotes,
            @JsonProperty("edgeNotes") Map<String, String> edgeNotes) {
        putAll(this.positions, positions);
        putAll(this.addedNodes, addedNodes);
        addAll(this.hiddenNodes, hiddenNodes);
        putAll(this.addedEdges, addedEdges);
        addAll(this.hiddenEdges, hiddenEdges);
        putAll(this.nodeNotes, nodeNotes);
        putAll(this.edgeNotes, edgeNotes);
    }

    @JsonProperty("positions")
    public Map<String, Position> positions() {
        return positions;
    }

    @JsonProperty("addedNodes")
    public Map<String, ManualNode> addedNodes() {
        return addedNodes;
    }

    @JsonProperty("hiddenNodes")
    public Set<String> hiddenNodes() {
        return hiddenNodes;
    }

    @JsonProperty("addedEdges")
    public Map<String, ManualEdge> addedEdges() {
        return addedEdges;
    }

    @JsonProperty("hiddenEdges")
    public Set<String> hiddenEdges() {
        return hiddenEdges;
    }

    @JsonProperty("nodeNotes")
    public Map<String, String> nodeNotes() {
        return nodeNotes;
    }

    @JsonProperty("edgeNotes")
    public Map<String, String> edgeNotes() {
        return edgeNotes;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return positions.isEmpty()
                && addedNodes.isEmpty()
                && hiddenNodes.isEmpty()
                && addedEdges.isEmpty()
                && hiddenEdges.isEmpty()
                && nodeNotes.isEmpty()
                && edgeNotes.isEmpty();
    }

    private static <K, V> void putAll(Map<K, V> target, Map<K, V> source) {
        if (source != null) {
            target.putAll(source);
        }
    }

    private static <T> void addAll(Set<T> target, Set<T> source) {
        if (source != null) {
            target.addAll(source);
        }
    }

    public record Position(double x, double y) {
    }

    /**
     * A node the user added by hand — a service they know about that no repository mentions.
     */
    public record ManualNode(String key, String displayName, NodeType type, String note) {

        public ManualNode {
            type = type == null ? NodeType.EXTERNAL : type;
        }
    }

    /** An edge the user asserted. */
    public record ManualEdge(String id, String sourceKey, String targetKey, EdgeType type, String label) {

        public ManualEdge {
            type = type == null ? EdgeType.UNKNOWN : type;
            if (id == null) {
                id = com.serviceatlas.graph.model.GraphEdge.edgeId(sourceKey, targetKey, type);
            }
        }
    }
}
