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
            ci.cancel();
        }
    }
}
