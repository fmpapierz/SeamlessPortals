package qouteall.imm_ptl.core;

import com.google.gson.Gson;
import me.shedaniel.autoconfig.ConfigHolder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import com.warwa.seamlessportals.event.Event;
import net.minecraft.server.MinecraftServer;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.MiscHelper;
import qouteall.q_misc_util.my_util.MyTaskList;

import java.util.function.Consumer;

public class IPGlobal {
    
    public static ConfigHolder<IPConfig> configHolder;
    
    public static int maxNormalPortalRadius = 32;
    
    
    /**
     * This is different to {@link ClientTickEvents#END_CLIENT_TICK}
     * It fires right after ticking client world, which is earlier than the Fabric event.
     */
    public static final Event<Runnable> POST_CLIENT_TICK_EVENT = Helper.createRunnableEvent();
    
    public static final Event<Runnable> PRE_GAME_RENDER_EVENT = Helper.createRunnableEvent();
    
    // executed after ticking. will be cleared when client encounter loading screen
    public static final MyTaskList CLIENT_TASK_LIST = new MyTaskList();
    
    // won't be cleared
    public static final MyTaskList PRE_GAME_RENDER_TASK_LIST = new MyTaskList();
    public static final MyTaskList PRE_TOTAL_RENDER_TASK_LIST = new MyTaskList();
    
    public static final Event<Consumer<MinecraftServer>> SERVER_CLEANUP_EVENT =
        Helper.createConsumerEvent();
    
    public static final Gson gson = MiscHelper.gson;
    
    public static int maxPortalLayer = 5;
    
    public static int indirectLoadingRadiusCap = 8;
    
    public static boolean lagAttackProof = true;
    
    public static RenderMode renderMode = RenderMode.normal;

    // ===== IS1 — the iris shaders-ON compat renderer activation (design §0.4-11) ================
    // Lives QOUTEALL-SIDE (the IPGlobal family), deliberately NOT in ExperimentalCompatGate
    // (which S20 deletes per decision C2-5(b)) — the S20 sweep cannot strand it.
    // IS4 Q-U1 DEFAULT-FLIP (user-decided 2026-07-20, after IS3 live-proved clipping): DEFAULT
    // TRUE. A shaderpack user now gets real portal views by default. The flip is SHADERS-GATED at
    // the routing site (isShaderpackPortalViewsActive below): the flag path activates ONLY when a
    // shaderpack is actually running, so shaders-OFF / no-pack / plain users fall through to the
    // proven stencil renderer UNCHANGED — the flip's blast radius is exactly "shaderpack-ON users",
    // nothing wider (the recon §4.5 over-reach the naive flip would have caused is foreclosed).
    // renderMode=none remains the master off-switch. experimentalShaderpackPortalViews=false is a
    // CODE-LEVEL opt-out ONLY — this field is NOT bound to IPConfig / IPConfigGUI (grep-confirmed:
    // it lives solely in IPGlobal), so it is neither serialized nor exposed in the config screen; a
    // real user cannot toggle it at runtime (their only fall-back off this renderer is
    // renderMode=none, which disables ALL portal rendering). Consumed by
    // PortalRenderer.switchToCorrectRenderer (the D8-EVO routing).
    public static boolean experimentalShaderpackPortalViews = true;

    /** The IS1 JVM lever (read once at class-init; wired via gradle -PshaderpackViews=true).
     *  Forces the compat renderer for BOTH shader states — the dev proof rows (IS1/IS2/IS3 sans
     *  a pack). Independent of the shaders gate below. */
    public static final boolean SHADERPACK_VIEWS_JVM_LEVER =
        Boolean.getBoolean("seamlessportals.shaderpackViews");

    /** True when the iris shaders-ON compat renderer is armed at all (config flag OR JVM lever).
     *  Retained for docs / the "is the feature enabled" question; has NO live caller after the IS4
     *  Q-U1 flip (renderer SELECTION moved to the shaders-gated method below).
     *  CAUTION: never wire renderer selection to this method — it OMITS the shadersActive gate, so
     *  at the default-TRUE flag it returns true for shaders-OFF / no-pack / plain users too and
     *  would reintroduce the recon §4.5 over-reach (routing everyone off the stencil renderer).
     *  Routing MUST use {@link #isShaderpackPortalViewsActive(boolean)}. */
    public static boolean isShaderpackPortalViewsArmed() {
        return experimentalShaderpackPortalViews || SHADERPACK_VIEWS_JVM_LEVER;
    }

    /**
     * IS4 Q-U1: whether the shaders-ON compat renderer should be SELECTED this frame. The JVM
     * lever forces it regardless of shader state (dev proof rows); the config flag activates it
     * only when a shaderpack is actually active ({@code shadersActive}), so the default-ON flag
     * NEVER pulls shaders-OFF / no-pack / plain users off the stencil renderer. renderMode=none is
     * gated separately at the call site.
     */
    public static boolean isShaderpackPortalViewsActive(boolean shadersActive) {
        return SHADERPACK_VIEWS_JVM_LEVER || (experimentalShaderpackPortalViews && shadersActive);
    }

    // ===== IS5 shadow-sync fix — RETIRED TOMBSTONE (2026-07-23) ====================================
    // The old theory ("sodium async shadow cull lags rotation; force getShouldRenderSync sync") was
    // WRONG about the wave: the getShouldRenderSync mixin was LIVE-PROVEN DEAD
    // (forcedSyncSinceLastCapture=0 every window — iris short-circuits before that callsite), and the
    // theory-restart identified the real causes (the clip leak = the wash; the prev-camera tracker = the
    // ghost). The mixin + lever + counter are deleted; this tombstone prevents a naive re-add.

    // C2-1 SAME-DIM FLASH FIX (2026-07-21; root-caused via the user's git bisect + the deep-Opus panel to
    // commit 712f595's D1 shared-RSM design). The shaders-OFF/ON same-dim flicker (SOURCE terrain beyond
    // the standing chunk vanishes on pan/move, only with a SAME-DIM portal in view; cross-dim never
    // flashes) is the shared per-region DRAW-COMMAND cache: RenderRegion.cachedBatches
    // (Map<TerrainRenderPass,MultiDrawBatch>, keyed by pass ONLY, gated by MultiDrawBatch.isFilled) is NOT
    // in the D1 swap set, and MixinSodiumRenderRegion previously isolated ONLY the ChunkRenderList (its
    // per-layer list is `new ChunkRenderList(this)` on the SAME region -> resolves to the SAME batch). The
    // same-dim portal terrain draw fills that shared batch with its narrower through-portal subset
    // (isFilled=true); the later main draw finds isFilled==true, SKIPS its refill, and draws the portal
    // subset -> every main-visible-but-not-portal-visible section is absent from the reused batch and
    // VANISHES (count-stable, DRAW-not-cull, same-dim-only, first appears at C2-1 -> uniquely fits every
    // symptom; verified to sodium 0.9.1 bytecode). Fix = the MISSING HALF of the #3 isolation: give each
    // portal recursion layer its OWN per-region MultiDrawBatch (MixinSodiumRenderRegion.getCachedBatch
    // redirect), so the portal draw never touches the region-own (main) batch. ORDER-INDEPENDENT (the two
    // batches are simply never the same instance -> no reliance on portal-fills-first or main-self-heals,
    // the concern all three judges flagged), covers shaders-OFF + shaders-ON (both route terrain through
    // the shared-RSM drive/arm), and is a no-op for cross-dim (separate per-dim RSM/regions). DEFAULT TRUE
    // (defect fix on the sodium-compat path); A/B OFF via -Dseamlessportals.disablePortalBatchIsolation.
    public static final boolean PORTAL_BATCH_ISOLATION_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disablePortalBatchIsolation");
    public static boolean portalBatchIsolation = true;

