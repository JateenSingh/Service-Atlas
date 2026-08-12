package com.serviceatlas.parser.scala;

import static org.assertj.core.api.Assertions.assertThat;

import com.serviceatlas.graph.model.SignalSource;
import com.serviceatlas.parser.DependencySignal;
import com.serviceatlas.parser.common.RepoFiles;
import com.serviceatlas.testsupport.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FR-3.10 — Google Pub/Sub publish and subscribe flows. */
class GooglePubSubScannerTest {

    private final GooglePubSubScanner scanner = new GooglePubSubScanner();

    private List<DependencySignal> scan(Path repo, String nodeKey) {
        RepoFiles files = new RepoFiles(repo, Fixtures.scanSettings());
        SbtBuild build = new SbtBuildParser(files).parse(repo.getFileName().toString());
        return scanner.scan(new ScalaScanContext(nodeKey, files, build, Fixtures.scanSettings()));
    }

    /** {@code PUBSUB_PUBLISHER:order-events-v2}, the shape these assertions read most clearly in. */
    private List<String> flows(Path repo, String nodeKey) {
        return scan(repo, nodeKey).stream()
                .map(signal -> signal.source() + ":" + signal.topicName())
                .toList();
    }

    private Path repo(Path temp, String name, String config) throws IOException {
        Path repo = temp.resolve(name);
        Files.createDirectories(repo.resolve("conf"));
        Files.writeString(repo.resolve("build.sbt"), "name := \"" + name + "\"");
        Files.writeString(repo.resolve("conf/application.conf"), config);
        return repo;
    }

    @Test
    @DisplayName("A topic key under a Pub/Sub block is a publish")
    void readsPublishers() {
        assertThat(flows(Fixtures.repo("log-order-svc"), "logordersvc"))
                .contains("PUBSUB_PUBLISHER:order-events-v2");
    }

    @Test
    @DisplayName("A fully-qualified resource path is reduced to the topic name")
    void stripsResourcePaths() {
        DependencySignal published = scan(Fixtures.repo("log-order-svc"), "logordersvc").stream()
                .filter(signal -> signal.source() == SignalSource.PUBSUB_PUBLISHER)
                .findFirst()
                .orElseThrow();

        // conf declares "projects/acme-logistics/topics/order-events-v2"; the node is the topic.
        assertThat(published.topicName()).isEqualTo("order-events-v2");
        assertThat(published.evidence().file()).isEqualTo("conf/application.conf");
        assertThat(published.evidence().line()).isPositive();
    }

    @Test
    @DisplayName("A subscription is attributed to the topic it sits beside, not to a node of its own")
    void resolvesSubscriptionsToTheirTopic() {
        assertThat(flows(Fixtures.repo("log-inventory-svc"), "loginventorysvc"))
                .containsExactly("PUBSUB_SUBSCRIBER:order-events-v2");
    }

    @Test
    @DisplayName("A topic named next to a subscription is not read as a publish")
    void aConsumersTopicKeyIsNotAPublish() {
        assertThat(flows(Fixtures.repo("log-audit-svc"), "logauditsvc"))
                .containsExactly("PUBSUB_SUBSCRIBER:order-events-v2")
                .doesNotContain("PUBSUB_PUBLISHER:order-events-v2");
    }

    @Test
    @DisplayName("One service can publish one topic and consume another")
    void publishAndSubscribeCoexist() {
        assertThat(flows(Fixtures.repo("log-order-svc"), "logordersvc"))
                .containsExactlyInAnyOrder(
                        "PUBSUB_PUBLISHER:order-events-v2",
                        "PUBSUB_SUBSCRIBER:payment-updates");
    }

    @Test
    @DisplayName("Source code confirms the config rather than duplicating its node")
    void sourceAndConfigAgreeOnOneNode() {
        // log-order-svc names order-events-v2 in application.conf and again in
        // OrderEventPublisher.scala; the topic must appear once.
        assertThat(flows(Fixtures.repo("log-order-svc"), "logordersvc"))
                .filteredOn("PUBSUB_PUBLISHER:order-events-v2"::equals)
                .hasSize(1);
    }

    @Test
    @DisplayName("A subscription found only in source resolves through the config's pairing")
    void sourceSubscriptionsResolveThroughConfig() {
        // OrderEventSubscriber.scala names only "order-events-inventory-sub"; the config knows
        // which topic that reads, so the flow lands on the topic and not on a subscriber-shaped node.
        assertThat(flows(Fixtures.repo("log-inventory-svc"), "loginventorysvc"))
                .doesNotContain("PUBSUB_SUBSCRIBER:order-events-inventory-sub");
    }

    @Test
    @DisplayName("A Kafka topic key is not claimed as Pub/Sub")
    void ignoresKafkaTopics(@TempDir Path temp) throws IOException {
        Path repo = repo(temp, "log-kafka-svc", """
                kafka {
                  producer.topic = "order-events"
                  consumer.topic = "payment-confirmations"
                }
                """);

        assertThat(scan(repo, "logkafkasvc")).isEmpty();
    }

    @Test
    @DisplayName("A Pub/Sub source file with no config still names its topic")
    void readsTopicsFromSourceAlone(@TempDir Path temp) throws IOException {
        Path repo = repo(temp, "log-lonely-svc", "app.name = \"log-lonely-svc\"");
        Files.createDirectories(repo.resolve("app/pubsub"));
        Files.writeString(repo.resolve("app/pubsub/Publisher.scala"), """
                package pubsub

                import com.google.cloud.pubsub.v1.Publisher
                import com.google.pubsub.v1.TopicName

                class ShipmentPublisher(projectId: String) {
                  private val topic = TopicName.of(projectId, "shipment-events")
                  private val publisher = Publisher.newBuilder(topic).build()
                }
                """);

        assertThat(flows(repo, "loglonelysvc")).containsExactly("PUBSUB_PUBLISHER:shipment-events");
    }

    @Test
    @DisplayName("A file that never mentions Pub/Sub is not searched for topics")
    void ignoresUnrelatedSource(@TempDir Path temp) throws IOException {
        Path repo = repo(temp, "log-plain-svc", "app.name = \"log-plain-svc\"");
        Files.createDirectories(repo.resolve("app/services"));
        Files.writeString(repo.resolve("app/services/Naming.scala"), """
                package services

                object Naming {
                  val label = "projects/acme/topics/not-a-real-flow"
                }
                """);

        assertThat(scan(repo, "logplainsvc")).isEmpty();
    }

    @Test
    @DisplayName("Every Pub/Sub signal is a messaging signal, carrying evidence")
    void signalsAreMessagingWithEvidence() {
        assertThat(scan(Fixtures.repo("log-order-svc"), "logordersvc")).isNotEmpty().allSatisfy(signal -> {
            assertThat(signal.isMessaging()).isTrue();
            assertThat(signal.evidence().snippet()).isNotBlank();
            assertThat(signal.evidence().detail()).containsIgnoringCase("pub/sub");
        });
    }
}
