# Inventory: `q_misc_util` library slice

Source ground truth: `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/q_misc_util/**`
(IP 6.0.6, MC 1.21.3 per `gradle.properties` `minecraft_version=1.21.3`; note the checkout's
`fabric.mod.json` "depends.minecraft" still says `["1.21","1.21.1"]` — stale metadata, gradle.properties wins).

All citations are `file:line` relative to `src/main/java/qouteall/q_misc_util/` unless prefixed otherwise.
Files owned by the **network slice** (`MiscNetworking.java`, `ImplRemoteProcedureCall.java`,
`api/McRemoteProcedureCall.java`) are cross-referenced only.

---

## 1. Overview

`q_misc_util` is qouteall's general-purpose utility layer that everything in `imm_ptl` sits on. It has
three tiers: (a) **pure math/geometry value types** (`my_util/`: `Plane`, `DQuaternion`, `AARotation`,
`IntMatrix3`, `IntBox`, `Mesh2D`, `GeometryUtil`, `Sphere`, `Circle`, `Range`, small records) that the
portal transform, teleportation, collision, and portal-shape code are built from; (b) **infrastructure
utilities** (`Helper` grab-bag, `MyTaskList` deferred-task engine, `Signal*` event objects, log limiters,
`Animated` client-side interpolation); and (c) a small **runtime glue layer** (dimension⇄int-id mapping in
`dimension/`, a server weak-ref, a text overlay, five mixins, and the RPC/networking entry points owned by
the network slice). Import census across `imm_ptl` (`grep -c` of import lines): `Helper` 86,
`DQuaternion` 27, `IntBox` 21, `Plane` 20, `MyTaskList` 14, `MiscHelper` 13, `LimitedLogger` 11,
`McRemoteProcedureCall` 10, `RayTraceResult` 9, `Mesh2D` 7 — this slice is the single most-depended-on
package in IP.

**Important architectural fact:** in this version `q_misc_util` is *not* a standalone library — it has
reverse dependencies **into `imm_ptl.core`**: `Helper` imports `qouteall.imm_ptl.core.McHelper`
(Helper.java:29, used at Helper.java:509), `DimensionIntId` imports `IPCGlobal`, `IPPerServerInfo`, and
`McHelper` (dimension/DimensionIntId.java:17-19), and `MiscNetworking` imports `ClientWorldLoader`
(MiscNetworking.java:28; line 29 is the `McHelper` import — `ClientWorldLoader.dimIdToDimTypeId` is
written at MiscNetworking.java:121). It also depends on the **external DimLib mod**
(`qouteall.dimlib.api.DimensionAPI`, dimension/DimensionIntId.java:16) — dynamic dimension *management*
does NOT live in this package (see §3.6). The mixin config is `src/main/resources/q_misc_util.mixins.json`;
Fabric entry points are `MiscUtilModEntry` / `MiscUtilModEntryClient` registered in `fabric.mod.json`.

---

## 2. Class-by-class inventory

### 2.1 Geometry / math core (`my_util/`) — FULL depth

#### `Plane` — my_util/Plane.java (146 LOC, common, pure math)
Immutable plane as `record Plane(Vec3 pos, Vec3 normal)` (Plane.java:7). The canonical constructor
**normalizes the normal** (Plane.java:8-11). This is the type used for portal clipping planes, crossing
detection, and wand constraints (consumers incl. `imm_ptl/core/portal/Portal.java`,
`teleportation/ClientTeleportationManager.java`, `render/FrontClipping.java`, `render/FrustumCuller.java`).
Key API:
- `double getDistanceTo(Vec3 point)` = `normal · (point − pos)` — **signed** distance (Plane.java:13-15);
  scalar overload avoiding allocation (Plane.java:17-19).
- `Vec3 getProjection(Vec3)` (Plane.java:22-24), `Vec3 getReflection(Vec3)` (Plane.java:26-28, mirrors).
- `boolean isPointOnPositiveSide(Vec3)` = distance > 0 (Plane.java:30-32).
- `Plane move(double distance)` translates along normal (Plane.java:34-36); `getOpposite()` flips normal
  (Plane.java:38-40); `getParallelPlane(Vec3 pos)` (Plane.java:123-125).
- `@Nullable Vec3 rayTrace(Vec3 origin, Vec3 vec)` — returns null when parallel or t<0 (Plane.java:42-54).
- `double rayTraceGetT(Vec3 lineOrigin, Vec3 lineVec)` — solves `lineOrigin + t*lineVec` vs plane;
  returns `NaN` when `|normal·lineVec| < 0.00001` (Plane.java:62-68); allocation-free scalar overload
  (Plane.java:73-83).
- `@Nullable Vec3 intersectionWithLineSegment(Vec3 p1, Vec3 p2)` — clamped to t∈[0,1] (Plane.java:85-98).
- `static Plane interpolate(Plane a, Plane b, double progress)` — lerps pos and normal, re-normalizes
  (Plane.java:117-121).
- Plane-equation accessors `getEquationX/Y/Z/W()` where `W = −normal·pos`, i.e. the form
  `ax+by+cz+w > 0` for the positive side (Plane.java:131-145) — used to feed clip-plane uniforms.
MC touch: `net.minecraft.world.phys.Vec3` only.

#### `DQuaternion` — my_util/DQuaternion.java (545 LOC, common, pure math)
Immutable **double-precision** quaternion — THE portal-rotation type; explicitly kept double because float
error accumulation breaks teleportation/collision (doc comment DQuaternion.java:461-464). Fields
`x,y,z,w` public final (DQuaternion.java:27-30); `identity = (0,0,0,1)` (DQuaternion.java:33).
Key API:
- Conversions: `fromMcQuaternion(Quaternionf)` / `(Quaterniond)` (DQuaternion.java:45-55),
  `Quaternionf toMcQuaternion()` (DQuaternion.java:75-79), `Matrix4f toMatrix()` = `new Matrix4f().set(toMcQuaternion())`
  (DQuaternion.java:81-83). These are the JOML boundary points.
- Construction: `rotationByDegrees(Vec3 axis, double degrees)` (DQuaternion.java:92-99);
  `rotationByRadians(Vec3 axis, double radians)` — normalizes axis, `(axis*sin(θ/2), cos(θ/2))`
  (DQuaternion.java:108-120).
- `Vec3 rotate(Vec3)` via `q · (v,0) · q*` (DQuaternion.java:125-130).
- `hamiltonProduct(DQuaternion other)` — "firstly do *other* rotation, then *this*" (doc + formula,
  DQuaternion.java:148-167); `combine(other)` = `other.hamiltonProduct(this)` i.e. "this first, then other"
  (DQuaternion.java:169-172). **The order conventions here are load-bearing for every portal transform.**
- `getConjugated()` = inverse for unit quats (DQuaternion.java:177-181); vector ops `multiply(double)`,
  `add`, `dotProduct` (DQuaternion.java:186-209); `getNormalized()` — logs error + returns identity for
  zero length (DQuaternion.java:214-225).
- Camera math: `getCameraRotation(pitch, yaw)` = `rotX(pitch) ⊗H rotY(yaw+180)` — "rotation applied to
  world for world rendering; its inverse is the rotation applied to entity head"
  (DQuaternion.java:234-239); closed-form twin `getCameraRotation1` (DQuaternion.java:242-251);
  inverse `getPitchYawFromRotation(DQuaternion) → Tuple<Double pitch, Double yaw>` in degrees via atan2
  (DQuaternion.java:343-359).
- Comparison: `distanceSq(a,b)` takes `min(|a−b|², |a+b|²)` — handles the q/−q double cover
  (DQuaternion.java:264-278); `isClose(a,b)` default tolerance 1e-8 (DQuaternion.java:256-262).
- `interpolate(a,b,t)` — slerp with dot<0 sign flip and nlerp fallback when dot>0.9995
  (DQuaternion.java:307-337).
- `matrixToQuaternion(Vec3 x, Vec3 y, Vec3 z)` — rows-of-rotation-matrix → quat, classic
  branch-on-largest-diagonal algorithm (DQuaternion.java:364-414); `fromFacingVecs(axisW, axisH)` =
  `matrixToQuaternion(axisW, axisH, axisW×axisH)` (DQuaternion.java:416-422) — this is how a portal's
  orientation quaternion is derived from its W/H axes; `getRotationBetween(from,to)` (undefined for
  collinear inputs, doc at DQuaternion.java:424-434).
- NBT: `Tag toTag()` / `fromTag(Tag)` — CompoundTag with doubles x,y,z,w; fromTag falls back to identity
  on wrong type or missing "x" (DQuaternion.java:436-458).
- `fixFloatingPointErrorAccumulation()` — snaps each component within 1e-7 of {0,1,−1} then normalizes;
  documented as preventing teleport/collision malfunction from accumulated error
  (DQuaternion.java:465-491). `isAxisAligned()` = all components exactly 0/±1 (DQuaternion.java:493-502).
