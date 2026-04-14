package com.warwa.seamlessportals.portal;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.BlockUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalForcer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages portal registration and linking.
 *
 * IP principle applied here:
 * - the server is the authority on links
 * - clients never invent destinations
 * - if the reverse portal does not exist yet, we render against the expected
 *   transform immediately, then let the first REAL vanilla portal creation
 *   correct the link later
 */
public class PortalManager {
    private static PortalManager serverInstance;
    private static PortalManager clientInstance;

    private final Map<ResourceKey<Level>, PortalTracker> trackers = new ConcurrentHashMap<>();
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

    public static void resetServer() {
        serverInstance = null;
    }

    public static void resetClient() {
        clientInstance = null;
    }

    public PortalTracker getTracker(ResourceKey<Level> dimension) {
        return trackers.computeIfAbsent(dimension, ignored -> new PortalTracker(dimension));
    }

    private String posKey(ResourceKey<Level> dim, BlockPos pos) {
        return dim.identifier() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public void registerPortal(PortalInfo portal) {
        getTracker(portal.getDimension()).addPortal(portal);
    }

    public void unregisterPortal(PortalInfo portal) {
        getTracker(portal.getDimension()).removePortal(portal);
        linksByPosition.remove(posKey(portal.getDimension(), portal.getOrigin()));
    }

    public PortalLink createLink(PortalInfo source, PortalInfo destination) {
        PortalLink forward = new PortalLink(source, destination);
        PortalLink reverse = new PortalLink(destination, source);

        linksByPosition.put(posKey(source.getDimension(), source.getOrigin()), forward);
        linksByPosition.put(posKey(destination.getDimension(), destination.getOrigin()), reverse);

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS DEBUG] Created link: {} in {} <-> {} in {}",
            source.getOrigin(), source.getDimension().identifier(),
            destination.getOrigin(), destination.getDimension().identifier()
        );
        return forward;
    }

    public Optional<PortalLink> getLinkForPortal(UUID portalId) {
        for (PortalTracker tracker : trackers.values()) {
            for (PortalInfo portal : tracker.getAllPortals()) {
                if (portal.getPortalId().equals(portalId)) {
                    return Optional.ofNullable(
                        linksByPosition.get(posKey(portal.getDimension(), portal.getOrigin()))
                    );
                }
            }
        }
        return Optional.empty();
    }

    public Optional<PortalLink> getLinkAt(ResourceKey<Level> dimension, BlockPos pos) {
        return getTracker(dimension).getPortalAt(pos).flatMap(portal ->
            Optional.ofNullable(linksByPosition.get(posKey(dimension, portal.getOrigin())))
        );
    }

    public List<PortalLink> getLinksInRange(ResourceKey<Level> dimension, BlockPos center, double range) {
        List<PortalLink> result = new ArrayList<>();
        for (PortalInfo portal : getTracker(dimension).getPortalsInRange(center, range)) {
            PortalLink link = linksByPosition.get(posKey(dimension, portal.getOrigin()));
            if (link != null) {
                result.add(link);
            }
        }
        return result;
    }

    public void onPortalFormed(
        PortalType type,
        ResourceKey<Level> dimension,
        BlockPos origin,
        Direction.Axis axis,
        int width,
        int height,
        MinecraftServer server
    ) {
        if (!SeamlessPortalsConfig.isImmersive(type)) return;

        String key = posKey(dimension, origin);
        PortalLink existing = linksByPosition.get(key);
        if (existing != null) {
            if (server != null) {
                sendLinkToClients(existing.getSource(), existing.getDestination(), server);
            }
            return;
        }

        PortalInfo portal = new PortalInfo(type, dimension, origin, axis, width, height);
        registerPortal(portal);

        ResourceKey<Level> destDim = type.getDestinationFor(dimension);
        if (destDim == null || server == null) {
            return;
        }

        ServerLevel destLevel = server.getLevel(destDim);
        if (destLevel != null) {
            findOrCreateDestinationPortal(portal, destDim, destLevel, server);
        }
    }

    /**
     * IP-style server authority:
     * 1. use a real destination portal if vanilla already has one
     * 2. otherwise publish a temporary mathematical link immediately
     * 3. let the first real vanilla portal creation replace the temporary link
     */
    private void findOrCreateDestinationPortal(
        PortalInfo source,
        ResourceKey<Level> destDim,
        ServerLevel destLevel,
        MinecraftServer server
    ) {
        PortalTracker destTracker = getTracker(destDim);
        BlockPos expectedPos = computeExpectedDestination(source);

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS DEBUG] findOrCreateDest: source={} in {} → expected={} in {}",
            source.getOrigin(), source.getDimension().identifier(),
            expectedPos, destDim.identifier());

        Optional<PortalInfo> tracked = destTracker.findNearestPortal(expectedPos, 128, source.getType());
        if (tracked.isPresent()) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] findOrCreateDest: FOUND in tracker at {}",
                tracked.get().getOrigin());
            createLink(source, tracked.get());
            sendLinkToClients(source, tracked.get(), server);
            return;
        }

        PortalForcer portalForcer = new PortalForcer(destLevel);
        boolean isNetherSide = destDim == Level.NETHER;
        Optional<BlockPos> actualPortalPos = portalForcer.findClosestPortalPosition(
            expectedPos,
            isNetherSide,
            destLevel.getWorldBorder()
        );

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS DEBUG] findOrCreateDest: PortalForcer.findClosest={} (from expected={})",
            actualPortalPos.orElse(null), expectedPos);

        if (actualPortalPos.isPresent()) {
            PortalInfo actualDest = detectActualPortal(source.getType(), destDim, destLevel, actualPortalPos.get(), source.getAxis());
            registerPortal(actualDest);
            createLink(source, actualDest);
            sendLinkToClients(source, actualDest, server);
            return;
        }

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS] No destination portal found at {} in {}, creating one now",
            expectedPos, destDim.identifier()
        );

        // Create the actual portal blocks using vanilla PortalForcer
        Optional<BlockUtil.FoundRectangle> createdRect = portalForcer.createPortal(expectedPos, source.getAxis());

        if (createdRect.isPresent()) {
            // Portal was created successfully, detect its actual dimensions
            PortalInfo actualDest = detectActualPortal(source.getType(), destDim, destLevel, createdRect.get().minCorner, source.getAxis());
            registerPortal(actualDest);
            createLink(source, actualDest);
            sendLinkToClients(source, actualDest, server);
        } else {
            // Portal creation failed (e.g., no valid placement), use virtual link as fallback
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS] Failed to create destination portal at {}, using virtual link",
                expectedPos
            );
            PortalInfo virtualDest = new PortalInfo(
                source.getType(),
                destDim,
                expectedPos,
                source.getAxis(),
                source.getWidth(),
                source.getHeight()
            );
            registerPortal(virtualDest);
            createLink(source, virtualDest);
            sendLinkToClients(source, virtualDest, server);
        }
    }

    private PortalInfo detectActualPortal(
        PortalType type,
        ResourceKey<Level> dimension,
        ServerLevel level,
        BlockPos portalBlockPos,
        Direction.Axis fallbackAxis
    ) {
        BlockState portalState = level.getBlockState(portalBlockPos);
        Direction.Axis axis = fallbackAxis;
        if (portalState.hasProperty(NetherPortalBlock.AXIS)) {
            axis = portalState.getValue(NetherPortalBlock.AXIS);
        }

        BlockPos origin = PortalDetector.findPortalOriginPublic(level, portalBlockPos, axis);
        int width = PortalDetector.measurePortalWidthPublic(level, origin, axis);
        int height = PortalDetector.measurePortalHeightPublic(level, origin);

        // Verify the detected origin actually has a portal block
        BlockState originState = level.getBlockState(origin);
        boolean originIsPortal = originState.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL);

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS DEBUG] detectActualPortal: startPos={} startBlock={} → origin={} originIsPortal={} axis={} {}x{}",
            portalBlockPos, portalState,
            origin, originIsPortal, axis, width, height
        );

        return new PortalInfo(type, dimension, origin, axis, width, height);
    }

    private void sendLinkToClients(PortalInfo source, PortalInfo dest, MinecraftServer server) {
        ModPayloads.PortalLinkPayload payload = new ModPayloads.PortalLinkPayload(
            source.getDimension().identifier().toString(),
            source.getOrigin(),
            source.getAxis().name().toLowerCase(),
            source.getWidth(),
            source.getHeight(),
            dest.getDimension().identifier().toString(),
            dest.getOrigin(),
            dest.getAxis().name().toLowerCase(),
            dest.getWidth(),
            dest.getHeight()
        );

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlatformHelper.getInstance().sendToClient(player, payload);
        }
    }

    private BlockPos computeExpectedDestination(PortalInfo source) {
        double scale = source.getType().getCoordinateScale();
        BlockPos origin = source.getOrigin();

        if (source.getDimension() == Level.OVERWORLD && source.getType() == PortalType.NETHER) {
            return new BlockPos((int) (origin.getX() / scale), origin.getY(), (int) (origin.getZ() / scale));
        }
        if (source.getDimension() == Level.NETHER && source.getType() == PortalType.NETHER) {
            return new BlockPos((int) (origin.getX() * scale), origin.getY(), (int) (origin.getZ() * scale));
        }
        return origin;
    }

    public void clearDimension(ResourceKey<Level> dimension) {
        // NO-OP: Don't clear portals on dimension change
        // Server sends fresh portal data, so clearing would break portal rendering
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS] clearDimension called for {}, skipping (NO-OP)", dimension.identifier());
    }

    public void clear() {
        trackers.clear();
        linksByPosition.clear();
    }

    /**
     * Send all portal links for a dimension to a specific player.
     * Called after dimension change to ensure client has portal data.
     */
    public void sendDimensionLinksToPlayer(ResourceKey<Level> dimension, ServerPlayer player) {
        PortalTracker tracker = trackers.get(dimension);
        if (tracker == null) {
            SeamlessPortalsConstants.LOGGER.debug(
                "[SEAMLESS] No portals to send for dimension {}", dimension.identifier());
            return;
        }

        int sent = 0;
        for (PortalInfo portal : tracker.getAllPortals()) {
            String key = posKey(dimension, portal.getOrigin());
            PortalLink link = linksByPosition.get(key);
            if (link != null) {
                ModPayloads.PortalLinkPayload payload = new ModPayloads.PortalLinkPayload(
                    link.getSource().getDimension().identifier().toString(),
                    link.getSource().getOrigin(),
                    link.getSource().getAxis().name().toLowerCase(),
                    link.getSource().getWidth(),
                    link.getSource().getHeight(),
                    link.getDestination().getDimension().identifier().toString(),
                    link.getDestination().getOrigin(),
                    link.getDestination().getAxis().name().toLowerCase(),
                    link.getDestination().getWidth(),
                    link.getDestination().getHeight()
                );
                PlatformHelper.getInstance().sendToClient(player, payload);
                sent++;
            }
        }

        if (sent > 0) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS] Re-sent {} portal links to player {} for dimension {}",
                sent, player.getName().getString(), dimension.identifier());
        }
    }
}
