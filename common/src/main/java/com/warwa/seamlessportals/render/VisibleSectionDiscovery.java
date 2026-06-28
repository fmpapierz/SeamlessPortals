package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.mixin.client.ViewAreaInvokerMixin;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

/**
 * Phase 5 — port of Immersive Portals' {@code VisibleSectionDiscovery}: a
 * render-distance-BOUNDED, frustum-culled, 6-neighbour breadth-first flood-fill
 * that fills a {@code visibleSections} list seeded at the camera section, WITHOUT
 * touching the engine's {@link net.minecraft.client.renderer.SectionOcclusionGraph}.
 *
 * <p>This is the reliable no-Sodium replacement for {@code sog.update}, whose
 * unbounded synchronous propagation ({@code runPartialUpdate → runUpdates}) froze
 * the render thread on the mod's bulk-loaded secondary renderers (verified
 * 2026-06-28). IP's upstream does exactly this on the no-Sodium path: it cancels
 * vanilla terrain setup and substitutes this flood-fill, accepting no cave-culling
 * for guaranteed-bounded, allocation-light, deterministic cost.
 *
 * <p>Synchronous + render-thread-only: the scratch {@link #SCRATCH_QUEUE} /
 * {@link #SCRATCH_VISITED} are reused across calls (cleared each run) to avoid
 * per-frame garbage, mirroring IP's static-field design.
 */
public final class VisibleSectionDiscovery {

    private VisibleSectionDiscovery() {}

    private static final ArrayDeque<SectionRenderDispatcher.RenderSection> SCRATCH_QUEUE = new ArrayDeque<>();
    private static final LongOpenHashSet SCRATCH_VISITED = new LongOpenHashSet();

    /**
     * Flood-fill from the camera section, frustum-culled and bounded by
     * {@code viewDistanceSections} (a Chebyshev cube), into {@code out}.
     *
     * @param viewArea             the renderer's ViewArea (provides RenderSections by position)
     * @param cameraPos            world-space camera position (the virtual/dest camera for a portal view)
     * @param frustum             the cull frustum (already prepared at cameraPos)
     * @param viewDistanceSections per-axis half-extent in sections (e.g. fog/render distance in chunks)
     * @param out                  cleared, then filled with the visited visible RenderSections
     */
    public static void discoverVisibleSections(
            ViewArea viewArea, Vec3 cameraPos, Frustum frustum, int viewDistanceSections,
            List<SectionRenderDispatcher.RenderSection> out) {
        out.clear();
        SCRATCH_QUEUE.clear();
        SCRATCH_VISITED.clear();

        int camX = SectionPos.blockToSectionCoord((int) Math.floor(cameraPos.x));
        int camY = SectionPos.blockToSectionCoord((int) Math.floor(cameraPos.y));
        int camZ = SectionPos.blockToSectionCoord((int) Math.floor(cameraPos.z));

        // Seed: the camera's own section, skipping the frustum test (the camera's
        // section can be wrongly culled — IP does the same with skipFrustumTest).
        checkSection(viewArea, frustum, camX, camY, camZ, camX, camY, camZ, viewDistanceSections, true, out);

        while (!SCRATCH_QUEUE.isEmpty()) {
            SectionRenderDispatcher.RenderSection curr = SCRATCH_QUEUE.poll();
            long node = curr.getSectionNode();
            int cx = SectionPos.x(node);
            int cy = SectionPos.y(node);
            int cz = SectionPos.z(node);
            checkSection(viewArea, frustum, cx + 1, cy, cz, camX, camY, camZ, viewDistanceSections, false, out);
            checkSection(viewArea, frustum, cx - 1, cy, cz, camX, camY, camZ, viewDistanceSections, false, out);
            checkSection(viewArea, frustum, cx, cy + 1, cz, camX, camY, camZ, viewDistanceSections, false, out);
            checkSection(viewArea, frustum, cx, cy - 1, cz, camX, camY, camZ, viewDistanceSections, false, out);
            checkSection(viewArea, frustum, cx, cy, cz + 1, camX, camY, camZ, viewDistanceSections, false, out);
            checkSection(viewArea, frustum, cx, cy, cz - 1, camX, camY, camZ, viewDistanceSections, false, out);
        }
    }

