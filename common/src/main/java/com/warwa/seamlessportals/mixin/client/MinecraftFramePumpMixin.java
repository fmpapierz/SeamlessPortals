package com.warwa.seamlessportals.mixin.client;

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
 * <p><b>Host history (D3).</b> This MOD-OWNED body was the S13 flag-dispatch point
 * ({@code if (entityPortals) { <ported IP chain> } else { <the block-era pump> }}). S20 deleted the
 * block era and then the flag, so nothing dispatches here any more: the body IS the ported IP
 * chain. It stays a mod-owned mixin because the anchor is a 26.2 relocation, not IP's own.
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
        // S20 INCREMENT 4: the `if (isEntityPortals())` dispatch is gone with the flag — this body is
        // now unconditionally IP's pre-render chain, i.e. IP MixinGameRenderer.onFarBeforeRendering
        // :76-99 relocated onto the renderFrame anchor (S03-frame-anchor.md §6). It must run before
        // the frame's camera is positioned so a render-time teleport renders the crossing frame from
        // the DESTINATION.
        //
        // CROSS-LOADER: this class is listed in SeamlessMixinConfigPlugin.FABRIC_ONLY_IP_DRIVERS and
        // is NOT woven off Fabric. Without that, the first NeoForge client frame would reach
        // IPGlobal's static initialiser below and die with ExceptionInInitializerError — see that
        // set's javadoc and port-note S20-block-era-deletion.md §G.12.
        Minecraft mc = Minecraft.getInstance();
        // IP MixinGameRenderer:76 — PRE_TOTAL_RENDER_TASK_LIST.processTasks() runs at render HEAD BEFORE
        // the level==null guard (IP :78), so the GC-cleaner disposal one-shot tasks PortalRenderInfo
        // queues (PortalRenderInfo.java:135) are always drained; without it they accumulate unboundedly.
        // Placed above the level guard to match IP's ordering exactly.
        qouteall.imm_ptl.core.IPGlobal.PRE_TOTAL_RENDER_TASK_LIST.processTasks();
        if (mc.level == null) {
            return;
        }
        // S15 pearl-freeze fix, client half (26.2-FORCED, port-note S15 §5): 26.2's
        // setScreenAndShow renders a frame SYNCHRONOUSLY mid-packet-handling
        // (Minecraft.java:2294 -> renderFrame). Vanilla respawn handling
        // (ClientPacketListener.startWaitingForNewLevel:1630) fires that frame in the window
        // where mc.level is already the NEW dim's level but mc.player is still the OLD-dim
        // player (the new LocalPlayer is created later in handleRespawn). No such mid-packet
        // frame exists in IP's 1.21.3 substrate. Running the pre-render chain against the
        // incoherent pair crashed the connection (initializeIfNeeded's player-level Validate
        // -> netty "Packet handling error" -> disconnect + freeze; crossing detection against
        // the wrong level would be its own hazard). Skip the WHOLE chain for the transient
        // frame — the first coherent frame re-runs it; respawn cleanup has already reset the
        // loader, so nothing stale accumulates across the skip. The server half (the pearl's
        // vanilla respawn-path teleport rerouted seamlessly) lives in MixinThrownEnderPearl;
        // this guard also protects every OTHER legitimate vanilla respawn (cross-dim death
        // respawn, server-initiated /tp on dedicated servers).
        if (mc.player == null || mc.player.level() != mc.level) {
            return;
        }
        // Note: use PARTIAL tick, not delta tick (IP MixinGameRenderer:85-86).
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        qouteall.imm_ptl.core.render.context_management.RenderStates.updatePreRenderInfo(partialTick);
        qouteall.imm_ptl.core.portal.animation.StableClientTimer.update(
            mc.level.getGameTime(), partialTick);
        // must update before teleportation (IP MixinGameRenderer:91)
        qouteall.imm_ptl.core.portal.animation.ClientPortalAnimationManagement.update();
        qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.manageTeleportation(false);
        qouteall.imm_ptl.core.IPGlobal.PRE_GAME_RENDER_EVENT.invoker().run();
        if (qouteall.imm_ptl.core.IPCGlobal.earlyRemoteUpload) {
            qouteall.imm_ptl.core.render.MyRenderHelper.earlyRemoteUpload();
        }
        // IP MixinGameRenderer:99 — frameIndex++ closes the pre-render handler (AFTER the pre-render
        // block, guarded by level!=null, exactly as IP). It rotates PortalRenderInfo.updateQuerySet's
        // occlusion-query buffers (PortalRenderInfo.java:155); without it every portal render takes the
        // synchronous fetchQueryResult stall path and the infoMap is never pruned.
        qouteall.imm_ptl.core.render.context_management.RenderStates.frameIndex++;

        // THIS FILE IS THE SOLE HOST of the IP pre-render chain (audit §G.2): each of
        // PRE_TOTAL_RENDER_TASK_LIST.processTasks, RenderStates.updatePreRenderInfo,
        // ClientPortalAnimationManagement.update, manageTeleportation(false), PRE_GAME_RENDER_EVENT,
        // MyRenderHelper.earlyRemoteUpload and RenderStates.frameIndex++ has exactly ONE call site
        // in the whole tree, and it is here. Do not delete this file as block-era collateral.
    }
}
