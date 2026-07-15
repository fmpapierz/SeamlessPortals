package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

// S11-B (Slice A) port disposition: PORT-FORWARD / RECONCILE (current-mod-render §1.6; S11-A carriage
// map §3 rows FrontClipping + flags B1/B2). This is the qouteall-path FrontClipping the held entity-era
// callers pin: ClientTeleportationManager.java:462 reads the public static boolean isClippingEnabled,
// and U10/S12 RendererUsingStencil / MixinLevelRenderer reach setupInnerClipping / setupOuterClipping /
// updateInnerClipping. Held/inert until S13.
//
// RECONCILE DECISION — BRIDGE (zero live edits). IP's 1.21.3 FrontClipping drives the fixed-function
// GL_CLIP_PLANE0 (GL11.glEnable/glDisable) + a per-"current shader" GL20.glUniform4f upload
// (updateClippingEquationUniformForCurrentShader via IEShader). BOTH are GONE on 26.2 core-profile
// (render-core G5/G9: no RenderSystem.getShader, GL_CLIP_PLANE0 is not a core-profile capability). The
// mod's PROVEN 26.2 clip mechanism is gl_ClipDistance[0] fed from a single VIEW-SPACE plane store
// (com.warwa.seamlessportals.render.FrontClipping.getPlaneX/Y/Z/W), injected into every shader by
// ShaderCodeTransformation and uploaded per-draw by the always-on KEEP mixin GlCommandEncoderClipMixin.
// Those two mixins are "always-on substrate KEEP" — they apply in BOTH flag states and bind to the
// com.warwa plane store. Carriage flag B1 requires ONE plane store feeding the single GL uniform (no
// two divergent stores). So this qouteall port does NOT open a second store: it computes IP's plane
// (IP API + IP plane SOURCE, IP's kept-half-space semantics) in the mod's view-space representation and
// FEEDS the single com.warwa store via that class's public restore(Snapshot)/disable() — the exact
// store GlCommandEncoderClipMixin already reads. The two KEEP mixins stay UNCHANGED (B1 satisfied), the
// live com.warwa copy is not edited (bridge is additive-call-only), and the exclusivity ledger
// guarantees only one renderer drives per session, so the two FrontClippings never feed the uniform at
// once. isClippingEnabled (B2, public static boolean — IP's field; the mod's glClipEnabled is private)
// is mirrored locally in lockstep with every bridge enable/disable.
//
// SIGN NOTE (D4.4, the deliverable of the sign-sensitive carriage): the view-space feed is IP-faithful.
// IP's kept half-space is n·p_rel + c > 0 (p_rel = world pos - camera pos; normal points to the KEPT
// side; c = -n·(clipPoint + n*correction - camera), getClipEquationInner). The mod's store evaluates
// gl_ClipDistance[0] = dot(viewPos, planeXYZ) + planeW with viewPos = R·p_rel (R = view rotation). Since
// rotation preserves the dot product, dot(R·p_rel, R·n) + c = n·p_rel + c — IDENTICAL kept half-space.
// Hence planeXYZ = R·n (the normal rotated into view space) and planeW = c. The rotation is the
// COLUMN-FORM idiom `new Vector4f(nx,ny,nz,0).mul(modelView)` = M·v (S11-A §2 anti-fix guard: this is
// Matrix4f.transform semantics; do NOT "fix" to mulTranspose — that yields the inverse rotation and a
// sign-flipped clip plane; the IP_DEVIATIONS_ANALYSIS "row-vector" note is WRONG). The w=0 normal makes
// the modelView translation column drop out, so passing the full model-view (as IP does) rotates the
// normal correctly; the scaling-portal edge (modelView carries scale → nView non-unit) is a driver-core
// refinement the mod handles with a rotation-only viewRotation and is flagged for S13, not "fixed" here.
// The IP double[] before/after-modelView equations are still computed for the held IP-contract getters
// (getActiveClipPlaneEquation{Before,After}ModelView) but they are VESTIGIAL on 26.2 — the live GL feed
// is the view-space store, not these equations (they backed the dead GL_CLIP_PLANE0 path).
//
// Forward-refs (documented debt): none new — Portal / Plane / PortalRendering / CHelper / IPCGlobal /
// IPGlobal are all landed; com.warwa FrontClipping is the live block-era substrate (compiles in :common).
public class FrontClipping {
    private static final Minecraft client = Minecraft.getInstance();

