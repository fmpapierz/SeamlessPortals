package com.warwa.seamlessportals.client;

import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 3D-containment check for source-dim particles against active portals.
 *
 * <p>When the player stands in dim A and looks through a portal at dim B,
 * dim B is composited into the framebuffer through a stencil mask
 * (PortalContextSwitch.compositePortalFbo). After the composite,
 * vanilla rendering continues — terrain, entities, particles, etc. —
 * with whatever GL stencil state the composite left, but blaze3d's
 * render pipelines re-bind their own state for each draw, so raw
 * GL stencil function flips have no effect on the particle draw.
 *
 * <p>This class implements the same idea geometrically: a source-dim
 * particle whose camera-ray crosses an active portal quad before
 * reaching the particle is "behind the portal" from the camera's view.
 * Its image projects inside the portal opening and would overdraw the
 * dest-dim composite. Such particles must be culled before they enter
 * the render-state extraction.
 *
 * <p>Same line-vs-quad test as {@link PortalInfo#intersectsMovement},
 * which is also used by teleport detection — proven correct geometry.
 *
 * <p>Skipped when no portals are present in the player's current dim
 * (returns false fast).
 *
 * <p>§2c (2026-07-25): the portal ROSTER is config-split — flag-ON reads the IP Portal
 * ENTITIES (+ globals) via {@code IPMcHelper.getNearbyPortals} with {@code Portal.rayTrace}
 * as the segment test (the block-era {@code PortalManager} tracker is EMPTY flag-ON — all
 * its feeders are D3-gated; final-diff verify catch); flag-OFF keeps the block-era tracker
 * path byte-identical.
 */
public final class PortalParticleClip {

    private PortalParticleClip() {}

    /**
     * Check if a 3D position lies "behind" any active portal from the
     * camera's viewpoint — i.e., a straight line from camera to position
     * passes through a portal quad in the player's current dimension.
     *
     * @return true if the position should be culled (behind portal),
     *         false if it can render normally
     */
    public static boolean isPositionBehindPortal(
            double x, double y, double z, Camera camera) {
        if (camera == null) return false;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return false;

        Vec3 camPos = camera.position();
        Vec3 partPos = new Vec3(x, y, z);

        // §2c REPOINT (2026-07-25, final-diff verify catch): flag-ON portals are IP Portal
        // ENTITIES — they NEVER enter the block-era PortalManager tracker below (all five of its
        // feeders are D3-gated OFF flag-ON), so consulting the tracker flag-ON culls NOTHING.
        // The flag-ON roster = the IP portal entities (+ global portals) of the CAMERA's level,
        // collected once per frame (IPMcHelper.getNearbyPortals entity-radius traversal), tested
        // with the shape-aware segment raytrace (Portal.rayTrace = lenientRayTrace 0.001 —
        // segment crosses the portal shape ⇒ the particle projects inside the aperture from the
        // camera = cull). Invisible portals excluded (no dest view drawn over them). Ledgered:
        // renderMode=none (the dev master-off) still culls — acceptable, dev-only state.
        if (com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) {
            for (qouteall.imm_ptl.core.portal.Portal portal : flagOnRoster(level, camPos)) {
                if (portal.rayTrace(camPos, partPos) != null) {
                    return true;
                }
            }
            return false;
        }

        PortalManager manager = PortalManager.getClientInstance();
        ResourceKey<Level> dim = level.dimension();
        Iterable<PortalInfo> portals = manager.getTracker(dim).getAllPortals();

        for (PortalInfo portal : portals) {
            // intersectsMovement(from, to) returns true iff the segment
            // from→to crosses this portal's quad — i.e., from is on one
            // side of the portal plane, to is on the other, AND the
            // intersection lies within the portal frame's W×H bounds.
            // Exactly the visual-occlusion test we want: "particle is
            // behind the portal from the camera's view".
            if (portal.intersectsMovement(camPos, partPos)) {
                return true;
            }
        }
        return false;
    }

    /** Camera→particle segments only matter when the portal sits between them, and vanilla
     *  particles live within ~a chunk-load's throw of the player — 64 covers every practical
     *  camera→particle span while keeping the per-frame entity traversal cheap. */
    private static final double FLAG_ON_ROSTER_RANGE = 64.0;

    // Per-frame roster cache (render thread only): the redirect runs PER PARTICLE per group —
    // the entity-radius traversal must not. Keyed on RenderStates.frameIndex + level identity.
    private static java.util.List<qouteall.imm_ptl.core.portal.Portal> cachedRoster =
        java.util.List.of();
    private static ClientLevel cachedRosterLevel = null;
    private static int cachedRosterFrame = -1;

    private static java.util.List<qouteall.imm_ptl.core.portal.Portal> flagOnRoster(
            ClientLevel level, Vec3 camPos) {
        int frame = qouteall.imm_ptl.core.render.context_management.RenderStates.frameIndex;
        if (frame != cachedRosterFrame || cachedRosterLevel != level) {
            cachedRosterFrame = frame;
            cachedRosterLevel = level;
            cachedRoster = qouteall.imm_ptl.core.IPMcHelper
                .getNearbyPortals(level, camPos, FLAG_ON_ROSTER_RANGE)
                .filter(qouteall.imm_ptl.core.portal.Portal::isVisible)
                .toList();
        }
        return cachedRoster;
    }
}
