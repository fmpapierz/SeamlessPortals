package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.portal.PortalType;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin into SectionCompiler to suppress nether portal block rendering.
 *
 * We redirect the blockState.getRenderShape() call inside compile().
 * When the block is a nether portal and seamless mode is on, we return
 * RenderShape.INVISIBLE instead of RenderShape.MODEL, which prevents
 * the purple swirl from being compiled into the chunk mesh.
 *
 * This works with BOTH vanilla and Fabric's Indigo renderer because
 * the getRenderShape() check happens BEFORE any renderer is invoked.
 *
 * DEBUG: Logs every portal block suppression.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {

    @Unique
    private static boolean seamlessportals$loggedSuppression = false;

    /**
     * Redirect blockState.getRenderShape() in the compile loop.
     * For nether portal blocks with seamless mode on, return INVISIBLE
     * to prevent the purple texture from rendering.
     */
    @Redirect(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"
        )
    )
    private RenderShape seamlessportals$redirectGetRenderShape(BlockState blockState) {
        if (blockState.is(Blocks.NETHER_PORTAL) && SeamlessPortalsConfig.shouldRenderThrough(PortalType.NETHER)) {
            if (!seamlessportals$loggedSuppression) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS DEBUG] Suppressing portal render shape -> INVISIBLE for block: {}",
                    blockState
                );
                seamlessportals$loggedSuppression = true;
            }
            return RenderShape.INVISIBLE;
        }
        return blockState.getRenderShape();
    }
}
