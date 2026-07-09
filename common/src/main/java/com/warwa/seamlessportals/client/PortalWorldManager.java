package com.warwa.seamlessportals.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import com.warwa.seamlessportals.mixin.client.LevelExtractorAccessor;
import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages secondary LevelRenderers and ClientLevels for portal rendering.
 *
 * Following the Immersive Portals architecture:
 * - Each remote dimension gets its own LevelRenderer + ClientLevel
 * - LevelRenderer has its own SectionRenderDispatcher (chunk mesh compilation)
 * - RenderBuffers is SHARED (sequential access via context switch)
 * - FeatureRenderDispatcher has SEPARATE instance (has mutable per-frame state)
 * - EntityRenderDispatcher/BlockEntityRenderDispatcher are SHARED
 *
 * Verified constructor signatures from MC 26.1.2 source:
 * - LevelRenderer(Minecraft, EntityRenderDispatcher, BlockEntityRenderDispatcher,
 *                  RenderBuffers, GameRenderState, FeatureRenderDispatcher)
 * - FeatureRenderDispatcher(SubmitNodeStorage, ModelManager, BufferSource,
 *                            AtlasManager, OutlineBufferSource, BufferSource, Font, GameRenderState)
 */
public class PortalWorldManager {

    /**
     * The single source of truth for per-dimension renderers and levels.
     *
     * <p>Modeled after IP's {@code ClientWorldLoader.worldRendererMap /
     * clientWorldMap}: one entry per dimension the client has touched. The
     * map contains both the vanilla {@code mc.levelRenderer} (registered by
     * {@link #initializeIfNeeded()} once the game is up) and any secondary
     * renderers we created for portal viewing. There is NO separate
     * "dormant" bucket — IP doesn't have one either. Whichever renderer is
     * currently referenced by {@code mc.levelRenderer} is the active
     * primary; everything else is ready to be used for portal viewing.
     *
     * <p>Design trade-off with this scheme: the renderer registered for the
     * starting dim shares {@code mc.renderBuffers} with {@code GameRenderer}.
     * That is safe when it's the active primary. When it's demoted and we
     * use it as a portal-view secondary while another renderer is the
     * primary, the shared buffers would collide with the active primary's
     * use of them during {@code AFTER_TRANSLUCENT_TERRAIN} — "Buffer
     * source must not be empty". This is the unsolved part of Subphase 1;
     * Subphase 2 addresses it by moving the render hook outside the active
     * primary's render pass.
     */
    private static final Map<ResourceKey<Level>, LevelRenderer> renderers = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, ClientLevel> levels = new ConcurrentHashMap<>();
    /**
     * Per-dimension {@link LevelExtractor} (26.2 render split). Owns the
     * dimension's {@code setLevel} / dirty-marking / {@code extract} duties that
     * used to live on {@link LevelRenderer}, plus the {@code SectionUpdateTracker}.
     * One entry per secondary renderer, created alongside it in
     * {@link #createRenderer}.
     */
    private static final Map<ResourceKey<Level>, LevelExtractor> extractors = new ConcurrentHashMap<>();

    /**
     * Per-destination {@link ParticleEngine}. A SEPARATE engine per cached dest
     * dim so the dest particle extract pulls only that dim's particles and can
     * never corrupt the source world's (each engine owns its own particle-group
     * map). Holds the destination's ambient particles (flame, lava, nether
     * portal, fog), spawned by {@link #tickCachedParticles} via the dest level's
     * {@code animateTick} and rendered into the portal FBO when
     * {@link com.warwa.seamlessportals.render.PortalContextSwitch#withSwitchedWorld}
     * swaps {@code mc.particleEngine} to it. Created lazily
     * ({@link #getOrCreateParticleEngine}); removed alongside the renderer/level
     * in {@link #removeRenderer}/{@link #promoteToMain}/{@link #cleanup}.
     */
    private static final Map<ResourceKey<Level>, ParticleEngine> particleEngines = new ConcurrentHashMap<>();

    /**
     * True only while {@link #tickCachedParticles} is spawning/ticking a cached
     * destination dimension's particles. Signals
     * {@link com.warwa.seamlessportals.mixin.client.ClientLevelMixin} to bypass
     * vanilla's main-camera distance gate in {@code ClientLevel.doAddParticle}:
     * at tick time the main camera is the SOURCE world's, but these particles
     * spawn at DEST-world coordinates (always >32 blocks away / a different dim),
     * so the gate would cull every non-override ambient particle. The
     * {@code animateTick} ±32 radius already bounds them around the dest view.
     */
    public static boolean spawningDestParticles = false;

    /** Temporary diagnostic throttle — first N dest-particle ticks logged. */
    private static int particleDiagCount = 0;

    /**
     * Phase 1 (IP "live window") master switch: tick every RESIDENT remote
     * ClientLevel each client tick like the active world, so the destination is
     * ALIVE through the portal — entities walk, fluids flow, fire spreads, block
     * entities run, light updates — instead of a frozen snapshot. Flip false to
     * revert to the prior per-entity mirror tick ({@link #tickCachedEntities}).
     * Mirrors {@code IPGlobal.isClientRemoteTickingEnabled}.
     */
    public static boolean isClientRemoteTickingEnabled = true;

    /**
     * True only while {@link #tickRemoteWorlds} is ticking a remote (non-active)
     * level — {@code mc.level} + {@code mc.particleEngine} are temporarily
     * swapped to it. Lets other client code recognise a remote-world tick.
     * Mirrors {@code ClientWorldLoader.isClientRemoteTicking}.
     */
    public static boolean isClientRemoteTicking = false;

    /**
     * Renderers that were just promoted and need a synchronous
     * {@link net.minecraft.client.renderer.SectionOcclusionGraph} prime on
     * their next {@code cullTerrain} call, to avoid the 1-2 blank-terrain
     * frames at the start of a portal crossing.
     *
     * <p>Consumed by
     * {@link com.warwa.seamlessportals.mixin.client.LevelRendererCullTerrainMixin}
     * on the first post-promote frame. Weak-referenced so a renderer that
     * gets closed without its prime consumed doesn't leak.
     */
    private static final java.util.Set<LevelRenderer> pendingSyncPrime =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private static boolean initialized = false;

    /** Promotion outcome: the renderer + level to install as Minecraft's primary. */
    public record Promotion(LevelRenderer renderer, ClientLevel level) {}

