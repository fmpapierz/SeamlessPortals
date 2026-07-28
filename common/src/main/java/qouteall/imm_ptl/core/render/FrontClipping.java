package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
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
// normal correctly for the UNSCALED common case (R orthonormal ⇒ R = R⁻ᵀ ⇒ the forward rotate M·n IS
// the covector transform — bit-identical to the proven first-light render).
//
// S13-L SCALED-PORTAL REFINEMENT (the deferred edge, now DUE with a live conviction). The scaling-portal
// edge flagged above is now implemented. A fuse-view scaling portal installs a UNIFORM scale k=1/s on the
// camera model-view (PortalRenderer.getPortalScaleMatrix = scale(1/s); shouldApplyScaleToModelView =
// hasScaling && isFuseView), so the SAME destViewMatrix that (a) transforms the dest TERRAIN vertices
// (prepareChunkRenders) and (b) feeds this clip bridge carries scale: M = R·kI. The mod's 26.2 clip
// shader (com.warwa ShaderCodeTransformation) evaluates in EYE space —
// gl_ClipDistance[0] = dot(ModelViewMat·pos, planeXYZ) + planeW — so with the forward rotate
// planeXYZ = M·n = k·R·n the shader computes dot(k·R·p_rel, k·R·n) + c = k²(n·p_rel) + c: the kept
// half-space plane is DISPLACED by k² (S13-L "white bar": scaled-dest terrain clipped where it must not
// be; the user's enableClippingMechanism=false A/B convicted exactly this). IP is scale-INVARIANT here
// because its vanilla terrain shader evaluates the clip in WORLD space (Position.xyz + ChunkOffset dotted
// with the raw {n,c}, shader_transformation.yaml:17) — the model-view scale never touches the clip. To
// reproduce IP's true-world-plane half-space from our EYE-space shader EXACTLY, the clip NORMAL (a
// COVECTOR) must transform by the INVERSE-TRANSPOSE of the model-view's linear block, planeXYZ = M⁻ᵀ·n
// = (1/k)·R·n — precisely IP's own transformClipEquation (the after-model-view equation) restricted to
// the translation-free part, so planeW = c is unchanged. Then dot(k·R·p_rel, (1/k)R·n) + c = n·p_rel + c,
// EXACT for k>1 and k<1 alike. This lives in rotateClipNormalToViewSpace below, behind a det≈1 fast path
// that keeps the unscaled/non-fuse path BIT-IDENTICAL. The nested-matrix install (S13-I) that now honors
// the passed matrices around drawMesh keeps the shader's ModelViewMat == the fed destViewMatrix, so the
// covector transform inverts exactly what the shader applies.
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

    // ===== IS5-SEAM CROSSING-WINDOW RELAX (2026-07-27; DEFAULT-ON fix, A/B OFF via
    // -Dseamlessportals.disableSeamClipRelax) ==========================================
    //
    // THE MEASURED MECHANISM (IS5-SEAM-ARM census; feed proven coherent, maxAbsFeedErr=0.0000 on
    // 95/95 rows): the black band at the portal seam is the inner clip WORKING AS DESIGNED on
    // crossing frames. Two measured frame classes:
    //   - fullyVoid (planeW >= 0, 6 frames across three crossings): the RENDER camera
    //     (partialTick + view bob) sits ON/past the armed plane before the tick-keyed crossing
    //     fires — every aperture ray starts kept-side and points away: whole-aperture void (the
    //     fast-crossing FLASH).
    //   - nearStraddle (planeW in (-0.1, 0), 38 frames): the eye is 1-11 cm short of the plane;
    //     the aperture mesh rasterizes the sliver past the seam line whose rays GRAZE the plane —
    //     t_cross = eyeDist/sin(theta) is meters long, so their near-side dest content is culled
    //     (the slow-crossing SUSTAINED band).
    // IP arms the IDENTICAL plane (ip-source RectangularPortalShape.getInnerClipping +
    // MixinLevelRenderer -ADJUSTMENT, verified) — IP fills those pixels with its UNCLIPPED sky;
    // a deferred shaderpack has no such filler, hence pure black, shaders-ON only.
    //
    // THE FIX: ramp the inner clip camera-side as the eye enters the crossing window. The
    // LOAD-BEARING INVARIANT (panel-corrected — this is weaker than "clearance exceeds the
    // near-plane corner reach", and it is what actually prevents the void): a rasterized
    // aperture ray can only void when the RENDER EYE is on the CLIPPED side of the armed plane
    // (eye kept-side => every aperture ray traverses kept space first and its near dest content
    // draws). So the fix holds the eye KEPT-side of the armed plane, with margin, throughout the
    // whole zone where the seam sliver can rasterize. The "extra" content this keeps is the
    // dest-side doorway interior the eye is physically inside mid-crossing — the correct
    // seamless view. Far from the portal the correction is bit-identical to IP's -ADJUSTMENT.
    //
    // Geometry (all along the KEPT normal; camToPlane = n.(cam - pos), NEGATIVE pre-crossing):
    //   eyeDist(corr) = camToPlane - corr        (the armed plane's clip distance at the eye)
    //   hold eyeDist == CLEARANCE while d = -camToPlane <= SLIVER_ZONE, ramp to the IP constant
    //   by RAMP_END.
    /** The kept-side margin held at the eye through the sliver zone. Budget: view-bob translation
     *  erodes the effective clearance by dot(nView, bobT) (viewSpacePlaneW), up to ~0.1 at a
     *  sprint bob peak — 0.20 keeps >= ~0.1 margin over it. */
    public static final double CROSSING_EYE_CLEARANCE = 0.20;
    /** Largest camera-to-plane distance at which the seam sliver can rasterize = the near-plane
     *  corner reach: 0.05 x corner secant = 0.087 (FOV 70, 16:9), 0.154 (Quake Pro), ~0.19
     *  (Quake Pro 21:9) — and MC's movement fovModifier multiplies further (sprint x1.15 =>
     *  ~0.21-0.26; sprinting is the TYPICAL crossing state). 0.30 covers those with margin.
     *  KNOWN LIMIT: dest-world units — a SCALED crossing portal changes the camera-unit ratio;
     *  the ARM census's armedVoidRisk counter is the live detector for a shortfall. */
    public static final double SLIVER_ZONE = 0.30;
    /** Where the relax has fully ramped out to IP's constant -ADJUSTMENT. */
    public static final double RAMP_END = 0.60;

    /**
     * The correction to pass to {@link #setupInnerClipping} for a full-pipeline dest render:
     * IP's constant {@code -ADJUSTMENT} far from the plane, smoothly relaxed camera-side inside
     * the crossing window (derivation above). Returns exactly {@code -ADJUSTMENT} when the lever
     * disables the relax, the plane is null, the camera is beyond {@link #RAMP_END}, the
     * rendering portal is a Mirror, or the pass is a NESTED recursion layer.
     *
     * <p>relax(d) = max(0, min(CLEARANCE − ADJUSTMENT + d,
     *                          (CLEARANCE − ADJUSTMENT + SLIVER_ZONE)·(RAMP_END − d)/(RAMP_END − SLIVER_ZONE)))
     * — the first term holds {@code eyeDist == CLEARANCE} through the window (and naturally
     * decays for d < 0, where the eye's own depth already provides clearance); the second is the
     * continuity ramp; both meet at {@code d == SLIVER_ZONE}.
     *
     * <p><b>Documented trade-off (panel-adjudicated, accepted):</b> the relax keys on DISTANCE,
     * not on an actual crossing — a player leaning within {@link #RAMP_END} of a crossable portal
     * without crossing sees up to {@code CLEARANCE + SLIVER_ZONE} (0.50) blocks of near-side dest
     * content through the aperture that IP would clip. That content is the dest doorway's
     * interior; the ramp keeps the transition continuous (no pop), and the alternative — a black
     * band on every crossing — is strictly worse.
     */
    public static double innerClipCorrectionForCrossing(
        @Nullable Plane plane, @Nullable Portal renderingPortal, Vec3 renderCameraPos
    ) {
        double base = -ADJUSTMENT;
        if (plane == null || IPGlobal.SEAM_CLIP_RELAX_DISABLED_LEVER) {
            return base;
        }
        // MIRROR EXCLUSION: a mirror is never CROSSED, so the crossing window never legitimately
        // applies — relaxing there would surface a slab of the mirrored space's near-side
        // geometry whenever the player walks up close. Mirrors keep IP's constant.
        if (renderingPortal instanceof qouteall.imm_ptl.core.portal.Mirror) {
            return base;
        }
        // LAYER-1 GATE (panel): only the OUTERMOST portal window is physically crossable, and the
        // layer-inheritance branch of getActiveClippingPlane can hand a nested pass a transformed
        // OUTER portal's plane (including an outer Mirror's — which the instanceof above cannot
        // see). Nested layers keep IP's constant: no benefit, only exposure.
        if (PortalRendering.getPortalLayer() > 1) {
            return base;
        }
        Vec3 n = plane.normal();
        Vec3 p = plane.pos();
        double camToPlane = n.x * (renderCameraPos.x - p.x)
            + n.y * (renderCameraPos.y - p.y)
            + n.z * (renderCameraPos.z - p.z);
        double d = -camToPlane; // positive pre-crossing, negative once the render eye is past
        if (d >= RAMP_END) {
            return base;
        }
        double hold = CROSSING_EYE_CLEARANCE - ADJUSTMENT + d;
        double ramp = (CROSSING_EYE_CLEARANCE - ADJUSTMENT + SLIVER_ZONE)
            * (RAMP_END - d) / (RAMP_END - SLIVER_ZONE);
        double relax = Math.max(0.0, Math.min(hold, ramp));
        return base - relax;
    }

    // S13-L: |det(3x3) − 1| threshold below which the model-view is treated as a rigid rotation (no scale)
    // and the proven forward-rotate fast path is taken (bit-identical unscaled render). A pure-rotation
    // product accumulates only ~1e-6 float error in its determinant, while any real portal scale k=1/s
    // moves det=k³ by ≥~3% for s≳1.01 — so 1e-3 cleanly separates "unscaled" from "scaled".
    private static final float SCALE_DETECT_EPSILON = 1.0e-3f;

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
     * GlCommandEncoderClipMixin uploads to gl_ClipDistance[0]: planeXYZ = the clip normal carried to eye
     * space by {@link #rotateClipNormalToViewSpace} (R·n for the unscaled common case, the covector
     * inverse-transpose M⁻ᵀ·n under a scaling model-view — S13-L), planeW = c — except under a
     * translation-carrying MV (the IS-BOB bobbed dest matrix), where {@link #viewSpacePlaneW} adds the
     * lever-gated exact W-term. Kept half-space is preserved
     * exactly (see class SIGN NOTE). Gated by
     * {@code IPGlobal.enableClippingMechanism}, mirroring IP's enableClipping() guard; isClippingEnabled
     * is set in lockstep with the com.warwa gl_ClipDistance enable that restore(...,true) performs.
     */
    private static void feedViewSpacePlane(double[] beforeModelView, Matrix4f modelView) {
        if (!IPGlobal.enableClippingMechanism) {
            return;
        }
        Vector3f nView = rotateClipNormalToViewSpace(beforeModelView, modelView);
        com.warwa.seamlessportals.render.FrontClipping.restore(
            new com.warwa.seamlessportals.render.FrontClipping.Snapshot(
                nView.x, nView.y, nView.z,
                viewSpacePlaneW(beforeModelView, modelView, nView), true
            )
        );
        isClippingEnabled = true;
    }

    /**
     * IS-BOB H2 (panel wf_22f132bb-257): with a TRANSLATION-carrying model-view (MV = POSE·V, the
     * iris bob-sync's bobbed dest matrix; t = col3(POSE) since col3(V)=0) the eye-space clip
     * evaluation {@code dot((MV·p).xyz, planeXYZ) + planeW} gains a spurious
     * {@code dot(planeXYZ, t)}; the exact form is {@code planeW = c − dot(planeXYZ, col3(MV))} —
     * using the PASSED matrix's m30/31/32 makes the form exact under ANY factorization. Lever-gated
     * + exact-zero-guarded: every translation-free caller (all shaders-OFF/stencil feeds — camera
     * rotation matrices) and every lever-OFF session computes bit-identical planeW with zero new
     * float ops. Lever-ON also CORRECTS the pre-existing bounded main-pass error when iris's
     * bobbed field reaches per-entity clip captures (adjudication A2/R4).
     */
    private static float viewSpacePlaneW(
        double[] beforeModelView, Matrix4f modelView, Vector3f nView
    ) {
        float w = (float) beforeModelView[3];
        if (IPGlobal.isIrisBobSyncActive()
            && (modelView.m30() != 0f || modelView.m31() != 0f || modelView.m32() != 0f)) {
            w -= nView.x * modelView.m30() + nView.y * modelView.m31() + nView.z * modelView.m32();
        }
        return w;
    }

    /**
     * The single place IP's world-space clip NORMAL {@code n = beforeModelView[0..2]} is turned into the
     * mod's EYE-space plane store (planeXYZ). {@code planeW = c = beforeModelView[3]} is written unchanged
     * by the callers for every translation-free MV (the S11-B/D4.4 SIGN NOTE convention) — under a
     * translation-carrying MV the callers route through {@link #viewSpacePlaneW} (IS-BOB H2).
     * See the class SIGN NOTE (S13-L) for the full derivation; in brief:
     *
     * <ul>
     *   <li>The 26.2 clip shader evaluates in EYE space:
     *       {@code gl_ClipDistance[0] = dot(ModelViewMat·pos, planeXYZ) + planeW}. Exactness of the kept
     *       half-space {@code n·p_rel + c > 0} (in true dest-world units) therefore requires
     *       {@code Mᵀ·planeXYZ = n}, i.e. {@code planeXYZ = M⁻ᵀ·n} — the INVERSE-TRANSPOSE of the
     *       model-view's linear block, because a clip normal is a COVECTOR.</li>
     *   <li>For a pure rotation {@code R} (unscaled / non-fuse-view scaling portal, the proven first-light
     *       common case) {@code R⁻ᵀ = R}, so the forward column-form rotate {@code M·n} already equals the
     *       covector transform. That path is kept BIT-IDENTICAL — no invert, no float drift.</li>
     *   <li>Only a fuse-view scaling portal's model-view carries a uniform scale {@code k=1/s}
     *       ({@code det = k³ ≠ 1}); there {@code M·n = k·R·n} and the shader's own {@code k·p_rel}
     *       compounds it to {@code k²(n·p_rel)+c} (the S13-L white-bar defect). {@code M⁻ᵀ·n = (1/k)R·n}
     *       cancels it exactly: {@code dot(k·R·p_rel, (1/k)R·n) = p_rel·n}. Sign-preserving on both the
     *       {@code k>1} and {@code k<1} sides.</li>
     * </ul>
     */
    private static Vector3f rotateClipNormalToViewSpace(double[] beforeModelView, Matrix4f modelView) {
        float nx = (float) beforeModelView[0];
        float ny = (float) beforeModelView[1];
        float nz = (float) beforeModelView[2];

        // The linear (rotation·scale) 3x3 block; translation is dropped exactly as the w=0 forward idiom
        // did. Portals apply ONLY rotation + UNIFORM scale to the view matrix (getPortalRotationMatrix +
        // getPortalScaleMatrix; no shear), so det = k³ where k is the model-view scale.
        Matrix3f linear = new Matrix3f(modelView);
        float det = linear.determinant();

        if (!Float.isFinite(det) || Math.abs(det - 1.0f) <= SCALE_DETECT_EPSILON) {
            // UNSCALED (rigid rotation): R⁻ᵀ == R ⇒ the forward rotate IS the covector transform. Keep the
            // PROVEN first-light path bit-identical. COLUMN FORM M·v (anti-fix guard: never mulTranspose).
            Vector4f nView = new Vector4f(nx, ny, nz, 0f);
            nView.mul(modelView);
            return new Vector3f(nView.x, nView.y, nView.z);
        }

        // SCALING model-view (k != 1): transform the covector by the INVERSE-TRANSPOSE of the linear block
        // (== IP's transformClipEquation on the translation-free part). in-place: linear becomes M⁻ᵀ.
        return linear.invert().transpose().transform(new Vector3f(nx, ny, nz));
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
    // and the EXACT eye-space covector transform of feedViewSpacePlane (rotateClipNormalToViewSpace: the
    // S13-L rotation-only/inverse-transpose form, see the class SIGN NOTE) — they just return the Snapshot
    // instead of feeding the single com.warwa store. Zero live edits.
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
     * store. {@code planeXYZ} is the clip normal carried to eye space by {@link #rotateClipNormalToViewSpace}
     * — the forward column-form rotate {@code R·n} for the unscaled common case (bit-identical; do NOT "fix"
     * to {@code mulTranspose} — S11-A anti-fix guard), the covector inverse-transpose {@code M⁻ᵀ·n} under a
     * scaling model-view (S13-L). {@code planeW} via the shared {@link #viewSpacePlaneW} helper (= c for
     * every translation-free MV; the IS-BOB W-term otherwise), {@code enabled = true}. {@code viewRotation}
     * is the world→view model-view the 26.2 draw applies to the camera-relative submit poses; the normal
     * transform uses only its 3x3 linear block, the W-term reads its translation column.
     */
    private static com.warwa.seamlessportals.render.FrontClipping.Snapshot toViewSpaceSnapshot(
        double[] beforeModelView, Matrix4f viewRotation
    ) {
        // Same eye-space covector transform as feedViewSpacePlane (S13-L): rotation-only for the unscaled
        // common case (bit-identical), inverse-transpose under a scaling model-view. planeW via the
        // SHARED helper (IS-BOB H2 — writer parity with feedViewSpacePlane is MANDATORY: dest-pass
        // PerEntityClipBracket captures consume the bobbed destCameraState.viewRotationMatrix).
        Vector3f nView = rotateClipNormalToViewSpace(beforeModelView, viewRotation);
        return new com.warwa.seamlessportals.render.FrontClipping.Snapshot(
            nView.x, nView.y, nView.z,
            viewSpacePlaneW(beforeModelView, viewRotation, nView), true
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
