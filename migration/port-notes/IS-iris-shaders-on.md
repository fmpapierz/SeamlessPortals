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
  GATED OUT by the driver's renderOutline=FALSE (**ROW REVISED AT IS2 (FIX-O, the §3.1
  defect-O verdict): the old "doubly load-bearing" claim is HALF-RETIRED — the Fabric-NPE
  half was decomposed-era reasoning (jar-verified: the BEFORE_BLOCK_OUTLINE
  WorldRenderContext is per-render-INSTANCE on 26.2, so the nested render carries its own),
  and the fidelity half points the OTHER way (IP delivered dest outlines through its compat
  renderer). The driver now passes renderOutline = cross-dim-only (vanilla's per-frame
  predicate via the S18.5 invoker; mc.hitResult is the shell-swapped REMOTE hit); SAME-DIM
  stays FALSE and THAT half remains load-bearing — destLRS==mainLRS would re-submit the
  MAIN outline at the dest transform**); the rest are held-inert /
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

## §1.6 THE IS0 PROBE ROUND — RUN + CLASSIFIED (2026-07-20, SELF-RUN x3; VERDICT: GO)

Vehicle: the crossing-gametest harness with `-PirisRuntime=true -PshaderpackViewsProbe=true`
+ ComplementaryReimagined_r5.8.1 staged (`shaderpacks/` + `config/iris.properties`
enableShaders=true) — the self-run pattern (user-routed 2026-07-20): a driven real client,
log-classified evidence, no user round needed at IS0.

**Attempts 1-2 CRASHED — root cause found, fixed, and the fix is itself evidence** (commits
68e223e superseded-by 5dbd0af): EXCEPTION_ACCESS_VIOLATION ~1s after the P-OQ4 line, victim
= C2 CompilerThread0 (hs_err_pid248404, CompileTask IOWorker::storePendingChunk) then GC
Thread#5 (hs_err_pid130364). First classified as the S19 Temurin JIT defect class — WRONG:
two DIFFERENT housekeeping-thread victims at the same run-point = native heap corruption
with varying victims. Mechanism (engine-source-proven): `GlCommandEncoder.copyTextureToBuffer`
(:346) sets `GL_PACK_ROW_LENGTH = width` via the raw uncached `_pixelStore` and never resets
(vanilla immune: its readbacks target bound PBOs; the gametest screenshot machinery is the
armer) → P-OQ4's client-memory 16x16 glReadPixels wrote strided rows over the native heap.
FIX = the pack-state bracket (save 4x GL_PACK_*, force tight 0/0/0/4, restore
exact-reverse); compact Fable verify PASS (4 NOTEs: no-try/finally acceptable-for-
instrumentation; PBO-binding hole is a non-corruption class; sweep = P-OQ4 was the ONLY
live client-memory readback; LATENT dead-code same-class sites MyRenderHelper
debugFramebufferDepth/:452 ColorRed — S20 audit items). Memory
`26-2-glstate-and-fbo-invariants` gains invariant #4; the JIT-exclude for
IOWorker::storePendingChunk (68e223e) KEPT — harmless, and the first hs_err is now
explained, not defect-class evidence. DISCRIMINATION LESSON: jvm.dll + compiler-thread
victim MASQUERADES as the Temurin class; discriminate by victim VARIETY across repro runs +
proximity to a readback.

**Attempt 3 (5dbd0af): exit 0, ALL 8 LEGS PASS under iris+pack, no hs_err. The probe
verdicts:**

- **P-OQ4 CONFIRMED** + the mechanism proven in-run: pre-bracket `GL_PACK_ROW_LENGTH=854`
  (the gametest window width — STALE, the corruption arm caught red-handed). Depth
  readback fbo=3 glGetError=0; this run min=max=mean=1.0 (attempt-1 read min=0.0 max=1.0
  mean=0.0625 pre-crash) — non-trivial both times, verdict stands; the all-1.0 flatworld
  oddity is noted, not load-bearing (the IS1 round re-reads depth in a real scene).
- **P-alpha DOCUMENTED** (the rejection observed live): one commanded decomposed dest
  render under the pack at the post-main slot — runs with NO iris lifecycle scoped to it,
  programs bind (GL_CURRENT_PROGRAM=31 at tail), glGetError=0. Gamma re-entry stays
  D22-conditional; nothing here re-opens it.
- **P-B2 = GO (the decisive verdict).** ONE direct 8-arg `LevelRenderer.render()` on
  WORLD_RENDERER_MAP[overworld] (= the MAIN renderer, promoted-identity — logged; all
  bracket fields identity for the source-world case) at anchorFrame 816 under the active
  pack: `renderReturned=true exception=none elapsedMs=7 glGetError=0`; iris pipeline
  object IDENTICAL head/tail (no create, manager slot behaves per §1.3-2c);
  `rendererPipelineField=null` post = **iris$endLevelRender ran at the NESTED render's own
  tail** — iris's full class-woven lifecycle re-entered; the DrawCallTrace dump shows the
  frame carrying TWO complete iris pass sequences INCLUDING TWO `Final pass
  (iris:composite)` executions = the nested render went through iris's WHOLE pipeline to
  presentation (the presentation-contract STOP class did not fire). Frame N+1:
  glGetError=0; the harness then played 30+ more seconds through legs 3/4/5/6a/6b/7 (incl.
  world reopen) to ALL LEGS PASS — no crash in any iris$ hook, no persistent corruption
  signal. RESIDUAL (named): pixel-level visual cleanliness of frames N/N+1 is
  un-judged by logs — it rides the IS1 visual round's pre-screen (screenshots) per the
  self-run split; the elapsedMs=7 figure also pre-answers the §2.7 cost envelope's
  order-of-magnitude question for one layer.

**IS0 EXIT CRITERIA MET** (design §1 IS0 Verify + the §1.1 reconciliation): every static
fork HOLDS or is RE-DECIDED with evidence; the one live-residual (P-B2) is now GO;
suite green at default in the same worktree (pre-probe baseline + the attempt-3 run is
itself an 8-leg pass under iris). IS1 is UNGATED.

---

## §2 THE IS1 IMPLEMENTATION (worktree `is1-driver-paste`, branch `iris-on/is1-driver-paste`)

### §2.1 Files

- **NEW** `common/.../qouteall/imm_ptl/core/compat/iris_compatibility/IrisCompatOn262Renderer.java`
  — the §2.2-member-walk renderer (design §1 IS1 deliverable 2): prepareRendering = stencil belt
  ONLY (deferred prepare/clear moved to the workhorse; the held source's
  IPPortingLibCompat.setIsStencilEnabled DROPPED — §0.4-3 stencil-plumbing NON-dependency);
  onBeforeHandRendering = the workhorse (arePipelinesReady gate → prepare+resize → reversed-Z
  clear → depth copyDepthFrom + color straight-copy snapshot → renderPortals(passingModelView)
  → deferred→main blit-back IN THE FINALLY — §2.5 fold);
  doRenderPortal (one-layer guard → occlusion test → push → try{renderPortalContent}
  finally{pop} (§2.5 BLOCKER fix, the S14.29 precedent) → stamp /
  debug raw view → cache-coherent GlStateManager._colorMask(15) restore — §2.5 fold);
  invokeWorldRendering with the **D23 layer-0 fallback**
  (isInsideOwnRenderPortals latch — CrossPortalViewRendering/GuiPortalRendering direct
  invocations fall back to the decomposed renderWorldNew; REQUIRED at IS1 already, those
  paths are reachable with the lever armed); §8-20 teardown (CLIENT_CLEANUP_EVENT + the
  switchRenderer switch-away eviction).
- **NEW** `common/.../qouteall/imm_ptl/core/render/IrisCompatPaste.java` — the paste family
  (deliverable 3): `portalAreaSample` (POSITION_COLOR portal mesh, ONE combined P·MV clip
  matrix as the Projection UBO, fragment `texelFetch` at gl_FragCoord = the 1:1 screen-space
  law; **depth GREATER_THAN_OR_EQUAL, no write — reversed-Z "closer = larger", GEQUAL not
  GREATER because the portal entity's own quad may have written plane depth**; blend off) +
  `portalStraightCopy` (mod-namespace screenquad/blit_screen copies; Optional.empty() depth =
  the working DISABLED state — the ALWAYS_PASS trap honored; OQ5: blitAndBlendToTexture never
  used on this path). All four block-era fixes on every pass (6-arg createRenderPass + explicit
  full RenderArea; depth Optional.empty() on copies; pass-bound NEAREST clamp sampler; blend
  off + GlStateManager backstops). Frame-transient buffers ride the S14.30 ledger.
- **NEW shader assets** `common/src/main/resources/assets/seamlessportals/shaders/core/
  {screenquad.vsh, blit_screen.fsh}` (verbatim vanilla copies — P-PASTE belt-and-suspenders)
  + `{portal_area_sample.vsh, portal_area_sample.fsh}` (the stamp pair). No "sodium" substring
  anywhere in our namespace (the §1-C rule).
- **EDIT (additive)** `MyGameRenderer.java` — `renderWorldFullPipeline(WorldRenderInfo)` +
  `switchAndRenderTheWorldFullPipeline(...)`: **DUPLICATE-with-pairing-comment** (the design
  §2.1 implementer's choice, taken for zero-drift: `switchAndRenderTheWorld` is BYTE-UNTOUCHED;
  the duplicate carries a lens-checkable PAIRING CONTRACT block listing the SAVE/SWAP/RESTORE
  sets in order). Differences from the decomposed shell, all declared in the contract block:
  the invoke body = `renderDestWorldFullPipeline`; the invokeWrapper indirection dropped
  (every live caller passed Runnable::run); renderWorldFullPipeline wraps push/popRenderInfo
  in try/finally (renderWorldNew's pop is not throw-protected — hardening, not drift: the
  decomposed entry is untouched).
- **EDIT (additive)** `SecondaryWorldRenderCore.java` — `renderDestWorldFullPipeline(...)`:
  the §2.1 core body (Step-2 extractor router + §8-14 re-point/assert, Step-3 matrices +
  conventional-Z frustum, Step-4 camera state, Step-5 cross-dim extract + identity-guarded
  SOG feed, Step-6 fog family, Step-7 projection, Step-8 Globals-UBO, the same-dim sodium
  drive, then ONE direct 8-arg `destRenderer.render(UNPOOLED, deltaTracker, false,
  destCameraState, destViewMatrix, destFogBuffer, destFogData.color, doRenderSky)` into the
  main target). NORMATIVE EXCLUSIONS honored in-code: no compile drain, no
  ip_armDestChunkRenders, no armed VisibleSectionDiscovery/manual visibleSections, no SOG
  feed beyond the identity-guarded one. DEF-G whole-pass clip belt via
  FrontClipping.disableClipping (the cached owner — never raw-GL) + raw stencil disable;
  finally = UBM latch reset, Globals restore, source diffuse, camera-state/fog-field
  restores, shader-fog slice restore, stencil-NEUTRALIZE (disabled — the stencil-free shape),
  §8-3(c) source setupFog re-run.
- **EDIT (behavior-identical refactor)** `ViewAreaRenderer.java` — the mesh BUILD (incl. the
  S14.36 near-plane clip) extracted to `buildPortalViewAreaMesh(...)`; the existing draw path
  calls it (build → null-check → drawMesh, unchanged). Single source of truth for the stamp's
  geometry route.
- **EDIT (additive)** `IPGlobal.java` — `experimentalShaderpackPortalViews` (default false,
  qouteall-side per §0.4-11) + `SHADERPACK_VIEWS_JVM_LEVER`
  (`-Dseamlessportals.shaderpackViews`) + `isShaderpackPortalViewsArmed()`.
- **EDIT** `renderer/PortalRenderer.java` — the D8-EVO lever-only routing branch AHEAD of the
  existing selection (armed && renderMode!=none → instance / debug→debugModeInstance, both
  shaders-ON and shaders-OFF proof rows; renderMode=none respected → dummy) + the one-shot
  lever-armed pack-ON notice; switchRenderer gains the compat-family switch-away eviction
  (instanceof — does not class-initialize; byte-inert unarmed). UNARMED = byte-identical
  pre-IS1 behavior (flag false + lever absent ⇒ the new branch is never taken; D8 dummy +
  notice verbatim; the deferred reloadPipelines one-shot rides unchanged and now also covers
  compat↔dummy transitions when armed).
- **EDIT (header comment only)** `IrisCompatibilityPortalRenderer.java` — the doc-pointer line.
- **EDIT** `fabric/build.gradle` — `-PshaderpackViews=true` ⇒
  `-Dseamlessportals.shaderpackViews=true` on `client`, `clientSodium`, `crossingGametest`
  (the established lever-passthrough pattern).

### §2.2 THE IS1 INJECTION INVENTORY (design §1 IS1 deliverable 4 — every flag-ON mixin +
fabric listener firing inside the REAL nested LevelRenderer.render, classified)

Context: the nested render always runs INSIDE `pushPortalLayer..popPortalLayer`
(doRenderPortal), so `PortalRendering.isRendering()==TRUE` for its whole extent — the
recursion-guard family below is structurally armed. `renderOutline=FALSE` on the 8-arg call
is DOUBLY load-bearing (§1-E): IP fidelity AND it gates out the block-outline family.

| # | Injection (site) | Classification | Ground |
|---|---|---|---|
| F1 | Fabric AFTER_TRANSLUCENT_TERRAIN driver (SeamlessPortalsClientFabric:134-146) | **guarded-by-isRendering (load-bearing)** | Re-fires inside the nested framegraph; early-returns because the nested render runs inside the pushed layer (§2.4-7 lens assert). The IS0 §1.5 NOTE's constraint ("push a portal layer around a real-renderer nested render") is SATISFIED by construction — doRenderPortal pushes before renderPortalContent. |
| F2 | PerEntityClipBracket BEFORE_TRANSLUCENT_TERRAIN (PerEntityClipBracket:467-469) | **guarded-by-isRendering (the SECOND load-bearing guard — §1-E ledger line)** | Same re-fire class; early-returns inside the pushed layer. |
| F3 | Fabric BEFORE_BLOCK_OUTLINE (inside vanilla submitBlockOutline) | **gated-out by renderOutline=FALSE** | submitBlockOutline is only called when render()'s renderOutline arg is true; Fabric's per-frame context is null outside the real MAIN framegraph (the S18.5 NPE class) — the FALSE arg forecloses it. |
| M1 | LevelRendererAccessorMixin | benign | Accessor-only, no injections. |
| M2 | LevelRendererBlockOutlineMixin (@ModifyArg submitBlockOutline) | **gated-out by renderOutline=FALSE** | Host method never runs (the [M11] of §1-E). |
| M3 | LevelRendererCompileSectionsMixin (@Redirect in compileSections) | benign/wanted | compileSections runs INSIDE the nested render() on the DEST renderer — the redirect resolves per-instance state; this is the §8-13-correct single drain (the sibling core drains nowhere else). |
| M4 | **MixinLevelRenderer_CrossPortalEntity** (submitEntities HEAD/TAIL/@WrapOperation, unguarded) — THE §1-E MUST-CLASSIFY | **benign-live-equivalent (RESOLVED)** | Already fires per pass on every LevelRenderer instance incl. secondaries since S13 (its own header): the decomposed path invokes submitFeatures on the same storages. Nested-render specifics: (a) HEAD's CASE-3 setupInnerClipping arms GL_CLIP_DISTANCE0 with the pass's OWN dest view matrix + this portal's plane during the SUBMIT phase (CPU, pre-framegraph-execute) and TAIL's disableClipping disarms it before any draw executes — no draw runs clipped, the DEF-G belt (asserted before render()) governs the EXECUTE phase; (b) per-storage scoping keys on the secondary's own SubmitNodeStorage (Verifier-1 P2); (c) renderEntityProjections at TAIL is iris-DISABLED (isCrossPortalRenderingEnabled → false when iris present — the C2-4 IP-faithful posture, design IS2 leg 7) and on sodium-only rows runs exactly as it already does for decomposed secondary submits. No bracket added. |
| M5 | MixinLevelRenderer_ForceMainThreadRebuild (compileSections internals) | benign/wanted | Rides the same single compileSections drain as M3. |
| M6 | MixinLevelRenderer_Optional (① translucent-sort HEAD-cancel while isRendering; ② sort-camera redirect; ③ per-layer clip uniform; ④ ViewArea update position) | **guarded-by-isRendering (wanted)** | IP-verbatim guards; ① actively protects the main translucent-sort state from the nested render — WANTED. ③'s clip upload no-ops under the DEF-G belt (FrontClipping disarmed for the whole pass). |
| M7 | MixinLevelRenderer (R4 ImmPtlViewArea install @Redirect in invalidateCompiledGeometry) | benign/wanted | Fires only if the dest extract consumes shouldInvalidateCompiledGeometry (RD change/reload); installs the per-dim grid exactly as on the decomposed path; under sodium the whole body is HEAD-cancelled (sodium$replace) as always. |
| M8 | MixinLevelRenderer_Clouds (cloudOptimization) | benign | Cloud-pass optimization keyed to the executing renderer instance; same-dim nested clouds are SUPPRESSED outright (cloudColor zeroed — §2.3 row 4), cross-dim secondary CloudRenderer is textureless (no-op render). |
| M9 | MixinLevelRenderer_PortalWand (submitFeatures RETURN) | benign (cosmetic, ledgered) | Wand overlay submit re-runs for the nested pass; body checks are player/item-scoped (IP-verbatim isRendering checks inside render bodies per its header). Worst case = wand gizmos visible in a compat window — a live-round observable, not a defect class. |
| M10 | LevelRendererCullTerrainMixin (cullTerrain HEAD) | benign | Sodium-present early-return; the prime-consume path is PortalWorldManager-scoped (block-era flag-OFF machinery, consumePendingPrime false flag-ON). |
| M11 | LevelRendererDiagMixin (SOG update probes) | benign | Log-gated diagnostics. |
| M12 | LevelRendererEntityVisibilityMixin (isSectionCompiledAndVisible HEAD) | benign (the §1-E "minor at render():274") | Affects only the playerCompiledSectionCallback gate; spurious dest-camera runs are idempotent vanilla one-shots. |
| S | sodium LevelRendererMixin (prepareChunkRenders wrap + endFrame RETURN) | **WANTED — the arm** | Mining §5: sodium arms the ChunkSectionsToRender render() builds internally, with the pass's own matrices (the scratch/dest cameraRenderState). This replaces ip_armDestChunkRenders on this path (which is EXCLUDED — §0.4-4). |
| I | iris MixinLevelRenderer (class weave: setupPipeline/beginLevelRender/endLevelRender + framegraph lambdas) | **WANTED — the mechanism** | P-B2-proven: the full iris lifecycle re-enters on the secondary instance and finalizes into the main target. |

