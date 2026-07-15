package qouteall.imm_ptl.core.mixin.client.sync;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.ducks.IEClientWorld;

// S12-B port disposition: PORTS-CLEAN (mixin-client.md §9). 26.2:
// `BlockStatePredictionHandler.startPredicting()` is `public BlockStatePredictionHandler startPredicting()`
// (26.2:BlockStatePredictionHandler.java:55, now used try-with-resources at MultiPlayerGameMode.java:298).
// @RETURN inject unaffected. Verbatim IP. Held/unregistered until S13.
@Mixin(BlockStatePredictionHandler.class)
public class MixinBlockStatePredictionHandler {
    @Shadow
    private int currentSequenceNr;

    /**
     * Each dimension has its own BlockStatePredictionHandler, because its internal map does not discriminate dimensions.
     * So all the handlers should have synchronized sequence number.
     * See also {@link MixinClientPacketListener#redirectHandleBlockChangedAck(ClientLevel, int)}
     */
    @Inject(
        method = "startPredicting",
        at = @At("RETURN")
    )
    private void onStartPredictingEnd(CallbackInfoReturnable<BlockStatePredictionHandler> cir) {
        for (ClientLevel clientWorld : ClientWorldLoader.getClientWorlds()) {
            BlockStatePredictionHandler handler = ((IEClientWorld) clientWorld).ip_getBlockStatePredictionHandler();
            ((IEBlockStatePredictionHandler) handler).ip_setCurrentSequenceNumber(currentSequenceNr);
        }
    }
}
