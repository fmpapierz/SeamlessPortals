package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * C2-0 probe P5 (trigger side) — {@code migration/C2_DESIGN.md} §4 P5, stage C2-0 deliverable 4(iv).
 *
 * <p>Instruments {@code ChunkRenderList.reset(int frame)} at HEAD. In Sodium 0.9.1 this is the
 * per-region list reset that {@code VisibleChunkCollector} fires when
 * {@code getLastVisibleFrame() != current frame} ({@code C2_091_MAP.md} #3) — the trigger the IP
 * per-portal-layer list fix (doc file #3) exists to neutralise. This probe measures the BASELINE
 * reset frequency + distinct frame cadence so the C2-1 round can re-check whether the recursive-
 * portal frame increment still forces a mid-frame reset UNDER the D1 swap design (a swapped frame
 * counter may neutralise the trigger, downgrading file #3 to DEFER-DORMANT). In C2-0 portals are
 * off, so {@code portalsRenderedThisFrame} and {@code isRendering()} sample as 0 / false — this run
 * captures the no-portal baseline only.
 *
 * <p>Gated behind {@code -Dseamlessportals.compatProbe=true}, {@code require = 0},
 * {@code remap = false} (a mod-class target). LOG-ONLY. The reset fires many times per frame on the
 * render thread, so per-call work is a single counter bump; a summary line is emitted at most once
 * per second (the ~130 ms log4j-stall rule). The class name carries {@code Sodium} so gate 1 weaves
 * it only when Sodium is present.
 */
@Mixin(value = ChunkRenderList.class, remap = false)
public abstract class MixinSodiumChunkRenderList_Probe {

    private static final boolean seamlessportals$probe = Boolean.getBoolean("seamlessportals.compatProbe");

    private static final long seamlessportals$sampleIntervalNanos = 1_000_000_000L; // 1 Hz

    private static long seamlessportals$windowStartNanos = 0L;
    private static long seamlessportals$resetCallsInWindow = 0L;
    private static long seamlessportals$frameTransitionsInWindow = 0L;
    private static int seamlessportals$lastFrameSeen = Integer.MIN_VALUE;

    @Inject(method = "reset(I)V", at = @At("HEAD"), require = 0, remap = false)
    private void seamlessportals$probeReset(int frame, CallbackInfo ci) {
        if (!seamlessportals$probe) {
            return;
        }
        // Cheap per-call accumulation only. NOTE: counts frame-value TRANSITIONS (A->B->A = 2),
        // not distinct values — with portals on screen, transitions exceeding frame advances IS
        // the mid-frame interleaved-reset signal P5 hunts (verify lens 1, C2-0).
        seamlessportals$resetCallsInWindow++;
        if (frame != seamlessportals$lastFrameSeen) {
            seamlessportals$lastFrameSeen = frame;
            seamlessportals$frameTransitionsInWindow++;
        }
        long now = System.nanoTime();
        if (seamlessportals$windowStartNanos == 0L) {
            seamlessportals$windowStartNanos = now;
            return;
        }
        if (now - seamlessportals$windowStartNanos < seamlessportals$sampleIntervalNanos) {
            return;
        }
        // 1 Hz summary flush.
        long resets = seamlessportals$resetCallsInWindow;
        long frameTransitions = seamlessportals$frameTransitionsInWindow;
        seamlessportals$resetCallsInWindow = 0L;
        seamlessportals$frameTransitionsInWindow = 0L;
        seamlessportals$windowStartNanos = now;
        com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
            "[COMPAT PROBE P5] ChunkRenderList.reset/s={} frameTransitions/s={} lastFrame={} "
                + "portalsRenderedThisFrame={} portalRendering={}",
            resets, frameTransitions, frame,
            RenderStates.portalsRenderedThisFrame, PortalRendering.isRendering());
    }
}
