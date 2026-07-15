package qouteall.imm_ptl.core.compat.iris_compatibility;

// S12-A Iris compile-shell duck (VERBATIM IP). Implemented by an Iris pipeline via a compat mixin (held);
// consumed by ExperimentalIrisPortalRenderer to un-force Iris's depth-mask disable. No iris types referenced
// here (the shadow-targets member stays commented, as in IP), so this interface self-compiles. Held/inert
// until S13; Iris never loads on 26.2 (no Iris build).
public interface IEIrisNewWorldRenderingPipeline {
    void ip_setIsRenderingWorld(boolean cond);

//    ShadowRenderTargets ip_getShadowRenderTargets();
}
