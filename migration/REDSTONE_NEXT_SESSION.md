# REDSTONE / RAIL / MINECART PASSTHROUGH — NEXT-SESSION HANDOFF

**Branch `redstone/passthrough`, tip `a83f0c8`**, worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`. Tree is clean and green.

## READ FIRST, in order
1. `migration/REDSTONE_RECON.md` **§0** — every user decision, pinned, with the reasoning.
2. `migration/REDSTONE_A_SPEC.md` — the (a) spec. **Its top banner overrides the body** where they differ.
3. This file.

## STATE: steps 0–4 of 8 landed, gated, pushed

| step | what | commit |
|---|---|---|
| 0 | levers (4 fix + 5 probe, rows in BOTH build.gradle blocks) | `8890bac` |
| 1 | `SeamMap` — the phase-agnostic seam arithmetic | `8890bac` |
| 2 | `SeamRegistry` + `SeamIndexHolder` duck index + signal seeding | `f12b3df` |
| 3 | 4 IP-core edits: aperture becomes buildable, integrity frame-only | `7983b0e` |
| 4 | ignition rules + `ApertureOccupancy` + 2 data tags | `a83f0c8` |

**Working today:** rails and blocks can be placed in a lit portal's opening by hand, the portal
survives, blocks render half-clipped at the plane, and a frame containing a track can be lit and re-lit.

**NOT built: mirroring.** A block placed on one side does not appear on the other. The user must
place a second block from the far side; the two half-clipped blocks merely *look* like one.

## REMAINING, in the user's chosen order

1. **Step 5 — placement veto.** `MixinBlockPlaceContext` + `MixinBlockItem` → `SeamMirror.mayPlace`.
   Enforces refuse-on-conflict, incl. block-entities, multi-cell blocks and fluids.
   **Hard part:** refusing requires reading the DESTINATION world, which may be unloaded. The spec
   offers no clean answer; if none emerges, it goes back to the user (force-load / refuse / optimistic).
2. **Step 6 — mirror driver.** Second `@Inject` on `LevelChunkSetBlockStateMixin` (site rationale
   already written at `:36-72`), deferred end-of-tick flush via `ServerTaskList`, `withApplying`
   recursion guard, cluster dedupe. **Writes provenance** (see below).
3. **Step 7 — `SeamJournal`** (pending clears surviving an unloaded destination) + IP-core edit 10
   (re-ignition guard, now defence-in-depth — see the staging flaw below). **Consumes provenance.**
4. **Targeting fix** — evidence-specified, see below.
5. **Frame mirroring** — new user scope, needs its own design pass.

## THE TARGETING FIX — settled by live round #1, do NOT re-litigate

Probe evidence found **two modes needing different treatment**:
- *Real block in a seam cell:* `localDist=2.331 portalDist=2.419 margin=0.112 → THROUGH-PORTAL`.
  Local hit was CLOSER and still lost, purely to the hardcoded `+ 0.2` at
  `BlockManipulationClient.java:81`. **This is the defect** — the player's block lands in the wrong dimension.
- *Empty aperture cell:* `localDist=NONE(23333)` (placeholder sentinel, `:104-109`) → portal wins.
  **CORRECT, MUST BE PRESERVED** — it is what lets a player reach through an open portal.

⇒ Fix is narrow: **local wins only when the seam cell holds a real, non-placeholder block.** A blanket
"seam cells win" fixes the rail and breaks cross-portal interaction in the same commit.

## FRAME MIRRORING — new scope, design pass required

User rule: breaking obsidian on one side breaks the corresponding obsidian on the other; repairing and
lighting one side ALSO repairs and lights the other, and they re-link.

**The hard part is not the mirroring.** Seam bindings are DERIVED from live `Portal` entities. Once
both portals tear down, nothing knows source frame ↔ dest frame, so a repair has nothing to mirror
through. Needs a persisted **DORMANT LINK** surviving teardown — a concept the spec lacks — and extends
the mirror beyond the aperture to the frame ring, which every §0.7 binding rule assumed would not happen.

## HAZARDS EARNED THE HARD WAY — do not rediscover

- **`ApertureOccupancy.areaPredicate()` is load-bearing in THREE systems at once**: flood-fill
  boundary, frame matchability, ignition validity. Two bad entries broke a different one each:
  * `obsidian` in the support tag → destroyed the flood-fill boundary; valid frames stopped lighting.
  * `PortalPlaceholderBlock` in the predicate → destroyed liveness detection; a LIVE portal's frame
    looked matchable and a second portal pair bound the same cell with a different destination.
  Never add a frame material to `aperture_support`. Never admit the placeholder.
- **STAGING FLAW in the spec:** IP-core edit 10 (re-ignition guard) is scheduled at step 7 but guards a
  hazard created at step 4. Root fix is already in; keep edit 10 as defence in depth.
- **Instruments must assert their own COVERAGE, not just their result.** Three separate times an
  instrument reported success without exercising what it tested: the teardown probe that only ever
  logged `intact=true`; a client-side block read returning `void_air` for unloaded chunks; the seam
  gate passing while skipping its involution because no bi-way pair was in range.
- **An evidence gametest leg must never perturb a functional leg** — twice: a staged block left in
  portal B's window failed the ender-pearl leg, and a leftover obsidian FRAME inside another leg's
  128-block match radius made leg 6a link to it. Cleanup belongs in a `finally`.
- **A portal has the SAME UUID on client and server.** Keying per-portal state by UUID alone lets one
  side suppress the other's work. `AperturePassthroughInit` keeps two fingerprint maps for this reason.
- **Four portal entities per frame pair**, two coincident per side. Any per-portal driver fires four
  times unless cluster-deduped.
- **Build scaffolding (`build.gradle`, `settings.gradle`, `gradlew*`, `gradle/`) is UNTRACKED in git.**
  A fresh worktree cannot build until they are copied in from the main checkout. Not committed —
  that is the user's call.

## GATES

```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PdisableAperturePassthrough=true
```
Both must reach `ALL LEGS PASS`. The RS-TEARDOWN-TEST verdict **inverts** on the master lever
(enabled → `NO TEARDOWN`; disabled → `TEARDOWN CONFIRMED`) and reports a REGRESSION either way round —
that is the end-to-end proof (a) works and that the lever cleanly restores stock IP.

Live client with probes:
```
.\gradlew.bat :fabric:runClient --no-daemon -PseamAimProbe=true -PapertureCensusProbe=true -PseamReconcileProbe=true
```
