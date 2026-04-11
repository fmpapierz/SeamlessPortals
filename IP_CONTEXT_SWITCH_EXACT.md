# IP's Exact Context-Switch Flow
# From MyGameRenderer.switchAndRenderTheWorld() - LINE BY LINE

## SAVE STATE (before switch):
```java
ClientLevel oldWorld = client.level;
LevelRenderer oldWorldRenderer = client.levelRenderer;
LightTexture oldLightmap = client.gameRenderer.lightTexture();
boolean oldNoClip = client.player.noPhysics;
boolean oldDoRenderHand = ieGameRenderer.ip_getDoRenderHand();
ObjectArrayList oldChunkInfoList = oldWorldRenderer.portal_getChunkInfoList();
HitResult oldCrosshairTarget = client.hitResult;
Camera oldCamera = client.gameRenderer.getMainCamera();
PostChain oldTransparencyShader = worldRenderer.portal_getTransparencyShader();
RenderBuffers oldRenderBuffers = worldRenderer.ip_getRenderBuffers();
RenderBuffers oldClientRenderBuffers = client.renderBuffers();
SectionBufferBuilderPack oldFixedBuffers = sectionRenderDispatcher.ip_getFixedBuffers();
Frustum oldFrustum = worldRenderer.portal_getFrustum();
Matrix4f oldProjectionMatrix = RenderSystem.getProjectionMatrix();
Matrix4fStack oldModelViewStack = RenderSystem.ip_getModelViewStack();
```

## SWITCH TO DESTINATION:
```java
client.levelRenderer = worldRenderer;        // via mixin
client.level = newWorld;
client.gameRenderer.setLightmap(helper.lightmapTexture);
client.blockEntityRenderDispatcher.level = newWorld;
client.player.noPhysics = true;
client.gameRenderer.setRenderHand(doRenderHand);
FogRendererContext.swappingManager.pushSwapping(newDimension);
client.particleEngine.setWorld(newWorld);     // via mixin
client.hitResult = remoteHitResult or null;
client.gameRenderer.setCamera(newCamera);     // via mixin

// CRITICAL: Create fresh model-view stack
RenderSystem.setModelViewStack(new Matrix4fStack(16));
RenderSystem.applyModelViewMatrix();

// Optionally use secondary RenderBuffers
if (useSecondary) {
    newRenderBuffers = acquireFromPool();
    worldRenderer.setRenderBuffers(newRenderBuffers);
    client.setRenderBuffers(newRenderBuffers);
} else {
    // Flush existing buffers to avoid conflicts
    client.renderBuffers().bufferSource().endBatch();
}

// Disable transparency shader for portal content
worldRenderer.setTransparencyShader(null);
```

## RENDER:
```java
client.gameRenderer.renderLevel(client.getTimer());
```

That's it. Just calls GameRenderer.renderLevel() which calls
LevelRenderer.renderLevel() which builds framegraph and executes.

## RESTORE STATE:
```java
client.levelRenderer = oldWorldRenderer;
client.level = oldWorld;
client.gameRenderer.setLightmap(oldLightmap);
client.blockEntityRenderDispatcher.level = oldWorld;
client.player.noPhysics = oldNoClip;
client.gameRenderer.setRenderHand(oldDoRenderHand);
client.particleEngine.setWorld(oldWorld);
client.hitResult = oldCrosshairTarget;
client.gameRenderer.setCamera(oldCamera);
worldRenderer.setTransparencyShader(oldTransparencyShader);
FogRendererContext.swappingManager.popSwapping();
oldWorldRenderer.setChunkInfoList(oldChunkInfoList);
worldRenderer.setRenderBuffers(oldRenderBuffers);
client.setRenderBuffers(oldClientRenderBuffers);
sectionRenderDispatcher.setFixedBuffers(oldFixedBuffers);
worldRenderer.setFrustum(oldFrustum);
client.gameRenderer.resetProjectionMatrix(oldProjectionMatrix);
RenderSystem.setModelViewStack(oldModelViewStack);
RenderSystem.applyModelViewMatrix();
```

## KEY INSIGHTS:

1. IP creates a FRESH Camera object for the dest world (not reusing main camera)
2. IP creates a FRESH Matrix4fStack(16) for the nested render
3. IP saves the ENTIRE modelViewStack object reference, not just push/pop
4. IP optionally uses SECONDARY RenderBuffers from a pool
5. If not using secondary buffers, IP calls endBatch() to flush
6. IP sets player.noPhysics=true during portal render
7. IP swaps the lightmap texture per dimension
8. IP nulls the transparency shader during portal render
9. IP saves/restores the frustum per world renderer
10. IP calls GameRenderer.renderLevel() (not LevelRenderer.renderLevel() directly)

## WHAT WE CAN SIMPLIFY FOR MC 26.1.2:

IP does a LOT of state saving because it's deeply integrated. For our
initial Phase 2, we can simplify:

MINIMUM VIABLE:
- Save/restore: client.level, client.levelRenderer
- Create fresh Matrix4fStack
- Save/restore projection matrix
- Flush bufferSource.endBatch()
- Call destRenderer.renderLevel() directly (not through GameRenderer)

SKIP FOR NOW:
- Lightmap swapping (use same lightmap)
- Fog context swapping
- Frustum per renderer (use simple frustum)
- Camera swapping on GameRenderer
- Transparency shader handling
- HitResult swapping
- Particle engine world swap
