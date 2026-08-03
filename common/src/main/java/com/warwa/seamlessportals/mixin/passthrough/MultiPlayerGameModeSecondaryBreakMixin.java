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
        SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(mc.level, pos);
        if (sec == null) {
            return;
        }
        var seam = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(mc.level, pos);
        if (seam == null) {
            return;
        }
        for (var binding : seam.bindings()) {
            if (binding == null || binding.cut() == null) {
                continue;
            }
            byte playerHalf = SeamOccupancy.halfOfEye(mc.player, pos,
                binding.srcFacing().getAxis(), binding.cut().srcPlaneOffset());
            if (playerHalf == sec.half()) {
                // Predict the SECONDARY's removal only. The vanilla path below this cancel would
                // have removed the primary's blockstate and cascaded into the dest client level.
                SeamOccupancy.setSecondary(mc.level, pos, null);
                cir.setReturnValue(true);
            }
            return;
        }
    }
}
