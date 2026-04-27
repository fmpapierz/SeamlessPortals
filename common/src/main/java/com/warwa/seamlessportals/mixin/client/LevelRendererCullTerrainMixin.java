package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.client.PortalWorldManager;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Future;

/**
 * Blanks-on-teleport fix.
 *
 * <p>Root cause: {@code SectionOcclusionGraph.update} schedules the full
 * graph rebuild on {@code Util.backgroundExecutor()} and returns immediately.
 * For the very first {@code renderLevel} call after
 * {@link PortalWorldManager#promoteToMain}, the task hasn't completed yet, so
 * {@code consumeFrustumUpdate} returns {@code false},
 * {@code applyFrustum}/{@code addSectionsInFrustum} never runs, and
 * {@code visibleSections} stays empty. That produces the 1-2 blank-terrain
 * frames users see on portal crossing.
 *
 * <p>Fix: after the first post-promote {@code update} call schedules a new
 * task, block the render thread on that task's {@code Future.get()} before
 * returning from {@code cullTerrain}. The task body populates
 * {@code currentGraph} and flips {@code needsFrustumUpdate = true}, so when
 * vanilla cullTerrain continues on to the
 * {@code consumeFrustumUpdate || camRot changed} branch, it runs
 * {@code applyFrustum} → {@code addSectionsInFrustum}, filling
 * {@code visibleSections} in time for the frame's draw.
 *
 * <p>Scope: only the one frame flagged by
 * {@link PortalWorldManager#consumePendingPrime}. All subsequent frames keep
 * vanilla's async behavior — no sustained perf cost.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCullTerrainMixin {

    @Inject(
        method = "cullTerrain(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;update(ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Ljava/util/List;Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;)V",
            shift = At.Shift.AFTER
        ),
        require = 1
    )
    private void seamlessportals$blockSyncOnFirstPostPromote(
            Camera camera, Frustum frustum, boolean spectator, CallbackInfo ci) {
        LevelRenderer self = (LevelRenderer) (Object) this;
        if (!PortalWorldManager.consumePendingPrime(self)) {
            return;
        }
        SectionOcclusionGraph sog = self.getSectionOcclusionGraph();
        Future<?> task =
            ((SectionOcclusionGraphAccessorMixin) (Object) sog)
                .seamlessportals$getFullUpdateTask();
        if (task == null) {
            // Graph already built from a previous use of this renderer, or
            // update() decided no rebuild was needed — either way there's
            // nothing to wait on.
            return;
        }
        long startNs = System.nanoTime();
        // Cap the wait to a 1-frame budget (~8 ms at 60 fps). Empirically
        // the unbounded block was 23 ms (nether RD~16) up to 73 ms (OW
        // RD~16) — visible as a multi-frame stutter exactly at the moment
        // of teleport, which is the user's main complaint about teleport
        // lag. Trade-off: if the SOG full-update task doesn't finish in
        // 8 ms, we fall through and render this frame with stale
        // visibleSections (mostly portal-view-direction sections frustum-
        // culled at draw time, producing 1 mostly-blank frame). Next
        // frame's cullTerrain will see {@code consumeFrustumUpdate}
        // return true and call {@code applyFrustum} which repopulates
        // visibleSections from the now-completed {@code currentGraph}.
        // 1 stale frame ≪ 5-frame stutter.
        try {
            task.get(8, java.util.concurrent.TimeUnit.MILLISECONDS);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PHASE2] SOG sync prime: blocked {}ms on full-update task (within budget)",
                elapsedMs);
        } catch (java.util.concurrent.TimeoutException te) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PHASE2] SOG sync prime: bailed at {}ms (budget exceeded; rendering with stale visibleSections this frame)",
                elapsedMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS PHASE2] SOG sync prime interrupted", ie);
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS PHASE2] SOG sync prime failed", e);
        }
    }
}
