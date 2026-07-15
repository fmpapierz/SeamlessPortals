package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.mixin.client.render.optimization.MixinSectionBufferBuilderPack;

/**
 * S12-B (render client-mixin half) — IP {@code MixinSectionRenderDispatcher}
 * ({@code IP:mixin/client/render/MixinSectionRenderDispatcher.java}), 26.2-RETARGETED (mixin-client.md §7
 * NEEDS-RETARGET).
 *
 * <p><b>The {@code <init>} redirect survives; only the ctor descriptor changed.</b> 26.2's ctor is
 * {@code (TracingExecutor, RenderBuffers, SectionCompiler, Consumer<RenderSection>)}
 * ({@code SectionRenderDispatcher.java:57-62}) but the redirect site is intact:
 * {@code this.bufferPool = renderBuffers.sectionBufferPool()} ({@code :65}); the pool API is
 * {@code SectionBufferBuilderPool.allocate(int)}. Fresh-pool-per-secondary-dispatcher rationale unchanged
 * (the pool is still acquire/release, not thread-bound — a per-dimension dispatcher must not share the main
 * one). Held/UNREGISTERED until S13.
 */
@Mixin(SectionRenderDispatcher.class)
public class MixinSectionRenderDispatcher {
    @Shadow
    @Final
    private SectionBufferBuilderPool bufferPool;

    /**
     * When loading multiple client dimensions at the same time,
     * there will be multiple {@link SectionRenderDispatcher} instances.
     * They cannot share one {@link SectionBufferBuilderPool} instance because
     * that type is not thread-safe.
     * In {@link MixinSectionBufferBuilderPack} it reduces the initial size of the buffer
     * to reduce memory overhead.
     * This is not enabled in Sodium as Sodium does not use this.
     */
    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderBuffers;sectionBufferPool()Lnet/minecraft/client/renderer/SectionBufferBuilderPool;"
        )
    )
    private SectionBufferBuilderPool redirectSectionBufferBuilderPool(RenderBuffers instance) {
        if (ClientWorldLoader.getIsCreatingClientWorld()
            && !SodiumInterface.invoker.isSodiumPresent()
        ) {
            int processors = Runtime.getRuntime().availableProcessors();
            return SectionBufferBuilderPool.allocate(processors);
        }

        return instance.sectionBufferPool();
    }
}
