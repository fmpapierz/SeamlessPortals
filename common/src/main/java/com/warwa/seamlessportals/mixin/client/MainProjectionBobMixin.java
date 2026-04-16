package com.warwa.seamlessportals.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skips the walk-bob / hurt-tilt transformation that vanilla applies to
 * the main world PROJECTION matrix (GameRenderer.renderLevel, the
 * bobHurt + bobView calls on the level bobStack).
 *
 * With this mixin active:
 * <ul>
 *   <li>The world projection stays clean → source terrain AND the
 *       destination composite inside the portal both render at stable
 *       on-screen positions.</li>
 *   <li>The hand / held-item render (GameRenderer.renderItemInHand) is
 *       NOT intercepted, so vanilla's separate bobHurt + bobView calls
 *       on the hand's pose stack still run → the player's arm/item
 *       still bobs while walking.</li>
 * </ul>
 *
 * This matches the user expectation of "I should bob up and down but
 * the terrain and view should not", and also removes the rendering
 * mismatch where the destination FBO (rendered clean) was composited
 * inside a bobbing source frame, which made the destination appear to
 * slide within the window.
 */
@Mixin(GameRenderer.class)
public abstract class MainProjectionBobMixin {

    @Redirect(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"
        )
    )
    private void seamlessportals$skipMainBobHurt(GameRenderer self,
                                                  CameraRenderState cameraState,
                                                  PoseStack poseStack) {
        // no-op: keep the world projection free of hurt-tilt
    }

    @Redirect(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"
        )
    )
    private void seamlessportals$skipMainBobView(GameRenderer self,
                                                  CameraRenderState cameraState,
                                                  PoseStack poseStack) {
        // no-op: keep the world projection free of walk-bob
    }
}