### §2.3 Design-interpretation decisions (flagged for the IS1 lenses)

1. **Bracket-share vs duplicate (deliverable-1 choice): DUPLICATE, chosen OVER the two
   sharing forms the design §2.1 lists** (extracted private driver / strategy branch — both
   SHARE the bracket; a duplicate copies it), satisfying the binding constraint maximally:
   "zero drift of the decomposed path" beats DRY; `switchAndRenderTheWorld` is byte-untouched
   and the sibling carries the PAIRING CONTRACT comment the lens diffs. CONSEQUENCE (Lens-F
   fold, §2.5): future edits to `switchAndRenderTheWorld` require a hand-mirrored edit of the
   sibling — **the pair is an S20/gate-audit LOCKSTEP item** (any edit to either re-diffs
   both against the contract block).
2. **Frustum capture (decomposed Step 3.5) is CROSS-DIM-DROPPED / SAME-DIM-KEPT.**
   Un-captured, extract's applyFrustum branch + sodium's cullTerrain anchor (at the
   SOG.consumeFrustumUpdate INVOKE inside the capturedFrustum==null branch) + render()'s
   sog.update graph scheduling ALL run naturally — "render() gets real occlusion"
   (mining §7.2-B). The cull frustum stays OUR conventional-Z build (I7: applyFrustum and
   sog.update both offsetToFullyIncludeCameraCube it). Same-dim keeps the capture to suppress
   dest-camera graph churn on the MAIN SOG (no extract runs there anyway).
3. **Same-dim (sharedState) discipline mirrors the decomposed path: NO extract.** A same-dim
   extract would reposition the MAIN SectionUpdateTracker to the portal camera + re-flip the
   main delta window. Consequences (pre-registered IS1 observables, NOT defects):
   (a) same-dim compat windows draw NO entities (main entityRenderStates consumed+cleared by
   the main pass — the S15 same-dim family; the lever-off stencil path retains the S15
   isolated pipeline, so the A/B leg DIFFERS here by design); (b) plain-row same-dim windows
   draw no vanilla terrain (shell-swapped empty visibleSections; armed discovery excluded) —
   sky/fog only; the DECISIVE same-dim row is sodium (design SD-ROW) where terrain rides the
   D1-swapped context via the explicit same-dim `ip_driveDestTerrainSetup` (the §2.4-4
   "decided in OUR code" item: drive for sharedState only — cross-dim culls naturally in the
   un-captured extract).
4. **Shared-state clouds/weather suppression (26.2-forced, crash-class foreclosure).** The
   nested render() on the MAIN renderer would rotate the main CloudRenderer's utb a SECOND
   time per frame at a different camera cell (the S18.3 fence-crash class) and draw
   MAIN-camera-centric weather columns at the portal camera (the S18.7 AIOOBE class —
   UNCAUGHT inside the framegraph). cloudColor zeroed (alpha gates addCloudsPass off;
   restored in the finally) + weatherRenderState reset (columnCount==0 no-op; next main
   extract refills). Ledgered observables: no clouds/weather in same-dim compat windows.
   Cross-dim: the secondary's own CloudRenderer is textureless (never reload-registered) →
   addCloudsPass no-ops → **no vanilla dest clouds in compat windows at IS1** (the decomposed
   path's mod-owned per-dim cloud isolation is Step-10.11 machinery, not reused here) —
   accepted stage-(a) envelope item.
5. **render() 8th arg** = WorldRenderInfo.doRenderSky (IP's fuse-view no-sky), not literal
   true; vanilla's boss-world-fog clause is not re-derived (immaterial, commented in-code).
6. **§8-3(c) source setupFog re-run** added to the sibling finally (block-era Step-9
   discipline; compute-only on 26.2; one extra AtmosphericFogEnvironment lerp step toward the
   SOURCE level per pass — benign, commented).
7. **endFrame audit (§2.1 lens item), no additions needed:** newly-exercised owners under the
   full render() = the secondary's SkyRenderer (no per-frame ring; closed by vanilla lifecycle),
   the secondary's CloudRenderer (textureless → render no-ops → no rotation; covered by
   ClientWorldLoader.endFrameOnSecondaryLevelRenderers under sodium anyway), the secondary FRD
   buffers + pooled RenderBuffers (already covered: endFrameOnSecondaryFeatureBuffers +
   endFramePooled), frame-transient stamp buffers (the S14.30 ledger). "Resizing Sodium
   terrain uniforms" spam stays the pre-registered regression signal.
8. **Plain-row pre-registered artifacts** (lever-ON only): first-frame SOG-walk stutter
   (§6.3 class — design-accepted); same-dim main-grid repositionCamera ping-pong (render()'s
   own repositionCamera recenters the main ViewArea at the dest camera each window frame; the
   next main frame recenters back — §8-12 class, plain-row-only: sodium's IgnoringViewArea
   no-ops it). **Lens-D upgraded wording (§2.5 fold): per the fix-A memory ("warm-swap
   repositionCamera resets meshes to UNCOMPILED"), a FAR same-dim portal may drive a
   MAIN-WORLD RECOMPILE STORM / terrain holes on the plain row, not just churn — watch for it
   in the plain proof row; if confirmed, the IS2 candidate is restoring the main grid position
   (or a sharedState repositionCamera skip), NOT accepting the ping-pong.** The decisive
   sodium row is unaffected. Both named for the live round.
9. **Both-levers precedence (Lens-G CORRECTION, defined here):** the IS0 anchor
   (`MixinGameRenderer_IPPostLevelAnchor`) fires `renderer.onBeforeHandRendering` — the FULL
   compat pass incl. blit-back — BEFORE the `ShaderpackViewsProbe` dispatch. With
   `-PshaderpackViewsProbe` AND `-PshaderpackViews` both set, P-alpha/P-B2 therefore snapshot
   and judge frames that already contain compat-stamped portal views + the deferred blit-back,
   confounding the probe's pre-registered discriminators (P-B2 "clean N/N+1", the FramePass
   trace). **Probe rounds are defined for renderer-lever-OFF only; both-levers is a
   diagnostics-confounded configuration, not a supported row.** Benign at default (both levers
   absent); no crash class. Also noted in the renderer's class header.
10. **Multi-portal occlusion-query ledger (Lens-D NOTE):** for the 2nd+ portal in a frame,
   `testShouldRenderPortal` depth-tests against the CURRENT main-target depth — which the
   previous portal's nested full render replaced with DEST-world depth (blit-back restores
   color only, after the loop; nothing restores main depth between portals). Wrong show/hide
   decisions possible for portals 2+ (a falsely-culled portal shows the snapshot scene). The
   STAMP stays correct (tested against the untouched deferred snapshot depth). Inherited
   one-layer-era shape, faithful to the held IP source — NOT an implementation error — but the
   stencil family does not have it: **pre-registered A/B observable — two portals
   side-by-side, the second window shows the static snapshot scene.** IS2+ candidate: run the
   query draw against the deferred buffer's snapshot depth.

### §2.4 Round commands (IS1 proof rows)

```
Set-Location "C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is1-driver-paste"
# plain proof row (A/B vs the stencil renderer by dropping the lever):
.\gradlew.bat :fabric:runClient -PshaderpackViews=true
# sodium proof row (the DECISIVE shared-RSM/same-dim leg):
.\gradlew.bat :fabric:runClientSodium -PsodiumRuntime=true -PshaderpackViews=true
# iris shaders-OFF regression row (unchanged behavior expected armed = compat, unarmed = parity):
.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PshaderpackViews=true
```

Suite gate (orchestrator): the 8-leg suite runs sodium/iris-ABSENT and cannot exercise them;
default (no lever) is byte-identical committed behavior.

### §2.5 THE FABLE FOLD (three-lens verify round: F=PASS, D=PASS_WITH_CORRECTIONS,
G=PASS_WITH_CORRECTIONS — every BLOCKER/CORRECTION applied, ZERO refuted)

**Applied (code):**

1. **[D-BLOCKER] doRenderPortal layer-stack throw-safety** (`IrisCompatOn262Renderer`):
   `pushPortalLayer → try{renderPortalContent} finally{popPortalLayer}` — the S14.29 /
   `RendererUsingStencil:332-346` approved-hardening precedent (a single escaping throw from
   the nested full render() used to leave `isRendering()==TRUE` forever = session-permanent
   silent portal death). Companion: the deferred→main **blit-back moved into
   onBeforeHandRendering's finally** (structurally gated — the try opens only after the
   snapshot is taken), so a mid-loop throw restores the composited snapshot instead of
   leaving the last portal's raw dest render on the main target. The held IP source lacks
   both guards; the stencil renderer's landed hardening is the governing precedent.
2. **[D-CORRECTION] cache-coherent color-mask restore**: raw
   `GL11.glColorMask(true,true,true,true)` → `GlStateManager._colorMask(15)` (the S14.22
   idiom, `RendererUsingStencil:451`). The raw call desynced the per-draw-buffer COLOR_MASK
   cache: with the stamp no-oping (maxPortalLayer==0 / null mesh), cache=0 (the query
   pipeline's writeColor=false) vs actual=15 → the next mask-0 pipeline apply short-circuits
   and draws WITH color writes (visible aperture-mesh artifacts). The "colorMask is GONE"
   rationale held only for the never-loaded held shell — `_colorMask(int)` exists (api-map
   render-sub.md:168) and was already in use in-tree.
3. **[D-CORRECTION] pipeline-failure honest fallback**: new
   `IrisCompatPaste.arePipelinesReady()` + workhorse early-return BEFORE the snapshot/loop.
   Previously a static-init pipeline failure (credible: reflection register, JPMS/driver
   variance) made every copy no-op while the per-portal nested renders still clobbered the
   main target — live symptom = whole-screen dest world (NOT the promised "unchanged
   window"), a wrongly-discriminated corruption mode. Now: D7 loud log + "portals render
   nothing", and the static-init catch comment describes the real behavior.
4. **[G-CORRECTION] both-levers precedence DEFINED** — §2.3 row 9 + the renderer class
   header (probe rounds are renderer-lever-OFF only; both-levers = diagnostics-confounded,
   unsupported). No code change (verified benign at default).

**Refuted:** none — all four verified against the worktree code (the try/finally gap, the
GlStateManager cache mechanics vs the in-tree S14.22 precedent, the clobber path, the anchor
firing order at `MixinGameRenderer_IPPostLevelAnchor`).

**NOTE folds (comments/ledger only, no behavior change):**

- **[F] deliverable-1 phrasing + lockstep**: §2.3 row 1 reworded (duplicate chosen OVER the
  two listed sharing forms) + the sibling pair registered as an S20/gate-audit LOCKSTEP item.
- **[F] mid-packet MISMATCH frame parity**: the workhorse's entry guard is nullity-only —
  the S15 mismatch class (`player.level() != mc.level`) is intentionally uncovered, at
  parity with the F1/stencil family (comment at the guard). If the live round surfaces a
  mismatch-frame defect on the anchor, add the CrossPortalViewRendering:59-62 one-line skip.
- **[F] sky pre-fill lever scope**: deliberate divergence — nested inside
  `!debugSkipDestExtract` (decomposed path gates it standalone); debug-lever-only,
  self-retiring (commented in-code).
- **[F] clouds/weather suppression pre-try window**: capture-before-try shape accepted on
  the decomposed precedent (commented in-code; the pre-try calls are field reads).
- **[D] multi-portal occlusion query**: §2.3 row 10 (pre-registered A/B observable +
  in-code comment at `testShouldRenderPortal`; IS2+ candidate = query vs snapshot depth).
- **[D] far same-dim plain-row severity upgrade**: §2.3 row 8 (recompile storm / terrain
  holes possible, not just churn; IS2 candidate named — do NOT accept the ping-pong if
  confirmed).
- **[D] renderWorldNew unprotected push/pop**: the D23 fallback rides `renderWorldNew`'s
  unbracketed `pushRenderInfo/popRenderInfo` — pre-existing exposure shared with EVERY
  existing renderer, deliberately left (frozen decomposed entry; the NEW
  `renderWorldFullPipeline` hardened its own pair). Candidate one-liner for a later
  stage-wide sweep.
- **[G] sibling signature**: `doRenderHand` dropped in the sibling (dead in the original) —
  now listed in the PAIRING CONTRACT block so lockstep diffs don't flag it.
- **[G] asset/pipeline lifecycle**: verified inert at default (lazy compile; registration
  only via the armed-branch clinit; failure loud-not-crash; no "sodium" substring).
- **[G] IPGlobal flag**: mutable public static with no config/command wiring at IS1 — the
  JVM lever is the practical switch; runtime flips handled (per-call re-evaluation +
  instanceof eviction). Config wiring rides the later Q-U1 default-flip decision.
- **[D+G] verified-clean records**: sibling pairing line-for-line; exclusion list vs
  render() internals (no double drain/arm; sharedState-XOR-natural-anchor cull); anchor
  non-reentrancy; passingModelView aliasing; deferred-buffer lifecycle + teardown; GL-state
  discipline (stencil/depth-clamp uncached — grep-verified); committed-default inertness
  (seam assert: 8 files, zero decomposed-path deletions, mixin configs untouched); one-shot
  consumer pairing per §0.4-4; compile gate re-run green by Lens G.

**Compile gate after the fold**: `.\gradlew.bat :common:compileJava :fabric:compileJava
--console=plain` — green (see below).

### §2.6 THE ROW-1 AIOOBE (IS1 live defect; diagnose-first per the C2-3b pattern —
tasked as "§2.5" but that slot is the Fable fold; all code comments reference §2.6)

**The repro (2 runs, deterministic):** the 8-leg crossing gametest with the IS1 lever
(`-PshaderpackViews=true`), PLAIN install. `ArrayIndexOutOfBoundsException: Index 923 out of
bounds for length 27` — IDENTICAL index both runs — at `RenderSectionRegion.getSection(:64)
<- getBlockState(:40) <- SectionCompiler.compile(:76)` on a ForkJoin meshing worker
("Batching sections", `CompileTask.doTask:472`), surfaced on the Render thread via
`BlockableEventLoop` delayed-crash, in `the_nether` as MAIN at client ticks ~465-467 —
immediately after leg 4's overworld->nether promote. Lever-OFF suite green in the same tree.
Length 27 = the 3x3x3 region; index 923 => (compiled-section, region-origin) pairing
INCONSISTENT — an identity/coords bug, not content.

**The mechanism (SAME-DIM STALE-STATE RE-CONSUMPTION THROUGH THE UNGUARDED ImmPtlViewArea
WRAP):** the IS1 same-dim full-pipeline pass violates vanilla's
one-LRS/one-camera/one-consume-per-frame invariant. sharedState passes skip the extract
(`renderDestWorldFullPipeline` Step 5 is cross-dim-only) and run a real 8-arg
`destRenderer.render()` on the MAIN renderer with the dest camera. `render()` first
repositions the MAIN `ImmPtlViewArea` to the DEST camera section (LevelRenderer.render:169
-> ImmPtlViewArea.repositionCamera — swaps the coord-pinned preset), then its internal
`compileSections` (:255 -> :608-640) RE-consumes the SAME main-LRS
`sectionUpdateRenderStates` the main pass already consumed this frame (the list clears only
at the next extract head — LevelRenderState.reset:35 / LevelExtractor:161; vanilla
compileSections never removes entries). Each stale state's node is re-resolved at :625 via
`ImmPtlViewArea.getRenderSection(long)`, which — unlike vanilla
`RotatingSectionStorage.getValue` (:104-123: `containsSection` window guard +
`repositionCenter` re-noding => in-window occupant ALWAYS exact-match, else null) — did a
bare `positiveModulo` wrap into the current preset with NO window check and NO occupant-node
verification. Our RenderSections are coord-PINNED (`createColumn`), so an out-of-window
query returns a live section at congruent-mod-W DIFFERENT coords. Geometry lock: arena node
A=(-4,y,-4) queried against the leg-7 portal-C far-dest preset centered at section (87,y,87)
(dest 1400.5,250,1400.5; renderDistance 6 -> W=13; -4 ≡ 87 ≡ 9 mod 13 on both axes) returns
the pinned B=(87,y,87); `compileAsync` pairs region(A) with section(B); `doTask` reads B's
node at RUN time (SectionRenderDispatcher:432) and iterates B's origin against region(A):
index = 92 + 3 + 828 = **923 exactly**, every run. The bad task is CREATED on the last
overworld-main frame(s) of the leg-4 crossing (portal C renders same-dim every frame; the
crossing machinery dirties exactly one arena section that frame — one state, one task);
the promote then flips main to the nether and the worker throws within ms — hence
"the_nether as MAIN". Leg 7 passes because its 150 ticks are static (empty states list on
every portal-C frame). Lever-OFF is green because the decomposed path never calls
`render()`: same-dim never repositions and its drain is cross-dim-only after its own
reposition. Cross-dim IS1 passes are consistent (Step-5 extract resets+refills destLRS;
`render()` repositions to the dest camera before compiling). The
`seamlessportals$suppressFrameObsidian` redirect in the stack is pass-through (incidental).

**The fix (both halves landed together — the diagnostician's spec verbatim, one placement
refinement):**

1. **PRIMARY (pairing-rule, `SecondaryWorldRenderCore.renderDestWorldFullPipeline`):**
   sharedState passes only — immediately before the nested `destRenderer.render()`, copy
   `destLRS.sectionUpdateRenderStates` to a local ArrayList and clear the (final) list; in
   the outermost finally's sharedState branch, `addAll` the contents back. The nested
   render()'s compileSections sees an EMPTY list (no re-consumption, no re-resolution
   against the dest-repositioned preset), while the restore keeps the states available for
   their ONE legitimate consumer on any pass ordering (protects pre-main layer-0 callers —
   CrossPortalViewRendering/GUI-portal — from starving the main compileSections: the
   ow-holes-consumed-compile-queue class). Per-pass bracketing handles multiple same-dim
   portals per frame. Cross-dim passes are NOT bracketed (their Step-5 extract+render() is
   the correct one-shot pairing); §8-13 honored — render() still self-drains, it is only
   denied someone else's already-consumed one-shots. PLACEMENT REFINEMENT vs the spec's
   "next to the cloudColor suppression" option: the swap sits INSIDE the try (the spec's
   alternative "just before :1601"), because a stranded EMPTY states list on a pre-try
   throw would be silent compile loss (the holes class) — worse than the accepted
   cloudColor pre-try window; the finally only runs for an entered try.
