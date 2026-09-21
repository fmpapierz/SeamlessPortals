package com.warwa.seamlessportals.forge;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.PortalChunkTracker;
import com.warwa.seamlessportals.chunk.PortalEntityTracker;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.forge.network.ForgePlatformHelper;
import com.warwa.seamlessportals.forge.platform.ForgePlatform;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.TicketType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.network.GatherLoginConfigurationTasksEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.DataPackRegistryEvent;
import net.minecraftforge.registries.RegisterEvent;
import qouteall.imm_ptl.core.IPModMain;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTickets;

/**
 * 26.3 FORGE PORT: the MinecraftForge entry point — the twin of {@code SeamlessPortalsModNeoForge} (NF-PARITY
 * W4/W5/W19), itself full parity with {@code SeamlessPortalsModFabric}. The Fabric mod-init sequence maps onto Forge
 * as:
 * <ul>
 *   <li>config load + payload-type registration + IP init chain → the CTOR (Fabric's
 *       {@code onInitialize} timing; every registry-touching call in the chain rides a
 *       QUEUED facade — Platform argument-types/data-pack registries, PlatformHelper
 *       entity-types — drained in the events below, because Forge's registries
 *       are frozen outside the {@code UNFREEZE_DATA} .. {@code FREEZE_DATA} loading states; payload types are NOT
 *       queued on Forge — {@code ForgePlatformHelper}'s registries are live and no Forge network channel is involved,
 *       see its header);</li>
 *   <li>registry sinks (entity types, blocks, items, components, generators, tickets, tabs) → the mod-bus
 *       {@link RegisterEvent} listener. TWO kinds on Forge: the vanilla registries Forge WRAPS in a
 *       {@code ForgeRegistry} (here ENTITY_TYPE, BLOCK, DATA_COMPONENT_TYPE, ITEM — {@code GameData.init}) are
 *       LOCKED against direct writes and take {@code event.register(..)}; the ones it leaves alone (CHUNK_GENERATOR,
 *       BIOME_SOURCE, TICKET_TYPE, CREATIVE_MODE_TAB) keep the direct {@code Registry.register} the NeoForge twin
 *       uses — they are unfrozen from {@code GameData.unfreezeData} on. Registration order across registries is
 *       vanilla's ({@code GameData.postRegisterEvents}: {@code vanillaRegistryOrder} first); Forge does NOT hoist
 *       DATA_COMPONENT_TYPE before ITEM the way NeoForge does;</li>
 *   <li>client init → {@code SeamlessPortalsClientForge}, constructed from this ctor on {@code Dist.CLIENT} (Forge's
 *       {@code @Mod} has no {@code dist} attribute — see the ctor's last statement).</li>
 * </ul>
 */
@Mod(SeamlessPortalsConstants.MOD_ID)
public class SeamlessPortalsModForge {

    private final PortalChunkTracker chunkTracker = new PortalChunkTracker();
    private final PortalEntityTracker entityTracker = new PortalEntityTracker();

