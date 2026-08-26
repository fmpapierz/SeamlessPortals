# MC 26.1.2 → 26.2 API Migration Map

Source of truth: decompiled MC 26.2 vanilla (MOJANG/official mappings) at `C:\Users\warwa\ModDev\mc262-ref`.
Every citation is `path/File.java:line` relative to that root. Snippets are quoted verbatim from 26.2 vanilla.

> **Headline architectural change (read first).** The old `MultiBufferSource`/`BufferSource` batched-immediate rendering model is GONE. There is no `RenderType.draw(MeshData)`, no `RenderBuffers.bufferSource()/outlineBufferSource()/crumblingBufferSource()`, and no `FeatureRenderDispatcher` buffer-source fields. Vanilla now uses a **submit → prepare → execute** pipeline: render code pushes `SubmitNode`s into a `SubmitNodeStorage`/`SubmitNodeCollector`, the `FeatureRenderDispatcher` prepares a frame, and drawing happens against the GPU-abstraction `RenderPass` (`com.mojang.blaze3d.systems.RenderPass`). Single meshes are drawn by building a `MeshData`, uploading it to a `GpuBuffer`, and issuing `RenderPass.setPipeline/setVertexBuffer/draw(...)` directly.

---

## GameRenderer — `net/minecraft/client/renderer/GameRenderer.java`

### 1. `getGlobalSettingsUniform()` — NOT FOUND (no getter)
There is **no public getter**. The field is private with no accessor:

```java
// GameRenderer.java:128
private final GlobalSettingsUniform globalSettingsUniform = new GlobalSettingsUniform();
```
Only references: declaration `:128`, `close()` `:169`, `update(...)` `:411`. (verified via grep — no `getGlobalSettingsUniform`, no other reads.)

**An Accessor mixin is required.**
- Target class: `net.minecraft.client.renderer.GameRenderer`
- Field name: `globalSettingsUniform`
- Field type: `net.minecraft.client.renderer.GlobalSettingsUniform` (class at `net/minecraft/client/renderer/GlobalSettingsUniform.java`)

**Note (likely better integration point):** `GlobalSettingsUniform.update(...)` itself ends with `RenderSystem.setGlobalSettingsUniform(this.buffer)` (`GlobalSettingsUniform.java:38`). The uniform is applied **globally through `RenderSystem`**, not bound per-pipeline. If the mod re-renders the world for a portal view, calling vanilla's existing `globalSettingsUniform.update(...)` (via accessor) or re-driving `RenderSystem.setGlobalSettingsUniform(...)` is the faithful path. `GlobalSettingsUniform` has no other public API besides `update(...)` and `close()`.

### 2. `gameRenderState()`, `mainCamera()`, `mainRenderTarget()` — ALL EXIST
| Method | Signature | Line |
|---|---|---|
| `gameRenderState()` | `public GameRenderState gameRenderState()` | `:192` |
| `mainCamera()` | `public Camera mainCamera()` | `:657` |
| `mainRenderTarget()` | `public RenderTarget mainRenderTarget()` | `:673` |

`GameRenderState` type = `net.minecraft.client.renderer.state.GameRenderState`. `RenderTarget` = `com.mojang.blaze3d.pipeline.RenderTarget`.

### 3. `nightVisionScale(...)` — param is `LivingEntity`
```java
// GameRenderer.java:367
public static float nightVisionScale(LivingEntity camera, float a) {
    MobEffectInstance nightVision = camera.getEffect(MobEffects.NIGHT_VISION);
    return !nightVision.endsWithin(200) ? 1.0F : 0.7F + Mth.sin((nightVision.getDuration() - a) * (float) Math.PI * 0.2F) * 0.3F;
}
```
**Param type is `LivingEntity` (NOT `LocalPlayer`).** Full sig: `public static float nightVisionScale(net.minecraft.world.entity.LivingEntity, float)`.

