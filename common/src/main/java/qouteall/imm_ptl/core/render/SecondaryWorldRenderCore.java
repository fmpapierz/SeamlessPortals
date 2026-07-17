package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.warwa.seamlessportals.mixin.client.CameraInvokerMixin;
import com.warwa.seamlessportals.mixin.client.GameRendererAccessorMixin;
import com.warwa.seamlessportals.mixin.client.LevelExtractorAccessor;
import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;
import qouteall.imm_ptl.core.render.renderer.RendererUsingStencil;
import qouteall.imm_ptl.core.render.context_management.FogRendererContext;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// S13-H port disposition: NEW ADDITIVE (registered forced deviation, S11-B §1 / S13H-driver-core-design.md
// §0). This is THE DRIVER CORE — the last inert link on the command→pixels chain. It has NO 1.21.3 analog
// because 26.2 SPLIT extract from render (render-core G1): IP's recursive `gameRenderer.renderLevel` for the
// destination world is REPLACED here by a re-expression of the runtime-proven stencil-direct dest-draw
// sequence (MOD:PortalContextSwitch.doFboRender:1533-2057, the C3-default stencilDirectMode branch —
// memories stencil-direct-rework-status / portal-stutter-is-mirror-fbo-cost). See the ARCHITECTURE VERDICT
// (S13H-driver-core-design.md §0): three source-grounded disqualifiers rule out recursing renderLevel from
// AFTER_TRANSLUCENT_TERRAIN (nested-framegraph blanking; the main FogRenderer WORLD slot + depth-clear
// corruption; GameRenderState.levelRenderState being public final). This class re-expresses IP's SEMANTICS
// (the same pass list — sky + clip + opaque + entities + translucent + clouds, masked by the live stencil,
// from the transformed camera, with vanilla terrain-visibility replaced by VisibleSectionDiscovery) onto
// 26.2's decomposed mechanics (extract → SOG delta feed → compileSections drain → armed discovery →
// prepareChunkRenders → renderGroup), WITHOUT nesting a framegraph.
//
// DISCIPLINE BOUNDARY (S11-B §1, honored): no com.warwa TYPE appears in any signature/field of this class;
// the core reaches private vanilla members through the PRE-EXISTING public com.warwa accessor-mixin
// INTERFACES exactly as the shell MyGameRenderer already does for the lightmap (MyGameRenderer.java:215,
// sanctioned S11-B §6 "accessor interfaces are substrate, not driver state"). The live block-era driver
// (PortalWorldManager / PortalContextSwitch) is not touched and stays suppressed flag-ON (I10 exclusivity).
//
// SIGN NOTE (D4.4): this class writes ZERO raw depth-compare / depth-range / stencil-op constants — all R5
// reversed-Z lives in the RendererUsingStencil choreography (Rows 1-16). The one depth-sensitive surface,
// the discovery frustum, is built from a CONVENTIONAL-Z culling projection (buildCullingProjection),
// NEVER the reversed-Z render projection — CUTOVER_SPEC §2.3 / I7 (the offsetToFullyIncludeCameraCube
// SPIKE-R1 hang). FrontClipping plane math stays column-form M·v (the qouteall FrontClipping bridge, I6).
// render-thread-logging discipline (memory render-thread-logging-log4j-stall): NO per-frame LOGGER (I5).
@Environment(EnvType.CLIENT)
public class SecondaryWorldRenderCore {

    public static final Minecraft client = Minecraft.getInstance();

    // ===== §2.2 core-owned state (no com.warwa duplication) =====================================
    // The armed-discovery one-shot "already scheduled UNCOMPILED" guard, per dim (re-expresses
    // MOD:PortalContextSwitch.portalCompileScheduled).
    private static final Map<ResourceKey<Level>, Set<Long>> portalCompileScheduled =
        new ConcurrentHashMap<>();
    // The SOG delta-window identity guard (re-expresses LAST_APPLIED_DELTA_WINDOW): the object
    // identity of the last-applied addedLoadedChunks set, per dim (§5.2 / memory
    // distant-chunk-vanish-sog-desync).
    private static final Map<ResourceKey<Level>, Object> lastAppliedDeltaWindow =
        new ConcurrentHashMap<>();

    // S14-A FIX-6 (M6): per-dim AtmosphericFogEnvironment.rainFogMultiplier values — the IP
    // StaticFieldsSwappingManager pattern applied to the field's 26.2 home (CUTOVER_SPEC §3 item
    // 3, designed-but-unimplemented until this audit). Step 6 installs the dest dim's stored
    // value around setupFog and restores the outer value in a finally; each dim keeps its own
    // smoothing state (IP's per-dim smoothing fidelity).
    private static final Map<ResourceKey<Level>, Float> destRainFogMultiplier =
        new ConcurrentHashMap<>();
    private static net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment
        cachedAtmosphericEnv;

    private static net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment
    getAtmosphericFogEnvironment() {
        if (cachedAtmosphericEnv == null) {
            for (net.minecraft.client.renderer.fog.environment.FogEnvironment env :
                qouteall.imm_ptl.core.mixin.client.accessor.IEFogRenderer_Environments.ip_getFogEnvironments()
            ) {
                if (env instanceof net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment atmo) {
                    cachedAtmosphericEnv = atmo;
                    break;
                }
            }
        }
        return cachedAtmosphericEnv;
    }

    // The block-atlas GpuSampler that ChunkSectionsToRender.renderGroup needs. Captured ONCE PER
    // FRAME by the shell at the OUTERMOST portal entry (getPortalLayer()==1), while mc.levelRenderer
    // is still the TRUE main renderer — NOT inside the invoke, where nested layers would resolve a
    // secondary whose sampler is null (§2.2 / MOD:PortalContextSwitch.java:1382-1396). Plain
    // CLAMP_TO_EDGE/LINEAR atlas sampler, not renderer-specific.
    private static GpuSampler mainChunkSampler;

    // Lazy portal-view SkyRenderer (the dest renderer's own is null — its addSkyPass never runs).
    // Recreated when the main target size OR IDENTITY changes (§1 Step 10.4; S14.27 F2).
    private static SkyRenderer portalSkyRenderer;
    private static int portalSkyW = -1;
    private static int portalSkyH = -1;
    private static RenderTarget portalSkyTarget;
    private static com.mojang.blaze3d.textures.GpuTextureView portalSkyColorView;
    private static com.mojang.blaze3d.textures.GpuTextureView portalSkyDepthView;

