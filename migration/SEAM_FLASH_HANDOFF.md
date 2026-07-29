# IS5-SEAM HANDOFF — the black flash at the portal seam (ROOT CAUSE = THE C3-BLOOM MASK; C4-SEAM fix landed)

**Worktree** `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow`, branch
`iris-on/is5-shadow`. Session 2026-07-26 → 2026-07-27 01:20; continued 2026-07-27 (day session).

---

## §00 THE ACTUAL ROOT CAUSE (2026-07-27, evening) — supersedes §0's clip attribution

**The band's painter is the C3-BLOOM aperture mask** (`IrisBloomApertureMask`): inside the nested
dest composite it clears `colortex0` to BLACK and repaints only the aperture footprint — but its
repaints drew WITHOUT the depth clamp the stamp draws under. The S14.36 CPU clip cuts at the
CAMERA plane (viewZ < −EPS), NOT the 0.05 near plane, so at a crossing the 0–5 cm aperture shell
survives to the GPU: the stamp (clamp ON) rasterizes it, the mask (clamp OFF) loses it to hardware
near clipping — **mask ⊉ stamp exactly at the seam**, and the stamp copies the mask's cleared
black = the band. Shaders-ON-only by construction (the mask exists only in the compat route).

**The attribution chain that got here (each step live/log-verified):**
1. solid-stamp leg → band pixels ARE stamped; the sampled dest frame is black there;
2. v1 plane-shift relax, provably armed (`armedVoidRisk=0`) → band unchanged;
3. v2 full clip suspension, provably live (`SUSPENDED=70–87/s`) → band unchanged ⇒ **the terrain
   clip exonerated as the painter**;
4. `IS5-SEAM-CONTENT` probe → the band = black COLOR over NORMAL geometry depth (z≈0.050–0.051)
   ⇒ a color-only painter, not missing geometry;
5. one-frame `DrawCallTrace` → the mask's aperture-mesh build fires INSIDE the nested pass;
6. `-PdisableIrisBloomApertureMask` live leg → **band GONE** (bloom ring back = lever self-proof).

**THE FIX (C4-SEAM, DEFAULT ON):** depth-clamp the mask's 5 dilated repaints (the same `CHelper`
pair the stamp uses — exact raster parity; clamp DISABLED after = the composite chain's ambient
state; try/finally per the StencilPortalRenderer precedent). A/B: `-PdisableBloomMaskSeamClamp`
reproduces the band with the mask on.

**Retro-corrections to the earlier evidence:** fact 3's magenta band = the stamp faithfully
copying the mask's black (multiply-blind); the `front_clipping disable` "band gone" observation
was CONFOUNDED and is superseded by the controlled mask-off leg. The v2 crossing-window clip
suspension REMAINS SHIPPED — it fixes the real, separately-measured void/flash frame class
(`baselineVoid`) and provides the user-validated crossing content.

**Recorded follow-up (separate item, do not fold in):** the first-person HAND disappears and
reappears when the camera crosses the seam — persists independently of the band (user-observed on
the mask-off leg).

### §00a THE HAND ENGAGEMENT (2026-07-27 late) — THREE VALID REFUTATIONS; next = the stage diff

Symptom (user, precise): source→dest the hand slices away progressively from the seam direction
and pops back whole after crossing; dest→source it vanishes at once and returns part-by-part.
Adjudicated legs, EVERY one config-proven in the log:
1. `front_clipping disable` ⇒ unchanged — **clip family exonerated** (store disarmed at every
   hand draw; hand programs loc==-1 — recon `wf_00a03d77`);
