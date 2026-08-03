package qouteall.imm_ptl.core.compat.mixin.iris;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.warwa.seamlessportals.render.SeamHandSubmitTap;

/**
 * IS5-HAND-TAP — the canRender-vs-submit tap on iris's HandRenderer (handoff §00c next split;
 * the tap's design + condition inventory live in {@link SeamHandSubmitTap}). Targets
 * bytecode-verified members of iris 1.11.2+26.2 (javap 2026-07-28):
 * {@code renderSolid}/{@code renderTranslucent} (public, 6-arg), the private
 * {@code canRender(Camera, GameRenderer)} gate, and the private {@code setupGlState} whose
 * HEAD is reached only past that pass's OUTER GATES — per-pass, bytecode-verified: solid =
 * canRender + iris$isAnyHandSolid + isPackInUseQuick; translucent = canRender +
 * isPackInUseQuick (NO translucent held-item gate in this build). Same registration arm and
 * {@code require = 0} loud-not-silent contract as the SeamDepthBracket mixin: the tap's
 * once-only ARMED line is the landing proof — a leg without it (lever ON) is VOID, not a
 * refutation. All callbacks are DEFAULT-OFF no-ops (the tap early-returns on its lever) and
 * can never throw into iris.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
public class MixinIrisHandRenderer_SubmitTap {

    @Inject(method = "renderSolid", at = @At("HEAD"), require = 0)
    private void ip_tapBeginSolid(CallbackInfo ci) {
        // Probes FIRST (pristine pre-pass state; the locator's full-frame pre snapshot), then
        // the IS5-HAND-FUNC fix (forces the declared GEQUAL for the pass — the measured leak).
        SeamHandSubmitTap.beginSolid();
        com.warwa.seamlessportals.render.SeamHandLocator.preSolid();
        qouteall.imm_ptl.core.compat.iris_compatibility.SeamHandDepthFuncFix.begin();
    }

    @Inject(method = "renderSolid", at = @At("RETURN"), require = 0)
    private void ip_tapEndSolid(CallbackInfo ci) {
        SeamHandSubmitTap.endSolid();
        com.warwa.seamlessportals.render.SeamHandLocator.postSolid();
        qouteall.imm_ptl.core.compat.iris_compatibility.SeamHandDepthFuncFix.end();
    }

    @Inject(method = "renderTranslucent", at = @At("HEAD"), require = 0)
    private void ip_tapBeginTranslucent(CallbackInfo ci) {
        SeamHandSubmitTap.beginTranslucent();
        qouteall.imm_ptl.core.compat.iris_compatibility.SeamHandDepthFuncFix.begin();
    }

    @Inject(method = "renderTranslucent", at = @At("RETURN"), require = 0)
    private void ip_tapEndTranslucent(CallbackInfo ci) {
        SeamHandSubmitTap.endTranslucent();
        qouteall.imm_ptl.core.compat.iris_compatibility.SeamHandDepthFuncFix.end();
    }

    @Inject(method = "canRender", at = @At("RETURN"), require = 0)
    private void ip_tapCanRender(
        Camera camera, GameRenderer gameRenderer, CallbackInfoReturnable<Boolean> cir
    ) {
        SeamHandSubmitTap.onCanRender(camera, cir.getReturnValueZ());
    }

    // RETURN (not HEAD): setupGlState has no early return (bytecode-verified), so body-entry
    // proof is preserved, and RETURN exposes BOTH the inputs (the shared CameraRenderState
    // whose hudFov/depthFar build the hand PROJECTION — the 26.2 shared-state hazard — and the
    // modelMatrix arg) and the OUTPUT pose the hands are submitted under.
    @Inject(method = "setupGlState", at = @At("RETURN"), require = 0)
    private void ip_tapBodyEntered(
        net.minecraft.client.renderer.GameRenderer gameRenderer,
        net.minecraft.client.renderer.state.level.CameraRenderState cameraState,
        org.joml.Matrix4fc modelMatrix, float tickDelta,
        CallbackInfoReturnable<com.mojang.blaze3d.vertex.PoseStack> cir
    ) {
        SeamHandSubmitTap.onBodyEntered(cameraState, modelMatrix, cir.getReturnValue());
    }
}
