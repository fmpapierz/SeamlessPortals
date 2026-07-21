package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;

/**
 * IS6 §6.4 — THE INSTRUMENT-FIRST SPIKE PROBE (the reentrancy probe; port-note
 * {@code IS-iris-shaders-on.md} §6.4). Lever-gated diagnostics that MUST run BEFORE trusting the
 * bounded N-deep recursion at depth. It logs the five §6.4 items per nested full-pipeline
 * {@code render()} boundary so the mirror / same-dim / cross-dim spike scenes can be classified
 * from logs alone:
 *
 * <ol>
 *   <li><b>sectionUpdateRenderStates identity + size</b> at swap-out AND restore
 *       ({@link #logSectionUpdateStateSwap}) — the list OBJECT identity must be stable across an
 *       inner swap and the outer restore; a differing identity ⇒ §2.6 double-swap corruption.</li>
 *   <li><b>the MAIN FRD {@code PreparedFrame} "in use" state</b> before/after each nested
 *       {@code render()} ({@link #beforeNestedRender}/{@link #afterNestedRender}) — the single most
 *       likely HARD BLOCKER (§6.3 hazard 1): if the shared MAIN {@code FeatureRenderDispatcher}'s
 *       frame is already open when a nested same-dim {@code render()} begins, it throws
 *       "PreparedFrame already in use" = whole-frame abort. Seeing IN_USE at the same-dim probe is
 *       the STOP signal (ship fallback (a), do NOT go N-deep).</li>
 *   <li><b>sodium context identity + {@code getVisibleChunkCount}</b> per layer — cross-contamination
 *       between layers ⇒ D1 keying failure.</li>
 *   <li><b>a cloud-utb-rotation counter</b> per dest dim per frame — a cross-dim dim rendered
 *       {@code >1}× a frame drives the secondary {@code CloudRenderer} utb ring twice = the S18.3
 *       "Cannot wait on a fence" precondition (§6.3 hazard 2).</li>
 *   <li><b>an assert that {@code isDestExtracting} is never true</b> at a nested-{@code render()}
 *       boundary (§6.3 hazard 5: extracts must never nest).</li>
 * </ol>
 *
 * <p>Lever: {@code -Dseamlessportals.reentrancyProbe=true} (wired via gradle -PreentrancyProbe=true;
 * the SAME property {@code IPGlobal.IRIS_NESTED_REENTRANCY_PROBE_LEVER} reads to cap the spike depth
 * to 2). BYTE-INERT at the default — the lever is a RUNTIME-read {@code static}
 * ({@code Boolean.getBoolean} — not a javac compile-time constant, so the guards are real
 * short-circuit branches, not dead-code-eliminated), and every entry point returns on the first
 * field read.
 *
 * <p><b>Render-thread only</b> (both {@code renderDestWorldFullPipeline} and its callers run on the
 * render thread), so the plain {@code HashMap} throttles and the mutable counters are
 * thread-confined — the {@code logSameDimSupplyProbe} pattern. <b>DISARMS ITSELF on any throw</b>
 * (globally, or per sub-probe for the FRD/sodium reflection) so a diagnostic can never take down a
 * render.
 */
public final class IrisNestedReentrancyProbe {

    private IrisNestedReentrancyProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The lever. Byte-inert unless {@code -Dseamlessportals.reentrancyProbe=true}. */
    private static final boolean ENABLED = Boolean.getBoolean("seamlessportals.reentrancyProbe");

    /** 1Hz rate limit between the (throttled) informational per-pass lines (ns). */
    private static final long RATE_LIMIT_NS = 1_000_000_000L;

    private static boolean disarmed = false;

    /** 1Hz throttle, keyed per pass identity (dim:layer[:tag]) — mirrors logSameDimSupplyProbe. */
    private static final HashMap<String, Long> lastLogNanos = new HashMap<>();

    /**
     * Nested render() boundary depth. Because the driver fires ONLY post-finalize (§6.0), nested
     * {@code render()} calls are strictly SEQUENTIAL, never re-entrant — so this must never exceed 1
     * at a boundary. A value {@code >1} is the diagnostic that the rejected mid-pass deep-end has
     * collapsed the design (a render()-inside-render()).
     */
    private static int pendingDepth = 0;

    /** Set by {@link #beforeNestedRender} (per 1Hz throttle) and consumed by
     *  {@link #afterNestedRender} so the before/after info lines pair up. */
    private static boolean lastBeforeInfoLogged = false;