2. shaders OFF ⇒ intact — compat-route-only (vanilla wipes depth pre-hand; iris bakes the hand
   INSIDE `LevelRenderer.render`, pre-anchor, and no-ops vanilla's call);
3. stamp NDC-z 0.5 cap (`vsh=capped` proven) ⇒ unchanged — **stamp overpaint refuted as the
   dominant carrier** (cap kept: verifier-passed, closes the clamped-sliver edge);
4. iris-HandRenderer `glDepthRange(0.999,1)` crossing-window bracket (`ARMED` line + 42 census
   seconds proven) ⇒ unchanged — **the hand-pass depth-test-loss hypothesis refuted**.

**Standing caution recorded:** the "hand shredded in the snapshot" reading rested on ONE mixed
hand-region probe row of 45 (possible mid-swing animation) — a one-sampled-block generalization;
treat it as UNCONFIRMED, not fact.

**THE STAGE DIFF RAN (2026-07-27 23:00, 15 sampled crossing frames) — VERDICT:**
- **A→B and C→D byte-clean on every block** — the snapshot copy and the blit-back are innocent.
- **On the slicing frames the hand is ALREADY ABSENT AT STAGE A**: the anchor-time mainRT holds
  the grazing shell (depth 0.981–0.994) where the hand's slice (0.554–0.556) should be, on 13/15
  blocks. The earlier one-row "snapshot shredding" reading is now CORROBORATED at scale.
- ⇒ **The eater acts INSIDE iris's `renderLevel`, upstream of the whole compat pass** — that is
  why all three downstream interventions changed nothing. And since the depth bracket (proven
  ARMED) did not save it, the loss is NOT a depth-test loss: the hand is either never DRAWN at
  those pixels (something culls/clips its geometry pre-raster) or drawn into colortex and eaten
  by a PACK COMPOSITE (TAA/reprojection rejecting the hand at the seam) before iris finalizes.
- **OPEN INSTRUMENT ANOMALY (audit before trusting the cap again):** stage C shows the stamp
  writing depth ≈0.655 — ABOVE the 0.5 cap — while the once-only line reports `vsh=capped`.
  Either the built resources did not carry the capped .vsh into that run, the compiled program
  is stale, or the C-stage depth is not the stamp's write. Settle with a live
  `glGetShaderSource` dump or a jar-resource check before any further cap-based reasoning.

**NEXT SESSION'S INSTRUMENTS (specified):**
1. Move the stage points INSIDE `renderLevel` using the ALREADY-LANDED HandRenderer mixin hooks
   (`MixinIrisHandRenderer_SeamDepthBracket` fires at HEAD/RETURN of both hand passes): read the
   hand-region pixels of the CURRENT draw target right after `renderSolid` and right after
   `renderTranslucent`, then at stage A — splitting "hand never drawn" from "hand drawn then
   eaten by a later in-renderLevel pass (pack composite/final)". Note the hand draws into iris's
   gbuffer targets there, not mainRT — resolve the live draw FBO, or read iris colortex0 via the
   IrisTemporalTargetGuard's target-access plumbing.
2. The cap-violation audit above.
Both shipped hand fixes stay (harmless, verifier-passed, band intact); the hand symptom itself
is UNCHANGED and OPEN.

### §00z THE HAND ARC — CLOSED, USER-CONFIRMED LIVE (2026-07-28: *"ITS FINALLY FIXED"*).

**Shipped DEFAULT ON:** the sign-corrected hand bracket (`glDepthRange(0.0, 0.0005)`) + the
stamp's NEAR FLOOR (`max(z, -0.998w)` = window depth 0.001). A/B levers:
`-PdisableHandSeamDepthBracket`, `-PdisableStampHandDepthCap`. Both the hand AND the window's
full-FOV coverage at the seam are user-verified.

**Floor tuning is load-bearing — do not widen it.** The first floor (window 0.005) fixed the
hand but caused a live regression: under LEQUAL it also loses to REAL geometry nearer than
~10 cm, which at a crossing is the portal frame/doorway around the camera, so the window
stopped filling the FOV and its region shrank/shifted with camera panning. The floor must
clear the HAND and nothing else: 0.001 floor vs a hand pinned into [0, 0.0005] leaves ~8000
representable 24-bit steps of margin while requiring real geometry to be within ~5 cm before
it can occlude the window.

**TWO independent eaters, both ours, both from the same wrong belief: that this depth buffer is
reversed-Z/GEQUAL. It is NOT — the hand pass proves small-is-near/LEQUAL (hand 0.5546 beats
scene 0.9945; a [0.999,1] remap loses to everything; [0,0.001] wins).**

1. **The IS5-HAND depth bracket had the wrong sign** (shipped 2026-07-27). It remapped the hand
   into [0.999, 1.0] intending "beat the grazing shell"; under the real LEQUAL convention that
   remap loses to EVERYTHING ⇒ total in-window vanish. This is what the whole §00a arc was
   chasing: "the hand is never rasterized" was OUR bracket. FIXED: `glDepthRange(0.0, 0.001)`.
   Symptom change on the fix leg (user): progressive slice → instant whole-hand vanish → (after
   the sign fix) back to the ORIGINAL progressive slice, which was eater #2 all along.
2. **The stamp's depth cap guarded the wrong side.** `min(z, 0.5w)` caps the FAR side (the
   reversed-Z assumption). The stamp executes `func=LEQUAL` (draw-time ground truth at
   `trySetup` RETURN) under `GL_DEPTH_CLAMP`; as the camera reaches the portal plane the
   aperture's projected depth falls to the near plane and CLAMPS to ~0.0, dropping below the
   hand's depth ⇒ LEQUAL lets the aperture win, sweeping across the hand as more of it crosses
   that threshold = the progressive slice, at every distance close enough. FIXED:
   `max(z, -0.99w)` — a NEAR FLOOR (window depth ≥ 0.005), always behind the bracket's hand
   ([0, 0.001]) and always in front of the scene the window replaces (~0.98). The two fixes
   COMPOSE BY CONSTRUCTION.

