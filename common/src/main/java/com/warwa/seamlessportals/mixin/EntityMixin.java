package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.entity.EntityPortalCollision;
import com.warwa.seamlessportals.entity.PortalTeleporter;
import com.warwa.seamlessportals.portal.PortalLink;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
     * Set true after teleporting. Cleared when the entity is no longer inside
     * any portal bounding box. Prevents re-trigger while still inside the
     * destination portal — no time-based cooldown needed.
     */
    @Unique
    private boolean seamlessportals$justTeleported;

    @Inject(method = "tick", at = @At("HEAD"))
    private void seamlessportals$checkPortalCrossing(CallbackInfo ci) {
        Entity self = (Entity)(Object) this;
        if (self.level().isClientSide()) return;

        // Tick down vanilla portal cooldown since we cancel handlePortal()
        if (getPortalCooldown() > 0) {
            setPortalCooldown(getPortalCooldown() - 1);
        }

        // After teleporting, wait until entity walks OUT of the destination
        // portal before allowing another teleport. This prevents bounce
        // without needing a time-based cooldown.
        if (seamlessportals$justTeleported) {
            if (!EntityPortalCollision.isInPortalBounds(self)) {
                seamlessportals$justTeleported = false;
            }
            return;
        }

        // Check if entity is inside a portal — teleport instantly
        Optional<PortalLink> linkOpt = EntityPortalCollision.findPortalLinkAtEntity(self);
        if (linkOpt.isPresent()) {
            SeamlessPortalsConstants.LOGGER.info("[SEAMLESS TELEPORT] {} at {}",
                self.getName().getString(), position());
            if (PortalTeleporter.teleportEntity(self, linkOpt.get())) {
                seamlessportals$justTeleported = true;
            }
        }
    }

    /**
     * Prevent vanilla's portal timer from advancing.
     */
    @Inject(method = "handlePortal", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$cancelVanillaPortal(CallbackInfo ci) {
        if (SeamlessPortalsConfig.get().isSeamlessTeleportation()) {
            ci.cancel();
        }
    }
}
