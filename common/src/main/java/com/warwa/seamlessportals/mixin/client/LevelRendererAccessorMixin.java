package com.warwa.seamlessportals.mixin.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for LevelRenderer private fields needed by portal rendering.
 *
 * Target fields (verified from LevelRenderer.java):
 *   private @Nullable ViewArea viewArea                              — line ~306
 *   private final ObjectArrayList<RenderSection> visibleSections     — line 147
 *   private final LevelRenderState levelRenderState                  — line 169
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessorMixin {

    @Accessor("viewArea")
    ViewArea seamlessportals$getViewArea();

    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> seamlessportals$getVisibleSections();

    /**
     * Read the renderer's LevelRenderState.
     * For secondary renderers with isolated state, this returns their OWN state
     * (not the shared GameRenderState.levelRenderState).
     */
    @Accessor("levelRenderState")
    LevelRenderState seamlessportals$getLevelRenderState();

    /**
     * Replace the renderer's LevelRenderState with an isolated instance.
     * Called after constructing secondary renderers so extractLevel() doesn't
     * corrupt the main renderer's state.
     *
     * Matches IP's architecture where each LevelRenderer has its own state.
     * MC 26.1.2 shares via GameRenderState; this mixin restores isolation.
     */
    @Accessor("levelRenderState")
    @Mutable
    void seamlessportals$setLevelRenderState(LevelRenderState state);

    // 26.2: the {@code lastCameraSectionX/Y/Z} int fields were REMOVED from
    // LevelRenderer. The "did the camera section change since last frame?" gate
    // that {@code cullTerrain} used to consult them for is now encapsulated
    // inside {@link ViewArea#repositionCamera(net.minecraft.core.SectionPos)},
    // which returns {@code boolean} (true iff the grid actually re-centered) and
    // tracks the center internally via its {@code RotatingSectionStorage}
    // ({@link ViewArea#getCameraSectionPos()} reads it). The old
    // seamlessportals$setLastCameraSection{X,Y,Z} accessors were therefore
    // removed; callers now seed the grid center directly through the public
    // {@code viewArea.repositionCamera(SectionPos.of(...))} (see
    // {@code SeamlessClientTeleport} / {@code HandleRespawnMixin}).

    /**
     * Read this renderer's {@link RenderBuffers}. For PortalWorldManager-built
     * secondaries this is their own private instance; for the vanilla
     * {@code mc.levelRenderer} this is the same instance as
     * {@code mc.renderBuffers}. Both get swapped to a pooled buffer during
     * portal sub-render via {@link com.warwa.seamlessportals.render.PortalContextSwitch#withSwitchedWorld}.
     */
    @Accessor("renderBuffers")
    RenderBuffers seamlessportals$getRenderBuffers();

    @Accessor("renderBuffers")
    @Mutable
    void seamlessportals$setRenderBuffers(RenderBuffers renderBuffers);

    /**
     * The {@link net.minecraft.client.renderer.feature.FeatureRenderDispatcher}
     * tied to this renderer. Secondary renderers created by
     * {@link com.warwa.seamlessportals.client.PortalWorldManager#createRenderer}
     * own their own dispatcher; exposing this lets
     * {@link com.warwa.seamlessportals.render.PortalContextSwitch#withSwitchedWorld}
     * reach into the dispatcher to swap its buffer-source references in
     * sync with the renderBuffers swap, so entity-equipment item draws go
     * to the actively-flushed buffer rather than the dormant
     * {@code destRenderBuffers.bufferSource()}.
     */
    @Accessor("featureRenderDispatcher")
    net.minecraft.client.renderer.feature.FeatureRenderDispatcher seamlessportals$getFeatureRenderDispatcher();

    /**
     * 26.2: the {@code featureRenderDispatcher} field is now {@code final} and
     * is derived from the passed {@code GameRenderer} inside the LevelRenderer
     * constructor. Secondary portal-view renderers need their OWN isolated
     * dispatcher (the old explicit-arg ctor gave us that directly); we override
     * the field after construction to restore that isolation.
     */
    @Accessor("featureRenderDispatcher")
    @Mutable
    void seamlessportals$setFeatureRenderDispatcher(
        net.minecraft.client.renderer.feature.FeatureRenderDispatcher dispatcher);

    /**
     * The block-atlas {@link com.mojang.blaze3d.textures.GpuSampler} that
     * {@code LevelRenderer.addMainPass} hands to
     * {@code ChunkSectionsToRender.renderGroup(...)} (LevelRenderer.java:127/402/409).
     *
     * <p>Phase 5 (stencil-direct): the dest-world terrain is drawn into the main
     * target via a direct {@code renderGroup(OPAQUE, sampler)} call instead of a
     * nested {@code render(...)} framegraph. {@code renderGroup} needs this sampler.
     * The dest renderer's own sampler is {@code null} (its main pass never runs), so
     * we read the MAIN renderer's — it is a plain CLAMP_TO_EDGE/LINEAR atlas sampler,
     * not renderer-specific, and is live by {@code AFTER_TRANSLUCENT_TERRAIN} (the
     * main pass created it at LevelRenderer.java:402 before drawing opaque terrain).
     */
    @Accessor("chunkLayerSampler")
    com.mojang.blaze3d.textures.GpuSampler seamlessportals$getChunkLayerSampler();
}
