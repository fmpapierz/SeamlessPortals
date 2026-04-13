package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.client.PortalWorldManager;
import com.warwa.seamlessportals.mixin.client.*;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Context-switch rendering matching IP's MyGameRenderer.switchAndRenderTheWorld() EXACTLY.
 *
 * IP saves/swaps/restores 15+ fields. We do the same.
 * IP NEVER re-extracts on restore. We don't either.
 * Every field is logged for verification.
 */
public class PortalContextSwitch {

    /** Per-dimension success/fail tracking so nether renders get logged separately from overworld */
    private static final java.util.Map<String, Integer> successCounts = new java.util.HashMap<>();
    private static int failCount = 0;

    /** Flag for ClearSkipMixin. */
    public static boolean isRenderingPortal = false;

    /** Lightmap override for GameRendererLightmapMixin (kept until Phase 1C replaces it). */
    public static com.mojang.blaze3d.textures.GpuTextureView portalLightmapOverride = null;

    /** Cached sampler. */
    private static com.mojang.blaze3d.textures.GpuSampler chunkSampler = null;

    /** Temp lightmap until Phase 1C creates per-dimension ones. */
    private static Lightmap portalLightmap = null;

    public static void renderDestinationWorld(PortalInfo srcPortal, PortalLink link, Camera camera) {
        PortalInfo destPortal = link.getDestination();
        ResourceKey<Level> destDim = destPortal.getDimension();

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        LevelRenderer destRenderer = PortalWorldManager.getOrCreateRenderer(destDim);
        ClientLevel destLevel = PortalWorldManager.getLevel(destDim);
        if (destRenderer == null || destLevel == null) {
            if (failCount < 10) SeamlessPortalsConstants.LOGGER.info(
                "[CTX BAIL] no renderer/level for {} (r={}, l={})",
                destDim.identifier(), destRenderer != null, destLevel != null);
            failCount++;
            return;
        }
        int loaded = destLevel.getChunkSource().getLoadedChunksCount();
        if (loaded == 0) {
            // Log every 60 frames (~1 second) to avoid spam but still visible
            if (failCount % 60 == 0) SeamlessPortalsConstants.LOGGER.info(
                "[CTX BAIL] no chunks for {} (loaded=0, bail#{})", destDim.identifier(), failCount);
            failCount++;
            return;
        }

        try {
            doContextSwitchRender(srcPortal, link, camera, destDim, destRenderer, destLevel, mc);
        } catch (Exception e) {
            failCount++;
            if (failCount <= 5) {
                SeamlessPortalsConstants.LOGGER.error("[CTX] Render failed", e);
            }
        }
    }

