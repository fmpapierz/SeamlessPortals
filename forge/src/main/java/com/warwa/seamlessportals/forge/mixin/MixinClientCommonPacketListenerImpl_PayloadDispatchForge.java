package com.warwa.seamlessportals.forge.mixin;

import com.warwa.seamlessportals.forge.network.ForgePlatformHelper;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 FORGE ("F-NET") — CLIENT receive side: hands the mod's clientbound payloads (play AND configuration) to
 * {@link ForgePlatformHelper#dispatchClientbound}, the stand-in for Fabric's {@code Client*Networking} receivers and
 * NeoForge's registrar handlers. Listed under {@code "client"} in seamlessportals-forge.mixins.json.
 *
 * <p>Target (javap on forge-26.3-66.0.2.jar): {@code ClientCommonPacketListenerImpl.handleCustomPayload(
 * Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V} — the one entry both client listeners
 * share: {@code ClientPacketListener} and {@code ClientConfigurationPacketListenerImpl} override only the
 * {@code (CustomPacketPayload)} overload, never this one. HEAD sits before Forge's own
 * {@code ForgeHooks.onCustomPayload} (invokestatic @8) and before {@code PacketUtils.ensureRunningOnSameThread} (@36),
 * and the cancel keeps both Forge's channel layer (which has no channel for these ids) and vanilla's "unknown custom
 * packet payload" path out of it. A CONFIGURATION payload of ours runs here once, inline, on the network thread. A
 * PLAY payload passes here TWICE, exactly as under Fabric API's {@code ClientCommonPacketListenerImplMixin}: on the
 * network thread the dispatcher re-queues the PACKET on {@code Minecraft.packetProcessor()} (vanilla packet order),
 * and when the processor re-delivers it — {@code packet.handle(listener)} → this method again, now on the main
 * thread — the handler runs inline. Both passes cancel. For every other payload the dispatcher answers false and
 * nothing changes. {@code PacketRedirection.Payload} never gets here — the common
 * {@code MixinClientboundCustomPayloadPacket} cancels it at the HEAD of the packet's {@code handle}.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class MixinClientCommonPacketListenerImpl_PayloadDispatchForge {

    @Inject(
        method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1, allow = 1
    )
    private void seamlessportals$dispatchModPayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (ForgePlatformHelper.dispatchClientbound(packet, (ClientCommonPacketListenerImpl) (Object) this)) {
            ci.cancel();
        }
    }
}
