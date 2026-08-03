# TP-XDIM HANDOFF — third-person cross-dimension camera + shaders = corrupted render

> ## ARC CLOSED (2026-08-01) — both defects fixed, both A/B-proven in BOTH directions
> **1. The whole-screen terrain vertex-transform explosion** — FIXED `4be60e0`, user-confirmed
> *"the nasty artifact is gone"*. Cause: `CrossPortalViewRendering` replaces `renderLevel`, so the
> dest world went through the DECOMPOSED driver, which never calls `LevelRenderer.render` — hence
> none of iris's per-frame gbuffer setup ran while the pack's programs still substituted. Fix =
> split the D23 layer-0 fallback BY CALLER so the frame-replacing cross view gets a real
> `LevelRenderer.render`. A rewrite to match IP, whose cross-view body was a nested `renderLevel`.
> **2. No portal window inside the cross view** — FIXED `9cf9d46`, user-confirmed. Cause: the
> FULL-PIPELINE dest core was missing the DECOMPOSED core's Step 10.10
> (`onBeforeTranslucentRendering`), which is why shaders-OFF already worked (M0 gate). Fix = the
> twin, dispatched after `render()` returns.
> **3. The crashes were never portal code** — a C2 JIT defect (6 victims, 5 in the portal
> occlusion-query / `doRenderPortal` region). **NOT vendor-specific and NOT resolved**: victim #6
> crashed on Zulu 25.0.4 too, so the JDK theory is dead (`c258873`) and the `CompileCommand` excludes
> — which were briefly deleted on a CONFOUNDED clean run that still had them active — are restored.
> The toolchain pin to Zulu (`d1fce7c`) is kept only as a newer patch level, not as a fix. Full
> record + the recognition signature: memory `temurin-c2-jit-crash`.
> **4. The first-person seam window shape-shift** — FIXED `7fd747a`, user-confirmed both ways. The
> stamp's near floor ran PER VERTEX and tilted the interpolated depth plane. See §9.
>
> **The closing A/B (`-PdisableCrossViewReverseWindow=true`), both directions:**
>
> | leg | window on screen | `xwin` | `invoke` |
> |---|---|---|---|
> | fix ON | present | `YES` 107/107 | `XVIEW-FULL x1` **+ `FULL-PIPELINE x1`** |
> | repro | gone | `NO` 458/458 | `XVIEW-FULL x1` alone |
>
> The second render disappearing IS the window pass not running. The explosion did NOT return on the
> repro leg (`xviewRouteLever=ON`), proving the two fixes are independent rather than entangled.
>
> **STILL OPEN, next in the user's order:** the first-person near-seam window shifting with camera
> panning — measured NOT to be this path (0 of 146 first-person frames entered it). See memory
> `firstperson-seam-window-panning-open`. Then: the MB bloom-ring commission, and the sharp-window
> polish re-audit.
>
> Everything below is the arc's working record, kept for its method lessons.

**Status: OPEN — census BUILT and its FIRST LEG RUN (2026-07-28). §0 below supersedes §1's
"unmeasured" list and §2's sub-claim 4.** Branch `iris-on/is5-shadow`, worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow`.

---

## §0 LEG 1 — THE CENSUS RAN (2026-07-28, commit `bdd8b62`, `-PtpXdimCensus=true`)

**Config proof (all gates green — this leg is adjudicable):** `[1/3] seamlessportals.tpXdimCensus
= true` AND `[2/3] TP_XDIM_CENSUS_LEVER = true`; one `ARMED` line; `entityPortalsFlag=true`;
`TARGET ACQUIRED at f=10203`; **10,488 frames classified, 272 emitted rows, 3,894 on-target
(third-person cross-dim) frames.** Control frames (ordinary first person) print
`renderLevel=YES(x1) is0=YES(x1) f1=YES:flagON` — the witnesses are alive, which is what licenses
reading a NO on a target row.

