# Minecraft 26.1.2 Render Pipeline - Complete Reference
# LIVING DOCUMENT - Updated as we learn more

## CRITICAL FINDING: WHY STENCIL DOESN'T WORK

### Root Cause: FBO=0 During Fabric Callback

**Source:** `GlCommandEncoder.finishRenderPass()` (line 654-657)
```java
public void finishRenderPass() {
    this.inRenderPass = false;
    GlStateManager._glBindFramebuffer(36160, 0);  // UNCONDITIONALLY binds FBO 0!
    this.device.debugLabels().popDebugGroup();
}
```

**What happens:**
1. `addMainPass()` lambda executes
2. `renderGroup(OPAQUE)` creates a RenderPass → binds game FBO (e.g., FBO 3)
3. Opaque terrain draws on FBO 3
4. RenderPass closes → `finishRenderPass()` → `glBindFramebuffer(GL_FRAMEBUFFER, 0)` → **FBO 0 bound**
5. Fabric `AFTER_SOLID_FEATURES` callback fires → **FBO 0 is active**
6. Our stencil code runs on FBO 0 which has **0 stencil bits**
7. `RenderType.draw()` creates a NEW RenderPass → binds game FBO 3 → draws blocks
8. Stencil values were written to FBO 0, not FBO 3 → **stencil test has no effect**

### The Fix

Stencil values ARE persistent in the FBO's depth-stencil texture. So:
1. The `glClear(GL_STENCIL_BUFFER_BIT)` must happen when the GAME FBO is bound
2. The stencil write (portal shape draw) already happens on the game FBO (via RenderType.draw)
3. The stencil test (destination draw) also happens on the game FBO (via RenderType.draw)

**Fix:** Store the game FBO ID from `RenderTargetMixin.createFbo()` in a static variable.
Then bind it manually before `glClear(GL_STENCIL_BUFFER_BIT)`.

After clearing, unbind (or let RenderType.draw rebind it).
The stencil write in step 5 of the algorithm happens inside RenderType.draw which binds the correct FBO.
The stencil values persist in the FBO's texture even after unbinding.
The stencil TEST in step 7 happens inside another RenderType.draw on the SAME FBO.

## Exact Render Call Order in a Single Frame

### GameRenderer.render() → extract() → renderLevel()

```
GameRenderer.extract(DeltaTracker, boolean advanceGameTime)
  ├── extractCamera() → Camera.update() → Camera.extractRenderState(CameraRenderState)
  ├── LevelRenderer.extractLevel(DeltaTracker, Camera, float)
  │     ├── blockEntityRenderDispatcher.prepare(cameraPos)
  │     ├── entityRenderDispatcher.prepare(camera, crosshairPickEntity)
  │     ├── prepareChunkRenders(modelViewMatrix) → ChunkSectionsToRender
  │     ├── extractVisibleEntities(camera, frustum, deltaTracker, levelRenderState)
  │     ├── extractVisibleBlockEntities(camera, partialTick, levelRenderState)
  │     └── ... weather, sky, particles, debug gizmos
  └── extractGui()

GameRenderer.renderLevel(DeltaTracker deltaTracker)
  ├── projectionMatrix = new Matrix4f(cameraState.projectionMatrix)
  ├── Apply bob/hurt/nausea effects
  ├── RenderSystem.setProjectionMatrix(projBuffer, PERSPECTIVE)
  ├── fogRenderer.updateBuffer(cameraState.fogData)
  ├── terrainFog = fogRenderer.getBuffer(FogMode.WORLD)
  ├── LevelRenderer.renderLevel(resourcePool, deltaTracker, renderOutline,
  │                              cameraState, modelViewMatrix, terrainFog,
  │                              cameraState.fogData.color, !shouldCreateBossFog,
  │                              chunkSectionsToRender)
  ├── Render hand (changes projection, clears depth)
  └── Render screen effects
```

### LevelRenderer.renderLevel() - Framegraph Construction

```java
public void renderLevel(
    GraphicsResourceAllocator resourceAllocator,
    DeltaTracker deltaTracker,
    boolean renderOutline,
    CameraRenderState cameraState,
    Matrix4fc modelViewMatrix,
    GpuBufferSlice terrainFog,
    Vector4f fogColor,
    boolean shouldRenderSky,
    ChunkSectionsToRender chunkSectionsToRender
)
```

