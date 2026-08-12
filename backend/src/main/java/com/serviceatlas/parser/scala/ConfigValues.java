package com.serviceatlas.parser.scala;

import com.serviceatlas.parser.common.RepoFiles;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Flattens a repository's HOCON configuration into {@code (key, value, file, line)} entries.
 *
 * <p>Shared by the config and HTTP-client scanners, since both need the same view: the config
 * scanner reads service URLs out of it (FR-3.2), and the HTTP scanner uses it to resolve calls
 * written as {@code ws.url(config.getString("services.quote.url"))} (FR-3.3).
 *
 * <p>Files are <b>parsed but never resolved</b>. Resolution would evaluate {@code ${?ENV}}
 * substitutions and {@code include}s, which can throw on a config that is only valid at runtime.
 * The raw tree is walked instead, and any value that cannot be read is skipped rather than
 * failing the repository.
 */
public final class ConfigValues {

    /** Where configuration lives in Play and in plain SBT projects. */
    private static final List<String> CONFIG_DIRECTORIES = List.of("conf", "src/main/resources");

    private static final Set<String> CONFIG_EXTENSIONS = Set.of(".conf", ".properties");

    private final List<Entry> entries;

    private ConfigValues(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static ConfigValues read(RepoFiles files) {
        List<Entry> entries = new ArrayList<>();
        for (Path path : configFiles(files)) {
            String relative = files.relativize(path);
            try {
                com.typesafe.config.Config parsed = com.typesafe.config.ConfigFactory
                        .parseFile(path.toFile(), ConfigParseOptions.defaults().setAllowMissing(true));
                collect(resolveLeniently(parsed).root(), "", relative, entries);
            } catch (RuntimeException e) {
                // A config we cannot parse is a fact about the repo, not a reason to fail the scan.
                entries.add(new Entry("", "", relative, 0, true));
            }
        }
        return new ConfigValues(entries);
    }

    /**
     * Resolves substitutions as far as possible without failing and without reading the host's
     * environment.
     *
     * <p>This matters for the near-universal "default plus environment override" pattern:
     *
     * <pre>{@code
     * url = "http://log-inventory-svc:9000"
     * url = ${?INVENTORY_URL}
     * }</pre>
     *
     * In the <em>unresolved</em> tree the second line has replaced the first, so the literal that
     * documents the dependency is invisible. Resolving with the environment switched off drops the
     * unset optional substitution and restores the literal — which is also what the service would
     * actually use on a machine where {@code INVENTORY_URL} is not set.
     */
    private static com.typesafe.config.Config resolveLeniently(com.typesafe.config.Config parsed) {
        try {
            return parsed.resolve(com.typesafe.config.ConfigResolveOptions.defaults()
                    .setAllowUnresolved(true)
                    .setUseSystemEnvironment(false));
        } catch (RuntimeException e) {
            return parsed; // unresolvable in some other way: the raw tree is still useful
        }
    }

    private static List<Path> configFiles(RepoFiles files) {
        Set<Path> found = new LinkedHashSet<>();
        for (String directory : CONFIG_DIRECTORIES) {
            found.addAll(files.find(directory, path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return CONFIG_EXTENSIONS.stream().anyMatch(name::endsWith);
            }));
        }
        return List.copyOf(found);
    }

    private static void collect(ConfigValue value, String prefix, String file, List<Entry> entries) {
        try {
            ConfigValueType type = value.valueType();
            if (type == ConfigValueType.OBJECT) {
                ConfigObject object = (ConfigObject) value;
                for (String key : object.keySet()) {
                    String path = prefix.isEmpty() ? key : prefix + "." + key;
                    collect(object.get(key), path, file, entries);
                }
            } else if (type == ConfigValueType.LIST) {
                ConfigList list = (ConfigList) value;
                for (int i = 0; i < list.size(); i++) {
                    collect(list.get(i), prefix + "[" + i + "]", file, entries);
                }
            } else if (type == ConfigValueType.STRING) {
                Object unwrapped = value.unwrapped();
                if (unwrapped != null) {
                    entries.add(new Entry(
                            prefix, unwrapped.toString(), file, Math.max(0, value.origin().lineNumber()), false));
                }
            }
            // Numbers and booleans carry no service references, so they are skipped.
        } catch (RuntimeException e) {
            // An unresolved substitution or a malformed node: skip this key, keep the rest.
        }
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean hasParseFailure() {
        return entries.stream().anyMatch(Entry::parseFailed);
    }

    /** Files that could not be parsed at all, for a node warning. */
    public List<String> unparseableFiles() {
        return entries.stream().filter(Entry::parseFailed).map(Entry::file).distinct().toList();
    }

    /** Looks up a config key exactly as source code would spell it. */
    public java.util.Optional<Entry> byKey(String key) {
        return entries.stream()
                .filter(entry -> !entry.parseFailed() && entry.key().equals(key))
                .findFirst();
    }

    /**
     * @param key        dotted key path, e.g. {@code services.inventory.url}
     * @param value      the string value
     * @param file       repo-relative config file
     * @param line       1-based line, or 0 when the origin is unknown
     * @param parseFailed marks a placeholder entry for a file that could not be parsed
     */
    public record Entry(String key, String value, String file, int line, boolean parseFailed) {

        /** The key's segments, split on dots and on separator characters within a segment. */
        public List<String> keyTokens() {
            return com.serviceatlas.parser.common.NameNormalizer.tokens(key.replace('.', ' '));
        }
    }
}
