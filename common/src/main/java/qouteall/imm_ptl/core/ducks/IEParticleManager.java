package qouteall.imm_ptl.core.ducks;

import net.minecraft.client.multiplayer.ClientLevel;

public interface IEParticleManager {
    void ip_setWorld(ClientLevel world);

    /**
     * S18 (dest particles) — the ISOLATED world-filtered extract: IP's per-particle world filter
     * (the S12-B deferred item ②) realized corruption-free on the 26.2 shared-accumulator model.
     * Replicates {@code ParticleEngine.extract}'s RENDER_ORDER walk, but per group extracts the
     * world-matching, frustum-passing particles into a FRESH caller-owned
     * {@code QuadParticleRenderState} — the group's own shared {@code particleTypeRenderState}
     * field (which the main pass's LevelRenderState references — the S14.40 corruption) is never
     * touched, so this is safe to run mid-frame for dest/same-dim portal passes. Non-quad groups
     * (item-pickup, elder-guardian) are skipped (ledgered — their state types are bespoke).
     */
    void ip_extractIsolated(
        net.minecraft.client.renderer.state.level.ParticlesRenderState output,
        net.minecraft.client.renderer.culling.Frustum frustum,
        net.minecraft.client.Camera camera,
        float partialTick,
        ClientLevel worldFilter
    );
}
