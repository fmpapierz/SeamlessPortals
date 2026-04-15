package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.portal.PortalType;
import com.warwa.seamlessportals.render.PortalFrameSuppressor;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppress blocks during section compilation:
 * 1. Nether portal blocks (purple swirl) — replaced by stencil view
 * 2. Destination portal obsidian frame — only during FBO compilation
 *
 * Uses getBlockState redirect to access both BlockState and BlockPos.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {

    @Redirect(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        )
    )
    private BlockState seamlessportals$redirectGetBlockState(RenderSectionRegion region, BlockPos pos) {
        BlockState state = region.getBlockState(pos);

        // Suppress nether portal purple swirl (replaced by stencil rendering)
        if (state.is(Blocks.NETHER_PORTAL) && SeamlessPortalsConfig.shouldRenderThrough(PortalType.NETHER)) {
            return Blocks.AIR.defaultBlockState();
        }

        // Suppress destination portal obsidian frame during FBO compilation
        if (state.is(Blocks.OBSIDIAN) && PortalFrameSuppressor.isFrameBlock(pos)) {
            return Blocks.AIR.defaultBlockState();
        }

        return state;
    }
}
