package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.entity.ProjectilePortalHandler;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ThrownEnderpearl.class)
public abstract class ThrownEnderpearlMixin {

    /**
     * Additional hook for ender pearls specifically.
     * Ender pearls are special because when they land, they teleport their owner.
     * When an ender pearl crosses a portal, we need to ensure the owner is
     * correctly tracked across dimensions.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$onEnderPearlTick(CallbackInfo ci) {
        ThrownEnderpearl pearl = (ThrownEnderpearl)(Object) this;

        if (ProjectilePortalHandler.handleProjectileTick(pearl)) {
            ci.cancel();
        }
    }
}
