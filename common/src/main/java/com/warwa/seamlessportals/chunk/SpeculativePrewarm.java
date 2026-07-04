package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.PortalShape;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * SPECULATIVE PRE-WARMING (beyond-IP enhancement, config-gated): when a valid UNLIT obsidian
 * frame exists near a player, pre-load + pre-generate + pre-stream the frame's would-be
 * destination region BEFORE anyone lights it — so ignition reveals an already-prepared view
 * (true perceived-instant portal).
 *
 * <p>Detection: when an OBSIDIAN block is placed server-side (player placement OR lava+water —
 * both go through {@code LevelChunk.setBlockState}, hooked by LevelChunkSetBlockStateMixin),
 * probe the placed block's AIR neighbours with vanilla {@link PortalShape#findEmptyPortalShape}
 * (which tries both axes) — a hit means a complete, valid, unlit frame now exists. Known v1
 * limitation: a frame "completed" by MINING its interior last isn't detected (no obsidian
 * placement fires); the periodic portal scan + normal ignition flow cover it.
 *
 * <p>What pre-warming does — and deliberately does NOT do: it computes the EXPECTED destination
 * (coordinate-scale of the frame position, same math as the real link) and keeps that region
 * loaded server-side (prewarm ticket) + streamed to nearby players + scope-live client-side
 * (via {@code SpeculativePrewarmScopePayload} → mesh pre-compilation). It NEVER creates the
 * destination portal blocks or a link — no world mutation for a portal that may never be lit.
 * At ignition, {@code PortalForcer} runs against already-loaded chunks (fast) and the client
 * already holds chunks + meshes, so the window fills the moment the link arrives.
 *
 * <p>All state is server-thread-only. Entries expire when the frame is lit (portal formed
 * nearby), broken (revalidation fails), or no player has been near for {@link #TTL_TICKS}.
 */
public final class SpeculativePrewarm {

    private SpeculativePrewarm() {}

    private static final class Entry {
        final ResourceKey<Level> srcDim;
        final BlockPos probePos;          // an interior air block of the unlit frame
        final ResourceKey<Level> destDim;
        final ChunkPos destCenter;
        long lastNearTick;
        long lastValidatedTick;

        Entry(ResourceKey<Level> srcDim, BlockPos probePos,
              ResourceKey<Level> destDim, ChunkPos destCenter, long now) {
            this.srcDim = srcDim;
            this.probePos = probePos;
            this.destDim = destDim;
            this.destCenter = destCenter;
            this.lastNearTick = now;
            this.lastValidatedTick = now;
        }
    }

    /** Keyed by srcDim + probe pos. Server thread only. */
    private static final Map<String, Entry> entries = new HashMap<>();

    /** How close a player must be (blocks) for an entry to stay warm. */
    private static final double PLAYER_NEAR_BLOCKS = 48.0;
    private static final double PLAYER_NEAR_SQ = PLAYER_NEAR_BLOCKS * PLAYER_NEAR_BLOCKS;
    /** Drop an entry after this long with no player nearby (ticks). */
    private static final long TTL_TICKS = 20L * 120L;
    /** Re-validate the frame (still complete + unlit) this often (ticks). */
    private static final long REVALIDATE_TICKS = 100L;
    /** Pre-warm radius (chunks) around the expected dest — the instant near-field. */
    public static final int PREWARM_RADIUS_CHUNKS = 8;

    private static String key(ResourceKey<Level> dim, BlockPos pos) {
        return dim.identifier() + ":" + pos.asLong();
    }

    /**
     * Called (server thread) when an OBSIDIAN block lands in a chunk. Probes air neighbours
     * for a completed, valid, UNLIT frame; registers a prewarm entry on a hit.
     */
    public static void onObsidianPlaced(ServerLevel level, BlockPos placed) {
        if (!SeamlessPortalsConfig.get().isSpeculativePrewarm()) return;
        ResourceKey<Level> srcDim = level.dimension();
        ResourceKey<Level> destDim = counterpart(srcDim);
        if (destDim == null) return;

        for (Direction dir : Direction.values()) {
            BlockPos probe = placed.relative(dir);
            if (!level.getBlockState(probe).isAir()) continue;
            if (PortalShape.findEmptyPortalShape(level, probe, Direction.Axis.X).isEmpty()) continue;

            String k = key(srcDim, probe);
            if (entries.containsKey(k)) return;
            // Coalesce: one entry per frame is enough — if any existing entry's probe is within
            // the max frame size of this one (same dim), skip.
            for (Entry e : entries.values()) {
                if (e.srcDim.equals(srcDim) && e.probePos.distSqr(probe) < 24 * 24) return;
            }

            BlockPos expected = scaleToDest(srcDim, probe, level.getServer());
            if (expected == null) return;
            Entry entry = new Entry(srcDim, probe.immutable(), destDim,
                new ChunkPos(expected.getX() >> 4, expected.getZ() >> 4), level.getGameTime());
            entries.put(k, entry);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PREWARM] Unlit frame detected at {} in {} — pre-warming expected dest {} in {}",
                probe.toShortString(), srcDim.identifier(), expected.toShortString(), destDim.identifier());
            return; // one hit per placement is enough
        }
    }

    private static ResourceKey<Level> counterpart(ResourceKey<Level> dim) {
        if (dim.equals(Level.OVERWORLD)) return Level.NETHER;
        if (dim.equals(Level.NETHER)) return Level.OVERWORLD;
        return null;
    }

    /** Same coordinate-scale math the real link uses (OW→nether ÷8, nether→OW ×8). */
    private static BlockPos scaleToDest(ResourceKey<Level> srcDim, BlockPos src, MinecraftServer server) {
        ResourceKey<Level> destDim = counterpart(srcDim);
        if (destDim == null) return null;
        ServerLevel dest = server.getLevel(destDim);
        if (dest == null) return null;
        double scale = srcDim.equals(Level.OVERWORLD) ? 1.0 / 8.0 : 8.0;
        int x = (int) Math.round(src.getX() * scale);
        int z = (int) Math.round(src.getZ() * scale);
        int y = Math.max(dest.getMinY() + 8, Math.min(dest.getMaxY() - 8, src.getY()));
        return new BlockPos(x, y, z);
    }

    /**
     * Per-server-tick upkeep (from PortalChunkTracker.tick): revalidate frames, expire stale
     * entries, and keep the dest regions of player-adjacent entries loaded via the prewarm ticket.
     */
    public static void tick(MinecraftServer server) {
        if (entries.isEmpty()) return;
        if (!SeamlessPortalsConfig.get().isSpeculativePrewarm()) {
            entries.clear();
            return;
        }
        long now = server.overworld().getGameTime();
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            ServerLevel srcLevel = server.getLevel(e.srcDim);
            if (srcLevel == null) { it.remove(); continue; }

            // Revalidate: frame broken, or lit (probe becomes a portal block → the real link
            // flow takes over via onPortalLit anyway; this is the belt).
            if (now - e.lastValidatedTick >= REVALIDATE_TICKS) {
                e.lastValidatedTick = now;
                if (!srcLevel.hasChunk(e.probePos.getX() >> 4, e.probePos.getZ() >> 4)
                        || !srcLevel.getBlockState(e.probePos).isAir()
                        || PortalShape.findEmptyPortalShape(srcLevel, e.probePos, Direction.Axis.X).isEmpty()) {
                    it.remove();
                    continue;
                }
            }

            boolean playerNear = false;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!p.level().dimension().equals(e.srcDim)) continue;
                if (p.position().distanceToSqr(
                        e.probePos.getX() + 0.5, e.probePos.getY() + 0.5, e.probePos.getZ() + 0.5)
                        <= PLAYER_NEAR_SQ) {
                    playerNear = true;
                    break;
                }
            }
            if (!playerNear) {
                if (now - e.lastNearTick > TTL_TICKS) it.remove();
                continue;
            }
            e.lastNearTick = now;

            // Keep the expected dest region loading/generating (ticket auto-expires).
            ServerLevel destLevel = server.getLevel(e.destDim);
            if (destLevel != null) {
                int radius = Math.min(PREWARM_RADIUS_CHUNKS,
                    SeamlessPortalsConfig.get().getPortalRenderDistance());
                destLevel.getChunkSource().addTicketWithRadius(
                    PortalEntityTracker.PORTAL_PREWARM_TICKET, e.destCenter, radius);
            }
        }
    }

    /**
     * Contribute the speculative dest chunks for {@code player} into the streaming needed-set
     * (called from PortalChunkTracker.updatePlayerPortalChunks, which streams + prunes them like
     * link-driven chunks), and tell the client to hold the region scope-live (mesh pre-compile).
     */
    public static void contribute(ServerPlayer player,
            Map<ResourceKey<Level>, java.util.Set<ChunkPos>> neededByDim, long serverTick) {
        if (entries.isEmpty() || !SeamlessPortalsConfig.get().isSpeculativePrewarm()) return;
        ResourceKey<Level> playerDim = player.level().dimension();
        for (Entry e : entries.values()) {
            if (!e.srcDim.equals(playerDim)) continue;
            if (player.position().distanceToSqr(
                    e.probePos.getX() + 0.5, e.probePos.getY() + 0.5, e.probePos.getZ() + 0.5)
                    > PLAYER_NEAR_SQ) continue;

            int radius = Math.min(PREWARM_RADIUS_CHUNKS,
                SeamlessPortalsConfig.get().getPortalRenderDistance());
            java.util.Set<ChunkPos> needed =
                neededByDim.computeIfAbsent(e.destDim, k -> new java.util.LinkedHashSet<>());
            int cx = e.destCenter.x(), cz = e.destCenter.z();
            needed.add(new ChunkPos(cx, cz));
            for (int r = 1; r <= radius; r++) {
                for (int d = -r; d <= r; d++) {
                    needed.add(new ChunkPos(cx + d, cz - r));
                    needed.add(new ChunkPos(cx + d, cz + r));
                }
                for (int d = -r + 1; d <= r - 1; d++) {
                    needed.add(new ChunkPos(cx - r, cz + d));
                    needed.add(new ChunkPos(cx + r, cz + d));
                }
            }

            // Scope-live marker to the client (throttled): keeps the region resident client-side
            // and lets the compile pump pre-build meshes around the expected dest.
            if (serverTick % 40 == 0) {
                BlockPos center = new BlockPos(
                    (cx << 4) + 8, e.probePos.getY(), (cz << 4) + 8);
                com.warwa.seamlessportals.network.PlatformHelper.getInstance().sendToClient(player,
                    new com.warwa.seamlessportals.network.ModPayloads.SpeculativePrewarmScopePayload(
                        e.destDim.identifier().toString(),
                        center.getX(), center.getY(), center.getZ()));
            }
        }
    }

    /** A real portal formed — drop any speculative entry for that frame (the link takes over). */
    public static void onPortalLit(Level level, BlockPos origin) {
        if (entries.isEmpty()) return;
        ResourceKey<Level> dim = level.dimension();
        entries.entrySet().removeIf(en -> en.getValue().srcDim.equals(dim)
            && en.getValue().probePos.distSqr(origin) < 24 * 24);
    }
}
