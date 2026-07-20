# PORT-NOTE IS — IRIS SHADERS-ON (the compatibility renderer engagement)

**Engagement**: replace deviation D8 (shaders-ON -> rendererDummy + notice) with the IP-faithful
compatibility renderer (post-main anchor; snapshot -> full-pipeline dest render INTO MAIN ->
depth-tested portal-area stamp -> blit-back; stencil-free; one layer).
**Governing design**: `migration/IRIS_SHADERS_ON_DESIGN.md` (the judge synthesis; §0.3 verdict =
(beta); §0.4 fork record; §1 stage ladder IS0-IS4; §2 mechanism specs).
**HEAD at census**: d6ed406. Jars pinned: iris-1.11.2+26.2-fabric, sodium-mc26.2-0.9.1-fabric.
Vanilla reference: mc262-ref (Mojang mappings; 26.2 ships unobfuscated).
**This file is the engagement's ledger of record.** §1 = the IS0 static census (five facets,
Fable-reconciled; every load-bearing verdict independently re-run at the reconciliation pass).

---

## §1 THE IS0 STATIC CENSUS

Five facets (P-A1 / P-B1+P-DIM / P-PASTE+OQ6 / P-CLIP-UP+GLSL / REPO-AUDITS), each verdict
cited to javap bytecode, extracted jar resources, mc262-ref file:line, or repo file:line.
The reconciliation pass INDEPENDENTLY re-ran the decisive extractions for the four
load-bearing verdicts (P-A1 anchor, P-B1 getfield-vs-manager, P-PASTE keying, the patchSodium
symbol map): **all four confirmed; zero factual corrections to the facets were needed.**
Two NEW census facts surfaced at the reconciliation pass (§1-F below).

### §1-A P-A1 — the post-main anchor (VERDICT: anchor RE-SITED into GameRenderer.renderLevel)

Vanilla 26.2 structure (mc262-ref GameRenderer.java, re-verified at reconciliation):
- `render(DeltaTracker,boolean)` :396 — the only world entry is `this.renderLevel(deltaTracker)`
  at :425; NO hand call in render() (body: renderLevel, tryTakeScreenshot :426,
  doEntityOutline :427, postChain :428-433, fogRenderer.endFrame :438, clearDepthTexture :439,
  gui :443, endFrames :447-448).
- `renderLevel(DeltaTracker)` :525-590 — the 8-arg `levelRenderer.render(...)` at :563-565;
  `popPush("hand")` :566; HUD-3D projection switch :568-570; **main depth-texture CLEAR :571**;
  `renderItemInHand` :572; featureRenderDispatcher.renderAllFeatures :582; TAIL :590.

Iris injections (javap -v, re-run at reconciliation):
- MixinGameRenderer (@Mixin GameRenderer): iris$startFrame render/HEAD; iris$logSystem
  <init>/TAIL; iris$modifyBlur render/@ModifyArgs GlobalSettingsUniform.update;
  iris$disableVanillaHandRendering @Redirect renderItemInHand @ INVOKE
  ItemInHandRenderer.submitHandsWithItems (early-return under a pack);
  iris$runColorSpace renderLevel/TAIL. **No iris inject between the 8-arg render return
  and renderItemInHand.**
