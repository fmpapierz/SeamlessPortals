package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.SeamlessPortalsConstants;

/**
 * Rewrites vanilla vertex-shader GLSL source at load time to add a
 * {@code gl_ClipDistance[0]} write backed by a uniform vec4 clip plane.
 *
 * <p>IP 1.19's equivalent is {@code qouteall.imm_ptl.core.render.ShaderCodeTransformation}.
 * Mechanism:
 * <ol>
 *   <li>Append {@code uniform vec4 seamlessportals_ClipPlane;} to the
 *       shader's global declarations (before {@code main()}).</li>
 *   <li>Find the line that writes {@code gl_Position = ...} and inject a
 *       {@code gl_ClipDistance[0] = dot(viewPos.xyz, plane.xyz) + plane.w;}
 *       right after it, deriving {@code viewPos} from
 *       {@code inverse(ProjMat) * gl_Position} when available, or from the
 *       common {@code ModelViewMat * vec4(pos, 1.0)} pattern.</li>
 * </ol>
 *
 * <p>Semantics: {@code gl_ClipDistance[0] >= 0} keeps the fragment,
 * {@code < 0} clips it. Plane in view space. Setting plane to
 * {@code (0, 0, 0, 1)} makes the distance always {@code 1} — no clipping
 * (the "disabled" state).
 *
 * <p>MVP note: current implementation is conservative — it only injects
 * into shaders that contain the canonical {@code gl_Position = ProjMat *
 * ModelViewMat * vec4(<pos>, 1.0)} pattern. Shaders with a different
 * layout (e.g. {@code rendertype_end_portal}, {@code panorama}) are left
 * untouched; their vanilla behaviour is preserved. The clip plane applies
 * to everything we successfully patched — block terrain, entities, items,
 * clouds, leashes, lightning, lines, the common position/color helpers.
 * Ungated shaders will show uncut source geometry during a slice; we'll
 * extend the pattern coverage in a follow-up if that matters visually.
 */
public final class ShaderCodeTransformation {

    private ShaderCodeTransformation() {}

    /** Uniform added to every patched vertex shader. */
    public static final String UNIFORM_NAME = "seamlessportals_ClipPlane";

    /**
     * TINT (SEAM_BAND_HANDOFF §4.1, diagnostic): uniform added to every patched FRAGMENT
     * shader while {@code -PseamPainterTint} is armed. Uploaded per draw by
     * {@code GlCommandEncoderClipMixin} from the live store's tint fields; GLSL guarantees
     * un-uploaded uniforms read as zero, so unpainted programs are no-ops by construction.
     */
    public static final String TINT_UNIFORM_NAME = "seamlessportals_DebugTint";
    private static final String TINT_INJECTED_MARKER = "// SEAMLESSPORTALS_TINT_INJECTED";
    /**
     * Uniform + explicit {@code gl_ClipDistance[1]} redeclaration.
     *
     * <p>Many GL drivers silently DROP writes to {@code gl_ClipDistance[0]}
     * unless the built-in output array is explicitly sized. Per the GLSL
     * spec and the OpenGL wiki, the array is pre-declared as an unsized
     * built-in, but the client must size it before use. Without the
     * redeclaration, the driver sees writes to an unsized array and may
     * link the shader fine but never export the clip distance — so
     * {@code glEnable(GL_CLIP_DISTANCE0)} has no effect. Adding this
     * declaration fixes silent-no-op cases on NVIDIA/AMD both.
     */
    private static final String UNIFORM_DECL =
        "out float gl_ClipDistance[1];\n" +
        "uniform vec4 " + UNIFORM_NAME + ";\n";
    private static final String INJECTED_MARKER = "// SEAMLESSPORTALS_CLIP_INJECTED";

