package qouteall.imm_ptl.core.render;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.chunk_loading.PerformanceLevel;
import qouteall.imm_ptl.core.ducks.IERenderSection;
import qouteall.imm_ptl.core.miscellaneous.ClientPerformanceMonitor;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.nether_portal.BlockTraverse;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.Stack;

/**
 * Discover visible sections by breadth-first traverse, for portal rendering.
 * Probably faster than vanilla (because no cave culling and garbage object allocation).
 * No multi-threading because portal rendering camera views are very dynamic which is not suitable for that.
 * No cave culling because vanilla has a multithreaded cave culling that's hard to integrate with portal rendering.
 * The cave culling is conditionally enabled with Sodium: {@link PortalRendering#shouldEnableSodiumCaveCulling()}
 *
 * <p><b>A3 reconcile (S11-A note §5; current-mod-render §5 / AMBIGUOUS A3 resolved PORT-FORWARD):</b>
 * IP's discovery is PURE traversal — it only fills {@code resultHolder}. On 26.2 that is not enough:
 * there is NO vanilla driver that compiles the sections of a HAND-FED secondary renderer
 * ({@code compileSections} is private and its one-shot dirty flag strands sections — the OW-holes /
 * trees-before-ground root cause, memories {@code ow-holes-consumed-compile-queue} +
 * {@code walking-limbo-seed-overclaim}). This port therefore carries IP's traversal VERBATIM (Chebyshev
 * cube bound, {@link PerformanceLevel} render distance, portal-shape-modified seed, bottom/top-layer
 * seeding, vanilla-frustum cull, the {@link IERenderSection} mark, {@code rawFetch}, the list pool,
 * {@code init}) AND folds in the mod's PROVEN required-26.2 accept step (own-chunk {@code hasChunk}
 * draw gate + budgeted async {@code compileAsync}) — the one mechanism with no IP counterpart. The
 * fold is ARMED per run by the driver ({@link #armCompileScheduling}); UNARMED it is byte-for-byte
 * IP behaviour, so the pure IP callers see no change. The mod's cylinder bound / inner-cull cone are
 * NOT ported — IP's cube + the passed portal-clipped frustum + {@link FrustumCuller} (at the render
 * layer) are the faithful forms. See {@code fragments/S11B-viewarea.md} (§VisibleSectionDiscovery).
 */
@Environment(EnvType.CLIENT)
public class VisibleSectionDiscovery {

    private static ImmPtlViewArea builtChunks;
    private static Frustum vanillaFrustum;
    private static ObjectArrayList<RenderSection> resultHolder;
    private static final ArrayDeque<RenderSection> tempQueue = new ArrayDeque<>();
    private static SectionPos cameraSectionPos;
    private static long timeMark;
    private static int viewDistance;

    // --- 26.2 required-adaptation (A3): budgeted async-compile scheduling context. ARMED per run by
    // the driver (S12 renderer) before discoverVisibleSections; null destLevel => pure IP discovery. ---
    @Nullable
    private static ClientLevel scratchDestLevel;
    @Nullable
    private static SectionUpdateTracker scratchSut;
    @Nullable
    private static RenderRegionCache scratchCache;
    @Nullable
    private static Set<Long> scratchSchedSet;
    private static long compileBudgetNs;
    private static long compileStartNs;
    private static int scheduledCount;
    // S14.51 F1: true when the armed tracker is the MAIN dim's (nested/return pass) — the fold
    // then compiles UNCOMPILED-only and never consumes dirty marks (they belong to the main
    // extract).
    private static boolean scratchMainDimArm;

    /**
     * Arm the folded-in compile scheduling for the NEXT {@link #discoverVisibleSections} call (A3).
     * The driver (S12 renderer / context switch) calls this where IP relied on vanilla's — now
     * stranding — compile path. It is auto-disarmed at the end of that discovery run so a later pure
     * IP caller cannot inherit a stale context.
     *
     * @param destLevel  the secondary level whose chunks gate drawing + feed {@code createRegion}
     * @param sut        the per-dim {@link SectionUpdateTracker} (null => schedule UNCOMPILED only)
     * @param cache      a per-frame {@link RenderRegionCache}
     * @param schedSet   the driver's one-shot "already scheduled UNCOMPILED" guard set
     * @param budgetNs   the per-run compile-scheduling time budget
     */
    public static void armCompileScheduling(
        ClientLevel destLevel, @Nullable SectionUpdateTracker sut,
        RenderRegionCache cache, Set<Long> schedSet, long budgetNs
    ) {
        armCompileScheduling(destLevel, sut, cache, schedSet, budgetNs, false);
    }