- MixinLevelRenderer (@Mixin LevelRenderer, class weave -> fires on secondaries):
  iris$setupPipeline render/HEAD; iris$beginLevelRender render/INVOKE FramePass.executes AFTER;
  **iris$endLevelRender render/INVOKE Matrix4fStack.popMatrix** (= LevelRenderer.render:252,
  the 8-arg render's own tail) — bytecode: HandRenderer.renderTranslucent (bci 0-38) ->
  "iris_final" -> finalizeLevelRendering (bci 73-77) -> **aconst_null putfield pipeline
  (bci 82-84)**; plus iris$renderTerrainShadows(2) @ addMainPass INVOKE and the framegraph
  lambda hooks (sky/clouds/weather/terrain-layer/translucents/blockOutline/debug phases).
- MixinLevelRenderer_SkipRendering targets LevelExtractor.extractVisibleEntities despite
  its name (not LevelRenderer).

**Anchor verdict** (see §1.2 for the concrete spec):
- Candidate 1 LITERAL (design §1-IS0 default: GameRenderer.render @ renderLevel INVOKE AFTER,
  = :426) EXISTS but is the WORST slot on 26.2: post-hand + post-depth-clear + post-colorspace.
  It is the DS6 fallback, NOT IP's handler-③ intent. **The design default is CORRECTED.**
- Candidate 2 (renderItemInHand HEAD) EXISTS (iris does not touch its HEAD) but lands after
  the :571 depth clear (OQ4 statically NEGATIVE there) and after the :570 projection switch
  (wrong projection for the world-space stamp). Disqualified.
- **CHOSEN: GameRenderer.renderLevel @ At INVOKE LevelRenderer.render(8-arg) shift=AFTER**
  (lands :566): after iris finalize (endLevelRender ran INSIDE the 8-arg render), scene depth
  VALID (before :571 — OQ4 statically POSITIVE here), WORLD projection active (before :570),
  before vanilla hand :572, before colorspace :590. No competing iris inject in the window.

Under-a-pack caveat (ledgered, not anchor-changing): iris renders the hand ITSELF pre-finalize
(solid via iris$beginTranslucents -> HandRenderer.renderSolid; translucent in endLevelRender)
and no-ops vanilla renderItemInHand — so under a pack the hand is baked into the composite at
EVERY reachable post-finalize slot; "portal behind hand" is unachievable by anchor choice under
shaders (DS6-style overlap where a portal covers the hand region is inherent, rare). On
shaders-OFF proof rows the :566 anchor is properly before the real vanilla hand.

### §1-B P-B1 + P-DIM — pipeline resolution + per-dim (VERDICT: null-bracket STANDS; let-iris-select VIABLE)

- The instance field `pipeline` exists ONLY on MixinLevelRenderer and is a per-render scratch
  cache: **iris$setupPipeline at render() HEAD writes `this.pipeline =
  Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimension())` (bci 135-145,
  re-verified) and iris$endLevelRender NULLS it at render tail (bci 82-84).** Every
  getfield-pipeline read is a MixinLevelRenderer render hook downstream of the HEAD write.
  Every other render-path consumer (MixinGameRenderer, MixinShaderManager_Overrides,
  skipRenderChunks, sky hooks) resolves via the GLOBAL manager
  (getPipeline()/getPipelineNullable()). **No authoritative getfield read exists -> the B4
  null bracket (MyGameRenderer.java:304/:356/:463, re-verified in-repo) stands; the design
  §0.4-5 install contingency is DEAD** (the field the mod nulls is one iris itself nulls
  every frame and overwrites at every render HEAD).
- Dimension key: `Iris.getCurrentDimension()` reads `mc.level.dimension()` LIVE at render()
  HEAD. The bracket sets `client.level = destWorld` by DIRECT field write (MyGameRenderer:308,
  NOT Minecraft.setLevel — iris's setLevel-keyed destroy+prepare in
  MixinMinecraft_PipelineManagement does NOT fire during the bracket). So the nested render
  resolves the DEST pipeline (design §0.4-6/D18 confirmed viable).
- `preparePipeline(NamespacedId)` absent-dim path: SYNCHRONOUS render-thread create at render()
  HEAD — SystemTimeUniforms COUNTER+TIMER reset; pipelineFactory.apply (shader compile + GL
  alloc = the one-time HITCH); map.put; if isReloadRequired -> `mc.levelExtractor.allChanged()`.
  **ADDITIVE — no destroy(), no map.clear(), no versionCounterForSodiumShaderReload++ —
  NOT the C2-4 destroy-mid-frame hazard family.** Pre-warm = hitch-mitigation only (OQ7 call).
- Named corners (new to the ledger): (i) the SystemTimeUniforms reset IS the concrete D21
  temporal-flicker mechanism on first view into an uncreated dim; (ii) on a reload-pending
  frame a portal-triggered create allChanged()s the MAIN extractor (bracket doesn't swap
  mc.levelExtractor) — rare corner, post-pack-reload frames only; (iii) after the nested
  render the manager's single `pipeline` slot points at the DEST pipeline until the next
  main-render HEAD — GUI/hand shader substitution in that window transiently sees the dest
  pipeline, self-corrects next frame (live observable, not a static blocker).
- LIVE-RESIDUAL: iris tolerating TWO preparePipeline calls / two per-pipeline lifecycles
  (double finalize, isBeforeTranslucent, shadow/temporal) in ONE frame = exactly the P-B2
  probe. Do not force statically.

### §1-C P-PASTE + OQ6 — the substitution keying (VERDICT: paste pipelines IMMUNE, three grounds)

- **Name-vs-target correction**: `MixinShaderManager_Overrides` targets
  `com.mojang.blaze3d.opengl.GlDevice.getOrCompilePipeline(RenderPipeline)` @At HEAD
  cancellable (re-verified: @Mixin value=[class GlDevice], method=["getOrCompilePipeline"]).
  There is no ShaderManager-targeting substitution mixin — search on GlDevice.
- Gate chain (bytecode re-verified): skip COMPOSITE_PIPELINE + ANIMATE_SPRITE_* (identity) ->
  **`Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline`
  (GLOBAL manager — corroborates the gamma rejection)** ->
  `shouldOverrideShaders()` = `isRenderingWorld && isMainBound` (a THIRD immunity layer: our
  post-main paste draws run isRenderingWorld=false) -> `!ImmediateState.bypass` ->
  `override()` -> `IrisPipelines.getPipeline(...)` -> ShaderMap. On a null override:
  one-time log only (minecraft-namespace miss = fatal-log; modded miss = soft error), never
  a crash — iris tolerates modded pipelines by design.
- **Keying** (re-verified): `IrisPipelines.getPipeline` first routes any pipeline whose
  location namespace `contains("sodium")` (substring!) to SODIUM_TERRAIN_*/SHADOW_* keys;
  else `coreShaderMap(Shadow).getOrDefault(rp, FAKE_FUNCTION)` where the maps are
  `Object2ObjectArrayMap<RenderPipeline, Function>` populated with ~59 vanilla
  RenderPipelines.* singletons and FAKE_FUNCTION returns null. mc262-ref RenderPipeline is a
  class with NO equals/hashCode override -> **object-IDENTITY keying**. No blit/screenquad/
  composite/GUI pipeline is in the key set.
- **Immunity of `seamlessportals:core/...` paste pipelines: CONFIRMED on three independent
  grounds** — (1) fresh RenderPipeline instance never registered -> identity miss -> null;
  (2) namespace lacks "sodium" -> skips the terrain branch; (3) post-main draws fail
  shouldOverrideShaders. Nuance: immunity is via pipeline-OBJECT identity + the sodium
  substring, NOT shader-source ids — mod-namespace shader ASSETS are belt-and-suspenders.
  **RULE: never put the substring "sodium" anywhere in our pipeline location namespace.**
- **OQ6 SETTLED**: iris fabric.mod.json depends = {fabricloader>=0.12.3, sodium:["0.9.x"]} —
  sodium is a HARD dep; the iris-without-sodium matrix row is UNREACHABLE (pre-decision moot).
  No `minecraft` key at all (range delegated via sodium). `breaks` includes
  immersive_portals<=1.4.2 — we dodge via mod-id `seamlessportals`; S20 flag: never adopt or
  `provides` the immersive_portals id.
- Mixin-plugin veto: IrisMixinPlugin only toggles iris's OWN VKOnly-vs-GL mixins
  (getMixins()=empty) — zero authority over our configs. Under a Vulkan backend iris
  self-disables its GL mixins (doesn't touch our substrate).

### §1-D P-CLIP-UP (static half) + the patchSodium GLSL map

- **P-CLIP-UP static: YES** — iris does NOT replace sodium's draw loop. Its whole sodium
  chunk integration = three thin weaves (MixinDefaultChunkRenderer sampler/cull tweaks;
  MixinShaderChunkRenderer vertexFormat swap in createShader; the program substitution via
  ShaderCreator/patchSodium + the GlDevice override). The draw still flows
  DefaultChunkRenderer.render -> GLDrawContext.setContext (C2-2 uploader #2 fires,
  MixinSodiumGLDrawContext_ClipUpload @ setContext RETURN) -> GLDrawBatch.draw ->
  RenderPass.multiDrawIndexed -> GlCommandEncoder.executeDraws -> trySetup (C2-2 uploader #1
  fires, GlCommandEncoderClipMixin @ trySetup RETURN). Both uploaders read GL_CURRENT_PROGRAM
  and cache loc per program id -> they cover iris programs the moment those programs declare
  the uniform (= the IS3 injection). **Ladder rung (c) holds at the transport level — zero
  new binder code**; runtime confirm rides IS3.
- **patchSodium map** (re-verified at reconciliation): `transformInternal(String,
  Map<PatchShaderType,String>, Parameters)` 3-arg CONFIRMED; returns the fully-transformed
  sources at RETURN; `Parameters` FQN = `net.irisshaders.iris.pipeline.transform.parameter
  .Parameters` (public final Patch patch; public String name) — the IS3 descriptor names this
  package. transform() caches by CacheKey and calls transformInternal only on miss; fires for
  ALL patch types -> the IS3 mixin gates `params.patch == Patch.SODIUM` + the VERTEX map key;
  a pipeline reload that clears the cache re-runs on the ORIGINAL source (guard still applies).
- **Symbol corrections (re-verified in SodiumTransformer bytecode)**: the transformed sodium
  VERTEX source unconditionally gains the `u_Globals` std140 UBO (`u_ProjectionMatrix`,
  **`u_ModelViewMatrix`** — gl_ModelViewMatrix is renamed TO it) and, in the VERTEX branch,
  `uniform vec3 u_RegionOffset;` + `vec4 getVertexPosition() { return vec4(_vert_position +
  u_RegionOffset + _get_draw_translation(_draw_id), 1.0); }` with gl_Vertex ->
  `getVertexPosition()`. **A plain `iris_ModelViewMatrix` does NOT exist on the sodium path**
  (only iris_ModelViewMatrixInverse) — the IP-1.8 yaml name is WRONG; the design's mistrust
  vindicated. `main()` is PRESERVED (only VanillaTransformer wraps main; sodium chain never
  routes there; CompatibilityTransformer explicitly skips main).
- **IS3 clip expression** = `gl_ClipDistance[0] = dot((u_ModelViewMatrix *
  getVertexPosition()).xyz, seamlessportals_ClipPlane.xyz) + seamlessportals_ClipPlane.w;`
  spliced at transformInternal RETURN (post-transform = break-proof; renames already ran;
  gl_ClipDistance is a built-in). NOTE: the C2-2 SodiumClipShaderPatch keys on the local
  `vec3 position` — NOT guaranteed in transformed pack source; **IS3 must switch to
  getVertexPosition() (a real code change, not a copy).** Pre-registered live-compile
  discriminator: a pack redeclaring `out gl_PerVertex {...}` needs gl_ClipDistance inside
  that block.
- **Idempotence guard CONFIRMED** both directions: SodiumClipShaderPatch.java:156-159
  `contains(ShaderCodeTransformation.UNIFORM_NAME)` early-return (UNIFORM_NAME =
  "seamlessportals_ClipPlane", ShaderCodeTransformation.java:41); both injectors emit the
  identical literal decl; IS3's mixin must reuse the same contains-guard (design mandate).

### §1-E REPO-AUDITS — OQ5, DEF-G, §8-14, the IS1 injection inventory

- **OQ5 SETTLED: `blitAndBlendToTexture` is ALPHA-BLEND, not replace** —
  RenderPipelines.ENTITY_OUTLINE_BLIT carries BlendFunction(SRC_ALPHA, ONE_MINUS_SRC_ALPHA,
  ZERO, ONE) (source-over, dst alpha preserved) and the pass load-op is Optional.empty()
  (no clear — accumulates). The IS1 straight-copy pass pair is MANDATORY, blend explicitly
  OFF (mining §8-7). `copyDepthFrom` = raw copyTextureToTexture REPLACE; **both targets must
  own a depth texture (else IllegalStateException) and it copies DEST.width x DEST.height —
  deferredBuffer MUST be resized to the main RT BEFORE the call (ordering load-bearing).**
  Depth only; no color/stencil (D19 unchanged).
- **DEF-G RE-DECIDED (the design's binary framing was imprecise)**: the C2-2 residual guard
  (MixinSodiumGLDrawContext_ClipUpload:141-147) is PREDICATE-scoped (loc==-1 &&
  FrontClipping armed — would cover iris-patched TERRAIN) but SEAM-CONFINED to sodium's
  GLDrawContext path; iris-patched NON-terrain programs bind via GlCommandEncoder.trySetup
  whose uploader (GlCommandEncoderClipMixin) has NO suppression. Exposure exists only if
  GL_CLIP_DISTANCE0 is armed during the hybrid pass. **RESOLUTION (IS1 driver constraint):
  the hybrid render holds a whole-pass GL_CLIP_DISTANCE0-disabled belt (broad D10 re-arm,
  coverage-complete, independent of P-CLIP-UP), retired at IS3.** Matches the §2.6 stage-(a)
  enableClippingMechanism=false posture — the belt makes it explicit.
- **§8-14 AUDIT: NO divergence, NO IS1 blocker** — renderer.levelRenderState ===
  paired-extractor.levelRenderState is maintained by identity at create
  (ClientWorldLoader:730-752), demote (:1075-1108), promote (:1040-1053) AND defensively
  re-pointed at the dest-pass entry (SecondaryWorldRenderCore:558-568, citing
  PCS:1159-1181). IS1 constraints: reuse the SWRC Step-1..7 prep (the re-point runs);
  render() on WORLD_RENDERER_MAP[destDim]; extract via WORLD_EXTRACTOR_MAP[destDim]; add the
  LRS-identity assert immediately before render().
- **IS1 injection inventory** (nested full render() on a secondary): flag-ON Fabric
  listeners F1 (AFTER_TRANSLUCENT_TERRAIN driver, guard :135-137) and **F2
  (PerEntityClipBracket BEFORE_TRANSLUCENT_TERRAIN, guard PerEntityClipBracket:467-469 — a
  SECOND load-bearing `PortalRendering.isRendering()` early-return the design did not name)**
  both re-fire and early-return. 12 @Mixin(LevelRenderer) classes: **[M4]
  MixinLevelRenderer_CrossPortalEntity (submitEntities HEAD/TAIL/@WrapOperation, UNGUARDED)
  is the single entry running live mod render-logic during the nested render — IS1
  must-classify (clip bracket + iris-disable posture under the nested drive)**; [M12]
  EntityVisibility fires minor at render():274; M11 + Fabric's BEFORE_BLOCK_OUTLINE are
  GATED OUT by the driver's renderOutline=FALSE (**the FALSE 3rd arg is doubly load-bearing
  — fidelity AND neutralization; ledger as a driver constraint**); the rest are held-inert /
  accessor-only / default-inert / extract-scope / correctly-scoped-to-secondary. Sodium's
  and iris's own LevelRenderer mixins firing = WANTED (the arm / the mechanism).

### §1-F NEW census facts from the reconciliation pass (not in any facet)

1. **iris$setupPipeline captures gbufferProjection through SODIUM's duck on the MAIN
   gameRenderer**: bci 32-49 = `CapturedRenderingState.setGbufferProjection(new Matrix4f(
   ((GameRendererStorage) Minecraft.getInstance().gameRenderer).sodium$getProjectionMatrix()))`.
   The bracket does not swap this store — during the nested render the pack's
   gbufferProjection uniform = whatever sodium last stored (the main WORLD projection, still
   active at the post-main anchor). gbufferModelView is taken from the render() ARG (the dest
   view matrix — correct). Benign while dest projection == main world projection (the stage-(a)
   posture); ledgered as a live observable for projection-dependent pack effects in portal
   views (IS2 round).
2. **iris's own hand can render INSIDE the nested dest render**: HandRenderer.canRender gates
   only on camera.isDetached / entity-is-Player / panoramic / HUD-hidden / sleeping /
   spectator — none of which the nested bracket falsifies. iris$beginTranslucents
   (renderSolid) and iris$endLevelRender (renderTranslucent) will therefore attempt the hand
   during the NESTED render with the dest pipeline. Possible artifact: a hand drawn inside
   the portal view. IP upstream shipped the same shape (same iris pathways existed) — treat
   as a pre-registered P-B2/IS2 observable, not a redesign trigger; candidate mitigation if
   confirmed ugly is deep-end (camera-detached spoof or ImmediateState-family toggle during
   the bracket), or accept in the envelope.

---

## §1.1 THE RECONCILIATION TABLE (design fork -> status -> consequence)

| Fork | Status | Consequence for IS1-IS3 |
|---|---|---|
| §0.3 (beta) central mechanism | LIVE-RESIDUAL (P-B2) | Every static leg confirms (class weave on secondaries; 8-arg handler signatures; global-manager resolution; per-secondary dispatchers). The one un-settleable = double per-frame pipeline LIFECYCLE tolerance -> the P-B2 one-shot source-world probe is the gate before IS1 renderer code. |
| §0.3 (alpha) rejection | HOLDS | patchSodium <- ShaderCreator only; global gate + shouldOverrideShaders confirmed. P-alpha/gamma probe = empirical documentation, not a fork gate. |
| §0.3 (gamma) rejection | HOLDS (strengthened) | The nulled field is iris's own scratch cache (overwritten at every render HEAD, nulled at tail); no unpatched program set exists under a pack. Gamma re-entry stays D22-conditional. |
| §0.4-1 anchor post-main | RE-DECIDED (mechanics; intent holds) | Hand moved INSIDE renderLevel on 26.2. Candidate-1 literal (:426) = post-hand -> demoted to the DS6 fallback. Candidate-2 disqualified (:571 depth clear + :570 HUD projection). **New anchor: GameRenderer.renderLevel @ INVOKE LevelRenderer.render(8-arg) shift=AFTER (:566)** — §1.2. D17 wording updates accordingly. |
| §0.4-2 clobber-and-restore | HOLDS | No census contradiction; iris finalize writes main. Final presentation-contract confirm = the P-B2 canary leg (pre-registered STOP class unchanged). |
| §0.4-3 stencil-free stamp (+OQ5) | HOLDS (OQ5 settled) | blitAndBlendToTexture = alpha-blend -> the straight-copy pair is mandatory (design already unconditional). copyDepthFrom = replace; resize-before-copy + both-depth-textures constraints are load-bearing in §2.2-3. |
| §0.4-4 sibling driver + exclusions | HOLDS (inventory enumerated) | Adds: F2 as a second load-bearing isRendering guard (ledger); renderOutline=FALSE doubly load-bearing (neutralizes M11 + Fabric outline event); [M4] CrossPortalEntity = the IS1 must-classify; §8-14 defended in-code, add the IS1 LRS-identity assert. |
| §0.4-5 null bracket vs install | HOLDS (strengthened) | No authoritative getfield read exists; iris nulls the field itself at every render tail. **The install contingency is DEAD** — delete it from the IS1 branch space. |
| §0.4-6 let-iris-select (D18) | HOLDS | getCurrentDimension reads mc.level live -> DEST pipeline under the bracket. Create path is ADDITIVE (not the C2-4 destroy family) -> pre-warm = hitch-mitigation only (OQ7 log rides IS2). Named corners: temporal reset = the D21 mechanism; reload-pending allChanged-on-MAIN-extractor corner; transient manager-slot-at-dest window. Design-conditional: correctness requires the render() to stay INSIDE the level swap (§2.1 preserves). |
| §0.4-7 clip binder ladder (c)-first | HOLDS (static half settled) | Iris does not replace sodium's draw transport; both landed uploaders traverse. IS3 = source injection only (uniform decl + clip write), expression `u_ModelViewMatrix * getVertexPosition()`; runtime confirm at IS3. |
| §0.4-8 default flip at IS3 (Q-U1) | HOLDS | No census bearing. |
| §0.4-9 P-B2 at IS0 | HOLDS | Probe pending (the IS0 probe round); spec deltas in §1.3. |
| §0.4-10 one deferred buffer | HOLDS | + the copyDepthFrom ordering/constraints above; §8-20 teardown unchanged. |
| §0.4-11 gate placement qouteall-side | HOLDS | No census bearing; the anchor mixin lives in seamlessportals-ip-client.mixins.json (S20-safe). |
| §2.3 P-PASTE immunity | HOLDS (three grounds) | Immunity = object identity + no-"sodium"-substring + shouldOverrideShaders; mod-namespace assets stay as belt-and-suspenders. **RULE: never put "sodium" in our pipeline location namespace.** Worst case = one soft log line, never a crash. |
| §2.6 DEF-G | RE-DECIDED (framing) | Guard is predicate-scoped but seam-confined (non-terrain iris programs uncovered). Resolution: IS1 hybrid driver holds a whole-pass GL_CLIP_DISTANCE0-disabled belt, retired at IS3. |
| OQ4 depth-at-anchor | RE-DECIDED (statically positive at the chosen slot) | Depth VALID at :566 (before the :571 clear); statically NEGATIVE at candidate-2/DS6 slots. P-OQ4 readback stays as live confirmation with the expected outcome pre-registered POSITIVE (a negative now means the mixin landed wrong, not "drop the query"). |
| OQ6 iris-without-sodium row | HOLDS (settled) | Hard dep; row unreachable; pre-decision moot. |
| OQ7 | LIVE-RESIDUAL (by design) | The IS2 one-shot selection log; expected outcome now pre-mapped (dest dim; additive create; hitch-only risk). |
| P-B2 / P-alpha-gamma / P-OQ4 probes | LIVE-RESIDUAL | The IS0 probe round; deltas §1.3. |

## §1.2 THE ANCHOR SPEC (the IS0 inert mixin — concrete)

```
Config:   seamlessportals-ip-client.mixins.json (qouteall client config; zero com.warwa deps)
Class:    qouteall.imm_ptl.core.render.MixinGameRenderer_IPPostLevelAnchor (new)
Target:   @Mixin(net.minecraft.client.renderer.GameRenderer.class)   // default priority 1000
Inject:   @Inject(
            method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE",
              target = "Lnet/minecraft/client/renderer/LevelRenderer;render(" +
                "Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;" +
                "Lnet/minecraft/client/DeltaTracker;Z" +
                "Lnet/minecraft/client/renderer/state/level/CameraRenderState;" +
                "Lorg/joml/Matrix4fc;" +
                "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;" +
                "Lorg/joml/Vector4f;Z)V",
              shift = At.Shift.AFTER))
Landing:  GameRenderer.renderLevel:566 — the sole LevelRenderer.render INVOKE in renderLevel
          (unique match). After iris finalize (iris$endLevelRender ran inside the 8-arg render
          at popMatrix); scene depth VALID (before the :571 clearDepthTexture); WORLD
          projection active (before the :568-570 HUD switch); before vanilla hand :572;
          before iris$runColorSpace (renderLevel TAIL). No iris inject in the window ->
          no priority clause needed (deterministic by distinct bytecode positions).
Body:     Matrix4f modelView = new Matrix4f(
            this.gameRenderState.levelRenderState.cameraRenderState.viewRotationMatrix);
          // == the object passed as render()'s 5th arg (GameRenderer:532-533); no @Local.
          IPCGlobal.renderer.onBeforeHandRendering(modelView);
Inert:    base hook is empty (PortalRenderer.java ~:111). [CORRECTED at the IS0 verify pass,
          Lens B — the original "NO existing renderer overrides it" was FALSE as stated:]
          two held-source iris renderers DO override onBeforeHandRendering
          (IrisPortalRenderer:135, IrisCompatibilityPortalRenderer:178) but are UNREACHABLE
          receivers — IPCGlobal.renderer is assigned only at IPModMainClient:86
          (rendererUsingStencil) and PortalRenderer.switchRenderer:477 (fed solely by
          switchToCorrectRenderer:461-471 = {rendererDummy under iris+pack per D8,
          rendererUsingStencil, rendererUsingFrameBuffer, rendererDebug}), and none of the
          four overrides the hook. No-op for every REACHABLE receiver until IS1 deliberately
          flips the D8 routing: the inert label survives via reachability, not absence of
          overriders. Gate-audit per the IS0 deliverable.
Fallback: DS6 (named, NOT used) = GameRenderer.render @ INVOKE renderLevel shift=AFTER (:426)
          — post-hand + post-depth-clear + post-colorspace; only if the :566 slot proves
          unusable live.
```

## §1.3 PROBE-CODE SPEC DELTAS (vs design §1-IS0 deliverable 3)

1. **P-OQ4**: run the depth readback AT the :566 anchor (inside the anchor handler under the
   probe lever), NOT "post-main" generically — the design's slot-agnostic wording would
   permit a post-:571 sample that is all-cleared by construction. Expected outcome
   pre-registered POSITIVE; an all-far readback at :566 now discriminates "mixin landed at
   the wrong site", not "depth unusable -> drop the occlusion query".
2. **P-B2** additions: (a) log `Iris.getCurrentDimension()` + the preparePipeline result's
   object identity at the nested render HEAD (source-world probe -> expect source dim,
   existing pipeline, NO create, NO SystemTimeUniforms reset); (b) observe whether
   HandRenderer.renderSolid/renderTranslucent fire during the nested render (the §1-F-2
   hand-in-portal-view observable — log HandRenderer.isRenderingSolid or the ACTIVE
   transitions one-shot); (c) after the probe frame, note the manager slot state
   (getPipelineNullable expected = the probed pipeline until next main HEAD — self-corrects;
   log once); (d) unchanged: pipeline object identity + pass list + the pre-registered
   discriminator map.
3. **P-alpha/gamma**: unchanged mechanics (GL_CURRENT_PROGRAM + GL_DRAW_FRAMEBUFFER during
   one levered decomposed draw under a pack); add the expected context to the log line:
   decomposed Step-10 draws run isRenderingWorld=true/isMainBound=true, so substitution IS
   live for them — expect iris program ids (that IS the alpha documentation).
4. **DEF-G probe: RETIRED** — settled statically (§1-E); replaced by the IS1 driver
   constraint (whole-pass clip-disable belt).
5. **No new probe** for the §1-F-1 gbufferProjection duck — recorded as an IS2 live-round
   observable (projection-dependent pack effects inside portal views), zero code now.

## §1.4 CORRECTIONS MADE AT THE RECONCILIATION PASS

- **Design §1-IS0 P-A1 "default: Candidate 1 (post-renderLevel INVOKE shift=AFTER)" is
  CORRECTED** — on 26.2 that literal slot is post-hand/post-depth-clear/post-colorspace
  (the DS6 fallback). The autonomous-execution default is now the §1.2 spec.
- **"MixinShaderManager_Overrides" naming**: the substitution lives on
  GlDevice.getOrCompilePipeline — design/port-note prose kept the iris class name, but any
  future search must target GlDevice (no ShaderManager class is involved).
- **IP-1.8 yaml symbol `iris_ModelViewMatrix`**: does not exist on the 1.11.2 sodium path;
  the forward view matrix is `u_ModelViewMatrix` (u_Globals UBO). IS3 uses
  `u_ModelViewMatrix * getVertexPosition()`.
- **C2-2 SodiumClipShaderPatch position keying**: the local `vec3 position` is not
  guaranteed in transformed pack source — IS3 switches to `getVertexPosition()`.
- **DEF-G framing** corrected from "sodium-id-scoped?" binary to predicate-vs-seam (§1-E).
- **Facet spot-checks**: P-A1 anchor facts, P-B1 bci 135-145/82-84, P-PASTE full gate chain +
  Object2ObjectArrayMap + contains("sodium") + FAKE_FUNCTION, patchSodium
  transformInternal/Parameters/u_Globals/getVertexPosition unconditional-in-VERTEX-branch —
  ALL independently re-run and CONFIRMED; no facet verdict was wrong. (Minor precision:
  RenderPipeline.getSortKey's super.hashCode() use sits behind a
  DEBUG_SHUFFLE_UI_RENDERING_ORDER ternary at RenderPipeline.java:65 — substance unchanged:
  no equals/hashCode override, identity keying stands.)

## §1.5 IS0 IMPLEMENTATION RECORD

(Numbered §1.5 because §1.4 was already the reconciliation-corrections ledger; the
orchestrator's "create §1.4" slot was taken.)

### Files (worktree `is0-recon`, branch `iris-on/is0-recon`)

- **NEW** `common/.../qouteall/imm_ptl/core/mixin/client/render/MixinGameRenderer_IPPostLevelAnchor.java`
  — the §1.2 inert post-main anchor. PACKAGE DEVIATION from the §1.2 spec block: the spec
  names `qouteall.imm_ptl.core.render.*`, but the config's mixin package root is
  `qouteall.imm_ptl.core.mixin` and a mixin must live under its config's package tree — same
  class name, config-mandated package. Adds a defensive `IPCGlobal.renderer != null` guard
  around the §1.2 two-line body. The Matrix4f copy KEPT per spec (owned-mutable-copy hook
  contract; the AFTER_TRANSLUCENT_TERRAIN driver idiom).
- **NEW** `common/.../qouteall/imm_ptl/core/compat/iris_compatibility/ShaderpackViewsProbeLever.java`
  — the minimal lever holder (see Lens-B correction 2 below).
- **NEW** `common/.../qouteall/imm_ptl/core/compat/iris_compatibility/ShaderpackViewsProbe.java`
  — the §1.3 probe suite: P-OQ4 (16x16 centered depth readback AT the anchor, FBO via
  frameBufferCache().getFbo live), P-alpha/gamma (one commanded decomposed dest render via the
  landed renderWorldNew machinery, program/FBO logged around it), P-B2 (snapshot main
  color+depth to a probe-local TextureTarget via straight copyTextureToTexture — REPLACE, not
  the alpha-blend blitAndBlendToTexture — then ONE direct 8-arg render() of the SOURCE dim's
  map renderer, stencil+clip neutralized, restore-in-exact-reverse in a finally; iris
  HEAD/TAIL pipeline-identity + HandRenderer logs; DrawCallTrace armed for the probe frame =
  the FramePass evidence; frame N+1 marker). All iris references in the lazily-classloaded
  nested IrisSide (public statics only, no new reflection).
- **EDIT** `common/src/main/resources/seamlessportals-ip-client.mixins.json` — anchor
  registered (qouteall config; weave-gated flag-ON; S20-safe).
- **EDIT** `fabric/build.gradle` — `-PshaderpackViewsProbe=true` => vmArg
  `-Dseamlessportals.shaderpackViewsProbe=true` on clientSodium AND crossingGametest
  (verbatim sodiumCompatLever pattern).

### Verify ledger (two-lens Fable pass; both PASS_WITH_CORRECTIONS; all 5 corrections APPLIED, 0 refuted)

Lens A (defect):
1. **APPLIED** — P-alpha portal-wait window was anchored to the ABSOLUTE frame counter
   (>= SETTLE+WAIT = 800); enabling the pack late via the Iris UI zeroed the window. Fix:
   `firstArmedFrame` recorded when the iris+pack gate first passes; fallback condition now
   `frameCount >= firstArmedFrame + ALPHA_PORTAL_WAIT_FRAMES`.
2. **APPLIED** — the mid-packet skip guard checked only nullity; the real 26.2 mid-packet
   class is the MISMATCH frame. Guard extended to
   `mc.player.level() != mc.level` (the CrossPortalViewRendering:59-62 predicate; skip,
   never assert).
3. **APPLIED** — FramePass evidence was coupled to B2 by frame COUNTING only; a skipped
   intervening frame spent the one-frame trace and B2 ran untraced. Fix: B2 dispatch now
   gates on `DrawCallTrace.capturing` (set at renderLevel HEAD, true at the anchor of any
   captured frame) and re-arms + retries if the capture was spent.

Lens B (inertness):
4. **APPLIED (doc)** — the anchor's inertness ground "no overrider exists" was FALSE
   (IrisPortalRenderer:135 / IrisCompatibilityPortalRenderer:178 both override the hook);
   inertness HOLDS via receiver reachability (the four switchRenderer-assignable renderers
   don't override it; D8 routes shaders-ON to rendererDummy). Anchor javadoc + the §1.2
   Inert line rewritten to the reachability form. Verified in-repo before applying
   (grep: overriders + both assignment sites confirmed).
5. **APPLIED** — `PROBE_ENABLED` as a static-final on the probe class is NOT a javac
   compile-time constant (Boolean.getBoolean is a method call), so the mixin's per-frame
   read class-initialized the probe suite in EVERY environment. Fix: the lever moved to the
   minimal holder `ShaderpackViewsProbeLever` (its `<clinit>` = the one field); the probe
   class now loads only property-set + first dispatch. NOT hosted on the mixin class (Mixin
   does not merge static initializers — a non-constant static field initializer on a mixin
   is silently dropped).

### NOTEs folded (no behavior change)

- P-OQ4 ALL-FAR verdict text now carries the open-sky confound caveat (reversed-Z sky reads
  0.0) + **probe-round procedure: aim at TERRAIN for the OQ4 leg**.
- P-B2's nested render re-fires the F1 AFTER_TRANSLUCENT_TERRAIN driver a SECOND time with
  isRendering()==false — benign at IS0 only via the D8 rendererDummy routing; EXPECT the
  extra dispatch in the B2 draw trace; CONSTRAINT: if the probe pattern is reused post-IS1
  with a real shaders-ON renderer live, push a portal layer / assert isRendering() around
  the nested render (comment at the render() call).
- P-B2 verdict block now names the double compile-drain confound: transient N+1 chunk-mesh
  pop that self-heals = the FBO_PRECEDENT §8-13 class, NOT lifecycle evidence; only
  persistent/full-frame corruption discriminates.
- render() 8th arg `shouldRenderSky=true` diverges from vanilla's
  `!bossOverlay.shouldCreateWorldFog()` under an active boss world-fog — immaterial for the
  one-shot probe; commented at the call.
- P-alpha uses getEffectiveRenderDistance, not getPortalRenderDistance(portal) — fine for a
  documentation leg; switch if promoted (comment in the builder chain).
- The probe imports the com.warwa fog-accessor mixin (existing qouteall-side precedent;
  the zero-com.warwa rule binds the ANCHOR MIXIN only) — S20 sweep note in the probe header.
- Lens-B persistent-state audit of P-B2: CLEAN (GLOBAL_PASS_SERIAL untouched; existing
  pipeline hit at HEAD; UNPOOLED allocator + same-frame vanilla endFrames; snapshot
  destroyed in finally; stencil/clip left in the neutral post-main state).

### Probe round

```
Set-Location "C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is0-recon"
.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PshaderpackViewsProbe=true
```

Enter a world, enable the reference pack (Complementary Reimagined), AIM AT TERRAIN. Legs
self-arm 200 anchor-frames after the pack gate first passes: P-OQ4 immediately; P-alpha
waits up to 600 frames (from gate-pass) for a portal in view (stand near one; else the
documented fallback line); P-B2 fires ~40 frames after alpha resolves, inside a verified
draw-traced frame; frame N+1 marker follows. Grep `[IS0-PROBE]`. Compile gate re-run to
green after the verify corrections. Default (no property): the probe class never loads; the
anchor dispatches the empty hook to a non-overriding receiver — inert by reachability.
