package com.warwa.seamlessportals.fabric;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.fabric.network.FabricPlatformHelper;
import com.warwa.seamlessportals.mixin.TicketTypeInvoker;
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


    @Override
    public void onInitialize() {
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals initializing (Fabric)");

        // S20 increment 4: SeamlessPortalsConfig is deleted and with it the
        // seamlessportals.properties load. Its six knobs were block-era
        // (portalRenderDistance/entityLoadDistance/maxPortalRenderDepth/enablePortalRendering/
        // unboundedClientChunkStore/speculativePrewarm) and had no surviving reader after the
        // block-era deletion — SeamlessConfigScreen was the last one — and the seventh key,
        // entityPortals, was the flag. The mod's live configuration is IP's own IPConfig
        // (config/immersive_portals.json, Cloth screen via IPModMenuConfigEntry).
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

        // ===== ENTITY-PORTAL (Immersive Portals) server/common init — S13 step 4 ==============
        // DEPENDENCY_ORDER §4.2 init order: the MiscUtilModEntry sequence
        // (ImplRemoteProcedureCall.init → MiscNetworking.init → DimensionIntId.init) THEN
        // IPModMain.init (networking → global portals → teleport → collision → commands →
        // ServerTaskList → CustomPortalGenManager → config). S20: this was the flag-ON branch of a
        // D3 pure gate; the flag is gone and it is the only init path. WIRE 2 (S13 step 5) adds the
        // entity-type / placeholder-block / argument-type / payload registration + the
        // entity-renderer seam above (kept UNCONDITIONAL: they are world-save registry entries).
        qouteall.q_misc_util.ImplRemoteProcedureCall.init();
        qouteall.q_misc_util.MiscNetworking.init();
        qouteall.q_misc_util.dimension.DimensionIntId.init();
        qouteall.imm_ptl.core.IPModMain.init();
        // S16: the peripheral init (IntrinsicPortalGeneration identifiers) runs after
        // IPModMain here. Verify correction (wf_91b049a9-0c1): IP's fabric.mod.json actually
        // lists PeripheralModEntry FIRST (before the core entry) — the order is functionally
        // irrelevant for the ported subset (identifiers are only read post-init; nothing in
        // it is init-order-sensitive), so this placement stands. Minimal subset: everything
        // except the portal-generation cargo is held to S19 (see PeripheralModMain header).
        qouteall.imm_ptl.peripheral.PeripheralModMain.init();
        // S19-A: the creative TAB. It registered flag-ON only (tabs are not world state, and the
        // block-era baseline must not surface entity-portal features in its UI); post-S20 it is
        // simply registered. IP's PeripheralModEntry registers the tab BEFORE init(); here it sits
        // after — functionally identical because displayItems is lazy, the same argument IP's own
        // ordering note relies on for the command-stick map.
        qouteall.imm_ptl.peripheral.PeripheralModMain.registerCreativeTabs(
            (id, tab) -> Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id, tab));
        SeamlessPortalsConstants.LOGGER.info(
            "Seamless Portals: entity-portal engine initialized (server/common)");
    }
}