- Euler: `fromEulerAngle(Vec3 pitchYawRoll_degrees)` = JOML `rotateZ(z)·rotateY(−y)·rotateX(x)`
  (DQuaternion.java:507-514); `toEulerAngle()` via `getEulerAnglesZYX`, negating y back
  (DQuaternion.java:516-520). **Yaw sign is negated at both boundaries** — a known sign-error trap.
- Basis extraction: `getAxisW()`/`getAxisH()`/`getNormal()` = rotate unit X/Y/Z (DQuaternion.java:530-540);
  `isValid()` = `|q·q| > 0.9` (DQuaternion.java:542-544); `fromNullable` → identity (DQuaternion.java:522-528).
MC touch: `Vec3`, `Tuple`, `CompoundTag`/`Tag`; JOML `Quaternionf`, `Quaterniond`, `Matrix4f`, `Vector3f`;
log4j Logger (DQuaternion.java:25).

#### `AARotation` — my_util/AARotation.java (214 LOC, common)
Enum of the **24 axis-aligned rotations** (rotation subgroup of vanilla's `OctahedralGroup`, which also
contains mirrors — doc AARotation.java:17-20). Each constant is defined by (transformedZ, transformedX)
direction pair (AARotation.java:23-51); constructor derives `transformedY` by direction cross product,
builds an `IntMatrix3` from the three transformed unit vectors and caches `quaternion = matrix.toQuaternion()`
(AARotation.java:62-72). `IDENTITY = SOUTH_ROT0` (AARotation.java:53).
Key API: `BlockPos transform(Vec3i)` (AARotation.java:74-76); `Direction transformDirection(Direction)`
(AARotation.java:78-85); static `dirCrossProduct(Direction,Direction)` (validates different axes,
AARotation.java:88-97); `rotateDir90DegreesAlong(dir, axis)` (AARotation.java:99-104);
`multiply(AARotation other)` = "firstly apply other, then this", precomputed 24×24 table
(AARotation.java:106-119); `getInverse()` brute-force precomputed (AARotation.java:156-171);
lookup constructors `getAARotationFromZX/YZ/XY` (AARotation.java:129-154);
`get90DegreesRotationAlong(Direction)` (AARotation.java:173-181);
`toVanillaRotation()` → `net.minecraft.world.level.block.Rotation`, **null** for the 20 non-horizontal
members (AARotation.java:183-192); `fromVanillaRotation` (AARotation.java:194-201);
`rotationsSortedByAngle` ImmutableList sorted by quaternion angle (AARotation.java:203-212).
MC touch: `Direction` (getNormal/fromDelta/getStep*/getAxis), `BlockPos`, `Vec3i`, `Rotation`,
`OctahedralGroup` (javadoc-only here).

#### `IntMatrix3` — my_util/IntMatrix3.java (108 LOC, common)
Integer 3×3 rotation matrix stored as three `Vec3i` **rows**; convention is **row-vector `p * m`** —
"the left one gets applied first… different to the MC transformation [which] uses column vector"
(IntMatrix3.java:13-18). Constructor from `OctahedralGroup` by rotating the three positive unit directions
(IntMatrix3.java:31-38). API: `BlockPos transform(Vec3i p)` (IntMatrix3.java:41-45, built on
`Helper.scale`/`offset`); `multiply(IntMatrix3 m)` — `this` applied first (IntMatrix3.java:48-54);
`transformDirection` (IntMatrix3.java:56-59); `getIdentity()` (IntMatrix3.java:76-82);
`Matrix3f toMatrix()` (JOML, IntMatrix3.java:84-99); `DQuaternion toQuaternion()` via
`DQuaternion.matrixToQuaternion(Vec3.atLowerCornerOf(x/y/z))` (IntMatrix3.java:101-107).
MC touch: `OctahedralGroup`, `Direction.fromAxisAndDirection`, `Vec3.atLowerCornerOf`, JOML `Matrix3f`.

#### `GeometryUtil` — my_util/GeometryUtil.java (384 LOC, common, pure math)
2D computational-geometry kit used by `Mesh2D` (and directly by `PortalCommand`). All double-scalar,
allocation-averse. Key API:
- `getAngle(dx1,dy1,dx2,dy2)` — angle between 2D vectors via acos of normalized dot; 0 for zero vectors
  (GeometryUtil.java:11-24).
- `isOppositeVec(v1,v2)` — normalized dot ≈ −1 within 1e-5; **returns true for zero vectors**
  (GeometryUtil.java:26-37).
- `triangleIntersects(6×double, 6×double)` — SAT over the 6 edge-normal axes of both triangles
  (GeometryUtil.java:42-89); separation test projects all 6 points on an axis and compares min/max
  intervals (GeometryUtil.java:91-111).
- `triangleIntersectsWithAABB(tri, box)` — AABB-extent quick reject then "AABB fully on right side of any
  CCW edge" test; **triangle must be counter-clockwise** (GeometryUtil.java:113-155); the side test picks
  the extreme AABB corner against the inward-facing vector (GeometryUtil.java:157-184).
- `record Line2D(linePX, linePY, dirX, dirY)` (GeometryUtil.java:191-285): `fromTwoPoints`
  (GeometryUtil.java:193-200); side vector = direction rotated 90° CCW (inward for CCW triangles,
  GeometryUtil.java:202-210); `testSide(x,y)` → {−1,0,1} with ±1e-5 dead zone (GeometryUtil.java:212-225);
  `testSideBool` (strict >0, GeometryUtil.java:227-232); `getIntersectionWithLine(other)` returns *this
  line's* t, NaN when |det| < 1e-8 (GeometryUtil.java:252-263); `getDistanceToLineIfWithinProjection`
  (GeometryUtil.java:266-283).
- `selectCoordFromAABB(box, xPos, yPos, zPos)` corner picker (GeometryUtil.java:287-295).
- `getSlicePolygonOfCube(AABB box, Plane plane, Vec3 planeX, Vec3 planeY, double scaleX, double scaleY)
  → ObjectArrayList<Vec2d>` — intersects the plane with the cube's 12 edges (edge table inline,
  GeometryUtil.java:310-340), converts hits to plane-local 2D coords divided by scaleX/scaleY, then sorts
  vertices CCW by atan2 around their centroid (GeometryUtil.java:344-382). This produces the convex
  polygon that portal-shape code subtracts from a `Mesh2D`.
MC touch: `AABB` fields, `Vec3`; fastutil `ObjectArrayList`.

#### `Mesh2D` — my_util/Mesh2D.java (1753 LOC, common; the biggest class in the slice)
Editable triangle mesh over the normalized square [−1,1]², used for **irregular portal shapes**
(`SpecialFlatPortalShape`, `GeometryPortalShape`, `Portal`, `BlockPortalShape`, `PortalManipulation`,
`BreakableMirror` — grep of `imm_ptl`). Full mechanism in §3.2. Storage (Mesh2D.java:50-64):
- `Long2IntOpenHashMap gridToPointIndex` — dedupe map from quantized grid coord to point index;
  grid = `round(coord * 2^30)` clamped, two ints packed into a long **with `& 0xFFFFFFFFL` masking**
  (comment warns against sign-extension, Mesh2D.java:34-47).
- `DoubleArrayList pointCoords` (2 doubles/point) — points keep full double precision, the grid is only
  for merging near points (comment Mesh2D.java:35-36).
- `IntArrayList trianglePointIndexes` (3 ints/triangle, −1 = deleted slot).
- `ObjectArrayList<IntArrayList> pointToTriangles` (reverse index; empty list = unused point).
- `@Nullable QuadTree<IntArrayList> triangleLookup` — maintained only after `enableTriangleLookup()`
  (Mesh2D.java:62-64, 1024-1038).