### §0.1 CONFIRMED, WITHOUT A SINGLE EXCEPTION (272/272 emitted RENDERED rows)
```
renderLevel=NO   is0=NO(sessionAnchorFrames=3608..4193)   f1=NO
invoke=D23-FALLBACK x1        bobbedProj=NULLED        portalLayerAtEnd=0
```
§2 sub-claims **1, 2 and 3 hold**: vanilla `renderLevel` never runs, the IS0 anchor never fires
(and `sessionAnchorFrames` climbing proves the anchor is woven and alive, so that NO is a
measurement and not a "never woven" artifact), the AFTER_TRANSLUCENT_TERRAIN driver never fires,
and the dest world ALWAYS goes through the D23 decomposed fallback — `FULL-PIPELINE` never once.

### §0.2 SUB-CLAIM 4 IS REFUTED — the pipeline is NOT foreign and NOTHING leaks
Measured on a cross-dim target frame (nether → overworld):
```
irisPre       = [pipeline=IrisRenderingPipeline@36f76cf5  irisCurrentDim=minecraft:the_nether]
irisDuring    = [pipeline=IrisRenderingPipeline@36f76cf5  irisCurrentDim=minecraft:overworld](inCrossView=yes)
irisDuringEnd = [pipeline=IrisRenderingPipeline@36f76cf5  irisCurrentDim=minecraft:overworld]
irisPost      = [pipeline=IrisRenderingPipeline@36f76cf5  irisCurrentDim=minecraft:the_nether]
```
The pipeline OBJECT is identical throughout and `irisPost` is correctly the source dim. Nothing is
left foreign, so there was never a slot to restore. What IS true is the mirror image of the
prediction: **the DEST world is rasterized through the SOURCE dimension's pipeline** — iris's
`preparePipeline` never runs because it hangs off the `LevelRenderer.render` this path skips.

### §0.3 THE TRIGGER IS NOT CROSS-DIM (user, with screenshots)
> "happens on same dim and dif dim portals when camera is on opposite dim to player"

The discriminator is the census's `cameraPastAperture=YES` — **the camera being on the far side of
the portal from the player** — NOT the dimension pair. Same-dim frames take the identical bare D23
path (`renderLevel=NO is0=NO f1=NO invoke=D23-FALLBACK`, 3,697 such frames this leg) and corrupt
identically. Since a same-dim portal's pipeline is trivially correct, **the wrong-dimension-pipeline
reading in §0.2 cannot be the carrier of the defect.** Do not spend the next session on it.

### §0.4 WHAT THE CORRUPTION IS (classified from the user's four screenshots)
A **geometry / vertex-transform explosion**, not a colour, depth, stale-frame or wrong-dim-content
fault. Real terrain textures (grass, stone, redstone dust are all legible) smeared into enormous
triangular spikes radiating from the camera. Decisive detail: in one shot **the sky renders
perfectly** (clean blue gradient + sun quad) while every piece of terrain explodes — so the
composite, the blit and the sky path are healthy and the fault is in the TERRAIN VERTEX TRANSFORM.
No GL errors are logged on these frames: the draws are legal, they transform wrong.

### §0.5 THE REFINED HYPOTHESIS (to test, not to assume)
On a cross-view frame the decomposed driver draws terrain through iris's gbuffer programs while
iris's per-frame setup — `beginLevelRendering` and everything it uploads (gbuffer model-view /
projection, chunk offsets, the per-frame uniform block) — **never ran, because it hangs off the
`LevelRenderer.render` that this path replaces.** The vertex shader therefore transforms with stale
or uninitialised values. This explains same-dim and cross-dim equally, and explains sky-fine /
terrain-exploded.

### §0.6 THE SHADERS A/B — RAN (leg 2, same client, K-toggle). §0.5 SURVIVES.
User: *"no problem with shaders off, only shaders on is fucked."* Both legs are config-proven and
BOTH exercised the cross-view path, so the clean leg is a measurement and not an absence:

