package qouteall.imm_ptl.core.mixin.client.render.shader;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * S12-B (render client-mixin half) — IP {@code MixinGameRenderer_Shaders}
 * ({@code IP:mixin/client/render/shader/MixinGameRenderer_Shaders.java}). PORTS-CLEAN (mixin-client.md §8):
 * IP ships this empty (its only handler — a {@code reloadShaders} @RETURN that pushed custom shaders into
 * the {@code shaders} map — is commented out upstream). The 26.2 shader model has no {@code reloadShaders}
 * map to inject into (shaders are {@code RenderPipeline}s loaded via {@code ShaderManager}); the custom
 * ImmPtl shaders are re-expressed as mod {@code RenderPipelines} (the {@code PortalRenderTypes} substrate,
 * S11-B). Empty body preserved for source-shape fidelity. Held/UNREGISTERED until S13.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer_Shaders {

//    @Inject(
//        method = "reloadShaders", at = @At("RETURN")
//    )
//    private void onLoadShaders(ResourceProvider resourceProvider, CallbackInfo ci) {
//        MyRenderHelper.loadShaderSignal.emit(
//            resourceProvider, (shader) -> {
//                shaders.put(shader.getName(), shader);
//            }
//        );
//    }
}
