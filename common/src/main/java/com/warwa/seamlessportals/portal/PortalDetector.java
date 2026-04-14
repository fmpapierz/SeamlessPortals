package com.warwa.seamlessportals.portal;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;

public class PortalDetector {

    public static void onNetherPortalFormed(Level level, BlockPos portalBlock, MinecraftServer server) {
        BlockState state = level.getBlockState(portalBlock);
        if (!state.is(Blocks.NETHER_PORTAL)) return;

        // Skip if this portal is already registered
        PortalTracker tracker = PortalManager.getServerInstance().getTracker(level.dimension());
        if (tracker.getPortalAt(portalBlock).isPresent()) return;

        Direction.Axis axis = state.getValue(NetherPortalBlock.AXIS);

        BlockPos origin = findPortalOrigin(level, portalBlock, axis);
        int width = measurePortalWidth(level, origin, axis);
        int height = measurePortalHeight(level, origin);

        SeamlessPortalsConstants.LOGGER.info("Detected nether portal at {} axis={} size={}x{}",
            origin, axis, width, height);

        PortalManager.getServerInstance().onPortalFormed(
            PortalType.NETHER, level.dimension(), origin, axis, width, height, server
        );
    }

    public static void onEndPortalFormed(Level level, BlockPos portalBlock, MinecraftServer server) {
        BlockPos origin = findEndPortalOrigin(level, portalBlock);
        int width = 3;
        int height = 1;

        SeamlessPortalsConstants.LOGGER.info("Detected end portal at {}", origin);

        PortalManager.getServerInstance().onPortalFormed(
            PortalType.END, level.dimension(), origin, Direction.Axis.Y, width, height, server
        );
    }

    /** Public accessor for PortalManager's PortalForcer-based detection. */
    public static BlockPos findPortalOriginPublic(Level level, BlockPos start, Direction.Axis axis) {
        return findPortalOrigin(level, start, axis);
    }

    /** Public accessor for PortalManager's PortalForcer-based detection. */
    public static int measurePortalWidthPublic(Level level, BlockPos origin, Direction.Axis axis) {
        return measurePortalWidth(level, origin, axis);
    }

    /** Public accessor for PortalManager's PortalForcer-based detection. */
    public static int measurePortalHeightPublic(Level level, BlockPos origin) {
        return measurePortalHeight(level, origin);
    }

    private static BlockPos findPortalOrigin(Level level, BlockPos start, Direction.Axis axis) {
        BlockPos.MutableBlockPos pos = start.mutable();

        while (level.getBlockState(pos.below()).is(Blocks.NETHER_PORTAL)) {
            pos.move(Direction.DOWN);
        }

        // axis = WIDTH direction. Move to the negative end of the width axis to find origin.
        // axis=X: width along X → move WEST (negative X)
        // axis=Z: width along Z → move NORTH (negative Z)
        Direction widthDir = (axis == Direction.Axis.X) ? Direction.WEST : Direction.NORTH;
        while (level.getBlockState(pos.relative(widthDir)).is(Blocks.NETHER_PORTAL)) {
            pos.move(widthDir);
        }

        return pos.immutable();
    }

    private static int measurePortalWidth(Level level, BlockPos origin, Direction.Axis axis) {
        // axis = WIDTH direction. Count along the width axis.
        // axis=X: width along X → count EAST (positive X)
        // axis=Z: width along Z → count SOUTH (positive Z)
        Direction widthDir = (axis == Direction.Axis.X) ? Direction.EAST : Direction.SOUTH;
        int width = 0;
        BlockPos.MutableBlockPos pos = origin.mutable();

        while (level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
            width++;
            pos.move(widthDir);
        }
        return width;
    }

    private static int measurePortalHeight(Level level, BlockPos origin) {
        int height = 0;
        BlockPos.MutableBlockPos pos = origin.mutable();

        while (level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
            height++;
            pos.move(Direction.UP);
        }
        return height;
    }

