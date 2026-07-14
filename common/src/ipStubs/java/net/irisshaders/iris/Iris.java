// F21 compileOnly stub (entity-portal migration; S00-U0-decisions F21).
// Empty third-party shell — NOT IP source, NOT shipped. Iris has no MC 26.2 build.
// S04-compat.md §3 IRIS I1 (FLAG A): the IrisInterface invoker BASE lands at S4, so its
// minimal iris subset is provisioned NOW (not only at S12). Deleted at S20.
package net.irisshaders.iris;

import net.irisshaders.iris.pipeline.PipelineManager;

import java.util.Optional;

public class Iris {
    public static Optional<?> getCurrentPack() {
        return Optional.empty();
    }

    public static PipelineManager getPipelineManager() {
        return null;
    }

    public static String getCurrentPackName() {
        return null;
    }
}
