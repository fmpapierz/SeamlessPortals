package com.warwa.seamlessportals.forge;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.EntityPortalsFlag;
import com.warwa.seamlessportals.client.PortalEntityRenderers;
import com.warwa.seamlessportals.client.SeamlessConfigScreen;
import com.warwa.seamlessportals.compat.RenderCompatGating;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.forge.network.ForgePlatformHelper;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * 26.3 FORGE PORT: the MinecraftForge CLIENT entry point — the twin of {@code SeamlessPortalsClientNeoForge}
 * (NF-PARITY W6/W7/W19), itself full parity with {@code SeamlessPortalsClientFabric}. NOT an {@code @Mod} class:
 * Forge's {@code @Mod} has no {@code dist} attribute and keeps one class per mod id, so
 * {@code SeamlessPortalsModForge}'s ctor constructs this one behind {@code FMLEnvironment.dist == Dist.CLIENT}.
 * Timing map:
 * <ul>
 *   <li>renderer-queue fill + listeners → the ctor. {@code RegisterRenderers} is
 *       posted from {@code Minecraft.<init>} ({@code ForgeHooksClient.initClientHooks}, javap -c @2812) BEFORE
 *       {@code FMLClientSetupEvent} (the LOAD phase, which {@code ClientModLoader} runs from its reload listener
 *       during the first resource reload), so the queue must be full at ctor time — it is
 *       (the ctor runs during mod construction — {@code ClientModLoader.begin}, @1573 — long before).</li>
 *   <li>the IP client init chain → {@code FMLClientSetupEvent.enqueueWork} (main thread;
 *       {@code Minecraft.getInstance()} exists, which is
 *       exactly why {@code IPModMainClient}'s GL block already wraps itself in
 *       {@code mc.execute}). Fabric runs the same chain from its client entrypoint
 *       mid-{@code Minecraft.<init>}.</li>
 *   <li>the flag-ON render dispatch → NOT an event on Forge: MinecraftForge 26.3-66.0.2 has no level-render stage
 *       event at all. The slot NeoForge reaches through {@code RenderLevelStageEvent.AfterTranslucentBlocks} (the
 *       exact-instruction match of Fabric's AFTER_TRANSLUCENT_TERRAIN, immediately after
 *       {@code renderGroup(TRANSLUCENT, ...)}) is driven from :common — see WIRE 3 below.</li>
 * </ul>
 */
public class SeamlessPortalsClientForge {

