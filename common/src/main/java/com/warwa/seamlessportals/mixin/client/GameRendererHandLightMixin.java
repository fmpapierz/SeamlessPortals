package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.HandLightSmoother;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Routes the first-person hand's per-frame light coords through
 * {@link HandLightSmoother} so a seamless crossing fades the hand's lighting
 * over ~0.6 s instead of popping from the source dimension's light to the
 * destination's in one frame (overworld sky-15 → nether sky-0). Vanilla hides
 * that pop behind the portal fade screen; a seamless teleport exposes it.
 *
 * <p>Target: the {@code submitHandsWithItems} call inside
 * {@code GameRenderer.renderItemInHand} (:353-359), whose last arg is
 * {@code getPackedLightCoords(player, deltaPartialTick)} — the only consumer
 * of that sample, so world/entity lighting is untouched.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererHandLightMixin {

    @ModifyArg(
        method = "renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V"
        ),
        index = 4
    )
    private int seamlessportals$smoothHandLight(int lightCoords) {
        return HandLightSmoother.smooth(lightCoords);
    }
}
