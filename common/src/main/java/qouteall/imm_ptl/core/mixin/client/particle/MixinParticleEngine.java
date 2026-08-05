package qouteall.imm_ptl.core.mixin.client.particle;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEParticleManager;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * S12-B port disposition: NEEDS-RETARGET (mixin-client.md §6). Particle multi-world + far-portal render
 * culling.
 *
 * <p><b>NOTE (concurrent-slice boundary):</b> the particle ACCESSOR {@code IEParticle} was landed by the
 * S12-B render slice; this class carries the {@code IEParticleManager} duck + the behavioral injections.
 *
 * <p><b>26.2 retargets:</b>
 * <ul>
 *   <li><b>far-portal render skip (ported; RE-SITED AGAIN at S18):</b> the original re-site put IP's
 *       &gt;4-portals cancel on {@code ParticleEngine.extract} — but since S18 no portal pass routes
 *       through vanilla extract (the isolated {@code ip_extractIsolated} serves them), so the live
 *       gate is at {@code ip_extractIsolated}'s HEAD (verify fold wf_aa5ce459-4af). The extract
 *       HEAD-cancel's isRendering&gt;4 branch remains as defense on the vanilla path only.</li>
 *   <li><b>duck {@code ip_setWorld} (ported):</b> writes the {@code level} field verbatim
 *       ({@code Particle}/{@code ParticleEngine.level} still present, {@code :31}; vanilla also exposes
 *       {@code setLevel(ClientLevel)} {@code :131}).</li>
 *   <li><b>per-particle world filter — LANDED at S18</b> (was deferred here): the MAIN-pass half is
 *       {@code MixinQuadParticleGroup} (WrapOperation on the per-particle extract); the DEST/portal-pass
 *       half is {@code ip_extractIsolated} below (the corruption-free world-filtered extract). The
 *       wrong-dimension tick-skip (item ③) stays deferred — {@code ParticleGroup.tickParticles} iterates
 *       internally; mitigated by {@code ip_setWorld} keeping the engine's level correct.</li>
 * </ul>
 * REGISTERED + LIVE (ip-client mixin set) since S13.
 */
@SuppressWarnings("resource")
@Mixin(ParticleEngine.class)
public class MixinParticleEngine implements IEParticleManager {
    @Shadow
    protected ClientLevel level;

