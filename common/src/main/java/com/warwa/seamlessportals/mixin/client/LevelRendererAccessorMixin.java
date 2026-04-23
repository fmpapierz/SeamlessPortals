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

    /**
     * Seed the "last camera section" bookkeeping fields used by
     * {@link LevelRenderer#cullTerrain} to decide whether the {@link ViewArea}
     * grid needs repositioning on a given frame. When we promote a cached
     * renderer on teleport, its stale values are from wherever the player
     * was when that dim was previously active — typically a different section
     * than the (preserved-player) section we're rendering from now. Without
     * seeding, the very first frame after promote detects a change,
     * unconditionally calls {@code viewArea.repositionCamera}, which in turn
     * calls {@code RenderSection.setSectionNode} on every relocated slot and
     * clears their compiled meshes via {@code reset()}. Result: a ~1-frame
     * flash where the primary world hasn't rendered because all nearby
     * section meshes were just wiped.
     *
     * Fields verified in {@code LevelRenderer.java:155-157}:
     * {@code private int lastCameraSectionX/Y/Z = Integer.MIN_VALUE}.
     *
     * See memory: {@code viewarea_reposition_mesh_loss.md}.
     */
    @Accessor("lastCameraSectionX")
    @Mutable
    void seamlessportals$setLastCameraSectionX(int x);

    @Accessor("lastCameraSectionY")
    @Mutable
    void seamlessportals$setLastCameraSectionY(int y);

    @Accessor("lastCameraSectionZ")
    @Mutable
    void seamlessportals$setLastCameraSectionZ(int z);

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
}
