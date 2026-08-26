package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.SecondaryWorldRenderCore;

/**
 * NF-PARITY W3/B1 (2026-08-25): the NeoForge-shape half of the S14.40
 * {@code extractVisibleBlockEntities} dest-pass skip lever — see
 * {@link MixinLevelExtractor_DestSubLevers_BEShapeVanilla} for the split rationale.
 *
 * <p>NeoForge's {@code LevelExtractor.extract} calls the patched 4-arg
 * {@code extractVisibleBlockEntities(Camera, float, LevelRenderState, @Nullable Frustum)}
 * (NF LevelExtractor.java:176/:281). {@code SeamlessMixinConfigPlugin} applies this variant
 * only when NeoForge's FML is present ({@code NEOFORGE_ONLY_MIXINS}).
 */
@Mixin(LevelExtractor.class)
public class MixinLevelExtractor_DestSubLevers_BEShapeNeoForge {

    @WrapOperation(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractVisibleBlockEntities"
                + "(Lnet/minecraft/client/Camera;F"
                + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
                + "Lnet/minecraft/client/renderer/culling/Frustum;)V"
        )
    )
    private void ip_leverExtractBlockEntities(
        LevelExtractor instance, Camera camera, float deltaPartialTick, LevelRenderState output,
        @Nullable Frustum cullFrustum, Operation<Void> original
    ) {
        if (SecondaryWorldRenderCore.isDestExtracting && IPGlobal.debugSkipExtractBlockEntities) {
            return;
        }
        original.call(instance, camera, deltaPartialTick, output, cullFrustum);
    }
}
