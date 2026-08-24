# SEAM FACE CUT — HANDOFF (rounds 31-41, 2026-08-22)

**Branch** `claude/particle-seam-regression-c2f79c`
**Worktree** `E:\Immersive Portals - Copy\.claude\worktrees\particle-seam-regression-c2f79c`
**Last commit** `0b1b7c3` (round 29). Everything after it is **uncommitted working tree**.

---

## §0 STATE — the baseline to protect

User-confirmed 2026-08-22, after the round-37/39 reverts, launching clean with **no `-P`
instrument flags**:

> "did another crossing, no bleed just the tiny face cut"

### KEPT — all user-confirmed live
| Fix | Effect |
|---|---|
| Visual sweep + locality-gate model margin | tail clip gone ("PERFECT. Tail clip is gone!!!") |
| `SeamRenderExtent` per-entity render envelope | drawn-extent bound from `getBoundingBoxForCulling` |
| `MixinEntityRenderDispatcher.seamlessportals$projectionCameraDistance` | shadow fix ("shadow is PERFECT") |

### REVERTED — do not reinstate without fresh, unmaskable evidence
| Round | Change | Why reverted |
|---|---|---|
| 37 | `SeamStraddleBracket.keeps()` → `pinnedForDraw` | Unvalidated (its only apparent confirmation was the tint masking the defect) **and** it widened the PHYSICS substrate — `portalCollisions` feeds collision handling, which round 30 deliberately kept on the post-tick box. Lever `-PdisableSeamKeepModelExtent` retained. |
| 39 | all-face in-pass visibility verdict | Premise refuted by the user's own photo, **and** a concrete candidate cause of the wrong-side bleed that appeared with it: `anyKeep` drew the real body if ANY straddled face voted KEEP, where before only entry 0 voted. Lever `-PdisableSeamAllFaceVisibility` retained. |

Instruments (tint, per-entity, neutral, probe, render-booking supplement) are all
**default-off** and inert unless their `-P` flag is passed.

---

## §1 THE ONE OPEN DEFECT

A tiny cut on the **leading edge of the drawn model** (cow's nose/muzzle) at the seam, at slow
crossing speed. Affects riders **and** vehicles; not species-specific (reproduced on horse).

---

## §2 ★ THE TRAP THAT COST THIS ARC — READ BEFORE INSTRUMENTING

**The per-entity TINT HID the defect.** With it on, the user reported "cut is gone". It was
not gone.

**The disproof is airtight.** Round 41 added NEUTRAL mode: identical rewritten shader source,
identical program, identical uniform locations, identical fresh `Snapshot` instances,
identical clip planes — with the tint alpha at 0. One float different in
`mix(fragColor, tintColor, a)`. **The cut came straight back.** A fragment colour blend cannot
move geometry or change a clip plane.

An 85%-strength flat colour destroys the texture and shading cues that make a few-pixel
missing sliver legible. The instrument concealed exactly the class of artifact it was aimed at.

**Rules that follow:**
- Prove any visual instrument can still SHOW the artifact before trusting a clean lap.
- Use the lowest strength that still attributes (0.2-0.3) for thin artifacts. Never 0.65-0.85.
- Build the NEUTRAL (alpha-0, same pipeline) control **at the same time** as the coloured one.
- "The defect vanished when I enabled the diagnostic" is a **masking alarm**, not a fix.

---

## §3 WHAT DID NOT WORK — do not re-run

1. **`DRAW_MODEL_MARGIN` 2.0** (r34). The admission gate moved exactly as designed (gating
   relocated 1.5-2.0 → 2.5-3.0; projection draws 6109 → 9824) and the cut survived unchanged.
   Clean disproof: **the cut is not an admission problem.**
2. **Round 37 entry lifetime.** No effect. The measured condition is `entries=0`
   (**acquisition**), not pruning — `removeIf` over an empty list cannot help.
3. **Round 39 all-face verdict.** No effect on the cut; likely **caused** the bleed.
4. **Render-booking supplement** (`-PenableSeamRenderBooking`). Books and submits — both twin
   faces, `touchedCollections=1`, every frame of the gap — and the cut persists. Note
   **`mustBook` does not book**: it returns a face list and the supplement calls
   `renderProjectedEntity` with collision state still empty, so every downstream consumer
   still sees `entries=0`.
5. **Per-entity tint "fix."** A false negative. See §2.

---

## §4 FALSE LEADS THAT ATE ROUNDS

- **`straddlesForDraw` is inflated ~1.75 blocks** (render envelope `0.5 + 0.25` plus
  `DRAW_MODEL_MARGIN` 1.0), so `modelStraddles=true` fires long before the model touches the
  plane. Every "straddle→draw GAP of 18-89 frames" metric built on it **overstates massively
  and does not track the visible defect** — the gaps persisted unchanged across laps the user
  reported clean.
- **`ABSENT(unbooked)` == `render-booked`** counts are equal **by construction** (a diagnostic
  loop, then a booking loop, over the same candidate set). This tautology was once read as
  evidence of failure.
- **"Green on the cow."** The in-pass ambient arm tints **everything** the window pass draws —
  grass, sky, clouds included. A cow at the destination *should* be green. Round 39 was built
  on reading this as a spurious draw; the user's photo refuted it.

---

## §5 INSTRUMENT BUGS THIS ARC (one shape, three times)

Bugs #5, #7, #8 were all: **a counter scoped to a tint colour the active mode never emits.**
`orangePhasesExecuted` matched the ROLE palette's orange, so under per-entity mode it read 0
on all 16,803 frames and its `SNAPSHOT NEVER FOUND AT EXECUTE` banner fired **unconditionally**.
Round 40 fixed it to test tint **presence** (`isSeamPaintedTint`, any alpha) → `TINTED-EXEC`.

Also: two laps were launched **without** `-PseamPainterTint` while reading a tint-dependent
counter. Vacuous. **Verify the arming banner in the log before adjudicating any lap.**

---

## §6 NEXT METHOD — the floor

**RenderDoc pixel history on the cut pixel.** It names every draw that touched that pixel and
why each did or did not write it: no instrument that can lie, no paint that can mask, no
interpretation. Requires the **GUI** (pixel history is not exposed through `renderdoccmd`).
The user already has RenderDoc working against this client and has captured frames from it.

Everything analytic has been exhausted across 41 rounds and three multi-agent verdicts. Every
painter audits individually correct. Pixel-level attribution that cannot be masked is the
remaining move.

---

## §7 DEFERRED

**Cross-dimension seam parity** (OW↔Nether, OW↔End, Nether↔End). The user stopped a
speculative audit as "pointless" without a symptom and will supply exact observed behaviour
when it is time. Do not start it before then.
