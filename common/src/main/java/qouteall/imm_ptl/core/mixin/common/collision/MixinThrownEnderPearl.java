package qouteall.imm_ptl.core.mixin.common.collision;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.Helper;

// S10-C mechanical retarget: ThrownEnderpearl moved package 1.21.3
// (net.minecraft.world.entity.projectile) -> 26.2
// (net.minecraft.world.entity.projectile.throwableitemprojectile). onHit(HitResult) survives
// (ThrownEnderpearl.java:85); the two discard() invocations inside onHit survive (:147, :149).
//
// S15 R13a RESOLUTION (the S10-C "SEMANTICS FLAG" deferred decision, now due — the live pearl
// FREEZE of 2026-07-18, disconnect-04.37.31/04.55.38): 26.2 vanilla onHit teleports the owner
// cross-dimension ITSELF (isAllowedToTeleportOwner -> player.teleport(new TeleportTransition(
// pearl-level, ...)) at ThrownEnderpearl.java:103-123) — the VANILLA dimension-change path,
// which sends ClientboundRespawnPacket. Flag-ON that packet is lethal to the session: 26.2's
// handleRespawn -> startWaitingForNewLevel -> setScreenAndShow RENDERS A FRAME SYNCHRONOUSLY
// MID-PACKET-HANDLING (Minecraft.java:2294 -> renderFrame:1357), the pre-render pump runs in
// the window where mc.level is already the new dim but mc.player is still the old-dim player,
// and ClientWorldLoader.initializeIfNeeded's player-level Validate throws -> netty "Packet
// handling error" -> disconnect + total freeze (the user-reported pearl freeze).
//
// API_RISKS R13a mandates exactly this shape: "IP's mixin must intercept/REPLACE vanilla's
// branch (redirect the teleport calls when dims differ), not add a missing case." History
// corrected per verify wf_30866195-b9f: vanilla's owner-teleport TeleportTransition branch
// shipped in 1.21.2, so 1.21.3 vanilla DID cross-dim teleport at this site and IP's
// discard()-point inject was largely INERT behind it (its only live case was the
// refused-owner gap — isAllowedToTeleportOwner false, notably a dead-but-connected owner;
// that IP-only behavior is DROPPED here as vanilla-consistent — see port-note S15 §5, the
// deferred dead-player-fallback ledger item). Same-dim pearls pass through to vanilla
// UNTOUCHED; cross-dim calls ServerTeleportationManager.forceTeleportPlayer DIRECTLY
// (sendPacket=false — the seamless move: player reuse, no respawn packet), then sends the
// VANILLA-SHAPED relative packet itself through the R8-stamped overwrite
// (MixinServerGamePacketListenerImpl.teleport(PositionMoveRotation, Set<Relative>) — the
// stamp reads player.level().dimension(), which IS the dest by then, so the client swaps
// dimensions seamlessly) and mirrors vanilla's teleport-then-resetPosition order.
//
// VERIFY BLOCKER FOLD (wf_30866195-b9f): the first cut sent forceTeleportPlayer's 5-arg
// connection.teleport, whose vanilla delegate passes EMPTY relatives — teleportSetPosition
// zeroes deltaMovement server-side and the client applies the same zero + absolute-snaps
// rotation. Vanilla's branch preserves the owner's momentum and client-held rotation via
// Relative.union(ROTATION, DELTA) (ThrownEnderpearl.java:119-123), so a sprinting/falling
// pearl throw arrived with wiped momentum. The PositionMoveRotation.of(transition) +
// transition.relatives() pass-through restores vanilla semantics on BOTH halves
// (teleportSetPosition honors the relatives server-side; the client consumer applies them
// from the packet; the mod's client position path touches only position, never velocity).
//
// Returning the (reused) player lets vanilla's own tail apply 1:1 (resetFallDistance,
// resetCurrentImpulseContext, the 5.0 pearl damage against the NEW level, playSound at the
// dest) — nothing re-implemented. The endermite roll + portal-cooldown transfer run BEFORE
// the redirect site, untouched. Non-player owners keep vanilla's Entity.teleport branch
// (server-side recreate, no respawn packet, no client hazard — IP's scope was players).
// Two vanilla ServerPlayer.teleport side-tasks are deliberately omitted as IP-consistent
// with every seamless crossing (port-note S15 §5): stopUsingItem and teleportSpectators
// (a spectator spectating the owner stays behind where vanilla would carry them).
@Mixin(ThrownEnderpearl.class)
public class MixinThrownEnderPearl {
    @org.spongepowered.asm.mixin.injection.Redirect(
        method = "onHit",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;"
        )
    )
    private ServerPlayer seamlessportals$redirectPearlOwnerTeleport(
        ServerPlayer player, net.minecraft.world.level.portal.TeleportTransition transition
    ) {
        if (player.level() == transition.newLevel()) {
            // same-dimension pearl: vanilla behavior byte-for-byte
            return player.teleport(transition);
        }
        Helper.log("Doing cross-dimensional ender pearl teleportation (R13a seamless route)");
        ServerTeleportationManager.of(player.level().getServer()).forceTeleportPlayer(
            player, transition.newLevel().dimension(), transition.position(), false
        );
        player.connection.teleport(
            net.minecraft.world.entity.PositionMoveRotation.of(transition),
            transition.relatives()
        );
        player.connection.resetPosition();
        return player;
    }
}
