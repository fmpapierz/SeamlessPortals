package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * ★ THE WINDOW RULE FOR PARTICLES (user round 32, verbatim): "particles on one side should never
 * bleed to other side if portal window is between particle SOURCE BLOCK and player." Round 33
 * made the anchor exact: visibility is decided by the segment from the camera to the particle's
 * EMITTING BLOCK, not to the particle's drifting position — a smoke puff that wandered past the
 * plane keeps its torch's fate (hidden head-on with everything else the torch emits, visible
 * from the side as one continuous plume over both halves). Particles with no block source
 * (entity effects, explosions) fall back to their own position as the anchor.
 *
 * <p>Render-thread confined. The nearby-portal list refreshes once per game tick; source tags
 * live in a weak map and die with their particles.
 */
public final class SeamParticleOcclusion {

    private SeamParticleOcclusion() {}

    private static List<Portal> portals = List.of();
    private static long cachedGameTime = Long.MIN_VALUE;
    private static Object cachedLevel = null;

    /** The block whose animateTick is currently running — the emitting-source bracket. */
    private static BlockPos emitting = null;

    /** Particle → centre of the block that emitted it. Weak keys: tags die with the particle. */
    private static final Map<Particle, Vec3> SOURCES = new WeakHashMap<>();

    public static void beginEmitting(BlockPos pos) {
        emitting = pos.immutable();
    }

    public static void endEmitting() {
        emitting = null;
    }

    /** Called at the creation funnel: tag the newborn with the bracket's block, if any. */
    public static void tagIfEmitting(Particle particle) {
        if (emitting != null && particle != null) {
            SOURCES.put(particle, Vec3.atCenterOf(emitting));
        }
    }

    public static boolean occluded(
        Particle particle, Vec3 cameraPos, double px, double py, double pz
    ) {
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
        Vec3 anchor = SOURCES.get(particle);
        double ax = anchor != null ? anchor.x : px;
        double ay = anchor != null ? anchor.y : py;
        double az = anchor != null ? anchor.z : pz;
        for (Portal portal : portals) {
            if (segmentCrossesPortal(portal, cameraPos, ax, ay, az)) {
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