    // FORGE 26.3: replaces NeoForge's injected (IEventBus modEventBus, ModContainer container) ctor. javafmllanguage
    // FMLModContainer.constructMod looks up getDeclaredConstructor(FMLJavaModLoadingContext.class) (javap -c: the
    // context's own class, falling back to the no-arg ctor); the mod bus is
    // FMLJavaModLoadingContext.getModBusGroup()Lnet/minecraftforge/eventbus/api/bus/BusGroup; — EventBus 7 has no bus
    // OBJECT to add listeners to: a mod-bus event hands out its bus for a group, `XEvent.getBus(modBusGroup)`.
    public SeamlessPortalsModForge(FMLJavaModLoadingContext context) {
        BusGroup modBusGroup = context.getModBusGroup();
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals initializing (Forge)");

        // Config FIRST — before any registry event and before the flag branch below.
        // FMLPaths.CONFIGDIR is the same path Forge's own ConfigTracker uses; loaded here
        // (not at common setup) because the flag decides the whole init shape.
        SeamlessPortalsConfig.loadFrom(FMLPaths.CONFIGDIR.get());

        // Payload TYPE registration is UNCONDITIONAL (harmless idle types flag-ON;
        // identical flag-OFF). Registered through the seams — live at once on Forge, nothing to drain.
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
        // FORGE 26.3: `modEventBus.addListener(x)` becomes `XEvent.getBus(modBusGroup).addListener(x)` for the
        // IModBusEvent types (RegisterEvent, FMLCommonSetupEvent: static getBus(BusGroup) on each). Two of NeoForge's
        // five mod-bus events are NOT mod-bus events on Forge and hang off a static BUS instead:
        // DataPackRegistryEvent$NewRegistry.BUS (fired once by RegistryManager.postNewRegistryEvent, the
        // CREATE_REGISTRIES state, before UNFREEZE_DATA — NeoForge's ordering) and
        // GatherLoginConfigurationTasksEvent.BUS. RegisterPayloadHandlersEvent does not exist at all: what is left of
        // that listener (the five direct payload registrations — everything queued is live already) runs at
        // FMLCommonSetupEvent, see ForgePlatformHelper.onRegisterPayloadHandlers.
        RegisterEvent.getBus(modBusGroup).addListener(this::onRegisterRegistries);
        DataPackRegistryEvent.NewRegistry.BUS.addListener(ForgePlatform::drainDataPackRegistries);
        FMLCommonSetupEvent.getBus(modBusGroup).addListener(ForgePlatformHelper::onRegisterPayloadHandlers);
        // FORGE 26.3: LOWEST so the ONE task this listener queues (the deferred configuration-start gate) lands AFTER
        // Forge's own negotiation tasks, which the same event gathers from ForgeMod's NORMAL-priority listener
        // (javap -c ForgeMod.<init>: GatherLoginConfigurationTasksEvent.BUS.addListener(Consumer) — no priority
        // argument). EventBus 7 takes the priority as a leading byte: EventBus.addListener(BLjava/util/function/
        // Consumer;), Priority.LOWEST = -127; buildInvoker sorts listeners by descending priority (stable), so
        // registration order between the two mods' parallel constructors does not matter.
        GatherLoginConfigurationTasksEvent.BUS.addListener(
            Priority.LOWEST, ForgePlatformHelper::onRegisterConfigurationTasks);
        FMLCommonSetupEvent.getBus(modBusGroup).addListener(this::onCommonSetup);

        if (SeamlessPortalsConfig.isEntityPortals()) {
            // ===== ENTITY-PORTAL (Immersive Portals) server/common init ===================
            // E0 boot iteration 4 finding, equally true on Forge: the chain CANNOT run at ctor time —
            // Portal.<clinit> BUILDS its EntityType, and EntityType.<init> creates an
            // INTRUSIVE HOLDER in the (frozen-outside-the-window) ENTITY_TYPE registry
            // (Forge: NamespacedWrapper.createIntrusiveHolder -> validateWrite throws while `frozen`). So only
            // the data-pack-registry hoist runs here (its NewRegistry event fires BEFORE the
            // unfreeze — ForgeStatesProvider: CREATE_REGISTRIES, then UNFREEZE_DATA), and the FULL chain runs at the
            // FIRST RegisterEvent dispatch (whichever registry vanilla's order puts first — Forge hoists none),
            // i.e. inside the unfrozen window, before every registry this
            // chain feeds (ENTITY_TYPE drain, COMMAND_ARGUMENT_TYPE queue, TICKET_TYPE).
            qouteall.imm_ptl.core.portal.custom_portal_gen.CustomPortalGenManager
                .registerDataPackRegistries();
        } else {
            // ===== BLOCK-ERA driver set (flag-OFF, the legacy opt-out) ====================
            // The chunk/entity trackers + prewarm + mirror flush, mirroring Fabric's
            // else-branch. KNOWN DEVIATION (recorded): the block-era client/server payload
            // HANDLER sets remain Fabric-only statics (FabricPlatformHelper.register*Handlers)
            // — the block-era driver is scheduled for deletion at S20 and the flag has
            // defaulted ON since S17, so flag-OFF Forge gets the tracker baseline only.
            // FORGE 26.3: NeoForge.EVENT_BUS.addListener(x) -> the event's own static BUS (EventBus 7).
            ServerStartingEvent.BUS.addListener(this::onServerStarting);
            ServerStoppingEvent.BUS.addListener(this::onServerStoppingBlockEra);
            TickEvent.ServerTickEvent.Post.BUS.addListener(this::onServerTickBlockEra);

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

        // FORGE 26.3: replaces NeoForge's SECOND entry class — `@Mod(value = MOD_ID, dist = Dist.CLIENT)` on
        // SeamlessPortalsClientNeoForge, which NeoForge's FML constructs itself. Forge's @Mod has ONE member,
        // value()Ljava/lang/String; (javap: no dist), and FMLJavaModLanguageProvider collects @Mod classes into a
        // Map keyed by mod id with a keep-the-first merge (javap -c: Collectors.toMap(.., lambda$getFileVisitor$4 =
        // `return a`)), so a second @Mod class for the same id is silently dropped. The client class is therefore a
        // plain class constructed here, last, behind the dist check (fmlloader FMLEnvironment.dist). Dedicated-server
        // safe: `new` + invokespecial of an exact type needs no assignability proof, so the verifier never loads it.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            new SeamlessPortalsClientForge(context);
        }
    }

