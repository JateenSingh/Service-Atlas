package com.serviceatlas.parser.scala;

import com.serviceatlas.parser.common.RepoFiles;
import com.serviceatlas.parser.common.TextSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads a repository's <em>production</em> Scala sources and the small amount of shared context the
 * source scanners need.
 *
 * <p>Test sources are excluded deliberately: a stubbed URL in a test says nothing about the running
 * architecture, and including them is the fastest way to fill a diagram with edges nobody believes.
 */
public final class ScalaSources {

    /** Play keeps sources in {@code app/}; everything else uses {@code src/main/}. */
    private static final List<String> SOURCE_DIRECTORIES = List.of("app", "src/main/scala", "src/main/java");

    /** {@code val baseUrl = "http://log-audit-svc:9000"} — the indirection scanners must follow. */
    private static final Pattern STRING_VAL = Pattern.compile(
            "\\b(?:private\\s+|final\\s+|lazy\\s+)*(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)"
                    + "\\s*(?::\\s*String\\s*)?=\\s*\"([^\"]*)\"");

    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "^\\s*package\\s+([a-z][A-Za-z0-9_.]*)\\s*$");

    private final List<TextSource> sources;

    private ScalaSources(List<TextSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public static ScalaSources read(RepoFiles files) {
        Map<String, TextSource> byPath = new LinkedHashMap<>();
        for (String directory : SOURCE_DIRECTORIES) {
            for (Path path : files.findByExtension(directory, ".scala", ".java")) {
                String relative = files.relativize(path);
                if (isTestSource(relative)) {
                    continue;
                }
                files.readSource(path).ifPresent(source -> byPath.putIfAbsent(relative, source));
            }
        }
        return new ScalaSources(List.copyOf(byPath.values()));
    }

    public List<TextSource> sources() {
        return sources;
    }

    /** String constants declared in a file, so {@code s"$baseUrl/path"} can be resolved. */
    public static Map<String, String> stringValues(TextSource source) {
        Map<String, String> values = new LinkedHashMap<>();
        for (TextSource.Match match : source.matches(STRING_VAL)) {
            values.putIfAbsent(match.group(1), match.group(2));
        }
        return values;
    }

    /**
     * Package names this repository declares. Registered as aliases so another repository's
     * {@code import com.acme.logistics.quote.QuoteClient} can be traced back here (FR-3.5).
     */
    public Set<String> declaredPackages() {
        Set<String> packages = new LinkedHashSet<>();
        for (TextSource source : sources) {
            for (TextSource.Match match : source.matches(PACKAGE_DECLARATION)) {
                String declared = match.group(1);
                if (declared != null && declared.contains(".")) {
                    packages.add(declared);
                }
            }
        }
        return packages;
    }

    /**
     * Substitutes known {@code val}s into an interpolated string:
     * {@code s"$baseUrl/audit/events"} → {@code http://log-audit-svc:9000/audit/events}. Unknown
     * references become a placeholder so the rest of the string still parses as a URL.
     */
    public static String interpolate(String literal, Map<String, String> values) {
        StringBuilder out = new StringBuilder(literal.length());
        int i = 0;
        while (i < literal.length()) {
            char c = literal.charAt(i);
            if (c != '$' || i + 1 >= literal.length()) {
                out.append(c);
                i++;
                continue;
            }
            int start = i + 1;
            boolean braced = literal.charAt(start) == '{';
            if (braced) {
                start++;
            }
            int end = start;
            while (end < literal.length() && (Character.isLetterOrDigit(literal.charAt(end))
                    || literal.charAt(end) == '_' || literal.charAt(end) == '.')) {
                end++;
            }
            String reference = literal.substring(start, end);
            String simple = reference.contains(".") ? reference.substring(0, reference.indexOf('.')) : reference;
            out.append(values.getOrDefault(simple, "_"));
            i = braced && end < literal.length() && literal.charAt(end) == '}' ? end + 1 : end;
        }
        return out.toString();
    }

    private static boolean isTestSource(String relativePath) {
        return relativePath.contains("/test/") || relativePath.startsWith("test/")
                || relativePath.contains("/it/") || relativePath.endsWith("Spec.scala")
                || relativePath.endsWith("Test.scala");
    }

    /** Convenience for scanners that want a mutable working list. */
    public List<TextSource> mutableCopy() {
        return new ArrayList<>(sources);
    }
}