Key public API (signatures other subsystems call):
`int addTriangle(x1,y1,x2,y2,x3,y3)` (Mesh2D.java:70-80); `int addTriangle(int p0,int p1,int p2)` —
rejects degenerate (|cross| < 1e-9) returning −1, **normalizes winding to CCW**, updates reverse index +
quadtree (Mesh2D.java:85-125); `int indexPoint(x,y)` — validates non-NaN, clamps to [−1,1], dedupes by
grid (Mesh2D.java:127-144); `removeTriangle(int)` (Mesh2D.java:207-221); `isTriangleValid`/`isPointUsed`
(Mesh2D.java:223-235); `getStoredPointNum`/`getStoredTriangleNum` (raw, include dead slots,
Mesh2D.java:240-249); accessors `getTrianglePointIndex`, `getPointX/Y` (Mesh2D.java:251-261);
`subtractTriangleFromMesh(6×double)` — input must be CCW (Mesh2D.java:541-569);
`subtractPolygon(ObjectArrayList<Vec2d>)` — convex, fan-triangulated (Mesh2D.java:1554-1568);
`simplify()` / `int simplifySteps(int countLimit)` (Mesh2D.java:491-530); `int fixTJunction()`
(Mesh2D.java:1315-1370); `fixIntersectedTriangle()` (Mesh2D.java:1253-1262); `compact()`
(Mesh2D.java:946-949); `boxIntersects(minX,minY,maxX,maxY)` — quadtree query + SAT
(Mesh2D.java:1590-1623); `getArea()` (sum of cross/2, Mesh2D.java:1526-1552); `getBarycenter()`
area-weighted (Mesh2D.java:1454-1489); `Rect getBoundingBox()` (Mesh2D.java:1491-1524);
`transformPoints(Function<Vec2d,Vec2d>)` — rebuilds grid index (Mesh2D.java:1570-1588); `copy()`
(Mesh2D.java:1625-1646); `addQuad(x1,y1,x2,y2)` = 2 triangles (Mesh2D.java:1650-1653);
`static createNewFullQuadMesh()` — the full [−1,1]² quad (Mesh2D.java:1655-1659);
NBT `CompoundTag toTag()` (does **not** compact — "Modification may cause data race",
Mesh2D.java:1661-1694) / `@Nullable fromTag(CompoundTag)` (null on empty or malformed lists,
Mesh2D.java:1696-1728); debug `toJson()` (Mesh2D.java:1412-1452), `checkStorageIntegrity()`
(Mesh2D.java:1161-1216), `debugVisualize()` (writes JSON + spawns python, dev-only,
Mesh2D.java:1730-1752).
MC touch: `CompoundTag`, `ListTag`, `DoubleTag`, `IntTag`, `Tag.TAG_DOUBLE/TAG_INT`, `Mth.clamp`,
`net.minecraft.util.Unit` (as traversal sentinel); fastutil; gson (debug only).

#### `QuadTree<T>` — my_util/QuadTree.java (193 LOC, common)
Flat-array quadtree over [−1,1]², `MAX_LEVEL = 8` (QuadTree.java:12). Node n's children live at indices
4n..4n+3 in `children` (−1 = absent); element of node n at `elements[n]` (QuadTree.java:14-20). Elements
created lazily by the `Supplier<T> elementFactory` (QuadTree.java:22-27).
API: `int acquireNodeForBoundingBox(minX,minY,maxX,maxY)` / `T acquireElementForBoundingBox(...)` — descends
while the (node-relative) box fits entirely in one signed quadrant, rescaling coords by
`(v − ±0.5) * 2` per level (QuadTree.java:40-93); `<U> U traverse(bbMinX..bbMaxY, Function<T,U>)` — visits
the element of every node whose quadrant overlaps the box, short-circuits on non-null result
(QuadTree.java:95-156). Only consumer: `Mesh2D.triangleLookup`. No MC types at all.

#### `IntBox` — my_util/IntBox.java (540 LOC, common)
Inclusive integer block box `[l, h]`, both `BlockPos`, auto-normalized min/max in constructor
(IntBox.java:21-27). Used by nether-portal shape matching, chunk math, API (21 imports in imm_ptl).
Key API: `fromBasePointAndSize(BlockPos, BlockPos)` (IntBox.java:29-36); `fromPosAndOffset`
(IntBox.java:39-43); `expandOrShrink(Vec3i)` (IntBox.java:45-50); `getExpanded(Axis,int)` /
`getExpanded(Direction,int)` (IntBox.java:52-76); `Stream<BlockPos> stream()` (boxed, IntBox.java:78-86);
`fastStream()` = `BlockPos.betweenClosedStream(l,h)` — **mutable pos, "store its copy"** (IntBox.java:88-92);
`getSize()` = h+1−l (IntBox.java:94-96); `getSurfaceLayer(Axis,AxisDirection)` / `(Direction)`
(IntBox.java:102-139); `static getIntersect(a,b)` nullable (IntBox.java:141-160); `getCenter()`
(integer-divide, IntBox.java:172-174) vs `getCenterVec()` = (l+h+1)/2.0 exact (IntBox.java:176-182);
`forSixSurfaces(mapper)` — the 6 face layers with overlap-dedup adjustments (IntBox.java:194-226);
`getMoved(Vec3i)` (IntBox.java:228-233); `getContainingBox` (IntBox.java:235-249); `getSubBoxInCenter`
(IntBox.java:251-257); `getEightVertices()` (IntBox.java:259-270); `AABB toRealNumberBox()` = [l, h+1)
(IntBox.java:272-281); `static fromRealNumberBox(AABB)` — floors/ceils with ±1e-5 epsilon to defeat
float error (IntBox.java:283-293); `contains(BlockPos)` / `contains(IntBox)` (IntBox.java:301-312);
`get12Edges()` (IntBox.java:318-371); `getBoxByPosAndSignedSize` — signed size, zero throws
(IntBox.java:381-405); `isOnSurface/isOnEdge/isOnVertex` (IntBox.java:407-429); `confineInnerBox(IntBox)` +
`getOffsetForConfiningIntegerRange` (validates fit, IntBox.java:434-479); `getVertex(bool,bool,bool)`
(IntBox.java:481-487); NBT `toTag()`/`fromTag()` — ints lX..hZ (IntBox.java:489-516).
MC touch: `BlockPos` (offset/subtract/betweenClosedStream), `Vec3i`, `Direction`, `AABB`, `CompoundTag`.

#### Small geometry records (common, trivial LOC)
- `RayTraceResult(double t, Vec3 hitPos, Vec3 surfaceNormal)` — my_util/RayTraceResult.java:5-7. Produced
  by `Helper.raytraceAABB`; consumed by `BoxPortalShape` ray tracing (imm_ptl/core/portal/shape/BoxPortalShape.java:117).
- `Vec2d(double x, double y)` — my_util/Vec2d.java:3. 2D point for Mesh2D.
- `LongBlockPos(long x, long y, long z)` — my_util/LongBlockPos.java:3. Dedup key in
  `Helper.deduplicateWithPrecision` (Helper.java:1206-1225).
- `LineSegment(Vec3 start, Vec3 end)` — my_util/LineSegment.java:5-17. `interpolate(a,b,progress)` is an
  **instance method that ignores `this`** (my_util/LineSegment.java:6-11 — latent oddity, called via a
  value anyway in Animated at animation/Animated.java:449); `isClose(other, v)` compares endpoint
  distances² < v² (my_util/LineSegment.java:13-16).
- `Circle(Plane plane, Vec3 circleCenter, double radius)` — my_util/Circle.java:6-20;
  `projectToCircle(Vec3)` projects onto plane then snaps to radius, null if within 0.001 lengthSqr of
  center (Circle.java:8-19). Used by wand drag constraints (6 imports).
- `Sphere(Vec3 center, double radius)` — my_util/Sphere.java:7-84: `projectToSphere` (null near center,
  Sphere.java:9-22); `getIntersectionWithPlane(Plane) → @Nullable Circle` (Sphere.java:24-40);
  `rayTrace(origin, vec)` — quadratic, returns nearest non-negative-t hit (Sphere.java:42-77);
  `static interpolate` uses `Mth.lerp` (Sphere.java:79-84).
- `Range(double start, double end)` — my_util/Range.java:5-76: `createUnordered` (Range.java:7-9);
  `intersection` → null when empty (`start >= end`, Range.java:11-23); `rangeIntersects` (inclusive
  touch counts, Range.java:33-38); `getPushRangeMovement` — push a range out of a collider toward the
  lesser-overlap side by midpoint comparison (Range.java:41-66); `getConfineRangeMovement`
  (Range.java:69-82). The push/confine functions are the 1D primitives of IP's collision resolution.
- Functional shapes: `BoxPredicate` (6 doubles → bool; `nonePredicate` constant, my_util/BoxPredicate.java:3-8),
  `BoxPredicateF` float twin (my_util/BoxPredicateF.java:3-8; used by portal shapes + `FrustumCuller`),
  `TriIntPredicate` (my_util/TriIntPredicate.java:3-5), `TriangleConsumer` (9 doubles,
  my_util/TriangleConsumer.java:4-10; used by shape → mesh rendering), `Access<T>` get/set pointer
  (my_util/Access.java:6-10).
- `WithDim<T>(ResourceKey<Level> dimension, T value)` — my_util/WithDim.java:6-10. Dimension-tagged value,
  8 imports in imm_ptl (animation, wand).

### 2.2 Infrastructure utilities (`my_util/` + root) — moderate depth

#### `Helper` — Helper.java (1501 LOC, common; 86 importers — the single biggest dependency)
Static grab-bag. Groups, with the members that matter cross-version:
- **Plane/line math**: `getCollidingT(Vec3 planeCenter, Vec3 planeNormal, Vec3 lineOrigin, Vec3 lineDir)`
  (no parallel guard — can return ±Inf/NaN, Helper.java:76-85); scalar overload returns NaN when
  |denom| < 1e-6 (Helper.java:97-115); `isInFrontOfPlane` (Helper.java:201-207); `fallPointOntoPlane`
  (Helper.java:209-216); `getDistanceFromPointToLine` (Helper.java:1227-1239).
