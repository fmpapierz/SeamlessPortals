package com.warwa.seamlessportals.forge.mixin;

import com.warwa.seamlessportals.forge.network.ForgePlatformHelper;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 FORGE ("F-NET") — SERVER receive side, CONFIGURATION phase: hands the mod's serverbound payloads to
 * {@link ForgePlatformHelper#dispatchServerbound}.
 *
 * <p>Target (javap on forge-26.3-66.0.2.jar): {@code ServerCommonPacketListenerImpl.handleCustomPayload(
 * Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V} — body: {@code ForgeHooks.onCustomPayload}
 * (invokestatic @8), {@code pop}, {@code return}; no thread hop, so HEAD runs once, on the network thread.
 * {@code ServerConfigurationPacketListenerImpl} does not override it, so this is the configuration listener's entry.
 * It is NOT the play listener's: Forge's {@code ServerGamePacketListenerImpl} OVERRIDES the method with the same
 * three instructions and never calls super (NeoForge's override is a bare {@code invokespecial} of this one) — that
 * override has its own mixin, {@code MixinServerGamePacketListenerImpl_PayloadDispatchForge}. The two never both fire
 * for one packet.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class MixinServerCommonPacketListenerImpl_PayloadDispatchForge {

    @Inject(
        method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1, allow = 1
    )
    private void seamlessportals$dispatchModPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (ForgePlatformHelper.dispatchServerbound(packet, (ServerCommonPacketListenerImpl) (Object) this)) {
            ci.cancel();
        }
    }
}