    // ===== per-frame cloud-utb counter (item 4) =================================================
    private static int currentFrameIndex = Integer.MIN_VALUE;
    private static final HashMap<String, Integer> crossDimRenderThisFrame = new HashMap<>();

    // ===== FRD reflection (item 2) — cached Field handles; sub-disarms independently ============
    private enum FrdState { NULL, FREE, IN_USE, UNKNOWN }

    private static boolean frdReflectReady = false;
    private static boolean frdDisarmed = false;
    private static Field preparedFrameField;
    private static Field contextField;

    // ===== sodium reflection (item 3) — sub-disarms independently ==============================
    private static boolean sodiumDisarmed = false;

    /**
     * Called immediately BEFORE the nested 8-arg {@code destRenderer.render()} in
     * {@code renderDestWorldFullPipeline}. Emits the hard-blocker signals (FRD-in-use, extract
     * nesting, mid-pass deep-end, cross-dim cloud revisit) unconditionally and a throttled state
     * line.
     */
    public static void beforeNestedRender(String dim, int layer, boolean sharedState) {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            maybeResetFrame();

            // item 5 — extract non-nesting assert.
            boolean extracting = qouteall.imm_ptl.core.render.SecondaryWorldRenderCore.isDestExtracting;
            if (extracting) {
                LOGGER.warn("[IS6-REENTRANCY] isDestExtracting==TRUE at a nested render() boundary "
                    + "(dim={} layer={}) — extract NESTING; the MixinParticleEngine guard is "
                    + "corruptible (§6.3 hazard 5). This should be impossible (extracts run inside "
                    + "each pass's own try/finally BEFORE render()).", dim, layer);
            }

            // mid-pass deep-end detector (§6.0): render() calls must be sequential, never nested.
            pendingDepth++;
            if (pendingDepth > 1) {
                LOGGER.warn("[IS6-REENTRANCY] NESTED render() boundary (pendingDepth={}, dim={} "
                    + "layer={}) — renderDestWorldFullPipeline re-entered DURING render() = the "
                    + "REJECTED mid-pass deep-end (§6.0). The driver must fire only POST-finalize; "
                    + "a mid-pass hook has been wired by mistake.", pendingDepth, dim, layer);
            }

            // item 4 — cloud-utb rotation counter (cross-dim revisit → S18.3 fence-crash class).
            if (!sharedState) {
                int c = crossDimRenderThisFrame.merge(dim, 1, Integer::sum);
                if (c > 1) {
                    LOGGER.warn("[IS6-REENTRANCY] cross-dim dest dim={} rendered {}x this frame "
                        + "(layer={}) — >1 drives the secondary CloudRenderer utb ring more than once "
                        + "= the S18.3 'Cannot wait on a fence for the current submit' precondition "
                        + "(§6.3 hazard 2).", dim, c, layer);
                }
            }

            // item 2 — the HARD BLOCKER discriminator (before render()).
            FrdState frd = readFrdState();
            if (frd == FrdState.IN_USE) {
                LOGGER.warn("[IS6-REENTRANCY] MAIN FRD PreparedFrame ALREADY IN USE before render() "
                    + "(dim={} layer={} shared={}) — the HARD-BLOCKER precondition: render() is about "
                    + "to throw 'PreparedFrame already in use' (§6.3 hazard 1). If seen at the "
                    + "same-dim probe, DO NOT proceed to N-deep — ship fallback (a).",
                    dim, layer, sharedState);
            }

            // throttled per-pass state line (items 2+3 normal case).
            long now = System.nanoTime();
            String passKey = dim + ":" + layer;
            Long last = lastLogNanos.get(passKey);
            if (last == null || now - last >= RATE_LIMIT_NS) {
                lastLogNanos.put(passKey, now);
                lastBeforeInfoLogged = true;
                LOGGER.info("[IS6-REENTRANCY] before render dim={} layer={} shared={} frd={} "
                    + "extracting={} pendingDepth={} sodium={}",
                    dim, layer, sharedState, frd, extracting, pendingDepth, readSodium());
            }
            else {
                lastBeforeInfoLogged = false;
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * Called immediately AFTER the nested 8-arg {@code destRenderer.render()}. The MAIN FRD frame
     * must have closed (context null); a still-open frame indicates a leak / exception path.
     */
    public static void afterNestedRender(String dim, int layer, boolean sharedState) {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            if (pendingDepth > 0) {
                pendingDepth--;
            }
            FrdState frd = readFrdState();
            if (frd == FrdState.IN_USE) {
                LOGGER.warn("[IS6-REENTRANCY] MAIN FRD PreparedFrame STILL IN USE after render() "
                    + "(dim={} layer={}) — render() left a frame open (leak / exception path); the "
                    + "NEXT nested render() will hit the hazard-1 blocker.", dim, layer);
            }
            if (lastBeforeInfoLogged) {
                LOGGER.info("[IS6-REENTRANCY] after  render dim={} layer={} frd={} sodium={}",
                    dim, layer, frd, readSodium());
            }
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /**
     * Item 1 — log the MAIN LRS {@code sectionUpdateRenderStates} object identity + size at a swap
     * event ({@code tag} = "swap-out" | "restore"). The identity must be IDENTICAL at swap-out and
     * restore for the same pass (one owner, non-refill).
     */
    public static void logSectionUpdateStateSwap(String tag, String dim, int layer, List<?> states) {
        if (!ENABLED || disarmed) {
            return;
        }
        try {
            String passKey = "swap:" + dim + ":" + layer + ":" + tag;
            long now = System.nanoTime();
            Long last = lastLogNanos.get(passKey);
            if (last != null && now - last < RATE_LIMIT_NS) {
                return;
            }
            lastLogNanos.put(passKey, now);
            LOGGER.info("[IS6-REENTRANCY] sectionUpdateRenderStates {} dim={} layer={} identity={} "
                + "size={}", tag, dim, layer, System.identityHashCode(states), states.size());
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    // ===== helpers ==============================================================================

    private static void maybeResetFrame() {
        int f = qouteall.imm_ptl.core.render.context_management.RenderStates.frameIndex;
        if (f != currentFrameIndex) {
            currentFrameIndex = f;
            crossDimRenderThisFrame.clear();
            if (pendingDepth != 0) {
                LOGGER.warn("[IS6-REENTRANCY] pendingDepth={} at a frame boundary — reset to 0 "
                    + "(a prior nested render() boundary was left unbalanced)", pendingDepth);
                pendingDepth = 0;
            }
        }
    }

    /** Read the MAIN {@code FeatureRenderDispatcher}'s {@code PreparedFrame.context != null}. */
    private static FrdState readFrdState() {
        if (frdDisarmed) {
            return FrdState.UNKNOWN;
        }
        try {
            Object frd = Minecraft.getInstance().gameRenderer.featureRenderDispatcher();
            if (frd == null) {
                return FrdState.NULL;
            }
            if (!frdReflectReady) {
                Field pf = frd.getClass().getDeclaredField("preparedFrame");
                pf.setAccessible(true);
                Object pfObj = pf.get(frd);
                if (pfObj == null) {
                    return FrdState.NULL;
                }
                Field cf = pfObj.getClass().getDeclaredField("context");
                cf.setAccessible(true);
                preparedFrameField = pf;
                contextField = cf;
                frdReflectReady = true;
            }
            Object pfObj = preparedFrameField.get(frd);
            if (pfObj == null) {
                return FrdState.NULL;
            }
            Object ctx = contextField.get(pfObj);
            return ctx != null ? FrdState.IN_USE : FrdState.FREE;
        }
        catch (Throwable t) {
            frdDisarmed = true;
            LOGGER.warn("[IS6-REENTRANCY] FRD reflection disarmed (diagnostic only, render "
                + "unaffected): {}", t.toString());
            return FrdState.UNKNOWN;
        }
    }

    /** Read the CURRENT levelRenderer's sodium context identity + visible chunk count. During a
     *  nested render the shell has swapped {@code client.levelRenderer} to the dest renderer, so this
     *  reads the per-layer dest context (identity cross-contamination ⇒ D1 keying failure). */
    private static String readSodium() {
        if (sodiumDisarmed) {
            return "n/a";
        }
        try {
            Object renderer = Minecraft.getInstance().levelRenderer;
            Object swr = renderer.getClass().getMethod("sodium$getWorldRenderer").invoke(renderer);
            if (swr == null) {
                sodiumDisarmed = true;
                return "no-swr";
            }
            Object count = swr.getClass().getMethod("getVisibleChunkCount").invoke(swr);
            return "ctx#" + System.identityHashCode(swr) + "/visible=" + count;
        }
        catch (Throwable t) {
            sodiumDisarmed = true;
            return "absent";
        }
    }

    private static void disarm(Throwable t) {
        disarmed = true;
        try {
            LOGGER.warn("[IS6-REENTRANCY] disarmed after a throw (diagnostic only, render "
                + "unaffected)", t);
        }
        catch (Throwable ignored) {
            // never let the probe's own failure escape
        }
    }
}
