# Stencil Operations Reference
# LIVING DOCUMENT

## CRITICAL FINDING: Why Our Stencil Doesn't Work

### The Root Cause (discovered by reading IP source code)

IP draws the portal shape using **raw shader + direct vertex submission**:
```java
// IP's approach (ViewAreaRenderer.java):
RenderSystem.setShader(() -> shader);
shader.MODEL_VIEW_MATRIX.set(modelViewMatrix);
shader.PROJECTION_MATRIX.set(projectionMatrix);
shader.apply();
// Build mesh...
BufferUploader.draw(bufferBuilder.build());
shader.clear();
```

This draws **DIRECTLY on the currently bound FBO** without creating a RenderPass.
The GL state (stencil func, stencil op, color mask, depth mask) set before the draw
remains active during the draw because nothing overrides it.

### Our approach uses `RenderType.draw(mesh)` which:
1. Creates a new `RenderPass` via `createRenderPass()`
2. `createRenderPass()` binds an FBO via `glBindFramebuffer()`
3. Calls `applyPipelineState()` which **OVERRIDES**:
   - `GlStateManager._depthMask(writeDepth)` → overrides our glDepthMask
   - `GlStateManager._colorMask(writeMask)` → overrides our glColorMask
   - `GlStateManager._depthFunc(depthTest)` → overrides our glDepthFunc
4. Draws the mesh
5. Closes the RenderPass → `finishRenderPass()` → `glBindFramebuffer(0)` → FBO unbound

**Result:** Our raw GL stencil state IS set correctly, but the pipeline's
`applyPipelineState()` overrides our depth/color mask settings. And after the
draw, the FBO is unbound so subsequent stencil operations happen on FBO 0.

