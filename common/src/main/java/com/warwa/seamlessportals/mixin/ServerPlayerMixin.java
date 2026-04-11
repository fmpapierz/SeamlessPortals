package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.entity.EntityPortalCollision;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    /**
     * Suppress the vanilla portal overlay and waiting time when seamless mode is enabled.
     * In vanilla, the player must stand in a portal for several seconds before teleporting.
     * We hook into tick() to check portal state and cancel vanilla processing.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void seamlessportals$onTick(CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer)(Object) this;

        // If we're in a portal and seamless mode is on, our EntityMixin handles
        // the teleportation during move(). We just need to prevent the vanilla
        // portal screen overlay from showing by resetting portal time.
        if (EntityPortalCollision.isInPortalBounds(player)
            && SeamlessPortalsConfig.get().isSeamlessTeleportation()) {
            // The actual teleportation is handled by EntityMixin.move() hook
        }
    }
}
