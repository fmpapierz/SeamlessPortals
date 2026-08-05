package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ★ NO PARTICLE IN THE EMPTY HALF — round 28's restatement of round 27's particle rule. The
 * first form suppressed {@code animateTick} by CAMERA side, which left two holes the user found
 * within a session: a slow particle spawned while the viewer stood on the owned side kept
 * rendering after they crossed ("just that one slow particle will bleed through"), and a side-on
 * viewer would have lost owned-half particles they can legitimately see. The honest invariant is
 * about the WORLD: the empty half of a cut seam cell contains nothing, so nothing may emit
 * there. Enforced at the particle-creation funnel ({@code ParticleEngine.createParticle}) by
 * spawn POSITION — camera-independent, so no view change can surface a stale one. Particles
 * spawned in the OWNED half render for everyone, exactly like the material they rise from.
 */
@Mixin(ParticleEngine.class)
public abstract class ClientLevelSeamParticleMixin {

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true, require = 1)
    private void seamlessportals$noParticleInTheEmptyHalf(
        ParticleOptions options, double x, double y, double z,
        double vx, double vy, double vz, CallbackInfoReturnable<Particle> cir
    ) {
        var level = Minecraft.getInstance().level;
        if (level != null && SeamFractional.positionInEmptyHalf(level, x, y, z)) {
            cir.setReturnValue(null);
        }
    }
}
