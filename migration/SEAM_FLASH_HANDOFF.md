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
the mask-off leg). Likely related to the existing `teleport-hand-glitch-chain` memory.

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
