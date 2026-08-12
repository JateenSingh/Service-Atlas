package com.serviceatlas.parser.scala;

import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.SignalSource;
import com.serviceatlas.parser.DependencySignal;
import com.serviceatlas.parser.common.TextSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * FR-3.3 — outbound HTTP calls found in source: Play WS, sttp, http4s and Akka/Pekko HTTP clients.
 *
 * <p>MEDIUM confidence by design. A URL in source is weaker evidence than a declared dependency:
 * it may be dead code, or overridden at runtime. Three things keep the false-positive rate down:
 *
 * <ul>
 *   <li>comments are stripped before matching, so commented-out calls do not count;
 *   <li>test sources are not scanned at all;
 *   <li>{@code val} indirection and {@code getString("key")} config lookups are followed, so a real
 *       call is not missed just because the URL is not written inline.
 * </ul>
 */
@Component
public final class HttpClientScanner implements ScalaSignalScanner {

    /** A literal URL anywhere in production source — the common case across every client library. */
    private static final Pattern URL_LITERAL = Pattern.compile("\"(https?://[^\"\\s]+)\"");

    /** An interpolated URL built from a val: {@code s"$baseUrl/audit/events"}. */
    private static final Pattern INTERPOLATED_URL = Pattern.compile("s\"(\\$\\{?[A-Za-z_][A-Za-z0-9_.]*}?[^\"]*)\"");

    /** A client call that takes its target from configuration. */
    private static final Pattern CONFIG_LOOKUP = Pattern.compile(
            "(?:getString|getOptional\\[String]|get\\[String]|getConfig)\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");

    /** Markers that a line is issuing an HTTP request rather than merely mentioning a URL. */
    private static final Pattern CLIENT_CALL = Pattern.compile(
            "\\b(ws\\.url|wsClient\\.url|\\.url\\s*\\(|HttpRequest|singleRequest|basicRequest"
                    + "|Uri\\.unsafeFromString|uri\"|Request\\s*\\(|\\.get\\s*\\(|\\.post\\s*\\(|Http\\(\\))");

    @Override
    public String id() {
        return "scala-http-client-calls";
    }

    @Override
    public List<DependencySignal> scan(ScalaScanContext context) {
        ScalaSources sources = ScalaSources.read(context.files());
        ConfigValues config = ConfigValues.read(context.files());
        List<DependencySignal> signals = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (TextSource source : sources.sources()) {
            Map<String, String> values = ScalaSources.stringValues(source);

            for (TextSource.Match match : source.matches(URL_LITERAL)) {
                // The literal may itself be interpolated — s"http://host/orders/$id" — so substitute
                // before emitting, otherwise the hint (and the edge label) carries a raw $variable.
                String target = match.group(1);
                String resolved = target.indexOf('$') >= 0
                        ? ScalaSources.interpolate(target, values)
                        : target;
                add(signals, seen, context, source, match, resolved, "literal URL");
            }

            for (TextSource.Match match : source.matches(INTERPOLATED_URL)) {
                String resolved = ScalaSources.interpolate(match.group(1), values);
                if (resolved.startsWith("http://") || resolved.startsWith("https://")) {
                    add(signals, seen, context, source, match, resolved, "URL built from a local constant");
                }
            }

            for (TextSource.Match match : source.matches(CONFIG_LOOKUP)) {
                if (!isClientCall(match.rawLine())) {
                    continue;
                }
                String key = match.group(1);
                config.byKey(key).ifPresent(entry -> add(
                        signals, seen, context, source, match, entry.value(),
                        "config key " + key + " resolved to " + entry.value()));
            }
        }
        return signals;
    }

    private void add(List<DependencySignal> signals, Set<String> seen, ScalaScanContext context,
                     TextSource source, TextSource.Match match, String target, String how) {
        String fingerprint = source.path() + "|" + match.line() + "|" + target;
        if (!seen.add(fingerprint)) {
            return;
        }
        Evidence evidence = Evidence.of(
                SignalSource.HTTP_CLIENT_CALL,
                source.path(),
                match.line(),
                match.rawLine(),
                "HTTP client call to " + target + " (" + how + ")");
        signals.add(DependencySignal.of(context.nodeKey(), target, SignalSource.HTTP_CLIENT_CALL, evidence));
    }

    private boolean isClientCall(String line) {
        return CLIENT_CALL.matcher(line).find();
    }
}
