package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.collision.PortalCollisionEntry;
import qouteall.imm_ptl.core.collision.PortalCollisionHandler;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalManipulation;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.Plane;

import java.util.WeakHashMap;

/**
 * S11-C (Slice A) port disposition: R3 COMPILE-LEVEL PORT — IP logic VERBATIM, re-expressed onto the
 * 26.2 submit render model per the design round {@code migration/port-notes/S11-R3-clip-bracketing.md}
 * (EXECUTION_PLAN S11 §929-940; API_RISKS R3). IP's 1.21.3 class cannot compile verbatim on 26.2 for two
 * reasons (render-core G2/G3/G23):
 * <ol>
 *   <li>the per-entity clip bracketing was forced out with mid-batch {@code BufferSource.endBatch()}
 *       splits (IP {@code :123,:139,:208,:308}) — 26.2 has no mid-batch flush model;</li>
 *   <li>the projection draw went through the private {@code LevelRenderer.renderEntity} duck
 *       ({@code IEWorldRenderer.ip_myRenderEntity}, IP {@code :301-306}) — GONE.</li>
 * </ol>
 *
 * <p>Re-expression (design §3.4 fate table):
 * <ul>
 *   <li>Every visibility gate, the collidedEntities bookkeeping, the projection substitution/pose math,
 *       and all the rough-check gating are ported <b>VERBATIM</b>.</li>
 *   <li>The two per-entity {@code endBatch()} clip splits (CASE 1) and the projection {@code endBatch()}
 *       (CASE 2) are REPLACED by the {@link PerEntityClipBracket} seam, which realizes IP's endBatch-split
 *       semantics natively on the 26.2 submit-order data model (Mechanism A, default) or an isolated
 *       one-entity {@code SubmitNodeStorage}+{@code renderAllFeatures} bracket (Mechanism B) — both stay
 *       wired behind {@code IPGlobal.crossPortalEntityClipMechanism} for the S18 live A/B test (C4 rider).</li>
 *   <li>The renderEntity duck is KILLED (render-core G3): {@code dispatcher.extractEntity} +
 *       {@code EntityRenderDispatcher.submit} (both public) replace {@code ip_myRenderEntity}.</li>
 * </ul>
 *
 * <p><b>26.2 signature adaptations (forced by the submit model, not IP-logic deviations):</b> the
 * WorldRenderer entity-loop hooks that IP fed from immediate-mode rendering now receive the submit
 * context (the {@link EntityRenderDispatcher}, the {@link CameraRenderState}, and the
 * {@link SubmitNodeStorage}) so the ported bodies can route through the seam. {@code beforeRenderingEntity}
 * + {@code afterRenderingEntity} fuse into {@link #submitMainPassEntity} (the endBatch bracket collapses
 * into the seam's per-order draw-call separation). The S12 anchor mixins (design §3.3) supply this
 * context: {@code submitEntities}-HEAD → {@link #onBeginRenderingEntitiesAndBlockEntities} +
 * {@link PerEntityClipBracket#onFrameSubmitBegin}; the per-entity submit {@code @WrapOperation} →
 * {@link #submitMainPassEntity}; {@code submitEntities}-TAIL →
 * {@link #onEndRenderingEntitiesAndBlockEntities}.
 *
 * <p><b>SIGN NOTE (D4.4):</b> no depth/winding constant is introduced here. Every clip plane flows through
 * the S11-B qouteall {@link FrontClipping} bridge (column-form {@code M·v}, {@code planeW = c}; the S11-A
 * anti-"fix" guard against {@code mulTranspose} applies) — see {@link PerEntityClipBracket} for the
 * view-space Snapshot derivation. The projection camera-pos substitution and the pose scale/rotation anchor
 * are pure point/vector transforms, ported byte-for-byte.
 *
 * <p><b>Expected forward-refs (documented debt, design §3.4; NOT translation slips):</b>
 * {@code render.renderer.PortalRenderer} (IP {@code :34,:386} — U10/S12). Everything else this class
 * consumes (IEEntity duck, PortalCollisionHandler/Entry, IrisInterface, Mirror, context_management,
 * McHelper/CHelper/Helper) is landed. LIVE since S13 (anchor mixins registered + firing); Mechanism B's
 * draw sites landed at S18 — only the C4 A/B live verdict remains.
 */
