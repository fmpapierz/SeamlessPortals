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
// the mod's proven 26.2 render mechanics. LIVE since S18: the IP handler-④ redirect is re-homed as
// MixinGameRenderer.seamlessportals$redirectRenderingWorld (@WrapOperation on render→renderLevel INVOKE —
// the S13 re-home had missed this one handler; zero call sites until S18). The class is the
// third-person / bob-through-portal cross-portal view path.
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

    /**
     * TP-XDIM — THE FRAME-REPLACEMENT LATCH. TRUE exactly while this class's render bracket is on
     * the stack: portal LAYER 0 (no pushPortalLayer), {@code mainRenderTarget()} is the GENUINE
     * main target, and vanilla {@code renderLevel} has been elided by
     * {@code MixinGameRenderer.seamlessportals$redirectRenderingWorld}.
     *
     * <p><b>Why a latch and not {@code PortalRendering.isRendering()}:</b> every other dest render
     * in this mod runs INSIDE {@code pushPortalLayer}, so {@code isRendering()==TRUE} is the
     * re-entrancy guard the design leans on. A frame-REPLACING render is a nested renderLevel at
     * LAYER 0, where {@code isRendering()} is FALSE — the three consumers below are blind to it by
     * construction. Pushing a layer instead is NOT an option: it would arm the inner clip plane,
     * and IP's cross view is unclipped (the camera is on the far side; the whole dest world is
     * visible, there is no aperture).
     *
     * <p><b>Why POSITIVE and not {@code !GuiPortalRendering.isRendering()}:</b> a negative test
     * would hand a full-pipeline main-target clobber to the next layer-0 caller anyone adds,
     * silently. Opt IN.
     *
     * <p>Consumers: (1) {@code IrisCompatOn262Renderer.invokeWorldRendering} — the D23 split;
     * (2) the Fabric AFTER_TRANSLUCENT_TERRAIN driver; (3)
     * {@code PerEntityClipBracket.onMainPassBeforeTranslucentTerrain}; and (4) XWIN —
     * {@code SecondaryWorldRenderCore.maybeRunCrossViewPortalPass}, the ONLY consumer that reads
     * the latch POSITIVELY (it fires the reverse-window pass precisely because this is a cross-view
     * frame, where the IS0 anchor cannot). (2) and (3) exist because
     * fabric level-render events RE-FIRE inside a real nested renderLevel (the doubly-load-bearing
     * guard documented at {@code IrisCompatOn262Renderer.onBeforeTranslucentRendering}) — under the
     * DECOMPOSED cross-view driver no framegraph runs and neither event fires at all, so honoring
     * the latch makes the two routes behaviour-identical at those seams. The no-regression argument
     * is structural, not empirical.
     *
     * <p>Render thread only. Cleared as the FIRST statement of the finally so no throw can strand
     * it — a stranded TRUE would silently kill the F1 driver and the entity bracket for the rest of
     * the session (the silent-portal-death class).
     */
    private static boolean renderingCrossPortalView = false;

    public static boolean isRenderingCrossPortalView() {
        return renderingCrossPortalView;
    }

    // if rendered, return true
    public static boolean renderCrossPortalView() {
        // TP-XDIM census (log-only, DEFAULT OFF, never throws; fully-qualified calls so this file
        // gains no import — the IrisCompatOn262Renderer precedent for qouteall -> com.warwa probe
        // calls). EVERY exit below is noted with its own code: "it did not render" must never
        // collapse into one unreadable bucket.
        com.warwa.seamlessportals.render.TpXdimFrameCensus.noteEnter();
        if (!IPGlobal.enableCrossPortalView) {
            com.warwa.seamlessportals.render.TpXdimFrameCensus.noteExit(
                com.warwa.seamlessportals.render.TpXdimFrameCensus.X_GATE_OFF);
            return false;
        }

        // TP-XDIM ESCAPE HATCH (-PcrossViewSuppressUnderPack, DEFAULT OFF). Decline the cross view
        // while a shaderpack is actually running: vanilla renderLevel then renders the frame
        // normally and the third-person camera sees the SOURCE world from inside the portal wall
        // (IP's pre-cross-view behaviour — clipping, NEVER corruption). The documented-limitation
        // fallback if the full-pipeline route proves unusable live, AND this arc's strongest
        // NEGATIVE discriminator: if the explosion SURVIVES this lever, the cross-view path is not
        // the carrier and the TP-XDIM hypothesis is refuted outright. Gated on isShaders() (not on
        // the pack-views flag) so it is inert for shaders-OFF users and the whole gametest suite.
        if (IPGlobal.CROSS_VIEW_SUPPRESS_UNDER_PACK_LEVER
            && qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface.invoker.isShaders()
        ) {
            com.warwa.seamlessportals.render.TpXdimFrameCensus.noteExit(
                com.warwa.seamlessportals.render.TpXdimFrameCensus.X_SUPPRESSED_UNDER_PACK);
            return false;
        }

        // S18.2 verify fold (wf_b9fd9266-022): the standing skip-never-assert rule (26.2 renders
        // frames MID-PACKET; the S15 pearl-freeze class). On a player/level mismatch frame the
        // head→camera segment would be raytraced in wrong-dim coordinates; vanilla shields the
        // hard-null cases at this call site, but the mismatch skip is the mod's own discipline
        // (mirrors MinecraftFramePumpMixin's pre-render guard). Also closes the theoretical
        // first-frame originalCamera-null window.
        if (client.player == null || client.level == null
            || client.player.level() != client.level
            || client.getCameraEntity() == null
            || RenderStates.originalCamera == null
        ) {
            // TP-XDIM: re-derive WHICH of the five sub-conditions tripped, in this guard's own
            // short-circuit order. The production guard is deliberately NOT split into five ifs —
            // a drift in the census's copy can only mis-LABEL a row, never mis-render a frame.
            com.warwa.seamlessportals.render.TpXdimFrameCensus.noteExitPrecondition();
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
            com.warwa.seamlessportals.render.TpXdimFrameCensus.noteExit(
                com.warwa.seamlessportals.render.TpXdimFrameCensus.X_NO_PORTAL_HIT);
            return false;
        }

        Portal portal = portalHit.getFirst();
        Vec3 hitPos = portalHit.getSecond();

        // TP-XDIM: physicalPlayerHeadPos and realCameraPos are BOTH in SOURCE space here — the only
        // space in which a signed distance to the SOURCE portal plane means anything (the
        // renderingCameraPos derived below is already dest-transformed).
        com.warwa.seamlessportals.render.TpXdimFrameCensus.notePortalHit(
            portal, physicalPlayerHeadPos, realCameraPos);

        if (!portal.canTeleportEntity(cameraEntity)) {
            com.warwa.seamlessportals.render.TpXdimFrameCensus.noteExit(
                com.warwa.seamlessportals.render.TpXdimFrameCensus.X_PORTAL_REJECTS_CAMERA);
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

        // 26.2 ADAPTATION (S18, driver-re-home-forced — documented in port-note S18 §2): IP's per-frame
        // switchToCorrectRenderer/prepareRendering/finishRendering ran from its GameRenderer handlers
        // ②/⑤ AROUND the renderLevel INVOKE — so IP's cross-view frame was implicitly bracketed. On
        // 26.2 those handlers were re-homed into the Fabric AFTER_TRANSLUCENT_TERRAIN listener, which
        // lives INSIDE the renderLevel this path REPLACES — on a cross-view frame it never fires. The
        // bracket therefore moves here (the exact GuiPortalRendering.renderWorldIntoFrameBuffer trio):
        // prepare arms the frame's stencil substrate (nested portals inside the cross view render as
        // portals from layer 0), finish in a finally mirrors IP's unconditional handler-⑤ order.
        //
        // S18.2 verify fold (wf_b9fd9266-022): the S13-M bobbed-projection capture is written only
        // INSIDE renderLevel — which this path SKIPS — so getPortalDrawProjection would otherwise
        // reuse the LAST NORMAL frame's frozen base*bob*spin (stale FOV/aspect + a bob offset this
        // pass explicitly disables). Null the capture: the getter then falls back to the CURRENT
        // frame's unbobbed cameraState.projectionMatrix (re-extracted every frame) — the correct,
        // IP-faithful projection for a bob-free cross view. The next normal frame recaptures.
        RenderStates.capturedMainPassBobbedProjection = null;

        // TP-XDIM: the render bracket is about to be entered — this is the point past which the
        // frame is committed to being rendered HERE instead of by vanilla renderLevel. irisPre is
        // captured now so it can be compared with irisPost below (the pipeline-slot leak question).
        com.warwa.seamlessportals.render.TpXdimFrameCensus.notePreRender(
            renderingCameraPos, isThirdPerson());

        qouteall.imm_ptl.core.render.renderer.PortalRenderer.switchToCorrectRenderer();
        IPCGlobal.renderer.prepareRendering();
        // TP-XDIM: armed AFTER prepareRendering (a stencil-disable belt; it renders nothing) and
        // BEFORE the invoke — the latch covers exactly the window in which a framegraph can run.
        renderingCrossPortalView = true;
        try {
            IPCGlobal.renderer.invokeWorldRendering(worldRenderInfo);
        } finally {
            // TP-XDIM: FIRST statement of the finally, ahead of every other unwind step, so no
            // throw anywhere below can strand the latch TRUE.
            renderingCrossPortalView = false;
            IPCGlobal.renderer.finishRendering();
            // S14.29 leak class (verify fold; the exact GuiPortalRendering.java:115-120 hardening):
            // the dest core's defensive Step-10.13 finally re-enables GL_STENCIL_TEST with EQUAL(0),
            // finishRendering() is IP-verbatim empty, and the renderLevel-HEAD frame-boundary guard
            // never fires on a cross-view frame — without this line the entity outline, post-effects,
            // and the whole GUI pass draw stencil-tested. 26.2 vanilla owns no stencil state to
            // restore it; IP exited cross-view frames stencil-disabled.
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
            // TP-XDIM: LAST statement of the finally. NOTE the honest limit — an UNCAUGHT throw
            // from the render bracket propagates past this method and out of GameRenderer.render,
            // so the census's TAIL row-builder never runs: that frame emits NO ROW AT ALL and the
            // buffered window is lost with it. This capture is visible on the normal path only.
            com.warwa.seamlessportals.render.TpXdimFrameCensus.notePostRender();
        }

        com.warwa.seamlessportals.render.TpXdimFrameCensus.noteExit(
            com.warwa.seamlessportals.render.TpXdimFrameCensus.X_RENDERED);
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
