package qouteall.imm_ptl.core.mixin.client.collisions;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ducks.IEEntity;

// S12-B port disposition: PORTS-CLEAN (mixin-client.md §3). 26.2 target unchanged —
// `LocalPlayer.suffocatesAt(BlockPos)` is `private boolean suffocatesAt(BlockPos)`
// (26.2:LocalPlayer.java:483, callers :456,:466). Private is irrelevant to a HEAD injection.
// Verbatim IP. Held/unregistered until S13.
@Mixin(LocalPlayer.class)
public class MixinLocalPlayer {
    // avoid beingpushed out of blocks by the blocks on the other facing of the portal
    @Inject(
        method = "Lnet/minecraft/client/player/LocalPlayer;suffocatesAt(Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onCannotFitAt(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (((IEEntity) this).ip_isRecentlyCollidingWithPortal()) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }
}