@Environment(EnvType.CLIENT)
public class CrossPortalEntityRenderer {
    private static final Minecraft client = Minecraft.getInstance();

    //there is no weak hash set
    private static final WeakHashMap<Entity, Object> collidedEntities = new WeakHashMap<>();

    public static boolean isRenderingEntityNormally = false;

    public static boolean isRenderingEntityProjection = false;

    public static void init() {
        IPGlobal.POST_CLIENT_TICK_EVENT.register(CrossPortalEntityRenderer::onClientTick);

        IPCGlobal.CLIENT_CLEANUP_EVENT.register(CrossPortalEntityRenderer::cleanUp);

        ClientWorldLoader.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT.register(dim -> cleanUp());
    }

    private static void cleanUp() {
        collidedEntities.clear();
        // S18 (design §1.2.5): reset the seam's identity maps alongside the collided set at the same
        // cleanup boundaries (world unload + dynamic dimension removal), so discarded renderers'
        // storages and stale phase registrations don't accumulate across dimension churn.
        PerEntityClipBracket.onClientCleanup();
    }

    private static void onClientTick() {
        collidedEntities.entrySet().removeIf(entry -> {
            Entity entity = entry.getKey();
            return entity.isRemoved() || !((IEEntity) entity).ip_isCollidingWithPortal();
        });
    }

    public static void onEntityTickClient(Entity entity) {
        if (entity instanceof Portal) {
            return;
        }

        if (((IEEntity) entity).ip_isCollidingWithPortal()) {
            collidedEntities.put(entity, null);
        }
    }

    // CASE 3 (design §0): whole-pass inner clip during a portal-view render. VERBATIM IP body — the
    // setupInnerClipping call feeds the single com.warwa view-space plane store through the S11-B
    // FrontClipping bridge (no batch split needed on 26.2). The S12 submitEntities-HEAD anchor calls this
    // (with the view-rotation model-view) and, separately, PerEntityClipBracket.onFrameSubmitBegin(storage)
    // to reset the per-frame seam state (design §3.3 row 1). NOTE: on 26.2 the effective whole-pass dest
    // clip is driven by the render driver's persistent store (the runtime-proven setupInnerClippingForEntities
    // pass); this submit-time hook is the IP-faithful anchor whose exact timing vs the driver store is the
    // extract-vs-render phase assignment finalized at S13 (CUTOVER_SPEC §4).
    public static void onBeginRenderingEntitiesAndBlockEntities(Matrix4f modelView) {
        isRenderingEntityNormally = true;

        if (PortalRendering.isRendering()) {
            FrontClipping.setupInnerClipping(
                PortalRendering.getActiveClippingPlane(),
                modelView, 0
            );
        }
    }

    private static boolean isCrossPortalRenderingEnabled() {
        if (IrisInterface.invoker.isIrisPresent()) {
            return false;
        }
        return IPGlobal.correctCrossPortalEntityRendering;
    }

    // 26.2 adaptation: receives the submit context (dispatcher/cam/storage) so the CASE 2 projections can
    // route through the seam. VERBATIM logic otherwise (the implicit draw-flush is GONE, design §3.4).
    public static void onEndRenderingEntitiesAndBlockEntities(
        EntityRenderDispatcher dispatcher,
        CameraRenderState cam,
        PoseStack matrixStack,
        SubmitNodeStorage storage
    ) {
        isRenderingEntityNormally = false;

        FrontClipping.disableClipping();

        if (!isCrossPortalRenderingEnabled()) {
            return;
        }

        renderEntityProjections(dispatcher, cam, matrixStack, storage);
    }

