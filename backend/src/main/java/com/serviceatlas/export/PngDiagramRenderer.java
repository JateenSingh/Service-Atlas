package com.serviceatlas.export;

import com.serviceatlas.graph.model.GraphEdge;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.graph.model.NodeType;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Rasterises the diagram with Java2D (FR-6.4).
 *
 * <p>Drawn directly from the graph model rather than by rasterising the SVG. That avoids pulling in
 * an SVG toolkit for one endpoint (§11: keep the dependency footprint small) and keeps the output
 * on the same {@link DiagramPalette} as every other export.
 */
@Component
public class PngDiagramRenderer {

    private static final double PADDING = 48;
    private static final double LEGEND_HEIGHT = 104;
    /** 2× so the file is usable in slides and documents rather than only on screen. */
    private static final double SCALE = 2.0;
    private static final int MAX_PIXELS = 40_000_000;

    public byte[] render(DiagramView view) {
        DiagramPalette palette = DiagramPalette.forView(view);
        DiagramView.Bounds bounds = view.bounds();

        int width = (int) Math.ceil((bounds.width() + PADDING * 2) * SCALE);
        int height = (int) Math.ceil((bounds.height() + PADDING * 2 + LEGEND_HEIGHT) * SCALE);
        if ((long) width * height > MAX_PIXELS) {
            throw new IllegalStateException(
                    "This diagram is too large to rasterise. Filter the view down, or export SVG instead.");
        }

        BufferedImage image = new BufferedImage(
                Math.max(width, 64), Math.max(height, 64), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            graphics.setColor(colour(palette.background()));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.scale(SCALE, SCALE);
            graphics.translate(PADDING - bounds.x(), PADDING - bounds.y());

            for (GraphEdge edge : view.graph().edges()) {
                drawEdge(graphics, view, palette, edge);
            }
            for (GraphNode node : view.graph().nodes()) {
                DiagramView.Box box = view.positions().get(node.key());
                if (box != null) {
                    drawNode(graphics, node, box, palette);
                }
            }
            drawLegend(graphics, palette, bounds);
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode the PNG", e);
        }
    }