**Framegraph passes built in order:**
1. CLEAR pass - clears color+depth
2. SKY pass - renders sky (if enabled)
3. MAIN pass - `addMainPass()` [THE CORE]
4. ENTITY_OUTLINE chain (if glowing entities)
5. CLOUDS pass (if enabled)
6. WEATHER pass - rain/snow + world border
7. TRANSPARENCY chain (if enabled)
8. LATE_DEBUG pass - debug gizmos
9. `frame.execute()` - runs all passes in order
10. Cleanup

### addMainPass() - THE CORE RENDER LOOP

```java
private void addMainPass(
    FrameGraphBuilder frame,
    Frustum frustum,
    Matrix4fc modelViewMatrix,
    GpuBufferSlice terrainFog,
    boolean renderOutline,
    LevelRenderState levelRenderState,
    DeltaTracker deltaTracker,
    ProfilerFiller profiler,
    ChunkSectionsToRender chunkSectionsToRender
)
```

**Inside pass.executes(() -> { ... }):**
```
 1. Set shader fog
 2. chunkSectionsToRender.renderGroup(ChunkSectionLayerGroup.OPAQUE, sampler)
    └── Creates RenderPass → FBO bound → draws opaque chunks → FBO 0
 3. Setup lighting
 4. [FABRIC AFTER_OPAQUE_TERRAIN fires here - FBO=0]
 5. Create PoseStack + MultiBufferSource.BufferSource
 6. submitEntities() + submitBlockEntities() + particles + destroy animation
 7. featureRenderDispatcher.renderSolidFeatures()
 8. bufferSource.endBatch() → Creates RenderPass → draws → FBO 0
 9. [FABRIC AFTER_SOLID_FEATURES fires here - FBO=0]  ← OUR HOOK POINT
10. Copy depth to translucent/itemEntity/particle targets
11. featureRenderDispatcher.renderTranslucentFeatures()
12. bufferSource.endBatch() → FBO 0
13. [FABRIC AFTER_TRANSLUCENT_FEATURES fires here - FBO=0]
14. Block outline rendering
15. Gizmo rendering
16. [FABRIC BEFORE_TRANSLUCENT_TERRAIN fires here - FBO=0]
17. chunkSectionsToRender.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, sampler)
    └── Creates RenderPass → FBO bound → draws translucent chunks → FBO 0
18. [FABRIC AFTER_TRANSLUCENT_TERRAIN fires here - FBO=0]
19. Translucent particles
20. Cleanup
```

**KEY:** Every Fabric callback fires with FBO=0 because each RenderPass
unconditionally unbinds FBO when it closes.

## Where Portal Rendering Must Be Injected

Following IP architecture:
```
renderWorld() {
    render solid things      ← step 2 (OPAQUE terrain)
    renderPortals()          ← INJECT between step 2 and step 17
    render transparent things ← step 17 (TRANSLUCENT terrain)
}
```

**Current hook:** `AFTER_TRANSLUCENT_TERRAIN` (step 18) - after ALL terrain renders.
Moved from AFTER_SOLID_FEATURES to prevent water/cloud bleed-through.
Portal content renders ON TOP of everything, stencil clips to portal shape.
**FBO state at hook:** FBO=0 (must manually bind game FBO for stencil ops)

NOTE: For Phase 2 context-switch rendering, we may need to revisit this
hook point since the destination world needs its own translucent pass.

## Global State to Save/Restore for Context Switch

### Must Save (for full IP-style context switch):
```
Minecraft.level          → ClientLevel instance
Camera fields:
  - position (Vec3)
  - xRot, yRot (float)
  - rotation (Quaternionf)
CameraRenderState:
  - pos, xRot, yRot
  - projectionMatrix (Matrix4f)
  - viewRotationMatrix (Matrix4f)
  - cullFrustum (Frustum)
Fog parameters
RenderSystem.getModelViewStack() state
```