    /** True when each portal recursion layer should own a distinct per-region MultiDrawBatch (the
     *  batch-analog of the #3 per-layer ChunkRenderList isolation). Default-on; the JVM lever forces it OFF
     *  for A/B comparison. Behavior is identical to vanilla sodium outside portal rendering. */
    public static boolean isPortalBatchIsolationActive() {
        return portalBatchIsolation && !PORTAL_BATCH_ISOLATION_DISABLED_LEVER;
    }

    /** Confirm-counter: incremented by MixinSodiumRenderRegion each time getCachedBatch is redirected to a
     *  per-portal-layer batch. A self-run round can read it to prove the isolation fires during same-dim
     *  portal terrain draws. Render-thread-only plain int; no atomic needed. */
    public static int portalBatchIsolationRedirectCount = 0;

    // IS5-P IRIS TEMPORAL-TARGET GUARD (2026-07-22) — the shaders-ON whole-screen PHANTOM fix. The compat
    // renderer runs the dest world through iris's full deferred pipeline, polluting iris's PERSISTENT
    // clear=false color targets (Complementary's colortex2 = TAA history, etc.); the mainRT-only blit-back
    // never restores those, so the next frame's TAA reprojects the dest terrain into view = the faint
    // whole-screen ghost (LIVE-CONFIRMED: TAA-off kills it). Fix = IrisTemporalTargetGuard save/restores
    // iris's clear=false color targets (both ping-pong textures) around the per-portal dest render, so the
    // dest pollution is undone in iris's own buffers while the MAIN view's TAA history survives byte-identical.
    // DEFAULT TRUE (defect fix on the experimental shaderpack-portal path); A/B OFF via
    // -Dseamlessportals.disableIrisTemporalGuard. Same-dim-scoped implicitly (touches only the main pipeline's
    // targets; cross-dim uses a separate per-dim pipeline). Byte-identical when off (save() early-returns).
    public static final boolean IRIS_TEMPORAL_GUARD_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableIrisTemporalGuard");
    public static boolean irisTemporalGuard = true;

    /** True when the iris temporal-target guard should save/restore iris's clear=false color targets around
     *  the portal dest render (the phantom fix). Default-on; the JVM lever forces it OFF for A/B comparison. */
    public static boolean isIrisTemporalGuardActive() {
        return irisTemporalGuard && !IRIS_TEMPORAL_GUARD_DISABLED_LEVER;
    }

    /** Confirm-counter: incremented by IrisTemporalTargetGuard per texture saved (2 per clear=false target).
     *  A self-run round can read it to prove the guard fired during shaders-ON portal frames. Render-thread int. */
    public static int irisTemporalGuardCopyCount = 0;

    // IS5-G DEST-PASS TAA-HISTORY CLEAR (2026-07-23) — the "ghost terrain" fix (the wave's SECOND component,
    // unmasked once the wash died). Live-proven carrier (user toggle chain: stamp exonerated by the magenta
    // lever; Real-Time Shadows OFF -> persists; SSAO OFF -> persists; Temporal Filtering OFF -> GONE): the
    // dest pass's TAA composite READS the main view's persistent history (which the IS5-P guard rightly
    // preserves) as its own -> SOURCE-world surfaces blended over the dest terrain, reprojected against the
    // mismatched camera = the moving surface-shaped ghost. Fix = IrisTemporalTargetGuard.clearForDestPass():
    // per-portal glClearTexImage of the guard-saved clear=false targets to ZERO before the nested dest render
    // (Complementary's taa.glsl black-history early-out then returns the pure current frame — no blend, no
    // darkening); the guard's restore() returns the main history byte-whole. Composed on the guard lever so
    // the clear can never run unguarded. DEFAULT TRUE; A/B OFF via -Dseamlessportals.disableIrisDestTaaClear.
    public static final boolean IRIS_DEST_TAA_CLEAR_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableIrisDestTaaClear");
    public static boolean irisDestTaaClear = true;

    /** True when the per-portal dest render should neutralize (zero) the guard-saved TAA/temporal history
     *  before rendering (the ghost fix). Composes on the IS5-P guard: guard off => clear off. */
    public static boolean isIrisDestTaaClearActive() {
        return irisDestTaaClear && !IRIS_DEST_TAA_CLEAR_DISABLED_LEVER && isIrisTemporalGuardActive();
    }

    /** Confirm-counter: incremented per texture cleared (2 per saved target, per portal, per frame).
     *  Expected with Complementary sans Voxy: 10/portal/frame (5 saved targets). Render-thread int. */
    public static int irisDestTaaClearCount = 0;

    // IS5-PH PREV-UNIFORM HEAL (2026-07-23 late) — the GHOST-TERRAIN root fix (ghost panel wf_98e3a1ee-634,
    // unanimous javap+GLSL-exact). The nested dest render ticks iris's FrameUpdateNotifier (no re-entrancy
    // guard in iris) with the DEST camera → the next MAIN frame uploads previousCameraPosition = destCameraPos
    // → the pack's TAA REPROJECTION displaces the (byte-correct) history by the portal offset → source-shading
    // painted over terrain, camera-tracked (the ghost). History clears provably could not fix it (carrier =
    // uniform state, not texture content); Temporal-Filtering-off kills it (the reprojection is the painter).
    // Fix = ONE onNewFrame() re-tick on the MAIN pipeline after the per-portal loop (camera restored) — the
    // next frame's natural tick then shifts previous←main before any upload. DEFAULT TRUE; A/B OFF via
    // -Dseamlessportals.disablePrevUniformHeal.
    public static final boolean PREV_UNIFORM_HEAL_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disablePrevUniformHeal");
    public static boolean prevUniformHeal = true;

    /** True when the post-portal prev-uniform heal should re-tick iris's frame notifier (the ghost fix).
     *  Default-on; the JVM lever forces it OFF for A/B comparison. */
    public static boolean isPrevUniformHealActive() {
        return prevUniformHeal && !PREV_UNIFORM_HEAL_DISABLED_LEVER;
    }

    // IS5-ACT (2026-07-26): the heal must tick the pipeline the MAIN frame used, captured BEFORE the
    // portal loop — NOT whatever the last nested dest render left in iris's manager slot. Cross-dim
    // dest renders select their own per-dimension pipeline and never restore the slot, so the
    // pre-retarget heal fed the DEST pipeline's CameraPositionTracker the MAIN camera, poisoning
    // previousCameraPosition by ~132 blocks and killing the dest ACT flood-fill. LIVE-MEASURED A/B:
    // heal ACTIVE => dest |posOffset|inf=132, floodfill plateau nz=90; heal DISABLED => <=2 on 89/89
    // and floodfill accumulates to nz=15283. DEFAULT TRUE; A/B OFF via
    // -Dseamlessportals.disableHealRetarget (which restores the old, poisoning target resolution).
    public static final boolean HEAL_RETARGET_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableHealRetarget");

    /** True when the prev-uniform heal should tick the PRE-LOOP captured main pipeline (the fix).
     *  Default-on; the JVM lever forces the old manager-slot resolution back for A/B comparison. */
    public static boolean isHealRetargetActive() {
        return !HEAL_RETARGET_DISABLED_LEVER;
    }

