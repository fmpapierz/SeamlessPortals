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

    public void tick(MinecraftServer server) {
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

        if (!loggedChunkUpdate) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] updatePlayerPortalChunks: playerDim={}, nearbyLinks={}, loadDistance={}, cap={}, range={}",
                playerDim.identifier(), nearbyLinks.size(), loadDistance, cap, range
            );
            loggedChunkUpdate = true;
        }

        if (nearbyLinks.isEmpty()) return;

        Set<ChunkPos> neededChunks = new HashSet<>();

        for (PortalLink link : nearbyLinks) {
            PortalInfo destPortal = link.getDestination();
            ResourceKey<Level> destDim = destPortal.getDimension();
            Vec3 destCenter = destPortal.getCenter();

            // IP getDirectLoadingDistance(loadDistance, distanceToPortalBlocks):
            double distBlocks = player.position().distanceTo(link.getSource().getCenter());
            int target;
            if (distBlocks < 5.0) {
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

            // Collect chunks around the destination portal
            for (int dx = -renderDist; dx <= renderDist; dx++) {
                for (int dz = -renderDist; dz <= renderDist; dz++) {
                    neededChunks.add(new ChunkPos(centerChunkX + dx, centerChunkZ + dz));
                }
            }

            sendChunksToPlayer(player, destDim, neededChunks, server);
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
        int sentThisTick = 0;
        for (ChunkPos pos : chunks) {
            if (previouslySent.contains(pos)) continue;
            if (sentThisTick >= MAX_CHUNK_SENDS_PER_TICK) break;

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
