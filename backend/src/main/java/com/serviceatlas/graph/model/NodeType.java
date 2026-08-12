package com.serviceatlas.graph.model;

/** Node kinds in the dependency graph (FR-4.1). */
public enum NodeType {
    /** A repository discovered in the scanned folder. */
    SERVICE,
    /** A module of a multi-module SBT build that looks like a deployable of its own (FR-2.4). */
    SUB_MODULE,
    /** A Kafka/RabbitMQ topic or queue acting as an intermediary (FR-3.6). */
    TOPIC,
    /** Referenced but not found in the scanned folder — third party, or simply not cloned. */
    EXTERNAL
}
