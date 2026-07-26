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
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
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
 * <p><b>No IP-core render file is edited and no render state is mutated mid-frame.</b> Sections are
 * handed to vanilla the way vanilla hands them to itself: a {@link SectionUpdateRenderState}
 * appended to the main {@code LevelRenderState}, drained by the main frame's own
 * {@code compileSections} (REF {@code LevelRenderer.java:608}). Nothing here repositions a camera,
 * touches a {@code ViewArea}, or re-flips a delta window — the three things the render core skipped
 * the same-dim extract to avoid.
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
        var renderState = ea.seamlessportals$getLevelRenderState();
        if (renderState == null) {
            return;
        }

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
            if (schedule(node, viewArea, tracker, level, renderState, cache)) {
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
                        if (schedule(node, viewArea, tracker, level, renderState, cache)) {
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
        net.minecraft.client.renderer.state.level.LevelRenderState renderState,
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
        renderState.sectionUpdateRenderStates.add(
            new SectionUpdateRenderState(node, false, cache.createRegion(level, node)));
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

    /** Rebuilds this fix has scheduled. */
    public static long scheduledCount() {
        return scheduled;
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
        return "scheduled=" + scheduled + " sweptDirty=" + sweptDirty
            + " refusedSeen=" + refusedSeen + " refusedDropped=" + refusedDropped
            + " refusedPending=" + REFUSED.size() + " sameDimPortalRegions=" + DEST_REGIONS.size();
    }
}
