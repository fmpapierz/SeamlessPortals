package com.warwa.seamlessportals.fabric;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.fabric.network.FabricPlatformHelper;
import com.warwa.seamlessportals.render.StencilPortalRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public class SeamlessPortalsClientFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals client initializing (Fabric)");

        FabricPlatformHelper.registerClientHandlers();

        // Phase 2 (stencil mask + composite) at AFTER_TRANSLUCENT_TERRAIN: this is
        // the ONLY point where the framegraph's camera/projection matrices are live
        // (moving it to renderLevel RETURN composites in the wrong screen position).
        // NOTE: the source overworld sky/celestial renders later in the SAME
        // framegraph and paints over this composite (the "blank curtain") — fixing
        // that needs to occlude the source sky in the portal region, not re-time
        // this composite. See GameRendererPortalPrepareMixin for the diagnosis.
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> {
            StencilPortalRenderer.renderPortals();
        });

        // Drain the remote-chunk queues in small batches per tick. Without
        // these, the post-teleport chunk processing (both the server's burst
        // of ~289 incoming chunks AND the secondary-renderer's initial feed
        // of pre-loaded chunks) would freeze the render thread for seconds.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // T2: drain queued redirected dest chunks within a per-tick time budget
            // (was: apply each inline the moment it arrived → a burst froze the render
            // thread ~155ms). Runs FIRST so freshly-applied chunks are available to the
            // compile pump below. The deferred chunk-light lambdas land on each dest
            // level's pollLightUpdates inside tickRemoteWorlds below.
            // DIAG: each client-tick subsystem timed + attributed off-thread
            // ([SEAMLESS TIMERS], reported per 5s) so the "stutters even when not looking
            // at the portal" cost is read from data, not guessed.
            com.warwa.seamlessportals.render.PerfTimers.time("drainChunks",
                com.warwa.seamlessportals.chunk.RedirectedPacketApplier::drainPending);
            com.warwa.seamlessportals.render.PerfTimers.time("advanceCompilePipelines",
                com.warwa.seamlessportals.client.PortalWorldManager::advanceCompilePipelines);
            com.warwa.seamlessportals.render.PerfTimers.time("syncTime",
                com.warwa.seamlessportals.client.PortalWorldManager::syncTimeToCachedLevels);
            com.warwa.seamlessportals.render.PerfTimers.time("tickRemoteWorlds",
                com.warwa.seamlessportals.client.PortalWorldManager::tickRemoteWorlds);
            com.warwa.seamlessportals.render.PerfTimers.time("tickCachedParticles",
                com.warwa.seamlessportals.client.PortalWorldManager::tickCachedParticles);
            com.warwa.seamlessportals.render.PerfTimers.time("evictUnboundedStores",
                com.warwa.seamlessportals.client.PortalWorldManager::evictUnboundedStores);
        });

        SeamlessPortalsConstants.LOGGER.info("Seamless Portals: Registered AFTER_TRANSLUCENT_TERRAIN stencil render hook");
    }
}
