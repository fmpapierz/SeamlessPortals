package com.warwa.seamlessportals.neoforge;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.PortalChunkTracker;
import com.warwa.seamlessportals.chunk.PortalEntityTracker;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.neoforge.network.NeoForgePlatformHelper;
import com.warwa.seamlessportals.neoforge.platform.NeoForgePlatform;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.TicketType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import qouteall.imm_ptl.core.IPModMain;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTickets;

/**
 * NF-PARITY W4/W5/W19 (2026-08-25): the rebuilt NeoForge entry point — full parity with
 * {@code SeamlessPortalsModFabric}. The Fabric mod-init sequence maps onto NeoForge as:
 * <ul>
 *   <li>config load + payload-type queuing + IP init chain → the CTOR (Fabric's
 *       {@code onInitialize} timing; every registry-touching call in the chain rides a
 *       QUEUED facade — Platform argument-types/data-pack registries, PlatformHelper
 *       payloads/entity-types — drained in the events below, because NeoForge's registries
 *       are frozen outside the {@code RegisterEvent} window);</li>
 *   <li>direct {@code Registry.register} sinks (entity types, blocks, items, components,
 *       generators, tickets, tabs) → the mod-bus {@link RegisterEvent} listener, where
 *       {@code BuiltInRegistries} are unfrozen (CommonModLoader: unfreeze → dispatch →
 *       freeze); registration order across registries is vanilla's, with
 *       DATA_COMPONENT_TYPE hoisted before ITEM by NeoForge itself;</li>
 *   <li>client init → {@code SeamlessPortalsClientNeoForge} (dist-gated {@code @Mod}).</li>
 * </ul>
 */
@Mod(SeamlessPortalsConstants.MOD_ID)
public class SeamlessPortalsModNeoForge {

    private final PortalChunkTracker chunkTracker = new PortalChunkTracker();
    private final PortalEntityTracker entityTracker = new PortalEntityTracker();

