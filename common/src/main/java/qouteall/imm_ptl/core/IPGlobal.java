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

    public static boolean doCheckGlError = true;

    public static boolean renderYourselfInPortal = true;
    
    public static boolean activeLoading = true;
    
    public static int netherPortalFindingRadius = 128;
    
    public static boolean teleportationDebugEnabled = false;
    
    public static boolean correctCrossPortalEntityRendering = true;

    // S20 increment 4: crossPortalEntityClipMechanism (the R3 clip-delivery A/B switch) is gone.
    // Checkpoint C4 decided for SUBMIT_ORDER_UNIFORM and the loser cleanup rode S20 — there is one
    // mechanism now, so there is nothing to select. See PerEntityClipBracket's class javadoc.

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
    public static boolean debugFrameBoundaryProbe = false;
    public static boolean debugSkipPortalEntities = false;
    // S15 (recursive-view entities): kills ONLY the new same-dim/loop-back entity pass
    // (renderPortalEntitiesSameDim) — flipping it ON restores the pre-S15 SAME-DIM pass
    // behavior exactly (the one-flip regression discriminator for the live A/B). Verify
    // fold (wf_b11fbd6f-f8a): CROSS-DIM frames may still differ from pre-S15 via the
    // deliberately un-levered fade-gate keying extension in
    // LevelRendererEntityVisibilityMixin (isDestExtracting) — attribute cross-dim
    // entity-pop deltas to that mixin change, not to this lever.
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
