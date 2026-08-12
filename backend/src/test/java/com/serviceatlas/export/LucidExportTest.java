package com.serviceatlas.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceatlas.export.importformat.LucidDocument;
import com.serviceatlas.export.importformat.LucidDocumentBuilder;
import com.serviceatlas.export.importformat.LucidPackager;
import com.serviceatlas.graph.model.Confidence;
import com.serviceatlas.graph.model.DependencyGraph;
import com.serviceatlas.graph.model.EdgeType;
import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.GraphEdge;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.graph.model.NodeType;
import com.serviceatlas.graph.model.SignalSource;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-6.1 / ADR-003 — the Lucid Standard Import output.
 *
 * <p>This is the golden-file test ADR-003 promises: it pins the emitted structure so that if the
 * schema ever has to be corrected against the official reference, the change shows up as one
 * visible, well-tested diff rather than as a silently broken export.
 */
class LucidExportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LucidDocumentBuilder builder = new LucidDocumentBuilder();
    private final LucidPackager packager = new LucidPackager(objectMapper);

    private DiagramView view() {
        DependencyGraph graph = new DependencyGraph(
                List.of(
                        GraphNode.builder("logordersvc", "log-order-svc", NodeType.SERVICE)
                                .framework("Play Framework").build(),
                        GraphNode.builder("logquotesvc", "log-quote-svc", NodeType.SERVICE)
                                .framework("http4s").build(),
                        GraphNode.builder("topic:orderevents", "order-events", NodeType.TOPIC).build(),
                        GraphNode.builder("external:apistripecom", "api.stripe.com", NodeType.EXTERNAL).build()),
                List.of(
                        new GraphEdge(null, "logordersvc", "logquotesvc", EdgeType.HTTP, Confidence.HIGH,
                                "GET /quotes/:id",
                                List.of(Evidence.of(SignalSource.CONFIG_REFERENCE, "conf/application.conf",
                                        7, "url = \"http://log-quote-svc:9000\"", "config"))),
                        GraphEdge.of("logordersvc", "topic:orderevents", EdgeType.MESSAGING, Confidence.MEDIUM,
                                List.of()),
                        GraphEdge.of("logordersvc", "external:apistripecom", EdgeType.HTTP, Confidence.LOW,
                                List.of())));

        Map<String, DiagramView.Box> positions = Map.of(
                "logordersvc", new DiagramView.Box(100, 100, 200, 64),
                "logquotesvc", new DiagramView.Box(420, 60, 200, 64),
                "topic:orderevents", new DiagramView.Box(420, 200, 180, 44),
                "external:apistripecom", new DiagramView.Box(420, 320, 200, 64));

        return new DiagramView("Logistics", graph, positions, Map.of(), "dark");
    }

    @Test
    @DisplayName("The document has the Standard Import top-level shape")
    void documentShape() throws Exception {
        JsonNode json = objectMapper.readTree(packager.toDocumentJson(builder.build(view())));

        assertThat(json.get("version").asInt()).isEqualTo(1);
        assertThat(json.get("pages")).hasSize(1);

        JsonNode page = json.get("pages").get(0);
        assertThat(page.get("title").asText()).isEqualTo("Logistics");
        assertThat(page.get("shapes")).isNotEmpty();
        assertThat(page.get("lines")).hasSize(3);
    }

    @Test
    @DisplayName("A shape carries id, type, boundingBox, style and text")
    void shapeShape() throws Exception {
        JsonNode shape = shapes(view()).get(0);

        assertThat(shape.hasNonNull("id")).isTrue();
        assertThat(shape.get("type").asText()).isIn("roundedRectangle", "ellipse", "rectangle");
        assertThat(shape.get("boundingBox").get("x").asDouble()).isEqualTo(0.0);
        assertThat(shape.get("boundingBox").get("w").asDouble()).isEqualTo(200.0);
        assertThat(shape.get("style").get("fill").get("type").asText()).isEqualTo("color");
        assertThat(shape.get("style").get("fill").get("color").asText()).startsWith("#");
        assertThat(shape.get("style").get("stroke").get("style").asText()).isIn("solid", "dashed", "dotted");
        assertThat(shape.get("text").asText()).contains("log-order-svc");
    }

    @Test
    @DisplayName("A line attaches to both shapes with normalised endpoint positions")
    void lineShape() throws Exception {
        JsonNode line = lines(view()).get(0);

        assertThat(line.get("lineType").asText()).isEqualTo("elbow");
        assertThat(line.get("stroke").get("color").asText()).startsWith("#");

        JsonNode start = line.get("endpoint1");
        assertThat(start.get("type").asText()).isEqualTo("shapeEndpoint");
        assertThat(start.get("style").asText()).isEqualTo("none");
        assertThat(start.get("position").get("x").asDouble()).isEqualTo(1.0);
        assertThat(start.get("position").get("y").asDouble()).isEqualTo(0.5);

        JsonNode end = line.get("endpoint2");
        assertThat(end.get("style").asText()).isEqualTo("arrow");
        assertThat(end.get("position").get("x").asDouble()).isEqualTo(0.0);
        assertThat(end.hasNonNull("shapeId")).isTrue();
    }

    @Test
    @DisplayName("FR-5.6: the encoding survives the export — dashes by type, width by confidence")
    void visualEncodingIsCarriedOver() throws Exception {
        JsonNode lines = lines(view());

        JsonNode http = lines.get(0);
        JsonNode messaging = lines.get(1);
        assertThat(http.get("stroke").get("style").asText()).isEqualTo("solid");
        assertThat(messaging.get("stroke").get("style").asText()).isEqualTo("dashed");
        assertThat(http.get("stroke").get("width").asDouble())
                .as("a HIGH-confidence edge is drawn heavier than a MEDIUM one")
                .isGreaterThan(messaging.get("stroke").get("width").asDouble());
    }

    @Test
    @DisplayName("An external service is drawn as a dashed outline, a topic as an ellipse")
    void nodeTypesAreVisuallyDistinct() throws Exception {
        JsonNode shapes = shapes(view());

        JsonNode topic = byText(shapes, "order-events");
        JsonNode external = byText(shapes, "api.stripe.com");
        assertThat(topic.get("type").asText()).isEqualTo("ellipse");
        assertThat(external.get("style").get("stroke").get("style").asText()).isEqualTo("dashed");
    }

    @Test
    @DisplayName("An edge label becomes line text")
    void edgeLabelsBecomeLineText() throws Exception {
        JsonNode line = lines(view()).get(0);

        assertThat(line.get("text").get(0).get("text").asText()).isEqualTo("GET /quotes/:id");
    }

    @Test
    @DisplayName("The legend is part of the exported document")
    void legendIsIncluded() throws Exception {
        JsonNode shapes = shapes(view());

        assertThat(shapes.toString()).contains("Legend").contains("HTTP call").contains("messaging");
    }

    @Test
    @DisplayName("Ids are stable across exports, so re-exporting produces a comparable document")
    void idsAreDeterministic() throws Exception {
        String first = new String(packager.toDocumentJson(builder.build(view())));
        String second = new String(packager.toDocumentJson(builder.build(view())));

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("Unset optional fields are omitted rather than sent as null")
    void optionalFieldsAreOmitted() throws Exception {
        String json = new String(packager.toDocumentJson(builder.build(view())));

        assertThat(json).doesNotContain(": null").doesNotContain(":null");
    }

    @Test
    @DisplayName("The .lucid file is a ZIP containing document.json at its root")
    void packagesAsZip() throws Exception {
        byte[] archive = packager.pack(builder.build(view()));

        List<String> entries = new java.util.ArrayList<>();
        String documentJson = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
                if (entry.getName().equals("document.json")) {
                    documentJson = new String(zip.readAllBytes());
                }
            }
        }

        assertThat(entries).containsExactly("document.json");
        assertThat(objectMapper.readTree(documentJson).get("version").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Nodes without a position are not exported — they are not on screen (FR-6.3)")
    void unpositionedNodesAreSkipped() throws Exception {
        DiagramView original = view();
        DiagramView partial = new DiagramView(
                original.title(),
                original.graph(),
                Map.of("logordersvc", new DiagramView.Box(0, 0, 200, 64)),
                Map.of(),
                "dark");

        JsonNode shapes = objectMapper.readTree(packager.toDocumentJson(builder.build(partial)))
                .get("pages").get(0).get("shapes");

        assertThat(shapes.toString()).contains("log-order-svc").doesNotContain("log-quote-svc");
        assertThat(objectMapper.readTree(packager.toDocumentJson(builder.build(partial)))
                .get("pages").get(0).get("lines"))
                .as("edges to unexported shapes are dropped rather than dangling")
                .isEmpty();
    }

    @Test
    @DisplayName("An oversized document is refused with an actionable message")
    void refusesOversizedDocuments() {
        List<GraphNode> many = new java.util.ArrayList<>();
        Map<String, DiagramView.Box> positions = new java.util.HashMap<>();
        for (int i = 0; i < 20000; i++) {
            String key = "service-with-a-fairly-long-name-number-" + i;
            many.add(GraphNode.builder(key, key, NodeType.SERVICE).framework("Play Framework").build());
            positions.put(key, new DiagramView.Box(i * 10, i * 5, 220, 64));
        }
        DiagramView huge = new DiagramView(
                "Huge", new DependencyGraph(many, List.of()), positions, Map.of(), "dark");

        assertThatThrownBy(() -> packager.toDocumentJson(builder.build(huge)))
                .hasMessageContaining("too large");
    }

    private JsonNode shapes(DiagramView view) throws Exception {
        return objectMapper.readTree(packager.toDocumentJson(builder.build(view)))
                .get("pages").get(0).get("shapes");
    }

    private JsonNode lines(DiagramView view) throws Exception {
        return objectMapper.readTree(packager.toDocumentJson(builder.build(view)))
                .get("pages").get(0).get("lines");
    }

    private JsonNode byText(JsonNode shapes, String text) {
        for (JsonNode shape : shapes) {
            if (shape.path("text").asText("").contains(text)) {
                return shape;
            }
        }
        throw new AssertionError("No shape containing '" + text + "' in " + shapes);
    }
}
