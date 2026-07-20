package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * C2-1 D10 — the INTERIM clip-definedness bracket (design {@code migration/C2_DESIGN.md} §3.3.4;
 * retired at C2-2 when the sodium GLSL clip transport lands).
 *
 * <p>WHY: during dest passes the flag-ON clip bridge ({@code qouteall...FrontClipping} feeding the
 * {@code com.warwa} store) raw-enables {@code GL_CLIP_DISTANCE0}, but Sodium 0.9.1's terrain
 * shaders do not write {@code gl_ClipDistance} (our C2-2 source injection is what adds it) — and
 * per the GL spec, drawing with a clip distance ENABLED while the vertex shader does not write it
 * is UNDEFINED behavior (some drivers cull everything). So sodium dest terrain must ship
 * UNCLIPPED-BUT-DEFINED: the enable is suppressed exactly across sodium's terrain draw and
 * restored after.
 *
 * <p>SEAM: {@code ShaderChunkRenderer.begin} RETURN / {@code end} HEAD — javap
 * ({@code sodium-mc26.2-0.9.1-fabric.jar}): {@code protected void begin(TerrainRenderPass,
 * FogParameters, GpuSampler)} / {@code protected void end(TerrainRenderPass)};
 * {@code DefaultChunkRenderer.render} invokespecial-calls begin at offset 6 and end at offsets
 * 195/542 (both the early-exit and normal paths), so the bracket exactly wraps every terrain
 * draw batch. The two injects are strictly nested per pass on the render thread; the
 * {@code @Unique} latch makes the restore self-consistent even so.
 *
 * <p>GL-state discipline (memory {@code 26-2-glstate-and-fbo-invariants}): {@code
 * GL_CLIP_DISTANCE0} is NOT GlStateManager-cached — raw {@code GL11.glEnable/glDisable} is legal
 * and is exactly how {@code com.warwa...FrontClipping.enableGlClipDistance/disableGlClipDistance}
 * toggles it; this bracket mirrors that pattern. The net effect on the store's own cached
 * {@code glClipEnabled} boolean is zero (disable+re-enable restores the exact GL state the
 * store believes is active).
 *
 * <p>GATING: only when the sodium compat is ACTIVE ({@code SodiumInterface.invoker} installed —
 * checked via {@code isSodiumPresent()}) AND a dest pass is rendering
 * ({@code PortalRendering.isRendering()}) AND the store reports the enable actually on. Residual
 * exposure (documented, accepted until C2-2): a MAIN-pass sodium draw under an enabled outer
 * clip would be equally undefined, but the flag-ON outer-clip enable window does not overlap
 * sodium's main terrain draws in this substrate; C2-2's in-shader write retires the whole class.
 *
 * <p>The class name carries {@code Sodium} for the compat plugin's substring gate.
 */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class MixinSodiumShaderChunkRenderer_InterimClipBracket {

    @Unique
    private boolean ip_clipDistance0Suppressed = false;

    @Inject(method = "begin", at = @At("RETURN"), remap = false)
    private void ip_onBeginReturn(
        TerrainRenderPass pass, FogParameters fogParameters, GpuSampler sampler, CallbackInfo ci
    ) {
        // NOTE-7 (verify lens A): defensively clear a latch left stale by a throw that skipped
        // end() — otherwise the NEXT pass's end() would glEnable the clip with no suppression.
        ip_clipDistance0Suppressed = false;
        if (SodiumInterface.invoker.isSodiumPresent()
            && PortalRendering.isRendering()
            && com.warwa.seamlessportals.render.FrontClipping.capture().enabled
        ) {
            // D10: sodium's terrain shader writes no gl_ClipDistance until C2-2 — suppress the
            // enable for this draw batch (undefined behavior otherwise).
            GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
            ip_clipDistance0Suppressed = true;
        }
    }

    @Inject(method = "end", at = @At("HEAD"), remap = false)
    private void ip_onEndHead(TerrainRenderPass pass, CallbackInfo ci) {
        if (ip_clipDistance0Suppressed) {
            ip_clipDistance0Suppressed = false;
            // Restore the exact pre-begin state (the com.warwa store still believes — correctly,
            // after this — that the enable is active for the non-sodium draws that follow).
            GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
        }
    }
}
