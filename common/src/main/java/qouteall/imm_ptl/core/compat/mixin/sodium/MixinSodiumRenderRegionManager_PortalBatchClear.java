package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IPPortalLayerBatchClearable;

import java.util.Collection;

/**
 * IS5-W FIX 3 (leg d) — per-upload PORTAL-LAYER batch invalidation at the REAL upload seam
 * (fix panel wf_a2d7890c-115 fold §2 Fix 3).
 *
 * <p><b>Why HOOK 2 is not enough under iris.</b> The C2-1 isolation mirrors sodium's own batch
 * invalidation via a RETURN inject on {@code RenderRegion.clearAllCachedBatches} (HOOK 2) — but
 * iris {@code @Redirect}s the {@code clearAllCachedBatches()} CALL inside
 * {@code RenderRegionManager.uploadResults} to its own {@code iris$forceClearAllBatches()} (which
 * clears iris's swapped shadow+regular sets), so under iris the vanilla method — and with it
 * HOOK 2 — never runs on mesh uploads. The per-portal-layer batches then keep {@code isFilled}
 * across region storage re-uploads and can draw STALE commands (the C2-1 javadoc's
 * "strict superset — never stale" claim is FALSE under iris). This mixin restores the mirror at
 * the seam iris cannot bypass: the RETURN of the private
 * {@code uploadResults(RenderRegion, Collection, UniformBufferManager)} (javap-confirmed against
 * sodium-mc26.2-0.9.1), calling the {@link IPPortalLayerBatchClearable} duck on the region.
 *
 * <p>Orthogonal to iris's redirect (that replaces a CALL; this injects at method RETURN). Under
 * vanilla sodium the vanilla {@code clearAllCachedBatches} still runs + HOOK 2 fires — the double
 * clear is idempotent ({@code MultiDrawBatch.clear()} zeroes size/isFilled). The duck body
 * first-statement-early-outs on a null layer set, so the non-portal path is cost-identical; the
 * shared FIX-2 lever ({@code IPGlobal.isShadowScopeIsolationActive()}) gates the actual clearing.
 * Clears only — never frees (HOOK 4 owns the region-lifetime delete).
 *
 * <p>The class name carries {@code Sodium} for the compat plugin's substring gate.
 */
@Mixin(value = RenderRegionManager.class, remap = false)
public abstract class MixinSodiumRenderRegionManager_PortalBatchClear {

    @Inject(
        method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
            + "Ljava/util/Collection;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
        at = @At("RETURN")
    )
    private void ip_clearPortalLayerBatchesAfterUpload(
        RenderRegion region, Collection<?> results, UniformBufferManager uniformBufferManager,
        CallbackInfo ci
    ) {
        ((IPPortalLayerBatchClearable) (Object) region).ip_clearPortalLayerBatches();
    }
}
