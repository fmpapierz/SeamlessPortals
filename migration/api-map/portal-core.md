# 26.2 API map: Portal entity core

Inventory source: `migration/inventory/portal-core.md` (§4 touchpoint list). IP citations = `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/imm_ptl/core/portal/` (1.21.3). 26.2 citations = `C:/Users/warwa/ModDev/mc262-ref` (Mojang mappings), path relative to that root unless noted. Render-pipeline facts defer to `MIGRATION_API_MAP.md` (pre-verified).

**Verdict tally: 3 GONE, 24 CHANGED, 108 SAME, 5 FABRIC-API (counted separately).**

Two cross-cutting renames apply to nearly every file and are listed once under CHANGED, not repeated per-row: `ResourceLocation` → `Identifier`, and `net.minecraft.Util` → `net.minecraft.util.Util`.

---

## 1. GONE (no member; a different 26.2 mechanism fills the role)

| # | IP usage | 26.2 mechanism | Migration note |
|---|---|---|---|
| G1 | `Entity.getBoundingBox()` **as an override point** — Portal overrides it to serve `boundingBoxCache` (Portal.java:572-578) | `Entity.getBoundingBox()` is now `public final AABB getBoundingBox() { return this.bb; }` (`net/minecraft/world/entity/Entity.java:3426-3429`); the box is stored via `public final void setBoundingBox(AABB)` (`:3431-3433`) and vanilla refreshes it in `setPos`: `this.setBoundingBox(this.makeBoundingBox())` (`Entity.java:472-475`) | The override is impossible. The vanilla `bb` field IS the cache: put the shape-derived box in the `makeBoundingBox(Vec3)` override (see C2) and make `Portal.updateCache()` call `setBoundingBox(makeBoundingBox())` explicitly whenever geometry fields change (axes/extents/shape setters, `ip_onEntityPositionUpdated`). The `NULL_BOX` sentinel logic moves into `makeBoundingBox(Vec3)`. `refreshDimensions()` override (currently "null the cache") should call `setBoundingBox` too — vanilla's `refreshDimensions` (`Entity.java:3366-3382`) calls `reapplyPosition()` → `setPos` which invokes `makeBoundingBox`, so overriding may not even be needed; verify against Portal's intent of avoiding `fudgePositionAfterSizeChange`. |
| G2 | `CompoundTag.hasUUID/getUUID/putUUID` — `specificPlayer` (Portal.java:375-377 write, read via `Helper.getUuid`); `reversePortalId`/`flippedPortalId`/`parallelPortalId` (PortalExtension.java:82-138) | No UUID members exist on `CompoundTag` (verified: full getter list `net/minecraft/nbt/CompoundTag.java:283-373` has none). Codec route: `tag.store(name, UUIDUtil.CODEC, uuid)` (`CompoundTag.java:490`), `tag.read(name, UUIDUtil.CODEC)` → `Optional<UUID>` (`CompoundTag.java:518`); `UUIDUtil.CODEC` = int-stream codec (`net/minecraft/core/UUIDUtil.java:23`) — same int-array wire format as the old `putUUID`. On the ValueInput/ValueOutput path: `input.read(name, UUIDUtil.CODEC)` (`ValueInput.java:10`) / `output.store(name, UUIDUtil.CODEC, uuid)` (`ValueOutput.java:8`). | Presence check (`hasUUID`) becomes `read(...).isPresent()`. Old-world NBT stays readable (int-array format unchanged). |
| G3 | `net.minecraft.util.Tuple` — `Tuple<Direction, Direction> = Helper.getPerpendicularDirections(facing)` (BreakableMirror.java:199; import at BreakableMirror.java:7) | Class absent from 26.2 entirely (no `Tuple.java` anywhere under `mc262-ref`; verified by full-tree find). Vanilla uses records and `com.mojang.datafixers.util.Pair` (library still on classpath — e.g. `import com.mojang.datafixers.util.Pair` in `net/minecraft/world/level/biome/BiomeSource.java`) | The type flows out of IP's own `q_misc_util.Helper.getPerpendicularDirections` signature — the replacement type (Pair or an own record) is a q-misc-util-slice decision; BreakableMirror just follows it. |

---

## 2. CHANGED (exists, but signature/location/semantics moved)

### 2.1 Entity persistence — the big one

