package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.mixin.client.LevelExtractorAccessor;
import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import com.warwa.seamlessportals.mixin.client.ViewAreaInvokerMixin;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.List;

/**
 * SAME-DIMENSION PORTAL TERRAIN FRESHNESS — the fix for a block that changes behind a same-dimension
 * portal and is never redrawn.
 *
 * <h2>What was actually wrong</h2>
 * Measured end to end with {@code SeamDeliveryProbe}, not inferred. The server write, the packet and
 * the client's {@code ClientLevel} were correct every time; only the picture was stale. There are
 * <b>two</b> independent losses:
 *
 * <ul>
 *   <li><b>A — the dirty mark is never CONSUMED.</b> {@code LevelExtractor.extract} turns dirty flags
 *       into rebuilds by iterating {@code levelRenderer.visibleSections()} (REF
 *       {@code LevelExtractor.java:152-169}) — the MAIN CAMERA's list, refilled only in
 *       {@code applyFrustum} from the single main-camera {@code SectionOcclusionGraph}. A section
 *       visible only THROUGH a same-dimension portal is never in it, so the flag is set and then
 *       ignored, permanently. Primary defect; fires at ANY distance.
 *       <p>Cross-dimension escapes it because the destination dimension owns a separate
 *       {@code LevelExtractor}, for which {@code SecondaryWorldRenderCore.renderDestWorld} runs a
 *       full dest extract plus a {@code compileSections} drain. That entire block is skipped for
 *       same-dimension passes, and the render core says why in its own javadoc: <i>"a same-dim
 *       extract would reposition the MAIN SectionUpdateTracker to the portal camera (dirty-mark loss
 *       for far portals) and re-flip the main delta window."</i> Those reasons hold. The cost that
 *       came with them — same-dim terrain never refreshing — was not enumerated.</li>
 *   <li><b>B — the dirty mark is DISCARDED before it is stored.</b>
 *       {@code SectionUpdateTracker.setDirty} (REF {@code :26-31}) looks the section up in a
 *       {@code RotatingSectionStorage} sized by render distance and centred on the camera, and
 *       returns silently on {@code null}. A destination beyond render distance never gets a flag.</li>
 * </ul>
 *
 * <h2>Two defects, two mechanisms — because they are not the same shape</h2>
 * The first cut of this class used ONE mechanism: queue every {@code setDirty} near a same-dim
 * portal and drain it. It failed, and the counters said so — {@code droppedOverCap=13784},
 * {@code pending} pinned at its cap. Chunk loading dirties whole regions at once, so the queue
 * saturated on load noise and then <b>dropped the genuine block change</b> it existed to carry. The
 * gate still passed, because it only asserted "some rebuilds happened".
 *
 * <p>The two defects need different tests, so they get different mechanisms:
 * <ul>
 *   <li><b>Defect A → a per-tick SWEEP, no queue.</b> A section still flagged dirty at the end of a
 *       tick is <i>precisely</i> one that no extract consumed — extract runs at least once per tick
 *       and clears everything it takes. So "still dirty now" IS the test for "nobody is going to
 *       rebuild this", with no bookkeeping and nothing to overflow. The sweep is bounded to the
 *       sections behind same-dimension portals and is a few hundred array lookups.</li>
 *   <li><b>Defect B → a small queue, and only for marks the tracker REFUSED.</b> Out-of-window marks
 *       cannot be found by a sweep, because there is no dirty state to find. These are recorded at
 *       the moment of refusal — and, crucially, refusals are rare (they need a portal past render
 *       distance), so this queue does not see the load-noise flood that drowned the first cut.</li>
 * </ul>
 *
 * <h2>★ AND THE COMPILE MUST BE CALLED DIRECTLY — the second thing that was wrong</h2>
 * The second cut appended a {@code SectionUpdateRenderState} to the main {@code LevelRenderState},
 * expecting the frame's own {@code compileSections} to drain it. That was <b>structurally inert</b>
 * and the user saw no change whatsoever. Per frame the order is
 * {@code extract()} → {@code levelRenderState.reset()} (which CLEARS
 * {@code sectionUpdateRenderStates}, REF {@code LevelRenderState.java:34-35} via
 * {@code LevelExtractor.java:110}) → fill from {@code visibleSections} → {@code render()} →
 * {@code compileSections} drains (REF {@code LevelRenderer.java:255}). This pass runs from
 * {@code POST_CLIENT_TICK}, between frames, so every entry it appended was wiped by the next
 * {@code reset()} before anything compiled it.
 *
 * <p>So {@link SectionRenderDispatcher.RenderSection#compileAsync} is called directly — the exact
 * primitive {@code compileSections} itself ends at (REF {@code LevelRenderer.java:626-640}), and the
 * one that owes nothing to {@code visibleSections} or the tracker. That is also, in shape, what
 * Sodium does: a rebuild queue that is not keyed to the main camera's visible set, which is why the
 * same-dim staleness does not occur under Sodium at all.
 *
 * <p><b>No IP-core render file is edited and no render state is mutated mid-frame.</b> Nothing here
 * repositions a camera, touches a {@code ViewArea}, or re-flips a delta window — the three things
 * the render core skipped the same-dim extract to avoid.
 *
 * <h2>Why the outcome is instrumented and not the request</h2>
 * Both failed cuts passed a gate. The first asserted "some rebuilds were scheduled" while dropping
 * the write under test; the second asserted "this section was scheduled" while the schedule was
 * being thrown away unread. Each assertion sat one step short of reality. So the gate now asserts
 * {@link #didCompileSectionAt} — driven by {@code RenderSection.setSectionMesh}, the point at which
 * a finished compile actually replaces the mesh. There is no step left between that and the pixels.
 */
