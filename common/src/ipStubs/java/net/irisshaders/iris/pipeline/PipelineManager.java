// F21 compileOnly stub (entity-portal migration; S00-U0-decisions F21).
// Empty third-party shell — NOT IP source, NOT shipped. S04-compat.md §3 IRIS I4
// (inferred: the return of Iris.getPipelineManager()). IrisInterface calls destroyPipeline().
// S12-A EXTENSION: ExperimentalIrisPortalRenderer.invokeWorldRendering (:122) + onBeginIrisTranslucentRendering
// (:66) call getPipelineManager().getPipeline().get() -> Optional<WorldRenderingPipeline>. Deleted at S20.
package net.irisshaders.iris.pipeline;

import java.util.Optional;

public class PipelineManager {
    public void destroyPipeline() {
    }

    public Optional<WorldRenderingPipeline> getPipeline() {
        return Optional.empty();
    }
}
