package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.util.profiling.Profiler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;
import qouteall.imm_ptl.core.render.ImmPtlViewArea;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.VisibleSectionDiscovery;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * S14.7 (fix-verify MAJOR): the re-sited consumer of {@link MyGameRenderer#vanillaTerrainSetupOverride}.
 * IP 1.21.3 anchored it at {@code LevelRenderer.setupRender} RETURN (IP MixinLevelRenderer
 * {@code onSetupTerrainEnd:271-306}): on the first frame(s) after a teleport, vanilla's
 * multithreaded visibility discovery yields nothing, so IP synchronously refilled
 * {@code visibleSections} with its own non-multithreaded {@link VisibleSectionDiscovery}. On 26.2
 * {@code setupRender} is GONE and the flag had become WRITE-ONLY (both teleport paths set it; no
 * consumer) — the first crossing into a dim whose SOG graph is cold would show a blank-terrain
 * flash IP did not have. The 26.2 anchor is {@code LevelExtractor.applyFrustum} RETURN: it runs
 * inside {@code extract()} BEFORE the {@code visibleSections} consumption loop (mc262
 * LevelExtractor.java:130 vs :152), so the refilled list feeds BOTH the compile queue and the
 * draw, exactly like IP's mid-setup anchor; the crossing promote forces
 * {@code needsFrustumUpdate}, so the first post-promote frame that can consume the override is
 * guaranteed to reach applyFrustum. The frustum here is vanilla's CONVENTIONAL-Z cull frustum
 * (Camera.createProjectionMatrixForCulling), so {@code offsetToFullyIncludeCameraCube} is safe
 * (the I7 reversed-Z hang needs the render projection, never fed here).
 *
 * <p>Gates mirror IP: main pass only ({@code !WorldRenderInfo.isRendering()} — the portal pass
 * has its own explicit discovery in the driver core), the MAIN extractor only (the dest pass
 * never reaches applyFrustum — captured frustum — but reload cascades can run secondaries'
 * allChanged; identity-guard regardless), and IP's {@code ip_allowOverrideTerrainSetup}
 * (no Sodium, not the Iris shadow pass). {@code IPGlobal.alwaysOverrideTerrainSetup} is IP's
 * debug switch, preserved.
 */
@Mixin(LevelExtractor.class)
public abstract class MixinLevelExtractor_TerrainSetupOverride {

    @Shadow
    @Final
    private LevelRenderer levelRenderer;

    @Shadow
    private ClientLevel level;

    @Inject(method = "applyFrustum", at = @At("RETURN"))
    private void portal_onApplyFrustumReturn(Frustum frustum, CallbackInfo ci) {
        // S14.42: probe counter — every MAIN-extractor applyFrustum since the last promote
        // (counted before the override gates; the probe needs the raw firing count).
        if ((Object) this == Minecraft.getInstance().levelExtractor) {
            qouteall.imm_ptl.core.render.RenderChainProbe.applyFrustumCount++;
        }
        if (MyGameRenderer.vanillaTerrainSetupOverride <= 0 && !IPGlobal.alwaysOverrideTerrainSetup) {
            return;
        }
        if (WorldRenderInfo.isRendering()) {
            return;
        }
        if ((Object) this != Minecraft.getInstance().levelExtractor) {
            return;
        }
        // C2-1b sodium evidence: under Sodium this whole injection is STRUCTURALLY DEAD —
        // applyFrustum's single call site (LevelExtractor.extract:130) is @Redirect-ed to an
        // empty no-op by sodium's LevelExtractorMixin.sodium$cancel (javap 0.9.1), so the
        // method never runs. The isSodiumPresent() yield below (IP's ip_allowOverrideTerrainSetup
        // precedent) and the instanceof-ImmPtlViewArea guard are the second and third walls
        // (they also cover the D11 feed-only state, where the invoker reports absent).
        if (SodiumInterface.invoker.isSodiumPresent()
            || IrisInterface.invoker.isRenderingShadowMap()
        ) {
            return;
        }
        if (level == null) {
            return;
        }
        ViewArea viewArea = ((IEWorldRenderer) levelRenderer).ip_getBuiltChunkStorage();
        if (!(viewArea instanceof ImmPtlViewArea immPtlViewArea)) {
            return;
        }

        if (MyGameRenderer.vanillaTerrainSetupOverride > 0) {
            MyGameRenderer.vanillaTerrainSetupOverride--;
        }

        // S14.48 (the zero-lag hunt's named term): vanilla's applyFrustum body ran just above and
        // filled visibleSections by walking the SOG currentGraph. On a WARM promote that graph is
        // the dim's previous occlusion tree (invalidate() only schedules the ASYNC rebuild — the
        // old graph keeps serving), BFS'd from ~this same camera position on rapid crossings, so
        // the vanilla fill is already a small, correctly-occluded set. IP's discovery below is a
        // frustum flood WITHOUT occlusion — capture-proven to hand the promote frame a 23k-33k
        // section list (visSec on every PROMOTE row) whose extract+draw is the dominant term of
        // the 15-35ms crossing hitch. The override exists for the COLD case (a never-BFS'd or
        // just-reset graph yields ~nothing -> IP's blank-first-frame bug): so only REPLACE the
        // vanilla fill when it is actually blank-ish. The one-shot is consumed either way; the
        // debug switch keeps the unconditional-override behavior. vy= in the flash-probe row
        // records the vanilla yield + branch for the confirming capture.
        int vanillaYield = ((IEWorldRenderer) levelRenderer).portal_getChunkInfoList().size();
        qouteall.imm_ptl.core.render.TeleportFlashProbe.vanillaYieldThisFrame = vanillaYield;
        // S14.48 verify BLOCKER fold (wf_33dda3b2-9f5): yield alone is NOT enough — a warm tree
        // BFS'd from a FAR-AWAY last-main-stint camera (return via a DIFFERENT portal, classic
        // two-portal geometry) can still push >32 stale in-frustum sections while MISSING the
        // arrival's near field (occluded from the old origin, e.g. the enclosed arrival room) →
        // 1-3 frames of holes. The SOG's prevCam fields ARE the last BFS origin (8-block cells,
        // updated only by main-stint invalidateIfNeeded). CAUTION (re-verify wf_0abb365a-666):
        // Double.MIN_VALUE — the never-updated sentinel — is the smallest POSITIVE double
        // (~0.0), NOT a far value; near the world origin it can spuriously read as "near". That
        // cannot reach a wrong branch TODAY only because a never-updated graph has an EMPTY
        // octree and can't yield >32 (the yield check is the sole cold guard) — do NOT weaken
        // the yield check without adding a real sentinel test. Keep vanilla's fill only when
        // the tree was built from ~here (≤2 cells ≈ 16-24 blocks — the same-portal flow); any
        // borderline case falls back to the discovery flood = the safe direction.
        boolean originNear = false;
        var sog = levelRenderer.sectionOcclusionGraph();
        if (sog != null) {
            var sogAcc = (com.warwa.seamlessportals.mixin.client.SectionOcclusionGraphAccessorMixin)
                (Object) sog;
            net.minecraft.world.phys.Vec3 camPos =
                Minecraft.getInstance().gameRenderer.mainCamera().position();
            originNear =
                Math.abs(Math.floor(camPos.x / 8.0) - sogAcc.seamlessportals$getPrevCamX()) <= 2
                && Math.abs(Math.floor(camPos.y / 8.0) - sogAcc.seamlessportals$getPrevCamY()) <= 2
                && Math.abs(Math.floor(camPos.z / 8.0) - sogAcc.seamlessportals$getPrevCamZ()) <= 2;
        }
        if (vanillaYield > 32 && originNear && !IPGlobal.alwaysOverrideTerrainSetup) {
            return;
        }

        Profiler.get().push("ip_terrain_setup");
        // S14.47 zero-lag hunt: the synchronous override discovery's wall time (dMs= in the
        // flash-probe row) — a promote-frame phase-cost suspect.
        long discoveryT0 = System.nanoTime();
        VisibleSectionDiscovery.discoverVisibleSections(
            level, immPtlViewArea,
            Minecraft.getInstance().gameRenderer.mainCamera(),
            new Frustum(frustum).offsetToFullyIncludeCameraCube(8),
            ((IEWorldRenderer) levelRenderer).portal_getChunkInfoList()
        );
        qouteall.imm_ptl.core.render.TeleportFlashProbe.discoveryNanosThisFrame +=
            System.nanoTime() - discoveryT0;
        Profiler.get().pop();
    }
}
