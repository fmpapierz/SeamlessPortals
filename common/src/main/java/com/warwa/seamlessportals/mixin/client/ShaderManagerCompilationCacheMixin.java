package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.ShaderCodeTransformation;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;

import com.mojang.renderpearl.api.pipeline.ShaderType;
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
// 26.3: THE SEAM MOVED. `ShaderManager$CompilationCache` is gone (mc262-ref ShaderManager.java:206-251 -> mc263-ref: no such
// class; sources are served by ShaderManager.Configs.getShader :301-304). Hooking that successor is NOT a port: 26.3 compiles
// what it returns with shaderc to SPIR-V under Vulkan rules (GlslCompiler.java:79,121), which rejects the loose
// `uniform vec4 seamlessportals_ClipPlane;` this patch adds, and PipelineBuilder.java:276-278 rejects any shader uniform the
// RenderPipeline does not declare — a patched source would fail every vanilla pipeline at load. What "right before it hits the
// GlDevice.compileShader path" (javadoc above) means on 26.3 is GlPipelineRecompiler.compileShader(String name, ShaderType,
// String source) (backend/opengl/GlPipelineRecompiler.java:188-191): the one place GLSL TEXT is handed to glShaderSource — the
// GLSL 330 spirv-cross regenerates from the SPIR-V (:48-109). javap 26.3: `private GlShaderModule compileShader(String,
// ShaderType, String)`. `name` is the shader Identifier's toString() (PipelineBuilder.java:85 `shader.getValue().toString()`),
// so the same namespace/path tests run on the parsed id. GL-backend class => the patch is GL-only by construction.
// The handler body below is unchanged except for how (id, source) arrive and how the result is returned.
@Mixin(targets = "com/mojang/renderpearl/backend/opengl/GlPipelineRecompiler")
public abstract class ShaderManagerCompilationCacheMixin {

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
        method = "compileShader(Ljava/lang/String;Lcom/mojang/renderpearl/api/pipeline/ShaderType;Ljava/lang/String;)Lcom/mojang/renderpearl/backend/opengl/GlShaderModule;",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 1, // the 2nd String argument = `source` (ordinal 0 is `name`)
        require = 1
    )
    private String seamlessportals$transformShaderSource(
            String source, String name, ShaderType type, String sourceArg) {
        if (source == null) return source;
        Identifier id = Identifier.tryParse(name);
        if (id == null) return source;
        // Only touch vanilla-namespaced shaders. Mod shaders (Iris,
        // Sodium, etc.) manage their own clip logic and our transform
        // could collide with theirs.
        if (!"minecraft".equals(id.getNamespace())) return source;

        // TINT (SEAM_BAND_HANDOFF §4.1, diagnostic): while -PseamPainterTint is armed, every
        // vanilla FRAGMENT shader gains the debug-tint wrapper. Lever off ⇒ this branch is one
        // static read and the fragment sources stay byte-identical.
        if (type == ShaderType.FRAGMENT) {
            if (!com.warwa.seamlessportals.render.SeamTint.ENABLED) return source;
            String tinted = ShaderCodeTransformation.transformFragment(source);
            if (tinted != source) {
                com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                    "[SEAM TINT] fragment shader patched: {}", id);
                return tinted; // 26.3: @ModifyVariable returns the new value (was cir.setReturnValue)
            } else {
                com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                    "[SEAM TINT] fragment shader skipped: {} (no match)", id);
            }
            return source;
        }

        if (type != ShaderType.VERTEX) return source;

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
            return transformed; // 26.3: @ModifyVariable returns the new value (was cir.setReturnValue)
        } else {
            com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS SLICE] shader skipped: {} (no match)", id);
        }
        return source;
    }
}
