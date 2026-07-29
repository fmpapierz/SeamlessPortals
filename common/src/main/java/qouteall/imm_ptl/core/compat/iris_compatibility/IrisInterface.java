package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.q_misc_util.Helper;

import java.lang.reflect.Field;

/**
 * IP's Iris facade, C2-4 LIVE (design {@code migration/C2_DESIGN.md} §1 C2-4 deliverable 1;
 * 091-map TRACER 3). {@code OnIrisPresent} is installed by
 * {@code SeamlessPortalsClientFabric.detectAndGateRenderCompat} when Iris is present and the
 * compat gate/lever passes — every {@code IrisInterface.invoker.*} call site (B1-B7 contract,
 * {@code migration/C2_IP_COMPAT_DEPTH.md} §B) goes live with it.
 *
 * <p><b>D7 NAMED DEVIATION</b> — the {@code LevelRenderer.pipeline} reflection is kept IP-EXACT
 * (091 map TRACER 3 javap-PROVED on Iris 1.11.2+26.2: Iris's {@code MixinLevelRenderer} still
 * targets {@code net.minecraft.client.renderer.LevelRenderer}, the added field is still literally
 * {@code private WorldRenderingPipeline pipeline}, un-prefixed — re-verified this stage), but the
 * resolve is no longer wrapped in {@code Helper.noError} at field-initializer time. Upstream that
 * wrapper made a resolve failure an opaque {@code IllegalStateException} thrown from the
 * {@code OnIrisPresent} ctor mid-install (a hard client crash with no actionable message). C2-4
 * resolves in the ctor with a ONE-TIME LOUD error log naming the running Iris version, and the
 * install site checks {@link OnIrisPresent#ip_isPipelineFieldResolved()} — on failure iris compat
 * is marked BROKEN and the install falls back to the warn+force-none path (never silent, never a
 * raw crash). {@code PipelineManager.getPipelineNullable()} stays the LEDGERED hardening
 * alternative (design §0.3 conflict 7) — NOT switched: the IP-exact reflection is javap-proven.
 *
 * <p>Shaders-OFF walk (javap-grounded, this stage): with no pack, Iris's
 * {@code iris$setupPipeline} still writes {@code pipeline = preparePipeline(...)} =
 * a {@code VanillaRenderingPipeline} on the MAIN renderer each frame ({@code PipelineManager}'s
 * ctor even seeds one), while secondary per-dim renderers never run {@code renderLevel}, so their
 * woven field stays null. {@code getPipeline} therefore returns non-null (main) / null (dest) and
 * the MyGameRenderer capture/null/restore bracket is same-reference inert-equivalent.
 * {@code isShaders()} = {@code Iris.getCurrentPack().isPresent()} (empty Optional, no deref);
 * {@code getShaderpackName()} = a static-field read (returns null-or-name, never throws);
 * {@code isRenderingShadowMap()} = {@code ShadowRenderer.ACTIVE} (static boolean, false with no
 * pack); {@code reloadPipelines()} = {@code destroyPipeline()} (forEach over the per-dimension
 * map + clear + null — safe on an empty/no-pack manager; only ever called by
 * {@code PortalRenderer.switchRenderer} when {@code isShaders()} is true anyway).
 */
public class IrisInterface {

    private static final Logger LOGGER = LoggerFactory.getLogger(IrisInterface.class);
    
    public static class Invoker {
        public boolean isIrisPresent() {
            return false;
        }
        
        public boolean isShaders() {
            return false;
        }
        
        public boolean isRenderingShadowMap() {
            return false;
        }
        
        public Object getPipeline(LevelRenderer worldRenderer) {
            return null;
        }
        
        public void setPipeline(LevelRenderer worldRenderer, Object pipeline) {
        
        }
        
        public void reloadPipelines() {}

        /** IS5-L in-portal-fullbright fix: advance iris's per-frame uniform counter so the nested dest
         *  pass re-uploads its PER_FRAME lighting uniforms. No-op when iris is absent (byte-identical). */
        public void bumpPerFrameUniformCounter() {}

        /** IS5-PH prev-uniform heal (the ghost-terrain fix): re-tick iris's frame-update notifier on the
         *  MAIN pipeline after the per-portal dest renders, so the next main frame's natural tick yields a
         *  clean, MAIN-valued previousCameraPosition. No-op when iris is absent (byte-identical). */
        public void healPreviousFrameUniforms(@Nullable Object mainPipelineCapturedPreLoop) {}

