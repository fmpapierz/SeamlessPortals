package com.warwa.seamlessportals.forge.mixin;

import com.warwa.seamlessportals.forge.network.ForgePlatformHelper;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 FORGE ("F-NET") — SERVER receive side, PLAY phase: hands the mod's serverbound payloads to
 * {@link ForgePlatformHelper#dispatchServerbound}.
 *
 * <p>Why a second server mixin: javap -c on forge-26.3-66.0.2.jar shows {@code ServerGamePacketListenerImpl} DECLARES
 * its own {@code handleCustomPayload(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V} —
 * {@code ForgeHooks.onCustomPayload} (invokestatic @8), {@code pop}, {@code return} — with no {@code invokespecial} of
 * the {@code ServerCommonPacketListenerImpl} method, so an injection in the superclass
 * ({@code MixinServerCommonPacketListenerImpl_PayloadDispatchForge}, the configuration phase's entry) is never reached
 * by a play-phase payload. HEAD here is likewise before Forge's hook, and a payload of ours passes it TWICE, as under
 * Fabric API's {@code ServerCommonPacketListenerImplMixin}: on the network thread the dispatcher re-queues the PACKET
 * on {@code MinecraftServer.packetProcessor()} (vanilla packet order — a teleport payload must not overtake or trail
 * the movement packets sent around it), and when the processor re-delivers it the handler runs inline on the server
 * main thread. Both passes cancel. The dispatcher takes the player from this listener's public {@code player} field.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerImpl_PayloadDispatchForge {

    @Inject(
        method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1, allow = 1
    )
    private void seamlessportals$dispatchModPlayPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (ForgePlatformHelper.dispatchServerbound(packet, (ServerCommonPacketListenerImpl) (Object) this)) {
            ci.cancel();
        }
    }
}
