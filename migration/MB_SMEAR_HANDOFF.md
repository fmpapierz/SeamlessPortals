# IS5-MB HANDOFF — the Motion-Blur portal-window smear (UNRESOLVED) + the ACT engagement (CLOSED)

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
| every PASS-level velocity input at the sampled `composite4` | `\|cam-prev\| = 0.000`, matrix `maxAbsDiff = 0.00000` | `IrisDestPrevCamera` 1 Hz series, 31 samples |
| the original poisoned pair (pre-fix) | `cam=DEST, prev=MAIN, \|d\|=204–264` | `MbGateProbe` + the control row |
| our correction fires | `writes=1243 neutralize=0 miss=0 tracked=1 seamProven=true` | probe counters |
| after correction | that pass's `\|cam-prev\|` → `0.000` | 28 samples |
| aperture mask OFF | no change | user A/B |
| pack Bloom OFF | no change | user A/B |
| stamp depth write OFF | no change — **BUT UNCONFIRMED** (§2c) | user A/B |

### 2b. THE CENTRAL CONTRADICTION (this is the whole problem)

`composite4`'s velocity is
```glsl
previousPosition = gbufferPreviousProjection * gbufferPreviousModelView * (viewPos + cameraOffset)
velocity = (currentPosition - previousPosition).xy
```
With `cameraOffset == 0` **and** previous matrices bit-identical to current, this telescopes to
`previousPosition == currentPosition` **exactly, for any depth z** ⇒ velocity ≡ 0 ⇒ the pass is a
mathematical passthrough. Yet the blur is real and scales with strength.

⇒ **There must be a `composite4` invocation that has never been sampled.** Every probe so far samples at
most one bind per second per role; none has ever counted binds per frame.

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

### 2e. HYPOTHESES ALREADY REFUTED (do not re-open without new evidence)

R-1 dispatch never runs · R-2 degenerate work groups · R-3 reprojection (as a *camera-position* defect —
it was real and is now fixed, and did **not** change the symptom) · R-3b stale PER_FRAME uniforms ·
parity desync of `framemod2` · volume-clearing · the aperture mask · the bloom path · the bracket
approach (three different brackets, none captured a smearing pass) · `bumpPerFrameUniformCounter`
(**dead code** — `IPGlobal.irisPerFrameRefresh = false`, retired 2026-07-23; re-enabling it would
re-create a retired bug).

---

## §3 THE OUTSTANDING DECISION

**`IrisDestPrevCamera` is DEFAULT-ON and fixes nothing observable.** It corrects a real, measured
264-block `previousCameraPosition` defect (`writes=1243/run`), but the smear is unchanged. Shipping a
default-on render-path change with no visible benefit is poor hygiene. **Recommend defaulting it OFF**
(`irisDestPrevCamera = false` in `IPGlobal`), keeping the code and lever so the state is available if
the parity work needs it. **The user has not yet ruled.**

Also standing: `-PdisableStampDepthWrite` is a **diagnostic-only** lever — while set it re-opens the
`#13` two-portal depth artifact. Never ship it on.

---

## §4 THE KIT (all default-OFF unless noted)

| lever | what |
|---|---|
| `-PactProbe` / `-PactVolumeProbe` | ACT census/identity + the DSA volume readback (**`actVolumeProbe` is the lag source — never leave on**) |
| `-PactDispatchProbe` | ACT compute dispatch witness (2 log-only iris mixins) |
| `-PmbGateProbe` | composite4 uniform gate at the pass boundary |
| `-PdestPrevCameraProbe` | IS5-MB 1 Hz counters + DRAW-TIME STATE + MAIN-CHAIN CONTROL |
| `-PdisableIrisDestPrevCamera` | A/B off the per-dest fix (**currently DEFAULT-ON**) |
| `-PirisDestPrevCameraNoMatrices` | A/B the matrix half |
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
