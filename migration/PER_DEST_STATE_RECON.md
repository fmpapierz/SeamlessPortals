# RECON DOSSIER (FINAL) — Per-dest persistent iris state: ACT volume + TAA history

**Adjudicated fold of the draft synthesis + 2 adversarial verifiers + 14 independent checks run by this agent.** Adjudicator-run evidence is marked **[FOLD]**. Every verifier point is dispositioned in §1.

---

## 0. HEADLINE — READ THIS BEFORE ANYTHING ELSE

**The draft's feasibility verdict was right about the wrong problem.** Binding-swap feasibility survived every attack: the ACT image bindings are genuinely late-bound and genuinely swappable. But three findings that surfaced only under adversarial review move the real blocker somewhere else entirely:

1. **The blocker is UNIFORM state, not texture state.** Complementary reprojects both the ACT volume (`shadowcomp.glsl:81-82`) and the TAA history (`taa.glsl:60-66`) from `previousCameraPosition`. During the nested dest render that value is the **main** camera for portal 1 and the **preceding portal's dest** camera for portals 2..k. Iris exposes **no read path and no write path** for it: `CameraUniforms$CameraPositionTracker` is a package-private class held in **no field anywhere in the 963-class jar** — only captured inside invokedynamic lambdas. Our own shipped IS5-PH heal never reads it either; its sole instrument is `getFrameUpdateNotifier().onNewFrame()`, a global one-deep shift that samples the **live** camera. **[FOLD, §5 H1]** Per-dest storage without per-dest reprojection produces a volume that shears by the portal offset every frame and never converges. *Both halves depend on this. Neither can be designed until it is settled.*

2. **A cap large enough to prevent LRU thrash is a cap larger than VRAM allows.** At the user's `COLORED_LIGHTING=512` each dest floodfill pair is **1.00 GiB**, on a per-dimension iris baseline that is ~2.4 GiB (not the draft's 1.125 GiB — the 773 MiB WSR SSBO and 32 MiB `wsr_img` were omitted). With any cap below the in-view portal count, `renderPortals`' stable-order loop gives a **0% hit rate forever**, every volume is re-zeroed every frame, and — because the ACT contribution is multiplicative on `lightVolume.a` (`mainLighting.glsl:394-395`) — every window goes **permanently ACT-dark**, which is strictly worse than today's source-seeded light.

3. **A free natural experiment may already refute half 1, and nobody has looked.** Cross-dim dests run on their own per-dimension iris pipeline with their own floodfill pair — i.e. **cross-dim windows are already "half 1, shipped."** If a cross-dim window today shows *no* colored light, the blocker is the nested shadow voxelization (the never-diagnosed `C:0/0` ledger item, `PORTAL_VIEW_POLISH_HANDOFF.md:151`), not storage, and half 1 dies before a line is written. This costs zero code: look through one Nether portal.

**Honest verdict:** Half 1 (ACT volume) is **mechanically possible but currently unshippable** at the user's settings — VRAM-fatal past one destination, reprojection-broken past portal 1, and resting on an unverified assumption a five-minute look-test can kill. Half 2 (TAA history) is **cheap and mechanically settled** (the MAIN-vs-ALT unknown is now closed statically), but shares blocker #1 and would ghost worse than today's zeroing if shipped without it. **The commission's "two halves, one arc, shared binding-swap machinery" framing is refuted twice over: the halves need different mechanisms, and what they actually share is a uniform-state problem neither of them was scoped to solve.**

---

## 1. ADJUDICATION LOG

### Verifier 1 — evidence lens

| Point | Ruling | Why |
|---|---|---|
| V1 Part A (independent re-verification of late binding, 2-constant-pool scan, negative greps) | **ACCEPT** | Reproduces the draft's load-bearing claim line-for-line. No change to §2(a). |
| V1-F1 — A2 "live-corroborated" is a misattributed pre-registered protocol | **ACCEPT** | **[FOLD] verified:** `POLISH_SESSION_NOTES.md:64` opens `LIVE PROTOCOL:` and the cross-dim clause at `:66-67` sits inside it. A2 is **DERIVED, never observed**. Downgraded throughout; drives gate G0. |
| V1-F2 — P0's `noopHits` rule does not discriminate | **ACCEPT** | **[FOLD] verified** `IPGlobal.java:227-233` verbatim: *"during a portal-on-screen scene it means nested passes are not reaching renderShadows (diagnostic)"*. P0 re-specified with the **visual** leg as decision variable + a positive pipeline-identity marker. |
| V1-F3 — `CameraPositionTracker` has no access path | **ACCEPT + ESCALATE** | **[FOLD] verified:** javap shows a package-private class with a package-private ctor; `grep -rl CameraPositionTracker` over 963 classes → 3 files, none holding it in a field. **[FOLD] further:** our own `IrisInterface.healPreviousFrameUniforms()` (`IrisInterface.java:198-237`) reaches it only via `getFrameUpdateNotifier().onNewFrame()`. Promoted to the engagement's #1 hazard and gate G1. |
| V1-F4 — P3 cannot answer its own headline | **ACCEPT** | Re-specified with a torch-control A/B as the observable (§6 P6). |
| V1-F5 — P1 cannot settle gap 4 (no per-program attribution) | **ACCEPT** | Probe re-hooked at `ProgramImages.update()` HEAD so the identity hash gives per-program attribution. |
| V1-F6 — P8 over-generalizes one JIT shape | **ACCEPT (narrowed)** | **[FOLD]:** `POLISH_SESSION_NOTES.md:59-60` records *"reflection legal on Temurin 25 (instance finals settable; unnamed module)"* — legality is already project-proven for an instance final **reference**. Open part narrows to C2 folding of a private final **int**. |
| V1-F7 — line numbers; R4 right, synthesis manufactured a ±2 | **ACCEPT** | **[FOLD] verified** by direct read: `:416 clearForDestPass` / `:417 try` / `:418 renderWorldFullPipeline` / `:420 finally` / `:421 bump`. The hedge is deleted. |
| V1-F8 — discriminator is `NamespacedId`, not "cross-dim" | **ACCEPT** | Skybox fallback in `Iris.getCurrentDimension()` means two custom dims can share a pipeline. All scope language re-keyed to **same-`NamespacedId` / same pipeline instance**. |
| V1-F9 — allocation clear requirement dropped | **ACCEPT** | **[FOLD] verified:** `GlImage.setup` ends `181: invokevirtual #111 clear:()V`. The ctor route self-zeroes; the §3.5-mandated DSA route does not. |
| V1-F10 — DSA drops 7 tex params ⇒ mipmap-incomplete ⇒ sampler reads black | **ACCEPT** | **[FOLD] verified** the full parameter list in `setup` bytecode incl. `33085 GL_TEXTURE_MAX_LEVEL = 0`. Highest-value catch in either report: the failure is silent and mimics "the swap didn't take". |
| V1-F11 — no GPU-time row; dispatch cost dropped | **ACCEPT** | Added to §4 and to the user-decision list. |
| V1-F12 — A3 removes the premise of the shipped voxel-residue argument | **ACCEPT + STRENGTHEN** | **[FOLD] new evidence:** `voxel_sampler` is read by **gbuffer material shaders** — `connectedGlass.glsl:64,79,91,95,103`, `wavingBlocks.glsl:164-165`, `endPortalEffect.glsl:83`, `netherPortal.glsl:66` — not only by the compute. Residue is a real read path (but **pre-existing**: the nested shadow pass already dest-writes it today). See A3′ below, which *reverses the polarity of the fix*. |
| V1-F13 — VRAM baseline understated by ~805 MiB | **ACCEPT** | **[FOLD] verified** `shaders.properties:199` (`wsr_img 512 64 512`), `:217 bufferObject.0 = 810549248` (= 773.0 MiB), `:218 wsr_lod_img`, all live at `WSR=1 & COLORED_LIGHTING>=512`. |
| V1-F14 — half-2 cost row conditional on P6 | **ACCEPT → then RESOLVED** | The conditionality is removed because V2-F7 settles P6 statically (below). Cost restated with the correct copy count. |
| V1-F15 — 3.4 vs 3.3 GiB/s | **ACCEPT** | Table was right; A5's headline was a rounding slip. All other arithmetic re-checked and correct. |
| V1-F16 — `SodiumContextRegistry` 16/layer misused as a VRAM scenario | **ACCEPT** | Row deleted; it is an over-provisioned CPU-cache ceiling, not a view estimate. |
| V1-F17 — `image.*` rows are inside a preprocessor conditional; 768/1024 tiers exist | **ACCEPT** | **[FOLD] verified** `shaders.properties:161-188`: seven tiers, top tier `1024 256 1024` = **2.0 GiB/pair**. |
| V1-F18 — `SwapPass` ctor is private | **ACCEPT** | Signature block corrected. |

### Verifier 2 — design-failure lens

