package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import org.joml.Matrix4fStack;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.ducks.IEParticleManager;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.render.context_management.DimensionRenderHelper;
import qouteall.imm_ptl.core.render.context_management.FogRendererContext;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.my_util.LimitedLogger;

import java.util.Stack;
import java.util.function.Consumer;

// S11-B (Slice A) port disposition: REPLACE-BY (current-mod-render §1.2 — supersedes the mod's
// PortalContextSwitch as the world-switch driver). This is the qouteall-path world-context switch the
// held entity-era caller pins: ClientTeleportationManager.java:423/452 write the public static
// `vanillaTerrainSetupOverride`. Ports IP's MyGameRenderer (IP :52) — the SAVE / SWAP / invoke-render /
// RESTORE choreography of switchAndRenderTheWorld — re-expressed onto the mod's PROVEN 26.2 mechanisms
// (PortalContextSwitch.withSwitchedWorld). Held/inert until S13.
//
// SCOPE LINE (what this class is vs is not). IP's switchAndRenderTheWorld is the world-CONTEXT switch:
// it saves mc.level/renderer/camera/lightmap/fog/particles/buffers, swaps them to the destination, then
// hands the actual render to an `invokeWrapper` supplied by the CALLER (PortalRenderer.invokeWorldRendering,
// U10/S12 — which sets up the stencil/depth/clip around the render). The DRIVER CORE — the per-dim
// LevelExtractor.extract(destCamera) + compileSections drain + LevelRenderState re-point that make 26.2's
// render actually draw the DESTINATION world (render-core G1; the ~500-line proven body at
// MOD:PortalContextSwitch.java:1533-2057) — lives behind that invokeWrapper and is wired by the
// CUTOVER_SPEC at S12/S13. This port keeps IP's invoke shape and translates the surrounding swap set;
// it does NOT inline the mod's driver-core lambda (that would drag com.warwa render types into a
// qouteall class and duplicate the live driver). The extract()/compileSections PAIRING rule (memory
// ow-holes-consumed-compile-queue) and the reversed-Z depth choreography (R5) belong to that S12/S13
// wiring, not to this context shell.
//
// 26.2 RE-EXPRESSIONS applied to switchAndRenderTheWorld (each is api-map-sanctioned; none deviates IP
// logic — they translate GONE 1.21.3 calls to the proven 26.2 mechanism the mod already ships):
//   * renderLevel(getTimer())            -> renderLevel(getDeltaTracker())  (render-core C2); the call
//        survives public (mc262 GameRenderer.java:525) but on 26.2 renders from the ALREADY-EXTRACTED
//        state (G1) — see the SCOPE LINE: the dest extract is driver-core (S12/S13).
//   * client.getProfiler()               -> Profiler.get() -> ProfilerFiller (G16).
//   * RenderSystem.getProjectionMatrix() save + resetProjectionMatrix() restore (G27/G19, GONE)
//        -> save RenderSystem.getProjectionMatrixBuffer()+getProjectionType() into PER-INVOCATION LOCALS
//        and re-set via setProjectionMatrix(...) on exit (proven at MOD:PortalContextSwitch.java:1800-...).
//        The saved-Matrix4f field is dropped. NOTE (S13-H V2-DEFECT-2): must be locals, NOT
//        RenderSystem.backup/restoreProjectionMatrix() — that static pair is a SINGLE slot and the driver
//        core makes portal nesting live, so an inner backup() would clobber the outer layer's saved slot.
//   * IERenderSystem.ip_getModelViewStack()/ip_setModelViewStack(new Matrix4fStack) swap (G28, the
//        stack-object swap is meaningless on 26.2) -> push identity on RenderSystem.getModelViewStack()
//        and pop on restore (proven at MOD:PortalContextSwitch.java:1790-1792). No IERenderSystem needed.
//   * client.renderBuffers()             -> client.gameRenderer.renderBuffers() (G15, moved off Minecraft).
//   * client.gameRenderer.lightTexture() save + ip_setLightmapTextureManager(LightTexture) (G20, GONE)
//        -> the per-dim Lightmap: save the main Lightmap via the mod accessor seamlessportals$getLightmap()
//        (the exact identity RenderStates.onTotalRenderEnd + ClientWorldLoader's conflict guard use),
//        install helper.lightmapTexture (a 26.2 Lightmap), restore. helper.lightmapTexture.updateLightTexture(0)
//        first-visit prime -> helper.updateAndRender(virtualCamera, partialTick) (the mod's proven
//        Lightmap.render(extract) driver; the virtual-camera CONFIG is S13 driver-core).
//   * getSectionRenderDispatcher().ip_setFixedBuffers(...) secondary-buffer isolation (IESectionRenderDispatcher
//        is not held; 26.2 buffer model is StagingBuffer, G15/G26) -> DROPPED here; flagged for the
//        CUTOVER_SPEC (S12) to decide whether a SectionRenderDispatcher fixed-buffer accessor is needed
//        on 26.2 (the mod's live path isolates via the RenderBuffers pool swap alone).
//   * client.gameRenderer.setRenderHand(...) + ip_getDoRenderHand() save (G18, GONE — no hand flag on
//        GameRenderer) -> DROPPED; hand suppression maps to WorldRenderInfo.doRenderHand ->
//        renderItemInHand gate at U10/S12.
//   * client.getBlockEntityRenderDispatcher().level = newWorld (G35, GONE — BERD has no level field)
//        -> DROPPED; the dim context is prepare(destCameraPos) in the extract (driver-core).
//   * portal_get/setTransparencyShader(...) per-dim fabulous PostChain swap (no such duck member on the
//        held IEWorldRenderer; 26.2 translucency is framegraph-driven) -> DROPPED (26.2-superseded).
//   * client.renderBuffers().bufferSource().endBatch() pool-exhausted fallback (G2, bufferSource/endBatch
//        GONE) -> DROPPED (no mid-batch flush model on 26.2).
//   * EntityRenderDispatcher.prepare(Level,Camera,Entity) -> prepare(Camera,Entity) (mc262
//        EntityRenderDispatcher.java:122, the Level arg was removed).
// The inline RenderBuffers pool (acquire/returnRenderBuffersObject + secondaryRenderBuffers) is folded
// in VERBATIM per carriage flag B5; endFramePooled() is the ADDITIVE 26.2-required per-frame endFrame
// (carriage flag B6; memory gpu-buffer-leak-endframe) wired from the render TAIL at S12/S13.
//
// SIGN NOTE (D4.4): this class performs NO depth/clip/winding/projection math — it only saves, swaps,
// and restores CONTEXT (level/renderer/camera/lightmap/fog/particles/buffers/frustum) and pushes an
// IDENTITY model-view. R5 reversed-Z lives in RendererUsingStencil (U10/S12), not here. render-thread-
// logging discipline: the limitedLogger field is kept for fidelity but never called per-frame.
//
// Forward-refs (documented debt, resolve later): VisibleSectionDiscovery (U9 same-stage, S11-B later
// commit — takeList/returnList), BlockManipulationClient (U11 / S13). Held/inert until S13.
@Environment(EnvType.CLIENT)
public class MyGameRenderer {
    public static final Minecraft client = Minecraft.getInstance();

