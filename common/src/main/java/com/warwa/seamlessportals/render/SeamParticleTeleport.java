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
 *
 * <p>★ ROUND 40 (instrument round r39's two measured findings, both fixed here): (1) the
 * teleport moves the BOUNDING BOX with the particle — vanilla re-derives x/y/z from the box
 * every moving tick, so the r35 field-only writes never actually moved anyone (the flap); (2)
 * OPEN aperture cells teleport only on a genuine plane crossing this tick — the r37 positional
 * "beyond" test is satisfiable by every open-cell position, which with (1) fixed would oscillate
 * every arrival at tick rate.
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
        BlockPos cell = BlockPos.containing(x, y, z);
        SeamRegistry.SeamCell seam = SeamRegistry.lookup(level, cell);
        BlockPos baseCell = cell;
        if (seam == null) {
            // ★ ROUND 45 — THE MARGIN RING (user live report: frameless portals let tall fire
            // smoke exit the TOP of the bound aperture region while still hugging the plane, then
            // cross in unbound air one jitter later — "the particles furthest past the seam are
            // the ones bleeding"). A cell adjacent to a bound aperture cell PERPENDICULAR to the
            // plane axis is cut by the same geometric plane, so particles there get the same
            // treatment (particles only — block logic never sees this). A margin cell has no
            // occupancy, so the resolved neighbor's SeamCell drives the ordinary OPEN-cell flow
            // unchanged (transition gate, birth rule, binding choice); only the destination
            // mapping carries the perpendicular offset (below). Cheap gate first: the per-section
            // seam index — a particle outside every seam-bearing section pays one set lookup.
            if (!((com.warwa.seamlessportals.passthrough.SeamIndexHolder) level)
                .seamlessportals$sectionsWithSeams()
                .contains(net.minecraft.core.SectionPos.asLong(cell))) {
                return;
            }
            outer:
            for (Direction d : Direction.values()) {
                BlockPos n = cell.relative(d);
                SeamRegistry.SeamCell nc = SeamRegistry.lookup(level, n);
                if (nc == null) {
                    continue;
                }
                for (SeamRegistry.SeamBinding b : nc.bindings()) {
                    if (b != null && b.isMirrorable() && b.cut() != null && b.destPos() != null
                        && d.getAxis() != b.srcFacing().getAxis()) {
                        seam = nc;
                        baseCell = n;
                        break outer;
                    }
                }
            }
            if (seam == null) {
                return;
            }
        }
        // ★ ROUND 37 — OCCUPANCY IS NOT THE GATE (the smoke miss: flames hug the torch's own
        // occupied cell and teleported; smoke rises into the OPEN aperture cell above, crossed
        // the plane there with no occupancy anywhere, and sailed into the local far side). An
        // aperture cell is portal surface whether or not a block occupies it: a particle beyond
        // a binding's plane goes through via THAT binding. Occupied cells keep their material
        // protection — a particle in the owned half stays, and an empty-half crosser travels via
        // the binding whose front IS the owned half (the material's own continuation).
        byte owned = com.warwa.seamlessportals.passthrough.SeamOccupancy.occupancyOf(level, cell);
        if (owned == SeamOccupancy.BOTH) {
            return;   // materially whole: no crossing surface inside this cell
        }
        boolean singleOwned = owned == SeamOccupancy.HALF_POSITIVE
            || owned == SeamOccupancy.HALF_NEGATIVE;
        boolean probe = SeamParticleProbe.armed();
        // Tick-start position (Particle.tick copies x/y/z into xo/yo/zo before moving) — the
        // crossing gate's "where the particle CAME FROM" for this tick.
        double px = ie.portal_getXo(), py = ie.portal_getYo(), pz = ie.portal_getZo();
        boolean openNoCrossing = false;
        SeamRegistry.SeamBinding binding = null;
        for (SeamRegistry.SeamBinding b : seam.bindings()) {
            if (b == null || !b.isMirrorable() || b.cut() == null || b.destPos() == null) {
                continue;
            }
            byte particleHalf = SeamOccupancy.halfFromHit(
                new net.minecraft.world.phys.Vec3(x, y, z), cell,
                b.srcFacing().getAxis(), b.cut().srcPlaneOffset());
            if (singleOwned) {
                if (particleHalf == owned) {
                    if (probe) {
                        SeamParticleProbe.onStayOwned();
                        SeamParticleProbe.tickSummary();
                    }
                    return;   // in the material: it belongs here
                }
                // No transition requirement here: an empty-half occupant must not EXIST locally
                // (r35 — "no particle ever exists past the seam locally"), so residents and
                // crossers alike travel via the material's own continuation. This cannot loop:
                // claimCrossingHalf derives the dest OWNED half through the same mapDir the
                // position transform uses, so arrivals land in dest material and stay.
                if (SeamOccupancy.halfOf(b.srcFacing()) == owned) {
                    binding = b;   // cross via the material's own continuation
                    break;
                }
            } else {
                // ★ ROUND 40 — OPEN cells teleport only on a genuine CROSSING this tick. The
                // round-37 "beyond this binding's plane" test is satisfiable by EVERY position in
                // a bi-faced open cell (two opposite fronts), so once the teleport actually moves
                // particles (the r39 bounding-box fix below) it would oscillate every arrival at
                // 20 Hz: an arrival is always "beyond" the counterpart's other binding. The
                // transition gate is the handoff's prescribed shape: previous-position half vs
                // current half must DIFFER (the particle passed through the plane this tick), and
                // the came-from half picks the binding (a crosser enters the face it was in front
                // of). Arrivals set xo/yo/zo = landing point, so they carry no transition and
                // rest; a later genuine re-crossing legitimately travels back (rule 3 applies to
                // every crossing, in both directions). Parallel risers never transition — they
                // stay, and the window rule owns their visibility (rule 2).
                byte prevHalf = SeamOccupancy.halfFromHit(
                    new net.minecraft.world.phys.Vec3(px, py, pz), cell,
                    b.srcFacing().getAxis(), b.cut().srcPlaneOffset());
                if (prevHalf == particleHalf) {
                    // ★ ROUND 41 — SPAWN-SCATTER CORRECTION (the live rest-bleed: fire smoke
                    // that MATERIALIZED past the plane — animateTick scatters spawn points
                    // across the whole block — has xo==x, so the crossing gate rests it on the
                    // wrong side with no local source). A first-tick particle found beyond a
                    // binding's plane crossed at birth: consume via that binding, once. age<=1
                    // is true exactly once per particle (every funnel pass runs tick() first),
                    // so arrivals — age 2+ by their next pass — can never re-trigger it.
                    if (ie.portal_getAge() <= 1
                        && particleHalf != SeamOccupancy.halfOf(b.srcFacing())) {
                        binding = b;
                        break;
                    }
                    openNoCrossing = true;
                    continue;   // no crossing against this plane this tick
                }
                if (SeamOccupancy.halfOf(b.srcFacing()) == prevHalf) {
                    binding = b;   // crossed this binding's plane from its front → through it
                    break;
                }
            }
        }
        if (binding == null) {
            if (probe && openNoCrossing) {
                SeamParticleProbe.onOpenNoCrossing();
                SeamParticleProbe.tickSummary();
            }
            return;
        }
        boolean openCell = !singleOwned;
        ClientLevel destLevel = level.dimension().equals(binding.destDim())
            ? level
            : ClientWorldLoader.peekWorld(binding.destDim());
        if (destLevel == null) {
            if (probe) {
                SeamParticleProbe.onConsumed(false);
                SeamParticleProbe.tickSummary();
            }
            particle.remove();   // consumed — the seam owns what crossed it
            return;
        }
        // Horizontal frame mapping via the binding's direction map (the same transform the
        // mirror uses for block states). Vertical components pass through; a mapping that turns
        // the horizontal axes vertical (a rolled portal) is not representable here — consume.
        Direction mx = SeamRegistry.mapDir(binding, Direction.EAST);
        Direction mz = SeamRegistry.mapDir(binding, Direction.SOUTH);
        if (mx.getAxis() == Direction.Axis.Y || mz.getAxis() == Direction.Axis.Y) {
            if (probe) {
                SeamParticleProbe.onConsumed(true);
                SeamParticleProbe.tickSummary();
            }
            particle.remove();
            return;
        }
        double cx = (x - cell.getX()) - 0.5;
        double cz = (z - cell.getZ()) - 0.5;
        double ly = y - cell.getY();
        BlockPos dest = binding.destPos();
        if (!baseCell.equals(cell)) {
            // Margin cell: the destination is the bound neighbor's counterpart offset by the
            // same perpendicular step, carried through the portal rotation (vertical passes
            // through — the same convention as the velocity mapping below).
            int ddx = cell.getX() - baseCell.getX();
            int ddy = cell.getY() - baseCell.getY();
            int ddz = cell.getZ() - baseCell.getZ();
            dest = dest.offset(
                mx.getStepX() * ddx + mz.getStepX() * ddz,
                ddy,
                mx.getStepZ() * ddx + mz.getStepZ() * ddz);
            if (probe) {
                SeamParticleProbe.onMarginTeleport();
            }
        }
        double nx = dest.getX() + 0.5 + mx.getStepX() * cx + mz.getStepX() * cz;
        double nz = dest.getZ() + 0.5 + mx.getStepZ() * cx + mz.getStepZ() * cz;
        double ny = dest.getY() + ly;
        double vx = ie.portal_getXd(), vy = ie.portal_getYd(), vz = ie.portal_getZd();
        double nvx = mx.getStepX() * vx + mz.getStepX() * vz;
        double nvz = mx.getStepZ() * vx + mz.getStepZ() * vz;

        ie.portal_setWorld(destLevel);
        // ★ ROUND 40 — THE BOUNDING BOX MOVES TOO (the r39 root cause, bytecode-proven):
        // Particle.move() ends with setBoundingBox(bb.move(...)) + setLocationFromBoundingbox(),
        // re-deriving x/y/z FROM THE BOX every moving tick. The r35 raw field writes left the box
        // at the source, so vanilla snapped every crosser back one tick later — the flap behind
        // all three regression reports. setPos writes the fields and rebuilds the box around the
        // landing point in one call (verified 26.2 bytecode: no other side effects).
        particle.setPos(nx, ny, nz);
        ie.portal_setXo(nx);
        ie.portal_setYo(ny);
        ie.portal_setZo(nz);
        ie.portal_setXd(nvx);
        ie.portal_setYd(vy);
        ie.portal_setZd(nvz);
        if (probe) {
            // The per-crossing line lives in the probe now, BUDGETED (10/s) — under the suspected
            // ping-pong it would fire hundreds of times per second and starve the summaries.
            SeamParticleProbe.onTeleport(particle, level, destLevel, cell, dest, openCell);
            SeamParticleProbe.tickSummary();
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