    /**
     * CASE 1 (design §0/§3.3 row 2): the fused {@code beforeRenderingEntity}/{@code afterRenderingEntity}
     * entry the S12 per-entity submit {@code @WrapOperation} anchor calls. Runs IP's VERBATIM decision
     * (collidedEntities membership + the portalCollisions loop) and, for a collided entity in the main
     * pass, submits it ONCE with the LAST colliding portal's outer clip plane (IP {@code :119-126}: the
     * per-portal endBatch loop's net effect is last-wins, single clipped draw — design §1.3). The two
     * {@code endBatch()} splits (IP {@code :123,:139}) collapse into the seam's per-order draw-call
     * separation.
     *
     * @return {@code true} if the entity was submitted with portal clipping (the anchor must skip the
     *         normal vanilla submit); {@code false} if the anchor should perform the vanilla submit.
     */
    public static boolean submitMainPassEntity(
        EntityRenderDispatcher dispatcher,
        EntityRenderState state,
        CameraRenderState cam,
        double camX, double camY, double camZ,
        PoseStack poseStack,
        SubmitNodeStorage storage,
        Entity entity
    ) {
        if (!isCrossPortalRenderingEnabled()) {
            frameProbe(entity, "MAIN vanilla-unclipped (cross-portal rendering disabled)");
            return false;
        }
        if (PortalRendering.isRendering()) {
            frameProbe(entity, "MAIN vanilla-unclipped (portal-view pass "
                + PortalRendering.getRenderingPortal().getId() + ")");
            return false;
        }
        if (!collidedEntities.containsKey(entity)) {
            frameProbe(entity, "MAIN vanilla-unclipped (not in collidedEntities)");
            return false;
        }

        PortalCollisionHandler collisionHandler = ((IEEntity) entity).ip_getPortalCollisionHandler();

        if (collisionHandler == null) {
            frameProbe(entity, "MAIN vanilla-unclipped (null handler)");
            return false;
        }

        // Stage 0 (engine design §1.3): selection delegated to the module — IP's last-wins
        // semantics hosted there verbatim; stage 2a makes it anchor-authoritative.
        Portal collidingPortal = com.warwa.seamlessportals.passthrough.SeamCrossingRule
            .resolveCrossingFace(entity, collisionHandler);

        if (collidingPortal == null) {
            frameProbe(entity, "MAIN vanilla-unclipped (empty entries)");
            return false;
        }

        frameProbe(entity, "MAIN clipped by face " + collidingPortal.getId()
            + " normal=" + collidingPortal.getNormal()
            + " entries=" + describeEntries(collisionHandler));
        PerEntityClipBracket.submitMainPassEntityClipped(
            dispatcher, state, cam, camX, camY, camZ, poseStack, storage, collidingPortal
        );
        return true;
    }

    // ═════════ F6 RENDER-PATH INSTRUMENT (probe-gated; the signature the arc was missing) ═════════

    /** Per-frame draw-decision record for carts + their riders, under -PseamCartProbe only. */
    private static void frameProbe(Entity entity, String what) {
        if (!com.warwa.seamlessportals.passthrough.AperturePassthroughLever.SEAM_CART_PROBE) {
            return;
        }
        if (!(entity instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart
            || (entity.isPassenger() && !(entity instanceof Player))
            || entity instanceof net.minecraft.world.entity.animal.cow.AbstractCow)) {
            return;
        }
        com.warwa.seamlessportals.passthrough.SeamCartProbe.event(entity, "[DRAW] " + what);
    }

