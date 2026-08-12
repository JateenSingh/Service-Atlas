package com.serviceatlas.scan;

import com.serviceatlas.api.ApiException;
import com.serviceatlas.config.ServiceAtlasProperties;
import com.serviceatlas.graph.GraphBuilder;
import com.serviceatlas.graph.GraphStore;
import com.serviceatlas.graph.model.DependencyGraph;
import com.serviceatlas.graph.model.GraphNode;
import com.serviceatlas.graph.model.NodeType;
import com.serviceatlas.parser.LanguageParser;
import com.serviceatlas.parser.ParseContext;
import com.serviceatlas.parser.ParsedRepo;
import com.serviceatlas.parser.ParserRegistry;
import com.serviceatlas.parser.RepoCandidate;
import com.serviceatlas.persistence.RepoScanStatus;
import com.serviceatlas.persistence.ScanEntity;
import com.serviceatlas.persistence.ScanRepoEntity;
import com.serviceatlas.persistence.ScanRepoRepository;
import com.serviceatlas.persistence.ScanRepository;
import com.serviceatlas.persistence.ScanStatus;
import com.serviceatlas.persistence.WorkspaceEntity;
import com.serviceatlas.workspace.WorkspaceService;
import com.serviceatlas.workspace.WorkspaceSettings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs scans: discover repositories, parse each one, build the graph, persist it (FR-7).
 *
 * <p>Failure isolation is the design constraint. Each repository is parsed inside its own
 * try/catch and its own short transaction, so a repository that throws is recorded as failed,
 * rendered as a node with a warning badge, and the scan continues (FR-7.2).
 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final ScanRepository scans;
    private final ScanRepoRepository scanRepos;
    private final WorkspaceService workspaces;
    private final RepoDiscoverer discoverer;
    private final ParserRegistry parsers;
    private final GraphBuilder graphBuilder;
    private final GraphStore graphStore;
    private final ScanProgressBroker progress;
    private final ServiceAtlasProperties properties;

    /** Scans currently running, so a workspace cannot be scanned twice at once. */
    private final Map<Long, Long> runningByWorkspace = new ConcurrentHashMap<>();

    public ScanService(ScanRepository scans, ScanRepoRepository scanRepos, WorkspaceService workspaces,
                       RepoDiscoverer discoverer, ParserRegistry parsers, GraphBuilder graphBuilder,
                       GraphStore graphStore, ScanProgressBroker progress,
                       ServiceAtlasProperties properties) {
        this.scans = scans;
        this.scanRepos = scanRepos;
        this.workspaces = workspaces;
        this.discoverer = discoverer;
        this.parsers = parsers;
        this.graphBuilder = graphBuilder;
        this.graphStore = graphStore;
        this.progress = progress;
        this.properties = properties;
    }

    /**
     * Creates the scan row and hands execution to the async worker (FR-7.1).
     *
     * <p>Deliberately not {@code @Transactional}: the worker runs on another thread and must be able
     * to read the scan row immediately, which it could not do from inside an uncommitted
     * transaction. {@code save} commits on its own.
     */
    public ScanEntity startScan(Long workspaceId) {
        ScanEntity scan = beginScan(workspaceId);
        self().executeAsync(workspaceId, scan.getId());
        return scan;
    }

    /**
     * Runs a scan on the calling thread and returns the finished scan.
     *
     * <p>Same work, no async hand-off — for callers that want to block, and for tests, which must
     * not race the worker.
     */
    public ScanEntity scanNow(Long workspaceId) {
        ScanEntity scan = beginScan(workspaceId);
        try {
            execute(workspaceId, scan.getId());
        } catch (RuntimeException e) {
            markScanFailed(scan.getId(), e.getMessage());
            throw e;
        } finally {
            runningByWorkspace.remove(workspaceId, scan.getId());
            progress.complete(scan.getId());
        }
        return get(scan.getId());
    }

    /** Claims the workspace and creates the scan row, committed before any worker reads it. */
    private ScanEntity beginScan(Long workspaceId) {
        WorkspaceEntity workspace = workspaces.get(workspaceId);
        Long running = runningByWorkspace.get(workspaceId);
        if (running != null) {
            throw ApiException.conflict("Scan " + running + " is already running for this workspace");
        }
        ScanEntity scan = scans.save(new ScanEntity(workspace.getId()));
        runningByWorkspace.put(workspaceId, scan.getId());
        return scan;
    }

    /**
     * Self-reference so the {@code @Async} proxy is used rather than a plain in-thread call.
     * Injected lazily to avoid a circular bean definition.
     */
    private ScanService self;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy ScanService self) {
        this.self = self;
    }

    private ScanService self() {
        return self == null ? this : self;
    }

    @Async("scanExecutor")
    public void executeAsync(Long workspaceId, Long scanId) {
        try {
            execute(workspaceId, scanId);
        } catch (RuntimeException e) {
            log.error("Scan {} failed", scanId, e);
            markScanFailed(scanId, e.getMessage());
        } finally {
            runningByWorkspace.remove(workspaceId, scanId);
            progress.publish(scanId, "scan", scanSnapshot(scanId));
            progress.complete(scanId);
        }
    }

    void execute(Long workspaceId, Long scanId) {
        WorkspaceEntity workspace = workspaces.get(workspaceId);
        WorkspaceSettings settings = workspaces.settingsOf(workspace);
        Path root = Path.of(workspace.getRootPath());

        self().updateScan(scanId, scan -> scan.setStatus(ScanStatus.RUNNING));
        progress.publish(scanId, "scan", scanSnapshot(scanId));

        List<RepoCandidate> candidates = discoverer.discover(
                root, settings.maxDepthOr(properties.getScan().getMaxDepth()), settings.ignoredDirectories());

        self().updateScan(scanId, scan -> scan.setRepoCount(candidates.size()));
        for (RepoCandidate candidate : candidates) {
            self().recordRepo(scanId, candidate, RepoScanStatus.DISCOVERED, null, null, null);
        }
        progress.publish(scanId, "discovered", Map.of("repoCount", candidates.size()));

        List<ParsedRepo> parsed = new ArrayList<>();
        int errors = 0;
        for (RepoCandidate candidate : candidates) {
            RepoOutcome outcome = parseOne(scanId, candidate);
            if (outcome.parsedRepo() != null) {
                parsed.add(outcome.parsedRepo());
            }
            if (outcome.failed()) {
                errors++;
            }
        }

        DependencyGraph graph = graphBuilder.build(parsed);
        graph = graphBuilder.withEndpointLabels(graph);
        graphStore.save(scanId, graph);

        int errorCount = errors;
        self().updateScan(scanId, scan -> {
            scan.setErrorCount(errorCount);
            scan.setStatus(candidates.isEmpty() || errorCount < candidates.size()
                    ? ScanStatus.COMPLETED
                    : ScanStatus.FAILED);
            if (candidates.isEmpty()) {
                scan.setMessage("No SBT repositories found under " + root);
            } else if (errorCount >= candidates.size()) {
                scan.setMessage("Every repository failed to parse");
            }
        });
        log.info("Scan {} finished: {} repos, {} nodes, {} edges, {} errors",
                scanId, candidates.size(), graph.nodeCount(), graph.edgeCount(), errorCount);
    }

    /** Parses one repository, converting any failure into a warning-badged node (FR-7.2). */
    private RepoOutcome parseOne(Long scanId, RepoCandidate candidate) {
        long startedAt = System.currentTimeMillis();
        setRepoStatus(scanId, candidate, RepoScanStatus.PARSING, null, null, null);
        progress.publish(scanId, "repo", Map.of(
                "repoPath", candidate.relativePath(), "status", RepoScanStatus.PARSING.name()));

        String fingerprint = null;
        try {
            fingerprint = new RepoFingerprint(candidate.root(), properties.getScan()).compute();
        } catch (RuntimeException e) {
            log.debug("Could not fingerprint {}", candidate.relativePath(), e);
        }

        Optional<LanguageParser> parser = parsers.parserFor(candidate);
        if (parser.isEmpty()) {
            long elapsed = System.currentTimeMillis() - startedAt;
            setRepoStatus(scanId, candidate, RepoScanStatus.SKIPPED,
                    "No parser claimed this repository", fingerprint, elapsed);
            progress.publish(scanId, "repo", Map.of(
                    "repoPath", candidate.relativePath(), "status", RepoScanStatus.SKIPPED.name()));
            return new RepoOutcome(null, false);
        }

        try {
            ParsedRepo result = parser.get().parse(
                    candidate, ParseContext.forRepo(candidate, properties.getScan()));
            long elapsed = System.currentTimeMillis() - startedAt;
            String message = result.warnings().isEmpty() ? null : String.join("; ", result.warnings());
            setRepoStatus(scanId, candidate, RepoScanStatus.DONE, message, fingerprint, elapsed);
            progress.publish(scanId, "repo", Map.of(
                    "repoPath", candidate.relativePath(),
                    "status", RepoScanStatus.DONE.name(),
                    "nodeCount", result.nodes().size(),
                    "signalCount", result.signals().size()));
            return new RepoOutcome(result, false);
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - startedAt;
            log.warn("Failed to parse {}", candidate.relativePath(), e);
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            setRepoStatus(scanId, candidate, RepoScanStatus.FAILED, message, fingerprint, elapsed);
            progress.publish(scanId, "repo", Map.of(
                    "repoPath", candidate.relativePath(),
                    "status", RepoScanStatus.FAILED.name(),
                    "message", message));
            return new RepoOutcome(placeholderFor(candidate, message), true);
        }
    }

    /** A repo that failed still appears on the canvas, badged, rather than silently vanishing. */
    private ParsedRepo placeholderFor(RepoCandidate candidate, String message) {
        GraphNode node = GraphNode.builder(
                        com.serviceatlas.parser.scala.ScalaSbtLanguageParser.nodeKey(candidate.directoryName()),
                        candidate.directoryName(),
                        NodeType.SERVICE)
                .repoPath(candidate.relativePath())
                .warnings(List.of("Parse failed: " + message))
                .metadata("parseFailed", true)
                .build();
        return new ParsedRepo(List.of(node), List.of(), List.of(candidate.directoryName()), List.of(message));
    }

    @Transactional(readOnly = true)
    public ScanEntity get(Long scanId) {
        return scans.findById(scanId).orElseThrow(() -> ApiException.notFound("Scan", scanId));
    }

    @Transactional(readOnly = true)
    public List<ScanEntity> listForWorkspace(Long workspaceId) {
        return scans.findByWorkspaceIdOrderByIdDesc(workspaceId);
    }

    @Transactional(readOnly = true)
    public List<ScanRepoEntity> repoProgress(Long scanId) {
        return scanRepos.findByScanIdOrderByRepoPathAsc(scanId);
    }

    /** The latest scan that produced a graph, if any. */
    @Transactional(readOnly = true)
    public Optional<ScanEntity> latestCompleted(Long workspaceId) {
        return scans.findFirstByWorkspaceIdAndStatusOrderByIdDesc(workspaceId, ScanStatus.COMPLETED);
    }

    @Transactional(readOnly = true)
    public Optional<ScanEntity> latest(Long workspaceId) {
        return scans.findFirstByWorkspaceIdOrderByIdDesc(workspaceId);
    }

    public ScanSnapshot scanSnapshot(Long scanId) {
        ScanEntity scan = get(scanId);
        List<ScanRepoEntity> repos = repoProgress(scanId);
        long done = repos.stream()
                .filter(repo -> repo.getStatus() == RepoScanStatus.DONE
                        || repo.getStatus() == RepoScanStatus.UNCHANGED)
                .count();
        return new ScanSnapshot(
                scan.getId(),
                scan.getStatus(),
                scan.getRepoCount(),
                (int) done,
                scan.getErrorCount(),
                scan.getMessage());
    }

    // Public because Spring's transaction proxy only advises public methods.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRepo(Long scanId, RepoCandidate candidate, RepoScanStatus status,
                           String message, String contentHash, Long durationMs) {
        ScanRepoEntity entity = scanRepos.findByScanIdAndRepoPath(scanId, candidate.relativePath())
                .orElseGet(() -> new ScanRepoEntity(scanId, candidate.relativePath(), candidate.directoryName()));
        entity.setStatus(status);
        entity.setMessage(message);
        if (contentHash != null) {
            entity.setContentHash(contentHash);
        }
        if (durationMs != null) {
            entity.setDurationMs(durationMs);
        }
        scanRepos.save(entity);
    }

    private void setRepoStatus(Long scanId, RepoCandidate candidate, RepoScanStatus status,
                               String message, String contentHash, Long durationMs) {
        try {
            self().recordRepo(scanId, candidate, status, message, contentHash, durationMs);
        } catch (RuntimeException e) {
            log.warn("Could not record progress for {}", candidate.relativePath(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateScan(Long scanId, java.util.function.Consumer<ScanEntity> mutation) {
        ScanEntity scan = scans.findById(scanId).orElseThrow(() -> ApiException.notFound("Scan", scanId));
        mutation.accept(scan);
        scans.save(scan);
    }

    private void markScanFailed(Long scanId, String message) {
        try {
            self().updateScan(scanId, scan -> {
                scan.setStatus(ScanStatus.FAILED);
                scan.setMessage(message);
            });
        } catch (RuntimeException e) {
            log.error("Could not mark scan {} failed", scanId, e);
        }
    }

    /** Sorted newest-first list of scans, for the workspace detail response. */
    public List<ScanEntity> historyFor(Long workspaceId) {
        List<ScanEntity> history = new ArrayList<>(listForWorkspace(workspaceId));
        history.sort(Comparator.comparing(ScanEntity::getId).reversed());
        return history;
    }

    private record RepoOutcome(ParsedRepo parsedRepo, boolean failed) {
    }

    /**
     * Progress summary for polling and SSE (FR-7.1).
     */
    public record ScanSnapshot(
            Long scanId, ScanStatus status, int repoCount, int completedCount, int errorCount, String message) {
    }
}