| Point | Ruling | Why |
|---|---|---|
| V2-F1 — LRU thrash ⇒ 0% hit rate ⇒ permanently ACT-dark windows | **ACCEPT — top design finding** | **[FOLD] verified** the multiplicative form at `mainLighting.glsl:394-395` (V2 cited 396-399; my count is 394-395 — substance unchanged): `lightmapXM = max(lightmapXM, mix(lightmapXM, 10.0, lightVolume.a)); specialLighting *= 1.0 + 50.0 * lightVolume.a;` plus `:390 specialLighting = lightVolume.rgb`. `a == 0` ⇒ ACT contribution vanishes. Drives the cap-policy correction in §3.6. |
| V2-F2 — portals are skipped on arbitrary frames but the pack reprojects by exactly one frame | **ACCEPT** | **[FOLD] verified** `IPGlobal.java:499 offsetOcclusionQuery = true` and the last-frame consumption at `PortalRenderInfo.java:216`. Per-dest state needs a frame-index and a zero-on-gap rule. |
| V2-F3 — for portals 2..k, `previousCameraPosition` is the *preceding portal's* dest camera | **ACCEPT** | Follows necessarily from the one-deep shift register + the unconditional nested `beginLevelRendering` tick, both stated verbatim in our own `IrisInterface.java:175-183`. Draft H1 was correct only for k=1. Fused with V1-F3 into hazard H1. |
| V2-F4 — `preparePipeline` resets `COUNTER`/`TIMER` on a cache miss, mid-nested-render | **ACCEPT** | **[FOLD] javap-verified:** offsets `10: ifne 109` (hit path skips), `13-16 COUNTER.reset()`, `19-22 TIMER.reset()`, `41-55 pipelineFactory.apply` — full pipeline construction inside our bracket on a first cross-dim look. Re-phases `framemod2/4/8/600` globally. Real, latent, and made load-bearing by half 1. |
| V2-F5 — per-dimension VRAM ≈2.4 GiB, not 1.125 GiB | **ACCEPT (partly UNVERIFIED)** | SSBO + `wsr_img` exact from the properties file. Shadow-map (~256 MiB) and colortex (~200 MiB) figures are **estimates**, labelled as such in §4. |
| V2-F6 — P5 tests allocation, not residency; partial-allocation split-brain | **ACCEPT** | P5 replaced by a steady-state residency probe measuring p99 frame time. All-or-nothing pair allocation added as a hard rule (H6). |
| V2-F7 — **P6 is unnecessary; the nested render does reach `renderFinalPass()`** | **ACCEPT — verified end-to-end by me** | **[FOLD] chain, all read this session:** `IrisCompatOn262Renderer:418 → MyGameRenderer.renderWorldFullPipeline:518 → switchAndRenderTheWorldFullPipeline (client.level = newWorld) → SecondaryWorldRenderCore:1861 destRenderer.render(8 args) → MixinLevelRenderer.iris$endLevelRender offsets 73-77 pipeline.finalizeLevelRendering() → IrisRenderingPipeline.finalizeLevelRendering offsets 10-20: compositeRenderer.renderAll(); finalPassRenderer.renderFinalPass()`. **P6 DELETED. Gap b.1 CLOSED: the dest's canonical fresh history is in `getMainTexture()`.** Also folded: the nested `endLevelRender` runs `HandRenderer.renderTranslucent` with `gameRenderer.mainCamera()` (offsets 0-38). |
| V2-F8 — cross-dim is a free A/B of half 1's whole hypothesis; the `C:0/0` item would refute it | **ACCEPT — PROMOTED TO GATE G0** | **[FOLD] verified** `PORTAL_VIEW_POLISH_HANDOFF.md:151` verbatim: *"never diagnosed on the current stack"*, still open at `POLISH_SESSION_NOTES.md:8`. Cheapest possible falsifier; runs first. |
| V2-F9 — A5's MAIN-only contradicts the guard's phase-agnostic discipline; `fxaa.glsl:188` is a second `colortex2` consumer | **ACCEPT** | **[FOLD] verified** `fxaa.glsl:188`. Half-2 swap-in restated as **write BOTH sides, read MAIN back** (3 copies), and the FXAA behaviour change is pre-registered. |
| V2-F10 — half 1 is resize-immune (`GlImage.updateNewSize` base = `0: return`; ACT images are `isRelative=false`) | **ACCEPT** | Independently javap'd by both verifiers; `isRelative` = 6th token = `false` on all three rows. **H10 now applies to half 2 only** — removes a whole subsystem from half 1's scope. |
| V2-F11 — nesting cannot occur; depth hard-capped at 1 | **ACCEPT** | `doRenderPortal:296` and `onBeforeHandRendering:176` both early-return on `PortalRendering.isRendering()`. No re-entrant/counted swap machinery needed. |
| V2-F12 — pipeline **destroy** cannot land mid-swap; **creation** can | **ACCEPT with correction** | **[FOLD]:** only 3 classes reference `destroyPipeline` — `Iris`, `MixinMinecraft_PipelineManagement`, `PipelineManager`. V2's `TextureFormatLoader` route is indirect (via `Iris.reload`). The "no mid-frame destroy" conclusion is **DERIVED from those call sites**, not proven exhaustively; keep the cheap per-frame identity re-resolve regardless. |
| V2-F13 — relocating IS5-FF narrows coverage; counter units change silently | **ACCEPT** | **[FOLD] verified** `PortalRenderer.java:285-289` (`renderPortalContent` returns when `layer > maxPortalLayer`) and the layer-0 fallback at `IrisCompatOn262Renderer.java:389-395`. Enumeration required before relocation. |

### Adjudicator-added folds

| # | Finding |
|---|---|
| **FOLD-1** | **The swap bracket has an exact in-tree precedent, and the same-dim nested render runs on the MAIN `LevelRenderer` instance.** `ClientWorldLoader.getWorldRenderer` (`:515-538`) returns from `WORLD_RENDERER_MAP`, whose active-dim entry *is* the object installed as `mc.levelRenderer` (`:543-549`). So iris's `iris$endLevelRender` `this.pipeline = null` hits the main renderer's woven field mid-frame — and `MyGameRenderer.java:626 IrisInterface.invoker.setPipeline(worldRenderer, null)` / `:690 setPipeline(worldRenderer, irisPipeline)` already brackets it. Copy that shape. |
| **FOLD-2** | **A3′ — do NOT duplicate `voxel_img`, and the reason is stronger than "it's redundant."** Under the recommended `getId()`-override mechanism, `ImageClearPass.execute() → GlImage.clear() → getGlId()` is **not** redirected. So a swapped `voxel_img` would have the nested `beginLevelRendering` clear the **original** while the nested shadow pass writes **ours** — leaving the shared voxel volume **cleared-but-never-rewritten** for the rest of the main frame, breaking `connectedGlass`/`wavingBlocks`/`netherPortal`/`endPortalEffect` in the post-anchor hand pass. Duplicating voxel is a **net regression**, not merely wasteful. Saves 128 MiB/dest and closes the hazard. |
| **FOLD-3** | **Per-dest previous-camera has no known instrument.** `onNewFrame()` shifts `previous ← current` and sets `current ← getUnshiftedCameraPosition()` (the live camera). There is no way to *install* a specific previous value. Options are (a) a new mixin on the package-private `CameraUniforms$CameraPositionTracker` adding accessors + per-dest slots, (b) interception at `CameraUniforms.addCameraUniforms`, (c) accept broken reprojection. All three are **unrecon'd**. This is gate G1. |
| **FOLD-4** | Temurin-25 instance-final reflection legality is already project-proven (`POLISH_SESSION_NOTES.md:59-60`), narrowing H13 to primitive-int JIT folding only. |

---

## 2. THE MECHANISM MAP (corrected)

### 2.1 Half 1 — ACT images: the seam is real

```
Iris.getPipelineManager().getPipelineNullable()  -> IrisRenderingPipeline
  └─ REFLECT: private final Set<GlImage> customImages     [NO accessor exists: grep "getCustomImages" over 963 classes -> 0]
       └─ GlImage.getId()  ─────────────── THE SEAM (virtual; methodref present in exactly 2 constant pools)
             ├─ IrisImages.addCustomImages   -> ImageHolder.addTextureImage(image::getId, fmt, name)
             │     -> ImageBinding.update():  5: getfield textureID  8: IntSupplier.getAsInt  22: bindImageTexture
             └─ IrisSamplers.addCustomImages -> addDynamicSampler(TEXTURE_3D, image::getId, null, samplerName)
                   -> SamplerBinding.updateSampler(): 55/58 getAsInt  63: bindTextureToUnit
       └─ ProgramImages.update() / ProgramSamplers.update():  unconditional iteration, NO short-circuit
       └─ re-run at ComputeProgram.use() and ExtendedShader.iris$setupState()
NOT the seam:  GlImage.clear(), destroyInternal(), updateNewSize(), ImageClearPass.execute()  -> all use protected getGlId()
NOT the seam:  mutating the Set (the lambdas captured instances)
No bindless path anywhere: grep Bindless|glGetTextureHandle over 963 classes -> 0
```
`beginLevelRendering` offsets `112 GLDebug.pushGroup("Clear textures")` → `116 getfield clearImages` → `124 forEach` is the **first real work** of every level render, nested included. `clear=false` images get no `ImageClearPass` — which is exactly why the floodfill pair is the persistent poison carrier.

### 2.2 The pack contract (verbatim, and conditional)

```
shaders.properties:177  #elif COLORED_LIGHTING == 512          <-- the rows below are INSIDE this branch
shaders.properties:178  image.voxel_img          = voxel_sampler          red_integer r16ui   unsigned_int true  false 512 256 512
shaders.properties:179  image.floodfill_img      = floodfill_sampler      rgba        rgba16f half_float  false false 512 256 512
shaders.properties:180  image.floodfill_img_copy = floodfill_sampler_copy rgba        rgba16f half_float  false false 512 256 512
shaders.properties:199  image.wsr_img            = wsr_sampler            red_integer r16ui   unsigned_int true  false 512 64 512
shaders.properties:217  bufferObject.0 = 810549248     // 773.0 MiB WSR SSBO — PER PIPELINE, active at WSR=1 & CL>=512
shaders.properties:240  uniform.float.framemod2 = frameCounter % 2
```
Seven `COLORED_LIGHTING` tiers exist (`:161-188`); the top two are `768 256 768` (1.5 GiB/pair) and `1024 256 1024` (2.0 GiB/pair). Token grammar from `ShaderProperties.lambda$new$49`: `[0]=samplerName, [1]=PixelFormat, [2]=InternalTextureFormat, [3]=PixelType, [4]=clear, [5]=isRelative, [6..8]=w,h,d`. Max 16 custom images.

**Writers:** `shadowcomp.glsl:109/111` (behind-player copy), `:149/151` (main store); `lightVoxelization.glsl:416` (voxel, from `shadow.glsl:249/323`).
**Readers — floodfill:** `lightVoxelization.glsl:100/102` (parity select) and `shadowcomp.glsl:88/90/129/132/135/138`; consumers `mainLighting.glsl:388`, `coloredLightFog.glsl:19`, `worldSpaceRef.glsl:172,307`.
**Readers — voxel [FOLD, new]:** `shadowcomp.glsl:120`, `lightVoxelization.glsl:31/35/58-60/81`, **and gbuffer material paths** `connectedGlass.glsl:64,79,91,95,103`, `wavingBlocks.glsl:164-165`, `endPortalEffect.glsl:83`, `netherPortal.glsl:66`.
**Program inventory:** exactly three `.csh` (`world-1/`, `world0/`, `world1/shadowcomp.csh`); **no `shadowcomp.fsh`** ⇒ `ShadowCompositeRenderer` builds one `ComputeOnlyPass` and `renderAll()` `continue`s past the graphical branch (offsets 212-220). **IS5-FF today costs exactly one thing: the flood-fill dispatch.**
**Parity:** `frameCounter` advances only on the outer frame (`MixinGameRenderer.iris$startFrame`); `IPGlobal.irisPerFrameRefresh` is **default FALSE / retired** (`IPGlobal.java:258`) — **but see H4: `preparePipeline` can `reset()` it mid-nested-render.**

### 2.3 Half 2 — colortex: reads late-bound, writes baked

