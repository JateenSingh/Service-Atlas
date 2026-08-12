package com.serviceatlas.export.importformat;

import com.serviceatlas.export.DiagramPalette;
import com.serviceatlas.export.DiagramView;
import com.serviceatlas.graph.model.EdgeType;
import com.serviceatlas.graph.model.GraphEdge;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.graph.model.NodeType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns the on-screen diagram into a Lucid Standard Import document (FR-6.1, ADR-003).
 *
 * <p>All Lucid schema knowledge is confined here and in {@link LucidDocument}. Everything else in
 * the codebase — including the REST-API export path — goes through this builder, so the file
 * download and the API push can never produce different documents.
 *
 * <p>Positions come from the client's current layout, so the imported diagram matches what the user
 * was looking at rather than being re-laid-out by Lucid.
 */
@Component
public class LucidDocumentBuilder {

    /** Lucid ids must be unique within the document; keys are mapped to stable generated ids. */
    private static final String PAGE_ID = "page-1";

    /** Legend geometry, placed under the diagram. */
    private static final double LEGEND_GAP = 72;
    private static final double LEGEND_ROW_HEIGHT = 26;
    private static final double LEGEND_WIDTH = 260;

    public LucidDocument build(DiagramView view) {
        DiagramPalette palette = DiagramPalette.forView(view);
        DiagramView.Bounds bounds = view.bounds();

        Map<String, String> shapeIds = new LinkedHashMap<>();
        List<LucidDocument.Shape> shapes = new ArrayList<>();
        List<LucidDocument.Line> lines = new ArrayList<>();

        for (GraphNode node : view.graph().nodes()) {
            DiagramView.Box box = view.positions().get(node.key());
            if (box == null) {
                continue; // not laid out, therefore not on screen, therefore not exported
            }
            String shapeId = idFor(node.key());
            shapeIds.put(node.key(), shapeId);
            shapes.add(shapeFor(node, box, shapeId, palette, bounds));
        }

        for (GraphEdge edge : view.graph().edges()) {
            String source = shapeIds.get(edge.sourceKey());
            String target = shapeIds.get(edge.targetKey());
            if (source == null || target == null) {
                continue;
            }
            lines.add(lineFor(edge, source, target, palette));
        }

        shapes.addAll(legendShapes(palette, bounds));

        return new LucidDocument(
                LucidDocument.VERSION,
                List.of(new LucidDocument.Page(PAGE_ID, pageTitle(view), shapes, lines)));
    }

    private LucidDocument.Shape shapeFor(GraphNode node, DiagramView.Box box, String shapeId,
                                         DiagramPalette palette, DiagramView.Bounds bounds) {
        String colour = palette.nodeColour(node);
        boolean external = node.type() == NodeType.EXTERNAL;

        LucidDocument.Style style = new LucidDocument.Style(
                // External services are drawn as outlines: they are referenced, not owned.
                LucidDocument.Fill.colour(external ? palette.background() : palette.surface()),
                new LucidDocument.Stroke(
                        external ? palette.external() : colour,
                        external ? 1.5 : 2.0,
                        external ? LucidDocument.Stroke.DASHED : LucidDocument.Stroke.SOLID));

        return new LucidDocument.Shape(
                shapeId,
                shapeTypeFor(node),
                new LucidDocument.BoundingBox(
                        round(box.x() - bounds.x()),
                        round(box.y() - bounds.y()),
                        round(box.width()),
                        round(box.height())),
                style,
                labelFor(node));
    }

