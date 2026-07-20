# IRIS SHADERS-ON HANDOFF — portal views WITH an active shaderpack

**The mission (USER-ROUTED 2026-07-20, C2-5 decision (a)): build the shaders-ON portal
renderer during the S20/polish era.** C2 closed with the honest pass-through (shaders ON →
`rendererDummy` + a one-shot notice, deviation D8); this engagement replaces that with real
portal views under shaders. Read this doc, then `migration/S19_HANDOFF.md` §0 (ALL standing
rules — unchanged and binding: NO GUESSING/instrument-first, Opus mechanical / Fable
verify+design, Fable-verify every increment, worktree isolation for code (the C2 pattern:
`git worktree add .claude/worktrees/<name> -b <branch> HEAD` + copy the untracked root build
files gradlew/gradlew.bat/settings.gradle/build.gradle/gradle/), commit+push each increment,
suite green before READYs, the IP side-by-side court, live rounds = the proof).

## 1. WHERE THIS STARTS FROM (the C2 end-state, all live-proven)

- **Sodium tier COMPLETE**: views, clipping (our view-space `seamlessportals_ClipPlane`
  through the sodium GLSL source patch + the GLDrawContext uploader), entities, culling
  (the viewport-carried snapshot), the endFrame walk, default-ON.
- **Iris tier (C2-4)**: OnIrisPresent live (the D7 loud resolve assert); shaders-OFF = FULL
  parity (user-proven incl. clipping — P11 settled: no-pack iris never routes patchSodium);
  shaders-ON = D8 dummy routing + notice; the B4 pipeline null/restore bracket at
  MyGameRenderer :295/:347/:454 live and no-pack-inert; `reloadPipelines` DEFERRED to the
  next frame's `PRE_GAME_RENDER_TASK_LIST` (the D8 timing sub-fix — IP's
  destroy-before-prepare ordering; keep this discipline in anything you build);
  CrossPortalEntityRenderer disabled under iris (IP-FAITHFUL — do not "fix").