    // Standalone GPU buffers for the dest projection + fog UBOs. Never close()d (the GPU may still
    // read the previous frame's slice); GC reclaims when the reference is overwritten (§1 Steps 6-7,
    // MOD:PortalContextSwitch.writeProjectionBuffer:2378 / writePortalFogBuffer:2495).
    private static GpuBuffer portalProjGpuBuffer;
    private static GpuBuffer portalFogGpuBuffer;

    // The proven steady-state compile-scheduling budget (MOD:PortalContextSwitch.java:1285).
    private static final long PORTAL_VIEW_COMPILE_BUDGET_NS = 3_000_000L;

    public static void init() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(SecondaryWorldRenderCore::cleanUp);
        ClientWorldLoader.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT.register(dim -> cleanUp());
    }

    private static void cleanUp() {
        portalCompileScheduled.clear();
        lastAppliedDeltaWindow.clear();
        destRainFogMultiplier.clear();
        mainChunkSampler = null;
    }

    /**
     * S14-A FIX-1: called by {@link ClientWorldLoader}'s crossing promote/demote for BOTH dims of a
     * cross-dim crossing. The per-dim armed-discovery compile guard and the SOG delta-window
     * identity are role-scoped: stale scheduled-but-consumed entries from a dim's prior dest stint
     * would block recompiles when it next becomes a dest (the block-era clearCompileSchedule
     * lesson, MOD:PortalWorldManager promote/demote both clear it), and a stale delta-window
     * identity would mis-skip the first SOG delta feed after the role flip.
     */
    public static void onDimensionMainStatusChanged(ResourceKey<Level> dim) {
        portalCompileScheduled.remove(dim);
        lastAppliedDeltaWindow.remove(dim);
        // FIX-6 freshness: while the dim is main, vanilla drives the real multiplier — the stored
        // dest-side smoothing value goes stale; drop it so the next dest stint re-lerps cleanly.
        destRainFogMultiplier.remove(dim);
    }

    /**
     * SHELL HOOK (§2.2). Called by {@link MyGameRenderer#switchAndRenderTheWorld} at the OUTERMOST
     * portal entry ({@code PortalRendering.getPortalLayer()==1}), while {@code mc.levelRenderer} is
     * still the TRUE main renderer, to capture the block-atlas sampler {@code renderGroup} needs. The
     * caller passes the true main renderer; the sampler is read through the sanctioned com.warwa
     * accessor (the RESULT is a vanilla {@link GpuSampler} — no com.warwa type in this signature).
     */
    public static void captureMainChunkSampler(LevelRenderer trueMainRenderer) {
        mainChunkSampler =
            ((LevelRendererAccessorMixin) trueMainRenderer).seamlessportals$getChunkLayerSampler();
    }

    /**
     * THE INVOKE BODY (S13H-driver-core-design.md §1). Replaces the bare
     * {@code client.gameRenderer.renderLevel(...)} that re-rendered the ALREADY-EXTRACTED main-world
     * state. Runs INSIDE the shell's {@code invokeWrapper} — i.e. AFTER the shell swapped
     * level/renderer/camera/lightmap/fog/particles/buffers to the destination, backed up the bobbed
     * projection, and pushed an IDENTITY model-view; and AFTER the stencil was limited to this layer
     * ({@code glStencilFunc(EQUAL, layer)}) and the opening depth FAR-cleared by the
     * RendererUsingStencil choreography. The virtual-camera CONFIG (Step 1) already ran in the shell.
     *
     * @param destLevel      the destination world (== mc.level here)
     * @param destRenderer   the destination LevelRenderer (== mc.levelRenderer here)
     * @param newCamera      the virtual camera, configured by the shell (pos/level/rotation/tick/init)
     * @param renderDistance the WorldRenderInfo render distance (IP's graduated dest radius)
     * @param sourceLevel    the shell's PRE-SWAP world (the immediate OUTER layer's dim) — the restore
     *                       target for the Globals-UBO game time + diffuse lighting (V1-M2 nesting fix)
     * @param sourceCamera   the shell's PRE-SWAP camera (the immediate OUTER layer's camera) — the
     *                       restore target for the Globals-UBO camera position (V1-M2 nesting fix)
     */
    public static void renderDestWorld(
        ClientLevel destLevel, LevelRenderer destRenderer, Camera newCamera, int renderDistance,
        ClientLevel sourceLevel, Camera sourceCamera
    ) {
        Minecraft mc = client;
        DeltaTracker deltaTracker = mc.getDeltaTracker();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        ResourceKey<Level> destDim = destLevel.dimension();
        Vec3 destCameraPos = newCamera.position();

        // ===== Step 2 — resolve the per-dim substrate (EXTRACTOR-IDENTITY router) ===============
        // DEFECT-1 fix (S13-H verifier 2, MAJOR): route the main-dim short-circuit by the TRUE main dim
        // (RenderStates.originalPlayerDimension), NEVER ClientWorldLoader.getWorldExtractor(destDim) here.
        // The shell already swapped client.level to the DEST before this invoke (MyGameRenderer.java:259),
        // so getWorldExtractor's `CLIENT.level.dimension()==dimension` short-circuit
        // (ClientWorldLoader.java:378) is ALWAYS true inside the invoke and would return
        // CLIENT.levelExtractor — the MAIN global extractor bound to the MAIN LevelRenderState — for EVERY
        // portal, collapsing cross-dim onto the same-dim path (destLRS==main LRS => sharedState=true) and
        // silently skipping the entire dest extract + SOG delta feed + compileSections drain the core
        // exists to add. originalPlayerDimension is the layer-0 main dim, invariant across nesting depth:
        // ONLY it uses CLIENT.levelExtractor; every other dim (incl. an outer dest dim under nesting) uses
        // its construction-bound WORLD_EXTRACTOR_MAP instance (memory nether-block-freeze-orphaned-
        // extractor). With correct routing the :below coherence re-point is a genuine no-op by construction.
        LevelExtractor destExtractor;
        if (destDim == RenderStates.originalPlayerDimension) {
            destExtractor = mc.levelExtractor;
        }
        else {
            destExtractor = ClientWorldLoader.WORLD_EXTRACTOR_MAP.get(destDim);
            if (destExtractor == null) {
                // Defensive only: the portal view resolved the dest renderer (and thus created the dim +
                // its extractor) before this pass, so the map entry exists in practice. Fall back rather
                // than NPE on the unreachable path.
                destExtractor = ClientWorldLoader.getWorldExtractor(destDim);
            }
        }

        // Renderer-state coherence assert (re-expresses MOD:PortalContextSwitch.java:1159-1181): the
        // extractor's LevelRenderState must be the SAME OBJECT as the renderer's, or entities/clouds/
        // particles silently vanish (extract writes one, render reads the other). Holds by
        // construction in the qouteall port; keep the defensive re-point for any future promote path.
        LevelRenderState extractorState =
            ((LevelExtractorAccessor) (Object) destExtractor).seamlessportals$getLevelRenderState();
        LevelRenderState rendererState =
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getLevelRenderState();
        if (rendererState != extractorState) {
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$setLevelRenderState(extractorState);
        }
        LevelRenderState destLRS =
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getLevelRenderState();

        // Mode selector (§1): TRUE exactly when the dest dim is the currently-extracted main dim
        // (same-dim portals). Object-identity test — composes under nesting.
        boolean sharedState = (destLRS == mc.gameRenderer.gameRenderState().levelRenderState);

        ImmPtlViewArea viewArea =
            (ImmPtlViewArea) ((IEWorldRenderer) destRenderer).ip_getBuiltChunkStorage();
        // S14-A FIX-10: the tracker is READ at Step 9 (right before the discovery arm), AFTER
        // Step 5's extract — extract() can trip its render-distance allChanged, which REPLACES
        // sectionUpdateTracker (the §2.1 identity rule: "always re-read from the extractor, never
        // cache across frames" — previously honored across frames but violated WITHIN the pass).

        // ===== Step 3 — dest view matrix + frustum (the cameraTransformation consumption point) ==
        // 3.1 un-transformed rotation from the player angles the shell set on newCamera.
        Matrix4f destViewMatrix = new Matrix4f();
        newCamera.getViewRotationMatrix(destViewMatrix);
        // 3.2 THIS is where WorldRenderInfo.cameraTransformation is consumed (the 26.2 site replacing
        // IP's wrap of Matrix4f.rotation inside the recursive renderLevel). processTransformation runs
        // applyAdditionalTransformations over the WHOLE render-info stack (JOML column-form M·v — the
        // D4.4 anti-"fix" guard; never transpose).
        destViewMatrix = TransformationManager.processTransformation(newCamera, destViewMatrix);
        // 3.3 dest projection = the UNBOBBED extract-time main projection. Used for the cull/extract
        // frustum (3.4) + destCameraState.projectionMatrix (below): vanilla keeps
        // cameraState.projectionMatrix BOB-FREE (bob is applied only to a LOCAL projection copy in
        // renderLevel) and culls with the un-bobbed projection, so these stay un-bobbed to match.
        CameraRenderState mainCameraState =
            mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        Matrix4f destProjection = new Matrix4f(mainCameraState.projectionMatrix);
        // 3.3b dest DRAW projection = the POST-spin main-pass bobbed projection (base*bob*spin) with the
        // bob TRANSLATION scaled by this pass's getExtraModelViewScaling(), so the portal-view content
        // bobs IN SYNC with the frame AND the stencil aperture (S13-M Finding B).
        //   * P2: MixinGameRenderer captures the POST-spin ambient at renderLevel:557 (getBuffer), NOT
        //     PRE-spin — IP's dest re-enters the FULL renderLevel (IP:MyGameRenderer:231) and gets the
        //     SAME nausea/portal spin, so IP's dest is base*bob*spin like its main pass (spin==0 in
        //     normal play, so nothing changes there).
        //   * P3: the bob-translation scale by getExtraModelViewScaling() — a non-fuse scale-s portal's
        //     content sits at dest eye-depth s*z, so on-screen it must shift by s*t to track the
        //     portal-plane aperture's t (= IP's A2 bob ModifyArg, which multiplies the walk-bob translate
        //     by viewBobFactor*getExtraModelViewScaling(); a non-fuse portal IS in that product —
        //     PortalRendering:113-121). The portal is already pushed here (renderPortalContent runs inside
        //     doRenderPortal's pushPortalLayer bracket), so getExtraModelViewScaling() reflects
        //     this-portal scaling — the SAME value getCurrentProjectionMatrix returns for a nested
        //     aperture drawn into this content at Step 10.10, so aperture and content stay locked.
        // Only Step 7's RenderSystem draw projection uses this; the frustum (3.4) + destCameraState
        // .projectionMatrix stay on the un-bobbed base (destProjection), matching vanilla (which bobs only
        // its local rasterization projection). getPortalDrawProjection falls back to the un-bobbed base if
        // the capture has not run yet (first frame).
        Matrix4f destDrawProjection = RenderStates.getPortalDrawProjection(
            destProjection, PortalRendering.getExtraModelViewScaling());
        // 3.4 cull/extract frustum — CONVENTIONAL-Z culling projection (I7 / §2.3: feeding the
        // reversed-Z render projection to offsetToFullyIncludeCameraCube deterministically hangs).
        Frustum destFrustum = new Frustum(destViewMatrix, buildCullingProjection(destProjection));
        destFrustum.prepare(destCameraPos.x, destCameraPos.y, destCameraPos.z);
        // 3.5 capture it: makes LevelExtractor.extract skip applyFrustum (the 26.2 re-expression of
        // IP's setupRender HEAD-cancel) so the discovery-built visibleSections is authoritative and
        // the SPIKE-R1 reversed-Z hang path is never entered from extract.
        ((CameraInvokerMixin) newCamera).seamlessportals$setCullFrustum(destFrustum);
        ((CameraInvokerMixin) newCamera).seamlessportals$setCapturedFrustum(destFrustum);

        // Reposition the dest ViewArea grid to the dest camera (cross-dim only; the decomposition
        // never runs render(), which is vanilla's only repositionCamera caller — LevelRenderer.java:
        // 168-169). Same-dim shares the main renderer's grid (already positioned this frame); moving
        // it would corrupt the main frame (§4-I9).
        if (!sharedState && viewArea != null) {
            SectionPos camSec = SectionPos.of(destCameraPos);
            viewArea.repositionCamera(camSec);
        }

        // ===== Step 4 — dispatcher camera + camera render state =================================
        SectionRenderDispatcher dispatcher = destRenderer.sectionRenderDispatcher();
        Vec3 savedDispatcherCamPos = null;
        if (dispatcher != null) {
            if (sharedState) {
                // [same-dim] save the main camera position and restore it in the finally.
                savedDispatcherCamPos = mainCameraState.pos;
            }
            dispatcher.setCameraPosition(destCameraPos);
        }

        CameraRenderState destCameraState;
        CameraRenderState savedSharedCameraState = null;
        if (sharedState) {
            // [same-dim] a CORE-OWNED scratch, reassigned for the duration so entity submission and
            // the main frame's later passes keep reading the ORIGINAL cameraRenderState untouched
            // (§1 Step 4). destLRS is the MAIN state, so the field is reassigned + restored.
            savedSharedCameraState = destLRS.cameraRenderState;
            destCameraState = new CameraRenderState();
            destLRS.cameraRenderState = destCameraState;
        } else {
            destCameraState = destLRS.cameraRenderState;
        }

        newCamera.extractRenderState(destCameraState, partialTick);
        // dest-pass R13k analog: apply the transform onto the state AFTER extract, never by wrapping
        // the cached Camera.getViewRotationMatrix (consumed downstream by PerEntityClipBracket/R3).
        destCameraState.viewRotationMatrix.set(destViewMatrix);
        destCameraState.projectionMatrix.set(destProjection);
        // zero bob/hurt (defensive verbatim, MOD:PortalContextSwitch.java:1424-1430).
        if (destCameraState.entityRenderState != null) {
            destCameraState.entityRenderState.bob = 0.0f;
            destCameraState.entityRenderState.backwardsInterpolatedWalkDistance = 0.0f;
            destCameraState.entityRenderState.hurtTime = -1.0f;
            destCameraState.entityRenderState.hurtDuration = 1;
            destCameraState.entityRenderState.isDeadOrDying = false;
        }
        // [cross-dim] save fogData/fogType before the Step-6 overwrite (the proven leak guard —
        // Sodium reads cameraRenderState.fogData directly). [same-dim] scratch object, harmless.
        FogData savedFogData = destCameraState.fogData;
        FogType savedFogType = destCameraState.fogType;

        // Pre-capture SOURCE state for the Globals-UBO restore (§1 Step 8) BEFORE any dest work.
        // V1-M2 fix (S13-H verifier 1): capture the IMMEDIATE OUTER context (the shell's pre-swap
        // sourceCamera / sourceLevel), NOT the layer-0 RenderStates.originalCamera/originalPlayerDimension.
        // The shell swaps mc.level/camera to the dest before this invoke, so its pre-swap oldWorld/oldCamera
        // ARE the outer layer's context by construction (proven core PortalContextSwitch:1519-1520 captured
        // mainCamera.position()/mc.level.getGameTime() before the swap). At layer 1 these equal the layer-0
        // originals (zero rung-1 change); under nesting they correctly restore the OUTER dest layer's
        // CameraPos/GameTime so the outer pass's remaining draws (Step-10.11 clouds, tail) read coherent
        // Globals-UBO values instead of the original-dim ones.
        Vec3 savedCameraPos = sourceCamera.position();
        long savedLevelGameTime = sourceLevel.getGameTime();

        boolean diffuseChangedToDest = false;
        try {
            // ===== Step 5 — dest EXTRACT + SOG delta feed + compileSections drain [cross-dim] =====
            if (!sharedState) {
                try {
                    destExtractor.extract(deltaTracker, newCamera, partialTick);
                } finally {
                    // (b) SOG delta feed (§5.2 / memory distant-chunk-vanish-sog-desync): the
                    // decomposition never runs destRenderer.render(), vanilla's only delta consumer,
                    // so feed the dest SOG here under the set-object IDENTITY window guard — applied
                    // once per flip window, idempotent within a window, NEVER lost on throw.
                    ChunkLoadingRenderState destDeltas = destLRS.chunkLoadingRenderState;
                    if (lastAppliedDeltaWindow.get(destDim) != destDeltas.addedLoadedChunks) {
                        lastAppliedDeltaWindow.put(destDim, destDeltas.addedLoadedChunks);
                        SectionOcclusionGraph destSog = destRenderer.sectionOcclusionGraph();
                        LongOpenHashSet addedLoaded = destDeltas.addedLoadedChunks;
                        LongOpenHashSet removedLoaded = destDeltas.removedLoadedChunks;
                        LongOpenHashSet addedEmpty = destDeltas.addedEmptySections;
                        LongOpenHashSet removedEmpty = destDeltas.removedEmptySections;
                        destSog.updateLoadedChunks(addedLoaded, removedLoaded);
                        destSog.updateEmptySections(addedEmpty, removedEmpty);
                    }
                    // (c) compileSections drain (§5.1 / memory ow-holes-consumed-compile-queue):
                    // extract() queued SectionUpdateRenderStates and set the sections not-dirty; the
                    // only vanilla consumer is the private compileSections inside render(), which never
                    // runs here — invoke it directly or the sections strand dirty=false+UNCOMPILED.
                    // Safe mid-main-framegraph (no GPU RenderPass open; compileAsync scheduling). The
                    // GPU upload half rides MyRenderHelper.earlyRemoteUpload (pre-frame pump, already
                    // wired flag-ON) — required BY CONSTRUCTION here (no render() upload tail runs).
                    ((LevelRendererAccessorMixin) destRenderer)
                        .seamlessportals$invokeCompileSections(destCameraState);
                }
            }

            // S14.23 (live-defect hunt, sky-state track): dest SKY extraction for never-main dims.
            // LevelExtractor.extract fills skyRenderState ONLY while levelRenderer.skyRenderer() is
            // non-null (mc262 LevelExtractor:182-186), and only vanilla's addSkyPass ever constructs
            // skyRenderer — which the decomposed dest pass never runs. A never-main dest dim would
            // keep default/stale sky state forever (OW-as-dest previously worked only through the
            // ACCIDENTAL invariant that demotion preserves the renderer instance's skyRenderer).
            // Complete the decomposition's parity with IP's always-extracted dest sky: run the
            // extraction explicitly with the core-owned portalSkyRenderer as the executor, mirroring
            // vanilla's own gate, with the same camera the dest extract used.
            if (!sharedState && destRenderer.skyRenderer() == null) {
                SkyRenderer portalSr = getOrCreatePortalSkyRenderer(destRenderer);
                if (portalSr != null) {
                    portalSr.extractRenderState(
                        destLevel, partialTick, newCamera, destLRS.skyRenderState
                    );
                }
            }

            // ===== Step 6 — dest FOG (R9): compute-only probe + core-owned standalone buffer =====
            FogRenderer fr =
                ((GameRendererAccessorMixin) mc.gameRenderer).seamlessportals$getFogRenderer();
            // S14-A FIX-6 (M6, CUTOVER_SPEC §3 item 3 — finally implemented): setupFog is
            // compute-only for the UBO but NOT for FogRenderer.FOG_ENVIRONMENTS — the shared
            // AtmosphericFogEnvironment lerps its private rainFogMultiplier toward THIS query's
            // level (updateRainFogState, deltaTicks*0.2 per call). Unbracketed, every dest pass
            // drags the MAIN world's rain fog toward the dest's (raining OW + nether portal on
            // screen = visibly weakened/unstable main rain fog, and the dest inherits residual OW
            // offsets). Install the dest dim's own stored smoothing value for the call; restore
            // the outer value in the finally (throw-safe); persist the updated dest value per-dim.
            FogData destFogData;
            var atmosphericEnv = getAtmosphericFogEnvironment();
            float outerRainFogMultiplier = 0f;
            if (atmosphericEnv != null) {
                var atmoAccess =
                    (qouteall.imm_ptl.core.mixin.client.accessor.IEAtmosphericFogEnvironment) atmosphericEnv;
                outerRainFogMultiplier = atmoAccess.ip_getRainFogMultiplier();
                atmoAccess.ip_setRainFogMultiplier(
                    destRainFogMultiplier.getOrDefault(destDim, 0f));
            }
            try {
                // compute-only for the UBO, ring-buffer-safe (§3.1: setupFog never writes the
                // WORLD slot). Radius = the render-info render distance (IP's dest radius).
                destFogData = fr.setupFog(
                    newCamera, WorldRenderInfo.getRenderDistance(), deltaTracker, 0f, destLevel
                );
            } finally {
                if (atmosphericEnv != null) {
                    var atmoAccess =
                        (qouteall.imm_ptl.core.mixin.client.accessor.IEAtmosphericFogEnvironment) atmosphericEnv;
                    destRainFogMultiplier.put(destDim, atmoAccess.ip_getRainFogMultiplier());
                    atmoAccess.ip_setRainFogMultiplier(outerRainFogMultiplier);
                }
            }
            destCameraState.fogData = destFogData;
            destCameraState.fogType = FogType.NONE;
            // S13-I (first-photons black-seam fix): publish the live dest fog color for the R5 Row-16
            // backdrop fill. RendererUsingStencil.replaceFrameBufferClearing (invoked at Step 10.3 below)
            // seals the WHOLE stencil opening with FogRendererContext.getCurrentFogColor BEFORE the dest
            // sky/terrain draw over it — IP's seamless atmospheric backdrop so any horizon seam (dest sky
            // meeting dest terrain) blends into the dest atmosphere. IP read this from the live fog
            // statics (the dest color, already set up); the 26.2 statics are GONE (R9), so the driver core
            // is the authority — publish exactly the color the dest terrain is being fogged with
            // (destFogData.color, the same source getFogColorOf returns). Without this the fill used the
            // Vec3.ZERO stub and the seam showed BLACK (the reported thin black line at eye level).
            FogRendererContext.setCurrentRenderedFogColor(
                new Vec3(destFogData.color.x, destFogData.color.y, destFogData.color.z)
            );
            // Write the fog UBO to a CORE-OWNED standalone GpuBuffer (never fr.updateBuffer — that
            // corrupts the main WORLD slot: dark clipping artifacts across the whole world).
            GpuBufferSlice destFogBuffer = writeFogSlice(destFogData);

            // ===== Step 7 — dest PROJECTION set (inside the shell's save/restore bracket) =========
            // Always override, even with no oblique/scale transform: the main path pushed the BOBBED
            // projection before the dispatch fired. The shell brackets this with a PER-INVOCATION local
            // save of getProjectionMatrixBuffer()+getProjectionType() and a setProjectionMatrix(...)
            // restore on exit (recursion-safe, V2-DEFECT-2) — only the SET is core work.
            // S13-M Finding B: set the bobbed+scaled draw projection (base*bob*spin, bob scaled by
            // getExtraModelViewScaling() — see Step 3.3b) so the dest content bobs in sync with the frame
            // and the aperture. This is the ONE site that takes the bobbed matrix; the frustum (3.4) and
            // destCameraState.projectionMatrix stay on the un-bobbed base, exactly as vanilla bobs only
            // its local rasterization projection while cameraState.projectionMatrix stays bob-free.
            RenderSystem.setProjectionMatrix(writeProjectionSlice(destDrawProjection), ProjectionType.PERSPECTIVE);

            // ===== Step 8 — Globals UBO for the dest pass ========================================
            RenderTarget mainRT = mc.gameRenderer.mainRenderTarget();
            ((GameRendererAccessorMixin) mc.gameRenderer).seamlessportals$getGlobalSettingsUniform().update(
                mainRT.width, mainRT.height,
                mc.gameRenderer.gameRenderState().optionsRenderState.glintStrength,
                destLevel.getGameTime(), deltaTracker,
                mc.gameRenderer.gameRenderState().optionsRenderState.menuBackgroundBlurriness,
                destCameraPos,
                // V1-M1 fix: mirror vanilla's RGSS texture-filtering flag (26.2 GameRenderer.render:420),
                // not a hardcoded false — else the RESTORE below clears the flag for the rest of the frame.
                mc.gameRenderer.gameRenderState().optionsRenderState.textureFiltering
                    == TextureFilteringMethod.RGSS
            );

            // ===== Step 9 — visibleSections: ARMED discovery ====================================
            ObjectArrayList<SectionRenderDispatcher.RenderSection> resultList =
                ((IEWorldRenderer) destRenderer).portal_getChunkInfoList();
            // S14.6 (fix-verify MINOR): re-read the grid too — Step 5's extract can consume
            // shouldInvalidateCompiledGeometry (RD change, or the FIX-9 reload cascade on ANY
            // main reload with a portal visible), which RELEASES and REPLACES the renderer's
            // viewArea (same §2.1 identity class as the FIX-10 tracker re-read below). The
            // Step-3 repositionCamera on the pre-extract read stays as-is (a replacement grid is
            // repositioned by invalidateCompiledGeometry itself).
            viewArea = (ImmPtlViewArea) ((IEWorldRenderer) destRenderer).ip_getBuiltChunkStorage();
            if (viewArea != null) {
                RenderRegionCache cache = new RenderRegionCache();
                Set<Long> schedSet =
                    portalCompileScheduled.computeIfAbsent(destDim, k -> new HashSet<>());
                // FIX-10: post-extract read — see the Step-2 note.
                SectionUpdateTracker sut = destExtractor.sectionUpdateTracker;
                VisibleSectionDiscovery.armCompileScheduling(
                    destLevel, sut, cache, schedSet, PORTAL_VIEW_COMPILE_BUDGET_NS
                );
                // IP-verbatim call shape (IP:MixinLevelRenderer.java:252-257): the offset frustum is
                // built from destFrustum (conventional-Z, I7). Discovery auto-disarms in its finally.
                VisibleSectionDiscovery.discoverVisibleSections(
                    destLevel, viewArea, newCamera,
                    new Frustum(destFrustum).offsetToFullyIncludeCameraCube(8),
                    resultList
                );
            }

            // ===== Step 10 — the DRAW sequence (masked by the live stencil) =======================
            // ChunkSectionsToRender is produced by prepareChunkRenders — now that visibleSections is
            // authoritative. NEVER bail on maxIndices==0 (draws are empty while async meshes compile).
            ChunkSectionsToRender destChunks = destRenderer.prepareChunkRenders(destViewMatrix);

            GpuBufferSlice savedShaderFog = RenderSystem.getShaderFog();
            RenderSystem.setShaderFog(destFogBuffer);
            try {
                // 10.3 Row-16 background fill: the driver-invoked re-expression of IP's redirectClearing
                // anchor (the decomposition has no clear to replace). RendererUsingStencil already
                // implements Row 16 + the doRenderSky gate.
                IPCGlobal.renderer.replaceFrameBufferClearing();

                // 10.4 dest sky (gated on doRenderSky — fuse-view portals set it false). Sky draws
                // BEFORE the clip is armed (the dome spans both sides of the plane).
                // S14.24 lever: debug_skip_portal_sky attributes residue to this draw.
                if (WorldRenderInfo.getTopRenderInfo().doRenderSky && !IPGlobal.debugSkipPortalSky) {
                    renderPortalSky(destRenderer, destLRS, destFogBuffer, destViewMatrix);
                }

                // 10.5 inner clip + portal draw state (IP's per-layer bracket).
                FrontClipping.setupInnerClipping(
                    PortalRendering.getActiveClippingPlane(), destViewMatrix, -FrontClipping.ADJUSTMENT
                );
                if (PortalRendering.isRenderingOddNumberOfMirrors()) {
                    MyRenderHelper.applyMirrorFaceCulling();
                }
                boolean depthClamp = IPGlobal.enableDepthClampForPortalRendering;
                if (depthClamp) {
                    CHelper.enableDepthClamp();
                }

                try {
                    // S14.24 lever: debug_skip_portal_terrain attributes residue to the renderGroup draws.
                    boolean canDraw = mainChunkSampler != null && destChunks.maxIndicesRequired() > 0
                        && !IPGlobal.debugSkipPortalTerrain;
                    if (canDraw) {
                        // 10.6 solid+cutout into the OPAQUE output (== the real main target), masked by
                        // the live stencil; LOAD, no clear.
                        destChunks.renderGroup(ChunkSectionLayerGroup.OPAQUE, mainChunkSampler);
                    }

                    // 10.7/10.8 dest lighting + entities (cross-dim only — same-dim entityRenderStates
                    // was already consumed+cleared by the main pass; re-running the shared main
                    // dispatcher mid-frame is unsafe — §6.1 documented gap).
                    if (!sharedState) {
                        MyGameRenderer.resetDiffuseLighting(); // mc.level == dest here
                        diffuseChangedToDest = true;
                        renderPortalEntities(destRenderer, destLRS, destViewMatrix);
                    }

                    if (canDraw) {
                        // 10.9 dest translucent (dest translucent target is null → falls back to the
                        // main target, blended over the opaque dest terrain).
                        destChunks.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, mainChunkSampler);
                    }

                    // 10.10 nested portal layers — the driver-invoked re-expression of IP's per-pass
                    // translucent hook (what recursed nested portals in IP). PortalRendering.isRendering
                    // is true, so the post-pass branch runs setStencilStateForWorldRendering (§5).
                    IPCGlobal.renderer.onBeforeTranslucentRendering(destViewMatrix);

                    // 10.11 dest clouds — DELIBERATELY SKIPPED (S13-J DOCUMENTED DEVIATION, restore at
                    // S18). The re-expression is preserved (renderPortalClouds below) but NOT called: on
                    // 26.2 a same-dim portal has destRenderer == mc.levelRenderer, so
                    // destRenderer.cloudRenderer() is the SAME CloudRenderer whose utb/ubo
                    // MappableRingBuffers the MAIN pass's LevelRenderer.addCloudsPass draws into LATER in
                    // the same framegraph submit. Drawing dest clouds here mid-submit rotates/fences those
                    // ring-buffer slots inside the current submit, so the main pass's currentBuffer()
                    // awaitCompletion sees a fence for the in-flight submit and throws
                    // "Cannot wait on a fence for the current submit" (GlCommandEncoder.awaitSubmit) —
                    // the deterministic crash-2026-07-16_11.50/11.58 (multiple portals multiply the
                    // mid-frame rotations, making it fire). This is the SAME shared-WORLD-ring-buffer
                    // hazard CUTOVER_SPEC §3.2 warned about for FOG (solved there with a core-owned
                    // standalone buffer) manifesting in CLOUDS. IP isolates per-dim cloud geometry via
                    // CloudContext, but that class's own header defers reconciling its per-dim cache
                    // against 26.2's single CloudRenderer ring buffer to U10/S12 (still inert) — building
                    // that isolation now is disproportionate at rung-1 triage, so dest clouds are OMITTED
                    // exactly like the already-accepted weather + world-border omission (S13H design §6.3),
                    // deviation-until-S18. Sky (Step 10.4) is unaffected: it uses the core-owned
                    // portalSkyRenderer, not the shared main renderer's buffers.
                } finally {
                    FrontClipping.disableClipping();
                    if (PortalRendering.isRenderingOddNumberOfMirrors()) {
                        MyRenderHelper.recoverFaceCulling();
                    }
                    if (depthClamp) {
                        CHelper.disableDepthClamp();
                    }
                    // Defensive stencil re-assert (§1 Step 10.13, mirrors
                    // MOD:PortalContextSwitch.java:2028-2030): cheap insurance against a feature-renderer
                    // or compat mod touching raw stencil during the entity/nested draws. Stencil-only
                    // (EQUAL + KEEP) — direction-independent, adds NO depth/reversed-Z surface (W3).
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    RendererUsingStencil.setStencilLimitation(PortalRendering.getPortalLayer());
                }
            } finally {
                if (savedShaderFog != null) {
                    RenderSystem.setShaderFog(savedShaderFog);
                }
            }
        } finally {
            // ===== Step 10.13 finally — restore everything the core changed =====================
            // Restore the Globals UBO with the SOURCE camera + game time (the main frame's remaining
            // passes — translucent-after-terrain, clouds, hand — read it).
            RenderTarget mainRT = mc.gameRenderer.mainRenderTarget();
            ((GameRendererAccessorMixin) mc.gameRenderer).seamlessportals$getGlobalSettingsUniform().update(
                mainRT.width, mainRT.height,
                mc.gameRenderer.gameRenderState().optionsRenderState.glintStrength,
                savedLevelGameTime, deltaTracker,
                mc.gameRenderer.gameRenderState().optionsRenderState.menuBackgroundBlurriness,
                savedCameraPos,
                // V1-M1 fix: mirror vanilla's RGSS texture-filtering flag (26.2 GameRenderer.render:420).
                mc.gameRenderer.gameRenderState().optionsRenderState.textureFiltering
                    == TextureFilteringMethod.RGSS
            );
            // Source-dim diffuse lighting (the outer frame's hand/translucent needs it; IP restored it
            // via the outer pass's own hook — the decomposition restores explicitly). V1-M2-consistent:
            // restore the IMMEDIATE OUTER dim (sourceLevel), not the layer-0 original — identical at
            // rung 1, correct under nesting (the outer dest layer's clouds/tail want its own lighting).
            if (diffuseChangedToDest) {
                DimensionType srcDimType = sourceLevel.dimensionType();
                mc.gameRenderer.lighting().updateLevel(srcDimType.cardinalLightType());
            }
            // [cross-dim] restore fogData/fogType. [same-dim] restore the cameraRenderState reference
            // + dispatcher camera position (the main frame's later passes must not inherit the dest).
            if (sharedState) {
                destLRS.cameraRenderState = savedSharedCameraState;
                if (dispatcher != null && savedDispatcherCamPos != null) {
                    dispatcher.setCameraPosition(savedDispatcherCamPos);
                }
            } else {
                destCameraState.fogData = savedFogData;
                destCameraState.fogType = savedFogType;
            }
        }
    }

    // ===== §1 Step 10.4 — dest sky (re-expresses MOD:PortalContextSwitch.renderPortalSky:895) =====
    private static void renderPortalSky(
        LevelRenderer destRenderer, LevelRenderState destLRS,
        GpuBufferSlice destFogBuffer, Matrix4f destViewMatrix
    ) {
        try {
            SkyRenderState sky = destLRS.skyRenderState;
            if (sky == null || sky.skybox == DimensionType.Skybox.NONE) {
                return;
            }
            SkyRenderer sr = getOrCreatePortalSkyRenderer(destRenderer);
            if (sr == null) {
                return;
            }
            Matrix4fStack mv = RenderSystem.getModelViewStack();
            mv.pushMatrix();
            mv.mul(destViewMatrix);
            try {
                RenderSystem.setShaderFog(destFogBuffer);
                if (sky.skybox == DimensionType.Skybox.END) {
                    sr.renderEndSky();
                } else {
                    PoseStack poseStack = new PoseStack();
                    sr.renderSkyDisc(sky.skyColor);
                    sr.renderSunriseAndSunset(poseStack, sky.sunAngle, sky.sunriseAndSunsetColor);
                    sr.renderSunMoonAndStars(
                        poseStack, sky.sunAngle, sky.moonAngle, sky.starAngle,
                        sky.moonPhase, sky.rainBrightness, sky.starBrightness
                    );
                    if (sky.shouldRenderDarkDisc) {
                        sr.renderDarkDisc();
                    }
                }
            } finally {
                mv.popMatrix();
            }
        } catch (Throwable t) {
            // Sky is non-critical; the flat dest-fog fill (Row 16) remains.
        }
    }

    private static SkyRenderer getOrCreatePortalSkyRenderer(LevelRenderer destRenderer) {
        RenderTarget mainRT = client.gameRenderer.mainRenderTarget();
        if (mainRT == null) {
            return null;
        }
        // S14.27 F2 (round-2 painter hunt): recreate on TARGET IDENTITY change, not just size —
        // the captured RenderTarget object (and its texture-view identities) can be swapped out
        // under the same dimensions (GUI-portal main-target swap; target churn), leaving the
        // SkyRenderer bound to a STALE target whose FBO lacks the live stencil content. Mirrors
        // vanilla's shouldResetSkyRenderer semantics.
        if (portalSkyRenderer == null || portalSkyW != mainRT.width || portalSkyH != mainRT.height
            || portalSkyTarget != mainRT
            || portalSkyColorView != mainRT.getColorTextureView()
            || portalSkyDepthView != mainRT.getDepthTextureView()
        ) {
            if (portalSkyRenderer != null) {
                try {
                    portalSkyRenderer.close();
                } catch (Throwable ignored) {
                }
            }
            AtlasManager atlas =
                ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getAtlasManager();
            portalSkyRenderer = new SkyRenderer(client.getTextureManager(), atlas, mainRT);
            portalSkyW = mainRT.width;
            portalSkyH = mainRT.height;
            portalSkyTarget = mainRT;
            portalSkyColorView = mainRT.getColorTextureView();
            portalSkyDepthView = mainRT.getDepthTextureView();
        }
        return portalSkyRenderer;
    }

    // ===== §1 Step 10.11 — dest clouds (re-expresses renderPortalClouds:947) =====================
    // INTENTIONALLY NOT CALLED (S13-J documented deviation — see the Step 10.11 skip note above). This
    // faithful re-expression is retained ONLY as the S18 restoration reference; wiring it back requires
    // per-dim cloud-buffer isolation first (CloudContext reconciled against 26.2's single CloudRenderer
    // ring buffer), or it re-introduces the "Cannot wait on a fence for the current submit" crash. Do
    // NOT re-add the call at rung 1.
    @SuppressWarnings("unused")
    private static void renderPortalClouds(
        LevelRenderer destRenderer, LevelRenderState destLRS,
        CameraRenderState destCameraState, Matrix4f destViewMatrix, float partialTick
    ) {
        var ors = client.gameRenderer.gameRenderState().optionsRenderState;
        CloudStatus cloudStatus = ors.cloudStatus;
        if (cloudStatus == CloudStatus.OFF) {
            return;
        }
        if (ARGB.alpha(destLRS.cloudColor) <= 0) {
            return;
        }
        if (destCameraState.pos == null) {
            return;
        }
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.mul(destViewMatrix);
        try {
            destRenderer.cloudRenderer().render(
                destLRS.cloudColor, cloudStatus, destLRS.cloudHeight, ors.cloudRange,
                destCameraState.pos, destLRS.gameTime, partialTick
            );
        } catch (Throwable t) {
            // Clouds are non-critical.
        } finally {
            mv.popMatrix();
        }
    }

    // ===== §1 Step 10.8 — dest entities/block-entities/particles (re-expresses renderPortalEntities:979) =
    private static void renderPortalEntities(
        LevelRenderer destRenderer, LevelRenderState destLRS, Matrix4f destViewMatrix
    ) {
        try {
            LevelRendererAccessorMixin acc = (LevelRendererAccessorMixin) destRenderer;
            SubmitNodeStorage storage = acc.seamlessportals$getSubmitNodeStorage();
            if (storage == null) {
                return;
            }
            acc.seamlessportals$invokeSubmitFeatures(destLRS, storage, false);
            Matrix4fStack mv = RenderSystem.getModelViewStack();
            mv.pushMatrix();
            mv.mul(destViewMatrix);
            try {
                acc.seamlessportals$getFeatureRenderDispatcher().renderAllFeatures(storage);
            } finally {
                mv.popMatrix();
            }
        } catch (Throwable t) {
            // Entities are non-critical; terrain + sky already drew.
        }
    }

    // ===== §2.3 / I7 — CONVENTIONAL-Z culling projection for the discovery frustum ================
    // Mirrors Camera.createProjectionMatrixForCulling (26.2:Camera.java:179-189) — private, so
    // reconstructed here. fovy/aspect are read from the (reversed-Z) render projection's scale terms
    // (m00/m11 are INVARIANT under the reversed-Z near/far swap); the Z terms are rebuilt conventional.
    private static Matrix4f buildCullingProjection(Matrix4f renderProjection) {
        float m11 = renderProjection.m11();
        float m00 = renderProjection.m00();
        double optionsFovDeg = client.options.fov().get();
        float fovyRad;
        float aspect;
        if (m11 > 1.0e-4f && m00 > 1.0e-4f) {
            fovyRad = (float) (2.0 * Math.atan(1.0 / m11));
            aspect = m11 / m00;
        } else {
            fovyRad = (float) Math.toRadians(optionsFovDeg);
            aspect = (float) client.getWindow().getWidth() / client.getWindow().getHeight();
        }
        // fovForCulling = max(renderFov, options.fov) — vanilla's conservative widening.
        float fovForCulling = Math.max(fovyRad, (float) Math.toRadians(optionsFovDeg));
        float far = Math.max(
            client.options.getEffectiveRenderDistance() * 16.0f * 4.0f,
            client.options.cloudRange().get() * 16.0f
        );
        boolean zZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        return new Matrix4f().perspective(fovForCulling, aspect, 0.05f, far, zZeroToOne);
    }

    // ===== §1 Step 7 — standalone projection UBO (re-expresses writeProjectionBuffer:2378) ========
    private static GpuBufferSlice writeProjectionSlice(Matrix4f matrix) {
        ByteBuffer buf = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        matrix.get(buf);
        buf.position(64);
        buf.flip();
        // fresh buffer per call; do NOT close the old one (the GPU may still be reading it).
        portalProjGpuBuffer = RenderSystem.getDevice().createBuffer(
            () -> "seamlessportals_portal_proj", GpuBuffer.USAGE_UNIFORM, buf
        );
        return portalProjGpuBuffer.slice();
    }

    // ===== §1 Step 6 — standalone fog UBO (re-expresses writePortalFogBuffer:2495) ================
    private static GpuBufferSlice writeFogSlice(FogData fog) {
        // FOG_UBO_SIZE = 48 (std140: vec4(16) + 6*float(24) + 8 padding).
        ByteBuffer buf = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
        Std140Builder.intoBuffer(buf)
            .putVec4(fog.color)
            .putFloat(fog.environmentalStart)
            .putFloat(fog.environmentalEnd)
            .putFloat(fog.renderDistanceStart)
            .putFloat(fog.renderDistanceEnd)
            .putFloat(fog.skyEnd)
            .putFloat(fog.cloudEnd);
        buf.position(48);
        buf.flip();
        portalFogGpuBuffer = RenderSystem.getDevice().createBuffer(
            () -> "seamlessportals_portal_fog", GpuBuffer.USAGE_UNIFORM, buf
        );
        return portalFogGpuBuffer.slice();
    }
}
