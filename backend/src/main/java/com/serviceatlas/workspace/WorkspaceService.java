package com.serviceatlas.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceatlas.api.ApiException;
import com.serviceatlas.config.ServiceAtlasProperties;
import com.serviceatlas.persistence.WorkspaceEntity;
import com.serviceatlas.persistence.WorkspaceRepository;
import com.serviceatlas.scan.RepoDiscoverer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workspace CRUD and root-path validation (FR-1.1 … FR-1.3). */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final WorkspaceRepository workspaces;
    private final RepoDiscoverer discoverer;
    private final ServiceAtlasProperties properties;
    private final ObjectMapper objectMapper;

    public WorkspaceService(WorkspaceRepository workspaces, RepoDiscoverer discoverer,
                            ServiceAtlasProperties properties, ObjectMapper objectMapper) {
        this.workspaces = workspaces;
        this.discoverer = discoverer;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkspaceEntity create(String name, String rootPath, WorkspaceSettings settings) {
        String trimmedName = name == null ? "" : name.strip();
        if (trimmedName.isEmpty()) {
            throw ApiException.badRequest("Workspace name must not be blank");
        }
        if (workspaces.existsByName(trimmedName)) {
            throw ApiException.conflict("A workspace named '" + trimmedName + "' already exists");
        }
        Path validated = validateRootPath(rootPath);
        WorkspaceSettings effective = settings == null ? WorkspaceSettings.defaults() : settings;
        WorkspaceEntity entity =
                new WorkspaceEntity(trimmedName, validated.toString(), writeSettings(effective));
        log.info("Created workspace '{}' at {}", trimmedName, validated);
        return workspaces.save(entity);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceEntity> list() {
        return workspaces.findAll();
    }

    @Transactional(readOnly = true)
    public WorkspaceEntity get(Long id) {
        return workspaces.findById(id).orElseThrow(() -> ApiException.notFound("Workspace", id));
    }

    @Transactional
    public WorkspaceEntity update(Long id, String name, String rootPath, WorkspaceSettings settings) {
        WorkspaceEntity entity = get(id);
        if (name != null && !name.isBlank() && !name.strip().equals(entity.getName())) {
            if (workspaces.existsByName(name.strip())) {
                throw ApiException.conflict("A workspace named '" + name.strip() + "' already exists");
            }
            entity.setName(name.strip());
        }
        if (rootPath != null && !rootPath.isBlank()) {
            entity.setRootPath(validateRootPath(rootPath).toString());
        }
        if (settings != null) {
            entity.setSettingsJson(writeSettings(settings));
        }
        return workspaces.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        WorkspaceEntity entity = get(id);
        // Scans, nodes, edges and overlays go with it via ON DELETE CASCADE.
        workspaces.delete(entity);
        log.info("Deleted workspace {}", id);
    }

    /**
     * FR-1.2 — validates a candidate root and reports how many repositories it contains, before the
     * user commits to a scan.
     */
    public RootPathPreview preview(String rootPath, WorkspaceSettings settings) {
        Path validated = validateRootPath(rootPath);
        WorkspaceSettings effective = settings == null ? WorkspaceSettings.defaults() : settings;
        int depth = effective.maxDepthOr(properties.getScan().getMaxDepth());
        var candidates = discoverer.discover(validated, depth, effective.ignoredDirectories());
        return new RootPathPreview(
                validated.toString(),
                candidates.size(),
                candidates.stream().map(candidate -> candidate.relativePath()).limit(50).toList());
    }

    public WorkspaceSettings settingsOf(WorkspaceEntity entity) {
        if (entity.getSettingsJson() == null || entity.getSettingsJson().isBlank()) {
            return WorkspaceSettings.defaults();
        }
        try {
            return objectMapper.readValue(entity.getSettingsJson(), WorkspaceSettings.class);
        } catch (Exception e) {
            log.warn("Unreadable settings for workspace {}; using defaults", entity.getId(), e);
            return WorkspaceSettings.defaults();
        }
    }

    /**
     * The path must exist, be a directory, and be readable. The scan itself is read-only (Q-4), so
     * write permission is deliberately not required or requested.
     */
    public Path validateRootPath(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) {
            throw ApiException.badRequest("Root path must not be blank");
        }
        Path path;
        try {
            path = Path.of(rootPath.strip()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw ApiException.badRequest("Not a valid filesystem path: " + rootPath);
        }
        if (!Files.exists(path)) {
            throw ApiException.badRequest("Path does not exist: " + path);
        }
        if (!Files.isDirectory(path)) {
            throw ApiException.badRequest("Path is not a directory: " + path);
        }
        if (!Files.isReadable(path)) {
            throw ApiException.badRequest("Path is not readable: " + path);
        }
        return path;
    }

    private String writeSettings(WorkspaceSettings settings) {
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (Exception e) {
            throw ApiException.badRequest("Could not store workspace settings: " + e.getMessage());
        }
    }

    /**
     * @param rootPath       the normalised absolute path
     * @param repoCount      how many repositories were found
     * @param sampleRepoPaths first few repository paths, so the user can sanity-check the folder
     */
    public record RootPathPreview(String rootPath, int repoCount, List<String> sampleRepoPaths) {
    }
}
