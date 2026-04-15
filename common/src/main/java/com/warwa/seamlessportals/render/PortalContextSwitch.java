package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.*;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.client.PortalWorldManager;
import com.warwa.seamlessportals.mixin.client.CameraInvokerMixin;
import com.warwa.seamlessportals.mixin.client.GameRendererAccessorMixin;
import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import com.warwa.seamlessportals.mixin.client.MinecraftRenderTargetMixin;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalTransform;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Renders the destination world to a secondary FBO, then composites through stencil.
 *
 * Following IP's RendererUsingFrameBuffer.doRenderPortal() pattern:
 * 1. Prepare secondary FBO (matching main FBO size)
 * 2. Swap mc.mainRenderTarget to secondary FBO
 * 3. Clear secondary FBO (color + depth, stencil disabled)
 * 4. Call renderLevel() with proper CameraRenderState → full framegraph on secondary FBO
 * 5. Restore mc.mainRenderTarget
 * 6. Composite secondary FBO onto main through stencil mask
 *
 * The secondary renderer has its own LevelRenderState (Commit 1), so
 * extractLevel() doesn't corrupt the main renderer's state.
 */
public class PortalContextSwitch {

    /**
     * Recursion guard. Prevents infinite recursion when renderLevel() on the
     * secondary renderer triggers Fabric's AFTER_TRANSLUCENT_TERRAIN event,
     * which would call StencilPortalRenderer.renderPortals() again.
     * Matches IP's PortalRendering.isRendering() check.
     */
    public static boolean isRenderingPortal = false;

    // Lightmap is now managed by DimensionRenderHelper (per dimension, real values).

    /**
     * Override for GameRenderer.lightmap() during portal rendering.
     * Read by GameRendererLightmapMixin. Removed in Commit 4 (mc.level swap).
     */
    public static com.mojang.blaze3d.textures.GpuTextureView portalLightmapOverride = null;

    /** Secondary FBO for portal world rendering. Matches IP's SecondaryFrameBuffer. */
    private static TextureTarget secondaryFbo = null;

    private static int phase2FailCount = 0;
    private static int phase2SuccessCount = 0;
    /** Tracks chunk count at last feed per dimension. Feed only when new chunks arrive. */
    private static final java.util.Map<ResourceKey<Level>, Integer> lastFedChunkCount = new java.util.HashMap<>();

    public static void resetChunkFedState(ResourceKey<Level> dimension) {
        lastFedChunkCount.remove(dimension);
        phase2FailCount = 0;
        phase2SuccessCount = 0;
    }

    /**
     * Render the destination dimension through the stencil mask.
     * Tries FBO rendering first, falls back to colored blocks, then background.
     *
     * The background fallback ensures the portal is always visible even when
     * destination chunks haven't arrived yet (e.g., nether side after dimension change).
     * Without it, SectionCompilerMixin hides the purple swirl but nothing replaces it.
     */
    public static void renderDestinationWorld(PortalInfo srcPortal, PortalLink link, Camera camera) {
        PortalInfo destPortal = link.getDestination();
        ResourceKey<Level> destDim = destPortal.getDimension();

        if (tryFboRender(srcPortal, link, camera, destDim)) {
            return;
        }

        if (RemoteChunkManager.hasDimensionData(destDim)) {
            renderColoredBlocks(srcPortal, link, camera, destDim);
        } else {
            // No chunk data yet — draw solid background so portal is visible.
            // This happens after dimension change before the server sends chunks.
            PortalShapeRenderer.drawPortalBackground(
                java.util.List.of(srcPortal), camera, destDim);
        }
    }

    private static boolean tryFboRender(PortalInfo srcPortal, PortalLink link,
                                         Camera mainCamera, ResourceKey<Level> destDim) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;

        LevelRenderer destRenderer = PortalWorldManager.getOrCreateRenderer(destDim);
        ClientLevel destLevel = PortalWorldManager.getLevel(destDim);
        if (destRenderer == null || destLevel == null) return false;

        PortalInfo destPortal = link.getDestination();
        BlockPos destOrigin = destPortal.getOrigin();
        destLevel.getChunkSource().updateViewCenter(
            destOrigin.getX() >> 4, destOrigin.getZ() >> 4);

        // Feed chunks incrementally: only when new chunks have arrived from the server.
        // Feeding is expensive (ByteBuf serialize/deserialize + light + dirty marking),
        // so we track the count and only re-feed when it increases.
        int currentCount = RemoteChunkManager.getChunkCount(destDim);
        int lastCount = lastFedChunkCount.getOrDefault(destDim, 0);
        if (currentCount > lastCount) {
            PortalWorldManager.feedExistingChunks(destDim);
            lastFedChunkCount.put(destDim, currentCount);
        }

        // Require minimum chunks before attempting FBO render.
        if (currentCount < 9) {
            return false;
        }

