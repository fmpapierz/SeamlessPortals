package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ★ THE FRACTIONAL INSTRUMENT — {@code -Dseamlessportals.seamFractionalProbe=true}, DEFAULT-OFF.
 *
 * <p>Built 2026-08-02 after a live round disproved a green gate: {@code rsSeamCollisionGate}
 * measured {@code span=0.5} on its own fixture while the user's portal showed a whole block, cut by
 * nothing but the renderer. A fixture that passes proves its own case and no other — so this probe
 * answers the only question that matters, on the user's own geometry: <b>does the hook fire for the
 * cell they are looking at, and what did it decide?</b>
 *
 * <h2>Limiter, stated as the house rule requires</h2>
 *
 * <p>{@code keptShape} sits on the entity-collision funnel — the highest-volume call site in the
 * game — so an unbounded line here would starve the log of everything else within a second. Two
 * mechanisms, deliberately separate:
 * <ul>
 *   <li>the DECISION line is emitted at most {@link #DECISION_BUDGET} times TOTAL and only for cells
 *       that are actually bound seam cells (rare — an aperture is a handful of cells), so the
 *       interesting case has its own reserve and cannot be crowded out by the common one;</li>
 *   <li>the SUMMARY line is latched to at most one per {@link #SUMMARY_NANOS}.</li>
 * </ul>
 * That split is the lesson from the (e) session, where 1,351 per-tick sample lines starved the
 * single line the round existed to capture.
 */
public final class SeamFractionalProbe {

    private SeamFractionalProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * ★ PER-VERDICT BUDGETS, not one shared pool.
     *
     * <p>The first build used a single 40-line budget and it was consumed entirely by {@code NO CUT}
     * lines for ordinary pre-existing blocks — so on the live round the {@code CLAIM} and {@code CUT}
     * lines the round existed to capture were starved, and I had to infer from their absence. That
     * is precisely the limiter defect the (e) session recorded (1,351 routine SAMPLE lines starving
     * one SET-PASSENGERS line), reproduced by the person who wrote it down. Each verdict now has its
     * own reserve, so the common case cannot crowd out the rare one.
     */
    private static final int BUDGET_PER_VERDICT = 25;
    private static final long SUMMARY_NANOS = 1_000_000_000L;

    private static final AtomicLong calls = new AtomicLong();
    private static final AtomicLong seamCellHits = new AtomicLong();
    private static final AtomicLong cutsApplied = new AtomicLong();
    private static final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> budgets =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicLong lastSummary = new AtomicLong();

    /** Every {@code keptShape} entry, whether or not it is a seam cell. Counter only. */
    public static void onCall() {
        calls.incrementAndGet();
    }

    /**
     * ★ THE LINE THIS PROBE EXISTS FOR — a cell that IS a bound seam cell, with the decision and,
     * when the answer is "no cut", the REASON. "did nothing" and "never ran" must never be
     * indistinguishable in a live log; that ambiguity cost a whole round once already.
     */
    public static void onSeamCell(BlockPos pos, String verdict, String detail) {
        seamCellHits.incrementAndGet();
        AtomicLong used = budgets.computeIfAbsent(verdict, k -> new AtomicLong());
        if (used.get() >= BUDGET_PER_VERDICT) {
            return;
        }
        used.incrementAndGet();
        LOGGER.info("[SEAM FRAC] cell {} -> {} | {}", pos, verdict, detail);
    }

    public static void onCut() {
        cutsApplied.incrementAndGet();
    }

    /** 1 Hz latched summary. Called from the same hook; cheap when the probe is off. */
    public static void tickSummary() {
        long now = System.nanoTime();
        long prev = lastSummary.get();
        if (now - prev < SUMMARY_NANOS) {
            return;
        }
        if (!lastSummary.compareAndSet(prev, now)) {
            return;
        }
        LOGGER.info("[SEAM FRAC] last 1s: keptShape calls={} seamCellHits={} cutsApplied={}"
                + " | active={} collisionActive={}",
            calls.getAndSet(0), seamCellHits.getAndSet(0), cutsApplied.getAndSet(0),
            SeamFractional.active(), SeamFractional.collisionActive());
    }

    /** One line at world join, so "the mixin never wove" is distinguishable from "it declined". */
    public static void announce() {
        LOGGER.info("[SEAM FRAC] probe armed. CUT active={} collisionActive={} — if you never see a"
                + " 'cell ... ->' line while looking at a seam block, the hook is not firing at all"
                + " (mixin not woven, or the cell is not in seamCells).",
            SeamFractional.active(), SeamFractional.collisionActive());
    }
}
