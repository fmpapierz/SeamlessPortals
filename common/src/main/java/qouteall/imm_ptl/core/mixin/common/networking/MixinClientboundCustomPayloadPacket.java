package qouteall.imm_ptl.core.mixin.common.networking;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IECustomPayloadPacket;
import qouteall.imm_ptl.core.network.PacketRedirection;

@Mixin(ClientboundCustomPayloadPacket.class)
public class MixinClientboundCustomPayloadPacket implements IECustomPayloadPacket {
    
    @Shadow
    @Final
    private CustomPacketPayload payload;
    
    // this is run before Fabric API try to handle the packet
    //
    // R7 §A step 2 — order-faithful redirection (S07-network.md §2 HANDOFF; SPIKE-R7-requeue §4).
    // The stock IP mixin did redirectPayload.handle(...) on BOTH passes, which on the netty pass drops
    // through PacketRedirectionClient.handleRedirectedPacket's !isSameThread branch -> minecraft.execute
    // (the naive EXECUTE shape SPIKE-R7 PROVED reorders every frame: Minecraft.processQueuedPackets drains
    // scheduledPacketProcessing BEFORE scheduledExecutables). The mandated correction: on the netty pass
    // re-queue the OUTER packet through the vanilla packet processor (scheduleIfPossible), so it is drained
    // in the same scheduledPacketProcessing pass as vanilla packets, preserving send order; the main pass
    // handles inline. Guard = packetProcessor().isSameThread() (vanilla's own gate in ensureRunningOnSameThread;
    // the netty pre-pass fires this HEAD inject TWICE, once per thread — SPIKE-R7 §3).
    @Inject(
        method = "handle(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onHandle(ClientCommonPacketListener listener, CallbackInfo ci) {
        if (payload instanceof PacketRedirection.Payload redirectPayload) {
            Minecraft mc = Minecraft.getInstance();
            if (!mc.packetProcessor().isSameThread()) {                     // netty pass
                mc.packetProcessor().scheduleIfPossible(
                    listener, (Packet<ClientCommonPacketListener>) (Object) this);
            }
            else if (listener instanceof ClientGamePacketListener cgpl) {   // main pass
                redirectPayload.handle(cgpl);
            }

            ci.cancel();
        }
    }
}