| # | IP usage | 26.2 replacement + citation | Migration note |
|---|---|---|---|
| C1 | `readAdditionalSaveData(CompoundTag)` / `addAdditionalSaveData(CompoundTag)` overrides (Portal.java:233,366; BreakableMirror.java:43-91; LoadingIndicatorEntity.java:115-123; PortalState.toTag/fromTag are IP-internal CompoundTag and unaffected) | `protected abstract void readAdditionalSaveData(ValueInput input)` / `addAdditionalSaveData(ValueOutput output)` (`net/minecraft/world/entity/Entity.java:2204-2206`). Whole save chain moved: `saveAsPassenger(ValueOutput)` `:2040`, `save(ValueOutput)` `:2055`, `saveWithoutId(ValueOutput)` `:2059`, `load(ValueInput)` `:2133`. ValueInput API: `read(String,Codec)`, `child/childOrEmpty`, `listOrEmpty(String,Codec)`, `getBooleanOr/getIntOr/getFloatOr/getDoubleOr/getString/getInt` (`net/minecraft/world/level/storage/ValueInput.java:10-49`); ValueOutput: `store(String,Codec,T)`, `putDouble/putBoolean/putString/putInt`, `child`, `list(String,Codec)`, `discard` (`ValueOutput.java:8-39`) | **The CompoundTag wire format survives via the bridge classes**: `TagValueOutput.createWithContext(ProblemReporter, HolderLookup.Provider)` → write → `buildResult()` returns `CompoundTag` (`net/minecraft/world/level/storage/TagValueOutput.java:27,152`); `TagValueInput.create(ProblemReporter, HolderLookup.Provider, CompoundTag)` returns `ValueInput` (`TagValueInput.java:40`). This is how `createSyncPacket` (build full NBT via `addAdditionalSaveData`), `acceptDataSync` (read NBT), `writePortalDataToNbt`/`readPortalDataFromNbt`/`updatePortalFromNbt` keep sending/merging `CompoundTag` over the custom packet while the entity overrides take Value types. `HolderLookup.Provider` comes from `level().registryAccess()`. |
| C2 | `makeBoundingBox()` no-arg override (Portal.java:961) | No-arg is now `protected final AABB makeBoundingBox() { return this.makeBoundingBox(this.position); }` (`Entity.java:477-479`); the override point is `protected AABB makeBoundingBox(Vec3 position)` (`Entity.java:481-483`) | Override the `Vec3` form; compute the shape box from the passed position (not `position()`) so vanilla's `setPos` pre-move call sites stay coherent. Pairs with G1. |
| C3 | NBT contains-guarded optional reads (`contains("scale")` etc., Portal.java:298-357) | `ValueInput` has **no `contains`**; presence = Optional getters (`getInt/getString/getLong` return `Optional`, `ValueInput.java:33,39,45`) or defaulted `getXxxOr` (`:27-47`). `CompoundTag.contains(String)` one-arg still exists (`CompoundTag.java:275-277`); the two-arg type-checked `contains(String,int)` is gone | `getDoubleOr("scale", 1.0)`, `getBooleanOr("teleportChangesGravity", configDefault)` (exact semantics of the old absent→config-default read, Portal.java:312-317), `getBooleanOr("isVisible", true)`, etc. Doubles have no Optional variant on ValueInput — only `getDoubleOr`. |
| C4 | **Rotation quirk**: write four doubles, read with `getFloat` numeric coercion (Portal.java:290-297, 384-389) | Coercion is preserved on both paths: `CompoundTag.getFloatOr` = `instanceof NumericTag tag ? tag.floatValue() : default` (`CompoundTag.java:319-321`); `TagValueInput.getFloatOr` identical (`TagValueInput.java:185-188`) | Port as `getFloatOr("rotationB", 0f)` etc.; the double-tag→float coercion (and its precision loss) reproduces exactly. Keep the (B,C,D,A)=(x,y,z,w) reassembly order. |
| C5 | `CompoundTag.getAllKeys()` (Portal.java:1772 merge loop) | Renamed `keySet()` (`CompoundTag.java:193-195`) | `updatePortalFromNbt` merge: `CompoundTag.merge(CompoundTag)` also still exists (`CompoundTag.java:435`). |
| C6 | `CompoundTag.getList(key, typeId)` with type ids 6/8 (Portal.java:264,336) | `getList(String)` → `Optional<ListTag>` / `getListOrEmpty(String)` (`CompoundTag.java:359-365`) — no element-type argument; element type is checked per-element (`Tag.asString()` → `Optional<String>`, see `StringTag.asString` `net/minecraft/nbt/StringTag.java:92`) | For `commandsOnTeleported` on the ValueInput path use `listOrEmpty(name, Codec.STRING)` (`ValueInput.java:25`). The legacy `specialShape` doubles list (GeometryPortalShape) reads via `getListOrEmpty` + per-element numeric access. |
| C7 | `CompoundTag.getDouble/getBoolean/getString/getInt/getFloat/getCompound` plain-typed getters (throughout NBT code + `Helper.getVec3d`) | All return `Optional<T>` now; defaulted variants added: `getDoubleOr/getBooleanOr/getStringOr/getIntOr/getFloatOr/getCompoundOrEmpty` (`CompoundTag.java:283-373`) | Mechanical: always-present keys → `getXxxOr(key, 0)`; IP's `Helper.getVec3d(tag, prefix)` internals migrate in the q-misc-util slice. |

