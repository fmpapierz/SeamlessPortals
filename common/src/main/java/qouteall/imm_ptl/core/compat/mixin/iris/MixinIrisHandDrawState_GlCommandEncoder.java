package qouteall.imm_ptl.core.compat.mixin.iris;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.warwa.seamlessportals.render.HandDrawStateDump;

/**
 * IS5-HAND-DRAW — draw-time state dump for REAL hand-feature draws (design in
 * {@link HandDrawStateDump}). Injects at {@code GlCommandEncoder.trySetup} RETURN — the state
 * is fully applied there, immediately before the GL draw call issues (the same seam the
 * sodium clip-upload/probe mixins use) — and fires only when trySetup succeeded AND iris's
 * {@code HandRenderer.INSTANCE.isActive()} (a real hand draw is being encoded). Registered
 * via the iris arm ("Iris" in the simple name = iris-present gate); {@code require = 0} with
 * the dump's once-only ARMED line as the landing proof. DEFAULT-OFF lever; the handler's
 * iris reference only executes when iris is present (the mixin does not apply otherwise).
 */
@Mixin(targets = "com/mojang/blaze3d/opengl/GlCommandEncoder")
public class MixinIrisHandDrawState_GlCommandEncoder {

    @Inject(
        method = "trySetup(Lcom/mojang/blaze3d/opengl/GlRenderPass;Ljava/util/Collection;)Z",
        at = @At("RETURN"),
        require = 0
    )
    private void ip_handDrawStateDump(CallbackInfoReturnable<Boolean> cir) {
        if (!HandDrawStateDump.ENABLED || !cir.getReturnValueZ()) {
            return;
        }
        if (qouteall.imm_ptl.core.render.IrisCompatPaste.STAMP_DRAWING) {
            // IS5-STAMP-EAT: the stamp's own draw — read its executed state here, the only
            // point that is provably the state the draw runs under.
            HandDrawStateDump.onStampDrawSetup();
            return;
        }
        try {
            net.irisshaders.iris.pathways.HandRenderer hr =
                net.irisshaders.iris.pathways.HandRenderer.INSTANCE;
            if (hr != null && hr.isActive()) {
                HandDrawStateDump.onHandDrawSetup(hr.isRenderingSolid());
            }
        }
        catch (Throwable ignored) {
            // iris absent/drifted: never let the probe touch the encoder path
        }
    }
}
