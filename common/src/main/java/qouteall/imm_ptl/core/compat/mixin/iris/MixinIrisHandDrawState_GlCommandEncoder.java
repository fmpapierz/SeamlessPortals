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
@Mixin(targets = "com/mojang/renderpearl/backend/opengl/GlCommandEncoder")
public class MixinIrisHandDrawState_GlCommandEncoder {

    // 26.3: trySetup(GlRenderPass, Collection)Z -> setupDraw(GlRenderPass)V — the same per-draw seam, now void (see the full
    // citation on com.warwa.seamlessportals.mixin.client.GlCommandEncoderClipMixin). 26.2's `false` return (no draw follows)
    // cannot occur any more, so the probe sees every setup as the successful one it always filtered for.
    @Inject(
        method = "setupDraw(Lcom/mojang/renderpearl/backend/opengl/GlRenderPass;)V",
        at = @At("RETURN"),
        require = 0
    )
    private void ip_handDrawStateDump(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // 26.3: this seam now also carries a FIX, not only the dump — the crossing-window hand bracket picks its remap
        // direction from the depth function of the hand draw that is about to issue (the convention flipped LEQUAL ->
        // GEQUAL between 26.2 and 26.3; full story on IrisHandSeamDepthBracket). Runs BEFORE the dump so a dump row shows
        // the range the draw really runs under. One static boolean read for every draw that is not a crossing-window hand
        // draw; the iris reference only executes while the bracket is armed (iris present by construction).
        if (qouteall.imm_ptl.core.compat.iris_compatibility.IrisHandSeamDepthBracket.isArmedNow()
            && !qouteall.imm_ptl.core.render.IrisCompatPaste.STAMP_DRAWING) {
            try {
                net.irisshaders.iris.pathways.HandRenderer bracketHr =
                    net.irisshaders.iris.pathways.HandRenderer.INSTANCE;
                if (bracketHr != null && bracketHr.isActive()) {
                    qouteall.imm_ptl.core.compat.iris_compatibility.IrisHandSeamDepthBracket.onHandDrawSetup();
                }
            }
            catch (Throwable ignored) {
                // iris drifted: the bracket keeps its provisional direction
            }
        }
        // 26.3: IS5-HAND-TP — count the real hand draws of each frame around a client teleport (default-off probe; after the
        // bracket above so the recorded depth range is the one the draw runs under).
        if (com.warwa.seamlessportals.render.SeamHandTeleportProbe.ENABLED
            && !qouteall.imm_ptl.core.render.IrisCompatPaste.STAMP_DRAWING) {
            try {
                net.irisshaders.iris.pathways.HandRenderer probeHr =
                    net.irisshaders.iris.pathways.HandRenderer.INSTANCE;
                if (probeHr != null && probeHr.isActive()) {
                    com.warwa.seamlessportals.render.SeamHandTeleportProbe.onHandDraw();
                }
            }
            catch (Throwable ignored) {
                // iris drifted: the probe simply records no draws
            }
        }
        // 26.3: the portal stamp's depth-guard direction is read from the depth function its own draw executes (the
        // convention flipped LEQUAL -> GEQUAL between 26.2 and 26.3, exactly like the hand's; full story on
        // portal_area_sample_ceil.fsh / IrisCompatPaste.onStampDrawSetup). Always on: one glGetInteger per stamp draw.
        if (qouteall.imm_ptl.core.render.IrisCompatPaste.STAMP_DRAWING) {
            try {
                qouteall.imm_ptl.core.render.IrisCompatPaste.onStampDrawSetup(
                    org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_DEPTH_FUNC));
            }
            catch (Throwable ignored) {
                // never let the measurement touch the encoder path; the stamp keeps its previous direction
            }
        }
        if (!HandDrawStateDump.ENABLED) {
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
