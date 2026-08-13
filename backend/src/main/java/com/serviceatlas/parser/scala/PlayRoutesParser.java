package com.serviceatlas.parser.scala;

import com.serviceatlas.graph.model.Endpoint;
import com.serviceatlas.parser.common.RepoFiles;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FR-3.4 — catalogues the endpoints a service <em>exposes</em>, from {@code conf/routes}.
 *
 * <p>Explicitly informational: routes describe inbound surface, so they never create edges. They
 * are used to label edges (matching a caller's path against the callee's routes) and to fill the
 * inspector panel.
 */
public final class PlayRoutesParser {

    private static final Set<String> HTTP_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    /** {@code GET   /quotes/:id   controllers.QuoteController.get(id: String)} */
    private static final Pattern ROUTE = Pattern.compile("^\\s*([A-Z]+)\\s+(\\S+)\\s+(.+?)\\s*$");

    /** {@code ->  /admin  admin.Routes} — Play's sub-router include. */
    private static final Pattern INCLUDE = Pattern.compile("^\\s*->\\s+(\\S+)\\s+(\\S+)\\s*$");

    /** Marks an endpoint as deprecated: {@code # @deprecated as of ...} or {@code # Deprecated ...} */
    private static final Pattern DEPRECATION = Pattern.compile("^\\s*#.*[Dd]eprecated", Pattern.CASE_INSENSITIVE);

    private static final int MAX_ENDPOINTS = 500;

    private final RepoFiles files;

    public PlayRoutesParser(RepoFiles files) {
        this.files = files;
    }

    /** Reads {@code conf/routes} plus any {@code conf/*.routes} sub-routers. */
    public List<Endpoint> parse() {
        return parse("conf");
    }

    public List<Endpoint> parse(String confDirectory) {
        List<Endpoint> endpoints = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        List<String> routeFiles = new ArrayList<>();
        if (files.exists(confDirectory + "/routes")) {
            routeFiles.add(confDirectory + "/routes");
        }
        files.find(confDirectory, path -> path.getFileName().toString().endsWith(".routes"))
                .forEach(path -> routeFiles.add(files.relativize(path)));

        for (String routeFile : routeFiles) {
            files.readSource(routeFile).ifPresent(source -> {
                boolean nextIsDeprecated = false;
                for (String raw : source.rawLines()) {
                    String line = raw.strip();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.startsWith("#")) {
                        if (DEPRECATION.matcher(line).matches()) {
                            nextIsDeprecated = true;
                        }
                        continue;
                    }
                    Matcher include = INCLUDE.matcher(line);
                    if (include.matches()) {
                        nextIsDeprecated = false;
                        continue; // the included router's own file is parsed separately
                    }
                    Matcher matcher = ROUTE.matcher(line);
                    if (!matcher.matches()) {
                        nextIsDeprecated = false;
                        continue;
                    }
                    String method = matcher.group(1);
                    if (!HTTP_METHODS.contains(method)) {
                        nextIsDeprecated = false;
                        continue;
                    }
                    Boolean deprecated = nextIsDeprecated ? true : null;
                    Endpoint endpoint = new Endpoint(method, matcher.group(2), matcher.group(3), deprecated);
                    if (seen.add(endpoint.signature()) && endpoints.size() < MAX_ENDPOINTS) {
                        endpoints.add(endpoint);
                    }
                    nextIsDeprecated = false;
                }
            });
        }
        return endpoints;
    }
}
