# Camera Transformation Reference
# LIVING DOCUMENT

## How Immersive Portals Transforms the Camera

### Source: Portal.java

```java
// The core transformation:
public Vec3 transformPoint(Vec3 pos) {
    Vec3 localPos = pos.subtract(getOriginPos());  // Relative to source portal
    return transformLocalVec(localPos).add(getDestPos()); // Map to destination
}

// For non-rotated portals (standard nether portals):
// transformLocalVec returns input unchanged (rotation=null)
// Result: destPos + (pos - srcPos)
```

### Source: PortalRendering.java

```java
public static Vec3 getRenderingCameraPos() {
    Vec3 pos = RenderStates.originalCamera.getPosition();
    for (Portal portal : portalLayers) {
        pos = portal.transformPoint(pos);  // Transform through each portal layer
    }
    return pos;
}
```

### What This Means

For a standard nether portal (no rotation, scale=1):
```
virtualCameraPos = destPortalCenter + (playerCameraPos - srcPortalCenter)
```

If the player is 3 blocks left and 2 blocks back from the overworld portal,
the virtual camera is 3 blocks left and 2 blocks back from the nether portal.

### Our Current Implementation

We DON'T move the virtual camera. Instead we move the BLOCKS:
```java
offset = srcOrigin - destOrigin
renderPos = (netherBlockPos + offset) - playerPos
```

This is mathematically equivalent for colored blocks:
```
renderPos = netherBlockPos + srcOrigin - destOrigin - playerPos
         = netherBlockPos - (destOrigin + (playerPos - srcOrigin))
         = netherBlockPos - virtualCameraPos
```

So the blocks render at the correct camera-relative positions.

### What Needs to Change for Phase 2

For context-switch rendering (re-rendering the world with vanilla's renderer):
1. Must actually SET the camera to virtualCameraPos
2. Must call LevelRenderer.renderLevel() with the new camera
3. Must transform the view/projection matrices
4. The stencil mask still applies - only portal pixels get drawn

### Camera Rotation

IP transforms camera rotation too (for rotated/scaled portals):
```java
public DQuaternion getAdditionalCameraTransformation() {
    return rotation != null ? rotation : DQuaternion.identity;
}
```

For standard nether portals, rotation=null → identity → no rotation change.
The player's view direction stays the same through the portal.

## NetherPortalBlock.AXIS Meaning (CRITICAL - was wrong in our code)

- `axis=X`: width extends along X, portal face perpendicular to Z
  - Player looks through portal along Z axis
  - Depth (behind portal) goes along Z
  - Width of opening is along X
  
- `axis=Z`: width extends along Z, portal face perpendicular to X
  - Player looks through portal along X axis
  - Depth (behind portal) goes along X
  - Width of opening is along Z

This was SWAPPED in our rendering code and caused the stencil quad to be 
on the thin edge instead of the flat face. Fixed 2026-04-11.
