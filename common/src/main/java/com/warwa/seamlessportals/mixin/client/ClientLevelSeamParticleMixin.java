package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.passthrough.SeamFractional;
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
 * ★ NO PARTICLE BLEED (user round 27: "if i put a torch on seam side a and go to side b, the
 * particles bleed through on side b"). Ambient block particles ({@code Block.animateTick} — torch
 * flames, campfire smoke, etc.) spawn at model-space offsets that sit on or near the cut plane,
 * so the empty side saw them floating in what must read as nothing. The dispatch is redirected:
 * a cut seam cell simply does not animate-tick while the camera is on its EMPTY half. On the
 * owned side everything spawns as vanilla; the far side's own mirrored fragment carries its own
 * particles for viewers over there.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSeamParticleMixin {

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
    private void seamlessportals$noParticleBleed(
        Block block, BlockState state, Level level, BlockPos pos, RandomSource random
    ) {
        if (SeamFractional.cameraOnEmptyHalf(level, pos)) {
            return;
        }
        block.animateTick(state, level, pos, random);
    }
}
