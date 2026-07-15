package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.optimization.GLResourceCache;

/**
 * S12-B (render client-mixin half) — IP {@code MixinGlStateManager}
 * ({@code IP:mixin/client/render/MixinGlStateManager.java}), 26.2-RETARGETED (mixin-client.md §7
 * NEEDS-RETARGET).
 *
 * <p><b>Package retarget only.</b> {@code GlStateManager} moved to {@code com.mojang.blaze3d.opengl}
 * (the GL backend); {@code _enableCull}/{@code _disableCull}/{@code _glGenBuffers}/{@code _glGenVertexArrays}
 * all survive. The mod already mixes into this class on 26.2 for the stencil substrate
 * ({@code com.warwa...stencil.GlStateManagerMixin}). <b>GL-backend-only</b> (mixin-client §0.8): under the
 * Vulkan backend these handlers never fire — an accepted known limitation while GL is default.
 *
 * <p><b>Semantic caveat (unchanged from IP intent).</b> Cull is re-asserted from each pipeline's
 * {@code withCull} state at every pass via {@code GlCommandEncoder.applyPipelineState}, which funnels
 * through {@code _enableCull} — so the force-disable-cull flag ({@code RenderStates.shouldForceDisableCull},
 * for odd-mirror-count portal passes) still works because the mixin flips {@code _enableCull} at that funnel.
 * Held/UNREGISTERED until S13.
 */
@Mixin(value = GlStateManager.class, remap = false)
public abstract class MixinGlStateManager {

    @Shadow
    public static void _disableCull() {
        throw new RuntimeException();
    }

    @Inject(
        method = "Lcom/mojang/blaze3d/opengl/GlStateManager;_enableCull()V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onEnableCull(CallbackInfo ci) {
        if (RenderStates.shouldForceDisableCull) {
            _disableCull();
            ci.cancel();
        }
    }

    @Inject(
        method = "Lcom/mojang/blaze3d/opengl/GlStateManager;_glGenBuffers()I",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onGenBuffers(CallbackInfoReturnable<Integer> cir) {
        if (IPGlobal.cacheGlBuffer) {
            cir.setReturnValue(GLResourceCache.bufferCache.getNewResourceId());
            cir.cancel();
        }
    }

    @Inject(
        method = "Lcom/mojang/blaze3d/opengl/GlStateManager;_glGenVertexArrays()I",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onGenVertexArrays(CallbackInfoReturnable<Integer> cir) {
        if (IPGlobal.cacheGlBuffer) {
            cir.setReturnValue(GLResourceCache.vertexArrayCache.getNewResourceId());
            cir.cancel();
        }
    }
}
