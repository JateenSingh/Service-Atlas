package com.serviceatlas.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.serviceatlas.graph.GraphService;
import com.serviceatlas.graph.OverlayService;
import com.serviceatlas.graph.OverlaySet;
import com.serviceatlas.scan.ScanService;
import com.serviceatlas.testsupport.Fixtures;
import com.serviceatlas.workspace.AtlasBundleService;
import com.serviceatlas.workspace.WorkspaceService;
import com.serviceatlas.workspace.WorkspaceSettings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-002 claims one Flyway migration set runs unmodified on both H2 and PostgreSQL. This test is
 * what makes that a fact rather than an intention: the same migrations, entities and application
 * code run against a real PostgreSQL, and Hibernate validates the schema at startup.
 *
 * <p>Named {@code *ContainerTest} so it is excluded from the default build — it needs Docker. Run it
 * with {@code ./gradlew :backend:test -PwithTestcontainers}.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Testcontainers
class PostgresSchemaContainerTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("service_atlas")
            .withUsername("service_atlas")
            .withPassword("service_atlas");

    @org.springframework.test.context.DynamicPropertySource
    static void datasource(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

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

    @Test
    @DisplayName("ADR-002: the whole flow works on PostgreSQL with the same migrations")
    void theApplicationRunsOnPostgres() {
        // Hibernate's ddl-auto=validate has already checked every entity against the Flyway schema
        // by the time this method runs — a mismatch would have failed the context startup.
        var workspace = workspaces.create(
                "postgres-" + System.nanoTime(), Fixtures.root().toString(), WorkspaceSettings.defaults());

        var scan = scans.scanNow(workspace.getId());
        assertThat(scan.getStatus()).isEqualTo(ScanStatus.COMPLETED);

        var graph = graphs.forWorkspace(workspace.getId()).graph();
        assertThat(graph.nodes()).hasSizeGreaterThanOrEqualTo(9);
        assertThat(graph.edges()).isNotEmpty();
        assertThat(graph.edges().get(0).evidence())
                .as("the JSON payload columns round-trip on Postgres too")
                .isNotEmpty();

        overlays.patch(workspace.getId(), new OverlayService.OverlayPatch(
                Map.of("logordersvc", new OverlaySet.Position(12, 34)),
                List.of(), List.of(), List.of(), List.of(),
                Map.of("logordersvc", "note on postgres"), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false));

        var view = graphs.forWorkspace(workspace.getId());
        assertThat(view.positions()).containsKey("logordersvc");
        assertThat(view.graph().node("logordersvc").orElseThrow().metadata())
                .containsEntry("note", "note on postgres");

        byte[] bundle = bundles.export(workspace.getId());
        var restored = bundles.importBundle(bundle, "restored-postgres-" + System.nanoTime());
        assertThat(graphs.forWorkspace(restored.getId()).graph().nodes()).isNotEmpty();

        // Cascading delete is declared in the migration, so it must work on both engines.
        workspaces.delete(workspace.getId());
        assertThat(workspaces.list()).noneSatisfy(entry ->
                assertThat(entry.getId()).isEqualTo(workspace.getId()));
    }
}
