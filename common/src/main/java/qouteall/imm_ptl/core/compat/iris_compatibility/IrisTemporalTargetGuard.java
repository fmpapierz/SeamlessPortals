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
import org.lwjgl.opengl.GL43C;
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

    private static boolean reflectionReady = false;
    private static boolean reflectionAttempted = false;
    private static Field fRenderTargets;
    private static Field fPackDirectives;

    /** colortex index -> {mainScratchId, altScratchId, w, h, glSizedFormat}. Scratch persists across frames. */
    private static final Map<Integer, int[]> scratch = new HashMap<>();

    /** per-save records, cleared each frame: {idx, dstMain, dstAlt, w, h, scratchMain, scratchAlt}. */
    private static final List<int[]> savedList = new ArrayList<>();

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
                savedList.add(new int[]{idx, dstMain, dstAlt, w, h, s[0], s[1]});
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
                // sv = {idx, dstMain, dstAlt, w, h, scratchMain, scratchAlt}
                copy(sv[5], sv[1], sv[3], sv[4]); // scratchMain -> dstMain
                copy(sv[6], sv[2], sv[3], sv[4]); // scratchAlt  -> dstAlt
            }
        } catch (Throwable t) {
            LOGGER.error("[Seamless Portals] iris temporal-guard restore() failed", t);
        } finally {
            savedList.clear();
        }
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
