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
        // ===== S13 FLAG-DISPATCH SCAFFOLD (still-INERT at S12-B) =====================================
        // Per S03-frame-anchor.md §6 + EXCLUSIVITY_LEDGER §4 (row 1, "The S3-relocated pre-render pump
        // host") this body is the S13 flag-dispatch point. At S13 step 3 — the SAME commit that
        // registers the IP client-mixin set and introduces the load-time `entityPortals` flag — it
        // becomes:
        //
        //   if (entityPortals) {
        //       // ported IP pre-render chain (IP anchor MixinGameRenderer.java:86-96), all held qouteall.*:
        //       //   float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        //       //   RenderStates.updatePreRenderInfo(partialTick);
        //       //   StableClientTimer.update(level.getGameTime(), partialTick);
        //       //   ClientPortalAnimationManagement.update();          // must update before teleportation
        //       //   ClientTeleportationManager.manageTeleportation(false);
        //       //   IPGlobal.PRE_GAME_RENDER_EVENT.invoker().run();
        //       //   if (IPCGlobal.earlyRemoteUpload) MyRenderHelper.earlyRemoteUpload();
        //   } else {
        //       <the block-era pump below, verbatim>
        //   }
        //
        // The IP branch CANNOT be live code yet, so it stays a documented scaffold here (inert):
        //   (a) the `entityPortals` flag is created at S13 (does not exist to reference now); and
        //   (b) RenderStates / StableClientTimer / ClientPortalAnimationManagement /
        //       ClientTeleportationManager / MyRenderHelper are HELD qouteall.* classes EXCLUDED from
        //       the shipping build until the S13 cutover — referencing them from this shipped mod file
        //       would break the shipping-green invariant.
        // The block-era pump therefore runs UNCONDITIONALLY at S12-B — runtime behavior byte-identical
        // to S3 (this edit adds only the scaffold comment; no live call changed). The D3 rule is
        // preserved: mod code dispatches on the flag, the ported IP body lives in its own branch and is
        // never flag-polluted. Kept as a single ordered unit so S13 wraps it without restructuring.
        // ============================================================================================

        // ----- flag-OFF path: BLOCK-ERA pre-render pump (the ONLY live path until S13) -----
        // Ordering mirrors IP's MixinGameRenderer.onFarBeforeRendering: teleport/crossing management
        // FIRST, then the per-frame upload/upkeep.

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
