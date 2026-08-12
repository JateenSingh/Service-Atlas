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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Google Pub/Sub publish and subscribe flows, from configuration and from source.
 *
 * <p>Pub/Sub is drawn through the same {@code TOPIC} node as Kafka and RabbitMQ, tagged with its
 * broker: a reader wants to see "order-events sits between these two services" regardless of which
 * technology carries it.
 *
 * <p><b>Recognising it.</b> A {@code topic} key is only Pub/Sub if something says so, or the same
 * key would claim every Kafka topic in the estate. Three things count, in descending order of
 * certainty: a {@code projects/…/topics/…} resource path, which is self-identifying wherever it
 * appears; a Pub/Sub client on the classpath, which makes a bare {@code topic} key in that
 * repository Pub/Sub; and a {@code pubsub}/{@code gcp} segment in the key path.
 *
 * <p><b>Direction.</b> A subscription is a named cursor onto a topic, and consuming services name
 * the topic in their configuration too — so reading every {@code topic} key as a publish inverts
 * half the diagram. Direction is decided in this order:
 *
 * <ul>
 *   <li>the key path, when it says {@code publisher}/{@code producer} or {@code subscriber}/
 *       {@code consumer} outright;
 *   <li>the source, when a topic key sits beside a subscription: the service publishes only if it
 *       actually builds a publisher somewhere;
 *   <li>otherwise a topic is a publish and a subscription is a subscribe.
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

    /** Key path tokens that state the direction outright. */
    private static final Set<String> PUBLISH_TOKENS = Set.of(
            "publish", "publisher", "publishers", "produce", "producer", "producers", "out",
            "outbound", "outgoing", "egress", "sink", "write");
    private static final Set<String> SUBSCRIBE_TOKENS = Set.of(
            "subscribe", "subscriber", "subscribers", "consume", "consumer", "consumers", "in",
            "inbound", "incoming", "ingress", "source", "listen", "read");

    /** Coordinates that put a Pub/Sub client on the classpath. */
    private static final Set<String> PUBSUB_COORDINATES = Set.of(
            "com.google.cloud:google-cloud-pubsub",
            "com.google.cloud:spring-cloud-gcp-starter-pubsub",
            "com.lightbend.akka:akka-stream-alpakka-google-cloud-pub-sub",
            "com.lightbend.akka:akka-stream-alpakka-google-cloud-pub-sub-grpc",
            "org.apache.pekko:pekko-connectors-google-cloud-pub-sub");

    /** {@code TopicName.of(project, "orders-events")} and {@code ProjectTopicName.of(...)} */
    private static final Pattern TOPIC_NAME_OF = Pattern.compile(
            "(?:Project)?TopicName\\.of\\s*\\([^,]+,\\s*(\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_.]*)\\s*\\)");

    /** {@code ProjectSubscriptionName.of(project, "orders-events-sub")} */
    private static final Pattern SUBSCRIPTION_NAME_OF = Pattern.compile(
            "(?:Project)?SubscriptionName\\.of\\s*\\([^,]+,\\s*(\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_.]*)\\s*\\)");

    /** Alpakka and Spring Cloud GCP, which name the channel inline. */
    private static final Pattern PUBLISH_CALL = Pattern.compile(
            "(?:GooglePubSub\\.publish|pubSubTemplate\\.publish|publishTo)\\s*\\(\\s*"
                    + "(?:topic\\s*=\\s*)?(\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_.]*)");
    private static final Pattern SUBSCRIBE_CALL = Pattern.compile(
            "(?:GooglePubSub\\.subscribe|pubSubTemplate\\.subscribe|subscribeTo)\\s*\\(\\s*"
                    + "(?:subscription\\s*=\\s*)?(\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_.]*)");

    /** A fully-qualified resource path: {@code projects/acme/topics/orders-events}. */
    private static final Pattern RESOURCE_PATH =
            Pattern.compile("projects/[^/\"\\s]+/(topics|subscriptions)/([A-Za-z0-9_.~+%\\-]+)");

    /** Building a publisher — the thing that distinguishes publishing from naming a topic. */
    private static final Pattern PUBLISHER_CONSTRUCTION = Pattern.compile(
            "Publisher\\s*\\.\\s*newBuilder|new\\s+Publisher\\b|GooglePubSub\\.publish"
                    + "|pubSubTemplate\\.publish|PubSubPublisherTemplate|publishMessage\\s*\\(");

    /** Building a subscriber, for the mirror-image case. */
    private static final Pattern SUBSCRIBER_CONSTRUCTION = Pattern.compile(
            "Subscriber\\s*\\.\\s*newBuilder|new\\s+Subscriber\\b|GooglePubSub\\.subscribe"
                    + "|pubSubTemplate\\.subscribe|MessageReceiver");

    @Override
    public String id() {
        return "google-pubsub";
    }

    @Override
    public List<DependencySignal> scan(ScalaScanContext context) {
        ScalaSources sources = ScalaSources.read(context.files());
        List<TextSource> pubSubSources = sources.sources().stream()
                .filter(GooglePubSubScanner::mentionsPubSub)
                .toList();

        Repo repo = new Repo(
                hasPubSubDependency(context),
                pubSubSources.stream().anyMatch(source ->
                        PUBLISHER_CONSTRUCTION.matcher(source.code()).find()));

        List<DependencySignal> signals = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, String> subscriptionToTopic = new LinkedHashMap<>();

        ConfigScan config = fromConfiguration(context, repo, seen, subscriptionToTopic);
        signals.addAll(config.signals());
        signals.addAll(fromSource(context, pubSubSources, seen, subscriptionToTopic));
        inferredPublish(context, repo, config, signals, seen).ifPresent(signals::add);
        return signals;
    }

    /** What the repository as a whole tells us, gathered once before reading any single key. */
    private record Repo(boolean pubSubOnClasspath, boolean buildsAPublisher) {
    }

    /**
     * The config pass: what it decided, plus the topic keys it could not attribute — those sitting
     * beside a subscription, where the name is as likely to be what the service reads as what it
     * writes.
     */
    private record ConfigScan(List<DependencySignal> signals, Map<String, ConfigValues.Entry> deferred) {
    }

    /**
     * A publisher the configuration never explained. When a service builds a publisher, publishes
     * to nothing we could name, and has exactly one topic we declined to attribute, that topic is
     * the one it publishes — the common shape where the topic name is read from config at runtime.
     * Two such topics is a guess, and a guess is worse than a missing edge, so it stops there.
     */
    private java.util.Optional<DependencySignal> inferredPublish(
            ScalaScanContext context, Repo repo, ConfigScan config,
            List<DependencySignal> found, Set<String> seen) {
        boolean alreadyPublishes = found.stream()
                .anyMatch(signal -> signal.source() == SignalSource.PUBSUB_PUBLISHER);
        if (!repo.buildsAPublisher() || alreadyPublishes || config.deferred().size() != 1) {
            return java.util.Optional.empty();
        }
        Map.Entry<String, ConfigValues.Entry> only = config.deferred().entrySet().iterator().next();
        String topic = only.getKey();
        if (!seen.add(SignalSource.PUBSUB_PUBLISHER + "|" + topic)) {
            return java.util.Optional.empty();
        }
        ConfigValues.Entry entry = only.getValue();
        return java.util.Optional.of(DependencySignal.messaging(
                        context.nodeKey(),
                        topic,
                        SignalSource.PUBSUB_PUBLISHER,
                        Evidence.of(SignalSource.PUBSUB_PUBLISHER, entry.file(), entry.line(),
                                entry.key() + " = \"" + entry.value() + "\"",
                                "Builds a Pub/Sub publisher and configures one topic, '" + topic
                                        + "', without naming it in code"))
                .withConfidence(com.serviceatlas.graph.model.Confidence.LOW));
    }

    // ---------------------------------------------------------------- configuration

    private ConfigScan fromConfiguration(ScalaScanContext context, Repo repo,
                                         Set<String> seen,
                                         Map<String, String> subscriptionToTopic) {
        List<ConfigValues.Entry> entries = ConfigValues.read(context.files()).entries().stream()
                .filter(entry -> !entry.parseFailed())
                .filter(entry -> isPubSub(entry, repo))
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
        subscriptionByParent.forEach((parent, subscription) -> {
            String topic = topicByParent.get(parent);
            if (topic != null) {
                subscriptionToTopic.put(subscription, topic);
            }
        });

        List<DependencySignal> signals = new ArrayList<>();
        Map<String, ConfigValues.Entry> deferred = new LinkedHashMap<>();

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
            SignalSource source = direction(entry, isTopic, subscriptionByParent.containsKey(parent));
            if (source == null) {
                deferred.putIfAbsent(value, entry);
                continue;
            }
            String channel = source == SignalSource.PUBSUB_SUBSCRIBER && isSubscription
                    ? topicByParent.getOrDefault(parent, value)
                    : value;

            if (!seen.add(source + "|" + channel)) {
                continue;
            }
            signals.add(DependencySignal.messaging(
                    context.nodeKey(),
                    channel,
                    source,
                    Evidence.of(source, entry.file(), entry.line(),
                            entry.key() + " = \"" + entry.value() + "\"",
                            detail(source, entry, value, channel))));
        }
        return new ConfigScan(signals, deferred);
    }

    /**
     * Which way the arrow points for one config key, or null when the honest answer is "this key
     * may only be naming the topic a sibling subscription reads".
     */
    private SignalSource direction(ConfigValues.Entry entry, boolean isTopic,
                                   boolean hasSiblingSubscription) {
        for (String token : entry.keyTokens()) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (PUBLISH_TOKENS.contains(lower)) {
                return SignalSource.PUBSUB_PUBLISHER;
            }
            if (SUBSCRIBE_TOKENS.contains(lower)) {
                return SignalSource.PUBSUB_SUBSCRIBER;
            }
        }
        if (!isTopic) {
            return SignalSource.PUBSUB_SUBSCRIBER;
        }
        if (hasSiblingSubscription) {
            // A topic beside a subscription is usually naming what that subscription reads, so it
            // is set aside rather than drawn; only an otherwise-unexplained publisher claims it.
            return null;
        }
        return SignalSource.PUBSUB_PUBLISHER;
    }

    private String detail(SignalSource source, ConfigValues.Entry entry, String value, String channel) {
        if (source == SignalSource.PUBSUB_PUBLISHER) {
            return "Publishes to Pub/Sub topic '" + channel + "' (config key " + entry.key() + ")";
        }
        return channel.equals(value)
                ? "Subscribes via Pub/Sub subscription '" + value + "' (config key " + entry.key() + ")"
                : "Subscribes to Pub/Sub topic '" + channel + "' via subscription '" + value + "'";
    }

    // ---------------------------------------------------------------- source

    private List<DependencySignal> fromSource(ScalaScanContext context, List<TextSource> sources,
                                              Set<String> seen,
                                              Map<String, String> subscriptionToTopic) {
        List<DependencySignal> signals = new ArrayList<>();

        for (TextSource source : sources) {
            Map<String, String> values = ScalaSources.stringValues(source);
            String code = source.code();

            collect(signals, seen, context, source, values, subscriptionToTopic, TOPIC_NAME_OF,
                    SignalSource.PUBSUB_PUBLISHER, "Pub/Sub topic reference");
            collect(signals, seen, context, source, values, subscriptionToTopic, PUBLISH_CALL,
                    SignalSource.PUBSUB_PUBLISHER, "Pub/Sub publish");
            collect(signals, seen, context, source, values, subscriptionToTopic, SUBSCRIPTION_NAME_OF,
                    SignalSource.PUBSUB_SUBSCRIBER, "Pub/Sub subscription reference");
            collect(signals, seen, context, source, values, subscriptionToTopic, SUBSCRIBE_CALL,
                    SignalSource.PUBSUB_SUBSCRIBER, "Pub/Sub subscribe");

            // Fully-qualified resource paths written as literals.
            for (TextSource.Match match : source.matches(RESOURCE_PATH)) {
                boolean topic = "topics".equals(match.group(1));
                SignalSource signalSource = topic
                        ? SignalSource.PUBSUB_PUBLISHER
                        : SignalSource.PUBSUB_SUBSCRIBER;
                // A topics/… path in a file that only ever subscribes is a subscribe, not a publish.
                if (topic && !PUBLISHER_CONSTRUCTION.matcher(code).find()
                        && SUBSCRIBER_CONSTRUCTION.matcher(code).find()) {
                    signalSource = SignalSource.PUBSUB_SUBSCRIBER;
                }
                add(signals, seen, context, source, subscriptionToTopic, match, match.group(2),
                        signalSource, "Pub/Sub resource path");
            }
        }
        return signals;
    }

    private void collect(List<DependencySignal> signals, Set<String> seen, ScalaScanContext context,
                         TextSource source, Map<String, String> values,
                         Map<String, String> subscriptionToTopic, Pattern pattern,
                         SignalSource signalSource, String how) {
        for (TextSource.Match match : source.matches(pattern)) {
            String captured = match.group(1);
            // The name is either written inline or held in a val a line or two above — possibly
            // qualified, as `Topics.Renders`, in which case the last segment is the val's name.
            String channel;
            if (captured == null) {
                channel = null;
            } else if (captured.startsWith("\"")) {
                channel = captured.substring(1, captured.length() - 1);
            } else {
                channel = values.getOrDefault(
                        captured, values.get(captured.substring(captured.lastIndexOf('.') + 1)));
            }
            add(signals, seen, context, source, subscriptionToTopic, match, channel, signalSource, how);
        }
    }

    private void add(List<DependencySignal> signals, Set<String> seen, ScalaScanContext context,
                     TextSource source, Map<String, String> subscriptionToTopic,
                     TextSource.Match match, String channel, SignalSource signalSource, String how) {
        if (channel == null || channel.isBlank() || channel.contains(" ")) {
            return;
        }
        String named = resourceName(channel);
        String resolved = signalSource == SignalSource.PUBSUB_SUBSCRIBER
                ? subscriptionToTopic.getOrDefault(named, named)
                : named;

        if (!seen.add(signalSource + "|" + resolved)) {
            return;
        }
        String detail = resolved.equals(named)
                ? how + " — '" + named + "'"
                : how + " — subscription '" + named + "' reads topic '" + resolved + "'";
        signals.add(DependencySignal.messaging(
                context.nodeKey(),
                resolved,
                signalSource,
                Evidence.of(signalSource, source.path(), match.line(), match.rawLine(), detail)));
    }

    // ---------------------------------------------------------------- helpers

    private static boolean mentionsPubSub(TextSource source) {
        String code = source.code();
        return code.contains("pubsub") || code.contains("PubSub") || code.contains("Pubsub");
    }

    private boolean hasPubSubDependency(ScalaScanContext context) {
        return context.build().dependencies().stream()
                .map(dependency -> dependency.group() + ":" + dependency.artifactWithoutScalaSuffix())
                .anyMatch(PUBSUB_COORDINATES::contains);
    }

    /**
     * Whether a config entry is about Pub/Sub at all. A resource path says so on its own; otherwise
     * either the key path or the classpath has to vouch for it, so a Kafka {@code topic} key in a
     * repository with no Pub/Sub client is never claimed.
     */
    private boolean isPubSub(ConfigValues.Entry entry, Repo repo) {
        if (RESOURCE_PATH.matcher(entry.value()).find()) {
            return true;
        }
        boolean keyPathSaysSo = entry.keyTokens().stream()
                .map(token -> token.toLowerCase(Locale.ROOT))
                .anyMatch(PUBSUB_KEY_TOKENS::contains);
        return keyPathSaysSo || repo.pubSubOnClasspath();
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
        Matcher matcher = RESOURCE_PATH.matcher(value);
        return matcher.find() ? matcher.group(2) : value.strip();
    }
}
