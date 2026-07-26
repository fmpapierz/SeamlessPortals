package com.warwa.seamlessportals.fabric;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.PortalChunkTracker;
import com.warwa.seamlessportals.chunk.PortalEntityTracker;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.fabric.network.FabricPlatformHelper;
import com.warwa.seamlessportals.mixin.TicketTypeInvoker;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.portal.PortalManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.TicketType;
import qouteall.imm_ptl.core.IPModMain;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTickets;
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

        // S16: the peripheral portal-helper block + item ride the SAME unconditional D3 seam —
        // world-saveable registry entries must be identical in both flag states (a world saved
        // flag-ON with portal_helper blocks placed must open flag-OFF). S19-A extends the seam
        // with portal_wand + command_stick (same parity argument: stacks live in saved
        // inventories) and their stack-persisted DataComponentTypes (an unregistered component
        // type would fail the stack parse on a flag-OFF load — data loss; AND network-mandatory:
        // fabric-registry-sync marks DATA_COMPONENT_TYPE synced-by-rawID, so a flag-gated
        // registration would shift raw ids between flag states and break mixed-state joins —
        // verify wf_7e348eaa-89b). All registered but inert flag-OFF (init/initClient/TAB are
        // flag-gated; behavior entry points carry flag-OFF guards: PortalWandItem.use(),
        // CommandStickItem.doUse(), PortalWandInteraction.checkPermission).
        qouteall.imm_ptl.peripheral.PeripheralModMain.registerBlocks(
            (id, block) -> Registry.register(BuiltInRegistries.BLOCK, id, block));
        qouteall.imm_ptl.peripheral.PeripheralModMain.registerItems(
            (id, item) -> Registry.register(BuiltInRegistries.ITEM, id, item));
        qouteall.imm_ptl.peripheral.PeripheralModMain.registerDataComponents();
        // S19-D: the alt-dim generator/biome-source codecs ride the SAME unconditional D3
        // seam — level.dat serializes generators through them, so a flag-ON-created alt-dim
        // world must deserialize flag-OFF (their worldgen ACCESSOR mixins carry a matching
        // D3 carve-out in SeamlessMixinConfigPlugin). NeoForge: deliberately NOT wired —
        // consistent with the whole peripheral surface being C7-deferred there.
        qouteall.imm_ptl.peripheral.PeripheralModMain.registerChunkGenerators(
            (id, codec) -> Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id, codec));
        qouteall.imm_ptl.peripheral.PeripheralModMain.registerBiomeSources(
            (id, codec) -> Registry.register(BuiltInRegistries.BIOME_SOURCE, id, codec));
        // S19-D verify catch (wf_c18735d7-449 BLOCKER): the chaos generator's math tables
        // (FormulaGenerator selectors) must init on the SAME unconditional seam as its codec —
        // a flag-ON-created chaos world deserializes flag-OFF through the codec above, and
        // generation NPEs if the tables are empty (init was flag-ON-only). Pure static math,
        // zero registry/behavior surface; the IP-faithful flag-ON init call remains (idempotent).
        qouteall.imm_ptl.peripheral.alternate_dimension.FormulaGenerator.init();

        // ===== S13-F (crash-1 fix): imm_ptl chunk-ticket TYPE registration — UNCONDITIONAL =====
        // 26.2 TicketType is a BuiltInRegistries.TICKET_TYPE-registered record (api-map chunk-loading
        // #23); the 1.21.3 TicketType.create is GONE and a bare unregistered instance throws when handed
        // to TicketStorage (the crash: NPE in Ticket.<init>, "type" null, via addTicketWithRadius). IP's
        // ImmPtlChunkTickets.TICKET_TYPE was a static-init TicketType.create; on 26.2 that becomes a real
        // registry op that must run at REGISTRY PHASE. It is assigned HERE by mod-owned glue (D2 —
        // qouteall.* holds no loader code), through the KEEP'd TicketTypeInvoker (register() is private on
        // 26.2), UNCONDITIONAL in both flag states (D3 registries-unconditional). Shape = FLAG_LOADING |
        // FLAG_SIMULATION, NO_TIMEOUT — the exact 26.2 translation of IP's load+entity-tick ticket
        // (vanilla DRAGON's shape). Registered in both states but only EXERCISED flag-ON (addTicket runs
        // only on the IP chunk-loading path); the name "imm_ptl" is IP's own registry id and cannot
        // collide with the block-era "seamlessportals_chunk_residency".
        ImmPtlChunkTickets.TICKET_TYPE = TicketTypeInvoker.seamlessportals$invokeRegister(
            "imm_ptl", TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);

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
            // RS PASSTHROUGH (a) step 2: subscribe the seam registry to IP's portal lifecycle
            // signals. MUST run after IPModMain.init, which is where the Portal entity type and its
            // signal Events are created. Self-gates on the master lever, so with
            // -Dseamlessportals.disableAperturePassthrough=true it registers listeners that
            // immediately return rather than changing the init sequence.
            com.warwa.seamlessportals.passthrough.AperturePassthroughInit.init();
            // S16: the peripheral init (IntrinsicPortalGeneration identifiers) runs after
            // IPModMain here. Verify correction (wf_91b049a9-0c1): IP's fabric.mod.json actually
            // lists PeripheralModEntry FIRST (before the core entry) — the order is functionally
            // irrelevant for the ported subset (identifiers are only read post-init; nothing in
            // it is init-order-sensitive), so this placement stands as the tidier one-branch
            // shape. Minimal subset: everything except the portal-generation cargo is held to
            // S19 (see PeripheralModMain header).
            qouteall.imm_ptl.peripheral.PeripheralModMain.init();
            // S19-A: the creative TAB registers FLAG-ON only — tabs are not world state, and
            // the block-era baseline must not surface entity-portal features in its UI. IP's
            // PeripheralModEntry registers the tab BEFORE init(); here it sits after (the
            // flag-ON branch shape) — functionally identical because displayItems is lazy, the
            // same argument IP's own ordering note relies on for the command-stick map.
            qouteall.imm_ptl.peripheral.PeripheralModMain.registerCreativeTabs(
                (id, tab) -> Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id, tab));
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
