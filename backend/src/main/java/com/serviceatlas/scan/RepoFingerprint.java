package com.serviceatlas.scan;

import com.serviceatlas.config.ServiceAtlasProperties;
import com.serviceatlas.parser.common.RepoFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * A cheap, stable digest of the files a scan actually reads, so a re-scan can skip repositories
 * that cannot have changed (FR-7.3).
 *
 * <p>Two tiers, chosen for cost:
 *
 * <ul>
 *   <li><b>Build and config files</b> ({@code *.sbt}, {@code project/**}, {@code conf/**}) are
 *       hashed by <em>content</em>. They are small, they carry most of the signal, and an edit
 *       there almost always changes the graph.
 *   <li><b>Scala sources</b> are hashed by path, size and modification time. Reading every source
 *       file of twenty repositories just to decide whether to read them again would defeat the
 *       purpose; size+mtime is the standard incremental-build heuristic and misses only edits that
 *       preserve both.
 * </ul>
 */
public final class RepoFingerprint {

    private final RepoFiles files;

    public RepoFingerprint(Path root, ServiceAtlasProperties.Scan settings) {
        this.files = new RepoFiles(root, settings);
    }

    /** Hex SHA-256 over the repository's scan-relevant surface. */
    public String compute() {
        MessageDigest digest = sha256();
        List<String> entries = new ArrayList<>();

        for (Path path : contentHashedFiles()) {
            files.readString(path).ifPresent(content ->
                    entries.add("c|" + files.relativize(path) + "|" + sha256Hex(content)));
        }
        for (Path path : metadataHashedFiles()) {
            entries.add("m|" + files.relativize(path) + "|" + sizeOf(path) + "|" + modifiedAt(path));
        }

        Collections.sort(entries); // filesystem walk order is not guaranteed stable
        for (String entry : entries) {
            digest.update(entry.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private List<Path> contentHashedFiles() {
        List<Path> paths = new ArrayList<>();
        paths.addAll(files.find(null, path -> path.getFileName().toString().endsWith(".sbt")));
        paths.addAll(files.find("project", path -> true));
        paths.addAll(files.find("conf", path -> true));
        return paths;
    }

    private List<Path> metadataHashedFiles() {
        return files.findByExtension("src", ".scala", ".java", ".conf", ".properties");
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    private long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    private static String sha256Hex(String content) {
        return HexFormat.of().formatHex(sha256().digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
