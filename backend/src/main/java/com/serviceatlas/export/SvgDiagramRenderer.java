package com.serviceatlas.export;

import com.serviceatlas.graph.model.EdgeType;
import com.serviceatlas.graph.model.GraphEdge;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.graph.model.NodeType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders the current view as a standalone SVG document (FR-6.4).
 *
 * <p>Generated from the graph model rather than by serialising the browser's DOM: the on-screen
 * canvas takes its colours from CSS custom properties, which mean nothing once the file leaves the
 * app. Building it here produces a file that opens correctly in any viewer, and keeps SVG, PNG and
 * Lucid on the same palette.
 */
@Component
public class SvgDiagramRenderer {

    private static final double PADDING = 48;
    private static final double LEGEND_HEIGHT = 104;

    public String render(DiagramView view) {
        DiagramPalette palette = DiagramPalette.forView(view);
        DiagramView.Bounds bounds = view.bounds();
        double width = Math.max(bounds.width() + PADDING * 2, 480);
        double height = bounds.height() + PADDING * 2 + LEGEND_HEIGHT;

        List<String> parts = new ArrayList<>();
        parts.add("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + round(width) + "\" height=\""
                + round(height) + "\" viewBox=\"0 0 " + round(width) + " " + round(height)
                + "\" font-family=\"Inter, system-ui, sans-serif\">");
        parts.add(defs(palette));
        parts.add("<rect width=\"100%\" height=\"100%\" fill=\"" + palette.background() + "\"/>");

        if (view.title() != null && !view.title().isBlank()) {
            parts.add("<text x=\"" + round(PADDING) + "\" y=\"" + round(PADDING - 18) + "\" fill=\""
                    + palette.textMuted() + "\" font-size=\"13\">" + escape(view.title()) + "</text>");
        }

        // Edges first: nodes must sit on top of the lines that connect them.
        for (GraphEdge edge : view.graph().edges()) {
            renderEdge(view, palette, bounds, edge).ifPresent(parts::add);
        }
        for (GraphNode node : view.graph().nodes()) {
            DiagramView.Box box = view.positions().get(node.key());
            if (box != null) {
                parts.add(renderNode(node, box, bounds, palette));
            }
        }

        parts.add(legend(palette, PADDING, height - LEGEND_HEIGHT + 24));
        parts.add("</svg>");
        return String.join("\n", parts);
    }

    private String defs(DiagramPalette palette) {
        StringBuilder markers = new StringBuilder("<defs>");
        for (EdgeType type : EdgeType.values()) {
            markers.append("<marker id=\"arrow-").append(type.name().toLowerCase(java.util.Locale.ROOT))
                    .append("\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\"")
                    .append(" orient=\"auto-start-reverse\"><path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"")
                    .append(palette.edgeColour(type)).append("\"/></marker>");
        }
        return markers.append("</defs>").toString();
    }

    private java.util.Optional<String> renderEdge(DiagramView view, DiagramPalette palette,
                                                  DiagramView.Bounds bounds, GraphEdge edge) {
        DiagramView.Box source = view.positions().get(edge.sourceKey());
        DiagramView.Box target = view.positions().get(edge.targetKey());
        if (source == null || target == null) {
            return java.util.Optional.empty();
        }

        DiagramView.Route route = view.routes().get(edge.id());
        List<DiagramView.Point> points = route != null && route.points().size() >= 2
                ? route.points()
                : List.of(
                        new DiagramView.Point(source.x() + source.width(), source.centreY()),
                        new DiagramView.Point(target.x(), target.centreY()));

        StringBuilder path = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            DiagramView.Point point = points.get(i);
            path.append(i == 0 ? "M " : "L ")
                    .append(round(point.x() - bounds.x() + PADDING)).append(' ')
                    .append(round(point.y() - bounds.y() + PADDING)).append(' ');
        }

        StringBuilder svg = new StringBuilder("<path d=\"").append(path.toString().strip())
                .append("\" fill=\"none\" stroke=\"").append(palette.edgeColour(edge.type()))
                .append("\" stroke-width=\"1.6\" opacity=\"")
                .append(DiagramPalette.opacityFor(edge.confidence())).append('"');

        String dash = DiagramPalette.dashArrayFor(edge.type());
        if (dash != null) {
            svg.append(" stroke-dasharray=\"").append(dash).append('"');
        }
        svg.append(" marker-end=\"url(#arrow-")
                .append(edge.type().name().toLowerCase(java.util.Locale.ROOT)).append(")\"/>");

        if (edge.label() != null && !edge.label().isBlank()) {
            DiagramView.Point middle = points.get(points.size() / 2);
            svg.append("<text x=\"").append(round(middle.x() - bounds.x() + PADDING))
                    .append("\" y=\"").append(round(middle.y() - bounds.y() + PADDING - 6))
                    .append("\" fill=\"").append(palette.textMuted())
                    .append("\" font-size=\"10\" font-family=\"ui-monospace, monospace\" text-anchor=\"middle\">")
                    .append(escape(edge.label())).append("</text>");
        }
        return java.util.Optional.of(svg.toString());
    }