    // skip particle rendering for far portals — retargeted render(...) -> extract(...) (26.2 render split)
    @Inject(
        method = "extract",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onBeginRenderParticles(
        ParticlesRenderState particlesRenderState, Frustum frustum, Camera camera, float partialTickTime,
        CallbackInfo ci
    ) {
        // S14.40 (the live-rung-2 sky-wedge root cause — user-bisected to destExtractor.extract, then
        // source-pinned here): QuadParticleGroup.extractRenderState RE-FILLS AND RETURNS the group's
        // SHARED particleTypeRenderState field (26.2:QuadParticleGroup.java:24-39); vanilla's invariant
        // is ONE ParticleEngine.extract per frame, so exactly one LevelRenderState ever references those
        // accumulators. The dest-pass extract (SecondaryWorldRenderCore Step 5) is a SECOND mid-frame
        // call: destLRS.reset() clears the shared accumulators the main LRS still references
        // (ParticlesRenderState.reset -> ParticleGroupRenderState::clear), then the dest-camera extract
        // re-bills the MAIN world's particle pool against the DEST camera into them — the main frame's
        // translucent particle submit then draws dest-camera geometry under the main camera state:
        // garbage triangles that depth-test onto sky/far-fog pixels only (the wedges), plus main-world
        // particle wipe (block-era precedent: ParticleEnginePortalSkipMixin, same mechanism, its gate is
        // block-era-only so it is inert flag-ON). THIS CANCEL STAYS ARMED FOREVER on the vanilla
        // path — since S18 the dest pass gets its particles via ip_extractIsolated (below), which
        // never touches the shared accumulators, so the corrupting vanilla second extract has no
        // legitimate caller. debug_allow_dest_particle_extract restores the corrupting vanilla
        // call for live A/B attribution (S20-removal-ledgered; it also SKIPS the isolated fill so
        // the A/B is clean).
        if (qouteall.imm_ptl.core.render.SecondaryWorldRenderCore.isDestExtracting
            && !qouteall.imm_ptl.core.IPGlobal.debugAllowDestParticleExtract
        ) {
            ci.cancel();
            return;
        }
        if (PortalRendering.isRendering()) {
            if (RenderStates.getRenderedPortalNum() > 4) {
                ci.cancel();
            }
        }
    }

    // ② per-particle world filter — LANDED at S18 (the re-site this note deferred): the MAIN-pass
    //    half is MixinQuadParticleGroup.ip_filterWrongWorldParticle (WrapOperation on the per-particle
    //    extract inside extractRenderState); the DEST-pass half is ip_extractIsolated below (the
    //    corruption-free world-filtered extract portal passes consume).
    // ③ wrong-dimension tick-skip — STILL DEFERRED (ParticleGroup.tickParticles iterates internally;
    //    mitigated by ip_setWorld keeping the engine level correct during remote-world ticks).

    @Override
    public void ip_setWorld(ClientLevel world_) {
        level = world_;
    }

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private java.util.Map<net.minecraft.client.particle.ParticleRenderType,
        net.minecraft.client.particle.ParticleGroup<?>> particles;

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private static java.util.List<net.minecraft.client.particle.ParticleRenderType> RENDER_ORDER;

    /**
     * S18 — the isolated world-filtered extract (contract on {@link IEParticleManager}). Replicates
     * vanilla {@code extract}'s RENDER_ORDER walk (26.2:ParticleEngine.java:122-129) with two
     * deliberate differences, both load-bearing: (1) a FRESH {@code QuadParticleRenderState} per
     * group instead of the group's shared field — the S14.40 corruption cannot occur, so portal
     * passes may run this mid-frame while the main LRS still references the shared accumulators;
     * (2) IP's per-particle WORLD FILTER (the flag-ON global engine is multi-world by IP design —
     * remote animateTick + redirected level events tag particles with their true level). The
     * frustum cull mirrors vanilla's (pointInFrustum on the particle's absolute coords — the
     * caller passes the PORTAL-derived frustum). Non-quad groups skipped (bespoke state types —
     * ledgered).
     */
    @Override
    public void ip_extractIsolated(
        ParticlesRenderState output, Frustum frustum, Camera camera, float partialTick,
        ClientLevel worldFilter
    ) {
        // Verify fold (wf_aa5ce459-4af): IP's far-portal particle skip carried onto the isolated
        // path — the ported >4-portals HEAD-cancel above became unreachable for portal passes once
        // they stopped routing through vanilla extract; IP drew NO portal-pass particles beyond 4
        // rendered portals (its worst-frame perf guard).
        if (PortalRendering.isRendering() && RenderStates.getRenderedPortalNum() > 4) {
            return;
        }
        for (net.minecraft.client.particle.ParticleRenderType type : RENDER_ORDER) {
            net.minecraft.client.particle.ParticleGroup<?> group = particles.get(type);
            if (group == null) {
                continue;
            }
            if (!(group instanceof net.minecraft.client.particle.QuadParticleGroup)) {
                continue; // item-pickup/elder-guardian groups: bespoke render states, skipped (ledgered)
            }
            java.util.Queue<net.minecraft.client.particle.Particle> groupParticles =
                ((IEParticleGroup) group).ip_getParticles();
            if (groupParticles.isEmpty()) {
                continue; // vanilla walk parity (ParticleEngine.extract:125 skips empty groups)
            }
            net.minecraft.client.renderer.state.level.QuadParticleRenderState freshState =
                new net.minecraft.client.renderer.state.level.QuadParticleRenderState();
            for (net.minecraft.client.particle.Particle particle : groupParticles) {
                IEParticle ieParticle = (IEParticle) particle;
                if (ieParticle.portal_getWorld() != worldFilter) {
                    continue; // defensive world check (the filter dim, explicit per contract)
                }
                // IP's FULL predicate (RenderStates.shouldRenderParticle, ported verbatim, now
                // live again — verify fold): world match vs mc.level (== worldFilter at both call
                // sites: the shell swaps mc.level before the cross-dim fill; same-dim IS mc.level)
                // + IP's spatial clause (isOnDestinationSide 0.5 during portal passes — the
                // CPU-side cull that saves extract work; wrong-side fragments would hardware-clip
                // anyway via the armed inner clip, but IP culled whole particles here).
                if (!RenderStates.shouldRenderParticle(particle)) {
                    continue;
                }
                if (!frustum.pointInFrustum(
                    ieParticle.portal_getX(), ieParticle.portal_getY(), ieParticle.portal_getZ())
                ) {
                    continue;
                }
                // ★ SYMMETRIC EMPTINESS FOR WINDOW CONTENT (seam round 34): the window shows the
                // far world's truth, and a cut cell's empty half contains nothing — including the
                // far fragment's own drifting smoke. This is the dest-pass mirror of the seam's
                // "there should be nothing" rule; the main pass deliberately does NOT filter by
                // half (a side-on viewer sees the whole plume), it applies the window rule
                // instead (MixinQuadParticleGroup).
                if (com.warwa.seamlessportals.passthrough.SeamFractional.positionInEmptyHalf(
                    worldFilter, ieParticle.portal_getX(), ieParticle.portal_getY(),
                    ieParticle.portal_getZ())) {
                    continue;
                }
                ((net.minecraft.client.particle.SingleQuadParticle) particle)
                    .extract(freshState, camera, partialTick);
            }
            output.add(freshState);
        }
    }
}
