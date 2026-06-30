package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.SeamlessPortalsConstants;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Off-thread per-subsystem timing aggregator. Hot paths call {@link #add(String, long)}
 * with a nanosecond duration (cheap: a map lookup + two longs under a tiny lock); a daemon
 * thread sums each named bucket and emits ONE {@code [SEAMLESS TIMERS]} line every 5s, then
 * resets. No logging or formatting ever happens on the caller's thread, so the measurement
 * cannot itself cause the stall it is measuring (same discipline as {@link RenderSpikeMonitor}).
 *
 * <p>Use to ATTRIBUTE per-tick / per-frame cost to a concrete subsystem (tickRemoteWorlds,
 * its light/entity sub-parts, particles, compile pump, chunk drain, block-mirror apply/flush,
 * the FBO render) so the stutter source is read from data, not guessed.
 */
public final class PerfTimers {

    private PerfTimers() {}

    // name -> {totalNs, count}. One small array per bucket, mutated under its own monitor.
    private static final Map<String, long[]> BUCKETS = new ConcurrentHashMap<>();
    private static volatile boolean started = false;

    public static void add(String name, long ns) {
        long[] b = BUCKETS.computeIfAbsent(name, k -> new long[2]);
        synchronized (b) {
            b[0] += ns;
            b[1]++;
        }
        if (!started) {
            started = true;
            startReporter();
        }
    }

    /** Convenience: time {@code r} and record it under {@code name}. */
    public static void time(String name, Runnable r) {
        long t0 = System.nanoTime();
        try {
            r.run();
        } finally {
            add(name, System.nanoTime() - t0);
        }
    }

    private static void startReporter() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException e) {
                    return;
                }
                StringBuilder sb = new StringBuilder("[SEAMLESS TIMERS] last 5s (total ms / calls):");
                boolean any = false;
                // Sort by total time descending for readability.
                java.util.List<Map.Entry<String, long[]>> entries =
                    new java.util.ArrayList<>(BUCKETS.entrySet());
                entries.sort((a, b) -> {
                    long ta, tb;
                    synchronized (a.getValue()) { ta = a.getValue()[0]; }
                    synchronized (b.getValue()) { tb = b.getValue()[0]; }
                    return Long.compare(tb, ta);
                });
                for (Map.Entry<String, long[]> e : entries) {
                    long total, count;
                    long[] v = e.getValue();
                    synchronized (v) {
                        total = v[0];
                        count = v[1];
                        v[0] = 0L;
                        v[1] = 0L;
                    }
                    if (count > 0) {
                        any = true;
                        sb.append(String.format("  %s=%.1fms/%d", e.getKey(), total / 1_000_000.0, count));
                    }
                }
                if (any) {
                    SeamlessPortalsConstants.LOGGER.info(sb.toString());
                }
            }
        }, "seamless-perf-timers");
        t.setDaemon(true);
        t.start();
    }
}
