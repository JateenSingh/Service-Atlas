package com.serviceatlas.graph.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Why we believe an edge exists: a file, a line, and the text we matched (FR-3).
 *
 * @param source which extraction rule fired
 * @param file   repo-relative path, so evidence stays meaningful after the folder moves
 * @param line   1-based line number, or 0 when the signal is not line-addressable
 * @param snippet the matched text, trimmed and length-capped
 * @param detail human-readable explanation of the match
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Evidence(SignalSource source, String file, int line, String snippet, String detail) {

    private static final int MAX_SNIPPET = 240;

    public Evidence {
        if (snippet != null) {
            snippet = snippet.strip();
            if (snippet.length() > MAX_SNIPPET) {
                snippet = snippet.substring(0, MAX_SNIPPET) + "…";
            }
        }
    }

    public static Evidence of(SignalSource source, String file, int line, String snippet, String detail) {
        return new Evidence(source, file, line, snippet, detail);
    }
}
