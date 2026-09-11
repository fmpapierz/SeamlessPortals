package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamFractional;
import com.warwa.seamlessportals.passthrough.SeamOccupancy;
import com.warwa.seamlessportals.passthrough.SeamOccupancyClient;
import com.warwa.seamlessportals.passthrough.SeamRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * ★ SEAM CRUMB BURST (landed 2026-09-11, user-verified "break particles show on both
 * sides") — a governed seam cell's destroy burst, both ends.
 *
 * <p>DEST side (revert with -Dseamlessportals.disableSeamCrumbReplay=true): the
 * counterpart's burst is server-sent (SeamMirror's {@code sendParticles} — the silent
 * {@code setBlockAndUpdate} clear fires no levelEvent), and that only reaches players
 * standing IN the dest level near the counterpart. The breaker looking through a cross-dim
 * window never gets it. The replay is CLIENT-side, driven by the breaking side's own
 * destroy burst ({@code ClientLevel.addDestroyBlockEffect} HEAD): for each mirrorable
 * binding of the broken cell, the vanilla crumb grid (26.2 ClientLevel.java:1011-1046 —
 * shape-driven, so the fractional cut scopes it to the broken half) is re-spawned
 * TRANSFORMED into the binding's destination: positions through the seam mapping,
 * velocities through the same horizontal direction map, particles constructed against the
 * DEST ClientLevel (the ctor's level argument IS the multi-world tag) and added
 * engine-direct — no camera gate, no packets. Dest-tagged particles render through windows
 * via the isolated dest extract ({@code MixinParticleEngine.ip_extractIsolated}); the
 * widened mirrorable valve in RenderStates admits counterpart-cell positions there.
 *
 * <p>LOCAL side — FULL DENSITY (2026-09-11 density ruling, all-green tint round: nothing
 * was being consumed, the burst was simply a HALF-shape grid at vanilla spacing = half a
 * block's crumbs; revert with -Dseamlessportals.disableSeamCrumbDensity=true): for
 * governed cells the vanilla grid is cancelled (mixin HEAD) and replaced by the identical
 * loop with the CUT axis's crumb spacing halved (0.25 → 0.125), so each end's half-shape
 * burst carries a full block's count (4x4x2 → 4x4x4 for the half-cell cut). Only the cut
 * axis densifies — the other axes already span the full cell. The replay grid uses the
 * same rule, so both ends match. (User order 2026-09-11: this doubled-count build is the
 * kept baseline — "go all the way back to when we just doubled the particle count".)
 */
public final class SeamCrumbReplay {

    private SeamCrumbReplay() {}

    /** Vanilla crumb spacing is 0.25/axis; a cut axis halves it so the half carries a full
     *  block's count. */
    private static int gridCount(double width, boolean cutAxis) {
        return Math.max(2, Mth.ceil(width / (cutAxis ? 0.125 : 0.25)));
    }

    /**
     * The seam burst for a governed cell: dest-side replay plus the densified local grid.
     *
     * @return true when the local burst was spawned here at full density and vanilla's own
     *         sparse grid must be cancelled ({@code ClientLevelDestroyEffectSeamMixin}).
     */
    public static boolean onDestroyBurst(ClientLevel level, BlockPos pos, BlockState state) {
        if (!SeamFractional.active()) {
            return false;
        }
        // The vanilla gates this burst mirrors (vanilla no-ops identically when they fail).
        if (state.isAir() || !state.shouldSpawnTerrainParticles()) {
            return false;
        }
        SeamRegistry.SeamCell seam = SeamRegistry.lookup(level, pos);
        if (seam == null) {
            return false;
        }
        boolean cutX = false, cutY = false, cutZ = false;
        SeamRegistry.SeamBinding firstCut = null;
        for (SeamRegistry.SeamBinding b : seam.bindings()) {
            if (b == null || !b.isMirrorable() || b.cut() == null) {
                continue;
            }
            if (firstCut == null) {
                firstCut = b;
            }
            switch (b.srcFacing().getAxis()) {
                case X -> cutX = true;
                case Y -> cutY = true;
                case Z -> cutZ = true;
            }
        }
        if (firstCut == null) {
            return false;
        }
        // ★ THE BURST SHAPE IS THE BROKEN HALF, RESOLVED — NOT the hooked shape (2026-09-11,
        // the both-sides-occupied break: "opposite side seam block blocks my side break
        // particles"). The fractional shape hook is POSITION-KEYED and state-blind — it
        // serves the cell's CURRENT kept half, and after breaking one half of a BOTH cell
        // that is the SURVIVING half: the whole grid spawned buried inside the remaining
        // block. So the volume is built directly: the state's context-free raw shape
        // (EmptyBlockGetter bypasses the hook) cut to the half the broken block actually
        // occupied, resolved in any packet ordering by resolveBrokenHalf. Unresolvable →
        // the hooked shape as before (not a break flow).
        Direction.Axis cutAxis = firstCut.srcFacing().getAxis();
        double cutOff = firstCut.cut().srcPlaneOffset();
        byte brokenHalf = resolveBrokenHalf(level, pos, state, cutAxis, cutOff);
        VoxelShape shape;
        if (brokenHalf != 0) {
            VoxelShape raw = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            shape = Shapes.joinUnoptimized(raw, halfBox(cutAxis, cutOff, brokenHalf),
                BooleanOp.AND);
        } else {
            shape = state.getShape(level, pos);
        }
        // ★ CRUMB-BURST probe (2026-09-11 both-sides instrument round): the full resolution
        // ladder's inputs and outcome, one line per governed break.
        if (SeamParticleProbe.armed()) {
            SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(level, pos);
            var player = Minecraft.getInstance().player;
            qouteall.q_misc_util.Helper.log(String.format(
                "[CRUMB-BURST] pos=%s dim=%s axis=%s off=%.2f state=%s | stash=%d occ=%d"
                    + " primary=%s sec=%s eye=%d -> brokenHalf=%d shapeEmpty=%s boxes=%d",
                pos, level.dimension().identifier(), cutAxis, cutOff,
                state.getBlock().getDescriptionId(),
                SeamOccupancyClient.recentClearedMask(level, pos.asLong()),
                SeamOccupancy.occupancyOf(level, pos),
                level.getBlockState(pos).getBlock().getDescriptionId(),
                sec == null ? "null"
                    : sec.state().getBlock().getDescriptionId() + "/h" + sec.half(),
                player == null ? -1
                    : SeamOccupancy.halfOfEye(player, pos, cutAxis, cutOff),
                brokenHalf, shape.isEmpty(), shape.toAabbs().size()));
        }
        if (shape.isEmpty()) {
            return false;
        }
        if (!AperturePassthroughLever.DISABLE_SEAM_CRUMB_REPLAY) {
            replayIntoDestinations(level, pos, state, seam, shape);
        }
        if (AperturePassthroughLever.DISABLE_SEAM_CRUMB_DENSITY) {
            return false;   // vanilla spawns the sparse local grid; the replay above stands
        }
        spawnLocalGrid(level, pos, state, shape, cutX, cutY, cutZ);
        return true;
    }

    /**
     * ★ The SECONDARY break's burst (2026-09-11, "opposite side seam block blocks my side
     * break particles" — probe round): a second-object removal is a side-table operation
     * with NO vanilla destroy, so {@code addDestroyBlockEffect} (and this class's normal
     * entry) never runs — its only particles were the server's 20-crumb CENTER scatter,
     * half of them buried in the surviving primary and the rest consumed unrested. The
     * client secondary-break prediction calls this with the removed object's exact state
     * and half: the same densified grid locally plus the reflected dest replay, at parity
     * with a primary seam break. (The caller stashes the cleared half first so the sided
     * rest holds the crumbs.)
     */
    public static void secondaryBurst(ClientLevel level, BlockPos pos, BlockState state,
        byte half) {
        if (!SeamFractional.active()) {
            return;
        }
        if (state.isAir() || !state.shouldSpawnTerrainParticles()) {
            return;
        }
        SeamRegistry.SeamCell seam = SeamRegistry.lookup(level, pos);
        if (seam == null) {
            return;
        }
        boolean cutX = false, cutY = false, cutZ = false;
        SeamRegistry.SeamBinding firstCut = null;
        for (SeamRegistry.SeamBinding b : seam.bindings()) {
            if (b == null || !b.isMirrorable() || b.cut() == null) {
                continue;
            }
            if (firstCut == null) {
                firstCut = b;
            }
            switch (b.srcFacing().getAxis()) {
                case X -> cutX = true;
                case Y -> cutY = true;
                case Z -> cutZ = true;
            }
        }
        if (firstCut == null) {
            return;
        }
        VoxelShape raw = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        VoxelShape shape = Shapes.joinUnoptimized(raw,
            halfBox(firstCut.srcFacing().getAxis(), firstCut.cut().srcPlaneOffset(), half),
            BooleanOp.AND);
        if (SeamParticleProbe.armed()) {
            qouteall.q_misc_util.Helper.log(String.format(
                "[CRUMB-BURST] SECONDARY pos=%s dim=%s half=%d state=%s shapeEmpty=%s boxes=%d",
                pos, level.dimension().identifier(), half,
                state.getBlock().getDescriptionId(), shape.isEmpty(), shape.toAabbs().size()));
        }
        if (shape.isEmpty()) {
            return;
        }
        spawnLocalGrid(level, pos, state, shape, cutX, cutY, cutZ);
        if (!AperturePassthroughLever.DISABLE_SEAM_CRUMB_REPLAY) {
            replayIntoDestinations(level, pos, state, seam, shape);
        }
    }

    /** Which half of the cell the broken state occupied — resolved in any packet ordering. */
    private static byte resolveBrokenHalf(ClientLevel level, BlockPos pos, BlockState state,
        Direction.Axis axis, double off) {
        byte stash = SeamOccupancyClient.recentClearedMask(level, pos.asLong());
        if (stash != 0) {
            return stash;   // the occupancy update already applied and named the half
        }
        byte owned = SeamOccupancy.occupancyOf(level, pos);
        if (owned == SeamOccupancy.HALF_POSITIVE || owned == SeamOccupancy.HALF_NEGATIVE) {
            return owned;   // burst-first, single occupant: the broken block IS the occupant
        }
        if (owned != 0) {
            SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(level, pos);
            if (sec != null) {
                boolean matchesSecondary = state == sec.state();
                boolean matchesPrimary = state == level.getBlockState(pos);
                if (matchesPrimary && !matchesSecondary) {
                    return SeamOccupancy.otherHalf(sec.half());
                }
                if (matchesSecondary && !matchesPrimary) {
                    return sec.half();
                }
            }
            // Same type in both halves (the completion gesture) — the breaker's eye side
            // names the half: the targeting rule only lets a player punch THEIR side.
            var player = Minecraft.getInstance().player;
            if (player != null) {
                return SeamOccupancy.halfOfEye(player, pos, axis, off);
            }
        }
        return 0;
    }

    private static VoxelShape halfBox(Direction.Axis axis, double off, byte half) {
        boolean positive = half == SeamOccupancy.HALF_POSITIVE;
        return switch (axis) {
            case X -> Shapes.box(positive ? off : 0, 0, 0, positive ? 1 : off, 1, 1);
            case Y -> Shapes.box(0, positive ? off : 0, 0, 1, positive ? 1 : off, 1);
            case Z -> Shapes.box(0, 0, positive ? off : 0, 1, 1, positive ? 1 : off);
        };
    }

    /** Vanilla's exact local grid — same positions, velocities and ctor — at cut-axis
     *  spacing 0.125. */
    private static void spawnLocalGrid(ClientLevel level, BlockPos pos, BlockState state,
        VoxelShape shape, boolean cutX, boolean cutY, boolean cutZ) {
        shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> {
            double widthX = Math.min(1.0, x2 - x1);
            double widthY = Math.min(1.0, y2 - y1);
            double widthZ = Math.min(1.0, z2 - z1);
            int countX = gridCount(widthX, cutX);
            int countY = gridCount(widthY, cutY);
            int countZ = gridCount(widthZ, cutZ);
            for (int xx = 0; xx < countX; xx++) {
                for (int yy = 0; yy < countY; yy++) {
                    for (int zz = 0; zz < countZ; zz++) {
                        double relX = (xx + 0.5) / countX;
                        double relY = (yy + 0.5) / countY;
                        double relZ = (zz + 0.5) / countZ;
                        double lx = relX * widthX + x1;
                        double ly = relY * widthY + y1;
                        double lz = relZ * widthZ + z1;
                        Minecraft.getInstance().particleEngine.add(new TerrainParticle(
                            level, pos.getX() + lx, pos.getY() + ly, pos.getZ() + lz,
                            relX - 0.5, relY - 0.5, relZ - 0.5, state, pos));
                    }
                }
            }
        });
    }

    private static void replayIntoDestinations(ClientLevel level, BlockPos pos,
        BlockState state, SeamRegistry.SeamCell seam, VoxelShape shape) {
        for (SeamRegistry.SeamBinding b : seam.bindings()) {
            if (b == null || !b.isMirrorable() || b.cut() == null || b.destPos() == null) {
                continue;
            }
            ClientLevel destLevel = level.dimension().equals(b.destDim())
                ? level
                : ClientWorldLoader.peekWorld(b.destDim());
            if (destLevel == null) {
                continue;
            }
            // The teleport's own frame convention: horizontal axes through the direction map,
            // vertical passes through; a rolled mapping is not representable — skip.
            Direction mx = SeamRegistry.mapDir(b, Direction.EAST);
            Direction mz = SeamRegistry.mapDir(b, Direction.SOUTH);
            if (mx.getAxis() == Direction.Axis.Y || mz.getAxis() == Direction.Axis.Y) {
                continue;
            }
            BlockPos dest = b.destPos();
            // ★ REFLECT ACROSS THE PLANE (probe round 4's conviction — the CRUMB LIFE TRACE
            // showed every crumb landing in the counterpart's EMPTY half: emptiness-dropped
            // when a mask was present, drawn-but-window-clipped when not). Mirrored material
            // CONTINUES THROUGH the plane — the counterpart's half is the mirror image, not the
            // translate — so the seam-axis component of every crumb position reflects about the
            // cut plane (l' = 2·offset − l) and its axis velocity negates, BEFORE the direction
            // map carries it into the dest frame. The repo's own recorded lesson, relearned:
            // "side/occupancy semantics need the flip; plain mapDir is for orientations only."
            Direction.Axis seamAxis = b.srcFacing().getAxis();
            double planeOffset = b.cut().srcPlaneOffset();
            // The vanilla destroy grid, positions and velocities carried through the seam
            // mapping. The shape is the SOURCE cell's — the fractional cut scopes it to the
            // broken half; the reflection above lands it on the counterpart's material half.
            shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> {
                double widthX = Math.min(1.0, x2 - x1);
                double widthY = Math.min(1.0, y2 - y1);
                double widthZ = Math.min(1.0, z2 - z1);
                int countX = gridCount(widthX, seamAxis == Direction.Axis.X);
                int countY = gridCount(widthY, seamAxis == Direction.Axis.Y);
                int countZ = gridCount(widthZ, seamAxis == Direction.Axis.Z);

                for (int xx = 0; xx < countX; xx++) {
                    for (int yy = 0; yy < countY; yy++) {
                        for (int zz = 0; zz < countZ; zz++) {
                            double relX = (xx + 0.5) / countX;
                            double relY = (yy + 0.5) / countY;
                            double relZ = (zz + 0.5) / countZ;
                            double lx = relX * widthX + x1;
                            double ly = relY * widthY + y1;
                            double lz = relZ * widthZ + z1;
                            double vx = relX - 0.5;
                            double vy = relY - 0.5;
                            double vz = relZ - 0.5;
                            switch (seamAxis) {
                                case X -> { lx = 2.0 * planeOffset - lx; vx = -vx; }
                                case Y -> { ly = 2.0 * planeOffset - ly; vy = -vy; }
                                case Z -> { lz = 2.0 * planeOffset - lz; vz = -vz; }
                            }
                            double cx = lx - 0.5;
                            double cz = lz - 0.5;
                            double nx = dest.getX() + 0.5
                                + mx.getStepX() * cx + mz.getStepX() * cz;
                            double nz = dest.getZ() + 0.5
                                + mx.getStepZ() * cx + mz.getStepZ() * cz;
                            double ny = dest.getY() + ly;
                            double nvx = mx.getStepX() * vx + mz.getStepX() * vz;
                            double nvz = mx.getStepZ() * vx + mz.getStepZ() * vz;
                            TerrainParticle crumb = new TerrainParticle(
                                destLevel, nx, ny, nz, nvx, vy, nvz, state, dest);
                            Minecraft.getInstance().particleEngine.add(crumb);
                        }
                    }
                }
            });
        }
    }
}