    /**
     * Transform a vertex-shader source string, adding a clip-plane uniform
     * and a {@code gl_ClipDistance[0]} write.
     *
     * @param source raw shader source
     * @return transformed source, or the original if no injection point was
     *         found. Idempotent — running twice is a no-op.
     */
    public static String transformVertex(String source) {
        if (source == null || source.isEmpty()) return source;
        if (source.contains(INJECTED_MARKER)) return source;

        // Strict gate: only touch shaders that use both the canonical
        // ModelViewMat and ProjMat uniforms. Non-world shaders like
        // screenquad (post-process, writes gl_Position directly from
        // attribute data) or animate_sprite (compute / texture atlas
        // processing) don't transform a 3D position and there's no
        // sensible clip plane to apply — trying would fail to compile
        // because the uniforms aren't even declared.
        if (!source.contains("ModelViewMat") || !source.contains("ProjMat")) {
            return source;
        }

        int mainIdx = source.indexOf("void main(");
        if (mainIdx < 0) return source;

        int glPosIdx = source.indexOf("gl_Position", mainIdx);
        if (glPosIdx < 0) return source;

        int semiIdx = source.indexOf(';', glPosIdx);
        if (semiIdx < 0) return source;

        // Extract the POS expression from the canonical
        //   gl_Position = ProjMat * ModelViewMat * vec4(<pos>, 1.0);
        // pattern. If the shader doesn't match, skip — we can extend
        // coverage later with more patterns.
        String afterGlPos = source.substring(glPosIdx, semiIdx + 1);
        String posExpr = extractPositionExpr(afterGlPos);
        if (posExpr == null) return source;
        // 26.3: this transform no longer sees Mojang's GLSL. Vanilla now compiles its sources with shaderc to SPIR-V under
        // VULKAN rules (mc263-ref com/mojang/renderpearl/frontend/shaders/GlslCompiler.java:79 set_target_env(.., 0, 4202496)),
        // where the loose `uniform vec4` this patch adds is illegal, and every shader-declared uniform must be declared by the
        // RenderPipeline (PipelineBuilder.java:276-278 "Unable to find shader defined uniform"). The ONLY GLSL text that still
        // reaches the GL driver is what spirv-cross regenerates from that SPIR-V (backend/opengl/GlPipelineRecompiler.java
        // :48-109 -> compileShader :188-191 glShaderSource), so the patch runs THERE (see ShaderManagerCompilationCacheMixin).
        // In that text every anchor this method uses is intact — reproduced offline with vanilla's exact shaderc/spvc options:
        //   gl_Position = (_uniform_instance_00_01.ProjMat * _uniform_instance_00_02.ModelViewMat) * vec4(pos, 1.0);
        // — except that UBO members are reached through the block INSTANCE name spirv-cross is told to emit
        // (GlPipelineRecompiler.java:148-157 "_uniform_instance_%02d_%02d"). So the one change is to qualify ModelViewMat with
        // the accessor the source itself uses; a source that declares it as a bare name (the 26.2 form) is handled unchanged.
        String viewPosExpr = modelViewMatAccessor(source) + " * vec4(" + posExpr + ", 1.0)";

        String injection = "\n"
            + "    {\n"
            + "        " + INJECTED_MARKER + "\n"
            + "        vec4 sp_viewPos = " + viewPosExpr + ";\n"
            + "        gl_ClipDistance[0] = dot(sp_viewPos.xyz, " + UNIFORM_NAME + ".xyz) + " + UNIFORM_NAME + ".w;\n"
            + "    }\n";

        // Insert uniform declaration before main().
        StringBuilder out = new StringBuilder(source.length() + UNIFORM_DECL.length() + injection.length());
        out.append(source, 0, mainIdx);
        out.append(UNIFORM_DECL);
        out.append(source, mainIdx, semiIdx + 1);
        out.append(injection);
        out.append(source, semiIdx + 1, source.length());

        if (SeamlessPortalsConstants.LOGGER.isDebugEnabled()) {
            SeamlessPortalsConstants.LOGGER.debug(
                "[SEAMLESS SLICE] transformed vertex shader (+{} bytes)",
                out.length() - source.length());
        }
        return out.toString();
    }

