package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.PortalContextSwitch;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    @Inject(method = "runPartialUpdate", at = @At("HEAD"), cancellable = true, require = 0)
    private void seamlessportals$skipSyncFloodDuringPortalView(
            CameraRenderState camera, LongSet loadedExpectedChunks, CallbackInfo ci) {
        if (PortalContextSwitch.isRenderingPortal
                && Minecraft.getInstance().isSameThread()) {
            ci.cancel();
        }
    }
}
