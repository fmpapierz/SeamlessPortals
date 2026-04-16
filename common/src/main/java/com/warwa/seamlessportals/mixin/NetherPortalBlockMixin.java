package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.portal.PortalDetector;
import com.warwa.seamlessportals.portal.PortalType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$onEntityInside(BlockState state, Level level, BlockPos pos,
                                                 Entity entity, InsideBlockEffectApplier effectApplier,
                                                 boolean isPrecise, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel) {
            PortalDetector.onNetherPortalFormed(level, pos, serverLevel.getServer());
        }
        // Also register on client side for rendering
        if (level.isClientSide()) {
            PortalDetector.onNetherPortalDetectedClient(level, pos);
        }

        // Suppress vanilla portal behavior (purple overlay, 4-second timer,
        // loading-screen teleport) when seamless teleportation is enabled.
        // Our EntityMixin.move() hook handles instant teleportation instead.
        if (SeamlessPortalsConfig.shouldSeamlessTeleport(PortalType.NETHER)) {
            ci.cancel();
        }
    }
}
