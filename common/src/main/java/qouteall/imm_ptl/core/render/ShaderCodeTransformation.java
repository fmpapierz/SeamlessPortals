package qouteall.imm_ptl.core.render;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;

import java.util.List;
import java.util.Set;

/**
 * S13 SHELL (entity-portal migration, Slice C — ratified S12-B gate-set amendment,
 * port-note S12B-gate-closure.md §2).
 *
 * IP's {@code ShaderCodeTransformation} is NOT verbatim-portable on 26.2:
 *  - it imports the TARGET-GONE type {@code com.mojang.blaze3d.shaders.CompiledShader}
 *    (the whole {@code CompiledShader}/{@code CompiledShaderProgram} GLSL-compile stack
 *    was removed for the core-profile pipeline model — api-map/mixin-client.md §8,
 *    {@code MixinCompiledShader} row; the GLSL transform re-sites to the
 *    {@code ShaderManager} source layer as part of the FrontClipping shader redesign), and
 *  - {@code init()} loaded the YAML transform config through the cloth-config-shaded
 *    snakeyaml {@code Yaml} loader ({@code me.shedaniel.cloth.clothconfig.shadowed.
 *    org.yaml.snakeyaml.Yaml}), which has no 26.2 build and is not on the F21 stub classpath.
 *
 * Per the ratified amendment this lands as a SHELL so the U11 client-init hub
 * {@code IPModMainClient} (imports it at :25, calls {@code init()} at :77) compiles:
 * the public surface + {@code init()} are kept, while the CompiledShader/Yaml
 * shader-transform internals are commented out and DEFERRED to the FrontClipping
 * shader redesign — the same redesign owner as the dropped {@code MixinCompiledShader} /
 * {@code MixinShaderInstance} / {@code MixinRenderSystem_Clipping} render-shader ducks
 * (its only IP callers of {@code transform(...)} are those held/dropped Iris/Sodium/
 * CompiledShader mixins, which themselves reference the GONE {@code CompiledShader.Type}).
 * Held-inert until the FrontClipping redesign revives or retires it.
 */
public class ShaderCodeTransformation {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static enum ShaderType {
        vs, fs
    }

    // FrontClipping-deferred (S13 SHELL): referenced the GONE
    // com.mojang.blaze3d.shaders.CompiledShader.Type. Verbatim IP body preserved:
    // private static boolean matches(ShaderType me, CompiledShader.Type type) {
    //     if (type == CompiledShader.Type.FRAGMENT) {
    //         return me == ShaderType.fs;
    //     }
    //     else if (type == CompiledShader.Type.VERTEX) {
    //         return me == ShaderType.vs;
    //     }
    //     return false;
    // }

    // snakeyaml does not allow passing generic type
    // so use another wrapper type to make list generic type work
    public static class ConfigsObj {
        public List<Config> configs;
    }

    public static class TransformationEntry {
        public String comment;
        public String pattern;
        public String replacement;
    }

    public static class Config {
        public String comment;
        public ShaderType type;
        public Set<String> affectedShaders;
        public List<TransformationEntry> transformations;
        public boolean debugOutput;
    }

    private static List<Config> configs;

    public static void init() {
        if (IPGlobal.enableClippingMechanism) {
            // FrontClipping-deferred (S13 SHELL): the shader-code YAML transform loader
            // used the cloth-config-shaded snakeyaml Yaml (no 26.2 build / not on the F21
            // stub classpath). The GLSL transform re-sites to the ShaderManager source
            // layer as part of the FrontClipping redesign; kept inert here so init()
            // compiles and IPModMainClient links. Verbatim IP body preserved:
            // Yaml yaml = new Yaml();
            //
            // String yamlStr = McHelper.readTextResource(McHelper.newResourceLocation(
            //     "immersive_portals:shaders/shader_transformation.yaml"
            // ));
            // ConfigsObj configsObj = yaml.loadAs(yamlStr, ConfigsObj.class);
            //
            // configs = configsObj.configs;
            //
            // LOGGER.info("Loaded Shader Code Transformation");
        }
        else {
            LOGGER.info("Shader Transformation Disabled");
        }
    }

    // FrontClipping-deferred (S13 SHELL): referenced the GONE
    // com.mojang.blaze3d.shaders.CompiledShader.Type. Verbatim IP body preserved:
    // public static String transform(CompiledShader.Type type, String shaderId, String inputCode) {
    //     if (configs == null) {
    //         LOGGER.info("Shader Transform Skipping {}", shaderId);
    //         return inputCode;
    //     }
    //
    //     Config selected = getConfig(type, shaderId);
    //
    //     if (selected == null) {
    //         return inputCode;
    //     }
    //
    //     String result = inputCode;
    //
    //     for (TransformationEntry entry : selected.transformations) {
    //         String replacement = String.join("\n", entry.replacement);
    //         result = result.replaceAll(entry.pattern, replacement);
    //     }
    //
    //     if (selected.debugOutput) {
    //         LOGGER.info("Shader Transformed {}\n{}", shaderId, result);
    //     }
    //
    //     return result;
    // }

    // FrontClipping-deferred (S13 SHELL): referenced the GONE
    // com.mojang.blaze3d.shaders.CompiledShader.Type. Verbatim IP body preserved:
    // @Nullable
    // private static Config getConfig(CompiledShader.Type type, String shaderId) {
    //     return configs.stream().filter(
    //         config -> matches(config.type, type) &&
    //             config.affectedShaders.contains(shaderId)
    //     ).findFirst().orElse(null);
    // }

    public static boolean shouldAddUniform(String shaderName) {
        if (configs == null) {
            LOGGER.info("Shader Transform Skipping {} in shouldAddUniform", shaderName);
            return false;
        }

        return configs.stream().anyMatch(config -> config.affectedShaders.contains(shaderName));
    }
}
