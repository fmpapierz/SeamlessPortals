# IS5-FARFADE — distance crossfade for cross-dim window atmospherics (PANEL-FOLDED 2026-08-16)

> Panel wf_939d290f-17d: engineering judge + closed-arcs judge both APPROVE_WITH_CHANGES;
> pack-truth facts extracted. ⟦J⟧ folds below are BINDING; §1-§4 as drafted are amended by §5.
> Implementation must follow §5 wherever it contradicts the draft body.

## §0 THE USER CONTRACT (2026-08-16, explicit)

The convicted defect (see IS5_WASH addendum in memory + the 2026-08-16 legs): the far-window
washout IS the dest chain's own atmospherics (nether storm + red fog + bloom haze) integrated
over the VIRTUAL camera distance — every delivery stage measured clean (bracket exonerated
live: blackout lands, tiles starved; sgF=0 sustained; capture ≈ ambient-at-matched-distance).
The user's decision, verbatim: **"far handover to pre with no pop in, storm always present."**

Binding readings:
- C-F1 **No pop**: the near→far transition must be continuous — no visible snap at any
  threshold distance.
- C-F2 **Storm always present**: the nether storm/red-fog styling must never fully vanish
  from a cross-dim window at any distance — a visible hint must remain at range.
- C-F3 (carried) No pack-formula replication (C6/no-hacks, §2.1 of IS5_RESTAMP_DESIGN.md).
- C-F4 (carried) Near windows keep the shipped XDIM-SG look — at w=1 the path must be
  byte-identical to today's.

## §1 Mechanism

One new number per armed cross-dim slot: the **fade weight w ∈ [wMin, 1]**, computed at arm
time from the player↔portal distance d:

    w = wMin + (1 - wMin) * (1 - smoothstep(D0, D1, d))

Defaults (levers, user-gated): D0 = 8 blocks (fade starts), D1 = 24 blocks (fade floor
reached), wMin = 0.15 (the storm floor, C-F2). w is continuous in d ⇒ C-F1.

Per-frame flow for an SG-armed cross-dim slot (all indices runtime-measured, §1.3 machinery
of IS5_RESTAMP_DESIGN.md reused; nothing hard-coded):

1. **PRE capture (new)**: at the DEST chain's pass-0 boundary (the existing ⟦J⟧ dispatch
   branch (a) hook, i == 0 — before any dest composite), glCopyImageSubData dest colortex0's
   pass-0 READ side into `slot.preColorTex` (pooled, format-keyed like colorTex). This is the
   dest scene **scene-referred, pre-storm, pre-fog-multiply, pre-grade** — exactly the
   content class the source chain's HEAD stamp contract expects (same-dim PRE windows stamp
   this class today and render correctly).
2. **SG capture unchanged**: dest anchor-boundary capture (post-tonemap, pre-AA) into
   `slot.colorTex` as today.
3. **HEAD stamp source**:
   - w == 1 (near): stamp `slot.colorTex` (SG) — **byte-identical to today** (C-F4).
   - w < 1: stamp `slot.preColorTex` (PRE). The source chain then processes the window
     pixels once — linearize, ITS storm/fog at PANE depth (the physically correct medium
     for the eye→pane stretch), bloom decisions, tonemap — producing the **graded-PRE**
     version in the anchor image target by anchor time. Depth semantics unchanged (PLANE at
     HEAD, CONTENT at the anchor; d1 restamps untouched).
4. **Anchor inject becomes a blend** (w < 1 only): snapshot the anchor image target's
   window region to the pooled scratch (the washScratch copy pattern), bind it as a second
   sampler, and draw the stamp-shader mode-2 variant writing
   `fragColor = mix(gradedPRE(scratch), sgCapture, w)`, keeping the R11-MASK alpha discard
   (capture alpha < 0.5 ⇒ discard) exactly as today. Both inputs are display-referred at
   this point (the source chain graded the PRE stamp; the SG capture is dest-graded) — the
   mix is color-space-legal. At w == 1 the blend is skipped entirely and the existing pure
   inject runs (C-F4).

