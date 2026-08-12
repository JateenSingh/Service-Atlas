package com.serviceatlas.persistence;

/** Per-repository progress, surfaced live in the scan screen (FR-7.1, FR-7.2, FR-7.3). */
public enum RepoScanStatus {
    DISCOVERED,
    PARSING,
    DONE,
    /** Parsing failed; the repo still becomes a node, with a warning badge (FR-7.2). */
    FAILED,
    /** Unchanged since the previous scan, so its previous result was reused (FR-7.3). */
    UNCHANGED,
    /** No parser claimed the repository. */
    SKIPPED
}
