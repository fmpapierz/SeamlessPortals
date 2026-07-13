# Inventory: Portal entity core

Slice: `qouteall/imm_ptl/core/portal/*.java` (root files), `portal/shape/`, `portal/util/`
Source of truth: `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/imm_ptl/core/portal/` (IP for MC 1.21.3, Mojang mappings).
All file:line citations below are into that tree unless otherwise noted. Total slice size: 6,374 LOC across 21 files.

---

## 1. Overview

This slice is the data model of the entire mod: `Portal` is a vanilla `Entity` subclass (`Portal.java:83-84`) that carries the complete geometric and behavioral definition of one portal — origin position (the entity position itself), an orthonormal in-plane basis `axisW`/`axisH`, extents `width`/`height`/`thickness`, destination dimension+position, an optional rotation quaternion, a scale factor, and a pluggable `PortalShape` strategy (rectangular / arbitrary-mesh flat / 3D box). Everything else in IP — teleportation, collision, chunk loading, rendering — consumes this class through the transformation methods (`transformPoint`, `transformLocalVec`, inverse variants), the state snapshots (`PortalState`, `UnilateralPortalState`), and the seven static Fabric-style events `Portal` exposes (tick signals, dispose signal, NBT read/write signals, client sync/spawn events).

Architecturally the slice divides into: the entity hierarchy (`Portal` → `Mirror` → `BreakableMirror`; `Portal` → `EndPortalEntity`; breakable/nether portals live in the `nether_portal` package, global portals in `global_portals` — both outside this slice but extending these classes); the immutable math snapshots (`PortalState`, plus `UnilateralPortalState` from the `animation` package); the cluster companion (`PortalExtension`, binding flipped/reverse/parallel portals into one logically-synced unit); static factory/query helpers (`PortalManipulation`, `PortalUtils`); the shape strategy (`shape/`); a client-only occlusion-query record (`PortalRenderInfo`); and two small coordinate-record utilities (`util/`). One critical property drives the whole sync design: **`Portal` defines zero `SynchedEntityData` entries** — `defineSynchedData` is deliberately empty (`Portal.java:227-230`) and all replication is a custom full-NBT packet, because vanilla position sync quantizes to 1/4096 which is too coarse for portal animation (`Portal.java:510-518`).

---

## 2. Class-by-class inventory

### 2.1 `Portal` — the portal entity (Portal.java, 1963 LOC, common + a few `@Environment(CLIENT)` members)

**Responsibility:** the portal entity: geometry, transformation math, NBT persistence, custom client sync, tick lifecycle, teleport/interaction gating, cluster/animation accessors.

**Type & registration surface:**
- `class Portal extends Entity implements PortalLike, IPEntityEventListenableEntity` (`Portal.java:83-84`).
- `public static final EntityType<Portal> ENTITY_TYPE = createPortalEntityType(Portal::new)` (`Portal.java:87`).
- `static <T extends Portal> EntityType<T> createPortalEntityType(EntityType.EntityFactory<T>)` (`Portal.java:94-108`): `FabricEntityTypeBuilder.create(MobCategory.MISC, ctor).dimensions(EntityDimensions.fixed(0, 0)).fireImmune().trackRangeBlocks(96).trackedUpdateRate(20).forceTrackedVelocityUpdates(true).build()`. Comment at `:101`: "eye height should be 0". This factory is reused by every portal subclass in the mod.
- Javadoc `Portal.java:80-82`: "Global portals are also entities but not added into world" — global portals are `Portal` instances held by `GlobalPortalStorage`, never in the entity list. Related: `isGlobalPortal` field (`Portal.java:162`), `myUnsetRemoved()` exposing protected `unsetRemoved()` (`Portal.java:1566-1568`), `shouldLimitBoundingBox() { return !getIsGlobal(); }` (`Portal.java:978-980`).

**Static events (Fabric `Event` objects built by `Helper.createConsumerEvent`/`createBiConsumerEvent`):**
| Event | Line | Fired from |
|---|---|---|
| `CLIENT_PORTAL_ACCEPT_SYNC_EVENT` | `Portal.java:89-90` | `acceptDataSync` (`Portal.java:1748`) |
| `CLIENT_PORTAL_SPAWN_EVENT` | `Portal.java:91-92` | `ImmPtlNetworking.PortalSyncPacket.handle` (`network/ImmPtlNetworking.java:224`) |
| `CLIENT_PORTAL_TICK_SIGNAL` | `Portal.java:208-209` | `tick()` client branch (`Portal.java:944`) |
| `SERVER_PORTAL_TICK_SIGNAL` | `Portal.java:210-211` | `tick()` server branch (`Portal.java:952`) |
| `PORTAL_DISPOSE_SIGNAL` | `Portal.java:213-214` | `ip_onRemoved` (`Portal.java:456-458`) |
| `READ_PORTAL_DATA_SIGNAL` | `Portal.java:216-217` | end of `readAdditionalSaveData` (`Portal.java:360`) |
| `WRITE_PORTAL_DATA_SIGNAL` | `Portal.java:218-219` | end of `addAdditionalSaveData` (`Portal.java:424`) |

**State fields (the persisted geometry/behavior model), `Portal.java:113-206`:**
- `double width, height, thickness` (`:113-115`) — extents; `thickness` only meaningful for non-planar shapes; forced to 0 when a planar shape is set (`setPortalShape`, `:436-444`).
- `Vec3 axisW, axisH` (`:117-118`) — normalized, mutually perpendicular in-plane basis ("axisW and axisH define the orientation", `:1429-1432`).
- `ResourceKey<Level> dimensionTo; Vec3 destination` (`:120-122`).
- `boolean teleportable = true` (`:124`).
- `@Nullable PortalShape portalShape` (`:126`) — lazily defaulted to `RectangularPortalShape.INSTANCE` in `getPortalShape()` (`:428-434`).
- `@Nullable UUID specificPlayerId` (`:128-134`) — if non-null, portal is only usable by that player; if it equals `Util.NIL_UUID`, only non-player entities may use it (enforced in `canTeleportEntity`, `:704-718`; broadcast gating in `broadcastToPlayer`, `:921-927`).
- `@Nullable DQuaternion rotation` (`:136-137`) — the rotation *transformation* (this-side → other-side), NOT the orientation.
- `double scaling = 1.0` (`:139`); `boolean teleportChangesScale = true` (`:141`); `boolean teleportChangesGravity` defaulted from `IPConfig.getConfig().portalsChangeGravityByDefault` (`:143-146`).
- `boolean interactable = true` (`:148-151`); `PortalExtension extension` (package-private, `:153`); `@Nullable String portalTag` (`:155-156`); `boolean isGlobalPortal` (`:158-162`); `boolean fuseView` (`:164`); `@Deprecated boolean renderingMergable` (`:166-175`); `boolean crossPortalCollisionEnabled = true` (`:177`); `boolean doRenderPlayer = true` (`:179`); `boolean visible = true` (`:181-184`); `@Nullable List<String> commandsOnTeleported` (`:186-187`).
- `@Environment(CLIENT) PortalRenderInfo portalRenderInfo` (package-private, `:189-190`).
- `public final PortalAnimation animation = new PortalAnimation()` (`:192`) — the animation subsystem's per-portal state (other slice).
- `@Nullable PortalState lastTickPortalState` (`:194`); `boolean reloadAndSyncNextTick` (`:196`).
- **Caches** (`:198-206`): `thinBoundingBoxCache`, `boundingBoxCache`, `normalCache`, `contentDirectionCache`, `portalStateCache`, `thisSideCollisionExclusion`, `thisSideStateCache`, `otherSideStateCache`. All invalidated by `updateCache()` (`:552-570`), which is a no-op until both axes are non-null (`:553-555`).

**Entity-data sync:** none. `defineSynchedData(SynchedEntityData.Builder)` is empty (`Portal.java:227-230`).