2. **HARDENING (vanilla-parity occupant guard, `ImmPtlViewArea.getRenderSection(long)`):**
   after resolving a non-null result, `if (result.getSectionNode() != sectionNode) return
   null;` — transplants exactly the guarantee vanilla's containsSection+repositionCenter
   congruence provides (in-window => exact match; else null), for EVERY node-keyed consumer
   (compileSections re-resolution, SOG BFS, ...). Landed WITH the primary, never instead
   (VERIFY-LENS AMENDMENT: the original "alone it would NPE at compileSections:626"
   rationale was inaccurate for THIS codebase — `LevelRendererCompileSectionsMixin`
   already @Redirects the states field-get and pre-filters null-resolving states, so the
   hardening alone would have SILENTLY ABSORBED the mis-paired state, dropping the
   compile; that mixin is the THIRD protective layer. The primary fix is still required
   to restore the one-consume invariant rather than mask its violation).

**Ledgered (no code change now):**

- `getRenderSectionAt(BlockPos)` shares the wrap hazard for BlockPos-keyed callers — queued
  for the S20 audit (also noted in-code at the §2.6 hardening comment).
- The same-dim pass leaves the main ViewArea on the DEST preset until the NEXT main
  frame's `render()`:169 reposition — and (VERIFY-LENS CORRECTION: the original
  "only same-frame reader = playerCompiledSectionCallback" claim was WRONG) the next
  frame's EXTRACT runs BEFORE that reposition, where TWO readers can hit the dest-centered
  preset: (a) `LevelExtractorFlashBridgeMixin`'s applyFrustum flood (live in the <=30s
  promote-bridge window — i.e. right after every crossing): post-hardening its lookups
  return null → possible multi-frame main-terrain BLANK (lever-ON + same-dim portal
  rendered the previous frame + bridge window); pre-hardening it returned wrapped WRONG
  sections (the same corruption family as the AIOOBE) — the hardening made this strictly
  SAFER, but the window is real. (b) An off-thread SOG full-update overlapping the window
  builds an empty/boundary-seeded graph instead of a garbage one. DECISION DEFERRED to
  the re-run per the lens: if watch row 7 shows blanking, restore the main preset in the
  outermost finally's sharedState branch (weigh against the PortalContextSwitch:1191
  DISAPPEAR-FIX history first).

**Residual uncertainty (from the verdict, not load-bearing):** (1) the precise dirty-source
of the single arena state on the fatal frame is inferred (ANY dirty visible section on a
portal-C frame triggers the mechanism); (2) arena spawn sections (-4,\*,-4) derived from the
923 algebra, not read from a log; (3) candidates (d)/(e) cleared by the I10-exclusivity
header note + absent probe property, not an exhaustive walk. The discriminating probe (log
occupant-mismatch in getRenderSection + dump the states list at sharedState entry) is
recorded in the verdict should a pre-fix run ever be wanted.

**REGRESSION WATCH (the §2.6 rows):**

1. Leg 4 (the crash leg): AIOOBE gone across x2 lever-ON runs (determinism => one clean
   pair is strong evidence).
2. Leg 7 (same-dim far-dest, 150 ticks): still passes; portal C windows keep their ledgered
   IS1 same-dim observables (no entities, no vanilla terrain on the PLAIN row) — the fix
   removes only wrong-paired compiles, which never produced valid meshes.
3. The ow-holes class: break/place a block near a visible same-dim portal — the main world
   must still recompile that section the same frame (the swap-out+restore never starves the
   MAIN compileSections, incl. layer-0 CrossPortalViewRendering/GUI-portal orderings).
4. Cross-dim window freshness (legs 2/3/4 views): nether/overworld window content still
   updates on block changes — the cross-dim Step-5 extract + render() self-drain pairing
   untouched.
5. Lever-OFF full suite: decomposed path stays green. NOTE (lens): the decomposed path is
   code-untouched but NOT byte-identical in BEHAVIOR — the ImmPtlViewArea occupant guard
   is live lever-OFF too (all lever-OFF consumers audited null-tolerant; vanilla-parity
   says no visible change) — watch for any new holes/visibility regressions regardless.
6. The 12-point checklist's far-walk item (SOG delta feed adjacent but untouched): walk
   away from and back to a portal — no distant-chunk vanish recurrence.
7. (Lens CORRECTION row) The dest-preset residue window: post-crossing (the <=30s bridge
   window) with a same-dim portal visible and a static camera — watch for multi-frame
   MAIN-terrain blanking (the two next-frame extract-time readers of the dest-centered
   preset). Blanking observed => land the preset-restore in the sharedState finally.

**Compile gate after §2.6**: `.\gradlew.bat :common:compileJava :fabric:compileJava
--console=plain` — green.

### §2.7 THE SAME-DIM SODIUM SUPPLY (Fable verdict, 2026-07-20 — NOT A BROKEN LINK:
### a NON-DISCRIMINATING OBSERVATION; evidence fixed, chain untouched)

**The observation (self-run screenshot rounds, deterministic):** SODIUM row (lever ON) =
pixel-identical to the PLAIN row — same-dim windows sky-only after leg 7's 150-tick hold;
cross-dim window carries real nether terrain+fog+entities. The pre-registered expectation
(design §2.4-4) treated the same-dim row as DECISIVE for the Step-9'
`ip_driveDestTerrainSetup` drive. That expectation is VOID for this arena.

**MECHANISM (the verdict, statically walked end-to-end — every link engaged and sound):**

- Bracket engaged: the sibling full-pipeline shell carries the D1 sodium context swap
  (`MyGameRenderer.switchAndRenderTheWorldFullPipeline` — createNewContext +
  switchContextWithCurrentWorldRenderer swap-in after the repoint; symmetric swap-back in
  the finally; the PAIRING CONTRACT lists it in both SWAP-IN and RESTORE).
- Swap payload sound: `MixinSodiumRenderSectionManager.ip_swapContext` reference-swaps the
  renderLists/tree/frame/camera-cache family + content-swaps cullResults/ACTC;
  consume-before-swap; GLOBAL_PASS_SERIAL absorb-then-increment; the SodiumInterface
  five-swap + scheduleTerrainUpdate bracket marks needsGraphUpdate per swapped-in pass.
- The drive runs: Step 9' (`SecondaryWorldRenderCore` sharedState-only, BEFORE the nested
  8-arg render(), inside the bracket) calls the full 6-arg `SWR.setupTerrain` on the
  call-time-resolved current renderer. Sodium 0.9.1's setupTerrain has NO once-per-frame
  gate (bytecode: prepareFrame -> prepareRender -> prepareRenderTrees ->
  finalizeRenderLists end-to-end on every call), and finalizeRenderLists always publishes
  dest-camera renderLists (sync renderOutOfGraph frustum-flood fallback, or findBestTree
  over the content-swapped persistent cullResults with a blocking-consume retry).
- Consumption live: sodium's own LevelRendererMixin WrapOperation at prepareChunkRenders
  arms the ChunkSectionsToRender with this SWR + destCameraState.pos; the renderGroup
  calls execute inside render() = inside the D1 bracket, HEAD-cancelling into
  SWR.drawChunkLayer -> RSM.getRenderLists() read LIVE. ip_armDestChunkRenders is
  correctly excluded on this path (design §2.4-3).
- Culling/clip cannot blank it: terrain collection tests the UNHOOKED Direct viewport
  functions (C2-3b sync-only discipline); async trees are portal-agnostic supersets; the
  C2-2 in-shader clip uploads keep-all {0,0,0,1} under the DEF-G whole-pass disable.

