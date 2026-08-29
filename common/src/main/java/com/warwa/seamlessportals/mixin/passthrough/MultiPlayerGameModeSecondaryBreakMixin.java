package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import com.warwa.seamlessportals.passthrough.SeamOccupancy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ★ THE CLIENT HALF OF SECONDARY BREAK ROUTING — the fix for live round 9's cascade.
 *
 * <p>The server half ({@link ServerPlayerGameModeSecondaryBreakMixin}) routed the break correctly,
 * but the CLIENT predicts breaking too: vanilla {@code MultiPlayerGameMode.destroyBlock} removed
 * the cell's blockstate — the PRIMARY — locally, and the client's break-both prediction then
 * removed the primary's crossing half in the DEST client level. The server corrects the source
 * cell (it sends that block update), but it never touches the dest cell, so no correction is ever
 * sent — leaving a phantom hole in the destination that exists only on this client. Measured live
 * 2026-08-02: "the half of the side A block that is in dest side (b) is broken", followed by a
 * cascade of placement rejections wherever the client's corrupted view disagreed with the server's
 * correct state.
 *
 * <p>Same rule as the server, same discriminator: when the breaking player's eye-side half is the
 * SECONDARY's, remove the secondary locally (prediction — the server's broadcast confirms moments
 * later) and cancel vanilla, so the primary is never predicted-removed and the break-both
 * prediction never fires. Target bytecode-verified: {@code MultiPlayerGameMode.destroyBlock(BlockPos)Z}.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeSecondaryBreakMixin {

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true, require = 1)
    private void seamlessportals$routeSecondaryBreakClient(
        BlockPos pos, CallbackInfoReturnable<Boolean> cir
    ) {
        if (!SeamFractional.active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        var seam = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(mc.level, pos);
        if (seam == null) {
            return;
        }
        SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(mc.level, pos);
        for (var binding : seam.bindings()) {
            if (binding == null || binding.cut() == null) {
                continue;
            }
            // Same unified rule as the server: local = own side, through-window = beyond the far
            // plane, no legitimate line = no break predicted.
            byte playerHalf = SeamFractional.viewerTargetableHalf(
                mc.level, pos, binding, mc.player);
            if (playerHalf == 0) {
                cir.setReturnValue(false);
                return;
            }
            if (playerHalf == SeamOccupancy.BOTH) {
                // Round 22: a side-on viewer holds a BOTH mask — narrow to the half the
                // crosshair actually struck, so the break hits the object being looked at.
                net.minecraft.world.phys.HitResult pick = mc.player.pick(6.0, 1.0f, false);
                if (pick instanceof net.minecraft.world.phys.BlockHitResult pickHit
                    && pickHit.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                    && pickHit.getBlockPos().equals(pos)) {
                    playerHalf = SeamOccupancy.halfFromHit(pickHit.getLocation(), pos,
                        binding.srcFacing().getAxis(), binding.cut().srcPlaneOffset());
                } else {
                    byte owned22 = SeamOccupancy.occupancyOf(mc.level, pos);
                    playerHalf = (owned22 == SeamOccupancy.HALF_POSITIVE
                        || owned22 == SeamOccupancy.HALF_NEGATIVE) ? owned22
                        : SeamOccupancy.halfOfEye(mc.player, pos,
                            binding.srcFacing().getAxis(), binding.cut().srcPlaneOffset());
                }
            }
            if (sec != null && playerHalf == sec.half()) {
                // Predict the SECONDARY's removal only. The vanilla path below this cancel would
                // have removed the primary's blockstate and cascaded into the dest client level.
                SeamOccupancy.setSecondary(mc.level, pos, null);
                // ★ Predict the COUNTERPART fragment's removal too (live round 10, "break
                // mirror has a tiny lag"): the far half otherwise lingers one round-trip until
                // the server broadcast lands. Same-dim: this level; cross-dim: the secondary
                // client level when present. The broadcast confirms/corrects.
                if (binding.isMirrorable() && binding.destPos() != null) {
                    // peekWorld — the loader's per-dim world, the instance the portal view
                    // reads (round 13: the manager's store was NULL cross-dim, prediction dead).
                    net.minecraft.client.multiplayer.ClientLevel farClient =
                        mc.level.dimension().equals(binding.destDim()) ? mc.level
                            : qouteall.imm_ptl.core.ClientWorldLoader.peekWorld(binding.destDim());
                    if (farClient != null) {
                        SeamOccupancy.setSecondary(farClient, binding.destPos(), null);
                    }
                }
                cir.setReturnValue(true);
                return;
            }
            byte owned = SeamOccupancy.occupancyOf(mc.level, pos);
            if ((owned == SeamOccupancy.HALF_POSITIVE || owned == SeamOccupancy.HALF_NEGATIVE)
                && playerHalf != owned) {
                // Single-object cell, breaker's half empty: predict NO break, matching the server's
                // refusal, so the client never phantom-removes a block it cannot legitimately reach.
                cir.setReturnValue(false);
                return;
            }
            return;
        }
    }
}
