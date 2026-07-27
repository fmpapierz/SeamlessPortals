# IS5-MB HANDOFF — the Motion-Blur portal-window smear (**DIAGNOSED + FIX BUILT**) + the ACT engagement (CLOSED)

## §0 ★ THE ANSWER (2026-07-26) — read this before anything below

The IS5-CEN census settled it in two runs, both levers confirmed landed. Steady state, player
**stationary**, same-dim portal in view, 22 consecutive 1 Hz blocks, **exactly 2 `composite4` binds per
frame on the SAME program id** (same-dim source and destination share a dimension ⇒ ONE iris pipeline ⇒
ONE `CameraPositionTracker`):

```
[1] cam=(-2.473,10.370,500.031)  prev=(13.027,102.370,-2.469)  |cam-prev|=511.088  span=265.8 px
[2] cam=(13.027,102.370,-2.469)  prev=(13.027,102.370,-2.469)  |cam-prev|=  0.000  span=  0.0 px
```

Bind[1] holds the portal **destination** camera with the **player's** camera as "previous" — a constant
511-block offset that saturates the pack's `velocity/(1+|velocity|)` clamp. That saturated image is what
gets stamped into the window; the main chain has velocity 0, so the main view stays sharp. Cross-dim runs
a **different program id** on its own per-dimension pipeline and reads `|cam-prev| = 0.000` — the
measured origin of the asymmetry.

**Refuted by these runs — do not re-open:**
- **the "unsampled invocation" premise** (§2b): binds/frame is exactly 1 or 2, both always reachable;
- **the stamp depth write**: run 2 (`bound=NO-DEPTH-WRITE`, confirmed at the point of effect) is
  identical — `510.204`, `263.841 px`, ×19;
- **the inverse/previous matrix mismatch** (§2a-bis): `idMV = idP = 0.00000` in every steady-state
  block. The four matrices cancel exactly; velocity is 100 % `cameraOffset`.

**Why the original fix was inert** (`writes=1243`, symptom unchanged): it recorded the dest camera from
inside the armed portal bracket, but the bind inside that bracket is the one carrying the **MAIN**
camera. It "corrected" the already-innocent bind 1243 times and never touched the guilty one.

**THE FIX (built, suite-green): per-chain camera matching.** Every guarded bind's `cameraPosition` is
remembered for one frame; next frame a bind takes its `previousCameraPosition` from the **nearest**
camera the same program held last frame, within `IRIS_DEST_PREV_MAX_DELTA` (4 blocks — sized to one
frame's camera travel). No portal context at all. Stationary ⇒ `prev == cur` ⇒ sharp window; moving ⇒
that chain's own true delta ⇒ correct real blur; no candidate in range ⇒ neutralize (one blur-free
frame), which can never be worse than the saturating defect.

> **An ordinal-keyed version of this was written and REJECTED by the panel — do not reintroduce it.**
> It is not the bind ordinal that shifts, it is *the camera slot 1 carries*: the dest camera on frames
> where a dest render ran, the player's on frames where none did (`testShouldRenderPortal` is a per-frame
> GL occlusion query and genuinely flaps — the census recorded `binds per frame: min=1 max=2` inside one
> second). With a 16-block guard that injects the destination camera into the MAIN view for one frame on
> every visibility flap of any portal whose destination is within 16 blocks — i.e. **an ordinary doorway
> portal** — a full-screen blur flash, strictly worse than the bug.

**STILL TO DO: the live confirmation run.** Expected signature on bind[1]:
`PRE |cam-prev|=511.088 span 265.8 px → POST 0.000, span 0.000`, verdict `CORRECTED`, and a sharp window.

```
.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PcompositeCensus=true
.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PcompositeCensus=true -PdisableIrisDestPrevCamera=true
```


**Worktree** `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow`, branch
`iris-on/is5-shadow`, HEAD `c6250f0` (pushed). Session 2026-07-25 → 2026-07-26 05:45.

---

## §1 WHAT CLOSED THIS SESSION (done, live-confirmed, do not re-open)

