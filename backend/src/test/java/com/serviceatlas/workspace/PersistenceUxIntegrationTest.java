package com.serviceatlas.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.serviceatlas.graph.GraphService;
import com.serviceatlas.graph.OverlayService;
import com.serviceatlas.graph.OverlaySet;
import com.serviceatlas.graph.model.EdgeType;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.graph.model.NodeType;
import com.serviceatlas.persistence.RepoScanStatus;
import com.serviceatlas.persistence.ScanEntity;
import com.serviceatlas.persistence.WorkspaceEntity;
import com.serviceatlas.scan.ScanService;
import com.serviceatlas.testsupport.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** M4 acceptance: overlays survive re-scans, re-scans are incremental, bundles round-trip. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:persistence-it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
class PersistenceUxIntegrationTest {

    @Autowired
    private WorkspaceService workspaces;
    @Autowired
    private ScanService scans;
    @Autowired
    private GraphService graphs;
    @Autowired
    private OverlayService overlays;
    @Autowired
    private AtlasBundleService bundles;

    private WorkspaceEntity workspaceOnFixtures() {
        return workspaces.create(
                "m4-" + System.nanoTime(), Fixtures.root().toString(), WorkspaceSettings.defaults());
    }

    @Test
    @DisplayName("FR-4.4: manual edits survive a re-scan")
    void overlaysSurviveRescan() {
        WorkspaceEntity workspace = workspaceOnFixtures();
        scans.scanNow(workspace.getId());

        overlays.patch(workspace.getId(), patch(builder -> builder
                .addedEdge("logordersvc", "logtrackingsvc", EdgeType.MESSAGING, "manual link")
                .hiddenEdge("logordersvc->logquotesvc:ARTIFACT")
                .note("logordersvc", "Owned by the orders team")
                .position("logordersvc", 120, 240)));

        scans.scanNow(workspace.getId());
        GraphService.GraphView view = graphs.forWorkspace(workspace.getId());

        assertThat(view.graph().edges())
                .extracting(edge -> edge.sourceKey() + "->" + edge.targetKey() + ":" + edge.type())
                .contains("logordersvc->logtrackingsvc:MESSAGING")
                .doesNotContain("logordersvc->logquotesvc:ARTIFACT");
        assertThat(view.graph().node("logordersvc").orElseThrow().metadata())
                .containsEntry("note", "Owned by the orders team");
        assertThat(view.positions()).containsKey("logordersvc");
        assertThat(view.positions().get("logordersvc").x()).isEqualTo(120);
        assertThat(view.conflicts())
                .as("the hidden edge came back, and the user should be told")
                .anySatisfy(conflict -> assertThat(conflict.target()).isEqualTo("logordersvc->logquotesvc:ARTIFACT"));
    }

    @Test
    @DisplayName("FR-4.4: an edit is undone by removing its overlay row")
    void overlaysCanBeUndone() {
        WorkspaceEntity workspace = workspaceOnFixtures();
        scans.scanNow(workspace.getId());

        overlays.patch(workspace.getId(), patch(builder -> builder.hiddenNode("logtrackingsvc")));
        assertThat(graphs.forWorkspace(workspace.getId()).graph().node("logtrackingsvc")).isEmpty();

        overlays.patch(workspace.getId(), patch(builder -> builder.unhideNode("logtrackingsvc")));
        assertThat(graphs.forWorkspace(workspace.getId()).graph().node("logtrackingsvc")).isPresent();
    }

    @Test
    @DisplayName("FR-7.3: an unchanged repository is reused rather than re-parsed")
    void rescanIsIncremental() {
        WorkspaceEntity workspace = workspaceOnFixtures();
        scans.scanNow(workspace.getId());

        ScanEntity second = scans.scanNow(workspace.getId());

        assertThat(scans.repoProgress(second.getId()))
                .as("nothing changed on disk between the two scans")
                .allSatisfy(repo -> assertThat(repo.getStatus()).isEqualTo(RepoScanStatus.UNCHANGED));

        // The reused result must be the same graph, not a hollowed-out one.
        assertThat(graphs.forWorkspace(workspace.getId()).graph().nodes()).hasSizeGreaterThanOrEqualTo(9);
        assertThat(graphs.forWorkspace(workspace.getId()).graph().edges()).isNotEmpty();
    }

