package com.serviceatlas.parser.scala;

import com.serviceatlas.parser.common.RepoFiles;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Identifies the HTTP/RPC framework a service is built on, from {@code libraryDependencies}
 * heuristics plus a couple of structural tells (FR-2.2). Drives node colour on the canvas (FR-5.6).
 */
public final class FrameworkDetector {

    public static final String PLAY = "Play Framework";
    public static final String AKKA_HTTP = "Akka HTTP";
    public static final String PEKKO_HTTP = "Pekko HTTP";
    public static final String HTTP4S = "http4s";
    public static final String GRPC = "gRPC";
    public static final String UNKNOWN = "Unknown";

    private FrameworkDetector() {
    }

    /**
     * Returns the primary framework. When a service uses several (a Play service with a gRPC
     * sidecar, say), the HTTP-serving framework wins because that is what the diagram is about;
     * {@link #detectAll} keeps the full set for the inspector panel.
     */
    public static String detect(List<ArtifactCoordinate> dependencies, RepoFiles files) {
        Set<String> all = detectAll(dependencies, files);
        for (String candidate : List.of(PLAY, HTTP4S, AKKA_HTTP, PEKKO_HTTP, GRPC)) {
            if (all.contains(candidate)) {
                return candidate;
            }
        }
        return UNKNOWN;
    }

    public static Set<String> detectAll(List<ArtifactCoordinate> dependencies, RepoFiles files) {
        Set<String> frameworks = new LinkedHashSet<>();
        for (ArtifactCoordinate dependency : dependencies) {
            String coordinate = (dependency.group() + ":" + dependency.artifact()).toLowerCase(Locale.ROOT);
            if (coordinate.contains("typesafe.play") || coordinate.contains("playframework")
                    || coordinate.contains(":play-") || coordinate.endsWith(":play")) {
                frameworks.add(PLAY);
            }
            if (coordinate.contains("akka-http")) {
                frameworks.add(AKKA_HTTP);
            }
            if (coordinate.contains("pekko-http")) {
                frameworks.add(PEKKO_HTTP);
            }
            if (coordinate.contains("http4s")) {
                frameworks.add(HTTP4S);
            }
            if (coordinate.contains("grpc") || coordinate.contains("scalapb")) {
                frameworks.add(GRPC);
            }
        }

        if (files != null) {
            // A conf/routes file is Play's defining structural marker, even when the dependency
            // lives in project/plugins.sbt rather than build.sbt.
            if (files.exists("conf/routes")) {
                frameworks.add(PLAY);
            }
            files.readString("project/plugins.sbt").ifPresent(plugins -> {
                String lower = plugins.toLowerCase(Locale.ROOT);
                if (lower.contains("sbt-plugin") || lower.contains("playscala") || lower.contains("play-")) {
                    frameworks.add(PLAY);
                }
                if (lower.contains("akka-grpc") || lower.contains("scalapb")) {
                    frameworks.add(GRPC);
                }
            });
        }
        return frameworks;
    }
}
