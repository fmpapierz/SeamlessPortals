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
    // (which S20 deletes per decision C2-5(b)) — the S20 sweep cannot strand it. Default FALSE:
    // every committed default stays byte-identical (shaders-ON = D8 dummy+notice; shaders-OFF =
    // the stencil family). OR'd with the JVM lever below; consumed by
    // PortalRenderer.switchToCorrectRenderer (the D8-EVO routing, lever-only until the IS3/IS4
    // default-flip decision Q-U1).
    public static boolean experimentalShaderpackPortalViews = false;

    /** The IS1 JVM lever (read once at class-init; wired via gradle -PshaderpackViews=true). */
    public static final boolean SHADERPACK_VIEWS_JVM_LEVER =
        Boolean.getBoolean("seamlessportals.shaderpackViews");

    /** True when the iris shaders-ON compat renderer is armed (config flag OR JVM lever). */
    public static boolean isShaderpackPortalViewsArmed() {
        return experimentalShaderpackPortalViews || SHADERPACK_VIEWS_JVM_LEVER;
    }
    
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
