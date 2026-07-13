# IP Subsystem Inventory: Teleportation managers + collision

**Slice owner:** `qouteall/imm_ptl/core/teleportation/` (4 files) + `qouteall/imm_ptl/core/collision/` (3 files), plus the collision mixin packages read for wiring context (`mixin/common/collision/`, `mixin/client/collisions/`).
**IP source root (ground truth, MC 1.21.3, Mojang mappings):** `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`
All `file:line` citations below are relative to that root unless a full path is given.

---

## 1. Overview

This slice is the **entity relocation engine** of Immersive Portals: it decides *when* an entity has crossed a portal plane, *moves* it (same-dimension re-position or cross-dimension re-home without any loading screen), and *fixes up everything the move perturbs* — velocity, rotation, gravity, scale, vehicle/passenger trees, entity tracking, and the network-level position-sync handshake. The design is **client-first for players**: the client detects the crossing per-frame against its render-time eye position, teleports its own `LocalPlayer` immediately (including swapping `Minecraft.level` to a pre-loaded secondary `ClientLevel`), then notifies the server with a C2S `TeleportPacket`; the server validates the claim and re-homes the `ServerPlayer` *without ever sending a vanilla position-sync packet back* (it just calls `connection.resetPosition()`). Non-player entities are the opposite: **server-first**, driven from the portal entity's tick. There is no vanilla-style portal cooldown anywhere in this slice; duplicate-teleport suppression is done with per-entity time valves.

The collision half makes the portal surface behave as a *window* for physics: an entity overlapping a portal gets a per-entity `PortalCollisionHandler` (attached to `Entity` by mixin) that replaces vanilla `Entity.collide` with a two-world computation — this-side block shapes with the portal's "exclusion" region cut away, plus the destination-side shapes transformed back through the portal (rotation/scale/gravity aware, recursive through chained portals up to 5 layers). This is what lets an entity stand half-in/half-out of a portal without clipping or suffocating. The teleportation managers and the collision handler are tightly coupled: teleport detection uses the colliding-portal state, and every teleport ends by rebuilding the entity's colliding-portal set (`PortalCollisionHandler.updateCollidingPortalAfterTeleportation`).

---

## 2. Class-by-class inventory

### 2.1 `ClientTeleportationManager` — client, ~721 LOC
`imm_ptl/core/teleportation/ClientTeleportationManager.java`, `@Environment(EnvType.CLIENT)`, all-static.

**Responsibility:** per-frame + per-tick detection of the local player's portal crossing; executing the client-side teleport (world swap, position, velocity, rotation, scale, vehicle); sending the C2S teleport packet; the client-side force-teleport used by the position-sync fallback.

**Key public API:**
- `static void init()` — registers the tick hook (ClientTeleportationManager.java:84-93).
- `static void manageTeleportation(boolean isTicking)` — the entry point, called once per client tick and once per frame (ClientTeleportationManager.java:121).
- `static void acceptSynchronizationDataFromServer(ResourceKey<Level> dim, Vec3 pos, boolean forceAccept)` — **DEAD CODE in this IP version: zero callers in the whole `src` tree** (definition ClientTeleportationManager.java:102; verified by repo-wide grep). Do not port as a live path; note it existed for server→client sync.
- `static void forceTeleportPlayer(ResourceKey<Level> toDimension, Vec3 destination)` (ClientTeleportationManager.java:432) — non-portal forced move; used by `MixinClientPacketListener` when a server position packet is tagged with a different dimension (mixin/client/sync/MixinClientPacketListener.java:117) and by the client debug command.
- `static void changePlayerDimension(LocalPlayer, ClientLevel from, ClientLevel to, Vec3 newEyePos)` (ClientTeleportationManager.java:458) — the client dimension-swap primitive.
- `static void moveClientEntityAcrossDimension(Entity, ClientLevel, Vec3)` (ClientTeleportationManager.java:552) — foot-pos variant for vehicles / RequiemCompat.
- `static boolean isTeleportingFrequently()` (ClientTeleportationManager.java:427) — 100-tick window; used by `MixinScreenEffectRenderer` to suppress the in-wall overlay (mixin/client/render/MixinScreenEffectRenderer.java:38).
- `static void disableTeleportFor(int ticks)` (ClientTeleportationManager.java:566).
- `static Vec3 getPlayerEyePos(float partialTick)` (ClientTeleportationManager.java:316) — used by `CrossPortalViewRendering`.
- Inner `RemoteCallables.updateEntityPos(dim, entityId, pos)` (ClientTeleportationManager.java:697-719) — S2C RPC target invoked by the server after a regular-entity teleport to kill client-side position interpolation; sets pos, `lerpTo(pos, yRot, xRot, 0)`, sets pos again (comment: "both of them are important for Minecart").

**State:** `tickTimeForTeleportation` (monotonic client tick counter), `lastTeleportGameTime`, `teleportTickTimeLimit` (cooldown), `lastPlayerEyePos` (the previous frame's eye pos — the detection segment start; nulled on cleanup and force-teleport), `teleportationCounter` (frame/tick invocation counter matched against portal animation state counters), debug flags `isTeleportingTick/isTeleportingFrame/isTicking`, `teleportLimitPerFrame = 3` (ClientTeleportationManager.java:67-82; note the combo loop's bound is *inclusive*, so up to 4 `tryTeleport` calls per invocation — see 3.1 step 4).

**Depends on (IP):** `TeleportationUtil`, `PortalCollisionHandler`, `CollisionHelper`, `ClientWorldLoader` (secondary `ClientLevel`s + per-dim renderers/lightmaps), `TransformationManager` (rotation), `ScaleUtils`, `GravityChangerInterface`, `PortalExtension` (`adjustPositionAfterTeleport`, `motionAffinity`), `ClientPortalAnimationManagement`/`StableClientTimer`, `RenderStates`, `MyGameRenderer.vanillaTerrainSetupOverride`, `FogRendererContext`, `PacketRedirectionClient`, `O_O`, ducks `IEClientPlayNetworkHandler/IEEntity/IEAbstractClientPlayer/IEGameRenderer/IEMinecraftClient/IEParticleManager`.

**Vanilla MC touched:** `Minecraft` (level/worldRenderer/particleEngine/BER-dispatcher swap), `ClientLevel.addEntity/removeEntity`, `LocalPlayer` (pos, rotation, `connection.send`), `Entity` last-tick pos fields, `ClientPlayNetworking.createC2SPacket` (Fabric).

### 2.2 `ServerTeleportationManager` — server, ~846 LOC
`imm_ptl/core/teleportation/ServerTeleportationManager.java`. **Per-server instance**, not static: `ServerTeleportationManager.of(server)` → `IPPerServerInfo.of(server).teleportationManager` (ServerTeleportationManager.java:61-63; IPPerServerInfo.java:19).

**Responsibility:** validating + executing the player teleport claimed by the client; server-driven teleportation of all non-player entities; the vehicle-across-dimension path; force-teleports; mob-chaser handoff; evacuating players from removed dimensions.

**Key public API:**
- `static ServerTeleportationManager of(MinecraftServer)` (ServerTeleportationManager.java:61).
- `static void init()` (ServerTeleportationManager.java:65-82) — registers `ServerTickEvents.END_SERVER_TICK` (clears the per-tick `teleportingEntities` set + runs global-portal scan), `Portal.SERVER_PORTAL_TICK_SIGNAL` (per-portal-tick entity scan → `startTeleportingRegularEntity`), and `DimensionAPI.SERVER_PRE_REMOVE_DIMENSION_EVENT` (evacuation).
- `void onPlayerTeleportedInClient(ServerPlayer, ResourceKey<Level> dimensionBefore, Vec3 eyePosBeforeTeleportation, UUID portalId)` (ServerTeleportationManager.java:160) — the C2S packet handler body.
- `static boolean shouldEntityTeleport(Portal, Entity)` (ServerTeleportationManager.java:94-106) — eye-pos segment (`getEyePosition(0)` → `getEyePosition(1)`, plus world velocity for `Projectile`s) ray-traced against the portal shape.
- `void startTeleportingRegularEntity(Portal, Entity)` (ServerTeleportationManager.java:108) — guards + defers actual teleport to the server task list.
- `void teleportPlayer(ServerPlayer, ResourceKey<Level> dimTo, Vec3 newEyePos)` (ServerTeleportationManager.java:329).
- `void forceTeleportPlayer(ServerPlayer, dim, Vec3 newPos[, boolean sendPacket])` (ServerTeleportationManager.java:360, 368) — the only path that sends a vanilla `connection.teleport(...)` position packet; also calls `ImmPtlChunkTracking.immediatelyUpdateForPlayer` (ServerTeleportationManager.java:415).
- `Entity changeEntityDimension(Entity, dim, Vec3 newEyePos, boolean recreateEntity)` (ServerTeleportationManager.java:615) — non-player dimension move, optionally recreating the entity object.
- `Entity teleportVehicleAcrossDimensions(Entity, dim, Vec3 newEyePos)` (ServerTeleportationManager.java:680) — always recreates; suppresses the remove-entity packet via `teleportingEntities`.
- `static Entity teleportEntityGeneral(Entity, Vec3, ServerLevel)` / `static <E> E teleportRegularEntityTo(E, dim, Vec3)` (ServerTeleportationManager.java:727, 740) — API-level general teleport (used by `PortalAPI`, ender-pearl mixin, commands).
- `boolean isTeleporting(Entity)` (ServerTeleportationManager.java:503) — read by `MixinChunkMap_E` to suppress tracker teardown.
- `boolean isJustTeleported(Entity, long valveTickTime)` (ServerTeleportationManager.java:721).
- `static boolean canPlayerReachPos(ServerPlayer, dim, Vec3)` / `canPlayerReachBlockEntity` (ServerTeleportationManager.java:298, 316) — cross-portal reach checks used by interaction validation elsewhere.
- `void recordLastPosition(...)` + public field `lastPosition` (`WeakHashMap<ServerPlayer, WithDim<Vec3>>`, ServerTeleportationManager.java:59) — consumed by `PortalCommand` "tpme back" style features (commands/PortalCommand.java:1451).

