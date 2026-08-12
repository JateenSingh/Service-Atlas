package com.serviceatlas.parser.scala;

import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.SignalSource;
import com.serviceatlas.parser.DependencySignal;
import com.serviceatlas.parser.common.TextSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * FR-3.6, source half — Kafka and RabbitMQ produce/consume calls in Scala source.
 *
 * <p>The config half lives in {@link ConfigReferenceScanner}; both emit the same messaging signals,
 * and the graph builder turns them into {@code producer → topic → consumer} chains through a
 * dedicated topic node.
 */
@Component
public final class MessagingScanner implements ScalaSignalScanner {

    /** {@code new ProducerRecord[String, String]("order-events", key, payload)} */
    private static final Pattern PRODUCER_RECORD = Pattern.compile(
            "ProducerRecord\\s*(?:\\[[^]]*])?\\s*\\(\\s*\"([^\"]+)\"");

    /** {@code Producer.plainSink}, {@code SendProducer(...).send("topic", ...)} and friends. */
    private static final Pattern PRODUCER_SEND = Pattern.compile(
            "(?:producer|sendProducer|publisher)\\.(?:send|publish)\\s*\\(\\s*\"([^\"]+)\"");

    /** {@code consumer.subscribe(List("order-events").asJava)} / {@code Subscriptions.topics("x")} */
    private static final Pattern CONSUMER_SUBSCRIBE = Pattern.compile(
            "(?:subscribe|Subscriptions\\.topics|topicPattern)\\s*\\(\\s*(?:List|Set|Seq|java\\.util\\.Arrays\\.asList)?\\s*\\(?\\s*\"([^\"]+)\"");

    /** RabbitMQ: {@code channel.basicPublish("exchange", "routing.key", ...)} */
    private static final Pattern RABBIT_PUBLISH = Pattern.compile(
            "basicPublish\\s*\\(\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"");

    /** RabbitMQ: {@code channel.basicConsume("queue-name", ...)} */
    private static final Pattern RABBIT_CONSUME = Pattern.compile("basicConsume\\s*\\(\\s*\"([^\"]+)\"");

    /** Topic names held in a constant: {@code val Topic = "order-events"} used by a send/subscribe. */
    private static final Pattern TOPIC_CONSTANT_USE = Pattern.compile(
            "(?:ProducerRecord\\s*(?:\\[[^]]*])?\\s*\\(|\\.send\\s*\\(|subscribe\\s*\\(\\s*(?:List|Set|Seq)?\\s*\\(?)"
                    + "\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*[,)]");

    @Override
    public String id() {
        return "scala-messaging";
    }

    @Override
    public List<DependencySignal> scan(ScalaScanContext context) {
        ScalaSources sources = ScalaSources.read(context.files());
        List<DependencySignal> signals = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (TextSource source : sources.sources()) {
            Map<String, String> values = ScalaSources.stringValues(source);

            collect(signals, seen, context, source, PRODUCER_RECORD, 1, SignalSource.MESSAGING_PRODUCER, "Kafka producer record");
            collect(signals, seen, context, source, PRODUCER_SEND, 1, SignalSource.MESSAGING_PRODUCER, "Message published");
            collect(signals, seen, context, source, CONSUMER_SUBSCRIBE, 1, SignalSource.MESSAGING_CONSUMER, "Kafka subscription");
            collect(signals, seen, context, source, RABBIT_PUBLISH, 2, SignalSource.MESSAGING_PRODUCER, "RabbitMQ publish");
            collect(signals, seen, context, source, RABBIT_CONSUME, 1, SignalSource.MESSAGING_CONSUMER, "RabbitMQ consume");

            // Topic names referenced through a constant rather than written inline.
            for (TextSource.Match match : source.matches(TOPIC_CONSTANT_USE)) {
                String constant = values.get(match.group(1));
                if (constant == null || constant.isBlank() || constant.contains(" ")) {
                    continue;
                }
                boolean producing = match.text().contains("ProducerRecord") || match.text().contains(".send");
                SignalSource signalSource =
                        producing ? SignalSource.MESSAGING_PRODUCER : SignalSource.MESSAGING_CONSUMER;
                addSignal(signals, seen, context, source, match, constant, signalSource, "topic constant");
            }
        }
        return signals;
    }

    private void collect(List<DependencySignal> signals, Set<String> seen, ScalaScanContext context,
                         TextSource source, Pattern pattern, int group, SignalSource signalSource, String how) {
        for (TextSource.Match match : source.matches(pattern)) {
            String topic = match.group(group);
            if (topic == null || topic.isBlank() || topic.contains(" ") || topic.startsWith("http")) {
                continue;
            }
            addSignal(signals, seen, context, source, match, topic, signalSource, how);
        }
    }

    private void addSignal(List<DependencySignal> signals, Set<String> seen, ScalaScanContext context,
                           TextSource source, TextSource.Match match, String topic,
                           SignalSource signalSource, String how) {
        String fingerprint = signalSource + "|" + topic + "|" + source.path() + "|" + match.line();
        if (!seen.add(fingerprint)) {
            return;
        }
        Evidence evidence = Evidence.of(
                signalSource,
                source.path(),
                match.line(),
                match.rawLine(),
                how + " on topic '" + topic + "'");
        signals.add(DependencySignal.messaging(context.nodeKey(), topic, signalSource, evidence));
    }
}