| leg | RENDERED rows | renderer | dest driver | witnesses | result |
|---|---|---|---|---|---|
| shaders **OFF** | 16 | `RendererUsingStencil` | `BASE-DECOMPOSED x2` | `renderLevel=NO is0=NO f1=NO` | **clean** |
| shaders **ON** | 20 | `IrisCompatOn262Renderer` | `D23-FALLBACK x1` | `renderLevel=NO is0=NO f1=NO` | **exploded** |

**The apparent confound dissolves.** The two legs select different renderers, but the two
`invokeWorldRendering` branches call the IDENTICAL line —
`MyGameRenderer.renderWorldNew(worldRenderInfo, Runnable::run)` (`IrisCompatOn262Renderer`'s D23
branch and `PortalRenderer.invokeWorldRendering`). **The dest-render code is byte-identical across
the A/B.** The only thing that changed is whether a shaderpack's programs are active. That isolates
the carrier to *the pack's programs drawing this decomposed render without iris's per-frame setup* —
i.e. §0.5 — rather than to the cross-view camera/geometry math, which both legs share and which is
clean in one of them.

Residual, honestly stated: the legs also differ in dest-render COUNT (x2 vs x1). That bears on how
many dest passes run, not on which driver code executes, so it does not touch the conclusion.

### §0.7 THE FIX DIRECTION IS NOW LICENSED (§3 instrument 4's precondition is met)
The census confirmed the D23 fallback is what runs, so §3's "make the cross-view frame use the same
compat machinery the normal frame uses" is now evidence-backed rather than speculative. The
structural asymmetry to exploit: the full-pipeline driver runs ONE direct 8-arg
`LevelRenderer.render()` so iris's woven hooks re-enter NATURALLY (the whole IS1 design), while the
D23 fallback never calls it. The fallback's own stated reason — *"a full-pipeline render would
clobber the main target mid-frame"* — distinguishes its two layer-0 callers: real for
`GuiPortalRendering` (a mid-frame render into another target), but on a cross-view frame the main
target IS what we are painting, because this call REPLACES the frame. Whether that reasoning is
sound is for the fix design to verify against the code, not to assume.

---

## §1 THE SYMPTOM (user, verbatim)

> "when you cross a portal in 3rd person with shaders, and the third person camera happens to
> be in a different dim than the player, there is a totally corrupted artifact thing render
> that happens. it super insane."

Three conditions co-occur: **third person** + **shaders ON** + **the camera is on the other
side of the portal from the player** (i.e. camera and player are in different dimensions).
"Totally corrupted", not a subtle artifact — expect whole-screen garbage, not a seam defect.

**UNMEASURED and to be established first (do NOT assume):** whether it is shaders-only, whether
it needs a CROSS-DIM portal (vs same-dim), whether it survives after the crossing completes or
only during, and what the corruption actually IS (garbage colors / wrong-dim content / stale
frame / depth garbage / geometry explosion). The user's phrasing is a starting point, not a
classification.

---

## §2 WHY THIS PATH IS STRUCTURALLY DANGEROUS (code-grounded — read this before theorising)

The camera-in-other-dimension case is not a variation of the normal frame; it is a **completely
different frame path** that REPLACES `renderLevel`.

`CrossPortalViewRendering.renderCrossPortalView()`
(`common/src/main/java/qouteall/imm_ptl/core/render/CrossPortalViewRendering.java`) is invoked
from `MixinGameRenderer.seamlessportals$redirectRenderingWorld` — an `@WrapOperation` on the
`render → renderLevel` INVOKE. When it returns true it has rendered the frame ITSELF and
**vanilla `renderLevel` never runs**. Consequences, all documented in that file's own comments:

1. **The IS0 post-main anchor never fires.** `MixinGameRenderer_IPPostLevelAnchor` injects
   inside `GameRenderer.renderLevel` (@ INVOKE `LevelRenderer.render`, shift=AFTER). No
   `renderLevel` ⇒ no anchor ⇒ **the entire iris compat pass is skipped for that frame**:
   no snapshot, no per-portal dest render, no stamp, no blit-back, and none of the state
   hygiene that pass owns (`IrisTemporalTargetGuard.save/restore`,
   `IrisShadowCompositeSuppressor.install/uninstall`, `IrisDestPrevCamera`,
   `healPreviousFrameUniforms`, the stencil belts, the `IS5-*` fixes from the whole IS5 arc).
