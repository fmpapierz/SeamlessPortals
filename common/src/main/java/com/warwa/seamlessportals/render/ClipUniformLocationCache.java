package com.warwa.seamlessportals.render;

import java.util.HashMap;
import java.util.Map;

/**
 * IS2 UNCONDITIONAL HARDENING (iris shaders-ON engagement; port-note
 * IS-iris-shaders-on §3.2): the {@code programId -> seamlessportals_ClipPlane
 * uniform location} cache shared between
 * {@link com.warwa.seamlessportals.mixin.client.GlCommandEncoderClipMixin}
 * (the per-draw uploader on the vanilla trySetup chokepoint),
 * {@code MixinSodiumGLDrawContext_ClipUpload} (the sodium per-pass uploader —
 * a second reader/writer since the IS3 §4.3 fold-in) and
 * {@link com.warwa.seamlessportals.mixin.client.GlDeviceClipCacheMixin}
 * (the invalidation seam). Program ids are globally unique and all three run
 * render-thread-only, so the shared map is coherent across both uploaders.
 *
 * <p><b>WHY INVALIDATION IS MANDATORY:</b> GL program ids are recycled.
 * {@code GlDevice.clearPipelineCache} (mc262 {@code GlDevice:261} — public;
 * called by {@code ShaderManager} on every resource reload, {@code :152/:162}
 * = F3+T / resource-pack apply, and by {@code GlDevice.close}) closes EVERY
 * cached {@code GlProgram} ({@code glDeleteProgram}). Freshly compiled
 * programs may then reuse a cached id with the clip uniform at a DIFFERENT
 * location (or absent). The stale cached location feeds {@code glUniform4f}
 * against the newly bound CURRENT program — a location that program never
 * issued — producing {@code GL_INVALID_OPERATION} spam and a real
 * wrong-uniform-write hazard. This is the leading suspect for the shaders-ON
 * GL error spam after pipeline-cache clears.
 *
 * <p><b>SEAM CHOICE (evidence, IS2):</b> a HEAD/RETURN mixin on
 * {@code GlDevice.clearPipelineCache} is the clean, verified target — the
 * single vanilla path that bulk-deletes the programs visible to
 * {@code GlCommandEncoder.trySetup} (the uploader's injection point). The
 * alternative fallback from the spec (per-hit {@code glIsProgram} validation)
 * is a no-op for this cache's shape: the looked-up id is always
 * {@code GL_CURRENT_PROGRAM}, which is by definition a live program — the
 * hazard is id REUSE, which {@code glIsProgram} cannot detect. Residual
 * ledgered exposure: iris deletes its own composite/gl programs via its own
 * {@code glDeleteProgram} (jar-verified: iris 1.11.2+26.2 never calls
 * {@code clearPipelineCache}), but those programs bind outside the vanilla
 * {@code trySetup} path this cache serves; the size cap below additionally
 * bounds growth from any id churn.
 *
 * <p>Render-thread-only ({@code trySetup} and {@code clearPipelineCache} both
 * run there) — a plain {@link HashMap} suffices.
 */
public final class ClipUniformLocationCache {

    private ClipUniformLocationCache() {}

    /**
     * Bounded-size belt: a full reset when the map would exceed this many
     * entries. Real sessions hold well under a hundred live programs; hitting
     * the cap implies heavy program churn (ids being recycled), exactly the
     * condition under which stale entries could accumulate — resetting is
     * both the memory bound and a staleness flush. A reset only costs one
     * {@code glGetUniformLocation} re-query per program on next use.
     */
    private static final int MAX_ENTRIES = 512;

    /** Cache: programId -> uniform location (or -1 if the program lacks it). */
    private static final Map<Integer, Integer> CACHE = new HashMap<>();

    /** Cached location for the program id, or {@code null} on miss. */
    public static Integer get(int programId) {
        return CACHE.get(programId);
    }

    /** Record the queried location (may be -1 = "not applicable"). */
    public static void put(int programId, int location) {
        if (CACHE.size() >= MAX_ENTRIES) {
            CACHE.clear();
        }
        CACHE.put(programId, location);
    }

    /**
     * Drop everything — called by
     * {@link com.warwa.seamlessportals.mixin.client.GlDeviceClipCacheMixin}
     * whenever {@code GlDevice.clearPipelineCache} has deleted the cached
     * programs (every cached id is stale from that point on).
     */
    public static void clear() {
        CACHE.clear();
    }

    /**
     * IS5-H PER-PROGRAM INVALIDATION (2026-07-23) — drop one entry when ITS program is deleted.
     * Called by {@code GlProgramClipCacheMixin} at {@code GlProgram.close()} HEAD — the UNIVERSAL
     * deletion funnel: vanilla {@code clearPipelineCache} closes its cached {@code GlProgram}s
     * through it, and iris's {@code ExtendedShader extends GlProgram} inherits {@code close()}
     * un-overridden (javap-confirmed), so IRIS-SIDE recompiles (in-game shader-settings applies —
     * the path that NEVER calls {@code clearPipelineCache}) are covered too. This closes the
     * ledgered IS3 V4-3 residual FOR REAL: it went acute live (2026-07-23: an in-game TAA re-enable
     * recycled iris program ids and the stale locations fed ~248k wrong-uniform {@code glUniform4f}
     * writes — "operation is invalid when the uniform is a matrix" spam + arbitrary uniform
     * corruption on live programs). The old "iris programs bind outside the trySetup path" ledger
     * rationale was REFUTED by probe v1 (iris terrain programs provably bind at trySetup).
     */
    public static void remove(int programId) {
        CACHE.remove(programId);
    }
}
