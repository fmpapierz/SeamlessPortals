package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * THE SEAM REGISTRY — the live index of which aperture cells are bound across a portal, and the query
 * surface that sub-features (b) rail connection, (c) redstone bridging and (d) minecart traversal all
 * consume. Spec: {@code migration/REDSTONE_A_SPEC.md} §2.4 / §3.4.
 *
 * <p>{@link SeamMap} answers "given a portal and a column, which cells pair up?" — pure arithmetic.
 * This class answers the question the game actually asks, which is the inverse and by position:
 * "I am about to change the block at this position — is it bound to anything?"
 *
 * <p><b>Bindings are derived, never persisted.</b> Portal geometry is already saved and synced as
 * entity data, so re-deriving the index at runtime cannot drift from the portals themselves. The one
 * thing that does need persisting is provenance and pending clears — see
 * {@link SeamIndexHolder#seamlessportals$mirrorCreatedCells()} — because those are facts about blocks,
 * not about geometry, and cannot be recomputed.
 *
 * <p><b>A portal binds only while it TICKS.</b> Seeding runs off {@code SERVER_PORTAL_TICK_SIGNAL},
 * so a portal in a non-ticking chunk holds no bindings, and a block changed in its aperture has no
 * seam to act on — the mirror is silently one-directional until the far side is alive. Usually
 * invisible in play (a portal you are standing at keeps its counterpart loaded), but it is exactly
 * why the steps 5+6 gate must force-load and TICK the far portal before asserting the reverse
 * direction. Making the mirror durable across a cold far side is the step-7 journal's job.
 *
 * <p><b>Two bindings per cell.</b> An obsidian frame produces FOUR portal entities — two coincident
 * opposite-normal ones per side (confirmed live: ids 13+14 at identical overworld coordinates). Both
 * faces bind the same aperture cell, so a cell carries up to two bindings and a driver that fires
 * per-portal would fire twice per side unless it deduplicates. {@link SeamCell} models that directly
 * rather than pretending a cell has one binding.
 */
public final class SeamRegistry {

    private SeamRegistry() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One direction of a seam: "from this cell, facing this way, you arrive there".
     *
     * <p>{@code destDim}/{@code destPos} are null for a portal that fails {@link SeamMap#isMirrorable}
     * — a scaled, off-axis or non-quarter-turn portal has no well-defined cell counterpart. Such a
     * portal is still indexed, so (b)/(c)/(d) can see that a seam exists and decline gracefully,
     * rather than silently treating the cell as ordinary.
     */
    /**
     * ★ THE CUT — where each side's plane actually falls inside its own cell, so the fractional
     * model can divide a block instead of duplicating it ({@code FRACTIONAL_DESIGN.md} §2a).
     *
     * <p>Both offsets are in {@code (0, 1)}, measured from the cell's lower corner along the plane's
     * axis. They are INDEPENDENT: a .3 source plane pairing with a .21 destination plane is legal
     * and must work — that is the whole point of user decision A, and it is why this is two numbers
     * and not one.
     *
     * <p>{@code destFacing} is the DESTINATION portal's facing — the side the far world keeps — and
     * is null when no reverse portal could be resolved (a one-way or unpaired portal). In that case
     * {@code destPlaneOffset} is {@link Double#NaN} and only the source half of the cut is known;
     * {@link #hasDestination()} is the guard.
     *
     * <p>Derived every bind from live portal geometry, exactly like the rest of the binding — so it
     * costs no persistence and no packet (both sides run the same bind handler off their own tick
     * signal, and portal geometry is already synced as entity data).
     */
    /**
     * ★ ROUND 46 — in-plane margin radius (cells beyond the aperture) inside which PARTICLES are
     * still governed by the seam (teleport + clip). Fire smoke wanders this far before crossing;
     * beyond it, a crossing is genuinely "around the portal" and flows free.
     */
    public static final int PARTICLE_MARGIN_RADIUS = 8;

    public record SeamCut(
        double srcPlaneOffset,
        @Nullable Direction destFacing,
        double destPlaneOffset
    ) {
        public boolean hasDestination() {
            return destFacing != null && !Double.isNaN(destPlaneOffset);
        }
    }

    public record SeamBinding(
        Direction srcFacing,
        @Nullable ResourceKey<Level> destDim,
        @Nullable BlockPos destPos,
        Rotation stateRotation,
        UUID portalUuid,
        SeamMap.SeamPhase phase,
        boolean seamContinuous,
        @Nullable SeamCut cut
    ) {
        public boolean isMirrorable() {
            return destDim != null && destPos != null;
        }

        /**
         * The direction a track travels when it crosses THIS binding's seam: into the portal's front
         * face, i.e. against the normal. {@code srcFacing} is the normal direction — the side the
         * portal's viewers (and its aperture cell) are on — so the crossing is its opposite.
         */
        public Direction crossDir() {
            return srcFacing.getOpposite();
        }

        /**
         * ★ THE (b) PRIMITIVE — the far cell a probe leaving this cell in {@code dir} arrives at, or
         * null when that direction does not reach across this binding's seam.
         *
         * <p>The two topologies answer differently, and getting either wrong is silently off by one
         * cell:
         * <ul>
         *   <li>{@link SeamMap.SeamPhase#DISJOINT} — the plane is flush with this cell's face on the
         *       {@link #crossDir()} side. Exactly ONE direction crosses, and the destination aperture
         *       cell IS the neighbour. Any other direction is ordinary in-world terrain.</li>
         *   <li>{@link SeamMap.SeamPhase#COINCIDENT} — the plane BISECTS this cell, and this cell and
         *       {@code destPos} are one physical slot kept byte-identical by the mirror. BOTH
         *       directions along the seam axis lead to a far counterpart: the next cell is the one
         *       BEYOND the shared slot, stepping the query direction carried through the portal's
         *       rotation. (For {@code dir == crossDir()} that is the crossing proper — the cell the
         *       portal's content direction points at; for {@code dir == srcFacing} it is the far
         *       world's cell CO-LOCATED with this side's approach cell, the fallback a resolver
         *       consults when the local approach is empty.)</li>
         * </ul>
         *
         * <p><b>Direction convention, pinned by the step-1 gate against the portal's own transform:</b>
         * for the crossing direction the continuation step in the destination equals
         * {@code Direction.getApproximateNearest(portal.getContentDirection())}. The first build of
         * this method stepped {@code stateRotation.rotate(srcFacing)} for the canonical crossing —
         * the co-located FALLBACK cell, pointing behind the far plane — and the gate could not tell,
         * because it only asserted the result differed from {@code destPos}. Self-consistent tests
         * prove nothing; the gate now derives the direction from the portal itself.
         */
        @Nullable
        public BlockPos continuationToward(Direction dir) {
            if (destPos == null) {
                return null;
            }
            if (phase == SeamMap.SeamPhase.DISJOINT) {
                return dir == crossDir() ? destPos : null;
            }
            // COINCIDENT: both directions along the seam axis cross; laterals never do.
            if (dir.getAxis() != srcFacing.getAxis()) {
                return null;
            }
            return destPos.relative(stateRotation.rotate(dir));
        }

        /**
         * The continuation for the canonical crossing — a track entering the portal's front face and
         * travelling {@link #crossDir()}. Kept for callers that do not care about direction; anything
         * resolving a specific probe must use {@link #continuationToward}.
         */
        @Nullable
        public BlockPos continuationCell() {
            return continuationToward(crossDir());
        }
    }

    /** The bindings at one cell — up to two, one per portal face. */
    public record SeamCell(@Nullable SeamBinding a, @Nullable SeamBinding b) {
        public List<SeamBinding> bindings() {
            List<SeamBinding> out = new ArrayList<>(2);
            if (a != null) out.add(a);
            if (b != null) out.add(b);
            return out;
        }

        public SeamCell with(SeamBinding binding) {
            if (a == null) return new SeamCell(binding, b);
            if (a.portalUuid().equals(binding.portalUuid())) return new SeamCell(binding, b);
            if (b == null || b.portalUuid().equals(binding.portalUuid())) return new SeamCell(a, binding);
            return this;   // already two faces bound; a third portal over the same cell is ignored
        }

        public SeamCell without(UUID portalUuid) {
            SeamBinding na = (a != null && a.portalUuid().equals(portalUuid)) ? null : a;
            SeamBinding nb = (b != null && b.portalUuid().equals(portalUuid)) ? null : b;
            return (na == null && nb == null) ? null : new SeamCell(na, nb);
        }
    }

    // =============================================================================================
    // HOT PATH
    // =============================================================================================

    /**
     * The {@code LevelChunk.setBlockState} gate. One field read plus one {@code contains} on a
     * usually-empty set — see {@link SeamIndexHolder} for why this is not a static map lookup.
     */
    public static boolean sectionHasSeam(Level level, BlockPos pos) {
        return !((SeamIndexHolder) level).seamlessportals$sectionsWithSeams().isEmpty()
            && ((SeamIndexHolder) level).seamlessportals$sectionsWithSeams()
                .contains(SectionPos.asLong(pos));
    }

    @Nullable
    public static SeamCell lookup(Level level, BlockPos pos) {
        return ((SeamIndexHolder) level).seamlessportals$seamCells().get(pos.asLong());
    }

    public static boolean isSeamCell(Level level, BlockPos pos) {
        return lookup(level, pos) != null;
    }

    /**
     * THE (b)/(c)/(d) CONTRACT: what lies across the seam from this cell in this direction, or null
     * when the direction does not cross a seam here.
     */
    @Nullable
    public static GlobalPos lookupAcross(Level level, BlockPos pos, Direction dir) {
        SeamBinding b = bindingAcross(level, pos, dir);
        return b == null ? null : GlobalPos.of(b.destDim(), b.continuationToward(dir));
    }

    /**
     * The binding a track/wire/cart would follow leaving {@code pos} in direction {@code dir}, or
     * null when that direction does not cross a seam here.
     *
     * <p>Exposed as the binding rather than just a position because (b), (c) and (d) all need more
     * than the cell: (b) needs {@link SeamBinding#stateRotation()} to reorient a rail shape, (c) needs
     * {@link #mapDir} to carry a signal's direction across, and (d) needs both to steer a minecart.
     * Returning a bare {@code GlobalPos} would force each of them to re-look-up what this already knows.
     *
     * <p><b>The match is by {@link SeamBinding#continuationToward}, not by facing.</b> An earlier
     * build matched {@code b.srcFacing() == dir}, which happened to give the right cells on an
     * obsidian frame — every aperture cell there carries BOTH facings, and the flipped twin's own
     * facing points along the query — and was silently inverted for any single-binding cell: a
     * boundary-phase (topology B) seam's one binding faces AWAY from the plane, so the query that
     * actually crosses ({@code crossDir()}) found nothing, and the query pointing back into the
     * approach returned the far cell as if it were the near neighbour. Zero consumers existed, so
     * nothing broke; the first consumer is (b), which is why this is fixed now.
     */
    @Nullable
    public static SeamBinding bindingAcross(Level level, BlockPos pos, Direction dir) {
        SeamCell cell = lookup(level, pos);
        if (cell == null) {
            return null;
        }
        for (SeamBinding b : cell.bindings()) {
            if (b.isMirrorable() && b.continuationToward(dir) != null) {
                return b;
            }
        }
        return null;
    }

    /**
     * The block on the far side of the seam, or null when {@code dir} does not cross one here or the
     * destination is unavailable. Reads only — never loads a chunk, so a cold far side reports
     * "nothing there" rather than stalling a shape resolution or a signal read on a chunk load.
     */
    @Nullable
    public static net.minecraft.world.level.block.state.BlockState stateAcross(
        Level level, BlockPos pos, Direction dir
    ) {
        SeamBinding b = bindingAcross(level, pos, dir);
        if (b == null || level.getServer() == null) {
            return null;
        }
        net.minecraft.server.level.ServerLevel dest = level.getServer().getLevel(b.destDim());
        BlockPos target = b.continuationToward(dir);
        if (dest == null || target == null || !dest.hasChunkAt(target)) {
            return null;
        }
        return dest.getBlockState(target);
    }

    /** Map a direction through a seam, for callers that must reorient a block state or a motion. */
    public static Direction mapDir(SeamBinding binding, Direction dir) {
        return binding.stateRotation().rotate(dir);
    }

    // =============================================================================================
    // SEEDING / TEARDOWN
    // =============================================================================================

    /**
     * (Re)bind every aperture cell of this portal. Idempotent — re-binding the same portal at the same
     * geometry rewrites identical entries, which is what makes it safe to call from the per-tick
     * portal signal without tracking dirty state.
     */
    public static void bind(Portal portal) {
        // ★ PASSTHROUGH EXTRAS belt: the tick-path gate in AperturePassthroughInit is the
        // master (it also releases live bindings); this head check covers any future caller.
        if (!qouteall.imm_ptl.core.platform_specific.IPConfig.getConfig().passthroughExtras) {
            return;
        }
        Level level = portal.level();
        if (level == null) {
            return;
        }
        boolean mirrorable = SeamMap.isMirrorable(portal);
        Rotation rotation = mirrorable ? SeamMap.blockRotationOf(portal) : null;
        if (mirrorable && rotation == null) {
            mirrorable = false;   // states cannot be carried; index it query-only
        }
        // ★ ALIGNMENT GATE (user decision 2026-07-26: exact lattice only, offset deferred).
        //
        // Applied HERE, at binding construction, and deliberately not at each consumer. The binding's
        // own isMirrorable() is what the veto, the mirror driver, the break/clear path, bind-time
        // reconciliation and frame mirroring all read, so gating once here makes every one of them
        // agree by construction. Gating them individually is how a cell ends up mirrored by the
        // driver but unclearable by the break path — a far-side block nothing can remove.
        //
        // The alignment is a property of the PORTAL's transform, not of an individual cell (the
        // translation term is shared), so any aperture cell answers for all of them.
        if (mirrorable) {
            java.util.List<Vec3> probeColumns = SeamMap.enumerateColumns(portal);
            BlockPos probeCell = SeamMap.seamCell(portal,
                probeColumns.isEmpty() ? portal.getOriginPos() : probeColumns.get(0));
            SeamAlignment alignment = SeamMap.alignmentOf(portal, probeCell);
            if (!SeamMirrorPolicy.mirrors(alignment)) {
                mirrorable = false;   // still indexed for (b)/(c)/(d) queries; simply never mirrored
                if (AperturePassthroughLever.SEAM_MAP_PROBE) {
                    LOGGER.info("[RS-SEAM-REGISTRY] portal {} alignment={} — indexed QUERY-ONLY,"
                        + " mirroring declined by policy", portal.getUUID(), alignment);
                }
            }
        }

        Vec3 normal = portal.getNormal();
        Direction facing = Direction.getApproximateNearest(normal.x, normal.y, normal.z);
        ResourceKey<Level> destDim = mirrorable ? portal.getDestDim() : null;

        // ★ (b) TRAVERSABILITY — whether oriented block logic (rails today; (c) wire and (d) carts
        // later) may treat this seam as a horizontal continuation of the world. Stricter than
        // mirrorable, on purpose:
        //   - HORIZONTAL crossing only. A floor/ceiling portal's crossing direction is vertical,
        //     which would alias RailState.getRail's own three-Y-level probe onto the seam axis —
        //     and rails cannot run vertically anyway. Such a portal keeps mirroring under (a)
        //     exactly as today; only traversal declines.
        //   - UP must survive the transform. isMirrorable admits any signed-axis rotation, including
        //     ones that turn +Y over (a roll); a rail shape carried through those has no meaning.
        boolean continuous = mirrorable
            && Math.abs(normal.y) < 1.0e-6
            && upPreserved(portal);

        SeamIndexHolder holder = (SeamIndexHolder) level;
        int bound = 0;
        // Resolve the destination portal ONCE, so every column's mirror cell can be defined as the
        // cell that portal itself claims — see resolveDestCell.
        Portal reverse = mirrorable ? findDestinationPortal(portal) : null;

        for (Vec3 column : SeamMap.enumerateColumns(portal)) {
            BlockPos src = SeamMap.seamCell(portal, column);
            BlockPos dst = mirrorable ? resolveDestCell(portal, reverse, column) : null;

            // ★ THE CUT (FRACTIONAL_DESIGN.md §2a). Derived here because this is the one moment BOTH
            // planes are knowable: `reverse` is already resolved above for resolveDestCell, so the
            // destination's own plane offset costs nothing extra. Independent per side on purpose —
            // .3 pairing with .21 is legal and is the case the model exists for.
            SeamCut cut = new SeamCut(
                SeamFractional.planeOffsetOf(portal, src),
                reverse == null ? null
                    : Direction.getApproximateNearest(
                        reverse.getNormal().x, reverse.getNormal().y, reverse.getNormal().z),
                (reverse == null || dst == null) ? Double.NaN
                    : SeamFractional.planeOffsetOf(reverse, dst));

            SeamBinding binding = new SeamBinding(
                facing, destDim, dst, rotation == null ? Rotation.NONE : rotation, portal.getUUID(),
                SeamMap.phaseOf(portal, src), continuous, cut);

            long key = src.asLong();
            SeamCell existing = holder.seamlessportals$seamCells().get(key);
            SeamCell updated = existing == null ? new SeamCell(binding, null) : existing.with(binding);
            holder.seamlessportals$seamCells().put(key, updated);
            holder.seamlessportals$sectionsWithSeams().add(SectionPos.asLong(src));
            // ★ ROUND 46 — PARTICLE MARGIN INDEX. Fire smoke wanders blocks along the plane
            // before crossing its extension (user live rounds 2026-08-10: "the particles
            // furthest laterally from the seam are the ones bleeding"), so the in-plane margin
            // around every aperture cell maps back to its governing cell for the PARTICLE
            // teleport/clip — one map get per particle, any radius. Blocks never read this.
            // Entries carry no owner: the unbind sweep drops any entry whose base cell is no
            // longer bound, which survives bi-faced double-registration and partner teardown
            // (a UUID sweep here would orphan the surviving face — the fingerprint gate means
            // no rebind ever repairs it).
            {
                Direction.Axis marginAxis = facing.getAxis();
                Direction.Axis u = marginAxis == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
                Direction.Axis v = marginAxis == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
                for (int du = -PARTICLE_MARGIN_RADIUS; du <= PARTICLE_MARGIN_RADIUS; du++) {
                    for (int dv = -PARTICLE_MARGIN_RADIUS; dv <= PARTICLE_MARGIN_RADIUS; dv++) {
                        if (du == 0 && dv == 0) {
                            continue;
                        }
                        BlockPos m = src.offset(
                            (u == Direction.Axis.X ? du : 0) + (v == Direction.Axis.X ? dv : 0),
                            (u == Direction.Axis.Y ? du : 0) + (v == Direction.Axis.Y ? dv : 0),
                            (u == Direction.Axis.Z ? du : 0) + (v == Direction.Axis.Z ? dv : 0));
                        holder.seamlessportals$particleMargin().put(m.asLong(), key);
                    }
                }
            }
            bound++;
        }

        // FRAME LINKS — recorded here because this is the one moment the pairing is knowable: both
        // portals are alive and their geometry is certain. They are persisted and OUTLIVE the portal,
        // which is what lets a frame broken and repaired later still find its partner. See
        // SeamFrameLink for why frame links and aperture bindings are deliberately separate.
        if (mirrorable && reverse != null
            && portal instanceof qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity bp
            && bp.blockPortalShape != null
            && reverse instanceof qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity rbp
            && rbp.blockPortalShape != null
            && level instanceof net.minecraft.server.level.ServerLevel srcServerLevel) {
            recordFrameLinks(srcServerLevel, portal, bp, rbp);
        }

        if (AperturePassthroughLever.SEAM_RECONCILE_PROBE) {
            LOGGER.info("[RS-SEAM-REGISTRY] bound portal id={} uuid={} dim={} cells={} mirrorable={}"
                    + " facing={} rot={}",
                portal.getId(), portal.getUUID(), level.dimension().identifier(), bound, mirrorable,
                facing, rotation);
        }
    }

    /**
     * Pair up the two frames' obsidian cells and persist the mapping, both directions.
     *
     * <p><b>Pairing is by transform, not by index.</b> The two frames are the same shape but may be
     * rotated relative to each other, so matching "the Nth cell of one" to "the Nth cell of the other"
     * would silently pair the wrong blocks on any rotated pair. Instead each source frame cell's
     * centre is transformed through the portal and matched to the nearest destination frame cell —
     * the same authority-of-the-far-portal principle that fixed the aperture off-by-one.
     */
    private static void recordFrameLinks(
        net.minecraft.server.level.ServerLevel srcLevel,
        Portal portal,
        qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity src,
        qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity dst
    ) {
        net.minecraft.server.level.ServerLevel dstLevel =
            srcLevel.getServer().getLevel(portal.getDestDim());
        if (dstLevel == null) {
            return;
        }
        for (BlockPos srcFrame : src.blockPortalShape.frameAreaWithoutCorner) {
            Vec3 transformed = portal.transformPoint(Vec3.atCenterOf(srcFrame));
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (BlockPos cand : dst.blockPortalShape.frameAreaWithoutCorner) {
                double d = Vec3.atCenterOf(cand).distanceToSqr(transformed);
                if (d < bestDist) {
                    bestDist = d;
                    best = cand;
                }
            }
            // Reject a poor match rather than pairing arbitrary blocks: a frame cell whose transform
            // lands more than half a block from any destination frame cell is not a real counterpart,
            // and inventing one would mirror a break onto an unrelated block.
            if (best == null || bestDist > 0.75) {
                continue;
            }
            SeamFrameLink.record(srcLevel, srcFrame, dstLevel.dimension(), best);
            SeamFrameLink.record(dstLevel, best, srcLevel.dimension(), srcFrame);
        }
    }

    /**
     * The portal on the far side of this one, or null if none can be found.
     *
     * <p>Located by position rather than by {@code reversePortalId}, so it works for plain
     * {@code Portal} entities as well as {@code BreakablePortalEntity} pairs: the reverse portal is
     * the one whose own origin sits at this portal's destination.
     */
    @Nullable
    private static Portal findDestinationPortal(Portal portal) {
        if (portal.level() == null) {
            return null;
        }
        // ★ BOTH SIDES. This used to bail on `getServer() == null`, which is unconditionally true on
        // any ClientLevel (REF Level.java:168-170 — ClientLevel does not override it), so the whole
        // method was dead client-side even in single-player. resolveDestCell then fell back to
        // SeamMap.mirrorCell — the exact pure-arithmetic path whose live half-block drift is
        // documented below, and which produced a mirror one cell off from what the far portal
        // claimed. Any client-side consumer built on that would place blocks in the wrong cell.
        //
        // Portal.getDestinationWorld() (REF Portal.java:1367-1377) is IP's own accessor and already
        // answers on both sides — CHelper.getClientWorld on the client, server.getLevel otherwise.
        // Using it rather than a hand-rolled branch keeps this matching IP instead of forking from it.
        Level destLevel = portal.getDestinationWorld();
        if (destLevel == null) {
            return null;
        }
        Vec3 destPos = portal.getDestPos();
        Portal positional = null;
        for (Portal candidate : destLevel.getEntitiesOfClass(Portal.class,
            new net.minecraft.world.phys.AABB(destPos.subtract(2, 2, 2), destPos.add(2, 2, 2)),
            p -> p != portal)) {
            if (candidate.getOriginPos().distanceToSqr(destPos) >= 0.25) {
                continue;
            }
            // ★ DISAMBIGUATE BY CONTENT DIRECTION — IP's own reverse-portal test
            // (Portal.isReversePortal:1402: normal(P) · contentDirection(Q) > 0.9). A bi-faced pair
            // (completeBiWayBiFacedPortal, public API and used live) puts TWO coincident
            // opposite-normal candidates at destPos; position alone picks whichever iterates first.
            // On a mid-block (COINCIDENT) plane both faces claim the same seam cell so the wrong
            // pick is harmless, but on a boundary-phase plane their seam cells sit on OPPOSITE
            // sides — first-match writes destPos one cell off, corrupting (a)'s mirror target,
            // not just (b). The true reverse is the face whose content direction is this portal's
            // normal. Lever: -Dseamlessportals.disableSeamReverseDisambig restores first-match.
            if (!AperturePassthroughLever.DISABLE_SEAM_REVERSE_DISAMBIG) {
                if (Portal.isReversePortal(portal, candidate)) {
                    return candidate;
                }
                if (positional == null) {
                    positional = candidate;   // remembered only as the no-dot-match fallback
                }
                continue;
            }
            return candidate;
        }
        // No candidate passed the dot test (e.g. an asymmetric hand-built link whose reverse is not
        // an exact inverse). Fall back to the positional match rather than losing the authority
        // anchor entirely — that is stock pre-disambiguation behaviour.
        return positional;
    }

    /** Whether the portal's transform carries world +Y to +Y — required to carry rail shapes. */
    private static boolean upPreserved(Portal portal) {
        if (portal.getRotation() == null) {
            return true;
        }
        Vec3 up = portal.transformLocalVecNonScale(new Vec3(0, 1, 0));
        return up.y > 0.999;
    }

    /**
     * The destination cell for one column — <b>defined as the cell the DESTINATION portal claims</b>,
     * not as an independent computation.
     *
     * <p><b>Why this is not just {@code SeamMap.mirrorCell}.</b> Computing the mirror cell purely from
     * the source portal's transform lets the two sides DISAGREE about which block is "theirs".
     * Observed live: a source portal mirrored a rail to {@code (-495,75,-500)} while the destination
     * portal's own {@code seamCell} was {@code (-495,75,-501)} — off by one along the through-axis,
     * because the transform landed the point half a block from that portal's actual plane. Provenance
     * was then recorded against a cell no portal claimed, so the frame-break rule found nothing to
     * clear and the mirrored half survived as a duplicate.
     *
     * <p>Resolving through the destination portal makes the invariant hold BY CONSTRUCTION rather
     * than by two computations happening to agree: the mirror writes exactly where the far portal
     * will look. Falls back to the pure arithmetic when no destination portal exists (an unpaired or
     * one-way portal), which is the only case where nothing can disagree anyway.
     */
    private static BlockPos resolveDestCell(Portal portal, @Nullable Portal reverse, Vec3 column) {
        if (reverse == null) {
            return SeamMap.mirrorCell(portal, column);
        }
        // Project the transformed point onto the DESTINATION portal's own plane, then take the cell
        // that portal would take. Any half-block drift in the transform is corrected by the
        // projection, because the far portal's plane is the authority on where its aperture is.
        Vec3 transformed = portal.transformPoint(column);
        return SeamMap.seamCell(reverse, SeamMap.onPlane(reverse, transformed));
    }

    /** Drop every binding owned by this portal. Sections are only dropped when they empty out. */
    public static void unbind(Portal portal) {
        Level level = portal.level();
        if (level == null) {
            return;
        }
        SeamIndexHolder holder = (SeamIndexHolder) level;
        UUID uuid = portal.getUUID();

        List<Long> emptied = new ArrayList<>();
        int removed = 0;
        for (Vec3 column : SeamMap.enumerateColumns(portal)) {
            long key = SeamMap.seamCell(portal, column).asLong();
            SeamCell existing = holder.seamlessportals$seamCells().get(key);
            if (existing == null) {
                continue;
            }
            SeamCell updated = existing.without(uuid);
            if (updated == null) {
                holder.seamlessportals$seamCells().remove(key);
                emptied.add(key);
            }
            else {
                holder.seamlessportals$seamCells().put(key, updated);
            }
            removed++;
        }

        // A section stays flagged while ANY cell in it is still bound — the other face of the same
        // frame, or an overlapping portal. Recomputing membership per emptied section is what keeps
        // the hot-path set from accumulating dead entries over a long session.
        for (long cellKey : emptied) {
            long section = SectionPos.asLong(BlockPos.of(cellKey));
            boolean stillUsed = false;
            for (long remaining : holder.seamlessportals$seamCells().keySet()) {
                if (SectionPos.asLong(BlockPos.of(remaining)) == section) {
                    stillUsed = true;
                    break;
                }
            }
            if (!stillUsed) {
                holder.seamlessportals$sectionsWithSeams().remove(section);
            }
        }

        // ★ ROUND 46 — margin-index sweep by BASE-CELL LIVENESS: an entry lives exactly while its
        // governing aperture cell is bound. UUID-free on purpose — a bi-faced pair registers the
        // same margin twice, and sweeping by the departing face's UUID would orphan the survivor
        // (whose fingerprint-gated bind never re-runs on unchanged geometry).
        if (removed > 0) {
            holder.seamlessportals$particleMargin().values().removeIf(
                base -> !holder.seamlessportals$seamCells().containsKey((long) base));
        }

        if (AperturePassthroughLever.SEAM_RECONCILE_PROBE) {
            LOGGER.info("[RS-SEAM-REGISTRY] unbound portal id={} uuid={} dim={} cells={} sectionsFreed={}",
                portal.getId(), portal.getUUID(), level.dimension().identifier(), removed, emptied.size());
        }
    }

    /** Total bound cells in a level — probe/test accounting only. */
    public static int boundCellCount(Level level) {
        return ((SeamIndexHolder) level).seamlessportals$seamCells().size();
    }
}
