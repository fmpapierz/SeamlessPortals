package com.warwa.seamlessportals.render;

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

    public static float getPlaneX() { return planeX; }
    public static float getPlaneY() { return planeY; }
    public static float getPlaneZ() { return planeZ; }
    public static float getPlaneW() { return planeW; }


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
