package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.ShaderCodeTransformation;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;

import com.mojang.blaze3d.shaders.ShaderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercept {@code ShaderManager$CompilationCache.getShaderSource} on
 * RETURN and, for vanilla-namespaced vertex shaders, rewrite the source
 * via {@link ShaderCodeTransformation} to add a {@code gl_ClipDistance[0]}
 * write driven by a {@code vec4 seamlessportals_ClipPlane} uniform.
 *
 * <p>This is the injection point the IP team uses in 1.19 (via
 * {@code ShaderCodeTransformation} hooked at the equivalent cache entry).
 * MC 26.1.2 changed paths but the idea is identical — we rewrite the
 * shader source exactly once per (id, type, defines) right before it hits
 * the {@code GlDevice.compileShader} path.
 */
@Mixin(targets = "net/minecraft/client/renderer/ShaderManager$CompilationCache")
public abstract class ShaderManagerCompilationCacheMixin {

    @Inject(
        method = "getShaderSource(Lnet/minecraft/resources/Identifier;Lcom/mojang/blaze3d/shaders/ShaderType;)Ljava/lang/String;",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private void seamlessportals$transformShaderSource(
            Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
        String source = cir.getReturnValue();
        if (source == null) return;
        // Only touch vanilla-namespaced shaders. Mod shaders (Iris,
        // Sodium, etc.) manage their own clip logic and our transform
        // could collide with theirs.
        if (!"minecraft".equals(id.getNamespace())) return;

        // TINT (SEAM_BAND_HANDOFF §4.1, diagnostic): while -PseamPainterTint is armed, every
        // vanilla FRAGMENT shader gains the debug-tint wrapper. Lever off ⇒ this branch is one
        // static read and the fragment sources stay byte-identical.
        if (type == ShaderType.FRAGMENT) {
            if (!com.warwa.seamlessportals.render.SeamTint.ENABLED) return;
            String tinted = ShaderCodeTransformation.transformFragment(source);
            if (tinted != source) {
                com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                    "[SEAM TINT] fragment shader patched: {}", id);
                cir.setReturnValue(tinted);
            } else {
                com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                    "[SEAM TINT] fragment shader skipped: {} (no match)", id);
            }
            return;
        }

        if (type != ShaderType.VERTEX) return;

        String transformed = ShaderCodeTransformation.transformVertex(source);
        if (transformed != source) {
            com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS SLICE] shader patched: {}", id);
            // One-shot dump of the terrain shader so we can eyeball the
            // injected GLSL. Drivers that silently-drop gl_ClipDistance
            // writes usually do so because the redeclaration is at the
            // wrong place (after #version, before any other decl, etc.).
            if ("terrain".equals(id.getPath()) || id.getPath().endsWith("/terrain")) {
                com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS SLICE] TRANSFORMED terrain source:\n{}", transformed);
            }
            cir.setReturnValue(transformed);
        } else {
            com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS SLICE] shader skipped: {} (no match)", id);
        }
    }
}