    // IS5-MB PER-DEST PREVIOUS-FRAME CAMERA STATE (2026-07-26) — the same-dim portal-window motion-blur
    // smear fix. MEASURED: at the same-dim dest composite4 the shader reads cameraPosition=dest_N but
    // previousCameraPosition=main_N (|d|=125.82 with the player STATIONARY) => the pack's soft clamp
    // velocity/(1+|velocity|)*STRENGTH saturates => full-strength smear that needs no player motion.
    // Cause: CameraPositionTracker ticks 3x on a portal frame (main BLR, dest BLR, our IS5-PH heal), so
    // the dest pass reads prev=main_N/cur=dest_N. Cross-dim is clean (own pipeline, own tracker) and is
    // EXCLUDED by construction. Fix = write the dest's own previous trio into the guarded composite
    // program between its Program.use() and its draw, then RESTORE iris's values before the next pass.
    // BACK TO DEFAULT-ON 2026-07-26, on a REWRITTEN mechanism. The bracket-keyed predecessor was
    // switched off because it measurably fixed nothing (writes=1243/run, symptom unchanged); the
    // IS5-CEN census then showed WHY: it recorded the dest camera from inside the armed portal bracket,
    // but the bind inside that bracket is the one carrying the MAIN camera, so it "corrected" the
    // already-innocent pass 1243 times and never touched the guilty one. The correction is now keyed on
    // the SLOT (programId, bind ordinal within the frame) and needs no portal context at all.
    // MEASURED defect it fixes: same-dim, stationary, 22 consecutive seconds — the dest content's
    // composite4 reads cam=DEST prev=PLAYER, |cam-prev|=511.088, a 265.8 PIXEL blur span.
    // BACK TO DEFAULT-OFF 2026-07-26 (second live round). The correction did NOT fix the smear and it
    // MADE ONE BIND WORSE: with three chains in view the census measured the same-dim dest bind going
    // from PRE |cam-prev|=152 to POST |cam-prev|=515.679 — i.e. the write pushed a 515-block camera
    // onto it. Two further facts from that run are unexplained and must be settled before this ships
    // enabled again: (1) iris's previousCameraPosition read exactly (0,0,0) on 100 of 100 census rows,
    // where the correction-OFF run had shown real cameras, and (2) the PRE and POST samples of one
    // bind are mutually inconsistent (cameraPosition differs between them), so at least one of the two
    // is mispaired and the instrument is under suspicion alongside the fix.
    // ROUND 4 UPDATE: that "worsened a bind" reading came from a POST sample that was itself mispaired
    // and is RETRACTED. The real defects were (a) the write was clobbered between the seam at bytecode
    // 422 and the draw — the seam moved to 447, past everything — and (b) there are TWO blur drivers,
    // the camera pair AND the matrix pair, and the matrix half had been deleted after generalising
    // from one run where idMV read zero everywhere. Both halves are now written, keyed per chain by
    // NEAREST CAMERA (not by bind ordinal, which was measured unstable).
    // ★ DEFAULT ON as of 2026-07-26, round 4 — USER-CONFIRMED LIVE: "FINALLY NOT BLURRY".
    // Log evidence from that same run: |cam-prev| 721.852 -> 0.001 (blur span 136.3 -> 1.7 px) and
    // 1172.391 -> 0.001 (108.4 -> 1.2 px), while rows where the player was genuinely moving kept their
    // real blur (0.047 -> 0.048, span 35 -> 35) rather than being flattened. That last part is why the
    // correction restores each chain's OWN previous state instead of just suppressing motion blur in
    // the window.
    // The enable lever is REMOVED rather than left in place: with the field defaulting true it could
    // never change the outcome ((true || x) is true), and a lever that silently does nothing is the
    // exact trap that has voided live rounds on this project. A/B OFF via
    // -Dseamlessportals.disableIrisDestPrevCamera.
    public static final boolean IRIS_DEST_PREV_CAMERA_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableIrisDestPrevCamera");
    public static boolean irisDestPrevCamera = true;

    /** True when the guarded composite pass should receive its own chain's previous-frame state. */
    public static boolean isIrisDestPrevCameraActive() {
        return irisDestPrevCamera && !IRIS_DEST_PREV_CAMERA_DISABLED_LEVER;
    }

    /** IS5-MB CHAIN-MATCH LIMIT, in blocks: the furthest a composite chain's camera may plausibly
     *  travel in ONE frame. A bind adopts the nearest camera its program held last frame only within
     *  this radius; beyond it the bind neutralizes (velocity 0, one blur-free frame).
     *  SIZED TO CAMERA MOTION, NOT TO THE DEFECT. 4 blocks/frame is already generous — elytra at
     *  30 m/s is ~1.5 blocks/frame at 20 fps. An earlier 16-block value was chosen merely to be "well
     *  below 511" and was DANGEROUS: it is wider than an ordinary doorway portal's source→destination
     *  offset, so it would have accepted the destination camera as history for the main view's chain
     *  and flashed a full-screen blur on every visibility flap. The limit must stay below the smallest
     *  portal offset worth correcting; it is not a safety margin against the bug, it is the definition
     *  of "same chain". Tune with -Dseamlessportals.irisDestPrevCameraMaxDelta. */
    public static final double IRIS_DEST_PREV_MAX_DELTA = parseMaxDelta();

    private static double parseMaxDelta() {
        String raw = System.getProperty("seamlessportals.irisDestPrevCameraMaxDelta");
        if (raw == null) {
            return 4.0;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            // A non-positive or absurd limit would silently disable the match or make it match
            // anything; refuse both rather than ship a lever that quietly breaks the correction.
            if (v > 0.0 && v <= 64.0) {
                return v;
            }
        }
        catch (NumberFormatException ignored) {
            // fall through to the warning below
        }
        // Must not throw: this runs in IPGlobal's static initializer, and an exception there takes the
        // whole class down at class-load time — a malformed -P value would crash the game at startup.
        System.err.println("[Seamless Portals] IS5-MB: ignoring malformed"
            + " -Dseamlessportals.irisDestPrevCameraMaxDelta=\"" + raw + "\" (want a number in"
            + " (0, 64]); using the default 4.0.");
        return 4.0;
    }

    /** IS5-MB MATRIX HALF, A/B lever — RE-ADDED and now meaningful. It was deleted when the first
     *  census run showed idMV=0.00000 on every sampled row, i.e. the matrix chain always cancelled.
     *  That generalised from one run: a later 56-row sample found idMV between 0.13 and 0.25 on EVERY
     *  row whose camera offset was ~zero, each still blurring 57-102 px. So there are two independent
     *  drivers and the correction writes both halves. Set this to write the CAMERA half only, which
     *  isolates driver A from driver B in one live A/B:
     *  -Dseamlessportals.irisDestPrevCameraNoMatrices */
    public static final boolean IRIS_DEST_PREV_NO_MATRICES =
        Boolean.getBoolean("seamlessportals.irisDestPrevCameraNoMatrices");

    /** Confirm-counter: binds whose nearest last-frame camera was outside the match limit (a portal
     *  coming into view, a teleport, an iris position-shift epoch). These NEUTRALIZE. A steadily
     *  climbing value flag-ON means chains are not being matched and the correction is mostly
     *  neutralizing rather than restoring real motion blur. */
    public static int irisDestPrevUnmatchedCount = 0;

