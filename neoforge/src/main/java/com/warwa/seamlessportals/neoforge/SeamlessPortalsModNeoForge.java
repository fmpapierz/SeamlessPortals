package com.warwa.seamlessportals.neoforge;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.PortalChunkTracker;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.neoforge.network.NeoForgePlatformHelper;
import com.warwa.seamlessportals.portal.PortalManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(SeamlessPortalsConstants.MOD_ID)
public class SeamlessPortalsModNeoForge {

    private final PortalChunkTracker chunkTracker = new PortalChunkTracker();

    public SeamlessPortalsModNeoForge(IEventBus modEventBus) {
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals initializing (NeoForge)");

        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(NeoForgePlatformHelper::onRegisterPayloadHandlers);

        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        ModPayloads.registerCommon();
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // NOTE (pre-existing, not a 26.2 change): the prior `PortalRenderer.init()`
        // referenced a class that does not exist in :common — the NeoForge client
        // render path was never wired. This mod's client integration is maintained
        // on Fabric (see SeamlessPortalsClientFabric: it registers the
        // AFTER_TRANSLUCENT_TERRAIN stencil hook + the per-tick chunk/compile pumps).
        // Removed the phantom call so the NeoForge module compiles. Wiring the
        // equivalent NeoForge hooks (RenderLevelStageEvent + ClientTickEvent) is
        // separate work, intentionally out of scope for the 26.2 API port.
    }

    private void onServerStarting(ServerStartingEvent event) {
        PortalManager.getServerInstance();
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals server systems ready");
    }

    private void onServerStopping(ServerStoppingEvent event) {
        PortalManager.resetServer();
        chunkTracker.clear();
    }

    private void onServerTick(ServerTickEvent.Post event) {
        // S17 sweep (wf_5183007f-fee CONFIRMED LEAK): the block-era portal scan was ungated
        // here (Fabric registers it flag-OFF-only) — flag-ON it would populate the block-era
        // PortalManager alongside IP's engine. Today the flag is force-false off Fabric
        // (EntityPortalsFlag S13-B P4 — no config load needed; the check is safe and constant),
        // so this gate is C7-proofing: when the NeoForge IP port lands, the block-era driver
        // stays structurally dead flag-ON, matching Fabric. The mirror-buffer flush stays
        // unconditional (its only filler is self-gated; provably empty flag-ON).
        if (!com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) {
            chunkTracker.tick(event.getServer());
        }
        // Flush the coalesced live-block-mirror updates (also clears the per-tick buffer
        // the common LevelChunkSetBlockStateMixin fills, so it cannot grow unbounded).
        com.warwa.seamlessportals.chunk.BlockUpdateMirrorBuffer.flush(event.getServer());
    }
}
