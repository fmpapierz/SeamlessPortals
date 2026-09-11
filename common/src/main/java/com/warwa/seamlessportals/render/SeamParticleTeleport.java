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

    /**
     * ★ SEAM-BORN TAGGING (2026-09-11, near-seam crossing feature): the crumb governance —
     * F1 consume, margin consume, axis-exit consume — exists because a seam-block break is
     * "already represented at the far end" (each end fires its own burst), so its crumbs must
     * never ALSO teleport. That is true of exactly the crumbs BORN inside a governed seam
     * cell (native burst, SeamMirror's counterpart send, and the replay all birth there — a
     * different block cannot occupy a governed cell). A TerrainParticle born anywhere else
     * (a block broken NEAR the portal) is represented nowhere else: it skips all three
     * consume rules and takes the standard particle teleport below (the proven flame/smoke
     * path) when it crosses inside the aperture — and flies vanilla in the margins, where
     * the plane has no portal. Weak identity set: dead crumbs drop with GC.
     */
    private static final java.util.Set<Particle> SEAM_BORN =
        java.util.Collections.newSetFromMap(
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>()));

    /** Whether the crumb governance applies — the revert lever restores consume-for-all. */
    private static boolean seamBornGoverned(Particle particle) {
        return com.warwa.seamlessportals.passthrough.AperturePassthroughLever
            .DISABLE_SEAM_CRUMB_CROSSING
            || SEAM_BORN.contains(particle);
    }

    /** Probe visibility (tint discriminator): whether this crumb carries the seam-born tag. */
    public static boolean isSeamBorn(Particle particle) {
        return SEAM_BORN.contains(particle);
    }

    /**
     * ★ CLEAR-RESTED (2026-09-11, the both-sides-occupied break): crumbs granted the sided
     * birth rest in a SINGLE-OWNED cell — the broken half's own burst when the opposite
     * half is still occupied. The open-cell rest persists by the transition gate alone, but
     * the singleOwned continuation branch grabs every empty-half occupant every tick, so
     * the rest there must be remembered for the crumb's LIFETIME. Weak identity set.
     */
    private static final java.util.Set<Particle> CLEAR_RESTED =
        java.util.Collections.newSetFromMap(
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>()));

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
        // ★ AXIS-EXIT CONSUME — the WORLD rule for the 18-round escapee (2026-09-11; revert
        // with -Dseamlessportals.disableSeamCrumbAxisExitConsume=true). F3-correlated
        // mechanism: a legitimate material-half break crumb leaves its aperture cell through a
        // SEAM-AXIS face (past the cell's far face into the unindexed deeper column, resting
        // visible around the window, ~1 per break). Every viewer-relative hide over-reached
        // ("far side particles totally gone"); this is viewer- and mask-independent: on the
        // tick a TerrainParticle's cell changes, if it CAME FROM a governed aperture cell and
        // its seam-axis coordinate now lies outside that cell's axis span, it exited through an
        // axis face — consume (the far end's own burst is untouched: its crumbs fall DOWN
        // within the plane slab, never leaving the axis span, so the window burst keeps
        // playing; lateral drifts along the aperture lattice equally keep their axis
        // coordinate in-span of their own column only when they exit — an adjacent aperture
        // cell shares the axis span, so in-lattice movement never trips this). SEAM-BORN
        // crumbs only (2026-09-11): a near-seam crumb — or a teleported arrival leaving the
        // counterpart cell on the dest side — exits governed cells freely.
        if (particle instanceof net.minecraft.client.particle.TerrainParticle
            && !com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                .DISABLE_SEAM_CRUMB_AXIS_EXIT_CONSUME
            && seamBornGoverned(particle)) {
            double exo = ie.portal_getXo(), eyo = ie.portal_getYo(), ezo = ie.portal_getZo();
            BlockPos prevCell = BlockPos.containing(exo, eyo, ezo);
            if (!prevCell.equals(cell)) {
                SeamRegistry.SeamCell prevSeam = SeamRegistry.lookup(level, prevCell);
                if (prevSeam != null) {
                    for (SeamRegistry.SeamBinding pb : prevSeam.bindings()) {
                        if (pb == null || !pb.isMirrorable() || pb.cut() == null) {
                            continue;
                        }
                        double axisCoord;
                        int axisBase;
                        switch (pb.srcFacing().getAxis()) {
                            case X -> { axisCoord = x; axisBase = prevCell.getX(); }
                            case Y -> { axisCoord = y; axisBase = prevCell.getY(); }
                            default -> { axisCoord = z; axisBase = prevCell.getZ(); }
                        }
                        if (axisCoord < axisBase || axisCoord >= axisBase + 1) {
                            if (SeamParticleProbe.armed()) {
                                SeamParticleProbe.onConsumed(false);
                                SeamParticleProbe.tickSummary();
                            }
                            particle.remove();
                            return;
                        }
                    }
                }
            }
        }
        SeamRegistry.SeamCell seam = SeamRegistry.lookup(level, cell);
        // Birth inside a governed cell marks the crumb seam-born (see SEAM_BORN). While
        // age<=1, xo/yo/zo still hold the BIRTH position (the ctor sets them and the first
        // tick copies them before moving), so the birth cell — not the current cell — decides:
        // a fast near-seam crumb that already entered the aperture on its first tick stays
        // untagged, and a teleported arrival (xo rewritten to the landing point at age>=1's
        // pass, checked at age 2+) can never self-tag. Tagged before any crossing branch can
        // select a binding, so the birth governance sees every burst crumb.
        if (particle instanceof net.minecraft.client.particle.TerrainParticle
            && ie.portal_getAge() <= 1) {
            BlockPos birthCell = BlockPos.containing(
                ie.portal_getXo(), ie.portal_getYo(), ie.portal_getZo());
            SeamRegistry.SeamCell birthSeam = birthCell.equals(cell) ? seam
                : SeamRegistry.lookup(level, birthCell);
            if (birthSeam != null) {
                for (SeamRegistry.SeamBinding b : birthSeam.bindings()) {
                    if (b != null && b.isMirrorable() && b.cut() != null) {
                        SEAM_BORN.add(particle);
                        break;
                    }
                }
            }
        }
        BlockPos baseCell = cell;
        if (seam == null) {
            // ★ F7 RULING (user, 2026-08-10, superseding r46's margin fallback here): teleport
            // governance applies to ACTUAL SEAM CELLS ONLY — "the margin should just worry about
            // the seam cells... we don't need an 8 block buffer zone". The r46 margin-index
            // teleports let oscillating classes (FallingLeavesParticle sways sinusoidally every
            // tick) re-qualify on every sway: one leaf logged 124 crossings in one lifetime,
            // blinking between ends at up to 19 Hz — every one via this fallback. The index
            // itself stays registered: SeamParticleQuadClip still consults it so near-plane
            // billboards keep their plane-exact cut (the bleed standard, contract point 4).
            // If lateral-wander bleed (the r46 driver) resurfaces live, the recorded principled
            // alternative is birth-side tagging — a decision for the user, with evidence first.
            // ★ CRUMB MARGIN CONSUME, RE-LANDED (2026-09-11 round 10 — first landed, then
            // undone on a live verdict that round 9's axis correction later proved
            // CONTAMINATED: the analysis had measured "wrong side" against the wrong axis. The
            // per-crumb life trace then convicted this exact class with a full trajectory:
            // id=6366861b, born own-side in the seam cell, drifted OUT of the cell in X into an
            // unbound neighbor, crossed the plane extension at the cut plane, rested 33 ticks
            // on the wrong side — precisely the crossing this consume governs.) TerrainParticle
            // ONLY: crumbs are ballistic and short-lived; the sinusoidal oscillator classes the
            // F7 ruling protected (19 Hz leaf ping-pong) are not terrain crumbs and stay
            // ungoverned here. A crumb that genuinely TRANSITIONS the governing plane inside
            // the margin ring is consumed — the F1 doctrine ("already represented at the far
            // end"). Same margin index the quad clip consults; one map get on cells that miss.
            // Revert with -Dseamlessportals.disableSeamCrumbMarginConsume=true. SEAM-BORN
            // crumbs only (2026-09-11): outside the aperture the plane extension is ordinary
            // same-world space — a near-seam crumb crossing it there flies vanilla.
            if (particle instanceof net.minecraft.client.particle.TerrainParticle
                && !com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                    .DISABLE_SEAM_CRUMB_MARGIN_CONSUME
                && seamBornGoverned(particle)
                && level instanceof com.warwa.seamlessportals.passthrough.SeamIndexHolder holder
            ) {
                long base = holder.seamlessportals$particleMargin()
                    .getOrDefault(cell.asLong(), Long.MIN_VALUE);
                if (base != Long.MIN_VALUE) {
                    SeamRegistry.SeamCell governing =
                        SeamRegistry.lookup(level, BlockPos.of(base));
                    if (governing != null) {
                        BlockPos governingCell = BlockPos.of(base);
                        double gx = ie.portal_getX(), gy = ie.portal_getY(),
                            gz = ie.portal_getZ();
                        double gpx = ie.portal_getXo(), gpy = ie.portal_getYo(),
                            gpz = ie.portal_getZo();
                        for (SeamRegistry.SeamBinding b : governing.bindings()) {
                            if (b == null || !b.isMirrorable() || b.cut() == null) {
                                continue;
                            }
                            byte cur = SeamOccupancy.halfFromHit(
                                new net.minecraft.world.phys.Vec3(gx, gy, gz), governingCell,
                                b.srcFacing().getAxis(), b.cut().srcPlaneOffset());
                            byte prev = SeamOccupancy.halfFromHit(
                                new net.minecraft.world.phys.Vec3(gpx, gpy, gpz), governingCell,
                                b.srcFacing().getAxis(), b.cut().srcPlaneOffset());
                            if (cur != prev) {
                                if (SeamParticleProbe.armed()) {
                                    SeamParticleProbe.onConsumed(false);
                                    SeamParticleProbe.tickSummary();
                                }
                                particle.remove();
                                return;
                            }
                        }
                    }
                }
            }
            return;
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
                // ★ SIDED BIRTH REST, singleOwned form (2026-09-11, the both-sides-occupied
                // break): with the opposite half still occupied the broken cell stays
                // singleOwned after the break, so the burst crumbs in the freshly-cleared
                // half hit the continuation grab below at ADD and were consumed by F1 —
                // "opposite side seam block blocks my side's break particles". The
                // just-cleared half is stashed (SeamOccupancyClient records partial clears
                // too); a crumb in that half is the break's own burst and rests there for
                // its LIFETIME (CLEAR_RESTED — no age gate, because the counterpart's
                // occupancy update and the replay's crumbs can land a tick apart).
                if (particle instanceof net.minecraft.client.particle.TerrainParticle
                    && !com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                        .DISABLE_SEAM_CRUMB_BIRTH_REST) {
                    if (CLEAR_RESTED.contains(particle)) {
                        return;
                    }
                    byte clearedMask = com.warwa.seamlessportals.passthrough
                        .SeamOccupancyClient.recentClearedMask(level, cell.asLong());
                    // ★ CRUMB-REST probe (both-sides instrument round): the decision inputs
                    // for every young crumb reaching the singleOwned branch — a crumb that
                    // falls through to the grab is about to be F1-consumed.
                    if (probe && ie.portal_getAge() <= 3) {
                        qouteall.q_misc_util.Helper.log(String.format(
                            "[CRUMB-REST] id=%08x age=%d pos=%.3f,%.3f,%.3f cell=%s owned=%d"
                                + " particleHalf=%d clearedMask=%d rest=%b",
                            System.identityHashCode(particle), ie.portal_getAge(), x, y, z,
                            cell, owned, particleHalf, clearedMask,
                            clearedMask != 0 && particleHalf == clearedMask));
                    }
                    if (clearedMask != 0 && particleHalf == clearedMask) {
                        CLEAR_RESTED.add(particle);
                        return;
                    }
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
                    //
                    // ★ SEAM CRUMB BIRTH REST (2026-09-10 live report: breaking a seam block
                    // left its crumbs clumped at the cell center for a frame or two, then gone
                    // — no spread; revert with -Dseamlessportals.disableSeamCrumbBirthRest=
                    // true). TerrainParticle is EXEMPT from this birth inference: the break
                    // clears occupancy before the engine's add-drain, so the burst evaluates
                    // HERE, in an open BI-FACED cell where every position is past one of the
                    // two planes — the inference marked every crumb of both bursts (native +
                    // SeamMirror's counterpart send) a birth-crosser, and the F1 rule below
                    // consumed the lot at first drain. Break crumbs are not scatter-spawned
                    // ambience: the burst is deliberately full-volume, F1's contract is that it
                    // "plays at full density for its half, at normal speed, to completion", the
                    // quad clip owns at-plane visibility (contract point 4), and a crumb that
                    // later GENUINELY drifts through the plane still transitions below and is
                    // consumed by F1.
                    //
                    // ★★ THE REST IS SIDED (2026-09-11, crumb probe round 8 — the wrong-side
                    // stray's root cause, read from the per-crumb life log): the occupancy
                    // CLEAR races the destroy burst, and when the clear lands first the shape
                    // hook returns the WHOLE block — vanilla then births crumbs in BOTH halves
                    // (four grid columns measured vs the cut's two), and the blanket exemption
                    // kept the wrong-half births alive: born past the plane, arcing onto the
                    // far ground, resting in plain sight. The just-cleared mask is the side
                    // reference (SeamOccupancyClient stashes it for one burst lifetime): a
                    // birth crumb rests only in the half that HELD MATERIAL; a wrong-half
                    // birth falls through to the consume, exactly as before the arc. No stash
                    // (cut-shape burst, or a genuinely open cell) → rest, unchanged.
                    boolean crumbRest = false;
                    if (particle instanceof net.minecraft.client.particle.TerrainParticle
                        && !com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                            .DISABLE_SEAM_CRUMB_BIRTH_REST) {
                        byte clearedMask = com.warwa.seamlessportals.passthrough
                            .SeamOccupancyClient.recentClearedMask(level, cell.asLong());
                        // ★ ROUND 9 COMPLETION (gate data: the residual bleed was exactly the
                        // clearedMask=0 rows — ~95 unsided rests, while sided rests and
                        // wrong-half consumes both worked): NO STASH → NO REST. A break with no
                        // recent mask has no side reference, and blanket-resting it revives
                        // wrong-half births; the round-41 consume applies there instead — the
                        // pre-arc behavior for unmasked cells, while every masked seam-pair
                        // break (the case the birth rest exists for) keeps the sided rest.
                        crumbRest = clearedMask != 0 && particleHalf == clearedMask;
                    }
                    if (ie.portal_getAge() <= 1
                        && particleHalf != SeamOccupancy.halfOf(b.srcFacing())
                        && !crumbRest) {
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
        // ★ F1 round 2 (2026-08-11 live re-analysis): DESTROY-BURST CRUMBS NEVER TELEPORT — via
        // ANY branch. Round 1 culled only open-cell BIRTH crossers, but the user's live probe
        // showed the real drain was the OWNED-cell material-continuation branch (ownedCont=29-31
        // teleports/s through the whole animation) — a burst around occupied seam cells feeds
        // crumbs into owned crossing halves every tick. Each end of a broken pair fires its own
        // native burst for its half (SeamMirror's levelEvent 2001), so any crumb this machinery
        // would move is already represented at the far end: consume it, whatever branch selected
        // the binding. Crumbs that stay in their own half are now never touched — the burst
        // plays at full density for its half, at normal speed, to completion. Flame/smoke keep
        // their teleports (closed, live-confirmed arc).
        //
        // ★ 2026-09-11 AMENDMENT — SEAM-BORN crumbs only: "already represented at the far end"
        // is a property of seam-block bursts, which are exactly the crumbs born in governed
        // cells (SEAM_BORN). A crumb from a block broken NEAR the portal is represented
        // nowhere else, so it falls through to the standard teleport below and crosses into
        // dest like flame/smoke. Revert with -Dseamlessportals.disableSeamCrumbCrossing=true.
        if (particle instanceof net.minecraft.client.particle.TerrainParticle
            && seamBornGoverned(particle)) {
            if (probe) {
                SeamParticleProbe.onConsumed(false);
                SeamParticleProbe.tickSummary();
            }
            particle.remove();
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
            // ★ CRUMB-XING: every TERRAIN teleport named. During a pure seam-block break this
            // must be silent (burst crumbs are tagged → F1); any line during one names a
            // tagging gap, and tagged=true here is impossible by construction.
            if (particle instanceof net.minecraft.client.particle.TerrainParticle) {
                qouteall.q_misc_util.Helper.log(String.format(
                    "[CRUMB-XING] terrain teleported tagged=%b age=%d %s %s -> %s %s",
                    SEAM_BORN.contains(particle), ie.portal_getAge(),
                    level.dimension().identifier(), cell,
                    destLevel.dimension().identifier(), dest));
            }
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
