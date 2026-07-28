package qouteall.imm_ptl.core.compat.mixin.iris;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisHandSeamDepthBracket;

/**
 * IS5-HAND V2 — brackets iris's two first-person hand passes with the crossing-window depth
 * bracket ({@link IrisHandSeamDepthBracket} — the measured mechanism and the fix's derivation
 * live there). Registered via {@code IPCompatMixinPlugin}'s iris arm ("Iris" in the simple name,
 * not "IrisSodium": gate = iris-present). {@code require = 0} with a RUNTIME positive proof
 * instead of a boot crash: the bracket's once-only ARMED line is the landing evidence — a leg
 * without it (lever ON, portals crossed) means an iris-version drift unhooked these injections,
 * and the leg is VOID for adjudication, not a refutation (the D7 loud-not-silent family, traded
 * against crashing every boot over a cosmetic fix).
 *
 * <p>HEAD/RETURN pairs on both methods; {@code end()} is armed-guarded so the four callbacks
 * cannot double-restore. The bracket self-gates on the crossing window, the lever, and
 * {@code PortalRendering.isRendering()} (nested dest-pass hands excluded).
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
public class MixinIrisHandRenderer_SeamDepthBracket {

    @Inject(method = "renderSolid", at = @At("HEAD"), require = 0)
    private void ip_beginSolid(CallbackInfo ci) {
        IrisHandSeamDepthBracket.begin();
    }

    @Inject(method = "renderSolid", at = @At("RETURN"), require = 0)
    private void ip_endSolid(CallbackInfo ci) {
        IrisHandSeamDepthBracket.end();
    }

    @Inject(method = "renderTranslucent", at = @At("HEAD"), require = 0)
    private void ip_beginTranslucent(CallbackInfo ci) {
        IrisHandSeamDepthBracket.begin();
    }

    @Inject(method = "renderTranslucent", at = @At("RETURN"), require = 0)
    private void ip_endTranslucent(CallbackInfo ci) {
        IrisHandSeamDepthBracket.end();
    }
}
