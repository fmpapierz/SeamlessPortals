package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.client.SeamlessClientTeleport;
import com.warwa.seamlessportals.render.StencilPortalRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The mod's PRE-RENDER PUMP host — S3 (R2 frame-anchor relocation + A4 upkeep re-home).
 *
 * <p><b>Anchor.</b> Fires inside {@code Minecraft.renderFrame(Z)V} at the {@code INVOKE} of
 * {@code gameRenderer.update(DeltaTracker)} (26.2 {@code Minecraft.java:1290}), i.e. immediately
 * BEFORE the frame's camera is positioned ({@code GameRenderer.update} runs
 * {@code mainCamera.update(deltaTracker)}, which does {@code alignWithEntity}). This is the
 * faithful 26.2 translation of IP 1.21.3's {@code MixinGameRenderer.onFarBeforeRendering} at
 * {@code GameRenderer.render} HEAD (portal-animation.md #14): the pump runs before ALL of the
 * frame's world-state reads (camera-update → extract → render), so a render-time teleport renders
 * the crossing frame from the DESTINATION rather than from the stale pre-teleport camera.
 *
 * <p><b>Why not {@code GameRenderer.update} HEAD</b> (the prior {@code GameRendererFrameCrossingMixin}
 * placement, now superseded)? The panorama/screenshot path {@code Minecraft.grabPanoramixScreenshot}
 * calls {@code gameRenderer.update}/{@code extract}/{@code renderLevel} DIRECTLY
 * ({@code Minecraft.java:2779-2781}), bypassing {@code renderFrame}. Anchoring at
 * {@code GameRenderer.update} HEAD would fire the crossing check DURING a panorama capture; IP's
 * 1.21.3 render-HEAD hook does not fire on that path either, so the {@code renderFrame} call-site
 * is the fidelity-equivalent anchor — a crossing never fires mid-panorama. See
 * {@code migration/port-notes/S03-frame-anchor.md}.
 *
 * <p><b>Shared host (D3).</b> This MOD-OWNED body is the S13 flag-dispatch point: at S13 it becomes
 * {@code if (entityPortals) { <ported IP manageTeleportation chain> } else { <the block-era pump
 * below> }}. The block-era pump is intentionally kept as a single ordered unit so that gate slots
 * in without restructuring, and IP-ported code will live in its own branch (never flag-polluting
 * IP bodies). No flag is added now — none exists until S13.
 *
 * <p>No {@code require = 0}: if the {@code gameRenderer.update} call site inside {@code renderFrame}
 * ever moves, fail loudly at load rather than silently dropping the pump (which would reintroduce
 * the crossing-frame flash).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftFramePumpMixin {

    @Inject(
        method = "renderFrame(Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V"
        )
    )
    private void seamlessportals$preRenderPump(boolean advanceGameTime, CallbackInfo ci) {
        // BLOCK-ERA pre-render pump. Ordering mirrors IP's MixinGameRenderer.onFarBeforeRendering:
        // teleport/crossing management FIRST, then the per-frame upload/upkeep.

        // 1. Crossing detection — the block-era analog of ClientTeleportationManager
        //    .manageTeleportation(false). Called UNCONDITIONALLY, exactly as the old
        //    GameRenderer.update-HEAD site did: it self-guards on player/level == null and
        //    resets the crossing tracer in that case (SeamlessClientTeleport:201-204).
        SeamlessClientTeleport.checkCameraCrossingPerFrame();

        // 2. Per-frame upkeep (A4 re-home) — block-era analog of MyRenderHelper.earlyRemoteUpload.
        //    Gated on level != null, mirroring IP's pre-render-block guard
        //    (MixinGameRenderer.java:78) and preserving the old renderLevel-HEAD precondition
        //    (renderLevel only fires with a level present).
        if (Minecraft.getInstance().level != null) {
            StencilPortalRenderer.frameUpkeep();
        }
    }
}
