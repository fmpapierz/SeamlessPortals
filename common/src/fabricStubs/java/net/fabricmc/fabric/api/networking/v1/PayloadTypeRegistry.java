// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. ImmPtlNetworkConfig.init uses configurationS2C()/configurationC2S()
// .register(TYPE, CODEC) for the config-phase handshake. (The PLAY-phase payloads route through
// the mod-owned PlatformHelper seam, S0 B2 — NOT this type.) :common compile classpath only; the
// REAL fabric-api PayloadTypeRegistry resolves at S13 (this stub is NEVER on the loader classpath,
// so it cannot shadow the real one FabricPlatformHelper uses). Removed at S20.
package net.fabricmc.fabric.api.networking.v1;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public class PayloadTypeRegistry<B> {
    public <T extends CustomPacketPayload> void register(
        CustomPacketPayload.Type<T> id, StreamCodec<? super B, T> codec) {
    }

    public static PayloadTypeRegistry<FriendlyByteBuf> configurationS2C() {
        return null;
    }

    public static PayloadTypeRegistry<FriendlyByteBuf> configurationC2S() {
        return null;
    }
}
