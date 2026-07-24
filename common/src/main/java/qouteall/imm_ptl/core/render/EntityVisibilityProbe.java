package qouteall.imm_ptl.core.render;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * §2b — the DEST-ENTITY VISIBILITY funnel probe (polish-queue engagement, 2026-07-24;
 * recon in migration/POLISH_SESSION_NOTES.md §2b). <b>DIAGNOSTIC ONLY — a PROBE, not a fix.</b>
 *
 * <p><b>Why it exists.</b> Entities are reported invisible through portal windows under sodium
 * (the ledgered C2-era regression), yet BOTH sodium-cull neutralizes are present and registered
 * (LevelRendererEntityVisibilityMixin C2-1e + MixinSodiumRenderSectionManager D5) — statically the
 * path should work. This probe measures the whole funnel per 1Hz window so the LIVE loss point
 * names itself instead of us guessing:
 *
 * <pre>
 *   lvl   dest ClientLevel.getEntityCount() census at each dest extract (0 = the level itself is
 *         empty — a MIRROR/tracking gap, not a render gap)
 *   cons  entities reaching LevelExtractor.isEntityVisible during a dest extract (0 with lvl&gt;0 =
 *         iteration never ran / skipped)
 *   rej   isEntityVisible false verdicts during dest extracts (== cons ⇒ the gate rejects all;
 *         read with hid/nv to name WHICH conjunct)
 *   hid   MixinEntityRenderDispatcher shouldRenderEntityNow==false cancels during dest extracts
 *         (the deliberate cross-portal hide — over-hiding is a culprit candidate)
 *   nv    C2-1e neutralize fires (vanilla isSectionCompiledAndVisible forced true; 0 during dest
 *         extracts ⇒ FRAGILITY A/B — the sodium branch is not being taken)
 *   nd5   D5 neutralize fires (RSM.isBoxVisible forced true)
 *   extr  destLRS.entityRenderStates.size() after each dest extract (extract OUTPUT)
 *   sub   entityRenderStates.size() summed at submitEntities HEAD inside portal rendering
 *         (what the submit stage RECEIVES)
 *   pes   per-entity dispatcher.submit invocations inside portal rendering (h= the seam-handled
 *         subset); pes&gt;0 with entities still invisible ⇒ the loss is DOWNSTREAM of submit
 *         (draw/target/clip/stamp) → round 2
 * </pre>
 *
 * <p>Routes tag: x = cross-dim decomposed (stencil family), f = cross-dim full-pipeline (iris
 * compat), sd = same-dim isolated pass — so one line also says WHICH route ran.
 *
 * <p><b>Discipline</b> (ShadowAliasProbe conventions): lever {@code -Dseamlessportals.entityProbe}
 * (default-OFF, {@code -PentityProbe} passthroughs in both fabric/build.gradle blocks); LOG-ONLY;
 * render-thread only; 1Hz max ({@code onFrameEnd} prints at most once per second, and only for
 * windows that saw portal rendering — an all-zero line on a portal-on-screen window IS the
 * "no dest entity machinery ran at all" finding); once-only ACTIVE liveness line; counters are
 * plain static ints fed from probe-guarded call sites (all sites early-out on {@code !ENABLED}).
 */
@Environment(EnvType.CLIENT)
public final class EntityVisibilityProbe {

    private EntityVisibilityProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String P = "[ENT-PROBE] ";

    /** The lever. Byte-inert unless {@code -Dseamlessportals.entityProbe=true}. */
    public static final boolean ENABLED = Boolean.getBoolean("seamlessportals.entityProbe");

    private static final long RATE_LIMIT_NS = 1_000_000_000L;
    private static long lastEmitNanos = 0L;
    private static boolean activeLogged = false;

    // ===== window counters (render-thread only; reset each 1Hz emit) ============================
    /** Frames in the window where RenderStates.portalsRenderedThisFrame != 0 (sampled at frame end
     *  BEFORE the counter reset — GameRendererMixin's call site precedes onFrameEnd's reset there;
     *  actually sampled live per frame, see onFrameEnd). */
    public static int portalFrames = 0;
    /** submitEntities HEAD entries with PortalRendering.isRendering() — dest submit passes. */
    public static int destPasses = 0;
    /** Route markers: cross-dim decomposed / cross-dim full-pipeline / same-dim passes. */
    public static int routeDecomposed = 0;
    public static int routeFullPipeline = 0;
    public static int routeSameDim = 0;
    /** Latest dest-level entity census (getEntityCount at a dest extract) + its dim path. */
    public static int levelCensus = -1;
    public static String censusDim = "-";
    /** isEntityVisible calls / false-verdicts during dest extracts. */
    public static int considered = 0;
    public static int rejected = 0;
    /** shouldRenderEntityNow==false cancels during dest extracts. */
    public static int hiddenByGate = 0;
    /** C2-1e vanilla-gate neutralize fires (sodium force-true branch). */
    public static int neutralizeVanilla = 0;
    /** D5 RSM.isBoxVisible neutralize fires. */
    public static int neutralizeD5 = 0;
    /** Sum of destLRS.entityRenderStates.size() right after each dest extract. */
    public static int extracted = 0;
    /** Sum of entityRenderStates.size() at submitEntities HEAD inside portal rendering. */
    public static int submittedList = 0;
    /** Per-entity dispatcher.submit invocations inside portal rendering (+ seam-handled subset). */
    public static int perEntitySubmits = 0;
    public static int perEntityHandled = 0;
    /** renderPortalEntities storage==null silent-skip occurrences (see the once-only WARN there). */
    public static int storageNullSkips = 0;
    /** Gate snapshot, sampled once per window at the first dest-extract isEntityVisible call. */
    public static boolean gateSampled = false;
    public static boolean gateSodiumPresent = false;
    public static int gatePortalsRenderedThisFrame = -1;

