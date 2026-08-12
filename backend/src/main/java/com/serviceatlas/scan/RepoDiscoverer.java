package com.serviceatlas.scan;

import com.serviceatlas.config.ServiceAtlasProperties;
import com.serviceatlas.parser.RepoCandidate;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Walks a workspace root and finds repositories (FR-2.1).
 *
 * <p>A directory is a repository when it contains one of the marker files. Once a directory
 * matches, we do <em>not</em> descend into it: a multi-module SBT build is one repository with
 * modules (FR-2.4), not a directory full of repositories.
 */
@Component
public class RepoDiscoverer {

    /** Marker files that identify an SBT repository (FR-2.1). */
    private static final List<String> MARKERS = List.of("build.sbt", "project/build.properties");

    private final ServiceAtlasProperties properties;

    public RepoDiscoverer(ServiceAtlasProperties properties) {
        this.properties = properties;
    }

    public List<RepoCandidate> discover(Path root) {
        return discover(root, properties.getScan().getMaxDepth(), List.of());
    }

    /**
     * @param root            workspace root
     * @param maxDepth        how deep to look for repository roots (FR-2.1, default 3)
     * @param extraIgnored    workspace-specific ignored directory names (FR-2.3)
     */
    public List<RepoCandidate> discover(Path root, int maxDepth, List<String> extraIgnored) {
        Path start = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(start)) {
            return List.of();
        }

        Set<String> ignored = new HashSet<>(properties.getScan().getIgnoredDirectories());
        ignored.addAll(extraIgnored);

        List<RepoCandidate> found = new ArrayList<>();
        try {
            // walkFileTree treats a directory sitting exactly at maxDepth as a leaf and never calls
            // preVisitDirectory for it, so a repo N levels down needs a walk limit of N + 1.
            int walkDepth = Math.max(0, maxDepth) + 1;
            Files.walkFileTree(start, Set.of(), walkDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    if (!dir.equals(start) && name != null
                            && (ignored.contains(name.toString()) || name.toString().startsWith("."))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    List<String> markers = markersIn(dir);
                    if (!markers.isEmpty()) {
                        found.add(new RepoCandidate(dir, relativeLabel(start, dir), markers));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // unreadable directory: skip, do not fail the scan
                }
            });
        } catch (IOException e) {
            return List.copyOf(found);
        }

        found.sort(Comparator.comparing(RepoCandidate::relativePath));
        return List.copyOf(found);
    }

    /** FR-1.2 — cheap candidate count shown before the user commits to a full scan. */
    public int previewCount(Path root, int maxDepth, List<String> extraIgnored) {
        return discover(root, maxDepth, extraIgnored).size();
    }

    private static List<String> markersIn(Path dir) {
        List<String> markers = new ArrayList<>();
        for (String marker : MARKERS) {
            if (Files.isRegularFile(dir.resolve(marker))) {
                markers.add(marker);
            }
        }
        return markers;
    }

    private static String relativeLabel(Path root, Path dir) {
        if (dir.equals(root)) {
            Path name = dir.getFileName();
            return name == null ? dir.toString() : name.toString();
        }
        return root.relativize(dir).toString().replace('\\', '/');
    }
}