- **`raytraceAABB(boolean boxFacingOutwards, 6×box, 3×origin, 3×delta) → @Nullable RayTraceResult`** —
  t∈[0,1] segment-vs-box with face normals; `boxFacingOutwards` flips which faces are tested and rejects
  origins already inside (Helper.java:117-199). Used by `BoxPortalShape` (imm_ptl BoxPortalShape.java:117).
- **Axis/Direction algebra**: `getUnitFromAxis` (Helper.java:218-223); `getCoordinate(Vec3i|Vec3, Axis)`
  via `axis.choose` (Helper.java:225-231); signed variants `getCoordinate(Vec3i, Direction)`
  (Helper.java:233-236), `putCoordinate` (Helper.java:238-252), `putSignedCoordinate`/`getSignedCoordinate`
  (Helper.java:254-277); `getAnotherTwoAxis` (Helper.java:344-354); `getAnotherFourDirections`
  (Helper.java:372-390); `getPerpendicularDirections` (order swaps for negative facing,
  Helper.java:392-401); `getFacingExcludingAxis` (Helper.java:948-955).
- **AABB algebra**: `getBoxSize` (Helper.java:403-405); `getBoxSurface(Inversed)` via `AABB.contract`
  (Helper.java:407-415); `getBoxCoordinate`/`replaceBoxCoordinate` (Helper.java:287-309);
  `getBoxByBottomPosAndSize`/`getBoxBottomCenter` (Helper.java:456-469); `transformBox(AABB, Function<Vec3,Vec3>)`
  — transforms 8 vertices and re-wraps axis-aligned (expands under rotation, Helper.java:997-1011);
  `eightVerticesOf` (Helper.java:613-624); `boundingBoxOfPoints(Vec3[])` (Helper.java:1479-1500);
  `getDistanceToBox`/`getSignedDistanceToBox` (Helper.java:1043-1063) on 1D helpers
  `getDistanceToRange`/`getSignedDistanceToRange` (Helper.java:1013-1041); `boxContains(outer, inner)`
  (Helper.java:1165-1168 — **uses `AABB.contains` which is min-inclusive/max-exclusive**);
  `verticesAndEdgeMidpoints` (26 points, Helper.java:1270-1287); `alignToBoxSurface(box, pos, gridCount)`
  — clamp, snap to 1/gridCount grid, then project to nearest face (Helper.java:1289-1352);
  `traverseBoxEdge(BoxEdgeConsumer)` unit-cube edges (Helper.java:1241-1268).
- **Block-area growth**: `expandArea(IntBox, Predicate<BlockPos>, Direction)` — grows one layer at a time
  while the whole new surface layer passes the predicate, hard cap 41 iterations (Helper.java:761-777);
  `expandRectangle` (4 directions perpendicular to axis, Helper.java:417-430); `expandBoxArea`
  (6 directions, Helper.java:432-444). These are the primitives of nether-portal frame detection.
- **2D**: `crossProduct2D(x1,y1,x2,y2)` — positive = CCW (Helper.java:496-502);
  `getDistanceToRectangle` (Helper.java:471-488); `getChebyshevDistance` (Helper.java:446-454).
- **Dimension ids / NBT**: `dimIdToKey(ResourceLocation|String)` → `ResourceKey.create(Registries.DIMENSION, …)`
  (Helper.java:504-510, the String form goes through `McHelper.newResourceLocation` — an
  **imm_ptl.core dependency**); `putWorldId`/`getWorldId` (string tag; error → OVERWORLD fallback,
  Helper.java:512-526); `putVec3d`/`getVec3d`/`getVec3dOptional` (suffix X/Y/Z keys, Helper.java:626-648);
  `putVec3i`/`getVec3i` (Helper.java:650-662); `putQuaternion`/`getQuaternion` (nullable,
  Helper.java:664-686); `putUuid`/`getUuid` (Most/Least longs, Helper.java:917-931);
  `getCompoundList(tag,name)` = `tag.getList(name, 10)` (Helper.java:688-690);
  `listTagToList`/`listToListTag`/`listTagDeserialize`/`listTagSerialize` (null entries skipped,
  wrong-class logged, Helper.java:692-733); `vec3ToListTag`/`vec3FromListTag` (Helper.java:1457-1477).
- **Vector misc**: `getFlippedVec(vec, axis)` (Helper.java:933-936), `getProjection(vec, dir)`
  (Helper.java:938-940), `interpolatePos` = `Vec3.lerp` (Helper.java:1129-1136).
- **Collections/streams**: `removeIf(ObjectList, Predicate)` — in-place O(n) compaction (fastutil
  ObjectList lacks efficient removeIf; doc Helper.java:802-817); `removeIfWithEarlyExit`
  (Helper.java:822-837); `compactArrayStorage(int size, IntPredicate valid, SwappingFunc swap) → int` —
  generic in-place array compaction, used by Mesh2D (Helper.java:1354-1404); `wrapAdjacentAndMap`
  (Helper.java:839-879); `mapReduce` (Helper.java:881-891); `compareOldAndNew(oldSet,newSet,onRemoved,onAdded)`
  (Helper.java:735-751); `mappedListView` (Helper.java:1170-1184); `arrayListComputeIfAbsent`
  (Helper.java:1088-1104); `deduplicateWithPrecision(Collection<Vec3>, int precision)`
  (Helper.java:1206-1225); minor: `swapListElement`, `firstOf/lastOf`, `indexOf(list,pred)`, `minBy/maxBy`,
  `listReverseStream`, `getLastSatisfying`, `composeTwoStreamsWithEqualLength`, `uniqueOfThree`,
  `combineNullable`, `SimpleBox<T>` (Helper.java:528-534).
- **Fabric event factories**: `createRunnableEvent()`, `createConsumerEvent()`, `createBiConsumerEvent()`
  — wrap `EventFactory.createArrayBacked` (Helper.java:1424-1455). Used all over imm_ptl for the mod's own
  event objects — a **Fabric-API-specific** construct that needs a multiloader answer.
- **Misc**: `LOGGER` named "iPortal" (Helper.java:63); `log`/`err` deprecated (Helper.java:601-611);
  `secondToNano`/`nanoToSecond` (Helper.java:753-758); `noError(Callable)` (Helper.java:793-800);
  `reflectionInvoke(target, methodName)` (Helper.java:1122-1127); `parseDouble`/`parseInt`
  (Helper.java:1186-1204); `splitStringByLen` (Helper.java:984-994); `cached(Supplier)` (Helper.java:957-971);
  `makeIntoExpression` (Helper.java:912-915).

#### `MyTaskList` — my_util/MyTaskList.java (307 LOC, common; 14 importers)
Deferred/repeating task engine; the tick-driven backbone for IP's chunk loading, teleport bookkeeping, GUI
deferral. Contract: task returns true = finished/removed, false = re-invoked next process
(doc my_util/MyTaskList.java:16-17). `MyTask` has `runAndGetIsFinished()` + default `onCancelled()`
(MyTaskList.java:21-25). Buffered adds: `addTask` appends to `tasksToAdd` (safe during iteration,
MyTaskList.java:31-33); `processTasks()` drains the buffer then `Helper.removeIf`; **exceptions are caught,
logged, and the task is dropped** (MyTaskList.java:42-55); `forceClearTasks()` cancels everything
(MyTaskList.java:57-68). Combinators (all preserve cancellation): `oneShotTask`, `nullTask`, `chainTask`
(MyTaskList.java:81-107), `withDelay(iterations)` (MyTaskList.java:109-129), `withCancelCondition`
(MyTaskList.java:131-147), `withDelayCondition` (MyTaskList.java:149-165), `withTimeDelayedFromNow(seconds)`
(nanoTime-based, MyTaskList.java:167-173), `withRetryNumberLimit` (MyTaskList.java:175-200),
`withInterval(n)` (MyTaskList.java:202-221), `withMacroLifecycle(begin,end,task)` (begin once, end on
finish/cancel, MyTaskList.java:223-250), `withMicroLifecycle` (begin/end around every invocation,
MyTaskList.java:253-271), `chainTasks(Iterator<MyTask>)` (must be finite, MyTaskList.java:273-298),
`repeat(n, supplier)` (MyTaskList.java:300-306). Instances live in `IPGlobal`/`IPPerServerInfo`
(imm_ptl/core/IPPerServerInfo.java:13) and are pumped from tick events — the ticking is owned by imm_ptl,
not this slice.

