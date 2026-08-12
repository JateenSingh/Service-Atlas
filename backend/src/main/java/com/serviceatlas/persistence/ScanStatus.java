package com.serviceatlas.persistence;

/** Lifecycle of a scan (FR-7.1). */
public enum ScanStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    /** Every repository failed, or the scan itself could not start. */
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