    // FORGE 26.3: replaces NeoForge's injected (IEventBus modEventBus, ModContainer container) — the same context
    // object the main class receives: getModBusGroup()Lnet/minecraftforge/eventbus/api/bus/BusGroup; and
    // getContainer()Lnet/minecraftforge/fml/javafmlmod/FMLModContainer;.
    public SeamlessPortalsClientForge(FMLJavaModLoadingContext context) {
        BusGroup modBusGroup = context.getModBusGroup();
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals client initializing (Forge)");

        // ===== WIRE 2: UNCONDITIONAL entity-renderer registration (both flag states) =====
        // Same rationale as Fabric's call-site note: entity types register unconditionally
        // (D3), so their renderers must too. registerAll() runs INSIDE the event listener,
        // not at ctor time — it references Portal.ENTITY_TYPE and friends, whose <clinit>
        // BUILDS the types (intrusive holders — frozen-registry crash before the window; the
        // E0 boot lesson). By RegisterRenderers (Minecraft.<init>) they are long initialized.
        // FORGE 26.3: EntityRenderersEvent$RegisterRenderers is NOT a mod-bus event on Forge 66 — a record on its own
        // static BUS (SelfDestructing: the bus disposes itself after the one post). registerEntityRenderer(
        // Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/client/renderer/entity/EntityRendererProvider;)V has
        // NeoForge's name and shape.
        EntityRenderersEvent.RegisterRenderers.BUS.addListener((EntityRenderersEvent.RegisterRenderers event) -> {
            PortalEntityRenderers.registerAll();
            ForgePlatformHelper.drainEntityRendererRegistrations(event::registerEntityRenderer);
        });

        // FORGE 26.3: FMLClientSetupEvent IS a mod-bus event — static getBus(BusGroup).
        FMLClientSetupEvent.getBus(modBusGroup).addListener(this::onClientSetup);
        // NF-PARITY L6 fix #2: the secondary-world reload FORWARDER registers inside
        // the loader's listener window. FORGE 26.3: replaces the mod-bus AddClientReloadListenersEvent —
        // RegisterClientReloadListenersEvent, static BUS, posted first thing in ForgeHooksClient.initClientHooks.
        // (Forge does not freeze the list afterwards; see the note on ForgeClientPlatform.)
        RegisterClientReloadListenersEvent.BUS.addListener(
            com.warwa.seamlessportals.forge.platform.ForgeClientPlatform::onAddReloadListeners);

        // Mod-list config button (the ModMenu equivalent) — same flag route as
        // fabric ModMenuIntegration: flag-ON -> IP's cloth screen; flag-OFF -> the vanilla
        // block-era screen (moved to :common this stage).
        // FORGE 26.3: replaces container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, parent) ->
        // ..). Forge's extension point is the RECORD ConfigScreenHandler$ConfigScreenFactory(BiFunction<Minecraft,
        // Screen, Screen>), registered through ModContainer.registerExtensionPoint(Class, Supplier) — so the factory
        // is wrapped in a supplier and its first lambda parameter is the Minecraft instance, not the mod container.
        // On Forge createClothConfigScreen resolves against this module's own me.shedaniel.autoconfig shim (Cloth
        // Config has no Forge build), whose AutoConfigClient builds the module's NATIVE config screen
        // (me.shedaniel.autoconfig.gui.NativeConfigScreen — vanilla widgets, driven by the same annotations). Until
        // 2026-09-21 the shim handed the PARENT screen back, so this button did nothing (user report).
        context.getContainer().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> EntityPortalsFlag.isOn()
                ? qouteall.imm_ptl.core.platform_specific.IPConfigGUI.createClothConfigScreen(parent)
                : new SeamlessConfigScreen(parent)));

        // ★ SEAM OCCUPANCY RECEIVER — UNCONDITIONAL, deliberately ABOVE the flag branch (the
        // exact Fabric placement + rationale: the seam is a flag-ON feature, so its receiver
        // cannot live in the flag-OFF handler set; harmless flag-OFF — occupancy never
        // arrives). Routed through the PlatformHelper seam: the Forge receive dispatcher
        // (ForgePlatformHelper.dispatchClientbound) already hops play handlers onto the client main thread
        // (Minecraft.execute), which IS Fabric's context.client().execute threading. NF-PARITY 2026-08-30.
        com.warwa.seamlessportals.network.PlatformHelper.getInstance().registerClientPayloadHandler(
            com.warwa.seamlessportals.network.ModPayloads.SeamOccupancyPayload.TYPE,
            (payload, client) -> com.warwa.seamlessportals.passthrough.SeamOccupancyClient.apply(
                payload.dimensionId(), payload.packedPos(), (byte) payload.mask(),
                payload.secondaryStateId(), (byte) payload.secondaryHalf()));
        // ★ PENDING flush driver (the live-relog fix, Fabric twin): the JOIN burst lands before
        // the joining client's level exists and parks in the PENDING stash — which would
        // otherwise only drain on the NEXT packet, i.e. never after a quiet relog. One branch
        // per tick when empty.
        // FORGE 26.3: NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> ..) -> the event's own static BUS,
        // TickEvent$ClientTickEvent$Post.BUS (EventBus 7).
        TickEvent.ClientTickEvent.Post.BUS.addListener((TickEvent.ClientTickEvent.Post event) ->
            com.warwa.seamlessportals.passthrough.SeamOccupancyClient.flushPendingTick());

        if (SeamlessPortalsConfig.isEntityPortals()) {
            // ===== WIRE 3: flag-ON render DISPATCH ========================================
            // Byte-mirrors SeamlessPortalsClientFabric's AFTER_TRANSLUCENT_TERRAIN driver,
            // including the census witness and the re-entrancy guard (LOAD-BEARING: a layer-0
            // nested render would otherwise recurse — see the Fabric driver's TP-XDIM note).
            // 26.3: NeoForge now posts this event INSIDE vanilla's open "Main" render pass and hands it over
            // (RenderLevelStageEvent.AfterTranslucentBlocks#getRenderPass); on 26.2 no pass was open here. The portal driver
            // opens passes, clears and copies, which the 26.3 front end refuses while a pass is open — so the listener
            // first SUSPENDS the event's pass (com.warwa.seamlessportals.render.MainPassSplit, full citation there);
            // LevelRendererMainPassSplitMixin reopens one before vanilla's next draw. LOWEST = run after every other
            // mod's listener, which legitimately draws into that pass and must never be handed a closed one;
            // same-priority listeners still run in registration order, so driver-then-band-painter is preserved.
            // FORGE 26.3: NO listener is registered here. net/minecraftforge/client/event/RenderLevelStageEvent does
            // not exist in forge-26.3-66.0.2.jar, and javap -c of the Forge-patched LevelRenderer shows no
            // net/minecraftforge reference inside executeSolid / executeClassicTransparency / executeOit (the class's
            // only Forge hooks are FramePassManager.insertForgePasses in render() — whole frame-graph passes added
            // AFTER vanilla's, via AddFramePassEvent — and the block-outline callback). The portal driver (this
            // listener's body) is run from :common instead, at the same instruction, by the Forge-only mixin
            // com.warwa.seamlessportals.mixin.client.LevelRendererForgeAfterTranslucentSlotMixin ->
            // com.warwa.seamlessportals.render.OitPathPortalSlot.onAfterTranslucentTerrainWithoutLoaderEvent(), which
            // suspends the live pass first exactly as this listener does.
            // ENGINE STAGE 2b — the band painter's hook (Fabric twin, NF-PARITY 2026-08-30):
            // the SECOND AfterTranslucentBlocks registration, immediately after the portal
            // driver's — the NF bus invokes same-priority listeners in registration order, so
            // this runs AFTER every portal pass of the frame, and it runs EVERY frame
            // regardless of whether any pass executed (the design PROHIBITS the doRenderPortal
            // epilogue). Thin timing driver only; all logic is common-side.
            // FORGE 26.3: NO listener here either — the same common-side slot runs
            // SeamBandPainter.onAfterPortalPasses() right after the driver, every frame.
            // The BEFORE_TRANSLUCENT_TERRAIN clip-bracket + seam-clip sites have NO NeoForge
            // event in the gap — driven by the NEOFORGE_ONLY mixin
            // MixinLevelRenderer_ClipBracketMainPassNeoForge instead (W21), which calls BOTH
            // in Fabric's registration order (PerEntityClipBracket, then SeamClipRenderer).
            // FORGE 26.3: Forge has no event in that gap either (see above); the mixin plugin applies
            // qouteall.imm_ptl.core.mixin.client.render.MixinLevelRenderer_ClipBracketMainPassNeoForge on Forge too.
            // ===== SEAM DEST-END AMBIENCE (stitched-space contract item 3) — Fabric twin. ====
            // Same-dim portal destinations are display-tick dead by construction (vanilla
            // samples ±31 blocks around the PLAYER); this display-ticks nearby mirrorable
            // portals' dest regions with the camera-distance gate defeated. ClientTickEvent
            // .Post = the END_CLIENT_TICK slot (after vanilla's animateTick + engine tick);
            // queued spawns drain next engine tick. Logic is common-side (SeamDestAmbience).
            TickEvent.ClientTickEvent.Post.BUS.addListener((TickEvent.ClientTickEvent.Post event) ->
                com.warwa.seamlessportals.render.SeamDestAmbience.tick(Minecraft.getInstance()));
        } else {
            // ===== BLOCK-ERA client driver set (flag-OFF, the legacy opt-out) =============
            // The stencil composite + per-tick pumps, mirroring Fabric's else-branch (same
            // :common bodies). KNOWN DEVIATION (recorded): the block-era client payload
            // HANDLERS remain Fabric-only — see the server class's flag-OFF note.
            // 26.3: pass-free + LOWEST, same reason as the flag-ON driver above (MainPassSplit).
            // FORGE 26.3: NO stencil listener is registered — no event exists (see WIRE 3). The common-side slot
            // (LevelRendererForgeAfterTranslucentSlotMixin -> OitPathPortalSlot) runs the block-era driver
            // (TpXdimFrameCensus.noteF1Driver("stencil"); StencilPortalRenderer.renderPortals()) when the flag is OFF.

            TickEvent.ClientTickEvent.Post.BUS.addListener((TickEvent.ClientTickEvent.Post event) -> {
                com.warwa.seamlessportals.render.PerfTimers.time("drainChunks",
                    com.warwa.seamlessportals.chunk.RedirectedPacketApplier::drainPending);
                com.warwa.seamlessportals.render.PerfTimers.time("advanceCompilePipelines",
                    com.warwa.seamlessportals.client.PortalWorldManager::advanceCompilePipelines);
                com.warwa.seamlessportals.render.PerfTimers.time("syncTime",
                    com.warwa.seamlessportals.client.PortalWorldManager::syncTimeToCachedLevels);
                com.warwa.seamlessportals.render.PerfTimers.time("tickRemoteWorlds",
                    com.warwa.seamlessportals.client.PortalWorldManager::tickRemoteWorlds);
                com.warwa.seamlessportals.render.PerfTimers.time("tickCachedParticles",
                    com.warwa.seamlessportals.client.PortalWorldManager::tickCachedParticles);
                com.warwa.seamlessportals.render.PerfTimers.time("evictUnboundedStores",
                    com.warwa.seamlessportals.client.PortalWorldManager::evictUnboundedStores);
            });

            // FORGE 26.3: the message names the mixin slot — there is no AfterTranslucentBlocks hook to have registered.
            SeamlessPortalsConstants.LOGGER.info(
                "Seamless Portals: block-era stencil render slot is driven by LevelRendererForgeAfterTranslucentSlotMixin (Forge)");
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        if (!SeamlessPortalsConfig.isEntityPortals()) {
            return;
        }
        // ===== ENTITY-PORTAL client init — the exact Fabric order, on the main thread ======
        // FMLClientSetupEvent is parallel-dispatch; enqueueWork serializes onto the main
        // thread (Minecraft.getInstance() is non-null by then,
        // matching Fabric's mid-ctor client-entrypoint window).
        // FORGE 26.3: ParallelDispatchEvent.enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;
        // — NeoForge's name and meaning.
        event.enqueueWork(() -> {
            qouteall.q_misc_util.ImplRemoteProcedureCall.initClient();
            qouteall.q_misc_util.MiscNetworking.initClient();
            qouteall.imm_ptl.core.IPModMainClient.init();
            qouteall.imm_ptl.peripheral.PeripheralModMain.initClient();
            // AFTER IPModMainClient.init (IPConfig loaded) so the force wins over the
            // config-derived renderMode — the same ordering contract as Fabric.
            RenderCompatGating.detectAndGateRenderCompat();
            SeamlessPortalsConstants.LOGGER.info(
                "Seamless Portals: entity-portal engine initialized (Forge client); "
                    + "flag-ON render dispatch driven by LevelRendererForgeAfterTranslucentSlotMixin");
        });
    }
}
