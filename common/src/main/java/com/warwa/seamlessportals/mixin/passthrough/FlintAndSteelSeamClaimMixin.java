package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ★ FIRE GETS AN OWNER HALF (user round 28: "if fire is placed on side a source, only source
 * seam a half block has fire ... instead, source seam block b side has the other half of the
 * fire"). Fire is not a {@code BlockItem} — flint &amp; steel writes it via {@code setBlock},
 * bypassing the {@code BlockItem.place} bracket where every other placement claims its half from
 * the crosshair hit. Unclaimed, the cell rendered whole (both halves local) and never crossed.
 * Claim here, at the same moment and from the same hit point a block placement would, on both
 * sides (useOn runs client-side for prediction like BlockItem.place); the mirror's crossing
 * machinery then carries the far fragment to the destination exactly as for any block.
 */
@Mixin(FlintAndSteelItem.class)
public abstract class FlintAndSteelSeamClaimMixin {

    @Inject(method = "useOn", at = @At("RETURN"), require = 1)
    private void seamlessportals$claimIgnitedFire(
        UseOnContext context, CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (!cir.getReturnValue().consumesAction() || !SeamFractional.active()) {
            return;
        }
        BlockPos firePos = context.getClickedPos().relative(context.getClickedFace());
        if (context.getLevel().getBlockState(firePos).getBlock() instanceof BaseFireBlock) {
            SeamFractional.recordPlacement(
                context.getLevel(), firePos, context.getClickLocation());
        }
    }
}
