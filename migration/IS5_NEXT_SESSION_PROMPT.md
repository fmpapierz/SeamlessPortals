# Next-session prompt (paste into a fresh session)

---

Continue the Seamless Portals iris shaders-ON work in the worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow`
(branch `iris-on/is5-shadow`, tip `cf8235a`, pushed).

READ FIRST, in order:

1. `migration/IS5_HANDOFF_2026_08_11.md` — THE GOVERNING DOC. What shipped (12 commits, all
gametest-gated), the lever inventory, the ONE open user decision, the next steps, the open
items, and the method notes.
2. On demand, the design records behind each piece: `IS5_DEPTHFORK_DESIGN.md` (the window
depth fork + the measured pack coupling), `IS5_ARRIVE_DESIGN.md`, `IS5_XDIM_DESIGN.md`,
`PERF_AND_FLICKER_2026_08_10.md` (the perf attribution + the flicker arcs).
3. Memory: `is5-perf-flicker-2026-08-10` (project state),
`feedback-one-leg-proof-and-forks` (the method lessons this arc earned),
`occluder-ring-mechanism` (★CLOSED — what must NOT regress),
`measure-at-the-draw-lessons`, `no-guessing-deep-debug-logs`, `temurin-c2-jit-crash`,
`feedback_cache_outlives_subject`.

THE TASK — finish the window-look arc

1. **Live-verify IS5-OUTLINE** (`d27468f`, built + gametest-green but NEVER tested): shaders
ON, look at a block in front of a portal — the source-terrain sliver hugging the selection
outline must be GONE, the outline still drawn on the block and clipped at the window edge.
A/B `-PdisableOutlineDepthWriteFix=true` brings the sliver back. Also target a block seen
THROUGH a portal (the `submitDestBlockOutline` twin).
2. **Run the fork legs and TAKE THE USER'S DECISION** (handoff §2 — the honest four-cell
table; each cell is one launch, no build): default/PLANE, `-Pis5WindowContentDepth=true`,
`-PcrossDimDestChain=true`, and **XDIM+CONTENT together (never run live)**. The user has
already seen PLANE (cool MB, MB-off ghost), CONTENT (ghost fixed, ordinary MB, stronger
source fog), and XDIM+PLANE (dest storm present, double-grade flatness they disliked).
Present the table, get the pick, do not choose for them.
3. **Flip the picked cell to default** (static-final + disable-row idiom, rows in all three
run blocks), update the IS5-RC docs and the handoff, gametest, commit, push.

DO NOT

* Do not "fix" a kept feature without an explicit ask: the portal-render motion-blur look and
the seam-crossing blur burst are USER POLICY ("Keep both of these as features, it is really
cool!"). The MB look is exactly what the PLANE cell preserves — the fork exists because it
cannot coexist with the MB-off ghost fix.
* Do not disturb the closed arcs: the occluder ring (IS5-PRE), the seam black band (the V2
clip suspension — IS5-ARRIVE only withholds it for ONE marked portal on ONE teleport frame),
the ghost double (§3.8 tracker bracket), IS5-BLOOMMB, the shaders-OFF stencil family, and the
three closed flicker fixes (XFLICK / BLINK / ARRIVE).
* Do not re-key any GL cache on texture names (two latches paid for this; NO key is the key),
and do not gate on `stamped=`/`masks=`-style counters — the 1Hz census is the readable
instrument.
* Do not gate a GPU-cost change on the `is5.*` PerfTimers — they are CPU-side and blind to
composite passes; use GL_TIME_ELAPSED or whole-frame FPS.
* Do not re-open the pack-coupled mid-composite restamp (the only both-ways road for the
fork) unless the user explicitly demands it — it is a rejected hack in the design record.

DISCIPLINE (unchanged, non-negotiable)

No guessing — instrument first; comparators over absolute readbacks; print the aim. Build the
detector to read RAW state and the fix to change the DECISION, so ONE live leg proves both.
Verify every lever on the LIVE process via `IS5-RC [1/3]`. Sidecar read (`shaderpacks/*.zip.txt`)
at every leg start — absent key = pack default. One variable per leg; a leg without its landing
proof is VOID. When an inference and the user's observation disagree, the observation wins —
and a trace-proven mechanism is not automatically THE mechanism (XFLICK cost a round to that).
Ask for the SIGNATURE (what varies it), not just presence/absence.
`.\gradlew.bat :fabric:runCrossingGametest` before every commit; push every commit;
explicit-file `git add`; Java cleanup by own-project PID only, never `gradlew --stop`.

RUN
`.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true [levers]`
Levers: handoff §5 (default-ON disables + default-OFF options/instruments).

AFTER THIS
`destRender` = 7.4 ms/view is THE remaining perf cost (2 portals ⇒ ~22-32 fps) — the three
measured levers the user declined are portal-view shadow clamp, alternate-frame view reuse,
reduced view resolution · the pre-warm debounce (every pack-option apply re-pays the compile)
· the 2-frame-blink hysteresis threshold 2→3 · the XDIM residuals (nested rim, DestPrevCamera
coverage) · parked: iris `copyPreHandDepth` GL_INVALID at nether pipeline creation,
`maxPortalLayer` never synced to dedicated servers, the C2 upstream report rewrite, and the
cross-view (XWIN) frames still running the OLD compositing (ring persists there, disclosed).
