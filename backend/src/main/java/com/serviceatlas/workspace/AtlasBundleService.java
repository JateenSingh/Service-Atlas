package com.serviceatlas.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceatlas.api.ApiException;
import com.serviceatlas.graph.GraphStore;
import com.serviceatlas.graph.OverlayService;
import com.serviceatlas.graph.OverlaySet;
import com.serviceatlas.graph.model.DependencyGraph;
import com.serviceatlas.persistence.ScanEntity;
import com.serviceatlas.persistence.ScanRepository;
import com.serviceatlas.persistence.ScanStatus;
import com.serviceatlas.persistence.WorkspaceEntity;
import com.serviceatlas.scan.ScanService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portable workspace bundles (FR-1.4).
 *
 * <p>A {@code .atlas} file is a ZIP holding a manifest, the graph, scan metadata and the user's
 * overlay. It explicitly does <b>not</b> contain source code: the point is to hand someone the
 * <em>picture</em> of an estate — including the edits you made by hand — without shipping the
 * estate itself. That also means a bundle is safe to attach to a ticket.
 */
@Service
public class AtlasBundleService {

    private static final Logger log = LoggerFactory.getLogger(AtlasBundleService.class);

    /** Bump when the bundle layout changes incompatibly. */
    public static final int BUNDLE_VERSION = 1;

    private static final String MANIFEST = "manifest.json";
    private static final String GRAPH = "graph.json";
    private static final String OVERLAY = "overlay.json";
    private static final String SCAN = "scan.json";

    /** Guards against a decompression bomb in an untrusted bundle. */
    private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;

    private final WorkspaceService workspaces;
    private final ScanService scans;
    private final ScanRepository scanRepository;
    private final GraphStore graphStore;
    private final OverlayService overlays;
    private final ObjectMapper objectMapper;

    public AtlasBundleService(WorkspaceService workspaces, ScanService scans, ScanRepository scanRepository,
                              GraphStore graphStore, OverlayService overlays, ObjectMapper objectMapper) {
        this.workspaces = workspaces;
        this.scans = scans;
        this.scanRepository = scanRepository;
        this.graphStore = graphStore;
        this.overlays = overlays;
        this.objectMapper = objectMapper;
    }

