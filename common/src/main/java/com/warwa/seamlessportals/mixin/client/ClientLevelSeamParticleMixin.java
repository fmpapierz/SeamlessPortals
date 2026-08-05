package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.SeamParticleOcclusion;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ★ SOURCE TAGGING at the creation funnel (round 33). No spawn filtering remains — positions
 * are sacred and particles exist wherever vanilla puts them (the round-32 deep-empty refusal
 * chopped the side-view plume in half: "only rendering on one seam half instead of both to be
 * continuous"). A particle born inside a block's animateTick bracket is tagged with that block;
 * {@code SeamParticleOcclusion} then anchors the window rule on the SOURCE, exactly as the user
 * stated it.
 */
@Mixin(ParticleEngine.class)
public abstract class ClientLevelSeamParticleMixin {

    @Inject(method = "createParticle", at = @At("RETURN"), require = 1)
    private void seamlessportals$tagSource(
        ParticleOptions options, double x, double y, double z,
        double vx, double vy, double vz, CallbackInfoReturnable<Particle> cir
    ) {
        SeamParticleOcclusion.tagIfEmitting(cir.getReturnValue());
    }
}
