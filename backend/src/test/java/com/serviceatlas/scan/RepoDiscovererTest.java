package com.serviceatlas.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.serviceatlas.config.ServiceAtlasProperties;
import com.serviceatlas.parser.RepoCandidate;
import com.serviceatlas.testsupport.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoDiscovererTest {

    private final RepoDiscoverer discoverer = new RepoDiscoverer(new ServiceAtlasProperties());

    @Test
    @DisplayName("FR-2.1: every SBT repository under the root is discovered")
    void discoversFixtureRepositories() {
        List<RepoCandidate> candidates = discoverer.discover(Fixtures.root());

        assertThat(candidates).extracting(RepoCandidate::relativePath)
                .containsExactlyInAnyOrder(
                        "log-order-svc", "log-quote-svc", "log-user-svc", "log-pricing-svc",
                        "log-inventory-svc", "log-notification-svc", "log-shipment-svc",
                        "log-audit-svc", "log-tracking-svc");
    }

    @Test
    @DisplayName("FR-2.3: ignored directories are never descended into")
    void skipsIgnoredDirectories(@TempDir Path temp) throws IOException {
        Path hidden = temp.resolve("target/nested-svc");
        Files.createDirectories(hidden);
        Files.writeString(hidden.resolve("build.sbt"), "name := \"nested-svc\"");
        Path real = temp.resolve("real-svc");
        Files.createDirectories(real);
        Files.writeString(real.resolve("build.sbt"), "name := \"real-svc\"");

        List<RepoCandidate> candidates = discoverer.discover(temp, 3, List.of());

        assertThat(candidates).extracting(RepoCandidate::relativePath).containsExactly("real-svc");
    }

    @Test
    @DisplayName("FR-2.3: the workspace's own ignore list is honoured")
    void honoursExtraIgnoreList(@TempDir Path temp) throws IOException {
        for (String name : List.of("keep-svc", "archive")) {
            Path repo = temp.resolve(name);
            Files.createDirectories(repo);
            Files.writeString(repo.resolve("build.sbt"), "name := \"" + name + "\"");
        }

        List<RepoCandidate> candidates = discoverer.discover(temp, 3, List.of("archive"));

        assertThat(candidates).extracting(RepoCandidate::relativePath).containsExactly("keep-svc");
    }

    @Test
    @DisplayName("FR-2.4: a multi-module build is one repository, not one per module")
    void doesNotDescendIntoAMatchedRepository() {
        List<RepoCandidate> candidates = discoverer.discover(Fixtures.root());

        assertThat(candidates).extracting(RepoCandidate::relativePath)
                .noneMatch(path -> path.startsWith("log-shipment-svc/"));
    }

    @Test
    @DisplayName("FR-2.1: depth is bounded")
    void respectsMaxDepth(@TempDir Path temp) throws IOException {
        Path deep = temp.resolve("a/b/c/deep-svc");
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("build.sbt"), "name := \"deep-svc\"");

        assertThat(discoverer.discover(temp, 2, List.of())).isEmpty();
        assertThat(discoverer.discover(temp, 4, List.of()))
                .extracting(RepoCandidate::relativePath).containsExactly("a/b/c/deep-svc");
    }

    @Test
    void markersAreRecorded() {
        RepoCandidate orderSvc = discoverer.discover(Fixtures.root()).stream()
                .filter(candidate -> candidate.relativePath().equals("log-order-svc"))
                .findFirst()
                .orElseThrow();

        assertThat(orderSvc.markers()).contains("build.sbt", "project/build.properties");
        assertThat(orderSvc.directoryName()).isEqualTo("log-order-svc");
    }

    @Test
    void missingRootYieldsNothingRatherThanThrowing(@TempDir Path temp) {
        assertThat(discoverer.discover(temp.resolve("does-not-exist"))).isEmpty();
    }

    @Test
    @DisplayName("FR-1.2: preview counts candidates without parsing them")
    void previewCountsRepositories() {
        assertThat(discoverer.previewCount(Fixtures.root(), 3, List.of())).isEqualTo(9);
    }
}
