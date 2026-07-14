package qouteall.imm_ptl.core.mixin.common.entity_sync;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.network.PacketRedirection;

@Mixin(ServerCommonPacketListenerImpl.class)
public class MixinServerGamePacketListenerImpl_Redirect {
    @Shadow @Final protected MinecraftServer server;

    // 26.2 NEEDS-RETARGET (mixin-common.md §3 MixinServerGamePacketListenerImpl_Redirect): the
    // send(Packet, listener) second parameter changed type PacketSendListener -> ChannelFutureListener
    // (ServerCommonPacketListenerImpl.java:160); the inner anchor changed identically to
    // Connection.send(Packet, ChannelFutureListener, boolean) (invoked at :168, Connection.java:281).
    // Same injection shape, new descriptors.
    @SuppressWarnings({"rawtypes", "unchecked"})
    @ModifyVariable(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Packet modifyPacket(Packet originalPacket) {
        if (PacketRedirection.getForceRedirectDimension() == null) {
            return originalPacket;
        }

        return PacketRedirection.createRedirectedMessage(
            server,
            PacketRedirection.getForceRedirectDimension(),
            originalPacket
        );
    }

    @SuppressWarnings("unchecked")
    @Inject(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V"
        ),
        cancellable = true
    )
    private void onSend(
        Packet<?> packet, @Nullable ChannelFutureListener packetSendListener, CallbackInfo ci
    ) {
        PacketRedirection.ForceBundleCallback forceBundleCallback = PacketRedirection.getForceBundleCallback();
        if (forceBundleCallback != null) {
            forceBundleCallback.accept(
                (ServerCommonPacketListenerImpl) (Object) this,
                (Packet<ClientGamePacketListener>) packet
            );
            ci.cancel();
        }
    }
}
