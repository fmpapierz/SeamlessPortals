package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.portal.PortalInfo;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Port of IP's {@code qouteall.imm_ptl.core.render.FrontClipping}.
 * Central state for the active clip plane written to
 * {@code gl_ClipDistance[0]} via our shader injection
 * ({@link ShaderCodeTransformation}).
 *
 * <p>Conventions:
 * <ul>
 *   <li>Plane stored as {@code (nx, ny, nz, w)} where the half-space
 *       {@code nx*x + ny*y + nz*z + w >= 0} is KEPT. {@code < 0} is
 *       clipped. Plane is in VIEW space (camera at origin, looking down
 *       −Z).</li>
 *   <li>Setting the plane to {@code (0, 0, 0, 1)} keeps everything —
 *       matches IP's "disabled" state.</li>
 *   <li>{@link #setupOuterClipping} clips source-dim geometry PAST the
 *       portal plane (camera-side kept) — used on the main camera pass.</li>
 *   <li>{@link #setupInnerClipping} clips destination-dim geometry IN
 *       FRONT of the destination portal plane — used on the FBO pass.</li>
 *   <li>{@link #disable} resets the plane to the no-clip default and
 *       disables {@code GL_CLIP_DISTANCE0}.</li>
 * </ul>
 */
public final class FrontClipping {

    private FrontClipping() {}

    /**
     * Master switch for the INNER clip during the stencil-direct dest draw (the fix for
     * "backing away from the portal makes the mirror camera clip into dest terrain").
     * Flag-gated because an earlier (FBO-era) enablement produced the "curtain" artifact;
     * if any view-content loss reappears, flip this false instead of reverting.
     */
    public static boolean INNER_CLIP_ENABLED = true;

    private static float planeX;
    private static float planeY;
    private static float planeZ;
    private static float planeW = 1.0f; // default: d=1, keeps everything (dot(pos,0) + 1 > 0)
    private static boolean glClipEnabled = false;

    /**
     * The portal link that the outer clip is currently configured for.
     * Read by the slice pass so it can render the same link's destination
     * with inner clipping. {@code null} means no outer clip active this
     * frame.
     */
    private static com.warwa.seamlessportals.portal.PortalLink activeLink = null;

    public static float getPlaneX() { return planeX; }
    public static float getPlaneY() { return planeY; }
    public static float getPlaneZ() { return planeZ; }
    public static float getPlaneW() { return planeW; }

    public static com.warwa.seamlessportals.portal.PortalLink getActiveLink() {
        return activeLink;
    }
    public static void setActiveLink(com.warwa.seamlessportals.portal.PortalLink link) {
        activeLink = link;
    }

    /**
     * Snapshot of the current clip-plane state. Used by nested render
     * passes (FBO composite, slice pass) to push/pop their own plane
     * without clobbering the main-camera pass's plane.
     */
    public static final class Snapshot {
        public final float x, y, z, w;
        public final boolean enabled;
        public Snapshot(float x, float y, float z, float w, boolean enabled) {
            this.x = x; this.y = y; this.z = z; this.w = w; this.enabled = enabled;
        }
    }

    public static Snapshot capture() {
        return new Snapshot(planeX, planeY, planeZ, planeW, glClipEnabled);
    }

    public static void restore(Snapshot s) {
        planeX = s.x; planeY = s.y; planeZ = s.z; planeW = s.w;
        if (s.enabled) enableGlClipDistance();
        else disableGlClipDistance();
    }

    /**
     * Suspend clipping — set plane to no-op default and disable the
     * GL capability. Used inside FBO-stencil render to prevent the
     * outer plane from clipping destination geometry in the wrong
     * view space.
     */
    public static void suspend() {
        planeX = 0;
        planeY = 0;
        planeZ = 0;
        planeW = 1.0f;
        disableGlClipDistance();
    }

    /**
     * IP's outer-clip: main-camera pass when the portal frame is in view.
     * Clips source-dim geometry on the BACK side of the portal plane
     * (away from the camera).
     *
     * @param portal         the portal the camera is looking at/through
     * @param cameraWorldPos camera eye world position
     * @param viewRotation   the view rotation matrix (world → view rotation)
     */
    private static int setupOuterLogCount = 0;

    public static void setupOuterClipping(PortalInfo portal, Vec3 cameraWorldPos,
                                          org.joml.Matrix4fc viewRotation) {
        Vec3 n = portal.getNormal();
        if (setupOuterLogCount < 5) {
            setupOuterLogCount++;
            com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS SLICE] setupOuterClipping #{} portal at {} camera at ({},{},{}) normal ({},{},{})",
                setupOuterLogCount, portal.getOrigin().toShortString(),
                String.format("%.2f", cameraWorldPos.x),
                String.format("%.2f", cameraWorldPos.y),
                String.format("%.2f", cameraWorldPos.z),
                String.format("%.2f", n.x), String.format("%.2f", n.y), String.format("%.2f", n.z));
        }
        Vec3 c = portal.getCenter();
        // Decide which side the camera is on — flip normal so it points
        // toward the camera, making the camera-side kept.
        double toCam = n.x * (cameraWorldPos.x - c.x)
                     + n.y * (cameraWorldPos.y - c.y)
                     + n.z * (cameraWorldPos.z - c.z);
        double flip = toCam >= 0 ? 1.0 : -1.0;
        double nwx = n.x * flip;
        double nwy = n.y * flip;
        double nwz = n.z * flip;

        // World-space plane offset — signed distance from camera to plane.
        double dWorld = nwx * (cameraWorldPos.x - c.x)
                      + nwy * (cameraWorldPos.y - c.y)
                      + nwz * (cameraWorldPos.z - c.z);

        // Rotate the normal into view space. Translation handled separately.
        org.joml.Vector4f nView = new org.joml.Vector4f((float) nwx, (float) nwy, (float) nwz, 0f);
        nView.mul(viewRotation);

        planeX = nView.x;
        planeY = nView.y;
        planeZ = nView.z;
        planeW = (float) dWorld;

        enableGlClipDistance();
    }

    /**
     * IP's inner-clip: destination-dim FBO pass. Clips dest-dim geometry
     * IN FRONT of the portal plane (on the camera's side of dest portal)
     * so nothing between the virtual camera and the portal face pokes
     * into the view.
     */
    public static void setupInnerClipping(PortalInfo destPortal, Vec3 virtualCameraPos,
                                          org.joml.Matrix4fc viewRotation) {
        Vec3 n = destPortal.getNormal();
        Vec3 c = destPortal.getCenter();
        double toCam = n.x * (virtualCameraPos.x - c.x)
                     + n.y * (virtualCameraPos.y - c.y)
                     + n.z * (virtualCameraPos.z - c.z);
        // FLIP the flip — we want to KEEP the side AWAY from the camera,
        // not toward. So we negate the normal that points toward camera.
        double flip = toCam >= 0 ? -1.0 : 1.0;
        double nwx = n.x * flip;
        double nwy = n.y * flip;
        double nwz = n.z * flip;

        double dWorld = nwx * (virtualCameraPos.x - c.x)
                      + nwy * (virtualCameraPos.y - c.y)
                      + nwz * (virtualCameraPos.z - c.z);

        org.joml.Vector4f nView = new org.joml.Vector4f((float) nwx, (float) nwy, (float) nwz, 0f);
        nView.mul(viewRotation);

        planeX = nView.x;
        planeY = nView.y;
        planeZ = nView.z;
        planeW = (float) dWorld;

        enableGlClipDistance();
    }

    /**
     * Entity-pass variant of {@link #setupInnerClipping}: the SAME dest-portal
     * clip plane, pushed {@code margin} blocks toward the camera so a mirrored
     * dest entity STRADDLING the portal plane is not bisected (2026-07-08, the
     * "animal clips out of existence on the portal window" fix).
     *
     * <p>The inner clip keeps the half-space {@code nw·(X-c) >= 0} (the far side,
     * away from the mirror camera) and discards the camera-side half — correct
     * for TERRAIN (near-side occluders must go) but it slices the camera-side
     * half off any entity sitting at the plane. Entities that just crossed land
     * within the landing-overshoot band right at the plane, so their near half
     * vanishes. Adding {@code margin} to {@code planeW} shifts the kept condition
     * to {@code nw·(X-c) >= -margin} — everything from {@code margin} blocks
     * camera-side of the plane outward is kept — which covers a mob's ~0.3-0.6
     * body depth. Used ONLY around {@link com.warwa.seamlessportals.render.PortalContextSwitch}'s
     * dest-entity pass; terrain keeps the tight clip, so the only cost is a dest
     * entity physically standing within {@code margin} of the plane on the camera
     * side leaking into the window (rare — the dest portal is frame-embedded).
     */
    public static void setupInnerClippingForEntities(PortalInfo destPortal, Vec3 virtualCameraPos,
                                                     org.joml.Matrix4fc viewRotation, double margin) {
        setupInnerClipping(destPortal, virtualCameraPos, viewRotation);
        planeW += (float) margin;
    }

    /**
     * Disable clipping. Sets the uniform to {@code (0, 0, 0, 1)} — a
     * constant positive {@code gl_ClipDistance} so nothing is clipped,
     * and turns off {@code GL_CLIP_DISTANCE0}.
     */
    public static void disable() {
        planeX = 0;
        planeY = 0;
        planeZ = 0;
        planeW = 1.0f;
        disableGlClipDistance();
    }

    /**
     * Diagnostic kill switch — sets the plane so {@code gl_ClipDistance[0]}
     * evaluates to a constant {@code -1} at every vertex. If the clip
     * distance write is actually effective, the screen should go blank
     * (or show only the clear color / stencil'd portal view). If we
     * still see geometry, the driver is silently dropping our write.
     */
    public static void setupKillSwitchClipping() {
        planeX = 0;
        planeY = 0;
        planeZ = 0;
        planeW = -1.0f;
        enableGlClipDistance();
    }

    private static void enableGlClipDistance() {
        if (!glClipEnabled) {
            GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
            glClipEnabled = true;
        }
    }

    private static void disableGlClipDistance() {
        if (glClipEnabled) {
            GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
            glClipEnabled = false;
        }
    }
}
