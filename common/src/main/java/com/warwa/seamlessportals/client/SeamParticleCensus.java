package com.warwa.seamlessportals.client;

import com.warwa.seamlessportals.mixin.client.ParticleEngineAccessorMixin;
import com.warwa.seamlessportals.render.SeamParticleProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import qouteall.imm_ptl.core.mixin.client.particle.IEParticleGroup;

/**
 * ★ PROBE-ONLY — the 1 Hz engine walk feeding {@link SeamParticleProbe#maybeCensus}. Walks the
 * GLOBAL engine (flag-ON the mod runs IP's single multi-world engine; the per-dest engines in
 * {@link PortalWorldManager} are block-era machinery, inert under the D3 exclusivity gate).
 * Called from the client tick path between group ticks — never concurrent with the queues it
 * iterates. ALL groups are walked, including the bespoke non-quad ones the isolated extract
 * skips: the census is about existence, not renderability.
 */
public final class SeamParticleCensus {

    private SeamParticleCensus() {}

    public static void walkGlobalEngine(SeamParticleProbe.CensusSink sink) {
        ParticleEngine engine = Minecraft.getInstance().particleEngine;
        if (engine == null) {
            return;
        }
        for (ParticleGroup<?> group
            : ((ParticleEngineAccessorMixin) engine).seamlessportals$getParticleGroups().values()) {
            if (group == null) {
                continue;
            }
            for (Particle particle : ((IEParticleGroup) group).ip_getParticles()) {
                sink.particle(particle);
            }
        }
    }
}
