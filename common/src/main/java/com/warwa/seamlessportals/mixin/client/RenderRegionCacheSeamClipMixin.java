package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.SeamClipRegionAccess;
import com.warwa.seamlessportals.render.SeamClipRenderer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SEAM CLIP arm 1 (the snapshot) — at {@code createRegion} RETURN, on the MAIN thread (every call
 * site is game-thread: vanilla {@code LevelExtractor.extract} plus the mod's tick/pass-driven
 * callers — panel-enumerated in SEAM_CLIP_DESIGN.md §2), compute the region's qualifying seam
 * cells from the live index + live block states and stash them immutably on the region duck for
 * the compile worker. Covers the FULL 3×3×3 bounds so neighbour compiles see AIR at border cells
 * too (the un-cull). Null set on regions without seam cells — near-zero cost.
 *
 * <p>Target javap-verified: single {@code createRegion(ClientLevel, long)} on RenderRegionCache.
 */
@Mixin(RenderRegionCache.class)
public abstract class RenderRegionCacheSeamClipMixin {

    @Inject(method = "createRegion", at = @At("RETURN"), require = 1)
    private void seamlessportals$stashSeamClipExclusions(
        ClientLevel level, long sectionNode, CallbackInfoReturnable<RenderSectionRegion> cir
    ) {
        RenderSectionRegion region = cir.getReturnValue();
        if (region == null) {
            return;
        }
        LongOpenHashSet cells = SeamClipRenderer.computeRegionExclusions(level, sectionNode);
        if (cells != null && !cells.isEmpty()) {
            ((SeamClipRegionAccess) region).seamlessportals$setSeamClipExclusions(cells);
        }
    }
}
