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
