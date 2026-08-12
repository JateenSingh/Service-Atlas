package com.serviceatlas.parser.scala;

import com.serviceatlas.parser.common.RepoFiles;
import com.serviceatlas.parser.common.TextSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static reader for SBT build definitions (FR-2.2, FR-3.1).
 *
 * <p>This never invokes SBT (FR-3.7). SBT builds are Scala programs, so a regex reader cannot be
 * complete — but the constructs that matter here (setting assignments, dependency coordinates,
 * sub-project declarations) are written in a conventional, highly regular style in practice. The
 * parser reads {@code build.sbt}, every {@code *.sbt} beside it, and {@code project/*.scala} (where
 * the common {@code Dependencies.scala} pattern puts coordinates), and resolves simple {@code val}
 * indirection so that {@code Dependencies.quoteClient} still yields a coordinate.
 */
public final class SbtBuildParser {

    private static final Pattern NAME = Pattern.compile("\\bname\\s*:=\\s*\"([^\"]+)\"");
    private static final Pattern ORGANIZATION = Pattern.compile("\\borganization\\s*:=\\s*\"([^\"]+)\"");
    private static final Pattern SCALA_VERSION = Pattern.compile("\\bscalaVersion\\s*:=\\s*\"([^\"]+)\"");
    private static final Pattern SBT_VERSION = Pattern.compile("^\\s*sbt\\.version\\s*=\\s*(\\S+)\\s*$");

    /** {@code "group" %% "artifact" % "version"}; the version may be a literal or a val reference. */
    private static final Pattern DEPENDENCY = Pattern.compile(
            "\"([A-Za-z0-9_.\\-]+)\"\\s*%{1,3}\\s*\"([A-Za-z0-9_.\\-]+)\""
                    + "(?:\\s*%\\s*(?:\"([A-Za-z0-9_.\\-+]+)\"|([A-Za-z_][A-Za-z0-9_.]*)))?");

    /** {@code lazy val quotes = (project in file("modules/quotes"))} and close variants. */
    private static final Pattern MODULE = Pattern.compile(
            "(?:lazy\\s+)?val\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*"
                    + "(?:\\(\\s*)?(?:project|Project\\s*\\([^)]*\\))"
                    + "(?:\\s*\\)\\s*)?(?:\\s*(?:in|\\.in\\s*\\()\\s*file\\s*\\(\\s*\"([^\"]+)\"\\s*\\))?");

    /** A module's own {@code name :=} inside its settings block, e.g. {@code .settings(name := "x")}. */
    private static final Pattern MODULE_NAME_IN_SETTINGS = Pattern.compile("name\\s*:=\\s*\"([^\"]+)\"");

    private final RepoFiles files;

    public SbtBuildParser(RepoFiles files) {
        this.files = files;
    }

    /** True when this repository is an SBT build at all (FR-2.1 marker check). */
    public static boolean looksLikeSbtRepo(Path root) {
        return java.nio.file.Files.isRegularFile(root.resolve("build.sbt"))
                || java.nio.file.Files.isRegularFile(root.resolve("project/build.properties"));
    }

    public SbtBuild parse(String fallbackName) {
        List<String> warnings = new ArrayList<>();
        List<TextSource> buildSources = buildSources(warnings);

        String name = firstGroup(buildSources, NAME).orElse(null);
        boolean nameFromBuild = name != null;
        if (name == null) {
            name = fallbackName;
        }

        String organization = firstGroup(buildSources, ORGANIZATION).orElse(null);
        String scalaVersion = firstGroup(buildSources, SCALA_VERSION).orElse(null);
        String sbtVersion = readSbtVersion().orElse(null);

        if (buildSources.isEmpty()) {
            warnings.add("No readable build.sbt: service metadata falls back to the directory name.");
        }

        List<ArtifactCoordinate> dependencies = readDependencies(buildSources);
        List<SbtModule> modules = readModules(buildSources);

        return new SbtBuild(
                name, nameFromBuild, organization, scalaVersion, sbtVersion, dependencies, modules, warnings);
    }

    /**
     * {@code build.sbt} plus sibling {@code *.sbt} files plus {@code project/*.scala}. The last of
     * those is where most real builds keep their dependency lists.
     */
    private List<TextSource> buildSources(List<String> warnings) {
        List<TextSource> sources = new ArrayList<>();
        files.readSource("build.sbt").ifPresent(sources::add);

        for (Path sbtFile : files.find(null, path -> path.getFileName().toString().endsWith(".sbt"))) {
            String relative = files.relativize(sbtFile);
            if (relative.equals("build.sbt") || relative.contains("/")) {
                continue; // build.sbt already added; nested .sbt files belong to sub-builds
            }
            files.readSource(sbtFile).ifPresent(sources::add);
        }

        for (Path projectScala : files.findByExtension("project", ".scala")) {
            files.readSource(projectScala).ifPresent(sources::add);
        }

        if (files.exists("build.sbt") && sources.isEmpty()) {
            warnings.add("build.sbt exists but could not be read as UTF-8 text.");
        }
        return sources;
    }

    private Optional<String> readSbtVersion() {
        return files.readSource("project/build.properties").flatMap(source -> {
            for (String line : source.codeLines()) {
                Matcher matcher = SBT_VERSION.matcher(line.replace("\\:", ":"));
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
            }
            return Optional.empty();
        });
    }

    private Optional<String> firstGroup(List<TextSource> sources, Pattern pattern) {
        for (TextSource source : sources) {
            List<TextSource.Match> matches = source.matches(pattern);
            if (!matches.isEmpty()) {
                return Optional.ofNullable(matches.get(0).group(1));
            }
        }
        return Optional.empty();
    }

    private List<ArtifactCoordinate> readDependencies(List<TextSource> sources) {
        Map<String, ArtifactCoordinate> byCoordinate = new LinkedHashMap<>();
        for (TextSource source : sources) {
            for (TextSource.Match match : source.matches(DEPENDENCY)) {
                String group = match.group(1);
                String artifact = match.group(2);
                String version = match.group(3);
                if (looksLikeSettingAssignment(match.rawLine(), group)) {
                    continue;
                }
                ArtifactCoordinate coordinate = new ArtifactCoordinate(
                        group, artifact, version, source.path(), match.line(), match.rawLine());
                byCoordinate.putIfAbsent(coordinate.coordinates(), coordinate);
            }
        }
        return List.copyOf(byCoordinate.values());
    }

    /**
     * Rejects lines where the two quoted strings are not a dependency at all, such as
     * {@code addCommandAlias("ci", "test")} or {@code javaOptions ++= Seq("-Dx", "-Dy")}.
     */
    private boolean looksLikeSettingAssignment(String rawLine, String group) {
        String line = rawLine.strip();
        if (line.startsWith("addCommandAlias") || line.startsWith("javaOptions") || line.startsWith("scalacOptions")) {
            return true;
        }
        // A group id always looks like a reverse domain or at least contains a dot or dash.
        return !group.contains(".") && !group.contains("-");
    }

    private List<SbtModule> readModules(List<TextSource> sources) {
        Map<String, SbtModule> byId = new LinkedHashMap<>();
        for (TextSource source : sources) {
            if (!source.path().endsWith(".sbt")) {
                continue; // sub-projects are declared in .sbt files, not project/*.scala helpers
            }
            for (TextSource.Match match : source.matches(MODULE)) {
                String id = match.group(1);
                String path = match.group(2);
                if (path == null || path.equals(".") || id == null) {
                    continue; // the root project, or a Project(...) form without a directory
                }
                String moduleName = moduleName(source, match.line(), id);
                boolean deployable = isDeployableModule(path);
                byId.putIfAbsent(id, new SbtModule(id, moduleName, normalizePath(path), deployable, match.line()));
            }
        }
        return List.copyOf(byId.values());
    }

    /**
     * Looks for a {@code name :=} in the declaration's settings block — the few lines following the
     * declaration, up to the next {@code lazy val}.
     */
    private String moduleName(TextSource source, int declarationLine, String fallbackId) {
        List<String> lines = source.codeLines();
        for (int i = declarationLine - 1; i < Math.min(lines.size(), declarationLine + 12); i++) {
            String line = lines.get(i);
            if (i > declarationLine - 1 && line.contains("lazy val")) {
                break;
            }
            Matcher matcher = MODULE_NAME_IN_SETTINGS.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return fallbackId;
    }

    /** A module is treated as its own service when it exposes routes or has a main class (FR-2.4). */
    private boolean isDeployableModule(String modulePath) {
        String base = normalizePath(modulePath);
        if (files.exists(base + "/conf/routes") || files.exists(base + "/src/main/resources/routes")) {
            return true;
        }
        return !files.find(base + "/src/main/scala", path -> {
            String fileName = path.getFileName().toString();
            return fileName.equals("Main.scala") || fileName.equals("Boot.scala") || fileName.equals("Server.scala");
        }).isEmpty();
    }

    private static String normalizePath(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.startsWith("./") ? normalized.substring(2) : normalized;
    }
}
