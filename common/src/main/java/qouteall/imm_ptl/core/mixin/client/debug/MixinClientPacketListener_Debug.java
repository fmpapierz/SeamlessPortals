package qouteall.imm_ptl.core.mixin.client.debug;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;

// S12-B port disposition: PORTS-CLEAN (mixin-client.md §3). Empty body (IP's debug injection is
// commented out). 26.2 target `ClientPacketListener` exists (ctor :431). Verbatim IP.
// Held/unregistered until S13.
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener_Debug {
//    @Inject(
//        method = "handleChunkBlocksUpdate",
//        at = @At("RETURN")
//    )
//    private void onChunkBlockUpdate(
//        ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci
//    ) {
//        Helper.LOGGER.info("BlockUpdatePacket handle {}", RenderStates.frameIndex);
//    }
}