2. **The Fabric AFTER_TRANSLUCENT_TERRAIN driver never fires either** (it lives inside the
   replaced `renderLevel`) — the file says so explicitly and hand-rolls a
   `prepareRendering`/`finishRendering` bracket plus a manual `glDisable(GL_STENCIL_TEST)` to
   compensate. That compensation was written for the STENCIL renderer, long before the iris
   compat renderer existed.
3. **The dest world is rendered through the D23 fallback, not the compat pipeline.**
   `renderCrossPortalView` calls `IPCGlobal.renderer.invokeWorldRendering(worldRenderInfo)`.
   In `IrisCompatOn262Renderer.invokeWorldRendering` the first branch is
   `if (!isInsideOwnRenderPortals)` — which is TRUE here (we are not inside the compat pass's
   own `renderPortals` loop) — so it takes the documented D23 fallback
   `MyGameRenderer.renderWorldNew(...)`, the **decomposed** driver, whose own comment calls it
   *"strictly better than nothing; full fidelity deferred"*. That fallback was designed for
   GuiPortalRendering-style layer-0 calls, never validated as a whole-frame renderer under an
   active shaderpack.
4. **Dimension switch under a per-dimension iris pipeline.** The render targets
   `ClientWorldLoader.getWorld(portal.getDestDim())`. Iris keeps a pipeline PER DIMENSION;
   switching dimensions mid-frame calls `preparePipeline` for the other dim, and (measured
   earlier this project, see the IS5-ACT heal note in `IrisCompatOn262Renderer`) **nothing
   restores iris's pipeline-manager slot afterwards**. On the normal path the compat pass
   captures and heals that; on this path there is no such heal.
5. `RenderStates.capturedMainPassBobbedProjection = null` is set here deliberately, and the
   projection falls back to `cameraState.projectionMatrix` — a different projection source than
   every other iris-path frame uses.

**Working hypothesis to test first (NOT a conclusion):** the corruption is the cross-view frame
running the decomposed D23 fallback and/or a foreign-dimension iris pipeline with none of the
compat pass's state save/restore — i.e. the "nested render pollutes shared persistent state"
family (see memory `polish-rounds-lessons`), but at whole-frame scale instead of
window-shaped.

---

## §3 FIRST INSTRUMENTS (specified — build these before any fix)

Every one of these is cheap, log-only, and lever-gated DEFAULT OFF. Follow the discipline in §5.

1. **TP-XDIM FRAME CENSUS** (the classifier — build first). One line per second while
   `renderCrossPortalView()` is being entered, recording: returned true/false; camera dim vs
   player dim vs `mc.level` dim; `isThirdPerson()`; the portal hit; whether the IS0 anchor
   fired this frame (a boolean the anchor sets, read+cleared here); whether
   `invokeWorldRendering` took the D23 fallback or the full-pipeline branch; the iris pipeline
   identity BEFORE and AFTER the call (`Iris.getPipelineManager().getPipelineNullable()`
   identity hash + its dimension). This alone will confirm or destroy the §2 hypothesis and
   tells the next session which of the five consequences actually happens live.
2. **The shaders A/B** — same scenario with shaders OFF. If it is clean shaders-OFF, the
   corruption is in the iris interaction, not the cross-view geometry/camera math. (Cheap,
   run it early, it halves the search space.)
3. **A frame-capture of the corruption itself.** `SeamHandLocator`'s full-frame grid
   (`-PhandLocator`) already reads mainRT colour+depth over a 12x8 grid at four stages and can
   be re-pointed at this path; or use the `DrawCallTrace` one-frame capture
   (`/imm_ptl_client_debug debug_capture_frame_enable`) on a corrupted frame vs a control
   frame and diff the pass lists — that instrument found the S14 wedge cause in two captures.