    public SeamlessPortalsModNeoForge(IEventBus modEventBus, ModContainer container) {
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals initializing (NeoForge)");

        // Config FIRST — before any registry event and before the flag branch below.
        // FMLPaths.CONFIGDIR is the same path NeoForge's own ConfigTracker uses; loaded here
        // (not at common setup) because the flag decides the whole init shape.
        SeamlessPortalsConfig.loadFrom(FMLPaths.CONFIGDIR.get());

        // Payload TYPE registration is UNCONDITIONAL (harmless idle channels flag-ON;
        // identical flag-OFF). Queued through the seams; drained at RegisterPayloadHandlersEvent.
        ModPayloads.registerCommon();
        PlatformHelper helper = PlatformHelper.getInstance();
        helper.registerPayloads();

        // ===== WIRE 2: UNCONDITIONAL registrations (D3 save-safety) — mirrors Fabric ======
        // Entity types + placeholder/peripheral blocks/items/components/codecs are WORLD-SAVED
        // registry entries and must resolve in BOTH flag states (D3). Queued/deferred to the
        // RegisterEvent window below. See SeamlessPortalsModFabric's WIRE 2 note for the full
        // argument — including why registrations are never SPAWNED/PLACED flag-OFF.
        helper.registerEntityTypes(IPModMain::registerEntityTypes);

        // ===== mod-bus wiring =============================================================
        modEventBus.addListener(this::onRegisterRegistries);
        modEventBus.addListener(NeoForgePlatform::drainDataPackRegistries);
        modEventBus.addListener(NeoForgePlatformHelper::onRegisterPayloadHandlers);
        modEventBus.addListener(NeoForgePlatformHelper::onRegisterConfigurationTasks);
        modEventBus.addListener(this::onCommonSetup);

        if (SeamlessPortalsConfig.isEntityPortals()) {
            // ===== ENTITY-PORTAL (Immersive Portals) server/common init ===================
            // E0 boot iteration 4 finding: the chain CANNOT run at ctor time on NeoForge —
            // Portal.<clinit> BUILDS its EntityType, and EntityType.<init> creates an
            // INTRUSIVE HOLDER in the (frozen-outside-the-window) ENTITY_TYPE registry
            // ("Registry is already frozen" at MappedRegistry.createIntrusiveHolder). So only
            // the data-pack-registry hoist runs here (its NewRegistry event fires BEFORE the
            // unfreeze window, CommonModLoader.begin:52-55), and the FULL chain runs at the
            // FIRST RegisterEvent dispatch (ATTRIBUTE — GameData.getRegistrationOrder:120
            // hoists it first), i.e. inside the unfrozen window, before every registry this
            // chain feeds (ENTITY_TYPE drain, COMMAND_ARGUMENT_TYPE queue, TICKET_TYPE).
            qouteall.imm_ptl.core.portal.custom_portal_gen.CustomPortalGenManager
                .registerDataPackRegistries();
        } else {
            // ===== BLOCK-ERA driver set (flag-OFF, the legacy opt-out) ====================
            // The chunk/entity trackers + prewarm + mirror flush, mirroring Fabric's
            // else-branch. KNOWN DEVIATION (recorded): the block-era client/server payload
            // HANDLER sets remain Fabric-only statics (FabricPlatformHelper.register*Handlers)
            // — the block-era driver is scheduled for deletion at S20 and the flag has
            // defaulted ON since S17, so flag-OFF NeoForge gets the tracker baseline only.
            NeoForge.EVENT_BUS.addListener(this::onServerStarting);
            NeoForge.EVENT_BUS.addListener(this::onServerStoppingBlockEra);
            NeoForge.EVENT_BUS.addListener(this::onServerTickBlockEra);

            // D3 registry parity — flag-OFF mirror of the flag-ON-registered argument types
            // and IP payload TYPES, identical to Fabric's else-branch (same seams; the
            // configuration payloads are part of ImmPtlNetworkConfig.init and are mirrored by
            // the seam's queue-registration here being TYPE-only).
            qouteall.imm_ptl.core.commands.AxisArgumentType.init();
            qouteall.imm_ptl.core.commands.SubCommandArgumentType.init();
            qouteall.imm_ptl.core.commands.TimingFunctionArgumentType.init();

            helper.registerServerboundPayload(
                qouteall.q_misc_util.ImplRemoteProcedureCall.C2SRPCPayload.TYPE,
                qouteall.q_misc_util.ImplRemoteProcedureCall.C2SRPCPayload.CODEC);
            helper.registerClientboundPayload(
                qouteall.q_misc_util.ImplRemoteProcedureCall.S2CRPCPayload.TYPE,
                qouteall.q_misc_util.ImplRemoteProcedureCall.S2CRPCPayload.CODEC);
            helper.registerClientboundPayload(
                qouteall.q_misc_util.MiscNetworking.DimIdSyncPacket.TYPE,
                qouteall.q_misc_util.MiscNetworking.DimIdSyncPacket.CODEC);
            helper.registerServerboundPayload(
                qouteall.imm_ptl.core.network.ImmPtlNetworking.TeleportPacket.TYPE,
                qouteall.imm_ptl.core.network.ImmPtlNetworking.TeleportPacket.CODEC);
            helper.registerClientboundPayload(
                qouteall.imm_ptl.core.network.ImmPtlNetworking.GlobalPortalSyncPacket.TYPE,
                qouteall.imm_ptl.core.network.ImmPtlNetworking.GlobalPortalSyncPacket.CODEC);
            helper.registerClientboundPayload(
                qouteall.imm_ptl.core.network.ImmPtlNetworking.PortalSyncPacket.TYPE,
                qouteall.imm_ptl.core.network.ImmPtlNetworking.PortalSyncPacket.CODEC);
        }
    }

    /**
     * The registry window (mod bus, once per registry, {@code BuiltInRegistries} unfrozen).
     * Direct {@code Registry.register} sinks are legal ONLY here — everything below mirrors
     * the direct-sink calls Fabric makes at mod-init time (where its registries are mutable).
     */
    /** Latch: the flag-ON IP init chain runs exactly once, at the FIRST RegisterEvent. */
    private boolean ipInitChainRan = false;