#### `Signal`, `SignalArged<A>`, `SignalBiArged<A,B>` — my_util/Signal*.java (60 LOC each, common)
Qt-style event objects: `connect(func)`, `disconnect(func)`, `emit(args…)`; all methods synchronized;
during `emit` the list is copy-on-write so connect/disconnect from inside a handler is safe
(`copyDataWhenEmitting`, Signal.java:55-59 and twins). `connectWithWeakRef(owner, func)` wraps the handler
so it self-disconnects once the weak-referenced owner is collected (Signal.java:26-42; the "weak hash map
was a mistake" comment cites the SO pitfall). Consumers: `ImmPtlClientChunkMap`, `PortalGenInfo` etc.

#### Logging/measurement: `LimitedLogger`, `CountDownInt`, `RateStat` (common)
- `LimitedLogger(maxCount)` — deprecated in favor of `CountDownInt` (my_util/LimitedLogger.java:12-15) but
  still used by 11 imm_ptl files; `log/err/lInfo/lErr/invoke/throwException`, stops after N with a notice
  (LimitedLogger.java:49-58).
- `CountDownInt` — non-atomic counted permit: `tryDecrement()`, `isZero()` (my_util/CountDownInt.java:15-41).
- `RateStat` — per-second hit-rate meter on `System.nanoTime` (my_util/RateStat.java:25-50).

#### `ObjectBuffer<T>` — my_util/ObjectBuffer.java (67 LOC)
Object pool (ArrayDeque + creator/destroyer + cacheSize; `takeObject`/`returnObject`/`reserveObjects`/
`destroyAll`, ObjectBuffer.java:29-66). **No importer in current imm_ptl** (grep found none) — historically
pooled secondary framebuffers. Port priority: none unless something re-adopts it.

#### `KeyedTaskList<K>`, `ChangeAccumulator<KEY>` — my_util (33/23 LOC)
Keyed retry-task map (KeyedTaskList.java:19-32) and change-key accumulator (ChangeAccumulator.java:16-22).
**No importers in imm_ptl** — dead in this checkout.

#### `GuiHelper` — my_util/GuiHelper.java (137 LOC, client)
Tiny 1D flex-layout for config screens: `LayoutElement` fixed/elastic, `layout(from, to, elements…)`
distributes leftover length by weight (GuiHelper.java:41-67); adapters `layoutButtonHorizontally`
(`AbstractWidget.setX/setWidth`, GuiHelper.java:13-18), `layoutButtonVertically` (`setY`,
GuiHelper.java:26-30), Rect variants; `Rect.renderTextLeft` uses `GuiGraphics.drawString(font, text, x, y, -1)`
(GuiHelper.java:105-112). MC touch: `Minecraft.getInstance().font`, `AbstractWidget`, `GuiGraphics`, `Component`.

### 2.3 Animation (`my_util/animation/`) — moderate depth

#### `Animated<T>` — my_util/animation/Animated.java (479 LOC, client-usage)
Generic time-based interpolator for smooth wand/HUD visuals: holds `startValue`, `endValue`, `startTime`,
`duration`, a `TypeInfo<T>` (interpolate / isClose / getEmpty, Animated.java:25-33), a `TimeSupplier`
(client render time in practice) and a `TimingFunction` (easing, Animated.java:39-41).
`setTarget(value, newDuration)` — no-op restart if `isClose(endValue, value)` (so calling per-frame with
the same target doesn't reset progress, doc Animated.java:55-67); null target clears; otherwise
`startValue = getCurrent()` and re-anchors time (Animated.java:60-88). `getCurrent()` — clamped progress,
snaps to end at ≥0.999, else `typeInfo.interpolate(start, end, timingFunction(progress))`
(Animated.java:100-124). Prebuilt TypeInfos: `VEC3_NULLABLE_TYPE_INFO` (Animated.java:131-143),
`VEC_3_DEFAULT_ZERO_TYPE_INFO` (Animated.java:145-160), `DOUBLE_DEFAULT_ZERO_TYPE_INFO`
(Animated.java:162-192), `QUATERNION_TYPE_INFO` (slerp via `DQuaternion.interpolate`,
Animated.java:194-204), and the four `Rendered*` infos (Animated.java:206-478) which all share the rules:
scale interpolates; result < 0.01 collapses to NONE/EMPTY; a null side keeps the other's geometry while
scaling; **cross-dimension change jumps straight to `end`** (e.g. Animated.java:242-244).
Consumers: `UnilateralPortalState`, wand client classes (grep §evidence).

#### `RenderedPlane` / `RenderedPoint` / `RenderedLineSegment` / `RenderedSphere` — records (client)
Dimension-tagged nullable geometry + scale for animated wand overlays; each has a NONE/EMPTY constant
(animation/RenderedPlane.java:7-12, RenderedPoint.java:7-9, RenderedLineSegment.java:7-9,
RenderedSphere.java:8-16 — sphere also carries a `DQuaternion orientation`).

### 2.4 Dimension int-id mapping (`dimension/`) — FULL depth

**Decide-relevant verdict: the core portal system NEEDS this package.** Evidence: the client packet
redirection path decodes dimension ints through it
(imm_ptl/core/network/PacketRedirectionClient.java:52-53 `DimensionIntId.getClientMap().fromIntegerId(dimensionIntId)`);
the public API exposes it (imm_ptl/core/api/PortalAPI.java:162-178, four dim⇄int methods); the per-server
holder is `IPPerServerInfo.dimIntIdMap` (imm_ptl/core/IPPerServerInfo.java:15). IP encodes dimensions as
ints inside its redirected-packet wrapper, so remote-dimension sync depends on this map being synced first.
What it does **not** do: dynamic dimension add/remove itself — that lives in the external **DimLib** mod;
this package only *reacts* to DimLib's update event.

#### `DimIntIdMap` — dimension/DimIntIdMap.java (160 LOC, common)
Bidirectional `ResourceKey<Level>` ⇄ int map: `Object2IntOpenHashMap toIntegerId` +
`Int2ObjectOpenHashMap fromIntegerId` + running `maxId` (DimIntIdMap.java:22-34); `MISSING_ID =
Integer.MIN_VALUE` as the Object2Int default (DimIntIdMap.java:20, 33). API: `fromIntegerId(int)` /
`toIntegerId(key)` throw on missing (DimIntIdMap.java:44-68); `fromIntegerIdNullable`
(DimIntIdMap.java:54-58); `add(dimId, intId)` throws on duplicate in either direction
(DimIntIdMap.java:70-86); `remove(dimId)` (DimIntIdMap.java:88-95); `removeUnused(Set)` —
**iterates `toIntegerId.keySet()` while removing** (DimIntIdMap.java:97-106; works only because fastutil's
keySet remove goes through, but is CME-risky if ported to a std map — preserve exact behavior);
`containsDimId`/`containsIntId` (DimIntIdMap.java:108-114); NBT `fromTag`/`toTag(Predicate filter)` —
one compound "intids" of `dimLocationString → int` (DimIntIdMap.java:116-146); `getNextIntegerId()` =
maxId+1 (never reuses freed ids, DimIntIdMap.java:155-157); sorted `toString` (DimIntIdMap.java:159-165).

#### `DimensionIntId` — dimension/DimensionIntId.java (129 LOC, common + client statics)
Lifecycle owner of the map. Server side: `onServerStarted(MinecraftServer)` builds a fresh map with the
**fixed vanilla ids OVERWORLD=0, NETHER=−1, END=1** (fillInVanillaDimIds, DimensionIntId.java:91-101) then
sequential ids for every other `server.getAllLevels()` level (DimensionIntId.java:74-89); stored on
`IPPerServerInfo.of(server).dimIntIdMap` (DimensionIntId.java:86-87; getter validates non-null,
DimensionIntId.java:67-72). `init()` registers on DimLib's `SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT` under a
custom early phase `iportal:early_phase` ordered before default — comment: "make sure that dimension int id
updates before global portal storage update" (DimensionIntId.java:31-44; phase constant :26-27).
`onServerDimensionChanged` adds ids for new dims, `removeUnused` keeping the 3 vanilla keys always, then
**broadcasts `MiscNetworking.DimIdSyncPacket` to every player** (DimensionIntId.java:103-128). Client side:
static `DimIntIdMap clientRecord` (DimensionIntId.java:29) set by the sync packet handler
(MiscNetworking.java:100-102), cleared on `IPCGlobal.CLIENT_EXIT_EVENT` (DimensionIntId.java:46-54);
`getClientMap()` validates non-null with "should not be used in networking thread"
(DimensionIntId.java:59-65).

#### `DimensionIdRecord` — dimension/DimensionIdRecord.java (18 LOC, common)
Deprecated shim kept so Polymer's reflection compat doesn't break (doc + link,
DimensionIdRecord.java:7-11): static `serverRecord` whose `getDim(int)` delegates to
`DimensionIntId.getServerMap(MiscHelper.getServer())` (DimensionIdRecord.java:13-17). Port decision:
carry only if Polymer compat matters; otherwise document as dropped.

### 2.5 Root glue (brief)

- **`MiscHelper`** — MiscHelper.java (118 LOC, common): `gson` with a `ResourceKey<Level>` adapter
  (string form, MiscHelper.java:34-63); `executeOnRenderThread(Runnable)` — run now if
  `Minecraft.isSameThread()` else `client.execute` (execution may be deferred on render thread — doc cites
  `ReentrantBlockableEventLoop#scheduleExecutables`, MiscHelper.java:64-85); `executeOnServerThread(server,
  Runnable)` twin (MiscHelper.java:93-105); deprecated `getServer()` via `MiscGlobals.refMinecraftServer`
  weak ref ("TODO support multi-server-in-one-JVM", MiscHelper.java:86-91); `isDedicatedServer()` via
  `FabricLoader.getEnvironmentType()` (MiscHelper.java:107-109); `getWorldSavingDirectory(server)` —
  chains the duck + accessor: `((IELevelStorageAccess_Misc)((IEMinecraftServer_Misc)server)
  .ip_getStorageSource()).ip_getLevelPath().path()` (MiscHelper.java:112-117).
- **`MiscGlobals`** — MiscGlobals.java (14 LOC): `WeakReference<MinecraftServer> refMinecraftServer`
  (MiscGlobals.java:9-10, written by MixinMinecraftServer_Misc) and `MyTaskList serverTaskList`
  (MiscGlobals.java:12) — **dead: no `processTasks`/`addTask` caller anywhere in the repo** (grep).
- **`CustomTextOverlay`** — CustomTextOverlay.java (133 LOC, client, `@Environment(CLIENT)`): multi-line
  HUD overlay (vanilla `Gui.setOverlayMessage` is single-line — doc CustomTextOverlay.java:17-19). Keyed
  `TreeMap<String, Entry(Component, clearingTimeNanos)>` (CustomTextOverlay.java:23-28); `putText(component,
  durationSeconds, key)` (+3 overloads, default key "5_defaultKey", 0.2s, CustomTextOverlay.java:36-57);
  `render(GuiGraphics, DeltaTracker)` — expires entries, joins components with "\n" into a cached
  `MultiLineLabel` (width = guiScaledWidth−20), renders centered at (width/2, height*0.75), wrapped in
  `guiGraphics.pose().pushPose()/popPose()` and profiler push "imm_ptl_custom_overlay"
  (CustomTextOverlay.java:66-133). Called from MixinGui_Overlay when `!options.hideGui`.
- **`MiscUtilModEntry`** (Fabric `ModInitializer`) — calls `ImplRemoteProcedureCall.init()`,
  `MiscNetworking.init()`, `DimensionIntId.init()` (MiscUtilModEntry.java:8-15).
  **`MiscUtilModEntryClient`** (`ClientModInitializer`) — `ImplRemoteProcedureCall.initClient()`,
  `MiscNetworking.initClient()` (MiscUtilModEntryClient.java:6-11).

### 2.6 Mixins & ducks (mixin config: `src/main/resources/q_misc_util.mixins.json`)

| Mixin | Target & injection | Purpose |
|---|---|---|
| `MixinMinecraftServer_Misc` | `MinecraftServer` ctor `@At("RETURN")` (MixinMinecraftServer_Misc.java:48-56); `createLevels` `@At("RETURN")` (:58-61); implements duck (:63-66) | Sets `MiscGlobals.refMinecraftServer`; calls `DimensionIntId.onServerStarted`; exposes shadowed `storageSource` field as `ip_getStorageSource()` |
| `IELevelStorageAccess_Misc` | `@Accessor("levelDirectory")` on `LevelStorageSource.LevelStorageAccess` (IELevelStorageAccess_Misc.java:7-11) | Read the save directory (`LevelDirectory.path()`) for `MiscHelper.getWorldSavingDirectory` |
| `MixinPlayerList_Misc` | `PlayerList.placeNewPlayer` `@At(INVOKE, ClientboundChangeDifficultyPacket.<init>)` (mixin/dimension/MixinPlayerList_Misc.java:15-32) | Sends `DimIdSyncPacket` to each joining player *mid-login*, anchored before the difficulty packet |
| `MixinGui_Overlay` (client) | `Gui.render(GuiGraphics, DeltaTracker)` `@At("RETURN")` (mixin/client/MixinGui_Overlay.java:21-31) | Renders `CustomTextOverlay` unless `options.hideGui` |
| `IEClientPacketListener_Misc` (client) | `@Accessor("levels")` **setter** on `ClientPacketListener` (mixin/client/IEClientPacketListener_Misc.java:12-14) | **No call sites in the repo** (grep) — dead accessor in this checkout; historically used to make the client accept dynamically-added dimensions |

Duck: `IEMinecraftServer_Misc.ip_getStorageSource()` (ducks/IEMinecraftServer_Misc.java:5-8).

### 2.7 Cross-references to the network slice (NOT owned here)
- `MiscNetworking` (149 LOC) — defines `DimIdSyncPacket` (`CustomPacketPayload`, id
  `imm_ptl:dim_int_id_sync`, MiscNetworking.java:37-44) carrying two NBT tags: the DimIntIdMap and a
  dimId→dimensionTypeId map built from `server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)`
  (MiscNetworking.java:52-79). Client handler sets `DimensionIntId.clientRecord` and
  `ClientWorldLoader.dimIdToDimTypeId` (MiscNetworking.java:99-126). Registered via Fabric
  `PayloadTypeRegistry.playS2C` + `ClientPlayNetworking.registerGlobalReceiver` (MiscNetworking.java:134-148).
- `api/McRemoteProcedureCall` + `ImplRemoteProcedureCall` (159 + 476 LOC) — string-addressed static-method
  RPC ("path.to.Class.method" + auto-serialized args; usage doc api/McRemoteProcedureCall.java:12-60).
  10 imm_ptl importers. Inventory/port analysis belongs to the network slice.

---

## 3. Mechanisms

### 3.1 Plane / transform math conventions (the sign-error minefield)
- `Plane.getDistanceTo` is **signed**: positive = the normal side (Plane.java:13-15). Everything
  ("isPointOnPositiveSide", clip equations, crossing detection) derives from this one sign.
- The plane-equation form handed to rendering is `n·p + w > 0` with `w = −n·pos` (Plane.java:127-145).
- `DQuaternion.hamiltonProduct(other)` applies **other first** (DQuaternion.java:148-151), while
  `combine(other)` applies **this first** (DQuaternion.java:169-172). Both appear throughout portal code.
- Camera rotation = `rotX(pitch) ∘H rotY(yaw+180)`; that quaternion is the *world* rotation for rendering,
  its conjugate is the entity-head rotation (DQuaternion.java:227-239).
- Euler conversions negate yaw in both directions (DQuaternion.java:507-520).
- Portal orientation from geometry: `fromFacingVecs(axisW, axisH)` builds the rotation whose rows are
  (axisW, axisH, axisW×axisH) (DQuaternion.java:416-422); the reverse decomposition is
  `getAxisW/getAxisH/getNormal` = rotations of unit X/Y/Z (DQuaternion.java:530-540).
- Long-run stability: after portal manipulation, call `fixFloatingPointErrorAccumulation()` (snap-to-{0,±1}
  at 1e-7 then normalize, DQuaternion.java:465-491) — the port must keep calling this wherever IP does.

### 3.2 Mesh2D portal-shape pipeline
The irregular-portal shape lives in the portal's local plane space normalized to [−1,1]². Flow used by
`SpecialFlatPortalShape` and friends:
1. Start from `createNewFullQuadMesh()` — the whole square as 2 triangles (Mesh2D.java:1655-1659).
2. Subtract obstructions: for a blocking cube, `GeometryUtil.getSlicePolygonOfCube` computes the convex
   cut polygon in plane-local coords (GeometryUtil.java:304-383), then `subtractPolygon` fan-triangulates
   it (convex requirement, Mesh2D.java:1554-1568) and calls `subtractTriangleFromMesh` per fan triangle.
3. `subtractTriangleFromMesh` (CCW input required, Mesh2D.java:541-569): quadtree-collect candidate
   triangles by bounding box, then per target triangle `subtractTriangleForOneTriangle`
   (Mesh2D.java:576-644): successively dissect the target by the cutter's 3 edge lines
   (`dissectTriangleByLine`, Mesh2D.java:649-691 → `dissectTriangleByEdgesFromVertex`,
   Mesh2D.java:699-857, which splits into a triangle + quad and cuts the quad along the *shorter diagonal*
   to avoid slivers, Mesh2D.java:782-793), finally deleting every fragment whose centroid is strictly
   inside all 3 cutter half-planes (`testSideBool` ∧×3, Mesh2D.java:615-641).
4. `simplify()`/`simplifySteps(limit)` (Mesh2D.java:491-530): first `fixTJunction()` — a point lying on
   another triangle's edge splits that triangle (grid-radius 1e-5 quadtree probe, Mesh2D.java:1315-1370);
   then repeatedly (a) collapse interior points (exposing angle ≈ 2π, Mesh2D.java:203-205, 398-436) and
   (b) collapse boundary points on straight outer edges (exposing angle ≈ π + opposite-vec collinearity)
   or edges with lengthSq < 1e-8 (Mesh2D.java:442-489) — each collapse guarded by `canCollapseEdge`
   (no triangle may flip winding, Mesh2D.java:310-356).
5. `compact()` before persistence/iteration hot paths — in-place compaction of both arrays via
   `Helper.compactArrayStorage` swap-from-end algorithm (Mesh2D.java:909-949; Helper.java:1371-1404).
6. Persistence: `toTag`/`fromTag` (Mesh2D.java:1661-1728) — this is stored in the Portal entity's NBT, so
   **the tag layout (pointCoords double list + triangles int list) is savegame format**.
Invariants enforced everywhere: triangles stored CCW (checkStorageIntegrity validates cross > 0,
Mesh2D.java:1191-1196); the point-grid dedupe means point identity is 2^-30-quantized; the quadtree, once
enabled, must be notified of every add/remove (notifyTriangle*, Mesh2D.java:1143-1158).

### 3.3 QuadTree spatial index
Fixed-region [−1,1]² quadtree, max depth 8, flat int-array children, one element of type T per node.
An item is stored at the deepest node whose quadrant fully contains its bounding box
(acquireNodeForBoundingBoxInternal recursion with coordinate rescale `(v ∓ 0.5)*2`, QuadTree.java:60-93);
queries visit every node overlapping the query box and early-exit on non-null (QuadTree.java:117-156).
Nothing MC-specific; ports verbatim.

### 3.4 raytraceAABB and the box-portal ray path
`Helper.raytraceAABB` is a segment (t∈[0,1]) vs axis-aligned box test that returns hit t, position, and
face normal (Helper.java:117-199). `boxFacingOutwards=true` treats the box as a solid seen from outside
(rejects origin-inside, tests the 3 facing planes); `false` treats it as a room seen from inside. The face
selection XORs the per-axis direction sign with `boxFacingOutwards`: `testXPosi = (lineDeltaX > 0) ^
boxFacingOutwards` (Helper.java:134-136). The `normalXPosi = lineDeltaX <= 0` values (Helper.java:140-142)
are INTERNAL plane-test normals only — they are fed solely to `getCollidingT`, where the normal's sign
cancels in the numerator/denominator ratio (scalar overload, Helper.java:97-115), so they never reach the
result. The normal actually returned in `RayTraceResult` is **testXPosi-signed**:
`new Vec3(testXPosi ? 1 : -1, 0, 0)` (Helper.java:157; Y twin :175, Z twin :193). It opposes the ray ONLY
when `boxFacingOutwards == true` (solid box seen from outside: ray +X hits the min-X face, normal −X);
when `boxFacingOutwards == false` (room seen from inside) the returned normal points ALONG the ray
(ray +X hits the max-X face, normal reported +X). Both modes are live — `BoxPortalShape` passes its own
`facingOutwards` field straight through (imm_ptl/core/portal/shape/BoxPortalShape.java:112-127). Port the
code verbatim; do NOT "fix" the inward-facing normal sign to make it oppose the ray — that would be a
deviation from IP. Consumed by `BoxPortalShape.raytracePortalShapeByLocalPos`
(imm_ptl/core/portal/shape/BoxPortalShape.java:117).

### 3.5 MyTaskList execution semantics
Tick-pumped cooperative tasks. Guarantees the port must preserve: (1) tasks added during processing run
starting the *next* pump (buffer swap, MyTaskList.java:42-44); (2) a throwing task is logged and removed —
it does not poison the list (MyTaskList.java:46-54); (3) every combinator forwards `onCancelled` so
`forceClearTasks` (used at client disconnect) releases resources (MyTaskList.java:57-68 and each
combinator's onCancelled); (4) `withMacroLifecycle` runs `endAction` exactly once on finish *or* cancel
(MyTaskList.java:223-250).

### 3.6 Dimension⇄int id lifecycle (and what depends on it)
Server: map created at `MinecraftServer.createLevels` RETURN (mixin → `DimensionIntId.onServerStarted`,
MixinMinecraftServer_Misc.java:58-61) with pinned vanilla ids {OW=0, Nether=−1, End=1}
(DimensionIntId.java:91-101) — **these three constants are wire/compat contract**. On DimLib's dynamic
dimension update event (early phase, so it beats global-portal-storage updates —
DimensionIntId.java:31-44), new dims get `maxId+1` (never recycled, DimIntIdMap.java:155-157), stale dims
are dropped (vanilla always kept), and the full map is re-broadcast (DimensionIntId.java:103-128).
Client: receives `DimIdSyncPacket` (a) mid-login before the difficulty packet (MixinPlayerList_Misc.java:15-32)
and (b) on every dynamic change; handler replaces `DimensionIntId.clientRecord` and the dimId→dimType map
used by `ClientWorldLoader` to construct remote `ClientLevel`s (MiscNetworking.java:99-126); cleared on
client exit (DimensionIntId.java:46-54). Downstream consumers: packet redirection decodes the int on the
client render thread only (PacketRedirectionClient.java:50-53 guards `minecraft.isSameThread()`), and
`PortalAPI` re-exports the conversions (PortalAPI.java:160-178). For the Portal-26.2 port: our mod has no
DimLib and no dynamic dimensions, but the *sync-before-use ordering* (map must arrive before any
dimension-int-tagged packet is decoded) is the invariant to keep.

### 3.7 Animated interpolation rules
See §2.3: per-frame `setTarget` is idempotent via `isClose`; scale < 0.01 collapses to the empty constant;
cross-dimension targets snap without blending. `Animated` is pure given a `TimeSupplier`, so it ports
verbatim; only the time source (IP feeds client render time) is version-sensitive.

---

## 4. MC API touchpoint list (deduplicated; anything that could break across versions)

**Math/value types (low risk, verify signatures):**
- `net.minecraft.world.phys.Vec3` — ctor, `x/y/z` fields + `x()/y()/z()`, `add`, `subtract`, `scale`,
  `dot`, `cross`, `normalize`, `lerp`, `distanceToSqr`, `lengthSqr`, `length`, `atLowerCornerOf(Vec3i)`
  (Plane, DQuaternion, GeometryUtil, Sphere, Circle, Helper, IntMatrix3.java:103-106).
- `net.minecraft.world.phys.AABB` — public fields `minX..maxZ`, `contract(x,y,z)` (Helper.java:410),
  `contains(x,y,z)` (Helper.java:1166-1167), `getXsize/getYsize/getZsize` (Helper.java:404, 1301-1303),
  ctor `(Vec3, Vec3)` (Helper.java:457-460) and 6-double ctor (Helper.java:1003, 1499).
- `net.minecraft.core.BlockPos` — ctor, `offset(int,int,int)/offset(Vec3i)`, `subtract`,
  `betweenClosedStream(l,h)` (IntBox.java:91), `getX/getY/getZ`.
- `net.minecraft.core.Vec3i`, `net.minecraft.core.Direction` — `getNormal()`, `fromDelta(int,int,int)`
  (AARotation.java:80-84, IntMatrix3.java:58), `getStepX/Y/Z`, `getAxis()`, `getAxisDirection()`,
  `get(AxisDirection, Axis)` (Helper.java:219-222), `fromAxisAndDirection` (IntMatrix3.java:32-34),
  `getOpposite()`, `Direction.Axis.choose(x,y,z)` (Helper.java:226-230), `Direction.values()`.
- `com.mojang.math.OctahedralGroup` — `rotate(Direction)` (IntMatrix3.java:31-37).
- `net.minecraft.world.level.block.Rotation` — enum constants (AARotation.java:183-201).
- `net.minecraft.util.Mth` — `clamp`, `lerp` (Mesh2D.java:42-43, Sphere.java:82, Animated.java:113).
- `net.minecraft.util.Tuple` — (DQuaternion.java:343-359, Helper multiple).
- `net.minecraft.util.Unit` — `Unit.INSTANCE` sentinel (Mesh2D.java:1292 etc.).
- JOML (via MC): `Quaternionf` (ctor, `x()/y()/z()/w()`, `rotateX/Y/Z`, `getEulerAnglesZYX`),
  `Quaterniond`, `Matrix4f.set(Quaternionf)`, `Matrix3f.set(col,row,val)`, `Vector3f`
  (DQuaternion.java:75-83, 507-520; IntMatrix3.java:84-99).

**NBT (HIGH risk — 26.2 changed CompoundTag getter semantics; see MIGRATION_API_MAP):**
- `CompoundTag` — `putDouble/putInt/putString/putLong`, `getDouble/getInt/getString/getLong`,
  `contains(String)`, `contains(String,int)` implicit via getList, `get(String)`, `getCompound`,
  `getAllKeys()` (DimIntIdMap.java:121, CustomTextOverlay n/a, MiscNetworking.java:107), `put(String,Tag)`.
  (DQuaternion.java:436-458, IntBox.java:489-516, Helper.java:512-733, Mesh2D.java:1661-1728,
  DimIntIdMap.java:116-146.)
- `ListTag` — `add`, `getDouble(int)`, `getInt(int)`, `getList(name, type)`, `getElementType()`, `size()`
  (Mesh2D.java:1696-1699, Helper.java:1465-1471).
- `DoubleTag.valueOf`, `IntTag.valueOf`, `StringTag` instanceof + `getAsString` (Helper.java:517-521),
  `Tag.TAG_DOUBLE/TAG_INT` constants (Mesh2D.java:1697-1698).

**Registry / resources:**
- `ResourceKey.create(Registries.DIMENSION, ResourceLocation)` (Helper.java:504-506);
  `ResourceKey.create(Registries.DIMENSION_TYPE, …)` (MiscNetworking.java:113-116);
  `ResourceKey.location()` (DimIntIdMap.java:66, 74).
- `ResourceLocation` construction — routed through `McHelper.newResourceLocation` (imm_ptl!)
  (Helper.java:509, DimensionIntId.java:27).
- `Level.OVERWORLD / NETHER / END` keys (DimensionIntId.java:91-101, Helper.java:525).
- `MinecraftServer.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)` + `Registry.getKey`
  (MiscNetworking.java:56-62 — network slice but registered by this slice's entrypoint).
- `DimensionType`, `BuiltinDimensionTypes.OVERWORLD` fallback (MiscNetworking.java:61-69).

**Server lifecycle / threading:**
- `MinecraftServer` — `isSameThread()`, `execute(Runnable)` (MiscHelper.java:94-103), `getAllLevels()`
  (DimensionIntId.java:79), `levelKeys()` (DimensionIntId.java:106, 112), `getPlayerList().getPlayers()`
  (DimensionIntId.java:125), ctor signature `(Thread, LevelStorageAccess, PackRepository, WorldStem, Proxy,
  DataFixer, Services, ChunkProgressListenerFactory)` (mixin inject, MixinMinecraftServer_Misc.java:52-54),
  **private field `storageSource`** (@Shadow, MixinMinecraftServer_Misc.java:38-40), **method
  `createLevels(ChunkProgressListener)`** (inject target, MixinMinecraftServer_Misc.java:58),
  superclass `ReentrantBlockableEventLoop` (MixinMinecraftServer_Misc.java:28; also doc-referenced
  `scheduleExecutables`, MiscHelper.java:64-67).
- `Minecraft` — `getInstance()`, `isSameThread()`, `execute(Runnable)` (MiscHelper.java:69-85), `font`,
  `gui.getFont()`, `options.hideGui`, `getWindow().getGuiScaledWidth()/getGuiScaledHeight()`,
  `getProfiler().push/pop` (CustomTextOverlay.java:99-133, MixinGui_Overlay.java:28) —
  **profiler access + Gui render signature changed in 26.2's render rewrite.**
- `ServerLevel.dimension()`, `ServerLevel.dimensionType()` (DimensionIntId.java:80,
  MiscNetworking.java:59-61).
- `ServerPlayer.connection.send(Packet)` (DimensionIntId.java:126, MixinPlayerList_Misc.java:29-31),
  `ServerPlayer.server` field (MixinPlayerList_Misc.java:30).
- `PlayerList.placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)` — inject target anchored at
  `ClientboundChangeDifficultyPacket.<init>(Difficulty, boolean)` (MixinPlayerList_Misc.java:15-22) —
  **both the method and the anchor packet ctor must be re-verified on 26.2.**
- `LevelStorageSource.LevelStorageAccess` — private field `levelDirectory` (@Accessor,
  IELevelStorageAccess_Misc.java:9-10), `LevelStorageSource.LevelDirectory.path()` (MiscHelper.java:115).
- `ClientPacketListener` — private field `levels` (@Accessor setter, IEClientPacketListener_Misc.java:13-14;
  currently uncalled).

**GUI / rendering (client; HIGH risk under the 26.2 submit→prepare→execute pipeline):**
- `Gui.render(GuiGraphics, DeltaTracker)` — inject target (MixinGui_Overlay.java:22-27).
- `GuiGraphics` — `drawString(Font, Component, int, int, int)` (GuiHelper.java:107-111),
  `pose().pushPose()/popPose()` (CustomTextOverlay.java:104-129 — pose-stack API is a rewrite casualty).
- `MultiLineLabel.create(Font, Component, int)`, `renderCentered(GuiGraphics, int, int)`,
  `renderLeftAligned(GuiGraphics, int, int, int, int)` (CustomTextOverlay.java:96-127; note in-code comment
  "the parchment names are incorrect", CustomTextOverlay.java:113).
- `Font`, `AbstractWidget.setX/setY/setWidth` (GuiHelper.java:13-30).
- `Component.empty()/append`, `MutableComponent` (CustomTextOverlay.java:82-92).
- `DeltaTracker` (render arg type, CustomTextOverlay.java:66, MixinGui_Overlay.java:25).

**Networking (registered here, owned by network slice):**
- `CustomPacketPayload` + `Type`, `StreamCodec.of`, `FriendlyByteBuf.writeNbt/readNbt`,
  `Packet<ClientCommonPacketListener>` (MiscNetworking.java:37-97).
- Fabric: `PayloadTypeRegistry.playS2C().register`, `ClientPlayNetworking.registerGlobalReceiver`,
  `ServerPlayNetworking.createS2CPacket` (MiscNetworking.java:81-84, 134-148).

**Fabric loader/API (multiloader-abstraction candidates):**
- `ModInitializer` / `ClientModInitializer` entrypoints (MiscUtilModEntry.java:6, MiscUtilModEntryClient.java:5).
- `EnvType` / `@Environment` annotations (MiscHelper, DimensionIntId, CustomTextOverlay).
- `FabricLoader.getInstance().getEnvironmentType()` (MiscHelper.java:108).
- `net.fabricmc.fabric.api.event.Event` + `EventFactory.createArrayBacked` (Helper.java:1424-1455),
  `Event.addPhaseOrdering` + `Event.DEFAULT_PHASE` (DimensionIntId.java:33-36).

**External mod dependency:**
- `qouteall.dimlib.api.DimensionAPI.SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT` (DimensionIntId.java:33-43) —
  not vanilla, not in this repo; `fabric.mod.json` hard-depends `"dimlib": "*"`.

**Bundled libs (stable):** fastutil (`DoubleArrayList`, `IntArrayList`, `Long2IntOpenHashMap`,
`ObjectArrayList`, `IntOpenHashSet`, `Int2ObjectOpenHashMap`, `Object2IntOpenHashMap`, `IntIterator`,
`Int2ObjectFunction`), gson, log4j + slf4j (`LogUtils.getLogger`), commons-lang3 `Validate`/`MutableBoolean`,
Guava (`Streams`, `Iterators.peekingIterator`, `ImmutableMap`, `ImmutableList`).

---

## 5. Registration & wiring

- **Entrypoints** (fabric.mod.json `entrypoints.main` / `.client`): `qouteall.q_misc_util.MiscUtilModEntry`
  → `ImplRemoteProcedureCall.init()`, `MiscNetworking.init()` (S2C payload type registration),
  `DimensionIntId.init()` (DimLib event subscription with early phase);
  `qouteall.q_misc_util.MiscUtilModEntryClient` → RPC client init + `MiscNetworking.initClient()` (global
  receiver). `DimensionIntId.initClient()` is *not* called from the q_misc_util entrypoint — it's called by
  `IPModMainClient` (imm_ptl/core/IPModMainClient.java:134), another q_misc_util→imm_ptl entanglement.
- **Mixins** (`q_misc_util.mixins.json`, package `qouteall.q_misc_util.mixin`, JAVA_17,
  defaultRequire 1): common = `IELevelStorageAccess_Misc`, `MixinMinecraftServer_Misc`,
  `dimension.MixinPlayerList_Misc`; client = `client.IEClientPacketListener_Misc`, `client.MixinGui_Overlay`.
- **No entity types, no blocks, no registries** are registered by this slice.
- **Ticking**: this slice defines `MyTaskList` but ticks nothing itself; `IPGlobal`/`IPPerServerInfo`
  (imm_ptl) own and pump the live task lists. `MiscGlobals.serverTaskList` is vestigial (never pumped).
- **Server reference**: `MiscGlobals.refMinecraftServer` is (re)assigned in the `MinecraftServer`
  constructor mixin (MixinMinecraftServer_Misc.java:48-56); `MiscHelper.getServer()` reads it and is
  already marked deprecated in favor of passing `MinecraftServer` explicitly (MiscHelper.java:86-91) —
  IP's newer code paths use `IPPerServerInfo.of(server)`.
- **Client overlay**: purely mixin-driven (`Gui.render` tail → `CustomTextOverlay.render`), no HUD API.
- **Dim-id sync triggers**: player login (MixinPlayerList_Misc) and DimLib dynamic-update event
  (DimensionIntId.onServerDimensionChanged) — the only two send sites of `DimIdSyncPacket`.

## Dead / vestigial inventory (verified by grep, port-priority zero)
- `MiscGlobals.serverTaskList` — declared only (MiscGlobals.java:12).
- `IEClientPacketListener_Misc.ip_setLevels` — accessor with no callers.
- `ObjectBuffer`, `KeyedTaskList`, `ChangeAccumulator` — no imm_ptl importers.
- `LimitedLogger` — deprecated but still 11 importers (must port).
- `DimensionIdRecord` — deprecated Polymer-compat shim.
- `Mesh2D.debugVisualize` — dev-only (writes file + execs python, Mesh2D.java:1730-1752).
