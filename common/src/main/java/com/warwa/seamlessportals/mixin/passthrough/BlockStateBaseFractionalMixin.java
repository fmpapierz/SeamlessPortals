package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ★ TIER (i) OF THE FRACTIONAL SEAM MODEL — a seam block's geometry ends at the plane.
 * Spec: {@code migration/FRACTIONAL_DESIGN.md} §2a/§4/§5.
 *
 * <h2>Why only three methods, when the map listed nine</h2>
 *
 * <p>The user's rule (2026-08-02) is that <b>collision follows the cut while support, redstone
 * conduction and suffocation report WHOLE</b> — the soul sand shape, applied generically. Support
 * and conduction read the per-blockstate {@code Cache}, which is position-blind by construction
 * ({@code BlockBehaviour.java:901-925}), so they <b>already</b> answer "whole" with no interception.
 * The cache-blindness the blast-radius map called a trap is, under this rule, precisely the
 * behaviour we want. So tier (ii) needs no hook, and only the position-aware tier (i) is touched.
 *
 * <p>The three targets, all verified against the bytecode
 * ({@code javap -p ... BlockBehaviour$BlockStateBase}) before this file was written:
 * <ul>
 *   <li>{@code getCollisionShape(BlockGetter, BlockPos, CollisionContext)} — the 3-arg form, which
 *       has <b>no cache branch even for an empty context</b>. This is the entity-movement funnel
 *       ({@code BlockCollisions:93} → {@code EntityCollisionContext:64}), plus
 *       {@code ClipContext.Block.COLLIDER} and block picking. It cannot be defeated.</li>
 *   <li>{@code getShape(BlockGetter, BlockPos, CollisionContext)} — the OUTLINE
 *       ({@code ClipContext.Block.OUTLINE}). <b>Required, not cosmetic:</b> "adjacent" means the
 *       player clicked the face of an already-offset block (§2a.2), so they must be able to see and
 *       aim at that face. A full-cube outline over a cut block means clicking a face that is not
 *       where the game drew it, and the whole placement rule falls apart.</li>
 *   <li>{@code isSuffocating(BlockGetter, BlockPos)} — the one deliberate divergence from soul sand.
 *       Its missing 2/16 is ordinary air in the same world; a seam cell's missing part is a doorway
 *       the player is meant to walk through, and reporting whole would damage them for using the
 *       portal.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 *
 * <p>Every injection short-circuits on one static boolean read when the model is off, and on one
 * {@code instanceof} when the {@code BlockGetter} is not a {@code Level} — which it very often is
 * not, since the compile path hands section copies and the blockstate cache uses
 * {@code EmptyBlockGetter}. That is the house standard for a hot-path hook.
 *
 * <p>⚠ Deliberately NOT hooked: the 2-arg {@code getCollisionShape}, {@code isFaceSturdy},
 * {@code isCollisionShapeFullBlock}, {@code getBlockSupportShape} and {@code isRedstoneConductor}.
 * Under the soul-sand rule their cached whole-block answers are correct, and hooking them is how
 * rails would start popping off every fractional seam cell — the behaviour the user replaced.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseFractionalMixin {

    @Inject(
        method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private void seamlessportals$cutCollisionShape(
        BlockGetter level, BlockPos pos, CollisionContext context,
        CallbackInfoReturnable<VoxelShape> cir
    ) {
        VoxelShape original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        VoxelShape cut = SeamFractional.keptShape(level, pos, original);
        if (cut != null) {
            cir.setReturnValue(cut);
        }
    }

    @Inject(
        method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private void seamlessportals$cutOutlineShape(
        BlockGetter level, BlockPos pos, CollisionContext context,
        CallbackInfoReturnable<VoxelShape> cir
    ) {
        VoxelShape original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        // ★ VIEWER-DEPENDENT, deliberately — the targeting rule (user live round 8): an entity's
        // outline for a seam cell includes ONLY the half on ITS side of the plane. From the empty
        // side there is nothing to see or hit and the ray passes; from your side you target exactly
        // your occupant. The picking entity rides in the CollisionContext, which vanilla plumbs
        // through ClipContext.Block.OUTLINE.
        VoxelShape cut = SeamFractional.outlineShape(level, pos, original, context);
        if (cut != null) {
            cir.setReturnValue(cut);
        }
    }

    @Inject(
        method = "isSuffocating(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private void seamlessportals$noSuffocationInADoorway(
        BlockGetter level, BlockPos pos, CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValueZ() && SeamFractional.suppressesSuffocation(level, pos)) {
            cir.setReturnValue(false);
        }
    }

    // ★ ROUND 27 — the "deliberately NOT hooked" list above SHRANK by user decision revision:
    // conduction now follows the cut (a cut cell relays vanilla signal only out of its owned-side
    // axis face; the seam signal bridge owns cross-seam transmission), and isFaceSturdy consults
    // the SECOND occupant so a non-sturdy primary cannot veto placements resting on the solid
    // second object. isRedstoneConductor / isCollisionShapeFullBlock / getBlockSupportShape remain
    // unhooked — their whole-cube answers are still what keeps rails and dust ON the seam cell
    // alive (the original soul-sand reading).

    @Inject(
        method = "getSignal(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)I",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private void seamlessportals$signalFollowsTheCut(
        BlockGetter level, BlockPos pos, net.minecraft.core.Direction direction,
        CallbackInfoReturnable<Integer> cir
    ) {
        if (cir.getReturnValueI() > 0
            && SeamFractional.blocksSignalTowards(level, pos, direction)) {
            cir.setReturnValue(0);
        }
    }

    @Inject(
        method = "getDirectSignal(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)I",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private void seamlessportals$directSignalFollowsTheCut(
        BlockGetter level, BlockPos pos, net.minecraft.core.Direction direction,
        CallbackInfoReturnable<Integer> cir
    ) {
        if (cir.getReturnValueI() > 0
            && SeamFractional.blocksSignalTowards(level, pos, direction)) {
            cir.setReturnValue(0);
        }
    }

    @Inject(
        method = "isFaceSturdy(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/SupportType;)Z",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private void seamlessportals$eitherOccupantSturdy(
        BlockGetter level, BlockPos pos, net.minecraft.core.Direction direction,
        net.minecraft.world.level.block.SupportType type,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()
            && SeamFractional.secondarySturdy(level, pos, direction, type)) {
            cir.setReturnValue(true);
        }
    }
}