    private LucidDocument.Line lineFor(GraphEdge edge, String sourceShapeId, String targetShapeId,
                                       DiagramPalette palette) {
        LucidDocument.Stroke stroke = new LucidDocument.Stroke(
                palette.edgeColour(edge.type()),
                strokeWidthFor(edge),
                strokeStyleFor(edge.type()));

        return new LucidDocument.Line(
                idFor(edge.id()),
                LucidDocument.Line.ELBOW,
                stroke,
                // Right edge of the source to the left edge of the target: the left-to-right
                // reading the canvas uses by default.
                LucidDocument.Endpoint.on(sourceShapeId, 1, 0.5, LucidDocument.Endpoint.NONE),
                LucidDocument.Endpoint.on(targetShapeId, 0, 0.5, LucidDocument.Endpoint.ARROW),
                edge.label() == null || edge.label().isBlank()
                        ? null
                        : List.of(LucidDocument.TextBlock.midpoint(edge.label())));
    }

    /**
     * A legend, because a shared diagram is read by people who were not in the room when it was
     * made (FR-6.1).
     */
    private List<LucidDocument.Shape> legendShapes(DiagramPalette palette, DiagramView.Bounds bounds) {
        List<LucidDocument.Shape> legend = new ArrayList<>();
        double top = bounds.height() + LEGEND_GAP;

        legend.add(textShape("legend-title", 0, top, LEGEND_WIDTH, LEGEND_ROW_HEIGHT,
                "Legend", palette, palette.textMuted()));

        List<String> rows = List.of(
                "Solid line — HTTP call",
                "Dashed line — messaging via a topic",
                "Dotted line — build dependency",
                "Faded line — lower confidence",
                "Dashed outline — service not found locally");

        for (int i = 0; i < rows.size(); i++) {
            legend.add(textShape(
                    "legend-" + i,
                    0,
                    top + LEGEND_ROW_HEIGHT * (i + 1),
                    LEGEND_WIDTH,
                    LEGEND_ROW_HEIGHT,
                    rows.get(i),
                    palette,
                    palette.textMuted()));
        }
        return legend;
    }

    private LucidDocument.Shape textShape(String id, double x, double y, double width, double height,
                                          String text, DiagramPalette palette, String stroke) {
        return new LucidDocument.Shape(
                idFor(id),
                LucidDocument.ShapeTypes.RECTANGLE,
                new LucidDocument.BoundingBox(round(x), round(y), round(width), round(height)),
                new LucidDocument.Style(
                        LucidDocument.Fill.colour(palette.background()),
                        new LucidDocument.Stroke(palette.background(), 0, LucidDocument.Stroke.SOLID)),
                text);
    }

    private static String shapeTypeFor(GraphNode node) {
        return node.type() == NodeType.TOPIC
                ? LucidDocument.ShapeTypes.ELLIPSE
                : LucidDocument.ShapeTypes.ROUNDED_RECTANGLE;
    }

    /** Two lines: the service name, then what it is — the same information the canvas shows. */
    private static String labelFor(GraphNode node) {
        String subtitle = switch (node.type()) {
            case TOPIC -> "topic";
            case EXTERNAL -> "external";
            case SUB_MODULE -> "module";
            case SERVICE -> node.framework() == null || node.framework().equals("Unknown")
                    ? "service"
                    : node.framework();
        };
        return node.displayName() + "\n" + subtitle;
    }

    private static double strokeWidthFor(GraphEdge edge) {
        return switch (edge.confidence()) {
            case HIGH -> 2.0;
            case MEDIUM -> 1.5;
            case LOW -> 1.0;
        };
    }

    private static String strokeStyleFor(EdgeType type) {
        return switch (type) {
            case MESSAGING -> LucidDocument.Stroke.DASHED;
            case ARTIFACT -> LucidDocument.Stroke.DOTTED;
            case UNKNOWN -> LucidDocument.Stroke.DASHED;
            case HTTP -> LucidDocument.Stroke.SOLID;
        };
    }

    private static String pageTitle(DiagramView view) {
        String title = view.title();
        return title == null || title.isBlank() ? "Service Atlas" : title;
    }

    /**
     * Deterministic ids derived from our own keys: Lucid needs uniqueness, and a stable mapping
     * makes the golden-file test meaningful and re-exports diff-able.
     */
    private static String idFor(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