    @Test
    @DisplayName("FR-7.3: a changed repository is re-parsed while its neighbours are reused")
    void changedRepositoriesAreReparsed(@TempDir Path temp) throws IOException {
        Path root = temp.resolve("repos");
        Path changing = root.resolve("log-changing-svc");
        Files.createDirectories(changing.resolve("project"));
        Files.writeString(changing.resolve("build.sbt"), "name := \"log-changing-svc\"\n");
        Files.writeString(changing.resolve("project/build.properties"), "sbt.version=1.10.1\n");

        Path stable = root.resolve("log-stable-svc");
        Files.createDirectories(stable.resolve("project"));
        Files.writeString(stable.resolve("build.sbt"), "name := \"log-stable-svc\"\n");
        Files.writeString(stable.resolve("project/build.properties"), "sbt.version=1.10.1\n");

        WorkspaceEntity workspace = workspaces.create(
                "incremental-" + System.nanoTime(), root.toString(), WorkspaceSettings.defaults());
        scans.scanNow(workspace.getId());

        // Now make one repo depend on the other.
        Files.writeString(changing.resolve("build.sbt"), """
                name := "log-changing-svc"
                libraryDependencies += "com.acme" %% "log-stable-svc-client" % "1.0.0"
                """);

        ScanEntity second = scans.scanNow(workspace.getId());

        Map<String, RepoScanStatus> statuses = scans.repoProgress(second.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        repo -> repo.getRepoPath(), repo -> repo.getStatus()));
        assertThat(statuses).containsEntry("log-changing-svc", RepoScanStatus.DONE);
        assertThat(statuses).containsEntry("log-stable-svc", RepoScanStatus.UNCHANGED);