    private static BlockPos findEndPortalOrigin(Level level, BlockPos start) {
        BlockPos.MutableBlockPos pos = start.mutable();

        while (level.getBlockState(pos.west()).is(Blocks.END_PORTAL)) {
            pos.move(Direction.WEST);
        }
        while (level.getBlockState(pos.north()).is(Blocks.END_PORTAL)) {
            pos.move(Direction.NORTH);
        }
        return pos.immutable();
    }

    /**
     * Client-side portal detection. Registers the portal shape for stencil rendering.
     *
     * IMPORTANT: Does NOT create links or compute destination positions.
     * Following IP's architecture, only the SERVER knows the actual destination
     * (via PortalForcer). Link data comes from the server via PortalLinkPayload.
     */
    public static void onNetherPortalDetectedClient(Level level, BlockPos portalBlock) {
        BlockState state = level.getBlockState(portalBlock);
        if (!state.is(Blocks.NETHER_PORTAL)) return;

        PortalManager clientManager = PortalManager.getClientInstance();
        PortalTracker tracker = clientManager.getTracker(level.dimension());

        // Skip if already registered
        if (tracker.getPortalAt(portalBlock).isPresent()) return;

        Direction.Axis axis = state.getValue(NetherPortalBlock.AXIS);
        BlockPos origin = findPortalOrigin(level, portalBlock, axis);
        int width = measurePortalWidth(level, origin, axis);
        int height = measurePortalHeight(level, origin);

        PortalInfo clientPortal = new PortalInfo(PortalType.NETHER, level.dimension(), origin, axis, width, height);
        clientManager.registerPortal(clientPortal);

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS] Portal block at {} axis={} in {}",
            origin, axis, level.dimension().identifier()
        );

        // Link data will arrive from server via PortalLinkPayload.
        // Do NOT create virtual links with scaled coordinates here.
    }

    /**
     * Handle portal link data received from the server.
     * This is the ONLY source of truth for portal link positions.
     * Following IP: the server determines actual positions via PortalForcer,
     * then sends them to the client.
     */
    public static void handlePortalLinkFromServer(
            com.warwa.seamlessportals.network.ModPayloads.PortalLinkPayload payload) {
        PortalManager clientManager = PortalManager.getClientInstance();

        net.minecraft.resources.ResourceKey<Level> srcDim =
            dimKeyFromString(payload.srcDimension());
        net.minecraft.resources.ResourceKey<Level> destDim =
            dimKeyFromString(payload.destDimension());

        Direction.Axis srcAxis = Direction.Axis.valueOf(payload.srcAxis().toUpperCase());
        Direction.Axis destAxis = Direction.Axis.valueOf(payload.destAxis().toUpperCase());

        PortalInfo srcPortal = new PortalInfo(
            PortalType.NETHER, srcDim, payload.srcOrigin(),
            srcAxis, payload.srcWidth(), payload.srcHeight()
        );
        PortalInfo destPortal = new PortalInfo(
            PortalType.NETHER, destDim, payload.destOrigin(),
            destAxis, payload.destWidth(), payload.destHeight()
        );

        clientManager.registerPortal(srcPortal);
        clientManager.registerPortal(destPortal);
        clientManager.createLink(srcPortal, destPortal);

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS DEBUG] Client registered portal at {} -> {} in {} (from server)",
            payload.srcOrigin(), payload.destOrigin(), destDim.identifier()
        );
    }

    private static net.minecraft.resources.ResourceKey<Level> dimKeyFromString(String dim) {
        if (dim.contains("the_nether")) return Level.NETHER;
        if (dim.contains("the_end")) return Level.END;
        return Level.OVERWORLD;
    }

    public static void onPortalDestroyed(Level level, BlockPos pos, PortalType type, MinecraftServer server) {
        PortalManager manager = PortalManager.getServerInstance();
        PortalTracker tracker = manager.getTracker(level.dimension());

        tracker.getPortalAt(pos).ifPresent(portal -> {
            SeamlessPortalsConstants.LOGGER.info("Portal destroyed at {} in {}",
                pos, level.dimension().identifier());
            manager.unregisterPortal(portal);
        });
    }
}
