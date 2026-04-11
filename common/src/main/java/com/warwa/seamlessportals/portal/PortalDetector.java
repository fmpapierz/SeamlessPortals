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
     * Client-side portal detection. When a player walks near a portal on the client,
     * register it with the client's PortalManager so the renderer knows where portals are.
     * Also creates a "virtual" link pointing to the corresponding nether position.
     *
     * DEBUG: Logs client-side portal registration.
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

        // Create a virtual destination portal at the nether-equivalent position
        net.minecraft.resources.ResourceKey<Level> destDim = PortalType.NETHER.getDestinationFor(level.dimension());
        if (destDim != null) {
            double scale = (level.dimension() == Level.OVERWORLD) ? 1.0 / 8.0 : 8.0;
            BlockPos destOrigin = new BlockPos(
                (int)(origin.getX() * scale),
                origin.getY(),
                (int)(origin.getZ() * scale)
            );
            PortalInfo destPortal = new PortalInfo(PortalType.NETHER, destDim, destOrigin, axis, width, height);
            clientManager.registerPortal(destPortal);
            clientManager.createLink(clientPortal, destPortal);

            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] Client registered portal at {} -> {} in {}",
                origin, destOrigin, destDim.identifier()
            );
        }
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
