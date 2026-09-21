package qouteall.imm_ptl.core.compat.sodium_compatibility;

/**
 * 26.3 / sodium 0.9.2 — duck on {@code SodiumWorldRenderer} (implemented by
 * {@code MixinSodiumWorldRenderer_OuterReprepare}). The defect, the measurement and the port are on
 * {@code SodiumInterface.OnSodiumPresent#ip_onPortalLayerPopped}.
 */
public interface IPSodiumOuterReprepare {

    /**
     * Re-run {@code layer}'s own {@code prepareChunkRendering} on this renderer when the renderer's prepared state was
     * last written by a DIFFERENT portal layer this frame. No-op otherwise (nothing prepared since, or that layer has not
     * prepared on this renderer this frame).
     */
    void ip_reprepareIfPreparedByAnotherLayer(int layer);
}
