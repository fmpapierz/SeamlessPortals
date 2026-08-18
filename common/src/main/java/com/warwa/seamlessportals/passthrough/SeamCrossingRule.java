package com.warwa.seamlessportals.passthrough;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.collision.PortalCollisionEntry;
import qouteall.imm_ptl.core.collision.PortalCollisionHandler;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.Plane;

/**
 * THE SEAM CROSSING RULE MODULE — the single decision authority for how an entity mid-seam-crossing
 * is booked, kept, and drawn. Governing spec: {@code migration/SEAM_ENTITY_ENGINE_DESIGN.md}
 * (the 2026-08-17 adversarially-judged engine design; §1.3 is this API, §5 lists the inline gates
 * this module replaced).
 *
 * <p><b>STAGE 0 STATE (bit-identical refactor):</b> every method body below is the corresponding
 * inline gate's logic moved VERBATIM from its former call site (cited per method). No behavior
 * change — the suite proves it. Stage 1 flips the five verdict deltas one at a time under
 * {@code -PseamResolver=shadow}; stages 2a/2b/3/4 layer the anchor, the band painter, the scoped
 * retreat, and the deletions per the design's staged plan.
 *
 * <p><b>THE CONSTITUTION (design §1.1-4, enforced from stage 1 onward):</b> verdicts derive only
 * from the entity's box, the face's own plane+transform, and the pass's armed clip + window
 * geometry. Forbidden inputs on the seam path: portal OBJECT identity (no {@code ==}, no
 * {@code isFlippedPortal}/{@code isReversePortal} against the rendering portal), entry order,
 * {@code getContentDirection}, and any binary eye/center-side test. Stage 0 still HOSTS legacy
 * forms of some of these (moved verbatim); they die on schedule, not ad hoc.
 */
public final class SeamCrossingRule {

    private SeamCrossingRule() {}

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // STAGE 1 SHADOW MODE (design §6 stage 1): under -DseamResolver=shadow the new-form verdicts
    // run alongside the legacy ones; every divergence logs (volume-capped per the
    // scoped-suppression lesson). A zero-divergence live lap gates each flip.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    private static int shadowLines = 0;
    private static final int SHADOW_LINE_CAP = 300;

    private static void shadowDiverge(String delta, String detail) {
        if (!AperturePassthroughLever.SEAM_RESOLVER_SHADOW) {
            return;
        }
        if (shadowLines >= SHADOW_LINE_CAP) {
            return;
        }
        shadowLines++;
        LOGGER.info("[SEAM-RULE] SHADOW-DIVERGE {} {}{}", delta, detail,
            shadowLines == SHADOW_LINE_CAP ? " (cap reached; further divergences suppressed)" : "");
    }

