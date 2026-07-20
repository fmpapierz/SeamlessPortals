package qouteall.imm_ptl.core.compat.sodium_compatibility;

import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.render.ShaderCodeTransformation;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * C2-2 D3 — the sodium terrain-shader clip-source injector (design
 * {@code migration/C2_DESIGN.md} §3.3.2 M1; port-note {@code C2-sodium-iris.md} §3.1 P7a).
 *
 * <p>Pure string logic + the D4 backend gate. Called ONLY from
 * {@code MixinSodiumShaderManagerCompilationCache_ClipSourcePatch} (woven exclusively when sodium
 * is present, via the compat plugin's substring gate) — so the {@link DrawBackend} reference in
 * {@link #isGlBackend()} can never be reached on a sodium-absent runtime.
 *
 * <h2>The injection (unzip-verified against the REAL 0.9.1 jar, sha 14f3388…)</h2>
 * {@code assets/sodium/shaders/blocks/block_layer_opaque.vsh} is the ONLY {@code blocks/block_layer_*}
 * vertex shader in the jar ({@code ShaderChunkRenderer.compileProgram} bytecode requests only the
 * constant {@code "blocks/block_layer_opaque"} for every {@link
 * net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass}; per-pass variance is
 * defines-only: {@code ALPHA_CUTOUT}/{@code USE_FOG}). Verified anchors:
 * <ul>
 *   <li>{@code u_ModelViewMatrix} — std140 member of the {@code u_Globals} uniform block
 *       ({@code include/globals.glsl}), referenced as a plain global symbol;</li>
 *   <li>local {@code vec3 position} declared in {@code main} ({@code vec3 position =
 *       _vert_position + translation;}) and consumed by
 *       {@code gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);}</li>
 * </ul>
 * We inject (mirroring the vanilla transformer {@link ShaderCodeTransformation}'s exact
 * formatting/anchor discipline — decl text identical, immediately before {@code void main(}; the
 * clip write in an indented braced block):
 * <pre>
 *   out float gl_ClipDistance[1];
 *   uniform vec4 seamlessportals_ClipPlane;
 *   ...
 *   gl_ClipDistance[0] = dot((u_ModelViewMatrix * vec4(position, 1.0)).xyz,
 *                            seamlessportals_ClipPlane.xyz) + seamlessportals_ClipPlane.w;
 * </pre>
 * before {@code main}'s closing brace. VIEW-space by construction (D3): {@code u_ModelViewMatrix *
 * vec4(position, 1.0)} is exactly the view-space position the {@code gl_Position} line computes,
 * and the {@code com.warwa} {@code FrontClipping} store is view-space — the same expression the
 * vanilla injection evaluates ({@code dot(sp_viewPos.xyz, plane.xyz) + plane.w} with
 * {@code sp_viewPos = ModelViewMat * vec4(pos, 1.0)}), with sodium's symbol names. NO space
 * conversion. The FRAGMENT shader is deliberately untouched: {@code gl_ClipDistance} is a
 * vertex-stage output consumed by fixed-function clipping between vertex processing and
 * rasterization — the fsh needs nothing.
 *
 * <h2>Guards</h2>
 * <ul>
 *   <li><b>Double-patch (P11 cost-one-contains)</b>: a source already containing
 *       {@code seamlessportals_ClipPlane} returns unpatched — idempotent under any future
 *       iris {@code patchSodium} routing (C2-4's question, guarded now).</li>
 *   <li><b>Anchor fail-safe</b>: any missing anchor (e.g. a resource pack replaced sodium's
 *       shader source) returns the source UNPATCHED with a one-shot WARN; the resulting
 *       location==-1 program is then caught by {@code MixinSodiumGLDrawContext_ClipUpload}'s
 *       definedness guard (the D10-residual envelope: unclipped-but-defined).</li>
 *   <li><b>D4 VK gate</b> ({@link #isGlBackend()}): lazy, first-patch-call evaluation of
 *       {@code DrawBackend.BACKEND} (census correction #3 — never {@code instanceof} the
 *       GpuDevice). Lazy is mandatory: the compat mixins weave long before the GPU device
 *       exists, but the first sodium shader-source fetch happens during pipeline compilation
 *       with the device active — and {@code DrawBackend.<clinit>} has by then already run via
 *       {@code DrawContext.create()} in {@code DefaultChunkRenderer}'s ctor. Under VK the
 *       patch is skipped entirely (a loose {@code uniform vec4} is illegal under Vulkan GLSL
 *       and would fail the shader compile) with a one-shot log naming the GL-only deviation
 *       (design §3.3.5 / user decision #2 at C2-2 close).</li>
 * </ul>
 *
 * <h2>Dump lever</h2>
 * {@code -Dseamlessportals.compatProbe=true} (the existing C2-0 probe lever) dumps the full
 * PATCHED vertex source once per shader id — the driver-silent-drop diagnosis tool, mirroring
 * the vanilla transformer's one-shot terrain dump.
 */
public final class SodiumClipShaderPatch {

    private SodiumClipShaderPatch() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The existing probe lever (C2-0), reused as the transformed-source dump lever. */
    private static final boolean PROBE = Boolean.getBoolean("seamlessportals.compatProbe");

    /** One-shot guards: per-id probe dumps and per-id anchor-failure warns. */
    private static final Set<String> DUMPED_IDS = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> WARNED_IDS = Collections.synchronizedSet(new HashSet<>());

    /** Lazy D4 verdict; volatile Boolean (null = not yet evaluated). */
    private static volatile Boolean glBackend = null;
    private static volatile boolean backendLogged = false;

    /**
     * Decl block — mirrors {@code ShaderCodeTransformation.UNIFORM_DECL} EXACTLY (same text, same
     * order: the explicit {@code gl_ClipDistance[1]} redeclaration FIRST — required so drivers
     * don't silently drop writes to the unsized built-in — then the uniform).
     */
    private static final String UNIFORM_DECL =
        "out float gl_ClipDistance[1];\n" +
        "uniform vec4 " + ShaderCodeTransformation.UNIFORM_NAME + ";\n";

    /**
     * D4: true iff sodium is driving the OpenGL backend. Lazy one-shot; on the (unreachable-today)
     * VK backends the clipping chain is skipped and the deviation is logged once. A throwing
     * {@code DrawBackend} access degrades to "not GL" (skip patching) — the unpatched program is
     * then handled by the uploader's definedness guard, never undefined behavior.
     */
    public static boolean isGlBackend() {
        Boolean cached = glBackend;
        if (cached != null) {
            return cached;
        }
        boolean gl;
        boolean unreadable = false;
        try {
            gl = DrawBackend.BACKEND == DrawBackend.OPENGL;
        }
        catch (Throwable t) {
            // DrawContext.create() in DefaultChunkRenderer's ctor performs this exact read long
            // before any terrain shader compiles, so reaching here means sodium itself is already
            // broken — degrade honestly (no patch → definedness guard) rather than break shader
            // loading.
            LOGGER.warn(
                "[imm_ptl C2-2] DrawBackend.BACKEND unreadable — treating as non-GL; "
                    + "sodium terrain clipping disabled (unclipped-but-defined degrade)", t);
            gl = false;
            unreadable = true;
        }
        if (!gl && !backendLogged) {
            backendLogged = true;
            // Lens-B wording note: only claim Vulkan when the backend was actually READ as non-GL;
            // an unreadable field proves nothing about which backend runs.
            LOGGER.warn(
                "[imm_ptl C2-2] Sodium draw backend is {} — portal-plane clipping is GL-only "
                    + "(named deviation D4, design §3.3.5): sodium terrain will render UNCLIPPED "
                    + "through portal apertures this session.",
                unreadable ? "unreadable (treated as non-GL)" : "non-GL (Vulkan)");
        }
        glBackend = gl;
        return gl;
    }

    /**
     * Patch a {@code sodium:blocks/block_layer_*} VERTEX source. Returns the SAME reference when
     * no patch was applied (the caller keys on identity, like the vanilla mixin does).
     *
     * @param source   the raw GLSL from the ShaderManager source seam
     * @param idString the shader id (log/one-shot key only)
     */
    public static String patchTerrainVertex(String source, String idString) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        // Double-patch guard (P11): one contains(), per the design.
        if (source.contains(ShaderCodeTransformation.UNIFORM_NAME)) {
            return source;
        }
        // Anchor checks — every symbol unzip-verified against the real 0.9.1 vsh; a replaced
        // source (resource pack) that lacks them fails SAFE (unpatched + guard).
        if (!source.contains("u_ModelViewMatrix")) {
            return fail(source, idString, "no u_ModelViewMatrix symbol");
        }
        if (!source.contains("vec3 position")) {
            return fail(source, idString, "no local 'vec3 position' in main");
        }
        int mainIdx = source.indexOf("void main(");
        if (mainIdx < 0) {
            return fail(source, idString, "no 'void main(' anchor");
        }
        int braceOpen = source.indexOf('{', mainIdx);
        if (braceOpen < 0) {
            return fail(source, idString, "no opening brace after main");
        }
        // Brace-match main's body to find ITS closing brace (robust to main not being the last
        // function; the real 0.9.1 source has no braces inside comments in main).
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
            return fail(source, idString, "unbalanced braces in main");
        }

        // The clip write — the D3 view-space expression, exactly the vanilla injection's math with
        // sodium's symbols; injected before main's closing brace where 'position' is in scope.
        String injection = "\n"
            + "    {\n"
            + "        // SEAMLESSPORTALS_CLIP_INJECTED (sodium terrain, C2-2 D3)\n"
            + "        gl_ClipDistance[0] = dot((u_ModelViewMatrix * vec4(position, 1.0)).xyz, "
            + ShaderCodeTransformation.UNIFORM_NAME + ".xyz) + "
            + ShaderCodeTransformation.UNIFORM_NAME + ".w;\n"
            + "    }\n";

        StringBuilder out = new StringBuilder(
            source.length() + UNIFORM_DECL.length() + injection.length());
        out.append(source, 0, mainIdx);      // everything up to main (directives + declarations)
        out.append(UNIFORM_DECL);            // decl immediately before main — the vanilla anchor
        out.append(source, mainIdx, braceClose);
        out.append(injection);               // before main's closing brace
        out.append(source, braceClose, source.length());
        String patched = out.toString();

        LOGGER.info("[imm_ptl C2-2] sodium terrain shader clip-patched: {}", idString);
        if (PROBE && DUMPED_IDS.add(idString)) {
            LOGGER.info(
                "[COMPAT PROBE C2-2] TRANSFORMED sodium vertex source ({}):\n{}",
                idString, patched);
        }
        return patched;
    }

    private static String fail(String source, String idString, String reason) {
        if (WARNED_IDS.add(idString)) {
            LOGGER.warn(
                "[imm_ptl C2-2] sodium terrain shader NOT clip-patched ({}): {} — the source does "
                    + "not match Sodium 0.9.1's shipped block_layer vsh (replaced by a resource "
                    + "pack?). Dest terrain from this program renders UNCLIPPED-BUT-DEFINED (the "
                    + "uploader's definedness guard suppresses GL_CLIP_DISTANCE0 for it).",
                idString, reason);
        }
        return source;
    }
}