**State:** `teleportingEntities` (`HashSet<Entity>`, cleared every server tick at ServerTeleportationManager.java:88-92), `lastTeleportGameTime` (`WeakHashMap<Entity, Long>` — the dedup valve), `isFiringMyChangeDimensionEvent` (public flag, set nowhere in this file — external mixin coordination), `lastPosition`.

**Depends on (IP):** `TeleportationUtil`, `PortalCollisionHandler`, `ScaleUtils`, `GravityChangerInterface`, `ImmPtlChunkTracking`, `GlobalPortalStorage`, `ServerTaskList`/`MyTaskList`, `McRemoteProcedureCall` (q_misc_util RPC), `DimensionAPI` (dimlib), `IPConfig.serverTeleportLogging`, `O_O`, ducks `IEEntity/IEServerPlayNetworkHandler/IEServerPlayerEntity`.

**Vanilla MC touched:** `ServerLevel.removePlayerImmediately/addDuringTeleport/getEntity(UUID)/getAllEntities/getEntitiesOfClass`, `ServerPlayer.setServerLevel`, `ServerGamePacketListenerImpl.resetPosition/teleport`, `EntityType.create(Level)`, `Entity.restoreFrom/setId/remove/unRide/setYHeadRot/getPassengers/startRiding/canChangeDimensions(Level,Level)`, `Mob` navigation (`getNavigation().createPath/moveTo`, `getMoveControl().setWantedPosition`, `setTarget`), `MinecraftServer.getLevel/getAllLevels/getPlayerList`.

### 2.3 `TeleportationUtil` — common (used by both sides), ~311 LOC
`imm_ptl/core/teleportation/TeleportationUtil.java`. Pure math, no MC state mutation except via `McHelper` velocity setters.

**Responsibility:** the crossing-detection math (static and animated portals) and the velocity transform.

**Key API:**
- `record Teleportation(...)` (TeleportationUtil.java:61-76) — the full result bundle: `isDynamic`, portal, last/current world eye pos, local eye pos, local collision point, `tOfCollision`, world collision point + surface normal, the interpolated `collidingPortalState`, last/this frame + last/this tick `PortalState`s, `PortalPointVelocity`, `teleportationCheckpoint`, and the **new last-tick/this-tick eye positions** the entity must be placed at.
- `record PortalPointVelocity(Vec3 thisSidePointVelocity, Vec3 otherSidePointVelocity)` with `ZERO` (TeleportationUtil.java:78-83).
- `static Teleportation checkStaticTeleportation(Portal, Vec3 lastPos, Vec3 currentPos, Vec3 lastTickEyePos, Vec3 thisTickEyePos)` (TeleportationUtil.java:97).
- `static Teleportation checkDynamicTeleportation(Portal, PortalState lastFrame, PortalState currentFrame, Vec3 lastFrameEyePos, Vec3 currentFrameEyePos, PortalState lastTick, PortalState thisTick, Vec3 lastTickEyePos, Vec3 thisTickEyePos, float partialTicks)` (TeleportationUtil.java:146).
- `static PortalPointVelocity getPortalPointVelocity(PortalState lastTick, PortalState thisTick, Vec3 localPos)` (TeleportationUtil.java:31).
- `static void transformEntityVelocity(Portal, Entity, PortalPointVelocity, Vec3 oldEntityPos)` (TeleportationUtil.java:50-59) — velocity relative to the moving portal point, transformed, plus other-side point velocity.
- Deprecated `getConservativePortalPointVelocity` (TeleportationUtil.java:278) — keep for fidelity, unused.

**Depends on:** `Portal`, `PortalState`, `UnilateralPortalState`, `PortalShape.raytracePortalShapeByLocalPos`, q_misc_util `RayTraceResult`.

### 2.4 `CrossPortalSound` — client, ~105 LOC
`imm_ptl/core/teleportation/CrossPortalSound.java`, `@Environment(EnvType.CLIENT)`.

**Responsibility:** when a sound plays in a non-player world (or far away), find the portal nearest the sound whose destination is the player's dimension, and re-emit the sound at the portal's exit point with distance-attenuated volume.
- `static SimpleSoundInstance createCrossPortalSound(ClientLevel soundWorld, SoundEvent, SoundSource, Vec3 soundPos, float vol, float pitch, long seed)` (CrossPortalSound.java:31): sound radius `min(64, max(16*vol, 16))` (CrossPortalSound.java:48); portal search radius `IPGlobal.maxNormalPortalRadius` filtered to `portal.getDestDim() == RenderStates.originalPlayerDimension` (CrossPortalSound.java:54-57); volume = `max(0, vol − distToEntryPoint/16) * max(0, 1 − distFromExitToPlayer/16)` (CrossPortalSound.java:69-74).
- `static boolean isPlayerWorld(ClientLevel)` (CrossPortalSound.java:26) — compares with `RenderStates.originalPlayerDimension`.

**Wired from** `mixin/client/sound/MixinClientLevel_Sound.onPlaySound` (@Inject HEAD into `ClientLevel.playSound`, cancellable): if the sound is not near the player, replace it with the cross-portal instance or cancel it entirely when the sound world isn't the player's world (MixinClientLevel_Sound.java:28-64). Gate: `IPGlobal.enableCrossPortalSound` (IPGlobal.java:126).

**Vanilla MC touched:** `SimpleSoundInstance` **8-arg** constructor `(SoundEvent, SoundSource, float, float, RandomSource, double, double, double)` with `RandomSource.create(seed)` (CrossPortalSound.java:76-85), `SoundManager.play/playDelayed` (mixin), `ClientLevel.playSound` signature — the 9-arg method (x,y,z,event,source,vol,pitch,distanceDelay,seed).

### 2.5 `CollisionHelper` — common + client sections, ~562 LOC
`imm_ptl/core/collision/CollisionHelper.java`, static utility + event registration.

**Responsibility:** geometric primitives (plane-clip a box/VoxelShape), the vanilla-copied collision resolution generalized for gravity/stepping, colliding-portal bookkeeping for whole worlds, and the client "stagnate" overlay.

**Key API (all static):**
- `@Nullable AABB clipBox(AABB, Vec3 planePos, Vec3 planeNormal)` (CollisionHelper.java:49-82) — keep the side the normal points to; null if fully clipped.
- `boolean isBoxFullyBehindPlane(Vec3 planePos, Vec3 planeNormal, AABB)` (CollisionHelper.java:85).
- `@Nullable VoxelShape clipVoxelShape(VoxelShape, Vec3 planePos, Vec3 planeNormal)` (CollisionHelper.java:148-186) — fast-paths fully-behind/fully-in-front, else `Shapes.joinUnoptimized(shape, Shapes.create(clippedBB), BooleanOp.AND)`.
- `boolean canCollideWithPortal(Entity, Portal, float partialTick)` / `mayEntityCollideWithPortal(Entity, Portal, Vec3 eyePos, AABB bb)` (CollisionHelper.java:99-120) — delegates to `Portal.canCollideWithEntity` (= `canTeleportEntity`, Portal.java:733-736) and `PortalShape.canCollideWith`.
- `double fixCoordinateFloatingPointError(double attemptedMove, double result)` (CollisionHelper.java:127-141) — restores the attempt when |diff|<0.001; snaps |result|<0.0001 to 0 ("avoid falling through floor" after rotation).
- `Vec3 handleCollisionWithShapeProcessor(Entity, AABB box, Level, Vec3 attemptedMove, Function<VoxelShape,VoxelShape> filter, Direction gravity, double steppingScale)` (CollisionHelper.java:250-325) — `@IPVanillaCopy` of `Entity.collide(Vec3)` with: arbitrary gravity axis, `maxUpStep * steppingScale` when scale>1 (CollisionHelper.java:275-277), and the shape filter applied to every candidate shape. (The unused `refHandleCollisionWithShapeProcessor` at CollisionHelper.java:189-242 is the un-generalized reference copy — keep for fidelity diffing.)
- `Vec3 collideBoundingBox(Entity, Vec3, AABB, Level, List<VoxelShape> potentialHits, Function<VoxelShape,VoxelShape> shapeProcessor)` (CollisionHelper.java:340-378) — `@IPVanillaCopy` of `Entity.collideBoundingBox`: filters entity shapes, adds world-border shape when center is in-bounds and <32 from border, filters block collisions, then calls the **private vanilla** `Entity.collideWithShapes` via `IEEntity_Collision.ip_CollideWithShapes` (@Invoker, mixin/common/collision/IEEntity_Collision.java:14-17).
- `AABB transformBox(Portal, AABB)` (CollisionHelper.java:380-387) — pure translation when no rotation and scale==1, else 8-vertex transform via `Helper.transformBox`.
- `void updateCollidingPortalForWorld(Level, float partialTick)` (CollisionHelper.java:403-429) — per world: for each `Portal` entity call `notifyCollidingPortals`; for each non-portal entity test **global portals** by stretched-box intersection.
- `void notifyCollidingPortals(Portal, float partialTick)` (CollisionHelper.java:466-493) — entities within the portal's bounding box (region search, half-size 8) whose stretched box intersects → `IEEntity.ip_notifyCollidingWithPortal(portal)`.
- `AABB getStretchedBoundingBox(Entity)` (CollisionHelper.java:495-512) — bb expanded backwards by (lastTickPos − pos) and forwards by `worldVelocity * 1.2`, inflated by scale when scale>4.
- `void init()` (CollisionHelper.java:431-437) — `ServerTickEvents.END_SERVER_TICK`: `updateCollidingPortalForWorld(world, 0)` for all server worlds. `initClient()` (CollisionHelper.java:440-442) — `IPGlobal.POST_CLIENT_TICK_EVENT` → same for all `ClientWorldLoader.getClientWorlds()` + stagnate-overlay update (CollisionHelper.java:445-449).
- `void informClientStagnant()` (CollisionHelper.java:518-521) — sets a flag; two consecutive stagnate ticks show the `imm_ptl.stagnate_movement` overlay (CollisionHelper.java:524-540).
- `@Nullable AABB getTotalBlockCollisionBox(Entity, AABB, Function<VoxelShape,VoxelShape>)` (CollisionHelper.java:543-560) — union of filtered block collision bounds; used by the client post-teleport position adjustment.

