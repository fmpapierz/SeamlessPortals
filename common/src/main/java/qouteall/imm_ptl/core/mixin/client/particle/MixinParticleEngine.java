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
 *   <li><b>far-portal render skip (ported):</b> {@code render(LightTexture,Camera,F)} is GONE — particle
 *       drawing is split extract->submit. The HEAD-cancel re-sites onto
 *       {@code ParticleEngine.extract(ParticlesRenderState, Frustum, Camera, float)}
 *       ({@code 26.2:ParticleEngine.java:122}, driven from {@code LevelExtractor.extract:199}). Cancelling
 *       extract while a portal view renders (&gt;4 portals) produces no particle render-state = no
 *       particle draw, the same net effect as IP's render-cancel. Phase: INSIDE-render / dest-pass extract
 *       (reads the render-pass flag {@code PortalRendering.isRendering()}; on the MAIN-frame extract that
 *       flag is false so particles extract normally) — CUTOVER_SPEC §4 render-side.</li>
 *   <li><b>duck {@code ip_setWorld} (ported):</b> writes the {@code level} field verbatim
 *       ({@code Particle}/{@code ParticleEngine.level} still present, {@code :31}; vanilla also exposes
 *       {@code setLevel(ClientLevel)} {@code :131}).</li>
 *   <li><b>per-particle render filter + wrong-dimension tick-skip (DEFERRED — targets gone):</b>
 *       {@code Particle.render(VertexConsumer,Camera,F)} and {@code ParticleEngine.tickParticle(Particle)}
 *       are GONE (per-particle draw = per-particle {@code extract}; ticking = {@code ParticleGroup
 *       .tickParticles()}, {@code :82}), so neither IP per-particle hook has a 1:1 anchor. These are
 *       render-culling / edge-case refinements (the wrong-dimension tick-skip is largely mitigated because
 *       {@code ip_setWorld} keeps the engine's level correct); they are DEFERRED to the S13 render-driver
 *       bring-up, where the per-group {@code extractRenderState}/{@code tickParticles} re-siting is designed
 *       against the live pipeline (mixin-client.md §6). Left commented below.</li>
 * </ul>
 * Held/unregistered until S13.
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
        // block-era-only so it is inert flag-ON). IP's per-particle world filter (deferred item ② below)
        // is the missing faithful mechanism; until it lands the dest pass extracts NO particles — the
        // main world's pool is the WRONG world for the dest view anyway, so skipping loses nothing that
        // ever rendered correctly. debug_allow_dest_particle_extract restores the corrupting vanilla
        // call for live A/B attribution (S20-removal-ledgered).
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

    // DEFERRED (26.2 TARGET-GONE, S13 render-driver bring-up):
    // ② per-particle filter — IP @WrapWithCondition on Particle.render(VertexConsumer,Camera,F) with
    //    RenderStates.shouldRenderParticle(instance). Particle.render is gone (per-particle extract);
    //    re-site onto the per-group/per-particle extractRenderState against the live pipeline.
    // ③ wrong-dimension tick-skip — IP @Inject HEAD-cancel on ParticleEngine.tickParticle(Particle) when
    //    ((IEParticle)particle).portal_getWorld() != Minecraft.getInstance().level. tickParticle is gone
    //    (ParticleGroup.tickParticles :82 iterates internally); re-site there. Mitigated meanwhile by
    //    ip_setWorld keeping the engine level correct.

    @Override
    public void ip_setWorld(ClientLevel world_) {
        level = world_;
    }

}
