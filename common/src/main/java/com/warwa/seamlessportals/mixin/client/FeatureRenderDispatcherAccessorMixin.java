package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for the three final {@code bufferSource}-type fields on
 * {@link FeatureRenderDispatcher}.
 *
 * <p>The secondary {@link net.minecraft.client.renderer.LevelRenderer} we
 * use for portal-view rendering is constructed with its own
 * {@link net.minecraft.client.renderer.RenderBuffers} and its own
 * {@link FeatureRenderDispatcher}, and the dispatcher's buffer-source
 * fields are assigned at construction from that renderer's buffers. When
 * {@link com.warwa.seamlessportals.render.PortalContextSwitch#withSwitchedWorld}
 * swaps {@code LevelRenderer.renderBuffers} to a pooled
 * {@link net.minecraft.client.renderer.RenderBuffers} for the duration of
 * the portal render (so the main renderer's mid-frame buffer state isn't
 * corrupted), the feature dispatcher's own buffer references stay stale —
 * pointing at {@code destRenderBuffers.bufferSource()} which nothing in
 * vanilla flushes during the portal render.
 *
 * <p>Symptom: entity equipment (bow, sword, armor on head/hand) is
 * submitted via {@code FeatureRenderDispatcher.itemFeatureRenderer.renderSolid}
 * to the stale buffer, never flushed, never drawn. Body parts may happen
 * to render via a different path; equipment does not.
 *
 * <p>Fix plan: save the current buffer refs, overwrite to the pooled
 * buffer refs during {@code withSwitchedWorld}'s body, restore on exit.
 */
@Mixin(FeatureRenderDispatcher.class)
public interface FeatureRenderDispatcherAccessorMixin {

    @Accessor("bufferSource")
    MultiBufferSource.BufferSource seamlessportals$getBufferSource();

    @Accessor("bufferSource")
    @Mutable
    void seamlessportals$setBufferSource(MultiBufferSource.BufferSource bufferSource);

    @Accessor("outlineBufferSource")
    OutlineBufferSource seamlessportals$getOutlineBufferSource();

    @Accessor("outlineBufferSource")
    @Mutable
    void seamlessportals$setOutlineBufferSource(OutlineBufferSource outlineBufferSource);

    @Accessor("crumblingBufferSource")
    MultiBufferSource.BufferSource seamlessportals$getCrumblingBufferSource();

    @Accessor("crumblingBufferSource")
    @Mutable
    void seamlessportals$setCrumblingBufferSource(MultiBufferSource.BufferSource crumblingBufferSource);
}
