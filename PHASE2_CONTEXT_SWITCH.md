# Phase 2: Context-Switch Rendering Plan
# LIVING DOCUMENT

## What IP Does (from actual source code)

### Context Switch (ClientWorldLoader.withSwitchedWorld):
```java
// Save state
ClientLevel originalWorld = CLIENT.level;
LevelRenderer originalWorldRenderer = CLIENT.levelRenderer;

// Swap to destination
CLIENT.level = newWorld;
CLIENT.levelRenderer = newWorldRenderer;  // via mixin accessor
CLIENT.particleEngine.setWorld(newWorld);  // via mixin accessor
networkHandler.setWorld(newWorld);         // via mixin accessor

// Execute rendering code
supplier.get();

// Restore state
CLIENT.level = originalWorld;
CLIENT.levelRenderer = originalWorldRenderer;
CLIENT.particleEngine.setWorld(originalWorld);
networkHandler.setWorld(originalNetHandlerWorld);
```

### Per-Dimension LevelRenderer:
IP creates a SEPARATE LevelRenderer for each dimension:
```java
// IP's constructor (older MC):
LevelRenderer worldRenderer = new LevelRenderer(
    CLIENT, entityRenderDispatcher, blockEntityRenderDispatcher, renderBuffers
);

// MC 26.1.2's constructor (6 params):
public LevelRenderer(
    Minecraft minecraft,
    EntityRenderDispatcher entityRenderDispatcher,
    BlockEntityRenderDispatcher blockEntityRenderDispatcher,
    RenderBuffers renderBuffers,
    GameRenderState gameRenderState,           // NEW in 26.1.2
    FeatureRenderDispatcher featureRenderDispatcher  // NEW in 26.1.2
)
```

### What Gets Swapped:
1. `Minecraft.level` → destination ClientLevel
2. `Minecraft.levelRenderer` → destination LevelRenderer (private field, needs mixin)
3. `ParticleEngine.level` → destination world (needs mixin)
4. `ClientPacketListener.level` → destination world (needs mixin)

### What IP does NOT swap:
- Camera position (set separately via WorldRenderInfo)
- Projection matrix (reused from main render)
- Player entity (stays in original dimension)

## What We Need for MC 26.1.2

### Step 1: Create secondary LevelRenderer
- Need: Minecraft instance, entity/block entity dispatchers, render buffers
- NEW for 26.1.2: GameRenderState, FeatureRenderDispatcher
- **I'm not sure** if GameRenderState and FeatureRenderDispatcher can be shared
  between LevelRenderers or need separate instances

### Step 2: Create secondary ClientLevel (ALREADY DONE - PortalDimensionManager)
- We already create ClientLevel for the nether
- But it's not connected to a LevelRenderer yet

### Step 3: Feed chunks into secondary ClientLevel via vanilla's pipeline
- Currently we use RemoteChunkManager with raw section data
- For Phase 2, we need proper chunks in the secondary ClientLevel's ChunkCache
- This means feeding our received chunk data through ClientChunkCache.replaceWithPacketData()

### Step 4: Connect secondary LevelRenderer to secondary ClientLevel
- Call `newLevelRenderer.setLevel(newClientLevel)` → triggers allChanged()
- This creates SectionRenderDispatcher and ViewArea for the nether
- Chunk meshes get compiled automatically

### Step 5: Context-switch and re-render
- During portal rendering (inside stencil mask):
  1. Save: Minecraft.level, Minecraft.levelRenderer, particle engine, etc.
  2. Swap to nether: set all fields to nether instances
  3. Call LevelRenderer.renderLevel() with transformed camera
  4. Restore all fields

### Major Challenges:
1. **Minecraft.levelRenderer is private** → need mixin @Accessor
2. **GameRenderState sharing** → need to investigate if shared instance works
3. **FeatureRenderDispatcher sharing** → same investigation needed
4. **Framegraph recursion** → MC 26.1.2 uses framegraph; calling renderLevel()
   recursively inside the framegraph might cause issues
5. **RenderBuffers sharing** → IP shares renderBuffers between LevelRenderers
6. **Chunk mesh compilation** → needs background thread time, might lag on first load

### Fields to Mixin/Access:
```
Minecraft.level           → public (can set directly)
Minecraft.levelRenderer   → private (need @Accessor mixin)
ParticleEngine.level      → private (need @Accessor mixin)  
ClientPacketListener.level → need to find field name in 26.1.2
```

### Investigation Needed Before Coding:
1. Read Minecraft class to find levelRenderer field name and accessibility
2. Read ParticleEngine to find world/level field
3. Read GameRenderState to understand if it can be shared
4. Read FeatureRenderDispatcher to understand sharing
5. Test: can LevelRenderer.renderLevel() be called recursively in 26.1.2?
6. Test: do RenderBuffers support concurrent use by two LevelRenderers?