    /** Serialises a workspace to a {@code .atlas} bundle. */
    @Transactional(readOnly = true)
    public byte[] export(Long workspaceId) {
        WorkspaceEntity workspace = workspaces.get(workspaceId);
        Optional<ScanEntity> scan = scans.latestCompleted(workspaceId);
        DependencyGraph graph = scan.map(entity -> graphStore.load(entity.getId()))
                .orElseGet(DependencyGraph::empty);

        Manifest manifest = new Manifest(
                BUNDLE_VERSION,
                workspace.getName(),
                workspace.getRootPath(),
                workspaces.settingsOf(workspace),
                Instant.now(),
                graph.nodeCount(),
                graph.edgeCount());

        ScanMetadata scanMetadata = scan
                .map(entity -> new ScanMetadata(
                        entity.getStartedAt(),
                        entity.getFinishedAt(),
                        entity.getRepoCount(),
                        entity.getErrorCount(),
                        scans.repoProgress(entity.getId()).stream()
                                .map(repo -> new RepoMetadata(
                                        repo.getRepoPath(),
                                        repo.getDisplayName(),
                                        repo.getStatus().name(),
                                        repo.getContentHash(),
                                        repo.getDurationMs()))
                                .toList()))
                .orElseGet(() -> new ScanMetadata(null, null, 0, 0, List.of()));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            writeEntry(zip, MANIFEST, manifest);
            writeEntry(zip, GRAPH, graph);
            writeEntry(zip, SCAN, scanMetadata);
            writeEntry(zip, OVERLAY, overlays.load(workspaceId));
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the .atlas bundle", e);
        }
        return buffer.toByteArray();
    }

    /**
     * Restores a bundle as a new workspace.
     *
     * <p>The graph is imported as a completed scan so the workspace opens with a diagram
     * immediately, even on a machine where none of the repositories exist. Re-scanning replaces it
     * with the local truth, and the overlay survives that (FR-4.4).
     */
    @Transactional
    public WorkspaceEntity importBundle(byte[] bundle, String requestedName) {
        Map<String, byte[]> entries = readEntries(bundle);

        Manifest manifest = parse(entries.get(MANIFEST), Manifest.class, "manifest.json");
        if (manifest.version() > BUNDLE_VERSION) {
            throw ApiException.badRequest(
                    "This bundle was written by a newer version of Service Atlas (format "
                            + manifest.version() + ", this build understands " + BUNDLE_VERSION + ")");
        }
        DependencyGraph graph = entries.containsKey(GRAPH)
                ? parse(entries.get(GRAPH), DependencyGraph.class, "graph.json")
                : DependencyGraph.empty();
        OverlaySet overlay = entries.containsKey(OVERLAY)
                ? parse(entries.get(OVERLAY), OverlaySet.class, "overlay.json")
                : new OverlaySet();

        String name = uniqueName(
                requestedName != null && !requestedName.isBlank() ? requestedName.strip() : manifest.name());

        // The bundled root path usually does not exist on this machine, and that must not block the
        // import — the diagram is the point. Fall back to a path that does exist.
        String rootPath = usableRootPath(manifest.rootPath());
        WorkspaceEntity workspace = workspaces.create(name, rootPath, manifest.settings());

        if (graph.nodeCount() > 0) {
            ScanEntity imported = scanRepository.save(new ScanEntity(workspace.getId()));
            imported.setRepoCount(manifest.nodeCount());
            imported.setStatus(ScanStatus.COMPLETED);
            imported.setMessage("Imported from a .atlas bundle exported on " + manifest.exportedAt());
            scanRepository.save(imported);
            graphStore.save(imported.getId(), graph);
        }
        if (!overlay.isEmpty()) {
            overlays.replace(workspace.getId(), overlay);
        }

        log.info("Imported workspace '{}' from bundle ({} nodes, {} edges)",
                name, graph.nodeCount(), graph.edgeCount());
        return workspace;
    }

    /** Reads a bundle's manifest without importing it, so the UI can preview what it holds. */
    public Manifest inspect(byte[] bundle) {
        return parse(readEntries(bundle).get(MANIFEST), Manifest.class, "manifest.json");
    }

    private String usableRootPath(String bundled) {
        try {
            workspaces.validateRootPath(bundled);
            return bundled;
        } catch (ApiException e) {
            return System.getProperty("user.home");
        }
    }

    /** Importing the same bundle twice yields "Logistics" and "Logistics (2)", not a 409. */
    private String uniqueName(String base) {
        String candidate = base;
        int suffix = 2;
        while (nameTaken(candidate) && suffix <= 100) {
            candidate = base + " (" + suffix++ + ")";
        }
        return candidate;
    }

    private boolean nameTaken(String name) {
        return workspaces.list().stream().anyMatch(existing -> existing.getName().equals(name));
    }

    private Map<String, byte[]> readEntries(byte[] bundle) {
        if (bundle == null || bundle.length == 0) {
            throw ApiException.badRequest("The uploaded bundle is empty");
        }
        Map<String, byte[]> entries = new HashMap<>();
        boolean sawAnyEntry = false;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bundle), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                sawAnyEntry = true;
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                // Only the files we wrote are read, which also neutralises any path traversal
                // attempt in a hand-crafted archive.
                if (name.equals(MANIFEST) || name.equals(GRAPH) || name.equals(OVERLAY) || name.equals(SCAN)) {
                    entries.put(name, readLimited(zip));
                }
            }
        } catch (IOException e) {
            throw ApiException.badRequest("This file is not a readable .atlas bundle");
        }
        if (!sawAnyEntry) {
            // Not a ZIP at all: ZipInputStream reports this as "no entries" rather than an error.
            throw ApiException.badRequest("This file is not a readable .atlas bundle");
        }
        if (!entries.containsKey(MANIFEST)) {
            throw ApiException.badRequest(
                    "This ZIP is not a Service Atlas bundle: manifest.json is missing");
        }
        return entries;
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > MAX_ENTRY_BYTES) {
                throw ApiException.badRequest("This bundle contains an implausibly large entry");
            }
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    private void writeEntry(ZipOutputStream zip, String name, Object payload) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload));
        zip.closeEntry();
    }

    private <T> T parse(byte[] bytes, Class<T> type, String what) {
        if (bytes == null) {
            throw ApiException.badRequest("The bundle is missing " + what);
        }
        try {
            return objectMapper.readValue(bytes, type);
        } catch (IOException e) {
            throw ApiException.badRequest("The bundle's " + what + " could not be read: " + e.getMessage());
        }
    }

    /** Bundle manifest — everything needed to describe the export without opening the graph. */
    public record Manifest(
            int version,
            String name,
            String rootPath,
            WorkspaceSettings settings,
            Instant exportedAt,
            int nodeCount,
            int edgeCount) {
    }

    /** Scan metadata carried alongside the graph, for provenance. */
    public record ScanMetadata(
            Instant startedAt, Instant finishedAt, int repoCount, int errorCount, List<RepoMetadata> repos) {
    }

    public record RepoMetadata(
            String repoPath, String displayName, String status, String contentHash, Long durationMs) {
    }
}
