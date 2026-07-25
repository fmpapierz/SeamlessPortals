package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shadows.ShadowCompositeRenderer;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;

import java.lang.reflect.Field;

/**
 * IS5-FF — nested shadowcomp (flood-fill) suppression: the Ultra "Advanced Color Tracing"
 * lava-light-phantom fix (§2h; recon HIGH + two user live toggles: ACT OFF kills it,
 * ACT ON + WSR OFF keeps it).
 *
 * <p>MECHANISM (verified, do-not-re-derive; POLISH_SESSION_NOTES §2h): the compat route's nested
 * same-dim dest render re-enters iris ShadowRenderer.renderShadows on the SAME pipeline with
 * an UN-advanced SystemTimeUniforms.COUNTER (iris$startFrame is on the outer GameRenderer
 * .render) => same framemod2 => its unconditional tail-call compositeRenderer.renderAll()
 * (javap: getfield@1402/invokevirtual@1405, straight-line, sole consumer of the field)
 * re-runs the pack's shadowcomp COMPUTE and FULLY OVERWRITES the frame's write-side
 * floodfill_img ping-pong (clear=false, PERSISTENT) with DEST-seeded light. The next main
 * frame's readers (mainLighting GetLightVolume, composite1 GetColoredLightFog) paint dest
 * lava light over the SOURCE world. Save/restore is cost-blocked (1 GiB at Ultra) — the fix
 * is SUPPRESSION of the nested dispatch.
 *
 * <p>SHAPE: per-FRAME bracket at the compat anchor (post-main-finalize — the MAIN dispatch of
 * the frame has already run; onBeforeHandRendering rejects re-entry, so the bracket is once
 * per frame): reflectively swap the MAIN pipeline's ShadowRenderer.compositeRenderer
 * (private final; write via setAccessible — instance finals are settable on JDK 25) to a
 * stateless no-op subclass; restore in the same finally. install() is the FIRST statement
 * inside the try and uninstall() the FIRST statement of the finally (verify-fold F1: no
 * intervening statement may throw while the noop is installed, or the NEXT MAIN frame's
 * shadowcomp would silently freeze). Cross-dim dest renders run on a SEPARATE per-dim
 * pipeline object — structurally untouched (own images; and if that recon were ever wrong, a
 * shared pipeline would be swapped too, which is then the CORRECT scope). Whole-STAGE
 * suppression is deliberate and pack-agnostic: any shadowcomp pass writing per-camera or
 * persistent state from the nested dest camera is the same poison family. (Verify-fold FIX-3
 * honesty: a pack whose shadowcomp does GRAPHICAL shadowcolor post-processing gets raw
 * un-postprocessed dest shadowcolor in-window — a mismatch artifact, not mere absence;
 * Complementary's shadowcomp is compute-only, zero impact.)
 *
 * <p>VOXEL_IMG (left unsuppressed, provably harmless for the carrier): voxel_img is clear=true
 * — iris re-clears it in EVERY beginLevelRendering (clearImages built from shouldClear,
 * IrisRenderingPipeline:291, executed unconditionally :1034) and the next MAIN frame's shadow
 * terrain rewrites SOURCE ids before its dispatch reads. Frame-N post-anchor readers
 * (verify-fold F3/FIX-1 correction — the HAND's gbuffers include mainLighting and draw AFTER
 * this anchor): the hand's ACT_CORNER_LEAK_FIX voxel_sampler read sees dest ids — PRE-EXISTING
 * and byte-identical pre/post fix (the nested shadow terrain writes them today too); the
 * hand's FLOODFILL read is actively HEALED by this fix (pre-fix it sampled the freshly
 * dest-poisoned write side) — hand colored-light near portals IMPROVES.
 *
 * <p>ACCEPTED COST (pre-registered): the same-dim dest WINDOW's own ACT colored light reads
 * the SOURCE-seeded, main-camera-anchored volume => in-window ACT bounce is the source area's,
 * translated by the portal offset (mild; emissive/vanilla-lightmap glow in-window unaffected).
 * NOT improvable mod-side: the read is compiled into the pack's gbuffer/composite GLSL.
 * Cross-dim windows are exempt. Walking through the portal restores correct ACT — light seeds
 * instantly, propagated bounce refills over ~1-2 s (93.5%/frame — the same refill any large
 * camera jump pays today); the load-bearing property is NO poison decay tail.
 *
 * <p>FAILURE ENVELOPE: every resolve/install/restore failure funnels to a once-only WARN and
 * disarms to pre-fix behavior. Never throws into the anchor. The iris destroy path is
 * unaffected by construction: IrisRenderingPipeline destroys via its OWN
 * shadowCompositeRenderer ref (:1310), never through the swapped ShadowRenderer field.
 */
