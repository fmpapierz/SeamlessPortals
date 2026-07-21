package com.warwa.seamlessportals.render;

/**
 * IS3 V6 FOLD — the pass-scoped full-pipeline clip OVERRIDE (port-note {@code IS-iris-shaders-on.md}
 * §4.1, the belt-swap reconciled against M4).
 *
 * <h2>Why this exists</h2>
 * The shaders-ON full-pipeline dest render (§4.1) arms the clip ONCE before
 * {@code destRenderer.render()} via {@code FrontClipping.setupInnerClipping}. But the vanilla
 * {@code LevelRenderer.render} runs {@code submitFeatures} (its ENTITY submit, BUILD phase) BEFORE
 * {@code executeFrameGraph} (where terrain draws). M4
 * ({@code MixinLevelRenderer_CrossPortalEntity}, class-woven onto the dest renderer) fires at the
 * {@code submitEntities} TAIL and calls {@code CrossPortalEntityRenderer
 * .onEndRenderingEntitiesAndBlockEntities} → {@code FrontClipping.disableClipping()}
 * UNCONDITIONALLY — which resets the live {@code com.warwa} store to the keep-all plane
 * {@code (0,0,0,1)} and clears {@code glClipEnabled} BEFORE any terrain executes. The per-draw
 * uploaders key their terrain-enable on {@code FrontClipping.capture().enabled}; post-disarm that
 * reads {@code false} and the store's plane reads keep-all, so terrain would render UNCLIPPED — the
 * exact symptom IS3 exists to fix would NOT be fixed (the belt-swap arm is defeated by M4).
 *
 * <h2>What it does</h2>
 * At the belt arm the full-pipeline core FREEZES the just-armed view-space plane here (only when the
 * belt actually armed a plane — a layer-0 null plane leaves this disarmed → unclipped, matching the
 * decomposed path). During the whole nested {@code render()} the two per-draw uploaders
 * ({@code GlCommandEncoderClipMixin} + {@code MixinSodiumGLDrawContext_ClipUpload}) consult THIS
 * override as an alternate arm source, so a terrain draw re-asserts {@code GL_CLIP_DISTANCE0} with
 * this frozen plane regardless of what M4 did to the live store, and a non-terrain draw suppresses
 * the enable (defined-and-unclipped). Terrain therefore clips even though M4 disarmed the live store
 * mid-pass. The core disarms this in its {@code finally} (with a save/restore so nested full-pipeline
 * passes stack correctly), and force-disables the raw GL cap at the pass boundary (the per-draw raw
 * {@code glEnable} toggles bypass the {@code com.warwa} {@code glClipEnabled} cache, so the store's
 * own {@code disableClipping()} can no-op while GL is left ON — the boundary must hard-disable).
 *
 * <h2>Neutrality</h2>
 * Armed ONLY inside {@code SecondaryWorldRenderCore.renderDestWorldFullPipeline} (the lever-gated
 * shaders-ON full-pipeline renderer). With the lever off that method never runs, {@link #isArmed()}
 * stays {@code false} forever, and both uploaders reduce to {@code override || capture().enabled} ==
 * {@code capture().enabled} — i.e. the decomposed / plain-sodium path is GL-state identical (one
 * added static-boolean read per draw, no GL delta). Render-thread-only (the belt-swap method and
 * both uploader seams all run there) — plain statics suffice.
 */
public final class FullPipelineClipState {

    private FullPipelineClipState() {}

    private static boolean armed = false;
    private static float planeX = 0.0f;
    private static float planeY = 0.0f;
    private static float planeZ = 0.0f;
    private static float planeW = 1.0f;

    /** Immutable capture for the core's per-pass save/restore (nested full-pipeline passes). */
    public static final class State {
        final boolean armed;
        final float x, y, z, w;
        State(boolean armed, float x, float y, float z, float w) {
            this.armed = armed;
            this.x = x; this.y = y; this.z = z; this.w = w;
        }
    }

    public static boolean isArmed() { return armed; }

    public static float getPlaneX() { return planeX; }
    public static float getPlaneY() { return planeY; }
    public static float getPlaneZ() { return planeZ; }
    public static float getPlaneW() { return planeW; }

    /** Freeze the belt-armed view-space plane for the duration of the full-pipeline pass. */
    public static void arm(float x, float y, float z, float w) {
        planeX = x; planeY = y; planeZ = z; planeW = w;
        armed = true;
    }

    public static void disarm() {
        armed = false;
        planeX = 0.0f; planeY = 0.0f; planeZ = 0.0f; planeW = 1.0f;
    }

    /** Snapshot the current override (before arming) so the core can restore it in its finally. */
    public static State save() {
        return new State(armed, planeX, planeY, planeZ, planeW);
    }

    /** Restore a saved override (nested-pass discipline; the outer pass's plane survives an inner). */
    public static void restore(State s) {
        armed = s.armed;
        planeX = s.x; planeY = s.y; planeZ = s.z; planeW = s.w;
    }
}
