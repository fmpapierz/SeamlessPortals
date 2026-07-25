package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.q_misc_util.Helper;

/**
 * IS-BOB — the shaders-ON relative-bob fix (panel wf_22f132bb-257, adjudicated DERIVE-POSE core,
 * 2×SOUND-WITH-FIXES folded). <b>ZERO iris imports</b> — self-disarms via the relocation
 * discriminator if iris changes strategy; failure mode = today's behavior, never corruption.
 *
 * <p><b>Mechanism</b> (triple-javap'd): under a shaderpack iris's {@code MixinModelViewBobbing}
 * strips the bob+spin multiply from the projection (saving the pose as {@code bobStack}) and
 * {@code mulLocal}s it onto the MODELVIEW argument of the MAIN {@code LevelRenderer.render} call
 * only — IN PLACE on the {@code cameraRenderState.viewRotationMatrix} FIELD object, no restore.
 * The compat route's nested dest render got neither leg ⇒ the world bobbed while the window sat
 * static. The aperture side (stamp/query/cull via {@code passingModelView}) is ALREADY the
 * post-mulLocal bobbed matrix — only the dest CONTENT was missing the pose.
 *
 * <p><b>The pipeline per frame:</b> capture a COPY of the pre-bob view rotation V at
 * extract-RETURN (post-R13k); discriminate relocation at the projection upload (the uploaded
 * matrix bit-equals the pristine {@code cameraRenderState.projectionMatrix} ⟺ iris stripped the
 * bob); derive {@code POSE = (bobStack·V)·V⁻¹} at the compat-pass entry from the anchor's
 * post-mulLocal copy; apply per portal as {@code POSE_s·V_dest} (pre-multiply, eye space —
 * mirroring iris's own {@code mulLocal}; post-multiply would bob in dest-world axes = the classic
 * S13-M order error). Projections stay UNBOBBED everywhere under iris, like iris's own main pass.
 *
 * <p><b>Keying note (verify fold ①):</b> the frameIndex keys are belt-and-suspenders, NOT the
 * load-bearing mechanism — the pump SKIPS {@code frameIndex++} on mid-packet mismatch frames and
 * panorama re-fires captures under one key. The actual safety invariant is structural: every
 * capture is overwritten inside the same renderLevel that consumes it, and derive always precedes
 * apply within the same {@code onBeforeHandRendering} invocation. Do NOT relocate derive out of
 * the workhorse on the strength of "frameIndex-keyed = safe".
 */
@Environment(EnvType.CLIENT)
public final class IrisBobSync {

    private IrisBobSync() {}

    /** V_main COPY — NEVER the field ref: iris mulLocal-mutates that exact object later in the
     *  frame, and the R13k handler replaces the object every frame. */
    private static final Matrix4f baseView = new Matrix4f();
    private static int baseViewFrameIndex = Integer.MIN_VALUE;
    private static int relocatedFrameIndex = Integer.MIN_VALUE;
    /** POSE = bobbedMV · V⁻¹ (bob + hurt tilt + spin, one product). */
    private static final Matrix4f framePose = new Matrix4f();
    private static final Matrix4f scratchInv = new Matrix4f();
    private static int poseFrameIndex = Integer.MIN_VALUE;
    private static boolean livenessLogged = false;
    private static boolean warnedBaseMissing = false;
    private static boolean warnedNonFinite = false;
    private static long lastProbeMs = 0;

    /**
     * Per-FRAME. Called at GameRenderer.extract RETURN, program-order AFTER the R13k
     * view-rotation replacement (same method body — no mixin-priority dependence).
     */
    public static void onExtractBaseViewCaptured(Matrix4f viewRotationField) {
        baseView.set(viewRotationField);
        baseViewFrameIndex = RenderStates.frameIndex;
    }

    /**
     * Per-FRAME. The post-spin level-projection upload (the mod's existing wrap). Bit-equal
     * arg == pristine base ⟺ iris stripped bob+spin from the projection this frame (or the pose
     * is identity — route-gated harmless: the applied pose is then V·V⁻¹ ≈ I, ~1e-7, five orders
     * below bob amplitude; deliberately NO deadband — an epsilon threshold pops when crossed,
     * the S13-M lesson).
     */
    public static void onMainProjectionCaptured(
        Matrix4f uploaded, @Nullable Matrix4f unbobbedBase
    ) {
        boolean relocated = unbobbedBase != null && bitEquals(uploaded, unbobbedBase);
        if (relocated) {
            relocatedFrameIndex = RenderStates.frameIndex;
        }
        if (IPGlobal.BOB_SYNC_PROBE) {
            probeTick(uploaded, unbobbedBase, relocated);
        }
    }