### 2.2 Entity / EntityType

| # | IP usage | 26.2 replacement + citation | Migration note |
|---|---|---|---|
| C8 | `Entity.getServer()` (Portal.java:542,1311) | Method no longer exists on `Entity` (grep: only `level.getServer()`/`serverLevel.getServer()` remain, e.g. `Entity.java:1305,3625`). Use `level().getServer()` — `public @Nullable MinecraftServer getServer()` (`net/minecraft/world/level/Level.java:168`) | Same nullability contract as before. |
| C9 | `EntityType.create(Level)` (PortalManipulation.java:91,134,161; BreakableMirror.java:173; EndPortalEntity.java:150; GlobalPortalStorage recreation) | `public @Nullable T create(Level level, EntitySpawnReason reason)` (`net/minecraft/world/entity/EntityType.java:298`); no single-arg overload exists (the other overloads: `create(Level, EntitySpawnRequest)` `:302`, static `create(ValueInput, Level, EntitySpawnRequest)` `:306`, `create(EntityType, ValueInput, Level, EntitySpawnReason)` `:314`) | Use `EntitySpawnReason.LOAD` (`net/minecraft/world/entity/EntitySpawnReason.java:21`) — the current mod already does exactly this on 26.2 (`migration/inventory/current-mod-core.md:342`). |
| C10 | `EntityType` built without an id, registered later (`createPortalEntityType`, Portal.java:94-108, static `ENTITY_TYPE` fields on every portal class) | `EntityType.Builder.build(ResourceKey<EntityType<?>>)` **requires the registry key at build time** (`EntityType.java:590`); builder entry `Builder.of(EntityFactory, MobCategory)` (`:479`) | Structural ripple: `createPortalEntityType` must take the id (`ResourceKey.create(Registries.ENTITY_TYPE, Identifier...)`), so each subclass's static `ENTITY_TYPE` needs its id at class-init — or type creation moves into the registration function. Interacts with F1 (FabricEntityTypeBuilder replacement). |
| C11 | `Entity.setRemoved(RemovalReason)` called directly (Portal.java:1764) and mixin target (MixinEntity_U setRemoved RETURN) | Now `public final void setRemoved(Entity.RemovalReason reason)` (`Entity.java:3912`); tail order: `this.levelCallback.onRemove(reason)` is the **penultimate** statement (`:3922`) and the body ends with `this.onRemoval(reason)` (`:3923`) | Direct calls compile unchanged; `@Inject` into a final method is legal, so MixinEntity_U's RETURN inject carries over — keep it `@At("RETURN")`. Do NOT re-anchor it as `@At(INVOKE, target=onRemove, shift=AFTER)` on the assumption that `onRemove` is the last call: that inject would run before `onRemoval(reason)`. Only an `@Override` (which IP never does) would break. |
| C12 | `Entity.getViewVector(float)` called (PortalUtils.java:75-76,93-94) | Now `public final Vec3 getViewVector(float a)` (`Entity.java:1929-1931`) | Call sites unchanged; flagged only because finality breaks any override elsewhere in the mod. |

### 2.3 Renames / moves (mechanical)