**THE MEASUREMENT THAT SETTLED IT (per-pixel, `-PhandLocator`):** classify each pixel of the
hand rows by its ANCHOR depth, then compare THOSE pixels after the blit-back.
- Pre-fix: `window d=0.00 → HAND-px 359 chg 359` (mean |dlum| 0.41), `row1 325/325`, repeatable;
  `window d=0.29 → 359 chg 0`; ambient always 0. ⇒ the compat pass eats the hand's OWN pixels,
  distance-gated.
- Post-fix: every window row `HAND-px chg 0` over 15 teleports, including frames where the
  aperture repaints 93% of the background row (`far 1328 chg 1239`).

**INSTRUMENT LESSONS THIS ARC ADDED (all three were MY errors, each caught by its own output):**
- **Fixed-column probes lie by omission.** The single-column stage diff (x=0.72W = grid c8) sat
  OUTSIDE the in-window hand footprint (c9–c11) and reported "all hops clean" — a measurement of
  empty screen. Validate a probe's aim against a frame where the target is KNOWN present.
- **Coarse cells manufacture false positives.** 143x170px cells read "hand color halved" when
  only the BACKGROUND inside the cell was replaced by portal content. Per-pixel classification
  is the only safe reading for a coverage question.
- **Pass-boundary GL reads are not draw-time state.** The hand pass re-applies its own func per
  draw; the only trustworthy read is at `GlCommandEncoder.trySetup` RETURN. The "LEQUAL leak"
  premise (IS5-HAND-FUNC, now DEFAULT OFF) died on this.
- **Three legs ran at three window sizes** (3440x1369 / 854x480 / 1718x1360) while comparing
  proportional probe coordinates — a two-variable comparison. Pin resolution across an A/B.

**LEVERS:** `-PdisableStampHandDepthCap` (no floor — reproduces the eating) ·
`-PdisableHandSeamDepthBracket` (no remap) · `-PhandLocator` (the per-pixel verdict) ·
`-PhandDrawDump` (hand + stamp draw-time state) · `-PhandSubmitTap` · `-PhandInLevelProbe` ·
`-PenableHandDepthFuncFix` (opt-in, premise refuted) · `-PstampLequal` (unused: the stamp
already runs LEQUAL).

---

### §00b THE 2026-07-28 CONTINUATION — the cap audit's STATIC verdict + both instruments BUILT

**The cap-violation audit (instrument 2) — static half SETTLED, and it found more than a stale
shader. Three facts, each independently checked:**
1. **`vsh=capped` was LEVER-ECHO, not effect-proof**: the once-only line prints
   `STAMP_HAND_DEPTH_CAP_DISABLED_LEVER ? "NOCAP" : "capped"` (IrisCompatPaste, the IS5-RC
   report args) — it can never detect a stale/mismatched program. Leg 3's landing proof was
   therefore weaker than recorded; its REFUTATION still stands via the stage-A evidence alone
   (the hand is absent BEFORE any stamp of the frame, so the stamp cannot be the eater).
2. **The built resources WERE capped in the stage-diff run** (timeline: cap commit `4161e83`
   22:29:11 → `fabric/build/resources/main/...vsh` copied 22:35:44, cap line verified present →
   run booted 22:58; no MC-side shader disk cache exists in the run dir).