## Investigation Results (2026-04-11)

### Minecraft.levelRenderer
- Declaration: `public final LevelRenderer levelRenderer`
- PUBLIC but FINAL - cannot set directly
- Need mixin @Mutable + @Accessor to remove final and expose setter
- Or use reflection: `Field f = Minecraft.class.getDeclaredField("levelRenderer"); f.setAccessible(true); f.set(mc, newRenderer);`

### GameRenderState
- Simple mutable data holder with public final fields:
  - LevelRenderState levelRenderState
  - LightmapRenderState lightmapRenderState
  - GuiRenderState guiRenderState
  - OptionsRenderState optionsRenderState
  - WindowRenderState windowRenderState
- **Can be shared** between LevelRenderers since it's just a data container
- BUT: LevelRenderState is populated during extractLevel() for one specific world
- For Phase 2, we'd need to re-extract for the destination world

### LevelRenderer Constructor (MC 26.1.2):
```java
new LevelRenderer(
    minecraft,                           // shared
    entityRenderDispatcher,              // shared
    blockEntityRenderDispatcher,         // shared
    renderBuffers,                       // shared (IP does this too)
    gameRenderer.getGameRenderState(),   // can share the container
    gameRenderer.getFeatureRenderDispatcher()  // needs investigation
)
```

### FeatureRenderDispatcher - NOT YET INVESTIGATED
- Need to read this class to determine if it can be shared
- It handles entity/particle rendering dispatch
- May have per-frame state that conflicts

### Open Questions:
1. Can FeatureRenderDispatcher be shared? Need to read source.
2. Can we call renderLevel() recursively inside the framegraph?
3. How does the ViewArea/SectionRenderDispatcher handle chunk meshes
   for a world that isn't the "current" one?
4. How to handle extractLevel() for the destination world?
   - extractLevel() populates LevelRenderState from the current world
   - We need a separate extract for the nether world
5. Will the framegraph support nested execution?

### FeatureRenderDispatcher - INVESTIGATED
- Has many per-frame renderers (shadow, flame, model, nameTag, etc.)
- Stores bufferSource and crumblingBufferSource - per-frame resources
- **I'm not sure** if sharing between two LevelRenderers is safe
- Probably needs a SEPARATE instance for the secondary LevelRenderer
- But creating a separate one requires its own buffer sources