    /**
     * F6: is the entity's through-portal image actually visible to the camera THROUGH the
     * portal's destination-side aperture? The visible (inner-clipped) image hugs the window
     * mouth, so the test is: the image's plane-projection lies within the aperture rectangle
     * (with a size margin), and the camera is on the opposite side of that plane from the
     * un-clipped image body (i.e., genuinely looking through). Errs toward drawing on any
     * geometric degeneracy — masking must never hide a legitimate image.
     */
    private static boolean isProjectionVisibleThroughAperture(Entity entity, Portal portal) {
        try {
            Vec3 cameraPos = client.gameRenderer.mainCamera().position();
            Vec3 entityInstantPos = McHelper.lastTickPosOf(entity)
                .lerp(entity.position(), RenderStates.getPartialTick());
            Vec3 imagePos = portal.transformPoint(entityInstantPos);
            var dest = portal.getOtherSideState();
            Vec3 camLocal = dest.transformGlobalToLocal(cameraPos);
            Vec3 imgLocal = dest.transformGlobalToLocal(imagePos);
            double margin = Math.max(entity.getBbWidth(), entity.getBbHeight()) + 0.4;
            // Two cases (the first shipped build collapsed these into a useless pair of
            // always-true checks — 857 draws, 0 masks, ghost intact):
            // SAME side: the inner-clipped image is emerged matter on the camera's side of the
            // window — a direct, legitimate view. Always draw.
            if (camLocal.z() * imgLocal.z() > 0) {
                return true;
            }
            // OPPOSITE sides: the camera claims to see the image THROUGH the window — require
            // the sight line to actually cross the plane inside the aperture rectangle. A
            // camera beside or behind the mouth fails this and the pasted ghost is masked.
            double dz = camLocal.z() - imgLocal.z();
            if (Math.abs(dz) < 1.0e-6) {
                return false;
            }
            double t = camLocal.z() / dz;
            double x = camLocal.x() + (imgLocal.x() - camLocal.x()) * t;
            double y = camLocal.y() + (imgLocal.y() - camLocal.y()) * t;
            return Math.abs(x) <= dest.width() / 2 + margin
                && Math.abs(y) <= dest.height() / 2 + margin;
        }
        catch (Throwable t) {
            return true;
        }
    }

