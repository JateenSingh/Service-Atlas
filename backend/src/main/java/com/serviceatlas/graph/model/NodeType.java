package com.serviceatlas.graph.model;

/** Node kinds in the dependency graph (FR-4.1). */
public enum NodeType {
    /** A repository discovered in the scanned folder. */
    SERVICE,
    /** A module of a multi-module SBT build that looks like a deployable of its own (FR-2.4). */
    SUB_MODULE,
    /** A Kafka, RabbitMQ or Google Pub/Sub topic acting as an intermediary (FR-3.6). */
    TOPIC,
    /**
     * A database, cache or other datastore a service reads and writes.
     *
     * <p>Keyed by engine and database name rather than by owning service, so two services pointed
     * at the same database converge on one node — a shared datastore is coupling, and the diagram
     * should say so.
     */
    DATASTORE,
    /** Referenced but not found in the scanned folder — third party, or simply not cloned. */
    EXTERNAL
}
