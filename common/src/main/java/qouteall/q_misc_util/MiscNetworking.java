package qouteall.q_misc_util;

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import com.warwa.seamlessportals.network.PlatformHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.McHelper;
import qouteall.q_misc_util.dimension.DimIntIdMap;
import qouteall.q_misc_util.dimension.DimensionIntId;

public class MiscNetworking {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static record DimIdSyncPacket(
        CompoundTag dimIntIdTag,
        CompoundTag dimTypeTag,
        CompoundTag dimSeaLevelTag
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DimIdSyncPacket> TYPE =
            new CustomPacketPayload.Type<>(
                McHelper.newResourceLocation("imm_ptl:dim_int_id_sync")
            );

        public static final StreamCodec<FriendlyByteBuf, DimIdSyncPacket> CODEC =
            StreamCodec.of(
                (b, p) -> p.write(b), DimIdSyncPacket::read
            );

        public static DimIdSyncPacket createFromServer(MinecraftServer server) {
            DimIntIdMap rec = DimensionIntId.getServerMap(server);
            CompoundTag dimIntIdTag = rec.toTag(dim -> true);

            RegistryAccess registryManager = server.registryAccess();
            Registry<DimensionType> dimensionTypes = registryManager.lookupOrThrow(Registries.DIMENSION_TYPE);

            CompoundTag dimIdToDimTypeIdTag = new CompoundTag();
            CompoundTag dimSeaLevelTag = new CompoundTag();
            for (ServerLevel world : server.getAllLevels()) {
                ResourceKey<Level> dimId = world.dimension();

                DimensionType dimType = world.dimensionType();
                Identifier dimTypeId = dimensionTypes.getKey(dimType);

                if (dimTypeId == null) {
                    LOGGER.error("Cannot find dimension type for {}", dimId.identifier());
                    LOGGER.error(
                        "Registered dimension types {}", dimensionTypes.keySet()
                    );
                    dimTypeId = BuiltinDimensionTypes.OVERWORLD.identifier();
                }

                dimIdToDimTypeIdTag.putString(
                    dimId.identifier().toString(),
                    dimTypeId.toString()
                );

                // R1 seaLevel protocol (SPIKE-R1 design v1, migration/spikes/SPIKE-R1-sealevel.md §3):
                // per-dimension seaLevel carried on the dim-id sync path so a not-yet-visited
                // dimension's ClientLevel can be constructed with the correct sea level (26.2
                // ClientLevel ctor requires it; the value is final for the level's whole life —
                // API_RISKS R1). Bit-identical to vanilla's own source for the spawn-info field
                // (ServerPlayer.createCommonSpawnInfo passes level.getSeaLevel()), so the synced
                // value can never disagree with what vanilla sends when the player travels there.
                dimSeaLevelTag.putInt(
                    dimId.identifier().toString(),
                    world.getSeaLevel()
                );
            }

            return new DimIdSyncPacket(dimIntIdTag, dimIdToDimTypeIdTag, dimSeaLevelTag);
        }

        public static Packet<ClientCommonPacketListener> createPacket(MinecraftServer server) {
            return new ClientboundCustomPayloadPacket(
                DimIdSyncPacket.createFromServer(server)
            );
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeNbt(dimIntIdTag);
            buf.writeNbt(dimTypeTag);
            buf.writeNbt(dimSeaLevelTag);
        }

        public static DimIdSyncPacket read(FriendlyByteBuf buf) {
            CompoundTag idMapTag = buf.readNbt();
            CompoundTag typeTag = buf.readNbt();
            CompoundTag seaLevelTag = buf.readNbt();

            return new DimIdSyncPacket(idMapTag, typeTag, seaLevelTag);
        }

        public void handle() {
            DimIntIdMap rec = DimIntIdMap.fromTag(dimIntIdTag);
            LOGGER.info("Client received dim id sync packet\n{}", rec);
            DimensionIntId.clientRecord = rec;

            ImmutableMap.Builder<ResourceKey<Level>, ResourceKey<DimensionType>> builder =
                new ImmutableMap.Builder<>();

            for (String key : dimTypeTag.keySet()) {
                ResourceKey<Level> dimId = ResourceKey.create(
                    Registries.DIMENSION,
                    McHelper.newResourceLocation(key)
                );
                String dimTypeId = dimTypeTag.getStringOr(key, "");
                ResourceKey<DimensionType> dimType = ResourceKey.create(
                    Registries.DIMENSION_TYPE,
                    McHelper.newResourceLocation(dimTypeId)
                );
                builder.put(dimId, dimType);
            }

            var dimTypeMap = builder.build();
            ClientWorldLoader.dimIdToDimTypeId = dimTypeMap;
            LOGGER.info(
                "Client accepted dimension type mapping {}",
                dimTypeMap
            );

            // R1 seaLevel protocol (SPIKE-R1 §3 "Client cache lifecycle"): mirror the dim-type
            // map handling exactly — build the per-dim seaLevel map and replace the client cache
            // WHOLESALE (stale entries for removed dims vanish with the swap). The field
            // ClientWorldLoader.dimIdToDimSeaLevel is a forward ref that resolves at S10 with
            // ClientWorldLoader (same documented U8 debt as dimIdToDimTypeId above); consumption
            // (createSecondaryClientWorld passing dimIdToDimSeaLevel.get(dimension) as the
            // ClientLevel ctor's trailing seaLevel arg) also lands at S10.
            ImmutableMap.Builder<ResourceKey<Level>, Integer> seaLevelBuilder =
                new ImmutableMap.Builder<>();

            for (String key : dimSeaLevelTag.keySet()) {
                ResourceKey<Level> dimId = ResourceKey.create(
                    Registries.DIMENSION,
                    McHelper.newResourceLocation(key)
                );
                int seaLevel = dimSeaLevelTag.getIntOr(key, 0);
                seaLevelBuilder.put(dimId, seaLevel);
            }

            var dimSeaLevelMap = seaLevelBuilder.build();
            ClientWorldLoader.dimIdToDimSeaLevel = dimSeaLevelMap;
            LOGGER.info(
                "Client accepted dimension sea level mapping {}",
                dimSeaLevelMap
            );
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @Environment(EnvType.CLIENT)
    public static void initClient() {
        PlatformHelper.getInstance().registerClientPayloadHandler(
            DimIdSyncPacket.TYPE,
            (p, client) -> {
                p.handle();
            }
        );
    }

    public static void init() {
        PlatformHelper.getInstance().registerClientboundPayload(
            DimIdSyncPacket.TYPE, DimIdSyncPacket.CODEC
        );
    }
}
