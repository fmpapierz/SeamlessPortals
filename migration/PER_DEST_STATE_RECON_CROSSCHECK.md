# Per-dest state engagement — findings established FIRST-HAND (not via the recon panel)

Established by direct javap / source reading before the recon workflow returned. These are the
cross-check set: if the panel's fold contradicts one of these, the fold is wrong until it produces
better evidence.

## F1 — Iris keeps ONE PIPELINE PER DIMENSION, and custom images are per-pipeline instance state

- `PipelineManager` field: `private final Map<NamespacedId, WorldRenderingPipeline> pipelinesPerDimension`
- `IrisRenderingPipeline` field: `private final Set<GlImage> customImages`  (INSTANCE field, not static)

**Mechanism chain (fully traced, bytecode + source):**
- `MixinLevelRenderer.iris$setupPipeline` (injected in `LevelRenderer.render`) calls
  `Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimension())`.
- `Iris.getCurrentDimension()` reads `Minecraft.getInstance().level` and derives a `NamespacedId`.
- Our nested dest render DOES swap it: `MyGameRenderer.switchAndRenderTheWorldFullPipeline` sets
  `client.level = newWorld` (~line 595) before the direct 8-arg `render()`.
⇒ a cross-dim nested render re-selects the DEST dimension's pipeline, which owns its own `customImages`.

**!! SCOPE CLAIM RETRACTED — CONTRADICTED BY USER OBSERVATION (2026-07-25).** I inferred from the above
that cross-dim windows already have a private ACT volume and are therefore correct, i.e. half 1 was
same-dim-only. The user, asked directly, reports the wrong in-window bounce **"was in a same dim portal,
but it happens in both."** Standing rule: the user's live observation outranks my bytecode inference.
Half 1 must cover BOTH portal kinds, and the cross-dim case has an as-yet-UNKNOWN root cause.

Competing hypotheses for the cross-dim case (to be settled by PROBE, not by more theorizing):
- **H-A (dimension-map collapse):** `getCurrentDimension()` returns the dimension-specific id ONLY if
  `ShaderPack.getDimensionMap()` contains that key; otherwise it falls back to a dimensionType-derived
  id. Two distinct dimensions can therefore collapse to the SAME `NamespacedId` ⇒ the SAME pipeline ⇒
  a shared volume ⇒ same-dim-style pollution even across dimensions. Especially likely for the mod's
  custom/alternate dims (S19 skylands etc.), less so for overworld↔nether (distinct world0/world-1).
- **H-B (dest volume never converges):** the dest pipeline's volume is genuinely separate but is never
  properly filled — its shadowcomp/flood-fill may not run (or runs on a stale/fresh volume) during the
  nested dest render ⇒ the window shows absent/wrong colored light by a DIFFERENT mechanism than the
  same-dim case.
- **H-C:** the two cases share no mechanism and merely look alike to the eye.
Discriminator: log the pipeline object IDENTITY + the resolved `NamespacedId` for the main pass vs the
nested dest pass, and whether a shadowcomp dispatch occurred on the dest pipeline. If identity differs
⇒ H-A refuted; if the dest pipeline saw zero flood-fill dispatches ⇒ H-B confirmed.

The related code comments (IrisCompatOn262Renderer:413 "the dest reads its own per-dim pipeline";
IS5-FF :249 "cross-dim dest pipelines are structurally untouched") are consistent with the bytecode but
are NOT evidence about the observed symptom — treat them as unverified w.r.t. the cross-dim complaint.

**Cost reframing:** the user already pays one full ACT volume set per VISITED DIMENSION today
(1 GiB at COLORED_LIGHTING=512). A per-dest volume is the same order as an already-accepted cost,
not a novel one.

## F2 — GlImage's texture id is IMMUTABLE. The naive "repoint the image" design is dead.

- `GlImage extends GlResource`; `GlResource` field is `private final int id`.
- `GlImage.getId()` → `getGlId()` → that final field. No setter, no re-`setup()` path that changes id.

So you CANNOT give a `GlImage` a different GL texture. Any design phrased as "swap the image object
under the pack's binding" or "repoint floodfill_img" is mechanically impossible as written. This also
means no final-field reflection is needed — good, because JEP-500-era final-field reflection was already
ledgered as a hazard for IS5-FF.

## F3 — THE FEASIBILITY VERDICT: bind-call interception WORKS, because both binds resolve the id dynamically

Image path:
- `ImageBinding`: `private final int imageUnit; private final IntSupplier textureID;`
- `ImageBinding.update()` bytecode: `getfield textureID` → `IntSupplier.getAsInt()` → **static**
  `IrisRenderSystem.bindImageTexture(int,int,int,boolean,int,int,int)`
  i.e. the id is re-resolved on EVERY bind — nothing is baked at program-build time.

Sampler path (the same storage is also exposed as a sampler — the pack directive's first token is
`floodfill_sampler`):
- `SamplerBinding`: `private final int textureUnit; private final IntSupplier texture; ...`
- funnels through **static** `IrisRenderSystem.bindTextureToUnit(int,int,int)`

