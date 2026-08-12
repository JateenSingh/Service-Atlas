package com.serviceatlas.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceatlas.export.DiagramView;
import com.serviceatlas.export.PngDiagramRenderer;
import com.serviceatlas.export.SvgDiagramRenderer;
import com.serviceatlas.export.api.LucidApiClient;
import com.serviceatlas.export.importformat.LucidDocument;
import com.serviceatlas.export.importformat.LucidDocumentBuilder;
import com.serviceatlas.export.importformat.LucidPackager;
import com.serviceatlas.persistence.WorkspaceEntity;
import com.serviceatlas.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Export endpoints (§5, FR-6).
 *
 * <p>Every export takes the {@link DiagramView} the client is currently showing, which is what makes
 * "export what I see" (FR-6.3) structural: the server never re-derives the filters or the layout,
 * so it cannot disagree with the screen.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/export")
public class ExportController {

    private final WorkspaceService workspaces;
    private final LucidDocumentBuilder lucidBuilder;
    private final LucidPackager lucidPackager;
    private final LucidApiClient lucidApi;
    private final SvgDiagramRenderer svgRenderer;
    private final PngDiagramRenderer pngRenderer;
    private final ObjectMapper objectMapper;

    public ExportController(WorkspaceService workspaces, LucidDocumentBuilder lucidBuilder,
                            LucidPackager lucidPackager, LucidApiClient lucidApi,
                            SvgDiagramRenderer svgRenderer, PngDiagramRenderer pngRenderer,
                            ObjectMapper objectMapper) {
        this.workspaces = workspaces;
        this.lucidBuilder = lucidBuilder;
        this.lucidPackager = lucidPackager;
        this.lucidApi = lucidApi;
        this.svgRenderer = svgRenderer;
        this.pngRenderer = pngRenderer;
        this.objectMapper = objectMapper;
    }

    /** FR-6.1 — the primary Lucid path: a {@code .lucid} file the user imports themselves. */
    @PostMapping("/lucid")
    public ResponseEntity<Resource> exportLucid(
            @PathVariable Long workspaceId, @RequestBody DiagramView view) {
        WorkspaceEntity workspace = workspaces.get(workspaceId);
        LucidDocument document = lucidBuilder.build(titled(view, workspace));
        byte[] archive = lucidPackager.pack(document);
        return download(archive, slug(workspace.getName()) + ".lucid", MediaType.APPLICATION_OCTET_STREAM);
    }

    /** FR-6.2 — the optional path: create the document directly in the user's Lucid account. */
    @PostMapping("/lucid-api")
    public LucidApiClient.CreatedDocument pushToLucid(
            @PathVariable Long workspaceId, @RequestBody DiagramView view) {
        WorkspaceEntity workspace = workspaces.get(workspaceId);
        LucidDocument document = lucidBuilder.build(titled(view, workspace));
        byte[] json = lucidPackager.toDocumentJson(document);
        return lucidApi.createDocument(workspaceId, json, workspace.getName(), "lucidchart");
    }

    /** Whether the API path is available, so the UI can hide it entirely when it is not (FR-6.2). */
    @GetMapping("/lucid-api/status")
    public LucidStatus lucidStatus(@PathVariable Long workspaceId) {
        workspaces.get(workspaceId);
        return new LucidStatus(lucidApi.isConfigured(), lucidApi.isConnected(workspaceId));
    }

    /** FR-6.4 — {@code svg}, {@code png} or {@code json}. */
    @PostMapping("/{format}")
    public ResponseEntity<Resource> exportFormat(
            @PathVariable Long workspaceId, @PathVariable String format, @RequestBody DiagramView view) {
        WorkspaceEntity workspace = workspaces.get(workspaceId);
        DiagramView titled = titled(view, workspace);
        String base = slug(workspace.getName());

        return switch (format.toLowerCase(Locale.ROOT)) {
            case "svg" -> download(
                    svgRenderer.render(titled).getBytes(StandardCharsets.UTF_8),
                    base + ".svg",
                    MediaType.valueOf("image/svg+xml"));
            case "png" -> download(pngRenderer.render(titled), base + ".png", MediaType.IMAGE_PNG);
            case "json" -> download(json(titled), base + ".json", MediaType.APPLICATION_JSON);
            default -> throw ApiException.badRequest(
                    "Unsupported export format '" + format + "'. Use svg, png, json or lucid.");
        };
    }

    private byte[] json(DiagramView view) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(view);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the diagram", e);
        }
    }

    /** The workspace name is the title unless the client supplied one. */
    private DiagramView titled(DiagramView view, WorkspaceEntity workspace) {
        if (view.title() != null && !view.title().isBlank()) {
            return view;
        }
        return new DiagramView(
                workspace.getName(), view.graph(), view.positions(), view.routes(), view.theme());
    }

    private ResponseEntity<Resource> download(byte[] content, String fileName, MediaType contentType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(contentType)
                .contentLength(content.length)
                .body(new ByteArrayResource(content));
    }

    private static String slug(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "service-atlas" : slug;
    }

    /**
     * @param configured whether the server has Lucid API credentials at all
     * @param connected  whether this workspace has a connected Lucid account
     */
    public record LucidStatus(boolean configured, boolean connected) {
    }
}