| # | IP usage | 26.2 replacement + citation | Migration note |
|---|---|---|---|
| C13 | `ResourceLocation` (registration ids, dim ids in `Helper.getWorldId`, everywhere) | **Renamed `Identifier`** — `public final class Identifier` (`net/minecraft/resources/Identifier.java:18`), factories `fromNamespaceAndPath` (`:40`), `withDefaultNamespace` (`:48`); e.g. `Level.END = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("the_end"))` (`Level.java:97`) | Cross-cutting rename over the whole port; the mod (already on 26.2) has this absorbed — IP code being ported must adopt it. |
| C14 | `net.minecraft.Util` (`Util.NIL_UUID`, Portal.java:130,713) | Package moved: `net.minecraft.util.Util`; `public static final UUID NIL_UUID = new UUID(0L, 0L)` (`net/minecraft/util/Util.java:121`) | Import-only change; NIL_UUID semantics identical (specificPlayerId "non-players only" sentinel keeps working). |
| C15 | `Minecraft.getInstance().getProfiler()` (PortalRenderInfo.java:213,236-249) | No `getProfiler()` on `Minecraft` (grep: only internal `metricsRecorder.getProfiler()` at `Minecraft.java:1395`). Replacement: static `Profiler.get()` → `ProfilerFiller` (`net/minecraft/util/profiling/Profiler.java:47`); vanilla pattern: `ProfilerFiller profiler = Profiler.get(); profiler.push(...)` (`Entity.java:511-513`) | `push`/`pop` calls unchanged on the returned `ProfilerFiller`. |
| C16 | `Minecraft.getInstance().gui.setOverlayMessage(text, false)` (LoadingIndicatorEntity.java:151-154) | Moved to the HUD object: `minecraft.gui.hud.setOverlayMessage(Component, boolean)` — `Gui.hud` is `public final Hud hud` (`net/minecraft/client/gui/Gui.java:72`); `public void setOverlayMessage(Component string, boolean animate)` (`net/minecraft/client/gui/Hud.java:1225-1227`); vanilla call site `this.minecraft.gui.hud.setOverlayMessage(packet.text(), false)` (`net/minecraft/client/multiplayer/ClientPacketListener.java:1942`) | One extra `.hud` hop. |
| C17 | `Level.isClientSide` public field (BreakableMirror.java:96 and general IP style) | Field is `private final boolean isClientSide` (`Level.java:127`); accessor `public boolean isClientSide()` (`Level.java:163`) | Use the method form everywhere. |
| C18 | `Direction.getNearest(Vec3)` / `(x,y,z)` (Portal.getApproximateFacingDirection :1056-1060, getTransformedGravityDirection :1655-1663) | Renamed `getApproximateNearest`: `(double,double,double)` `net/minecraft/core/Direction.java:303`, `(float,float,float)` `:307`, `(Vec3)` `:322`. The surviving `getNearest(int x,int y,int z, Direction orElse)` overloads (`:327-343`) are a different (exact-int) API | Vanilla's own usage: `Direction.getApproximateNearest(this.getViewVector(1.0F))` (`Entity.java:1934`). Same selection semantics as the old method. |
| C19 | `Direction.getNormal()` (inventory math cluster) | Renamed `getUnitVec3i()` → `Vec3i` (`Direction.java:375`) | Mechanical rename. |
| C20 | `BlockPos.getBottomCenter()` (EndPortalEntity.java:105) | Gone from `BlockPos` (grep: no match); use `Vec3.atBottomCenterOf(Vec3i)` (`net/minecraft/world/phys/Vec3.java:57`) | `pos.getBottomCenter()` → `Vec3.atBottomCenterOf(pos)`. |
| C21 | `new ChunkPos(BlockPos)` (Portal.java:1868) | `ChunkPos` is now `public record ChunkPos(int x, int z)` (`net/minecraft/world/level/ChunkPos.java:19`); BlockPos conversion is `ChunkPos.containing(BlockPos)` (`:45-47`) | Also: `.x`/`.z` field reads become record accessors `x()`/`z()`. |
| C22 | `BlockBehaviour.Properties.noCollission()` (PortalPlaceholderBlock.java:57-65) | Renamed `noCollision()` (`net/minecraft/world/level/block/state/BlockBehaviour.java:1065`) | Mojang fixed the typo. |
| C23 | `Properties.of()` chain with no id (PortalPlaceholderBlock singleton `instance`) | `Properties.of()` survives (`BlockBehaviour.java:1000`) but block registration requires `setId(ResourceKey<Block>)` (`BlockBehaviour.java:1263`) before the Block ctor (1.21.2+ discipline; vanilla sets it in `Blocks.register`) | The `instance` construction must add `.setId(ResourceKey.create(Registries.BLOCK, Identifier...))` matching the `immersive_portals:nether_portal_block`-analog id used at registration. |
| C24 | `EndDragonFight` (+ `IEEndDragonFight` duck, EndPortalEntity.java:90-97); `Arrow` (EndPortalEntity canTeleportEntity :298-313); `AbstractMinecart` (Portal.java:1080) | `EndDragonFight` → **`EnderDragonFight`** (`net/minecraft/world/level/dimension/end/EnderDragonFight.java:68`; `ServerLevel.getDragonFight()` returns it, `net/minecraft/server/level/ServerLevel.java:1654`). `Arrow` → package `net.minecraft.world.entity.projectile.arrow.Arrow` (dir: `projectile/arrow/{AbstractArrow,Arrow,SpectralArrow,ThrownTrident}.java`). `AbstractMinecart` → `net.minecraft.world.entity.vehicle.minecart.AbstractMinecart` | Class renames/package moves only; the `IEEndDragonFight` mixin duck retargets to `EnderDragonFight` (its internals re-verified in the mixin slice). |

---

## 3. SAME (verified compatible in 26.2)

### 3.1 Entity base class (all `net/minecraft/world/entity/Entity.java`)

