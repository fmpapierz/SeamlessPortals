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

    // ★ SEAM ROUND 35 — the PARTICLE SEAM TELEPORT (user-proposed mechanism: "can we do the same
    // delete + mirror thing for particles like we do with blocks?"). A particle crossing into a
    // cut cell's empty half is not culled — it is MOVED to the counterpart world/position, the
    // client-side analogue of the entity teleport: re-tag the level (the global engine is
    // multi-world by IP design; S18 renders dest-tagged particles through windows), reposition,
    // and remap velocity. The level field is protected FINAL on 26.2 — hence @Mutable.

    @org.spongepowered.asm.mixin.Mutable
    @Accessor("level")
    void portal_setWorld(ClientLevel level);

    @Accessor("x")
    void portal_setX(double x);

    @Accessor("y")
    void portal_setY(double y);

    @Accessor("z")
    void portal_setZ(double z);

    @Accessor("xo")
    void portal_setXo(double xo);

    @Accessor("yo")
    void portal_setYo(double yo);

    @Accessor("zo")
    void portal_setZo(double zo);

    @Accessor("xd")
    double portal_getXd();

    @Accessor("yd")
    double portal_getYd();

    @Accessor("zd")
    double portal_getZd();

    @Accessor("xd")
    void portal_setXd(double xd);

    @Accessor("yd")
    void portal_setYd(double yd);

    @Accessor("zd")
    void portal_setZd(double zd);
}
