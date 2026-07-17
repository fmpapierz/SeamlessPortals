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
        if (MyGameRenderer.vanillaTerrainSetupOverride <= 0 && !IPGlobal.alwaysOverrideTerrainSetup) {
            return;
        }
        if (WorldRenderInfo.isRendering()) {
            return;
        }
        if ((Object) this != Minecraft.getInstance().levelExtractor) {
            return;
        }
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

        Profiler.get().push("ip_terrain_setup");
        VisibleSectionDiscovery.discoverVisibleSections(
            level, immPtlViewArea,
            Minecraft.getInstance().gameRenderer.mainCamera(),
            new Frustum(frustum).offsetToFullyIncludeCameraCube(8),
            ((IEWorldRenderer) levelRenderer).portal_getChunkInfoList()
        );
        Profiler.get().pop();
    }
}
