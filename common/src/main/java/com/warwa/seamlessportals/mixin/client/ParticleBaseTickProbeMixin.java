package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.SeamParticleProbe;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ★ PROBE-ONLY (evidence question 2, {@code migration/PARTICLE_SEAM_HANDOFF.md}) — the round-35
 * driver's exact anchor, rebuilt as a COUNTER: {@code Particle.tick} RETURN. Round 35's live
 * evidence (flame crossed, smoke did not) contradicted a javap reachability analysis that said
 * smoke's tick chain reaches this base body; the ledger's standing order is to resolve that
 * contradiction with an instrument, not another reading. Per-class counts here vs the engine-side
 * redirect's counts ({@code ParticleSeamTeleportMixin}) settle it: a class the engine ticks whose
 * counter here stays 0 has an override chain that bypasses the transformed base method.
 *
 * <p>No behavior: one lever check per particle tick when disarmed.
 */
@Mixin(Particle.class)
public abstract class ParticleBaseTickProbeMixin {

    @Inject(method = "tick", at = @At("RETURN"))
    private void seamlessportals$countBaseTickReturn(CallbackInfo ci) {
        if (SeamParticleProbe.armed()) {
            SeamParticleProbe.onBaseTickReturn((Particle) (Object) this);
        }
    }
}
