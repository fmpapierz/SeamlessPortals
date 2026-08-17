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
        Portal collidingPortal = null;
        for (PortalCollisionEntry e : handler.portalCollisions) {
            collidingPortal = e.portal;
        }
        return collidingPortal;
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
        Vec3 passKeptDir = passClip != null
            ? passClip.normal()
            : renderingPortal.getContentDirection();
        if (collidingPortal.getNormal().dot(passKeptDir) <= 0) {
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
        return SeamStraddleBracket.pinned(entity, collidingPortal);
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
        if (!SeamStraddleBracket.inSeed()
            && SeamCartContinuity.isSeamContinuous(face)
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
