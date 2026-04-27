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

        PortalManager manager = PortalManager.getClientInstance();
        ResourceKey<Level> dim = level.dimension();
        Iterable<PortalInfo> portals = manager.getTracker(dim).getAllPortals();

        Vec3 camPos = camera.position();
        Vec3 partPos = new Vec3(x, y, z);

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
}
