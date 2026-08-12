package com.serviceatlas.parser;

import com.serviceatlas.config.ServiceAtlasProperties;
import com.serviceatlas.parser.common.RepoFiles;

/**
 * Everything a parser is allowed to touch while parsing one repository.
 *
 * <p>Note what is <em>absent</em>: no database, no HTTP client, no writable filesystem handle, and
 * no view of other repositories. Parsers see one repo and emit unresolved signals; correlation is
 * the graph builder's job.
 */
public record ParseContext(RepoFiles files, ServiceAtlasProperties.Scan settings) {

    public static ParseContext forRepo(RepoCandidate candidate, ServiceAtlasProperties.Scan settings) {
        return new ParseContext(new RepoFiles(candidate.root(), settings), settings);
    }
}
