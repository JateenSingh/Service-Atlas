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

/** FR-3.3, FR-3.5, FR-3.6: the source-code scanners. */
class SourceScannerTest {

    private final HttpClientScanner httpScanner = new HttpClientScanner();
    private final MessagingScanner messagingScanner = new MessagingScanner();
    private final SourceImportScanner importScanner = new SourceImportScanner();

    private ScalaScanContext context(Path repo, String nodeKey) {
        RepoFiles files = new RepoFiles(repo, Fixtures.scanSettings());
        SbtBuild build = new SbtBuildParser(files).parse(repo.getFileName().toString());
        return new ScalaScanContext(nodeKey, files, build, Fixtures.scanSettings());
    }

    @Test
    @DisplayName("FR-3.3: literal URLs in client calls become MEDIUM-confidence signals")
    void findsLiteralClientCalls() {
        List<DependencySignal> signals = httpScanner.scan(context(Fixtures.repo("log-order-svc"), "logordersvc"));

        assertThat(signals).extracting(DependencySignal::targetHint)
                .contains("http://log-notification-svc:9000/notifications/_");
        assertThat(signals).allSatisfy(signal -> {
            assertThat(signal.source()).isEqualTo(SignalSource.HTTP_CLIENT_CALL);
            assertThat(signal.confidence()).isEqualTo(com.serviceatlas.graph.model.Confidence.MEDIUM);
            assertThat(signal.evidence().line()).isPositive();
        });
    }

    @Test
    @DisplayName("FR-3.3: a URL assembled from a local val is followed")
    void followsValIndirection() {
        List<DependencySignal> signals = httpScanner.scan(context(Fixtures.repo("log-order-svc"), "logordersvc"));

        assertThat(signals).extracting(DependencySignal::targetHint)
                .anyMatch(hint -> hint.startsWith("http://log-audit-svc:9000"));
    }

    @Test
    @DisplayName("FR-3.3: a call whose target comes from configuration is resolved through the config")
    void followsConfigLookup() {
        List<DependencySignal> signals =
                httpScanner.scan(context(Fixtures.repo("log-inventory-svc"), "loginventorysvc"));

        assertThat(signals).extracting(DependencySignal::targetHint)
                .contains("http://log-pricing-svc:8080");
        assertThat(signals).anySatisfy(signal ->
                assertThat(signal.evidence().detail()).contains("services.pricing.url"));
    }

    @Test
    @DisplayName("A commented-out client call produces no signal")
    void ignoresCommentedOutCalls() {
        List<DependencySignal> signals = httpScanner.scan(context(Fixtures.repo("log-order-svc"), "logordersvc"));

        assertThat(signals).extracting(DependencySignal::targetHint)
                .noneMatch(hint -> hint.contains("log-deprecated-svc"));
    }

    @Test
    @DisplayName("Test sources are never scanned")
    void ignoresTestSources(@TempDir Path temp) throws IOException {
        Path repo = temp.resolve("log-tested-svc");
        Files.createDirectories(repo.resolve("src/test/scala"));
        Files.createDirectories(repo.resolve("src/main/scala"));
        Files.writeString(repo.resolve("build.sbt"), "name := \"log-tested-svc\"");
        Files.writeString(repo.resolve("src/test/scala/StubSpec.scala"),
                "class StubSpec { val stub = \"http://log-stub-svc:9000/x\" }");
        Files.writeString(repo.resolve("src/main/scala/Real.scala"),
                "class Real { ws.url(\"http://log-real-svc:9000/x\") }");

        assertThat(httpScanner.scan(context(repo, "logtestedsvc")))
                .extracting(DependencySignal::targetHint)
                .containsExactly("http://log-real-svc:9000/x");
    }

    @Test
    @DisplayName("FR-3.6: Kafka producers and consumers are found in source")
    void findsMessagingCalls() {
        List<DependencySignal> produced =
                messagingScanner.scan(context(Fixtures.repo("log-order-svc"), "logordersvc"));
        List<DependencySignal> consumed =
                messagingScanner.scan(context(Fixtures.repo("log-inventory-svc"), "loginventorysvc"));

        assertThat(produced).extracting(signal -> signal.source() + ":" + signal.topicName())
                .contains("MESSAGING_PRODUCER:order-events");
        assertThat(consumed).extracting(signal -> signal.source() + ":" + signal.topicName())
                .contains("MESSAGING_CONSUMER:order-events");
    }

    @Test
    @DisplayName("FR-3.6: a topic held in a constant is resolved")
    void resolvesTopicConstants() {
        List<DependencySignal> signals =
                messagingScanner.scan(context(Fixtures.repo("log-audit-svc"), "logauditsvc"));

        assertThat(signals).extracting(signal -> signal.source() + ":" + signal.topicName())
                .contains("MESSAGING_CONSUMER:order-events");
    }

    @Test
    @DisplayName("FR-3.5: cross-service imports become LOW-confidence signals")
    void findsCrossServiceImports() {
        List<DependencySignal> signals =
                importScanner.scan(context(Fixtures.repo("log-order-svc"), "logordersvc"));

        assertThat(signals).extracting(DependencySignal::targetHint)
                .contains("com.acme.logistics.quote", "com.acme.logistics.user");
        assertThat(signals).allSatisfy(signal ->
                assertThat(signal.confidence()).isEqualTo(com.serviceatlas.graph.model.Confidence.LOW));
    }

    @Test
    @DisplayName("A service's own packages and library imports are not dependencies")
    void ignoresOwnAndLibraryImports() {
        List<DependencySignal> signals =
                importScanner.scan(context(Fixtures.repo("log-quote-svc"), "logquotesvc"));

        assertThat(signals).extracting(DependencySignal::targetHint)
                .noneMatch(hint -> hint.startsWith("play.") || hint.startsWith("scala.")
                        || hint.startsWith("javax.") || hint.startsWith("com.acme.logistics.quote"));
    }

    @Test
    @DisplayName("Interpolation leaves an unresolvable reference as a placeholder, not a crash")
    void interpolationHandlesUnknownReferences() {
        String resolved = ScalaSources.interpolate("$unknown/path", java.util.Map.of());

        assertThat(resolved).isEqualTo("_/path");
    }
}
