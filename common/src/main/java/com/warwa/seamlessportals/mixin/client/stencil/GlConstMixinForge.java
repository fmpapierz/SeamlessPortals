package com.warwa.seamlessportals.mixin.client.stencil;

import com.mojang.renderpearl.backend.opengl.GlConst;
import com.mojang.renderpearl.api.GpuFormat;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3 FORGE-ONLY ({@code FORGE_ONLY_MIXINS}) loader-shape variant of {@link GlConstMixin} — same three hooks, same
 * bodies, on the method shape MinecraftForge gives {@code GlConst}.
 *
 * <p>Forge 26.3-66.0.2 patches each of the three format translators into a PAIR (javap -s on the Forge jar):
 * {@code toGlInternalId(GpuFormat)I} + {@code toGlInternalId(GpuFormat, boolean)I}, and likewise
 * {@code toGlExternalId} / {@code toGlType}. The 1-arg form is reduced to a delegate — javap -c:
 * {@code aload_0; iconst_0; invokestatic toGlInternalId:(GpuFormat;Z)I; ireturn} — and the 2-arg form holds the real
 * switch (the boolean is Forge's own opt-in depth-STENCIL request: true + a depth format returns
 * GL_DEPTH32F_STENCIL8 / GL_DEPTH_STENCIL / GL_FLOAT_32_UNSIGNED_INT_24_8_REV). Two consequences for the vanilla-shape
 * mixin: its name-only selectors match BOTH overloads (audit: 2 sites each on Forge, 1 on vanilla and NeoForge) and
 * its {@code (GpuFormat, CallbackInfoReturnable)} handlers do not fit the 2-arg target; and hooking only the 1-arg
 * delegate would miss every Forge-side caller that goes straight to the 2-arg form. So on Forge the hooks sit on the
 * 2-arg implementations — which the 1-arg delegates funnel into, so every caller of either form is covered — and
 * {@link GlConstMixin} is skipped there ({@code NON_FORGE_MIXINS}). The override is unconditional on the boolean, as
 * it is unconditional on vanilla: the mod needs DEPTH24_STENCIL8 on every depth texture.
 *
 * GL_DEPTH_COMPONENT32F = 33191
 * GL_DEPTH24_STENCIL8 = 35056
 * GL_DEPTH_COMPONENT = 6402
 * GL_DEPTH_STENCIL = 34041
 * GL_UNSIGNED_INT_24_8 = 34042
 * GL_FLOAT = 5126
 */
@Mixin(GlConst.class)
public abstract class GlConstMixinForge {

    @Unique
    private static boolean seamlessportals$logged = false;

    /**
     * Change DEPTH32 internal format from GL_DEPTH_COMPONENT32F to GL_DEPTH24_STENCIL8
     */
    @Inject(method = "toGlInternalId(Lcom/mojang/renderpearl/api/GpuFormat;Z)I", at = @At("HEAD"), cancellable = true)
    private static void seamlessportals$changeDepthFormat(GpuFormat format, boolean stencil, CallbackInfoReturnable<Integer> cir) {
        if (format == GpuFormat.D32_FLOAT) {
            cir.setReturnValue(35056); // GL_DEPTH24_STENCIL8
            if (!seamlessportals$logged) {
                SeamlessPortalsConstants.LOGGER.info("[SEAMLESS STENCIL] Changed depth format: DEPTH32F -> DEPTH24_STENCIL8");
                seamlessportals$logged = true;
            }
        }
    }

    /**
     * Change DEPTH32 external format from GL_DEPTH_COMPONENT to GL_DEPTH_STENCIL
     */
    @Inject(method = "toGlExternalId(Lcom/mojang/renderpearl/api/GpuFormat;Z)I", at = @At("HEAD"), cancellable = true)
    private static void seamlessportals$changeDepthExternalFormat(GpuFormat format, boolean stencil, CallbackInfoReturnable<Integer> cir) {
        if (format == GpuFormat.D32_FLOAT) {
            cir.setReturnValue(34041); // GL_DEPTH_STENCIL
        }
    }

    /**
     * Change DEPTH32 type from GL_FLOAT to GL_UNSIGNED_INT_24_8
     */
    @Inject(method = "toGlType(Lcom/mojang/renderpearl/api/GpuFormat;Z)I", at = @At("HEAD"), cancellable = true)
    private static void seamlessportals$changeDepthType(GpuFormat format, boolean stencil, CallbackInfoReturnable<Integer> cir) {
        if (format == GpuFormat.D32_FLOAT) {
            cir.setReturnValue(34042); // GL_UNSIGNED_INT_24_8
        }
    }
}
