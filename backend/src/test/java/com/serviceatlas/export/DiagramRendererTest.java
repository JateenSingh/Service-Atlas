package com.serviceatlas.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.serviceatlas.graph.model.Confidence;
import com.serviceatlas.graph.model.DependencyGraph;
import com.serviceatlas.graph.model.EdgeType;
import com.serviceatlas.graph.model.GraphEdge;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.graph.model.NodeType;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-6.4 — the SVG and PNG exports. */
class DiagramRendererTest {

    private final SvgDiagramRenderer svg = new SvgDiagramRenderer();
    private final PngDiagramRenderer png = new PngDiagramRenderer();

    private DiagramView view(String theme) {
        DependencyGraph graph = new DependencyGraph(
                List.of(
                        GraphNode.builder("a", "log-order-svc", NodeType.SERVICE)
                                .framework("Play Framework").build(),
                        GraphNode.builder("b", "log-quote-svc", NodeType.SERVICE)
                                .framework("http4s").build(),
                        GraphNode.builder("topic:x", "order-events", NodeType.TOPIC).build(),
                        GraphNode.builder("external:y", "api.stripe.com", NodeType.EXTERNAL).build()),
                List.of(
                        new GraphEdge(null, "a", "b", EdgeType.HTTP, Confidence.HIGH, "GET /quotes", List.of()),
                        GraphEdge.of("a", "topic:x", EdgeType.MESSAGING, Confidence.MEDIUM, List.of()),
                        GraphEdge.of("a", "external:y", EdgeType.ARTIFACT, Confidence.LOW, List.of())));

        return new DiagramView(
                "Logistics",
                graph,
                Map.of(
                        "a", new DiagramView.Box(0, 0, 200, 64),
                        "b", new DiagramView.Box(320, 0, 200, 64),
                        "topic:x", new DiagramView.Box(320, 120, 180, 44),
                        "external:y", new DiagramView.Box(320, 240, 200, 64)),
                Map.of("a->b:HTTP", new DiagramView.Route(
                        "a->b:HTTP",
                        List.of(new DiagramView.Point(200, 32), new DiagramView.Point(320, 32)))),
                theme);
    }

    @Test
    @DisplayName("The SVG is standalone, sized to its content, and carries the legend")
    void rendersSvg() {
        String output = svg.render(view("dark"));

        assertThat(output).startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\"");
        assertThat(output).endsWith("</svg>");
        assertThat(output).contains("log-order-svc").contains("order-events").contains("api.stripe.com");
        assertThat(output).contains("LEGEND").contains("Logistics");
        assertThat(output).contains("marker-end=\"url(#arrow-http)\"");
    }

    @Test
    @DisplayName("FR-5.6: the encoding survives — dashes by type, opacity by confidence")
    void svgCarriesTheEncoding() {
        String output = svg.render(view("dark"));

        assertThat(output).contains("stroke-dasharray=\"8 5\"");   // messaging
        assertThat(output).contains("stroke-dasharray=\"2 4\"");   // artifact
        assertThat(output).contains("opacity=\"0.95\"");           // HIGH
        assertThat(output).contains("opacity=\"0.4\"");            // LOW
    }

    @Test
    @DisplayName("Light and dark themes produce different palettes")
    void themesDiffer() {
        assertThat(svg.render(view("dark"))).contains("#0d1218");
        assertThat(svg.render(view("light"))).contains("#ffffff").doesNotContain("#0d1218");
    }

    @Test
    @DisplayName("Text is escaped, so a service name cannot break the document")
    void escapesText() {
        DependencyGraph graph = new DependencyGraph(
                List.of(GraphNode.builder("a", "<script>&\"", NodeType.SERVICE).build()), List.of());
        DiagramView view = new DiagramView(
                "t", graph, Map.of("a", new DiagramView.Box(0, 0, 200, 64)), Map.of(), "dark");

        assertThat(svg.render(view)).contains("&lt;script&gt;&amp;&quot;").doesNotContain("<script>");
    }

    @Test
    @DisplayName("The PNG is a real image at 2x the layout size")
    void rendersPng() throws Exception {
        byte[] bytes = png.render(view("dark"));

        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).isNotNull();
        // Content is 520x304, plus 48px padding each side and the legend strip, doubled.
        assertThat(image.getWidth()).isEqualTo((int) Math.ceil((520 + 96) * 2.0));
        assertThat(image.getHeight()).isEqualTo((int) Math.ceil((304 + 96 + 104) * 2.0));
    }

    @Test
    @DisplayName("An empty view still renders rather than throwing")
    void emptyViewRenders() {
        DiagramView empty = new DiagramView("empty", DependencyGraph.empty(), Map.of(), Map.of(), "dark");

        assertThat(svg.render(empty)).contains("<svg");
        assertThat(png.render(empty)).isNotEmpty();
    }
}