### Assessment: Phase 2 Complexity
Phase 2 is a MAJOR undertaking. It requires:
1. Secondary LevelRenderer with its own SectionRenderDispatcher and ViewArea
2. Mixin to make Minecraft.levelRenderer mutable
3. Context-switch logic (save/restore 4+ fields)
4. Chunk feeding into secondary ClientLevel via vanilla pipeline
5. extractLevel() for the secondary world
6. Recursive framegraph execution (may not be supported)
7. Camera transformation for the secondary render
8. Performance optimization (two worlds' chunk meshes in GPU memory)

### Recommended Approach:
Start with the SIMPLEST possible Phase 2:
1. Create secondary LevelRenderer + ClientLevel
2. Call setLevel() to connect them
3. Feed ONE chunk of data to test mesh compilation
4. Try a context-switch render (swap fields, call renderLevel, restore)
5. See what crashes/works and iterate

### What We Have Working (Phase 1):
- Stencil buffer infrastructure (DEPTH24_STENCIL8 on all FBOs)
- Portal shape stencil masking (portalStencilOnly render type)
- Colored block destination rendering (portalNoDepthColor render type)
- Correct axis orientation for both X and Z portals
- Portal detection with correct width measurement
- Chunk data pipeline (server → client)
- Background quad to block overworld view (partially working)

## ALL UNKNOWNS RESOLVED (2026-04-11)

### 1. FeatureRenderDispatcher: CANNOT SHARE ❌
- Has mutable per-frame state (SubmitNodeStorage with AVL tree map)
- renderSolidFeatures() modifies internal state
- clearSubmitNodes() is destructive
- No synchronization - concurrent calls would corrupt state
- **MUST create separate instance per LevelRenderer**

### 2. Recursive renderLevel(): SAFE ✅
- FrameGraphBuilder is created fresh EVERY call (new instance)
- execute() has NO re-entrancy guards (not needed - instances are independent)
- Nested calls use separate BitSet, ArrayList, etc.
- GPU commands serialize naturally
- **SAFE to call recursively inside stencil pass**

### 3. GPU Memory: ~1.6 GB per SectionRenderDispatcher ⚠️
- 128 MB vertex heap + 32 MB staging per chunk layer
- 5-6 chunk layers = ~1 GB per dispatcher
- TWO dispatchers = ~3.2 GB total GPU memory
- **RISK: Low-VRAM GPUs will fail**
- **Mitigation: Lazy allocation, smaller buffer for portal view**

### 4. extractLevel(): Needs Separate Call ✅
- Each LevelRenderer has its own levelRenderState (instance field)
- extractLevel() populates levelRenderState from this.level
- MUST pass Camera positioned in destination dimension
- MUST have destination chunks compiled (SectionRenderDispatcher ready)
- **Safe to call independently for secondary world**

### 5. RenderBuffers: Need Separate Instance ✅
- Each LevelRenderer needs its own RenderBuffers
- BufferSource, crumblingBufferSource, outlineBufferSource all per-instance
- Current Phase 1 doesn't share (manual vertex building)
- **Phase 2 must create new RenderBuffers for secondary renderer**

## PHASE 2 MINIMUM VIABLE IMPLEMENTATION

Based on these findings, the simplest Phase 2 that could work:

1. Create secondary LevelRenderer:
   ```java
   RenderBuffers destRenderBuffers = new RenderBuffers(...);
   FeatureRenderDispatcher destFeatureDispatcher = new FeatureRenderDispatcher(...);
   LevelRenderer destRenderer = new LevelRenderer(
       mc, mc.getEntityRenderDispatcher(), mc.getBlockEntityRenderDispatcher(),
       destRenderBuffers, gameRenderState, destFeatureDispatcher
   );
   ```

2. Connect to secondary ClientLevel:
   ```java
   destRenderer.setLevel(netherClientLevel);
   // This creates SectionRenderDispatcher + ViewArea → ~1.6 GB GPU memory
   ```

3. Feed chunks via vanilla pipeline:
   ```java
   netherClientLevel.getChunkSource().replaceWithPacketData(x, z, buf, heightmaps, blockEntities);
   // Triggers chunk mesh compilation automatically
   ```

4. During portal rendering (inside stencil mask):
   ```java
   // Save state
   ClientLevel savedLevel = mc.level;
   // Swap
   mc.level = netherClientLevel;
   // Extract + render with destination camera
   destRenderer.extractLevel(tracker, destCamera, partialTick);
   destRenderer.renderLevel(allocator, tracker, false, destCameraState, ...);
   // Restore
   mc.level = savedLevel;
   ```

## RISKS AND MITIGATION
- GPU memory: Start with small render distance for portal (4 chunks)
- Performance: Chunk compilation on background threads, may take time
- Stability: Thoroughly test context switch save/restore
- Framegraph: Nested execution is safe but untested at scale

## EXACT CONSTRUCTOR SIGNATURES (Verified from Source)

### RenderBuffers:
```java
public RenderBuffers(int maxSectionBuilders)
// maxSectionBuilders = Runtime.getRuntime().availableProcessors()
// Allocates: SectionBufferBuilderPool, BufferSource (768KB), crumbling buffers
```

### FeatureRenderDispatcher:
```java
public FeatureRenderDispatcher(
    SubmitNodeStorage submitNodeStorage,      // new SubmitNodeStorage()
    ModelManager modelManager,                // mc.getModelManager() - CAN SHARE
    MultiBufferSource.BufferSource bufferSource,  // renderBuffers.bufferSource()
    AtlasManager atlasManager,                // mc.getAtlasManager() - CAN SHARE
    OutlineBufferSource outlineBufferSource,  // renderBuffers.outlineBufferSource()
    MultiBufferSource.BufferSource crumblingBufferSource, // renderBuffers.crumblingBufferSource()
    Font font,                                // mc.font - CAN SHARE
    GameRenderState gameRenderState           // gameRenderer.getGameRenderState() - CAN SHARE
)
```

### LevelRenderer:
```java
public LevelRenderer(
    Minecraft minecraft,                       // mc - SHARED
    EntityRenderDispatcher entityRenderDispatcher, // mc.getEntityRenderDispatcher() - SHARED
    BlockEntityRenderDispatcher blockEntityRenderDispatcher, // mc.getBlockEntityRenderDispatcher() - SHARED
    RenderBuffers renderBuffers,               // NEW INSTANCE for secondary
    GameRenderState gameRenderState,           // gameRenderer.getGameRenderState() - SHARED
    FeatureRenderDispatcher featureRenderDispatcher // NEW INSTANCE for secondary
)
```

### Fields That Need Mixin Access:
```
Minecraft.levelRenderer      → public final → @Mutable @Accessor or reflection
ParticleEngine.level          → protected → reflection or @Accessor
ClientPacketListener.level    → private → reflection or @Accessor
```

## ASSUMPTIONS FOR PHASE 2 IMPLEMENTATION

1. RenderBuffers can be created independently with any maxSectionBuilders value
2. FeatureRenderDispatcher's shared params (ModelManager, AtlasManager, Font,
   GameRenderState) are read-only during rendering and safe to share