    private static String describeEntries(@Nullable PortalCollisionHandler h) {
        if (h == null || h.portalCollisions.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (PortalCollisionEntry e : h.portalCollisions) {
            sb.append(e.portal.getId()).append('@').append(e.portal.getNormal()).append(' ');
        }
        return sb.append(']').toString();
    }

    //if an entity is in overworld but halfway through a nether portal
    //then it has a projection in nether
    private static void renderEntityProjections(
        EntityRenderDispatcher dispatcher,
        CameraRenderState cam,
        PoseStack matrixStack,
        SubmitNodeStorage storage
    ) {
        if (!isCrossPortalRenderingEnabled()) {
            return;
        }

        ResourceKey<Level> clientDim = client.level.dimension();

        for (Entity entity : collidedEntities.keySet()) {
            PortalCollisionHandler collisionHandler = ((IEEntity) entity).ip_getPortalCollisionHandler();

            if (collisionHandler != null) {
                for (PortalCollisionEntry e : collisionHandler.portalCollisions) {
                    Portal collidingPortal = e.portal;
                    if (!(collidingPortal instanceof Mirror)) {
                        ResourceKey<Level> projectionDimension = collidingPortal.getDestDim();
                        if (clientDim == projectionDimension) {
                            renderProjectedEntity(entity, collidingPortal, dispatcher, cam, matrixStack, storage);
                        }
                    }
                }
            }
        }
    }

    public static boolean hasIntersection(
        Vec3 outerPlanePos, Vec3 outerPlaneNormal,
        Vec3 entityPos, Vec3 collidingPortalNormal
    ) {
        return entityPos.subtract(outerPlanePos).dot(outerPlaneNormal) > 0.01 &&
            outerPlanePos.subtract(entityPos).dot(collidingPortalNormal) > 0.01;
    }

    private static void renderProjectedEntity(
        Entity entity,
        Portal collidingPortal,
        EntityRenderDispatcher dispatcher,
        CameraRenderState cam,
        PoseStack matrixStack,
        SubmitNodeStorage storage
    ) {
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();
            //correctly rendering it needs two culling planes
            //use some rough check to work around

            if (renderingPortal instanceof Portal) {
                // Stage 0 (engine design §1.3): the seam same-plane exception lives in the
                // module — the renderer may draw a window through EITHER co-located face, and
                // when it picks the TWIN, IP's flipped-skip would drop the straddler's
                // back-image (the window's only painter of the un-emerged half). The isHidden
                // camera guard is NOT bypassed (round 17). Non-seam portals keep IP's
                // flipped/reverse skips verbatim.
                boolean seamSamePlane = com.warwa.seamlessportals.passthrough.SeamCrossingRule
                    .inPassSamePlaneException(((Portal) renderingPortal), collidingPortal);
                if (!seamSamePlane
                    && (Portal.isFlippedPortal(((Portal) renderingPortal), collidingPortal)
                        || Portal.isReversePortal(((Portal) renderingPortal), collidingPortal))) {
                    frameProbe(entity, "PROJ inpass-skip flipped/reverse via face "
                        + collidingPortal.getId() + " in pass " + renderingPortal.getId());
                    com.warwa.seamlessportals.passthrough.SeamCrossingRule.shadowInPassProjection(
                        ((Portal) renderingPortal), collidingPortal,
                        collidingPortal.getInnerClipping(), false, "flipped-skip");
                }
                if (seamSamePlane
                    || (!Portal.isFlippedPortal(((Portal) renderingPortal), collidingPortal)
                        && !Portal.isReversePortal(((Portal) renderingPortal), collidingPortal))
                ) {
                    Vec3 cameraPos = client.gameRenderer.mainCamera().position();

                    Plane innerClipping = collidingPortal.getInnerClipping();

                    boolean isHidden = innerClipping != null &&
                        !innerClipping.isPointOnPositiveSide(cameraPos);
                    // Round 17: seamSamePlane no longer bypasses isHidden — the blanket bypass
                    // (round 16) let the twin's back-image draw for cameras on the WRONG side
                    // of the face ("cart leaks into source side a while crossing at the far
                    // station"). The skip below now logs its verdict, so if isHidden ever eats
                    // a legitimate back-image the lap's log will name it directly.
                    if (renderingPortal != collidingPortal && isHidden) {
                        frameProbe(entity, "PROJ inpass-hidden via face " + collidingPortal.getId()
                            + " in pass " + renderingPortal.getId()
                            + (seamSamePlane ? " (seam same-plane)" : ""));
                        com.warwa.seamlessportals.passthrough.SeamCrossingRule
                            .shadowInPassProjection(((Portal) renderingPortal), collidingPortal,
                                innerClipping, false, "isHidden");
                    }
                    if (renderingPortal == collidingPortal || !isHidden) {
                        com.warwa.seamlessportals.passthrough.SeamCrossingRule
                            .shadowInPassProjection(((Portal) renderingPortal), collidingPortal,
                                innerClipping, true, "drawn");
                        //IP draws these projections UNCLIPPED: onEndRenderingEntitiesAndBlockEntities
                        //disabled the CASE-3 clip (IP :101) and this isRendering branch (IP :184-204) sets
                        //NO clip — the rough flipped/reverse/isHidden checks above stand in for a second
                        //culling plane (IP :186-187). null → the seam registers an EXPLICITLY DISABLED
                        //snapshot (NOT the ambient dest inner clip), so the projection draws unclipped,
                        //matching IP (PerEntityClipBracket.submitProjectedEntityClipped; Verifier-1 P1).
                        //
                        // Stage 0 (engine design §1.3): the in-pass projection clip verdict
                        // lives in the module — seam faces always carry their real inner clip
                        // (the round-2 `clip=DISABLED` window-ghost fix); non-seam portals keep
                        // IP's null verbatim.
                        Plane seamInPassClip = com.warwa.seamlessportals.passthrough
                            .SeamCrossingRule.inPassProjectionClip(collidingPortal, innerClipping);
                        renderEntity(entity, collidingPortal, dispatcher, cam, matrixStack, storage,
                            seamInPassClip);
                    }
                }
            }
        }
        else {
            // Stage 0 (engine design §1.3): the crossing-window projection gate lives in the
            // module (IP's dropped hasIntersection, restored seam-correctly): a seam face
            // projects an entity only during its crossing window — pixel-identical for every
            // legitimate phase, and the co-located twin's whole-body approach ghost (the
            // "couple-seconds sighting") is impossible.
            if (!com.warwa.seamlessportals.passthrough.SeamCrossingRule
                    .mainPassProjectionAdmitted(entity, collidingPortal)) {
                frameProbe(entity, "PROJ gated (not crossing face "
                    + collidingPortal.getId() + ")");
                return;
            }
            // F6 APERTURE MASK (video 2026-08-12, frame-by-frame): the main-pass counterpart
            // used to draw whenever the entity had an entry — including from camera angles
            // where the window is edge-on or behind, pasting a floating image onto the near
            // scene ("renders on source side A for a second"). Only draw it where the camera
            // can actually see it THROUGH the aperture.
            if (!isProjectionVisibleThroughAperture(entity, collidingPortal)) {
                frameProbe(entity, "PROJ masked (not through aperture of face "
                    + collidingPortal.getId() + ")");
                return;
            }
            //IP :206-214: disableClipping + endBatch + setupInnerClipping(inner) collapse into the seam
            //bracket — the inner clip plane becomes the ARGUMENT threaded to the projection submit.
            renderEntity(
                entity, collidingPortal, dispatcher, cam, matrixStack, storage,
                collidingPortal.getInnerClipping()
            );
        }
    }