### 1a. The ACT colored-light engagement — **CLOSED**, user-confirmed
*"colored light works now, ghost still gone … showing up nice and smooth."*

Root cause: **our own IS5-PH prev-uniform heal**. It resolved its target via
`Iris.getPipelineManager().getPipelineNullable()` at a post-portal-loop anchor, but a cross-dim nested
render calls `preparePipeline(destDim)` and iris's manager slot has **no restorer** — so the heal ticked
the **DEST** pipeline's `CameraPositionTracker` with the MAIN camera, putting `previousCameraPosition`
~132 blocks out. Every history read in the flood-fill then landed outside the volume.

Fix `e33bc4e`: capture the pipeline **before** `renderPortals()` and tick that one. Same-dim is
byte-identical (same object); only cross-dim changes, where the old heal was purely destructive.

| | DEST `\|posOffset\|∞` | floodfill nz |
|---|---|---|
| heal ACTIVE, pre-fix | 132 ×38 | plateau **90** |
| heal DISABLED | ≤2 on 89/89 | 135 → **15283** |
| heal ACTIVE + retarget | ≤2 on 188/204 | **4149–7293** |

### 1b. The per-dest ACT/TAA storage commission — **REFUTED before any code**
Cross-dim windows already own a separate floodfill volume (own pipeline ⇒ own `customImages`) and still
showed no colored light ⇒ storage was never the blocker. ~1 GiB/dest of machinery avoided by one look
through a nether portal.

### 1c. The MB bloom-ring commission — **premise CONFIRMED, work SUSPENDED**
Under MB, `composite4` gains `DRAWBUFFERS:30`, becomes the last colortex0 writer, and the mask slides
`composite4 idx=3 reads=ALT` → `composite5 idx=4 reads=MAIN` — i.e. after the bloom gather, so the ring
returns. **Verified live in a fresh launch.** Not built, because the smear (§2) dominates the view and
the ring cannot be judged until it is fixed.

---

## §2 THE OPEN PROBLEM — the Motion-Blur portal-window smear

**Symptom (user-observed, authoritative):** with the pack's Motion Blur ON, a **SAME-DIM** portal window
is uniformly blurred, **constantly, whether moving or standing still**. The main view stays sharp.
**CROSS-DIM windows are clean.** MB off ⇒ clean.

### 2a. MEASURED FACTS (do not re-derive)

| fact | value | how |
|---|---|---|
| blur scales with `MOTION_BLURRING_STRENGTH` | 0.15 visibly reduces it | user A/B |
| camera pair at the sampled `composite4` | `\|cam-prev\| = 0.000` | `IrisDestPrevCamera` 1 Hz series, 31 samples |
| ~~matrix `maxAbsDiff = 0.00000`~~ | **RETRACTED 2026-07-26 — NEVER MEASURED** | see §2a-bis |
| the original poisoned pair (pre-fix) | `cam=DEST, prev=MAIN, \|d\|=204–264` | `MbGateProbe` + the control row |
| our correction fires | `writes=1243 neutralize=0 miss=0 tracked=1 seamProven=true` | probe counters |
| after correction | that pass's `\|cam-prev\|` → `0.000` | 28 samples |
| aperture mask OFF | no change | user A/B |
| pack Bloom OFF | no change | user A/B |
| stamp depth write OFF | no change — **BUT UNCONFIRMED** (§2c) | user A/B |

### 2a-bis. THE RETRACTION — the matrix half was never measured (2026-07-26)

The row above claimed `matrix maxAbsDiff = 0.00000`. **It does not say that.** The actual log line, from
this worktree, reads:

```
fabric/runs/client-sodium/logs/latest.log:867  (13:37:49, 2026-07-26)
  maxAbsDiff(gbufferModelView, gbufferPreviousModelView)=n/a(loc -1/4)
  maxAbsDiff(gbufferProjection,  gbufferPreviousProjection)=n/a(loc -1/5)
```

