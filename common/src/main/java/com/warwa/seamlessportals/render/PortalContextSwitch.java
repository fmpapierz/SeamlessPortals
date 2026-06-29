package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.*;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.RemoteChunkManager;
import com.warwa.seamlessportals.client.PortalWorldManager;
import com.warwa.seamlessportals.mixin.client.CameraInvokerMixin;
import com.warwa.seamlessportals.mixin.client.GameRendererAccessorMixin;
import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalTransform;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Renders the destination world to a secondary FBO, then composites through stencil.
 *
 * Following IP's RendererUsingFrameBuffer.doRenderPortal() pattern:
 * 1. Prepare secondary FBO (matching main FBO size)
 * 2. Swap mc.mainRenderTarget to secondary FBO
 * 3. Clear secondary FBO (color + depth, stencil disabled)
 * 4. Call renderLevel() with proper CameraRenderState → full framegraph on secondary FBO
 * 5. Restore mc.mainRenderTarget
 * 6. Composite secondary FBO onto main through stencil mask
 *
 * The secondary renderer has its own LevelRenderState (Commit 1), so
 * extractLevel() doesn't corrupt the main renderer's state.
 */
public class PortalContextSwitch {

    /**
     * Recursion guard. Prevents infinite recursion when renderLevel() on the
     * secondary renderer triggers Fabric's AFTER_TRANSLUCENT_TERRAIN event,
     * which would call StencilPortalRenderer.renderPortals() again.
     * Matches IP's PortalRendering.isRendering() check.
     */
    public static boolean isRenderingPortal = false;

    /**
     * True while {@link #withSwitchedWorld} has swapped {@code mc.particleEngine}
     * to the destination dimension's OWN {@link net.minecraft.client.particle.ParticleEngine}
     * (per-dest engine, holding only that dim's particles).
     *
     * <p>When true, {@link com.warwa.seamlessportals.mixin.client.ParticleEnginePortalSkipMixin}
     * LETS the dest particle extract run — a separate engine has its own
     * particle-group map, so the extract cannot corrupt the source world's
     * shared render state (the bug that originally forced the blanket skip).
     * When false during a portal render (no dest engine yet / creation failed),
     * the extract is still skipped to protect the main world's particles.
     *
     * <p>Single-threaded with the render: set/cleared only inside
     * {@link #withSwitchedWorld} on the render thread.
     */
    public static boolean destParticlesActive = false;

    /**
     * Phase 2 (IP "live window"): keep the destination renderer's
     * {@code SectionOcclusionGraph} WARM every frame so a crossing finds it
     * already primed. When true, the dest {@code CameraRenderState} is reported
     * as NOT frustum-captured, so {@code LevelRenderer.render()}'s
     * {@code sectionOcclusionGraph.update(...)} (the async graph build, gated on
     * {@code !isFrustumCaptured}) actually runs for the virtual (≈ post-teleport)
     * camera. The DISPLAY path is unchanged: the captured frustum still makes the
     * dest {@code extract()} skip {@code applyFrustum}, and the manual section
     * scan still populates {@code visibleSections}. The build leaves
     * {@code needsFrustumUpdate} set (unconsumed, since the dest extract skips
     * applyFrustum), so on promotion the main extract consumes it and fills
     * {@code visibleSections} from the warm graph — enabling Phase 3 to drop the
     * post-promote SOG re-prime → instant crossings. {@code isFrustumCaptured}
     * has a SINGLE renderer consumer ({@code SectionOcclusionGraph.update}),
     * verified, so this is side-effect-free on the display.
     *
     * <p><b>Now gated (2026-06-27):</b> enabling {@code sog.update} HANGS the
     * render thread when the dest level is SPARSE / still streaming. So the
     * native (occlusion-culled) path — including this flag's effect — is applied
     * ONLY when {@link com.warwa.seamlessportals.client.PortalWorldManager#isDestResident}
     * reports the dest dense around the view center; a sparse dest falls back to
     * the captured-frustum + manual-scan path (no hang). Set this master flag
     * false to force the manual path for ALL dests (full rollback).
     *
     * <p><b>OFF again (2026-06-27):</b> even gated on density, the native path
     * tipped over during the initial chunk STREAM-IN — the dest is still
     * receiving chunks while being viewed, so {@code sog.update} churns its
     * propagation queue on the render thread. The native path needs the dest fed
     * the VANILLA way (Phase 4c: redirected chunk packets that drive the engine's
     * chunk-load tracking correctly) before it is stable enough to drive. Stays
     * off until 4c lands; the gating/scan-skip plumbing remains for re-enabling.
     *
     * <p><b>ON again (2026-06-28):</b> the freeze was the dest still STREAMING
     * while the native path ran, not native-render itself. Two guards now bound
     * that: the chunk send is throttled (6/tick, no burst) AND the native path is
     * additionally gated on {@link PortalWorldManager#isDestStable} — it only runs
     * on a dest that has gone quiet (the dim you just left), never on one whose
     * chunks are landing this second. Re-enabled to test that combination.
     *
     * <p><b>OFF again (2026-06-28, post-4c):</b> Phase 4c (redirected vanilla
     * chunk packets) landed and the FEED is confirmed working — but the native
     * path still froze, because {@code sog.update} (the vanilla/Sodium occlusion
     * graph) genuinely churns on a no-Sodium remote level regardless of how it was
     * fed. IP's NO-SODIUM remote render does NOT use {@code sog.update}; it uses a
     * render-distance-BOUNDED BFS ({@code VisibleSectionDiscovery}). So the native
     * (sog.update) path is wrong for this (vanilla) target. Back on the
     * captured-frustum path (sog.update SUPPRESSED) with the redirect feed for a
     * stable build; the manual scan is being replaced by a bounded BFS next.
     *
     * <p><b>ON again (2026-06-28, warm-graph):</b> re-enabled to WARM the dest
     * SectionOcclusionGraph for the post-teleport view (kills the residual blank
     * flash). Now gated on a robust COUNT-BASED {@link PortalWorldManager#isDestStable}
     * (loaded-chunk count flat for a window) + density — so sog.update only runs on
     * a SETTLED dest and can't churn on a streaming one (the earlier hangs). Set
     * false here for an instant rollback to the pure manual-scan path.
     *
     * <p><b>OFF — CONFIRMED DEAD END (2026-06-28):</b> the native path froze the
     * instant it activated on a FULLY-SETTLED 289-chunk nether (count flat, not
     * streaming) — log {@code [SEAMLESS WARM] ... -> true} was the last line. So
     * {@code sog.update} hangs in the mod's hand-built secondary renderer
     * REGARDLESS of stability; it is not a streaming problem but a fundamental
     * incompatibility (the secondary renderer is set up in a way sog.update can't
     * drive). The native/warm-graph path stays OFF; the teleport keeps the
     * captured-frustum manual scan + the warm-currentGraph promote repaint.
     *
     * <p><b>ON again (2026-06-28, root-caused):</b> the hang was {@code sog.update}
     * → {@code runPartialUpdate}'s UNBOUNDED synchronous occlusion flood over the
     * secondary's whole bulk-loaded 289-chunk section set. {@link
     * com.warwa.seamlessportals.mixin.client.SectionOcclusionGraphPartialUpdateSkipMixin}
     * now SKIPS that synchronous flood during a portal-view render and lets the
     * async {@code scheduleFullUpdate} build the graph off-thread instead. With the
     * render-thread flood removed, the native path can warm the dest graph safely
     * (still gated on a settled/dense dest). Set false for instant rollback.
     *
     * <p><b>OFF — INTERMITTENT FREEZE (2026-06-28):</b> with the flood-skip + a
     * 500-chunk cap, the native warm path STILL froze non-deterministically even on
     * the small (289-chunk) nether that had warmed fine moments earlier. The
     * deterministic sync flood is gone, but the remaining fragility (most likely the
     * heavy async full-build — a per-node ray-march BFS — saturating the shared
     * Util.backgroundExecutor ForkJoinPool that also serves chunk meshing) makes it
     * unreliable. Intermittent freezes are unshippable, so the native warm path is
     * OFF. Reliable best = captured-frustum manual scan + warm-currentGraph promote
     * repaint: nether tiny-hiccup, overworld brief flash, NO freeze. Truly-instant
     * needs Phase 5 (a real engine renderer for the secondary so sog.update is
     * native + reliable) — a major rewrite, not an incremental gate.
     */
    public static boolean useContinuousExtract = false;

    /** Per-dim last-logged native(sog.update)-render on/off state (warm-path diagnostic). */
    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Boolean>
        lastNativeState = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Phase 5 flash-bridge window (monotonic ns). While active, the MAIN render's
     * {@code LevelExtractor.applyFrustum} populates visibleSections via the bounded
     * {@link VisibleSectionDiscovery} flood-fill instead of the freshly-promoted,
     * still-COLD {@link net.minecraft.client.renderer.SectionOcclusionGraph}. So the
     * dimension you just entered paints terrain IMMEDIATELY (no blank/sky flash)
     * while the engine rebuilds its occlusion graph in the background; once the
     * window expires the warm graph takes back over (with cave-culling). Bounded
     * flood-fill = no freeze (unlike sog.update on a bulk-loaded graph).
     */
    private static volatile long promoteBridgeMinUntilNanos = 0L;
    private static volatile long promoteBridgeMaxUntilNanos = 0L;

    /** Arm the post-promote flash-bridge (called from promoteToMain). */
    public static void armPromoteBridge() {
        long now = System.nanoTime();
        promoteBridgeMinUntilNanos = now + 1_000_000_000L; // always-bridge floor (~1s)
        promoteBridgeMaxUntilNanos = now + 8_000_000_000L; // bridge-until-rebuilt cap (~8s)
    }

    /** Within the floor window — always bridge (covers the first frames + small dests). */
    public static boolean isPromoteBridgeMinActive() {
        return System.nanoTime() < promoteBridgeMinUntilNanos;
    }

    /**
     * Within the max (safety-capped) window. Past the floor, the bridge mixin
     * additionally checks the renderer's SOG full-update task: it keeps bridging
     * until that rebuild is DONE (so a big demoted overworld never falls back to a
     * half-built graph → no "blank a second later"). Also used by the
     * runPartialUpdate flood-skip to cover the post-promote main render.
     */
    public static boolean isPromoteBridgeActive() {
        return System.nanoTime() < promoteBridgeMaxUntilNanos;
    }

    /**
     * Frame-scoped frustum-cull result. Built ONCE by the doFboRender section
     * sweep (the {@code for (viewArea.sections)} loop) and reused by
     * {@link #populateVisibleSectionsByFrustum}, so the portal view runs the
     * O(all viewArea.sections) frustum scan ONCE per FBO frame instead of twice
     * (the sweep, then an identical re-scan after extract() clears
     * visibleSections). Same predicate + same fixed {@code destFrustum} ⇒
     * identical result. Render-thread only.
     */
    private static final it.unimi.dsi.fastutil.objects.ObjectArrayList<SectionRenderDispatcher.RenderSection>
        prebuiltVisibleSections = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();

    // Lightmap is now managed by DimensionRenderHelper (per dimension, real values).

    /**
     * Override for GameRenderer.lightmap() during portal rendering.
     * Read by GameRendererLightmapMixin. Removed in Commit 4 (mc.level swap).
     */
    public static com.mojang.blaze3d.textures.GpuTextureView portalLightmapOverride = null;

    /** Secondary FBO for portal world rendering. Matches IP's SecondaryFrameBuffer. */
    private static TextureTarget secondaryFbo = null;

    /**
     * Two-phase render hand-off flag.
     *
     * <p>Phase 1 ({@link #prepareDestinationWorld}, run from
     * {@code GameRenderer.renderLevel} HEAD — BEFORE the main frame's framegraph)
     * renders the destination world into {@link #secondaryFbo} and sets this
     * {@code true} when the FBO holds drawable destination geometry.
     *
     * <p>Phase 2 ({@link #compositeDestinationWorld}, run from Fabric's
     * {@code AFTER_TRANSLUCENT_TERRAIN} — INSIDE the main framegraph, after the
     * stencil mask is written) reads it: {@code true} → composite the secondary
     * FBO through the stencil; {@code false} → fall back to the colored-block /
     * background draw (which paints directly to the main FBO through the stencil
     * and therefore must stay in phase 2).
     *
     * <p>Reset to {@code false} at the START of every phase 1 (i.e. once per main
     * frame, via {@link #beginPortalFrame}) so a stale FBO from a previous frame is
     * never composited.
     */
    private static boolean fboReadyThisFrame = false;

    /**
     * Per-frame reset of the two-phase hand-off state. Call once at the very start
     * of phase 1 (from {@code GameRenderer.renderLevel} HEAD), unconditionally —
     * even when there are no portals this frame — so a {@code true} flag left from a
     * previous frame can never cause a stale composite.
     */
    public static void beginPortalFrame() {
        fboReadyThisFrame = false;
    }

