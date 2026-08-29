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

    @Shadow
    protected ServerLevel level;

    /**
     * ★ THE LEVEL THE BREAK ACTUALLY TARGETS (live round 8, cross-dim: "breaking nether-a broke
     * the ow-a↔nether-b rail"). The {@code level} FIELD is only correct for the vanilla path: a
     * THROUGH-WINDOW break arrives via IP's {@code BlockManipulationServer}, which swaps the far
     * world in by redirecting the field READS inside {@code destroyBlock}'s own bytecode — a
     * redirect this handler's direct shadow-field access does not go through. Reading the raw
     * field here made the seam lookup run against the PLAYER's level with far-cell coordinates:
     * lookup missed, the routing stood down, and vanilla destroy removed the far cell's chunk
     * primary — the OTHER object — while the aimed fragment survived. Resolve exactly as IP
     * does: the redirect context's world when a cross-portal manipulation is in flight, the
     * field otherwise.
     */
    @org.spongepowered.asm.mixin.Unique
    private ServerLevel seamlessportals$actualLevel() {
        var redirect = qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer
            .REDIRECT_CONTEXT.get();
        return redirect != null ? redirect.world() : this.level;
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true, require = 1)
    private void seamlessportals$routeSecondaryBreak(
        BlockPos pos, CallbackInfoReturnable<Boolean> cir
    ) {
        ServerLevel actual = seamlessportals$actualLevel();
        if (!SeamFractional.active() || actual == null) {
            return;
        }
        var seam = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(actual, pos);
        if (seam == null) {
            return;
        }
        SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(actual, pos);
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
                actual, pos, binding, this.player);
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
                    byte owned22 = SeamOccupancy.occupancyOf(actual, pos);
                    playerHalf = (owned22 == SeamOccupancy.HALF_POSITIVE
                        || owned22 == SeamOccupancy.HALF_NEGATIVE) ? owned22
                        : SeamOccupancy.halfOfEye(this.player, pos,
                            binding.srcFacing().getAxis(), binding.cut().srcPlaneOffset());
                }
            }
            // ★ ROUTING DECISION PROBE (live round 11 — "the second break destroyed the wrong
            // object"): every routed seam break logs its full resolution, so a wrong-object
            // break is attributable from the log instead of reconstructed from promote echoes.
            if (com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                    .SEAM_FRACTIONAL_PROBE) {
                com.warwa.seamlessportals.passthrough.SeamFractionalProbe.onSeamCell(pos,
                    "BREAK-ROUTE", "playerHalf=" + playerHalf
                        + " secHalf=" + (sec == null ? "none" : sec.half())
                        + " owned=" + SeamOccupancy.occupancyOf(actual, pos)
                        + " chunk=" + actual.getBlockState(pos).getBlock()
                        + " viewerAt=" + this.player.blockPosition()
                        + " in " + this.player.level().dimension().identifier()
                        + " -> " + (sec != null && playerHalf == sec.half()
                            ? "SECONDARY" : "PRIMARY/vanilla"));
            }
            if (sec != null && playerHalf == sec.half()) {
                // The breaker is on the second object's side: remove IT, leave the primary.
                // The ROUTED binding rides along so the counterpart clear never depends on a
                // second lookup that can blink (the round-12 phantom-door hole).
                cir.setReturnValue(SeamFractional.breakSecondary(actual, pos, this.player, binding));
                return;
            }
            byte owned = SeamOccupancy.occupancyOf(actual, pos);
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
