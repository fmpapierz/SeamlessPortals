package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.CameraTransitionHandler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRendererMixin - camera transition ticking (HEAD) + secondary render-frame
 * end (TAIL). Portal rendering is now done via Fabric's LevelRenderEvents API.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void seamlessportals$beforeRender(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        // D3 EXCLUSIVITY GATE (row 17 — CameraTransitionHandler). Flag ON → IP's
        // TransformationManager.managePlayerRotationAndChangeGravity supersedes it; this block-era
        // camera-transition driver stays off. Flag OFF (default) → unchanged. (The TAIL half below is
        // §3 substrate — GPU-buffer endFrame + secondary light — and stays active in BOTH states.)
        if (com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) return;
        CameraTransitionHandler.tick();
    }

    /**
     * The missing 1:1 counterpart of vanilla's {@code this.renderBuffers.endFrame()}
     * (GameRenderer.render:447, which covers ONLY the GameRenderer's own buffers) for
     * every RenderBuffers the mod created: promoted/secondary renderers + the portal
     * sub-render pool. Without it their StagedVertexBuffer pools never fence-recycle —
     * a GPU-buffer leak of tens of MB/s while a portal is in view, ending in
     * multi-second driver paging stalls inside arbitrary GL calls (the [SEAMLESS
     * STUCK]/[SEAMLESS FREEZE] nglDrawElementsInstancedBaseVertex signatures,
     * 2026-07-05). TAIL = right after vanilla's own endFrame, the same lifecycle point.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void seamlessportals$endSecondaryFrames(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        long t0 = System.nanoTime();
        // IP MyRenderHelper.lateUpdateLight: run each live secondary's light
        // engine at frame-render END so the nether portal view's block light
        // (queued by pollLightUpdates in the client tick) is actually published
        // and the sections re-mesh next frame — instead of staying dark until a
        // crossing. Render-end (not mid-tick) per IP to avoid section-edge
        // smooth-lighting artifacts.
        com.warwa.seamlessportals.client.PortalWorldManager.lateUpdateSecondaryLight();
        com.warwa.seamlessportals.client.PortalWorldManager.endSecondaryRenderFrames();
        com.warwa.seamlessportals.render.PortalRenderBuffersPool.endFramePooled();
        // Flag-ON counterpart: drain IP's own secondary RenderBuffers pool
        // (MyGameRenderer.secondaryRenderBuffers, cycled by switchAndRenderTheWorld when
        // IPGlobal.useSecondaryEntityVertexConsumer=true). The block-era PortalRenderBuffersPool drain above
        // does NOT touch this pool. ADDITIVE 26.2-required per carriage flag B6 (MyGameRenderer
        // .endFramePooled javadoc mandates wiring "from GameRenderer.render TAIL at S12/S13"); without it
        // every portal frame leaks GPU buffers (memory gpu-buffer-leak-endframe). Flag-gated: the IP pool is
        // idle flag-OFF (switchAndRenderTheWorld never runs), so flag-OFF this is byte-inert.
        if (com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) {
            qouteall.imm_ptl.core.render.MyGameRenderer.endFramePooled();
        }
        // S14.30: drain the frame-transient UBO ledger (deferred close of the per-pass fog/projection
        // GpuBuffers — vanilla's DynamicUniformStorage.endFrame discipline). UNCONDITIONAL, not
        // inside the flag gate, so a mid-session flag flip cannot strand pending buffers; byte-inert
        // flag-OFF (the list is only ever fed by flag-ON code paths — empty loop).
        qouteall.imm_ptl.core.render.SecondaryWorldRenderCore.closeFrameTransientUbos();
        // S14.31: dump the one-frame draw trace (single log write; no-op unless a capture ran).
        qouteall.imm_ptl.core.render.DrawCallTrace.onFrameEnd();
        // S14.45: teleport-flash/stutter ring row (always-on, in-memory only; one batched log
        // write when a promote-armed or lever-armed capture window closes).
        qouteall.imm_ptl.core.render.TeleportFlashProbe.onFrameEnd();
        // §2b dest-entity funnel probe (1Hz; byte-inert without -Dseamlessportals.entityProbe).
        qouteall.imm_ptl.core.render.EntityVisibilityProbe.onFrameEnd();
        // IS5-CEN: the composite bind census's FRAME BOUNDARY. This is the only anchor in the mod that
        // fires exactly once per rendered frame unconditionally — RenderStates.frameIndex is NOT
        // usable for it, because MinecraftFramePumpMixin deliberately SKIPS the increment on
        // mid-packet player/level-mismatch frames, which would silently merge two frames into one
        // census row and corrupt the per-frame bind COUNT that is the whole point of the measurement.
        // Byte-inert without -Dseamlessportals.compositeCensus.
        com.warwa.seamlessportals.render.IrisCompositeCensus.onFrameEnd();
        // IS5-MB: promote this frame's per-slot composite cameras to "last frame". Same anchor and same
        // reasoning as the census boundary above — RenderStates.frameIndex is skipped on mid-packet
        // mismatch frames, and a merged frame here would hand a slot the camera from two frames ago.
        qouteall.imm_ptl.core.compat.iris_compatibility.IrisDestPrevCamera.onFrameEnd();
        // IS5-RC: run self-identification watchdog. Always on, once per session — if no portal has
        // been rendered by then it emits the config block anyway, so a run that measured nothing still
        // says so IN THE LOG rather than looking deceptively healthy.
        com.warwa.seamlessportals.render.RunConfigReport.tickFrame();
        com.warwa.seamlessportals.render.PerfTimers.add("endSecondaryFrames", System.nanoTime() - t0);
    }
}
