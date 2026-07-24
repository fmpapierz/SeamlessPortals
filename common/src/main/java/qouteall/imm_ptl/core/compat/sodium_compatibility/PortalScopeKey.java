package qouteall.imm_ptl.core.compat.sodium_compatibility;

import net.minecraft.client.Minecraft;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * IS5-W FIX 2 — the shared per-portal-layer storage KEY, split by shadow-vs-camera scope
 * (fix panel wf_a2d7890c-115 fold §2 Fix 2; mechanism leg B).
 *
 * <p>Iris isolates shadow-vs-regular per-region draw state by PHYSICALLY SWAPPING
 * {@code RenderRegion.renderList} + {@code cachedBatches} at shadow-scope entry/exit. The C2-1
 * per-portal-layer overrides in {@code MixinSodiumRenderRegion} bypass that swap, and keying by
 * {@code layer-1} alone made the dest SHADOW and dest CAMERA scopes share ONE layer list + ONE
 * layer batch per region (the scope collapse — probe-proven live). This helper is the single
 * source of the layer index for BOTH structures: with the fix active the key doubles per layer —
 * even slot = camera scope, odd slot = shadow scope — restoring iris's two-scope separation
 * INSIDE the portal layer. Lever-off returns exactly the pre-fix {@code layer-1} at both sites
 * (byte-identical A/B baseline).
 *
 * <p><b>Scope discriminator:</b> {@code IrisInterface.invoker.isRenderingShadowMap()} ==
 * {@code ShadowRenderer.ACTIVE} (constant false when iris is absent — the key then never splits,
 * and shaders-off/sodium-alone behave exactly as before; there is no shadow pass to separate).
 *
 * <p><b>Thread pin (load-bearing, C2 discipline):</b> the (isRendering, layer, shadow-scope)
 * triple is RENDER-THREAD state — this key must never be evaluated from sodium's async cull
 * threads (the C2 lesson: never bake a portal predicate into the async cull trees). Both call
 * sites live on the synchronous collect/draw path; the assert documents + dev-enforces it.
 *
 * <p>Only meaningful while {@code PortalRendering.isRendering()} — both call sites early-return
 * to region-own storage outside portal rendering (their existing gates, unchanged).
 */
public final class PortalScopeKey {

    private PortalScopeKey() {}

    /**
     * The per-portal-layer storage index for the CURRENT (render-thread) scope. Caller guarantees
     * {@code PortalRendering.isRendering()} (both existing gates do).
     */
    public static int index() {
        assert Minecraft.getInstance().isSameThread()
            : "PortalScopeKey.index() evaluated off the render thread (async-cull hazard)";
        int base = PortalRendering.getPortalLayer() - 1;
        if (!IPGlobal.isShadowScopeIsolationActive()) {
            return base; // pre-fix key, byte-identical A/B baseline
        }
        return 2 * base + (IrisInterface.invoker.isRenderingShadowMap() ? 1 : 0);
    }
}