    private static final LimitedLogger limitedLogger = new LimitedLogger(10);

//    public static final int MAX_SECONDARY_BUFFER_NUM = 2;

    // portal rendering and outer world rendering uses different buffer builder storages
    private static Stack<RenderBuffers> secondaryRenderBuffers = new Stack<>();
    private static int usingRenderBuffersObjectNum = 0;

    // the vanilla visibility sections discovery code is multithreaded
    // when the player teleports through a portal, on the first frame it will not work normally
    // so use IP's non-multi-threaded algorithm at the first frame
    public static int vanillaTerrainSetupOverride = 0;

    /**
     * S14.9 (final verify round, MINOR — both agents' verbatim prescription): arm the override AND
     * force the CURRENT main renderer's SOG frustum update. IP's consumer anchor (setupRender
     * RETURN) ran every frame, so the flag was consumed on the very next frame; the 26.2 re-site
     * (applyFrustum RETURN) is EVENT-driven — without the force, a SAME-dim teleport's override
     * sat armed until the next natural applyFrustum (rotation bucket / SOG rebuild), silently
     * narrowing IP's same-frame contract. Cross-dim promotes force it independently; the double
     * force is an idempotent AtomicBoolean set (survives waitAndReset — bytecode-verified).
     */
    public static void armVanillaTerrainSetupOverride() {
        vanillaTerrainSetupOverride = 1;
        if (client.levelRenderer != null) {
            var sog = client.levelRenderer.sectionOcclusionGraph();
            if (sog != null) {
                ((com.warwa.seamlessportals.mixin.client.SectionOcclusionGraphAccessorMixin) (Object) sog)
                    .seamlessportals$getNeedsFrustumUpdate().set(true);
            }
        }
    }