Result by regime:
- Near (d ≤ D0): today's shipped look, bit-for-bit.
- Fade band (D0 < d < D1): continuous crossfade; the storm dims as the haze error would
  otherwise grow.
- Far (d ≥ D1): face = (1−wMin)·(nether content + OW fog at pane depth) + wMin·(full dest
  atmosphere) — wash bounded at wMin, storm hint permanent (C-F2), no pop anywhere (C-F1).

## §2 Disclosed residuals / open panel questions

R-F1. **Secondary-channel switch at w<1**: reflections, fog decisions, and the bloom gather
      see the PRE stamp instead of the SG stamp once fading starts (a discrete change in a
      secondary channel at the D0 threshold). The gather is bracket-blacked anyway; the
      visible surface is reflections of fading windows. Judged acceptable? Alternative: blend
      the HEAD stamp too (extra draw + a scene/display mixed-space stamp — suspected
      ILLEGAL, panel to confirm).
R-F2. **Nested windows (R11)**: the child's alpha-sentinel discard keeps working (the blend
      draw inherits the discard); the child face stays anchor-time source content. Panel:
      any new interaction with nest>0?
R-F3. **The blend-band double capture cost**: one extra glCopyImageSubData per armed
      cross-dim slot per frame (PRE copy) — always taken (even at w=1) or gated on w<1?
      Gating saves the copy but makes the D0 crossing arm a new copy path (a frame of
      latency / possible one-frame pop — violates C-F1?). Panel to rule.
R-F4. **Scratch snapshot scope**: full-texture copy (simple, matches washScratch) vs
      window-footprint-only (cheaper, more machinery). Draft says full-texture.
R-F5. **In-nether direction**: the mirrored case (standing in the nether far from the
      portal, OW dest) has the same virtual-distance medium error with OW atmospherics —
      milder pack loudness but the same mechanism. The fade applies symmetrically by
      construction (it keys on distance, not dimension). Panel: confirm no nether-side
      special-casing needed.
