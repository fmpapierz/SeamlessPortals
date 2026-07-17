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
            isClientRemoteTicking = true;
            CLIENT_WORLD_MAP.values().forEach(world -> {
                if (CLIENT.level != world) {
                    tickRemoteWorld(world);
                }
            });
            // 26.2: LevelRenderer.tick() is GONE (SPIKE-R1 §4.3 "the worldRenderer.tick()
            // loop is DELETED with a documented role transfer, not replaced"). Its only
            // 1.21.3 duty — expiring stale BlockDestructionProgress — moved onto ClientLevel
            // (ClientLevel.tick → removeBlockBreakingProgress, same gameTime%20 / 400-tick
            // algorithm), which tickRemoteWorld above already runs via newWorld.tick(() -> true);
            // the render-side consumption of destruction progress happens per-frame in
            // extraction. Nothing renderer-side remains to tick per game tick.
            isClientRemoteTicking = false;
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
            // deleted; without the seed every dest-world gameTime consumer (portal-animation
            // timing, %20 expiry windows, animateTick %2) runs 0-based. Stays in lockstep
            // afterwards (+1/tick via the remote tick under the shared TickRateManager; the
            // MixinClientLevel tickTime HEAD-cancel keeps the shared connection clock excluded).
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
