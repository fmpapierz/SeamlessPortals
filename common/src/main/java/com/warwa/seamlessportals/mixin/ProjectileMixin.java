package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.entity.ProjectilePortalHandler;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
public abstract class ProjectileMixin {

    /**
     * Hook into projectile tick to check for portal crossing.
     * Every tick, we check if the projectile's movement would cross a portal plane.
     * If so, the projectile is teleported to the destination dimension.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$onTick(CallbackInfo ci) {
        Projectile projectile = (Projectile)(Object) this;

        if (ProjectilePortalHandler.handleProjectileTick(projectile)) {
            // Projectile was teleported, cancel the normal tick
            ci.cancel();
        }
    }
}
