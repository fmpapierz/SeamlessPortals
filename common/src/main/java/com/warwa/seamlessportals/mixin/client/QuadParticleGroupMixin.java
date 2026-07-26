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
 * {@link ParticleGroupRenderState} for submission.
 *
 * <p><b>S20 STATE — the cull itself is currently ABSENT.</b> Its block-era implementation died
 * with the block era; the redirect below is an identity pass-through and the camera capture is the
 * substrate the replacement will use. See the redirect's own comment.
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
        // S20 INCREMENT 4 — THIS REDIRECT IS CURRENTLY AN IDENTITY, and that is the honest state,
        // not an oversight. Its two arms were: flag-ON → vanilla frustum cull only (IP render-side
        // clipping owned particle clipping); flag-OFF → the block-era behind-portal cull. The
        // block-era cull (PortalParticleClip fed by the block-era PortalManager tracker) died with
        // the block era at increment 3, and the flag died here, so what a mechanical collapse
        // leaves is exactly `frustum.pointInFrustum(...)`.
        //
        // WHY THE CLASS SURVIVES the collapse rather than being deleted as dead weight: the camera
        // capture above is live substrate for the incoming behind-portal cull. `iris-on/is5-shadow`
        // re-introduces a geometric source-particle cull HERE — sourced from IP Portal ENTITIES via
        // IPMcHelper, reading seamlessportals$currentCamera, lever-gated default-ON (its §2c fix;
        // port-note §A/§E.6). Deleting this file would turn that merge into a delete/modify
        // conflict over live sibling work. When it lands, its cull replaces this return.
        return frustum.pointInFrustum(x, y, z);
    }
}
