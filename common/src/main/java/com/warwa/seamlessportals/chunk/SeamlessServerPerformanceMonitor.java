package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.server.MinecraftServer;

/**
 * Server-side performance auto-scaling. Direct port of IP 1.19's
 * {@code ServerPerformanceMonitor}.
 *
 * <p>Every {@link #UPDATE_INTERVAL_NANOS} samples the server's
 * average tick time. Maps to a {@link PerformanceLevel} (good /
 * medium / bad) which feeds back into:
 * <ul>
 *   <li>{@link SeamlessChunkVisibility#computeAdaptiveRadius} — caps
 *       the cross-dim radius when struggling.</li>
 *   <li>{@link SeamlessChunkTrackingGraph#flushPendingLoading} — caps
 *       the per-tick delivery + ticket-add budget.</li>
 * </ul>
 *
 * <p><b>Why this matters for SP integrated server.</b> User testing
 * found that even at radius=4, ~5-second freezes occur during heavy
 * teleport play because the SP server-thread can't keep up with
 * chunk-gen + propagation. With this monitor: when tick time exceeds
 * 40 ms (medium) or 80 ms (bad), our system AUTO-SCALES radii down
 * (and burst budget). When server recovers, scales back up.
 *
 * <p>Without this feedback loop, our radii are hardcoded at the
 * worst-case-tolerable point. WITH the feedback loop, we can ship
 * higher defaults that scale themselves down under load.
 *
 * <p><b>Thresholds (from IP 1.19):</b>
 * <ul>
 *   <li>tick time &lt; 40 ms → {@link PerformanceLevel#good}</li>
 *   <li>tick time &lt; 80 ms → {@link PerformanceLevel#medium}</li>
 *   <li>else → {@link PerformanceLevel#bad}</li>
 * </ul>
 */
public final class SeamlessServerPerformanceMonitor {

    private SeamlessServerPerformanceMonitor() {}

    /**
     * Re-sample every 5 seconds. IP uses 20 seconds (their feedback
     * loop has more headroom because their MP server has dedicated
     * CPU). On the SP integrated server, bursts cause 4-6 second
     * freezes that fully complete inside a single 20-second window —
     * leading to "stuck in portal" before the monitor can drop the
     * scale. 5 seconds is fast enough to react to a building burst.
     */
    private static final long UPDATE_INTERVAL_NANOS = 5L * 1_000_000_000L;

    private static volatile PerformanceLevel level = PerformanceLevel.good;
    private static long lastUpdateNanos = 0L;

    /** Public getter. {@link #level} starts at {@code good} until first sample. */
    public static PerformanceLevel getLevel() {
        return level;
    }

    /**
     * Per-tick monitor. Cheap when not sampling (single timestamp
     * compare). Wire into {@code ServerTickEvents.END_SERVER_TICK}.
     */
    public static void tick(MinecraftServer server) {
        if (server == null) return;
        long currentNanos = System.nanoTime();
        if (currentNanos - lastUpdateNanos < UPDATE_INTERVAL_NANOS) return;
        lastUpdateNanos = currentNanos;

        long tickNanos = server.getAverageTickTimeNanos();
        float tickMs = tickNanos / 1_000_000.0f;
        PerformanceLevel newLevel = PerformanceLevel.getServerPerformanceLevel(tickMs);
        if (newLevel != level) {
            level = newLevel;
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PERF] Server performance level: {} (avg tick {}ms)",
                newLevel, String.format("%.1f", tickMs));
        }
    }

    public static void cleanup() {
        level = PerformanceLevel.good;
        lastUpdateNanos = 0L;
    }
}
