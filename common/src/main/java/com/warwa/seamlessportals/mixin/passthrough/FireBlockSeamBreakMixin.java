package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.SeamWriteContext;
import com.warwa.seamlessportals.passthrough.SeamWriteSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ★ FIRE BREAKS LIKE A PLAYER (user round 28: "when fire breaks seam blocks, the breaking
 * behavior is not correct, make sure it follows the same rules as player breaking"). Fire's burn
 * consumes blocks via raw {@code setBlock}/{@code removeBlock} inside {@code checkBurnOut} — an
 * unclassified WORLD write, which the player-only mirror policy declines, so a burned seam half
 * left its far fragment orphaned instead of running break-both + promote. Both burn writes are
 * wrapped in the PLAYER_BREAK classification (user decision, this round: fire-breaking follows
 * the player-break rules), which routes them through the exact same seam machinery a pickaxe
 * uses — origin clear, counterpart clear, surviving secondaries promoted on both sides.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockSeamBreakMixin {

    @Redirect(
        method = "checkBurnOut",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;removeBlock("
                + "Lnet/minecraft/core/BlockPos;Z)Z"
        )
    )
    private boolean seamlessportals$burnRemovesLikeAPlayer(
        Level level, BlockPos pos, boolean movedByPiston
    ) {
        Object[] saved = SeamWriteContext.push(SeamWriteSource.PLAYER_BREAK, pos);
        try {
            return level.removeBlock(pos, movedByPiston);
        } finally {
            SeamWriteContext.pop(saved);
        }
    }

    @Redirect(
        method = "checkBurnOut",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock("
                + "Lnet/minecraft/core/BlockPos;"
                + "Lnet/minecraft/world/level/block/state/BlockState;I)Z"
        )
    )
    private boolean seamlessportals$burnReplacesLikeAPlayer(
        Level level, BlockPos pos, BlockState state, int flags
    ) {
        Object[] saved = SeamWriteContext.push(SeamWriteSource.PLAYER_BREAK, pos);
        try {
            return level.setBlock(pos, state, flags);
        } finally {
            SeamWriteContext.pop(saved);
        }
    }
}
