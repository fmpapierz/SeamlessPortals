package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.jspecify.annotations.Nullable;

/**
 * Expose {@link ViewArea#getRenderSection(long)} so non-vanilla code can
 * resolve a {@link SectionRenderDispatcher.RenderSection} from a packed
 * {@link net.minecraft.core.SectionPos} node id without iterating the
 * entire {@link ViewArea#sections} array (~26k entries on default render
 * distance).
 *
 * <p>Used by {@link com.warwa.seamlessportals.chunk.RemoteBlockUpdater} to
 * directly schedule {@code rebuildSectionAsync} on the section that
 * received a mirrored block update — bypassing the radius-cull in the
 * per-frame ({@code PortalContextSwitch}) and per-tick
 * ({@code PortalWorldManager.advanceCompilePipelines}) compile pumps.
 */
@Mixin(ViewArea.class)
public interface ViewAreaInvokerMixin {

    @Invoker("getRenderSection")
    SectionRenderDispatcher.@Nullable RenderSection seamlessportals$invokeGetRenderSection(long sectionNode);
}
