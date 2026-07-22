# IN-PORTAL FULLBRIGHT — DEBUG HANDOFF (2026-07-22, rev 2 — PROBE NOW FULLY DESIGNED)

Continue debugging the **shaders-ON in-portal fullbright** here. Read `§0 STANDING RULES` first, then
`§5 THE PROBE` (it is a PROBE, not a fix — diagnose-first). The 2026-07-22 recon (rev 2) javap-confirmed
every iris symbol and turned §5 into a build-ready design; the symbol table is `§8`. **Next session = BUILD
the §5 probe, run it (A/B), read the full log, then design the fix. No code was written this recon; the
working tree is unchanged (uncommitted counter-bump + dead shadow-sync WIP still present — isolate on commit).**

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

## §5 THE PROBE — FULLY DESIGNED (2026-07-22 rev-2 recon), BUILD THIS FIRST
Diagnose-first: build this lever-gated, log-only, reflection-only probe and run it BEFORE touching the fix.
The recon javap-confirmed every symbol (§8) and settled the design below. The OLD sketch (read `lastFrame`
"after `program.iris$setupState`") was WRONG — see the ⚠ corrections at the end.

**Shape — clone the two proven precedents:**
- A reflection-only `IrisFullbrightProbe` in `com.warwa.seamlessportals.render` (discipline = `ShadowEmptinessProbe`:
  NO iris `@Mixin`, javap-confirmed reflective symbols, self-disarming on any throw, 1Hz-latched, render-thread only).
- A thin per-draw hook mixin cloned from `MixinSodiumProbe_GlCommandEncoder`:
  `@Mixin(targets="com/mojang/blaze3d/opengl/GlCommandEncoder")`, `@Inject` into
  `trySetup(GlRenderPass, Collection)Z` at **RETURN**, `require = 0`, that forwards `(cir.getReturnValue())` to the probe.

**Lever:** `-Dseamlessportals.fullbrightProbe=true` (DEFAULT-OFF). Add the `-PfullbrightProbe` passthrough to BOTH
the `runClientSodium` and gametest blocks in `fabric/build.gradle` (idiom:
`if (project.findProperty('fullbrightProbe') == 'true') { vmArg('-Dseamlessportals.fullbrightProbe=true') }`).

**Pass discriminator (bytecode-confirmed):** `PortalRendering.isRendering()` — FALSE = the MAIN pass, TRUE = inside
the compat nested dest render (`renderWorldFullPipeline`, via `pushPortalLayer` in `doRenderPortal`). ⚠ NOT
`isDestExtracting` (that brackets only the vanilla extract sub-phase, NOT the compat full-pipeline terrain draws).

**The key recon insight — no pre-update `lastFrame` read needed:** same-dim reuses the SAME `ProgramUniforms`
instance across the main + dest passes, and `update()` re-uploads perFrame IFF `COUNTER` advanced since that
instance last updated. So "did the dest draw re-upload?" ⇔ **`COUNTER(dest-pass) != COUNTER(main-pass)` in the SAME
frame**. No need to read `lastFrame` (post-`update()` it is ALWAYS `==COUNTER` → useless). Both COUNTERs AND both
uniform value-sets are readable at the `trySetup` RETURN seam (iris injects its setup at trySetup HEAD, so by
RETURN `update()` has already run and the GL program carries the post-update uniforms).

**Per-frame state machine** (in `IrisFullbrightProbe`, driven from the trySetup-RETURN hook; entry gated on:
enabled + successful setup + an iris program bound = `GL_CURRENT_PROGRAM > 0` AND
`glGetUniformLocation(prog,"cameraPosition") >= 0`):
- `armed` ⇔ `now - lastEmit >= 1s`.
- MAIN draw (`!isRendering()`), armed, once per frame (refresh only when `COUNTER` changed): capture
  `mainSlot = { cMain = COUNTER.getAsInt(), programId, vals = readUniforms(programId) }`.
