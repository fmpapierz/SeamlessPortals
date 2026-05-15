package com.warwa.seamlessportals.compat;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Reflective bridge to Sodium's per-{@link LevelRenderer} API. Used to
 * drive Sodium's chunk pipeline for our cached secondary renderers so
 * portal-view FBO renders actually produce chunk meshes under Sodium.
 *
 * <p>Sodium installs per-LevelRenderer state via mixin: each renderer
 * gets a {@code SodiumWorldRenderer} accessible through the mixin-added
 * interface {@code LevelRendererExtension.sodium$getWorldRenderer()}.
 * That renderer has:
 *
 * <ul>
 *   <li>{@code setLevel(ClientLevel)} — switch the renderer to a level,
 *       loads chunks, initializes chunk graph.</li>
 *   <li>{@code setupTerrain(Camera, Viewport, FogParameters, boolean,
 *       boolean, Matrix4f)} — drive chunk-graph update for a given
 *       camera. Must be called each frame the renderer is being used.</li>
 *   <li>{@code scheduleTerrainUpdate()} — force the chunk graph to
 *       rebuild on the next setupTerrain call.</li>
 * </ul>
 *
 * <p>For our portal-view rendering: before invoking
 * {@code destRenderer.renderLevel(...)} inside
 * {@link com.warwa.seamlessportals.render.PortalContextSwitch#withSwitchedWorld},
 * we call this bridge to (1) ensure Sodium's per-dest-renderer state is
 * loaded with the dest ClientLevel, and (2) run setupTerrain with the
 * virtual camera so Sodium's chunk graph for the secondary renderer is
 * up-to-date for the portal-view camera angle (not the player's actual
 * camera angle).
 *
 * <p>Reflective because we don't add a compile-time Sodium dep. All
 * calls are guarded by {@link SodiumCompat#isSodiumLoaded()} so the
 * vanilla path is unaffected when Sodium isn't installed.
 */
public final class SodiumBridge {

    private SodiumBridge() {}

    // Cached reflection handles. Populated lazily on first use after
    // confirming Sodium is loaded. Volatile so the publish is safe for
    // the render-thread/init-thread interaction.
    private static volatile boolean handlesInited = false;
    private static volatile Method getWorldRendererMethod;     // LevelRendererExtension.sodium$getWorldRenderer
    private static volatile Method setLevelMethod;             // SodiumWorldRenderer.setLevel(ClientLevel)
    private static volatile Method setupTerrainMethod;         // SodiumWorldRenderer.setupTerrain(Camera, Viewport, FogParameters, bool, bool, ChunkRenderMatrices)
    private static volatile Method scheduleUpdateMethod;       // SodiumWorldRenderer.scheduleTerrainUpdate()
    private static volatile Constructor<?> viewportCtor;        // new Viewport(Frustum, Vector3d)
    private static volatile Constructor<?> sodiumFrustumCtor;   // new ViewCullingFrustum (or equivalent)
    private static volatile Class<?> sodiumFrustumClass;
    private static volatile Object fogParamsNone;              // FogParameters.NONE static field value
    private static volatile Method scheduleRebuildForChunkMethod;       // SodiumWorldRenderer.scheduleRebuildForChunk(int, int, int, boolean)
    private static volatile java.lang.reflect.Field renderSectionManagerField; // SodiumWorldRenderer.renderSectionManager
    private static volatile Method onSectionAddedMethod;                // RenderSectionManager.onSectionAdded(int, int, int)
    private static volatile Method onChunkAddedMethod;                  // RenderSectionManager.onChunkAdded(int, int)
    private static volatile Method getVisibleChunkCountMethod;          // SodiumWorldRenderer.getVisibleChunkCount()
    private static volatile Constructor<?> chunkRenderMatricesCtor;     // new ChunkRenderMatrices(Matrix4fc, Matrix4fc)
    private static volatile Class<?> chunkRenderMatricesClass;
    private static volatile Method getChunkTrackerMethod;               // ChunkTrackerHolder.get(ClientLevel)
    private static volatile Method onChunkStatusAddedMethod;            // ChunkTracker.onChunkStatusAdded(int, int, int)
    private static volatile Method enterManagedCodeMethod;              // RenderDevice.enterManagedCode()
    private static volatile Method exitManagedCodeMethod;               // RenderDevice.exitManagedCode()

    private static void initHandlesIfNeeded() {
        if (handlesInited) return;
        synchronized (SodiumBridge.class) {
            if (handlesInited) return;
            try {
                Class<?> extClass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.world.LevelRendererExtension");
                getWorldRendererMethod = extClass.getMethod("sodium$getWorldRenderer");

                Class<?> swrClass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
                setLevelMethod = swrClass.getMethod("setLevel", ClientLevel.class);
                scheduleUpdateMethod = swrClass.getMethod("scheduleTerrainUpdate");
                scheduleRebuildForChunkMethod = swrClass.getMethod("scheduleRebuildForChunk",
                    int.class, int.class, int.class, boolean.class);
                getVisibleChunkCountMethod = swrClass.getMethod("getVisibleChunkCount");

                // Reach into SodiumWorldRenderer.renderSectionManager so we
                // can call onSectionAdded / onChunkAdded directly on the
                // secondary renderer's chunk graph. Even though Sodium's
                // own setupTerrain → processChunkEvents drains the
                // per-ClientLevel ChunkTracker, calling onSectionAdded
                // directly is a belt-and-suspenders fallback in case
                // events get missed.
                renderSectionManagerField = swrClass.getDeclaredField("renderSectionManager");
                renderSectionManagerField.setAccessible(true);

                Class<?> rsmClass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager");
                onSectionAddedMethod = rsmClass.getMethod("onSectionAdded",
                    int.class, int.class, int.class);
                onChunkAddedMethod = rsmClass.getMethod("onChunkAdded",
                    int.class, int.class);

                Class<?> viewportClass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.viewport.Viewport");
                Class<?> frustumClass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum");
                viewportCtor = viewportClass.getConstructor(frustumClass, Vector3d.class);
                sodiumFrustumClass = frustumClass;

                Class<?> fogParamsClass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.util.FogParameters");
                fogParamsNone = fogParamsClass.getField("NONE").get(null);

                // Sodium {@code setupTerrain} has two API variants in the
                // wild we need to support:
                //
                // <ul>
                //   <li>0.6.x / earlier: 6th param is {@code org.joml.Matrix4f}
                //       — a combined projection*view (PV) cull matrix.</li>
                //   <li>0.8.x / newer: 6th param is
                //       {@code ChunkRenderMatrices(Matrix4fc projection,
                //       Matrix4fc modelView)} — a record wrapping both
                //       matrices.</li>
                // </ul>
                //
                // We enumerate setupTerrain methods on SodiumWorldRenderer,
                // find one with arity 6, then inspect its 6th param to
                // decide which API variant we're dealing with. This avoids
                // strict {@code getMethod(name, ...types)} signature
                // matching — strict matching fails when the parameter
                // {@code Class<?>} object doesn't exactly match (e.g.
                // intermediary vs mojang mapping for {@code Camera}).
                Method foundSetupTerrain = null;
                for (Method m : swrClass.getMethods()) {
                    if (m.getName().equals("setupTerrain") && m.getParameterCount() == 6) {
                        foundSetupTerrain = m;
                        break;
                    }
                }
                if (foundSetupTerrain == null) {
                    throw new NoSuchMethodException(
                        "SodiumWorldRenderer.setupTerrain (6-arg) not found");
                }
                setupTerrainMethod = foundSetupTerrain;

                // Determine the 6th-param API variant.
                Class<?>[] paramTypes = setupTerrainMethod.getParameterTypes();
                Class<?> sixthParam = paramTypes[5];
                if (sixthParam.equals(Matrix4f.class)) {
                    // 0.6.x variant — 6th arg is PV cull matrix. No
                    // ChunkRenderMatrices to construct; setupTerrainForCamera
                    // will pass the combined matrix directly.
                    chunkRenderMatricesClass = null;
                    chunkRenderMatricesCtor = null;
                } else {
                    // 0.8.x variant — 6th arg is ChunkRenderMatrices record.
                    // Look up its (Matrix4fc, Matrix4fc) constructor.
                    chunkRenderMatricesClass = sixthParam;
                    chunkRenderMatricesCtor = chunkRenderMatricesClass.getConstructor(
                        org.joml.Matrix4fc.class, org.joml.Matrix4fc.class);
                }

                // ChunkTrackerHolder bridge — Sodium attaches per-ClientLevel
                // ChunkTracker via ClientLevelMixin. We use it to manually
                // inject chunk-load events when we replaceWithPacketData
                // bypasses Sodium's own ClientChunkCacheMixin path. In
                // practice the mixin DOES fire on our manual feeds (it
                // injects on replaceWithPacketData), so this is a backup.
                Class<?> ctHolderClass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder");
                getChunkTrackerMethod = ctHolderClass.getMethod("get", ClientLevel.class);
                Class<?> ctClass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker");
                onChunkStatusAddedMethod = ctClass.getMethod("onChunkStatusAdded",
                    int.class, int.class, int.class);

                // RenderDevice managed-context guard — Sodium asserts on
                // {@code RenderDevice.enterManagedCode/exitManagedCode}
                // bracketing for any call into its OpenGL command list /
                // chunk graph. Calling {@code setupTerrain} or {@code setLevel}
                // outside the managed context throws
                // {@code IllegalStateException: Tried to access device from
                // unmanaged context}. Sodium's own LevelRenderer mixin wraps
                // each call in enter/exitManagedCode, so we must do the same
                // for our reflective entry points.
                Class<?> renderDeviceClass = Class.forName(
                    "net.caffeinemc.mods.sodium.client.gl.device.RenderDevice");
                enterManagedCodeMethod = renderDeviceClass.getMethod("enterManagedCode");
                exitManagedCodeMethod = renderDeviceClass.getMethod("exitManagedCode");

                handlesInited = true;
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS SODIUM] Bridge initialized — Sodium API hooks resolved (ChunkRenderMatrices ctor + ChunkTracker hooks)");
            } catch (Throwable e) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS SODIUM] Bridge init failed: {} — sodium-specific render hooks disabled",
                    e.toString());
                handlesInited = true; // don't keep retrying
            }
        }
    }

    /**
     * Make sure Sodium's per-{@code renderer} state is loaded with
     * {@code expectedLevel}. If it's already loaded with the right level
     * this is a no-op. Otherwise calls
     * {@code SodiumWorldRenderer.setLevel(level)} which unloads the prior
     * level and loads the new one (heavy — but only fires when the
     * renderer's tracked level actually changed, which for a cached
     * secondary renderer happens at most when it's first wired up to a
     * dim).
     */
    /**
     * Enter Sodium's RenderDevice managed-code context. Idempotent /
     * reentrant guarded by Sodium internally. Pair every call with
     * {@link #exitManagedCode()}, ideally via try/finally.
     */
    private static void enterManagedCode() {
        if (enterManagedCodeMethod == null) return;
        try {
            enterManagedCodeMethod.invoke(null);
        } catch (Throwable ignored) {
            // Best-effort; we'd rather attempt the Sodium call and fail
            // there than skip our work just because the device-context
            // guard tripped.
        }
    }

    private static void exitManagedCode() {
        if (exitManagedCodeMethod == null) return;
        try {
            exitManagedCodeMethod.invoke(null);
        } catch (Throwable ignored) {}
    }

    public static void ensureSodiumLevel(LevelRenderer renderer, ClientLevel expectedLevel) {
        if (!SodiumCompat.isSodiumLoaded()) return;
        if (renderer == null || expectedLevel == null) return;
        initHandlesIfNeeded();
        if (getWorldRendererMethod == null || setLevelMethod == null) return;
        try {
            Object swr = getWorldRendererMethod.invoke(renderer);
            if (swr == null) return;
            // SodiumWorldRenderer has a 'level' field but no public getter.
            // We just call setLevel unconditionally; Sodium's setLevel is
            // idempotent (early-returns when level == this.level).
            //
            // Wrapped in managed-code context because setLevel transitively
            // creates GL resources (RenderSectionManager → ChunkRenderer →
            // shader uniform buffers) which trip Sodium's RenderDevice
            // assertions if invoked outside enter/exitManagedCode.
            enterManagedCode();
            try {
                setLevelMethod.invoke(swr, expectedLevel);
            } finally {
                exitManagedCode();
            }
        } catch (Throwable e) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS SODIUM] ensureSodiumLevel failed: {}", e.toString());
        }
    }

    /**
     * Drive Sodium's chunk-graph update for {@code renderer} at the given
     * camera. Called before {@code destRenderer.renderLevel} so Sodium's
     * draw step has a current graph for the portal-view camera angle.
     *
     * <p>{@code viewMatrix} and {@code projMatrix} must already be the
     * matrices that will be passed to {@code renderLevel} so frustum
     * culling matches actual rendering.
     */
    public static void setupTerrainForCamera(
            LevelRenderer renderer, Camera virtualCamera,
            Matrix4f viewMatrix, Matrix4f projMatrix) {
        if (!SodiumCompat.isSodiumLoaded()) return;
        if (renderer == null || virtualCamera == null) return;
        initHandlesIfNeeded();
        if (setupTerrainMethod == null || viewportCtor == null) return;
        try {
            Object swr = getWorldRendererMethod.invoke(renderer);
            if (swr == null) return;

            // Build a Sodium Viewport from the vanilla Frustum + camera pos.
            // We translate the vanilla Frustum via reflection — Sodium's
            // Frustum has a different class but accepts our cull data.
            // For first-pass, we build a frustum from the camera matrices
            // ourselves through Sodium's own Frustum implementation by
            // copying plane data. Easier: use vanilla Frustum and let
            // Sodium derive its own from the matrices — but Sodium's
            // Viewport constructor takes its OWN Frustum type, no vanilla.
            //
            // The most robust path: create Sodium's frustum from the
            // view+proj matrices. We do that by finding the constructor
            // that takes those.
            Object sodiumFrustum = buildSodiumFrustum(viewMatrix, projMatrix);
            if (sodiumFrustum == null) {
                // No way to build a Sodium frustum — fall back to scheduling
                // a terrain update so Sodium runs its own setupTerrain
                // during renderLevel against its tracked camera.
                if (scheduleUpdateMethod != null) {
                    scheduleUpdateMethod.invoke(swr);
                }
                return;
            }
            Vector3d cameraPos = new Vector3d(
                virtualCamera.position().x,
                virtualCamera.position().y,
                virtualCamera.position().z);
            Object viewport = viewportCtor.newInstance(sodiumFrustum, cameraPos);

            // setupTerrain(camera, viewport, fogParams, spectator, hasCapturedFrustum, <matrices>)
            //
            // fogParams: pass FogParameters.NONE — we want chunks to render
            //   regardless of fog distance; fog itself is drawn separately.
            //   getEffectiveRenderDistance() will fall back to Sodium's
            //   configured render distance.
            // spectator/hasCapturedFrustum: false — these are config flags
            //   that don't apply to portal-view.
            // <matrices>: either Matrix4f (older Sodium, combined PV cull
            //   matrix) OR ChunkRenderMatrices(projection, view) record
            //   (newer Sodium). {@link #chunkRenderMatricesCtor} is non-null
            //   iff the newer API was detected at init time.
            Object sixthArg;
            if (chunkRenderMatricesCtor != null) {
                sixthArg = chunkRenderMatricesCtor.newInstance(projMatrix, viewMatrix);
            } else {
                // Older API: combined projection*view (cull-test matrix).
                sixthArg = new Matrix4f(projMatrix).mul(viewMatrix);
            }
            // Wrapped in managed-code context — setupTerrain transitively
            // creates GL command lists and trips Sodium's RenderDevice
            // assertions if invoked outside enter/exitManagedCode.
            enterManagedCode();
            try {
                setupTerrainMethod.invoke(swr, virtualCamera, viewport, fogParamsNone,
                    false, false, sixthArg);
            } finally {
                exitManagedCode();
            }
        } catch (Throwable e) {
            // Best-effort — failure here just means Sodium uses its
            // previously-captured camera for this frame's draws. Throttled
            // log: first failure prints full cause chain, subsequent
            // failures are silently swallowed so we don't spam at frame
            // rate. Manual reset via a counter every N frames would be
            // nice but we expect this to succeed once init works.
            if (!setupTerrainFailureLogged) {
                setupTerrainFailureLogged = true;
                Throwable rootCause = e;
                while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                    rootCause = rootCause.getCause();
                }
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS SODIUM] setupTerrainForCamera failed (subsequent failures silenced) — cause: {}: {}",
                    rootCause.getClass().getName(), rootCause.getMessage(), rootCause);
            }
        }
    }

    private static volatile boolean setupTerrainFailureLogged = false;

    /**
     * Manually inject a chunk-load event into Sodium's per-level
     * {@code ChunkTracker} for {@code level}. Called as a defense in
     * depth: Sodium's {@code ClientChunkCacheMixin} already injects on
     * {@code replaceWithPacketData} so our manual chunk feeds DO fire
     * Sodium's event path. This is here in case a future Sodium update
     * moves the injection point and breaks the mixin's coverage.
     *
     * <p>Status value {@code 1} matches the value Sodium's own mixin
     * passes for "chunk loaded" (the level represents chunk-status
     * tier — Sodium uses 1 for the basic loaded state).
     */
    public static void notifyChunkLoaded(ClientLevel level, int chunkX, int chunkZ) {
        if (!SodiumCompat.isSodiumLoaded()) return;
        if (level == null) return;
        initHandlesIfNeeded();
        if (getChunkTrackerMethod == null || onChunkStatusAddedMethod == null) return;
        try {
            Object tracker = getChunkTrackerMethod.invoke(null, level);
            if (tracker == null) return;
            onChunkStatusAddedMethod.invoke(tracker, chunkX, chunkZ, 1);
        } catch (Throwable e) {
            // Best-effort.
        }
    }

    /**
     * Schedule an entire chunk's worth of section rebuilds on the given
     * Sodium-enabled renderer. Use after {@code replaceWithPacketData}
     * for cached secondary levels — Sodium's own chunk-event path goes
     * through {@code processChunkEvents}, which only runs inside
     * {@code setupTerrain}. By the time setupTerrain fires for the
     * portal-view, the chunks would be re-registered via the tracker,
     * but their sections would be empty (no mesh) on the first frame.
     * Calling {@code onChunkAdded} here loads them into the RSM and
     * schedules them for rebuild so the next setupTerrain frame already
     * has meshes available.
     *
     * <p>The MC section coords range is
     * {@code [level.getMinSectionY(), level.getMinSectionY() + sectionCount)}.
     */
    /**
     * Update the matrices, renderer, and camera offset stored on a
     * {@code ChunkSectionsToRender} object (created earlier by
     * {@code LevelRenderer.extractLevel}). Sodium's
     * {@code ChunkSectionsToRenderMixin} installs three fields on the
     * vanilla class (renderer, matrices, x/y/z) via
     * {@code SodiumChunkSection.sodium$setRendering(...)}, and Sodium's
     * draw mixin reads those when invoking
     * {@code drawChunkLayer}.
     *
     * <p>For our portal-view render, {@code destRenderer.extractLevel}
     * runs FIRST with stale matrices (last main-render's values are still
     * on the LevelRenderer's mixin {@code matrices} field — they are
     * updated by {@code sodium$setMatrices} only when renderLevel itself
     * fires). The destChunks object created during extract therefore
     * captures main-render's matrices and main-render's camera offset.
     * When we subsequently call {@code destRenderer.renderLevel} with
     * portal-view matrices, Sodium updates the LevelRenderer's matrices
     * field — but destChunks STILL holds the stale ones, so
     * drawChunkLayer draws at the wrong position (visible chunks but
     * off-screen / wrong angle = only fog color visible in FBO).
     *
     * <p>Calling this method AFTER extractLevel but BEFORE renderLevel
     * (with portal-view matrices and portal-view camera offset) re-points
     * destChunks to the correct rendering state.
     *
     * @param chunkSectionsToRender vanilla {@code ChunkSectionsToRender}
     *     instance retrieved from {@code LevelRenderState.chunkSectionsToRender}
     * @param renderer the {@link LevelRenderer} whose swr should be used
     *     for the draw — i.e. {@code destRenderer}
     * @param projMatrix portal-view projection matrix
     * @param viewMatrix portal-view modelView matrix
     * @param cameraX/Y/Z portal-view camera world position (the cull-distance
     *     reference point Sodium uses for chunk-section offset)
     */
    private static volatile boolean updateChunkSectionsLoggedOnce = false;

    /**
     * Query Sodium's debug strings for the given renderer's swr. Used to
     * inspect chunk-build / render-list state at runtime — does the
     * secondary renderer actually have ready meshes for visible chunks?
     */
    public static String getDebugInfo(LevelRenderer renderer) {
        if (!SodiumCompat.isSodiumLoaded()) return "(sodium not loaded)";
        if (renderer == null) return "(null renderer)";
        initHandlesIfNeeded();
        if (getWorldRendererMethod == null) return "(bridge not inited)";
        try {
            Object swr = getWorldRendererMethod.invoke(renderer);
            if (swr == null) return "(swr null)";
            Method getChunksDebugStringMethod = swr.getClass().getMethod("getChunksDebugString");
            Method isTerrainCompleteMethod = swr.getClass().getMethod("isTerrainRenderComplete");
            Method getDebugStringsMethod = swr.getClass().getMethod("getDebugStrings", boolean.class);
            Object chunksDebug = getChunksDebugStringMethod.invoke(swr);
            Object terrainComplete = isTerrainCompleteMethod.invoke(swr);
            Object debugStrings = getDebugStringsMethod.invoke(swr, true);
            return "chunks=" + chunksDebug + " terrainComplete=" + terrainComplete
                + " details=" + debugStrings;
        } catch (Throwable e) {
            return "(debug failed: " + e.toString() + ")";
        }
    }

    public static void updateChunkSectionsRenderer(
            Object chunkSectionsToRender,
            LevelRenderer renderer,
            Matrix4f projMatrix, Matrix4f viewMatrix,
            double cameraX, double cameraY, double cameraZ) {
        if (!SodiumCompat.isSodiumLoaded()) return;
        if (chunkSectionsToRender == null || renderer == null) return;
        initHandlesIfNeeded();
        if (getWorldRendererMethod == null || chunkRenderMatricesCtor == null) return;
        try {
            Object swr = getWorldRendererMethod.invoke(renderer);
            if (swr == null) return;
            Object matrices = chunkRenderMatricesCtor.newInstance(projMatrix, viewMatrix);

            // Sodium's interface: SodiumChunkSection.sodium$setRendering(
            //     SodiumWorldRenderer, ChunkRenderMatrices, double, double, double)
            Class<?> sodiumChunkSectionClass = Class.forName(
                "net.caffeinemc.mods.sodium.client.util.SodiumChunkSection");
            Method setRenderingMethod = sodiumChunkSectionClass.getMethod(
                "sodium$setRendering",
                Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer"),
                chunkRenderMatricesClass,
                double.class, double.class, double.class);

            // Probe the BEFORE state to confirm whether extractLevel set
            // the SodiumChunkSection fields at all. If 'renderer' was null
            // before, that means method_72157 wasn't called during extract
            // → destChunks fell through to vanilla draw which can't
            // render Sodium-built chunks.
            if (!updateChunkSectionsLoggedOnce) {
                updateChunkSectionsLoggedOnce = true;
                try {
                    java.lang.reflect.Field rendererField = sodiumChunkSectionClass.getMethod(
                        "sodium$setRendering",
                        Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer"),
                        chunkRenderMatricesClass,
                        double.class, double.class, double.class).getDeclaringClass()
                        .getDeclaredField("renderer");
                    // The field is on the mixin class — likely package-private
                    rendererField.setAccessible(true);
                    Object beforeRenderer = rendererField.get(chunkSectionsToRender);
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS SODIUM] updateChunkSectionsRenderer ENTRY: destChunks.renderer before our call = {}",
                        beforeRenderer == null ? "NULL" : beforeRenderer.getClass().getSimpleName());
                } catch (Throwable probeFail) {
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS SODIUM] (probe of destChunks.renderer failed — best-effort skip: {})",
                        probeFail.toString());
                }
            }

            setRenderingMethod.invoke(chunkSectionsToRender, swr, matrices,
                cameraX, cameraY, cameraZ);
        } catch (Throwable e) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS SODIUM] updateChunkSectionsRenderer failed: {}", e.toString());
        }
    }

    public static void notifyChunkAddedToRenderer(LevelRenderer renderer, int chunkX, int chunkZ) {
        if (!SodiumCompat.isSodiumLoaded()) return;
        if (renderer == null) return;
        initHandlesIfNeeded();
        if (getWorldRendererMethod == null || onChunkAddedMethod == null
            || renderSectionManagerField == null) return;
        try {
            Object swr = getWorldRendererMethod.invoke(renderer);
            if (swr == null) return;
            Object rsm = renderSectionManagerField.get(swr);
            if (rsm == null) return;
            // Wrapped in managed-code context — onChunkAdded creates
            // RenderSection instances which may allocate GL resources
            // through Sodium's RenderDevice. Without the wrap we'd hit
            // the same "Tried to access device from unmanaged context"
            // failure as setupTerrain.
            enterManagedCode();
            try {
                onChunkAddedMethod.invoke(rsm, chunkX, chunkZ);
            } finally {
                exitManagedCode();
            }
        } catch (Throwable e) {
            // Best-effort.
        }
    }

    /**
     * Schedule a chunk-section rebuild on the given renderer's Sodium
     * pipeline. Use after feeding a remote chunk into a cached secondary
     * {@link ClientLevel} — vanilla's chunk-loaded notification routes
     * to {@code mc.levelRenderer}'s Sodium state, NOT the secondary's,
     * so secondary-renderer Sodium has no idea the chunk exists until
     * we tell it. Without this call, chunks fed via
     * {@link com.warwa.seamlessportals.client.PortalWorldManager#drainPendingFeeds}
     * never get meshed by Sodium, leaving portal views with fog color
     * but no terrain until the player teleports and the renderer becomes
     * the active one (at which point vanilla's normal load-event path
     * starts feeding Sodium correctly).
     *
     * <p>Sections coordinates are MC section coords:
     * {@code sectionX = blockX >> 4}, {@code sectionY = section-index +
     * level.getMinSectionY()}, {@code sectionZ = blockZ >> 4}.
     */
    public static void scheduleRebuildForChunk(LevelRenderer renderer,
                                                int sectionX, int sectionY, int sectionZ,
                                                boolean important) {
        if (!SodiumCompat.isSodiumLoaded()) return;
        if (renderer == null) return;
        initHandlesIfNeeded();
        if (scheduleRebuildForChunkMethod == null || getWorldRendererMethod == null) return;
        try {
            Object swr = getWorldRendererMethod.invoke(renderer);
            if (swr == null) return;
            // FIRST: register the section with Sodium's RenderSectionManager
            // directly. Sodium's normal chunk-event pipeline (ChunkTracker →
            // SodiumWorldRenderer.processChunkEvents) only fires from
            // setupTerrain, and even then only for the renderer that is
            // "active" in some Sodium-internal sense. For our cached
            // secondary renderers, the section may never get added to the
            // RenderSectionManager's sectionByPosition map, and
            // scheduleRebuild silently no-ops for unknown sections.
            //
            // Calling onSectionAdded directly ensures the section is
            // registered even before any setupTerrain runs.
            //
            // Both calls wrapped in managed-code context — Sodium's
            // RenderDevice asserts on unmanaged access.
            enterManagedCode();
            try {
                if (renderSectionManagerField != null && onSectionAddedMethod != null) {
                    Object rsm = renderSectionManagerField.get(swr);
                    if (rsm != null) {
                        onSectionAddedMethod.invoke(rsm, sectionX, sectionY, sectionZ);
                    }
                }
                scheduleRebuildForChunkMethod.invoke(swr, sectionX, sectionY, sectionZ, important);
            } finally {
                exitManagedCode();
            }
        } catch (Throwable e) {
            // Best-effort; a single failed chunk just means it won't mesh
            // until something else triggers a rebuild. Don't spam logs.
        }
    }

    /**
     * Debug helper — return the number of visible chunks Sodium has
     * built for the given renderer. Used to verify the secondary
     * renderer's Sodium state is actually receiving and meshing chunks.
     */
    public static int getVisibleChunkCount(LevelRenderer renderer) {
        if (!SodiumCompat.isSodiumLoaded()) return -1;
        if (renderer == null) return -1;
        initHandlesIfNeeded();
        if (getVisibleChunkCountMethod == null || getWorldRendererMethod == null) return -1;
        try {
            Object swr = getWorldRendererMethod.invoke(renderer);
            if (swr == null) return -1;
            Object result = getVisibleChunkCountMethod.invoke(swr);
            return result instanceof Integer ? (Integer) result : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * Construct a Sodium frustum from the view + projection matrices.
     * Sodium 0.8.x's {@code SimpleFrustum} (implements the marker
     * interface {@code Frustum}) is constructed from a JOML
     * {@link org.joml.FrustumIntersection} — NOT a raw Matrix4f. We
     * compute the combined PV matrix, wrap it in a
     * {@code FrustumIntersection}, and pass that.
     *
     * <p>If construction fails (e.g. Sodium's class layout changes again),
     * return {@code null} and the caller falls back to
     * {@code scheduleTerrainUpdate} — Sodium will then derive its frustum
     * internally from whatever camera state it last captured. The portal
     * view will reuse the main render's frustum (slightly stale chunk
     * selection but not a crash).
     */
    private static Object buildSodiumFrustum(Matrix4f view, Matrix4f proj) {
        try {
            Matrix4f pv = new Matrix4f(proj).mul(view);
            org.joml.FrustumIntersection fi = new org.joml.FrustumIntersection(pv);
            Class<?> simpleFrustumClass = Class.forName(
                "net.caffeinemc.mods.sodium.client.render.viewport.frustum.SimpleFrustum");
            Constructor<?> ctor = simpleFrustumClass.getConstructor(
                org.joml.FrustumIntersection.class);
            return ctor.newInstance(fi);
        } catch (Throwable e) {
            return null;
        }
    }
}
