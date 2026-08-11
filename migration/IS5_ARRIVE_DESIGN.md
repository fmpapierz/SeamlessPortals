# IS5-ARRIVE — direction-scoped arrival-frame suspension withhold

**Status: DESIGNED + JUDGED (2026-08-10, 2 facts + synthesis + 2 judges, ~512k tok,
wf_9e231947-15d, both APPROVE_WITH_CHANGES with all anchors source-verified) → IMPLEMENTED
same session with every judge fold. The seam-suspension arc the user ordered after three
XFLICK refinement rounds left "flashing only on side to side".**

## §1 THE ADJUDICATION (the facts matrix split the §4b premise)

- FORWARD arrival (look·N > +0.2): the window mesh is NULL (every triangle behind the camera
  plane drops — S14.36 clip, derived) — skip paints nothing that could be visible. Shipped
  skip is CORRECT; the pre-XFLICK "forward flicker" could never have been this mesh.
- BACKWARD arrival (look·N < -0.2): render UNDER SUSPENSION is user-clean; the "wrong-side
  slab" is the benign doorway interior (the V1 trade-off note's content). The matrix's
  C-active recommendation was OVERRIDDEN: V1 with the eye in the window on a full-screen
  quad is exactly the geometry the IS5-SEAM-CONTENT depth probe measured V1 insufficient
  for (unlit near-side faces at reversed-Z 0.995-1.0). Observed-clean beats derived.
- SIDEWAYS arrival (|look·N| ≤ 0.2): BOTH shipped branches wrong (render-under-suspension =
  the round-1 half-screen wrong-content paint; skip = the round-2 half-screen hole). The
  correct cell: RENDER with the V2 suspension WITHHELD for that one portal on that one frame
  — the armed V1 clip pins planeW at exactly +CROSSING_EYE_CLEARANCE (+0.20) across the
  whole arrival window (judge-verified arithmetic incl. slightly-negative d), so
  armedVoidRisk stays 0 and the window gets the correct half-split.

Neither blanket candidate survived: "withhold everywhere" reintroduces the V1 band on
backward + rapid re-cross; "suspension is fine, just render" is the recorded round-1 defect.

## §2 THE SHAPE (implemented; five sites + judge folds C1-C4 + B1-B2)

1. `SeamArrivalScope` (new, com.warwa.seamlessportals.render): per-frame identity SET of
   WeakReference<Portal> (B2/C2: cluster flip portals + teleportLimitPerFrame=3 multi-cross
   frames defeat a single slot), withheldCount (census-read), once-latch LIVE note.
   Cleared at manageTeleportation HEAD **above** the disableTeleportation early-return
   (C1: a runtime toggle after a sideways arrival must not latch mark+flag into a
   sustained V1-in-doorway band).
2. `consumeVisibilityForArmedFrame`: the tp-frame classification HOISTED above the query
   consume (B1: same-dim crossings have no query wipe — the reverse portal arrives KNOWN or
   hysteresis-credited and returns early; the mark is about geometry, not query state).
   Layer 0 only. Marks sideways regardless of query state (an unrendered marked portal
   leaves the mark unread — harmless, incl. the spec-cap skip case). The XFLICK skip block
   reuses the hoisted values and adds `!sideways`; forward/backward/camEnt-null/throw cells
   bit-identical to round 2 (C3: the throw path keeps dPl=MAX → render).
3. `FrontClipping.shouldSuspendInnerClipForCrossing`: one conjunct after the Mirror/layer
   gates — `isTeleportingFrame && SeamArrivalScope.isMarked(renderingPortal)` → withhold
   (return false). The caller's !seamSuspend branch arms V1 unconditionally — zero
   call-site changes. Re-cross band hazard dead by construction (mark+flag die at the next
   manageTeleportation).
4. `SeamClipArmCensus`: `arrivalWithheld={}` appended (withheld frames are ARMED frames —
   armedVoidRisk is their live void detector; no new risk counter).
5. XTRACE: tp-frame consume rows now carry `consume:P<id> cls={f|b|s|u}[+mark] dPl=` so a
   leg log adjudicates every arrival's cell.

Lever: `-PdisableSeamArrivalScope` (DEFAULT ON; disable = shipped round-2 bit-identically —
the sideways hole on command). Rows in all three run blocks.

## §3 RESIDUALS (design-disclosed)

- R1: the sideways C-active cell is derived, never observed — if the leg shows a one-frame
  seam-sliver band on sideways arrivals (needs doorway-embedded solid), the fallback is
  accept-and-disclose; competing cells are measured worse.
- The +0.2 boundary (forward↔sideways) is new; its bad side equals the shipped round-2 hole
  (no new wrong-paint class). The -0.2 side separates two clean cells.
- Spec-cap exhaustion on a marked portal ⇒ the shipped hole for that frame (mark unread).

## §4 LIVE-LEG KILL-CHECKS (shaders ON; doorway-embedded pair = the band's geometry)

1. Sideways arrivals ×10 both strafe directions: no hole, no wrong-world paint, no black
   sliver; trace `cls=s+mark` + `arrivalWithheld` matching. THE gate.
2. CLOSED ARC: slow crossings ×3/direction — seam band absent; census SUSPENDED>0 on
   crossing seconds as before. 3. armedVoidRisk=0 everywhere incl. tp frames.
4. Blur-burst intact. 5. Forward ×5 clean (`cls=f` skip). 6. Backward ×5 clean (`cls=b`,
   suspension fired). 7. Rapid sideways re-cross gauntlet ×10: no band flash, no hole.
8. On-plane trace rows show no anomaly. 9. A/B: `-PdisableSeamArrivalScope` reproduces the
   round-2 hole and nothing else. 10. Same-dim sideways crossing (B1's cell) clean.
