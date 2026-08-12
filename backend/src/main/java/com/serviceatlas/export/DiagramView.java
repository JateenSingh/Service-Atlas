package com.serviceatlas.export;

import com.serviceatlas.graph.model.DependencyGraph;
import java.util.Map;

/**
 * Exactly what the user is looking at, posted by the client for export (FR-6.3).
 *
 * <p>The client sends the view rather than the server recomputing it, for two reasons: the filters
 * live in the browser, and so does the layout — ELK runs there (ADR-001). Asking the server to
 * reproduce both would mean a second layout engine that could disagree with the picture on screen,
 * which is precisely what "export what I see" rules out.
 *
 * @param title    diagram title, normally the workspace name
 * @param graph    the filtered graph
 * @param positions node key → box, in layout coordinates
 * @param routes   edge id → routed polyline; optional, straight lines are used when absent
 * @param theme    {@code dark} or {@code light}; picks the palette
 */
public record DiagramView(
        String title,
        DependencyGraph graph,
        Map<String, Box> positions,
        Map<String, Route> routes,
        String theme) {

    public DiagramView {
        graph = graph == null ? DependencyGraph.empty() : graph;
        positions = positions == null ? Map.of() : positions;
        routes = routes == null ? Map.of() : routes;
        theme = theme == null ? "dark" : theme;
    }

    public boolean isDark() {
        return !"light".equalsIgnoreCase(theme);
    }

    public record Box(double x, double y, double width, double height) {

        public double centreX() {
            return x + width / 2;
        }

        public double centreY() {
            return y + height / 2;
        }
    }

    public record Route(String id, java.util.List<Point> points) {

        public Route {
            points = points == null ? java.util.List.of() : java.util.List.copyOf(points);
        }
    }

    public record Point(double x, double y) {
    }

    /** Bounding box of everything drawn, used to size the output. */
    public Bounds bounds() {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (Box box : positions.values()) {
            minX = Math.min(minX, box.x());
            minY = Math.min(minY, box.y());
            maxX = Math.max(maxX, box.x() + box.width());
            maxY = Math.max(maxY, box.y() + box.height());
        }
        for (Route route : routes.values()) {
            for (Point point : route.points()) {
                minX = Math.min(minX, point.x());
                minY = Math.min(minY, point.y());
                maxX = Math.max(maxX, point.x());
                maxY = Math.max(maxY, point.y());
            }
        }
        if (minX > maxX) {
            return new Bounds(0, 0, 800, 400);
        }
        return new Bounds(minX, minY, maxX - minX, maxY - minY);
    }

    public record Bounds(double x, double y, double width, double height) {
    }
}
