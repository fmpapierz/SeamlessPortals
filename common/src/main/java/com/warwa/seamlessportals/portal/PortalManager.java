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
import net.minecraft.world.level.block.Blocks;
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

    /**
     * Radius (in chunks) of the pre-warm region around the destination
     * portal origin. Sized to cover what the player will see immediately
     * after teleport — vanilla simulation distance is typically 7, plus
     * a small margin for headroom.
     */
    private static final int PREWARM_RADIUS_CHUNKS = 8;

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
            preWarmDestinationChunks(tracked.get().getOrigin(), destLevel);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS] Initial pre-warm at {} in {}",
                tracked.get().getOrigin(), destLevel.dimension().identifier());
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
            preWarmDestinationChunks(actualDest.getOrigin(), destLevel);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS] Initial pre-warm at {} in {}",
                actualDest.getOrigin(), destLevel.dimension().identifier());
            return;
        }

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS] No destination portal found at {} in {}, creating one now",
            expectedPos, destDim.identifier()
        );

        // Create the actual portal blocks using vanilla PortalForcer
        Optional<BlockUtil.FoundRectangle> createdRect = portalForcer.createPortal(expectedPos, source.getAxis());

        if (createdRect.isPresent()) {
            BlockPos minCorner = createdRect.get().minCorner;

            // Vanilla createPortal hardcodes 2x3 dimensions. Resize the just-
            // created destination portal to match the source portal's size so
            // both sides are symmetric windows.
            resizePortalToMatchSource(
                destLevel, minCorner, source.getAxis(),
                source.getWidth(), source.getHeight());

            // Portal was created successfully, detect its actual dimensions
            PortalInfo actualDest = detectActualPortal(source.getType(), destDim, destLevel, minCorner, source.getAxis());
            registerPortal(actualDest);
            createLink(source, actualDest);
            sendLinkToClients(source, actualDest, server);
            preWarmDestinationChunks(actualDest.getOrigin(), destLevel);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS] Initial pre-warm at {} in {} (newly built)",
                actualDest.getOrigin(), destLevel.dimension().identifier());
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
            preWarmDestinationChunks(virtualDest.getOrigin(), destLevel);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS] Initial pre-warm at {} in {} (virtual)",
                virtualDest.getOrigin(), destLevel.dimension().identifier());
        }
    }

    /**
     * Schedule asynchronous chunk loading around the destination portal
     * origin so the chunks are resident by the time the player walks
     * through. Without this, the cross-dim teleport triggers a multi-
     * second synchronous worldgen stall on the server thread (the player
     * is dropped into a fresh region; vanilla generates the surrounding
     * chunks all at once).
     *
     * <p>Uses the vanilla chunk-ticket system: {@code addTicketWithRadius}
     * tells the server's chunk pipeline to load the radius of chunks
     * over the next several ticks. Spreads the worldgen cost across many
     * ticks instead of one big stall.
     *
     * <p>Called from each destination-establishing branch of
     * {@link #findOrCreateDestinationPortal} — both for newly-created
     * destinations (via {@code PortalForcer}) and existing-portal links
     * (chunks may have been unloaded since the destination portal was
     * last touched).
     */
    /**
     * Range (in blocks) within which a player "approaching" a portal
     * triggers continuous pre-warm of that portal's destination chunks.
     * 32 blocks ≈ 2 chunks — wide enough to start loading well before the
     * player reaches the portal, narrow enough that we don't pre-warm
     * portals on the other side of the world.
     */
    private static final double PREWARM_PROXIMITY_BLOCKS = 32.0;

    /**
     * Per-tick hook (called from {@code ServerTickEvents.END_SERVER_TICK}).
     * For every player in every dim, find portals within
     * {@link #PREWARM_PROXIMITY_BLOCKS} of the player and re-add the
     * pre-warm ticket on each portal's destination. The vanilla ticket
     * timeout (60 s) means each call extends the chunks' resident
     * lifetime — chunks stay loaded as long as the player stays near
     * the portal in source dim.
     *
     * <p>Modeled after IP's per-player {@code NewChunkTrackingGraph.tick}
     * pattern (simpler — no rate budget, no distance priority queue,
     * just "if near portal, ensure dest chunks loaded").
     */
    public void tickPortalPreWarm(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null) continue;
            ResourceKey<Level> playerDim = player.level().dimension();
            BlockPos playerPos = player.blockPosition();

            for (PortalLink link : getLinksInRange(playerDim, playerPos, PREWARM_PROXIMITY_BLOCKS)) {
                PortalInfo dest = link.getDestination();
                ServerLevel destLevel = server.getLevel(dest.getDimension());
                if (destLevel == null) continue;
                preWarmDestinationChunks(dest.getOrigin(), destLevel);
            }
        }
    }

    private void preWarmDestinationChunks(BlockPos destOrigin, ServerLevel destLevel) {
        net.minecraft.world.level.ChunkPos centerChunk = new net.minecraft.world.level.ChunkPos(
            destOrigin.getX() >> 4, destOrigin.getZ() >> 4);
        destLevel.getChunkSource().addTicketWithRadius(
            com.warwa.seamlessportals.chunk.PortalEntityTracker.PORTAL_PREWARM_TICKET,
            centerChunk, PREWARM_RADIUS_CHUNKS);
        // (No log — this runs every server tick from
        // {@link #tickPortalPreWarm} and would spam. Formation-time
        // log lives in the formation paths in
        // {@link #findOrCreateDestinationPortal}.)
    }

    /**
     * Resize the just-created vanilla 2x3 destination portal so it matches the
     * source portal's dimensions. Vanilla's {@code PortalForcer.createPortal}
     * hardcodes a 2x3 opening; without this, a 3x4 overworld portal would link
     * to a 2x3 nether portal and the player would see a mismatched window.
     *
     * <p>This operates on the portal coordinate system vanilla uses in
     * {@code PortalForcer.createPortal}:
     * <ul>
     *   <li>{@code minCorner} is the bottom-width-start portal block (the
     *       {@code closestFullPosition} vanilla returned in the FoundRectangle).</li>
     *   <li>For axis=X the "width" direction is EAST (+X).</li>
     *   <li>For axis=Z the "width" direction is NORTH (-Z).</li>
     *   <li>Height is +Y.</li>
     * </ul>
     *
     * <p>The resize overwrites anything in the way of the larger portal. We don't
     * attempt to validate the terrain around the expansion — vanilla already
     * placed a 2x3 frame there, and overwriting a few extra blocks is the price
     * of symmetric portals. Source size is assumed ≥ 2x3 (vanilla's minimum
     * and also our enforced minimum on the source side).
     */
    private static void resizePortalToMatchSource(
            ServerLevel destLevel, BlockPos minCorner, Direction.Axis axis,
            int sourceWidth, int sourceHeight) {
        if (sourceWidth == 2 && sourceHeight == 3) {
            return; // already the right size
        }

        // Match vanilla PortalForcer.createPortal's direction convention:
        //   Direction.get(Direction.AxisDirection.POSITIVE, axis)
        // i.e. EAST for axis=X, SOUTH for axis=Z. The portal extends from
        // `minCorner` (= vanilla's closestFullPosition) in this +axis
        // direction. Earlier I used NORTH for axis=Z, which placed the new
        // portal blocks and the new frame on the WRONG side of vanilla's
        // 2x3, producing a double-thick obsidian column and a portal whose
        // real position was offset by ~1 block from what detection said.
        Direction widthDir = Direction.get(Direction.AxisDirection.POSITIVE, axis);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState portalBlockState = Blocks.NETHER_PORTAL.defaultBlockState()
            .setValue(NetherPortalBlock.AXIS, axis);
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();

        // ORDER MATTERS: place the obsidian frame FIRST, then the portal blocks.
        // If we do it the other way round, the obsidian setBlock(flag=3) triggers
        // neighbor updates on the just-placed portal blocks. Those portal blocks
        // then run their "is my frame valid?" self-check against a still-partial
        // frame, fail, and self-destruct. Vanilla's PortalForcer follows the same
        // "frame first, then portal blocks" order for this reason.

        // Step 1 — obsidian ring around the source-sized opening.
        // Also overwrites the PARTIAL vanilla 2x3 frame edges that now sit
        // inside the new larger opening (those get re-set to portal blocks in
        // step 2). Everything outside the new opening is obsidian after this.
        for (int w = -1; w <= sourceWidth; w++) {
            for (int h = -1; h <= sourceHeight; h++) {
                boolean onFrame = (w == -1) || (w == sourceWidth)
                               || (h == -1) || (h == sourceHeight);
                if (!onFrame) continue;
                pos.setWithOffset(minCorner,
                    w * widthDir.getStepX(),
                    h,
                    w * widthDir.getStepZ());
                destLevel.setBlock(pos, obsidian, 3);
            }
        }

        // Step 2 — portal blocks filling the source-sized opening. Flag 18
        // (UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE) avoids triggering neighbor
        // self-checks so the portal block placements are durable. At this
        // point the full 3x4 (or whatever) obsidian ring is already in place,
        // so any self-check that does fire finds a valid frame.
        for (int w = 0; w < sourceWidth; w++) {
            for (int h = 0; h < sourceHeight; h++) {
                pos.setWithOffset(minCorner,
                    w * widthDir.getStepX(),
                    h,
                    w * widthDir.getStepZ());
                destLevel.setBlock(pos, portalBlockState, 18);
            }
        }

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS] Resized destination portal at {} (axis={}) from 2x3 to {}x{}",
            minCorner, axis, sourceWidth, sourceHeight);
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
