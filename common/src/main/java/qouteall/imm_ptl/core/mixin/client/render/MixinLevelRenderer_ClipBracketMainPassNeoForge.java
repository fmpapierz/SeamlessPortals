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

    // 26.3: the slot this reproduces MOVED with Fabric's. 26.2's main-pass lambda ran executeOutline() and then
    // renderGroup(TRANSLUCENT) back to back (mc262-ref LevelRenderer.java:436-438), so "AFTER executeOutline" WAS
    // "BEFORE_TRANSLUCENT_TERRAIN". 26.3 draws translucent terrain inside the new executeClassicTransparency and runs the
    // outline pass only AFTER that method returns (NF-patched 26.3 LevelRenderer.java:465-475, 713-748), so the old anchor
    // would now fire AFTER translucent terrain — the wrong side of it. Fabric API 0.161 fires BEFORE_TRANSLUCENT_TERRAIN
    // from its @WrapOperation on the `renderGroup(TRANSLUCENT, renderPass, ..)` INVOKE in executeClassicTransparency
    // (javap fabric-rendering-v1 27.0.14 LevelRendererMixin.wrapRenderTranslucentTerrain), i.e. immediately before that
    // draw — and this injects at exactly that INVOKE. It is a real method now, not a synthetic lambda, so the
    // lambda-index candidate list is gone (NF-patched jar: exactly one such INVOKE in the method; require/allow = 1).
    // PASS-FREE: LevelRendererMainPassSplitMixin (priority 900, applied first => runs first) has already closed vanilla's
    // "Main" pass at this same point, so the handlers below may open their own passes exactly as on 26.2.
    @Inject(
        method = "executeClassicTransparency",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup("
                + "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                + "Lcom/mojang/renderpearl/api/commands/RenderPass;"
                + "Lcom/mojang/renderpearl/api/textures/GpuSampler;"
                + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"
        ),
        require = 1,
        allow = 1,
        remap = false
    )
    private void ip_onMainPassBeforeTranslucentTerrain(CallbackInfo ci) {
        PerEntityClipBracket.onMainPassBeforeTranslucentTerrain();
        // SEAM CLIP main-pass draw site (NF-PARITY 2026-08-30) — Fabric's SECOND
        // BEFORE_TRANSLUCENT_TERRAIN registration rides the same injection, in the same
        // registration order (bracket first, seam clip second). Self-guarded: active() +
        // PortalRendering.isRendering() + null-level early returns — inert flag-OFF/lever-OFF.
        com.warwa.seamlessportals.render.SeamClipRenderer.onMainPassBeforeTranslucentTerrain();
    }
}