    /** IP before-model-view clip equation {nx,ny,nz,c} (camera-relative world space). Vestigial on
     *  26.2 (the live feed is the view-space store); retained for the held IP-contract getter. */
    private static double[] activeClipPlaneEquationBeforeModelView;

    /** IP after-model-view clip equation (inverse-transpose of the above). Vestigial on 26.2. */
    private static double[] activeClipPlaneAfterModelView;

    // IP field, pinned public static boolean by ClientTeleportationManager.java:462 (B2). Mirrors the
    // single com.warwa gl_ClipDistance enable state; maintained in lockstep by the bridge below.
    public static boolean isClippingEnabled = false;

    public static final double ADJUSTMENT = 0.01;

    public static void disableClipping() {
        if (IPGlobal.enableClippingMechanism) {
            if (isClippingEnabled) {
                // BRIDGE: disable the single com.warwa gl_ClipDistance store (26.2 replacement for
                // GL11.glDisable(GL_CLIP_PLANE0)). Resets the plane to the no-op default (0,0,0,1).
                com.warwa.seamlessportals.render.FrontClipping.disable();
                isClippingEnabled = false;
            }
        }
    }

    public static void updateInnerClipping(PoseStack matrixStack) {
        Matrix4f modelView = matrixStack.last().pose();
        updateInnerClipping(modelView);
    }

    public static void updateInnerClipping(Matrix4f modelView) {
        if (PortalRendering.isRendering()) {
            setupInnerClipping(
                PortalRendering.getActiveClippingPlane(),
                modelView, 0
            );
        }
        else {
            disableClipping();
        }
    }

    // NOTE the actual clipping plane is related to the current model view matrix
    public static void setupInnerClipping(
        Plane clipping, Matrix4f modelView, double adjustment
    ) {
        if (!IPCGlobal.useFrontClipping) {
            return;
        }

        // Note: the normal of the plane points to the non-clipped (KEPT) side.

        if (clipping != null) {
            activeClipPlaneEquationBeforeModelView =
                getClipEquationInner(clipping.pos(), clipping.normal(), adjustment);
            activeClipPlaneAfterModelView =
                transformClipEquation(activeClipPlaneEquationBeforeModelView, modelView);

            feedViewSpacePlane(activeClipPlaneEquationBeforeModelView, modelView);
        }
        else {
            activeClipPlaneEquationBeforeModelView = null;
            disableClipping();
        }
    }

    /**
     * BRIDGE feed + enable (26.2 replacement for IP's {@code enableClipping()}). Takes IP's
     * before-model-view equation {nx,ny,nz,c} (camera-relative world space, kept half-space
     * n·p_rel + c > 0) and writes it into the single com.warwa view-space plane store that
     * GlCommandEncoderClipMixin uploads to gl_ClipDistance[0]: planeXYZ = R·n (column-form rotate the
     * world-space normal into view space; the w=0 makes translation drop out), planeW = c. Kept
     * half-space is preserved exactly (see class SIGN NOTE). Gated by
     * {@code IPGlobal.enableClippingMechanism}, mirroring IP's enableClipping() guard; isClippingEnabled
     * is set in lockstep with the com.warwa gl_ClipDistance enable that restore(...,true) performs.
     */
    private static void feedViewSpacePlane(double[] beforeModelView, Matrix4f modelView) {
        if (!IPGlobal.enableClippingMechanism) {
            return;
        }
        Vector4f nView = new Vector4f(
            (float) beforeModelView[0], (float) beforeModelView[1], (float) beforeModelView[2], 0f
        );
        nView.mul(modelView); // COLUMN FORM M·v (anti-fix guard: never mulTranspose)
        com.warwa.seamlessportals.render.FrontClipping.restore(
            new com.warwa.seamlessportals.render.FrontClipping.Snapshot(
                nView.x, nView.y, nView.z, (float) beforeModelView[3], true
            )
        );
        isClippingEnabled = true;
    }

