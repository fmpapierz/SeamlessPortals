package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.MyRenderHelper;

/**
 * S12-B (render client-mixin half) — IP {@code MixinRenderSystem_Fog}
 * ({@code IP:mixin/client/render/MixinRenderSystem_Fog.java}), 26.2-RETARGETED (mixin-client.md §7
 * NEEDS-RETARGET). <b>File keeps IP's name for diff-fidelity; the {@code @Mixin} target moved from
 * {@code RenderSystem} to {@code FogRenderer}.</b>
 *
 * <p><b>Why the target moved.</b> IP scaled the shader fog distance by
 * {@code MyRenderHelper.transformFogDistance} via {@code @ModifyVariable} on
 * {@code RenderSystem.setShaderFogStart/setShaderFogEnd(F)}. On 26.2 those setters are GONE — fog is a UBO
 * slice ({@code RenderSystem.setShaderFog(GpuBufferSlice)}); the distances live in
 * {@code FogData} ({@code fog/FogData.java}: {@code environmentalStart/End}, {@code renderDistanceStart/End},
 * ...) produced by {@code FogRenderer.setupFog} and serialized by {@code FogRenderer.updateBuffer(FogData)}
 * ({@code 26.2:fog/FogRenderer.java:188}). The distance transform therefore re-sites onto
 * {@code updateBuffer} HEAD, scaling the {@code FogData} near/far distances before serialization — the
 * faithful equivalent of IP's start/end scale (api-map/mixin-client.md §7; render-sub G-fog).
 *
 * <p><b>Portal-distance scaling for the R9 per-dim fog design finalizes at S13</b> (the exact set of
 * distances / dest-pass gating is R9/CUTOVER_SPEC §3). Held/UNREGISTERED until S13.
 */
@Mixin(FogRenderer.class)
public class MixinRenderSystem_Fog {
    @Inject(
        method = "updateBuffer(Lnet/minecraft/client/renderer/fog/FogData;)V",
        at = @At("HEAD")
    )
    private void onUpdateFogBuffer(FogData fogData, CallbackInfo ci) {
        fogData.renderDistanceStart = MyRenderHelper.transformFogDistance(fogData.renderDistanceStart);
        fogData.renderDistanceEnd = MyRenderHelper.transformFogDistance(fogData.renderDistanceEnd);
        fogData.environmentalStart = MyRenderHelper.transformFogDistance(fogData.environmentalStart);
        fogData.environmentalEnd = MyRenderHelper.transformFogDistance(fogData.environmentalEnd);
    }
}
