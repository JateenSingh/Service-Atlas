package com.serviceatlas.parser;

import com.serviceatlas.graph.model.GraphNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything one {@link LanguageParser} learned about one repository.
 *
 * @param nodes    the service node plus any sub-module nodes (FR-2.4)
 * @param signals  unresolved dependency findings (see {@link DependencySignal})
 * @param aliases  extra names this repo answers to — artifact coordinates, module names, host
 *                 names — used by the graph builder to resolve other repos' signals
 * @param warnings non-fatal parse problems, surfaced as node badges (FR-7.2)
 */
public record ParsedRepo(
        List<GraphNode> nodes,
        List<DependencySignal> signals,
        List<String> aliases,
        List<String> warnings) {

    public ParsedRepo {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        signals = signals == null ? List.of() : List.copyOf(signals);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ParsedRepo empty() {
        return new ParsedRepo(List.of(), List.of(), List.of(), List.of());
    }

    /** The repository's own node — the first one, by construction of the parsers. */
    public GraphNode primaryNode() {
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    public ParsedRepo withWarning(String warning) {
        List<String> merged = new ArrayList<>(warnings);
        merged.add(warning);
        return new ParsedRepo(nodes, signals, aliases, merged);
    }
}