4. **Only after the above:** if the D23 fallback is confirmed as the renderer, the fix
   direction is almost certainly *"make the cross-view frame use the same compat machinery the
   normal frame uses"* — i.e. give this path its own anchor-equivalent bracket rather than
   letting it run bare. Do not build that until the census says the fallback is what runs.

---

## §4 REPRO PROTOCOL (for the live legs)

Shaders ON (Complementary Reimagined r5.8.1 is the project's reference pack), third person
(F5), walk through a **cross-dimensional** portal slowly so the camera passes the plane before
the player. Then repeat with a **same-dim** portal, and repeat both shaders-OFF. Note for each:
does the corruption appear, when (camera crossing / player crossing / after), and does it clear.

---

## §5 THE DISCIPLINE (binding — the hand arc is the case study, and it cost days)

NO GUESSING / diagnose-first · read the FULL `latest.log` every run · **check the `RUN CONFIG`
block and the once-only self-report lines before adjudicating ANY leg** (a leg without its
landing proof is VOID, not a refutation) · never generalize from one sampled block · a probe's
failure sentinel is not a measurement · instrument every branch, not just the one your
hypothesis predicts · A/B both directions · **change ONE variable per leg** · panels +
adversarial verify + final-diff for every non-trivial mechanism ·
`.\gradlew.bat :fabric:runCrossingGametest` before every commit · push every stage commit ·
`git add` explicit file lists only · Java cleanup by own-project PID only, **never
`gradlew --stop`**.

**Instrument defects the hand arc paid for — do not repeat them** (full text in memory
`seam-hand-slicing-open`):
- **Validate a probe's AIM against a frame where the target is KNOWN present.** A fixed-column
  probe sat outside the in-window hand footprint and printed "all hops clean" — empty screen
  reported as evidence.
- **Coverage questions need PER-PIXEL classification.** Coarse cells (143x170 px) reported hand
  loss when only the background inside the cell changed.
- **Pass-boundary GL reads are not draw-time state.** Only `GlCommandEncoder.trySetup` RETURN
  is trustworthy for "what did this draw execute under". A whole fix was built on a boundary
  read and was inert.
- **Pin the window size across an A/B.** Three legs at three resolutions were compared using
  proportional probe coordinates — a two-variable comparison.
- **A symptom CHANGING is data.** "Progressive slice → instant vanish" is what revealed which
  of two fixes governed which failure.

---

## §6 THE ONE FACT MOST LIKELY TO MISLEAD THE NEXT SESSION

**This buffer is small-is-near / LEQUAL, NOT reversed-Z / GEQUAL.** Two shipped "fixes" were
built on the reversed-Z assumption and each did the exact opposite of its intent (handoff
§00z). Anything in this new arc that reasons about depth comparisons must start from the
measured convention, and must verify it at the DRAW, not at a pass boundary.

---

## §7 KIT ALREADY IN THE TREE (all DEFAULT OFF unless noted)

`-PhandLocator` (full-frame 12x8 grid + per-pixel row classifier, four stages) ·
`-PhandDrawDump` (draw-time GL state at hand AND stamp draws, via the `GlCommandEncoder`
trySetup hook — the pattern to copy for any new draw-time probe) · `-PhandSubmitTap`
(iris HandRenderer gate/body/transform tap) · `-PhandInLevelProbe` · `-PhandStageDiff`
(single-column, four compat stages — remember its aim caveat) · `-PseamContentProbe` ·
`-PdebugStampSolid` / `-PdisableStampDepthTest` / `-PstampLequal` (stamp discriminators) ·
`DrawCallTrace` via `/imm_ptl_client_debug debug_capture_frame_enable` · always-on: the
`IS5-SEAM` and `IS5-SEAM-ARM` censuses, `RunConfigReport`'s `RUN CONFIG` block.

Shipped hand fixes (DEFAULT ON, do not disturb): the hand depth bracket
`glDepthRange(0, 0.0005)` (`-PdisableHandSeamDepthBracket`) and the stamp NEAR FLOOR
`max(z, -0.998w)` (`-PdisableStampHandDepthCap`). Their values are load-bearing — §00z.

---

## §9 IS5-XCUT — THE FIRST-PERSON SEAM WINDOW SHAPE-SHIFT (CLOSED 2026-08-01, `7fd747a`)

Reported by the user in the same message that confirmed the cross-view explosion fixed. User-
confirmed BOTH ways: *"no more shape changing, hand is fine, window fills correctly"* and, on the
repro lever, *"shape changing is back"*.

**Cause.** The stamp's near floor ran PER VERTEX
(`gl_Position.z = max(gl_Position.z, -0.998 * gl_Position.w)`). Depth interpolates SCREEN-AFFINE, so
clamping per vertex computes `L[max(z,c)]` where the correct value is `max(L[z],c)` — it TILTS the
interpolated depth plane rather than clamping it. The S14.36 CPU clip leaves one aperture vertex
~0.1 mm from the eye (true NDC z ~ -1e3); flooring that one vertex skews the whole plane, and the
error is affine in screen space ⇒ a STRAIGHT boundary, pinned at the unfloored vertices (pivoting
about a corner) and SWEEPING as the clipped vertex slides with camera rotation.

**Fix.** Move the floor to the fragment stage: `gl_FragDepth = max(gl_FragCoord.z, 0.001)`, in new
siblings `portal_area_sample_floor.fsh` / `portal_area_solid_floor.fsh`. Same depth, same clamp
magnitude — only where it is computed, so §00z (hand) and IS5-SEAM (coverage) are preserved by
construction. A/B: `-PdisableXcutFragFloor`.

### §9.1 THE ATTRIBUTION TABLE — two legs, zero new code, a whole family eliminated
| leg | depth test | floor | swept cut |
|---|---|---|---|
| baseline | ON | ON | PRESENT |
| `-PdebugStampSolid -PdisableStampDepthTest` | OFF | ON | GONE (raw footprint = clean stable rect) |
| `-PdisableStampHandDepthCap` | ON | OFF | GONE |
The cut needed BOTH ⇒ the floor is the carrier and the aperture GEOMETRY is innocent. The CPU clip,
the mesh and the projection were never touched by the fix.

### §9.2 ★ MEASURE THE DEPTH CONVENTION — DO NOT INFER IT, NOT EVEN FROM BYTECODE
`GlDevice` genuinely calls `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` — verified by javap. From
that it follows that the near floor could never bind, that the §00z tuning was a placebo, and that
`-PdisableStampHandDepthCap` is a null lever. **All three are false at runtime.** The draw measured
`clipDepthMode=NEGATIVE_ONE_TO_ONE` (39/39) and `range=[0,1]` (26/26, across both floor-ON and
floor-OFF legs); something — most plausibly iris — sets it back before the stamp. Two
`glGetInteger` reads at `GlCommandEncoder.trySetup` RETURN refuted a confident, fully-argued,
bytecode-grounded conclusion in a single run. Those reads are now permanent in
`StampExecStateProbe`. Corollary also measured: the hand's `glDepthRange(0, 0.0005)` bracket is
SEQUENTIAL with the stamp, not nested around it.

### §9.3 THE STANDING WARNING THIS ARC EARNED
Five leads died here and ALL FIVE WERE MINE: the near-floor dismissal (a too-coarse
"distance can't respond to rotation" argument — a per-VERTEX clamp is rotation-sensitive because the
clipped vertex moves), the aperture clip as cause (disabling it made things WORSE — it was a
mitigation), the degenerate-w/guard-band story, the ZERO_TO_ONE claim, and (earlier in the session)
the JDK theory for the C2 crashes. The unifying error every time: **reasoning from something that
FELT like ground truth — an intuition, a code comment, bytecode, a clean run — instead of reading
state at the draw.**

And three instruments were lying when this arc reached them: a probe aimed where the target wasn't,
a sentinel matching a string no shipped shader contains (so every leg carried a false anomaly), and
a counter that printed `0` inside the very block meant to prove it had run. **Verify the instrument
before believing the instrument.**