    private static void renderEntity(
        Entity entity,
        Portal transformingPortal,
        EntityRenderDispatcher dispatcher,
        CameraRenderState cam,
        PoseStack matrixStack,
        SubmitNodeStorage storage,
        @Nullable Plane innerClipPlane
    ) {
        Vec3 cameraPos = client.gameRenderer.mainCamera().position();

        ClientLevel newWorld = ClientWorldLoader.getWorld(transformingPortal.getDestDim());

        Vec3 entityPos = entity.position();
        Vec3 entityEyePos = McHelper.getEyePos(entity);
        Vec3 entityLastTickPos = McHelper.lastTickPosOf(entity);
        Vec3 entityLastTickEyePos = McHelper.getLastTickEyePos(entity);
        Level oldWorld = entity.level();

        Vec3 newEyePos = transformingPortal.transformPoint(entityEyePos);

        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();

            Vec3 transformedEntityPos = newEyePos.subtract(McHelper.getEyeOffset(entity));
            AABB transformedBoundingBox = McHelper.getBoundingBoxWithMovedPosition(entity, transformedEntityPos);

            boolean intersects = PortalManipulation.isOtherSideBoxInside(transformedBoundingBox, renderingPortal);

            if (!intersects) {
                frameProbe(entity, "PROJ skipped (outside rendering portal "
                    + renderingPortal.getId() + ")");
                return;
            }
        }

        frameProbe(entity, "PROJ via face " + transformingPortal.getId()
            + " clip=" + (innerClipPlane == null ? "DISABLED"
                : innerClipPlane.pos() + "/" + innerClipPlane.normal())
            + (PortalRendering.isRendering()
                ? " in-portal-pass " + PortalRendering.getRenderingPortal().getId() : " main-pass"));

        if (entity instanceof LocalPlayer) {
            if (!IPGlobal.renderYourselfInPortal) {
                return;
            }

            if (!transformingPortal.getDoRenderPlayer()) {
                return;
            }

            if (client.options.getCameraType().isFirstPerson()) {
                //avoid rendering player too near and block view
                double dis = newEyePos.distanceTo(cameraPos);
                double valve = 0.5 + entityLastTickPos.distanceTo(entityPos);
                if (transformingPortal.getScaling() > 1) {
                    valve *= transformingPortal.getScaling();
                }
                if (dis < valve) {
                    return;
                }

                AABB transformedBoundingBox =
                    Helper.transformBox(RenderStates.originalPlayerBoundingBox, transformingPortal::transformPoint);
                if (transformedBoundingBox.contains(CHelper.getCurrentCameraPos())) {
                    return;
                }
            }
        }