3. **Yet the measured stage-C writes are DOUBLY impossible under the declared stamp state**:
   (a) GEQUAL-with-write can only RAISE a pixel's depth — B→C mean depth FELL 0.98→0.643-0.660
   on every painted bin of 13/15 blocks; (b) a capped vsh cannot emit any fragment above 0.5
   (per-vertex `min`, window-space-linear depth interpolation), and the sub-10cm hand-column
   fragments would write exactly 0.5000 — measured 0.6597-0.6603 in a smooth planar gradient
   (geometrically the aperture plane at ~7.6 cm, i.e. NATURAL nocap depth). And one frame's
   stage C read 0.0000 flat — exactly the clear value, i.e. almost certainly a FAILED read
   tabulated as zeros: SeamHandStageDiff never checks glGetError (the failure-sentinel trap,
   again, in our own instrument).
   ⇒ Either the EXECUTING GL state at the stamp is not the declared pipeline state (a
   state-application/iris-interaction defect), or the old probe's deferred-buffer reads are
   unreliable. DO NOT reason further from stage-C depth values until the executed-state probe
   below has reported.

**BOTH specified instruments are BUILT, adversarially verified (SOUND-WITH-FIXES ×1 +
PASS-WITH-FIXES ×1, all 10 actionable findings landed), suite-gated:**
- **IS5-HAND-INLVL** (`-PhandInLevelProbe`, DEFAULT OFF; `SeamHandInLevelProbe` on the landed
  bracket mixin hooks + a compat-anchor call): five stage points
  preSolid/postSolid/preTranslucent/postTranslucent/anchor; per stage the LIVE draw FBO column
  (glGetError-sentineled color+depth, PACK-bracketed, try/finally-restored, att0/dims
  identified) + iris colortex0 via read-only `IrisTemporalTargetGuard.peekColortex0()`
  (glGetTextureSubImage, PACK-bracketed, WRONG-SURFACE?-flagged — CORROBORATIVE ONLY under
  ping-pong flipping). Verdict signal = per-hop CHANGED-BINS with UNMEASURED/CROSS-TARGET
  guards; depth bands hand=[0.9985,1] / shell=[0.95,0.9985) valid only while the depth bracket
  is armed (armed-state recorded per emit). KNOWN READING TRAPS (verifier-proven from iris
  1.11.2 bytecode): iris calls renderSolid from `iris$beginTranslucents` and renderTranslucent
  from `iris$endLevelRender` — the postSolid→preTrans hop spans ALL translucent terrain; the
  hand passes leave their last per-program framebuffer bound at RETURN, so pre/post can read
  different targets (hence the CROSS-TARGET guard); "never drawn" includes iris's canRender
  GATE (F1/spectator/sleeping/no-item) — split gate-vs-submit next if that branch lands.
