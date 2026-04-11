package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.client.PortalWorldManager;
import com.warwa.seamlessportals.mixin.client.CameraInvokerMixin;
import com.warwa.seamlessportals.mixin.client.GameRendererAccessorMixin;
import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * Handles destination world rendering through the stencil mask.
 *
 * Phase 1: Colored blocks from RemoteChunkManager (CURRENT - working).
 * Phase 2: Full context-switch rendering with vanilla's LevelRenderer (IN PROGRESS).
 *
 * Following IP's exact context-switch pattern from MyGameRenderer.switchAndRenderTheWorld():
 * 1. Save state (level, levelRenderer, matrices)
 * 2. Flush buffers
 * 3. Swap to destination (level, renderer, fresh matrix stack)
 * 4. Render via LevelRenderer.renderLevel()
 * 5. Restore all state
 *
 * Phase 2 is gated behind a flag. Falls back to Phase 1 if secondary renderer
 * isn't ready (chunks not compiled yet).
 */
public class PortalContextSwitch {

    private static boolean loggedFirst = false;
    private static boolean phase2Attempted = false;

    /**
     * Flag for ClearSkipMixin. Currently unused since we use renderGroup()
     * instead of renderLevel(), but kept for future full-render support.
     */
    public static boolean isRenderingPortal = false;

    /** Cached sampler for chunk terrain rendering. Created once, reused. */
    private static com.mojang.blaze3d.textures.GpuSampler chunkSampler = null;

    /** Secondary lightmap for portal rendering. Uses destination dimension lighting. */
    private static net.minecraft.client.renderer.Lightmap portalLightmap = null;

    /**
     * Override for GameRenderer.lightmap() during portal rendering.
     * Set non-null before renderGroup(), null after.
     * Read by GameRendererLightmapMixin to return the portal lightmap.
     */
    public static com.mojang.blaze3d.textures.GpuTextureView portalLightmapOverride = null;

    /**
     * Render the destination dimension through the stencil mask.
     * Tries Phase 2 (context-switch) first, falls back to Phase 1 (colored blocks).
     */
    public static void renderDestinationWorld(PortalInfo srcPortal, PortalLink link, Camera camera) {
        PortalInfo destPortal = link.getDestination();
        ResourceKey<Level> destDim = destPortal.getDimension();

        // Try Phase 2: context-switch rendering with vanilla renderer
        if (tryPhase2Render(srcPortal, link, camera, destDim)) {
            return;
        }

        // Fall back to Phase 1: colored blocks
        renderColoredBlocks(srcPortal, link, camera, destDim);
    }

    private static int phase2FailCount = 0;
    private static int phase2SuccessCount = 0;
    /** Track which dimensions have had chunks fed — must be per-dimension, not global. */
    private static final java.util.Set<ResourceKey<Level>> chunksEverFed = new java.util.HashSet<>();

    /**
     * Phase 2: Render destination chunk terrain through the stencil mask.
     *
     * Uses ChunkSectionsToRender.renderGroup(OPAQUE) directly instead of the
     * full renderLevel(). renderLevel() creates a nested framegraph with passes
     * on DIFFERENT FBOs (sky, translucent, entity outline) that don't have our
     * stencil values — breaking the stencil mask completely.
     *
     * renderGroup(OPAQUE) creates ONE RenderPass on the MAIN render target
     * (where our stencil lives). applyPipelineState() never touches stencil
     * (verified: MC 26.1.2 has ZERO stencil references). So GL_STENCIL_TEST
     * with GL_EQUAL(1) persists and clips terrain to the portal area.
     *
     * Flow:
     * 1. Get/create secondary renderer + level
     * 2. Feed chunks into secondary level's ClientChunkCache
     * 3. Create virtual camera at destination position
     * 4. Call destRenderer.extractLevel() to build chunk draw lists
     * 5. Call destChunks.renderGroup(OPAQUE, sampler) through stencil mask
     * 6. Restore saved state
     */
    private static boolean tryPhase2Render(PortalInfo srcPortal, PortalLink link,
                                            Camera mainCamera, ResourceKey<Level> destDim) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;

        // ===== 1. Get or create secondary renderer =====
        LevelRenderer destRenderer = PortalWorldManager.getOrCreateRenderer(destDim);
        ClientLevel destLevel = PortalWorldManager.getLevel(destDim);
        if (destRenderer == null || destLevel == null) return false;

        // ===== 2. Set view center at portal destination FIRST, then feed chunks =====
        // CRITICAL: updateViewCenter must be called BEFORE any replaceWithPacketData,
        // otherwise ClientChunkCache.inRange() rejects chunks far from default center (0,0).
        // IP sets view center at the destination portal, then feeds all chunks.
        PortalInfo destPortal = link.getDestination();
        BlockPos destOrigin = destPortal.getOrigin();
        destLevel.getChunkSource().updateViewCenter(
            destOrigin.getX() >> 4, destOrigin.getZ() >> 4);

