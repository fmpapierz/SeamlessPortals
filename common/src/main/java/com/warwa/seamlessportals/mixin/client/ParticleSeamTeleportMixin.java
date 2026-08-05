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
        SeamParticleTeleport.maybeTeleport(particle);
    }
}
