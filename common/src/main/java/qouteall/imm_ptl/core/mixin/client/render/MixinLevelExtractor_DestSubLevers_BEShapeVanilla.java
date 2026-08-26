package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.SecondaryWorldRenderCore;

/**
 * NF-PARITY W3/B1 (2026-08-25): the vanilla/Fabric-shape half of the S14.40
 * {@code extractVisibleBlockEntities} dest-pass skip lever (see
 * {@link MixinLevelExtractor_DestSubLevers}, where it used to live).
 *
 * <p>Vanilla 26.2's {@code LevelExtractor.extract} calls the 3-arg
 * {@code extractVisibleBlockEntities(Camera, float, LevelRenderState)} (mc262
 * LevelExtractor.java:175); NeoForge patched that call to a 4-arg overload with a trailing
 * {@code @Nullable Frustum} (NF LevelExtractor.java:176). One descriptor cannot match both,
 * so the lever exists in two shape variants and {@code SeamlessMixinConfigPlugin} applies
 * exactly one per loader ({@code NON_NEOFORGE_MIXINS} skips this one when NeoForge's FML is
 * present). Each variant keeps the config's {@code defaultRequire = 1} strictness — a
 * zero-match on the selected variant is a hard boot failure, never a silent no-op.
 */
@Mixin(LevelExtractor.class)
public class MixinLevelExtractor_DestSubLevers_BEShapeVanilla {

    @WrapOperation(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractVisibleBlockEntities"
                + "(Lnet/minecraft/client/Camera;F"
                + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"
        )
    )
    private void ip_leverExtractBlockEntities(
        LevelExtractor instance, Camera camera, float deltaPartialTick, LevelRenderState output,
        Operation<Void> original
    ) {
        if (SecondaryWorldRenderCore.isDestExtracting && IPGlobal.debugSkipExtractBlockEntities) {
            return;
        }
        original.call(instance, camera, deltaPartialTick, output);
    }
}