public final class SameDimRemesh {

    private SameDimRemesh() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** {@code RenderRegionCache.createRegion} snapshots 27 sections and costs ~1ms. Spread it. */
    private static final int MAX_REGIONS_PER_TICK = 8;

    /**
     * How far around a same-dimension portal's destination is swept, in sections. Deliberately
     * modest: this bounds per-tick work, and a portal window shows the near field of its
     * destination, not a render-distance sphere.
     */
    private static final int DEST_RADIUS_SECTIONS = 3;

    /**
     * Out-of-window refusals only (defect B). Small on purpose — if this ever fills, something is
     * refusing marks in bulk, which is a different bug and should be visible rather than absorbed.
     */
    private static final int MAX_REFUSED = 256;
    private static final LongOpenHashSet REFUSED = new LongOpenHashSet();

    /** Destination section positions of same-dimension portals, rebuilt each client tick. */
    private static final List<long[]> DEST_REGIONS = new ArrayList<>();
    private static final List<long[]> DEST_REGIONS_NEXT = new ArrayList<>();

    /**
     * Sections this fix has scheduled, for the gate to assert the SPECIFIC cell it wrote — not
     * merely that some rebuild happened somewhere. Bounded; the count is the headline figure.
     */
    private static final LongOpenHashSet RECENT_SCHEDULED = new LongOpenHashSet();
    private static final int MAX_RECENT = 512;

    private static long scheduled = 0L;
    private static long sweptDirty = 0L;
    private static long refusedSeen = 0L;
    private static long refusedDropped = 0L;

    // =============================================================================================
    // Portal bookkeeping
    // =============================================================================================

    /**
     * Called from IP's per-portal CLIENT tick signal — the same source {@code AperturePassthroughInit}
     * already binds to. Portals are few, so this needs no entity scan.
     */
    public static void onClientPortalTick(Portal portal) {
        if (AperturePassthroughLever.DISABLE_SAME_DIM_REMESH) {
            return;
        }
        try {
            Level level = portal.level();
            if (level == null || !level.isClientSide()) {
                return;
            }
            // ONLY same-dimension portals. A cross-dimension destination is served correctly by its
            // own dimension's extractor and must not be touched here.
            if (!level.dimension().equals(portal.getDestDim())) {
                return;
            }
            Vec3 dest = portal.getDestPos();
            DEST_REGIONS_NEXT.add(new long[]{
                SectionPos.blockToSectionCoord((int) Math.floor(dest.x)),
                SectionPos.blockToSectionCoord((int) Math.floor(dest.y)),
                SectionPos.blockToSectionCoord((int) Math.floor(dest.z))
            });
        }
        catch (Throwable t) {
            LOGGER.warn("[RS-REMESH] same-dim portal bookkeeping failed for {}", portal.getUUID(), t);
        }
    }