    private void onRegisterRegistries(RegisterEvent event) {
        if (!ipInitChainRan && SeamlessPortalsConfig.isEntityPortals()) {
            ipInitChainRan = true;
            // DEPENDENCY_ORDER §4.2, byte-mirroring SeamlessPortalsModFabric's flag-ON
            // branch: the MiscUtilModEntry sequence THEN IPModMain.init THEN
            // PeripheralModMain.init. Runs INSIDE the unfrozen registry window (see the ctor
            // note) — EntityType statics, ticket types, and every queued facade fill before
            // their own registry's dispatch reaches them.
            qouteall.q_misc_util.ImplRemoteProcedureCall.init();
            qouteall.q_misc_util.MiscNetworking.init();
            qouteall.q_misc_util.dimension.DimensionIntId.init();
            qouteall.imm_ptl.core.IPModMain.init();
            qouteall.imm_ptl.peripheral.PeripheralModMain.init();
            SeamlessPortalsConstants.LOGGER.info(
                "Seamless Portals: entity-portal engine initialized (NeoForge server/common, in-window)");
        }
        if (event.getRegistryKey() == Registries.ENTITY_TYPE) {
            NeoForgePlatformHelper.drainEntityTypeRegistrations(
                (id, type) -> Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type));
        }
        else if (event.getRegistryKey() == Registries.BLOCK) {
            IPModMain.registerBlocks(
                (id, block) -> Registry.register(BuiltInRegistries.BLOCK, id, block));
            qouteall.imm_ptl.peripheral.PeripheralModMain.registerBlocks(
                (id, block) -> Registry.register(BuiltInRegistries.BLOCK, id, block));
        }
        else if (event.getRegistryKey() == Registries.DATA_COMPONENT_TYPE) {
            // Internally direct-registers into BuiltInRegistries.DATA_COMPONENT_TYPE — legal
            // in this window. NeoForge hoists DATA_COMPONENT_TYPE before ITEM itself
            // (GameData.getRegistrationOrder: "Item depends on data components").
            qouteall.imm_ptl.peripheral.PeripheralModMain.registerDataComponents();
        }
        else if (event.getRegistryKey() == Registries.ITEM) {
            qouteall.imm_ptl.peripheral.PeripheralModMain.registerItems(
                (id, item) -> Registry.register(BuiltInRegistries.ITEM, id, item));
        }
        else if (event.getRegistryKey() == Registries.CHUNK_GENERATOR) {
            // S19-D: alt-dim generator codecs — unconditional D3 seam (level.dat parity).
            qouteall.imm_ptl.peripheral.PeripheralModMain.registerChunkGenerators(
                (id, codec) -> Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id, codec));
            // The chaos generator's math tables must init on the SAME unconditional seam
            // (S19-D verify catch — flag-OFF deserialization generates through them).
            qouteall.imm_ptl.peripheral.alternate_dimension.FormulaGenerator.init();
        }
        else if (event.getRegistryKey() == Registries.BIOME_SOURCE) {
            qouteall.imm_ptl.peripheral.PeripheralModMain.registerBiomeSources(
                (id, codec) -> Registry.register(BuiltInRegistries.BIOME_SOURCE, id, codec));
        }
        else if (event.getRegistryKey() == Registries.TICKET_TYPE) {
            // S13-F: the imm_ptl chunk-ticket TYPE — 26.2 TicketType is a registered record;
            // the shape is the exact translation of IP's load+entity-tick ticket. On NeoForge
            // this MUST land in this window (MappedRegistry extends BaseMappedRegistry — it is
            // covered by unfreeze). Registered in both flag states (D3), exercised flag-ON only.
            ImmPtlChunkTickets.TICKET_TYPE =
                com.warwa.seamlessportals.mixin.TicketTypeInvoker.seamlessportals$invokeRegister(
                    "imm_ptl", TicketType.NO_TIMEOUT,
                    TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
            // The block-era tickets, same window, both flag states (D3) — E0 boot fix: their
            // old <clinit> registrations were exactly the "Registry is already frozen" crash.
            PortalChunkTracker.bootstrapTicketType();
            com.warwa.seamlessportals.chunk.PortalEntityTracker.bootstrapTicketTypes();
        }
        else if (event.getRegistryKey() == Registries.CREATIVE_MODE_TAB) {
            if (SeamlessPortalsConfig.isEntityPortals()) {
                // S19-A: the creative TAB registers FLAG-ON only, mirroring Fabric — tabs are
                // not world state and not network-synced (CREATIVE_MODE_TAB is not in
                // NeoForge's VANILLA_SYNC_REGISTRIES), so the flag gate is connection-safe.
                qouteall.imm_ptl.peripheral.PeripheralModMain.registerCreativeTabs(
                    (id, tab) -> Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id, tab));
            }
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // Nothing left here — the init chain runs in the ctor (Fabric's onInitialize timing)
        // and the registry work runs in the RegisterEvent window. Kept as an anchor for
        // deferred-work needs (event.enqueueWork) during later parity rounds.
    }

    // ===== BLOCK-ERA game-bus handlers (registered flag-OFF only, see the ctor) ==========

    private void onServerStarting(ServerStartingEvent event) {
        PortalManager.getServerInstance();
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals server systems ready");
    }

    private void onServerStoppingBlockEra(ServerStoppingEvent event) {
        PortalManager.resetServer();
        chunkTracker.clear();
        entityTracker.clear();
    }

    private void onServerTickBlockEra(ServerTickEvent.Post event) {
        // Block-era trackers + prewarm + coalesced mirror flush — Fabric's else-branch body.
        // Registered only when the flag is OFF, so no runtime gate is needed here (the old
        // always-registered + gated shape predates the ctor-time flag branch).
        chunkTracker.tick(event.getServer());
        entityTracker.tick(event.getServer());
        com.warwa.seamlessportals.portal.PortalManager
            .getServerInstance().tickPortalPreWarm(event.getServer());
        com.warwa.seamlessportals.render.PerfTimers.time("srv.blockMirrorFlush",
            () -> com.warwa.seamlessportals.chunk.BlockUpdateMirrorBuffer.flush(event.getServer()));
    }
}
