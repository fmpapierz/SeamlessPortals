# REDSTONE SEAM CROSSTALK — SESSION PROMPT

## THE DEFECT (user-reported 2026-08-22, exact reproduction)

1. Build a rail running **source side A → dest side B** across the seam. **Power it.**
2. Build a rail running **source side B → dest side A** across the seam. **Leave it unpowered.**
3. The unpowered **S-B→D-A** rail **receives power** from the powered **S-A→D-B** rail.

That is wrong. The two through-paths are independent and must never exchange signal.

## THE CONTRACT (user-stated, unchanged since 2026-07)

> Any redstone signal going across the seam must act **EXACTLY the same** as redstone signal
> going across normal terrain — especially when there is signal going through **both** sides
> of the portal. Signal from source A to dest B never bleeds with signal from source B to
> dest A through the seam, **even if they share a seam cell or adjacent cells.**

Two rules:
1. The seam is **invisible** to redstone — behaviour identical to continuous terrain.
2. The two through-paths **never interact**, sharing a cell or not.

## PRIME SUSPECT — verify, do not assume

`SeamSignalContinuity` lines **213** and **300** both reach for far-side signal via:

```java
guardedFarSignal(far, target, SeamRegistry.mapDir(b, b.crossDir()), src, pos)
```

Project memory records this exact trap: **side/occupancy semantics need
`mapDir(...).getOpposite()`; plain `mapDir` is for ORIENTATIONS only.** If these sites ask a
side/occupancy question with an orientation-only mapping, both halves resolve to the **same**
side — which is precisely a shared-power symptom, and it would be **symmetrically wrong**, so
volume gates and totals look healthy while the behaviour is wrong.

A real mechanism is not THE mechanism. Prove it against the reproduction before acting.

## MACHINERY

- `common/src/main/java/com/warwa/seamlessportals/passthrough/SeamSignalContinuity.java`
  — `hasNeighborSignalAcross`, `neighborSignalStrengthAcross`, `walkRedirect`,
  `onSeamCellChanged`, `hasLocalNeighborSignalSkippingEmptyHalf`,
  `localNeighborSignalSkippingEmptyHalf`, `wireDeclineCold`; liveness via `counters()`,
  `walkCrossedCount()`, `unionHitsCount()`, `dispatchDeliveredCount()`, `dispatchDroppedCount()`
- Mixins: `MixinPoweredRailBlockSeamSignal`, `MixinRedStoneWireBlockSeamSignal`,
  `MixinRedStoneWireBlockSeamAuthority`, `MixinRedstoneWireEvaluatorSeam`,
  `MixinRedstoneLampBlockSeamSignal`
- Specs: `migration/REDSTONE_{A,B,C}_SPEC.md`, `migration/REDSTONE_C2_HANDOFF.md`

**Vanilla gotcha (recorded):** `updateNeighborsAt` notifies **AROUND** P, never P. Vanilla
wire fans `{pos} ∪ shell`; a fan that drops `{pos}` never wakes face neighbours.

## RULES THAT HAVE TEETH

1. **Evidence first.** No speculative fixes. Measure the defect, then fix one variable per lap.
2. **Read liveness counters BEFORE adjudicating any fix.** A lap whose instrument or `-P` flag
   was not armed is *vacuous*, not clean. This has invalidated laps repeatedly — verify the
   arming line in that run's own log.
3. **Assert the OUTCOME, not the request.** Gate on the last step the user can see (does the
   S-B→D-A rail light?), never on "my code issued the call". Three separate gates once passed
   on a no-op fix.
4. **Symmetric wrongness passes volume gates.** Both halves reading the same wrong side
   produces healthy-looking totals. Test the two paths *independently and asymmetrically*.
5. **Suite green before any commit** — `ALL LEGS PASS` in that run's own log:
   `.\gradlew.bat :fabric:runCrossingGametest -PapertureCensusProbe=true -PapertureTeardownTest=true -PseamFractionalProbe=true`
6. **User tests first**, suite once pre-commit.
7. **File writes via the Edit tool or .NET UTF-8 APIs only** — never bulk
   `Get-Content`/`Set-Content` (mangles UTF-8 in this repo).
8. **javap the loom deobf jar before any new mixin target** — owners and return counts drift
   from the decompile.

## DO NOT TOUCH

The rendering baseline is user-confirmed good ("no bleed just the tiny face cut"). The one
open render defect and its full failure ledger are in `migration/SEAM_FACE_CUT_HANDOFF.md`.
Do not reinstate rounds 37 or 39 — both reverted, both unvalidated, one refuted.

## STATE

Branch `claude/particle-seam-regression-c2f79c`, worktree
`E:\Immersive Portals - Copy\.claude\worktrees\particle-seam-regression-c2f79c`.
Last commit `0b1b7c3`; the kept render fixes are **uncommitted working tree** — do not discard
them.
