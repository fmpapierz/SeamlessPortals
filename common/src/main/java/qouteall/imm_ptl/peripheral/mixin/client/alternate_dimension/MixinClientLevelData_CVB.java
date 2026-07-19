package qouteall.imm_ptl.peripheral.mixin.client.alternate_dimension;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.peripheral.alternate_dimension.AlternateDimensions;

// S19-D: 1:1 port of IP's MixinClientLevelData_CVB (alternate-dimension client surface).
// For alt dims (skyland/void/chaos), force the horizon height very low so the sky never
// treats the camera as "below the horizon" -> no void darkness / sky-darkness onset when
// standing on or looking below the floating island.
//
// 26.2 target is SAME as IP's (API-map "SAME"): ClientLevel$ClientLevelData.getHorizonHeight
// (LevelHeightAccessor)D exists verbatim (1.21.3's getSkyDarknessHeight was renamed to
// getHorizonHeight upstream; IP's own mixin already targets getHorizonHeight). Vanilla body is
// `return this.isFlat ? level.getMinY() : 63.0;`. The consumer is SkyRenderer.java:295 —
// `player.getEyePosition(dt).y - level.getLevelData().getHorizonHeight(level) < 0.0` decides the
// void-darkness onset; returning -10000.0 makes that difference always >= 0 (never "below").
@Mixin(ClientLevel.ClientLevelData.class)
public class MixinClientLevelData_CVB {
    @Inject(
        method = "getHorizonHeight",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onGetSkyDarknessHeight(CallbackInfoReturnable<Double> cir) {
        ClientLevel world = Minecraft.getInstance().level;
        assert world != null;
        boolean isAlternateDimension =
            AlternateDimensions.isAlternateDimension(world);

        if (isAlternateDimension) {
            cir.setReturnValue(-10000.0);
        }
    }
}
