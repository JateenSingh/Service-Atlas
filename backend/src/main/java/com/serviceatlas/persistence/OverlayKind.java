package com.serviceatlas.persistence;

/** The kinds of user edit stored as overlays (FR-4.4, FR-5.3). */
public enum OverlayKind {
    /** A node the user added by hand. */
    NODE_ADDED,
    /** A node hidden from the view; the parser may still produce it on the next scan. */
    NODE_HIDDEN,
    /** Free-text annotation or renamed label on an existing node. */
    NODE_ANNOTATION,
    /** An edge the user asserted. */
    EDGE_ADDED,
    /** An edge the user rejected — kept so a re-scan does not resurrect it silently. */
    EDGE_HIDDEN,
    /** Annotation on an existing edge. */
    EDGE_ANNOTATION,
    /** A dragged node position (FR-5.3). */
    LAYOUT_POSITION
}
