package com.warwa.seamlessportals.fabric;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.fabric.network.FabricPlatformHelper;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.render.StencilPortalRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.compat.ExperimentalCompatGate;
import qouteall.imm_ptl.core.compat.iris_compatibility.ExperimentalIrisPortalRenderer;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
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
import qouteall.q_misc_util.my_util.MyTaskList;

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

            // S19-A: the peripheral client init (IPOuterClientMisc + the wand client-tick
            // driver + the drag animation signal). IP's fabric.mod.json runs its peripheral
            // CLIENT entry FIRST (before the core client entry); this placement after
            // IPModMainClient mirrors the S16 server-side "tidier one-branch shape" decision —
            // order-insensitive: registrations (static events/signals) plus IPOuterClientMisc's
            // IP-faithful imm_ptl_state.json read/upgrade, which only touches the lazy
            // IPConfig (already loaded flag-ON) — nothing here depends on core client init.
            qouteall.imm_ptl.peripheral.PeripheralModMain.initClient();

            // ===== S19-E: Sodium/Iris detection + HONEST incompat gating (NAMED DEVIATION) =========
            // Runs at IP's corresponding slot (IPModEntryClient.onInitializeClient:71-108, right
            // after the core client init). Flag-ON only (this whole branch), and only after
            // IPModMainClient.init above has loaded IPConfig — so the force below wins over the
            // config-derived renderMode. See ExperimentalCompatGate / detectAndGateRenderCompat.
            detectAndGateRenderCompat();

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
            // Recursion guard (S18 doc correction — the landed S13-H decomposition never recurses
            // renderLevel: SecondaryWorldRenderCore hand-drives renderGroup/renderAllFeatures, so
            // Level render events do NOT re-fire for dest passes; the guard below is DEFENSIVE).
            // Original S13-G rationale, kept for history: re-running prepareRendering() inside a
            // nested pass would clear the OUTER portal's
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

            // ===== S18: Mechanism-B main-pass draw site (R3 seam, design §2.1.3 decided) =====
            // Fires inside the main-pass framegraph lambda AFTER the entity feature phases
            // (solid/translucent/outline) execute and BEFORE translucent terrain — IP's exact
            // end-of-entity-rendering slot, so translucent terrain still tints bracketed entities
            // drawn behind it (drawing at AFTER_TRANSLUCENT_TERRAIN would depth-reject them:
            // translucent terrain writes depth). Thin timing driver only; all logic is common-side
            // (F12). Inert under Mechanism A and with no straddling entities (one map lookup).
            // Dest passes have their own direct call sites in SecondaryWorldRenderCore (no
            // framegraph runs there — this event never fires for them).
            LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(context ->
                qouteall.imm_ptl.core.render.PerEntityClipBracket.onMainPassBeforeTranslucentTerrain());

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

    /**
     * S19-E increment 3 — Sodium/Iris presence detection + the HONEST incompat gate (a NAMED
     * DEVIATION removed at C2; see {@link ExperimentalCompatGate}).
     *
     * <p>1:1 re-site of IP's {@code IPModEntryClient.onInitializeClient:71-108}: the
     * {@code FabricLoader.isModLoaded("sodium"/"iris")} checks, the {@code On*Present} invoker swap,
     * {@code ExperimentalIrisPortalRenderer.init()}, and the one-shot Iris shaderpack warning. IP
     * runs this in the fabric client entrypoint right after its core client init; this method runs
     * at the corresponding point (after {@code PeripheralModMain.initClient}), flag-ON only.
     *
     * <p>DEVIATION vs IP: the invoker swap is gated behind
     * {@link ExperimentalCompatGate#ENABLE_SODIUM_IRIS_COMPAT} (default {@code false}). Detection
     * always runs (cheap, side-effect-free). While the gate is off and Sodium/Iris IS present,
     * instead of swapping the invoker — which would drive the un-C2-verified IP render paths — the
     * mod warns loudly and force-disables portal views for the session
     * ({@link #warnAndForcePortalRenderingOff}).
     *
     * <p>IP's lazy-classload discipline is PRESERVED exactly: the {@code new *.On*Present()} and
     * {@code ExperimentalIrisPortalRenderer.init()} references only execute inside the
     * {@code isModLoaded} branch AND the gate-on sub-branch, so no {@code net.caffeinemc.*} /
     * {@code net.irisshaders.*} implementation class loads unless the mod is present AND the gate
     * is flipped. (With the gate {@code false} at runtime, that whole sub-branch is never taken.)
     */
    private static void detectAndGateRenderCompat() {
        boolean isSodiumPresent = FabricLoader.getInstance().isModLoaded("sodium");
        boolean isIrisPresent = FabricLoader.getInstance().isModLoaded("iris");

        // IP IPModEntryClient logs "Sodium is present"/"is not present" (Helper.log) — kept.
        SeamlessPortalsConstants.LOGGER.info("Sodium is {}present", isSodiumPresent ? "" : "not ");
        SeamlessPortalsConstants.LOGGER.info("Iris is {}present", isIrisPresent ? "" : "not ");

        if (ExperimentalCompatGate.ENABLE_SODIUM_IRIS_COMPAT) {
            // ===== C2 PATH (gate flipped) — IP IPModEntryClient:73-105 behavior verbatim =====
            // Never taken today (gate default false). NOTE the C2 flip is NOT just this gate:
            // none of IP's imm_ptl_compat mixin set (9 sodium + 7 iris mixins, incl. the
            // IESodiumWorldRenderer accessor and the IESodiumRenderSectionManager duck impl) is
            // ported/registered yet — flipping the gate with Sodium present would CCE at the
            // OnSodiumPresent duck casts. C2 ports + registers that set FIRST (see
            // migration/C2_IP_COMPAT_DEPTH.md), then flips/deletes the gate.
            // The On*Present classes classload only here.
            if (isSodiumPresent) {
                SodiumInterface.invoker = new SodiumInterface.OnSodiumPresent();
            }
            if (isIrisPresent) {
                IrisInterface.invoker = new IrisInterface.OnIrisPresent();
                ExperimentalIrisPortalRenderer.init();

                IPGlobal.CLIENT_TASK_LIST.addTask(MyTaskList.oneShotTask(() -> {
                    if (IPConfig.getConfig().shouldDisplayWarning("iris")) {
                        CHelper.printChat(
                            Component.translatable("imm_ptl.iris_warning")
                                .append(IPMcHelper.getDisableWarningText("iris"))
                        );
                    }
                }));
            }
        }
        else if (isSodiumPresent || isIrisPresent) {
            // ===== HONEST-GATING DEVIATION (gate off + an incompatible renderer present) =====
            // Iris implies Sodium at runtime, so a single warn+force covers both; the subject just
            // names what was detected (Iris named too, so a shader user knows shaders are the issue).
            String subject = isIrisPresent
                ? (isSodiumPresent ? "Sodium + Iris (shaders)" : "Iris (shaders)")
                : "Sodium";
            warnAndForcePortalRenderingOff(subject);
        }
    }

    /**
     * The user-facing half of the S19-E honest-gating deviation: a loud init log, a one-shot
     * world-join chat message, and a session-only force of {@code IPGlobal.renderMode = none}.
     * {@code subject} names the incompatible renderer(s) detected. See {@link ExperimentalCompatGate}.
     */
    private static void warnAndForcePortalRenderingOff(String subject) {
        // (a) loud, multi-line log at init.
        SeamlessPortalsConstants.LOGGER.error(
            "\n============================================================\n"
                + "[Seamless Portals] {} detected, but the ported {}-compatible portal\n"
                + "rendering layer is NOT enabled yet (still being ported — C2).\n"
                + "Portal VIEWS are disabled for this session to avoid rendering errors.\n"
                + "(Teleportation and portal creation still work.)\n"
                + "This is temporary; a future update will restore portal rendering with {}.\n"
                + "============================================================",
            subject, subject, subject
        );

        // (c) force portal rendering off for THIS SESSION only. Applied AFTER config load (this
        // runs post-IPModMainClient.init, flag-ON, where IPConfig is already loaded) so it wins
        // over the config's compatibilityRenderMode-derived renderMode value. The session flag
        // makes it survive any runtime config reload (in-game config save) via the guard in
        // IPConfig.onConfigChanged. NEVER written to disk (no saveConfigFile call) — the user's
        // persisted renderMode preference is untouched.
        ExperimentalCompatGate.forcePortalRenderingOffThisSession = true;
        IPGlobal.renderMode = IPGlobal.RenderMode.none;

        // (b) one-shot chat on world join. IP's Iris-warning idiom (IPModEntryClient:97 — bare
        // oneShotTask added at client init; CLIENT_TASK_LIST is processed on POST_CLIENT_TICK,
        // i.e. the first client-world tick after join, and only force-cleared on disconnect).
        // Unconditional (not gated by shouldDisplayWarning): portal views being off is a functional
        // state change the user must be told about, not a suppressible cosmetic warning.
        final String subjectFinal = subject;
        IPGlobal.CLIENT_TASK_LIST.addTask(MyTaskList.oneShotTask(() -> {
            CHelper.printChat(
                Component.literal(
                    "[Seamless Portals] " + subjectFinal + " detected: portal views are disabled "
                        + "this session (rendering not compatible with it yet). "
                        + "Teleportation still works."
                ).withStyle(ChatFormatting.RED)
            );
        }));
    }
}
