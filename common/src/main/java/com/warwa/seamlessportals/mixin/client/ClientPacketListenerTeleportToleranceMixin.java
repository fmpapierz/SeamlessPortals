package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.client.SeamlessClientTeleport;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/**
 * Tolerant application of the vanilla teleport packet in the post-swap window —
 * removes the LAST per-crossing movement artifact: a ~0.4-block backward
 * position snap plus one tick of standstill.
 *
 * <p>Proven by the XTRACE position channel (2026-07-05 run, visible on nearly
 * every crossing): the client swaps and keeps walking (pl −8.00 → −8.39), then
 * the vanilla {@code ClientboundPlayerPositionPacket} from the server's
 * {@code teleportTo} lands ~2 ticks later and hard-sets the ABSOLUTE landing
 * position — pl snaps back to −8.00, holds a tick, then re-walks. Rotation and
 * velocity were already made relative (the hand fix); position stayed absolute
 * as an anchor, and this is its cost.
 *
 * <p>Fix mirrors the seamless-reconcile treatment: while
 * {@link SeamlessClientTeleport#isInPostSwapWindow()} and the packet's computed
 * absolute target is within 4 blocks of the player (pure RTT walk-ahead), SKIP
 * {@code setValuesFromPositionPacket} — but ONLY that call. The redirect leaves
 * the rest of {@code handleMovePlayer} intact, which is load-bearing:
 * <ul>
 *   <li>{@code ServerboundAcceptTeleportationPacket} still ACKs the teleport id
 *       (clears the server's {@code awaitingPositionFromClient} — skipping it
 *       would rubber-band every subsequent move packet);</li>
 *   <li>the follow-up {@code ServerboundMovePlayerPacket.PosRot} now reports the
 *       client's REAL walked-ahead position, which the server adopts — strictly
 *       better than reporting the snapped-back one;</li>
 *   <li>{@code blockStatePredictionHandler.onTeleport()} still runs.</li>
 * </ul>
 *
 * <p>Outside the window, or beyond 4 blocks (a genuine correction, /tp, real
 * desync), the vanilla application runs unchanged via the invoker.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTeleportToleranceMixin {

    @Redirect(
        method = "handleMovePlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;setValuesFromPositionPacket(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;Lnet/minecraft/world/entity/Entity;Z)Z"
        )
    )
    private static boolean seamlessportals$tolerantTeleportApply(
            PositionMoveRotation change, Set<Relative> relatives, Entity entity, boolean interpolate) {
        if (SeamlessClientTeleport.isInPostSwapWindow()) {
            PositionMoveRotation current = PositionMoveRotation.of(entity);
            PositionMoveRotation target = PositionMoveRotation.calculateAbsolute(current, change, relatives);
            double d2 = current.position().distanceToSqr(target.position());
            if (d2 < 16.0) {
                com.warwa.seamlessportals.render.CrossingTracer.event(String.format(
                    "VANILLA-TP ACK-only (walk-ahead d=%.3f, no snap)", Math.sqrt(d2)));
                return false; // skip the stale snap; ACK + PosRot still run
            }
            com.warwa.seamlessportals.render.CrossingTracer.event(String.format(
                "VANILLA-TP applied (d=%.2f blocks — genuine correction)", Math.sqrt(d2)));
        }
        return ClientPacketListenerPositionInvoker.seamlessportals$invokeSetValues(
            change, relatives, entity, interpolate);
    }
}
