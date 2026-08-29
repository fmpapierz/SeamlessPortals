package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SUB-FEATURE (d) — MINECART RAIL FOLLOWING ACROSS THE SEAM. The measured problem and the fix,
 * from the 2026-07-28 instrument round (RS-CART-A/B probe legs, {@code -PseamCartProbe}):
 *
 * <p><b>What the instrument proved.</b> The live teleport pipeline (detection inside
 * {@code Portal.tick} via the eye-segment test, execution at {@code END_SERVER_TICK} through
 * {@code ServerTaskList}) leaves AT MOST ONE stranded cart behaviour tick in the near level after
 * the cart's position passes the portal plane. On a COINCIDENT (mid-block, obsidian) seam that
 * tick still resolves the seam cell's own near rail, and the crossing is CLEAN end to end —
 * measured: zero {@code comeOffTrack}, arrival on the far rail at riding height, rolling. On a
 * DISJOINT (boundary-phase) seam the stranded tick resolves the behind-cell — no near rail —
 * so {@code comeOffTrack} fires once, the cart leaves rail height, and the teleport then
 * transfers the corrupted Y: the 0.05 arrival nudge along a slightly-downward eye-delta lands the
 * cart EPSILON BELOW the far rail's cell, {@code getCurrentBlockPosOrRailBelow} floors into the
 * stone below forever, and the cart halts one block past the far plane beside a perfectly good
 * rail ({@code comeOffTrack} every tick, speed halved to zero).
 *
 * <p><b>The fix — a seam-framed READ bridge at the cart's rail-resolution chokepoints, never a
 * state copy</b> (the (b)/(c) pattern): every {@code Level.getBlockState} read inside
 * {@code OldMinecartBehavior} (tick / moveAlongTrack / getPos / getPosOffs) and
 * {@code AbstractMinecart.getCurrentBlockPosOrRailBelow} is wrapped through
 * {@link #railAwareState}. LOCAL-FIRST: a local rail always answers. Only when the local state is
 * not a rail AND the queried cell is the THROUGH-IMAGE of an adjacent bound seam cell (owner =
 * one horizontal step back, {@link SeamShadowBridge#shadowFor} with {@code yWindow=1}) does the
 * bridge answer with {@link SeamShadow#readLocal} — the far continuation's state routed into the
 * near frame (rotation applied, cold far chunk reads as AIR + counted decline). The stranded tick
 * then stays ON RAILS at riding height, so the teleport transfers an uncorrupted Y and the far
 * side re-mounts — exactly the mechanism that made the COINCIDENT arm clean.
 *
 * <p><b>Bounded by construction, in BOTH senses</b> — and the second one was a real defect the
 * adversarial panel caught before commit (2026-07-28, two independent lenses):
 * <ul>
 *   <li><i>Depth:</i> the bridge reaches exactly ONE cell past the plane (the owner must be a
 *       bound seam cell; the cell past THAT has no seam-cell owner). A cart whose portal never
 *       fires the teleport derails at the second behind-cell — vanilla-like, and the geometric
 *       stranding window (&le;0.8 blocks past the plane at rail speeds) never reaches it.</li>
 *   <li><i>Direction:</i> the read asks {@link SeamShadowBridge} for {@code crossingOnly}
 *       shadows. Without that narrowing the COINCIDENT backward fallback in
 *       {@link SeamRegistry.SeamBinding#continuationToward} — which answers BOTH axis directions
 *       by design, because (b)'s SHAPE resolver wants the far world's co-located approach cell —
 *       is read as PHYSICAL PRESENCE and conjures a rail one cell in FRONT of the plane: a cart
 *       rolling out of a portal onto an unrailed near approach keeps resolving "on rails" and
 *       LEVITATES one cell past the end of the track. Cart physics needs the crossing direction
 *       and nothing else.</li>
 *   <li><i>Occupancy:</i> the cart must be STRADDLING the seam cell right now ({@link #straddles}).
 *       Direction alone is not enough on a BI-FACED portal — every obsidian frame is a four-entity
 *       cluster, and each face's own {@code crossDir} points the opposite way, so both axis
 *       directions pass the {@code crossingOnly} test for one binding or the other. RS-CART-C
 *       caught a cart hovering on the far world's track with the narrowing already in place; the
 *       straddle test is what makes "one cell past the plane" mean the cart is ON the seam rather
 *       than merely near it.</li>
 * </ul>
 *
 * <p><b>Deliberately NOT here</b> (documented in the handoff): the ×2 slow-minecart velocity
 * boost at teleport is IP's own deliberate kludge ({@code Portal.transformVelocityRelativeToPortal},
 * "avoid cannot push minecart out of nether portal") — inherited, bounded by the 0.4 rail clamp,
 * left as-is; the ridden-cart vehicle path carries velocity through NBT untransformed (matters on
 * ROTATED bindings only); slope traversal at the seam (yWindow stays 1); the stalled-cart
 * {@code isRedstoneConductor} restart probes at a seam boundary.
 *
 * <p><b>One accepted consequence, scoped rather than removed.</b> The bridged state also feeds
 * {@code moveAlongTrack}'s POWERED_RAIL branch, so a stranded tick can apply ONE tick of the far
 * rail's power state to the cart's own velocity — which is the motion we want carried across, and
 * has no effect outside the cart. The other action the bridged state could have driven — the
 * ACTIVATOR_RAIL branch in {@code tick}, which ejects passengers / primes TNT / runs a command
 * block IN THE NEAR LEVEL at a locally-air cell — is suppressed at its own call site
 * (see {@code MixinOldMinecartBehaviorSeamRail}).
 *
 * <p>Counters are always-on (gate coverage + volume ceilings); per-event logging rides
 * {@link AperturePassthroughLever#SEAM_CART_PROBE}. Every entry is exception-guarded (the reads
 * run inside vanilla's tick; an escape would kill the cart tick — the spec-F8 discipline).
 */
public final class SeamCartContinuity {

    private SeamCartContinuity() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Direction[] HORIZONTALS =
        {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private static final AtomicLong BRIDGE_READS = new AtomicLong();
    private static final AtomicLong BRIDGE_HITS = new AtomicLong();
    private static boolean warnedOnce = false;

    /**
     * Set while {@link SeamCartProbe} is making its OWN resolution reads. The probe calls
     * {@code getCurrentBlockPosOrRailBelow} to report what the cart sees, and that method's reads
     * are wrapped — so without this bracket the INSTRUMENT feeds the very counters the gates use
     * as coverage evidence, and RS-CART-B's {@code bridgeHits > 0} assert could be satisfied by
     * the probe rather than by the cart (the panel's gate-integrity finding; same family as the
     * five false readings in the handoff's HAZARDS list). Bridging still happens inside the
     * bracket — the probe must report what the cart actually resolves — only the COUNTING is
     * suppressed. Server-thread confined, like the rest of this class's state.
     */
    private static boolean inProbeRead = false;

    /**
     * How long a cart stays "mid-crossing" after it last straddled a seam cell.
     *
     * <p>THE STRADDLE TEST ALONE IS TOO STRICT FOR A RIDDEN CART, and the user's own live round
     * proved it (2026-07-28, 40km same-dim pair at (3,104,0)→(10,19,40000)). An EMPTY cart is
     * teleported by the entity pipeline within one tick and never leaves the seam cell: eight
     * crossings, zero derails, every arrival at exactly rail riding height. A RIDDEN cart is
     * skipped by that pipeline entirely and waits for its rider's client-first crossing to
     * round-trip — measured at 3 ticks, during which the cart travelled to 1.59 blocks past the
     * seam cell's origin. Its box had cleared the seam cell by 0.10, the bridge went quiet, and
     * it derailed for one tick before the carry.
     *
     * <p>The grace does NOT widen the reach: the DEPTH bound still requires the queried cell to be
     * the immediate neighbour of a bound seam cell, so a cart two cells out gets nothing whatever
     * its mark says. The grace only removes the SUB-CELL position requirement inside that one
     * cell, for a cart that has already been established as crossing.
     */
    private static final int GRACE_TICKS = 10;

    /** Per-cart "I am mid-crossing this seam" mark. Server-thread confined, evicted each tick. */
    private record CrossingMark(long ownerCell, String dimension, long tick) {}

    private static final Map<Integer, CrossingMark> CROSSING = new HashMap<>();
    private static final AtomicLong GRACE_SERVED = new AtomicLong();

    /** Brackets a probe's own resolution reads out of the gated counters. See {@link #inProbeRead}. */
    public static boolean beginProbeRead() {
        boolean prev = inProbeRead;
        inProbeRead = true;
        return prev;
    }

    public static void endProbeRead(boolean prev) {
        inProbeRead = prev;
    }

    public static long bridgeReadsCount() {
        return BRIDGE_READS.get();
    }

    public static long bridgeHitsCount() {
        return BRIDGE_HITS.get();
    }

    public static long graceServedCount() {
        return GRACE_SERVED.get();
    }

    /**
     * The position a ridden vehicle was PLACED at by the crossing, per cart id — always-on
     * (not probe-gated), because it is the only race-free way to judge the arrival.
     *
     * <p>Polling the cart's position after arrival cannot do it: an arriving cart that lands off
     * riding height is snapped back by {@code moveAlongTrack} within ONE tick, so a gate that
     * samples even two ticks later reads a corrected value and passes. That is exactly how the
     * first version of the ridden-arrival inversion failed to reproduce a defect the
     * {@code CARRY-TERMS} log line showed plainly — the same "assert the last thing the engine
     * mutates, not what it looks like afterwards" rule that this engagement has now paid for
     * three times.
     */
    private static final Map<Integer, net.minecraft.world.phys.Vec3> LAST_CARRY = new HashMap<>();

    public static void recordVehicleCarry(int entityId, net.minecraft.world.phys.Vec3 placedAt) {
        if (LAST_CARRY.size() > 64) {
            LAST_CARRY.clear();   // gametest/live bound; this is diagnostics, not state
        }
        LAST_CARRY.put(entityId, placedAt);
    }

    @Nullable
    public static net.minecraft.world.phys.Vec3 lastVehicleCarry(int entityId) {
        return LAST_CARRY.get(entityId);
    }

    public static String counters() {
        return "SeamCartContinuity{bridgeReads=" + BRIDGE_READS.get()
            + ", bridgeHits=" + BRIDGE_HITS.get()
            + ", graceServed=" + GRACE_SERVED.get()
            + ", marks=" + CROSSING.size() + "}";
    }

    /**
     * F6 — is this portal one face of a SEAM (stitched, mirrored continuation on the far side)?
     * Decides whether a crossing may CONSERVE the through-transform (position and interpolation
     * continue exactly; safe only because a seam's far side is continuous terrain by contract)
     * versus IP's native plane-rewind arrival, which stays for every non-seam portal (a nether
     * portal has no rail or floor continuation behind its plane).
     *
     * <p>Resolution: the SAME cell {@code bind} indexes — {@code seamCell(onPlane(origin))}, the
     * aperture cell one STEP along the portal's own normal — matched by portal UUID. NOT
     * {@code containing(getOriginPos())}: the origin lies ON the plane, so for a negative-axis
     * normal the containing cell is the far-side cell and the UUID match silently fails for that
     * whole crossing direction (the same off-by-one family SeamRegistry's unbind saga records).
     * A miss answers false — the conservative fallback is always IP's own behaviour.
     */
    public static boolean isSeamContinuous(qouteall.imm_ptl.core.portal.Portal portal) {
        try {
            SeamRegistry.SeamCell cell = SeamRegistry.lookup(
                portal.level(),
                SeamMap.seamCell(portal, SeamMap.onPlane(portal, portal.getOriginPos())));
            if (cell == null) {
                return false;
            }
            for (SeamRegistry.SeamBinding b : cell.bindings()) {
                if (b.portalUuid().equals(portal.getUUID())
                    && b.isMirrorable() && b.seamContinuous()) {
                    return true;
                }
            }
            return false;
        }
        catch (Throwable t) {
            return false;
        }
    }

    /**
     * The one entry point (both mixins). Returns {@code local} unchanged unless the seam bridge
     * has a rail to offer at {@code pos}.
     *
     * @param cart the cart doing the resolving, or null when the caller cannot supply it. The
     *             bridge speaks only for a cart that is STRADDLING the seam right now — see
     *             {@link #straddles}. A null cart declines, because an unattributed read cannot
     *             be shown to be mid-crossing.
     */
    public static BlockState railAwareState(
        Level level, BlockPos pos, BlockState local, @Nullable AbstractMinecart cart
    ) {
        if (AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SEAM_CART_RAIL
            || !SeamlessPortalsConfig.isEntityPortals()) {
            return local;
        }
        // LOCAL-FIRST: the bridge may only ADD a rail vanilla would miss, never replace one.
        if (BaseRailBlock.isRail(local)) {
            return local;
        }
        if (!(level instanceof ServerLevel src)) {
            return local;
        }
        try {
            // Zero-portal hot path: one field read + isEmpty.
            if (((SeamIndexHolder) level).seamlessportals$sectionsWithSeams().isEmpty()) {
                return local;
            }
            MinecraftServer server = src.getServer();
            if (server == null || !server.isSameThread()) {
                return local;
            }
            if (cart != null) {
                markIfOnSeam(src, cart);
            }
            for (Direction d : HORIZONTALS) {
                BlockPos owner = pos.relative(d.getOpposite());
                if (!SeamRegistry.sectionHasSeam(level, owner)) {
                    continue;
                }
                SeamRegistry.SeamCell cell = SeamRegistry.lookup(level, owner);
                if (cell == null) {
                    continue;
                }
                // THE STRADDLE TEST — the bridge speaks only while the cart is physically ON the
                // seam cell. Without it a bi-faced portal (every obsidian frame is a 4-entity
                // cluster) hands out far rails in BOTH axis directions, because each face's own
                // crossDir points the other way, and a cart resting one cell clear of the
                // aperture floats on the far world's track. Measured, not reasoned:
                // RS-CART-C caught exactly that with the direction narrowing already in place.
                if (!midCrossing(src, cart, owner)) {
                    continue;
                }
                if (!inProbeRead) {
                    BRIDGE_READS.incrementAndGet();
                }
                // crossingOnly — cart physics follows the crossing direction only; the COINCIDENT
                // backward fallback is (b)'s shape-resolution view and would serve a rail for a
                // cell in FRONT of the plane on a SINGLE-faced portal, where the straddle test
                // alone cannot help (a straddling cart looking backward). Both guards are load
                // bearing and each has its own failure case; the lever restores the unrestricted
                // view so the gate can reproduce the defect.
                SeamShadow shadow = SeamShadowBridge.shadowFor(level, cell, owner, pos, 1,
                    !AperturePassthroughLever.DISABLE_SEAM_CART_CROSS_ONLY);
                if (shadow == null) {
                    continue;
                }
                BlockState far = shadow.readLocal(pos);
                if (BaseRailBlock.isRail(far)) {
                    if (!inProbeRead) {
                        BRIDGE_HITS.incrementAndGet();
                    }
                    if (AperturePassthroughLever.SEAM_CART_PROBE) {
                        LOGGER.info("[RS-CART] bridge hit at {} (owner {} step {}): {}",
                            pos.toShortString(), owner.toShortString(), d, far);
                    }
                    return far;
                }
            }
        }
        catch (Throwable t) {
            if (!warnedOnce) {
                warnedOnce = true;
                LOGGER.warn("[RS-CART] rail-aware read failed; answering vanilla from here on"
                    + " (first failure logged once)", t);
            }
        }
        // fall through: no bridge answer
        return local;
    }

    /**
     * True when {@code cart}'s own collision box overlaps the seam cell {@code owner} — i.e. the
     * cart is sitting ON the seam, mid-crossing, which is the only state (d) exists to carry.
     *
     * <p>This is the guard that makes the one-cell reach honest. The measured stranded tick has
     * the cart barely past the plane (RS-CART-B: 0.08 blocks past a flush plane, box overlapping
     * the seam cell by 0.4), while a cart resting in the next cell clears the seam cell entirely
     * (a 0.98-wide box centred in a cell spans 0.01..0.99 of it). A cart that has travelled far
     * enough to clear the seam cell is no longer mid-crossing by any reading — either its portal
     * already fired, or it is not crossing at all, and in both cases vanilla's own answer is the
     * correct one.
     *
     * <p>A null cart declines: the two wrapped call sites both supply one, so null means an
     * unattributed caller, and an unattributed read cannot be shown to be a crossing.
     */
    /**
     * Record that {@code cart} is OCCUPYING a seam cell right now — its own centre is inside one.
     * Called once per resolution, before any direction is considered.
     *
     * <p><b>Why the centre and not the collision box.</b> The first build tested
     * {@code cart.getBoundingBox().intersects(AABB(seamCell))}, which sounds equivalent and is
     * not: a minecart is 0.98 wide, so its box reaches 0.49 past its centre, and a cart AT REST
     * in the approach cell overlapped the seam cell whenever its centre came within 0.49 of the
     * boundary — a half-block band, inside a cell the cart never leaves, in which the whole
     * bridge fired on a cell entirely in FRONT of the plane. The hovering-cart gate passed that
     * build by 0.02 blocks of spawn placement (adversarial panel, round 2). A centre is either
     * in the cell or it is not.
     */
    private static void markIfOnSeam(ServerLevel level, AbstractMinecart cart) {
        BlockPos cartCell = cart.blockPosition();
        if (!SeamRegistry.sectionHasSeam(level, cartCell)
            || SeamRegistry.lookup(level, cartCell) == null) {
            return;
        }
        CROSSING.put(cart.getId(), new CrossingMark(
            cartCell.asLong(), level.dimension().identifier().toString(), level.getGameTime()));
    }

    /**
     * Is this cart mid-crossing THIS seam cell? True iff its own centre has been inside that seam
     * cell within the last {@link #GRACE_TICKS} ticks.
     *
     * <p>History, not geometry, is what separates the two states that position alone cannot: a
     * cart stranded mid-crossing and a cart merely sitting next to a portal occupy the same cell
     * and, on a BI-FACED portal, satisfy the same direction test. Only one of them was ever ON
     * the seam. A cart that never entered a seam cell therefore never gets an answer, at any
     * distance and at any sub-cell position — which is what makes the hovering-cart gate a real
     * verdict rather than a near miss.
     *
     * <p>The grace does not widen the reach: the DEPTH bound still demands that the queried cell
     * be the immediate neighbour of a bound seam cell, so a cart two cells out gets nothing
     * whatever its mark says. It removes only the requirement that the cart still be touching the
     * seam cell at the instant of the read — the requirement the user's own ridden crossing
     * broke by 0.10 of a block.
     */
    private static boolean midCrossing(ServerLevel level, @Nullable AbstractMinecart cart,
                                       BlockPos owner) {
        if (AperturePassthroughLever.DISABLE_SEAM_CART_STRADDLE) {
            return true;   // lever: reproduce the hover on demand (rsCartLegPhantomRail)
        }
        if (cart == null) {
            return false;
        }
        CrossingMark mark = CROSSING.get(cart.getId());
        if (mark == null
            || mark.ownerCell() != owner.asLong()
            || !mark.dimension().equals(level.dimension().identifier().toString())) {
            return false;
        }
        long age = level.getGameTime() - mark.tick();
        if (age < 0 || age > GRACE_TICKS) {
            return false;
        }
        if (age > 0 && !inProbeRead) {
            GRACE_SERVED.incrementAndGet();
        }
        return true;
    }

    /**
     * Evict stale crossing marks. Registered on {@code END_SERVER_TICK} beside the other
     * continuity classes; the map only ever holds carts that touched a seam in the last
     * {@link #GRACE_TICKS} ticks, so it is small by construction — but a cart that is removed
     * mid-crossing would otherwise leave an entry behind forever.
     */
    public static void onServerTickEnd(net.minecraft.server.MinecraftServer server) {
        if (CROSSING.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        CROSSING.entrySet().removeIf(e -> now - e.getValue().tick() > GRACE_TICKS);
    }

    /**
     * True when a rail-driven ACTION at {@code (x,y,z)} would be firing on a state this bridge
     * supplied rather than on a real local block — i.e. the cell holds no rail in this level at
     * all. Used to suppress {@code OldMinecartBehavior.tick}'s ACTIVATOR_RAIL branch during a
     * stranded tick (ejecting a passenger, priming TNT or running a command block IN THE NEAR
     * LEVEL at a locally-air cell is a side effect (d) must not introduce; the far activator rail
     * does its own job when the cart actually arrives).
     *
     * <p>Under vanilla this can never be true at that call site — the branch is only reached with
     * the state read from that exact cell — so the suppression is unreachable except through the
     * bridge, and needs no lever of its own beyond the (d) master.
     */
    public static boolean actionWouldBeBridged(Level level, int x, int y, int z) {
        if (AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SEAM_CART_RAIL) {
            return false;
        }
        try {
            return !BaseRailBlock.isRail(level.getBlockState(new BlockPos(x, y, z)));
        }
        catch (Throwable t) {
            return false;   // never let an instrument-grade check break a cart tick
        }
    }
}
