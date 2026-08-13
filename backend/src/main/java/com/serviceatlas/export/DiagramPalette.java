package com.serviceatlas.export;

import com.serviceatlas.graph.model.EdgeType;
import com.serviceatlas.graph.model.GraphNode;
import java.util.Map;

/**
 * The visual encoding, in one place, shared by every exporter (FR-5.6, FR-6.1).
 *
 * <p>Colours are plain hex rather than CSS variables: an exported file has no stylesheet, and a
 * Lucid shape has no notion of a theme. Keeping the SVG, PNG and Lucid exports on one palette is
 * what stops the three outputs from drifting into three different-looking diagrams.
 */
public record DiagramPalette(
        String background,
        String surface,
        String border,
        String text,
        String textMuted,
        String external,
        String topic,
        String datastore,
        Map<String, String> frameworks,
        Map<EdgeType, String> edges) {

    public static final DiagramPalette DARK = new DiagramPalette(
            "#0d1218",
            "#121821",
            "#2a3644",
            "#e6edf3",
            "#8496a6",
            "#64748b",
            "#f472b6",
            "#22d3ee",
            Map.of(
                    "Play Framework", "#4ade80",
                    "Akka HTTP", "#c084fc",
                    "Pekko HTTP", "#a78bfa",
                    "http4s", "#38bdf8",
                    "gRPC", "#fbbf24",
                    "Unknown", "#94a3b8"),
            Map.of(
                    EdgeType.HTTP, "#60a5fa",
                    EdgeType.ARTIFACT, "#a3a3a3",
                    EdgeType.MESSAGING, "#f472b6",
                    EdgeType.PERSISTENCE, "#22d3ee",
                    EdgeType.UNKNOWN, "#737373"));

    public static final DiagramPalette LIGHT = new DiagramPalette(
            "#ffffff",
            "#ffffff",
            "#d3dae2",
            "#16202b",
            "#6b7c8d",
            "#475569",
            "#db2777",
            "#0e7490",
            Map.of(
                    "Play Framework", "#16a34a",
                    "Akka HTTP", "#7c3aed",
                    "Pekko HTTP", "#6d28d9",
                    "http4s", "#0284c7",
                    "gRPC", "#b45309",
                    "Unknown", "#64748b"),
            Map.of(
                    EdgeType.HTTP, "#1d6fe0",
                    EdgeType.ARTIFACT, "#64748b",
                    EdgeType.MESSAGING, "#db2777",
                    EdgeType.PERSISTENCE, "#0e7490",
                    EdgeType.UNKNOWN, "#94a3b8"));

    public static DiagramPalette forView(DiagramView view) {
        return view.isDark() ? DARK : LIGHT;
    }

    public String nodeColour(GraphNode node) {
        return switch (node.type()) {
            case TOPIC -> topic;
            case DATASTORE -> datastore;
            case EXTERNAL -> external;
            default -> frameworks.getOrDefault(
                    node.framework() == null ? "Unknown" : node.framework(),
                    frameworks.get("Unknown"));
        };
    }

    public String edgeColour(EdgeType type) {
        return edges.getOrDefault(type, edges.get(EdgeType.UNKNOWN));
    }

    /** Dash pattern per edge type (FR-5.6): solid HTTP, dashed messaging, dotted artifact. */
    public static float[] dashFor(EdgeType type) {
        return switch (type) {
            case MESSAGING -> new float[] {8f, 5f};
            case ARTIFACT -> new float[] {2f, 4f};
            case UNKNOWN -> new float[] {4f, 4f};
            // Long dash with a gap: reads as a link to storage rather than a call.
            case PERSISTENCE -> new float[] {10f, 3f};
            case HTTP -> null;
        };
    }

    public static String dashArrayFor(EdgeType type) {
        float[] dash = dashFor(type);
        if (dash == null) {
            return null;
        }
        StringBuilder pattern = new StringBuilder();
        for (float value : dash) {
            if (pattern.length() > 0) {
                pattern.append(' ');
            }
            pattern.append((int) value);
        }
        return pattern.toString();
    }

    /** Opacity carries confidence, so a weak edge looks weak. */
    public static double opacityFor(com.serviceatlas.graph.model.Confidence confidence) {
        return switch (confidence) {
            case HIGH -> 0.95;
            case MEDIUM -> 0.66;
            case LOW -> 0.40;
        };
    }
}