    /**
     * Populate the renderer map with the vanilla {@code mc.levelRenderer} +
     * {@code mc.level} on first access. Mirrors IP's
     * {@code ClientWorldLoader.initializeIfNeeded()}.
     *
     * <p>Must be called before any {@link #getOrCreateRenderer(ResourceKey)}
     * or {@link #promoteToMain(ResourceKey, LevelRenderState)} lookup, so
     * that those lookups see the starting dim's renderer as an entry rather
     * than falling through to {@link #createRenderer(ResourceKey)} (which
     * would fabricate a duplicate).
     *
     * <p>Idempotent — subsequent calls are no-ops.
     */
    public static void initializeIfNeeded() {
        if (initialized) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.levelRenderer == null) {
            // Game not fully loaded yet — we'll initialize on the next call.
            return;
        }
        ResourceKey<Level> startingDim = mc.level.dimension();
        renderers.putIfAbsent(startingDim, mc.levelRenderer);
        levels.putIfAbsent(startingDim, mc.level);
        initialized = true;
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS PHASE2] Initialized — vanilla mc.levelRenderer registered for {}",
            startingDim.identifier());
    }

    /**
     * Get or create a LevelRenderer + ClientLevel for the given dimension.
     *
     * <p>Lookup: existing entry in {@link #renderers} → returned directly
     * (covers both the initial vanilla renderer and any secondary we've
     * previously created or re-registered via {@link #demoteFromMain}).
     * Otherwise create a fresh secondary via {@link #createRenderer}.
     *
     * <p>The "reuse if exists" path is what preserves compiled meshes
     * across a full round trip — the renderer stays in the map while its
     * dim is not currently primary, so portal views from the other side
     * keep using the same renderer with its accumulated mesh cache.
     */
    public static LevelRenderer getOrCreateRenderer(ResourceKey<Level> dimension) {
        initializeIfNeeded();
        LevelRenderer existing = renderers.get(dimension);
        if (existing != null) {
            return existing;
        }
        return renderers.computeIfAbsent(dimension, PortalWorldManager::createRenderer);
    }

    public static ClientLevel getLevel(ResourceKey<Level> dimension) {
        return levels.get(dimension);
    }

    /**
     * 26.2 render split: the per-dimension {@link LevelExtractor} that owns the
     * {@code setLevel} / dirty-marking / {@code extract} duties that used to live
     * on {@link LevelRenderer}, plus the {@code SectionUpdateTracker}. Returns
     * {@code null} for a dim with no secondary renderer (e.g. the starting dim's
     * vanilla {@code mc.levelRenderer}, whose extractor is {@code mc.levelExtractor}).
     */
    public static LevelExtractor getExtractor(ResourceKey<Level> dimension) {
        return extractors.get(dimension);
    }

    public static boolean hasRenderer(ResourceKey<Level> dimension) {
        return renderers.containsKey(dimension);
    }

    // ---- IP-faithful secondary-world scoping (qouteall ChunkVisibility.getNearbyPortals) ----
    //
    // A secondary dimension is only maintained — ticked ({@link #tickRemoteWorlds}),
    // compile-pumped ({@link #advanceCompilePipelines}), and kept resident
    // ({@link #evictUnboundedStores}) — while a portal that links into it is NEAR the
    // player in the active dim. Walk away from every portal and the dest stops being
    // maintained and its store is released, instead of a ~render-distance-deep second
    // world being fully ticked + swept every client tick forever (the "laggy even
    // 5000 blocks away" leak). A grace window keeps a just-left dim alive briefly so a
    // quick glance away / return doesn't thrash evict→re-stream.

    /** Per-dim: dest-region center (chunk-origin) of the nearest in-range portal that links here. */
    private static final Map<ResourceKey<Level>, net.minecraft.core.BlockPos> liveCentersByDim =
        new ConcurrentHashMap<>();
    /** Per-dim: {@link System#nanoTime()} a portal was last near enough to keep this dest alive. */
    private static final Map<ResourceKey<Level>, Long> lastActiveNanosByDim = new ConcurrentHashMap<>();
    /** Per-dim: IP-graduated, config-capped scope radius (chunks) — how far this dest stays resident. */
    private static final Map<ResourceKey<Level>, Integer> liveRadiusByDim = new ConcurrentHashMap<>();
    /** Game-tick stamp of the last {@link #refreshDestScopes()} (recompute at most once/tick). */
    private static long destScopeStampTick = Long.MIN_VALUE;
    /**
     * Grace after a dest leaves portal range before it is paused + released. Long
     * enough that a quick look-away / walk-back doesn't thrash, short enough that the
     * far world stops costing once you genuinely leave. Mirrors the spirit of the
     * server residency ticket timeout (200t).
     */
    private static final long DEST_SCOPE_GRACE_NANOS = 5_000_000_000L; // 5 s

    /**
     * Recompute (at most once per game tick) which secondary dims have a portal near
     * the player, recording each dim's nearest dest-region center + the time it was
     * last seen near. Uses the SAME range + filters the render path uses to pick
     * portals ({@code StencilPortalRenderer.resolveRenderTargets}), so a dim that is
     * about to be rendered is never paused/evicted out from under the renderer.
     */
    /**
     * Speculative pre-warm scopes (dim → {BlockPos center, Long untilNanos}), fed by
     * {@code SpeculativePrewarmScopePayload} (server: an unlit valid frame near the player).
     * Merged into the live dest scopes by {@link #refreshDestScopes} so the region stays
     * resident, ticks, and gets its meshes pre-compiled — without any portal link existing.
     * Expires ~6s after the last payload (server sends every ~2s while the player is near).
     */
    private static final Map<ResourceKey<Level>, Object[]> speculativeScopes = new ConcurrentHashMap<>();

    /** Client receive: mark {@code dim} scope-live around {@code center} + seed its renderer. */
    public static void addSpeculativeScope(String dimId, net.minecraft.core.BlockPos center) {
        try {
            ResourceKey<Level> dim = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.Identifier.parse(dimId));
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.dimension().equals(dim)) return; // active dim: no-op
            speculativeScopes.put(dim, new Object[]{ center, System.nanoTime() + 6_000_000_000L });

            // Ensure the cached level + renderer exist and are CENTERED on the expected dest so
            // arriving chunks land in-grid and the compile pump pre-builds meshes there. (The
            // same seeding the redirected-chunk/portal-render paths do.)
            LevelRenderer renderer = getOrCreateRenderer(dim);
            ClientLevel level = getLevel(dim);
            if (renderer != null && level != null) {
                net.minecraft.client.renderer.ViewArea va =
                    ((com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin) renderer)
                        .seamlessportals$getViewArea();
                net.minecraft.core.SectionPos sp = net.minecraft.core.SectionPos.of(center);
                if (va != null) va.repositionCamera(sp);
                level.getChunkSource().updateViewCenter(sp.x(), sp.z());
            }
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS PREWARM] addSpeculativeScope failed for {}: {}", dimId, e.toString());
        }
    }

    private static void refreshDestScopes() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel active = mc.level;
        if (active == null || mc.player == null) return;
        long stamp = active.getGameTime();
        if (stamp == destScopeStampTick) return; // already computed this tick
        destScopeStampTick = stamp;

        long now = System.nanoTime();
        // Keep the ACTIVE dim continuously live so the dim you JUST LEFT stays warm
        // for the full grace window after a crossing demotes it to a secondary —
        // preserves the seamless return (no blank look-back while the return portal's
        // link resolves client-side). The active dim is never evicted/paused (those
        // paths skip it), so the recorded center only matters once it is demoted.
        lastActiveNanosByDim.put(active.dimension(), now);
        liveCentersByDim.put(active.dimension(), mc.player.blockPosition());

        try {
            com.warwa.seamlessportals.portal.PortalManager pm =
                com.warwa.seamlessportals.portal.PortalManager.getClientInstance();
            com.warwa.seamlessportals.portal.PortalTracker tracker = pm.getTracker(active.dimension());
            if (tracker == null) return;
            net.minecraft.core.BlockPos playerPos = mc.player.blockPosition();
            double range = com.warwa.seamlessportals.config.SeamlessPortalsConfig.get()
                .getPortalRenderDistance() * 16.0;
            java.util.List<com.warwa.seamlessportals.portal.PortalInfo> near =
                new java.util.ArrayList<>(tracker.getPortalsInRange(playerPos, range));
            // Nearest-first so the closest portal's dest wins as the load/evict center.
            net.minecraft.world.phys.Vec3 pv = mc.player.position();
            near.sort(java.util.Comparator.comparingDouble(p -> p.getCenter().distanceToSqr(pv)));
            for (com.warwa.seamlessportals.portal.PortalInfo src : near) {
                if (!com.warwa.seamlessportals.config.SeamlessPortalsConfig
                        .shouldRenderThrough(src.getType())) continue;
                java.util.Optional<com.warwa.seamlessportals.portal.PortalLink> linkOpt =
                    pm.getLinkForPortal(src.getPortalId());
                if (linkOpt.isEmpty()) continue;
                com.warwa.seamlessportals.portal.PortalInfo dest = linkOpt.get().getDestination();
                ResourceKey<Level> destDim = dest.getDimension();
                if (destDim.equals(active.dimension())) continue; // same-dim: no secondary world
                if (liveCentersByDim.containsKey(destDim)
                        && lastActiveNanosByDim.getOrDefault(destDim, 0L) == now) {
                    continue; // already recorded a nearer portal for this dim this tick
                }
                liveCentersByDim.put(destDim, dest.getOrigin());
                lastActiveNanosByDim.put(destDim, now);
                // IP-graduated, config-capped scope radius (mirrors the SERVER's
                // PortalChunkTracker renderDist + IP ChunkVisibility.getDirectLoadingDistance
                // / getCappedLoadingDistance): full render distance only within 5 blocks of
                // the portal, 2/3 within 15, else 1/3 — capped by portalRenderDistance. This
                // is what makes the just-left dimension SHRINK as the player walks away
                // (it was pinned at renderDistance+16, so its full RD-deep chunk set was
                // ticked/lit forever — the 207ms post-teleport pollLight).
                double distBlocks = Math.sqrt(src.getCenter().distanceToSqr(pv));
                int rd = mc.options.getEffectiveRenderDistance();
                com.warwa.seamlessportals.config.SeamlessPortalsConfig cfg =
                    com.warwa.seamlessportals.config.SeamlessPortalsConfig.get();
                int cap = cfg.getPortalRenderDistance();
                // auto → IP-graduated by portal distance; fixed → full configured depth always.
                int target = cfg.isAutoRenderDistance()
                    ? (distBlocks < 5.0 ? rd : (distBlocks < 15.0 ? (rd * 2) / 3 : rd / 3))
                    : cap;
                liveRadiusByDim.put(destDim, Math.max(1, Math.min(target, cap)));
            }
        } catch (Throwable t) {
            // Best-effort: on any hiccup leave the scope state untouched (keeps dims
            // alive — the safe default that never evicts a dim that might be needed).
        }

        // Merge SPECULATIVE pre-warm scopes (unlit valid frames near the player): treated like a
        // portal-linked dim — eviction spares the region, tickRemoteWorlds ticks it, and the
        // compile pump pre-builds meshes around the expected dest. Portal-derived scopes (set
        // above this tick) take precedence; expired entries drop out.
        if (!speculativeScopes.isEmpty()) {
            for (Map.Entry<ResourceKey<Level>, Object[]> e : speculativeScopes.entrySet()) {
                ResourceKey<Level> dim = e.getKey();
                if ((Long) e.getValue()[1] < System.nanoTime()) {
                    speculativeScopes.remove(dim);
                    continue;
                }
                if (dim.equals(active.dimension())) continue;
                if (lastActiveNanosByDim.getOrDefault(dim, 0L) == now) continue; // portal scope wins
                liveCentersByDim.put(dim, (net.minecraft.core.BlockPos) e.getValue()[0]);
                lastActiveNanosByDim.put(dim, now);
                liveRadiusByDim.put(dim, Math.min(8,
                    com.warwa.seamlessportals.config.SeamlessPortalsConfig.get().getPortalRenderDistance()));
            }
        }
    }

    /** Has a portal linked into {@code dim} within the grace window? (kept ticked + resident) */
    public static boolean isDestScopeLive(ResourceKey<Level> dim) {
        Long t = lastActiveNanosByDim.get(dim);
        return t != null && (System.nanoTime() - t) < DEST_SCOPE_GRACE_NANOS;
    }

    /** Nearest in-range portal's dest-region center for {@code dim}, or null if none recorded. */
    public static net.minecraft.core.BlockPos getDestScopeCenter(ResourceKey<Level> dim) {
        return liveCentersByDim.get(dim);
    }

    /**
     * IP-graduated, config-capped resident radius (chunks) for {@code dim}. Defaults to the
     * config cap (NOT renderDistance+16) for the brief window right after a crossing before
     * {@link #refreshDestScopes()} recomputes — so the just-left dimension is never pinned
     * at the full render-distance set.
     */
    public static int getDestScopeRadius(ResourceKey<Level> dim) {
        Integer r = liveRadiusByDim.get(dim);
        if (r != null) return r;
        return com.warwa.seamlessportals.config.SeamlessPortalsConfig.get().getPortalRenderDistance();
    }

    /**
     * IP-faithful per-tick store bounding. For every INACTIVE secondary level using the
     * unbounded {@link SeamlessClientChunkMap}:
     * <ul>
     *   <li>if a portal is near the player linking into this dim (within the grace
     *       window) → keep its live region resident, recentered on the nearest such
     *       portal's dest origin (NOT the frozen FBO view-center);</li>
     *   <li>otherwise → release the whole store (the dest is no longer near any portal;
     *       a return crossing re-streams it, since the server prunes its sent-chunk
     *       record to the live working set).</li>
     * </ul>
     * The radius is the player render distance + margin, so a live dest region is never
     * dropped — only far stragglers. No-op when the flag is off or no unbounded store exists.
     */
    public static void evictUnboundedStores() {
        if (levels.isEmpty()
            || !com.warwa.seamlessportals.config.SeamlessPortalsConfig.get().isUnboundedClientChunkStore()) {
            return;
        }
        refreshDestScopes();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel active = mc.level;
        for (Map.Entry<ResourceKey<Level>, ClientLevel> e : levels.entrySet()) {
            ClientLevel level = e.getValue();
            if (level == null || level == active) continue; // never evict the active world here
            if (!(level.getChunkSource() instanceof SeamlessClientChunkMap store)) continue;
            ResourceKey<Level> dim = e.getKey();
            if (isDestScopeLive(dim)) {
                // Reverted to a fixed render-distance+16 bound: the IP-graduated radius
                // (getDestScopeRadius) jumps at the 5/15-block bands as the player moves,
                // which evicted-then-re-streamed a ring each band crossing (drainChunks
                // spiked ~195ms — the "stutter right after lighting"). A churn-free shrink
                // needs IP's delay-unload hysteresis (a few generations before dropping),
                // not an instantaneous graduated radius. Until then, keep the stable bound.
                int radius = mc.options.getEffectiveRenderDistance() + 16;
                net.minecraft.core.BlockPos center = getDestScopeCenter(dim);
                if (center != null) {
                    store.seamlessportals$evictAround(center.getX() >> 4, center.getZ() >> 4, radius);
                } else {
                    store.seamlessportals$evictBeyond(radius); // fall back to tracked view-center
                }
            } else {
                // No portal near this dim past the grace window → release it entirely.
                store.seamlessportals$evictAll();
            }
        }
    }

    /**
     * Phase 2/5 safety gate: is the dest level DENSE enough (every chunk in a
     * small radius around the view center is loaded) to safely drive the native
     * occlusion-graph render? The SectionOcclusionGraph BFS / sog.update spins /
     * hangs on a SPARSE, still-streaming secondary (e.g. a freshly-lit portal
     * whose nether side has a handful of chunks) — which is exactly why the
     * captured-frustum bypass + manual scan exist. We only switch a dest to the
     * native (occlusion-culled) render path when this returns true; otherwise the
     * manual scan handles it. The dimension the player just LEFT is always dense
     * (it was the active world), so its heavy mirror render gets the native path.
     */
    public static boolean isDestResident(ClientLevel level, int centerSecX, int centerSecZ, int radius) {
        if (level == null) return false;
        net.minecraft.client.multiplayer.ClientChunkCache cache = level.getChunkSource();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!cache.hasChunk(centerSecX + dx, centerSecZ + dz)) return false;
            }
        }
        return true;
    }

    /** Per-dim: last observed loaded-chunk count + the time it last CHANGED. */
    private static final Map<ResourceKey<Level>, Integer> lastLoadedCount = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Long> lastCountChangeNanos = new ConcurrentHashMap<>();

    /**
     * Phase 2 safety gate: is {@code level}'s loaded-chunk count UNCHANGED for at
     * least {@code stableNanos}? The native occlusion-culled render warms (and
     * displays from) the engine's SectionOcclusionGraph, whose {@code sog.update}
     * propagation CHURNS the render thread while chunks are still streaming in (the
     * freeze). So it must run ONLY on a settled dest.
     *
     * <p>Count-based (not last-apply-time): a freshly-lit portal's nether is still
     * GROWING its loaded count → unstable; the dimension you just left (already
     * fully loaded) keeps a flat count even though the redirect feed RE-sends its
     * already-resident chunks on it becoming a dest — so it settles quickly and
     * earns the warm native path. This is what makes the post-teleport graph warm
     * for the new view (no blank flash).
     *
     * <p>Called every portal-view frame, so it doubles as the per-dim sampler.
     */
    public static boolean isDestStable(ClientLevel level, long stableNanos) {
        if (level == null) return false;
        ResourceKey<Level> dim = level.dimension();
        int count = level.getChunkSource().getLoadedChunksCount();
        Integer prev = lastLoadedCount.get(dim);
        long now = System.nanoTime();
        if (prev == null || prev != count) {
            lastLoadedCount.put(dim, count);
            lastCountChangeNanos.put(dim, now);
            return false;
        }
        Long changed = lastCountChangeNanos.get(dim);
        return changed != null && (now - changed) > stableNanos;
    }

    /**
     * The destination dimension's own {@link ParticleEngine}, or {@code null}
     * if none has been created yet (lazily created by
     * {@link #getOrCreateParticleEngine} / {@link #tickCachedParticles}).
     */
    public static ParticleEngine getParticleEngine(ResourceKey<Level> dimension) {
        return particleEngines.get(dimension);
    }

    /**
     * Get or lazily create the per-destination {@link ParticleEngine} for
     * {@code level}, bound to that level. Reuses the global engine's shared
     * {@link net.minecraft.client.particle.ParticleResources} (read-only sprite
     * + provider data), so no separate resource reload is needed. Particles
     * spawned via {@code level.addParticle} land here whenever
     * {@code mc.particleEngine} is swapped to this engine (the dest render and
     * {@link #tickCachedParticles}). Returns {@code null} only if there is no
     * global engine to source resources from.
     */
    public static ParticleEngine getOrCreateParticleEngine(ClientLevel level) {
        if (level == null) return null;
        return particleEngines.computeIfAbsent(level.dimension(), k -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.particleEngine == null) return null;
            net.minecraft.client.particle.ParticleResources resources =
                ((com.warwa.seamlessportals.mixin.client.ParticleEngineAccessorMixin) mc.particleEngine)
                    .seamlessportals$getResourceManager();
            return new ParticleEngine(level, resources);
        });
    }

    /**
     * 26.2: schedule an async chunk-section compile iff the section is dirty in the
     * extractor's SectionUpdateTracker. Replaces the old
     * {@code if (section.isDirty()) { section.rebuildSectionAsync(cache); section.setNotDirty(); }}.
     * Returns true if a compile was scheduled.
     */
    public static boolean scheduleCompileIfDirty(
            net.minecraft.client.renderer.extract.LevelExtractor extractor,
            net.minecraft.client.multiplayer.ClientLevel level,
            net.minecraft.client.renderer.chunk.RenderRegionCache cache,
            net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection section) {
        if (extractor == null) return false;
        net.minecraft.client.SectionUpdateTracker sut = extractor.sectionUpdateTracker;
        if (sut == null) return false;
        net.minecraft.client.SectionUpdateTracker.SectionDirtyState ds =
            sut.getDirtyState(section.getSectionNode());
        if (ds == null || !ds.isDirty()) return false;
        section.compileAsync(cache.createRegion(level, section.getSectionNode()));
        ds.setNotDirty();
        return true;
    }

    /**
     * Create a secondary LevelRenderer for a dimension.
     * Follows IP's ClientWorldLoader.createSecondaryClientWorld() pattern.
     */
    private static LevelRenderer createRenderer(ResourceKey<Level> dimension) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) {
            SeamlessPortalsConstants.LOGGER.error("[SEAMLESS PHASE2] Cannot create renderer - no active game");
            return null;
        }

        SeamlessPortalsConstants.rlog("[SEAMLESS PHASE2] Creating secondary renderer for {}", dimension.identifier());

        try {
            GameRenderState gameRenderState = mc.gameRenderer.gameRenderState();

            // Create SEPARATE RenderBuffers for the secondary renderer.
            // The main renderer's buffers are in use during AFTER_TRANSLUCENT_TERRAIN
            // (when our portal rendering runs). Sharing them causes
            // "Buffer source must not be empty" crashes when the 1:1 camera
            // position triggers entity rendering.
            RenderBuffers destRenderBuffers = new RenderBuffers(4);

            // Create SEPARATE FeatureRenderDispatcher (has mutable per-frame state).
            // 26.2: the old 8-arg buffer-source ctor is gone (MultiBufferSource
            // removed). The dispatcher now takes its RenderBuffers (it pulls the
            // shared StagedVertexBuffer from it) + model/atlas/font/state.
            FeatureRenderDispatcher destFeatureDispatcher = new FeatureRenderDispatcher(
                destRenderBuffers,
                mc.getModelManager(),
                mc.getAtlasManager(),
                mc.font,
                gameRenderState
            );

            // Create secondary LevelRenderer. 26.2's ctor no longer takes
            // RenderBuffers/FeatureRenderDispatcher explicitly — it derives them
            // from the passed GameRenderer (the MAIN ones). We construct it, then
            // override those two now-final fields with our isolated instances
            // below, preserving the per-secondary isolation the old ctor gave us.
            int rtWidth = mc.gameRenderer.mainRenderTarget().width;
            int rtHeight = mc.gameRenderer.mainRenderTarget().height;
            LevelRenderer destRenderer = new LevelRenderer(
                mc.getEntityRenderDispatcher(),
                mc.getBlockEntityRenderDispatcher(),
                mc.getModelManager(),
                mc.getTextureManager(),
                mc.getAtlasManager(),
                mc.getShaderManager(),
                mc.gameRenderer,
                rtWidth,
                rtHeight
            );

            LevelRendererAccessorMixin destRendererAccess =
                (LevelRendererAccessorMixin) destRenderer;
            // Give the secondary renderer its OWN LevelRenderState so extraction
            // doesn't corrupt the main renderer's shared state, and override to
            // the isolated buffers + dispatcher (see ctor note above).
            LevelRenderState destState = new LevelRenderState();
            destRendererAccess.seamlessportals$setLevelRenderState(destState);
            destRendererAccess.seamlessportals$setRenderBuffers(destRenderBuffers);
            destRendererAccess.seamlessportals$setFeatureRenderDispatcher(destFeatureDispatcher);

            // 26.2 render split: setLevel / dirty / extract moved off
            // LevelRenderer onto a LevelExtractor. Each secondary gets its own
            // extractor bound to its isolated LevelRenderState + renderer.
            LevelExtractor destExtractor =
                new LevelExtractor(mc, destState, destRenderer);

            // Subphase 1 (2026-04-17): unified renderer map — no separate
            // dormantLevels to reuse from. Create a fresh ClientLevel. If
            // this dim was previously visited, its renderer+level pair is
            // already in {@link #renderers}/{@link #levels}, so we would
            // have returned it above in getOrCreateRenderer without ever
            // reaching here. Subphase 2 will reduce the "fresh level has
            // no chunks" problem by swapping the render hook so the
            // starting dim's renderer can also be reused as a secondary.
            Holder<DimensionType> dimensionType = mc.level.registryAccess()
                .lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(getDimensionTypeKey(dimension));

            ClientLevel.ClientLevelData levelData = new ClientLevel.ClientLevelData(
                Difficulty.NORMAL, false, false
            );

            // ClientLevel now takes the LevelExtractor (was LevelRenderer in 26.1.2).
            // The secondary level's storage radius = the configured dest loading cap
            // (IP's indirectLoadingRadiusCap, default 8, clamp 1..32). This is the
            // HARD limit on how deep the dest can be held + meshed as a portal view:
            // the ClientChunkCache storage is sized here, so chunks beyond it are
            // dropped (and reload on crossing). Reading the config makes the whole
            // chain — residency, feed, mesh pump, draw, AND this storage — scale
            // together when you raise the cap.
            int destViewRadius = com.warwa.seamlessportals.config.SeamlessPortalsConfig.get().getPortalRenderDistance();
            ClientLevel destLevel = new ClientLevel(
                mc.getConnection(),
                levelData,
                dimension,
                dimensionType,
                destViewRadius,  // render distance (storage radius) = config loading cap
                destViewRadius,  // simulation distance
                destExtractor,
                false,
                0L,
                mc.level.getSeaLevel()
            );

            // T3 (experimental, flag-gated): give this secondary level an UNBOUNDED chunk
            // store so dest chunks are never dropped at the radius cap → no far-ring reload
            // (re-decode + re-mesh) on crossing. Swapped BEFORE setLevel (which only reads
            // this.level, not chunkSource). Off by default; stock bounded store when off.
            if (com.warwa.seamlessportals.config.SeamlessPortalsConfig.get().isUnboundedClientChunkStore()) {
                ((com.warwa.seamlessportals.mixin.client.ClientLevelChunkSourceAccessor) (Object) destLevel)
                    .seamlessportals$setChunkSource(new SeamlessClientChunkMap(destLevel, destViewRadius));
                // One-time per dim (NOT per frame) — confirms T3 engaged for this run.
                com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS T3] Unbounded client chunk store installed for {}",
                    dimension.identifier());
            }

            // Connect extractor to level (creates chunk infrastructure via
            // allChanged() -> levelRenderer.invalidateCompiledGeometry()).
            destExtractor.setLevel(destLevel);

            // Initialize sky renderer + entity outline target + resources.
            // 26.2: onResourceManagerReload moved to LevelExtractor (it
            // implements ResourceManagerReloadListener). Without it, extract()
            // crashes on null sky/resource state.
            destExtractor.onResourceManagerReload(mc.getResourceManager());

            levels.put(dimension, destLevel);
            extractors.put(dimension, destExtractor);

            SeamlessPortalsConstants.rlog(
                "[SEAMLESS PHASE2] Secondary renderer created for {} (sections={})",
                dimension.identifier(), destLevel.getSectionsCount()
            );

            return destRenderer;

        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.error("[SEAMLESS PHASE2] Failed to create secondary renderer", e);
            return null;
        }
    }

    private static ResourceKey<DimensionType> getDimensionTypeKey(ResourceKey<Level> dimension) {
        if (dimension == Level.NETHER) {
            return ResourceKey.create(Registries.DIMENSION_TYPE,
                Identifier.withDefaultNamespace("the_nether"));
        }
        if (dimension == Level.END) {
            return ResourceKey.create(Registries.DIMENSION_TYPE,
                Identifier.withDefaultNamespace("the_end"));
        }
        return ResourceKey.create(Registries.DIMENSION_TYPE,
            Identifier.withDefaultNamespace("overworld"));
    }

    /**
     * Queue of dimensions whose pre-loaded chunks still need to be fed into
     * the secondary ClientLevel. Populated by {@link #feedExistingChunks} and
     * drained a small batch at a time in {@link #drainPendingFeeds}.
     *
     * Previously {@code feedExistingChunks} ran synchronously and processed
     * all ~289 chunks in one call, freezing the render thread for ~3 seconds
     * right after every teleport when the "dimension we just left" secondary
     * renderer was created. Splitting it into (queue → drain N/tick) keeps
     * each frame responsive.
     */
    private record PendingFeed(ResourceKey<Level> dim, net.minecraft.world.level.ChunkPos pos) {}
    private static final java.util.concurrent.ConcurrentLinkedQueue<PendingFeed> pendingFeeds =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** Dimensions with at least one feed still pending — used to skip the final "fed all" log until the queue drains. */
    private static final java.util.Set<ResourceKey<Level>> feedingDims =
        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** How many chunks to feed per drain call. Each chunk ≈ 10ms → 6 → ~60ms worst case per tick. */
    private static final int FEEDS_PER_DRAIN = 6;

    /** Temporary diagnostic throttle for the per-tick chunk-feed cost. */
    private static int feedDiagCount = 0;

    /**
     * Enqueue a SMALL radius of RemoteChunkManager chunks around each portal
     * in the given dimension. The chunks are fed into the secondary ClientLevel
     * one batch at a time by {@link #drainPendingFeeds}.
     *
     * Previously this queued ALL ~289 pre-loaded chunks, and even with the
     * batched drain the sheer volume created a backlog of chunk inserts +
     * mesh rebuilds that caused near-total FPS collapse (0-1 fps) for
     * multiple seconds. The user only sees the destination through the
     * portal opening, so a tight radius around each portal covers everything
     * visible. More distant chunks are still held in RemoteChunkManager and
     * can be lazily queued later if we ever need them.
     */
    // 2026-06-27: raised 3 → 8 so the destination dim becomes FULLY resident
    // while the player is near a portal (the cached level's own render distance
    // is 8 — see createRenderer — so this fills it), instead of only a 49-chunk
    // patch around the portal mouth. The feed is batched + time-budgeted
    // (drainPendingFeeds: ≤8ms/tick), so the larger set just drains over MORE
    // ticks at the SAME per-tick cost — the approach stays smooth, and a crossing
    // then finds the world already built (no post-teleport chunk stream / mesh
    // storm, which was the multi-second teleport stutter). The radius-8 compile
    // pump (COMPILE_PUMP_RADIUS_CHUNKS) builds the meshes for the fed chunks in
    // the background, so they are ready too.
    private static final int FEED_RADIUS_CHUNKS = 8; // 17x17 = 289 chunks per portal

    public static void feedExistingChunks(ResourceKey<Level> dimension) {
        ClientLevel destLevel = levels.get(dimension);
        if (destLevel == null) return;

        var chunks = com.warwa.seamlessportals.chunk.RemoteChunkManager.getChunks(dimension);
        if (chunks == null || chunks.isEmpty()) return;

        // Find the destination portal positions in this dimension (from any
        // link where this dimension appears as either source or destination).
        var pm = com.warwa.seamlessportals.portal.PortalManager.getClientInstance();
        java.util.List<net.minecraft.core.BlockPos> portalOrigins = new java.util.ArrayList<>();
        for (var link : pm.getLinksInRange(dimension,
                new net.minecraft.core.BlockPos(0, 64, 0), Integer.MAX_VALUE / 2)) {
            if (link.getDestination().getDimension() == dimension) {
                portalOrigins.add(link.getDestination().getOrigin());
            } else if (link.getSource().getDimension() == dimension) {
                portalOrigins.add(link.getSource().getOrigin());
            }
        }

        if (portalOrigins.isEmpty()) {
            SeamlessPortalsConstants.rlog(
                "[SEAMLESS PHASE2] feedExistingChunks: no portal origins found for {}, skipping",
                dimension.identifier());
            return;
        }

        // Only backfill chunks the cached ClientLevel doesn't already have.
        // Re-feeding an already-loaded chunk from RemoteChunkManager's
        // snapshot would destroy live mutations that arrived via vanilla
        // block-update packets (when the cached level was mc.level and
        // received a direct edit, e.g. the player placing a block while in
        // that dim) or via our RemoteBlockUpdatePayload mirror. The snapshot
        // is only guaranteed to be fresh at initial serialization time; as
        // the player's session progresses, the cached level's in-memory
        // state drifts ahead of it. Guard against that regression here.
        net.minecraft.client.multiplayer.ClientChunkCache cachedChunkSource =
            destLevel.getChunkSource();

        java.util.Set<net.minecraft.world.level.ChunkPos> alreadyQueued = new java.util.HashSet<>();
        int enqueued = 0;
        int skippedLive = 0;
        for (var origin : portalOrigins) {
            int cx = origin.getX() >> 4;
            int cz = origin.getZ() >> 4;
            for (int dx = -FEED_RADIUS_CHUNKS; dx <= FEED_RADIUS_CHUNKS; dx++) {
                for (int dz = -FEED_RADIUS_CHUNKS; dz <= FEED_RADIUS_CHUNKS; dz++) {
                    var pos = new net.minecraft.world.level.ChunkPos(cx + dx, cz + dz);
                    if (!alreadyQueued.add(pos)) continue;
                    if (!chunks.containsKey(pos)) continue;
                    if (cachedChunkSource.hasChunk(pos.x(), pos.z())) {
                        // Cached level already has this chunk — treat its
                        // in-memory state as authoritative (it may contain
                        // live mutations not in our snapshot).
                        skippedLive++;
                        continue;
                    }
                    pendingFeeds.add(new PendingFeed(dimension, pos));
                    enqueued++;
                }
            }
        }
        if (enqueued > 0) {
            feedingDims.add(dimension);
        }
        if (skippedLive > 0) {
            SeamlessPortalsConstants.LOGGER.debug(
                "[SEAMLESS PHASE2] feedExistingChunks: skipped {} live-loaded chunks (only backfilling {}) for {}",
                skippedLive, enqueued, dimension.identifier());
        }

        if (enqueued > 0 || skippedLive > 0) {
            SeamlessPortalsConstants.rlog(
                "[SEAMLESS PHASE2] feedExistingChunks for {} — enqueued={} skippedLive={} (chunks-in-RCM={})",
                dimension.identifier(), enqueued, skippedLive, chunks.size());
        }
    }

    /**
     * Called once per client tick from the main client lifecycle. Processes at
     * most {@link #FEEDS_PER_DRAIN} queued chunks: loads the section data into
     * the destination ClientLevel via replaceWithPacketData, applies stored
     * light, and marks sections dirty on the secondary renderer.
     */
    public static void drainPendingFeeds() {
        if (pendingFeeds.isEmpty()) return;

        long drainStart = System.nanoTime();
        int processed = 0;
        // Time-bounded drain: never exceed 8ms of work per tick. Sections
        // counts vary (overworld chunks are full-height, lots of heavy
        // sections; nether chunks can be smaller), so we can't predict
        // per-chunk cost from count alone — just keep drawing chunks until
        // we either hit FEEDS_PER_DRAIN or the time budget.
        final long BUDGET_NS = 8_000_000L;
        while (processed < FEEDS_PER_DRAIN && (System.nanoTime() - drainStart) < BUDGET_NS) {
            PendingFeed feed = pendingFeeds.poll();
            if (feed == null) break;

            ClientLevel destLevel = levels.get(feed.dim);
            if (destLevel == null) {
                processed++;
                continue;
            }

            var sections = com.warwa.seamlessportals.chunk.RemoteChunkManager
                .getChunks(feed.dim);
            if (sections == null) {
                processed++;
                continue;
            }
            net.minecraft.world.level.chunk.LevelChunkSection[] sectionsForChunk = sections.get(feed.pos);
            if (sectionsForChunk == null) {
                processed++;
                continue;
            }

            net.minecraft.client.multiplayer.ClientChunkCache cache = destLevel.getChunkSource();
            int chunkX = feed.pos.x();
            int chunkZ = feed.pos.z();

            try {
                io.netty.buffer.ByteBuf rawBuf = io.netty.buffer.Unpooled.buffer();
                net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(rawBuf);
                for (var section : sectionsForChunk) {
                    section.write(buf);
                }
                cache.replaceWithPacketData(chunkX, chunkZ, buf,
                    java.util.Collections.emptyMap(), tag -> {});
                buf.release();

                // Apply stored light (fixes "blue box"/unlit chunks).
                var skyLight = com.warwa.seamlessportals.chunk.RemoteChunkManager
                    .getSkyLight(feed.dim, feed.pos);
                var blockLight = com.warwa.seamlessportals.chunk.RemoteChunkManager
                    .getBlockLight(feed.dim, feed.pos);
                if (skyLight != null || blockLight != null) {
                    PortalDimensionManager.applyLightToLevel(
                        destLevel, chunkX, chunkZ, skyLight, blockLight, sectionsForChunk.length);
                }

                // Mark sections dirty on the secondary renderer so meshes
                // rebuild. Vanilla path; under Sodium this is a no-op
                // because Sodium replaces SectionRenderDispatcher's dirty-
                // tracking. Sodium's own
                // {@code ClientChunkCacheMixin.onChunkLoaded} fires on
                // {@code replaceWithPacketData} above and queues a
                // chunk-load event in the per-{@code ClientLevel}
                // {@code ChunkTracker}. That queue is drained inside
                // {@code SodiumWorldRenderer.setupTerrain ->
                // processChunkEvents} (during the next portal-view render
                // frame), which registers the chunk's sections in the
                // {@code RenderSectionManager} the SAME way vanilla chunk
                // loads do.
                //
                // Earlier attempts here also called
                // {@code SodiumBridge.notifyChunkAddedToRenderer} and
                // {@code scheduleRebuildForChunk} per-section. Those
                // direct RSM pokes caused state corruption (garbled
                // chunk meshes / striped texture artifacts post-teleport
                // — the chunk graph's neighbor links were broken by
                // double-registration). The natural Sodium flow is
                // sufficient; do not manually poke RSM here.
                // 26.2: setSectionDirtyWithNeighbors moved off LevelRenderer
                // onto the dimension's LevelExtractor (D3).
                LevelExtractor destExtractor = extractors.get(feed.dim);
                if (destExtractor != null) {
                    int minSectionY = destLevel.getMinSectionY();
                    for (int sy = 0; sy < sectionsForChunk.length; sy++) {
                        int sectionY = minSectionY + sy;
                        destExtractor.setSectionDirtyWithNeighbors(
                            chunkX, sectionY, chunkZ);
                    }
                }

            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.error(
                    "[SEAMLESS PHASE2] drainPendingFeeds: Failed chunk [{},{}] in {}",
                    chunkX, chunkZ, feed.dim.identifier(), e);
            }

            processed++;
        }

        // Diagnostic (temporary): real per-chunk feed cost + remaining backlog,
        // so we know whether the wider FEED_RADIUS stays within the per-tick
        // budget during approach (the old "≈10ms/chunk" note predates the
        // time-budgeted drain).
        long drainMs = (System.nanoTime() - drainStart) / 1_000_000L;
        if (processed > 0 && feedDiagCount < 80) {
            feedDiagCount++;
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS FEED] drained {} chunks in {}ms (~{}us/chunk) pending={}",
                processed, drainMs,
                (drainMs * 1000L) / processed, pendingFeeds.size());
        }

        // Log when a dimension's feed queue fully drains.
        for (ResourceKey<Level> d : feedingDims.toArray(new ResourceKey[0])) {
            boolean anyStillPending = false;
            for (PendingFeed pf : pendingFeeds) {
                if (pf.dim == d) { anyStillPending = true; break; }
            }
            if (!anyStillPending) {
                feedingDims.remove(d);
                SeamlessPortalsConstants.rlog(
                    "[SEAMLESS PHASE2] Finished async-feeding chunks for level {}",
                    d.identifier());
            }
        }
    }

    /**
     * Remove a specific dimension's secondary renderer and level.
     * Called when the player transitions to that dimension (it becomes primary).
     */
    public static void removeRenderer(ResourceKey<Level> dimension) {
        LevelRenderer renderer = renderers.remove(dimension);
        ClientLevel level = levels.remove(dimension);
        // 26.2: setLevel moved off LevelRenderer onto the LevelExtractor (D4).
        LevelExtractor extractor = extractors.remove(dimension);
        // Drop this dim's per-dest particle engine (clears its particles +
        // tracking emitters via setLevel(null)).
        ParticleEngine particleEngine = particleEngines.remove(dimension);
        if (particleEngine != null) {
            particleEngine.setLevel(null);
        }

        if (renderer != null) {
            try {
                if (extractor != null) {
                    extractor.setLevel(null);
                }
                renderer.close();
            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.error(
                    "[SEAMLESS PHASE2] Error removing renderer for {}", dimension.identifier(), e);
            }
        }
    }

    /**
     * Promote the registered renderer+level for {@code dim} to be installed
     * as {@code mc.levelRenderer} / {@code mc.level}.
     *
     * <p>Removes the pair from {@link #renderers}/{@link #levels} so it's
     * exclusively "primary" while the player is in that dim. The caller
     * (see {@link com.warwa.seamlessportals.mixin.client.HandleRespawnMixin})
     * performs the actual {@code mc.levelRenderer = ...} swap.
     *
     * <p>Re-points the promoted renderer's {@code LevelRenderState} to the
     * supplied shared state so {@code GameRenderer.renderLevel} — which
     * reads {@code gameRenderState.levelRenderState.chunkSectionsToRender}
     * — sees what the renderer's {@code extractLevel} writes.
     *
     * <p>Returns {@code null} when no entry exists for the dim (the caller
     * should fall back to vanilla respawn + fresh ClientLevel). In practice
     * this only happens if the player enters a dim they've never visited
     * and never rendered a portal into.
     */
    public static Promotion promoteToMain(
            ResourceKey<Level> dim,
            LevelRenderState sharedState) {
        com.warwa.seamlessportals.render.CrossingTracer.event(
            "PROMOTE " + dim.identifier().getPath());
        initializeIfNeeded();
        LevelRenderer renderer = renderers.remove(dim);
        ClientLevel level = levels.remove(dim);
        // The dim is becoming primary — its cached per-dest particle engine is
        // obsolete (the global mc.particleEngine handles the active dim). Drop it.
        ParticleEngine promotedEngine = particleEngines.remove(dim);
        if (promotedEngine != null) {
            promotedEngine.setLevel(null);
        }
        if (renderer == null || level == null) {
            return null;
        }
        ((LevelRendererAccessorMixin) renderer)
            .seamlessportals$setLevelRenderState(sharedState);

        // Clear the stale visibleSections this renderer accumulated as a
        // portal-view secondary. We populate visibleSections MANUALLY for the
        // FBO render (the "Direct compilation" loop), holding RenderSection
        // objects by their then-current section node. On promotion to main, the
        // post-teleport viewArea.repositionCamera RELOCATES those nodes; if the
        // stale list leaks into the main render, GameRenderer.extract() (which
        // iterates levelRenderer.visibleSections(), LevelExtractor.java:152)
        // feeds the now-relocated nodes into sectionUpdateRenderStates, and
        // LevelRenderer.compileSections does viewArea.getRenderSection(staleNode)
        // → null → NPE (the teleport crash, RenderSection.wasPreviouslyEmpty()).
        // clearVisibleSections() is vanilla's own reset (LevelRenderer.java:873);
        // extract()'s applyFrustum repopulates it from the new camera next frame.
        renderer.clearVisibleSections();
        // Clear this dim's portal-view compile-schedule guard — it's no longer a
        // dest; stale entries would block recompiling when it next becomes a dest.
        com.warwa.seamlessportals.render.PortalContextSwitch.clearCompileSchedule(dim);

        // 26.2: re-prime the promoted renderer's SectionOcclusionGraph so its
        // visibleSections actually repopulate for the main render. The mod's
        // 26.1.2 sync-prime (consumePendingPrime via a cullTerrain @Inject) is
        // DEAD in 26.2 — cull moved to LevelExtractor.extract()->applyFrustum,
        // which is gated on a frustum/camera-rotation change and so never
        // repopulates visibleSections after a renderer swap (log DIAG #1:
        // promoted renderer has visibleSections=0 -> blank main terrain).
        // invalidate() sets needsFullUpdate; render()'s SectionOcclusionGraph
        // .update() schedules the async rebuild, whose completion flips
        // needsFrustumUpdate=true so the next extract()'s applyFrustum runs and
        // repopulates visibleSections.
        // Phase 3 (IP "live window"): when continuous-extract keeps the dest
        // SectionOcclusionGraph WARM (Phase 2), do NOT invalidate it on promote.
        // The warm graph is already built for the virtual (≈ post-teleport) camera
        // and left needsFrustumUpdate set (the dest extract skipped applyFrustum
        // via the captured frustum), so the FIRST main extract after promotion
        // consumes it and repopulates visibleSections immediately — no async
        // rebuild, no blank frames, instant. Invalidating would discard that
        // warmth and reintroduce the rebuild stall. When continuous-extract is
        // off, keep the legacy invalidate (cold renderer needs the rebuild).
        if (!com.warwa.seamlessportals.render.PortalContextSwitch.useContinuousExtract) {
            try {
                var sog = renderer.sectionOcclusionGraph();
                if (sog != null) {
                    sog.invalidate();
                }
            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS PHASE2] SOG invalidate on promote failed: {}", e.toString());
            }
        }

        // INSTANT REPAINT (fixes the "blank for a second" on entering the heavier
        // overworld): the demoted renderer preserved its WARM SectionOcclusionGraph
        // currentGraph (built while it was the active world). invalidate() above only
        // schedules an ASYNC full rebuild, so applyFrustum waits for it → blank until
        // it lands (~1s for the big overworld). Force needsFrustumUpdate=true so the
        // FIRST post-promote LevelExtractor.extract → applyFrustum repopulates
        // visibleSections from that warm currentGraph IMMEDIATELY (no rebuild wait);
        // the invalidate's rebuild then refines in the background, no blank. For a
        // COLD graph (a dim never yet active, e.g. nether first visit) currentGraph
        // is empty so this is a harmless no-op — that path keeps today's behavior.
        try {
            var sog = renderer.sectionOcclusionGraph();
            if (sog != null) {
                ((com.warwa.seamlessportals.mixin.client.SectionOcclusionGraphAccessorMixin) (Object) sog)
                    .seamlessportals$getNeedsFrustumUpdate().set(true);
            }
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS PHASE2] SOG frustum-repaint on promote failed: {}", e.toString());
        }

        // Phase 5 flash-bridge: for ~1.5s the main render's applyFrustum paints
        // visibleSections via the bounded VisibleSectionDiscovery flood-fill instead
        // of the cold just-promoted occlusion graph — so the entered dim shows
        // terrain immediately (no sky/blank flash) while the engine rebuilds the
        // graph in the background. The needsFrustumUpdate force above makes the
        // FIRST post-promote applyFrustum actually run so the bridge engages.
        com.warwa.seamlessportals.render.PortalContextSwitch.armPromoteBridge();

        // ADOPT, DON'T WIPE (2026-07-05). This used to DISCARD every mirrored
        // entity and wait for vanilla's post-teleport re-adds — which is exactly
        // the user-visible "entities disappear and reappear" blink at each
        // crossing, plus a render-thread stall re-CONSTRUCTING each entity
        // (piglin Brain init ~160ms, [SEAMLESS STUCK] proven). The mirrors are
        // in the CORRECT level at their true dest coordinates (the old
        // wrong-coords fear predates per-dimension mirror levels), so KEEP
        // them: vanilla's re-add packets now ADOPT the existing instances in
        // place (ClientPacketListenerAddEntityAdoptMixin — same id+type+uuid →
        // update from packet, no discard, no recreate). Mirrors that vanilla
        // does NOT re-add by the deadline no longer exist server-side; the
        // deferred prune removes those quietly.
        Minecraft mc0 = Minecraft.getInstance();
        net.minecraft.client.player.LocalPlayer lp0 = mc0.player;
        java.util.Set<Integer> promotePending = new java.util.HashSet<>();
        for (net.minecraft.world.entity.Entity ent : level.entitiesForRendering()) {
            if (ent == lp0) continue;
            promotePending.add(ent.getId());
        }
        armEntityAdoption(level, promotePending, 4_000L);
        int promoteWiped = promotePending.size(); // log: mirrors HELD for adoption (none wiped)

        // 26.2 CRITICAL: re-point mc.levelExtractor onto the PROMOTED renderer.
        // mc.levelExtractor is the SINGLE main extractor (public final, bound once
        // to the ORIGINAL renderer at Minecraft.java:649). GameRenderer.extract()
        // drives mc.levelExtractor — NOT mc.levelRenderer — so swapping only
        // mc.levelRenderer (as the 26.1.2 path did, before LevelExtractor existed)
        // leaves extract() populating the OLD renderer's visibleSections while
        // render() uses the promoted one with 0 visibleSections -> blank terrain
        // after teleport (PROVEN: log DIAG #1 oldRenderer=1225, promoted=0).
        //
        // We re-point its fields DIRECTLY (levelRenderer -> promoted renderer,
        // level -> dest level, sectionUpdateTracker -> the per-dimension
        // destExtractor's already-correct tracker) rather than calling setLevel(),
        // because setLevel() -> allChanged() sets shouldInvalidateCompiledGeometry
        // -> extract() runs invalidateCompiledGeometry -> WIPES the cached meshes
        // this cached-renderer promotion exists to preserve (LevelExtractor.java
        // :393/:406). levelRenderState stays mc.levelExtractor's shared state,
        // already consistent (the promoted renderer was set to sharedState above).
        // Remove dim from extractors to match the renderers/levels removals above.
        LevelExtractor destExtractor = extractors.remove(dim);
        LevelExtractorAccessor mainExt =
            (LevelExtractorAccessor) (Object) mc0.levelExtractor;
        mainExt.seamlessportals$setLevelRenderer(renderer);
        mainExt.seamlessportals$setLevel(level);
        // Sync lastViewDistance so the FIRST post-promote extract() doesn't trip its
        // `getEffectiveRenderDistance() != lastViewDistance` guard and call allChanged()
        // -> invalidateCompiledGeometry, which would WIPE every compiled mesh this
        // promotion exists to preserve (then re-mesh the whole world — the confirmed
        // per-teleport stutter cascade: allChanged fired 1:1 with promotes, each
        // triggering ~15+ re-mesh stall frames). The direct setLevel above already
        // skips the setLevel->allChanged path; this closes the indirect extract() path.
        mainExt.seamlessportals$setLastViewDistance(mc0.options.getEffectiveRenderDistance());
        if (destExtractor != null) {
            mainExt.seamlessportals$setSectionUpdateTracker(
                ((LevelExtractorAccessor) (Object) destExtractor)
                    .seamlessportals$getSectionUpdateTracker());
        } else {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS PHASE2] No destExtractor for {} on promote — "
                    + "mc.levelExtractor.sectionUpdateTracker left stale", dim.identifier());
        }
        // SYMMETRIC RE-POINT (the other half of the demote-side block-freeze
        // fix): while promoted, the level's own levelExtractor field must be
        // mc.levelExtractor ITSELF — the exact identity vanilla gives its
        // level at construction — not merely an extractor sharing the same
        // tracker SNAPSHOT. Any allChanged() (render-distance change, F3+A,
        // video settings, resource reload) REPLACES mc.levelExtractor's
        // sectionUpdateTracker (LevelExtractor.java:411); with only a snapshot
        // share, the level would keep writing dirty-marks into the replaced
        // tracker and the frozen-blocks bug would return until the next
        // crossing. Writing through mc.levelExtractor survives replacement.
        // demoteFromMain re-points back to the per-dim extractor on the way out.
        ((com.warwa.seamlessportals.mixin.client.ClientLevelExtractorAccessor) level)
            .seamlessportals$setLevelExtractor(mc0.levelExtractor);

        // Flag this renderer for synchronous SOG prime on its next
        // cullTerrain call. Eliminates the 1-2 blank-terrain frames
        // that would otherwise show while the async
        // SectionOcclusionGraph full-update task propagates.
        synchronized (pendingSyncPrime) {
            pendingSyncPrime.add(renderer);
        }

        SeamlessPortalsConstants.rlog(
            "[SEAMLESS PHASE2] Promoted renderer → mc.levelRenderer for {} (marked for SOG sync prime; {} mirrored entities held for adoption)",
            dim.identifier(), promoteWiped);
        return new Promotion(renderer, level);
    }

    // ===== Entity adoption across crossings (no wipe, no blink) =====
    //
    // At promote/demote the level's mirrored entity population is NOT discarded;
    // instead the ids are armed here and either (a) ADOPTED when the authoritative
    // re-add arrives (vanilla ClientboundAddEntityPacket for the promoted dim,
    // RemoteEntityAddPayload for the demoted one) — the arrival unmarks the id —
    // or (b) PRUNED quietly at the deadline (no re-add ⇒ the entity no longer
    // exists server-side; a stale mirror must not linger). Render-thread only.

    private static final class PendingAdoption {
        final java.lang.ref.WeakReference<ClientLevel> level;
        final java.util.Set<Integer> ids;
        final long deadlineNanos;
        PendingAdoption(ClientLevel level, java.util.Set<Integer> ids, long deadlineNanos) {
            this.level = new java.lang.ref.WeakReference<>(level);
            this.ids = ids;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private static final java.util.List<PendingAdoption> PENDING_ADOPTIONS = new java.util.ArrayList<>();

    /** Arm a level's current mirror ids for adopt-or-prune. */
    public static void armEntityAdoption(ClientLevel level, java.util.Set<Integer> ids, long timeoutMs) {
        if (ids.isEmpty()) return;
        PENDING_ADOPTIONS.add(new PendingAdoption(
            level, ids, System.nanoTime() + timeoutMs * 1_000_000L));
    }

    /** The authoritative re-add for this id arrived (and adopted the mirror) — unmark it. */
    public static void noteEntityAdopted(net.minecraft.world.level.Level level, int id) {
        for (PendingAdoption p : PENDING_ADOPTIONS) {
            if (p.level.get() == level) p.ids.remove(id);
        }
    }

    /** Once per frame (renderLevel HEAD): prune expired never-re-added mirrors. */
    public static void pruneEntityAdoptions() {
        if (PENDING_ADOPTIONS.isEmpty()) return;
        long now = System.nanoTime();
        java.util.Iterator<PendingAdoption> it = PENDING_ADOPTIONS.iterator();
        while (it.hasNext()) {
            PendingAdoption p = it.next();
            ClientLevel lvl = p.level.get();
            if (lvl == null || p.ids.isEmpty()) { it.remove(); continue; }
            if (now < p.deadlineNanos) continue;
            int pruned = 0;
            for (int id : p.ids) {
                try {
                    if (lvl.getEntity(id) != null) {
                        lvl.removeEntity(id, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                        pruned++;
                    }
                } catch (Exception ignored) {}
            }
            if (pruned > 0) {
                SeamlessPortalsConstants.rlog(
                    "[SEAMLESS LIVE ENT] Pruned {} stale mirrors in {} (not re-added by deadline)",
                    pruned, lvl.dimension().identifier());
            }
            it.remove();
        }
    }

    /**
     * Returns {@code true} if the given renderer was marked for a one-shot
     * synchronous SectionOcclusionGraph prime (and clears the flag).
     *
     * <p>Called from
     * {@link com.warwa.seamlessportals.mixin.client.LevelRendererCullTerrainMixin}
     * immediately after vanilla's {@code SectionOcclusionGraph.update()} call
     * on each frame. Returning {@code true} triggers a blocking wait on the
     * newly-scheduled full-update task; all other frames pass through without
     * cost.
     */
    public static boolean consumePendingPrime(LevelRenderer renderer) {
        synchronized (pendingSyncPrime) {
            return pendingSyncPrime.remove(renderer);
        }
    }

    /**
     * Put a renderer+level back into the unified map after it is no longer
     * {@code mc.levelRenderer} / {@code mc.level}. The re-isolated
     * {@code LevelRenderState} means any future {@code extractLevel} call
     * during portal-view rendering writes to the isolated state and does
     * not clobber the new primary's shared state.
     *
     * <p>{@code ViewArea} and compiled section meshes are untouched, so a
     * future {@link #promoteToMain} skips rebuild.
     */
    /**
     * Keep cached (dormant) ClientLevels' {@code gameTime} in sync with the
     * currently-active {@code mc.level}. Without this, the cached levels'
     * time stays frozen at whatever the server last synced when that dim was
     * active — which in practice means overworld-as-portal-view stays at
     * the day-time from your last overworld visit even while the real server
     * time has advanced to night (or vice versa).
     *
     * <p>Effect: portal-view rendering of the destination dim now uses
     * current time of day. After a teleport to that dim, sky/lighting stays
     * consistent instead of jumping from "stale portal-view time" to
     * "live server time" over the first few frames.
     *
     * <p>Vanilla's {@code ClientPacketListener.handleSetTime} only applies
     * server-sent time updates to {@code mc.level}. Copying to cached
     * levels here mirrors that update across all of them every tick.
     *
     * <p>{@code gameTime} is the single authoritative counter in 1.21.2
     * (day/night is derived per-dim via {@code DimensionType.fixedTime} +
     * vanilla sky shaders) so syncing that alone is sufficient.
     */
    /**
     * Phase 2a tick pump: tick mirrored entities living in cached (dormant)
     * ClientLevels so their animations, interpolation handlers, and
     * prev-tick position fields advance frame-to-frame.
     *
     * <p>Without this call, a mirrored entity's
     * {@link net.minecraft.world.entity.InterpolationHandler} never runs
     * ({@code interpolate()} isn't called), so the render path reads stale
     * interpolated values and animation timers (arm swing, walk animation,
     * idle breathing) stay frozen. Prev-tick position fields
     * ({@code xo/yo/zo}) also stay stale, so the render's within-tick lerp
     * jumps every time our 20 Hz Move payload snaps the current position
     * to a new value — which is the "spazzing" symptom observed when mobs
     * were visible but not animated.
     *
     * <p>We call the entity's own {@link Entity#tick()} which handles all
     * of the above. Exceptions are caught per-entity so a single misbehaving
     * mob doesn't poison the rest of the pump. Skips the local player
     * (which shouldn't ever be in a cached level but a defense-in-depth
     * guard doesn't cost anything).
     */
    /**
     * Phase 1 of the IP "live window" migration — tick every RESIDENT remote
     * ClientLevel each client tick exactly like the active world, so the
     * destination dimension is genuinely LIVE through the portal (entities,
     * fluids, fire, block entities, light), not a frozen snapshot. Faithful port
     * of IP {@code ClientWorldLoader.tick}/{@code tickRemoteWorld} (lines
     * 111-174).
     *
     * <p>Each remote level is ticked inside a MINIMAL context swap: {@code mc.level}
     * and {@code mc.particleEngine} point at the remote dim for the duration.
     * The renderer/extractor are NOT swapped — in 26.2 a {@link ClientLevel}
     * routes its own block/section dirties to the {@link LevelExtractor} it was
     * constructed with ({@code ClientLevel.setBlocksDirty → this.levelExtractor},
     * verified), so dest updates land on the dest renderer regardless. The
     * particle engine IS swapped because {@code ClientLevel.addParticle} routes
     * through {@code mc.particleEngine}.
     *
     * <p>Supersedes {@link #tickCachedEntities} (the old mirrored-entity-only
     * hand-tick); when {@link #isClientRemoteTickingEnabled} is off it falls back
     * to that for a clean rollback. Ambient particles stay in
     * {@link #tickCachedParticles}.
     */
    public static void tickRemoteWorlds() {
        if (!isClientRemoteTickingEnabled) {
            tickCachedEntities();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel active = mc.level;
        if (active == null || mc.player == null) return;

        ParticleEngine globalEngine = mc.particleEngine;
        com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin mcAccess =
            (com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin) mc;

        // IP scope: only tick a secondary while a portal looking into it is near the
        // player. A dim past the grace window is paused (no tick/fluids/light) — it
        // resumes when the player walks back into portal range.
        refreshDestScopes();

        isClientRemoteTicking = true;
        try {
            for (Map.Entry<ResourceKey<Level>, ClientLevel> e : levels.entrySet()) {
                ClientLevel cached = e.getValue();
                if (cached == null || cached == active) continue;
                if (!isDestScopeLive(e.getKey())) continue; // paused: no nearby portal
                ParticleEngine engine = getOrCreateParticleEngine(cached);

                mc.level = cached;
                if (engine != null) mcAccess.seamlessportals$setParticleEngine(engine);
                try {
                    long e0 = System.nanoTime();
                    cached.tickEntities();
                    long e1 = System.nanoTime();
                    cached.tick(() -> true);
                    long e2 = System.nanoTime();
                    cached.pollLightUpdates();
                    long e3 = System.nanoTime();
                    com.warwa.seamlessportals.render.PerfTimers.add("  remote.tickEntities", e1 - e0);
                    com.warwa.seamlessportals.render.PerfTimers.add("  remote.tick", e2 - e1);
                    com.warwa.seamlessportals.render.PerfTimers.add("  remote.pollLight", e3 - e2);
                } catch (Throwable t) {
                    // Remote tick is best-effort — never crash the client tick.
                } finally {
                    if (engine != null) mcAccess.seamlessportals$setParticleEngine(globalEngine);
                    mc.level = active;
                }
            }
        } finally {
            isClientRemoteTicking = false;
            // Defensive: guarantee the active context is restored.
            if (mc.level != active) mc.level = active;
            if (mc.particleEngine != globalEngine) {
                mcAccess.seamlessportals$setParticleEngine(globalEngine);
            }
        }
    }

    /**
     * Run each live secondary (cached dest) ClientLevel's light engine at
     * frame-render END — the missing HALF of the portal-view light pipeline
     * (2026-07-08, the "nether portal-view lighting is wrong until the first
     * crossing" fix).
     *
     * <p>{@link #tickRemoteWorlds} already calls {@code cached.pollLightUpdates()},
     * which drains the queued light lambdas → {@code applyLightData} →
     * {@code queueSectionData} — but that ONLY stashes the light nibbles into
     * {@code queuedSections} and sets {@code hasInconsistencies}; it does NOT
     * publish them. Only {@code LevelLightEngine.runLightUpdates()} swaps the
     * queued nibbles into the live map ({@code swapSectionMap}) and drains the
     * block-light recompute queued by mirrored block changes ({@code checkBlock}
     * from {@code RemoteBlockUpdater}). Vanilla {@code ClientLevel.update()} runs
     * BOTH halves every frame for the ACTIVE level; the inactive secondary never
     * gets {@code update()}, and the mod had ported only the poll half — so the
     * nether portal view (no skylight → block light is everything) showed stale/
     * dark light until a crossing promoted the secondary and the now-active
     * level's {@code update()} finally ran the engine.
     *
     * <p>IP-faithful: this is IP's {@code MyRenderHelper.lateUpdateLight}, invoked
     * at frame-render END (its {@code MixinGameRenderer}), deliberately NOT
     * mid-tick — IP's comment: running it before world rendering can make
     * section-edge smooth lighting abnormal. {@code runLightUpdates} fires the
     * engine's {@code onLightUpdate} reports, which
     * {@link SeamlessClientChunkMap#onLightUpdate} routes to the secondary's
     * extractor ({@code setSectionDirty}) so the portal-view sections re-mesh next
     * frame. {@code mc.level} stays the ACTIVE dim here (no swap), so
     * {@code onLightUpdate} correctly takes the secondary-extractor branch.
     */
    public static void lateUpdateSecondaryLight() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel active = mc.level;
        for (Map.Entry<ResourceKey<Level>, ClientLevel> e : levels.entrySet()) {
            ClientLevel cached = e.getValue();
            if (cached == null || cached == active) continue;
            if (!isDestScopeLive(e.getKey())) continue; // paused: no nearby portal
            try {
                cached.getChunkSource().getLightEngine().runLightUpdates();
            } catch (Throwable t) {
                // Best-effort — never crash the render frame on a light update.
            }
        }
    }

    public static void tickCachedEntities() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel active = mc.level;
        net.minecraft.client.player.LocalPlayer localPlayer = mc.player;
        refreshDestScopes();
        for (Map.Entry<ResourceKey<Level>, ClientLevel> e : levels.entrySet()) {
            ClientLevel cached = e.getValue();
            if (cached == null || cached == active) continue;
            if (!isDestScopeLive(e.getKey())) continue; // paused: no nearby portal
            for (net.minecraft.world.entity.Entity ent : cached.entitiesForRendering()) {
                if (ent == localPlayer) continue;
                if (ent.isPassenger()) continue;
                if (ent.isRemoved()) continue;
                try {
                    // Mirror entities follow vanilla's exact per-tick
                    // lifecycle: setOldPosAndRot() to freeze the prev
                    // frame, increment tickCount, then full entity.tick().
                    // This is what ClientLevel.tickNonPassenger does for
                    // normal mc.level entities — using the same code path
                    // here guarantees walkAnimation, attack swings, pose
                    // transitions, effect ticks, head-rotation lerping
                    // all behave exactly like they do for a directly-seen
                    // entity in vanilla. AI server-side work stays
                    // guarded inside aiStep via isClientSide().
                    ent.setOldPosAndRot();
                    ent.tickCount++;
                    ent.tick();
                } catch (Throwable t) {
                    // Swallow — mirror is render-only, can't crash here.
                }
            }
        }
    }

    public static void syncTimeToCachedLevels() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel active = mc.level;
        if (active == null) return;
        long gt = active.getGameTime();
        for (Map.Entry<ResourceKey<Level>, ClientLevel> e : levels.entrySet()) {
            ClientLevel cached = e.getValue();
            if (cached == null || cached == active) continue;
            cached.setTimeFromServer(gt);
        }
    }

    /**
     * Spawn + tick each cached destination dimension's ambient particles (flame,
     * lava, nether portal, fog) in its OWN {@link ParticleEngine}, so they are
     * present to render inside the portal view.
     *
     * <p>Mirrors vanilla's per-tick {@code level.animateTick(...) +
     * particleEngine.tick()} ({@code Minecraft.tick}), but for the non-active
     * cached levels: vanilla only animates/ticks particles for {@code mc.level}.
     * For each cached dim we swap {@code mc.particleEngine} to that dim's engine
     * across BOTH the {@code animateTick} spawn ({@code ClientLevel.addParticle}
     * routes to {@code mc.particleEngine}) AND the engine tick (a particle's own
     * tick can spawn sub-particles the same way), then restore it. The dest level
     * is animate-ticked only when a nearby portal in the active dim looks into it,
     * around the through-portal 1:1 mirror of the player (same transform the dest
     * camera uses) — so particles spawn where they will actually be seen. Every
     * cached engine is ticked regardless, so existing particles age out and die.
     *
     * <p>Thread-safety: this runs on the client tick, and the render-time swap in
     * {@link com.warwa.seamlessportals.render.PortalContextSwitch#withSwitchedWorld}
     * runs on the same (client/render) thread; the two never overlap, and every
     * swap here is restored before the loop body returns.
     */
    public static void tickCachedParticles() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel active = mc.level;
        net.minecraft.client.player.LocalPlayer player = mc.player;
        if (active == null || player == null) return;
        ParticleEngine globalEngine = mc.particleEngine;
        if (globalEngine == null) return;

        com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin mcAccess =
            (com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin) mc;

        // Drop engines whose level is no longer cached (dim promoted/removed).
        particleEngines.keySet().removeIf(dim -> !levels.containsKey(dim));

        // For each dest dim viewed through a nearby portal, the center to spawn
        // ambient particles around: the DEST PORTAL ORIGIN. It is always inside
        // the fed-chunk radius (FEED_RADIUS_CHUNKS, ~48 blocks ⊇ animateTick's ±32
        // scan) so samples hit LOADED chunks, AND it is exactly the region framed
        // by the portal opening, so the particles spawn where they are seen. (The
        // transformed player position drifts out of the fed region when standing
        // far from the portal → empty-chunk samples → no particles.) One entry per
        // dim (first nearby portal that links to it).
        Map<ResourceKey<Level>, net.minecraft.core.BlockPos> animateCenters =
            new java.util.HashMap<>();
        try {
            com.warwa.seamlessportals.portal.PortalManager pm =
                com.warwa.seamlessportals.portal.PortalManager.getClientInstance();
            com.warwa.seamlessportals.portal.PortalTracker tracker =
                pm.getTracker(active.dimension());
            if (tracker != null) {
                net.minecraft.core.BlockPos playerPos = player.blockPosition();
                double range = com.warwa.seamlessportals.config.SeamlessPortalsConfig.get()
                    .getPortalRenderDistance() * 16.0;
                for (com.warwa.seamlessportals.portal.PortalInfo srcPortal :
                        tracker.getPortalsInRange(playerPos, range)) {
                    if (!com.warwa.seamlessportals.config.SeamlessPortalsConfig
                            .shouldRenderThrough(srcPortal.getType())) continue;
                    java.util.Optional<com.warwa.seamlessportals.portal.PortalLink> linkOpt =
                        pm.getLinkForPortal(srcPortal.getPortalId());
                    if (linkOpt.isEmpty()) continue;
                    com.warwa.seamlessportals.portal.PortalLink link = linkOpt.get();
                    com.warwa.seamlessportals.portal.PortalInfo destPortal = link.getDestination();
                    ResourceKey<Level> destDim = destPortal.getDimension();
                    if (destDim.equals(active.dimension())) continue;
                    animateCenters.putIfAbsent(destDim, destPortal.getOrigin());
                }
            }
        } catch (Throwable t) {
            // Best-effort discovery; particles are non-critical, never crash the tick.
        }

        for (Map.Entry<ResourceKey<Level>, ClientLevel> e : levels.entrySet()) {
            ResourceKey<Level> dim = e.getKey();
            ClientLevel cached = e.getValue();
            if (cached == null || cached == active) continue;
            ParticleEngine engine = getOrCreateParticleEngine(cached);
            if (engine == null) continue;
            net.minecraft.core.BlockPos center = animateCenters.get(dim);
            // Route this dim's spawns (animateTick) + sub-spawns (tick) into its
            // own engine by making it the live mc.particleEngine for the duration.
            mcAccess.seamlessportals$setParticleEngine(engine);
            spawningDestParticles = true;
            try {
                // animateTick (ambient particle spawn) runs EVERY OTHER tick, matching IP
                // (ClientWorldLoader: `if (newWorld.getGameTime() % 2 == 0) newWorld.animateTick(...)`).
                // It is the dominant tickCachedParticles cost (667 random block samples/dim);
                // halving its rate is a free, IP-faithful cut. engine.tick() still runs every
                // tick so existing ambient particles keep aging/animating smoothly.
                if (center != null && (cached.getGameTime() % 2L == 0L)) {
                    cached.animateTick(center.getX(), center.getY(), center.getZ());
                }
                engine.tick();
            } catch (Throwable t) {
                // Mirror-only — never crash the client tick on a particle hiccup.
            } finally {
                spawningDestParticles = false;
                mcAccess.seamlessportals$setParticleEngine(globalEngine);
            }
            // Diagnostic (gated, temporary): confirm the dest engine actually
            // accumulates ambient particles after animateTick + tick.
            if (center != null && particleDiagCount < 12) {
                particleDiagCount++;
                SeamlessPortalsConstants.rlog(
                    "[SEAMLESS PARTICLE] tick dim={} center=({},{},{}) engine=[{}]",
                    dim.identifier(),
                    center.getX(), center.getY(), center.getZ(), engine.countParticles());
            }
        }
    }

    public static void demoteFromMain(
            ResourceKey<Level> dim,
            LevelRenderer renderer,
            ClientLevel level) {
        LevelRenderState demotedState = new LevelRenderState();
        ((LevelRendererAccessorMixin) renderer)
            .seamlessportals$setLevelRenderState(demotedState);
        // Clear this dim's portal-view compile-schedule guard so it re-evaluates
        // sections fresh as a dest — stale scheduled-but-cancelled entries (from
        // its prior dest stint before promotion) otherwise leave comp=0 and the
        // portal view shows the source sky instead of this dim's terrain.
        com.warwa.seamlessportals.render.PortalContextSwitch.clearCompileSchedule(dim);

        // Wipe cached entity state from the demoted level. While the level
        // is dormant the server isn't sending entity-tracking updates for
        // it, so any entities here would otherwise sit at their last-known
        // positions from this visit until the player returns — appearing
        // "frozen" on re-promotion (mobs stuck mid-walk, arrows still in
        // the air, items not despawned). On return the server's normal
        // entity-tracking sync spawns them fresh.
        //
        // The player instance has already been migrated to the incoming
        // dim's level by SeamlessClientTeleport.doVisualSwap's
        // oldLevel.removeEntity(player.getId(), ...) — so it's not in
        // this list. Still guard defensively.
        // ADOPT, DON'T WIPE (2026-07-05, same treatment as promoteToMain): the
        // demoted level's entities are kept and armed for adoption — the server's
        // PortalEntityTracker cold-restarts the (now-remote) dim and re-streams
        // RemoteEntityAddPayload for every live entity; RemoteEntityApplier
        // adopts the existing instances (same id+uuid+type → update in place,
        // no discard/recreate — no blink in the portal view, no construction
        // stall). Entities not re-streamed by the deadline are pruned. Longer
        // deadline than promote: the remote restream is drain-budgeted.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.player.LocalPlayer player = mc.player;
        java.util.Set<Integer> demotePending = new java.util.HashSet<>();
        for (net.minecraft.world.entity.Entity e : level.entitiesForRendering()) {
            if (e == player) continue;
            demotePending.add(e.getId());
        }
        armEntityAdoption(level, demotePending, 8_000L);

        // The demoted renderer was the vanilla main (driven by the shared
        // mc.levelExtractor); as a secondary it needs its OWN per-dimension
        // LevelExtractor so the portal-view doFboRender's extract() actually runs.
        // Without it getExtractor(dim) is null (PROVEN: log DIAG-STATE
        // dest=overworld extractorNull=true), extract() is SKIPPED, and entities,
        // clouds, particles, and fluid (dirty-section) updates never populate in
        // the portal view — while terrain still renders because it draws from
        // visibleSections, not the render-state. createRenderer builds this for
        // dims born as secondaries; demote (the STARTING dim becoming a secondary)
        // was missing it. Bind to the same demotedState the renderer now uses, and
        // set level + tracker DIRECTLY (NOT setLevel(), which calls allChanged() ->
        // invalidateCompiledGeometry -> wipes the meshes this demote preserves).
        try {
            LevelExtractor demotedExtractor = new LevelExtractor(mc, demotedState, renderer);
            LevelExtractorAccessor dea = (LevelExtractorAccessor) (Object) demotedExtractor;
            dea.seamlessportals$setLevel(level);
            dea.seamlessportals$setSectionUpdateTracker(
                new net.minecraft.client.SectionUpdateTracker(
                    level, mc.options.getEffectiveRenderDistance()));
            // Sync lastViewDistance so this demoted dim's FIRST FBO-render extract does NOT trip
            // extract()'s `getEffectiveRenderDistance() != lastViewDistance` (-1) guard ->
            // allChanged() -> invalidateCompiledGeometry() -> SectionOcclusionGraph.waitAndReset(),
            // a ~190ms render-thread block on the async SOG rebuild that ALSO wipes the meshes this
            // demote exists to preserve. Mirrors promoteToMain's sync.
            dea.seamlessportals$setLastViewDistance(mc.options.getEffectiveRenderDistance());
            demotedExtractor.onResourceManagerReload(mc.getResourceManager());
            extractors.put(dim, demotedExtractor);
            // RE-POINT THE LEVEL'S OWN EXTRACTOR FIELD (2026-07-06, the nether
            // block-freeze fix): every visual block-change on a ClientLevel goes
            // through its FINAL construction-time levelExtractor (setBlocksDirty →
            // setBlockDirty → that extractor's SectionUpdateTracker). Without this,
            // the level keeps writing dirty-marks into its ORIGINAL extractor's
            // tracker while the next promote wires mc.levelExtractor to consume
            // THIS new extractor's tracker — block break/place/server-updates then
            // change chunk data but never remesh (frozen visuals from the second
            // entry into a mod-created dim). Keeping the level pointed at the
            // CURRENT map extractor keeps writer and reader on one tracker across
            // every promote/demote cycle, for both mod-created and the original
            // vanilla level.
            ((com.warwa.seamlessportals.mixin.client.ClientLevelExtractorAccessor) level)
                .seamlessportals$setLevelExtractor(demotedExtractor);
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.error(
                "[SEAMLESS PHASE2] Failed to create demoted extractor for {}", dim.identifier(), e);
        }

        renderers.put(dim, renderer);
        levels.put(dim, level);
        SeamlessPortalsConstants.rlog(
            "[SEAMLESS PHASE2] Demoted renderer for {} — preserved meshes, created extractor, {} entities held for adoption",
            dim.identifier(), demotePending.size());
    }

    /**
     * Phase C — continuous async compile pump for secondary renderers.
     *
     * Modeled after IP's {@code ClientWorldLoader.tickRemoteWorld + .tick}
     * pattern: every non-active LevelRenderer gets a chance each client tick
     * to advance its compile work. In MC 26.1.2 the primary renderer's
     * compile is driven from {@code LevelRenderer.compileSections} inside
     * {@code GameRenderer.renderLevel}; secondaries are never rendered via
     * that path, so without this pump their dirty sections sit idle until
     * the next portal-view FBO render touches them. That's why portal views
     * into a dim we just left showed only 1-2 compiled chunks — the FBO
     * render only has a few ms of render-thread time to schedule async work
     * before the frame ends, so most sections stayed UNCOMPILED until the
     * user lingered.
     *
     * <p>Strategy:
     * <ul>
     *   <li>Iterate each secondary in {@link #renderers} whose dim is not
     *       the active primary's.</li>
     *   <li>Read {@code viewArea.getCameraSectionPos()} — wherever the last
     *       FBO render placed the ring buffer — and schedule async compile
     *       for dirty sections within a tight radius. Crucially we do NOT
     *       call {@code repositionCamera} here; that would wipe meshes via
     *       {@code setSectionNode → reset} (see
     *       {@code viewarea_reposition_mesh_loss.md}) and undo the async
     *       work already in flight.</li>
     *   <li>Hard cap on total scheduled tasks per tick
     *       ({@link #COMPILE_PUMP_BUDGET_PER_TICK}) to avoid saturating the
     *       background executor.</li>
     * </ul>
     *
     * Called from {@code ClientTickEvents.END_CLIENT_TICK} alongside the
     * chunk-feed drain.
     */
    // IP-faithful: the background mesh pump compiles the dest exactly as deep as it
    // is kept loaded (the config loading cap, IP's indirectLoadingRadiusCap; default
    // 8, clamp 1..32), so the chunks you'll land in are meshed BEFORE you cross —
    // IP's continuous worldRenderer.tick() equivalent. Read live so a config change
    // takes effect without restart. viewArea iteration cost is unchanged; only more
    // sections pass the radius gate to compile, still budget-capped.
    private static int compilePumpRadiusSq() {
        int d = com.warwa.seamlessportals.config.SeamlessPortalsConfig.get().getPortalRenderDistance();
        return d * d;
    }
    /**
     * Per-tick async-compile budget. Bounded to avoid client-tick
     * freeze: each rebuildSectionAsync call has synchronous chunk-
     * snapshot work (~1ms/section). 8192/tick = 4-5s of synchronous
     * blocking on cached-renderer-swap (the "totalt freeze on first
     * teleport" reported). 24/tick was too low — cached renderer
     * stayed near-empty.
     *
     * <p>Option-2 trim (toward IP, which has NO background compile pump and relies on
     * retention + lazy render-path compile): each scheduled section costs a ~1ms
     * synchronous createRegion snapshot ON the render/tick thread, so 128/tick was
     * ~128ms worst-case render-thread stall. Lowered to 48 (~48ms worst case) AND now
     * caps PASS 1 too, so the pump can no longer out-stall a frame. Still well above the
     * "24/tick was too low — cached renderer stayed near-empty" floor: 48/tick = 960
     * sections/sec, so a typical cached level (~5000 sections) still warms in ~5s of
     * background ticks while the player is in the other dimension.
     */
    private static final int COMPILE_PUMP_BUDGET_PER_TICK = 48;

    /**
     * Escape hatch for {@link #flushDestStagedUploads()}: an earlier build that flushed dest
     * uploads was in the mix during a "rapid flashing after teleport" report (never isolated —
     * the whole arc got rolled back before a clean test of the pre-frame placement). If that
     * symptom reappears, flip this false to confirm/deny in one change.
     */
    public static boolean FLUSH_DEST_UPLOADS = true;

    /**
     * Flush every live dest renderer's STAGED section-mesh uploads to the GPU — the missing
     * tail of vanilla {@code LevelRenderer.render()} (LevelRenderer.java:257-265) for the
     * stencil-direct path, which draws via raw {@code renderGroup} and therefore never runs it.
     *
     * <p>26.2 stages compiled meshes ({@code addAllocation}) and only swaps them in when
     * {@code uploadTerrainBuffersToGpu()} runs. Without this, dest meshes became drawable only
     * when the staging buffer OVERFLOWED (the emergency flush in
     * {@code SectionRenderDispatcher.addSectionBuffersToUberBuffer}) — bulk terrain appeared in
     * chunky bursts and small incremental rebuilds (mirrored block updates) never appeared.
     * Flushing every frame gives the smooth near-first fill the instant-portal-view work needs.
     *
     * <p>MUST run OUTSIDE the main framegraph (called from {@code GameRenderer.renderLevel}
     * HEAD via {@code StencilPortalRenderer.prepareDestinationRender}); running it mid-pass
     * resizes GPU buffers the in-flight pass has bound (proven screen-flashing). Cheap no-op
     * when nothing is staged.
     */
    public static void flushDestStagedUploads() {
        if (!FLUSH_DEST_UPLOADS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        ResourceKey<Level> activeDim = mc.level.dimension();
        for (Map.Entry<ResourceKey<Level>, LevelRenderer> e : renderers.entrySet()) {
            ResourceKey<Level> dim = e.getKey();
            if (dim.equals(activeDim)) continue; // main renderer flushes itself in render()
            if (!isDestScopeLive(dim)) continue; // paused dim: nothing rendering from it
            LevelRenderer renderer = e.getValue();
            if (renderer == null) continue;
            try {
                net.minecraft.client.renderer.chunk.SectionRenderDispatcher dispatcher =
                    renderer.sectionRenderDispatcher();
                if (dispatcher == null) continue;
                dispatcher.lock();
                try {
                    dispatcher.uploadTerrainBuffersToGpu();
                } finally {
                    dispatcher.unlock();
                }
            } catch (Throwable t) {
                // Upload hiccups must never kill the frame; worst case meshes stay staged.
            }
        }
    }

    public static void advanceCompilePipelines() {
        // Yield to the main render during the post-teleport reload. While the entered
        // dimension is streaming + meshing its far chunks (the promote bridge window),
        // pause the secondary mesh pump so the shared chunk-builder worker pool serves
        // the MAIN render — otherwise the secondaries' compiles starve it and the
        // reload stutters. The pump resumes (resumes pre-warming dests) once the
        // entered dim has settled.
        if (com.warwa.seamlessportals.render.PortalContextSwitch.isPromoteBridgeActive()) {
            return;
        }
        // Sodium gate: under Sodium, our vanilla-pipeline compile pump
        // ({@code RenderSection.rebuildSectionAsync}) is a no-op
        // because Sodium replaces {@code SectionRenderDispatcher} with
        // its own builder. Skip entirely — Sodium's own per-frame
        // chunk-build loop on the secondary {@code LevelRenderer}
        // handles the equivalent work for cached dims.
        if (com.warwa.seamlessportals.compat.SodiumCompat.isSodiumLoaded()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        ResourceKey<Level> activeDim = mc.level.dimension();
        int remaining = COMPILE_PUMP_BUDGET_PER_TICK;

        // IP scope: only pump compiles on a secondary while a portal looking into it
        // is near the player. A paused (far-away) dim wastes no chunk-builder budget.
        refreshDestScopes();

        for (Map.Entry<ResourceKey<Level>, LevelRenderer> entry : renderers.entrySet()) {
            if (remaining <= 0) break;
            ResourceKey<Level> dim = entry.getKey();
            if (dim == activeDim) continue;
            if (!isDestScopeLive(dim)) continue; // paused: no nearby portal
            ClientLevel level = levels.get(dim);
            remaining -= advanceOneRenderer(dim, entry.getValue(), level, remaining);
        }
    }

    /**
     * Inner-radius priority. Vanilla's {@code compileSections} sync-
     * rebuilds dirty sections within ~28 blocks (~2 chunks). If the
     * cached renderer always has its inner-3-chunk-radius sections
     * compiled, vanilla finds nothing to sync-rebuild post-teleport
     * and the player sees no FPS drop.
     *
     * <p>This radius is tighter than the outer pump radius
     * ({@link #COMPILE_PUMP_RADIUS_CHUNKS}) and runs as a separate
     * priority pass.
     */
    private static final int COMPILE_PUMP_PRIORITY_RADIUS_CHUNKS = 3;
    private static final int COMPILE_PUMP_PRIORITY_RADIUS_SQ =
        COMPILE_PUMP_PRIORITY_RADIUS_CHUNKS * COMPILE_PUMP_PRIORITY_RADIUS_CHUNKS;

    private static int advanceOneRenderer(
            ResourceKey<Level> dim,
            LevelRenderer renderer,
            ClientLevel level,
            int budget) {
        if (renderer == null || level == null || budget <= 0) return 0;
        net.minecraft.client.renderer.ViewArea viewArea =
            ((com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin) renderer)
                .seamlessportals$getViewArea();
        if (viewArea == null) return 0;

        net.minecraft.core.SectionPos viewCenter = viewArea.getCameraSectionPos();
        int camSecX = viewCenter.x();
        int camSecZ = viewCenter.z();

        net.minecraft.client.renderer.chunk.RenderRegionCache cache =
            new net.minecraft.client.renderer.chunk.RenderRegionCache();

        int scheduled = 0;

        // PASS 1: priority — inner radius (≤3 chunks). Prefer these (run first) so the
        // landing area is meshed before the outer ring, but CAP by the shared budget:
        // each scheduleCompileIfDirty does a synchronous ~1ms createRegion snapshot ON the
        // render/tick thread, so an uncapped pass over an all-dirty inner ring (post-load)
        // was itself a render-thread stall (Option-2 trim toward IP, which has no such pump).
        for (net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection section
                : viewArea.sections) {
            if (section == null) continue;
            if (scheduled >= budget) break;
            long sectionNode = section.getSectionNode();
            int sx = net.minecraft.core.SectionPos.x(sectionNode);
            int sz = net.minecraft.core.SectionPos.z(sectionNode);
            int dx = sx - camSecX;
            int dz = sz - camSecZ;
            if (dx * dx + dz * dz > COMPILE_PUMP_PRIORITY_RADIUS_SQ) continue;
            if (!level.getChunkSource().hasChunk(sx, sz)) continue;
            // 26.2: dirty state + async compile moved to the LevelExtractor's
            // SectionUpdateTracker (D2).
            if (!scheduleCompileIfDirty(extractors.get(dim), level, cache, section)) continue;
            scheduled++;
        }

        // PASS 2: outer radius (≤ compilePumpRadiusSq() = portalRenderDistance², i.e.
        // the FULL portal-view radius — NOT the old fixed 8 chunks; that stale figure
        // misdirected a root-cause hunt on 2026-07-07). Bounded by budget.
        for (net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection section
                : viewArea.sections) {
            if (section == null) continue;
            if (scheduled >= budget) break;
            long sectionNode = section.getSectionNode();
            int sx = net.minecraft.core.SectionPos.x(sectionNode);
            int sz = net.minecraft.core.SectionPos.z(sectionNode);
            int dx = sx - camSecX;
            int dz = sz - camSecZ;
            int distSq = dx * dx + dz * dz;
            // Skip inner radius (already handled by Pass 1).
            if (distSq <= COMPILE_PUMP_PRIORITY_RADIUS_SQ) continue;
            if (distSq > compilePumpRadiusSq()) continue;
            if (!level.getChunkSource().hasChunk(sx, sz)) continue;
            // 26.2: dirty state + async compile moved to the LevelExtractor's
            // SectionUpdateTracker (D2).
            if (!scheduleCompileIfDirty(extractors.get(dim), level, cache, section)) continue;
            scheduled++;
        }
        return scheduled;
    }

    /**
     * End the render frame of every {@link RenderBuffers} the mod created that vanilla
     * does not own — the missing 1:1 counterpart of {@code GameRenderer.render():447}
     * ({@code this.renderBuffers.endFrame()}), which only covers the GameRenderer's OWN
     * buffers.
     *
     * <p><b>THE VRAM LEAK THIS FIXES (2026-07-05):</b> each per-secondary
     * {@code new RenderBuffers(4)} (see {@link #createRenderer}) owns a
     * {@code StagedVertexBuffer} whose {@code GpuBufferPool.acquire} creates a fresh
     * ≥256KB GPU buffer whenever nothing has been recycled — and recycling happens ONLY
     * in {@code endFrame()} (fence {@code usedThisFrame} → recycle when the GPU passes;
     * StagedVertexBuffer.java:326-336). Without a per-frame endFrame, every frame that
     * draws dest entities/block-entities through a portal parks new GPU buffers in
     * {@code usedThisFrame} forever: tens of MB/s of VRAM while a portal is in view.
     * After ~2 minutes the driver hits memory exhaustion and synchronously pages inside
     * arbitrary GL calls — the logged 150-193ms [SEAMLESS STUCK] stalls and 3-5.6s
     * [SEAMLESS FREEZE]s, all RUNNABLE inside {@code nglDrawElementsInstancedBaseVertex}
     * with ZERO GC, first in the portal pass, then the main pass and GUI, plus the ~3s
     * shutdown {@code glDeleteFramebuffers} freeze tearing the bloated space down.
     *
     * <p>Coverage (identity-deduped, skipping the one vanilla endFrames itself):
     * <ol>
     *   <li>The CURRENT {@code mc.levelRenderer}'s buffers — after a crossing the
     *       promoted renderer draws the MAIN world out of its own ex-dest buffers
     *       (promotion never swaps {@code gameRenderer.renderBuffers}), so vanilla's
     *       endFrame misses the active world's buffers entirely.</li>
     *   <li>Every live secondary in {@link #renderers} (the demoted vanilla renderer
     *       shares the GameRenderer's buffers — the identity skip avoids a double
     *       endFrame there).</li>
     * </ol>
     * The pooled sub-render buffers are ended separately by
     * {@link com.warwa.seamlessportals.render.PortalRenderBuffersPool#endFramePooled()}.
     *
     * <p>Called from {@code GameRenderer.render} TAIL (render thread, after vanilla's
     * own endFrame — the same lifecycle point). Cheap no-op for idle dims: endFrame
     * only fences when {@code usedThisFrame} is non-empty.
     */
    public static void endSecondaryRenderFrames() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) return;

        java.util.Set<RenderBuffers> done =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        RenderBuffers vanillaOwned =
            ((com.warwa.seamlessportals.mixin.client.GameRendererAccessorMixin) mc.gameRenderer)
                .seamlessportals$getRenderBuffers();
        if (vanillaOwned != null) {
            done.add(vanillaOwned); // GameRenderer.render:447 endFrames this one itself
        }

        endRendererBuffersOnce(mc.levelRenderer, done);
        for (LevelRenderer renderer : renderers.values()) {
            endRendererBuffersOnce(renderer, done);
            // Sibling frame-end vanilla also only does for the CURRENT renderer
            // (Minecraft.runTick:1336 → LevelRenderer.endFrame → cloudRenderer.endFrame
            // → cloud UBO ring rotate): a secondary that drew clouds in the portal view
            // would otherwise re-map the SAME ring slice every frame while the GPU may
            // still be reading it (implicit-sync stall / cloud flicker). Skip the
            // installed renderer — vanilla ends it itself right after render().
            if (renderer != null && renderer != mc.levelRenderer) {
                renderer.endFrame();
            }
        }
    }

    private static void endRendererBuffersOnce(LevelRenderer renderer, java.util.Set<RenderBuffers> done) {
        if (renderer == null) return;
        RenderBuffers buffers =
            ((LevelRendererAccessorMixin) renderer).seamlessportals$getRenderBuffers();
        if (buffers == null || !done.add(buffers)) return;
        buffers.endFrame();
    }

    /**
     * Clean up all registered renderers and levels. Called on disconnect/quit.
     *
     * <p>Note: the entry registered by {@link #initializeIfNeeded()} holds a
     * reference to the vanilla {@code mc.levelRenderer}. We call
     * {@code setLevel(null)} on it to release its ViewArea but do NOT call
     * {@code renderer.close()} — Minecraft itself owns that lifecycle.
     * Secondary renderers we created via {@link #createRenderer} are safe
     * to close since we own their lifecycle.
     *
     * <p>In practice the vanilla renderer cannot be distinguished from
     * secondaries by looking at the map alone, so we conservatively skip
     * {@code close()} on all — relying on Minecraft's own cleanup to
     * release GPU resources when appropriate.
     */
    public static void cleanup() {
        // 26.2: setLevel(null) moved off LevelRenderer onto the per-dimension
        // LevelExtractor (D4) — release each secondary's ViewArea via its
        // extractor. The vanilla mc.levelRenderer's extractor is mc.levelExtractor
        // (not in our map), so Minecraft's own cleanup handles that one.
        for (LevelExtractor extractor : extractors.values()) {
            try {
                extractor.setLevel(null);
            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.error("[SEAMLESS PHASE2] Error cleaning up renderer", e);
            }
        }
        for (ParticleEngine particleEngine : particleEngines.values()) {
            try {
                particleEngine.setLevel(null);
            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.error("[SEAMLESS PHASE2] Error cleaning up particle engine", e);
            }
        }
        renderers.clear();
        levels.clear();
        extractors.clear();
        particleEngines.clear();
        com.warwa.seamlessportals.render.PortalContextSwitch.resetPerDimRenderState();
        initialized = false;
    }
}
