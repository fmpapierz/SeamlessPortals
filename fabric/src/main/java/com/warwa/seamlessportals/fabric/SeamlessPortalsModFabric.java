package com.warwa.seamlessportals.fabric;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.PortalChunkTracker;
import com.warwa.seamlessportals.chunk.PortalEntityTracker;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.fabric.network.FabricPlatformHelper;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.portal.PortalManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import qouteall.imm_ptl.core.IPModMain;
import qouteall.imm_ptl.core.commands.AxisArgumentType;
import qouteall.imm_ptl.core.commands.SubCommandArgumentType;
import qouteall.imm_ptl.core.commands.TimingFunctionArgumentType;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;
import qouteall.imm_ptl.core.network.ImmPtlNetworking;
import qouteall.q_misc_util.ImplRemoteProcedureCall;
import qouteall.q_misc_util.MiscNetworking;

public class SeamlessPortalsModFabric implements ModInitializer {

    private final PortalChunkTracker chunkTracker = new PortalChunkTracker();
    private final PortalEntityTracker entityTracker = new PortalEntityTracker();

    @Override
    public void onInitialize() {
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals initializing (Fabric)");

        // Load the configurable knob(s) (portalRenderDistance = dest loading/mesh depth) — this also
        // round-trips the entityPortals master switch into the properties file. UNCONDITIONAL: config
        // load is identical in both flag states.
        SeamlessPortalsConfig.loadFrom(
            net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());

        // Payload TYPE registration is UNCONDITIONAL (harmless idle channels flag-ON; identical
        // flag-OFF). Only the SENDERS/HANDLERS are driver-gated below.
        ModPayloads.registerCommon();

        FabricPlatformHelper helper = new FabricPlatformHelper();
        helper.registerPayloads();

        // ===== WIRE 2 (S13 step 5): UNCONDITIONAL registrations (D3 save-safety) ===============
        // The Portal entity-type family (incl. all global-portal subtypes, LoadingIndicatorEntity,
        // and the BreakablePortalEntity subclasses NetherPortalEntity/GeneralBreakablePortal) and
        // PortalPlaceholderBlock are WORLD-SAVED registry entries, so they MUST resolve in BOTH
        // flag states or a world saved flag-ON breaks when opened flag-OFF (D3: "registries must
        // not differ between flag states or world saves break on flips"). They are registered but
        // never SPAWNED/PLACED flag-OFF — behavior stays flag-gated — so this adds registry
        // entries only, no block-era behavior change. It also underwrites the client's
        // Minecraft.selfTest() (IS_RUNNING_IN_IDE): EntityRenderers.validateRegistrations() throws
        // unless every registered entity type has a renderer, and the matching renderers ride the
        // same-shaped UNCONDITIONAL seam in SeamlessPortalsClientFabric. Routed through the S0
        // registry seams exactly like IP's (unported) IPModEntry:18-22 (Appendix A.9).
        helper.registerEntityTypes(IPModMain::registerEntityTypes);
        IPModMain.registerBlocks(
            (id, block) -> Registry.register(BuiltInRegistries.BLOCK, id, block));

        if (SeamlessPortalsConfig.isEntityPortals()) {
            // ===== ENTITY-PORTAL (Immersive Portals) server/common init — S13 step 4 ==============
            // DEPENDENCY_ORDER §4.2 init order: the MiscUtilModEntry sequence
            // (ImplRemoteProcedureCall.init → MiscNetworking.init → DimensionIntId.init) THEN
            // IPModMain.init (networking → global portals → teleport → collision → commands →
            // ServerTaskList → CustomPortalGenManager → config). Only reached when entityPortals=true;
            // flag-OFF this whole branch is never class-loaded, so the block-era baseline is byte-
            // unchanged (D3 pure gate). WIRE 2 (S13 step 5) adds the UNCONDITIONAL entity-type /
            // placeholder-block / argument-type / payload registration + the entity-renderer seam.
            qouteall.q_misc_util.ImplRemoteProcedureCall.init();
            qouteall.q_misc_util.MiscNetworking.init();
            qouteall.q_misc_util.dimension.DimensionIntId.init();
            qouteall.imm_ptl.core.IPModMain.init();
            SeamlessPortalsConstants.LOGGER.info(
                "Seamless Portals: entity-portal engine initialized (server/common)");
        } else {
            // ===== BLOCK-ERA driver set (flag-OFF, the shipping baseline — UNCHANGED) =============
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

            // ===== WIRE 2 (S13 step 5): D3 registry parity — flag-OFF mirror =====================
            // The command argument types and the IP payload TYPES are registered flag-ON inside
            // IPModMain.init / the q_misc init methods (the flag-ON branch above). This block is
            // their flag-OFF counterpart so the argument-type + payload registries are IDENTICAL
            // between flag states (D3: "registries must not differ between flag states"). This
            // else-branch and the flag-ON init are MUTUALLY EXCLUSIVE, so nothing is registered
            // twice in either session. Only the registry (TYPE) half is mirrored: the payload
            // HANDLERS / configuration connection events / the argument-typed COMMANDS are IP-driver
            // BEHAVIOR and are NOT mirrored — they stay flag-ON only, so flag-OFF behavior is
            // unchanged (safety contract). No ID collisions: IP uses the imm_ptl:/iportal:
            // namespaces, the block-era ModPayloads use seamlessportals:. Registered here but never
            // exercised flag-OFF (no IP sender/handler is wired), i.e. inert idle channels.
            AxisArgumentType.init();
            SubCommandArgumentType.init();
            TimingFunctionArgumentType.init();

            helper.registerServerboundPayload(
                ImplRemoteProcedureCall.C2SRPCPayload.TYPE, ImplRemoteProcedureCall.C2SRPCPayload.CODEC);
            helper.registerClientboundPayload(
                ImplRemoteProcedureCall.S2CRPCPayload.TYPE, ImplRemoteProcedureCall.S2CRPCPayload.CODEC);
            helper.registerClientboundPayload(
                MiscNetworking.DimIdSyncPacket.TYPE, MiscNetworking.DimIdSyncPacket.CODEC);
            helper.registerServerboundPayload(
                ImmPtlNetworking.TeleportPacket.TYPE, ImmPtlNetworking.TeleportPacket.CODEC);
            helper.registerClientboundPayload(
                ImmPtlNetworking.GlobalPortalSyncPacket.TYPE, ImmPtlNetworking.GlobalPortalSyncPacket.CODEC);
            helper.registerClientboundPayload(
                ImmPtlNetworking.PortalSyncPacket.TYPE, ImmPtlNetworking.PortalSyncPacket.CODEC);
            PayloadTypeRegistry.clientboundConfiguration().register(
                ImmPtlNetworkConfig.S2CConfigStartPacket.TYPE, ImmPtlNetworkConfig.S2CConfigStartPacket.CODEC);
            PayloadTypeRegistry.serverboundConfiguration().register(
                ImmPtlNetworkConfig.C2SConfigCompletePacket.TYPE, ImmPtlNetworkConfig.C2SConfigCompletePacket.CODEC);
        }
    }
}