    private static void checkSection(
            ViewArea viewArea, Frustum frustum,
            int cx, int cy, int cz, int camX, int camY, int camZ,
            int viewDistanceSections, boolean skipFrustum,
            List<SectionRenderDispatcher.RenderSection> out) {
        // Per-axis cube cutoff (Chebyshev), like IP.
        if (Math.abs(cx - camX) > viewDistanceSections) return;
        if (Math.abs(cy - camY) > viewDistanceSections) return;
        if (Math.abs(cz - camZ) > viewDistanceSections) return;

        long node = SectionPos.asLong(cx, cy, cz);
        if (!SCRATCH_VISITED.add(node)) return; // already visited this run

        // 26.2: fetch the RenderSection by packed section node (no BlockPos alloc).
        // getRenderSection(SectionPos.asLong(cx,cy,cz)) is exactly equivalent to
        // getRenderSectionAt(blockPos) — both bottom out in RotatingSectionStorage
        // .getValue(node). Returns null when (cx,cy,cz) is outside the grid (incl.
        // above/below world), which naturally bounds the fill.
        SectionRenderDispatcher.RenderSection section =
            ((ViewAreaInvokerMixin) (Object) viewArea).seamlessportals$invokeGetRenderSection(node);
        if (section == null) return;

        if (skipFrustum || frustum.isVisible(section.getBoundingBox())) {
            SCRATCH_QUEUE.add(section);
            out.add(section);
        }
    }

    /**
     * The FBO portal-view variant of the flood-fill. Replaces the old
     * {@code for (RenderSection : viewArea.sections)} scan in
     * {@code PortalContextSwitch.doFboRender}, which iterated the ENTIRE ViewArea
     * (~78K–101K sections at render distance 32) every frame just to find the few
     * thousand actually visible through the portal — a hand-rolled O(all-sections)
     * sweep on the render thread, the dominant teleport-stutter cost. This walks
     * ONLY the connected, in-cone, in-radius sections (the same set the old scan
     * admitted) and folds the old scan's per-section work into the accept step:
     * the {@code hasChunk} draw gate, the dirty/UNCOMPILED async-compile scheduling
     * (budgeted, with the one-shot {@code schedSet} guard), and population of both
     * the renderer's {@code visibleSections} and the static
     * {@code prebuiltVisibleSections}.
     *
     * <p>BOUND: a 2D horizontal cylinder of {@code radiusSq} sections with NO Y cap
     * (full vertical column) — exactly the old scan's admission bound
     * ({@code rdx*rdx + rdz*rdz > destDepthRadiusSq()}). This deliberately keeps the
     * mod's existing portal-view draw set (which is intentionally tighter than IP's
     * literal render-distance cube, because this mod keeps the just-left dimension
     * FULLY resident — so a cube bound would re-admit the whole RD set and defeat
     * the fix). A 2D cylinder cannot vertically clip the tall column the way a
     * Chebyshev cube at the same radius would.
     *
     * <p>Never touches {@link net.minecraft.client.renderer.SectionOcclusionGraph},
     * so it cannot reintroduce the {@code sog.update} render-thread hang.
     *
     * @return the number of sections for which an async compile was scheduled this
     *         call (for diagnostics); all other behaviour is via the out-lists.
     */
    public static int discoverAndScheduleForPortalView(
            ViewArea viewArea, Vec3 cameraPos, Frustum frustum, int radiusSq,
            ClientLevel destLevel, SectionUpdateTracker sut, RenderRegionCache cache,
            Set<Long> schedSet, long compileBudgetNs,
            List<SectionRenderDispatcher.RenderSection> visibleOut,
            List<SectionRenderDispatcher.RenderSection> prebuiltOut) {
        visibleOut.clear();
        prebuiltOut.clear();
        SCRATCH_QUEUE.clear();
        SCRATCH_VISITED.clear();

        int camX = SectionPos.blockToSectionCoord((int) Math.floor(cameraPos.x));
        int camY = SectionPos.blockToSectionCoord((int) Math.floor(cameraPos.y));
        int camZ = SectionPos.blockToSectionCoord((int) Math.floor(cameraPos.z));
        long startNs = System.nanoTime();
        int[] scheduled = {0};

        // Seed: the camera's own section, skipping the frustum test (IP does the same).
        acceptPortalView(viewArea, frustum, camX, camY, camZ, camX, camZ, radiusSq, true,
            destLevel, sut, cache, schedSet, compileBudgetNs, startNs, scheduled, visibleOut, prebuiltOut);

        while (!SCRATCH_QUEUE.isEmpty()) {
            SectionRenderDispatcher.RenderSection curr = SCRATCH_QUEUE.poll();
            long node = curr.getSectionNode();
            int cx = SectionPos.x(node);
            int cy = SectionPos.y(node);
            int cz = SectionPos.z(node);
            acceptPortalView(viewArea, frustum, cx + 1, cy, cz, camX, camZ, radiusSq, false,
                destLevel, sut, cache, schedSet, compileBudgetNs, startNs, scheduled, visibleOut, prebuiltOut);
            acceptPortalView(viewArea, frustum, cx - 1, cy, cz, camX, camZ, radiusSq, false,
                destLevel, sut, cache, schedSet, compileBudgetNs, startNs, scheduled, visibleOut, prebuiltOut);
            acceptPortalView(viewArea, frustum, cx, cy + 1, cz, camX, camZ, radiusSq, false,
                destLevel, sut, cache, schedSet, compileBudgetNs, startNs, scheduled, visibleOut, prebuiltOut);
            acceptPortalView(viewArea, frustum, cx, cy - 1, cz, camX, camZ, radiusSq, false,
                destLevel, sut, cache, schedSet, compileBudgetNs, startNs, scheduled, visibleOut, prebuiltOut);
            acceptPortalView(viewArea, frustum, cx, cy, cz + 1, camX, camZ, radiusSq, false,
                destLevel, sut, cache, schedSet, compileBudgetNs, startNs, scheduled, visibleOut, prebuiltOut);
            acceptPortalView(viewArea, frustum, cx, cy, cz - 1, camX, camZ, radiusSq, false,
                destLevel, sut, cache, schedSet, compileBudgetNs, startNs, scheduled, visibleOut, prebuiltOut);
        }
        return scheduled[0];
    }