```
READ :  IrisSamplers.lambda$addRenderTargetSamplers$0: set.contains(i) ? getAltTexture() : getMainTexture()
WRITE:  RenderTargets.createColorFramebuffer(...) -> GlFramebuffer.addColorAttachment(idx, id)   [BAKED at ctor/resize]
        RenderTarget.mainTexture / altTexture : private FINAL int, no setter
FLIP :  construction-time only (BufferFlipper never stored; flip() called only in CompositeRenderer.<init>)
END   : FinalPassRenderer$SwapPass  from = createColorFramebuffer(ImmutableSet.of(), {i})  [ALT]
                                    targetTexture = get(i).getMainTexture()
        renderFinalPass(): from.bind(); _bindTexture(targetTexture); glCopyTexSubImage2D
```
⇒ a `RenderTarget`-object or id swap makes the pack **read dest and write main** — worse than today's zeroing. **The only viable half-2 mechanism is content copy** into iris's existing ids, the technique already in production at `IrisTemporalTargetGuard.copy()` (`glCopyImageSubData`, binds nothing).

**Pack side:** zero `flip.*` directives in the 275-line properties file — persistence is `clear=false` + the SwapPass. `colortex2` is the TAA history, written by **exactly one** DRAWBUFFERS directive pack-wide (`composite6.glsl:43 /* DRAWBUFFERS:32 */`); read at `taa.glsl:198/200` **and** `fxaa.glsl:188` (the second consumer V2 caught). No prev-depth/velocity buffer — reprojection is uniform-driven. Zero-history sentinel: `taa.glsl:203`. **TAA is ON** (`lib/common.glsl:155 #define TAA_MODE 1`, no sidecar override) — the older TAA_MODE=0 note is dead. Persistent set: colortex **1,2,4,5,7**; colortex1/7 are half-res (`REFLECTION_RES 0.5`).

---

## 3. THE SWAP BRACKET

### 3.1 Location (line numbers verified)

`IrisCompatOn262Renderer.invokeWorldRendering` — the only full-pipeline nested render in the tree:

```java
416:        IrisTemporalTargetGuard.clearForDestPass();     // <-- HALF 2 REPLACES THIS; HALF 1 SWAPS IN HERE
417:        try {
418:            MyGameRenderer.renderWorldFullPipeline(worldRenderInfo);
419:        }
420:        finally {
421:            IrisInterface.invoker.bumpPerFrameUniformCounter();   // <-- SWAP-OUT goes BEFORE this
422:        }
```
Swap-in immediately before `try`; swap-out as the **first** statement of the `finally`. The layer-0 branch at `:389-395` (`CrossPortalViewRendering`/`GuiPortalRendering` → `renderWorldNew`) returns before this point and is **outside** the bracket — as it is outside today's IS5-FF window (V2-F13; disposition required).

### 3.2 Dest identity, zero new plumbing

`PortalRendering.getRenderingPortal()` (`:89`), `worldRenderInfo.description` (== `portal.getUUID()`), `worldRenderInfo.world.dimension()`, `worldRenderInfo.cameraPos`. **Recommended key: `record DestKey(UUID portalId, int layer)`** — the shape already in production at `SodiumContextRegistry.java:108`. Not dimension (the volume is camera-block-anchored: `SceneToVoxel(p) = p + cameraPositionBestFract + 0.5*voxelVolumeSize`, `lightVoxelization.glsl:16-18` — every camera indexes the same texels). Not camera position (use it as an invalidation signal only). Not layer alone (always 1 here).

### 3.3 Precedent to copy [FOLD]

`MyGameRenderer.java:614` `Object irisPipeline = IrisInterface.invoker.getPipeline(worldRenderer);` → `:626 setPipeline(worldRenderer, null)` → nested render → `:690 setPipeline(worldRenderer, irisPipeline)` in the outermost `finally`. Same capture/mutate/restore shape, same bracket, already live-proven.

### 3.4 IS5-FF must become conditional and per-portal

Today's `install()`/`uninstall()` wrap the whole portal phase (`:252` / `:259`) and resolve the **main** pipeline via `Iris.getPipelineManager().getPipelineNullable()` — which is why cross-dim nested renders are structurally unsuppressed. Half 1 needs un-suppression **only while a dest volume is actually swapped in**. Requirements:
- Relocate install/uninstall into `invokeWorldRendering` preserving F1 ordering (install first in `try`, uninstall first in `finally`), **or** nest an `uninstall/…/install` pair — `install()` early-returns on `swappedOn != null` and `uninstall()` nulls both handles in a `finally`, so both shapes re-arm correctly.
- **Hard rule:** any portal whose swap failed (alloc refused, not admitted, pipeline identity changed) falls back to **suppressed**. Un-suppressing without a swap is the live-proven lava phantom.
- Enumerate every path where `doRenderPortal` runs without `invokeWorldRendering` (at minimum `maxPortalLayer = 0` and `RenderStates.isLaggy`, via `PortalRenderer.java:288`).
- `nestedShadowCompositeSuppressCount` changes units (anchor-frames → portal-installs, ×k). Rewrite the javadoc at `IPGlobal.java:227-233` **and** the live-round protocol in the same commit, or the A/B is unjudgeable.

### 3.5 IS5-G replacement (half 2)