    /** IS5-MB guarded-pass names, comma-separated. Default "composite4" (Complementary's sole
     *  MOTION_BLURRING_STRENGTH consumer). Override only if a pack renames the pass — the once-only
     *  roster line names every pass actually seen, so the value is never guesswork. */
    public static final String IRIS_DEST_PREV_PASSES =
        System.getProperty("seamlessportals.irisDestPrevCameraPass", "composite4");

    /** IS5-MB 1Hz [IS5-MB] counter probe (default OFF): -Dseamlessportals.destPrevCameraProbe */
    public static final boolean DEST_PREV_CAMERA_PROBE =
        Boolean.getBoolean("seamlessportals.destPrevCameraProbe");

    // IS5-MB ATTRIBUTION LEVER (2026-07-26, DIAGNOSTIC — default keeps today's behaviour).
    // The Motion-Blur portal-window blur has every PASS-level velocity input measured at exactly zero
    // (|cam-prev|=0.000, matrix maxAbsDiff=0.00000), yet it scales with MOTION_BLURRING_STRENGTH — so
    // velocity is nonzero PER PIXEL. composite4 derives it from `z = texture2D(depthtex1, texCoord)`,
    // and the portal stamp writes DEST depth into the window region of the MAIN depth buffer (the #13
    // two-portal fix restored that write). Main-chain composite4 then unprojects dest depth with MAIN
    // matrices => garbage viewPos => large velocity for WINDOW PIXELS ONLY. Setting this swaps the
    // stamp to a no-depth-write pipeline: if the blur vanishes, that is the mechanism confirmed.
    // COST while set: re-opens the #13 "second portal paints over the first" artifact. Diagnostic only.
    public static final boolean STAMP_DEPTH_WRITE_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableStampDepthWrite");

    // IS5-SEAM ATTRIBUTION LEVER (2026-07-27, DIAGNOSTIC ONLY — never ship it on).
    // The black flash at the portal seam: -PdebugTintStamp turns the whole window magenta EXCEPT
    // that band (CORRECTED INFERENCE: the tint is a MULTIPLY, blind on black content — this proves
    // no NON-BLACK fragment survives there, not that the stamp misses it; handoff §2c'), and the
    // IS5-SEAM census shows the aperture mesh is never null (meshNull=false 23/23) and never fully
    // dropped (dropped=0 23/23) but IS partially clipped on the approach (clipped=2 on 13/23 rows
    // from ~1.2 blocks in). This lever passes every aperture triangle through UNCLIPPED, so one
    // look settles whether the removed area is the band (it settled it: the clip is INNOCENT —
    // kept=2 clipped=0 dropped=0 on 18/18 and the band survived). COST while set: re-opens the
    // S14.36 sky-wedge artifact the clip exists to prevent — and seeing those wedges is itself
    // proof the lever took effect.
    public static final boolean APERTURE_PLANE_CLIP_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableAperturePlaneClip");

    // IS5-SEAM THE DEPTH-TEST DISCRIMINATOR (2026-07-27, DIAGNOSTIC ONLY — never ship it on).
    // The seam handoff's §2d measurement: the census proves the aperture geometry COVERS the black
    // band (kept=2 clipped=0 dropped=0 on 18/18 with the clip lever) while -PdebugTintStamp shows no
    // fragment PAINTED there — so the stamp's fragments are being rejected (or are painting content
    // that is itself black; see debugStampSolid below, which splits that). This lever swaps the stamp
    // to a sibling pipeline whose depth state is fully DISABLED (Optional.empty(), the shape
    // PORTAL_STRAIGHT_COPY uses): band fills in => the GEQUAL test against the snapshot depth was
    // rejecting the stamp at the seam; band PERSISTS => depth rejection is NOT THE SOLE cause — do
    // NOT exonerate the depth test from this leg alone (this pipeline still paints SAMPLED content,
    // so black dest content keeps the band black regardless of depth) — run the debugStampSolid
    // ladder below, which is what actually separates the branches (handoff §2d matrix).
    // COST while set: GL's depth-test disable also disables depth WRITES, so the #13 two-portal
    // occlusion write is off too, and the stamp ignores real occluders in front of the portal.
    public static final boolean STAMP_DEPTH_TEST_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableStampDepthTest");

    // IS5-SEAM THE SOLID-PAINT DISCRIMINATOR (2026-07-27, DIAGNOSTIC ONLY). The magenta coverage
    // tint (-PdebugTintStamp) is a MULTIPLY in the stamp's fragment shader (tint × sampled): on
    // sampled content that is already pure black the tint is invisible, so "the band did not turn
    // magenta" CANNOT distinguish "no fragment landed" from "fragments landed painting black
    // content". This lever swaps the stamp to a sibling whose fragment shader outputs the vertex
    // color DIRECTLY (solid WHITE; solid MAGENTA when combined with -PdebugTintStamp), ignoring the
    // sample. Band turns solid => fragments pass and survive there, and the black is the SAMPLED
    // dest content (hunt the nested dest render); band stays black => no fragment survives there
    // (depth-rejected, or overpainted after the stamp). Composes with -PdisableStampDepthTest
    // ONLY; combined with -PdisableStampDepthWrite the depth write stays ON (SOLID+NO-WRITE is
    // deliberately not built — the once-only IS5-RC STAMP PIPELINE line says so).
    public static final boolean debugStampSolid =
        Boolean.getBoolean("seamlessportals.debugStampSolid");

    // IS5-SEAM THE CROSSING-WINDOW CLIP RELAX — **DEFAULT ON** (2026-07-27). The black seam band
    // was measured end-to-end: solid-stamp leg => the sampled dest frame is black at the band;
    // front_clipping live A/B both directions => the IS3 inner clip carries it; IS5-SEAM-ARM
    // census => feed coherent (maxAbsFeedErr=0.0000, 95/95) and the band frames are the crossing
    // window (fullyVoid=6, nearStraddle=38 across three crossings) — the clip WORKING AS DESIGNED
    // voids grazing aperture rays while the render eye is within near-reach of the plane. IP has
    // identical geometry but fills those pixels with UNCLIPPED sky; deferred packs have no filler
    // => pure black, shaders-ON only. The fix ramps the inner clip camera-side inside the
    // crossing window (FrontClipping.innerClipCorrectionForCrossing — derivation and constants
    // there); far from the portal it is bit-identical to IP's -ADJUSTMENT. Scope: the compat
    // full-pipeline dest arm only (the measured defect site; shaders-OFF keeps its sky filler).
    // Pass this to force the relax OFF and reproduce the band (attribution both directions) —
    public static final boolean SEAM_CLIP_RELAX_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableSeamClipRelax");

    // IS5-SEAM-CONTENT band-content probe (DIAGNOSTIC, default OFF). Declared here only so the
    // IS5-RC run-config block reports it beside every other lever (the IrisCompositeCensus
    // precedent); the probe reads its own system property (SeamDestContentProbe.ENABLED).
    public static final boolean SEAM_CONTENT_PROBE =
        Boolean.getBoolean("seamlessportals.seamContentProbe");

    // IS5-CEN THE PER-FRAME COMPOSITE BIND CENSUS (2026-07-26, DIAGNOSTIC, default OFF):
    // -Dseamlessportals.compositeCensus (+ -Dseamlessportals.compositeCensusPasses to rename the deep
    // pass). Declared here only so the IS5-RC run-config block reports it beside every other lever;
    // the census reads its own system properties directly (see IrisCompositeCensus).
    public static final boolean COMPOSITE_CENSUS_PROBE =
        Boolean.getBoolean("seamlessportals.compositeCensus");
    public static final String COMPOSITE_CENSUS_PASSES =
        System.getProperty("seamlessportals.compositeCensusPasses", "composite4");

