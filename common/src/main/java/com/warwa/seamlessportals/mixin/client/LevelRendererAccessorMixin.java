package com.warwa.seamlessportals.mixin.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for LevelRenderer private fields needed by Phase 2.
 *
 * The secondary renderer's occlusion graph BFS requires hasAllNeighbors()
 * for traversal. With sparse portal chunks (small patch, no surrounding
 * chunks loaded), the BFS can't traverse → visibleSections stays empty.
 *
 * We bypass the occlusion graph by directly accessing:
 * - viewArea: to get RenderSection objects and their chunk positions
 * - visibleSections: to manually add loaded sections for compilation
 *
 * Target fields (verified from LevelRenderer.java):
 *   private @Nullable ViewArea viewArea                              — line ~306
 *   private final ObjectArrayList<RenderSection> visibleSections     — line 147
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessorMixin {

    @Accessor("viewArea")
    ViewArea seamlessportals$getViewArea();

    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> seamlessportals$getVisibleSections();
}
