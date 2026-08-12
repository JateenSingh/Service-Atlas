package com.serviceatlas.parser.scala;

import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.SignalSource;
import com.serviceatlas.parser.DependencySignal;
import com.serviceatlas.parser.common.TextSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * FR-3.5 — cross-service package imports, the weakest signal we collect.
 *
 * <p>An import proves a compile-time relationship, not a call, so these are LOW confidence and only
 * ever <em>corroborate</em>: they resolve against packages that other repositories declare in their
 * own sources, which is what makes {@code import com.acme.logistics.quote.QuoteClient} point at
 * {@code log-quote-svc} rather than at a guess.
 *
 * <p>Because these edges are typed {@code ARTIFACT}, an import backed by a real
 * {@code libraryDependencies} entry merges into the same edge and simply adds evidence.
 */
@Component
public final class SourceImportScanner implements ScalaSignalScanner {

    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*import\\s+([a-z][A-Za-z0-9_.]*)(?:\\.(?:_|\\{[^}]*}|[A-Z][A-Za-z0-9_]*))?\\s*$");

    /** Package roots that are never another service in the workspace. */
    private static final Set<String> IGNORED_ROOTS = Set.of(
            "java", "javax", "jakarta", "scala", "akka", "org", "play", "cats", "io", "kafka", "slick", "sbt");

    @Override
    public String id() {
        return "scala-cross-service-imports";
    }

    @Override
    public List<DependencySignal> scan(ScalaScanContext context) {
        ScalaSources sources = ScalaSources.read(context.files());
        Set<String> ownPackages = sources.declaredPackages();
        List<DependencySignal> signals = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (TextSource source : sources.sources()) {
            for (TextSource.Match match : source.matches(IMPORT)) {
                String imported = packagePart(match.group(1));
                if (imported == null || isIgnored(imported) || ownsPackage(ownPackages, imported)) {
                    continue;
                }
                if (!seen.add(imported)) {
                    continue;
                }
                Evidence evidence = Evidence.of(
                        SignalSource.SOURCE_IMPORT,
                        source.path(),
                        match.line(),
                        match.rawLine(),
                        "Imports package " + imported);
                signals.add(DependencySignal.of(
                        context.nodeKey(), imported, SignalSource.SOURCE_IMPORT, evidence));
            }
        }
        return signals;
    }

    /** Drops a trailing type name so {@code a.b.QuoteClient} is offered as the package {@code a.b}. */
    private String packagePart(String imported) {
        if (imported == null || !imported.contains(".")) {
            return null;
        }
        String[] segments = imported.split("\\.");
        int end = segments.length;
        while (end > 0 && !segments[end - 1].isEmpty() && Character.isUpperCase(segments[end - 1].charAt(0))) {
            end--;
        }
        if (end < 2) {
            return null; // too little left to identify anything
        }
        return String.join(".", java.util.Arrays.copyOfRange(segments, 0, end));
    }

    private boolean isIgnored(String imported) {
        String root = imported.substring(0, imported.indexOf('.'));
        return IGNORED_ROOTS.contains(root);
    }

    /** A service importing its own packages is not a dependency. */
    private boolean ownsPackage(Set<String> ownPackages, String imported) {
        return ownPackages.stream()
                .anyMatch(owned -> owned.equals(imported) || imported.startsWith(owned + ".")
                        || owned.startsWith(imported + "."));
    }
}
