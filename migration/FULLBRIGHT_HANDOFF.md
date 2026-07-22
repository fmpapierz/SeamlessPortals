# IN-PORTAL FULLBRIGHT — DEBUG HANDOFF (2026-07-22)

Continue debugging the **shaders-ON in-portal fullbright** here. Read `§0 STANDING RULES` first, then
`§4 THE NEXT MOVE` (it is a PROBE, not a fix — diagnose-first).

---

## §0 STANDING RULES (do not skip)
- **NO GUESSING / DIAGNOSE-FIRST** (user, emphatic). Instrument before theorizing. This session's fullbright
  fix was implemented from a *bytecode-confirmed* mechanism + a *SOUND* design panel and STILL failed live —
  proof that the confirming **probe should have run first**. Do the probe (§4) before touching the fix again.
- **Deep-Opus verify protocol** for non-trivial work (6 lenses + judges, or the workflow panels). Depth over speed.
- **Worktree:** `C:/Users/warwa/ModDev/Portals/Portal 26.2/.claude/worktrees/is5-shadow`, branch
  `iris-on/is5-shadow`. HEAD after this session = `e408a0d`. Push stage commits to origin `iris-on/is5-shadow`.
- **Live testing:** the user runs `.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true` from is5-shadow
  (iris + Complementary Reimagined r5.8.1). Reserve the user for experiential verdicts; self-run for
  log-classifiable evidence (`fabric/runs/client-sodium/logs/latest.log`).
- **Iris jar for javap:** `/c/Users/warwa/.gradle/caches/modules-2/files-2.1/maven.modrinth/iris/1.11.2+26.2-fabric/f7d526b1062c4bfe2567113cf933d1de26eddd3f/iris-1.11.2+26.2-fabric.jar`
- **IP source (reference):** `C:/Users/warwa/ModDev/ImmersivePortalsMod/` (NB: IP has NO shaders — no lighting
  reference, but its `ExperimentalIrisPortalRenderer` is the precedent for the counter-bump we tried).

---

## §1 WHERE THE SHADERS-ON ENGAGEMENT STANDS
Four shaders-on portal faults were found this arc. **THREE ARE FIXED + LIVE-CONFIRMED + COMMITTED** on
`iris-on/is5-shadow`:
| Fault | Commit | Mechanism / fix |
|---|---|---|
| Same-dim terrain **flash** | `ca6e93b` | shared per-region `MultiDrawBatch` not isolated → per-portal-layer batch isolation (`MixinSodiumRenderRegion`) |
| **Two-portal depth** | `e8e4767` | portal-area stamp dropped IP's depth WRITE → `IrisCompatPaste` PORTAL_AREA_SAMPLE `writeDepth false→true` |
| Whole-screen **phantom** | `e408a0d` | dest render pollutes iris's `colortex2` TAA history → `IrisTemporalTargetGuard` save/restore of clear=false color targets |

**THE FOURTH — the in-portal FULLBRIGHT — is NOT fixed (this doc).**

Also on this branch, **UNCOMMITTED**: the IS5 shadow-sync WIP (`ShadowEmptinessProbe` changes, the
`MixinSodiumAsyncCameraTimingControl_PortalShadowSync` mixin + its mixins.json registration, the IPGlobal
`shadowSync*` members). That mixin is **DEAD** (its `getShouldRenderSync` gate never fires — iris short-circuits
before it; live-proven `forcedSyncSinceLastCapture=0`). Leave it or revert it; it is unrelated to the fullbright.

---

## §2 THE SYMPTOM (fullbright) — authoritative
Under a shaderpack, the DEST terrain seen THROUGH a **same-dim** portal is **too bright ("fullbright"), and it
is MAIN-CAMERA DIRECTION-DEPENDENT**: at some facings the dest terrain looks correct, at others fullbright, and
**panning toggles between them** ("same issue at different viewing angles"). User-confirmed direction-dependent.
- DISTINCT from the phantom (now fixed) and from the shadow-map (EXONERATED: populated at rest; the
  async-shadow-sync fix is DEAD). The lightmap TEXTURE is exonerated (screen-uniform LUT).
- Shaders-OFF (stencil renderer) has correct parity — this is a shaders-ON-only, compat-renderer fault.

---

