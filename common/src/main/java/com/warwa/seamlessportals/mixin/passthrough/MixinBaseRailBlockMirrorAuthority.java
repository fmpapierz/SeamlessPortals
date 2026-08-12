package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamIndexHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RS PASSTHROUGH — <b>MIRROR AUTHORITY</b>: a mirrored cell's shape and validity are the SOURCE
 * cell's; the destination must not independently re-derive or delete a block it did not author.
 *
 * <h2>Why this exists — the derived-state family</h2>
 * (a) mirrors BLOCK STATE, and block state is not an inert value: the game re-derives it. Three
 * defects came from under-modelling that, and {@code UPDATE_SKIP_ON_PLACE} closed only the
 * placement-time re-derive. {@code BaseRailBlock.neighborChanged} is the remaining hole, and it does
 * TWO damaging things to a mirrored copy:
 *
 * <ol>
 *   <li><b>Re-resolves the shape</b> — {@code updateState → updateDir → RailState.place} against the
 *       DESTINATION dimension's neighbours, which are unrelated to the source's. The two coincident
 *       halves then hold different shapes, which on an obsidian portal renders as a straight rail and
 *       a curved rail on the same block. User-observed, and after the placement-time fix the user
 *       still reported "sometimes not curving" — this is that residue.</li>
 *   <li><b>Deletes it, and DUPLICATES THE ITEM</b> — {@code shouldBeRemoved} evaluates
 *       {@code canSupportRigidBlock} at the DESTINATION's {@code pos.below()}, then calls
 *       {@code dropResources} + {@code removeBlock}. The source rail still exists, so one placement
 *       becomes one block plus one dropped item. The two dimensions have unrelated terrain, so a
 *       mirrored cell lacking support is ordinary, not exotic.</li>
 * </ol>
 *
 * <p><b>The rule closes both with one condition</b> rather than patching each path: for a cell whose
 * occupant this level RECEIVED from a mirror (provenance —
 * {@link SeamIndexHolder#seamlessportals$mirrorCreatedCells()}), suppress vanilla's self-derivation
 * entirely. The source side already re-mirrors on every change, so the copy stays correct.
 *
 * <p><b>KNOWN GAMEPLAY CONSEQUENCE, deliberate:</b> a mirrored rail no longer needs support in the
 * destination — it can hang over a hole in the nether. That is the cost of refusing to let the
 * destination delete it. The alternative is a live item-duplication exploit, which is strictly worse;
 * {@code -Dseamlessportals.disableMirrorAuthority=true} restores vanilla behaviour, duplication and
 * all, for anyone who prefers it.
 */
@Mixin(BaseRailBlock.class)
public abstract class MixinBaseRailBlockMirrorAuthority {

    /** One-shot liveness latch — proves the hook actually weaves and fires, rather than assuming it. */
    @org.spongepowered.asm.mixin.Unique
    private static boolean seamlessportals$loggedFirstCancel = false;

    // require = 1, NOT 0. With require = 0 a renamed or mis-signatured target weaves NOTHING and the
    // feature silently does not exist, which is precisely the failure mode that has produced five
    // false instrument readings this engagement. Fail at load instead.
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
        if (!SeamlessPortalsConfig.isEntityPortals()) {
            return;
        }
        if (AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_MIRROR_AUTHORITY) {
            return;
        }
        if (level.isClientSide()) {
            return;
        }
        // Fast path: one field read plus a contains() on a set that is empty in any world whose
        // portals have never mirrored anything.
        SeamIndexHolder holder = (SeamIndexHolder) level;
        if (holder.seamlessportals$mirrorCreatedCells().isEmpty()) {
            return;
        }
        if (holder.seamlessportals$mirrorCreatedCells().contains(pos.asLong())) {
            if (!seamlessportals$loggedFirstCancel) {
                seamlessportals$loggedFirstCancel = true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "[RS-MIRROR-AUTHORITY] LIVE — suppressed vanilla re-derivation of a mirrored rail"
                        + " at {} in {} (shape re-resolution and support-deletion both blocked)."
                        + " One-shot; the hook is weaving and firing.",
                    pos, level.dimension().identifier());
            }
            // ★ (c) POWER-WAKE (found live 2026-07-28 — the user's seam-stop). This suppression
            // also ate the POWER question: a signal ENTERING a coincident pair from the MIRROR
            // half's side died at the seam — the approach rail's notification was cancelled here,
            // the mirror half never reacted, no cross-dispatch fired, and the player half was
            // never woken ("stops at the first half of the seam rail"; the break-and-replace
            // ritual worked only because placement-time evaluation runs before provenance lands).
            //
            // THE FIX FORWARDS THE POKE, IT DOES NOT EVALUATE HERE. The first build ran the
            // mirror half's own power evaluation in place — and looped: flip → authority revert →
            // updateNeighborsAt(pos.below()) re-notifies this very rail → wake again, ~500k
            // same-drain iterations until vanilla's chain cap broke it. The mirror half must not
            // WRITE at all: the poke is queued to the COUNTERPART (the player half) through the
            // tick-end dispatch queue — deduped per tick, budgeted, loop-proof — and the player
            // half re-derives with the bridged union/walk, then shape sync copies back. The
            // authority rule stays fully intact: nothing here writes the mirrored cell.
            // Lever: -Dseamlessportals.disableSeamPowerWake.
            if (!AperturePassthroughLever.DISABLE_SEAM_SIGNAL
                && !AperturePassthroughLever.DISABLE_SEAM_POWER_WAKE
                && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                if (AperturePassthroughLever.SEAM_SIGNAL_PROBE) {
                    com.mojang.logging.LogUtils.getLogger().info(
                        "[RS-SIGNAL] poke at mirrored rail {} in {} — counterpart queued",
                        pos, level.dimension().identifier());
                }
                com.warwa.seamlessportals.passthrough.SeamSignalContinuity
                    .onSeamCellChanged(serverLevel, pos, level.getBlockState(pos));
            }
            // F4: the cancelled cell may host the OTHER object's side-table fragment (a second
            // rail sharing the cell) — the poke is its only wake-up; re-derive it here.
            if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                com.warwa.seamlessportals.passthrough.SeamWireBridge.refreshSecondary(sl, pos);
            }
            ci.cancel();
        }
    }

    // F4 — the unmarked path's fragment wake, the rail twin of the wire authority's TAIL hook.
    @Inject(method = "neighborChanged", at = @At("TAIL"), require = 1)
    private void seamlessportals$refreshSecondaryFragment(
        BlockState state, Level level, BlockPos pos, Block block,
        @Nullable Orientation orientation, boolean movedByPiston, CallbackInfo ci
    ) {
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            com.warwa.seamlessportals.passthrough.SeamWireBridge.refreshSecondary(sl, pos);
        }
    }
}
