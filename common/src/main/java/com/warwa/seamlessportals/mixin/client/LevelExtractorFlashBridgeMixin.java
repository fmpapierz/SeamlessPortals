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
    @Shadow private net.minecraft.client.multiplayer.ClientLevel level;

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
            viewArea, cameraPos, frustum, viewDistance, this.level,
            this.levelRenderer.visibleSections());
        ci.cancel();
    }

    /**
     * Self-healing handback guard — fixes "nether terrain is there, then it disappears a few
     * seconds after teleporting in." The HEAD bridge hands back to the engine occlusion graph
     * the instant its full-rebuild task {@code isDone()}, but for a just-promoted, still-
     * streaming dimension that rebuilt graph routinely yields EMPTY {@code visibleSections}
     * (the BFS seeds from the player section and stops at the not-yet-loaded boundary), so the
     * main view BLANKS. Here, on every {@code applyFrustum} RETURN within the post-promote
     * window, if the engine cull produced NOTHING, flood-fill instead — so the view never
     * blanks mid-bridge regardless of why the graph came back empty. Runs only when empty +
     * within the (≤30s) bridge window; normal gameplay is untouched. Safe re: the historical
     * applyFrustum freeze — that is the SOG WALK (skipped at HEAD); this fires after it.
     */
    @Inject(method = "applyFrustum", at = @At("RETURN"), require = 0)
    private void seamlessportals$flashBridgeHandbackGuard(Frustum frustum, CallbackInfo ci) {
        if (PortalContextSwitch.isRenderingPortal) return;          // portal render: HEAD handled it
        if (!PortalContextSwitch.isPromoteBridgeActive()) return;   // window expired → trust the engine
        if (!this.levelRenderer.visibleSections().isEmpty()) return; // engine painted terrain → fine
        ViewArea viewArea = ((LevelRendererAccessorMixin) this.levelRenderer)
            .seamlessportals$getViewArea();
        if (viewArea == null) return;
        Vec3 cameraPos = this.levelRenderState.cameraRenderState.pos;
        if (cameraPos == null) return;
        int viewDistance = this.minecraft.options.getEffectiveRenderDistance();
        VisibleSectionDiscovery.discoverVisibleSections(
            viewArea, cameraPos, frustum, viewDistance, this.level,
            this.levelRenderer.visibleSections());
        // DIAG: how often the engine handed back an EMPTY graph and we rescued it (count column).
        com.warwa.seamlessportals.render.PerfTimers.add("promoteEmptyRescue", 0L);
    }
}