## §3 THE CONFIRMED MECHANISM (bytecode — from the fullbright-lighting-research workflow)
**iris PER_FRAME lighting-uniform STALENESS on the nested same-dim dest pass.** Bytecode-certain chain:
1. `net.irisshaders.iris.gl.program.ProgramUniforms.update()` (bci 94-122): `frameNow = SystemTimeUniforms
   .COUNTER.getAsInt(); if (lastFrame == frameNow) return; else { lastFrame = frameNow; updateStage(perFrame); }`
   — perFrame uploads ONLY when the counter advanced. `lastFrame` inits -1, PER ProgramUniforms instance.
2. `MixinGameRenderer.iris$startFrame` @Inject `GameRenderer.render` HEAD → `SystemTimeUniforms.COUNTER
   .beginFrame()` (count=(count+1)%720720). **SOLE** per-frame beginFrame caller (jar-grep). So COUNTER advances
   ONCE per frame at HEAD, constant across the main pass AND the nested dest pass.
3. The dest is a re-entrant 8-arg `LevelRenderer.render` (`SecondaryWorldRenderCore.renderDestWorldFullPipeline:1747`),
   NOT a `GameRenderer.render` → COUNTER not re-advanced. Same-dim → `PipelineManager.preparePipeline` returns the
   SAME cached `WorldRenderingPipeline` → SAME `ExtendedShader`s → SAME `ProgramUniforms` whose `lastFrame ==
   COUNTER` from the main pass → **the dest terrain draws SKIP the perFrame upload** → dest lit with the MAIN
   pass's per-frame lighting uniforms. (Geometry is dest-framed because `ExtendedShader.iris$setupState` sets
   modelViewInverse/projectionInverse/normalMatrix DIRECTLY each draw, ungated.) Sodium's terrain program is ALSO
   an `ExtendedShader`, so terrain draws hit the same gate via `MixinGlCommandEncoder.iris$setupState`.
   → main-camera direction-dependent fullbright toggling on pan = the symptom exactly.

**The stale PER_FRAME uniforms** (`UniformUpdateFrequency.PER_FRAME`, skipped for dest draws): CameraUniforms
(cameraPosition, previousCameraPosition, eyeAltitude, near, far), MatrixUniforms (gbufferModelView/Inverse,
gbufferProjection/Inverse, previous), shadow matrices (shadowModelView/Projection/Inverse), CelestialUniforms
(sunPosition, moonPosition, shadowLightPosition, upPosition, sunAngle, shadowAngle). Full recon in the task
output: `…/tasks/w2g03lnzv.output` and `…/tasks/wxa3wihie.output` (recon + design; journal in
`…/subagents/workflows/wf_f2532817-099/journal.jsonl`).

---

## §4 THE ATTEMPTED FIX + WHY IT FAILED (the crux for the next session)
**Attempt (UNCOMMITTED, in the working tree):** bump `SystemTimeUniforms.COUNTER.beginFrame()` BEFORE + AFTER
the nested dest render, via a new facade `IrisInterface.invoker.bumpPerFrameUniformCounter()` (Invoker no-op /
OnIrisPresent real, lever `IPGlobal.isIrisPerFrameRefreshActive()` DEFAULT-ON), called in
`IrisCompatOn262Renderer.invokeWorldRendering` bracketing `MyGameRenderer.renderWorldFullPipeline`. A/B off:
`-PdisableIrisPerFrameRefresh=true`. IP precedent: `ExperimentalIrisPortalRenderer.invokeWorldRendering:169/171`
does exactly this. **The design panel returned SOUND+SOUND(none)** for this.

**RESULT (live, 2026-07-22): "still same problem" — the bump FIRED WITHOUT ERROR but did NOT fix the fullbright.**
(No crash, no iris exception in the log. The ~6-8 `Invalid format` GL lines in the log are the SEPARATE, known
phantom-fix residual — not from this.)

**Why it probably failed — hypotheses to test, most-likely first:**
1. **RE-SOURCE WAS NEEDED (leading).** The re-upload runs, but the SOURCES are still MAIN-valued at the
   re-upload instant, so it re-uploads MAIN values → no visible change. The design claimed the sources are
   already dest-primed (iris sets dest `gbufferModelView` at `render()` HEAD; camera dest-swapped), and a
   skeptic even called the recon's cameraPosition detail "wrong" — so **cameraPosition and/or the CELESTIAL
   terms (sunPosition/shadowLightPosition/upPosition — the direction-dependent ones) and shadowModelView/
   Projection may NOT be dest at the re-upload**. These read main-tracking state (CameraPositionTracker /
   CelestialUniforms reading level+camera / ShadowRenderer), which the compat path does NOT re-point. → the fix
   likely needs recon "Option B": push dest camera pos + dest celestial + dest shadow matrices into the iris
   sources BEFORE the dest render, in addition to the counter bump.
