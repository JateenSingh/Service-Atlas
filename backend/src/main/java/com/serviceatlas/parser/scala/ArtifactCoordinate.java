package com.serviceatlas.parser.scala;

/**
 * A {@code libraryDependencies} coordinate as written in an SBT build.
 *
 * @param group     the organization, e.g. {@code com.acme.logistics}
 * @param artifact  the artifact name, e.g. {@code log-quote-svc-client}
 * @param version   the version literal, or {@code null} when it is a variable reference
 * @param file      repo-relative file the coordinate was read from
 * @param line      1-based line number
 * @param sourceLine the untouched source line, used as evidence
 */
public record ArtifactCoordinate(
        String group, String artifact, String version, String file, int line, String sourceLine) {

    /** {@code group:artifact} — the identity used when matching against other services. */
    public String coordinates() {
        return group + ":" + artifact;
    }

    /**
     * Artifact name with the cross-build Scala suffix removed:
     * {@code log-quote-svc-client_2.13} → {@code log-quote-svc-client}.
     */
    public String artifactWithoutScalaSuffix() {
        return artifact.replaceFirst("_\\d+(\\.\\d+)*$", "");
    }
}