    private static double[] transformClipEquation(
        double[] equation, Matrix4f modelView
    ) {
        Vector4f eq =
            new Vector4f((float) equation[0], (float) equation[1], (float) equation[2], (float) equation[3]);
        Matrix4f m = new Matrix4f(modelView);
        m.invert();
        m.transpose();
        m.transform(eq);
        return new double[]{eq.x(), eq.y(), eq.z(), eq.w()};
    }

    private static double[] getClipEquationInner(
        Vec3 clippingPoint, Vec3 clippingDirection, double correction
    ) {
        Vec3 cameraPos = CHelper.getCurrentCameraPos();

        Vec3 planeNormal = clippingDirection;

        Vec3 portalPos = clippingPoint
            .add(planeNormal.scale(correction))
            .subtract(cameraPos);

        //equation: planeNormal * p + c > 0
        //-planeNormal * portalCenter = c
        double c = planeNormal.scale(-1).dot(portalPos);

        return new double[]{
            planeNormal.x, planeNormal.y, planeNormal.z, c
        };
    }

    public static void setupOuterClipping(PoseStack matrixStack, Portal portal) {
        if (!IPCGlobal.useFrontClipping) {
            return;
        }

        double[] clipEquationOuter = getClipEquationOuter(portal);

        if (clipEquationOuter != null) {
            activeClipPlaneEquationBeforeModelView = clipEquationOuter;
            activeClipPlaneAfterModelView = transformClipEquation(
                activeClipPlaneEquationBeforeModelView, matrixStack.last().pose()
            );
            feedViewSpacePlane(activeClipPlaneEquationBeforeModelView, matrixStack.last().pose());
        }
        else {
            activeClipPlaneEquationBeforeModelView = null;
            disableClipping();
        }
    }

    // "double @Nullable []" is weird...
    private static double @Nullable [] getClipEquationOuter(Portal portal) {
        @Nullable Plane outerClipping = portal.getPortalShape()
            .getOuterClipping(portal.getThisSideState());

        if (outerClipping == null) {
            return null;
        }

        Vec3 planeNormal = outerClipping.normal();

        // 26.2: Camera.getPosition() -> position(); GameRenderer.getMainCamera() -> mainCamera().
        Vec3 cameraPos = client.gameRenderer.mainCamera().position();

        Vec3 portalPos = outerClipping.pos()
            .subtract(cameraPos);

        //equation: planeNormal * p + c > 0
        //-planeNormal * portalCenter = c
        double c = planeNormal.scale(-1).dot(portalPos);

        return new double[]{
            planeNormal.x, planeNormal.y, planeNormal.z, c
        };
    }

    // ------------------------------------------------------------------------------------------------
    // R3 (S11-C Slice A) — NON-MUTATING view-space plane capture for PerEntityClipBracket.
    //
    // IP forced its per-entity clip out by mutating the live store mid-batch (setupOuterClipping /
    // setupInnerClipping between endBatch() flushes). On the 26.2 submit model there is no mid-batch
    // flush; the per-entity plane must be captured as a Snapshot and pushed by the seam AROUND the
    // entity's own submit-order draw (Mechanism A executePhase bracket) or its isolated-storage draw
    // (Mechanism B), NOT written into the ambient store at submit time. These capture-only variants
    // reuse the EXACT IP plane SOURCE + kept-half-space math of setupOuterClipping/setupInnerClipping
    // and the EXACT column-form view-space rotation of feedViewSpacePlane (see the class SIGN NOTE) —
    // they just return the Snapshot instead of feeding the single com.warwa store. Zero live edits.
    // ------------------------------------------------------------------------------------------------

    /**
     * OUTER-clip counterpart of {@link #setupOuterClipping} that RETURNS the view-space plane instead of
     * feeding the live store. Returns {@code null} when the portal shape has no outer clipping (matching
     * IP's {@code setupOuterClipping(null)} → {@code disableClipping()} — an unclipped draw).
     */
    public static com.warwa.seamlessportals.render.FrontClipping.Snapshot captureOuterClipping(
        Portal portal, Matrix4f viewRotation
    ) {
        if (!IPCGlobal.useFrontClipping) {
            return null;
        }
        double[] clipEquationOuter = getClipEquationOuter(portal);
        if (clipEquationOuter == null) {
            return null;
        }
        return toViewSpaceSnapshot(clipEquationOuter, viewRotation);
    }

