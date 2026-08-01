package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import org.jetbrains.annotations.Nullable;

/**
 * (e) DEFECT-A INSTRUMENT — the dest-pass ENTITY VISIBILITY gate
 * ({@code -Dseamlessportals.cartWindowProbe=true}, DEFAULT-OFF).
 *
 * <p><b>The question it exists to settle.</b> The user's 2026-07-28 live report: an empty minecart
 * DISAPPEARS from a SAME-DIM portal window as it crosses the seam, while the window's TERRAIN keeps
 * drawing. The hypothesis under test is that the entity is culled at EXTRACTION by
 * {@code LevelExtractor.isEntityVisible}'s trailing
 * {@code levelRenderer.isSectionCompiledAndVisible(entity.blockPosition())}, because the mod's
 * re-implementation of that gate resolves the section through
 * {@code ViewArea.getRenderSectionAt(BlockPos)} — which on {@link
 * qouteall.imm_ptl.core.render.ImmPtlViewArea} wraps the query with {@code positiveModulo} into the
 * CURRENT PRESET array and has no exact-node guard. The preset is re-centred on the dest camera for
 * CROSS-DIM only ({@code SecondaryWorldRenderCore:663}), so a far SAME-DIM destination aliases onto
 * an unrelated section near the player.
 *
 * <p><b>What it prints, and why that is the whole proof.</b> One line per disagreement between the
 * WRAP lookup and the EXACT lookup, naming the requested section, BOTH resolved section nodes and
 * BOTH mesh states. If the two nodes differ, the gate is provably answering about a different
 * section than the one the entity is in — which is the defect, stated in coordinates, with no
 * inference left over. A {@code flipped} count says how often that disagreement actually changed
 * the verdict from false to true, i.e. how many entities the wrap-around lookup was eating.
 *
 * <p><b>Limiter (mandatory — stated, not assumed).</b> The 2026-07-28 live round caught the
 * previous instrument emitting 40,104 lines plus 4,962 truncation notices, 96% of a 46k-line log,
 * because a per-tick probe shipped without a rate limit. This gate runs once per entity per portal
 * pass, i.e. potentially thousands of calls per second, and under the defect EVERY call disagrees.
 * So: counters are always cheap (plain longs, no allocation), and emission is capped at ONE detail
 * line and ONE summary line per {@link #EMIT_INTERVAL_MS}. Nothing is logged at all when the lever
 * is off — the mixin does not even perform the second lookup.
 *
 * <p>Render-thread confined by construction (the only caller is the visibility mixin, which runs
 * inside extract). Counters are plain fields for that reason; they are read only by the same
 * thread's summary emission and by the gametest's coverage assertion between frames.
 */
public final class CartWindowProbe {

    private CartWindowProbe() {}

    private static final org.slf4j.Logger LOGGER =
        com.mojang.logging.LogUtils.getLogger();
    private static final String TAG = "[RS-CARTWIN] ";

    /** Emission budget: at most one detail line and one summary line per this interval. */
    public static final long EMIT_INTERVAL_MS = 1000L;

    // ---- counters (always cheap; only written when the lever is on) ----------------------------
    private static long gateCalls;
    private static long gateFalse;
    private static long disagreements;
    private static long flipped;      // wrap said "cull", exact says "keep" — entities being eaten
    private static long wrapNull;
    private static long exactNull;

    private static long lastEmitMs;
    private static boolean emittedDetailThisWindow;

    /** Last verdict, for the gametest's coverage assertion. */
    private static volatile String lastVerdict = "NONE";

