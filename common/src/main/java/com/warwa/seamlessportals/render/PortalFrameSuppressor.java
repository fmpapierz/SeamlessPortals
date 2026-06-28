package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.portal.PortalInfo;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Used to hide the destination portal's obsidian frame during FBO section
 * compilation. Now DISABLED — the user wants the destination obsidian frame
 * visible through the source portal opening.
 *
 * {@link #isFrameBlock(BlockPos)} always returns false so
 * {@link com.warwa.seamlessportals.mixin.client.SectionCompilerMixin} keeps
 * the obsidian geometry.
 *
 * {@link #maybeForceDirtyForPortal} is called once per destination portal to
 * re-dirty sections near it, ensuring any mesh data that was previously baked
 * with the old suppression behaviour gets regenerated with the full frame.
 */
public final class PortalFrameSuppressor {

    private static final Set<UUID> firstPassHandled = new HashSet<>();

    /** Radius in sections around the destination portal to force-dirty. */
    private static final int FORCE_DIRTY_RADIUS = 2;

    private PortalFrameSuppressor() {}

    // ---------- Legacy API kept for callers, now all no-ops ----------

    public static void setDestination(PortalInfo portal) {
        // no-op: suppression disabled
    }

    public static void clear() {
        // no-op: suppression disabled
    }

    public static boolean isActive() {
        return false;
    }

    public static boolean isFrameBlock(BlockPos pos) {
        return false;
    }

    // ---------- New: one-shot re-dirty of near-portal sections ----------

    /**
     * On the first render pass that touches a given destination portal,
     * mark every section within {@link #FORCE_DIRTY_RADIUS} of the portal as
     * dirty so the suppressor's previously baked meshes get thrown away.
     * Subsequent passes are no-ops — normal dirty tracking takes over.
     */
    public static void maybeForceDirtyForPortal(PortalInfo destPortal, ViewArea viewArea) {
        if (destPortal == null || viewArea == null) return;
        UUID id = destPortal.getPortalId();
        if (!firstPassHandled.add(id)) return;

        // 26.2: the section dirty flag moved off RenderSection onto the
        // dimension's LevelExtractor SectionUpdateTracker. Look up the
        // extractor for the destination dim and mark via its tracker.
        net.minecraft.client.renderer.extract.LevelExtractor ext =
            com.warwa.seamlessportals.client.PortalWorldManager.getExtractor(
                destPortal.getDimension());
        if (ext == null) return;
        net.minecraft.client.SectionUpdateTracker tracker = ext.sectionUpdateTracker;
        if (tracker == null) return;

        BlockPos origin = destPortal.getOrigin();
        int dSecX = SectionPos.blockToSectionCoord(origin.getX());
        int dSecZ = SectionPos.blockToSectionCoord(origin.getZ());
        int dSecY = SectionPos.blockToSectionCoord(origin.getY());

        for (SectionRenderDispatcher.RenderSection section : viewArea.sections) {
            if (section == null) continue;
            long node = section.getSectionNode();
            int sx = SectionPos.x(node);
            int sy = SectionPos.y(node);
            int sz = SectionPos.z(node);
            if (Math.abs(sx - dSecX) <= FORCE_DIRTY_RADIUS
                    && Math.abs(sy - dSecY) <= FORCE_DIRTY_RADIUS
                    && Math.abs(sz - dSecZ) <= FORCE_DIRTY_RADIUS) {
                net.minecraft.client.SectionUpdateTracker.SectionDirtyState ds =
                    tracker.getDirtyState(node);
                if (ds != null) ds.setDirty(false);
            }
        }
    }
}