    /**
     * INNER-clip counterpart of {@link #setupInnerClipping} that RETURNS the view-space plane instead of
     * feeding the live store (correction 0, matching IP's renderProjectedEntity
     * {@code setupInnerClipping(collidingPortal.getInnerClipping(), …, 0)}). Returns {@code null} for a
     * null plane; the caller ({@code PerEntityClipBracket.submitProjectedEntityClipped}) then registers an
     * EXPLICITLY DISABLED snapshot so the projection draws UNCLIPPED (IP's isRendering branch inherits the
     * {@code :101} disableClipping and the else branch's {@code setupInnerClipping(null)} collapses to
     * disableClipping — NOT the ambient dest inner clip; Verifier-1 P1).
     */
    public static com.warwa.seamlessportals.render.FrontClipping.Snapshot captureInnerClipping(
        @Nullable Plane clipping, Matrix4f viewRotation
    ) {
        if (!IPCGlobal.useFrontClipping) {
            return null;
        }
        if (clipping == null) {
            return null;
        }
        double[] clipEquationInner = getClipEquationInner(clipping.pos(), clipping.normal(), 0);
        return toViewSpaceSnapshot(clipEquationInner, viewRotation);
    }

    /**
     * The shared IP-before-model-view {@code {nx,ny,nz,c}} → com.warwa view-space Snapshot conversion —
     * the EXACT math of {@link #feedViewSpacePlane} but returning the Snapshot instead of writing the live
     * store. {@code planeXYZ = R·n} (column-form {@code Vector4f(n,0).mul(viewRotation)} = M·v; do NOT
     * "fix" to {@code mulTranspose} — S11-A anti-fix guard), {@code planeW = c}, {@code enabled = true}.
     * {@code viewRotation} is the world→view rotation (the model-view the 26.2 draw applies to the
     * camera-relative submit poses); the {@code w=0} normal makes any translation column drop out.
     */
    private static com.warwa.seamlessportals.render.FrontClipping.Snapshot toViewSpaceSnapshot(
        double[] beforeModelView, Matrix4f viewRotation
    ) {
        Vector4f nView = new Vector4f(
            (float) beforeModelView[0], (float) beforeModelView[1], (float) beforeModelView[2], 0f
        );
        nView.mul(viewRotation); // COLUMN FORM M·v (anti-fix guard: never mulTranspose)
        return new com.warwa.seamlessportals.render.FrontClipping.Snapshot(
            nView.x, nView.y, nView.z, (float) beforeModelView[3], true
        );
    }

    public static double[] getActiveClipPlaneEquationBeforeModelView() {
        return activeClipPlaneEquationBeforeModelView;
    }

    public static double[] getActiveClipPlaneEquationAfterModelView() {
        return activeClipPlaneAfterModelView;
    }

    /**
     * IP 1.21.3 uploaded the clip equation to the "current shader" via GL20.glUniform4f keyed off
     * IEShader.ip_getClippingEquationUniformLocation() — the whole RenderSystem.getShader() /
     * CompiledShaderProgram stack is GONE on 26.2 (render-core G5/G9). On 26.2 the per-draw upload is
     * done by the always-on KEEP mixin GlCommandEncoderClipMixin, which reads the single com.warwa
     * view-space store this class already feeds. So this method is 26.2-SUPERSEDED: the upload happens
     * at the backend draw path, not from a static "current shader" call. Retained as an inert IP-
     * contract no-op (held callers U10/S12 MixinRenderSystem_Clipping expect the symbol).
     */
    public static void updateClippingEquationUniformForCurrentShader(
        boolean isRenderingEntities
    ) {
        // 26.2: per-draw gl_ClipDistance upload is GlCommandEncoderClipMixin (B1); no-op here.
    }

    /**
     * Companion to the above — IP's "reset the current shader's clip uniform to (0,0,0,1)". Superseded
     * on 26.2 by disableClipping() feeding the com.warwa store's no-op plane, which the KEEP mixin then
     * uploads. Inert IP-contract no-op.
     */
    public static void unsetClippingUniform() {
        // 26.2: superseded by the disable() no-op plane + GlCommandEncoderClipMixin upload; no-op here.
    }
}
