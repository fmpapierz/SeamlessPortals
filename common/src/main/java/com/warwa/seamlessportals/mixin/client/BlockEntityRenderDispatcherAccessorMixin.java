package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * IP context switch: client.blockEntityRenderDispatcher.level = newWorld
 *
 * MC 26.1.2 CHANGE: BlockEntityRenderDispatcher no longer has a `level` field.
 * Instead it has `cameraPos` (Vec3) set via prepare(Vec3).
 * During context switch, we call prepare(destCameraPos) to update it.
 * This accessor provides read access to verify state.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public interface BlockEntityRenderDispatcherAccessorMixin {

    @Accessor("cameraPos")
    Vec3 seamlessportals$getCameraPos();
}
