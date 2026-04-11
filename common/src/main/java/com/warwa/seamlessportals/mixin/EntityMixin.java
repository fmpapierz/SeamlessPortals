package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.entity.EntityPortalCollision;
import com.warwa.seamlessportals.entity.PortalTeleporter;
import com.warwa.seamlessportals.portal.PortalLink;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(Entity.class)
public abstract class EntityMixin {

    @Shadow
    public abstract Vec3 position();

    @Shadow
    public abstract int getPortalCooldown();

    @Shadow
    public abstract void setPortalCooldown(int cooldown);

    /**
     * Intercept entity movement to detect portal plane crossing.
     * When an entity's movement vector crosses a portal boundary,
     * trigger seamless teleportation to the destination dimension.
     */
    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$onMove(MoverType moverType, Vec3 movement, CallbackInfo ci) {
        Entity self = (Entity)(Object) this;

        if (self.level().isClientSide()) return;
        if (getPortalCooldown() > 0) return;

        Vec3 from = position();
        Vec3 to = from.add(movement);

        Optional<PortalLink> linkOpt = EntityPortalCollision.checkPortalCrossing(self, from, to);
        if (linkOpt.isPresent()) {
            PortalLink link = linkOpt.get();
            if (PortalTeleporter.teleportEntity(self, link)) {
                ci.cancel();
            }
        }
    }
}
