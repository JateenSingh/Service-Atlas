package com.serviceatlas.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.serviceatlas.config.ServiceAtlasProperties;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the shared fixture repositories and builds default scan settings for tests. */
public final class Fixtures {

    private Fixtures() {
    }

    /**
     * The {@code e2e/fixtures} directory. Tests run with the module directory as the working
     * directory, so the walk upwards keeps this working from either the module or the repo root.
     */
    public static Path root() {
        Path current = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && current != null; i++) {
            Path candidate = current.resolve("e2e/fixtures");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate e2e/fixtures from " + Path.of("").toAbsolutePath());
    }

    public static Path repo(String name) {
        Path repo = root().resolve(name);
        assertThat(repo).as("fixture repo %s", name).isDirectory();
        return repo;
    }

    public static ServiceAtlasProperties.Scan scanSettings() {
        return new ServiceAtlasProperties().getScan();
    }
}
