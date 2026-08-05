package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamOccupancy;
import com.warwa.seamlessportals.passthrough.SeamRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.mixin.client.particle.IEParticle;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ★ THE PARTICLE SEAM INSTRUMENT — {@code -Dseamlessportals.seamFractionalProbe=true},
 * DEFAULT-OFF, counters only, ZERO behavior when disarmed and zero behavior change when armed.
 *
 * <p>Built for the five open evidence questions in {@code migration/PARTICLE_SEAM_HANDOFF.md}
 * (state at tip {@code 51b3eab}: everything bleeding, far side broken, 12 failed rounds).
 * The handoff's standing order is INSTRUMENT FIRST: the prime suspect (the round-37
 * open-aperture rule ping-ponging every particle between levels every tick once the round-38
 * engine-side driver unmasked it) is a hypothesis, and hypotheses here have lost to live
 * evidence before (the round-31 invokespecial reachability claim). These counters exist to
 * confirm or refute with numbers.
 *
 * <h2>Sections (each its own 1 Hz-latched summary line, house limiter rules)</h2>
 * <ul>
 *   <li><b>TP</b> — the seam teleport (Q1 ping-pong): totals per branch (open-cell vs
 *       owned-continuation), per class, per direction, DISTINCT particles per second, lifetime
 *       max teleports for one particle, count of particles above the ping-pong threshold.</li>
 *   <li><b>DRV</b> — the two candidate drivers (Q2): engine-redirect ticks vs base
 *       {@code Particle.tick} RETURN executions, per class. A class ticked by the engine whose
 *       base counter stays 0 proves an override chain bypasses the base body.</li>
 *   <li><b>MAIN</b> — main-pass extract decisions (Q3/Q4): world/window/band drops and
 *       extracted, plus band drops of RECENTLY TELEPORTED particles (Q4).</li>
 *   <li><b>DEST</b> — isolated dest-pass extract decisions per world filter (Q3): where the
 *       "painting in the portal render from side b" is billed.</li>
 *   <li><b>CENSUS</b> — 1 Hz engine population: per level, per class, and how many particles
 *       currently sit inside seam cells (Q5's live half).</li>
 * </ul>
 *
 * <p>The per-crossing line the round-35 teleport logged unconditionally is now BUDGETED
 * ({@link #CROSSING_LINES_PER_SEC}/s, remainder counted in the TP summary): if the ping-pong is
 * real that line fires hundreds of times per second, and an unbounded line starving the log of
 * the summary it feeds is precisely the (e)-session limiter defect.
 */
public final class SeamParticleProbe {

    private SeamParticleProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    public static boolean armed() {
        return AperturePassthroughLever.SEAM_FRACTIONAL_PROBE;
    }

    private static final long SUMMARY_NANOS = 1_000_000_000L;
    private static final int CROSSING_LINES_PER_SEC = 10;
    /** Lifetime teleport count above which a particle is called a ping-ponger. */
    private static final int PINGPONG_THRESHOLD = 10;

    private static volatile boolean announced = false;

    private static void announceOnce() {
        if (!announced) {
            announced = true;
            LOGGER.info("[SEAM FRAC][PTCL] particle probe armed. Sections: TP (teleport/Q1),"
                + " DRV (drivers/Q2+Q5), MAIN (main extract/Q3+Q4), DEST (isolated extract/Q3),"
                + " CENSUS (population/Q5). A section with zero activity prints nothing.");
        }
    }

    // ------------------------------------------------------------------ TP (Q1)

    private static final AtomicLong tpOpenCell = new AtomicLong();
    private static final AtomicLong tpOwnedContinuation = new AtomicLong();
    private static final AtomicLong tpConsumedNoDest = new AtomicLong();
    private static final AtomicLong tpConsumedRolled = new AtomicLong();
    private static final AtomicLong tpStayOwned = new AtomicLong();
    private static final AtomicLong crossingLinesSuppressed = new AtomicLong();
    private static final AtomicLong crossingLinesThisSec = new AtomicLong();
    private static final ConcurrentHashMap<String, AtomicLong> tpByClass = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> tpByDirection = new ConcurrentHashMap<>();

    /** Lifetime per-particle {teleport count, last teleport game time}. Probe-only, weak. */
    private static final Map<Particle, long[]> tpHistory =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong lifetimeTeleports = new AtomicLong();
    private static final AtomicLong lifetimeConsumed = new AtomicLong();
    private static final Set<Particle> distinctThisSec =
        Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final AtomicLong maxTeleportsOneParticle = new AtomicLong();
    private static final Set<Particle> pingPongers =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** A particle stood in its cell's owned half — the "it belongs here" early-out. */
    public static void onStayOwned() {
        tpStayOwned.incrementAndGet();
    }

    private static final AtomicLong tpOpenNoCrossing = new AtomicLong();

    /** r40 crossing gate: an open-cell occupant with no plane transition this tick — rests. */
    public static void onOpenNoCrossing() {
        tpOpenNoCrossing.incrementAndGet();
    }

    /**
     * One executed teleport. {@code openCell} distinguishes the round-37 open-aperture branch
     * from the owned-half material continuation; the two suspects have different signatures.
     */
    public static void onTeleport(
        Particle particle, ClientLevel from, ClientLevel to, BlockPos cell, BlockPos dest,
        boolean openCell
    ) {
        announceOnce();
        lifetimeTeleports.incrementAndGet();
        (openCell ? tpOpenCell : tpOwnedContinuation).incrementAndGet();
        bump(tpByClass, particle.getClass().getSimpleName());
        bump(tpByDirection, from.dimension().identifier().getPath()
            + "->" + to.dimension().identifier().getPath());
        distinctThisSec.add(particle);
        long gameTime = from.getGameTime();
        long[] hist = tpHistory.computeIfAbsent(particle, p -> new long[2]);
        long count;
        synchronized (hist) {
            count = ++hist[0];
            hist[1] = gameTime;
        }
        maxTeleportsOneParticle.accumulateAndGet(count, Math::max);
        if (count == PINGPONG_THRESHOLD) {
            pingPongers.add(particle);
        }
        if (crossingLinesThisSec.incrementAndGet() <= CROSSING_LINES_PER_SEC) {
            LOGGER.info("[SEAM FRAC] particle crossed the seam {} -> {} ({} -> {})"
                    + " [class={} branch={} lifetimeCrossings={}]",
                cell, dest, from.dimension().identifier(), to.dimension().identifier(),
                particle.getClass().getSimpleName(), openCell ? "OPEN" : "OWNED", count);
        } else {
            crossingLinesSuppressed.incrementAndGet();
        }
    }

    /** A crosser consumed instead of moved (no dest level / rolled portal). Rare; own counters. */
    public static void onConsumed(boolean rolledPortal) {
        announceOnce();
        lifetimeConsumed.incrementAndGet();
        (rolledPortal ? tpConsumedRolled : tpConsumedNoDest).incrementAndGet();
    }

    /**
     * Lifetime aggregates for a measurement leg's closing line — the per-second summaries scroll,
     * this one states the verdict-relevant numbers once. Never resets.
     */
    public static void legReport(String tag) {
        LOGGER.info("[SEAM FRAC][PTCL] {} LIFETIME: teleports={} consumed={}"
                + " maxLifetimeCrossingsOneParticle={} pingPongers(>= {} crossings)={}",
            tag, lifetimeTeleports.get(), lifetimeConsumed.get(),
            maxTeleportsOneParticle.get(), PINGPONG_THRESHOLD, pingPongers.size());
    }

    /** Q4's correlation key: did this particle teleport within the last {@code window} ticks? */
    public static boolean teleportedWithin(Particle particle, ClientLevel level, int window) {
        long[] hist = tpHistory.get(particle);
        if (hist == null) {
            return false;
        }
        synchronized (hist) {
            return level.getGameTime() - hist[1] <= window;
        }
    }

    // ------------------------------------------------------------ DRV (Q2 + Q5)

    private static final ConcurrentHashMap<String, AtomicLong> engineTicks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> baseTickReturns = new ConcurrentHashMap<>();

    /** The round-38 engine-side driver saw this particle's tick (ParticleGroup.tickParticle). */
    public static void onEngineTick(Particle particle) {
        bump(engineTicks, particle.getClass().getSimpleName());
    }

    /** The BASE {@code Particle.tick()} body ran to RETURN (the round-35 driver's anchor). */
    public static void onBaseTickReturn(Particle particle) {
        bump(baseTickReturns, particle.getClass().getSimpleName());
    }

    // -------------------------------------------------------- MAIN extract (Q3/Q4)

    private static final AtomicLong mainSeen = new AtomicLong();
    private static final AtomicLong mainWorldDrop = new AtomicLong();
    private static final AtomicLong mainWindowDrop = new AtomicLong();
    private static final AtomicLong mainBandDrop = new AtomicLong();
    private static final AtomicLong mainBandDropRecentTp = new AtomicLong();
    private static final AtomicLong mainExtracted = new AtomicLong();
    private static final ConcurrentHashMap<String, AtomicLong> mainWindowDropByClass =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> mainBandDropByClass =
        new ConcurrentHashMap<>();

    public static void onMainSeen() {
        mainSeen.incrementAndGet();
    }

    public static void onMainWorldDrop() {
        mainWorldDrop.incrementAndGet();
    }

    public static void onMainWindowDrop(Particle particle) {
        mainWindowDrop.incrementAndGet();
        bump(mainWindowDropByClass, particle.getClass().getSimpleName());
    }

    public static void onMainBandDrop(Particle particle, ClientLevel level) {
        mainBandDrop.incrementAndGet();
        bump(mainBandDropByClass, particle.getClass().getSimpleName());
        if (level != null && teleportedWithin(particle, level, 3)) {
            mainBandDropRecentTp.incrementAndGet();
        }
    }

    public static void onMainExtracted() {
        mainExtracted.incrementAndGet();
    }

    // --------------------------------------------------------- DEST extract (Q3)

    /** Per world-filter dim: [seen, worldDrop, shouldRenderDrop, frustumDrop, emptinessDrop, extracted]. */
    private static final ConcurrentHashMap<String, AtomicLong[]> destByDim = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> destExtractedByClass =
        new ConcurrentHashMap<>();
    private static final AtomicLong destCalls = new AtomicLong();

    public static final int DEST_SEEN = 0, DEST_WORLD_DROP = 1, DEST_SHOULD_RENDER_DROP = 2,
        DEST_FRUSTUM_DROP = 3, DEST_EMPTINESS_DROP = 4, DEST_EXTRACTED = 5;

    public static void onDestCall() {
        destCalls.incrementAndGet();
    }

    public static void onDest(ClientLevel worldFilter, int slot, Particle particle) {
        AtomicLong[] row = destByDim.computeIfAbsent(
            worldFilter.dimension().identifier().getPath(),
            k -> {
                AtomicLong[] r = new AtomicLong[6];
                for (int i = 0; i < 6; i++) {
                    r[i] = new AtomicLong();
                }
                return r;
            });
        row[slot].incrementAndGet();
        if (slot == DEST_EXTRACTED) {
            bump(destExtractedByClass, particle.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------- CENSUS (Q5)

    /**
     * 1 Hz engine walk, called from the tick driver (client thread — the walk happens between
     * group ticks, never concurrent with them). The walker is injected by the caller because the
     * engine's group map is package-private (the accessor lives in mixin code).
     */
    public interface EngineWalker {
        void walk(CensusSink sink);
    }

    public interface CensusSink {
        void particle(Particle particle);
    }

    private static final AtomicLong lastCensus = new AtomicLong();

    public static void maybeCensus(EngineWalker walker) {
        long now = System.nanoTime();
        long prev = lastCensus.get();
        if (now - prev < SUMMARY_NANOS || !lastCensus.compareAndSet(prev, now)) {
            return;
        }
        Map<String, Integer> byDim = new java.util.TreeMap<>();
        Map<String, Integer> byClass = new java.util.TreeMap<>();
        int[] counts = new int[4];   // total, inSeamCell, inOpenSeamCell, inEmptyHalf
        walker.walk(particle -> {
            counts[0]++;
            IEParticle ie = (IEParticle) particle;
            ClientLevel level = ie.portal_getWorld();
            String dim = level == null ? "NULL" : level.dimension().identifier().getPath();
            byDim.merge(dim, 1, Integer::sum);
            byClass.merge(particle.getClass().getSimpleName(), 1, Integer::sum);
            if (level != null) {
                double x = ie.portal_getX(), y = ie.portal_getY(), z = ie.portal_getZ();
                BlockPos cell = BlockPos.containing(x, y, z);
                SeamRegistry.SeamCell seam = SeamRegistry.lookup(level, cell);
                if (seam != null) {
                    counts[1]++;
                    byte owned = SeamOccupancy.occupancyOf(level, cell);
                    if (owned == 0) {
                        counts[2]++;
                    } else if (owned == SeamOccupancy.HALF_POSITIVE
                        || owned == SeamOccupancy.HALF_NEGATIVE) {
                        if (com.warwa.seamlessportals.passthrough.SeamFractional
                            .positionInEmptyHalf(level, x, y, z)) {
                            counts[3]++;
                        }
                    }
                }
            }
        });
        ClientLevel mcLevel = Minecraft.getInstance().level;
        LOGGER.info("[SEAM FRAC][PTCL] CENSUS total={} byDim={} inSeamCells={} (openCells={},"
                + " inEmptyHalf={}) mc.level={} byClass={}",
            counts[0], byDim, counts[1], counts[2], counts[3],
            mcLevel == null ? "NULL" : mcLevel.dimension().identifier().getPath(), byClass);
    }

    // ------------------------------------------------------------------ summary

    private static final AtomicLong lastSummary = new AtomicLong();

    /** 1 Hz latched; callable from any instrumented site (tick or render thread). */
    public static void tickSummary() {
        long now = System.nanoTime();
        long prev = lastSummary.get();
        if (now - prev < SUMMARY_NANOS || !lastSummary.compareAndSet(prev, now)) {
            return;
        }
        long open = tpOpenCell.getAndSet(0);
        long ownedC = tpOwnedContinuation.getAndSet(0);
        long noDest = tpConsumedNoDest.getAndSet(0);
        long rolled = tpConsumedRolled.getAndSet(0);
        long stay = tpStayOwned.getAndSet(0);
        long openRest = tpOpenNoCrossing.getAndSet(0);
        long suppressed = crossingLinesSuppressed.getAndSet(0);
        crossingLinesThisSec.set(0);
        int distinct = distinctThisSec.size();
        distinctThisSec.clear();
        if (open + ownedC + noDest + rolled + stay + openRest > 0) {
            LOGGER.info("[SEAM FRAC][PTCL] TP last 1s: teleports={} (openCell={} ownedCont={})"
                    + " consumed(noDest={} rolled={}) stayOwned={} openRestingNoCrossing={}"
                    + " distinctParticles={}"
                    + " maxLifetimeCrossingsOneParticle={} pingPongers(>= {} crossings)={}"
                    + " crossingLinesSuppressed={} byClass={} byDirection={}",
                open + ownedC, open, ownedC, noDest, rolled, stay, openRest, distinct,
                maxTeleportsOneParticle.get(), PINGPONG_THRESHOLD, pingPongers.size(),
                suppressed, drain(tpByClass), drain(tpByDirection));
        }
        Map<String, Long> eng = drain(engineTicks);
        Map<String, Long> base = drain(baseTickReturns);
        if (!eng.isEmpty() || !base.isEmpty()) {
            LOGGER.info("[SEAM FRAC][PTCL] DRV last 1s: engineRedirectTicks={} baseTickReturns={}",
                eng, base);
        }
        long seen = mainSeen.getAndSet(0);
        if (seen > 0) {
            LOGGER.info("[SEAM FRAC][PTCL] MAIN last 1s: seen={} worldDrop={} windowDrop={}"
                    + " bandDrop={} (ofWhichTeleportedWithin3t={}) extracted={}"
                    + " windowDropByClass={} bandDropByClass={}",
                seen, mainWorldDrop.getAndSet(0), mainWindowDrop.getAndSet(0),
                mainBandDrop.getAndSet(0), mainBandDropRecentTp.getAndSet(0),
                mainExtracted.getAndSet(0), drain(mainWindowDropByClass),
                drain(mainBandDropByClass));
        }
        long dCalls = destCalls.getAndSet(0);
        if (dCalls > 0) {
            StringBuilder dims = new StringBuilder();
            for (Map.Entry<String, AtomicLong[]> e : destByDim.entrySet()) {
                AtomicLong[] r = e.getValue();
                dims.append(e.getKey()).append("[seen=").append(r[DEST_SEEN].getAndSet(0))
                    .append(" worldDrop=").append(r[DEST_WORLD_DROP].getAndSet(0))
                    .append(" shouldRenderDrop=").append(r[DEST_SHOULD_RENDER_DROP].getAndSet(0))
                    .append(" frustumDrop=").append(r[DEST_FRUSTUM_DROP].getAndSet(0))
                    .append(" emptinessDrop=").append(r[DEST_EMPTINESS_DROP].getAndSet(0))
                    .append(" extracted=").append(r[DEST_EXTRACTED].getAndSet(0)).append("] ");
            }
            LOGGER.info("[SEAM FRAC][PTCL] DEST last 1s: calls={} perDim={} extractedByClass={}",
                dCalls, dims, drain(destExtractedByClass));
        }
    }

    private static void bump(ConcurrentHashMap<String, AtomicLong> map, String key) {
        map.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    private static Map<String, Long> drain(ConcurrentHashMap<String, AtomicLong> map) {
        Map<String, Long> out = new java.util.TreeMap<>();
        for (Map.Entry<String, AtomicLong> e : map.entrySet()) {
            long v = e.getValue().getAndSet(0);
            if (v > 0) {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }
}
