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
        SeamHandSubmitTap.beginSolid();
    }

    @Inject(method = "renderSolid", at = @At("RETURN"), require = 0)
    private void ip_tapEndSolid(CallbackInfo ci) {
        SeamHandSubmitTap.endSolid();
    }

    @Inject(method = "renderTranslucent", at = @At("HEAD"), require = 0)
    private void ip_tapBeginTranslucent(CallbackInfo ci) {
        SeamHandSubmitTap.beginTranslucent();
    }

    @Inject(method = "renderTranslucent", at = @At("RETURN"), require = 0)
    private void ip_tapEndTranslucent(CallbackInfo ci) {
        SeamHandSubmitTap.endTranslucent();
    }

    @Inject(method = "canRender", at = @At("RETURN"), require = 0)
    private void ip_tapCanRender(
        Camera camera, GameRenderer gameRenderer, CallbackInfoReturnable<Boolean> cir
    ) {
        SeamHandSubmitTap.onCanRender(camera, cir.getReturnValueZ());
    }

    @Inject(method = "setupGlState", at = @At("HEAD"), require = 0)
    private void ip_tapBodyEntered(CallbackInfoReturnable<?> cir) {
        SeamHandSubmitTap.onBodyEntered();
    }
}