**Therefore:** the swap is a pure ID SUBSTITUTION at two static, mixin-able call sites, active only
while a dest render is in flight. No cache to defeat, no ImmutableList to rebuild, no final field to
write. This is the design spine.

Open sub-questions this raises (for the design panel, NOT settled here):
- enumerate EVERY bind site for these images (ImageClearPass, the shadowcomp compute dispatch, and any
  direct bind outside ImageBinding/SamplerBinding) — an unintercepted site would read the wrong volume;
- identity test for "is this id one of the floodfill images" must be by GlImage identity/name from the
  pipeline's `customImages`, resolved per pipeline, not by a hardcoded id;
- whether `voxel_img` (clear=true, 128 MiB) must also be per-dest or can stay shared.

## F4 — THE BRACKET: both halves install at the same seam, but at DIFFERENT granularity than IS5-FF

`IrisCompatOn262Renderer`:
- **FRAME-scoped** (`onBeforeHandRendering`): `IrisTemporalTargetGuard.save()` :234 … `try` :243 →
  `IrisShadowCompositeSuppressor.install()` :252 → `renderPortals()` :253 … `finally` :254 →
  `uninstall()` :259 → `IrisTemporalTargetGuard.restore()` :276 → IS5-PH heal :288.
- **PER-PORTAL** (`invokeWorldRendering` :388): `bumpPerFrameUniformCounter()` :406 →
  `IrisTemporalTargetGuard.clearForDestPass()` :416 (the IS5-G zeroing half 2 replaces) →
  `try { MyGameRenderer.renderWorldFullPipeline(...) } finally { bump }` :417-422.

Both halves belong at the PER-PORTAL bracket (:416-422) — that is the "shared machinery" the commission
predicted, and it is confirmed by code shape rather than assumed.

**GRANULARITY MISMATCH (a real design problem):** IS5-FF's suppression is installed FRAME-scoped for the
whole portal phase, but half 1 needs un-suppression PER PORTAL (only for portals that have a dest volume
bound). Cleanest resolution: leave install/uninstall frame-scoped and make
`NoopShadowCompositeRenderer.renderAll()` conditional — delegate to `savedReal` when a dest volume is
currently swapped in, no-op otherwise. Cheap, keeps the existing throw-safety placement intact.

**KEYING PROBLEM:** `invokeWorldRendering(WorldRenderInfo)` does NOT receive the `Portal`. Per-dest keying
needs an identity source — either from `WorldRenderInfo`, from the `PortalRendering` layer stack, or by
having `doRenderPortal` (:296) stash the current portal. Must be resolved before the design panel.

## F5 — IP's own sketch for this problem class was COPY-based and BOUNDED

`ShadowMapSwapper.java` is a fully commented-out shell (ported for source fidelity, never live), but it
is upstream IP's design sketch for exactly "give the nested render its own persistent GPU state":
a bounded POOL (`storageNumLimit = 3`) with acquire/restitute, and save/restore by
`GL43C.glCopyImageSubData`.

Useful two ways: (a) the BOUNDED-POOL + eviction shape is proven prior art worth reusing for the
per-dest cache; (b) copy-based save/restore is the FALLBACK if interception is refuted — but note the
bandwidth: at 512 MiB per floodfill image, copying in+out per portal per frame is on the order of
GiB/frame, i.e. tens of GiB/s at 60fps. Treat copy-based as infeasible for the ACT volume unless the
math says otherwise; it remains fine for colortex-sized data (IrisTemporalTargetGuard already does
exactly that today for TAA history).

## F6 — TAA IS CURRENTLY ON (corrects a stale project memory)

Pack default `#define TAA_MODE 1` (shaders/lib/common.glsl:155); the user's settings sidecar
`ComplementaryReimagined_r5.8.1.zip.txt` has NO `TAA_MODE` override (it holds only non-default keys, and
was rewritten today 17:14 when Bloom was re-enabled). Therefore half 2 (per-dest TAA history) is LIVE
and worth building — the memory note "the user runs TAA_MODE=0" is stale and must be corrected.

Also confirmed active: `COLORED_LIGHTING=512` (ULTRA profile value), `WORLD_SPACE_REFLECTIONS=1`,
`SHADOW_QUALITY=3`, `shadowDistance=256.0`, `FXAA_STRENGTH=70`, `TAA_JITTER=2`.

## F7 — VRAM arithmetic, from the pack declaration (shaders.properties:177-180 @ COLORED_LIGHTING=512)

512 x 256 x 512 = 67,108,864 texels.
- `floodfill_img`      rgba16f  = 8 B/texel → 536,870,912 B = **512 MiB**  (clear=false, PERSISTENT)
- `floodfill_img_copy` rgba16f  = 8 B/texel → **512 MiB**                  (clear=false, PERSISTENT)
- `voxel_img`          r16ui    = 2 B/texel → **128 MiB**                  (clear=true, re-cleared)

Per-dest duplicate of the floodfill pair = **1024 MiB**; +128 MiB if voxel must also be per-dest.
At COLORED_LIGHTING=256 (256x128x256 = 8,388,608 texels): 64 MiB / 64 MiB / 16 MiB → 128 MiB per dest.
The 512→256 step is a **8x** reduction and is the obvious mitigation lever if VRAM is tight.
