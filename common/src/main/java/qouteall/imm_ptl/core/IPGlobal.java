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
    
    /** DEFAULT FALSE since 2026-08-02 (user decision) — see IPConfig.lagAttackProof for the reasoning
     *  and for what protection is given up. Kept in step with that field's default deliberately: if
     *  the two disagree, behaviour differs between the first frames and the moment the config is
     *  applied, which is the kind of split nobody debugs twice. */
    public static boolean lagAttackProof = false;
    
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

    // ===== TP-XDIM FRAME CENSUS (2026-07-28) — log-only, DEFAULT OFF ==========================
    // The third-person cross-dimension corruption arc's first instrument. The lever lives HERE
    // (not on a holder class) on purpose: RunConfigReport's [2/3] sweep reflects IPGlobal's public
    // static primitives with NO name filter, so these rows print in EVERY run — true when the -P
    // landed, FALSE when it did not, and ABSENT entirely on a pre-census jar. Three distinguishable
    // states from the once-per-session self-ID block; a holder class outside IPGlobal reaches the
    // [1/3] raw-property section only and cannot tell "lever off" from "wrong jar".
    //   .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PtpXdimCensus=true
    public static final boolean TP_XDIM_CENSUS_LEVER =
        Boolean.getBoolean("seamlessportals.tpXdimCensus");

    /** TP-XDIM optional GL add-on: adds TWO glIsEnabled STATE QUERIES (stencil, scissor) once per
     *  frame at the census's frame boundary. No readback, no barrier, no fence, no glGetError, and
     *  never inside a pass. Split from the primary lever so the base census is provably GL-free; a
     *  leg run with this set is a SEPARATE leg and is never mixed into the primary A/B. */
    public static final boolean TP_XDIM_CENSUS_GL_LEVER =
        Boolean.getBoolean("seamlessportals.tpXdimCensusGl");

    // ===== TP-XDIM — CROSS-VIEW FULL-PIPELINE ROUTE, DEFAULT ON (2026-07-28) ==================
    // MEASURED (census bdd8b62, 10k+ frames, 272/272 rows): third person + shaderpack + the camera
    // on the far side of a portal => whole-screen terrain vertex-transform explosion, sky perfect,
    // no GL errors, SAME-DIM and CROSS-DIM alike. Every such row: renderLevel=NO is0=NO f1=NO
    // invoke=D23-FALLBACK. CrossPortalViewRendering had replaced vanilla renderLevel, so iris's
    // ENTIRE per-frame gbuffer envelope (its @Inject at LevelRenderer.render HEAD: pipeline
    // selection, beginLevelRendering, the gbufferModelView/Projection capture, plus the separate
    // isRenderingLevel toggle that gates the terrain vertex-FORMAT remap) never ran — while the
    // pack's gbuffer PROGRAMS still substituted. Confirmed by the shaders A/B: identical dest-render
    // code, clean without a pack, exploded with one.
    // FIX: route that ONE layer-0 caller to the FULL-PIPELINE driver, whose single direct 8-arg
    // LevelRenderer.render() makes iris's class-woven hooks re-enter naturally — which is what IP
    // itself did (its cross-view invoke body was client.gameRenderer.renderLevel(...), translation
    // recorded at MyGameRenderer.java:68-70). GuiPortalRendering keeps the decomposed D23 fallback.
    // Pass this to force the pre-fix route and REPRODUCE the explosion (attribution both ways) —
    //   .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PdisableCrossViewFullPipeline=true
    public static final boolean CROSS_VIEW_FULL_PIPELINE_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableCrossViewFullPipeline");

    /** Confirm-counter: cross-view frames driven through the full pipeline. Render-thread int; the
     *  RunConfigReport [2/3] sweep surfaces it, so "the route was taken" is provable from the
     *  once-per-session self-ID block without reading a single census row. */
    public static int crossViewFullPipelineCount = 0;

    // TP-XDIM ESCAPE HATCH — DEFAULT OFF. Decline the cross-portal view entirely while a shaderpack
    // is running: vanilla renderLevel renders the frame and the third-person camera sees the SOURCE
    // world from inside the portal wall (IP's pre-cross-view behaviour — clipping, never
    // corruption). The documented-limitation fallback if the route above proves unusable live, AND
    // this arc's strongest NEGATIVE discriminator: if the explosion survives it, the cross-view
    // path is not the carrier and every conclusion in the TP-XDIM handoff must be re-opened —
    //   .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PcrossViewSuppressUnderPack=true
    public static final boolean CROSS_VIEW_SUPPRESS_UNDER_PACK_LEVER =
        Boolean.getBoolean("seamlessportals.crossViewSuppressUnderPack");

    // ===== XWIN — THE REVERSE WINDOW INSIDE THE CROSS VIEW, DEFAULT ON (2026-07-29) ===========
    // USER-REPORTED right after the sibling fix above landed: "when the camera is in opposite dim
    // of player, the portal window does not show, so you only see the opposite dim and no player".
    // Ledgered gap #7 of that fix. The DECOMPOSED dest core has always had the slot that draws
    // portals inside a dest render (SecondaryWorldRenderCore Step 10.10) — which is why a
    // SHADERS-OFF cross view already shows the window (M0 gate, live-confirmed by the user
    // 2026-07-29: "the window is there with shaders off"). The FULL-PIPELINE core has no such slot:
    // everything happens inside its one LevelRenderer.render() call, and the compat renderer's
    // window pass is driven only by the IS0 anchor, which injects inside GameRenderer.renderLevel —
    // the method a cross-view frame elides. IP had no gap at all: its cross-view invoke body was a
    // recursive renderLevel(), which re-fired its own portal hooks with client.level == the dest.
    // THE FIX adds the full-pipeline twin of Step 10.10, at the only correct point for the compat
    // renderer (after render(), because its workhorse snapshots the FINISHED frame).
    // DEFAULT ON: window-less is the DEFECT, not a conservative baseline, and shaders-OFF already
    // behaves the fixed way — so ON is the parity direction, not the novel one. Blast radius is
    // exactly the frames broken today; every other frame short-circuits on one boolean.
    // Pass this to force the pre-fix route and REPRODUCE the missing window —
    //   .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PdisableCrossViewReverseWindow=true
    public static final boolean CROSS_VIEW_REVERSE_WINDOW_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableCrossViewReverseWindow");

    // ===== IS5-XCUT — THE NEAR FLOOR MOVES TO THE FRAGMENT STAGE, DEFAULT ON (2026-08-01) =====
    // USER-REPORTED: first person, close to the seam, FEET PLANTED — the portal window's outline is
    // cut by a straight line that SWEEPS as the camera pans, pivoting about a corner; content
    // inside is correct; stops ~half a block out.
    // CAUSE, attributed by three user-verified legs (each config-proven in the log):
    //     depth ON  + floor ON  -> cut PRESENT  (baseline)
    //     depth OFF + floor ON  -> cut GONE     (raw footprint is a clean stable rectangle =>
    //                                            the aperture GEOMETRY is innocent)
    //     depth ON  + floor OFF -> cut GONE     (vsh=NOCAP, probe CAP-IN-SOURCE=false x14)
    // The cut needs BOTH => the PER-VERTEX floor is the carrier. Depth interpolates screen-affine,
    // so clamping per VERTEX computes L[max(z,c)] instead of max(L[z],c): it TILTS the interpolated
    // depth plane. The S14.36 CPU clip leaves one aperture vertex ~0.1 mm from the eye (true NDC z
    // ~ -1e3); flooring that one vertex skews the whole plane, and the error is affine in screen
    // space => a straight boundary, pinned at the unfloored vertices, sweeping with rotation.
    // FIX: apply the floor PER FRAGMENT (gl_FragDepth = max(gl_FragCoord.z, 0.001)) — a true clamp,
    // which cannot tilt a plane. Same depth the vertex floor targeted: NDC -0.998 -> window 0.001
    // under the MEASURED glDepthRange(0,1) and clipDepthMode=NEGATIVE_ONE_TO_ONE at the stamp draw.
    // DEFAULT ON: every frame in the affected set is measurably wrong today, and the clamp's
    // magnitude is unchanged — only where it is computed. The hand protection §00z shipped is
    // preserved (window still pinned to 0.001, behind the hand's [0, 0.0005] bracket) and no
    // aperture geometry is cut, so the IS5-SEAM coverage fix cannot regress either.
    // Pass this to force the OLD per-vertex floor back and REPRODUCE the swept cut —
    //   .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PdisableXcutFragFloor=true
    public static final boolean XCUT_FRAG_FLOOR_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableXcutFragFloor");

    /** XWIN confirm-counter: cross-view frames on which the portal pass was actually entered.
     *
     *  <p>DELIBERATELY PRIVATE, for the SAME reason as the IS0 anchor counter above:
     *  RunConfigReport's [2/3] sweep prints every PUBLIC static scalar on this class under "lever
     *  constants", and that block is emitted synchronously by RunConfigReport.noteArmedFrame —
     *  which the cross-view invoke branch already fired EARLIER in this same frame. A public field
     *  would therefore print 0 in the very block meant to prove the route was taken. That exact bug
     *  is one commit old (2f9d7b8). Render-thread plain int. */
    private static int crossViewReverseWindowPasses = 0;

    public static void noteCrossViewReverseWindowPass() {
        crossViewReverseWindowPasses++;
    }

    public static int getCrossViewReverseWindowPasses() {
        return crossViewReverseWindowPasses;
    }

    // ===== IS5-REC — RECURSIVE PORTAL LAYERS, SHADERS ON (2026-08-02) =========================
    // MEASURED BASELINE (c493fb2, reverse pair, first person, laggy overlay never fired, 381 rows):
    //     shaders ON   renderer=IrisCompatOn262Renderer  invoke=FULL-PIPELINE x1  maxPortalDepth=1
    //     shaders OFF  renderer=RendererUsingStencil     invoke=BASE-DECOMPOSED x2 maxPortalDepth=2
    // User-confirmed visually in both directions: "with shaders on, the recursive portal doesnt show
    // at all, with shaders off, the recursive portal show correctly". So this is PARITY work against
    // a measured number, not a new capability.
    //
    // WHY IP'S OWN MECHANISM COULD NOT BE ADOPTED: IP recursed by re-entering the nested
    // renderLevel's hooks, and masked each layer with a stencil blitted FBO->FBO via
    // RenderTarget.frameBufferId. Neither exists here — the IS0 anchor injects into
    // GameRenderer.renderLevel while the nested dest render calls LevelRenderer.render directly
    // (SecondaryWorldRenderCore:1905, so the anchor fires once per frame — measured is0=YES(x1) on
    // 381/381 baseline rows), and frameBufferId is gone on 26.2 (IP's held IrisPortalRenderer has
    // those blit blocks COMMENTED OUT at :152/:210). The dispatch is therefore a new per-layer entry
    // point at the full-pipeline core's tail, and the masking stays the stencil-free D20 stamp.

    /**
     * IS5-TERM A/B lever — restores the pre-fix behaviour: the portal at the recursion bound draws
     * its black aperture mesh with no content painted over it, i.e. a solid black box.
     * {@code -PdrawTerminalPortalAsBlack=true}. Shaders-OFF (stencil family) only; the shaders-ON
     * compat renderer never drew it.
     */
    public static final boolean debugDrawTerminalPortalAsBlack =
        Boolean.getBoolean("seamlessportals.drawTerminalPortalAsBlack");

    /**
     * IS5-XREC A/B lever — restores the Stage-3 scope limit: no recursion on a CROSS-PORTAL-VIEW
     * frame (third person with the camera past the aperture), so portals seen inside the reverse
     * window go flat again. {@code -PdisableCrossViewRecursion=true}.
     *
     * <p>Deliberately SEPARATE from {@link #CROSS_VIEW_REVERSE_WINDOW_DISABLED_LEVER}: that one
     * governs whether the reverse WINDOW is drawn at all, this one whether portals INSIDE it
     * recurse. Two independent behaviours, so two independent A/Bs — collapsing them would make each
     * leg unattributable.
     */
    public static final boolean CROSS_VIEW_RECURSION_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableCrossViewRecursion");

    /** A/B lever: reproduces the pre-IS5-REC one-layer behaviour byte-for-byte on command.
     *  {@code -PdisableIrisPortalRecursion=true}. Every fix in this project must be provable in BOTH
     *  directions, and "it looks better" has passed on a no-op here before. */
    public static final boolean IRIS_PORTAL_RECURSION_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableIrisPortalRecursion");

    /**
     * The shaders-ON recursion bound, SEPARATE from {@link #maxPortalLayer} (=5) on purpose.
     *
     * <p>Layer n shaders-ON is a full PACK-SHADED world render — gbuffer, shadow pass and the whole
     * composite chain — which is a categorically heavier unit than the stencil family's layer n.
     * Reusing 5 would multiply the frame's heaviest work by five on a path nobody has cost yet (the
     * only per-pass number in this tree is explicitly voided pending re-measurement,
     * MyGameRenderer:418-421).
     *
     * <p>2 is chosen because it is exactly parity for the measured baseline geometry, not as a
     * placeholder: a REVERSE PAIR reaches exactly depth 2 shaders-OFF, and it is cut there by
     * {@code isInvalidRecursionRendering}, which requires a stack of >=2 — so at [A] candidate B is
     * admitted and at [A,B] candidate A is rejected. That cut happens in shouldSkipRenderingPortal,
     * BEFORE any aperture is drawn, so shaders-OFF shows terrain with the portal simply absent —
     * which is also what a bound of 2 produces here. Parity is therefore EXACT for this geometry,
     * terminal appearance included. Raising it is a MEASUREMENT-GATED decision (sweep dpMs), not a
     * taste one. -PirisMaxPortalLayer=N.
     */
    public static int irisMaxPortalLayer =
        Integer.getInteger("seamlessportals.irisMaxPortalLayer", 5);

    /** TRUE when {@code -PirisMaxPortalLayer} was passed, i.e. a dev A/B leg is pinning the depth.
     *  The in-game setting must NOT quietly overwrite that — three legs of this project have already
     *  been voided by a lever that did not end up governing what it claimed to govern. */
    public static final boolean IRIS_MAX_LAYER_PINNED_BY_LEVER =
        System.getProperty("seamlessportals.irisMaxPortalLayer") != null;

    /** Belt against a pathological scene (many portals x many layers) turning one frame into an
     *  unbounded pack-shaded render tree. Counted per FRAME across all layers, not per layer.
     *  -PirisMaxDestRenders=N. */
    public static int irisMaxDestRenders =
        Integer.getInteger("seamlessportals.irisMaxDestRenders", 30);

    /**
     * The bound actually enforced this frame.
     *
     * <p>Precedence: (1) the {@code -PirisMaxPortalLayer} dev lever if passed — it PINS the value so
     * an A/B leg measures what it claims to; (2) otherwise the in-game setting
     * ({@code irisRecursionDepth}, default 5), read live so the config slider applies without a
     * restart; then (3) the OPT-IN, default-OFF lag guard, and the engine bound.
     *
     * <p>The {@code min} with {@link PortalRendering#getMaxPortalLayer()} keeps the engine bound
     * dominant, which also means {@code RenderStates.isLaggy} still collapses this to 1. But note
     * that guard CANNOT arm for deep recursion — it requires >10 dest renders in the previous frame
     * and a deep single chain makes about one per LAYER (measured: {@code isLaggy=false} on all 166
     * rows of the depth-5 leg, which proves only that the gate could not fire). That gap is exactly
     * what the opt-in guard below covers.
     */
    public static int effectiveIrisMaxPortalLayer() {
        int engineBound = qouteall.imm_ptl.core.render.context_management.PortalRendering
            .getMaxPortalLayer();

        int configured = irisMaxPortalLayer;
        if (irisRecursionLagGuard) {
            configured = Math.min(configured, lagGuardedDepth());
        }
        return Math.min(configured, engineBound);
    }

    /** Live value, written by {@code IPConfig.onConfigChanged} (the config screen and the json).
     *  Default OFF — see that field's javadoc for why the engine's own lag guard cannot cover deep
     *  recursion. */
    public static boolean irisRecursionLagGuard = false;

    /**
     * How far from the player a portal still renders its WINDOW, in CHUNKS. {@code 0} = follow the
     * vanilla render distance (the default, and what the config's reset button restores). Clamped
     * 0..32 by {@code IPConfig.onConfigChanged}; consumed by {@code PortalRenderer.getRenderRange}.
     *
     * <p>Not to be confused with the destination LOADING depth
     * ({@code portalRenderDistance} in seamlessportals.properties). This is how far AWAY the player
     * can be and still see a window at all; that one is how deep the world behind it is loaded.
     */
    public static int portalWindowRenderDistance = 0;

    /** Resource ceiling on the shaders-ON depth, not a taste limit: each layer a scene ACTUALLY
     *  REACHES allocates its own full-screen colour+depth target. 128 layers is ~2.1 GB of VRAM at
     *  1080p and ~8.5 GB at 4K. */
    public static final int IRIS_RECURSION_DEPTH_CEILING = 128;

    /**
     * IS5-RLOAD — total number of portal-destination chunk loaders one player may generate per tick
     * while walking the portal chain ({@code ChunkVisibility.loadPortalChainRecursively}).
     *
     * <p>DEPTH ALONE DOES NOT BOUND THIS. Portals fan out: a room with 5 portals each seeing 5 more
     * is 25 regions at depth 3 and 125 at depth 4. This is the bound that stops a dense build from
     * pinning thousands of chunk regions loaded on the server. 64 is generous for any hand-built
     * chain (a 10-deep single chain needs 10) while still capping the pathological case.
     *
     * <p>When it bites, the chain simply stops loading further out — the same visual result as
     * before this fix existed, and strictly better than a stalled server tick.
     */
    public static int portalChainLoaderBudget =
        Integer.getInteger("seamlessportals.portalChainLoaderBudget", 64);

    /**
     * IS5-REACH — nested portal levels load with the same distance graduation as the FIRST level
     * (full view distance within 5 blocks of that portal, 2/3 within 15, 1/3 beyond) instead of a
     * flat quarter of the view distance. Live value, written by {@code IPConfig.onConfigChanged}.
     *
     * <p>OFF restores the original flat {@code loadDistance / 4} — which also makes
     * {@link #indirectLoadingRadiusCap} INERT for deep levels, since that cap can only bind when the
     * target exceeds it and a fixed quarter never does. That is why maxing the cap appeared to do
     * nothing before this existed.
     */
    public static boolean deepPortalLoadingReach = true;

    /**
     * IS5-KEEP — how long a portal-loaded chunk stays resident after nothing is watching it, in
     * GENERATIONS of {@code ImmPtlChunkTracking.updateInterval} (13 ticks each).
     *
     * <p>Default 4 = about 2.6 seconds, IP's original. Raising it stops destination chunks being
     * re-streamed every time you glance away from a portal and back. <b>Negative = never unload
     * while the player is online</b>, which also disables the adaptive shrink that would otherwise
     * cancel the setting the moment it started working.
     *
     * <p>Live value, written by {@code IPConfig.onConfigChanged}. Costs server memory in proportion
     * to how much world stays resident.
     */
    public static int chunkUnloadDelayGenerations = 4;

    /** Above this, {@link #warnIfDeepRecursion} logs the VRAM arithmetic once per changed pair. */
    public static final int DEEP_RECURSION_WARN_AT = 8;

    private static int lastWarnedRecursionDepth = -1;

    /**
     * One line per changed value — loud enough to explain a VRAM cliff after the fact, quiet enough
     * not to spam someone who set 40 deliberately. Deliberately does NOT clamp below the ceiling:
     * the value is the user's decision; this only makes its cost legible.
     */
    public static void warnIfDeepRecursion(int vanillaDepth, int shaderDepth) {
        int deepest = Math.max(vanillaDepth, shaderDepth);
        if (deepest <= DEEP_RECURSION_WARN_AT || deepest == lastWarnedRecursionDepth) {
            return;
        }
        lastWarnedRecursionDepth = deepest;
        Helper.LOGGER.warn(
            "[IS5-REC] Portal recursion depth set deep (maxPortalLayer={}, irisRecursionDepth={})."
                + " With a shaderpack ON, each layer a scene ACTUALLY REACHES is a full pack-shaded"
                + " world render AND allocates its own full-screen colour+depth target (~16.6 MB at"
                + " 1920x1080, ~66 MB at 3840x2160) — so a scene that truly recurses {} deep would"
                + " hold ~{} MB of them at 1080p. Allocation is lazy, so this costs nothing until"
                + " such a scene exists. Note a linked REVERSE PAIR still terminates at 2 regardless"
                + " (isInvalidRecursionRendering); reaching depth N needs a CHAIN of N portals.",
            vanillaDepth, shaderDepth, deepest, deepest * 17);
    }

    /**
     * The OPT-IN deep-recursion lag guard. Graduated rather than a cliff, so a machine sitting near
     * a threshold sheds one layer at a time instead of oscillating between 5 and 1 every second.
     * Reads the same {@code ClientPerformanceMonitor} the engine's guard uses, so the two agree on
     * what "laggy" means; that monitor's per-second averaging supplies the damping.
     *
     * <p>Returns MAX_VALUE (= no throttle) when there is no sample yet, so a cold monitor during
     * world load cannot clamp the depth on the frames a player is most likely to be looking at a
     * portal.
     */
    private static int lagGuardedDepth() {
        try {
            int avg = qouteall.imm_ptl.core.miscellaneous.ClientPerformanceMonitor.getAverageFps();
            if (avg <= 0) {
                return Integer.MAX_VALUE;
            }
            if (avg < 15) {
                return 1;
            }
            if (avg < 25) {
                return 2;
            }
            if (avg < 40) {
                return 3;
            }
            return Integer.MAX_VALUE;
        }
        catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }

    /** MONOTONIC count of nested portal passes dispatched (layer >= 1). DELIBERATELY NOT routed
     *  through {@code TpXdimFrameCensus.noteXWinPass}: that counter prints
     *  "YES(xN RECURSION-ANOMALY)" above 1, so reusing it would make a CORRECT deep cross-view frame
     *  adjudicate as broken — an existing pre-registered criterion declaring a working fix a defect,
     *  which this project has already done once. */
    private static int nestedPortalLayerPasses = 0;

    public static void noteNestedPortalLayerPass() {
        nestedPortalLayerPasses++;
    }

    public static int getNestedPortalLayerPasses() {
        return nestedPortalLayerPasses;
    }

    /** MONOTONIC count of nested dest renders REFUSED by the {@link #irisMaxDestRenders} budget.
     *  Exists because a silent cut is indistinguishable from "there was nothing to render": the
     *  budget cuts in portal DISTANCE order, so the portals that lose are the far ones, and a window
     *  simply not filling in looks like a rendering defect rather than a bound doing its job. */
    private static int nestedBudgetCuts = 0;

    public static void noteNestedBudgetCut() {
        nestedBudgetCuts++;
    }

    public static int getNestedBudgetCuts() {
        return nestedBudgetCuts;
    }

    /** MONOTONIC count of frames on which the IS0 post-main anchor fired. Written ONLY by
     *  MixinGameRenderer_IPPostLevelAnchor (gated on the lever above), read as a DELTA by
     *  TpXdimFrameCensus at GameRenderer.render TAIL — the "did the anchor fire this frame"
     *  boolean expressed as a COUNTER, so no clear can be stranded by a throw and print a false NO.
     *  Held on IPGlobal (not on the census class) so the anchor mixin keeps its
     *  zero-com.warwa-imports property (its javadoc's S20-safety clause).
     *
     *  <p>DELIBERATELY PRIVATE, reached through the two accessors below. RunConfigReport's [2/3]
     *  sweep prints every PUBLIC static primitive on this class under the heading "lever constants",
     *  and that block is emitted at the first portal dest render — before this anchor has fired even
     *  once. A public field here would therefore print "tpXdimCensusIs0AnchorFrames = 0" directly
     *  under "TP_XDIM_CENSUS_LEVER = true" in a list of levers, which reads as "the IS0 witness is
     *  not woven": a running counter tabulated as a lever state. The sweep's no-name-predicate
     *  property is deliberate and load-bearing, so the field hides from it instead.
     *  Render-thread plain int; no atomic needed. */
    private static int tpXdimCensusIs0AnchorFrames = 0;

    /** Called by the IS0 anchor mixin (already inside its folded lever test). */
    public static void noteTpXdimIs0AnchorFired() {
        tpXdimCensusIs0AnchorFrames++;
    }

    /** Read by TpXdimFrameCensus as a per-frame delta. Not a boolean, so RunConfigReport's
     *  decision sweep (public static boolean isX()) does not pick it up either. */
    public static int getTpXdimCensusIs0AnchorFrames() {
        return tpXdimCensusIs0AnchorFrames;
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

    // IS5-COV (2026-08-03) — the STAMP COVERAGE PROBE. Log-only, DEFAULT OFF, <=1 Hz. Reads one
    // full-width scanline of the deferred buffer AFTER the stamp pass and run-length encodes it by
    // stamped-ness, carrying each run's depth range. Answers the one open question on the occluder
    // ring: does the un-stamped ring hold the OCCLUDER's depth (⇒ the depth footprint is dilated)
    // or the BACKGROUND's (⇒ the depth test is not the gate). It REFUSES to measure unless
    // debugStampSolid AND debugTintStamp are both set, because its classifier is "is this pixel the
    // stamp's flat magenta" and that is exact only then. See StampCoverageProbe's javadoc.
    public static final boolean STAMP_COVERAGE_PROBE =
        Boolean.getBoolean("seamlessportals.stampCoverageProbe");

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

    // C4-SEAM — THE BLACK SEAM BAND's ROOT-CAUSE FIX, **DEFAULT ON** (2026-07-27). The band was
    // the C3-BLOOM aperture mask: it clears colortex0 to black inside the nested dest composite
    // and repaints only the aperture footprint, but its repaint drew WITHOUT the depth clamp the
    // stamp draws under — the S14.36 CPU clip cuts at the CAMERA plane, not the 0.05 near plane,
    // so at a crossing the 0..5 cm aperture shell rasterized for the stamp and near-clipped away
    // for the mask: the stamp copied the mask's cleared black = the band. Attribution: the
    // one-frame DrawCallTrace placed the mask's mesh build inside the nested pass; the content
    // probe measured black COLOR over normal geometry depth; -PdisableIrisBloomApertureMask
    // killed the band live (and brought the bloom ring back). Fix = depth-clamp the mask's
    // repaints (exact raster parity with the stamp, the CHelper pair, DISABLED after — the
    // composite chain's ambient state).
    // Pass this to force the UNCLAMPED repaints and reproduce the band (attribution both ways) —
    public static final boolean BLOOM_MASK_SEAM_CLAMP_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableBloomMaskSeamClamp");

    // IS5-HAND — THE SEAM HAND-SLICING FIX, **DEFAULT ON** (2026-07-27). Under a pack iris
    // bakes the first-person hand into the main frame PRE-anchor (HandRenderer inside
    // LevelRenderer.render; vanilla's post-anchor hand call is no-op'd) with a compressed depth
    // slice MEASURED at 0.5556..0.5569 (45 probe samples). The stamp draws under GL_DEPTH_CLAMP,
    // so the crossing sliver's nearer-than-near fragments wrote depth 1.0 and GEQUAL-painted the
    // portal view OVER the hand exactly along the seam (clip family exonerated by the
    // front_clipping leg — store disarmed at every hand draw, hand programs loc==-1; shaders-off
    // intact because vanilla wipes depth pre-hand). Fix = the stamp vertex shader caps NDC z at
    // 0.5: the sliver still stamps over all world content beyond 10 cm (the C4-SEAM band fix
    // intact) but always loses to the hand slice. Selected at PIPELINE REGISTRATION (this lever
    // swaps in the verbatim pre-cap portal_area_sample_nocap.vsh for every stamp pipeline) —
    // pass it to reproduce the hand slicing (attribution both directions).
    public static final boolean STAMP_HAND_DEPTH_CAP_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableStampHandDepthCap");

    // IS5-HAND V2 — THE HAND-SLICING ROOT-CAUSE FIX, **DEFAULT ON** (2026-07-27). The stamp cap
    // above was refuted as the dominant carrier (armed + byte-proven, symptom identical); the
    // hand-region probe then measured the hand SHREDDED IN THE SNAPSHOT ITSELF on deep-crossing
    // seconds — iris's own hand pass (compressed slice 0.5544..0.5560) loses its depth test to
    // the seam's 5-10 cm grazing shell (depth 0.56..1.0) in the MAIN pass, before any compat
    // machinery. Fix = bracket iris's HandRenderer.renderSolid/renderTranslucent with
    // glDepthRange(0.999, 1.0) while the camera is within the crossing window (0.35, non-Mirror,
    // main pass only): the hand beats the shell except a ~0.05 mm-equivalent hairline, intra-hand
    // ordering preserved, and the capped stamp (<= 0.5) can never overpaint it. The once-only
    // "IS5-HAND depth bracket ARMED" line is the mixin-landing proof (require=0 — an iris drift
    // unhooks quietly; a lever-ON leg without that line is VOID, not a refutation).
    // Pass this to force the bracket OFF and reproduce the slicing (attribution both ways) —
    public static final boolean HAND_SEAM_DEPTH_BRACKET_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableHandSeamDepthBracket");

    // IS5-HAND-FUNC (2026-07-28, DEFAULT OFF — premise REFUTED by the draw-time dump: the
    // hand draws re-apply their own LEQUAL per draw, so the HEAD force is inert for them and
    // LEQUAL is the hand pass's NORMAL convention, not a leak. Kept as an opt-in experiment
    // lever only). Enable with -PenableHandDepthFuncFix.
    public static final boolean HAND_DEPTH_FUNC_FIX_DISABLED_LEVER =
        !Boolean.getBoolean("seamlessportals.enableHandDepthFuncFix");

    // IS5-HAND-STAGE four-point stage diff (DIAGNOSTIC, default OFF). Declared here only so the
    // IS5-RC run-config block reports it (the probe reads its own property —
    // SeamHandStageDiff.ENABLED).
    public static final boolean HAND_STAGE_DIFF_PROBE =
        Boolean.getBoolean("seamlessportals.handStageDiff");

    // IS5-HAND-INLVL in-renderLevel hand-pass stage points (DIAGNOSTIC, default OFF). Declared
    // here only so the IS5-RC run-config block reports it (the probe reads its own property —
    // SeamHandInLevelProbe.ENABLED).
    public static final boolean HAND_IN_LEVEL_PROBE =
        Boolean.getBoolean("seamlessportals.handInLevelProbe");

    // IS5-STAMP-EXEC executed-state probe at the stamp draw (DIAGNOSTIC, default OFF). Declared
    // here only so the IS5-RC run-config block reports it (the probe reads its own property —
    // StampExecStateProbe.ENABLED).
    public static final boolean STAMP_EXEC_PROBE =
        Boolean.getBoolean("seamlessportals.stampExecProbe");

    // IS5-HAND-TAP iris HandRenderer canRender-vs-submit tap (DIAGNOSTIC, default OFF).
    // Declared here only so the IS5-RC run-config block reports it (the tap reads its own
    // property — SeamHandSubmitTap.ENABLED).
    public static final boolean HAND_SUBMIT_TAP =
        Boolean.getBoolean("seamlessportals.handSubmitTap");

    // IS5-HAND-LOC full-frame hand locator (DIAGNOSTIC, default OFF). Declared here only so
    // the IS5-RC run-config block reports it (the probe reads its own property —
    // SeamHandLocator.ENABLED).
    public static final boolean HAND_LOCATOR =
        Boolean.getBoolean("seamlessportals.handLocator");

    // IS5-HAND-DRAW draw-time state dump at real hand-feature draws (DIAGNOSTIC, default
    // OFF). Declared here only so the IS5-RC run-config block reports it (the probe reads
    // its own property — HandDrawStateDump.ENABLED).
    public static final boolean HAND_DRAW_DUMP =
        Boolean.getBoolean("seamlessportals.handDrawDump");

    // IS5-STAMP-EAT (2026-07-28) — the candidate hand fix: bind the LEQUAL stamp sibling.
    // The survival table caught the stamp overpainting the hand between the anchor and the
    // blit-back; the hand pass's measured convention is small-is-near/LEQUAL, so the shipped
    // GEQUAL lets the aperture beat everything NEARER than it. A/B: -PstampLequal.
    public static final boolean STAMP_LEQUAL_LEVER =
        Boolean.getBoolean("seamlessportals.stampLequal");

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

    // IS5-BLOOMMB — the gatherer retarget. `maskIndex = lastC0Writer + 1` assumes the last colortex0
    // WRITER precedes the bloom GATHERER. With the pack's Motion Blur on, Complementary's composite4
    // (which IS the gatherer) starts writing colortex0 too, becomes the last writer, and shoves the
    // mask one pass PAST the gather — where it runs successfully every frame and does nothing.
    // The retarget detects that shape via Pass.mipmappedBuffers and masks the gatherer instead.
    // DEFAULT TRUE, but PROVISIONALLY so: it also blackens composite4's motion-blur source, a trade
    // that is arithmetically zero at rest and must be judged under sustained fast yaw before the
    // default is considered settled. A/B OFF via -Dseamlessportals.disableBloomMaskGathererRetarget.
    // Full record: migration/MB_BLOOM_SEAM_HANDOFF.md §7f.
    public static final boolean BLOOM_MASK_GATHERER_RETARGET_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableBloomMaskGathererRetarget");

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

    // IS5-PRE — stage-consistent portal compositing (migration/IS5_PRE_DESIGN.md v3). The occluder
    // ring's MECHANISM fix: portal views render at FRAME START, are captured pre-composite at their
    // finalizeLevelRendering seam (composites+final cancelled), and are stamped into the MAIN chain
    // at composite renderAll HEAD — so the pack's non-local passes filter occluders and portal
    // content TOGETHER, once. The old post-final path remains whole behind this flag: it is the
    // on-command ring reproduction and the escape hatch for every closed arc.
    // ONE static-final resolved at class load (mixins cannot unload; every hook gates on this same
    // immutable value — the path can never mix mid-session). DEFAULT FLIPPED TRUE 2026-08-05
    // after the regression session (IS5_PRE_REGRESSION_RESULTS.md): the §2 matrix on both paths,
    // the C2 unit-size acceptance leg, and part4 (nested capture-to-capture) all passed.
    // -PdisableStageConsistentComposite is the SHIPPED lever — the on-command old-path/ring
    // reproduction and the escape hatch for every closed arc; -PstageConsistentComposite=true
    // remains accepted (redundant now) so existing leg commands keep working.
    public static final boolean STAGE_CONSISTENT_COMPOSITE_DEFAULT = true;
    public static final boolean STAGE_CONSISTENT_COMPOSITE =
        Boolean.getBoolean("seamlessportals.stageConsistentComposite")
            || (STAGE_CONSISTENT_COMPOSITE_DEFAULT
                && !Boolean.getBoolean("seamlessportals.disableStageConsistentComposite"));

    // PERF-P1 (2026-08-10) — the perf-milestone instrument mutes. The two 1Hz GL readbacks
    // ("capture center px" + "depth center after stamp") are synchronous GPU pipeline stalls that
    // answered questions in arcs now CLOSED (leg-6/leg-7 write-path proof; part5 depth comparator,
    // ghost-double dd01b4d). Each block gates ATOMICALLY (guard + body + glGetError drain together —
    // the drain at the depth readback protects runStampPass's final error check whose failure
    // breaks the mechanism; never leave a readback live with its drain gated off).
    public static final boolean is5LiveReadbacks =
        Boolean.getBoolean("seamlessportals.is5LiveReadbacks");

    // PERF-P1 — TeleportFlashProbe's ALWAYS-ON half: a ~15-String.format ring row built EVERY
    // frame (portal or not) + a 10-20KB render-thread batched dump 40 frames after every crossing.
    // The flash + ghost-double arcs are closed user-confirmed; the row build is measurable
    // allocation pressure and the dump is itself a crossing-frame stall (self-marked in the probe).
    // OFF by default; the debug_capture_flash one-shot command path stays live regardless.
    public static final boolean flashProbe =
        Boolean.getBoolean("seamlessportals.flashProbe");

    // PERF-P1 — RenderChainProbe's post-crossing AUTO-ARM (1 line/s on the render thread for ~20s
    // after every promote — exactly the window a freeze hunt measures; S20-removal-ledgered).
    // OFF by default; the debug_dump_render_chain one-shot command path stays live regardless.
    public static final boolean renderChainProbeAutoArm =
        Boolean.getBoolean("seamlessportals.renderChainProbeAutoArm");

    // IS5-OUTLINE (2026-08-11) — the shaders-ON selection-outline sliver fix, DEFAULT ON.
    // The block outline's LINES pipeline writes depth (bytecode-pinned); its overhang past the
    // block silhouette beats the portal plane, the stamp loses those pixels, and raw SOURCE
    // terrain shows as a sliver hugging the outline. Fix = swap the main outline draw to
    // linesTranslucent (identical pipeline, writeDepth=false; same iris program) — shaders-ON
    // only, the closed stencil-path arc untouched. Disable = the sliver on command.
    public static final boolean disableOutlineDepthWriteFix =
        Boolean.getBoolean("seamlessportals.disableOutlineDepthWriteFix");

    // IS5-DEPTHFORK (2026-08-11) → IS5-RESTAMP (2026-08-11, migration/IS5_RESTAMP_DESIGN.md):
    // the PLANE-vs-CONTENT fork this lever carried is DISSOLVED — the restamp delivers both
    // sides simultaneously (PLANE at HEAD for storm/MB/DOF, CONTENT at the measured
    // reprojection-anchor boundary for TAA). This lever is KEPT as the force-CONTENT-at-HEAD
    // escape: when set, the HEAD replay runs mode 1 (today's CONTENT semantics bit-for-bit) and
    // the restamp DISARMS (precedence: explicit CONTENT-HEAD > restamp).
    public static final boolean is5WindowContentDepth =
        Boolean.getBoolean("seamlessportals.is5WindowContentDepth");

    // IS5-RESTAMP (2026-08-11, migration/IS5_RESTAMP_DESIGN.md — the DEPTHFORK §4 rejected road
    // RE-OPENED on the user's explicit demand, runtime-measured variant only). DEFAULT ON.
    // depthtex1 gets PLANE at the main composite renderAll HEAD (kept cool MB at composite4,
    // storm-stops-at-pane at composite1) and a depth-only CONTENT restamp immediately BEFORE the
    // measured reprojection anchor (first pass actively sampling BOTH depthtex1 AND the history
    // colortex — TAA; the MB-off translation ghost dies there). Reader sets are measured per
    // CompositeRenderer via glGetUniformLocation on each pass's program — nothing hardcoded.
    // Disable row = force-PLANE (byte-identical shipped PLANE everywhere, the ghost on command).
    public static final boolean IS5_DEPTH_RESTAMP_DEFAULT = true;
    public static final boolean is5DepthRestamp =
        Boolean.getBoolean("seamlessportals.is5DepthRestamp")
            || (IS5_DEPTH_RESTAMP_DEFAULT
                && !Boolean.getBoolean("seamlessportals.disableDepthRestamp"));

    // IS5-XDIM-SG (2026-08-11, migration/IS5_RESTAMP_DESIGN.md Part 2) — single-grade cross-dim
    // capture. Requires crossDimDestChain + a restamp mode of RESTAMP/HEAD-CONTENT on the
    // source renderer. The dest chain's image is captured at the DEST pipeline's measured
    // anchor boundary (post-tonemap, pre-AA — graded exactly ONCE; the tonemap curve is
    // world-independent, so it matches the source surroundings) and INJECTED over the window
    // pixels at the SOURCE anchor boundary — the double-grade tint dies. Nested-child pixels
    // are excluded per-pixel via the R11-MASK alpha sentinel (the near-portal dim-window fix).
    // DEFAULT FLIPPED ON 2026-08-11 — the user residual gate passed (kill-checks 11-14: red
    // fog + storm ✓, double-grade gone ✓, fog-pop/storm-slab/nested acceptable; the
    // lava/high-angle bloom washout ships DISCLOSED, fix arc next).
    public static final boolean IS5_XDIM_SINGLE_GRADE_DEFAULT = true;
    public static final boolean is5XdimSingleGrade =
        Boolean.getBoolean("seamlessportals.is5XdimSingleGrade")
            || (IS5_XDIM_SINGLE_GRADE_DEFAULT
                && !Boolean.getBoolean("seamlessportals.disableXdimSingleGrade"));

    // IS5-WASH (2026-08-11, IS5_RESTAMP_DESIGN.md §1.10) — the lava/high-angle bloom-washout
    // fix, DEFAULT ON. User-adjudicated mechanism: ambient control clean + pack-bloom-off
    // vanishes it ⇒ the SOURCE chain's bloom gather harvesting energy from the stamped
    // window (fixed screen-space reach vs the angle-compressed footprint = the angle
    // signature); dest-baked bloom in the capture is the correct glow and stays. Fix: black
    // out POST/SG window footprints in c0 across the measured gatherer pass, restore before
    // bloom-apply. v1 GUARD: skipped when the gatherer also writes c0 (pack MB on — a
    // blackout would MB-smear; washout persists there, meas-line-visible). Same-dim PRE
    // windows are never blacked (source gather is their only bloom source).
    public static final boolean IS5_WINDOW_BLOOM_EXCLUDE_DEFAULT = true;
    public static final boolean is5WindowBloomExclude =
        Boolean.getBoolean("seamlessportals.is5WindowBloomExclude")
            || (IS5_WINDOW_BLOOM_EXCLUDE_DEFAULT
                && !Boolean.getBoolean("seamlessportals.disableWindowBloomExclude"));

    // IS5-WASHPROBE (2026-08-12, log-only, DEFAULT OFF) — the R12 washout's uniform
    // ground-truth: 1Hz dest-vs-main read of the bloom-apply pass's ACTUAL renderDistance /
    // isEyeInWater / cameraPosition uniform values (glGetUniform on the pass program — reads
    // executed state, not any mod-side mirror). Built after the matched-height ambient control
    // refuted the pack's-own-distance-glow story: a capture-vs-ambient uniform delta exists;
    // this names it. The nether GetBloomFog is (lViewPos/clamp(min(renderDistance, LIMIT),
    // 96, 512))^3 — a dest renderDistance 4x smaller than ambient = 64x the fog term.
    public static final boolean washProbe = Boolean.getBoolean("seamlessportals.washProbe");

    // IS5-FARFADE (2026-08-16, IS5_FARFADE_DESIGN.md, DEFAULT ON) — the far-window washout
    // fix, user-decided ("far handover to pre with no pop in, storm always present"): the
    // convicted defect is the dest chain's own atmospherics integrated over the VIRTUAL
    // camera distance (every delivery stage measured clean). Cross-dim SG windows crossfade
    // with camera-to-nearest-portal-point distance toward a pend-time PRE capture that the
    // SOURCE chain fogs at pane depth (the physically correct eye-to-pane medium); the SG
    // weight floors at farFadeWMin so the storm never fully vanishes. disableFarFade = w≡1 =
    // the shipped byte path (zero new GL commands).
    public static final boolean disableFarFade =
        Boolean.getBoolean("seamlessportals.disableFarFade");
    public static final double farFadeD0 =
        parseDoubleLever("seamlessportals.farFadeD0", 8.0, 0.0, 256.0);
    public static final double farFadeD1 =
        parseDoubleLever("seamlessportals.farFadeD1", 24.0, 0.5, 512.0);
    public static final double farFadeWMin =
        parseDoubleLever("seamlessportals.farFadeWMin", 0.15, 0.0, 1.0);

    private static double parseDoubleLever(String prop, double def, double min, double max) {
        String raw = System.getProperty(prop);
        if (raw == null) return def;
        try {
            double v = Double.parseDouble(raw.trim());
            if (v >= min && v <= max) return v;
        } catch (NumberFormatException ignored) {
        }
        // Must not throw (static initializer); an out-of-range lever falls back loudly-ish
        // via the RC block printing the EFFECTIVE value.
        return def;
    }

    // IS5-HIST (2026-08-10) — the window history stamp writes the PREVIOUS frame's capture,
    // DEFAULT ON. history=current left TAA's reprojected history read one frame WRONG at window
    // pixels — the ugly MB-off approach-blur (jitter=0 leg refuted jitter-alone; the blend's
    // wrong-time history is the surviving mechanism; MB-on masks it into the KEPT cool look).
    // Prev content = real temporal accumulation. Disable = the ugly blur on command.
    public static final boolean disableWindowHistoryPrev =
        Boolean.getBoolean("seamlessportals.disableWindowHistoryPrev");

    // IS5-XDIM (2026-08-10, migration/IS5_XDIM_DESIGN.md) — cross-dim dest-chain restore.
    // Cross-dim portal views run their dest pipeline to COMPLETION (composites + final — the
    // dimension's own pack look: the nether storm, the red bloom-fog) and are captured at the
    // finalize TAIL (or, under SG below, at the dest anchor boundary); same-dim views keep the
    // shipped pre-composite capture byte-identically. At most one POST view per dest dim/frame.
    // DEFAULT FLIPPED ON 2026-08-11 with SG below (the user residual gate: red fog + storm
    // delivered, double-grade gone; the lava/high-angle bloom washout ships DISCLOSED as the
    // top open item). The formal GL_TIME_ELAPSED A/B was NOT run — disclosed; the ~26-min live
    // SG session raised no fps complaint; the disable row is the escape.
    public static final boolean CROSS_DIM_DEST_CHAIN_DEFAULT = true;
    public static final boolean crossDimDestChain =
        Boolean.getBoolean("seamlessportals.crossDimDestChain")
            || (CROSS_DIM_DEST_CHAIN_DEFAULT
                && !Boolean.getBoolean("seamlessportals.disableCrossDimDestChain"));

    // IS5-ARRIVE (2026-08-10, migration/IS5_ARRIVE_DESIGN.md) — the sideways-arrival fix,
    // DEFAULT ON. On teleport frames a sideways-classified (|look·N| ≤ 0.2, near-plane) reverse
    // portal RENDERS with the V2 suspension withheld for that one portal/frame (armed V1 clip,
    // eye pinned +0.20) — the correct half-split instead of the round-2 hole or the round-1
    // wrong-content paint. Forward keeps the XFLICK skip; backward keeps render+suspension.
    // The disable row restores shipped round-2 bit-identically (sideways hole on command).
    public static final boolean disableSeamArrivalScope =
        Boolean.getBoolean("seamlessportals.disableSeamArrivalScope");

    // IS5-BLINK (2026-08-10) — the one-frame visibility-dropout FIX (query hysteresis),
    // DEFAULT ON. The new path consumes LAST frame's occlusion query; a single zero-sample
    // query (occlusion-edge noise, jitter, a skipped anchor frame) reads as a confident
    // "not visible" and drops the window for one frame — the user's "flicker even when far
    // away sometimes" (the old path decides same-frame and cannot blink). Hysteresis: invisible
    // only after 2 consecutive FALSE consumes; a single FALSE renders on credit (census hys=).
    // The [IS5-BLINK] detector logs raw T→F→T transitions regardless of this lever, so one leg
    // carries mechanism proof and fix proof independently. Disable = the B direction.
    public static final boolean disableQueryHysteresis =
        Boolean.getBoolean("seamlessportals.disableQueryHysteresis");

    // IS5-XFLICK (2026-08-10) — the crossing wrong-dest flicker FIX, DEFAULT ON. On the teleport
    // frame the reverse portal's query is history-wiped (unknown) and the speculative render
    // paints it full-screen from a camera on its plane (XTRACE: tp=true specR=1 dPl≈0 on 29/29
    // crossings) — one frame of the SOURCE world. The fix skips near-plane (<1 block) unknowns
    // on teleport frames only; the disable row reproduces the flicker on command (B direction).
    public static final boolean disableTeleportSpecSkip =
        Boolean.getBoolean("seamlessportals.disableTeleportSpecSkip");

    // IS5-XTRACE (2026-08-10) — the crossing-flicker discriminator trace, log-only, DEFAULT OFF.
    // ±8 frames around every teleport, one line per frame: dim, teleport flag, stamp-ran,
    // speculative counters, and per-stamped-slot [portal, layer, ARM-camera-vs-STAMP-camera
    // delta, camera-to-plane distance]. Built to discriminate three candidate mechanisms for the
    // user's "flicker of wrong dest when crossing" (new-path-only, B-leg-attributed): arm/stamp
    // camera mismatch, crossing-window clip suspension overpaint, speculative-render pop-in.
    public static final boolean is5CrossingTrace =
        Boolean.getBoolean("seamlessportals.is5CrossingTrace");

    // PERF-P2 (2026-08-10, user-picked) — iris pipeline PRE-WARM, DEFAULT ON. Compiles every
    // server dimension's shaderpack pipeline when a main-pipeline generation appears (world join /
    // pack reload / K toggle) instead of on the first portal LOOK into that dimension — the
    // P1-measured 3s mid-gameplay freeze (census gap 13:57:50-53, GL-burst-correlated). The
    // disable row reverts to on-demand first-look compiles.
    public static final boolean disablePipelinePrewarm =
        Boolean.getBoolean("seamlessportals.disablePipelinePrewarm");

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