@Environment(EnvType.CLIENT)
public final class IrisShadowCompositeSuppressor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Field shadowRendererField;    // IrisRenderingPipeline.shadowRenderer
    private static Field compositeRendererField; // ShadowRenderer.compositeRenderer
    private static Field packDirectivesField;    // IrisRenderingPipeline.packDirectives
    private static boolean resolveAttempted = false;
    private static boolean broken = false;       // permanent disarm (resolve/restore failure)

    // ONE stateless noop for the process lifetime (its inherited fields are never read on the
    // noop path; survives pipeline reloads — instances are re-resolved every frame; holds no
    // GL resources).
    private static ShadowCompositeRenderer noopInstance = null;

    // Frame-bracket state (render thread only).
    private static ShadowRenderer swappedOn = null;
    private static ShadowCompositeRenderer savedReal = null;

    private static boolean liveLogged, skipVanillaLogged, skipNoShadowLogged,
        skipRealNullLogged, failLogged, restoreFailLogged;
    private static long lastProbeNanos = 0L;

    /** The no-op stand-in. The super ctor with empty sources builds zero programs/framebuffers
     *  (verified against the decompiled ctor: only packDirectives is dereferenced; the source
     *  loop is skipped; passes = empty); its only side effect is one cached
     *  GL_READ_FRAMEBUFFER=0 bind, executed once at first install inside the portal phase.
     *  renderAll() replicates the exact GL-state tail an empty-passes renderAll leaves
     *  (ShadowCompositeRenderer:244-246) and skips the RenderPass create/close. Double-safe:
     *  even if a future iris renames renderAll (override no longer overrides), the inherited
     *  real method iterates this instance's EMPTY pass list — still no dispatch. */
    private static final class NoopShadowCompositeRenderer extends ShadowCompositeRenderer {
        NoopShadowCompositeRenderer(PackDirectives directives) {
            super(null, directives, new ProgramSource[0], new ComputeSource[0][],
                null, null, null, null, null, null, ImmutableMap.of(), null, null);
        }

        @Override
        public void renderAll() {
            IPGlobal.nestedShadowCompositeNoopHits++;
            if (!liveLogged) {
                liveLogged = true;
                LOGGER.info("[Seamless Portals] IS5-FF nested shadowcomp suppression LIVE"
                    + " (once-only): a nested portal-dest shadow pass reached the no-op"
                    + " composite — the flood-fill volume poison is being suppressed"
                    + " (A/B lever -Dseamlessportals.disableNestedShadowComposite)");
            }
            if (IPGlobal.NESTED_SHADOW_COMPOSITE_PROBE) { // 1Hz max — the log4j stall rule
                long now = System.nanoTime();
                if (now - lastProbeNanos >= 1_000_000_000L) {
                    lastProbeNanos = now;
                    LOGGER.info("[IS5-FF] installs={} noopHits={}",
                        IPGlobal.nestedShadowCompositeSuppressCount,
                        IPGlobal.nestedShadowCompositeNoopHits);
                }
            }
            // State-parity tail (what an empty-passes renderAll would have left behind).
            ProgramUniforms.clearActiveUniforms();
            GlStateManager._glUseProgram(0);
            GlStateManager._activeTexture(33984 /* GL_TEXTURE0 */);
        }
    }

    private IrisShadowCompositeSuppressor() {}

    private static boolean resolveOnce() {
        if (resolveAttempted) return !broken;
        resolveAttempted = true;
        try {
            shadowRendererField = IrisRenderingPipeline.class.getDeclaredField("shadowRenderer");
            shadowRendererField.setAccessible(true);
            compositeRendererField = ShadowRenderer.class.getDeclaredField("compositeRenderer");
            compositeRendererField.setAccessible(true);
            packDirectivesField = IrisRenderingPipeline.class.getDeclaredField("packDirectives");
            packDirectivesField.setAccessible(true);
            return true;
        } catch (Throwable t) {
            broken = true;
            LOGGER.warn("[Seamless Portals] IS5-FF DISARMED: could not resolve the iris"
                + " shadow-composite fields on this Iris build. The Ultra colored-light"
                + " phantom fix is NOT applying; behavior = pre-fix.", t);
            return false;
        }
    }

    /** Install the no-op on the MAIN pipeline's ShadowRenderer. Called ONCE per frame as the
     *  FIRST statement inside the anchor's try (verify-fold F1 — the manager-slot-is-main-
     *  valued anchor, live-proven by IrisTemporalTargetGuard). Never throws. */
    public static void install() {
        if (broken || swappedOn != null || !IPGlobal.isNestedShadowCompositeSuppressActive()) {
            return;
        }
        try {
            if (!resolveOnce()) return;
            Object pl = Iris.getPipelineManager().getPipelineNullable();
            if (!(pl instanceof IrisRenderingPipeline pipeline)) {
                if (!skipVanillaLogged) {
                    skipVanillaLogged = true;
                    LOGGER.info("[Seamless Portals] IS5-FF idle (once-only): current pipeline"
                        + " is {} (no shaderpack pipeline) — nothing to suppress",
                        pl == null ? "null" : pl.getClass().getName());
                }
                return;
            }
            ShadowRenderer sr = (ShadowRenderer) shadowRendererField.get(pipeline);
            if (sr == null) {
                if (!skipNoShadowLogged) {
                    skipNoShadowLogged = true;
                    LOGGER.info("[Seamless Portals] IS5-FF idle (once-only): pack has no"
                        + " shadow pass (shadowRenderer=null) — nothing to suppress");
                }
                return;
            }
            ShadowCompositeRenderer real = (ShadowCompositeRenderer) compositeRendererField.get(sr);
            if (real == null || real instanceof NoopShadowCompositeRenderer) {
                // Belt: never double-swap / never save a noop as "real". real==null is
                // unreachable on iris 1.11.2 (the pipeline constructs the composite before
                // ShadowRenderer's ctor consumes it) — hitting either signals an unknown iris
                // variant (verify-fold F2: name the surprise, once).
                if (!skipRealNullLogged) {
                    skipRealNullLogged = true;
                    LOGGER.warn("[Seamless Portals] IS5-FF skip (once-only): compositeRenderer"
                        + " was {} at install — unknown iris variant? Fix inert this frame.",
                        real == null ? "null" : "already the no-op");
                }
                return;
            }
            if (noopInstance == null) {
                PackDirectives dirs = (PackDirectives) packDirectivesField.get(pipeline);
                noopInstance = new NoopShadowCompositeRenderer(dirs);
            }
            savedReal = real;                             // stash BEFORE the mutating op
            compositeRendererField.set(sr, noopInstance); // the only throwing op past here
            swappedOn = sr;                               // commit: plain assigns cannot throw
            IPGlobal.nestedShadowCompositeSuppressCount++;
        } catch (Throwable t) {
            swappedOn = null;
            savedReal = null;
            if (!failLogged) {
                failLogged = true;
                LOGGER.warn("[Seamless Portals] IS5-FF install THREW (once-only; behavior ="
                    + " pre-fix for affected frames)", t);
            }
        }
    }

    /** Restore the real composite. Called as the FIRST statement of the anchor's finally
     *  (verify-fold F1). A restore failure permanently disarms (a stuck no-op would freeze the
     *  main pipeline's shadowcomp until shader reload — must never recur silently). Never
     *  throws. */
    public static void uninstall() {
        if (swappedOn == null) return;
        try {
            compositeRendererField.set(swappedOn, savedReal);
        } catch (Throwable t) {
            broken = true;
            if (!restoreFailLogged) {
                restoreFailLogged = true;
                LOGGER.warn("[Seamless Portals] IS5-FF RESTORE FAILED — feature permanently"
                    + " disarmed this session; the main pipeline's shadowcomp may be inert"
                    + " until shader reload (F3+R)", t);
            }
        } finally {
            swappedOn = null;
            savedReal = null;
        }
    }
}