`clearForDestPass()` (`:248-292`) is replaced, not supplemented. Three structural changes:
1. Re-key `private static final Map<Integer,int[]> scratch` (`:104`) to `Map<DestKey, Map<Integer,int[]>>`; `clearForDestPass()` currently takes no arguments.
2. **Add a write-back anchor that does not exist today.** Current bracket is `save() → [clear, dest render]×k → restore()`; `restore()` (`:201`) discards whatever the dest wrote. A third anchor is required immediately after `renderWorldFullPipeline`, inside the same `try/finally`.
3. Keep the zero path as the cold-start/invalid fallback — `taa.glsl:203` makes zero the correct "no history" sentinel.
Swap-in writes **both** main and alt (phase-agnostic, per the guard's own `:59-61` reasoning); write-back reads **MAIN** (now settled — §1 V2-F7).

### 3.6 Cap policy — thrash-proof by construction [FOLD, replaces "LRU"]

An access-ordered LRU is refuted (V2-F1). Use a **sticky, first-come, fixed-slot** assignment:
- N slots, N small (1 or 2). A slot is claimed by a `DestKey` and **is not released on capacity pressure**.
- A portal whose key does not own a slot falls back to **suppressed** (today's behaviour) — stably, frame after frame, so the same windows keep their volumes and the same windows keep today's look.
- Release only on absence (key not seen for M frames), teardown, or pipeline-identity change.
- **Never** evict on access. Hit rate is then 100% for the slot owners and 0% for the rest — no thrash, no per-frame GiB churn, no permanently-dark windows.

### 3.7 House conventions (unchanged, all verified)

Lever quad (`IPGlobal.java:431-451`, C3-BLOOM shape); probe lever default-OFF plain `Boolean.getBoolean` with a 1 Hz-gated consumer (render-thread log4j stall rule); `-P` passthrough in **both** gradle blocks (`fabric/build.gradle:257-267` clientSodium **and** `:392-397` the 8-leg suite — lever-row parity); restore discipline per `IrisShadowCompositeSuppressor` (stash-before-mutate, one throwing op, plain-assign commit, catch nulls both, restore keyed on the saved handle, **permanent `broken` disarm latch**, never throw into the render path, once-only log latches + a named LIVENESS line); idempotent teardown on `IPCGlobal.CLIENT_CLEANUP_EVENT` and `onSwitchedAway()` (double-call is normal).

**Allocation discipline — CORRECTED (V1-F9/F10). Pick ONE and state it:**
- **(a) `new GlImage(...)`** — self-parameterises *and* self-clears (`setup` ends `181: clear()`), but binds the active 3D texture unit during construction.
- **(b) DSA** (`glCreateTextures` + `glTextureStorage3D`, error-drained, returns 0 on reject) — then you **must** replicate all seven parameter calls **and** issue an explicit `glClearTexImage`. Omitting `GL_TEXTURE_MAX_LEVEL = 0` with a 1-level immutable store leaves the texture **mipmap-incomplete**: `imageStore` writes succeed while every `texture()`/`texelFetch` returns `(0,0,0,1)`. The observable is "dest volume written every frame, window shows no colored light" — indistinguishable from "the swap didn't take."

**Pair atomicity:** allocate `floodfill_img` and `floodfill_img_copy` all-or-nothing. A half-allocated pair with the swap armed makes the shader write dest and read main on alternate frames (parity selector, `shadowcomp.glsl:148-152`) — the lava phantom, from a path IS5-FF no longer covers.

---

## 4. COST

### 4.1 Half 1 — per-dest VRAM

`rgba16f` = 8 B/texel; `r16ui` = 2 B/texel.

| `COLORED_LIGHTING` | texels | floodfill each | **per-dest pair** | voxel (shared, NOT duplicated — A3′) |
|---|---|---|---|---|
| 256 (256×128×256) | 8,388,608 | 64.00 MiB | **128.00 MiB** | 16 MiB |
| **512 (512×256×512) — the user's setting** | 67,108,864 | 512.00 MiB | **1024 MiB = 1.000 GiB** | 128 MiB |
| 768 (768×256×768) | 150,994,944 | 1152 MiB | **2.25 GiB** | 288 MiB |
| 1024 (1024×256×1024) | 268,435,456 | 2048 MiB | **4.00 GiB** | 512 MiB |

Nominal, ignoring driver alignment — treat as **lower bounds**.

### 4.2 The baseline this sits on top of — CORRECTED

Iris caches **one pipeline per `NamespacedId`, forever** (`PipelineManager.pipelinesPerDimension`). All of these are **instance** fields:

| item | evidence | size |
|---|---|---|
| floodfill pair | `shaders.properties:179-180` | 1024 MiB |
| `voxel_img` | `:178` | 128 MiB |
| `wsr_img` 512×64×512 r16ui | `:199` | 32 MiB |
| **WSR face SSBO `bufferObject.0`** | `:217 = 810549248` | **773.0 MiB** |
| shadow maps @ `shadowMapResolution 4096` | `lib/common.glsl:487` | ~256 MiB *(ESTIMATE)* |
| colortex set main+alt @1440p | §4.4 | ~200 MiB *(ESTIMATE)* |
| **per-dimension total** | | **≈ 2.4 GiB** |

A player who has looked through one cross-dim portal already carries **≈4.8 GiB** of iris state before this feature adds a byte.

### 4.3 Worst-case dest count — **no cap exists today**

- Recursion depth = **1**, hard-coded (`doRenderPortal:296-300`). `maxPortalLayer` is irrelevant here.
- `portalRenderLimit` (200) does **not** bound the compat loop: the check reads a count incremented only inside `PortalRendering.onBeginPortalWorldRendering()`, but `renderPortals` (`:461-467`) builds the whole list before rendering anything.
- k is bounded only by frustum + `getRenderRange()`.
- **Scope:** half 1 is needed only for dests sharing the source's `NamespacedId` (V1-F8) — usually "same dimension", but two custom dims sharing a `DimensionType.skybox()` class collide onto one pipeline.

| scenario | k | half-1 VRAM @512 | @256 |
|---|---|---|---|
| single portal | 1 | 1.00 GiB | 128 MiB |
| two side-by-side (our own pre-registered multi-portal row, `:440-445`) | 2 | 2.00 GiB | 256 MiB |
| portal room / hub | 4–6 | 4–6 GiB | 512–768 MiB |
| our own lag-attack threshold (`RenderStates.java:181`, >10 views/frame) | 10 | 10 GiB | 1.25 GiB |
| structural maximum | unbounded | unbounded | unbounded |

**On an 8 GiB card at 512, with a ≈4.8 GiB two-dimension baseline, there is not room for even one dest set.** `COLORED_LIGHTING ≤ 256` is the realistic primary target, with 512 as an explicit opt-in.

**Shrinking the dest volume is BLOCKED:** `lightVoxelization.glsl:5-7` and `shadowcomp.glsl:16-30` are compile-time constants; a smaller dest volume needs a separately compiled program.

### 4.4 Half 1 — GPU TIME (was missing entirely; V1-F11)

Un-suppressing costs a **full compute dispatch per portal per frame**: `shadowcomp.glsl:26 const ivec3 workGroups = ivec3(64, 32, 64)` × `local_size 8³` = **67.1 M invocations**, each doing up to 6 `texelFetch`es against a 512 MiB volume, plus the voxel read at `:120`. With k portals the frame pays **(k+1)×** that. **This may bind before VRAM does at k≥2** and belongs in the user's budget question alongside memory. **Magnitude UNVERIFIED** — no measurement exists; the residency probe (G3) is where it gets measured.

### 4.5 Half 2 — TAA history

Recommended set = **`colortex2` only**, 3 copies/portal/frame (write both sides in, read MAIN back). RGB16F is commonly stored as 8 B/texel; the honest figure is runtime-only via the `glGetTextureLevelParameteri` the guard already performs (`:182`).

| | 1080p | 1440p |
|---|---|---|
| colortex2 one copy | 15.82 MiB | 28.13 MiB |
| **recommended: 3 copies/portal/frame** | **47.46 MiB** | **84.38 MiB** |
| at 60 fps, 1 portal | 2.78 GiB/s | **4.94 GiB/s** |
| at 60 fps, 4 portals | 11.1 GiB/s | 19.8 GiB/s |
| (full `clear=false` set 1,2,4,5,7 main+alt, one copy) | 75.15 MiB | 133.59 MiB |

**Half 2 costs ~2.7% of half 1's VRAM per dest** (28 MiB vs 1024 MiB at 1440p/512) and adds no compute. It is by far the cheaper half — but it does **not** dodge the shared blocker (H1).

---

## 5. HAZARDS, RANKED

**H1 — [BLOCKER, both halves] Per-dest reprojection state has no known instrument.**
`shadowcomp.glsl:81-82 posOffset = floor(previousCameraPosition) - floor(cameraPosition)`; `taa.glsl:60-66` reprojects from `gbufferPreviousModelView`/`gbufferPreviousProjection`/`previousCameraPosition` with no prev-depth buffer. The tracker is one-deep and per-pipeline, ticked by the nested `beginLevelRendering` (our own `IrisInterface.java:175-183`). Portal 1 sees `previous = mainCam`; **portals 2..k see `previous = the preceding portal's dest camera`** — thousands of blocks off, so `previousPos` lands outside the volume and portal B's per-dest storage accumulates nothing. And `CameraUniforms$CameraPositionTracker` is package-private, held in **no field** in 963 classes; our own heal only calls `onNewFrame()`, which samples the **live** camera. **A per-dest volume without per-dest reprojection is a volume that shears every frame.**
*Design must answer: which of (a) new mixin on the package-private tracker, (b) interception at `addCameraUniforms`, (c) accept-and-degrade — and at what cost.* → **Gate G1.**

**H2 — [BLOCKER-class] VRAM: 1.00 GiB/dest on a ≈2.4 GiB/dimension baseline, with no cap in the tree.**
Largest existing GPU allocation in our code is ~75 MiB of guard scratch @1080p; `IrisTemporalTargetGuard.scratch` is an unbounded `HashMap`. Nothing anywhere implements eviction. → **§3.6 sticky-slot policy + gates G2/G3.**

**H3 — [was invisible] LRU thrash ⇒ permanently ACT-dark windows.** `renderPortals` walks a stable-ordered list; any cap below in-view k gives a 0% hit rate forever. Because `lightVolume.a == 0` collapses both the 10× lightmap lift and the 50× emitter boost (`mainLighting.glsl:394-395`), the result is *no ACT at all* in every window — worse than today. → §3.6.

**H4 — [unmodelled] `preparePipeline` resets iris's frame counter and timer mid-nested-render.** On a first cross-dim look, offsets `13-22` run `COUNTER.reset(); TIMER.reset()` **inside our bracket**, re-phasing `framemod2` (ACT ping-pong read/write side), `framemod8` (TAA jitter, `jitter.glsl:15`, user has `TAA_JITTER=2`), `framemod4/600` (WSR schedule). It also constructs an entire ~2.4 GiB pipeline mid-frame. Latent today; **load-bearing** for a design that depends on parity determinism. → probe leg in G0/P6.

**H5 — [design-forcing] Portals are not rendered every frame; the pack reprojects by exactly one.** `offsetOcclusionQuery = true` (`IPGlobal.java:499`) makes `PortalRenderInfo` consume last frame's query, so occluded/grazing portals are skipped for arbitrary runs. Per-dest state needs a `lastAdvancedFrameIndex` and must **zero, not reproject**, on any gap > 1. Cannot happen today (the volume is fully re-seeded each main frame) — per-dest state creates it.

**H6 — Partial allocation = split-brain = the lava phantom, from a path IS5-FF no longer covers.** All-or-nothing per pair; any partial failure frees both and re-installs suppression for that portal.

**H7 — Uncleared / unparameterised mod-owned storage (V1-F9/F10).** DSA storage is undefined; `shadowcomp.glsl:146 clamp(light, 0.0, 1000.0)` clamps but does not zero, and garbage decays at ~6.5%/step ⇒ a bright blob persisting >100 frames. Missing `MAX_LEVEL=0` ⇒ sampler reads black while imageStore succeeds. → §3.5.

**H8 — Restore discipline is absolute.** Bindings re-read the supplier at every `use()`; a stranded swap repoints every subsequent main-world program. Our own code states the rule: *"nothing may throw while the noop is installed, or the NEXT MAIN frame's shadowcomp would silently freeze"* (`:249-251`). Swap-out first in the `finally`; permanent disarm latch on restore failure.

**H9 — Mechanism choice changes the blast radius.** `GlImage.getId` methodrefs exist in exactly 2 constant pools; `clear()`, `destroyInternal()`, `updateNewSize()`, `ImageClearPass.execute()` all use protected `getGlId()`. **Mechanism B (mixin overriding `getId()`) redirects exactly the image + sampler bindings and nothing else. Mechanism A (reflective `GlResource.id` write) additionally redirects clears and `glDeleteTexture`.** B costs a second live iris `@Mixin` — a documented D9 amendment (precedent: `MixinIrisSodiumTransformPatcher_ClipInject.java:24`). **Recommend B.** → user decision Q2.

**H10 — Un-suppressing shadowcomp re-arms everything IS5-FF was built to stop** for anything else the stage writes. For Complementary the stage's only effect is the flood-fill dispatch (full `renderAll()` bytecode read), but the design must enumerate rather than assume.

**H11 — Pipeline identity: creation lands mid-frame; destroy (probably) does not.** `destroyPipeline` is referenced by 3 classes only, all tick/client-thread/frame-boundary — so H3-style "iris deletes our texture mid-swap" is **not reachable** (DERIVED, not exhaustive). Keep the cheap per-frame identity re-resolve anyway (the `IrisShadowCompositeSuppressor` model; `WeakHashMap` keyed on the pipeline gets eviction free). **Creation is the real mid-frame event** — H4.

**H12 — Same-`NamespacedId`, not "same dimension."** `Iris.getCurrentDimension()` falls back to a `DimensionType.skybox()`-derived id when the pack's dimension map lacks the level; this mod ships alternate dimensions. Key on **pipeline instance identity**.

**H13 — Half 2's swap must target the pipeline the dest actually uses.** The guard resolves exactly one pipeline (`:148`) and calls cross-dim behaviour "wasted-but-harmless" today; for a content **swap** it becomes actively wrong.

**H14 — Resize (half 2 only).** `RenderTarget.resize` destroys contents, keeps ids, with no callback; per-target scale differs (`REFLECTION_RES 0.5` on colortex1/7). Poll `(w,h,fmt)` per target as `ensureScratch` (`:322`) does; post-resize must **zero, not copy**. **Half 1 is resize-immune** — `GlImage.updateNewSize` base is `0: return` and all three ACT images are `isRelative = false`.

**H15 — `taa.glsl:203`'s zero sentinel is load-bearing and pack-specific.** Keep the IS5-G zero path as the cold-start/invalid fallback. Note also `fxaa.glsl:188`: a near-black `colortex2` currently **forces FXAA on** — replacing zeros with real history changes FXAA behaviour. Pre-register it or the live round reports it as a new bug.

**H16 — Shared-`voxel_img` residue (pre-existing, do not "fix" by duplicating).** After the last nested render the shared voxel volume holds DEST voxelization for the rest of the main frame, and gbuffer material shaders read it (`connectedGlass`, `wavingBlocks`, `endPortalEffect`, `netherPortal`). This is already true today. **Duplicating `voxel_img` would make it worse** (A3′/FOLD-2): the clear hits the original, the write hits ours, leaving the shared volume cleared-and-empty.

**H17 — 2D texture-unit cache early-out.** `IrisRenderSystem$DSAARB.bindTextureToUnit` returns without binding for `GL_TEXTURE_2D` when the cached binding matches. Half 1 is 3D (safe); half 2 must stay binding-free (`glCopyImageSubData`, DSA) as the existing guards deliberately are.

**H18 — `addTextureImage` silently no-ops when the uniform location is `-1`.** Per-program bindings are not guaranteed; a probe must not expect one per program.

**H19 — `CustomTextureSamplerInterceptor` name shadowing.** Inert for Complementary (`image.` only), but a pack declaring a `texture.` named `floodfill_sampler` would defeat the sampler half while the image half still worked — a split-brain volume.

**H20 — Sodium does not interact (verified negative).** 777 sodium classes: `glBindImageTexture` 0, `TEXTURE_3D`/`glTexImage3D`/`glTexStorage3D` 0, `glDispatchCompute`/`glMemoryBarrier` 0, `GL42/GL43/ARBShaderImageLoadStore` 0. Relevant to half 2 only via H17.

---

## 6. PROBE PLAN — the actionable centrepiece

**Binding rule (`no-guessing-deep-debug-logs.md`): instrument and confirm live BEFORE designing.** Every probe: log-only, default-OFF, ≤1 Hz on the render thread, once-only liveness + once-only lines on **every** silent-skip and throw path, `-P` row in **both** gradle blocks. Ordered so the cheapest project-killers run first.

---

### ⛔ G0 — **GO/NO-GO GATE #1. Zero new code. Run before anything else.**
**`-Dseamlessportals.actScopeProbe`** (reuses the shipped `-PnestedShadowCompositeProbe` counters) **+ a mandatory user look-test.**

**Hook:** existing counters (`nestedShadowCompositeSuppressCount`, `nestedShadowCompositeNoopHits`) **plus** one new 1 Hz line at `invokeWorldRendering` logging the resolved pipeline identity — `[IS6-SCOPE] portal=<uuid> destDim=<id> pipeHash=<identityHashCode(Iris.getPipelineManager().getPipelineNullable())> nsid=<Iris.getCurrentDimension()>`. This positive marker is what separates "different pipeline" from "never reached renderShadows" (the ambiguity V1-F2 caught in `IPGlobal.java:227-233`).

**Protocol:** (1) same-dim portal to a lava-rich dest; (2) **cross-dim OW→Nether portal to a lava-rich dest**. For each: record counters, record `pipeHash`, and **look through the window and report whether the colored light matches the DESTINATION or the SOURCE — or is absent entirely.**

**DECISION RULE:**
- Cross-dim window shows **DEST-seeded** colored light, `pipeHash` differs from main, `noopHits == 0` ⇒ **the per-dest-storage hypothesis is validated by a natural experiment**, and half 1's scope narrows to same-`NamespacedId` portals. **GO** (to G1).
- Cross-dim window shows **NO colored light at all** ⇒ the blocker is nested shadow voxelization (`voxel_img` never written for the dest — the open `C:0/0` item), **not** storage. **NO-GO for half 1**; the engagement re-scopes to the voxelization hunt.
- Cross-dim window shows **SOURCE-seeded** light, or `noopHits > 0` cross-dim, or `pipeHash` identical ⇒ **A2 refuted**; §2/§3/§4 must be re-derived. **STOP.**

---

### ⛔ G1 — **GO/NO-GO GATE #2. The engagement's real blocker.**
**Part (a): static recon, no game, no lever.** Deliver a *mechanism or a refusal* for per-dest previous-camera state. Enumerate and cost: (i) `@Mixin` on the package-private `CameraUniforms$CameraPositionTracker` adding an accessor + per-dest slots; (ii) interception at `CameraUniforms.addCameraUniforms` before the lambdas capture; (iii) walking the pipeline's `UniformHolder` to the synthetic captured-argument field; (iv) accept-and-degrade. For each: does it also cover `gbufferPreviousModelView` / `gbufferPreviousProjection` (`CapturedRenderingState`), which half 2 needs too?

**Part (b): live probe `-Dseamlessportals.prevCamProbe` — implementable only if (a) yields a read path.**
**Hook:** the existing `NoopShadowCompositeRenderer.renderAll()` (the one method that runs only during a nested dest render) + the anchor finally.
**Log (1 Hz):** `[IS6-PREV] portal=<uuid> idx=<i of k> k=<k> curCam=<…> prevCam=<…> frameCounter=<…> parity=<%2> pipeHash=<…>`
**MANDATORY LEGS:** (1) **k ≥ 2** — two same-dim portals in one frame; (2) **cold cross-dim first look** (tests H4's `COUNTER.reset()`).

**DECISION RULE:**
- (a) yields no mechanism ⇒ **NO-GO for both halves as commissioned.** Neither the volume nor the history can be correctly reprojected per dest. Re-scope to something that does not need reprojection, or stop.
- k=1 shows `prevCam ≈ mainCam` **and** k=2 shows portal B's `prevCam ≈ portal A's dest camera` ⇒ **H1 confirmed at full strength**; the design must carry per-dest prev-camera or it is knowingly broken past portal 1.
- Cold cross-dim leg shows `frameCounter` restarting near 0 mid-frame ⇒ **H4 confirmed**; the design needs a parity-discontinuity detector that zeroes rather than reprojects.

---

### ⛔ G2 — **GO/NO-GO GATE #3. Sizes the cap from reality.**
**`-Dseamlessportals.destCensusProbe`** — hook `renderPortals` (`:461`), 1 Hz.
**Log:** `[IS6-CENSUS] portalsThisFrame=<k> sameNsid=<ks> crossNsid=<kc> distinctDestKeys60s=<d> maxK60s=<m>`
**DECISION RULE:** `maxK60s == 1` in ordinary play ⇒ a **1-slot sticky** design is sufficient and thrash-free. `maxK60s ≥ 2` routinely ⇒ at `COLORED_LIGHTING=512` half 1 is **VRAM-dead** (2 GiB+ of dest sets on a ≈4.8 GiB baseline); the only viable forms are `≤256` or a 1-slot sticky cap that visibly degrades the other portals. **Surface to the user (Q1) rather than guessing.**

---

### ⛔ G3 — **GO/NO-GO GATE #4. Residency and dispatch cost, measured — not `glGetError`.**
**`-Dseamlessportals.actResidencyProbe`** — replaces the draft's P5 (V2-F6: WDDM does not report OOM for texture creation; it spills and stalls).
**Hook:** the anchor; allocate the dest pair once, **hold it ≥ 60 s of normal play with a portal in view**, then free. Also arm a second phase that leaves IS5-FF **un-suppressed** for one portal (dispatch cost, §4.4).
**Log (1 Hz):** `[IS6-VRAM] held=<MiB> frameMs p50/p95/p99=<…> glErr=<slate> vramAvailKB=<…|unknown> phase=<idle|held|held+dispatch>`
**DECISION RULE:** p99 frame time in `held` within noise of `idle`, and `held+dispatch` within the user's tolerance ⇒ the tier is viable on this machine. Any multi-hundred-ms p99 excursion, or a `held+dispatch` regression the user can feel ⇒ **that tier is dead**; fall to 256, then to 1 slot, then abandon half 1.

---

### P4 — `-Dseamlessportals.imageBindProbe` — mechanism + weave test (re-specified per V1-F5)
**Hook:** `@Mixin(ProgramImages.class)` `@Inject(method="update", at=@At("HEAD"), require=1)` — **per-instance identity hash gives per-program attribution**, which the draft's `GlImage.getId` hook could not provide. Optionally also `@Mixin(GlImage.class)` on `getId` HEAD (that *is* mechanism B's seam, so a successful weave is simultaneously the mechanism proof).
**Log (1 Hz):** `[IS6-BIND] programImagesUpdates/s=<n> distinctProgramImages=<m> getIdCalls/s=<g> mainFrames=<f> portalFrames=<p>`; once-only `MIXIN WOVEN` and a 10 s `NEVER FIRED` watchdog.
**DECISION RULE:** updates/s scaling with frame rate across **many distinct instances** ⇒ late binding confirmed per program, mechanism B available. Updates/s ≈ 0 after startup ⇒ **half 1's binding-swap premise dies** (contradicts the bytecode, so treat as a weave failure first). Weave fails ⇒ mechanism B unavailable → P7.

### P5 — `-Dseamlessportals.imageRegistryProbe` — registry model, reflection only, no mixin
**Hook:** `onBeforeHandRendering` (`:176`), 1 Hz; reflect `customImages`.
**Log:** per image — `[IS6-REG] name=… sampler=… id=… target=… clear=… objHash=… pipeHash=…`; once-only lines for reflection failure / null pipeline / non-`IrisRenderingPipeline` / empty set.
**DECISION RULE:** exactly three names, `voxel_img.shouldClear()==true`, both floodfill `false`, ids+hashes stable ⇒ model + A3′ confirmed. Anything else ⇒ no swap; suppression stays. `pipeHash` churning without a dimension change ⇒ H11 is worse than modelled.

### P6 — `-Dseamlessportals.actDestSeedProbe` + **TORCH CONTROL A/B** (re-specified per V1-F4)
The draft's P3 could not answer its own title (it was hooked inside the *no-op*). Give it a real observable: run the existing IS5-FF A/B **with a torch control** — place/break a torch away from any portal and confirm its ACT light appears/updates (proves the real composite is live), then compare in-window colored light source-vs-dest. Carry the H4 parity fields on the same line.
**DECISION RULE:** torch responds **and** in-window light is source-seeded ⇒ today's baseline is exactly as documented and the fix target is correct. Torch does not respond ⇒ the restore path is broken; fix that before anything else.

### P7 — `-Dseamlessportals.finalFieldProbe` — **only if P4 says mechanism B is unavailable**
Throwaway `GlImage`, never a pack image: construct, record `getId()`, reflectively set `GlResource.id` to a sentinel, call through an `IntSupplier` 200,000× to force C2, log immediate + post-JIT read-back, restore, destroy.
**DECISION RULE:** `set=ok` and `readBackPostJIT == sentinel` ⇒ mechanism A viable **subject to** the clear+destroy collateral (H9). Any exception or fold ⇒ mechanism A refuted ⇒ half 1 has no mechanism ⇒ stop. *(Legality is already project-proven on Temurin 25 for instance finals — this probe is specifically about a private final **int** in a JIT-hot supplier.)*

### P8 — `-Dseamlessportals.swapDryRunProbe` — final bracket airtightness, behaviour-neutral
Arm the swap to the image's **own** id (no-op redirect) at the real bracket.
**Log (1 Hz):** `[IS6-DRY] arms=<n> disarms=<n> mismatches=<n> throwsCaught=<n> pipeIdentityChanges=<n>`; once-only first-arm / disarm-without-arm / any throwable.
**DECISION RULE:** `arms == disarms`, `mismatches == 0`, zero throws across 10 minutes **including a dimension crossing, a shaderpack settings apply, and a window resize** ⇒ proceed to a real swap. Any drift ⇒ fix the bracket before allocating a byte.

### ~~P6-old (finalPassProbe)~~ — **DELETED.** Settled statically (§1 V2-F7): the nested render runs `finalizeLevelRendering()` → `renderFinalPass()`; half 2's write-back reads **MAIN**. Saves a D9 amendment.

**Order: G0 → G2 → G1(a) → P5 → P4 → G1(b) → G3 → [P7 iff P4 fails] → P6 → P8.**
G0 and G2 are same-session and nearly free; either can end the project. G1 is the one that decides whether a *correct* design exists at all.

---

## 7. RECOMMENDED SEQUENCING

| Phase | Content | Decision point |
|---|---|---|
| **0. Gate round (cheap)** | G0 cross-dim ACT look-test + pipeline-identity marker; G2 dest census. One live session, ~1 hour, one tiny log-only patch. | **GO/NO-GO #1:** cross-dim shows no ACT ⇒ stop half 1, re-scope to voxelization. **GO/NO-GO #3:** `maxK60s ≥ 2` at 512 ⇒ half 1 is VRAM-dead at the user's tier; escalate Q1 before any design. |
| **1. Blocker recon (static)** | G1(a): does a per-dest previous-camera instrument exist? Deliver mechanism-or-refusal, covering `previousCameraPosition` **and** `gbufferPrevious*`. | **GO/NO-GO #2:** no instrument ⇒ **do not start either half as commissioned.** |
| **2. Mechanism round** | P5 registry, P4 bind/weave, G1(b) live prev-camera (k≥2 + cold cross-dim), G3 residency + dispatch cost, P7 if needed. | **GO/NO-GO #4:** any tier that fails G3 is dead on this hardware. |
| **3. Design panel** | Only with gates green. Decides: mechanism A vs B; slot count and sticky policy; the prev-camera design; IS5-FF relocation enumeration; allocation route (ctor vs DSA+7 params+clear); half-2 both-sides swap-in. | Panel output is a spec, not code. |
| **4. Implement — half 2 FIRST** | Half 2 is ~2.7% of half 1's VRAM, adds no compute, and its MAIN-vs-ALT question is already settled. It is the cheap proving ground for per-dest keying, the bracket, the write-back anchor, and the prev-camera machinery. | If half 2 ghosts worse than today's zeroing, half 1 will too — stop there. |
| **5. Verify** | Full-depth Opus verify rounds (user-endorsed depth); mandatory final-diff verification (both prior polish catches were spec-invisible). | Verify blockers gate the suite. |
| **6. Suite** | The 8-leg suite as the per-stage gate; lever-row parity in both gradle blocks. | Green or no live round. |
| **7. Live A/B** | P8 dry-run first, then the real swap. Torch control mandatory (anti-false-PASS). Pre-register: the FXAA behaviour change (H15), the counter unit change (§3.4), and the eviction pop (Q5). | User verdict. |
| **8. Half 1** | Only if half 2 landed clean **and** Q1 settled the VRAM budget. | — |

---

## 8. GENUINE USER DECISIONS (evidence cannot settle these)

**Q1 — VRAM and GPU-time budget.** At `COLORED_LIGHTING=512` each destination costs **1.00 GiB** on a per-dimension iris baseline of ≈2.4 GiB, **plus** a full 67.1 M-invocation compute dispatch per portal per frame. Options: (a) 1 sticky slot at 512 — one portal gets correct ACT light, all others keep today's look, permanently and stably; (b) require `COLORED_LIGHTING ≤ 256` for the feature (128 MiB/dest, up to ~4 slots plausible); (c) auto-degrade — disable half 1 above 256; (d) an IPConfig slider. *G2 and G3 supply the numbers; the trade is yours.*

**Q2 — Is a second live iris `@Mixin` acceptable?** Mechanism B (override `GlImage.getId()`) is surgically correct — redirects exactly the image + sampler bindings, touches neither clears nor `glDeleteTexture`, needs no reflective final-field write. It costs a documented D9 amendment (D9 has been amended once already). Mechanism A keeps zero new mixins but redirects clears and deletes too, requiring compensating discipline. **Which?** *(Note: gate probes P4 and — if used — the tracker mixin in G1 would also be iris mixins, so this decision arrives at the probe round, not at implementation.)*

**Q3 — Is half 2 worth its cost given the shared blocker?** Half 2 is cheap (28 MiB/portal, ~4.9 GiB/s at 1440p, no compute) and its one static unknown is now closed. But without per-dest prev-camera it would reproject the dest history by the wrong camera delta — potentially **ghosting worse than today's zeroing**, which is a known-good, live-proven fix. Ship half 2 only after G1, or not at all?

**Q4 — Asymmetric quality, pending G0.** If cross-dim windows already show correct dest-seeded ACT light, half 1 becomes same-`NamespacedId`-only. Is a *visibly different look between same-dim and cross-dim windows* acceptable as an end state, or must the feature ship symmetric?

**Q5 — Degradation contract.** Under the sticky-slot policy, a portal that never wins a slot keeps today's source-seeded light **forever** (stable, no popping). The alternative — rotating slots — gives every portal a turn but pops on every rotation and, at any cap below the in-view count, degenerates to permanently-dark windows. **Stable-but-unfair, or fair-but-popping?**

**Q6 — Is the honest answer "not now"?** Given that (i) the reprojection instrument is unrecon'd, (ii) the VRAM tier the user actually runs cannot fit one destination, and (iii) a five-minute look-test may show the premise is already refuted — the recon's own recommendation is to run the **gate round only** and re-decide. That is a legitimate outcome, not a failure.

---

## 9. SIGNATURES — verbatim block to code against

```java
// ================= HALF 1 — the swap seam ======================================================
public abstract class net.irisshaders.iris.gl.GlResource {
  private final int id;  private boolean isValid;
  protected GlResource(int);
  public final void destroy();      protected abstract void destroyInternal();
  protected void assertValid();     // IllegalStateException("Tried to use a destroyed GlResource")
  protected int getGlId();          // assertValid(); return id
}
public class net.irisshaders.iris.gl.image.GlImage extends net.irisshaders.iris.gl.GlResource {
  protected final String name, samplerName;
  protected final net.irisshaders.iris.gl.texture.TextureType target;          // TEXTURE_3D
  protected final net.irisshaders.iris.gl.texture.PixelFormat format;          // RGBA | RED_INTEGER
  protected final net.irisshaders.iris.gl.texture.InternalTextureFormat internalTextureFormat; // RGBA16F | R16UI
  protected final net.irisshaders.iris.gl.texture.PixelType pixelType;         // HALF_FLOAT | UNSIGNED_INT
  private   final boolean clear;
  public GlImage(String name, String samplerName, TextureType, PixelFormat, InternalTextureFormat,
                 PixelType, boolean clear, int w, int h, int d);   // PUBLIC; self-allocates, self-parameterises, SELF-CLEARS
  public int    getId();            // <== THE SEAM. virtual, non-final: { aload_0; invokevirtual getGlId; ireturn }
  public String getName(); public String getSamplerName(); public TextureType getTarget();
  public boolean shouldClear();
  public void   clear();            //  1: invokevirtual getGlId()I  ... 23: ARBClearTexture.glClearTexImage  <-- NOT getId()
  public void   updateNewSize(int,int);   //  0: return   <== BASE IS A NO-OP  (half 1 is resize-immune)
  protected void setup(int,int,int,int);  // 7 texParameter calls, then 181: invokevirtual clear()
  protected void destroyInternal();       //  1: getGlId()  4: GlStateManager._deleteTexture   <-- NOT getId()
  public InternalTextureFormat getInternalFormat(); public PixelFormat getFormat(); public PixelType getPixelType();
}
// TEXTURE STATE a DSA replacement MUST replicate (GlImage.setup bytecode 0-184; isInt = format.isInteger()):
//   10241 GL_TEXTURE_MIN_FILTER = isInt ? 9728 GL_NEAREST : 9729 GL_LINEAR
//   10240 GL_TEXTURE_MAG_FILTER = same selector
//   10242 GL_TEXTURE_WRAP_S     = 33071 GL_CLAMP_TO_EDGE
//   10243 GL_TEXTURE_WRAP_T     = 33071        (if h > 0)
//   32882 GL_TEXTURE_WRAP_R     = 33071        (if d > 0)
//   33085 GL_TEXTURE_MAX_LEVEL  = 0            <-- OMIT THIS AND sampler3D READS RETURN (0,0,0,1)
//   33082 GL_TEXTURE_MIN_LOD    = 0
//   33083 GL_TEXTURE_MAX_LOD    = 0
//   34049 GL_TEXTURE_LOD_BIAS   = 0.0f
//   then clear()  — the ctor route zeroes; DSA does NOT.

public class net.irisshaders.iris.gl.image.ImageBinding {
  private final int imageUnit, internalFormat;
  private final java.util.function.IntSupplier textureID;      // == image::getId
  public ImageBinding(int,int,java.util.function.IntSupplier);
  public void update();   // 8: IntSupplier.getAsInt  22: bindImageTexture(unit,id,0,true,0,GL_READ_WRITE(35002),fmt)
}
public class net.irisshaders.iris.gl.sampler.SamplerBinding {
  private final int textureUnit; private final java.util.function.IntSupplier texture;
  private final java.util.function.Supplier<net.irisshaders.iris.gl.sampler.GlSampler> sampler;
  private final net.irisshaders.iris.gl.texture.TextureType textureType;
  public void update();  private void updateSampler();
}
public class net.irisshaders.iris.gl.program.ProgramImages {
  private final com.google.common.collect.ImmutableList<ImageBinding> imageBindings;
  private java.util.List<net.irisshaders.iris.gl.program.GlUniform1iCall> initializer;
  private ProgramImages(ImmutableList<ImageBinding>, java.util.List<GlUniform1iCall>);  // PRIVATE ctor
  public static ProgramImages$Builder builder(int program);
  public void update();          // <== P4's hook: unconditional iteration, no short-circuit
  public int getActiveImages();
}
public final class net.irisshaders.iris.gl.program.ProgramImages$Builder implements ImageHolder {
  public boolean hasImage(String);
  public void addTextureImage(IntSupplier, InternalTextureFormat, String);   // RETURNS EARLY if uniform loc == -1
  public ProgramImages build();
}
public class net.irisshaders.iris.gl.image.ImageClearPass {
  private final GlImage image;
  public static ImageClearPass create(GlImage);
  public void execute();    // image.clear()  -> getGlId(), NOT getId()
  public void destroy();    // { return; }
}
public static void net.irisshaders.iris.samplers.IrisImages.addCustomImages(ImageHolder, java.util.Set<GlImage>);
public static void net.irisshaders.iris.samplers.IrisSamplers.addCustomImages(SamplerHolder, java.util.Set<GlImage>);
public void net.irisshaders.iris.pipeline.IrisRenderingPipeline.addGbufferOrShadowSamplers(
        SamplerHolder, ImageHolder, Supplier<ImmutableSet<Integer>>, boolean, boolean, boolean, boolean);

// ================= pipeline + lifecycle ========================================================
public class net.irisshaders.iris.pipeline.IrisRenderingPipeline {
  private final java.util.Set<GlImage> customImages;                        // REFLECT — NO accessor exists
  private final ImmutableList<ImageClearPass> clearImages;
  private final net.irisshaders.iris.targets.RenderTargets renderTargets;   // REFLECT (already done in-tree)
  private final net.irisshaders.iris.shaderpack.properties.PackDirectives packDirectives;   // REFLECT
  private final net.irisshaders.iris.shadows.ShadowRenderer shadowRenderer;                 // REFLECT
  private final net.irisshaders.iris.pipeline.FinalPassRenderer finalPassRenderer;
  private final int shadowMapResolution;
  private net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder shaderStorageBufferHolder;  // 773 MiB @WSR=1,CL>=512
  private net.irisshaders.iris.shadows.ShadowRenderTargets shadowRenderTargets;                // 4096^2 @SHADOW_QUALITY=3
  private final ImmutableSet<Integer> flippedBeforeShadow, flippedAfterPrepare, flippedAfterTranslucent;
  public ImmutableSet<Integer> getFlippedAfterTranslucent();   // PUBLIC — prefer over reflection
  public void beginLevelRendering();      // 112 pushGroup("Clear textures") 116 clearImages 124 forEach  (FIRST real work)
  public void finalizeLevelRendering();   // 10-13 compositeRenderer.renderAll()  17-20 finalPassRenderer.renderFinalPass()
  public void destroy();
  public net.irisshaders.iris.uniforms.FrameUpdateNotifier getFrameUpdateNotifier();
}
public class net.irisshaders.iris.pipeline.PipelineManager {
  private final java.util.Map<NamespacedId, WorldRenderingPipeline> pipelinesPerDimension;   // HashMap, per-DIMENSION, forever
  public WorldRenderingPipeline preparePipeline(NamespacedId);
  //   5: Map.containsKey   10: ifne 109                        <- HIT: plain get, no side effects
  //  13-16: SystemTimeUniforms.COUNTER.reset()                 <- MISS: frameCounter := 0   (HAZARD H4)
  //  19-22: SystemTimeUniforms.TIMER.reset()
  //  41-55: pipelineFactory.apply(id)                          <- full pipeline built MID-FRAME
  //  73-97: if isReloadRequired() -> Minecraft.levelExtractor.allChanged()
  public WorldRenderingPipeline getPipelineNullable();
  public void destroyPipeline();   // forEach-destroy, clear(), pipeline=null, version++
}
public static NamespacedId net.irisshaders.iris.Iris.getCurrentDimension();
//   0-35: Minecraft.getInstance().level.dimension().identifier() -> new NamespacedId
//   39-68: returned only if pack.getDimensionMap().containsKey(id)
//   69-101: else fall back to a DimensionId chosen by ClientLevel.dimensionType().skybox()   (HAZARD H12)
public class net.irisshaders.iris.shadows.ShadowCompositeRenderer {
  private final java.util.Set<GlImage> irisCustomImages;
  public void renderAll();   // 212 instanceof ComputeOnlyPass -> 220 goto (skips the graphical branch)
  public void destroy();
}
// javap -c MixinLevelRenderer (both are @Injects on LevelRenderer.render — our nested 8-arg call fires BOTH):
private void iris$setupPipeline(...CallbackInfo);
//  136-142: Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimension())
//  145: this.pipeline = ...     165: pipeline.beginLevelRendering()      <- unconditional, NO re-entrancy guard
private void iris$endLevelRender(...CallbackInfo);
//    0-38: HandRenderer.INSTANCE.renderTranslucent(..., gameRenderer.mainCamera(), ...)   // MAIN camera, nested too
//   73-77: pipeline.finalizeLevelRendering()      <- ==> renderFinalPass() DOES run nested (P6 deleted)
//   82-84: this.pipeline = null                   <- per-INSTANCE field; bracketed in-tree by setPipeline()

// ================= HALF 2 — content-copy surfaces ==============================================
public class net.irisshaders.iris.targets.RenderTarget {
  private final int mainTexture; private final int altTexture;   // FINAL — no setter; DO NOT attempt a field swap
  public int getMainTexture(); public int getAltTexture(); public int getWidth(); public int getHeight();
  public InternalTextureFormat getInternalFormat();
  public GlSampler getMainSampler(); public GlSampler getAltSampler();
  public void turnOnMips(boolean); public void turnOffMips(boolean); public void destroy();
  void resize(org.joml.Vector2i); void resize(int,int);          // PACKAGE-PRIVATE
}
public class net.irisshaders.iris.targets.RenderTargets {
  public int getRenderTargetCount(); public RenderTarget get(int) /*MAY BE NULL*/; public RenderTarget getOrCreate(int);
  public boolean resizeIfNeeded(int depthBufferVersion, GpuTexture, int w, int h, DepthBufferFormat, PackDirectives);
  public GlFramebuffer createColorFramebuffer(ImmutableSet<Integer>, int[]);
  public int getCurrentWidth(); public int getCurrentHeight();
}
public class net.irisshaders.iris.pipeline.FinalPassRenderer {
  private final ImmutableList<FinalPassRenderer$SwapPass> swapPasses;
  public void renderFinalPass();  public void recalculateSwapPassSize();
}
final class net.irisshaders.iris.pipeline.FinalPassRenderer$SwapPass {
  public int target, width, height;
  net.irisshaders.iris.gl.framebuffer.GlFramebuffer from;      // createColorFramebuffer(ImmutableSet.of(), {i}) => ALT
  int targetTexture;                                          // RenderTargets.get(i).getMainTexture()
  private FinalPassRenderer$SwapPass();                        // PRIVATE ctor
}
public class net.irisshaders.iris.targets.BufferFlipper {
  public void flip(int); public boolean isFlipped(int); public ImmutableSet<Integer> snapshot();
}   // constructed only in IrisRenderingPipeline.<init>; flip() called ONLY in CompositeRenderer.<init>

// ================= reprojection state — THE BLOCKER (H1) ========================================
public final class net.irisshaders.iris.uniforms.SystemTimeUniforms {
  public static final SystemTimeUniforms$FrameCounter COUNTER;   // "frameCounter" @ PER_FRAME
}
public class net.irisshaders.iris.uniforms.SystemTimeUniforms$FrameCounter implements java.util.function.IntSupplier {
  private int count;  public int getAsInt();
  public void beginFrame();   // count = (count + 1) % 720720   — sole caller MixinGameRenderer.iris$startFrame
  public void reset();        // 0: aload_0  1: iconst_0  2: putfield count:I   — called by preparePipeline (H4)
}
class net.irisshaders.iris.uniforms.CameraUniforms$CameraPositionTracker {   // <== PACKAGE-PRIVATE CLASS
  private static final double WALK_RANGE, TP_RANGE;
  private final org.joml.Vector3d shift;
  private org.joml.Vector3d previousCameraPosition, currentCameraPosition;
  private org.joml.Vector3d previousCameraPositionUnshifted, currentCameraPositionUnshifted;
  CameraUniforms$CameraPositionTracker(FrameUpdateNotifier);                 // PACKAGE-PRIVATE ctor
  private void update();      // previous <- current;  current <- CameraUniforms.getUnshiftedCameraPosition()
  public org.joml.Vector3d getCurrentCameraPosition();
  public org.joml.Vector3d getPreviousCameraPosition();
  public org.joml.Vector3d getPreviousCameraPositionUnshifted();
  public double getCurrentCameraPositionY();
}
public class net.irisshaders.iris.uniforms.CameraUniforms {
  private static final net.minecraft.client.Minecraft client;      // THE ONLY FIELD — no tracker field anywhere
  public static void addCameraUniforms(UniformHolder, FrameUpdateNotifier);   // the tracker is created + captured HERE
  public static org.joml.Vector3d getUnshiftedCameraPosition();
}
// grep -rl CameraPositionTracker over 963 classes -> CameraUniforms, CameraUniforms$CameraPositionTracker,
//   HardcodedCustomUniforms (PARAMETERS only). NO FIELD HOLDS AN INSTANCE.  ==> no read path, no write path.

// ================= OUR seams (line numbers verified this session) ==============================
// qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatOn262Renderer
public void   onBeforeHandRendering(Matrix4f);        // :176 (early-returns if PortalRendering.isRendering())
                                                      // :234 IrisTemporalTargetGuard.save()
                                                      // :243 try {   :252 IrisShadowCompositeSuppressor.install()
                                                      // :253 renderPortals(...)   :259 finally-first: uninstall()
                                                      // :288 IrisInterface.invoker.healPreviousFrameUniforms()
protected void doRenderPortal(Portal, Matrix4f);      // :296 RETURNS if isRendering()  <- depth hard-capped at 1
                                                      // :302 testShouldRenderPortal (occlusion query)
                                                      // :316-319 IrisBloomApertureMask.arm(portal, ...)
                                                      // :322 pushPortalLayer   :331 try { renderPortalContent }
public void   invokeWorldRendering(WorldRenderInfo);  // :388 ; :389-395 layer-0 -> renderWorldNew (OUTSIDE the bracket)
                                                      // :399 anyFullPipelineDestRendered = true
                                                      // :406 bumpPerFrameUniformCounter (lever DEFAULT-OFF)
                                                      // :416 IrisTemporalTargetGuard.clearForDestPass()   <== SWAP-IN
                                                      // :417 try {  :418 renderWorldFullPipeline  :420 finally
                                                      // :421 bumpPerFrameUniformCounter            <== SWAP-OUT before this
protected void renderPortals(Matrix4f);               // :461 flat stable-order loop  <- the cap-policy access pattern
public void   teardown();                             // :470  (idempotent; double-call is normal)
public static void onSwitchedAway();                  // :495
// IrisShadowCompositeSuppressor: install():165  uninstall():227  private static boolean broken
//   install() resolves via Iris.getPipelineManager().getPipelineNullable() -> swaps the MAIN pipeline's
//   ShadowRenderer.compositeRenderer  ==> cross-pipeline dest renders are structurally UNSUPPRESSED.
// IrisTemporalTargetGuard: save():143  restore():201  clearForDestPass():248  teardown():360
//   copy(int,int,int,int):311   ensureScratch(int,int,int,int):320   alloc(int,int,int):348
//   scratch: private static final Map<Integer,int[]>  (:104)  — keyed by colortex index ONLY, no dest key
// qouteall.imm_ptl.core.render.MyGameRenderer
public static void renderWorldFullPipeline(WorldRenderInfo);   // :518 — SOLE caller = invokeWorldRendering
//   :614 Object irisPipeline = IrisInterface.invoker.getPipeline(worldRenderer);
//   :626 IrisInterface.invoker.setPipeline(worldRenderer, null);      <== THE BRACKET PRECEDENT
//   :646 SecondaryWorldRenderCore.renderDestWorldFullPipeline(...)
//   :690 IrisInterface.invoker.setPipeline(worldRenderer, irisPipeline);   (outermost finally)
//   client.level = newWorld happens in the SWAP-IN block  ==> Iris.getCurrentDimension() returns the DEST dim
// qouteall.imm_ptl.core.render.SecondaryWorldRenderCore
//   :1861 destRenderer.render(8 args)      <== the direct LevelRenderer.render that fires both iris mixins
// qouteall.imm_ptl.core.ClientWorldLoader
public static LevelRenderer getWorldRenderer(ResourceKey<Level>);   // :515 — active-dim entry IS mc.levelRenderer
// qouteall.imm_ptl.core.render.renderer.PortalRenderer
protected final void renderPortalContent(Portal);   // :285 ; :288 returns if layer > getMaxPortalLayer()
// qouteall.imm_ptl.core.portal.PortalRenderInfo
public static boolean renderAndDecideVisibility(Portal, Runnable);  // :212 ; :216 last-frame query consumption
// qouteall.imm_ptl.core.IPGlobal
public static boolean offsetOcclusionQuery = true;                  // :499  DEFAULT ON  (hazard H5)
public static boolean irisPerFrameRefresh  = false;                 // :258  IS5-L RETIRED (parity claim holds)
public static int nestedShadowCompositeSuppressCount, nestedShadowCompositeNoopHits;  // :233-234 (javadoc :227-233)
public static final boolean NESTED_SHADOW_COMPOSITE_PROBE = Boolean.getBoolean("seamlessportals.nestedShadowCompositeProbe"); // :237
// qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface
public void healPreviousFrameUniforms();   // :198 — resolves via the PIPELINE MANAGER (never the woven field),
                                           //        instrument = getFrameUpdateNotifier().onNewFrame() ONLY
public Object getPipeline(LevelRenderer);  // :243   public void setPipeline(LevelRenderer, Object);  // :253
public void reloadPipelines();             // :267 -> Iris.getPipelineManager().destroyPipeline()  (OUR name, not iris's)
```

```glsl
// ================= PACK FACTS (all re-verified verbatim this session) ===========================
shaders.properties:161-188   SEVEN COLORED_LIGHTING tiers; the quoted rows are inside `#elif COLORED_LIGHTING == 512`
shaders.properties:178  image.voxel_img          = voxel_sampler          red_integer r16ui   unsigned_int true  false 512 256 512
shaders.properties:179  image.floodfill_img      = floodfill_sampler      rgba        rgba16f half_float  false false 512 256 512
shaders.properties:180  image.floodfill_img_copy = floodfill_sampler_copy rgba        rgba16f half_float  false false 512 256 512
shaders.properties:199  image.wsr_img            = wsr_sampler            red_integer r16ui   unsigned_int true  false 512 64 512
shaders.properties:217  bufferObject.0 = 810549248        // 773.0 MiB, PER PIPELINE, at WSR=1 & CL>=512
shaders.properties:218  image.wsr_lod_img        = wsr_lod_sampler        red_integer r8ui    unsigned_int true  false 128 16 128
shaders.properties:223  image.playerAtlas_img    — the only OTHER clear=false custom image; INERT (WORLD_SPACE_PLAYER_REF=-1)
shaders.properties:240  uniform.float.framemod2 = frameCounter % 2
lib/common.glsl:155     #define TAA_MODE 1 //[0 1]        // sidecar has NO override  => TAA IS ON
lib/common.glsl:487     const int shadowMapResolution = 4096;              // SHADOW_QUALITY=3
lib/uniforms.glsl:155-157   usampler3D voxel_sampler; sampler3D floodfill_sampler; sampler3D floodfill_sampler_copy;
lib/voxelization/lightVoxelization.glsl:5-7     const ivec3 voxelVolumeSize = ivec3(CLI, CLI*0.5, CLI);   // COMPILE-TIME
lib/voxelization/lightVoxelization.glsl:16-18   SceneToVoxel: camera-block-anchored  => all cameras index the SAME texels
lib/voxelization/lightVoxelization.glsl:31/35   GetVoxelVolume / GetVoxelVolumeRaw  -> texelFetch(voxel_sampler,...)
lib/voxelization/lightVoxelization.glsl:100/102 GetLightVolume parity select (floodfill_sampler_copy | floodfill_sampler)
lib/voxelization/lightVoxelization.glsl:416     imageStore(voxel_img, ...)   // from shadow.glsl:249/323, SHADOW+VERTEX only
program/shadowcomp.glsl:26      const ivec3 workGroups = ivec3(64, 32, 64);   // x local 8^3 = 67.1M invocations (§4.4)
program/shadowcomp.glsl:62-63   light = (px+py+pz+nx+ny+nz) / 6.42;           // retention 0.93458 per step
program/shadowcomp.glsl:81-82   posOffset = floor(previousCameraPosition) - floor(cameraPosition);      // HAZARD H1
                                previousPos = pos - ivec3(posOffset);
program/shadowcomp.glsl:120     GetVoxelVolumeRaw(pos)
program/shadowcomp.glsl:127-138 half-rate spreading split by framemod2
program/shadowcomp.glsl:146     light = clamp(light, 0.0, 1000.0);            // clamps but does NOT zero (H7)
program/shadowcomp.glsl:148-152 imageStore(floodfill_img_copy | floodfill_img, pos, light);   // parity write
lib/lighting/mainLighting.glsl:388      lightVolume = GetLightVolume(voxelPosM);
lib/lighting/mainLighting.glsl:390      specialLighting = lightVolume.rgb;
lib/lighting/mainLighting.glsl:394-395  lightmapXM = max(lightmapXM, mix(lightmapXM, 10.0, lightVolume.a));
                                        specialLighting *= 1.0 + 50.0 * lightVolume.a;    // a==0 => ACT GONE (H3)
lib/materials/materialMethods/connectedGlass.glsl:64,79,91,95,103   GetVoxelVolume(...)   // GBUFFER voxel readers (H16)
lib/materials/materialMethods/wavingBlocks.glsl:164-165             GetVoxelVolume(...)
lib/materials/specificMaterials/others/endPortalEffect.glsl:83      GetVoxelVolume(...)
lib/materials/specificMaterials/translucents/netherPortal.glsl:66   GetVoxelVolume(...)
lib/antialiasing/taa.glsl:60-66   Reprojection from gbufferPreviousModelView/Projection + previousCameraPosition
lib/antialiasing/taa.glsl:198/200 colortex2 history read
lib/antialiasing/taa.glsl:203     if (tempColor == vec3(0.0) || any(isnan(tempColor))) { temp = color; return; }  // H15
lib/antialiasing/fxaa.glsl:188    if (dot(texelFetch(colortex2, texelCoord,0).rgb, vec3(1.0)) < 0.01) skipFXAA = 0.0;
lib/antialiasing/jitter.glsl:15   jitterOffsets[int(framemod8)]     // re-phased by preparePipeline's COUNTER.reset (H4)
program/composite6.glsl:43        /* DRAWBUFFERS:32 */   // the ONLY colortex2 writer, exhaustively confirmed
program/composite7.glsl:23-32     FXAA gate (user sidecar: FXAA_STRENGTH=70)
grep "flip" shaders.properties -> 0 lines.   find -name "*.csh" -> world-1/, world0/, world1/shadowcomp.csh only.
```

---

## 10. UNVERIFIED / GAPS (consolidated, honest)

1. **[BLOCKER-GRADE] Whether any mechanism exists to read or install per-dest previous-camera state.** Class is package-private, held in no field; our own heal never reads it. **Wholly unrecon'd.** → G1(a).
2. **Whether the nested *cross-dim* shadow map is non-empty on the current stack.** The `C:0/0` item at `PORTAL_VIEW_POLISH_HANDOFF.md:151` is explicitly *"never diagnosed on the current stack"*. If it is empty, `voxel_img` is all-zero for the dest and half 1 is refuted before it starts. → G0.
3. **Whether `ExtendedShader.iris$setupState` is reached on every sodium chunk draw** (vs. a per-frame short-circuit somewhere upstream). `ProgramImages/ProgramSamplers.update()` have no short-circuit; the call sites were not exhaustively traced. → P4 (re-hooked for per-program attribution).
4. **Real GPU residency and dispatch cost on the user's hardware.** Not knowable statically. Shadow-map (~256 MiB) and colortex (~200 MiB) baseline figures are **estimates**. → G3.
5. **`Field.set` on a private final `int` + C2 folding on Temurin 25.** Legality is project-proven for instance finals (`POLISH_SESSION_NOTES.md:59-60`); folding of a primitive int in a JIT-hot `IntSupplier` is not. Moot if mechanism B is chosen. → P7.
6. **Exhaustiveness of the `destroyPipeline` call-site analysis.** Three referencing classes found; "no mid-frame destroy" is DERIVED from their contexts, not proven.
7. **Whether `MyGameRenderer.renderWorldNew` (the layer-0 decomposed driver) reaches `ShadowRenderer.renderShadows` / `ShadowCompositeRenderer.renderAll`.** Not traced. Decides whether `CrossPortalViewRendering`/`GuiPortalRendering` are an uncovered lava-phantom path **today**.
8. **Actual GPU storage size of `RGB16F`** (6 B vs padded 8 B) — driver's choice; runtime-only via the guard's existing `glGetTextureLevelParameteri` (`:182`). All half-2 figures use the padded assumption.
9. **Which `ImmutableSet<Integer>` ctor arg feeds `FinalPassRenderer`'s SwapPass list** — verified positionally (`aload 7`), wiring DERIVED. Low risk (MAIN-is-canonical is independently implied), now also corroborated by the confirmed nested `renderFinalPass`.
10. **Exact iris source line numbers** — everything is verified by bytecode offset, not source line.
11. **`ClearPass` / `ClearPassCreator` / `Blaze3dRenderTargetExt` versioning** — not disassembled; relevant only if half 2 piggybacks on iris's clear machinery or wants a cheap resize detector.
12. **Whether the shared `voxel_img` is correctly dest-seeded during a nested render** — structurally argued (nested `beginLevelRendering` clears it; nested shadow pass re-voxelizes) but never A/B'd. → P6 torch control.

**Searched for and NOT found (first-class negatives):** any field of type `CameraPositionTracker` in iris (0/963); any `getCustomImages()` accessor (0/963); any `reloadPipelines` symbol in iris (0/963 — it is our own facade name); any bindless / `glGetTextureHandle` usage (0/963); any third constant pool holding `GlImage.getId`; any `colortex2` write outside `composite6`'s single `DRAWBUFFERS:32`; any `flip.*` directive in Complementary r5.8.1; any `TAA_MODE` in the settings sidecar; any velocity or prev-depth colortex in the TAA path; any setter or non-final path to `RenderTarget.mainTexture`/`altTexture`; any resize event/callback in `RenderTargets`; any per-frame `BufferFlipper` mutation; any existing per-dest **GPU** cache in the tree; any image/3D/compute usage in sodium 0.9.1; any non-no-op `updateNewSize` for absolute images.

**Dead-code warning:** `ShadowMapSwapper.java` is **not** a precedent — lines 10–121 are commented out, the class body is empty (`:7-8`), and its mechanism is a content copy, not a binding swap. Only its pooled-storage shape (`storageNumLimit = 3`, LIFO deque) is worth reusing. The **only** live in-tree precedents for replacing state inside iris and restoring it are `IrisShadowCompositeSuppressor` (reflective field swap) and `MyGameRenderer`'s `getPipeline`/`setPipeline` bracket (§3.3).
