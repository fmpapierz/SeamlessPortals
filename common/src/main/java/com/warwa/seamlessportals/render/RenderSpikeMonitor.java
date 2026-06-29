package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.SeamlessPortalsConstants;

/**
 * Off-render-thread frame-gap telemetry.
 *
 * <p>The render thread only stamps a {@code nanoTime} and bumps a couple of plain
 * counters per frame ({@link #onFrame()}) — NO logging, NO allocation. Dedicated
 * daemon threads emit the summaries and the stall stack dumps, so a log4j/IO/GC-
 * introspection cost can never land on the frame loop.
 *
 * <p>Attribution: each 5s window reports the max single-frame FBO portal-view render
 * time ({@link #recordFbo}) and the GC delta. A separate WATCHDOG daemon dumps the
 * render thread's stack when a frame stalls > {@link #STALL_MS} ms, so the exact
 * method eating a long gap is captured (mesh upload? a lock? chunk save?).
 */
public final class RenderSpikeMonitor {

    /** A frame gap at or above this is counted as a spike. */
    private static final long SPIKE_MS = 50L;
    private static final long REPORT_PERIOD_MS = 5000L;
    /** Render-thread stall threshold for the stack-dump watchdog (temp diagnostic). */
    private static final long STALL_MS = 150L;

    private static volatile long lastFrameNanos = 0L;
    private static volatile int spikeCount = 0;
    private static volatile long maxGapMs = 0L;
    private static volatile int totalFrames = 0;
    private static volatile long maxFboMs = 0L;
    private static volatile Thread renderThread;
    private static volatile boolean started = false;

    private RenderSpikeMonitor() {}

    /** Render-thread hot path: stamp + count only. Cheap; never logs, never allocates. */
    public static void onFrame() {
        long now = System.nanoTime();
        long prev = lastFrameNanos;
        lastFrameNanos = now;
        totalFrames++;
        if (renderThread == null) renderThread = Thread.currentThread();
        if (prev != 0L) {
            long gapMs = (now - prev) / 1_000_000L;
            if (gapMs >= SPIKE_MS) {
                spikeCount++;
                if (gapMs > maxGapMs) maxGapMs = gapMs;
            }
        }
        if (!started) {
            started = true;
            startReporter();
            startWatchdog();
        }
    }

    /** Record one frame's portal-view FBO render cost (ms). Render-thread; field write only. */
    public static void recordFbo(long ms) {
        if (ms > maxFboMs) maxFboMs = ms;
    }

    private static void startReporter() {
        Thread t = new Thread(() -> {
            java.util.List<java.lang.management.GarbageCollectorMXBean> gcBeans =
                java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
            long lastGcCount = 0L, lastGcMs = 0L;
            for (java.lang.management.GarbageCollectorMXBean gc : gcBeans) {
                long c = gc.getCollectionCount();
                if (c > 0) lastGcCount += c;
                long m = gc.getCollectionTime();
                if (m > 0) lastGcMs += m;
            }
            while (true) {
                try {
                    Thread.sleep(REPORT_PERIOD_MS);
                } catch (InterruptedException e) {
                    return;
                }
                int spikes = spikeCount;
                long maxMs = maxGapMs;
                int frames = totalFrames;
                long fboMs = maxFboMs;
                spikeCount = 0;
                maxGapMs = 0L;
                totalFrames = 0;
                maxFboMs = 0L;
                long gcCount = 0L, gcMs = 0L;
                for (java.lang.management.GarbageCollectorMXBean gc : gcBeans) {
                    long c = gc.getCollectionCount();
                    if (c > 0) gcCount += c;
                    long m = gc.getCollectionTime();
                    if (m > 0) gcMs += m;
                }
                long dGcCount = gcCount - lastGcCount;
                long dGcMs = gcMs - lastGcMs;
                lastGcCount = gcCount;
                lastGcMs = gcMs;
                if (spikes > 0) {
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS PERF] last {}s: {} gaps >={}ms (max {}ms) | maxFboRender={}ms | GC {}coll {}ms | over {} frames",
                        REPORT_PERIOD_MS / 1000L, spikes, SPIKE_MS, maxMs, fboMs, dGcCount, dGcMs, frames);
                }
            }
        }, "seamless-perf-monitor");
        t.setDaemon(true);
        t.start();
    }

    /**
     * TEMP stall watchdog: polls the render-thread heartbeat every 50ms and, when a
     * frame has been in flight > STALL_MS, dumps that thread's stack ONCE — capturing
     * the exact method eating the gap. The dump runs HERE (daemon), never on the render
     * thread, so it can't trigger the log4j-on-render-thread stall.
     */
    private static void startWatchdog() {
        Thread t = new Thread(() -> {
            long lastDumped = 0L;
            while (true) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    return;
                }
                Thread rt = renderThread;
                long hb = lastFrameNanos;
                if (rt == null || hb == 0L) continue;
                long stuckMs = (System.nanoTime() - hb) / 1_000_000L;
                if (stuckMs >= STALL_MS && hb != lastDumped) {
                    lastDumped = hb;
                    StackTraceElement[] stack = rt.getStackTrace();
                    StringBuilder sb = new StringBuilder();
                    sb.append("[SEAMLESS STUCK] render thread stalled ~").append(stuckMs).append("ms in:");
                    int n = 0;
                    for (StackTraceElement el : stack) {
                        sb.append("\n  at ").append(el);
                        if (++n >= 24) break;
                    }
                    SeamlessPortalsConstants.LOGGER.warn(sb.toString());
                }
            }
        }, "seamless-stall-watchdog");
        t.setDaemon(true);
        t.start();
    }
}