    /**
     * Delta (b)+(c) shadow: the design's in-pass projection admission — locality is checked
     * downstream (E9); here the SIDE AGREEMENT half: {@code n_innerImage · passKeptNormal > 0}
     * (the projection clip's kept normal at the image's station vs the pass's armed clip
     * normal). Called from renderProjectedEntity's in-pass branch at each legacy decision
     * point with the legacy outcome; logs when the new form disagrees.
     */
    public static void shadowInPassProjection(
        Portal renderingPortal, Portal collidingPortal,
        @Nullable Plane imageInnerClip, boolean legacyDrawn, String legacyReason
    ) {
        if (!AperturePassthroughLever.SEAM_RESOLVER_SHADOW) {
            return;
        }
        if (!SeamCartContinuity.isSeamContinuous(collidingPortal)) {
            return;
        }
        Plane passClip = renderingPortal.getInnerClipping();
        boolean newDrawn = imageInnerClip != null && passClip != null
            && imageInnerClip.normal().dot(passClip.normal()) > 0;
        if (newDrawn != legacyDrawn) {
            shadowDiverge("b/c-inpass-projection",
                "face=" + collidingPortal.getId() + " pass=" + renderingPortal.getId()
                    + " legacy=" + legacyDrawn + "(" + legacyReason + ") new=" + newDrawn);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // STAGE 2a — THE ANCHOR LAYER (design §3): the crossing's one irreducible history bit,
    // stored on the entity (SeamCrossingHolder), epoch-guarded, rider-inherited per tick.
    // ═════════════════════════════════════════════════════════════════════════════════════════

    @Nullable
    public static Portal anchorOf(Entity entity) {
        return SeamCrossingHolder.of(entity).seamlessportals$getAnchorFace();
    }

    /** Is this face the entity's (or its unit root's) live anchor? */
    public static boolean isAnchoredAt(Entity entity, Portal face) {
        if (anchorOf(entity) == face) {
            return true;
        }
        Entity root = entity.getRootVehicle();
        return root != entity && anchorOf(root) == face;
    }

    /**
     * The FLIP (design §3.2): re-anchor the unit to the arrival face, unit-atomically — the
     * root and every (recursive) rider change in one call, so no frame can observe a partial
     * unit. Epoch increments once per real flip; the caller epoch-guards via
     * {@link #isAnchoredAt} so duplicate/late/rider-echo RPC applications are structural
     * no-ops (the double-transform family dies here).
     */
    public static void flip(Entity unitMember, Portal arrivalFace) {
        Entity root = unitMember.getRootVehicle();
        SeamCrossingHolder rootHolder = SeamCrossingHolder.of(root);
        rootHolder.seamlessportals$setAnchorFace(arrivalFace);
        int epoch = rootHolder.seamlessportals$getAnchorEpoch() + 1;
        rootHolder.seamlessportals$setAnchorEpoch(epoch);
        for (Entity rider : root.getIndirectPassengers()) {
            SeamCrossingHolder riderHolder = SeamCrossingHolder.of(rider);
            riderHolder.seamlessportals$setAnchorFace(arrivalFace);
            riderHolder.seamlessportals$setAnchorEpoch(epoch);
        }
        SeamCartProbe.event(root, "ANCHOR-FLIP face=" + arrivalFace.getId() + " epoch=" + epoch);
    }

    /**
     * Per-tick anchor maintenance, called from {@code ip_tickCollidingPortal} for every entity
     * BEFORE the prune consults {@link #mustKeep}:
     * <ul>
     *   <li><b>Rider inheritance</b> (design §3.2): a rider continuously mirrors its unit
     *   root's anchor, so a mid-crossing dismount orphan keeps its crossing with no dismount
     *   event needed — never a geometric re-derivation (which anchors a majority-crossed
     *   orphan to the wrong face).</li>
     *   <li><b>CLOSE</b>: the anchor releases when the face is removed, the level mismatches,
     *   or the crossing is no longer in progress ({@code !pinned}) — full front emergence and
     *   lateral pin-column exit both land here. Anchor and bracket die together.</li>
     * </ul>
     */
    public static void tickAnchor(Entity entity) {
        SeamCrossingHolder holder = SeamCrossingHolder.of(entity);
        Entity root = entity.getRootVehicle();
        if (root != entity) {
            Portal rootAnchor = anchorOf(root);
            holder.seamlessportals$setAnchorFace(rootAnchor);
            if (rootAnchor != null) {
                holder.seamlessportals$setAnchorEpoch(
                    SeamCrossingHolder.of(root).seamlessportals$getAnchorEpoch());
            }
            return;
        }
        Portal anchor = holder.seamlessportals$getAnchorFace();
        if (anchor == null) {
            return;
        }
        if (anchor.isRemoved() || anchor.level() != entity.level()
            || !SeamStraddleBracket.pinned(entity, anchor)) {
            holder.seamlessportals$setAnchorFace(null);
            SeamCartProbe.event(entity, "ANCHOR-CLOSE face=" + anchor.getId());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // RESOLUTION
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The entity's current crossing face. Stage 0: IP's last-wins selection moved verbatim from
     * {@code CrossPortalEntityRenderer.submitMainPassEntity} (IP re-set the outer clip per
     * colliding portal with an endBatch flush between, IP :119-126 — the net effect is the LAST
     * portal's plane clips the entity's single draw; registration overwrites → same last-wins
     * semantics). Stage 2a makes this anchor-authoritative with entry fallback + divergence
     * assert (design §1.3).
     */
    @Nullable
    public static Portal resolveCrossingFace(
        Entity entity, @Nullable PortalCollisionHandler handler
    ) {
        if (handler == null) {
            return null;
        }
        Portal lastWins = null;
        boolean anchorHasEntry = false;
        Portal anchor = anchorOf(entity);
        for (PortalCollisionEntry e : handler.portalCollisions) {
            lastWins = e.portal;
            if (e.portal == anchor) {
                anchorHasEntry = true;
            }
        }
        // Stage 2a, delta (d): anchor-authoritative resolution — the crossing's stored history
        // bit outranks entry ORDER (a forbidden input; last-wins was invertible by any
        // clear-and-resweep whose iteration visited the twin first). Divergences logged; the
        // per-member entry fallback survives unit destruction.
        if (anchor != null && anchorHasEntry) {
            if (lastWins != anchor) {
                shadowDiverge("d-resolution",
                    "id=" + entity.getId() + " anchor=" + anchor.getId()
                        + " lastWins=" + (lastWins == null ? "null" : lastWins.getId()));
            }
            return anchor;
        }
        return lastWins;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // REAL-BODY VERDICT (portal passes)
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /** Tri-state verdict for the real body inside a portal pass. */
    public enum InPassBodyVerdict {
        /** Seam rule engaged: cull, final. */
        CULL,
        /** Seam rule engaged: draw, final — the pass's plane-exact ambient clip cuts pixels. */
        KEEP,
        /** Seam rule not engaged (non-seam face, not pinned): the legacy IP gates decide. */
        NOT_ENGAGED
    }

    /**
     * The straddle-side verdict for the real body in a portal pass. Moved verbatim from
     * {@code CrossPortalEntityRenderer.shouldRenderEntityNow}'s seam branch (rounds 3/14/15):
     * while a seam crossing is in progress, the real body draws only in passes whose view side
     * matches the side its crossing face fronts — tested against the pass's ARMED INNER CLIP
     * normal (which always points into the pass's actual content), never contentDirection
     * (which flips meaning with the renderer's co-located face pick — the round-14 lesson).
     * The KEEP verdict is FINAL (round 15): the binary center-side onDestSide test culled the
     * poked front until the center crossed; the ambient clip cuts plane-exactly instead.
     */
    public static InPassBodyVerdict inPassRealBodyVerdict(
        Entity entity, @Nullable Portal collidingPortal, Portal renderingPortal
    ) {
        if (collidingPortal == null) {
            return InPassBodyVerdict.NOT_ENGAGED;
        }
        if (!SeamCartContinuity.isSeamContinuous(collidingPortal)) {
            return InPassBodyVerdict.NOT_ENGAGED;
        }
        if (!SeamStraddleBracket.pinned(entity, collidingPortal)) {
            return InPassBodyVerdict.NOT_ENGAGED;
        }
        Plane passClip = renderingPortal.getInnerClipping();
        if (passClip == null) {
            // Delta (a) FLIPPED (zero shadow occurrences over a 34-crossing lap): the
            // contentDirection fallback is deleted — a pass without an armed clip cannot be
            // characterized by the seam rule, so the IP baseline decides (constitution:
            // contentDirection is a forbidden input; it flips meaning with the renderer's
            // co-located face pick).
            shadowDiverge("a-null-passClip-not-engaged",
                "pass=" + renderingPortal.getId() + " face=" + collidingPortal.getId());
            return InPassBodyVerdict.NOT_ENGAGED;
        }
        if (collidingPortal.getNormal().dot(passClip.normal()) <= 0) {
            return InPassBodyVerdict.CULL;
        }
        return InPassBodyVerdict.KEEP;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // PROJECTION VERDICTS
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Main-pass (CASE-2) projection admission for seam faces. Moved verbatim from
     * {@code renderProjectedEntity}'s crossing-window gate (round 10; IP's dropped
     * {@code hasIntersection} restored seam-correctly): a seam face projects an entity only
     * during its crossing window — pre-crossing the legitimate face's image is fully clipped
     * anyway (nothing has poked through), so gating on the pin window is pixel-identical for
     * every legitimate phase and kills the co-located twin's whole-body approach ghost
     * (~2 s per loop, the "couple-seconds sighting").
     * Returns true when the seam rule does NOT object (non-seam faces always pass — their
     * legacy IP path applies).
     */
    public static boolean mainPassProjectionAdmitted(Entity entity, Portal collidingPortal) {
        if (!SeamCartContinuity.isSeamContinuous(collidingPortal)) {
            return true;
        }
        // Delta (e) FLIPPED (zero shadow divergences over a 34-crossing lap): projection
        // existence IS back-piece existence — a face projects an entity iff part of the box is
        // past its plane (the piece the projection displays). The reversed shadow comparator
        // stays as cheap insurance.
        boolean newForm = SeamStraddleBracket.backPieceExists(entity, collidingPortal);
        if (AperturePassthroughLever.SEAM_RESOLVER_SHADOW
            && newForm != SeamStraddleBracket.pinned(entity, collidingPortal)) {
            shadowDiverge("e-projection-existence",
                "face=" + collidingPortal.getId() + " id=" + entity.getId()
                    + " active(backPiece)=" + newForm + " legacy(pinned)=" + !newForm);
        }
        return newForm;
    }

    /**
     * The seam same-plane exception for in-pass (isRendering) projections. Moved verbatim from
     * round 16: the renderer may draw a window through EITHER co-located face; when it picks
     * the twin of the face the entity straddles, IP's flipped-skip would silently drop the
     * straddler's back-image — the window's only painter of the un-emerged half. At a seam the
     * flipped twin's projection IS legitimate window content, so the flipped-skip is bypassed
     * (the isHidden camera guard is NOT bypassed — round 17 restored it after the blanket
     * bypass caused the far-station leak).
     */
    public static boolean inPassSamePlaneException(Portal renderingPortal, Portal collidingPortal) {
        return SeamCartContinuity.isSeamContinuous(collidingPortal)
            && Portal.isFlippedPortal(renderingPortal, collidingPortal);
    }

    /**
     * The clip plane a seam projection must carry in-pass. Moved verbatim from round 2's fix
     * (the {@code PROJ clip=DISABLED in-portal-pass} ghost, 1,485 probe lines): IP draws
     * in-pass projections UNCLIPPED, relying on framed-portal stand-in gates that do nothing at
     * a frameless co-planar seam — the whole un-poked body pasted into the window view. Seam
     * faces thread their real inner clip; non-seam portals keep IP's null verbatim.
     */
    @Nullable
    public static Plane inPassProjectionClip(Portal collidingPortal, @Nullable Plane innerClipping) {
        return SeamCartContinuity.isSeamContinuous(collidingPortal) ? innerClipping : null;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // LIFECYCLE PREDICATES (bookkeeping call sites)
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /**
     * May {@code face} register a portal-collision entry on {@code entity}? Moved verbatim from
     * {@code MixinEntity.ip_notifyCollidingWithPortal}'s inline gates:
     * <ul>
     *   <li><b>Behind-refusal</b> (round 11): a portal is entered from its FRONT — a seam face
     *   never accepts an entity wholly behind its plane. This keeps the co-located TWIN
     *   unbooked during a mere approach (a booked twin's projection painted the whole
     *   approaching cart at the far station). The arrival SEED bypasses this deliberately —
     *   the rebased trail body is legitimately wholly behind the arrival face
     *   ({@link SeamStraddleBracket#inSeed()}; stage 2a replaces the ThreadLocal costume with
     *   anchor-authorized booking).</li>
     *   <li><b>Twin-refusal</b> (rounds 2/11): while a seam face is booked with a crossing in
     *   progress, its co-located opposite twin may not register — last-entry-wins would flip
     *   the render bracket mid-crossing ({@link SeamStraddleBracket#refuses}).</li>
     * </ul>
     */
    public static boolean mayBook(
        Entity entity, @Nullable PortalCollisionHandler handler, Portal face
    ) {
        // Stage 2a: ANCHOR-AUTHORIZED booking (design §3.2 clause (a)) — the arrival face is
        // bookable while the body is wholly behind it BECAUSE the anchor says a crossing to it
        // is live, not because a thread-local seed bracket happens to be open. This retires
        // the beginSeed/endSeed ThreadLocal costume.
        if (isAnchoredAt(entity, face)) {
            return true;
        }
        if (SeamCartContinuity.isSeamContinuous(face)
            && SeamStraddleBracket.whollyBehind(entity, face)) {
            return false;
        }
        if (handler != null && SeamStraddleBracket.refuses(entity, handler, face)) {
            return false;
        }
        return true;
    }

    /**
     * Must this entry survive the prune (bypassing the eye-side, staleness, and box-proximity
     * reasons)? Moved verbatim from {@code PortalCollisionHandler.update}'s inline keeps:
     * <ul>
     *   <li><b>The straddle pin</b> (rounds 2/3): while the entity's crossing of this seam face
     *   is in progress ({@link SeamStraddleBracket#pinned}), the entry persists and stays fresh
     *   — consulted BEFORE the box gate, because a rebased arrival visual can trail more than
     *   the 0.5 stretch margin behind the plane.</li>
     *   <li><b>The rider bracket mirror</b> (round 9): a passenger's entry lives exactly as
     *   long as its VEHICLE holds an entry for the same portal — the rider is part of the
     *   crossing unit; its own box/eye gates are tuned for self-moving entities and would
     *   delete the fanned entry every tick before a frame renders (the cowless emergence).</li>
     * </ul>
     */
    public static boolean mustKeep(Entity entity, PortalCollisionEntry entry) {
        // Stage 2a: the unit's live anchor face keeps its entry unconditionally — the anchor
        // IS the crossing; tickAnchor's CLOSE (which runs before the prune) is the single
        // release point, so anchor and bracket die together.
        if (isAnchoredAt(entity, entry.portal)) {
            return true;
        }
        if (SeamStraddleBracket.keeps(entity, entry.portal)) {
            return true;
        }
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            PortalCollisionHandler vehicleHandler =
                ((IEEntity) vehicle).ip_getPortalCollisionHandler();
            if (vehicleHandler != null) {
                for (PortalCollisionEntry vehicleEntry : vehicleHandler.portalCollisions) {
                    if (vehicleEntry.portal == entry.portal) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
