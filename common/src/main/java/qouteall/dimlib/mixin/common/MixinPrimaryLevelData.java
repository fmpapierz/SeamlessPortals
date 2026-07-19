package qouteall.dimlib.mixin.common;

import com.mojang.serialization.Lifecycle;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.dimlib.DimensionImpl;

// S19-D: port of DimLib's MixinPrimaryLevelData (second of the two suppression mixins). Disables
// the "Worlds using Experimental Settings are not supported" warning from the root when the
// suppression flag is set.
//
// 26.2-forced deltas vs DimLib source:
//  - DimLib gated on `DimLibConfig.suppressExperimentalWarning || DimensionImpl.suppressExperimentalWarning`.
//    DimLibConfig is a MidnightConfig user setting toggled by the client MixinBackupConfirmScreen
//    button, which is optional-skip in this port (no MidnightConfig dependency), so that disjunct
//    is dropped. The remaining DimensionImpl.suppressExperimentalWarning flag (set by
//    DimensionAPI.suppressExperimentalWarning()) is read here, faithful to DimLib.
//  - Faithful behavior: IP sets STABLE_NAMESPACES (via suppressExperimentalWarningForNamespace)
//    but never calls the global suppressExperimentalWarning(), so this flag stays default-false
//    and this mixin is inert under faithful IP usage — the working suppression is
//    MixinWorldDimensions. Ported for parity + so the API's suppressExperimentalWarning() path
//    remains functional.
// 26.2 target verified (mc262-ref PrimaryLevelData.java:283): public
// worldGenSettingsLifecycle()Lcom/mojang/serialization/Lifecycle; exists (HEAD-cancellable).
@Mixin(PrimaryLevelData.class)
public class MixinPrimaryLevelData {
    // disable the warning from the root
    @Inject(method = "worldGenSettingsLifecycle", at = @At("HEAD"), cancellable = true)
    private void onWorldGenSettingsLifecycle(CallbackInfoReturnable<Lifecycle> cir) {
        if (DimensionImpl.suppressExperimentalWarning) {
            cir.setReturnValue(Lifecycle.stable());
        }
    }
}
