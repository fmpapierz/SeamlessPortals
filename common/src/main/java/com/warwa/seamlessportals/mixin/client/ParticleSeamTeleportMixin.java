package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.SeamParticleTeleport;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ★ ROUND 35 — the particle seam teleport's tick driver: after a particle moves, if it crossed
 * into a cut cell's empty half it is carried to the counterpart world (see
 * {@link SeamParticleTeleport}). Subclasses that override {@code tick} reach this through their
 * {@code super.tick()} movement chain (verified for the flame/smoke families in round 31).
 */
@Mixin(Particle.class)
public abstract class ParticleSeamTeleportMixin {

    @Inject(method = "tick", at = @At("RETURN"))
    private void seamlessportals$crossTheSeam(CallbackInfo ci) {
        SeamParticleTeleport.maybeTeleport((Particle) (Object) this);
    }
}
