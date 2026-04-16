# Immersive Portals Deviations Analysis
## Updated 2026-04-14 — After Commits 1-4, comprehensive test results

---

## Current Pipeline Status

### What WORKS (verified by logs + behavior):
- Portal detection, linking, chunk loading with light ✓
- Secondary LevelRenderer with isolated LevelRenderState ✓
- Single ClientLevel per dimension (no duplicate levels) ✓
- Full context switch: mc.level + mc.levelRenderer + mainRenderTarget ✓
- renderLevel() on secondary FBO — SUCCESS in both directions ✓
- Fog computed for destination dimension ✓
- Seamless dimension transitions (no loading screen) ✓
- Recursion guard prevents infinite loops ✓

### What DOESN'T WORK:
- **Composite**: FBO content not displayed through portal
- Portal appears transparent (invisible purple swirl, see-through)
- The FBO has correct content but the composite step fails

### Current Blocker: Composite
See "Composite Attempts" section below.

---

## Composite Attempts (detailed)

### Attempt 1: Stencil-based full-screen blit
```java
createRenderPass(name, mainRT.colorView, empty, mainRT.depthView, empty)
pass.setPipeline(ENTITY_OUTLINE_BLIT)
pass.bindTexture("InSampler", secondaryFbo.colorView, sampler)
pass.draw(0, 3) // full-screen triangle
```
**Expected**: stencil EQUAL(1) clips to portal area
**Result**: UNKNOWN — early tests showed full-screen content but this was misinterpreted as the normal overworld. User clarified no portal rendering was ever visible.
**Status**: Rebuilt with proper bindTexture, needs retesting.

### Attempt 2: Raw GL texture + geometry composite
```java
glBindTexture(GL_TEXTURE_2D, fboTexId) // RAW GL
// portal quad with position_tex shader via PortalRenderTypes.portalFboComposite()
PortalRenderTypes.portalFboComposite().draw(mesh)
```
**Expected**: portal quad shows FBO texture, geometry clips to portal shape
**Result**: INVISIBLE — raw GL texture binding does NOT affect MC render pass samplers
**Root cause**: MC 26.1.2 pipelines bind textures via renderPass.bindTexture(), ignoring raw GL state
**Key learning**: NEVER use raw GL for texture binding in MC 26.1.2 pipeline rendering

### Attempt 3: createRenderPass + bindTexture (CURRENT CODE)
Same as Attempt 1 but with confirmed correct texture binding.
**Status**: Built, not yet tested.

### IP's Approach (for reference)
IP uses `MyRenderHelper.drawPortalAreaWithFramebuffer()`:
- Custom `DrawFbInAreaShader` with portal geometry mesh
- Shader computes screen-space UV from vertex position
- Draws through portal geometry (not full-screen)
- Depth clamp enabled during composite
- No stencil for composite — geometry acts as mask

---

## MC 26.1.2 Key Facts

### Texture Binding
- `renderPass.bindTexture(name, textureView, sampler)` — CORRECT way to bind textures
- `GL11.glBindTexture(GL_TEXTURE_2D, id)` — does NOT affect render pass samplers
- Pipeline shaders read from named samplers bound via renderPass, not GL state

### FBO Management
- `GlTextureView.getFbo(dsa, depth)` caches FBOs per (colorTexId, depthTexId)
- `createRenderPass()` gets FBO via getFbo() — returns cached if same textures
- `DirectStateAccess.bindFrameBufferTextures()` attaches depth as GL_DEPTH_ATTACHMENT (36096)
- Our GlTextureViewMixin reattaches as GL_DEPTH_STENCIL_ATTACHMENT (33306)
- Our RenderTargetMixin does the same for the game's own FBO

### Stencil
- GLFW stencil bits requested by GlBackendMixin ✓
- All depth textures DEPTH24_STENCIL8 via GlConstMixin ✓
- applyPipelineState() never touches stencil ✓
- GL stencil state persists across render pass creation ✓
- Stencil clipping in composite NOT confirmed working

### JOML
- `Vector4f.mul(Matrix4f)` = v * M (row-vector) — WRONG for OpenGL
- `Matrix4f.transform(Vector4f)` = M * v (column-vector) — CORRECT

### renderLevel() Flow
1. `frame.importExternal("main", minecraft.getMainRenderTarget())`
2. Clear pass: `minecraft.getMainRenderTarget()` in lambda (uses swapped target)
3. Sky pass: `this.targets.main`
4. Main pass: `renderGroup()` → `group.outputTarget()` → `minecraft.getMainRenderTarget()`
5. Clouds, weather, entity outline, debug passes
6. `frame.execute()` runs all passes
7. `this.levelRenderState.reset()` clears entity lists

---

## Remaining Deviations

| # | Deviation | Priority | Fix |
|---|-----------|----------|-----|
| 1 | Portal lifecycle (blocks vs entities) | Low | Future |
| 2 | Detection timing (scan vs instant) | Low | Future |
| 3 | Server sync (custom vs vanilla) | Low | Future |
| 4 | Chunk loading (custom vs vanilla) | Low | Future |
| 5 | Chunk direction (player dim vs all) | Low | Future |
| 8 | Section compilation (sync vs async) | Medium | Performance only |
| 10 | Lightmap (hardcoded vs real) | Medium | Commit 5 |
| 12 | Teleportation (PortalLink vs entity) | Low | Future |
| 14 | View center tracking | Low | Future |
| BLOCKER | Composite clipping | **CRITICAL** | Next |
