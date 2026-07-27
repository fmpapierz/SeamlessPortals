package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.SeamClipRegionAccess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SEAM CLIP arm 1 (the AIR report) — a region whose exclusion snapshot names a cell answers AIR
 * for it, everywhere the region is consulted during section compile:
 * <ul>
 *   <li>the compile loop's own read (skips tessellation, VisGraph-opaque and BE collection —
 *       the predicate excludes BE blocks so nothing is lost);</li>
 *   <li>{@code ModelBlockRenderer.shouldRenderFace} neighbour reads — the UN-CULL: neighbours
 *       render their faces against the cell, which is what closes the frameless-portal x-ray
 *       (design §0/§1a);</li>
 *   <li>{@code FluidRenderer} neighbour reads (water renders its face against the cell).</li>
 * </ul>
 * The region's {@code getFluidState} reads the SectionCopy directly and is deliberately NOT
 * touched — a waterlogged seam block keeps its water in the mesh (fluids never clip, job spec).
 *
 * <p>Composes with the flag-gated block-era {@code SectionCompilerMixin} redirects (they call
 * through this same method). The set is null on almost every region — one field read per call.
 * Targets javap-verified against minecraft-merged-deobf-26.2.jar (single
 * {@code getBlockState(BlockPos)} declared on RenderSectionRegion).
 */
@Mixin(RenderSectionRegion.class)
public abstract class RenderSectionRegionSeamClipMixin implements SeamClipRegionAccess {

    @Unique
    @Nullable
    private LongOpenHashSet seamlessportals$seamClipExclusions;

    @Override
    @Nullable
    public LongOpenHashSet seamlessportals$seamClipExclusions() {
        return this.seamlessportals$seamClipExclusions;
    }

    @Override
    public void seamlessportals$setSeamClipExclusions(@Nullable LongOpenHashSet cells) {
        this.seamlessportals$seamClipExclusions = cells;
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true, require = 1)
    private void seamlessportals$reportSeamCellsAsAir(
        BlockPos pos, CallbackInfoReturnable<BlockState> cir
    ) {
        LongOpenHashSet set = this.seamlessportals$seamClipExclusions;
        if (set != null && set.contains(pos.asLong())) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }
}
