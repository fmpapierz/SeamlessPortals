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
public abstract class EntityMixin implements com.warwa.seamlessportals.entity.SeamlessTeleportState {

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

    @Override
    public boolean seamlessportals$isJustTeleported() {
        return seamlessportals$justTeleported;
    }

    @Override
    public void seamlessportals$setJustTeleported(boolean value) {
        seamlessportals$justTeleported = value;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void seamlessportals$checkPortalCrossing(CallbackInfo ci) {
        Entity self = (Entity)(Object) this;
        if (self.level().isClientSide()) return;

        // Tick down vanilla portal cooldown since we cancel handlePortal()
        if (getPortalCooldown() > 0) {
            setPortalCooldown(getPortalCooldown() - 1);
        }

        // IP-style: for ServerPlayer entities, the client is the authoritative
        // crossing detector — it calls SeamlessClientTeleport.performCrossing
        // on LocalPlayer.tick HEAD, which does the visual swap synchronously
        // and sends a ClientPortalCrossingPayload to trigger the server
        // teleport. If the server-side tick detected independently, it would
        // race against (and often beat) the client packet, reintroducing the
        // 50-100 ms round-trip flash for the player whose view we're trying
        // to keep seamless.
        //
        // Mobs and items still use the server-side detector below — they have
        // no client to originate the crossing from.
        if (self instanceof net.minecraft.server.level.ServerPlayer) {
            return;
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

        // Honour vanilla portal cooldown. When a non-player entity does a
        // cross-dim teleport via {@code entity.teleport()}, vanilla
        // destroys the old entity object and creates a new one at the
        // destination — our {@code seamlessportals$justTeleported} @Unique
        // flag is on the OLD object and is lost. PortalTeleporter now
        // stamps a 300-tick portalCooldown on the new entity so we can
        // skip re-crossing attempts until the mob walks out of the
        // destination portal. Without this check, every non-player
        // entity that crosses a portal would loop endlessly, spawning a
        // fresh entity id each tick (observed: stationary "piglins" at a
        // portal with new IDs 729, 738, 747, 756... every tick).
        if (getPortalCooldown() > 0) {
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