| Touchpoint | 26.2 citation |
|---|---|
| `Entity(EntityType<?>, Level)` ctor | `:304` |
| `defineSynchedData(SynchedEntityData.Builder)` (builder form) | `:414` (abstract), invoked `:321` |
| `tick()` / `super.tick()` | `:507-509` |
| `move(MoverType, Vec3)` | `:712` |
| `refreshDimensions()` | `:3366` (see G1 note re: behavior) |
| `broadcastToPlayer(ServerPlayer)` → boolean | `:3422-3424` |
| `getAddEntityPacket(ServerEntity)` → `Packet<ClientGamePacketListener>` | `:3672-3674` |
| `position()` | `:3685-3687` |
| `setPos(Vec3)` / `setPos(x,y,z)` | `:468` / `:472-475` |
| `setPosRaw(double,double,double)` (final, as before) | `:3786` |
| `getX()` (+ getY/getZ family) | `:3734` |
| `getId()` | `:381` |
| `getUUID()` | `:3246` |
| `getType()` | `:363` |
| `level()` | `:3958` |
| `remove(Entity.RemovalReason)`; `RemovalReason.KILLED` | `:430-431`; usage `:406` |
| `unsetRemoved()` (protected — `myUnsetRemoved` exposure still needed) | `:3926` |
| `isRemoved()` | `:3903` |
| `tickCount` public field | `:247` |
| `getEntityData()` | `:416` |
| `getLookAngle()` | `:2562-2564` |
| `getEyePosition()` / `getEyePosition(float)` | `:1971` / `:1975` |
| `getDeltaMovement()` / `setDeltaMovement(Vec3)` | `:3710` / `:3714` |
| **Mixin target**: `setPosRaw` → `this.levelCallback.onMove()` call site | `:3800` |
| **Mixin target**: `setRemoved` → `levelCallback.onRemove(reason)` penultimate at `:3922`, followed by the final statement `onRemoval(reason)` `:3923` | `:3912-3924` (method now final — RETURN inject still valid, see C11) |
| `EntityInLevelCallback.onMove()` / `.onRemove(RemovalReason)` | `net/minecraft/world/level/entity/EntityInLevelCallback.java:16-18` |

### 3.2 Entity typing / registration