`n/a(loc -1/...)` is `matDiff`'s **failure** return, not a zero. `gbufferModelView` and
`gbufferProjection` are **INACTIVE** in `composite4`: the pack declares them
(`lib/uniforms.glsl:66,125`) but that program never references them, so the GLSL linker strips them and
`glGetUniformLocation` returns `-1`. On 31 samples the matrix half returned `n/a` every time and was
read as `0.00000`.

**What this costs the argument in §2b.** The telescoping proof needs the previous matrices to equal the
current ones. `composite4` holds **no copy of the current matrices at all** — it has only
`gbufferProjectionInverse`, `gbufferModelViewInverse`, `gbufferPreviousModelView`,
`gbufferPreviousProjection`. So nothing in that program constrains `gbufferPreviousModelView` to be the
inverse of `gbufferModelViewInverse`, and **the premise that velocity telescopes to exactly zero has
never been tested.** The correct condition to test — computable from the four uniforms that ARE active —
is `MVprev · MVinv == I` and `Pprev · Pinv == I`. IS5-CEN measures exactly that, and additionally
replays the whole chain numerically.

This also killed a run before it happened: the first draft of IS5-CEN required all eight locations to be
valid and would have aborted every `composite4` measurement, printing a clean "no blur" acquittal from an
instrument that measured nothing. Caught by the adversarial final-diff panel, not by review of the spec.

### 2b. THE CENTRAL CONTRADICTION (this is the whole problem)

`composite4`'s velocity is
```glsl
previousPosition = gbufferPreviousProjection * gbufferPreviousModelView * (viewPos + cameraOffset)
velocity = (currentPosition - previousPosition).xy
```
With `cameraOffset == 0` **and** previous matrices bit-identical to current, this telescopes to
`previousPosition == currentPosition` **exactly, for any depth z** ⇒ velocity ≡ 0 ⇒ the pass is a
mathematical passthrough. Yet the blur is real and scales with strength.

**⚠ READ §2a-bis FIRST — the second premise ("previous matrices bit-identical to current") was NEVER
MEASURED.** The full chain is FOUR matrices, not two:

```
NDC --gbufferProjectionInverse--> view --gbufferModelViewInverse--> world
    --+cameraOffset--> --gbufferPreviousModelView--> --gbufferPreviousProjection--> NDC'
```

It cancels iff `MVprev·MVinv == I` and `Pprev·Pinv == I`. If the *inverse* uniforms belong to a
different camera than the *previous* uniforms, the chain does **not** cancel, and the residual is a
per-PIXEL, depth-dependent velocity — constant, needing no player motion, scaling with strength — while
`|cam-prev|` still reads exactly 0.000. **That fits every observed fact and is invisible to every probe
built before IS5-CEN.**

So there are now two live explanations, not one:
1. an unsampled `composite4` invocation (the original reading), **or**
2. a sampled invocation whose *inverse/previous* matrix pair never cancelled.

IS5-CEN discriminates them in one run: it counts binds per frame (settling 1) and reports
`idMV`/`idP` plus a CPU replay of the shader's own arithmetic (settling 2).

### 2c. THE THREE INSTRUMENTATION FAILURES THAT COST THIS SESSION

1. **Uniform probes cannot see a per-pixel defect.** Velocity is per-pixel (`z` from `depthtex1`);
   every probe reads uniforms, which are per-pass. Eight consecutive measurements read zero while the
   screen smeared.
2. **Levers that do not self-report produce void runs.** Three runs were wasted:
   `-PdisablePrevUniformHeal` silently never reached the JVM; a run had no cross-dim portal; and the
   **final `-PdisableStampDepthWrite` run has NO way to confirm the lever landed** — so "blur unchanged"
   is **NOT a refutation**. Fixed once via `RUN CONFIG:`; the new lever still lacks it.
3. **A once-only latch on a per-frame-varying value is invalid.** Two runs of the same build reported
   `\|cam-prev\|` = 0.000 and 564.239 respectively.

### 2d. THE NEXT MEASUREMENT (specified, not yet built)

