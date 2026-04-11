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

    public PortalLink createLink(PortalInfo source, PortalInfo destination) {
        PortalLink link = new PortalLink(source, destination);
        PortalLink reverseLink = new PortalLink(destination, source);

        String srcKey = posKey(source.getDimension(), source.getOrigin());
        String dstKey = posKey(destination.getDimension(), destination.getOrigin());

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

    private void findOrCreateDestinationPortal(PortalInfo source, ResourceKey<Level> destDim,
                                                ServerLevel destLevel, MinecraftServer server) {
        PortalTracker destTracker = getTracker(destDim);
        BlockPos expectedPos = computeExpectedDestination(source);

        // Try to find existing portal in destination dimension
        Optional<PortalInfo> existing = destTracker.findNearestPortal(expectedPos, 128, source.getType());
        if (existing.isPresent()) {
            createLink(source, existing.get());
            return;
        }

        // No portal found in destination - create a virtual destination for rendering
        // The actual position might not be exact but it lets us load chunks around it
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS DEBUG] No destination portal found, creating virtual link at {} in {}",
            expectedPos, destDim.identifier()
        );

        PortalInfo virtualDest = new PortalInfo(
            source.getType(), destDim, expectedPos,
            source.getAxis(), source.getWidth(), source.getHeight()
        );
        registerPortal(virtualDest);
        createLink(source, virtualDest);
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

    /**
     * Clear all portal data for a specific dimension.
     * Used when dimension changes to remove stale virtual portals.
     */
    public void clearDimension(ResourceKey<Level> dimension) {
        PortalTracker tracker = trackers.get(dimension);
        if (tracker != null) {
            // Remove links for all portals in this dimension
            for (PortalInfo portal : new ArrayList<>(tracker.getAllPortals())) {
                String key = posKey(dimension, portal.getOrigin());
                linksByPosition.remove(key);
                tracker.removePortal(portal);
            }
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS] Cleared portal data for dimension: {}", dimension.identifier());
        }
    }

    public void clear() {
        trackers.clear();
        linksByPosition.clear();
    }
}
