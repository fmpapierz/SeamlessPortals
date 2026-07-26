package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRendererMixin — the per-frame END-OF-FRAME driver for the mod's render substrate.
 *
 * <p><b>S20:</b> the block-era HEAD inject (camera-transition ticking, whose only body was the
 * flag-OFF-only {@code CameraTransitionHandler.tick()}) is gone with the block era, together with
 * three UNGATED block-era calls that used to lead this TAIL
 * ({@code PortalWorldManager.lateUpdateSecondaryLight()},
 * {@code PortalWorldManager.endSecondaryRenderFrames()},
 * {@code PortalRenderBuffersPool.endFramePooled()}). All three were verified flag-ON NO-OPS before
 * removal, so this is behaviour-identical on the shipping path — see port-note
 * S20-block-era-deletion.md §G.5: the two {@code PortalWorldManager} entries were no-ops only
 * because {@code LevelRenderer.renderBuffers} IS {@code gameRenderer.renderBuffers()} (javap:
 * {@code LevelRenderer.<init>} offset 152) so identity-dedup catches them, and
 * {@code PortalRenderBuffersPool}'s pool was never populated flag-ON (its only feeder was
 * {@code release()} from the deleted {@code PortalContextSwitch:662}).
 *
 * <p><b>THIS FILE IS LOAD-BEARING — DO NOT DELETE IT AS BLOCK-ERA COLLATERAL.</b> It used to
 * reference five block-era classes, so a symbol-driven sweep reads it as block era; the S20
 * adversarial audit refuted that (§G.2). It is the SOLE per-frame caller in the entire tree of the
 * four ported-IP frame-end entry points below, and deleting it is a silent GPU-buffer leak rather
 * than a compile error.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    /**
     * The missing 1:1 counterpart of vanilla's {@code this.renderBuffers.endFrame()}
     * (GameRenderer.render:447, which covers ONLY the GameRenderer's own buffers) for
     * every RenderBuffers the mod created. Without it their StagedVertexBuffer pools never
     * fence-recycle — a GPU-buffer leak of tens of MB/s while a portal is in view, ending in
     * multi-second driver paging stalls inside arbitrary GL calls (the [SEAMLESS
     * STUCK]/[SEAMLESS FREEZE] nglDrawElementsInstancedBaseVertex signatures,
     * 2026-07-05). TAIL = right after vanilla's own endFrame, the same lifecycle point.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void seamlessportals$endSecondaryFrames(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        long t0 = System.nanoTime();
        // Drain IP's own secondary RenderBuffers pool (MyGameRenderer.secondaryRenderBuffers,
        // cycled by switchAndRenderTheWorld when IPGlobal.useSecondaryEntityVertexConsumer=true).
        // ADDITIVE 26.2-required per carriage flag B6 (MyGameRenderer.endFramePooled's javadoc
        // mandates wiring "from GameRenderer.render TAIL at S12/S13"); without it every portal
        // frame leaks GPU buffers (memory gpu-buffer-leak-endframe). S20: the enclosing
        // `if (isEntityPortals())` gate collapsed to unconditional with the flag's death.
        qouteall.imm_ptl.core.render.MyGameRenderer.endFramePooled();
        // S14.30: drain the frame-transient UBO ledger (deferred close of the per-pass fog/projection
        // GpuBuffers — vanilla's DynamicUniformStorage.endFrame discipline).
        qouteall.imm_ptl.core.render.SecondaryWorldRenderCore.closeFrameTransientUbos();
        // S14.31: dump the one-frame draw trace (single log write; no-op unless a capture ran).
        qouteall.imm_ptl.core.render.DrawCallTrace.onFrameEnd();
        // S14.45: teleport-flash/stutter ring row (always-on, in-memory only; one batched log
        // write when a lever-armed capture window closes).
        qouteall.imm_ptl.core.render.TeleportFlashProbe.onFrameEnd();
        com.warwa.seamlessportals.render.PerfTimers.add("endSecondaryFrames", System.nanoTime() - t0);
    }
}