### Pipeline State (managed per-draw, do NOT save):
```
Depth test/func/mask    → SET BY applyPipelineState(), overrides raw GL
Blend enable/func       → SET BY applyPipelineState()
Color mask              → SET BY applyPipelineState()
Cull face               → SET BY applyPipelineState()
Scissor                 → SET BY trySetup() via ScissorState
Stencil                 → NOT SET BY PIPELINE - raw GL persists ← THIS IS KEY
```

## Method Signatures We're Mixin-ing Into

### Already Implemented (verified against source):
```java
// GlBackendMixin - target: GlBackend
// Target method: public void setWindowHints()
@Inject(method = "setWindowHints", at = @At("TAIL"))

// GlConstMixin - target: GlConst
// Target methods:
//   public static int toGlInternalId(TextureFormat textureFormat)
//   public static int toGlExternalId(TextureFormat textureFormat)
//   public static int toGlType(TextureFormat textureFormat)
@Inject(method = "toGlInternalId", at = @At("HEAD"), cancellable = true)
@Inject(method = "toGlExternalId", at = @At("HEAD"), cancellable = true)
@Inject(method = "toGlType", at = @At("HEAD"), cancellable = true)

// RenderTargetMixin - target: GlTexture
// Target method: private int createFbo(DirectStateAccess dsa, int depthid)
@Inject(method = "createFbo", at = @At("RETURN"))

// SectionCompilerMixin - target: ModelBlockRenderer (via Fabric Indigo bypass)
// Target method: compile() → redirects getRenderShape()
@Redirect(method = "compile", target = "BlockState.getRenderShape()")
```

### Rendering Hook (Fabric API):
```java
LevelRenderEvents.AFTER_SOLID_FEATURES.register(context -> { ... });
// Callback type: void afterSolidFeatures(LevelRenderContext context)
// Fires INSIDE pass.executes() lambda, AFTER solid features
// FBO state: 0 (default framebuffer)
```

## GL Constants (NEVER hardcode - always use LWJGL names)

```java
// Stencil
GL11.GL_STENCIL_TEST                = 0x0B90  (2960)
GL11.GL_STENCIL_BUFFER_BIT          = 0x00000400
GL11.GL_ALWAYS                      = 0x0207  (519)
GL11.GL_EQUAL                       = 0x0202  (514)
GL11.GL_KEEP                        = 0x1E00
GL11.GL_REPLACE                     = 0x1E01
GL11.GL_LEQUAL                      = 0x0203

// Depth-Stencil
GL30.GL_DEPTH24_STENCIL8             = 0x88F0  (35056)
GL30.GL_DEPTH_STENCIL_ATTACHMENT     = 0x821A  (33306)
GL30.GL_DEPTH_STENCIL                = 0x84F9  (34041)  // external format
GL30.GL_UNSIGNED_INT_24_8            = 0x84FA  (34042)  // type
GL30.GL_DEPTH_ATTACHMENT             = 0x8D00  (36096)

// Framebuffer
GL30.GL_FRAMEBUFFER                  = 0x8D40  (36160)
GL30.GL_FRAMEBUFFER_BINDING          = 0x8CA6
GL30.GL_FRAMEBUFFER_COMPLETE         = 0x8CD5
GL30.GL_TEXTURE_2D                   = 0x0DE1

// Depth (vanilla uses these via GlStateManager)
GL11.GL_DEPTH_TEST                   = 0x0B71
GL11.GL_DEPTH_BUFFER_BIT             = 0x00000100
```

## Shader Analysis

### terrain.vsh (chunk rendering):
- Attributes: Position(vec3), Color(vec4), UV0(vec2), UV2(ivec2)
- Uniforms: Sampler2, ProjMat, ModelViewMat, ChunkPosition, CameraBlockPos, CameraOffset
- **NO gl_ClipDistance**
- **NO stencil operations**

### position_color.vsh (our portal quad):
- Attributes: Position(vec3), Color(vec4)
- Uniforms: ProjMat, ModelViewMat (via imports)
- **NO gl_ClipDistance**
- Fragment shader: discards if alpha == 0

## Camera System

