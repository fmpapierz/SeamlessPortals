package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ★ PARTICLES RESPECT THE CUT — SPAWN SIDE (round 30's final form; see also
 * {@code ParticleSeamTickMixin} for the lifetime side). Three cases at the creation funnel:
 * deep empty-half spawns are refused (nothing there emits); plane-adjacent spawns are SHIFTED
 * 0.06 into the owned half (round 29 let them pass and the torch's neck flame — a billboard
 * sitting exactly ON a coincident plane — bled to the empty side constantly; nudged inside it
 * hides behind the window); everything else spawns as vanilla. The shift re-enters this hook via
 * the recursive create call, guarded by the thread-local.
 */
@Mixin(ParticleEngine.class)
public abstract class ClientLevelSeamParticleMixin {

    private static final ThreadLocal<Boolean> seamlessportals$RESPAWNING =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true, require = 1)
    private void seamlessportals$spawnRespectsTheCut(
        ParticleOptions options, double x, double y, double z,
        double vx, double vy, double vz, CallbackInfoReturnable<Particle> cir
    ) {
        if (seamlessportals$RESPAWNING.get()) {
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Vec3 spawn = SeamFractional.particleSpawnPos(level, x, y, z);
        if (spawn == null) {
            cir.setReturnValue(null);
            return;
        }
        if (spawn.x != x || spawn.y != y || spawn.z != z) {
            seamlessportals$RESPAWNING.set(Boolean.TRUE);
            try {
                cir.setReturnValue(((ParticleEngine) (Object) this)
                    .createParticle(options, spawn.x, spawn.y, spawn.z, vx, vy, vz));
            } finally {
                seamlessportals$RESPAWNING.set(Boolean.FALSE);
            }
        }
    }
}