    public static int irisDestPrevWriteCount = 0;
    public static int irisDestPrevNeutralizeCount = 0;

    /** Confirm-counter: incremented once per healed frame. Render-thread int. */
    public static int prevUniformHealCount = 0;

    // IS5-FF — NESTED SHADOWCOMP SUPPRESSION (2026-07-25; §2h recon HIGH + two user live toggles:
    // ACT OFF kills the phantom, ACT ON + WSR OFF keeps it). Complementary Ultra's COLORED_LIGHTING
    // flood-fill (shadowcomp COMPUTE, persistent clear=false floodfill_img ping-pong) is re-dispatched
    // by the compat route's nested same-dim dest renderShadows on the SAME pipeline with an unchanged
    // framemod2 => the nested run FULLY OVERWRITES the frame's write side with DEST-seeded light
    // (lava seed ~160/alpha 0.8 bypasses the vanilla-lightmap gate ~41x) => dest lava light painted
    // over the SOURCE world. Fix = frame-scoped reflective swap of the MAIN pipeline's
    // ShadowRenderer.compositeRenderer (javap: sole consumer = renderShadows, unconditional
    // getfield@1402) to a stateless no-op subclass at the compat anchor; restored in the finally.
    // Save/restore was cost-blocked (1 GiB @ Ultra). Cross-dim = separate pipeline, untouched.
    // DEFAULT TRUE; A/B OFF via -Dseamlessportals.disableNestedShadowComposite.
    public static final boolean NESTED_SHADOW_COMPOSITE_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableNestedShadowComposite");
    public static boolean nestedShadowCompositeSuppress = true;

    /** True when the compat anchor should no-op the nested dest passes' shadowcomp dispatch
     *  (the Ultra colored-light phantom fix). Default-on; the JVM lever forces OFF for A/B. */
    public static boolean isNestedShadowCompositeSuppressActive() {
        return nestedShadowCompositeSuppress && !NESTED_SHADOW_COMPOSITE_DISABLED_LEVER;
    }

    /** Confirm-counters: suppressCount = swap-installed anchor frames (fires even on compat
     *  frames with zero portals in view — the anchor brackets unconditionally, same as the
     *  temporal guard); noopHits = nested renderShadows that landed on the no-op (k per frame
     *  with k same-dim portals IN VIEW). installs>0 with noopHits==0 is NORMAL on portal-less
     *  frames; during a portal-on-screen scene it means nested passes are not reaching
     *  renderShadows (diagnostic). Render-thread ints. */
    public static int nestedShadowCompositeSuppressCount = 0;
    public static int nestedShadowCompositeNoopHits = 0;

    /** IS5-FF 1Hz [IS5-FF] probe (default OFF): -Dseamlessportals.nestedShadowCompositeProbe. */
    public static final boolean NESTED_SHADOW_COMPOSITE_PROBE =
        Boolean.getBoolean("seamlessportals.nestedShadowCompositeProbe");

    // IS5-L IN-PORTAL FULLBRIGHT FIX (2026-07-22) — the shaders-ON dest terrain (seen through a same-dim portal)
    // is lit with the MAIN camera's PER_FRAME lighting uniforms, giving a main-camera direction-dependent
    // fullbright that toggles on pan. Bytecode-confirmed root: iris re-uploads per-frame lighting uniforms only
    // when SystemTimeUniforms.COUNTER advanced since a program last bound; the counter advances once/frame at
    // GameRenderer.render HEAD, and the nested dest render (a re-entrant LevelRenderer.render) never re-advances
    // it, so the reused same-dim programs (lastFrame==COUNTER from the main pass) SKIP the perFrame upload for
    // dest draws. Fix = bump COUNTER before+after the dest render (IrisInterface.bumpPerFrameUniformCounter) so
    // ProgramUniforms.update() re-runs the perFrame stage, re-reading the already-dest-primed sources (iris sets
    // dest gbufferModelView at render() HEAD; the camera is dest-swapped). IP precedent: ExperimentalIrisPortal\
    // Renderer did exactly this; the live full-pipeline path dropped it. DEFAULT TRUE; A/B OFF via
    // -Dseamlessportals.disableIrisPerFrameRefresh. Same-dim/shaders-on-scoped (only the compat dest path bumps).
    public static final boolean IRIS_PER_FRAME_REFRESH_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableIrisPerFrameRefresh");
    // IS5 RETIREMENT (2026-07-23, ghost panel wf_98e3a1ee-634 Fix B): DEFAULT flipped true->FALSE. The bump
    // never fixed the fullbright (live-proven ineffective, probe v1 A/B), and the panel proved it ENABLES the
    // leg-B poisoning (extra COUNTER advance makes the dest pass re-upload gbufferPrevious* MATRICES from
    // dest-framed state into the next main frame's TAA reprojection). The wash was the clip leak (Fix 1); the
    // ghost was the prev-camera tracker (IS5-PH heal). Kept as opt-in dead code pending a full removal pass.
    public static boolean irisPerFrameRefresh = false;

    /** True when the nested dest pass should force iris to re-upload its per-frame lighting uniforms (the
     *  in-portal fullbright fix). Default-on; the JVM lever forces it OFF for A/B comparison. */
    public static boolean isIrisPerFrameRefreshActive() {
        return irisPerFrameRefresh && !IRIS_PER_FRAME_REFRESH_DISABLED_LEVER;
    }

    // IS5-W FIX 1 — SHADOW-SCOPE CLIP SUPPRESSION (2026-07-23; theory-restart wf_2d369c84-97c + the
    // ShadowAliasProbe run + fix panel wf_a2d7890c-115, 3/3 SOUND-WITH-FIXES). THE WASH CARRIER: the
    // portal front-clip plane is armed in CAMERA view space for the whole nested dest render, and the
    // IS3 clip inject patched iris's SODIUM SHADOW terrain programs too (live-log-confirmed:
    // shadow_sodium_terrain_solid/_cutout/_translucent clip-patched, loc>=0). Both clip uploaders fire
    // during the dest SHADOW pass with the armed camera-space plane, which the shadow program evaluates
    // against the SUN's model-view — a wrong-space half-space whose effective offset swings ~+/-100
    // blocks with yaw/pitch, clipping the dest shadow casters out of the 2048^2 map AT RASTER STAGE
    // (invisible to every batch/list counter: #587 = 254 sections collected+filled+drawn, 0/2304 texels).
    // Empty map -> deferred shading finds no occluders -> the yaw-keyed brightness wash on terrain that
    // renders fine (entity shadows survive: Patch.VANILLA programs un-injected). Fix = when
    // isRenderingShadowMap(), both uploaders upload keep-all {0,0,0,1} instead of the armed plane (and
    // skip the enable re-assert) — the shadow pass draws unclipped casters; the CAMERA pass keeps its
    // correct clip. DEFAULT TRUE; A/B OFF via -Dseamlessportals.disableShadowScopeClipFix.
    public static final boolean SHADOW_SCOPE_CLIP_FIX_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableShadowScopeClipFix");
    public static boolean shadowScopeClipFix = true;

    /** True when the clip uploaders should suppress the portal front-clip plane (upload keep-all) for
     *  draws inside an iris SHADOW pass (the wash fix). Default-on; the JVM lever forces it OFF. */
    public static boolean isShadowScopeClipFixActive() {
        return shadowScopeClipFix && !SHADOW_SCOPE_CLIP_FIX_DISABLED_LEVER;
    }

