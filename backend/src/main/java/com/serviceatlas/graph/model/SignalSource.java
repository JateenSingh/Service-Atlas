package com.serviceatlas.graph.model;

/**
 * Which extraction rule produced a piece of evidence. Kept explicit so the inspector panel can
 * say <em>why</em> an edge exists, and so a user can judge a MEDIUM/LOW edge for themselves.
 */
public enum SignalSource {
    /** FR-3.1 — libraryDependencies coordinate matching another discovered service. */
    BUILD_DEPENDENCY(Confidence.HIGH, EdgeType.ARTIFACT, false),
    /** FR-3.2 — a URL/host/service-discovery key in application.conf or reference.conf. */
    CONFIG_REFERENCE(Confidence.HIGH, EdgeType.HTTP, true),
    /** FR-3.3 — an HTTP client call in source whose target resolves to another service. */
    HTTP_CLIENT_CALL(Confidence.MEDIUM, EdgeType.HTTP, true),
    /** FR-3.5 — a cross-service package import. */
    SOURCE_IMPORT(Confidence.LOW, EdgeType.ARTIFACT, false),
    /** FR-3.6 — a message produced to a topic. */
    MESSAGING_PRODUCER(Confidence.MEDIUM, EdgeType.MESSAGING, false),
    /** FR-3.6 — a message consumed from a topic. */
    MESSAGING_CONSUMER(Confidence.MEDIUM, EdgeType.MESSAGING, false),
    /** A datastore named by a connection URL or equivalent in configuration. */
    DATASTORE_CONNECTION(Confidence.HIGH, EdgeType.PERSISTENCE, false),
    /** A datastore this service owns the schema of — it ships migrations or evolutions for it. */
    DATASTORE_SCHEMA(Confidence.HIGH, EdgeType.PERSISTENCE, false),
    /** A datastore inferred from a driver dependency, with no connection details to confirm it. */
    DATASTORE_DRIVER(Confidence.LOW, EdgeType.PERSISTENCE, false),
    /** Publishes to a Google Pub/Sub topic. */
    PUBSUB_PUBLISHER(Confidence.MEDIUM, EdgeType.MESSAGING, false),
    /** Subscribes to a Google Pub/Sub topic. */
    PUBSUB_SUBSCRIBER(Confidence.MEDIUM, EdgeType.MESSAGING, false),
    /** A user-created edge (FR-4.4). Always HIGH: the user asserted it. */
    MANUAL(Confidence.HIGH, EdgeType.UNKNOWN, false);

    private final Confidence defaultConfidence;
    private final EdgeType defaultEdgeType;
    private final boolean createsExternalNodes;

    SignalSource(Confidence defaultConfidence, EdgeType defaultEdgeType, boolean createsExternalNodes) {
        this.defaultConfidence = defaultConfidence;
        this.defaultEdgeType = defaultEdgeType;
        this.createsExternalNodes = createsExternalNodes;
    }

    /**
     * Whether an unresolved target should become an {@link NodeType#EXTERNAL} node (FR-4.1).
     *
     * <p>False for build and import signals on purpose: an unmatched {@code libraryDependencies}
     * entry is almost always a third-party library, not a service, and turning every one of those
     * into a node would bury the architecture in scalatest and logback. An unmatched <em>host</em>
     * in config or an HTTP call, by contrast, really is a remote service worth drawing.
     */
    public boolean createsExternalNodes() {
        return createsExternalNodes;
    }

    public Confidence defaultConfidence() {
        return defaultConfidence;
    }

    public EdgeType defaultEdgeType() {
        return defaultEdgeType;
    }
}
