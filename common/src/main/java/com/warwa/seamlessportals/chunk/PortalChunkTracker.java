package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.portal.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Server-side tracker that determines which chunks from the destination dimension
 * need to be sent to each player, and sends them via custom network packets.
 *
 * DEBUG: Logs every chunk serialization and send operation.
 */
public class PortalChunkTracker {
    // Per-player, per-dimension tracking of sent chunks.
    // Without dimension key, chunks sent from dimension A (e.g., nether chunks while
    // player was in overworld) block sending chunks from dimension B at the same
    // ChunkPos (e.g., overworld chunks when player enters nether).
    private final Map<UUID, Map<ResourceKey<Level>, Set<ChunkPos>>> sentChunks = new HashMap<>();
    private static boolean loggedFirstSend = false;

    private int scanCooldown = 0;

    /** The one live tracker (created by the loader entrypoint) — for the crossing hook. */
    private static volatile PortalChunkTracker ACTIVE;

    public PortalChunkTracker() {
        ACTIVE = this;
    }

    // ===== Crossing hand-off: stop the post-teleport re-send storm =====
    //
    // vanilla ServerPlayer.teleportTo restarts BOTH trackers from scratch: the
    // dest dim's ChunkMap re-sends the player's whole view distance (the client
    // already holds the near-field — this tracker streamed it via redirected
    // packets and the block mirror kept it live), and this tracker itself used
    // to cold-restart the OLD dim at 32 chunks/tick (the player never had a
    // sentChunks record for the dim they were standing in). Both re-sends were
    // decoded synchronously on the render thread → the per-crossing ~150-300ms
    // freeze ([SEAMLESS STUCK]: 18/21 stalls in chunk decode + light init).
    //
    // onPlayerCrossing (called BEFORE teleportTo, while player.level() is still
    // the old dim) does the hand-off both ways:
    //  1. ARMS one-shot suppression of vanilla's re-send for exactly the chunks
    //     this tracker already delivered for the NEW dim
    //     (ChunkMapResendSuppressMixin cancels markChunkPendingToSend for them);
    //  2. SEEDS the OLD dim's sent-record with the player's vanilla view around
    //     their old position (the client provably has those chunks, and the
    //     demoted level preserves them) so streaming resumes incrementally
    //     instead of restarting cold.

    private static final class ResendSuppression {
        final ResourceKey<Level> dim;
        final Set<Long> chunks;
        final long armedNanos;
        ResendSuppression(ResourceKey<Level> dim, Set<Long> chunks, long armedNanos) {
            this.dim = dim;
            this.chunks = chunks;
            this.armedNanos = armedNanos;
        }
    }

    /** Per-player armed suppression; server thread only; entries expire after 60s. */
    private static final Map<UUID, ResendSuppression> VANILLA_RESEND_SUPPRESS = new HashMap<>();
    private static final long SUPPRESS_TTL_NANOS = 60_000_000_000L;

