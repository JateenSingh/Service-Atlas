package com.serviceatlas.parser.scala;

import com.serviceatlas.graph.model.Endpoint;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.graph.model.NodeType;
import com.serviceatlas.parser.DependencySignal;
import com.serviceatlas.parser.LanguageParser;
import com.serviceatlas.parser.ParseContext;
import com.serviceatlas.parser.ParsedRepo;
import com.serviceatlas.parser.RepoCandidate;
import com.serviceatlas.parser.common.NameNormalizer;
import com.serviceatlas.parser.common.RepoFiles;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Scala/SBT implementation of {@link LanguageParser} (FR-2.2, FR-3).
 *
 * <p>Structure: parse the build once, derive the node(s), then run each {@link ScalaSignalScanner}
 * over the repository. Scanners are independent — one throwing does not lose the others' findings.
 */
@Component
public class ScalaSbtLanguageParser implements LanguageParser {

    private static final Logger log = LoggerFactory.getLogger(ScalaSbtLanguageParser.class);

    private final List<ScalaSignalScanner> scanners;

    public ScalaSbtLanguageParser(List<ScalaSignalScanner> scanners) {
        this.scanners = List.copyOf(scanners);
    }

    @Override
    public String id() {
        return "scala-sbt";
    }

    @Override
    public String displayName() {
        return "Scala / SBT";
    }

    @Override
    public boolean supports(RepoCandidate candidate) {
        return SbtBuildParser.looksLikeSbtRepo(candidate.root());
    }

    @Override
    public ParsedRepo parse(RepoCandidate candidate, ParseContext context) {
        RepoFiles files = context.files();
        SbtBuild build = new SbtBuildParser(files).parse(candidate.directoryName());

        String serviceName = build.name();
        String nodeKey = nodeKey(serviceName);

        GraphNode serviceNode = buildServiceNode(candidate, build, files, nodeKey, serviceName);

        List<GraphNode> nodes = new ArrayList<>();
        nodes.add(serviceNode);
        nodes.addAll(subModuleNodes(build, nodeKey, candidate, files));

        List<DependencySignal> signals = new ArrayList<>();
        List<String> warnings = new ArrayList<>(build.warnings());
        ScalaScanContext scanContext = new ScalaScanContext(nodeKey, files, build, context.settings());
        for (ScalaSignalScanner scanner : scanners) {
            try {
                signals.addAll(scanner.scan(scanContext));
            } catch (RuntimeException e) {
                log.warn("Scanner {} failed on {}", scanner.id(), candidate.relativePath(), e);
                warnings.add("Signal scanner '" + scanner.id() + "' failed: " + rootMessage(e));
            }
        }

        return new ParsedRepo(nodes, signals, aliases(build, candidate, files), warnings);
    }

    private GraphNode buildServiceNode(RepoCandidate candidate, SbtBuild build, RepoFiles files,
                                       String nodeKey, String serviceName) {
        Set<String> frameworks = FrameworkDetector.detectAll(build.dependencies(), files);
        List<Endpoint> endpoints = new PlayRoutesParser(files).parse();
        GraphNode.Builder node = GraphNode.builder(nodeKey, serviceName, NodeType.SERVICE)
                .endpoints(endpoints)
                .framework(FrameworkDetector.detect(build.dependencies(), files))
                .scalaVersion(build.scalaVersion())
                .sbtVersion(build.sbtVersion())
                .repoPath(candidate.relativePath())
                .metadata("organization", build.organization())
                .metadata("parser", id())
                .metadata("directoryName", candidate.directoryName())
                .metadata("dependencyCount", build.dependencies().size())
                .metadata("nameSource", build.nameFromBuild() ? "build.sbt" : "directory");
        if (frameworks.size() > 1) {
            node.metadata("frameworks", List.copyOf(frameworks));
        }
        if (!build.modules().isEmpty()) {
            node.metadata("moduleCount", build.modules().size());
        }
        if (!endpoints.isEmpty()) {
            node.metadata("endpointCount", endpoints.size());
        }
        return node.warnings(build.warnings()).build();
    }

    /** FR-3.4 — a deployable module publishes its own routes, under its own directory. */
    private List<Endpoint> moduleEndpoints(RepoFiles files, SbtModule module) {
        return new PlayRoutesParser(files).parse(module.relativePath() + "/conf");
    }

    /** FR-2.4 — independently deployable modules become nested SUB_MODULE nodes. */
    private List<GraphNode> subModuleNodes(SbtBuild build, String parentKey, RepoCandidate candidate,
                                           RepoFiles files) {
        List<GraphNode> nodes = new ArrayList<>();
        for (SbtModule module : build.modules()) {
            if (!module.deployable()) {
                continue;
            }
            String key = parentKey + "/" + NameNormalizer.canonical(module.name());
            nodes.add(GraphNode.builder(key, module.name(), NodeType.SUB_MODULE)
                    .parentKey(parentKey)
                    .repoPath(candidate.relativePath() + "/" + module.relativePath())
                    .scalaVersion(build.scalaVersion())
                    .endpoints(moduleEndpoints(files, module))
                    .metadata("moduleId", module.id())
                    .metadata("modulePath", module.relativePath())
                    .metadata("declaredAtLine", module.line())
                    .build());
        }
        return nodes;
    }

    /**
     * Names this repository answers to when another repository references it: the service name, the
     * directory name, and its published artifact coordinates.
     */
    private List<String> aliases(SbtBuild build, RepoCandidate candidate, RepoFiles files) {
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(build.name());
        aliases.add(candidate.directoryName());
        for (SbtModule module : build.modules()) {
            aliases.add(module.name());
        }
        // Packages this repo declares, so another repo's import of them resolves here (FR-3.5).
        aliases.addAll(ScalaSources.read(files).declaredPackages());
        return List.copyOf(aliases);
    }

    /** Stable, human-legible node identity derived from the service name. */
    public static String nodeKey(String serviceName) {
        String canonical = NameNormalizer.canonical(serviceName);
        return canonical.isEmpty() ? "unnamed" : canonical;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
