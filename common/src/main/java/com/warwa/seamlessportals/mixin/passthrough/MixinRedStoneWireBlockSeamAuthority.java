package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamIndexHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RS PASSTHROUGH — <b>MIRROR AUTHORITY for redstone wire</b>: the wire-side twin of
 * {@link MixinBaseRailBlockMirrorAuthority}, closing the hole that stalled the server 42 seconds on
 * 2026-08-10 (live sweep finding F3).
 *
 * <h2>The loop this kills</h2>
 * Dust placed at a seam cell mirrors to the far half; each half's wire evaluator then recomputes
 * POWER from its OWN local-only neighbourhood, so the halves permanently disagree. The mirror
 * half's self-write is classified a refinement at a mirror-created cell and reverted by
 * {@code SeamMirror.revertMirrorHalf} with {@code UPDATE_ALL}, whose neighbour fan-out re-wakes the
 * wire, which re-writes, which is re-reverted — vanilla's 1,000,000-chained-neighbor-updates cap
 * tripped 81 times in the live session (once per completed tick, 20:20:23–20:22:33, server 627→837
 * ticks behind) until the user broke the dust. The rail authority mixin could not help:
 * {@code @Mixin(BaseRailBlock.class)} does not weave wire.
 *
 * <h2>The rule, verbatim from rails</h2>
 * For a cell whose occupant this level RECEIVED from a mirror (provenance —
 * {@link SeamIndexHolder#seamlessportals$mirrorCreatedCells()}), suppress vanilla's
 * self-derivation entirely; the poke is FORWARDED to the counterpart through the tick-end dispatch
 * queue (deduped, budgeted, loop-proof), never evaluated in place — the first rail build evaluated
 * in place and looped ~500k iterations (the volume scar). Two vanilla doors need closing for wire
 * where rails needed one: {@code neighborChanged} (power + survival re-derivation, the loop's
 * engine) and {@code updateShape} (connection-state re-derivation and the support-deletion path —
 * {@code UPDATE_SKIP_ON_PLACE} covers {@code onPlace} only, not the shape channel). The mirrored
 * half's POWER and connection state come exclusively from shape sync of the player half.
 *
 * <p><b>KNOWN GAMEPLAY CONSEQUENCE, deliberate (same as rails):</b> mirrored dust no longer needs
 * support at the destination. The alternative is the destination deleting a block it did not
 * author, with the same one-block-two-drops duplication shape rails had.
 */
@Mixin(RedStoneWireBlock.class)
public abstract class MixinRedStoneWireBlockSeamAuthority {

    /** One-shot liveness latch — proves the hook actually weaves and fires, rather than assuming it. */
    @org.spongepowered.asm.mixin.Unique
    private static boolean seamlessportals$loggedFirstCancel = false;

    // require = 1, NOT 0 — a renamed or mis-signatured target must fail at load, not silently
    // not-exist (the failure mode that produced five false instrument readings this engagement).
    @Inject(
        method = "neighborChanged",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void seamlessportals$mirrorIsAuthoritative(
        BlockState state, Level level, BlockPos pos, Block block,
        @Nullable Orientation orientation, boolean movedByPiston, CallbackInfo ci
    ) {
        if (!seamlessportals$suppressAt(level, pos)) {
            return;
        }
        if (!seamlessportals$loggedFirstCancel) {
            seamlessportals$loggedFirstCancel = true;
            com.mojang.logging.LogUtils.getLogger().info(
                "[RS-WIRE-AUTHORITY] LIVE — suppressed vanilla re-derivation of mirrored dust"
                    + " at {} in {} (power recompute, survival deletion and the revert loop all"
                    + " blocked). One-shot; the hook is weaving and firing.",
                pos, level.dimension().identifier());
        }
        // POWER-WAKE, exactly as rails: the cancelled notification still matters — a signal
        // arriving from the mirror half's side must wake the PLAYER half, which re-derives with
        // the bridged reads and shape-syncs back. Forward, never evaluate here.
        if (!AperturePassthroughLever.DISABLE_SEAM_SIGNAL
            && !AperturePassthroughLever.DISABLE_SEAM_POWER_WAKE
            && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            if (AperturePassthroughLever.SEAM_SIGNAL_PROBE) {
                com.mojang.logging.LogUtils.getLogger().info(
                    "[RS-WIRE] poke at mirrored dust {} in {} — counterpart queued",
                    pos, level.dimension().identifier());
            }
            com.warwa.seamlessportals.passthrough.SeamSignalContinuity
                .onSeamCellChanged(serverLevel, pos, level.getBlockState(pos));
        }
        ci.cancel();
    }

    // The shape channel: updateShape re-derives connection state (and deletes unsupported wire via
    // the DOWN-direction survival check) against the DESTINATION's neighbours. At a marked cell the
    // pair's state is the player half's business — answer "unchanged".
    @Inject(
        method = "updateShape",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void seamlessportals$mirrorShapeIsAuthoritative(
        BlockState state, LevelReader levelReader, ScheduledTickAccess tickAccess, BlockPos pos,
        Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random,
        CallbackInfoReturnable<BlockState> cir
    ) {
        if (levelReader instanceof Level level && seamlessportals$suppressAt(level, pos)) {
            cir.setReturnValue(state);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean seamlessportals$suppressAt(Level level, BlockPos pos) {
        if (!SeamlessPortalsConfig.isEntityPortals()) {
            return false;
        }
        if (AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_MIRROR_AUTHORITY
            || AperturePassthroughLever.DISABLE_SEAM_WIRE) {
            return false;
        }
        if (level.isClientSide()) {
            return false;
        }
        // Fast path: one field read plus a contains() on a set that is empty in any world whose
        // portals have never mirrored anything.
        SeamIndexHolder holder = (SeamIndexHolder) level;
        return !holder.seamlessportals$mirrorCreatedCells().isEmpty()
            && holder.seamlessportals$mirrorCreatedCells().contains(pos.asLong());
    }
}
