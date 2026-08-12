package com.serviceatlas.parser;

/**
 * Service provider interface for language/build-tool specific extraction (FR-3.8).
 *
 * <p>Implementations are Spring beans; {@link ParserRegistry} picks the first one that
 * {@link #supports(RepoCandidate) supports} a candidate. Adding a Kotlin/Gradle or Go parser means
 * adding one bean — no change to discovery, graph building, persistence or the API.
 *
 * <p><b>Contract:</b> implementations must be pure static analysis. They must never execute
 * repository code, invoke a build tool, or open a file for writing (FR-3.7, Q-4). Read access goes
 * through {@link com.serviceatlas.parser.common.RepoFiles}, which offers no write operations.
 */
public interface LanguageParser {

    /** Stable identifier, used in logs and in scan metadata. */
    String id();

    /** Human-readable name for the UI. */
    String displayName();

    /** Whether this parser can handle the candidate — typically a marker-file check. */
    boolean supports(RepoCandidate candidate);

    /**
     * Extracts nodes and unresolved dependency signals from one repository.
     *
     * <p>Implementations should degrade rather than throw: a malformed file becomes a warning on
     * the returned {@link ParsedRepo}, not an exception, so one bad repo cannot fail a scan
     * (FR-7.2). Exceptions that do escape are caught by the scan service and recorded per repo.
     */
    ParsedRepo parse(RepoCandidate candidate, ParseContext context);
}
