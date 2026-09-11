package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.SeamCrumbReplay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ★ SEAM CRUMB REPLAY driver (attempt #2, 2026-09-11) — the destroy-burst funnel:
 * {@code addDestroyBlockEffect(BlockPos, BlockState)} is where every break burst spawns
 * (levelEvent 2001 → 26.2 LevelEventHandler:305-315; javap-verified identical on the loom and
 * NeoForge-patched jars). TAIL so the native burst has fully spawned; the replay itself
 * re-checks vanilla's own air/shouldSpawnTerrainParticles gates and fast-exits on non-seam
 * cells (one map lookup). Logic is common-side in {@link SeamCrumbReplay}.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelDestroyEffectSeamMixin {

    @Inject(method = "addDestroyBlockEffect", at = @At("TAIL"), require = 1)
    private void seamlessportals$replayAcrossSeam(
        BlockPos pos, BlockState blockState, CallbackInfo ci
    ) {
        SeamCrumbReplay.onDestroyBurst((ClientLevel) (Object) this, pos, blockState);
    }
}