        isRenderingEntityProjection = true;
        matrixStack.pushPose();
        try {
            // we don't switch the entity position now
            // to make the entity to render in the new position,
            // we change the camera pos passed in

            // renderedPos = entityPos - cameraPos
            // cameraPos = entityPos - renderedPos

            // expectedRenderedPos = newEntityPos - cameraPos
            // newCameraPos = entityPos - expectedRenderedPos
            //              = entityPos - newEntityPos + cameraPos

            Vec3 entityInstantPos = entityLastTickPos.lerp(entityPos, RenderStates.getPartialTick());
            Vec3 newEntityInstantPos = transformingPortal.transformPoint(entityInstantPos);
            Vec3 newCameraPos = entityInstantPos.subtract(newEntityInstantPos).add(cameraPos);

            setupEntityProjectionRenderingTransformation(
                transformingPortal, matrixStack,
                entityPos, entityLastTickPos,
                newCameraPos
            );

            // Duck kill (render-core G3): the private LevelRenderer.renderEntity path
            // (IEWorldRenderer.ip_myRenderEntity) + consumers.endBatch() (IP :301-308) are GONE. Re-expressed
            // onto the public extractEntity + submit path — the projection is extracted fresh and routed
            // through the R3 seam (per-order clip on Mechanism A / isolated-storage bracket on Mechanism B).
            // The camera-pos substitution is preserved exactly: the seam submits at
            // (state.{x,y,z} - newCameraPos.{x,y,z}), i.e. the destination-transformed position relative to
            // the main camera (identical to IP feeding newCameraPos into ip_myRenderEntity).
            EntityRenderState projectionState =
                dispatcher.extractEntity(entity, RenderStates.getPartialTick());
            PerEntityClipBracket.submitProjectedEntityClipped(
                dispatcher, projectionState, cam, newCameraPos, matrixStack, storage,
                innerClipPlane, false
            );
        }
        finally {
            matrixStack.popPose();
            isRenderingEntityProjection = false;
        }
    }

    private static void setupEntityProjectionRenderingTransformation(
        Portal portal, PoseStack matrixStack,
        Vec3 entityPos, Vec3 entityLastTickPos, Vec3 cameraPos
    ) {
        if (portal.getScaling() == 1.0 && portal.getRotation() == null) {
            return;
        }

        Vec3 anchor = entityLastTickPos.lerp(entityPos, RenderStates.getPartialTick())
            .subtract(cameraPos);

        matrixStack.translate(anchor.x, anchor.y, anchor.z);

        float scaling = (float) portal.getScaling();
        matrixStack.scale(scaling, scaling, scaling);

        if (portal.getRotation() != null) {
            matrixStack.mulPose(portal.getRotation().toMcQuaternion());
        }

        matrixStack.translate(-anchor.x, -anchor.y, -anchor.z);
    }

    public static boolean shouldRenderPlayerDefault() {
        if (!IPGlobal.renderYourselfInPortal) {
            return false;
        }
        if (!WorldRenderInfo.isRendering()) {
            return false;
        }
        LocalPlayer player = client.player;
        assert player != null;

        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();
            if (renderingPortal instanceof Mirror) {
                // if the camera pos is too close to the mirror,
                // it will show the inside of the player head.
                // avoid rendering player in this case.
                float width = player.getBbWidth();
                if (renderingPortal.getDistanceToNearestPointInPortal(player.getEyePosition()) < width * 0.8) {
                    return false;
                }
            }
        }

        if (client.level == player.level()) {
            return true;
        }

        return false;
    }

    public static boolean shouldRenderEntityNow(Entity entity) {
        Validate.notNull(entity);
        if (IrisInterface.invoker.isRenderingShadowMap()) {
            return true;
        }
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();
            Portal collidingPortal = ((IEEntity) entity).ip_getCollidingPortal();

            if (entity instanceof Player && !renderingPortal.getDoRenderPlayer()) {
                return false;
            }

            // client colliding portal update is not immediate
            if (collidingPortal != null && !(entity instanceof LocalPlayer)) {
                if (renderingPortal instanceof Portal) {
                    // F6 SEAM STRADDLE-SIDE GATE (live round 2026-08-16 #2, log-nailed): while a
                    // seam crossing is in progress, the REAL entity may draw only in passes whose
                    // view side matches the side its colliding face fronts (pre-teleport: the
                    // departure side where the body still is; post-teleport: the arrival side it
                    // emerges into) — the other side's image is the PROJ path's job. Without this,
                    // the isReversePortal carve-out below skipped isHidden for the opposite-side
                    // pass and the binary eye-side test drew the whole rebased-trail cart
                    // vanilla-unclipped INTO that window for ~2 frames ("MAIN vanilla-unclipped
                    // (portal-view pass 5)" at the teleport tick — the user's window flash).
                    // Stage 0 (engine design §1.3): the straddle-side verdict lives in the
                    // module — side test against the pass's ARMED INNER CLIP normal (never
                    // contentDirection: it flips meaning with the renderer's co-located face
                    // pick), and the KEEP verdict is FINAL (no binary center-side test; the
                    // pass's plane-exact ambient clip cuts pixels).
                    switch (com.warwa.seamlessportals.passthrough.SeamCrossingRule
                        .inPassRealBodyVerdict(entity, collidingPortal, (Portal) renderingPortal)) {
                        case CULL -> {
                            frameProbe(entity, "VIS seam-side culled in portal-pass "
                                + renderingPortal.getId()
                                + " (pass shows the far side of straddled face "
                                + collidingPortal.getId() + ")");
                            return false;
                        }
                        case KEEP -> {
                            frameProbe(entity, "VIS seam-side kept in portal-pass "
                                + renderingPortal.getId() + " (straddling face "
                                + collidingPortal.getId() + ")");
                            return true;
                        }
                        case NOT_ENGAGED -> {
                        }
                    }
                    if (!Portal.isReversePortal(collidingPortal, ((Portal) renderingPortal))) {
                        Vec3 cameraPos = PortalRenderer.client.gameRenderer.mainCamera().position();

                        boolean isHidden = cameraPos.subtract(collidingPortal.getOriginPos())
                            .dot(collidingPortal.getNormal()) < 0;
                        if (isHidden) {
                            frameProbe(entity, "VIS hidden in portal-pass "
                                + renderingPortal.getId() + " (camera behind colliding face "
                                + collidingPortal.getId() + ")");
                            return false;
                        }
                    }
                }
            }

            boolean onDestSide = renderingPortal.isOnDestinationSide(
                getRenderingCameraPos(entity), -0.01
            );
            if (!onDestSide) {
                frameProbe(entity, "VIS culled in portal-pass " + renderingPortal.getId()
                    + " (not on destination side)");
            }
            return onDestSide;
        }
        return true;
    }

    public static boolean shouldRenderPlayerNormally(Entity entity) {
        if (!client.options.getCameraType().isFirstPerson()) {
            return true;
        }

        if (RenderStates.originalPlayerBoundingBox.contains(CHelper.getCurrentCameraPos())) {
            return false;
        }

        double distanceToCamera =
            getRenderingCameraPos(entity)
                .distanceTo(client.gameRenderer.mainCamera().position());
        //avoid rendering player too near and block view except mirror
        return distanceToCamera > 1 || PortalRendering.isRenderingOddNumberOfMirrors();
    }

    public static Vec3 getRenderingCameraPos(Entity entity) {
        if (entity instanceof LocalPlayer) {
            return RenderStates.originalPlayerPos.add(
                McHelper.getEyeOffset(entity)
            );
        }
        return entity.getEyePosition(RenderStates.getPartialTick());
    }
}
