package com.serviceatlas.parser.common;

import com.serviceatlas.config.ServiceAtlasProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Read-only, sandboxed access to one repository's files (Q-4, FR-3.7).
 *
 * <p>This class is the <em>only</em> way parsers touch the filesystem, and it deliberately exposes
 * no write, delete, move or create operation. Two further protections:
 *
 * <ul>
 *   <li><b>Containment</b> — every path is resolved and normalised against the repo root; anything
 *       that escapes it (a {@code ../} in a glob, a symlink pointing outside) is refused.
 *   <li><b>Budgets</b> — file count and file size limits keep a pathological repository from
 *       stalling a scan.
 * </ul>
 */
public final class RepoFiles {

    private final Path root;
    private final ServiceAtlasProperties.Scan settings;
    private final Set<String> ignoredDirectories;

    public RepoFiles(Path root, ServiceAtlasProperties.Scan settings) {
        this.root = root.toAbsolutePath().normalize();
        this.settings = settings;
        this.ignoredDirectories = Set.copyOf(settings.getIgnoredDirectories());
    }

    public Path root() {
        return root;
    }

    /** Repo-relative, forward-slashed path — the form used in {@link com.serviceatlas.graph.model.Evidence}. */
    public String relativize(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            return absolute.toString();
        }
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    public boolean exists(String relativePath) {
        return resolve(relativePath).filter(Files::isRegularFile).isPresent();
    }

    public Optional<Path> file(String relativePath) {
        return resolve(relativePath).filter(Files::isRegularFile);
    }

    /**
     * Reads a repo-relative text file. Returns empty for a missing, oversized or binary file rather
     * than throwing — parsers treat "not readable" and "not there" the same way.
     */
    public Optional<String> readString(String relativePath) {
        return file(relativePath).flatMap(this::readString);
    }

    public Optional<String> readString(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > settings.getMaxFileSizeBytes()) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
        } catch (MalformedInputException e) {
            return Optional.empty(); // binary or non-UTF-8: not source we can reason about
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Reads a file as a line-addressable {@link TextSource}, ready for evidence-producing scans. */
    public Optional<TextSource> readSource(String relativePath) {
        return file(relativePath).flatMap(this::readSource);
    }

    public Optional<TextSource> readSource(Path path) {
        return readString(path).map(content -> new TextSource(relativize(path), content));
    }

    /**
     * Walks the repository, honouring the ignore list and the file budget.
     *
     * @param subdirectory repo-relative directory to start from, or {@code null} for the root
     * @param filter       which files to return
     */
    public List<Path> find(String subdirectory, Predicate<Path> filter) {
        Path start = subdirectory == null || subdirectory.isBlank()
                ? root
                : resolve(subdirectory).orElse(null);
        if (start == null || !Files.isDirectory(start)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(start, FileVisitOptionsHolder.MAX_DEPTH)) {
            walk.filter(this::isNotIgnored)
                    .filter(Files::isRegularFile)
                    .filter(filter)
                    .limit(settings.getMaxFilesPerRepo())
                    .forEach(found::add);
        } catch (IOException | UncheckedIOException e) {
            // A partially readable tree still yields useful results; report what we got.
            return found;
        }
        return found;
    }

    /** Convenience: every file under {@code subdirectory} whose name ends with one of the suffixes. */
    public List<Path> findByExtension(String subdirectory, String... suffixes) {
        return find(subdirectory, path -> {
            String name = path.getFileName().toString();
            for (String suffix : suffixes) {
                if (name.endsWith(suffix)) {
                    return true;
                }
            }
            return false;
        });
    }

    private boolean isNotIgnored(Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        for (Path segment : relative) {
            if (ignoredDirectories.contains(segment.toString())) {
                return false;
            }
        }
        return true;
    }

    /** Resolves a repo-relative path, refusing anything that escapes the repository root. */
    private Optional<Path> resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return Optional.empty();
        }
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root)) {
            return Optional.empty();
        }
        try {
            // Refuse symlinks that leave the repository; real path must still be inside.
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(candidate)) {
                Path real = candidate.toRealPath();
                if (!real.startsWith(root)) {
                    return Optional.empty();
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private static final class FileVisitOptionsHolder {
        /** Deep enough for src/main/scala/<org>/<pkg>/... trees, shallow enough to stay quick. */
        private static final int MAX_DEPTH = 12;

        private FileVisitOptionsHolder() {
        }
    }
}
