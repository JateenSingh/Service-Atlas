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

class ConfigReferenceScannerTest {

    private final ConfigReferenceScanner scanner = new ConfigReferenceScanner();

    private List<DependencySignal> scan(Path repo, String nodeKey) {
        RepoFiles files = new RepoFiles(repo, Fixtures.scanSettings());
        SbtBuild build = new SbtBuildParser(files).parse(repo.getFileName().toString());
        return scanner.scan(new ScalaScanContext(nodeKey, files, build, Fixtures.scanSettings()));
    }

    @Test
    @DisplayName("FR-3.2: service URLs in application.conf become HIGH-confidence signals")
    void readsServiceUrls() {
        List<DependencySignal> signals = scan(Fixtures.repo("log-order-svc"), "logordersvc");

        assertThat(signals)
                .filteredOn(signal -> signal.source() == SignalSource.CONFIG_REFERENCE)
                .extracting(DependencySignal::targetHint)
                .contains(
                        "http://log-inventory-svc:9000",
                        "http://log-shipment-svc.logistics.svc.cluster.local:9000/shipments",
                        "https://api.stripe.com/v1/charges",
                        "log-tracking-svc");
    }

    @Test
    @DisplayName("Evidence points at the exact config file and line")
    void carriesFileAndLineEvidence() {
        DependencySignal inventory = scan(Fixtures.repo("log-order-svc"), "logordersvc").stream()
                .filter(signal -> "http://log-inventory-svc:9000".equals(signal.targetHint()))
                .findFirst()
                .orElseThrow();

        assertThat(inventory.evidence().file()).isEqualTo("conf/application.conf");
        assertThat(inventory.evidence().line()).isPositive();
        assertThat(inventory.evidence().snippet()).contains("log-inventory-svc");
        assertThat(inventory.confidence()).isEqualTo(com.serviceatlas.graph.model.Confidence.HIGH);
    }

    @Test
    @DisplayName("A service's own bind address is not a dependency on itself")
    void ignoresSelfConfiguration() {
        List<DependencySignal> signals = scan(Fixtures.repo("log-order-svc"), "logordersvc");

        assertThat(signals).extracting(DependencySignal::targetHint)
                .doesNotContain("http://localhost:9000");
    }

    @Test
    @DisplayName("FR-3.6: topic keys become directed messaging signals")
    void readsTopicsWithDirection() {
        List<DependencySignal> signals = scan(Fixtures.repo("log-order-svc"), "logordersvc");

        assertThat(signals)
                .filteredOn(DependencySignal::isMessaging)
                .extracting(signal -> signal.source() + ":" + signal.topicName())
                .contains("MESSAGING_PRODUCER:order-events", "MESSAGING_CONSUMER:payment-confirmations");
    }

    @Test
    @DisplayName("A topic key with no produce/consume direction is skipped rather than guessed")
    void skipsUndirectedTopics(@TempDir Path temp) throws IOException {
        Path repo = temp.resolve("log-vague-svc");
        Files.createDirectories(repo.resolve("conf"));
        Files.writeString(repo.resolve("build.sbt"), "name := \"log-vague-svc\"");
        Files.writeString(repo.resolve("conf/application.conf"), """
                kafka {
                  topic = "mystery-events"
                }
                """);

        assertThat(scan(repo, "logvaguesvc")).filteredOn(DependencySignal::isMessaging).isEmpty();
    }

    @Test
    @DisplayName("A malformed config file does not fail the scan")
    void malformedConfigIsSurvivable(@TempDir Path temp) throws IOException {
        Path repo = temp.resolve("log-broken-svc");
        Files.createDirectories(repo.resolve("conf"));
        Files.writeString(repo.resolve("build.sbt"), "name := \"log-broken-svc\"");
        Files.writeString(repo.resolve("conf/application.conf"), "this { is [ not valid hocon");

        assertThat(scan(repo, "logbrokensvc")).isEmpty();
    }

    @Test
    @DisplayName("Environment-substituted values do not break parsing of their neighbours")
    void toleratesUnresolvedSubstitutions(@TempDir Path temp) throws IOException {
        Path repo = temp.resolve("log-sub-svc");
        Files.createDirectories(repo.resolve("conf"));
        Files.writeString(repo.resolve("build.sbt"), "name := \"log-sub-svc\"");
        Files.writeString(repo.resolve("conf/application.conf"), """
                services {
                  a { url = ${?A_URL} }
                  b { url = "http://log-quote-svc:9000" }
                }
                """);

        assertThat(scan(repo, "logsubsvc")).extracting(DependencySignal::targetHint)
                .containsExactly("http://log-quote-svc:9000");
    }
}
