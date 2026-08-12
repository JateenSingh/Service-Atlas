package com.serviceatlas.graph;

import com.serviceatlas.parser.common.NameNormalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the name one service uses for another into a node key (FR-3.2).
 *
 * <p>Two indexes, consulted in order:
 *
 * <ol>
 *   <li><b>exact</b> — the canonical form of a name the service actually declares
 *       ({@code log-quote-svc} → {@code logquotesvc}). Confident.
 *   <li><b>stem</b> — the same with trailing {@code -svc}/{@code -service}/{@code -client} noise
 *       stripped, which is what lets {@code log-quote-svc-client} (an artifact) find
 *       {@code log-quote-svc} (a service).
 * </ol>
 *
 * <p>Ambiguity is treated as failure, not as a coin flip: if two services share a stem, that stem
 * resolves to nothing and only exact matches work. A missing edge is a much smaller problem than a
 * confidently wrong one.
 */
public final class AliasIndex {

    private final Map<String, String> exact = new HashMap<>();
    private final Map<String, String> stem = new HashMap<>();
    private final Set<String> ambiguousStems = new HashSet<>();

    /** Registers every spelling {@code nodeKey} should answer to. */
    public void register(String nodeKey, Iterable<String> names) {
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String canonical = NameNormalizer.canonical(name);
            if (NameNormalizer.isMatchable(canonical)) {
                exact.putIfAbsent(canonical, nodeKey);
            }
            String stemmed = NameNormalizer.canonicalStem(name);
            if (!NameNormalizer.isMatchable(stemmed) || stemmed.equals(canonical)) {
                continue; // nothing new: the exact index already covers it
            }
            String existing = stem.putIfAbsent(stemmed, nodeKey);
            if (existing != null && !existing.equals(nodeKey)) {
                ambiguousStems.add(stemmed);
            }
        }
        // A service's own canonical name is also a valid stem target.
        String canonicalKey = NameNormalizer.canonical(nodeKey);
        if (NameNormalizer.isMatchable(canonicalKey)) {
            exact.putIfAbsent(canonicalKey, nodeKey);
        }
    }

    /**
     * Resolves a raw reference — an artifact name, a host, a config key, a URL — to a node key.
     */
    public Optional<String> resolve(String rawReference) {
        if (rawReference == null || rawReference.isBlank()) {
            return Optional.empty();
        }

        for (String candidate : candidates(rawReference)) {
            String hit = exact.get(NameNormalizer.canonical(candidate));
            if (hit != null) {
                return Optional.of(hit);
            }
        }
        for (String candidate : candidates(rawReference)) {
            String stemmed = NameNormalizer.canonicalStem(candidate);
            if (stemmed.isEmpty() || ambiguousStems.contains(stemmed)) {
                continue;
            }
            String hit = stem.get(stemmed);
            if (hit == null) {
                hit = exact.get(stemmed);
            }
            if (hit != null) {
                return Optional.of(hit);
            }
        }
        return Optional.empty();
    }

    /** The successively more aggressive readings of a reference: as-is, host-only, first label. */
    private static java.util.List<String> candidates(String rawReference) {
        java.util.List<String> candidates = new java.util.ArrayList<>(3);
        candidates.add(rawReference);
        for (String reading : java.util.List.of(
                NameNormalizer.hostOf(rawReference), NameNormalizer.firstLabelOf(rawReference))) {
            if (!reading.isBlank() && !candidates.contains(reading)) {
                candidates.add(reading);
            }
        }
        return candidates;
    }

    public boolean isEmpty() {
        return exact.isEmpty() && stem.isEmpty();
    }
}
