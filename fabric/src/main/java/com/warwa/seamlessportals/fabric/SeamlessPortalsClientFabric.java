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

        // Following IP: hook AFTER solid features, BEFORE translucent terrain.
        // Portal rendering includes its own translucent pass for the destination world.
        // Rendering here ensures destination blocks (including translucent) render
        // correctly within the stencil mask, then the main world's translucent pass
        // renders on top (excluding the portal area via depth shield).
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(context -> {
            StencilPortalRenderer.renderPortals();
        });

        SeamlessPortalsConstants.LOGGER.info("Seamless Portals: Registered AFTER_SOLID_FEATURES render hook (IP architecture)");
    }
}