**THE GEOMETRY VERDICT:** both same-dim dest cameras hover MID-AIR at y=250
(`CrossingSmoke` destA = (px+100.5, 250, planeZ); portal C dest = (1400.5, 250, 1400.5))
with client renderDistance 6. Sodium's collection/draw envelope = min(fog cullDistance,
renderDistance*16 ~= 96 blocks) (RSM getSearchDistance bytecode); the ground sits >=180
blocks below both cameras -> ZERO renderable sections in range -> the CORRECT, fully
converged dest view is sky+fog — pixel-identical to the plain row's by-design no-extract
floor. A working drive and a broken drive produce the SAME image there. Cross-dim "works"
in reverse: destB = (0.5, 129.5, 0.5) sits 2 blocks above the solid bedrock roof (terrain
in range), and its setupTerrain fires naturally at extract time (capture dropped
cross-dim -> sodium's LevelExtractorMixin cullTerrain anchor) on the dest dim's own SWR.

**THE FIX (evidence only — ZERO change to the sodium chain; no seam file touched):**

1. `CrossingSmoke` leg-7 block, lever-gated (`-Dseamlessportals.gametest.screenshots`,
   via `screenshotsLeverOn()` — default suite byte-identical): PORTAL D above portal A
   (origin (px+0.5, py+4.5, planeZ), spans y py+3..py+6 inside the cleared box; no plane
   overlap with A; clear of the item/pearl paths and of window B's screen region) with a
   same-dim ZERO-VERTICAL-OFFSET dest (px+100.5, py+4.5, planeZ). VERIFY-LENS GEOMETRY
   FIX (round-1 BLOCKER: D's window sits ABOVE the eye so all window rays point UP — the
   original floor pad was never hit, and the (+100,-3,0) dest embedded the through-portal
   camera inside the pad slab): the dest camera now sits at eye height (~py+1.62) in
   cleared air, and the terrain in the rays' path is a south-facing obsidian WALL across
   the transformed window frustum (x px+94..px+106, z pz-12..pz-11, y py+3..py+13 —
   bottom/top rays land at ~py+4.6..~py+11.2 on z=pz-12), ray corridor air-cleared
   (x px+94..106, z pz-10..pz+2, y py..py+13), inside the already-forceloaded dest area
   and ~12 blocks into the ~96-block envelope. Second screenshot
   `is1-leg7-samedim-ground-dest-converged` at the 150-tick hold.
2. The positive-half probe (diagnose-first): `SecondaryWorldRenderCore
   .logSameDimSupplyProbe` — immediately after the Step-9' drive, while the D1 context is
   still installed, log `SodiumWorldRenderer.getVisibleChunkCount()` (reflection-only:
   `sodium$getWorldRenderer` -> `getVisibleChunkCount`, both public on 0.9.1; disarms on
   any failure). Same lever; the 1Hz throttle is keyed PER PASS IDENTITY
   (dim:layer:portal-UUID — VERIFY-LENS CORRECTION: a global gate is deterministically
   claimed by portal A whose correct count is 0, starving the discriminating portal-D
   line forever). Render-thread-logging discipline I5 preserved.

**EXPECTED VISUALS (the fixed rows):**

- SODIUM row, portal D: real dest-camera terrain (the obsidian WALL filling the window's
  upper region). Pass 1 may be the renderOutOfGraph frustum-flood (no occlusion) or
  briefly blank while fresh dest chunks mesh; occlusion-tree lists within ~2-3 passes;
  150-tick hold = converged. Probe: portal D's pass line shows
  visibleSectionsAfterDrive > 0 (A/C legitimately log 0 — empty envelope).
- PLAIN row, portal D: stays SKY-ONLY (same-dim runs no extract — the pre-registered
  floor). THIS pair (sodium terrain vs plain sky in the same window) is the real decisive
  discriminator the design's SD-ROW wanted.
- The two y=250 windows legitimately remain sky-only on EVERY row — correct content, not
  a defect. Same-dim windows still draw NO entities and NO clouds/weather (pre-registered
  IS1 observables, unchanged).
- Ledgered: scaled portals (scale>2) may show a projection mismatch in nested-arm terrain
  (sodium's GameRendererMixin captures the MAIN projection; the nested arm cannot receive
  destDrawProjection — identical at scale 1). Untested until a scale>2 same-dim row
  exists.

**RESIDUAL UNCERTAINTY:** (i) the drive's positive delivery is proven statically, not yet
live — one sodium-row run with the fixed evidence settles it (pad in D's window and/or
nonzero probe line); (ii) if the consistent-settings seed held terrain >= y~154 within 96
blocks of either y=250 dest, the verdict flips back to a chain defect — the same probe
discriminates (typical spawn gen makes this unlikely; no terrain fragment in any
screenshot); (iii) the scale>2 nested-arm projection question (ledgered above); (iv) the
FlawlessFrames n=1 cold-arm interplay is asserted from the C2-1c ledger, not re-walked
(benign either way — it only forces the sync path harder).

**REGRESSION WATCH (the §2.7 rows):**

1. C2 same-dim water/glass OUTER-WORLD INTEGRITY: main-world visibility/translucency
   uncorrupted after same-dim full-pipeline passes — guarded by the untouched symmetric
   swap-back, consume-before-swap + GLOBAL_PASS_SERIAL, the SWR five-swap, and the UBM
   latch reset (`ip_onDestTerrainDrawsFinished`); all four stayed byte-untouched in this
   fix (verified: no seam file in the diff).
2. The §2.6 rows stay green: leg 4 x2 lever-ON (no AIOOBE), leg 7 held 150 ticks, the
   ow-holes block-break row, cross-dim freshness (legs 2/3/4), lever-OFF full suite,
   far-walk item, watch-row-7 dest-preset-residue blanking.
3. Cross-dim unaffected: the nether window keeps real terrain+fog+entities on both rows —
   portal D does not sit in window B's screen region (D is stacked above A).
4. Lever-OFF byte-identical: portal D + pad + second screenshot + probe are all gated on
   the same screenshots lever; the probe additionally self-disarms sodium-absent.
5. "Resizing Sodium terrain uniforms" spam absent (the C2-1d endFrame-leak signal — D's
   draws ride the same shared-SWR UBM).
6. With the ground-level row live: first-pass envelope may exceed the converged set
   (frustum-flood, acceptable <=3 passes); on the PLAIN row watch the §2.3 row-8 far
   same-dim recompile-storm class (sodium row immune via IgnoringViewArea).

**Compile gate after §2.7**: `.\gradlew.bat :common:compileJava :fabric:compileJava
--console=plain` — green.

## §3 IS2 OPENING EVIDENCE (2026-07-20, self-run — THE DELIVERABLE RENDERS)

The iris+pack row (ComplementaryReimagined_r5.8.1 staged, `-PirisRuntime=true
-PshaderpackViews=true -PgametestScreenshots=true`, the merged IS1 tree, ZERO IS2-specific
code): **ALL 8 LEGS PASS with the compat renderer LIVE under the active pack**; zero
AIOOBE; the supply probe delivers identically under iris (portal D 35→42, A/C
legitimately 0); no notable errors. The converged screenshot
(`0001_is1-leg7-samedim-ground-dest-converged.png`, iris row): main world fully
pack-rendered (volumetric clouds, shader lighting); portal D's obsidian wall THROUGH the
window WITH pack shading; the two y=250 windows showing the pack's cloud layer from
above (real dest-camera full-pipeline renders — the mid-air windows are now visibly
meaningful under the pack); the cross-dim nether window under the pack's fog treatment;
stamps portal-shaped; main terrain intact. The engagement's core deliverable — portal
views rendering WITH an active shaderpack — is live on the harness evidence.
REMAINING FOR IS2 CLOSE: the experiential legs only a human can judge (pack-toggle
mid-session at the switch frame, D21 temporal/flicker envelope, walk-through feel,
frame-edge nose-to-frame, resize) + notice v2 wording (tracks what the user sees) +
the OQ7 one-shot pipeline log + the D8-default decision points (Q-U1 rides IS3 per the
design).

### §3.1 THE USER ROUND (2026-07-20, iris+sodium, levered session)

PASS (user-confirmed): views under the pack (1) / pack-toggle no-crash (2) / walk-through
(3) / no flicker observed (4) / block-break recompile (5, partial) / relog (6). DEFECTS
routed to the is2-live-defects diagnose round (wf_ad533d56-9f9): **(F)** shaders-OFF +
lever: NO dest-dim fog in the nether-from-OW window (clear view; fog normal after
teleporting; the decomposed path had this PROVEN at C2 §3.7 — the lever routes shaders-OFF
to the full-pipeline renderer too, so the suspect is renderDestWorldFullPipeline's fog
supply vs the decomposed Step-6); **(O)** the targeted-block outline MISSING shaders on AND
off (levered; default unknown); **(G)** creative inventory MANGLED — vanilla tabs empty
slots + blank tab icons, the IP tab INTACT, shaders-independent (levered; default/plain/
pre-IS unknown; the static-init suspect: IrisCompatPaste's reflective RenderPipelines
.register at class load). The user's observations outrank every screenshot reading.

### §3.2 THE IS2 FIX BATCH (wf_ad533d56-9f9 synthesis verdict, implemented 2026-07-20)

**FIX-F (defect F — no dest-dim fog in cross-dim windows, shaders-OFF + lever).**
Two-part, both in `renderDestWorldFullPipeline`:

1. **THE HOIST**: the whole Step-6 fog family (FIX-6 rain bracket, `fr.setupFog(newCamera,
   ...)`, `destCameraState.fogData`/`fogType=NONE`, the `setCurrentRenderedFogColor`
   publish, `writeFogSlice`) moved from after the Step-5 extract to BEFORE it (top of the
   try). Verified root cause: the IS1 body INVERTED vanilla's order — 26.2
   `GameRenderer.extract` runs `extractCamera` (setupFog → `cameraState.fogData`,
   GameRenderer:631-640) BEFORE `levelExtractor.extract` (:389), and sodium's `cullTerrain`
   runs INSIDE the extract reading fog through the FogRendererMixin duck — so the nested
   pass served it SOURCE-poisoned fog. Step 9' keeps its slot; the finally's §8-3(c) SOURCE
   setupFog re-run is unchanged; `LevelExtractor.extract` never writes `fogData`
   (ref-verified), so the hoisted values survive the extract.
2. **THE BELT**: the Step-5 extract is bracketed with `SodiumFogOverride.activate(
   destFogData)` / `clear()` (clear FIRST in the existing SOG-feed finally — paired,
   throw-safe). The P8-proven `SodiumFogOverrideMixin` HEAD-cancel on
   `sodium$getFogParameters` serves the dest fog to `cullTerrain` deterministically;
   reflection-built `FogParameters`, sodium-absent no-op.

**THE DUCK-ORDERING DEPENDENCY (new, load-bearing):** sodium resolves fog AT CULL TIME
inside the extract via the merged `sodium$getFogParameters` duck — any future re-ordering
of the full-pipeline body MUST keep the dest fog computed (and the override armed) before
the Step-5 extract. Corollary: **`SodiumFogOverride` + `SodiumFogOverrideMixin` are now
FLAG-ON LOAD-BEARING** (no longer block-era-only) — the S20 deletion inventory and the
§0.2 gate-audit entries are rewritten accordingly (`migration/S20_HANDOFF.md`); they must
NOT be deleted at the sweep.

**FIX-O (defect O — targeted-block outline missing).** The nested render()'s
`renderOutline` arg: literal `false` → `destRenderOutline = !sharedState &&
seamlessportals$invokeShouldRenderBlockOutline()` (CROSS-DIM ONLY). See the §1-E row
revision above: the Fabric-NPE half of the old blanket-FALSE rationale was decomposed-era
reasoning (per-instance context, jar-verified); IP delivered dest outlines through its
compat renderer. SAME-DIM MUST STAY FALSE (destLRS==mainLRS re-submits the main outline at
the dest transform). The decomposed path + the D23 fallback are untouched.

**UNCONDITIONAL HARDENING (separable change-set): the clip uniform-location cache.**
`GlCommandEncoderClipMixin`'s static programId→location cache moved to
`ClipUniformLocationCache` (render pkg) and is invalidated by the new
`GlDeviceClipCacheMixin` at `GlDevice.clearPipelineCache` RETURN (mc262 GlDevice:261,
public; callers = ShaderManager:152/:162 on every resource reload — F3+T/pack apply — and
GlDevice.close). Rationale: `clearPipelineCache` glDeleteProgram's every cached program;
recycled ids then serve STALE locations to `glUniform4f` against the new current program —
the leading suspect for the shaders-ON GL_INVALID_OPERATION spam. Seam choice evidence:
the spec's `glIsProgram`-on-hit fallback is a NO-OP for this cache's shape (the looked-up
id is `GL_CURRENT_PROGRAM`, always a live program; the hazard is id REUSE, undetectable by
glIsProgram), so the clean vanilla seam was taken; iris 1.11.2 deletes its own programs
via its own glDeleteProgram (never `clearPipelineCache` — jar-verified) but binds them
outside the vanilla trySetup path this cache serves; a 512-entry cap bounds growth under
any residual churn. Ledgered residual: non-trySetup program-id churn.

**THE EM EVIDENCE LEGS (CrossingSmoke; defects O + G — NO fix for G, evidence rows only).**
All under the screenshots lever, all fail-soft (maybeScreenshot never-throw discipline):
EM-O-A (`em-o-a-ordinary-target-outline`: TestInput.lookAt at a platform block, no portal
on the ray), EM-O-B (`em-o-b-window-target-outline`: repositioned before portal B, aimed
through the window-bottom onto the nether bedrock roof — ~3.3-block total ray, in pick
range through the portal), EM-G creative-browse checkpoint invoked TWICE
(`em-g-pre-*` BEFORE any portal spawns / `em-g-post-*` after the leg-7 views): setScreen
the creative inventory (the vanilla InventoryScreen:43-45 recipe), screenshot the default
tab, cursor-click three vanilla tabs + the IP tab at vanilla's own tab geometry
(reflective selectTab fallback for pagination/click misses), screenshot each
(`em-g-<phase>-tab-<name>`), close. The pre/post pair discriminates whether the G mangle
needs portal machinery to have run or is static-init-only (the IrisCompatPaste suspect).

**§3.2-VERIFY: 6 Opus adversarial line-by-line verifiers + 3 Opus judges + a
majority-bound fold (the Fable-unavailable depth protocol, memory
`opus-verifier-depth-protocol`).** All 6 verifiers PASS, all 3 judges CLEAN. Applied (all
judge-majority-backed): V4-1 (GlDeviceClipCacheMixin → string `targets=` form, decoupling
its build from the unrelated S14.21 GlDevice access-widener line), V2-1 (belt comment
rewrite — the hoist and the belt are INDEPENDENT and idempotent on 0.9.1, neither may be
deleted believing the other covers it), V6-1 (`em-g` baseline shot renamed `-tab-initial`:
`selectedTab` is static + re-selected at init, so the post-shot captures the leftover
pre-phase tab), V5-1 (confirmed the always-on clip-cache clear is corrective not a
regression — render-thread-only, no mid-frame race; NOT gating it behind the lever, which
would reinstate the stale-id bug). Refuted with evidence (6, each majority NOT_REAL; the
lone-REAL votes were all jB, the interaction judge running hot — the majority overrode
correctly): the §8-3(c) doc nit, the cache javadoc tone, the EM pick-range comment values,
the off-page pagination-tab click, the phantom cross-dim outline (byte-identical to the
decomposed path = IP-faithful, FIX-O's goal is parity), and the cross-slice JX hunt (all
verified safe: the FIX-O three-slice coupling, the writeFogSlice per-call distinct
GpuBuffer, the integrated two-pass trace).

**RESIDUAL V2-2 (judges 3/3 NEEDS_EVIDENCE — NOT an IS2 blocker; ledgered, do not
guess-fix):** `SodiumFogOverride` holds a single non-re-entrant static `current` with a
flat-null `clear()` (no save/restore stack). **The IS2 FIX-F belt is IMMUNE** — it brackets
ONLY `destExtractor.extract` (builds CPU render-state, never recurses into a portal render),
so this batch adds NO new hazard (verified by all three judges). The genuinely-unresolved
risk is PRE-EXISTING and OUT-OF-SURFACE: the block-era `PortalContextSwitch` draw belt
(activate ~1858 / clear ~1989) brackets the DRAW — IF that stencil-direct draw can recurse
into a nested portal that also activate/clears the shared holder, the nested `clear()` would
null the parent's override mid-draw. That belt is flag-OFF-only and S20-doomed, and never
co-active with the flag-ON belt (different sessions). DISPOSITION: it most likely DIES at the
S20 block-era sweep untouched; IF a pre-S20 flag-OFF fog defect ever surfaces there, the fix
is to convert `SodiumFogOverride` to a save/restore stack (activate returns the prior value;
clear restores it) — gated by a trace first confirming the decomposed draw actually recurses.
Recorded so the S20 sweep does not delete it blind.

**§3.3 THE EM SELF-RUN EVIDENCE (2026-07-20, harness screenshots) — F fixed, O explained,
G not-ours + not-harness-reproducible.**
- **DEFECT F (fog):** FIXED. Shaders-OFF sodium+lever cross-dim window now shows the nether
  FOG GRADIENT (`shots_A_sodium_lever/*em-o-b*`); pre-fix it was the user's "clear, no fog".
- **DEFECT O (outline):** FIX-O delivers the through-window outline (faint at white-on-grey
  contrast, present shaders-OFF). The ORDINARY-block outline is missing under iris+shaders
  **identically with and without our lever** (`shots_B_iris_default` == `shots_C_iris_lever`
  EM-O-A) — i.e. iris's own faint outline rendering under a pack, NOT an IS regression. Our
  renderer is exonerated for O; FIX-O closes the one part that WAS ours (dest outlines).
- **DEFECT G (creative inventory mangle):** OUR CODE EXONERATED + NOT HARNESS-REPRODUCIBLE.
  The creative screen (vanilla tabs + IP tab, per-tab shots, pre AND post portal) is INTACT
  across FOUR configs: sodium+lever (`shots_A`), iris+pack default/no-lever (`shots_B`),
  iris+pack+lever (`shots_C`), and iris+pack+lever+SHADER-TOGGLE-x2/11-pipeline-destroys
  (`shots_D`, the EM-G-R3 toggle leg). None mangle. Since even the pipeline-reload trigger
  (the leading atlas-poison suspect) is clean, the user's mangle depends on real-play
  environment state the gametest harness does not replicate — the strongest candidates are
  GUI-scale change / window-resize / fullscreen-toggle (each rebuilds/invalidates the
  GuiItemAtlas — the exact one-time-poison-window mechanism the G diagnosis favored), none of
  which the fixed-854x480 harness exercises. LIKELY a pre-existing iris/sodium GUI-atlas
  interaction independent of Seamless Portals. NEXT = user localization (§3.4), not a
  guessed fix (NO GUESSING).

**§3.4 USER LOCALIZATION QUESTIONS for defect G** (each a ~30s action in the user's own
mangled session; they three-way-split what the harness could not):
1. Were HOTBAR item icons ever blank, or only the creative screen? (atlas-wide vs creative-only)
2. Hover an "empty" creative slot — does a TOOLTIP appear? (content exists = render/atlas bug;
   no tooltip = content-generation bug)
3. Change GUI Scale in Options, reopen creative: HEALS (→ one-time past poison event) /
   stays blank (→ active per-frame poison) / unchanged (→ atlas anatomy wrong).
4. Was it mangled from the FIRST creative-open of the session, or only AFTER an event
   (first portal view / first shader toggle / a window resize / fullscreen)?
5. Does F3+T (reload resources) heal it?

**§3.5 THE SECOND USER ROUND (2026-07-20, iris+sodium+lever, main-tree 304420d — the
post-fix hands-on):** F FOG **FIXED (user-confirmed)**; O OUTLINE **works (user-confirmed)**;
G CREATIVE INVENTORY **GOOD this session (user-confirmed)** — so defect G was
environment/session-specific and is NOT present on the fixed build; G is effectively CLOSED
(the §3.4 questions stand only if it ever recurs). The one remaining observation, USER-ROUTED
TO IS3: **"if terrain is close to the portal on the dest side, and I move around on the
source side, the camera clips into terrain."** = the MISSING FRONT-CLIPPING — the
full-pipeline renderer's DEF-G belt (`FrontClipping.disableClipping()` at
`renderDestWorldFullPipeline` ~:1629, replacing the decomposed path's `setupInnerClipping`
arm at ~:1098) leaves dest geometry on the camera side of the portal plane UN-clipped, so a
dest camera swung near dest terrain shows into it. IS3 is exactly the fix: retire the belt,
arm `setupInnerClipping` for the full-pipeline pass, and land the TransformPatcher injection
so shaders-ON iris-patched terrain programs write `gl_ClipDistance` (arming clip-distance
globally is only safe once the programs write it — the belt existed to avoid the
UNDEFINED-non-writing-program hazard). User directive: execute IS3 + IS4 with the deep-Opus
protocol ([[opus-verifier-depth-protocol]]).


## §4 THE IS3 IMPLEMENTATION SPEC

*Opus reconcile of the four IS3 recon reports (rBelt / rPatcher / rBinder / rInteract),
standing in for Fable at full depth. Every load-bearing claim below was re-derived from
source in the main READ-ONLY tree, the iris 1.11.2+26.2 jar (javap), and mc262-ref — NOT
inherited from the recon reports or the IS0 census. Where the reports disagreed (the mixin
naming/gate, the GLSL splice anchor), the disagreement is resolved here against source.*

### §4.0 THE HAZARD VERDICT — non-terrain iris programs (RESOLVED: option (b))

**Decision: extend the vanilla `GlCommandEncoderClipMixin` (the per-draw `trySetup` uploader)
with the same per-draw definedness guard the sodium uploader already carries.** The belt swap
(§4.1) MUST NOT ship without this — landing them together is the single most important
implementation requirement of IS3.

**The hazard, re-derived from source.** The full-pipeline arm (§4.1) drives a GLOBAL raw
`GL11.glEnable(GL30.GL_CLIP_DISTANCE0)` — verified: `qouteall...FrontClipping.setupInnerClipping`
-> `feedViewSpacePlane` (FrontClipping.java:166-177) -> `com.warwa...FrontClipping.restore(Snapshot
enabled=true)` (com.warwa FrontClipping.java:85-89) -> `enableGlClipDistance()` = a bare
`GL11.glEnable(GL30.GL_CLIP_DISTANCE0)` guarded by the private `glClipEnabled` bool
(com.warwa FrontClipping.java:245-250). This is a global GL capability that stays ON for the
entire nested `render()`. Per the GL clip-distance rule (OpenGL 4.6 core; GLSL 4.60 section 7.1):
if `GL_CLIP_DISTANCE0` is enabled and the bound vertex program does not write
`gl_ClipDistance[0]`, the value is UNDEFINED — the primitive may be arbitrarily clipped or
culled (NVIDIA tends to read 0/benign; AMD/Intel are free to cull).

**Why non-terrain iris programs do not write it (jar-verified).** IS3 injects only into
`params.patch == Patch.SODIUM` VERTEX (§4.2). javap of the iris jar: `Patch` = {VANILLA,
DH_TERRAIN, DH_GENERIC, SODIUM, COMPOSITE, COMPUTE} — **there is no ENTITY patch type.** Under
an active pack iris routes terrain through `patchSodium` (Patch.SODIUM) and
entities/sky/particles/clouds/weather/block-entities/hand through `patchVanilla`
(Patch.VANILLA), composites through Patch.COMPOSITE. So every non-terrain iris program writes
NO `gl_ClipDistance`. The vanilla source seam (`ShaderCodeTransformation` via
`ShaderManagerCompilationCacheMixin`) does NOT reach iris pack programs — iris compiles them
itself and substitutes via `GlDevice.getOrCompilePipeline`, bypassing the vanilla
`ShaderManager` source path. So under a pack the ONLY programs that write `gl_ClipDistance`
are the IS3-injected Patch.SODIUM terrain programs.

**Why the existing sodium guard does NOT cover the gap (the crux the reports split on —
resolved against source).** `MixinSodiumGLDrawContext_ClipUpload` carries a real definedness
guard: at `GLDrawContext.setContext` RETURN, `loc == -1 && FrontClipping.capture().enabled` ->
`GL11.glDisable(GL30.GL_CLIP_DISTANCE0)`, restored at `endDraw`
(MixinSodiumGLDrawContext_ClipUpload.java:141-160). BUT `GLDrawContext` is **sodium's own draw
context** — it is bound only for sodium TERRAIN passes. Non-terrain iris draws
(entities/sky/particles) are ordinary Mojang draws that funnel through
`com.mojang.blaze3d.opengl.GlCommandEncoder.trySetup`, NOT sodium's `GLDrawContext.setContext`.
The sodium guard never sees them. And the vanilla `GlCommandEncoderClipMixin` — the mixin that
DOES cover those draws — has NO guard today: line 56 is `if (loc < 0) return;` (verified,
GlCommandEncoderClipMixin.java:56), a bare early-out. Its own comment (lines 64-69) states
"GL_CLIP_DISTANCE0 enable/disable is managed by FrontClipping ... No per-draw enable needed" —
correct in the shaders-OFF world (every vanilla world shader is injected via the source seam ->
`loc >= 0`), but FALSE the moment a global clip is armed over un-injected iris Patch.VANILLA
programs.

**Options weighed.** (a) inject into ALL iris patch types — REJECTED: COMPOSITE/COMPUTE are
fullscreen/deferred passes with no world model-view (a view-space clip is meaningless and can
corrupt them); DH/shadow passes use a different projection (clipping cuts the wrong plane);
and blanket-injecting Patch.VANILLA entity programs would bisect straddling dest entities (the
exact defect `setupInnerClippingForEntities`' margin exists to avoid). (c) accept the
undefined behavior — REJECTED: the GL spec makes it undefined, not benign; non-NVIDIA drivers
can cull the sky/entities. (b) scope the enable per-program at the universal `trySetup`
chokepoint — ADOPTED: mirror the sodium guard in the vanilla mixin. It fixes the user's
terrain-clip symptom (terrain has `loc >= 0` -> clips), leaves non-terrain iris draws
DEFINED-and-unclipped (`loc == -1` -> suppress), and is bit-identical for the proven
shaders-OFF decomposed path (all vanilla world shaders are injected -> `loc >= 0` -> the guard's
suppress branch never fires; behavior unchanged).

**Mandatory keying detail (from source).** The vanilla guard MUST key its enable/disable
decision on `FrontClipping.capture().enabled` — the store's armed INTENT — NOT on the private
`glClipEnabled` cache, exactly as the sodium mixin does (MixinSodiumGLDrawContext_ClipUpload
.java:111,141,156). Reason: `com.warwa...FrontClipping.enableGlClipDistance` early-returns when
`glClipEnabled` is already true (com.warwa FrontClipping.java:245-250), so a per-draw
`glDisable` that goes behind the store's back would leave the store's cache reading "enabled"
while GL is off, and the store would then decline to re-enable -> a suppressed draw's disable
could stick across the next terrain draw. Keying on `capture().enabled` makes each `trySetup`
re-decide from the true intent: `loc >= 0` -> `glEnable`; `loc < 0 && capture().enabled` ->
`glDisable`. Because `trySetup` fires per-draw, no latch/restore is needed on the vanilla side
(the sodium side needs its `endDraw` restore because `setContext` fires once per pass, not per
draw).

### §4.1 THE BELT SWAP — `renderDestWorldFullPipeline`

**Site.** `SecondaryWorldRenderCore.java`, the DEF-G pre-render belt immediately before
`destRenderer.render(...)`. Current:

```java
GL11.glDisable(GL11.GL_STENCIL_TEST);
FrontClipping.disableClipping();          // <-- the DEF-G clip belt, retired at IS3
```

**Delta — replace ONLY the `disableClipping()` line with the decomposed path's arm
(byte-for-byte the decomposed arm at ~:1098-1101; verified identical there):**

```java
GL11.glDisable(GL11.GL_STENCIL_TEST);     // KEEP — the compat shape is stencil-free (section 2.1-3)
FrontClipping.setupInnerClipping(
    PortalRendering.isRendering() ? PortalRendering.getActiveClippingPlane() : null,
    destViewMatrix, -FrontClipping.ADJUSTMENT
);
```

All symbols are in scope in this method: `FrontClipping` = `qouteall.imm_ptl.core.render
.FrontClipping` (same package, no import), `PortalRendering` (imported line 65), `destViewMatrix`
(the matrix passed to `destRenderer.render` a few lines below), `FrontClipping.ADJUSTMENT` =
`0.01` (qouteall FrontClipping.java:95). The signature matches: `setupInnerClipping(Plane,
Matrix4f, double)` (qouteall FrontClipping.java:132) — `getActiveClippingPlane()` returns
`@Nullable Plane` (PortalRendering.java:187), `destViewMatrix` is `Matrix4f`, `-ADJUSTMENT` is
`double`. So `-ADJUSTMENT = -0.01`.

**Why the `isRendering()` guard is mandatory (verified).** `getActiveClippingPlane()` peeks the
portal-layer stack and throws at layer-0 callers (its javadoc: "Must use after checking
isRendering()", PortalRendering.java:87; the S18.2 BLOCKER fix at the decomposed site,
SecondaryWorldRenderCore ~:1089-1097). The full-pipeline pass always runs inside
`doRenderPortal`'s `pushPortalLayer` bracket (so `isRendering()==true` in practice — the D23
layer-0 fallback routes CrossPortalViewRendering/GuiPortalRendering to the decomposed
`renderWorldNew`, never here), but the guard is kept for symmetry and safety:
`setupInnerClipping(null, ...)` collapses to `disableClipping()` (qouteall FrontClipping.java:
149-152), so a layer-0 full-frame render stays unclipped, exactly like the decomposed path.

**The finally needs NO change.** The outermost finally already re-asserts `GL11.glDisable(
GL11.GL_STENCIL_TEST); FrontClipping.disableClipping();` (the section 2.1-3 stencil-neutralize +
clip disarm). Verified: it disarms the clip after `render()` on any path or throw — correct
post-pass cleanup, directly mirroring the decomposed finally at ~:1197. Leave it.

**Ordering (verified).** The belt sits AFTER the section 2.7 same-dim supply probe
(`logSameDimSupplyProbe`) and the section 2.6 `sectionUpdateRenderStates` swap-out, BEFORE
`destRenderer.render(...)`. Arming clip is pure GL state; section 2.6/2.7 are CPU
LevelRenderState — no interaction. The finally restores clip after `render()` regardless of
throw. Correct.

### §4.2 THE TRANSFORMPATCHER MIXIN — shaders-ON GLSL clip injector (javap-exact)

Registers the FIRST live iris-targeting `@Mixin` (D9 amendment, section 4.6).

**Target (javap-confirmed).** `net.irisshaders.iris.pipeline.transform.TransformPatcher` — a
`public class` (not interface). Method:
`private static java.util.Map<PatchShaderType,String> transformInternal(String, Map<PatchShaderType,String>, Parameters)`;
JVM descriptor `(Ljava/lang/String;Ljava/util/Map;Lnet/irisshaders/iris/pipeline/transform/parameter/Parameters;)Ljava/util/Map;`.
Body (bytecode offsets 0-16): `params.name = arg0; return (Map) transformer.transform(arg1, params);`
— arg1 is the INPUT source map, the RETURN is the TRANSFORMED map. `Parameters` is a
`public abstract class` with `public final Patch patch`, `public PatchShaderType type`,
`public String name` (all readable directly). `Patch.SODIUM` and `PatchShaderType.VERTEX` are
public static final enum constants. iris is `compileOnly` on the common classpath, so the
mixin imports these directly (no reflection).

**Caching behavior (javap-confirmed — better than per-frame).** The public 6-arg `transform(...)`
wrapper builds a `CacheKey`, and on `cache.containsKey(key)` returns the cached map WITHOUT
calling `transformInternal` (bytecode: containsKey short-circuit); on a miss it calls
`transformInternal` then `cache.put(key, result)` (offsets 170-190). So our `@Inject` at
`transformInternal` RETURN fires ONCE per unique (params+sources) tuple — never per-frame,
never per-draw — and the mutated map is what gets cached. Zero steady-state cost, no
double-patch.

**Mixin shape.**
```java
@Pseudo
@Mixin(value = net.irisshaders.iris.pipeline.transform.TransformPatcher.class, remap = false)
public abstract class MixinIrisSodiumTransformPatcher_ClipInject {
    @Inject(method = "transformInternal", at = @At("RETURN"), remap = false, require = 1)
    private static void seamlessportals$injectSodiumTerrainClip(
        String name,
        java.util.Map<PatchShaderType, String> inputSources,
        Parameters params,
        CallbackInfoReturnable<java.util.Map<PatchShaderType, String>> cir
    ) {
        if (params.patch != Patch.SODIUM) return;              // terrain only
        java.util.Map<PatchShaderType, String> out = cir.getReturnValue();
        if (out == null) return;
        String vsh = out.get(PatchShaderType.VERTEX);
        if (vsh == null) return;
        String patched = seamlessportals$spliceClip(vsh);
        if (patched != vsh) {
            // Defensive: do NOT assume the returned map is mutable. Copy + setReturnValue.
            java.util.EnumMap<PatchShaderType, String> copy = new java.util.EnumMap<>(out);
            copy.put(PatchShaderType.VERTEX, patched);
            cir.setReturnValue(copy);
        }
    }
}
```

Handler is `static` (target is `private static`; a static @Inject captures the three target
args ahead of the CIR). The `EnumMap` copy + `setReturnValue` avoids the "is the return mutable?"
assumption (bytecode does not prove the `EnumASTTransformer.transform` result is an in-place
mutable map — one alloc per cache-MISS is free and zero-risk; do not rely on in-place `.put`).

**The GLSL splice — the getVertexPosition anchor gate + core-profile fail-safe (LOAD-BEARING
CORRECTION, jar-verified; the IS0 census expression is INCOMPLETE).** The IS0 section 1-D
expression `dot((u_ModelViewMatrix * getVertexPosition()).xyz, seamlessportals_ClipPlane.xyz) +
seamlessportals_ClipPlane.w` compiles ONLY on the compatibility-profile path. Verified from the
jar: `getVertexPosition` appears as a defined symbol only in `SodiumTransformer` (the
compat-profile transformer), `DHTerrainTransformer`, `DHGenericTransformer` — NOT in
`SodiumCoreTransformer`. Both `SodiumTransformer` and `SodiumCoreTransformer` inject
`u_ModelViewMatrix`. So:

- **Compat-profile packs** (dispatched to `SodiumTransformer`): inject
  `vec4 getVertexPosition(){ return vec4(_vert_position + u_RegionOffset +
  _get_draw_translation(_draw_id), 1.0); }` — bytecode-equal to `vec4(position,1.0)` from
  sodium's own `block_layer_opaque.vsh` (`position = _vert_position + translation`,
  `translation = u_RegionOffset + _get_draw_translation`). So
  `u_ModelViewMatrix * getVertexPosition()` = eye-space position, mathematically identical to
  the proven shaders-OFF sodium seam `u_ModelViewMatrix * vec4(position,1.0)`
  (SodiumClipShaderPatch.java:202-205). The census expression is sign-exact HERE.
