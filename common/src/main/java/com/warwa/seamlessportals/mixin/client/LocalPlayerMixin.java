package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.client.SeamlessClientTeleport;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.entity.EntityPortalCollision;
import com.warwa.seamlessportals.portal.PortalLink;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * IP-style client-initiated seamless teleport detection.
 *
 * <p>Runs once per client tick at the very start of {@code LocalPlayer.tick},
 * before any vanilla movement / portal-handling code. If the local player is
 * inside a portal that has a known {@link PortalLink}, trigger the
 * client-first visual swap via {@link SeamlessClientTeleport#performCrossing}
 * — which also notifies the server so it can do its authoritative cross-dim
 * move.
 *
 * <p>The server-side fallback in {@code EntityMixin.tick} still runs for all
 * entities; for the local player specifically, the client-first detection
 * should always win the race because it runs earlier in the frame and
 * doesn't wait for a packet round-trip. If the client misses the detection
 * (e.g. {@link PortalLink} hasn't been synced yet), the server falls back
 * ~50 ms later and the client performs a deferred swap on receiving
 * {@code ClientboundSeamlessMovePayload}.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void seamlessportals$clientPortalCrossingCheck(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;

        if (!SeamlessPortalsConfig.get().isSeamlessTeleportation()) return;

        // After a client-first crossing, block re-entry of the detector while
        // the player is still inside any portal bounding box. Mirrors the
        // server-side {@code justTeleported} guard in
        // {@code EntityMixin.seamlessportals$checkPortalCrossing}.
        if (SeamlessClientTeleport.justTeleportedClient) {
            if (!EntityPortalCollision.isInPortalBounds(self)) {
                SeamlessClientTeleport.justTeleportedClient = false;
            }
            return;
        }

        Optional<PortalLink> linkOpt = EntityPortalCollision.findPortalLinkAtEntity(self);
        if (linkOpt.isEmpty()) return;

        SeamlessClientTeleport.performCrossing(linkOpt.get());
    }
}
