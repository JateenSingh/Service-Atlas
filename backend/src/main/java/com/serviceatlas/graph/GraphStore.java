package com.serviceatlas.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceatlas.graph.model.DependencyGraph;
import com.serviceatlas.graph.model.Endpoint;
import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.GraphEdge;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.persistence.DependencyEdgeEntity;
import com.serviceatlas.persistence.DependencyEdgeRepository;
import com.serviceatlas.persistence.ServiceNodeEntity;
import com.serviceatlas.persistence.ServiceNodeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes a {@link DependencyGraph} for one scan (§6).
 *
 * <p>Node metadata, endpoints and warnings share the single {@code metadata_json} column the schema
 * specifies, wrapped in {@link NodePayload}. Keeping them together means one column to migrate if
 * the node model grows, and none of it is ever queried in SQL (ADR-002).
 */
@Component
public class GraphStore {

    private static final Logger log = LoggerFactory.getLogger(GraphStore.class);

    private final ServiceNodeRepository nodes;
    private final DependencyEdgeRepository edges;
    private final ObjectMapper objectMapper;

    public GraphStore(ServiceNodeRepository nodes, DependencyEdgeRepository edges, ObjectMapper objectMapper) {
        this.nodes = nodes;
        this.edges = edges;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void save(Long scanId, DependencyGraph graph) {
        List<ServiceNodeEntity> nodeEntities = new ArrayList<>(graph.nodes().size());
        for (GraphNode node : graph.nodes()) {
            ServiceNodeEntity entity =
                    new ServiceNodeEntity(scanId, node.key(), node.displayName(), node.type());
            entity.setFramework(node.framework());
            entity.setScalaVersion(node.scalaVersion());
            entity.setSbtVersion(node.sbtVersion());
            entity.setRepoPath(node.repoPath());
            entity.setParentKey(node.parentKey());
            entity.setMetadataJson(writeJson(
                    new NodePayload(node.description(), node.metadata(), node.endpoints(), node.warnings())));
            nodeEntities.add(entity);
        }
        nodes.saveAll(nodeEntities);

        List<DependencyEdgeEntity> edgeEntities = new ArrayList<>(graph.edges().size());
        for (GraphEdge edge : graph.edges()) {
            DependencyEdgeEntity entity = new DependencyEdgeEntity(
                    scanId, edge.sourceKey(), edge.targetKey(), edge.type(), edge.confidence());
            entity.setLabel(edge.label());
            entity.setEvidenceJson(writeJson(edge.evidence()));
            edgeEntities.add(entity);
        }
        edges.saveAll(edgeEntities);
    }

    @Transactional(readOnly = true)
    public DependencyGraph load(Long scanId) {
        List<GraphNode> loadedNodes = new ArrayList<>();
        for (ServiceNodeEntity entity : nodes.findByScanId(scanId)) {
            NodePayload payload = readPayload(entity.getMetadataJson());
            loadedNodes.add(new GraphNode(
                    entity.getNodeKey(),
                    entity.getDisplayName(),
                    entity.getType(),
                    entity.getFramework(),
                    entity.getScalaVersion(),
                    entity.getSbtVersion(),
                    entity.getRepoPath(),
                    entity.getParentKey(),
                    payload.description(),
                    payload.endpoints(),
                    payload.warnings(),
                    payload.metadata()));
        }

        List<GraphEdge> loadedEdges = new ArrayList<>();
        for (DependencyEdgeEntity entity : edges.findByScanId(scanId)) {
            loadedEdges.add(new GraphEdge(
                    null,
                    entity.getSourceKey(),
                    entity.getTargetKey(),
                    entity.getEdgeType(),
                    entity.getConfidence(),
                    entity.getLabel(),
                    readEvidence(entity.getEvidenceJson())));
        }
        return new DependencyGraph(loadedNodes, loadedEdges);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialise graph payload; storing empty", e);
            return null;
        }
    }

    private NodePayload readPayload(String json) {
        if (json == null || json.isBlank()) {
            return NodePayload.empty();
        }
        try {
            return objectMapper.readValue(json, NodePayload.class);
        } catch (Exception e) {
            log.warn("Could not read node payload; treating as empty", e);
            return NodePayload.empty();
        }
    }

    private List<Evidence> readEvidence(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Evidence>>() {
            });
        } catch (Exception e) {
            log.warn("Could not read edge evidence; treating as empty", e);
            return List.of();
        }
    }

    /** The node fields that share {@code metadata_json}: description, metadata, endpoints and warnings. */
    public record NodePayload(
            String description,
            Map<String, Object> metadata, List<Endpoint> endpoints, List<String> warnings) {

        public NodePayload {
            metadata = metadata == null ? Map.of() : metadata;
            endpoints = endpoints == null ? List.of() : endpoints;
            warnings = warnings == null ? List.of() : warnings;
        }

        static NodePayload empty() {
            return new NodePayload(null, Map.of(), List.of(), List.of());
        }
    }
}
