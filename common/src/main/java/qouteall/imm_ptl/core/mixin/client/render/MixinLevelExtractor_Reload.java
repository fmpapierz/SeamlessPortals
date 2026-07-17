package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * S14-A FIX-9 (audit link invalidate, MAJOR M9): the IP reload cascade, landed. IP 1.21.3
 * anchored this at {@code LevelRenderer.allChanged} TAIL (IP {@code MixinLevelRenderer.java:
 * 403-414}: validate {@code mc.levelRenderer == this}, then
 * {@code ClientWorldLoader._onWorldRendererReloaded()}), so a main-renderer reload
 * (resource-pack change, F3+A, graphics options) propagated {@code allChanged} to every
 * secondary dimension's renderer. 26.2 moved {@code allChanged} onto {@link LevelExtractor}; the
 * planned retarget (api-map world-loader-root row 16) was never landed — the callee sat fully
 * ported but orphaned (zero call sites), so secondary portal views kept stale tint caches and
 * compiled meshes across reloads.
 *
 * <p>The main-extractor identity filter replaces IP's swap-dependent Validate: vanilla binds
 * {@code Minecraft.levelExtractor} ONCE (Minecraft.java:649) and the S14-A crossing cutover keeps
 * it the CURRENT main driver, so {@code this == mc.levelExtractor} is exactly "the main render
 * reloaded". Cascaded secondary {@code allChanged} calls TAIL-fire here too but fail the filter;
 * {@code _onWorldRendererReloaded}'s own guards (isReloadingOtherWorldRenderers /
 * PortalRendering.isRendering / isCreatingClientWorld) preserve IP's exact recursion semantics.
 * The {@code getIsInitialized} guard skips the whole cascade during world join/exit transitions
 * (no secondaries can exist).
 *
 * <p>IP's OTHER allChanged anchor (HEAD-cancel while {@code WorldRenderInfo.isRendering}, IP
 * {@code MixinLevelRenderer.java:394-400}) is DESIGN-ABSORBED on 26.2: allChanged is now
 * cheap-deferred flag-setting consumed by the next extract, and the S13-H per-frame
 * tracker-re-read rule covers the replacement identity — recorded in the S14A port-note ledger.
 */
@Mixin(LevelExtractor.class)
public class MixinLevelExtractor_Reload {

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void portal_onAllChanged(CallbackInfo ci) {
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }
        if ((Object) this == Minecraft.getInstance().levelExtractor) {
            ClientWorldLoader._onWorldRendererReloaded();
        }
    }
}