    private String renderNode(GraphNode node, DiagramView.Box box, DiagramView.Bounds bounds,
                              DiagramPalette palette) {
        double x = box.x() - bounds.x() + PADDING;
        double y = box.y() - bounds.y() + PADDING;
        String colour = palette.nodeColour(node);
        StringBuilder svg = new StringBuilder("<g transform=\"translate(")
                .append(round(x)).append(',').append(round(y)).append(")\">");

        if (node.type() == NodeType.DATASTORE) {
            // Storage cylinder: a rounded body plus an ellipse cap.
            double cap = Math.min(18, box.height() / 3.2);
            svg.append("<path d=\"M 0 ").append(round(cap / 2))
                    .append(" a ").append(round(box.width() / 2)).append(' ').append(round(cap / 2))
                    .append(" 0 0 1 ").append(round(box.width())).append(" 0")
                    .append(" v ").append(round(box.height() - cap))
                    .append(" a ").append(round(box.width() / 2)).append(' ').append(round(cap / 2))
                    .append(" 0 0 1 -").append(round(box.width())).append(" 0 z\"")
                    .append(" fill=\"").append(palette.surface()).append("\" stroke=\"")
                    .append(palette.datastore()).append("\" stroke-width=\"1.2\"/>")
                    .append("<ellipse cx=\"").append(round(box.width() / 2)).append("\" cy=\"")
                    .append(round(cap / 2)).append("\" rx=\"").append(round(box.width() / 2))
                    .append("\" ry=\"").append(round(cap / 2)).append("\" fill=\"")
                    .append(palette.surface()).append("\" stroke=\"").append(palette.datastore())
                    .append("\" stroke-width=\"1.2\"/>")
                    .append("<text x=\"14\" y=\"").append(round(cap + 20)).append("\" fill=\"")
                    .append(palette.text()).append("\" font-size=\"12.5\" font-weight=\"600\">")
                    .append(escape(node.displayName())).append("</text>")
                    .append("<text x=\"14\" y=\"").append(round(cap + 34)).append("\" fill=\"")
                    .append(palette.datastore()).append("\" font-size=\"10\">")
                    .append(escape(subtitle(node))).append("</text>");
        } else if (node.type() == NodeType.TOPIC) {
            svg.append("<rect width=\"").append(round(box.width())).append("\" height=\"")
                    .append(round(box.height())).append("\" rx=\"").append(round(box.height() / 2))
                    .append("\" fill=\"").append(palette.surface()).append("\" stroke=\"")
                    .append(palette.topic()).append("\" stroke-width=\"1.2\"/>")
                    .append("<text x=\"16\" y=\"").append(round(box.height() / 2 + 4))
                    .append("\" fill=\"").append(palette.text())
                    .append("\" font-size=\"12\" font-family=\"ui-monospace, monospace\">")
                    .append(escape(node.displayName())).append("</text>");
        } else {
            boolean external = node.type() == NodeType.EXTERNAL;
            svg.append("<rect width=\"").append(round(box.width())).append("\" height=\"")
                    .append(round(box.height())).append("\" rx=\"10\" fill=\"")
                    .append(external ? "none" : palette.surface()).append("\" stroke=\"")
                    .append(external ? palette.external() : palette.border()).append("\" stroke-width=\"1\"")
                    .append(external ? " stroke-dasharray=\"5 4\"" : "").append("/>")
                    .append("<rect x=\"0\" y=\"8\" width=\"4\" height=\"").append(round(box.height() - 16))
                    .append("\" fill=\"").append(colour).append("\"/>")
                    .append("<text x=\"16\" y=\"27\" fill=\"").append(palette.text())
                    .append("\" font-size=\"13.5\" font-weight=\"600\">")
                    .append(escape(node.displayName())).append("</text>")
                    .append("<text x=\"16\" y=\"45\" fill=\"").append(colour)
                    .append("\" font-size=\"10.5\" font-weight=\"500\">")
                    .append(escape(subtitle(node))).append("</text>");
        }
        return svg.append("</g>").toString();
    }

