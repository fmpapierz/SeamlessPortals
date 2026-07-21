package qouteall.imm_ptl.core.compat.mixin.iris;

import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.render.ShaderCodeTransformation;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * IS3 §4.2 — the shaders-ON GLSL terrain-clip injector (port-note {@code IS-iris-shaders-on.md}
 * §4.2). This is the FIRST live iris-targeting {@code @Mixin} (D9 amendment, §4.6): it populates
 * the previously-empty Iris arm of {@link qouteall.imm_ptl.core.compat.IPCompatMixinPlugin}.
 *
 * <h2>What it does</h2>
 * Under an active iris shaderpack, sodium terrain shaders are transformed by iris's
 * {@link TransformPatcher} (Patch.SODIUM). Those transformed programs write no
 * {@code gl_ClipDistance} — so the shaders-OFF vanilla source seam
 * ({@code ShaderCodeTransformation} via the ShaderManager cache) never reaches them (iris compiles
 * and substitutes its own programs via {@code GlDevice.getOrCompilePipeline}). This mixin injects
 * the SAME {@code seamlessportals_ClipPlane} decl + {@code gl_ClipDistance[0]} write into the
 * transformed sodium VERTEX source at {@code transformInternal} RETURN, so the per-draw uploaders
 * ({@code GlCommandEncoderClipMixin} + {@code MixinSodiumGLDrawContext_ClipUpload}) find a real
 * {@code >= 0} location on the iris terrain program and clip it at the portal plane.
 *
 * <h2>Why {@code transformInternal} RETURN (javap-confirmed)</h2>
 * {@code TransformPatcher.transformInternal(String, Map<PatchShaderType,String>, Parameters)}
 * returns the fully-TRANSFORMED source map (renames already applied). Splicing at RETURN is
 * break-proof: {@code gl_ModelViewMatrix} has already been renamed to {@code u_ModelViewMatrix},
 * {@code gl_Vertex} to {@code getVertexPosition()}, and {@code gl_ClipDistance} is a built-in. The
 * public 6-arg {@code transform(...)} wrapper caches by {@code CacheKey} and calls
 * {@code transformInternal} only on a cache MISS (jar bytecode: {@code containsKey} short-circuit),
 * so this {@code @Inject} fires ONCE per unique (params+sources) tuple — never per-frame, never
 * per-draw — and the mutated map is what gets cached. Zero steady-state cost, no double-patch.
 *
 * <h2>Gating</h2>
 * The Patch.SODIUM + VERTEX gate keeps it to sodium terrain only: iris routes entities/sky/
 * particles/clouds/hand through Patch.VANILLA (there is NO ENTITY patch type) and composites
 * through Patch.COMPOSITE — none of which should carry a view-space terrain clip (§4.0 option b:
 * those stay defined-and-unclipped via the per-draw definedness guards). The compat plugin's
 * gate-1 requires the simple name contain {@code "IrisSodium"} (tested BEFORE {@code "Iris"} /
 * {@code "Sodium"}, load-bearing order) → weaves only when BOTH mods are present; gate-2 is the
 * {@code EntityPortalsFlag} master switch (force-false off Fabric → skipped entirely on NeoForge).
 *
 * <h2>Compat- vs core-profile (jar-verified §4.2 correction)</h2>
 * {@code getVertexPosition()} is a defined symbol only on the compat-profile path
 * ({@code SodiumTransformer}); core-profile packs route through {@code SodiumCoreTransformer}
 * which injects {@code u_ModelViewMatrix} but NOT {@code getVertexPosition()}. So the injected
 * {@code u_ModelViewMatrix * getVertexPosition()} eye-space expression compiles only on the
 * compat path (BSL/Complementary/Sildur's are {@code #version 120} compat → covered). Core-profile
 * packs hit the {@code getVertexPosition(} gate → the one-shot WARN fail-safe: source returned
 * UNCHANGED (unclipped-but-defined residual; the sodium guard keeps the enable defined), NOT a
 * broken compile.
 *
 * <h2>Assert / version coupling</h2>
 * {@code require = 1} + {@code @Pseudo}: with iris present (the gate ensures it) a drift in
 * {@code transformInternal}'s descriptor across iris versions fails the bind → LOUD boot crash
 * (the D7 deliberate-honesty discipline). {@code @Pseudo} tolerates iris-absent (the gate already
 * skips). This couples the mod to iris 1.11.2+26.2's {@code TransformPatcher} shape (ledgered §4.6).
 */
@Pseudo
@Mixin(value = TransformPatcher.class, remap = false)
public abstract class MixinIrisSodiumTransformPatcher_ClipInject {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The existing C2-0 probe lever, reused for a full patched-source dump. */
    private static final boolean PROBE = Boolean.getBoolean("seamlessportals.compatProbe");

    /** One-shot guards keyed on the shader name: patch-INFO dumps + core-profile WARNs. */
    private static final Set<String> IP_PATCHED_IDS = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> IP_WARNED_IDS = Collections.synchronizedSet(new HashSet<>());

    /**
     * Decl spliced immediately before {@code main} — mirrors {@code ShaderCodeTransformation}'s
     * order: the explicit {@code gl_ClipDistance[1]} redeclaration FIRST (drivers silently drop
     * writes to the unsized built-in otherwise), then the uniform.
     */
    private static final String IP_UNIFORM_DECL =
        "out float gl_ClipDistance[1];\n" +
        "uniform vec4 " + ShaderCodeTransformation.UNIFORM_NAME + ";\n";

    // cancellable = true is MANDATORY: CallbackInfoReturnable.setReturnValue(R) invokes
    // CallbackInfo.cancel(), which THROWS CancellationException when cancellable == false (the
    // Mixin 0.8.5 default). Omitting it makes the setReturnValue below (fired on the primary
    // compat-profile path, where spliceClip returns a patched != vsh source) throw out of iris
    // shader compilation — the sibling setReturnValue-at-RETURN seams
    // (MixinSodiumShaderManagerCompilationCache_ClipSourcePatch:45,
    // ShaderManagerCompilationCacheMixin:32) all carry it; this one must too (IS3 V2 fold).
    @Inject(method = "transformInternal", at = @At("RETURN"), remap = false, require = 1, cancellable = true)
    private static void seamlessportals$injectSodiumTerrainClip(
        String name,
        Map<PatchShaderType, String> inputSources,
        Parameters params,
        CallbackInfoReturnable<Map<PatchShaderType, String>> cir
    ) {
        // Terrain only — sodium chunk shaders route through Patch.SODIUM (§4.0/§4.2).
        if (params.patch != Patch.SODIUM) {
            return;
        }
        Map<PatchShaderType, String> out = cir.getReturnValue();
        if (out == null) {
            return;
        }
        String vsh = out.get(PatchShaderType.VERTEX);
        if (vsh == null) {
            return;
        }
        String patched = seamlessportals$spliceClip(vsh, name);
        if (patched != vsh) {
            // Defensive: do NOT assume the returned map is mutable (bytecode does not prove the
            // EnumASTTransformer.transform result is in-place mutable). Copy + setReturnValue —
            // one alloc per cache MISS is free and zero-risk.
            EnumMap<PatchShaderType, String> copy = new EnumMap<>(out);
            copy.put(PatchShaderType.VERTEX, patched);
            cir.setReturnValue(copy);
        }
    }

    /**
     * Splice the clip decl + write into a TRANSFORMED sodium VERTEX source. Returns the SAME
     * reference (identity) when no patch is applied, so the caller can key on {@code != vsh}.
     */
    private static String seamlessportals$spliceClip(String source, String name) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        // 1. Idempotence — shared literal with the shaders-OFF sodium seam (belt-and-suspenders;
        //    the two seams operate on different source strings, mutually exclusive per program).
        if (source.contains(ShaderCodeTransformation.UNIFORM_NAME)) {
            return source;
        }
        // 2. u_ModelViewMatrix gate — present on both sodium (compat + core) paths.
        if (!source.contains("u_ModelViewMatrix")) {
            return seamlessportals$failSafe(source, name, "no u_ModelViewMatrix symbol");
        }
        // 3. getVertexPosition anchor gate — the compat/core discriminator (§4.2 correction).
        //    Absent on the core-profile path (SodiumCoreTransformer) → route to the fail-safe
        //    (unchanged source + one-shot WARN), NOT a broken compile.
        if (!source.contains("getVertexPosition(")) {
            return seamlessportals$failSafe(source, name,
                "core-profile: no getVertexPosition — unclipped-but-defined residual");
        }
        // 3b. Cheap insurance against an exotic pack that redeclares the gl_PerVertex block:
        //     our plain `out float gl_ClipDistance[1]` would collide with a redeclared block.
        if (source.contains("gl_PerVertex")) {
            return seamlessportals$failSafe(source, name,
                "redeclared gl_PerVertex block — skipping to avoid a duplicate gl_ClipDistance decl");
        }

        int mainIdx = source.indexOf("void main(");
        if (mainIdx < 0) {
            return seamlessportals$failSafe(source, name, "no 'void main(' anchor");
        }
        int braceOpen = source.indexOf('{', mainIdx);
        if (braceOpen < 0) {
            return seamlessportals$failSafe(source, name, "no opening brace after main");
        }
        // Brace-match main's body to find ITS closing brace (mirror SodiumClipShaderPatch:168-195;
        // sodium's terrain main is not wrapped — no irisMain rename on either sodium path — so the
        // single `void main(` is the real one).
        int depth = 0;
        int braceClose = -1;
        for (int i = braceOpen; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            }
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    braceClose = i;
                    break;
                }
            }
        }
        if (braceClose < 0) {
            return seamlessportals$failSafe(source, name, "unbalanced braces in main");
        }

        // The clip write — the §4.2 eye-space expression. getVertexPosition() returns vec4
        // (= vec4(_vert_position + u_RegionOffset + _get_draw_translation, 1.0), bytecode-equal to
        // sodium's own vec4(position,1.0)); u_ModelViewMatrix * getVertexPosition() = the eye-space
        // position, mathematically identical to the proven shaders-OFF sodium seam. Declared before
        // use: u_Globals (u_ModelViewMatrix) is injected BEFORE_DECLARATIONS and getVertexPosition
        // BEFORE_FUNCTIONS — both precede main; our uniform decl sits immediately before main.
        String injection = "\n"
            + "    {\n"
            + "        // SEAMLESSPORTALS_CLIP_INJECTED (iris-sodium terrain, IS3)\n"
            + "        gl_ClipDistance[0] = dot((u_ModelViewMatrix * getVertexPosition()).xyz, "
            + ShaderCodeTransformation.UNIFORM_NAME + ".xyz) + "
            + ShaderCodeTransformation.UNIFORM_NAME + ".w;\n"
            + "    }\n";

        StringBuilder out = new StringBuilder(
            source.length() + IP_UNIFORM_DECL.length() + injection.length());
        out.append(source, 0, mainIdx);          // directives + declarations
        out.append(IP_UNIFORM_DECL);             // decl immediately before main
        out.append(source, mainIdx, braceClose);
        out.append(injection);                   // before main's closing brace
        out.append(source, braceClose, source.length());
        String patched = out.toString();

        // D7 one-shot patch-INFO per shader id (mirror SodiumClipShaderPatch:216).
        if (IP_PATCHED_IDS.add(name)) {
            LOGGER.info("[imm_ptl IS3] iris-sodium terrain shader clip-patched: {}", name);
        }
        if (PROBE) {
            LOGGER.info(
                "[COMPAT PROBE IS3] TRANSFORMED iris-sodium vertex source ({}):\n{}", name, patched);
        }
        return patched;
    }

    private static String seamlessportals$failSafe(String source, String name, String reason) {
        if (IP_WARNED_IDS.add(name)) {
            LOGGER.warn(
                "[imm_ptl IS3] iris-sodium terrain shader NOT clip-patched ({}): {} — dest terrain "
                    + "from this program renders UNCLIPPED-BUT-DEFINED through portal apertures "
                    + "(the per-draw definedness guard keeps GL_CLIP_DISTANCE0 honest).",
                name, reason);
        }
        if (PROBE) {
            LOGGER.info(
                "[COMPAT PROBE IS3] UNPATCHED iris-sodium vertex source ({}, {}):\n{}",
                name, reason, source);
        }
        return source;
    }
}