        /** IS5-ACT: capture the ACTIVE pipeline BEFORE the portal loop, so the heal can tick the
         *  pipeline the MAIN frame actually used rather than whichever one the last nested dest
         *  render left in the manager slot. Returns null when iris is absent (byte-identical). */
        @Nullable
        public Object capturePipelineForHeal() {
            return null;
        }

        /**
         * TP-XDIM census: iris's CURRENT pipeline identity + CURRENT dimension, read at ONE
         * synchronous call site. They must NEVER be combined from values captured at different
         * times — the IS5-ACT heal note below records cross-dim frames where a stored pipeline and
         * {@code Iris.getCurrentDimension()} disagreed.
         *
         * <p>Returns null when iris is absent OR when the compat invoker was never installed; the
         * caller MUST render that as a loud sentinel naming this invoker's class, never as
         * {@code ""} and never as a value.
         */
        @Nullable
        public String describePipelineAndDim() {
            return null;
        }

        @Nullable
        public String getShaderpackName() {
            return null;
        }
    }
    
    public static class OnIrisPresent extends Invoker {

        // D7: resolved LOUDLY in the ctor (see the class javadoc). Null == resolve failed ==
        // this instance must NOT be installed (the install site checks
        // ip_isPipelineFieldResolved() and falls back to warn+force-none).
        @Nullable
        private final Field worldRendererPipelineField;

        public OnIrisPresent() {
            Field field = null;
            try {
                field = LevelRenderer.class.getDeclaredField("pipeline");
                field.setAccessible(true);
            }
            catch (Throwable e) {
                // ONE-TIME LOUD resolve assert (D7). Named version: the field is woven by Iris's
                // own MixinLevelRenderer, so a miss means THIS iris build changed the mixin —
                // exactly what the error must say. The version read is itself guarded (this is a
                // failure path; it must never throw).
                String irisVersion;
                try {
                    irisVersion = Iris.getVersion();
                }
                catch (Throwable ignored) {
                    irisVersion = "<unknown>";
                }
                LOGGER.error(
                    "[Seamless Portals] IRIS COMPAT BROKEN: could not resolve the "
                        + "LevelRenderer.pipeline field woven by Iris (running Iris version: {}). "
                        + "This Iris build is not compatible with the ported pipeline bracket; "
                        + "falling back to portal-views-off.",
                    irisVersion, e
                );
            }
            this.worldRendererPipelineField = field;
        }

        /**
         * D7: true iff the IP-exact {@code LevelRenderer.pipeline} reflection resolved. The
         * install site refuses to install this invoker when false (warn+force-none instead), so
         * the null-field guards in {@link #getPipeline}/{@link #setPipeline} are defensive-only.
         */
        public boolean ip_isPipelineFieldResolved() {
            return worldRendererPipelineField != null;
        }

        @Override
        public boolean isIrisPresent() {
            return true;
        }
        
        @Override
        public boolean isShaders() {
            return Iris.getCurrentPack().isPresent();
        }
        
        @Override
        public boolean isRenderingShadowMap() {
            return ShadowRenderer.ACTIVE;
        }

        /**
         * IS5-L in-portal-fullbright fix (design panel SOUND; the IP {@code ExperimentalIrisPortalRenderer}
         * precedent — "make Iris to update the uniforms"): advance iris's global per-frame counter
         * ({@code SystemTimeUniforms.COUNTER.beginFrame()}) so the reused same-dim ExtendedShader programs'
         * {@code ProgramUniforms.update()} sees {@code lastFrame != COUNTER} and re-runs
         * {@code updateStage(perFrame)}, re-uploading the PER_FRAME lighting uniforms (cameraPosition,
         * sun/shadowLight/celestial, gbuffer + shadow matrices) from the already-dest-primed sources for the
         * nested dest draws. Without it the dest terrain is lit with the MAIN camera's uniforms = the
         * direction-dependent fullbright. Bracketed before+after the dest render (the after-bump re-freshens
         * the post-anchor hand/GUI). Lever-gated (default-on); try/catch so it never propagates into the pass.
         */
        @Override
        public void bumpPerFrameUniformCounter() {
            if (!qouteall.imm_ptl.core.IPGlobal.isIrisPerFrameRefreshActive()) {
                return;
            }
            try {
                SystemTimeUniforms.COUNTER.beginFrame();
            }
            catch (Throwable t) {
                // never propagate into the render pass; a bump failure just leaves the (buggy) main uniforms
            }
        }

