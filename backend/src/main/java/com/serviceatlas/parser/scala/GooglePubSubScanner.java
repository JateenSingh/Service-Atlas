package com.serviceatlas.parser.scala;

import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.SignalSource;
import com.serviceatlas.parser.DependencySignal;
import com.serviceatlas.parser.common.TextSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Google Pub/Sub publish and subscribe flows, from configuration and from source.
 *
 * <p>Pub/Sub is drawn through the same {@code TOPIC} node as Kafka and RabbitMQ, tagged with its
 * broker: a reader wants to see "orders-events sits between these two services" regardless of which
 * technology carries it.
 *
 * <p><b>Subscriptions.</b> A subscription is not a topic — it is a named cursor onto one — and the
 * subscription name alone does not reveal which topic it reads. Two things are done about that,
 * both conservative:
 *
 * <ul>
 *   <li>When configuration groups a topic and a subscription under the same parent (the common
 *       layout), the subscription is attributed to that topic.
 *   <li>Otherwise the subscription becomes its own node, labelled as a subscription. That is
 *       honest — the flow is real and the service does consume it — rather than inventing a link
 *       to a topic we cannot see.
 * </ul>
 */
@Component
public final class GooglePubSubScanner implements ScalaSignalScanner {

    /** Config key leaves naming a topic or a subscription. */
    private static final Set<String> TOPIC_KEYS = Set.of("topic", "topics", "topicid", "topicname");
    private static final Set<String> SUBSCRIPTION_KEYS =
            Set.of("subscription", "subscriptions", "subscriptionid", "subscriptionname");

    /** Key path tokens that mark a config block as Pub/Sub rather than Kafka. */
    private static final Set<String> PUBSUB_KEY_TOKENS =
            Set.of("pubsub", "googlepubsub", "gcp", "google");

    /** {@code TopicName.of(project, "orders-events")} and {@code ProjectTopicName.of(...)} */
    private static final Pattern TOPIC_NAME_OF = Pattern.compile(
            "(?:Project)?TopicName\\.of\\s*\\([^,]+,\\s*\"([^\"]+)\"\\s*\\)");

    /** {@code ProjectSubscriptionName.of(project, "orders-events-sub")} */
    private static final Pattern SUBSCRIPTION_NAME_OF = Pattern.compile(
            "(?:Project)?SubscriptionName\\.of\\s*\\([^,]+,\\s*\"([^\"]+)\"\\s*\\)");

    /** Alpakka: {@code PubSubMessage(...)} sinks and sources naming a topic inline. */
    private static final Pattern ALPAKKA_PUBLISH = Pattern.compile(
            "GooglePubSub\\.publish\\s*\\(\\s*(?:topic\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern ALPAKKA_SUBSCRIBE = Pattern.compile(
            "GooglePubSub\\.subscribe\\s*\\(\\s*(?:subscription\\s*=\\s*)?\"([^\"]+)\"");

    /** A fully-qualified resource path: {@code projects/acme/topics/orders-events}. */
    private static final Pattern RESOURCE_PATH =
            Pattern.compile("projects/[^/\"\\s]+/(topics|subscriptions)/([A-Za-z0-9_.~+%\\-]+)");

    /** Markers that a source file is publishing rather than subscribing. */
    private static final Pattern PUBLISHER_CONTEXT =
            Pattern.compile("\\b(Publisher|publish|PubsubMessage|newBuilder)\\b");
    private static final Pattern SUBSCRIBER_CONTEXT =
            Pattern.compile("\\b(Subscriber|subscribe|MessageReceiver|pull)\\b");

    @Override
    public String id() {
        return "google-pubsub";
    }

    @Override
    public List<DependencySignal> scan(ScalaScanContext context) {
        List<DependencySignal> signals = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        signals.addAll(fromConfiguration(context, seen));
        signals.addAll(fromSource(context, seen));
        return signals;
    }

    // ---------------------------------------------------------------- configuration

