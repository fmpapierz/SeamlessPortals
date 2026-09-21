package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.DrawCallTrace;

/**
 * S14.31 draw-trace hook 2: every pipeline bind, by RenderPipeline location (its toString).
 * Together with the pass labels (hook 1) and the mod's choreography markers, this names every
 * draw of the captured frame. Package-private target via {@code targets=}.
 */
@Mixin(targets = "com/mojang/renderpearl/backend/opengl/GlRenderPass")
public abstract class MixinGlRenderPass_DrawTrace {

    // 26.3: the backend pass no longer receives the RenderPipeline DEFINITION — GlRenderPass.setPipeline(RenderPipeline) became
    // setPipeline(BackendRenderPipeline) (mc262-ref com/mojang/blaze3d/opengl/GlRenderPass.java -> mc263-ref
    // com/mojang/renderpearl/backend/opengl/GlRenderPass.java:61; merged 26.3 jar descriptor
    // (Lcom/mojang/renderpearl/backend/api/BackendRenderPipeline;)V), always a GlRenderPipeline (:62-63 throws otherwise).
    // The trace line keeps its 26.2 TEXT: 26.2 printed RenderPipeline.toString() = its location (mc262-ref RenderPipeline.java
    // :73-75); the same string is the GlProgram's debug label — PipelineBuilder.java:342 CreateInfo.name =
    // pipeline.getLocation().toString() -> GlPipelineRecompiler.java:237 GlProgram.link(.., createInfo.name()) -> GlProgram
    // .toString() :135-137 returns it.
    @Inject(method = "setPipeline(Lcom/mojang/renderpearl/backend/api/BackendRenderPipeline;)V", at = @At("HEAD"), require = 1)
    private void portal_onSetPipeline(com.mojang.renderpearl.backend.api.BackendRenderPipeline pipeline, CallbackInfo ci) {
        if (DrawCallTrace.capturing) {
            DrawCallTrace.record("  pipeline " + (pipeline instanceof com.mojang.renderpearl.backend.opengl.GlRenderPipeline glRenderPipeline
                ? glRenderPipeline.program() : pipeline));
        }
    }
}
