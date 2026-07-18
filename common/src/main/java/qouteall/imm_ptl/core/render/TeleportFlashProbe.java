package qouteall.imm_ptl.core.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S14.45 — the TELEPORT-FLASH capture kit (NO-GUESSING: instrument first, classify second).
 *
 * <p><b>The observation under test (user, far-walk retest round):</b> a one-frame flash at portal
 * crossings visible ONLY on distant-fog/sky pixels — looking down at nearby terrain during the
 * crossing shows nothing. That pixel class (far depth: pixels no geometry overwrites) is painted
 * exclusively by the frame's clear fill (fogData.color), the sky pass, and the clouds pass.
 *
 * <p><b>What this probe captures (the Opus enumeration wf_92129b0d-5f7 candidate set, ALL
 * captured so the log discriminates instead of us guessing):</b>
 * <ul>
 *   <li><b>Retained-probe atmospheric lag:</b> {@code Camera.attributeProbe} is NOT reset by the
 *       crossing ({@code Camera.setLevel} only swaps the level pointer), so FOG/SKY/CLOUD colors
 *       lerp source→dest over ~1 tick while geometry snaps. Captured as the frame's actual
 *       fogData.color / skyColor / cloudColor PLUS the raw probe endpoints — {@code getValue(attr,
 *       0)} = lastValue (pre-tick sample) vs {@code getValue(attr, 1)} = newValue (current-dim
 *       sample); a lagging frame shows last≠new with the painted value in between.</li>
 *   <li><b>One-frame sky-pass skip:</b> vanilla extract skips sky when
 *       {@code levelRenderer.skyRenderer()==null} (a promoted renderer that never ran addSkyPass),
 *       leaving skybox=NONE after the reset → no sky pass → flat clear color on far pixels for
 *       exactly one frame. Signature in the rows: skybox=NONE + MAIN skyDraws=0 on the skip frame
 *       (skyRenNull reads false by then — addSkyPass constructs the renderer before its skybox
 *       gate — and only confirms recovery on later frames).</li>
 *   <li><b>Retained rain-fog distance lag:</b> the singleton AtmosphericFogEnvironment's
 *       rainFogMultiplier converges 0.2/tick cross-dim → fog DISTANCES stale several frames.
 *       Captured: the multiplier + all FogData distances.</li>
 *   <li><b>Stutter profile (the second job — IP side-by-side reports zero felt lag; ours
 *       stutters):</b> per-frame wall time + visibleSections size + dispatcher compile-queue depth
 *       + terrain-override/applyFrustum markers, so the hitch's shape (single spike vs elevated
 *       tail; compile-bound vs anomalous) is read off the same window. Rows also mark frames where
 *       RenderChainProbe wrote its 1Hz line (a log4j write can itself stall — memory
 *       render-thread-logging-log4j-stall — so those frames must not be misread).</li>
 * </ul>
 *
 * <p><b>Cadence discipline (memory render-thread-logging-log4j-stall):</b> ZERO log writes on the
 * hot path. Every frame appends ONE preformatted row to an in-memory ring (always-on, so each
 * capture includes pre-crossing baseline rows of the old dim's steady state). A capture window —
 * armed by every promote ({@link #armOnPromote}) or the {@code debug_capture_flash} lever — runs
 * {@link #CAPTURE_FRAMES} frames and then flushes the whole ring as ONE log write
 * (DrawCallTrace's batched-dump pattern). A promote landing mid-capture extends the window and
 * inserts a marker row instead of nesting.
 *
 * <p>S20 ledger: removal candidate with RenderChainProbe/DrawCallTrace once the flash + stutter
 * are classified.
 */
@Environment(EnvType.CLIENT)
public class TeleportFlashProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmPtlFlashProbe");

    private static final int RING_SIZE = 96;
    /** ~2/3 s at 60fps past the last arm — covers the flash frame(s) + the stutter tail. */
    private static final int CAPTURE_FRAMES = 40;

    private static final String[] ring = new String[RING_SIZE];
    private static int ringWrite = 0;
    private static long frameCounter = 0;
    private static long lastFrameEndNanos = 0;
    private static int captureRemaining = 0;

    // Set by SkyRendererTargetMixin on every sky-family draw; sampled + reset each frame TAIL.
    // Kit-verify fold (wf_a144117b-011): main-pass and portal-pass sky draws are counted
    // SEPARATELY (split by WorldRenderInfo.isRendering at the draw site) — under stencil-direct
    // both passes draw into the same main target, so target hashes cannot tell them apart, and
    // the sky-skip discriminator needs the MAIN count alone. NOTE (same fold): skyRenNull can
    // never read true at TAIL on the skip frame itself — addSkyPass constructs the SkyRenderer
    // BEFORE its skybox gate — so the skip signature in the rows is skybox=NONE + skyDraws=0;
    // skyRenNull merely confirms recovery on later frames.
    public static int skyDrawsThisFrame = 0;
    public static int portalSkyDrawsThisFrame = 0;
    public static int skyTargetHashA = 0;
    public static int skyTargetHashB = 0;

    // S14.47 — promote-frame phase timing (the zero-lag hunt: every crossing costs one 15-29ms
    // frame; these name what's inside it). Set by ClientWorldLoader (whole promote body) and
    // MixinLevelExtractor_TerrainSetupOverride (the synchronous override discovery); sampled +
    // reset each TAIL; printed only when nonzero.
    public static long promoteNanosThisFrame = 0;
    public static long discoveryNanosThisFrame = 0;
    /** S14.48: vanilla applyFrustum's visibleSections yield BEFORE the override decision
     *  (-1 = no applyFrustum this frame). dMs present on the same row = the discovery branch
     *  ran (blank-ish yield); absent = the warm vanilla fill was kept. */
    public static int vanillaYieldThisFrame = -1;
    /** S14.49 (the many-portal steady-state lag): dest passes rendered this frame (incl.
     *  nesting layers) — regressing row ms against dp names the per-portal cost. */
    public static int destPassesThisFrame = 0;
    /** S14.51 F0: armed-fold compile schedules this frame, split MAIN-dim arm (nested/return
     *  passes) vs normal dest arms — the standstill-churn attribution (fs=M+D in rows). */
    public static int foldSchedMainThisFrame = 0;
    public static int foldSchedDestThisFrame = 0;
    /** S14.52: total dest-pass wall time this frame (dpMs=) — with dp=N, splits per-pass cost
     *  from between-pass overhead in the many-portal superlinear-lag hunt. */
    public static long destPassNanosThisFrame = 0;
    /** S15 (recursive-view entities): sde=P:L:E:S:T — same-dim/loop-back entity passes this
     *  frame (P), max recursion layer among them (L), entity states extracted (E) vs submitted
     *  (S — E>0,S=0 brackets a throw between the two), and the one-shot-swallow flag (T).
     *  The live discrimination row for the S15 watch-list fix (port-note S15 §4). */
    public static int sameDimPassesThisFrame = 0;
    public static int sameDimMaxLayerThisFrame = 0;
    public static int sameDimEntitiesExtracted = 0;
    public static int sameDimEntitiesSubmitted = 0;
    public static int sameDimEntityThrow = 0;

    // For the rcLog marker (did RenderChainProbe write a 1Hz line since the previous row?).
    private static long lastSeenRcLogMs = 0;

    /** Called by ClientWorldLoader at the end of every promote, beside RenderChainProbe's arm. */
    public static void armOnPromote(String fromDim, String toDim, boolean cold) {
        insertMarker("[PROMOTE " + fromDim + " -> " + toDim + " (" + (cold ? "cold" : "warm") + ")]");
        captureRemaining = CAPTURE_FRAMES;
    }

    /** debug_capture_flash lever: a manual 40-frame capture+dump without a crossing (baseline). */
    public static void armManual() {
        insertMarker("[MANUAL CAPTURE]");
        captureRemaining = CAPTURE_FRAMES;
    }

    private static void insertMarker(String marker) {
        ring[ringWrite] = marker;
        ringWrite = (ringWrite + 1) % RING_SIZE;
    }

    /**
     * Called from GameRendererMixin at GameRenderer.render TAIL, every frame — after extract AND
     * all draws (main + portal passes), so this frame's fogData/skyRenderState are final and the
     * sky-draw counters are complete. Builds one row; never logs unless a capture window closes.
     */
    public static void onFrameEnd() {
        long now = System.nanoTime();
        double frameMs = lastFrameEndNanos == 0 ? -1 : (now - lastFrameEndNanos) / 1.0e6;
        lastFrameEndNanos = now;
        frameCounter++;
        int skyDraws = skyDrawsThisFrame;
        int portalSkyDraws = portalSkyDrawsThisFrame;
        int targetA = skyTargetHashA;
        int targetB = skyTargetHashB;
        long promoteNanos = promoteNanosThisFrame;
        long discoveryNanos = discoveryNanosThisFrame;
        int vanillaYield = vanillaYieldThisFrame;
        int destPasses = destPassesThisFrame;
        int foldM = foldSchedMainThisFrame;
        int foldD = foldSchedDestThisFrame;
        long destPassNanos = destPassNanosThisFrame;
        int sdeP = sameDimPassesThisFrame;
        int sdeL = sameDimMaxLayerThisFrame;
        int sdeE = sameDimEntitiesExtracted;
        int sdeS = sameDimEntitiesSubmitted;
        int sdeT = sameDimEntityThrow;
        sameDimPassesThisFrame = 0;
        sameDimMaxLayerThisFrame = 0;
        sameDimEntitiesExtracted = 0;
        sameDimEntitiesSubmitted = 0;
        sameDimEntityThrow = 0;
        destPassesThisFrame = 0;
        foldSchedMainThisFrame = 0;
        foldSchedDestThisFrame = 0;
        destPassNanosThisFrame = 0;
        skyDrawsThisFrame = 0;
        portalSkyDrawsThisFrame = 0;
        skyTargetHashA = 0;
        skyTargetHashB = 0;
        promoteNanosThisFrame = 0;
        discoveryNanosThisFrame = 0;
        vanillaYieldThisFrame = -1;
        try {
            ring[ringWrite] = collectRow(
                frameMs, skyDraws, portalSkyDraws, targetA, targetB,
                promoteNanos, discoveryNanos, vanillaYield, destPasses, foldM, foldD,
                destPassNanos, sdeP, sdeL, sdeE, sdeS, sdeT);
        }
        catch (Throwable t) {
            // The probe must never take down the frame.
            ring[ringWrite] = "f=" + frameCounter + " row-collect-failed: " + t;
        }
        ringWrite = (ringWrite + 1) % RING_SIZE;
        if (captureRemaining > 0) {
            captureRemaining--;
            if (captureRemaining == 0) {
                dump();
                // Kit-verify fold (wf_a144117b-011): the batched write itself stalls the frame
                // it runs in, and that stall lands in the NEXT row's ms (rows persist in the
                // always-on ring and can re-print as baseline in an overlapping capture) — the
                // marker keeps it from being misread as organic stutter.
                insertMarker("[DUMP FLUSHED — the next row's ms includes this write's stall]");
            }
        }
    }

    private static String collectRow(
        double frameMs, int skyDraws, int portalSkyDraws, int targetA, int targetB,
        long promoteNanos, long discoveryNanos, int vanillaYield, int destPasses,
        int foldM, int foldD, long destPassNanos,
        int sdeP, int sdeL, int sdeE, int sdeS, int sdeT
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.levelRenderer == null || mc.gameRenderer == null) {
            return "f=" + frameCounter + " no-level";
        }
        LevelRenderer renderer = mc.levelRenderer;
        LevelRenderState lrs = mc.gameRenderer.gameRenderState().levelRenderState;
        SkyRenderState sky = lrs.skyRenderState;
        FogData fog = lrs.cameraRenderState.fogData;
        var fogType = lrs.cameraRenderState.fogType;
        var probe = mc.gameRenderer.mainCamera().attributeProbe();
        // t=0 -> lastValue (the previous tick's promoted sample), t=1 -> newValue (the current
        // level's sample). A crossing frame mid-lerp shows last != new; the painted color above
        // sits between them. (getValue may populate newValue if the extract somehow didn't this
        // frame — identical to what vanilla's next read would do; behavior-neutral.)
        int fogLast = probe.getValue(EnvironmentAttributes.FOG_COLOR, 0.0f);
        int fogNew = probe.getValue(EnvironmentAttributes.FOG_COLOR, 1.0f);
        int skyLast = probe.getValue(EnvironmentAttributes.SKY_COLOR, 0.0f);
        int skyNew = probe.getValue(EnvironmentAttributes.SKY_COLOR, 1.0f);

        float rainFogMult = Float.NaN;
        var atmo = SecondaryWorldRenderCore.getAtmosphericFogEnvironment();
        if (atmo != null) {
            rainFogMult = ((qouteall.imm_ptl.core.mixin.client.accessor.IEAtmosphericFogEnvironment) atmo)
                .ip_getRainFogMultiplier();
        }

        var dispatcher = renderer.sectionRenderDispatcher();
        // S14.48 verify MAJOR fold: compare against lastWriteMs — stamped ONLY by an actual
        // LOGGER write — so rcLog marks exactly the frames a log4j stall could contaminate
        // (lastLogMs is pacing state and mutates at arm with no write).
        boolean rcLogged = RenderChainProbe.lastWriteMs != lastSeenRcLogMs;
        lastSeenRcLogMs = RenderChainProbe.lastWriteMs;

        return "f=" + frameCounter
            + " ms=" + String.format("%.2f", frameMs)
            + " dim=" + mc.level.dimension().identifier().getPath()
            + " camY=" + String.format("%.1f", mc.gameRenderer.mainCamera().position().y)
            + " fogType=" + fogType
            + " fogCol=" + String.format("%.3f,%.3f,%.3f", fog.color.x, fog.color.y, fog.color.z)
            + " fogDist=[env " + String.format("%.0f..%.0f", fog.environmentalStart, fog.environmentalEnd)
            + " rd " + String.format("%.0f..%.0f", fog.renderDistanceStart, fog.renderDistanceEnd)
            + " sky " + String.format("%.0f", fog.skyEnd)
            + " cloud " + String.format("%.0f", fog.cloudEnd) + "]"
            + " skybox=" + sky.skybox
            + " skyCol=" + Integer.toHexString(sky.skyColor)
            + " sunriseCol=" + Integer.toHexString(sky.sunriseAndSunsetColor)
            + " starB=" + String.format("%.2f", sky.starBrightness)
            + " darkDisc=" + sky.shouldRenderDarkDisc
            + " cloudCol=" + Integer.toHexString(lrs.cloudColor)
            + " skyRenNull=" + (renderer.skyRenderer() == null)
            + " skyReset=" + lrs.shouldResetSkyRenderer
            + " skyDraws=" + skyDraws + (portalSkyDraws > 0 ? "(+" + portalSkyDraws + "p)" : "")
            + (targetA != 0 ? " skyTgt=" + Integer.toHexString(targetA)
                + (targetB != 0 ? "," + Integer.toHexString(targetB) : "") : "")
            + " probeFog=" + Integer.toHexString(fogLast)
            + (fogLast != fogNew ? "->" + Integer.toHexString(fogNew) : "")
            + " probeSky=" + Integer.toHexString(skyLast)
            + (skyLast != skyNew ? "->" + Integer.toHexString(skyNew) : "")
            + " rainMult=" + String.format("%.3f", rainFogMult)
            + " ovr=" + MyGameRenderer.vanillaTerrainSetupOverride
            + " applyF=" + RenderChainProbe.applyFrustumCount
            + " visSec=" + renderer.visibleSections().size()
            + " compQ=" + (dispatcher == null ? -1 : dispatcher.getCompileQueueSize())
            + (promoteNanos > 0 ? " pMs=" + String.format("%.2f", promoteNanos / 1.0e6) : "")
            + (discoveryNanos > 0 ? " dMs=" + String.format("%.2f", discoveryNanos / 1.0e6) : "")
            + (vanillaYield >= 0 ? " vy=" + vanillaYield : "")
            + (destPasses > 0 ? " dp=" + destPasses : "")
            + (destPassNanos > 0 ? " dpMs=" + String.format("%.2f", destPassNanos / 1.0e6) : "")
            + (foldM + foldD > 0 ? " fs=" + foldM + "+" + foldD : "")
            + (sdeP > 0 || sdeT > 0
                ? " sde=" + sdeP + ":" + sdeL + ":" + sdeE + ":" + sdeS + ":" + sdeT : "")
            + (rcLogged ? " rcLog" : "");
    }

    /** One log write for the whole ring, oldest row first (the DrawCallTrace dump discipline). */
    private static void dump() {
        try {
            StringBuilder sb = new StringBuilder(1 << 15);
            sb.append("==== TELEPORT-FLASH CAPTURE (").append(RING_SIZE)
                .append("-frame ring; markers inline; schema: probeFog/probeSky = attributeProbe")
                .append(" lastValue->newValue, lag shows as a->b converging over ~1 tick) ====");
            for (int i = 0; i < RING_SIZE; i++) {
                String row = ring[(ringWrite + i) % RING_SIZE];
                if (row != null) {
                    sb.append('\n').append(row);
                }
            }
            sb.append("\n==== END FLASH CAPTURE ====");
            LOGGER.info("{}", sb);
        }
        catch (Throwable t) {
            LOGGER.info("flash-probe dump failed: {}", t.toString());
        }
    }
}
