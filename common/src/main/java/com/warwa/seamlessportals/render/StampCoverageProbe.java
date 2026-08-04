package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * IS5-COV — THE STAMP COVERAGE PROBE (2026-08-03).
 *
 * <h2>The question it exists to answer, and nothing else</h2>
 * A ~1 px ring around every occluder standing between the camera and a portal window is never
 * covered by the stamp, so the SOURCE world's geometry shows through where destination content
 * should be. USER-CONFIRMED with {@code -PdebugStampSolid -PdebugTintStamp}: <i>"ring of ordinary
 * terrain around the block, magenta elsewhere"</i>. The ring sits well inside the aperture mesh, so
 * the only thing that can have excluded it is the stamp's depth test.
 *
 * <p><b>The one open question is therefore: what depth value does the buffer actually hold in that
 * ring?</b>
 * <ul>
 *   <li>the OCCLUDER's depth ⇒ the occluder's depth footprint is genuinely DILATED relative to its
 *       colour footprint, and the hunt moves to whatever produced that depth;</li>
 *   <li>the BACKGROUND's depth, yet still unstamped ⇒ the depth test is NOT the gate at all, and
 *       every conclusion resting on it has to be re-derived.</li>
 * </ul>
 * Those two answers point in completely different directions and no amount of source reading
 * separates them. Four mechanisms were proposed for this artifact before it was measured; three
 * were refuted by live legs and the fourth had the SIGN backwards. This probe exists because the
 * user's instruction was, correctly, to stop guessing.
 *
 * <h2>What input makes it print the GUILTY answer — and the INNOCENT one</h2>
 * Required of every probe in this tree, and this one can print both:
 * <ul>
 *   <li>GUILTY (dilated depth): an UNSTAMPED run, 1-3 px wide, sitting between a wide unstamped run
 *       (the occluder) and a stamped run (the window), whose depth matches the OCCLUDER run's.</li>
 *   <li>GUILTY (not the depth test): the same narrow unstamped run, but with the BACKGROUND's
 *       depth.</li>
 *   <li>INNOCENT: no narrow run at all — the map goes straight from the occluder's unstamped run to
 *       the stamped run with no boundary pixels — meaning the artifact is not on this scanline and
 *       the aim is wrong. That is a real possible output and it is why the aim is reported.</li>
 * </ul>
 *
 * <h2>Why it REFUSES to run without the solid-stamp levers</h2>
 * The classifier is "is this pixel the stamp's flat magenta". That is exact ONLY when
 * {@code debugStampSolid} AND {@code debugTintStamp} are both set, which makes every stamped
 * fragment write vertex colour {@code (1,0,1)} directly and ignore the sample. Without them the
 * stamp writes destination CONTENT, which can be any colour including magenta-ish, and the
 * classification silently becomes a guess wearing a measurement's clothes. So the probe hard-refuses
 * with a once-only WARN rather than emitting a number it cannot justify. An instrument that can only
 * ever print "clean" is worse than none; so is one that prints a confident number from an invalid
 * classifier.
 *
 * <h2>Discipline</h2>
 * DEFAULT OFF ({@code -Dseamlessportals.stampCoverageProbe}); ≤1 Hz (the render-thread log4j-stall
 * rule); log-only — it reads pixels and writes text and touches nothing else; every entry wrapped,
 * a throw DISARMS for the session rather than propagating into the render path; all pixel-store and
 * framebuffer binding state saved and restored around the readback, matching
 * {@link SeamHandStageDiff}'s established idiom.
 *
 * <p>Sampled AFTER the stamp's render pass closes. That matters: where the stamp PASSED it has also
 * written depth, but where it FAILED — the ring, the only place this probe is asking about — the
 * depth is untouched and is exactly the value the test rejected on.
 */
