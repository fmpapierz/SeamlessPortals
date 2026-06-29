package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.portal.PortalInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Inner-frustum portal cull — a faithful port of Immersive Portals'
 * {@code FrustumCuller.getFlatPortalInnerFrustumCullingFunc} +
 * {@code Frustum4Planes.isFullyOutside}
 * (qouteall.imm_ptl.core.render.FrustumCuller.java:143-202, 258-291, branch 1.21.3).
 *
 * <p>This is the ONE thing IP does that the mod was missing: when rendering a portal
 * view, IP narrows visibility to the cone formed by the portal opening as seen from
 * the (virtual) camera, so it draws ONLY the sections actually visible through the
 * hole — not the whole camera frustum. The mod was rendering the full destination
 * world over the entire main-camera cone and discarding everything outside the
 * opening via the stencil, so the GPU rasterized a second full world every frame
 * (the confirmed 120-223ms {@code glDrawElementsInstancedBaseVertex} stall under
 * {@code doFboRender}). Folding this 4-plane test into the portal-view BFS removes
 * off-opening sections at the source.
 *
 * <p>The 4 side-planes are built from the portal's 4 opening corners through the
 * camera origin, so each plane's W is 0 and all test coordinates are
 * CAMERA-RELATIVE (world minus the virtual camera position) — exactly as IP feeds
 * camera-relative boxes in {@code MixinFrustum.onCubeInFrustum}.
 */
public final class PortalInnerCull {

    private PortalInnerCull() {}

    /** Four side-planes of the portal cone (unit normals; W=0, all pass through the camera). */
    public static final class Cone {
        private final float n0x, n0y, n0z;
        private final float n1x, n1y, n1z;
        private final float n2x, n2y, n2z;
        private final float n3x, n3y, n3z;

        private Cone(float[] n) {
            n0x = n[0]; n0y = n[1]; n0z = n[2];
            n1x = n[3]; n1y = n[4]; n1z = n[5];
            n2x = n[6]; n2y = n[7]; n2z = n[8];
            n3x = n[9]; n3y = n[10]; n3z = n[11];
        }

        /**
         * Box coordinates MUST be camera-relative (world minus the virtual camera pos).
         * Returns true iff the box is fully outside the portal cone — i.e. fully behind
         * any one of the 4 side-planes (IP {@code Frustum4Planes.isFullyOutside}).
         */
        public boolean isFullyOutside(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            return behind(minX, minY, minZ, maxX, maxY, maxZ, n0x, n0y, n0z)
                || behind(minX, minY, minZ, maxX, maxY, maxZ, n1x, n1y, n1z)
                || behind(minX, minY, minZ, maxX, maxY, maxZ, n2x, n2y, n2z)
                || behind(minX, minY, minZ, maxX, maxY, maxZ, n3x, n3y, n3z);
        }

        // IP FrustumCuller.isFullyBehindPlane (W=0): pick the box corner most toward the
        // plane normal; if even that corner is behind (dot < 0), the whole box is behind.
        private static boolean behind(float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ,
                                      float px, float py, float pz) {
            float bx = px > 0 ? maxX : minX;
            float by = py > 0 ? maxY : minY;
            float bz = pz > 0 ? maxZ : minZ;
            return bx * px + by * py + bz * pz < 0.0f;
        }
    }

    /**
     * Build the cone for a flat (axis-aligned rectangular) portal — the only portal
     * shape this mod has — from its opening corners and the virtual camera position.
     * The corners are taken directly in DEST space (the mod's destPortal is already
     * dest-space; IP transforms source corners, but the mod supplies the dest portal),
     * so we only subtract the camera (camera-relative), no portal transform.
     *
     * <p>Self-correcting winding: the portal CENTER must read as inside the cone; if
     * the corner order produced outward-facing normals (center reads "outside"), we
     * rebuild with reversed winding. This makes the cull robust regardless of which
     * side the mirror camera sits on — it can never accidentally cull the whole view.
     */
    public static Cone buildFromDestPortal(PortalInfo destPortal, Vec3 camPos) {
        BlockPos o = destPortal.getOrigin();
        int w = destPortal.getWidth();
        int h = destPortal.getHeight();
        Direction.Axis axis = destPortal.getAxis();
        double y0 = o.getY();
        double y1 = o.getY() + h;

        // 4 opening corners, world space, in IP's order: right-bottom, right-top,
        // left-top, left-bottom (matches getRectPortalFourVerticesCounterClockwise v[0..3]).
        Vec3 br, tr, tl, bl, center;
        if (axis == Direction.Axis.X) {
            double z = o.getZ() + 0.5; // portal plane (faces +/-Z), spans X by width
            double x0 = o.getX();
            double x1 = o.getX() + w;
            br = new Vec3(x1, y0, z);
            tr = new Vec3(x1, y1, z);
            tl = new Vec3(x0, y1, z);
            bl = new Vec3(x0, y0, z);
            center = new Vec3((x0 + x1) * 0.5, (y0 + y1) * 0.5, z);
        } else {
            double x = o.getX() + 0.5; // portal plane (faces +/-X), spans Z by width
            double z0 = o.getZ();
            double z1 = o.getZ() + w;
            br = new Vec3(x, y0, z1);
            tr = new Vec3(x, y1, z1);
            tl = new Vec3(x, y1, z0);
            bl = new Vec3(x, y0, z0);
            center = new Vec3(x, (y0 + y1) * 0.5, (z0 + z1) * 0.5);
        }

        Vec3 v0 = br.subtract(camPos);
        Vec3 v1 = tr.subtract(camPos);
        Vec3 v2 = tl.subtract(camPos);
        Vec3 v3 = bl.subtract(camPos);

        Cone cone = fromCorners(v0, v1, v2, v3);

        // Winding self-correction: the portal center (camera-relative) must read as
        // INSIDE the cone. If this winding produced outward normals (center "outside"),
        // rebuild reversed. If even the reversed winding fails (degenerate cone — e.g.
        // the camera sitting in the portal plane), return null so the caller falls back
        // to frustum-only culling rather than ever culling the whole view to black.
        Vec3 c = center.subtract(camPos);
        if (isPointOutside(cone, c)) {
            cone = fromCorners(v3, v2, v1, v0);
            if (isPointOutside(cone, c)) {
                return null;
            }
        }
        return cone;
    }

    private static boolean isPointOutside(Cone cone, Vec3 p) {
        return cone.isFullyOutside((float) p.x, (float) p.y, (float) p.z,
                                   (float) p.x, (float) p.y, (float) p.z);
    }

    // normal_i = v[(i+1)%4] x v[i], normalized (IP getFrustumPlanesFromFourVerticesCounterClockwise).
    private static Cone fromCorners(Vec3 v0, Vec3 v1, Vec3 v2, Vec3 v3) {
        float[] n = new float[12];
        cross(v1, v0, n, 0);
        cross(v2, v1, n, 3);
        cross(v3, v2, n, 6);
        cross(v0, v3, n, 9);
        return new Cone(n);
    }

    private static void cross(Vec3 a, Vec3 b, float[] out, int i) {
        Vec3 c = a.cross(b).normalize();
        out[i] = (float) c.x;
        out[i + 1] = (float) c.y;
        out[i + 2] = (float) c.z;
    }
}
