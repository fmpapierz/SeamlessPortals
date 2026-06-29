package com.warwa.seamlessportals.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * T3 — IP-faithful UNBOUNDED client chunk store for a portal SECONDARY {@link ClientLevel},
 * a 26.2 port of {@code qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap}.
 *
 * <p>Vanilla {@link ClientChunkCache} stores chunks in a fixed-radius
 * {@code AtomicReferenceArray} (Storage) gated by {@code inRange}; chunks beyond the
 * radius are dropped and {@code updateViewRadius} rebuilds the array, discarding
 * out-of-range chunks. For a portal dest that means: when the player crosses, the dim
 * the secondary represents must fill to the player render distance, and everything the
 * fixed cap dropped reloads — the server re-sends it, the client re-decodes it, and the
 * whole batch re-meshes (the measured 250-470ms post-teleport stall).
 *
 * <p>This store replaces the radius-array with an unbounded {@link Long2ObjectOpenHashMap}
 * (dual main-thread / worker-thread maps, exactly as IP) and makes {@code updateViewRadius}
 * a no-op, so resident chunks are NEVER dropped by distance — a return-crossing finds them
 * already present, and {@code replaceWithPacketData} on an existing chunk takes the refresh
 * path (no mass re-add), so the re-decode/re-mesh storm cannot recur.
 *
 * <p>Because {@code emptyChunk}, {@code level}, and {@code storage} are PRIVATE in 26.2,
 * the subclass holds its own {@code level}/{@code emptyChunk}/{@code mainThread} and a full
 * copy of the four delta-tracking buffers (addedEmptySections / removedEmptySections /
 * addedLoadedChunks / removedLoadedChunks) that {@link LevelExtractor#extract} reads + flips
 * every frame — overriding ALL of {@code add/removeEmpty/LoadedSections + flip +
 * onSectionEmptinessChanged} together against its OWN buffers (the dead radius-1 super
 * Storage is never touched). It inherits {@code getLightEngine} (super built it capturing
 * this as the LightChunkGetter) so lighting routes through the overridden {@link #getChunk}.
 *
 * <p>Gated behind {@code SeamlessPortalsConfig.isUnboundedClientChunkStore()} (off by
 * default) and installed only on mod-created secondary levels. A bounded
 * {@link #seamlessportals$evictBeyond} sweep (driven per client tick) keeps it from growing
 * unbounded as the player roams (vanilla's forget-chunk eviction only fires on the ACTIVE
 * level, never an inactive secondary).
 */
public class SeamlessClientChunkMap extends ClientChunkCache {

    private final ClientLevel ccLevel;
    private final LevelChunk ccEmptyChunk;
    private final Thread mainThread;

    // Dual map (IP design): the game thread reads/writes mainMap unsynchronized; worker
    // threads read otherMap under its monitor. modifyMap (main only) mutates both.
    private final Long2ObjectOpenHashMap<LevelChunk> mainMap = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<LevelChunk> otherMap = new Long2ObjectOpenHashMap<>();

    // Own copy of the 26.2 double-buffered delta sets (super's live in the private Storage).
    private final LongOpenHashSet[] addedEmpty = { new LongOpenHashSet(), new LongOpenHashSet() };
    private final LongOpenHashSet[] removedEmpty = { new LongOpenHashSet(), new LongOpenHashSet() };
    private final LongOpenHashSet[] addedLoaded = { new LongOpenHashSet(), new LongOpenHashSet() };
    private final LongOpenHashSet[] removedLoaded = { new LongOpenHashSet(), new LongOpenHashSet() };
    private int idx = 0;

    // Tracked (NOT used to gate storage) so the bounded eviction sweep has a center.
    private volatile int viewCenterX = 0;
    private volatile int viewCenterZ = 0;
    private volatile boolean viewCenterSet = false;

    public SeamlessClientChunkMap(ClientLevel level, int serverChunkRadius) {
        // radius 1 => super allocates a tiny (unused) Storage and, crucially, builds super's
        // LevelLightEngine capturing THIS as the LightChunkGetter. Do NOT override getLightEngine.
        super(level, 1);
        this.ccLevel = level;
        this.mainThread = Thread.currentThread();
        this.ccEmptyChunk = new EmptyLevelChunk(
            level, new ChunkPos(0, 0),
            level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
    }

    private <T> T readMap(Function<Long2ObjectOpenHashMap<LevelChunk>, T> func) {
        if (Thread.currentThread() == mainThread) {
            return func.apply(mainMap);
        }
        synchronized (otherMap) {
            return func.apply(otherMap);
        }
    }

    private void modifyMap(Consumer<Long2ObjectOpenHashMap<LevelChunk>> func) {
        // main-thread only (all structural mutation is on the render/main thread).
        func.accept(mainMap);
        synchronized (otherMap) {
            func.accept(otherMap);
        }
    }

    @Override
    public @Nullable LevelChunk getChunk(int x, int z, ChunkStatus targetStatus, boolean loadOrGenerate) {
        long key = ChunkPos.pack(x, z);
        LevelChunk chunk = readMap(m -> m.get(key));
        if (chunk != null) {
            return chunk;
        }
        return loadOrGenerate ? this.ccEmptyChunk : null;
    }

    @Override
    public @Nullable LevelChunk replaceWithPacketData(
            int chunkX, int chunkZ, FriendlyByteBuf readBuffer,
            Map<Heightmap.Types, long[]> heightmaps,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        LevelChunk existing = readMap(m -> m.get(key));
        LevelChunk chunk;
        if (existing == null) {
            chunk = new LevelChunk(this.ccLevel, pos);
            chunk.replaceWithPacketData(readBuffer, heightmaps, blockEntities);
            final LevelChunk added = chunk;
            modifyMap(m -> m.put(key, added));
            emitChunkAdded(chunk);
        } else {
            chunk = existing;
            chunk.replaceWithPacketData(readBuffer, heightmaps, blockEntities);
            emitRefresh(chunk);
        }
        this.ccLevel.onChunkLoaded(pos);
        return chunk;
    }

    @Override
    public void replaceBiomes(int chunkX, int chunkZ, FriendlyByteBuf readBuffer) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        LevelChunk chunk = readMap(m -> m.get(key));
        if (chunk != null) {
            chunk.replaceBiomes(readBuffer);
        }
    }

    @Override
    public void drop(ChunkPos pos) {
        long key = pos.pack();
        LevelChunk removed = readMap(m -> m.get(key));
        if (removed != null) {
            modifyMap(m -> m.remove(key));
            emitChunkRemoved(removed);
            this.ccLevel.unload(removed);
        }
    }

    @Override
    public void updateViewCenter(int x, int z) {
        // Track for the eviction sweep, but DO NOT use it to gate/drop storage.
        this.viewCenterX = x;
        this.viewCenterZ = z;
        this.viewCenterSet = true;
    }

    @Override
    public void updateViewRadius(int viewRange) {
        // no-op: the unbounded store never shrinks by render distance (the whole point of T3).
    }

    @Override
    public int getLoadedChunksCount() {
        return this.mainMap.size();
    }

    @Override
    public String gatherStats() {
        return "SeamlessUnbounded: " + this.mainMap.size();
    }

    @Override
    public void onLightUpdate(LightLayer layer, SectionPos pos) {
        // Vanilla hardcodes mc.levelExtractor (the MAIN renderer). Route to the secondary's
        // extractor so its portal-view sections re-mesh; fall back to main when this store's
        // level IS the active level (post-promote, its extractor is mc.levelExtractor).
        Minecraft mc = Minecraft.getInstance();
        if (this.ccLevel == mc.level) {
            mc.levelExtractor.setSectionDirty(pos.x(), pos.y(), pos.z());
            return;
        }
        LevelExtractor ex = PortalWorldManager.getExtractor(this.ccLevel.dimension());
        if (ex != null) {
            ex.setSectionDirty(pos.x(), pos.y(), pos.z());
        }
    }

    // ---- delta-tracking surface (own buffers; LevelExtractor.extract reads + flips these) ----

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

    // Mirror Storage.onChunkAdded / onChunkRemoved / refreshEmptySections (ClientChunkCache:274-312),
    // but with NO inRange gate and against our own buffers.
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

    /**
     * Bounded eviction: drop resident chunks more than {@code radius} chunks (Chebyshev) from
     * the tracked view center. Called per client tick so the inactive-secondary store stays
     * bounded (vanilla's forget-chunk path only drops on the ACTIVE level). Main-thread only.
     */
    public void seamlessportals$evictBeyond(int radius) {
        if (Thread.currentThread() != this.mainThread || this.mainMap.isEmpty() || !this.viewCenterSet) {
            return;
        }
        int cx = this.viewCenterX;
        int cz = this.viewCenterZ;
        LongArrayList toDrop = null;
        LongIterator it = this.mainMap.keySet().iterator();
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
