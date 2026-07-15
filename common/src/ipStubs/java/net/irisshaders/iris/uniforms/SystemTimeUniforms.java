// F21 compileOnly stub (entity-portal migration; S00-U0-decisions F21).
// Empty third-party shell — NOT IP source, NOT shipped. Iris has no MC 26.2 build.
// S12-A EXTENSION (iris half consumed here): ExperimentalIrisPortalRenderer.invokeWorldRendering (:140,:142)
// calls SystemTimeUniforms.COUNTER.beginFrame(). Modelled as a static COUNTER holding a beginFrame() member.
// Deleted at S20.
package net.irisshaders.iris.uniforms;

public class SystemTimeUniforms {
    public static final Counter COUNTER = new Counter();

    public static class Counter {
        public void beginFrame() {
        }
    }
}