        /**
         * IS5-PH PREV-UNIFORM HEAL (ghost panel wf_98e3a1ee-634, 2x SOUND-WITH-FIXES, mechanism
         * unanimous javap+GLSL-exact): the nested dest render reaches {@code IrisRenderingPipeline
         * .beginLevelRendering} on the SAME per-dim pipeline (iris's MixinLevelRenderer has NO
         * re-entrancy guard) → its unconditional {@code updateNotifier.onNewFrame()} ticks
         * {@code CameraPositionTracker} (a one-deep shift register) with the DEST camera → the NEXT
         * main frame uploads {@code previousCameraPosition = destCameraPos} (~the portal offset off)
         * → Complementary's taa.glsl REPROJECTION displaces the (byte-correct, guard-restored)
         * history by that offset and the TAA blend paints source-shading over the terrain = the
         * camera-tracked "ghost terrain" wave. History-clearing provably could not fix it (the
         * carrier is UNIFORM state, not texture content — live-proven: 740-820 clears/s, ghost
         * unchanged); Temporal-Filtering-off kills it (the reprojection is the painter).
         *
         * <p>The heal: ONE extra {@code onNewFrame()} tick on the MAIN pipeline, called after the
         * per-portal loop + guard restore, when the main camera is already restored — the tracker
         * then holds current=main; the NEXT frame's own natural tick shifts previous←main before any
         * upload = clean uniforms. (Without it, the next tick shifts previous←dest = the poison.)
         * Deliberately NOT calling customUniforms.update() (would double-advance smoothed customs);
         * NOT suppressing the nested tick (per-program lastFrame PER_FRAME gating depends on it).
         * Known micro-cost: smoothed uniforms (eye adaptation etc.) take one extra decay step on
         * portal frames — bounded, the nested ticks already do this k times today. Never propagates.
         */
        @Override
        @Nullable
        public Object capturePipelineForHeal() {
            try {
                return Iris.getPipelineManager().getPipelineNullable();
            }
            catch (Throwable t) {
                return null;
            }
        }

        /**
         * TP-XDIM census (log-only). Both reads happen HERE, in one call, so the pair can never be
         * assembled from two different moments.
         *
         * <p>The dimension is built from {@code NamespacedId}'s TYPED accessors
         * ({@code getNamespace()}/{@code getName()}), javap-verified present on Iris
         * 1.11.2+26.2 — deliberately NOT from {@code toString()}. A {@code toString()} that a
         * future Iris build stops overriding would silently degrade to an identity hash, which is
         * not a stable dimension identity and would be tabulated as if it were one. The accessors
         * cannot fail that way: they either return the real strings or throw, and a throw prints
         * the UNREADABLE sentinel below.
         */
        @Override
        @Nullable
        public String describePipelineAndDim() {
            try {
                Object p = Iris.getPipelineManager().getPipelineNullable();
                net.irisshaders.iris.shaderpack.materialmap.NamespacedId d =
                    Iris.getCurrentDimension();
                return "[pipeline="
                    + (p == null ? "NONE(manager slot is null)"
                    : p.getClass().getSimpleName() + "@"
                        + Integer.toHexString(System.identityHashCode(p)))
                    + " irisCurrentDim="
                    + (d == null ? "NULL" : (d.getNamespace() + ":" + d.getName()))
                    + "]";
            }
            catch (Throwable t) {
                return "UNREADABLE(" + t.getClass().getSimpleName() + ")";
            }
        }

