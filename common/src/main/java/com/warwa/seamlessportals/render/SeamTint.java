package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;

/**
 * SEAM_BAND_HANDOFF §4.1 — THE PER-PAINTER TINT (pixel-attribution instrument, DEFAULT-OFF,
 * {@code -PseamPainterTint}).
 *
 * <p>Twenty-seven rounds of logs attributed SUBMISSIONS; the user's eyes attributed PIXELS;
 * nothing connected them (the arc's lesson #1: "pixel bugs need pixel instruments"). This
 * instrument closes the gap with zero new delivery machinery: each painter bakes a distinct
 * colour into the {@link FrontClipping.Snapshot} that ALREADY travels with its draws (both
 * bracket mechanisms, the band painter's immediate draws, and the pass-wide ambient arms all
 * pair a Snapshot with their GL draws — the proven clip-plane pairing); the snapshot's tint
 * rides the same live store the clip plane rides; and the per-draw uploader
 * ({@code GlCommandEncoderClipMixin}) feeds it to a debug uniform injected into every vanilla
 * fragment shader ({@code ShaderCodeTransformation.transformFragment} — wrapper-main injection,
 * because the fragment sources are {@code #ifdef}-variant-heavy and the once-claimed
 * {@code vertexColor} anchor does not exist in all variants). One user lap then reads the
 * attribution off the screen:
 *
 * <ul>
 *   <li><b>RED</b> — main-pass real body (CASE-1, outer −ADJ clip)</li>
 *   <li><b>ORANGE</b> — main-pass projection (CASE-2 outside any portal pass)</li>
 *   <li><b>BLUE</b> — in-pass projection (CASE-2 under a portal pass, incl. DISABLED-clip draws)</li>
 *   <li><b>GREEN</b> — in-pass AMBIENT content: everything drawn under the pass's armed inner
 *       clip through the vanilla per-draw chokepoint (the in-pass vanilla body, block entities,
 *       particles)</li>
 *   <li><b>MAGENTA</b> — band painter piece 1 (real coordinates)</li>
 *   <li><b>CYAN</b> — band painter piece 2 (image coordinates)</li>
 *   <li><b>YELLOW, weaker</b> — the seam-cell block redraw ({@link SeamClipRenderer}), a
 *       first-class candidate for the wrong-side bleed (handoff §5 painter inventory)</li>
 *   <li><b>NO TINT on an artifact pixel</b> — the artifact is painted by NONE of the above:
 *       portal quad, sodium terrain, or something outside the inventory.</li>
 * </ul>
 *
 * <p>Byte-inert when the lever is off: shader sources untransformed (the fragment branch of the
 * compilation-cache mixin short-circuits on {@link #ENABLED}), snapshots carry zero tint, and
 * the uploader's tint block short-circuits on the same read.
 */
public final class SeamTint {

    private SeamTint() {}

    public static final boolean ENABLED = AperturePassthroughLever.SEAM_PAINTER_TINT;

    /** Mix strength for entity painters — unmistakable, but the texture stays readable. */
    private static final float STRENGTH = 0.65f;
    /** Weaker strength for the seam-cell redraw: it is constantly visible at every seam cell
     *  and must not mask a thin body-artifact drawn over it. */
    private static final float CELL_STRENGTH = 0.35f;

    /** CASE-1 main-pass real body — RED. Null-safe (a null outer plane stays null/unclipped). */
    public static FrontClipping.Snapshot mainBody(FrontClipping.Snapshot s) {
        return tint(s, 1.0f, 0.1f, 0.1f, STRENGTH);
    }

    /** CASE-2 projection outside a portal pass — ORANGE. */
    public static FrontClipping.Snapshot mainPassProjection(FrontClipping.Snapshot s) {
        return tint(s, 1.0f, 0.6f, 0.0f, STRENGTH);
    }

    /** CASE-2 projection inside a portal pass — BLUE. */
    public static FrontClipping.Snapshot inPassProjection(FrontClipping.Snapshot s) {
        return tint(s, 0.2f, 0.4f, 1.0f, STRENGTH);
    }

    /** The pass-wide ambient arm (everything the window pass draws under its inner clip through
     *  the vanilla chokepoint) — GREEN. */
    public static FrontClipping.Snapshot inPassAmbient(FrontClipping.Snapshot s) {
        return tint(s, 0.1f, 1.0f, 0.1f, STRENGTH);
    }

    /**
     * Band painter pieces — P1 (real coords) WHITE, P2 (image coords) CYAN.
     *
     * <p>★ P1 WAS MAGENTA AND IS NOW WHITE (round 29, the judge's discriminator). On the
     * 2026-08-19 lap-1 the user reported a thin RED sliver at the plane that vanished when the
     * painter was disabled. RED is the main-pass body's colour, so that read as a cross-painter
     * perturbation — which §1.1 clause 2 says is impossible, and for which no ordering path
     * exists: under the active {@code SUBMIT_ORDER_UNIFORM} mechanism the main-pass real body
     * draws inside the entity feature phases at order ≥ 1000, strictly BEFORE translucent
     * terrain and therefore strictly before this painter's second
     * {@code AFTER_TRANSLUCENT_TERRAIN} registration. Nothing the painter does can add
     * main-body fragments to the same frame.
     *
     * <p>The competing reading is perceptual: MAGENTA {@code (1,0,1)} and RED {@code (1,0.1,0.1)}
     * are both red-channel-dominant at 0.65 strength over a dark minecart, on a few-pixel sliver,
     * at speed — the only separating channel is blue. P1's measured signature fits the artifact
     * exactly: 3448 of 4881 P1 draws in that lap carried a plane near the camera, where P1
     * redraws the front half-space PLUS the band with a forward depth bias, so its ONLY new
     * pixels are the ±ADJ band at the plane.
     *
     * <p>WHITE removes the ambiguity outright. On the next painter-ON lap: sliver turns WHITE ⇒
     * it was P1 all along and there is no cross-painter defect; sliver stays RED ⇒ a genuine
     * perturbation, and the one code-supported candidate to chase is the GLOBAL
     * {@code phaseRegistry} ({@code PerEntityClipBracket:127-128}, "GLOBAL and NOT destructively
     * cleared per pass") against this painter's fresh {@code SubmitNodeStorage} scratches, which
     * {@code drawImmediateClipped} never registers and never evicts — if 26.2 pools
     * {@code FeatureRenderPhase} objects across storages, the main body's snapshot would be
     * pushed onto the painter's own draws.
     */
    public static FrontClipping.Snapshot bandPiece(FrontClipping.Snapshot s, boolean pieceOne) {
        return pieceOne
            ? tint(s, 1.0f, 1.0f, 1.0f, STRENGTH)
            : tint(s, 0.0f, 1.0f, 1.0f, STRENGTH);
    }

    /** The seam-cell block redraw — YELLOW, weaker. */
    public static FrontClipping.Snapshot seamCell(FrontClipping.Snapshot s) {
        return tint(s, 1.0f, 1.0f, 0.0f, CELL_STRENGTH);
    }

    private static FrontClipping.Snapshot tint(
        FrontClipping.Snapshot s, float r, float g, float b, float a
    ) {
        if (!ENABLED || s == null) {
            return s;
        }
        return new FrontClipping.Snapshot(s.x, s.y, s.z, s.w, s.enabled, r, g, b, a);
    }
}
