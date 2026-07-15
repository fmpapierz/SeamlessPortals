// F21 compileOnly stub (entity-portal migration; S00-U0-decisions F21).
// Empty third-party shell — NOT IP source, NOT shipped. Iris has no MC 26.2 build.
// S12-A EXTENSION (iris half consumed here): ExperimentalIrisPortalRenderer.invokeWorldRendering (:144)
// does `pipeline instanceof IrisRenderingPipeline p` then `p.isBeforeTranslucent = true`. Modelled as a
// WorldRenderingPipeline implementor exposing the mutable public field. Deleted at S20.
package net.irisshaders.iris.pipeline;

public class IrisRenderingPipeline implements WorldRenderingPipeline {
    public boolean isBeforeTranslucent;
}