    /**
     * TINT (SEAM_BAND_HANDOFF §4.1, diagnostic): transform a FRAGMENT shader source, wrapping
     * its {@code main()} so a debug tint uniform can be mixed over the final colour.
     *
     * <p>ANCHOR DERIVATION (the verdict's correction — the once-claimed {@code vertexColor}
     * anchor does NOT exist): the 26.2 fragment sources (e.g. {@code entity.fsh}, extracted
     * from the loom 26.2 client jar) are heavy with {@code #ifdef} variants
     * (PER_FACE_LIGHTING/EMISSIVE/NO_OVERLAY/DISSOLVE) and contain {@code discard} paths, so no
     * single interior statement is a safe anchor. The two facts that ARE stable across every
     * vanilla fragment shader: the out variable is declared exactly {@code out vec4 fragColor;}
     * and there is exactly one {@code void main(}. So: rename the real main to
     * {@code seamlessportals_tintRealMain} and append a wrapper {@code main()} that calls it,
     * then mixes the tint in — correct under every define-variant, every {@code discard}
     * (a discarded fragment never reaches the wrapper's mix), and any early {@code return}
     * (control returns to the wrapper, which still applies the tint).
     *
     * @return transformed source, or the original when the shader doesn't match (no
     *         {@code fragColor} out, zero or multiple mains) — those draw untinted, which is
     *         itself attribution data. Idempotent via the marker.
     */
    public static String transformFragment(String source) {
        if (source == null || source.isEmpty()) return source;
        if (source.contains(TINT_INJECTED_MARKER)) return source;
        // 26.3: the fragment text this sees is spirv-cross output (see the note in transformVertex), where the fragment outputs
        // are renamed by location — GlPipelineRecompiler.java:79-81 renameInterfaceVariables(.., 4, "_frag_output_%02d") — so
        // Mojang's `out vec4 fragColor;` (location 0) arrives as `layout(location = 0) out vec4 _frag_output_00;` (reproduced
        // offline for position_color/entity/terrain.fsh). Same anchor, same variable, new spelling; the 26.2 spelling still works.
        String fragColor;
        if (source.contains("out vec4 fragColor;")) {
            fragColor = "fragColor";
        } else if (source.contains("out vec4 _frag_output_00;")) {
            fragColor = "_frag_output_00";
        } else {
            return source;
        }

        int mainIdx = source.indexOf("void main(");
        if (mainIdx < 0) return source;
        if (source.indexOf("void main(", mainIdx + 1) >= 0) return source;

        StringBuilder out = new StringBuilder(source.length() + 320);
        out.append(source, 0, mainIdx);
        out.append("void seamlessportals_tintRealMain(");
        out.append(source, mainIdx + "void main(".length(), source.length());
        out.append('\n')
            .append(TINT_INJECTED_MARKER).append('\n')
            .append("uniform vec4 ").append(TINT_UNIFORM_NAME).append(";\n")
            .append("void main() {\n")
            .append("    seamlessportals_tintRealMain();\n")
            .append("    if (").append(TINT_UNIFORM_NAME).append(".a > 0.001) {\n")
            .append("        ").append(fragColor).append(" = vec4(mix(").append(fragColor).append(".rgb, ") // 26.3: was the literal fragColor
            .append(TINT_UNIFORM_NAME).append(".rgb, ")
            .append(TINT_UNIFORM_NAME).append(".a), ").append(fragColor).append(".a);\n")
            .append("    }\n")
            .append("}\n");
        return out.toString();
    }

    /**
     * 26.3: how this source spells {@code ModelViewMat} — {@code <blockInstance>.ModelViewMat} in spirv-cross output (see the
     * note in {@link #transformVertex}), or the bare name when no qualified use exists.
     */
    private static final java.util.regex.Pattern MODEL_VIEW_MAT_ACCESSOR =
        java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z0-9_]*\\.)ModelViewMat\\b");

    private static String modelViewMatAccessor(String source) {
        java.util.regex.Matcher m = MODEL_VIEW_MAT_ACCESSOR.matcher(source);
        return m.find() ? m.group(1) + "ModelViewMat" : "ModelViewMat";
    }

    /**
     * Try to extract the POS expression from a canonical vanilla line
     * like {@code gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);}
     * Returns null if the line doesn't match the pattern.
     */
    private static String extractPositionExpr(String glPosLine) {
        int vec4 = glPosLine.indexOf("vec4(");
        if (vec4 < 0) return null;
        int open = vec4 + "vec4(".length();
        int depth = 1;
        int i = open;
        int firstComma = -1;
        while (i < glPosLine.length() && depth > 0) {
            char c = glPosLine.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') { depth--; if (depth == 0) break; }
            else if (c == ',' && depth == 1 && firstComma == -1) firstComma = i;
            i++;
        }
        if (firstComma < 0) return null;
        return glPosLine.substring(open, firstComma).trim();
    }
}
