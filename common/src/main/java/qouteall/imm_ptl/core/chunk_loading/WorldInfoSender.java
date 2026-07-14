package qouteall.imm_ptl.core.chunk_loading;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.Validate;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.Set;

public class WorldInfoSender {
    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register((server) -> {
            Profiler.get().push("portal_send_world_info");
            if (McHelper.getServerGameTime() % 100 == 42) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    Set<ResourceKey<Level>> visibleDimensions = ImmPtlChunkTracking.getVisibleDimensions(player);
                    
                    // sync overworld status when the player is not in overworld
                    if (player.level().dimension() != Level.OVERWORLD) {
                        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
                        Validate.notNull(overworld, "missing overworld");
                        sendWorldInfo(player, overworld);
                    }
                    
                    server.getAllLevels().forEach(thisWorld -> {
                        if (isNonOverworldSurfaceDimension(thisWorld)) {
                            if (visibleDimensions.contains(thisWorld.dimension())) {
                                sendWorldInfo(player, thisWorld);
                            }
                        }
                    });
                    
                }
            }
            Profiler.get().pop();
        });
    }
    
    //send the daytime and weather info to player when player is in nether
    public static void sendWorldInfo(ServerPlayer player, ServerLevel world) {
        // [26.2 FORCED DEVIATION F1 / R13j — api-map/chunk-loading.md #34 + #43]
        // IP sent a per-dimension ClientboundSetTimePacket(gameTime, dayTime, doDaylightCycle)
        // here (with the now-dead local `ResourceKey<Level> remoteDimension = world.dimension()`)
        // so a player outside a skylight dimension still saw that remote dimension's day-time.
        // In 26.2 the packet is the connection-global WorldClock record
        // `ClientboundSetTimePacket(long gameTime, Map<Holder<WorldClock>, ClockNetworkState>)`
        // — there is no per-dim dayTime and no daylight boolean: both `Level.getDayTime()` and
        // `GameRules.RULE_DAYLIGHT` are GONE. Vanilla already syncs one global gameTime + all
        // clock states to every player regardless of dimension, so the time half is structurally
        // obsolete; a hand-built `Map.of()` variant would merely duplicate that global sync and a
        // per-dim variant could desync the connection-global clock manager. The time half is
        // therefore DELETED, not translated. Only the un-dimensioned weather half below survives
        // (its cross-dimension rain-flip broadcast is still un-dimensioned in 26.2).

        /**{@link net.minecraft.client.network.ClientPlayNetworkHandler#onGameStateChange(GameStateChangeS2CPacket)}*/
        
        if (world.isRaining()) {
            PacketRedirection.sendRedirectedMessage(
                player,
                world.dimension(),
                new ClientboundGameEventPacket(
                    ClientboundGameEventPacket.START_RAINING,
                    0.0F
                )
            );
        }
        else {
            //if the weather is already not raining when the player logs in then no need to sync
            //if the weather turned to not raining then elsewhere syncs it
        }
        
        PacketRedirection.sendRedirectedMessage(
            player,
            world.dimension(),
            new ClientboundGameEventPacket(
                ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
                world.getRainLevel(1.0F)
            )
        );
        PacketRedirection.sendRedirectedMessage(
            player,
            world.dimension(),
            new ClientboundGameEventPacket(
                ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,
                world.getThunderLevel(1.0F)
            )
        );
    }
    
    public static boolean isNonOverworldSurfaceDimension(Level world) {
        return world.dimensionType().hasSkyLight() && world.dimension() != Level.OVERWORLD;
    }
}