- DEST draw (`isRendering()`), `mainSlot` present: capture `destSlot = { cDest, programId, vals }` PLUS the reflected
  SOURCE — `CapturedRenderingState.getGbufferModelView()` / `getGbufferProjection()` (what iris WOULD upload) — PLUS
  the live dest camera pos. EMIT one comparison block; reset `mainSlot`; `lastEmit = now`.
- `readUniforms(programId)`: `glGetUniformfv` into a 16-float zero-init buffer (program-explicit, no bind needed) for
  each PRESENT uniform of: `cameraPosition`, `gbufferModelView`, `gbufferModelViewInverse`, `shadowLightPosition`,
  `sunPosition`, `upPosition`, `shadowModelView`. **Strong discriminators = `gbufferModelView` (the view matrix —
  dest≠main clearly) + the celestial dirs (`shadowLightPosition`/`sunPosition`/`upPosition`, the direction-dependent
  terms).** `cameraPosition` is WEAK (iris may use camera-relative fract coords ≈ equal both passes) — read it, but
  never decide on it alone.
- Log, per pass, the bound `programId` (+ a label if cheap) so a run SELF-VERIFIES the seam catches sodium TERRAIN
  draws (see the RISK note).

**The 3-way decision (settles §4 in ONE run):**

| dest-vs-main GL values | `C_dest` vs `C_main` | VERDICT |
|---|---|---|
| DIFFER (dest) yet STILL fullbright | (either) | **H3 → per-vertex lmcoord** — uniforms are fine; adapt `LightSectionDump` to the dest `ClientLevel` |
| EQUAL (main) | `!=` (re-upload happened) | **H1 → re-source (Option B):** push dest cameraPosition + celestial sun/shadowLight/up + shadow matrices before the dest render. Cross-check the reflected SOURCE: source `main` ⇒ H1 firm; source `dest` yet GL `main` ⇒ the perFrame upload isn't propagating that source (plumbing, closer to H2) |
| EQUAL (main) | `==` (no re-upload) | **H2 → bump didn't reach:** fix reach/timing — bump inside `renderDestWorldFullPipeline` right before `destRenderer.render:1747`, or reset each reused `ExtendedShader.uniforms.lastFrame = -1` |

**Run matrix (A/B):** (i) bump ON (default): `.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PfullbrightProbe=true`;
(ii) bump OFF: add `-PdisableIrisPerFrameRefresh=true`. Bump-OFF is the baseline — expect `C_dest==C_main` +
EQUAL(main) values. Bump-ON flipping `C_dest` to `!= C_main` proves the bump reaches; the values then split H1 vs H3.

**⚠ Corrections to the pre-recon sketch (do NOT repeat):** (1) reading `lastFrame` after `iris$setupState` is
useless (always `==COUNTER` post-update) — use the cross-pass COUNTER compare. (2) gate on
`PortalRendering.isRendering()`, NOT `isDestExtracting`. (3) the seam is Mojang `GlCommandEncoder.trySetup` RETURN
(the P7b idiom), NOT an iris `@Mixin`.

**RISK (the probe self-diagnoses it):** if sodium terrain draws do NOT route through Mojang
`GlCommandEncoder.trySetup` in this sodium/iris build, the dest-pass hook catches only entity/particle draws, not
terrain. §8 bytecode says they DO (iris's `MixinGlCommandEncoder` injects `trySetup` HEAD and drives the sodium
terrain `ExtendedShader`). The per-pass `programId` logging confirms it live — "no terrain-like program in the dest
pass" is itself the finding, and the pivot is to hook sodium's terrain draw path directly.

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

## §8 JAVAP-CONFIRMED SYMBOL TABLE + STRUCTURE FACTS (2026-07-22 rev-2 recon)
Every symbol below was `javap`-verified against
`iris-1.11.2+26.2-fabric.jar` (`…/maven.modrinth/iris/1.11.2+26.2-fabric/f7d526b…/`) THIS recon — no guessing.

