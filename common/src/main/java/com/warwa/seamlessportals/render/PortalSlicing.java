package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.List;

/**
 * Non-Euclidean slice: when the main camera's eye approaches a portal
 * plane, apply an oblique near-clip plane to the main world's projection
 * matrix so source-dimension geometry stops rendering exactly at the
 * portal plane. The portal's stencil FBO already draws destination-dim
 * through the portal shape, so the combination produces a perfectly
 * split frame — source-half + destination-half meeting at the portal
 * plane — matching IP's crossing visual.
 *
 * <p>State is updated each frame by
 * {@link com.warwa.seamlessportals.mixin.client.GameRendererObliqueClipMixin}
 * from the main-camera position + live portal set in
 * {@link PortalManager}. Threshold is a few blocks so the clip fades in
 * smoothly as the player approaches the portal, and fades out once
 * they've walked past.
 */
public final class PortalSlicing {

    private PortalSlicing() {}

    /** Active portal for this frame, or null. */
    private static PortalInfo activePortal;
    /** +1 or -1 — which side of the portal plane the camera eye is on. */
    private static float sideSign;
    /** Distance from eye to portal plane along the plane normal. */
    private static float signedDistance;

    /**
     * How close (along the plane normal) the eye has to be before we
     * start clipping. Matches IP's ~2-block "near the portal" window.
     */
    private static final double ACTIVATION_THRESHOLD_BLOCKS = 3.0;

    /**
     * Refresh the active-portal state. Call once per frame from render
     * thread BEFORE the projection matrix is used for the main level.
     */
    public static void updateForFrame() {
        activePortal = null;
        sideSign = 0;
        signedDistance = 0;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return;

        Vec3 eye = player.getEyePosition();
        PortalManager pm = PortalManager.getClientInstance();
        BlockPos posKey = BlockPos.containing(eye);
        List<PortalLink> nearby = pm.getLinksInRange(
            level.dimension(), posKey,
            ACTIVATION_THRESHOLD_BLOCKS + 2.0); // small margin for link scan
        if (nearby.isEmpty()) return;

        double bestAbsDist = Double.POSITIVE_INFINITY;
        PortalInfo bestPortal = null;
        double bestSigned = 0;
        for (PortalLink link : nearby) {
            PortalInfo src = link.getSource();
            if (src.getDimension() != level.dimension()) continue;
            // Signed distance eye → portal plane. Plane normal points
            // perpendicular to portal face (depth axis).
            Vec3 n = src.getNormal();
            Vec3 c = src.getCenter();
            double s = n.x * (eye.x - c.x) + n.y * (eye.y - c.y) + n.z * (eye.z - c.z);
            double abs = Math.abs(s);
            if (abs > ACTIVATION_THRESHOLD_BLOCKS) continue;
            // Also require that eye is laterally within the portal's
            // width/height (plus a little slack), otherwise clipping a
            // distant portal's plane from the middle of the world is
            // wrong.
            if (!isEyeLaterallyWithin(eye, src)) continue;
            if (abs < bestAbsDist) {
                bestAbsDist = abs;
                bestPortal = src;
                bestSigned = s;
            }
        }
        if (bestPortal == null) return;
        activePortal = bestPortal;
        signedDistance = (float) bestSigned;
        sideSign = signedDistance >= 0 ? 1.0f : -1.0f;
    }

    private static boolean isEyeLaterallyWithin(Vec3 eye, PortalInfo portal) {
        // Check eye within the portal's width × height footprint (± slack).
        BlockPos origin = portal.getOrigin();
        int w = portal.getWidth();
        int h = portal.getHeight();
        double slack = 1.5;
        double ex = eye.x;
        double ey = eye.y;
        double ez = eye.z;
        if (portal.getAxis() == Direction.Axis.X) {
            return ex >= origin.getX() - slack && ex <= origin.getX() + w + slack
                && ey >= origin.getY() - slack && ey <= origin.getY() + h + slack;
        } else {
            return ez >= origin.getZ() - slack && ez <= origin.getZ() + w + slack
                && ey >= origin.getY() - slack && ey <= origin.getY() + h + slack;
        }
    }

