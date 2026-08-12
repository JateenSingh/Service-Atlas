package com.serviceatlas.parser.scala;

import com.serviceatlas.graph.model.Evidence;
import com.serviceatlas.graph.model.SignalSource;
import com.serviceatlas.parser.DependencySignal;
import java.util.ArrayList;
import java.util.List;

/**
 * FR-3.1 — emits one HIGH-confidence signal per {@code libraryDependencies} coordinate.
 *
 * <p>Every coordinate is emitted, including third-party ones. The graph builder decides which are
 * internal (they match a discovered service) and which are third-party noise; only internal matches
 * become edges. Filtering here instead would mean this scanner needed to know about other repos,
 * which is exactly the coupling the SPI avoids.
 */
@org.springframework.stereotype.Component
public final class BuildDependencyScanner implements ScalaSignalScanner {

    @Override
    public String id() {
        return "sbt-library-dependencies";
    }

    @Override
    public List<DependencySignal> scan(ScalaScanContext context) {
        List<DependencySignal> signals = new ArrayList<>();
        for (ArtifactCoordinate dependency : context.build().dependencies()) {
            Evidence evidence = Evidence.of(
                    SignalSource.BUILD_DEPENDENCY,
                    dependency.file(),
                    dependency.line(),
                    dependency.sourceLine(),
                    "libraryDependencies on " + dependency.coordinates());
            signals.add(new DependencySignal(
                    context.nodeKey(),
                    dependency.artifactWithoutScalaSuffix(),
                    null,
                    SignalSource.BUILD_DEPENDENCY,
                    SignalSource.BUILD_DEPENDENCY.defaultEdgeType(),
                    SignalSource.BUILD_DEPENDENCY.defaultConfidence(),
                    evidence));
        }
        return signals;
    }
}
