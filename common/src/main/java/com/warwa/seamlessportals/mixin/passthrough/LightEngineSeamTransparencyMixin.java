package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import com.warwa.seamlessportals.passthrough.SeamOccupancy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ★ SEAM CELLS DO NOT BLOCK LIGHT — the LIGHTING arm of the fractional model (user live round
 * 16, 2026-08-03: "adjacent rows are good, no transparent face but lighting is
 * darkened/effected by opposite side seam block"). A seam cell's REAL blockstate is a full
 * opaque cube, so the light engine propagated darkness through the half-space that is
 * materially EMPTY, and every neighbouring face sampled that darkness.
 *
 * <p>26.2's engine has exactly one position-aware choke point: {@code LightEngine.getState
 * (BlockPos)}. Opacity ({@code getOpacity(BlockState)}) and directional occlusion
 * ({@code shapeOccludes(state, state, dir)}) are position-blind, but both consume states this
 * method returned — so substituting AIR here for a single-owned-half seam cell makes the whole
 * engine treat the cell as light-transparent: block light and skylight flow through the empty
 * half's cell. (Cell-granularity is the honest approximation: light data is per-voxel, so "the
 * cut face passes light" and "the whole cell passes light" are indistinguishable to every
 * sampler a player can see.)
 *
 * <p>Luminous seam blocks are deliberately NOT substituted ({@code getLightEmission() != 0}):
 * the engine reads emission from this same state, and a half glowstone must still glow — it
 * keeps whole-cube light semantics, where the blocking hardly matters because it is itself the
 * source.
 *
 * <p>Both sides run this (server and client each own a light engine); the duck maps exist on
 * both. Mask mutations trigger {@code checkBlock} relights from {@code SeamOccupancy}, so a
 * placement or break re-lights the cell without waiting for an unrelated neighbour update.
 */
@Mixin(LightEngine.class)
public abstract class LightEngineSeamTransparencyMixin {

    @Shadow
    @Final
    protected LightChunkGetter chunkSource;

    @Inject(method = "getState", at = @At("RETURN"), cancellable = true, require = 1)
    private void seamlessportals$seamCellsPassLight(
        BlockPos pos, CallbackInfoReturnable<BlockState> cir
    ) {
        BlockState state = cir.getReturnValue();
        // Ordered cheap-first for the hot path: most cells are air; most non-air cells are not
        // seam cells (the map miss is one hash probe on a small map).
        if (state == null || state.isAir() || state.getLightEmission() != 0
            || !SeamFractional.active()) {
            return;
        }
        if (!(chunkSource.getLevel() instanceof Level level)) {
            return;
        }
        byte owned = SeamOccupancy.occupancyOf(level, pos);
        if (owned == SeamOccupancy.HALF_POSITIVE || owned == SeamOccupancy.HALF_NEGATIVE) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }
}
