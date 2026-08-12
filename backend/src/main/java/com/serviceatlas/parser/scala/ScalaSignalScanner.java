package com.serviceatlas.parser.scala;

import com.serviceatlas.parser.DependencySignal;
import java.util.List;

/**
 * One extraction rule from FR-3, isolated so each signal source can be tested against its own
 * fixtures and enabled or disabled independently.
 *
 * <p>Scanners return <em>unresolved</em> signals: they name a target as the repository names it,
 * and leave matching to the graph builder.
 */
public interface ScalaSignalScanner {

    /** Identifier for logs and tests. */
    String id();

    /**
     * Scans one repository. Implementations must not throw for malformed input — return what was
     * found and let the rest go (FR-7.2).
     */
    List<DependencySignal> scan(ScalaScanContext context);
}
