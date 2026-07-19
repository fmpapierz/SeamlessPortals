package qouteall.dimlib.mixin.common;

import com.mojang.serialization.Lifecycle;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.dimlib.DimensionImpl;

// S19-D: port of DimLib's MixinWorldDimensions (experimental-warning suppression chain, first of
// the two ported here; MixinBackupConfirmScreen's optional disable-warning button is skipped).
// This is the WORKING half of the suppression: PeripheralModMain calls
// DimensionAPI.suppressExperimentalWarningForNamespace("immersive_portals") (flag-ON), which adds
// that namespace to STABLE_NAMESPACES; isVanillaLike then returns true for immersive_portals alt
// dims so they do not mark the world's dimension registry lifecycle experimental.
//
// 26.2-forced deltas vs DimLib source:
//  - ResourceKey.location() -> ResourceKey.identifier() (26.2 rename; ResourceLocation ->
//    Identifier). Identifier.getNamespace() is unchanged.
// 26.2 targets verified (mc262-ref WorldDimensions.java): private static
// isVanillaLike(ResourceKey<LevelStem>, LevelStem)Z exists (RETURN-cancellable), and bake(...)
// contains exactly one Lifecycle.experimental() invocation (the initialStability ternary), which
// the redirect pins to stable(). remap=false (Lifecycle is a mojang-serialization type).
@Mixin(WorldDimensions.class)
public class MixinWorldDimensions {
    // hack lifecycle
    @Inject(
        method = "isVanillaLike", at = @At("RETURN"), cancellable = true
    )
    private static void onIsVanillaLike(
        ResourceKey<LevelStem> resourceKey, LevelStem levelStem, CallbackInfoReturnable<Boolean> cir
    ) {
        String namespace = resourceKey.identifier().getNamespace();
        if (DimensionImpl.STABLE_NAMESPACES.contains(namespace)) {
            cir.setReturnValue(true);
        }
    }

    // hack lifecycle
    @Redirect(
        method = "bake",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/serialization/Lifecycle;experimental()Lcom/mojang/serialization/Lifecycle;",
            remap = false
        )
    )
    private Lifecycle redirectLifecycle() {
        return Lifecycle.stable();
    }
}