    /**
     * Clear the per-dim uncompiled-section schedule guard. Called on promote /
     * demote: when a dimension changes render role, any sections it had scheduled
     * while a previous dest get their compiles cancelled (createCompileTask's
     * cancelTasks on the role swap), leaving STALE "already scheduled" entries that
     * block re-scheduling forever. Next time it's a dest, its terrain never
     * recompiles ({@code comp=0} with {@code visSec>0} in the log) and the portal
     * view falls back to the SOURCE sky — the "blank curtain = overworld sky" bug.
     */
    public static void clearCompileSchedule(ResourceKey<Level> dim) {
        portalCompileScheduled.remove(dim);
    }

    private static int phase2FailCount = 0;
    private static int phase2SuccessCount = 0;
    /** Tracks chunk count at last feed per dimension. Feed only when new chunks arrive. */
    private static final java.util.Map<ResourceKey<Level>, Integer> lastFedChunkCount = new java.util.HashMap<>();
    /** Per-dim one-shot guard for scheduling UNCOMPILED portal-view sections.
     *  compileAsync cancels any in-flight task, so an uncompiled section must be
     *  scheduled exactly once (not every frame) or its compile never finishes.
     *  An entry is removed once the section's mesh becomes compiled. */
    private static final java.util.Map<ResourceKey<Level>, java.util.Set<Long>> portalCompileScheduled =
        new java.util.HashMap<>();

    /**
     * How far (in chunks) from the destination camera we will consider
     * scheduling async compile for dirty {@code RenderSection}s in the
     * portal-view FBO render path.
     *
     * Previous history: before Phase A (commit TBD), this bounded a
     * {@code rebuildSectionSync} loop to avoid a 4-second render-thread stall
     * on return teleports when a demoted primary's ViewArea (up to
     * 23×23×16 = 8464 slots for render dist 11 × Nether height) was
     * substantially dirty. Sync rebuild has since been replaced with
     * {@link SectionRenderDispatcher.RenderSection#rebuildSectionAsync} which
     * returns immediately and runs on {@link net.minecraft.Util#backgroundExecutor}.
     *
     * The radius gate is kept because async scheduling still has a cost:
     * {@code createCompileTask} allocates a {@code RenderSectionRegion} and
     * cancels prior tasks. Flooding the queue with sections beyond the
     * portal-view frustum wastes worker CPU on meshes we'll never show.
     *
     * See memory: {@code step1_5_viewarea_sync_radius_fix.md},
     * {@code viewarea_reposition_mesh_loss.md}.
     */
    /**
     * Phase 2/5 native-render gate radius: every chunk within this many chunks of
     * the dest view center must be loaded for the dest to count as "dense" and use
     * the native occlusion-culled render (else the SOG BFS could hang on a sparse
     * graph). 4 → an 9×9 loaded patch around the camera.
     */
    private static final int NATIVE_RESIDENCY_RADIUS = 4;

    /**
     * Phase 2/5 native-render stability window: the dest must have gone this long
     * (ns) without landing a fed chunk before the native occlusion render is
     * allowed. While chunks are still streaming, sog.update's propagation churns
     * the render thread (the freeze). 1s is comfortably past the per-tick feed
     * cadence so a quiet (fully-loaded) dest qualifies immediately and a streaming
     * one never does.
     */
    private static final long NATIVE_STABLE_NANOS = 1_000_000_000L;

    /**
     * Max loaded-chunk count for which the native warm-graph path is allowed. The
     * first native frame does O(loadedChunks) occlusion-graph setup; a residency-
     * bounded fresh dest (~289) is fine, but a demoted full-RD dim (e.g. 1662 at
     * high render distance) freezes the render thread on activation. Above this,
     * stay on the manual scan (no warm, brief promote flash, but no freeze).
     */
    private static final int NATIVE_MAX_LOADED_CHUNKS = 500;

    /**
     * IP-faithful: the portal view draws + meshes exactly as deep as the dest is
     * kept loaded — the configured loading cap (IP's indirectLoadingRadiusCap,
     * default 8, clamp 1..32). So you draw/mesh exactly what's resident; raising
     * the config widens both the resident region AND what the portal view shows
     * and pre-meshes. Squared, for the scan's distance gates. Read live so a config
     * change takes effect without restart.
     */
    private static int destDepthRadiusSq() {
        int d = com.warwa.seamlessportals.config.SeamlessPortalsConfig.get().getPortalRenderDistance();
        return d * d;
    }

    /**
     * Atomically swap primary client state to the destination for the duration
     * of {@code renderCallback}, then restore in {@code finally}.
     *
     * <p>Subphase 2 Commit B — swap set expanded to mirror IP's
     * {@code MyGameRenderer.switchAndRenderTheWorld} as much as 26.1.2 allows:
     *
     * <ul>
     *   <li>{@code mc.level}, {@code mc.levelRenderer}, {@code mc.mainRenderTarget}
     *       — the original Subphase 1 trio.</li>
     *   <li>{@code mc.gameRenderer.mainCamera} — needed once Commit C calls
     *       {@code mc.gameRenderer.renderLevel(...)} which extracts camera state
     *       from {@code mainCamera}, not from a passed-in argument.</li>
     *   <li>{@code mc.gameRenderer.lightmap} — per-dim {@link Lightmap} via
     *       {@link DimensionRenderHelper}. Once wired (Commit C), this lets
     *       vanilla's {@code GameRenderer.lightmap()} return the destination
     *       dimension's lightmap directly, replacing the
     *       {@link #portalLightmapOverride} HEAD-inject path.</li>
     *   <li>{@code mc.hitResult = null} — prevents crosshair targeting in the
     *       portal-view render. IP does the same (clears hitResult during
     *       fake-camera render).</li>
     *   <li>{@code mc.player.noPhysics = true} — defensive; matches IP. Stops
     *       any incidental physics tick triggered from the render path.</li>
     *   <li>{@code mc.particleEngine.level} — particles consult
     *       {@code particleEngine.level} for tick + extract; mismatching it
     *       against {@code mc.level} would mis-bind particle render state.
     *       Done via {@link com.warwa.seamlessportals.mixin.client.ParticleEngineAccessorMixin}
     *       (raw field write, NOT {@code setLevel(...)} — the public setter
     *       calls {@code clearParticles()} as a side effect, which would wipe
     *       the source level's particles every portal-render frame).</li>
     *   <li>{@code mc.renderBuffers} + {@code destRenderer.renderBuffers} —
     *       both swapped to a pooled {@link PortalRenderBuffersPool} buffer
     *       for the duration. The dormant vanilla {@code mc.levelRenderer}
     *       (a map entry per Subphase 1) shares its {@code renderBuffers}
     *       with {@code mc.renderBuffers}; without this swap, using it as a
     *       portal-view secondary would conflict with the in-flight main
     *       render's buffer. PortalWorldManager-built secondaries already
     *       have isolated buffers, but routing them through the same swap
     *       is harmless and keeps the path uniform.</li>
     * </ul>
     *
     * <p>Dropped from IP's swap set (no equivalent field in 26.1.2):
     * <ul>
     *   <li>{@code mc.blockEntityRenderDispatcher.level} — BERD has no
     *       {@code level} field in 26.1.2; level lookup happens at
     *       render-state extraction time, not at submit time.</li>
     *   <li>{@code GameRenderer.doRenderHand} flag — no such field.</li>
     *   <li>{@code FogRendererContext.swappingManager} push/pop — no such
     *       infrastructure. {@link #doFboRender} sidesteps the issue by
     *       writing a separate {@code GpuBufferSlice} for portal fog.</li>
     * </ul>
     *
     * <p>Wired into {@link #doFboRender} as of Commit C1 — the inline
     * save/swap block was replaced with a {@code withSwitchedWorld(...)}
     * call wrapping a lambda that runs the GL state setup +
     * {@code destRenderer.renderLevel(...)}.
     */
    public static void withSwitchedWorld(
            ClientLevel destLevel,
            LevelRenderer destRenderer,
            com.mojang.blaze3d.pipeline.RenderTarget destMainRT,
            Camera destCamera,
            Lightmap destLightmap,
            Runnable renderCallback) {
        Minecraft mc = Minecraft.getInstance();
        GameRendererAccessorMixin gameRendererAccess =
            (GameRendererAccessorMixin) mc.gameRenderer;
        com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin mcAccess =
            (com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin) mc;
        com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin destRendererAccess =
            (com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin) destRenderer;

        // Per-destination ParticleEngine. We swap the WHOLE engine (not just its
        // level) so the dest extract pulls THIS dim's particles and the shared
        // particle-group corruption that forced the old blanket extract-skip can
        // never happen (separate engine = separate groups). Lazily created here
        // if the cached-particle tick hasn't yet. Null only if creation failed
        // (e.g. no global engine) — then we leave the main engine in place and
        // {@code destParticlesActive} stays false so the skip mixin still
        // protects the source particles.
        net.minecraft.client.particle.ParticleEngine savedParticleEngine = mc.particleEngine;
        net.minecraft.client.particle.ParticleEngine destParticleEngine =
            com.warwa.seamlessportals.client.PortalWorldManager.getOrCreateParticleEngine(destLevel);

        com.mojang.blaze3d.pipeline.RenderTarget savedMainRT = mc.gameRenderer.mainRenderTarget();
        ClientLevel savedLevel = mc.level;
        LevelRenderer savedRenderer = mc.levelRenderer;
        Camera savedMainCamera = gameRendererAccess.seamlessportals$getMainCamera();
        Lightmap savedLightmap = gameRendererAccess.seamlessportals$getLightmap();
        net.minecraft.world.phys.HitResult savedHitResult = mc.hitResult;
        net.minecraft.client.player.LocalPlayer player = mc.player;
        boolean savedNoPhysics = player != null && player.noPhysics;

        // Buffer pool acquire — null if exhausted (no swap, fall back to
        // existing per-renderer buffers; no crash).
        net.minecraft.client.renderer.RenderBuffers pooledBuffers = PortalRenderBuffersPool.acquire();
        net.minecraft.client.renderer.RenderBuffers savedMcBuffers =
            gameRendererAccess.seamlessportals$getRenderBuffers();
        net.minecraft.client.renderer.RenderBuffers savedDestRendererBuffers =
            destRendererAccess.seamlessportals$getRenderBuffers();

        // 26.2 (submit model): the old MultiBufferSource/OutlineBufferSource
        // dispatcher buffer-source swap is GONE (D6). FeatureRenderDispatcher no
        // longer holds bufferSource/outlineBufferSource/crumblingBufferSource
        // fields — it takes its StagedVertexBuffer from its own RenderBuffers and
        // collects submissions via SubmitNodeStorage. The Phase 2b "stash + swap +
        // restore the dispatcher's buffer-source refs" block was therefore removed.
        //
        // SEAMLESS-26.2-TODO: the dispatcher's per-frame isolation is now via its
        // own StagedVertexBuffer (from its isolated RenderBuffers), not swappable
        // buffer-source refs. The old swap existed so entity equipment items
        // (bow/sword/armor, submitted through the item feature renderer) would
        // flush to the pooled buffer and actually draw in the portal view; that
        // mechanism no longer exists. Verify entity/item rendering in the portal
        // view at runtime.

        try {
            gameRendererAccess.seamlessportals$setMainRenderTarget(destMainRT);
            mc.level = destLevel;
            mcAccess.seamlessportals$setLevelRenderer(destRenderer);
            gameRendererAccess.seamlessportals$setMainCamera(destCamera);
            gameRendererAccess.seamlessportals$setLightmap(destLightmap);
            mc.hitResult = null;
            if (player != null) player.noPhysics = true;
            if (destParticleEngine != null) {
                mcAccess.seamlessportals$setParticleEngine(destParticleEngine);
                destParticlesActive = true;
            }
            if (pooledBuffers != null) {
                gameRendererAccess.seamlessportals$setRenderBuffers(pooledBuffers);
                destRendererAccess.seamlessportals$setRenderBuffers(pooledBuffers);
            }

            renderCallback.run();
        } finally {
            if (pooledBuffers != null) {
                destRendererAccess.seamlessportals$setRenderBuffers(savedDestRendererBuffers);
                gameRendererAccess.seamlessportals$setRenderBuffers(savedMcBuffers);
            }
            destParticlesActive = false;
            if (destParticleEngine != null) {
                mcAccess.seamlessportals$setParticleEngine(savedParticleEngine);
            }
            if (player != null) player.noPhysics = savedNoPhysics;
            mc.hitResult = savedHitResult;
            gameRendererAccess.seamlessportals$setLightmap(savedLightmap);
            gameRendererAccess.seamlessportals$setMainCamera(savedMainCamera);
            mcAccess.seamlessportals$setLevelRenderer(savedRenderer);
            mc.level = savedLevel;
            gameRendererAccess.seamlessportals$setMainRenderTarget(savedMainRT);
            PortalRenderBuffersPool.release(pooledBuffers);
        }
    }

