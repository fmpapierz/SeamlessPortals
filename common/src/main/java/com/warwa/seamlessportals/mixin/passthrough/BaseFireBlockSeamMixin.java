package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ★ FIRE BURNS ONLY ITS OWN HALF (round 30: "flame from source side a collides with me if i
 * cross from source side b to dest side a"). {@code entityInside} fires for any entity in the
 * CELL — position-blind — while a seam fire's material occupies one half. Contact damage now
 * requires the entity's body to actually touch the owned half; the far fragment's own fire
 * handles the other side of the seam in its own dimension.
 */
@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockSeamMixin {

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true, require = 1)
    private void seamlessportals$burnOnlyTheOwnedHalf(
        BlockState state, Level level, BlockPos pos, Entity entity,
        InsideBlockEffectApplier applier, boolean flag, CallbackInfo ci
    ) {
        if (!SeamFractional.entityTouchesOwnedHalf(level, pos, entity)) {
            ci.cancel();
        }
    }
}
