package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IS5-P — the iris TEMPORAL-TARGET GUARD: the fix for the shaders-ON, whole-screen, faint,
 * per-frame PHANTOM terrain overlay (task #8, the phantom half).
 *
 * <h2>The bug (LIVE-CONFIRMED by the user's TAA-off test; investigation
 * {@code phantom-terrain-investigation})</h2>
 * The shaders-ON compat renderer ({@link IrisCompatOn262Renderer#onBeforeHandRendering}) runs the
 * portal-DEST world through iris's FULL deferred pipeline, which writes the dest scene full-screen
 * into iris's PERSISTENT {@code clear=false} color render targets — for Complementary Reimagined,
 * {@code colortex2} (RGB16F, {@code //taa} = the TAA history), plus its other clear=false temporal
 * targets. The compat renderer restores only {@code mc.gameRenderer.mainRenderTarget()}'s COLOR
 * (via {@code IrisCompatPaste.drawStraightCopy}); it never touches iris's colortex. So the NEXT
 * frame's MAIN composite/TAA samples the polluted history and reprojects the dest terrain into
 * view = a faint whole-screen phantom, baked into mainRT UPSTREAM of the compat anchor (which fires
 * AFTER iris finalize; iris DISPLAYS mainRT, so the mainRT blit-back is faithful — the pollution
 * re-enters solely via iris's persistent colortex the mainRT blit can never isolate). Turning TAA
 * off in Complementary made the phantom vanish — proving the temporal-history carrier.
 *
 * <h2>The fix (design panel {@code phantom-fix-design}, SOUND x2)</h2>
 * SAVE, right after iris finalized the main frame and BEFORE the per-portal dest render, the CURRENT
 * (post-main-finalize = legitimate main-view history) contents of every {@code clear=false} COLOR
 * target — BOTH main and alt of each ping-pong pair — into scratch GL textures; let the dest render
 * pollute them; then RESTORE byte-for-byte AFTER the blit-back (in the throw-safe finally). Net
 * effect on iris's persistent buffers = identical to "the dest render never ran": the phantom dies,
 * and the MAIN view's own TAA continuity is preserved by construction (we put back exactly frame N's
 * legitimate history that frame N+1 must reproject).
 *
 * <p><b>Why BOTH main+alt</b> (recon): iris 1.11.2 keeps NO PERSISTENT per-frame {@code BufferFlipper}
 * on the pipeline (the class exists, but flip CONFIG is baked at construction into final
 * {@code ImmutableSet} snapshots and {@code getMainTexture()/getAltTexture()} return FIXED,
 * flip-parity-independent ids), so there is no runtime flip state to save. The dest render's full composite chain ping-pongs BOTH textures of each pair, so
 * both are polluted; a byte-identical restore of BOTH makes the next-frame read identical regardless
 * of which physical texture the pack's deterministic parity samples = phase-agnostic correctness.
 *
 * <p><b>Why only {@code clear=false} COLOR targets:</b> {@code clear=true} targets are re-cleared at
 * the next frame's {@code beginLevelRendering}, so dest dirt in them cannot leak cross-frame (this
 * bounds the cost — for Complementary, ~5 targets). Depth is a separate {@code GpuTexture} and is not
 * the persistent-history bug. Enumerated PACK-AGNOSTICALLY from the live pack directives
 * ({@code RenderTargetSettings.shouldClear()==false}), not hardcoded.
 *
 * <h2>Access / GL discipline</h2>
 * The live pipeline is {@code Iris.getPipelineManager().getPipelineNullable()} (public — at hook
 * entry, before any dest render, it IS the main/same-dim pipeline; cross-dim dest renders use a
 * SEPARATE per-dim pipeline, so restoring the main pipeline's textures is inherently same-dim-scoped
 * = a no-op for cross-dim). {@code renderTargets}/{@code packDirectives} are private-final on
 * {@code IrisRenderingPipeline}, reached by reflection (the {@code ShadowEmptinessProbe} precedent —
 * NO new iris @Mixin, preserving the D9 zero-iris-mixin invariant). iris color targets are RAW GL
 * texture IDs, so the copy is {@code glCopyImageSubData} (binds nothing — 26.2 GL-state invariant #1
 * safe; the {@code ShadowMapSwapper} precedent); scratch alloc is DSA ({@code glCreateTextures} +
 * {@code glTextureStorage2D}, binds nothing) with the target's GL-QUERIED sized internal format
 * ({@code glGetTextureLevelParameteri(GL_TEXTURE_INTERNAL_FORMAT)} — iris's own
 * {@code getInternalFormat().getGlFormat()} is NOT a storage-valid sized format), guaranteeing both
 * storage validity and copy view-class compatibility. Lever-gated
 * ({@link IPGlobal#isIrisTemporalGuardActive()}), DEFAULT ON, byte-identical when off ({@code save()}
 * returns false before any GL call). {@code save()} never propagates an exception into the anchor.
 */
@Environment(EnvType.CLIENT)
public final class IrisTemporalTargetGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** GL gate: glCopyImageSubData (4.3) for the copy + glCreateTextures (4.5 DSA) for the scratch alloc /
     *  format query. If absent the whole guard no-ops (phantom persists, no crash). */
    private static final boolean COPY_SUPPORTED =
        GL.getCapabilities().glCopyImageSubData != 0L && GL.getCapabilities().glCreateTextures != 0L;

    /** IS5-G GL gate: glClearTexImage (4.4) for the dest-pass history neutralization. Absent -> the
     *  clear no-ops (the ghost persists, no crash; save/restore unaffected). */
    private static final boolean CLEAR_SUPPORTED = GL.getCapabilities().glClearTexImage != 0L;

    private static boolean reflectionReady = false;
    private static boolean reflectionAttempted = false;
    private static Field fRenderTargets;
    private static Field fPackDirectives;

    /** colortex index -> {mainScratchId, altScratchId, w, h, glSizedFormat}. Scratch persists across frames. */
    private static final Map<Integer, int[]> scratch = new HashMap<>();

    /** per-save records, cleared each frame: {idx, dstMain, dstAlt, w, h, scratchMain, scratchAlt, fmt}.
     *  (fmt = the GL-queried sized internal format, appended for the IS5-G dest-pass clear's
     *  integer-vs-float format selection; restore() reads only indices 1..6.) */
    private static final List<int[]> savedList = new ArrayList<>();

    /** IS5-G once-only log latches (the clear fires per-portal-per-frame — an unlatched per-frame
     *  render-thread log is the known ~130ms log4j-stall class). */
    private static boolean clearFailLogged = false;
    private static boolean clearGlErrorLogged = false;
    /** Once-only LIVENESS log: the counter-bump lesson — a fix that produces zero log evidence of
     *  firing cannot be A/B-judged. Emitted on the first successful clear of a session. */
    private static boolean clearLiveLogged = false;

    private IrisTemporalTargetGuard() {}

    private static boolean ensureReflection() {
        if (reflectionReady) return true;
        if (reflectionAttempted) return false;
        reflectionAttempted = true;
        try {
            Class<?> p = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            fRenderTargets = p.getDeclaredField("renderTargets");
            fRenderTargets.setAccessible(true);
            fPackDirectives = p.getDeclaredField("packDirectives");
            fPackDirectives.setAccessible(true);
            reflectionReady = true;
        } catch (Throwable t) {
            LOGGER.error("[Seamless Portals] iris temporal-guard reflection failed; guard disabled", t);
        }
        return reflectionReady;
    }

    /**
     * Snapshot iris's clear=false color targets (both main+alt) into scratch. Call immediately BEFORE
     * the per-portal dest render. Returns true iff something was saved (then {@link #restore()} must run).
     * Never throws into the caller — a pipeline-teardown/hot-reload race is caught and disables this frame.
     */
    public static boolean save() {
        if (!IPGlobal.isIrisTemporalGuardActive() || !COPY_SUPPORTED) {
            return false;
        }
        try {
            WorldRenderingPipeline plRaw = Iris.getPipelineManager().getPipelineNullable();
            if (!(plRaw instanceof IrisRenderingPipeline pipeline)) {
                return false; // no active iris pipeline / vanilla-shader path -> nothing to guard
            }
            if (!ensureReflection()) {
                return false;
            }
            savedList.clear();
            RenderTargets rts = (RenderTargets) fRenderTargets.get(pipeline);
            PackDirectives pd = (PackDirectives) fPackDirectives.get(pipeline);
            if (rts == null || pd == null) {
                return false;
            }
            int count = rts.getRenderTargetCount();
            for (Map.Entry<Integer, PackRenderTargetDirectives.RenderTargetSettings> e
                : pd.getRenderTargetDirectives().getRenderTargetSettings().entrySet()) {
                if (e.getValue().shouldClear()) {
                    continue; // clear=true: re-cleared next frame, cannot leak cross-frame -> skip (cost bound)
                }
                int idx = e.getKey();
                if (idx < 0 || idx >= count) {
                    continue;
                }
                RenderTarget t = rts.get(idx);
                if (t == null) {
                    continue; // declared but never created (e.g. a Voxy-only target when Voxy is absent)
                }
                int w = t.getWidth();
                int h = t.getHeight();
                int dstMain = t.getMainTexture();
                int dstAlt = t.getAltTexture();
                // Query the target's ACTUAL sized internal format from GL (robust against driver format
                // promotion; getInternalFormat().getGlFormat() proved insufficient). Guarantees
                // glTextureStorage2D validity + glCopyImageSubData src/dst view-class compatibility.
                int fmt = GL45C.glGetTextureLevelParameteri(dstMain, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                int[] s = ensureScratch(idx, w, h, fmt);
                if (s == null) {
                    continue; // scratch alloc rejected this format (hardening) -> skip this target
                }
                copy(dstMain, s[0], w, h);
                copy(dstAlt, s[1], w, h);
                savedList.add(new int[]{idx, dstMain, dstAlt, w, h, s[0], s[1], fmt});
            }
            IPGlobal.irisTemporalGuardCopyCount += savedList.size() * 2;
            return !savedList.isEmpty();
        } catch (Throwable t) {
            LOGGER.error("[Seamless Portals] iris temporal-guard save() failed; skipping restore this frame", t);
            savedList.clear();
            return false;
        }
    }

    /** Restore the saved history back into iris's color targets (both main+alt). Call AFTER the blit-back. */
    public static void restore() {
        try {
            for (int[] sv : savedList) {
                // sv = {idx, dstMain, dstAlt, w, h, scratchMain, scratchAlt, fmt}
                copy(sv[5], sv[1], sv[3], sv[4]); // scratchMain -> dstMain
                copy(sv[6], sv[2], sv[3], sv[4]); // scratchAlt  -> dstAlt
            }
        } catch (Throwable t) {
            LOGGER.error("[Seamless Portals] iris temporal-guard restore() failed", t);
        } finally {
            savedList.clear();
        }
    }

    /**
     * IS5-G — the DEST-pass TAA-history NEUTRALIZATION (the "ghost terrain" fix; fix panel
     * wf_ab83a5fa-b39, 2x SOUND-WITH-FIXES).
     *
     * <p><b>The bug (live-proven by the user's toggle chain):</b> this guard's save/restore protects
     * the MAIN view's persistent history from dest pollution — but DURING the dest render the live
     * textures still HOLD the source frames, and the dest pass's TAA composite READS them as its own
     * history → SOURCE-world surfaces blended over the dest terrain, reprojected against the
     * mismatched camera = the moving "ghost terrain" wave (Temporal Filtering OFF killed it live;
     * stamp/shadow-map/SSAO all exonerated by levers/toggles). The mirror direction of the phantom
     * this guard fixed.
     *
     * <p><b>The fix:</b> clear every SAVED (clear=false) target pair to exact ZERO immediately before
     * EACH portal's nested dest render. Complementary's taa.glsl black-history early-out
     * ({@code tempColor == vec3(0.0) || isnan} → pure current frame) makes the dest view render
     * blend-free — no ghost, no darkening (Catmull-Rom on all-black = exact ±0.0). PER-PORTAL is
     * mandatory: the pack's composite chain writes each dest frame back into the history targets, so
     * a once-after-save clear would hand portal 2 portal 1's frame (cross-portal ghost). The existing
     * {@link #restore()} then returns the main history byte-whole — the phantom fix is untouched by
     * construction. In-window cost: the dest view runs TAA-history-free (sub-pixel jitter wobble +
     * fresh-per-frame temporal effects inside the aperture — accepted; the pack's gbuffer jitter and
     * FXAA stay active). Cross-dim portals reach here too: the clear touches the MAIN pipeline's
     * saved targets (restored later) while the dest reads its own per-dim pipeline —
     * wasted-but-harmless.
     *
     * <p>Gated so it can NEVER touch an unsaved texture: {@code savedList} is non-empty only inside
     * the save/restore bracket (save() clears it on catch, restore() clears in finally), and the
     * lever composes on {@link IPGlobal#isIrisTemporalGuardActive()} — guard off ⇒ clear off.
     * {@code glClearTexImage} binds nothing (GL-state invariant #1 safe). Integer-format targets get
     * the {@code GL_RGBA_INTEGER} pixel format (a float format on an integer texture is
     * GL_INVALID_OPERATION); the trailing error drain (once-only warn) keeps a rejected exotic
     * format from being misattributed at the anchor's checkGlError.
     */
    public static void clearForDestPass() {
        if (!CLEAR_SUPPORTED || !IPGlobal.isIrisDestTaaClearActive() || savedList.isEmpty()) {
            return;
        }
        try {
            for (int[] sv : savedList) {
                // sv = {idx, dstMain, dstAlt, w, h, scratchMain, scratchAlt, fmt}
                int pixelFormat = isIntegerFormat(sv[7]) ? GL30C.GL_RGBA_INTEGER : GL11.GL_RGBA;
                int pixelType = isIntegerFormat(sv[7]) ? GL11.GL_INT : GL11.GL_FLOAT;
                // data == null -> cleared to zeros (the black-history early-out trigger)
                GL44C.glClearTexImage(sv[1], 0, pixelFormat, pixelType, (java.nio.ByteBuffer) null);
                GL44C.glClearTexImage(sv[2], 0, pixelFormat, pixelType, (java.nio.ByteBuffer) null);
            }
            IPGlobal.irisDestTaaClearCount += savedList.size() * 2;
            if (!clearLiveLogged) {
                clearLiveLogged = true;
                LOGGER.info("[Seamless Portals] IS5-G dest-pass TAA-history clear ACTIVE: zeroed {}"
                    + " saved target pair(s) before a portal dest render (once-only liveness line)",
                    savedList.size());
            }
            // Drain any queued error from an exotic-format rejection so it cannot be misattributed
            // at the anchor's CHelper.checkGlError(). Once-only warn (per-portal-per-frame seam —
            // the log4j render-thread stall rule).
            int err;
            boolean any = false;
            while ((err = GL11.glGetError()) != GL11.GL_NO_ERROR) {
                any = true;
                if (!clearGlErrorLogged) {
                    clearGlErrorLogged = true;
                    LOGGER.warn("[Seamless Portals] dest-pass TAA clear raised GL error 0x{}"
                        + " (exotic target format?); further occurrences suppressed",
                        Integer.toHexString(err));
                }
            }
            if (any && clearGlErrorLogged) {
                // drained; nothing else to do (the clear of that target simply didn't take)
            }
        } catch (Throwable t) {
            if (!clearFailLogged) {
                clearFailLogged = true;
                LOGGER.error("[Seamless Portals] dest-pass TAA clear failed; further occurrences"
                    + " suppressed (the ghost may persist)", t);
            }
        }
    }

    /** Integer sized internal formats need GL_RGBA_INTEGER at glClearTexImage (else GL_INVALID_OPERATION). */
    private static boolean isIntegerFormat(int fmt) {
        return switch (fmt) {
            case GL30C.GL_R8I, GL30C.GL_R8UI, GL30C.GL_R16I, GL30C.GL_R16UI,
                 GL30C.GL_R32I, GL30C.GL_R32UI,
                 GL30C.GL_RG8I, GL30C.GL_RG8UI, GL30C.GL_RG16I, GL30C.GL_RG16UI,
                 GL30C.GL_RG32I, GL30C.GL_RG32UI,
                 GL30C.GL_RGB8I, GL30C.GL_RGB8UI, GL30C.GL_RGB16I, GL30C.GL_RGB16UI,
                 GL30C.GL_RGB32I, GL30C.GL_RGB32UI,
                 GL30C.GL_RGBA8I, GL30C.GL_RGBA8UI, GL30C.GL_RGBA16I, GL30C.GL_RGBA16UI,
                 GL30C.GL_RGBA32I, GL30C.GL_RGBA32UI,
                 GL33C.GL_RGB10_A2UI -> true;
            default -> false;
        };
    }

    /** GPU->GPU copy of a full 2D texture; binds nothing (GL-state invariant #1 safe). */
    private static void copy(int src, int dst, int w, int h) {
        GL43C.glCopyImageSubData(
            src, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
            dst, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
            w, h, 1
        );
    }

    /** @return the scratch pair for {@code idx}, or NULL if the driver rejected immutable storage for {@code fmt}. */
    private static int[] ensureScratch(int idx, int w, int h, int fmt) {
        int[] s = scratch.get(idx);
        if (s != null && s[2] == w && s[3] == h && s[4] == fmt) {
            return s;
        }
        if (s != null) {
            GL11.glDeleteTextures(s[0]);
            GL11.glDeleteTextures(s[1]);
            scratch.remove(idx);
        }
        int m = alloc(w, h, fmt);
        int a = (m == 0) ? 0 : alloc(w, h, fmt);
        if (m == 0 || a == 0) {
            if (m != 0) GL11.glDeleteTextures(m);
            if (a != 0) GL11.glDeleteTextures(a);
            return null; // format rejected -> save() skips this target (no leak, no error spam)
        }
        s = new int[]{m, a, w, h, fmt};
        scratch.put(idx, s);
        return s;
    }

    /**
     * Allocate an immutable-storage scratch GL texture matching {@code fmt} (the target's queried sized
     * internal format). DSA — binds NOTHING (26.2 GL-state invariant #1). Returns 0 (and self-cleans) if the
     * driver rejects immutable storage for the format (review-hardening; unreachable for real float temporal
     * targets, defensive for exotic-format packs).
     */
    private static int alloc(int w, int h, int fmt) {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate so the check below reads only our op */ }
        int id = GL45C.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(id, 1, fmt, w, h);
        if (GL11.glGetError() != GL11.GL_NO_ERROR) {
            GL11.glDeleteTextures(id);
            return 0;
        }
        return id;
    }

    /** Free all scratch textures. Called from the compat renderer's teardown (CLIENT_CLEANUP + renderer switch). */
    public static void teardown() {
        for (int[] s : scratch.values()) {
            GL11.glDeleteTextures(s[0]);
            GL11.glDeleteTextures(s[1]);
        }
        scratch.clear();
        savedList.clear();
    }
}
