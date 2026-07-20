package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * IS3 §4.7 THE CLIP DISCRIMINATOR PROBE — diagnose-first evidence for the two load-bearing
 * live-only forks the self-run round must settle (port-note {@code IS-iris-shaders-on.md} §4.7
 * discriminators 1 + 2):
 *
 * <ol>
 *   <li><b>Discriminator 1</b> — do iris NON-terrain (Patch.VANILLA) draws actually pass through
 *       {@code GlCommandEncoder.trySetup} under an active pack? (The §4.0 vanilla-mixin definedness
 *       guard is worthless if they route elsewhere.) Expect: terrain {@code ->} loc &gt;= 0 /
 *       enabled; entity + sky {@code ->} loc == -1 / disabled.</li>
 *   <li><b>Discriminator 2</b> — do the per-draw uploaders FIRE for sodium terrain under an ACTIVE
 *       iris pack? Confirmed by seeing the iris terrain program with loc &gt;= 0 / uploaded in the
 *       dump.</li>
 * </ol>
 *
 * <p>Lever-gated ({@code -Dseamlessportals.clipProbe=true}; mirror of the {@code compatProbe}
 * pattern), 1Hz-latched, one-shot-per-pass: {@link #beginPass} arms a capture at most once per
 * second; {@link #recordDraw} appends the first {@value #MAX_DRAWS} draws of that pass;
 * {@link #endPass} dumps them as a single log block. Off-thread-safe by construction (both the
 * belt-swap pass and {@code trySetup} run on the render thread) and DISARMS ITSELF on any throw so
 * a diagnostic can never take down a render. Cheap at the default (no property): the lever is a
 * RUNTIME-read {@code static} ({@code Boolean.getBoolean} — NOT a javac compile-time constant, so
 * the guards are real short-circuit branches, not dead-code-eliminated), {@link #recordDraw} takes
 * only primitives (no boxing/allocation), every entry point short-circuits on 3 static-field reads,
 * and no {@code log4j} touches the per-draw hot path.
 */
public final class ClipDiscriminatorProbe {

    private ClipDiscriminatorProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The lever. Byte-inert unless {@code -Dseamlessportals.clipProbe=true}. */
    private static final boolean ENABLED = Boolean.getBoolean("seamlessportals.clipProbe");

    /** Max draws captured per armed pass (the "first N draws"). */
    private static final int MAX_DRAWS = 24;

    /** 1Hz rate limit between armed captures (ns). */
    private static final long RATE_LIMIT_NS = 1_000_000_000L;

    private static boolean disarmed = false;
    private static boolean capturing = false;
    private static long lastCaptureNanos = 0L;
    private static int drawCount = 0;
    private static final StringBuilder buffer = new StringBuilder(1024);
    private static String passDim = "";

    /**
     * Called from {@code SecondaryWorldRenderCore.renderDestWorldFullPipeline} immediately before
     * the 8-arg {@code render()} (after the clip is armed). Arms a capture window at most once per
     * second.
     */
    public static void beginPass(String dim) {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (now - lastCaptureNanos < RATE_LIMIT_NS) {
                return;
            }
            lastCaptureNanos = now;
            capturing = true;
            drawCount = 0;
            passDim = dim;
            buffer.setLength(0);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * Called from the vanilla {@code GlCommandEncoderClipMixin} trySetup handler for every draw.
     * Records the program id, the queried clip-uniform location, and the enable decision for the
     * first {@value #MAX_DRAWS} draws of an armed pass.
     *
     * @param programId    {@code GL_CURRENT_PROGRAM}
     * @param loc          the queried {@code seamlessportals_ClipPlane} location (-1 = absent)
     * @param clipArmed    the store's armed intent ({@code FrontClipping.capture().enabled})
     * @param clipEnabled  the enable decision this draw took (true = glEnable, false = glDisable/none)
     */
    public static void recordDraw(int programId, int loc, boolean clipArmed, boolean clipEnabled) {
        if (!ENABLED || disarmed || !capturing) {
            return;
        }
        try {
            if (drawCount >= MAX_DRAWS) {
                return;
            }
            drawCount++;
            buffer.append("\n  draw #").append(drawCount)
                .append(" program=").append(programId)
                .append(" loc=").append(loc)
                .append(" armed=").append(clipArmed)
                .append(" -> ").append(loc >= 0 ? "TERRAIN(inject)" : "NON-TERRAIN")
                .append(clipEnabled ? " CLIP-ENABLED" : " clip-disabled");
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * Called from the belt-swap method's {@code finally}. Dumps the captured draws as one log block
     * (1Hz-latched by {@link #beginPass}) and closes the window.
     */
    public static void endPass() {
        if (!ENABLED || disarmed || !capturing) {
            return;
        }
        try {
            capturing = false;
            LOGGER.info(
                "[IS3-CLIP-PROBE] full-pipeline pass dim={} — first {} trySetup draws (discriminators "
                    + "1+2: terrain->loc>=0/enabled, entity+sky->loc==-1/disabled):{}",
                passDim, drawCount, buffer.length() == 0 ? " (no draws recorded)" : buffer.toString());
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    private static void disarm(Throwable t) {
        disarmed = true;
        capturing = false;
        try {
            LOGGER.warn("[IS3-CLIP-PROBE] disarmed after a throw (diagnostic only, render unaffected)", t);
        }
        catch (Throwable ignored) {
            // never let the probe's own failure escape
        }
    }
}