| Touchpoint | 26.2 citation |
|---|---|
| `EntityType.EntityFactory<T>` — `T create(EntityType<T>, Level)` | `EntityType.java:615-618` |
| `MobCategory.MISC` | `net/minecraft/world/entity/MobCategory.java:14` |
| `EntityDimensions.fixed(w,h)` | `net/minecraft/world/entity/EntityDimensions.java:45` |
| `EntityType.Builder.of/sized/fireImmune/clientTrackingRange/updateInterval/noSave` | `EntityType.java:479,487,550,565,570,545` (note `clientTrackingRange` is in **chunks** — Fabric's `trackRangeBlocks(96)` ≙ 6) |
| `EntityType.trackDeltas()` — velocity updates default **true** for non-listed (i.e. all modded) types | `EntityType.java:418-424` (consumed at `net/minecraft/server/level/ChunkMap.java:1150`) — Fabric's `forceTrackedVelocityUpdates(true)` is the 26.2 vanilla default |
| `Registry.register(Registry, Identifier, T)` / `(Registry, ResourceKey, T)` | `net/minecraft/core/Registry.java:111,115` |
| `BuiltInRegistries.ENTITY_TYPE` | `net/minecraft/core/registries/BuiltInRegistries.java:186` |
| `EntitySpawnReason.LOAD` | `EntitySpawnReason.java:21` |

### 3.3 Entity data sync (LoadingIndicatorEntity)

| Touchpoint | 26.2 citation |
|---|---|
| `SynchedEntityData.defineId(Class, EntityDataSerializer)` | `net/minecraft/network/syncher/SynchedEntityData.java:29` |
| `SynchedEntityData.Builder.define(accessor, value)` | `SynchedEntityData.java:146` |
| `EntityDataSerializers.COMPONENT` | `net/minecraft/network/syncher/EntityDataSerializers.java:58` |
| `EntityDataSerializers.BLOCK_POS` | `EntityDataSerializers.java:94` |

### 3.4 Networking (vanilla side)

| Touchpoint | 26.2 citation |
|---|---|
| `Packet<ClientGamePacketListener>` return type | `ClientGamePacketListener` exists (`net/minecraft/network/protocol/game/ClientGamePacketListener.java:7`); used at `Entity.java:3672` |
| `ServerEntity` (spawn-packet param; resync-suppression mixin target) | `net/minecraft/server/level/ServerEntity.java:50`; `sendChanges()` `:88`, `positionCodec` `:61` |
| `CustomPacketPayload` / `StreamCodec` / `RegistryFriendlyByteBuf` | files exist: `net/minecraft/network/protocol/common/custom/CustomPacketPayload.java`, `net/minecraft/network/codec/StreamCodec.java`, `net/minecraft/network/RegistryFriendlyByteBuf.java` |
| `ByteBufCodecs.registry(ResourceKey<Registry<T>>)` | `net/minecraft/network/codec/ByteBufCodecs.java:582` |
| `ClientboundCustomPayloadPacket` (the vanilla payload→`Packet` wrapper both loaders build on — see F3) | `net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket.java` exists |

### 3.5 NBT (surviving members)

| Touchpoint | 26.2 citation |
|---|---|
| `CompoundTag.putDouble/putString/putInt/putBoolean` | `CompoundTag.java:247,251,235,267` |
| `CompoundTag.contains(String)` (one-arg) | `CompoundTag.java:275-277` |
| `CompoundTag.merge(CompoundTag)` | `CompoundTag.java:435` |
| `CompoundTag.store(String,Codec,T)` / `read(String,Codec)` | `CompoundTag.java:490` / `:518` |
| `ListTag` / `StringTag.valueOf` / `StringTag.asString()` | `net/minecraft/nbt/StringTag.java:56` / `:92`; `ListTag` per `CompoundTag.java:359-365` |
| `TagValueInput.create(ProblemReporter, HolderLookup.Provider, CompoundTag)` | `TagValueInput.java:40` |
| `TagValueOutput.createWithContext(...)` / `.buildResult()` | `TagValueOutput.java:27` / `:152` |

### 3.6 Level / dimension / chunk

| Touchpoint | 26.2 citation |
|---|---|
| `Level.isClientSide()` method | `Level.java:163` |
| `Level.dimension()` | `Level.java:960` |
| `Level.getGameTime()` | default on `LevelAccessor` (`net/minecraft/world/level/LevelAccessor.java:41`); called as `this.getGameTime()` in `Level.java:903` |
| `Level.getBlockState(BlockPos)` | `Level.java:357` |
| `Level.getRandom()` | `Level.java:965` |
| `Level.addParticle(...)` | `Level.java:447,450` |
| `Level.clip(ClipContext)` | default on `BlockGetter` (`net/minecraft/world/level/BlockGetter.java:65`) |
| `Level.addFreshEntity(Entity)` | default on `LevelWriter` (`net/minecraft/world/level/LevelWriter.java:28`) |
| `Level.getServer()` | `Level.java:168` |
| `Level.getEntities()` protected `LevelEntityGetter<Entity>` (the `IEWorld.portal_getEntityLookup` duck target) | `Level.java:1000`; uuid lookup pattern `this.getEntities().get(uuid)` `Level.java:782` |
| `Level.END` / `Level.OVERWORLD` | `Level.java:97` / `:95` |
| `ServerLevel.END_SPAWN_POINT` | `ServerLevel.java:186` |
| `ServerLevel.getDragonFight()` | `ServerLevel.java:1654` (returns renamed `EnderDragonFight`, C24) |
| `MinecraftServer.getLevel(ResourceKey<Level>)` | `net/minecraft/server/MinecraftServer.java:1189` |
| `WorldBorder.isWithinBounds(BlockPos)` | `net/minecraft/world/level/border/WorldBorder.java:48` |
| `SectionPos.of(int,int,int)/of(BlockPos)/of(Position)`; `x()/y()/z()` | `net/minecraft/core/SectionPos.java:41-61`; `:148-156` |

### 3.7 Math / geometry

| Touchpoint | 26.2 citation |
|---|---|
| `Vec3.ZERO` / `atLowerCornerOf` / `atCenterOf` (+ arithmetic methods) | `Vec3.java:37` / `:45` / `:53` |
| `AABB.minmax/move/intersects/getCenter` | `net/minecraft/world/phys/AABB.java:213,223-237,241-255,445` |
| `Mth.clamp(double)` / `Mth.lerp(double)` | `net/minecraft/util/Mth.java:106` / `:558` |
| `BlockPos.containing` / `ZERO` / `relative` | `BlockPos.java:90` / `:47` / `:189-199` |
| `Shapes.create(AABB)` / `VoxelShape` | `net/minecraft/world/phys/shapes/Shapes.java:98` |
| `Direction.getOpposite()` / `getAxis()` / `Direction.Axis` | `Direction.java:167` / `:267` |
| JOML `Matrix4d/Matrix4f/Matrix3d/Quaterniond` | JOML still bundled — `Matrix4fc` in vanilla signatures (`LevelRenderer.render`, MIGRATION_API_MAP.md "LevelRenderer" section) |

### 3.8 Client-only

| Touchpoint | 26.2 citation |
|---|---|
| `Minecraft.getInstance().player` | `Minecraft.java:336` (`public @Nullable LocalPlayer player`) |
| `Minecraft.gui` | `Minecraft.java:290` |
| `LocalPlayer` | `net/minecraft/client/player/LocalPlayer.java` exists |
| `ParticleTypes.PORTAL` | `net/minecraft/core/particles/ParticleTypes.java:107` |
| `Component.literal(String)` | `net/minecraft/network/chat/Component.java:135` |
| IP `GlQueryObject`/`QueryManager` raw-GL occlusion queries | vanilla-independent; 26.2 still runs a GL backend behind `RenderSystem.getDevice()` (raw-GL interop verified for the stencil work — see MIGRATION_API_MAP.md RenderPass section + PHASE5 stencil findings). Frame-index/rotation coupling is IP-internal (`RenderStates`). The only vanilla API in `PortalRenderInfo` is the profiler → C15. |

### 3.9 Block API (PortalPlaceholderBlock)

| Touchpoint | 26.2 citation |
|---|---|
| `updateShape(BlockState, LevelReader, ScheduledTickAccess, BlockPos, Direction, BlockPos, BlockState, RandomSource)` — **identical to IP's 1.21.2+ signature** | `BlockBehaviour.java:145-156` |
| `getShape(BlockState, BlockGetter, BlockPos, CollisionContext)` | `BlockBehaviour.java:309` |
| `propagatesSkylightDown(BlockState)` single-arg | `BlockBehaviour.java:381` |
| `getRenderShape(BlockState)`; `RenderShape.INVISIBLE` | `BlockBehaviour.java:209`; `net/minecraft/world/level/block/RenderShape.java:4` (enum now just INVISIBLE/MODEL) |
| `getShadeBrightness(BlockState, BlockGetter, BlockPos)` | `BlockBehaviour.java:301` |
| `Properties.sound/strength/noOcclusion/noLootTable/lightLevel` | `BlockBehaviour.java:1096,1106,1071,1129,1101` |
| `BlockStateProperties.AXIS` | `net/minecraft/world/level/block/state/properties/BlockStateProperties.java:46` |
| `Block.box(...)` | `net/minecraft/world/level/block/Block.java:160` |
| `registerDefaultState` / `getStateDefinition` / `createBlockStateDefinition` | `Block.java:509` / `:505` / `:502` |
| `Blocks.GLASS` / `Blocks.GLASS_PANE` | `net/minecraft/world/level/block/Blocks.java:646` / `:2358` |
| `StainedGlassBlock` / `StainedGlassPaneBlock` | both files exist under `net/minecraft/world/level/block/` |
| `SoundType.GLASS` | `net/minecraft/world/level/block/SoundType.java:34` |
| `BlockState.isAir/getValue/getCollisionShape` | `BlockBehaviour.java:568` (isAir), `:665-669` (getCollisionShape); getValue via `StateHolder` as before |

### 3.10 Raytrace / interaction

| Touchpoint | 26.2 citation |
|---|---|
| `ClipContext(Vec3, Vec3, Block, Fluid, Entity)` | `net/minecraft/world/level/ClipContext.java:28-30` (delegates to a new `CollisionContext` overload `:32` — entity form retained) |
| `BlockHitResult.getBlockPos/getDirection/getType` | `net/minecraft/world/phys/BlockHitResult.java:46,50,55` |
| `HitResult.getLocation()` / `HitResult.Type.BLOCK` | `net/minecraft/world/phys/HitResult.java:21` / `:25` |

### 3.11 Gameplay (EndPortalEntity)

| Touchpoint | 26.2 citation |
|---|---|
| `MobEffects.SLOW_FALLING` (`Holder<MobEffect>`) | `net/minecraft/world/effect/MobEffects.java:105` |
| `MobEffectInstance(Holder, int, int)` | `net/minecraft/world/effect/MobEffectInstance.java:57` |
| `LivingEntity.addEffect(MobEffectInstance)` / `(…, Entity)` | `net/minecraft/world/entity/LivingEntity.java:1010` / `:1014` |
| `ServerPlayer.gameMode` (public final) | `net/minecraft/server/level/ServerPlayer.java:233` |
| `ServerPlayerGameMode.getGameModeForPlayer()` | `net/minecraft/server/level/ServerPlayerGameMode.java:91` |
| `GameType.CREATIVE` | `net/minecraft/world/level/GameType.java:18` |
| `EquipmentSlot.CHEST` | `net/minecraft/world/entity/EquipmentSlot.java:17` |
| `LivingEntity.getItemBySlot(EquipmentSlot)` | `LivingEntity.java:2270` |
| `Items.ELYTRA` | `net/minecraft/world/item/Items.java:894` |
| `EndPlatformFeature.createEndPlatform(ServerLevelAccessor, BlockPos, boolean)` | `net/minecraft/world/level/levelgen/feature/EndPlatformFeature.java:21` |

### 3.12 Misc

| Touchpoint | 26.2 citation |
|---|---|
| `RandomSource` | e.g. `BlockBehaviour.java:153` (updateShape param), `Level.getRandom()` `Level.java:965` |
| `com.mojang.datafixers.util.Pair` | library on classpath — imported by `net/minecraft/world/level/biome/BiomeSource.java` et al. |
| `UUIDUtil.CODEC` | `net/minecraft/core/UUIDUtil.java:23` |

---

## 4. FABRIC-API touchpoints (route through the mod's loader abstraction)

The mod is multiloader (common/fabric/neoforge) with a `PlatformHelper` ServiceLoader seam and per-loader payload registration (`migration/inventory/current-mod-core.md` §2.3 :89-113, §4 :351). None of these may appear in `common/` as Fabric types.

| # | IP usage | Note |
|---|---|---|
| F1 | `FabricEntityTypeBuilder.create(...).dimensions(...).fireImmune().trackRangeBlocks(96).trackedUpdateRate(20).forceTrackedVelocityUpdates(true).build()` (Portal.java:97-107); `.trackable(96, 20)` (LoadingIndicatorEntity.java:26-32) | Deprecated upstream. Vanilla `EntityType.Builder` covers everything: `sized` (`EntityType.java:487`), `fireImmune` (`:550`), `clientTrackingRange(6)` — **chunks**, 96 blocks/16 (`:565`), `updateInterval(20)` (`:570`), and `forceTrackedVelocityUpdates(true)` is already the vanilla default for modded types (`trackDeltas()`, `:418-424`). `build(ResourceKey)` per C10. Loader-neutral: build in common with vanilla Builder; no Fabric type needed at all. |
| F2 | Fabric `EntityRendererRegistry.register(entityType, provider)` (IPModEntryClient.java:41-61) | Per-loader: Fabric keeps `EntityRendererRegistry`; NeoForge uses `EntityRenderersEvent.RegisterRenderers`. Route through the mod's client platform hook. |
| F3 | `ServerPlayNetworking.createS2CPacket(CustomPacketPayload)` cast to `Packet<ClientGamePacketListener>` (Portal.java:904-919, the `getAddEntityPacket` return) | The vanilla mechanism underneath is `new ClientboundCustomPayloadPacket(payload)` (`net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket.java`) — both loaders can construct it; the contravariance cast noted at Portal.java:911 still applies. Needs a `PlatformHelper`-style "payload → vanilla Packet" hook so `getAddEntityPacket` stays in common. |
| F4 | `PayloadTypeRegistry` + `ClientPlayNetworking`/`ServerPlayNetworking` registration/receivers (ImmPtlNetworking.java:134-267 — the PortalSyncPacket contract) | Already-solved seam in the mod: `ModPayloads` + `FabricPlatformHelper.registerPayloads` / NeoForge `RegisterPayloadHandlersEvent` (current-mod-core.md:351,377). IP's registration must be poured into it. |
| F5 | `net.fabricmc.fabric.api.event.Event` — the seven static events on `Portal` (Portal.java:89-219, built by IP `Helper.createConsumerEvent`) plus `PortalExtension.init` / `PortalRenderInfo.init` subscriptions | Fabric-API type in would-be common code. Needs a loader-neutral event object (the q_misc_util slice owns `Helper.createConsumerEvent`; any self-made multicast list preserves IP semantics — synchronous, registration-order invoke). Flagged here because the portal-core public API (`Portal.CLIENT_PORTAL_TICK_SIGNAL` etc.) is typed by it. |

---

## 5. Semantic hazards (port-review checklist)

1. **bbox caching inversion (G1/C2):** IP pattern = lazy cache in `getBoundingBox()` override; 26.2 pattern = eager `setBoundingBox(makeBoundingBox())`. Every `updateCache()` call site must now *push* the box; missing one leaves a stale `bb` that vanilla ticking will happily use. The `NULL_BOX`-before-axes-set window must return the sentinel from `makeBoundingBox(Vec3)`.
2. **NBT double-write/float-read rotation quirk (C4)** survives via `NumericTag` coercion on both `CompoundTag.getFloatOr` and `TagValueInput.getFloatOr` — but only through the `...Or` variants; the Optional `getFloat` also coerces (`Tag::asFloat`), so either works. Keep the (B,C,D,A) order.
3. **Sync packet keeps CompoundTag** (`PortalSyncPacket` carries full NBT): only the entity-override signatures change; bridge with `TagValueOutput.createWithContext(...).buildResult()` server-side and `TagValueInput.create(...)` in `acceptDataSync`/spawn handling. `ProblemReporter` + `registryAccess()` plumbing is new boilerplate at each wrapper (`writePortalDataToNbt`/`readPortalDataFromNbt`/`updatePortalFromNbt`).
4. **`EntityType` ids at class-init (C10):** the static-field self-instantiation pattern (`Portal.ENTITY_TYPE = createPortalEntityType(...)`) needs the id before `build()`; decide once (pass id into the factory) and apply to all five entity types in this slice.
5. **`clientTrackingRange` unit trap (F1):** Fabric's `trackRangeBlocks(96)` ≙ vanilla `clientTrackingRange(6)` (chunks). Writing `96` there would create a 1536-block tracking radius.
6. **`Entity.getServer()` (C8)** appears in validity checking (`isPortalValid`) — `level().getServer()` is @Nullable exactly like before; no semantic change, but it's an easy silent-miss in a mass find/replace because the method name stays identical.
7. **Profiler (C15)** is per-thread static now (`Profiler.get()`); `PortalRenderInfo.renderAndDecideVisibility` runs on the render thread where vanilla has an active profiler during the frame — same push/pop discipline required.