    /** Confirm-counters (render-thread ints; ShadowAliasProbe reads + resets per capture): suppressed =
     *  shadow-scope armed uploads replaced by keep-all (fix ON — must be nonzero while a portal is on
     *  screen under shaders); armed = shadow-scope armed uploads that went through UNSUPPRESSED (fix
     *  OFF A/B leg — must be nonzero there, proving the wash carrier fires). */
    public static int shadowScopeClipSuppressedCount = 0;
    public static int shadowScopeClipArmedUploadCount = 0;

    // IS5-W FIX 2+3 — SHADOW/CAMERA SCOPE SPLIT + PER-UPLOAD BATCH CLEAR (same panel). Leg B: the C2-1
    // per-portal-layer isolation keys ONLY on (isRendering, layer), bypassing iris's shadow/regular
    // PHYSICAL FIELD SWAP on RenderRegion — inside the portal pass the dest SHADOW and dest CAMERA
    // scopes share ONE layer ChunkRenderList + ONE layer MultiDrawBatch per region (probe-proven live:
    // camera freshFill=0 with reuseCross>0 in 50/50 passes; camera collect never ran while the shadow
    // collect rewrote the shared lists). Benign today only because the shadow selection happens to
    // superset the view frustum — latent correctness. Fix 2 = split the layer key by scope:
    // index = 2*(layer-1) + (isRenderingShadowMap() ? 1 : 0) in BOTH structures (PortalScopeKey).
    // Leg d: iris @Redirects RenderRegion.clearAllCachedBatches at uploadResults to
    // iris$forceClearAllBatches, so the mod's HOOK-2 upload-clear mirror NEVER fires under iris — layer
    // batches miss per-upload invalidation (stale draw commands after mesh re-uploads). Fix 3 =
    // MixinSodiumRenderRegionManager_PortalBatchClear clears the layer batches at the REAL uploadResults
    // RETURN (orthogonal to iris's redirect). Both share this lever (A/B ambiguity accepted + noted).
    // DEFAULT TRUE; A/B OFF via -Dseamlessportals.disableShadowScopeIsolation.
    public static final boolean SHADOW_SCOPE_ISOLATION_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableShadowScopeIsolation");
    public static boolean shadowScopeIsolation = true;

    /** True when each portal layer's ChunkRenderList/MultiDrawBatch key splits by shadow-vs-camera scope
     *  (fix 2) and the per-upload layer-batch clear runs (fix 3). Default-on; the JVM lever forces both
     *  OFF together for A/B (lever-off = exactly the pre-fix (layer-1) key + no upload clear). */
    public static boolean isShadowScopeIsolationActive() {
        return shadowScopeIsolation && !SHADOW_SCOPE_ISOLATION_DISABLED_LEVER;
    }

    // §2b COMPAT SAME-DIM ENTITY FILL (2026-07-24) — the shaders-ON same-dim dest-entity fix. The
    // compat full-pipeline route's nested render() drains the SHARED main LRS whose
    // entityRenderStates the main pass already submitted+cleared (26.2 LevelRenderer.java:282),
    // and the route never calls the decomposed renderPortalEntitiesSameDim — so same-dim
    // shaderpack windows drew NO entities ([ENT-PROBE] dp>0 sub=0, live 2026-07-24). Fix =
    // Step-9.5-SD in SecondaryWorldRenderCore.renderDestWorldFullPipeline: a portal-camera
    // isolated extract (the proven same-dim discipline) INTO the shared LRS right before the
    // nested render, so its own submitFeatures draws them through iris's gbuffers entity phase
    // (pack-shaded). Also forecloses the nested re-submit of the MAIN pass's still-resident
    // particle groups at the dest camera (submitFeatures never clears particlesRenderState).
    // DEFAULT TRUE; A/B OFF via -Dseamlessportals.disableCompatSameDimEntities.
    public static final boolean COMPAT_SAMEDIM_ENTITIES_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableCompatSameDimEntities");
    public static boolean compatSameDimEntities = true;

    /** True when the compat full-pipeline route should pre-fill the shared LRS with a
     *  portal-camera entity/BE/particle extract for same-dim passes. Default-on; the JVM lever
     *  forces OFF for A/B comparison. */
    public static boolean isCompatSameDimEntitiesActive() {
        return compatSameDimEntities && !COMPAT_SAMEDIM_ENTITIES_DISABLED_LEVER;
    }

    /** Confirm-counter: incremented per successful Step-9.5-SD fill (per same-dim portal pass per
     *  frame). Render-thread int; readable by self-run rounds beside the [ENT-PROBE] fsd route. */
    public static int compatSameDimEntityFillCount = 0;

    // §2c SOURCE-PARTICLE CULL (2026-07-25) — the shaders-OFF stencil-route particle-bleed fix.
    // Mechanism (recon-proven): under Fabulous/Improved Transparency, translucent particles draw
    // into the SEPARATE framegraph particles target whose depth was copied BEFORE the portal
    // window drew — behind-plane SOURCE particles pass the stale depth test and the transparency
    // composite paints them over the window (iris force-disables Fabulous, hence shaders-ON never
    // showed it). Fix = run the geometric cull for the flag-ON MAIN extract (QuadParticleGroupMixin
    // D3-gate amendment) with the roster REPOINTED to the IP Portal ENTITIES (+ globals) via
    // IPMcHelper.getNearbyPortals + Portal.rayTrace (the block-era PortalManager tracker is EMPTY
    // flag-ON — final-diff verify catch). DEFAULT TRUE; A/B OFF via
    // -Dseamlessportals.disableSourceParticleCull.
    public static final boolean SOURCE_PARTICLE_CULL_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableSourceParticleCull");
    public static boolean sourceParticleCull = true;

    /** True when flag-ON main extracts geometrically cull behind-portal source particles.
     *  Default-on; the JVM lever forces OFF for A/B (lever-off = the old bleed returns). */
    public static boolean isSourceParticleCullActive() {
        return sourceParticleCull && !SOURCE_PARTICLE_CULL_DISABLED_LEVER;
    }

    /** Confirm-counter: behind-portal source particles culled (render-thread int; [ENT-PROBE]
     *  reads+resets it into the spc= field when the probe is armed). */
    public static int sourceParticleCullCount = 0;

    // IS-BOB IRIS BOB-SYNC (2026-07-25) — the shaders-ON relative-bob fix (world bobs, window
    // static). Iris relocates view-bob+spin projection->modelview on the MAIN render only; the
    // compat route's nested dest render got neither. Fix = derive the main pose per frame
    // (bobbedMV·V⁻¹, zero iris reach-in), relocation-discriminated (NOT isShaders-gated — the
    // discriminator is causally locked to what iris DID this frame, absorbing every toggle
    // window), pre-multiplied per portal onto the compat full-pipeline dest modelview + clip
    // plane (the FrontClipping planeW term). DEFAULT TRUE; A/B OFF via
    // -Dseamlessportals.disableIrisBobSync.
    public static final boolean IRIS_BOB_SYNC_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableIrisBobSync");
    public static boolean irisBobSync = true;

    /** True when the compat route applies the derived main-pass bob pose to dest draws. */
    public static boolean isIrisBobSyncActive() {
        return irisBobSync && !IRIS_BOB_SYNC_DISABLED_LEVER;
    }

