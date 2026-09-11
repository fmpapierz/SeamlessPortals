package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamFractional;
import com.warwa.seamlessportals.passthrough.SeamRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * ★ SEAM CRUMB REPLAY (landed 2026-09-11, user-verified "break particles show on both
 * sides"; revert with -Dseamlessportals.disableSeamCrumbReplay=true) — the dest-side half of
 * seam-block break particles. See the lever's javadoc for attempt #1's history.
 *
 * <p>Mechanism: the counterpart's burst is server-sent (SeamMirror's {@code sendParticles} —
 * the silent {@code setBlockAndUpdate} clear fires no levelEvent), and that only reaches
 * players standing IN the dest level near the counterpart. The breaker looking through a
 * cross-dim window never gets it. The replay is CLIENT-side, driven by the breaking side's own
 * destroy burst ({@code ClientLevel.addDestroyBlockEffect} TAIL): for each mirrorable binding
 * of the broken cell, the exact vanilla crumb grid (26.2 ClientLevel.java:1011-1046 —
 * shape-driven, so the fractional cut scopes it to the broken half) is re-spawned TRANSFORMED
 * into the binding's destination: positions through the seam mapping, velocities through the
 * same horizontal direction map, particles constructed against the DEST ClientLevel (the
 * ctor's level argument IS the multi-world tag) and added engine-direct — no camera gate, no
 * packets. Dest-tagged particles render through windows via the isolated dest extract
 * ({@code MixinParticleEngine.ip_extractIsolated}); the widened mirrorable valve in
 * RenderStates admits counterpart-cell positions there.
 */
public final class SeamCrumbReplay {

    private SeamCrumbReplay() {}

    public static void onDestroyBurst(ClientLevel level, BlockPos pos, BlockState state) {
        if (!SeamFractional.active() || AperturePassthroughLever.DISABLE_SEAM_CRUMB_REPLAY) {
            return;
        }
        // The vanilla gates this replay mirrors (the mixin fires at TAIL unconditionally).
        if (state.isAir() || !state.shouldSpawnTerrainParticles()) {
            return;
        }
        SeamRegistry.SeamCell seam = SeamRegistry.lookup(level, pos);
        if (seam == null) {
            return;
        }
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
            VoxelShape shape = state.getShape(level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> {
                double widthX = Math.min(1.0, x2 - x1);
                double widthY = Math.min(1.0, y2 - y1);
                double widthZ = Math.min(1.0, z2 - z1);
                int countX = Math.max(2, Mth.ceil(widthX / 0.25));
                int countY = Math.max(2, Mth.ceil(widthY / 0.25));
                int countZ = Math.max(2, Mth.ceil(widthZ / 0.25));

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
