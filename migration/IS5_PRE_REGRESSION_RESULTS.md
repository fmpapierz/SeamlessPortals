# IS5-PRE REGRESSION SESSION RESULTS (2026-08-05, the gate-the-default-flip session)

**Verdict: matrix PASS on both paths (one disclosed instrument bound), C2 unit-size gate PASS,
part4 landed, default FLIPPED.** Branch `iris-on/is5-shadow`. Handoff basis:
`IS5_PRE_REGRESSION_HANDOFF.md`; design record `IS5_PRE_DESIGN.md` §7.

## §1 THE INSTRUMENT — a scripted A/B driver, not hand legs

`Is5RegressionDriver` (fabric client-gametest, `runIs5RegressionGametest` run config): stages
the SAME scene (obsidian platform, 4x4 bi-way portal pair A/R with an orange+glowstone dest
wall, cross-dim 3x3 pair B/B_R to the nether roof, white occluder pillar between camera and
window, recursion portal N with a 3-west offset corridor) and drives the SAME motion on both
paths — the `-PstageConsistentComposite` lever is the only variable across a pair of runs. The
iris stage (pack + option sidecar + iris.properties) is copied from the live client-sodium run
dir at every launch and echoed to the build log (sidecar discipline; MB=1 str=2.00, TAA_JITTER=2
throughout). Landing proof is hard: with `-PirisRuntime` the driver throws unless the pack is
actually in use, and every leg's `IS5-RC [1/3]` block names the live JVM properties.
Screenshots land in `runs/gametest-is5/screenshots/` prefixed `new-`/`old-`.

## §2 MATRIX RESULTS (each row: NEW leg + OLD leg unless noted)

| row | result | evidence |
|---|---|---|
| A/B pair mechanism | PASS | NEW: armDecision=ARMED, capture/cancel/stamp census green (60 fps, 2-3 views/frame), zero breaks; OLD: seams path=OLD, old stamp path, zero errors |
| A/B pair, loud MB ring | BOUNDED (see §3) | ring ≤2px on BOTH paths across static + strafe (60/20 fps) + jump frames — at/below instrument resolution without MB amplification |
| IS5-BLOOMMB | PASS | OLD: mask ACTIVE + consuming (`C3-BLOOM LIVE sel=gatherer pass=composite4` per frame); NEW: call-site-gated, ZERO WARN noise; no visible rim glow either path |
| hand + seam | PASS | hand visible in first-person frames; crossing shots carry the hand; window shape stable through crossing/resize legs |
| ghost double (§3.8) | PASS | `camera tracker located via listener-capture scan` printed every NEW leg; no whole-frame double in any strafe frame |
| kept FEATURES | PRESENT both paths | crossing-arrival frames heavily motion-streaked on BOTH paths (the burst); the tracker bracket did NOT break it |
| resize | PASS | capture geometry tracked 1600x900 → 1024x640 → 1600x900 (same formats), window rendered at every size, census green throughout |
| shaders toggle (K-class) | PASS | `switched to renderer ...RendererUsingStencil` and back; stencil window correct; post-recreate window alive incl. under motion (the no-cache FBO rule holding) |
| XWIN same-dim | PASS | reverse window renders (the through-R frame shows the player + pillar) on both paths |
| XWIN cross-dim | PASS (after a scene fix) | with a nether-side twin, the nether frame shows the overworld through the reverse window (~98k sky px) both paths. A ONE-WAY portal correctly shows nothing from its dest side — the first empty shot was portal semantics, not a defect |
| same-dim + cross-dim windows | PASS | orange-wall window + dark nether window both render, both paths (cross-dim brightness = the disclosed source-exposure trade) |
| shaders-OFF stencil family | PASS | untouched; renders the window vanilla-style mid-session |
| crossing both directions | PASS | bi-way pair crossed north then south, arrival coherence asserted, yaw preserved, census continuous across the crossings |
| runCrossingGametest | GREEN | at the incoming tip AND after part4 |

## §3 THE RING ROW'S DISCLOSED BOUND (and what was measured trying)

The loud (MB-amplified) ring could not be made to reproduce ON EITHER PATH inside the
client-gametest harness: pack MB demonstrably runs (the crossing burst blurs; `Profile: Custom
(+19 options)` on load; the C3-BLOOM plan identifies the MB pass) but ordinary per-frame camera
deltas never manifest as smear on captured frames — measured at 60 fps, at 20 fps (one frame
per tick ⇒ every frame carries the full 0.7-block delta), and across single/multi-shot captures
of a deterministic 10-block camera jump (the blur lives on exactly the frames a screenshot
round-trip cannot land on; the crossing's burst persists a few frames and IS captured, but its
sightline holds no occluder+window). The IS5-CEN composite census self-reported MEASURED
NOTHING in-harness (arms during teardown) — instrument verified before belief, not trusted.

**The ring row's standing record therefore is:** mechanism gates green both directions (this
session, on the record) + ring ≤2px bounded both directions (this session) + the user's live
both-direction closure under real MB whips ("all closers look good", 2026-08-05 ~01:00, ledger
§7). The loud-ring live pair remains a two-minute check on the real client
(`runClientSodium -PirisRuntime=true` ± the disable lever, MB whip at an occluder) — listed as
the one user-eyes item, NOT a flip blocker: the flip's escape hatch
(`-PdisableStageConsistentComposite`) reproduces the old path on command.

