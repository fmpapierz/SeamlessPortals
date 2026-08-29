package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.SeamParticleTeleport;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ★ ROUND 38 — the particle seam teleport's driver moved ENGINE-SIDE. Round 35 injected
 * {@code Particle.tick} RETURN and flames crossed while smoke did not: an override chain that
 * bypasses the transformed base method never fires a base-method inject, and per-class
 * whack-a-mole is exactly the trap round 31 named. The group's own per-particle tick call
 * dispatches VIRTUALLY — hooking the call site catches every particle type unconditionally,
 * override or not.
 */
@Mixin(ParticleGroup.class)
public abstract class ParticleSeamTeleportMixin {

    @Redirect(
        method = "tickParticle",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/Particle;tick()V"
        )
    )
    private void seamlessportals$tickThenCrossTheSeam(Particle particle) {
        particle.tick();
        if (com.warwa.seamlessportals.render.SeamParticleProbe.armed()) {
            com.warwa.seamlessportals.render.SeamParticleProbe.onEngineTick(particle);
            com.warwa.seamlessportals.render.SeamParticleProbe.maybeCensus(
                com.warwa.seamlessportals.client.SeamParticleCensus::walkGlobalEngine);
            com.warwa.seamlessportals.render.SeamParticleProbe.tickSummary();
        }
        SeamParticleTeleport.maybeTeleport(particle);
    }

    /**
     * ★ ROUND 43 — CROSS AT THE DOOR (the user's persistent flicker-bleed, measured live
     * 2026-08-10: flame/smoke birth-crossers teleporting 3-7/s via the OWNED branch during the
     * filmed bleed). 26.2's engine drains {@code particlesToAdd} into groups AFTER the tick
     * phase (ParticleEngine.tick bytecode: groups tick at offset 13, the drain adds at 144), so
     * a particle that materialized past the seam was EXTRACTED AND DRAWN for a frame or two
     * before its first tick teleported it — a flame-colored flash on the empty side at emission
     * cadence, which no clip can remove (that frame, the far side IS the particle's own side)
     * and no window rule can hide (viewer and particle share a side). Teleporting at group-add
     * closes the gap: the particle enters the renderable set already on its correct side.
     * Idempotent with the per-tick driver above (a resident re-check is a cheap no-op).
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "add", at = @At("HEAD"))
    private void seamlessportals$crossAtBirth(
        Particle particle,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir
    ) {
        SeamParticleTeleport.maybeTeleport(particle);
    }
}