### Bonus GameRenderer getters the port will likely need
- `public RenderBuffers renderBuffers()` `:184`
- `public FeatureRenderDispatcher featureRenderDispatcher()` `:188`
- `public Lighting lighting()` `:701`
- `public GpuTextureView lightmap()` `:661` / `public GpuTextureView levelLightmap()` `:665`
- `public OverlayTexture overlayTexture()` `:669`
- `public void setLevel(@Nullable ClientLevel level)` `:705` (this is GameRenderer's own; see also LevelExtractor.setLevel)

---

## Minecraft — `net/minecraft/client/Minecraft.java`

### `Minecraft.getMainRenderTarget()` — REMOVED
There is no `getMainRenderTarget()`. Vanilla obtains the target via the public `gameRenderer` field:
```java
// Minecraft.java:289
public final GameRenderer gameRenderer;
// Minecraft.java:660 (representative usage)
RenderTarget mainRenderTarget = this.gameRenderer.mainRenderTarget();
```
Other vanilla call sites: `:1307`, `:2735`. **Use `minecraft.gameRenderer.mainRenderTarget()`.** Confirmed.

---

## LevelRenderer — `net/minecraft/client/renderer/LevelRenderer.java`

### Constructor — CHANGED (now 8 params, no `RenderBuffers`/`Options` args)
```java
// LevelRenderer.java:131
public LevelRenderer(
    EntityRenderDispatcher entityRenderDispatcher,
    BlockEntityRenderDispatcher blockEntityRenderDispatcher,
    ModelManager modelManager,
    TextureManager textureManager,
    AtlasManager atlasManager,
    ShaderManager shaderManager,
    GameRenderer gameRenderer,
    int width,
    int height
)
```
It pulls `RenderBuffers`/`FeatureRenderDispatcher`/render-state from the passed `GameRenderer` (`:145-152`). `AtlasManager` = `net.minecraft.client.resources.model.sprite.AtlasManager`; `ShaderManager` = `net.minecraft.client.renderer.ShaderManager`.

### Method-by-method
| 26.1.2 method | 26.2 status |
|---|---|
| `setSectionDirtyWithNeighbors(int,int,int)` | **MOVED to `LevelExtractor`** → `public void setSectionDirtyWithNeighbors(int,int,int)` at `LevelExtractor.java:453` |
| `setLevel(ClientLevel)` | **MOVED to `LevelExtractor`** → `public void setLevel(@Nullable ClientLevel)` at `LevelExtractor.java:393` (note: `GameRenderer.setLevel` `:705` also exists but only sets lighting/camera) |
| `onResourceManagerReload(ResourceManager)` | **MOVED to `LevelExtractor`** → `LevelExtractor.java:389` (LevelExtractor `implements ResourceManagerReloadListener`; LevelRenderer no longer does) |
| `getSectionOcclusionGraph()` | **RENAMED** → `public SectionOcclusionGraph sectionOcclusionGraph()` at `LevelRenderer.java:976` |
| `getSectionRenderDispatcher()` | **RENAMED + nullable** → `public @Nullable SectionRenderDispatcher sectionRenderDispatcher()` at `LevelRenderer.java:908` |
| `extractLevel(DeltaTracker,Camera,float)` | **MOVED + RENAMED to `LevelExtractor`** → `public void extract(DeltaTracker, Camera, float)` at `LevelExtractor.java:95` |
| `update(Camera)` | **NOT FOUND on LevelRenderer.** Closest: per-frame camera update is `GameRenderer.update(DeltaTracker)` `:372` (calls `mainCamera.update`) and `LevelExtractor.extract(...)` does the level-state extraction. See LevelExtractor split below. |
| `renderLevel(...)` | **RENAMED to `render(...)` with new signature** (see below) |

### `render(...)` — the new "renderLevel" (drawing only)
```java
// LevelRenderer.java:156
public void render(
    GraphicsResourceAllocator resourceAllocator,
    DeltaTracker deltaTracker,
    boolean renderOutline,
    CameraRenderState cameraState,
    Matrix4fc modelViewMatrix,
    GpuBufferSlice terrainFog,
    Vector4f fogColor,
    boolean shouldRenderSky
)
```
- `GraphicsResourceAllocator` = `com.mojang.blaze3d.resource.GraphicsResourceAllocator`
- `CameraRenderState` = `net.minecraft.client.renderer.state.level.CameraRenderState`
- `GpuBufferSlice` = `com.mojang.blaze3d.buffers.GpuBufferSlice`

Vanilla call site (how GameRenderer drives it):
```java
// GameRenderer.java:563
this.minecraft.levelRenderer.render(
    this.resourcePool, deltaTracker, renderOutline, cameraState, modelViewMatrix, terrainFog, cameraState.fogData.color, !shouldCreateBossFog);
```

### Other LevelRenderer methods the port will touch
- `public ChunkSectionsToRender prepareChunkRenders(Matrix4fc modelViewMatrix)` `:509` — **builds the `ChunkSectionsToRender`** (the per-frame terrain draw batch). This is the new producer.
- `public @Nullable ViewArea viewArea()` `:960`
- `public ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections()` `:964` / `nearbyVisibleSections()` `:968`
- `public void resize(int,int)` `:763`
- `public void invalidateCompiledGeometry(ClientLevel, Options, Camera, BlockColors)` `:796` (creates/refreshes `SectionRenderDispatcher` + `ViewArea`)
- `public void clearVisibleSections()` `:873`, `public void resetLevelRenderData()` `:878`
- `public boolean isSectionCompiledAndVisible(BlockPos)` `:897`, `public boolean hasRenderedAllSections()` `:893`
- Target getters: `entityOutlineTarget()` `:920`, `translucentTarget()` `:924`, `itemEntityTarget()` `:928`, `particlesTarget()` `:932`, `weatherTarget()` `:936`, `cloudsTarget()` `:940`

---

## LevelExtractor — `net/minecraft/client/renderer/extract/LevelExtractor.java`

**This class is new in the 26.2 render split.** `public class LevelExtractor implements ResourceManagerReloadListener`.

### Constructor
```java
// LevelExtractor.java:89
public LevelExtractor(Minecraft minecraft, LevelRenderState levelRenderState, LevelRenderer levelRenderer)
```
Built in `Minecraft` right after `LevelRenderer` (`Minecraft.java:649`):
```java
this.levelExtractor = new LevelExtractor(this, this.gameRenderer.gameRenderState().levelRenderState, this.levelRenderer);
```

### Public API (extract / setLevel / update / dirty)
| Method | Signature | Line |
|---|---|---|
| extract | `public void extract(DeltaTracker deltaTracker, Camera camera, float deltaPartialTick)` | `:95` |
| setLevel | `public void setLevel(@Nullable ClientLevel level)` | `:393` |
| allChanged | `public void allChanged()` | `:406` |
| onResourceManagerReload | `public void onResourceManagerReload(ResourceManager)` | `:389` |
| blockChanged | `public void blockChanged(BlockPos, @Block.UpdateFlags int)` | `:423` |
| setBlockDirty | `public void setBlockDirty(BlockPos, BlockState old, BlockState new)` | `:447` |
| setBlocksDirty | `public void setBlocksDirty(int,int,int,int,int,int)` | `:437` |
| setSectionDirtyWithNeighbors | `public void setSectionDirtyWithNeighbors(int,int,int)` | `:453` |
| setSectionRangeDirty | `public void setSectionRangeDirty(int,int,int,int,int,int)` | `:457` |
| setSectionDirty | `public void setSectionDirty(int,int,int)` | `:467` |
| resetSampler | `public void resetSampler()` | `:419` |
| countRenderedSections | `public int countRenderedSections()` | `:479` |

There is **no method literally named `update`** on `LevelExtractor`; the per-frame entry point is `extract(...)`. (The old `LevelRenderer.update(Camera)`/`extractLevel(...)` responsibilities are now `extract(...)` here.)

### The LevelRenderer ↔ LevelExtractor split (how they connect)
- **`LevelExtractor` = EXTRACTION (logic/game-thread state capture).** `extract(...)` walks entities/block-entities/particles/sky/weather/border, computes section dirty updates, applies the frustum, and fills the shared **`LevelRenderState`** (`net/minecraft/client/renderer/state/level/LevelRenderState.java`). It drives `LevelRenderer` via the back-reference (e.g. `this.levelRenderer.visibleSections()`, `this.levelRenderer.invalidateCompiledGeometry(...)`, `this.levelRenderer.resetLevelRenderData()`).
- **`LevelRenderer` = DRAWING (render-thread GPU work).** `render(...)` consumes the already-extracted `LevelRenderState`, builds the frame graph, and submits GPU passes.
- **They share one `LevelRenderState`** instance (owned by `GameRenderState`; both get it from `gameRenderer.gameRenderState().levelRenderState`). Extractor writes it; renderer reads it.
- **`ChunkSectionsToRender` is produced by `LevelRenderer.prepareChunkRenders(Matrix4fc)`** (`LevelRenderer.java:509`, returned `:605`), called inside `LevelRenderer.render(...)` at `:211`. It is **not** stored on `LevelRenderState`.
- Per-frame call order (from `GameRenderer`): `extract(...)` → `GameRenderer.extract` calls `minecraft.levelExtractor.extract(deltaTracker, mainCamera, worldPartialTicks)` (`GameRenderer.java:389`); then `render(...)` → `GameRenderer.renderLevel` calls `minecraft.levelRenderer.render(...)` (`GameRenderer.java:563`).

---

## LevelRenderState — `net/minecraft/client/renderer/state/level/LevelRenderState.java`

### `chunkSectionsToRender` field — DOES NOT EXIST
`LevelRenderState` has **no `chunkSectionsToRender` field**. Full field list (`:13-32`): `cameraRenderState`, `sectionUpdateRenderStates`, `entityRenderStates`, `blockEntityRenderStates`, `blockOutlineRenderState`, `blockBreakingRenderStates`, `weatherRenderState`, `worldBorderRenderState`, `skyRenderState`, `particlesRenderState`, `gameTime`, `lastEntityRenderStateCount`, `cloudColor`, `cloudHeight`, `render3dCrosshair`, `playerCompiledSectionCallback`, `chunkLoadingRenderState`, `shouldResetChunkLayerSampler`, `shouldShowEntityOutlines`, `shouldResetSkyRenderer`.

**`ChunkSectionsToRender` is a transient local**, produced by `LevelRenderer.prepareChunkRenders(...)` and consumed within the same `render(...)` call (passed into `addMainPass(...)`). Type = `net.minecraft.client.renderer.chunk.ChunkSectionsToRender` (a `record`, see below).

---

## ViewArea — `net/minecraft/client/renderer/ViewArea.java`

### `sections` field — now PRIVATE, type changed
```java
// ViewArea.java:15
private final RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections;
```
- Exact type: `net.minecraft.client.RotatingSectionStorage<net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection>` (was `SectionRenderDispatcher.RenderSection[]` in older versions).
- **Accessor mixin needed** (SpongePowered): `@Accessor("sections") RotatingSectionStorage<SectionRenderDispatcher.RenderSection> getSections();`

**Before mixing in, note vanilla already exposes lookups** that may remove the need:
- `public int size()` `:46`
- `public SectionRenderDispatcher.@Nullable RenderSection getRenderSectionAt(BlockPos pos)` `:87`
- `protected SectionRenderDispatcher.@Nullable RenderSection getRenderSection(long sectionNode)` `:91` (protected → Invoker mixin if you need it from outside)
- `public boolean repositionCamera(SectionPos)` `:74`, `public SectionPos getCameraSectionPos()` `:83`
- `public void releaseAllBuffers()` `:40`

---

## SectionRenderDispatcher.RenderSection — `net/minecraft/client/renderer/chunk/SectionRenderDispatcher.java`

**The dirty/rebuild API was reworked.** Dirty tracking moved OFF `RenderSection` and ONTO `SectionUpdateTracker.SectionDirtyState`. Rebuild was renamed to compile and takes `RenderSectionRegion` (not `RenderRegionCache`).

| 26.1.2 (`RenderSection`) | 26.2 replacement |
|---|---|
| `isDirty()` | **MOVED** → `SectionUpdateTracker.SectionDirtyState.isDirty()` (`SectionUpdateTracker.java:96`) |
| `setNotDirty()` | **MOVED** → `SectionUpdateTracker.SectionDirtyState.setNotDirty()` (`SectionUpdateTracker.java:77`) |
| `setDirty(boolean)` | **MOVED + renamed param** → `SectionUpdateTracker.SectionDirtyState.setDirty(boolean fromPlayer)` (`SectionUpdateTracker.java:71`); or set via tracker: `SectionUpdateTracker.setDirty(int x,int y,int z, boolean playerChanged)` (`SectionUpdateTracker.java:26`) |
| `rebuildSectionAsync(RenderRegionCache)` | **RENAMED + new param** → `RenderSection.compileAsync(RenderSectionRegion region)` (`SectionRenderDispatcher.java:319`); sync variant `compileSync(RenderSectionRegion)` (`:324`) |

`RenderSectionRegion` = `net.minecraft.client.renderer.chunk.RenderSectionRegion`; produced by `RenderRegionCache.createRegion(level, sectionNode)` (see `LevelExtractor.java:164`). Vanilla usage of the new dirty + compile flow:
```java
// LevelExtractor.java:153-167 (dirty read → schedule update → clear dirty)
SectionUpdateTracker.SectionDirtyState dirtyState = this.sectionUpdateTracker.getDirtyState(section.getSectionNode());
if (dirtyState != null && dirtyState.isDirty() && (... hasAllNeighbors ...)) {
    this.levelRenderState.sectionUpdateRenderStates.add(
        new SectionUpdateRenderState(section.getSectionNode(), dirtyState.isDirtyFromPlayer(),
            cache.createRegion(this.level, section.getSectionNode())));
    dirtyState.setNotDirty();
}
// LevelRenderer.java:635-638 (compile)
if (rebuildSync) { section.compileSync(state.region()); }
else            { section.compileAsync(state.region()); }
```
Useful `RenderSection` members that DID survive: `public final AtomicReference<SectionMesh> sectionMesh` `:206`, `getSectionMesh()` `:253`, `getSectionNode()` `:277`, `getRenderOrigin()` `:272`, `getBoundingBox()` `:238`, `getVisibility(long)` `:221`, `compileAsync/compileSync` `:319/:324`, `resortTransparency()` `:285`.

`SectionRenderDispatcher` constructor also changed:
```java
// SectionRenderDispatcher.java:57
public SectionRenderDispatcher(TracingExecutor executor, RenderBuffers renderBuffers,
    SectionCompiler sectionCompiler, Consumer<SectionRenderDispatcher.RenderSection> onSectionMeshUpdate)
```

---

## RenderBuffers — `net/minecraft/client/renderer/RenderBuffers.java`

### `bufferSource()`, `outlineBufferSource()`, `crumblingBufferSource()` — ALL REMOVED
`MultiBufferSource` is gone from this class. The entire new public API:
```java
public RenderBuffers(int maxSectionBuilders)                 // :12
public SectionBufferBuilderPack fixedBufferPack()            // :17
public SectionBufferBuilderPool sectionBufferPool()          // :21
public StagedVertexBuffer stagedVertexBuffer()               // :25
public void endFrame()                                       // :29
public void close()                                          // :34
```
- `SectionBufferBuilderPack` = `net.minecraft.client.renderer.SectionBufferBuilderPack` (terrain section building only)
- `SectionBufferBuilderPool` = `net.minecraft.client.renderer.SectionBufferBuilderPool`
- `StagedVertexBuffer` = `net.minecraft.client.renderer.StagedVertexBuffer` (the shared GPU staging buffer that replaces the batched buffer-source for feature rendering)

### What replaced the MultiBufferSource/BufferSource batched model
The new model is **submit → prepare → execute**:
1. **Collect:** render code calls `submitXxx(...)` on a **`SubmitNodeCollector`** (`net/minecraft/client/renderer/SubmitNodeCollector.java`) / **`OrderedSubmitNodeCollector`** (`net/minecraft/client/renderer/OrderedSubmitNodeCollector.java`), backed by a **`SubmitNodeStorage`** (`net/minecraft/client/renderer/SubmitNodeStorage.java`). Submissions are `SubmitNode`s grouped by `FeatureRendererType`. Example submit methods: `submitModel(...)`, `submitModelPart(...)`, `submitText(...)`, `submitCustomGeometry(PoseStack, RenderType, CustomGeometryRenderer)`, `submitShapeOutline(...)` (full list at `OrderedSubmitNodeCollector.java:34-188`).
2. **Prepare:** `FeatureRenderDispatcher.prepareFrame(SubmitNodeStorage)` → `PreparedFrame` (`FeatureRenderDispatcher.java:60`), which sorts, prepares feature renderers, and uploads the shared vertex data (`stagedVertexBuffer.upload()` `:107`).
3. **Execute:** `PreparedFrame.executeSolid()/executeTranslucent()/executeTranslucentAfterTerrain()/executeOutline()/executeAlwaysOnTop()` (`:197-256`) issue the actual draws; or one-shot `FeatureRenderDispatcher.renderAllFeatures(SubmitNodeStorage)` (`:112`) which does all phases.

The actual GPU submission abstraction is **`RenderPass`** (`com.mojang.blaze3d.systems.RenderPass`) — created from `RenderSystem.getDevice().createCommandEncoder().createRenderPass(...)`. There is also an **`OrderedSubmitNodeCollector`** ordering layer (`order(int)` → `SubmitNodeCollector.java:10`). Related collector/queue classes live in `net/minecraft/client/renderer/`: `SubmitNodeCollection.java`, `SubmitNodeStorage.java`, `OrderedSubmitNodeCollector.java`, plus `com/mojang/blaze3d/OrderedSubmitNodeCollector.java`/`SubmitNodeCollection.java`/`SubmitNodeCollector.java`/`SubmitNodeStorage.java`/`OrderedSubmitNodeCollector.java`. (No class literally named `OrderedRenderCommandQueue` exists; the ordering role is `OrderedSubmitNodeCollector`.)

---

## FeatureRenderDispatcher — `net/minecraft/client/renderer/feature/FeatureRenderDispatcher.java`

### `bufferSource` / `outlineBufferSource` / `crumblingBufferSource` fields — DO NOT EXIST
The accessor mixin expecting `MultiBufferSource.BufferSource` / `OutlineBufferSource` fields is **fully broken** — those fields are gone. The actual 26.2 fields (`:29-35`):
```java
private final ModelManager modelManager;
private final AtlasManager atlasManager;
private final Font font;
private final GameRenderState gameRenderState;
private final StagedVertexBuffer stagedVertexBuffer;     // <-- replaces all buffer sources
private final FeatureRendererMap featureRenderers;
private final FeatureRenderDispatcher.PreparedFrame preparedFrame;
```
Constructor:
```java
// FeatureRenderDispatcher.java:37
public FeatureRenderDispatcher(RenderBuffers renderBuffers, ModelManager modelManager,
    AtlasManager atlasManager, Font font, GameRenderState gameRenderState)
```
It takes its buffer from `renderBuffers.stagedVertexBuffer()` (`:44`). **Migration:** drop the buffer-source accessor entirely. If the mod needs to push feature submissions for a re-rendered view, use a `SubmitNodeStorage` + `prepareFrame(...)`/`renderAllFeatures(...)` (see `GameRenderer.java:360,582` for vanilla doing exactly this for hand/screen items via `handAndScreenSubmitNodeStorage`). Outlines are handled by `PreparedFrame.executeOutline()` and the entity-outline `RenderTarget`, not a buffer source.

---

## RenderType immediate draw — `net/minecraft/client/renderer/rendertype/RenderType.java`

### `RenderType.draw(MeshData)` — REMOVED
`RenderType` no longer draws anything itself. New shape: `RenderType.prepare()` → `PreparedRenderType`, which draws **from a GPU buffer** (not a raw `MeshData`):
```java
// RenderType.java:52
public PreparedRenderType prepare() { ... }     // builds pipeline+textures+transforms snapshot
// RenderType.java:95
public RenderPipeline pipeline() { return this.state.pipeline; }
```
`PreparedRenderType` (`net/minecraft/client/renderer/rendertype/PreparedRenderType.java`) draws via:
```java
// PreparedRenderType.java:28
public void drawFromBuffer(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, IndexType indexType,
    int baseVertex, int firstIndex, int indexCount)
```

### How vanilla draws a single MeshData immediately (exact sequence)
There is **no one-call helper**. Vanilla (e.g. `SkyRenderer`) builds a `MeshData`, uploads it once to a persistent `GpuBuffer`, then draws via a manual `RenderPass`. Build + upload:
```java
// SkyRenderer.java:88-94
try (ByteBufferBuilder builder = ByteBufferBuilder.exactlySized(10 * DefaultVertexFormat.POSITION.getVertexSize())) {
    BufferBuilder bufferBuilder = new BufferBuilder(builder, PrimitiveTopology.TRIANGLE_FAN, DefaultVertexFormat.POSITION);
    this.buildSkyDisc(bufferBuilder, 16.0F);
    try (MeshData meshData = bufferBuilder.buildOrThrow()) {
        this.topSkyBuffer = RenderSystem.getDevice().createBuffer(() -> "Top sky vertex buffer", 32, meshData.vertexBuffer());
    }
}
```
Immediate **non-indexed** draw (this is the literal `draw(0,3)`→ new-API analog — note `draw(vertexCount,1,0,0)`):
```java
// SkyRenderer.java:307-315
try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder()
        .createRenderPass(() -> "Sky dark", colorTexture, Optional.empty(), depthTexture, OptionalDouble.empty())) {
    renderPass.setPipeline(RenderPipelines.SKY);
    RenderSystem.bindDefaultUniforms(renderPass);
    renderPass.setUniform("DynamicTransforms", dynamicTransforms);
    renderPass.setVertexBuffer(0, this.bottomSkyBuffer.slice());
    renderPass.draw(10, 1, 0, 0);
}
```
Immediate **indexed** (quads) draw, using the shared sequential index buffer:
```java
// SkyRenderer.java:355-365
GpuBuffer indexBuffer = this.quadIndices.getBuffer(6);   // quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder()
        .createRenderPass(() -> "Sky sun", color, Optional.empty(), depth, OptionalDouble.empty())) {
    renderPass.setPipeline(RenderPipelines.CELESTIAL);
    RenderSystem.bindDefaultUniforms(renderPass);
    renderPass.setUniform("DynamicTransforms", dynamicTransforms);
    renderPass.bindTexture("Sampler0", this.celestialsAtlas.getTextureView(), this.celestialsAtlas.getSampler());
    renderPass.setVertexBuffer(0, this.sunBuffer.slice());
    renderPass.setIndexBuffer(indexBuffer, this.quadIndices.type());
    renderPass.drawIndexed(6, 1, 0, 0, 0);
}
```
If you want to reuse a `RenderType`'s pipeline/textures/scissor for a one-off mesh, call `renderType.prepare().drawFromBuffer(vertexBuffer, indexBuffer, indexType, baseVertex, firstIndex, indexCount)` (`PreparedRenderType.java:28`) after uploading your `MeshData.vertexBuffer()` into a `GpuBuffer`.

---

## RenderPipeline.Builder — `com/mojang/blaze3d/pipeline/RenderPipeline.java`

### ALL `Builder` methods (`:161-271`)
| Method | Line |
|---|---|
| `withLocation(String)` / `withLocation(Identifier)` | `:161` / `:166` |
| `withFragmentShader(String)` / `withFragmentShader(Identifier)` | `:171` / `:176` |
| `withVertexShader(String)` / `withVertexShader(Identifier)` | `:181` / `:186` |
| `withShaderDefine(String)` | `:191` |
| `withShaderDefine(String, int)` | `:200` |
| `withShaderDefine(String, float)` | `:209` |
| `withBindGroupLayout(BindGroupLayout)` | `:218` |
| `withPolygonMode(PolygonMode)` | `:227` |
| `withCull(boolean)` | `:232` |
| `withColorTargetState(int index, ColorTargetState)` | `:237` |
| `withUnusedColorTargetState(int index)` | `:243` |
| `withColorTargetState(ColorTargetState)` | `:249` |
| `withDepthStencilState(DepthStencilState)` | `:253` |
| `withDepthStencilState(Optional<DepthStencilState>)` | `:258` |
| `withVertexBinding(int bindingIndex, VertexFormat)` | `:263` |
| `withPrimitiveTopology(PrimitiveTopology)` | `:268` |
| `buildSnippet()` / `build()` | `:337` / `:353` |

### `withUniform(String, UniformType)` — REMOVED from `RenderPipeline.Builder`
It is **not** a pipeline-builder method anymore. Uniforms/samplers are declared on a **`BindGroupLayout`** and attached via `withBindGroupLayout(...)`. The replacements live on `BindGroupLayout.Builder` (`com/mojang/blaze3d/pipeline/BindGroupLayout.java`):
```java
// BindGroupLayout.java:82
public BindGroupLayout.Builder withSampler(String sampler)
// BindGroupLayout.java:87
public BindGroupLayout.Builder withUniform(String name, UniformType type)          // UNIFORM_BUFFER
// BindGroupLayout.java:96
public BindGroupLayout.Builder withUniform(String name, UniformType type, GpuFormat format)  // TEXEL_BUFFER only
```
`UniformType` still exists at `com/mojang/blaze3d/shaders/UniformType.java` with exactly two values: `UNIFORM_BUFFER`, `TEXEL_BUFFER`. So:
`pipeline.withUniform("Foo", UniformType.UNIFORM_BUFFER)` → `pipeline.withBindGroupLayout(BindGroupLayout.builder().withUniform("Foo", UniformType.UNIFORM_BUFFER).build())`.

### `withVertexFormat(VertexFormat, VertexFormat.Mode)` — REPLACED by two calls
- Old single call → now **two** builder calls:
  - `withVertexBinding(int bindingIndex, VertexFormat vertexFormat)` (`:263`) — bind format to a buffer slot (use `0` for the common single-buffer case)
  - `withPrimitiveTopology(PrimitiveTopology primitiveTopology)` (`:268`) — replaces the old `VertexFormat.Mode`
- It does **NOT** take `PrimitiveTopology` on a combined method; topology is its own call. `PrimitiveTopology` = `com.mojang.blaze3d.PrimitiveTopology`.

Vanilla example (`RenderPipelines.java:37-38`):
```java
.withVertexBinding(0, DefaultVertexFormat.BLOCK)
.withPrimitiveTopology(PrimitiveTopology.QUADS)
```

---

## ColorTargetState — `com/mojang/blaze3d/pipeline/ColorTargetState.java`

```java
// ColorTargetState.java:13
public record ColorTargetState(Optional<BlendFunction> blendFunction, GpuFormat format, @WriteMask int writeMask)
```
Write-mask constants (`:14-20`): `WRITE_RED=1`, `WRITE_GREEN=2`, `WRITE_BLUE=4`, `WRITE_ALPHA=8`, `WRITE_COLOR=7`, `WRITE_ALL=15`, `WRITE_NONE=0`.

### Standard color pipeline — what `GpuFormat` does vanilla pass?
For a STANDARD color target, vanilla does **not** call `withColorTargetState(...)` at all — it relies on the default:
```java
// ColorTargetState.java:21
public static final ColorTargetState DEFAULT = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15);
```
So the **default color target `GpuFormat` is `GpuFormat.RGBA8_UNORM`** (there is no `RGBA8` — the constant is `RGBA8_UNORM`), writeMask `15` (`WRITE_ALL`). For blended pipelines vanilla uses the convenience ctor:
```java
// ColorTargetState.java:24
public ColorTargetState(BlendFunction blendFunction) { this(Optional.of(blendFunction), GpuFormat.RGBA8_UNORM, 15); }
// e.g. RenderPipelines.java:88
.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
```

### Write-mask = 0 ("no color write") pipeline — vanilla example
```java
// RenderPipelines.java:416
.withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, 0))
```
And an explicit full 4-arg with WRITE_ALL (no blend):
```java
// RenderPipelines.java:725
.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15))
```
(Another: `:758` uses writeMask `7` = `WRITE_COLOR` with `BlendFunction.ENTITY_OUTLINE_BLIT`.)

`BlendFunction` = `com.mojang.blaze3d.pipeline.BlendFunction`.

---

## TextureTarget — `com/mojang/blaze3d/pipeline/TextureTarget.java`

### Constructor — now `(String, int, int, boolean, GpuFormat)`
```java
// TextureTarget.java:11
public TextureTarget(@Nullable String label, int width, int height, boolean useDepth, GpuFormat format) {
    super(label, useDepth, format);
    RenderSystem.assertOnRenderThread();
    this.resize(width, height);
}
```
### What `GpuFormat` for a standard color framebuffer?
`GpuFormat.RGBA8_UNORM`. The only vanilla `new TextureTarget(...)` color uses:
```java
// LevelRenderer.java:153
this.entityOutlineTarget = new TextureTarget("Entity Outline", width, height, true, GpuFormat.RGBA8_UNORM);
// com/mojang/blaze3d/resource/RenderTargetDescriptor.java:15 (framegraph-created targets)
return new TextureTarget(null, this.width, this.height, this.useDepth, this.format);  // this.format is RGBA8_UNORM for screen-size targets (see LevelRenderer.java:183)
```

---

## RenderPass.draw — `com/mojang/blaze3d/systems/RenderPass.java`

### `draw(int,int)` → `draw(int,int,int,int)`
```java
// RenderPass.java:262
public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance)
```
The 4 params are **`(vertexCount, instanceCount, firstVertex, firstInstance)`**. Old `draw(first, count)` semantics changed: the old 2-arg `draw(0, 3)` (first=0, count=3) becomes **`draw(3, 1, 0, 0)`** (vertexCount=3, instanceCount=1, firstVertex=0, firstInstance=0).

Vanilla non-indexed full-mesh draw (the `draw(0,3)`-style call):
```java
// SkyRenderer.java:314
renderPass.draw(10, 1, 0, 0);     // 10 vertices, 1 instance, from vertex 0
```
Indexed sibling:
```java
// RenderPass.java:160
public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance)
// SkyRenderer.java:364
renderPass.drawIndexed(6, 1, 0, 0, 0);
```
(`firstInstance != 0` requires `deviceFeatures.nonZeroFirstInstance()` — pass `0` `:267`.)

---

## GpuFormat / depth formats — `com/mojang/blaze3d/GpuFormat.java`

### `com.mojang.blaze3d.textures.TextureFormat` — REMOVED ENTIRELY
The old `com.mojang.blaze3d.textures.TextureFormat` enum **no longer exists** (no file under `com/mojang/blaze3d/textures/`, zero references in vanilla). Texture/render-target formats are now the single unified enum **`com.mojang.blaze3d.GpuFormat`**.

### `TextureFormat.DEPTH32` equivalent → `GpuFormat.D32_FLOAT`
```java
// GpuFormat.java:59
D32_FLOAT(GpuFormat.ComponentType.OPAQUE_32, 1),
```

### Depth / stencil format constants (`GpuFormat.java:59-63`)
| Constant | Line | Aspect |
|---|---|---|
| `D32_FLOAT` | `:59` | depth (32-bit float) — **use this for old `DEPTH32`** |
| `D32_FLOAT_S8_UINT` | `:60` | depth + stencil |
| `D24_UNORM_S8_UINT` | `:61` | depth + stencil |
| `D16_UNORM` | `:62` | depth (16-bit) |
| `S8_UINT` | `:63` | stencil only |

Helpers: `hasDepthAspect()` `:93` (true for D32_FLOAT, D32_FLOAT_S8_UINT, D24_UNORM_S8_UINT, D16_UNORM), `hasStencilAspect()` `:97`, `hasColorAspect()` `:89`. Standard color constant = `RGBA8_UNORM` `:14`.

---

## PrimitiveTopology — `com/mojang/blaze3d/PrimitiveTopology.java`

### `QUADS` — EXISTS
```java
// PrimitiveTopology.java:7-15
public enum PrimitiveTopology {
    LINES(2, 2, false), DEBUG_LINES(2, 2, false), DEBUG_LINE_STRIP(2, 1, true),
    POINTS(1, 1, false), TRIANGLES(3, 3, false), TRIANGLE_STRIP(3, 1, true),
    TRIANGLE_FAN(3, 1, true), QUADS(4, 4, false);
}
```
Full set: `LINES, DEBUG_LINES, DEBUG_LINE_STRIP, POINTS, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN, QUADS`. (This is the replacement for the old `VertexFormat.Mode`.)

### `BufferBuilder` constructor — now takes `PrimitiveTopology`
```java
// BufferBuilder.java:42
public BufferBuilder(ByteBufferBuilder buffer, PrimitiveTopology primitiveTopology, VertexFormat format)
```
Confirmed — the middle arg is `PrimitiveTopology` (was `VertexFormat.Mode`). Vanilla usage: `new BufferBuilder(builder, PrimitiveTopology.TRIANGLE_FAN, DefaultVertexFormat.POSITION)` (`SkyRenderer.java:89`).

---

## Appendix — supporting type reference (verified)

- **`ChunkSectionsToRender`** = `record` at `net/minecraft/client/renderer/chunk/ChunkSectionsToRender.java:25`:
  `record ChunkSectionsToRender(GpuTextureView textureView, EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroupsPerLayer, int maxIndicesRequired, GpuBufferSlice[] chunkSectionInfos)`. Draw via `renderGroup(ChunkSectionLayerGroup group, GpuSampler sampler)` (`:31`).
- **`DepthStencilState`** = `record(CompareOp depthTest, boolean writeDepth, float depthBiasScaleFactor, float depthBiasConstant)` at `com/mojang/blaze3d/pipeline/DepthStencilState.java:8`; `DEFAULT = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true)`. (Depth compare is now `com.mojang.blaze3d.platform.CompareOp`.)
- **`RenderPass.Draw<T>`** = `record(int slot, GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer, @Nullable IndexType indexType, int firstIndex, int indexCount, int baseVertex, @Nullable BiConsumer<T, RenderPass.UniformUploader> uniformUploaderConsumer)` at `RenderPass.java:358`.
- **`SectionUpdateTracker`** = `net/minecraft/client/SectionUpdateTracker.java`; constructor `(LevelHeightAccessor, int renderDistance)` `:17`; inner `static class SectionDirtyState` `:61` holds the dirty flags.