### IP's approach vs ours:
| Aspect | IP (works) | Ours (broken) |
|--------|-----------|--------------|
| Draw method | shader.apply() + BufferUploader.draw() | RenderType.draw(mesh) |
| FBO management | Already bound, stays bound | Creates RenderPass, binds, unbinds |
| GL state | Raw GL calls persist | Pipeline overrides depth/color mask |
| Stencil | Raw GL persists (pipeline doesn't touch) | Persists but depth/color overridden |

### The Fix

In MC 26.1.2, `BufferUploader` doesn't exist. But we can achieve the same
direct-draw behavior by:

1. **Option A:** Manually bind the game FBO + set shader + draw vertices
   without going through RenderType.draw() or RenderPass
2. **Option B:** Find MC 26.1.2's equivalent of BufferUploader.draw()
3. **Option C:** Create a custom RenderPass but inject our stencil state
   AFTER applyPipelineState() runs

## IP's Complete Stencil Algorithm (from RendererUsingStencil.java)

### prepareRendering() - Called once at frame start:
```java
client.getMainRenderTarget().bindWrite(false);  // Bind the game FBO
GL11.glClearStencil(0);
GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);       // Clear stencil on game FBO
GlStateManager._enableDepthTest();
GL11.glEnable(GL_STENCIL_TEST);                  // Enable stencil for entire frame
```

### renderPortalViewAreaToStencil() - Write stencil for one portal:
```java
GL11.glStencilFunc(GL_EQUAL, outerPortalStencilValue, 0xFF);
GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);    // INCREMENT on pass
GL11.glStencilMask(0xFF);
ViewAreaRenderer.renderPortalArea(...);           // Direct draw, no RenderPass
```

### clearDepthOfThePortalViewArea() - Clear depth inside portal:
```java
setStencilStateForWorldRendering();  // GL_EQUAL to current layer, GL_KEEP
GL11.glColorMask(false, false, false, false);
GL11.glDepthFunc(GL_ALWAYS);
GL11.glDepthRange(1, 1);            // Write maximum depth
MyRenderHelper.renderScreenTriangle(); // Direct draw, fills portal pixels
GL11.glColorMask(true, true, true, true);
GL11.glDepthFunc(originalDepthFunc);
GL11.glDepthRange(0, 1);
```

### setStencilLimitation() - Set stencil for world rendering:
```java
GL11.glStencilFunc(GL_EQUAL, stencilValue, 0xFF);
GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);    // Don't modify stencil
```

### clampStencilValue() - Reset stencil after portal:
```java
GL11.glStencilFunc(GL_LESS, maximumValue, 0xFF);
GL11.glStencilOp(GL_KEEP, GL_REPLACE, GL_REPLACE);
GL11.glDepthMask(false);
GL11.glColorMask(false, false, false, false);
GlStateManager._disableDepthTest();
MyRenderHelper.renderScreenTriangle();            // Direct draw
```

### myFinishRendering() - End of all portal rendering:
```java
GL11.glStencilFunc(GL_ALWAYS, 2333, 0xFF);
GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
GL11.glDisable(GL_STENCIL_TEST);
```

## Key Observations

1. IP clears stencil ONCE at frame start with `bindWrite(false)` which binds the game FBO
2. IP enables stencil ONCE and keeps it enabled for the entire portal rendering phase
3. IP uses `GlStateManager` for depth/blend/cull (cached state) but raw GL11 for stencil
4. ALL portal shape drawing uses direct shader+draw, NOT RenderType
5. IP sets colorMask/depthMask via raw GL11 calls because it draws directly (no pipeline override)
6. The `GL_INCR` stencil op handles multiple portals - each portal increments the layer
7. The `clampStencilValue()` resets stencil after each portal using `GL_REPLACE`

## MC 26.1.2 Equivalent API Search Needed

Need to find:
- How to draw vertices directly without RenderPass in MC 26.1.2
- MC 26.1.2 equivalent of `BufferUploader.draw()`
- MC 26.1.2 equivalent of `shader.apply()` + `shader.clear()`
- Whether `RenderSystem.setShader()` still exists
- Whether `Tesselator` / `BufferBuilder` can draw without RenderType

## CRITICAL FINDING: MC 26.1.2 Has NO Direct Draw API

### What IP uses (older MC):
- `RenderSystem.setShader()` → sets active shader program
- `shader.apply()` → applies shader uniforms to GL
- `BufferUploader.draw()` → draws vertices directly on current FBO
- `GameRenderer.getPositionColorShader()` → gets shader instance

### What MC 26.1.2 has:
- `RenderSystem.setShader()` → **DOES NOT EXIST**
- `ShaderInstance` → **DOES NOT EXIST** (replaced by RenderPipeline)
- `BufferUploader` → **DOES NOT EXIST**
- `shader.apply()` → **DOES NOT EXIST**

### MC 26.1.2's ONLY way to draw:
- `RenderType.draw(MeshData)` → creates RenderPass, binds FBO, draws, unbinds FBO
- There is NO API to draw vertices without creating a RenderPass

### Consequences:
1. We CANNOT replicate IP's direct-draw approach in MC 26.1.2
2. Every draw MUST go through RenderType.draw() which creates a RenderPass
3. Every RenderPass binds an FBO and then unbinds to FBO 0 when closed
4. The pipeline's applyPipelineState() overrides depth/color mask on every draw

### What This Means for Our Approach:
The stencil GL state DOES persist across RenderPasses (confirmed by debug).
The issue is that:
1. glColorMask and glDepthMask set via raw GL11 calls get OVERRIDDEN
   by the pipeline's applyPipelineState() during every draw
2. This means we CANNOT use raw GL to disable color/depth writing 
   during the stencil write step

### Possible Solutions:
1. Create a custom RenderPipeline that has colorMask=0 and depthMask=false
   for the stencil-only draw step
2. Mixin into applyPipelineState() to prevent it from overriding our state
3. Accept that color+depth ARE written during stencil step (portal shape
   draws visually but that's OK since portal texture is suppressed)

### ACTUALLY - Wait. Re-reading the IP code:
IP's stencil write step (renderPortalViewAreaToStencil) draws the portal
shape with BOTH color and depth enabled:
```java
ViewAreaRenderer.renderPortalArea(
    portal, Vec3.ZERO,
    modelView, projectionMatrix,
    true, true,    // doFaceCulling=true, doModifyColor=true
    true, true     // doModifyDepth=true, doClip=true
);
```
doModifyColor=true and doModifyDepth=true means color and depth ARE written!

The stencil write doesn't NEED color/depth disabled. The portal shape
draws with the fog color (which makes it visually blend with background)
AND writes depth AND writes stencil. All three happen simultaneously.

SO: Our RenderType.draw() approach is actually FINE for the stencil write
step. The pipeline sets color=true, depth=true, which is what IP does too.
The stencil state (GL_ALWAYS, GL_INCR) persists because the pipeline
doesn't touch stencil.

The real question is: why aren't the destination blocks being clipped
by the stencil test?

## CRITICAL FINDING: Stencil Test Is Completely Non-Functional

### Test Result
Set `GL11.glStencilFunc(GL11.GL_NEVER, 0, 0xFF)` before drawing.
GL_NEVER should reject 100% of pixels. But the green quad STILL renders.

### This means:
The raw GL stencil state we set via GL11.glStencilFunc() is being
COMPLETELY IGNORED during RenderType.draw().

### Possible causes:
1. glBindFramebuffer() resets stencil state (NOT per OpenGL spec - stencil
   is context state, not per-FBO state)
2. Something in createRenderPass() or trySetup() disables stencil test
3. The FBO might not actually have a stencil attachment, so the GL driver
   silently ignores stencil operations
4. GlStateManager caching - GlStateManager may have cached stencil as
   disabled, and when the pipeline runs, it doesn't know stencil was
   enabled, so a subsequent GlStateManager call (like _enableDepthTest)
   might somehow reset it

### WAIT - KEY INSIGHT about GlStateManager caching:
GlStateManager caches GL state. When we call GL11.glEnable(GL_STENCIL_TEST)
DIRECTLY, GlStateManager doesn't know about it. Its internal cache still
thinks stencil is disabled.

But GlStateManager doesn't HAVE stencil methods, so it can't interfere...
unless one of its OTHER methods has a side effect on stencil.

### Need to investigate:
- Does GlStateManager._enableDepthTest() affect stencil?
- Does _glBindFramebuffer affect stencil state?
- Does the GL driver reset stencil on FBO bind?

## VICTORY: Stencil Masking Confirmed Working (2026-04-11 05:40)

### What Fixed It
Three bugs were found and fixed:

1. **Wrong mixin target:** Mixin was on `GlTexture.createFbo()` but FBOs used for
   rendering are created by `GlTextureView.createFbo()`. Fixed by targeting GlTextureView.

2. **FBO mismatch:** Stencil clear happened on wrong FBO. Fixed by discovering the
   actual render FBO via `StencilState.lastBoundFbo` (captured by GlStateManagerMixin).

3. **Fragment discard killed stencil write:** Portal shape used alpha=0 color.
   The `position_color.fsh` shader has `if (color.a == 0.0) discard;` which
   prevents stencil operations from executing. Fixed by using alpha=1.

### Current State
- Stencil masking: WORKING (blocks confined to portal shape)
- Destination blocks: VISIBLE inside portal
- Portal shape: draws as thin strips (1-block wide per portal plane)
- Remaining: merge portal planes, fix double pane in nether

## AXIS ORIENTATION FIX (2026-04-11 06:20)

### The Bug
NetherPortalBlock.AXIS specifies the WIDTH direction, NOT the facing direction:
- axis=X: width along X, face perpendicular to Z → stencil quad in XY plane
- axis=Z: width along Z, face perpendicular to X → stencil quad in ZY plane

ALL our rendering code had these SWAPPED. The stencil quad was on the thin
edge of the portal instead of the flat face.

### Files Fixed
- PortalShapeRenderer.java: buildPortalQuadMesh() and drawMergedPortalShape()
- PortalContextSwitch.java: depth direction calculation and block iteration

### Result
Portal view now shows nether terrain (lava, netherrack) through the portal
opening, properly clipped by stencil mask to the obsidian frame.

## OVERWORLD BLEED-THROUGH FIX (2026-04-11 07:30)

### Problem
Overworld water and clouds were bleeding through the portal view because:
1. Water/translucent terrain renders AFTER our hook (AFTER_SOLID_FEATURES)
2. Clouds render AFTER translucent terrain

### Fix (Two Parts)
1. **Hook moved to AFTER_TRANSLUCENT_TERRAIN** - portal content renders
   ON TOP of translucent terrain (water, ice, stained glass)
2. **Depth shield** - After drawing portal content, write depth=0.0 (near plane)
   inside the stencil mask via `glDepthRange(0,0)` + portalDepthClear.
   Clouds/weather render later but fail depth test against 0.0.

### Files Changed
- SeamlessPortalsClientFabric.java: AFTER_SOLID_FEATURES → AFTER_TRANSLUCENT_TERRAIN
- StencilPortalRenderer.java: Added step 4.5 depth shield pass

## OBSIDIAN FRAME BLEED-THROUGH FIX (2026-04-11 07:45)

### Problem
Nether content was visible through the obsidian frame from angled views.
Root cause: portalStencilOnly had NO depth test, so the stencil mask was
written even where obsidian was closer to the camera than the portal quad.

### How IP Solves This
IP draws the portal shape WITH depth test enabled during stencil write.
`glStencilOp(KEEP, KEEP, REPLACE)`:
- dpfail=KEEP: where obsidian occludes the quad, depth fails → stencil stays 0
- dppass=REPLACE: where portal opening is visible, depth passes → stencil=1

### Fix
1. Created new render type `portalStencilWithDepth`:
   - ColorTargetState.WRITE_NONE (no color)
   - DepthStencilState(LESS_THAN_OR_EQUAL, false) - depth TEST yes, depth WRITE no
2. Added `drawMergedPortalShapeWithDepthTest()` using this render type
3. Step 2 (stencil write) uses depth-tested variant
4. Step 5 (stencil reset) keeps original portalStencilOnly (no depth) because
   the depth shield wrote depth=0.0, and the portal quad would fail LEQUAL
   against 0.0, preventing stencil reset

### Files Changed
- PortalRenderTypes.java: Added PORTAL_STENCIL_WITH_DEPTH pipeline + accessor
- PortalShapeRenderer.java: Added drawMergedPortalShapeWithDepthTest()
- StencilPortalRenderer.java: Step 2 uses drawMergedPortalShapeWithDepthTest()

### Result
Nether content is perfectly contained within the portal opening.
Obsidian frame is completely solid from all angles.
