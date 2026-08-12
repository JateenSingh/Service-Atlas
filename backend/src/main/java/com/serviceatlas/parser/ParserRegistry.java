package com.serviceatlas.parser;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Chooses the {@link LanguageParser} for a repository (FR-3.8).
 *
 * <p>Adding a parser is adding a bean: discovery, scanning, graph building and the API all go
 * through this one lookup and none of them know Scala from Kotlin.
 */
@Component
public class ParserRegistry {

    private final List<LanguageParser> parsers;

    public ParserRegistry(List<LanguageParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public Optional<LanguageParser> parserFor(RepoCandidate candidate) {
        return parsers.stream().filter(parser -> parser.supports(candidate)).findFirst();
    }

    public List<LanguageParser> parsers() {
        return parsers;
    }
}
