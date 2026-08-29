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

    /** ENGINE STAGE 2b: read-only view for the band painter's straddler enumeration. */
    public static java.util.Set<Entity> collidedEntitiesView() {
        return collidedEntities.keySet();
    }

    /** ENGINE STAGE 2b: the aperture-mask admissibility, shared with the band painter's piece 2
     *  (design §2.5: never wider than what the main-pass projection drew this frame). */
    public static boolean projectionVisibleThroughAperture(Entity entity, Portal portal) {
        return isProjectionVisibleThroughAperture(entity, portal);
    }

    public static boolean isRenderingEntityNormally = false;

    public static boolean isRenderingEntityProjection = false;

    /**
     * ★ ROUND 31 (D2) — THE PROJECTION CAMERA DISTANCE OVERRIDE. {@code -1.0} = unset.
     *
     * <p>A projected entity is DRAWN at {@code transformPoint(entityInstantPos)} relative to the
     * real camera, but vanilla stamps {@code EntityRenderState.distanceToCameraSq} from
     * {@code EntityRenderDispatcher.distanceToSqr(entity)} — the entity's REAL position. On a far
     * seam those differ by ~691 blocks, giving ~4.8e5 against vanilla's shadow threshold of 256
     * ({@code EntityRenderer.extractShadow}: the shadow-piece loop only runs while
     * {@code pow = (1 - distSq/256) * strength > 0}). So EVERY projected image is submitted with
     * an EMPTY {@code shadowPieces} list and {@code EntityRenderDispatcher} skips
     * {@code submitShadow} entirely — the user-reported "the ENTIRE shadow disappears when it
     * touches the seam". Not the clip, not the band: the decal is never built.
     *
     * <p>Read by {@code MixinEntityRenderDispatcher.onDistanceToSqr}, which returns it in place of
     * the real distance while it is set. Scoped to a single {@code extractEntity} call and cleared
     * in a {@code finally} — it must never outlive that call, or an unrelated entity's distance
     * would be answered with a stale value. Render-thread-only, like everything on this path.
     * Lever {@code -PdisableSeamProjectionCameraDistance}.
     */
    public static double projectionCameraDistanceSqOverride = -1.0;

    /**
     * ★ PROJECTION LIGHT (2026-08-24). While a projection's {@code extractEntity} runs, these
     * redirect {@code EntityRenderer.getPackedLightCoords} to sample the IMAGE position in the
     * projection's target level instead of the entity's real position in its own level — the
     * cross-dim back-half image otherwise carries source-level light coords interpreted through
     * the viewing dimension's lightmap (nether sky=0 under the overworld night lightmap =
     * near-black; the user's "source side becomes much darker" report). Same scoping contract
     * as {@link #projectionCameraDistanceSqOverride}: set for one extraction, cleared in a
     * {@code finally}, render-thread-only. Read by {@code MixinEntityRenderer_ProjectionLight}.
     * Lever {@code -PdisableSeamProjectionLight}.
     */
    @Nullable
    public static ClientLevel projectionLightWorld = null;
    public static Vec3 projectionLightPos = Vec3.ZERO;

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

        // ★ ROUND 36: one tick spans several frames; the probe must be able to tell them apart.
        // ⚠ This hook fires once PER PASS (main pass + one per portal pass), not once per frame —
        // the first version counted pass-ends and made every portal pass look like a "frame with
        // no main-pass projection", which is trivially true and meaningless. Only the MAIN pass
        // (isRendering == false) opens a new frame.
        if (!qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
            // ★ ROUND 36 v4 — report the PREVIOUS frame's execute-side truth before resetting.
            // Phases execute AFTER this hook's submits, so the counts for frame N are only
            // complete when frame N+1 begins. A frame with healthy submits but ORANGE-EXEC=0 is
            // the RenderDoc signature reproduced in the log: the registered snapshot was never
            // found at execute time, so the tint (and the CLIP) never reached the GPU.
            if (com.warwa.seamlessportals.passthrough.AperturePassthroughLever.SEAM_CART_PROBE
                && probeSubmitsThisFrame > 0) {
                com.warwa.seamlessportals.passthrough.SeamCartProbe.rpc(
                    "[EXEC] f=" + probeFrameCounter
                        + " projSubmits=" + probeSubmitsThisFrame
                        + " phasesExecuted=" + PerEntityClipBracket.phasesExecuted
                        + " registryHits=" + PerEntityClipBracket.phasesRegisteredHit
                        + " TINTED-EXEC=" + PerEntityClipBracket.orangePhasesExecuted
                        + (PerEntityClipBracket.orangePhasesExecuted == 0
                            ? "  *** NO TINTED SNAPSHOT REACHED EXECUTE ***" : ""));
            }
            PerEntityClipBracket.phasesExecuted = 0;
            PerEntityClipBracket.phasesRegisteredHit = 0;
            PerEntityClipBracket.orangePhasesExecuted = 0;
            probeSubmitsThisFrame = 0;

            onFrameBegin();
            com.warwa.seamlessportals.passthrough.SeamRenderExtent.onFrameBegin();
            mainPassProjectionFaces.clear();
        }

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
            return seamlessportals$straddleFallbackClip(
                dispatcher, state, cam, camX, camY, camZ, poseStack, storage, entity,
                "not in collidedEntities");
        }

        PortalCollisionHandler collisionHandler = ((IEEntity) entity).ip_getPortalCollisionHandler();

        if (collisionHandler == null) {
            return seamlessportals$straddleFallbackClip(
                dispatcher, state, cam, camX, camY, camZ, poseStack, storage, entity,
                "null handler");
        }

        // Stage 0 (engine design §1.3): selection delegated to the module — IP's last-wins
        // semantics hosted there verbatim; stage 2a makes it anchor-authoritative.
        Portal collidingPortal = com.warwa.seamlessportals.passthrough.SeamCrossingRule
            .resolveCrossingFace(entity, collisionHandler);

        if (collidingPortal == null) {
            return seamlessportals$straddleFallbackClip(
                dispatcher, state, cam, camX, camY, camZ, poseStack, storage, entity,
                "empty entries");
        }

        frameProbe(entity, "MAIN clipped by face " + collidingPortal.getId()
            + " normal=" + collidingPortal.getNormal()
            + " entries=" + describeEntries(collisionHandler));
        PerEntityClipBracket.submitMainPassEntityClipped(
            dispatcher, state, cam, camX, camY, camZ, poseStack, storage, collidingPortal,
            entity.getId(), entity.getType().toString(),
            seamlessportals$anchoredKeptDirection(entity, collidingPortal)
        );
        return true;
    }

    /**
     * ★ ANCHORED-BODY ORIENTATION (2026-08-24): for an entity clipped by its live ANCHOR (a
     * just-arrived crossing), the kept side of the outer plane is the side being crossed
     * INTO — the direction of travel — because the two arrival doors of a seam cell carry
     * opposite baked shape orientations. Null for non-anchored draws and parked arrivals.
     *
     * <p>v2 (keep-away-from-camera, same day) is REVERTED: it cut an ARRIVING cart's emerged
     * half for every viewer standing in the arrival region (the cart went invisible until
     * ANCHOR-CLOSE, "reappears 3-4 blocks after crossing") while not fixing the side-b gap it
     * targeted. The residual this leaves — the far-side viewer's vanish window between the
     * flip and visual emergence — is a four-region view-matrix problem (which pass owns which
     * screen region per camera side), not a one-plane orientation problem; it needs the
     * matrix designed against the fractional-seam pass structure, not another flip of this
     * vector.
     */
    @Nullable
    private static Vec3 seamlessportals$anchoredKeptDirection(Entity entity, Portal face) {
        if (!com.warwa.seamlessportals.passthrough.SeamCrossingRule.isAnchoredAt(entity, face)) {
            return null;
        }
        Vec3 vel = McHelper.getWorldVelocity(entity);
        return vel.lengthSqr() > 1.0e-6 ? vel : null;
    }

    /**
     * ★ REAL-BODY OVERHANG (2026-08-24, the "touching seam on opposite side, bleeding to my
     * side, instantly disappears as it crosses" report). The CASE-1 outer clip only engages for
     * PHYSICS-BOOKED entities, but the booking lags the DRAWN model (the round-35 box-to-model
     * gap, real-body edition): a tagged near-seam entity whose model already overhangs the
     * plane drew "MAIN vanilla-unclipped (not in collidedEntities)" — 166 frames in the
     * 2026-08-24 census — poking its unclipped tip through the seam into the far segment until
     * the flip deleted the instance. The fallback clips by the render-side straddle candidates:
     * the face the entity's center is IN FRONT of (its outer clip keeps the body's own side).
     * Over-eager clipping of the REAL body is a structural no-op — until the model actually
     * overhangs, every drawn pixel is on the kept side — which is the mirror of the projection
     * rule (over-eager projection ADMISSION paints ghosts; over-eager body CLIPPING paints
     * nothing extra and deletes nothing real).
     */
    private static boolean seamlessportals$straddleFallbackClip(
        EntityRenderDispatcher dispatcher, EntityRenderState state, CameraRenderState cam,
        double camX, double camY, double camZ, PoseStack poseStack, SubmitNodeStorage storage,
        Entity entity, String reason
    ) {
        // ★ FLASH FIX (2026-08-24, "flash of half a cart on my source side cut off at the
        // seam"): an ANCHORED entity is a just-arrived one whose CARRIED visual still sits
        // behind the arrival plane — in the observer's world that region belongs to the OTHER
        // path's segment, so a center-side pick clips keep-BEHIND and draws the half-cart lie
        // for the lerp ticks until the visual emerges (same-dim tolerates the behind-plane
        // visual — same rail; the four-door seam does not). Clip by the ANCHOR, exactly as the
        // booked path's stage-2a resolution does: the arrival side is kept, the behind-plane
        // visual is cut entirely, and the emerging part grows out of the window. The
        // center-side pick remains for UNANCHORED entities — the pre-crossing departure
        // overhang it was built for, where keep-own-side is the correct cut.
        Portal anchor = com.warwa.seamlessportals.passthrough.SeamCrossingRule.anchorOf(entity);
        if (anchor != null && !anchor.isRemoved() && anchor.level() == entity.level()) {
            frameProbe(entity, "MAIN clipped by face " + anchor.getId()
                + " (anchor fallback; " + reason + ")");
            PerEntityClipBracket.submitMainPassEntityClipped(
                dispatcher, state, cam, camX, camY, camZ, poseStack, storage, anchor,
                entity.getId(), entity.getType().toString(),
                seamlessportals$anchoredKeptDirection(entity, anchor)
            );
            return true;
        }
        for (Portal g : com.warwa.seamlessportals.passthrough.SeamCrossingRule
                .mustBookCandidates(entity)) {
            double side = entity.position().subtract(g.getOriginPos()).dot(g.getNormal());
            if (side > 0) {
                frameProbe(entity, "MAIN clipped by face " + g.getId()
                    + " (render-side straddle fallback; " + reason + ")");
                PerEntityClipBracket.submitMainPassEntityClipped(
                    dispatcher, state, cam, camX, camY, camZ, poseStack, storage, g,
                    entity.getId(), entity.getType().toString()
                );
                return true;
            }
        }
        frameProbe(entity, "MAIN vanilla-unclipped (" + reason + ")");
        return false;
    }

    // ═════════ F6 RENDER-PATH INSTRUMENT (probe-gated; the signature the arc was missing) ═════════

    /** Per-frame draw-decision record for carts + their riders, under -PseamCartProbe only. */
    /**
     * ★ ROUND 36 — THE FRAME COUNTER. The probe stamps the TICK (t=), and a tick spans several
     * frames, so a painter that draws on some frames of a tick and not others reads as a healthy
     * per-tick draw count. That is exactly how the face cut hid: 40558 main-pass projection draws
     * across the run, "2-6 per tick" steady, while a RenderDoc capture of one cut FRAME showed
     * the projection's tint uploaded ZERO times. Every probe line now carries f=<frame> so the
     * per-frame picture is legible.
     */
    private static long probeFrameCounter = 0;

    /**
     * ★ ROUND 36: incremented wherever a MAIN-PASS projection actually reaches its draw, so the
     * per-frame verdict can state DREW vs ABSENT outright instead of leaving absence to be
     * inferred from missing log lines — the inference that hid this defect for six rounds.
     */
    private static int mainPassProjectionDrawCount = 0;

    /**
     * ★ ROUND 36 v2: which FACE ids actually reached a main-pass projection draw this frame.
     * Per-face, because a co-located twin drawing is not the same as the anchor face drawing —
     * v1's any-face counter reported DREW in exactly that case and hid the defect.
     */
    private static final java.util.Set<Integer> mainPassProjectionFaces = new java.util.HashSet<>();

    /**
     * ★ ROUND 36 v4: main-pass projection submits issued this frame, paired with the execute-side
     * counters in {@link PerEntityClipBracket} so a frame can be read as
     * submitted-but-never-executed — the state RenderDoc showed and no probe could see.
     */
    private static int probeSubmitsThisFrame = 0;

    public static void onFrameBegin() {
        probeFrameCounter++;
    }

    private static void frameProbe(Entity entity, String what) {
        if (!com.warwa.seamlessportals.passthrough.AperturePassthroughLever.SEAM_CART_PROBE) {
            return;
        }
        what = "f=" + probeFrameCounter + " " + what;
        // ★ ROUND 35 — THE PROBE FILTER WAS SPECIES-SCOPED, WHICH MADE EVERY NON-COW LAP VACUOUS.
        // It admitted only minecarts, passengers and cows, so a horse, boat, armour stand or
        // elytra player crossing the seam logged ZERO lines — and a silent log reads as "clean"
        // rather than "unobserved", the exact false reading this project has paid for repeatedly.
        // Now: anything the seam machinery is actually tracking (a live crossing anchor or a
        // colliding portal) is watched, whatever species it is. The user's requirement is that
        // fixes cover "every single type of rider, entity, literally everything" — an instrument
        // that cannot SEE those entities cannot verify a fix for them.
        boolean tracked =
            com.warwa.seamlessportals.passthrough.SeamCrossingRule.anchorOf(entity) != null
                || ((IEEntity) entity).ip_getCollidingPortal() != null;
        if (!tracked
            && !(entity instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart
                || (entity.isPassenger() && !(entity instanceof Player)))) {
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

        // ★ ROUND 36 — THE SILENT EXITS, MADE OBSERVABLE.
        //
        // A RenderDoc capture diff proved the defect lives HERE, not downstream: on a cut frame
        // the ORANGE (main-pass projection) tint uniform is uploaded ZERO times, while on a good
        // frame it is uploaded 4 times, with everything else in the two frames identical (GREEN
        // 11 vs 10, YELLOW 8 vs 8, same clip planes). So the projection is not clipped, not
        // depth-rejected and not stencil-masked on cut frames — it is NEVER SUBMITTED.
        //
        // Every exit in this loop was silent, which is why the probe census looked clean while
        // pixels went missing: the later gates (gated/masked/skipped) log, but "entity absent
        // from collidedEntities", "no collision entries", "is a Mirror" and "dimension mismatch"
        // logged NOTHING. Silence read as coverage. Worse, the census counts draws PER TICK and a
        // tick spans several frames, so "2-6 draws per tick" was consistent with "some frames in
        // that tick drew nothing" — which is exactly what was happening.
        //
        // These probes are DIAGNOSTIC ONLY (SEAM_CART_PROBE-gated, byte-inert otherwise).
        for (Entity entity : collidedEntities.keySet()) {
            PortalCollisionHandler collisionHandler = ((IEEntity) entity).ip_getPortalCollisionHandler();

            // ★ ROUND 36 — THE MAIN-PASS VERDICT LINE. In the MAIN pass, every seam-tracked
            // entity reports whether a main-pass projection was drawn for it THIS FRAME. A frame
            // where the projection is absent now states so outright, instead of being an absence
            // I have to infer from missing lines — which is what let this hide for six rounds.
            boolean mainPass = !qouteall.imm_ptl.core.render.context_management
                .PortalRendering.isRendering();
            int drewBefore = mainPass ? mainPassProjectionDrawCount : 0;

            if (collisionHandler == null) {
                frameProbe(entity, "PROJ-SILENT no collision handler");
                continue;
            }
            if (collisionHandler.portalCollisions.isEmpty()) {
                frameProbe(entity, "PROJ-SILENT zero collision entries (nothing to project through)");
                continue;
            }
            {
                for (PortalCollisionEntry e : collisionHandler.portalCollisions) {
                    Portal collidingPortal = e.portal;
                    if (collidingPortal instanceof Mirror) {
                        frameProbe(entity, "PROJ-SILENT face " + collidingPortal.getId() + " is a Mirror");
                    }
                    if (!(collidingPortal instanceof Mirror)) {
                        ResourceKey<Level> projectionDimension = collidingPortal.getDestDim();
                        if (clientDim != projectionDimension) {
                            frameProbe(entity, "PROJ-SILENT face " + collidingPortal.getId()
                                + " dim mismatch: client=" + clientDim
                                + " projection=" + projectionDimension);
                        }
                        if (clientDim == projectionDimension) {
                            renderProjectedEntity(entity, collidingPortal, dispatcher, cam, matrixStack, storage);
                        }
                    }
                }
            }

            // ★ ROUND 36 v2 — THE VERDICT, REBUILT SO IT CANNOT BE SILENT.
            //
            // v1 was structurally vacuous and its "DREW 1470 / ABSENT 0" ledger is RETIRED. Two
            // defects, both of which hid the failure rather than reporting it:
            //   (i) it gated on isSeamContinuous(portalCollisions.get(0)) — only the FIRST entry.
            //       These entities also hold a NETHER-portal entry (426 dim-mismatch lines in the
            //       same log), so whenever entry 0 was the nether portal the verdict printed
            //       NOTHING. Measured: verdict lines on 753 of 1652 seam-active frames.
            //   (ii) `drew` was a per-entity ANY-FACE counter, so a frame where the correct face
            //       was dropped but its co-located TWIN drew still reported DREW.
            // Now: one verdict PER SEAM FACE, keyed to that face, emitted unconditionally.
            if (mainPass) {
                for (PortalCollisionEntry e : collisionHandler.portalCollisions) {
                    Portal face = e.portal;
                    if (!com.warwa.seamlessportals.passthrough.SeamCartContinuity
                            .isSeamContinuous(face)) {
                        continue;
                    }
                    boolean straddles = com.warwa.seamlessportals.passthrough.SeamStraddleBracket
                        .straddlesForDraw(entity, face);
                    boolean drewThisFace = mainPassProjectionFaces.contains(face.getId());
                    frameProbe(entity, "PROJ-FACE-VERDICT face " + face.getId()
                        + " mainPassProjection=" + (drewThisFace ? "DREW" : "*** ABSENT ***")
                        + " modelStraddles=" + straddles
                        + " entries=" + collisionHandler.portalCollisions.size());
                }
            }

            // ★ ROUND 35 — THE RENDER-SIDE BOOKING SUPPLEMENT (the face cut).
            //
            // The loop above is the projection painter's ONLY source, and it is the PHYSICS
            // collision booking — scoped to the COLLISION BOX expanded by velocity, with zero
            // model margin (CollisionHelper). The seam clip cuts the DRAWN MODEL, which reaches
            // further, and by a per-type amount. Measured across all 8 crossings of the
            // 2026-08-20 lap: booking fires at a near-constant ~0.71 blocks from the plane while
            // a cow's muzzle crosses at 0.9375 — so for ~0.23 blocks of travel the emerged muzzle
            // has NO PAINTER AT ALL. Not culled: never invoked. The probe log shows the
            // signature directly — 37 consecutive ticks with no PROJ line of any kind (every
            // early return in renderProjectedEntity emits one), while the module's own straddle
            // predicate already reported the cow straddling that face.
            //
        }

        // ★ ROUND 35 — THE RENDER-SIDE BOOKING SUPPLEMENT (the face cut).
        //
        // ⚠ THIS MUST LIVE OUTSIDE THE collidedEntities LOOP. The first attempt placed it inside
        // and it was dead code: collidedEntities is populated from ip_isCollidingWithPortal()
        // (:162-163) — the SAME box-scoped collision that does the booking — so during the
        // pre-booking gap the entity is not in that map and the loop never reaches it. It logged
        // ZERO supplements across 7 live crossings.
        //
        // THE DEFECT: the projection painter's only source is the PHYSICS collision booking,
        // scoped to the COLLISION BOX expanded by velocity, with zero model margin
        // (CollisionHelper). The seam clip cuts the DRAWN MODEL, which reaches further and by a
        // per-type amount. Measured across all 8 crossings of the 2026-08-20 lap: booking fires
        // at a near-constant ~0.71 blocks from the plane while a cow's muzzle crosses at 0.9375
        // — so for ~0.23 blocks of travel the emerged muzzle has NO PAINTER AT ALL. Not culled:
        // never invoked. Signature in the probe log: 37 consecutive ticks with no PROJ line of
        // any kind (every early return in renderProjectedEntity emits one) while the module's
        // own straddle predicate already reported the straddle.
        //
        // Render-only: PortalCollisionHandler is never written, so physics, teleport timing and
        // collision stay byte-identical. Every supplemented face still runs
        // mainPassProjectionAdmitted, the aperture mask and the locality gate, so the round-10
        // error direction holds — an over-admitted projection clips to nothing.
        // Lever -PdisableSeamRenderBooking.
        // ★ ROUND 36 v5 — THE DIAGNOSTIC RUNS UNCONDITIONALLY; ONLY THE FIX IS LEVERED.
        // v2 put this loop inside the lever gate, so it went silent exactly when the supplement
        // was off — and the one measurement that would have identified this defect ("the cart is
        // absent from collidedEntities while its model straddles") reported zero, for the same
        // reason the defect itself was invisible. That is the sixth instrument in this session to
        // measure its own switch rather than the world. Diagnostic OUT of the gate; fix INSIDE.
        {
            for (Entity entity : client.level.entitiesForRendering()) {
                if (collidedEntities.containsKey(entity)) {
                    continue;   // the physics loop above already handled this one
                }
                // THE CASE THE OLD PROBE COULD NOT SEE AT ALL. An entity whose DRAWN model
                // straddles a seam plane but which is absent from collidedEntities never enters
                // the loop above, so it produced no line of any kind — the loudest possible
                // failure was the quietest possible log. MEASURED 2026-08-21: on the 11 cut
                // frames of a slow crossing the MINECART (id=3, the entity that actually moves)
                // has ZERO lines anywhere, while its rider renders normally — so what is missing
                // from the destination image is the CART around the cow, which reads as the
                // cow's face being cut.
                java.util.List<Portal> unbooked = com.warwa.seamlessportals.passthrough
                    .SeamCrossingRule.mustBookCandidates(entity);
                for (Portal f : unbooked) {
                    frameProbe(entity, "PROJ-FACE-VERDICT face " + f.getId()
                        + " mainPassProjection=*** ABSENT(unbooked) ***"
                        + " modelStraddles=true entries=0");
                }
                if (com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                        .DISABLE_SEAM_RENDER_BOOKING) {
                    continue;   // diagnostic only; the supplement itself is switched off
                }
                for (Portal supplemental : com.warwa.seamlessportals.passthrough.SeamCrossingRule
                        .mustBook(entity)) {
                    if (!(supplemental instanceof Mirror)
                        && clientDim == supplemental.getDestDim()) {
                        frameProbe(entity, "PROJ render-booked face " + supplemental.getId()
                            + " (model straddles, physics has not booked)");
                        renderProjectedEntity(
                            entity, supplemental, dispatcher, cam, matrixStack, storage);
                    }
                }
            }

            // ★ CROSS-DIM REVERSE PROJECTION (cart round 1, re-scoped 2026-08-24 — the user's
            // four-photo report: "cart disappears and only slice in dest render"). The loop
            // above enumerates only the PLAYED level, but a just-flipped straddler lives in the
            // FAR client level — which is only enumerable during the portal pass, where the
            // clientDim==destDim gate correctly fails. Cross-dim, the two admission conditions
            // are never simultaneously satisfiable, so the emerging entity's back half — still
            // poking through the plane into this world — had no painter at all.
            //
            // MEASURED 2026-08-24 (post-carryover run): every straddle frame logs
            // "PROJ-FACE-VERDICT face 108 mainPassProjection=*** ABSENT(unbooked) ***
            // modelStraddles=true entries=0" — the visual carryover places the arrived visual
            // BEHIND the arrival face, exactly where the physics collision booking declines, so
            // the physics-booked loop (which painted this image pre-carryover, when the visual
            // spawned fully emerged) no longer reaches it. The carryover and this painter are
            // the two halves of one feature — a continuous crossing NEEDS a behind-plane
            // straddle window, and that window NEEDS a back-half painter — so they share the
            // ONE lever (-PdisableSeamVisualCarryover), not the refuted round-35 blanket
            // booking lever this block originally rode (default-OFF: the block was dead code,
            // round-7 log: zero firings).
            //
            // COST, bounded by construction: the round-35 supplement's expense (14,820
            // draws/tick) came from running straddle geometry on EVERY rendered entity against
            // every nearby portal. Here the crossing-set filter (anchored or portal-colliding —
            // one duck-field read each) runs FIRST, and only the 0-2 mid-crossing entities ever
            // reach the geometry. mustBookCandidates is the UNGATED predicate (mustBook returns
            // empty under the default booking lever); the admitted path's crossing-window gate,
            // aperture mask and inner clip all still apply, so the round-10 error direction
            // holds (an over-admitted projection clips to nothing). Same-dim behaviour is
            // untouched (far==played is skipped).
            // ★ ROUND 11 — THE IN-PASS ANCHOR BOOKING SUPPLEMENT (2026-08-24, the nether
            // viewer's late-BLUE report). The window's only projection-booking source is the
            // physics collidedEntities loop, and it drops the freshly-flipped entity while its
            // CARRIED visual's box is still short of the plane — the log shows ZERO face-54
            // in-pass lines of any kind during the behind-plane frames, so the window's
            // transitional image had no caller until emergence ("the dest part suddenly
            // disappears … then reappears [BLUE]"). Book far-level ANCHORED entities into the
            // pass whose dim their anchor's image targets — the in-pass twin of the main-pass
            // far-level block below; renderProjectedEntity's own in-pass gates still apply.
            if (qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()
                && !com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                    .DISABLE_SEAM_VISUAL_CARRYOVER) {
                for (net.minecraft.client.multiplayer.ClientLevel far
                        : qouteall.imm_ptl.core.ClientWorldLoader.getClientWorlds()) {
                    if (far == null || far == client.level) {
                        continue;
                    }
                    for (Entity farEntity : far.entitiesForRendering()) {
                        if (collidedEntities.containsKey(farEntity)) {
                            continue;   // the physics loop already books it
                        }
                        Portal anchor = com.warwa.seamlessportals.passthrough.SeamCrossingRule
                            .anchorOf(farEntity);
                        if (anchor != null && !(anchor instanceof Mirror)
                            && clientDim == anchor.getDestDim()) {
                            frameProbe(farEntity, "PROJ in-pass anchor-booked face "
                                + anchor.getId());
                            renderProjectedEntity(
                                farEntity, anchor, dispatcher, cam, matrixStack, storage);
                        }
                    }
                }
            }
            if (!qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()
                && !com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                    .DISABLE_SEAM_VISUAL_CARRYOVER) {
                for (net.minecraft.client.multiplayer.ClientLevel far
                        : qouteall.imm_ptl.core.ClientWorldLoader.getClientWorlds()) {
                    if (far == null || far == client.level) {
                        continue;
                    }
                    for (Entity farEntity : far.entitiesForRendering()) {
                        Portal anchor = com.warwa.seamlessportals.passthrough.SeamCrossingRule
                            .anchorOf(farEntity);
                        if (anchor == null
                            && ((IEEntity) farEntity).ip_getCollidingPortal() == null) {
                            continue;
                        }
                        // ★ TINT ROUND 2026-08-24 — THE WRONG-TWIN CLIP. The candidates
                        // predicate picks doors by front-side straddle, which post-flip selects
                        // the twin whose inner clip keeps the EMERGED side (log: "PROJ via face
                        // 82 clip=…/(0,0,1)" every straddle frame) — it re-paints the slice the
                        // window pass already draws and DELETES the back half it exists for
                        // (the user's tint run: dest slice untinted, NO orange until late). The
                        // door whose inner clip keeps the un-emerged side is the ARRIVAL FACE —
                        // the anchor set by the flip (pre-carryover proof: the physics-booked
                        // draw went via the anchor with clip (0,0,-1)). Union it in; the
                        // admitted gate (backPieceExists) retires it when the crossing ends.
                        java.util.List<Portal> bookFaces = new java.util.ArrayList<>(
                            com.warwa.seamlessportals.passthrough.SeamCrossingRule
                                .mustBookCandidates(farEntity));
                        if (anchor != null && !bookFaces.contains(anchor)) {
                            bookFaces.add(anchor);
                        }
                        for (Portal g : bookFaces) {
                            if (!(g instanceof Mirror) && clientDim == g.getDestDim()) {
                                frameProbe(farEntity, "PROJ cross-dim render-booked face "
                                    + g.getId() + (g == anchor ? " (ANCHOR back-half)" : "")
                                    + " (far-level straddler, back half into "
                                    + clientDim.identifier() + ")");
                                renderProjectedEntity(
                                    farEntity, g, dispatcher, cam, matrixStack, storage);
                            }
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
            // ★ ROUND 10 — THE IN-PASS ANCHORED-PHASE TWINS (2026-08-24, the nether-side
            // viewer's two reports). The main-pass layer already has both rules; the in-pass
            // layer needed the same pair:
            // (a) ANCHORED SUPPRESSES CANDIDATES: while a crossing is anchored, the carried
            //     visual straddles the OTHER pair's planes too, and that pair's door passes
            //     the geometric gates AND the round-29 side-agreement for the window it feeds
            //     — painting the crossing cart into the wrong segment's window ("looking from
            //     source b into dest a … a cart suddenly appears"). Only phase can veto it.
            // (b) THE ANCHOR BYPASSES THE CROSSING-WINDOW GATE below: the delta-(e) admission
            //     measures the far-side piece, but the anchor's displayed piece exists from
            //     the first flip frame — the gate delayed the window image and it popped in
            //     late and whole ("the dest part suddenly disappears … then fully reappears").
            if (com.warwa.seamlessportals.passthrough.SeamCartContinuity
                    .isSeamContinuous(collidingPortal)) {
                boolean anchoredHere = com.warwa.seamlessportals.passthrough.SeamCrossingRule
                    .isAnchoredAt(entity, collidingPortal);
                if (!anchoredHere
                    && com.warwa.seamlessportals.passthrough.SeamCrossingRule
                        .anchorOf(entity) != null) {
                    frameProbe(entity, "PROJ inpass-gated (non-anchor face while anchored,"
                        + " face " + collidingPortal.getId() + ")");
                    return;
                }
                if (!anchoredHere
                    && !com.warwa.seamlessportals.passthrough.SeamStraddleBracket
                        .backPieceExists(entity, collidingPortal)) {
                    frameProbe(entity, "PROJ inpass-gated (not crossing face "
                        + collidingPortal.getId() + ")");
                    return;
                }
            }
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

                    // ★ ROUND 29 — ENGINE §1.3 (b) SIDE AGREEMENT. The fix for the one artifact
                    // that survived the 2026-08-19 tint laps: the BLUE cross-twin bleed, both
                    // directions (away = 81% of the body, toward = a 7cm leading-edge slab —
                    // both derived, both matching the user's reports). A pass shows the half its
                    // ARMED clip keeps; this projection carries its OWN clip as the single
                    // hardware plane of its draw, REPLACING the pass's ambient plane rather than
                    // intersecting it. So when the kept normals oppose — identically so for a
                    // co-located twin, whose clip is the SAME plane with the OPPOSITE normal —
                    // it paints exactly the half-space the pass's ambient clip deletes for every
                    // other object in the frame. Measured lap 2: face41→pass40 9156 and
                    // face40→pass41 58 of 9278 in-pass seam projections were this pair; the two
                    // AGREEING pairs (28, 36) each equal the real body's CULL count in the same
                    // pass and are preserved untouched. Added CONJUNCT below, never a
                    // replacement — restrictive-only by construction.
                    Plane passKeptPlane = PortalRendering.getActiveClippingPlane();
                    boolean sideAgrees = com.warwa.seamlessportals.passthrough.SeamCrossingRule
                        .inPassProjectionSideAgrees(collidingPortal, innerClipping, passKeptPlane);
                    if (!sideAgrees) {
                        frameProbe(entity, "PROJ inpass-sidecull via face " + collidingPortal.getId()
                            + " in pass " + renderingPortal.getId()
                            + " n_img=" + innerClipping.normal()
                            + " n_pass=" + (passKeptPlane == null ? "null" : passKeptPlane.normal()));
                    }

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
                    if (sideAgrees && (renderingPortal == collidingPortal || !isHidden)) {
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
            // ★ ROUND 8 — THE ANCHOR BYPASSES THE CROSSING-WINDOW GATE (2026-08-24, the
            // three-photo gap: touch ✓ → ENTIRELY GONE → reappears properly clipped). The
            // delta-(e) admission (backPieceExists) measures the piece a STANDARD projection
            // displays — the far-side piece — but the ANCHOR's displayed piece is the KEPT-side
            // half, which exists from the first flip frame: the gate delayed the transitional
            // image until the visual's front crossed the plane, exactly the photographed gap
            // (reappearance coincides with straddle-start, "properly clipped"). The anchor's
            // own lifecycle (flip → ANCHOR-CLOSE) is its admission window; its clip confines
            // over-admission to the kept side, where the transitional cart belongs.
            if (!com.warwa.seamlessportals.passthrough.SeamCrossingRule
                    .isAnchoredAt(entity, collidingPortal)
                && !com.warwa.seamlessportals.passthrough.SeamCrossingRule
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
            // ★ ROUND 9 — THE ANCHOR BYPASSES THE APERTURE MASK (2026-08-24, the side-b gap's
            // true killer). The window route cannot cover side views STRUCTURALLY (a portal
            // pass's frustum is the pyramid through its quad — razor thin edge-on; measured:
            // "IS-VISIBLE ... pass=portal-2 EXCLUDED [FRUSTUM]" at the carried visual), so the
            // far-side viewer's transitional cart can only come from this main-pass anchor
            // image — and the mask's sight-line-through-quad test fails at exactly the glancing
            // angles that need it ("PROJ masked (not through aperture ...)" in the gap frames).
            // The mask's pasted-ghost family (video 2026-08-12) was NON-anchored counterparts;
            // the anchored image is phase-bound (flip → ANCHOR-CLOSE) and clipped keep-behind,
            // so from any camera it paints only the transitional cart on the seam's rail line.
            if (!com.warwa.seamlessportals.passthrough.SeamCrossingRule
                    .isAnchoredAt(entity, collidingPortal)
                && !isProjectionVisibleThroughAperture(entity, collidingPortal)) {
                frameProbe(entity, "PROJ masked (not through aperture of face "
                    + collidingPortal.getId() + ")");
                return;
            }
            // ★ XDIM GHOST (2026-08-24): the swept admission above over-admits by design ("an
            // over-admitted projection clips to nothing") — but for a CROSS-DIM main-pass
            // projection the anchor route's clip keeps the SOURCE side, so an over-admitted
            // approach image paints a fully visible duplicate ~2 blocks before the crossing
            // (the user's wrong-side bleed; live: "render-booked face 107" from z=-60.28
            // against a plane at -58.5, via the swept predicate and the looping cart's stale
            // anchor). Cross-dim, require the drawn model to straddle the plane RIGHT NOW —
            // one choke point covering both booking sources (physics entries and the far-level
            // supplement). Same-dim keeps the swept admission unchanged (its clips are proven
            // by the slice photos, and the round-35 muzzle-tail coverage depends on the sweep).
            if (com.warwa.seamlessportals.passthrough.SeamCartContinuity
                    .isSeamContinuous(collidingPortal)
                && collidingPortal.getDestDim() != entity.level().dimension()) {
                // ★ XDIM GHOST round 5 — CAMERA ∈ IMAGE-SIDE (2026-08-24, the tint verdict:
                // "orange... full cart on source side b when it should be in dest side b",
                // viewed from the far side of the plane). At a seam, everything beyond the
                // plane from the camera is the WINDOW's territory: the window pass paints that
                // region's world, and every through-window image already has an in-pass twin
                // painter. A MAIN-pass image on the far side of the plane paints OVER the
                // window content — the log's one orange painter in the bleed window was the
                // anchor back-half (face 72, keep z<7.5, image behind the plane) drawn while
                // the camera stood past the plane: a cart pasted into the S-B view. Rule: a
                // main-pass seam projection draws ONLY when the camera is on the image's own
                // (kept) side of the plane — anchor and candidates alike. Same-side viewers
                // keep the back-half continuity and the arrival tip; far-side viewers get
                // window content exclusively from the window pass. (This replaces the earlier
                // non-anchor camera-side gate, whose sign was wrong for the arriving-tip
                // case.) The vanilla-portal aperture-mask allowance (through-window
                // counterparts) stays for non-seam faces.
                Plane seamImageClip = collidingPortal.getInnerClipping();
                if (seamImageClip != null) {
                    Vec3 camPos = client.gameRenderer.mainCamera().position();
                    boolean cameraOnImageSide = camPos.subtract(seamImageClip.pos())
                        .dot(seamImageClip.normal()) > 0;
                    // ★ ROUND 6 — ANCHOR EXEMPTION (2026-08-24, the side-b vanish): with the
                    // clip frame fixed (CLIPNUM verdict) the anchor's transitional image is
                    // provably confined to its kept (departure-image) side, so from a far-side
                    // camera it paints only over the window region — the cart visibly sliding
                    // through the seam, which is precisely the continuity the far-side viewer
                    // lost ("cart instantly disappears when it touches the seam"). The
                    // original far-side bleed this rule killed was the SAME image UNCLIPPED
                    // (the broken plane pairing); with the clip cutting, the error direction
                    // is round-10-safe again. Non-anchor faces keep the rule — their far-side
                    // images are the ghost family.
                    if (!cameraOnImageSide
                        && !com.warwa.seamlessportals.passthrough.SeamCrossingRule
                            .isAnchoredAt(entity, collidingPortal)) {
                        frameProbe(entity, "PROJ xdim-gated (camera beyond seam plane;"
                            + " image side is window territory, face "
                            + collidingPortal.getId() + ")");
                        return;
                    }
                }
                if (!com.warwa.seamlessportals.passthrough.SeamStraddleBracket
                        .straddlesForDrawInstant(entity, collidingPortal)) {
                    frameProbe(entity, "PROJ xdim-gated (instant model not straddling face "
                        + collidingPortal.getId() + ")");
                    return;
                }
                // ★ XDIM GHOST, the PHASE × SIDE rule (2026-08-24 round 2 — the instant gate
                // alone left a ~1.2-block ghost: the envelope's contractual slack still
                // straddles early, and geometry CANNOT distinguish the pre-flip approach from
                // the post-flip carried-back visual — same shape, opposite legitimacy). A
                // projection displays the face's BACK (-n) piece; painting it while the whole
                // body sits on that side duplicates the un-crossed cart into the viewer's own
                // world (live: "PROJ via face 75 clip=…/(0,0,-1) main-pass" at z=-59.72,
                // plane -58.5 — the wrong-side ghost riding the near rail). Phase carries the
                // exception: the flip anchors the arrival face, and ANCHOR-CLOSE releases it at
                // full emergence, so anchor ⟺ crossing-in-progress — the anchor face's
                // back-piece image IS the legitimate not-yet-caught-up half. Non-anchor faces
                // may only display an EMERGED piece: the entity's center must be on the +n
                // side. Same-dim is out of scope (dest==source dim above).
                if (!com.warwa.seamlessportals.passthrough.SeamCrossingRule
                        .isAnchoredAt(entity, collidingPortal)) {
                    // ★ ROUND 7 — ANCHORED SUPPRESSES CANDIDATES (2026-08-24, the side-b photo:
                    // "part of the cart in dest side b leaks inside source side b"). While a
                    // crossing is anchored, the carried visual OCCUPIES the other pair's source
                    // segment moving exactly like that pair's crosser — every geometric test
                    // (side, straddle, motion, camera-side) legitimately passes for the
                    // wrong-pair door, and its image paints the emerged half onto the viewer's
                    // native far-side land. Only PHASE can tell the pairs apart (the walk-sever
                    // theorem, painter edition): an entity with a LIVE anchor projects through
                    // its anchor alone; candidates exist for the anchorless gaps.
                    if (com.warwa.seamlessportals.passthrough.SeamCrossingRule
                            .anchorOf(entity) != null) {
                        frameProbe(entity, "PROJ xdim-gated (non-anchor face while anchored,"
                            + " face " + collidingPortal.getId() + ")");
                        return;
                    }
                    double side = entity.position()
                        .subtract(collidingPortal.getOriginPos())
                        .dot(collidingPortal.getNormal());
                    if (side <= 0) {
                        frameProbe(entity, "PROJ xdim-gated (non-anchor keep-near ghost, face "
                            + collidingPortal.getId() + " side=" + String.format("%.2f", side)
                            + ")");
                        return;
                    }
                    // ★ XDIM GHOST round 3 — FOUR-DOOR CROSSTALK, render edition (2026-08-24,
                    // "crossing seam on other side and bleeding to my side"). The seam cell
                    // carries TWO door pairs sharing one affine; the loop crosses it twice per
                    // lap. The moment a crossing cart's center passes the plane, the OTHER
                    // pair's door flips to side>0 and its instant straddle holds — every
                    // geometric gate passes — and its image paints the cart's back onto the
                    // OBSERVER's own segment, where this crossing owes no pixels (the exact
                    // analogue of the redstone crosstalk: per-path independence, and geometry
                    // alone cannot pick the path — RS ledger's walk-sever theorem). The
                    // viewer's-side image is exclusively the ANCHOR's job (the true crossing's
                    // arrival face, phase-bound by flip→ANCHOR-CLOSE): a non-anchor face whose
                    // kept image side CONTAINS THE CAMERA would paint into the viewer's own
                    // region — never legitimate. The beyond-side tip painter (camera outside
                    // its kept side) stays candidate-bookable, and the rule is symmetric for
                    // an observer on either side of the seam.
                    // ★ XDIM GHOST round 4 — THE MOTION×KEPT-SIDE RULE (2026-08-24). A
                    // non-anchor projection paints the region the entity is moving INTO — its
                    // image-space velocity must point into the clip's kept half-space.
                    // Right-pair entry doors pass by construction; wrong-pair doors fail; the
                    // ANCHOR (which paints the region the cart came FROM) bypasses on phase
                    // above; a parked straddler (velocity ~0) keeps the old behavior. (Round
                    // 5 removed the non-anchor camera-side gate that used to sit here — its
                    // sign was wrong for the arriving-tip case; the camera ∈ image-side rule
                    // above subsumes the case it was written for.)
                    Plane nonAnchorClip = collidingPortal.getInnerClipping();
                    if (nonAnchorClip != null) {
                        Vec3 imageVel = collidingPortal.transformLocalVec(
                            McHelper.getWorldVelocity(entity));
                        if (imageVel.dot(nonAnchorClip.normal()) < -0.001) {
                            frameProbe(entity, "PROJ xdim-gated (non-anchor image-motion out of"
                                + " kept side, face " + collidingPortal.getId() + ")");
                            return;
                        }
                    }
                }
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

            // ★ ROUND 32 — THE LOCALITY GATE'S MODEL MARGIN. This test asks whether the entity's
            // transformed BOX still reaches the side of the window the pass shows; but the seam
            // clip cuts the drawn MODEL, which reaches further. A cow's muzzle extends 0.9375
            // blocks from its position against a 0.45 box half, so between ~0.5 and ~0.94 blocks
            // past the plane the model still pokes back through the window while this gate has
            // already culled its projection — and the main body's outer clip stops at the plane,
            // so nothing paints that sliver.
            //
            // MEASURED (tint lap 2026-08-20, and the reason this gate was found at all): the
            // in-pass projection drew only within 0.75 blocks of the plane and was dead beyond,
            // while "PROJ skipped (outside rendering portal N)" took over at exactly that
            // distance — 0.50-0.75 flipped from 124 drawn/30 culled to 18 drawn/164 culled. The
            // user's tint photos show the BLUE (in-pass projection) fading progressively while
            // the RED (main body) stays clipped at the plane: "cuts off from dest and grows from
            // seam".
            //
            // Widening is safe in the way the round-10 argument is safe: an over-admitted
            // projection is clipped to nothing by its own inner plane, so the error direction is
            // "draw a piece that paints no pixels", never "cull a piece that should paint".
            // Scoped to seam faces so non-seam IP behaviour stays byte-identical.
            AABB localityBox = transformedBoundingBox;
            if (com.warwa.seamlessportals.passthrough.SeamCartContinuity
                    .isSeamContinuous(transformingPortal)) {
                double m = com.warwa.seamlessportals.passthrough.SeamStraddleBracket.modelMargin();
                if (m > 0.0) {
                    localityBox = transformedBoundingBox.inflate(m);
                }
            }
            boolean intersects = PortalManipulation.isOtherSideBoxInside(localityBox, renderingPortal);

            if (!intersects) {
                frameProbe(entity, "PROJ skipped (outside rendering portal "
                    + renderingPortal.getId() + ")");
                return;
            }
        }

        if (!PortalRendering.isRendering()) {
            // ★ ROUND 36 v3: reaching HERE only means the function got this far — there are
            // early returns below and the submit is ~90 lines further on. The per-face verdict
            // is fed from the PROJ-SUBMIT marker AFTER the submit returns, never from here.
            mainPassProjectionDrawCount++;
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
            // ★ ROUND 31 (D2) — THE PROJECTION CAMERA DISTANCE, the vanishing-shadow fix.
            // extractEntity stamps distanceToCameraSq from EntityRenderDispatcher.distanceToSqr,
            // which measures the entity's REAL position — but this projection is DRAWN at
            // newEntityInstantPos. On a far seam those differ by ~691 blocks, so the stamped value
            // is ~4.8e5 against vanilla's shadow gate of 256 (EntityRenderer.extractShadow:
            // pow = (1 - distSq/256) * strength, and the piece loop only runs while pow > 0).
            // The projection therefore arrives with an EMPTY shadowPieces list and
            // EntityRenderDispatcher skips submitShadow outright — which is why the user sees the
            // ENTIRE shadow vanish at the seam rather than half of it clipped. Override the
            // measurement to the DRAWN position for exactly this extraction.
            // ★ PASSTHROUGH EXTRAS scope: both extraction overrides below are part of the
            // extras feature set — with the master config OFF they stay inert, so non-seam IP
            // portal projections render byte-identically to the pre-extras engine.
            boolean passthroughExtrasOn = qouteall.imm_ptl.core.platform_specific.IPConfig
                .getConfig().passthroughExtras;
            if (passthroughExtrasOn
                && !com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                    .DISABLE_SEAM_PROJECTION_CAMERA_DISTANCE) {
                projectionCameraDistanceSqOverride =
                    newEntityInstantPos.distanceToSqr(cameraPos);
            }
            // ★ PROJECTION LIGHT (2026-08-24, the user's darkness report): extraction samples
            // lightCoords at the ENTITY's position in the ENTITY's level
            // (EntityRenderer.getPackedLightCoords → entity.level().getBrightness), but this
            // projection is DRAWN in the destination level at the transformed position — and the
            // main pass interprets those coords through the DESTINATION dimension's lightmap.
            // Cross-dim that mismatch is glaring: a nether-sampled (sky=0) coord under the
            // overworld night lightmap renders near-black — the user's "source side of the cart
            // becomes much darker" the moment the flip moves the entity into the nether. IP 1.19
            // carries this latently (its projections sample source-side light too; its crossing
            // window is a tick, ours is the whole straddle). Override the sample to the image
            // position in the projection's target level, scoped to exactly this extraction —
            // same bracket pattern as the shadow-distance override above.
            if (passthroughExtrasOn
                && !com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                    .DISABLE_SEAM_PROJECTION_LIGHT) {
                projectionLightWorld = newWorld;
                projectionLightPos = transformingPortal.transformPoint(
                    entity.getLightProbePosition(RenderStates.getPartialTick()));
            }
            EntityRenderState projectionState;
            try {
                projectionState = dispatcher.extractEntity(entity, RenderStates.getPartialTick());
            }
            finally {
                projectionCameraDistanceSqOverride = -1.0;
                projectionLightWorld = null;
            }
            int touched = PerEntityClipBracket.submitProjectedEntityClipped(
                dispatcher, projectionState, cam, newCameraPos, matrixStack, storage,
                innerClipPlane, false, entity.getId(), entity.getType().toString()
            );
            // ★ ROUND 36 v3 — ASSERT THE OUTCOME, NOT THE REQUEST. v2's marker sat at the
            // "PROJ via face" probe ~90 lines ABOVE this call, so "DREW" only ever meant
            // "reached the probe" — the fourth time this session that a request was measured
            // and reported as an outcome. The marker now sits AFTER the submit returns, and
            // carries what the submit actually produced: the number of SubmitNodeCollections
            // the entity's geometry landed in. touched==0 means nothing was submitted at all
            // and no phase can ever carry the ORANGE snapshot to the GPU — which is exactly
            // the RenderDoc signature (0 orange uniform uploads on a cut frame, 4 on a good one).
            if (!PortalRendering.isRendering()) {
                mainPassProjectionFaces.add(transformingPortal.getId());
                probeSubmitsThisFrame++;
                frameProbe(entity, "PROJ-SUBMIT face " + transformingPortal.getId()
                    + " touchedCollections=" + touched
                    + (touched == 0 ? "  *** NOTHING SUBMITTED ***" : ""));
            }
        }
        finally {
            // Belt-and-braces: the overrides must never outlive this extraction, or an unrelated
            // entity's distance/light would be answered with a stale value.
            projectionCameraDistanceSqOverride = -1.0;
            projectionLightWorld = null;
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
                    // ★ ROUND 39 REVERTED 2026-08-22, at the user's direction. Round 39 ran this
                    // verdict against EVERY straddled face instead of ip_getCollidingPortal()'s
                    // entry-0 lookup. Two reasons it does not ride along:
                    //
                    // 1. ITS PREMISE WAS REFUTED BY THE USER'S OWN PHOTO. It was built on "the cow
                    //    paints GREEN at the cut, so a spurious vanilla in-pass body is drawing".
                    //    The photo then showed GREEN covering the whole window — grass, sky and
                    //    clouds included — because the ambient arm tints everything the pass draws.
                    //    A cow at the destination SHOULD be green; there was no spurious draw.
                    //
                    // 2. IT IS A CANDIDATE CAUSE OF THE WRONG-SIDE BLEED IT COINCIDED WITH. The
                    //    anyKeep fold returned true if ANY straddled face voted KEEP, where before
                    //    only entry 0 voted — so the real body could paint in passes that
                    //    previously culled it, which is a wrong-side bleed by construction.
                    //
                    // The underlying observation stands and is worth revisiting deliberately:
                    // entry-0 addressing IS arbitrary on a bi-faced cluster, and the same shape
                    // already produced a silent wrong verdict in the round-36 probe. But it must be
                    // re-derived against a measurement that cannot be masked, not against a tint
                    // lap — round 41 proved the tint hides this artifact rather than attributing it.
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