    private List<DependencySignal> fromConfiguration(ScalaScanContext context, Set<String> seen) {
        List<ConfigValues.Entry> entries = ConfigValues.read(context.files()).entries().stream()
                .filter(entry -> !entry.parseFailed())
                .filter(entry -> isPubSub(entry))
                .toList();

        // Index each block's topic and subscription, so the pair can be read together.
        Map<String, String> topicByParent = new LinkedHashMap<>();
        Map<String, String> subscriptionByParent = new LinkedHashMap<>();
        for (ConfigValues.Entry entry : entries) {
            if (leafIsIn(entry, TOPIC_KEYS)) {
                topicByParent.putIfAbsent(parentOf(entry.key()), resourceName(entry.value()));
            } else if (leafIsIn(entry, SUBSCRIPTION_KEYS)) {
                subscriptionByParent.putIfAbsent(parentOf(entry.key()), resourceName(entry.value()));
            }
        }
        this.subscriptionToTopic.clear();
        subscriptionByParent.forEach((parent, subscription) -> {
            String topic = topicByParent.get(parent);
            if (topic != null) {
                this.subscriptionToTopic.put(subscription, topic);
            }
        });

        List<DependencySignal> signals = new ArrayList<>();
        for (ConfigValues.Entry entry : entries) {
            boolean isTopic = leafIsIn(entry, TOPIC_KEYS);
            boolean isSubscription = leafIsIn(entry, SUBSCRIPTION_KEYS);
            if (!isTopic && !isSubscription) {
                continue;
            }
            String value = resourceName(entry.value());
            if (value.isBlank() || value.contains(" ")) {
                continue;
            }
            String parent = parentOf(entry.key());

            // A topic key sitting next to a subscription is naming the topic that subscription
            // reads — it is not evidence that this service publishes. Consuming services declare a
            // topic name too, and reading every topic key as a publish inverts half the diagram.
            if (isTopic && subscriptionByParent.containsKey(parent)) {
                continue;
            }

            SignalSource source = isTopic
                    ? SignalSource.PUBSUB_PUBLISHER
                    : SignalSource.PUBSUB_SUBSCRIBER;
            String channel = isSubscription ? topicByParent.getOrDefault(parent, value) : value;

            if (!seen.add(source + "|" + channel)) {
                continue;
            }
            String detail = isTopic
                    ? "Publishes to Pub/Sub topic '" + channel + "' (config key " + entry.key() + ")"
                    : channel.equals(value)
                            ? "Subscribes via Pub/Sub subscription '" + value + "' (config key "
                                    + entry.key() + ")"
                            : "Subscribes to Pub/Sub topic '" + channel + "' via subscription '"
                                    + value + "'";

            signals.add(DependencySignal.messaging(
                    context.nodeKey(),
                    channel,
                    source,
                    Evidence.of(source, entry.file(), entry.line(),
                            entry.key() + " = \"" + entry.value() + "\"", detail)));
        }
        return signals;
    }

    /**
     * Subscription name → topic, learned from configuration and used to resolve source-code
     * references. {@code ProjectSubscriptionName.of(project, "order-events-inventory-sub")} names
     * only the subscription; the config usually knows which topic that reads, and using it keeps
     * one node per topic instead of one per subscriber.
     */
    private final Map<String, String> subscriptionToTopic = new LinkedHashMap<>();

    // ---------------------------------------------------------------- source