- **IS5-STAMP-EXEC** (`-PstampExecProbe`, DEFAULT OFF; `StampExecStateProbe` called right after
  the stamp's drawIndexed — synchronous GL backend, state-as-executed): once-only FULL dump
  (actual program id + attached vertex shader SOURCE + cap-substring presence) then 1 Hz
  in-window lines of actual depth test/func/mask/clamp/range + draw FBO + viewport, all
  glGetError-drained, liveness-announced. EXPECTED if all is well: func=GEQUAL writeMask=true
  clamp=true range=[0,1] CAP-IN-SOURCE=true. ANY mismatch is the anomaly's mechanism.

**THE LEG TO RUN:**
`.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PhandInLevelProbe=true -PstampExecProbe=true`
(bracket stays DEFAULT ON — the hand-band discriminator needs it). Cross slowly BOTH directions
several times. Before adjudicating: RUN CONFIG block + the THREE once-only lines (IS5-HAND-INLVL
ARMED, IS5-STAMP-EXEC ARMED, IS5-RC STAMP PIPELINE) + read the FULL latest.log.

### §00c THE LEG RAN (2026-07-28 00:05, ~1 min, crossings both directions) — TWO VERDICTS

**Config proven:** both probes armed (RUN CONFIG + both ARMED once-only lines), zero disarms,
zero anchor-miss warnings, bracket `armed=true` at BOTH post-pass captures on every block,
14 IS5-HAND-INLVL blocks + 1 full dump + 14 in-window IS5-STAMP-EXEC samples, teleports
interleaved (the user was genuinely crossing).

**VERDICT 1 — THE HAND IS NEVER RASTERIZED IN-WINDOW (the never-drawn branch CONFIRMED;
composite-eaten DEAD).** On ALL 14 blocks: iris colortex0-main is byte-identical across
preSolid→postSolid→preTranslucent→postTranslucent at the hand region (the hand passes painted
NOTHING), and the anchor's mainRT depth carries ZERO hand-band (≥0.9985) rows — with the
bracket PROVEN armed, any rasterized-and-depth-tested hand fragment MUST have landed ≥0.999 in
the shared main depth texture (depth has no ping-pong; the alt-surface caveat does not apply to
it). Historical control: the SAME column caught the hand slice (0.554-0.556) on 45 samples in
the pre-bracket sessions, so the column does catch a present hand. Instrument limitation
discovered live: at the pass boundaries the live GL draw binding is fbo=0 (the DEFAULT
framebuffer — iris binds its per-program FBOs only INSIDE the pass), so the fb rows are inert
padding; ct0 + anchor-depth carried the verdict.
⇒ NEXT SPLIT (the only one left): WHY does iris's HandRenderer emit nothing —
(i) its canRender-family gate declines the pass entirely in-window, vs (ii) the pass runs but
submits no geometry / geometry clipped pre-raster. Instrument: a require=0 tap on iris's
HandRenderer internals logging (1 Hz in-window + 0.1 Hz ambient CONTROL — the ambient rows
prove the tap sees a DRAWN hand normally): the gate decision, the held-item/arm submit count,
and GL_VIEWPORT/SCISSOR at the pass. OUR code is swept clean: `doRenderHand` is nested-render
plumbing (dead param in the sibling), no main-pass hand gate exists mod-side.

**VERDICT 2 — THE STAMP EXECUTES UNDER func=LEQUAL, NOT THE DECLARED GEQUAL (15/15 samples:
the full dump + all 14 in-window), with CAP-IN-SOURCE=true, clamp=true, writeMask=true,
range=[0,1], same prog=240 every time.** The declared-vs-executed mismatch is the stage-C
anomaly's mechanism-class (a raw-GL depth-func leak reaching the stamp's RenderPass apply —
prime suspect: the nested render's iris finalize leaves LEQUAL raw and the pass apply
short-circuits against a stale GlStateManager cache). CONSEQUENCE FOR THE HAND: under real
LEQUAL the 0.5 cap is INVERTED — capped fragments (0.5) pass against ANYTHING nearer than
10 cm (hand slice 0.554 included), so the stamp is a GUARANTEED deferred-buffer hand-eater
wherever the aperture covers a surviving hand; the uncapped natural depth (≥0.55 there) would
NOT overpaint the hand slice under LEQUAL. (Leg 3's "cap ⇒ unchanged" stays refuted-as-carrier
only because the hand is already gone upstream — VERDICT 1.)
**OPEN before ANY fix:** all 15 samples were in-window or the session-first — whether
out-of-window stamps also run LEQUAL is UNMEASURED, and the working normal-window content is
easier to explain if they run GEQUAL. The probe now emits 0.1 Hz AMBIENT (out-of-window)
samples to split this next leg. Fix CANDIDATE (do not build until the ambient data lands): a
cache-desync-buster before the stamp pass (cache-coherent `_depthFunc(GL_ALWAYS)` so the pass
apply re-issues the declared GEQUAL), lever-gated; audit the C4-SEAM mask + #13 + window
content under it — today's user-validated behavior was validated ON the LEQUAL-executing stamp,
so flipping it is a REGRESSION RISK, not a free correctness win. Yesterday's stage-C depth
NUMBERS (0.66/0.0000) stay quarantined: the old stage diff has no glGetError checks and one
frame provably tabulated a failed read as zeros.

---

## §0 (superseded by §00 on the ROOT CAUSE; the clip work below remains SHIPPED for the void class)

**Root cause, every link measured live:**
1. **Solid-stamp leg** (`-PdebugStampSolid`, log-valid `bound=SOLID`): the band turned WHITE ⇒
   stamp fragments pass the default depth test and survive ⇒ depth-rejection AND overpaint both
   refuted in one leg; the black is the SAMPLED dest content — `mainRT` after the nested dest
   render is itself pure black at the band.
2. **`/imm_ptl_client_debug front_clipping disable` live A/B (both directions):** band gone across
   repeated slow crossings while disabled, back on enable ⇒ **the IS3 inner clip plane carries it**.