        try {
            return doFboRender(srcPortal, link, mainCamera, destDim,
                               destRenderer, destLevel, mc);
        } catch (Exception e) {
            if (phase2FailCount <= 3) {
                SeamlessPortalsConstants.LOGGER.error(
                    "[SEAMLESS] FBO render failed", e);
            }
            phase2FailCount++;
            return false;
        }
    }

    /**
     * Render destination world to secondary FBO, then composite through stencil.
     * Matches IP's RendererUsingFrameBuffer.doRenderPortal() + MyGameRenderer.switchAndRenderTheWorld().
     */
    private static boolean doFboRender(
            PortalInfo srcPortal, PortalLink link, Camera mainCamera,
            ResourceKey<Level> destDim, LevelRenderer destRenderer,
            ClientLevel destLevel, Minecraft mc) {

        PortalInfo destPortal = link.getDestination();
        DeltaTracker deltaTracker = mc.getDeltaTracker();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

        if (phase2SuccessCount <= 3) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS] FBO render: src={} ({}), dest={} ({})",
                srcPortal.getOrigin(), srcPortal.getDimension().identifier(),
                destPortal.getOrigin(), destDim.identifier());
        }

        // ===== 1. Compute destination camera position =====
        // 1:1 mapping through portal transform. Camera mirrors the player's
        // position relative to the destination portal. Oblique near-plane
        // clipping prevents seeing terrain between camera and portal surface.
        Direction.Axis srcAxis = srcPortal.getAxis();
        Direction.Axis destAxis = destPortal.getAxis();
        // 1:1 position mapping — NO clamping. The FBO and main screen share
        // pixel coordinates. For the portal to look like a physical window,
        // the FBO camera must be at the exact transformed position so that
        // destination terrain projects to the same screen pixels as if it
        // were physically behind the portal. Clamping breaks this alignment.
        // The stencil mask naturally clips geometry outside the portal opening.
        Vec3 destCameraPos = PortalTransform.transformPoint(
            srcPortal, destPortal, srcPortal.getType(), mainCamera.position());

        float yawOffset = (srcAxis != destAxis)
            ? ((srcAxis == Direction.Axis.Z) ? 90.0f : -90.0f)
            : 0;

        // ===== 2. Create virtual camera =====
        // Rotation follows the player's view direction (+ yaw offset for
        // cross-axis portals). The model-view stack is reset to identity
        // before renderLevel() to prevent the main camera's rotation from
        // contaminating the destination view.
        Camera virtualCamera = new Camera();
        virtualCamera.setLevel(destLevel);
        virtualCamera.setEntity(mc.player);
        ((CameraInvokerMixin) virtualCamera).seamlessportals$invokeSetRotation(
            mainCamera.yRot() + yawOffset, mainCamera.xRot());
        ((CameraInvokerMixin) virtualCamera).seamlessportals$invokeSetPosition(destCameraPos);
        // CRITICAL: tick the camera's EnvironmentAttributeProbe with the destination
        // level and position. Without this, the probe returns default values (all zeros)
        // → fog color is black, sky light factor is 0, ambient is black.
        // The probe reads biome/dimension attributes from the level at the camera position.
        virtualCamera.tick();

        if (phase2SuccessCount <= 3) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] Camera: playerPos=({},{},{}) → destCam=({},{},{}) destYaw={} srcAxis={} destAxis={}",
                String.format("%.1f", mainCamera.position().x),
                String.format("%.1f", mainCamera.position().y),
                String.format("%.1f", mainCamera.position().z),
                String.format("%.1f", destCameraPos.x),
                String.format("%.1f", destCameraPos.y),
                String.format("%.1f", destCameraPos.z),
                String.format("%.1f", mainCamera.yRot() + yawOffset),
                srcAxis, destAxis);
        }

        // Build frustum
        CameraRenderState mainCameraState =
            mc.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
        Matrix4f viewMatrix = new Matrix4f();
        virtualCamera.getViewRotationMatrix(viewMatrix);
        Matrix4f projMatrix = new Matrix4f(mainCameraState.projectionMatrix);
        Frustum destFrustum = new Frustum(viewMatrix, projMatrix);
        destFrustum.prepare(destCameraPos.x, destCameraPos.y, destCameraPos.z);
        ((CameraInvokerMixin) virtualCamera).seamlessportals$setCullFrustum(destFrustum);
        ((CameraInvokerMixin) virtualCamera).seamlessportals$setInitialized(true);

        // ===== 3. Direct section compilation (sparse chunks, bypass occlusion graph) =====
        LevelRenderState destLRS =
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getLevelRenderState();
        net.minecraft.client.renderer.ViewArea viewArea =
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getViewArea();
        it.unimi.dsi.fastutil.objects.ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections =
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getVisibleSections();

        if (viewArea != null) {
            net.minecraft.core.SectionPos cameraSectionPos = net.minecraft.core.SectionPos.of(destCameraPos);
            viewArea.repositionCamera(cameraSectionPos);
            destLevel.getChunkSource().updateViewCenter(cameraSectionPos.x(), cameraSectionPos.z());

            SectionRenderDispatcher dispatcher = destRenderer.getSectionRenderDispatcher();
            // CRITICAL: Tell the dispatcher where the camera is.
            // Vanilla calls this in cullTerrain() every frame (LevelRenderer.java:401).
            // Without it, the terrain shader doesn't know the camera position
            // → chunks render at wrong screen positions ("far away" / "wrong view").
            dispatcher.setCameraPosition(destCameraPos);

            net.minecraft.client.renderer.chunk.RenderRegionCache cache =
                new net.minecraft.client.renderer.chunk.RenderRegionCache();

            visibleSections.clear();
            int compiled = 0;
            for (SectionRenderDispatcher.RenderSection section : viewArea.sections) {
                if (section == null) continue;
                long sectionNode = section.getSectionNode();
                int sx = net.minecraft.core.SectionPos.x(sectionNode);
                int sz = net.minecraft.core.SectionPos.z(sectionNode);
                if (destLevel.getChunkSource().hasChunk(sx, sz)) {
                    if (section.isDirty()) {
                        dispatcher.rebuildSectionSync(section, cache);
                        section.setNotDirty();
                    }
                    visibleSections.add(section);
                    compiled++;
                }
            }
            if (phase2SuccessCount == 0 && compiled > 0) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS] Direct compilation: {} sections at [{},{}]",
                    compiled, cameraSectionPos.x(), cameraSectionPos.z());
            }
        }

        // ===== 4. Extract level state into secondary renderer's own LevelRenderState =====
        destRenderer.extractLevel(deltaTracker, virtualCamera, partialTick);

        ChunkSectionsToRender destChunks = destLRS.chunkSectionsToRender;
        if (destChunks == null || destChunks.maxIndicesRequired() == 0) {
            if (phase2FailCount <= 5) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS] No compiled chunks for {} (fail #{})", destDim.identifier(), phase2FailCount + 1);
            }
            phase2FailCount++;
            return false;
        }

        // Diagnostic: count draw groups per layer to verify terrain will actually render
        if (phase2SuccessCount <= 5) {
            int totalDraws = 0;
            for (var layerEntry : destChunks.drawGroupsPerLayer().values()) {
                for (var drawList : layerEntry.values()) {
                    totalDraws += drawList.size();
                }
            }
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] destChunks: maxIndices={} totalDraws={} textureView={}",
                destChunks.maxIndicesRequired(), totalDraws,
                destChunks.textureView() != null ? "valid" : "NULL");
        }

        // ===== 5. Prepare secondary FBO (match IP's SecondaryFrameBuffer.prepare()) =====
        prepareSecondaryFbo();

        // ===== 6. Build CameraRenderState for destination =====
        CameraRenderState destCameraState = destLRS.cameraRenderState;
        virtualCamera.extractRenderState(destCameraState, partialTick);
        // Override projection from main camera (same FOV/aspect)
        destCameraState.projectionMatrix.set(mainCameraState.projectionMatrix);

        // Apply oblique near-plane clipping to hide terrain between camera and portal.
        boolean obliqueApplied = applyObliqueNearPlane(
            destCameraState.projectionMatrix,
            virtualCamera,
            destCameraPos,
            destPortal.getCenter(),
            destPortal.getNormal()
        );

        // ===== 7. Compute destination fog =====
        FogRenderer fogRenderer =
            ((GameRendererAccessorMixin) mc.gameRenderer).seamlessportals$getFogRenderer();
        FogData destFogData = fogRenderer.setupFog(
            virtualCamera,
            mc.options.getEffectiveRenderDistance(),
            deltaTracker,
            0f, // no boss darkening for portal view
            destLevel
        );
        destCameraState.fogData = destFogData;
        destCameraState.fogType = FogType.NONE;

        // Create a SEPARATE fog buffer for portal rendering.
        // DO NOT call fogRenderer.updateBuffer() — that overwrites the main renderer's
        // fog buffer (MappableRingBuffer shared memory), causing dark clipping artifacts
        // across the entire world. Instead, write directly to our own buffer.
        com.mojang.blaze3d.buffers.GpuBufferSlice destFogBuffer = writePortalFogBuffer(destFogData);

        // ===== 8. Update per-dimension lightmap (match IP's DimensionRenderHelper) =====
        // Uses virtual camera's attributeProbe() to get destination dimension values.
        // No more hardcoded per-dimension lightmap — computed from actual world state.
        DimensionRenderHelper dimHelper = DimensionRenderHelper.getOrCreate(destDim);
        dimHelper.updateAndRender(virtualCamera, partialTick);
        portalLightmapOverride = dimHelper.getLightmap().getTextureView();

        // ===== 9. Full context switch (match IP's withSwitchedWorld) =====
        // Save state
        RenderTarget savedMainRT = mc.getMainRenderTarget();
        ClientLevel savedLevel = mc.level;
        LevelRenderer savedRenderer = mc.levelRenderer;

        // Swap to destination context
        ((MinecraftRenderTargetMixin)(Object) mc).seamlessportals$setMainRenderTarget(secondaryFbo);
        mc.level = destLevel;
        ((com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin) mc)
            .seamlessportals$setLevelRenderer(destRenderer);

        // Debug: verify swaps
        if (phase2SuccessCount <= 3) {
            int mainFbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] Context switch: level={} renderer={} mainRT={}x{} glFbo={}",
                mc.level.dimension().identifier(),
                mc.levelRenderer == destRenderer ? "dest" : "WRONG",
                mc.getMainRenderTarget().width, mc.getMainRenderTarget().height,
                mainFbo);
        }

        // ===== 10. Set recursion guard and call renderLevel() =====
        // Save main camera pos for Globals UBO restore (must be before try block)
        Vec3 savedCameraPos = mainCamera.position();
        isRenderingPortal = true;
        try {
            // With full context switch, renderLevel() sees:
            // - mc.level = destLevel (fog, biome, sky from destination)
            // - mc.levelRenderer = destRenderer (correct translucent target, etc.)
            // - mc.getMainRenderTarget() = secondaryFbo (renders to FBO, not screen)
            // This matches IP's withSwitchedWorld() + RendererUsingFrameBuffer.doRenderPortal()
            Matrix4f destViewMatrix = new Matrix4f();
            virtualCamera.getViewRotationMatrix(destViewMatrix);

            if (phase2SuccessCount <= 3) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS DEBUG] renderLevel fogColor=({},{},{},{}) destChunks.maxIndices={} skyRender=true cam=({},{},{})",
                    destFogData.color.x, destFogData.color.y, destFogData.color.z, destFogData.color.w,
                    destChunks.maxIndicesRequired(),
                    (int) destCameraPos.x, (int) destCameraPos.y, (int) destCameraPos.z);
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS DEBUG] fogDistances: envStart={} envEnd={} renderStart={} renderEnd={} skyEnd={} cloudEnd={}",
                    destFogData.environmentalStart, destFogData.environmentalEnd,
                    destFogData.renderDistanceStart, destFogData.renderDistanceEnd,
                    destFogData.skyEnd, destFogData.cloudEnd);
            }

            // CRITICAL: Disable stencil test before rendering to secondary FBO.
            GL11.glDisable(GL11.GL_STENCIL_TEST);

            // CRITICAL: Reset model-view stack to identity before renderLevel().
            // We're called inside the main renderer's addMainPass lambda, so the
            // stack has the main camera's rotation. renderLevel() pushes+multiplies
            // its own rotation on top. Without resetting, terrain gets the COMBINED
            // rotation (main * dest) → terrain rotates with the player's cursor.
            org.joml.Matrix4fStack modelViewStack = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
            modelViewStack.pushMatrix();
            modelViewStack.identity();

            // Set the oblique projection on RenderSystem so bindDefaultUniforms()
            // writes it to the Projection UBO. Use MC's native backup/restore API.
            if (obliqueApplied) {
                RenderSystem.backupProjectionMatrix();
                RenderSystem.setProjectionMatrix(
                    writeProjectionBuffer(destCameraState.projectionMatrix, false),
                    com.mojang.blaze3d.ProjectionType.PERSPECTIVE);
                if (phase2SuccessCount <= 3) {
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS DEBUG] Projection: backed up + set oblique. m22={} m32={}",
                        String.format("%.4f", destCameraState.projectionMatrix.m22()),
                        String.format("%.4f", destCameraState.projectionMatrix.m32()));
                }
            }

            // CRITICAL: Update the Globals UBO with the destination camera position.
            mc.gameRenderer.getGlobalSettingsUniform().update(
                mc.getMainRenderTarget().width,
                mc.getMainRenderTarget().height,
                mc.gameRenderer.getGameRenderState().optionsRenderState.glintStrength,
                destLevel.getGameTime(),
                deltaTracker,
                mc.gameRenderer.getGameRenderState().optionsRenderState.menuBackgroundBlurriness,
                destCameraPos,
                false // RGSS flag — matches main camera
            );

            destRenderer.renderLevel(
                GraphicsResourceAllocator.UNPOOLED,
                deltaTracker,
                false,
                destCameraState,
                destViewMatrix,
                destFogBuffer,
                destFogData.color,
                true,
                destChunks
            );

        } finally {
            // Restore model-view stack FIRST (must be in finally to prevent
            // stack overflow if renderLevel throws — stack size limit is 16)
            modelViewStack.popMatrix();

            isRenderingPortal = false;
            portalLightmapOverride = null;

            // Restore RenderSystem projection using MC's native restore
            if (obliqueApplied) {
                RenderSystem.restoreProjectionMatrix();
                if (phase2SuccessCount <= 3) {
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS DEBUG] Projection: restored from backup");
                }
            }

            // Restore Globals UBO with main camera position
            mc.gameRenderer.getGlobalSettingsUniform().update(
                mc.getMainRenderTarget().width,
                mc.getMainRenderTarget().height,
                mc.gameRenderer.getGameRenderState().optionsRenderState.glintStrength,
                savedLevel.getGameTime(),
                deltaTracker,
                mc.gameRenderer.getGameRenderState().optionsRenderState.menuBackgroundBlurriness,
                savedCameraPos,
                false // RGSS flag — matches main camera
            );

            // ===== 11. Restore ALL state (match IP's finally block) =====
            mc.level = savedLevel;
            ((com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin) mc)
                .seamlessportals$setLevelRenderer(savedRenderer);
            ((MinecraftRenderTargetMixin)(Object) mc).seamlessportals$setMainRenderTarget(savedMainRT);
        }

        // Debug: verify state after restore
        if (phase2SuccessCount <= 3) {
            int postFbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
            boolean stencilEnabled = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
            RenderTarget postRT = mc.getMainRenderTarget();
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] Post-restore: glFbo={} mainRT={}x{} level={} stencil={}",
                postFbo, postRT.width, postRT.height,
                mc.level.dimension().identifier(), stencilEnabled);
        }

        // ===== 12. Composite secondary FBO onto main =====
        // Draw portal geometry textured with the FBO content.
        // The portal shape clips to the portal area. Stencil EQUAL(1) also active.
        compositePortalFbo();

        // No fog restore needed — we never touched the global fogRenderer buffer.

        phase2SuccessCount++;
        if (phase2SuccessCount <= 5) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS] FBO renderLevel SUCCESS #{} for {} at ({}, {}, {})",
                phase2SuccessCount, destDim.identifier(),
                (int) destCameraPos.x, (int) destCameraPos.y, (int) destCameraPos.z);
        }

        return true;
    }

    /**
     * Prepare secondary FBO matching main FBO size.
     * Matches IP's SecondaryFrameBuffer.prepare().
     */
    private static void prepareSecondaryFbo() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        if (secondaryFbo == null) {
            secondaryFbo = new TextureTarget("seamless_portal", w, h, true);
            SeamlessPortalsConstants.LOGGER.info("[SEAMLESS] Created secondary FBO {}x{}", w, h);
        } else if (secondaryFbo.width != w || secondaryFbo.height != h) {
            secondaryFbo.resize(w, h);
        }
    }

    /**
     * Oblique near-plane clipping (Lengyel method).
     * Modifies the projection matrix so the near clip plane aligns with the
     * portal surface. Everything between the camera and the portal is clipped.
     *
     * @see <a href="https://terathon.com/lengyel/Lengyel-Oblique.pdf">Lengyel paper</a>
     */
    private static boolean applyObliqueNearPlane(
            Matrix4f projMatrix,
            Camera camera,
            Vec3 cameraPos,
            Vec3 portalCenter,
            Vec3 portalNormal) {

        // Get view rotation matrix
        Matrix4f viewRotMatrix = new Matrix4f();
        camera.getViewRotationMatrix(viewRotMatrix);

        // Portal normal should point AWAY from the camera (into the destination).
        float nx = (float) portalNormal.x;
        float ny = (float) portalNormal.y;
        float nz = (float) portalNormal.z;

        double cameraDot = nx * (cameraPos.x - portalCenter.x)
                         + ny * (cameraPos.y - portalCenter.y)
                         + nz * (cameraPos.z - portalCenter.z);

        if (phase2SuccessCount <= 3) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] ObliqueClip: cameraDot={} normalFlipped={} nx={} ny={} nz={}",
                String.format("%.3f", cameraDot),
                cameraDot > 0, nx, ny, nz);
        }

        if (cameraDot > 0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }

        // Transform normal to view space (viewRotMatrix is orthonormal)
        float vnx = viewRotMatrix.m00() * nx + viewRotMatrix.m10() * ny + viewRotMatrix.m20() * nz;
        float vny = viewRotMatrix.m01() * nx + viewRotMatrix.m11() * ny + viewRotMatrix.m21() * nz;
        float vnz = viewRotMatrix.m02() * nx + viewRotMatrix.m12() * ny + viewRotMatrix.m22() * nz;

        // d in view space: dot(normal, cameraPos - portalCenter) with the possibly-negated normal
        float vd = nx * (float)(cameraPos.x - portalCenter.x)
                 + ny * (float)(cameraPos.y - portalCenter.y)
                 + nz * (float)(cameraPos.z - portalCenter.z);

        // Compute Q (inverse-projected corner point)
        float qx = (Math.signum(vnx) + projMatrix.m20()) / projMatrix.m00();
        float qy = (Math.signum(vny) + projMatrix.m21()) / projMatrix.m11();
        float qz = -1.0f;
        float qw = (1.0f + projMatrix.m22()) / projMatrix.m32();

        // Scale clip plane
        float dotCQ = vnx * qx + vny * qy + vnz * qz + vd * qw;

        if (phase2SuccessCount <= 3) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] ObliqueClip: viewNormal=({},{},{}) vd={} dotCQ={} degenerate={}",
                String.format("%.3f", vnx), String.format("%.3f", vny), String.format("%.3f", vnz),
                String.format("%.3f", vd), String.format("%.3f", dotCQ),
                Math.abs(dotCQ) < 1e-6f);
        }

        if (Math.abs(dotCQ) < 1e-4f) return false; // degenerate — skip clipping

        float scale = 2.0f / dotCQ;

        // Safety: if the resulting values are extreme, skip clipping.
        // Extreme values cause the depth buffer to produce NaN/infinity,
        // which freezes the renderer (all depth tests fail forever).
        float newM02 = vnx * scale;
        float newM12 = vny * scale;
        float newM22 = vnz * scale + 1.0f;
        float newM32 = vd * scale;

        if (Math.abs(newM32) > 5f || Math.abs(newM22) > 5f
                || Float.isNaN(newM32) || Float.isInfinite(newM32)) {
            if (phase2SuccessCount <= 3) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS DEBUG] ObliqueClip: SKIPPED extreme values m22={} m32={}",
                    newM22, newM32);
            }
            return false;
        }

        // Save original row 2 for debug
        float origM02 = projMatrix.m02(), origM12 = projMatrix.m12();
        float origM22 = projMatrix.m22(), origM32 = projMatrix.m32();

        // Replace row 2 of projection matrix (OpenGL NDC z range [-1,+1] → +1.0)
        projMatrix.m02(newM02);
        projMatrix.m12(newM12);
        projMatrix.m22(newM22);
        projMatrix.m32(newM32);

        if (phase2SuccessCount <= 3) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] ObliqueClip: row2 BEFORE=({},{},{},{}) AFTER=({},{},{},{})",
                String.format("%.4f", origM02), String.format("%.4f", origM12),
                String.format("%.4f", origM22), String.format("%.4f", origM32),
                String.format("%.4f", projMatrix.m02()), String.format("%.4f", projMatrix.m12()),
                String.format("%.4f", projMatrix.m22()), String.format("%.4f", projMatrix.m32()));
        }

        return true;
    }

    /**
     * Persistent GPU buffer references for projection matrix save/restore.
     * Must be static fields to prevent GC from invalidating OpenGL handles
     * before the GPU is done with them. NEVER call close() — the GPU may
     * still be referencing the buffer from the previous frame. Old buffers
     * get GC'd naturally when the reference is overwritten.
     */
    private static com.mojang.blaze3d.buffers.GpuBuffer portalProjGpuBuffer = null;
    private static com.mojang.blaze3d.buffers.GpuBuffer restoreProjGpuBuffer = null;

    /**
     * Write a Matrix4f to a GPU buffer for RenderSystem.setProjectionMatrix().
     * @param forRestore true = use restore buffer slot, false = use portal buffer slot
     */
    private static com.mojang.blaze3d.buffers.GpuBufferSlice writeProjectionBuffer(Matrix4f matrix, boolean forRestore) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(64)
            .order(java.nio.ByteOrder.nativeOrder());
        matrix.get(buf);
        buf.flip();

        // Create new buffer — do NOT close the old one (GPU may still be using it)
        com.mojang.blaze3d.buffers.GpuBuffer gpuBuf = RenderSystem.getDevice().createBuffer(
            () -> forRestore ? "portal_proj_restore" : "portal_proj_oblique",
            com.mojang.blaze3d.buffers.GpuBuffer.USAGE_UNIFORM, buf);

        if (forRestore) {
            restoreProjGpuBuffer = gpuBuf;
        } else {
            portalProjGpuBuffer = gpuBuf;
        }
        return gpuBuf.slice();
    }

    /**
     * Composite the secondary FBO onto the main render target.
     *
     * Uses createRenderPass with the main RT's textures and binds the FBO texture
     * via pass.bindTexture() (NOT raw GL — raw GL doesn't affect render pass samplers).
     * Draws a full-screen triangle (ENTITY_OUTLINE_BLIT pipeline).
     * Stencil EQUAL(1) from StencilPortalRenderer clips to portal area.
     *
     * GlTextureViewMixin ensures the render pass FBO has DEPTH_STENCIL_ATTACHMENT
     * so stencil values from earlier writes are accessible.
     */
    private static void compositePortalFbo() {
        if (secondaryFbo == null || secondaryFbo.getColorTextureView() == null) return;

        Minecraft mc = Minecraft.getInstance();
        com.mojang.blaze3d.pipeline.RenderTarget mainRT = mc.getMainRenderTarget();

        // Re-enable stencil test (renderLevel may have changed GL state)
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        GL11.glStencilMask(0x00);
        // Disable blend so the FBO texture completely replaces the main world content.
        // The FBO's clear color has alpha=0 (hardcoded in MC). Without disabling blend,
        // the main world's content (clouds, sky) shows through.
        GL11.glDisable(GL11.GL_BLEND);

        if (phase2SuccessCount <= 3) {
            boolean stencilOn = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            int glFboBefore = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
            // Check what FBO the composite render pass will use
            int stencilRef = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_STENCIL_REF);
            int stencilFunc = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_STENCIL_FUNC);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] Composite: stencil={} stencilRef={} stencilFunc={} glFbo={} mainRT={}x{} fboColorTex={} fboDepthTex={}",
                stencilOn, stencilRef, stencilFunc, glFboBefore,
                mainRT.width, mainRT.height,
                secondaryFbo.getColorTextureView() != null ? "valid" : "NULL",
                secondaryFbo.getDepthTextureView() != null ? "valid" : "NULL");
        }

        // Create render pass on main RT with depth-stencil (5-arg version).
        // GlTextureViewMixin ensures the FBO created by getFbo() has DEPTH_STENCIL_ATTACHMENT.
        // Stencil values written by StencilPortalRenderer are on the same depth-stencil texture.
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "portal_composite",
                mainRT.getColorTextureView(),
                OptionalInt.empty(),
                mainRT.getDepthTextureView(),
                OptionalDouble.empty()
        )) {
            if (phase2SuccessCount <= 3) {
                int compositeFbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS DEBUG] Composite RenderPass bound FBO={}", compositeFbo);
            }
            // CRITICAL: Use TRACY_BLIT (no blend), NOT ENTITY_OUTLINE_BLIT (alpha blend).
            // renderLevel() clears the FBO with alpha=0.0 (hardcoded in MC 26.1.2 line 511).
            // Sky and fog have alpha=0. ENTITY_OUTLINE_BLIT uses SRC_ALPHA blending which
            // multiplies by alpha=0 → invisible. TRACY_BLIT has no blend → direct copy.
            pass.setPipeline(RenderPipelines.TRACY_BLIT);
            RenderSystem.bindDefaultUniforms(pass);
            // Bind our FBO texture via the render pass — this is the correct way.
            // Raw GL glBindTexture does NOT affect render pass sampler bindings.
            pass.bindTexture("InSampler", secondaryFbo.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(
                    com.mojang.blaze3d.textures.FilterMode.NEAREST));
            pass.draw(0, 3); // Full-screen triangle
        }
        // Restore blend state for main world rendering
        GL11.glEnable(GL11.GL_BLEND);
    }

    /**
     * Write fog data to a standalone GPU buffer, not the global fogRenderer.
     * Creates a fresh buffer each call to avoid "Buffer is not writable" errors
     * (the command encoder may still be using the previous buffer).
     *
     * The global fogRenderer uses a MappableRingBuffer whose currentBuffer() is
     * shared with the main renderer's terrainFog slice. Writing to it corrupts
     * the main world's fog → dark clipping artifacts across the entire world.
     */
    private static com.mojang.blaze3d.buffers.GpuBufferSlice writePortalFogBuffer(FogData fog) {
        // Build fog data into a ByteBuffer, then create a GPU buffer from it.
        // This avoids mapping an existing buffer (which may be in use by a render pass).
        // FOG_UBO_SIZE = 48 in MC (std140 padded: vec4(16) + 6*float(24) + 8 padding)
        // We must allocate exactly 48 bytes. Std140Builder writes 40 bytes of data;
        // remaining 8 bytes are std140 padding (zeros).
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(48)
            .order(java.nio.ByteOrder.nativeOrder());
        com.mojang.blaze3d.buffers.Std140Builder.intoBuffer(buf)
            .putVec4(fog.color)
            .putFloat(fog.environmentalStart)
            .putFloat(fog.environmentalEnd)
            .putFloat(fog.renderDistanceStart)
            .putFloat(fog.renderDistanceEnd)
            .putFloat(fog.skyEnd)
            .putFloat(fog.cloudEnd);
        // Include std140 padding — advance to full 48 bytes before flip
        buf.position(48);
        buf.flip();

        com.mojang.blaze3d.buffers.GpuBuffer gpuBuf = RenderSystem.getDevice().createBuffer(
            () -> "portal_fog", com.mojang.blaze3d.buffers.GpuBuffer.USAGE_UNIFORM, buf);
        return gpuBuf.slice(); // Full buffer slice
    }

    // ==================== Phase 1 Fallback (colored blocks) ====================

    private static void renderColoredBlocks(PortalInfo srcPortal, PortalLink link,
                                             Camera camera, ResourceKey<Level> destDim) {
        if (!RemoteChunkManager.hasDimensionData(destDim)) return;

        PortalInfo destPortal = link.getDestination();
        Vec3 playerPos = camera.position();
        BlockPos destOrigin = destPortal.getOrigin();
        Direction.Axis axis = srcPortal.getAxis();

        int offsetX = srcPortal.getOrigin().getX() - destOrigin.getX();
        int offsetY = srcPortal.getOrigin().getY() - destOrigin.getY();
        int offsetZ = srcPortal.getOrigin().getZ() - destOrigin.getZ();

        int depthSign;
        if (axis == Direction.Axis.X) {
            depthSign = (playerPos.z < srcPortal.getCenter().z) ? 1 : -1;
        } else {
            depthSign = (playerPos.x < srcPortal.getCenter().x) ? 1 : -1;
        }

        ByteBufferBuilder byteBuf = new ByteBufferBuilder(262144);
        BufferBuilder builder = new BufferBuilder(byteBuf, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (int d = -4; d <= 32; d++) {
            for (int w = -20; w < srcPortal.getWidth() + 20; w++) {
                for (int h = -20; h < srcPortal.getHeight() + 20; h++) {
                    int nx, ny, nz;
                    ny = destOrigin.getY() + h;
                    if (axis == Direction.Axis.X) {
                        nx = destOrigin.getX() + w;
                        nz = destOrigin.getZ() + (d * depthSign);
                    } else {
                        nx = destOrigin.getX() + (d * depthSign);
                        nz = destOrigin.getZ() + w;
                    }

                    BlockState state = RemoteChunkManager.getRemoteBlockState(destDim, new BlockPos(nx, ny, nz));
                    if (state == null || state.isAir()) continue;

                    float rx = (nx + offsetX) - (float) playerPos.x;
                    float ry = (ny + offsetY) - (float) playerPos.y;
                    float rz = (nz + offsetZ) - (float) playerPos.z;

                    int c = getColor(state);
                    int dk = darken(c, 0.7f);
                    int dkb = darken(c, 0.55f);

                    if (isAir(destDim, nx, ny+1, nz)) { builder.addVertex(rx,ry+1,rz).setColor(c); builder.addVertex(rx+1,ry+1,rz).setColor(c); builder.addVertex(rx+1,ry+1,rz+1).setColor(c); builder.addVertex(rx,ry+1,rz+1).setColor(c); }
                    if (isAir(destDim, nx, ny-1, nz)) { builder.addVertex(rx,ry,rz+1).setColor(dkb); builder.addVertex(rx+1,ry,rz+1).setColor(dkb); builder.addVertex(rx+1,ry,rz).setColor(dkb); builder.addVertex(rx,ry,rz).setColor(dkb); }
                    if (isAir(destDim, nx, ny, nz-1)) { builder.addVertex(rx+1,ry+1,rz).setColor(dk); builder.addVertex(rx,ry+1,rz).setColor(dk); builder.addVertex(rx,ry,rz).setColor(dk); builder.addVertex(rx+1,ry,rz).setColor(dk); }
                    if (isAir(destDim, nx, ny, nz+1)) { builder.addVertex(rx,ry+1,rz+1).setColor(dk); builder.addVertex(rx+1,ry+1,rz+1).setColor(dk); builder.addVertex(rx+1,ry,rz+1).setColor(dk); builder.addVertex(rx,ry,rz+1).setColor(dk); }
                    if (isAir(destDim, nx-1, ny, nz)) { builder.addVertex(rx,ry+1,rz).setColor(dk); builder.addVertex(rx,ry+1,rz+1).setColor(dk); builder.addVertex(rx,ry,rz+1).setColor(dk); builder.addVertex(rx,ry,rz).setColor(dk); }
                    if (isAir(destDim, nx+1, ny, nz)) { builder.addVertex(rx+1,ry+1,rz+1).setColor(dk); builder.addVertex(rx+1,ry+1,rz).setColor(dk); builder.addVertex(rx+1,ry,rz).setColor(dk); builder.addVertex(rx+1,ry,rz+1).setColor(dk); }
                }
            }
        }

        MeshData mesh = builder.build();
        if (mesh != null) {
            PortalRenderTypes.portalNoDepthColor().draw(mesh);
        } else {
            byteBuf.close();
        }
    }

    private static boolean isAir(ResourceKey<Level> dim, int x, int y, int z) {
        BlockState s = RemoteChunkManager.getRemoteBlockState(dim, new BlockPos(x, y, z));
        return s == null || s.isAir();
    }

    private static int darken(int c, float f) {
        int a = (c >> 24) & 0xFF;
        int r = (int)(((c >> 16) & 0xFF) * f);
        int g = (int)(((c >> 8) & 0xFF) * f);
        int b = (int)((c & 0xFF) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int getColor(BlockState s) {
        if (s.is(Blocks.NETHERRACK)) return 0xFF6B3030;
        if (s.is(Blocks.LAVA)) return 0xFFFF6600;
        if (s.is(Blocks.MAGMA_BLOCK)) return 0xFF8B3000;
        if (s.is(Blocks.GLOWSTONE)) return 0xFFFFCC66;
        if (s.is(Blocks.SOUL_SAND)) return 0xFF513A2A;
        if (s.is(Blocks.BASALT)) return 0xFF494949;
        if (s.is(Blocks.BLACKSTONE)) return 0xFF2A2A2A;
        if (s.is(Blocks.BEDROCK)) return 0xFF333333;
        if (s.is(Blocks.NETHER_BRICKS)) return 0xFF2D1515;
        if (s.is(Blocks.GRAVEL)) return 0xFF8B7D72;
        if (s.is(Blocks.OBSIDIAN)) return 0xFF0D0015;
        if (s.is(Blocks.FIRE)) return 0xFFFF4400;
        if (s.is(Blocks.STONE)) return 0xFF7F7F7F;
        if (s.is(Blocks.DIRT)) return 0xFF8B6843;
        if (s.is(Blocks.GRASS_BLOCK)) return 0xFF5D8C32;
        if (s.is(Blocks.SAND)) return 0xFFDBCD82;
        return 0xFF5A2828;
    }
}
