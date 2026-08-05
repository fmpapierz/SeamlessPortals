package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import com.warwa.seamlessportals.passthrough.SeamWriteContext;
import com.warwa.seamlessportals.passthrough.SeamWriteSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ★ FIRE GETS THE FULL PLACEMENT BRACKET (rounds 28→29). Fire is not a {@code BlockItem}: flint
 * &amp; steel writes it via {@code setBlock}, so it missed BOTH halves of what
 * {@code BlockItem.place} provides — the owner-half CLAIM and the PLAYER_PLACE write
 * classification. Round 28 added only the claim, and at RETURN: too late and not enough — the
 * player-only mirror policy had already DECLINED the unclassified write, so the far fragment
 * never crossed ("does not paint on dest side B"). The order is load-bearing, same as the
 * BlockItem bracket: the mirror derives the crossing half FROM the source claim at write time.
 * <ol>
 *   <li>HEAD: claim from the click point (no-ops off seam cells);</li>
 *   <li>both {@code setBlock} writes carry PLAYER_PLACE (the campfire/candle-lighting branch
 *       rides along harmlessly);</li>
 *   <li>RETURN: release the claim if the use did not go through.</li>
 * </ol>
 */
@Mixin(FlintAndSteelItem.class)
public abstract class FlintAndSteelSeamClaimMixin {

    @Inject(method = "useOn", at = @At("HEAD"), require = 1)
    private void seamlessportals$claimBeforeIgnition(
        UseOnContext context, CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (!SeamFractional.active()) {
            return;
        }
        BlockPos firePos = context.getClickedPos().relative(context.getClickedFace());
        SeamFractional.recordPlacement(
            context.getLevel(), firePos, context.getClickLocation());
    }

    @Redirect(
        method = "useOn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock("
                + "Lnet/minecraft/core/BlockPos;"
                + "Lnet/minecraft/world/level/block/state/BlockState;I)Z"
        )
    )
    private boolean seamlessportals$igniteAsPlayerPlace(
        Level level, BlockPos pos, BlockState state, int flags
    ) {
        Object[] saved = SeamWriteContext.push(SeamWriteSource.PLAYER_PLACE, pos);
        try {
            return level.setBlock(pos, state, flags);
        } finally {
            SeamWriteContext.pop(saved);
        }
    }

    @Inject(method = "useOn", at = @At("RETURN"), require = 1)
    private void seamlessportals$releaseOnFail(
        UseOnContext context, CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (!SeamFractional.active() || cir.getReturnValue().consumesAction()) {
            return;
        }
        BlockPos firePos = context.getClickedPos().relative(context.getClickedFace());
        SeamFractional.forgetPlacement(context.getLevel(), firePos);
    }
}