    private static boolean behindSameDimPortal(int sx, int sy, int sz) {
        for (int i = 0; i < DEST_REGIONS.size(); i++) {
            long[] r = DEST_REGIONS.get(i);
            if (Math.abs(sx - r[0]) <= DEST_RADIUS_SECTIONS
                && Math.abs(sy - r[1]) <= DEST_RADIUS_SECTIONS
                && Math.abs(sz - r[2]) <= DEST_RADIUS_SECTIONS) {
                return true;
            }
        }
        return false;
    }

    // =============================================================================================
    // DEFECT B — record marks the tracker REFUSED, which a sweep can never find
    // =============================================================================================

    /**
     * Called from {@code SectionUpdateTracker.setDirty} <b>only when the lookup returned null</b>,
     * i.e. the mark was thrown away for being outside the rotating window.
     *
     * <p>Refusals near a same-dimension portal are rare by construction — they need a destination
     * further than render distance — which is what keeps this queue small where the first cut's
     * queue-everything approach saturated on ordinary chunk-load dirtying.
     */
    public static void onDirtyMarkRefused(boolean isMainTracker, int sx, int sy, int sz) {
        if (AperturePassthroughLever.DISABLE_SAME_DIM_REMESH || !isMainTracker) {
            return;
        }
        if (DEST_REGIONS.isEmpty() || !behindSameDimPortal(sx, sy, sz)) {
            return;
        }
        refusedSeen++;
        synchronized (REFUSED) {
            if (REFUSED.size() >= MAX_REFUSED) {
                refusedDropped++;
                return;
            }
            REFUSED.add(SectionPos.asLong(sx, sy, sz));
        }
    }

    // =============================================================================================
    // The per-tick pass
    // =============================================================================================

    /** Called once per client tick, after the world tick — never mid-extract, never mid-render. */
    public static void onEndClientTick(Minecraft mc) {
        // Swap in this tick's portal set FIRST: a portal removed since last tick must stop
        // qualifying immediately, or a stale region keeps scheduling rebuilds nobody is looking at.
        DEST_REGIONS.clear();
        DEST_REGIONS.addAll(DEST_REGIONS_NEXT);
        DEST_REGIONS_NEXT.clear();

        if (AperturePassthroughLever.DISABLE_SAME_DIM_REMESH
            || DEST_REGIONS.isEmpty()
            || mc == null || mc.level == null || mc.levelExtractor == null) {
            return;
        }
        try {
            pass(mc);
        }
        catch (Throwable t) {
            LOGGER.warn("[RS-REMESH] same-dim remesh pass failed (terrain may stay stale)", t);
        }
    }