## §4 C2 UNIT-SIZE ACCEPTANCE LEG — PASS (gate on unit size, never a clean run)

Config: `runIs5RegressionGametest -PirisRuntime=true -PstageConsistentComposite=true
-Pc2Inlining=true -Pis5C2HoldSeconds=720 -PirisMaxPortalLayer=6` — dense stack immediately on
load (6 stacked portals + recursion corridor + cross-dim pair), 50 hold cycles of strafe motion
+ cross-dim round trips (~100 dimension switches), 12m45s, dontinline DEFAULT active, no shader
toggling. Parsed from `runs/gametest-is5/c2_inlining.xml` (47457 tasks):

- **the flag bites**: 8 × `disallowed by CompileCommand`; decisively, task #42251
  (`renderAndDecideVisibility`, unit=183) DECLARES the `renderPortalArea` call site and welded
  it ZERO times — the refusal fired at the exact historical weld point;
- **the crash-form units are collapsed**: `$$Lambda::run` unit=2 (the crash form measured 385);
  `lambda$testShouldRenderPortal$0` unit=3 (dis=1); `performQuery*` units 4-9; the leaf
  compiles standalone (units 2/48/84);
- **no caller task anywhere contains a welded `renderPortalArea`** (0 parses across the file);
  `doRenderPortal`'s big unit (#42250, 332) holds zero references to the leaf — its size is
  ordinary machinery under C2's own NodeCountInliningCutoff (fired 50×);
- the consume-only rewrite moved NO lambda: `testShouldRenderPortal` still threads the same
  lambda into `renderAndDecideVisibility` (source + XML both show the same two compilable forms
  under the same names) ⇒ the `-Pc2LegacyExcludes` rows stay valid verbatim;
- survival: 12m45s under the dense recipe (the unprotected recipe crashed at 8m49s) — supporting
  evidence only, per the never-gate-on-silence rule.

## §5 PART4 — nested capture-to-capture re-aim: LANDED, DEPTH-3 PARITY PROVEN

The three mainRT dereferences COLLAPSE rather than re-aim one-for-one (the parent's capture IS
the snapshot; nothing to blit back): the armed nested loop runs bare, and fork (c) stamps a
nested slot's capture into the PARENT slot's buffer (same program/depth semantics as the main
stamp, no FBO caching, every write-enable asserted, aux when both sides hold one). §3.3
sharpened as judged (speculative cap counts layer 0 only). Census gains `nest=`.

**Leg results (offset recursion corridor, irisMaxPortalLayer default 5):** census
`armG=300 capt=300 stampPass=60 views=120 nest=180` per second at 60 fps = per frame 2 layer-0
captures + a FULL DEPTH-3 nested chain (layers 3→2→1 stamped innermost-first into their
parents), zero breaks, the part3 DEFERRED announcement gone; the NEW-path corridor screenshot
is **pixel-class identical to the OLD path's** — recursion parity, which single-layer part3
could not give. Crossing gametest green on the change.

**One defect found live and fixed in-session:** the nested-stamp once-note was content-keyed on
the CURRENT child layer, which a recursion corridor cycles 3→2→1 every frame — the exact
`stamped=1↔2` bouncing-key trap re-walked (~180 render-thread log lines/sec). Re-keyed to a
monotonic deepest-layer latch. The trap generalizes: on the new path, ANY per-view value is a
per-frame-cycling value once recursion exists.

## §6 FOUND & FIXED ALONG THE WAY (regression-session yield)

1. **`logSameDimSupplyProbe` crash on cross-view frames** (pre-existing, IS1-era `b4abc4f`):
   the probe violated `getRenderingPortal()`'s documented contract (peek-throws on the empty
   stack of an XWIN layer-0 pass) — unreachable until this driver first combined the
   screenshots lever with third person. One-line guard; the probe's own passKey always
   anticipated the null case. Crash `crash-2026-08-05_01.55.21` — a Minecraft-level crash, NOT
   the C2 signature (all four §1 points absent).
2. **One-way portals show nothing from the dest side** — correct IP semantics that read as a
   missing reverse window until checked; the driver now stages bi-way pairs, and the finding is
   worth remembering when staging future XWIN scenes.

## §7 KNOWN-OPEN AFTER THIS SESSION (unchanged unless noted)

- Cross-view (XWIN) frames keep the OLD compositing — ring persists there under MB (disclosed).
- Rotated portals: prev MATRICES unbracketed (§4 caveat) — extend the scan if a ghost appears.
- TAA_JITTER shimmer inside the window — not user-flagged; unobserved in harness stills.
- Parked: iris `copyPreHandDepth` GL_INVALID_OPERATION at nether pipeline creation;
  `maxPortalLayer` never synced to dedicated servers; the C2 upstream report rewrite
  (`C2_JIT_BUG_REPORT.md` — this session's XML shapes are fresh evidence for it).
- The live-instrument log lines (`capture center px`, `depth center after stamp`, 1Hz census)
  remain LIVE — mute or lever-gate before any perf-sensitive milestone.
- IS5-CEN does not arm inside the client-gametest harness (self-reported; teardown-time ARMED)
  — harness legs must not adjudicate MB uniforms from it.