    /**
     * The registry window (mod bus, once per registry, vanilla registries unfrozen).
     * Registry sinks are legal ONLY here — everything below mirrors
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
            // RS PASSTHROUGH NF-PARITY (2026-08-30): the same two flag-ON passthrough pieces
            // Fabric wires at this exact slot (SeamlessPortalsModFabric). Everything else of
            // the passthrough system is common-side (mixins in seamlessportals-common.mixins
            // .json, e.g. ServerPlayerSeamResendMixin for the world-change re-send) — these two
            // registrations plus the payload type row in ForgePlatformHelper are the whole loader half.
            //
            // (1) Seam registry ← portal lifecycle signals. MUST run after IPModMain.init,
            // which is where the Portal entity type and its signal Events are created. Self-
            // gates on the master lever (registers listeners that immediately return when
            // -Dseamlessportals.disableAperturePassthrough=true).
            com.warwa.seamlessportals.passthrough.AperturePassthroughInit.init();
            // (2) ★ FRACTIONAL OCCUPANCY JOIN SYNC (FRACTIONAL_DESIGN.md §3) — the Forge twin of
            // Fabric's ServerPlayConnectionEvents.JOIN registration. PlayerLoggedInEvent fires
            // from the same funnel (PlayerList.placeNewPlayer -> ForgeEventFactory.firePlayerLoggedIn). Occupancy is
            // the one piece of
            // seam state a client cannot derive; a joining player gets every persisted entry of
            // every level — all dims deliberately, matching the broadcast's own policy, with
            // the client's PENDING stash absorbing dims whose ClientLevel does not exist yet.
            // FORGE 26.3: PlayerEvent$PlayerLoggedInEvent is a record on its own static BUS;
            // getEntity()Lnet/minecraft/world/entity/player/Player; keeps NeoForge's name.
            net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent.BUS.addListener(
                (net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent joinEvent) -> {
                    if (com.warwa.seamlessportals.passthrough.AperturePassthroughLever.DISABLED) {
                        return;
                    }
                    if (!(joinEvent.getEntity()
                        instanceof net.minecraft.server.level.ServerPlayer player)) {
                        return;
                    }
                    net.minecraft.server.MinecraftServer server = player.level().getServer();
                    if (server == null) {
                        return;
                    }
                    for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
                        com.warwa.seamlessportals.passthrough.SeamOccupancySavedData
                            .sendAllTo(level, player);
                    }
                });
            qouteall.imm_ptl.peripheral.PeripheralModMain.init();
            SeamlessPortalsConstants.LOGGER.info(
                "Seamless Portals: entity-portal engine initialized (Forge server/common, in-window)");
        }
        // FORGE 26.3 (the four WRAPPED registries below — ENTITY_TYPE, BLOCK, DATA_COMPONENT_TYPE, ITEM): replaces
        // NeoForge's `Registry.register(BuiltInRegistries.X, id, value)`. On Forge BuiltInRegistries.X is a
        // NamespacedWrapper over a ForgeRegistry for these keys (GameData.init creates them; GameData.getWrapper
        // installs the wrapper), GameData.vanillaSnapshot — called from vanilla's Bootstrap — LOCKS every wrapper, and
        // NamespacedWrapper.register(ResourceKey, Object, RegistrationInfo) then throws IllegalStateException("Can not
        // register to a locked registry. Modder should use Forge Register methods.") (javap -c; there is no unlock).
        // The Forge route is RegisterEvent.register(ResourceKey, Identifier, Supplier)V, which calls
        // IForgeRegistry.register(Identifier, Object)V while GameData.postRegisterEvents holds that one ForgeRegistry
        // unfrozen (unfreeze -> dispatch -> freeze, per registry).
        if (event.getRegistryKey() == Registries.ENTITY_TYPE) {
            ForgePlatformHelper.drainEntityTypeRegistrations(
                (id, type) -> event.register(Registries.ENTITY_TYPE, id, () -> type));
        }
        else if (event.getRegistryKey() == Registries.BLOCK) {
            IPModMain.registerBlocks(
                (id, block) -> event.register(Registries.BLOCK, id, () -> block));
            qouteall.imm_ptl.peripheral.PeripheralModMain.registerBlocks(
                (id, block) -> event.register(Registries.BLOCK, id, () -> block));
        }
        else if (event.getRegistryKey() == Registries.DATA_COMPONENT_TYPE) {
            // FORGE 26.3: replaces the NeoForge twin's `PeripheralModMain.registerDataComponents()`. That no-arg form
            // direct-registers into BuiltInRegistries.DATA_COMPONENT_TYPE, which on Forge is one of the LOCKED wrappers
            // (GameData.init: makeRegistry(Registries.DATA_COMPONENT_TYPE)) and would throw. RESOLVED 2026-09-20 (user
            // decision): common now has the sink-taking overload its block/item siblings always had, so the two ids
            // are no longer repeated here — same entries, same order, routed through RegisterEvent.register.
            qouteall.imm_ptl.peripheral.PeripheralModMain.registerDataComponents(
                (id, type) -> event.register(Registries.DATA_COMPONENT_TYPE, id, () -> type));
        }
        else if (event.getRegistryKey() == Registries.ITEM) {
            qouteall.imm_ptl.peripheral.PeripheralModMain.registerItems(
                (id, item) -> event.register(Registries.ITEM, id, () -> item));
        }
        // FORGE 26.3 (the four UNWRAPPED registries below — CHUNK_GENERATOR, BIOME_SOURCE, TICKET_TYPE,
        // CREATIVE_MODE_TAB): unchanged from the NeoForge twin. Forge creates no ForgeRegistry for them, so
        // BuiltInRegistries.X is the plain MappedRegistry, unfrozen by GameData.unfreezeData (UNFREEZE_DATA state)
        // until GameData.freezeData — and a direct Registry.register is what RegisterEvent.register itself does for
        // them (javap -c: forgeRegistry == null -> Registry.register(vanillaRegistry, id, value)).
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
            // the shape is the exact translation of IP's load+entity-tick ticket. On Forge
            // this MUST land in this window too (TICKET_TYPE is a plain MappedRegistry — it is
            // covered by unfreezeData). Registered in both flag states (D3), exercised flag-ON only.
            ImmPtlChunkTickets.TICKET_TYPE =
                com.warwa.seamlessportals.mixin.TicketTypeInvoker.seamlessportals$invokeRegister(
                    "imm_ptl", TicketType.NO_TIMEOUT,
                    TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
            // The block-era tickets, same window, both flag states (D3) — E0 boot fix: their
            // old <clinit> registrations were exactly the "Registry is already frozen" crash.
            PortalChunkTracker.bootstrapTicketType();
            com.warwa.seamlessportals.chunk.PortalEntityTracker.bootstrapTicketTypes();
        }
        else if (event.getRegistryKey() == Registries.COMMAND_ARGUMENT_TYPE) {
            // 26.3 (user decision 2026-09-20): the drain ForgePlatform's javadoc promises and nothing called — the
            // NeoForge twin had the same gap since 26.2 (see its branch for the full mechanism). Without it IP's three
            // argument types never reached COMMAND_ARGUMENT_TYPE; Forge serializes the command tree even on the
            // integrated server's local connection, so EVERY Forge client lost completion/validation for the /portal
            // sub-commands that use them. Both flag states have queued by now (flag-ON: the init chain at the first
            // RegisterEvent above; flag-OFF: the ctor). ForgePlatform queues RegisterEvent.register calls — the only
            // legal route into this ForgeRegistry-wrapped registry.
            ForgePlatform.drainArgumentTypeRegistrations(event);
        }
        else if (event.getRegistryKey() == Registries.CREATIVE_MODE_TAB) {
            if (SeamlessPortalsConfig.isEntityPortals()) {
                // S19-A: the creative TAB registers FLAG-ON only, mirroring Fabric — tabs are
                // not world state and not network-synced, so the flag gate is connection-safe.
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

    // ===== BLOCK-ERA global-bus handlers (registered flag-OFF only, see the ctor) ==========

    private void onServerStarting(ServerStartingEvent event) {
        PortalManager.getServerInstance();
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals server systems ready");
    }

    private void onServerStoppingBlockEra(ServerStoppingEvent event) {
        PortalManager.resetServer();
        chunkTracker.clear();
        entityTracker.clear();
    }

    private void onServerTickBlockEra(TickEvent.ServerTickEvent.Post event) {
        // Block-era trackers + prewarm + coalesced mirror flush — Fabric's else-branch body.
        // Registered only when the flag is OFF, so no runtime gate is needed here (the old
        // always-registered + gated shape predates the ctor-time flag branch).
        // FORGE 26.3: TickEvent$ServerTickEvent$Post is a record — server(), where NeoForge has getServer().
        chunkTracker.tick(event.server());
        entityTracker.tick(event.server());
        com.warwa.seamlessportals.portal.PortalManager
            .getServerInstance().tickPortalPreWarm(event.server());
        com.warwa.seamlessportals.render.PerfTimers.time("srv.blockMirrorFlush",
            () -> com.warwa.seamlessportals.chunk.BlockUpdateMirrorBuffer.flush(event.server()));
    }
}