    /**
     * Called from {@link com.warwa.seamlessportals.mixin.client.LevelRendererEntityVisibilityMixin}
     * ONLY when the lever is on, with both resolutions of the same BlockPos already performed.
     *
     * @param wrap  what {@code ViewArea.getRenderSectionAt(pos)} returned (the pre-fix read)
     * @param exact what the exact coord-pinned {@code rawGet} returned (the fixed read)
     */
    public static void onDestGate(
        BlockPos pos, @Nullable RenderSection wrap, @Nullable RenderSection exact,
        boolean wrapVerdict, boolean exactVerdict
    ) {
        gateCalls++;
        if (!exactVerdict) {
            gateFalse++;
        }
        if (wrap == null) {
            wrapNull++;
        }
        if (exact == null) {
            exactNull++;
        }

        long wrapNode = wrap == null ? Long.MIN_VALUE : wrap.getSectionNode();
        long exactNode = exact == null ? Long.MIN_VALUE : exact.getSectionNode();
        boolean disagree = wrapNode != exactNode || wrapVerdict != exactVerdict;
        if (disagree) {
            disagreements++;
        }
        if (!wrapVerdict && exactVerdict) {
            flipped++;
            lastVerdict = "ALIASED@compiled";
        }
        else if (!exactVerdict) {
            lastVerdict = "CULL@exact-uncompiled";
        }
        else {
            lastVerdict = "KEEP";
        }

        long now = System.currentTimeMillis();
        if (now - lastEmitMs >= EMIT_INTERVAL_MS) {
            lastEmitMs = now;
            emittedDetailThisWindow = false;
            LOGGER.info(TAG
                    + "SUMMARY gateCalls={} exactFalse={} disagree={} flipped={} wrapNull={}"
                    + " exactNull={} sameDimExtracted={} sameDimSubmitted={} sameDimThrow={}",
                gateCalls, gateFalse, disagreements, flipped, wrapNull, exactNull,
                qouteall.imm_ptl.core.render.TeleportFlashProbe.sameDimEntitiesExtracted,
                qouteall.imm_ptl.core.render.TeleportFlashProbe.sameDimEntitiesSubmitted,
                qouteall.imm_ptl.core.render.TeleportFlashProbe.sameDimEntityThrow);
        }
        if (disagree && !emittedDetailThisWindow) {
            emittedDetailThisWindow = true;
            LOGGER.info(TAG
                    + "★ ALIASED LOOKUP at {} (section {},{},{}): wrap->{} mesh={} verdict={} |"
                    + " exact->{} mesh={} verdict={}",
                pos,
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()),
                describeNode(wrapNode), describeMesh(wrap), wrapVerdict,
                describeNode(exactNode), describeMesh(exact), exactVerdict);
        }
    }

    private static String describeNode(long node) {
        if (node == Long.MIN_VALUE) {
            return "null";
        }
        return SectionPos.x(node) + "," + SectionPos.y(node) + "," + SectionPos.z(node);
    }

    private static String describeMesh(@Nullable RenderSection section) {
        if (section == null) {
            return "-";
        }
        return section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED
            ? "UNCOMPILED" : "compiled";
    }

    // ---- gametest surface ---------------------------------------------------------------------

    /** Coverage: the gate must actually have run, or a green verdict means nothing. */
    public static long gateCalls() {
        return gateCalls;
    }

    /** How many entity-visibility verdicts the wrap-around lookup would have wrongly culled. */
    public static long flipped() {
        return flipped;
    }

    public static long disagreements() {
        return disagreements;
    }

    public static String lastVerdict() {
        return lastVerdict;
    }

    public static String counters() {
        return "gateCalls=" + gateCalls + " exactFalse=" + gateFalse
            + " disagree=" + disagreements + " flipped=" + flipped
            + " wrapNull=" + wrapNull + " exactNull=" + exactNull;
    }

    /** Gametest leg teardown — a leg that inherits another leg's counters cannot assert coverage. */
    public static void reset() {
        gateCalls = 0;
        gateFalse = 0;
        disagreements = 0;
        flipped = 0;
        wrapNull = 0;
        exactNull = 0;
        lastEmitMs = 0;
        emittedDetailThisWindow = false;
        lastVerdict = "NONE";
    }

    /** True when the probe lever is armed — the mixin's guard, hoisted for readability. */
    public static boolean armed() {
        return AperturePassthroughLever.CART_WINDOW_PROBE;
    }
}
