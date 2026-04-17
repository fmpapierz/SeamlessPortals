package com.warwa.seamlessportals.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
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

    private static final Map<ResourceKey<Level>, LevelRenderer> renderers = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, ClientLevel> levels = new ConcurrentHashMap<>();

    /**
     * Dormant former-primary renderers, keyed by dimension. When the player
     * leaves a dimension where the primary was the vanilla-main LevelRenderer
     * (shared {@code mc.renderBuffers} with {@code GameRenderer}), we cannot
     * reuse it as a secondary for portal viewing — the shared buffer sources
     * would collide with the main render's own use inside
     * {@code AFTER_TRANSLUCENT_TERRAIN} → "Buffer source must not be empty".
     *
     * Instead, stash it here. Compiled section meshes + ViewArea are
     * preserved untouched, so a future return to the dim can promote the
     * stashed renderer straight back to primary and skip all chunk rebuild.
     * While stashed, the renderer is not involved in any render path — we
     * create a fresh PWM-style secondary (with separate RenderBuffers) if
     * the dim needs portal-viewing coverage.
     */
    private static final Map<ResourceKey<Level>, LevelRenderer> dormantPrimaries = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, ClientLevel> dormantLevels = new ConcurrentHashMap<>();

    /**
     * Renderers that were promoted FROM the secondary {@link #renderers} map.
     * These are PWM-style (separate RenderBuffers, separate FeatureRenderDispatcher)
     * and therefore safe to re-demote back into the secondary map on dim
     * change — they won't cause buffer conflicts during portal viewing.
     *
     * Renderers NOT in this set (i.e. the vanilla main or a once-dormant that
     * was re-promoted) go into {@link #dormantPrimaries} on demote instead.
     */
    private static final Set<LevelRenderer> promotedSecondaries =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Promotion outcome: the renderer + level to install as Minecraft's primary. */
    public record Promotion(LevelRenderer renderer, ClientLevel level) {}

    /**
     * Get or create a LevelRenderer + ClientLevel for the given dimension.
     * Creates the secondary rendering pipeline on first call.
     *
     * <p>History note (2026-04-17): An earlier version of this method pulled
     * entries out of {@link #dormantPrimaries} here to preserve compiled
     * meshes when the player looked back at a just-left dim. That broke
     * vanilla rendering — the dormant vanilla-main's {@code renderBuffers}
     * is {@code mc.renderBuffers}, which {@code GameRenderer} accesses
     * directly for several passes. Using it concurrently as a portal-view
     * secondary caused subtle buffer corruption (no crash, but visible
     * geometry artifacts on the primary view). Reverted to always-fresh
     * allocation; the trade-off is the ~8s compile wait on first return
     * to a previously-visited dim, documented in
     * {@code phase_abc_async_compile_pipeline.md}.
     */
    public static LevelRenderer getOrCreateRenderer(ResourceKey<Level> dimension) {
        return renderers.computeIfAbsent(dimension, PortalWorldManager::createRenderer);
    }

    public static ClientLevel getLevel(ResourceKey<Level> dimension) {
        return levels.get(dimension);
    }

    public static boolean hasRenderer(ResourceKey<Level> dimension) {
        return renderers.containsKey(dimension);
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

        SeamlessPortalsConstants.LOGGER.info("[SEAMLESS PHASE2] Creating secondary renderer for {}", dimension.identifier());

        try {
            GameRenderState gameRenderState = mc.gameRenderer.getGameRenderState();

            // Create SEPARATE RenderBuffers for the secondary renderer.
            // The main renderer's buffers are in use during AFTER_TRANSLUCENT_TERRAIN
            // (when our portal rendering runs). Sharing them causes
            // "Buffer source must not be empty" crashes when the 1:1 camera
            // position triggers entity rendering.
            RenderBuffers destRenderBuffers = new RenderBuffers(4);

            // Create SEPARATE FeatureRenderDispatcher (has mutable per-frame state)
            SubmitNodeStorage destSubmitNodes = new SubmitNodeStorage();
            FeatureRenderDispatcher destFeatureDispatcher = new FeatureRenderDispatcher(
                destSubmitNodes,
                mc.getModelManager(),
                destRenderBuffers.bufferSource(),
                mc.getAtlasManager(),
                destRenderBuffers.outlineBufferSource(),
                destRenderBuffers.crumblingBufferSource(),
                mc.font,
                gameRenderState
            );

            // Create secondary LevelRenderer with its OWN RenderBuffers
            LevelRenderer destRenderer = new LevelRenderer(
                mc,
                mc.getEntityRenderDispatcher(),
                mc.getBlockEntityRenderDispatcher(),
                destRenderBuffers,       // SEPARATE — avoids buffer conflicts
                gameRenderState,         // SHARED
                destFeatureDispatcher    // SEPARATE
            );

            // Phase C follow-up (2026-04-17): if a dormant primary's level
            // exists for this dim, REUSE it instead of creating an empty
            // ClientLevel. The dormant level holds every chunk that was
            // loaded while the player was in that dim — often the entire
            // server render distance. A fresh ClientLevel has zero chunks
            // and only gets the 49 that {@code feedExistingChunks} copies
            // from {@code RemoteChunkManager}, so portal-view coverage was
            // capped at 7×7 chunks around the destination portal.
            //
            // Two renderers (dormant + new PWM secondary) pointing at the
            // same ClientLevel is safe: the dormant isn't ticking, so only
            // our new renderer observes + compiles the level's chunks.
            // When the dormant is later promoted back to primary, its
            // viewArea and compiled meshes are still valid because nothing
            // touched them.
            ClientLevel destLevel = dormantLevels.get(dimension);
            boolean reusedDormantLevel = destLevel != null;

            if (destLevel == null) {
                Holder<DimensionType> dimensionType = mc.level.registryAccess()
                    .lookupOrThrow(Registries.DIMENSION_TYPE)
                    .getOrThrow(getDimensionTypeKey(dimension));

                ClientLevel.ClientLevelData levelData = new ClientLevel.ClientLevelData(
                    Difficulty.NORMAL, false, false
                );

                destLevel = new ClientLevel(
                    mc.getConnection(),
                    levelData,
                    dimension,
                    dimensionType,
                    8,  // render distance for portal view (matches portalRenderDistance)
                    8,  // simulation distance
                    destRenderer,
                    false,
                    0L,
                    mc.level.getSeaLevel()
                );
            }

            // Give the secondary renderer its OWN LevelRenderState so that
            // extractLevel() doesn't corrupt the main renderer's shared state.
            // MC 26.1.2 shares LevelRenderState via GameRenderState (line 190),
            // but IP's architecture requires each renderer to have isolated state.
            ((LevelRendererAccessorMixin) destRenderer).seamlessportals$setLevelRenderState(
                new LevelRenderState());

            // Connect renderer to level (triggers chunk infrastructure creation)
            destRenderer.setLevel(destLevel);

            // Initialize sky renderer + entity outline target.
            // onResourceManagerReload() creates SkyRenderer (line 218 in LevelRenderer.java).
            // Without this, extractLevel() crashes with NPE on skyRenderer.extractRenderState().
            destRenderer.onResourceManagerReload(mc.getResourceManager());

            levels.put(dimension, destLevel);

            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PHASE2] Secondary renderer created for {} (sections={}, reusedDormantLevel={})",
                dimension.identifier(), destLevel.getSectionsCount(), reusedDormantLevel
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
    private static final int FEED_RADIUS_CHUNKS = 3; // 7x7 = 49 chunks per portal

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
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PHASE2] feedExistingChunks: no portal origins found for {}, skipping",
                dimension.identifier());
            return;
        }

        java.util.Set<net.minecraft.world.level.ChunkPos> alreadyQueued = new java.util.HashSet<>();
        int enqueued = 0;
        for (var origin : portalOrigins) {
            int cx = origin.getX() >> 4;
            int cz = origin.getZ() >> 4;
            for (int dx = -FEED_RADIUS_CHUNKS; dx <= FEED_RADIUS_CHUNKS; dx++) {
                for (int dz = -FEED_RADIUS_CHUNKS; dz <= FEED_RADIUS_CHUNKS; dz++) {
                    var pos = new net.minecraft.world.level.ChunkPos(cx + dx, cz + dz);
                    if (!alreadyQueued.add(pos)) continue;
                    if (!chunks.containsKey(pos)) continue;
                    pendingFeeds.add(new PendingFeed(dimension, pos));
                    enqueued++;
                }
            }
        }
        if (enqueued > 0) {
            feedingDims.add(dimension);
        }

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS PHASE2] Queued {} existing chunks (radius {} around {} portal(s)) for async feed to level {}",
            enqueued, FEED_RADIUS_CHUNKS, portalOrigins.size(), dimension.identifier());
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

                // Mark sections dirty on the secondary renderer so meshes rebuild.
                LevelRenderer destRenderer = renderers.get(feed.dim);
                if (destRenderer != null) {
                    int minSectionY = destLevel.getMinSectionY();
                    for (int sy = 0; sy < sectionsForChunk.length; sy++) {
                        destRenderer.setSectionDirtyWithNeighbors(
                            chunkX, minSectionY + sy, chunkZ);
                    }
                }
            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.error(
                    "[SEAMLESS PHASE2] drainPendingFeeds: Failed chunk [{},{}] in {}",
                    chunkX, chunkZ, feed.dim.identifier(), e);
            }

            processed++;
        }

        // Log when a dimension's feed queue fully drains.
        for (ResourceKey<Level> d : feedingDims.toArray(new ResourceKey[0])) {
            boolean anyStillPending = false;
            for (PendingFeed pf : pendingFeeds) {
                if (pf.dim == d) { anyStillPending = true; break; }
            }
            if (!anyStillPending) {
                feedingDims.remove(d);
                SeamlessPortalsConstants.LOGGER.info(
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

        if (renderer != null) {
            try {
                renderer.setLevel(null);
                renderer.close();
            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.error(
                    "[SEAMLESS PHASE2] Error removing renderer for {}", dimension.identifier(), e);
            }
        }
    }

    /**
     * Promote a cached renderer+level for {@code dim} to be installed as
     * {@code mc.levelRenderer} / {@code mc.level}.
     *
     * Lookup order:
     * <ol>
     *   <li>{@link #dormantPrimaries} — the previous primary for this dim.
     *       Meshes cover the entire view distance from when the player was
     *       last there, so promotion gives the best visual continuity on
     *       return trips.</li>
     *   <li>{@link #renderers} — a PWM secondary built up from portal views.
     *       Meshes cover a tighter radius around the portal, but they are
     *       ready to display the destination the instant we arrive.</li>
     * </ol>
     *
     * The promoted renderer's {@code LevelRenderState} is re-pointed at the
     * supplied shared state so that {@code GameRenderer.renderLevel} — which
     * reads {@code gameRenderState.levelRenderState.chunkSectionsToRender}
     * — sees what the renderer's {@code extractLevel} writes. Without this
     * re-point, a newly-installed primary would render black terrain because
     * its isolated state is never read by the outer framegraph.
     *
     * Returns {@code null} when neither cache has an entry; the caller then
     * falls back to the vanilla dim-change flow (new ClientLevel + allChanged).
     */
    public static Promotion promoteToMain(
            ResourceKey<Level> dim,
            LevelRenderState sharedState) {

        // Prefer the dormant-primary cache (fuller mesh coverage).
        LevelRenderer dormantRenderer = dormantPrimaries.remove(dim);
        ClientLevel dormantLevel = dormantLevels.remove(dim);
        if (dormantRenderer != null && dormantLevel != null) {
            ((LevelRendererAccessorMixin) dormantRenderer)
                .seamlessportals$setLevelRenderState(sharedState);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PHASE2] Promoted dormant-primary → mc.levelRenderer for {}",
                dim.identifier());
            return new Promotion(dormantRenderer, dormantLevel);
        }

        // Fall back to the portal-view secondary.
        LevelRenderer secondaryRenderer = renderers.remove(dim);
        ClientLevel secondaryLevel = levels.remove(dim);
        if (secondaryRenderer != null && secondaryLevel != null) {
            ((LevelRendererAccessorMixin) secondaryRenderer)
                .seamlessportals$setLevelRenderState(sharedState);
            promotedSecondaries.add(secondaryRenderer);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PHASE2] Promoted PWM secondary → mc.levelRenderer for {}",
                dim.identifier());
            return new Promotion(secondaryRenderer, secondaryLevel);
        }

        return null;
    }

    /**
     * Stash a renderer+level that was previously the primary for {@code dim}.
     * Where it goes depends on whether it has separate RenderBuffers:
     * <ul>
     *   <li>A PWM-style renderer (tracked in {@link #promotedSecondaries})
     *       can safely re-enter the secondary {@link #renderers} map and be
     *       used again for portal viewing of this dim.</li>
     *   <li>A vanilla-main-style renderer (shared {@code mc.renderBuffers})
     *       must go to {@link #dormantPrimaries} only — using it as a
     *       secondary during another frame's portal render would try to
     *       reuse the main render's active buffer source.</li>
     * </ul>
     *
     * Either way the renderer's {@code ViewArea} and compiled section meshes
     * are untouched, so a future promotion back to primary skips any rebuild.
     */
    public static void demoteFromMain(
            ResourceKey<Level> dim,
            LevelRenderer renderer,
            ClientLevel level) {

        if (promotedSecondaries.remove(renderer)) {
            // Was a PWM secondary before promotion — safe to return to the
            // secondary pool. Give it an isolated LevelRenderState so that
            // portal-view extractLevel calls don't clobber the new primary's
            // shared state.
            ((LevelRendererAccessorMixin) renderer)
                .seamlessportals$setLevelRenderState(new LevelRenderState());
            renderers.put(dim, renderer);
            levels.put(dim, level);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS PHASE2] Demoted promoted-secondary → PWM secondary for {}",
                dim.identifier());
            return;
        }

        // Vanilla-main style: shared RenderBuffers. Keep it dormant so its
        // meshes survive for a future re-promotion, but do not expose it to
        // portal-view rendering — the shared buffer sources would conflict
        // with the outer frame's own use of them.
        dormantPrimaries.put(dim, renderer);
        dormantLevels.put(dim, level);
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS PHASE2] Demoted vanilla-main → dormant for {}",
            dim.identifier());
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
    private static final int COMPILE_PUMP_RADIUS_CHUNKS = 8;
    private static final int COMPILE_PUMP_RADIUS_SQ =
        COMPILE_PUMP_RADIUS_CHUNKS * COMPILE_PUMP_RADIUS_CHUNKS;
    private static final int COMPILE_PUMP_BUDGET_PER_TICK = 24;

    public static void advanceCompilePipelines() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        ResourceKey<Level> activeDim = mc.level.dimension();
        int remaining = COMPILE_PUMP_BUDGET_PER_TICK;

        for (Map.Entry<ResourceKey<Level>, LevelRenderer> entry : renderers.entrySet()) {
            if (remaining <= 0) break;
            ResourceKey<Level> dim = entry.getKey();
            if (dim == activeDim) continue;
            ClientLevel level = levels.get(dim);
            remaining -= advanceOneRenderer(dim, entry.getValue(), level, remaining);
        }
    }

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
        for (net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection section
                : viewArea.sections) {
            if (section == null) continue;
            if (scheduled >= budget) break;
            long sectionNode = section.getSectionNode();
            int sx = net.minecraft.core.SectionPos.x(sectionNode);
            int sz = net.minecraft.core.SectionPos.z(sectionNode);
            if (!level.getChunkSource().hasChunk(sx, sz)) continue;
            if (!section.isDirty()) continue;
            int dx = sx - camSecX;
            int dz = sz - camSecZ;
            if (dx * dx + dz * dz > COMPILE_PUMP_RADIUS_SQ) continue;
            section.rebuildSectionAsync(cache);
            section.setNotDirty();
            scheduled++;
        }
        return scheduled;
    }

    /**
     * Clean up all secondary renderers and levels.
     */
    public static void cleanup() {
        for (LevelRenderer renderer : renderers.values()) {
            try {
                renderer.setLevel(null);
                renderer.close();
            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.error("[SEAMLESS PHASE2] Error cleaning up renderer", e);
            }
        }
        renderers.clear();
        levels.clear();
        // Dormant primaries hold references to the vanilla main's ViewArea +
        // SectionRenderDispatcher. Release them on full teardown.
        for (LevelRenderer renderer : dormantPrimaries.values()) {
            try {
                renderer.setLevel(null);
                // Do NOT call renderer.close() on the vanilla main — Minecraft
                // itself owns its lifecycle and calls close() in its own close().
                // If this is a once-dormant-now-detached primary, setLevel(null)
                // releases the ViewArea; that's enough.
            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.error("[SEAMLESS PHASE2] Error cleaning up dormant renderer", e);
            }
        }
        dormantPrimaries.clear();
        dormantLevels.clear();
        promotedSecondaries.clear();
    }
}
