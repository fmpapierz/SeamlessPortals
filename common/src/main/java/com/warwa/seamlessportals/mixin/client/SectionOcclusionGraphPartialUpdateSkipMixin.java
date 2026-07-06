package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.PortalContextSwitch;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Future;

/**
 * Stops the portal-view (secondary renderer) occlusion-graph build from FREEZING
 * the render thread.
 *
 * <p>Root cause (verified, 2026-06-28): {@code SectionOcclusionGraph.runPartialUpdate}
 * runs a SYNCHRONOUS, UNBOUNDED breadth-first occlusion flood-fill on the render
 * thread ({@code runUpdates}, with a per-node distant-cull ray-march). A vanilla
 * main renderer never stalls on it because chunks stream in a few per frame, so each
 * frame propagates a tiny increment. The mod's HAND-BUILT secondary renderer instead
 * BULK-loads its whole destination (e.g. 289 chunks) before the first un-suppressed
 * {@code sog.update}, so {@code sectionsToPropagateFrom} holds the ENTIRE section set
 * and {@code runPartialUpdate} floods thousands of nodes in ONE call → multi-second
 * freeze the instant the native (warm-graph) path activates.
 *
 * <p>Fix: during a portal-view render ({@link PortalContextSwitch#isRenderingPortal})
 * skip the synchronous {@code runPartialUpdate}. The async full build —
 * {@code scheduleFullUpdate} (dispatched in {@code sog.update} immediately BEFORE
 * this call) — runs the same flood-fill on {@code Util.backgroundExecutor()} and
 * populates {@code currentGraph} off-thread, then flips {@code needsFrustumUpdate} so
 * {@code applyFrustum} repaints. So the dest graph still WARMS (for a flash-free
 * promote) without ever blocking the render thread. The MAIN render
 * ({@code isRenderingPortal == false}) and the background build are untouched.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphPartialUpdateSkipMixin {

    @Shadow private @Nullable Future<?> fullUpdateTask;
    @Shadow private boolean needsFullUpdate;

    /**
     * Post-promote flood-skip scoping (2026-07-06, the limbo-bands fix): the flood
     * hazard on the PROMOTED (main) SOG exists only until its FIRST post-promote
     * rebuild completes — that rebuild discards the demoted-era graph whose
     * propagation backlog is the multi-second flood. Subsequent rebuilds (vanilla's
     * 8-block walk invalidations) run against fresh graphs where runPartialUpdate is
     * cheap AND is the graph's only healing path (chunk-arrival releases + compile
     * propagation). The old blanket 30s window suppressed that healing for the whole
     * post-crossing walk: the view oscillated between the bridge flood and a
     * truncated rebuilt graph — the "terrain vanishes in rolling bands" bug.
     * Generation from PortalContextSwitch resets the latch at each promotion.
     */
    @org.spongepowered.asm.mixin.Unique
    private long seamlessportals$lastBridgeGen = -1L;
    @org.spongepowered.asm.mixin.Unique
    private boolean seamlessportals$firstRebuildDone = false;

    @Inject(method = "runPartialUpdate", at = @At("HEAD"), cancellable = true, require = 0)
    private void seamlessportals$skipSyncFloodDuringPortalView(
            CameraRenderState camera, LongSet loadedExpectedChunks, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;

        long gen = PortalContextSwitch.getPromoteBridgeGeneration();
        if (gen != this.seamlessportals$lastBridgeGen) {
            this.seamlessportals$lastBridgeGen = gen;
            this.seamlessportals$firstRebuildDone = false;
        }
        // needsFullUpdate==true here proves update():150 saw an IN-FLIGHT task (a
        // done task would have been consumed by scheduleFullUpdate, which clears the
        // flag, before runPartialUpdate) — treating it as in-flight closes the
        // microsecond race where the async task completes between update()'s isDone
        // read and ours, which would otherwise latch firstRebuildDone off the
        // PREVIOUS era's rebuild and drain the demote-era queue un-gated.
        // NOTE (useContinuousExtract=true, future Phase-3 path): this latch relies on
        // promoteToMain's unconditional sog.invalidate() under the current
        // useContinuousExtract=false — if that flag is ever flipped, pair the
        // promotion with an explicit invalidate or the first-rebuild gate is defeated.
        boolean taskInFlight = this.needsFullUpdate
            || this.fullUpdateTask == null || !this.fullUpdateTask.isDone();
        if (!taskInFlight) {
            // Observed a completed rebuild since this promotion: the hazardous
            // demoted-era propagation backlog is gone; partial updates are cheap
            // incremental healing from here on.
            this.seamlessportals$firstRebuildDone = true;
        }

        // Skip the synchronous flood during a portal-view render (the secondary's
        // bulk-loaded graph) and, on the promoted main SOG, ONLY until its first
        // post-promote rebuild lands. The async scheduleFullUpdate still builds
        // the graph off-thread; the bridge paints terrain via VisibleSectionDiscovery
        // meanwhile.
        if ((PortalContextSwitch.isRenderingPortal
                || (PortalContextSwitch.isPromoteBridgeActive()
                    && !this.seamlessportals$firstRebuildDone))
                && taskInFlight) {
            ci.cancel();
        }
    }
}