**iris internals (reflect these):**
- `net.irisshaders.iris.gl.program.ProgramUniforms`: `int lastFrame;` (PACKAGE-private, NOT `private`).
  `public void update()` — bci 94+: `frameNow = SystemTimeUniforms$FrameCounter.getAsInt()` (bci 97), compare
  `lastFrame` (bci 102), if advanced `updateStage(perFrame)` + `lastFrame = frameNow` (bci 111). Also
  `private static ProgramUniforms active;` (set to `this` at bci 12, BEFORE the gate).
- `net.irisshaders.iris.uniforms.SystemTimeUniforms`: `public static final SystemTimeUniforms$FrameCounter COUNTER;`.
  `FrameCounter`: `public int getAsInt()`, `public void beginFrame()` (advances `private int count` `(count+1)%720720`),
  `public void reset()`. — the bump = `SystemTimeUniforms.COUNTER.beginFrame()`.
- `net.irisshaders.iris.pipeline.programs.ExtendedShader extends com.mojang.blaze3d.opengl.GlProgram implements IrisProgram`:
  `private final ProgramUniforms uniforms;`, `private static ExtendedShader lastApplied;`,
  `public void iris$setupState(HashMap, GpuTextureView)`, `public boolean iris$isSetUp()`,
  `public Map<String,com.mojang.blaze3d.opengl.Uniform> getUniforms()`. Inside `iris$setupState`: sets
  modelViewInverse/normalMat/projectionInverse DIRECTLY (ungated, bci 19-153 → why dest GEOMETRY is correctly framed),
  then bci 261 `ProgramSamplers.update()`, **bci 268 `this.uniforms.update()` = THE GATE**, bci 276
  `customUniforms.push()`, bci 283 `ProgramImages.update()`.
- `net.irisshaders.iris.mixin.MixinGlCommandEncoder`: injects `private void iris$setupState(GlRenderPass, Collection,
  CallbackInfoReturnable)` into `com.mojang.blaze3d.opengl.GlCommandEncoder.trySetup(GlRenderPass, Collection)Z` at
  **`@At("HEAD")`** (verified); calls `IrisProgram.iris$setupState(HashMap, GpuTextureView)` at bci 127 ⇒ sodium
  terrain draws hit the gate through this seam.
- `net.irisshaders.iris.uniforms.CapturedRenderingState`: `public static final … INSTANCE;`
  `public org.joml.Matrix4fc getGbufferModelView()`, `public org.joml.Matrix4fc getGbufferProjection()` — the reflected
  SOURCE reads (precedent: `ShadowEmptinessProbe` already resolves these).
- Stale PER_FRAME set (skipped for dest draws): `CameraUniforms` (cameraPosition…), `MatrixUniforms`
  (gbufferModelView/Proj…), shadow matrices, `CelestialUniforms` (sunPosition/shadowLightPosition/upPosition…).

**mod-side structure:**
- `qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()` = cached static (`getPortalLayer()!=0`)
  — the pass discriminator (cheap boolean).
- `IrisCompatOn262Renderer.invokeWorldRendering` lines **338/343** bracket `IrisInterface.invoker.bumpPerFrameUniformCounter()`
  around `MyGameRenderer.renderWorldFullPipeline` (the dest render runs there with `isRendering()==TRUE`).
- per-draw hook precedent: `MixinSodiumProbe_GlCommandEncoder` (trySetup RETURN, `GL20.glGetInteger(GL_CURRENT_PROGRAM)`,
  `GlStateManager._glGetUniformLocation`, 1Hz-throttled — package `qouteall.imm_ptl.core.compat.mixin.sodium`, registered
  in `seamlessportals-ip-compat.mixins.json`).
- reflection-probe precedent: `ShadowEmptinessProbe` (self-disarming; 1Hz latch; javadoc'd binding discipline).
- `fabric/build.gradle` `-P`→`-D` passthrough idiom lives in the `runClientSodium` block (~L146-187) AND the gametest block
  (~L236-270); the existing `-PdisableIrisPerFrameRefresh` A/B lever is at L187/L270.

## §9 FRESH-SESSION STARTER PROMPT
The prompt to open the next session is saved alongside this doc as `migration/FULLBRIGHT_NEXT_PROMPT.md` (paste it verbatim).
