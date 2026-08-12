package com.serviceatlas.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.serviceatlas.graph.GraphService;
import com.serviceatlas.graph.model.DependencyGraph;
import com.serviceatlas.graph.model.EdgeType;
import com.serviceatlas.graph.model.GraphEdge;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.graph.model.NodeType;
import com.serviceatlas.persistence.RepoScanStatus;
import com.serviceatlas.persistence.ScanEntity;
import com.serviceatlas.persistence.ScanStatus;
import com.serviceatlas.persistence.WorkspaceEntity;
import com.serviceatlas.testsupport.Fixtures;
import com.serviceatlas.workspace.WorkspaceService;
import com.serviceatlas.workspace.WorkspaceSettings;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * M1 acceptance: scanning the fixture repository set produces the expected service nodes and
 * build-level dependency edges (FR-2, FR-3.1, FR-7).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:scan-it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
class ScanIntegrationTest {

    @Autowired
    private WorkspaceService workspaces;
    @Autowired
    private ScanService scans;
    @Autowired
    private GraphService graphs;

    private WorkspaceEntity workspace;

    @BeforeEach
    void createWorkspace() {
        String name = "fixtures-" + System.nanoTime();
        workspace = workspaces.create(name, Fixtures.root().toString(), WorkspaceSettings.defaults());
    }

    private DependencyGraph scanFixtures() {
        scans.scanNow(workspace.getId());
        return graphs.forWorkspace(workspace.getId()).graph();
    }

    @Test
    @DisplayName("FR-2.2: each fixture repository becomes a service node with its build metadata")
    void discoversServiceNodes() {
        DependencyGraph graph = scanFixtures();

        assertThat(graph.nodes())
                .filteredOn(node -> node.type() == NodeType.SERVICE)
                .extracting(GraphNode::displayName)
                .contains("log-order-svc", "log-quote-svc", "log-user-svc", "log-pricing-svc",
                        "log-inventory-svc", "log-notification-svc", "log-shipment-svc",
                        "log-audit-svc", "log-tracking-svc");

        GraphNode order = graph.node("logordersvc").orElseThrow();
        assertThat(order.framework()).isEqualTo("Play Framework");
        assertThat(order.scalaVersion()).isEqualTo("2.13.14");
        assertThat(order.sbtVersion()).isEqualTo("1.10.1");
        assertThat(order.repoPath()).isEqualTo("log-order-svc");
        assertThat(order.metadata()).containsEntry("organization", "com.acme.logistics");
    }

    @Test
    @DisplayName("FR-3.1: internal artifact dependencies become HIGH-confidence ARTIFACT edges")
    void buildsArtifactEdges() {
        DependencyGraph graph = scanFixtures();

        assertThat(artifactEdges(graph)).contains(
                "logordersvc->logquotesvc",
                "logordersvc->logusersvc",
                "logordersvc->logpricingsvc",
                "logquotesvc->logpricingsvc",
                "lognotificationsvc->logusersvc");

        GraphEdge edge = graph.edges().stream()
                .filter(e -> e.sourceKey().equals("logordersvc") && e.targetKey().equals("logquotesvc"))
                .findFirst()
                .orElseThrow();
        assertThat(edge.confidence()).isEqualTo(com.serviceatlas.graph.model.Confidence.HIGH);
        assertThat(edge.evidence()).isNotEmpty();
        assertThat(edge.evidence().get(0).file()).isEqualTo("build.sbt");
        assertThat(edge.evidence().get(0).line()).isPositive();
    }

    @Test
    @DisplayName("Third-party libraries never become nodes or edges")
    void ignoresThirdPartyDependencies() {
        DependencyGraph graph = scanFixtures();

        assertThat(graph.nodes()).extracting(GraphNode::displayName)
                .doesNotContain("cats-core", "logback-classic", "scalatest", "kafka-clients", "play-ahc-ws");
    }

    @Test
    @DisplayName("FR-2.4: a deployable module of a multi-module build becomes a nested node")
    void createsSubModuleNodes() {
        DependencyGraph graph = scanFixtures();

        GraphNode api = graph.nodes().stream()
                .filter(node -> node.type() == NodeType.SUB_MODULE)
                .filter(node -> node.displayName().equals("log-shipment-api"))
                .findFirst()
                .orElseThrow();

        assertThat(api.parentKey()).isEqualTo("logshipmentsvc");
        assertThat(api.repoPath()).isEqualTo("log-shipment-svc/modules/api");
        assertThat(graph.nodes())
                .as("the library-only module is not promoted to a node")
                .extracting(GraphNode::displayName)
                .doesNotContain("log-shipment-domain");
    }

    @Test
    @DisplayName("FR-7.1: per-repository progress is recorded for the whole set")
    void recordsPerRepositoryProgress() {
        ScanEntity scan = scans.scanNow(workspace.getId());

        assertThat(scan.getStatus()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(scans.repoProgress(scan.getId()))
                .hasSize(9)
                .allSatisfy(repo -> {
                    assertThat(repo.getStatus()).isEqualTo(RepoScanStatus.DONE);
                    assertThat(repo.getContentHash()).isNotBlank();
                    assertThat(repo.getDurationMs()).isNotNull();
                });

        ScanService.ScanSnapshot snapshot = scans.scanSnapshot(scan.getId());
        assertThat(snapshot.repoCount()).isEqualTo(9);
        assertThat(snapshot.completedCount()).isEqualTo(9);
        assertThat(snapshot.errorCount()).isZero();
    }

    @Test
    @DisplayName("FR-4.3: each scan is stored separately so history is preserved")
    void scansAreVersioned() {
        scanFixtures();
        scanFixtures();

        assertThat(scans.historyFor(workspace.getId())).hasSize(2);
        assertThat(graphs.forWorkspace(workspace.getId()).scanId())
                .isEqualTo(scans.historyFor(workspace.getId()).get(0).getId());
    }

    @Test
    @DisplayName("A workspace that has never been scanned serves an empty graph, not an error")
    void emptyWorkspaceServesEmptyGraph() {
        assertThat(graphs.forWorkspace(workspace.getId()).graph().nodes()).isEmpty();
        assertThat(graphs.forWorkspace(workspace.getId()).scanId()).isNull();
    }

    private List<String> artifactEdges(DependencyGraph graph) {
        return graph.edges().stream()
                .filter(edge -> edge.type() == EdgeType.ARTIFACT)
                .map(edge -> edge.sourceKey() + "->" + edge.targetKey())
                .toList();
    }
}
