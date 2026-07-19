package qouteall.imm_ptl.core.mixin.client.particle;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Queue;

/**
 * S18 (dest particles) — accessor for {@code ParticleGroup.particles}
 * ({@code protected final Queue<P>}, 26.2:ParticleGroup.java:21; protected is inaccessible
 * cross-package). Consumed by {@code MixinParticleEngine.ip_extractIsolated}: the isolated
 * world-filtered per-particle extract that realizes IP's per-particle world filter
 * (the S12-B deferred item ②) corruption-free on the 26.2 shared-accumulator model — the
 * caller supplies FRESH render-state objects, so the group's own shared
 * {@code particleTypeRenderState} field (the S14.40 hazard) is never touched.
 */
@Mixin(ParticleGroup.class)
public interface IEParticleGroup {
    @Accessor("particles")
    Queue<Particle> ip_getParticles();
}
