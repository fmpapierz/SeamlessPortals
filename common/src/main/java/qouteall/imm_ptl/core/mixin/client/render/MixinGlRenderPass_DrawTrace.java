package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
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
@Mixin(targets = "com/mojang/blaze3d/opengl/GlRenderPass")
public abstract class MixinGlRenderPass_DrawTrace {

    @Inject(method = "setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V", at = @At("HEAD"), require = 1)
    private void portal_onSetPipeline(RenderPipeline pipeline, CallbackInfo ci) {
        if (DrawCallTrace.capturing) {
            DrawCallTrace.record("  pipeline " + pipeline);
        }
    }
}