    public static void resetChunkFedState(ResourceKey<Level> dimension) {
        lastFedChunkCount.remove(dimension);
        phase2FailCount = 0;
        phase2SuccessCount = 0;
    }

    /**
     * PHASE 1 — render the destination world into the secondary FBO.
     *
     * <p>Runs from {@code GameRenderer.renderLevel} HEAD (via
     * {@link com.warwa.seamlessportals.mixin.client.GameRendererPortalPrepareMixin}),
     * BEFORE the main frame's {@code levelRenderer.render(...)} framegraph is built
     * or executed. This is the move that fixes the "overworld blanks" bug: the heavy
     * nested {@code destRenderer.render(...)} (itself a full deferred framegraph) is
     * no longer issued from {@code AFTER_TRANSLUCENT_TERRAIN} (which fires mid-main-
     * framegraph and disrupted the imported "main" target), so it cannot blank the
     * overworld.
     *
     * <p>Only the heavy FBO render happens here. The composite onto the screen, and
     * the colored-block / background fallbacks (which draw directly to the main FBO
     * through the stencil mask), are deferred to {@link #compositeDestinationWorld}
     * in phase 2 — they need the stencil mask, which is written in
     * {@code AFTER_TRANSLUCENT_TERRAIN}.
     *
     * <p>Sets {@link #fboReadyThisFrame} from the render result. The per-frame
     * reset to {@code false} happens earlier in {@link #beginPortalFrame} (called
     * unconditionally at phase-1 entry), so a stale FBO from a previous frame is
     * never composited even on frames where this method isn't reached.
     */
    public static void prepareDestinationWorld(PortalInfo srcPortal, PortalLink link, Camera camera) {
        PortalInfo destPortal = link.getDestination();
        ResourceKey<Level> destDim = destPortal.getDimension();

        // tryFboRender renders the dest world into secondaryFbo (no composite).
        // On success the FBO holds drawable geometry → mark it ready so phase 2
        // composites it through the stencil. On failure (chunks not ready / no
        // geometry yet) leave it false → phase 2 takes the fallback path.
        try {
            fboReadyThisFrame = tryFboRender(srcPortal, link, camera, destDim);
        } finally {
            // CRITICAL (phase-split fix): leave the GL stencil in a benign state
            // before returning to vanilla GameRenderer.renderLevel, which is about
            // to build + execute the MAIN frame's framegraph against a freshly
            // CLEARED stencil buffer (all zeros).
            //
            // doFboRender's inner cleanup re-enables GL_STENCIL_TEST with
            // glStencilFunc(EQUAL, 1) / glStencilMask(0x00) — correct in the OLD
            // design, where the heavy render ran INSIDE phase 2 right after the
            // mask was written (value 1) and immediately before the EQUAL(1)
            // composite. In the NEW design phase 1 runs at renderLevel HEAD, so
            // that EQUAL(1) would otherwise persist into the main world render and
            // reject every fragment (stencil==0 everywhere) → the overworld would
            // blank again, this time via the stencil. Reset it here so the main
            // framegraph renders unmasked. Phase 2 re-enables + writes the mask
            // itself from AFTER_TRANSLUCENT_TERRAIN.
            org.lwjgl.opengl.GL11.glStencilMask(0xFF);
            org.lwjgl.opengl.GL11.glStencilFunc(org.lwjgl.opengl.GL11.GL_ALWAYS, 0, 0xFF);
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
        }
    }

    /**
     * PHASE 2 — put the destination view on screen through the stencil mask.
     *
     * <p>Runs from Fabric's {@code AFTER_TRANSLUCENT_TERRAIN} (via
     * {@link StencilPortalRenderer}), INSIDE the main frame's framegraph, AFTER the
     * portal stencil mask has been written. This is intentionally lightweight: a
     * single textured-quad composite (or a small fallback mesh) — none of it nests a
     * framegraph, so it is safe to issue mid-main-render.
     *
     * <ul>
     *   <li>FBO ready (phase 1 drew the dest world) → composite the secondary FBO
     *       through the stencil (EQUAL 1) onto the screen.</li>
     *   <li>FBO not ready → fall back to colored blocks if remote chunk data exists,
     *       else a solid background, so the portal is always visible even before
     *       destination chunks arrive (e.g. immediately after a dimension change).
     *       Without it, SectionCompilerMixin hides the purple swirl but nothing
     *       replaces it.</li>
     * </ul>
     */
    public static void compositeDestinationWorld(PortalInfo srcPortal, PortalLink link, Camera camera) {
        if (fboReadyThisFrame) {
            compositePortalFbo();
            return;
        }

        PortalInfo destPortal = link.getDestination();
        ResourceKey<Level> destDim = destPortal.getDimension();

        if (RemoteChunkManager.hasDimensionData(destDim)) {
            renderColoredBlocks(srcPortal, link, camera, destDim);
        } else {
            // No chunk data yet — draw solid background so portal is visible.
            // This happens after dimension change before the server sends chunks.
            PortalShapeRenderer.drawPortalBackground(
                java.util.List.of(srcPortal), camera, destDim);
        }
    }

    private static boolean tryFboRender(PortalInfo srcPortal, PortalLink link,
                                         Camera mainCamera, ResourceKey<Level> destDim) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;

        LevelRenderer destRenderer = PortalWorldManager.getOrCreateRenderer(destDim);
        ClientLevel destLevel = PortalWorldManager.getLevel(destDim);
        if (destRenderer == null || destLevel == null) return false;

        PortalInfo destPortal = link.getDestination();
        BlockPos destOrigin = destPortal.getOrigin();
        destLevel.getChunkSource().updateViewCenter(
            destOrigin.getX() >> 4, destOrigin.getZ() >> 4);

        // Phase 4c: chunks now arrive via the redirected vanilla packet handler,
        // which lands them straight into destLevel's ClientChunkCache (no snapshot
        // feed to drain). getChunkCount reads that live cache.
        int currentCount = RemoteChunkManager.getChunkCount(destDim);

        // Require minimum chunks before attempting FBO render.
        if (currentCount < 9) {
            if (phase2FailCount <= 5) {
                SeamlessPortalsConstants.rlog(
                    "[SEAMLESS DEBUG] tryFboRender bailed: currentCount={} (need >=9) destDim={}",
                    currentCount, destDim.identifier());
                phase2FailCount++;
            }
            return false;
        }