public final class StampCoverageProbe {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] [IS5-COV] ";

    /** Bound so one line can never become a wall. Runs beyond this are summarised as a count. */
    private static final int MAX_RUNS = 24;

    private static boolean disarmed = false;
    private static boolean refusedWarned = false;
    private static boolean armReported = false;
    private static long lastNanos = 0L;

    private StampCoverageProbe() {
    }

    /**
     * Called from {@code IrisCompatPaste.stampPortalArea} immediately after the stamp's RenderPass
     * closes, with the buffer the stamp drew into.
     *
     * <p>Cheapest-first: a folded static-final false test is the entire cost when the lever is off.
     */
    public static void afterStamp(RenderTarget deferred) {
        if (!IPGlobal.STAMP_COVERAGE_PROBE || disarmed) {
            return;
        }
        try {
            // ---- INSTRUMENT VALIDITY GATE — refuse rather than mislead -------------------------
            if (!IPGlobal.debugStampSolid || !IPGlobal.debugTintStamp) {
                if (!refusedWarned) {
                    refusedWarned = true;
                    LOGGER.warn(P + "REFUSING TO MEASURE. This probe classifies a pixel as"
                        + " stamped-or-not by testing for the stamp's flat magenta, which is exact"
                        + " ONLY with -PdebugStampSolid=true AND -PdebugTintStamp=true. Without"
                        + " both, the stamp writes destination CONTENT and the classifier becomes a"
                        + " guess. Re-run with:  -PstampCoverageProbe=true -PdebugStampSolid=true"
                        + " -PdebugTintStamp=true   (debugStampSolid={} debugTintStamp={})",
                        IPGlobal.debugStampSolid, IPGlobal.debugTintStamp);
                }
                return;
            }
            long now = System.nanoTime();
            if (now - lastNanos < 1_000_000_000L) {
                return;
            }
            lastNanos = now;
            scan(deferred);
        }
        catch (Throwable t) {
            disarmed = true;
            try {
                LOGGER.warn(P + "probe threw — DISARMED for this session (render unaffected)", t);
            }
            catch (Throwable ignored) {
                // never let instrumentation escape into the render path
            }
        }
    }

    private static void scan(RenderTarget rt) {
        if (rt == null
            || !(RenderSystem.getDevice().backend instanceof GlDevice glDevice)
            || !(rt.getColorTextureView() instanceof GlTextureView colorView)
            || !(rt.getDepthTextureView() instanceof GlTextureView depthView)
        ) {
            disarmed = true;
            LOGGER.info(P + "non-GL backend or missing texture views — DISARMED");
            return;
        }
        final int w = rt.width;
        final int h = rt.height;
        if (w < 8 || h < 8) {
            return;
        }
        // The scanline: the full width of the buffer at its vertical centre. Deliberately NOT
        // aimed at anything computed. A fixed-column probe in the seam-hand arc sat just outside
        // the footprint it was built to measure and printed "all clean" for two adjudications; a
        // full-width row cannot miss horizontally, and the operator aims it by standing so the
        // occluder is at eye level. The row is reported so a null result is attributable to aim.
        final int y = h / 2;

        int fbo = glDevice.frameBufferCache().getFbo(
            glDevice.directStateAccess(), List.of(colorView), depthView
        );
        int prevRead = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
        int prevRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int prevSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int prevSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int prevAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);

        ByteBuffer colors = BufferUtils.createByteBuffer(w * 4);
        FloatBuffer depths = BufferUtils.createFloatBuffer(w);
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, y, w, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, colors);
            GL11.glReadPixels(0, y, w, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depths);
        }
        finally {
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, prevAlignment);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, prevSkipPixels);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, prevSkipRows);
            GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, prevRowLength);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        }

        if (!armReported) {
            armReported = true;
            LOGGER.info(P + "ARMED. Scanning row y={} of the {}x{} deferred buffer, full width,"
                + " 1 Hz, AFTER the stamp pass. Stand so the occluder crosses the middle of the"
                + " screen. Read the RUNS line: a NARROW UNSTAMPED run wedged between the wide"
                + " unstamped occluder run and a stamped run IS the artifact; its depth says which"
                + " mechanism is live.", y, w, h);
        }

        // ---- run-length encode by stamped-ness, carrying each run's depth range -----------------
        StringBuilder runs = new StringBuilder(512);
        int runStart = 0;
        boolean runStamped = isStampMagenta(colors, 0);
        float runMin = depths.get(0);
        float runMax = runMin;
        int emitted = 0;
        int suppressed = 0;

        for (int x = 1; x <= w; x++) {
            boolean stamped = x < w && isStampMagenta(colors, x);
            if (x < w && stamped == runStamped) {
                float d = depths.get(x);
                if (d < runMin) runMin = d;
                if (d > runMax) runMax = d;
                continue;
            }
            int len = x - runStart;
            if (emitted < MAX_RUNS) {
                emitted++;
                runs.append(" [").append(runStart).append("..").append(x - 1)
                    .append(' ').append(runStamped ? "STAMPED" : "UNSTAMPED")
                    .append(" w=").append(len)
                    .append(" d=").append(fmt(runMin));
                if (runMax != runMin) {
                    runs.append("..").append(fmt(runMax));
                }
                runs.append(']');
            }
            else {
                suppressed++;
            }
            if (x < w) {
                runStart = x;
                runStamped = stamped;
                runMin = depths.get(x);
                runMax = runMin;
            }
        }
        if (suppressed > 0) {
            // NEVER silently truncate — a dropped run is a dropped measurement.
            runs.append(" [+").append(suppressed).append(" MORE RUNS SUPPRESSED — the scanline is")
                .append(" busier than the cap; re-aim at a simpler view]");
        }

        LOGGER.info(P + "row={} w={} runs={}:{}", y, w, emitted + suppressed, runs);
    }

    /**
     * The stamp's solid+tint fragment writes vertex colour {@code (1,0,1)} directly. The tolerance
     * is wide on purpose: the exact byte values depend on the deferred buffer's format and on the
     * readback conversion, and the only thing that must never happen is a NEARBY colour being
     * mistaken for the stamp. Ordinary terrain does not land near full-red-full-blue-no-green.
     */
    private static boolean isStampMagenta(ByteBuffer c, int x) {
        int r = c.get(x * 4) & 0xFF;
        int g = c.get(x * 4 + 1) & 0xFF;
        int b = c.get(x * 4 + 2) & 0xFF;
        return r > 180 && b > 180 && g < 70;
    }

    private static String fmt(float d) {
        return String.format("%.6f", d);
    }
}
