package com.serviceatlas.api;

import com.serviceatlas.api.dto.WorkspaceDtos.WorkspaceResponse;
import com.serviceatlas.persistence.WorkspaceEntity;
import com.serviceatlas.workspace.AtlasBundleService;
import com.serviceatlas.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Portable {@code .atlas} bundles (§5, FR-1.4). */
@RestController
@RequestMapping("/api/v1/workspaces")
public class BundleController {

    private final AtlasBundleService bundles;
    private final WorkspaceService workspaces;

    public BundleController(AtlasBundleService bundles, WorkspaceService workspaces) {
        this.bundles = bundles;
        this.workspaces = workspaces;
    }

    @GetMapping("/{workspaceId}/bundle")
    public ResponseEntity<Resource> download(@PathVariable Long workspaceId) {
        WorkspaceEntity workspace = workspaces.get(workspaceId);
        byte[] bundle = bundles.export(workspaceId);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileNameFor(workspace.getName()), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bundle.length)
                .body(new ByteArrayResource(bundle));
    }

    /** Multipart upload — what a file input posts. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<WorkspaceResponse> importMultipart(
            @RequestParam("file") MultipartFile file, @RequestParam(required = false) String name) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("No bundle was uploaded");
        }
        try {
            return created(bundles.importBundle(file.getBytes(), name));
        } catch (IOException e) {
            throw ApiException.badRequest("The uploaded bundle could not be read: " + e.getMessage());
        }
    }

    /** Raw body upload — for curl and scripts. */
    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<WorkspaceResponse> importRaw(
            @RequestBody byte[] bundle, @RequestParam(required = false) String name) {
        return created(bundles.importBundle(bundle, name));
    }

    private ResponseEntity<WorkspaceResponse> created(WorkspaceEntity workspace) {
        return ResponseEntity
                .created(java.net.URI.create("/api/v1/workspaces/" + workspace.getId()))
                .body(WorkspaceResponse.of(workspace, workspaces.settingsOf(workspace), null));
    }

    private static String fileNameFor(String workspaceName) {
        String slug = workspaceName.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return (slug.isEmpty() ? "workspace" : slug) + ".atlas";
    }
}
