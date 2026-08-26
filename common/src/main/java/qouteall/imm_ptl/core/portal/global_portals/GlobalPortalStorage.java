package qouteall.imm_ptl.core.portal.global_portals;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.warwa.seamlessportals.platform.Platform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.ducks.IEClientWorld;
import qouteall.imm_ptl.core.network.ImmPtlNetworking;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.MiscHelper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Stores global portals.
 * Also stores bedrock replacement block state for dimension stack.
 */
@SuppressWarnings("resource")
public class GlobalPortalStorage extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // R11 (F15): 26.2 removed SavedData.Factory + SavedData#save. Persistence is now declared by a
    // SavedDataType<GlobalPortalStorage> (id + constructor + Codec + DataFixTypes). The id is the
    // 1:1 translation of IP's DimensionDataStorage key string "global_portal" through the mod's own
    // Identifier helper (McHelper.newResourceLocation("global_portal") == minecraft:global_portal),
    // which becomes the on-disk file <dim>/data/minecraft/global_portal.dat and the SavedDataType
    // toString token "SavedDataType[minecraft:global_portal]" used by the two silent-loss grep
    // signatures below. The constant is SPIKE-R11's proven zero-fix DataFixTypes value.
    private static final Identifier SAVED_DATA_ID = McHelper.newResourceLocation("global_portal");
    
    public List<Portal> data;
    public final WeakReference<ServerLevel> world;
    private int version = 1;
    private boolean shouldReSync = false;
    
    @Nullable
    public BlockState bedrockReplacement;
    
    public static void init() {
        Platform.get().onServerTickEnd((server) -> { // NF-PARITY W9
            server.getAllLevels().forEach(world1 -> {
                GlobalPortalStorage gps = GlobalPortalStorage.get(world1);
                gps.tick();
            });
        });
        
        IPGlobal.SERVER_CLEANUP_EVENT.register((s) -> {
            for (ServerLevel world : s.getAllLevels()) {
                get(world).onServerClose();
            }
        });
        
        DimensionAPI.SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT.register((server, dims) -> {
            for (ServerLevel world : server.getAllLevels()) {
                GlobalPortalStorage gps = get(world);
                gps.clearAbnormalPortals(server);
                gps.syncToAllPlayers();
            }
        });
        
        if (!O_O.isDedicatedServer()) {
            initClient();
        }
    }
    
    public static GlobalPortalStorage get(
        ServerLevel world
    ) {
        // R11 (F15): SavedData.Factory<>(constructor, deserializer, null) + "global_portal" name ->
        // SavedDataType<>(id, constructor, codec, DataFixTypes). The 1.21.3 (nbt, holderLookup)
        // deserializer becomes the codec's decode side (createDataCodec), and the null dataFixType
        // becomes SPIKE-R11's proven zero-fix DataFixTypes.SAVED_DATA_COMMAND_STORAGE (vanilla uses
        // it for arbitrary /data storage NBT, so no shape-assuming fix can ever target it).
        return world.getDataStorage().computeIfAbsent(
            new SavedDataType<>(
                SAVED_DATA_ID,
                () -> {
                    LOGGER.info("Global portal storage initialized {}", world.dimension().identifier());
                    return new GlobalPortalStorage(world);
                },
                createDataCodec(world),
                DataFixTypes.SAVED_DATA_COMMAND_STORAGE
            )
        );
    }
    
    // R11 (F15): CompoundTag pass-through codec keeping IP's tag layout byte-shape (portal-generation.md
    // G1 / SPIKE-R11 D-R11-6). The codec closes over `world` (per-dimension) exactly as IP's
    // SavedData.Factory closed over it. Encode = save(...) (IP's persistence tag); decode = new
    // GlobalPortalStorage(world) + fromNbt(...) (IP's Factory deserializer body), wrapped in the
    // loud load-failure guard.
    private static Codec<GlobalPortalStorage> createDataCodec(ServerLevel world) {
        return CompoundTag.CODEC.xmap(
            tag -> deserializeWithGuard(world, tag),
            storage -> storage.save(new CompoundTag(), world.registryAccess())
        );
    }
    
    // R11 (F15) LOUD load-failure guard. 26.2 SavedDataStorage.readSavedData
    // (storage/SavedDataStorage.java:86-102) wraps the whole codec read in catch(Exception): on ANY
    // failure it logs ONE of two easy-to-miss ERROR lines, returns null, and computeIfAbsent then
    // SILENTLY constructs a fresh empty GlobalPortalStorage which is marked dirty and OVERWRITES the
    // existing global_portal.dat on the next save — silent data loss, not a crash (SPIKE-R11 D-R11-3).
    // The two vanilla signatures the S13 relog check must grep for (this file's id in brackets):
    //   [A] "Error loading saved data: SavedDataType[minecraft:global_portal]"
    //   [B] "Failed to parse saved data for 'SavedDataType[minecraft:global_portal]': ..."
    // This guard NEVER swallows: it emits a loud, mod-branded ERROR (grep token
    // "[IP][R11] GlobalPortalStorage load FAILED") naming the dimension and the overwrite hazard,
    // then rethrows so vanilla's signature-[A] line also fires. A swallow here would masquerade as an
    // empty dimension and guarantee the overwrite, so the port refuses to do it.
    private static GlobalPortalStorage deserializeWithGuard(ServerLevel world, CompoundTag tag) {
        try {
            GlobalPortalStorage globalPortalStorage = new GlobalPortalStorage(world);
            globalPortalStorage.fromNbt(tag);
            return globalPortalStorage;
        }
        catch (RuntimeException e) {
            LOGGER.error(
                "[IP][R11] GlobalPortalStorage load FAILED for dimension {}. Its global portals will " +
                    "be treated as EMPTY and the existing global_portal.dat will be OVERWRITTEN on the " +
                    "next save (silent data loss; this is NOT a normal empty-world state). Related " +
                    "vanilla signatures: 'Error loading saved data: SavedDataType[minecraft:global_portal]' " +
                    "/ 'Failed to parse saved data for SavedDataType[minecraft:global_portal]'.",
                world.dimension().identifier(), e
            );
            throw e;
        }
    }
    
    @Environment(EnvType.CLIENT)
    private static void initClient() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(GlobalPortalStorage::onClientCleanup);
    }
    
    @Environment(EnvType.CLIENT)
    private static void onClientCleanup() {
        if (ClientWorldLoader.getIsInitialized()) {
            for (ClientLevel clientWorld : ClientWorldLoader.getClientWorlds()) {
                for (Portal globalPortal : getGlobalPortals(clientWorld)) {
                    globalPortal.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                }
            }
        }
    }
    
    public GlobalPortalStorage(ServerLevel world_) {
        world = new WeakReference<>(world_);
        data = new ArrayList<>();
    }
    
    public static void onPlayerLoggedIn(ServerPlayer player) {
        MiscHelper.getServer().getAllLevels().forEach(
            world -> {
                GlobalPortalStorage storage = get(world);
                if (!storage.data.isEmpty()) {
                    Packet<ClientCommonPacketListener> packet = createSyncPacket(world, storage);
                    player.connection.send(packet);
                }
            }
        );
        
    }
    
    public static Packet<ClientCommonPacketListener> createSyncPacket(
        ServerLevel world, GlobalPortalStorage storage
    ) {
        // F2: Fabric ServerPlayNetworking.createS2CPacket(payload) -> vanilla record packet
        // new ClientboundCustomPayloadPacket(payload) (loader-neutral, constructible in common;
        // the record directly implements Packet<ClientCommonPacketListener>).
        return new ClientboundCustomPayloadPacket(
            new ImmPtlNetworking.GlobalPortalSyncPacket(
                PortalAPI.serverDimKeyToInt(world.getServer(), world.dimension()),
                storage.save(new CompoundTag(), world.registryAccess())
            )
        );
    }
    
    public void onDataChanged() {
        setDirty(true);
        
        shouldReSync = true;
    }
    
    public void removePortal(Portal portal) {
        data.remove(portal);
        portal.remove(Entity.RemovalReason.KILLED);
        onDataChanged();
    }
    
    public void addPortal(Portal portal) {
        Validate.isTrue(!data.contains(portal));
        
        Validate.isTrue(portal.isPortalValid());
        
        portal.isGlobalPortal = true;
        portal.myUnsetRemoved();
        data.add(portal);
        onDataChanged();
    }
    
    public void removePortals(Predicate<Portal> predicate) {
        data.removeIf(portal -> {
            final boolean shouldRemove = predicate.test(portal);
            if (shouldRemove) {
                portal.remove(Entity.RemovalReason.KILLED);
            }
            return shouldRemove;
        });
        onDataChanged();
    }
    
    private void syncToAllPlayers() {
        ServerLevel currWorld = world.get();
        Validate.notNull(currWorld);
        Packet packet = createSyncPacket(currWorld, this);
        McHelper.getRawPlayerList().forEach(
            player -> player.connection.send(packet)
        );
    }
    
    public void fromNbt(CompoundTag tag) {
        
        ServerLevel currWorld = world.get();
        Validate.notNull(currWorld, "world is null");
        
        List<Portal> newData = getPortalsFromTag(tag, currWorld);
        data = newData;
        
        if (tag.contains("version")) {
            version = tag.getIntOr("version", version);
        }
        
        if (tag.contains("bedrockReplacement")) {
            bedrockReplacement = NbtUtils.readBlockState(
                currWorld.holderLookup(Registries.BLOCK),
                tag.getCompoundOrEmpty("bedrockReplacement")
            );
        }
        else {
            bedrockReplacement = null;
        }
        
        clearAbnormalPortals(currWorld.getServer());
    }
    
    private static List<Portal> getPortalsFromTag(
        CompoundTag tag,
        Level currWorld
    ) {
        /**{@link CompoundTag#getType()}*/
        ListTag listTag = tag.getListOrEmpty("data");
        
        List<Portal> newData = new ArrayList<>();
        
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag compoundTag = listTag.getCompoundOrEmpty(i);
            Portal e = readPortalFromTag(currWorld, compoundTag);
            if (e != null) {
                newData.add(e);
            }
            else {
                Helper.err("error reading portal" + compoundTag);
            }
        }
        return newData;
    }
    
    private static Portal readPortalFromTag(Level currWorld, CompoundTag compoundTag) {
        Identifier entityId = McHelper.newResourceLocation(compoundTag.getStringOr("entity_type", ""));
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getValue(entityId);
        
        Entity e = entityType.create(currWorld, EntitySpawnReason.LOAD);
        e.load(TagValueInput.create(
            ProblemReporter.DISCARDING, currWorld.registryAccess(), compoundTag
        ));
        
        ((Portal) e).isGlobalPortal = true;
        
        // normal portals' bounding boxes are limited
        // update to non-limited bounding box
        ((Portal) e).updateCache();
        
        return (Portal) e;
    }
    
    // R11 (F15): no longer @Override — 26.2 SavedData has no save method (it is only the dirty flag).
    // This method is retained verbatim: it is the codec's encode side AND is called directly by
    // createSyncPacket to build the S2C sync payload. IP's on-disk tag layout is preserved byte-for-byte.
    public @NotNull CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (data == null) {
            return tag;
        }
        
        ListTag listTag = new ListTag();
        ServerLevel currWorld = world.get();
        Validate.notNull(currWorld, "world is null");
        
        for (Portal portal : data) {
            Validate.isTrue(portal.level() == currWorld);
            // R11: Entity#saveWithoutId(CompoundTag) -> saveWithoutId(ValueOutput). Bridge through
            // TagValueOutput to recover the identical CompoundTag layout (portal-generation.md row 2).
            TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, registries
            );
            portal.saveWithoutId(output);
            CompoundTag portalTag = output.buildResult();
            portalTag.putString(
                "entity_type",
                EntityType.getKey(portal.getType()).toString()
            );
            listTag.add(portalTag);
        }
        
        tag.put("data", listTag);
        
        tag.putInt("version", version);
        
        if (bedrockReplacement != null) {
            tag.put("bedrockReplacement", NbtUtils.writeBlockState(bedrockReplacement));
        }
        
        return tag;
    }
    
    public void tick() {
        if (shouldReSync) {
            syncToAllPlayers();
            shouldReSync = false;
        }
        
        if (version <= 1) {
            upgradeData(world.get());
            version = 2;
            setDirty(true);
        }
    }
    
    public void clearAbnormalPortals(MinecraftServer server) {
        data.removeIf(e -> {
            ResourceKey<Level> dimensionTo = ((Portal) e).getDestDim();
            if (server.getLevel(dimensionTo) == null) {
                LOGGER.error("Missing Dimension for global portal {}", dimensionTo.identifier());
                return true;
            }
            return false;
        });
    }
    
    private static void upgradeData(ServerLevel world) {
        //removed
    }
    
    @Environment(EnvType.CLIENT)
    public static void receiveGlobalPortalSync(ResourceKey<Level> dimension, CompoundTag compoundTag) {
        ClientLevel world = ClientWorldLoader.getWorld(dimension);
        
        List<Portal> oldGlobalPortals = ((IEClientWorld) world).ip_getGlobalPortals();
        if (oldGlobalPortals != null) {
            for (Portal p : oldGlobalPortals) {
                p.remove(Entity.RemovalReason.KILLED);
            }
        }
        
        List<Portal> newPortals = getPortalsFromTag(compoundTag, world);
        for (Portal p : newPortals) {
            p.myUnsetRemoved();
            p.isGlobalPortal = true;
            
            Validate.isTrue(p.isPortalValid());
            
            ClientWorldLoader.getWorld(p.getDestDim());
        }
        
        ((IEClientWorld) world).ip_setGlobalPortals(newPortals);
        
        LOGGER.info("Global Portals Updated {}", dimension.identifier());
    }
    
    public static void convertNormalPortalIntoGlobalPortal(Portal portal) {
        Validate.isTrue(!portal.getIsGlobal());
        Validate.isTrue(!portal.level().isClientSide());
        
        // global portal can only be square
        portal.setPortalShapeToDefault();
        
        portal.remove(Entity.RemovalReason.KILLED);
        
        Portal newPortal = McHelper.copyEntity(portal);
        
        get(((ServerLevel) portal.level())).addPortal(newPortal);
    }
    
    public static void convertGlobalPortalIntoNormalPortal(Portal portal) {
        Validate.isTrue(portal.getIsGlobal());
        Validate.isTrue(!portal.level().isClientSide());
        
        get(((ServerLevel) portal.level())).removePortal(portal);
        
        Portal newPortal = McHelper.copyEntity(portal);
        
        McHelper.spawnServerEntity(newPortal);
    }
    
    private void onServerClose() {
        for (Portal portal : data) {
            portal.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        }
    }
    
    @NotNull
    public static List<Portal> getGlobalPortals(Level world) {
        List<Portal> result;
        if (world.isClientSide()) {
            result = CHelper.getClientGlobalPortal(world);
        }
        else if (world instanceof ServerLevel) {
            result = get(((ServerLevel) world)).data;
        }
        else {
            result = null;
        }
        return result != null ? result : Collections.emptyList();
    }
}