    /** Called from SecondaryWorldRenderCore right after each dest entity extract. Route:
     *  "x" decomposed cross-dim, "f" full-pipeline cross-dim, "sd" same-dim. */
    public static void recordDestExtract(
        String route, net.minecraft.client.multiplayer.ClientLevel level, int extractedCount
    ) {
        if (!ENABLED) {
            return;
        }
        switch (route) {
            case "x" -> routeDecomposed++;
            case "f" -> routeFullPipeline++;
            default -> routeSameDim++;
        }
        if (level != null) {
            levelCensus = level.getEntityCount();
            censusDim = level.dimension().identifier().getPath();
        }
        extracted += extractedCount;
    }

    /** Sampled by the isEntityVisible HEAD hook, once per window. */
    public static void sampleGates() {
        if (gateSampled) {
            return;
        }
        gateSampled = true;
        gateSodiumPresent = qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface
            .invoker.isSodiumPresent();
        gatePortalsRenderedThisFrame = RenderStates.portalsRenderedThisFrame;
    }

    /**
     * Called from GameRendererMixin at GameRenderer.render TAIL (beside TeleportFlashProbe).
     * 1Hz max; emits only for windows that saw portal rendering.
     */
    public static void onFrameEnd() {
        if (!ENABLED) {
            return;
        }
        if (!activeLogged) {
            activeLogged = true;
            LOGGER.info(P + "ACTIVE (1Hz; lever -Dseamlessportals.entityProbe; schema: lvl census "
                + "-> cons/rej gate -> extr -> sub -> pes; routes x/f/sd; nv/nd5 = neutralize fires;"
                + " hid = cross-portal hide)");
        }
        if (RenderStates.portalsRenderedThisFrame != 0) {
            portalFrames++;
        }
        long now = System.nanoTime();
        if (now - lastEmitNanos < RATE_LIMIT_NS) {
            return;
        }
        lastEmitNanos = now;
        if (portalFrames == 0 && destPasses == 0 && routeDecomposed + routeFullPipeline
            + routeSameDim == 0) {
            // Nothing portal-related this window — stay silent (log4j discipline).
            resetWindow();
            return;
        }
        LOGGER.info(P
            + "pf=" + portalFrames
            + " dp=" + destPasses
            + " routes[x=" + routeDecomposed + ",f=" + routeFullPipeline
            + ",sd=" + routeSameDim + "]"
            + " lvl=" + levelCensus + "@" + censusDim
            + " cons=" + considered
            + " rej=" + rejected
            + " hid=" + hiddenByGate
            + " nv=" + neutralizeVanilla
            + " nd5=" + neutralizeD5
            + " extr=" + extracted
            + " sub=" + submittedList
            + " pes=" + perEntitySubmits + "(h=" + perEntityHandled + ")"
            + (storageNullSkips > 0 ? " STORAGE-NULL=" + storageNullSkips : "")
            + " gates[sod=" + (gateSampled ? (gateSodiumPresent ? "T" : "F") : "?")
            + " pf@x=" + gatePortalsRenderedThisFrame + "]"
            + " latch[" + SecondaryWorldRenderCore.entityProbeLatchSummary() + "]");
        resetWindow();
    }

    private static void resetWindow() {
        portalFrames = 0;
        destPasses = 0;
        routeDecomposed = 0;
        routeFullPipeline = 0;
        routeSameDim = 0;
        levelCensus = -1;
        censusDim = "-";
        considered = 0;
        rejected = 0;
        hiddenByGate = 0;
        neutralizeVanilla = 0;
        neutralizeD5 = 0;
        extracted = 0;
        submittedList = 0;
        perEntitySubmits = 0;
        perEntityHandled = 0;
        storageNullSkips = 0;
        gateSampled = false;
        gateSodiumPresent = false;
        gatePortalsRenderedThisFrame = -1;
    }
}