### 2.6 `PortalCollisionHandler` — common, ~523 LOC
`imm_ptl/core/collision/PortalCollisionHandler.java`. **One instance per entity**, lazily created and stored in the `@Unique` field `ip_portalCollisionHandler` added to `Entity` by `MixinEntity` (mixin/common/collision/MixinEntity.java:41-42).

**Responsibility:** the entity's set of currently-colliding portals and the cross-portal collision resolution itself.

**Key API:**
- Fields: `long lastActiveTime`; `List<PortalCollisionEntry> portalCollisions`; `maxCollidingPortals = 6` (PortalCollisionHandler.java:30-33).
- `boolean isRecentlyCollidingWithPortal(Entity)` — within 20 of `entity.tickCount` (PortalCollisionHandler.java:35-37; timing source is `tickCount`, NOT game time — comment at MixinEntity.java:383 explains client game time can jump on time sync).
- `void update(Entity)` (PortalCollisionHandler.java:39-66) — eviction, run per entity per tick from `ip_tickCollidingPortal`: drop entry if portal level != entity level; stretched bb (inflated 0.5) no longer intersects portal bb; entry older than 3 ticks; or `mayEntityCollideWithPortal` fails **using the last-tick eye pos** (`getEyePosition(0)`) — comment: teleportation is based on rendering camera pos which is behind this-tick pos (PortalCollisionHandler.java:54-60).
- `Vec3 handleCollision(Entity, Vec3 attemptedMove)` (PortalCollisionHandler.java:72-90) — sorts entries by `activeTime` descending, then `doHandleCollision`.
- `@Nullable AABB getActiveCollisionBox(Entity, AABB raw)` (PortalCollisionHandler.java:338-354) — folds `PortalShape.transformEntityActiveCollisionBox` over all colliding portals; null = "no valid collision box".
- `static void updateCollidingPortalAfterTeleportation(Entity, Vec3 newEyePos, Vec3 newLastTickEyePos, float partialTicks)` (PortalCollisionHandler.java:356-373) — clears the handler, re-notifies from a fresh portal search around the stretched bb, then re-sets eye pos + bounding box. Called by both client and server teleports.
- `void notifyCollidingWithPortal(Entity, Portal)` (PortalCollisionHandler.java:379-397) — add or refresh entry (cap 6), fires `portal.onCollidingWithEntity(entity)` (Portal.java:1619 — overridable hook, e.g. `EndPortalEntity`), updates `lastActiveTime`.
- `List<Portal> getCollidingPortals()` (PortalCollisionHandler.java:399-401).
- Static geometry helpers: `getOffsetForPushingBoxOutOfAABB` (PortalCollisionHandler.java:403-444, axis of largest |movement| via `Range.getPushRangeMovement`), `getOffsetForConfiningBoxInsideAABB` (PortalCollisionHandler.java:446-463), `getOffsetForPushingBoxOutOfPlane` (PortalCollisionHandler.java:465-487), `getMovementForPushingEntityOutOfPortal(attemptedMove, origin, normal, bb)` (PortalCollisionHandler.java:490-518 — cancels the inward component of the attempt and pushes the box back to the plane). These are called by `PortalShape` implementations for the chunk-not-loaded case.

### 2.7 `PortalCollisionEntry` — common, 14 LOC
`imm_ptl/core/collision/PortalCollisionEntry.java` — struct: `final Portal portal; long activeTime` (PortalCollisionEntry.java:5-12).

### 2.8 Collision mixins (read for wiring; live in `mixin/common/collision/` + `mixin/client/collisions/`)

- **`MixinEntity`** (~387 LOC, targets `Entity`, implements ducks `IEEntity` + public API `ImmPtlEntityExtension`):
  - `@Redirect` of `Entity.collide(Vec3)` inside `Entity.move(MoverType, Vec3)` → `redirectHandleCollisions` (MixinEntity.java:83-138). Order of checks: (1) if `!IPGlobal.enableServerCollision` server-side → players get raw `attemptedMove`, non-players get `Vec3.ZERO` (MixinEntity.java:91-100); (2) `attemptedMove.lengthSqr() > 60*60` → `Vec3.ZERO` + rate-limited error (avoid chunk-loading lag, MixinEntity.java:102-113); (3) no handler / `IPGlobal.crossPortalCollision` off → vanilla `collide` (MixinEntity.java:115-121); (4) else `handler.handleCollision(...)`, and a result `lengthSqr() > 20*20` is discarded to `Vec3.ZERO` (MixinEntity.java:127-135).
  - `fireImmune()` forced true while colliding with an `EndPortalEntity` (MixinEntity.java:143-152).
  - `checkInsideBlocks`: bounding box redirected through `ip_getActiveCollisionBox`, and the method cancelled when it is null (MixinEntity.java:154-179) — prevents e.g. fire/portal-block triggers from the ghost part of the box.
  - `isInWall()` forced false while recently colliding (MixinEntity.java:182-187) — anti-suffocation at the frame.
  - `getInBlockState` cross-portal override for upward-facing portals (ladder climbing across, MixinEntity.java:217-239).
  - Duck impl: `ip_tickCollidingPortal()` runs `handler.update(entity)` and, client-side, `IPMcHelper.onClientEntityTick` (MixinEntity.java:268-278). `ip_setPositionWithoutTriggeringCallback` = `@IPVanillaCopy` of `Entity.setPosRaw` body updating `position/blockPosition/chunkPosition/inBlockState` without callbacks (MixinEntity.java:315-340). `ip_setWorld` writes the private `Entity.level` field directly (MixinEntity.java:379-381). `ip_unsetRemoved` exposes the protected vanilla `unsetRemoved()` (MixinEntity.java:308-310).
