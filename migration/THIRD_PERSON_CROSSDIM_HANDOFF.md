# TP-XDIM HANDOFF — third-person cross-dimension camera + shaders = corrupted render

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

### §0.6 THE NEXT MEASUREMENT (specified, cheap, decisive)
**The shaders A/B — handoff §3 instrument 2, still NOT RUN** (`grep -c "class=RENDERED.*shaders=OFF"`
= 0 for leg 1). Toggle the pack with **K** in the same client; the census stamps `shaders=` on every
row so each leg self-identifies. Clean shaders-OFF while `RENDERED` rows keep flowing ⇒ the iris
per-frame setup is the carrier and §0.5 stands; corrupt shaders-OFF ⇒ §0.5 is dead and the fault is
in the cross-view camera/geometry math itself, which the D23 driver would share.

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
