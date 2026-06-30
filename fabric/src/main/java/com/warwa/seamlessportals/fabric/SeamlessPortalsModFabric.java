package com.warwa.seamlessportals.fabric;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.PortalChunkTracker;
import com.warwa.seamlessportals.chunk.PortalEntityTracker;
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

        // Load the configurable knob(s) (portalRenderDistance = dest loading/mesh depth).
        com.warwa.seamlessportals.config.SeamlessPortalsConfig.loadFrom(
            net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());

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
            entityTracker.clear();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            chunkTracker.tick(server);
            entityTracker.tick(server);
            // IP-style continuous pre-warm: re-add the dest-chunk loading
            // ticket every tick for every portal within proximity of any
            // player. Keeps the destination chunks resident as long as
            // the player is "approaching", so the cross-dim teleport
            // never has to wait for synchronous worldgen.
            com.warwa.seamlessportals.portal.PortalManager
                .getServerInstance().tickPortalPreWarm(server);
            // Flush the per-tick coalesced live-block-mirror updates as one batch per
            // player per dim (vanilla-style), instead of a packet per block change.
            com.warwa.seamlessportals.render.PerfTimers.time("srv.blockMirrorFlush",
                () -> com.warwa.seamlessportals.chunk.BlockUpdateMirrorBuffer.flush(server));
        });
    }
}
