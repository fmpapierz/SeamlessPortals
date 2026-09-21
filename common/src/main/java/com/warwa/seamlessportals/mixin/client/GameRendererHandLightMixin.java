package com.warwa.seamlessportals.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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

    // 26.3: the `int lightCoords` ARGUMENT this hook modified no longer exists. 26.2 sampled the hand light at the call site
    // and passed it down (mc262-ref GameRenderer.java:353-359 `submitHandsWithItems(.., this.minecraft.player,
    // getPackedLightCoords(this.minecraft.player, deltaPartialTick))` -> ItemInHandRenderer.java:335,358,376: the one int fed
    // BOTH submitArmWithItem calls). 26.3 samples it at EXTRACT time into the player's own render state (mc263-ref
    // LevelExtractor.java:419-420 -> PlayerRenderState.avatarRenderState.lightCoords) and the renamed renderer reads that FIELD
    // itself, twice (mc263-ref FirstPersonHandsAndItemsRenderer.java:352,370; javap 26.3 submitHandsWithItems: getfield
    // AvatarRenderState.lightCoords at offsets 162 and 246, each the last arg of submitArmWithItem — the only two reads).
    // Same class, same method, same call site as 26.2 (mc263-ref GameRenderer.java:393-400; renderItemInHand's new descriptor is
    // javap-verified): the field is swapped to the smoothed value for exactly the duration of the call and restored after, so
    // (a) smooth() still runs ONCE per hand-drawing frame (it is a wall-clock integrator — HandLightSmoother:76-77), (b) both
    // hands still receive the SAME smoothed value, and (c) it is still "the only consumer of that sample, so world/entity
    // lighting is untouched" (class javadoc) — nothing else observes the swapped field.
    @WrapOperation(
        method = "renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V"
        )
    )
    private void seamlessportals$smoothHandLight(
        net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer instance,
        float partialTicks,
        com.mojang.blaze3d.vertex.PoseStack poseStack,
        net.minecraft.client.renderer.SubmitNodeCollector submitNodeCollector,
        net.minecraft.client.renderer.state.level.PlayerRenderState playerState,
        net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState state,
        Operation<Void> original
    ) {
        net.minecraft.client.renderer.entity.state.AvatarRenderState avatarRenderState = playerState.avatarRenderState;
        if (avatarRenderState == null) {
            // vanilla draws no hand in this case (mc263-ref FirstPersonHandsAndItemsRenderer.java:331-332) — nothing to smooth.
            original.call(instance, partialTicks, poseStack, submitNodeCollector, playerState, state);
            return;
        }
        int lightCoords = avatarRenderState.lightCoords;
        avatarRenderState.lightCoords = HandLightSmoother.smooth(lightCoords);
        try {
            original.call(instance, partialTicks, poseStack, submitNodeCollector, playerState, state);
        } finally {
            avatarRenderState.lightCoords = lightCoords;
        }
    }
}
