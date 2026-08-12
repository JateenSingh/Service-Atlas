package com.serviceatlas.parser.scala;

import java.util.List;

/**
 * The facts extracted from a repository's SBT build definition (FR-2.2).
 *
 * @param name         service name from {@code name :=}, falling back to the directory name
 * @param nameFromBuild whether {@code name} came from the build file rather than the directory
 * @param organization the {@code organization :=} value, if declared
 * @param scalaVersion the {@code scalaVersion :=} value, if declared
 * @param sbtVersion   the {@code sbt.version} from {@code project/build.properties}
 * @param dependencies every {@code libraryDependencies} coordinate found
 * @param modules      sub-projects of a multi-module build
 * @param warnings     non-fatal problems worth showing on the node
 */
public record SbtBuild(
        String name,
        boolean nameFromBuild,
        String organization,
        String scalaVersion,
        String sbtVersion,
        List<ArtifactCoordinate> dependencies,
        List<SbtModule> modules,
        List<String> warnings) {

    public SbtBuild {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        modules = modules == null ? List.of() : List.copyOf(modules);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