    private static void doContextSwitchRender(
            PortalInfo srcPortal, PortalLink link, Camera mainCamera,
            ResourceKey<Level> destDim, LevelRenderer destRenderer,
            ClientLevel destLevel, Minecraft mc) {

        PortalInfo destPortal = link.getDestination();
        DeltaTracker deltaTracker = mc.getDeltaTracker();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        GameRenderState grs = mc.gameRenderer.getGameRenderState();
        GameRendererAccessorMixin grAccessor = (GameRendererAccessorMixin) mc.gameRenderer;

        // ================================================================
        // STEP 1: SAVE — IP saves 15+ fields by reference
        // ================================================================
        // 1. client.level
        ClientLevel savedLevel = mc.level;
        // 2. client.levelRenderer
        LevelRenderer savedRenderer = ((MinecraftAccessorMixin) mc).seamlessportals$getLevelRenderer();
        // 3. lightmap
        Lightmap savedLightmap = grAccessor.seamlessportals$getLightmap();
        // 4. blockEntityRenderDispatcher.cameraPos (MC 26.1.2: no level field)
        Vec3 savedBlockEntityCameraPos =
            ((BlockEntityRenderDispatcherAccessorMixin) mc.getBlockEntityRenderDispatcher())
                .seamlessportals$getCameraPos();
        // 5. player.noPhysics
        boolean savedNoPhysics = mc.player.noPhysics;
        // 6. chunkSectionsToRender (IP: oldChunkInfoList)
        ChunkSectionsToRender savedChunks = grs.levelRenderState.chunkSectionsToRender;
        // 7. hitResult
        HitResult savedHitResult = mc.hitResult;
        // 8. mainCamera
        Camera savedCamera = grAccessor.seamlessportals$getMainCamera();
        // 9. particleEngine.level
        ClientLevel savedParticleLevel =
            ((ParticleEngineAccessorMixin) mc.particleEngine).seamlessportals$getLevel();
        // 10. fog — DEEP COPY before extractLevel overwrites in-place.
        // FogData is mutable — saving a reference would point to the SAME object
        // that extractLevel() overwrites with destination fog values.
        net.minecraft.client.renderer.fog.FogData savedFogData = new net.minecraft.client.renderer.fog.FogData();
        {
            net.minecraft.client.renderer.fog.FogData src = grs.levelRenderState.cameraRenderState.fogData;
            savedFogData.environmentalStart = src.environmentalStart;
            savedFogData.renderDistanceStart = src.renderDistanceStart;
            savedFogData.environmentalEnd = src.environmentalEnd;
            savedFogData.renderDistanceEnd = src.renderDistanceEnd;
            savedFogData.skyEnd = src.skyEnd;
            savedFogData.cloudEnd = src.cloudEnd;
            savedFogData.color = new org.joml.Vector4f(src.color);
        }

        int dimSuccess = successCounts.getOrDefault(destDim.identifier().toString(), 0);
        if (dimSuccess < 3) {
            SeamlessPortalsConstants.LOGGER.info(
                "[CTX SAVE] level={}, fog=({},{},{},{}), noPhysics={}, hitResult={}",
                savedLevel.dimension().identifier(),
                String.format("%.2f", savedFogData.color.x), String.format("%.2f", savedFogData.color.y),
                String.format("%.2f", savedFogData.color.z), String.format("%.2f", savedFogData.color.w),
                savedNoPhysics, savedHitResult != null ? savedHitResult.getType() : "null"
            );
        }

        // ================================================================
        // STEP 2: Compute destination camera — IP: transformPoint()
        // ================================================================
        Vec3 srcCenter = srcPortal.getCenter();
        Vec3 destCenter = destPortal.getCenter();
        Vec3 playerPos = mainCamera.position();
        Vec3 destCameraPos = new Vec3(
            destCenter.x + (playerPos.x - srcCenter.x),
            destCenter.y + (playerPos.y - srcCenter.y),
            destCenter.z + (playerPos.z - srcCenter.z)
        );

        if (dimSuccess < 3) {
            SeamlessPortalsConstants.LOGGER.info(
                "[CTX CAMERA] srcPortal: origin={} center=({},{},{}), destPortal: origin={} center=({},{},{}), player=({},{},{}), destCam=({},{},{})",
                srcPortal.getOrigin(),
                String.format("%.1f", srcCenter.x), String.format("%.1f", srcCenter.y), String.format("%.1f", srcCenter.z),
                destPortal.getOrigin(),
                String.format("%.1f", destCenter.x), String.format("%.1f", destCenter.y), String.format("%.1f", destCenter.z),
                String.format("%.1f", playerPos.x), String.format("%.1f", playerPos.y), String.format("%.1f", playerPos.z),
                String.format("%.1f", destCameraPos.x), String.format("%.1f", destCameraPos.y), String.format("%.1f", destCameraPos.z)
            );
        }

        // IP: fresh Camera for destination
        Camera virtualCamera = new Camera();
        virtualCamera.setLevel(destLevel);
        virtualCamera.setEntity(mc.player);
        ((CameraInvokerMixin) virtualCamera).seamlessportals$invokeSetRotation(
            mainCamera.yRot(), mainCamera.xRot());
        ((CameraInvokerMixin) virtualCamera).seamlessportals$invokeSetPosition(destCameraPos);

        // Build frustum
        CameraRenderState mainCameraState = grs.levelRenderState.cameraRenderState;
        Matrix4f viewMatrix = new Matrix4f();
        virtualCamera.getViewRotationMatrix(viewMatrix);
        Frustum destFrustum = new Frustum(viewMatrix, new Matrix4f(mainCameraState.projectionMatrix));
        destFrustum.prepare(destCameraPos.x, destCameraPos.y, destCameraPos.z);
        ((CameraInvokerMixin) virtualCamera).seamlessportals$setCullFrustum(destFrustum);
        ((CameraInvokerMixin) virtualCamera).seamlessportals$setInitialized(true);

        // ================================================================
        // STEP 3: SWITCH — IP swaps all fields to destination
        // ================================================================
        // IP: client.level = newWorld
        mc.level = destLevel;
        // IP: client.levelRenderer = worldRenderer
        ((MinecraftAccessorMixin) mc).seamlessportals$setLevelRenderer(destRenderer);
        // IP: client.player.noPhysics = true
        mc.player.noPhysics = true;
        // IP: client.hitResult = null
        mc.hitResult = null;
        // IP: client.particleEngine.setWorld(newWorld)
        ((ParticleEngineAccessorMixin) mc.particleEngine).seamlessportals$setLevel(destLevel);
        // IP: client.gameRenderer.setCamera(newCamera)
        grAccessor.seamlessportals$setMainCamera(virtualCamera);
        // IP: blockEntityRenderDispatcher.prepare(destCameraPos)
        mc.getBlockEntityRenderDispatcher().prepare(destCameraPos);
        // IP: bufferSource.endBatch() — flush before render
        mc.renderBuffers().bufferSource().endBatch();

        if (dimSuccess < 3) {
            SeamlessPortalsConstants.LOGGER.info(
                "[CTX SWAP] level→{}, noPhysics→true, hitResult→null, camera→({},{},{})",
                destDim.identifier(),
                (int) destCameraPos.x, (int) destCameraPos.y, (int) destCameraPos.z
            );
        }

        // ================================================================
        // STEP 4: View center at camera — IP: follows camera, not portal
        // ================================================================
        destLevel.getChunkSource().updateViewCenter(
            (int)(destCameraPos.x) >> 4, (int)(destCameraPos.z) >> 4);

        // ================================================================
        // STEP 5: Compile sections + extractLevel
        // ================================================================
        net.minecraft.client.renderer.ViewArea viewArea =
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getViewArea();
        it.unimi.dsi.fastutil.objects.ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections =
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getVisibleSections();

        if (viewArea != null) {
            viewArea.repositionCamera(net.minecraft.core.SectionPos.of(destCameraPos));

            SectionRenderDispatcher dispatcher = destRenderer.getSectionRenderDispatcher();
            net.minecraft.client.renderer.chunk.RenderRegionCache cache =
                new net.minecraft.client.renderer.chunk.RenderRegionCache();

            visibleSections.clear();
            int compiled = 0;
            int maxCompilePerFrame = 32;

            for (SectionRenderDispatcher.RenderSection section : viewArea.sections) {
                if (section == null) continue;
                long sectionNode = section.getSectionNode();
                int sx = net.minecraft.core.SectionPos.x(sectionNode);
                int sz = net.minecraft.core.SectionPos.z(sectionNode);

                if (destLevel.getChunkSource().hasChunk(sx, sz)) {
                    if (section.isDirty() && compiled < maxCompilePerFrame) {
                        dispatcher.rebuildSectionSync(section, cache);
                        section.setNotDirty();
                        compiled++;
                    }
                    if (!section.isDirty()) {
                        visibleSections.add(section);
                    }
                }
            }

            if (dimSuccess < 3) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[CTX COMPILE] {} visible, {} compiled for {}",
                    visibleSections.size(), compiled, destDim.identifier());
            }
        }

        // extractLevel — populates chunkSectionsToRender, fogData, sky
        destRenderer.extractLevel(deltaTracker, virtualCamera, partialTick);

        ChunkSectionsToRender destChunks = grs.levelRenderState.chunkSectionsToRender;
        if (destChunks == null || destChunks.maxIndicesRequired() == 0) {
            // RESTORE on failure
            restoreAllState(mc, grAccessor, savedLevel, savedRenderer, savedLightmap,
                savedNoPhysics, savedHitResult, savedCamera, savedParticleLevel,
                savedChunks, savedBlockEntityCameraPos, savedFogData, grs, dimSuccess);
            failCount++;
            return;
        }

        // ================================================================
        // STEP 6: Fog — IP: FogRendererContext.pushSwapping(destDim)
        // ================================================================
        FogRenderer fogRenderer = grAccessor.seamlessportals$getFogRenderer();
        // extractLevel populated cameraRenderState.fogData for dest dimension.
        // Push to GPU. Save main fog for restore.
        net.minecraft.client.renderer.fog.FogData destFogData = grs.levelRenderState.cameraRenderState.fogData;
        FogContextManager.pushFog(fogRenderer, destFogData);

        // ================================================================
        // STEP 7: Lightmap — IP: gameRenderer.setLightmap(destLightmap)
        // Phase 1C will create per-dimension Lightmaps. For now, use override.
        // ================================================================
        if (portalLightmap == null) {
            portalLightmap = new Lightmap();
        }
        net.minecraft.client.renderer.state.LightmapRenderState lrs =
            new net.minecraft.client.renderer.state.LightmapRenderState();
        lrs.needsUpdate = true;
        lrs.darknessEffectScale = 0.0f;
        lrs.bossOverlayWorldDarkening = 0.0f;
        lrs.nightVisionColor = new org.joml.Vector3f(1f, 1f, 1f);
        lrs.blockLightTint = new org.joml.Vector3f(1f, 0.85f, 0.7f);
        if (destDim == Level.NETHER) {
            lrs.skyFactor = 0f; lrs.blockFactor = 1f; lrs.brightness = 0.1f;
            lrs.skyLightColor = new org.joml.Vector3f(1f, 1f, 1f);
            lrs.ambientColor = new org.joml.Vector3f(0.6f, 0.3f, 0.2f);
            lrs.nightVisionEffectIntensity = 0f;
        } else {
            lrs.skyFactor = 1f; lrs.blockFactor = 1f; lrs.brightness = 0f;
            lrs.skyLightColor = new org.joml.Vector3f(0.95f, 0.97f, 1f);
            lrs.ambientColor = new org.joml.Vector3f(0.9f, 0.9f, 0.9f);
            lrs.nightVisionEffectIntensity = 0f;
        }
        portalLightmap.render(lrs);
        portalLightmapOverride = portalLightmap.getTextureView();

        // ================================================================
        // STEP 8: RENDER
        // ================================================================
        if (chunkSampler == null) {
            chunkSampler = RenderSystem.getDevice().createSampler(
                com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
                com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
                com.mojang.blaze3d.textures.FilterMode.LINEAR,
                com.mojang.blaze3d.textures.FilterMode.LINEAR,
                1, java.util.OptionalDouble.empty()
            );
        }

        isRenderingPortal = true;

        destChunks.renderGroup(
            net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup.OPAQUE, chunkSampler);
        destChunks.renderGroup(
            net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup.TRANSLUCENT, chunkSampler);

        isRenderingPortal = false;

        // ================================================================
        // STEP 9: RESTORE — IP restores ALL by reference. NEVER re-extract.
        // ================================================================
        portalLightmapOverride = null;

        // IP: FogRendererContext.popSwapping()
        FogContextManager.popFog(fogRenderer, savedFogData);

        // Also restore the mutable fogData object on cameraRenderState.
        // extractLevel() mutated it in-place with destination fog values.
        // Without this, the NEXT frame's save reads stale destination fog.
        {
            net.minecraft.client.renderer.fog.FogData liveFog = grs.levelRenderState.cameraRenderState.fogData;
            liveFog.environmentalStart = savedFogData.environmentalStart;
            liveFog.renderDistanceStart = savedFogData.renderDistanceStart;
            liveFog.environmentalEnd = savedFogData.environmentalEnd;
            liveFog.renderDistanceEnd = savedFogData.renderDistanceEnd;
            liveFog.skyEnd = savedFogData.skyEnd;
            liveFog.cloudEnd = savedFogData.cloudEnd;
            liveFog.color.set(savedFogData.color);
        }

        restoreAllState(mc, grAccessor, savedLevel, savedRenderer, savedLightmap,
            savedNoPhysics, savedHitResult, savedCamera, savedParticleLevel,
            savedChunks, savedBlockEntityCameraPos, savedFogData, grs, dimSuccess);

        // IP: bufferSource.endBatch() — flush after render
        mc.renderBuffers().bufferSource().endBatch();

        String dimKey = destDim.identifier().toString();
        int newCount = successCounts.getOrDefault(dimKey, 0) + 1;
        successCounts.put(dimKey, newCount);
        if (newCount <= 3) {
            SeamlessPortalsConstants.LOGGER.info(
                "[CTX RENDER] #{} for {} at ({},{},{})",
                newCount, destDim.identifier(),
                (int) destCameraPos.x, (int) destCameraPos.y, (int) destCameraPos.z);
        }
    }

    /**
     * Restore ALL saved state by reference — IP's exact restore pattern.
     * NEVER calls extractLevel(). Just puts back every saved reference.
     */
    private static void restoreAllState(
            Minecraft mc, GameRendererAccessorMixin grAccessor,
            ClientLevel savedLevel, LevelRenderer savedRenderer,
            Lightmap savedLightmap, boolean savedNoPhysics,
            HitResult savedHitResult, Camera savedCamera,
            ClientLevel savedParticleLevel,
            ChunkSectionsToRender savedChunks,
            Vec3 savedBlockEntityCameraPos,
            net.minecraft.client.renderer.fog.FogData savedFogData,
            GameRenderState grs,
            int dimSuccessForRestore) {

        // IP: client.level = oldWorld
        mc.level = savedLevel;
        // IP: client.levelRenderer = oldWorldRenderer
        ((MinecraftAccessorMixin) mc).seamlessportals$setLevelRenderer(savedRenderer);
        // IP: client.player.noPhysics = oldNoClip
        mc.player.noPhysics = savedNoPhysics;
        // IP: client.hitResult = oldCrosshairTarget
        mc.hitResult = savedHitResult;
        // IP: client.particleEngine.setWorld(oldWorld)
        ((ParticleEngineAccessorMixin) mc.particleEngine).seamlessportals$setLevel(savedParticleLevel);
        // IP: client.gameRenderer.setCamera(oldCamera)
        grAccessor.seamlessportals$setMainCamera(savedCamera);
        // IP: blockEntityRenderDispatcher.prepare(oldCameraPos)
        mc.getBlockEntityRenderDispatcher().prepare(
            savedBlockEntityCameraPos != null ? savedBlockEntityCameraPos : Vec3.ZERO);
        // IP: oldWorldRenderer.setChunkInfoList(oldChunkInfoList)
        grs.levelRenderState.chunkSectionsToRender = savedChunks;

        if (dimSuccessForRestore < 3) {
            // Verify all fields match
            boolean levelMatch = mc.level == savedLevel;
            boolean rendererMatch = ((MinecraftAccessorMixin) mc).seamlessportals$getLevelRenderer() == savedRenderer;
            boolean physicsMatch = mc.player.noPhysics == savedNoPhysics;
            boolean hitMatch = mc.hitResult == savedHitResult;
            boolean cameraMatch = grAccessor.seamlessportals$getMainCamera() == savedCamera;
            boolean chunksMatch = grs.levelRenderState.chunkSectionsToRender == savedChunks;

            // Also verify fog was restored
            net.minecraft.client.renderer.fog.FogData currentFog = grs.levelRenderState.cameraRenderState.fogData;
            SeamlessPortalsConstants.LOGGER.info(
                "[CTX RESTORE] level={}, renderer={}, physics={}, hit={}, camera={}, chunks={}, fogColor=({},{},{},{})",
                levelMatch, rendererMatch, physicsMatch, hitMatch, cameraMatch, chunksMatch,
                String.format("%.2f", currentFog.color.x), String.format("%.2f", currentFog.color.y),
                String.format("%.2f", currentFog.color.z), String.format("%.2f", currentFog.color.w)
            );
        }
    }
}
