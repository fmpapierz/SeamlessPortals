package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.passthrough.SeamRideProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * (e) DEFECT-B instrument — hooks the client-side DISMOUNT WRITE for the local player.
 *
 * <p><b>Why the write and not the settled state.</b> This engagement has paid three separate times
 * for reading an outcome after the engine has already papered over it (an off-rail cart is snapped
 * back by {@code moveAlongTrack} within ONE tick; a 2-tick poll read the corrected value while the
 * carry had plainly placed the cart elsewhere). A dismount is the same shape: prediction and the
 * next {@code SetPassengers} can both restore the link before any per-tick sampler looks, and the
 * user would still have seen the break. {@code Entity.removeVehicle()} is the last thing the engine
 * mutates before the rider is off, so that is what this hooks.
 *
 * <p><b>Target verified by javap</b> against
 * {@code minecraft-merged-deobf-26.2.jar}: {@code public void removeVehicle()} on
 * {@code net.minecraft.world.entity.Entity}. This is also the method IP's own
 * {@code MixinServerPlayer} note names as the real dismount path on 26.2 ("Entity.stopRiding ->
 * this.removeVehicle() virtual"), so hooking it catches {@code stopRiding} too — including the
 * unconditional {@code getPassengers().forEach(Entity::stopRiding)} inside {@code Entity.setRemoved},
 * which is the ejection route the recon predicts.
 *
 * <p>Registered in the CLIENT list of {@code seamlessportals-common.mixins.json}, so it never loads
 * on a dedicated server despite {@code Entity} being a common class. Read-only, non-cancellable,
 * and a single boolean test ({@code SeamRideProbe.windowOpen()}) outside a crossing window.
 */
@Mixin(Entity.class)
public class EntityRideWriteMixin {

    @Inject(method = "removeVehicle", at = @At("HEAD"))
    private void seamlessportals$traceLocalPlayerDismount(CallbackInfo ci) {
        // NOT gated on SeamRideProbe.windowOpen(). The 2026-08-01 live round showed the failure
        // appearing only after the view distance went 2 -> 32, which need not coincide with a
        // crossing window — a window-gated hook would have recorded nothing, exactly the way the
        // first build of this probe recorded nothing because it was armed on the wrong path.
        Entity self = (Entity) (Object) this;
        // The SERVER player's own dismounts matter too: an ejecting SetPassengers on the client is
        // either an echo of a real server-side dismount or a stale packet contradicting a ride the
        // server still holds, and only the pair of traces distinguishes them. In a dev client both
        // sides live in one JVM, so this client-listed mixin sees both.
        if (self instanceof net.minecraft.server.level.ServerPlayer) {
            SeamRideProbe.onServerPlayerRemoveVehicle(self, self.getVehicle());
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || self != mc.player) {
            return;
        }
        SeamRideProbe.onLocalPlayerRemoveVehicle(self, self.getVehicle());
    }

    /**
     * THE REMOVAL that ejects a rider, with its {@code RemovalReason}.
     *
     * <p>Measured 2026-08-01: a same-dim ridden crossing ends with an EMPTY
     * {@code ClientboundSetPassengersPacket} for the ridden cart, and the server-side stack behind
     * it is {@code ServerPlayer.removeVehicle < LivingEntity.stopRiding < Entity.setRemoved} —
     * i.e. the packet is HONEST: the server really did dismount the player, because the cart entity
     * was REMOVED. {@code Entity.setRemoved} ends in an unconditional
     * {@code getPassengers().forEach(Entity::stopRiding)} (javap), so any removal of a ridden
     * vehicle ejects its rider.
     *
     * <p>The remaining question is WHY it is removed, and the {@code RemovalReason} answers it
     * directly: {@code UNLOADED_TO_CHUNK} means the 40,000-block same-dim teleport outran the
     * destination chunk's residency (a ticket/ordering fix), {@code DISCARDED} means something
     * deliberately discarded it (a caller fix), {@code CHANGED_DIMENSION} means an entity-pipeline
     * crossing fired on a vehicle it should have skipped. Those are three different fixes, which is
     * why this logs the reason rather than assuming one.
     */
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void seamlessportals$traceRiddenVehicleRemoval(
        Entity.RemovalReason reason, CallbackInfo ci
    ) {
        Entity self = (Entity) (Object) this;

        // BOTH SIDES OF THE RIDE, because setRemoved ejects in two different ways and the first
        // build of this hook only watched one of them:
        //   1. `if (removalReason.shouldDestroy()) this.stopRiding();`   <- THIS entity dismounts
        //   2. `this.getPassengers().forEach(Entity::stopRiding);`        <- its riders dismount
        // Watching only case 2 (a vehicle carrying a player) logged nothing on 2026-08-01 while
        // the server-side stack plainly showed
        // `ServerPlayer.removeVehicle < LivingEntity.stopRiding < Entity.setRemoved` — i.e. case 1,
        // with setRemoved called on the PLAYER. A hook that cannot fire for the observed stack is
        // the instrument confessing, not evidence of absence.
        boolean carriesPlayer = false;
        for (Entity passenger : self.getPassengers()) {
            if (passenger instanceof net.minecraft.world.entity.player.Player) {
                carriesPlayer = true;
                break;
            }
        }
        boolean isRidingPlayer =
            self instanceof net.minecraft.world.entity.player.Player && self.getVehicle() != null;
        if (!carriesPlayer && !isRidingPlayer) {
            return;
        }
        SeamRideProbe.onRiddenVehicleRemoved(
            self, reason == null ? "null" : reason.name(), self.level().isClientSide(),
            isRidingPlayer ? "PLAYER-REMOVED(case1)" : "VEHICLE-REMOVED(case2)");
    }

    /**
     * THE POSITION WRITE for the carried vehicle. {@code setPosRaw} is {@code public final} on
     * 26.2 (javap) and is the funnel every position write goes through, so hooking it catches the
     * packet handler, the interpolation and the physics alike — and, unlike a per-tick sample, it
     * names the CALLER.
     *
     * <p>Why this exists: the 2026-08-01 round showed the client's cart correctly placed in the
     * destination for two ticks and then jumping 40,000 blocks to a position byte-identical to the
     * SOURCE dimension's server-side sample from two ticks BEFORE the crossing — i.e. a stale
     * source-dimension position applied to an entity that had already been moved to the
     * destination. The per-tick sampler could show that it happened but not who did it. This can.
     *
     * <p>Cost outside the crossing window: one boolean and one int compare. The log line is
     * emitted only for jumps beyond {@link com.warwa.seamlessportals.passthrough.SeamRideProbe#JUMP_BLOCKS},
     * so ordinary rolling never prints.
     */
    @Inject(method = "setPosRaw", at = @At("HEAD"))
    private void seamlessportals$traceVehicleJump(double x, double y, double z, CallbackInfo ci) {
        if (!SeamRideProbe.windowOpen()) {
            return;
        }
        Entity self = (Entity) (Object) this;
        // IDENTITY, never getId(). Entity.<init> reaches setPosRaw before the id is assigned, and
        // getId() throws there — the first build of this hook disconnected the client the moment a
        // mob spawned during a crossing window (2026-08-01, IllegalStateException inside
        // ClientPacketListener.handleAddEntity, killing the packet handler).
        if (!SeamRideProbe.isWatchedVehicle(self)) {
            return;
        }
        SeamRideProbe.onWatchedVehicleMoved(self, x, y, z);
    }
}