    public static boolean isActive() { return activePortal != null; }
    public static PortalInfo getActivePortal() { return activePortal; }

    /**
     * Apply the Lengyel oblique near-clip plane (GDC 2007) to the
     * projection matrix. Matches the algorithm from his published paper
     * directly, transcribed from the C++ / OpenGL column-major form to
     * JOML accessor methods.
     *
     * <p>World→camera transform used by MC: {@code P_view = viewRotation *
     * (P_world - cameraPos)}. Our viewRotation is a pure rotation, so the
     * plane-normal transform is simply {@code R * n_world}; the plane
     * offset in camera space is {@code n_world · (cameraPos - portalCenter)}.
     *
     * <p>Plane convention we use: {@code n · V + d > 0} is the KEPT
     * half-space. Lengyel's formula discards the other half.
     */
    public static void applyObliqueClip(
            Matrix4f projectionMatrix, Matrix4f viewMatrix, Vec3 cameraWorldPos) {
        if (activePortal == null) return;

        Vec3 nWorld0 = activePortal.getNormal();
        Vec3 cCenter = activePortal.getCenter();

        // Flip the normal so it points toward the camera side — keeps the
        // camera's side and clips the far side regardless of which side
        // the camera is on.
        double flip = sideSign >= 0 ? 1.0 : -1.0;
        double nwx = nWorld0.x * flip;
        double nwy = nWorld0.y * flip;
        double nwz = nWorld0.z * flip;

        // Plane offset in camera space. Since view is rotation-only,
        // n_world · (cameraPos - portalCenter) is the signed distance
        // from camera to plane along the (flipped) normal — this is d.
        float dCam = (float) (nwx * (cameraWorldPos.x - cCenter.x)
                            + nwy * (cameraWorldPos.y - cCenter.y)
                            + nwz * (cameraWorldPos.z - cCenter.z));

        // Rotate normal into camera space.
        Vector4f nView = new Vector4f((float) nwx, (float) nwy, (float) nwz, 0f);
        nView.mul(viewMatrix);

        Vector4f clipPlane = new Vector4f(nView.x, nView.y, nView.z, dCam);

        // Lengyel's Q: clip-space corner point opposite the plane,
        // transformed back to camera space via the inverse projection.
        // Hand-rolled for our known standard perspective projection:
        //   q.x = (sign(C.x) + m20) / m00
        //   q.y = (sign(C.y) + m21) / m11
        //   q.z = -1
        //   q.w = (1 + m22) / m32
        // In JOML's m{col}{row} naming, these translate directly.
        float qx = (Math.signum(clipPlane.x) + projectionMatrix.m20()) / projectionMatrix.m00();
        float qy = (Math.signum(clipPlane.y) + projectionMatrix.m21()) / projectionMatrix.m11();
        float qz = -1.0f;
        float qw = (1.0f + projectionMatrix.m22()) / projectionMatrix.m32();

        float dot = clipPlane.x * qx + clipPlane.y * qy
                  + clipPlane.z * qz + clipPlane.w * qw;
        if (Math.abs(dot) < 1.0e-6f) return;

        float scale = 2.0f / dot;
        float cx = clipPlane.x * scale;
        float cy = clipPlane.y * scale;
        float cz = clipPlane.z * scale;
        float cw = clipPlane.w * scale;

        // Overwrite third row (produces clip.z) of the projection.
        // Lengyel: matrix[2]=c.x, matrix[6]=c.y, matrix[10]=c.z+1, matrix[14]=c.w.
        // In JOML: m02=row2,col0 ; m12=row2,col1 ; m22=row2,col2 ; m32=row2,col3.
        projectionMatrix.m02(cx);
        projectionMatrix.m12(cy);
        projectionMatrix.m22(cz + 1.0f);
        projectionMatrix.m32(cw);
    }
}
