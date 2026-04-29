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

    /**
     * Snapshot of all currently-known portal links. Used by
     * {@link com.warwa.seamlessportals.chunk.SeamlessLinkReadinessTracker}
     * to iterate per-tick checking readiness.
     */
    public java.util.Collection<PortalLink> getAllLinksSnapshot() {
        return new java.util.ArrayList<>(linksByPosition.values());
    }

    /**
     * Look up a link by source dim + source origin. Used by the
     * client-side {@code LinkReadinessPayload} handler to find the
     * matching link.
     */
    public PortalLink getLinkBySource(ResourceKey<Level> dim, BlockPos origin) {
        return linksByPosition.get(posKey(dim, origin));
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

        // IP parity: register global chunk loaders for BOTH portal
        // endpoints. Idempotent — only registers ONCE per
        // (source, destination) pair. createLink can be called
        // many times for the same portal pair as the server re-syncs
        // link data; without dedup, each call registers another pair
        // of loaders, and the graph's updateAndPurge iterates ALL
        // loaders every cycle (49 chunks × N loaders × 13-tick
        // interval = catastrophic CPU pile-up at >50 redundant
        // loaders).
        if (!isClient) {
            registerLinkChunkLoadersIfNotPresent(source);
        }
        return forward;
    }

    /**
     * Per-link chunk-loader registry: holds strong refs to the
     * loaders so they aren't GC'd. Keyed by source-side posKey for
     * lookup on portal removal.
     */
    private final Map<String, com.warwa.seamlessportals.chunk.SeamlessChunkLoader[]>
        linkChunkLoaders = new ConcurrentHashMap<>();

    /**
     * Half-side of the destination-chunk grid we pre-load on portal
     * creation. {@code 2 → 25 chunks per portal endpoint × 2 ends =
     * 50 chunks of pre-load work per portal pair}. Lowered from 4
     * (81 chunks/end → 162/pair) because SP integrated server's
     * chunk-gen throughput cannot complete 162 chunks in the few
     * seconds between portal creation and the user's first
     * teleport — server falls 5s behind, user gets stuck mid-portal.
     *
     * <p>At 25 chunks, gen completes in ~2-3 seconds on typical
     * hardware. By the time the user walks to the portal, dest area
     * is ready.
     */
    private static final int PORTAL_CREATE_PRELOAD_RADIUS = 2;

    /**
     * Register chunk-loaders for a portal endpoint pair, but ONLY if
     * we haven't already registered for this {@code source}. The key
     * is the source-side {@code posKey} so {@code createLink(A, B)}
     * and {@code createLink(B, A)} (the reverse link) each get their
     * own registration — that's correct because each side needs its
     * own loader to pre-load the OTHER side's chunks.
     */
    private void registerLinkChunkLoadersIfNotPresent(PortalInfo source) {
        String key = posKey(source.getDimension(), source.getOrigin());
        if (linkChunkLoaders.containsKey(key)) {
            // Already registered. createLink is being called as part
            // of a re-sync (e.g. portal-link payload re-broadcast or
            // server-state restore). The original chunk loaders are
            // still active in the graph; no need to register more.
            return;
        }
        try {
            com.warwa.seamlessportals.chunk.SeamlessChunkLoader sourceLoader =
                makeLoader(source);
            com.warwa.seamlessportals.chunk.SeamlessChunkTrackingGraph
                .addGlobalAdditionalChunkLoader(sourceLoader);
            // Strong ref so the graph's WeakReference survives.
            linkChunkLoaders.put(key,
                new com.warwa.seamlessportals.chunk.SeamlessChunkLoader[]{sourceLoader});
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PRELOAD] Registered global chunk loader for portal at {} in {}",
                source.getOrigin(), source.getDimension().identifier());
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS PRELOAD] Failed to register chunk loaders for portal at {}: {}",
                source.getOrigin(), t.toString());
        }
    }

    private com.warwa.seamlessportals.chunk.SeamlessChunkLoader makeLoader(PortalInfo portal) {
        BlockPos origin = portal.getOrigin();
        net.minecraft.world.level.ChunkPos chunkPos =
            net.minecraft.world.level.ChunkPos.containing(origin);
        return new com.warwa.seamlessportals.chunk.SeamlessChunkLoader(
            new com.warwa.seamlessportals.chunk.DimChunkPos(portal.getDimension(), chunkPos),
            PORTAL_CREATE_PRELOAD_RADIUS,
            false);
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