3. EntityRenderDispatcher and BlockEntityRenderDispatcher can be shared
   (they prepare() once per frame, and we'll call prepare() for dest world too)
4. Creating a second SectionRenderDispatcher (~1.6GB GPU) won't crash on
   GPUs with 4GB+ VRAM
5. The secondary LevelRenderer's setLevel() will trigger allChanged() which
   compiles chunk meshes on background threads
6. Chunk mesh compilation for the nether will complete within a few seconds
   after chunks are loaded
7. The framegraph allows nested execution (verified: FrameGraphBuilder is per-instance)

## UPDATED FINDING: RenderBuffers Sharing

IP shares CLIENT.renderBuffers() between all LevelRenderers.
This is safe because context-switch means only ONE renderer runs at a time.

In MC 26.1.2, the same logic applies:
- During portal rendering, we swap to the secondary LevelRenderer
- Only the secondary renderer's methods are called
- When we swap back, only the main renderer runs
- No concurrent access → sharing is safe

**REVISED APPROACH: Share RenderBuffers (matches IP architecture)**
This avoids allocating duplicate buffer memory.

However, FeatureRenderDispatcher CANNOT be shared (has per-frame mutable state
that gets populated during submitEntities/submitBlockEntities).
Need separate FeatureRenderDispatcher with separate SubmitNodeStorage.

## IP's Exact Secondary Renderer Creation (from source):
```java
// IP creates LevelRenderer with SHARED renderBuffers:
LevelRenderer worldRenderer = new LevelRenderer(
    CLIENT,
    CLIENT.getEntityRenderDispatcher(),
    CLIENT.getBlockEntityRenderDispatcher(),
    CLIENT.renderBuffers()  // SHARED
);

// IP creates ClientLevel with the secondary renderer:
ClientLevel newWorld = new ClientLevel(
    mainNetHandler, properties, dimension, dimensionType,
    chunkLoadDistance, simulationDistance,
    CLIENT::getProfiler,    // Note: older MC has Supplier<ProfilerFiller>
    worldRenderer,          // secondary LevelRenderer
    CLIENT.level.isDebug(),
    CLIENT.level.getBiomeManager().biomeZoomSeed
);
```

## Our MC 26.1.2 Equivalent:
```java
// Create FeatureRenderDispatcher (CANNOT share - has mutable state):
SubmitNodeStorage destSubmitNodes = new SubmitNodeStorage();
FeatureRenderDispatcher destFeatureDispatcher = new FeatureRenderDispatcher(
    destSubmitNodes,
    mc.getModelManager(),         // shared (read-only)
    mc.renderBuffers.bufferSource(),  // shared (context-switch safe)
    mc.getAtlasManager(),         // shared (read-only)
    mc.renderBuffers.outlineBufferSource(), // shared
    mc.renderBuffers.crumblingBufferSource(), // shared
    mc.font,                      // shared (read-only)
    mc.gameRenderer.getGameRenderState()  // shared (mutable but sequential)
);

// Create LevelRenderer with SHARED renderBuffers:
LevelRenderer destRenderer = new LevelRenderer(
    mc,
    mc.getEntityRenderDispatcher(),
    mc.getBlockEntityRenderDispatcher(),
    mc.renderBuffers,             // SHARED (context-switch safe)
    mc.gameRenderer.getGameRenderState(),  // shared
    destFeatureDispatcher         // SEPARATE instance
);

// Create ClientLevel and connect:
ClientLevel netherLevel = new ClientLevel(
    mc.getConnection(), levelData, Level.NETHER, dimensionType,
    4, 4, destRenderer, false, 0L, mc.level.getSeaLevel()
);

// Connect renderer to level:
destRenderer.setLevel(netherLevel);
// → creates SectionRenderDispatcher (~1.6GB GPU)
// → creates ViewArea
// → chunk mesh compilation starts on background threads
```

## PHASE 2 CODE WRITTEN (2026-04-11 07:09)

### Files Created:
1. MinecraftAccessorMixin.java - @Mutable @Accessor for levelRenderer field
2. PortalWorldManager.java - Creates secondary LevelRenderer + ClientLevel

### Compilation: CLEAN ✅
### Runtime: NO CRASHES ✅ (Phase 2 code loaded but not yet triggered)

### What renderBuffers Access Taught Us:
- Field is `private final RenderBuffers renderBuffers`
- Accessor method is `public RenderBuffers renderBuffers()` (NOT a field access)
- Always verify accessibility - don't assume public

### Next Steps:
1. Wire PortalWorldManager into PortalContextSwitch
2. Replace colored block drawing with context-switch render
3. Test: does the secondary LevelRenderer compile chunk meshes?
4. Test: does renderLevel() work nested inside the stencil pass?
5. Handle camera transformation for the secondary render

## ASSUMPTION VERIFICATION RESULTS (2026-04-11)

### 1. RenderBuffers sharing: ❌ UNSAFE
MultiBufferSource.BufferSource has `startedBuilders` map that accumulates
vertex data. If renderer A adds to buffer but doesn't endBatch(), renderer B
sees stale data. IP explicitly calls endBatch() before portal rendering.
**FIX: Call bufferSource.endBatch() BEFORE and AFTER context switch.**

### 2. EntityRenderDispatcher sharing: ✅ SAFE  
prepare() overwrites state completely. No accumulation. Safe.

### 3. GameRenderState sharing: ⚠️ NEEDS MITIGATION
LevelRenderState holds entity/block entity lists that get populated during
extractLevel(). reset() is called AFTER rendering, not before.
**FIX: Call levelRenderState.reset() BEFORE extractLevel() for dest world.**
Or better: create separate GameRenderState per renderer.

### 4. SubmitNodeStorage: ✅ SAFE (separate instances preferred)
Has mutable state but can be safely separated. Using separate instances
per FeatureRenderDispatcher is the cleanest approach.

### 5. Background chunk compilation: ✅ SAFE
SectionRenderDispatcher uses Util.backgroundExecutor() for async compilation.
setLevel() → allChanged() creates ViewArea and starts compilation.
Estimate: 1-3 seconds for initial compilation at render distance 4.

### 6. Nested renderLevel(): ⚠️ UNSAFE (matrix stack corruption)
RenderSystem.modelViewStack is STATIC. Nested renderLevel() pushes matrices
without knowing about the outer call's push. Stack gets corrupted.
RenderSystem.projectionMatrixBuffer is also STATIC and overwritten.
**FIX: Save/restore modelViewStack depth and projectionMatrix around nested call.**
```java
// Before nested render:
Matrix4fStack stack = RenderSystem.getModelViewStack();
stack.pushMatrix(); // Save current state
RenderSystem.backupProjectionMatrix(); // Saves projection

// After nested render:
RenderSystem.restoreProjectionMatrix();
stack.popMatrix(); // Restore to before nested render
```

## REVISED CONTEXT-SWITCH PROCEDURE

Based on verified assumptions, the safe context-switch procedure is:

```java
// 1. Flush pending draws
bufferSource.endBatch();                    // FIX for Assumption 1

// 2. Save state
ClientLevel savedLevel = mc.level;
LevelRenderer savedRenderer = mc.levelRenderer;
Matrix4fStack stack = RenderSystem.getModelViewStack();
stack.pushMatrix();                          // FIX for Assumption 6
RenderSystem.backupProjectionMatrix();       // FIX for Assumption 6

// 3. Reset dest state
destRenderer.levelRenderState.reset();       // FIX for Assumption 3

// 4. Swap to destination
mc.level = destLevel;
((MinecraftAccessorMixin)mc).seamlessportals$setLevelRenderer(destRenderer);

// 5. Prepare + Extract + Render
entityRenderDispatcher.prepare(destCamera, null);  // Assumption 2: safe
blockEntityRenderDispatcher.prepare(destCameraPos);
destRenderer.extractLevel(deltaTracker, destCamera, partialTick);
destRenderer.renderLevel(allocator, deltaTracker, false, destCameraState, ...);

// 6. Restore state
mc.level = savedLevel;
((MinecraftAccessorMixin)mc).seamlessportals$setLevelRenderer(savedRenderer);
RenderSystem.restoreProjectionMatrix();
stack.popMatrix();

// 7. Flush dest draws
bufferSource.endBatch();                    // FIX for Assumption 1
```

## SECONDARY RENDERER CREATION: SUCCESS (2026-04-11 07:22)

### What was created:
- SubmitNodeStorage (new instance)
- FeatureRenderDispatcher (new instance with shared ModelManager/AtlasManager/Font)
- LevelRenderer (new instance with shared RenderBuffers)
- ClientLevel for nether (16 sections)
- Connected via setLevel() → triggers allChanged() → SectionRenderDispatcher created

### Result: NO CRASHES ✅
The secondary rendering pipeline initializes cleanly alongside the main one.

### IP's switchAndRenderTheWorld() flow documented in IP_CONTEXT_SWITCH_EXACT.md
Key learnings:
1. IP saves ~15 pieces of state before switching
2. IP creates a FRESH Camera and FRESH Matrix4fStack
3. IP calls GameRenderer.renderLevel() (the outer call)
4. IP explicitly flushes bufferSource.endBatch() if not using secondary buffers
5. IP nulls the transparency shader during portal render

### Next: Implement the actual renderLevel() call
Currently falls back to Phase 1. Need to:
1. Flush buffers
2. Save state
3. Swap level + renderer
4. Create fresh matrix stack
5. Call renderLevel()
6. Restore state

## PHASE 2 ATTEMPT #1: FAILED (2026-04-11 08:48)

### What Was Tried
Called `destRenderer.renderLevel()` inside the AFTER_TRANSLUCENT_TERRAIN hook
with a ClearSkipMixin to prevent the framebuffer clear from destroying the
main world. Expected the stencil test (GL_EQUAL, 1) to clip all draws to
the portal area.

### What Happened
Destination world rendered EVERYWHERE, not just inside the portal. The stencil
masking was completely broken. Nether netherrack walls covered the entire screen.

### Root Cause Analysis
`LevelRenderer.renderLevel()` creates a full FrameGraph with multiple passes:
1. CLEAR pass (skipped by our mixin — correct)
2. SKY pass — creates its own RenderPass
3. MAIN pass — creates its own RenderPass
4. CLOUDS, WEATHER, etc.

Each pass creates a NEW RenderPass via `createRenderPass()`. The RenderPass
calls `applyPipelineState()` which sets depth/color/blend state. While the
pipeline doesn't EXPLICITLY touch stencil, the act of creating a new
RenderPass and binding an FBO may reset GL stencil state in MC 26.1.2's
rendering backend.

**Key insight: IP does NOT call renderLevel() from INSIDE another world's
framegraph execution.** IP intercepts the render at a HIGHER level — it
controls the entire render flow. IP's renderLevel() for the dest world
runs as a TOP-LEVEL render, not nested inside an existing framegraph.

Our hook at AFTER_TRANSLUCENT_TERRAIN fires INSIDE the main world's
framegraph pass execution. Calling renderLevel() here creates a NESTED
framegraph that interferes with the outer one.

### Why The Stencil Didn't Work
The stencil test was set before calling renderLevel(). But renderLevel()
creates multiple RenderPasses. Each RenderPass:
1. Binds an FBO via createRenderPass()
2. Runs applyPipelineState()
3. Draws content
4. Closes via finishRenderPass() → binds FBO 0

The stencil state MAY have been lost during FBO transitions, or the
renderLevel() framegraph bound DIFFERENT FBOs (sky target, translucent
target, etc.) that don't have our stencil values.

### Lesson Learned
Cannot call the full renderLevel() from inside AFTER_TRANSLUCENT_TERRAIN.
Need a different approach that doesn't create a nested framegraph.

### Correct Approach: renderGroup(OPAQUE) directly ✅ WORKING

Uses `ChunkSectionsToRender.renderGroup(OPAQUE, sampler)` directly:
- Creates ONE RenderPass on the MAIN render target (where stencil lives)
- NO clears (OptionalInt.empty(), OptionalDouble.empty() in createRenderPass)
- applyPipelineState() NEVER touches stencil (verified: MC 26.1.2 has ZERO
  stencil references in entire codebase)
- GL_STENCIL_TEST with GL_EQUAL(1) persists untouched through the RenderPass
- Terrain fragments only pass where stencil=1 (inside portal)
- Only renders terrain blocks (no sky/entities/weather) — acceptable tradeoff

### Why renderLevel() failed (DOCUMENTED)
renderLevel() creates a full framegraph with multiple passes:
- CLEAR pass on main target (skipped by ClearSkipMixin, but...)
- SKY pass → may use different render targets
- MAIN pass → multiple RenderPasses on DIFFERENT FBOs
  (translucent target, entity outline target, particle target)
- These other FBOs DON'T have our stencil values
- Content renders everywhere, breaking the stencil mask

IP avoids this because IP intercepts at the TOP LEVEL (MyGameRenderer),
not inside a framegraph pass. IP's renderLevel() runs as a separate
top-level render. Our Fabric hook fires INSIDE the main framegraph.

## PHASE 2 WORKING (2026-04-11 09:10)

### What Works:
- Textured nether terrain visible through portal (netherrack, glowstone, etc.)
- Stencil masking correctly clips terrain to portal opening
- Obsidian occlusion still works (depth-tested stencil write)
- Phase 1 fallback during chunk compilation delay
- Separate success/failure logging

### Architecture:
1. Create virtual Camera at dest position (IP's transformPoint pattern)
2. Build Frustum from dest camera rotation + main projection
3. Call destRenderer.extractLevel() → builds chunk draw lists
4. Call destChunks.renderGroup(OPAQUE) → renders through stencil
5. Save/restore shared levelRenderState.chunkSectionsToRender

### Key Findings:
- ChunkSectionInfo stores per-section model-view matrix from dest camera
- Terrain shader uses per-section UBO, NOT global model-view stack
- Fog set via RenderSystem.setShaderFog() before renderGroup()
- Lightmap is global (uses main world's) — IP swaps per dimension
- Chunk compilation is async, takes ~1-3 seconds after feeding

### Chunk Compilation Issue (FIXED):
ClientChunkCache.replaceWithPacketData() fires chunk load events to
mc.levelRenderer (main renderer), NOT our secondary renderer. Sections
were never marked dirty → never compiled. Fixed by explicitly calling
destRenderer.setSectionDirtyWithNeighbors() after feeding each chunk.

### Files Modified for Phase 2:
- PortalContextSwitch.java — renderGroup() implementation
- PortalDimensionManager.java — feed chunks + mark sections dirty
- PortalWorldManager.java — feedExistingChunks() + mark dirty
- CameraInvokerMixin.java — Camera.setPosition/setRotation access
- ClearSkipMixin.java — skip framebuffer clear (for future use)
- GameRendererAccessorMixin.java — FogRenderer access
- LevelRendererAccessorMixin.java — viewArea + visibleSections access
- seamlessportals-common.mixins.json — 4 new mixins registered

## PHASE 2 BOTH DIRECTIONS WORKING (2026-04-11 09:47)

### Overworld→Nether:
- 81 chunks fed, 1296 sections compiled
- Textured netherrack, glowstone, warped nylium visible
- Stencil masking perfect, obsidian occlusion correct
- Dark caves visible with proper 3D depth

### Nether→Overworld:
- 81 chunks fed, overworld terrain rendered
- Hill silhouettes and trees visible through portal
- Red tint issue: nether lightmap applied to overworld terrain

### Critical Fixes Made:
1. **Occlusion graph bypass**: BFS requires hasAllNeighbors() (8 surrounding chunks).
   With sparse portal chunks, BFS can't traverse. Fixed by directly iterating
   ViewArea sections, compiling sync, adding to visibleSections manually.
2. **Fog corruption**: fogRenderer.updateBuffer() permanently overwrites GPU buffer.
   savedFog GpuBufferSlice points to same overwritten buffer. Removed fog update.
3. **Per-dimension chunksEverFed**: Was a single boolean. Nether feed set it true,
   overworld feed skipped. Changed to Set<ResourceKey<Level>>.

## DEEP ANALYSIS: WHY IP WORKS AND WE DON'T (2026-04-11 11:05)

### What IP does differently at EVERY level:

**1. CHUNK PIPELINE: IP uses vanilla's network handler, we use custom packets**
IP modifies ClientPacketListener to route chunk packets to the CORRECT ClientLevel
based on dimension. When the server sends chunks, IP intercepts them and feeds
them to the right ClientLevel. This means:
- Light data arrives via ClientboundLightUpdatePacket → properly applied
- Chunk data arrives via ClientboundLevelChunkPacket → proper deserialization
- Heightmaps, block entities, biomes ALL included
- The LevelLightEngine receives and processes light updates normally

WE send custom RemoteChunkDataPayload packets with ONLY section data + light.
Our light data gets queued via queueSectionData() and we force-apply with
runLightUpdates(). But the light engine's internal state (section tracking,
neighbor tracking) isn't fully set up because we bypass vanilla's flow.

**2. FOG: IP swaps FogContext per dimension, we don't swap fog at all**
IP has FogRendererContext.swappingManager that saves/restores fog per dimension.
The destination world gets its own fog parameters (nether = red, overworld = blue).
WE removed fog swapping because fogRenderer.updateBuffer() permanently overwrites
the GPU buffer. Need a SEPARATE fog GPU buffer per portal view.

**3. CHUNK LOADING TIMING: IP pre-loads chunks continuously, we load on-demand**
IP's ClientWorldLoader maintains secondary ClientLevels that continuously receive
chunk updates from the server. Chunks are ALWAYS up-to-date.
WE only send chunks via PortalChunkTracker which scans every 2 seconds and sends
chunks batch-by-batch. Chunks arrive gradually, causing incomplete terrain.

**4. LOADING SCREEN: IP intercepts dimension change at the network level**
IP replaces the dimension change handler to prevent the loading screen.
It pre-creates the destination ClientLevel BEFORE the teleportation packet
arrives, so the player transitions seamlessly.
WE use vanilla's dimension change which includes the loading screen.

### REMAINING ISSUES (ACTIVE - 2026-04-11 11:05):

### Known Issues (ACTIVE - 2026-04-11 10:21):

1. **Overworld terrain dark through nether portal**: Light data not applying.
   ROOT CAUSE: `queueSectionData()` puts data into `queuedSections` map with
   `hasInconsistencies=true` (LayerLightSectionStorage line 205). The queued data
   is only applied during `swapSectionMap()` (line 263) which is called from
   `LightEngine.runUpdates()` (line 151). For the secondary ClientLevel, 
   `runUpdates()` NEVER runs because the light engine isn't ticked.
   
   FIX NEEDED: After queueing all light data, call `lightEngine.runUpdates()`
   to force-apply the queued DataLayers. Or replicate what vanilla does in
   ClientPacketListener (lines 920-931):
   ```
   lightEngine.setLightEnabled(chunkPos, false);
   // queue null for all sections
   // updateSectionStatus for all sections  
   lightEngine.setLightEnabled(chunkPos, true);
   // queue actual data
   // Call runUpdates or equivalent
   ```
   
   The vanilla flow also calls `level.setSectionDirtyWithNeighbors()` AFTER
   queuing light data, which triggers section recompilation with correct light.

2. **Loading delay**: Chunks arrive gradually from PortalChunkTracker.
   FIX: Start sending chunks as soon as portal is DETECTED (during
   scanForPortalsNearPlayer), not just when player is near.

3. **Loading screen**: Vanilla teleportation creates a loading screen.
   FIX: IP intercepts the teleportation to make it seamless. Need to
   mixin into the teleportation handler.

4. **Chunk coverage**: Limited to render distance 4 (81 chunks).
   FIX: Increase render distance for portal chunks.

## DEEP RE-ANALYSIS: WHY THE VIEW AND TELEPORT DIVERGED (2026-04-11 14:20)

### Exact divergences from IP that were still present

1. **We eagerly called `PortalForcer.createPortal()` during detection**
   - That blocks the server thread while terrain generates and the obsidian frame is built.
   - This is the source of the big lag spike right after lighting a portal.
   - It also means our first render depends on a synchronous world-gen side effect instead of a stable transform.

2. **Render and teleport were using different transform math**
   - Render path: axis-aware local-space transform in `PortalContextSwitch`
   - Teleport path: old center-offset transform in `PortalLink.transformPosition`
   - Result: the portal could show one place but teleport the player somewhere else.
   - IP does not allow this split; both rendering and teleport use the same portal transform.

3. **We were treating the temporary destination as final**
   - When the reverse portal does not exist yet, the first view must be temporary.
   - IP keeps one canonical server-side transform and updates it when the real portal is known.
   - We were mixing temporary guessed links with “actual” links too early.

### What changed to return toward IP

1. **Detection no longer creates the reverse portal**
   - `PortalManager.findOrCreateDestinationPortal()` now:
     - uses an existing tracked portal if present
     - uses `PortalForcer.findClosestPortalPosition()` if vanilla already has one
     - otherwise publishes a temporary virtual link only
   - No synchronous `createPortal()` during detection anymore.

2. **Vanilla creation now corrects the link later**
   - `PortalForcerMixin` now only observes real vanilla `createPortal()` calls.
   - When the first actual teleport causes vanilla to create the reverse portal, that creation is registered and the server re-links using the real portal position.

3. **Canonical transform utility introduced**
   - `PortalTransform.transformPoint(...)`
   - `PortalTransform.transformVector(...)`
   - `PortalTransform.transformYaw(...)`
   - This is now the intended single source of truth for both view and teleport.

### Why this matters

This gets us back to IP’s real architecture:
- server-authoritative links
- temporary render transform first, corrected by real portal creation later
- one shared transform path for rendering and teleport

If the portal still feels “off” after this, the next place to check is no longer portal creation —
it is whether every caller uses `PortalTransform` and whether teleport timing matches the render-side crossing point.

## CRITICAL FIXES (2026-04-11 13:00)

### Root Cause: Wrong Destination Position
The CLIENT was computing portal destinations independently using scaled coordinates
(overworld / 8 = nether). This was ALWAYS wrong because vanilla's PortalForcer
places portals at valid terrain locations that can be tens of blocks away.

Example from logs: Virtual dest at (1, 71, -8), ACTUAL portal at (-6, 58, -1).
That's 7 blocks X, 13 blocks Y, 7 blocks Z off!

### IP's Architecture (What We Must Follow)
1. SERVER is the ONLY authority on portal positions (via PortalForcer)
2. SERVER sends link data to CLIENT via network packet
3. CLIENT NEVER computes destination positions
4. SERVER creates destination portal via PortalForcer.createPortal() if it doesn't exist

### Fixes Applied:
1. **PortalLinkPayload**: New S→C packet with both portal positions + axes
2. **PortalManager.findOrCreateDestinationPortal()**: Uses PortalForcer.createPortal()
   when findClosestPortalPosition() returns empty. Sends PortalLinkPayload to all clients.
3. **PortalDetector.onNetherPortalDetectedClient()**: No longer creates virtual links.
   Only registers portal shape for stencil rendering. Links come from server.
4. **PortalInfo AABB**: Fixed swapped axes in computeBoundingBox(). axis=X now
   extends X by width, axis=Z extends Z by width.
5. **PortalInfo normal**: Fixed to return perpendicular direction, not width direction.
6. **Camera transform**: Axis-aware decomposition into depth/width/height, remapped
   to destination portal's coordinate system. Yaw rotated 90° when axes differ.