    private List<DependencySignal> fromSource(ScalaScanContext context, Set<String> seen) {
        List<DependencySignal> signals = new ArrayList<>();

        for (TextSource source : ScalaSources.read(context.files()).sources()) {
            String code = source.code();
            if (!code.contains("pubsub") && !code.contains("PubSub") && !code.contains("Pubsub")) {
                continue; // not a Pub/Sub file at all
            }

            collect(signals, seen, context, source, TOPIC_NAME_OF, SignalSource.PUBSUB_PUBLISHER,
                    "Pub/Sub topic reference");
            collect(signals, seen, context, source, ALPAKKA_PUBLISH, SignalSource.PUBSUB_PUBLISHER,
                    "Alpakka Pub/Sub publish");
            collect(signals, seen, context, source, SUBSCRIPTION_NAME_OF, SignalSource.PUBSUB_SUBSCRIBER,
                    "Pub/Sub subscription reference");
            collect(signals, seen, context, source, ALPAKKA_SUBSCRIBE, SignalSource.PUBSUB_SUBSCRIBER,
                    "Alpakka Pub/Sub subscribe");

            // Fully-qualified resource paths written as literals.
            for (TextSource.Match match : source.matches(RESOURCE_PATH)) {
                boolean topic = "topics".equals(match.group(1));
                SignalSource signalSource = topic
                        ? SignalSource.PUBSUB_PUBLISHER
                        : SignalSource.PUBSUB_SUBSCRIBER;
                // A topics/… path in a file that only ever subscribes is a subscribe, not a publish.
                if (topic && !PUBLISHER_CONTEXT.matcher(code).find()
                        && SUBSCRIBER_CONTEXT.matcher(code).find()) {
                    signalSource = SignalSource.PUBSUB_SUBSCRIBER;
                }
                add(signals, seen, context, source, match, match.group(2), signalSource,
                        "Pub/Sub resource path");
            }
        }
        return signals;
    }

    private void collect(List<DependencySignal> signals, Set<String> seen, ScalaScanContext context,
                         TextSource source, Pattern pattern, SignalSource signalSource, String how) {
        for (TextSource.Match match : source.matches(pattern)) {
            add(signals, seen, context, source, match, match.group(1), signalSource, how);
        }
    }

    private void add(List<DependencySignal> signals, Set<String> seen, ScalaScanContext context,
                     TextSource source, TextSource.Match match, String channel,
                     SignalSource signalSource, String how) {
        if (channel == null || channel.isBlank() || channel.contains(" ")) {
            return;
        }
        String resolved = signalSource == SignalSource.PUBSUB_SUBSCRIBER
                ? subscriptionToTopic.getOrDefault(channel, channel)
                : channel;

        if (!seen.add(signalSource + "|" + resolved)) {
            return;
        }
        String detail = resolved.equals(channel)
                ? how + " — '" + channel + "'"
                : how + " — subscription '" + channel + "' reads topic '" + resolved + "'";
        signals.add(DependencySignal.messaging(
                context.nodeKey(),
                resolved,
                signalSource,
                Evidence.of(signalSource, source.path(), match.line(), match.rawLine(), detail)));
    }

    // ---------------------------------------------------------------- helpers

    /** Only config under a Pub/Sub-ish key counts; a Kafka {@code topic} key must not be claimed. */
    private boolean isPubSub(ConfigValues.Entry entry) {
        return entry.keyTokens().stream()
                .map(token -> token.toLowerCase(Locale.ROOT))
                .anyMatch(PUBSUB_KEY_TOKENS::contains);
    }

    private boolean leafIsIn(ConfigValues.Entry entry, Set<String> leaves) {
        List<String> tokens = entry.keyTokens();
        if (tokens.isEmpty()) {
            return false;
        }
        // Key tokens are split on separators, so "topic-id" arrives as [topic, id]; test the tail.
        String leaf = tokens.get(tokens.size() - 1);
        String pair = tokens.size() >= 2 ? tokens.get(tokens.size() - 2) + leaf : leaf;
        return leaves.contains(leaf) || leaves.contains(pair);
    }

    /** The config path above the leaf, used to pair a subscription with its topic. */
    private String parentOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot > 0 ? key.substring(0, dot) : "";
    }

    /** {@code projects/acme/topics/orders-events} → {@code orders-events}. */
    private String resourceName(String value) {
        if (value == null) {
            return "";
        }
        java.util.regex.Matcher matcher = RESOURCE_PATH.matcher(value);
        return matcher.find() ? matcher.group(2) : value.strip();
    }
}
