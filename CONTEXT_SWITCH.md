# Context Switch Reference
# LIVING DOCUMENT

## How Immersive Portals Does Context Switching

Source: `/tmp/ip_source/src/main/java/qouteall/imm_ptl/core/render/`

### Key Classes:
- `RendererUsingStencil.java` - The stencil renderer (read in full)
- `PortalRenderer.java` - Base class for portal renderers
- `ViewAreaRenderer.java` - Draws the portal shape quad
- `MyRenderHelper.java` - Utility for screen triangles and GL helpers
- `context_management/WorldRenderInfo.java` - Stores render context
- `context_management/PortalRendering.java` - Tracks portal layer stack
- `context_management/FogRendererContext.java` - Fog state per dimension

### IP's Context Switch Flow:
```
1. Save current WorldRenderInfo (dimension, camera, fog, etc.)
2. Push portal layer onto PortalRendering stack
3. Switch Minecraft.level to destination ClientLevel
4. Transform camera position to destination
5. Re-render the world (calls LevelRenderer again recursively)
6. Pop portal layer
7. Restore WorldRenderInfo
```

### What Gets Saved/Restored:
- `Minecraft.level` (ClientLevel)
- Camera position + rotation
- Fog color + parameters
- Projection matrix
- View matrix
- Portal layer counter (stencil depth)
- Entity render state

### Key Difference from MC 26.1.2:
IP was written for older MC where `LevelRenderer.renderChunkLayer()` could
be called independently. In MC 26.1.2, rendering goes through the framegraph
which is much harder to call recursively.

## Current Status
We have NOT implemented context switching yet. Phase 1 uses colored blocks
from RemoteChunkManager. Phase 2 (future) will need full context switching.

## What We Need to Investigate for MC 26.1.2:
1. Can we call `LevelRenderer.renderLevel()` recursively in 26.1.2?
2. How to switch `Minecraft.level` mid-frame without breaking the framegraph?
3. How to handle the ViewArea / SectionRenderDispatcher per dimension?
4. How to manage the model-view stack across recursive renders?
