package com.serviceatlas.parser.scala;

import com.serviceatlas.config.ServiceAtlasProperties;
import com.serviceatlas.parser.common.RepoFiles;

/**
 * What a {@link ScalaSignalScanner} gets to work with: the node it is producing signals for, the
 * repository's files, and the already-parsed build definition.
 *
 * @param nodeKey key of the service node that owns anything this scanner finds
 * @param files   read-only file access, scoped to the repository
 * @param build   the parsed SBT build, so scanners need not re-read it
 */
public record ScalaScanContext(
        String nodeKey, RepoFiles files, SbtBuild build, ServiceAtlasProperties.Scan settings) {
}
