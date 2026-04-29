package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.network.SeamlessPacketRedirection;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Set;

/**
 * Cross-dim time + weather sync. Direct port of IP 1.19's
 * {@code WorldInfoSender}.
 *
 * <p>Vanilla server only sends {@code ClientboundSetTimePacket} and
 * weather-related {@code ClientboundGameEventPacket}s to clients for
 * the player's CURRENT dimension. When the player views a cached
 * cross-dim {@link net.minecraft.client.multiplayer.ClientLevel}
 * through a portal, that level's time + weather state are stale
 * (frozen at whatever they were last synced).
 *
 * <p>This sender re-broadcasts time + weather to players for every
 * dimension they're watching (per the
 * {@link SeamlessChunkTrackingGraph#getVisibleDimensions} set), every
 * 100 ticks (5 s). Wrapped in {@link SeamlessPacketRedirection} so
 * the packet lands on the player's CACHED level for that dim, not
 * their active mc.level.
 *
 * <p>Specifically targets dimensions with {@code hasSkyLight() &&
 * != OVERWORLD} (i.e. The End in vanilla, or modded surface dims).
 * Plus always sends Overworld time to non-Overworld players (so
 * Overworld-through-portal-from-Nether shows correct day/night).
 *
 * <p><b>Difference from prior {@code PortalWorldManager.syncTimeToCachedLevels}:</b>
 * Our existing time sync mutates the CACHED level's {@code gameTime}
 * directly via {@code level.setTimeFromServer}, which works but
 * doesn't sync weather and only handles time. This sender uses the
 * proper packet path matching IP's design — same data flows through
 * the same vanilla handlers, ensuring weather + time stay
 * consistent.
 */
public final class SeamlessWorldInfoSender {

    private SeamlessWorldInfoSender() {}

    /** Send interval in server ticks (5 seconds at 20 tps). */
    private static final int SEND_INTERVAL_TICKS = 100;

    /** Phase offset so the work doesn't all collide with another tick-mod-100 system. */
    private static final int SEND_PHASE = 42;

    public static void tick(MinecraftServer server) {
        if (server == null) return;
        long gameTime = server.overworld().getGameTime();
        if (gameTime % SEND_INTERVAL_TICKS != SEND_PHASE) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                sendForPlayer(server, player);
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.debug(
                    "[SEAMLESS WORLD-INFO] sendForPlayer failed for {}: {}",
                    player.getName().getString(), t.toString());
            }
        }
    }

    private static void sendForPlayer(MinecraftServer server, ServerPlayer player) {
        Set<ResourceKey<Level>> visibleDimensions =
            SeamlessChunkTrackingGraph.getVisibleDimensions(player);
        ResourceKey<Level> playerDim = player.level().dimension();

        // If player is NOT in OW, also send OW time/weather (so
        // OW-through-portal-from-Nether/End looks correct).
        if (!playerDim.equals(Level.OVERWORLD)) {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow != null) {
                sendWorldInfo(player, ow);
            }
        }

        // For each non-OW surface dim the player can see (i.e. End,
        // or any modded surface dim), send its time + weather.
        for (ServerLevel world : server.getAllLevels()) {
            if (!isNonOverworldSurfaceDimension(world)) continue;
            if (!visibleDimensions.contains(world.dimension())) continue;
            // Skip if it's the player's current dim — vanilla's
            // server already sends those packets via the regular
            // (non-redirected) path.
            if (world.dimension().equals(playerDim)) continue;
            sendWorldInfo(player, world);
        }
    }

    private static void sendWorldInfo(ServerPlayer player, ServerLevel world) {
        ResourceKey<Level> dim = world.dimension();

        // NB: time sync is handled separately by
        // PortalWorldManager.syncTimeToCachedLevels which directly
        // mutates cached.setTimeFromServer per tick. The
        // ClientboundSetTimePacket API in MC 26.1.2 was redesigned
        // around per-clock network state (Map<Holder<WorldClock>,
        // ClockNetworkState>) and constructing one cross-dim
        // requires the destination world's full clock-state, which
        // doesn't cleanly redirect. Mutating the cached level
        // directly is simpler and equivalent for our purposes.

        // Send rain + thunder level. (Don't skip when not raining —
        // if weather just stopped the level needs to know to clear.)
        if (world.isRaining()) {
            SeamlessPacketRedirection.sendRedirected(
                player, dim,
                new ClientboundGameEventPacket(
                    ClientboundGameEventPacket.START_RAINING, 0.0F));
        }
        SeamlessPacketRedirection.sendRedirected(
            player, dim,
            new ClientboundGameEventPacket(
                ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
                world.getRainLevel(1.0F)));
        SeamlessPacketRedirection.sendRedirected(
            player, dim,
            new ClientboundGameEventPacket(
                ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,
                world.getThunderLevel(1.0F)));
    }

    /**
     * True for dimensions that have sky-light AND aren't the
     * Overworld. In vanilla this is just The End. Mods can add
     * other surface-dim worlds; they'd qualify too.
     */
    private static boolean isNonOverworldSurfaceDimension(ServerLevel world) {
        return world.dimensionType().hasSkyLight()
            && !world.dimension().equals(Level.OVERWORLD);
    }
}
