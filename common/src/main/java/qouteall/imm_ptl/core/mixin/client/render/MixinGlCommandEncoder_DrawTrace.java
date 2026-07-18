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
            // S14.32: attribute every IMMEDIATE-mode pass (PreparedRenderType buffer-source draws
            // — the unowned line-49 suspect class) to its CALLER via a compact filtered stack.
            if (label.startsWith("Immediate draw")) {
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                int emitted = 0;
                for (StackTraceElement e : stack) {
                    String c = e.getClassName();
                    if (c.contains("qouteall") || c.contains("seamlessportals")
                        || c.contains("net.minecraft.client")
                    ) {
                        DrawCallTrace.record("    at " + c + "." + e.getMethodName() + ":" + e.getLineNumber());
                        if (++emitted >= 10) break;
                    }
                }
            }
        }
    }
}
