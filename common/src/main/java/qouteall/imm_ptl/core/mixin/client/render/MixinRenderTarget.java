package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.ducks.IEFrameBuffer;

/**
 * S13-F (crash-2 duck-census sibling) — the {@link IEFrameBuffer} implementor on 26.2's
 * {@code com.mojang.blaze3d.pipeline.RenderTarget}.
 *
 * <p>IP implemented {@link IEFrameBuffer} on {@code framebuffer.MixinMainTarget}/{@code MixinRenderTarget}
 * by {@code @Shadow}ing a {@code isStencilBufferEnabled} field + a {@code resize}-driven reload; those mixins
 * were RETIRED on 26.2 (S13B §3: the per-target stencil-enabled FIELD model is GONE — 26.2's
 * {@code RenderTarget}/GPU abstraction carries no stencil attachment, stencil is raw-GL only). So the duck
 * had a reachable consumer ({@code IPPortingLibCompat.getIsStencilEnabled/setIsStencilEnabled}, driven
 * flag-ON by {@code RendererUsingStencil.prepareRendering} / {@code RendererUsingFrameBuffer.finishRendering}
 * the moment a portal renders) but NO registered implementor → a {@code ClassCastException} on
 * {@code (IEFrameBuffer) renderTarget}. This lands the implementor so the (verbatim-IP) enable-dance runs.
 *
 * <p><b>26.2 re-expression (the documented disposition, not a 1:1 field port).</b> On 26.2 the mod substrate
 * ({@code com.warwa …stencil.RenderTargetMixin} on {@code FrameBufferCache} + {@code GlConstMixin}) makes the
 * main FBO stencil-capable ({@code DEPTH24_STENCIL8}) UNCONDITIONALLY, so per-target stencil enabling is a
 * no-op (as {@code RendererUsingStencil.prepareRendering:147-151} + {@code SecondaryFrameBuffer:27-31} state):
 * <ul>
 *   <li>{@code ip_getIsStencilBufferEnabled()} → {@code true} — the substrate guarantees a stencil buffer, so
 *       {@code IPPortingLibCompat}'s "if not enabled, enable+reload" body is correctly skipped.</li>
 *   <li>{@code ip_setIsStencilBufferEnabledAndReload(cond)} → no-op — there is no per-target stencil field to
 *       toggle and no {@code worldRenderer.reload()} equivalent (the buffer exists regardless).</li>
 * </ul>
 * Targeting the abstract base {@code RenderTarget} propagates the interface to every subclass
 * ({@code MainTarget}/{@code TextureTarget}), covering the main + secondary FBOs the duck is cast on.
 *
 * <p>A pure interface-implementation mixin (no {@code @Inject}/{@code @Redirect}, so no injection point to
 * collide) — the block-era {@code RenderTargetMixin} targets {@code FrameBufferCache}, a different class.
 * Registered flag-ON via {@code seamlessportals-ip-client.mixins.json}; skipped flag-OFF by the plugin.
 */
@Mixin(RenderTarget.class)
public class MixinRenderTarget implements IEFrameBuffer {

    @Override
    public boolean ip_getIsStencilBufferEnabled() {
        // 26.2: the mod substrate makes every render FBO stencil-capable unconditionally.
        return true;
    }

    @Override
    public void ip_setIsStencilBufferEnabledAndReload(boolean cond) {
        // 26.2: no per-target stencil-enabled field and no reload — the substrate provides the buffer.
    }
}
