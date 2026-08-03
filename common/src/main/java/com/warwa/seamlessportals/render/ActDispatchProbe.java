package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * IS5-ACT ROUND-2 — the DISPATCH WITNESS. Log-only, DEFAULT OFF
 * ({@code -Dseamlessportals.actDispatchProbe}; requires {@code -Dseamlessportals.actProbe} to emit).
 *
 * <p><b>The question.</b> Live run 2 established, with a positive control, that a portal destination's
 * ACT volume is VOXELISED BUT NEVER FLOOD-FILLED — the same nether volume reads {@code ff nz=2522..9905}
 * as the main view and {@code ff nz=0/32768} in 0-of-~80 dest captures. Reflection cannot say WHY:
 * it reads the compute roster but not whether {@code dispatch()} executes, nor what uniforms the
 * invocation sees. Hence the two log-only iris mixins that feed this class.
 *
 * <p><b>Pre-registered hypotheses</b> (decided by the table in the spec, not re-derived after reading
 * the log): <b>R-1a</b> {@code renderAll} never reached · <b>R-1b</b> reached, no dispatch ·
 * <b>R-2</b> degenerate work groups · <b>R-3</b> the reprojection discards everything ·
 * <b>R-4</b> the writes land in the wrong images.
 *
 * <p><b>R-3 is the hypothesis this kit most expects to REFUTE.</b> Two independent reasons: the dest
 * pipeline owns its own {@code CameraPositionTracker} (pipeline identity is live-confirmed DIFFERENT),
 * which only ever sees dest cameras ⇒ {@code posOffset ≈ 0}; and {@code GetLightCalculated}
 * ({@code shadowcomp.glsl:55-60}) CLAMPS all six neighbour fetches, so a broken {@code previousPos}
 * reads the volume EDGE rather than garbage. Decisive either way: light-source voxels write
 * {@code light = max(light, pow2(color))} with ZERO dependence on {@code previousPos}
 * ({@code shadowcomp.glsl:140-144}) followed by an UNCONDITIONAL {@code imageStore} — so a zero at a
 * front-facing emitter texel cannot be caused by reprojection at all.
 *
 * <p><b>The uniform read is the REAL uniform, not a proxy.</b> {@code glGetUniformfv(programId, loc)}
 * reads the program object's own uniform storage — the exact bits the invocation will read. The only
 * writer is {@code ProgramUniforms.update()} via {@code ComputeProgram.use()}, 37 bytecodes before
 * {@code dispatch} (off.152 → off.189), so at dispatch HEAD the value IS the dispatch's input —
 * modulo the PER_FRAME skip, which is MEASURED here ({@code lastFrame} vs {@code COUNTER}), not assumed.
 *
 * <p><b>Hot-path discipline.</b> A dest dispatch can fire up to 27x per portal per frame. The fast path
 * is one {@code static final} branch, two {@code int++}, one static-field read and a &le;16-entry int
 * scan — no allocation, no logging, no GL. The single heavy capture happens once per armed portal
 * window, and emission is folded into {@link ActSeedProbe}'s existing ONE {@code LOGGER.info} per
 * second (the ~130 ms log4j render-thread stall rule).
 *
 * <p><b>Never</b> calls {@code ComputeProgram.getWorkGroups(float,float)} (it MUTATES the work-group
 * cache and returns null under an indirect pointer) and <b>never</b> calls {@code glGetError} (it would
 * drain the queue {@code CHelper.checkGlError()} depends on). Every public entry is total.
 */
public final class ActDispatchProbe {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[IS5-DISP] ";

    private static final boolean ENABLED = Boolean.getBoolean("seamlessportals.actDispatchProbe");

    private ActDispatchProbe() {
    }

    // =============================================================================================
    // Latches
    // =============================================================================================

    private static boolean broken = false;
    private static boolean reflectionReady = false;
    private static boolean reflectionAttempted = false;
    private static final Set<String> warnedOnce = new HashSet<>();
    private static boolean liveDispatchLogged = false;
    private static boolean liveRenderAllLogged = false;

    // =============================================================================================
    // Counters (session + per-window). Plain ints on the hot path.
    // =============================================================================================

    private static long dispatchTotal = 0L;
    private static long dispatchMain = 0L;
    private static long dispatchDest = 0L;
    private static long dispatchDestAct = 0L;
    private static long renderAllTotal = 0L;
    private static long renderAllInWindow = 0L;
    private static long armedFrames = 0L;

