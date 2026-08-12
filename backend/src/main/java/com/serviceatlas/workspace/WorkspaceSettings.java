package com.serviceatlas.workspace;

import java.util.List;

/**
 * Per-workspace scan configuration (FR-2.1 depth, FR-2.3 ignore list). Stored as JSON in
 * {@code workspace.settings_json} so adding a knob does not need a migration.
 *
 * @param maxDepth           how deep to look for repository roots; null means the global default
 * @param ignoredDirectories extra directory names to skip, on top of the built-in list
 */
public record WorkspaceSettings(Integer maxDepth, List<String> ignoredDirectories) {

    public WorkspaceSettings {
        ignoredDirectories = ignoredDirectories == null ? List.of() : List.copyOf(ignoredDirectories);
        if (maxDepth != null && (maxDepth < 1 || maxDepth > 12)) {
            throw new IllegalArgumentException("maxDepth must be between 1 and 12");
        }
    }

    public static WorkspaceSettings defaults() {
        return new WorkspaceSettings(null, List.of());
    }

    public int maxDepthOr(int fallback) {
        return maxDepth == null ? fallback : maxDepth;
    }
}
