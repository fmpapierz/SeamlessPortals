package qouteall.imm_ptl.core.chunk_loading;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.q_misc_util.my_util.SignalArged;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Vanilla use a 2D array to store the chunk references on client and cannot store the chunks that are far from player.
 * This use map to store the chunk references, to eliminate such limitation.
 * (Two maps, one for main thread and one for other threads)
 *
 * <p>26.2 PORT (S9 / U7) of {@code qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap}. This is
 * a FAITHFUL port of IP's class (dual-map store, load/unload signals, {@link O_O} client chunk
 * events, {@link SodiumInterface} invoker, error-reporting {@code loadChunkDataFromPacket}) with two
 * classes of documented change on top of verbatim IP:
 * <ol>
 *   <li><b>Mechanical 26.2 translations</b> (api-map {@code chunk-loading.md} rows, cited inline):
 *   #42 {@code ChunkPos.asLong/toLong}→{@code pack} and {@code .x/.z}→{@code .x()/.z()};
 *   #45/#48 {@code replaceWithPacketData} heightmaps arg {@code CompoundTag}→{@code Map<Heightmap.Types,long[]>}
 *   + {@code @Nullable LevelChunk} return; #45 super's {@code level}/{@code emptyChunk} are now PRIVATE,
 *   so this subclass holds its own copies; {@code ResourceKey.location()}→{@code identifier()}.</li>
 *   <li><b>R13f carriage</b> (api-map {@code chunk-loading.md} #45 "critical, new vanilla surface";
 *   current-mod-core §5; memories {@code distant-chunk-vanish-sog-desync} + {@code walking-limbo-seed-overclaim}):
 *   the 26.2 per-frame SOG delta feed ({@code addedEmptySections}/{@code removedEmptySections}/
 *   {@code addedLoadedChunks}/{@code removedLoadedChunks}/{@code flipUpdateTrackingSets}/
 *   {@code onSectionEmptinessChanged} against own double-buffered sets, woven into {@code drop} +
 *   {@code replaceWithPacketData}) has NO 1.21.3/IP analog and MUST be reproduced per dimension or
 *   every renderer bound to a secondary dimension sees phantom/missing chunks in its occlusion graph;
 *   plus the store-center machinery (tracked view center + bounded eviction sweeps). NOTE: the
 *   verbatim IP {@code ClientWorldLoader} (landed S10) has NO eviction driver — IP has no
 *   1.21.3 analog for these sweeps and relies on server-side chunk tracking; adding a per-tick
 *   sweep driver into the verbatim file would itself be a fidelity deviation. The live per-tick
 *   DRIVER therefore REMAINS the mod's {@code PortalWorldManager.evictUnboundedStores} (on the mod
 *   store {@code SeamlessClientChunkMap}) until cutover; whether these sweeps are re-homed onto a
 *   mod-side driver over THIS store or dropped (IP's server tracking may make them redundant) is
 *   the S14/S17 C8/F20 decision (see the PORT-FORWARD note on the store-center methods below).
 *   These blocks are byte-identical to the mod's runtime-proven {@code SeamlessClientChunkMap} carriage.</li>
 * </ol>
 *
 * <p>Install point (IP-faithful): the {@code ClientLevel} CONSTRUCTOR mixin
 * {@code MixinClientLevel.onConstructed} (a U8 file — lands S10) sets
 * {@code chunkSource = O_O.createMyClientChunkManager(world, loadDistance)} → {@code new ImmPtlClientChunkMap(...)}
 * for EVERY client world including the vanilla main one. HELD (unregistered) until S13.
 */
@Environment(EnvType.CLIENT)
@IPVanillaCopy
public class ImmPtlClientChunkMap extends ClientChunkCache {
    private static final Logger LOGGER = LogManager.getLogger();

    // the most chunk accesses are from the main thread,
    // so we use two maps to reduce synchronization.
    // the main thread accesses this map, without synchronization
    protected final Long2ObjectOpenHashMap<LevelChunk> chunkMapForMainThread =
        new Long2ObjectOpenHashMap<>();
    // other threads read this map, with synchronization
    protected final Long2ObjectOpenHashMap<LevelChunk> chunkMapForOtherThreads =
        new Long2ObjectOpenHashMap<>();

    public final Thread mainThread;

    // 26.2 (api-map #45): super's `level` and `emptyChunk` are PRIVATE, no longer inherited —
    // hold our own copies. `emptyChunk` construction mirrors vanilla ClientChunkCache ctor.
    protected final ClientLevel level;
    protected final LevelChunk emptyChunk;

    public static final SignalArged<LevelChunk> clientChunkLoadSignal = new SignalArged<>();
    public static final SignalArged<LevelChunk> clientChunkUnloadSignal = new SignalArged<>();

    public ImmPtlClientChunkMap(ClientLevel clientWorld, int loadDistance) {
        super(clientWorld, 1);
        // the chunk array is unused. make it small by passing 1 as load distance to super constructor

        // 26.2 (api-map #45): own copies of the now-private super fields.
        this.level = clientWorld;
        this.emptyChunk = new EmptyLevelChunk(
            clientWorld, new ChunkPos(0, 0),
            clientWorld.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS)
        );

        mainThread = ((IEMinecraftClient) Minecraft.getInstance()).ip_getRunningThread();
    }

    @Override
    public void drop(ChunkPos chunkPos) {
        Validate.isTrue(Thread.currentThread() == mainThread);

//        LOGGER.info("unload {} {}", level, chunkPos);

        LevelChunk chunk = chunkMapForMainThread.get(chunkPos.pack()); // 26.2 #42: toLong -> pack
        if (chunk != null) {
            modifyChunkMap(chunkMap -> {
                chunkMap.remove(chunkPos.pack()); // 26.2 #42
            });

            emitChunkRemoved(chunk); // R13f: SOG delta feed (removed-loaded + removed-empty sections)
            O_O.postClientChunkUnloadEvent(chunk);
            this.level.unload(chunk);
            SodiumInterface.invoker.onClientChunkUnloaded(level, chunkPos.x(), chunkPos.z()); // 26.2 #42: .x/.z -> .x()/.z()
            clientChunkUnloadSignal.emit(chunk);
        }
    }

    public <T> T readChunkMap(Function<Long2ObjectOpenHashMap<LevelChunk>, T> func) {
        if (Thread.currentThread() == mainThread) {
            return func.apply(chunkMapForMainThread);
        }
        else {
            synchronized (chunkMapForOtherThreads) {
                return func.apply(chunkMapForOtherThreads);
            }
        }
    }

    public void modifyChunkMap(Consumer<Long2ObjectOpenHashMap<LevelChunk>> func) {
        Validate.isTrue(Thread.currentThread() == mainThread);
        func.accept(chunkMapForMainThread);
        synchronized (chunkMapForOtherThreads) {
            func.accept(chunkMapForOtherThreads);
        }
    }

    @Override
    public @Nullable LevelChunk getChunk(int x, int z, ChunkStatus chunkStatus, boolean create) {
        return readChunkMap(chunkMap -> {
            LevelChunk chunk = chunkMap.get(ChunkPos.pack(x, z)); // 26.2 #42: asLong -> pack
            if (chunk != null) {
                return chunk;
            }

            return create ? this.emptyChunk : null;
        });
    }

    public boolean isChunkLoaded(int x, int z) {
        return readChunkMap(chunkMap -> {
            return chunkMap.containsKey(ChunkPos.pack(x, z)); // 26.2 #42
        });
    }

    @Override
    public void replaceBiomes(int x, int z, FriendlyByteBuf friendlyByteBuf) {
        Validate.isTrue(Thread.currentThread() == mainThread);

        long chunkPosLong = ChunkPos.pack(x, z); // 26.2 #42

        LevelChunk worldChunk = chunkMapForMainThread.get(chunkPosLong);
        ChunkPos chunkPos = new ChunkPos(x, z);
        if (worldChunk == null) {
            LOGGER.error("Trying to replace biomes for missing chunk {} {}", x, z);
        }
        else {
            worldChunk.replaceBiomes(friendlyByteBuf);
        }
    }

    @Override
    public @Nullable LevelChunk replaceWithPacketData(
        int x, int z,
        FriendlyByteBuf buf, Map<Heightmap.Types, long[]> heightmaps, // 26.2 #45/#48: CompoundTag nbt -> Map<Heightmap.Types,long[]>
        Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer
    ) {
        Validate.isTrue(Thread.currentThread() == mainThread);

        long chunkPosLong = ChunkPos.pack(x, z); // 26.2 #42
        LevelChunk worldChunk = chunkMapForMainThread.get(chunkPosLong);
        if (worldChunk == null) {
            worldChunk = new LevelChunk(this.level, new ChunkPos(x, z));
            loadChunkDataFromPacket(buf, heightmaps, worldChunk, consumer);

            LevelChunk worldChunkToPut = worldChunk; // lambda can only capture effectively final variables
            modifyChunkMap(chunkMap -> {
                chunkMap.put(chunkPosLong, worldChunkToPut);
            });
            emitChunkAdded(worldChunk); // R13f: SOG delta feed (new-chunk added-loaded + added-empty sections)
        }
        else {
            loadChunkDataFromPacket(buf, heightmaps, worldChunk, consumer);
            emitRefresh(worldChunk); // R13f: SOG delta feed (api-map #45 existing-chunk refreshEmptySections branch)
        }

        this.level.onChunkLoaded(new ChunkPos(x, z));
        O_O.postClientChunkLoadEvent(worldChunk);
        SodiumInterface.invoker.onClientChunkLoaded(level, x, z);
        clientChunkLoadSignal.emit(worldChunk);

//        LOGGER.info("load {} {} {}", level, x, z);

        return worldChunk;
    }

    /**
     * {@link net.minecraft.core.IdMap#byIdOrThrow(int)}
     * {@link net.minecraft.world.level.chunk.LinearPalette#read(FriendlyByteBuf)}
     */
    private void loadChunkDataFromPacket(
        FriendlyByteBuf buf,
        Map<Heightmap.Types, long[]> heightmaps, // 26.2 #48
        LevelChunk worldChunk,
        Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer
    ) {
        try {
            worldChunk.replaceWithPacketData(buf, heightmaps, consumer); // 26.2 #48
        }
        catch (Exception e) {
            LOGGER.error(
                "Error deserializing chunk packet {} {}",
                worldChunk.getLevel().dimension().identifier(), // 26.2: ResourceKey.location() -> identifier()
                worldChunk.getPos(),
                e
            );
            CHelper.printChat(
                Component
                    .literal("Failed to deserialize chunk packet. %s %s %s".formatted(
                        worldChunk.getLevel().dimension().identifier(), // 26.2: location() -> identifier()
                        worldChunk.getPos().x(), worldChunk.getPos().z() // 26.2 #42: .x/.z -> .x()/.z()
                    ))
                    .append(Component.literal(" Report issue:"))
                    .append(McHelper.getLinkText(O_O.getIssueLink()))
                    .withStyle(ChatFormatting.RED)
            );

            throw new RuntimeException(e);
        }
    }

    public List<LevelChunk> getCopiedChunkList() {
        return readChunkMap((chunkMap) -> {
            return Arrays.asList(chunkMap.values().toArray(new LevelChunk[0]));
        });
    }

    @Override
    public void updateViewCenter(int x, int z) {
        // R13f store-center carriage: IP does nothing here; the mod tracks the center so the
        // bounded eviction sweep (below) has a fallback center. Never used to gate/drop storage.
        this.viewCenterX = x;
        this.viewCenterZ = z;
        this.viewCenterSet = true;
    }

    @Override
    public void updateViewRadius(int r) {
        // do nothing
    }

    @Override
    public String gatherStats() {
        return "Client Chunks (ImmPtl) " + getLoadedChunksCount();
    }

    @Override
    public int getLoadedChunksCount() {
        return readChunkMap(chunkMap -> {
            return chunkMap.size();
        });
    }

    @Override
    public void onLightUpdate(LightLayer lightType, SectionPos chunkSectionPos) {
        // 26.2 render-split (S9-deferred routing, resolved at S10): setSectionDirty moved
        // LevelRenderer → LevelExtractor (api-map chunk-loading #45). getWorldExtractor routes to
        // the per-dim extractor (the ACTIVE dim → the global mc.levelExtractor itself, extractor
        // identity — memory nether-block-freeze-orphaned-extractor).
        // S14.50 note: a ±1 neighbor-spread was TRIED here (fix C) and REVERTED on verify
        // (wf_3b6a4ccd-772): the light engine ALREADY delivers a 27-neighbor affected set to this
        // callback (markSectionAndNeighborsAsAffected at initializeSection → swapSectionMap fires
        // per affected section, POST-publish) — an extra spread would multiply re-marks up to
        // ~27× per update (a remesh-storm cousin of the S14.48-removed all-dirty wave) with zero
        // added healing. Single-section is correct here for ALL dims.
        ClientWorldLoader.getWorldExtractor(level.dimension())
            .setSectionDirty(chunkSectionPos.x(), chunkSectionPos.y(), chunkSectionPos.z());
    }

    // ================================================================================================
    // R13f carriage — 26.2 SOG delta feed (api-map chunk-loading.md #45; current-mod-core §5;
    // memory distant-chunk-vanish-sog-desync). No IP/1.21.3 analog: 26.2 ClientChunkCache publishes
    // per-frame delta sets that LevelExtractor.extract reads + flips (ClientChunkCache.java:180-207 →
    // LevelExtractor.java:138-142 → SectionOcclusionGraph.java:146-147). Own double-buffered sets
    // (super's live in the private Storage). Byte-identical to the mod's runtime-proven
    // SeamlessClientChunkMap; the emit* helpers mirror vanilla Storage.onChunkAdded/onChunkRemoved/
    // refreshEmptySections (ClientChunkCache.java:274-312) MINUS the inRange gate (unbounded store).
    // ================================================================================================

    private final LongOpenHashSet[] addedEmpty = { new LongOpenHashSet(), new LongOpenHashSet() };
    private final LongOpenHashSet[] removedEmpty = { new LongOpenHashSet(), new LongOpenHashSet() };
    private final LongOpenHashSet[] addedLoaded = { new LongOpenHashSet(), new LongOpenHashSet() };
    private final LongOpenHashSet[] removedLoaded = { new LongOpenHashSet(), new LongOpenHashSet() };
    private int idx = 0;

    @Override
    public LongOpenHashSet addedEmptySections() {
        return this.addedEmpty[this.idx];
    }

    @Override
    public LongOpenHashSet removedEmptySections() {
        return this.removedEmpty[this.idx];
    }

    @Override
    public LongOpenHashSet addedLoadedChunks() {
        return this.addedLoaded[this.idx];
    }

    @Override
    public LongOpenHashSet removedLoadedChunks() {
        return this.removedLoaded[this.idx];
    }

    @Override
    public void flipUpdateTrackingSets() {
        this.idx = (this.idx + 1) % 2;
        this.addedEmpty[this.idx].clear();
        this.removedEmpty[this.idx].clear();
        this.addedLoaded[this.idx].clear();
        this.removedLoaded[this.idx].clear();
    }

    @Override
    public void onSectionEmptinessChanged(int sectionX, int sectionY, int sectionZ, boolean empty) {
        long node = SectionPos.asLong(sectionX, sectionY, sectionZ);
        if (empty) {
            this.addedEmpty[this.idx].add(node);
        } else {
            this.removedEmpty[this.idx].add(node);
        }
    }

    private void emitChunkAdded(LevelChunk chunk) {
        ChunkPos p = chunk.getPos();
        this.addedLoaded[this.idx].add(p.pack());
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            if (sections[i].hasOnlyAir()) {
                this.addedEmpty[this.idx].add(SectionPos.asLong(p.x(), chunk.getSectionYFromSectionIndex(i), p.z()));
            }
        }
    }

    private void emitChunkRemoved(LevelChunk chunk) {
        ChunkPos p = chunk.getPos();
        this.removedLoaded[this.idx].add(p.pack());
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            this.removedEmpty[this.idx].add(SectionPos.asLong(p.x(), chunk.getSectionYFromSectionIndex(i), p.z()));
        }
    }

    private void emitRefresh(LevelChunk chunk) {
        ChunkPos p = chunk.getPos();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            long node = SectionPos.asLong(p.x(), chunk.getSectionYFromSectionIndex(i), p.z());
            if (sections[i].hasOnlyAir()) {
                this.addedEmpty[this.idx].add(node);
            } else {
                this.removedEmpty[this.idx].add(node);
            }
        }
    }

    // ================================================================================================
    // R13f carriage — store-center machinery (memory walking-limbo-seed-overclaim §5;
    // current-mod-core §5). The per-tick DRIVER (recenter on the nearest in-range portal's dest
    // origin, grace-window release) is NOT the ported ClientWorldLoader — the verbatim IP
    // ClientWorldLoader (landed S10) deliberately has no eviction driver (these sweeps have NO
    // 1.21.3/IP analog, so injecting a driver into the verbatim file would deviate). Until cutover
    // the live driver REMAINS the mod's PortalWorldManager.evictUnboundedStores over the mod store
    // SeamlessClientChunkMap. These are the STORE-side methods a cutover driver WOULD call on THIS
    // store IF the sweeps prove necessary — which the PORT-FORWARD note below gates at S14/S17.
    // PORT-FORWARD (verify): under IP's constructor-hook install (EVERY client world, incl. main),
    // the server's ImmPtlChunkTracking already drives vanilla forget packets (→ drop); whether these
    // sweeps remain necessary for INACTIVE secondaries is re-proven at S14/S17 (C8/F20 gate).
    // ================================================================================================

    private volatile int viewCenterX = 0;
    private volatile int viewCenterZ = 0;
    private volatile boolean viewCenterSet = false;

    /**
     * Bounded eviction: drop resident chunks more than {@code radius} chunks (Chebyshev) from the
     * tracked view center. Main-thread only. Called per client tick by the eviction driver (until
     * cutover: the mod's {@code PortalWorldManager.evictUnboundedStores} over
     * {@code SeamlessClientChunkMap}; re-homing onto this store is the S14/S17 gate — see the
     * section header) so an inactive-secondary store stays bounded (vanilla's forget-chunk path only
     * drops on the ACTIVE level).
     */
    public void evictBeyond(int radius) {
        if (Thread.currentThread() != this.mainThread || this.chunkMapForMainThread.isEmpty() || !this.viewCenterSet) {
            return;
        }
        evictBeyondCenter(this.viewCenterX, this.viewCenterZ, radius);
    }

    /**
     * Bounded eviction around an EXPLICIT center (chunk coords) rather than the tracked view center —
     * the store-center PINNING path (memory walking-limbo-seed-overclaim §5): the eviction driver
     * (deferred to cutover; see the section header) recenters on the nearest in-range portal's dest
     * origin every tick so a still-"near" dim keeps its live region resident even when not currently
     * viewed. Main-thread only.
     */
    public void evictAround(int centerChunkX, int centerChunkZ, int radius) {
        if (Thread.currentThread() != this.mainThread || this.chunkMapForMainThread.isEmpty()) {
            return;
        }
        evictBeyondCenter(centerChunkX, centerChunkZ, radius);
    }

    /**
     * Drop EVERY resident chunk — the grace-window release: when no portal has linked into this dim
     * for longer than the grace window it stops being maintained and its store is released; a return
     * crossing re-streams it from the server. Main-thread only.
     */
    public void evictAll() {
        if (Thread.currentThread() != this.mainThread || this.chunkMapForMainThread.isEmpty()) {
            return;
        }
        // Snapshot keys first — drop() mutates the map.
        LongArrayList toDrop = new LongArrayList(this.chunkMapForMainThread.keySet());
        for (int i = 0; i < toDrop.size(); i++) {
            long key = toDrop.getLong(i);
            drop(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)));
        }
    }

    private void evictBeyondCenter(int cx, int cz, int radius) {
        LongArrayList toDrop = null;
        LongIterator it = this.chunkMapForMainThread.keySet().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (Math.abs(ChunkPos.getX(key) - cx) > radius || Math.abs(ChunkPos.getZ(key) - cz) > radius) {
                if (toDrop == null) {
                    toDrop = new LongArrayList();
                }
                toDrop.add(key);
            }
        }
        if (toDrop != null) {
            for (int i = 0; i < toDrop.size(); i++) {
                long key = toDrop.getLong(i);
                drop(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)));
            }
        }
    }

}