        @Override
        public void healPreviousFrameUniforms(@Nullable Object mainPipelineCapturedPreLoop) {
            if (!qouteall.imm_ptl.core.IPGlobal.isPrevUniformHealActive()) {
                return;
            }
            try {
                // Resolve via the PIPELINE MANAGER, not the woven LevelRenderer.pipeline field:
                // live-proven (heal run #2) the field is NULL at the anchor-finally (a capture/
                // null/restore bracket's window), while the manager slot resolves the main/same-dim
                // pipeline correctly at this exact anchor every frame (IrisTemporalTargetGuard.save
                // uses it successfully right before the portal loop).
                //
                // IS5-ACT RETARGET (2026-07-26, MEASURED — the Step-0 A/B). The javadoc above assumed
                // "the nested dest render reaches beginLevelRendering on the SAME per-dim pipeline".
                // That is TRUE same-dim and FALSE cross-dim: iris keeps one pipeline per dimension,
                // the nested render's iris$setupPipeline is the LAST writer of the manager slot, and
                // the slot has NO restorer — so at this anchor the slot holds the DEST pipeline on a
                // cross-dim frame (probe-measured pipelineIdentity=DIFFERENT on 42/42 captures).
                // Ticking it fed the DEST pipeline's CameraPositionTracker the MAIN camera, so the
                // dest ACT flood-fill read previousCameraPosition ~132 blocks away and every history
                // sample landed outside the volume. LIVE A/B: heal ACTIVE => dest |posOffset|inf=132
                // and floodfill plateaus at nz=90; heal DISABLED => |posOffset|inf<=2 on 89/89 dest
                // samples and the floodfill accumulates to nz=15283. Retargeting to the PRE-LOOP
                // capture is byte-identical same-dim (same object) and fixes cross-dim, where the
                // main pipeline's notifier is never ticked by the nested render at all.
                Object pl = mainPipelineCapturedPreLoop;
                if (pl == null || !qouteall.imm_ptl.core.IPGlobal.isHealRetargetActive()) {
                    pl = Iris.getPipelineManager().getPipelineNullable();
                    if (mainPipelineCapturedPreLoop == null && !prevHealNoCaptureLogged) {
                        prevHealNoCaptureLogged = true;
                        LOGGER.warn("[Seamless Portals] IS5-ACT heal retarget: no pre-loop pipeline"
                            + " capture was supplied — falling back to the manager slot (the"
                            + " pre-retarget behaviour, which poisons the DEST tracker on cross-dim"
                            + " frames). Further occurrences suppressed.");
                    }
                }
                if (pl instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irisPipeline) {
                    irisPipeline.getFrameUpdateNotifier().onNewFrame();
                    qouteall.imm_ptl.core.IPGlobal.prevUniformHealCount++;
                    if (!prevHealLiveLogged) {
                        prevHealLiveLogged = true;
                        LOGGER.info("[Seamless Portals] IS5-PH prev-uniform heal ACTIVE (once-only"
                            + " liveness line): main-pipeline frame notifier re-ticked after the"
                            + " portal dest renders");
                    }
                }
                else if (!prevHealSkipLogged) {
                    // FAILURE-PATH LIVENESS (the first heal build skipped SILENTLY here and the run
                    // was unjudgeable — never leave a fix's miss path dark): name what we got.
                    prevHealSkipLogged = true;
                    LOGGER.warn("[Seamless Portals] IS5-PH prev-uniform heal SKIPPED: getPipeline"
                        + " returned {} (expected IrisRenderingPipeline) — the ghost fix is NOT"
                        + " applying; further occurrences suppressed",
                        pl == null ? "null" : pl.getClass().getName());
                }
            }
            catch (Throwable t) {
                if (!prevHealFailLogged) {
                    prevHealFailLogged = true;
                    LOGGER.warn("[Seamless Portals] IS5-PH prev-uniform heal THREW (suppressed"
                        + " hereafter; the ghost fix is NOT applying)", t);
                }
            }
        }

        private static boolean prevHealLiveLogged = false;
        private static boolean prevHealSkipLogged = false;
        private static boolean prevHealFailLogged = false;
        private static boolean prevHealNoCaptureLogged = false;

        @Override
        public Object getPipeline(LevelRenderer worldRenderer) {
            if (worldRendererPipelineField == null) {
                // D7 defensive-only: an unresolved instance is never installed.
                return null;
            }
            return Helper.noError(() ->
                ((WorldRenderingPipeline) worldRendererPipelineField.get(worldRenderer))
            );
        }

        // the pipeline switching is unnecessary when using shaders
        // but still necessary with shaders disabled
        @Override
        public void setPipeline(LevelRenderer worldRenderer, Object pipeline) {
            if (worldRendererPipelineField == null) {
                // D7 defensive-only: an unresolved instance is never installed.
                return;
            }
            Helper.noError(() -> {
                worldRendererPipelineField.set(worldRenderer, pipeline);
                return null;
            });
        }
        
        @Override
        public void reloadPipelines() {
            Iris.getPipelineManager().destroyPipeline();
        }
    
        @Nullable
        @Override
        public String getShaderpackName() {
            return Iris.getCurrentPackName();
        }
    }
    
    public static Invoker invoker = new Invoker();
}
