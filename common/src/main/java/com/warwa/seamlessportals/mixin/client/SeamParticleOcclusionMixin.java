package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.SeamParticleOcclusion;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ★ THE WINDOW RULE, applied at the extract loop (see {@link SeamParticleOcclusion} for the
 * rule's story): a particle whose line to the camera crosses a portal quad contributes nothing
 * to the frame — that space shows the window's view. Redirecting the per-particle extract call
 * skips it cleanly (nothing is written into the group's render state, unlike cancelling inside
 * {@code extract} itself, which would leave a half-initialised quad).
 */
@Mixin(QuadParticleGroup.class)
public abstract class SeamParticleOcclusionMixin {

    @Redirect(
        method = "extractRenderState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/SingleQuadParticle;extract("
                + "Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;"
                + "Lnet/minecraft/client/Camera;F)V"
        )
    )
    private void seamlessportals$skipWindowOccluded(
        SingleQuadParticle particle, QuadParticleRenderState state,
        Camera camera, float partialTick
    ) {
        ParticleAccessor a = (ParticleAccessor) particle;
        if (SeamParticleOcclusion.occluded(particle, camera.position(),
            a.seamlessportals$x(), a.seamlessportals$y(), a.seamlessportals$z())) {
            return;
        }
        particle.extract(state, camera, partialTick);
    }
}
