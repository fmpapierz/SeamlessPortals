package com.warwa.seamlessportals.fabric;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.PortalChunkTracker;
import com.warwa.seamlessportals.fabric.network.FabricPlatformHelper;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.portal.PortalManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class SeamlessPortalsModFabric implements ModInitializer {

    private final PortalChunkTracker chunkTracker = new PortalChunkTracker();

    @Override
    public void onInitialize() {
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals initializing (Fabric)");

        ModPayloads.registerCommon();

        FabricPlatformHelper helper = new FabricPlatformHelper();
        helper.registerPayloads();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            PortalManager.getServerInstance();
            FabricPlatformHelper.registerServerHandlers();
            SeamlessPortalsConstants.LOGGER.info("Seamless Portals server systems ready");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PortalManager.resetServer();
            chunkTracker.clear();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            chunkTracker.tick(server);
        });
    }
}
