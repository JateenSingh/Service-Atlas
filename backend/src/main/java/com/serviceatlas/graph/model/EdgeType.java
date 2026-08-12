package com.serviceatlas.graph.model;

/** Edge kinds (FR-4.2), each with its own visual encoding (FR-5.6). */
public enum EdgeType {
    /** A synchronous call: config-declared URL or an HTTP client call in source. */
    HTTP,
    /** A build-level dependency on another service's published artifact. */
    ARTIFACT,
    /** Produce/consume through a topic or queue. */
    MESSAGING,
    /** A relationship we are confident exists but could not classify. */
    UNKNOWN
}
