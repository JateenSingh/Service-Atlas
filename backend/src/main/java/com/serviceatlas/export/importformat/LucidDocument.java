package com.serviceatlas.export.importformat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The Lucid Standard Import {@code document.json} model (FR-6.1).
 *
 * <p>This file and {@link LucidDocumentBuilder} are the only places in the codebase that know a
 * Lucid field name (ADR-003). Everything is {@code NON_NULL}: an unset optional field is omitted
 * rather than sent as {@code null}, which is the safest posture against a schema whose official
 * reference could not be reached from the build environment (see ADR-003 for what was verified and
 * how).
 *
 * <p>Shape, as corroborated across the reachable sources:
 *
 * <pre>{@code
 * { "version": 1,
 *   "pages": [ { "id", "title",
 *                "shapes": [ { "id", "type", "boundingBox": {x,y,w,h},
 *                              "style": { "fill": {"type":"color","color"},
 *                                         "stroke": {"color","width","style"} },
 *                              "text" } ],
 *                "lines":  [ { "id", "lineType", "stroke": {...},
 *                              "endpoint1": {"type":"shapeEndpoint","style","shapeId",
 *                                            "position":{x,y}},
 *                              "endpoint2": { ... } } ] } ] }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LucidDocument(int version, List<Page> pages) {

    /** The format version Lucid expects at the document root. */
    public static final int VERSION = 1;

    public LucidDocument {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(String id, String title, List<Shape> shapes, List<Line> lines) {

        public Page {
            shapes = shapes == null ? List.of() : List.copyOf(shapes);
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Shape(String id, String type, BoundingBox boundingBox, Style style, String text) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BoundingBox(double x, double y, double w, double h) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Style(Fill fill, Stroke stroke) {
    }

    /** Lucid expresses a flat colour fill as {@code {"type":"color","color":"#rrggbb"}}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fill(String type, String color) {

        public static Fill colour(String hex) {
            return new Fill("color", hex);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stroke(String color, double width, String style) {

        public static final String SOLID = "solid";
        public static final String DASHED = "dashed";
        public static final String DOTTED = "dotted";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(String id, String lineType, Stroke stroke, Endpoint endpoint1, Endpoint endpoint2,
                       List<TextBlock> text) {

        public static final String ELBOW = "elbow";
        public static final String STRAIGHT = "straight";
    }

    /**
     * A line end. {@code type} says what it attaches to; {@code position} is normalised 0…1 within
     * the shape's bounding box, so 0.5/1 is bottom-centre.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Endpoint(String type, String style, String shapeId, Position position) {

        public static final String SHAPE = "shapeEndpoint";
        public static final String NONE = "none";
        public static final String ARROW = "arrow";

        public static Endpoint on(String shapeId, double x, double y, String style) {
            return new Endpoint(SHAPE, style, shapeId, new Position(x, y));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Position(double x, double y) {
    }

    /** Text on a line, positioned along it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TextBlock(String text, Double position, String side) {

        public static TextBlock midpoint(String text) {
            return new TextBlock(text, 0.5, "middle");
        }
    }

    /** Shape library names used by the builder. */
    public static final class ShapeTypes {
        public static final String RECTANGLE = "rectangle";
        public static final String ROUNDED_RECTANGLE = "roundedRectangle";
        public static final String ELLIPSE = "ellipse";
        public static final String CYLINDER = "cylinder";

        private ShapeTypes() {
        }
    }
}
