package com.warwa.seamlessportals.fabric;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.PortalChunkTracker;
import com.warwa.seamlessportals.chunk.PortalEntityTracker;
import com.warwa.seamlessportals.chunk.SeamlessChunkDataSync;
import com.warwa.seamlessportals.chunk.SeamlessChunkTrackingGraph;
import com.warwa.seamlessportals.chunk.SeamlessLoadingTicket;
import com.warwa.seamlessportals.fabric.network.FabricPlatformHelper;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.portal.PortalManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class SeamlessPortalsModFabric implements ModInitializer {

    private final PortalChunkTracker chunkTracker = new PortalChunkTracker();
    private final PortalEntityTracker entityTracker = new PortalEntityTracker();

    @Override
    public void onInitialize() {
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals initializing (Fabric)");

        ModPayloads.registerCommon();

        FabricPlatformHelper helper = new FabricPlatformHelper();
        helper.registerPayloads();

        // Full IP-style chunk-distribution graph init (E1 + E6 of the
        // 2026-04-28 design doc). E1 registers the custom TicketType
        // before world load; E6 wires the data-sync listeners onto
        // the graph signals.
        SeamlessLoadingTicket.init();
        SeamlessChunkDataSync.init();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            PortalManager.getServerInstance();
            FabricPlatformHelper.registerServerHandlers();
            SeamlessPortalsConstants.LOGGER.info("Seamless Portals server systems ready");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PortalManager.resetServer();
            chunkTracker.clear();
            entityTracker.clear();
            SeamlessChunkTrackingGraph.cleanup();
            SeamlessChunkDataSync.clear();
            SeamlessLoadingTicket.clear();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            chunkTracker.tick(server);
            entityTracker.tick(server);
            // E4 — per-tick player chunk-watch graph. Throttled
            // internally (each player updates every 13 ticks).
            SeamlessChunkTrackingGraph.tick(server);
            // E6 — drain deferred chunk-data deliveries for chunks
            // that weren't generated yet at begin-watch time.
            SeamlessChunkDataSync.tick(server);
            // Track-B-R-S1: Auto-scale performance level based on
            // server tick time. Drops radii + budgets when struggling.
            com.warwa.seamlessportals.chunk.SeamlessServerPerformanceMonitor.tick(server);
            // Cross-dim time + weather sync. Sends time/rain/thunder
            // to players for visible non-active dimensions every
            // 100 ticks via SeamlessPacketRedirection.
            com.warwa.seamlessportals.chunk.SeamlessWorldInfoSender.tick(server);
            // IP loading-indicator parity: poll all PortalLinks per
            // tick. When a link's pre-load chunks reach FULL status,
            // mark it ready and broadcast to clients. Until ready,
            // both client-first and server-side teleport are blocked
            // for that link.
            com.warwa.seamlessportals.chunk.SeamlessLinkReadinessTracker.tick(server);
        });
    }
}
