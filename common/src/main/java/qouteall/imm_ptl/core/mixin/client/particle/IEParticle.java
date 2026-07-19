package qouteall.imm_ptl.core.mixin.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * S12-B (render client-mixin half) — VERBATIM IP accessor duck
 * ({@code IP:mixin/client/particle/IEParticle.java}). PORTS-CLEAN (mixin-client.md §6): on 26.2
 * {@code Particle.level} is {@code protected final ClientLevel level} ({@code 26.2:Particle.java:22}),
 * so the read accessor is unchanged. Held/UNREGISTERED until S13.
 */
@Mixin(Particle.class)
public interface IEParticle {
    @Accessor("level")
    ClientLevel portal_getWorld();

    // S18 (dest particles): position reads for the isolated world-filtered extract's frustum cull
    // (Particle.x/y/z are protected cross-package — 26.2:Particle.java:26-28; vanilla's own cull
    // reads them same-package in QuadParticleGroup.extractRenderState:26).
    @Accessor("x")
    double portal_getX();

    @Accessor("y")
    double portal_getY();

    @Accessor("z")
    double portal_getZ();
}
