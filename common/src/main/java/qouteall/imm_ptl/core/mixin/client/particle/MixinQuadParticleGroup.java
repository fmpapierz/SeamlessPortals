package qouteall.imm_ptl.core.mixin.client.particle;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * S18 (dest particles) — IP's per-particle WORLD FILTER on the MAIN extract path (the S12-B
 * deferred item ② re-sited; IP original: {@code @WrapWithCondition} on
 * {@code Particle.render(VertexConsumer,Camera,F)} keyed on
 * {@code portal_getWorld() == client.level} — {@code Particle.render} is GONE on 26.2, the
 * per-particle boundary is now the per-particle {@code extract} inside
 * {@code QuadParticleGroup.extractRenderState}, 26.2:QuadParticleGroup.java:24-40).
 *
 * <p>Why it matters flag-ON: the single global engine holds MULTI-WORLD particles by design (IP's
 * architecture — remote-world {@code animateTick} + redirected level events spawn dest-tagged
 * particles into it, level-tagged via the {@code withSwitchedWorld} swaps). Without the filter, a
 * dest-tagged particle whose ABSOLUTE coords happen to land inside the main camera's frustum would
 * render in the MAIN pass (the phantom-leak corner the S18 particles trace named). Vanilla's own
 * frustum cull usually hides it (dest coords are typically far/off-axis) — the filter closes it
 * structurally. (This wrap carries the WORLD clause only — IP's full predicate incl. the
 * isRendering spatial clause lives in the revived {@code RenderStates.shouldRenderParticle},
 * consumed by the isolated portal-pass extract; at MAIN extract time isRendering is false, so the
 * predicates coincide here.)
 *
 * <p>The mod's ISOLATED dest extract ({@code MixinParticleEngine.ip_extractIsolated}) does NOT go
 * through {@code extractRenderState} — this filter governs the vanilla path, where {@code mc.level}
 * is the main level at the once-per-frame main extract (LevelExtractor:199). Under the
 * {@code debug_allow_dest_particle_extract} A/B lever the corrupting second vanilla extract is
 * restored and ALSO passes through this wrap with {@code mc.level} swapped to the dest — the
 * shared-accumulator corruption MECHANISM is preserved for attribution, though the billed pool is
 * now dest-filtered rather than the pre-S18 main pool (recorded).
 */
@Mixin(QuadParticleGroup.class)
public class MixinQuadParticleGroup {

    @WrapOperation(
        method = "extractRenderState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/SingleQuadParticle;extract"
                + "(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;"
                + "Lnet/minecraft/client/Camera;F)V"
        )
    )
    private void ip_filterWrongWorldParticle(
        SingleQuadParticle particle, QuadParticleRenderState state, Camera camera, float partialTick,
        Operation<Void> original
    ) {
        IEParticle ie = (IEParticle) particle;
        if (ie.portal_getWorld() != Minecraft.getInstance().level) {
            return; // IP's world filter: wrong-world particles never extract into this pass
        }
        // ★ THE WINDOW RULE (seam round 34, the user's rule with the user's anchor): "if the
        // window is between player and PARTICLE, it does not show". Lives INSIDE this wrap
        // because rounds 32-33 put it in a separate @Redirect on this same instruction, where it
        // never demonstrably fired — one instruction, one owner. Main pass only; the isolated
        // dest extract has its own seam filter.
        if (com.warwa.seamlessportals.render.SeamParticleOcclusion.occluded(
            camera.position(), ie.portal_getX(), ie.portal_getY(), ie.portal_getZ())) {
            return;
        }
        original.call(particle, state, camera, partialTick);
    }
}