        if (!chunksEverFed.contains(destDim)) {
            PortalWorldManager.feedExistingChunks(destDim);
            chunksEverFed.add(destDim);
        }

        if (RemoteChunkManager.getChunkCount(destDim) == 0) {
            return false;
        }

        try {
            return doRenderGroupRender(srcPortal, link, mainCamera, destDim,
                                        destRenderer, destLevel, mc);
        } catch (Exception e) {
            if (phase2FailCount <= 3) {
                SeamlessPortalsConstants.LOGGER.error(
                    "[SEAMLESS PHASE2] renderGroup render failed", e);
            }
            phase2FailCount++;
            return false;
        }
    }

    /**
     * Phase 2 render using renderGroup(OPAQUE) directly.
     *
     * Unlike the failed renderLevel() approach (which created nested framegraph
     * passes on DIFFERENT FBOs without stencil), renderGroup() creates ONE
     * RenderPass on the MAIN render target where our stencil values live.
     * MC 26.1.2's applyPipelineState() NEVER touches stencil state (verified:
     * zero stencil references in entire MC codebase). So GL_STENCIL_TEST with
     * GL_EQUAL(1) persists and clips terrain to the portal area.
     */
    private static boolean doRenderGroupRender(
            PortalInfo srcPortal, PortalLink link, Camera mainCamera,
            ResourceKey<Level> destDim, LevelRenderer destRenderer,
            ClientLevel destLevel, Minecraft mc) {

        PortalInfo destPortal = link.getDestination();
        DeltaTracker deltaTracker = mc.getDeltaTracker();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

        // ===== 3. Compute destination camera position =====
        // Following IP's transformPoint(): destPos + (cameraPos - srcPos)
        Vec3 srcCenter = srcPortal.getCenter();
        Vec3 destCenter = destPortal.getCenter();
        Vec3 playerPos = mainCamera.position();
        Vec3 destCameraPos = new Vec3(
            destCenter.x + (playerPos.x - srcCenter.x),
            destCenter.y + (playerPos.y - srcCenter.y),
            destCenter.z + (playerPos.z - srcCenter.z)
        );

        // ===== 4. Create virtual camera (IP creates fresh Camera) =====
        Camera virtualCamera = new Camera();
        virtualCamera.setLevel(destLevel);
        virtualCamera.setEntity(mc.player);
        ((CameraInvokerMixin) virtualCamera).seamlessportals$invokeSetRotation(
            mainCamera.yRot(), mainCamera.xRot());
        ((CameraInvokerMixin) virtualCamera).seamlessportals$invokeSetPosition(destCameraPos);

        // Build frustum for chunk culling
        CameraRenderState mainCameraState =
            mc.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
        Matrix4f viewMatrix = new Matrix4f();
        virtualCamera.getViewRotationMatrix(viewMatrix);
        Matrix4f projForCulling = new Matrix4f(mainCameraState.projectionMatrix);
        Frustum destFrustum = new Frustum(viewMatrix, projForCulling);
        destFrustum.prepare(destCameraPos.x, destCameraPos.y, destCameraPos.z);
        ((CameraInvokerMixin) virtualCamera).seamlessportals$setCullFrustum(destFrustum);
        ((CameraInvokerMixin) virtualCamera).seamlessportals$setInitialized(true);

        // ===== 5. Save shared state =====
        GameRenderState grs = mc.gameRenderer.getGameRenderState();
        ChunkSectionsToRender savedChunks = grs.levelRenderState.chunkSectionsToRender;
        // Fog: NOT saving/restoring because updateBuffer() permanently overwrites
        // the GPU buffer. Nether will use overworld fog for now — acceptable tradeoff.

        // View center already set in tryPhase2Render() step 2

        // ===== 6. Direct section compilation (bypass occlusion graph) =====
        // The normal pipeline uses SectionOcclusionGraph BFS to find visible sections.
        // BFS requires hasAllNeighbors() (all 8 chunk neighbors loaded) for traversal.
        // With sparse portal chunks (small patch, no surrounding chunks), BFS can't
        // traverse → visibleSections stays empty → nothing compiles.
        //
        // IP doesn't have this problem because IP loads full worlds with neighbors.
        // We bypass the graph: directly iterate ViewArea sections, compile loaded ones
        // synchronously, and add them to visibleSections.
        net.minecraft.client.renderer.ViewArea viewArea =
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getViewArea();
        it.unimi.dsi.fastutil.objects.ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections =
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getVisibleSections();

        if (viewArea != null) {
            // Reposition ViewArea around destination camera
            viewArea.repositionCamera(net.minecraft.core.SectionPos.of(destCameraPos));

            SectionRenderDispatcher dispatcher = destRenderer.getSectionRenderDispatcher();
            net.minecraft.client.renderer.chunk.RenderRegionCache cache =
                new net.minecraft.client.renderer.chunk.RenderRegionCache();

            visibleSections.clear();
            int compiled = 0;

            for (SectionRenderDispatcher.RenderSection section : viewArea.sections) {
                if (section == null) continue;
                long sectionNode = section.getSectionNode();
                int sx = net.minecraft.core.SectionPos.x(sectionNode);
                int sy = net.minecraft.core.SectionPos.y(sectionNode);
                int sz = net.minecraft.core.SectionPos.z(sectionNode);

                // Check if the chunk at this section is loaded in the ClientChunkCache
                if (destLevel.getChunkSource().hasChunk(sx, sz)) {
                    // Compile dirty sections synchronously (bypass async + neighbor check)
                    if (section.isDirty()) {
                        dispatcher.rebuildSectionSync(section, cache);
                        section.setNotDirty();
                    }
                    // Add ALL sections with loaded chunks to visibleSections
                    visibleSections.add(section);
                    compiled++;
                }
            }

            if (phase2SuccessCount == 0 && compiled > 0) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS PHASE2] Direct compilation: {} sections compiled/visible", compiled);
            }
        }

        // ===== 8. Extract level state → populates chunkSectionsToRender =====
        destRenderer.extractLevel(deltaTracker, virtualCamera, partialTick);

        // ===== 9. Get chunk draw list =====
        ChunkSectionsToRender destChunks = grs.levelRenderState.chunkSectionsToRender;
        if (destChunks == null || destChunks.maxIndicesRequired() == 0) {
            grs.levelRenderState.chunkSectionsToRender = savedChunks;
            if (phase2FailCount <= 5) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS PHASE2] Chunks not compiled yet for {} - Phase 1 fallback (fail #{})",
                    destDim.identifier(), phase2FailCount + 1);
            }
            phase2FailCount++;
            return false; // Fall back to Phase 1
        }

        // ===== 10. Fog handling =====
        // NOTE: We do NOT update fogRenderer.updateBuffer() here because it
        // permanently overwrites the GPU buffer. The saved GpuBufferSlice points
        // to the SAME buffer, so restoring it doesn't undo the overwrite.
        // This causes the overworld to render with nether fog (near-black).
        // TODO: Create a separate fog buffer for the portal render, or
        // save/restore the actual fog data bytes, not just the slice reference.

        // ===== 11. Set up portal lightmap for destination dimension =====
        // renderGroup() binds minecraft.gameRenderer.lightmap() as Sampler2.
        // Without swapping, nether lightmap (red tint) is used for overworld terrain.
        // IP swaps lightmap per dimension. We create a second Lightmap with neutral
        // lighting and override GameRenderer.lightmap() via mixin during renderGroup().
        if (portalLightmap == null) {
            portalLightmap = new net.minecraft.client.renderer.Lightmap();
        }
        // Build neutral LightmapRenderState for the destination dimension
        net.minecraft.client.renderer.state.LightmapRenderState destLightState =
            new net.minecraft.client.renderer.state.LightmapRenderState();
        destLightState.needsUpdate = true;
        destLightState.darknessEffectScale = 0.0f;
        destLightState.bossOverlayWorldDarkening = 0.0f;
        destLightState.nightVisionColor = new org.joml.Vector3f(1.0f, 1.0f, 1.0f);
        destLightState.blockLightTint = new org.joml.Vector3f(1.0f, 0.85f, 0.7f);

        if (destDim == Level.NETHER) {
            // Nether: no sky light, warm ambient. Block light from glowstone/lava.
            destLightState.skyFactor = 0.0f;
            destLightState.blockFactor = 1.0f;
            destLightState.brightness = 0.1f; // DimensionType nether ambient = 0.1
            destLightState.skyLightColor = new org.joml.Vector3f(1.0f, 1.0f, 1.0f);
            destLightState.ambientColor = new org.joml.Vector3f(0.6f, 0.3f, 0.2f);
            destLightState.nightVisionEffectIntensity = 0.0f;
        } else {
            // Overworld: full sky light + block light.
            // Light data now sent from server via PortalChunkTracker (sky + block
            // DataLayers serialized and applied to secondary LevelLightEngine).
            // No more nightvision hack needed.
            destLightState.skyFactor = 1.0f;
            destLightState.blockFactor = 1.0f;
            destLightState.brightness = 0.0f; // DimensionType overworld ambient = 0.0
            destLightState.skyLightColor = new org.joml.Vector3f(0.95f, 0.97f, 1.0f); // slight blue sky
            destLightState.ambientColor = new org.joml.Vector3f(0.9f, 0.9f, 0.9f);
            destLightState.nightVisionEffectIntensity = 0.0f;
        }
        portalLightmap.render(destLightState);

        // Override GameRenderer.lightmap() to return our portal lightmap
        portalLightmapOverride = portalLightmap.getTextureView();

        // ===== 12. Render chunk terrain through stencil mask =====
        // renderGroup(OPAQUE) creates ONE RenderPass on the main render target.
        // Stencil test (GL_EQUAL, 1) clips all terrain to the portal area.
        // applyPipelineState() NEVER touches stencil (verified: MC 26.1.2 has
        // zero stencil references). No framegraph nesting, no FBO switching.
        if (chunkSampler == null) {
            chunkSampler = RenderSystem.getDevice().createSampler(
                com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
                com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
                com.mojang.blaze3d.textures.FilterMode.LINEAR,
                com.mojang.blaze3d.textures.FilterMode.LINEAR,
                1, java.util.OptionalDouble.empty()
            );
        }

        destChunks.renderGroup(
            net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup.OPAQUE,
            chunkSampler
        );

        // ===== 13. Restore saved state =====
        portalLightmapOverride = null; // Stop overriding lightmap
        grs.levelRenderState.chunkSectionsToRender = savedChunks;

        phase2SuccessCount++;
        if (phase2SuccessCount <= 5) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PHASE2] renderGroup SUCCESS #{} for {} - maxIndices={} at ({}, {}, {})",
                phase2SuccessCount, destDim.identifier(), destChunks.maxIndicesRequired(),
                (int) destCameraPos.x, (int) destCameraPos.y, (int) destCameraPos.z
            );
        }

        return true;
    }

    /**
     * Phase 1: Render colored blocks from RemoteChunkManager.
     * This is the working implementation with stencil masking.
     */
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

        // axis = WIDTH direction. Depth perpendicular to face.
        int depthSign;
        if (axis == Direction.Axis.X) {
            depthSign = (playerPos.z < srcPortal.getCenter().z) ? 1 : -1;
        } else {
            depthSign = (playerPos.x < srcPortal.getCenter().x) ? 1 : -1;
        }

        ByteBufferBuilder byteBuf = new ByteBufferBuilder(262144);
        BufferBuilder builder = new BufferBuilder(byteBuf, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int blocksDrawn = 0;

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

                    if (isAir(destDim, nx, ny+1, nz)) {
                        builder.addVertex(rx,ry+1,rz).setColor(c);
                        builder.addVertex(rx+1,ry+1,rz).setColor(c);
                        builder.addVertex(rx+1,ry+1,rz+1).setColor(c);
                        builder.addVertex(rx,ry+1,rz+1).setColor(c);
                    }
                    if (isAir(destDim, nx, ny-1, nz)) {
                        builder.addVertex(rx,ry,rz+1).setColor(dkb);
                        builder.addVertex(rx+1,ry,rz+1).setColor(dkb);
                        builder.addVertex(rx+1,ry,rz).setColor(dkb);
                        builder.addVertex(rx,ry,rz).setColor(dkb);
                    }
                    if (isAir(destDim, nx, ny, nz-1)) {
                        builder.addVertex(rx+1,ry+1,rz).setColor(dk);
                        builder.addVertex(rx,ry+1,rz).setColor(dk);
                        builder.addVertex(rx,ry,rz).setColor(dk);
                        builder.addVertex(rx+1,ry,rz).setColor(dk);
                    }
                    if (isAir(destDim, nx, ny, nz+1)) {
                        builder.addVertex(rx,ry+1,rz+1).setColor(dk);
                        builder.addVertex(rx+1,ry+1,rz+1).setColor(dk);
                        builder.addVertex(rx+1,ry,rz+1).setColor(dk);
                        builder.addVertex(rx,ry,rz+1).setColor(dk);
                    }
                    if (isAir(destDim, nx-1, ny, nz)) {
                        builder.addVertex(rx,ry+1,rz).setColor(dk);
                        builder.addVertex(rx,ry+1,rz+1).setColor(dk);
                        builder.addVertex(rx,ry,rz+1).setColor(dk);
                        builder.addVertex(rx,ry,rz).setColor(dk);
                    }
                    if (isAir(destDim, nx+1, ny, nz)) {
                        builder.addVertex(rx+1,ry+1,rz+1).setColor(dk);
                        builder.addVertex(rx+1,ry+1,rz).setColor(dk);
                        builder.addVertex(rx+1,ry,rz).setColor(dk);
                        builder.addVertex(rx+1,ry,rz+1).setColor(dk);
                    }
                    blocksDrawn++;
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
