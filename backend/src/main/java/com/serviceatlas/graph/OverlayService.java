package com.serviceatlas.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceatlas.api.ApiException;
import com.serviceatlas.graph.model.EdgeType;
import com.serviceatlas.graph.model.NodeType;
import com.serviceatlas.persistence.OverlayEntity;
import com.serviceatlas.persistence.OverlayKind;
import com.serviceatlas.persistence.OverlayRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the per-workspace overlay (FR-4.4, FR-5.3).
 *
 * <p>Overlays are stored one row per (kind, target) and upserted, so dragging the same node fifty
 * times leaves one row rather than fifty, and an edit is undone by deleting its row rather than by
 * writing a tombstone.
 */
@Service
public class OverlayService {

    private static final Logger log = LoggerFactory.getLogger(OverlayService.class);

    private final OverlayRepository overlays;
    private final ObjectMapper objectMapper;

    public OverlayService(OverlayRepository overlays, ObjectMapper objectMapper) {
        this.overlays = overlays;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public OverlaySet load(Long workspaceId) {
        OverlaySet set = new OverlaySet();
        for (OverlayEntity entity : overlays.findByWorkspaceId(workspaceId)) {
            try {
                apply(set, entity);
            } catch (RuntimeException e) {
                log.warn("Skipping unreadable overlay {} for workspace {}", entity.getId(), workspaceId, e);
            }
        }
        return set;
    }

    private void apply(OverlaySet set, OverlayEntity entity) {
        String target = entity.getTargetKey();
        switch (entity.getKind()) {
            case LAYOUT_POSITION -> set.positions().put(target, read(entity, OverlaySet.Position.class));
            case NODE_ADDED -> set.addedNodes().put(target, read(entity, OverlaySet.ManualNode.class));
            case NODE_HIDDEN -> set.hiddenNodes().add(target);
            case NODE_ANNOTATION -> set.nodeNotes().put(target, read(entity, Note.class).note());
            case EDGE_ADDED -> set.addedEdges().put(target, read(entity, OverlaySet.ManualEdge.class));
            case EDGE_HIDDEN -> set.hiddenEdges().add(target);
            case EDGE_ANNOTATION -> set.edgeNotes().put(target, read(entity, Note.class).note());
        }
    }

    /** Applies an incremental patch and returns the resulting overlay. */
    @Transactional
    public OverlaySet patch(Long workspaceId, OverlayPatch patch) {
        if (patch.clear()) {
            for (OverlayEntity entity : overlays.findByWorkspaceId(workspaceId)) {
                overlays.delete(entity);
            }
            return new OverlaySet();
        }

        patch.positions().forEach((key, position) ->
                upsert(workspaceId, OverlayKind.LAYOUT_POSITION, key, position));

        patch.addedNodes().forEach(node -> {
            if (node.key() == null || node.key().isBlank()) {
                throw ApiException.badRequest("A manual node needs a key");
            }
            upsert(workspaceId, OverlayKind.NODE_ADDED, node.key(), node);
        });

        patch.addedEdges().forEach(edge -> {
            if (edge.sourceKey() == null || edge.targetKey() == null) {
                throw ApiException.badRequest("A manual edge needs a source and a target");
            }
            if (edge.sourceKey().equals(edge.targetKey())) {
                throw ApiException.badRequest("An edge cannot start and end at the same service");
            }
            upsert(workspaceId, OverlayKind.EDGE_ADDED, edge.id(), edge);
        });

        patch.hiddenNodes().forEach(key -> upsert(workspaceId, OverlayKind.NODE_HIDDEN, key, Map.of()));
        patch.hiddenEdges().forEach(id -> upsert(workspaceId, OverlayKind.EDGE_HIDDEN, id, Map.of()));

        patch.nodeNotes().forEach((key, note) ->
                upsert(workspaceId, OverlayKind.NODE_ANNOTATION, key, new Note(note)));
        patch.edgeNotes().forEach((id, note) ->
                upsert(workspaceId, OverlayKind.EDGE_ANNOTATION, id, new Note(note)));

        // Removals: each list names overlay rows to delete, which is how an edit is undone.
        patch.removePositions().forEach(key -> remove(workspaceId, OverlayKind.LAYOUT_POSITION, key));
        patch.removeAddedNodes().forEach(key -> remove(workspaceId, OverlayKind.NODE_ADDED, key));
        patch.removeHiddenNodes().forEach(key -> remove(workspaceId, OverlayKind.NODE_HIDDEN, key));
        patch.removeAddedEdges().forEach(id -> remove(workspaceId, OverlayKind.EDGE_ADDED, id));
        patch.removeHiddenEdges().forEach(id -> remove(workspaceId, OverlayKind.EDGE_HIDDEN, id));
        patch.removeNodeNotes().forEach(key -> remove(workspaceId, OverlayKind.NODE_ANNOTATION, key));
        patch.removeEdgeNotes().forEach(id -> remove(workspaceId, OverlayKind.EDGE_ANNOTATION, id));

        return load(workspaceId);
    }

    /** Replaces the whole overlay — used when importing a bundle (FR-1.4). */
    @Transactional
    public void replace(Long workspaceId, OverlaySet set) {
        for (OverlayEntity entity : overlays.findByWorkspaceId(workspaceId)) {
            overlays.delete(entity);
        }
        set.positions().forEach((key, position) ->
                upsert(workspaceId, OverlayKind.LAYOUT_POSITION, key, position));
        set.addedNodes().forEach((key, node) -> upsert(workspaceId, OverlayKind.NODE_ADDED, key, node));
        set.hiddenNodes().forEach(key -> upsert(workspaceId, OverlayKind.NODE_HIDDEN, key, Map.of()));
        set.addedEdges().forEach((id, edge) -> upsert(workspaceId, OverlayKind.EDGE_ADDED, id, edge));
        set.hiddenEdges().forEach(id -> upsert(workspaceId, OverlayKind.EDGE_HIDDEN, id, Map.of()));
        set.nodeNotes().forEach((key, note) ->
                upsert(workspaceId, OverlayKind.NODE_ANNOTATION, key, new Note(note)));
        set.edgeNotes().forEach((id, note) ->
                upsert(workspaceId, OverlayKind.EDGE_ANNOTATION, id, new Note(note)));
    }

    private void upsert(Long workspaceId, OverlayKind kind, String targetKey, Object payload) {
        String json = write(payload);
        overlays.findByWorkspaceIdAndKindAndTargetKey(workspaceId, kind, targetKey)
                .ifPresentOrElse(
                        existing -> {
                            existing.setPayloadJson(json);
                            overlays.save(existing);
                        },
                        () -> overlays.save(new OverlayEntity(workspaceId, kind, targetKey, json)));
    }

    private void remove(Long workspaceId, OverlayKind kind, String targetKey) {
        overlays.deleteByWorkspaceIdAndKindAndTargetKey(workspaceId, kind, targetKey);
    }

    private <T> T read(OverlayEntity entity, Class<T> type) {
        try {
            return objectMapper.readValue(entity.getPayloadJson(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable overlay payload for " + entity.getKind(), e);
        }
    }

    private String write(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw ApiException.badRequest("Could not store overlay: " + e.getMessage());
        }
    }

    private record Note(String note) {
    }

    /**
     * The PATCH body (§5 {@code PATCH /workspaces/{id}/graph/overlay}). Every field is optional;
     * absent means "leave alone", which is what makes a drag a one-key request.
     */
    public record OverlayPatch(
            Map<String, OverlaySet.Position> positions,
            List<OverlaySet.ManualNode> addedNodes,
            List<String> hiddenNodes,
            List<ManualEdgeRequest> addedEdgeRequests,
            List<String> hiddenEdges,
            Map<String, String> nodeNotes,
            Map<String, String> edgeNotes,
            List<String> removePositions,
            List<String> removeAddedNodes,
            List<String> removeHiddenNodes,
            List<String> removeAddedEdges,
            List<String> removeHiddenEdges,
            List<String> removeNodeNotes,
            List<String> removeEdgeNotes,
            boolean clear) {

        public OverlayPatch {
            positions = positions == null ? Map.of() : positions;
            addedNodes = addedNodes == null ? List.of() : addedNodes;
            hiddenNodes = hiddenNodes == null ? List.of() : hiddenNodes;
            addedEdgeRequests = addedEdgeRequests == null ? List.of() : addedEdgeRequests;
            hiddenEdges = hiddenEdges == null ? List.of() : hiddenEdges;
            nodeNotes = nodeNotes == null ? Map.of() : nodeNotes;
            edgeNotes = edgeNotes == null ? Map.of() : edgeNotes;
            removePositions = removePositions == null ? List.of() : removePositions;
            removeAddedNodes = removeAddedNodes == null ? List.of() : removeAddedNodes;
            removeHiddenNodes = removeHiddenNodes == null ? List.of() : removeHiddenNodes;
            removeAddedEdges = removeAddedEdges == null ? List.of() : removeAddedEdges;
            removeHiddenEdges = removeHiddenEdges == null ? List.of() : removeHiddenEdges;
            removeNodeNotes = removeNodeNotes == null ? List.of() : removeNodeNotes;
            removeEdgeNotes = removeEdgeNotes == null ? List.of() : removeEdgeNotes;
        }

        public List<OverlaySet.ManualEdge> addedEdges() {
            return addedEdgeRequests.stream()
                    .map(request -> new OverlaySet.ManualEdge(
                            null, request.sourceKey(), request.targetKey(), request.type(), request.label()))
                    .toList();
        }
    }

    /** Manual edge input; the id is derived, so callers never invent one. */
    public record ManualEdgeRequest(String sourceKey, String targetKey, EdgeType type, String label) {
    }

    /** Manual node input. */
    public record ManualNodeRequest(String key, String displayName, NodeType type, String note) {
    }
}
