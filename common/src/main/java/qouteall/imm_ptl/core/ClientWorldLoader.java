package qouteall.imm_ptl.core;

import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.warwa.seamlessportals.event.Event;
import com.warwa.seamlessportals.mixin.client.GameRendererAccessorMixin;
import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.ducks.IEClientPlayNetworkHandler;
import qouteall.imm_ptl.core.ducks.IEClientWorld;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.ducks.IEParticleManager;
import qouteall.imm_ptl.core.ducks.IEWorld;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;
import qouteall.imm_ptl.core.mixin.client.accessor.IEClientLevelData;
import qouteall.imm_ptl.core.mixin.client.accessor.IEClientLevel_Accessor;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.DimensionRenderHelper;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.CountDownInt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@SuppressWarnings("resource")
@Environment(EnvType.CLIENT)
public class ClientWorldLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientWorldLoader.class);

    private static final CountDownInt LOG_LIMIT = new CountDownInt(20);

    public static final Event<Consumer<ResourceKey<Level>>> CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT =
        Helper.createConsumerEvent();
    public static final Event<Consumer<ClientLevel>> CLIENT_WORLD_LOAD_EVENT =
        Helper.createConsumerEvent();

    private static final Map<ResourceKey<Level>, ClientLevel> CLIENT_WORLD_MAP =
        new Object2ObjectOpenHashMap<>();
    public static final Map<ResourceKey<Level>, LevelRenderer> WORLD_RENDERER_MAP =
        new Object2ObjectOpenHashMap<>();
    // 26.2 render-split (memory nether-block-freeze-orphaned-extractor + api-map
    // world-loader-root §4): the per-dimension LevelExtractor sibling of the renderer. In
    // 1.21.3 the LevelRenderer itself carried setLevel / onResourceManagerReload / allChanged;
    // 26.2 moved them onto a LevelExtractor. Each ClientLevel is permanently bound to the
    // extractor passed at construction (its final levelExtractor field, ClientLevel.java:152),
    // so this map records that identity per dim and NEVER rebuilds it — the writer
    // (setBlocksDirty → level.levelExtractor) and the reader (this extractor's extract) must
    // stay the same object for the level's whole life, or block break/place silently stops
    // remeshing (the nether-block-freeze bug). Added for the render-split; has no 1.21.3 analog.
    public static final Map<ResourceKey<Level>, LevelExtractor> WORLD_EXTRACTOR_MAP =
        new Object2ObjectOpenHashMap<>();
    public static final Map<ResourceKey<Level>, DimensionRenderHelper> RENDER_HELPER_MAP =
        new Object2ObjectOpenHashMap<>();

    // S14-A FIX-4 (M4, audit link drivercore): per-secondary FeatureRenderDispatcher isolation.
    // 26.2's LevelRenderer ctor binds featureRenderDispatcher = gameRenderer.featureRenderDispatcher()
    // — the ONE main instance, whose single PreparedFrame is already OPEN during
    // AFTER_TRANSLUCENT_TERRAIN (begin() throws "PreparedFrame already in use"), so a secondary
    // driving it renders NO entities/block-entities/particles and its SubmitNodeStorage never
    // drains (unbounded growth). Each secondary gets its OWN dispatcher over its OWN
    // RenderBuffers(4) (sharing the MAIN RenderBuffers into a second dispatcher is forbidden:
    // PreparedFrame.close() calls stagedVertexBuffer.endDraw(), which would end the MAIN pass's
    // staged draw mid-frame). The renderer's own renderBuffers FIELD stays main-shared — that IS
    // IP 1.21.3's arrangement (IP passed client.renderBuffers() to secondaries). These maps keep
    // the isolated instances across promote/demote cycles; every entry needs the per-frame
    // endFrame() walk (memory gpu-buffer-leak-endframe) and close() at disposal.
    public static final Map<ResourceKey<Level>, net.minecraft.client.renderer.RenderBuffers>
        SECONDARY_FEATURE_BUFFERS = new Object2ObjectOpenHashMap<>();
    public static final Map<ResourceKey<Level>, net.minecraft.client.renderer.feature.FeatureRenderDispatcher>
        SECONDARY_FEATURE_DISPATCHERS = new Object2ObjectOpenHashMap<>();

    public static @Nullable Map<ResourceKey<Level>, ResourceKey<DimensionType>> dimIdToDimTypeId;

    // R1 seaLevel client cache (SPIKE-R1-sealevel.md §3 "Client cache lifecycle"): per-dim sea
    // level synced on the DimIdSyncPacket path (S7 MiscNetworking), consumed by
    // createSecondaryClientWorld as the 26.2 ClientLevel ctor's trailing int seaLevel. Mirrors
    // dimIdToDimTypeId exactly — replaced WHOLESALE in DimIdSyncPacket.handle (stale removed-dim
    // entries vanish with the swap) and nulled on client exit (init(), below). The 26.2
    // ClientLevel ctor requires a sea level and there is NO client-local source for a
    // not-yet-visited dim (flat overworld reports -63, not 63; the value is final for the
    // level's whole life — API_RISKS R1 / SPIKE-R1 §1.2).
    public static @Nullable ImmutableMap<ResourceKey<Level>, Integer> dimIdToDimSeaLevel;

    private static final Minecraft CLIENT = Minecraft.getInstance();

    private static boolean isInitialized = false;

    private static boolean isCreatingClientWorld = false;

    public static boolean isClientRemoteTicking = false;

    private static boolean isWorldSwitched = false;

    public static void init() {
        DimensionAPI.CLIENT_DIMENSION_UPDATE_EVENT.register((serverDimensions) -> {
            if (getIsInitialized()) {
                List<ResourceKey<Level>> dimensionsToRemove =
                    CLIENT_WORLD_MAP.keySet().stream()
                        .filter(dim -> !serverDimensions.contains(dim)).toList();

                for (ResourceKey<Level> dim : dimensionsToRemove) {
                    disposeDimensionDynamically(dim);
                }

            }
        });

        IPCGlobal.CLIENT_EXIT_EVENT.register(() -> {
            dimIdToDimTypeId = null;
            // R1: null the seaLevel cache in lockstep with the dim-type cache (SPIKE-R1 §3).
            dimIdToDimSeaLevel = null;
        });
    }

    public static boolean getIsInitialized() {
        return isInitialized;
    }

    public static boolean getIsCreatingClientWorld() {
        return isCreatingClientWorld;
    }

    public static void tick() {
        if (IPCGlobal.isClientRemoteTickingEnabled) {
            // S14.6 hardening (fix-verify, deliberate 1-line deviation from IP's shape): the flag
            // was WRITE-ONLY in IP; S14.1's tickTime HEAD-cancel made it load-bearing, and a throw
            // escaping the inner catch (withSwitchedWorld's own prologue validations) would strand
            // it true — permanently cancelling the shared connection clock. try/finally matches
            // the runtime-proven block-era PortalWorldManager.tickRemoteWorlds form.
            isClientRemoteTicking = true;
            try {
                CLIENT_WORLD_MAP.values().forEach(world -> {
                    if (CLIENT.level != world) {
                        tickRemoteWorld(world);
                    }
                });
            }
            finally {
                isClientRemoteTicking = false;
            }
            // 26.2: LevelRenderer.tick() is GONE (SPIKE-R1 §4.3 "the worldRenderer.tick()
            // loop is DELETED with a documented role transfer, not replaced"). Its only
            // 1.21.3 duty — expiring stale BlockDestructionProgress — moved onto ClientLevel
            // (ClientLevel.tick → removeBlockBreakingProgress, same gameTime%20 / 400-tick
            // algorithm), which tickRemoteWorld above already runs via newWorld.tick(() -> true);
            // the render-side consumption of destruction progress happens per-frame in
            // extraction. Nothing renderer-side remains to tick per game tick.
        }

        // 26.2: GameRenderer.lightTexture() and the LightTexture class are GONE — split into a
        // per-GameRenderState Lightmap / LightmapRenderState triple (api-map GONE "LightTexture"
        // row). The "a secondary helper must not hold the MAIN gameRenderer's lightmap" identity
        // guard therefore compares the helper's Lightmap against the private GameRenderer.lightmap
        // reached through the mod's GameRendererAccessorMixin (the "GameRenderer.lightmap accessor
        // as mapped", SPIKE-R1 §4.4, which assigns this block to S10). helper.lightmapTexture is
        // retyped LightTexture → Lightmap by the S11 DimensionRenderHelper port (forward-ref debt).
        Lightmap currentLightmap =
            ((GameRendererAccessorMixin) CLIENT.gameRenderer).seamlessportals$getLightmap();
        boolean lightmapTextureConflict = false;
        for (DimensionRenderHelper helper : RENDER_HELPER_MAP.values()) {
            helper.tick();
            if (helper.world != CLIENT.level) {
                if (helper.lightmapTexture == currentLightmap) {
                    assert CLIENT.level != null;
                    LOGGER.info(
                        "Lightmap Texture Conflict {} {}",
                        helper.world.dimension().identifier(),
                        CLIENT.level.dimension().identifier()
                    );
                    lightmapTextureConflict = true;
                }
            }
        }
        if (lightmapTextureConflict) {
            disposeRenderHelpers();
            LOGGER.info("Refreshed Lightmaps");
        }

    }

    public static void disposeRenderHelpers() {
        RENDER_HELPER_MAP.values().forEach(DimensionRenderHelper::cleanUp);
        RENDER_HELPER_MAP.clear();
    }

    private static void tickRemoteWorld(ClientLevel newWorld) {
        List<Portal> nearbyPortals = CHelper.getClientNearbyPortals(10).collect(Collectors.toList());

        withSwitchedWorld(newWorld, () -> {
            try {
                newWorld.tickEntities();
                // S14-A FIX-8 (M8, ticklight): 26.2 HOISTED the block-entity tick out of
                // tickEntities (mc262 Minecraft.tick:1797-1799 calls tickEntities() and
                // tickBlockEntities() as SEPARATE siblings; 1.21.3 tickEntities tail-called it).
                // IP's verbatim pair therefore lost client block-entity ticking for secondaries
                // (frozen campfire smoke/spawner spin/chest lids through the window, and
                // pendingBlockEntityTickers never drains). Required 26.2 re-expression of IP's
                // tickEntities semantics, mirroring vanilla's entities->blockEntities pairing.
                newWorld.tickBlockEntities();
                newWorld.tick(() -> true);

                if (!CLIENT.isPaused()) {
                    tickRemoteWorldRandomTicksClient(newWorld, nearbyPortals);
                }

                newWorld.pollLightUpdates();
                // S14.51 fix T (trace wf_1e07ce4b-f53 M1, HIGH): restore vanilla's poll→run
                // ADJACENCY for secondaries. Vanilla publishes immediately after polling
                // (ClientLevel.update()), so a light-correction packet's drain-time dirty mark
                // can never be consumed against a pre-publish store. Our split (poll here at
                // tick, publish at frame-END lateUpdateLight) opened a window where the
                // dest-pass consumers compile against stale light and re-sent corrections then
                // publish SILENTLY (no onLightUpdate callbacks) — the mark is gone, the dark
                // bake permanent. This ADDS the vanilla-cadence run at the drain site; the
                // frame-end lateUpdateLight stays (its FIX-3 placement is load-bearing) and
                // becomes a near-no-op safety net for anything queued post-tick.
                newWorld.getChunkSource().getLightEngine().runLightUpdates();
            }
            catch (Throwable e) {
                if (LOG_LIMIT.tryDecrement()) {
                    LOGGER.error("", e);
                }
            }
        });
    }

    // show nether particles through portal
    // TODO optimize it in future version?
    private static void tickRemoteWorldRandomTicksClient(
        ClientLevel newWorld, List<Portal> nearbyPortals
    ) {
        nearbyPortals.stream().filter(
            portal -> portal.getDestDim() == newWorld.dimension()
        ).findFirst().ifPresent(portal -> {
            assert CLIENT.player != null;
            Vec3 playerPos = CLIENT.player.position();
            Vec3 center = portal.transformPoint(playerPos);

            Camera camera = CLIENT.gameRenderer.mainCamera(); // 26.2: getMainCamera() → mainCamera()
            Vec3 oldCameraPos = camera.position();            // 26.2: getPosition() → position()

            ((IECamera) camera).portal_setPos(center);

            if (newWorld.getGameTime() % 2 == 0) {
                // it costs some CPU time
                newWorld.animateTick(
                    (int) center.x, (int) center.y, (int) center.z
                );
            }

            CLIENT.particleEngine.tick();

            ((IECamera) camera).portal_setPos(oldCameraPos);
        });


    }

    public static void cleanUp() {
        // 26.2 render-split: dispose each dim's renderer AND null its LevelExtractor's level
        // (setLevel moved LevelRenderer → LevelExtractor). Iterate ENTRIES so each renderer is
        // paired with its dimension → its extractor in WORLD_EXTRACTOR_MAP.
        WORLD_RENDERER_MAP.forEach(ClientWorldLoader::disposeWorldRenderer);

        for (ClientLevel clientWorld : CLIENT_WORLD_MAP.values()) {
            ((IEClientWorld) clientWorld).ip_resetWorldRendererRef();
        }

        CLIENT_WORLD_MAP.clear();
        WORLD_RENDERER_MAP.clear();
        WORLD_EXTRACTOR_MAP.clear();
        // S14-A FIX-4: per-dim entries were closed+removed by disposeWorldRenderer above;
        // defensive clear only.
        SECONDARY_FEATURE_BUFFERS.clear();
        SECONDARY_FEATURE_DISPATCHERS.clear();

        disposeRenderHelpers();

        isInitialized = false;
    }

    private static void disposeWorldRenderer(ResourceKey<Level> dimension, LevelRenderer worldRenderer) {
        // 26.2: setLevel(null) moved LevelRenderer → LevelExtractor (api-map CHANGED
        // "LevelRenderer.setLevel"). Null the level on this dim's construction-bound extractor
        // (EXTRACTOR-IDENTITY). For the main dim the extractor is CLIENT.levelExtractor (seeded
        // in initializeIfNeeded); nulling it here is idempotent with vanilla's own world-exit
        // path (Minecraft.updateLevelInEngines → levelExtractor.setLevel(null)).
        LevelExtractor extractor = WORLD_EXTRACTOR_MAP.get(dimension);
        if (extractor != null) {
            extractor.setLevel(null);
        }
        if (worldRenderer != CLIENT.levelRenderer) {
            worldRenderer.close();
            ((IEWorldRenderer) worldRenderer).portal_fullyDispose();
        }
        // S14-A FIX-4: release this dim's isolated feature pipeline (the maps only ever hold
        // mod-created instances — never the main dispatcher/buffers, so close is always safe).
        net.minecraft.client.renderer.feature.FeatureRenderDispatcher featureDispatcher =
            SECONDARY_FEATURE_DISPATCHERS.remove(dimension);
        if (featureDispatcher != null) {
            featureDispatcher.close();
        }
        net.minecraft.client.renderer.RenderBuffers featureBuffers =
            SECONDARY_FEATURE_BUFFERS.remove(dimension);
        if (featureBuffers != null) {
            featureBuffers.close();
        }
    }

    /**
     * S14-A FIX-4: the per-frame {@code endFrame()} walk over every isolated per-secondary
     * feature-pipeline {@link net.minecraft.client.renderer.RenderBuffers} (memory
     * gpu-buffer-leak-endframe — every mod-created RenderBuffers needs vanilla's per-frame
     * endFrame or its StagedVertexBuffer pools never fence-recycle and leak GPU buffers).
     * Called from {@link qouteall.imm_ptl.core.render.MyGameRenderer#endFramePooled()}, which the
     * mod already wires at {@code GameRenderer.render} TAIL flag-ON.
     */
    public static void endFrameOnSecondaryFeatureBuffers() {
        for (net.minecraft.client.renderer.RenderBuffers buffers : SECONDARY_FEATURE_BUFFERS.values()) {
            buffers.endFrame();
        }
        for (net.minecraft.client.renderer.RenderBuffers buffers : CORE_OWNED_FEATURE_BUFFERS) {
            buffers.endFrame();
        }
    }

    /** S15: core-owned (non-per-dim) mod RenderBuffers that need the same per-frame endFrame
     *  as the per-secondary map above (memory gpu-buffer-leak-endframe). Render thread only.
     *  First registrant: SecondaryWorldRenderCore's same-dim entity pipeline. */
    private static final java.util.List<net.minecraft.client.renderer.RenderBuffers>
        CORE_OWNED_FEATURE_BUFFERS = new java.util.ArrayList<>();

    public static void registerCoreOwnedFeatureBuffers(
        net.minecraft.client.renderer.RenderBuffers buffers
    ) {
        if (!CORE_OWNED_FEATURE_BUFFERS.contains(buffers)) {
            CORE_OWNED_FEATURE_BUFFERS.add(buffers);
        }
    }

    private static void disposeDimensionDynamically(ResourceKey<Level> dimension) {
        Validate.notNull(CLIENT.player, "player is null");
        Validate.notNull(CLIENT.level, "level is null");
        Validate.isTrue(
            CLIENT.level.dimension() != dimension,
            "Cannot dispose current dimension"
        );
        Validate.isTrue(
            CLIENT.player.level().dimension() != dimension,
            "Cannot dispose current dimension"
        );
        Validate.isTrue(CLIENT.isSameThread(), "not on client thread");

        LevelRenderer worldRenderer = WORLD_RENDERER_MAP.get(dimension);
        disposeWorldRenderer(dimension, worldRenderer);
        WORLD_RENDERER_MAP.remove(dimension);
        WORLD_EXTRACTOR_MAP.remove(dimension);

        Validate.isTrue(CLIENT.levelRenderer != worldRenderer);

        ClientLevel clientWorld = CLIENT_WORLD_MAP.get(dimension);
        ((IEClientWorld) clientWorld).ip_resetWorldRendererRef();
        CLIENT_WORLD_MAP.remove(dimension);

        DimensionRenderHelper renderHelper = RENDER_HELPER_MAP.remove(dimension);
        if (renderHelper != null) {
            renderHelper.cleanUp();
        }

        LOGGER.info("Client Dynamically Removed Dimension {}", dimension.identifier());

        if (clientWorld.getChunkSource().getLoadedChunksCount() > 0) {
            LOGGER.error("The chunks of that dimension was not cleared before removal");
        }

        if (clientWorld.getEntityCount() > 0) {
            LOGGER.error("The entities of that dimension was not cleared before removal");
        }

        CLIENT.gameRenderer.resetData();

        CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT.invoker().accept(dimension);
    }

    @NotNull
    public static LevelRenderer getWorldRenderer(ResourceKey<Level> dimension) {
        initializeIfNeeded();

        LevelRenderer result = WORLD_RENDERER_MAP.get(dimension);

        if (result == null) {
            LOGGER.warn(
                "Acquiring LevelRenderer before acquiring Level. Something is probably wrong. {}",
                dimension.identifier(), new Throwable()
            );

            // the world renderer is created along with the world
            // so create the world now
            getWorld(dimension);

            result = WORLD_RENDERER_MAP.get(dimension);

            if (result == null) {
                throw new RuntimeException("Unable to get LevelRenderer of " + dimension.identifier());
            }
        }

        return result;
    }

    /**
     * 26.2 render-split: the per-dimension {@link LevelExtractor} accessor — the S9-deferred
     * routing for {@code ImmPtlClientChunkMap.onLightUpdate} (S9 port-note §4.2). In 1.21.3 the
     * LevelRenderer carried {@code setSectionDirty} so IP reached it via {@link #getWorldRenderer};
     * 26.2 moved that (and setLevel / allChanged / reload) onto the LevelExtractor (api-map
     * chunk-loading #45 / world-loader-root §4), and {@link #getWorldRenderer} keeps returning the
     * actual {@link LevelRenderer} that its OTHER callers install as {@code mc.levelRenderer}
     * (withSwitchedWorld here; ClientTeleportationManager) — so the setSectionDirty caller gets its
     * own accessor rather than overloading getWorldRenderer's meaning (divergence from the Slice C
     * §3 draft, which flipped getWorldRenderer's return type; kept verbatim here instead).
     *
     * <p>EXTRACTOR-IDENTITY (memory nether-block-freeze-orphaned-extractor): the ACTIVE/main dim
     * MUST route through the vanilla GLOBAL {@code CLIENT.levelExtractor} ITSELF — never a per-dim
     * copy — because the active level forwards its own block/section dirties there
     * (ClientLevel.java:791-804; Minecraft.levelExtractor:280), and after a crossing the newly-
     * active dim's WORLD_EXTRACTOR_MAP entry is the STALE secondary extractor vanilla no longer
     * drives. Secondaries use their own construction-bound extractor.
     */
    @NotNull
    public static LevelExtractor getWorldExtractor(ResourceKey<Level> dimension) {
        initializeIfNeeded();

        // S14-A FIX-3 hardening (latent identity bug, audit link clientworld): prefer the
        // identity-coherent MAP over the CLIENT.level short-circuit. The map's main-dim entry IS
        // CLIENT.levelExtractor (seeded by initializeIfNeeded; kept invariant at every crossing by
        // the S14-A promote/demote), while CLIENT.level is a SWAPPABLE field — the old
        // level-keyed short-circuit returned the MAIN extractor for a dim whose level was merely
        // swapped in (withSwitchedWorld / the dest render pass), landing dirties on the wrong
        // SectionUpdateTracker (the S13-H DEFECT-1 shape). Map-first is swap-safe by construction.
        LevelExtractor mapped = WORLD_EXTRACTOR_MAP.get(dimension);
        if (mapped != null) {
            return mapped;
        }

        if (CLIENT.level != null && CLIENT.level.dimension() == dimension) {
            return CLIENT.levelExtractor;
        }

        LevelExtractor result = WORLD_EXTRACTOR_MAP.get(dimension);

        if (result == null) {
            LOGGER.warn(
                "Acquiring LevelExtractor before acquiring Level. Something is probably wrong. {}",
                dimension.identifier(), new Throwable()
            );

            // the extractor is created along with the world
            getWorld(dimension);

            result = WORLD_EXTRACTOR_MAP.get(dimension);

            if (result == null) {
                throw new RuntimeException("Unable to get LevelExtractor of " + dimension.identifier());
            }
        }

        return result;
    }


    /**
     * Get the client world and create if missing.
     * If the dimension id is invalid, it will throw an error
     */
    @NotNull
    public static ClientLevel getWorld(ResourceKey<Level> dimension) {
        Validate.notNull(dimension, "dimension is null");
        Validate.isTrue(CLIENT.isSameThread());

        initializeIfNeeded();

        if (!CLIENT_WORLD_MAP.containsKey(dimension)) {
            return createSecondaryClientWorld(dimension);
        }

        ClientLevel result = CLIENT_WORLD_MAP.get(dimension);
        Validate.notNull(result, "null value in world map");
        return result;
    }

    /**
     * Get the client world and create if missing.
     * If the dimension id is invalid, it will return null
     */
    @Nullable
    public static ClientLevel getOptionalWorld(ResourceKey<Level> dimension) {
        Validate.notNull(dimension, "dimension is null");
        Validate.isTrue(CLIENT.isSameThread(), "not on client thread");

        if (getServerDimensions().contains(dimension)) {
            return getWorld(dimension);
        }

        return null;
    }

    public static DimensionRenderHelper getDimensionRenderHelper(ResourceKey<Level> dimension) {
        initializeIfNeeded();

        DimensionRenderHelper result = RENDER_HELPER_MAP.computeIfAbsent(
            dimension,
            dimensionType -> {
                return new DimensionRenderHelper(
                    getWorld(dimension)
                );
            }
        );

        Validate.isTrue(result.world.dimension() == dimension);

        return result;
    }

    @SuppressWarnings("ConstantValue")
    public static void initializeIfNeeded() {
        if (!isInitialized) {
            Validate.isTrue(
                CLIENT.level != null, "level is null"
            );
            // note: client.levelRenderer is not necessarily not null due to mixin
            Validate.isTrue(
                CLIENT.levelRenderer != null, "levelRenderer is null"
            );

            Validate.notNull(
                CLIENT.player,
                "player is null. This may be caused by prior initialization failure. The log may provide useful information."
            );
            Validate.isTrue(
                CLIENT.player.level() == CLIENT.level,
                "The player level is not the same as client level"
            );

            ResourceKey<Level> playerDimension = CLIENT.level.dimension();
            CLIENT_WORLD_MAP.put(playerDimension, CLIENT.level);
            WORLD_RENDERER_MAP.put(playerDimension, CLIENT.levelRenderer);
            // 26.2 render-split: record the main dim's extractor too — CLIENT.levelExtractor is
            // the one vanilla bound to CLIENT.levelRenderer at Minecraft.java:649. public final
            // field, read directly. This keeps WORLD_EXTRACTOR_MAP total over the renderer map so
            // dispose/reload paths find every dim's extractor (EXTRACTOR-IDENTITY).
            WORLD_EXTRACTOR_MAP.put(playerDimension, CLIENT.levelExtractor);
            RENDER_HELPER_MAP.put(
                CLIENT.level.dimension(),
                new DimensionRenderHelper(CLIENT.level)
            );

            isInitialized = true;
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private static ClientLevel createSecondaryClientWorld(ResourceKey<Level> dimension) {
        Validate.notNull(CLIENT.player, "player is null");
        Validate.isTrue(CLIENT.isSameThread(), "not on client thread");

        Set<ResourceKey<Level>> dimIds = getServerDimensions();
        if (!dimIds.contains(dimension)) {
            throw new RuntimeException("Cannot create invalid client dimension " + dimension.identifier());
        }

        isCreatingClientWorld = true;

        Profiler.get().push("create_world"); // 26.2: Minecraft.getProfiler() GONE → Profiler.get()

        int chunkLoadDistance = 3; // my own chunk manager doesn't need it

        // --- 26.2 render-split construction (re-derived from the mod's PROVEN
        //     PortalWorldManager.createRenderer + PortalContextSwitch mechanics; api-map
        //     world-loader-root §4 + GONE "LevelRenderer ctor"/"setLevel"/"allChanged"/
        //     "onResourceManagerReload" rows) ---
        // IP's 1.21.3 4-arg LevelRenderer(Minecraft, entityRD, blockEntityRD, RenderBuffers) is
        // GONE. The 9-arg 26.2 ctor derives RenderBuffers + FeatureRenderDispatcher from the
        // passed GameRenderer, so secondaries implicitly SHARE the main RenderBuffers (exactly
        // IP's shared-buffers-across-dims behavior, api-map GONE "Minecraft.renderBuffers" row);
        // per-frame buffer isolation is the render slice's pooled-buffer swap (S11
        // PortalRenderBuffersPool), NOT construction. What DOES need per-dim isolation is the
        // render STATE: 1.21.3 gave each LevelRenderer its own, 26.2 shares one via
        // GameRenderState, so give each secondary its own LevelRenderState (the LevelExtractor
        // ctor accepts any instance — api-map §4.3) and bind a per-dim LevelExtractor to it.
        LevelRenderer worldRenderer = new LevelRenderer(
            CLIENT.getEntityRenderDispatcher(),
            CLIENT.getBlockEntityRenderDispatcher(),
            CLIENT.getModelManager(),
            CLIENT.getTextureManager(),
            CLIENT.getAtlasManager(),
            CLIENT.getShaderManager(),
            CLIENT.gameRenderer,
            CLIENT.gameRenderer.mainRenderTarget().width,
            CLIENT.gameRenderer.mainRenderTarget().height
        );
        LevelRenderState worldRenderState = new LevelRenderState();
        ((LevelRendererAccessorMixin) worldRenderer)
            .seamlessportals$setLevelRenderState(worldRenderState);
        // S14-A FIX-4 (M4): isolated feature-render pipeline for this secondary (see the
        // SECONDARY_FEATURE_BUFFERS field note). Ctor shape verified against mc262
        // FeatureRenderDispatcher.java:37-58 + the identical runtime-proven call at
        // MOD:PortalWorldManager.createRenderer.
        net.minecraft.client.renderer.RenderBuffers featureBuffers =
            new net.minecraft.client.renderer.RenderBuffers(4);
        net.minecraft.client.renderer.feature.FeatureRenderDispatcher featureDispatcher =
            new net.minecraft.client.renderer.feature.FeatureRenderDispatcher(
                featureBuffers,
                CLIENT.getModelManager(),
                CLIENT.getAtlasManager(),
                CLIENT.font,
                CLIENT.gameRenderer.gameRenderState()
            );
        ((LevelRendererAccessorMixin) worldRenderer)
            .seamlessportals$setFeatureRenderDispatcher(featureDispatcher);
        // EXTRACTOR-IDENTITY (memory nether-block-freeze-orphaned-extractor): this extractor is
        // bound to the ClientLevel below at construction and stays its writer+reader for life;
        // never rebuilt after.
        LevelExtractor worldExtractor = new LevelExtractor(CLIENT, worldRenderState, worldRenderer);

        ClientLevel newWorld;
        try {
            ClientPacketListener mainNetHandler = CLIENT.player.connection;
            assert CLIENT.level != null;
            // S12-B category-(c) fix: 26.2 ClientLevel.mapData is Map<MapId, MapItemSavedData>
            // (key changed String -> MapId; IEClientLevel_Accessor already returns Map<MapId,...>).
            // The S10 locals lagged at Map<String,...>; retyped to match. See S12B-render.md §7.
            Map<MapId, MapItemSavedData> mapData = ((IEClientLevel_Accessor) CLIENT.level).ip_getMapData();

            Validate.notNull(
                dimIdToDimTypeId, "dimension type mapping is missing"
            );
            ResourceKey<DimensionType> dimensionTypeKey = dimIdToDimTypeId.get(dimension);

            if (dimensionTypeKey == null) {
                throw new IllegalStateException(
                    "Cannot find dimension type for %s in %s"
                        .formatted(dimension.identifier(), dimIdToDimTypeId)
                );
            }

            ClientLevel.ClientLevelData currentProperty =
                (ClientLevel.ClientLevelData) ((IEWorld) CLIENT.level).ip_getLevelData();
            RegistryAccess registryManager = mainNetHandler.registryAccess();
            int simulationDistance = CLIENT.level.getServerSimulationDistance();

            Holder<DimensionType> dimensionType = registryManager
                .lookupOrThrow(Registries.DIMENSION_TYPE)  // 26.2: registryOrThrow → lookupOrThrow
                .getOrThrow(dimensionTypeKey);             // 26.2: getHolderOrThrow → getOrThrow

            // currently use a separated level data object
            // day time is not shared between worlds
            ClientLevel.ClientLevelData properties = new ClientLevel.ClientLevelData(
                currentProperty.getDifficulty(),
                currentProperty.isHardcore(),
                ((IEClientLevelData) currentProperty).ip_getIsFlat()
            );

            // R1 seaLevel (SPIKE-R1 §3 "Consumption (S10)"): the 26.2 ClientLevel ctor requires a
            // per-dim sea level (final for the level's whole life). Take it from the
            // DimIdSyncPacket-synced cache. Fail-soft per the memo — if the value has not arrived
            // (dynamic-dim race / desync; near-impossible-by-construction since the dim-type map
            // above is the same packet's hard dependency) fall back to the current dim's sea level
            // and warn (rate-limited); NEVER throw (matches IP's remote-world doctrine).
            int seaLevel;
            if (dimIdToDimSeaLevel != null && dimIdToDimSeaLevel.containsKey(dimension)) {
                seaLevel = dimIdToDimSeaLevel.get(dimension);
            }
            else {
                seaLevel = CLIENT.level.getSeaLevel();
                if (LOG_LIMIT.tryDecrement()) {
                    LOGGER.warn(
                        "Sea level for {} not synced; falling back to current dim's {}",
                        dimension.identifier(), seaLevel
                    );
                }
            }

            newWorld = new ClientLevel(
                mainNetHandler,
                properties,
                dimension,
                dimensionType,
                chunkLoadDistance,
                simulationDistance,// seems that client world does not use this
                worldExtractor,    // 26.2: LevelExtractor replaces the 1.21.3 LevelRenderer arg
                CLIENT.level.isDebug(),
                CLIENT.level.getBiomeManager().biomeZoomSeed,
                seaLevel           // 26.2: NEW trailing per-dim sea level (R1)
            );

            // all worlds share the same map data map
            ((IEClientLevel_Accessor) newWorld).ip_setMapData(mapData);

            // all worlds share the same tick rate manager
            ((IEClientWorld) newWorld).ip_setTickRateManager(CLIENT.level.tickRateManager());

            // S14-A FIX-2b (B2 part b, ticklight): seed the fresh ClientLevelData's ABSOLUTE
            // gameTime from the current level. IP kept remote gameTime correct via the redirected
            // per-dim ClientboundSetTimePacket, which the F1 weather-only WorldInfoSender deviation
            // deleted. Scope (S14.6 verifier correction): the surviving IP-verbatim
            // MixinClientPacketListener.onSetTime fan-out re-syncs every non-current world at each
            // ~20-tick vanilla SetTime broadcast, so unseeded consumers would be 0-based only for
            // the creation window — this seed covers that window and keeps creation-time
            // consumers (%20 expiry stamps, animateTick %2) correct from tick one. Stays in
            // lockstep afterwards (+1/tick via the remote tick under the shared TickRateManager;
            // the MixinClientLevel tickTime HEAD-cancel keeps the shared connection clock
            // excluded; the fan-out snaps any drift).
            newWorld.setTimeFromServer(CLIENT.level.getGameTime());

            // 26.2: setLevel moved LevelRenderer → LevelExtractor. Wires the level and builds the
            // chunk infrastructure via allChanged → invalidateCompiledGeometry.
            worldExtractor.setLevel(newWorld);

            // Immediate reload so extract() has non-null sky / resource state (direct port of IP's
            // worldRenderer.onResourceManagerReload; 26.2 moved the listener onto LevelExtractor —
            // it implements ResourceManagerReloadListener, LevelExtractor.java:71).
            worldExtractor.onResourceManagerReload(CLIENT.getResourceManager());
            // R1 silent-miss closure (SPIKE-R1 / api-map §4.7 — mirror Minecraft.java:650-651):
            // register BOTH the extractor and the renderer's cloudRenderer for FUTURE resource
            // (-pack) reloads. A secondary that registers only the extractor silently misses
            // cloud-resource reloads (CloudRenderer is a SimplePreparableReloadListener with no
            // synchronous onResourceManagerReload, so it can only be reached via registration).
            ReloadableResourceManager reloadableResourceManager =
                (ReloadableResourceManager) CLIENT.getResourceManager();
            reloadableResourceManager.registerReloadListener(worldExtractor);
            reloadableResourceManager.registerReloadListener(worldRenderer.cloudRenderer());
            // NOTE (no matching unregister — accepted): 26.2's ReloadableResourceManager exposes
            // registerReloadListener ONLY (private final listeners list; no removal API,
            // ReloadableResourceManager.java:36-38). So disposeDimensionDynamically has no way to
            // unregister a dynamically-removed dim's now-dead (level=null) extractor + cloudRenderer,
            // and they keep receiving future resource-pack reloads. This is inert until S13 and a no-op
            // for the typical fixed 2-3 dim setup; only unbounded under dynamic-dim CHURN. NOT fixed
            // here: neither IP nor the proven PortalWorldManager.createRenderer registers these at all
            // (they rely on the retained _onWorldRendererReloaded manual propagation, kept below), so
            // there is no IP precedent for cleanup, and reaching the private list to unregister would
            // require a non-IP vanilla accessor. If dynamic-dim churn ever matters, add that accessor
            // + a matching unregister in disposeDimensionDynamically at cutover.

            CLIENT_WORLD_MAP.put(dimension, newWorld);
            WORLD_RENDERER_MAP.put(dimension, worldRenderer);
            WORLD_EXTRACTOR_MAP.put(dimension, worldExtractor);
            SECONDARY_FEATURE_BUFFERS.put(dimension, featureBuffers);
            SECONDARY_FEATURE_DISPATCHERS.put(dimension, featureDispatcher);

            // C2-0 probe P1 (migration/C2_DESIGN.md §4 P1): lever-gated, flag-gated, sodium-gated,
            // one-shot per dim. No-op (and loads no Sodium type) without -Dseamlessportals.compatProbe=true.
            qouteall.imm_ptl.core.compat.SodiumCompatProbe.probeSecondaryWorldRenderer(dimension, worldRenderer);

            LOGGER.info("Client World Created {}", dimension.identifier());
        }
        catch (Exception e) {
            throw new IllegalStateException(
                "Creating Client World " + dimension.identifier() + " " + CLIENT_WORLD_MAP.keySet(),
                e
            );
        }
        finally {
            isCreatingClientWorld = false;
            Profiler.get().pop();
        }

        CLIENT_WORLD_LOAD_EVENT.invoker().accept(newWorld);

        return newWorld;
    }

    /**
     * S14-A FIX-1 (audit BLOCKER B1, links teleport+drivercore — the migration corpus' declared
     * top-risk §11.1 "CRITICAL PORT-FORWARD, no IP source"): the 26.2 extract-driver
     * promote/demote at a flag-ON cross-dim crossing.
     *
     * <p>WHY: IP 1.21.3's {@code client.level = toWorld} + {@code ip_setWorldRenderer} was the
     * COMPLETE render cutover because the 1.21.3 LevelRenderer was itself the extractor. 26.2
     * split per-frame extraction onto the single global {@code Minecraft.levelExtractor} (public
     * final, bound once to the BOOT renderer at Minecraft.java:649; driven by
     * GameRenderer.extract:389) — swapping only level+renderer leaves extract() populating the OLD
     * renderer's visibleSections from the OLD world while render() draws the promoted renderer's
     * never-fed isolated state: blank/stale main terrain on the first walk-through (the block era
     * PROVED this exact failure: log DIAG #1 oldRenderer=1225 visibleSections, promoted=0). The
     * faithful re-expression of IP's dim cutover therefore re-points the extract driver too —
     * re-derived from the runtime-proven block-era plumbing (MOD:PortalWorldManager.promoteToMain
     * :1145-1197 + demoteFromMain:1627-1720), which EXCLUSIVITY_LEDGER rows 12/16 suppress
     * flag-ON precisely so this replacement can own the seam.
     *
     * <p>Raw field re-points, NEVER {@code LevelExtractor.setLevel()} — that calls allChanged() →
     * invalidateCompiledGeometry and WIPES the cached meshes the seamless swap exists to preserve;
     * {@code lastViewDistance} is synced for the same reason (extract()'s render-distance guard
     * would otherwise allChanged on the first post-crossing frame).
     *
     * <p>Caller: {@link qouteall.imm_ptl.core.teleportation.ClientTeleportationManager}
     * .changePlayerDimension, immediately after the {@code client.level}/{@code levelRenderer}
     * swap. IP's {@code vanillaTerrainSetupOverride = 1} (set by both teleport callers after this
     * returns) covers the first frame's terrain setup with IP's non-multithreaded discovery while
     * the SOG rebuild lands — via the S14.7-re-sited consumer
     * (MixinLevelExtractor_TerrainSetupOverride at applyFrustum RETURN; the flag was WRITE-ONLY
     * on 26.2 before that, fix-verify MAJOR), triggered by the needsFrustumUpdate force below.
     */
    public static void promoteAndDemoteOnPlayerDimensionChange(
        ClientLevel fromWorld, ClientLevel toWorld
    ) {
        // S14.47 zero-lag hunt: whole-promote wall time, folded into the flash-probe row (pMs=).
        long promoteT0 = System.nanoTime();
        ResourceKey<Level> fromDim = fromWorld.dimension();
        ResourceKey<Level> toDim = toWorld.dimension();

        LevelRenderer promotedRenderer = WORLD_RENDERER_MAP.get(toDim);
        LevelRenderer demotedRenderer = WORLD_RENDERER_MAP.get(fromDim);
        Validate.notNull(promotedRenderer, "no renderer for promoted dim %s", toDim.identifier());
        Validate.notNull(demotedRenderer, "no renderer for demoted dim %s", fromDim.identifier());

        // S14.43 round-2 fold (verify wf_4089f91b-610 MAJOR): pre-resolve the promoted dim's
        // CURRENT delta window by live chunk truth BEFORE the first post-promote main extract
        // captures+flips it for vanilla's UNRESOLVED application — on the cold path (never-
        // rendered dest, pump gate never opened) the accumulated window's added∩removed pairs
        // would net-evict the arrival chunks (the parked-BFS wipe). Resolution-only: the window
        // still applies wholesale as the sole loadedChunks re-seeder. See the hook's javadoc.
        qouteall.imm_ptl.core.render.SecondaryWorldRenderCore.preResolvePromotedWindow(toWorld);

        // ===== PROMOTE toDim: the global main extractor now drives the promoted renderer ========
        LevelExtractor toDimPerDimExtractor = WORLD_EXTRACTOR_MAP.get(toDim);
        com.warwa.seamlessportals.mixin.client.LevelExtractorAccessor mainExt =
            (com.warwa.seamlessportals.mixin.client.LevelExtractorAccessor) (Object) CLIENT.levelExtractor;
        // S14.48 (the remesh-wave fix, capture-proven compQ=3042): capture fromDim's LIVE tracker
        // BEFORE the promote overwrites mainExt's tracker field — this is the object fromDim's
        // dirty-marks accumulated on all main stint. The demote below hands it to the demoted
        // extractor instead of a FRESH one: a fresh SectionUpdateTracker is ALL-DIRTY by
        // construction (vanilla news it only at level init where everything genuinely needs
        // compiling), so every crossing was scheduling a full-dim background remesh of the dim
        // just left — adopted back on return as the visible mid-distance remesh wave.
        net.minecraft.client.SectionUpdateTracker fromDimLiveTracker =
            mainExt.seamlessportals$getSectionUpdateTracker();
        mainExt.seamlessportals$setLevelRenderer(promotedRenderer);
        mainExt.seamlessportals$setLevel(toWorld);
        // Sync lastViewDistance so the FIRST post-promote extract() doesn't trip its
        // getEffectiveRenderDistance() != lastViewDistance guard -> allChanged -> mesh wipe.
        mainExt.seamlessportals$setLastViewDistance(CLIENT.options.getEffectiveRenderDistance());
        // S14.7 (fix-verify MAJOR, cold promote): 26.2 defers dispatcher/viewArea/graph creation
        // into the first EXTRACT — a dest renderer that never extracted (its portal never in the
        // view frustum; forced teleport into a never-viewed dim) has none of them, and the warm
        // path's mesh-preserving re-points would leave the first post-promote frame to NPE
        // (applyFrustum on a never-reset SOG / render() on a null viewArea). IP was immune
        // (creation-time setLevel->allChanged built everything eagerly); re-express that here:
        // fresh toWorld-bound tracker + the invalidate one-shot, so the first extract runs
        // invalidateCompiledGeometry (creates dispatcher+viewArea+resets the SOG) BEFORE render().
        // Done via direct flag+tracker writes, NOT allChanged() — the S14.5 reload-cascade mixin
        // TAIL-fires on mc.levelExtractor.allChanged and must not sweep other dims mid-crossing.
        // There are no meshes to preserve on this branch by definition.
        boolean coldPromote = promotedRenderer.sectionRenderDispatcher() == null;
        if (coldPromote) {
            mainExt.seamlessportals$setSectionUpdateTracker(
                new net.minecraft.client.SectionUpdateTracker(
                    toWorld, CLIENT.options.getEffectiveRenderDistance())
            );
            mainExt.seamlessportals$setShouldInvalidateCompiledGeometry(true);
        }
        else if (toDimPerDimExtractor != null && toDimPerDimExtractor != CLIENT.levelExtractor) {
            // WARM path: adopt the per-dim extractor's CURRENT tracker — the object toWorld's
            // dirty-marks have been landing on while it was a secondary (writer/reader stay one
            // object).
            mainExt.seamlessportals$setSectionUpdateTracker(
                ((com.warwa.seamlessportals.mixin.client.LevelExtractorAccessor) (Object) toDimPerDimExtractor)
                    .seamlessportals$getSectionUpdateTracker()
            );
            // S14.9 (final verify round): make the warm path's mesh-preservation contract
            // explicit — a pending invalidate one-shot from a same-tick cold promote (A->B->A
            // double-crossing) must not survive onto the warm-promoted renderer and wipe the
            // meshes this path exists to preserve.
            mainExt.seamlessportals$setShouldInvalidateCompiledGeometry(false);
        }
        // The promoted renderer joins the SHARED main render state (extract-writes and
        // render-reads must be one object) and the MAIN feature dispatcher (vanilla's main pass
        // drives the renderer's own dispatcher field; its isolated one stays registered in
        // SECONDARY_FEATURE_DISPATCHERS for its next demoted stint).
        ((LevelRendererAccessorMixin) promotedRenderer).seamlessportals$setLevelRenderState(
            CLIENT.gameRenderer.gameRenderState().levelRenderState
        );
        ((LevelRendererAccessorMixin) promotedRenderer).seamlessportals$setFeatureRenderDispatcher(
            CLIENT.gameRenderer.featureRenderDispatcher()
        );
        // SYMMETRIC RE-POINT (memory nether-block-freeze-orphaned-extractor): while promoted, the
        // level's own final levelExtractor field must be mc.levelExtractor ITSELF — any
        // allChanged() REPLACES the extractor's tracker, and only writing THROUGH the extractor
        // survives that replacement. A tracker SNAPSHOT share would freeze block updates after the
        // first F3+A/render-distance change.
        ((com.warwa.seamlessportals.mixin.client.ClientLevelExtractorAccessor) toWorld)
            .seamlessportals$setLevelExtractor(CLIENT.levelExtractor);
        WORLD_EXTRACTOR_MAP.put(toDim, CLIENT.levelExtractor);
        // Re-prime the promoted renderer: stale portal-view visibleSections hold RenderSection
        // nodes the post-teleport reposition relocates (block-era NPE class) — clear them;
        // invalidate schedules the SOG async rebuild; needsFrustumUpdate forces the FIRST
        // post-promote applyFrustum so a warm currentGraph repopulates instantly while the rebuild
        // refines (block-era "instant repaint"). Cold branch: frame 1 takes the invalidate path
        // (no applyFrustum — SOG safely reset by invalidateCompiledGeometry), frame 2's forced
        // applyFrustum then runs the S14.7 terrain-setup override discovery on the fresh grid.
        promotedRenderer.clearVisibleSections();
        var promotedSog = promotedRenderer.sectionOcclusionGraph();
        if (promotedSog != null) {
            promotedSog.invalidate();
            ((com.warwa.seamlessportals.mixin.client.SectionOcclusionGraphAccessorMixin) (Object) promotedSog)
                .seamlessportals$getNeedsFrustumUpdate().set(true);
        }
        qouteall.imm_ptl.core.render.SecondaryWorldRenderCore.onDimensionMainStatusChanged(toDim);

        // ===== DEMOTE fromDim: the outgoing renderer becomes a self-contained secondary =========
        // Fresh isolated LevelRenderState + a per-dim LevelExtractor bound to it, so the
        // portal-view dest pass (looking BACK through the portal) has a live extract substrate
        // (the block-era demote proved the missing-extractor failure: extract skipped, entities/
        // particles never populate in the look-back view).
        LevelRenderState demotedState = new LevelRenderState();
        ((LevelRendererAccessorMixin) demotedRenderer).seamlessportals$setLevelRenderState(demotedState);
        LevelExtractor demotedExtractor = new LevelExtractor(CLIENT, demotedState, demotedRenderer);
        com.warwa.seamlessportals.mixin.client.LevelExtractorAccessor dea =
            (com.warwa.seamlessportals.mixin.client.LevelExtractorAccessor) (Object) demotedExtractor;
        dea.seamlessportals$setLevel(fromWorld);
        // S14.48: tracker CONTINUITY (see the capture at the promote head) — reuse fromDim's
        // live main-stint tracker; pending dirty-marks survive exactly, and no all-dirty fresh
        // tracker schedules a phantom full-dim remesh. Null-guard: fall back to fresh (all-dirty
        // = the previous behavior) on any path where the main extractor had none.
        dea.seamlessportals$setSectionUpdateTracker(
            fromDimLiveTracker != null
                ? fromDimLiveTracker
                : new net.minecraft.client.SectionUpdateTracker(
                    fromWorld, CLIENT.options.getEffectiveRenderDistance())
        );
        // Same mesh-preserving guard as promote: the demoted dim's FIRST dest extract must not
        // trip the render-distance allChanged (a ~190ms SOG waitAndReset + full re-mesh of the
        // meshes this demote preserves — block-era measured).
        dea.seamlessportals$setLastViewDistance(CLIENT.options.getEffectiveRenderDistance());
        // S14.9 (final verify round): a NEVER-BUILT demoted renderer (same-tick double-crossing
        // through a cold dim) must self-heal at its first dest extract — set the invalidate
        // one-shot so dispatcher/viewArea/graph get created (world-creation gets this from
        // setLevel->allChanged; the demote's raw writes deliberately skip allChanged).
        if (demotedRenderer.sectionRenderDispatcher() == null) {
            dea.seamlessportals$setShouldInvalidateCompiledGeometry(true);
        }
        demotedExtractor.onResourceManagerReload(CLIENT.getResourceManager());
        // The other half of the nether-block-freeze fix: the level keeps writing dirty-marks
        // through its own extractor field — point it at the CURRENT per-dim extractor so writer
        // and reader stay on one tracker across every promote/demote cycle.
        ((com.warwa.seamlessportals.mixin.client.ClientLevelExtractorAccessor) fromWorld)
            .seamlessportals$setLevelExtractor(demotedExtractor);
        WORLD_EXTRACTOR_MAP.put(fromDim, demotedExtractor);
        // Isolated feature pipeline for the demoted renderer (FIX-4 invariant: only the CURRENT
        // main renderer carries the main dispatcher). The boot dim arrives here without one.
        net.minecraft.client.renderer.feature.FeatureRenderDispatcher demotedDispatcher =
            SECONDARY_FEATURE_DISPATCHERS.get(fromDim);
        if (demotedDispatcher == null) {
            net.minecraft.client.renderer.RenderBuffers demotedFeatureBuffers =
                new net.minecraft.client.renderer.RenderBuffers(4);
            demotedDispatcher = new net.minecraft.client.renderer.feature.FeatureRenderDispatcher(
                demotedFeatureBuffers,
                CLIENT.getModelManager(),
                CLIENT.getAtlasManager(),
                CLIENT.font,
                CLIENT.gameRenderer.gameRenderState()
            );
            SECONDARY_FEATURE_BUFFERS.put(fromDim, demotedFeatureBuffers);
            SECONDARY_FEATURE_DISPATCHERS.put(fromDim, demotedDispatcher);
        }
        ((LevelRendererAccessorMixin) demotedRenderer)
            .seamlessportals$setFeatureRenderDispatcher(demotedDispatcher);
        qouteall.imm_ptl.core.render.SecondaryWorldRenderCore.onDimensionMainStatusChanged(fromDim);

        // Per-crossing event, not per-frame — logging discipline holds. The (cold)/(warm) tag
        // (S14.24) converts future first-frame reports into branch-attributed evidence. S14.42
        // enrichment: the promote-instant state snapshot + probe arming (the far-walk terrain-wipe
        // hunt — every promote now emits ~20s of 1Hz render-chain lines).
        LOGGER.info(
            "[S14 crossing cutover] extract driver promoted {} -> {} ({}) dispNull={} sogNull={} "
                + "viewAreaNull={} ldChunks={}",
            fromDim.identifier(), toDim.identifier(), coldPromote ? "cold" : "warm",
            promotedRenderer.sectionRenderDispatcher() == null,
            promotedRenderer.sectionOcclusionGraph() == null,
            ((qouteall.imm_ptl.core.ducks.IEWorldRenderer) promotedRenderer)
                .ip_getBuiltChunkStorage() == null,
            toWorld.getChunkSource().getLoadedChunksCount()
        );
        qouteall.imm_ptl.core.render.RenderChainProbe.armOnPromote();
        // S14.45: arm the teleport-flash/stutter capture (batched ring dump ~40 frames later).
        qouteall.imm_ptl.core.render.TeleportFlashProbe.armOnPromote(
            fromDim.identifier().getPath(), toDim.identifier().getPath(), coldPromote);
        qouteall.imm_ptl.core.render.TeleportFlashProbe.promoteNanosThisFrame +=
            System.nanoTime() - promoteT0;
    }

    public static Set<ResourceKey<Level>> getServerDimensions() {
        assert CLIENT.player != null;
        return CLIENT.player.connection.levels();
    }

    public static Collection<ClientLevel> getClientWorlds() {
        Validate.isTrue(isInitialized);

        return CLIENT_WORLD_MAP.values();
    }

    private static boolean isReloadingOtherWorldRenderers = false;

    @SuppressWarnings("Convert2MethodRef")
    public static void _onWorldRendererReloaded() {
        Validate.isTrue(CLIENT.isSameThread());
        if (CLIENT.level != null) {
            LOGGER.info("WorldRenderer reloaded {}", CLIENT.level.dimension().identifier());
        }

        if (isReloadingOtherWorldRenderers) {
            return;
        }
        if (PortalRendering.isRendering()) {
            return;
        }
        if (ClientWorldLoader.getIsCreatingClientWorld()) {
            return;
        }

        isReloadingOtherWorldRenderers = true;

        List<ResourceKey<Level>> toReload = WORLD_RENDERER_MAP.keySet().stream()
            .filter(d -> d != CLIENT.level.dimension()).toList();

        for (ResourceKey<Level> dim : toReload) {
            ClientLevel world = CLIENT_WORLD_MAP.get(dim);
            Validate.notNull(world, "missing client world %s", dim.identifier());
            withSwitchedWorld(
                world,
                () -> {
                    // 26.2: allChanged() moved LevelRenderer → LevelExtractor (api-map CHANGED).
                    // Call the swapped dim's own construction-bound extractor; the withSwitchedWorld
                    // context is retained verbatim from IP (its levelRenderer field is actually
                    // mutable — cannot be a method reference) though on 26.2 the extractor operates
                    // on its own bound renderer independent of the swapped CLIENT.levelRenderer.
                    WORLD_EXTRACTOR_MAP.get(dim).allChanged();
                }
            );
        }

        isReloadingOtherWorldRenderers = false;
    }

    /**
     * It will not switch the dimension of client player
     */
    @SuppressWarnings({"ReassignedVariable", "DataFlowIssue"})
    public static <T> T withSwitchedWorld(ClientLevel newWorld, Supplier<T> supplier) {
        Validate.isTrue(CLIENT.isSameThread(), "not on client thread");
        Validate.isTrue(CLIENT.player != null, "player is null");

        ClientPacketListener networkHandler = CLIENT.getConnection();
        assert networkHandler != null;

        ClientLevel originalWorld = CLIENT.level;
        LevelRenderer originalWorldRenderer = CLIENT.levelRenderer;
        ClientLevel originalNetHandlerWorld = networkHandler.getLevel();
        boolean originalIsWorldSwitched = isWorldSwitched;

        LevelRenderer newWorldRenderer = getWorldRenderer(newWorld.dimension());

        Validate.notNull(newWorldRenderer, "new world renderer is null");

        // 26.2 EXTRACTOR-IDENTITY note (memory nether-block-freeze-orphaned-extractor): the swap
        // set is deliberately NOT extended with CLIENT.levelExtractor. A ClientLevel routes its
        // block/section dirties through the extractor it was CONSTRUCTED with (its final
        // levelExtractor field), NOT through mc.levelExtractor — so the mod's proven
        // PortalWorldManager.tickRemoteWorlds swaps neither the renderer nor the extractor during
        // remote tick, and dest updates still land on the dest renderer. IP's mc.levelRenderer swap
        // (via IEMinecraftClient below) is retained verbatim for structural fidelity but is inert
        // on 26.2 (mc.levelExtractor, bound once to the ORIGINAL renderer at Minecraft.java:649,
        // keeps driving that renderer regardless of the field swap; no extract runs during tick).
        CLIENT.level = newWorld;
        ((IEParticleManager) CLIENT.particleEngine).ip_setWorld(newWorld);
        ((IEMinecraftClient) CLIENT).ip_setWorldRenderer(newWorldRenderer);
        ((IEClientPlayNetworkHandler) networkHandler).ip_setWorld(newWorld);
        isWorldSwitched = true;

        try {
            return supplier.get();
        }
        finally {
            if (CLIENT.level != newWorld) {
                LOGGER.error("Respawn packet should not be redirected");
                originalWorld = CLIENT.level;
                originalWorldRenderer = CLIENT.levelRenderer;
                // client.levelRenderer is not final by mixin.
            }

            CLIENT.level = originalWorld;
            ((IEMinecraftClient) CLIENT).ip_setWorldRenderer(originalWorldRenderer);
            ((IEParticleManager) CLIENT.particleEngine).ip_setWorld(originalWorld);
            ((IEClientPlayNetworkHandler) networkHandler).ip_setWorld(originalNetHandlerWorld);
            isWorldSwitched = originalIsWorldSwitched;
        }
    }

    public static void withSwitchedWorld(ClientLevel newWorld, Runnable runnable) {
        withSwitchedWorld(newWorld, () -> {
            runnable.run();
            return null;
        });
    }

    public static void withSwitchedWorldFailSoft(ResourceKey<Level> dim, Runnable runnable) {
        ClientLevel world = getOptionalWorld(dim);

        if (world == null) {
            LOGGER.error(
                "Ignoring redirected task of invalid dimension {}", dim.identifier(), new Throwable()
            );
            return;
        }

        withSwitchedWorld(world, runnable);
    }

    public static boolean getIsWorldSwitched() {
        return isWorldSwitched;
    }

    public static class RemoteCallables {
        public static void checkBiomeRegistry(
            Map<String, Integer> idMap
        ) {
            LocalPlayer player = Minecraft.getInstance().player;
            assert player != null;
            RegistryAccess registryAccess = player.connection.registryAccess();
            Registry<Biome> biomes = registryAccess.lookupOrThrow(Registries.BIOME); // 26.2: registryOrThrow → lookupOrThrow

            for (Map.Entry<String, Integer> entry : idMap.entrySet()) {
                Identifier id = McHelper.newResourceLocation(entry.getKey()); // 26.2: ResourceLocation → Identifier
                int expectedId = entry.getValue();

                if (biomes.getId(biomes.getValue(id)) != expectedId) { // 26.2: Registry.get(id) → getValue(id)
                    LOGGER.error("Biome id mismatch: {} {}", id, expectedId);
                }
            }

            if (idMap.size() != biomes.keySet().size()) {
                LOGGER.error("Biome id mismatch: size not equal");
            }

            LOGGER.info("Biome id check finished");
        }
    }
}
