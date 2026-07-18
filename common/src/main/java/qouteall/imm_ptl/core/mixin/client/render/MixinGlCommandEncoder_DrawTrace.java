package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.systems.RenderPassDescriptor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.DrawCallTrace;

/**
 * S14.31 draw-trace hook 1: every render pass, by its own debug label, at creation. Targets the
 * package-private {@code GlCommandEncoder} via {@code targets=} (the proven idiom of the
 * in-tree GlCommandEncoderClipMixin). Zero cost while not capturing (one boolean check).
 */
@Mixin(targets = "com/mojang/blaze3d/opengl/GlCommandEncoder")
public abstract class MixinGlCommandEncoder_DrawTrace {

    @Inject(
        method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)"
            + "Lcom/mojang/blaze3d/systems/RenderPassBackend;",
        at = @At("HEAD"),
        require = 1
    )
    private void portal_onCreateRenderPass(
        RenderPassDescriptor descriptor, CallbackInfoReturnable<?> cir
    ) {
        if (DrawCallTrace.capturing) {
            String label;
            try {
                label = String.valueOf(descriptor.label().get());
            } catch (Throwable t) {
                label = "<label threw: " + t + ">";
            }
            DrawCallTrace.record("PASS " + label);
        }
    }
}