    private static void acceptPortalView(
            ViewArea viewArea, Frustum frustum,
            int cx, int cy, int cz, int camX, int camZ, int radiusSq, boolean skipFrustum,
            ClientLevel destLevel, SectionUpdateTracker sut, RenderRegionCache cache,
            Set<Long> schedSet, long compileBudgetNs, long startNs, int[] scheduled,
            List<SectionRenderDispatcher.RenderSection> visibleOut,
            List<SectionRenderDispatcher.RenderSection> prebuiltOut) {
        // 2D horizontal cylinder bound (full Y column — no vertical clip).
        int dx = cx - camX;
        int dz = cz - camZ;
        if (dx * dx + dz * dz > radiusSq) return;

        long node = SectionPos.asLong(cx, cy, cz);
        if (!SCRATCH_VISITED.add(node)) return; // already visited this run

        SectionRenderDispatcher.RenderSection section =
            ((ViewAreaInvokerMixin) (Object) viewArea).seamlessportals$invokeGetRenderSection(node);
        if (section == null) return; // outside the grid (incl. above/below world) → bounds the fill

        // Frustum-culled sections are neither drawn NOR expanded through (the flood
        // only propagates through what's actually on-screen for the portal view).
        if (!skipFrustum && !frustum.isVisible(section.getBoundingBox())) return;

        // In-bound + on-screen: keep flooding THROUGH it even if its chunk isn't
        // loaded yet, so a momentary gap (still-loading dest) doesn't stall the fill.
        SCRATCH_QUEUE.add(section);

        // Draw + compile ONLY where the dest chunk is actually loaded — exactly the
        // old scan's hasChunk gate (PortalContextSwitch:940). Unloaded coords must
        // never reach createRegion.
        int sx = SectionPos.x(node);
        int sz = SectionPos.z(node);
        if (!destLevel.getChunkSource().hasChunk(sx, sz)) return;

        visibleOut.add(section);
        prebuiltOut.add(section);

        // Async-compile scheduling — verbatim from the old scan (PortalContextSwitch
        // 954-988): schedule dirty OR UNCOMPILED-not-yet-scheduled sections, budgeted,
        // with the one-shot schedSet guard (compileAsync cancels any in-flight task,
        // so an UNCOMPILED section must be scheduled exactly once or it never finishes).
        SectionUpdateTracker.SectionDirtyState ds = sut != null ? sut.getDirtyState(node) : null;
        boolean uncompiled = section.sectionMesh.get() == CompiledSectionMesh.UNCOMPILED;
        if (!uncompiled) schedSet.remove(node);
        boolean wantCompile = (ds != null && ds.isDirty()) || (uncompiled && !schedSet.contains(node));
        if (wantCompile && System.nanoTime() - startNs < compileBudgetNs) {
            section.compileAsync(cache.createRegion(destLevel, node));
            if (ds != null) ds.setNotDirty();
            if (uncompiled) schedSet.add(node);
            scheduled[0]++;
        }
        // Over-budget sections are left dirty/uncompiled for the next frame + the
        // per-tick compile pump — same as the old scan's deferred path.
    }
}
