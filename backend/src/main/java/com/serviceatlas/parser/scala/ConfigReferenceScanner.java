package com.serviceatlas.parser.scala;

import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.SignalSource;
import com.serviceatlas.parser.DependencySignal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * FR-3.2 — service references declared in {@code application.conf} / {@code reference.conf}, and
 * FR-3.6's config half: Kafka/RabbitMQ topic names.
 *
 * <p>Three shapes are recognised:
 *
 * <ul>
 *   <li><b>URLs</b> — {@code services.inventory.url = "http://log-inventory-svc:9000"}
 *   <li><b>Bare hosts</b> — {@code services.tracking.host = "log-tracking-svc"}, but only under a
 *       key that says it is about a service, since a bare string is otherwise unidentifiable
 *   <li><b>Topics</b> — {@code kafka.producer.topic = "order-events"}, with produce/consume
 *       direction read from the key path
 * </ul>
 */
@Component
public final class ConfigReferenceScanner implements ScalaSignalScanner {

    /** Key path segments that mark a value as naming another service. */
    private static final Set<String> SERVICE_KEY_TOKENS = Set.of(
            "service", "services", "client", "clients", "endpoint", "endpoints", "upstream",
            "downstream", "dependency", "dependencies", "api", "apis", "integration", "integrations");

    /** Key path leaves that hold a host-like value. */
    private static final Set<String> HOST_KEY_TOKENS = Set.of("url", "uri", "host", "hostname", "baseurl", "address", "endpoint", "base");

    private static final Set<String> TOPIC_KEY_TOKENS = Set.of("topic", "topics", "queue", "queues", "stream", "exchange");

    private static final Set<String> PRODUCER_TOKENS = Set.of(
            "producer", "producers", "produce", "publish", "publisher", "sink", "outbound", "egress", "out", "write");

    private static final Set<String> CONSUMER_TOKENS = Set.of(
            "consumer", "consumers", "consume", "subscribe", "subscription", "source", "inbound", "ingress", "in", "read", "listen");

    /** Keys that describe how this service listens, not who it calls. */
    private static final Set<String> SELF_KEY_TOKENS = Set.of("self", "bind", "listen", "server", "http", "play");

    private static final Pattern URL_VALUE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*://\\S+$");
    private static final Pattern HOST_VALUE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9.\\-]{2,}(:\\d+)?$");

    @Override
    public String id() {
        return "hocon-config-references";
    }

    @Override
    public List<DependencySignal> scan(ScalaScanContext context) {
        ConfigValues config = ConfigValues.read(context.files());
        List<DependencySignal> signals = new ArrayList<>();

        for (ConfigValues.Entry entry : config.entries()) {
            if (entry.parseFailed() || entry.value().isBlank()) {
                continue;
            }
            List<String> tokens = entry.keyTokens();

            if (isTopicKey(tokens)) {
                topicSignal(context, entry, tokens).ifPresent(signals::add);
                continue;
            }
            if (isSelfKey(tokens)) {
                continue;
            }
            referenceSignal(context, entry, tokens).ifPresent(signals::add);
        }
        return signals;
    }

    private java.util.Optional<DependencySignal> referenceSignal(
            ScalaScanContext context, ConfigValues.Entry entry, List<String> tokens) {
        String value = entry.value().strip();
        boolean urlShaped = URL_VALUE.matcher(value).matches();
        boolean hostShaped = !urlShaped && HOST_VALUE.matcher(value).matches() && value.contains("-");

        // A bare host is only a dependency if the key says so; a URL speaks for itself.
        if (!urlShaped && !(hostShaped && mentionsService(tokens))) {
            return java.util.Optional.empty();
        }
        if (hostShaped && !isHostKey(tokens)) {
            return java.util.Optional.empty();
        }

        Evidence evidence = Evidence.of(
                SignalSource.CONFIG_REFERENCE,
                entry.file(),
                entry.line(),
                entry.key() + " = \"" + value + "\"",
                "Configuration key " + entry.key() + " points at " + value);
        return java.util.Optional.of(
                DependencySignal.of(context.nodeKey(), value, SignalSource.CONFIG_REFERENCE, evidence));
    }

    private java.util.Optional<DependencySignal> topicSignal(
            ScalaScanContext context, ConfigValues.Entry entry, List<String> tokens) {
        String topic = entry.value().strip();
        if (topic.isBlank() || topic.contains("/") || topic.contains(" ")) {
            return java.util.Optional.empty();
        }
        SignalSource source = direction(tokens);
        if (source == null) {
            return java.util.Optional.empty(); // direction unknown: an unattributable arrow helps nobody
        }
        Evidence evidence = Evidence.of(
                source,
                entry.file(),
                entry.line(),
                entry.key() + " = \"" + topic + "\"",
                (source == SignalSource.MESSAGING_PRODUCER ? "Produces to" : "Consumes from")
                        + " topic '" + topic + "' (config key " + entry.key() + ")");
        return java.util.Optional.of(DependencySignal.messaging(context.nodeKey(), topic, source, evidence));
    }

    private boolean isTopicKey(List<String> tokens) {
        return tokens.stream().anyMatch(TOPIC_KEY_TOKENS::contains);
    }

    private boolean isSelfKey(List<String> tokens) {
        return !tokens.isEmpty() && SELF_KEY_TOKENS.contains(tokens.get(0))
                || tokens.stream().anyMatch(token -> token.equals("self"));
    }

    private boolean mentionsService(List<String> tokens) {
        return tokens.stream().anyMatch(SERVICE_KEY_TOKENS::contains);
    }

    private boolean isHostKey(List<String> tokens) {
        return tokens.stream().map(token -> token.toLowerCase(Locale.ROOT)).anyMatch(HOST_KEY_TOKENS::contains);
    }

    /** Reads produce/consume direction from the key path; null when it says neither. */
    private SignalSource direction(List<String> tokens) {
        for (String token : tokens) {
            if (PRODUCER_TOKENS.contains(token)) {
                return SignalSource.MESSAGING_PRODUCER;
            }
            if (CONSUMER_TOKENS.contains(token)) {
                return SignalSource.MESSAGING_CONSUMER;
            }
        }
        return null;
    }
}