**A per-frame CENSUS of every `composite4` bind** — not a 1 Hz sample:
- count binds per frame; for each, log `programId`, `cameraPosition`, `previousCameraPosition`,
  `|cam-prev|`, and the modelview `maxAbsDiff`;
- emit one aggregated block per second listing **all** binds seen in the most recent frame.

**Decision rule:** if some bind shows a large `|cam-prev|` or matrix diff, that is the smearing pass and
the correction re-points to it. If **every** bind in every frame is zero on both, then motion blur
genuinely cannot be the source and the MB toggle's *other* effect is — the added `colortex0` write
(`DRAWBUFFERS:3` → `:30`) and the ping-pong parity flip it causes (independently evidenced by C3-BLOOM's
plan moving `reads=ALT` → `reads=MAIN`).

**Before any of that: add a `RUN CONFIG:` self-ID line naming EVERY active lever**, and re-run the
stamp-depth A/B, because its result is currently unusable.

### 2d-bis. ALL THREE BUILT AND SUITE-GREEN (2026-07-26) — the run is now the only thing missing

| deliverable | where | lever |
|---|---|---|
| (a) run self-identification | `RunConfigReport` | always on, once per session |
| (a2) stamp-pipeline self-report | `IrisCompatPaste.stampPortalArea` | always on, once per session |
| (c) per-frame bind census | `IrisCompositeCensus` + 2 iris mixins | `-PcompositeCensus=true` |

**Why the stamp-depth leg was void for a SECOND reason.** `IrisCompatPaste:325` selects
`LEVER && PORTAL_AREA_SAMPLE_NO_DEPTH_WRITE != null`. If that sibling pipeline failed to register, the
lever **silently falls back to the depth-WRITING pipeline** — so even a lever that reached the JVM could
no-op with no trace. There is now a once-only line naming the pipeline actually bound.

**THE TWO RUNS.** Same build; pack Motion Blur ON; stand still at a **same-dim** window ~30 s, then a
**cross-dim** window ~15 s as the control.

```
.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PcompositeCensus=true
.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PcompositeCensus=true -PdisableStampDepthWrite=true
```

**How to read it** — the headline column is `maxSpanPx`, the blur span in pixels this bind would produce
at `MOTION_BLURRING_STRENGTH = 1`, computed by replaying `composite4.glsl:99-139` on the CPU over a
3×3×3 screen/depth grid (off-centre included: a rotational mismatch is exactly zero at the screen
centre).

| observation | verdict |
|---|---|
| one bind has large `maxSpanPx` | **that is the smearing pass** — `win=`/`layer=` say which phase, `idMV`/`idP`/`\|cam-prev\|` say which input |
| `idMV` or `idP` nonzero | the inverse/previous pair does not cancel — §2a-bis explanation 2 |
| every bind ~0 in every frame, no COVERAGE AUDIT warn | motion blur is **exonerated**; go to the `DRAWBUFFERS:3`→`:30` parity flip, evidence already in the `drawBuf=`/`readsAlt=` columns |
| a `COVERAGE AUDIT !!` warn appears | a velocity-capable program is being bound from a chain the census does not harvest — widen with `-PcompositeCensusPasses` |

### 2e. HYPOTHESES ALREADY REFUTED (do not re-open without new evidence)

R-1 dispatch never runs · R-2 degenerate work groups · R-3 reprojection (as a *camera-position* defect —
it was real and is now fixed, and did **not** change the symptom) · R-3b stale PER_FRAME uniforms ·
parity desync of `framemod2` · volume-clearing · the aperture mask · the bloom path · the bracket
approach (three different brackets, none captured a smearing pass) · `bumpPerFrameUniformCounter`
(**dead code** — `IPGlobal.irisPerFrameRefresh = false`, retired 2026-07-23; re-enabling it would
re-create a retired bug).

---

## §3 THE DECISION — RULED DEFAULT-OFF, THEN SUPERSEDED BY §0

**Current state: DEFAULT ON**, on a rewritten mechanism (§0). The default-OFF ruling below applied to the
bracket-keyed implementation, which was switched off precisely because it measurably fixed nothing; the
census then explained why and the correction was re-aimed at the bind it proved guilty. The enable lever
added during the default-OFF period has been **removed** — with the field defaulting true it could never
change the outcome, making it a lever that silently did nothing.

