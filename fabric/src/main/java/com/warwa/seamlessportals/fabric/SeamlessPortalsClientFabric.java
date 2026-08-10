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

        // ★ SEAM OCCUPANCY RECEIVER — UNCONDITIONAL, deliberately ABOVE the flag branch.
        //
        // The live 2026-08-02 round found the crossing half claimed on the server with the client
        // logging "Unknown custom packet payload: seamlessportals:seam_occupancy". Root cause: the
        // receiver was first registered inside FabricPlatformHelper.registerClientHandlers() — the
        // BLOCK-ERA driver set, which the flag-ON branch below NEVER CALLS. The payload TYPE was
        // registered (unconditional in registerPayloads), so the codec decoded fine and vanilla's
        // ClientPacketListener.handleCustomPayload swallowed it with a warning. The seam is a
        // flag-ON feature, so its receiver cannot live in the flag-OFF set; registering here covers
        // both configurations and is harmless flag-OFF (occupancy simply never arrives).
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            com.warwa.seamlessportals.network.ModPayloads.SeamOccupancyPayload.TYPE,
            (payload, context) -> context.client().execute(() ->
                com.warwa.seamlessportals.passthrough.SeamOccupancyClient.apply(
                    payload.dimensionId(), payload.packedPos(), (byte) payload.mask(),
                    payload.secondaryStateId(), (byte) payload.secondaryHalf())));
        // ★ PENDING flush driver (the live-relog fix): the JOIN burst lands before the joining
        // client's level exists and parks in the PENDING stash — which previously only drained on
        // the NEXT packet, i.e. never after a quiet relog. One branch per tick when empty.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
            mc -> com.warwa.seamlessportals.passthrough.SeamOccupancyClient.flushPendingTick());

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

            // ===== SEAM CLIP main-pass draw site (SEAM_CLIP_DESIGN.md §3) =====
            // Same slot: after opaque terrain + entity phases, before translucent terrain and the
            // portal driver — near halves are depth-buffered before the stencil pass computes
            // window visibility. The handler carries the MANDATORY PortalRendering.isRendering()
            // guard (this class-woven event DOES fire inside the full-pipeline twin's nested
            // render with mc.level swapped — panel finding). Thin timing driver; logic is
            // common-side in SeamClipRenderer.
            LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(context ->
                com.warwa.seamlessportals.render.SeamClipRenderer.onMainPassBeforeTranslucentTerrain());

            // ===== SEAM DEST-END AMBIENCE (stitched-space contract item 3, 2026-08-10) =====
            // Same-dim portal destinations are display-tick dead by construction (vanilla samples
            // ±31 blocks around the PLAYER; IP's remote pass walks other-dim worlds only), so a
            // mirrored torch at the far end never emits its own flame/smoke. This pass
            // display-ticks nearby mirrorable portals' dest regions with the camera-distance gate
            // defeated. END_CLIENT_TICK = after vanilla's own animateTick+engine tick; queued
            // spawns drain on the next engine tick (one-tick latency, invisible). Cross-dim stays
            // IP's remote pass. Logic is common-side (SeamDestAmbience); this is the timing driver.
            ClientTickEvents.END_CLIENT_TICK.register(mc ->
                com.warwa.seamlessportals.render.SeamDestAmbience.tick(mc));

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
     * C2-4 (evolved from the C2-1 per-mod-verdict shape) — PER-MOD verdicts under ONE gate
     * (design {@code migration/C2_DESIGN.md} §1 C2-4 deliverables 1-2; see
     * {@link ExperimentalCompatGate} — the {@code ENABLE_SODIUM_IRIS_COMPAT} name is finally
     * true: one gate, both mods).
     *
     * <p>SODIUM verdict: {@code sodiumActive = sodiumPresent && (gate || lever)}. The C2-1
     * {@code !irisPresent} exclusion is DROPPED at C2-4: its rationale ("activating sodium chains
     * while IrisInterface reports absent would route the stencil renderer under an active shader
     * pipeline") is discharged by the honest routing now that {@code OnIrisPresent} is live —
     * {@code PortalRenderer.switchToCorrectRenderer} sees a truthful {@code isShaders()} and
     * routes shaders-ON to {@code rendererDummy} (D8), while shaders-OFF runs the normal
     * stencil-direct renderers with the sodium chains active underneath (iris requires sodium at
     * runtime) and the B4 pipeline null/restore bracket detaching iris for dest passes — IP's
     * exact shaders-OFF arrangement.
     *
     * <p>IRIS verdict: {@code irisActive = irisPresent && (gate || lever)}. When active,
     * {@code IrisInterface.invoker = new OnIrisPresent()} (all B1-B7 call sites go live) +
     * {@code ExperimentalIrisPortalRenderer.init()} at IP's IPModEntryClient slot (:94-95 —
     * init() is EMPTY upstream AND here, verified; the Experimental renderer itself stays
     * D9-deferred and is unreachable: the D8 routing never selects it). <b>D7</b>: the install is
     * REFUSED if the IP-exact {@code LevelRenderer.pipeline} reflection failed to resolve
     * ({@code ip_isPipelineFieldResolved()} — loud ctor-time error naming the iris version); on
     * refusal BOTH verdicts fall to the warn+force-none path (running sodium chains under an
     * unmanageable iris pipeline is the exact hazard the C2-1 exclusion guarded), never silent.
     *
     * <p>Committed default = gate {@code true} (since C2-2, user decision #1) ⇒ sodium AND
     * sodium+iris installs get the compat live out of the box; gate {@code false} + no lever
     * restores warn+force for both. Lever: {@code -Dseamlessportals.experimentalSodiumCompat=true}.
     *
     * <p>IP's lazy-classload discipline is PRESERVED: {@code new OnSodiumPresent()} /
     * {@code new OnIrisPresent()} execute only inside their present+active sub-branches, so no
     * {@code net.caffeinemc.*} / {@code net.irisshaders.*} implementation class loads otherwise.
     *
     * <p>Embeddium line (C2-1 ledger, re-checked at this install site): this method detects via
     * {@code FabricLoader.isModLoaded("sodium"/"iris")} — fabric-only, where embeddium (a
     * NeoForge fork whose internals are NOT Sodium 0.9.1) does not exist; the compat weave's
     * {@code IPCompatMixinPlugin} deliberately does not match embeddium either. The block-era
     * {@code SodiumCompat.isSodiumLoaded} DOES count embeddium on its NeoForge reflection path —
     * that semantic mismatch is ledgered and moot until C7 un-defers the NeoForge client (no
     * install seam exists there; design §6.3).
     */
    private static void detectAndGateRenderCompat() {
        boolean isSodiumPresent = FabricLoader.getInstance().isModLoaded("sodium");
        boolean isIrisPresent = FabricLoader.getInstance().isModLoaded("iris");

        // IP IPModEntryClient logs "Sodium is present"/"is not present" (Helper.log) — kept.
        SeamlessPortalsConstants.LOGGER.info("Sodium is {}present", isSodiumPresent ? "" : "not ");
        SeamlessPortalsConstants.LOGGER.info("Iris is {}present", isIrisPresent ? "" : "not ");

        boolean sodiumLever = Boolean.getBoolean("seamlessportals.experimentalSodiumCompat");
        boolean gateOrLever = ExperimentalCompatGate.ENABLE_SODIUM_IRIS_COMPAT || sodiumLever;
        boolean sodiumActive = isSodiumPresent && gateOrLever;
        boolean irisActive = isIrisPresent && gateOrLever;

        if (irisActive) {
            // ===== C2-4 IRIS ACTIVE (D7 loud-resolve install) ==================================
            // OnIrisPresent classloads only here (lazy discipline). The ctor resolves the
            // IP-exact LevelRenderer.pipeline reflection LOUDLY (D7); a resolve failure marks
            // iris compat BROKEN → refuse the install AND drop the sodium verdict (sodium chains
            // must not run under an iris pipeline the bracket cannot detach) → the warn+force
            // branch below runs, exactly the pre-C2-4 posture. Never silent.
            IrisInterface.OnIrisPresent onIrisPresent = new IrisInterface.OnIrisPresent();
            if (onIrisPresent.ip_isPipelineFieldResolved()) {
                IrisInterface.invoker = onIrisPresent;
                // IP IPModEntryClient:95 — init() is empty (verified upstream + here); called at
                // IP's slot for fidelity. The Experimental renderer stays D9-deferred.
                ExperimentalIrisPortalRenderer.init();
            }
            else {
                irisActive = false;
                sodiumActive = false;
                SeamlessPortalsConstants.LOGGER.error(
                    "Seamless Portals: iris compat BROKEN (pipeline field unresolved — see the "
                        + "error above); falling back to warn + portal-views-off for this session.");
            }
        }

        if (sodiumActive) {
            // ===== C2-1 SODIUM ACTIVE (gate or lever) ==========================================
            // The compat mixin set (seamlessportals-ip-compat.mixins.json: the D1 swap mixin,
            // #3 per-layer lists, FlawlessFrames bridge, the C2-2 clip transport (sodium shader
            // source patch + GLDrawContext upload; invoker-independent by the §6.2 flag-agnostic
            // discipline) + accessors) is woven whenever sodium is present flag-ON; this install
            // is what makes the invoker-gated bodies live. OnSodiumPresent classloads only here.
            SodiumInterface.invoker = new SodiumInterface.OnSodiumPresent();

            // One-shot HONEST experimental notice (GOLD — a notice, not a failure). C2-2: the
            // clipping-gap clause DROPPED (the D3 transport landed) and sodium compat is now
            // DEFAULT-ON (user decision #1). C2-4: ONE notice covering the whole install state —
            // with iris present it states the per-shader-state behavior honestly (shaders OFF =
            // full portal views; shaders ON = pass-through, the D8 routing; the in-the-moment
            // reminder when a pack is actually enabled is the one-shot in
            // PortalRenderer.switchToCorrectRenderer).
            final boolean irisInstalled = irisActive;
            IPGlobal.CLIENT_TASK_LIST.addTask(MyTaskList.oneShotTask(() -> {
                String text = irisInstalled
                    ? ("[Seamless Portals] Sodium + Iris support is EXPERIMENTAL — with shaders "
                        + "OFF portal views are fully active (clipping enabled); with a "
                        + "shaderpack ON portal views are not yet supported and render as "
                        + "pass-through. Please report any terrain artifacts near portals.")
                    : ("[Seamless Portals] Sodium support is EXPERIMENTAL — portal clipping "
                        + "is newly enabled; please report any terrain artifacts near portals.");
                CHelper.printChat(Component.literal(text).withStyle(ChatFormatting.GOLD));
            }));
        }
        else if ((isSodiumPresent || isIrisPresent) && !irisActive) {
            // ===== HONEST-GATING (per-mod verdict fell through) ================================
            // Covers: sodium/iris present with gate+lever off, AND the D7 iris-broken fallback
            // (both verdicts dropped above). The `!irisActive` guard is for the theoretical
            // iris-installed-without-sodium loader state (iris hard-depends on sodium, so it
            // cannot boot — but if it did, a successful iris install must not be immediately
            // force-disabled by this branch). The subject names what was detected so a shader
            // user knows shaders are the issue.
            //
            // D11-LANDED (2026-07-19 C2-1b; design D-ledger D11 / §0.3 conflict 10): the C2
            // baseline round (gate OFF) PROVED the contingency — the main world was BLANK (only
            // sky/outlines/particles) because flag-ON's ImmPtlClientChunkMap replaces the client
            // chunk cache and Sodium's own load hook never fires while the invoker is the no-op
            // base. The A5/A6 chunk-tracker feed is therefore installed here PRESENCE-gated,
            // BEFORE warnAndForcePortalRenderingOff: a tracker-feed-only Invoker whose
            // isSodiumPresent() stays FALSE, so everything else (swap driver, terrain-setup
            // yield, pool-split, notices) still behaves exactly as the un-levered world — pure
            // correctness plumbing outside the experimental gate, same family as the D3
            // unconditional-worldgen seam. With the feed live, this state = a NORMALLY MESHED
            // main world with portal views forced off (the warn text below claims only "portal
            // views are disabled" — still honest). Gated on isSodiumPresent (never iris alone):
            // FeedOnlyOnSodiumPresent classloads sodium types.
            // See the matching D11-LANDED comment on SodiumInterface.FeedOnlyOnSodiumPresent.
            if (isSodiumPresent) {
                SodiumInterface.invoker = new SodiumInterface.FeedOnlyOnSodiumPresent();
                SeamlessPortalsConstants.LOGGER.info(
                    "Seamless Portals: sodium present but compat inactive — installed the D11 "
                        + "presence-gated chunk-tracker feed (main-world meshing only; portal "
                        + "views stay disabled)");
            }
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