### Camera.setRotation() (exact code):
```java
protected void setRotation(float yRot, float xRot) {
    this.xRot = xRot;
    this.yRot = yRot;
    this.rotation.rotationYXZ(
        (float) Math.PI - yRot * (float) (Math.PI / 180.0),
        -xRot * (float) (Math.PI / 180.0),
        0.0F
    );
    FORWARDS.rotate(this.rotation, this.forwards);
    UP.rotate(this.rotation, this.up);
    LEFT.rotate(this.rotation, this.left);
    this.matrixPropertiesDirty |= 3;
}
```

### Camera.getViewRotationMatrix() (exact code):
```java
public Matrix4f getViewRotationMatrix(Matrix4f dest) {
    if ((this.matrixPropertiesDirty & 1) != 0) {
        Quaternionf inverseRotation = this.rotation().conjugate(new Quaternionf());
        this.cachedViewRotMatrix.rotation(inverseRotation);
        this.matrixPropertiesDirty &= -2;
    }
    return dest.set(this.cachedViewRotMatrix);
}
```

## Assumptions (all verified)

1. ✅ GL30.GL_DEPTH_STENCIL_ATTACHMENT = 0x821A (33306) - VERIFIED from LWJGL spec
2. ✅ GL 3.3 Core Profile supports stencil with DEPTH24_STENCIL8 - VERIFIED from GL spec
3. ✅ Stencil GL state persists across RenderPass creations - VERIFIED: trySetup() does NOT touch stencil
4. ✅ Same FBO reused for same color+depth pair - VERIFIED: GlTexture.getFbo() caches by depthId
5. ✅ Stencil values persist in FBO texture when FBO is unbound - VERIFIED: OpenGL spec
6. ✅ glClear(GL_STENCIL_BUFFER_BIT) only clears stencil, not depth - VERIFIED: GL spec
7. ✅ FBO=0 during Fabric callbacks - VERIFIED: finishRenderPass() unconditionally binds FBO 0
8. ✅ FBO 0 has 0 stencil bits despite GLFW_STENCIL_BITS=8 request - NEEDS INVESTIGATION
   - GLFW stencil bits affect the DEFAULT framebuffer only
   - The game renders to CUSTOM FBOs which have DEPTH24_STENCIL8 (our mixin)
   - FBO 0 may or may not get stencil depending on driver/GPU

## Open Questions

1. Why does FBO 0 report stencilBits=0 despite GLFW_STENCIL_BITS=8?
   - GL_STENCIL_BITS might not be queryable in Core Profile (GL_INVALID_ENUM error seen)
   - Or the driver didn't honor the request
   - Either way, FBO 0 is NOT where we need stencil - game FBO is

2. How to get the game FBO ID for manual binding?
   - RenderTargetMixin.createFbo() sees it - store in static variable
   - Or query GlStateManager after a RenderType.draw() call

## DIAGNOSTIC UPDATE: FBO Identity Problem

### Finding
`drawPortalShape: fboBefore=0, fboAfter=0, gameFboId=27`

The RenderType.draw() binds some FBO internally then unbinds to 0. We stored gameFboId=27 from RenderTargetMixin.createFbo(). But we DON'T KNOW if RenderType.draw() uses FBO 27.

### Root Question
Does `OutputTarget.MAIN.getRenderTarget().getColorTextureView().getFbo(dsa, depth)` return FBO 27?

### Next Step
Instead of storing gameFboId from createFbo() (which captures ALL FBO creations),
we need to store the FBO that the MAIN render target uses. The RenderTargetMixin
on GlTexture.createFbo() fires for EVERY color texture, not just the main one.

### Possible Fix
Don't store in RenderTargetMixin. Instead, capture inside RenderType.draw() flow
by querying GL_FRAMEBUFFER_BINDING immediately after a known draw occurs.
Or: use the main render target's FBO from the CLEAR pass (which is the first draw each frame).

## BREAKTHROUGH: Stencil Portal Rendering Working (2026-04-11)

### Three Bugs Found & Fixed:
1. **GlTextureView vs GlTexture** - FBOs created by GlTextureView, not GlTexture
2. **FBO identity** - Must discover render FBO at runtime via GlStateManagerMixin
3. **Alpha=0 discard** - position_color.fsh discards alpha=0, prevents stencil write

