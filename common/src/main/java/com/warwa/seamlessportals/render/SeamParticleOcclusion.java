package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.List;

/**
 * ★ THE WINDOW RULE FOR PARTICLES (user round 32, verbatim): "particles on one side should never
 * bleed to other side if portal window is between particle source block and player." Rounds
 * 27–31 tried side tests, spawn shifts and dying bands — every one wrong somewhere, because a
 * particle's visibility is not a property of which half it sits in; it is a property of what
 * stands between it and the camera. This class answers exactly that: does the camera→particle
 * segment cross a portal's quad? If yes, that region of space belongs to the window's view and
 * the particle does not render. From the side (no window between) it renders wherever it truly
 * is — positions are never moved.
 *
 * <p>Render-thread confined. The nearby-portal list refreshes once per game tick.
 */
public final class SeamParticleOcclusion {

    private SeamParticleOcclusion() {}

    private static List<Portal> portals = List.of();
    private static long cachedGameTime = Long.MIN_VALUE;
    private static Object cachedLevel = null;

    public static boolean occluded(Vec3 cameraPos, double px, double py, double pz) {
        if (!SeamFractional.active()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }
        long now = mc.level.getGameTime();
        if (now != cachedGameTime || cachedLevel != mc.level) {
            cachedGameTime = now;
            cachedLevel = mc.level;
            try {
                portals = CHelper.getClientNearbyPortals(64.0).toList();
            } catch (Throwable t) {
                portals = List.of();
            }
        }
        if (portals.isEmpty()) {
            return false;
        }
        for (Portal portal : portals) {
            if (segmentCrossesPortal(portal, cameraPos, px, py, pz)) {
                return true;
            }
        }
        return false;
    }

    private static boolean segmentCrossesPortal(
        Portal portal, Vec3 cam, double px, double py, double pz
    ) {
        Vec3 n = portal.getNormal();
        Vec3 o = portal.getOriginPos();
        double da = (cam.x - o.x) * n.x + (cam.y - o.y) * n.y + (cam.z - o.z) * n.z;
        double db = (px - o.x) * n.x + (py - o.y) * n.y + (pz - o.z) * n.z;
        if (da * db >= 0) {
            return false;   // both endpoints on one side: no crossing
        }
        double t = da / (da - db);
        double hx = cam.x + (px - cam.x) * t - o.x;
        double hy = cam.y + (py - cam.y) * t - o.y;
        double hz = cam.z + (pz - cam.z) * t - o.z;
        Vec3 w = portal.getAxisW();
        Vec3 h = portal.getAxisH();
        double u = hx * w.x + hy * w.y + hz * w.z;
        double v = hx * h.x + hy * h.y + hz * h.z;
        return Math.abs(u) <= portal.getWidth() / 2.0 + 1.0e-3
            && Math.abs(v) <= portal.getHeight() / 2.0 + 1.0e-3;
    }
}