        assertThat(graphs.forWorkspace(workspace.getId()).graph().edges())
                .as("the new dependency was picked up even though the target repo was reused")
                .extracting(edge -> edge.sourceKey() + "->" + edge.targetKey())
                .contains("logchangingsvc->logstablesvc");
    }

    @Test
    @DisplayName("FR-7.3: a forced scan re-parses everything")
    void forcedScanSkipsReuse() {
        WorkspaceEntity workspace = workspaceOnFixtures();
        scans.scanNow(workspace.getId());

        ScanEntity forced = scans.scanNow(workspace.getId(), true);

        assertThat(scans.repoProgress(forced.getId()))
                .allSatisfy(repo -> assertThat(repo.getStatus()).isEqualTo(RepoScanStatus.DONE));
    }

    @Test
    @DisplayName("FR-1.4: a bundle round-trips the graph and the overlay, without source code")
    void bundleRoundTrip() {
        WorkspaceEntity original = workspaceOnFixtures();
        scans.scanNow(original.getId());
        overlays.patch(original.getId(), patch(builder -> builder
                .note("logordersvc", "Critical path")
                .position("logordersvc", 42, 84)));

        byte[] bundle = bundles.export(original.getId());
        assertThat(bundle).isNotEmpty();
        assertThat(new String(bundle, java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("a bundle carries the picture, never the code")
                .doesNotContain("libraryDependencies")
                .doesNotContain("class OrderController");

        AtlasBundleService.Manifest manifest = bundles.inspect(bundle);
        assertThat(manifest.version()).isEqualTo(AtlasBundleService.BUNDLE_VERSION);
        assertThat(manifest.nodeCount()).isGreaterThan(0);

        WorkspaceEntity restored = bundles.importBundle(bundle, "restored-" + System.nanoTime());
        GraphService.GraphView view = graphs.forWorkspace(restored.getId());

        assertThat(view.graph().nodes()).extracting(GraphNode::displayName).contains("log-order-svc");
        assertThat(view.graph().edges()).isNotEmpty();
        assertThat(view.positions()).containsKey("logordersvc");
        assertThat(view.graph().node("logordersvc").orElseThrow().metadata())
                .containsEntry("note", "Critical path");
    }

    @Test
    @DisplayName("A bundle whose root path does not exist here still imports")
    void bundleImportsWhenRepositoriesAreAbsent() {
        WorkspaceEntity original = workspaceOnFixtures();
        scans.scanNow(original.getId());
        byte[] bundle = bundles.export(original.getId());

        workspaces.delete(original.getId());
        WorkspaceEntity restored = bundles.importBundle(bundle, "no-repos-" + System.nanoTime());

        assertThat(graphs.forWorkspace(restored.getId()).graph().nodes()).isNotEmpty();
    }

    @Test
    @DisplayName("A file that is not a bundle is rejected with an explanation")
    void rejectsGarbage() {
        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> bundles.importBundle("not a zip".getBytes(), "x")))
                .hasMessageContaining("not a readable .atlas bundle");
    }

    @Test
    @DisplayName("Manual nodes and edges can be created together")
    void manualNodesAndEdges() {
        WorkspaceEntity workspace = workspaceOnFixtures();
        scans.scanNow(workspace.getId());

        overlays.patch(workspace.getId(), patch(builder -> builder
                .addedNode("mainframe", "Mainframe", NodeType.EXTERNAL, "Not in git")
                .addedEdge("logordersvc", "mainframe", EdgeType.HTTP, "nightly batch")));

        assertThat(graphs.forWorkspace(workspace.getId()).graph().node("mainframe")).isPresent();
        assertThat(graphs.forWorkspace(workspace.getId()).graph().edges())
                .extracting(edge -> edge.sourceKey() + "->" + edge.targetKey())
                .contains("logordersvc->mainframe");
    }

    // ------------------------------------------------------------------ helpers

    private OverlayService.OverlayPatch patch(java.util.function.Consumer<PatchBuilder> configure) {
        PatchBuilder builder = new PatchBuilder();
        configure.accept(builder);
        return builder.build();
    }

    /** Small builder so the tests read as intent rather than as fourteen positional nulls. */
    private static final class PatchBuilder {
        private final Map<String, OverlaySet.Position> positions = new java.util.LinkedHashMap<>();
        private final List<OverlaySet.ManualNode> addedNodes = new java.util.ArrayList<>();
        private final List<String> hiddenNodes = new java.util.ArrayList<>();
        private final List<OverlayService.ManualEdgeRequest> addedEdges = new java.util.ArrayList<>();
        private final List<String> hiddenEdges = new java.util.ArrayList<>();
        private final Map<String, String> notes = new java.util.LinkedHashMap<>();
        private final List<String> unhideNodes = new java.util.ArrayList<>();

        PatchBuilder position(String key, double x, double y) {
            positions.put(key, new OverlaySet.Position(x, y));
            return this;
        }

        PatchBuilder addedNode(String key, String name, NodeType type, String note) {
            addedNodes.add(new OverlaySet.ManualNode(key, name, type, note));
            return this;
        }

        PatchBuilder hiddenNode(String key) {
            hiddenNodes.add(key);
            return this;
        }

        PatchBuilder unhideNode(String key) {
            unhideNodes.add(key);
            return this;
        }

        PatchBuilder addedEdge(String source, String target, EdgeType type, String label) {
            addedEdges.add(new OverlayService.ManualEdgeRequest(source, target, type, label));
            return this;
        }

        PatchBuilder hiddenEdge(String id) {
            hiddenEdges.add(id);
            return this;
        }

        PatchBuilder note(String key, String note) {
            notes.put(key, note);
            return this;
        }

        OverlayService.OverlayPatch build() {
            return new OverlayService.OverlayPatch(
                    positions, addedNodes, hiddenNodes, addedEdges, hiddenEdges, notes, Map.of(),
                    List.of(), List.of(), unhideNodes, List.of(), List.of(), List.of(), List.of(), false);
        }
    }
}
