package com.warwa.seamlessportals.render;

import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import org.slf4j.Logger;

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

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * ★ ROUND 38 — PER-ENTITY MODE ({@code -PseamTintPerEntity}, on top of the tint itself).
     *
     * <p><b>Why the per-PAINTER palette cannot answer the open question.</b> The user's objection,
     * 2026-08-22: the face cut happens AT THE SEAM, which is exactly where painter identity
     * changes BY DESIGN — near half by the main-pass real body, far half by the projection. On the
     * role palette a colour change at the cut is therefore produced by the normal handoff AND by
     * a defect, identically, and no amount of staring separates them. The instrument was
     * confounded with the thing it was meant to measure.
     *
     * <p><b>What this mode changes.</b> The hue keys on the ENTITY, not the painter: every painter
     * that draws a given entity — main-pass real body, main-pass projection, in-pass projection —
     * bakes the SAME colour at the SAME strength. A crossing cow is therefore ONE FLAT COLOUR on
     * both sides of the seam, with no boundary where the painters hand over. The reading rule
     * becomes binary and confound-free:
     *
     * <ul>
     *   <li>silhouette solid in its colour ⇒ every painter that should cover it did;</li>
     *   <li>a HOLE showing GREEN (the pass-wide ambient arm) or raw terrain/sky ⇒ NOTHING painted
     *       those pixels — an absence, and the projection loop is where to look;</li>
     *   <li>a hole showing ANOTHER ENTITY's colour ⇒ the wrong entity's draw wins there.</li>
     * </ul>
     *
     * <p>The cost is that painter identity is unavailable this lap — deliberately, since that is
     * precisely the axis the seam confounds. WHETHER first; WHO on a follow-up lap, aimed by the
     * answer instead of guessing between six painters.
     *
     * <p>The ambient arm (GREEN) and the seam-cell redraw (YELLOW) keep their role colours: they
     * are not entity-scoped, and they are what a hole in an entity REVEALS — so they must stay
     * distinguishable from every entity hue. The palette below contains no green and no yellow.
     */
    public static final boolean PER_ENTITY =
        ENABLED && AperturePassthroughLever.SEAM_TINT_PER_ENTITY;

    /** Strong enough to be unmistakable in a photo; texture still faintly readable underneath. */
    private static final float PER_ENTITY_STRENGTH = 0.85f;

    /** Maximally separated hues, none of them green (ambient) or yellow (seam cell). */
    private static final float[][] ENTITY_PALETTE = {
        {1.0f, 0.05f, 0.05f},   // RED
        {0.15f, 0.35f, 1.0f},   // BLUE
        {1.0f, 0.05f, 1.0f},    // MAGENTA
        {1.0f, 1.0f, 1.0f},     // WHITE
    };
    private static final String[] PALETTE_NAMES = {"RED", "BLUE", "MAGENTA", "WHITE"};

    /**
     * Slot assignment in ORDER OF FIRST APPEARANCE, so the two entities of a crossing fixture take
     * the two most separated hues (RED, BLUE) instead of whatever their entity ids happen to hash
     * to. Every assignment is logged: the user reads this artifact off the screen and must be told
     * which colour is which entity — an unlogged mapping is an unreadable lap.
     */
    private static final java.util.Map<Integer, Integer> SLOTS = new java.util.HashMap<>();

    private static int slotFor(int entityId, String describe) {
        synchronized (SLOTS) {
            Integer existing = SLOTS.get(entityId);
            if (existing != null) {
                return existing;
            }
            int slot = SLOTS.size() % ENTITY_PALETTE.length;
            SLOTS.put(entityId, slot);
            LOGGER.info("[SEAM-TINT] id={} ({}) -> {}", entityId, describe, PALETTE_NAMES[slot]);
            return slot;
        }
    }

    /**
     * Arming banner. Printed from the render path's first tinted submit so that a silent lap can
     * never again be mistaken for a clean one when the instrument was simply never switched on —
     * that omission invalidated two laps of this session already.
     */
    public static void announceOnce() {
        if (ANNOUNCED.compareAndSet(false, true)) {
            LOGGER.info("[SEAM-TINT] ARMED tint={} perEntity={}", ENABLED, PER_ENTITY);
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean ANNOUNCED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private static FrontClipping.Snapshot entityTint(
        FrontClipping.Snapshot s, int entityId, String describe
    ) {
        announceOnce();
        float[] c = ENTITY_PALETTE[slotFor(entityId, describe)];
        return tint(s, c[0], c[1], c[2], PER_ENTITY_STRENGTH);
    }

    /** Mix strength for entity painters — unmistakable, but the texture stays readable. */
    private static final float STRENGTH = 0.65f;
    /** Weaker strength for the seam-cell redraw: it is constantly visible at every seam cell
     *  and must not mask a thin body-artifact drawn over it. */
    private static final float CELL_STRENGTH = 0.35f;

    /** CASE-1 main-pass real body — RED. Null-safe (a null outer plane stays null/unclipped). */
    public static FrontClipping.Snapshot mainBody(FrontClipping.Snapshot s) {
        return tint(s, 1.0f, 0.1f, 0.1f, STRENGTH);
    }

    /** CASE-1 main-pass real body, entity-keyed under {@link #PER_ENTITY} (round 38). */
    public static FrontClipping.Snapshot mainBody(
        FrontClipping.Snapshot s, int entityId, String describe
    ) {
        return PER_ENTITY ? entityTint(s, entityId, describe) : mainBody(s);
    }

    /** CASE-2 projection outside a portal pass — ORANGE. */
    public static FrontClipping.Snapshot mainPassProjection(FrontClipping.Snapshot s) {
        return tint(s, 1.0f, 0.6f, 0.0f, STRENGTH);
    }

    /** CASE-2 main-pass projection, entity-keyed under {@link #PER_ENTITY} (round 38). */
    public static FrontClipping.Snapshot mainPassProjection(
        FrontClipping.Snapshot s, int entityId, String describe
    ) {
        return PER_ENTITY ? entityTint(s, entityId, describe) : mainPassProjection(s);
    }

    /** CASE-2 projection inside a portal pass — BLUE. */
    public static FrontClipping.Snapshot inPassProjection(FrontClipping.Snapshot s) {
        return tint(s, 0.2f, 0.4f, 1.0f, STRENGTH);
    }

    /** CASE-2 in-pass projection, entity-keyed under {@link #PER_ENTITY} (round 38). */
    public static FrontClipping.Snapshot inPassProjection(
        FrontClipping.Snapshot s, int entityId, String describe
    ) {
        return PER_ENTITY ? entityTint(s, entityId, describe) : inPassProjection(s);
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

    /**
     * ★ ROUND 41 — NEUTRAL MODE ({@code -PseamTintNeutral}, on top of the tint).
     *
     * <p>MEASURED 2026-08-22, and the most important result of the arc: with the tint ON the face
     * cut is GONE across repeated slow crossings; with the tint OFF and NOTHING else changed, the
     * cut and the wrong-side bleed both return. A diagnostic that only adds colour cannot fix a
     * geometry bug — so the defect is not in any painter's logic (which is why 27 rounds of
     * painter audits all came back clean) but in the CLIP DELIVERY PIPELINE, and the tint
     * perturbs that pipeline in two ways that have nothing to do with colour:
     *
     * <ol>
     *   <li><b>Shader rewriting.</b> {@link ShaderCodeTransformation#transformFragment} runs only
     *       when {@link #ENABLED}, so every fragment program is recompiled — new program ids, new
     *       uniform locations, fresh {@code ClipUniformLocationCache} entries. A program whose
     *       clip-uniform location is {@code -1} takes the uploader's {@code else if (clipArmed)}
     *       branch and draws DEFINED-BUT-UNCLIPPED — a direct route to a wrong-side bleed.</li>
     *   <li><b>Snapshot identity.</b> With the tint on, every painter receives a FRESH Snapshot
     *       instance; with it off, {@code tint()} returns the caller's object unchanged — including
     *       the shared static {@code DISABLED_CLIP}, so many registrations alias one instance.</li>
     * </ol>
     *
     * <p>NEUTRAL mode keeps BOTH perturbations and removes only the colour (alpha 0): shaders are
     * still rewritten, snapshots are still fresh copies, the uniform is still uploaded — the frame
     * just looks untinted. It is the one-variable test that separates "the pipeline change fixes
     * it" from "the colours were masking it", and it costs a single lap.
     */
    public static final boolean NEUTRAL =
        ENABLED && AperturePassthroughLever.SEAM_TINT_NEUTRAL;

    private static FrontClipping.Snapshot tint(
        FrontClipping.Snapshot s, float r, float g, float b, float a
    ) {
        if (!ENABLED || s == null) {
            return s;
        }
        // NEUTRAL: identical machinery, invisible result — alpha 0 makes the shader's mix a no-op.
        return new FrontClipping.Snapshot(
            s.x, s.y, s.z, s.w, s.enabled, r, g, b, NEUTRAL ? 0.0f : a);
    }
}
