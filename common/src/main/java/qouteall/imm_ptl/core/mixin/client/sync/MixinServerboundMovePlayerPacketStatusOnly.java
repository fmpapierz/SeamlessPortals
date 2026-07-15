package qouteall.imm_ptl.core.mixin.client.sync;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.Validate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEPlayerMoveC2SPacket;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;

// S12-B port disposition: PORTS-CLEAN (mixin-client.md §9). Client-side WRITE (encode) append. 26.2:
// `ServerboundMovePlayerPacket.StatusOnly.write(FriendlyByteBuf)` survives
// (`26.2:ServerboundMovePlayerPacket.java:222-224`). DECODE half = S7 common
// `position_sync.MixinServerboundMovePlayerPacketStatusOnly.read()`. NOTE: no `@Environment` (IP parity
// — this subclass alone lacks it). Verbatim IP. Held/unregistered until S13.
@Mixin(ServerboundMovePlayerPacket.StatusOnly.class)
public class MixinServerboundMovePlayerPacketStatusOnly {
    @Inject(method = "Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket$StatusOnly;write(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"))
    private void onWrite(FriendlyByteBuf buf, CallbackInfo ci) {
        if (!ImmPtlNetworkConfig.doesServerHaveImmPtl()) {
            return;
        }
        ResourceKey<Level> playerDimension =
            ((IEPlayerMoveC2SPacket) this).ip_getPlayerDimension();
        Validate.notNull(playerDimension, "player dimension is null");
        buf.writeResourceKey(playerDimension);
    }
}
