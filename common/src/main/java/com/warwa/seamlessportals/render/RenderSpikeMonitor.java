package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.SeamlessPortalsConstants;

/**
 * Off-render-thread frame-gap telemetry.
 *
 * <p>The render thread only stamps a {@code nanoTime} and bumps a couple of plain
 * counters per frame ({@link #onFrame()}) — NO logging, NO allocation. A dedicated
 * daemon thread emits a single summary line every {@link #REPORT_PERIOD_MS} ms, and
 * only when spikes actually occurred.
 *
 * <p>This deliberately replaces the old inline {@code [SEAMLESS SPIKE]} logger +
 * stall-watchdog, which logged ON the render thread: in MC 26.2 a render-thread
 * {@code logger.info(...)} can stall ~130ms inside log4j's per-event
 * {@code InstantPatternDynamicFormatter} (regex {@code Pattern.compile}), so the
 * instrument added to FIND the stutter was itself CAUSING render-thread stalls. Doing
 * all logging on a daemon keeps any log4j/IO cost off the frame loop entirely.
 */
public final class RenderSpikeMonitor {

    /** A frame gap at or above this is counted as a spike. */
    private static final long SPIKE_MS = 50L;
    private static final long REPORT_PERIOD_MS = 5000L;

    private static volatile long lastFrameNanos = 0L;
    private static volatile int spikeCount = 0;
    private static volatile long maxGapMs = 0L;
    private static volatile int totalFrames = 0;
    private static volatile boolean started = false;

    private RenderSpikeMonitor() {}

    /** Render-thread hot path: stamp + count only. Cheap; never logs, never allocates. */
    public static void onFrame() {
        long now = System.nanoTime();
        long prev = lastFrameNanos;
        lastFrameNanos = now;
        totalFrames++;
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
        }
    }

    private static void startReporter() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(REPORT_PERIOD_MS);
                } catch (InterruptedException e) {
                    return;
                }
                // Snapshot + reset. Races with onFrame() are benign (telemetry only).
                int spikes = spikeCount;
                long maxMs = maxGapMs;
                int frames = totalFrames;
                spikeCount = 0;
                maxGapMs = 0L;
                totalFrames = 0;
                if (spikes > 0) {
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS PERF] last {}s: {} frame gaps >={}ms (max {}ms) over {} frames",
                        REPORT_PERIOD_MS / 1000L, spikes, SPIKE_MS, maxMs, frames);
                }
            }
        }, "seamless-perf-monitor");
        t.setDaemon(true);
        t.start();
    }
}
