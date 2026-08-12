package com.serviceatlas.parser.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalises the many spellings of one service name so that {@code log-quote-svc},
 * {@code logQuoteSvc}, {@code LOG_QUOTE_SVC} and {@code log_quote_svc} all compare equal (FR-3.2).
 *
 * <p>The canonical form is the lower-cased concatenation of the name's word tokens. Splitting
 * happens on separators <em>and</em> on camelCase humps, which is what makes the config-key,
 * environment-variable and artifact-name spellings converge.
 */
public final class NameNormalizer {

    /** Below this length a canonical name is too generic to match on without false positives. */
    private static final int MIN_MATCHABLE_LENGTH = 5;

    private static final Pattern SEPARATORS = Pattern.compile("[^A-Za-z0-9]+");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    /** Tokens that carry no identity on their own — an alias may not consist only of these. */
    private static final Set<String> NOISE_TOKENS = Set.of(
            "service", "services", "svc", "srv", "api", "app", "core", "common", "shared", "client",
            "server", "http", "https", "url", "uri", "host", "hostname", "port", "base", "endpoint",
            "internal", "external", "backend", "the", "com", "org", "io", "net", "local", "localhost",
            "prod", "dev", "test", "staging", "config", "conf", "default", "scala", "java", "lib");

    private NameNormalizer() {
    }

    /**
     * Canonical comparison form: lower-case alphanumerics with all separators and camel humps
     * removed. {@code "log-quote-svc"} and {@code "logQuoteSvc"} both become {@code "logquotesvc"}.
     */
    public static String canonical(String raw) {
        return String.join("", tokens(raw));
    }

    /** Canonical form with trailing {@code service}/{@code svc}-style noise removed. */
    public static String canonicalStem(String raw) {
        List<String> tokens = new ArrayList<>(tokens(raw));
        while (tokens.size() > 1 && NOISE_TOKENS.contains(tokens.get(tokens.size() - 1))) {
            tokens.remove(tokens.size() - 1);
        }
        return String.join("", tokens);
    }

    /** Lower-cased word tokens, split on separators and camelCase boundaries. */
    public static List<String> tokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String part : SEPARATORS.split(raw.strip())) {
            if (part.isEmpty()) {
                continue;
            }
            for (String piece : CAMEL_BOUNDARY.split(part)) {
                if (!piece.isEmpty()) {
                    tokens.add(piece.toLowerCase(Locale.ROOT));
                }
            }
        }
        return tokens;
    }

    /**
     * Whether a canonical name is distinctive enough to key an alias index on. Guards against
     * matching every service that mentions {@code "api"} or a two-letter directory name.
     */
    public static boolean isMatchable(String canonical) {
        if (canonical == null || canonical.length() < MIN_MATCHABLE_LENGTH) {
            return false;
        }
        return !NOISE_TOKENS.contains(canonical);
    }

    /**
     * Every spelling a service should answer to: the name itself, its stem, and — when the name has
     * two or more tokens — nothing else. Single-token names are deliberately conservative.
     */
    public static Set<String> aliasesFor(String rawName) {
        Set<String> aliases = new LinkedHashSet<>();
        String canonical = canonical(rawName);
        if (isMatchable(canonical)) {
            aliases.add(canonical);
        }
        String stem = canonicalStem(rawName);
        if (isMatchable(stem) && tokens(rawName).size() > 1) {
            aliases.add(stem);
        }
        return aliases;
    }

    /**
     * Extracts the host portion of a URL-ish string: {@code "http://log-quote-svc:9000/quotes"} →
     * {@code "log-quote-svc"}. Returns the input unchanged when it is not URL-shaped.
     */
    public static String hostOf(String urlish) {
        if (urlish == null || urlish.isBlank()) {
            return "";
        }
        String value = urlish.strip();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int at = value.indexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        return value.strip();
    }

    /** Host suffixes that mark a name as cluster-internal rather than a public domain. */
    private static final Set<String> INTERNAL_HOST_SUFFIXES =
            Set.of("local", "internal", "cluster", "svc", "lan", "intranet", "localdomain");

    /**
     * The name to <em>show</em> for a host.
     *
     * <p>For a cluster-internal name the first label is the service:
     * {@code log-quote-svc.logistics.svc.cluster.local} → {@code log-quote-svc}. For a public
     * domain the whole host is the identity — reducing {@code api.stripe.com} to {@code api} would
     * turn every third-party API into an indistinguishable node called "api".
     */
    public static String serviceLabelOf(String host) {
        String value = hostOf(host);
        if (!value.contains(".")) {
            return value;
        }
        String[] labels = value.split("\\.");
        String last = labels[labels.length - 1].toLowerCase(Locale.ROOT);
        return INTERNAL_HOST_SUFFIXES.contains(last) ? labels[0] : value;
    }

    /**
     * The first label of a host, whatever kind it is. Used when <em>matching</em> against known
     * services, where {@code log-quote-svc.logistics} should still find {@code log-quote-svc}.
     */
    public static String firstLabelOf(String host) {
        String value = hostOf(host);
        int dot = value.indexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }
}