        try {
            return doFboRender(srcPortal, link, mainCamera, destDim,
                               destRenderer, destLevel, mc);
        } catch (Exception e) {
            if (phase2FailCount <= 3) {
                SeamlessPortalsConstants.LOGGER.error(
                    "[SEAMLESS] FBO render failed", e);
            }
            phase2FailCount++;
            return false;
        }
    }

    /**
     * Render destination world to secondary FBO, then composite through stencil.
     * Matches IP's RendererUsingFrameBuffer.doRenderPortal() + MyGameRenderer.switchAndRenderTheWorld().
     */
    private static boolean doFboRender(
            PortalInfo srcPortal, PortalLink link, Camera mainCamera,
            ResourceKey<Level> destDim, LevelRenderer destRenderer,
            ClientLevel destLevel, Minecraft mc) {

        PortalInfo destPortal = link.getDestination();
        DeltaTracker deltaTracker = mc.getDeltaTracker();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

        if (phase2SuccessCount <= 3) {
            SeamlessPortalsConstants.rlog(
                "[SEAMLESS] FBO render: src={} ({}) size={}x{} axis={}, dest={} ({}) size={}x{} axis={}",
                srcPortal.getOrigin(), srcPortal.getDimension().identifier(),
                srcPortal.getWidth(), srcPortal.getHeight(), srcPortal.getAxis(),
                destPortal.getOrigin(), destDim.identifier(),
                destPortal.getWidth(), destPortal.getHeight(), destPortal.getAxis());
        }

        // ===== 1. Compute destination camera position =====
        // 1:1 mapping through portal transform. Camera mirrors the player's
        // position relative to the destination portal. Oblique near-plane
        // clipping prevents seeing terrain between camera and portal surface.
        Direction.Axis srcAxis = srcPortal.getAxis();
        Direction.Axis destAxis = destPortal.getAxis();
        // 1:1 camera position for screen-space alignment. Separate RenderBuffers
        // on the secondary renderer prevent "Buffer source must not be empty" errors.
        Vec3 destCameraPos = PortalTransform.transformPoint(
            srcPortal, destPortal, srcPortal.getType(), mainCamera.position());

        float yawOffset = (srcAxis != destAxis)
            ? ((srcAxis == Direction.Axis.Z) ? 90.0f : -90.0f)
            : 0;

        // Player rotation for FBO/stencil alignment.
        Camera virtualCamera = new Camera();
        virtualCamera.setLevel(destLevel);
        virtualCamera.setEntity(mc.player);
        ((CameraInvokerMixin) virtualCamera).seamlessportals$invokeSetRotation(
            mainCamera.yRot() + yawOffset, mainCamera.xRot());
        ((CameraInvokerMixin) virtualCamera).seamlessportals$invokeSetPosition(destCameraPos);
        // CRITICAL: tick the camera's EnvironmentAttributeProbe with the destination
        // level and position. Without this, the probe returns default values (all zeros)
        // → fog color is black, sky light factor is 0, ambient is black.
        // The probe reads biome/dimension attributes from the level at the camera position.
        virtualCamera.tick();

        if (phase2SuccessCount <= 3) {
            SeamlessPortalsConstants.rlog(
                "[SEAMLESS DEBUG] Camera: playerPos=({},{},{}) → destCam=({},{},{}) destYaw={} srcAxis={} destAxis={}",
                String.format("%.1f", mainCamera.position().x),
                String.format("%.1f", mainCamera.position().y),
                String.format("%.1f", mainCamera.position().z),
                String.format("%.1f", destCameraPos.x),
                String.format("%.1f", destCameraPos.y),
                String.format("%.1f", destCameraPos.z),
                String.format("%.1f", mainCamera.yRot() + yawOffset),
                srcAxis, destAxis);
        }

        // Build frustum
        CameraRenderState mainCameraState =
            mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        Matrix4f viewMatrix = new Matrix4f();
        virtualCamera.getViewRotationMatrix(viewMatrix);
        Matrix4f projMatrix = new Matrix4f(mainCameraState.projectionMatrix);
        Frustum destFrustum = new Frustum(viewMatrix, projMatrix);
        destFrustum.prepare(destCameraPos.x, destCameraPos.y, destCameraPos.z);
        ((CameraInvokerMixin) virtualCamera).seamlessportals$setCullFrustum(destFrustum);

        // Phase 2/5 (IP "live window") NATIVE-RENDER GATE. When the dest is DENSE
        // around the view center (the dimension you just LEFT always is), drive
        // the engine's own occlusion-graph cull — extract()'s applyFrustum +
        // render()'s sog.update, O(visible) — instead of the O(all
        // viewArea.sections) manual scan. That manual scan over a full-RD mirror
        // (13k–19k sections) is the multi-second post-crossing freeze; the native
        // path renders only what's visible, like vanilla/IP. We do NOT capture the
        // frustum in that case (capture makes extract() skip applyFrustum and the
        // CameraRenderState report isFrustumCaptured → render() skips sog.update).
        //
        // Phase 4c: chunks now arrive via REDIRECTED VANILLA packets, whose handler
        // runs through the dest ClientLevel under a world-switch — driving the
        // engine's own chunk-load tracking + onChunkReadyToRender on the dest
        // renderer, exactly as the main world does. That keeps the SectionOcclusionGraph
        // coherent as the dest streams in, so sog.update no longer churns on an
        // incomplete graph. The density/stability gates (which existed only to keep
        // the native path off a snapshot-fed, SOG-cold dest) are therefore RETIRED:
        // we drive the engine's native render whenever we render at all (the >=9
        // chunk gate above still defers to the solid background until there's data).
        //
        // Phase 2 (warm-graph): the native path drives the engine's SectionOcclusionGraph
        // (sog.update), which both DISPLAYS the dest occlusion-culled AND keeps the
        // graph WARM for the virtual (≈ post-teleport) camera — so on promote the
        // main render finds a current graph and there's NO blank flash. But sog.update
        // CHURNS the render thread while chunks stream in, so we gate it on a SETTLED
        // dest: dense around the view center AND its loaded-chunk count flat for
        // NATIVE_STABLE_NANOS. A still-streaming dest (freshly-lit portal) stays on the
        // captured-frustum manual scan (sog.update suppressed) until it settles.
        // ...AND the dest must be SMALL ENOUGH to warm safely. The runPartialUpdate
        // flood-skip handles the synchronous BFS, but the first native frame still
        // does O(loadedChunks) graph setup (updateLoadedChunks/updateEmptySections +
        // the async full-build dispatch over the whole graph). A residency-bounded
        // fresh dest (~289 chunks, the nether) warms fine and teleports instantly;
        // the DEMOTED dim carries its full active render distance (1662 chunks at
        // high RD) and overwhelms that setup on activation → freeze. Cap warming to
        // dests under NATIVE_MAX_LOADED_CHUNKS; bigger dests keep the manual scan
        // (brief promote flash, but no freeze).
        final boolean nativeRender = useContinuousExtract
            && PortalWorldManager.isDestResident(
                destLevel,
                net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(destCameraPos.x)),
                net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(destCameraPos.z)),
                NATIVE_RESIDENCY_RADIUS)
            && PortalWorldManager.isDestStable(destLevel, NATIVE_STABLE_NANOS)
            && destLevel.getChunkSource().getLoadedChunksCount() <= NATIVE_MAX_LOADED_CHUNKS;

        // One-shot per-dim diagnostic: log the transition into the warm native
        // path (and out of it), so the log shows exactly when sog.update starts
        // driving a dim — and, if it ever hangs, which dim it hung on.
        Boolean prevNative = lastNativeState.get(destDim);
        if (prevNative == null || prevNative != nativeRender) {
            lastNativeState.put(destDim, nativeRender);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS WARM] native(sog.update) render for {} -> {} (loadedChunks={})",
                destDim.identifier(), nativeRender,
                destLevel.getChunkSource().getLoadedChunksCount());
        }

        if (!nativeRender) {
            // Capture the frustum so extract() skips applyFrustum (the manual scan
            // below supplies visibleSections) and sog.update is skipped (no hang on
            // a sparse graph).
            ((CameraInvokerMixin) virtualCamera).seamlessportals$setCapturedFrustum(destFrustum);
        }
        ((CameraInvokerMixin) virtualCamera).seamlessportals$setInitialized(true);

        // ===== 3. Direct section compilation (sparse chunks, bypass occlusion graph) =====
        // 26.2 render split: setLevel/dirty/extract moved off LevelRenderer onto
        // the dimension's LevelExtractor. The section-compile loop + the level
        // extract below route through it.
        net.minecraft.client.renderer.extract.LevelExtractor destExtractor =
            PortalWorldManager.getExtractor(destDim);

        // CRITICAL (26.2): destExtractor.extract() populates the extractor's OWN
        // (final) LevelRenderState, but the renderer's levelRenderState reference
        // can DIVERGE from it — promoteToMain re-binds a renderer to the shared
        // main state (PortalWorldManager.java:611). When they differ, render()
        // reads the renderer's state while extract() wrote the extractor's, so
        // entities, clouds, and particles (which all live in the render-state)
        // silently vanish in the portal view — while terrain still renders because
        // it draws from visibleSections on the renderer, not the render-state.
        // Re-bind the renderer to the extractor's state so render() sees what
        // extract() writes.
        boolean seamlessStateWasMismatched = false;
        if (destExtractor != null) {
            LevelRenderState extractorState =
                ((com.warwa.seamlessportals.mixin.client.LevelExtractorAccessor) (Object) destExtractor)
                    .seamlessportals$getLevelRenderState();
            LevelRenderState rendererState =
                ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getLevelRenderState();
            if (rendererState != extractorState) {
                seamlessStateWasMismatched = true;
                ((LevelRendererAccessorMixin) destRenderer)
                    .seamlessportals$setLevelRenderState(extractorState);
            }
        }
        LevelRenderState destLRS =
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getLevelRenderState();
        net.minecraft.client.renderer.ViewArea viewArea =
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getViewArea();
        it.unimi.dsi.fastutil.objects.ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections =
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$getVisibleSections();

        if (viewArea != null) {
            net.minecraft.core.SectionPos cameraSectionPos = net.minecraft.core.SectionPos.of(destCameraPos);
            viewArea.repositionCamera(cameraSectionPos);
            destLevel.getChunkSource().updateViewCenter(cameraSectionPos.x(), cameraSectionPos.z());

            SectionRenderDispatcher dispatcher = destRenderer.sectionRenderDispatcher();
            // CRITICAL: Tell the dispatcher where the camera is.
            // Vanilla calls this in cullTerrain() every frame (LevelRenderer.java:401).
            // Without it, the terrain shader doesn't know the camera position
            // → chunks render at wrong screen positions ("far away" / "wrong view").
            dispatcher.setCameraPosition(destCameraPos);

            net.minecraft.client.renderer.chunk.RenderRegionCache cache =
                new net.minecraft.client.renderer.chunk.RenderRegionCache();

            visibleSections.clear();
            prebuiltVisibleSections.clear();
            int compiled = 0;
            // PortalFrameSuppressor DISABLED:
            // User expects to see the destination obsidian frame through the
            // source portal opening (matches Immersive Portals' classical
            // behaviour). Hiding it left empty sky/void in the FBO at those
            // pixels, and any stencil bleed onto source floor/frame pixels
            // showed that void through them ("X-ray floor" effect).
            //
            // Force-dirty sections near the destination portal ONCE per portal
            // so any previously suppression-baked meshes get regenerated with
            // the full obsidian frame. After that initial pass, the normal
            // isDirty() path handles updates as usual.
            PortalFrameSuppressor.maybeForceDirtyForPortal(destPortal, viewArea);
            // Phase A (2026-04-17): Schedule dirty sections on the background
            // executor via {@code rebuildSectionAsync} instead of blocking the
            // render thread with {@code rebuildSectionSync}. This matches the
            // pattern vanilla's {@code LevelRenderer.compileSections} uses for
            // the primary renderer and IP uses for every renderer.
            //
            // - First-frame portal view after teleport may show "holes" where
            //   sections are still compiling; they pop in within tens of ms.
            // - No render-thread stall → no "sky-color flash" on teleport.
            // - Radius gate kept: don't flood worker queue with sections
            //   outside the visible portal-view frustum.
            // - Vanilla compilability filter applied: only schedule sections
            //   that already have a (stale) mesh OR have all 8 neighbor
            //   chunks loaded — otherwise mesh compiles against missing
            //   neighbors and produces broken chunk borders.
            //
            // Sections with existing (possibly stale) meshes are still added
            // to {@code visibleSections} so the renderer shows SOMETHING while
            // the fresh compile runs. UNCOMPILED sections contribute zero
            // draws but are harmless to have in the list.
            int camSecX = cameraSectionPos.x();
            int camSecZ = cameraSectionPos.z();
            int scheduledAsync = 0;
            int skippedFar = 0;
            int deferredCompiles = 0;
            // Per-frame TIME budget on async-compile scheduling. Each
            // compileAsync does a synchronous RenderRegionCache.createRegion
            // chunk-snapshot (~1ms/section) ON THE RENDER THREAD. The old fixed
            // count of 128/frame therefore stalled the render thread ~128ms per
            // frame for the dozens of frames it took to drain a post-crossing
            // backlog — the multi-second near-freeze + FPS collapse on teleport.
            // A TIME budget caps the per-frame render-thread stall to a small
            // fraction of a frame REGARDLESS of backlog size; remaining
            // dirty/uncompiled sections roll onto the next frames and the
            // per-tick advanceCompilePipelines pump, so the view fills in
            // SMOOTHLY instead of lurching. (Count is still tracked for logging.)
            // Throttle the portal-view's own mesh compiling during the post-teleport
            // reload so the shared worker pool serves the MAIN render (smooth reload).
            // The portal view fills a touch slower for those ~seconds; far better than
            // starving the main render's far-chunk reload into a stutter.
            final long PORTAL_VIEW_COMPILE_BUDGET_NS = isPromoteBridgeActive() ? 400_000L : 3_000_000L;
            // FRUSTUM CULL — full RD, only sections actually visible
            // through portal opening are added to visibleSections.
            // Without this filter, all ~78K loaded sections in the
            // cached level's viewArea got added every frame → 10 FPS
            // stalls.
            //
            // Frustum check uses {@code destFrustum} (narrow cone from
            // virtual camera through portal opening). Sections outside
            // are entirely off-screen for the portal-view, so skipping
            // them costs nothing visually.
            //
            // Compile scheduling still uses the small radius (8) — only
            // schedule compiles for sections close to the virtual
            // camera, since those will be the ones actually rendered.
            // 26.2: the section dirty flag + async compile moved off
            // RenderSection onto the dimension's LevelExtractor
            // SectionUpdateTracker (D2). Query/clear dirty via the tracker;
            // {@code rebuildSectionAsync(cache)} → {@code compileAsync(region)}.
            net.minecraft.client.SectionUpdateTracker sut =
                destExtractor != null ? destExtractor.sectionUpdateTracker : null;
            java.util.Set<Long> schedSet =
                portalCompileScheduled.computeIfAbsent(destDim, k -> new java.util.HashSet<>());
            // Native path SKIPS the O(all viewArea.sections) manual scan (this is
            // the post-crossing freeze). extract()'s applyFrustum (occlusion-graph
            // BFS) supplies visibleSections and its sectionUpdates loop schedules
            // compiles — like vanilla/IP. The compile-count logs below self-skip
            // (compiled/scheduled stay 0).
            // Bounded flood-fill replaces the old O(all viewArea.sections) per-frame
            // scan (the dominant teleport-stutter cost: ~78K–101K iterations/frame at
            // render distance 32, just to find the few thousand visible through the
            // portal). VisibleSectionDiscovery walks ONLY the connected, in-cone,
            // in-radius sections and folds in the same hasChunk gate + budgeted
            // dirty/UNCOMPILED async-compile scheduling + dual-list population the old
            // scan did. It never touches SectionOcclusionGraph, so the sog.update hang
            // cannot return. Bound = the same 2D horizontal cylinder (destDepthRadiusSq,
            // full Y column) the old scan admitted on.
            if (!nativeRender) {
                // IP's inner-frustum portal cull: narrow visibility to the cone through
                // the portal OPENING (not the full camera frustum), so the FBO draw
                // submits only the sections actually visible through the hole — IP's one
                // real render-cost lever (FrustumCuller.getFlatPortalInnerFrustumCullingFunc).
                // This directly shrinks the FeatureRenderDispatcher terrain draw that was
                // stalling the GPU 120-223ms per lit-portal frame. Built from the DEST
                // portal opening + the virtual (mirror) camera.
                PortalInnerCull.Cone innerCull =
                    PortalInnerCull.buildFromDestPortal(destPortal, destCameraPos);
                scheduledAsync = VisibleSectionDiscovery.discoverAndScheduleForPortalView(
                    viewArea, destCameraPos, destFrustum, innerCull, destDepthRadiusSq(),
                    destLevel, sut, cache, schedSet, PORTAL_VIEW_COMPILE_BUDGET_NS,
                    visibleSections, prebuiltVisibleSections);
                compiled = visibleSections.size();
            }
            if (phase2SuccessCount == 0 && compiled > 0) {
                SeamlessPortalsConstants.rlog(
                    "[SEAMLESS] Direct compilation: {} sections at [{},{}] "
                        + "(scheduledAsync={}, skippedFarDirty={})",
                    compiled, cameraSectionPos.x(), cameraSectionPos.z(),
                    scheduledAsync, skippedFar);
            }
        }

        // ===== 4. Extract + terrain-cull MOVED into the switched-world block =====
        // 26.2 + Sodium fidelity fix (replaces the removed 26.1.2
        // {@code destRenderer.update(virtualCamera)}):
        //
        // In 26.1.2 the cull/Sodium-activation entry point was
        // {@code LevelRenderer.update(Camera)} → {@code cullTerrain()}, and the
        // pre-port code called it INSIDE {@code withSwitchedWorld} (i.e. while
        // {@code mc.levelRenderer == destRenderer}) right before {@code renderLevel}.
        //
        // In 26.2 that entry point moved to {@code LevelExtractor.extract(...)}:
        //   * Vanilla: {@code extract(...)} runs {@code applyFrustum(cullFrustum)}
        //     which CLEARS + repopulates {@code visibleSections} from the
        //     SectionOcclusionGraph (BFS graph that lags / never settles for a
        //     sparse, just-fed secondary level). That clobbers the manual
        //     frustum-cull list the section loop above built — leaving
        //     {@code prepareChunkRenders} nothing to draw ("No compiled chunks").
        //   * Sodium 0.9.0: its {@code LevelExtractorMixin.cullTerrain} @Inject
        //     (which calls {@code SodiumWorldRenderer.setupTerrain(...)}) resolves
        //     the SodiumWorldRenderer via {@code Minecraft.getInstance().levelRenderer}
        //     (LevelExtractorMixin.checkRenderer — verified in the 26.2 jar). So
        //     {@code extract(...)} only drives the CORRECT (dest) renderer's Sodium
        //     chunk graph when {@code mc.levelRenderer == destRenderer}. Running it
        //     outside the switch drove the MAIN renderer's SWR with dest data
        //     (→ empty dest graph, and the CullTask-on-terminated-pool crash).
        //
        // Therefore {@code extract(...)} + the authoritative {@code visibleSections}
        // re-population + the {@code prepareChunkRenders} bail now all run inside the
        // switched-world block below, mirroring exactly where {@code update()} ran.
        //
        // destViewMatrix is the virtual camera's view-rotation matrix; computed here
        // because the inner-clip setup + the render(...) call (both inside the
        // switch lambda) capture it as an effectively-final local.
        final Matrix4f destViewMatrix = new Matrix4f();
        virtualCamera.getViewRotationMatrix(destViewMatrix);

        // ===== 5. Prepare secondary FBO (match IP's SecondaryFrameBuffer.prepare()) =====
        prepareSecondaryFbo();

        // ===== 6. Build CameraRenderState for destination =====
        CameraRenderState destCameraState = destLRS.cameraRenderState;
        virtualCamera.extractRenderState(destCameraState, partialTick);
        // Phase 2 (IP "live window"): re-enable the dest occlusion-graph build in
        // destRenderer.render() (LevelRenderer.render → sectionOcclusionGraph
        // .update(...), gated on !isFrustumCaptured). extractRenderState just set
        // this true from our captured frustum, which would skip the build and
        // leave the graph cold — the root of the post-teleport blank/prime. The
        // SOLE renderer consumer of isFrustumCaptured is SectionOcclusionGraph
        // .update (verified), so this only warms the dest graph; the display path
        // (captured-frustum extract skip + manual scan) is unchanged.
        if (nativeRender) {
            // Native path: ensure the dest graph build runs (we already left the
            // frustum un-captured above; this is belt-and-suspenders).
            destCameraState.isFrustumCaptured = false;
        }
        // Override projection from main camera (same FOV/aspect)
        destCameraState.projectionMatrix.set(mainCameraState.projectionMatrix);

        // Zero out bob/hurt state on destCameraState as a defensive measure
        // (in case any render-path consults it directly — vanilla doesn't
        // inside LevelRenderer.renderLevel, but mods/mixins might).
        if (destCameraState.entityRenderState != null) {
            destCameraState.entityRenderState.bob = 0.0f;
            destCameraState.entityRenderState.backwardsInterpolatedWalkDistance = 0.0f;
            destCameraState.entityRenderState.hurtTime = -1.0f;
            destCameraState.entityRenderState.hurtDuration = 1;
            destCameraState.entityRenderState.isDeadOrDying = false;
        }

        // Oblique near-plane clipping — clips terrain between camera and portal.
        // Shift the clip plane ~0.55 blocks TOWARD the destination camera so it
        // sits slightly on the camera-side of the destination portal's near face
        // (not the portal center, and not coplanar with the near face itself).
        //
        // 0.5 would put the plane exactly on the destination obsidian's near face
        // — floating-point precision then makes the obsidian flicker in and out
        // as the camera moves. 0.55 gives a 0.05-block safety margin while still
        // keeping the clip plane near enough to the portal to hide anything in
        // front of it.
        // Oblique near-plane clipping REMOVED (2026-04-24) — replaced with
        // gl_ClipDistance inner clip plane (see withSwitchedWorld lambda
        // below). IP does not use oblique; it uses a clip-plane uniform
        // only. Oblique projection corrupts depth precision across the
        // whole frustum; gl_ClipDistance only affects clipped fragments.
        boolean obliqueApplied = false;

        // NOTE: no longer applying bob to the destination projection.
        // Instead, MainProjectionBobMixin skips the main projection's bob
        // entirely (world + destination both render without bob), while the
        // hand still bobs via its own pose stack in renderItemInHand. This
        // avoids the subtle rendering artifacts that appeared when we
        // multiplied bob into the destination projection directly.

        // ===== 7. Compute destination fog =====
        FogRenderer fogRenderer =
            ((GameRendererAccessorMixin) mc.gameRenderer).seamlessportals$getFogRenderer();
        FogData destFogData = fogRenderer.setupFog(
            virtualCamera,
            mc.options.getEffectiveRenderDistance(),
            deltaTracker,
            0f, // no boss darkening for portal view
            destLevel
        );
        // SAVE the main render's fogData + fogType BEFORE overwriting them.
        // In MC 26.1.2, {@code levelRenderState.cameraRenderState} is shared
        // across LevelRenderer instances — writing destFogData here without
        // restoring leaks the destination dim's fog into the main render
        // after we return. Vanilla didn't read fogData directly from this
        // field (it used the GPU fog buffer we isolate via
        // {@code writePortalFogBuffer}), so the leak was invisible. Sodium
        // and any mod that reads {@code cameraRenderState.fogData} directly
        // sees the stale dest-dim values. Save+restore is the safe pattern.
        final FogData savedFogData = destCameraState.fogData;
        final FogType savedFogType = destCameraState.fogType;
        destCameraState.fogData = destFogData;
        destCameraState.fogType = FogType.NONE;

        // Create a SEPARATE fog buffer for portal rendering.
        // DO NOT call fogRenderer.updateBuffer() — that overwrites the main renderer's
        // fog buffer (MappableRingBuffer shared memory), causing dark clipping artifacts
        // across the entire world. Instead, write directly to our own buffer.
        com.mojang.blaze3d.buffers.GpuBufferSlice destFogBuffer = writePortalFogBuffer(destFogData);

        // ===== 8. Update per-dimension lightmap (match IP's DimensionRenderHelper) =====
        // Uses virtual camera's attributeProbe() to get destination dimension values.
        // No more hardcoded per-dimension lightmap — computed from actual world state.
        DimensionRenderHelper dimHelper = DimensionRenderHelper.getOrCreate(destDim);
        dimHelper.updateAndRender(virtualCamera, partialTick);
        portalLightmapOverride = dimHelper.getLightmap().getTextureView();

        // ===== 9. Full context switch via withSwitchedWorld =====
        // Pre-capture for the lambda's nested finally (Globals UBO restore
        // needs source-side game time + camera position; both come from
        // mc.level / mainCamera before the swap).
        // (destViewMatrix was computed earlier at the prepareChunkRenders step.)
        final Vec3 savedCameraPos = mainCamera.position();
        final long savedLevelGameTime = mc.level.getGameTime();
        final boolean obliqueAppliedFinal = obliqueApplied;

        // Result channel from inside the switch lambda: set true once the
        // dest world is actually drawn into the FBO. Stays false if the
        // terrain cull produced no geometry yet (async meshes still
        // compiling) → caller falls back to the colored-block/background
        // render and retries next frame. A 1-element array because the
        // lambda can't reassign a captured local.
        final boolean[] fboRendered = { false };

        isRenderingPortal = true;
        try {
            withSwitchedWorld(
                destLevel, destRenderer, secondaryFbo, virtualCamera,
                dimHelper.getLightmap(),
                () -> {
                    // ===== 4a. Extract + terrain cull (now INSIDE the switch) =====
                    // This is the 26.2 replacement for 26.1.2's
                    // {@code destRenderer.update(virtualCamera)}: it must run while
                    // {@code mc.levelRenderer == destRenderer} (true here) so that:
                    //   * Sodium's {@code LevelExtractorMixin.cullTerrain} @Inject
                    //     (fired from inside {@code extract(...)}) resolves the
                    //     SodiumWorldRenderer via {@code mc.levelRenderer} and drives
                    //     {@code setupTerrain} on the DEST renderer's chunk graph —
                    //     not the main renderer's (which caused the empty graph + the
                    //     CullTask-on-terminated-pool crash).
                    //   * vanilla {@code extract(...)}'s {@code applyFrustum} runs here,
                    //     then we immediately re-populate {@code visibleSections} with
                    //     the manual portal-view frustum cull so it is authoritative
                    //     for the {@code render(...)} call below (extract's
                    //     occlusion-graph result is sparse/lagging for a just-fed
                    //     secondary level).
                    if (destExtractor != null) {
                        destExtractor.extract(deltaTracker, virtualCamera, partialTick);
                    }
                    // Diagnostic (gated, temporary): at render time mc.particleEngine
                    // is the dest engine — confirm it holds particles to draw and
                    // that the skip-gate is open.
                    if (phase2SuccessCount <= 3) {
                        SeamlessPortalsConstants.rlog(
                            "[SEAMLESS PARTICLE] render dim={} destEngine=[{}] destParticlesActive={}",
                            destDim.identifier(), mc.particleEngine.countParticles(),
                            destParticlesActive);
                    }
                    // Manual path only: re-assert the portal-view visible-section
                    // set from the manual scan result. On the NATIVE path, extract()'s
                    // applyFrustum (occlusion-graph BFS) already populated
                    // visibleSections from the SOG — overwriting it here with the
                    // (empty) prebuilt manual-scan list would blank the view.
                    if (!nativeRender) {
                        populateVisibleSectionsByFrustum(
                            destRenderer, viewArea, destFrustum, destLevel);
                    }

                    // 26.2: ChunkSectionsToRender is produced by
                    // prepareChunkRenders(Matrix4fc) (render(...) calls it internally).
                    // Pre-compute it here — now that visibleSections is authoritative —
                    // for the maxIndices==0 bail + diagnostics. Under Sodium this still
                    // reflects vanilla's visibleSections (Sodium's draw goes through its
                    // own render(...) wrap), so it remains a valid "is there geometry?"
                    // probe.
                    ChunkSectionsToRender destChunks = destRenderer.prepareChunkRenders(destViewMatrix);
                    // 26.2 FIX — do NOT bail when destChunks is empty.
                    // The section compile→upload pipeline lives INSIDE
                    // LevelRenderer.render(): prepareChunkRenders (reads uploaded,
                    // :211/:535) → compileSections (:255/:608) →
                    // sectionRenderDispatcher.uploadTerrainBuffersToGpu() (:262).
                    // Returning here skipped render() entirely, so the secondary
                    // renderer's meshes were scheduled (compileAsync) but NEVER
                    // uploaded → getRenderSectionSlice null → prepareChunkRenders
                    // perpetually empty ("constant thin sliver that never fills").
                    // Let render() run every frame (as vanilla does); it performs the
                    // compile+upload and the FBO fills over the next frames. This
                    // standalone destChunks probe is never null (prepareChunkRenders
                    // always returns a fresh record, LevelRenderer.java:605); it stays
                    // only for the diagnostics + Sodium re-point below.
                    if (destChunks.maxIndicesRequired() == 0 && phase2FailCount <= 8) {
                        SeamlessPortalsConstants.rlog(
                            "[SEAMLESS] dest empty this frame for {} (#{}) — running render() to compile+upload",
                            destDim.identifier(), phase2FailCount + 1);
                        phase2FailCount++;
                    }

                    // Diagnostic: count draw groups per layer to verify terrain will render.
                    if (phase2SuccessCount <= 5) {
                        int totalDraws = 0;
                        for (var layerEntry : destChunks.drawGroupsPerLayer().values()) {
                            for (var drawList : layerEntry.values()) {
                                totalDraws += drawList.size();
                            }
                        }
                        SeamlessPortalsConstants.rlog(
                            "[SEAMLESS DEBUG] destChunks: maxIndices={} totalDraws={} textureView={}",
                            destChunks.maxIndicesRequired(), totalDraws,
                            destChunks.textureView() != null ? "valid" : "NULL");
                    }

                    if (phase2SuccessCount <= 3) {
                        int mainFbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
                        SeamlessPortalsConstants.rlog(
                            "[SEAMLESS DEBUG] Context switch: level={} renderer={} mainRT={}x{} glFbo={}",
                            mc.level.dimension().identifier(),
                            mc.levelRenderer == destRenderer ? "dest" : "WRONG",
                            mc.gameRenderer.mainRenderTarget().width, mc.gameRenderer.mainRenderTarget().height,
                            mainFbo);
                        SeamlessPortalsConstants.rlog(
                            "[SEAMLESS DEBUG] renderLevel fogColor=({},{},{},{}) destChunks.maxIndices={} skyRender=true cam=({},{},{})",
                            destFogData.color.x, destFogData.color.y, destFogData.color.z, destFogData.color.w,
                            destChunks.maxIndicesRequired(),
                            (int) destCameraPos.x, (int) destCameraPos.y, (int) destCameraPos.z);
                        SeamlessPortalsConstants.rlog(
                            "[SEAMLESS DEBUG] fogDistances: envStart={} envEnd={} renderStart={} renderEnd={} skyEnd={} cloudEnd={}",
                            destFogData.environmentalStart, destFogData.environmentalEnd,
                            destFogData.renderDistanceStart, destFogData.renderDistanceEnd,
                            destFogData.skyEnd, destFogData.cloudEnd);
                    }

                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                    // Inner clip plane — IP's actual technique. Active ONLY
                    // during the nested dest-dim render inside the switched
                    // world. Keeps dest geometry on the far side of the dest
                    // portal plane (the side the source camera "looks into"
                    // through the portal). Dest geometry in front of the
                    // dest portal (between the virtual camera and the dest
                    // portal face) is clipped — this prevents the obsidian
                    // frame / mobs / floor from poking through the stencil
                    // mask onto source pixels.
                    //
                    // Previously we used oblique near-plane clipping, which
                    // achieves the same geometric goal but corrupts depth
                    // precision across the whole frustum. IP uses this
                    // gl_ClipDistance approach; we now match it.
                    //
                    // Capture/restore discipline: the outer plane state
                    // coming into this block is whatever the main-camera
                    // pass set (currently always default-no-op now that
                    // GameRendererMainClipMixin has been deleted). We
                    // still capture/restore so future work (entity cross-
                    // portal clip) doesn't surprise this code path.
                    FrontClipping.Snapshot outerSnap = FrontClipping.capture();
                    // ===== CURTAIN FIX (2026-06-27): inner clip DISABLED =====
                    // PROVEN from DIAG-STEADY: the inner clip plane sits at the dest
                    // PORTAL (planeW = distance from virtual camera to dest portal).
                    // This mod uses a 1:1 MIRROR camera (destCameraPos = player pos
                    // transformed through the portal), NOT IP's at-the-portal camera.
                    // So as the player moves away from the source portal, the virtual
                    // camera moves away from the dest portal and planeW grows
                    // (-0.7 close → -6.8 far in the logs) — the clip then removes the
                    // ENTIRE near half of the dest view (the nether floor in the lower
                    // screen), leaving only far terrain up top: the "below blank /
                    // above terrain, follows eye-level" curtain, and "solid blue when
                    // far" once planeW swallows everything. The clip's original job
                    // (hide dest-side frame/floor in front of the portal) is already
                    // done by the STENCIL MASK — it confines the composite to the
                    // SOURCE opening, and the mirrored dest frame lands behind the
                    // source frame. So disabling is correct for the mirror approach,
                    // not just a workaround. DIAG-STEADY will now log clip=(0,0,0,1).
                    FrontClipping.disable();
                    org.joml.Matrix4fStack mvStack = RenderSystem.getModelViewStack();
                    mvStack.pushMatrix();
                    mvStack.identity();
                    try {
                        // ALWAYS override the RenderSystem projection during the destination
                        // render — even if oblique clipping wasn't applied. The main render
                        // path in GameRenderer.renderLevel has already pushed the BOBBED
                        // main-camera projection onto RenderSystem (walk-bob multiplied in
                        // before levelRenderer runs). Skipping the override would let the
                        // destination world inherit the player's footstep bob.
                        RenderSystem.backupProjectionMatrix();
                        RenderSystem.setProjectionMatrix(
                            writeProjectionBuffer(destCameraState.projectionMatrix, false),
                            com.mojang.blaze3d.ProjectionType.PERSPECTIVE);
                        if (phase2SuccessCount <= 3) {
                            SeamlessPortalsConstants.rlog(
                                "[SEAMLESS DEBUG] Projection: backed up + set {}. m22={} m32={}",
                                obliqueAppliedFinal ? "oblique" : "clean",
                                String.format("%.4f", destCameraState.projectionMatrix.m22()),
                                String.format("%.4f", destCameraState.projectionMatrix.m32()));
                        }
                        try {
                            ((GameRendererAccessorMixin) mc.gameRenderer).seamlessportals$getGlobalSettingsUniform().update(
                                mc.gameRenderer.mainRenderTarget().width,
                                mc.gameRenderer.mainRenderTarget().height,
                                mc.gameRenderer.gameRenderState().optionsRenderState.glintStrength,
                                destLevel.getGameTime(),
                                deltaTracker,
                                mc.gameRenderer.gameRenderState().optionsRenderState.menuBackgroundBlurriness,
                                destCameraPos,
                                false
                            );

                            // 26.2 + Sodium: the 26.1.2 {@code destRenderer.update(virtualCamera)}
                            // (which drove cullTerrain → Sodium's RenderSectionManager
                            // setup) is replaced by {@code destExtractor.extract(...)}
                            // run at the TOP of this lambda (step 4a). Because that
                            // extract runs while {@code mc.levelRenderer == destRenderer},
                            // Sodium's {@code LevelExtractorMixin.cullTerrain} drives
                            // {@code setupTerrain} on the DEST renderer's SodiumWorldRenderer
                            // — re-establishing exactly what update() did.

                            // SodiumFogOverride.activate: tell Sodium's
                            // GameRendererMixin to serve dest-dim fog
                            // instead of its captured main-render fog
                            // for the duration of this renderLevel call.
                            //
                            // SEAMLESS-26.2-TODO (Sodium draw matrices): the call below
                            // re-points the Sodium mixin fields on the PRE-COMPUTED
                            // destChunks. The actual draw issued by {@code render(...)}
                            // uses a DIFFERENT ChunkSectionsToRender that render(...)
                            // builds internally via prepareChunkRenders — and Sodium's
                            // own {@code LevelRendererMixin.getRenderState} @WrapOperation
                            // already points THAT one at the dest SWR with the portal-view
                            // matrices (from the RenderSystem projection we set above) and
                            // the dest camera pos (from destCameraState). So this explicit
                            // re-point is now effectively redundant/harmless; kept only as
                            // a belt-and-suspenders no-op for older Sodium builds whose
                            // wrap path differs. Drop once the 0.9.0 draw path is
                            // visually confirmed.
                            com.warwa.seamlessportals.compat.SodiumBridge
                                .updateChunkSectionsRenderer(
                                    destChunks, destRenderer,
                                    destCameraState.projectionMatrix,
                                    destViewMatrix,
                                    destCameraPos.x, destCameraPos.y, destCameraPos.z);

                            com.warwa.seamlessportals.render.SodiumFogOverride
                                .activate(destFogData);
                            int sodiumVisBefore = -1;
                            if (phase2SuccessCount <= 3) {
                                sodiumVisBefore = com.warwa.seamlessportals.compat.SodiumBridge
                                    .getVisibleChunkCount(destRenderer);
                            }
                            try {
                                // 26.2: LevelRenderer.renderLevel(...) → render(...)
                                // with the new 8-arg signature (no trailing
                                // ChunkSectionsToRender — render(...) produces its own
                                // internally via prepareChunkRenders(modelView)) (D5).
                                destRenderer.render(
                                    GraphicsResourceAllocator.UNPOOLED,
                                    deltaTracker,
                                    false,
                                    destCameraState,
                                    destViewMatrix,
                                    destFogBuffer,
                                    destFogData.color,
                                    true
                                );
                                // The dest world was drawn into the FBO — the
                                // caller may composite it through the stencil.
                                fboRendered[0] = true;
                            } finally {
                                com.warwa.seamlessportals.render.SodiumFogOverride.clear();
                            }
                            if (phase2SuccessCount <= 3) {
                                int sodiumVisAfter = com.warwa.seamlessportals.compat.SodiumBridge
                                    .getVisibleChunkCount(destRenderer);
                                String debugInfo = com.warwa.seamlessportals.compat.SodiumBridge
                                    .getDebugInfo(destRenderer);
                                SeamlessPortalsConstants.rlog(
                                    "[SEAMLESS SODIUM] visibleChunkCount: before={}, after={} | {}",
                                    sodiumVisBefore, sodiumVisAfter, debugInfo);
                            }
                        } finally {
                            // Restore Globals UBO with the source camera while mc.mainRT is
                            // still the secondary FBO (sizes match — secondaryFbo was sized
                            // to main FBO at prepare time).
                            ((GameRendererAccessorMixin) mc.gameRenderer).seamlessportals$getGlobalSettingsUniform().update(
                                mc.gameRenderer.mainRenderTarget().width,
                                mc.gameRenderer.mainRenderTarget().height,
                                mc.gameRenderer.gameRenderState().optionsRenderState.glintStrength,
                                savedLevelGameTime,
                                deltaTracker,
                                mc.gameRenderer.gameRenderState().optionsRenderState.menuBackgroundBlurriness,
                                savedCameraPos,
                                false
                            );
                            RenderSystem.restoreProjectionMatrix();
                            if (phase2SuccessCount <= 3) {
                                SeamlessPortalsConstants.rlog(
                                    "[SEAMLESS DEBUG] Projection: restored from backup");
                            }
                        }
                    } finally {
                        mvStack.popMatrix();
                        // Restore the clip plane state captured BEFORE the
                        // nested render (inner clip is swapped out here).
                        // Post-deletion of GameRendererMainClipMixin this
                        // restore is mainly defensive in case we later
                        // re-introduce an entity-level clip plane.
                        FrontClipping.restore(outerSnap);
                        GL11.glEnable(GL11.GL_STENCIL_TEST);
                        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
                        GL11.glStencilMask(0x00);
                    }
                }
            );
        } finally {
            isRenderingPortal = false;
            portalLightmapOverride = null;
            // Restore the main render's fog data + type that we overwrote
            // at "===== 7. Compute destination fog =====". Sodium and any
            // mod that reads cameraRenderState.fogData directly would
            // otherwise see the destination dim's fog after we return,
            // tinting the source-dim main world with dest-dim fog.
            destCameraState.fogData = savedFogData;
            destCameraState.fogType = savedFogType;

            // Restore Sodium's captured FogParameters on FogRenderer.
            // Our portal render called {@code fogRenderer.setupFog(virtualCamera, ..., destLevel)}
            // at "===== 7. Compute destination fog =====". Sodium has an
            // @Inject on {@code FogRenderer.setupFog} that captures the
            // resulting {@code FogData} into a per-FogRenderer
            // {@code FogParameters parameters} field
            // ({@code net.caffeinemc.mods.sodium.mixin.core.render.world.FogRendererMixin}).
            // Once captured as nether fog, Sodium's chunk-draw uniforms
            // serve nether fog into the source-dim main render until the
            // NEXT frame's main setupFog runs.
            //
            // Re-run setupFog with the main camera and source level
            // (mc.level at this point is restored to source dim — withSwitchedWorld
            // finally) so Sodium's @Inject re-captures source fog now,
            // not on the next frame. No vanilla side effects: setupFog
            // returns a fresh FogData and doesn't write to the GPU fog
            // buffer.
            if (mc.level != null) {
                try {
                    FogRenderer fr = ((GameRendererAccessorMixin) mc.gameRenderer)
                        .seamlessportals$getFogRenderer();
                    fr.setupFog(
                        mc.gameRenderer.mainCamera(),
                        mc.options.getEffectiveRenderDistance(),
                        deltaTracker,
                        0f,
                        (ClientLevel) mc.level);
                } catch (Exception e) {
                    SeamlessPortalsConstants.LOGGER.warn(
                        "[SEAMLESS SODIUM] Source-fog recapture failed: {}",
                        e.toString());
                }
            }
        }

        // If the terrain cull produced no drawable geometry this frame (async
        // meshes still compiling), the FBO holds only the cleared fog color —
        // do NOT composite it (that would flash fog-only through the portal).
        // Return false so the caller falls back to the colored-block/background
        // render and we retry the FBO path next frame.
        if (!fboRendered[0]) {
            return false;
        }

        // Debug: verify state after restore
        if (phase2SuccessCount <= 3) {
            int postFbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
            boolean stencilEnabled = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
            RenderTarget postRT = mc.gameRenderer.mainRenderTarget();
            SeamlessPortalsConstants.rlog(
                "[SEAMLESS DEBUG] Post-restore: glFbo={} mainRT={}x{} level={} stencil={}",
                postFbo, postRT.width, postRT.height,
                mc.level.dimension().identifier(), stencilEnabled);
        }

        // ===== 12. (composite deferred to phase 2) =====
        // The secondary FBO now holds the destination world. The composite onto
        // the screen happens in PHASE 2 (compositeDestinationWorld →
        // compositePortalFbo), from AFTER_TRANSLUCENT_TERRAIN, AFTER the stencil
        // mask is written. It is NOT done here because:
        //   * this method runs at renderLevel HEAD (phase 1), before the main
        //     framegraph and before the portal stencil mask exists; and
        //   * the composite must clip to the portal shape via the stencil, which
        //     is only set up in phase 2.
        // Returning true marks the FBO ready; prepareDestinationWorld records that
        // in fboReadyThisFrame so phase 2 composites it.

        // No fog restore needed — we never touched the global fogRenderer buffer.

        phase2SuccessCount++;
        if (phase2SuccessCount <= 5) {
            SeamlessPortalsConstants.rlog(
                "[SEAMLESS] FBO renderLevel SUCCESS #{} for {} at ({}, {}, {})",
                phase2SuccessCount, destDim.identifier(),
                (int) destCameraPos.x, (int) destCameraPos.y, (int) destCameraPos.z);
        }

        return true;
    }

    /**
     * Re-populate a renderer's {@code visibleSections} list with the portal-view
     * frustum cull, replacing whatever {@code LevelExtractor.extract(...)} just
     * left there.
     *
     * <p>Background (26.2): {@code extract(...)} ends its frame work by calling
     * {@code applyFrustum(camera.getCullFrustum())}, which {@code clearVisibleSections()}
     * then refills {@code visibleSections} from the {@link net.minecraft.client.renderer.SectionOcclusionGraph}.
     * That occlusion graph is a BFS visibility graph seeded from the camera
     * section and updated asynchronously; for a freshly-fed, sparse secondary
     * portal-view level it is routinely empty or several frames stale, so the
     * refilled {@code visibleSections} has little/nothing in it and
     * {@code prepareChunkRenders} returns {@code maxIndicesRequired()==0}
     * ("No compiled chunks").
     *
     * <p>This is the same direct frustum cull the doFboRender section loop uses
     * (the reliable population mechanism for sparse levels), but population-only:
     * the dirty-section async compile scheduling already happened in that loop.
     * It mirrors what vanilla {@code cullTerrain}/26.1.2 {@code update()} did —
     * clear + populate {@code visibleSections} from the frustum, every frame,
     * immediately before the draw consumes it.
     *
     * <p>Must be called AFTER {@code extract(...)} (so it isn't clobbered) and
     * BEFORE {@code render(...)} (whose internal {@code prepareChunkRenders}
     * reads this list).
     */
    private static void populateVisibleSectionsByFrustum(
            LevelRenderer renderer,
            net.minecraft.client.renderer.ViewArea viewArea,
            Frustum frustum,
            ClientLevel level) {
        if (viewArea == null) return;
        it.unimi.dsi.fastutil.objects.ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections =
            ((LevelRendererAccessorMixin) renderer).seamlessportals$getVisibleSections();
        visibleSections.clear();
        // Reuse the frustum-cull result the doFboRender section sweep already
        // computed THIS frame ({@link #prebuiltVisibleSections}) instead of a
        // second O(all viewArea.sections) scan. The sweep used the identical
        // predicate (hasChunk + destFrustum.isVisible) and destFrustum is fixed
        // for the whole FBO render, so the result is identical — it just halves
        // the per-frame portal-view iteration cost. (viewArea/frustum/level are
        // retained in the signature for callers and the gate above.)
        visibleSections.addAll(prebuiltVisibleSections);
    }

    /**
     * Prepare secondary FBO matching main FBO size.
     * Matches IP's SecondaryFrameBuffer.prepare().
     */
    private static void prepareSecondaryFbo() {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        int w = main.width;
        int h = main.height;
        if (secondaryFbo == null) {
            secondaryFbo = new TextureTarget("seamless_portal", w, h, true, com.mojang.blaze3d.GpuFormat.RGBA8_UNORM);
            SeamlessPortalsConstants.LOGGER.info("[SEAMLESS] Created secondary FBO {}x{}", w, h);
        } else if (secondaryFbo.width != w || secondaryFbo.height != h) {
            secondaryFbo.resize(w, h);
        }
    }

    /**
     * Oblique near-plane clipping (Lengyel method).
     * Modifies the projection matrix so the near clip plane aligns with the
     * portal surface. Everything between the camera and the portal is clipped.
     *
     * @see <a href="https://terathon.com/lengyel/Lengyel-Oblique.pdf">Lengyel paper</a>
     */
    private static boolean applyObliqueNearPlane(
            Matrix4f projMatrix,
            Camera camera,
            Vec3 cameraPos,
            Vec3 portalCenter,
            Vec3 portalNormal) {

        // Get view rotation matrix
        Matrix4f viewRotMatrix = new Matrix4f();
        camera.getViewRotationMatrix(viewRotMatrix);

        // Portal normal should point AWAY from the camera (into the destination).
        float nx = (float) portalNormal.x;
        float ny = (float) portalNormal.y;
        float nz = (float) portalNormal.z;

        double cameraDot = nx * (cameraPos.x - portalCenter.x)
                         + ny * (cameraPos.y - portalCenter.y)
                         + nz * (cameraPos.z - portalCenter.z);

        if (phase2SuccessCount <= 3) {
            SeamlessPortalsConstants.rlog(
                "[SEAMLESS DEBUG] ObliqueClip: cameraDot={} normalFlipped={} nx={} ny={} nz={}",
                String.format("%.3f", cameraDot),
                cameraDot > 0, nx, ny, nz);
        }

        if (cameraDot > 0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }

        // Transform normal to view space (viewRotMatrix is orthonormal)
        float vnx = viewRotMatrix.m00() * nx + viewRotMatrix.m10() * ny + viewRotMatrix.m20() * nz;
        float vny = viewRotMatrix.m01() * nx + viewRotMatrix.m11() * ny + viewRotMatrix.m21() * nz;
        float vnz = viewRotMatrix.m02() * nx + viewRotMatrix.m12() * ny + viewRotMatrix.m22() * nz;

        // d in view space: dot(normal, cameraPos - portalCenter) with the possibly-negated normal
        float vd = nx * (float)(cameraPos.x - portalCenter.x)
                 + ny * (float)(cameraPos.y - portalCenter.y)
                 + nz * (float)(cameraPos.z - portalCenter.z);

        // Compute Q (inverse-projected corner point)
        float qx = (Math.signum(vnx) + projMatrix.m20()) / projMatrix.m00();
        float qy = (Math.signum(vny) + projMatrix.m21()) / projMatrix.m11();
        float qz = -1.0f;
        float qw = (1.0f + projMatrix.m22()) / projMatrix.m32();

        // Scale clip plane
        float dotCQ = vnx * qx + vny * qy + vnz * qz + vd * qw;

        if (phase2SuccessCount <= 3) {
            SeamlessPortalsConstants.rlog(
                "[SEAMLESS DEBUG] ObliqueClip: viewNormal=({},{},{}) vd={} dotCQ={} degenerate={}",
                String.format("%.3f", vnx), String.format("%.3f", vny), String.format("%.3f", vnz),
                String.format("%.3f", vd), String.format("%.3f", dotCQ),
                Math.abs(dotCQ) < 1e-6f);
        }

        if (Math.abs(dotCQ) < 1e-4f) return false; // degenerate — skip clipping

        float scale = 2.0f / dotCQ;

        // Safety: if the resulting values are extreme, skip clipping.
        // Extreme values cause the depth buffer to produce NaN/infinity,
        // which freezes the renderer (all depth tests fail forever).
        float newM02 = vnx * scale;
        float newM12 = vny * scale;
        float newM22 = vnz * scale + 1.0f;
        float newM32 = vd * scale;

        if (Math.abs(newM32) > 50f || Math.abs(newM22) > 50f
                || Float.isNaN(newM32) || Float.isInfinite(newM32)) {
            if (phase2SuccessCount <= 3) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS DEBUG] ObliqueClip: SKIPPED extreme values m22={} m32={}",
                    newM22, newM32);
            }
            return false;
        }

        // Save original row 2 for debug
        float origM02 = projMatrix.m02(), origM12 = projMatrix.m12();
        float origM22 = projMatrix.m22(), origM32 = projMatrix.m32();

        // Replace row 2 of projection matrix (OpenGL NDC z range [-1,+1] → +1.0)
        projMatrix.m02(newM02);
        projMatrix.m12(newM12);
        projMatrix.m22(newM22);
        projMatrix.m32(newM32);

        if (phase2SuccessCount <= 3) {
            SeamlessPortalsConstants.rlog(
                "[SEAMLESS DEBUG] ObliqueClip: row2 BEFORE=({},{},{},{}) AFTER=({},{},{},{})",
                String.format("%.4f", origM02), String.format("%.4f", origM12),
                String.format("%.4f", origM22), String.format("%.4f", origM32),
                String.format("%.4f", projMatrix.m02()), String.format("%.4f", projMatrix.m12()),
                String.format("%.4f", projMatrix.m22()), String.format("%.4f", projMatrix.m32()));
        }

        return true;
    }

    /**
     * Apply GameRenderer's walk-bob + hurt-tilt to the given projection matrix,
     * using the MAIN camera's render state as the bob source.
     *
     * Mirrors the sequence in GameRenderer.renderLevel:
     *   PoseStack bobStack = new PoseStack();
     *   this.bobHurt(cameraState, bobStack);
     *   if (optionsState.bobView) this.bobView(cameraState, bobStack);
     *   projectionMatrix.mul(bobStack.last().pose());
     *
     * so that the destination render's projection has the same visible bob as
     * the source render. Without this, the source frame bobs but the destination
     * FBO is still — and the composite makes the destination content appear to
     * slide within the bobbing frame.
     */
    private static void applyMainCameraBobToProjection(
            Matrix4f projectionMatrix,
            CameraRenderState mainState) {
        if (mainState == null || mainState.entityRenderState == null) return;

        Minecraft mc = Minecraft.getInstance();
        boolean bobViewEnabled = mc.gameRenderer.gameRenderState().optionsRenderState.bobView;
        double damageTiltStrength = mc.gameRenderer.gameRenderState().optionsRenderState.damageTiltStrength;

        Matrix4f bob = new Matrix4f();

        // bobHurt
        if (mainState.entityRenderState.isLiving) {
            if (mainState.entityRenderState.isDeadOrDying) {
                float duration = Math.min(mainState.entityRenderState.deathTime, 20.0f);
                bob.rotateZ((float) Math.toRadians(40.0f - 8000.0f / (duration + 200.0f)));
            }
            float hurt = mainState.entityRenderState.hurtTime;
            if (hurt >= 0.0f) {
                hurt /= mainState.entityRenderState.hurtDuration;
                hurt = net.minecraft.util.Mth.sin(hurt * hurt * hurt * hurt * (float) Math.PI);
                float rr = mainState.entityRenderState.hurtDir;
                bob.rotateY((float) Math.toRadians(-rr));
                float tiltAmount = (float)(-hurt * 14.0 * damageTiltStrength);
                bob.rotateZ((float) Math.toRadians(tiltAmount));
                bob.rotateY((float) Math.toRadians(rr));
            }
        }

        // bobView
        if (bobViewEnabled && mainState.entityRenderState.isPlayer) {
            float walkDist = mainState.entityRenderState.backwardsInterpolatedWalkDistance;
            float bobAmt = mainState.entityRenderState.bob;
            bob.translate(
                net.minecraft.util.Mth.sin(walkDist * (float) Math.PI) * bobAmt * 0.5f,
                -Math.abs(net.minecraft.util.Mth.cos(walkDist * (float) Math.PI) * bobAmt),
                0.0f
            );
            bob.rotateZ((float) Math.toRadians(
                net.minecraft.util.Mth.sin(walkDist * (float) Math.PI) * bobAmt * 3.0f));
            bob.rotateX((float) Math.toRadians(
                Math.abs(net.minecraft.util.Mth.cos(walkDist * (float) Math.PI - 0.2f) * bobAmt) * 5.0f));
        }

        projectionMatrix.mul(bob);
    }

    /**
     * Persistent GPU buffer references for projection matrix save/restore.
     * Must be static fields to prevent GC from invalidating OpenGL handles
     * before the GPU is done with them. NEVER call close() — the GPU may
     * still be referencing the buffer from the previous frame. Old buffers
     * get GC'd naturally when the reference is overwritten.
     */
    private static com.mojang.blaze3d.buffers.GpuBuffer portalProjGpuBuffer = null;
    private static com.mojang.blaze3d.buffers.GpuBuffer restoreProjGpuBuffer = null;

    /**
     * Write a Matrix4f to a GPU buffer for RenderSystem.setProjectionMatrix().
     * @param forRestore true = use restore buffer slot, false = use portal buffer slot
     */
    private static com.mojang.blaze3d.buffers.GpuBufferSlice writeProjectionBuffer(Matrix4f matrix, boolean forRestore) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(64)
            .order(java.nio.ByteOrder.nativeOrder());
        matrix.get(buf);     // JOML writes 64 bytes at position 0 without advancing
        buf.position(64);    // manually advance past the written data
        buf.flip();          // now limit=64, position=0 → 64 bytes readable

        // Create new buffer — do NOT close the old one (GPU may still be using it)
        com.mojang.blaze3d.buffers.GpuBuffer gpuBuf = RenderSystem.getDevice().createBuffer(
            () -> forRestore ? "portal_proj_restore" : "portal_proj_oblique",
            com.mojang.blaze3d.buffers.GpuBuffer.USAGE_UNIFORM, buf);

        if (forRestore) {
            restoreProjGpuBuffer = gpuBuf;
        } else {
            portalProjGpuBuffer = gpuBuf;
        }
        return gpuBuf.slice();
    }

    /**
     * Composite the secondary FBO onto the main render target.
     *
     * Uses createRenderPass with the main RT's textures and binds the FBO texture
     * via pass.bindTexture() (NOT raw GL — raw GL doesn't affect render pass samplers).
     * Draws a full-screen triangle (ENTITY_OUTLINE_BLIT pipeline).
     * Stencil EQUAL(1) from StencilPortalRenderer clips to portal area.
     *
     * RenderTargetMixin (on {@code FrameBufferCache.createFbo}) ensures the
     * render pass FBO has DEPTH_STENCIL_ATTACHMENT so stencil values from
     * earlier writes are accessible.
     */
    private static void compositePortalFbo() {
        if (secondaryFbo == null || secondaryFbo.getColorTextureView() == null) return;

        Minecraft mc = Minecraft.getInstance();
        com.mojang.blaze3d.pipeline.RenderTarget mainRT = mc.gameRenderer.mainRenderTarget();

        // Re-enable stencil test (renderLevel may have changed GL state)
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        GL11.glStencilMask(0x00);
        // Disable blend so the FBO texture completely replaces the main world content.
        // The FBO's clear color has alpha=0 (hardcoded in MC). Without disabling blend,
        // the main world's content (clouds, sky) shows through.
        GL11.glDisable(GL11.GL_BLEND);
        // CURTAIN FIX: raw-GL backstop — disable depth test for the composite so it is
        // never GEQUAL-gated by the leftover portal-plane depth in the opening (DIAG-PRE
        // proved that gate produced the blue curtain at the top of the opening). The
        // pipeline's Optional.empty() depth state already calls _disableDepthTest in
        // applyPipelineState, but this also covers the case where that apply is skipped
        // (GlCommandEncoder caches lastPipeline and short-circuits when unchanged).
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        if (phase2SuccessCount <= 3) {
            boolean stencilOn = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            int glFboBefore = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
            // Check what FBO the composite render pass will use
            int stencilRef = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_STENCIL_REF);
            int stencilFunc = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_STENCIL_FUNC);
            SeamlessPortalsConstants.rlog(
                "[SEAMLESS DEBUG] Composite: stencil={} stencilRef={} stencilFunc={} glFbo={} mainRT={}x{} fboColorTex={} fboDepthTex={}",
                stencilOn, stencilRef, stencilFunc, glFboBefore,
                mainRT.width, mainRT.height,
                secondaryFbo.getColorTextureView() != null ? "valid" : "NULL",
                secondaryFbo.getDepthTextureView() != null ? "valid" : "NULL");
        }

        // Create render pass on main RT with depth-stencil — use the 6-arg overload
        // with an EXPLICIT full renderArea = (0,0, mainRT.width, mainRT.height).
        // CURTAIN FIX: the 5-arg overload auto-derives renderArea from
        // colorView.getWidth/Height(0) (CommandEncoder:62), which the GL backend
        // enforces as the SCISSOR for every draw (GlCommandEncoder:153,192). At
        // ultrawide the composite was being clipped to the bottom half of the screen
        // (the source overworld sky showing in the upper portal opening). Forcing the
        // renderArea to the RT's real field dimensions makes the full-screen composite
        // triangle cover the whole screen regardless of what the color view reports.
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "portal_composite",
                mainRT.getColorTextureView(),
                Optional.empty(),
                mainRT.getDepthTextureView(),
                OptionalDouble.empty(),
                new com.mojang.blaze3d.systems.RenderPass.RenderArea(0, 0, mainRT.width, mainRT.height)
        )) {
            // CURTAIN FIX (2026-06-27): use our TRACY_BLIT clone with an EXPLICIT
            // ALWAYS_PASS depth state instead of vanilla TRACY_BLIT. Vanilla
            // TRACY_BLIT has depthStencilState=empty; driven through createRenderPass
            // WITH a depth attachment (needed for the stencil) the GL backend applies
            // the reversed-Z GEQUAL default, which depth-gates the composite — the
            // upper portal opening (portal-plane depth ~0.85 near) FAILS GEQUAL vs the
            // ~0.5 full-screen-triangle depth, so the source sky showed through (the
            // blue curtain, proven by DIAG-PRE). ALWAYS_PASS writes the full opening.
            // (Still no blend — pipeline has no blend state and we glDisable(BLEND).)
            pass.setPipeline(PortalRenderTypes.portalCompositeBlit());
            RenderSystem.bindDefaultUniforms(pass);
            // Bind our FBO texture via the render pass — this is the correct way.
            // Raw GL glBindTexture does NOT affect render pass sampler bindings.
            pass.bindTexture("InSampler", secondaryFbo.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(
                    com.mojang.blaze3d.textures.FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0); // Full-screen triangle (vertexCount=3, instanceCount=1)
        }
        // Restore blend + depth state for the rest of the main-frame rendering.
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /**
     * Write fog data to a standalone GPU buffer, not the global fogRenderer.
     * Creates a fresh buffer each call to avoid "Buffer is not writable" errors
     * (the command encoder may still be using the previous buffer).
     *
     * The global fogRenderer uses a MappableRingBuffer whose currentBuffer() is
     * shared with the main renderer's terrainFog slice. Writing to it corrupts
     * the main world's fog → dark clipping artifacts across the entire world.
     */
    private static com.mojang.blaze3d.buffers.GpuBufferSlice writePortalFogBuffer(FogData fog) {
        // Build fog data into a ByteBuffer, then create a GPU buffer from it.
        // This avoids mapping an existing buffer (which may be in use by a render pass).
        // FOG_UBO_SIZE = 48 in MC (std140 padded: vec4(16) + 6*float(24) + 8 padding)
        // We must allocate exactly 48 bytes. Std140Builder writes 40 bytes of data;
        // remaining 8 bytes are std140 padding (zeros).
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(48)
            .order(java.nio.ByteOrder.nativeOrder());
        com.mojang.blaze3d.buffers.Std140Builder.intoBuffer(buf)
            .putVec4(fog.color)
            .putFloat(fog.environmentalStart)
            .putFloat(fog.environmentalEnd)
            .putFloat(fog.renderDistanceStart)
            .putFloat(fog.renderDistanceEnd)
            .putFloat(fog.skyEnd)
            .putFloat(fog.cloudEnd);
        // Include std140 padding — advance to full 48 bytes before flip
        buf.position(48);
        buf.flip();

        com.mojang.blaze3d.buffers.GpuBuffer gpuBuf = RenderSystem.getDevice().createBuffer(
            () -> "portal_fog", com.mojang.blaze3d.buffers.GpuBuffer.USAGE_UNIFORM, buf);
        return gpuBuf.slice(); // Full buffer slice
    }

    // ==================== Phase 1 Fallback (colored blocks) ====================

    private static void renderColoredBlocks(PortalInfo srcPortal, PortalLink link,
                                             Camera camera, ResourceKey<Level> destDim) {
        if (!RemoteChunkManager.hasDimensionData(destDim)) return;

        PortalInfo destPortal = link.getDestination();
        Vec3 playerPos = camera.position();
        BlockPos destOrigin = destPortal.getOrigin();
        Direction.Axis axis = srcPortal.getAxis();

        int offsetX = srcPortal.getOrigin().getX() - destOrigin.getX();
        int offsetY = srcPortal.getOrigin().getY() - destOrigin.getY();
        int offsetZ = srcPortal.getOrigin().getZ() - destOrigin.getZ();

        int depthSign;
        if (axis == Direction.Axis.X) {
            depthSign = (playerPos.z < srcPortal.getCenter().z) ? 1 : -1;
        } else {
            depthSign = (playerPos.x < srcPortal.getCenter().x) ? 1 : -1;
        }

        ByteBufferBuilder byteBuf = new ByteBufferBuilder(262144);
        BufferBuilder builder = new BufferBuilder(byteBuf, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (int d = -4; d <= 32; d++) {
            for (int w = -20; w < srcPortal.getWidth() + 20; w++) {
                for (int h = -20; h < srcPortal.getHeight() + 20; h++) {
                    int nx, ny, nz;
                    ny = destOrigin.getY() + h;
                    if (axis == Direction.Axis.X) {
                        nx = destOrigin.getX() + w;
                        nz = destOrigin.getZ() + (d * depthSign);
                    } else {
                        nx = destOrigin.getX() + (d * depthSign);
                        nz = destOrigin.getZ() + w;
                    }

                    BlockState state = RemoteChunkManager.getRemoteBlockState(destDim, new BlockPos(nx, ny, nz));
                    if (state == null || state.isAir()) continue;

                    float rx = (nx + offsetX) - (float) playerPos.x;
                    float ry = (ny + offsetY) - (float) playerPos.y;
                    float rz = (nz + offsetZ) - (float) playerPos.z;

                    int c = getColor(state);
                    int dk = darken(c, 0.7f);
                    int dkb = darken(c, 0.55f);

                    if (isAir(destDim, nx, ny+1, nz)) { builder.addVertex(rx,ry+1,rz).setColor(c); builder.addVertex(rx+1,ry+1,rz).setColor(c); builder.addVertex(rx+1,ry+1,rz+1).setColor(c); builder.addVertex(rx,ry+1,rz+1).setColor(c); }
                    if (isAir(destDim, nx, ny-1, nz)) { builder.addVertex(rx,ry,rz+1).setColor(dkb); builder.addVertex(rx+1,ry,rz+1).setColor(dkb); builder.addVertex(rx+1,ry,rz).setColor(dkb); builder.addVertex(rx,ry,rz).setColor(dkb); }
                    if (isAir(destDim, nx, ny, nz-1)) { builder.addVertex(rx+1,ry+1,rz).setColor(dk); builder.addVertex(rx,ry+1,rz).setColor(dk); builder.addVertex(rx,ry,rz).setColor(dk); builder.addVertex(rx+1,ry,rz).setColor(dk); }
                    if (isAir(destDim, nx, ny, nz+1)) { builder.addVertex(rx,ry+1,rz+1).setColor(dk); builder.addVertex(rx+1,ry+1,rz+1).setColor(dk); builder.addVertex(rx+1,ry,rz+1).setColor(dk); builder.addVertex(rx,ry,rz+1).setColor(dk); }
                    if (isAir(destDim, nx-1, ny, nz)) { builder.addVertex(rx,ry+1,rz).setColor(dk); builder.addVertex(rx,ry+1,rz+1).setColor(dk); builder.addVertex(rx,ry,rz+1).setColor(dk); builder.addVertex(rx,ry,rz).setColor(dk); }
                    if (isAir(destDim, nx+1, ny, nz)) { builder.addVertex(rx+1,ry+1,rz+1).setColor(dk); builder.addVertex(rx+1,ry+1,rz).setColor(dk); builder.addVertex(rx+1,ry,rz).setColor(dk); builder.addVertex(rx+1,ry,rz+1).setColor(dk); }
                }
            }
        }

        MeshData mesh = builder.build();
        if (mesh != null) {
            PortalRenderTypes.drawMesh(PortalRenderTypes.portalNoDepthColor(), mesh);
        } else {
            byteBuf.close();
        }
    }

    private static boolean isAir(ResourceKey<Level> dim, int x, int y, int z) {
        BlockState s = RemoteChunkManager.getRemoteBlockState(dim, new BlockPos(x, y, z));
        return s == null || s.isAir();
    }

    private static int darken(int c, float f) {
        int a = (c >> 24) & 0xFF;
        int r = (int)(((c >> 16) & 0xFF) * f);
        int g = (int)(((c >> 8) & 0xFF) * f);
        int b = (int)((c & 0xFF) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int getColor(BlockState s) {
        if (s.is(Blocks.NETHERRACK)) return 0xFF6B3030;
        if (s.is(Blocks.LAVA)) return 0xFFFF6600;
        if (s.is(Blocks.MAGMA_BLOCK)) return 0xFF8B3000;
        if (s.is(Blocks.GLOWSTONE)) return 0xFFFFCC66;
        if (s.is(Blocks.SOUL_SAND)) return 0xFF513A2A;
        if (s.is(Blocks.BASALT)) return 0xFF494949;
        if (s.is(Blocks.BLACKSTONE)) return 0xFF2A2A2A;
        if (s.is(Blocks.BEDROCK)) return 0xFF333333;
        if (s.is(Blocks.NETHER_BRICKS)) return 0xFF2D1515;
        if (s.is(Blocks.GRAVEL)) return 0xFF8B7D72;
        if (s.is(Blocks.OBSIDIAN)) return 0xFF0D0015;
        if (s.is(Blocks.FIRE)) return 0xFFFF4400;
        if (s.is(Blocks.STONE)) return 0xFF7F7F7F;
        if (s.is(Blocks.DIRT)) return 0xFF8B6843;
        if (s.is(Blocks.GRASS_BLOCK)) return 0xFF5D8C32;
        if (s.is(Blocks.SAND)) return 0xFFDBCD82;
        return 0xFF5A2828;
    }

    // Phase 2f Option-1 experiment removed (2026-04-24). Research showed
    // IP does not render a "slice" into the main FBO. The illusion of
    // two worlds split at the portal plane emerges from the stencil +
    // secondary-FBO render PLUS an inner clip plane active only inside
    // the nested dest render. The full-screen `doSliceRender` method
    // that used to live here painted dest-sky over source in half the
    // screen when the eye was close to a portal — IP never does this.
    // Inner-clip wiring now lives inside `doFboRender` itself.
}
