package qouteall.imm_ptl.core.mixin.common.collision;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.Helper;

// S10-C mechanical retarget: ThrownEnderpearl moved package 1.21.3
// (net.minecraft.world.entity.projectile) -> 26.2
// (net.minecraft.world.entity.projectile.throwableitemprojectile). onHit(HitResult) survives
// (ThrownEnderpearl.java:85); the two discard() invocations inside onHit survive (:147, :149).
//
// SEMANTICS FLAG for the teleportation-slice owner (S14/S15 runtime bring-up): 26.2 vanilla
// onHit now teleports the owner cross-dimension itself (isAllowedToTeleportOwner ->
// player.teleport(new TeleportTransition(pearl-level, ...)) at ThrownEnderpearl.java:101-135)
// BEFORE reaching discard(). IP's inject (which fires ServerTeleportationManager when
// serverPlayer.level() != pearl.level()) therefore now runs after a vanilla cross-dim teleport
// may already have moved the owner — potential double-teleport / stale-reference interaction.
// Ported VERBATIM per mixin-common.md §5 ("port as-is but flag for the teleportation-slice
// owner"); resolution (redesign the injection point vs. suppress vanilla's branch) is deferred
// to the unified crossing bring-up.
@Mixin(ThrownEnderpearl.class)
public class MixinThrownEnderPearl {
    @Inject(
        method = "onHit",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl;discard()V"
        )
    )
    private void onOnHitDiscard(HitResult result, CallbackInfo ci) {
        ThrownEnderpearl this_ = (ThrownEnderpearl) (Object) this;

        Entity owner = this_.getOwner();

        if (owner instanceof ServerPlayer serverPlayer) {
            if (serverPlayer.connection.isAcceptingMessages()
                && serverPlayer.level() != this_.level()
                && !serverPlayer.isSleeping()
            ) {
                Helper.log("Doing cross-dimensional ender pearl teleportation");
                ServerTeleportationManager.teleportEntityGeneral(
                    serverPlayer,
                    this_.position(),
                    ((ServerLevel) this_.level())
                );
            }
        }
    }
}