    private static int windowRenderAll = 0;
    private static int windowAll = 0;
    private static int windowAct = 0;
    private static int windowNonAct = 0;
    private static boolean inWindow = false;

    private static boolean captureArmed = false;
    private static boolean destCaptured = false;
    private static long lastMainCaptureNanos = 0L;
    private static long mainCaptureAgeMs = -1L;

    private static String destBlock = null;
    private static String mainBlock = null;

    /** ACT-identity cache: programId -> 1 (ACT) / 0 (not) . Fixed size, no allocation on the hot path. */
    private static final int[] tagIds = new int[16];
    private static final byte[] tagVals = new byte[16];
    private static int tagCount = 0;

    private static int firstDestProgId = -1;
    private static int distinctDestProgIds = 0;
    private static long crossIdTotal = 0L;
    private static long sameIdTotal = 0L;

    // =============================================================================================
    // Reflective handles
    // =============================================================================================

    private static Method mGetProgramId;
    private static Method mGetActiveImages;
    private static Field fAbsoluteWorkGroups;
    private static Field fRelativeWorkGroups;
    private static Field fLocalSize;
    private static Field fIndirectPointer;
    private static Field fUniforms;          // ComputeProgram.uniforms
    private static Field fLastFrame;         // ProgramUniforms.lastFrame (package-private)
    private static Field fShadowActive;      // ShadowRenderer.ACTIVE (public static)
    private static Field fCounter;           // SystemTimeUniforms.COUNTER
    private static Method mCounterGetAsInt;

    // =============================================================================================
    // Hot path
    // =============================================================================================