    /**
     * Per-FRAME derive at the compat-pass entry (IrisCompatOn262Renderer workhorse, after all
     * early-return gates), from the IS0 anchor's post-mulLocal copy of the main modelview.
     * The previously-IGNORED onBeforeHandRendering arg becomes load-bearing here.
     */
    public static void deriveFramePose(Matrix4f bobbedMainModelView) {
        poseFrameIndex = Integer.MIN_VALUE; // IS5-H discipline: a stale pose can never leak
        if (!IPGlobal.isIrisBobSyncActive()) {
            return; // lever A/B: byte-identical off
        }
        int frame = RenderStates.frameIndex;
        if (relocatedFrameIndex != frame) {
            return; // iris did not relocate this frame (shaders off / vanilla pipeline / toggle)
        }
        if (baseViewFrameIndex != frame) {
            // Structurally impossible unless the extract hook was reordered — name the surprise.
            if (!warnedBaseMissing) {
                warnedBaseMissing = true;
                Helper.err("[iris-bob-sync] relocated frame but no base-view capture — skipped "
                    + "(once-only warn)");
            }
            return;
        }
        // V is orthonormal (rotation product; R13k adds only rotations at the main extract) —
        // the general invert is kept for robustness, guarded below.
        scratchInv.set(baseView).invert();
        float diag = scratchInv.m00() + scratchInv.m11() + scratchInv.m22() + scratchInv.m33();
        if (!Float.isFinite(diag)) {
            if (!warnedNonFinite) {
                warnedNonFinite = true;
                Helper.err("[iris-bob-sync] non-finite view inverse — skipped (once-only warn)");
            }
            return;
        }
        // POSE = bobbedMV · V⁻¹ (JOML mul = this·right — ORDER IS LOAD-BEARING, S13-M hazard #1).
        framePose.set(bobbedMainModelView).mul(scratchInv);
        poseFrameIndex = frame;
    }

    /**
     * Per-PORTAL apply. Returns a FRESH copy — LOAD-BEARING, not style (verify fold FIX-2):
     * iris's {@code CapturedRenderingState.setGbufferModelView} ALIASES its argument (putfield of
     * the reference, no copy), so a reused scratch for the bobbed dest matrix would retro-mutate
     * iris's captured gbufferModelView across portals. Never fold this into a shared scratch.
     *
     * <p>Translation ×s = IP's dest bob form (S13-M P3, modelview flavor) — gated on
     * {@code IPGlobal.viewBobbingReduce} (verify fold FIX-1): when the reduce option is OFF, the
     * main multiplier is 1 and IP's dest applies the UNSCALED translation, so we must too.
     * The linear 3×3 block and m33 are untouched either way. s==1 skips the op.
     */
    public static @Nullable Matrix4f getScaledPoseForDestPass(double extraModelViewScaling) {
        if (poseFrameIndex != RenderStates.frameIndex) {
            return null; // not relocated / lever-off / stale frame → caller aliases the raw matrix
        }
        Matrix4f pose = new Matrix4f(framePose);
        if (extraModelViewScaling != 1.0 && IPGlobal.viewBobbingReduce) {
            float s = (float) extraModelViewScaling;
            pose.m30(pose.m30() * s);
            pose.m31(pose.m31() * s);
            pose.m32(pose.m32() * s);
        }
        IPGlobal.irisBobSyncApplyCount++;
        if (!livenessLogged) {
            livenessLogged = true;
            Helper.log("[iris-bob-sync] LIVE (first apply): s=" + extraModelViewScaling
                + " poseT=(" + pose.m30() + "," + pose.m31() + "," + pose.m32() + ")"
                + " (A/B lever -Dseamlessportals.disableIrisBobSync)");
        }
        return pose;
    }

    /** Mod-owned bit-compare — no dependence on JOML equals()/properties fast-path semantics. */
    private static boolean bitEquals(Matrix4f a, Matrix4f b) {
        return Float.floatToIntBits(a.m00()) == Float.floatToIntBits(b.m00())
            && Float.floatToIntBits(a.m01()) == Float.floatToIntBits(b.m01())
            && Float.floatToIntBits(a.m02()) == Float.floatToIntBits(b.m02())
            && Float.floatToIntBits(a.m03()) == Float.floatToIntBits(b.m03())
            && Float.floatToIntBits(a.m10()) == Float.floatToIntBits(b.m10())
            && Float.floatToIntBits(a.m11()) == Float.floatToIntBits(b.m11())
            && Float.floatToIntBits(a.m12()) == Float.floatToIntBits(b.m12())
            && Float.floatToIntBits(a.m13()) == Float.floatToIntBits(b.m13())
            && Float.floatToIntBits(a.m20()) == Float.floatToIntBits(b.m20())
            && Float.floatToIntBits(a.m21()) == Float.floatToIntBits(b.m21())
            && Float.floatToIntBits(a.m22()) == Float.floatToIntBits(b.m22())
            && Float.floatToIntBits(a.m23()) == Float.floatToIntBits(b.m23())
            && Float.floatToIntBits(a.m30()) == Float.floatToIntBits(b.m30())
            && Float.floatToIntBits(a.m31()) == Float.floatToIntBits(b.m31())
            && Float.floatToIntBits(a.m32()) == Float.floatToIntBits(b.m32())
            && Float.floatToIntBits(a.m33()) == Float.floatToIntBits(b.m33());
    }

    /** 1Hz [BOB-SYNC] probe line (lever-gated; the MixinGameRenderer frame-boundary idiom).
     *  projDeltaMax computed only here — zero cost when the probe is off. */
    private static void probeTick(Matrix4f uploaded, @Nullable Matrix4f base, boolean relocated) {
        long now = System.currentTimeMillis();
        if (now - lastProbeMs < 1000) {
            return;
        }
        lastProbeMs = now;
        float projDeltaMax = 0f;
        if (base != null) {
            Matrix4f d = new Matrix4f(uploaded).sub(base);
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 4; r++) {
                    projDeltaMax = Math.max(projDeltaMax, Math.abs(d.get(c, r)));
                }
            }
        }
        boolean poseFresh = poseFrameIndex == RenderStates.frameIndex;
        Helper.log("[BOB-SYNC] relocated=" + relocated
            + " projDeltaMax=" + projDeltaMax
            + " poseT=(" + framePose.m30() + "," + framePose.m31() + "," + framePose.m32() + ")"
            + " poseFresh=" + poseFresh
            + " applies=" + IPGlobal.irisBobSyncApplyCount);
    }
}