    private static void pass(Minecraft mc) {
        LevelExtractor extractor = mc.levelExtractor;
        LevelExtractorAccessor ea = (LevelExtractorAccessor) (Object) extractor;
        ClientLevel level = ea.seamlessportals$getLevel();
        LevelRenderer renderer = ea.seamlessportals$getLevelRenderer();
        SectionUpdateTracker tracker = ea.seamlessportals$getSectionUpdateTracker();
        if (level == null || renderer == null || level != mc.level) {
            return;
        }
        ViewArea viewArea = ((LevelRendererAccessorMixin) renderer).seamlessportals$getViewArea();
        if (viewArea == null) {
            return;   // Sodium owns terrain (IgnoringViewArea); its own builder does the meshing
        }
        // NOTE: deliberately no LevelRenderState here — see the compile call in schedule().

        RenderRegionCache cache = new RenderRegionCache();
        int budget = MAX_REGIONS_PER_TICK;

        // ---- DEFECT B first: refusals are rarer and would otherwise starve behind the sweep ----
        long[] refusedBatch;
        synchronized (REFUSED) {
            int take = Math.min(budget, REFUSED.size());
            refusedBatch = new long[take];
            var it = REFUSED.iterator();
            for (int i = 0; i < take; i++) {
                refusedBatch[i] = it.nextLong();
                it.remove();
            }
        }
        for (long node : refusedBatch) {
            if (schedule(node, viewArea, tracker, level, cache)) {
                budget--;
            }
        }

        // ---- DEFECT A: sweep the sections behind same-dim portals for marks nobody consumed ----
        //
        // "Still dirty at end of tick" is the exact test. An extract runs at least once per tick and
        // clears every section it takes, so a flag that survives the tick is one no camera's
        // visibleSections covered — which is defect A, stated operationally.
        for (int i = 0; i < DEST_REGIONS.size() && budget > 0; i++) {
            long[] r = DEST_REGIONS.get(i);
            for (int dx = -DEST_RADIUS_SECTIONS; dx <= DEST_RADIUS_SECTIONS && budget > 0; dx++) {
                for (int dy = -DEST_RADIUS_SECTIONS; dy <= DEST_RADIUS_SECTIONS && budget > 0; dy++) {
                    for (int dz = -DEST_RADIUS_SECTIONS; dz <= DEST_RADIUS_SECTIONS && budget > 0; dz++) {
                        long node = SectionPos.asLong(
                            (int) r[0] + dx, (int) r[1] + dy, (int) r[2] + dz);
                        SectionUpdateTracker.SectionDirtyState dirty =
                            tracker == null ? null : tracker.getDirtyState(node);
                        if (dirty == null || !dirty.isDirty()) {
                            continue;
                        }
                        sweptDirty++;
                        if (schedule(node, viewArea, tracker, level, cache)) {
                            budget--;
                        }
                    }
                }
            }
        }
    }