    /** Confirm-counter: per-portal pose applies (render-thread int; the [BOB-SYNC] probe reads it). */
    public static int irisBobSyncApplyCount = 0;

    /** 1Hz [BOB-SYNC] probe (default OFF): -Dseamlessportals.bobSyncProbe. */
    public static final boolean BOB_SYNC_PROBE = Boolean.getBoolean("seamlessportals.bobSyncProbe");

    /** IS5-G GHOST-WAVE DISCRIMINATOR (ghost panel wf_ac30cdc3-265): tint the IrisCompatPaste portal-area
     *  STAMP magenta (channel-killing {1,0,1} in the fragment multiply) so a live run settles whether the
     *  "phantom colored-blocks terrain" wave IS the stamp's own paint overreaching the aperture (ghost
     *  turns magenta => B3 confirmed) vs the snapshot/other carrier (window magenta, ghost full-color).
     *  DEFAULT-OFF, byte-identical (the white Vec3 is the identity multiply). Restart-bound sysprop. */
    public static final boolean debugTintStamp = Boolean.getBoolean("seamlessportals.debugTintStamp");

    // §2g PORTAL-TICKET DESPAWN SUPPRESS (2026-07-25) — the portal mob-despawn fix
    // (user-requested deviation-from-IP; upstream has the identical bug). Mod tickets hold
    // dest chunks ENTITY_TICKING (per-chunk level 31); ServerLevel:425 runs checkDespawn for
    // every entityTickList member UNGATED => portal-held mobs >128 from every player DISCARD
    // permanently, both crossing directions. Fix = MobDespawnSuppressMixin @WrapOperation on
    // BOTH removeWhenFarAway call sites in checkDespawn: return false (the vanilla passive
    // value) iff player > despawnDistance AND the mob's chunk is PORTAL-FED in our ticket
    // bookkeeping (ImmPtlChunkTickets.isChunkPortalFedNear — NOT raw membership: the player's
    // own view square is also in the map, verify-fold FIX-1). Restores the no-portal-mod
    // outcome (UNLOADED-not-DISCARDED); near-player behavior byte-vanilla. SERVER-thread.
    // DEFAULT TRUE; A/B OFF via -Dseamlessportals.disableTicketDespawnSuppress.
    public static final boolean TICKET_DESPAWN_SUPPRESS_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableTicketDespawnSuppress");
    public static boolean ticketDespawnSuppress = true;

    /** True when portal-ticket-held far mobs are exempt from the two distance-despawn legs.
     *  Default-on; the JVM lever forces OFF for A/B (lever-off = crossing-despawn returns). */
    public static boolean isTicketDespawnSuppressActive() {
        return ticketDespawnSuppress && !TICKET_DESPAWN_SUPPRESS_DISABLED_LEVER;
    }

    /** Confirm-counter: suppressed would-be despawn verdicts (SERVER-thread int, single
     *  writer — checkDespawn runs only on the server thread; [DESPAWN-PROBE] snapshots it). */
    public static int ticketDespawnSuppressCount = 0;

    /** §2g Probe A (default OFF): -Dseamlessportals.despawnProbe — event-rate [DESPAWN-PROBE]
     *  lines for server-side Mob DISCARDED / UNLOADED_TO_CHUNK removals (EntityMixin). */
    public static final boolean DESPAWN_PROBE = Boolean.getBoolean("seamlessportals.despawnProbe");

    // C3-BLOOM APERTURE MASK (2026-07-25) — the §2f dark-environment bloom RING fix (user toggle-proven:
    // Bloom OFF => ring GONE). The dest pass's own thresholdless bloom tiles (reach ±896px, BLOOM_FOG
    // ×3 night/×14 cave) deposit energy from bright dest content just OUTSIDE the window rectangle onto
    // pixels just INSIDE it. Fix = IrisBloomApertureMask: during the nested dest composite chain only,
    // mask colortex0 to the aperture footprint AFTER its last writer and BEFORE the bloom-tile gather,
    // so bloom is computed from window-visible content only; the frame stays pack-tonemapped; the stamp
    // is untouched. DEFAULT TRUE; A/B OFF via -Dseamlessportals.disableIrisBloomApertureMask.
    public static final boolean IRIS_BLOOM_MASK_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableIrisBloomApertureMask");
    public static boolean irisBloomApertureMask = true;

    /** True when the per-portal dest composite chain should aperture-mask colortex0 before the bloom
     *  gather (the §2f ring fix). Default-on; the JVM lever forces it OFF for A/B comparison. */
    public static boolean isIrisBloomApertureMaskActive() {
        return irisBloomApertureMask && !IRIS_BLOOM_MASK_DISABLED_LEVER;
    }

    /** Confirm-counter: incremented once per successful mask (per portal per frame). Render-thread int. */
    public static int irisBloomMaskCount = 0;
    /** Miss-counter: armed-but-never-consumed portal windows (dormant mixin / ineligible pack / disarm). */
    public static int irisBloomMaskMissCount = 0;

    /** §2f probes: magenta-tint the aperture repaint (flip-side runtime confirm — a tinted WINDOW proves
     *  the masked texture is the one the chain consumes) / skip the repaint (footprint + crop proof). */
    public static final boolean debugTintBloomMask = Boolean.getBoolean("seamlessportals.debugTintBloomMask");
    public static final boolean debugBloomMaskBlackout = Boolean.getBoolean("seamlessportals.debugBloomMaskBlackout");

    /** C3-BLOOM 1Hz [C3-BLOOM] counter probe (default OFF): -Dseamlessportals.bloomMaskProbe —
     *  "masks=<count> misses=<missCount>" from the mask path (verifier FIX3: without it the
     *  live protocol's counter-climbing leg is unjudgeable from the log). */
    public static final boolean BLOOM_MASK_PROBE = Boolean.getBoolean("seamlessportals.bloomMaskProbe");

    public static boolean doCheckGlError = true;
    
    public static boolean renderYourselfInPortal = true;
    
    public static boolean activeLoading = true;
    
    public static int netherPortalFindingRadius = 128;
    
    public static boolean teleportationDebugEnabled = false;
    
    public static boolean correctCrossPortalEntityRendering = true;

    // R3 (S11-C): the cross-portal entity clip DELIVERY mechanism. Read live per frame by
    // PerEntityClipBracket.getMechanism(); the C4 rider (BINDING) keeps BOTH mechanisms wired so the S18
    // A/B live test is a no-restart config toggle. Design: migration/port-notes/S11-R3-clip-bracketing.md §5.
    // (The Mechanism enum is nested in the client-side PerEntityClipBracket; this field only names the
    // constant, so class-loading IPGlobal on a dedicated server never force-loads the client render class.)
    public static qouteall.imm_ptl.core.render.PerEntityClipBracket.Mechanism crossPortalEntityClipMechanism =
        qouteall.imm_ptl.core.render.PerEntityClipBracket.Mechanism.SUBMIT_ORDER_UNIFORM;

    public static boolean disableTeleportation = false;
    
    public static boolean looseMovementCheck = false;
    
    public static boolean pureMirror = false;
    
    public static int portalRenderLimit = 200;
    
    public static boolean cacheGlBuffer = true;
    
    public static boolean reducedPortalRendering = false;
    
    public static boolean useSecondaryEntityVertexConsumer = true;
    
    public static boolean cullSectionsBehind = true;
    
    public static boolean offsetOcclusionQuery = true;
    
    public static boolean cloudOptimization = true;
    