    /** The same legend the SVG and Lucid exports carry — a shared diagram needs its key. */
    private void drawLegend(Graphics2D graphics, DiagramPalette palette, DiagramView.Bounds bounds) {
        double x = bounds.x();
        double y = bounds.y() + bounds.height() + 48;

        graphics.setColor(colour(palette.textMuted()));
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        graphics.drawString("LEGEND", (float) x, (float) y);

        List<com.serviceatlas.graph.model.EdgeType> types = List.of(
                com.serviceatlas.graph.model.EdgeType.HTTP,
                com.serviceatlas.graph.model.EdgeType.MESSAGING,
                com.serviceatlas.graph.model.EdgeType.ARTIFACT);
        List<String> labels = List.of("HTTP call", "Messaging", "Build dependency");

        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        for (int i = 0; i < types.size(); i++) {
            float lineY = (float) (y + 18 + i * 18);
            float[] dash = DiagramPalette.dashFor(types.get(i));
            graphics.setColor(colour(palette.edgeColour(types.get(i))));
            graphics.setStroke(dash == null
                    ? new BasicStroke(1.8f)
                    : new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
            graphics.drawLine((int) x, (int) lineY, (int) x + 28, (int) lineY);

            graphics.setColor(colour(palette.textMuted()));
            graphics.drawString(labels.get(i), (float) x + 38, lineY + 4);
        }

        graphics.drawString(
                "Line opacity shows confidence · dashed outline means the service was not found locally",
                (float) x + 200,
                (float) y + 18);
    }

    private void drawEdge(Graphics2D graphics, DiagramView view, DiagramPalette palette, GraphEdge edge) {
        DiagramView.Box source = view.positions().get(edge.sourceKey());
        DiagramView.Box target = view.positions().get(edge.targetKey());
        if (source == null || target == null) {
            return;
        }
        DiagramView.Route route = view.routes().get(edge.id());
        List<DiagramView.Point> points = route != null && route.points().size() >= 2
                ? route.points()
                : List.of(
                        new DiagramView.Point(source.x() + source.width(), source.centreY()),
                        new DiagramView.Point(target.x(), target.centreY()));

        GeneralPath path = new GeneralPath();
        path.moveTo(points.get(0).x(), points.get(0).y());
        for (int i = 1; i < points.size(); i++) {
            path.lineTo(points.get(i).x(), points.get(i).y());
        }

        float[] dash = DiagramPalette.dashFor(edge.type());
        graphics.setStroke(dash == null
                ? new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                : new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
        graphics.setColor(withAlpha(
                colour(palette.edgeColour(edge.type())), DiagramPalette.opacityFor(edge.confidence())));
        graphics.draw(path);

        DiagramView.Point last = points.get(points.size() - 1);
        DiagramView.Point previous = points.get(points.size() - 2);
        drawArrowHead(graphics, previous, last);

        if (edge.label() != null && !edge.label().isBlank()) {
            DiagramView.Point middle = points.get(points.size() / 2);
            graphics.setColor(colour(palette.textMuted()));
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            int textWidth = graphics.getFontMetrics().stringWidth(edge.label());
            graphics.drawString(edge.label(), (float) (middle.x() - textWidth / 2.0), (float) middle.y() - 6);
        }
    }

    private void drawArrowHead(Graphics2D graphics, DiagramView.Point from, DiagramView.Point to) {
        double angle = Math.atan2(to.y() - from.y(), to.x() - from.x());
        double size = 7;
        GeneralPath head = new GeneralPath();
        head.moveTo(to.x(), to.y());
        head.lineTo(to.x() - size * Math.cos(angle - Math.PI / 7), to.y() - size * Math.sin(angle - Math.PI / 7));
        head.lineTo(to.x() - size * Math.cos(angle + Math.PI / 7), to.y() - size * Math.sin(angle + Math.PI / 7));
        head.closePath();
        graphics.setStroke(new BasicStroke(1f));
        graphics.fill(head);
    }

    private void drawNode(Graphics2D graphics, GraphNode node, DiagramView.Box box, DiagramPalette palette) {
        boolean topic = node.type() == NodeType.TOPIC;
        boolean external = node.type() == NodeType.EXTERNAL;
        double arc = topic ? box.height() : 10;

        RoundRectangle2D shape = new RoundRectangle2D.Double(
                box.x(), box.y(), box.width(), box.height(), arc, arc);

        if (!external) {
            graphics.setColor(colour(palette.surface()));
            graphics.fill(shape);
        }
        graphics.setColor(colour(external ? palette.external() : topic ? palette.topic() : palette.border()));
        graphics.setStroke(external
                ? new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {5f, 4f}, 0f)
                : new BasicStroke(1f));
        graphics.draw(shape);

        String colour = palette.nodeColour(node);
        if (!topic) {
            graphics.setColor(colour(colour));
            graphics.fillRect((int) box.x(), (int) (box.y() + 8), 4, (int) (box.height() - 16));
        }

        graphics.setColor(colour(palette.text()));
        graphics.setFont(new Font(Font.SANS_SERIF, topic ? Font.PLAIN : Font.BOLD, topic ? 12 : 13));
        graphics.drawString(
                node.displayName(),
                (float) box.x() + 16,
                (float) (topic ? box.y() + box.height() / 2 + 4 : box.y() + 27));

        if (!topic) {
            graphics.setColor(colour(colour));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            graphics.drawString(subtitle(node), (float) box.x() + 16, (float) box.y() + 45);
        }
    }

    private static String subtitle(GraphNode node) {
        return switch (node.type()) {
            case EXTERNAL -> "external";
            case SUB_MODULE -> "module";
            case TOPIC -> "topic";
            case SERVICE -> node.framework() == null || node.framework().equals("Unknown")
                    ? "service"
                    : node.framework();
        };
    }

    private static Color colour(String hex) {
        return Color.decode(hex);
    }

    private static Color withAlpha(Color base, double opacity) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(),
                (int) Math.round(Math.max(0, Math.min(1, opacity)) * 255));
    }
}
