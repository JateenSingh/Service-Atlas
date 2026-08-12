package com.serviceatlas.parser.scala;

import static org.assertj.core.api.Assertions.assertThat;

import com.serviceatlas.parser.common.RepoFiles;
import com.serviceatlas.testsupport.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SbtBuildParserTest {

    private SbtBuildParser parserFor(Path repo) {
        return new SbtBuildParser(new RepoFiles(repo, Fixtures.scanSettings()));
    }

    @Test
    @DisplayName("FR-2.2: name, organization, Scala version and SBT version come from the build")
    void readsServiceMetadata() {
        SbtBuild build = parserFor(Fixtures.repo("log-order-svc")).parse("wrong-fallback");

        assertThat(build.name()).isEqualTo("log-order-svc");
        assertThat(build.nameFromBuild()).isTrue();
        assertThat(build.organization()).isEqualTo("com.acme.logistics");
        assertThat(build.scalaVersion()).isEqualTo("2.13.14");
        assertThat(build.sbtVersion()).isEqualTo("1.10.1");
    }

    @Test
    @DisplayName("FR-2.2: the directory name is the fallback when the build declares no name")
    void fallsBackToDirectoryName(@TempDir Path temp) throws IOException {
        Path repo = temp.resolve("log-nameless-svc");
        Files.createDirectories(repo.resolve("project"));
        Files.writeString(repo.resolve("build.sbt"), "scalaVersion := \"2.13.14\"\n");
        Files.writeString(repo.resolve("project/build.properties"), "sbt.version=1.9.9\n");

        SbtBuild build = parserFor(repo).parse("log-nameless-svc");

        assertThat(build.name()).isEqualTo("log-nameless-svc");
        assertThat(build.nameFromBuild()).isFalse();
        assertThat(build.sbtVersion()).isEqualTo("1.9.9");
    }

    @Test
    @DisplayName("FR-3.1: every libraryDependencies coordinate is captured with file and line")
    void readsDependencyCoordinates() {
        SbtBuild build = parserFor(Fixtures.repo("log-order-svc")).parse("log-order-svc");

        assertThat(build.dependencies())
                .extracting(ArtifactCoordinate::coordinates)
                .contains(
                        "com.acme.logistics:log-quote-svc-client",
                        "com.acme.logistics:log-user-svc-client",
                        "com.acme.logistics:log-pricing-svc-client",
                        "org.typelevel:cats-core");

        ArtifactCoordinate quoteClient = build.dependencies().stream()
                .filter(dependency -> dependency.artifact().equals("log-quote-svc-client"))
                .findFirst()
                .orElseThrow();
        assertThat(quoteClient.file()).isEqualTo("build.sbt");
        assertThat(quoteClient.line()).isPositive();
        assertThat(quoteClient.version()).isEqualTo("1.4.0");
        assertThat(quoteClient.sourceLine()).contains("log-quote-svc-client");
    }

    @Test
    @DisplayName("Cross-build suffixes are stripped so _2.13 artifacts still match their service")
    void stripsScalaSuffix() {
        ArtifactCoordinate coordinate = new ArtifactCoordinate(
                "com.acme.logistics", "log-quote-svc-client_2.13", "1.4.0", "build.sbt", 3, "line");

        assertThat(coordinate.artifactWithoutScalaSuffix()).isEqualTo("log-quote-svc-client");
    }

    @Test
    @DisplayName("FR-2.4: multi-module builds expose their deployable modules")
    void readsMultiModuleBuild() {
        SbtBuild build = parserFor(Fixtures.repo("log-shipment-svc")).parse("log-shipment-svc");

        assertThat(build.name()).isEqualTo("log-shipment-svc");
        assertThat(build.modules()).extracting(SbtModule::id).contains("api", "domain");

        SbtModule api = build.modules().stream()
                .filter(module -> module.id().equals("api")).findFirst().orElseThrow();
        assertThat(api.name()).isEqualTo("log-shipment-api");
        assertThat(api.relativePath()).isEqualTo("modules/api");
        assertThat(api.deployable()).as("has its own conf/routes").isTrue();

        SbtModule domain = build.modules().stream()
                .filter(module -> module.id().equals("domain")).findFirst().orElseThrow();
        assertThat(domain.deployable()).as("library module, no routes or main class").isFalse();
    }

    @Test
    @DisplayName("Dependencies declared in project/*.scala are found too")
    void readsDependenciesFromProjectScala(@TempDir Path temp) throws IOException {
        Path repo = temp.resolve("log-helper-svc");
        Files.createDirectories(repo.resolve("project"));
        Files.writeString(repo.resolve("build.sbt"), """
                name := "log-helper-svc"
                libraryDependencies ++= Dependencies.all
                """);
        Files.writeString(repo.resolve("project/Dependencies.scala"), """
                import sbt._

                object Dependencies {
                  val quoteClient = "com.acme.logistics" %% "log-quote-svc-client" % "1.4.0"
                  val all = Seq(quoteClient)
                }
                """);

        SbtBuild build = parserFor(repo).parse("log-helper-svc");

        assertThat(build.dependencies())
                .extracting(ArtifactCoordinate::coordinates)
                .contains("com.acme.logistics:log-quote-svc-client");
    }

    @Test
    @DisplayName("Non-dependency string pairs are not mistaken for coordinates")
    void ignoresNonDependencyStringPairs(@TempDir Path temp) throws IOException {
        Path repo = temp.resolve("log-noise-svc");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("build.sbt"), """
                name := "log-noise-svc"
                addCommandAlias("ci", "test")
                javaOptions ++= Seq("-Dfoo", "-Dbar")
                libraryDependencies += "com.acme.logistics" %% "log-quote-svc-client" % "1.4.0"
                """);

        SbtBuild build = parserFor(repo).parse("log-noise-svc");

        assertThat(build.dependencies()).extracting(ArtifactCoordinate::coordinates)
                .containsExactly("com.acme.logistics:log-quote-svc-client");
    }

    @Test
    @DisplayName("An unreadable build produces a warning rather than an exception")
    void missingBuildIsAWarningNotAFailure(@TempDir Path temp) throws IOException {
        Path repo = temp.resolve("log-empty-svc");
        Files.createDirectories(repo.resolve("project"));
        Files.writeString(repo.resolve("project/build.properties"), "sbt.version=1.10.1\n");

        SbtBuild build = parserFor(repo).parse("log-empty-svc");

        assertThat(build.name()).isEqualTo("log-empty-svc");
        assertThat(build.warnings()).isNotEmpty();
        assertThat(build.dependencies()).isEmpty();
    }

    @Test
    void looksLikeSbtRepoRecognisesBothMarkers(@TempDir Path temp) throws IOException {
        Path withBuildSbt = Files.createDirectories(temp.resolve("a"));
        Files.writeString(withBuildSbt.resolve("build.sbt"), "name := \"a\"");
        Path withProperties = Files.createDirectories(temp.resolve("b/project"));
        Files.writeString(withProperties.resolve("build.properties"), "sbt.version=1.10.1");
        Path notARepo = Files.createDirectories(temp.resolve("c"));

        assertThat(SbtBuildParser.looksLikeSbtRepo(withBuildSbt)).isTrue();
        assertThat(SbtBuildParser.looksLikeSbtRepo(temp.resolve("b"))).isTrue();
        assertThat(SbtBuildParser.looksLikeSbtRepo(notARepo)).isFalse();
    }

    @Test
    @DisplayName("FR-2.2: frameworks are detected from dependencies and structure")
    void detectsFrameworks() {
        List<String> detected = List.of(
                frameworkOf("log-order-svc"),
                frameworkOf("log-pricing-svc"),
                frameworkOf("log-notification-svc"),
                frameworkOf("log-tracking-svc"));

        assertThat(detected).containsExactly(
                FrameworkDetector.PLAY,
                FrameworkDetector.HTTP4S,
                FrameworkDetector.AKKA_HTTP,
                FrameworkDetector.GRPC);
    }

    private String frameworkOf(String repoName) {
        Path repo = Fixtures.repo(repoName);
        RepoFiles files = new RepoFiles(repo, Fixtures.scanSettings());
        SbtBuild build = new SbtBuildParser(files).parse(repoName);
        return FrameworkDetector.detect(build.dependencies(), files);
    }
}
