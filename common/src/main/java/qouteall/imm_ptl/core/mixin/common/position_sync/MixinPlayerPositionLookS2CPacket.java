package qouteall.imm_ptl.core.mixin.common.position_sync;

import com.mojang.datafixers.util.Function3;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;

import java.util.function.Function;

/**
 * R8 position-packet dimension stamp (F9) — the SERVER-WRITE + CLIENT-READ lock-step PAIR.
 *
 * <p>26.2 reality: {@link ClientboundPlayerPositionPacket} is a record serialized ENTIRELY by a
 * composite {@code STREAM_CODEC} built once in {@code <clinit>} — there is no
 * {@code write(FriendlyByteBuf)} method (IP 1.21.3's {@code onWrite} @Inject anchor) and no
 * {@code <init>(FriendlyByteBuf)} ctor (IP 1.21.3's client {@code onRead} anchor,
 * {@code mixin/client/sync/MixinClientboundPlayerPositionPacket}). So the dimension stamp must ride
 * the {@code STREAM_CODEC} itself (S08-teleportation §6 Option (a), the RECOMMENDED codec-wrap idiom;
 * mixin-common.md §4 row TARGET-GONE).
 *
 * <p>Idiom mechanism: S08 §6 wrote "@ModifyExpressionValue (MixinExtras)", but MixinExtras is NOT on
 * the :common compile classpath (verified S07-network.md §393 — {@code compileOnly mixin:0.8.5} only,
 * no {@code com.llamalad7.mixinextras}). This realizes the SAME Option (a) codec-wrap via vanilla
 * Mixin {@code @Redirect} on the sole {@code StreamCodec.composite(...)} call in {@code <clinit>} —
 * mixin-common.md §4 explicitly lists "{@code @ModifyExpressionValue}/{@code @Redirect} on the
 * {@code StreamCodec.composite} call" as the alternatives. Protocol + atomicity are identical to
 * S08's decision: the dim rides the same byte stream as the position (no id-correlation table, no
 * arrival-order race, no re-queue / netty-pre-pass hazards).
 *
 * <p>A single {@code STREAM_CODEC} field serves BOTH wire directions, so this ONE common mixin covers
 * the whole lock-step pair (S08 §6 "SINGLE common-side {@code <clinit>} mixin"):
 * ENCODE (server-write) appends the trailing dimension unconditionally (matching IP's unconditional
 * server {@code onWrite}); DECODE (client-read) consumes it into the duck field, GATED on
 * {@link ImmPtlNetworkConfig#doesServerHaveImmPtl()} to avoid buffer underflow against a vanilla
 * server (matching IP's client {@code onRead} gate). The server stamps the per-instance dim at the
 * send site — {@code MixinServerGamePacketListenerImpl.teleport}, right after
 * {@code ClientboundPlayerPositionPacket.of(...)}.
 */
@Mixin(ClientboundPlayerPositionPacket.class)
public class MixinPlayerPositionLookS2CPacket implements IEPlayerPositionLookS2CPacket {
    private ResourceKey<Level> playerDimension;

    @Override
    public ResourceKey<Level> ip_getPlayerDimension() {
        return playerDimension;
    }

    @Override
    public void ip_setPlayerDimension(ResourceKey<Level> dimension) {
        playerDimension = dimension;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
        method = "<clinit>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/codec/StreamCodec;composite(Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;Lcom/mojang/datafixers/util/Function3;)Lnet/minecraft/network/codec/StreamCodec;"
        )
    )
    private static StreamCodec ip_wrapStreamCodec(
        StreamCodec codec1, Function getter1,
        StreamCodec codec2, Function getter2,
        StreamCodec codec3, Function getter3,
        Function3 constructor
    ) {
        StreamCodec<FriendlyByteBuf, ClientboundPlayerPositionPacket> original =
            StreamCodec.composite(
                codec1, getter1,
                codec2, getter2,
                codec3, getter3,
                constructor
            );

        return new StreamCodec<FriendlyByteBuf, ClientboundPlayerPositionPacket>() {
            @Override
            public ClientboundPlayerPositionPacket decode(FriendlyByteBuf buf) {
                ClientboundPlayerPositionPacket packet = original.decode(buf);
                if (ImmPtlNetworkConfig.doesServerHaveImmPtl()) {
                    ResourceKey<Level> playerDimension = buf.readResourceKey(Registries.DIMENSION);
                    ((IEPlayerPositionLookS2CPacket) (Object) packet).ip_setPlayerDimension(playerDimension);
                }
                return packet;
            }

            @Override
            public void encode(FriendlyByteBuf buf, ClientboundPlayerPositionPacket packet) {
                original.encode(buf, packet);
                buf.writeResourceKey(((IEPlayerPositionLookS2CPacket) (Object) packet).ip_getPlayerDimension());
            }
        };
    }
}
