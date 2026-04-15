package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.portal.PortalInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Tracks which destination portal frame blocks should be suppressed during
 * FBO section compilation. Set before rebuildSectionSync() calls in
 * PortalContextSwitch, read by SectionCompilerMixin.
 *
 * Thread-safe: FBO compilation is synchronous on the render thread.
 */
public final class PortalFrameSuppressor {

    private static PortalInfo destPortal;

    private PortalFrameSuppressor() {}

    public static void setDestination(PortalInfo portal) {
        destPortal = portal;
    }

    public static void clear() {
        destPortal = null;
    }

    public static boolean isActive() {
        return destPortal != null;
    }

    /**
     * Check if a block position is part of the destination portal's obsidian frame.
     * The frame is the ring of obsidian surrounding the portal opening.
     */
    public static boolean isFrameBlock(BlockPos pos) {
        if (destPortal == null) return false;

        BlockPos origin = destPortal.getOrigin();
        Direction.Axis axis = destPortal.getAxis();
        int w = destPortal.getWidth();
        int h = destPortal.getHeight();
        int bx = pos.getX(), by = pos.getY(), bz = pos.getZ();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();

        if (axis == Direction.Axis.X) {
            // Portal blocks at x=[ox, ox+w), y=[oy, oy+h), z=oz
            // Frame obsidian surrounds this on the same z-plane:
            //   bottom: y=oy-1, x=[ox-1, ox+w]
            //   top:    y=oy+h, x=[ox-1, ox+w]
            //   left:   x=ox-1, y=[oy, oy+h-1]
            //   right:  x=ox+w, y=[oy, oy+h-1]
            if (bz != oz) return false;
            boolean onBottom = by == oy - 1 && bx >= ox - 1 && bx <= ox + w;
            boolean onTop    = by == oy + h && bx >= ox - 1 && bx <= ox + w;
            boolean onLeft   = bx == ox - 1 && by >= oy && by <= oy + h - 1;
            boolean onRight  = bx == ox + w && by >= oy && by <= oy + h - 1;
            return onBottom || onTop || onLeft || onRight;
        } else {
            // axis=Z: portal blocks at x=ox, y=[oy, oy+h), z=[oz, oz+w)
            if (bx != ox) return false;
            boolean onBottom = by == oy - 1 && bz >= oz - 1 && bz <= oz + w;
            boolean onTop    = by == oy + h && bz >= oz - 1 && bz <= oz + w;
            boolean onLeft   = bz == oz - 1 && by >= oy && by <= oy + h - 1;
            boolean onRight  = bz == oz + w && by >= oy && by <= oy + h - 1;
            return onBottom || onTop || onLeft || onRight;
        }
    }
}