**NBT serialization** (`readAdditionalSaveData` `:232-363`, `addAdditionalSaveData` `:365-426`). Keys and semantics:
- Always read: `width`, `height`, `thickness` (doubles); `axisW`, `axisH` (via `Helper.getVec3d`, normalized on read `:237-238`); `dimensionTo` (`Helper.getWorldId`); `destination` (`Helper.getVec3d`); `specificPlayer` (`Helper.getUuid`, written only if non-null `:375-377`).
- `portalShape` (CompoundTag): deserialized by `PortalShapeSerialization.deserialize`; on failure logs and falls back to `RectangularPortalShape.INSTANCE` (`:243-253`). **Legacy upgrade path** (`:254-284`): if no `portalShape` key, an old `specialShape` ListTag (+ `shapeNormalized` bool) is converted via `GeometryPortalShape.readOldMeshFromTag` / `readOldMeshFromTagNonNormalized(list, width/2, height/2)` into a `SpecialFlatPortalShape`, else rectangular.
- Optional (contains-guarded): `teleportable`, `interactable`, `scale` (→ `scaling`), `teleportChangesScale`, `teleportChangesGravity` (default re-read from config when absent, `:312-317`), `portalTag`, `fuseView`, `renderingMergable`, `hasCrossPortalCollision` (→ `crossPortalCollisionEnabled`), `commandsOnTeleported` (ListTag of StringTag, type-id 8, `:335-342`), `doRenderPlayer` (default true), `isVisible` (default true).
- **Rotation quirk (port carefully):** written as four *doubles* `rotationA`=w, `rotationB`=x, `rotationC`=y, `rotationD`=z (`:384-389`), but read back with `getFloat` and reassembled as `new DQuaternion(B, C, D, A)` i.e. (x, y, z, w) (`:290-297`) — works because `CompoundTag.getFloat` coerces any numeric tag, but loses double precision on every load, and the A=w ordering is easy to scramble.
- Tail of both paths: `animation.readFromTag/writeToTag` then the READ/WRITE signals (this is how `PortalExtension` persists its fields inside the portal's own NBT), then `updateCache()` on read (`:358-362`, `:422-425`).
- Wrappers: `writePortalDataToNbt()` (`:1751-1755`), `readPortalDataFromNbt(CompoundTag)` with exception guard + kill-if-invalid (`:1757-1767`), `updatePortalFromNbt(CompoundTag)` merge-then-read (`:1769-1777`).

**Geometry & transformation API (the math heart):**
- `getOriginPos()` = `position()` (`:583-586`); `setOriginPos(Vec3)` = `setPos` — cache update happens via `MixinEntity_U` hook (`:599-603`).
- `getNormal()` = `axisW.cross(axisH).normalize()` cached (`:482-487`). "the normal is no longer the plane normal for 3D portals" (`:479-481`).
- `getContentDirection()` = `transformLocalVecNonScale(getNormal().scale(-1))` cached (`:493-498`) — "should not be used for 3D portals".
- `transformPoint(pos)` = `transformLocalVec(pos − origin) + dest` (`:463-468`).
- `transformLocalVec(v)` = `transformLocalVecNonScale(v).scale(scaling)` (`:473-476`).
- `transformLocalVecNonScale(v)` = `rotation == null ? v : rotation.rotate(v)` (`:1202-1208`).
- `inverseTransformLocalVecNonScale(v)` = conjugated rotate (`:1210-1216`); `inverseTransformLocalVec(v)` divides by scaling (`:1218-1221`); `inverseTransformPoint(p)` = `origin + inverseTransformLocalVec(p − dest)` (`:1223-1226`).
- `transformPointRough(pos)` = pure translation `pos + (dest − origin)` (`:1194-1200`).
- `getFullSpaceTransformation()` = `Matrix4d` composing `translation(dest) · scale(scale) · rotate(rotation) · translate(−origin)` (`:1403-1412`).
- Plane helpers: `getDistanceToPlane` (`:1092-1094`, flat-only), `isInFrontOfPortal` (`:1101-1103`), `getPointInPlane`/`getPointInPlaneLocal`/`...Clamped` (`:1110-1128`), `getFourVerticesLocal(shrink)` with the vertex order `3 2 / 1 0` (`:1130-1152`), `getPointProjectedToPlane`/`getLocalVecProjectedToPlane` (`:1273-1283`), `getNearestPointInPortal` (clamped to rectangle, `:1285-1300`).
- Local frame conversion: `transformFromPortalLocalToWorld` (`:1894-1896`) and `transformFromWorldToPortalLocal` (X=axisW, Y=axisH, Z=normal dot products, `:1898-1905`).
- Orientation vs rotation: `getOrientationRotation()` = `PortalManipulation.getPortalOrientationQuaternion(axisW, axisH)` (`:824-826`); `setOrientationRotation` decodes a quaternion back to axes via `McHelper.getAxisWFromOrientation`/`getAxisHFromOrientation`, applying `fixFloatingPointErrorAccumulation()` on server only (`:828-834`); `setOtherSideOrientation` computes rotation = `PortalManipulation.computeDeltaTransformation(orientation, otherSideOrientation)` (`:859-863`); `setRotation`/`setRotationTransformation(D)` normalize FP error (`:841-857`).
- Velocity: `transformVelocityRelativeToPortal(velRelToPortal, entity, oldPos)` — transform through `transformLocalVec` (`:1071`); if the PRE-transform vector's length exceeds 15 (`:1074`), the POST-transform result is set to exactly magnitude 15 (`result.normalize().scale(15)`, `:1076`) — a condition on input speed with a magnitude override on the output, NOT a clamp of the output (under scaling ≠ 1 the two differ: input speed 20 through a 0.1× portal outputs 15, not 2); then double the result for `AbstractMinecart` when `lengthSqr < 0.5` ("avoid cannot push minecart out of nether portal") (`:1080-1082`).
- Gravity: `getTeleportedGravityDirection(oldDir)` gated by `teleportChangesGravity` (`:1648-1653`); `getTransformedGravityDirection` = `Direction.getNearest(transformLocalVecNonScale(dirVec))` (`:1655-1663`).
- Relation predicates (static; thresholds 0.1 distance / 0.9 dot): `isParallelPortal` (`:1317-1326`), `isParallelOrientedPortal` (same-plane test `|dot| < 0.001`, `:1328-1335`), `isReversePortal` (`:1337-1343`), `isFlippedPortal` (`:1345-1354`).
- `TransformationDesc` record (dimensionTo, full matrix, rotation, scaling) with `isRoughlyEqual` = Frobenius-ish squared diff `< 0.1` (`:1515-1545`); `getTransformationDesc()` (`:1547-1554`) — used by rendering-merge logic.

**State snapshots:**
- `getPortalState()` → `new PortalState(fromDim, origin, toDim, dest, scale, rotationD, orientationRotation, width, height, thickness, this instanceof Mirror)`; asserts `axisH != null` (`:1666-1682`).
- `setPortalState(PortalState)` validates both dims match, then writes extents, origin, destination, orientation (via `PortalManipulation.setPortalOrientationQuaternion`), rotation (null when close to identity), scale (`:1684-1703`).
- `getThisSideState()` / `getOtherSideState()` — cached `UnilateralPortalState` (this-side built directly; other-side via `UnilateralPortalState.extractOtherSide(getPortalState())`) (`:1705-1723`); `setThisSideState(ups, lockScale)` rebuilds via `PortalState.withThisSideUpdated` (`:1725-1735`).
- `getLastTickPortalState()` / `getThisTickPortalState()` (`:1872-1888`) — this-tick is the `portalStateCache`; last-tick recorded at the top of `tick()`.
- `getAnimationEndingState()` (`:1890-1892`).

**Sync protocol (server → client):**
- `getAddEntityPacket(ServerEntity)` returns the custom packet instead of vanilla's (`:897-902`).
- `createSyncPacket()` (`:904-919`): builds full NBT via `addAdditionalSaveData`, wraps in `ImmPtlNetworking.PortalSyncPacket(getId(), getUUID(), getType(), PortalAPI.serverDimKeyToInt(server, originDim), x, y, z, nbt)` through `ServerPlayNetworking.createS2CPacket`, cast to `Packet<ClientGamePacketListener>` (contravariance cast noted at `:911`).
- `reloadAndSyncToClient()` (`:519-529`): asserts non-global + server side, `updateCache()`, sends packet via `McHelper.sendToTrackers`. Javadoc `:510-518`: this is *the only place* portal position syncs; vanilla move packets are too imprecise; `/tp` won't work on portals (references `MixinServerEntity`).
- `reloadAndSyncToClientNextTick()` (flag; `:531-534`), `reloadAndSyncClusterToClientNextTick()` (whole cluster via `PortalExtension.forClusterPortals`; `:536-538`), `reloadAndSyncToClientWithTickDelay(int)` via `ServerTaskList`+`MyTaskList.withDelay` (`:540-546`).
- `reloadPortal()` (`:1783-1796`): server-side "I modified the portal" entry point = `updateCache()` + `rectifyClusterPortals(true)` + sync next tick.
- Client receive: `acceptDataSync(Vec3 pos, CompoundTag customData)` (`@Environment(CLIENT)`, `:1737-1749`): snapshot old state, `setPos`, `readAdditionalSaveData`, start default client animation if `durationTicks > 0`, fire `CLIENT_PORTAL_ACCEPT_SYNC_EVENT`.
- `broadcastToPlayer(ServerPlayer)` override: only the `specificPlayerId` player gets tracked when set (`:921-927`).

**Tick lifecycle (`tick()`, `:929-958`):** (1) error-log if bounding box equals the `NULL_BOX` sentinel; (2) `lastTickPortalState = getThisTickPortalState()`; (3) server: flush `reloadAndSyncNextTick`; (4) client: fire `CLIENT_PORTAL_TICK_SIGNAL`; server: if `!isPortalValid()` log + `remove(RemovalReason.KILLED)` and return, else fire `SERVER_PORTAL_TICK_SIGNAL`; (5) `animation.tick(this)`; (6) `super.tick()`.

**Bounding box:** `makeBoundingBox()` override (`:960-976`) — returns `NULL_BOX` (0-box at origin, `:110-111`) if `axisW == null`; else `getPortalShape().getBoundingBox(getThisSideState(), shouldLimitBoundingBox(), 0.2)`. Rationale comment `:968-971`: non-global portals must limit bbox because "some ticking operations traverse all chunks in bounding box". `getBoundingBox()` override caches (`:572-578`); `getThinBoundingBox()` uses expansion 0.001 (`:1366-1375`); `refreshDimensions()` override just nulls the cache (`:1570-1573`); `move(MoverType, Vec3)` is a no-op — "portal cannot be moved" (`:982-985`).

**Validity (`isPortalValid()`, `:990-1021`):** requires `dimensionTo != null`, nonzero width/height, non-null axes/dest, `axisW.lengthSqr() > 0.9 && axisH.lengthSqr() > 0.9`, `getY() > minY − 100`; server-side additionally: destination level exists on the server and dest pos is within the destination world border; client-side: `ClientWorldLoader.getServerDimensions().contains(dimensionTo)` (`:1023-1030`). Invalid portals self-remove in `tick()`.

**Teleport/interaction gating:**
- `canTeleportEntity(Entity)` (`:697-726`): `teleportable` && not a `Portal` && specificPlayerId rules && `O_O.allowTeleportingEntity(entity, this)` (platform hook) && `((ImmPtlEntityExtension) entity).imm_ptl_canTeleportThroughPortal(this)` (comment `:724`: can't use vanilla `canChangeDimensions` — it blocks riding entities).
- `canCollideWithEntity(Entity)` = `canTeleportEntity` by default (`:733-736`).
- `isInteractableBy(Player)` (`:742-753`): config `enableCrossPortalInteraction` && `interactable` && `visible` && `canTeleportEntity`.
- `onEntityTeleportedOnServer(Entity)` (`:504-508`): runs `commandsOnTeleported` via `McHelper.invokeCommandAs`. Overridable.
- `onCollidingWithEntity(Entity)` empty hook; "NOTE you should not add or remove or move entity here" (`:1617-1621`).

**Ray tracing:** `isMovedThroughPortal(last, now)` = `rayTrace != null` (`:1231-1236`, "does not count animation"); `rayTrace` = `lenientRayTrace(from, to, 0.001)` (`:1238-1243`); `lenientRayTrace` delegates to `getPortalShape().raytracePortalShape(getThisSideState(), …)` returning hit pos (`:1245-1256`); `generalRayTrace` returns the full `RayTraceResult` (`:1258-1262`); `getDistanceToNearestPointInPortal` → shape `roughDistanceToPortalShape` (`:1264-1271`).

**Rendering-facing members (client):**
- `renderViewAreaMesh(Vec3 portalPosRelativeToCamera, TriangleConsumer)` (`@Environment(CLIENT)`, `:876-895`): Mirror special-case offsets the mesh ±0.01 along the normal depending on `IrisInterface.invoker.isShaders() || IPGlobal.pureMirror`; then delegates to `getPortalShape().renderViewAreaMesh(relPos, getThisSideState(), out, getIsGlobal())`.
- `getAdditionalCameraTransformation()` → `PortalRenderer.getPortalTransformation(this)` returning `Matrix4f` (`:1575-1580`).
- `isRoughlyVisibleTo(cameraPos)` → shape `roughTestVisibility(…, IrisInterface.invoker.isShaders())` (`:1377-1384`).
- `getInnerClipping()` → shape (`:1386-1392`); `getOuterFrustumCullingVertices()` = four unshrunk local vertices (`:1394-1400`); `canDoOuterFrustumCulling()` = not fuseView && visible && shape allows (`:1582-1590`).
- `cannotRenderInMe(Portal)` — parallel(-oriented) portal exclusion, switched by `respectParallelOrientedPortal()` (default false; MiniScaled rationale in javadoc `:1600-1615`) (`:1556-1564`).
- `getDiscriminator()` = `getUUID()` (`:1032-1039`).

**Misc public API:** getters/setters for every field (`:600-867`, `:1414-1513`, `:1628-1646`); `setOrientationAndSize` (`:785-794`); `setPortalSize` (`:817-822`); `getDestinationWorld()` client/server fork — `CHelper.getClientWorld(dimensionTo)` vs `server.getLevel(dimensionTo)` (`:1302-1315`); `isOtherSideChunkLoaded()` via `McHelper.isServerChunkFullyLoaded` (`:1863-1870`); `getApproximateFacingDirection()` = `Direction.getNearest(normal)` (`:1056-1060`); `toString()` diagnostic format (`:1041-1054`); animation facade (`getAnimationView` `:1828-1861`, `getAnimationHolder`/`getPossibleAnimationHolder` scanning cluster for the driver holder `:1907-1942`, `getAnimationEffectiveTime` `:1944-1947`, `disableDefaultAnimation` `:1949-1952`, `addThisSide/OtherSideAnimationDriver` `:1806-1814`, `pauseAnimation`/`resumeAnimation` `:1816-1822`, `resetAnimationReferenceState` `:1824-1826`, `clearAnimationDrivers` `:1802-1804`, `getDefaultAnimation` `:1798-1800`); `rectifyClusterPortals(boolean sync)` (`:1779-1781`); `getThisSideCollisionExclusion()` cached VoxelShape from shape (`:1954-1961`).

**Lifecycle hooks (`IPEntityEventListenableEntity`):** `ip_onEntityPositionUpdated()` → `updateCache()` (`:450-453`); `ip_onRemoved(reason)` → fire `PORTAL_DISPOSE_SIGNAL` (`:455-458`). Wired by `MixinEntity_U` (see §5).

**Dependencies (IP):** `McHelper`, `CHelper`, `ClientWorldLoader`, `IPGlobal`, `IPConfig`, `O_O`, `PortalAPI`, `ImmPtlEntityExtension`, `IrisInterface`, `ServerTaskList`, `ImmPtlNetworking`, `PortalRenderer`, `animation` package (`PortalAnimation`, `AnimationView`, `DefaultPortalAnimation`, `PortalAnimationDriver`, `UnilateralPortalState`), `shape` package, `q_misc_util` (`Helper`, `DQuaternion`, `Mesh2D`, `MyTaskList`, `Plane`, `RayTraceResult`, `TriangleConsumer`).

### 2.2 `PortalLike` — interface (PortalLike.java, 108 LOC, common)

**Responsibility:** the abstraction over `Portal` used by the renderer and collision code. Marked "This is no longer needed. May be deleted in the future." (`PortalLike.java:17-19`) — but still the type the render pipeline consumes, so it must be ported.

Abstract methods (`:21-81`): `isConventionalPortal`, `getThinBoundingBox`, `transformPoint`, `transformLocalVec`, `transformLocalVecNonScale`, `inverseTransformLocalVec`, `inverseTransformPoint`, `getDistanceToNearestPointInPortal`, `getDestAreaRadiusEstimation`, `getOriginPos`, `getDestPos`, `getOriginWorld`, `getDestWorld`, `getDestDim`, `isRoughlyVisibleTo` (client), `getInnerClipping`, `getRotation`, `getScale`, `getIsGlobal`, `isVisible`, `getOuterFrustumCullingVertices`, `getAdditionalCameraTransformation` (`Matrix4f`), `getDiscriminator` (UUID), `cannotRenderInMe(Portal)`, `isFuseView`, `getDoRenderPlayer`, `getHasCrossPortalCollision`.

Defaults: `hasScaling()` = `|scale − 1| > 0.01` (`:83-85`); `getOriginDim()` (`:87-89`); `isOnDestinationSide(entityPos, valve)` — signed distance past the inner clipping plane `> valve`, true when no plane (`:91-100`); `getSizeEstimation()` = max dimension of the thin bbox (`:102-106`).

### 2.3 `PortalState` — immutable full-portal snapshot (PortalState.java, 234 LOC, common)

**Responsibility:** "The animatable states of a portal" (`PortalState.java:14-17`) — an immutable value of both sides used by the animation system, teleportation math, and NBT-storable state.

Fields (all `public final`, `:19-29`): `fromWorld`, `fromPos`, `toWorld`, `toPos`, `scaling`, `rotation` (DQuaternion transformation), `orientation` (this-side orientation quaternion), `width`, `height`, `thickness`, `isMirror`.

Key API:
- Two constructors (legacy one forces thickness=0, isMirror=false, `:31-48`; full one `:50-67`).
- `withThisSideUpdated(UnilateralPortalState thisSide, boolean lockScale)` (`:69-85`): re-extracts the other side, optionally rescales other-side extents by `scaling` to keep relative scale, recombines via `UnilateralPortalState.combine`.
- `toTag()`/`fromTag()` (`:87-119`): keys `fromWorld`/`toWorld` (dim id strings), `fromPos`/`toPos`, `scaling`, `width`, `height`, `thickness`, `rotation`, `orientation` (quaternion sub-tags), `isMirror`.
- `interpolate(a, b, progress, inverseScale)` (`:121-140`): lerps positions (`Helper.interpolatePos`), slerps quaternions (`DQuaternion.interpolate`), lerps extents (`Mth.lerp`); `inverseScale` lerps `1/scale` reciprocally (`:142-151`) — used for the reverse side of a scaling animation.
- Transformation: `transformPoint` (`:164-170`) and `transformVec` (`:176-186`) — rotate → scale → mirror-reflect if `isMirror` (via `Mirror.mirroredVec`); explicit note that these must not be used for mirror teleportation (`:160-163`).
- Local frame: `worldPosToPortalLocalPos`/`portalLocalPosToWorldPos` (X=axisW, Y=axisH, Z=W×H; `:188-209`), `getPointOnSurface` (`:153-158`), `getLocalPosTransformed` (`:211-217`).
- `getNormal()` from orientation (`McHelper.getNormalFromOrientation`, `:219-221`); `getContentDirection()` = `rotation.rotate(−normal)` (`:223-225`); `getThisSideState()`/`getOtherSideState()` via `UnilateralPortalState.extractThisSide/OtherSide` (`:227-233`).

### 2.4 `PortalExtension` — cluster companion (PortalExtension.java, 518 LOC, common)

**Responsibility:** "the additional features of a portal" (`:14`): motion affinity, post-teleport adjustment flag, and the **cluster binding** of flipped/reverse/parallel portals so a 4-portal bi-way bi-faced group behaves as one object.

State: `double motionAffinity = 0` (accelerate/decelerate touching players, `:47-52`); `boolean adjustPositionAfterTeleport = true` (levitate out of blocks, `:54-58`); `boolean bindCluster = true` (`:60`); persisted `@Nullable UUID reversePortalId/flippedPortalId/parallelPortalId` (`:62-68`); runtime `@Nullable Portal reversePortal/flippedPortal/parallelPortal` (`:70-76`).

Lifecycle & wiring:
- `get(Portal)` lazily creates and stores on `portal.extension` (`:22-27`).
- `init()` (`:29-45`) registers into all four `Portal` signals: ticks on both sides, NBT piggyback via READ/WRITE_PORTAL_DATA_SIGNAL. NBT keys: `motionAffinity` (only if ≠0), `adjustPositionAfterTeleport`, `bindCluster`, `reversePortalId`/`flippedPortalId`/`parallelPortalId` (UUIDs, `:82-138`).
- Server tick `updateClusterStatusServer` (`:149-281`): skips `Mirror`; clears removed refs; resolves UUIDs → entities via `((IEWorld) level).portal_getEntityLookup().get(uuid)` (flipped in origin world `:176`, reverse/parallel in dest world `:197,220`); breaks the link (id=null) only when the other-side chunk is loaded (`portal.isOtherSideChunkLoaded()`, `:202-207`); when an id is null, auto-discovers via `PortalManipulation.findFlippedPortal/findReversePortal/findParallelPortal` (`:186-239`); force-sets `bindCluster=true` on all linked portals (`:250-258`); fixes legacy self-references (`:260-276`); calls `portal.reloadAndSyncToClient()` when anything changed (`:278-280`).
- Client tick `updateClusterStatusClient` (`:284-322`): resolves ids to entities, no discovery.
- `rectifyClusterPortals(Portal portal, boolean sync)` (`:324-447`) — makes the linked portals geometric functions of the primary (see §3.5 for the formulas). Moves cross-dimension via `ServerTeleportationManager.teleportRegularEntityTo` (`:330-334`, `:369-374`, `:412-417`).
- `initializeClusterBind(f1, f2, t1, t2)` (`:449-477`): wires the full 4-portal UUID graph (f=from-side pair, t=to-side pair; f1↔t1 reverse, f1↔t2 parallel, etc).
- Iteration helpers `forClusterPortals` / `forConnectedPortals` / `forEachClusterPortal` (`:479-516`).

**Dependencies:** `IEWorld` duck (entity lookup), `ServerTeleportationManager` (teleportation slice), `PortalManipulation`.

### 2.5 `PortalManipulation` — static portal factory/geometry helpers (PortalManipulation.java, 520 LOC, common/server-mostly)

**Responsibility:** create/copy/flip/reverse portals, orientation quaternion math, cluster discovery searches, portal placement.

Key API:
- `flipAxisW` constant: 180° rotation around +Y, its own inverse (`:35-38`).
- `setPortalTransformation(portal, destDim, destPos, rotation, scale)` (`:40-52`).
- `completeBiWayPortal(portal, entityType)` = `createReversePortal` + `McHelper.spawnServerEntity` (`:79-85`).
- `createReversePortal(portal, entityType)` (`:87-117`): in dest world at destPos; destination = origin; `width/height/thickness × scaling`; `axisW = −axisW, axisH = axisH`; shape = `getPortalShape().getReverse()`; if rotated: `rotatePortalBody(newPortal, rotation)` then `newRotation = rotation.getConjugated()`; `scaling = 1/scaling`; `copyAdditionalProperties`.
- `rotatePortalBody(portal, rotation)` rotates both axes (`:119-122`).
- `completeBiFacedPortal` / `createFlippedPortal` (`:124-156`): same world/pos; `axisW = −axisW`; shape `getFlipped()`; same rotation/scale.
- `copyPortal(portal, entityType)` (`:158-181`) — exact copy, not added to world (note: does NOT copy thickness — only width/height, `:167-168`).
- `completeBiWayBiFacedPortal(portal, removalInformer, addingInformer, entityType)` (`:183-217`): removes overlapped portals at each of the 4 slots then creates flipped + two reverses.
- `removeOverlappedPortals(world, pos, normal, predicate, informer)` (`:219-230`) / `getPortalCluster(world, pos, normal, predicate)` — box ±0.1 around pos, radius `IPGlobal.maxNormalPortalRadius`, normal dot > 0.5 (`:232-248`).
- `createOrthodoxPortal(entityType, fromWorld, toWorld, facing, portalArea, destination)` via `PortalAPI.setPortalOrthodoxShape` (`:250-264`).
- `copyAdditionalProperties(to, from[, includeSpecialProperties])` (`:266-286`): teleportable, teleportChangesScale, teleportChangesGravity, specificPlayerId, motionAffinity, adjustPositionAfterTeleport, crossPortalCollisionEnabled, bindCluster, defaultAnimation copy, visible; special = portalTag + commandsOnTeleported.
- `@Deprecated createScaledBoxView(…)` — legacy 6-portal scale box (`:288-331`).
- `placePortal(width, height, entity)` (`:333-400`): portal-aware raytrace (`IPMcHelper.rayTrace`) from the entity's look; transforms the look vector through each crossed portal (`:366-368`); computes axisH from the hit face normal, axisW = axisH × look-opposite; spawns in the last crossed portal's destination world; uses `setPosRaw` (`:389-391`).
- `getPortalOrientationQuaternion(axisW, axisH)` = `DQuaternion.matrixToQuaternion(axisW, axisH, axisW×axisH)` (`:402-408`); `setPortalOrientationQuaternion` (`:410-414`).
- `adjustRotationToConnect(portalA, portalB)` (`:416-430`): delta = `b.hamiltonProduct(a.conjugated)`, then flip 180° around B's axisH; sets A's rotation and B's conjugate.
- `isOtherSideBoxInside(transformedBox, renderingPortal)` — any of 8 vertices past the inner clipping (`:432-436`).
- `findParallelPortal` / `findReversePortal` / `findFlippedPortal` (`:438-478`): `McHelper.findEntitiesRough` radius 0, position ε² < 0.01, dot thresholds ±0.9, excludes `Mirror` and self.
- `computeDeltaTransformation(thisSideOrientation, otherSideOrientation)` = `otherSideOrientation · flipAxisW · thisSideOrientation⁻¹` (derivation comment at `:497-499`; `flipAxisW` is self-inverse) (`:490-503`).
- `makePortalRound(portal, triangleNum)` — builds a triangle-fan `Mesh2D` unit circle, sets `SpecialFlatPortalShape` (`:505-519`).
- `@Deprecated raytracePortals` → `PortalCommand.raytracePortals` (`:480-488`).

### 2.6 `Mirror` — reflecting portal (Mirror.java, 83 LOC, common)

Extends `Portal`; own `ENTITY_TYPE` (`:13`). Never teleports: `canTeleportEntity` returns false (`:32-35`) and `tick()` re-forces `setTeleportable(false); setInteractable(false)` *every tick* (`:19-24`).

Transformation override — **rotate before mirror** (`:26-30`): `transformLocalVecNonScale(v)` = `getMirrored(super.transformLocalVecNonScale(v))`; inverse mirrors first then un-rotates (`:47-50`). `mirroredVec(vec, normal)` = `vec − 2(vec·normal)normal` (`:42-45`; also used by `PortalState.transformVec`). `getFullSpaceTransformation()` inserts `.reflect(normal, 0)` between translation and scale (`:52-63`). `setRotationTransformationForMirror(visualRotation)` (`:65-82`): computes `newRotation = visualRotation · mirror⁻¹` with JOML `Matrix3d.reflect/rotate` and `Quaterniond.setFromNormalized` (derivation in the javadoc `:65-73`).

### 2.7 `BreakableMirror` — glass-backed mirror (BreakableMirror.java, 283 LOC, common/server-gen)

Extends `Mirror`; own `ENTITY_TYPE` (`:30-31`). State: `@Nullable IntBox wallArea`, `@Nullable BlockPortalShape blockPortalShape` (from the `nether_portal` package), `boolean unbreakable` (`:33-37`). NBT: `boxXL/YL/ZL/XH/YH/ZH` ints, `blockPortalShape` compound, `unbreakable` (`:43-91`).

Behavior: tick checks wall integrity every 10 ticks, staggered by `getId() % 10` (`:94-103`); `checkWallIntegrity` requires every block of the area to still satisfy `isGlass` else `remove(KILLED)` (`:110-128`); `isGlass` = `Blocks.GLASS`/`GLASS_PANE`/`StainedGlassBlock`/`StainedGlassPaneBlock` (`:130-136`); `isPortalValid` additionally requires an area (`:105-108`).

Creation: `createMirror(ServerLevel, glassPos, facing)` (`:143-214`) — rejects Y-facing panes; finds the glass sheet via `BlockPortalShape.findArea` with an air-in-front predicate (`:159-167`); positions the mirror plane at 1/16 (pane) or 0.5 (block) from block center (`:175`, `:184-195`); sizes from `McHelper.getWallBox`; axes from `Helper.getPerpendicularDirections(facing)` (`:199-205`); non-rectangular shapes get a `Mesh2D` built from each block's collision-shape bounds → `SpecialFlatPortalShape` (`initializeMirrorGeometryShape`, `:216-251`); removes intersecting older mirrors (`breakIntersectedMirror`, `:265-281`); spawned via `world.addFreshEntity` (`:211`).

### 2.8 `EndPortalEntity` — end portal replacement (EndPortalEntity.java, 364 LOC, common + client tick)

Extends `Portal`; own `ENTITY_TYPE` (`:43-44`); constructor disables cross-portal collision (`:50-57`). Four modes from `IPGlobal.endPortalMode` handled in `onEndPortalComplete(ServerLevel world, Vec3 portalCenter)` (`:59-98`): `normal` (portal to (0,120,0)), `toObsidianPlatform` (to `ServerLevel.END_SPAWN_POINT` + 1, `:70-75`), `scaledView` / `scaledViewRotating`. Also creates the end platform (`EndPlatformFeature.createEndPlatform`, `:103-108`) and pokes the dragon fight scan via the `IEEndDragonFight` mixin duck (`:90-97`).

- `generateClassicalEndPortal` (`:110-126`): 3×3 flat portal, axisW=(0,0,1), axisH=(1,0,0), spawned by `addFreshEntity`.
- `generateScaledViewEndPortal` (`:128-194`): a 3×1.5×3 `BoxPortalShape.FACING_OUTWARDS` box portal scaled ×96 to show an 18-chunk (288-block) end view; `teleportChangesScale=false`, `interactable=false`, `crossPortalCollisionEnabled=false`, `fuseView=true`, `portalTag="view_box"` (`:45`); rotating mode adds a `RotationAnimation` (0.5°/tick around Y, infinite) plus a `NormalAnimation` vertical oscillation (`:172-191`); spawned via `McHelper.spawnServerEntity`.
- Client tick (`:205-243`): for the view-box portal, damps player `deltaMovement` (×0.5) when the eye is within 1 block and inside the horizontal teleport range; legacy path for upward-facing portals via the `IEEntity.ip_getCollidingPortal` duck (`:226-240`); re-forces `setFuseView(true)`.
- `onEntityTeleportedOnServer` (`:245-272`): adds SLOW_FALLING (duration 200, or 400 for view-box, amplifier 1) to living entities except creative players and elytra-wearers (`shouldAddSlowFalling`, `:315-330`); re-creates the end platform.
- `transformVelocityRelativeToPortal` override (`:278-296`): view-box portals zero the velocity when the entity crossed >1 block off-axis horizontally.
- `canTeleportEntity` (`:298-313`): arrows never (end-crystal sniping); box shape requires eye within horizontal range (`isInBoxPortalTeleportataionRange` [sic], radius 1.5, `:356-362`).
- `shouldLimitBoundingBox()` returns false — "if the bounding box is too small grouping will fail" (`:332-337`); `onCollidingWithEntity` pre-generates the obsidian platform for `toObsidianPlatform` mode (`:339-354`).

### 2.9 `LoadingIndicatorEntity` — progress display entity (LoadingIndicatorEntity.java, 156 LOC, common + client tick)

Plain `Entity` (NOT a Portal). Own `entityType` via `FabricEntityTypeBuilder … dimensions fixed(1,1) … fireImmune().trackable(96, 20)` (`:26-32`). **The only class in this slice that uses `SynchedEntityData`:** `TEXT` (`EntityDataSerializers.COMPONENT`), `BOX_LOW_POS`, `BOX_HIGH_POS` (`EntityDataSerializers.BLOCK_POS`) defined via `SynchedEntityData.defineId` (`:34-42`) and `defineSynchedData(Builder)` (`:108-113`). Transient `isValid` — server removes stale indicators after restart (`:44`, `:57-62`). NBT read/write are empty (`:115-123`).

Client tick: spawns 20–50 `ParticleTypes.PORTAL` particles per tick across the synced `IntBox` (`:81-106`); after 40 ticks, shows the synced text via `Minecraft.getInstance().gui.setOverlayMessage(text, false)` when the player is within 16 blocks in the same level (`:65-79`, `:149-155`). API: `inform/setText/getText`, `setBox/getBox` (`:125-147`). Used by nether portal generation (outside slice).

### 2.10 `PortalRenderInfo` — client occlusion-query state (PortalRenderInfo.java, 260 LOC, client-only)

`@Environment(EnvType.CLIENT)`; lives on `portal.portalRenderInfo` (package-private access is why the class sits in this package, comment `:25-26`). Holds `Map<List<UUID>, Visibility>` keyed by the rendering-description chain (`WorldRenderInfo.getRenderingDescription()`, `:219`); each `Visibility` holds last-frame and this-frame `GlQueryObject`s + rendered flags (`:33-70`).

- `init()` (`:81-97`): registers on `CLIENT_PORTAL_TICK_SIGNAL` (tick assert only) and `PORTAL_DISPOSE_SIGNAL` (dispose GL queries).
- `getOptional`/`get(portal)` create-on-demand, asserting client side (`:99-113`).
- GC safety: `Cleaner` registered per instance; the cleanup posts to `IPGlobal.PRE_TOTAL_RENDER_TASK_LIST` because the cleaner runs off-thread (`:115-139`, `:259`).
- `updateQuerySet()` rotates queries per `RenderStates.frameIndex`; non-consecutive frames dispose everything (`:154-173`).
- **Core algorithm** `renderAndDecideVisibility(portal, queryRendering)` (`:212-257`): if `IPGlobal.offsetOcclusionQuery`, issue this frame's any-sample-passed GL query around `queryRendering`, but *decide* from the last frame's result (one-frame-latency prediction, no stall). A blocking same-frame fetch happens in exactly two cases: (a) no last-frame query exists (`:246-251`, unconditional); (b) the last-frame result was INVISIBLE **and** `noPredict` holds, where `noPredict` = frequently mispredicted (>5 total mispredicts or two mispredicts within 30s — `:188-196`) OR `QueryManager.queryStallCounter <= 3` (`:228-230`) — `if (!lastFrameVisible && noPredict)` (`:235`). A last-frame-VISIBLE result is used as the decision regardless of `noPredict` (`:241-244`). With `offsetOcclusionQuery` off entirely, the whole thing is a blocking `QueryManager.renderAndGetDoesAnySamplePass` (`:253-255`). Uses `Minecraft.getInstance().getProfiler()` push/pop (`:213`, `:236-249`) — a known 26.2 API change point.

### 2.11 `PortalUtils` — portal ray tracing (PortalUtils.java, 249 LOC, common)

- `raytracePortals(world, from, to, includeGlobalPortal, predicate)` → `lenientRayTracePortals(…, 0.001)` (`:30-37`): collects portals via `McHelper.getEntitiesNearby(world, from, Portal.class, rayLength)`, concats `GlobalPortalStorage.getGlobalPortals(world)` when asked, maps to `portal.generalRayTrace`, filters by predicate, takes the nearest hit (`:39-69`). Note `:27-28`: invisible portals ARE hit unless the predicate excludes them.
- `raytracePortalFromEntityView(entity, partialTick, maxDistance, includeGlobalPortal, predicate)` (`:71-79`).
- `PortalAwareRaytraceResult(Level world, BlockHitResult hitResult, List<Portal> portalsPassingThrough)` record (`:81-85`).
- `portalAwareRayTrace` overload family (`:87-153`) → `portalAwareRayTraceFull(world, start, dir, maxDistance, entity, clipBlock, clipFluid, portalsPassingThrough, maxPortalLayer)` (`:155-248`): recursion depth default 5; each level raytraces portals (interactable-by-player or visible filter, `:169-179`) AND blocks (`world.clip(new ClipContext(...))`, `:181-188`); portal wins ties within +0.0001 (`:198-203`); on portal hit, continues from `portal.transformPoint(hitPos) − 0.001·surfaceNormal` with direction `portal.transformLocalVecNonScale(direction)` and the remaining distance, in `portal.getDestinationWorld()` (`:220-244`).

### 2.12 `GeometryPortalShape` — legacy shape NBT reader (GeometryPortalShape.java, 75 LOC, common)

Only two static methods, used exclusively by `Portal.readAdditionalSaveData`'s upgrade path: `readOldMeshFromTag(ListTag)` (`:14-42`) and `readOldMeshFromTagNonNormalized(ListTag, halfWidth, halfHeight)` (`:44-74`) — 6 doubles per triangle, capped at `MAX_TRIANGLE_NUM = 10000` (`:12`), returning `null` for invalid/empty data.

### 2.13 `IntraClusterRelation` — enum (IntraClusterRelation.java, 16 LOC, common)

`SAME(false,false)`, `FLIPPED(true,false)`, `REVERSE(false,true)`, `PARALLEL(true,true)` with `isFlipped`/`isReverse` fields. Consumed by `AnimationView` (animation slice) via `Portal.getAnimationView` (`Portal.java:1828-1861`).

### 2.14 `PortalPlaceholderBlock` — invisible frame-filler block (PortalPlaceholderBlock.java, 156 LOC, common)

The one Block in the slice (breakable/nether portals fill their frame holes with it). `EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS` (`:31`); thin axis-dependent `VoxelShape`s like the vanilla nether portal block (`:32-55`); singleton `instance` with `Properties.of().noCollission().sound(GLASS).strength(1.0f, 0).noOcclusion().noLootTable().lightLevel(s -> 15)` (`:57-65`). `updateShape` override (new 1.21.2+ signature with `LevelReader, ScheduledTickAccess, RandomSource`, `:96-122`): on server, when a neighbor changed off-axis, notifies nearby `BreakablePortalEntity.notifyPlaceholderUpdate()` (radius 2 via `McHelper.findEntitiesRough`). `isHitOnPlaceholder(HitResult, Level)` helper (`:124-132`). Barrier-like: `propagatesSkylightDown` true, `getRenderShape` = `RenderShape.INVISIBLE`, `getShadeBrightness` = 1.0 (`:134-155`).

### 2.15 `shape/PortalShape` — shape strategy interface (shape/PortalShape.java, 206 LOC, common + client methods)

The polymorphic geometry strategy every `Portal` holds. Methods (all take `UnilateralPortalState` — position+orientation+extents of ONE side — so shapes are stateless w.r.t. the portal):
- `isPlanar()` (`:22`); `getBoundingBox(state, limitSize, boxExpand)` (`:24-32`, limitSize=true for non-global); `roughDistanceToPortalShape(state, pos)` (`:34-36`).
- `raytracePortalShapeByLocalPos(state, localFrom, localTo, leniency)` (`:38-41`) + default `raytracePortalShape` that converts global→local via `state.transformGlobalToLocal`, then maps the hit back with `transformLocalToGlobal`/`transformVecLocalToGlobal` (`:43-64`).
- `getOuterClipping(state)` / `getInnerClipping(thisSideState, otherSideState, portal)` (`:66-74`); default `getNearbyPortalPlanes` wraps outer clipping (`:76-87`).
- `getFlipped()` / `getReverse()` (`:89-91`); `cloneIfNecessary()` (`:168`).
- `roughTestVisibility(state, cameraPos, isIrisShaderOn)` (`:93-97`).
- `@Environment(CLIENT) renderViewAreaMesh(originRelToCamera, state, TriangleConsumer, isGlobalPortal)` (`:99-105`) — outputs raw triangles; no vanilla buffer types here.
- Collision: `canCollideWith(portal, state, entityEyePos, entityBox)` (`:107-111`); default `isBoxInPortalProjection` — transforms 8 box vertices to local, min/max, delegates (`:113-146`); `isLocalBoxInPortalProjection` (`:148-152`); default `getThisSideCollisionExclusion` = null (`:154-158`); `getMovementForPushingEntityOutOfPortal(portal, state, entity, attemptedMove)` — "Entities are pushed out when the other side of the portal is not yet loaded" (`:160-166`); default `transformEntityActiveCollisionBox(portal, box, entity)` = box (`:183-188`).
- Client culling defaults: `canDoOuterFrustumCulling()` false (`:170-173`); `getInnerFrustumCullingFunc` / `getOuterFrustumCullingFunc` null (`:175-181`, `:190-195`); `getModifiedVisibleSectionIterationOrigin(portal, cameraPos)` null — returns a `SectionPos` (`:197-203`).
- `shouldRenderInside(portal, box)` (`:205`).

### 2.16 `shape/RectangularPortalShape` — default shape (shape/RectangularPortalShape.java, 275 LOC)

Stateless singleton `INSTANCE` (`:26`); serializer type `"rectangular"` registered in `init()` (`:28-35`). `isPlanar` true. Highlights:
- `getBoundingBox`: half-extents clamped to 64 when limited; box of 8 transformed corners at ±boxExpand along local Z (`:42-67`).
- `raytracePortalShapeByLocalPos` (`:82-109`): only front→back crossings (`localFrom.z > 0 && localTo.z < 0`); solves `t = −from.z/Δz`; hit if `|x| < w/2+leniency && |y| < h/2+leniency`; surface normal local (0,0,1). Comment `:95`: "do not trust GitHub copilot. It may use z as up axis."
- Clipping: outer plane at this-side position/normal (`:111-118`); inner plane at other-side position/normal (`:120-129`).
- `getFlipped`/`getReverse` return `this` (symmetric) (`:131-139`).
- `renderViewAreaMesh`: one full quad via `ViewAreaRenderer.outputFullQuad`, extent clamped to 23333 (`:141-158`).
- `roughTestVisibility`: camera local z > 0 (`:160-169`).
- `canCollideWith`: eye in front && box-projection overlap (`:171-183`); `isLocalBoxInPortalProjection`: X/Y range intersection (`:185-195`).
- `getMovementForPushingEntityOutOfPortal` → `PortalCollisionHandler.getMovementForPushingEntityOutOfPortal(attemptedMove, pos, normal, entityBox)` (`:197-207`).
- `getThisSideCollisionExclusion`: thin bbox unioned with itself moved 10 blocks along −normal, as a `VoxelShape` (`Shapes.create`) (`:259-267`).
- `transformEntityActiveCollisionBox` → `CollisionHelper.clipBox(box, originPos, normal)` (`:269-274`).
- Culling: `canDoOuterFrustumCulling` true (`:216-220`); inner/outer funcs → `FrustumCuller.getFlatPortalInner/OuterFrustumCullingFunc` (`:222-241`).
- `shouldRenderInside`: box not fully behind the inner clipping plane (tests the farthest-along-normal corner) (`:243-257`).

### 2.17 `shape/SpecialFlatPortalShape` — arbitrary flat mesh (shape/SpecialFlatPortalShape.java, 287 LOC)

Holds `@NotNull Mesh2D mesh` in **normalized** coords (unit square maps to width/height); constructor enables triangle lookup (`:22-27`). Serializer type `"specialFlat"`, tag key `shape` = `mesh.toTag()` (`:29-53`). Mostly delegates rough operations to `RectangularPortalShape.INSTANCE` (bbox, rough distance, clipping planes, visibility, culling funcs, push-out, collision exclusion, active-collision box, shouldRenderInside).

Own logic: raytrace = rectangular rough hit, then `mesh.boxIntersects(nx±boxR, ny±boxR)` in normalized coords with `boxR = max(leniency, 1e-5)` (`:75-108`); `getFlipped` rebuilds the mesh negating every X (`:125-147`), `getReverse` = `getFlipped` (`:149-152`); `renderViewAreaMesh` emits each valid triangle via `ViewAreaRenderer.outputTriangle` (`:154-187`); `isLocalBoxInPortalProjection` = rectangular test AND mesh box intersection (`:202-222`); `canCollideWith` uses `portal.isInFrontOfPortal(eyePos)` (`:189-200`); `cloneIfNecessary` deep-copies the mesh (`:231-234`); `createDefault()` full-quad mesh (`:247-249`).

### 2.18 `shape/BoxPortalShape` — 3D box shape (shape/BoxPortalShape.java, 437 LOC)

Two singletons `FACING_OUTWARDS` / `FACING_INWARDS` (`:28-29`), field `facingOutwards`; serializer type `"box"`, key `facingOutwards` (`:33-56`). `isPlanar` false. Highlights:
- `getBoundingBox`: half-extents (+boxExpand) clamped to 32 when limited; 8 corners (`:65-92`).
- `roughDistanceToPortalShape`: per-axis distance-to-range, Euclidean combine (`:94-109`).
- Raytrace via `Helper.raytraceAABB(facingOutwards, ±w/2, ±h/2, ±t/2, localFrom, lineVec)` (`:111-130`).
- No clipping planes: both `getOuterClipping` and `getInnerClipping` return null (`:132-143`).
- `getFlipped`/`getReverse` toggle the facing (`:145-158`).
- `roughTestVisibility`: inside/outside test vs facing; always true under Iris (`:160-185`).
- `renderViewAreaMesh`: 6 quads; winding order controlled by swapping the two edge vectors per facing (`:187-240`).
- `canCollideWith`: bbox expanded by 2.0 intersects entity box (`:242-251`).
- `getMovementForPushingEntityOutOfPortal` (`:267-297`): work in local space; outwards → `PortalCollisionHandler.getOffsetForPushingBoxOutOfAABB`, inwards → `getOffsetForConfiningBoxInsideAABB`; returns global `localMove + offset`.
- `transformEntityActiveCollisionBox` (`:306-363`): clips the box by whichever of the 6 face planes the eye is beyond, via `CollisionHelper.clipBox`.
- Renderer-iteration specials, gated by `IPGlobal.boxPortalSpecialIteration` and outwards-facing only: `getModifiedVisibleSectionIterationOrigin` clamps the camera's `SectionPos` into the other side's section range (`:365-385`, `getInnerSectionRange` from the reverse shape's other-side bbox `:392-402`); `getInnerFrustumCullingFunc` culls sections whose center lies inside that range (`:408-436`).
- `shouldRenderInside` always true (`:387-390`).

### 2.19 `shape/PortalShapeSerialization` — shape (de)serializer registry (shape/PortalShapeSerialization.java, 58 LOC)

`Serializer<T extends PortalShape>(typeName, clazz, serializer, deserializer)` record (`:15-20`); two maps keyed by type name and class (`:22-26`); `addSerializer` (`:28-31`); `deserialize(tag)` reads `type` key, warns+null on unknown (`:33-41`); `serialize(shape)` looks up by class, stamps `type`, warns + empty tag on unknown (`:43-57`).

### 2.20 `util/PortalLocalXY` — plane-local 2D coordinate (util/PortalLocalXY.java, 45 LOC)

Record `(localX, localY)`; conversions from/to world positions and offsets against either a `Portal` (axisW/axisH dot products, `:19-23`) or a `UnilateralPortalState` (orientation conjugate rotate, `:25-28`); `getOffset`/`getPos` inverses (`:30-44`).

### 2.21 `util/PortalLocalXYNormalized` — normalized [0,1]² coordinate (util/PortalLocalXYNormalized.java, 81 LOC)

Record `(nx, ny)` in 0..1 (`:7-8`); same conversion set scaled by width/height with the 0.5 center offset (`:9-49`); plus `clamp()`, `snapToGrid(int)`, `isValid()`, `isCloseTo(other, maxDistance)`, `add`/`subtract` (`:51-80`). Used by portal manipulation UI/commands (outside slice).

---

## 3. Mechanisms

### 3.1 Geometry model

A portal is defined on its "this side" by: origin `O` = entity position; unit vectors `axisW`, `axisH` spanning the portal plane; `normal = axisW × axisH` (`Portal.java:482-487`); extents `width` (along axisW), `height` (along axisH), `thickness` (along normal, 3D shapes only). The "other side" is defined by destination `D` (`destination` field), the rotation transformation `R` (`rotation`, nullable ⇒ identity), and `scaling` `s`. The this-side orientation as a quaternion is `matrixToQuaternion(axisW, axisH, normal)` (`PortalManipulation.java:402-408`); the other-side orientation is derived, not stored: `otherSideOrientation = rotation · orientation · flipAxisW` (from the `computeDeltaTransformation` derivation, `PortalManipulation.java:497-499` — the 180°-about-axisH flip accounts for walking *through* the portal reversing the facing).

Point transformation (this-side world → other-side world): `P' = s·R·(P − O) + D` (`Portal.java:463-476`, `:1202-1208`). Inverse: `P = O + R⁻¹·(P' − D)/s` (`:1210-1226`). As a single matrix: `T(D)·S(s)·Rot(R)·T(−O)` (`:1403-1412`). Mirrors compose a reflection about the portal normal AFTER rotation: `v' = mirror(R·v)` where `mirror(v) = v − 2(v·n)n` (`Mirror.java:26-45`), and the matrix form inserts `.reflect(n, 0)` after translation-to-D (`Mirror.java:52-63`). `PortalState.transformVec` reproduces rotate→scale→mirror for snapshot-based teleport math (`PortalState.java:176-186`).

Velocity through a portal uses `transformLocalVec` (rotation+scale, no translation); when the pre-transform speed exceeds 15 blocks/tick, the transformed result is overridden to exactly magnitude 15 — `if (original.length() > 15) result = transformLocalVec(original).normalize().scale(15)` (`Portal.java:1074-1076`); the threshold tests the input and the override sets the output magnitude, so under scaling ≠ 1 this is not an output cap — plus a minecart escape boost (`Portal.java:1080-1082`). Gravity direction maps through `transformLocalVecNonScale` then snaps to the nearest `Direction` (`Portal.java:1655-1663`).

### 3.2 Sync model (server→client replication)

No `SynchedEntityData` (`Portal.java:227-230`). Both spawn and every subsequent update use one packet: `ImmPtlNetworking.PortalSyncPacket` (`imm_ptl:spawn_portal`, `network/ImmPtlNetworking.java:134-174`) carrying `(entityId, uuid, EntityType, dimensionIdInt, x, y, z, fullNbt)`.
- Spawn: `getAddEntityPacket(ServerEntity)` returns this packet (`Portal.java:897-902`), so vanilla's entity-tracker spawn path delivers it.
- Update: `reloadAndSyncToClient()` sends the same packet to trackers (`Portal.java:519-529`). Mutations are expected to set `reloadAndSyncNextTick` (directly or via `reloadPortal()`), flushed at the top of `tick()` (`Portal.java:937-941`) — coalescing multiple same-tick changes.
- Client handling (`network/ImmPtlNetworking.java:180-224`, outside slice but the contract matters): if the entity id already exists → `portal.acceptDataSync(pos, nbt)` (`Portal.java:1737-1749`: setPos + `readAdditionalSaveData` + start client default animation from the old state + fire accept-sync event); else construct the entity from the `EntityType`, read NBT, add to world, fire `CLIENT_PORTAL_SPAWN_EVENT`.
- Rationale (`Portal.java:510-518`): vanilla position packets quantize; portals need exact doubles for animation. Vanilla's periodic entity resync for portals is suppressed elsewhere (`MixinServerEntity`, referenced by that javadoc).
- Per-player visibility: `broadcastToPlayer` restricts tracking to `specificPlayerId` when set (`Portal.java:921-927`).

### 3.3 Tick & lifecycle

Server tick order (`Portal.java:929-958`): record last-tick state → flush pending sync → validity check (`isPortalValid`, §2.1) with auto-`remove(KILLED)` → `SERVER_PORTAL_TICK_SIGNAL` (drives `PortalExtension` cluster upkeep) → `animation.tick` → `super.tick()`. Client tick fires `CLIENT_PORTAL_TICK_SIGNAL` (drives `PortalExtension` client resolution + `PortalRenderInfo`).

Position changes and removal are observed via `MixinEntity_U` (`mixin/common/mc_util/MixinEntity_U.java:12-33`): an inject in `Entity.setPosRaw(DDD)` at the `EntityInLevelCallback.onMove()` call site → `ip_onEntityPositionUpdated()` → `updateCache()`; an inject at `Entity.setRemoved(RemovalReason)` RETURN → `ip_onRemoved` → `PORTAL_DISPOSE_SIGNAL` (which triggers `PortalRenderInfo` GL cleanup client-side). All geometry getters go through the cache fields; every setter calls `updateCache()`.

### 3.4 Bounding box discipline

`makeBoundingBox()` is overridden to derive the AABB from the shape + this-side state with expand 0.2 (`Portal.java:960-976`); `move()` is a no-op (`:982-985`). Bounding boxes are clamped (64 half-extent flat, 32 box-shape) for non-global portals because ticking code iterates chunks in the bbox (`Portal.java:968-971`, `RectangularPortalShape.java:50-53`, `BoxPortalShape.java:74-78`). `EndPortalEntity` opts out of limiting (`EndPortalEntity.java:332-337`). The `NULL_BOX` sentinel covers the not-yet-initialized window (axes null) and is error-logged if it survives to tick (`Portal.java:931-933`).

### 3.5 Cluster mechanism (flipped / reverse / parallel)

Definitions relative to primary portal `p` (from the find/rectify code, `PortalManipulation.java:438-478`, `PortalExtension.java:325-447`):
- **flipped**: same origin & dest, `axisW → −axisW` (other face of the same portal surface), same rotation & scale, shape `getFlipped()`.
- **reverse**: lives at `p`'s dest, leads back to `p`'s origin; `axisW = R·(−axisW)`, `axisH = R·axisH` (transformed by `p`'s rotation, non-scale); `scaling = 1/s`; `rotation = R⁻¹`; extents × `s`; shape `getReverse()`; `defaultAnimation.inverseScale = true` (`PortalExtension.java:396`).
- **parallel**: reverse of the flipped — at dest, `axisW = R·axisW`, `axisH = R·axisH`, `scaling = 1/s`, `rotation = R⁻¹`, extents × `s`, shape `cloneIfNecessary()`, `inverseScale = true`.

Links are persisted as UUIDs inside each portal's NBT (via the READ/WRITE signals) and re-resolved each tick from the world entity lookup; missing reverse/parallel targets are only unlinked when the destination chunk is verifiably loaded (`PortalExtension.java:195-232`), so unloaded chunks don't break clusters. When ids are absent, geometric discovery runs each tick (position ε 0.1, dot 0.9 tests). `rectifyClusterPortals` makes the primary authoritative: it teleports the linked portal entities to the correct dimension/position (`ServerTeleportationManager.teleportRegularEntityTo`) and rewrites their geometry from the primary's, then syncs. `Portal.reloadPortal()` is the documented mutation entry point that keeps a cluster coherent (`Portal.java:1783-1796`). Animation is unified per-cluster: `getAnimationView`/`getAnimationHolder` pick whichever cluster member owns an animation driver and tag it with the `IntraClusterRelation` (`Portal.java:1828-1861`, `:1907-1934`).

### 3.6 Shape strategy & serialization

`Portal` never does shape-specific math itself; every geometric query (bbox, raytrace, clipping planes, visibility, collision projection, push-out, culling functions) routes through `getPortalShape()` with `getThisSideState()`/`getOtherSideState()` as arguments, making shapes stateless singletons except `SpecialFlatPortalShape` (owns a `Mesh2D`; hence `cloneIfNecessary`). Serialization is a name↔class registry populated by each shape's `init()` at mod init (`IPModMain.java:75-77`): `"rectangular"`, `"specialFlat"`, `"box"`. Unknown shape tags degrade to rectangular with an error log (`Portal.java:246-249`). The default raytrace pipeline converts to portal-local coordinates (X=axisW, Y=axisH, Z=normal) via `UnilateralPortalState.transformGlobalToLocal`, runs shape-local intersection, and maps hit+normal back (`shape/PortalShape.java:43-64`).

### 3.7 Teleport gating chain

`canTeleportEntity` (`Portal.java:697-726`) is the composite filter: `teleportable` flag → portals never teleport portals → `specificPlayerId` semantics (null = everyone; NIL_UUID = non-players only; other = exactly that player) → platform veto `O_O.allowTeleportingEntity` → per-entity veto `ImmPtlEntityExtension.imm_ptl_canTeleportThroughPortal` (mixin-injected on `Entity`; replaces vanilla `canChangeDimensions` which would block ridden entities). `canCollideWithEntity` and `isInteractableBy` layer on top. Subclasses override for policy (Mirror: never; EndPortal: no arrows + horizontal range gate).

### 3.8 Portal-aware ray tracing

Two levels: (a) `Portal.rayTrace`/`generalRayTrace` — one portal's surface via its shape (leniency 0.001; used by crossing detection `isMovedThroughPortal`, `Portal.java:1231-1256`); (b) `PortalUtils.portalAwareRayTraceFull` — world-level recursive trace that races the nearest portal hit against `Level.clip` block hit, portal winning ties by 0.0001, then re-enters from the transformed point in the destination world with transformed direction and decremented distance budget, up to 5 portal layers (`PortalUtils.java:155-248`). Player-initiated traces filter portals by `isInteractableBy`; non-player by `isVisible` (`PortalUtils.java:169-179`).

### 3.9 Occlusion-query visibility prediction (client)

`PortalRenderInfo.renderAndDecideVisibility` (`PortalRenderInfo.java:212-257`): render the portal's view-area mesh inside a GL any-samples-passed query each frame, but return *last* frame's query result to avoid a GPU sync stall; keyed per rendering-description chain (nested portals have distinct keys). Blocking fetch ⇔ (no last-frame query — the first frame of a chain, `PortalRenderInfo.java:246-251`) OR (the last-frame result was invisible AND (misprediction is frequent (>5 lifetime, or two within 30s — `:188-196`) OR `queryStallCounter <= 3`)) — `if (!lastFrameVisible && noPredict)`, `:228-239`. A last-frame-visible result is used as the decision regardless of the noPredict conditions (`:241-244`). Query objects are pooled (`GlQueryObject.acquire/return`); frame rotation and stale-entry eviction happen on `RenderStates.frameIndex` change (`:154-173`); disposal happens on portal removal and via `Cleaner` posting to the pre-render task list (`:89-96`, `:128-139`).

### 3.10 Legacy data upgrade

Old worlds store flat mesh shapes as `specialShape` (raw triangle list, optionally non-normalized) — upgraded on read into `SpecialFlatPortalShape` (`Portal.java:254-284`, `GeometryPortalShape.java`). Old cluster self-reference bugs are corrected in `updateClusterStatusServer` (`PortalExtension.java:260-276`). `PortalState`'s 9-arg constructor keeps thickness-0/mirror-false compatibility (`PortalState.java:31-48`).

---

## 4. MC API touchpoint list (deduplicated; anything that can break across versions)

**Entity lifecycle / base class (`net.minecraft.world.entity.Entity`):**
- Constructor `Entity(EntityType<?>, Level)` (`Portal.java:221-225`).
- Overridden protected/lifecycle members: `defineSynchedData(SynchedEntityData.Builder)` (`Portal.java:228`; `LoadingIndicatorEntity.java:109` — builder-parameter form, 1.20.5+), `readAdditionalSaveData(CompoundTag)` / `addAdditionalSaveData(CompoundTag)` (`Portal.java:233,366`), `tick()` + `super.tick()` (`Portal.java:930,957`), `makeBoundingBox()` no-arg override (`Portal.java:961`), `getBoundingBox()` override (`Portal.java:573`), `refreshDimensions()` override (`Portal.java:1571`), `move(MoverType, Vec3)` override (`Portal.java:983`), `getAddEntityPacket(ServerEntity)` returning `Packet<ClientGamePacketListener>` (`Portal.java:897-902` — signature gained the `ServerEntity` param in 1.20.2), `broadcastToPlayer(ServerPlayer)` override (`Portal.java:922`).
- Called members: `position()`, `setPos(Vec3)`, `setPos(x,y,z)`, `setPosRaw` (`PortalManipulation.java:391`), `getX/getY/getZ`, `getId`, `getUUID`, `getType`, `level()`, `getServer()` (`Portal.java:542,1311`), `remove(RemovalReason)`, `setRemoved(RemovalReason)` (`Portal.java:1764`), `unsetRemoved()` (protected, exposed via `myUnsetRemoved` `Portal.java:1566-1568`), `isRemoved()` (`PortalExtension.java:158`), `tickCount` (`LoadingIndicatorEntity.java:69,83`), `getEntityData()` (`LoadingIndicatorEntity.java:131`), `getLookAngle()`, `getEyePosition(float)`/`getEyePosition()`, `getViewVector(float)` (`PortalUtils.java:75-76,93-94`), `getDeltaMovement`/`setDeltaMovement` (`EndPortalEntity.java:219-223`), `getBoundingBox()` (entity arg, `BoxPortalShape.java:273`), `Entity.RemovalReason.KILLED`.
- Mixin injection targets (wiring, not this slice's code but its lifeline): `Entity.setPosRaw(DDD)` at `EntityInLevelCallback.onMove()`; `Entity.setRemoved(RemovalReason)` RETURN (`MixinEntity_U.java:12-33`).

**Entity typing / registration:**
- `EntityType<T>`, `EntityType.EntityFactory<T>`, `EntityType.create(Level)` (`PortalManipulation.java:91,134,161`; `BreakableMirror.java:173`; `EndPortalEntity.java:150`), `MobCategory.MISC`, `EntityDimensions.fixed(w,h)`.
- Fabric `FabricEntityTypeBuilder`: `.create(...).dimensions(...).fireImmune().trackRangeBlocks(96).trackedUpdateRate(20).forceTrackedVelocityUpdates(true).build()` (`Portal.java:97-107`); `.trackable(96, 20)` (`LoadingIndicatorEntity.java:26-32`).
- `Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type)` (`platform_specific/IPModEntry.java:18-20`), `BuiltInRegistries.BLOCK` (`IPModEntry.java:22`).
- Fabric `EntityRendererRegistry.register(entityType, provider)` (`platform_specific/IPModEntryClient.java:41-61`).

**Entity data sync (`LoadingIndicatorEntity` only):** `SynchedEntityData.defineId(Class, serializer)`, `SynchedEntityData.Builder.define`, `EntityDataAccessor<T>`, `EntityDataSerializers.COMPONENT`, `EntityDataSerializers.BLOCK_POS` (`LoadingIndicatorEntity.java:34-42,108-113`).

**Networking:**
- `Packet<ClientGamePacketListener>` raw-cast from a Fabric payload packet (`Portal.java:904-919`) — depends on Fabric's `ServerPlayNetworking.createS2CPacket(CustomPacketPayload)` returning a vanilla `Packet`.
- `ServerEntity` (spawn-packet parameter type).
- Indirect but contract-critical: `CustomPacketPayload`, `StreamCodec`, `RegistryFriendlyByteBuf`, `ByteBufCodecs.registry(Registries.ENTITY_TYPE)`, `PayloadTypeRegistry`, `ClientPlayNetworking`/`ServerPlayNetworking` receivers (`network/ImmPtlNetworking.java:134-267`).

**NBT:** `CompoundTag` (`getDouble/putDouble`, `getBoolean/putBoolean`, `getString/putString`, `getFloat` (numeric coercion on double tags — `Portal.java:290-296`), `getCompound/put`, `getList(key, type)` with type ids 6 (double) and 8 (string) (`Portal.java:264,336`), `contains`, `hasUUID/getUUID/putUUID`, `getInt/putInt`, `getAllKeys` (`Portal.java:1772`)), `ListTag`, `StringTag.valueOf/getAsString`.

**Level / dimension / chunk:**
- `Level`: `isClientSide()`/`isClientSide` field (`BreakableMirror.java:96`), `dimension()`, `getGameTime()`, `getBlockState`, `getRandom()`, `addParticle` (`LoadingIndicatorEntity.java:100`), `clip(ClipContext)` (`PortalUtils.java:188`), `addFreshEntity` (`BreakableMirror.java:211`, `EndPortalEntity.java:125`).
- `ServerLevel`: `getServer().getLevel(ResourceKey<Level>)` (`Portal.java:1002`), `getWorldBorder().isWithinBounds(BlockPos)` (`Portal.java:1007`), `ServerLevel.END_SPAWN_POINT` (`EndPortalEntity.java:71,105`), `getDragonFight()` (`EndPortalEntity.java:91`).
- `ResourceKey<Level>`, `Level.END`.
- `MinecraftServer.getLevel(...)`.
- `ChunkPos` (`Portal.java:1868`), chunk-load query via `McHelper.isServerChunkFullyLoaded` (wraps vanilla chunk source — migration-sensitive helper).
- `SectionPos.of(Vec3/BlockPos/int,int,int)`, `.x()/.y()/.z()` (`BoxPortalShape.java:378-401`).

**Math/geometry types:** `Vec3` (add/subtract/scale/dot/cross/normalize/length/lengthSqr/distanceTo/distanceToSqr/multiply/atLowerCornerOf/atCenterOf/ZERO), `AABB` (ctor, move, intersects, minmax (`RectangularPortalShape.java:265`), getCenter, bounds), `Mth.clamp/lerp`, `Direction` (getNearest, getNormal, getOpposite, getAxis, values, `Direction.Axis`), `BlockPos` (containing, ZERO, relative, getBottomCenter (`EndPortalEntity.java:105`)), `VoxelShape`, `Shapes.create(AABB)`, `Tuple`, `Util.NIL_UUID`.

**JOML (via MC):** `Matrix4d` (translation/scale/rotate/translate/reflect/set/sub/m00…m33), `Matrix4f` (camera transformation type in `PortalLike.java:70`), `Matrix3d.reflect/rotate/mul`, `Quaterniond.setFromNormalized` (`Mirror.java:75-81`).

**Client-only:**
- `Minecraft.getInstance()`: `.player` (`EndPortalEntity.java:208`; `LoadingIndicatorEntity.java:70`), `.gui` + `Gui.setOverlayMessage(Component, boolean)` (`LoadingIndicatorEntity.java:151-154`), `.getProfiler()` returning `ProfilerFiller` with push/pop (`PortalRenderInfo.java:213,236-249`) — **26.2: profiler access moved off Minecraft; must be re-mapped**.
- `LocalPlayer` (movement damping, `EndPortalEntity.java:208-239`).
- `ParticleTypes.PORTAL` (`LoadingIndicatorEntity.java:101`).
- `Component.literal` (`LoadingIndicatorEntity.java:110`).
- Raw GL occlusion queries via IP's own `GlQueryObject`/`QueryManager` (any-samples-passed; `PortalRenderInfo.java:223-254`) — vanilla-independent GL usage, but sits on the render thread and frame index.
- NOTE: `renderViewAreaMesh` outputs to IP's `TriangleConsumer` — no `MultiBufferSource`/`VertexConsumer` in this slice; the buffer-model break (26.2 submit→prepare→execute) lands in `ViewAreaRenderer`/`PortalRenderer` (render slice), which this slice only calls into at `RectangularPortalShape.java:155-157`, `SpecialFlatPortalShape.java:181-184`, `BoxPortalShape.java:199-239`, `Portal.java:1579`.

**Block API (`PortalPlaceholderBlock`):** `Block` ctor + `BlockBehaviour.Properties.of().noCollission().sound(SoundType.GLASS).strength(1.0f, 0).noOcclusion().noLootTable().lightLevel(fn)` (`:57-65`), `StateDefinition.Builder.add`, `registerDefaultState`/`getStateDefinition().any()`, `BlockStateProperties.AXIS`/`EnumProperty`, `Block.box`, `getShape(BlockState, BlockGetter, BlockPos, CollisionContext)`, **`updateShape(BlockState, LevelReader, ScheduledTickAccess, BlockPos, Direction, BlockPos, BlockState, RandomSource)`** (1.21.2+ signature — `PortalPlaceholderBlock.java:96-122`), `propagatesSkylightDown(BlockState)` (single-arg form, `:135-140`), `getRenderShape`/`RenderShape.INVISIBLE`, `getShadeBrightness(BlockState, BlockGetter, BlockPos)`, `Blocks.GLASS/GLASS_PANE`, `StainedGlassBlock`/`StainedGlassPaneBlock`, `BlockState.getCollisionShape/getBlock/isAir/getValue/setValue`.

**Raytrace / interaction:** `ClipContext(Vec3, Vec3, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, Entity)`, `BlockHitResult` (getDirection/getBlockPos/getLocation/getType), `HitResult.Type.BLOCK`.

**Gameplay (EndPortalEntity):** `MobEffectInstance`/`MobEffects.SLOW_FALLING`, `LivingEntity.addEffect`, `ServerPlayer.gameMode.getGameModeForPlayer()`, `GameType.CREATIVE`, `EquipmentSlot.CHEST`, `Player.getItemBySlot(...).getItem()`, `Items.ELYTRA`, `Arrow`, `AbstractMinecart` (`Portal.java:1080`), `EndDragonFight` (+ `IEEndDragonFight` mixin duck: `ip_getNeedsStateScanning`/`ip_scanState` — mixin into vanilla internals), `EndPlatformFeature.createEndPlatform(ServerLevel, BlockPos, boolean)` (`EndPortalEntity.java:104-107`).

**Misc:** `RandomSource` (`LoadingIndicatorEntity.java:87`), `net.minecraft.Util.NIL_UUID` (`Portal.java:130,713`), `com.mojang.datafixers.util.Pair` (`PortalUtils.java:31`), Fabric `Event` type (`Portal.java:89-219` — event objects built by IP's `Helper`, but typed as `net.fabricmc.fabric.api.event.Event`).

---

## 5. Registration & wiring

**Entity types.** Each class self-instantiates its `EntityType` in a static field via `Portal.createPortalEntityType` (`Portal.java:87`, `Mirror.java:13`, `BreakableMirror.java:30-31`, `EndPortalEntity.java:43-44`) or its own builder (`LoadingIndicatorEntity.java:26-32`). Registration happens at Fabric mod-init: `IPModEntry.onInitialize` → `IPModMain.registerEntityTypes((id, type) -> Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type))` (`platform_specific/IPModEntry.java:18-20`). IDs from this slice: `immersive_portals:portal`, `immersive_portals:end_portal`, `immersive_portals:mirror`, `immersive_portals:breakable_mirror` (`IPModMain.java:162-187`); the same function also registers the out-of-slice portal types (nether_portal_new, global_tracked_portal, …). The placeholder block registers as `immersive_portals:nether_portal_block` (`IPModMain.java:155-160`).

**Renderers (client).** `IPModEntryClient.initPortalRenderers()` registers `PortalEntityRenderer` for Portal, EndPortalEntity, Mirror, BreakableMirror (and out-of-slice portal types) and `LoadingIndicatorRenderer` for the indicator, via Fabric `EntityRendererRegistry` (`platform_specific/IPModEntryClient.java:38-62`).

**Init hooks.** `IPModMain.init()` calls `RectangularPortalShape.init()`, `SpecialFlatPortalShape.init()`, `BoxPortalShape.init()` (shape serializer registration, `IPModMain.java:75-77`) and `PortalExtension.init()` (`IPModMain.java:91`); `IPModMainClient.init()` calls `PortalRenderInfo.init()` (`IPModMainClient.java:95`). Both extension and render-info subscribe to `Portal`'s static events rather than being called by `Portal` directly — the events are the slice's plug-in bus.

**Ticking.** Portals tick as ordinary in-world entities (vanilla entity ticking); there is no custom ticker. Everything per-tick beyond the entity itself rides the `CLIENT_/SERVER_PORTAL_TICK_SIGNAL` invoked inside `Portal.tick()` (`Portal.java:943-953`). Global portals do NOT tick (never added to a world — `Portal.java:80-82`, `:968-971`); they are managed by `GlobalPortalStorage` (out of slice), which reconstructs them from NBT via `BuiltInRegistries.ENTITY_TYPE.get(id)` + `entityType.create(world)` (`global_portals/GlobalPortalStorage.java:249-254`).

**Position/removal hooks.** `MixinEntity_U` (mixin on vanilla `Entity`) invokes `IPEntityEventListenableEntity.ip_onEntityPositionUpdated` inside `setPosRaw` (at the `EntityInLevelCallback.onMove()` call) and `ip_onRemoved` at `setRemoved` RETURN (`mixin/common/mc_util/MixinEntity_U.java:12-33`); `Portal` implements that interface to invalidate caches on movement and to fire the dispose signal (`Portal.java:450-458`). The interface deliberately avoids vanilla `EntityChangeListener` ("does not use EntityChangeListener to avoid messing with vanilla mechanics", `mc_utils/IPEntityEventListenableEntity.java:5`).

**Network wiring.** `ImmPtlNetworking.init()` registers `PortalSyncPacket` play-S2C (`network/ImmPtlNetworking.java:247-249`); `initClient()` registers the client receiver (`:263-266`). The packet handler either updates an existing portal (`acceptDataSync`) or constructs + adds it, firing `CLIENT_PORTAL_SPAWN_EVENT` (`:202,224`).

**Spawning portals.** Server code paths in this slice spawn via `McHelper.spawnServerEntity` (`PortalManipulation.java:82,127,320-327`; `EndPortalEntity.java:193`) or plain `world.addFreshEntity` (`BreakableMirror.java:211`; `EndPortalEntity.java:125`).

---

## Notes for the 26.2 mapping stage (facts observed, not proposals)

1. **No immediate-render usage in this slice.** View-area geometry is produced as raw triangles into `TriangleConsumer`; all buffer/pipeline interaction is behind `ViewAreaRenderer`, `PortalRenderer`, `FrustumCuller` (render slice). The 26.2 submit→prepare→execute break therefore does not restructure this slice's code, only its render-slice callees.
2. `Minecraft.getInstance().getProfiler()` (`PortalRenderInfo.java:213`) is a known 26.2 removal (profiler access relocated).
3. `getAddEntityPacket(ServerEntity)` override + suppression of vanilla position resync (`MixinServerEntity`) are entity-tracker touchpoints that must be re-verified against 26.2's `ServerEntity`.
4. `defineSynchedData(SynchedEntityData.Builder)` builder-form and `EntityDataSerializers.COMPONENT/BLOCK_POS` must be re-checked in 26.2 (`LoadingIndicatorEntity` only).
5. NBT `contains`/`getFloat`-on-double coercion semantics (`Portal.java:290-296`) depend on `CompoundTag` behavior — 26.2 moved NBT reads toward Optional-style getters; the exact legacy coercion must be preserved.
6. `PortalPlaceholderBlock.updateShape`'s 1.21.2+ signature (`LevelReader, ScheduledTickAccess, RandomSource`) is close to but must be diffed against 26.2's.
7. `FabricEntityTypeBuilder` is deprecated upstream in favor of `EntityType.Builder` extensions; 26.2 Fabric API surface must be confirmed for `forceTrackedVelocityUpdates`/`trackRangeBlocks`/`trackedUpdateRate`.