    /**
     * S14.51 fix F1 (trace wf_1e07ce4b-f53 tracer B, HIGH): {@code isMainDimArm} = this arm's
     * tracker IS the MAIN dim's (a nested/return pass whose dest dim == the main dim). The fold
     * must then never act as if it OWNS the tracker: dirty marks belong to the MAIN extract
     * (next frame, vanilla semantics) — consuming them here was mid-frame mark THEFT on the
     * origin dim (the standstill nether compQ churn, and a shadow feeder on "both dims"). The
     * load-bearing half stays: UNCOMPILED behind-player sections seen through a return window
     * still compile (the ow-holes rule), just without touching the dirty flags.
     */
    public static void armCompileScheduling(
        ClientLevel destLevel, @Nullable SectionUpdateTracker sut,
        RenderRegionCache cache, Set<Long> schedSet, long budgetNs, boolean isMainDimArm
    ) {
        scratchDestLevel = destLevel;
        scratchSut = sut;
        scratchCache = cache;
        scratchSchedSet = schedSet;
        compileBudgetNs = budgetNs;
        scratchMainDimArm = isMainDimArm;
    }

    /** Number of async compiles scheduled during the last discovery run (diagnostics). */
    public static int getLastScheduledCount() {
        return scheduledCount;
    }

    public static void discoverVisibleSections(
        ClientLevel world,
        ImmPtlViewArea builtChunks_,
        Camera camera,
        Frustum vanillaFrustum_,
        ObjectArrayList<RenderSection> resultHolder_
    ) {
        builtChunks = builtChunks_;
        vanillaFrustum = vanillaFrustum_;
        resultHolder = resultHolder_;

        try {
            resultHolder.clear();
            tempQueue.clear();

            updateViewDistance();

            timeMark = System.nanoTime();
            compileStartNs = timeMark;
            scheduledCount = 0;

            Vec3 cameraPos = camera.position();
            vanillaFrustum.prepare(cameraPos.x, cameraPos.y, cameraPos.z);
            cameraSectionPos = SectionPos.of(BlockPos.containing(cameraPos));

            SectionPos modifiedVisibleSectionIterationOrigin = null;
            if (PortalRendering.isRendering()) {
                Portal renderingPortal = PortalRendering.getRenderingPortal();
                modifiedVisibleSectionIterationOrigin = renderingPortal.getPortalShape()
                    .getModifiedVisibleSectionIterationOrigin(renderingPortal, cameraPos);
            }

            if (modifiedVisibleSectionIterationOrigin != null) {
                checkSection(
                    modifiedVisibleSectionIterationOrigin.getX(),
                    modifiedVisibleSectionIterationOrigin.getY(),
                    modifiedVisibleSectionIterationOrigin.getZ(),
                    true
                );
            }
            else if (cameraPos.y < world.getMinY()) {  // 26.2: getMinBuildHeight() -> getMinY() (C40)
                discoverBottomOrTopLayerVisibleChunks(builtChunks.minSectionY);
            }
            else if (cameraPos.y > world.getMaxY()) {  // 26.2: getMaxBuildHeight() -> getMaxY() (C40)
                discoverBottomOrTopLayerVisibleChunks(builtChunks.endSectionY - 1);
            }
            else {
                checkSection(
                    cameraSectionPos.x(),
                    cameraSectionPos.y(),
                    cameraSectionPos.z(),
                    true
                );
            }

            // breadth-first searching
            while (!tempQueue.isEmpty()) {
                RenderSection curr = tempQueue.poll();
                // 26.2: RenderSection.getOrigin() -> getRenderOrigin() (C29)
                int cx = SectionPos.blockToSectionCoord(curr.getRenderOrigin().getX());
                int cy = SectionPos.blockToSectionCoord(curr.getRenderOrigin().getY());
                int cz = SectionPos.blockToSectionCoord(curr.getRenderOrigin().getZ());

                checkSection(cx + 1, cy, cz, false);
                checkSection(cx - 1, cy, cz, false);
                checkSection(cx, cy + 1, cz, false);
                checkSection(cx, cy - 1, cz, false);
                checkSection(cx, cy, cz + 1, false);
                checkSection(cx, cy, cz - 1, false);
            }

            // avoid memory leak (IP verbatim — kept inside the try to preserve byte-level IP fidelity)
            resultHolder = null;
            builtChunks = null;
            vanillaFrustum = null;
        }
        finally {
            // disarm the folded 26.2 compile-scheduling context on EVERY exit path — normal return AND
            // an exception mid-run (e.g. scratchCache.createRegion, a portal-shape callback) — so a
            // later PURE-IP caller cannot inherit a stale destLevel (wrong-level hasChunk gating would
            // silently drop sections + schedule compileAsync against the wrong level's regions). These
            // scratch* fields are the mod's ADDITIVE A3 context with NO 1.21.3 analog, so gating their
            // cleanup on finally is not an IP deviation — IP's own resultHolder/builtChunks/vanillaFrustum
            // cleanup stays inside the try, verbatim.
            scratchDestLevel = null;
            scratchSut = null;
            scratchCache = null;
            scratchSchedSet = null;
            scratchMainDimArm = false;
        }
    }