    public static boolean crossPortalCollision = true;
    
    public static boolean netherPortalOverlay = false;
    
    public static boolean debugDisableFog = false;
    
    public static int scaleLimit = 30;
    
    public static boolean easeCreativePermission = true;
    public static boolean easeCommandStickPermission = true;
    
    public static boolean enableDepthClampForPortalRendering = true;
    
    public static boolean enableServerCollision = true;
    
    public static boolean enableSharedBlockMeshBuffers = true;
    
    public static boolean saveMemoryInBufferPack = true;
    
    public static boolean enableDatapackPortalGen = true;
    
    public static boolean enableCrossPortalView = true;
    
    public static boolean enableClippingMechanism = true;
    
    public static boolean enableWarning = true;
    
    public static boolean enableMirrorCreation = true;
    
    public static boolean lightVanillaNetherPortalWhenCrouching = true;
    
    public static boolean enableNetherPortalEffect = true;
    
    public static boolean tickOnlyIfChunkLoaded = true;
    
    public static boolean allowClientEntityPosInterpolation = true;
    
    public static boolean alwaysOverrideTerrainSetup = false;

    // S14.22/S14.24/S14.27 diagnosis levers (default OFF; ClientDebugCommand switches; S20-removal-ledgered).
    public static boolean debugGlStateAssert = false;
    public static boolean debugDyePortalFill = false;
    public static boolean debugSkipPortalSky = false;
    public static boolean debugSkipPortalTerrain = false;
    public static boolean debugDyeViewAreaMesh = false;
    public static boolean debugNoApertureDepthClamp = false;

    /** IS5-G GHOST-WAVE DISCRIMINATOR run 2 (ghost panel wf_ac30cdc3-265): skip the depth-clamp bracket
     *  around the IrisCompatPaste STAMP ONLY (IrisCompatOn262Renderer:288/311). DEDICATED lever — do NOT
     *  reuse debugNoApertureDepthClamp: that one also gates the occlusion-query aperture draw
     *  (ViewAreaRenderer), which can cull the portal at plane-hugging frames and fake the verdict.
     *  Splits sub-cause (b) clamp-wedge-overreach (ghost shrinks with this ON) from (a)/(b'). Runtime
     *  debug command: /imm_ptl_client_debug debug_no_stamp_depth_clamp enable. Default-OFF. */
    public static boolean debugNoStampDepthClamp = false;
    public static boolean debugFrameBoundaryProbe = false;
    public static boolean debugSkipPortalEntities = false;
    // S15 (recursive-view entities): kills ONLY the new same-dim/loop-back entity pass
    // (renderPortalEntitiesSameDim) — flipping it ON restores the pre-S15 SAME-DIM pass
    // behavior exactly (the one-flip regression discriminator for the live A/B). Verify
    // fold (wf_b11fbd6f-f8a): CROSS-DIM frames may still differ from pre-S15 via the
    // deliberately un-levered fade-gate keying extension in
    // LevelRendererEntityVisibilityMixin (isDestExtracting) — attribute cross-dim
    // entity-pop deltas to that mixin change, not to this lever.
    // §2b: ALSO honored by the compat full-pipeline same-dim fill (Step-9.5-SD) — one lever
    // kills BOTH same-dim entity passes (runtime: /imm_ptl_client_debug debug_skip_same_dim_entities).
    public static boolean debugSkipSameDimEntities = false;
    // S14.35: per-draw kill switches for the four never-individually-levered portal draws.
    // NOTE (S14.37): debugSkipApertureIncr zeroes the occlusion query => kills the WHOLE portal
    // pass — it attributes "the pass", not the draw. The channel masks below are the surgical form.
    public static boolean debugSkipApertureIncr = false;
    public static boolean debugSkipDepthClear = false;
    public static boolean debugSkipDepthRestore = false;
    public static boolean debugSkipStencilClamp = false;
    // S14.37: aperture-draw CHANNEL masks — the draw still rasterizes (query samples still pass,
    // the full pipeline still runs); only the named write channel is disabled.
    public static boolean debugApertureNoStencilWrite = false;
    public static boolean debugApertureNoDepthWrite = false;
    public static boolean debugApertureNoColorWrite = false;
    // S14.38: pass-interior STATE side-effect levers (every DRAW is lever-exonerated; the painter
    // is a vanilla draw corrupted by pass state — bisect the state writers one by one).
    public static boolean debugSkipDestExtract = false;
    // S14.39: Step-5 sub-bisection (dest-extract block = the confirmed corruptor).
    public static boolean debugSkipExtractOnly = false;
    public static boolean debugSkipSogFeed = false;
    public static boolean debugSkipCompileDrain = false;
    public static boolean debugSkipDestFogInstall = false;
    public static boolean debugSkipGlobalsUbo = false;
    public static boolean debugSkipDestLightmap = false;
    public static boolean debugSkipProjectionInstall = false;
    // S14.40: the wedge FIX'S A/B lever — ON restores the CORRUPTING vanilla mid-frame
    // ParticleEngine.extract during the dest-pass extract (mechanism: MixinParticleEngine).
    public static boolean debugAllowDestParticleExtract = false;
    // S14.40: dest-extract sub-call attribution levers (MixinLevelExtractor_DestSubLevers;
    // active only during the dest-pass extract — the main extract never consults them).
    public static boolean debugSkipExtractBerdPrepare = false;
    public static boolean debugSkipExtractErdPrepare = false;
    public static boolean debugSkipExtractEntities = false;
    public static boolean debugSkipExtractBlockEntities = false;
    public static boolean debugSkipExtractWeather = false;
    public static boolean debugSkipExtractSky = false;
    public static boolean debugSkipExtractBorder = false;
    // S14.41: the WEDGE FIX'S A/B lever — the dest-pass extract SKIPS the gizmo family by default
    // (user-attributed live: the S14.40 skip lever removed the wedges); ON restores the corrupting
    // vanilla emission (ChunkCullingDebugRenderer's captured-frustum rainbow visualization —
    // mechanism in MixinLevelExtractor_DestSubLevers).
    public static boolean debugAllowDestExtractGizmos = false;

    public static boolean viewBobbingReduce = true;
    
    public static boolean enableClientPerformanceAdjustment = true;
    public static boolean enableServerPerformanceAdjustment = true;
    
    public static boolean enableCrossPortalSound = true;
    
    public static boolean checkModInfoFromInternet = true;
    
    public static boolean enableUpdateNotification = true;
    
    public static boolean logClientPlayerCollidingPortalUpdate = false;
    
    public static boolean chunkPacketDebug = false;
    
    public static boolean entityUntrackDebug = false;
    public static boolean entityTrackDebug = false;
    public static boolean clientPortalLoadDebug = false;
    
    public static boolean debugRenderPortalShapeMesh = false;
    
    // make debug text not out-of-screen
    public static boolean moveDebugTextToTop = false;
    
    public static boolean boxPortalSpecialIteration = true;
    
    public static enum RenderMode {
        normal,
        compatibility,
        debug,
        none
    }
    
    // this should not be in core but the config is in core
    public static enum NetherPortalMode {
        normal,
        vanilla,
        adaptive,
        disabled
    }
    
    public static enum EndPortalMode {
        normal,
        toObsidianPlatform,
        scaledView,
        scaledViewRotating,
        vanilla
    }
    
    public static NetherPortalMode netherPortalMode = NetherPortalMode.adaptive;
    
    public static EndPortalMode endPortalMode = EndPortalMode.normal;
}