    public static boolean enablePortalCaveCulling = true;

    public static void init() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(() -> {
            secondaryRenderBuffers.clear();
        });
    }

    // carriage flag B5: IP's private inline pool, folded in VERBATIM. Do NOT delegate to the mod's
    // standalone PortalRenderBuffersPool.
    private static RenderBuffers acquireRenderBuffersObject() {
//        if (usingRenderBuffersObjectNum >= MAX_SECONDARY_BUFFER_NUM) {
//            return null;
//        }
        usingRenderBuffersObjectNum++;

        if (secondaryRenderBuffers.isEmpty()) {
            return new RenderBuffers(0);
        }
        else {
            return secondaryRenderBuffers.pop();
        }
    }

    private static void returnRenderBuffersObject(RenderBuffers renderBuffers) {
        usingRenderBuffersObjectNum--;
        secondaryRenderBuffers.push(renderBuffers);
    }

    /**
     * ADDITIVE 26.2-required (carriage flag B6; NOT in IP — every mod-created {@link RenderBuffers}
     * needs the per-frame {@code endFrame()} or its {@code StagedVertexBuffer} pools never fence-recycle
     * and leak GPU buffers; memory gpu-buffer-leak-endframe, render-core fact 6). The pooled secondary
     * buffers are swapped in as the ACTIVE renderBuffers during portal sub-renders, so they accumulate
     * GPU buffers per use exactly like the per-secondary ones. Wired from {@code GameRenderer.render}
     * TAIL at S12/S13 — all sub-renders acquire/release within renderLevel, so the pool is idle here.
     */
    public static void endFramePooled() {
        for (RenderBuffers renderBuffers : secondaryRenderBuffers) {
            renderBuffers.endFrame();
        }
        // S14-A FIX-4: the isolated per-secondary feature-pipeline buffers need the same
        // per-frame endFrame (memory gpu-buffer-leak-endframe); this is the flag-ON
        // GameRenderer.render-TAIL site that already covers the pool above.
        ClientWorldLoader.endFrameOnSecondaryFeatureBuffers();
    }

    public static void renderWorldNew(
        WorldRenderInfo worldRenderInfo,
        Consumer<Runnable> invokeWrapper
    ) {
        WorldRenderInfo.pushRenderInfo(worldRenderInfo);

        switchAndRenderTheWorld(
            worldRenderInfo.world,
            invokeWrapper,
            worldRenderInfo.renderDistance,
            worldRenderInfo.doRenderHand
        );

        WorldRenderInfo.popRenderInfo();
    }

    private static void switchAndRenderTheWorld(
        ClientLevel newWorld,
        Consumer<Runnable> invokeWrapper,
        int renderDistance,
        boolean doRenderHand
    ) {
        if (!enablePortalCaveCulling) {
            client.smartCull = false;
        }

        if (!PortalRendering.shouldEnableSodiumCaveCulling()) {
            client.smartCull = false;
        }

        ResourceKey<Level> newDimension = newWorld.dimension();

        LevelRenderer worldRenderer = ClientWorldLoader.getWorldRenderer(newDimension);

        CHelper.checkGlError();

        IEGameRenderer ieGameRenderer = (IEGameRenderer) client.gameRenderer;
        DimensionRenderHelper helper =
            ClientWorldLoader.getDimensionRenderHelper(newDimension);
        Camera newCamera = new Camera();

        // ===== S13-H Step 1 — virtual-camera CONFIG (S13H-driver-core-design.md §1 Step 1) =========
        // Must land HERE (right after construction), BEFORE helper.updateAndRender(newCamera, ...) at
        // the first-visit lightmap prime below: that prime reads the camera's EnvironmentAttributeProbe,
        // which is only valid once the camera has the dest level+position+tick (else black fog/ambient).
        // Position = WorldRenderInfo.cameraPos (the ORIGINAL camera transformed through every pushed
        // portal layer); rotation = the ORIGINAL camera angles (the portal's rotation rides
        // cameraTransformation into the dest view matrix inside the core, NEVER the camera angles).
        ((IECamera) newCamera).ip_resetState(WorldRenderInfo.getCameraPos(), newWorld);
        ((IECamera) newCamera).portal_setFocusedEntity(client.getCameraEntity());
        ((com.warwa.seamlessportals.mixin.client.CameraInvokerMixin) newCamera)
            .seamlessportals$invokeSetRotation(
                RenderStates.originalCamera.yRot(), RenderStates.originalCamera.xRot());
        newCamera.tick(); // primes the camera's OWN EnvironmentAttributeProbe with dest level+position
        ((com.warwa.seamlessportals.mixin.client.CameraInvokerMixin) newCamera)
            .seamlessportals$setInitialized(true);

        // S13-H §2.2 — capture the block-atlas sampler renderGroup needs at the OUTERMOST entry,
        // while client.levelRenderer is still the TRUE main renderer (the swap to the dest renderer
        // is below). S18.2 verify fix (wf_b9fd9266-022): the gate is layer <= 1, not == 1 — the
        // CrossPortalViewRendering / GuiPortalRendering full-frame paths enter at LAYER 0 (no
        // pushPortalLayer) and must capture too, or a fresh session's first cross view draws zero
        // terrain. Nested layers resolve a secondary whose sampler is null; captureMainChunkSampler
        // itself now refuses null candidates (never poisons a good capture), so the layer gate is
        // belt-and-braces, not the correctness boundary.
        if (PortalRendering.getPortalLayer() <= 1) {
            SecondaryWorldRenderCore.captureMainChunkSampler(client.levelRenderer);
        }

        // store old state
        ClientLevel oldWorld = client.level;
        LevelRenderer oldWorldRenderer = client.levelRenderer;
        // G20: LightTexture -> Lightmap; the "current" lightmap is the GameRenderer's own, reached via
        // the mod's existing accessor (the exact identity RenderStates.onTotalRenderEnd restores to).
        Lightmap oldLightmap =
            ((com.warwa.seamlessportals.mixin.client.GameRendererAccessorMixin) client.gameRenderer)
                .seamlessportals$getLightmap();
        boolean oldNoClip = client.player.noPhysics;
        ObjectArrayList<SectionRenderDispatcher.RenderSection> oldChunkInfoList =
            ((IEWorldRenderer) oldWorldRenderer).portal_getChunkInfoList();
        HitResult oldCrosshairTarget = client.hitResult;
        Camera oldCamera = client.gameRenderer.mainCamera();
        RenderBuffers oldRenderBuffers = ((IEWorldRenderer) worldRenderer).ip_getRenderBuffers();
        RenderBuffers oldClientRenderBuffers = client.gameRenderer.renderBuffers();
        Frustum oldFrustum = ((IEWorldRenderer) worldRenderer).portal_getFrustum();

        ObjectArrayList<SectionRenderDispatcher.RenderSection> newChunkInfoList =
            VisibleSectionDiscovery.takeList();
        ((IEWorldRenderer) oldWorldRenderer).portal_setChunkInfoList(newChunkInfoList);

        Object irisPipeline = IrisInterface.invoker.getPipeline(worldRenderer);

        // switch (note: it will no longer switch the world that client player is in )
        ((IEMinecraftClient) client).ip_setWorldRenderer(worldRenderer);
        client.level = newWorld;
        ieGameRenderer.ip_setLightmapTextureManager(helper.lightmapTexture);

        client.player.noPhysics = true;

        FogRendererContext.swappingManager.pushSwapping(newDimension);
        ((IEParticleManager) client.particleEngine).ip_setWorld(newWorld);
        if (BlockManipulationClient.remotePointedDim == newDimension) {
            client.hitResult = BlockManipulationClient.remoteHitResult;
        }
        if (!PortalRendering.shouldRenderHitResult()) {
            client.hitResult = null;
        }
        ieGameRenderer.ip_setCamera(newCamera);

        RenderBuffers newRenderBuffers = null;
        if (IPGlobal.useSecondaryEntityVertexConsumer) {
            newRenderBuffers = acquireRenderBuffersObject();
            if (newRenderBuffers != null) {
                // S14-A FIX-5 (M5, audit link drivercore): do NOT swap the dest renderer's own
                // renderBuffers field while its SectionRenderDispatcher does not exist yet. 26.2
                // defers dispatcher creation into the FIRST extract (LevelExtractor.java:105-124,
                // inside this very pass for a fresh secondary), and the dispatcher PERMANENTLY
                // captures `this.renderBuffers` at construction — with the swap active that is
                // this transient pooled RenderBuffers(0) = a 1-pack section-builder pool serializing
                // every async compile for the dim's whole life (plus a pool-object identity leak).
                // Skipping the first pass leaves the CONSTRUCTION buffers — for layer-1 creation
                // the main shared pool at full concurrency, exactly IP 1.21.3's permanent
                // arrangement (IP secondaries compiled from client.renderBuffers()); a dim FIRST
                // created inside a NESTED pass (needs a 3rd dim) captures the outer pooled object,
                // an IP-inherited corner, unreachable at rung 2 — ledgered. The swap resumes from
                // the pass after the dispatcher exists. (S14.6 verifier correction: the previously
                // documented "RD change mid-pass recreates the dispatcher under the swap" corner is
                // mechanically impossible — recreation needs shouldResetLevelRenderData, set only
                // by setLevel, never by an RD-change allChanged; the guard is complete.)
                if (worldRenderer.sectionRenderDispatcher() != null) {
                    ((IEWorldRenderer) worldRenderer).ip_setRenderBuffers(newRenderBuffers);
                }
                ((IEMinecraftClient) client).ip_setRenderBuffers(newRenderBuffers);
                // ip_setFixedBuffers(newRenderBuffers.fixedBufferPack()) DROPPED — see header (no held
                // IESectionRenderDispatcher; 26.2 StagingBuffer model; CUTOVER_SPEC S12 flag).
            }
            // pool-exhausted fallback (bufferSource().endBatch(), G2) DROPPED — no mid-batch flush on 26.2.
        }

        Object newSodiumContext = SodiumInterface.invoker.createNewContext(renderDistance);
        SodiumInterface.invoker.switchContextWithCurrentWorldRenderer(newSodiumContext);

        IrisInterface.invoker.setPipeline(worldRenderer, null);

        //update lightmap (G20: updateLightTexture(0) first-visit prime -> extract+render once via the
        // mod's proven per-dim Lightmap driver; virtual-camera config is S13 driver-core).
        if (!RenderStates.isDimensionRendered(newDimension)
            && !qouteall.imm_ptl.core.IPGlobal.debugSkipDestLightmap // S14.38 lever
        ) {
            helper.updateAndRender(newCamera, RenderStates.getPartialTick());
        }

        // Projection + model-view bracket (G27/G19/G28): save the (bobbed main) projection + push an
        // identity model-view around the dest render; restore after.
        //
        // V2-DEFECT-2 fix (S13-H verifier 2): use PER-INVOCATION LOCALS, NOT RenderSystem.backup/
        // restoreProjectionMatrix(). That pair is a SINGLE-SLOT static save (one savedProjectionMatrixBuffer/
        // savedProjectionType — mc262 RenderSystem.java:59,64,186-196), but the driver core now makes portal
        // nesting LIVE (SecondaryWorldRenderCore Step 10.10 -> onBeforeTranslucentRendering -> nested
        // switchAndRenderTheWorld). Under nesting the inner layer's backup() would overwrite the shared slot
        // with the outer layer's dest projection, so the outer restore() would reinstate the wrong (inner)
        // buffer — the main frame's tail (weather/clouds/hand) would run on it. Locals are recursion-safe:
        // each invocation restores exactly what it saw (IP's 1.21.3 form saved the projection in a local
        // too; the G27/G19 backup/restore re-expression lost that property — this restores it).
        GpuBufferSlice savedProjectionBuffer = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType savedProjectionType = RenderSystem.getProjectionType();
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.identity();

        //invoke rendering
        // S18.2 verify BLOCKER fix (wf_b9fd9266-022): try/finally around the invoke — a throw inside
        // the dest render previously skipped the ENTIRE restore block below, permanently stranding
        // client.level/levelRenderer/camera/lightmap/particle-world/renderBuffers/hitResult and the
        // pushed model-view + projection on the DESTINATION as the exception unwound (pre-existing
        // exposure; the S18 layer-0 callers made it live). The finally preserves the exact restore
        // order; the throw still propagates after restoration (callers keep their own handling).
        try {
            invokeWrapper.accept(() -> {
                ProfilerFiller profiler = Profiler.get();
                profiler.push("render_portal_content");
                // S13-H THE DRIVER CORE (S13H-driver-core-design.md §0 ARCHITECTURE VERDICT): the bare
                // `client.gameRenderer.renderLevel(...)` re-rendered the ALREADY-EXTRACTED MAIN-world
                // state (WorldRenderInfo.cameraPos/cameraTransformation consumed by NOTHING → the window
                // showed the player's own view). It is REPLACED by the re-expression of the runtime-proven
                // stencil-direct dest-draw sequence: per-dim LevelExtractor.extract with the TRANSFORMED
                // camera + compileSections drain + LevelRenderState re-point + armed VisibleSectionDiscovery
                // + renderGroup, all masked by the live stencil, WITHOUT nesting a framegraph.
                // V1-M2: thread the shell's PRE-SWAP source context (oldWorld/oldCamera — the immediate
                // OUTER layer's world+camera, captured above before the swap) into the core so its
                // Globals-UBO + diffuse-lighting restore targets the outer layer under nesting, not the
                // layer-0 originals. At layer 1 these equal the originals (no rung-1 change).
                // S14.52 zero-lag hunt (many-portal): whole-pass wall time, dpMs= in the kit rows —
                // splits "the passes themselves are expensive" from "something BETWEEN passes is".
                long dpT0 = System.nanoTime();
                SecondaryWorldRenderCore.renderDestWorld(
                    newWorld, worldRenderer, newCamera, renderDistance,
                    oldWorld, oldCamera);
                qouteall.imm_ptl.core.render.TeleportFlashProbe.destPassNanosThisFrame +=
                    System.nanoTime() - dpT0;
                profiler.pop();
            });
        } finally {
            SodiumInterface.invoker.switchContextWithCurrentWorldRenderer(newSodiumContext);

            //recover
            modelViewStack.popMatrix();
            // V2-DEFECT-2: restore from the per-invocation locals (recursion-safe; equivalent to the field
            // assignment restoreProjectionMatrix() does, but sourced from this frame's saved slice/type).
            RenderSystem.setProjectionMatrix(savedProjectionBuffer, savedProjectionType);

            ((IEMinecraftClient) client).ip_setWorldRenderer(oldWorldRenderer);
            client.level = oldWorld;
            ieGameRenderer.ip_setLightmapTextureManager(oldLightmap);
            client.player.noPhysics = oldNoClip;

            ((IEParticleManager) client.particleEngine).ip_setWorld(oldWorld);
            client.hitResult = oldCrosshairTarget;
            ieGameRenderer.ip_setCamera(oldCamera);

            FogRendererContext.swappingManager.popSwapping();

            ((IEWorldRenderer) oldWorldRenderer).portal_setChunkInfoList(oldChunkInfoList);
            VisibleSectionDiscovery.returnList(newChunkInfoList);

            ((IEWorldRenderer) worldRenderer).ip_setRenderBuffers(oldRenderBuffers);
            ((IEMinecraftClient) client).ip_setRenderBuffers(oldClientRenderBuffers);
            if (newRenderBuffers != null) {
                returnRenderBuffersObject(newRenderBuffers);
            }

            ((IEWorldRenderer) worldRenderer).portal_setFrustum(oldFrustum);

            IrisInterface.invoker.setPipeline(worldRenderer, irisPipeline);

            // EntityRenderDispatcher.prepare(Level,Camera,Entity) -> prepare(Camera,Entity) on 26.2.
            client.getEntityRenderDispatcher()
                .prepare(
                    oldCamera,
                    client.crosshairPickEntity
                );

            CHelper.checkGlError();

            client.smartCull = true;
        }
    }

    /**
     * IP {@link LevelRenderer#renderLevel} fog-state reset (@IPVanillaCopy). SUPERSEDED on 26.2: IP's
     * six {@code FogRenderer} statics (setupFog/levelFogColor) are GONE (render-core G31), and the
     * per-dimension fog difference is data-driven through the extracted {@code CameraRenderState.fogData}
     * + the per-camera {@code EnvironmentAttributes} probe (G32) — i.e. re-running extract for the dest
     * dimension covers it. There is no static fog to reset. The LIVE per-dim fog buffer ownership (so the
     * single WORLD fog ring-buffer slot is not written mid-frame for the secondary) is the CUTOVER_SPEC
     * R9 design item handed forward from S11-A §4 — NOT solved here. Retained as an inert IP-contract shim.
     */
    @IPVanillaCopy
    public static void resetFogState() {
        // 26.2: fog is extract-driven (G31/G32); per-dim fog buffer ownership = CUTOVER_SPEC R9. No-op.
    }

    /**
     * IP {@link LevelRenderer#renderLevel} fog-color reset. SUPERSEDED on 26.2 for the same reason as
     * {@link #resetFogState()}: {@code FogRenderer.setupColor} + the color statics are GONE (G31); the
     * fog color now rides {@code cameraState.fogData.color} into the render (G31 note). The compute-only
     * color probe {@code setupFog(...).color} is ring-buffer-safe (S11-A §4) but has nowhere to WRITE on
     * 26.2 except the extracted state. Inert IP-contract shim; the real per-dim fog is CUTOVER_SPEC R9.
     */
    public static void updateFogColor() {
        // 26.2: fog color rides the extracted CameraRenderState (G31); CUTOVER_SPEC R9 owns per-dim fog.
    }

    /**
     * IP {@link LevelRenderer#renderLevel} diffuse-lighting reset (@IPVanillaCopy). RE-EXPRESSED onto
     * 26.2 (render-core G30): the static {@code Lighting.setupLevel()/setupNetherLevel()} became the
     * instance {@code GameRenderer.lighting().updateLevel(CardinalLighting.Type)}, and IP's
     * {@code effects().constantAmbientLight()} branch is now the data-driven
     * {@code dimensionType().cardinalLightType()} (DEFAULT vs NETHER) — the exact selector vanilla's own
     * {@code GameRenderer.setLevel} uses (mc262 GameRenderer.java:705-711). Same nether-vs-overworld
     * diffuse split, no sign surface.
     */
    @IPVanillaCopy
    public static void resetDiffuseLighting() {
        ClientLevel world = client.level;
        assert world != null;
        client.gameRenderer.lighting().updateLevel(
            world.dimensionType().cardinalLightType()
        );
    }
}
