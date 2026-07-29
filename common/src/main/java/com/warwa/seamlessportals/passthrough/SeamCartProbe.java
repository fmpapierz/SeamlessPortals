package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RS (d) MINECART-CROSSING INSTRUMENT ({@code -Dseamlessportals.seamCartProbe=true}, DEFAULT-OFF).
 *
 * <p>Settles recon §5.5 question 5 — the ordering of the live teleport machinery against
 * {@code comeOffTrack} — BY MEASUREMENT, before any (d) design is committed. The derived timeline
 * (from reading, to be confirmed or refuted here): the crossing is detected inside
 * {@code Portal.tick()} ({@code SERVER_PORTAL_TICK_SIGNAL} → eye-segment test), so entity iteration
 * order decides whether detection lands the same tick as the cart's crossing move or one tick
 * later; the teleport itself always runs at {@code END_SERVER_TICK} of the detecting tick
 * ({@code ServerTaskList}). That predicts a stranded window of 0–1 cart behaviour ticks in the
 * NEAR level at a position past the plane — where {@code OldMinecartBehavior.tick}'s same-level
 * rail resolution ({@code getCurrentBlockPosOrRailBelow} + {@code isRail}) decides
 * {@code moveAlongTrack} vs {@code comeOffTrack}.
 *
 * <p>Three instruments, all server-thread, all no-ops unless the lever is armed:
 * <ul>
 *   <li><b>SAMPLE</b> — a per-tick line per watched cart from {@code END_SERVER_TICK}
 *       (dimension, position, velocity, on-rails flag, the resolution cell and whether it is a
 *       rail). ⚠ Ordering caveat, per the house instrument rule: this handler and
 *       {@code ServerTaskList}'s teleport-executing handler share the same event; registration
 *       order decides which runs first, so the SAMPLE at the teleport tick may show either the
 *       pre- or post-teleport state. The EVT lines carry the precise instant.</li>
 *   <li><b>COME-OFF-TRACK</b> — an event line from {@code AbstractMinecart.comeOffTrack} HEAD
 *       (mixin), with the resolution cell and its actual state, plus a per-cart counter the
 *       gametest legs read.</li>
 *   <li><b>EVT</b> — teleport-path event lines from {@code ServerTeleportationManager}
 *       (queued / each skip reason / run begin / run end), so "never teleported" is attributable
 *       to a NAMED gate instead of silence.</li>
 * </ul>
 *
 * <p>Watch set semantics: ids registered via {@link #watch}; an EMPTY set means "every minecart"
 * so a live client run can arm the lever without gametest plumbing. Ids survive the cross-dim
 * recreate ({@code changeEntityDimension} and {@code teleportVehicleAcrossDimensions} both
 * {@code setId(oldEntity.getId())}), so a watched cart stays watched across the crossing.
 */
public final class SeamCartProbe {

    private SeamCartProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[RS-CART] ";

    /** Server-thread confined (watch/unwatch called via server tasks; all readers server-side). */
    private static final Set<Integer> WATCHED = new HashSet<>();
    private static final Map<Integer, Integer> COME_OFF_TRACK_COUNTS = new HashMap<>();

    public static void watch(int entityId) {
        if (!AperturePassthroughLever.SEAM_CART_PROBE) {
            return;
        }
        WATCHED.add(entityId);
        LOGGER.info(TAG + "watching cart id={}", entityId);
    }

    /** Clears the watch set AND the counters — gametest leg teardown. */
    public static void clear() {
        WATCHED.clear();
        COME_OFF_TRACK_COUNTS.clear();
    }

    public static int comeOffTrackCount(int entityId) {
        return COME_OFF_TRACK_COUNTS.getOrDefault(entityId, 0);
    }

    private static boolean isWatched(Entity entity) {
        if (!AperturePassthroughLever.SEAM_CART_PROBE) {
            return false;
        }
        if (!(entity instanceof AbstractMinecart)) {
            return false;
        }
        return WATCHED.isEmpty() || WATCHED.contains(entity.getId());
    }

    /** Called from {@code MixinAbstractMinecartSeamCart} at {@code comeOffTrack} HEAD. */
    public static void onComeOffTrack(AbstractMinecart cart, ServerLevel level) {
        if (!isWatched(cart)) {
            return;
        }
        COME_OFF_TRACK_COUNTS.merge(cart.getId(), 1, Integer::sum);
        // Bracketed: getCurrentBlockPosOrRailBelow's reads are WRAPPED, so an unbracketed probe
        // call would feed the counters the gates use as coverage evidence — the instrument
        // certifying the feature. See SeamCartContinuity.inProbeRead.
        boolean prev = SeamCartContinuity.beginProbeRead();
        BlockPos cell;
        try {
            cell = cart.getCurrentBlockPosOrRailBelow();
        }
        finally {
            SeamCartContinuity.endProbeRead(prev);
        }
        LOGGER.info(TAG + "t={} COME-OFF-TRACK id={} dim={} pos={} vel={} onGround={} cell={} state={}",
            level.getGameTime(), cart.getId(), level.dimension().identifier(),
            fmt(cart.position()), fmt(cart.getDeltaMovement()), cart.onGround(),
            cell.toShortString(), level.getBlockState(cell));
    }

    /** Teleport-path event lines ({@code ServerTeleportationManager} call sites). */
    public static void event(Entity entity, String msg) {
        if (!isWatched(entity)) {
            return;
        }
        LOGGER.info(TAG + "t={} EVT {} id={} dim={} pos={} vel={}",
            entity.level().getGameTime(), msg, entity.getId(),
            entity.level().dimension().identifier(),
            fmt(entity.position()), fmt(entity.getDeltaMovement()));
    }

    /**
     * Per-tick SAMPLE per watched cart. Registered in {@code AperturePassthroughInit}.
     *
     * <p>An EMPTY watch set means "every minecart", matching {@link #isWatched} — this class's
     * javadoc advertises exactly that for live runs, and the first build broke the promise by
     * returning early on an empty set, so a live {@code -PseamCartProbe} round got every channel
     * EXCEPT the per-tick one (adversarial panel, round 2; confirmed against the user's own
     * 2026-07-28 log, which contains no SAMPLE line at all). Live sampling is capped at
     * {@link #LIVE_SAMPLE_CAP} carts per tick so a rail yard cannot flood the log — and says so
     * when it truncates, because a silent cap is how an instrument starts lying.
     */
    public static void onServerTickEnd(MinecraftServer server) {
        if (!AperturePassthroughLever.SEAM_CART_PROBE) {
            return;
        }
        if (WATCHED.isEmpty()) {
            sampleAllCarts(server);
            return;
        }
        for (int id : WATCHED) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity e = level.getEntity(id);
                if (e instanceof AbstractMinecart cart) {
                    // Bracketed out of the gated counters — see onComeOffTrack's note.
                    boolean prev = SeamCartContinuity.beginProbeRead();
                    BlockPos cell;
                    try {
                        cell = cart.getCurrentBlockPosOrRailBelow();
                    }
                    finally {
                        SeamCartContinuity.endProbeRead(prev);
                    }
                    var cellState = level.getBlockState(cell);
                    LOGGER.info(TAG + "t={} SAMPLE id={} dim={} pos={} vel={} hSpeed={} onRails={}"
                            + " cell={} cellIsRail={}",
                        level.getGameTime(), id, level.dimension().identifier(),
                        fmt(cart.position()), fmt(cart.getDeltaMovement()),
                        String.format(Locale.ROOT, "%.4f", cart.getDeltaMovement().horizontalDistance()),
                        cart.isOnRails(), cell.toShortString(), BaseRailBlock.isRail(cellState));
                    break;
                }
            }
        }
    }

    /** Live-run sampling cap — see {@link #onServerTickEnd}. */
    private static final int LIVE_SAMPLE_CAP = 8;

    /** Ticks between the live heartbeat lines (1 Hz), so silence still proves coverage. */
    private static final int LIVE_HEARTBEAT_TICKS = 20;

    /**
     * Live mode (nobody called {@link #watch}): sample only the carts that are AT a seam, every
     * tick, plus a 1 Hz heartbeat counting the rest.
     *
     * <p>The first build sampled every cart every tick and produced 40,104 SAMPLE lines plus
     * 4,962 truncation notices in one live session — 96% of the log, for a session whose actual
     * findings were two numbers. A per-tick line per entity is also the shape that costs ~130 ms
     * log4j stalls on the render thread (the aim probe's recorded scar, which is why that one is
     * 1 Hz-latched). Near-seam carts are rare and are exactly the crossing window, so they get
     * full per-tick resolution and everything else costs one line per second.
     */
    private static void sampleAllCarts(MinecraftServer server) {
        int atSeam = 0;
        int elsewhere = 0;
        long now = server.overworld().getGameTime();
        boolean heartbeat = now % LIVE_HEARTBEAT_TICKS == 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (!(e instanceof AbstractMinecart cart)) {
                    continue;
                }
                if (!nearSeam(level, cart)) {
                    elsewhere++;
                    continue;
                }
                atSeam++;
                if (atSeam <= LIVE_SAMPLE_CAP) {
                    sampleOne(level, cart);
                }
            }
        }
        if (atSeam > LIVE_SAMPLE_CAP) {
            LOGGER.info(TAG + "SAMPLE truncated — {} carts at seams, {} sampled (cap {})",
                atSeam, LIVE_SAMPLE_CAP, LIVE_SAMPLE_CAP);
        }
        if (heartbeat && (atSeam > 0 || elsewhere > 0)) {
            LOGGER.info(TAG + "heartbeat — {} cart(s) at a seam, {} elsewhere (only seam-adjacent"
                + " carts are sampled per tick)", atSeam, elsewhere);
        }
    }

    /** Is this cart on, or one step from, a bound seam cell? The crossing window and nothing else. */
    private static boolean nearSeam(ServerLevel level, AbstractMinecart cart) {
        BlockPos cell = cart.blockPosition();
        if (!SeamRegistry.sectionHasSeam(level, cell)) {
            return false;
        }
        if (SeamRegistry.lookup(level, cell) != null) {
            return true;
        }
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (SeamRegistry.lookup(level, cell.relative(d)) != null) {
                return true;
            }
        }
        return false;
    }

    private static void sampleOne(ServerLevel level, AbstractMinecart cart) {
        boolean prev = SeamCartContinuity.beginProbeRead();
        BlockPos cell;
        try {
            cell = cart.getCurrentBlockPosOrRailBelow();
        }
        finally {
            SeamCartContinuity.endProbeRead(prev);
        }
        LOGGER.info(TAG + "t={} SAMPLE id={} dim={} pos={} vel={} hSpeed={} onRails={}"
                + " ridden={} cell={} cellIsRail={}",
            level.getGameTime(), cart.getId(), level.dimension().identifier(),
            fmt(cart.position()), fmt(cart.getDeltaMovement()),
            String.format(Locale.ROOT, "%.4f", cart.getDeltaMovement().horizontalDistance()),
            cart.isOnRails(), !cart.getPassengers().isEmpty(), cell.toShortString(),
            BaseRailBlock.isRail(level.getBlockState(cell)));
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }
}
