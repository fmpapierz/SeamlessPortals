package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.PortalContextSwitch;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skip vanilla debug-gizmo emission during the PORTAL-VIEW extract.
 *
 * <p>Fixes the intermittent "floating camera diagnostic" artifact: every
 * {@code LevelExtractor.extract} unconditionally calls {@code debugRenderer.emitGizmos}
 * (LevelExtractor.java:207), and {@code ChunkCullingDebugRenderer} draws a rainbow
 * frustum-quad + wireframe visualization whenever
 * {@code minecraft.gameRenderer.mainCamera().getCapturedFrustum() != null} — with NO
 * debug-screen gate (ChunkCullingDebugRenderer.java:90-113). During the portal render,
 * {@code withSwitchedWorld} swaps {@code mainCamera} to the VIRTUAL camera, which carries a
 * captured frustum (the load-bearing trick that makes the dest extract skip
 * applyFrustum/sog.update). So the dest extract emitted the portal camera's frustum
 * visualization — a ghostly camera-shaped artifact that mirrors the player's movement
 * (unreachable, view-tracking, intermittent).
 *
 * <p>Cancelling the aggregate {@code DebugRenderer.emitGizmos} while
 * {@link PortalContextSwitch#isRenderingPortal} also keeps every other vanilla debug
 * overlay (chunk borders, bee/brain debug, …) out of the portal view — they are main-view
 * diagnostics that read main-camera/main-level state and would render at wrong coordinates
 * mid-swap. The MAIN extract is unaffected (isRenderingPortal is false there), so F3 debug
 * features work normally in the main view.
 */
@Mixin(DebugRenderer.class)
public abstract class DebugRendererPortalSkipMixin {

    @Inject(method = "emitGizmos", at = @At("HEAD"), cancellable = true, require = 0)
    private void seamlessportals$skipDuringPortalExtract(CallbackInfo ci) {
        if (PortalContextSwitch.isRenderingPortal) {
            ci.cancel();
        }
    }
}
