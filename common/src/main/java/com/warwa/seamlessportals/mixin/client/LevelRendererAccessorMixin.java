package com.warwa.seamlessportals.mixin.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
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
}
