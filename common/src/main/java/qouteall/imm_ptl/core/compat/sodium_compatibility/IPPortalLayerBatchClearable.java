package qouteall.imm_ptl.core.compat.sodium_compatibility;

/**
 * IS5-W FIX 3 duck — implemented by {@code MixinSodiumRenderRegion} on sodium's
 * {@code RenderRegion} (fix panel wf_a2d7890c-115 fold §2 Fix 3; mechanism leg d).
 *
 * <p>Under iris, sodium's upload-time {@code RenderRegion.clearAllCachedBatches()} call is
 * {@code @Redirect}ed to {@code iris$forceClearAllBatches()} (which clears iris's own swapped
 * batch sets), so the mod's HOOK-2 mirror on {@code clearAllCachedBatches} RETURN NEVER fires on
 * mesh uploads — the per-portal-layer batches miss per-upload invalidation and can draw stale
 * commands after region storage re-uploads. {@code MixinSodiumRenderRegionManager_PortalBatchClear}
 * calls this at the REAL {@code uploadResults(RenderRegion,...)} RETURN (orthogonal to iris's
 * redirect; idempotent with HOOK 2 under vanilla sodium). Clears only — never frees (HOOK 4 owns
 * the region-lifetime delete).
 */
public interface IPPortalLayerBatchClearable {

    void ip_clearPortalLayerBatches();
}
