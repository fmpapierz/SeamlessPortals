package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.PortalContextSwitch;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops the portal-view render from wiping the MAIN world's particles.
 *
 * <p>{@code QuadParticleGroup.extractRenderState} returns {@code this
 * .particleTypeRenderState} — a SHARED per-group field, re-filled in place on
 * every call (QuadParticleGroup.java:24-39). Vanilla calls
 * {@code ParticleEngine.extract} exactly ONCE per frame (the main render), so the
 * sharing is invisible.
 *
 * <p>The portal secondary render breaks that invariant: the dest
 * {@code LevelExtractor.extract()} calls {@code ParticleEngine.extract} a SECOND
 * time, with the destination (e.g. nether) frustum, over the SAME global particle
 * pool (the engine is global and holds only the player's current/source dim's
 * particles). That second call re-fills the shared {@code particleTypeRenderState}
 * with only the particles inside the dest frustum — almost none, since the
 * source-dim particles are nowhere near the dest camera — and since the main
 * render-state holds a reference to that very object, the main world's particles
 * silently vanish ("particles stop in the overworld once the nether view loads").
 *
 * <p>This is now a FALLBACK guard. The destination view normally renders with
 * {@code mc.particleEngine} swapped to that dimension's OWN
 * {@link ParticleEngine} (a per-dest engine, see
 * {@link com.warwa.seamlessportals.client.PortalWorldManager#getOrCreateParticleEngine}),
 * which has its own particle-group map and so cannot corrupt the source world's
 * render state — there the extract SHOULD run, drawing the dest dim's particles
 * into the portal FBO. {@link PortalContextSwitch#destParticlesActive} signals
 * that swap is in effect. We only skip the extract when a portal view is
 * rendering WITHOUT a dest engine active (creation failed / not yet created) —
 * in that case the extract would run on the shared source engine and wipe the
 * main world's particles, so it is suppressed.
 */
@Mixin(ParticleEngine.class)
public class ParticleEnginePortalSkipMixin {

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$skipExtractDuringPortal(
            ParticlesRenderState particlesRenderState, Frustum frustum,
            Camera camera, float partialTickTime, CallbackInfo ci) {
        if (PortalContextSwitch.isRenderingPortal && !PortalContextSwitch.destParticlesActive) {
            ci.cancel();
        }
    }
}
