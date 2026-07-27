# FRESH-SESSION STARTER — THE SEAM CLIP (renderer). User-chosen next work, 2026-07-27.

*Paste this verbatim into a new session.*

---

Build the SEAM CLIP for Seamless Portals: cut source-dimension geometry at the portal plane on the
MAIN camera pass, so a seam block's far half stops drawing when viewed from the side and everything
beyond the plane comes from the mirrored copy.

Work in the worktree `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`
(branch `redstone/passthrough`). Tree clean, suite green, everything pushed.

READ FIRST, in order:

1. `migration/REDSTONE_NEXT_SESSION.md` — state (rails (b) is DONE and user-confirmed; the two
   mirror-policy decisions are CONFIRMED, do not relitigate), OPEN ITEM 1 (this job's design, done
   and adversarially verified), and the HAZARDS list. **Read the hazards properly.**
2. `migration/REDSTONE_RECON.md` §0 — pinned decisions, as amended by the handoff's DECISIONS
   CHANGED and the 2026-07-27 confirmations.

## §0 PARALLEL-SESSION RULES — RENDERER WORK, READ TWICE

Another session may be running in this repository on iris shaders-ON work, in
`.claude/worktrees/is5-shadow`. **This job touches the main-pass clip path that session sits next
to. Coordinate before large changes; never touch that worktree's files or logs; reading its branch
through git objects is fine.** Never run `gradlew --stop` (machine-wide; kills live games). Java
cleanup: own PIDs only, filtered by THIS worktree's path; `idea64.exe` is a JVM. Build scaffolding
(`build.gradle`, `settings.gradle`, `gradlew*`, `gradle/`) is UNTRACKED and must stay so — explicit
`git add` file lists only.

## §1 THE JOB — headlines from the verified design (full record: handoff OPEN ITEM 1)

- **Draw-time `gl_ClipDistance` via the existing `seamlessportals_ClipPlane` is the route.**
  Compile-time quad clamping is PERMANENTLY WRONG for same-dimension portals (one `ViewArea`, drawn
  in the same frame by two cameras on opposite sides of the plane) and silently no-ops under
  Sodium; draw-time is correct in every view and already has a Sodium path in this tree.
- **`FrontClipping.setupOuterClipping` — javadoc says it clips source-dim geometry past the plane
  on the main pass — is NEVER INVOKED on the live path.** The main pass resets the plane to
  keep-all at `renderLevel` HEAD; the only surviving main-pass clip is the per-ENTITY bracket. The
  intended mechanism is already written and dead. Start there.
- The obstacle: the clip plane is GLOBAL per draw, so seam sections need their own bracketed draw
  or a per-vertex "clippable" flag.
- ⚠ An oblique clip plane must not be coplanar with the geometry it cuts — exactly what a
  cut-at-the-plane face is. Use the existing `ADJUSTMENT` epsilon.
- Per-block half-models were considered and REJECTED (an offset seam needs an arbitrary cut
  fraction; real-time clipping is what generalises). Fluids and block entities will not clip either
  way — accepted.

## §2 STANDING DISCIPLINE — unchanged, every line has a scar

Instrument before theorising. Assert the OUTCOME the user can see (for a renderer job that means
the pixels or the mesh, not the request — three gates once passed on a fix that did nothing).
Gates lever-aware and coverage-asserting. Verify mixin targets with javap on the loom deobf jar
(`minecraft-merged-deobf-26.2.jar` in the gradle cache) BEFORE first launch — two silent drifts
were caught that way last session. Trust the user's live observations over your own readings.
Every fix DEFAULT-ON behind `-Dseamlessportals.disableX`; probes DEFAULT-OFF; `-P` rows in BOTH
`fabric/build.gradle` blocks; commit via `git commit -F <file>` with explicit file lists; push
every commit.

## §3 GATES

```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PseamMirrorProbe=true -PrsOnly=true
```

is the fast iteration gate (~3.5 min; RS-only mode, user-approved). Full matrix before any commit:
the two FULL suites (canonical + `-PdisableAperturePassthrough`) plus the inversion configs listed
in the handoff's GATE MATRIX, plus whichever new lever this job adds, in both directions. A
renderer change likely needs LIVE eyes too — screenshots via `-PgametestScreenshots=true` legs
exist, but the user's own look is the real gate; ask for it.

After the clip: (c) redstone bridge (power across the seam — the powered-rail chain the user
already found is its first target), then (d) minecart traversal.
