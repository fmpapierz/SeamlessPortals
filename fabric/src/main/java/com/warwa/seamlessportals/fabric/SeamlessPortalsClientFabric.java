package com.warwa.seamlessportals.fabric;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.fabric.network.FabricPlatformHelper;
import com.warwa.seamlessportals.render.StencilPortalRenderer;
import net.fabricmc.api.ClientModInitializer;
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

        SeamlessPortalsConstants.LOGGER.info("Seamless Portals: Registered AFTER_TRANSLUCENT_TERRAIN stencil render hook");
    }
}
