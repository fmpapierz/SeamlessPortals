package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import com.warwa.seamlessportals.passthrough.SeamOccupancy;
import com.warwa.seamlessportals.passthrough.SeamRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.mixin.client.particle.IEParticle;

/**
 * ★ THE PARTICLE SEAM TELEPORT — round 35, the user's own design: "the particles should be
 * consumed by the seam teleport the same way half of blocks/torches are consumed ... the same
 * delete + mirror thing for particles like we do with blocks."
 *
 * <p>A particle that crosses into a cut cell's EMPTY half is not culled and not hidden — it is
 * MOVED: re-tagged to the counterpart world (the global engine is multi-world by IP design),
 * repositioned through the seam mapping, velocity remapped. Consequences, each one of the
 * user's three reports:
 * <ul>
 *   <li>no particle ever exists past the seam locally — nothing to bleed from the empty side
 *       regardless of window geometry;</li>
 *   <li>the far side now "mirrors" the particles — the moved particle IS there, and S18's
 *       dest-tagged rendering shows it through the window;</li>
 *   <li>positions are never faked — the particle continues its own trajectory in the world
 *       where the material continues.</li>
 * </ul>
 *
 * <p>Fallbacks are CONSUMPTION, never bleed: an unresolvable destination level or a
 * non-horizontal mapping removes the particle (it crossed the seam; it must not remain).
 */
public final class SeamParticleTeleport {

    private SeamParticleTeleport() {}

    public static void maybeTeleport(Particle particle) {
        if (!SeamFractional.active() || !particle.isAlive()) {
            return;
        }
        IEParticle ie = (IEParticle) particle;
        ClientLevel level = ie.portal_getWorld();
        if (level == null) {
            return;
        }
        double x = ie.portal_getX(), y = ie.portal_getY(), z = ie.portal_getZ();
        if (!SeamFractional.positionInEmptyHalf(level, x, y, z)) {
            return;
        }
        BlockPos cell = BlockPos.containing(x, y, z);
        SeamRegistry.SeamCell seam = SeamRegistry.lookup(level, cell);
        if (seam == null) {
            return;
        }
        SeamRegistry.SeamBinding binding = null;
        for (SeamRegistry.SeamBinding b : seam.bindings()) {
            if (b != null && b.isMirrorable() && b.cut() != null && b.destPos() != null) {
                binding = b;
                break;
            }
        }
        if (binding == null) {
            return;
        }
        ClientLevel destLevel = level.dimension().equals(binding.destDim())
            ? level
            : ClientWorldLoader.peekWorld(binding.destDim());
        if (destLevel == null) {
            particle.remove();   // consumed — the seam owns what crossed it
            return;
        }
        // Horizontal frame mapping via the binding's direction map (the same transform the
        // mirror uses for block states). Vertical components pass through; a mapping that turns
        // the horizontal axes vertical (a rolled portal) is not representable here — consume.
        Direction mx = SeamRegistry.mapDir(binding, Direction.EAST);
        Direction mz = SeamRegistry.mapDir(binding, Direction.SOUTH);
        if (mx.getAxis() == Direction.Axis.Y || mz.getAxis() == Direction.Axis.Y) {
            particle.remove();
            return;
        }
        double cx = (x - cell.getX()) - 0.5;
        double cz = (z - cell.getZ()) - 0.5;
        double ly = y - cell.getY();
        BlockPos dest = binding.destPos();
        double nx = dest.getX() + 0.5 + mx.getStepX() * cx + mz.getStepX() * cz;
        double nz = dest.getZ() + 0.5 + mx.getStepZ() * cx + mz.getStepZ() * cz;
        double ny = dest.getY() + ly;
        double vx = ie.portal_getXd(), vy = ie.portal_getYd(), vz = ie.portal_getZd();
        double nvx = mx.getStepX() * vx + mz.getStepX() * vz;
        double nvz = mx.getStepZ() * vx + mz.getStepZ() * vz;

        ie.portal_setWorld(destLevel);
        ie.portal_setX(nx);
        ie.portal_setY(ny);
        ie.portal_setZ(nz);
        ie.portal_setXo(nx);
        ie.portal_setYo(ny);
        ie.portal_setZo(nz);
        ie.portal_setXd(nvx);
        ie.portal_setYd(vy);
        ie.portal_setZd(nvz);
        if (com.warwa.seamlessportals.passthrough.AperturePassthroughLever.SEAM_FRACTIONAL_PROBE) {
            com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                "[SEAM FRAC] particle crossed the seam {} -> {} ({} -> {})",
                cell, dest, level.dimension().identifier(), destLevel.dimension().identifier());
        }
    }

    /** Test/probe visibility: whether a cell would currently teleport crossers (binding ready). */
    public static boolean seamReady(ClientLevel level, BlockPos cell) {
        SeamRegistry.SeamCell seam = SeamRegistry.lookup(level, cell);
        if (seam == null) {
            return false;
        }
        for (SeamRegistry.SeamBinding b : seam.bindings()) {
            if (b != null && b.isMirrorable() && b.cut() != null && b.destPos() != null) {
                return true;
            }
        }
        return false;
    }
}
