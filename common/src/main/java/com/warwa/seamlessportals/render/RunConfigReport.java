package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * IS5-RC — THE RUN SELF-IDENTIFICATION BLOCK. Always on; emitted exactly once per session.
 *
 * <h2>Why this exists (three void runs paid for it)</h2>
 * A live A/B round is only evidence if the log can prove <b>which leg it is</b>. Three rounds of the
 * IS5 arc were thrown away for want of that proof:
 * <ul>
 *   <li>{@code -PdisablePrevUniformHeal} silently never reached the JVM — the gradle {@code -P} row
 *       existed in one block and not the other, and the log looked perfectly healthy;</li>
 *   <li>a cross-dim leg ran with no cross-dim portal ever in view, measuring nothing;</li>
 *   <li>the {@code -PdisableStampDepthWrite} leg had <b>no way at all</b> to confirm the lever landed,
 *       so its "blur unchanged" result is <b>not a refutation</b> and the whole leg must be re-run.</li>
 * </ul>
 * A lever that does not self-report produces a void run. This block makes that structurally impossible.
 *
 * <h2>What it prints, and why in that order</h2>
 * <ol>
 *   <li><b>[1/3] JVM PROPERTIES ACTUALLY PRESENT</b> — every {@code seamlessportals.*} key really in
 *       {@link System#getProperties()}. This is the <b>ground truth</b> for "did the {@code -P} row
 *       land": it is read from the live JVM, not from any mod-side mirror of what we hoped was passed,
 *       and it catches levers that live outside {@code IPGlobal} entirely.</li>
 *   <li><b>[2/3] DERIVED FEATURE STATE</b> — every {@code IPGlobal} lever constant and every
 *       {@code isXActive()} decision, harvested <b>by reflection</b>. A hand-maintained list would
 *       drift the moment someone adds a lever and forgets this file; reflection cannot. This is the
 *       row that distinguishes "the property was set" from "the feature actually turned off" (they
 *       differ whenever a feature composes on another, e.g. the TAA clear on the temporal guard).</li>
 *   <li><b>[3/3] ENVIRONMENT</b> — iris presence, pack name, GL strings, and the ARB extensions this
 *       project has twice mis-gated on. {@code OpenGL42/43/45} report {@code false} on the 3.3-core
 *       context MC 26.2/Sodium create, while the ARB extensions are present; two probe legs were lost
 *       to that before it was written down. Printing both makes the trap visible in every log.</li>
 * </ol>
 *
 * <h2>Emission points</h2>
 * {@link #noteArmedFrame()} fires it at the first portal dest render — the moment that proves portals
 * AND (shaders-ON runs) the iris path are actually live. {@link #tickFrame()} is the watchdog: if no
 * portal has been rendered after {@link #FALLBACK_FRAMES} frames it emits anyway, so a run that never
 * looked at a portal still carries its own configuration. Whichever fires first wins; the other is a
 * no-op forever after.
 *
 * <p>Never throws: the entire body is wrapped, and a failure degrades to a single WARN. Log-only —
 * this class reads state and writes text, and touches nothing else.
 */
public final class RunConfigReport {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[Seamless Portals] IS5-RC ";

    /** ~10 s at 60 fps. Long enough that a normal run has reached a portal first. */
    private static final int FALLBACK_FRAMES = 600;

    private static volatile boolean emitted = false;
    private static int frames = 0;

    private RunConfigReport() {
    }

    /** Called at the first portal dest render (pre-push, beside the IS5-MB arm). */
    public static void noteArmedFrame() {
        if (emitted) {
            return;
        }
        emit("first portal dest render");
    }

    /**
     * Called from GameRenderer.render TAIL. Watchdog only — a portal-less run still self-IDs.
     *
     * <p>ONLY IN-WORLD FRAMES COUNT, and the counter RESETS whenever there is no level.
     * {@code GameRenderer.render} has a single exit and runs for the title screen and every loading
     * screen too; a process-lifetime counter therefore burns 600 frames in a handful of seconds at the
     * main menu, long before the operator has picked a world, let alone walked to a portal. It would
     * then latch {@code emitted} and the session's one self-ID block would claim no portal was ever
     * rendered — which by this class's own rules is grounds to void a leg that was in fact fine, and
     * would simultaneously suppress the real {@link #noteArmedFrame} emission that carries the
     * accurate trigger. Gating on the level makes the watchdog measure what it claims to measure:
     * in-world frames spent without seeing a portal.
     */
    public static void tickFrame() {
        if (emitted) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            frames = 0;
            return;
        }
        if (++frames >= FALLBACK_FRAMES) {
            emit("watchdog: " + FALLBACK_FRAMES + " consecutive IN-WORLD frames with NO portal dest"
                + " render — if this run was supposed to measure a portal window, IT MEASURED NOTHING");
        }
    }

    private static synchronized void emit(String trigger) {
        if (emitted) {
            return;
        }
        emitted = true;
        try {
            StringBuilder sb = new StringBuilder(2048);
            sb.append('\n').append(P).append("=== RUN CONFIG (once-only, trigger: ").append(trigger)
                .append(") ===");
            appendProperties(sb);
            appendDerivedState(sb);
            appendEnvironment(sb);
            sb.append('\n').append(P).append("=== END RUN CONFIG ===");
            LOGGER.info(sb.toString());
        }
        catch (Throwable t) {
            try {
                LOGGER.warn(P + "failed to emit the run-config block. Treat this run's lever state as"
                    + " UNCONFIRMED — do not adjudicate an A/B leg from it.", t);
            }
            catch (Throwable ignored) {
                // never let self-identification escape
            }
        }
    }

    // =============================================================================================
    // [1/3] the JVM's own view — the only thing that proves a -P row reached the process
    // =============================================================================================

    private static void appendProperties(StringBuilder sb) {
        sb.append('\n').append(P).append("[1/3] JVM PROPERTIES ACTUALLY PRESENT (seamlessportals.*)"
            + " — this is the GROUND TRUTH for \"did the -P row land\":");
        List<String> rows = new ArrayList<>();
        try {
            Properties props = System.getProperties();
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("seamlessportals.")) {
                    rows.add("    " + key + " = " + props.getProperty(key));
                }
            }
        }
        catch (Throwable t) {
            sb.append("\n    <unreadable: ").append(t).append('>');
            return;
        }
        if (rows.isEmpty()) {
            sb.append("\n    (NONE — every lever is at its shipped default. If you passed a -P flag,"
                + " IT DID NOT LAND: check that the row exists in BOTH fabric/build.gradle run"
                + " blocks and that you spelled the property exactly.)");
            return;
        }
        Collections.sort(rows);
        for (String r : rows) {
            sb.append('\n').append(r);
        }
    }

    // =============================================================================================
    // [2/3] what the mod DECIDED — reflection, so it can never drift out of date
    // =============================================================================================

    private static void appendDerivedState(StringBuilder sb) {
        sb.append('\n').append(P).append("[2/3] DERIVED FEATURE STATE (reflected from IPGlobal —"
            + " exhaustive by construction, cannot drift):");
        Class<?> g;
        try {
            g = Class.forName("qouteall.imm_ptl.core.IPGlobal");
        }
        catch (Throwable t) {
            sb.append("\n    <IPGlobal unresolvable: ").append(t).append('>');
            return;
        }

        List<String> levers = new ArrayList<>();
        try {
            for (Field f : g.getDeclaredFields()) {
                int m = f.getModifiers();
                if (!Modifier.isStatic(m) || !Modifier.isPublic(m)) {
                    continue;
                }
                // NO NAME PREDICATE. An earlier draft filtered on _LEVER/_PROBE/debug*/disable* and
                // silently missed IRIS_DEST_PREV_NO_MATRICES (matches none of those) and
                // crossPortalEntityClipMechanism (an enum, excluded by the type filter as well). A
                // lever that the self-ID block does not print is a lever that can void a run without
                // leaving a trace — which is the entire failure mode this class exists to prevent. So
                // print EVERY public static scalar on IPGlobal and let the reader filter. Verbosity
                // once per session is not a cost worth trading a void run for.
                Class<?> t = f.getType();
                if (!t.isPrimitive() && t != String.class && !t.isEnum()) {
                    continue;
                }
                f.setAccessible(true);
                levers.add("    " + f.getName() + " = " + f.get(null));
            }
        }
        catch (Throwable t) {
            sb.append("\n    <field sweep failed: ").append(t).append('>');
        }
        Collections.sort(levers);
        sb.append("\n  -- lever constants --");
        if (levers.isEmpty()) {
            sb.append("\n    (none found)");
        }
        for (String r : levers) {
            sb.append('\n').append(r);
        }

        // The isXActive() decisions. THIS is the row that matters for adjudication: a property can be
        // set and the feature still be off (composition), or unset and the feature still be on
        // (shipped default). Only these say what the render path actually did.
        List<String> decisions = new ArrayList<>();
        try {
            for (Method me : g.getDeclaredMethods()) {
                int m = me.getModifiers();
                if (!Modifier.isStatic(m) || !Modifier.isPublic(m)) {
                    continue;
                }
                if (me.getReturnType() != boolean.class) {
                    continue;
                }
                String n = me.getName();
                if (!n.startsWith("is")) {
                    continue;
                }
                me.setAccessible(true);
                if (me.getParameterCount() == 0) {
                    decisions.add("    " + n + "() = " + (Boolean.TRUE.equals(me.invoke(null))
                        ? "ACTIVE" : "INACTIVE"));
                    continue;
                }
                // A 0-arg-only sweep dropped isShaderpackPortalViewsActive(boolean) — the ONLY
                // renderer-ROUTING predicate — while happily printing isShaderpackPortalViewsArmed(),
                // which IPGlobal's own javadoc says must never be read as the routing decision because
                // it omits the shaders-active gate. The result was a "what the render path ACTUALLY
                // did" section with no row for which renderer ran, and a row that reads ACTIVE always.
                // These are pure predicates, so evaluating both branches is safe and drift-proof.
                if (me.getParameterCount() == 1 && me.getParameterTypes()[0] == boolean.class) {
                    decisions.add("    " + n + "(false) = "
                        + (Boolean.TRUE.equals(me.invoke(null, false)) ? "ACTIVE" : "INACTIVE")
                        + " ; " + n + "(true) = "
                        + (Boolean.TRUE.equals(me.invoke(null, true)) ? "ACTIVE" : "INACTIVE"));
                }
            }
        }
        catch (Throwable t) {
            sb.append("\n    <decision sweep failed: ").append(t).append('>');
        }
        Collections.sort(decisions);
        sb.append("\n  -- feature decisions (what the render path ACTUALLY did) --");
        if (decisions.isEmpty()) {
            sb.append("\n    (none found)");
        }
        for (String r : decisions) {
            sb.append('\n').append(r);
        }
    }

    // =============================================================================================
    // [3/3] the environment, including the two GL traps this project has already paid for
    // =============================================================================================

    private static void appendEnvironment(StringBuilder sb) {
        sb.append('\n').append(P).append("[3/3] ENVIRONMENT:");
        sb.append("\n    iris present = ").append(classPresent("net.irisshaders.iris.Iris"));
        sb.append("\n    sodium present = ")
            .append(classPresent("net.caffeinemc.mods.sodium.client.SodiumClientMod"));
        sb.append("\n    iris version = ").append(irisString("getVersion"));
        sb.append("\n    iris current pack = ").append(irisString("getCurrentPackName"));
        try {
            sb.append("\n    iris shaders enabled = ")
                .append(qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface.invoker
                    .isShaders());
        }
        catch (Throwable t) {
            sb.append("\n    iris shaders enabled = <unreadable: ").append(t).append('>');
        }
        appendGl(sb);
    }

    /**
     * GL strings + the extension/core-version split. The core-version booleans are printed
     * DELIBERATELY beside the ARB extensions: on this context they disagree, and gating a feature on
     * the core flag rather than the extension has already cost this project two probe legs.
     */
    private static void appendGl(StringBuilder sb) {
        try {
            org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
            sb.append("\n    GL_VENDOR   = ").append(glString(org.lwjgl.opengl.GL11.GL_VENDOR));
            sb.append("\n    GL_RENDERER = ").append(glString(org.lwjgl.opengl.GL11.GL_RENDERER));
            sb.append("\n    GL_VERSION  = ").append(glString(org.lwjgl.opengl.GL11.GL_VERSION));
            sb.append("\n    core-version flags (DO NOT GATE ON THESE): OpenGL42=").append(caps.OpenGL42)
                .append(" OpenGL43=").append(caps.OpenGL43)
                .append(" OpenGL45=").append(caps.OpenGL45);
            sb.append("\n    ARB extensions (GATE ON THESE): GL_ARB_direct_state_access=")
                .append(caps.GL_ARB_direct_state_access)
                .append(" GL_ARB_get_texture_sub_image=").append(caps.GL_ARB_get_texture_sub_image)
                .append(" GL_ARB_compute_shader=").append(caps.GL_ARB_compute_shader)
                .append(" GL_ARB_shader_image_load_store=")
                .append(caps.GL_ARB_shader_image_load_store);
        }
        catch (Throwable t) {
            sb.append("\n    <GL capabilities unreadable (not on the render thread?): ")
                .append(t).append('>');
        }
    }

    private static String glString(int name) {
        try {
            String s = org.lwjgl.opengl.GL11.glGetString(name);
            return s == null ? "<null>" : s;
        }
        catch (Throwable t) {
            return "<unreadable>";
        }
    }

    private static String classPresent(String fqn) {
        try {
            Class.forName(fqn, false, RunConfigReport.class.getClassLoader());
            return "yes";
        }
        catch (Throwable t) {
            return "no";
        }
    }

    private static String irisString(String method) {
        try {
            Class<?> c = Class.forName("net.irisshaders.iris.Iris");
            Method m = c.getMethod(method);
            Object v = m.invoke(null);
            return String.valueOf(v);
        }
        catch (Throwable t) {
            return "<n/a>";
        }
    }
}
