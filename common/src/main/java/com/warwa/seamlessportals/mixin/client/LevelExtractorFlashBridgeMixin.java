package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.PortalContextSwitch;
import com.warwa.seamlessportals.render.VisibleSectionDiscovery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 5 — flash-free teleport bridge.
 *
 * <p>On portal promote the entered dimension's renderer is swapped in with an
 * empty/cold {@link net.minecraft.client.renderer.SectionOcclusionGraph}, so the
 * first main-render {@code applyFrustum} fills {@code visibleSections} from a graph
 * that hasn't been built for the new camera yet → blank/sky FLASH until the engine
 * finishes its async rebuild ~a second later.
 *
 * <p>During the brief post-promote window ({@link PortalContextSwitch#isPromoteBridgeActive})
 * this substitutes the bounded {@link VisibleSectionDiscovery} flood-fill for the
 * cold SOG, so terrain paints IMMEDIATELY for the new view. The flood-fill is
 * render-distance-bounded and frustum-culled (no cave culling, but that only
 * matters for ~1.5s), and crucially has NONE of {@code sog.update}'s unbounded
 * synchronous propagation — so it can never freeze. Once the window expires, the
 * now-warm engine graph takes back over with full occlusion culling.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorFlashBridgeMixin {

    @Shadow @Final private LevelRenderer levelRenderer;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "applyFrustum", at = @At("HEAD"), cancellable = true, require = 0)
    private void seamlessportals$flashBridge(Frustum frustum, CallbackInfo ci) {
        // FBO portal-view render: doFboRender's bounded flood-fill
        // (VisibleSectionDiscovery) already fills this secondary renderer's
        // visibleSections every frame, so SKIP the vanilla SOG frustum walk
        // (applyFrustum → addSectionsInFrustum). That walk is an occlusion-tree
        // traversal over the BULK-LOADED secondary world and is the lit-portal
        // stutter: it runs whenever the virtual camera rotates / the graph signals
        // a frustum update, freezing the render thread 100–766ms (confirmed by two
        // thread dumps frozen in Frustum.offsetToFullyIncludeCameraCube ←
        // SectionOcclusionGraph.addSectionsInFrustum ← LevelExtractor.applyFrustum ←
        // doFboRender). The flood-fill is frustum-only (no occlusion culling) —
        // exactly IP's portal-view model — and never touches the graph, so the
        // visibleSections it set survive untouched. Cancelling at HEAD also avoids
        // applyFrustum clearing them.
        if (PortalContextSwitch.isRenderingPortal) {
            ci.cancel();
            return;
        }
        if (!PortalContextSwitch.isPromoteBridgeActive()) {
            return; // window fully expired → engine occlusion graph drives the cull
        }
        // Past the always-bridge floor, keep bridging ONLY until this renderer's SOG
        // full rebuild has completed — so a big demoted dim (overworld) never falls
        // back to a still-building graph (the "blank a second later"). The floor
        // window covers the first frames where the fresh rebuild hasn't been
        // scheduled yet.
        if (!PortalContextSwitch.isPromoteBridgeMinActive()) {
            java.util.concurrent.Future<?> task =
                ((SectionOcclusionGraphAccessorMixin) (Object) this.levelRenderer.sectionOcclusionGraph())
                    .seamlessportals$getFullUpdateTask();
            if (task != null && task.isDone()) {
                return; // rebuild complete → hand back to the warm engine graph
            }
        }
        ViewArea viewArea = ((LevelRendererAccessorMixin) this.levelRenderer)
            .seamlessportals$getViewArea();
        if (viewArea == null) {
            return; // no grid yet — fall through to vanilla
        }
        Vec3 cameraPos = this.levelRenderState.cameraRenderState.pos;
        if (cameraPos == null) {
            return;
        }
        this.levelRenderer.clearVisibleSections();
        int viewDistance = this.minecraft.options.getEffectiveRenderDistance();
        VisibleSectionDiscovery.discoverVisibleSections(
            viewArea, cameraPos, frustum, viewDistance, this.levelRenderer.visibleSections());
        ci.cancel();
    }

    // TEMP DIAGNOSTIC: allChanged() is the ONLY setter of shouldInvalidateCompiledGeometry,
    // whose consumption in extract() rebuilds the whole ViewArea (the watchdog-confirmed
    // 126-766ms stall, ~64x/run). Log every caller chain so we can see which path fires
    // it ~2/sec. Remove once root-caused.
    @Inject(method = "allChanged", at = @At("HEAD"), require = 0)
    private void seamlessportals$diagAllChanged(CallbackInfo ci) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder("[SEAMLESS ALLCHANGED]");
        int n = 0;
        for (StackTraceElement e : st) {
            String s = e.toString();
            if (s.contains("getStackTrace") || s.contains("diagAllChanged")) {
                continue;
            }
            sb.append("\n  <- ").append(s);
            if (++n >= 7) {
                break;
            }
        }
        com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.warn(sb.toString());
    }
}
