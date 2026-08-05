package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.List;

/**
 * ★ THE WINDOW RULE FOR PARTICLES — round 34, the user's rule with the user's anchor, verbatim:
 * "if the window is between player and PARTICLE, it does not show". No side tests, no spawn
 * filtering, no source anchoring, no offsets — one question per particle per frame: does the
 * camera→particle segment cross a portal quad? Consumed by the main-pass world-filter wrap
 * ({@code MixinQuadParticleGroup}); the isolated dest extract applies the symmetric-emptiness
 * filter instead ({@code MixinParticleEngine.ip_extractIsolated}).
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
