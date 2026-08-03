package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import com.warwa.seamlessportals.passthrough.SeamOccupancy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ★ BREAK ROUTING for a shared seam cell — the user's "completely separate" rule (live round 8):
 * breaking from your side must remove YOUR object and must never touch the other one.
 *
 * <p>Vanilla {@code destroyBlock} removes THE cell's blockstate — which is the PRIMARY object.
 * When the breaking player's half is the SECONDARY's, that is precisely the wrong thing, so the
 * call is intercepted and replaced by second-object removal (both dimensions, one drop). When the
 * player's half IS the primary's, vanilla proceeds — and the surviving secondary is promoted into
 * the chunk by the seam driver's air branch ({@code SeamFractional.promoteSecondaryOnAir}).
 *
 * <p>The discriminator is the player's EYE side of the plane — the same rule the outline uses, so
 * what you broke is always what you were shown. Target verified against the bytecode:
 * {@code ServerPlayerGameMode.destroyBlock(BlockPos)Z}, field {@code player}.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeSecondaryBreakMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true, require = 1)
    private void seamlessportals$routeSecondaryBreak(
        BlockPos pos, CallbackInfoReturnable<Boolean> cir
    ) {
        if (!SeamFractional.active() || !(this.player.level() instanceof ServerLevel level)) {
            return;
        }
        SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(level, pos);
        if (sec == null) {
            return;
        }
        var seam = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(level, pos);
        if (seam == null) {
            return;
        }
        for (var binding : seam.bindings()) {
            if (binding == null || binding.cut() == null) {
                continue;
            }
            byte playerHalf = SeamOccupancy.halfOfEye(this.player, pos,
                binding.srcFacing().getAxis(), binding.cut().srcPlaneOffset());
            if (playerHalf == sec.half()) {
                // The breaker is on the second object's side: remove IT, leave the primary.
                cir.setReturnValue(SeamFractional.breakSecondary(level, pos, this.player));
            }
            // Player on the primary's side: vanilla destroy runs, and the driver's air branch
            // promotes the surviving secondary into the chunk.
            return;
        }
    }
}
