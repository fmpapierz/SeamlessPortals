package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.portal.PortalType;
import com.warwa.seamlessportals.render.PortalFrameSuppressor;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppress blocks during section compilation:
 * 1. Nether portal blocks (purple swirl) — via getRenderShape redirect (proven working)
 * 2. Destination portal obsidian frame — via getBlockState redirect (needs BlockPos)
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {

    /**
     * Suppress nether portal purple swirl by returning INVISIBLE render shape.
     * This prevents the animated portal texture from being compiled into the mesh.
     */
    @Redirect(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"
        )
    )
    private RenderShape seamlessportals$suppressPortalSwirl(BlockState blockState) {
        if (blockState.is(Blocks.NETHER_PORTAL) && SeamlessPortalsConfig.shouldRenderThrough(PortalType.NETHER)) {
            return RenderShape.INVISIBLE;
        }
        return blockState.getRenderShape();
    }

    /**
     * Suppress destination portal obsidian frame during FBO compilation.
     * Returns AIR for obsidian at frame positions when the suppressor is active.
     */
    @Redirect(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        )
    )
    private BlockState seamlessportals$suppressFrameObsidian(RenderSectionRegion region, BlockPos pos) {
        BlockState state = region.getBlockState(pos);
        if (state.is(Blocks.OBSIDIAN) && PortalFrameSuppressor.isFrameBlock(pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return state;
    }
}
