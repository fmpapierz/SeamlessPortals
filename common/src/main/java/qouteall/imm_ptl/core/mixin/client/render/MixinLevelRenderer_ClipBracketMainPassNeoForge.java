package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.PerEntityClipBracket;

/**
 * NF-PARITY W21 (2026-08-25, NEOFORGE-ONLY — {@code NEOFORGE_ONLY_MIXINS}): the Mechanism-B
 * per-entity clip-bracket main-pass draw site.
 *
 * <p>On Fabric this is driven by {@code LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN}
 * (registered in {@code SeamlessPortalsClientFabric}), which fires between
 * {@code featureFrame.executeOutline()} and {@code renderGroup(TRANSLUCENT, ...)} inside the
 * main-pass framegraph lambda. NeoForge posts NO event in that one-instruction gap — its
 * nearest, {@code RenderLevelStageEvent.AfterTranslucentFeatures}, fires BEFORE
 * {@code executeOutline()} (NF LevelRenderer.java:470-474), which would composite bracket
 * geometry against the outline buffer in the wrong order for outlined seam-straddling
 * entities. This mixin reproduces the exact Fabric timing: inject AFTER the single
 * {@code PreparedFrame.executeOutline()} INVOKE.
 *
 * <p><b>Selector robustness:</b> the draw site lives in a compiler-generated framegraph
 * lambda whose synthetic index ({@code lambda$addMainPass$N}) is compiler-order-dependent, so
 * the method list names the first few candidates; only the real main-pass lambda contains an
 * {@code executeOutline()} call, so exactly one injection lands regardless of which index the
 * lambda got (require = 1 makes a total miss a HARD boot failure, never a silent no-op —
 * verify the synthetic name via javap on the deobf jar per the repo's standing rule).
 *
 * <p>Dest passes never reach this: {@code SecondaryWorldRenderCore} hand-drives
 * {@code renderGroup} without a framegraph, so {@code addMainPass}'s lambda does not run for
 * them (same argument as the flag-ON driver's). Inert with no straddling entities (one map
 * lookup inside the callee).
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_ClipBracketMainPassNeoForge {

    @Inject(
        method = {
            "lambda$addMainPass$0", "lambda$addMainPass$1", "lambda$addMainPass$2",
            "lambda$addMainPass$3", "lambda$addMainPass$4", "lambda$addMainPass$5"
        },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
                + "executeOutline()V",
            shift = At.Shift.AFTER
        ),
        require = 1,
        remap = false
    )
    private void ip_onMainPassBeforeTranslucentTerrain(CallbackInfo ci) {
        PerEntityClipBracket.onMainPassBeforeTranslucentTerrain();
    }
}
