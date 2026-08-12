package com.serviceatlas.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.serviceatlas.persistence.ScanEntity;
import com.serviceatlas.persistence.WorkspaceEntity;
import com.serviceatlas.testsupport.Fixtures;
import com.serviceatlas.workspace.WorkspaceService;
import com.serviceatlas.workspace.WorkspaceSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Q-4: the scanner must never modify a scanned repository.
 *
 * <p>Asserted rather than assumed — the whole fixture tree is hashed (path, size, content digest,
 * modification time) before and after a scan and the two snapshots must be identical.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:readonly-it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
class ReadOnlyScanTest {

    @Autowired
    private WorkspaceService workspaces;
    @Autowired
    private ScanService scans;

    @Test
    @DisplayName("Q-4: a full scan leaves every scanned file byte-identical and untouched")
    void scanningDoesNotModifyRepositories() throws IOException {
        Map<String, String> before = snapshot(Fixtures.root());

        WorkspaceEntity workspace = workspaces.create(
                "readonly-" + System.nanoTime(), Fixtures.root().toString(), WorkspaceSettings.defaults());
        scans.scanNow(workspace.getId());

        Map<String, String> after = snapshot(Fixtures.root());

        assertThat(after).as("no file added, removed, edited or re-stamped").isEqualTo(before);
    }

    /** path → digest of (size, content, last-modified). */
    private Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> snapshot = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    digest.update(Files.readAllBytes(path));
                    digest.update(Long.toString(Files.size(path)).getBytes());
                    digest.update(Long.toString(Files.getLastModifiedTime(path).toMillis()).getBytes());
                    snapshot.put(root.relativize(path).toString(), HexFormat.of().formatHex(digest.digest()));
                } catch (Exception e) {
                    throw new IllegalStateException("Could not hash " + path, e);
                }
            });
        }
        return snapshot;
    }
}
