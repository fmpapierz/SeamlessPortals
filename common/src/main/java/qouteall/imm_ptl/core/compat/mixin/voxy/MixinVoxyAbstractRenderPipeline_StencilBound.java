package qouteall.imm_ptl.core.compat.mixin.voxy;

import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.voxy_compatibility.VoxyInterface;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.renderer.RendererUsingStencil;

/**
 * Voxy compat, tier 2 — the aperture bound on Voxy's final composite (design
 * {@code migration/VOXY_COMPAT.md}).
 *
 * <p>Voxy draws its LODs into its own framebuffer, then {@code runPipeline} binds a scratch
 * framebuffer to the TARGET's depth and colour textures and {@code finish} →
 * {@code transformBlitDepth} composites the LODs into it with a depth test: wherever the target's
 * depth is still far (sky), LOD colour and depth land. In a portal pass the target is the main
 * render target and that composite would land on EVERY sky pixel of the screen — the dest's LODs
 * painted over the main world's sky outside the window. Two changes bound it to the aperture:
 * <ol>
 *   <li>the scratch framebuffer binds the target's depth texture as
 *       {@code GL_DEPTH_STENCIL_ATTACHMENT} when the texture carries a stencil component (this
 *       mod makes the main target's depth {@code DEPTH24_STENCIL8} — {@code GlConstMixin}); a plain
 *       {@code GL_DEPTH_ATTACHMENT} bind leaves the stencil out of the framebuffer, and the stencil
 *       test is then silently ignored. Done for every pass, not only portal passes, so the
 *       framebuffer's attachments never alternate (an attachment left pointing at a deleted
 *       texture after a resize would make it incomplete);</li>
 *   <li>{@code transformBlitDepth} opens with {@code glDisable(GL_STENCIL_TEST)}; in a portal pass
 *       that call instead ENABLES the test with the pass's limitation ({@code EQUAL layer, KEEP}),
 *       so the composite only writes inside this portal's aperture.</li>
 * </ol>
 * The main pass is unchanged (the first call still disables the test there).
 *
 * <p>Bound to voxy-0.2.19-beta ({@code AbstractRenderPipeline.runPipeline}'s first
 * {@code GlFramebuffer.bind(II)} call, the first {@code GL11C.glDisable(I)} in the static
 * {@code transformBlitDepth}); the class name carries {@code Voxy} for the compat plugin's gate.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.AbstractRenderPipeline", remap = false)
public abstract class MixinVoxyAbstractRenderPipeline_StencilBound {

    private static final int GL_TEXTURE_INTERNAL_FORMAT = 0x1003;
    private static final int GL_DEPTH24_STENCIL8 = 0x88F0;
    private static final int GL_DEPTH32F_STENCIL8 = 0x8CAD;

    /** Texture id → carries a stencil component; the main target's texture rarely changes. */
    @Unique
    private static final Int2BooleanOpenHashMap ip_depthStencilById = new Int2BooleanOpenHashMap();

    @Unique
    private static boolean ip_isDepthStencil(int texture) {
        if (ip_depthStencilById.containsKey(texture)) {
            return ip_depthStencilById.get(texture);
        }
        int format = GL45C.glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_INTERNAL_FORMAT);
        boolean depthStencil = format == GL_DEPTH24_STENCIL8 || format == GL_DEPTH32F_STENCIL8;
        if (ip_depthStencilById.size() > 64) {
            ip_depthStencilById.clear();
        }
        ip_depthStencilById.put(texture, depthStencil);
        return depthStencil;
    }

    @Redirect(
        method = "runPipeline",
        at = @At(
            value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/gl/GlFramebuffer;bind(II)Lme/cortex/voxy/client/core/gl/GlFramebuffer;",
            ordinal = 0
        )
    )
    private GlFramebuffer ip_bindDepthWithStencil(GlFramebuffer framebuffer, int attachment, int texture) {
        if (attachment == GL30.GL_DEPTH_ATTACHMENT && ip_isDepthStencil(texture)) {
            return framebuffer.bind(GL30.GL_DEPTH_STENCIL_ATTACHMENT, texture);
        }
        return framebuffer.bind(attachment, texture);
    }

    @Redirect(
        method = "transformBlitDepth",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11C;glDisable(I)V", ordinal = 0)
    )
    private static void ip_boundCompositeToAperture(int cap) {
        if (cap == GL11.GL_STENCIL_TEST && VoxyInterface.isStencilBoundedLodPass()) {
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            RendererUsingStencil.setStencilLimitation(PortalRendering.getPortalLayer());
            return;
        }
        GL11C.glDisable(cap);
    }
}
