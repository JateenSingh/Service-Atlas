package com.serviceatlas.graph.model;

/**
 * How much we trust an extracted edge (FR-3). The ordinal ordering is meaningful: filters compare
 * with {@link #atLeast(Confidence)} and the canvas maps this to edge opacity (FR-5.6).
 */
public enum Confidence {
    LOW(0.35),
    MEDIUM(0.65),
    HIGH(1.0);

    private final double weight;

    Confidence(double weight) {
        this.weight = weight;
    }

    public double weight() {
        return weight;
    }

    public boolean atLeast(Confidence threshold) {
        return this.ordinal() >= threshold.ordinal();
    }

    /** When several signals support one edge, the strongest wins. */
    public static Confidence strongest(Confidence a, Confidence b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