3. **IS5-SEAM-ARM census** (95 rows, `maxAbsFeedErr=0.0000` on all — feed coherent): the band
   frames ARE the crossing window — `fullyVoid=6` (render eye at/past the armed plane ⇒
   whole-aperture void = the fast-crossing flash) + `nearStraddle=38` (eye 1–11 cm short ⇒ the
   seam sliver's grazing rays lose their near-side dest content for meters = the sustained band).
4. **IP comparison** (`ip-source`, verified): IP arms the IDENTICAL plane
   (`RectangularPortalShape.getInnerClipping` + `-ADJUSTMENT`) — IP fills those pixels with its
   UNCLIPPED sky; a deferred shaderpack has no filler ⇒ pure black, shaders-ON only.

**The fix (DEFAULT ON, `-PdisableSeamClipRelax` to A/B):**
`FrontClipping.innerClipCorrectionForCrossing` — IP's constant far from the plane; inside the
crossing window the inner clip ramps camera-side so the render eye stays KEPT-side of the armed
plane (clearance +0.20, bob budget 0.10; hold zone 0.30 covers the sprint-FOV sliver reach, ramp
out by 0.60). The load-bearing invariant (panel-corrected): an aperture ray can only void while
the EYE is on the CLIPPED side of the armed plane — holding the eye kept-side, every aperture
ray's near dest content draws. The extra content kept is the dest doorway interior the eye is
physically inside mid-crossing. Mirrors excluded (never crossed); nested recursion layers
excluded (layer-1 gate — also closes the inherited-outer-Mirror-plane hole). Scope: the compat
full-pipeline arm only (shaders-OFF keeps IP's sky filler).

**Verification instrument:** the ARM census counters were REKEYED (the relax deliberately pins the
armed clearance positive, so the old armed-plane classes would convict a working fix):
`baselineVoid`/`baselineStraddle` mark crossing seconds in every leg (nonzero EXPECTED);
`armedVoidRisk` is the health check — relax ON ⇒ MUST be 0; the disable leg reproduces the
pre-fix signature. Documented trade-off: leaning within 0.6 of a crossable portal without
crossing shows up to ~0.5 blocks of near-side dest content IP would clip (continuous, no pop).

---

## §1 WHAT CLOSED FIRST — the MB portal-window smear (do not re-open)

**CLOSED, user-confirmed live** (*"FINALLY NOT BLURRY"*), shipped DEFAULT-ON, suite green, pushed.
Full record in `migration/MB_SMEAR_HANDOFF.md` §00. One-line summary: same-dim source and destination
share a dimension ⇒ iris hands both chains ONE pipeline ⇒ the dest `composite4` drew with **two** wrong
pairs at once (camera AND previous-matrix). Fixed by per-chain nearest-camera state restore written at a
clobber-proof seam. Took four rounds; three were adjudicated with a defective census.

**The seam flash below was UNMASKED by that fix** — a full-strength blur over the window had been hiding
it. User: *"i guess i didnt notice it with the blur before."* **Expect more of this: the entire polish
queue was assessed against a blurred window.**

---

## §2 THE (formerly) OPEN PROBLEM — historical record; §0 supersedes the "next step" here

**Symptom (user-observed, authoritative):** crossing a portal shows **pure black, exactly at the seam**.
Crossing slowly it is sustained and clearly visible; crossing quickly it reads as a brief flash.

### 2a. MEASURED FACTS — every one of these is log- or A/B-verified, do not re-derive

| # | fact | how |
|---|---|---|
| 1 | pure black, exactly on the seam; slow crossing = sustained, fast = flash | user |
| 2 | **shaders OFF ⇒ gone**; no pack setting affects it | user sweep (see 2b) |
| 3 | `-PdebugTintStamp` turns the whole window magenta EXCEPT the band. **INFERENCE CORRECTED 2026-07-27 (§2c′):** the tint is a MULTIPLY, so this proves *no NON-BLACK fragment survives there* — NOT "the stamp does not cover the band" | user A/B + shader read |
| 4 | **predates this session** — reproduces at `082d533` | worktree checkout; log-verified (zero `IS5-RC` lines, that class did not exist yet) |
| 5 | **not `IrisDestPrevCamera`** | `-PdisableIrisDestPrevCamera`; log-verified `isIrisDestPrevCameraActive() = INACTIVE`, zero writes |
| 6 | the aperture mesh is **never null** and **never fully dropped**; it IS partially clipped from ~1.2 blocks in | IS5-SEAM census: `meshNull=false` 23/23, `dropped=0` 23/23, `clipped=2` on 13/23 |
| 7 | **the near-plane clip is INNOCENT** | `-PdisableAperturePlaneClip`; log-verified `kept=2 clipped=0 dropped=0` on 18/18 ⇒ every triangle passed through unclipped ⇒ **black still there** |

### 2b. The pack sweep that came back empty (all under Shader Pack Settings)

Temporal Filtering (Camera → TAA Settings) · Motion Blur (Camera) · Advanced Color Tracing
(Performance) · World Blur (Camera → World Blur Settings) · Edge Shadow SSAO Quality · Block Reflection
Quality · Water Reflection Quality · Light Shaft Quality · Distant Light Bokeh. **None removed it.
Only disabling shaders entirely did.**

### 2c. THE CONTRADICTION THAT DEFINES THE NEXT STEP

Fact 6 + fact 7 say the aperture geometry **fully covers** the band — with the clip disabled, every
triangle is passed through and nothing is dropped. Fact 3 says nothing non-black is **painted** there.
Geometry covers it, yet nothing visible lands.

### 2c′. THE TINT IS MULTIPLICATIVE (instrument audit, 2026-07-27)

`portal_area_sample.fsh`: `fragColor = texelFetch(InSampler, …) * vertexColor`. Magenta {1,0,1} ×
black {0,0,0} = black. **The magenta test cannot see a fragment that paints black content.** And an
overpaint AFTER the stamp would erase magenta too. So fact 3 never separated these **three live
branches**:

- **(A)** the stamp's fragments are **depth-rejected** at the seam (the original §2d hypothesis);
- **(B)** fragments **land**, but the SAMPLED dest content (`mainRT` after the nested dest render) is
  itself **pure black** in the band — the stamp faithfully copies black;
- **(C)** fragments land and are then **overpainted** by a later writer.

### 2d. THE NEXT MEASUREMENT (BUILT 2026-07-27, this session — awaiting the live legs)

The stamp pipeline is declared **`CompareOp.GREATER_THAN_OR_EQUAL` with depth WRITE ON**
(`IrisCompatPaste` static init; the GEQUAL direction is the R5 reversed-Z convention, the write is the
`#13` two-portal fix). The deferred buffer's depth is re-cleared and re-snapshotted from `mainRT` each
frame in `IrisCompatOn262Renderer.onBeforeHandRendering`.

Two new lever-gated stamp siblings (both DEFAULT OFF, diagnostic only; selection + once-only
`IS5-RC STAMP PIPELINE` self-report in `IrisCompatPaste.selectStampPipeline`):

- **`-PdisableStampDepthTest`** — depth state fully DISABLED (`Optional.empty()`, the proven
  `PORTAL_STRAIGHT_COPY` shape; GL disables depth WRITES with the test, so this strictly contains
  `-PdisableStampDepthWrite` and wins when both are set).
- **`-PdebugStampSolid`** — fragment paints solid vColor and IGNORES the sample
  (`portal_area_solid.fsh`; WHITE, or MAGENTA when combined with `-PdebugTintStamp`) — the
  content-free paint the multiplicative tint could never be. Composes with
  `-PdisableStampDepthTest` ONLY (SOLID+NO-WRITE is deliberately not built; the once-only line
  says so when both are passed).

**Panel-hardened (the verification panel's HIGH, bytecode-verified):** `RenderPipelines.register`
is a bare map-put — it neither compiles nor validates, and every REGISTERED pipeline joins the
eager precompile set of every subsequent resource reload (an invalid one hard-fails the reload).
So the siblings are **LEVER-GATED** (a default run registers zero new pipelines) and
**COMPILE-VALIDATED before registration** (`GpuDevice.precompilePipeline(...).isValid()`); an
invalid sibling is never registered, stays null, and selection degrades to the shipped default
with a VOID warning on the IS5-RC line. "Usable=true" on that line therefore means
*lever-requested AND compiled* for the three seam siblings (`noDepthTest`/`solid`/
`solidNoDepthTest`); the pre-existing `noDepthWrite` sibling ships unconditionally from the static
block and shares the shipped default's shader, so its column is a plain null-check.

**THE ADJUDICATION MATRIX (two legs, run in this order):**

| leg | config | band turns solid | band stays black |
|---|---|---|---|
| 1 | `-PdebugStampSolid` (depth DEFAULT) | fragments pass the depth test AND survive ⇒ **(B) the sampled dest content is black** — hunt the nested dest render / what iris leaves in `mainRT` at the seam | (A) or (C) — go to leg 2 |
| 2 | `-PdebugStampSolid -PdisableStampDepthTest` | **(A) the GEQUAL test vs the snapshot depth was rejecting** (and nothing overpaints) — find why the snapshot depth wins at the seam | **(C) overpainted after the stamp** or a non-depth rejector (scissor/mask) — next tool: the one-frame `DrawCallTrace` capture |

Optional leg 3: `-PdisableStampDepthTest` alone shows the seam with REAL content when depth is off —
worth one look if leg 2 lands on (A), since it is then a candidate shape for the fix.

**Before adjudicating ANY leg: read the `RUN CONFIG` block AND the once-only
`IS5-RC STAMP PIPELINE` line — it names the pipeline actually bound, every lever, every sibling's
registration state, and prints an explicit VOID warning on any degradation. A leg with a VOID
warning is re-run, not adjudicated. The IS5-SEAM census now also prints `stamp=<name>` on every row.**

The cheap `-PdisableStampDepthWrite=true` probe (write only, compare kept) remains available but is
superseded by the matrix above.

### 2e. REFUTED — do not re-open without new evidence

the near-plane clip (fact 7, mechanically verified) · a null/skipped mesh (`meshNull=false` on **41/41**
census rows over two runs — a full-screen fallback was built for this and REMOVED when the census
refuted it; do not rebuild it) · `IrisDestPrevCamera` (fact 5) · every pack effect (2b) · anything
introduced by this session (fact 4).

---

## §3 THE KIT LEFT IN THE TREE

| lever | what | default |
|---|---|---|
| `-PdebugTintStamp` | MULTIPLY-tints the stamp MAGENTA — produced fact 3; **blind on black content (§2c′)** | OFF |
| `-PdebugStampSolid` | stamp paints SOLID vColor, ignoring the sample (white; magenta with the tint lever) — the content-free coverage discriminator | OFF |
| `-PdisableStampDepthTest` | stamp depth state fully DISABLED (test+write) — the §2d depth discriminator | OFF |
| `-PdisableAperturePlaneClip` | passes every aperture triangle through unclipped (**diagnostic only**; nominally re-opens the S14.36 sky wedges, though none appeared in the live run) | OFF |
| `-PdisableStampDepthWrite` | stamp depth-WRITE off (**diagnostic only**; re-opens the `#13` two-portal artifact; superseded by `-PdisableStampDepthTest` for the seam work) | OFF |
| `-PdisableSeamClipRelax` | **THE FIX's A/B** — forces the crossing-window clip relax OFF, reproducing the band (§0) | OFF (fix ON) |

**IS5-SEAM-ARM census** — always on, 1 Hz, within 3 blocks of the active clip plane. Counter
semantics in §0 / the class javadoc; `/imm_ptl_client_debug front_clipping disable` remains the
zero-rebuild whole-mechanism kill switch.

**IS5-SEAM census** — always on, 1 Hz, only within 3 blocks of the aperture. Prints
`distToAperture`, `meshNull`, `stamp=<pipeline actually selected>`, and the near-plane clip's
`kept/clipped/dropped` triangle counts. This is what refuted two hypotheses in one run; read it
before theorising.

---

## §4 THE STANDING RULES (unchanged, binding — and this session is the case study)

NO GUESSING / diagnose-first · read the FULL `latest.log` every run · **check the `RUN CONFIG` block
before adjudicating ANY A/B** · never generalize from one sampled block · a probe's failure sentinel is
not a measurement · **instrument every branch, not just the one your hypothesis predicts** · A/B both
directions · panels + adversarial verify + final-diff for every non-trivial mechanism ·
`.\gradlew.bat :fabric:runCrossingGametest` before every commit · push every stage commit · `git add`
explicit file lists only · Java cleanup by own-project PID only, **never `gradlew --stop`**.

### Why those rules are in force, from this session specifically
- The smear took **four rounds**; every round where I reasoned from a compelling mechanism was wrong,
  and every round where I measured first was right.
- **Three separate census defects** each would have produced a confident false verdict: an all-eight
  uniform-location gate on a pass where two are inactive; a POST replay through stale matrices; a
  process-global action slot read at emit time.
- **Two watchdogs cried VOID on healthy runs** by counting frames in which the awaited event was
  impossible (title/loading screens).
- The `RUN CONFIG` block caught **two** wrong-configuration adjudications, including one where I had
  already told the user a conclusion drawn from the wrong run.
- On the seam specifically: a full-screen fallback was designed, built, shipped to a live run, and
  **refuted by its own census in one run** — because that census logged the alternatives, not just the
  hypothesis.