### Working Stencil Algorithm:
```
1. Enable GL_STENCIL_TEST
2. Bind game FBO, clear stencil to 0, unbind
3. Set stencil: GL_ALWAYS, ref=1, op=GL_REPLACE
4. Draw portal shape (portalNoDepthColor) → stencil=1 written where portal is
5. Set stencil: GL_EQUAL, ref=1 (only pass inside portal)
6. Draw destination blocks (portalNoDepthColor) → clipped to portal shape
7. Reset stencil to 0 (draw portal shape with ref=0)
8. Disable GL_STENCIL_TEST
```

### Key: Both portal shape and destination blocks use NO depth test
This is necessary because:
- Portal shape: must write stencil even where overworld terrain already has depth
- Destination blocks: are positioned behind the portal face depth

### Next Steps:
- Merge portal planes into wider quad for full portal opening
- Fix double pane in nether
- Phase 2: context-switch rendering with vanilla's LevelRenderer

## PORTAL RENDERING WORKING (2026-04-11 06:20)

### What's Working:
- Stencil buffer masks nether blocks to portal opening PERFECTLY
- Orange lava, grey netherrack, dark blocks visible through portal
- Portal acts as a WINDOW into the nether
- Overworld terrain outside, nether terrain inside
- Works from multiple viewing angles

### Bugs Fixed This Session:
1. GlTextureView vs GlTexture mixin target (FBO creation)
2. FBO identity discovery via GlStateManagerMixin
3. Alpha=0 fragment shader discard preventing stencil write
4. Axis orientation swap (NetherPortalBlock.AXIS = width direction, not face direction)
5. Stencil-only render type with colorMask=0 (no dark tint)
6. Merged portal shape quad spanning all portal planes

### Key Insight: NetherPortalBlock.AXIS Meaning
- axis=X: width extends along X, portal face perpendicular to Z → XY quad
- axis=Z: width extends along Z, portal face perpendicular to X → ZY quad
- This was BACKWARDS in all our rendering code until this fix

### Current Limitations:
- Phase 1 colored blocks (no textures, no lighting)
- No camera transformation (view doesn't change with angle)
- No front clipping (blocks between camera and portal visible)
- No recursive portal rendering

## CURRENT WORKING RENDER ALGORITHM (2026-04-11 08:00)

### Complete Stencil Pipeline (5 render types, 7 steps):

```
Step 1: Enable GL_STENCIL_TEST
        Dummy draw to discover render FBO
        Bind render FBO, clear stencil to 0, unbind

Step 2: STENCIL WRITE (portalStencilWithDepth)
        glStencilFunc(ALWAYS, 1, 0xFF)
        glStencilOp(KEEP, KEEP, REPLACE)
        drawMergedPortalShapeWithDepthTest()
        → Depth test (LEQUAL) ensures obsidian occludes stencil

Step 3: Set stencil to EQUAL(1)
        glStencilMask(0x00)

Step 3.5: DEPTH CLEAR (portalDepthClear)
          glDepthRange(1,1) → write depth=1.0 inside stencil mask
          Prevents overworld terrain from drawing through air gaps

Step 3.6: BACKGROUND (portalNoDepthColor)
          Draw opaque background color based on destination dimension

Step 4: DESTINATION BLOCKS (portalNoDepthColor)
        renderDestinationWorld() - Phase 1 colored blocks

Step 4.5: DEPTH SHIELD (portalDepthClear)
          glDepthRange(0,0) → write depth=0.0 inside stencil mask
          Blocks clouds/weather from drawing over portal content

Step 5: STENCIL RESET (portalStencilOnly - no depth test)
        glStencilFunc(ALWAYS, 0, 0xFF)
        drawMergedPortalShape() → reset stencil to 0
        Disable GL_STENCIL_TEST
```

### Render Types:
| Name | Color | Depth Test | Depth Write | Purpose |
|------|-------|-----------|-------------|---------|
| portalStencilWithDepth | NONE | LEQUAL | NO | Step 2: stencil write with obsidian occlusion |
| portalStencilOnly | NONE | NONE | NO | Step 5: stencil reset |
| portalDepthClear | NONE | ALWAYS | YES | Steps 3.5, 4.5: depth manipulation |
| portalNoDepthColor | FULL | NONE | NO | Steps 3.6, 4: visible content |
