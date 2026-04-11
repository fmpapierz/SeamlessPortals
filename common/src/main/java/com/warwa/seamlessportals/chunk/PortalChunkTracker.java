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
    private final Map<UUID, Set<ChunkPos>> sentChunks = new HashMap<>();
    private static boolean loggedFirstSend = false;

    private int scanCooldown = 0;

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
    private void scanForPortalsNearPlayer(ServerPlayer player, MinecraftServer server) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos playerPos = player.blockPosition();
        int chunkRadius = 4; // Scan 4 chunks in each direction

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

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
                                    PortalDetector.onNetherPortalFormed(level, worldPos, server);
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

        int renderDist = SeamlessPortalsConfig.get().getPortalRenderDistance();
        double range = renderDist * 16.0;

        List<PortalLink> nearbyLinks = manager.getLinksInRange(playerDim, player.blockPosition(), range);

        if (!loggedChunkUpdate) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] updatePlayerPortalChunks: playerDim={}, nearbyLinks={}, renderDist={}, range={}",
                playerDim.identifier(), nearbyLinks.size(), renderDist, range
            );
            loggedChunkUpdate = true;
        }

        if (nearbyLinks.isEmpty()) return;

        Set<ChunkPos> neededChunks = new HashSet<>();

        for (PortalLink link : nearbyLinks) {
            PortalInfo destPortal = link.getDestination();
            ResourceKey<Level> destDim = destPortal.getDimension();
            Vec3 destCenter = destPortal.getCenter();

            int centerChunkX = (int)(destCenter.x) >> 4;
            int centerChunkZ = (int)(destCenter.z) >> 4;

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
        Set<ChunkPos> previouslySent = sentChunks.computeIfAbsent(playerId, k -> new HashSet<>());

        if (!loggedFirstSend) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] sendChunksToPlayer: dim={}, chunks to check={}, prevSent={}",
                dimId, chunks.size(), previouslySent.size()
            );
        }

        for (ChunkPos pos : chunks) {
            if (previouslySent.contains(pos)) continue;

            // Force-load the chunk in the destination dimension if not loaded
            // This is necessary because the nether may not have any loaded chunks
            // when the player hasn't visited it yet
            LevelChunk chunk;
            try {
                chunk = destLevel.getChunk(pos.x(), pos.z());
            } catch (Exception e) {
                continue; // Skip if chunk can't be loaded
            }
            if (chunk != null) {
                byte[] chunkData = serializeChunkSections(chunk);

                if (chunkData != null && chunkData.length > 0) {
                    if (!loggedFirstSend) {
                        SeamlessPortalsConstants.LOGGER.info(
                            "[SEAMLESS DEBUG] Sending remote chunk [{}, {}] from {} to player {} ({} bytes, {} sections)",
                            pos.x(), pos.z(), dimId, player.getName().getString(),
                            chunkData.length, chunk.getSectionsCount()
                        );
                        loggedFirstSend = true;
                    }

                    PlatformHelper.getInstance().sendToClient(player,
                        new ModPayloads.RemoteChunkDataPayload(dimId, pos.x(), pos.z(), chunkData));
                    previouslySent.add(pos);
                }
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
