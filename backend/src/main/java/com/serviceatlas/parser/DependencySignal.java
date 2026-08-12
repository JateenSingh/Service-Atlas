package com.serviceatlas.parser;

import com.serviceatlas.graph.model.Confidence;
import com.serviceatlas.graph.model.EdgeType;
import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.SignalSource;

/**
 * A parser's raw finding: "this node references <em>something</em> called {@code targetHint}".
 *
 * <p>Parsers deliberately do <b>not</b> resolve the target. They cannot: resolution needs the full
 * set of discovered services, which only exists after every repository has been parsed. The
 * {@link com.serviceatlas.graph.GraphBuilder} does the resolving. This split is what lets a new
 * {@link LanguageParser} be added without touching graph code (FR-3.8).
 *
 * @param sourceNodeKey key of the node that owns the reference
 * @param targetHint    raw text naming the target — a host, an artifact coordinate, a config key
 * @param topicName     set only for messaging signals; the topic/queue the message flows through
 */
public record DependencySignal(
        String sourceNodeKey,
        String targetHint,
        String topicName,
        SignalSource source,
        EdgeType edgeType,
        Confidence confidence,
        Evidence evidence) {

    public static DependencySignal of(String sourceNodeKey, String targetHint, SignalSource source,
                                      Evidence evidence) {
        return new DependencySignal(
                sourceNodeKey, targetHint, null, source, source.defaultEdgeType(),
                source.defaultConfidence(), evidence);
    }

    public static DependencySignal messaging(String sourceNodeKey, String topicName, SignalSource source,
                                             Evidence evidence) {
        return new DependencySignal(
                sourceNodeKey, topicName, topicName, source, EdgeType.MESSAGING,
                source.defaultConfidence(), evidence);
    }

    public boolean isMessaging() {
        return topicName != null && !topicName.isBlank();
    }
}
