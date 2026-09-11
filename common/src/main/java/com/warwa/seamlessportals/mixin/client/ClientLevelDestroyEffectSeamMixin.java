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
 * ★ SEAM CRUMB BURST driver (2026-09-11) — the destroy-burst funnel:
 * {@code addDestroyBlockEffect(BlockPos, BlockState)} is where every break burst spawns
 * (levelEvent 2001 → 26.2 LevelEventHandler:305-315; javap-verified identical on the loom
 * and NeoForge-patched jars). HEAD, cancellable: for a governed seam cell the handler fires
 * the dest-side replay AND spawns the local grid at full-block density (cut-axis spacing
 * halved — the density ruling), cancelling vanilla's sparse half-shape grid; for every
 * other cell it returns false and vanilla runs untouched. With the density lever off the
 * handler replays only and vanilla keeps the local burst.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelDestroyEffectSeamMixin {

    @Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true,
        require = 1)
    private void seamlessportals$seamBurst(
        BlockPos pos, BlockState blockState, CallbackInfo ci
    ) {
        if (SeamCrumbReplay.onDestroyBurst((ClientLevel) (Object) this, pos, blockState)) {
            ci.cancel();
        }
    }
}