- **`IEEntity_Collision`** — `@Invoker("collideWithShapes")` static accessor for the private vanilla resolver (IEEntity_Collision.java:14-17).
- **`MixinPlayer_Collision`** — `@Overwrite` of `Player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose)`: builds the pose box, maps through `ip_getActiveCollisionBox`, null → fits; else vanilla `noCollision(deflate 1e-7)` (MixinPlayer_Collision.java:19-33) — stops wrong forced-crouch at the portal.
- **`MixinLocalPlayer`** (client) — `suffocatesAt(BlockPos)` forced false while recently colliding (mixin/client/collisions/MixinLocalPlayer.java:14-24) — stops being pushed out of "blocks" that are actually behind the portal.
- **`MixinProjectile`** — `@Redirect` in `Projectile.getOwner`: `ServerLevel.getEntity(UUID)` searched across **all** server levels so the owner is found after either party crossed (MixinProjectile.java:15-34).
- **`MixinThrownEnderPearl`** — `@Inject` before `discard()` in `onHit`: if the owner `ServerPlayer` is in a *different* level than the pearl (pearl crossed a portal), do `ServerTeleportationManager.teleportEntityGeneral(owner, pearl.position(), pearlLevel)` (MixinThrownEnderPearl.java:17-42). (Vanilla only teleports within the pearl's own level.)
- **`MixinAbstractMinecartEntity`** — after `lerpTo`, if `!IPGlobal.allowClientEntityPosInterpolation` snap `setPos` (MixinAbstractMinecartEntity.java:13-24; debug aid, flag default true per IPGlobal.java:117).
- **`MixinAbstractArrow` / `MixinThrowableProjectile`** — **entirely commented out** in 1.21.3 (arrow/projectile hit-through-placeholder handling; MixinAbstractArrow.java:7-19, MixinThrowableProjectile.java:6-45). Nothing to port except the TODO awareness.

### 2.9 Cross-slice hooks this slice requires (owned by other slices, cited for completeness)
- `MixinClientLevel.tickNonPassenger` HEAD → `ip_tickCollidingPortal` (mixin/client/MixinClientLevel.java:153-160); same on server in `MixinServerLevel` (mixin/common/MixinServerLevel.java:64-71). Comment: "right before setting last tick pos to this tick pos".
- `MixinMinecraft.onAfterClientTick` → `manageTeleportation(true)` after `ClientLevel.tick` and remote-world ticking, with `RenderStates.setPartialTick(0)` (mixin/client/MixinMinecraft.java:119-142).
- `MixinGameRenderer.onFarBeforeRendering` (`GameRenderer.render` HEAD) → `RenderStates.updatePreRenderInfo(partialTick)`, `ClientPortalAnimationManagement.update()`, then `manageTeleportation(false)` (mixin/client/render/MixinGameRenderer.java:70-100).
- `MixinChunkMap_E.onUnloadEntity` (`ChunkMap.removeEntity` HEAD, cancellable): if `isTeleporting(entity)` — for players, remove the tracker entry and `updatePlayerStatus(player,false)` but *keep other entities tracked*; for non-players just remove the tracker map entry; then cancel vanilla (mixin/common/entity_sync/MixinChunkMap_E.java:57-76).
- `MixinServerGamePacketListenerImpl` (position-sync package) — see mechanism 3.4.
- `MixinServerPlayer` ducks: `ip_stopRidingWithoutTeleportRequest` = `super.stopRiding()`, `ip_startRidingWithoutTeleportRequest` = `super.startRiding(v, true)` (bypassing `ServerPlayer`'s overrides that send teleport/dismount packets), `portal_worldChanged(fromWorld, fromPos)` = sets `enteredNetherPosition` for OW→nether + `triggerDimensionChangeTriggers` (advancements) (mixin/common/entity_sync/MixinServerPlayer.java:33-51).
- `MixinClientLevel_Sound` (see 2.4).
- `ImmPtlNetworking.TeleportPacket` (network/ImmPtlNetworking.java:45-90) — C2S payload `imm_ptl:teleport`: varint dim-int-id + 3 doubles (eye pos before teleport) + portal UUID; `handle(ServerPlayer)` maps dim int → key via `PortalAPI.serverIntToDimKey` and calls `onPlayerTeleportedInClient` (ImmPtlNetworking.java:76-84).

---

## 3. Mechanisms

### 3.1 Client-first player teleport — detection

`manageTeleportation(isTicking)` runs **twice per frame-cycle**: once per client tick (partial tick forced to 0, mixin/client/MixinMinecraft.java:133-137) and once per render frame before world render (real partial tick, mixin/client/render/MixinGameRenderer.java:86-92). Flow (ClientTeleportationManager.java:121-203):

1. Bail if `IPGlobal.disableTeleportation`, no level/player, or player last-tick pos is uninitialized (`xo==yo==zo==0`, ClientTeleportationManager.java:138).
2. `teleportationCounter++` — this counter is compared against `portal.animation.clientLastFramePortalStateCounter` to decide whether a portal has fresh animation states this frame (ClientTeleportationManager.java:241).
3. Update per-portal animation state for custom-animated portal clusters (ClientTeleportationManager.java:144-151).
4. **Combo loop**: `for (int i = 0; i <= teleportLimitPerFrame; i++)` (ClientTeleportationManager.java:162) — the inclusive bound allows up to **4** successive `tryTeleport(realPartialTicks)` calls (`teleportLimitPerFrame = 3`); rejection fires when the **4th** teleport succeeds (`i == teleportLimitPerFrame`, ClientTeleportationManager.java:171). The rejection does **not** restore the pre-combo position: by that point `lastPlayerEyePos` was already overwritten inside that final `tryTeleport` with the final teleport's checkpoint (`teleportationCheckpoint + newTickDelta * adjustment`, ClientTeleportationManager.java:306-307), and the restore position is computed as `oldPos = lastPlayerEyePos.subtract(eyeOffset)` (ClientTeleportationManager.java:175-176) — i.e. derived from the **last teleport's checkpoint**. Only the *dimension* is pre-combo: `originalDim` is captured before the loop (ClientTeleportationManager.java:159) and passed to `forceTeleportPlayer` (:178). The player is force-teleported there, position nudged `-0.001` along the last portal's surface normal (:179), and given velocity `normal * -0.1` (:181-183) (escape-fractal-scale-box guard).
5. If a teleport happened and `PortalExtension.adjustPositionAfterTeleport` — run `adjustPlayerPosition` (3.8).
6. Record `lastPlayerEyePos = player.getEyePosition(partialTick)` — next invocation's segment start (ClientTeleportationManager.java:198).

`tryTeleport(partialTicks)` (ClientTeleportationManager.java:211-314):
- Detection segment = `lastPlayerEyePos → thisFrameEyePos` (**render-time interpolated eye positions**, not tick positions). If segment length² > 1600 (40 blocks) → skip (ClientTeleportationManager.java:217-220).
- Enumerate portals via `IPMcHelper.traverseNearbyPortals(level, eyePos, IPGlobal.maxNormalPortalRadius + 1, ...)` — global portals within 2×range plus entity-portals by rough radius (IPMcHelper.java:86-103; `maxNormalPortalRadius = 32`, IPGlobal.java:19). Filter `portal.canTeleportEntity(player)` (teleportable flag, not-a-portal, `specificPlayerId` gate, `O_O.allowTeleportingEntity`, `ImmPtlEntityExtension.imm_ptl_canTeleportThroughPortal` — Portal.java:697-726).
- Per portal, **two disjoint code paths** (deliberate: "I want the dynamic teleportation bugs to not affect static teleportation", ClientTeleportationManager.java:238-240):
  - *Animated portal* (has fresh last/this frame state + last/this tick animated state): `TeleportationUtil.checkDynamicTeleportation` with both frame-pair and tick-pair states, both frame-pair and tick-pair eye positions, and partialTicks (ClientTeleportationManager.java:249-261).
  - *Static portal*: `TeleportationUtil.checkStaticTeleportation(portal, lastFrameEyePos, thisFrameEyePos, lastTickEyePos, thisTickEyePos)` (ClientTeleportationManager.java:269-274). Note the frame pair is what is ray-traced; the tick pair is what gets transformed for placement.
- Choose the candidate whose `worldCollisionPoint` is nearest `lastPlayerEyePos` (ClientTeleportationManager.java:282-287).
- After `teleportPlayer(...)`, set `lastPlayerEyePos = teleportationCheckpoint + newTickDelta * adjustment`, where adjustment is `-0.001` if `portal.respectParallelOrientedPortal()` (allow overlapped/parallel portals, e.g. MiniScaled) else `+0.001` (avoid re-crossing a parallel portal by floating error) (ClientTeleportationManager.java:298-307).

### 3.2 Detection math (`TeleportationUtil`)

**Static** (`checkStaticTeleportation`, TeleportationUtil.java:97-142): transform both segment endpoints into portal-local coordinates (`Portal.transformFromWorldToPortalLocal` = dot with axisW/axisH/normal, Portal.java:1898-1905); `PortalShape.raytracePortalShapeByLocalPos(thisSideState, lastLocal, currentLocal, 0)`; if hit, build the `Teleportation` with all four PortalStates equal to the current state, `PortalPointVelocity.ZERO`, checkpoint = `portal.transformPoint(worldHitPos)`, and new tick eye positions = `portal.transformPoint(lastTickEyePos/thisTickEyePos)` (TeleportationUtil.java:122-140).

**Dynamic** (`checkDynamicTeleportation`, TeleportationUtil.java:146-270): eye positions converted to local space using the *matching-time* portal states (last frame state for last frame pos, etc., TeleportationUtil.java:153-154); ray-trace in local space; then:
- `getPortalPointVelocity(lastTickState, thisTickState, localHitPos)` (TeleportationUtil.java:31-48): this-side point velocity = movement of the local hit point between tick states; other-side = movement of the transformed point. Known-approximate for rotating portals (header comment TeleportationUtil.java:24-30).
- New other-side tick positions = each tick state's `transformPoint` of the corresponding tick eye pos; then a **camera-continuity offset** is applied: `offset = collisionPortalState.transformPoint(currentFrameEyePos) − lerp(newLastTick, newThisTick, partialTicks)`, where `collisionPortalState = PortalState.interpolate(lastFrameState, currentFrameState, t)` (TeleportationUtil.java:176-203). This pins the *immediate render camera* to the exact transform at the collision instant.
- For planar shapes, three successive "not behind the destination" corrections, each pushing both new tick positions along the content direction if the dot < 0.00001: against `thisTickState.toPos`, then `currentFrameState.toPos` (re-lerped), then `lastFrameState.toPos` (re-lerped) (TeleportationUtil.java:205-249).
- `teleportationCheckpoint = lerp(newLastTick, newThisTick, partialTicks)` (TeleportationUtil.java:251-252).

### 3.3 Client teleport execution

`teleportPlayer(teleportation, partialTicks)` (ClientTeleportationManager.java:320-424), gated by `tickTimeForTeleportation > teleportTickTimeLimit` (cooldown set via `disableTeleportFor`):

1. Capture vehicle + its position; capture current tick eye pair.
2. If cross-dimension: `changePlayerDimension(player, fromWorld, ClientWorldLoader.getWorld(toDim), newThisTickEyePos)` (3.3.1).
3. `McHelper.setEyePos(player, newThisTickEyePos, newLastTickEyePos)` — sets pos = eyePos − gravity-aware eye offset and writes **both** `xo/yo/zo` and `xOld/yOld/zOld` to the last-tick value (McHelper.java:247-293); then `McHelper.updateBoundingBox` (= `setPos` with same coords to rebuild bb, McHelper.java:373-375).
4. **Rotation**: capture world velocity, run `TransformationManager.managePlayerRotationAndChangeGravity(portal)`, then restore the captured velocity (ClientTeleportationManager.java:359-361) — rotation management must not change velocity. Details (render/TransformationManager.java:135-224): only if `portal.getRotation() != null`; composes quaternions `finalRot = rawCameraRot * gravityRot * animationDelta * portalRot⁻¹`, applies gravity change via GravityChanger if `getTeleportedGravityDirection` differs, extracts new pitch/yaw (pitch mirrored back into [-90,90], TransformationManager.java:188-193), and writes `setYRot/setXRot` **plus** `yRotO/xRotO/yBob/xBob/yBobO/xBobO` (TransformationManager.java:195-203) so no interpolation glitch occurs; any residual rotation difference becomes a ~1s smooth `animationDelta` interpolation (TransformationManager.java:211-220) and the camera is re-`setup(...)` immediately (TransformationManager.java:230-238).
5. **Velocity**: `TeleportationUtil.transformEntityVelocity(portal, player, portalPointVelocity, thisTickEyePos)` — `(v − thisSidePointVel)` transformed by `portal.transformVelocityRelativeToPortal` (= `transformLocalVec`; clamped to length 15; minecarts with result²<0.5 doubled — Portal.java:1067-1085) `+ otherSidePointVel` (TeleportationUtil.java:50-59). Same applied to the vehicle with its old position (ClientTeleportationManager.java:366-368).
6. **Scale**: `ScaleUtils.onClientPlayerTeleported(portal)` — if `portal.hasScaling() && portal.isTeleportChangesScale()`, rescale the player (attribute `Attributes.SCALE` based) and multiply the camera's smoothed Y offsets (ScaleUtils.java:30-46).
7. **Packet**: send `ImmPtlNetworking.TeleportPacket(clientDimKeyToInt(fromDimension), thisTickEyePos, portal.getUUID())` — note it carries the **pre-teleport eye pos in the origin dimension** and the portal id, nothing else (ClientTeleportationManager.java:372-378).
8. `PortalCollisionHandler.updateCollidingPortalAfterTeleportation(player, newThisTickEyePos, newLastTickEyePos, partialTick)` (3.7.5), `McHelper.adjustVehicle(player)` (re-pin vehicle to passenger with `setPos` + `lerpTo(…, 0)` interpolation kill, McHelper.java:305-323), `RenderStates.updatePreRenderInfo(partialTick)` (teleport may happen after pre-render info was captured), `MyGameRenderer.vanillaTerrainSetupOverride = 1` (ClientTeleportationManager.java:380-423).

**3.3.1 `changePlayerDimension`** (ClientTeleportationManager.java:458-527), asserts not-rendering / not-clipping / not-processing-redirected-packet: `player.unRide()`; `IEClientPlayNetworkHandler.ip_setWorld(toWorld)` (the `ClientPacketListener.level` field); `fromWorld.removeEntity(player.getId(), CHANGED_DIMENSION)`; `IEEntity.ip_setWorld(toWorld)`; set eye pos both-ticks; `ip_unsetRemoved()`; `toWorld.addEntity(player)`; `IEAbstractClientPlayer.ip_setClientLevel(toWorld)`; swap `GameRenderer`'s lightmap to the dest `DimensionRenderHelper.lightmapTexture`; `client.level = toWorld`; swap `Minecraft.levelRenderer` via `IEMinecraftClient.ip_setWorldRenderer`; re-point `particleEngine` world (keeps particles alive) and `BlockEntityRenderDispatcher.setLevel`; if there was a vehicle: `moveClientEntityAcrossDimension(vehicle, toWorld, playerPos+offset)` (ClientTeleportationManager.java:552-564: remove/setWorld/setPos/unsetRemoved/addEntity), restore both tick positions with the passenger offset, `player.startRiding(vehicle, true)`; finally `FogRendererContext.onPlayerTeleport` + `O_O.onPlayerChangeDimensionClient` (→ RequiemCompat, platform_specific/O_O.java:44-48).

### 3.4 Server validation + player relocation

`TeleportPacket.handle` → `onPlayerTeleportedInClient(player, dimBefore, eyePosBefore, portalId)` (ServerTeleportationManager.java:160-226):

1. Refuse removed players. `findPortal` — `originalWorld.getEntity(portalId)`, falling back to `GlobalPortalStorage.get(world).data` scan by UUID (ServerTeleportationManager.java:228-253).
2. `lastTeleportGameTime.put(player, serverGameTime)` — the dedup stamp (`McHelper.getServerGameTime` = overworld game time, McHelper.java:126-128).
3. `validatePlayerTeleportationAndGetReason` (ServerTeleportationManager.java:264-296) — **returns valid immediately if `player.getVehicle() != null`** (ServerTeleportationManager.java:270-272 — mounted crossings bypass all checks); rejects when: connection has an unconfirmed awaiting teleport (`IEServerPlayNetworkHandler.ip_hasAwaitingTeleport` = `awaitingPositionFromClient != null`, MixinServerGamePacketListenerImpl.java:338-341); `!portal.canTeleportEntity(player)`; player's server dimension != packet's `dimensionBefore`; server pos vs packet feet-pos distance² > 256 (16 blocks); `portal.getDistanceToNearestPointInPortal(posBefore) > 20`.
4. On success: `notifyChasersForPlayer` (3.4.2); `newEyePos = portal.transformPoint(eyePosBeforeTeleportation)` — the server re-derives the destination itself, it does **not** trust a client-supplied destination; `recordLastPosition`; `teleportPlayer(player, destDim, newEyePos)`; `portal.onEntityTeleportedOnServer(player)` (runs `commandsOnTeleported`, Portal.java:504-508); `ScaleUtils.onServerEntityTeleported` (scale + vehicle scale, ScaleUtils.java:48-56); if `portal.getTeleportChangesGravity()` set the new base gravity = `portal.getTransformedGravityDirection(oldDir)` (ServerTeleportationManager.java:206-211).
5. On failure: log reason; `teleportEntityGeneral(player, player.position(), currentLevel)` → `forceTeleportPlayer` **which sends a vanilla `connection.teleport`** to yank the client back; re-assert base scale and gravity (ServerTeleportationManager.java:214-225).

`teleportPlayer` (ServerTeleportationManager.java:329-358): same-dimension → set eye pos both-ticks + update bb; cross-dimension → `changePlayerDimension` (3.4.1). Then `McHelper.adjustVehicle`, **`player.connection.resetPosition()`** (re-baselines the server's move-packet validator at the new position — this is the *entire* position "sync" for a normal crossing; no S2C position packet is sent), and `updateCollidingPortalAfterTeleportation(player, newEyePos, newEyePos, 1)`.

**3.4.1 `changePlayerDimension` (server)** (ServerTeleportationManager.java:421-485): add player to `teleportingEntities` (so `MixinChunkMap_E` keeps trackers alive and cancels `ChunkMap.removeEntity`'s untrack-everything behavior; the set is cleared at end of the server tick, ServerTeleportationManager.java:88-92); `ip_stopRidingWithoutTeleportRequest` if mounted; `fromWorld.removePlayerImmediately(player, CHANGED_DIMENSION)` + `ip_unsetRemoved()`; set eye pos both-ticks; `player.setServerLevel(toWorld)`; `toWorld.addDuringTeleport(player)`; vehicle → `teleportVehicleAcrossDimensions` (3.5) then `ip_startRidingWithoutTeleportRequest(vehicle)` + `adjustVehicle`; `O_O.onPlayerTravelOnServer` (RequiemCompat, O_O.java:50-55); `portal_worldChanged(fromWorld, oldPos)` — advancement triggers + `enteredNetherPosition` (MixinServerPlayer.java:46-51).

**3.4.2 Chaser handoff** (ServerTeleportationManager.java:765-824): mobs within rough radius 1 whose `getTarget() == player` get `setTarget(null)` and a retrying (≤140 attempts) server task: while the chaser exists, path it toward `player.position() + portal.getNormal() * -0.1` (through the portal); direct `MoveControl.setWantedPosition` when within 2 blocks; once the chaser is removed (teleported by the regular-entity path), find its UUID in the dest world and restore `setTarget(player)`.

### 3.5 Server-first regular-entity + vehicle teleport

**Trigger**: every server portal tick fires `Portal.SERVER_PORTAL_TICK_SIGNAL` (Portal.java:952); the manager's listener runs `getEntitiesToTeleport(portal)` = entities of class `Entity` in `portal.getBoundingBox().inflate(2)`, excluding portals, filtered by `shouldEntityTeleport` (ServerTeleportationManager.java:70-77, 148-158). `shouldEntityTeleport` (ServerTeleportationManager.java:94-106): same level; `canTeleportEntity`; segment = eye pos at partial-tick 0 → partial-tick 1, **projectiles additionally extend the segment by their world velocity** (they move after the portal ticks); `portal.isMovedThroughPortal(lastEyePos, nextEyePos)` = lenient ray trace (leniency 0.001) against the portal shape (Portal.java:1231-1243). Additionally, `manageGlobalPortalTeleportation` at END_SERVER_TICK scans **all entities of all worlds** whose current colliding portal is a global portal (global portals don't tick as entities) (ServerTeleportationManager.java:487-501).

**`startTeleportingRegularEntity`** guards (ServerTeleportationManager.java:108-146): not a `ServerPlayer`, not a `Portal`, not a passenger, cluster contains no player (recursive passenger check, ServerTeleportationManager.java:710-719), not removed, `entity.canChangeDimensions(entity.level(), portal.getDestinationWorld())`, not `isJustTeleported(entity, 1)`, last-tick pos not `0,0,0` ("fresh new entity" warn), and per-tick movement² ≤ 20. Then defers `teleportRegularEntity` to `ServerTaskList` (runs later same tick / next tick, exceptions swallowed with logging).

**`teleportRegularEntity`** (ServerTeleportationManager.java:507-583): re-validate (not removed, same level as portal, `getDistanceToNearestPointInPortal(eyePos) ≤ 5`); dedup: skip if `currGameTime − lastTeleportGameTime(entity) <= 0` — i.e. at most one teleport per entity per game-time tick — then stamp (ServerTeleportationManager.java:527-532); skip passengers/player clusters again.
- **Destination eye pos** (`getRegularEntityTeleportedEyePos`, ServerTeleportationManager.java:585-607): because the teleport is deferred ~1 tick, ray-trace `eyePosThisTick − dir*5 → eyePosThisTick + dir` (dir = normalized tick delta) against the portal; fall back to `eyePosLastTick` if no hit; result = `portal.transformPoint(collidingPoint) + dir * 0.05` (small overshoot).
- Velocity: `transformEntityVelocity(portal, entity, PortalPointVelocity.ZERO, oldPos)` — server-side regular entities ignore portal motion (ServerTeleportationManager.java:545-547).
- Cross-dimension: `entity = changeEntityDimension(entity, destDim, newEyePos, recreateEntity=true)`; **each passenger** is likewise dimension-changed then `startRiding(newEntity, true)` (ServerTeleportationManager.java:549-559).
- Set eye pos both-ticks; send the `updateEntityPos` RPC to trackers (kill client interpolation; ServerTeleportationManager.java:564-575); `portal.onEntityTeleportedOnServer(entity)`; `ScaleUtils.onServerEntityTeleported`; re-stamp the (possibly new) entity object's `lastTeleportGameTime` (ServerTeleportationManager.java:577-582).

**`changeEntityDimension(entity, dim, newEyePos, recreateEntity)`** (ServerTeleportationManager.java:615-678): `entity.unRide()`. If `recreateEntity` (the norm — javadoc: "reusing the same entity object is problematic because entity's AI related things may have world reference inside", ServerTeleportationManager.java:609-613): `newEntity = entity.getType().create(toWorld)`; `newEntity.restoreFrom(oldEntity)` (vanilla NBT-copy); **`newEntity.setId(oldEntity.getId())`** (keeps the network id — clients see the same entity id across the crossing); set eye pos both-ticks; `setYHeadRot(oldEntity.getYHeadRot())`; `oldEntity.remove(CHANGED_DIMENSION)`; `toWorld.addDuringTeleport(newEntity)`. Else (reuse path): `remove(CHANGED_DIMENSION)` + `ip_unsetRemoved()` + set pos + `ip_setWorld(toWorld)` + `addDuringTeleport(entity)` (ServerTeleportationManager.java:663-677). A `// TODO check minecart item duplication` sits on the recreate path (ServerTeleportationManager.java:656).

**`teleportVehicleAcrossDimensions`** (ServerTeleportationManager.java:680-708): the vehicle variant used inside the *player* dimension change — always recreates with id-preservation as above, but **first adds the vehicle to `teleportingEntities`** ("avoid sending the remove entity packet") and calls `ip_unsetRemoved()` on the **old** entity after `remove` (so the old object is still queryable during the swap). Caller re-seats the player with `ip_startRidingWithoutTeleportRequest` and restores vehicle both-tick positions offset by `McHelper.getVehicleOffsetFromPassenger` (= vanilla `passenger.getVehicleAttachmentPoint(vehicle)`, McHelper.java:299-303).

### 3.6 Missed-crossing fallback + position-sync protocol

There is **no dedicated server-side crossing detector for players**. The safety net is the dimension-tagged position-sync protocol in `mixin/common/position_sync/MixinServerGamePacketListenerImpl`:

- Every C2S `ServerboundMovePlayerPacket` is expected to carry the client's dimension (added to the packet by an IP client mixin via `IEPlayerMoveC2SPacket`). If the tag is missing → the client has no ImmPtl → disconnect (MixinServerGamePacketListenerImpl.java:128-142). If the tag mismatches the server's dimension for the player, the packet is **cancelled** (not applied to the wrong world's coordinates) and the counter is incremented, then checked as `ip_wrongMovePacketCount > 10` (MixinServerGamePacketListenerImpl.java:155-157) — so on the **11th consecutive** wrong-dimension packet the server gives up waiting for the TeleportPacket and force-teleports the player to the server's own authoritative position — `forceTeleportPlayer(player, serverDim, serverPos)`, which sends the vanilla `connection.teleport` packet and yanks the client (MixinServerGamePacketListenerImpl.java:144-169; counter reset to 0 after the force move :165 and on any matching-dimension packet :171).
- S2C: `ServerGamePacketListenerImpl.teleport(...)` is `@Overwrite`n (vanilla-copy) to stamp `ip_dimOfAwaitingPosition = player.level().dimension()` and tag the outgoing `ClientboundPlayerPositionPacket` with the dimension via `IEPlayerPositionLookS2CPacket` (MixinServerGamePacketListenerImpl.java:179-225).
- When the client confirms (`handleAcceptTeleportPacket`), if the recorded awaiting-dimension differs from the player's current server dimension, the accept is honored by force-teleporting the player to the awaited position **in the awaited dimension** (`sendPacket=false`) instead of applying coordinates in the wrong world (MixinServerGamePacketListenerImpl.java:284-322). `handlePlayerCommand`'s wake-up path re-stamps the dim (MixinServerGamePacketListenerImpl.java:324-336).
- Client side of the same protocol: `MixinClientPacketListener.handleMovePlayer` reads the dimension tag off the position packet; if it differs from the client player's dimension, `ClientTeleportationManager.forceTeleportPlayer(packetDim, packetPos)` swaps the client world *before* the packet applies (mixin/client/sync/MixinClientPacketListener.java:88-129).
- Anticheat interplay: `isPlayerCollidingWithAnythingNew` is re-implemented on top of `ip_getActiveCollisionBox` so cross-portal standing doesn't trigger "moved wrongly" (MixinServerGamePacketListenerImpl.java:227-272); `clientIsFloating` is cleared while recently colliding with a portal (MixinServerGamePacketListenerImpl.java:275-280).
- `ClientTeleportationManager.acceptSynchronizationDataFromServer` (ClientTeleportationManager.java:102-119) is the *legacy* client acceptance path for a server-pushed sync — **it has no callers in 1.21.3 IP** (verified by grep over the full `src` tree); the live mechanism is the two mixins above.

### 3.7 Cross-portal collision

**3.7.1 Who has a handler.** `CollisionHelper.init` (server END tick) / `initClient` (IP post-client-tick) run `updateCollidingPortalForWorld(world, 0)` for every world (CollisionHelper.java:431-458): each `Portal` entity searches entities intersecting its bb and calls `IEEntity.ip_notifyCollidingWithPortal` (CollisionHelper.java:466-493), which lazily creates the `PortalCollisionHandler` and adds/refreshes a `PortalCollisionEntry` stamped with `entity.tickCount` (MixinEntity.java:281-289; PortalCollisionHandler.java:379-397). Entity-side eviction runs from `tickNonPassenger` HEAD (`ip_tickCollidingPortal` → `handler.update`) *before* last-tick pos is overwritten (mixin/client/MixinClientLevel.java:153-160; mixin/common/MixinServerLevel.java:64-71). The stretched box used both for notify and eviction extends backward by the last-tick delta and forward by 1.2× velocity (CollisionHelper.java:495-512) because "normal colliding portal update lags 1 tick before collision calculation".

**3.7.2 The collide replacement.** `Entity.move → collide` is redirected (MixinEntity.java:83-138; guards listed in 2.8). `PortalCollisionHandler.handleCollision` sorts entries newest-first and calls `doHandleCollision(entity, move, portalLayer=1, entries, originalBB)` (PortalCollisionHandler.java:72-90), which composes:

1. **This-side move** (`handleThisSideMove`, PortalCollisionHandler.java:264-279): `CollisionHelper.handleCollisionWithShapeProcessor` over the entity's own world with filter `processThisSideCollisionShape`: for each colliding portal, take `portal.getPortalShape().getOuterClipping(thisSideState)` — if the shape's bounds are **not** fully behind that plane, keep the shape untouched (axis-aligned VoxelShapes can't represent diagonal cuts — workaround comment PortalCollisionHandler.java:303-306); otherwise subtract the portal's cached `getThisSideCollisionExclusion()` (a VoxelShape region behind the portal, Portal.java:1954-1961) via `Shapes.joinUnoptimized(shape, exclusion, BooleanOp.ONLY_FIRST)`, short-circuiting to `null` when fully contained (PortalCollisionHandler.java:282-336).
2. **Other-side move per portal** (`handleOtherSideMove`, PortalCollisionHandler.java:145-244): skip if `!portal.getHasCrossPortalCollision()` or `portalLayer >= 5`. Transform the attempted move by `portal.transformLocalVec` (rotation+scale) and the bb by `CollisionHelper.transformBox`; give up (return original move) if the transformed box exceeds 100 per axis (PortalCollisionHandler.java:169-172, 520-522). If the dest chunk at the box center is **not loaded**: at layer ≤1, `handleOtherSideChunkNotLoaded` → client shows the stagnate overlay for players and the move becomes `PortalShape.getMovementForPushingEntityOutOfPortal(...)` (don't let the entity enter; PortalCollisionHandler.java:246-262); at deeper layers just pass the move through. Otherwise: find **indirect colliding portals** on the destination side (portals whose shape may collide with the transformed box and that lie `isOnDestinationSide(originPos, 0.1)`, PortalCollisionHandler.java:186-194); compute the transformed gravity (`portal.getTransformedGravityDirection(gravityOf(entity))`); collide against the destination world with filter = clip each shape by the portal's **inner clipping plane** (`portal.getInnerClipping()`, Portal.java:1388-1392 — discard geometry on the origin side of the destination plane) then subtract indirect portals' exclusions; `steppingScale = portal.getScale()` (so stepping height scales through scaling portals; PortalCollisionHandler.java:198-222). Recurse into each indirect portal with `portalLayer+1` and eye pos transformed again (PortalCollisionHandler.java:224-233). Fix floating error against the *transformed* attempt, then map the result back with `portal.inverseTransformLocalVec` (PortalCollisionHandler.java:235-243).
3. Final floating-point fix against the original attempt (PortalCollisionHandler.java:114-118).

`handleCollisionWithShapeProcessor` (CollisionHelper.java:250-325) is the vanilla `Entity.collide` algorithm (normal collide → stepping attempt → step-down) rewritten against an arbitrary gravity direction using `Helper.getSignedCoordinate/putSignedCoordinate/getDistanceSqrOnAxisPlane`, with `maxUpStep * steppingScale` and a `+0.001` epsilon on the vertical-step comparison (CollisionHelper.java:292). `collideBoundingBox` (CollisionHelper.java:340-378) is the vanilla `Entity.collideBoundingBox` with the shape filter applied to entity shapes and block shapes, the world-border shape appended under vanilla's conditions, and the final resolution delegated to the real private `Entity.collideWithShapes` through the `@Invoker` (IEEntity_Collision.java:14-17) — so the actual sweep math is *always vanilla's*, never reimplemented.

**3.7.3 Active collision box.** `getActiveCollisionBox` folds `PortalShape.transformEntityActiveCollisionBox(portal, box, entity)` (default: identity; shape-specific cropping, portal/shape/PortalShape.java:186-188) over all colliding portals (PortalCollisionHandler.java:338-354). Consumers: `Entity.checkInsideBlocks` bb redirect + cancel-on-null (MixinEntity.java:154-179), `Player.canPlayerFitWithinBlocksAndEntitiesWhen` overwrite (MixinPlayer_Collision.java:19-33), server anticheat `isPlayerCollidingWithAnythingNew` (MixinServerGamePacketListenerImpl.java:227-272).

**3.7.4 Suffocation/misc guards.** `isInWall` → false and `LocalPlayer.suffocatesAt` → false while `ip_isRecentlyCollidingWithPortal()` (20-tick memory) (MixinEntity.java:182-187; MixinLocalPlayer.java:14-24); `fireImmune` while touching an `EndPortalEntity` (MixinEntity.java:143-152); cross-portal `getInBlockState` for climbing (MixinEntity.java:217-239).

**3.7.5 Post-teleport reset.** `updateCollidingPortalAfterTeleportation` (PortalCollisionHandler.java:356-373): `ip_clearCollidingPortal()` (nulls the handler, MixinEntity.java:342-345), re-notify from a fresh `Portal` search around the stretched bb, then set the new eye pair + bb. Comment: deliberately does *not* run the eviction pass ("it only removes collisions"). Called on: client player teleport (ClientTeleportationManager.java:380-382), server player teleport (ServerTeleportationManager.java:353-355), server force teleport (ServerTeleportationManager.java:411-413).

### 3.8 Cooldown / dedup model (no vanilla portal cooldown!)

Nothing in this slice touches vanilla `Entity.setPortalCooldown`/`portalCooldown`. The model is:
- **Client player:** `teleportTickTimeLimit` hard-gate (`disableTeleportFor(ticks)`, checked at ClientTeleportationManager.java:325-328); `isTeleportingFrequently()` = teleported within 100 ticks or gate active (ClientTeleportationManager.java:427-430; consumed by the dead sync-acceptance path + screen-effect suppression); the per-frame combo limit (`teleportLimitPerFrame = 3`, inclusive loop bound → up to 4 `tryTeleport` calls, rejection on the 4th success) with pushback rejection (3.1); the ±0.001 checkpoint nudge to prevent immediate parallel-portal re-cross (ClientTeleportationManager.java:300-307).
- **Server player:** `lastTeleportGameTime` stamped on every accepted packet (ServerTeleportationManager.java:181); `isTeleporting(player)` true only within the same server tick (set cleared each END tick) — its real job is tracker-teardown suppression, and a frequent-teleport log (ServerTeleportationManager.java:189-191).
- **Server regular entity:** `isJustTeleported(entity, 1)` at scan time + the `<= 0` game-time diff check at execution time = max one teleport per game tick per entity, WeakHashMap keyed by object identity, re-stamped onto the recreated entity (ServerTeleportationManager.java:124, 527-532, 582).

### 3.9 How player state survives the crossing

- **Same object on both sides**: the player entity object is *never recreated* — client (`removeEntity` + `ip_setWorld` + `ip_unsetRemoved` + `addEntity`) and server (`removePlayerImmediately` + `ip_unsetRemoved` + `setServerLevel` + `addDuringTeleport`) both re-home the existing instance. Therefore attributes, effects, inventory, sprint state, food, etc. survive *by not being touched at all*. (This is the fidelity-critical difference from vanilla's respawn-packet + copyFrom flow — no `restoreFrom`, no `ClientboundRespawnPacket` for portal crossings.)
- **Velocity**: transformed relative to the portal-point velocity, capped at 15, minecart low-speed boost (3.3 step 5; Portal.java:1067-1085); rotation management explicitly save/restores velocity around itself (ClientTeleportationManager.java:359-361). Server does **not** transform the player's velocity (the client already did; the server only transforms regular entities').
- **Rotation**: quaternion recomposition writes current + last-tick + both bob fields in the same call, and residual visual delta is smoothed by `TransformationManager`'s animation over ~1s (3.3 step 4) — nothing snaps.
- **Gravity direction** (GravityChanger integration): client sets it during rotation management (TransformationManager.java:159-166); server re-derives base gravity from the portal transform after a validated teleport (ServerTeleportationManager.java:206-211). Without the mod, all of this is identity (`GravityChangerInterface.Invoker` fallbacks, compat/GravityChangerInterface.java:22-62).
- **Scale** (attribute `minecraft:generic.scale`): `ScaleUtils.onClientPlayerTeleported` (+ camera Y rescale) / `onServerEntityTeleported` (+ vehicle) only when `portal.teleportChangesScale` (ScaleUtils.java:30-56; base-scale setter also `refreshDimensions()`, ScaleUtils.java:125-133).
- **Both-tick position writes everywhere**: every relocation writes `xo/yo/zo` *and* `xOld/yOld/zOld` (McHelper.java:247-259) so neither interpolation nor `lastTickPos`-based logic sees a cross-dimension jump.
- **Vehicle**: preserved through both client and server flows with the passenger-attachment offset and interpolation-kill (`lerpTo(..., 0)`); the server recreates the vehicle entity but preserves its network id (3.5).
- **Post-teleport floor adjustment**: `adjustPlayerPosition` (ClientTeleportationManager.java:570-691) — only when `PortalExtension.adjustPositionAfterTeleport`; computes the union of block collision boxes intersecting the bottom half of the player's bb (shapes clipped by each colliding portal's outer clipping), and if the player's feet are below the union top, lifts them there over 5 ticks via a `CLIENT_TASK_LIST` task (gravity-aware; aborts on removal, gravity change, drift >2, or if the lift path would re-enter the colliding portal via `rayTrace`).
- **Motion affinity**: each client tick, if the colliding portal has `PortalExtension.motionAffinity != 0`, velocity is scaled by `1 + affinity` (only when speed > 0.7 for negative affinity) (ClientTeleportationManager.java:95-100, 529-549).

---

## 4. MC API touchpoint list (dedup; anything that could move across versions)

**Entity lifecycle & world membership**
- `Entity.remove(RemovalReason.CHANGED_DIMENSION)`; protected `Entity.unsetRemoved()` (exposed via duck, MixinEntity.java:308); `Entity.getRemovalReason()`/`isRemoved()`
- `ServerLevel.addDuringTeleport(Entity)` (ServerTeleportationManager.java:447, 659, 672, 705); `ServerLevel.removePlayerImmediately(ServerPlayer, RemovalReason)` (ServerTeleportationManager.java:438)
- `ServerPlayer.setServerLevel(ServerLevel)` (ServerTeleportationManager.java:444)
- `ClientLevel.addEntity(Entity)` / `ClientLevel.removeEntity(int, RemovalReason)` (ClientTeleportationManager.java:473, 482, 558, 562)
- `EntityType.create(Level)` **(1.21.3 single-arg; newer versions take `EntitySpawnReason`)** (ServerTeleportationManager.java:645, 693); `Entity.restoreFrom(Entity)`; `Entity.setId(int)`; `Entity.setUUID`
- private field `Entity.level` (written via duck `ip_setWorld`, MixinEntity.java:379-381); private fields `Entity.position/blockPosition/chunkPosition/inBlockState` (vanilla-copy `setPosRaw`, MixinEntity.java:315-340)
- `Entity.xo/yo/zo`, `Entity.xOld/yOld/zOld` (direct writes, McHelper.java:247-259); `Entity.setPosRaw`, `Entity.setPos`, `Entity.moveTo`, `Entity.absMoveTo` (mixin overwrite)
- `Entity.getEyePosition(float partialTick)`, `Entity.getEyeHeight`, `Entity.position()`, `Entity.getBoundingBox()`, `Entity.setYHeadRot/getYHeadRot`, `Entity.getYRot/getXRot/setYRot/setXRot`, `Entity.yRotO/xRotO`; `LocalPlayer.yBob/xBob/yBobO/xBobO` (TransformationManager.java:198-203)
- `Entity.canChangeDimensions(Level from, Level to)` (ServerTeleportationManager.java:121)
- Vehicle tree: `Entity.getVehicle/getPassengers/isPassenger/unRide/stopRiding/startRiding(Entity, boolean)`; `Entity.getVehicleAttachmentPoint(Entity)` (McHelper.java:299-303); `ServerPlayer.stopRiding/startRiding` bypass via `super` calls (MixinServerPlayer.java:33-40)
- `Entity.lerpTo(x,y,z,yaw,pitch,steps)` with steps=0 as interpolation-kill (ClientTeleportationManager.java:713-717; McHelper.java:321-323); `AbstractMinecart.lerpTo` inject (MixinAbstractMinecartEntity.java:13-24)
- `Entity.tickCount` (collision timing), `Entity.getDeltaMovement/setDeltaMovement`
- `LivingEntity.getAttributes().getInstance(Attributes.SCALE)`, `AttributeInstance.setBaseValue/getBaseValue/getModifier`, `Entity.refreshDimensions()` (ScaleUtils.java:58-133)

**Collision & shapes**
- `Entity.move(MoverType, Vec3)` (redirect site) and private `Entity.collide(Vec3)` (redirect target + `@Shadow` invoke, MixinEntity.java:47-48, 83-90)
- private static `Entity.collideWithShapes(Vec3, AABB, List<VoxelShape>)` (`@Invoker`, IEEntity_Collision.java:14-17) — **name/shape must be re-verified on 26.2**
- vanilla-copied algorithm bodies: `Entity.collide` and `Entity.collideBoundingBox(Entity, Vec3, AABB, Level, List)` (CollisionHelper.java:244-378) — any 26.2 change to stepping/step-down logic must be re-copied
- `Level.getEntityCollisions(Entity, AABB)`, `Level.getBlockCollisions(Entity, AABB)` (CollisionHelper.java:259, 368, 544), `Level.noCollision(Entity, AABB)` (MixinPlayer_Collision.java:28), `LevelReader.getCollisions` (MixinServerGamePacketListenerImpl.java:257-258)
- `WorldBorder.isWithinBounds(x,z)/getDistanceToBorder(x,z)/getCollisionShape()` (CollisionHelper.java:356-364)
- `Shapes.create(AABB)`, `Shapes.joinUnoptimized(a,b,BooleanOp.AND|ONLY_FIRST)`, `Shapes.joinIsNotEmpty`, `VoxelShape.bounds()/isEmpty()`, `BooleanOp` (CollisionHelper.java:179-185; PortalCollisionHandler.java:322-326; MixinServerGamePacketListenerImpl.java:260-263)
- `Entity.maxUpStep()` (CollisionHelper.java:274 — **26.2: became an attribute; verify accessor**), `Entity.onGround()`, `Entity.fireImmune()`, `Entity.isInWall()`, `Entity.checkInsideBlocks()` (inject target — renamed/insideBlock handling changed in newer versions), `Entity.getInBlockState()` (inject target)
- `Player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose)` (`@Overwrite`, MixinPlayer_Collision.java:20), `LivingEntity.getDimensions(Pose).makeBoundingBox(Vec3)`
- `LocalPlayer.suffocatesAt(BlockPos)` (inject target, MixinLocalPlayer.java:15)
- `Level.hasChunkAt(BlockPos)` (PortalCollisionHandler.java:176; MixinEntity.java:229)

**Networking & position sync**
- Fabric: `CustomPacketPayload` + `Type`, `StreamCodec.of`, `PayloadTypeRegistry.playC2S/playS2C`, `ServerPlayNetworking.registerGlobalReceiver`, `ClientPlayNetworking.registerGlobalReceiver/createC2SPacket` (ImmPtlNetworking.java:45-267; ClientTeleportationManager.java:372)
- `FriendlyByteBuf.readVarInt/writeVarInt/readDouble/writeDouble/readUUID/writeUUID`
- `ServerGamePacketListenerImpl`: `teleport(double,double,double,float,float,Set<RelativeMovement>)` **@Overwrite** with fields `awaitingPositionFromClient/awaitingTeleport/awaitingTeleportTime/tickCount/clientIsFloating` (MixinServerGamePacketListenerImpl.java:48-95, 179-225) — **26.2 rewrote position sync (`PositionMoveRotation`-style); this is the highest-risk touchpoint**
- `ServerGamePacketListenerImpl.resetPosition()` (ServerTeleportationManager.java:351, 408); `.teleport(x,y,z,yaw,pitch)` (ServerTeleportationManager.java:398-405); `.handleMovePlayer` / `.handleAcceptTeleportPacket` / `.handlePlayerCommand` / `.isPlayerCollidingWithAnythingNew` (inject targets); `.disconnect(Component)`; `.isAcceptingMessages()` (MixinThrownEnderPearl.java:30)
- `ClientboundPlayerPositionPacket` (construction with relative bases + teleport id, MixinServerGamePacketListenerImpl.java:216-220), `ServerboundMovePlayerPacket` (`getX/getY/getZ(default)`), `ServerboundAcceptTeleportationPacket`, `RelativeMovement` set
- `PacketUtils.ensureRunningOnSameThread` (injection anchors, both sides)
- `ChunkMap.removeEntity(Entity)` inject + `ChunkMap.entityMap` + `updatePlayerStatus(ServerPlayer, boolean)` (MixinChunkMap_E.java:57-76)
- `ClientPacketListener.level` field swap (via duck, ClientTeleportationManager.java:471); `ClientPacketListener.handleMovePlayer` inject (client sync mixin)

**Client world/renderer swap** (touchpoints for the crossing itself)
- `Minecraft.level` (direct assign, ClientTeleportationManager.java:489); `Minecraft.levelRenderer` (via duck `IEMinecraftClient`); `Minecraft.particleEngine` world re-point (duck `IEParticleManager`); `Minecraft.getBlockEntityRenderDispatcher().setLevel(ClientLevel)` (ClientTeleportationManager.java:499); `GameRenderer` lightmap swap (duck `IEGameRenderer`, ClientTeleportationManager.java:485-487)
- `Minecraft.tick()` / `GameRenderer.render(DeltaTracker, boolean)` (hook sites); `DeltaTracker.getGameTimeDeltaPartialTick(true)` (MixinGameRenderer.java:86)
- `Camera.setup(BlockGetter, Entity, boolean, boolean, float)` (TransformationManager.java:230-238); `Minecraft.options.getCameraType()`
- `ClientLevel.tickNonPassenger` / `ServerLevel.tickNonPassenger` (hook sites)
- `Player.getViewXRot(partialTick)/getViewYRot(partialTick)` (TransformationManager.java:147)

**Sound (CrossPortalSound)**
- `ClientLevel.playSound(double,double,double,SoundEvent,SoundSource,float,float,boolean,long)` (inject target, MixinClientLevel_Sound.java:28-44); `SimpleSoundInstance(SoundEvent,SoundSource,float,float,RandomSource,double,double,double)` (CrossPortalSound.java:76-85); `SoundManager.play/playDelayed`; `RandomSource.create(long)`; `Holder`-based `SoundEvent` handling in 26.2 must be checked

**Entity queries & misc**
- `ServerLevel.getEntity(UUID)` (ServerTeleportationManager.java:239; MixinProjectile.java:19-33), `ServerLevel.getEntitiesOfClass(Class, AABB, Predicate)` (ServerTeleportationManager.java:149-153), `ServerLevel.getAllEntities()` (ServerTeleportationManager.java:489), `Level.getEntity(int)` (ClientTeleportationManager.java:704)
- `MinecraftServer.getLevel(ResourceKey)/getAllLevels/getPlayerList/getProfiler`
- `Mob.getTarget/setTarget`, `Mob.getNavigation().createPath(BlockPos,int)/moveTo(Path,double)`, `Mob.getMoveControl().setWantedPosition` (ServerTeleportationManager.java:769-820)
- `Projectile.getOwner` (redirect target); `ThrownEnderpearl.onHit`/`discard` (inject anchor)
- `ServerPlayer.absMoveTo(double,double,double,float,float)`; `ServerPlayer.server` field; `ServerPlayer.isSleeping`
- `Level.getGameTime`, `Level.dimension()`, `Level.isClientSide()`, profiler push/pop (`Profiler.get()` in 26.2 vs `level.getProfiler()` — both appear: CollisionHelper.java:404, MixinGameRenderer.java:74)
- `Entity.getType()`, `Entity.getUUID`, `Entity.getId`
- `BlockPos.containing(Vec3)`, `SectionPos.blockToSectionCoord`, `Vec3.atLowerCornerOf(Direction.getNormal())`, `Direction.getNearest(x,y,z)`, `Mth.clamp/floor`
- `LivingEntity` pose/dimensions (`getDimensions(Pose)`)
- Fabric events: `ServerTickEvents.END_SERVER_TICK` (ServerTeleportationManager.java:66; CollisionHelper.java:432)

---

## 5. Registration & wiring

**Server side** (all from `IPModMain.init`):
- `ImmPtlNetworking.init()` (IPModMain.java:67) — registers the C2S `TeleportPacket` payload type + global receiver (ImmPtlNetworking.java:238-255).
- `ServerTeleportationManager.init()` (IPModMain.java:87) — static event registration only; the **instance** lives in `IPPerServerInfo` (one per `MinecraftServer`, constructed eagerly — IPPerServerInfo.java:19) and is reached everywhere via `ServerTeleportationManager.of(server)`. Events: `ServerTickEvents.END_SERVER_TICK` (clear `teleportingEntities` + global-portal scan), `Portal.SERVER_PORTAL_TICK_SIGNAL` (fired from `Portal.tick()` server branch, Portal.java:952), `DimensionAPI.SERVER_PRE_REMOVE_DIMENSION_EVENT` (evacuate players to overworld spawn, ServerTeleportationManager.java:79-81, 826-845).
- `CollisionHelper.init()` (IPModMain.java:89) — `END_SERVER_TICK` colliding-portal update for all server worlds.

**Client side** (from `IPModMainClient.init`):
- `ClientTeleportationManager.init()` (IPModMainClient.java:74) — registers on `IPGlobal.POST_CLIENT_TICK_EVENT` (motion-affinity tick + `isTeleportingTick` reset) and `IPCGlobal.CLIENT_CLEANUP_EVENT` (null `lastPlayerEyePos`) (ClientTeleportationManager.java:84-93).
- `CollisionHelper.initClient()` (IPModMainClient.java:93) — `POST_CLIENT_TICK_EVENT` colliding-portal update for all client worlds + stagnate overlay.
- `ImmPtlNetworking.initClient()` (IPModMainClient.java:125).
- The *actual teleportation entry points* are mixin-driven, not event-driven: `MixinMinecraft.onAfterClientTick` (tick-time check, partial tick 0) and `MixinGameRenderer.onFarBeforeRendering` (frame-time check, real partial tick) both call `manageTeleportation` — order matters: `RenderStates.updatePreRenderInfo` and `ClientPortalAnimationManagement.update()` **must** run before it each frame (MixinGameRenderer.java:87-92).
- `ip_tickCollidingPortal` is wired per-entity from `ClientLevel.tickNonPassenger`/`ServerLevel.tickNonPassenger` HEAD.
- The S2C regular-entity position fix is q_misc_util's `McRemoteProcedureCall` targeting the **fully-qualified string** `"qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.RemoteCallables.updateEntityPos"` (ServerTeleportationManager.java:567-575) — renaming/moving the class breaks the protocol string.
- No entity types, blocks, or registries are registered by this slice; the only registry interaction is the payload types. Config knobs consumed: `IPGlobal.disableTeleportation/maxNormalPortalRadius/crossPortalCollision/enableServerCollision/allowClientEntityPosInterpolation/enableCrossPortalSound/teleportationDebugEnabled` and `IPConfig.serverTeleportLogging` (IPGlobal.java:19, 62, 82, 95, 117, 126).

---

## Surprises / fidelity landmines found while reading

1. **`acceptSynchronizationDataFromServer` is dead** — zero callers anywhere in IP 1.21.3 `src`. The live missed-crossing fallback is the move-packet dimension-tag protocol (3.6), not a server-pushed sync.
2. **A validated player crossing sends no S2C position packet** — `teleportPlayer` only calls `connection.resetPosition()` (ServerTeleportationManager.java:351). Only *failed* validation and force-teleports send `connection.teleport`.
3. **Mounted players bypass all teleport validation** — `validatePlayerTeleportationAndGetReason` returns valid immediately when `player.getVehicle() != null` (ServerTeleportationManager.java:270-272).
4. **No vanilla portal cooldown anywhere** — dedup is `lastTeleportGameTime` valves + per-frame combo limit; contrast with the current mod's `setPortalCooldown(300)` mirror-gate model, which has no IP analog.
5. **Regular entities are recreated but keep their network entity id** (`setId(oldId)`, ServerTeleportationManager.java:651, 697) — clients never see a remove+add pair for the crossing; players are never recreated at all.
6. **`MixinAbstractArrow` and `MixinThrowableProjectile` are fully commented out** in 1.21.3 — arrows/throwables have no special hit handling, only the segment-extension in `shouldEntityTeleport` and the cross-dim owner lookup.
7. Collision timing uses `Entity.tickCount`, deliberately not game time (client game time jumps on time-sync, MixinEntity.java:383-386).
8. `handleOtherSideMove` silently skips collision when the transformed box exceeds 100 blocks per axis or recursion depth ≥ 5 (PortalCollisionHandler.java:158-172).