- **Core-profile packs** (dispatched to `SodiumCoreTransformer`): NO `getVertexPosition()`
  defined -> the census expression fails to compile -> would blank dest terrain. This path
  becomes a DOCUMENTED RESIDUAL (unclipped-but-defined; parallels the D4 Vulkan residual).

`seamlessportals$spliceClip(String source)` therefore:
1. `if (source.contains(ShaderCodeTransformation.UNIFORM_NAME)) return source;` — idempotence
   (`UNIFORM_NAME == "seamlessportals_ClipPlane"`, verified ShaderCodeTransformation.java:41).
   Shared literal with the shaders-OFF sodium seam (belt-and-suspenders; the two seams operate
   on different source strings and are mutually exclusive per program, so cross-seam
   double-patch cannot actually occur).
2. `if (!source.contains("u_ModelViewMatrix")) return failSafe(source, "no u_ModelViewMatrix");`
   — present on both sodium paths.
3. `if (!source.contains("getVertexPosition(")) return failSafe(source, "core-profile: no
   getVertexPosition — unclipped-but-defined residual");` — the core-path gate. Route to the
   fail-safe (unchanged source + one-shot WARN), NOT to a broken compile.
4. Brace-match `"void main("` (the identical loop as SodiumClipShaderPatch.java:168-195), splice:
   - before `void main(`:
     ```
     out float gl_ClipDistance[1];
     uniform vec4 seamlessportals_ClipPlane;
     ```
   - before main's closing brace:
     ```
     {
         // SEAMLESSPORTALS_CLIP_INJECTED (iris-sodium terrain, IS3)
         gl_ClipDistance[0] = dot((u_ModelViewMatrix * getVertexPosition()).xyz, seamlessportals_ClipPlane.xyz) + seamlessportals_ClipPlane.w;
     }
     ```

Declaration-order safe (bytecode-verified from the transformer injection points): `u_Globals`
(carrying `u_ModelViewMatrix`) is injected BEFORE_DECLARATIONS and `getVertexPosition()`
BEFORE_FUNCTIONS — both precede `main` in print order; our uniform decl sits immediately before
`main`. So at the write site all four symbols (`u_ModelViewMatrix`, `getVertexPosition`,
`seamlessportals_ClipPlane`, `gl_ClipDistance`) are declared-before-use. `getVertexPosition()`
returns `vec4` -> `mat4 * vec4` = `vec4`, `.xyz` valid. Sodium's terrain `main` is not wrapped
(no `irisMain`/`renameFunctionCall("main")` on either sodium path), so the single `void main(`
is the real one. `out float gl_ClipDistance[1];` is the same explicit built-in redeclaration the
proven shaders-OFF sodium seam already emits into `#version 330 core` sources; iris declares no
`gl_PerVertex` block anywhere (grep-verified), so a separate `out float gl_ClipDistance[1]` is
safe — optionally add a `contains("gl_PerVertex")` fail-safe as cheap insurance against an
exotic pack that redeclares the block.

**Registration.** Config `common/src/main/resources/seamlessportals-ip-compat.mixins.json`
(package `qouteall.imm_ptl.core.compat.mixin`, plugin `IPCompatMixinPlugin`,
`injectors.defaultRequire = 1`). Add the class under a new `iris.` (or `iris_sodium.`) subpackage:
`iris.MixinIrisSodiumTransformPatcher_ClipInject`.

**Naming/gate — resolved against source (rPatcher vs rInteract split).** The simple name MUST
contain `"IrisSodium"`. Gate-1 in `IPCompatMixinPlugin.shouldApplyMixin` is order-sensitive
(verified IPCompatMixinPlugin.java:104-117): `contains("IrisSodium")` is tested FIRST ->
`isSodiumPresent() && isIrisPresent()`; only if that substring is absent does it fall to
`contains("Iris")` -> iris-only. rInteract argued "Iris" alone suffices because iris hard-depends
on sodium; that is behaviorally correct in the shipped config but LESS precise. The mixin
patches Patch.SODIUM terrain GLSL — it is genuinely meaningless without BOTH mods — and the
plugin's own footgun doc (IPCompatMixinPlugin.java:34-42) states the substring is "the
mod-presence key, not decoration." `IrisSodium` is the honest key and the gate-order comment
explicitly anticipates it. Gate-2 = `EntityPortalsFlag.isOn()` (applied to every class; forces
false off Fabric -> the mixin is skipped on NeoForge entirely, benign — no shaders-ON compat
there).

**Assert.** `require = 1` (inherits `defaultRequire = 1`) + `@Pseudo`: with iris present (gate
ensures it) a drift in `transformInternal`'s descriptor across iris versions fails the @Inject
bind -> LOUD boot crash (the D7 deliberate-honesty discipline). `@Pseudo` tolerates iris-absent
(gate already skips). Ledger the iris-version coupling (section 4.6). Add a one-shot
`LOGGER.info` per patched shader id (mirror SodiumClipShaderPatch.java:216) and a one-shot
`LOGGER.warn` on the core-profile fail-safe, both behind the existing
`-Dseamlessportals.compatProbe=true` lever for a full patched-source dump.

### §4.3 THE BINDER + PLANE-SOURCE CONFIRMATION

**The per-draw uploaders resolve the location after injection (source + bytecode-verified).**
Two uploaders fire for the nested full-pipeline terrain draw:
- `MixinSodiumGLDrawContext_ClipUpload` @ `GLDrawContext.setContext` RETURN. Jar bytecode of
  `setContext`: `GlRenderPipeline.program() -> GlProgram.getProgramId() ->
  GlStateManager._glUseProgram(id)` (offsets 24-34), re-bound before RETURN (offsets 70-72) —
  so at the injection `GL_CURRENT_PROGRAM` is the iris-patched terrain program. Post-section-4.2,
  `_glGetUniformLocation(id, "seamlessportals_ClipPlane") >= 0` -> `glUniform4f` uploads the
  armed plane; the definedness-suppress branch (loc==-1) goes dead on patched programs.
- `GlCommandEncoderClipMixin` @ `trySetup` RETURN (the universal chokepoint; the sodium
  Candidate-A ledger proves sodium terrain reaches it via `GLDrawBatch.draw ->
  RenderPass.multiDrawIndexed -> GlCommandEncoder.executeDraws -> trySetup`, program current).
  Uploads via `ClipUniformLocationCache`.

**Plane source and matrix-match (the load-bearing correctness proof).** The clip's eye-space
correctness requires the matrix fed to `setupInnerClipping` to equal the matrix that transforms
the terrain vertices. `getActiveClippingPlane()` returns the innermost pushed portal's
`getInnerClipping()` (the correct dest-portal inner-clip plane; null -> keep-all). `destViewMatrix`
is the matrix passed to `destRenderer.render(...)`, and (per the qouteall FrontClipping S13-L
class note, lines 55-74, and rBinder's `render() -> prepareChunkRenders(cameraRenderState
.viewRotationMatrix == destViewMatrix) -> sodium ChunkRenderMatrices.modelView ->
u_ModelViewMatrix` trace) it is provably the SAME matrix sodium terrain is transformed by. So
the injected `dot((u_ModelViewMatrix*getVertexPosition()).xyz, n) + c` evaluates
`dot(R*p_rel, R*n) + c = n*p_rel + c` — the exact kept half-space of the bridge SIGN NOTE.
Correct for same-dim and cross-dim (both branches reach the same arm with `isRendering()` true
and the same portal plane). Scaled fuse-view portals: `destViewMatrix` carries the same uniform
scale k=1/s the decomposed path handles, and `rotateClipNormalToViewSpace` (qouteall
FrontClipping.java:201-223) detects `|det-1| > 1e-3` -> returns the covector inverse-transpose
`M^-T * n = (1/k)R*n`; the shader's `k*R*p_rel` compounds to `n*p_rel + c` — scale cancels.
Because the full-pipeline arm feeds the SAME `destViewMatrix` to both terrain and clip, the
S13-L derivation transfers verbatim.

**Fold-in hardening (from rBinder, verified).** `MixinSodiumGLDrawContext_ClipUpload` uses its
own private `ip_clipPlaneLocationCache` HashMap (line 92), which `GlDeviceClipCacheMixin` does
NOT invalidate on `GlDevice.clearPipelineCache` and which has no size cap — unlike the vanilla
uploader, which was moved to the IS2-invalidated `ClipUniformLocationCache`. On program-id reuse
after a sodium/iris terrain-shader rebuild it can feed a stale >=0 location to `glUniform4f`
(GL_INVALID_OPERATION spam + wrong-uniform-write). This PRE-EXISTS IS3 (live for shaders-OFF
sodium since C2) but IS3 widens the >=0-location program set to the iris-transformed terrain, so
fold the fix in: route the sodium uploader's cache through `ClipUniformLocationCache`. Non-blocking
(the `trySetup` uploader still lands the correct plane per-batch via the invalidated cache).

### §4.4 THE RETIREMENTS + the new notice

- **RETIRE** the DEF-G clip belt: only the `FrontClipping.disableClipping()` at the pre-render
  site (section 4.1). KEEP the `GL11.glDisable(GL_STENCIL_TEST)` beside it (the stencil-free
  shape). KEEP both finally re-asserts (`glDisable(STENCIL)` + `disableClipping()` — post-pass
  cleanup). Update the method-header javadoc's "DEF-G belt ... retired at IS3" bullet to describe
  the arm.
- **KEEP** the sodium per-pass definedness guard (MixinSodiumGLDrawContext_ClipUpload:141-160):
  it is the safety net for an unpatched sodium terrain program (core-profile residual section 4.2,
  resource-pack-replaced source, driver dead-strip) — NOT the whole-pass belt.
- **ADD** (NOT a retirement — the section 4.0 requirement): the definedness guard to the vanilla
  `GlCommandEncoderClipMixin`, keyed on `FrontClipping.capture().enabled`.
- **FOLD IN** the sodium-cache wiring (section 4.3).
- **REWORD the notice** (`PortalRenderer.java` ~:453-460): IS3 makes clipping real, so drop the
  "dest terrain is not clipped at the portal plane" clause and the blanket "expect artifacts."
  New wording (keep GOLD, the one-shot latch, the `isShaders()` gate):
  > `[Seamless Portals] Experimental shaderpack portal views are ON — portals render through
  > your shaderpack (one recursion layer). Expect some added frame cost while portals are on
  > screen.`
  Leave the D8 pass-through notice (~:485-491) as-is (it still serves opt-out / renderMode=none
  / pre-flip builds).

### §4.5 THE DEFAULT-FLIP (Q-U1) — USER CHECKPOINT, DO NOT BAKE

Mechanics verified. `isShaderpackPortalViewsArmed() = experimentalShaderpackPortalViews (default
FALSE) || SHADERPACK_VIEWS_JVM_LEVER` (IPGlobal.java:57-65). The D8-EVO routing branch
(PortalRenderer.java:450-467) fires on `isShaderpackPortalViewsArmed() && renderMode != none`
and routes to `IrisCompatOn262Renderer` (debug -> debugModeInstance, else -> instance). CRITICAL,
verified: the `IrisInterface.invoker.isShaders()` check INSIDE that branch (:453) gates ONLY the
notice — NOT the routing. So a naive flip (`experimentalShaderpackPortalViews = true`) routes
EVERYONE — including shaders-OFF / no-pack users — to the full-pipeline compat renderer,
retiring the proven `rendererUsingStencil` as the default. That is a far larger blast radius than
"shaders-ON defaults to the new renderer" and is almost certainly NOT the intended Q-U1.

**Recommended flip form (minimal blast radius) — shaders-gate the flag path, keep the JVM
lever's broad dev routing:**
```java
boolean shaders = IrisInterface.invoker.isShaders();
if (IPGlobal.renderMode != IPGlobal.RenderMode.none
    && (IPGlobal.SHADERPACK_VIEWS_JVM_LEVER
        || (IPGlobal.experimentalShaderpackPortalViews && shaders))) {
    // ... route to IrisCompatOn262Renderer ...
}
```
with `experimentalShaderpackPortalViews` flipped to default `true`. Effect: shaders-ON -> compat
(new renderer); shaders-OFF -> falls through to `switch(renderMode)` -> `rendererUsingStencil`
(unchanged proven path); the dev lever still forces compat for both (the IS1/IS2/IS3 proof rows).
`renderMode=none` stays the master off-switch (guard is false -> `switch(none)` ->
`rendererDummy`, verified). Opt-out = `experimentalShaderpackPortalViews=false` or
`renderMode=none`; `IrisCompatOn262Renderer.onSwitchedAway()` handles the eviction.

**This is a USER DECISION.** Recommendation: flip to default-ON ONLY after IS3 live-validates
clipping (terrain-clip defect resolved AND the sky/non-terrain observables judged acceptable),
via the shaders-gated form above. Do NOT bake the flip into IS3's code — surface it as the
checkpoint with the recommended default.

### §4.6 THE DEVIATION LEDGER

- **D9 amendment.** D9 was "zero iris mixins." IS3 registers ONE: the TransformPatcher clip
  injector (section 4.2), populating the previously-empty Iris arm of `IPCompatMixinPlugin`. It
  is NOT one of IP's three iris mixins (those target the rendering pipeline; IP used the now-dead
  GL_CLIP_PLANE0 / per-shader-uniform path) — it is a NEW mixin with no IP precedent. Fabric-only
  by construction (gate-2). Amend the IPCompatMixinPlugin section 57-66 javadoc.
- **NEW: vanilla-mixin definedness guard.** The always-on-substrate `GlCommandEncoderClipMixin`
  gains a per-draw enable-authority guard (section 4.0). Bit-identical for shaders-OFF (all
  vanilla world shaders injected -> loc>=0 -> suppress never fires).
- **NEW: full-pipeline shaders-OFF SKY clipping.** The monolithic `render()` cannot draw-sky-
  before-arm the way the decomposed path does (decomposed draws sky before its :1098 arm because
  "the dome spans both sides of the plane"). On the lever-only shaders-OFF proof row, the vanilla
  sky shader IS injected (loc>=0) -> option (b) enables the clip for the sky draw inside `render()`
  -> the sky dome gets bisected at the plane. Lever-only test-config artifact (shipped default =
  D8 / the shaders-gated flip keeps shaders-OFF on the decomposed stencil renderer). Likely
  benign (the clipped half is the near-camera hemisphere behind the portal). Live-round
  observable — a cut-sky screenshot on a proof row is NOT a regression.
- **NEW: non-terrain iris programs render UNCLIPPED-but-DEFINED under a pack.** Entities/clouds/
  weather straddling the dest plane are not clipped shaders-ON — only terrain is (section 4.0
  option b). This is actually MORE faithful to IP (IP unset the clip uniform for all shaders
  except cross-portal-entity + weather) and within the stage-(a) envelope. Matching the
  decomposed path's margin-clipped entities under shaders-ON would need Patch.VANILLA injection
  with the straddle-margin — deferred.
- **RESIDUAL: core-profile shaderpacks get no terrain clipping** (section 4.2
  `SodiumCoreTransformer` path -> no `getVertexPosition()` -> fail-safe). Dest terrain near the
  portal still clips into view for them (unclipped-but-defined; the sodium guard keeps it
  defined). Popular packs (BSL/Complementary/Sildur's are `#version 120` compat) route through
  `SodiumTransformer` -> covered; the residual bites only core-profile packs. Surface via the
  one-shot WARN.