    /** @return true when a rebuild was actually queued (so the caller can spend its budget). */
    private static boolean schedule(
        long node, ViewArea viewArea, SectionUpdateTracker tracker, ClientLevel level,
        RenderRegionCache cache
    ) {
        SectionRenderDispatcher.RenderSection section =
            ((ViewAreaInvokerMixin) (Object) viewArea).seamlessportals$invokeGetRenderSection(node);
        if (section == null) {
            return false;   // no render section there; nothing to rebuild
        }
        // Vanilla's own admission test (REF LevelExtractor.java:155-158): rebuild a section that is
        // already compiled, or a fresh one only once its neighbours are present — meshing a section
        // whose neighbours are missing bakes wrong face culling into it.
        boolean alreadyCompiled = section.getSectionMesh()
            != net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED;
        if (!alreadyCompiled && (tracker == null || !tracker.hasAllNeighbors(level, node))) {
            return false;   // not ready; the sweep will find it again next tick
        }
        // ★ COMPILE DIRECTLY. Do NOT append to levelRenderState.sectionUpdateRenderStates.
        //
        // The first version of this method did exactly that, and it was STRUCTURALLY INERT. Per
        // frame the order is: extract() -> levelRenderState.reset() (which CLEARS
        // sectionUpdateRenderStates, REF LevelRenderState.java:34-35, called from
        // LevelExtractor.java:110) -> fill from visibleSections -> render() -> compileSections
        // drains it (REF LevelRenderer.java:255). This pass runs from POST_CLIENT_TICK, between
        // frames, so everything it appended was wiped by the next reset() before anything compiled
        // it. The user saw no change at all, which was exactly right.
        //
        // These three lines are what compileSections itself does per entry (REF
        // LevelRenderer.java:626-640), minus the sync/async preference: fade 0 so a re-mesh does not
        // fade in like a newly loaded section, and the previously-empty flag cleared the same way.
        section.setFadeDuration(0L);
        section.setWasPreviouslyEmpty(false);
        synchronized (AWAITING) {
            if (AWAITING.size() >= MAX_RECENT) {
                AWAITING.clear();
            }
            AWAITING.add(node);
        }
        section.compileAsync(cache.createRegion(level, node));
        // Clear the tracker's own flag when it HAS one, so the main extract does not schedule the
        // same section a second time. A refused mark (defect B) has no state to clear — which is
        // exactly why those are tracked outside the tracker.
        SectionUpdateTracker.SectionDirtyState dirty =
            tracker == null ? null : tracker.getDirtyState(node);
        if (dirty != null) {
            dirty.setNotDirty();
        }
        // Tell the delivery probe directly rather than letting it infer from setNotDirty: a defect-B
        // section has no state to clear, so setNotDirty never fires and stage 7 would report
        // NOT-REACHED for a rebuild this method just scheduled — an instrument reporting the
        // opposite of what happened.
        com.warwa.seamlessportals.passthrough.SeamDeliveryProbe.noteRebuildScheduled(
            SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
        synchronized (RECENT_SCHEDULED) {
            if (RECENT_SCHEDULED.size() >= MAX_RECENT) {
                RECENT_SCHEDULED.clear();
            }
            RECENT_SCHEDULED.add(node);
        }
        scheduled++;
        return true;
    }

    // =============================================================================================
    // Test/probe accounting
    // =============================================================================================

    /**
     * Sections this fix asked to compile and is still waiting on, and those whose mesh has actually
     * been replaced since. {@link #AWAITING} keeps the outcome hook cheap and exact: it fires for
     * every section in the game, and only the ones we asked for are of interest.
     */
    private static final LongOpenHashSet AWAITING = new LongOpenHashSet();
    private static final LongOpenHashSet COMPILED = new LongOpenHashSet();
    private static long compiled = 0L;

    /**
     * Called from {@code RenderSection.setSectionMesh} — the point at which a finished compile
     * replaces a section's mesh. This is the END of the chain: past here the new geometry is what
     * gets drawn, so an assertion on it cannot pass while the picture stays stale.
     */
    public static void notifyMeshReplaced(long sectionNode) {
        if (AperturePassthroughLever.DISABLE_SAME_DIM_REMESH) {
            return;
        }
        synchronized (AWAITING) {
            if (!AWAITING.remove(sectionNode)) {
                return;   // not one of ours
            }
        }
        synchronized (COMPILED) {
            if (COMPILED.size() >= MAX_RECENT) {
                COMPILED.clear();
            }
            COMPILED.add(sectionNode);
        }
        compiled++;
    }

    /** Rebuilds this fix has requested. */
    public static long scheduledCount() {
        return scheduled;
    }

    /** Rebuilds this fix requested that have actually produced a new mesh. */
    public static long compiledCount() {
        return compiled;
    }

    /**
     * Whether a rebuild this fix requested for the section containing {@code (x,y,z)} has COMPLETED.
     *
     * <p>This is what the gate asserts. {@code didScheduleSectionAt} is deliberately not enough —
     * the second cut of this class satisfied exactly that while its scheduling was being discarded
     * unread, and the gate passed on it.
     */
    public static boolean didCompileSectionAt(int x, int y, int z) {
        long node = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        synchronized (COMPILED) {
            return COMPILED.contains(node);
        }
    }

    /**
     * Whether this fix scheduled a rebuild for the section containing {@code pos}.
     *
     * <p>The gate asserts THIS rather than a count delta. A count says "something was rebuilt
     * somewhere", which the first cut of this class satisfied while dropping the very write under
     * test — the self-consistent-test failure this project has already been bitten by.
     */
    public static boolean didScheduleSectionAt(int x, int y, int z) {
        long node = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        synchronized (RECENT_SCHEDULED) {
            return RECENT_SCHEDULED.contains(node);
        }
    }

    public static String counters() {
        return "scheduled=" + scheduled + " COMPILED=" + compiled + " sweptDirty=" + sweptDirty
            + " refusedSeen=" + refusedSeen + " refusedDropped=" + refusedDropped
            + " refusedPending=" + REFUSED.size() + " sameDimPortalRegions=" + DEST_REGIONS.size();
    }
}
