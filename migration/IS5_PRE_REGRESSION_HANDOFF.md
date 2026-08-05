# IS5-PRE REGRESSION HANDOFF — gate the default flip

**★ EXECUTED 2026-08-05 — see `IS5_PRE_REGRESSION_RESULTS.md` for the session record: the §2
matrix ran on BOTH paths via a new scripted A/B driver (`runIs5RegressionGametest`), the §3.1 C2
unit-size leg PASSED (weld collapsed, dontinline biting at the exact call site), §3.2 part4
LANDED (nested capture-to-capture, depth-3 parity), and §3.3 the DEFAULT IS FLIPPED —
`-PdisableStageConsistentComposite` is now the shipped lever. One disclosed bound: the
MB-amplified loud ring is not reproducible on captured harness frames (either path); its live
pair rests on the user's 2026-08-05 closure + the mechanism gates, and remains a two-minute
live check on demand.**

Original brief (historical):
Status: the occluder-ring MECHANISM fix (IS5-PRE, stage-consistent compositing) is CLOSED and
user-affirmed 2026-08-05 ("all closers look good" — the ring is GONE, water/lighting rechecks
good, kept features intact). The dev default is STILL OFF. This session's job: a disciplined
regression sweep over every closed arc on BOTH paths, the two remaining engineering items, and
only then the default flip. Branch `iris-on/is5-shadow`; worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow`. Read
`IS5_PRE_DESIGN.md` for the full arc record (design §1-§6, stage ledger + S6 leg records in §7);
`OCCLUDER_RING_HANDOFF.md` for the original bug provenance.

## §1 WHAT SHIPPED (all pushed; every commit gated on ALL LEGS PASS)

`1e42990` design → `1b28c3c` S1 flag/levers → `d8acde1` S2 seams → `506dba8` S3 anchor →
`c4ef1d4` S4a reflection → `30fd9d4` S4b-1 capture → `bc5cba5` S4b-2 stamp → `3de43e7` S4b-3+S5
loop fork → then the S6 live-leg fixes: `fa45b98` (finally-scope), `84f3a31` (LEQUAL),
`9989963` (colour mask), `22b1207` (FBO pipeline identity), `752f12e` (NO FBO caching),
`c484bbd` (part5: depthtex1/2 + c6 aux), + the TAA history stamp, + the §3.8-fallback
camera-tracker bracket, `dd01b4d` ledger closure. One class owns the new machinery:
`IrisStageConsistentComposite` (+ the loop fork in `IrisCompatOn262Renderer`, the consume-only
method in `PortalRenderInfo`, two @Pseudo mixins).

**The lever**: `-PstageConsistentComposite=true` = the NEW path (dev). Absent = the SHIPPED OLD
path (the ring reproduces on command — the permanent B-direction). Verify on the live JVM via
`IS5-RC [1/3]`; the frame-start announcement prints `armDecision=ARMED` content-keyed.

## §2 THE REGRESSION MATRIX (run each on BOTH paths unless marked)

| arc | what to check | notes |
|---|---|---|
| A/B pair FIRST | ring GONE (new) / ring BACK (old), same scene, MB on | re-run even though closed — the pair belongs on one leg's record |
| IS5-BLOOMMB `4b913a5` | bloom light-bleed absent on OLD path (mask active); on NEW path the mask is call-site-gated — check no bleed AND no WARN noise | do not disturb the mask |
| IS5-HAND family + seam clip | first-person hand at a crossing; seam band; window shape stability near the seam | new stamp carries the per-fragment floor |
| Ghost double (§3.8 bracket) | move at a portal, TAA on — no whole-frame double | `camera tracker located` line must print |
| Kept FEATURES (user policy) | crossing blur burst present; window MB "cool" | do NOT fix these away |
| Resize/fullscreen/pack-toggle | window survives all (two latch classes closed) | capture-geometry lines track |
| Cross-view (XWIN) frames | third-person-through-portal still renders (OLD path there by design — the ring persists on those frames, DISCLOSED) | |
| Same-dim + cross-dim portals | both render; cross-dim brightness = source-exposure trade (disclosed) | |
| Shaders-OFF stencil family | untouched (K toggle mid-session; frame-start hook no-ops) | |
| `runCrossingGametest` | green (iris-absent; regression gate only) | |
| Crossing itself | seamless teleport both directions, both paths | the tracker bracket must not break the crossing burst |

## §3 REMAINING ENGINEERING (before the default flip)

1. **C2 unit-size acceptance leg**: dense portal scene, `-Pc2Inlining`, gate on UNIT SIZE never
   on a clean run (`C2_JIT_PROTECTION.md`); the query path was rewritten (consume-only split) —
   verify the dontinline anchor (`ViewAreaRenderer::renderPortalArea`, name unchanged ✓) still
   collapses the weld, and refresh `-Pc2LegacyExcludes` rows if any lambda moved.
2. **Part4 — recursion re-aim**: nested layers are DEFERRED on the new path (announced once,
   single-layer). Re-aim the three mainRT dereferences in `renderNestedPortalLayer`
   (snapshot :491/:519-520, stamp source :708, blit-back :542) at the parent capture buffer;
   capture-to-capture stamps are exact (unfiltered content).
3. **Default flip**: only after §2+§3.1+§3.2 — flip `STAGE_CONSISTENT_COMPOSITE_DEFAULT` true;
   `-PdisableStageConsistentComposite` becomes the shipped lever; update IS5-RC docs.

## §4 KNOWN-OPEN / CAVEATS (documented, not defects)

- Rotated portals: the tracker bracket saves POSITION only (prev MATRICES unbracketed — rotation
  part unpoisoned for non-rotated portals). If a rotated-portal ghost appears, extend the scan.
- Aux/history MRT is all-or-nothing (c0+c6+c2): packs missing any → plain stamp (window intact,
  enhancements absent, noted once). AUX/HISTORY target numbers are Complementary-r5.8.1-measured.
- TAA_JITTER shimmer inside the window (history = current ⇒ no accumulation there) — not yet
  user-flagged; check under TAA_JITTER=2.
- `capture center px` / `depth center after stamp` / 1Hz census are LIVE instruments (log-only)
  — mute or lever-gate them before any perf-sensitive milestone.
- Old-path probes (SeamHandStageDiff etc.) assume post-main ordering — STALE on new-path legs.
- Parked: iris `copyPreHandDepth` GL_INVALID_OPERATION at nether pipeline creation;
  `maxPortalLayer` never synced to dedicated servers; the C2 upstream report rewrite.

## §5 THE TRAPS THIS ARC PAID FOR (do not re-pay)

1. **Declared-vs-executed depth, AGAIN** — the raw-GL stamp shipped with declared GEQUAL and
   painted nothing; the vanilla pipeline translates, raw GL does not. LEQUAL per the measured
   small-is-near buffer.
2. **A HEAD-seam draw must assert EVERY write-enable** — nothing re-establishes state at a chain
   head; the write-free query loop left colorMask OFF (flashing + invisibility, zero errors).
3. **Never key GL caches on texture NAMES** — two latches (pack rebuild; resize with pipeline
   identity + recycled names). The only bulletproof key is NO key.
4. **`stamped=` joined `masks=` in the never-a-gate ledger** — draws issued ≠ effect achieved;
   the 1Hz per-stage census is what made silent drops attributable.
5. **Print the aim; prefer comparators** — a depth readback without aim was unreadable
   (third-decimal regime); the aim-independent d0/d1 comparator settled it in one look.
6. **Pre-registered checks work** — §3.8's "no bracket needed" failed EXACTLY at its registered
   check and the pre-named fallback shipped the same night. Register predictions before legs.
7. **The user's observation outranks the model** — "player movement, not panning" reframed MB→
   TAA; "faint magenta window in the ghost" identified whole-frame reprojection. Ask early.
8. **Content-key on STATE, not counts** — `stamped=1↔2` re-emitted constantly on a two-portal
   scene until the count moved to the census.

## §6 RUN COMMANDS

New path: `.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PstageConsistentComposite=true`
Old path (B-direction): drop the lever. Probe levers carry over: `-PdebugStampSolid`
`-PdebugTintStamp` work identically on the new path. Gametest before every commit; push every
stage commit; sidecar (`shaderpacks/*.zip.txt`) read at every leg start — absent key = pack
default (`shaders/lib/common.glsl`).
