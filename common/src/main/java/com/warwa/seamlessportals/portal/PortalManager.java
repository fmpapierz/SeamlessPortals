package com.warwa.seamlessportals.portal;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages portal registration and linking.
 * Links are stored by POSITION (dimension + origin), not by UUID,
 * so re-detection of the same portal doesn't break links.
 */
public class PortalManager {
    private static PortalManager serverInstance;
    private static PortalManager clientInstance;

    private final Map<ResourceKey<Level>, PortalTracker> trackers = new ConcurrentHashMap<>();

    // Links keyed by "dimension:x,y,z" string for position-based lookup
    private final Map<String, PortalLink> linksByPosition = new ConcurrentHashMap<>();
    private final boolean isClient;

    private PortalManager(boolean isClient) {
        this.isClient = isClient;
    }

    public static PortalManager getServerInstance() {
        if (serverInstance == null) serverInstance = new PortalManager(false);
        return serverInstance;
    }

    public static PortalManager getClientInstance() {
        if (clientInstance == null) clientInstance = new PortalManager(true);
        return clientInstance;
    }

    public static void resetServer() { serverInstance = null; }
    public static void resetClient() { clientInstance = null; }

    public PortalTracker getTracker(ResourceKey<Level> dimension) {
        return trackers.computeIfAbsent(dimension, k -> new PortalTracker(dimension));
    }

