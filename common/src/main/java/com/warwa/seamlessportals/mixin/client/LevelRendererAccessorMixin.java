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

    /**
     * The renderer's {@link net.minecraft.client.resources.model.sprite.AtlasManager} — needed
     * to construct a {@code SkyRenderer} for the portal-view dest sky (Phase 5 Step 2b). The dest
     * renderer's own {@code skyRenderer} is null (its {@code addSkyPass} never runs), so we build
     * one from this + the texture manager + the main target and drive it with the dest sky state.
     */
    @Accessor("atlasManager")
    net.minecraft.client.resources.model.sprite.AtlasManager seamlessportals$getAtlasManager();

    /**
     * The renderer's {@link net.minecraft.client.renderer.SubmitNodeStorage} — the submit buffer
     * that {@code submitFeatures} fills and {@code featureRenderDispatcher.prepareFrame} consumes.
     * Phase 5 Step 2c: needed to render the dest dimension's entities/block-entities/particles in
     * the portal view (the FBO path did this inside {@code render()}; the direct path must drive
     * submit→prepare→execute itself).
     */
    @Accessor("submitNodeStorage")
    net.minecraft.client.renderer.SubmitNodeStorage seamlessportals$getSubmitNodeStorage();

    /**
     * Invoke the renderer's private {@code submitFeatures(...)} — gathers the entity / block-entity
     * / particle render states (already extracted into {@code levelRenderState}) into the submit
     * storage so {@code FeatureRenderDispatcher.renderAllFeatures} can draw them. {@code renderOutline}
     * = false in the portal view (no glow outlines).
     */
    @org.spongepowered.asm.mixin.gen.Invoker("submitFeatures")
    void seamlessportals$invokeSubmitFeatures(
        LevelRenderState levelRenderState,
        net.minecraft.client.renderer.SubmitNodeCollector submitNodeCollector,
        boolean renderOutline);

    /**
     * Invoke the renderer's private {@code compileSections(CameraRenderState)}
     * (LevelRenderer.java:608) — the ONLY vanilla consumer of the
     * {@code levelRenderState.sectionUpdateRenderStates} queue that
     * {@code LevelExtractor.extract}'s sectionUpdates loop fills (each entry's
     * dirty flag is consumed by {@code setNotDirty} at queue time,
     * LevelExtractor.java:167). Vanilla pairs them inside {@code render()}
     * (:254-255); the stencil-direct portal path never calls {@code render()}
     * for the dest renderer, so it must invoke this directly after each
     * {@code extract(...)} or the queued compiles are discarded by the next
     * extract's {@code levelRenderState.reset()} — stranding sections
     * dirty=false + UNCOMPILED forever (the post-crossing chunk-hole bug).
     * Invoking the real method keeps the 1:1 vanilla semantics (fade windows,
     * sync-nearby compile options, translucent resort) and the
     * {@link LevelRendererCompileSectionsMixin} out-of-range filter applies
     * to it automatically.
     */
    @org.spongepowered.asm.mixin.gen.Invoker("compileSections")
    void seamlessportals$invokeCompileSections(
        net.minecraft.client.renderer.state.level.CameraRenderState camera);
}
