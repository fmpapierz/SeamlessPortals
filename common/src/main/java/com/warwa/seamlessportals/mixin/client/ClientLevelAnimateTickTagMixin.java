package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.SeamParticleOcclusion;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ★ WHO IS EMITTING — the source-block bracket for the window rule (round 33: the user's rule
 * anchors on the "particle SOURCE BLOCK", not the particle's drifting position; a smoke puff
 * that wandered past the plane keeps its torch's visibility fate). While a block's
 * {@code animateTick} runs, every particle it creates is tagged with the block's position
 * ({@code ClientLevelSeamParticleMixin} reads the bracket at the creation funnel).
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelAnimateTickTagMixin {

    @Redirect(
        method = "doAnimateTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;animateTick("
                + "Lnet/minecraft/world/level/block/state/BlockState;"
                + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                + "Lnet/minecraft/util/RandomSource;)V"
        )
    )
    private void seamlessportals$tagEmittingBlock(
        Block block, BlockState state, Level level, BlockPos pos, RandomSource random
    ) {
        SeamParticleOcclusion.beginEmitting(pos);
        try {
            block.animateTick(state, level, pos, random);
        } finally {
            SeamParticleOcclusion.endEmitting();
        }
    }
}
