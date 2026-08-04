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
    /** Coverage boundaries whose raw neighbourhood is dumped (see {@code dumpEdges}). */
    private static final int MAX_EDGES = 4;
    /** Pixels dumped either side of each boundary. */
    private static final int EDGE_SPAN = 6;

    private static boolean disarmed = false;
    private static boolean refusedWarned = false;
    private static boolean armReported = false;
    private static long lastNanos = 0L;

    /** Content-keyed, so a stable geometry announces once and a CHANGE always announces. */
    private static String announcedSnapshotGeometry = null;

    private StampCoverageProbe() {
    }

    /**
     * IS5-COV GEOMETRY WITNESS — called at the snapshot, immediately before
     * {@code deferred.fb.copyDepthFrom(mainRT)}.
     *
     * <p><b>Why this is ALWAYS ON and not behind the probe lever.</b> The stamp's depth test is
     * only meaningful if the depth it tests was copied 1:1 from the buffer that rasterized it. A
     * copy between render targets of DIFFERENT dimensions cannot be a 1:1 texel move — it has to
     * scale — and scaling a depth buffer dilates every silhouette by roughly a pixel. That is
     * precisely the measured defect: MEASURED 2026-08-03, an occluder's depth footprint is 1-2 px
     * wider than its colour footprint (raw per-pixel dump: colour {@code c0b0ad} carrying the
     * block's bit-identical depth {@code 0.975007} one pixel before the bark at {@code 574439}).
     * A silent size mismatch would explain it, would be invariant to every shaderpack filtering
     * option (all four were separately refuted by live legs), would be present at rest, and would
     * look worse under motion.
     *
     * <p>A mismatch is a DEFECT, not a diagnostic curiosity, so it must never be able to happen
     * without a line in the log. Content-keyed like the C3-BLOOM plan announcement, whose
     * class-lifetime latch predecessor cost this project a full false-refutation cycle: a stable
     * geometry prints once, and any change always re-announces. Log-only; never throws.
     */
    public static void noteSnapshotGeometry(RenderTarget mainRT, RenderTarget deferred) {
        try {
            if (mainRT == null || deferred == null) {
                return;
            }
            boolean match = mainRT.width == deferred.width && mainRT.height == deferred.height;
            String key = "main=" + mainRT.width + "x" + mainRT.height
                + " deferred=" + deferred.width + "x" + deferred.height
                + " match=" + match;
            if (key.equals(announcedSnapshotGeometry)) {
                return;
            }
            announcedSnapshotGeometry = key;
            if (match) {
                LOGGER.info(P + "snapshot geometry {} — copyDepthFrom is a 1:1 texel move.", key);
            }
            else {
                LOGGER.warn(P + "snapshot geometry {} — MISMATCH. copyDepthFrom cannot be a 1:1"
                    + " texel move at these sizes; it must scale, and scaling a depth buffer"
                    + " DILATES every silhouette. This is the leading candidate for the occluder"
                    + " ring (source terrain visible in a 1-2 px band around anything standing"
                    + " between the camera and a portal window).", key);
            }
        }
        catch (Throwable t) {
            // a witness must never be able to break the thing it is witnessing
        }
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
        dumpEdges(colors, depths, w);
    }

    /**
     * THE AMENDMENT THAT MAKES THIS PROBE ABLE TO PRINT THE GUILTY ANSWER (2026-08-03, same day,
     * after its first run came back inconclusive BY CONSTRUCTION).
     *
     * <p>The run encoding above splits on stamped-ness alone. The ring and the occluder are BOTH
     * unstamped, so they merge into one run and a 1-2 px ring against a 147 px block is invisible —
     * and if the ring carries the occluder's own depth, which is exactly what "dilated depth
     * footprint" means, the run's depth range does not separate them either. The first version of
     * this probe could therefore only ever print "no ring" in the very case it was built to detect:
     * the banned shape its own javadoc legislates against.
     *
     * <p>So: no classifier. Dump the RAW pixels either side of each coverage boundary — colour AND
     * depth, per pixel, unaggregated — and let the reader see where the occluder's COLOUR actually
     * stops versus where the stamp's coverage stops. If the last 1-2 pixels before a stamped run
     * are already source-terrain colour while still carrying occluder depth, that IS the dilation,
     * stated in raw data rather than inferred from a summary.
     *
     * <p>Aim aid: use a vividly-coloured occluder (redstone / gold / lapis block). Against ordinary
     * grey terrain the colour transition is then unmistakable without any thresholding.
     *
     * <p>Bounded: at most {@link #MAX_EDGES} boundaries, {@link #EDGE_SPAN} pixels either side.
     */
    private static void dumpEdges(ByteBuffer colors, FloatBuffer depths, int w) {
        int edges = 0;
        for (int x = 1; x < w && edges < MAX_EDGES; x++) {
            if (isStampMagenta(colors, x) == isStampMagenta(colors, x - 1)) {
                continue;
            }
            edges++;
            int from = Math.max(0, x - EDGE_SPAN);
            int to = Math.min(w - 1, x + EDGE_SPAN - 1);
            StringBuilder sb = new StringBuilder(320);
            for (int i = from; i <= to; i++) {
                if (i == x) {
                    sb.append(" ||");
                }
                sb.append(' ').append(i).append(':')
                    .append(isStampMagenta(colors, i) ? "M" : "-")
                    .append(hex(colors, i)).append('/').append(fmt(depths.get(i)));
            }
            LOGGER.info(P + "edge@{} (|| marks the first pixel of the new run; M=stamped):{}",
                x, sb);
        }
    }

    private static String hex(ByteBuffer c, int x) {
        int r = c.get(x * 4) & 0xFF;
        int g = c.get(x * 4 + 1) & 0xFF;
        int b = c.get(x * 4 + 2) & 0xFF;
        return String.format("%02x%02x%02x", r, g, b);
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
