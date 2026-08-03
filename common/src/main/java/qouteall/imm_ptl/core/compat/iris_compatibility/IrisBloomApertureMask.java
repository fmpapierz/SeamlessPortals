package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.mixin.GpuDeviceAccessor;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.pipeline.CompositePass;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.SecondaryWorldRenderCore;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * C3-BLOOM §2f — the APERTURE MASK: the dark-environment bloom-RING fix (adjudicated spec
 * {@code migration/C3BLOOM_ADJUDICATED_SPEC.md}, Design B, 2x adversarially verified
 * SOUND-WITH-FIXES — all folds applied here; user toggle-proven defect: Bloom OFF ⇒ ring GONE).
 *
 * <h2>The bug</h2>
 * The compat route renders each portal's DEST world through iris's full pipeline; the pack's own
 * composite chain then runs on that full-screen dest frame. Complementary's thresholdless bloom
 * tiles (composite4, reach ±896 px, BLOOM_FOG ×3 night / ×14 cave) gather energy from bright dest
 * content just OUTSIDE the window rectangle's screen footprint and deposit it onto pixels just
 * INSIDE it — the stamp then crops the aperture, keeping the contaminated in-window pixels = the
 * bloom ring hugging the window edge in dark environments.
 *
 * <h2>The fix (mutate-last aperture mask at the bloom-capture seam)</h2>
 * During each portal's nested dest render only — armed in
 * {@code IrisCompatOn262Renderer.doRenderPortal}, consumed inside iris's dest composite chain via
 * {@code MixinIrisCompositeRenderer_BloomApertureMask} (the single {@code Program.unbind()} seam
 * in {@code CompositeRenderer.renderAll}, fired once per pass iteration) — after the chain's LAST
 * colortex0 writer has drawn and BEFORE the next pass regenerates c0's mipmaps and gathers its
 * bloom tiles, mutate the c0 texture that next pass will read: copy it to scratch
 * ({@code glCopyImageSubData}), clear it to black ({@code glClearTexImage}), and repaint ONLY the
 * aperture footprint back from scratch — under DEPTH CLAMP, matching the stamp's raster state
 * (C4-SEAM: without it, the near-straddling crossing sliver rasterized for the stamp but not for
 * the mask, and the stamp copied the cleared black = the seam band) — the same
 * {@link ViewAreaRenderer#buildPortalViewAreaMesh}
 * geometry and the same layer-0 {@code P·MV} the stamp uses, fragment = {@code texelFetch} at
 * {@code gl_FragCoord} (idempotent per pixel), 5 NDC-offset draws dilating the keep-region ~1.5 px
 * so mask ⊇ stamp footprint unconditionally. composite4's tiles then gather ONLY window-visible
 * energy; composite5 tonemaps normally; the frame stays 100% pack-authored; the stamp is untouched.
 * No capture, no color replication, no pack math (zero licensing exposure).
 *
 * <h2>Why the seam is safe (spec §3.1, verified terminal-fate walk)</h2>
 * Outside-aperture dest pixels reach mainRT black via the pack's own {@code final}; the stamp
 * copies ONLY the aperture-mesh footprint and the blit-back restores the snapshot everywhere else;
 * the next per-portal / next-frame {@code beginLevelRendering} re-clears c0 (clear=true). The
 * clear=true precondition is GUARDED, not assumed (verifier-1 F1): at plan build the pack's
 * colortex0 clear directive is reflected ({@code RenderTargets.targetSettingsMap} →
 * {@code RenderTargetSettings.shouldClear()}, both javap-confirmed on the release jar) and a
 * clear=false pack shape DISARMS with a once-only WARN — on such packs the FinalPass swap chain
 * would actively propagate the masked black into the persistent side.
 *
 * <h2>Pack portability (honest — verifier-2 FIX4)</h2>
 * The fix addresses the "bloom gathered from colortex0 after c0's last write" family
 * (Complementary/BSL lineage, Motion Blur OFF). Outside the window the mutation is invisible on
 * ANY pack whose plan resolves (the terminal-fate walk + the F1 guard). IN-window, a foreign pack
 * with a post-last-writer NON-LOCAL c0 reader other than the bloom gatherer (color-sourced radial
 * godrays / DOF-from-c0 / distortion families) inherits the same window-frame-occludes semantics
 * as bloom: the effect truncates at the window edge — a behavior change, not garbage; the
 * {@code -Dseamlessportals.disableIrisBloomApertureMask} lever is the opt-out. Packs where the
 * bloom source is not post-last-write c0 (MB-ON Complementary — the amendment-3 INFO names the
 * DRAWBUFFERS:30 shape, compute-composite packs, bloom-in-final packs) disarm or mask inertly:
 * exactly today's ring, never worse.
 *
 * <h2>Ordering / state discipline (spec §3.3, bytecode-verified restore set)</h2>
 * MUTATE-LAST: all throwing work (plan build, program ensure, scratch/FBO ensure, mesh build,
 * GpuBuffer upload, matrix staging, copy) happens BEFORE the first destructive GL call (the
 * clear); the post-clear tail is raw non-throwing GL only, so a mid-mask throw cannot leave a
 * black window beyond one frame (and permanently disarms). Blend needs NO save/restore (the next
 * pass's {@code setupState()} re-establishes it unconditionally — amendment 2); cull is the ONLY
 * state nothing downstream re-establishes (query-save, GlStateManager-restore); the VAO is
 * restored via {@code FullScreenQuadRenderer.INSTANCE.bind()} — the mask binds through the SAME
 * {@code VertexArrayCache} iris rides ({@code GpuDeviceAccessor.getBackend() → GlDevice
 * .vertexArrayCache()}, instruction-identical to iris's own {@code bind()} bytecode), so the
 * cache stays coherent in and out. FBO/viewport/scissor/element-binding/samplers/colorMask are
 * all re-established per-pass by renderAll itself (decompile :302-338). The one-shot glGetError
 * drain after the mask PERMANENTLY DISARMS on a nonzero error (verifier-2 FIX2 — a persistent
 * black window with a climbing confirm-counter is the banned silent-regression class); a
 * clear-slate pre-drain at mask start (the guard's alloc() idiom) keeps a pre-existing queued
 * iris error from falsely tripping it.
 *
 * <h2>Cost / caches</h2>
 * Steady-state reflection ZERO (plan cached per {@code CompositeRenderer} instance in a
 * WeakHashMap — per-pipeline; cross-dim portals build per-dim plans). Mask-FBO cache keyed
 * (texId, w, h) (the FIX2 belt); ≤2 live entries per pipeline, up to 4 global with cross-dim
 * (verifier-1 F3) — nuked on every plan build (fresh pipeline = fresh texture ids) and destroyed
 * ({@code GlResource.destroy()}) at {@link #teardown()}, never ref-dropped. One scratch texture
 * keyed (w, h, fmt) (8.3 MB @1080p for R11F_G11F_B10F). Inert envelopes (shaders-OFF / stencil /
 * plain / flag-OFF / no-portal / lever-off / main pass / suite): the mixin hook costs one static
 * null check per pass ITERATION — ~10-15/frame across the 4 stage instances under Complementary
 * (verifier-1 F4), 0 when unwoven — byte-inert.
 *
 * <h2>Lifecycle</h2>
 * {@code arm()} at doRenderPortal layer 0 (pre-push — the same matrix/camera row the stamp uses);
 * {@code disarmAndReport()} unconditional in the pop finally (the arm never outlives its portal
 * window; armed-but-never-consumed ⇒ miss-counter + once-only WARN naming the reason).
 * {@link #teardown()} is IDEMPOTENT (called from both compat instances' teardown via
 * {@code onSwitchedAway} — double-call is the normal case) and frees program + scratch + FBOs.
 * Never throws into the render path (every public entry catches Throwable).
 */
@Environment(EnvType.CLIENT)
public final class IrisBloomApertureMask {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** GL gate (the guard's idiom): glCopyImageSubData (4.3) + glCreateTextures (4.5 DSA, also
     *  covers the glGetTextureLevelParameteri format query) + glClearTexImage (4.4). */
    private static final boolean GL_SUPPORTED =
        GL.getCapabilities().glCopyImageSubData != 0L
            && GL.getCapabilities().glCreateTextures != 0L
            && GL.getCapabilities().glClearTexImage != 0L;

    // ===== armed state (static single slot — sound: the compat renderer is one-layer-only, its
    // nested re-entry early-returns before the arm point) =========================================
    private static final class Armed {
        final Portal portal;
        final Matrix4f modelView;
        final Matrix4f projection;
        final Vec3 cameraPos;
        final float partialTick;
        boolean consumed = false;

        Armed(Portal portal, Matrix4f modelView, Matrix4f projection,
              Vec3 cameraPos, float partialTick) {
            this.portal = portal;
            this.modelView = modelView;
            this.projection = projection;
            this.cameraPos = cameraPos;
            this.partialTick = partialTick;
        }
    }

    private static Armed armed = null;

    // ===== permanent disarm (FIX2 + any throw) ===================================================
    private static boolean broken = false;
    private static String lastDisarmReason = null;

    // ===== reflection (two-flag latch, once-only WARN → disarm) ==================================
    private static boolean reflectAttempted = false;
    private static boolean reflectReady = false;
    private static Field fPasses;            // CompositeRenderer.passes (ImmutableList<Pass>)
    private static Field fCompositePass;     // CompositeRenderer.compositePass (CompositePass)
    private static Field fRenderTargets;     // CompositeRenderer.renderTargets (RenderTargets)
    private static Field fTargetSettingsMap; // RenderTargets.targetSettingsMap (F1 guard)
    private static Field fPassDrawBuffers;   // CompositeRenderer$Pass.drawBuffers (int[])
    private static Field fPassName;          // CompositeRenderer$Pass.name (String)
    private static Field fPassComputes;      // CompositeRenderer$Pass.computes (ComputeProgram[])
    private static Field fPassReadsFromAlt;  // CompositeRenderer$Pass.stageReadsFromAlt (ImmutableSet<Integer>)
    /** OPTIONAL — {@code CompositeRenderer$Pass.mipmappedBuffers} (ImmutableSet&lt;Integer&gt;).
     *  {@code null} ⇒ the IS5-BLOOMMB gatherer retarget is unavailable and {@link #buildPlan}
     *  resolves exactly the legacy index. Bound in its OWN try (see {@link #ensureReflection}). */
    private static Field fPassMipmapped;

    // ===== the per-pipeline plan =================================================================
    /** noop=true plans are permanent fast-path skips (BEGIN/PREPARE/DEFERRED instances, or a
     *  disarmed COMPOSITE walk — reason retained for the miss WARN). */
    private static final class MaskPlan {
        final boolean noop;
        final int maskIndex;
        final boolean readAlt;
        final String passName;
        final RenderTargets renderTargets;
        /** IS5-BLOOMMB: true when the gatherer retarget moved the index off `lastC0Writer + 1`.
         *  Reported on the LIVE line so an A/B leg can be attributed from the log alone. */
        final boolean retargeted;

        MaskPlan(boolean noop, int maskIndex, boolean readAlt,
                 String passName, RenderTargets renderTargets, boolean retargeted) {
            this.noop = noop;
            this.maskIndex = maskIndex;
            this.readAlt = readAlt;
            this.passName = passName;
            this.renderTargets = renderTargets;
            this.retargeted = retargeted;
        }
    }

    /** Per CompositeRenderer instance (per pipeline; GC'd with it — values hold no key ref). */
    private static final Map<CompositeRenderer, MaskPlan> planCache = new WeakHashMap<>();

    // ===== GL objects ============================================================================
    /** Mask-FBO cache key — (texId, w, h), the FIX2 belt against any stale-id/resize surprise. */
    private record FboKey(int texId, int w, int h) {}

    private static final Map<FboKey, GlFramebuffer> fboCache = new HashMap<>();

    /** One scratch slot: {id, w, h, fmt}; realloc on mismatch. */
    private static int scratchId = 0;
    private static int scratchW = 0;
    private static int scratchH = 0;
    private static int scratchFmt = 0;

    private static boolean programAttempted = false;
    private static boolean programReady = false;
    private static int maskProgram = 0;
    private static int locCombined = -1;
    private static int locNdcOffset = -1;
    private static int locSaved = -1;
    private static int locTint = -1;
    /** Persistent staging for the combined matrix — allocated once at program ensure so the
     *  post-clear tail (mutate-last) performs zero allocation for the upload. */
    private static FloatBuffer matBuf = null;

    // ===== once-only log latches (keyed by reason family — the two-flag-latch discipline; a
    // per-frame render-thread log is the known ~130ms log4j-stall class) ==========================
    private static final Set<String> warnedOnce = new HashSet<>();
    private static boolean missWarned = false;
    private static long lastProbeNanos = 0L;

    // ===== plan announcements: CONTENT-KEYED, not latched ========================================
    // These two were `boolean` latches, reset only in teardown(). teardown() runs from
    // PortalRenderer.switchRenderer -> onSwitchedAway, i.e. a RENDERER switch — NOT a pipeline
    // rebuild. An in-game shaderpack option change (every Motion-Blur toggle is one) mints a new
    // CompositeRenderer, so the WeakHashMap misses and a NEW plan is built and cached — and then
    // announced NOTHING, because the latch was still set from the previous pipeline.
    //
    // MEASURED, in this worktree's own log (run of 2026-08-02 22:47:14): one
    // `[C3-BLOOM] LIVE: pass=composite5 idx=4` at 22:47:36, followed by FOUR `Using shaderpack:`
    // pipeline rebuilds (22:48:22, 22:50:44, 22:51:14, 22:51:32) and ZERO re-announcements. The
    // reader cannot tell whether the live plan is still composite5/idx=4 or silently flipped to
    // composite4/idx=3 — which is the entire difference between "the mask runs BEFORE the bloom
    // gather" and "the mask runs after it and cannot suppress the ring".
    //
    // This exact class of staleness already cost one full false-refutation cycle
    // (MB_SMEAR_VERDICT.md §2: a stale MB-OFF plan line was read as evidence against the MB-ON
    // analysis). The teardown()-side reset added in response was the RIGHT idea in the WRONG
    // place. Keying the announcement on its own CONTENT removes the failure mode structurally: a
    // plan that differs from the last announced one always re-announces, wherever it was built,
    // and an identical plan never spams. Log-only; no render-path effect.
    private static String announcedLivePlan = null;
    private static String announcedMbShape = null;
    /** IS5-BLOOMMB: the whole-chain census, same content-keyed discipline. */
    private static String announcedPlanCensus = null;

    // ===== the mask GL program (§3.4 — trivial texelFetch passthrough; NO pack math) =============
    private static final String VERTEX_SRC = """
        #version 330 core
        layout(location = 0) in vec3 Position;   // POSITION_COLOR; Color (loc 1) deliberately unread
        uniform mat4 u_combined;
        uniform vec2 u_ndcOffset;
        void main() {
            vec4 p = u_combined * vec4(Position, 1.0);
            p.xy += u_ndcOffset * p.w;
            gl_Position = p;
        }
        """;
    private static final String FRAGMENT_SRC = """
        #version 330 core
        uniform sampler2D u_saved;
        uniform vec4 u_tint;   // white; debugTintBloomMask -> (1,0,1,1)
        out vec4 fragColor;
        void main() {
            fragColor = vec4(texelFetch(u_saved, ivec2(gl_FragCoord.xy), 0).rgb, 1.0) * u_tint;
        }
        """;

    private IrisBloomApertureMask() {}

    // =============================================================================================
    // Public lifecycle (all throw-safe)
    // =============================================================================================

    /**
     * Arm for ONE portal window (doRenderPortal layer 0, pre-push): the passing modelView + the
     * layer-0 draw projection + the current camera pos + partialTick — the stamp's exact
     * matrix/camera row ({@code IrisCompatPaste.stampPortalArea} args), so mask footprint and
     * stamp footprint share geometry by construction (scaled portals included).
     */
    public static void arm(Portal portal, Matrix4f modelView, Matrix4f projection,
                           Vec3 cameraPos, float partialTick) {
        armed = new Armed(portal, modelView, projection, cameraPos, partialTick);
    }

    /**
     * Unconditional disarm at the portal window's finally — the arm must never outlive its
     * portal. Armed-but-never-consumed (dormant mixin after an iris update, ineligible pack
     * shape, plan/permanent disarm) ⇒ miss-counter + once-only WARN naming the reason.
     */
    public static void disarmAndReport() {
        Armed a = armed;
        armed = null;
        if (a == null || a.consumed) {
            return;
        }
        IPGlobal.irisBloomMaskMissCount++;
        if (!missWarned) {
            missWarned = true;
            LOGGER.warn("[Seamless Portals] [C3-BLOOM] aperture mask armed but never consumed"
                    + " (once-only): {} — the bloom ring persists; behavior = pre-fix"
                    + " (A/B lever -Dseamlessportals.disableIrisBloomApertureMask)",
                lastDisarmReason != null ? lastDisarmReason
                    : "mixin dormant (iris drift?) or ineligible pack shape");
        }
        maybeProbe();
    }

    /**
     * The mixin's per-pass-iteration hook — fired at the single {@code Program.unbind()} in
     * {@code CompositeRenderer.renderAll}, i.e. AFTER pass {@code i}'s draw and BEFORE pass
     * {@code i}'s successor work (mip regen → setupState → draw of the NEXT iteration happens
     * when the loop reaches it; at iteration {@code i} the unbind precedes THAT pass's own mip
     * regen/draw). Cheapest-first: the armed null check is the ONLY cost on main-pass /
     * BEGIN/PREPARE/DEFERRED invocations. Never throws.
     */
    public static void onCompositePassBoundary(CompositeRenderer renderer, int i) {
        // IS5-MB gate probe — DELIBERATELY ABOVE the armed-window early return below, so the MAIN
        // composite chain is sampled too. Without a MAIN control row the DEST row is uninterpretable;
        // that control is what caught every wrong conclusion in this engagement. Default-OFF, log-only.
        com.warwa.seamlessportals.render.MbGateProbe.onPass(renderer, i);

        Armed a = armed;
        if (a == null) {
            return; // ~10-15 static null checks/frame across the 4 stage instances — byte-inert
        }
        if (a.consumed || broken) {
            return;
        }
        try {
            MaskPlan plan = planCache.get(renderer);
            if (plan == null) {
                plan = buildPlan(renderer);
                planCache.put(renderer, plan);
            }
            if (plan.noop || i != plan.maskIndex) {
                return;
            }
            if (!PortalRendering.isRendering()) {
                return; // belt: the armed window is exactly the pushed portal layer
            }
            runMask(a, plan);
        } catch (Throwable t) {
            broken = true;
            lastDisarmReason = "mask threw: " + t;
            warnOnce("throw", "[Seamless Portals] [C3-BLOOM] aperture mask THREW — feature"
                + " permanently disarmed (behavior = pre-fix bloom ring)", t);
        }
    }

    /** Free program + scratch + cached FBOs. IDEMPOTENT (onSwitchedAway double-calls via both
     *  compat instances) and best-effort; re-created lazily on next use. */
    public static void teardown() {
        armed = null;
        for (GlFramebuffer fbo : fboCache.values()) {
            try {
                fbo.destroy(); // GlResource.destroy (public final) — never ref-drop an FBO name
            } catch (Throwable t) {
                // disposal is best-effort
            }
        }
        fboCache.clear();
        if (scratchId != 0) {
            try {
                GL11.glDeleteTextures(scratchId);
            } catch (Throwable t) {
                // disposal is best-effort
            }
            scratchId = 0;
            scratchW = 0;
            scratchH = 0;
            scratchFmt = 0;
        }
        if (maskProgram != 0) {
            try {
                GL20C.glDeleteProgram(maskProgram);
            } catch (Throwable t) {
                // disposal is best-effort
            }
            maskProgram = 0;
            // a SUCCESSFUL program was torn down — allow lazy re-create next session (the
            // attempted latch only pins compile FAILURES)
            programAttempted = false;
            programReady = false;
        }
        planCache.clear();
        // Belt only. The announcements are CONTENT-keyed now (see their declarations), so they
        // re-emit on any real change without needing a reset here — which matters because this
        // method is NOT reached on a pipeline rebuild, only on a renderer switch. Clearing them
        // with the cache they describe keeps a fresh session announcing from a clean slate.
        announcedLivePlan = null;
        announcedMbShape = null;
        announcedPlanCensus = null;
    }

    // =============================================================================================
    // Plan build (per CompositeRenderer instance; reflection runs once per pipeline)
    // =============================================================================================

    private static MaskPlan noopPlan(String reason) {
        if (reason != null) {
            lastDisarmReason = reason;
        }
        return new MaskPlan(true, -1, false, null, null, false);
    }

    private static MaskPlan buildPlan(CompositeRenderer renderer) throws Exception {
        // Fresh plan = possibly fresh pipeline = fresh texture ids ⇒ the FBO cache must never
        // serve a stale id (ordering verified: the nuke precedes any ensure on the new plan).
        nukeFboCache();
        if (!ensureReflection()) {
            return noopPlan("reflection failed (iris field drift)");
        }
        CompositePass stage = (CompositePass) fCompositePass.get(renderer);
        if (stage != CompositePass.COMPOSITE) {
            // BEGIN/PREPARE/DEFERRED instances: permanent fast-path no-op, no WARN (normal).
            // ShadowCompositeRenderer is a separate class in net.irisshaders.iris.shadows
            // (verifier-1 F2) — never matched by the mixin's target at all.
            return new MaskPlan(true, -1, false, null, null, false);
        }
        ImmutableList<?> passes = (ImmutableList<?>) fPasses.get(renderer);
        RenderTargets rts = (RenderTargets) fRenderTargets.get(renderer);
        if (passes == null || passes.isEmpty() || rts == null) {
            return noopWarnPlan("empty composite chain / no render targets");
        }
        // F1 (verifier-1) — the c0-clear=false pack-shape guard: both terminal-fate legs
        // (beginLevelRendering re-clear + FinalPassRenderer swap exclusion) hold ONLY for
        // clear=true; a clear=false pack's swap pass would actively propagate the masked black.
        @SuppressWarnings("unchecked")
        Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> settings =
            (Map<Integer, PackRenderTargetDirectives.RenderTargetSettings>)
                fTargetSettingsMap.get(rts);
        PackRenderTargetDirectives.RenderTargetSettings s0 =
            settings == null ? null : settings.get(0);
        if (s0 == null || !s0.shouldClear()) {
            return noopWarnPlan("colortex0 is clear=false — aperture mask unsafe on this pack"
                + " shape; bloom ring left as today");
        }
        int lastC0Writer = -1;
        for (int p = 0; p < passes.size(); p++) {
            Object pass = passes.get(p);
            Object[] computes = (Object[]) fPassComputes.get(pass);
            if (computes != null) {
                for (Object c : computes) {
                    if (c != null) {
                        // Compute shaders can image-write c0 invisibly to the drawBuffers walk.
                        return noopWarnPlan("composite chain contains compute passes — c0 writer"
                            + " set undeterminable, mask disarmed");
                    }
                }
            }
            int[] drawBuffers = (int[]) fPassDrawBuffers.get(pass);
            if (drawBuffers == null) {
                continue; // ComputeOnlyPass shape (never sets drawBuffers) — no c0 color write
            }
            for (int db : drawBuffers) {
                if (db == 0) {
                    lastC0Writer = p;
                    break;
                }
            }
        }
        if (lastC0Writer < 0) {
            return noopWarnPlan("no colortex0 writer in the composite chain");
        }
        if (lastC0Writer == passes.size() - 1) {
            return noopWarnPlan("last colortex0 writer is the final composite pass — no safe"
                + " mask point (bloom likely gathered in final)");
        }
        // Amendment 3: the DRAWBUFFERS:30 motion-blur shape — a VALID plan whose gatherer runs
        // BEFORE the last c0 write (composite4 writes c0 itself when MB is on) regressed to
        // exactly today's ring. IS5-BLOOMMB (below) now RETARGETS that shape instead; this INFO
        // stays because it names the shape in the log and is what led to the diagnosis.
        int[] lastDb = (int[]) fPassDrawBuffers.get(passes.get(lastC0Writer));
        // Content-keyed (see the announcedMbShape declaration): the signature is recorded on EVERY
        // build, not only on the MB-shaped ones, so an ON -> OFF -> ON toggle re-announces rather
        // than staying silent on the second ON.
        String mbShape = "lastC0Writer=" + lastC0Writer + " db=" + java.util.Arrays.toString(lastDb);
        if (!mbShape.equals(announcedMbShape)) {
            announcedMbShape = mbShape;
            if (lastDb.length > 1) {
                LOGGER.info("[Seamless Portals] [C3-BLOOM] last colortex0 writer also writes other"
                    + " draw buffers (motion-blur shape, {}) — if the bloom ring persists, disable"
                    + " the pack's Motion Blur", mbShape);
            }
        }
        int maskIndex = lastC0Writer + 1;

        // ===== IS5-BLOOMMB — THE GATHERER RETARGET ==============================================
        // maskIndex = lastC0Writer + 1 assumes the last colortex0 WRITER sits before the bloom
        // GATHERER. Under Complementary Reimagined it does — until the pack's Motion Blur is on.
        // Then composite4, WHICH IS ITSELF THE GATHERER (composite4.glsl:65-84, BloomTile), flips
        // /* DRAWBUFFERS:3 */ to /* DRAWBUFFERS:30 */ (:180-184), becomes the last c0 writer, and
        // pushes the mask one pass PAST the gather. The mask then runs successfully every frame —
        // MEASURED masks=10148 misses=0 — and is completely inert.
        //
        //   MB OFF: lastC0Writer=2 (composite3, db=[0])   -> maskIndex 3, BEFORE the gather, works
        //   MB ON : lastC0Writer=3 (composite4, db=[3,0]) -> maskIndex 4, AFTER  the gather, inert
        //
        // USER A/B 2026-08-03, one variable, both directions: Bloom ON + MB OFF -> ring "goes
        // away"; Bloom ON + MB ON -> "comes back". The log printed the plan flipping idx=4 ->
        // idx=3 -> idx=4 at the two toggle moments. Full record: MB_BLOOM_SEAM_HANDOFF.md §7f.
        //
        // THE SIGNAL IS THE MIP DECLARATION, NOT THE DRAW BUFFERS. A pass that samples colortex0's
        // mip pyramid is non-local BY DEFINITION, and no pack can do it without declaring
        //   const bool colortex0MipmapEnabled = true;
        // which iris parses into Pass.mipmappedBuffers (javap-confirmed on the pinned jar). In
        // composite4.glsl that declaration is at :18, inside #ifdef FRAGMENT_SHADER and under NO
        // other conditional — so unlike the draw buffers it is MOTION-BLUR-INVARIANT, which is
        // precisely the property a selector needs here.
        //
        // WHY lastDb.length > 1 IS IN THE CONDITION, and it is not a gatherer test. It is a
        // blast-radius short-circuit that makes the SHIPPED, USER-CONFIRMED MB-OFF PATH
        // STRUCTURALLY UNREACHABLE by this change rather than merely unaffected in practice:
        // composite3.glsl:160 is /* DRAWBUFFERS:0 */, length 1, so with Motion Blur off the &&
        // short-circuits before passMipGathersC0 is ever called and the legacy index is produced
        // by code this block cannot reach. That is a proof, not a promise.
        //
        // WHAT WAS DELIBERATELY NOT USED:
        //  - "drawBuffers.length == 1" as the gatherer test — composite1.glsl:337 is
        //    /* DRAWBUFFERS:05 */, a default-reachable multi-buffer c0 write, and on a pack where
        //    every c0 write is multi-buffer the rule yields lastC0Writer = -1 and hard-disarms a
        //    working feature.
        //  - retargeting onto EVERY mip-gathering pass — composite3.glsl:19 declares the same
        //    mipmap constant under #if WORLD_BLUR > 0, and its DOF branch REPLACES the frame from
        //    18 c0 mip taps (:79 onward). Masking there would push a second non-local reader's
        //    dark fringe straight into the visible image. WORLD_BLUR defaults to 0
        //    (lib/common.glsl:162), but the narrow rule never selects composite3 regardless.
        //
        // BOUNDS: the `lastC0Writer == passes.size() - 1` disarm above runs BEFORE this, so the
        // override can only ever produce maskIndex <= passes.size() - 2. No new edge case.
        // Everything downstream (readAlt, passName, and the texture the mask mutates) is resolved
        // from passes.get(maskIndex), so it retargets itself with no further change.
        //
        // ★ THE DEFAULT IS PROVISIONAL. Masking before composite4 also blackens the source of its
        // motion blur, which samples colortex0 at LOD 0 with 9 taps clamped to the SCREEN, not to
        // the aperture (composite4.glsl:141), with reach linear in MOTION_BLURRING_STRENGTH (the
        // user runs 2.00, the slider maximum). The trade — a bright bloom ring for a possible dark
        // MB fringe inside the window — is ARITHMETICALLY ZERO AT REST (at velocity == 0 all nine
        // taps collapse onto the fragment's own texel), so a stationary A/B WILL PASS EVEN IF THE
        // FRINGE IS SEVERE. It must be judged under sustained fast yaw, with -PdebugTintBloomMask
        // on so the fringe reads as a magenta->black ramp that can be measured in pixels. If that
        // ramp is unacceptable this flag's default flips and the level-0 restore stage is built.
        if (!IPGlobal.BLOOM_MASK_GATHERER_RETARGET_DISABLED_LEVER
            && lastDb.length > 1
            && passMipGathersC0(passes.get(lastC0Writer))
        ) {
            maskIndex = lastC0Writer;
        }
        final boolean retargeted = maskIndex == lastC0Writer;

        Object maskPass = passes.get(maskIndex);
        @SuppressWarnings("unchecked")
        ImmutableSet<Integer> readsFromAlt = (ImmutableSet<Integer>) fPassReadsFromAlt.get(maskPass);
        boolean readAlt = readsFromAlt != null && readsFromAlt.contains(0);
        String passName = (String) fPassName.get(maskPass);
        announcePlan(passes, lastC0Writer, maskIndex, retargeted);
        return new MaskPlan(false, maskIndex, readAlt, passName, rts, retargeted);
    }

    /**
     * True when {@code pass} samples colortex0's mip pyramid — i.e. it is a NON-LOCAL colortex0
     * reader, which for this purpose is the definition of "the gatherer".
     *
     * <p>Returns false rather than throwing on every unhappy path, so an iris drift or an
     * unexpected pass shape degrades to the legacy index (today's behaviour) and never to a crash
     * in the render path.
     *
     * <p>The {@code instanceof Collection} test is the {@code ComputeOnlyPass} guard, not
     * defensive padding: {@code CompositeRenderer$ComputeOnlyPass extends Pass} (javap-confirmed as
     * a separate class on the pinned jar) and its construction path never assigns
     * {@code mipmappedBuffers}, so the field reads null there. The class already carries the twin
     * defence for {@code drawBuffers} a few lines above ("ComputeOnlyPass shape (never sets
     * drawBuffers)").
     */
    private static boolean passMipGathersC0(Object pass) {
        try {
            if (fPassMipmapped == null) {
                return false;
            }
            Object v = fPassMipmapped.get(pass);
            return (v instanceof java.util.Collection<?> c) && c.contains(0);
        }
        catch (Throwable t) {
            return false;
        }
    }

    /**
     * IS5-BLOOMMB — the per-pipeline PLAN census. Content-keyed like the other two announcements,
     * so a pipeline rebuild that changes the chain re-emits and one that does not stays silent.
     *
     * <p><b>Why this is not decoration.</b> Every A/B in this arc is adjudicated on which pass the
     * mask landed on relative to the gatherer, and until now the log could not say what the chain
     * looked like — only which index was chosen. That cost this arc one wrong verdict already: a
     * leg that disabled the mask was read as exonerating its mechanism, when the previous leg's log
     * already showed the mask landing past the gather and therefore inert by construction. The
     * {@code mip=} column is the specific thing that was missing.
     */
    private static void announcePlan(ImmutableList<?> passes, int lastC0Writer, int maskIndex,
                                     boolean retargeted) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("lastC0Writer=").append(lastC0Writer)
            .append(" maskIndex=").append(maskIndex)
            .append(retargeted ? " sel=gatherer" : " sel=legacy")
            .append(" mipField=").append(fPassMipmapped == null ? "UNAVAILABLE" : "ok")
            .append(" |");
        for (int p = 0; p < passes.size(); p++) {
            Object pass = passes.get(p);
            String name;
            String db;
            String mip;
            try {
                name = String.valueOf(fPassName.get(pass));
                int[] d = (int[]) fPassDrawBuffers.get(pass);
                db = d == null ? "null" : java.util.Arrays.toString(d);
                Object m = fPassMipmapped == null ? null : fPassMipmapped.get(pass);
                mip = m == null ? "-" : String.valueOf(m);
            }
            catch (Throwable t) {
                // A census row must never be able to break a plan build.
                name = "<unreadable>";
                db = "?";
                mip = "?";
            }
            sb.append(' ').append(p).append(':').append(name)
                .append(" db=").append(db).append(" mip=").append(mip).append(" |");
        }
        String plan = sb.toString();
        if (!plan.equals(announcedPlanCensus)) {
            announcedPlanCensus = plan;
            LOGGER.info("[C3-BLOOM] PLAN: {}", plan);
        }
    }

    private static MaskPlan noopWarnPlan(String reason) {
        warnOnce("plan:" + reason, "[Seamless Portals] [C3-BLOOM] aperture mask disarmed for this"
            + " pipeline: " + reason + " (behavior = pre-fix bloom ring)", null);
        return noopPlan(reason);
    }

    private static boolean ensureReflection() {
        if (reflectReady) return true;
        if (reflectAttempted) return false;
        reflectAttempted = true;
        try {
            fPasses = CompositeRenderer.class.getDeclaredField("passes");
            fPasses.setAccessible(true);
            fCompositePass = CompositeRenderer.class.getDeclaredField("compositePass");
            fCompositePass.setAccessible(true);
            fRenderTargets = CompositeRenderer.class.getDeclaredField("renderTargets");
            fRenderTargets.setAccessible(true);
            // F1 guard plumbing (javap-confirmed on the release jar: private final
            // Map<Integer, PackRenderTargetDirectives$RenderTargetSettings> targetSettingsMap).
            fTargetSettingsMap = RenderTargets.class.getDeclaredField("targetSettingsMap");
            fTargetSettingsMap.setAccessible(true);
            // CompositeRenderer$Pass is a source-private static nested class.
            Class<?> passClass = Class.forName("net.irisshaders.iris.pipeline.CompositeRenderer$Pass");
            fPassDrawBuffers = passClass.getDeclaredField("drawBuffers");
            fPassDrawBuffers.setAccessible(true);
            fPassName = passClass.getDeclaredField("name");
            fPassName.setAccessible(true);
            fPassComputes = passClass.getDeclaredField("computes");
            fPassComputes.setAccessible(true);
            fPassReadsFromAlt = passClass.getDeclaredField("stageReadsFromAlt");
            fPassReadsFromAlt.setAccessible(true);
            reflectReady = true;
            // IS5-BLOOMMB: mipmappedBuffers is OPTIONAL and is bound in its OWN try DELIBERATELY.
            // javap on the pinned jar (iris-1.11.2+26.2-fabric) confirms
            //   com.google.common.collect.ImmutableSet<java.lang.Integer> mipmappedBuffers;
            // package-private, non-final, declared beside stageReadsFromAlt. But an iris rename of
            // THIS field must not be able to take down the whole feature: inside the shared try
            // above, one NoSuchFieldException would fall through to the catch, leave reflectReady
            // false, and route every plan to noopPlan("reflection failed") — killing the MB-OFF
            // masking that is ALREADY SHIPPED AND USER-CONFIRMED WORKING. Nested, the same rename
            // degrades to "fPassMipmapped == null ⇒ no retarget ⇒ the ring returns under MB ON",
            // which is exactly today's behaviour and nothing worse.
            try {
                fPassMipmapped = passClass.getDeclaredField("mipmappedBuffers");
                fPassMipmapped.setAccessible(true);
            }
            catch (Throwable mip) {
                fPassMipmapped = null;
                warnOnce("reflect-mip", "[Seamless Portals] [C3-BLOOM] CompositeRenderer$Pass"
                    + ".mipmappedBuffers is unreadable (iris drift?) — the gatherer retarget is"
                    + " OFF, so the bloom ring returns when the pack's Motion Blur is on. The"
                    + " Motion-Blur-OFF masking is unaffected.", mip);
            }
        } catch (Throwable t) {
            warnOnce("reflect", "[Seamless Portals] [C3-BLOOM] iris reflection failed — aperture"
                + " mask disabled (behavior = pre-fix bloom ring)", t);
        }
        return reflectReady;
    }

    // =============================================================================================
    // The mask pass (§3.3 — cheapest-first, MUTATE-LAST)
    // =============================================================================================

    private static void runMask(Armed a, MaskPlan plan) {
        // ---- PHASE 1: fallible, NON-destructive ------------------------------------------------

        // Clear-slate pre-drain (the guard's alloc() idiom): the FIX2 post-mask drain PERMANENTLY
        // disarms on a nonzero error, so a pre-existing queued error from iris/elsewhere must not
        // be misattributed to the mask.
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain */ }

        if (!GL_SUPPORTED) {
            broken = true;
            lastDisarmReason = "GL 4.3/4.4/4.5 copy/clear/DSA entry points unavailable";
            warnOnce("glcap", "[Seamless Portals] [C3-BLOOM] required GL entry points missing —"
                + " aperture mask disabled", null);
            return;
        }
        RenderTarget c0 = plan.renderTargets.get(0);
        if (c0 == null) {
            broken = true;
            lastDisarmReason = "colortex0 RenderTarget is null";
            warnOnce("c0null", "[Seamless Portals] [C3-BLOOM] colortex0 is null — aperture mask"
                + " disabled", null);
            return;
        }
        // The consumed side by DEFINITION: the mask-index pass's sampler binds
        // flipped.contains(0) ? alt : main off its construction-snapshot stageReadsFromAlt
        // (IrisSamplers.addRenderTargetSamplers) — parity is never inferred.
        int tex = plan.readAlt ? c0.getAltTexture() : c0.getMainTexture();
        int w = c0.getWidth();
        int h = c0.getHeight();
        // The target's ACTUAL sized internal format from GL (the guard's idiom — robust against
        // driver format promotion; storage-valid + copy view-class compatible by construction).
        int fmt = GL45C.glGetTextureLevelParameteri(tex, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        if (isIntegerFormat(fmt)) {
            broken = true;
            lastDisarmReason = "colortex0 has an integer format (0x" + Integer.toHexString(fmt) + ")";
            warnOnce("intfmt", "[Seamless Portals] [C3-BLOOM] colortex0 is integer-formatted —"
                + " aperture mask disabled", null);
            return;
        }
        if (!ensureProgram()) {
            return; // ensureProgram disarmed + warned
        }
        if (!ensureScratch(w, h, fmt)) {
            broken = true;
            lastDisarmReason = "scratch texture allocation rejected (fmt 0x"
                + Integer.toHexString(fmt) + ")";
            warnOnce("scratch", "[Seamless Portals] [C3-BLOOM] scratch alloc failed — aperture"
                + " mask disabled", null);
            return;
        }
        GlFramebuffer maskFbo = ensureMaskFbo(tex, w, h);

        // Mesh — the stamp's EXACT geometry route (ViewAreaRenderer, incl. the S14.36 near-plane
        // clip): camera-relative POSITION_COLOR triangles. Tint = WHITE (the fragment ignores
        // vertex color; u_tint is the operative channel-killer) or MAGENTA under the probe lever.
        int vertexCount;
        GpuBufferSlice vertexSlice;
        Vec3 tint = IPGlobal.debugTintBloomMask ? new Vec3(1.0, 0.0, 1.0) : new Vec3(1.0, 1.0, 1.0);
        try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(
            256 * DefaultVertexFormat.POSITION_COLOR.getVertexSize()
        )) {
            MeshData mesh = ViewAreaRenderer.buildPortalViewAreaMesh(
                tint, a.portal, a.cameraPos, a.partialTick, a.modelView, byteBuffer
            );
            if (mesh == null) {
                // Every triangle near-plane-clipped away — the stamp skips identically, so an
                // unmasked chain changes nothing visible: count as a (vacuous) mask.
                a.consumed = true;
                IPGlobal.irisBloomMaskCount++;
                maybeProbe();
                return;
            }
            try (mesh) {
                vertexCount = mesh.drawState().vertexCount();
                // Frame-transient buffer (S14.30 ledger — freed at the render TAIL; the draw is
                // synchronous, so the ledger can never free a referenced name early).
                vertexSlice = SecondaryWorldRenderCore.registerFrameTransientUbo(
                    RenderSystem.getDevice().createBuffer(
                        () -> "seamlessportals_bloommask_mesh",
                        GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer()
                    )
                );
            }
        }
        // Stage the combined clip transform NOW (mutate-last: zero allocation/JOML work after
        // the clear): projection * modelView — the stamp's :271 column-form (clip = P·MV·pos).
        matBuf.clear();
        new Matrix4f(a.projection).mul(a.modelView).get(matBuf);

        // Last fallible-op: the c0 → scratch copy (same queried format ⇒ view-class compatible;
        // binds nothing — GL-state invariant #1 safe).
        GL43C.glCopyImageSubData(
            tex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
            scratchId, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
            w, h, 1
        );

        // ---- PHASE 2: destructive — raw NON-THROWING GL only -----------------------------------

        // 7. Clear c0 to black (binds nothing; alpha-less float formats take GL_RGBA/GL_FLOAT).
        GL44C.glClearTexImage(tex, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);

        // 8. State: cull is the ONLY state nothing downstream re-establishes — query-save
        // (read-only = cache-safe), GlStateManager-restore. Blend: amendment 2 — NO restore
        // (the next pass's setupState() re-establishes unconditionally); just off for our draw.
        boolean cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        maskFbo.bind(); // routes GlStateManager._glBindFramebuffer — the composite passes' idiom
        GlStateManager._viewport(0, 0, w, h);
        GlStateManager._disableScissorTest(); // belt — the previous iteration already disabled it
        GlStateManager._disableBlend(0);
        GlStateManager._disableCull(); // one-sided aperture mesh; stamp parity (.withCull(false))
        GlStateManager._glUseProgram(maskProgram);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(scratchId); // texelFetch ignores sampler objects; the next
        // pass's ProgramSamplers.update() re-binds its units; renderAll's tail resets the cache

        // 9. VAO through the SAME cache iris rides (FullScreenQuadRenderer.bind()'s disassembled
        // pattern, instruction-identical) — coherence in; restored via the quad bind below.
        ((GlDevice) ((GpuDeviceAccessor) RenderSystem.getDevice()).getBackend())
            .vertexArrayCache().bindVertexArray(
                new VertexFormat[]{DefaultVertexFormat.POSITION_COLOR},
                new GpuBufferSlice[]{vertexSlice},
                null
            );
        GL20C.glUniformMatrix4fv(locCombined, false, matBuf);
        GL20C.glUniform1i(locSaved, 0);
        if (IPGlobal.debugTintBloomMask) {
            GL20C.glUniform4f(locTint, 1.0f, 0.0f, 1.0f, 1.0f);
        } else {
            GL20C.glUniform4f(locTint, 1.0f, 1.0f, 1.0f, 1.0f);
        }
        // 5 dilated repaints (~1.5 px): mask-keep ⊇ stamp footprint against cross-program
        // rasterization non-invariance + the C4 +0.01 overhang. Idempotent fragment ⇒ overdraw
        // harmless. Unrolled — zero allocation in the post-clear tail.
        // debugBloomMaskBlackout skips all 5 (clear-only footprint/crop proof leg).
        //
        // C4-SEAM DEPTH CLAMP (2026-07-27 — THE BLACK SEAM BAND's root cause, attributed live):
        // the stamp rasterizes this IDENTICAL mesh under CHelper.enableDepthClamp (the
        // doRenderPortal bracket), and the S14.36 CPU clip cuts at the CAMERA plane (viewZ <
        // -EPS), NOT the 0.05 near plane — so during a crossing the 0..5 cm shell of the aperture
        // survives to the GPU, where the stamp (clamp ON) keeps it and this repaint (clamp OFF)
        // lost it to hardware near clipping: mask ⊉ stamp exactly at the seam, and the stamp
        // copied the mask's cleared-black c0 there = the band (shaders-ON only; the content probe
        // measured black COLOR over normal geometry depth, and -PdisableIrisBloomApertureMask
        // killed the band live). Clamp brackets the repaints only, via the SAME CHelper pair the
        // stamp uses (both gated on enableClippingMechanism — exact raster-state parity), and is
        // left DISABLED after, matching the composite chain's ambient state.
        // A/B: -PdisableBloomMaskSeamClamp reverts to the unclamped repaints (band returns).
        if (!IPGlobal.debugBloomMaskBlackout) {
            boolean seamClamp = !IPGlobal.BLOOM_MASK_SEAM_CLAMP_DISABLED_LEVER;
            if (seamClamp) {
                qouteall.imm_ptl.core.CHelper.enableDepthClamp();
            }
            // try/finally per the StencilPortalRenderer clamp-bracket precedent: the body is raw
            // non-throwing GL, but the class-wide catch would otherwise swallow a throw and leak
            // clamp ENABLED into the rest of the composite chain for the frame.
            try {
                float dx = 3.0f / w;
                float dy = 3.0f / h;
                GL20C.glUniform2f(locNdcOffset, 0.0f, 0.0f);
                GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
                GL20C.glUniform2f(locNdcOffset, dx, dy);
                GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
                GL20C.glUniform2f(locNdcOffset, -dx, -dy);
                GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
                GL20C.glUniform2f(locNdcOffset, dx, -dy);
                GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
                GL20C.glUniform2f(locNdcOffset, -dx, dy);
                GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
            } finally {
                if (seamClamp) {
                    qouteall.imm_ptl.core.CHelper.disableDepthClamp();
                }
            }
        }

        // 10. Restore. VAO: MANDATORY quad re-bind (every subsequent composite draw rides it —
        // same VertexArrayCache in, same out). Program: _glUseProgram(0) matches the state the
        // injected-before Program.unbind() establishes right after we return (idempotent). Cull:
        // per the query-save. FBO/viewport/scissor/element-binding/samplers/colorMask need NO
        // restore — the mask-index pass's own iteration rebinds all of them before its draw, and
        // the intervening mip regen is DSA (no binding dependency).
        FullScreenQuadRenderer.INSTANCE.bind();
        GlStateManager._glUseProgram(0);
        if (cullWasEnabled) {
            GlStateManager._enableCull();
        } else {
            GlStateManager._disableCull();
        }

        // 11. Commit + liveness + the FIX2 error drain.
        a.consumed = true;
        IPGlobal.irisBloomMaskCount++;
        // Content-keyed (see the announcedLivePlan declaration). Re-announces whenever ANY field
        // changes — pack option toggle, pipeline rebuild, window resize — and never repeats an
        // identical line, so the newest LIVE line in a log is always the plan in force.
        String livePlan = (plan.retargeted ? "sel=gatherer " : "sel=legacy ")
            + "pass=" + plan.passName + " idx=" + plan.maskIndex
            + " reads=" + (plan.readAlt ? "ALT" : "MAIN")
            + " tex=" + tex + " " + w + "x" + h + " fmt=0x" + Integer.toHexString(fmt);
        if (!livePlan.equals(announcedLivePlan)) {
            announcedLivePlan = livePlan;
            LOGGER.info("[C3-BLOOM] LIVE: {}", livePlan);
        }
        // FIX2 (verifier-2): a nonzero error here (e.g. dropped repaint draws on an incomplete
        // FBO) means the clear may have landed WITHOUT the repaint — a persistent-black-window
        // state whose confirm-counter keeps climbing is the exact banned silent-regression
        // class. PERMANENT disarm (one bad frame, then today's ring), not just a WARN.
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* drain the rest */ }
            broken = true;
            lastDisarmReason = "GL error 0x" + Integer.toHexString(err) + " during the mask pass";
            warnOnce("glerr", "[Seamless Portals] [C3-BLOOM] mask pass raised GL error 0x"
                + Integer.toHexString(err) + " — feature permanently disarmed (behavior = pre-fix"
                + " bloom ring from the next frame)", null);
        }
        maybeProbe();
    }

    // =============================================================================================
    // GL object ensure/teardown helpers
    // =============================================================================================

    private static boolean ensureProgram() {
        if (programReady) return true;
        if (programAttempted) return false;
        programAttempted = true;
        try {
            int vs = compileShader(GL20C.GL_VERTEX_SHADER, VERTEX_SRC, "vertex");
            int fs = compileShader(GL20C.GL_FRAGMENT_SHADER, FRAGMENT_SRC, "fragment");
            int prog = GL20C.glCreateProgram();
            GL20C.glAttachShader(prog, vs);
            GL20C.glAttachShader(prog, fs);
            GL20C.glLinkProgram(prog);
            GL20C.glDeleteShader(vs);
            GL20C.glDeleteShader(fs);
            if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) != GL11.GL_TRUE) {
                String log = GL20C.glGetProgramInfoLog(prog);
                GL20C.glDeleteProgram(prog);
                throw new IllegalStateException("mask program link failed: " + log);
            }
            locCombined = GL20C.glGetUniformLocation(prog, "u_combined");
            locNdcOffset = GL20C.glGetUniformLocation(prog, "u_ndcOffset");
            locSaved = GL20C.glGetUniformLocation(prog, "u_saved");
            locTint = GL20C.glGetUniformLocation(prog, "u_tint");
            matBuf = BufferUtils.createFloatBuffer(16);
            maskProgram = prog;
            programReady = true;
            return true;
        } catch (Throwable t) {
            broken = true;
            lastDisarmReason = "mask GL program compile/link failed";
            warnOnce("compile", "[Seamless Portals] [C3-BLOOM] mask program compile failed —"
                + " aperture mask disabled", t);
            return false;
        }
    }

    private static int compileShader(int type, String src, String label) {
        int id = GL20C.glCreateShader(type);
        GL20C.glShaderSource(id, src);
        GL20C.glCompileShader(id);
        if (GL20C.glGetShaderi(id, GL20C.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            String log = GL20C.glGetShaderInfoLog(id);
            GL20C.glDeleteShader(id);
            throw new IllegalStateException(label + " shader compile failed: " + log);
        }
        return id;
    }

    /** One scratch slot keyed (w, h, fmt); DSA alloc (binds nothing); realloc on mismatch.
     *  Returns false if the driver rejects immutable storage for the format. */
    private static boolean ensureScratch(int w, int h, int fmt) {
        if (scratchId != 0 && scratchW == w && scratchH == h && scratchFmt == fmt) {
            return true;
        }
        if (scratchId != 0) {
            GL11.glDeleteTextures(scratchId);
            scratchId = 0;
        }
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* clear slate for the alloc check */ }
        int id = GL45C.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(id, 1, fmt, w, h);
        if (GL11.glGetError() != GL11.GL_NO_ERROR) {
            GL11.glDeleteTextures(id);
            return false;
        }
        scratchId = id;
        scratchW = w;
        scratchH = h;
        scratchFmt = fmt;
        return true;
    }

    /** Mask FBO for a c0 texture — cache keyed (texId, w, h) (FIX2 belt). ≤2 live per pipeline
     *  (main+alt), ≤4 global with cross-dim (F3); nuked on plan build + teardown. */
    private static GlFramebuffer ensureMaskFbo(int texId, int w, int h) {
        FboKey key = new FboKey(texId, w, h);
        GlFramebuffer fbo = fboCache.get(key);
        if (fbo == null) {
            fbo = new GlFramebuffer();
            fbo.addColorAttachment(0, texId);
            fbo.drawBuffers(new int[]{0});
            fboCache.put(key, fbo);
        }
        return fbo;
    }

    private static void nukeFboCache() {
        for (GlFramebuffer fbo : fboCache.values()) {
            try {
                fbo.destroy();
            } catch (Throwable t) {
                // disposal is best-effort
            }
        }
        fboCache.clear();
    }

    /** Integer sized internal formats: glClearTexImage with GL_RGBA/GL_FLOAT would be
     *  GL_INVALID_OPERATION and the repaint math is undefined — disarm instead (the
     *  IrisTemporalTargetGuard format table). */
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

    // =============================================================================================
    // Logging
    // =============================================================================================

    /** Once-only per reason family (the two-flag-latch discipline generalized). */
    private static void warnOnce(String key, String msg, Throwable t) {
        if (!warnedOnce.add(key)) {
            return;
        }
        if (t != null) {
            LOGGER.warn(msg, t);
        } else {
            LOGGER.warn(msg);
        }
    }

    /** FIX3 (verifier-2): the 1Hz counter probe — without it protocol step 1 ("counter
     *  climbing") is unjudgeable from the log. Default OFF; 1Hz max (the log4j stall rule). */
    private static void maybeProbe() {
        if (!IPGlobal.BLOOM_MASK_PROBE) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastProbeNanos >= 1_000_000_000L) {
            lastProbeNanos = now;
            LOGGER.info("[C3-BLOOM] masks={} misses={}",
                IPGlobal.irisBloomMaskCount, IPGlobal.irisBloomMaskMissCount);
        }
    }
}
