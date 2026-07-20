package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
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