- **Ground-truth docs (all committed)**: `migration/C2_IP_COMPAT_DEPTH.md` (IP's iris
  renderer trio + 7 mixins at injection level — TRACER B/C sections), `migration/C2_091_MAP.md`
  (TRACER 3: every iris 1.11.2 bind verdict), `migration/C2_DESIGN.md` (§2 dispositions
  #10-#17/#23-#27, §0.3 conflict 6, D8/D9/D12 in §5), port-note
  `migration/port-notes/C2-sodium-iris.md` (§3.12/§3.13 + the ledger).

## 2. THE DESIGNATED SHAPE (pre-decided by the C2 design panel — start here, not from zero)

**The `IrisCompatibilityPortalRenderer` SHAPE re-expressed onto `renderDestWorld`** (design
§0.3 conflict 6; tracer C's verdict: LOWEST coupling — pure GL `glCopyImageSubData` +
IP-core shaders, ZERO iris internals, ONE recursion layer). Mechanism: render the dest world
through the ACTIVE iris pipeline into an offscreen FBO once per portal per frame, then paste
the texture onto the portal quad. This is the classic "compatibility render mode"
architecture — and THE BLOCK-ERA CODE IN THIS REPO IS A WORKING PRECEDENT of exactly it
(PortalContextSwitch + the mirror-FBO path). **S20 DELETES that code — mine it BEFORE the
deletion or from git history (any commit ≤ the S20 sweep).**

The deep-end alternative (the Experimental/stencil trio + its 7-mixin substrate) is
DEFER-DORMANT with held sources in-tree; its re-entry facts are pre-mapped (below) but the
panel's verdict was: compatibility shape first.

## 3. PRE-MAPPED FACTS THE DESIGN MUST HONOR (all javap/ledger-proven during C2)

- **The S18 substrate constraint**: dest passes run NO framegraph / no recursive
  renderLevel — IP's iris renderers assume IP's recursive model → RE-EXPRESSION, not port.
- **The re-anchor for IP's translucent-phase hook** (if the deep end is ever taken):
  `FeatureRenderDispatcher$PreparedFrame.executeTranslucent()V` INVOKE / the
  `iris_pre_translucent` FramePass, mixin priority < iris's (D12 ledger).
- **`MixinShaderManager_Overrides`** (iris, shaders-ON): substitutes iris GlPrograms for
  vanilla RenderPipelines — gated on `getPipelineNullable() instanceof IrisRenderingPipeline`.
  Shaders-ON rendering through vanilla-looking pipelines will actually run IRIS programs —
  account for it (C2-4 lens-B sweep; port-note §3.12).
- **The clip transport under shaders**: iris routes sodium terrain GLSL through
  `TransformPatcher.patchSodium` when a pack is active → our C2-2 source patch does NOT
  reach those programs. The pre-mapped route: the TransformPatcher retarget (3-arg
  `transformInternal` survives; `Parameters` moved to
  `net.irisshaders.iris.pipeline.transform.parameter.Parameters`) injecting OUR view-space
  uniform into iris-patched vertex sources (design §3.3.6) + a binder redesign — iris
  deleted `SodiumShader` (091 map: uniforms are now DynamicUniformStorage/GpuBufferSlice
  UBOs; candidate (a) iris CustomUniforms, candidate (b) rely on gl_ClipDistance if the
  patched pipeline keeps fixed-function clipping). The C2-2 sodium patch's idempotence guard
  (`contains(seamlessportals_ClipPlane)`) is the double-patch defense.
- **Per-dimension pipelines**: `PipelineManager.pipelinesPerDimension` — iris keys pipelines
  by dimension; a cross-dim dest render under shaders must use/prepare the DEST dim's
  pipeline (or accept the source pipeline for the compatibility shape — a design decision).
- **Iris FILES 1/2/3 binds confirmed-alive on 1.11.2** (IrisRenderingPipeline's
  finalizeLevelRendering/beginTranslucents+CompositeRenderer.renderAll/isBeforeTranslucent;
  ClearPass.execute; FinalPassRenderer) = cheap revival if the deep end needs them.
  `ShadowRenderer.ACTIVE` gates ride the live invoker already.
- **`versionCounterForSodiumShaderReload`** increments on destroyPipeline — sodium terrain
  shaders rebuild on pipeline changes; the C2-2 patch re-applies via the source seam on
  rebuild (shaders-OFF proven; shaders-ON goes through patchSodium instead — see above).
- **The UBM/endFrame discipline**: any new dest-pass draw producer must respect the C2-1d
  endFrame walk + the C2-1c latch reset (`ip_onDestTerrainDrawsFinished`) — read port-note
  §3.4/§2.5 before adding draws.

## 4. SUGGESTED PROCESS (the C2 pattern, proven 5 stages straight)

1. **Design round**: a Fable judge panel (2-3 designers + xhigh synthesis) over THIS doc +
   the three C2 ground-truth docs + the block-era FBO precedent → a staged plan with
   probes/deviations (the `c2-design-panel` workflow script under the session's
   workflows/scripts dir is the template shape).
2. Stage the delivery: (a) the FBO compatibility renderer shaders-ON (one layer, source-dim
   pipeline) → live round; (b) the clip transport under shaders (TransformPatcher +
   binder) → live round; (c) optional depth: per-dim pipelines / multi-layer / the
   stencil deep end — each its own decision.
3. Worktree per stage; Fable lenses per increment; the user's shaderpack rounds are the
   proof (Complementary Reimagined/Unbound = the reference pack).
4. Honest gating throughout: shaders-ON keeps the D8 pass-through until each stage's
   deliverable is live-proven; the notice wording tracks reality.

## 5. OPENING PROMPT (paste into a fresh session)

> Read `migration/IRIS_SHADERS_ON_HANDOFF.md` in full (it chains to
> `migration/S19_HANDOFF.md` §0 for ALL standing rules — follow every one), then execute the
> iris shaders-ON engagement per its §4 process: FIRST the block-era FBO-precedent mining
> (PortalContextSwitch + the mirror-FBO path — from the working tree if S20 hasn't deleted
> it yet, else from git history), THEN the Fable design panel over the handoff + the three
> C2 ground-truth docs, bring me the stage ladder + open questions before implementing, then
> proceed stage by stage: worktree isolation, Fable-verify every increment, commit+push
> each, suite green + my shaderpack live round before each stage closes. The deliverable:
> portal views rendering WITH an active shaderpack (the compatibility FBO shape first — one
> layer is acceptable), with the honest D8 pass-through retained until each piece is
> live-proven. Do not touch the sodium tier's landed machinery except through the ledgered
> seams (port-note C2-sodium-iris.md §2.5/§3.4).
