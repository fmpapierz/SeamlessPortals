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
            // Spawn + tick each cached destination dimension's ambient particles
            // (flame, lava, nether portal, fog) in its OWN per-dest ParticleEngine
            // so they render inside the portal view.
            com.warwa.seamlessportals.client.PortalWorldManager.tickCachedParticles();
        });

        SeamlessPortalsConstants.LOGGER.info("Seamless Portals: Registered AFTER_TRANSLUCENT_TERRAIN stencil render hook");
    }
}
