package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.WeakHashMap;

/**
 * E1 — Custom chunk-loading ticket for cross-dim portal chunks.
 *
 * <p>Mirrors IP 1.19's {@code MyLoadingTicket.portalLoadingTicketType}:
 * a TicketType with NO auto-expiry, ref-counted add/remove via per-world
 * LongSortedSet. Used by {@code SeamlessChunkTrackingGraph} (E4) when a
 * watched cross-dim chunk needs to stay loaded server-side so we can
 * fetch + send it via {@code SeamlessChunkDataSync} (E6).
 *
 * <p><b>Why a custom TicketType, not vanilla {@code TicketType.PORTAL}.</b>
 * Vanilla {@code PORTAL} has a 300-tick lifetime — it self-expires after
 * 15 seconds. To keep a chunk loaded continuously you'd have to re-add
 * the ticket every tick, which floods {@code TicketStorage} with bookkeeping
 * and was the root cause of "Can't keep up! Running 6029ms behind"
 * warnings in the prior {@code CrossDimChunkTracker} v1. Our type uses
 * {@code timeout = NO_TIMEOUT} so a single add stays valid until we
 * explicitly remove it. The graph's
 * {@code updateAndPurge} is the only thing that calls remove.
 *
 * <p><b>Why FLAG_LOADING and not FLAG_SIMULATION.</b> {@code FLAG_LOADING}
 * (=2) loads the chunk into memory but doesn't tick it. We don't need
 * mob spawning / block entity ticking on cross-dim chunks — we only
 * need to read them to encode + send the chunk packet. Saves CPU and
 * keeps cross-dim load proportional to "what's visible through portals"
 * not "what's actively running."
 *
 * <p><b>26.1.2 delta from IP 1.19.</b> 1.19's {@code TicketType.create(name,
 * comparator)} no longer exists — {@code TicketType} is now a record
 * {@code (timeout, flags, forceNaturalSpawning)} with no generic
 * parameter and no comparator. Registration is via
 * {@code Registry.register(BuiltInRegistries.TICKET_TYPE, id, instance)}.
 *
 * <p>The {@code addTicketWithRadius} / {@code removeTicketWithRadius}
 * APIs on {@link ServerChunkCache} take {@code (TicketType, ChunkPos,
 * radius)} and don't consult the registry — they store the {@link
 * net.minecraft.server.level.Ticket} object directly. Registry
 * registration is therefore best-effort: it benefits NBT
 * serialization/round-trip but is not required for the in-memory
 * ticket lifecycle we need.
 */
public final class SeamlessLoadingTicket {

    private SeamlessLoadingTicket() {}

    /**
     * Custom ticket type: no auto-expiry, FLAG_LOADING (=2) only.
     * Constructed eagerly so it's available to callers even before
     * {@link #init()} runs (init only registers the type into the
     * vanilla registry).
     */
    public static final TicketType SEAMLESS_PORTAL_LOADING =
        new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);

    /**
     * Identifier we register under. Stored as a constant for
     * deterministic lookup if anyone later wants to find our ticket
     * type via the registry.
     */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(
        SeamlessPortalsConstants.MOD_ID, "portal_loading");

    /**
     * Loading radius for the {@code addTicketWithRadius} call. Matches
     * IP 1.19's {@code MyLoadingTicket.getLoadingRadius()} default of 1
     * (active loading) — the chunk and its 8 neighbors are loaded.
     * The graph adds tickets per-chunk, so the actual loaded set is the
     * union of all per-chunk radius-1 disks — covers every chunk a
     * watcher cares about.
     */
    private static final int LOADING_RADIUS = 1;

    /**
     * Per-level set of currently-ticketed {@code chunkPos.pack()} keys.
     * Used by {@link #addTicketIfNotLoaded} / {@link #removeTicketIfPresent}
     * to make the operations idempotent — first add registers the
     * ticket, redundant adds no-op; first remove unregisters, redundant
     * removes no-op. {@link WeakHashMap} so a server-stop / dim-remove
     * doesn't pin the {@link ServerLevel} reference.
     */
    private static final WeakHashMap<ServerLevel, LongSortedSet> loadedChunkRecord =
        new WeakHashMap<>();

    private static volatile boolean registryAttempted = false;

    /**
     * Best-effort registry registration. Should be called from
     * {@code ModInitializer.onInitialize} (Fabric) or equivalent
     * before any ticket is added. Idempotent.
     *
     * <p>If the registry is already frozen (unlikely from mod init,
     * but possible if called late) a warning is logged and the type
     * stays usable as an anonymous instance — only NBT round-trip
     * would be affected.
     */
    public static void init() {
        if (registryAttempted) return;
        registryAttempted = true;
        try {
            Registry.register(
                BuiltInRegistries.TICKET_TYPE,
                ID,
                SEAMLESS_PORTAL_LOADING);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS TICKET] Registered custom TicketType {}", ID);
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS TICKET] Could not register TicketType {} " +
                "(registry frozen?). The type is still usable as an " +
                "in-memory instance: {}", ID, t.toString());
        }
    }

    /**
     * Ref-counted add. First call for a (world, chunkPos) pair adds the
     * ticket via {@link ServerChunkCache#addTicketWithRadius}; redundant
     * calls return without server-side work.
     */
    public static void addTicketIfNotLoaded(ServerLevel world, ChunkPos chunkPos) {
        boolean newlyAdded = getRecord(world).add(chunkPos.pack());
        if (newlyAdded) {
            world.getChunkSource().addTicketWithRadius(
                SEAMLESS_PORTAL_LOADING, chunkPos, LOADING_RADIUS);
        }
    }

    /** Ref-counted remove. First call drops the ticket; redundant calls no-op. */
    public static void removeTicketIfPresent(ServerLevel world, ChunkPos chunkPos) {
        boolean newlyRemoved = getRecord(world).remove(chunkPos.pack());
        if (newlyRemoved) {
            world.getChunkSource().removeTicketWithRadius(
                SEAMLESS_PORTAL_LOADING, chunkPos, LOADING_RADIUS);
        }
    }

    /**
     * Returns the live record set for the world. Used by
     * {@code SeamlessChunkTrackingGraph.updateAndPurge} to iterate
     * "what we currently have ticketed" and prune chunks that no
     * watcher needs anymore.
     */
    public static LongSortedSet getRecord(ServerLevel world) {
        return loadedChunkRecord.computeIfAbsent(world, k -> new LongLinkedOpenHashSet());
    }

    /**
     * Drop every ticket we hold for the given world. Called when a
     * dimension is removed so the orphaned tickets don't pin chunks
     * in a level that's about to be discarded.
     */
    public static void onLevelUnload(ServerLevel world) {
        LongSortedSet record = loadedChunkRecord.remove(world);
        if (record == null) return;
        ServerChunkCache cache = world.getChunkSource();
        record.forEach((long pos) -> {
            try {
                cache.removeTicketWithRadius(
                    SEAMLESS_PORTAL_LOADING,
                    ChunkPos.unpack(pos),
                    LOADING_RADIUS);
            } catch (Throwable ignored) {
                // World may already be partially torn down.
            }
        });
    }

    /** Wipe all state. Called on server stop. */
    public static void clear() {
        loadedChunkRecord.clear();
    }
}
