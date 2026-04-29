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

        // Hook AFTER translucent terrain so our portal content renders
        // ON TOP of everything including water, clouds, and translucent blocks.
        // The stencil mask clips to the portal shape regardless of render order.
        //
        // NOTE: IP hooks between solid and translucent because it re-renders
        // the entire destination world (including its own translucent pass).
        // For Phase 1 (colored blocks), rendering last avoids overworld bleed.
        // For Phase 2 (context-switch), we'll need to revisit this hook point.
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> {
            StencilPortalRenderer.renderPortals();
        });

        // Drain the remote-chunk queues in small batches per tick. Without
        // these, the post-teleport chunk processing (both the server's burst
        // of ~289 incoming chunks AND the secondary-renderer's initial feed
        // of pre-loaded chunks) would freeze the render thread for seconds.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RemoteChunkManager.drainPending();
            com.warwa.seamlessportals.client.PortalWorldManager.drainPendingFeeds();
            // Phase C: advance compile work on every non-active secondary
            // renderer so their portal-view sections stay compiled in the
            // background — not just during the brief window an FBO render
            // occupies the render thread.
            com.warwa.seamlessportals.client.PortalWorldManager.advanceCompilePipelines();
            // Keep cached (dormant) levels' gameTime in sync with the active
            // mc.level so portal-view rendering doesn't show stale time-of-day.
            com.warwa.seamlessportals.client.PortalWorldManager.syncTimeToCachedLevels();
            // Phase 2a.5: tick mirrored entities in cached levels so their
            // interpolation handlers advance and animations run. Without
            // this, mobs visible through portals are frozen / spazzing.
            com.warwa.seamlessportals.client.PortalWorldManager.tickCachedEntities();
            // Stage 4 (IP parity): drain queueLightUpdate runnables on
            // cached levels + advance destruction-progress cleanup on
            // cached renderers. Mirrors IP's ClientWorldLoader.tick →
            // tickRemoteWorld + worldRenderer.tick loop. No-op until
            // Stage 3 (cross-dim chunk redirection) routes vanilla
            // chunk/light packets to cached levels.
            com.warwa.seamlessportals.client.PortalWorldManager.tickCachedLightAndRenderers();
        });

        SeamlessPortalsConstants.LOGGER.info("Seamless Portals: Registered AFTER_TRANSLUCENT_TERRAIN stencil render hook");
    }
}