    private String legend(DiagramPalette palette, double x, double y) {
        StringBuilder svg = new StringBuilder("<g transform=\"translate(")
                .append(round(x)).append(',').append(round(y)).append(")\">")
                .append("<text x=\"0\" y=\"0\" fill=\"").append(palette.textMuted())
                .append("\" font-size=\"11\" font-weight=\"600\" letter-spacing=\"0.08em\">LEGEND</text>");

        List<String[]> entries = List.of(
                new String[] {"HTTP call", palette.edgeColour(EdgeType.HTTP), null},
                new String[] {"Messaging", palette.edgeColour(EdgeType.MESSAGING),
                        DiagramPalette.dashArrayFor(EdgeType.MESSAGING)},
                new String[] {"Datastore", palette.edgeColour(EdgeType.PERSISTENCE),
                        DiagramPalette.dashArrayFor(EdgeType.PERSISTENCE)},
                new String[] {"Build dependency", palette.edgeColour(EdgeType.ARTIFACT),
                        DiagramPalette.dashArrayFor(EdgeType.ARTIFACT)});

        for (int i = 0; i < entries.size(); i++) {
            String[] entry = entries.get(i);
            double offsetY = 22 + i * 18;
            svg.append("<line x1=\"0\" y1=\"").append(round(offsetY)).append("\" x2=\"28\" y2=\"")
                    .append(round(offsetY)).append("\" stroke=\"").append(entry[1])
                    .append("\" stroke-width=\"1.8\"")
                    .append(entry[2] == null ? "" : " stroke-dasharray=\"" + entry[2] + "\"").append("/>")
                    .append("<text x=\"38\" y=\"").append(round(offsetY + 4)).append("\" fill=\"")
                    .append(palette.textMuted()).append("\" font-size=\"11\">")
                    .append(escape(entry[0])).append("</text>");
        }

        svg.append("<text x=\"200\" y=\"22\" fill=\"").append(palette.textMuted())
                .append("\" font-size=\"11\">")
                .append("Line opacity shows confidence · dashed outline means the service was not found locally")
                .append("</text></g>");
        return svg.toString();
    }

    private static String subtitle(GraphNode node) {
        return switch (node.type()) {
            case EXTERNAL -> "external";
            case SUB_MODULE -> "module";
            case TOPIC -> "topic";
            case DATASTORE -> String.valueOf(node.metadata().getOrDefault("engine", "datastore"));
            case SERVICE -> node.framework() == null || node.framework().equals("Unknown")
                    ? "service"
                    : node.framework();
        };
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
