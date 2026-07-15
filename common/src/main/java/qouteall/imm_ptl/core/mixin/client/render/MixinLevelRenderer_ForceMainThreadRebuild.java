package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.render.ForceMainThreadRebuild;

/**
 * S12-B (render client-mixin half) — IP {@code MixinLevelRenderer_ForceMainThreadRebuild}
 * ({@code IP:mixin/client/render/MixinLevelRenderer_ForceMainThreadRebuild.java}), 26.2-RETARGETED
 * (mixin-client.md §7 NEEDS-RETARGET).
 *
 * <p><b>The prioritize-chunk-updates read moved from a live {@code Options} call to an extracted field.</b>
 * IP {@code @ModifyVariable}'d the boolean produced by {@code Options.prioritizeChunkUpdates()} INVOKE
 * inside {@code compileSections}. On 26.2 {@code compileSections(CameraRenderState)} reads
 * {@code this.optionsRenderState.prioritizeChunkUpdates} ({@code LevelRenderer.java:619-623}, extracted at
 * {@code GameRenderer.java:624}) into a local {@code rebuildSync} that selects
 * {@code section.compileSync(...)} ({@code :635}) vs {@code compileAsync(...)} ({@code :638}). The
 * {@code @ModifyVariable} therefore retargets onto the {@code rebuildSync} boolean local (force it true on a
 * portal-force frame).
 *
 * <p><b>S13 anchor-verify.</b> The local-capture ({@code name = "rebuildSync"}) resolves in dev (Mojmap LVT);
 * if a stripped LVT defeats name-capture at S13, the documented alternative is a {@code @WrapOperation} on
 * the {@code compileAsync} call → conditionally {@code compileSync} (api-map/mixin-client.md §7).
 * Held/UNREGISTERED until S13.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_ForceMainThreadRebuild {
    @ModifyVariable(
        method = "compileSections",
        at = @At("STORE"),
        name = "rebuildSync"
    )
    private boolean modifyShouldImmediatelyRebuild(boolean originalValue) {
        if (ForceMainThreadRebuild.isCurrentFrameForceMainThreadRebuild()) {
            return true;
        }
        else {
            return originalValue;
        }
    }
}
