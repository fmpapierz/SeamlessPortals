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
 * McHelper/CHelper/Helper) is landed. Held/inert until S13; runtime wiring (anchor mixins + A/B verdict)
 * trails S18.
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
            return false;
        }
        if (PortalRendering.isRendering()) {
            return false;
        }
        if (!collidedEntities.containsKey(entity)) {
            return false;
        }

        PortalCollisionHandler collisionHandler = ((IEEntity) entity).ip_getPortalCollisionHandler();

        if (collisionHandler == null) {
            return false;
        }

        Portal collidingPortal = null;
        for (PortalCollisionEntry e : collisionHandler.portalCollisions) {
            //IP re-set the outer clip per colliding portal with an endBatch flush between (IP :119-126);
            //the net effect is the LAST portal's plane clips the entity's single draw. Registration
            //overwrites → same last-wins semantics.
            collidingPortal = e.portal;
        }

        if (collidingPortal == null) {
            return false;
        }

        PerEntityClipBracket.submitMainPassEntityClipped(
            dispatcher, state, cam, camX, camY, camZ, poseStack, storage, collidingPortal
        );
        return true;
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
                if (!Portal.isFlippedPortal(((Portal) renderingPortal), collidingPortal)
                    && !Portal.isReversePortal(((Portal) renderingPortal), collidingPortal)
                ) {
                    Vec3 cameraPos = client.gameRenderer.mainCamera().position();

                    Plane innerClipping = collidingPortal.getInnerClipping();

                    boolean isHidden = innerClipping != null &&
                        !innerClipping.isPointOnPositiveSide(cameraPos);
                    if (renderingPortal == collidingPortal || !isHidden) {
                        //IP draws these projections UNCLIPPED: onEndRenderingEntitiesAndBlockEntities
                        //disabled the CASE-3 clip (IP :101) and this isRendering branch (IP :184-204) sets
                        //NO clip — the rough flipped/reverse/isHidden checks above stand in for a second
                        //culling plane (IP :186-187). null → the seam registers an EXPLICITLY DISABLED
                        //snapshot (NOT the ambient dest inner clip), so the projection draws unclipped,
                        //matching IP (PerEntityClipBracket.submitProjectedEntityClipped; Verifier-1 P1).
                        renderEntity(entity, collidingPortal, dispatcher, cam, matrixStack, storage, null);
                    }
                }
            }
        }
        else {
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
                return;
            }
        }

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
                dispatcher, projectionState, cam, newCameraPos, matrixStack, storage, innerClipPlane
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
                    if (!Portal.isReversePortal(collidingPortal, ((Portal) renderingPortal))) {
                        Vec3 cameraPos = PortalRenderer.client.gameRenderer.mainCamera().position();

                        boolean isHidden = cameraPos.subtract(collidingPortal.getOriginPos())
                            .dot(collidingPortal.getNormal()) < 0;
                        if (isHidden) {
                            return false;
                        }
                    }
                }
            }

            return renderingPortal.isOnDestinationSide(
                getRenderingCameraPos(entity), -0.01
            );
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
