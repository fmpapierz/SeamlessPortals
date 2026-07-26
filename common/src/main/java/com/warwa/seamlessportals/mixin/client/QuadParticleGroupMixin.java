package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cull source-dim particles whose camera-ray passes through an active
 * portal quad — they would otherwise overdraw the dest-dim composite
 * inside the portal opening on screen.
 *
 * <p>Why this exists: in MC 26.1.2 particles are drawn via blaze3d
 * render pipelines that re-bind their own GL state, so raw
 * {@code glStencilFunc} calls before the draw don't survive into the
 * draw itself. Setting stencil func at the end of
 * {@code compositePortalFbo} also breaks because the state leaks
 * across frames and corrupts the next frame's portal stencil pre-pass
 * (verified empirically — caused "wrong source dim showing through"
 * + portal-view stuck on stale framebuffer).
 *
 * <p>So we cull particles geometrically instead of relying on stencil:
 * any particle whose 3D position lies "behind" an active portal from
 * the camera's view is dropped before it's added to the
 * {@link ParticleGroupRenderState} for submission. The geometry test
 * is the same line-vs-quad intersection
 * ({@link com.warwa.seamlessportals.portal.PortalInfo#intersectsMovement})
 * already used by teleport detection — proven correct.
 *
 * <p>Hook strategy: vanilla
 * {@link QuadParticleGroup#extractRenderState(Frustum, Camera, float)}
 * already calls {@code frustum.pointInFrustum(particle.x, particle.y, particle.z)}
 * to skip off-screen particles. We capture the {@code Camera} at HEAD
 * (so the redirect can use it without changing method signatures), then
 * redirect that {@code pointInFrustum} call: if vanilla says CULL we
 * return false; if vanilla says RENDER we additionally consult the
 * portal-clip helper. Either CULL → particle skipped.
 */
@Mixin(QuadParticleGroup.class)
public abstract class QuadParticleGroupMixin {

    /**
     * Captured per-call camera. Set at HEAD of {@code extractRenderState},
     * read by the {@code pointInFrustum} redirect, cleared at TAIL.
     * Single-threaded (render thread) — static is safe.
     */
    @Unique
    private static Camera seamlessportals$currentCamera;

    @Inject(
        method = "extractRenderState",
        at = @At("HEAD"),
        require = 0
    )
    private void seamlessportals$captureCameraForCull(
            Frustum frustum, Camera camera, float partialTickTime,
            CallbackInfoReturnable<ParticleGroupRenderState> cir) {
        seamlessportals$currentCamera = camera;
    }

    @Inject(
        method = "extractRenderState",
        at = @At("RETURN"),
        require = 0
    )
    private void seamlessportals$clearCameraAfterExtract(
            Frustum frustum, Camera camera, float partialTickTime,
            CallbackInfoReturnable<ParticleGroupRenderState> cir) {
        seamlessportals$currentCamera = null;
    }

    @Redirect(
        method = "extractRenderState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"
        ),
        require = 1
    )
    private boolean seamlessportals$cullBehindPortal(
            Frustum frustum, double x, double y, double z) {
        // D3 EXCLUSIVITY GATE (A5 — the block-era PortalParticleClip cull call inside this KEEP'd
        // substrate mixin). Flag ON → IP render-side clipping (FrontClipping + CrossPortalEntityRenderer)
        // owns particle clipping, so skip the block-era portal cull but KEEP vanilla frustum culling.
        // Flag OFF (default) → falls through to the full block-era cull below, unchanged.
        if (com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) {
            return frustum.pointInFrustum(x, y, z);
        }
        // Vanilla cull first (cheap; frustum check is fast).
        if (!frustum.pointInFrustum(x, y, z)) return false;
        // During the DEST portal render the camera + particles are the
        // destination dimension's own (per-dest engine extract). The
        // source-dim portal clip is meaningless there — these particles are
        // INSIDE the portal view, already bounded by the stencil mask — and
        // applying it (source-dim portals vs dest-dim positions/camera) would
        // wrongly drop them. So render all in-frustum dest particles.
        // S20: the block-era discriminator (PortalContextSwitch.isRenderingPortal) is replaced by
        // its FLAG-ON equivalent, the IP dest-extract bracket. Same intent, stated for the surviving
        // architecture: particles extracted for a DEST pass are inside the portal view and already
        // bounded by the stencil mask, so a source-dim portal cull must not touch them. Flag-ON dest
        // passes should not even reach this redirect (S14.40's MixinParticleEngine HEAD-cancels the
        // vanilla dest extract, and ip_extractIsolated bypasses this class), so this stays as a
        // defensive belt on that invariant rather than a load-bearing branch.
        if (qouteall.imm_ptl.core.render.SecondaryWorldRenderCore.isDestExtracting) {
            return true;
        }
        // S20: the block-era behind-portal cull (PortalParticleClip, fed by the block-era
        // PortalManager tracker) died with the block era. NOTE FOR THE is5-shadow MERGE: that branch
        // re-introduces a flag-ON behind-portal cull sourced from IP Portal ENTITIES via IPMcHelper
        // (its §2c fix, port-note §A/§E.6) — when it merges, its version lands here unconditionally.
        return true;
    }
}
