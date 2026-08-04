package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamOccupancySavedData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ★ RE-SEND SEAM OCCUPANCY ON EVERY DIMENSION CHANGE — the RS-SEAM-EMPTINESS discriminator
 * probe (2026-08-03: {@code resolved mask=0 [absent] | server said 1}) proved that a dimension
 * change loses every seam record the client held: the client's world swap runs
 * {@code ClientWorldLoader.cleanUp()}, which discards EVERY per-dim {@code ClientLevel} and the
 * occupancy duck maps riding on them — all dimensions at once — while the server broadcasts only
 * at write time and on JOIN. The player then sees every seam cell as vanilla's whole cube (the
 * user's round-15 "full continuous block" on both empty sides).
 *
 * <p>26.2's {@code ServerPlayer.teleport(TeleportTransition)} is the single funnel every
 * cross-dimension move passes through — vanilla portals, {@code /execute in ... tp}, and the
 * mod's own {@code SeamlessServerTeleport.performCrossing} (whose {@code teleportTo} overload
 * wraps it; that path ALSO calls the resend directly, belt-and-braces — masks REPLACE on apply,
 * so duplicates are no-ops). Fabric's {@code ServerEntityWorldChangeEvents} would have been the
 * polite hook, but the 26.2-era fabric-api removed it (verified in
 * {@code fabric-entity-events-v1-5.0.5}: the class is gone).
 *
 * <p>The resend fires at RETURN, after the respawn packet is queued, so the client applies the
 * records to the level it will actually keep; anything that still arrives early parks in the
 * PENDING stash and the END_CLIENT_TICK flush lands it. Same-dimension teleports are filtered at
 * HEAD (the level comparison), so /tp within a world sends nothing.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerSeamResendMixin {

    @Unique
    private boolean seamlessportals$crossDimTeleport;

    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)"
        + "Lnet/minecraft/server/level/ServerPlayer;", at = @At("HEAD"), require = 1)
    private void seamlessportals$markCrossDim(
        TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir
    ) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        seamlessportals$crossDimTeleport = transition.newLevel() != self.level();
    }

    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)"
        + "Lnet/minecraft/server/level/ServerPlayer;", at = @At("RETURN"), require = 1)
    private void seamlessportals$resendAfterCrossDim(
        TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir
    ) {
        if (!seamlessportals$crossDimTeleport || AperturePassthroughLever.DISABLED) {
            seamlessportals$crossDimTeleport = false;
            return;
        }
        seamlessportals$crossDimTeleport = false;
        // The unified teleport reuses the instance, but honour the return value if vanilla ever
        // hands back a different player (the death-respawn path does; it re-enters here too).
        ServerPlayer target = cir.getReturnValue() != null
            ? cir.getReturnValue() : (ServerPlayer) (Object) this;
        SeamOccupancySavedData.resendAllToPlayer(target);
    }
}