2. **The bump doesn't reach the dest terrain programs** (timing / program-instance mismatch — e.g. the sodium
   terrain program binds/updates at a moment where lastFrame is already re-synced).
3. **Uniform staleness is not the (dominant) cause** — the runner-up from the earlier lighting research was
   **per-vertex lmcoord** (stale sky=15 baked into fresh dest sections) or an ambient/AO term. If the probe (§5)
   shows the uniforms ARE dest but fullbright persists, pivot to lmcoord.

---

## §5 THE NEXT MOVE — RUN THE PROBE (diagnose-first; the recon designed it)
Do NOT iterate the fix blind. Build the lever-gated **live uniform-readback probe** and run it on the exact
same-dim OW-dest view, once, 1Hz, log-only:
1. During a dest terrain draw (hook near `MixinGlCommandEncoder.iris$setupState` after `program.iris$setupState`,
   gated to the dest-extract flag), reflect the bound `gbuffers_terrain` `ExtendedShader`'s private `uniforms`
   (`ProgramUniforms`) field → read `lastFrame`; read `SystemTimeUniforms.COUNTER.getAsInt()`. Log both.
2. `glGetUniformLocation` + `glGetUniformfv` the LIVE bound-program `cameraPosition`, one column of
   `gbufferModelView`, and `shadowLightPosition` (or `sunPosition`). Log as **dest-expected vs main-expected**
   (capture the saved main camera pos pre-swap for the contrast).

**Reading (settles §4):**
- Bump ON: `lastFrame != COUNTER` during the dest draw ⇒ the bump DID cause a re-upload. Then check the values:
  live cameraPosition/celestial ≈ **MAIN** ⇒ **hypothesis 1 confirmed → add the re-source (Option B)**. Values ≈
  DEST but still fullbright ⇒ **hypothesis 3 → pivot to lmcoord** (adapt `LightSectionDump` to the dest ClientLevel).
- Bump ON but `lastFrame == COUNTER` during the dest draw ⇒ the bump did NOT re-upload ⇒ **hypothesis 2 → fix the
  bump reach/timing** (e.g. bump inside `renderDestWorldFullPipeline` right before `destRenderer.render:1747`, or
  reflect each reused `ExtendedShader.uniforms.lastFrame = -1`).

---

## §6 KEY FILES
- `common/.../compat/iris_compatibility/IrisInterface.java` — the `bumpPerFrameUniformCounter()` facade (added).
- `common/.../compat/iris_compatibility/IrisCompatOn262Renderer.java` — `invokeWorldRendering` (the bump bracket,
  ~line 332) and `onBeforeHandRendering` (the whole compat dest pipeline: snapshot → renderPortals → blit-back).
- `common/.../render/SecondaryWorldRenderCore.java` — `renderDestWorldFullPipeline` (`destRenderer.render:1747`;
  dest camera/state setup :1393+); `MyGameRenderer.renderWorldFullPipeline` / `switchAndRenderTheWorldFullPipeline`
  (dest camera swap ~:608, CapturedRenderingState).
- `common/.../render/ShadowEmptinessProbe.java` — the reflection-into-iris + probe precedent to clone for §5.
- `common/.../IPGlobal.java` — `isIrisPerFrameRefreshActive()` (the fullbright lever) + the flash/phantom levers.
- IP: `ImmersivePortalsMod/.../compat/iris/ExperimentalIrisPortalRenderer.java:169/171` — the counter-bump precedent.

## §7 HOUSEKEEPING
- The fullbright fix is **DEFAULT-ON but ineffective** and UNCOMMITTED. Decide after the probe: extend (re-source)
  / revert / keep. Do NOT commit it or build a "combined jar" until it actually fixes the fullbright.
- The phantom fix's **~6 sparse `Invalid format` GL log-lines** are a separate known-minor (NOT the guard's
  copies — all proven 0x0). Attribute via a guard-off A/B (`-PdisableIrisTemporalGuard=true`) and clean separately.
- A stale combined jar `seamlessportals-fabric-1.0.0-flash-depth.jar` sits in the top-level `fabric/build/libs`
  (predates the phantom + fullbright work). Rebuild once the fullbright lands. Main branch `claude/nifty-kepler`
  has flash + depth only (fast-forwarded to `e8e4767`); the phantom (`e408a0d`) is on `iris-on/is5-shadow`, not
  yet merged to main.
