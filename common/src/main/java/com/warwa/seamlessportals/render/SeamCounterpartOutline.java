package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.passthrough.SeamOccupancy;
import com.warwa.seamlessportals.passthrough.SeamRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;

/**
 * ★ THE WHOLE-OBJECT SELECTION — user order 2026-08-03: "i want the entire seam block to be
 * selected normally as a single block" across both dimensions.
 *
 * <p>A seam object is one block whose material spans two cells in two charts. IP's machinery
 * outlines exactly ONE target — the local hit, or (through a window) the remote hit. This class
 * derives the object's OTHER half every frame from whichever half is targeted, so both light up:
 * <ul>
 *   <li><b>near→far:</b> the local hit is an owned seam cell → {@code far*} names the counterpart
 *       cell in the binding's destination; the portal shells (cross-dim {@code MyGameRenderer}
 *       swap, same-dim outline pass) draw it through the window.</li>
 *   <li><b>far→near:</b> the through-window hit is an owned seam cell → {@code near*} names the
 *       counterpart in the PLAYER's own dimension; the main pass's outline extract picks it up
 *       (the local {@code hitResult} is IP's MISS placeholder then, so nothing competes).</li>
 * </ul>
 *
 * <p>Deliberately SEPARATE fields from {@code BlockManipulationClient.remotePointedDim/
 * remoteHitResult}: those double as the INTERACTION router ({@code isPointingToPortal()} reroutes
 * clicks), and synthesizing them for outline purposes would hijack attacks/uses toward a block the
 * player is not aiming at. These fields feed renderers only; clicks never read them.
 *
 * <p>Render-thread confined (written from the pick tail, read from render passes on the same
 * thread); no synchronization needed.
 */
public final class SeamCounterpartOutline {

    private SeamCounterpartOutline() {}

    /** The far half of a locally-targeted object; null dim = none this frame. */
    public static ResourceKey<Level> farDim = null;
    public static BlockHitResult farHit = null;

    /** The near half of a through-window-targeted object; null = none this frame. */
    public static BlockHitResult nearHit = null;

    /**
     * ★ TRUE while vanilla's {@code extractBlockOutline} is capturing the outline shape (user live
     * round: "extra line in the outline at the seam"). The line is the cut-face rectangle — a
     * half-box outline necessarily draws four edges ON the plane, and a VoxelShape cannot omit
     * edges. During the outline EXTRACT (and only then), seam cells report their FULL shape, so the
     * near pass and the window pass outline coincident full cubes that merge into one normal block
     * box with no internal line. Targeting rays never run inside the extract, so the
     * viewer-half rule — and the far-side break protection built on it — is untouched.
     * Render-thread confined, like the rest of this class.
     */
    public static boolean extractingOutline = false;

    /**
     * ★ Which HALF of the near cell the through-window-targeted object occupies (round 26: the
     * synthetic cell-centre {@code nearHit} cannot pick a side, so a two-object cell targeted at
     * its DEST half outlined only that half — the near extract fell back to the primary). Computed
     * from the REAL remote hit's side of the far plane, mapped through the binding. 0 = unknown.
     */
    public static byte nearHitHalf = 0;

    /** Recompute both directions from the frame's final targeting. Called from the pick tail. */
    public static void update(Minecraft client) {
        farDim = null;
        farHit = null;
        nearHit = null;
        nearHitHalf = 0;
        if (client.level == null || client.player == null) {
            return;
        }
        // near -> far: local hit on an owned seam cell.
        if (client.hitResult instanceof BlockHitResult local
            && local.getType() != HitResult.Type.MISS) {
            var pair = counterpartOf(client.level, local.getBlockPos(), local.getLocation());
            if (pair != null) {
                farDim = pair.dim();
                farHit = pair.hit();
            }
        }
        // far -> near: through-window hit on an owned seam cell; its counterpart in the player's
        // own dimension is the near half.
        if (BlockManipulationClient.remotePointedDim != null
            && BlockManipulationClient.remoteHitResult instanceof BlockHitResult remote
            && remote.getType() != HitResult.Type.MISS) {
            // peekWorld, not getOptionalWorld: the remote world is being RENDERED right now
            // (remotePointedDim is only set while looking through a window), so it must already
            // exist — and getOptionalWorld force-CREATES for known dims, which is how the relog
            // join burst once landed records on a parallel overworld that never became mc.level.
            var remoteLevel = qouteall.imm_ptl.core.ClientWorldLoader
                .peekWorld(BlockManipulationClient.remotePointedDim);
            if (remoteLevel != null) {
                var pair = counterpartOf(remoteLevel, remote.getBlockPos(), remote.getLocation());
                if (pair != null && pair.dim().equals(client.level.dimension())) {
                    nearHit = pair.hit();
                    nearHitHalf = pair.mappedHalf();
                }
            }
        }
    }

    private record Counterpart(ResourceKey<Level> dim, BlockHitResult hit, byte mappedHalf) {}

    private static Counterpart counterpartOf(Level level, BlockPos cell, Vec3 hitLoc) {
        byte owned = SeamOccupancy.occupancyOf(level, cell);
        boolean anyObject = owned != 0 || SeamOccupancy.secondaryOf(level, cell) != null;
        if (!anyObject) {
            return null;
        }
        SeamRegistry.SeamCell seam = SeamRegistry.lookup(level, cell);
        if (seam == null) {
            return null;
        }
        for (SeamRegistry.SeamBinding b : seam.bindings()) {
            if (b == null || !b.isMirrorable() || b.cut() == null || b.destPos() == null) {
                continue;
            }
            // Which half of the COUNTERPART cell continues the targeted object: the real hit's
            // side of this cell's plane, carried through the binding the way the crossing itself
            // is (an object occupies OPPOSITE relative sides at its two cells — claimCrossingHalf's
            // own mapDir(ownedDir.getOpposite()) rule, inverted here from the hit side).
            byte hereHalf = SeamOccupancy.halfFromHit(hitLoc, cell,
                b.srcFacing().getAxis(), b.cut().srcPlaneOffset());
            net.minecraft.core.Direction hereDir = net.minecraft.core.Direction.get(
                hereHalf == SeamOccupancy.HALF_POSITIVE
                    ? net.minecraft.core.Direction.AxisDirection.POSITIVE
                    : net.minecraft.core.Direction.AxisDirection.NEGATIVE,
                b.srcFacing().getAxis());
            byte mappedHalf = SeamOccupancy.halfOf(
                SeamRegistry.mapDir(b, hereDir.getOpposite()));
            // Outline-only hit: position/face are cosmetic (the box comes from the far cell's own
            // half-aware getShape); UP keeps vanilla's face-dependent tinting neutral.
            return new Counterpart(b.destDim(), new BlockHitResult(
                Vec3.atCenterOf(b.destPos()), net.minecraft.core.Direction.UP,
                b.destPos(), false), mappedHalf);
        }
        return null;
    }
}