    private static void updateViewDistance() {
        int distance = WorldRenderInfo.getRenderDistance();
        viewDistance = PerformanceLevel.getPortalRenderingDistance(
            ClientPerformanceMonitor.level, distance
        );
    }

    // NOTE the vanilla frustum culling code may wrongly cull the first section
    private static boolean isVisible(RenderSection builtChunk) {
        AABB box = builtChunk.getBoundingBox();
        return vanillaFrustum.isVisible(box);
    }

    private static void discoverBottomOrTopLayerVisibleChunks(int cy) {
        BlockTraverse.<Object>searchOnPlane(
            cameraSectionPos.x(),
            cameraSectionPos.z(),
            viewDistance - 1,
            (cx, cz) -> {
                checkSection(cx, cy, cz, false);
                return null;
            }
        );
    }

    private static void checkSection(int cx, int cy, int cz, boolean skipFrustumTest) {
        if (Math.abs(cx - cameraSectionPos.x()) > viewDistance) {
            return;
        }
        if (Math.abs(cy - cameraSectionPos.y()) > viewDistance) {
            return;
        }
        if (Math.abs(cz - cameraSectionPos.z()) > viewDistance) {
            return;
        }

        RenderSection builtChunk =
            builtChunks.rawFetch(cx, cy, cz, timeMark);
        if (builtChunk != null) {
            IERenderSection ieRenderSection = (IERenderSection) builtChunk;
            if (ieRenderSection.portal_getMark() != timeMark) {
                ieRenderSection.portal_setMark(timeMark);// mark it checked
                if (skipFrustumTest || isVisible(builtChunk)) {
                    tempQueue.add(builtChunk);// keep flooding THROUGH even if unloaded/uncompiled
                    acceptVisible(builtChunk, cx, cz);
                }
            }
        }
    }