    /** M1 — {@code ComputeProgram.dispatch(FF)} HEAD. Up to 27x per portal per frame: stay cheap. */
    public static void onDispatch(Object prog, float w, float h) {
        if (!ENABLED || broken) {
            return;
        }
        try {
            dispatchTotal++;
            if (!PortalRendering.isRendering()) {
                dispatchMain++;
                long now = System.nanoTime();
                if (now - lastMainCaptureNanos >= 1_000_000_000L) {
                    lastMainCaptureNanos = now;
                    mainBlock = capture("MAIN", prog, w, h);
                    mainCaptureAgeMs = 0L;
                }
                return;
            }
            dispatchDest++;
            windowAll++;
            if (actTag(prog) != 1) {
                windowNonAct++;
                return;
            }
            dispatchDestAct++;
            windowAct++;
            int pid = programId(prog);
            if (firstDestProgId == -1) {
                firstDestProgId = pid;
                distinctDestProgIds = 1;
            }
            else if (pid != firstDestProgId) {
                distinctDestProgIds++;
                warnOnce("dmultiprog", P + "more than one DEST ACT compute dispatched in a single"
                    + " portal window (ids " + firstDestProgId + "," + pid + ") — the [5] row"
                    + " describes only the FIRST. Report the full log.", null);
            }
            if (!captureArmed || destCaptured) {
                return;
            }
            destCaptured = true;
            destBlock = capture("DEST", prog, w, h);
            if (!liveDispatchLogged) {
                liveDispatchLogged = true;
                LOGGER.info(P + "LIVE (once-only): ACT compute dispatch reached a DEST portal window"
                    + " — prog={} — the round-2 kit is at the seam.", pid);
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** M2 — {@code ShadowCompositeRenderer.renderAll()} HEAD. Off the IS5-FF no-op path by design. */
    public static void onShadowCompositeRenderAll() {
        if (!ENABLED || broken) {
            return;
        }
        try {
            renderAllTotal++;
            if (PortalRendering.isRendering()) {
                renderAllInWindow++;
                windowRenderAll++;
                if (!liveRenderAllLogged) {
                    liveRenderAllLogged = true;
                    LOGGER.info(P + "LIVE (once-only): ShadowCompositeRenderer.renderAll entered"
                        + " inside a DEST portal window.");
                }
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    // =============================================================================================
    // Bracket hooks, driven by ActSeedProbe
    // =============================================================================================

    /**
     * Called from {@code ActSeedProbe.endPortal} so the cross-dim watchdog can fire. SAME-dim windows
     * can NEVER produce a dest ACT dispatch — IS5-FF's NoopShadowCompositeRenderer suppresses them by
     * design — so a session that only ever views a same-dim portal yields zero measurement and looks
     * deceptively like a healthy run. That happened once; hence the loud watchdog below.
     */
    public static void noteWindowClass(boolean crossId) {
        if (!ENABLED || broken) {
            return;
        }
        if (crossId) {
            crossIdTotal++;
        }
        else {
            sameIdTotal++;
        }
    }

    public static void onFrameArmed(boolean armed) {
        if (!ENABLED || broken) {
            return;
        }
        captureArmed = armed;
        if (armed) {
            armedFrames++;
            if (armedFrames == 1) {
                // RUN SELF-IDENTIFICATION. Two runs of this kit differ only by a JVM flag, and a log
                // that does not say which one it is cannot be adjudicated later. Emit it once, loudly.
                LOGGER.info(P + "RUN CONFIG: prevUniformHeal={} (IS5-PH) · actProbe=on ·"
                        + " actDispatchProbe=on. For the Step-0 A/B this line is what distinguishes"
                        + " run A (heal DISABLED) from run B (heal ACTIVE).",
                    qouteall.imm_ptl.core.IPGlobal.isPrevUniformHealActive() ? "ACTIVE" : "DISABLED");
            }
            if (mainCaptureAgeMs >= 0) {
                mainCaptureAgeMs = (System.nanoTime() - lastMainCaptureNanos) / 1_000_000L;
            }
        }
        watchdogs();
    }

    public static void beginWindow() {
        if (!ENABLED || broken) {
            return;
        }
        inWindow = true;
        windowRenderAll = 0;
        windowAll = 0;
        windowAct = 0;
        windowNonAct = 0;
        destCaptured = false;
        destBlock = null;
        firstDestProgId = -1;
        distinctDestProgIds = 0;
    }

    public static void endWindow() {
        if (!ENABLED || broken) {
            return;
        }
        try {
            inWindow = false;
            if (windowAct == 0 && dispatchMain > 0) {
                warnOnce("dzero", P + "FINDING (once-only): a DEST portal window closed with ZERO ACT"
                    + " compute dispatches (renderAll=" + windowRenderAll + " all=" + windowAll
                    + " nonAct=" + windowNonAct + ") while MAIN dispatched " + dispatchMain
                    + ". R-1 CONFIRMED — the dest flood-fill compute never ran. Report this line and"
                    + " the [5] block verbatim.", null);
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** Appended to the {@code [3]} PORTAL row + the {@code [5]} DEST section. */
    public static void appendDestSection(StringBuilder block) {
        if (!ENABLED || broken || block == null) {
            return;
        }
        try {
            block.append("      dispatch: window renderAll=").append(windowRenderAll)
                .append(" all=").append(windowAll)
                .append(" act=").append(windowAct)
                .append(" nonAct=").append(windowNonAct)
                .append("   session: destAct=").append(dispatchDestAct)
                .append(" main=").append(dispatchMain)
                .append(" renderAll=").append(renderAllTotal)
                .append(" renderAllInWindow=").append(renderAllInWindow).append('\n');
            if (destBlock != null) {
                block.append(destBlock);
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** Appended once per armed second: the MAIN positive control. */
    public static void appendMainSection(StringBuilder block) {
        if (!ENABLED || broken || block == null) {
            return;
        }
        try {
            if (mainBlock != null) {
                block.append(mainBlock);
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    // =============================================================================================
    // The heavy capture — pure GL queries + read-only reflection
    // =============================================================================================

    private static String capture(String role, Object prog, float w, float h) {
        StringBuilder b = new StringBuilder(1024);
        try {
            if (!ensureReflection()) {
                return "";
            }
            int pid = programId(prog);
            int bound = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            b.append("  [5] ACT DISPATCH  role=").append(role);
            if ("DEST".equals(role)) {
                b.append("  portal@").append(PortalRendering.isRendering()
                        ? Integer.toHexString(System.identityHashCode(PortalRendering.getRenderingPortal()))
                        : "n/a")
                    .append(" layer=").append(PortalRendering.isRendering()
                        ? PortalRendering.getPortalLayer() : -1);
            }
            else {
                b.append("  ageMs=").append(mainCaptureAgeMs);
            }
            b.append('\n');
            b.append("      prog=").append(pid).append(" boundProgram=").append(bound)
                .append(" MATCH=").append(pid == bound ? "YES" : "NO")
                .append("  shadowActive=").append(readStaticBool(fShadowActive))
                .append("  activeImages=").append(invokeInt(mGetActiveImages, prog))
                .append("  args=(w=").append(w).append(",h=").append(h).append(")\n");
            if (pid != bound) {
                warnOnce("dprogmismatch", P + "skip (once-only): at dispatch HEAD the bound program ("
                    + bound + ") is not the dispatching program (" + pid + ") — use() did not bind, or"
                    + " the hook slot moved. Uniform reads target the program OBJECT so they stay"
                    + " valid; the images(GL) block is UNRELIABLE. Report this line.", null);
            }

            // --- freshness: was the PER_FRAME uniform stage uploaded for this program this frame? ---
            int lastFrame = -1;
            int counter = -1;
            try {
                Object uni = fUniforms.get(prog);
                if (uni != null && fLastFrame != null) {
                    lastFrame = fLastFrame.getInt(uni);
                }
                Object c = fCounter.get(null);
                if (c != null && mCounterGetAsInt != null) {
                    counter = (Integer) mCounterGetAsInt.invoke(c);
                }
            }
            catch (Throwable ignored) {
                // reported as n/a below
            }
            String fresh = lastFrame < 0 || counter < 0 ? "n/a"
                : (lastFrame == 0 ? "FIRST-USE" : (lastFrame == counter ? "FRESH" : "STALE"));
            b.append("      freshness: lastFrame=").append(lastFrame).append(" COUNTER=").append(counter)
                .append(" => ").append(fresh).append('\n');
            if ("DEST".equals(role) && "STALE".equals(fresh)) {
                warnOnce("dstale", P + "DEST compute uniforms are STALE (lastFrame=" + lastFrame
                    + " COUNTER=" + counter + ") — ProgramUniforms.update() skipped the PER_FRAME stage"
                    + " for this program this frame (R-3b). The [5] uniform values are from an EARLIER"
                    + " upload; the R-3 verdict for this row is WITHHELD.", null);
            }
            else if ("FIRST-USE".equals(fresh)) {
                infoOnce("dfirstuse", P + "lastFrame=0 FIRST-USE — ProgramUniforms.update()'s"
                    + " once-branch returns without stamping lastFrame. Freshness undecidable for"
                    + " this row only.");
            }

            // --- work groups, reproduced READ-ONLY (never call getWorkGroups: it mutates) ---
            Object abs = fAbsoluteWorkGroups == null ? null : fAbsoluteWorkGroups.get(prog);
            Object rel = fRelativeWorkGroups == null ? null : fRelativeWorkGroups.get(prog);
            Object ind = fIndirectPointer == null ? null : fIndirectPointer.get(prog);
            int[] local = fLocalSize == null ? null : (int[]) fLocalSize.get(prog);
            b.append("      workGroups: absolute=").append(abs).append(" relative=").append(rel)
                .append(" localSize=").append(local == null ? "null"
                    : ("[" + local[0] + "," + local[1] + "," + local[2] + "]"))
                .append(" indirect=").append(ind).append('\n');
            String size;
            if (ind != null) {
                size = "INDIRECT";
                warnOnce("dindirect", P + "the dest shadowcomp uses INDIRECT dispatch — the work-group"
                    + " count lives in a GPU buffer and R-2 CANNOT be decided from CPU-side fields."
                    + " R-2 stays OPEN.", null);
            }
            else if (abs != null) {
                size = String.valueOf(abs);
            }
            else if (rel != null && local != null) {
                size = "derived-from-relative " + rel;
            }
            else if (local != null) {
                size = "(" + (int) Math.ceil(w / local[0]) + "," + (int) Math.ceil(h / local[1]) + ",1)";
            }
            else {
                size = "DEGENERATE";
            }
            b.append("                  => dispatchSize=").append(size).append('\n');
            if ("DEGENERATE".equals(size) || (abs == null && rel == null && ind == null)) {
                warnOnce("ddegen", P + "FINDING (once-only): dest dispatch size " + size
                    + " — a zero/absent component means ZERO invocations. R-2 CONFIRMED.", null);
            }

            // --- uniforms: the REAL program storage (this is the R-3 decider) ---
            float[] cam = readVec3(pid, "cameraPosition");
            float[] prev = readVec3(pid, "previousCameraPosition");
            b.append("      uniforms(GL program storage — REAL, not proxy):\n");
            b.append("                  cameraPosition=").append(fmt3(cam))
                .append("  previousCameraPosition=").append(fmt3(prev)).append('\n');
            if (cam != null && prev != null) {
                double dx = Math.floor(prev[0]) - Math.floor(cam[0]);
                double dy = Math.floor(prev[1]) - Math.floor(cam[1]);
                double dz = Math.floor(prev[2]) - Math.floor(cam[2]);
                double inf = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
                b.append("                  posOffset=(").append((int) dx).append(',').append((int) dy)
                    .append(',').append((int) dz).append(")  |posOffset|inf=").append(inf)
                    .append("            <== R-3 DECIDER\n");
            }
            else {
                if (cam == null) {
                    warnOnce("dnoloc-cam", P + "cameraPosition is not an active uniform on the ACT"
                        + " compute (loc=-1). The posOffset measurement is UNAVAILABLE this run.", null);
                }
                if (prev == null) {
                    warnOnce("dnoloc-prev", P + "previousCameraPosition is not an active uniform on"
                        + " the ACT compute (loc=-1) — inconsistent with shadowcomp.glsl:81. The"
                        + " posOffset measurement is UNAVAILABLE this run; R-3 is NOT thereby refuted"
                        + " — decide it on the pinpoint leg alone.", null);
                }
            }
            float[] fm2 = readVec1(pid, "framemod2");
            if (fm2 != null) {
                b.append("                  framemod2=").append(fm2[0]).append(" => this dispatch writes ")
                    .append(((int) fm2[0]) == 0 ? "floodfill_img_copy" : "floodfill_img").append('\n');
            }

            // --- camera attribution (R-6, free) ---
            Vec3 destCam = ActSeedProbe.currentDestCam();
            if (cam != null && destCam != null) {
                double d = Math.sqrt(sq(cam[0] - destCam.x) + sq(cam[1] - destCam.y) + sq(cam[2] - destCam.z));
                b.append("      cameras:    destCam=(").append(fmt(destCam.x)).append(',')
                    .append(fmt(destCam.y)).append(',').append(fmt(destCam.z))
                    .append(") d(uniform,dest)=").append(fmt(d))
                    .append(" => camMatches=").append(d < 1.0 ? "DEST" : "NOT-DEST").append('\n');
            }

            // --- image bindings (R-4) ---
            b.append("      images:     ").append(imageLine(pid, "floodfill_img"))
                .append("\n                  ").append(imageLine(pid, "floodfill_img_copy")).append('\n');
            b.append("                  ActSeedProbe destFf=").append(ActSeedProbe.currentDestFfId())
                .append('/').append(ActSeedProbe.currentDestFfCopyId()).append('\n');
        }
        catch (Throwable t) {
            disarm(t);
            return "";
        }
        return b.toString();
    }

    private static String imageLine(int pid, String name) {
        try {
            int loc = GL20.glGetUniformLocation(pid, name);
            if (loc < 0) {
                return name + " loc=-1 (not an active uniform)";
            }
            int[] unit = new int[1];
            GL20.glGetUniformiv(pid, loc, unit);
            // RUN-3 DEFECT FIX (my own, and the SECOND time this exact mistake cost a leg): gating on
            // the CORE-VERSION flag is wrong on MC 26.2/Sodium, which create a 3.3 CORE context —
            // measured: OpenGL45=false OpenGL43=false, yet ARB_get_texture_sub_image=true. Run 3 lost
            // the entire R-4 (write-side) leg to this: `tex=n/a(no GL42)` 800/800 times.
            // GL_IMAGE_BINDING_NAME is 0x8F3A in core 4.2, ARB_shader_image_load_store AND
            // EXT_shader_image_load_store alike, and the query function glGetIntegeri_v is GL 3.0
            // CORE — so it carries no version requirement at all. Mirror iris's OWN gate, which is
            // authoritative because it is what binds these images and demonstrably succeeds here
            // (IrisRenderSystem.bindImageTexture: OpenGL42 || ARB_shader_image_load_store -> GL42C,
            // else EXTShaderImageLoadStore). The LWJGL GL42.GL_IMAGE_BINDING_* names are compile-time
            // int constants, so referencing them on a 3.3 context is safe.
            org.lwjgl.opengl.GLCapabilities caps = GL.getCapabilities();
            if (!(caps.OpenGL42 || caps.GL_ARB_shader_image_load_store
                || caps.GL_EXT_shader_image_load_store)) {
                warnOnce("dnocaps", P + "image-binding leg n/a: neither core GL 4.2 nor"
                    + " ARB/EXT_shader_image_load_store is present — R-4 reads n/a;"
                    + " R-1/R-2/R-3 unaffected.", null);
                return name + " unit=" + unit[0] + " tex=n/a(no image_load_store)";
            }
            int tex = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, unit[0]);
            int fmt = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT, unit[0]);
            int layered = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED, unit[0]);
            int access = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS, unit[0]);
            int dFf = ActSeedProbe.currentDestFfId();
            int dFfC = ActSeedProbe.currentDestFfCopyId();
            if (tex != 0 && dFf > 0 && tex != dFf && tex != dFfC) {
                warnOnce("dbind", P + "FINDING (once-only): the dest ACT compute's image unit " + unit[0]
                    + " resolves to texture " + tex + " but the dest floodfill images are " + dFf + "/"
                    + dFfC + ". R-4 CONFIRMED — the writes are landing elsewhere.", null);
            }
            return name + " unit=" + unit[0] + " tex=" + tex + " fmt=0x" + Integer.toHexString(fmt)
                + " layered=" + (layered != 0) + " access=0x" + Integer.toHexString(access);
        }
        catch (Throwable t) {
            return name + " read-failed(" + t + ")";
        }
    }

    private static float[] readVec3(int pid, String name) {
        try {
            int loc = GL20.glGetUniformLocation(pid, name);
            if (loc < 0) {
                return null;
            }
            float[] v = new float[4];
            GL20.glGetUniformfv(pid, loc, v);
            return v;
        }
        catch (Throwable t) {
            return null;
        }
    }

    private static float[] readVec1(int pid, String name) {
        return readVec3(pid, name);
    }

    // =============================================================================================
    // ACT identity — cached, one GL query per program per session
    // =============================================================================================

    private static int actTag(Object prog) {
        int pid = programId(prog);
        for (int i = 0; i < tagCount; i++) {
            if (tagIds[i] == pid) {
                return tagVals[i];
            }
        }
        byte tag = 0;
        try {
            // Sound: iris only registers an image whose NAME resolves as a uniform
            // (ProgramImages$Builder.addTextureImage returns early on loc == -1).
            tag = (byte) (GL20.glGetUniformLocation(pid, "floodfill_img") != -1 ? 1 : 0);
        }
        catch (Throwable ignored) {
            tag = 0;
        }
        if (tagCount < tagIds.length) {
            tagIds[tagCount] = pid;
            tagVals[tagCount] = tag;
            tagCount++;
        }
        return tag;
    }

    private static int programId(Object prog) {
        try {
            return mGetProgramId == null ? -1 : (Integer) mGetProgramId.invoke(prog);
        }
        catch (Throwable t) {
            return -1;
        }
    }

    // =============================================================================================
    // Watchdogs
    // =============================================================================================

    private static void watchdogs() {
        // THE VOID-RUN GUARD. A session that only ever views a SAME-dim window produces zero dest ACT
        // dispatches BY DESIGN (IS5-FF's noop) and therefore zero measurement — but nothing in the log
        // says so, and it reads like a healthy run. Cost us one live round; never again.
        if (armedFrames == 45 && crossIdTotal == 0) {
            warnOnce("dnocross", P + "VOID RUN WARNING: " + sameIdTotal + " same-dimension portal"
                + " windows seen and ZERO cross-dimension ones after ~45 armed seconds. SAME-DIM"
                + " WINDOWS CANNOT PRODUCE THIS MEASUREMENT — IS5-FF suppresses their dest shadowcomp"
                + " by design (renderAll=0, act=0). Go look through an OVERWORLD<->NETHER portal, or"
                + " this run answers nothing.", null);
        }
        if (armedFrames == 120) {
            if (dispatchTotal == 0L) {
                warnOnce("dnever", P + "FINDING (once-only): the ComputeProgram.dispatch hook NEVER"
                    + " fired in 120 armed frames. Either M1 did not weave (grep the boot log for"
                    + " \"Mixin apply\") or this pack runs zero compute programs. Every R-1..R-4"
                    + " verdict from this run is VOID — do NOT read the silence as \"dispatch ran\".",
                    null);
            }
            if (renderAllTotal == 0L) {
                warnOnce("dranever", P + "FINDING (once-only): ShadowCompositeRenderer.renderAll HEAD"
                    + " never fired — R-1a cannot be measured; the dispatch counts still stand.", null);
            }
            if (dispatchDest > 0L && dispatchDestAct == 0L) {
                warnOnce("dnoact", P + "FINDING (once-only): dest dispatches occurred but NONE carries"
                    + " a \"floodfill_img\" uniform (first programId seen: " + firstDestProgId + ")."
                    + " The ACT identity test failed — profile is not ACT, or the uniform was renamed."
                    + " R-3/R-4 verdicts are VOID.", null);
            }
        }
    }

    // =============================================================================================
    // Reflection
    // =============================================================================================

    private static synchronized boolean ensureReflection() {
        if (reflectionReady) {
            return true;
        }
        if (reflectionAttempted) {
            return false;
        }
        reflectionAttempted = true;
        try {
            Class<?> cp = Class.forName("net.irisshaders.iris.gl.program.ComputeProgram");
            mGetProgramId = cp.getMethod("getProgramId");
            mGetActiveImages = cp.getMethod("getActiveImages");
            fAbsoluteWorkGroups = opt(cp, "absoluteWorkGroups");
            fRelativeWorkGroups = opt(cp, "relativeWorkGroups");
            fLocalSize = opt(cp, "localSize");
            fIndirectPointer = opt(cp, "indirectPointer");
            fUniforms = opt(cp, "uniforms");

            Class<?> pu = Class.forName("net.irisshaders.iris.gl.program.ProgramUniforms");
            fLastFrame = opt(pu, "lastFrame");

            Class<?> sr = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
            fShadowActive = sr.getField("ACTIVE");

            Class<?> stu = Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms");
            fCounter = stu.getField("COUNTER");
            Object c = fCounter.get(null);
            if (c != null) {
                mCounterGetAsInt = c.getClass().getMethod("getAsInt");
                mCounterGetAsInt.setAccessible(true);
            }

            reflectionReady = true;
            return true;
        }
        catch (ClassNotFoundException e) {
            broken = true;
            LOGGER.info(P + "iris classes not present on the runtime — dispatch probe inert");
            return false;
        }
        catch (Throwable t) {
            broken = true;
            warnOnce("dresolve", P + "DISARMED: could not resolve the iris ComputeProgram/"
                + "ProgramUniforms symbols on this Iris build. No R-1..R-4 verdict may be read from"
                + " this run.", t);
            return false;
        }
    }

    private static Field opt(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
        catch (Throwable t) {
            LOGGER.info(P + "optional symbol {}.{} absent on this Iris build — that field reads n/a",
                owner.getSimpleName(), name);
            return null;
        }
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    private static String readStaticBool(Field f) {
        try {
            return f == null ? "n/a" : String.valueOf(f.getBoolean(null));
        }
        catch (Throwable t) {
            return "n/a";
        }
    }

    private static String invokeInt(Method m, Object o) {
        try {
            return m == null ? "n/a" : String.valueOf(m.invoke(o));
        }
        catch (Throwable t) {
            return "n/a";
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String fmt(double d) {
        return String.format("%.3f", d);
    }

    private static String fmt3(float[] v) {
        return v == null ? "n/a" : ("(" + fmt(v[0]) + "," + fmt(v[1]) + "," + fmt(v[2]) + ")");
    }

    /** Called by ActSeedProbe so the lever-pairing WARN fires once, at a known point. */
    static void checkLeverPairing(boolean seedProbeEnabled) {
        if (ENABLED && !seedProbeEnabled) {
            warnOnce("dlever", P + "-Dseamlessportals.actDispatchProbe requires"
                + " -Dseamlessportals.actProbe — the dispatch leg counts but never emits;"
                + " re-run with both.", null);
        }
    }

    private static void warnOnce(String key, String msg, Throwable t) {
        if (!warnedOnce.add(key)) {
            return;
        }
        try {
            if (t != null) {
                LOGGER.warn(msg, t);
            }
            else {
                LOGGER.warn(msg);
            }
        }
        catch (Throwable ignored) {
            // never let the probe's own logging escape
        }
    }

    private static void infoOnce(String key, String msg) {
        if (!warnedOnce.add(key)) {
            return;
        }
        try {
            LOGGER.info(msg);
        }
        catch (Throwable ignored) {
            // never let the probe's own logging escape
        }
    }

    private static void disarm(Throwable t) {
        broken = true;
        try {
            LOGGER.warn(P + "DISARMED after a throw — the dispatch leg is dead for this session"
                + " (render unaffected). No R-1..R-4 verdict may be read from a silent log.", t);
        }
        catch (Throwable ignored) {
            // never let the probe's own failure escape
        }
    }
}
