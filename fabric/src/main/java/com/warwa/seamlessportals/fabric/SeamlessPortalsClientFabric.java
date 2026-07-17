package com.warwa.seamlessportals.fabric;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.fabric.network.FabricPlatformHelper;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.render.StencilPortalRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.EntityType;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.portal.BreakableMirror;
import qouteall.imm_ptl.core.portal.EndPortalEntity;
import qouteall.imm_ptl.core.portal.LoadingIndicatorEntity;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalTrackedPortal;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;
import qouteall.imm_ptl.core.portal.global_portals.WorldWrappingPortal;
import qouteall.imm_ptl.core.portal.nether_portal.GeneralBreakablePortal;
import qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity;
import qouteall.imm_ptl.core.render.LoadingIndicatorRenderer;
import qouteall.imm_ptl.core.render.PortalEntityRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

public class SeamlessPortalsClientFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals client initializing (Fabric)");

        // ===== WIRE 2 (S13 step 5): UNCONDITIONAL entity-renderer registration =================
        // Wired in BOTH flag states through the S0 renderer seam (PlatformHelper#registerEntity
        // Renderer), mirroring IP's (unported) IPModEntryClient.initPortalRenderers:41-61
        // (Appendix A.9). MANDATORY unconditionally: the entity types are registered
        // unconditionally (D3, common entrypoint), and the client's Minecraft.selfTest()
        // (IS_RUNNING_IN_IDE) -> EntityRenderers.validateRegistrations() THROWS
        // ("...game data is foobar...") if any registered entity type lacks a renderer. Flag-OFF
        // the portals are never spawned, so these renderers are instantiated (trivial
        // super(context) ctors — no flag-ON state touched) but never asked to render: no block-era
        // behavior change. Flag-ON they render the rung-1 portals (without this, rung 1 renders
        // nothing — EXECUTION_PLAN §3 S13 step 5).
        registerPortalEntityRenderers();

        if (SeamlessPortalsConfig.isEntityPortals()) {
            // ===== ENTITY-PORTAL (Immersive Portals) client init — S13 step 4 =====================
            // DEPENDENCY_ORDER §4.2 client init order: the MiscUtilModEntryClient sequence
            // (ImplRemoteProcedureCall.initClient → MiscNetworking.initClient) THEN IPModMainClient.init
            // (teleport client → renderers on the render thread → collision client → networking client →
            // DimensionIntId.initClient, all internal to IPModMainClient.init). Only reached when
            // entityPortals=true; flag-OFF this branch is never class-loaded, so the block-era baseline
            // is byte-unchanged (D3 pure gate).
            qouteall.q_misc_util.ImplRemoteProcedureCall.initClient();
            qouteall.q_misc_util.MiscNetworking.initClient();
            qouteall.imm_ptl.core.IPModMainClient.init();

            // ===== WIRE 3 (S13-G): flag-ON render-DISPATCH — the REPLACE-BY of the block-era driver =====
            // CUTOVER_SPEC §6.2 item 2 / EXCLUSIVITY_LEDGER rows 14/15 / ported MixinGameRenderer.java:
            // 40-53. First-light attempt 6 spawned + synced the client Portal entities correctly but drew
            // NO window (zero-error invisibility): IPModMainClient.init constructs the ported renderer and
            // assigns IPCGlobal.renderer = rendererUsingStencil, but NOTHING ever drove its per-frame
            // lifecycle. IP drove it from client MIXINS that were RE-HOMED, not re-ported —
            //   MixinGameRenderer.onBeforeRenderingCenter  -> switchToCorrectRenderer() + prepareRendering()
            //   MixinLevelRenderer.onMyBeforeTranslucentRendering -> onBeforeTranslucentRendering(modelView)
            //   MixinGameRenderer.onAfterRenderingCenter   -> finishRendering()
            // whose promised 26.2 REPLACE-BY (ported MixinGameRenderer.java:44-47) is exactly this Fabric
            // LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN registration, wired flag-ON in place of the
            // block-era StencilPortalRenderer.renderPortals() (the else-branch below). This is the strict
            // first-missing link and the direct cause of the invisibility; it makes the renderer RUN.
            //
            // Recursion guard: renderPortalContent recursively calls renderLevel on the destination, which
            // re-fires this event; re-running prepareRendering() there would clear the OUTER portal's
            // stencil mid-render. IP was structurally immune (prepareRendering fired once per frame at
            // GameRenderer.render, not on the recursive renderLevel; onBeforeTranslucentRendering re-fired
            // per pass for nested portals). The mod's single per-renderLevel seam reproduces the essential
            // once-per-frame guarantee by early-returning when PortalRendering.isRendering() — mirroring
            // the block-era renderPortals() `if (isRenderingPortal) return;`. This drives rung-1 (single)
            // portals. Two nested layers remain deferred to the S13 DRIVER-CORE pass, to be landed against
            // the live observation THIS dispatch first enables (NOT wired here — no game run available):
            //   (a) MyGameRenderer.switchAndRenderTheWorld's invokeWrapper never re-points extraction to
            //       the dest world (dest LevelExtractor.extract + compileSections drain + LevelRenderState
            //       re-point — CUTOVER_SPEC §6.2 item 2 / §5.1; MyGameRenderer.java SCOPE LINE :50-63), so
            //       the window will show the main-world extract until it lands.
            //   (b) VisibleSectionDiscovery.armCompileScheduling at the renderPortalContent dest-pass seam
            //       (PortalRenderer.java:287-303 / CUTOVER_SPEC §5.1) — needs the (a) driver-core state.
            // modelView is IP's onBeforeTranslucentRendering argument (IP MixinLevelRenderer.java:148 passed
            // renderLevel's `modelView` local, i.e. the camera VIEW-ROTATION matrix — NOT the pose stack).
            // The Fabric LevelRenderContext.poseStack() is the fresh `new PoseStack()` created in
            // LevelRenderer.submitFeatures and balance-asserted to IDENTITY before the main pass; reading its
            // top pose here fed FrontClipping/getPortalsToRender an identity matrix, so the early frustum cull
            // (IPCGlobal.earlyFrustumCullingPortal, PortalRenderer.java:233) faced world -Z regardless of the
            // camera and wrongly culled visible portals (the first-light invisibility). On 26.2 IP's
            // renderLevel view-rotation moved into CameraRenderState.viewRotationMatrix (CameraRenderState
            // .java:30), already carrying the R13k processTransformation post-process (ported
            // MixinGameRenderer.onExtractEnded:156). Read it — the exact idiom the live substrate proves
            // (StencilPortalRenderer.buildMainFrustum:66-70). Copied defensively: it feeds getPortalsToRender's
            // frustum + FrontClipping.updateInnerClipping + ViewAreaRenderer, none of which may mutate it.
            LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> {
                if (PortalRendering.isRendering()) {
                    return;
                }
                Minecraft client = Minecraft.getInstance();
                Matrix4f modelView = new Matrix4f(
                    client.gameRenderer.gameRenderState().levelRenderState
                        .cameraRenderState.viewRotationMatrix);
                PortalRenderer.switchToCorrectRenderer();
                IPCGlobal.renderer.prepareRendering();
                IPCGlobal.renderer.onBeforeTranslucentRendering(modelView);
                IPCGlobal.renderer.finishRendering();
            });

            SeamlessPortalsConstants.LOGGER.info(
                "Seamless Portals: entity-portal engine initialized (client); "
                    + "flag-ON render dispatch registered (AFTER_TRANSLUCENT_TERRAIN)");
        } else {
            // ===== BLOCK-ERA client driver set (flag-OFF, the shipping baseline — UNCHANGED) =======
            FabricPlatformHelper.registerClientHandlers();

            // Phase 2 (stencil mask + composite) at AFTER_TRANSLUCENT_TERRAIN: this is
            // the ONLY point where the framegraph's camera/projection matrices are live
            // (moving it to renderLevel RETURN composites in the wrong screen position).
            // S14.29 ORDERING CORRECTION (round-3 verified; the old claim here — "the source
            // sky/celestial renders later in the same framegraph and paints over this
            // composite" — is WRONG and seeded a refuted defect-hunt lead): the verified 26.2
            // execution order is clear -> SKY pass -> main pass (this hook fires INSIDE the
            // main pass, AFTER the sky already executed) — mc262 LevelRenderer.java:195-212 +
            // migration/inventory/current-mod-render.md. Any historical "blank curtain" had a
            // different mechanism. See GameRendererPortalPrepareMixin for the old diagnosis.
            LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> {
                StencilPortalRenderer.renderPortals();
            });

            // Drain the remote-chunk queues in small batches per tick. Without
            // these, the post-teleport chunk processing (both the server's burst
            // of ~289 incoming chunks AND the secondary-renderer's initial feed
            // of pre-loaded chunks) would freeze the render thread for seconds.
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                // T2: drain queued redirected dest chunks within a per-tick time budget
                // (was: apply each inline the moment it arrived → a burst froze the render
                // thread ~155ms). Runs FIRST so freshly-applied chunks are available to the
                // compile pump below. The deferred chunk-light lambdas land on each dest
                // level's pollLightUpdates inside tickRemoteWorlds below.
                // DIAG: each client-tick subsystem timed + attributed off-thread
                // ([SEAMLESS TIMERS], reported per 5s) so the "stutters even when not looking
                // at the portal" cost is read from data, not guessed.
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

            SeamlessPortalsConstants.LOGGER.info("Seamless Portals: Registered AFTER_TRANSLUCENT_TERRAIN stencil render hook");
        }
    }

    /**
     * Registers {@link PortalEntityRenderer} for the whole Portal entity-type family and
     * {@link LoadingIndicatorRenderer} for {@link LoadingIndicatorEntity}, through the S0
     * renderer seam. 1:1 with IP's IPModEntryClient.initPortalRenderers:41-61 (the raw-cast +
     * {@code @SuppressWarnings} pattern is IP's own — the shared {@code PortalEntityRenderer}
     * services every portal subtype, which the seam's per-type generic cannot express). Called
     * unconditionally (both flag states) — see the call-site note.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerPortalEntityRenderers() {
        PlatformHelper platformHelper = PlatformHelper.getInstance();

        for (EntityType<?> entityType : new EntityType<?>[]{
            Portal.ENTITY_TYPE,
            NetherPortalEntity.ENTITY_TYPE,
            EndPortalEntity.ENTITY_TYPE,
            Mirror.ENTITY_TYPE,
            BreakableMirror.ENTITY_TYPE,
            GlobalTrackedPortal.ENTITY_TYPE,
            WorldWrappingPortal.ENTITY_TYPE,
            VerticalConnectingPortal.ENTITY_TYPE,
            GeneralBreakablePortal.ENTITY_TYPE
        }) {
            platformHelper.registerEntityRenderer(
                (EntityType) entityType, (EntityRendererProvider) PortalEntityRenderer::new);
        }

        platformHelper.registerEntityRenderer(
            LoadingIndicatorEntity.entityType, LoadingIndicatorRenderer::new);
    }
}