    /** Called by SeamlessServerTeleport BEFORE the vanilla teleport. */
    public static void onPlayerCrossing(ServerPlayer player,
            ResourceKey<Level> oldDim, ResourceKey<Level> newDim) {
        PortalChunkTracker tracker = ACTIVE;
        if (tracker == null) return;
        UUID uuid = player.getUUID();
        Map<ResourceKey<Level>, Set<ChunkPos>> perDim =
            tracker.sentChunks.computeIfAbsent(uuid, k -> new HashMap<>());

        // 1. Arm suppression for the redirected set of the dim being entered.
        Set<ChunkPos> redirected = perDim.get(newDim);
        if (redirected != null && !redirected.isEmpty()) {
            Set<Long> packed = new java.util.HashSet<>(redirected.size() * 2);
            for (ChunkPos p : redirected) packed.add(p.pack());
            VANILLA_RESEND_SUPPRESS.put(uuid,
                new ResendSuppression(newDim, packed, System.nanoTime()));
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS CROSSING] Armed vanilla-resend suppression: {} chunks of {} already client-held",
                packed.size(), newDim.identifier());
        }
        // Vanilla owns the entered dim now — drop the redirected record
        // (pruneAndSend's retainAll would do this next tick anyway).
        perDim.remove(newDim);

        // 2. Seed the OLD dim's record with the player's vanilla view (Chebyshev
        // square, one ring of safety margin) around their pre-teleport position:
        // the client had all of it loaded, and demoteFromMain preserves the
        // chunks. Without this, streaming the old dim (now visible back through
        // the portal) restarted COLD at the 32/tick burst rate.
        MinecraftServer server = player.level().getServer();
        int viewDist = Math.min(player.requestedViewDistance(),
            server != null ? server.getPlayerList().getViewDistance() : 10);
        int seedRadius = Math.max(2, viewDist - 1);
        net.minecraft.world.level.ChunkPos center = player.chunkPosition();
        Set<ChunkPos> seeded = perDim.computeIfAbsent(oldDim, k -> new java.util.HashSet<>());
        int added = 0;
        for (int dx = -seedRadius; dx <= seedRadius; dx++) {
            for (int dz = -seedRadius; dz <= seedRadius; dz++) {
                if (seeded.add(new ChunkPos(center.x() + dx, center.z() + dz))) added++;
            }
        }
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS CROSSING] Seeded {} client-held chunks of {} (radius {}) — no cold restart",
            added, oldDim.identifier(), seedRadius);
    }

    /**
     * One-shot: {@code true} ⇒ the client already holds this chunk (we streamed
     * it) — the caller (ChunkMapResendSuppressMixin) cancels vanilla's re-send.
     * Consuming removes the entry so any LATER legitimate send (view re-enter
     * after a real client-side forget) passes through untouched.
     */
    public static boolean consumeVanillaResendSuppression(ServerPlayer player, ChunkPos pos) {
        ResendSuppression r = VANILLA_RESEND_SUPPRESS.get(player.getUUID());
        if (r == null) return false;
        if (System.nanoTime() - r.armedNanos > SUPPRESS_TTL_NANOS) {
            VANILLA_RESEND_SUPPRESS.remove(player.getUUID());
            return false;
        }
        if (!player.level().dimension().equals(r.dim)) return false;
        boolean suppressed = r.chunks.remove(pos.pack());
        if (suppressed && r.chunks.isEmpty()) {
            VANILLA_RESEND_SUPPRESS.remove(player.getUUID());
        }
        return suppressed;
    }

    /**
     * Phase 4a (IP "live window" residency): keep each player's nearby
     * portal-destination chunks LOADED on the server so the dest dimension is
     * dense + resident — sendable to the client, and already loaded when the
     * player crosses (→ no chunk resend / reprocess).
     *
     * <p>Flags {@code 0b0010 = LOADING ONLY} — NOT {@code SIMULATION} (4) and NOT
     * {@code KEEP_DIMENSION_ACTIVE} (8). The wide-radius residency must not make
     * all ~289 chunks block-tick: giving them {@code SIMULATION} burst-ticked the
     * whole nether region (lava/fire/fluid) at once and overloaded the server
     * ("Can't keep up! Running 4s behind"). Liveness through the portal is
     * provided client-side by {@code PortalWorldManager.tickRemoteWorlds}
     * (Phase 1), and authoritative near-portal simulation (cross-dim fire) by
     * {@code PortalEntityTracker.MIRROR_VIEW_TICKET} (radius 3, SIMULATION) — so
     * this layer only needs the chunks resident, not ticking. ({@code
     * KEEP_DIMENSION_ACTIVE} is likewise avoided — the shipped PORTAL ticket,
     * flags 15, floods the nether with a piglin every tick.) Timeout 200t,
     * re-added every tick by {@link #updatePlayerPortalChunks} so it auto-expires
     * ~10 s after the player leaves the portal.
     */
    private static final net.minecraft.server.level.TicketType SEAMLESS_CHUNK_TICKET =
        com.warwa.seamlessportals.mixin.TicketTypeInvoker
            .seamlessportals$invokeRegister("seamlessportals_chunk_residency", 200L, 0b0010);

    /** One-shot log confirming Phase-4a residency tickets are being applied. */
    private static boolean loggedResidency = false;

    /**
     * Max NEW chunks shipped to a player per tick (per dest dim). Bounds the
     * client's per-frame chunk-apply + mesh-compile load so the async residency
     * stream-in can't burst into a render-thread freeze.
     */
    private static final int MAX_CHUNK_SENDS_PER_TICK = 12;

    /**
     * Elevated per-tick send cap while a dim's sent set is still cold (<100 chunks) — a
     * freshly-lit portal. With the ring-ordered needed set this fills the visible near-field
     * in fractions of a second; steady-state streaming then drops back to the normal cap.
     */
    private static final int COLD_START_CHUNK_SENDS_PER_TICK = 32;

    /**
     * Cap on how many nearby portals a single player feeds per tick — the N nearest.
     * Matches the render path's {@code StencilPortalRenderer.MAX_PORTALS_RENDERED}: a
     * player can only see through so many at once, so feeding (and re-adding a
     * residency ticket for) EVERY lit portal within range every tick is wasted
     * per-tick work that grows with how many portals you have lit nearby — a
     * progressive server-tick cost behind the "laggy after lighting multiple portals".
     */
    private static final int MAX_ACTIVE_LINKS = 4;

    public void tick(MinecraftServer server) {
        // Formations queued at ignition (PortalShapeFormMixin) — run the heavy dest-portal
        // creation HERE, one tick after the light, so the ignition tick's block broadcast
        // (fire → portal blocks) ships without delay (no visible flame in the frame).
        com.warwa.seamlessportals.portal.PortalDetector.drainPendingFormations(server);

        // Speculative pre-warm upkeep: revalidate/expire unlit-frame entries + keep their
        // expected dest regions loading (prewarm tickets). Config-gated internally.
        com.warwa.seamlessportals.chunk.SpeculativePrewarm.tick(server);

        // Periodically scan for portals near players on the server
        scanCooldown--;
        if (scanCooldown <= 0) {
            scanCooldown = 40; // Every 2 seconds
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                scanForPortalsNearPlayer(player, server);
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            updatePlayerPortalChunks(player, server);
        }
    }

    /**
     * Scan loaded chunks near the player for portal blocks.
     * Uses chunk sections to efficiently find portal blocks.
     */
    /**
     * Scan for portal blocks near the player and register any unlinked portals.
     *
     * IMPORTANT: Only calls onNetherPortalFormed ONCE per unique portal origin.
     * IP doesn't scan blocks at all — it intercepts portal creation events.
     * We scan because we need to find pre-existing portals, but we must avoid:
     * 1. Calling onNetherPortalFormed for every block in a portal (6+ calls for 2x3)
     * 2. Re-processing portals that are already linked
     * 3. Creating spurious links from blocks at chunk boundaries
     */
    private void scanForPortalsNearPlayer(ServerPlayer player, MinecraftServer server) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos playerPos = player.blockPosition();
        int chunkRadius = 4;

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        // Track origins we've already processed this scan to avoid duplicates
        Set<BlockPos> processedOrigins = new HashSet<>();

        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                int chunkX = playerChunkX + cx;
                int chunkZ = playerChunkZ + cz;

                if (!level.hasChunk(chunkX, chunkZ)) continue;

                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
                    var section = chunk.getSection(sIdx);
                    if (section == null || section.hasOnlyAir()) continue;

                    int sectionY = chunk.getSectionYFromSectionIndex(sIdx);
                    for (int lx = 0; lx < 16; lx++) {
                        for (int ly = 0; ly < 16; ly++) {
                            for (int lz = 0; lz < 16; lz++) {
                                if (section.getBlockState(lx, ly, lz).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) {
                                    BlockPos worldPos = new BlockPos(
                                        chunkX * 16 + lx,
                                        sectionY * 16 + ly,
                                        chunkZ * 16 + lz
                                    );
                                    // Find origin FIRST, skip if already processed
                                    net.minecraft.world.level.block.state.BlockState state =
                                        level.getBlockState(worldPos);
                                    net.minecraft.core.Direction.Axis axis =
                                        state.getValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS);
                                    BlockPos origin = PortalDetector.findPortalOriginPublic(
                                        level, worldPos, axis);

                                    if (!processedOrigins.contains(origin)) {
                                        processedOrigins.add(origin);
                                        PortalDetector.onNetherPortalFormed(level, origin, server);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean loggedChunkUpdate = false;

    private void updatePlayerPortalChunks(ServerPlayer player, MinecraftServer server) {
        ResourceKey<Level> playerDim = player.level().dimension();
        PortalManager manager = PortalManager.getServerInstance();

        // EXACT IP loading model (ChunkVisibility.getDirectLoadingDistance +
        // getCappedLoadingDistance): graduate the loaded depth by the player's
        // BLOCK distance to the portal (full load distance < 5 blocks, 2/3 < 15,
        // else 1/3), then CAP it by the config (IP's indirectLoadingRadiusCap,
        // default 8, clamp 1..32). With the default cap the dest loads 8 chunks
        // deep — identical to IP; the far ring reloads on crossing, exactly as IP
        // does. Raise the config toward your render distance for a more seamless
        // (heavier) crossing.
        int loadDistance = server.getPlayerList().getViewDistance(); // IP McHelper.getPlayerLoadDistance
        int cap = SeamlessPortalsConfig.get().getPortalRenderDistance();
        double range = Math.max(cap, loadDistance) * 16.0;

        List<PortalLink> nearbyLinks = manager.getLinksInRange(playerDim, player.blockPosition(), range);

        // Cap to the N nearest portals (the only ones the player can actually look
        // through — matches MAX_PORTALS_RENDERED). Without this, standing among many
        // lit portals re-adds a residency ticket + collects chunks for EVERY one,
        // every tick — per-tick work that grows with your portal count.
        if (nearbyLinks.size() > MAX_ACTIVE_LINKS) {
            Vec3 pPos = player.position();
            List<PortalLink> sorted = new ArrayList<>(nearbyLinks);
            sorted.sort(java.util.Comparator.comparingDouble(
                l -> l.getSource().getCenter().distanceToSqr(pPos)));
            nearbyLinks = sorted.subList(0, MAX_ACTIVE_LINKS);
        }

        if (!loggedChunkUpdate) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] updatePlayerPortalChunks: playerDim={}, nearbyLinks={}, loadDistance={}, cap={}, range={}",
                playerDim.identifier(), nearbyLinks.size(), loadDistance, cap, range
            );
            loggedChunkUpdate = true;
        }

        // Per-dim needed-chunk sets (was: ONE shared set sent once PER link, which
        // cross-contaminated dims and re-scanned prior links' chunks O(N^2)).
        Map<ResourceKey<Level>, Set<ChunkPos>> neededByDim = new HashMap<>();

        for (PortalLink link : nearbyLinks) {
            PortalInfo destPortal = link.getDestination();
            ResourceKey<Level> destDim = destPortal.getDimension();
            Vec3 destCenter = destPortal.getCenter();

            // IP getDirectLoadingDistance(loadDistance, distanceToPortalBlocks) — only when "auto".
            // Fixed mode (a numeric portalRenderDistance) overrides the graduation: load the full
            // configured depth regardless of distance, so the portal window shows full render dist.
            double distBlocks = player.position().distanceTo(link.getSource().getCenter());
            int target;
            if (!SeamlessPortalsConfig.get().isAutoRenderDistance()) {
                target = cap;
            } else if (distBlocks < 5.0) {
                target = loadDistance;
            } else if (distBlocks < 15.0) {
                target = (loadDistance * 2) / 3;
            } else {
                target = loadDistance / 3;
            }
            // IP getCappedLoadingDistance: cap by indirectLoadingRadiusCap (config).
            int renderDist = Math.max(1, Math.min(target, cap));

            int centerChunkX = (int)(destCenter.x) >> 4;
            int centerChunkZ = (int)(destCenter.z) >> 4;

            // Phase 4a: hold the dest region loaded + simulated so it is dense
            // and resident on the server — sendable now, and already loaded when
            // the player crosses (no resend / reprocess). Re-added every tick;
            // expires ~10 s after the player leaves the portal.
            ServerLevel destResLevel = server.getLevel(destDim);
            if (destResLevel != null) {
                destResLevel.getChunkSource().addTicketWithRadius(
                    SEAMLESS_CHUNK_TICKET,
                    new ChunkPos(centerChunkX, centerChunkZ), renderDist);
                if (!loggedResidency) {
                    loggedResidency = true;
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS RESIDENCY] Holding {} chunks loaded around dest portal "
                            + "[{},{}] in {} (radius {}); dest loadedChunks≈{}",
                        (2 * renderDist + 1) * (2 * renderDist + 1),
                        centerChunkX, centerChunkZ, destDim.identifier(), renderDist,
                        destResLevel.getChunkSource().getLoadedChunksCount());
                }
            }

            // Collect chunks around the destination portal (per dim) in RING order — nearest
            // ring first, into a LinkedHashSet so sendChunksToPlayer's iteration streams the
            // chunks the portal window actually shows FIRST. (Was a plain HashSet: iteration
            // order effectively random, so the visible near-field could wait behind far chunks
            // for minutes — the cold-start blank window.)
            Set<ChunkPos> needed = neededByDim.computeIfAbsent(destDim, k -> new LinkedHashSet<>());
            needed.add(new ChunkPos(centerChunkX, centerChunkZ));
            for (int r = 1; r <= renderDist; r++) {
                for (int d = -r; d <= r; d++) {
                    needed.add(new ChunkPos(centerChunkX + d, centerChunkZ - r));
                    needed.add(new ChunkPos(centerChunkX + d, centerChunkZ + r));
                }
                for (int d = -r + 1; d <= r - 1; d++) {
                    needed.add(new ChunkPos(centerChunkX - r, centerChunkZ + d));
                    needed.add(new ChunkPos(centerChunkX + r, centerChunkZ + d));
                }
            }
        }

        // Speculative pre-warm: merge unlit-frame expected-dest chunks into the same streaming
        // set (ring-ordered, small radius) + push the scope-live marker to the client so it
        // pre-builds meshes. Config-gated internally.
        com.warwa.seamlessportals.chunk.SpeculativePrewarm.contribute(
            player, neededByDim, server.getTickCount());

        // Prune the player's sent-chunk record to the live working set, then send.
        // Runs even when neededByDim is EMPTY (player walked away from every portal),
        // so the record can't grow unbounded across the session and chunks re-send
        // when the player returns (after the client evicted them — IP roam model).
        pruneAndSend(player, neededByDim, server);
    }

    /**
     * Drop a player's sent-chunk record for any dim they are no longer near (left ALL
     * its portals), then ship the not-yet-sent needed chunks for each still-near dim.
     *
     * <p>We DROP the whole record for a departed dim (bounds {@link #sentChunks} across a
     * session and lets the dest re-send when the player roams back after the client
     * released it) — but we DO NOT prune a still-near dim's record down to the current
     * tick's graduated {@code needed} ring. The needed radius graduates with the player's
     * distance to the portal (full &lt;5 blocks, 2/3 &lt;15, else 1/3), so retaining only the
     * current ring would forget + RE-SEND the outer ring every time the player moves
     * across a band — a redirected-chunk re-send storm (~20k packets) and the persistent
     * post-teleport stutter. Letting the record accumulate the union of what was sent
     * while near keeps each chunk sent exactly once per visit.
     */
    private void pruneAndSend(ServerPlayer player,
                              Map<ResourceKey<Level>, Set<ChunkPos>> neededByDim,
                              MinecraftServer server) {
        Map<ResourceKey<Level>, Set<ChunkPos>> playerSent = sentChunks.get(player.getUUID());
        if (playerSent != null) {
            // Drop records only for dims the player is no longer near (not in neededByDim).
            playerSent.keySet().retainAll(neededByDim.keySet());
        }

        for (Map.Entry<ResourceKey<Level>, Set<ChunkPos>> e : neededByDim.entrySet()) {
            sendChunksToPlayer(player, e.getKey(), e.getValue(), server);
        }
    }

    private void sendChunksToPlayer(ServerPlayer player, ResourceKey<Level> dimension,
                                     Set<ChunkPos> chunks, MinecraftServer server) {
        ServerLevel destLevel = server.getLevel(dimension);
        if (destLevel == null) return;

        String dimId = dimension.identifier().toString();
        UUID playerId = player.getUUID();
        Map<ResourceKey<Level>, Set<ChunkPos>> playerSent =
            sentChunks.computeIfAbsent(playerId, k -> new HashMap<>());
        Set<ChunkPos> previouslySent = playerSent.computeIfAbsent(dimension, k -> new HashSet<>());

        if (!loggedFirstSend) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] sendChunksToPlayer: dim={}, chunks to check={}, prevSent={}",
                dimId, chunks.size(), previouslySent.size()
            );
        }

        // Phase 4a: cap how many NEW chunks we ship per tick. With the async
        // residency ticket, many chunks finish loading in the same tick; sending
        // them all at once made the CLIENT receive a 100-chunk burst and try to
        // mesh-compile it in one frame ("scheduled=100" → render-thread storm /
        // freeze). Sending a small batch/tick streams the dest in smoothly; the
        // rest are retried next tick (not marked sent).
        //
        // COLD-START BURST (instant portal view): while this dim's sent set is still
        // small (a freshly-lit portal), ship 32/tick instead of 12 — combined with the
        // ring-ordered needed set, the visible near-field (~radius 4 = 81 chunks) lands
        // on the client within ~2-3 ticks. The client's mesh compiles are budget-gated
        // per frame (VisibleSectionDiscovery), so the larger receive burst queues meshes
        // instead of stalling the render thread. Back to 12/tick once warmed.
        int cap = previouslySent.size() < 100
            ? COLD_START_CHUNK_SENDS_PER_TICK
            : MAX_CHUNK_SENDS_PER_TICK;
        int sentThisTick = 0;
        for (ChunkPos pos : chunks) {
            if (previouslySent.contains(pos)) continue;
            if (sentThisTick >= cap) break;

            // Phase 4a: send only chunks ALREADY loaded. The Phase-4a residency
            // ticket loads the dest region ASYNCHRONOUSLY over ticks; getChunkNow
            // is non-blocking, so an unloaded chunk is skipped (NOT marked sent,
            // so retried next tick as the ticket fills it in) instead of being
            // force-loaded. The old synchronous getChunk(...) force-loaded all
            // ~289 nether chunks in one tick and stalled the server
            // ("Can't keep up! Running 3.6s behind"). Now the dest streams in
            // smoothly with no server hitch.
            LevelChunk chunk = destLevel.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk == null) continue;
            {
                // Phase 4c: ship the REAL vanilla chunk packet (chunk + all light
                // sections, null/null filters = full), wrapped in the redirect
                // payload. The client runs its own handler against the dest
                // ClientLevel under a world-switch, so the engine's native
                // chunk-load tracking drives the dest occlusion graph — no custom
                // snapshot serialize/store/drain, no O(all-sections) manual scan.
                net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket vanillaPkt =
                    new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                        chunk, destLevel.getLightEngine(), null, null);

                if (!loggedFirstSend) {
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS 4C] Sending REDIRECTED vanilla chunk [{}, {}] from {} to player {} ({} sections)",
                        pos.x(), pos.z(), dimId, player.getName().getString(), chunk.getSectionsCount()
                    );
                    loggedFirstSend = true;
                }

                PlatformHelper.getInstance().sendToClient(player,
                    new ModPayloads.RedirectedChunkPayload(dimId, vanillaPkt));
                previouslySent.add(pos);
                sentThisTick++;
            }
        }

        if (!loggedFirstSend) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] sendChunksToPlayer finished: sent {} chunks total to {}",
                previouslySent.size(), player.getName().getString()
            );
        }
    }

    /**
     * Serialize chunk sections + light data using vanilla's format.
     *
     * Format:
     *   sectionCount (varint)
     *   For each section:
     *     section data (blocks + biomes via LevelChunkSection.write)
     *   For each section (sky light):
     *     hasData (boolean) + if true: 2048 bytes (DataLayer)
     *   For each section (block light):
     *     hasData (boolean) + if true: 2048 bytes (DataLayer)
     *
     * Following IP's architecture: chunks must include FULL light data so the
     * destination dimension renders with correct lighting. Without light data,
     * overworld chunks in the nether portal view appear pitch black (sky light = 0).
     */
    private byte[] serializeChunkSections(LevelChunk chunk) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                LevelChunkSection[] sections = chunk.getSections();
                buf.writeVarInt(sections.length);

                // Write block states + biomes per section
                for (LevelChunkSection section : sections) {
                    section.write(buf);
                }

                // Write sky light data per section
                // Server's LevelLightEngine has the authoritative light data
                net.minecraft.world.level.lighting.LevelLightEngine lightEngine =
                    chunk.getLevel().getLightEngine();
                net.minecraft.world.level.ChunkPos chunkPos = chunk.getPos();
                int minSectionY = chunk.getLevel().getMinSectionY();

                for (int i = 0; i < sections.length; i++) {
                    int sectionY = minSectionY + i;
                    net.minecraft.core.SectionPos sectionPos =
                        net.minecraft.core.SectionPos.of(chunkPos.x(), sectionY, chunkPos.z());

                    // Sky light
                    net.minecraft.world.level.chunk.DataLayer skyData =
                        lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.SKY)
                            .getDataLayerData(sectionPos);
                    if (skyData != null && skyData.getData() != null) {
                        buf.writeBoolean(true);
                        buf.writeBytes(skyData.getData());
                    } else {
                        buf.writeBoolean(false);
                    }
                }

                for (int i = 0; i < sections.length; i++) {
                    int sectionY = minSectionY + i;
                    net.minecraft.core.SectionPos sectionPos =
                        net.minecraft.core.SectionPos.of(chunkPos.x(), sectionY, chunkPos.z());

                    // Block light
                    net.minecraft.world.level.chunk.DataLayer blockData =
                        lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK)
                            .getDataLayerData(sectionPos);
                    if (blockData != null && blockData.getData() != null) {
                        buf.writeBoolean(true);
                        buf.writeBytes(blockData.getData());
                    } else {
                        buf.writeBoolean(false);
                    }
                }

                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                return data;
            } finally {
                buf.release();
            }
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.error("[SEAMLESS DEBUG] Failed to serialize chunk sections", e);
            return null;
        }
    }

    public void onPlayerDisconnect(UUID playerId) {
        sentChunks.remove(playerId);
    }

    public void clear() {
        sentChunks.clear();
        loggedFirstSend = false;
    }
}
