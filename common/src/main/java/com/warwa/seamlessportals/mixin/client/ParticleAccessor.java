package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Position readout for {@link SeamParticleOcclusionMixin} — the fields are protected. */
@Mixin(Particle.class)
public interface ParticleAccessor {

    @Accessor("x")
    double seamlessportals$x();

    @Accessor("y")
    double seamlessportals$y();

    @Accessor("z")
    double seamlessportals$z();
}
