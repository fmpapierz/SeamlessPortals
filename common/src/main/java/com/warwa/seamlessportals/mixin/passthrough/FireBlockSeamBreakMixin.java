package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import com.warwa.seamlessportals.passthrough.SeamWriteContext;
import com.warwa.seamlessportals.passthrough.SeamWriteSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    /**
     * ★ ROUND 38 — fire's OWN LIFECYCLE writes carry the classification too ("far side seam
     * half flame not disappearing when source side seam half runs out"): self-extinguish and
     * age-out go through raw removeBlock/setBlock in FireBlock.tick — unclassified, so the
     * player-only mirror policy declined them and the dest half orphaned. Removals classify as
     * PLAYER_BREAK (mirror-clears the far half); state updates as PLAYER_PLACE (which also
     * carries the fire's age across the seam). No ordinal: every matching site in tick() is a
     * fire lifecycle write and gets the same treatment.
     */
    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;removeBlock("
                + "Lnet/minecraft/core/BlockPos;Z)Z"
        )
    )
    private boolean seamlessportals$extinguishLikeAPlayer(
        net.minecraft.server.level.ServerLevel level, BlockPos pos, boolean movedByPiston
    ) {
        Object[] saved = SeamWriteContext.push(SeamWriteSource.PLAYER_BREAK, pos);
        try {
            return level.removeBlock(pos, movedByPiston);
        } finally {
            SeamWriteContext.pop(saved);
        }
    }

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlock("
                + "Lnet/minecraft/core/BlockPos;"
                + "Lnet/minecraft/world/level/block/state/BlockState;I)Z"
        )
    )
    private boolean seamlessportals$ageLikeAPlayer(
        net.minecraft.server.level.ServerLevel level, BlockPos pos, BlockState state, int flags
    ) {
        Object[] saved = SeamWriteContext.push(
            state.isAir() ? SeamWriteSource.PLAYER_BREAK : SeamWriteSource.PLAYER_PLACE, pos);
        try {
            return level.setBlock(pos, state, flags);
        } finally {
            SeamWriteContext.pop(saved);
        }
    }

    /**
     * ★ ROUND 30 — a seam-claimed fire survives only if the occupant of ITS half below is
     * sturdy ("sometimes seam flame not breaking if underlying block is broken — fire just
     * floats there": round 27's either-occupant support let the other object vouch for a fire
     * whose own footing was gone). null = not a seam case, keep vanilla's verdict.
     */
    @Inject(method = "canSurvive", at = @At("RETURN"), cancellable = true, require = 1)
    private void seamlessportals$surviveOnOwnHalfOnly(
        BlockState state, LevelReader level, BlockPos pos,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        Boolean seamVerdict = SeamFractional.fireSupportedOnOwnHalf(level, pos);
        if (seamVerdict != null && !seamVerdict) {
            cir.setReturnValue(false);
        }
    }
}
