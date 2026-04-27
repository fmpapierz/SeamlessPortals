package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.client.PortalWorldManager;
import com.warwa.seamlessportals.mixin.client.ClientChunkCacheAccessorMixin;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.apache.commons.lang3.Validate;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Port of Immersive Portals' {@code ImmPtlClientChunkMap} adapted for
 * SeamlessPortals (MC 26.1.2). API drift handled vs IP's 1.21 source:
 * <ul>
 *   <li>{@code ChunkPos.toLong()} / {@code asLong(x,z)} → {@code pack()}
 *       / {@code pack(x,z)} static.</li>
 *   <li>{@code ResourceKey<Level>.location()} → {@code identifier()}.</li>
 *   <li>{@code replaceWithPacketData} signature changed: {@code CompoundTag}
 *       parameter is now {@code Map<Heightmap.Types, long[]>}.</li>
 *   <li>{@code ClientChunkCache.level} and {@code emptyChunk} are
 *       {@code private final} in 26.1.2 (in 1.21 IP could access them
 *       directly through their {@code @Mixin} package). We stash our own
 *       {@code ClientLevel} reference at construction and use an accessor
 *       mixin {@link ClientChunkCacheAccessorMixin} for {@code emptyChunk}.</li>
 * </ul>
 *
 * <p><b>Why we need this:</b> Vanilla {@link ClientChunkCache} stores its
 * chunk references in a 2-D array sized to the player's render distance
 * (the {@code Storage} inner class). Chunks beyond RD CANNOT be stored —
 * the array slot doesn't exist. That's exactly the wall the teleport
 * lag diagnostic hit: cached destination {@link ClientLevel}s could only
 * hold ~50 chunks (whatever fit within the small array around the
 * portal-view "camera" section), so {@code SectionOcclusionGraph}'s BFS
 * through {@code level.hasChunk(x, z)} stopped at ~25 visibleSections
 * after teleport — even with all our compile-pump tuning. World rendered
 * mostly empty until vanilla's per-tick chunk streamer caught up
 * over ~5 seconds.
 *
 * <p>This class swaps that 2-D array out for a hash map keyed by
 * {@link ChunkPos#pack()}. No RD limit. Two parallel maps so the main
 * thread reads without synchronization while networking / worker threads
 * read with sync — matches IP's discipline. {@code drop()} explicitly
 * removes a chunk (used by vanilla {@code ClientChunkCache.updateViewCenter}
 * when chunks fall outside RD on the active level, and by IP-style
 * cross-dim {@code ClientboundForgetLevelChunkPacket} once Stage 2 lands).
 *
 * <p><b>Mechanism:</b> We pass {@code loadDistance=1} to the vanilla
 * super constructor so its underlying {@code Storage} array is the
 * smallest legal size (3×3). We never use it; all reads/writes hit our
 * hash map.
 *
 * <p><b>Differences from vanilla:</b>
 * <ul>
 *   <li>{@code updateViewCenter} / {@code updateViewRadius} are no-ops.
 *       Vanilla relied on these to shift the array; we don't have an
 *       array.</li>
 *   <li>{@code onLightUpdate} routes the section-dirty mark to the
 *       correct dimension's renderer via {@link PortalWorldManager}
 *       instead of always {@code Minecraft.levelRenderer} — so light
 *       updates received via cross-dim packet redirection (Stage 2)
 *       reach the correct cached renderer.</li>
 * </ul>
 */
public class SeamlessClientChunkMap extends ClientChunkCache {

    /**
     * Our own reference to the {@link ClientLevel} since the vanilla
     * field is {@code private}. Set at construction.
     */
    private final ClientLevel ourLevel;

    /**
     * Chunk references read by the client main render/tick thread.
     * Direct read access; no sync.
     */
    protected final Long2ObjectOpenHashMap<LevelChunk> chunkMapForMainThread =
        new Long2ObjectOpenHashMap<>();

    /**
     * Chunk references read by network and worker threads. All access
     * is synchronized on the map instance.
     */
    protected final Long2ObjectOpenHashMap<LevelChunk> chunkMapForOtherThreads =
        new Long2ObjectOpenHashMap<>();

    /**
     * The thread treated as "main" for fast-path reads. Captured at
     * construction time. {@link Minecraft#isSameThread()} would also
     * work but is more expensive than a reference compare.
     */
    public final Thread mainThread;

    public SeamlessClientChunkMap(ClientLevel clientLevel, int loadDistance) {
        // Pass 1 to super so vanilla's underlying chunk array is the
        // smallest legal size. We never read from it; the hash maps
        // above are the source of truth.
        super(clientLevel, 1);
        this.ourLevel = clientLevel;
        this.mainThread = Thread.currentThread();
    }

    /**
     * Vanilla calls this when a chunk should be unloaded. Removes from
     * both maps and notifies the level so per-block-entity unload events
     * fire correctly.
     */
    @Override
    public void drop(ChunkPos chunkPos) {
        Validate.isTrue(Thread.currentThread() == mainThread,
            "drop() must be called on the main thread");

        LevelChunk chunk = chunkMapForMainThread.get(chunkPos.pack());
        if (chunk != null) {
            modifyChunkMap(map -> map.remove(chunkPos.pack()));
            this.ourLevel.unload(chunk);
        }
    }

    /**
     * Read access discriminating on thread. Main thread hits the
     * unsynchronized map for speed; other threads use the synced map.
     */
    public <T> T readChunkMap(Function<Long2ObjectOpenHashMap<LevelChunk>, T> func) {
        if (Thread.currentThread() == mainThread) {
            return func.apply(chunkMapForMainThread);
        } else {
            synchronized (chunkMapForOtherThreads) {
                return func.apply(chunkMapForOtherThreads);
            }
        }
    }

    /**
     * Write access. Must be on the main thread. Updates both maps so
     * subsequent reads (regardless of thread) see the change.
     */
    public void modifyChunkMap(Consumer<Long2ObjectOpenHashMap<LevelChunk>> func) {
        Validate.isTrue(Thread.currentThread() == mainThread,
            "modifyChunkMap() must be called on the main thread");
        func.accept(chunkMapForMainThread);
        synchronized (chunkMapForOtherThreads) {
            func.accept(chunkMapForOtherThreads);
        }
    }

    @Override
    public LevelChunk getChunk(int x, int z, ChunkStatus chunkStatus, boolean create) {
        return readChunkMap(map -> {
            LevelChunk chunk = map.get(ChunkPos.pack(x, z));
            if (chunk != null) return chunk;
            return create
                ? ((ClientChunkCacheAccessorMixin) this).seamlessportals$getEmptyChunk()
                : null;
        });
    }

    /**
     * Public helper for callers that want a yes/no answer without the
     * empty-chunk fallback {@link #getChunk} returns when {@code create}
     * is true.
     */
    public boolean isChunkLoaded(int x, int z) {
        return readChunkMap(map -> map.containsKey(ChunkPos.pack(x, z)));
    }

    @Override
    public void replaceBiomes(int x, int z, FriendlyByteBuf buf) {
        Validate.isTrue(Thread.currentThread() == mainThread);
        LevelChunk chunk = chunkMapForMainThread.get(ChunkPos.pack(x, z));
        if (chunk == null) {
            SeamlessPortalsConstants.LOGGER.error(
                "Tried to replace biomes for missing chunk {} {}", x, z);
        } else {
            chunk.replaceBiomes(buf);
        }
    }

    /**
     * MC 26.1.2 signature: 5-arg with a {@code Map<Heightmap.Types, long[]>}
     * heightmaps parameter (replaces the 1.x {@code CompoundTag nbt}).
     */
    @Override
    public @Nullable LevelChunk replaceWithPacketData(
            int x, int z,
            FriendlyByteBuf buf,
            Map<Heightmap.Types, long[]> heightmaps,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities) {
        Validate.isTrue(Thread.currentThread() == mainThread);

        long chunkPosLong = ChunkPos.pack(x, z);
        LevelChunk chunk = chunkMapForMainThread.get(chunkPosLong);
        if (chunk == null) {
            chunk = new LevelChunk(this.ourLevel, new ChunkPos(x, z));
            loadChunkDataFromPacket(buf, heightmaps, chunk, blockEntities);
            LevelChunk chunkToPut = chunk;
            modifyChunkMap(map -> map.put(chunkPosLong, chunkToPut));
        } else {
            loadChunkDataFromPacket(buf, heightmaps, chunk, blockEntities);
        }

        this.ourLevel.onChunkLoaded(new ChunkPos(x, z));
        return chunk;
    }

    private void loadChunkDataFromPacket(
            FriendlyByteBuf buf,
            Map<Heightmap.Types, long[]> heightmaps,
            LevelChunk chunk,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities) {
        // Don't wrap exceptions — let {@code IndexOutOfBoundsException}
        // propagate raw so {@link com.warwa.seamlessportals.mixin.client.ChunkPacketGuardMixin}
        // can catch it and drop the stale packet without disconnecting.
        // (The guard's catch is on the exact exception type; wrapping in
        // RuntimeException defeats it and was the cause of an instant
        // disconnect on the first cross-dim chunk after the chunk-source
        // swap.)
        chunk.replaceWithPacketData(buf, heightmaps, blockEntities);
    }

    /**
     * Snapshot of currently-loaded chunks. Returns a copy so the caller
     * doesn't depend on internal map state.
     */
    public List<LevelChunk> getCopiedChunkList() {
        return readChunkMap(map ->
            Arrays.asList(map.values().toArray(new LevelChunk[0])));
    }

    /**
     * No-op: vanilla used this to shift its 2-D array. We have a hash
     * map keyed by chunk pos; the view center doesn't matter for
     * storage. Eviction is server-driven via
     * {@link net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket}.
     */
    @Override
    public void updateViewCenter(int x, int z) {
        // intentionally empty
    }

    /**
     * No-op for the same reason as {@link #updateViewCenter}.
     */
    @Override
    public void updateViewRadius(int r) {
        // intentionally empty
    }

    @Override
    public String gatherStats() {
        return "Client Chunks (Seamless) " + getLoadedChunksCount();
    }

    @Override
    public int getLoadedChunksCount() {
        return readChunkMap(Long2ObjectOpenHashMap::size);
    }

    /**
     * Route the section-dirty mark to THIS level's renderer (not always
     * {@code Minecraft.getInstance().levelRenderer} which is always the
     * active main renderer). Without this, light updates arriving via
     * cross-dim packet redirection (Stage 2) would incorrectly mark the
     * active main renderer dirty when the change actually applies to a
     * cached secondary renderer's chunk data.
     */
    @Override
    public void onLightUpdate(LightLayer lightType, SectionPos sectionPos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == this.ourLevel && mc.levelRenderer != null) {
            mc.levelRenderer.setSectionDirty(
                sectionPos.x(), sectionPos.y(), sectionPos.z());
            return;
        }
        // Cached secondary level — route to the corresponding cached
        // renderer if one exists.
        if (PortalWorldManager.hasRenderer(this.ourLevel.dimension())) {
            net.minecraft.client.renderer.LevelRenderer renderer =
                PortalWorldManager.getOrCreateRenderer(this.ourLevel.dimension());
            if (renderer != null) {
                renderer.setSectionDirty(
                    sectionPos.x(), sectionPos.y(), sectionPos.z());
            }
        }
    }
}
