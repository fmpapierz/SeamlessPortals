package com.warwa.seamlessportals.forge.mixin;

import com.warwa.seamlessportals.forge.network.ForgePlatformHelper;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3 FORGE ("F-NET") — makes the mod's custom payloads known to VANILLA's dispatch codec, which is what Fabric API
 * and NeoForge do on their loaders and MinecraftForge does not.
 *
 * <p>Target: {@code net.minecraft.network.protocol.common.custom.CustomPacketPayload$1} — javap on
 * forge-26.3-66.0.2.jar: the ONLY anonymous class of {@code CustomPacketPayload} (the jar lists {@code $1},
 * {@code $FallbackProvider}, {@code $Type}, {@code $TypeAndCodec}), created by
 * {@code CustomPacketPayload.codec(FallbackProvider, List)} ({@code new CustomPacketPayload$1} @28). All three
 * custom-payload packet codecs are instances of it: {@code ClientboundCustomPayloadPacket.<clinit>} calls that
 * {@code codec(..)} @39 -> {@code GAMEPLAY_STREAM_CODEC} and @81 -> {@code CONFIG_STREAM_CODEC};
 * {@code ServerboundCustomPayloadPacket.<clinit>} @39 -> {@code STREAM_CODEC}. Its
 * {@code private StreamCodec findCodec(Identifier)} (descriptor
 * {@code (Lnet/minecraft/resources/Identifier;)Lnet/minecraft/network/codec/StreamCodec;}) is the single resolution
 * point of both directions of the wire: {@code writeCap} @14 (encode) and {@code decode} @7.
 *
 * <p>Why it is needed: vanilla's body is {@code idToType.get(id)}, else {@code fallback.create(id)}, and on Forge the
 * fallback of all three codecs is {@code ForgeHooks.getCustomPayloadCodec} — an encoder compiled as
 * {@code lambda$getCustomPayloadCodec$0(ForgePayload, FriendlyByteBuf)} (any other payload object is a
 * ClassCastException at encode) and a decoder that produces a {@code ForgePayload} (or, for an id no Forge channel
 * owns, vanilla's {@code DiscardedPayload}). Common code sends {@code new ClientboundCustomPayloadPacket(record)} and
 * tests the received object with {@code instanceof}; both need the record itself on the wire. Returning the registered
 * codec at HEAD gives exactly that and leaves every other id to vanilla and Forge untouched.
 */
@Mixin(targets = "net.minecraft.network.protocol.common.custom.CustomPacketPayload$1")
public abstract class MixinCustomPacketPayloadCodecForge {

    @Inject(method = "findCodec", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void seamlessportals$findModPayloadCodec(
        Identifier id, CallbackInfoReturnable<StreamCodec<?, ?>> cir
    ) {
        StreamCodec<?, ?> codec = ForgePlatformHelper.findCodec(id);
        if (codec != null) {
            cir.setReturnValue(codec);
        }
    }
}