<details><summary>the superseded ruling</summary>


**`IrisDestPrevCamera` now defaults OFF** (`irisDestPrevCamera = false`), code + both mixins + all levers
kept. A new **enable** lever was required because the old one could only disable:
`-Dseamlessportals.enableIrisDestPrevCamera` / `-PenableIrisDestPrevCamera`; the disable lever still wins
if both are set. Inertness traced on the off-path: `arm()` returns before setting `armed`,
`correctIfDestChain()` returns on its first line, `onPassDrawn()` returns on the null pending — two
branches per composite bind and no GL calls. Flipping it also removes a confound from the IS5-CEN run,
since it wrote into the very uniform storage the census reads.

</details>

Also standing: `-PdisableStampDepthWrite` is a **diagnostic-only** lever — while set it re-opens the
`#13` two-portal depth artifact. Never ship it on. (Its hypothesis is now REFUTED — §0.)

---

## §4 THE KIT (all default-OFF unless noted)

| lever | what |
|---|---|
| `-PactProbe` / `-PactVolumeProbe` | ACT census/identity + the DSA volume readback (**`actVolumeProbe` is the lag source — never leave on**) |
| `-PactDispatchProbe` | ACT compute dispatch witness (2 log-only iris mixins) |
| `-PmbGateProbe` | composite4 uniform gate at the pass boundary |
| `-PdestPrevCameraProbe` | IS5-MB 1 Hz counters + DRAW-TIME STATE + MAIN-CHAIN CONTROL |
| `-PcompositeCensus` / `-PcompositeCensusPasses` | **IS5-CEN** per-frame bind census (§2d-bis) |
| `-PdisableIrisDestPrevCamera` | A/B off the per-chain camera correction (**DEFAULT-ON** — §0) |
| `-PirisDestPrevCameraMaxDelta` | IS5-MB chain-match limit in blocks (default 4.0) — **not** a safety margin, it is the definition of "same chain"; see §0 |
| `-PirisDestPrevCameraPass` | guarded-pass name override (default `composite4`) |
| `-PdisableStampDepthWrite` | **diagnostic only**; re-opens the `#13` artifact |
| `-PdisableIrisBloomApertureMask` | A/B the C3-BLOOM mask |
| `-PdisablePrevUniformHeal` / `-PdisableHealRetarget` | A/B the IS5-PH heal and its retarget |

Evidence docs in `migration/`: `ACT_RUN3_VERDICT.md`, `MB_SMEAR_VERDICT.md`,
`MB_PERDEST_PREVCAM_SPEC.md`, `PER_DEST_STATE_RECON.md`, `PER_DEST_STATE_RECON_CROSSCHECK.md`.

---

## §5 STANDING RULES (unchanged, binding)

NO GUESSING / diagnose-first · read the FULL `latest.log` every run, GL census first (baseline ~6 =
iris's `copyPre*`; **check the log's start timestamp against the event you are attributing**) ·
every fix DEFAULT-ON behind `-Dseamlessportals.disableX`, every probe DEFAULT-OFF, `-P` rows in **BOTH**
`fabric/build.gradle` blocks · A/B both directions · panels + adversarial verify + **final-diff against
implemented code** for every non-trivial mechanism · `.\gradlew.bat :fabric:runCrossingGametest` before
every commit · push every stage commit · `git add` explicit file lists only · Java cleanup by own-project
PID only, **never `gradlew --stop`** · iris mixin simple names must contain "Iris" · 26.2 is
UNOBFUSCATED — javap, never guess a signature · **gate GL features on ARB extensions, never core-version
flags** (this context reports `OpenGL42/43/45 = false` with the ARBs present — cost two probe legs) ·
trust the user's live observation over any inference.

Model tiers: **Opus 5** for design/adjudication/adversarial verify; the lighter **Opus (4.8)** for
mechanical recon (javap, decompile, log tabulation).
