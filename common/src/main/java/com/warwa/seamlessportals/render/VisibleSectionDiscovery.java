package com.warwa.seamlessportals.render;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;

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

        // 26.2: fetch the RenderSection by the section's origin block pos. Returns
        // null when (cx,cy,cz) is outside the ViewArea grid (incl. above/below world),
        // which naturally bounds the fill.
        SectionRenderDispatcher.RenderSection section =
            viewArea.getRenderSectionAt(new BlockPos(
                SectionPos.sectionToBlockCoord(cx),
                SectionPos.sectionToBlockCoord(cy),
                SectionPos.sectionToBlockCoord(cz)));
        if (section == null) return;

        if (skipFrustum || frustum.isVisible(section.getBoundingBox())) {
            SCRATCH_QUEUE.add(section);
            out.add(section);
        }
    }
}
