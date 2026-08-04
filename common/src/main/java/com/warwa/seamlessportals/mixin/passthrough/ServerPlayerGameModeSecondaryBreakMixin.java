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

    /**
     * The level the game mode operates on — NOT {@code player.level()}: under cross-portal
     * interaction the break can target a cell in another dimension than the one the player stands
     * in, and the occupancy that matters is the target level's.
     */
    @Shadow
    protected ServerLevel level;

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true, require = 1)
    private void seamlessportals$routeSecondaryBreak(
        BlockPos pos, CallbackInfoReturnable<Boolean> cir
    ) {
        if (!SeamFractional.active() || this.level == null) {
            return;
        }
        var seam = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(this.level, pos);
        if (seam == null) {
            return;
        }
        SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(this.level, pos);
        for (var binding : seam.bindings()) {
            if (binding == null || binding.cut() == null) {
                continue;
            }
            // ★ ONE RULE, LOCAL AND THROUGH-WINDOW (live round 11): a local breaker's half is
            // their eye side; a through-window breaker may only reach the half BEYOND the far
            // plane. Raw eye coordinates for a foreign breaker computed a garbage half that
            // coincided with the material, letting the far-side-only object be broken from the
            // side that provably shows nothing.
            byte playerHalf = SeamFractional.viewerTargetableHalf(
                this.level, pos, binding, this.player);
            if (playerHalf == 0) {
                // No legitimate line to this cell from where the breaker is: refuse the break
                // outright rather than letting vanilla destroy whatever the cell holds.
                if (com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                        .SEAM_FRACTIONAL_PROBE) {
                    com.warwa.seamlessportals.passthrough.SeamFractionalProbe.onSeamCell(pos,
                        "REFUSE", "break refused — no legitimate line (viewer at "
                            + this.player.blockPosition() + " in "
                            + this.player.level().dimension().identifier() + ")");
                }
                cir.setReturnValue(false);
                return;
            }
            if (playerHalf == SeamOccupancy.BOTH) {
                // Round 22: a side-on viewer holds a BOTH mask — narrow to the half the
                // crosshair actually struck, so the break hits the object being looked at.
                net.minecraft.world.phys.HitResult pick = this.player.pick(6.0, 1.0f, false);
                if (pick instanceof net.minecraft.world.phys.BlockHitResult pickHit
                    && pickHit.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                    && pickHit.getBlockPos().equals(pos)) {
                    playerHalf = SeamOccupancy.halfFromHit(pickHit.getLocation(), pos,
                        binding.srcFacing().getAxis(), binding.cut().srcPlaneOffset());
                } else {
                    byte owned22 = SeamOccupancy.occupancyOf(this.level, pos);
                    playerHalf = (owned22 == SeamOccupancy.HALF_POSITIVE
                        || owned22 == SeamOccupancy.HALF_NEGATIVE) ? owned22
                        : SeamOccupancy.halfOfEye(this.player, pos,
                            binding.srcFacing().getAxis(), binding.cut().srcPlaneOffset());
                }
            }
            if (sec != null && playerHalf == sec.half()) {
                // The breaker is on the second object's side: remove IT, leave the primary.
                cir.setReturnValue(SeamFractional.breakSecondary(this.level, pos, this.player));
                return;
            }
            byte owned = SeamOccupancy.occupancyOf(this.level, pos);
            if ((owned == SeamOccupancy.HALF_POSITIVE || owned == SeamOccupancy.HALF_NEGATIVE)
                && playerHalf != owned) {
                // The breaker's targetable half is EMPTY (single-object cell, material on the other
                // side): there is nothing of theirs to break. Without this, vanilla destroy would
                // remove the primary a viewer on that side cannot even see.
                if (com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                        .SEAM_FRACTIONAL_PROBE) {
                    com.warwa.seamlessportals.passthrough.SeamFractionalProbe.onSeamCell(pos,
                        "REFUSE", "break refused — targetable half "
                            + (playerHalf == SeamOccupancy.HALF_POSITIVE ? "POSITIVE" : "NEGATIVE")
                            + " is empty (material on the other side)");
                }
                cir.setReturnValue(false);
                return;
            }
            // Breaker's half holds the primary: vanilla destroy runs, and the driver's air branch
            // promotes any surviving secondary into the chunk.
            return;
        }
    }
}
