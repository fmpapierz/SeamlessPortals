package com.warwa.seamlessportals.neoforge;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.EntityPortalsFlag;
import com.warwa.seamlessportals.client.PortalEntityRenderers;
import com.warwa.seamlessportals.client.SeamlessConfigScreen;
import com.warwa.seamlessportals.compat.RenderCompatGating;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.neoforge.network.NeoForgePlatformHelper;
import com.warwa.seamlessportals.render.StencilPortalRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

/**
 * NF-PARITY W6/W7/W19 (2026-08-25): the NeoForge CLIENT entry point — dist-gated
 * {@code @Mod}, full parity with {@code SeamlessPortalsClientFabric}. Timing map:
 * <ul>
 *   <li>renderer-queue fill + mod-bus listeners → the ctor. {@code RegisterRenderers} is
 *       posted from {@code Minecraft.<init>} (NF Minecraft.java:702) BEFORE
 *       {@code FMLClientSetupEvent} (:719), so the queue must be full at ctor time — it is
 *       (the ctor runs during mod construction, long before {@code new Minecraft}).</li>
 *   <li>the IP client init chain → {@code FMLClientSetupEvent.enqueueWork} (main thread;
 *       {@code Minecraft.getInstance()} exists — we are inside its ctor at :719, which is
 *       exactly why {@code IPModMainClient}'s GL block already wraps itself in
 *       {@code mc.execute}). Fabric runs the same chain from its client entrypoint
 *       mid-{@code Minecraft.<init>} — the same window.</li>
 *   <li>the flag-ON render dispatch → {@code RenderLevelStageEvent.AfterTranslucentBlocks}
 *       (game bus), the exact-instruction match of Fabric's AFTER_TRANSLUCENT_TERRAIN
 *       (posted immediately after {@code renderGroup(TRANSLUCENT, ...)}, NF
 *       LevelRenderer.java:474-476).</li>
 * </ul>
 */
@Mod(value = SeamlessPortalsConstants.MOD_ID, dist = Dist.CLIENT)
public class SeamlessPortalsClientNeoForge {

    public SeamlessPortalsClientNeoForge(IEventBus modEventBus, ModContainer container) {
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals client initializing (NeoForge)");

        // ===== WIRE 2: UNCONDITIONAL entity-renderer registration (both flag states) =====
        // Same rationale as Fabric's call-site note: entity types register unconditionally
        // (D3), so their renderers must too. registerAll() runs INSIDE the event listener,
        // not at ctor time — it references Portal.ENTITY_TYPE and friends, whose <clinit>
        // BUILDS the types (intrusive holders — frozen-registry crash before the window; the
        // E0 boot lesson). By RegisterRenderers (Minecraft.<init>) they are long initialized.
        modEventBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> {
            PortalEntityRenderers.registerAll();
            NeoForgePlatformHelper.drainEntityRendererRegistrations(event::registerEntityRenderer);
        });

        modEventBus.addListener(this::onClientSetup);

        // Mod-list config button (the ModMenu equivalent) — same flag route as
        // fabric ModMenuIntegration: flag-ON -> IP's cloth screen; flag-OFF -> the vanilla
        // block-era screen (moved to :common this stage).
        container.registerExtensionPoint(IConfigScreenFactory.class,
            (modContainer, parent) -> EntityPortalsFlag.isOn()
                ? qouteall.imm_ptl.core.platform_specific.IPConfigGUI.createClothConfigScreen(parent)
                : new SeamlessConfigScreen(parent));

        if (SeamlessPortalsConfig.isEntityPortals()) {
            // ===== WIRE 3: flag-ON render DISPATCH ========================================
            // Byte-mirrors SeamlessPortalsClientFabric's AFTER_TRANSLUCENT_TERRAIN driver,
            // including the census witness and the re-entrancy guard (LOAD-BEARING: a layer-0
            // nested render would otherwise recurse — see the Fabric driver's TP-XDIM note).
            NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentBlocks event) -> {
                com.warwa.seamlessportals.render.TpXdimFrameCensus.noteF1Driver("flagON");
                if (PortalRendering.isRendering()
                    || qouteall.imm_ptl.core.render.CrossPortalViewRendering
                        .isRenderingCrossPortalView()
                ) {
                    com.warwa.seamlessportals.render.TpXdimFrameCensus
                        .noteF1Driver("skipped-reentrant");
                    return;
                }
                Minecraft client = Minecraft.getInstance();
                // Defensive copy — FrontClipping/ViewAreaRenderer must not mutate the live
                // cameraRenderState matrix (same read + copy as the Fabric driver).
                Matrix4f modelView = new Matrix4f(
                    client.gameRenderer.gameRenderState().levelRenderState
                        .cameraRenderState.viewRotationMatrix);
                PortalRenderer.switchToCorrectRenderer();
                IPCGlobal.renderer.prepareRendering();
                IPCGlobal.renderer.onBeforeTranslucentRendering(modelView);
                IPCGlobal.renderer.finishRendering();
            });
            // The BEFORE_TRANSLUCENT_TERRAIN clip-bracket site has NO NeoForge event in the
            // gap — driven by the NEOFORGE_ONLY mixin
            // MixinLevelRenderer_ClipBracketMainPassNeoForge instead (W21).
        } else {
            // ===== BLOCK-ERA client driver set (flag-OFF, the legacy opt-out) =============
            // The stencil composite + per-tick pumps, mirroring Fabric's else-branch (same
            // :common bodies). KNOWN DEVIATION (recorded): the block-era client payload
            // HANDLERS remain Fabric-only — see the server class's flag-OFF note.
            NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentBlocks event) -> {
                com.warwa.seamlessportals.render.TpXdimFrameCensus.noteF1Driver("stencil");
                StencilPortalRenderer.renderPortals();
            });

            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
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

            SeamlessPortalsConstants.LOGGER.info(
                "Seamless Portals: Registered AfterTranslucentBlocks stencil render hook (NeoForge)");
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        if (!SeamlessPortalsConfig.isEntityPortals()) {
            return;
        }
        // ===== ENTITY-PORTAL client init — the exact Fabric order, on the main thread ======
        // FMLClientSetupEvent is parallel-dispatch; enqueueWork serializes onto the main
        // thread (we are inside Minecraft.<init> at :719 — mc.getInstance() is non-null,
        // matching Fabric's mid-ctor client-entrypoint window).
        event.enqueueWork(() -> {
            qouteall.q_misc_util.ImplRemoteProcedureCall.initClient();
            qouteall.q_misc_util.MiscNetworking.initClient();
            qouteall.imm_ptl.core.IPModMainClient.init();
            qouteall.imm_ptl.peripheral.PeripheralModMain.initClient();
            // AFTER IPModMainClient.init (IPConfig loaded) so the force wins over the
            // config-derived renderMode — the same ordering contract as Fabric.
            RenderCompatGating.detectAndGateRenderCompat();
            SeamlessPortalsConstants.LOGGER.info(
                "Seamless Portals: entity-portal engine initialized (NeoForge client); "
                    + "flag-ON render dispatch registered (AfterTranslucentBlocks)");
        });
    }
}