- **Scaled-portal.** Scale-1 exact (rigid fast path, bit-identical). Scale>2 correctness under
  the full-pipeline iris terrain path is UNPROVEN (the covector transform assumes the iris sodium
  shader's `u_ModelViewMatrix` == the fed `destViewMatrix`, compounded by the section 2.7 scale>2
  nested-arm projection-capture mismatch). Rides a later live row.

### §4.7 THE LIVE-ROUND SCRIPT + discriminators + verify plan

**Pre-registered discriminators (settle the load-bearing live-only forks):**
1. **Do iris non-terrain (Patch.VANILLA) draws actually pass through `GlCommandEncoder.trySetup`
   under an active pack?** (rBelt's LIVE-ONLY fork — the section 4.0 guard is worthless if they
   don't reach the vanilla mixin, and the non-terrain hazard would then not exist there either.)
   Discriminator: a 1Hz lever-gated per-draw probe at `trySetup` RETURN logging
   `GL_CURRENT_PROGRAM`, the queried loc, and the enable-decision for the first N draws of a
   full-pipeline pass under a real pack. Expect: terrain -> loc>=0 / enabled; entity+sky ->
   loc==-1 / disabled. If entity/sky draws never appear, they route elsewhere (iris's own path)
   and the guard is moot — re-scope.
2. **Do the per-draw uploaders FIRE for sodium terrain under an ACTIVE iris pack?** (rPatcher/
   rBinder's top live risk — iris may reroute chunk-draw dispatch.) Discriminator: the
   `compatProbe` dump + a `glGetUniformLocation != -1` + upload log on the iris terrain program.
   If the location is never queried/uploaded, the correctly-patched uniform is dead and clipping
   stays off even on the compat path.
3. **Does the injection reach the RIGHT terrain source (compat vs core)?** Discriminator: the
   one-shot patch-INFO (compat, patched) vs the one-shot core-profile WARN (residual). A pack
   showing the WARN is a core-profile pack -> expected unclipped terrain.
4. **Is the clip visually correct (the user's symptom)?** Discriminator: on the dest side place
   terrain close to and in front of the portal; on the source side swing the camera near it. PASS
   = the near-side dest terrain is clipped at the portal plane (no clip-through). Convict
   independently via the C4 A/B lever (`IPGlobal.enableClippingMechanism` /
   `IPCGlobal.useFrontClipping`) — toggling clip OFF must restore the clip-through, ON must fix
   it. Surface the A/B switch at the live test (standing C4 directive).
5. **Sky/entity definedness under a pack** (the section 4.0 hazard's negative check).
   Discriminator: on AMD/Intel if available (undefined-clip culls there, not on NVIDIA), confirm
   the sky dome and entities render fully with clip armed. A culled sky = the guard is not firing
   for those draws.

**Live script (surface the A/B switch at each):** (a) plain no-pack lever row — full-pipeline
shaders-OFF: terrain clips, note the sky-bisection artifact (expected, section 4.6). (b)
sodium-only lever row. (c) real compat-profile pack (BSL/Complementary): terrain clips at the
plane, sky + entities render fully (defined). (d) real core-profile pack if available: WARN
fires, terrain unclipped-but-defined (documented residual). (e) scaled fuse-view portal at scale
1 (exact) and >2 (ledgered). (f) cross-dim + same-dim both.

**Deep-Opus verify plan ([[opus-verifier-depth-protocol]]).** 6 verifiers x2 rounds + 3 judges +
majority-bound fold. Load-bearing claims each verifier MUST re-derive from source (no
inheritance): (i) the section 4.0 hazard chain — Patch enum has no ENTITY, vanilla mixin line 56
lacks the guard, non-terrain draws route through vanilla trySetup not sodium GLDrawContext; (ii)
the transformInternal descriptor + caching + Parameters.patch access; (iii) the getVertexPosition
compat/core split (SodiumTransformer vs SodiumCoreTransformer) — the census-expression
correction; (iv) the belt-swap symbol scope + the finally already restoring; (v) destViewMatrix
== the sodium terrain matrix (the plane-source correctness proof); (vi) the flip over-reach (the
isShaders() check gates only the notice). Judges fold on the two decisions that split the recon
(option b vs the sodium-guard-covers-it claim; IrisSodium vs Iris naming) — both resolved above
against source. Majority-bound: a claim ships only if >=2 independent verifiers re-derive it from
source; a split escalates to a fresh source read, never to a vote on prose.

## §4.8 IS3 IMPLEMENTATION RECORD + THE VERIFY FOLD (worktree `is3-clip`, branch `iris-on/is3-clip`)

The §4 spec was built; the deep-Opus verify plan (§4.7) ran — 6 verifiers x2 + 3 judges + a
majority-bound Opus fold. TWO independent BLOCKERs surfaced (3/3 judges REAL each), both on the
primary compat-profile shaders-ON path, both MUST-FIX. This subsection is the post-impl reconcile
the recon (§4.1/§4.2) lacked.

### The two BLOCKERs (both landed)

- **V2 — the `cancellable = true` omission.** `MixinIrisSodiumTransformPatcher_ClipInject`'s
  `@Inject(at = @At("RETURN"))` called `cir.setReturnValue(copy)` WITHOUT `cancellable = true`.
  Mixin 0.8.5: `CallbackInfoReturnable.setReturnValue` invokes `CallbackInfo.cancel()`, which
  THROWS `CancellationException` when `cancellable == false` (the default). On every compat-profile
  pack `spliceClip` returns a patched (`!= vsh`) source → `setReturnValue` fires → throws out of
  iris shader compilation. Blast radius (judge JX-1/JB): the injection weaves on iris+sodium+flag
  independent of the shaderpackViews LEVER, so this threw on NORMAL startup for every default
  iris+sodium user loading a common shaderpack — not just the experimental feature. FIX: added
  `cancellable = true` (matching the sibling `setReturnValue`-at-RETURN seams
  `MixinSodiumShaderManagerCompilationCache_ClipSourcePatch:45`, `ShaderManagerCompilationCacheMixin:32`).

- **V6 — the M4 belt-swap defeat (the §4.1 arm does not survive `render()`).** The §4.1 belt swap
  arms the clip ONCE before `destRenderer.render()`. But vanilla `LevelRenderer.render`
  (mc262-ref `:174`) runs `submitFeatures` (its ENTITY submit, BUILD phase) BEFORE
  `executeFrameGraph` (`:239`, where terrain draws at `:409` OPAQUE / `:438` TRANSLUCENT). M4
  (`MixinLevelRenderer_CrossPortalEntity` submitEntities-TAIL →
  `CrossPortalEntityRenderer.onEndRenderingEntitiesAndBlockEntities` `:171`) calls
  `FrontClipping.disableClipping()` UNCONDITIONALLY there — resetting the live `com.warwa` store to
  the keep-all plane `(0,0,0,1)` AND clearing `glClipEnabled` BEFORE terrain executes. The per-draw
  uploaders key terrain-enable on `capture().enabled` (now false) and upload the live plane (now
  keep-all), so dest terrain rendered UNCLIPPED — IS3's core symptom fix did not occur. The §2.2 M4
  row (IS1-era "disarms before any draw executes") was never reconciled against the new arm; under
  the belt swap that benign note IS the clobber. (The decomposed path is immune: it arms at
  `~:1098` immediately before its own DIRECT terrain draw and submits entities AFTER, so M4 never
  precedes its terrain.)

  FIX (the pass-scoped override, judge-prescribed option (a) — a mere post-`submitFeatures` re-arm,
  option (b), is INSUFFICIENT):
  1. New holder `com.warwa.seamlessportals.render.FullPipelineClipState` — a pass-scoped `armed` +
     frozen view-space plane `(x,y,z,w)`, armed ONLY inside `renderDestWorldFullPipeline`.
  2. The belt arm FREEZES the just-armed `com.warwa` `FrontClipping.capture()` into the override
     (only when `enabled` — a layer-0 null plane leaves it disarmed → unclipped, matching decomposed).
     Saved/restored across the pass (nested full-pipeline passes stack).
  3. BOTH per-draw uploaders (`GlCommandEncoderClipMixin` + `MixinSodiumGLDrawContext_ClipUpload`)
     now compute `clipArmed = FullPipelineClipState.isArmed() || FrontClipping.capture().enabled`
     and, when the override is armed, source the FROZEN belt plane (not the M4-clobbered live store)
     for the `glUniform4f` upload. Terrain therefore re-asserts `GL_CLIP_DISTANCE0` with the real
     plane regardless of M4; non-terrain draws still suppress (defined-and-unclipped).
  4. **jB J1 (the deeper oscillation, 2/3 judges via jA+jB — the sodium uploader MUST self-enable
     too):** within one `frame.execute` the draw order is OPAQUE terrain → solid entities →
     translucent entities → TRANSLUCENT terrain (mc262-ref `:409/:419/:434/:438`). The un-injected
     entity draws `glDisable` the cap between the two terrain groups, and the sodium `setContext`
     uploader previously did NOT self-enable (it relied on the ambient whole-pass arm), so
     translucent terrain would draw unclipped even after the store is re-armed. FIX: the sodium
     uploader now issues `glEnable(GL_CLIP_DISTANCE0)` at `loc>=0` when the full-pipeline override is
     armed (each terrain group's `setContext` re-asserts it; the vanilla per-batch trySetup guard
     also covers it if Candidate-A holds — belt-and-suspenders). Full-pipeline-override-gated so the
     decomposed path adds no GL call.
  5. **The teardown leak** (found while implementing): the per-draw raw `glEnable` toggles bypass the
     `com.warwa` `glClipEnabled` cache, and M4 may have driven that cache to false mid-pass, so the
     finally's `disableClipping()` can no-op while raw `GL_CLIP_DISTANCE0` is left ON — leaking into
     the main frame. FIX: the finally now HARD-disables the raw cap (`GL11.glDisable(GL30
     .GL_CLIP_DISTANCE0)`; not GlStateManager-cached — the store's own idiom) + restores the override.

  Ordering for the live round: V2 throws at pack-COMPILE (before any portal frame), so it MASKS V6.
  With V2 fixed, a compat pack reaches the terrain-draw scenario where V6's unclipped terrain (now
  also fixed) would have shown.

### The M4 row — reconciled (feeds the §2.2 discoverability gap)

`§2.2`'s M4 row must now read: under the §4.1 full-pipeline arm, M4's submitEntities-TAIL
`disableClipping()` disarms the LIVE store during `render()`'s BUILD phase, so the full-pipeline
terrain clip does NOT ride the live store — it rides the pass-scoped `FullPipelineClipState`
override (frozen at the belt arm), which both per-draw uploaders consult. M4's transient onBegin
re-arm (margin 0, `viewRotationMatrix`) during submit is therefore also harmless to terrain (terrain
reads the frozen belt plane, never the M4-mutated live store).

### Doc/wording corrections folded (>=2/3 judges REAL, non-blocking)

- **V1-1 / V5-1 (3/3):** the "bit-identical shaders-OFF / suppress-branch-never-fires" claim was
  FALSE — `ShaderCodeTransformation` is a CONSERVATIVE injector (its own javadoc names
  `rendertype_end_portal`/panorama/ungated programs as left un-injected, loc==-1), so an un-injected
  world program CAN draw inside an armed decomposed inner-clip window and now hits the new `glDisable`
  suppress branch where pre-IS3 nothing happened. Retired the "bit-identical" wording to
  "undefined→defined-unclipped SAFE improvement (same pixels on NVIDIA, correct on AMD/Intel), NOT a
  regression; lever-OFF the full-pipeline renderer never runs → the guard reads exactly
  `capture().enabled`, GL-state identical to plain sodium." (`GlCommandEncoderClipMixin` javadoc.)
- **V4-3 (2/2):** softened the `ClipUniformLocationCache` "closes it" claim — the fold-in closes the
  stale-location hazard only for `clearPipelineCache`-triggering reloads; an iris-only pipeline
  rebuild (iris never calls `clearPipelineCache`) that recycles a program id stays covered only by
  the `MAX_ENTRIES` cap + the next resource reload (bounded, self-healing, not fully closed).
- **V5-4 (2/2):** `ClipUniformLocationCache` javadoc no longer calls `GlCommandEncoderClipMixin` the
  "sole reader/writer" — the sodium uploader is a second reader/writer since the §4.3 fold-in.
- **V4-2 (2/2):** `ClipDiscriminatorProbe` javadoc corrected — `ENABLED = Boolean.getBoolean(...)`
  is a RUNTIME-read static, NOT a javac compile-time constant, so the guards are real short-circuit
  branches (cheap: 3 static reads + primitives, no allocation), not dead-code-eliminated / "byte-inert".
- **V3-1 (jA REAL; masked by V6 for jC):** the shaders-OFF full-pipeline SKY bisection (render()
  draws sky AFTER the single arm; the decomposed path draws sky before its arm because the dome spans
  the plane) is spec-accepted (§4.6) and reappears now that V6 is fixed — added the cross-reference to
  the belt comment so the "clips exactly as on the stencil path" line no longer hides the sky exception.

### NOTEs recorded (no code change — API-forced / accepted)

- **Per-draw `FrontClipping.capture()` allocation (V1-2/V4-1/V5-3, 2/2):** the guard reads
  `capture().enabled` eagerly for every `programId>0` draw (GUI/menu included), allocating a
  `Snapshot` where pre-IS3 the `loc<0` early-return did not. HotSpot escape analysis should
  scalar-replace the non-escaping Snapshot after warmup; a non-allocating `isGlClipEnabled()` accessor
  on `com.warwa FrontClipping` would remove it but was declined to honor the live-substrate freeze.
  Correctness unaffected. (An additive accessor remains a deferrable follow-up if GC pressure is seen.)
- **The user notice (§4.4 reword; judges J2/JX-3):** the dropped "expect artifacts" clause is now
  BACKED by the V2+V6 fixes making the clip real end-to-end — but the final "it works" proof is the
  live round; the notice ships coupled to that round confirming terrain clips through the pack.

### Live-only items the fold cannot statically close (settle at the §4.7 self-run)

- **Discriminator 1** — do iris Patch.VANILLA non-terrain draws funnel through `GlCommandEncoder
  .trySetup`? If they route through iris's own deferred path the guard never fires for them (armed +
  no `gl_ClipDistance` written = undefined → strict-driver cull). The fix is robust to the terrain
  side regardless (both uploaders self-enable), but the non-terrain definedness still depends on this.
- **Candidate-A** — that every sodium terrain batch also recurs through vanilla trySetup. The sodium
  uploader's per-group `setContext` self-enable now covers the translucent-after-entities case even if
  Candidate-A fails; the `[IS3-CLIP-PROBE]` dump must still confirm terrain→loc>=0/enabled at BOTH the
  OPAQUE and TRANSLUCENT draws.

### Compile gate

`.\gradlew.bat :common:compileJava :fabric:compileJava --console=plain` from the worktree root —
GREEN (both tasks executed) after the fold.

### §4.9 THE CLIP-PROBE SELF-RUN (2026-07-20) — mechanism LIVE-CONFIRMED, both residuals settled

`runCrossingGametest -PirisRuntime -PshaderpackViews -PclipProbe -PgametestScreenshots`
(Complementary Reimagined active): ALL 8 LEGS PASS, no AIOOBE. The `[IS3-CLIP-PROBE]`
per-draw dump settles §4.7 discriminators 1+2 and the two fold residuals:
- **TransformPatcher fired** (discriminator 3 = compat-profile, patched; core-profile WARN
  count = 0): 6 iris-sodium terrain shaders clip-patched — `sodium_terrain_{solid,cutout,
  translucent}` + the 3 `shadow_` variants. Complementary routes through `SodiumTransformer`
  (compat) → `getVertexPosition()` present → patched; no core-profile residual.
- **Discriminator 2 (terrain gets loc>=0/enabled at opaque AND translucent):** terrain
  programs 480/483 report `loc=27/28 armed=true -> TERRAIN(inject) CLIP-ENABLED` across
  every full-pipeline pass (overworld + nether, opaque + translucent draws present).
- **Discriminator 1 (non-terrain iris draws DO reach the vanilla trySetup chokepoint, and
  the guard fires):** non-terrain programs 345/372/474 report `loc=-1 armed=true ->
  NON-TERRAIN clip-disabled` — so entity/sky draws pass through `GlCommandEncoder.trySetup`
  (the guard sees them) and the §4.0 definedness guard correctly DISABLES GL_CLIP_DISTANCE0
  for them (defined-unclipped, not undefined). Tally: 155 enable / 107 disable decisions,
  all correctly keyed on loc sign.

The IS3 mechanism is wired correctly end to end (injection → location resolve → per-program
enable scope). REMAINING = the experiential VISUAL confirmation (does dest terrain near the
portal now clip at the plane instead of showing through) — user pack round, with the C4 A/B
lever (`IPGlobal.enableClippingMechanism`) as the independent convict — + the Q-U1
default-flip decision (§4.5).

## §5 THE IS4 DEFAULT-FLIP (Q-U1, user-decided 2026-07-20; worktree `is4-flip`, branch `iris-on/is4-flip`)

**Decision Q-U1 (user):** after IS3 live-proved the clip, the experimental shaderpack portal
views become the DEFAULT for shaderpack users. `experimentalShaderpackPortalViews` flips
`false -> true`, but the flip is made SHADERS-GATED at the routing site so it does not retire the
proven stencil renderer for everyone (the recon §4.5 naive-flip over-reach).

### The change (2 files)

- **`IPGlobal.java`** — (a) `experimentalShaderpackPortalViews` default `false -> true`;
  (b) NEW `isShaderpackPortalViewsActive(boolean shadersActive)` =
  `SHADERPACK_VIEWS_JVM_LEVER || (experimentalShaderpackPortalViews && shadersActive)`.
  `isShaderpackPortalViewsArmed()` (= `flag || lever`) is RETAINED but now has **zero live
  callers** (docs-only; a CAUTION was added — never wire routing to it, it omits the shaders gate).
- **`PortalRenderer.switchToCorrectRenderer`** — the Block-1 predicate changed from
  `isShaderpackPortalViewsArmed()` to `isShaderpackPortalViewsActive(IrisInterface.invoker.isShaders())`.
  The `&& renderMode != none` guard is UNCHANGED (master off-switch, sits OUTSIDE `active()`).

### The routing fold

`Active(S) = LEVER || (FLAG && S)`, where `S = IrisInterface.invoker.isShaders()`
(base `Invoker` returns literal `false` when iris absent; `OnIrisPresent` returns
`Iris.getCurrentPack().isPresent()` — i.e. **a pack is actually loaded AND on**).

Block 1 fires iff `Active(S) && M != none`, then folds `renderMode`:
`debug -> debugModeInstance`, **`normal` AND `compatibility` both -> `instance`** (the iris compat
renderer has NO framebuffer variant; vanilla `rendererUsingFrameBuffer` is inapplicable while a
pack is active). Otherwise control falls through to the pre-IS1 selection (D8 iris block, then the
`switch(renderMode)` stencil family).

### Truth table

DEFAULT config (`FLAG=true`, `LEVER=false`):

| S (pack on) | iris present | renderMode | Active(S) | Route | vs pre-flip |
|---|---|---|---|---|---|
| true | yes | normal | true | `IrisCompatOn262Renderer.instance` | **FLIP DELTA** (was D8 dummy+notice) |
| true | yes | compatibility | true | `instance` (folds to it) | **FLIP DELTA** (was D8 dummy) |
| true | yes | debug | true | `debugModeInstance` | **FLIP DELTA** (was D8 dummy) |
| true | yes | none | false | falls -> D8 iris block -> `rendererDummy` + pass-through notice | identical |
| false | yes (no pack) | normal | false | falls -> stencil (`rendererUsingStencil`) | byte-identical |
| false | no | normal | false | `rendererUsingStencil` | byte-identical |
| false | no | compatibility | false | `rendererUsingFrameBuffer` | byte-identical |
| false | no | debug | false | `rendererDebug` | byte-identical |
| false | any | none | false | `rendererDummy` | byte-identical |

DEV lever (`LEVER=true`, FLAG irrelevant): any `M != none` -> `instance`/`debugModeInstance`
regardless of shader state (proof rows, unchanged from IS1); `M=none` falls through (`S=true` ->
D8 dummy+notice if iris present; `S=false` -> `switch none` -> dummy).

**The single behavioral delta of the flip = `{S=true, LEVER=false}` non-`none` rows**: pre-flip
D8 dummy pass-through -> post-flip the compat renderer (real portal views). Every shaders-OFF /
no-pack / plain / `none` row is byte-identical to pre-flip. Blast radius = exactly shaderpack-ON
users. Confirmed by all four verifiers' break-attempt traces (`active(false) = false||(true&&false)
= false -> stencil family`).

### Verify verdict

4 verifiers: v1 PASS, v2 PASS, v3 PASS_WITH_CORRECTIONS, v4 PASS_WITH_CORRECTIONS. 3 judges
(jA/jB/jC): all CORRECTIONS_REQUIRED. The FLIP LOGIC itself is adjudicated CORRECT and ships as
written — every correction is documentation-only. The recon §4.5 over-reach is genuinely
foreclosed by the shaders gate; no NoClassDefFoundError (base `Invoker.isShaders()` touches no
`net.irisshaders.*` class; `OnIrisPresent` installs only when iris is present); `invoker` never
null (static-init `new Invoker()`, only reassigned to `OnIrisPresent`); the eager `isShaders()`
argument is side-effect-free (pure `Optional.isPresent()` / constant `false`) and already called
downstream in the same synchronous method.

### Corrections APPLIED (>=2/3 judges REAL — MUST-APPLY)

1. **`IrisCompatOn262Renderer.java` Selection javadoc** (v1-1/v2-2/v3-1/v4-2; jA/jB/jC all REAL;
   jA JX-3 CORRECTION) — rewrote the `<b>Selection</b>` block: it claimed "IS1 = LEVER-ONLY, zero
   committed-default change" routing via `isShaderpackPortalViewsArmed()`. Now describes the IS4
   default-ON, shaders-gated routing via `isShaderpackPortalViewsActive(isShaders())`, and adds an
   explicit "DEFAULT-LIVE renderer, NOT a dormant/lever-only held source — do not strand it" note
   for the S20 dormancy/deletion pass (the load-bearing reason: the project treats these comments
   as the S20 deletion inventory's source of truth).
2. **`PortalRenderer.java:419-420` field comment** on `shaderpackViewsExperimentNotified`
   (v4-1; jA/jB/jC all REAL) — said "lever-armed sessions only — never fires at default". That is
   now false: at the default config the GOLD experiment notice fires whenever a pack is active.
   Rewrote to state it fires at the default under an active pack (via the default-TRUE flag) OR the
   lever.
3. **`IPGlobal.java` `isShaderpackPortalViewsArmed()` javadoc** (v2-1/v3-2; jA/jB/jC all REAL) —
   noted zero live callers post-flip and added a CAUTION: never wire renderer selection to
   `armed()` (it omits the shaders gate; at default-TRUE it would route shaders-OFF users to the
   compat renderer = the recon §4.5 over-reach). Routing MUST use `isShaderpackPortalViewsActive`.
4. **`IPGlobal.java` `experimentalShaderpackPortalViews` comment** (v4-3; jA/jB/jC all REAL) — the
   "opt-out" wording implied a user control that does not exist. Clarified it is a CODE-LEVEL
   opt-out only: the field is not bound to `IPConfig`/`IPConfigGUI` (grep-confirmed sole-file),
   so it is neither serialized nor exposed; a real user's only runtime fall-back off this renderer
   is `renderMode=none` (which disables all portal rendering).

### NOT applied (below the >=2/3 threshold or no-action) — ledgered for completeness

- **isShaders() eager-eval (v2-3):** all judges REAL but "no defect, fix = none" — side-effect-free
  and already invoked downstream. No code change.
- **D8 pass-through message text "not yet supported — disable shaders" (jC JX-1 CORRECTION):**
  only jC rates it a required correction; jA JX-1 covers the same area as a NOTE and states "No
  action required for the flip"; jB silent. Below the >=2/3 bar, so NOT changed. It is a
  pre-existing message (not introduced by the flip), now reachable by a shaders-ON user only at
  `renderMode=none` (or the code-level `FLAG=false` opt-out) — where its "disable shaders" advice
  is stale (a `none` user has already disabled portal rendering). Flagged for a future cleanup, not
  a flip blocker.
- **`ENABLE_SODIUM_IRIS_COMPAT` coupling (jB JX-1 NOTE):** the flip's effectiveness depends on the
  independent default-true gate `ExperimentalCompatGate.ENABLE_SODIUM_IRIS_COMPAT`, which gates the
  `OnIrisPresent` invoker install (`SeamlessPortalsClientFabric` ~:294-307). If that gate were ever
  set false, the invoker stays base `Invoker`, `isShaders()==false`, `active(false)==false` -> the
  flip nullifies gracefully to the stencil family (byte-identical to pre-flip gate-off), and the D8
  notice also does not fire (`isIrisPresent()==false`). The blast-radius claim holds in BOTH gate
  states; the two default-true switches must stay aligned for the feature to FUNCTION, but a
  gate-off change nullifies the flip, it does not regress anything.
- **Sibling stale comment (observed, NOT adjudicated):** `PortalRenderer.switchRenderer` ~:517-518
  still reads "oldRenderer can only BE one after an armed route — byte-inert at the committed
  default." Post-flip that eviction path is live at the default (a shaders-ON default route makes
  `oldRenderer` an `IrisCompatOn262Renderer`), so "byte-inert at the committed default" is now stale
  — same staleness class as the applied fixes. No verifier/judge raised it, so it was left
  UNCHANGED per the bound-corrections discipline; surfaced here for the user's S20 doc pass.

### Compile gate

`.\gradlew.bat :common:compileJava :fabric:compileJava --console=plain` from the worktree root —
**GREEN** (BUILD SUCCESSFUL; both `:common:compileJava` and `:fabric:compileJava` executed) after
the four doc corrections.

## §7 THE IS5 SHADOW-FIX SPEC

**Status: RECONCILE (Opus, Fable depth, 2026-07-20). Verdict = REFUTE-AND-REDIRECT + DIAGNOSE-FIRST GATE.**
Three recon reports were synthesized (A = iris shadow-drive mechanism; B = why-empty; C = diagnose-first/fix-spec). Every load-bearing claim below was re-verified by re-opening the iris/sodium jars (javap) and the worktree source; the citations are this session's, not the recons'. The headline: **the Option-1 premise the probe's own javadoc and recon-A carry — "our shell drives the dest CAMERA-frustum terrain but NOT iris's SHADOW-scope terrain, so mirror `ip_driveDestTerrainSetup` for the shadow frustum" — is REFUTED by bytecode.** Iris self-drives the shadow-scope terrain, on the correct renderer, at the correct camera. The leading real cause is recon-B's: a **stale projection feeding iris's shadow CULL FRUSTUM**. But recon-C's objection survives — static analysis cannot prove that stale projection is *degenerate* (vs merely mismatched) for an unscaled portal, so the fix is written AND GATED behind a probe extension that reads the actual frustum inputs. This honors the standing NO-GUESSING rule.

### §7.0 The confirmed defect + the root cause

**The defect (probe-proven, `[IS5-SHADOW-PROBE]`).** Under a shaderpack, looking at a SUN-world (OW) dest through a cross-dim portal (nether->OW), the OW shadow depth map is EMPTY: readback min=max=mean=1.0 (res 2048, all-cleared), sodium shadow HUD terrain "C: 0/0", `renderedShadowEntities=0`, `renderedShadowBlockEntities=0`, WHILE iris is correctly OW-targeted (pipeline=IrisRenderingPipeline, getCurrentDimension=overworld, getSunAngle=27.5deg). The pack's in-shadow depth-compare returns "lit" everywhere -> full-bright, shadowless. This is a CROSS-dim capture (the probe cannot answer same-dim — it aliases the main-frame OW targets; `ShadowEmptinessProbe` javadoc lines 27-33).

**The verified architecture (both drives hit the SAME renderer — recon-A's mismatch is WRONG).**
- Our nested pass calls the full 8-arg `destRenderer.render(...)` at `SecondaryWorldRenderCore.java:1740`, which runs `addMainPass`, so iris's shadow inject (`MixinLevelRenderer.iris$renderTerrainShadows2`) re-fires for the dest.
- Iris's `ShadowRenderer.renderShadows(LevelRendererAccessor arg1, Camera arg2, CameraRenderState)` resolves its sodium world renderer from `arg1` = the renderer `render()` was invoked on (jar: `sodium$getWorldRenderer()` invokeinterface @ renderShadows off. 528), then self-drives `swr.setupTerrain(...)` (off. 630) inside `iris$beginShadowRenderListScope()`.
- **CRUX RESOLUTION (recon-A vs recon-C):** recon-A claimed cross-dim `destRenderer` != `mc.levelRenderer`, so iris's shadow drives a DIFFERENT (possibly null-RSM) SWR than our terrain drive. **FALSE.** `MyGameRenderer.java:307` (`ip_setWorldRenderer(worldRenderer)`) repoints `client.levelRenderer` to the dest secondary renderer BEFORE the render invoke. So during the nested pass `Minecraft.getInstance().levelRenderer` == `destRenderer`; our `SodiumInterface.ip_driveDestTerrainSetup` (:375, reads `Minecraft.getInstance().levelRenderer`) and iris's `renderShadows` (reads arg1 == destRenderer) resolve the **same SWR / same per-dim RSM**. Recon-A's "null/empty secondary RSM" hypothesis is refuted — same renderer; the camera pass proves that RSM's tree is populated (dest terrain renders, just full-bright).
- **Therefore `ip_driveDestTerrainSetup` is NOT missing for the shadow.** Note also it is only called for `sharedState` (same-dim) at Step 9' (`SecondaryWorldRenderCore.java:1616-1627`); cross-dim terrain is driven by the extract. Either way, iris re-drives its OWN `setupTerrain` for the shadow scope inside `render()`. A pre-render "mirror drive for the shadow frustum" would be re-culled/overwritten by iris — **dead code**. (Recon-A's own risk note reached the same correction; recon-C led with it.)

**The root cause (recon-B, verified — the shadow CULL FRUSTUM rejects all dest geometry).**
Both empty facts (terrain=0 AND entities=0) travel through DISJOINT machinery: terrain = `setupTerrain` -> sodium `renderOutOfGraph` over the shadow viewport; entities = `extractVisibleEntities` -> `EntityRenderDispatcher.shouldRender(entity, entityFrustum, ...)`. Their ONLY shared input is the frustum built by `ShadowRenderer.createShadowFrustum`:
- Jar-verified: `terrainFrustumHolder = createShadowFrustum(...)` (renderShadows off. 414-417) feeds the shadow terrain viewport (off. 563-566 -> setupTerrain off. 630); `entityFrustumHolder = createShadowFrustum(...)` (off. 849-852) feeds `extractVisibleEntities` (off. 908) and `renderEntities` (off. 1054). They are DIFFERENT `FrustumHolder` instances but produced by the SAME method reading the SAME inputs. (Recon-B's "same instance" phrasing is imprecise; the causal conclusion — a shared corrupting input — is correct.)
- The default-pack arm of `createShadowFrustum` (off. 488-571) builds `new AdvancedShadowCullingFrustum(gbufferProjection.mul(gbufferModelView), PROJECTION, lightVec, BoxCuller)` where the clipping planes come from `CapturedRenderingState.getGbufferProjection() x getGbufferModelView()` (off. 491/497/507).
- Jar-verified seed: `MixinLevelRenderer.iris$setupPipeline` (render() HEAD) sets `setGbufferModelView(arg5)` (off. 20-22) = `render()`'s viewMatrix = **destViewMatrix (CORRECT)**, and `setGbufferProjection(new Matrix4f(GameRendererStorage.sodium$getProjectionMatrix()))` (off. 25-49) = a copy of **sodium's cached projection**.
- Jar-verified sole writer: sodium's `GameRendererMixin.sodium$setProjection` is a `@WrapOperation` that `putfield projection` (off. 12) — it fires ONLY at the `ProjectionMatrixBuffer.getBuffer` INVOKE inside `GameRenderer.renderLevel`. Our dest projection install is `SecondaryWorldRenderCore.writeProjectionSlice` (:2475-2482), which calls `RenderSystem.getDevice().createBuffer(...)` DIRECTLY and never touches `ProjectionMatrixBuffer.getBuffer`. **So `sodium$getProjectionMatrix()` is never refreshed for the dest pass — iris's `gbufferProjection` for the nested shadow is stale (the main frame's projection).**

**Frustum-input elimination (strengthens B, my own pass).** For the ENTITY cull the ONLY inputs to `AdvancedShadowCullingFrustum` are: (a) `gbufferProjection x gbufferModelView`; (b) the shadow `PROJECTION` ortho; (c) the `BoxCuller` distance; (d) `lightVec`. `gbufferModelView` = destViewMatrix (correct); the `BoxCuller`/`PROJECTION` distance in `createShadowFrustum` uses `Options.getEffectiveRenderDistance()*16` (off. 74/91/284/306 — the PLAYER render distance, NOT the portal fog, so non-degenerate); `lightVec` = the OW sun (probe sun=27.5 confirms). The one remaining degenerate-capable frustum input is **`gbufferProjection`** — exactly the stale value. This narrows the cause to the projection with the boxCuller/light/modelview ruled out.

**THE UNREFUTED CAVEAT (recon-C, decisive for the gate).** `destProjection = new Matrix4f(mainCameraState.projectionMatrix)` (`SecondaryWorldRenderCore.java:1398`); sodium's cached main projection also derives from the same player projection. For an UNSCALED portal (`getExtraModelViewScaling()` == identity) the stale `gbufferProjection` ~= the correct dest projection, so `gbufferProjection x destViewMatrix` ~= the correct dest view-projection — a frustum that should PARTIALLY populate, not empty completely. A merely-mismatched frustum does not obviously produce min=max=mean=1.0 + entities=0. **Static analysis cannot prove the stale value is actually degenerate.** So recon-B is the strongest lead and identifies a genuinely real staleness bug, but the leap "stale -> causes-complete-emptiness" is a runtime claim that MUST be instrumented before we commit. (`destDrawProjection` DOES diverge from `destProjection` for SCALED portals — the staleness is unambiguously wrong there, and would be for any iris depth-reproject; but the probed portal is presumed unscaled.)

**Bottom line.** Fix target = make iris's `gbufferProjection` dest-correct for the nested pass. Written below (§7.1), but GATED behind the probe extension (§7.4) that reads the actual `gbufferProjection` and runs an `isVisible` reality test. If the probe shows the value is degenerate -> ship. If it shows the value is already ~=dest yet the frustum still rejects -> the cause is elsewhere in the frustum build or the sodium shadow SectionTree, which may require iris-internal hooks (§7.5 deep-end flag; user decision).

### §7.1 The fix (REFRAMED — NOT a shadow-scope terrain drive)

**The task's original §7.1 framing ("the shadow-scope terrain drive: method sig + body") is RETIRED as refuted** (iris self-drives; a mirror drive is dead code — §7.0). The real primary fix is the projection repoint the task filed under §7.2. It is promoted here.

**Fix: repoint sodium's cached projection to the dest draw projection for the duration of the nested `render()`.** Iris copies `sodium$getProjectionMatrix()` into `gbufferProjection` at `render()` HEAD (`iris$setupPipeline`); mutating sodium's cache to `destDrawProjection` immediately before `destRenderer.render(...)` makes that copy dest-correct, which flows into `createShadowFrustum` -> the `AdvancedShadowCullingFrustum` for BOTH the terrain and entity shadow culls.

**Feasibility (verified in-reach — NOT an iris-internal hook).** `GameRendererStorage.sodium$getProjectionMatrix()` is PUBLIC on the sodium duck (already used by iris) and returns the live backing `Matrix4f` (field `private final Matrix4f projection`; the `@WrapOperation` `putfield`s it). The value is mutable — cast `Matrix4fc`->`Matrix4f` and `set(...)` it in place, save/restore around the render. No new mixin into sodium OR iris is required (a defensive `@Accessor` getter into sodium's `GameRendererMixin` is an equally valid alternative if the cast is judged fragile).

**Placement.** `SecondaryWorldRenderCore.renderDestWorldFullPipeline`, immediately AFTER Step 7's `RenderSystem.setProjectionMatrix(writeProjectionSlice(destDrawProjection), ...)` (:1598-1601) and BEFORE the `destRenderer.render(...)` invoke (:1740), inside the existing outer try (whose `finally` begins :1750). Facade discipline: route through `SodiumInterface.invoker` (a new `ip_repointShadowProjection(Matrix4f)` / `ip_restoreShadowProjection(Object)` pair) so no sodium type appears in the core and the no-sodium build stays a no-op — mirrors the existing `ip_driveDestTerrainSetup` / `ip_onDestTerrainDrawsFinished` facade.

Sketch (facade body, `OnSodiumPresent`):

```java
// C2/IS5 — repoint sodium's cached gbuffer projection to the dest draw projection so iris's
// iris$setupPipeline copies a DEST-correct gbufferProjection into its shadow cull frustum.
// Save/restore bracketed by the caller's finally. destDrawProjection is base*bob*spin of the
// dest projection — the exact matrix the dest rasterizes with, and the matrix sodium WOULD have
// cached had render() gone through ProjectionMatrixBuffer.getBuffer.
@Override
public Object ip_repointShadowProjection(Matrix4f destDrawProjection) {
    Matrix4f cached = (Matrix4f) ((GameRendererStorage) Minecraft.getInstance().gameRenderer)
        .sodium$getProjectionMatrix();
    Matrix4f saved = new Matrix4f(cached);   // deep copy of the outgoing value
    cached.set(destDrawProjection);          // in-place mutate (iris copies it at setupPipeline)
    return saved;                            // opaque token for the restore
}

@Override
public void ip_restoreShadowProjection(Object savedToken) {
    if (savedToken == null) return;
    Matrix4f cached = (Matrix4f) ((GameRendererStorage) Minecraft.getInstance().gameRenderer)
        .sodium$getProjectionMatrix();
    cached.set((Matrix4f) savedToken);
}
```

**Shadow projection & frustum source (unchanged by the fix — for the record).** The shadow ortho `PROJECTION` (`ShadowMatrices.createOrthoMatrix`), the shadow MODELVIEW, the shadow frustum center (`getUnshiftedCameraPosition()` = `mainCamera().position()` = the dest camera, since `MyGameRenderer` `ip_setCamera(newCamera)` repoints `mainCamera`), and the `lightVec` (OW sun) are all already dest-correct and are NOT touched. Only `gbufferProjection` (the player-view planes half of `AdvancedShadowCullingFrustum`) is corrected.

**Ordering.** `iris$setupPipeline` runs at `render()` HEAD (before the `addMainPass` shadow inject), so the cache must be repointed BEFORE `render()` — the placement above satisfies this. There is no dependency on Step 9's `ip_driveDestTerrainSetup` ordering (that drives the CAMERA-frustum terrain, a separate concern; iris's shadow `setupTerrain` runs later, inside `render()`).

### §7.2 (folded into §7.1)

The projection repoint IS the fix; see §7.1. No separate shadow-scope terrain drive exists to spec.

### §7.3 The bracket / restore + the main-frame-shadow-safety proof

**Bracket.** `saved = ip_repointShadowProjection(destDrawProjection)` right before `destRenderer.render(...)`; `ip_restoreShadowProjection(saved)` in the outermost `finally` (:1750+), alongside the existing `ip_onDestTerrainDrawsFinished()` / Globals-UBO / fog restores. Throw-safe (the finally always runs); nested passes self-bracket recursively (each layer saves+restores its own token — same discipline as the Step-7 projection locals and the UBM latch reset).

**Safety proof (why the main frame's shadow is not clobbered).**
1. **Same-dim / shared-SWR frame.** The dest `render()` runs DURING the main frame; the MAIN frame's iris shadow pass already ran (its `gbufferProjection` already consumed by the main `createShadowFrustum`) BEFORE the portal nested pass. The repoint mutates sodium's cached `Matrix4f` in place and the `finally` restores the exact prior value, so any later main-frame consumer of `sodium$getProjectionMatrix()` (e.g. sodium's own main-pass projection reads, or a subsequent frame's iris `setupPipeline`) sees the original. The window is strictly [repoint .. restore] within one nested pass. This is the same in-place-mutate-then-restore contract the mod already relies on for the `SWR.lastFogParameters` five-swap and the FogStorage duck.
2. **Cross-dim frame.** Per-dim secondary renderer, but `sodium$getProjectionMatrix()` lives on the SINGLE `GameRenderer` (one instance, not per-dim), so the repoint still touches shared state — the restore is equally mandatory. Proven by the same window argument.
3. **Leak class if restore is skipped.** Without the finally-restore, the dest projection would persist in sodium's cache and the NEXT main-frame `iris$setupPipeline` would seed `gbufferProjection` from the stale DEST value -> a whole-frame main shadow warp. The finally forecloses it. (Recon-C flagged exactly this; it is the decisive belt.)

### §7.4 The probe-confirmation criterion + the self-run plan (THE GATE — diagnose-first)

The current `ShadowEmptinessProbe` proves EMPTY but does NOT read WHICH frustum input is at fault. **Before shipping §7.1, extend the probe to read the shadow-cull inputs, so we confirm B (degenerate `gbufferProjection`) rather than guess it.** All reads stay reflection-only, lever-gated (`-Dseamlessportals.shadowProbe`), 1Hz, self-disarming — the existing binding discipline.

Add to `endPass()` (post-render; these are persistent instance/static fields, not tail-nulled statics — verify each handle):
- **[5] gbufferProjection value + identity.** Read `CapturedRenderingState.INSTANCE.getGbufferProjection()` (javap: `Matrix4fc getGbufferProjection()`), log its 16 floats + `identityHashCode`, and compare against `destDrawProjection` and `mainCameraState.projectionMatrix`. Also read `((GameRendererStorage) mc.gameRenderer).sodium$getProjectionMatrix()`. **Decisive:** a value that is NOT a sane dest perspective (degenerate near/far, wrong aspect, or plainly != destDrawProjection) confirms B.
- **[6] FRUSTUM-REALITY test.** Reflect `ShadowRenderer.terrainFrustumHolder` -> `getFrustum()` (retains the last shadow frustum post-render) and call `Frustum.isVisible(AABB)` / `cubeInFrustum` against the AABB of a KNOWN-loaded dest section the CAMERA pass drew (e.g. the dest camera's own section). **Decisive:** `false` for a plainly-in-view section => the shadow frustum is degenerate (-> B, ship the repoint). Also log `terrainFrustumHolder.getDistanceInfo()` / `getCullingInfo()` (the pack's frustum-mode strings) to confirm the AdvancedShadowCullingFrustum arm (vs NonCulling/BoxCulling/CullEverything — a `CullEverythingFrustum` would be a separate pack-config cause).
- **[7] SEARCH-DISTANCE.** Log `((GameRendererStorage) mc.gameRenderer).sodium$getFogParameters()` (alpha, cullDistance, renderDistanceEnd) so a collapsed sodium shadow search radius is ruled in/out for the terrain half (does NOT affect the entity half).

**CONFIRMATION CRITERION (unchanged, decisive).** The fix is correct iff, at a nether->OW cross-dim portal under a shaderpack, the probe's [4] depth readback flips from ALL-CLEARED (min~=max~=1.0) to POPULATED (min<1.0), AND [2] `renderedShadowEntities`/`renderedShadowBlockEntities` go non-zero when entities/BEs stand in the OW shadow frustum.

**Decision tree from the extended probe (run BEFORE committing §7.1):**
- [5] gbufferProjection degenerate/non-dest AND [6] isVisible rejects an in-view section => **B CONFIRMED** -> ship §7.1, re-run, expect the [4] flip.
- [5] gbufferProjection ~= sane dest yet [6] isVisible STILL rejects => the defect is elsewhere in the frustum build (back/edge planes, lightVec, or the `PROJECTION` ortho) OR in the sodium shadow SectionTree population => §7.1 will NOT move the probe; **re-open toward the deep end (§7.5).**
- [6] isVisible PASSES (frustum fine) yet terrain "C: 0/0" => the fault is downstream of the frustum (sodium shadow render-list / SectionTree), NOT the projection => deep end.

**Self-run plan.** Worktree `is5-shadow`, iris 1.11.2 + sodium 0.9.1 + a shadow pack, `-Dseamlessportals.shadowProbe=true`. Stand at a nether->OW portal (dest = OW sun-world). (1) Capture the baseline block (already have: EMPTY). (2) Land the [5]/[6]/[7] probe extension, re-capture, READ the decision tree. (3) If B confirmed, land §7.1, re-capture, confirm the [4] flip + non-zero counts + visible shadows in-world. (4) Regression: a same-dim OW->OW portal frame + a main-frame shadow check (no warp) to prove §7.3's restore. All screenshots via the existing gametest lever; suite stays byte-identical at the default.

### §7.5 Risks + the deep-Opus verify plan

**Risks / adversarial notes.**
1. **The fix is CONDITIONAL.** If [5]/[6] show `gbufferProjection` is already ~=dest (recon-C's unscaled case), §7.1 is a near no-op and will NOT flip [4]. A green "no crash + no warp" build could be mistaken for a fix — ONLY the [4] depth flip + non-zero counts prove correctness. Do not ship §7.1 as "the fix" until the probe confirms B.
2. **Deep-end reclassification (task NOTE).** If the probe refutes B, the surviving candidates — a degenerate `PROJECTION` ortho, a bad `lightVec`, or an unpopulated sodium shadow SectionTree / `ShadowRenderRegion` shadow-list — live INSIDE iris's shadow flow. Correcting those would require a mixin INTO iris's shadow render (e.g. wrapping `createShadowFrustum` or the shadow-list swap), a materially larger and riskier change than the projection repoint, and one the D1 context swap deliberately does not touch. **That is a user decision — surface it before committing.** Say so explicitly rather than forcing a clean secondary-drive that does not exist.
3. **In-place mutate fragility.** `sodium$setProjection` `putfield`s a NEW `Matrix4f` reference each main capture; our `set(...)`+restore acts on the CURRENT object within one nested pass — sound within the frame, but if a future sodium revision returns a defensive copy from `sodium$getProjectionMatrix()` the in-place mutate would silently no-op. The `@Accessor`-getter alternative (§7.1) is more robust; choose it if the cast is judged fragile. Either way the value MUST be restored in the finally (§7.3).
4. **[6] handle liveness.** `renderShadows` nulls some statics at its tail (`visibleBlockEntities` off. 1420). Verify each new reflective handle ([5] `getGbufferProjection`, [6] `terrainFrustumHolder`) is a persistent instance/static field NOT tail-nulled, or the probe misleads. `terrainFrustumHolder` is an instance field re-`putfield`ed each pass (not nulled) — safe; confirm at implementation.
5. **Pack-arm dependency.** `createShadowFrustum` has NonCulling/BoxCulling/CullEverything arms besides the default Advanced arm. The [6] `getCullingInfo` dump identifies the tested pack's arm; a `CullEverythingFrustum` (pack config) would be an entirely separate cause. Confirm the Advanced arm before attributing to `gbufferProjection`.
6. **Scaled-portal correctness (independent win).** Even if unscaled proves B false, the repoint is a genuine correctness fix for SCALED portals (`destDrawProjection` diverges from the stale main projection there) — worth keeping as a latent-bug fix regardless, but NOT as "the IS5 shadow fix" unless [4] flips.

**Deep-Opus verify plan (6 verifiers + 3 judges + fold).**
- **V1 (jar-symbol re-audit):** independently re-javap every iris/sodium symbol this spec relies on — `renderShadows` swr resolution (off. 528), `setupTerrain` (off. 630), `createShadowFrustum` gbuffer read (off. 488-571), `iris$setupPipeline` gbuffer seed (off. 20-49), `GameRendererMixin.sodium$setProjection` putfield, `GameRendererStorage.sodium$getProjectionMatrix` return type. Confirm no naming assumption survives.
- **V2 (renderer-identity):** re-prove `MyGameRenderer.java:307` repoints `client.levelRenderer` to the dest for BOTH the same-dim and cross-dim paths through `renderDestWorldFullPipeline`, so both drives hit one SWR. Attack the claim that a cross-dim path could bypass the repoint.
- **V3 (frame-ordering):** establish what `sodium$getProjectionMatrix()` actually holds at nested `iris$setupPipeline` time — does the main `renderLevel` capture fire before the portal nested passes? Determines whether the stale value is the main projection (~=dest, benign) or an older/degenerate one (the B-confirming case). Name the instrument if unresolved statically.
- **V4 (shared-input completeness):** adversarially confirm the terrain AND entity shadow culls share ONLY the `createShadowFrustum` output and that its non-projection inputs (BoxCuller=player-RD, PROJECTION ortho, lightVec) are non-degenerate — i.e. `gbufferProjection` is the sole degenerate-capable input. Try to find a second shared corrupting input.
- **V5 (bracket-safety):** prove the §7.3 save/restore leaves the main frame's `gbufferProjection` and every other `sodium$getProjectionMatrix()` consumer untouched after the pass, under nesting and under same-dim shared-SWR. Attack for a leak path.
- **V6 (probe-validity):** verify the [5]/[6]/[7] extension reads live, non-tail-nulled handles and that the [4]-flip criterion is truly decisive (not confounded by pack arm or entity absence). Confirm the isVisible AABB choice is a section the camera pass provably drew.
- **Judges (3):** J1 rules on B-vs-C (is the projection the cause, or must the probe decide? — grade the gate). J2 rules on the refutation of recon-A (shadow drive not missing; renderer identity). J3 rules on deep-end classification (does any live probe outcome force an iris-internal mixin, and is that correctly surfaced as a user decision?).
- **Fold:** reconcile verifier catches + judge verdicts into a corrections list; re-verify each correction; only then implement the probe extension, run the gate, and (conditionally) land §7.1.
