package qouteall.imm_ptl.core.render;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ScaleUtils;
import qouteall.imm_ptl.core.commands.PortalCommand;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;

// S11-C port disposition: VERBATIM IP logic (IP:render/CrossPortalViewRendering.java) re-expressed onto
// the mod's proven 26.2 render mechanics. Held/inert until S13 (ip_scc_closed filter). The class is the
// third-person / bob-through-portal cross-portal view path pinned by MixinGameRenderer.java:33,:155 (U10/S12).
//
// The ONLY non-verbatim hunks are three api-map-sanctioned 26.2 renames + one GONE-API re-expression:
//   1) client.cameraEntity FIELD is GONE on 26.2 -> client.getCameraEntity() (the exact translation already
//      applied in RenderStates.java:111-113 and FogRendererContext.java:122-125). 3 sites.
//   2) Camera.getPosition() -> Camera.position() (render-core; Camera.java:359). 1 site.
//   3) Camera.setup(BlockGetter, Entity, detached, mirrored, partialTick) is GONE (render-core G21) ->
//      setLevel + setEntity + update(DeltaTracker). See the derivation comment at the call site; this is the
//      SAME setup->update translation TransformationManager.java:230-239 already committed to, extended for a
//      FRESH camera (which must have level+entity installed before update()'s guards can run).
//
// Documented forward-ref debt (NOT translation slips; already in the probe ledger, EXECUTION_PLAN S11 §985):
//   - commands.PortalCommand (import :14, call :54) resolves U11 / S13.
//   - IPCGlobal.renderer (PortalRenderer, call :96) resolves U10 / S12.

// in third person view it may render cross portal.
// if view bobbing make the camera go through portal before the actual player go through portal,
//  it will also render cross-portal
public class CrossPortalViewRendering {
    public static final Minecraft client = Minecraft.getInstance();

    // if rendered, return true
    public static boolean renderCrossPortalView() {
        if (!IPGlobal.enableCrossPortalView) {
            return false;
        }

        Entity cameraEntity = client.getCameraEntity();

        Camera camera1 = new Camera();
        float cameraY = ((IECamera) RenderStates.originalCamera).ip_getCameraY();
        float lastCameraY = ((IECamera) RenderStates.originalCamera).ip_getLastCameraY();
        ((IECamera) camera1).ip_setCameraY(cameraY, lastCameraY);
        Camera camera = camera1;
        // 26.2: Camera.setup(BlockGetter, Entity, thirdPerson, frontView, partialTick) is GONE
        // (render-core G21). Its role — recompute this camera's pos/rotation from the cameraEntity while
        // honoring third-person (detached) / front-view (mirrored) — is now Camera.update(DeltaTracker):
        //   * update() reads level + entity ALREADY installed on the camera (guarded on
        //     minecraft.player != null && this.level != null), so a FRESH camera must have both set first —
        //     hence setLevel(client.level) + setEntity(cameraEntity) below (IP passed them as setup() args).
        //   * detached/mirrored are no longer parameters: update() -> alignWithEntity() reads them from
        //     options.getCameraType() itself — the EXACT booleans IP computes here (isThirdPerson() ==
        //     !getCameraType().isFirstPerson(); isFrontView() == getCameraType().isMirrored()), so the swap
        //     is behavior-preserving.
        //   * the ip_setCameraY above primes eyeHeight/eyeHeightOld BEFORE update(): 26.2 update() does not
        //     recompute the eye offset (only Camera.tick() does), so alignWithEntity() consumes the main
        //     camera's copied eye height exactly as IP's setup() used the copied cameraY.
        //   * partialTick is sourced from the frame DeltaTracker (getGameTimeDeltaPartialTick), equivalent
        //     to IP's RenderStates.getPartialTick() (the current frame partial tick).
        camera.setLevel(client.level);
        camera.setEntity(cameraEntity);
        camera.update(client.getDeltaTracker());

        Vec3 realCameraPos = camera.position();
        Vec3 isometricAdjustedOriginalCameraPos =
            TransformationManager.getIsometricAdjustedCameraPos(camera);

        Vec3 physicalPlayerHeadPos = ClientTeleportationManager.getPlayerEyePos(RenderStates.getPartialTick());

        Pair<Portal, Vec3> portalHit = PortalCommand.raytracePortals(
            client.level, physicalPlayerHeadPos, isometricAdjustedOriginalCameraPos, true
        ).orElse(null);

        if (portalHit == null) {
            return false;
        }

        Portal portal = portalHit.getFirst();
        Vec3 hitPos = portalHit.getSecond();

        if (!portal.canTeleportEntity(cameraEntity)) {
            return false;
        }

        Vec3 renderingCameraPos;

        if (isThirdPerson()) {
            double distance = getThirdPersonMaxDistance();

            Vec3 thirdPersonPos = realCameraPos.subtract(physicalPlayerHeadPos).normalize()
                .scale(distance).add(physicalPlayerHeadPos);

            renderingCameraPos = getThirdPersonCameraPos(thirdPersonPos, portal, hitPos);
        }
        else {
            renderingCameraPos = portal.transformPoint(realCameraPos);
        }

        ((IECamera) RenderStates.originalCamera).portal_setPos(renderingCameraPos);

        WorldRenderInfo worldRenderInfo = new WorldRenderInfo.Builder()
            .setWorld(ClientWorldLoader.getWorld(portal.getDestDim()))
            .setCameraPos(renderingCameraPos)
            .setCameraTransformation(portal.getAdditionalCameraTransformation())
            .setOverwriteCameraTransformation(false)
            .setDescription(null)
            .setRenderDistance(client.options.getEffectiveRenderDistance())
            .setDoRenderHand(false)
            .setEnableViewBobbing(false)
            .build();

        IPCGlobal.renderer.invokeWorldRendering(worldRenderInfo);

        return true;
    }

    private static boolean isFrontView() {
        return client.options.getCameraType().isMirrored();
    }

    private static boolean isThirdPerson() {
        return !client.options.getCameraType().isFirstPerson();
    }

    /**
     * {@link Camera#getMaxZoom}
     */
    @SuppressWarnings("JavadocReference")
    private static Vec3 getThirdPersonCameraPos(Vec3 endPos, Portal portal, Vec3 startPos) {
        Vec3 rtStart = portal.transformPoint(startPos);
        Vec3 rtEnd = portal.transformPoint(endPos);
        assert client.getCameraEntity() != null;
        BlockHitResult blockHitResult = portal.getDestinationWorld().clip(
            new ClipContext(
                rtStart,
                rtEnd,
                ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE,
                client.getCameraEntity()
            )
        );

        if (blockHitResult.getType() == HitResult.Type.BLOCK) {
            return rtStart.add(rtEnd.subtract(rtStart).normalize().scale(
                getThirdPersonMaxDistance()
            ));
        }

        return blockHitResult.getLocation();
    }

    private static double getThirdPersonMaxDistance() {
        return 4.0d * ScaleUtils.computeThirdPersonScale(client.player);
    }

    //    private static Vec3d getThirdPersonCameraPos(Portal portalHit, Camera resuableCamera) {
//        return CHelper.withWorldSwitched(
//            client.cameraEntity,
//            portalHit,
//            () -> {
//                World destinationWorld = portalHit.getDestinationWorld();
//                resuableCamera.update(
//                    destinationWorld,
//                    client.cameraEntity,
//                    true,
//                    isInverseView(),
//                    RenderStates.tickDelta
//                );
//                return resuableCamera.getPos();
//            }
//        );
//    }
}
