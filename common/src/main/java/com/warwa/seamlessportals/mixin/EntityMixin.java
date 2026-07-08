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
     * WRITE-ONLY as of 2026-07-08 (verified: zero readers). The non-player
     * detector below dropped its walk-out gate — plane-segment detection made
     * it obsolete (an embedded entity that isn't moving THROUGH the plane
     * never re-fires) — and the ServerPlayer early-return above always sat
     * before the old read, so the {@code SeamlessServerTeleport} write for
     * players was already inert. Field + interface kept only to avoid
     * touching the player path in the entity-visibility fix; safe to delete
     * together with that write.
     */
    @Unique
    private boolean seamlessportals$justTeleported;

    /**
     * This entity's position at the previous tick HEAD — the {@code from} end
     * of the per-tick movement segment fed to plane-crossing detection. Null
     * on the first tick after spawn/load AND (deliberately) on the first tick
     * of the recreated entity after a cross-dim teleport (vanilla
     * remove+recreate gives the new object fresh mixin state), so a teleport
     * never leaks a stale cross-dimension segment into detection.
     */
    @Unique
    private Vec3 seamlessportals$lastTickPos;

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

        // PLANE-SEGMENT DETECTION (2026-07-08, the "items/animals vanish
        // through portals" fix — the same switch that fixed the PLAYER
        // oscillation freeze in LocalPlayerMixin). The old containment test
        // (findPortalLinkAtEntity: boundingBox.inflate(0.1).contains) fired
        // for anything merely STANDING in the portal volume — up to half a
        // block before the plane — which forced the 300-tick (15s) portal
        // cooldown + the PortalEntityTracker cooldown gate as anti-loop
        // armor, making every crossed entity invisible through the portal
        // for 15 seconds (the user-visible "items disappear"). A crossing is
        // now the movement segment {last tick HEAD -> this tick HEAD}
        // passing THROUGH the aperture (PortalInfo.intersectsMovement:
        // plane straddle + t in [0,1] + height/width bounds at the crossing
        // point), matching IP's ServerTeleportationManager segment test. An
        // entity embedded in the portal but not moving through the plane
        // never fires; walking straight back through legitimately re-fires.
        //
        // The segment MUST be advanced every tick (even under cooldown),
        // or a post-cooldown segment would span multiple ticks of movement.
        // First tick (null): no segment — also covers the recreated entity
        // after a cross-dim teleport (fresh mixin state), so the old
        // "justTeleported flag lost with the old object" hazard is gone
        // structurally; the 2-tick cooldown PortalTeleporter stamps is only
        // an anti-jitter dedup (IP uses a 1-tick dedup), not loop armor.
        // Known accepted edge (IP-parity): a NON-portal teleport (e.g.
        // command /tp) that lands an entity across a portal plane produces
        // one long bogus segment; vanishingly rare for non-players.
        Vec3 from = seamlessportals$lastTickPos;
        Vec3 to = position();
        seamlessportals$lastTickPos = to;
        if (from == null) return;
        if (getPortalCooldown() > 0) return;

        Optional<PortalLink> linkOpt = EntityPortalCollision.findPortalCrossing(self, from, to);
        if (linkOpt.isPresent()) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS TELEPORT] {} id={} {} -> {} at {}{}",
                self.getName().getString(), self.getId(),
                self.level().dimension().identifier(),
                linkOpt.get().getDestination().getDimension().identifier(),
                to,
                self instanceof net.minecraft.world.entity.item.ItemEntity item
                    ? " x" + item.getItem().getCount() : "");
            if (PortalTeleporter.teleportEntity(self, linkOpt.get(), from, to)) {
                // Same-dim teleports keep THIS object: null the segment so
                // the source-side position can't pair with the post-teleport
                // position into a bogus cross-portal segment next tick.
                // (Cross-dim recreates the entity — fresh state anyway.)
                seamlessportals$lastTickPos = null;
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
