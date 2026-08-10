package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashSet;
import java.util.Set;

/**
 * ★ THE DEST-END AMBIENCE PASS (stitched-space contract item 3, user-confirmed 2026-08-10:
 * "the mirrored half-torch is real and SHOULD be emitting its own flame/smoke at the dest
 * independently"). The far end of a SAME-DIMENSION pair is display-tick dead by construction:
 * vanilla runs {@code animateTick} as 667×2 random samples within ±31 blocks of the PLAYER
 * ({@code Minecraft.tick} → {@code ClientLevel.animateTick}), drops spawns more than 32 blocks
 * from the CAMERA ({@code ClientLevel.doAddParticle}, constant 1024.0 squared), and the mod's
 * remote ticker ({@code ClientWorldLoader.tickRemoteWorldRandomTicksClient}) walks only
 * OTHER-dimension worlds — a same-dim destination is skipped by all three. So a mirrored torch
 * 90 blocks away never emits, and the window honestly shows a dead region (the r39/r41 sessions
 * measured extraction working whenever far particles existed; existence was the gap).
 *
 * <p>This pass display-ticks the DESTINATION region of nearby mirrorable same-dim portals:
 * <ul>
 *   <li>centered on the portal's DEST ORIGIN, not the transformed player — the block-era system
 *       recorded that the transformed player position drifts off the loaded/fed region when the
 *       player stands away from the portal (PortalWorldManager "ideal center" note);</li>
 *   <li>every other tick, matching the cadence IP chose for its cross-dim pass;</li>
 *   <li>with the camera temporarily moved to the anchor (IP's own {@code IECamera.portal_setPos}
 *       mechanism) so the 32-block creation gate passes — restored in a finally;</li>
 *   <li>with NO extra {@code particleEngine.tick()}: the engine is one shared multi-world pool
 *       and an extra tick double-ages every particle in it; spawns queue in
 *       {@code particlesToAdd} and the next vanilla engine tick drains them (one-tick latency,
 *       invisible);</li>
 *   <li>CROSS-DIM destinations are deliberately NOT handled here — IP's own remote pass already
 *       covers them (live-verified: the cross-dim fixture's mirrored torch emits).</li>
 * </ul>
 *
 * <p>Near destinations (within 16 blocks of the player) are skipped: vanilla's own range-16
 * sampling already covers them densely, and a second pass would only double emission rates.
 */
public final class SeamDestAmbience {

    private SeamDestAmbience() {}

    /** Vanilla covers this radius around the player densely on its own. Squared blocks. */
    private static final double VANILLA_COVERED_DIST_SQ = 16.0 * 16.0;
    /** Portal search radius around the player — the radius IP's remote pass uses. */
    private static final double PORTAL_RANGE = 10.0;

    private static final Set<Long> tickedAnchors = new HashSet<>();

    public static void tick(Minecraft mc) {
        if (AperturePassthroughLever.DISABLED
            || mc.level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        if (mc.level.getGameTime() % 2 != 0) {
            return;
        }
        tickedAnchors.clear();
        Vec3 playerPos = mc.player.position();
        CHelper.getClientNearbyPortals(PORTAL_RANGE).forEach(portal -> {
            if (portal.getDestDim() != mc.level.dimension()) {
                return;   // cross-dim: IP's remote pass owns it
            }
            if (!SeamMap.isMirrorable(portal)) {
                return;   // ambience is a seam feature; non-seam portals keep vanilla behavior
            }
            Vec3 dest = portal.getDestPos();
            if (dest == null) {
                return;
            }
            BlockPos anchor = BlockPos.containing(dest);
            if (!tickedAnchors.add(anchor.asLong())) {
                return;   // bi-faced pairs share destinations; one pass per anchor per tick
            }
            if (playerPos.distanceToSqr(dest) < VANILLA_COVERED_DIST_SQ) {
                return;   // vanilla's own player-centered sampling covers it
            }
            runAmbientPass(mc, anchor);
        });
    }

    private static void runAmbientPass(Minecraft mc, BlockPos anchor) {
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 oldCameraPos = camera.position();
        ((IECamera) camera).portal_setPos(Vec3.atCenterOf(anchor));
        try {
            mc.level.animateTick(anchor.getX(), anchor.getY(), anchor.getZ());
            if (SeamParticleProbe.armed()) {
                SeamParticleProbe.onAmbiencePass(anchor);
                SeamParticleProbe.tickSummary();
            }
        } catch (Throwable t) {
            // Ambience is cosmetic; a bad sample must never break the client tick.
        } finally {
            ((IECamera) camera).portal_setPos(oldCameraPos);
        }
    }
}