    /**
     * Accept a visible section into {@code resultHolder}. UNARMED (pure IP): unconditional add.
     * ARMED (A3 required-26.2 fold): gate the draw list on the section's OWN chunk being loaded and
     * schedule a budgeted async compile for dirty/UNCOMPILED sections — because 26.2's one-shot dirty
     * flag + private {@code compileSections} strand hand-fed secondary sections. The flood still
     * propagated through this section (tempQueue add already happened in {@link #checkSection}), so
     * loaded terrain beyond a still-loading gap is reached; the per-frame re-run picks a section up
     * once its chunk arrives.
     */
    private static void acceptVisible(RenderSection section, int cx, int cz) {
        if (scratchDestLevel == null) {
            resultHolder.add(section);
            return;
        }

        // own-chunk draw gate (the trees-before-ground fix): unloaded coords must never reach
        // createRegion (their mesh is UNCOMPILED — they draw nothing anyway).
        if (!scratchDestLevel.getChunkSource().hasChunk(cx, cz)) {
            return;
        }

        resultHolder.add(section);

        long node = section.getSectionNode();
        SectionUpdateTracker.SectionDirtyState ds =
            scratchSut != null ? scratchSut.getDirtyState(node) : null;
        boolean uncompiled = section.sectionMesh.get() == CompiledSectionMesh.UNCOMPILED;
        // compileAsync cancels any in-flight task, so an UNCOMPILED section must be scheduled exactly
        // once (schedSet guard) or it never finishes; a compiled section clears its guard entry.
        if (!uncompiled) {
            scratchSchedSet.remove(node);
        }
        // S14.51 F1: a MAIN-dim arm (nested/return pass) compiles UNCOMPILED-only — main-visible
        // dirty sections are the MAIN extract's job next frame; consuming them here was the
        // standstill-churn scheduler AND a mark-theft shadow feeder on the origin dim.
        boolean wantCompile = scratchMainDimArm
            ? (uncompiled && !scratchSchedSet.contains(node))
            : (ds != null && ds.isDirty()) || (uncompiled && !scratchSchedSet.contains(node));
        // S14.50 — the BOUNDARY-SHADOW root fix (trace wf_9749e767-5bc; verify wf_3b6a4ccd-772
        // PASS with the mechanism CORRECTED): vanilla's load-bearing FIRST-compile gate is
        // `dirty && (compiled || hasAllNeighbors)` (LevelExtractor:154-159; hasAllNeighbors = all
        // 8 neighbor chunks FULL + lightOnInColumn per neighbor). This armed fold compiled on
        // own-chunk-loaded alone AND consumed the one-shot mark at schedule time — a
        // streaming-ring frontier section therefore baked its smooth-lighting seam against
        // ABSENT/unlit neighbors (dark), with the mark gone. (Verify correction of the trace's
        // permanence claim: the light engine DOES re-mark samplers — the publish delivers a
        // 27-neighbor affected set to onLightUpdate post-publish — so the post-fix residual
        // exposure through THIS site is a 1-2 frame flicker in the enable-vs-publish window, not
        // permanence; hasAllNeighbors' lightOnInColumn flips at data-ENABLE (tick), the worker
        // reads the PUBLISHED store (frame-end for secondaries).) Fix: defer ANY armed compile
        // until the frontier is complete — vanilla's predicate for first compiles, plus
        // (hardening, stricter than vanilla) the same hold for RE-compiles, narrowing the
        // enable-vs-publish window. Defer = keep the mark, keep schedSet eligibility (the
        // over-budget branch's retained-mark shape) — the compile happens a few frames later with
        // real light, vanilla's (and IP 1.21.3's) frontier cadence. The BFS still floods THROUGH
        // deferred sections (tempQueue add precedes this call) — no reachability loss. Ledgered
        // residuals: (a) block-update remeshes at a PERMANENTLY incomplete frontier defer until
        // neighbors exist (mark retained, self-heals); (b) any REMAINING permanent seam requires
        // a mark-LOSS mechanism on top (e.g. across a promote in the publish frame) — dump a
        // post-fix shadow (dirty=true ⇒ heal never scheduled; dirty=false ⇒ a consume race
        // survives). Budget check runs FIRST (verify micro-opt): over-budget frames skip the
        // 8-chunk neighbor probe entirely; both defer branches retain the mark.
        if (wantCompile && System.nanoTime() - compileStartNs < compileBudgetNs
            && (scratchSut == null || scratchSut.hasAllNeighbors(scratchDestLevel, node))
        ) {
            section.compileAsync(scratchCache.createRegion(scratchDestLevel, node));
            // F1: never consume the MAIN tracker's marks (vanilla ownership — the main extract).
            if (ds != null && !scratchMainDimArm) {
                ds.setNotDirty();
            }
            if (uncompiled) {
                scratchSchedSet.add(node);
            }
            scheduledCount++;
            // F0 attribution: split fold-scheduled counts by arm kind for the kit rows.
            if (scratchMainDimArm) {
                TeleportFlashProbe.foldSchedMainThisFrame++;
            }
            else {
                TeleportFlashProbe.foldSchedDestThisFrame++;
            }
        }
        // Over-budget sections are left dirty/uncompiled for the next frame + the per-tick pump.
    }

    private static final Stack<ObjectArrayList<RenderSection>> listCaches = new Stack<>();

    public static ObjectArrayList<RenderSection> takeList() {
        if (listCaches.isEmpty()) {
            return new ObjectArrayList<>();
        }
        else {
            return listCaches.pop();
        }
    }

    public static void returnList(ObjectArrayList<RenderSection> list) {
        list.clear();// avoid memory leak
        listCaches.push(list);
    }

    public static void init() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(VisibleSectionDiscovery::cleanUp);

        ClientWorldLoader.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT.register((dim) -> {
            cleanUp();
        });
    }

    private static void cleanUp() {
        listCaches.clear();
        resultHolder = null;
        builtChunks = null;
        vanillaFrustum = null;
        scratchDestLevel = null;
        scratchSut = null;
        scratchCache = null;
        scratchSchedSet = null;
        scratchMainDimArm = false;
    }

}
