package com.serviceatlas.parser;

import java.nio.file.Path;

/**
 * A directory that looked like a repository during discovery (FR-2.1), before any parsing.
 *
 * @param root        absolute path to the repository root
 * @param relativePath path relative to the workspace root, used for display
 * @param markers      the files that made this look like a repo (e.g. {@code build.sbt})
 */
public record RepoCandidate(Path root, String relativePath, java.util.List<String> markers) {

    public RepoCandidate {
        markers = markers == null ? java.util.List.of() : java.util.List.copyOf(markers);
    }

    public String directoryName() {
        Path fileName = root.getFileName();
        return fileName == null ? root.toString() : fileName.toString();
    }
}