    private String posKey(ResourceKey<Level> dim, BlockPos pos) {
        return dim.identifier() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public void registerPortal(PortalInfo portal) {
        getTracker(portal.getDimension()).addPortal(portal);
    }

    public void unregisterPortal(PortalInfo portal) {
        getTracker(portal.getDimension()).removePortal(portal);
        String key = posKey(portal.getDimension(), portal.getOrigin());
        linksByPosition.remove(key);
    }

    /**
     * Create a bidirectional link. Does NOT overwrite existing links —
     * each portal position links to exactly one destination.
     * IP: each portal entity has one fixed destination.
     */
    public PortalLink createLink(PortalInfo source, PortalInfo destination) {
        String srcKey = posKey(source.getDimension(), source.getOrigin());
        String dstKey = posKey(destination.getDimension(), destination.getOrigin());

        // Don't overwrite existing links — IP: each portal has one fixed destination.
        // If EITHER portal already has a link, don't create a new one.
        // A second overworld portal linking to the same nether portal would hijack it.
        PortalLink existingSrc = linksByPosition.get(srcKey);
        PortalLink existingDst = linksByPosition.get(dstKey);
        if (existingSrc != null) return existingSrc;
        if (existingDst != null) return existingDst;

        PortalLink link = new PortalLink(source, destination);
        PortalLink reverseLink = new PortalLink(destination, source);

        linksByPosition.put(srcKey, link);
        linksByPosition.put(dstKey, reverseLink);

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS DEBUG] Created link: {} in {} <-> {} in {}",
            source.getOrigin(), source.getDimension().identifier(),
            destination.getOrigin(), destination.getDimension().identifier()
        );
        return link;
    }

    public Optional<PortalLink> getLinkForPortal(UUID portalId) {
        // Look up by finding the portal's position first
        for (PortalTracker tracker : trackers.values()) {
            for (PortalInfo portal : tracker.getAllPortals()) {
                if (portal.getPortalId().equals(portalId)) {
                    String key = posKey(portal.getDimension(), portal.getOrigin());
                    PortalLink link = linksByPosition.get(key);
                    if (link != null) return Optional.of(link);
                }
            }
        }
        return Optional.empty();
    }

    public Optional<PortalLink> getLinkAt(ResourceKey<Level> dimension, BlockPos pos) {
        return getTracker(dimension).getPortalAt(pos).flatMap(portal -> {
            String key = posKey(dimension, portal.getOrigin());
            return Optional.ofNullable(linksByPosition.get(key));
        });
    }

    public List<PortalLink> getLinksInRange(ResourceKey<Level> dimension, BlockPos center, double range) {
        List<PortalLink> result = new ArrayList<>();
        for (PortalInfo portal : getTracker(dimension).getPortalsInRange(center, range)) {
            String key = posKey(dimension, portal.getOrigin());
            PortalLink link = linksByPosition.get(key);
            if (link != null) {
                result.add(link);
            }
        }
        return result;
    }

    public void onPortalFormed(PortalType type, ResourceKey<Level> dimension,
                                BlockPos origin, Direction.Axis axis,
                                int width, int height, MinecraftServer server) {
        if (!SeamlessPortalsConfig.isImmersive(type)) return;

        // Check if already linked at this position
        String key = posKey(dimension, origin);
        if (linksByPosition.containsKey(key)) return;

        PortalInfo portal = new PortalInfo(type, dimension, origin, axis, width, height);
        registerPortal(portal);

        ResourceKey<Level> destDim = type.getDestinationFor(dimension);
        if (destDim != null && server != null) {
            ServerLevel destLevel = server.getLevel(destDim);
            if (destLevel != null) {
                findOrCreateDestinationPortal(portal, destDim, destLevel, server);
            }
        }
    }

    /**
     * Find the destination portal and link to it.
     * Following IP: both portals must EXIST. No virtual placeholders.
     *
     * IP hooks PortalForcer.createPortal() so the dest portal exists at link time.
     * We do the equivalent: force-load the expected destination chunk on the server
     * and scan it for portal blocks. The server CAN load any chunk in any dimension.
     */
    private void findOrCreateDestinationPortal(PortalInfo source, ResourceKey<Level> destDim,
                                                ServerLevel destLevel, MinecraftServer server) {
        PortalTracker destTracker = getTracker(destDim);
        BlockPos expectedPos = computeExpectedDestination(source);

        // First check if already registered
        Optional<PortalInfo> existing = destTracker.findNearestPortal(expectedPos, 128, source.getType());
        if (existing.isPresent()) {
            createLink(source, existing.get());
            return;
        }

        // Not registered yet — force-load the destination chunk and scan for portal blocks.
        // This is what IP effectively does: the server always has access to dest dimension chunks.
        int chunkX = expectedPos.getX() >> 4;
        int chunkZ = expectedPos.getZ() >> 4;

        // Scan a small radius around the expected position
        for (int cx = chunkX - 8; cx <= chunkX + 8; cx++) {
            for (int cz = chunkZ - 8; cz <= chunkZ + 8; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk;
                try {
                    chunk = destLevel.getChunk(cx, cz);
                } catch (Exception e) {
                    continue;
                }
                if (chunk == null) continue;

                for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
                    var section = chunk.getSection(sIdx);
                    if (section == null || section.hasOnlyAir()) continue;
                    int sectionY = chunk.getSectionYFromSectionIndex(sIdx);
                    for (int lx = 0; lx < 16; lx++) {
                        for (int ly = 0; ly < 16; ly++) {
                            for (int lz = 0; lz < 16; lz++) {
                                if (section.getBlockState(lx, ly, lz).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) {
                                    BlockPos worldPos = new BlockPos(cx * 16 + lx, sectionY * 16 + ly, cz * 16 + lz);
                                    // Register this portal via PortalDetector (deduplicates internally)
                                    PortalDetector.onNetherPortalFormed(destLevel, worldPos, server);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Try again after scanning
        existing = destTracker.findNearestPortal(expectedPos, 128, source.getType());
        if (existing.isPresent()) {
            createLink(source, existing.get());
            return;
        }

        // Destination doesn't exist yet. Vanilla creates it when player first teleports.
        // PortalForcerMixin will fire at that point and create the REAL link + sync to client.
        // No expected/placeholder portals — they cause wrong positions and broken rendering.
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS] No destination portal near {} in {} — will link when PortalForcer creates it",
            expectedPos, destDim.identifier()
        );
    }

    private BlockPos computeExpectedDestination(PortalInfo source) {
        double scale = source.getType().getCoordinateScale();
        BlockPos origin = source.getOrigin();

        if (source.getDimension() == Level.OVERWORLD && source.getType() == PortalType.NETHER) {
            return new BlockPos((int)(origin.getX() / scale), origin.getY(), (int)(origin.getZ() / scale));
        } else if (source.getDimension() == Level.NETHER && source.getType() == PortalType.NETHER) {
            return new BlockPos((int)(origin.getX() * scale), origin.getY(), (int)(origin.getZ() * scale));
        }
        return origin;
    }

    public void clear() {
        trackers.clear();
        linksByPosition.clear();
    }
}
