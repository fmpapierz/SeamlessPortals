package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.client.PortalParticleClip;
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
        // substrate mixin) — §2c AMENDED (2026-07-25): the old flag-ON premise ("IP render-side
        // clipping owns particle clipping") is REFUTED — FrontClipping.setupOuterClipping is
        // dead/uncalled and vanilla particle programs carry no clip. Live + recon-proven bleed
        // mechanism (shaders-OFF + Fabulous/Improved Transparency): translucent particles draw
        // into the SEPARATE framegraph particles target whose depth was copied BEFORE the portal
        // window drew (26.2 LevelRenderer :429-431 copy vs the AFTER_TRANSLUCENT_TERRAIN portal
        // draw), so behind-plane source particles pass the stale test and the transparency
        // composite paints them OVER the window (iris force-disables Fabulous ⇒ shaders-ON never
        // bleeds; the mod's own dest-side Fabulous guard documents the identical class). So
        // flag-ON now ALSO runs the geometric cull (graphics-mode-independent, removes the
        // particle at extract) — lever-gated DEFAULT-ON. The flag-ON portal ROSTER is the IP
        // Portal ENTITIES via IPMcHelper (PortalParticleClip §2c repoint — the block-era tracker
        // is empty flag-ON; final-diff verify catch).
        // Flag-ON dest passes CANNOT reach this redirect (S14.40 MixinParticleEngine HEAD-cancels
        // the vanilla dest extract; ip_extractIsolated bypasses this class), so the cull only
        // ever sees the MAIN extract: main camera + source-world portals — correct semantics.
        // The isDestExtracting belt below defends that invariant anyway.
        if (com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()
            && !qouteall.imm_ptl.core.IPGlobal.isSourceParticleCullActive()) {
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
        // (Block-era discriminator + the flag-ON belt — see the gate note above.)
        if (com.warwa.seamlessportals.render.PortalContextSwitch.isRenderingPortal
            || qouteall.imm_ptl.core.render.SecondaryWorldRenderCore.isDestExtracting) {
            return true;
        }
        // Additional cull: particle behind any active portal from camera.
        // No-op when no portals are present in the player's current dim.
        if (seamlessportals$currentCamera != null
            && PortalParticleClip.isPositionBehindPortal(
                x, y, z, seamlessportals$currentCamera)) {
            // §2c liveness + confirm counter (probe-read as spc=; once-only ACTIVE line) —
            // flag-ON ONLY (verify-fold finding 2: block-era cull hits must stay byte-identical
            // including logging; the lever the ACTIVE line names is inoperative block-era).
            if (com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) {
                qouteall.imm_ptl.core.IPGlobal.sourceParticleCullCount++;
                if (!seamlessportals$cullLivenessLogged) {
                    seamlessportals$cullLivenessLogged = true;
                    qouteall.q_misc_util.Helper.log(
                        "[sourceParticleCull] ACTIVE — first behind-portal source particle culled "
                            + "(A/B lever -Dseamlessportals.disableSourceParticleCull)");
                }
            }
            return false;
        }
        return true;
    }

    /** §2c once-only liveness latch (render thread). */
    @Unique
    private static boolean seamlessportals$cullLivenessLogged = false;
}
