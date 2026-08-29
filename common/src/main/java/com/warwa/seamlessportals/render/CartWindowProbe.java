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
    private static long aliasedKept;  // ...and the fixed lookup was the one actually used
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
        boolean wrapVerdict, boolean exactVerdict, boolean useExact
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
            // THE INVERTING COUNTER. `flipped` counts cases where the wrap lookup would have culled
            // an entity the exact lookup keeps — it is identical in both lever directions, because
            // the probe computes BOTH lookups regardless. What differs is which verdict was USED,
            // so this counts the entities the fix actually rescued: == flipped with the fix on,
            // and 0 with -PdisableDestEntitySectionExact. That is what lets a gate leg invert.
            if (useExact) {
                aliasedKept++;
            }
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
    /** Cart round 3: last extract-presence per tracked cart — log on CHANGE only. */
    private static final java.util.Map<Integer, Boolean> EXTRACT_LAST =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * ★ CART ROUND 3 (log-only): did this MAIN extract include a render state for each
     * seam-tracked cart? Splits "never extracted" from "extracted but not submitted".
     * Identification rides the mod's own clip-context attachment (set at extraction for
     * portal-colliding entities — exactly the tracked set).
     */
    public static void onMainExtract(
        net.minecraft.client.multiplayer.ClientLevel level,
        net.minecraft.client.renderer.state.level.LevelRenderState lrs
    ) {
        if (!com.warwa.seamlessportals.passthrough.AperturePassthroughLever.SEAM_CART_PROBE
            || level == null || lrs == null
            || PortalContextSwitch.isRenderingPortal
            || qouteall.imm_ptl.core.render.SecondaryWorldRenderCore.isDestExtracting) {
            return;
        }
        for (net.minecraft.world.entity.Entity e : level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart)) {
                continue;
            }
            boolean tracked = com.warwa.seamlessportals.passthrough.SeamCrossingRule
                    .anchorOf(e) != null
                || ((qouteall.imm_ptl.core.ducks.IEEntity) e).ip_getCollidingPortal() != null;
            if (!tracked) {
                continue;
            }
            boolean present = false;
            for (net.minecraft.client.renderer.entity.state.EntityRenderState st
                    : lrs.entityRenderStates) {
                if (((qouteall.imm_ptl.core.ducks.IEEntityRenderState) st)
                        .ip_getClipContextEntity() == e) {
                    present = true;
                    break;
                }
            }
            Boolean prev = EXTRACT_LAST.put(e.getId(), present);
            if (prev == null || prev != present) {
                LOGGER.info(TAG + "MAIN-EXTRACT cart id={} -> {} (states={}, pos={})",
                    e.getId(), present ? "PRESENT" : "*** ABSENT ***",
                    lrs.entityRenderStates.size(), e.position());
            }
        }
    }

    /** Round 6/7: last shouldRender verdict per (cart, source, dim) — change-only OUTSIDE the
     *  straddle window, unthrottled inside it. Round 6's id-only change map collapsed repeated
     *  {@code false} across passes ("main said false" vs "main never asked" were indistinguishable
     *  and a portal-pass false masked a main-pass false); the straddle window is ~20 ticks, so
     *  unthrottled there is bounded. */
    private static final java.util.Map<String, Boolean> SHOULD_RENDER_LAST =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean straddling(net.minecraft.world.entity.Entity entity) {
        return ((qouteall.imm_ptl.core.ducks.IEEntity) entity).ip_getCollidingPortal() != null;
    }

    private static boolean trackedCart(net.minecraft.world.entity.Entity entity) {
        return com.warwa.seamlessportals.passthrough.AperturePassthroughLever.SEAM_CART_PROBE
            && entity instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart
            && com.warwa.seamlessportals.passthrough.SeamRegistry
                .sectionHasSeam(entity.level(), entity.blockPosition());
    }

    /** ★ CART ROUND 6 (log-only): the dispatcher shouldRender verdict for near-seam minecarts. */
    public static void onShouldRender(
        net.minecraft.world.entity.Entity entity, boolean verdict, String source
    ) {
        if (!trackedCart(entity)) {
            return;
        }
        String key = entity.getId() + "|" + source + "|" + entity.level().dimension().identifier();
        Boolean prev = SHOULD_RENDER_LAST.put(key, verdict);
        if (straddling(entity) || prev == null || prev != verdict) {
            LOGGER.info(TAG + "SHOULD-RENDER cart id={} -> {} ({}) pass={} pos={} dim={}",
                entity.getId(), verdict ? "RENDER" : "*** CULLED ***", source,
                passName(), entity.position(), entity.level().dimension().identifier());
        }
    }

    private static String passName() {
        return qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()
            ? "portal-" + qouteall.imm_ptl.core.render.context_management
                .PortalRendering.getRenderingPortal().getId()
            : "MAIN";
    }

    /** Round 7: change map for out-of-straddle IS-VISIBLE lines, keyed (cart, extract level). */
    private static final java.util.Map<String, Boolean> IS_VISIBLE_LAST =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * ★ CART ROUND 7 (log-only): the FULL {@code LevelExtractor.isEntityVisible} verdict, per
     * extracting level, for near-seam minecarts — THE extraction question itself. Unthrottled
     * while the cart straddles a seam face. On a false verdict the vanilla conjuncts are
     * recomputed in place: {@code distanceOK} ({@code Entity.shouldRender(camX,camY,camZ)}) and
     * {@code frustumOK} ({@code frustum.isVisible(bb.inflate(0.5))}). If both hold, the excluder
     * is the dispatcher's IP force-false (an adjacent SHOULD-RENDER ip-forced line) or the
     * trailing section gate (no such line) — the pass name + adjacency attributes it. TOTAL
     * ABSENCE of IS-VISIBLE lines for a level while that level's frames flow = the cart is not
     * in that level's {@code entitiesForRendering()} at all.
     */
    public static void onIsEntityVisible(
        net.minecraft.client.multiplayer.ClientLevel extractLevel,
        net.minecraft.world.entity.Entity entity,
        net.minecraft.client.renderer.culling.Frustum frustum,
        double camX, double camY, double camZ, boolean verdict
    ) {
        if (!trackedCart(entity)) {
            return;
        }
        String levelDim = extractLevel == null
            ? "?" : extractLevel.dimension().identifier().toString();
        String key = entity.getId() + "|" + levelDim;
        Boolean prev = IS_VISIBLE_LAST.put(key, verdict);
        if (!straddling(entity) && prev != null && prev == verdict) {
            return;
        }
        String detail = "";
        if (!verdict) {
            boolean distanceOK = entity.shouldRender(camX, camY, camZ);
            boolean frustumOK = frustum.isVisible(entity.getBoundingBox().inflate(0.5));
            detail = " [distanceOK=" + distanceOK + " frustumOK=" + frustumOK
                + " -> " + (!distanceOK ? "DISTANCE"
                    : (!frustumOK ? "FRUSTUM" : "IP-FORCED-or-SECTION-GATE")) + "]";
        }
        LOGGER.info(TAG + "IS-VISIBLE cart id={} -> {} extractLevel={} pass={} straddling={}"
                + " pos={} dim={}{}",
            entity.getId(), verdict ? "VISIBLE" : "*** EXCLUDED ***", levelDim, passName(),
            straddling(entity), entity.position(),
            entity.level().dimension().identifier(), detail);
    }

    /** Round 5: is this state within one section of a seam (either the played or a far level)? */
    public static boolean nearSeam(net.minecraft.client.renderer.entity.state.EntityRenderState state) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        net.minecraft.core.BlockPos pos =
            net.minecraft.core.BlockPos.containing(state.x, state.y, state.z);
        return com.warwa.seamlessportals.passthrough.SeamRegistry.sectionHasSeam(mc.level, pos);
    }

    /**
     * ★ CART ROUND 5 (log-only, unthrottled): every near-seam minecart submit, with tag and
     * outcome — the complete per-frame anatomy in one run.
     */
    public static void onMinecartSubmit(
        net.minecraft.client.renderer.entity.state.EntityRenderState state,
        String tag, String outcome
    ) {
        LOGGER.info(TAG + "SUBMIT {} pass={} pos=({}, {}, {}) -> {}",
            tag,
            qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()
                ? "portal-" + qouteall.imm_ptl.core.render.context_management
                    .PortalRendering.getRenderingPortal().getId()
                : "MAIN",
            String.format("%.2f", state.x), String.format("%.2f", state.y),
            String.format("%.2f", state.z), outcome);
    }

    /** Cart round 2: last MAIN-pass verdict per seam section — log on CHANGE only. */
    private static final java.util.Map<Long, Boolean> MAIN_GATE_LAST =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * ★ CART ROUND 2 (log-only): the MAIN extract's section gate verdict for a seam section.
     * A FALSE here culls every entity in that section from the main pass with no other trace —
     * the candidate mechanism for "the cart vanishes at tip-touch". Logged on verdict change.
     */
    public static void onMainGate(net.minecraft.core.BlockPos pos, boolean verdict) {
        long key = net.minecraft.core.SectionPos.asLong(pos);
        Boolean prev = MAIN_GATE_LAST.put(key, verdict);
        if (prev == null || prev != verdict) {
            LOGGER.info(TAG + "MAIN-GATE seam section {} -> {} (a FALSE culls every entity in"
                    + " the section from the main pass)",
                net.minecraft.core.SectionPos.of(pos), verdict ? "VISIBLE" : "CULLED");
        }
    }

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

    /**
     * Entities the fix actually rescued this session — {@code flipped} restricted to calls where
     * the fixed lookup was the one used. Equals {@code flipped} with the fix on, and 0 under
     * {@code -PdisableDestEntitySectionExact}. The inverting quantity for a Defect A gate.
     */
    public static long aliasedKept() {
        return aliasedKept;
    }

    public static String lastVerdict() {
        return lastVerdict;
    }

    public static String counters() {
        return "gateCalls=" + gateCalls + " exactFalse=" + gateFalse
            + " disagree=" + disagreements + " flipped=" + flipped
            + " aliasedKept=" + aliasedKept
            + " wrapNull=" + wrapNull + " exactNull=" + exactNull;
    }

    /** Gametest leg teardown — a leg that inherits another leg's counters cannot assert coverage. */
    public static void reset() {
        gateCalls = 0;
        gateFalse = 0;
        disagreements = 0;
        flipped = 0;
        aliasedKept = 0;
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
