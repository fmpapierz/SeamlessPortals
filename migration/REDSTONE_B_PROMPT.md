# FRESH-SESSION STARTER — SEAM CLIP + (b) RAILS THROUGH A PORTAL, then (c) REDSTONE, (d) MINECARTS

*Paste this verbatim into a new session.*

---

Continue the redstone/rail/minecart passthrough engagement for Seamless Portals.

Work in the worktree `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`
(branch `redstone/passthrough`). Tree clean, suite green, everything pushed.

READ FIRST, in order:

1. `migration/REDSTONE_NEXT_SESSION.md` — state, the OPEN ITEMS, and a hazards list earned the hard
   way. **Read the hazards properly; each line cost real debugging time.**
2. `migration/REDSTONE_RECON.md` §0 — every binding user decision. **§0.7 and §0.8 were CHANGED on
   2026-07-26 — the handoff's "DECISIONS CHANGED" section overrides them. Do not restore the old
   rules from §0 alone; that mistake has already been made once (commit `7766010`).**
3. `migration/REDSTONE_B_SPEC.md` — the (b) design. Its top banner overrides its body, and see the
   re-check notes below before implementing anything from it.

## §0 PARALLEL-SESSION RULES

Another session may be running in this repository on the iris shaders-ON work, in
`.claude/worktrees/is5-shadow`.

* **NEVER touch `.claude/worktrees/is5-shadow`** — not its files, not its logs. That session reads
  its `latest.log` as primary evidence. Reading its BRANCH through git objects from your own
  worktree (`git show iris-on/is5-shadow:<path>`, `git diff`) is fine and is how to compare.
* **NEVER run `gradlew --stop`.** Machine-wide; it kills the other session's live game.
* Java process cleanup: your own PIDs only, filtered by this worktree's path. `idea64.exe` is a JVM.
* Build scaffolding (`build.gradle`, `settings.gradle`, `gradlew*`, `gradle/`) is **UNTRACKED and
  must stay that way** — copy it in from the main checkout. A `git add -A` will sweep it in; use
  explicit file lists. (This was done by accident on 2026-07-26 and reverted in `3f29e08`.)

## §1 THE JOB — TWO INDEPENDENT PIECES

There are two, they do not depend on each other, and they are in different subsystems. Either can go
first; they can also go to different sessions.

**A. SEAM CLIP (renderer).** A mirrored block still renders as a WHOLE cube in the source world —
nothing clips source terrain at the plane. Through the portal it only looks right because the
stencil+depth OVERWRITE paints the destination over that region; stand to the side and the far half
is simply drawn. The user wants it cut at the plane, with everything beyond coming from the mirrored
copy. **The design is done and adversarially verified — read `REDSTONE_NEXT_SESSION.md` OPEN ITEM 1
before touching this.** Its two headlines:

* **Compile-time quad clamping is the obvious route and it is PERMANENTLY WRONG for same-dimension
  portals** (one dimension = one `ViewArea`, drawn in the same frame by two cameras on opposite sides
  of the plane), and it no-ops silently under Sodium. Draw-time `gl_ClipDistance` is correct in every
  view and already has a Sodium path here.
* **`FrontClipping.setupOuterClipping` — javadoc "clips source-dim geometry PAST the portal plane …
  used on the main camera pass" — is NEVER INVOKED on the live path.** The intended mechanism is
  already written and dead. Start there.

Per-block half-models were considered and REJECTED: an offset seam needs an arbitrary cut fraction,
not a fixed half, so real-time clipping is what generalises to the deferred offset work. Fluids and
block entities will not clip either way.

⚠ This is renderer work, adjacent to what the `is5-shadow` session touches. Coordinate before large
changes to the main-pass clip path.

**B. RAILS THROUGH A PORTAL (block logic).** The rest of this prompt.

## §1B THE RAIL JOB

**(b) step 2 — make rails CONNECT across the plane.** Step 1 built the cross-seam neighbour
primitive (`SeamBinding.continuationCell`, `SeamRegistry.bindingAcross` / `stateAcross`,
`SeamMap.phaseOf`), gated on BOTH topologies. **Nothing consumes it.** Rails still only mirror as
blocks; they do not join or carry carts.

The consumer is `RailState` — it captures a single `Level`, and `hasConnection` compares X/Z only.
`REDSTONE_B_SPEC.md` §2–3 has the design (`SeamShadow`, `SeamShadowBridge`, `MixinRailStateSeam`)
with a staged plan S1–S10.

**Re-check the spec as you go — it is older than the code:**

* It was written against an (a) carrying three defects since fixed, so any section reasoning about
  mirrored-state behaviour may describe the broken version.
* Its F2 re-entrancy analysis is stale — mirror writes use `UPDATE_SKIP_ON_PLACE`.
* Its §3.3 `shouldBeRemoved` wraps are probably dead for mirror-created cells, because mirror
  authority cancels `neighborChanged` before reaching them.
* One verifier in that panel read a NeoForge-patched decompile; the fold withdrew its citations.
  Verify against `C:\Users\warwa\ModDev\mc262-ref`.
* **NEW since the spec:** mirroring is now PLAYER-ONLY and EXACT-ALIGNMENT-ONLY (see the handoff's
  DECISIONS CHANGED). Any spec text assuming every write mirrors, or that offset seams mirror, is
  out of date.

Then **(c) redstone bridge** and **(d) minecart traversal**, both consuming the same primitive —
which is why it was deliberately built non-rail-specific. The user named dust and repeaters
explicitly.

## §2 STANDING DISCIPLINE — these are not platitudes, each has a scar

* **Instrument before theorising.** The same-dim bug survived three fixes written from three
  unfalsified theories. One probe run settled it.
* **Assert the OUTCOME the user can see, not the request your code issued.** Three gates in a row
  passed while the fix did nothing — "some rebuilds scheduled", then "this section scheduled", while
  the schedule was being discarded unread. Only "this section's mesh was REPLACED" could fail for
  the reason the user was seeing.
* **A reproduction that fails is not thereby reproducing the REPORTED failure.** Moving a test
  destination 60→600 blocks reproduced a real defect — a *different* one — and it was written up as
  the answer. Change one variable, not two.
* **Gates must assert their own COVERAGE**, and must be LEVER-AWARE: a verdict that does not invert
  under its own disable lever cannot tell "the fix works" from "the defect never existed here".
* **Beware code that looks like a check and isn't** — a `require = 0` mixin that weaves nothing, a
  comparison against itself.
* **Check panel findings against the USER DECISIONS doc, not only against source.** A verified proof
  can still carry a conclusion that is not yours to make. That happened once and silently disabled a
  feature the user had asked for.
* **Trust the user's live observations over your own readings.** Nearly every defect this engagement
  was found by them playing, not by analysis. Twice they caught what the suite passed.
* Every fix DEFAULT-ON behind `-Dseamlessportals.disableX`; every probe DEFAULT-OFF; a `-P` row in
  BOTH `fabric/build.gradle` blocks; A/B-attribute both directions.
* Commit messages via `git commit -F <file>`; `git add` with explicit file lists; push every commit.

## §3 GATES

```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamMirrorProbe=true
```
```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PdisableAperturePassthrough=true
```

Both must reach `ALL LEGS PASS`. Four more configurations exist for the levers added on 2026-07-26 —
see the handoff's GATE MATRIX. **Do not run the full matrix on every iteration**; run the canonical
first gate plus whichever inversion you touched, and the full sweep before a commit. The user has
already flagged the suite as slow, and there is a recorded proposal for an RS-only lever that would
skip the unrelated crossing legs.
