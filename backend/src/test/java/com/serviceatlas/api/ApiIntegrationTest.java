package com.serviceatlas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceatlas.persistence.WorkspaceEntity;
import com.serviceatlas.scan.ScanService;
import com.serviceatlas.testsupport.Fixtures;
import com.serviceatlas.workspace.WorkspaceService;
import com.serviceatlas.workspace.WorkspaceSettings;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP contract in §5, exercised through the real stack. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:api-it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private WorkspaceService workspaces;
    @Autowired
    private ScanService scans;

    private WorkspaceEntity scannedWorkspace() {
        WorkspaceEntity workspace = workspaces.create(
                "api-" + System.nanoTime(), Fixtures.root().toString(), WorkspaceSettings.defaults());
        scans.scanNow(workspace.getId());
        return workspace;
    }

    @Test
    @DisplayName("FR-1.1: creating a workspace returns 201 with a Location header")
    void createsWorkspace() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "created-" + System.nanoTime(), "rootPath", Fixtures.root().toString()));

        mvc.perform(post("/api/v1/workspaces").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.rootPath").value(Fixtures.root().toString()));
    }

    @Test
    @DisplayName("§5: errors are RFC 7807 problem+json")
    void errorsAreProblemJson() throws Exception {
        mvc.perform(get("/api/v1/workspaces/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/problems/not-found"))
                .andExpect(jsonPath("$.detail").value("Workspace 999999 does not exist"));

        String badPath = objectMapper.writeValueAsString(
                Map.of("name", "x", "rootPath", "/definitely/not/here"));
        mvc.perform(post("/api/v1/workspaces").contentType(MediaType.APPLICATION_JSON).content(badPath))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("does not exist")));

        String blankName = objectMapper.writeValueAsString(Map.of("name", "", "rootPath", "/tmp"));
        mvc.perform(post("/api/v1/workspaces").contentType(MediaType.APPLICATION_JSON).content(blankName))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/invalid-request"));
    }

    @Test
    @DisplayName("FR-1.2: preview reports the repository count before anything is created")
    void previewsRootPath() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("rootPath", Fixtures.root().toString()));

        mvc.perform(post("/api/v1/workspaces/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoCount").value(9))
                .andExpect(jsonPath("$.sampleRepoPaths").isArray());
    }

    @Test
    @DisplayName("FR-1.3: workspaces can be listed, fetched with history, and deleted")
    void workspaceLifecycle() throws Exception {
        WorkspaceEntity workspace = scannedWorkspace();

        mvc.perform(get("/api/v1/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/v1/workspaces/" + workspace.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace.id").value(workspace.getId()))
                .andExpect(jsonPath("$.scans[0].status").value("COMPLETED"));

        mvc.perform(delete("/api/v1/workspaces/" + workspace.getId()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/workspaces/" + workspace.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FR-7.1: a scan is accepted asynchronously and its progress is readable")
    void scanLifecycle() throws Exception {
        WorkspaceEntity workspace = workspaces.create(
                "scan-api-" + System.nanoTime(), Fixtures.root().toString(), WorkspaceSettings.defaults());

        String response = mvc.perform(post("/api/v1/workspaces/" + workspace.getId() + "/scans"))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString();
        long scanId = objectMapper.readTree(response).get("id").asLong();

        mvc.perform(get("/api/v1/workspaces/" + workspace.getId() + "/scans/" + scanId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.id").value(scanId))
                .andExpect(jsonPath("$.repos").isArray());
    }

    @Test
    @DisplayName("A scan id from another workspace is a 404, not someone else's data")
    void scanIsScopedToItsWorkspace() throws Exception {
        WorkspaceEntity first = scannedWorkspace();
        WorkspaceEntity second = workspaces.create(
                "other-" + System.nanoTime(), Fixtures.root().toString(), WorkspaceSettings.defaults());
        long scanId = scans.historyFor(first.getId()).get(0).getId();

        mvc.perform(get("/api/v1/workspaces/" + second.getId() + "/scans/" + scanId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FR-4: the graph endpoint serves nodes, edges, positions and conflicts")
    void servesGraph() throws Exception {
        WorkspaceEntity workspace = scannedWorkspace();

        mvc.perform(get("/api/v1/workspaces/" + workspace.getId() + "/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeCount").isNumber())
                .andExpect(jsonPath("$.graph.nodes").isArray())
                .andExpect(jsonPath("$.graph.edges[0].evidence").isArray())
                .andExpect(jsonPath("$.positions").exists())
                .andExpect(jsonPath("$.conflicts").isArray());
    }

    @Test
    @DisplayName("FR-4.4: an overlay patch returns the merged graph")
    void patchesOverlay() throws Exception {
        WorkspaceEntity workspace = scannedWorkspace();
        String body = objectMapper.writeValueAsString(Map.of(
                "positions", Map.of("logordersvc", Map.of("x", 10, "y", 20)),
                "nodeNotes", Map.of("logordersvc", "Critical")));

        mvc.perform(patch("/api/v1/workspaces/" + workspace.getId() + "/graph/overlay")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions.logordersvc.x").value(10.0));
    }

    @Test
    @DisplayName("FR-6.1: the .lucid export is a ZIP holding a valid document.json")
    void exportsLucid() throws Exception {
        WorkspaceEntity workspace = scannedWorkspace();

        byte[] archive = mvc.perform(post("/api/v1/workspaces/" + workspace.getId() + "/export/lucid")
                        .contentType(MediaType.APPLICATION_JSON).content(diagramViewJson(workspace)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".lucid")))
                .andReturn().getResponse().getContentAsByteArray();

        String documentJson = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            var entry = zip.getNextEntry();
            assertThat(entry).isNotNull();
            assertThat(entry.getName()).isEqualTo("document.json");
            documentJson = new String(zip.readAllBytes());
        }
        JsonNode document = objectMapper.readTree(documentJson);
        assertThat(document.get("version").asInt()).isEqualTo(1);
        assertThat(document.get("pages").get(0).get("shapes")).isNotEmpty();
    }

    @Test
    @DisplayName("FR-6.4: svg, png and json exports each return their own content type")
    void exportsOtherFormats() throws Exception {
        WorkspaceEntity workspace = scannedWorkspace();
        String view = diagramViewJson(workspace);

        mvc.perform(post("/api/v1/workspaces/" + workspace.getId() + "/export/svg")
                        .contentType(MediaType.APPLICATION_JSON).content(view))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"));

        mvc.perform(post("/api/v1/workspaces/" + workspace.getId() + "/export/png")
                        .contentType(MediaType.APPLICATION_JSON).content(view))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));

        mvc.perform(post("/api/v1/workspaces/" + workspace.getId() + "/export/json")
                        .contentType(MediaType.APPLICATION_JSON).content(view))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        mvc.perform(post("/api/v1/workspaces/" + workspace.getId() + "/export/pdf")
                        .contentType(MediaType.APPLICATION_JSON).content(view))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("Unsupported export format")));
    }

    @Test
    @DisplayName("FR-6.2: the Lucid API path reports itself unconfigured rather than failing oddly")
    void lucidApiIsGatedByConfiguration() throws Exception {
        WorkspaceEntity workspace = scannedWorkspace();

        mvc.perform(get("/api/v1/workspaces/" + workspace.getId() + "/export/lucid-api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.connected").value(false));

        mvc.perform(post("/api/v1/workspaces/" + workspace.getId() + "/export/lucid-api")
                        .contentType(MediaType.APPLICATION_JSON).content(diagramViewJson(workspace)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/not-configured"));
    }

    @Test
    @DisplayName("FR-1.4: a bundle downloads and imports back through the API")
    void bundleRoundTripsOverHttp() throws Exception {
        WorkspaceEntity workspace = scannedWorkspace();

        byte[] bundle = mvc.perform(get("/api/v1/workspaces/" + workspace.getId() + "/bundle"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".atlas")))
                .andReturn().getResponse().getContentAsByteArray();

        mvc.perform(post("/api/v1/workspaces/import")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(bundle)
                        .param("name", "imported-" + System.nanoTime()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    @DisplayName("A mistyped API path is a JSON 404, not the Angular index page")
    void unknownApiPathsStayJson() throws Exception {
        mvc.perform(get("/api/v1/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /** A minimal but valid view: one node with a position, as the client would post. */
    private String diagramViewJson(WorkspaceEntity workspace) throws Exception {
        JsonNode graph = objectMapper.readTree(
                        mvc.perform(get("/api/v1/workspaces/" + workspace.getId() + "/graph"))
                                .andReturn().getResponse().getContentAsString())
                .get("graph");

        var positions = objectMapper.createObjectNode();
        int index = 0;
        for (JsonNode node : graph.get("nodes")) {
            var box = objectMapper.createObjectNode();
            box.put("x", index * 260);
            box.put("y", (index % 5) * 120);
            box.put("width", 200);
            box.put("height", 64);
            positions.set(node.get("key").asText(), box);
            index++;
        }

        var view = objectMapper.createObjectNode();
        view.put("title", workspace.getName());
        view.set("graph", graph);
        view.set("positions", positions);
        view.set("routes", objectMapper.createObjectNode());
        view.put("theme", "dark");
        return objectMapper.writeValueAsString(view);
    }
}