R-F6. **Suite/gametest**: crossing gametest must stay green; the fade must be lever-disable-able
      (`-PdisableFarFade` ⇒ w ≡ 1 everywhere = today's behavior, the regression escape hatch).
R-F7. **w read side**: distance d measured at arm time (pre-push camera pos vs portal origin
      — the same values the arm already holds). Per-frame update ⇒ smooth during approach ✓.

## §3 Levers

- `-PdisableFarFade` — kill switch, w ≡ 1 (shipped behavior).
- `-PfarFadeD0=<blocks>` / `-PfarFadeD1=<blocks>` / `-PfarFadeWMin=<0..1>` — tuning levers
  for the user gate (defaults 8 / 24 / 0.15).
- RC block prints all four; the 1Hz meas line gains `fw=` (current w, min/max over the
  window) so legs are log-adjudicable.

## §4 Verification plan

1. Compile + crossing gametest green.
2. Self-leg: probes confirm at parked distance w=1 and byte-path unchanged (census identical
   to the 2026-08-16 legs); walk-out confirms fw descending smoothly, no sgF, blend draws
   counted.
3. User gate (the only verdict that counts): walk away from the lit-nether portal — washout
   gone/bounded at range, storm hint visible, NO pop at any distance, near look unchanged.

## §5 ⟦J⟧ PANEL FOLDS (BINDING — wf_939d290f-17d, 2026-08-16)

F1 **Distance basis** (both judges, blocking): d = `portal.getDistanceToNearestPointInPortal(
   slot.cameraPos)` — the existing dPl idiom — NEVER portal origin (origin fades large portals
   at point-blank range, breaking C-F4). Computed per arm in armCaptureForView.
F2 **PRE capture at PEND TIME** (eng B2, arc B4-i/ii): the PRE copy happens inside the
   XDIM pend's postFinalMode branch (~ISCC:1054-1087) reusing the proven same-dim PRE body
   (resolvePassZeroFlipSet + glCopyImageSubData) — pass-0-boundary content is byte-identical
   to pend-time content. NO second dispatch firing index (destAnchor==0 collision + compute-
   pass-0 packs sidestepped). Keys on the pend record, never bare i==0.
F3 **Own pooled blendScratch** (eng B3, arc N2): the anchor-image snapshot gets its OWN
   pooled texture keyed to the MEASURED anchor-image format — never washScratchId (format
   thrash on mixed frames; a shared scratch between wash-save and wash-restore would destroy
   the saved c0 → permanent blacked bloom halo).
F4 **w==1 = zero new GL commands** (eng B4): PRE copy, snapshot, blend, and preColorTex
   allocation ALL gate on w<1 evaluated at arm time. Intra-frame ordering (arm → dest chain →
   stamp) means the D0-crossing frame arms same-frame: no latency, no pop. `-PdisableFarFade`
   short-circuits BEFORE all of it. Clamp D1 > D0 (equality = a step = a pop).
F5 **Stamp-time gate / failure ladder** (eng B5, arc B4-iii/iv/v/vi): HEAD stamp source =
   preColorTex ONLY when `fadeW < 1 && preCaptured && slot.sgCaptured && arm.injectTexId != 0`
   (all final by stamp time); otherwise slot.colorTex — today's exact ladder. EVERY w<1-chain
   failure (PRE copy/alloc, snapshot, blend) collapses THAT SLOT to w:=1 (shipped SG path),
   census-visible (fwF++), NEVER breakMechanism. Snapshot failure ⇒ pure inject (mutate-last).
   New per-slot state (preCaptured, fadeW) clears with the pending lifecycle at beginFrame;
   preColorTex pools/recycles exactly like colorTex.
F6 **Discard sentinel stays on slot.colorTex ONLY** (eng B6, arc B1): the blend's alpha
   discard reads exclusively the SG capture's alpha (the R11 sentinel carrier). preColorTex
   alpha is NEVER read (c0-class R11F_G11F_B10F is alpha-less: .a reads the constant 1.0 —
   keying on it silently resurrects the R11 dim-window). No new alpha-init for preColorTex.
F7 **Nested children also stamp into preColorTex** (arc B1, blocking): when the parent slot
   holds a PRE capture this frame, runNestedStamp draws the child into parent.preColorTex too
   (color-only; the PRE copy at pend time precedes nested layers). Otherwise an A→B→A corridor
   at d>D0 loses the child window (blend discards to the graded parent BACKDROP — an R11-class
   closed-arc regression). Disclosed residual: a nested CROSS-DIM child (A→B→C) stamps
   SG-class content into the scene-referred PRE tex — the inverse nested-rim class.
F8 **Wash bracket never blacks PRE-stamped entries** (arc B2, blocking): §1.10's "PRE windows
   are never blacked — the source gather is their only bloom source" applies verbatim to a
   w<1 stamp. Eligibility keys per entry on the STAMPED SIDE this frame (not postFinalMode
   alone); washNeeded counts only SG-stamped entries; mixed frames bracket only the SG ones.
   (At w<1 the gather never sees the SG side anyway — it enters post-gather via the blend.)
F9 **HIST channel** (eng B7): prev-store source at w<1 stays the SG capture (the wMin
   endpoint; TAA neighborhood clamp bounds the residual — same argument as shipped same-dim
   PRE history). Named fade-band-shimmer leg added to §4 (slow walk D0→D1, MB off); the
   one-line escape if it shimmers: store preColorTex as prev for w<1 slots.
F10 **Per-entry uniform discipline** (eng N5, arc N6): locDepthMode + u_fadeW set PER ENTRY
   in the inject loop (mixed w==1/w<1 frames = two portals at different distances), and every
   stamp-program draw site asserts u_fadeW (the u_zeroAlpha stale-uniform rule).
F11 **Census + instrument honesty** (arc N3/N4, eng N8): blend draws count under sgI; new
   fwB (blend draws) / fwF (per-slot collapses) counters; meas line gains fw=min/max; RC rows
   for all four levers in all three run blocks. The inject-landing comparator prints fw and
   is adjudicated MATCH-only-at-fw=1 (a w<1 "target≠capture" is the blend working, not a
   defect — the instrument-scoping rule). Same caveat on capture-vs-main pairs.
F12 **Pack-fact corrections** (fact agent): (a) GetNetherStorm contributes EXACTLY ZERO below
   one 8-block march step and only dither-speckle below ~24 blocks — C-F2's far storm hint
   rides ENTIRELY on wMin·SG, not on near-pane storm in the graded-PRE (and the OW chain
   compiles NO storm at all). (b) OW bloom fog is DAYTIME-ZERO (sunFactor kills it without
   rain/cave) — a noon far window gets no bloom fog from the PRE side; correct medium, but
   the D0 bloom-spill change is a named strafe-across-D0 gate leg. (c) OW-chain processing of
   nether content uses OW-viewer uniforms for sky-depth repaints (z==1 pixels → OW fogColor)
   — nether sky through a far window tints OW-ward; disclosed, inherent to the PRE class.
F13 **Disclosures carried to the user gate** (eng N1/N3, arc N8): wash=SKIP configs (MB-on)
   let the PRE component glow via the source gather (existing disclosed gap, extends to the
   fade); the second window into the same dest dim stays the storm-less PRE fallback at ALL
   distances (postDimsThisFrame dedupe — don't misread as a fade defect); dBnd-collapse packs
   (§1.9b watch) re-acquire virtual-distance fog in the fade band — walk-out leg adjudicates
   raw dBnd ≥ the c1 index. N2 expectation: wMin=0.15 of a near-white wash may read milky —
   plan a wMin/D1 retune round with the user.

F14 **Steep-angle driver amendment** (2026-08-17, POST-GATE — the user's live hold at the
   "near portal, extreme angle from above" worst case printed fw=0.79 with the wash visible
   and window-confined, SG capture 2-3.5x the graded-PRE at the same pixel, wB/R=0/0, no
   spill): the pane distance UNDER-MEASURES the haze at extreme incidence — the sight line
   through the pane travels ~1/|look·n| times the pane distance through DEST space. The
   fade driver becomes d_eff = d_nearestPoint / max(|look·portalNormal|, farFadeCosFloor)
   (lever, default 0.2 = ≤5x amplification; 1.0 disables). Head-on unchanged; crossing
   frames inert (d≈0). Disclosed: edge-on walks past a portal now fade its window (grazing
   views barely show the window; acceptable). Self-judged as a driver refinement inside F1's
   machinery (all other folds carry unchanged); flag to the next panel if the family
   reopens. The principled successor if uniform fade reads wrong on mixed-depth windows:
   per-pixel w from the captured dest depth (u_captureDepth) — noted, not built.

F15 **Per-pixel content-distance fade** (2026-08-17, POST-GATE round 2 — user: "good from
   far away, gradually comes back when very close"): every pane-distance driver goes to 0
   as the player approaches the pane while the CONTENT haze does not (point-blank steep
   view still looks at lava 10+ blocks into the dest). The fade weight moves INTO the
   mode-2 shader, per pixel, from the captured dest depth (slot.depthTex = dest depthtex0
   at the anchor): |viewZ| = m32/(2d-1+m22) (JOML z-row constants of the slot projection;
   clipDepthMode NEGATIVE_ONE_TO_ONE measured), wpx = wMin+(1-wMin)*(1-smoothstep(D0,D1,
   dist)) — D0/D1 REINTERPRETED as CONTENT-distance thresholds (8/24; storm is zero below
   one 8-block march step, so sub-8 content has no storm to lose). The arm gate stays
   pane-side (d_eff with cosFloor, default lowered 0.2→0.05: honest slant amplification —
   over-arming costs only the copy since near-content pixels fade w=1 anyway) and a slot
   RAMP smoothstep(D0, D0+3, d_eff) scales the per-pixel fade so the D0 gate crossing can
   never pop (ramp=0 at the edge). Near-content pixels stay sharp at EVERY player position;
   sky (d=1) reads far and fades to the source-processed look (F12c disclosure applies).
