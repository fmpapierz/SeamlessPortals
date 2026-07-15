package qouteall.imm_ptl.core.mixin.client.sync;

import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// S12-B port disposition: PORTS-CLEAN (mixin-client.md §9). 26.2: `BlockStatePredictionHandler`
// `private int currentSequenceNr` (26.2:BlockStatePredictionHandler.java:17). Verbatim IP accessor.
// Held/unregistered until S13.
@Mixin(BlockStatePredictionHandler.class)
public interface IEBlockStatePredictionHandler {
    @Accessor("currentSequenceNr")
    void ip_setCurrentSequenceNumber(int arg);
}
