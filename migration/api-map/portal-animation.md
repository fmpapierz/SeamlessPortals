# 26.2 API Map: Portal Animation slice

Companion to `migration/inventory/portal-animation.md` (touchpoint numbering `#1`–`#28` follows its §4 list).
All 26.2 citations are `path/File.java:line` relative to `C:/Users/warwa/ModDev/mc262-ref` (Mojang mappings, decompiled vanilla 26.2). IP citations are relative to `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`.
Every SAME/CHANGED verdict below was verified by opening the 26.2 file — not by name-matching.

**Verdict totals: GONE 0 · CHANGED 7 · SAME 20 · FABRIC-API 1 (grouped, counted separately).**

---

## GONE

**None.** Every MC touchpoint in this slice has a direct or renamed 26.2 equivalent. This slice is almost entirely logic-side (NBT, game time, entity lifecycle, networking); the 26.2 renderer rewrite touches it only at the frame-half injection point (#14, CHANGED — the highest-risk item below).

---

## CHANGED

### #14 — `GameRenderer#render(DeltaTracker, boolean)` HEAD injection (the client frame half) — **highest-risk item in this slice**

| | |
|---|---|
| IP usage | `MixinGameRenderer.onFarBeforeRendering` — HEAD inject on `GameRenderer.render(DeltaTracker, boolean)`; runs `RenderStates.updatePreRenderInfo(partialTick)` → `StableClientTimer.update(...)` → `ClientPortalAnimationManagement.update()` → `ClientTeleportationManager.manageTeleportation(false)` (`mixin/client/render/MixinGameRenderer.java:70-100`) |
| Verdict | **CHANGED** — the method survives with the identical signature, but its meaning moved |
| 26.2 | `public void render(DeltaTracker deltaTracker, boolean advanceGameTime)` still exists (`net/minecraft/client/renderer/GameRenderer.java:396`). But 26.2 split the frame into **camera-update → extract → render**: `Minecraft.renderFrame` calls `gameRenderer.update(deltaTracker)` (`net/minecraft/client/Minecraft.java:1290`), then `gameRenderer.extract(deltaTracker, advanceGameTime)` (`Minecraft.java:1295`), then `gameRenderer.render(deltaTracker, advanceGameTime)` (`Minecraft.java:1302`). Two world-state reads now precede `render`: (a) **the camera is positioned from the camera entity in the update phase** — `GameRenderer.update` runs `mainCamera.update(deltaTracker)` (`GameRenderer.java:372-377`), whose body does `alignWithEntity(partialTicks)` + `prepareCullFrustum`/`setupPerspective` (`net/minecraft/client/Camera.java:93-112`, align at `:103`); `Camera.extractRenderState` later only copies the already-computed `this.position` (`Camera.java:118-135`); (b) `GameRenderer.extract(DeltaTracker, boolean)` (`GameRenderer.java:379`) captures ALL remaining world render state — camera state (`:388`) and the whole level via `minecraft.levelExtractor.extract(...)` (`:389`). |
| Migration | A HEAD inject on `render` now runs **after** the frame's world state has already been extracted, and even a HEAD inject on `extract` runs **after the camera has been positioned** at `Minecraft.java:1290`: a client teleport performed by `manageTeleportation(false)` there would leave the crossing frame rendering from the pre-teleport camera position (in 1.21.3, `render` HEAD preceded `Camera.setup`, which ran inside `renderLevel` — so the crossing frame rendered post-teleport; that is the seamless-crossing property). The faithful injection point preserving IP's ordering contract ("animation update immediately precedes teleportation management, and both precede ALL of the frame's world-state reads" — inventory §3.4) is therefore **inside `Minecraft.renderFrame(Z)V`, before the `gameRenderer.update(...)` call** — e.g. `@At(value="INVOKE", target="GameRenderer.update(DeltaTracker)")` with no shift (call site `Minecraft.java:1290`), or `renderFrame` HEAD. Inputs are available there: the old `renderWorldIn` guard maps to `renderFrame`'s `advanceGameTime` parameter (`Minecraft.java:1230`; vanilla's own level-touch test is `isGameLoadFinished() && advanceGameTime && this.level != null`, `:1286`, mirrored by `readyForLevelRendering` in `GameRenderer.extract`, `GameRenderer.java:380-381`); the `minecraft.level == null` guard carries verbatim; the partial tick comes from `Minecraft.getDeltaTracker()` (public getter, `Minecraft.java:2693-2695`, field `:279`) via `getGameTimeDeltaPartialTick(true)` (#15). Do NOT hook `GameRenderer.update` HEAD itself: the panorama/screenshot path calls `gameRenderer.update/extract/renderLevel` directly (`Minecraft.java:2779-2781`), bypassing `renderFrame` — IP's 1.21.3 hook did not fire on that path either, so hooking the `renderFrame` call site is fidelity-equivalent. Note the mod's existing 26.2 render work already lives around this split (see `MIGRATION_API_MAP.md` headline); this slice only needs the two-line pump call relocated, not any render code. |

### #24 — `net.minecraft.resources.ResourceLocation` → renamed class `Identifier`

| | |
|---|---|
| IP usage | Driver deserializer registry keys `Map<ResourceLocation, Function<CompoundTag, PortalAnimationDriver>>` + `registerDeserializer` + NBT `"type"` dispatch (`core/portal/animation/PortalAnimationDriver.java:16-43`); driver ids `imm_ptl:normal` / `imm_ptl:rotation` (`NormalAnimation.java:22-27`, `RotationAnimation.java:14-19`) |
| Verdict | **CHANGED (class renamed)** |
| 26.2 | `ResourceLocation` does not exist anywhere in the 26.2 decompile (grep: zero declarations). The class is now `public final class Identifier` (`net/minecraft/resources/Identifier.java:18`). Factory methods keep the 1.21 names: `fromNamespaceAndPath(String, String)` (`Identifier.java:40`), `parse(String)` (`:44`), `withDefaultNamespace(String)` (`:48`). `ResourceKey.create` now takes an `Identifier` (`net/minecraft/resources/ResourceKey.java:26`). |
| Migration | Mechanical rename everywhere the slice touches registry keys. Also hits #18 (`CustomPacketPayload.Type(Identifier id)`) below. |

### #1 — `CompoundTag` getters → Optional-returning + `getXOr` defaults

| | |
|---|---|
| IP usage | `contains`, `getCompound`, `getList(String, 10)`, `getString`, `getInt`, `getLong`, `getBoolean`, `getDouble`, `put`, `putString/Int/Long/Boolean/Double` throughout `PortalAnimation.java:66-173`, every driver `toTag`/`fromTag`, `UnilateralPortalState.java:140-161`, `DeltaUnilateralPortalState.java:51-77`, `DefaultPortalAnimation.java:35-83` |
| Verdict | **CHANGED** |
| 26.2 | Writers unchanged: `putInt` (`net/minecraft/nbt/CompoundTag.java:235`), `putLong` (`:239`), `putDouble` (`:247`), `putString` (`:251`), `putBoolean` (`:267`), `put(String, Tag)` (`:223`). Readers now Optional: `getInt` → `Optional<Integer>` (`:299`), `getLong` → `Optional<Long>` (`:307`), `getDouble` → `Optional<Double>` (`:323`), `getString` → `Optional<String>` (`:331`), `getCompound` → `Optional<CompoundTag>` (`:351`), `getList(String)` → `Optional<ListTag>` (`:359` — **no NBT-type-id parameter anymore**), `getBoolean` → `Optional<Boolean>` (`:367`). Default-value forms exist: `getIntOr` (`:303`), `getLongOr` (`:311`), `getDoubleOr` (`:327`), `getStringOr` (`:335`), `getCompoundOrEmpty` (`:355`), `getListOrEmpty` (`:363`), `getBooleanOr` (`:371`). `contains(String)` survives (`:275`); the typed `contains(String, int)` variant no longer exists (only `:275` in the class). |
| Migration | Mechanical, but pervasive: every `readFromTag`/`fromTag` in the slice. Where IP relies on vanilla's old implicit default (absent key → 0/""/false), use the `getXOr(key, default)` forms to keep byte-identical behavior — e.g. `DeltaUnilateralPortalState.fromTag`'s sizeScaling z defaulting to 1 when absent (`DeltaUnilateralPortalState.java:58`) maps to `getDoubleOr("sizeScalingZ", 1.0)`. `getList(key, 10)` call sites (`PortalAnimation.java:78,86`) drop the `10` and use `getListOrEmpty`/`getList`. The legacy-key fallback logic (`"animation"` at `PortalAnimation.java:67-69`) expresses naturally with `contains`/Optional. |

### #13 — `Minecraft#updateLevelInEngines(ClientLevel)` → two overloads; hook the 2-arg one

| | |
|---|---|
| IP usage | HEAD inject driving `IPCGlobal.CLIENT_CLEANUP_EVENT`, which resets `StableClientTimer` and `ClientPortalAnimationManagement` (`mixin/client/MixinMinecraft.java:156-175`; registrations `StableClientTimer.java:110-112`, `ClientPortalAnimationManagement.java:29-32`) |
| Verdict | **CHANGED (overload split — 1-arg inject now misses a path)** |
| 26.2 | Two private overloads: `updateLevelInEngines(@Nullable ClientLevel level)` (`net/minecraft/client/Minecraft.java:2191`) delegates to `updateLevelInEngines(@Nullable ClientLevel level, boolean stopSound)` (`:2195`). Call sites: `setLevel(ClientLevel)` → 1-arg (`:2061`); the saving-screen disconnect path calls **the 2-arg directly** with `null` (`:2146`); `clearClientLevel` → 1-arg with `null` (`:2177`). Body now re-points `levelExtractor`/`particleEngine`/`gameRenderer` (`:2202-2204`). |
| Verdict detail | Injecting only the 1-arg overload (IP's target) silently misses the `:2146` disconnect-with-saving path → stale animation maps across a world exit. **Target the 2-arg overload** `(ClientLevel, boolean)` at `:2195` to cover all three paths. (The mod's existing 26.2 code already hooks `updateLevelInEngines(null)` for kick-cleanup — see memory `nether-block-freeze-orphaned-extractor` — verify it targets the 2-arg form during the port.) |

### #18 — `CustomPacketPayload.Type` now keyed by `Identifier` (rest of the packet stack survives)

| | |
|---|---|
| IP usage | `PortalSyncPacket` custom payload: `CustomPacketPayload` + `Type`, `StreamCodec`, `FriendlyByteBuf`/`RegistryFriendlyByteBuf`, `ByteBufCodecs.registry(Registries.ENTITY_TYPE)` (`core/network/ImmPtlNetworking.java:134-174`) |
| Verdict | **CHANGED (only the `Type` id class renamed; everything else SAME)** |
| 26.2 | `public interface CustomPacketPayload` (`net/minecraft/network/protocol/common/custom/CustomPacketPayload.java:13`); `record Type<T extends CustomPacketPayload>(Identifier id)` (`:56` — **`Identifier`, not `ResourceLocation`**); `TypeAndCodec` record (`:59`). `StreamCodec<B, V>` (`net/minecraft/network/codec/StreamCodec.java:20`). `ByteBufCodecs.registry(ResourceKey<? extends Registry<T>>)` (`net/minecraft/network/codec/ByteBufCodecs.java:582`). `FriendlyByteBuf` (`net/minecraft/network/FriendlyByteBuf.java:71`), `RegistryFriendlyByteBuf` (`net/minecraft/network/RegistryFriendlyByteBuf.java:7`). |
| Migration | Rename the id constant's type; codec structure ports 1:1. Payload registration itself is loader-side (see FABRIC-API section). |

### #19 — client entity-creation calls: `Entity#moveTo` → renamed `snapTo` (rest SAME)

| | |
|---|---|
| IP usage | Client receipt of `PortalSyncPacket` creates the portal entity: `setId/setUUID/syncPacketPositionCodec/moveTo`, `ClientLevel.getEntity(int)`, `ClientLevel.addEntity(Entity)` (`core/network/ImmPtlNetworking.java:186-221`) |
| Verdict | **CHANGED (one rename)** |
| 26.2 | `ClientLevel.addEntity(Entity)` (`net/minecraft/client/multiplayer/ClientLevel.java:529`); `getEntity(int)` → `@Nullable Entity` (`:551`). `Entity.setId(int)` (`net/minecraft/world/entity/Entity.java:389`), `setUUID(UUID)` (`:3240`), `syncPacketPositionCodec(double,double,double)` (`:355`) — all SAME. **`moveTo` no longer exists; it is `snapTo`**: `snapTo(Vec3)` (`:1778`), `snapTo(double,double,double)` (`:1782`), `snapTo(Vec3,float,float)` (`:1790`), `snapTo(double,double,double,float,float)` (`:1794`). Vanilla's own client spawn performs the same **set** of calls inside `Entity.recreateFromPacket(ClientboundAddEntityPacket)`, but in a **different order** than IP: vanilla does `syncPacketPositionCodec(x,y,z); snapTo(x,y,z,yRot,xRot); setId; setUUID; setDeltaMovement` (`Entity.java:3834-3844`), whereas IP does `setId; setUUID; syncPacketPositionCodec; moveTo` (`core/network/ImmPtlNetworking.java:214-217`). Vanilla's path is driven from `ClientPacketListener.handleAddEntity` → `createEntityFromPacket` → `level.addEntity` (`net/minecraft/client/multiplayer/ClientPacketListener.java:566-576`). |
| Migration | Rename `moveTo` → the matching `snapTo` overload; keep IP's exact call order — `setId → setUUID → syncPacketPositionCodec → snapTo` (`ImmPtlNetworking.java:214-217`). This is the same set of calls as vanilla's `recreateFromPacket` but NOT the same order (vanilla positions first, then sets id/uuid — `Entity.java:3834-3844`); fidelity means IP's order. |

### #20 — `EntityType#create(Level)` → requires `EntitySpawnReason`

| | |
|---|---|
| IP usage | Client-side portal entity construction on sync receipt (`core/network/ImmPtlNetworking.java:206`) |
| Verdict | **CHANGED** |
| 26.2 | `public @Nullable T create(Level level, EntitySpawnReason reason)` (`net/minecraft/world/entity/EntityType.java:298`), delegating to `create(Level, EntitySpawnRequest)` (`:302`). `EntitySpawnReason` is an enum incl. `LOAD` (`net/minecraft/world/entity/EntitySpawnReason.java:3-22`); `EntitySpawnRequest(EntitySpawnReason reason, boolean ignoreChecks)` (`net/minecraft/world/entity/EntitySpawnRequest.java:3`). Vanilla's client spawn-packet path uses **`EntitySpawnReason.LOAD`**: `type.create(this.level, EntitySpawnReason.LOAD)` (`net/minecraft/client/multiplayer/ClientPacketListener.java:601`). |
| Migration | `entityType.create(world)` → `entityType.create(world, EntitySpawnReason.LOAD)` — matching what vanilla does for every packet-spawned entity. |

---

## SAME

### Time / level

| # | IP usage | 26.2 citation | Note |
|---|---|---|---|
| 3 | `Level#getGameTime()` (`PortalAnimation.java:197,204,252,487`; `DefaultPortalAnimation.java:64`) | default method `LevelAccessor.getGameTime()` (`net/minecraft/world/level/LevelAccessor.java:41-43`), delegating to `LevelData.getGameTime()` (`net/minecraft/world/level/storage/LevelData.java:21`); used by `Level` itself (`net/minecraft/world/level/Level.java:903`) | Despite the 26.2 world-clock rework (day-time now lives in `WorldClock`s), raw `gameTime` semantics are intact: server +1/tick (`ServerLevel.java:477-480`), client +1/tick (`ClientLevel.java:439-442`), server-sync jump via `setTimeFromServer` (`ClientLevel.java:445-446`). |
| 4 | `Level#isClientSide()` (`PortalAnimation.java:244` et al.) | `public boolean isClientSide()` (`net/minecraft/world/level/Level.java:163`) | |
| 5 | `Level#dimension()` (`UnilateralPortalState` component) | `public ResourceKey<Level> dimension()` (`net/minecraft/world/level/Level.java:960`) | `ResourceKey` survives (`net/minecraft/resources/ResourceKey.java:13`); its `create` takes `Identifier` (`:26`) — see #24. `UnilateralPortalState.toTag` stores it as a string id, unaffected. |
| 6 | `Minecraft.getInstance()/isPaused()/level` (`StableClientTimer.java:143-176`) | `getInstance()` (`net/minecraft/client/Minecraft.java:2517`), `isPaused()` (`:2600`), `public @Nullable ClientLevel level` (`:335`) | |
| 7 | `ClientLevel#tickRateManager()` (`StableClientTimer.java:148,176`) | `public TickRateManager tickRateManager()` (`net/minecraft/client/multiplayer/ClientLevel.java:748-749`) | |
| 8 | `TickRateManager#tickrate()/runsNormally()` (`StableClientTimer.java:150-158,176`) | `tickrate()` (`net/minecraft/world/TickRateManager.java:20`), `runsNormally()` (`:32`) | Same package (`net.minecraft.world`). |

### Entity lifecycle / server ordering

| # | IP usage | 26.2 citation | Note |
|---|---|---|---|
| 9 | `Entity#tickCount` — the tick-swap key (`PortalAnimation.java:284`) | `public int tickCount` (`net/minecraft/world/entity/Entity.java:247`) | |
| 10 | `Entity#isRemoved()` (`ClientPortalAnimationManagement.java:91,134`) | `public final boolean isRemoved()` (`net/minecraft/world/entity/Entity.java:3903`) | |
| 11 | **Server tick ordering assumption** — game time increments before entities tick; the whole `partialTicks = 1` rationale (`PortalAnimation.java:245-250`) | **Verified in 26.2**: `ServerLevel.tick(BooleanSupplier)` calls `this.tickTime()` at `net/minecraft/server/level/ServerLevel.java:381` (gameTime+1 at `:477-480`), and entity ticking happens later in the same method via `entityTickList.forEach → guardEntityTick(this::tickNonPassenger, ...)` (`:425-451`) | The mutually-consistent convention set (server `partialTicks=1`, `tickTime-1+partialTicks`, client `stableTickTime+1`) ports unchanged. |

### Client tick wiring

| # | IP usage | 26.2 citation | Note |
|---|---|---|---|
| 12 | `Minecraft#tick` inject at `INVOKE ClientLevel.tick(BooleanSupplier)` + `Shift.AFTER` (`MixinMinecraft.java:119-126`) | `Minecraft.tick()` (`net/minecraft/client/Minecraft.java:1758`) still calls `this.level.tick(() -> true)` (`:1819`); target method `ClientLevel.tick(BooleanSupplier)` unchanged (`net/minecraft/client/multiplayer/ClientLevel.java:299`) | Ordering contract preserved: entity ticking `this.level.tickEntities()` (`Minecraft.java:1797`) and `tickBlockEntities()` (`:1799`) run **before** `level.tick(...)` (`:1819`), which increments game time via `tickTime()` (`ClientLevel.java:303`, `:439-442`) — identical relative order to 1.21.3, so "after remote world ticking, after game time++, at partialTick 0" holds at the same injection point. IP's `ClientWorldLoader.tick()` (remote dims) stays mod-side. |
| 15 | `DeltaTracker#getGameTimeDeltaPartialTick(true)` (`MixinGameRenderer.java:85-87`) | `float getGameTimeDeltaPartialTick(boolean ignoreFrozenGame)` on `public interface DeltaTracker` (`net/minecraft/client/DeltaTracker.java:8,14`) | Vanilla's frame loop passes `false` (`Minecraft.java:1291`); IP deliberately passes `true` — port as-is. "Do not use delta tick" note still applies: `getGameTimeDeltaTicks()` (`DeltaTracker.java:12`) is the frame delta, not the partial tick. |

### Sync path

| # | IP usage | 26.2 citation | Note |
|---|---|---|---|
| 16 | `ServerEntity#sendChanges` cancelled for portals (`MixinServerEntity.java:96-105`) | `public void sendChanges()` (`net/minecraft/server/level/ServerEntity.java:88`; class `:50`) | Mixin-cancel target survives verbatim. |
| 17 | `Entity#getAddEntityPacket(ServerEntity)` override returning the custom payload packet (`Portal.java:897-919`) | `public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity serverEntity)` (`net/minecraft/world/entity/Entity.java:3672-3674`) — non-final, overridable; consumed at `ServerEntity.java:278`; `ClientGamePacketListener` intact (`net/minecraft/network/protocol/game/ClientGamePacketListener.java:7`) | IP's contravariant double-cast (`Portal.java:904-919`) still works: `ClientboundCustomPayloadPacket` is a **public record** `(CustomPacketPayload payload) implements Packet<ClientCommonPacketListener>` (`net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket.java:15`) — directly constructible, so a loader-neutral `new ClientboundCustomPayloadPacket(payload)` can replace Fabric's `createS2CPacket` (routing decision belongs to the networking slice; see FABRIC-API). |
| 27 | `ClientboundSetTimePacket` / `ClientPacketListener.handleSetTime` — javadoc reference only (`StableClientTimer.java:17`) | `handleSetTime` (`net/minecraft/client/multiplayer/ClientPacketListener.java:1129-1136`); `record ClientboundSetTimePacket(long gameTime, Map<Holder<WorldClock>, ClockNetworkState> clockUpdates)` (`net/minecraft/network/protocol/game/ClientboundSetTimePacket.java:14`) | The premise `StableClientTimer` exists to absorb — server time-sync jumping `level.getGameTime()` — is intact: `handleSetTime` → `level.setTimeFromServer(gameTime)` (`ClientPacketListener.java:1132`). The packet additionally carries `WorldClock` updates; irrelevant to this slice. |

### NBT container

| # | IP usage | 26.2 citation | Note |
|---|---|---|---|
| 2 | `ListTag` build/iterate (`PortalAnimation.java:78,86` via `Helper` list⇄ListTag) | `public final class ListTag extends AbstractList<Tag> implements CollectionTag` (`net/minecraft/nbt/ListTag.java:16`); `new ListTag()` (`:145`); `get(int) → Tag` (`:332`); `add(int, Tag)` (`:340`) / `addTag(int, Tag)` (`:351`) — `add(Tag)` inherited from `AbstractList` | Now backed by plain `List<Tag>` (`:143`) — heterogeneous lists are legal, the old same-type constraint is gone (superset of old behavior; IP's homogeneous compound lists unaffected). Typed element getters are Optional (`getCompound(int)` → `Optional<CompoundTag>` `:254`, `getCompoundOrEmpty(int)` `:258`) — only relevant if the mod's `Helper` port uses them instead of `get(int)` + cast. |

### Text / math / misc

| # | IP usage | 26.2 citation | Note |
|---|---|---|---|
| 21 | `Component.literal`, `MutableComponent.append/withStyle`, `ChatFormatting.GOLD` (`PortalAnimation.java:544-573`, `NormalAnimation.java:354-364`, `RotationAnimation.java:133-142`) | `static MutableComponent literal(String)` (`net/minecraft/network/chat/Component.java:135`); `append(String)`/`append(Component)` (`net/minecraft/network/chat/MutableComponent.java:48,52`); `withStyle(ChatFormatting...)` (`:67`), `withStyle(ChatFormatting)` (`:72`); `public enum ChatFormatting { ... GOLD('6') ... }` (`net/minecraft/ChatFormatting.java:7,14`) | |
| 22 | `Mth#lerp` (`DeltaUnilateralPortalState.java:84-87`, `UnilateralPortalState.java:134-136,441-443`) | `lerp(float,float,float)` (`net/minecraft/util/Mth.java:550`), `lerp(double,double,double)` (`:558`) | Same argument order (alpha first). |
| 23 | `Vec3` — `add/scale/subtract/lengthSqr/lerp/distanceToSqr` (throughout) | `subtract` (`net/minecraft/world/phys/Vec3.java:96-104`), `add` (`:108-116`), `distanceToSqr` (`:131,138`), `scale` (`:152`), `lengthSqr` (`:184`), `lerp` (`:228`) | |
| 25 | JOML `Matrix3d/Matrix3dc/Vector3d` (`UnilateralPortalState.java:40-65,205-237`); DFU `Pair` (`:416-424`) | JOML still shipped and used by vanilla (`import org.joml.*` in `net/minecraft/client/renderer/SectionOcclusionGraph.java`); DFU `com.mojang.datafixers.util.Pair` used throughout (e.g. `net/minecraft/world/level/biome/Climate.java`) | Library dependencies, unchanged. |
| 28 | `System#nanoTime` — default-animation wall clock (`ClientPortalAnimationManagement.java:44,85`) | JDK, not MC | Design decision carries over: default animation = wall clock, driver animation = game time. |

---

## FABRIC-API (loader-abstraction routing required — multiloader common/fabric/neoforge)

The mod's loader seam is documented in `migration/inventory/current-mod-core.md` (§2.3): a common `PlatformHelper` interface (`sendToClient`/`sendToServer`/`registerPayloads`) resolved via `ServiceLoader`, implemented by `FabricPlatformHelper` (Fabric `PayloadTypeRegistry` + `ServerPlayNetworking`/`ClientPlayNetworking`) and `NeoForgePlatformHelper` (`RegisterPayloadHandlersEvent`/`PayloadRegistrar`). All items below are from inventory #26 plus registration wiring (§5) and must route through that seam:

| Fabric API surface | IP usage | Routing note |
|---|---|---|
| `@Environment(EnvType.CLIENT)` annotations | `PortalAnimation` (two methods), `DefaultPortalAnimation.startClientDefaultAnimation`, whole `ClientPortalAnimationManagement`/`StableClientTimer` classes | Loader-specific dist annotation (NeoForge: `@OnlyIn(Dist.CLIENT)` or — preferably for common code — plain client-package separation per the mod's existing convention). |
| `net.fabricmc.fabric.api.event.Event` + `EventFactory.createArrayBacked` (via `Helper.createConsumerEvent`, `q_misc_util/Helper.java:1435-1444`) | `ClientPortalAnimationManagement.CLIENT_PORTAL_DEFAULT_ANIMATION_FINISH` (`ClientPortalAnimationManagement.java:21-22`, fired `:98`) | Needs the mod's common event abstraction (or a plain listener list like IP's own `Signal`, which is already loader-neutral — `q_misc_util/my_util/Signal.java:11-60`). |
| `PayloadTypeRegistry` / `ServerPlayNetworking.createS2CPacket` / `ClientPlayNetworking.registerGlobalReceiver` (`core/network/ImmPtlNetworking.java:238-267`) | `PortalSyncPacket` registration + client receiver (spawn/update transport for all animation NBT) | Route through the mod's loader networking abstraction. The payload/codec definitions themselves are vanilla (#18) and belong in common. |
| Mixin injection points (#12, #13, #14) | `MixinMinecraft`, `MixinGameRenderer`, `MixinServerEntity` | Mixins are loader-neutral in this project's layout but live in the platform-mixin config; note #14's target moves into `Minecraft.renderFrame` before the `GameRenderer.update` call (see CHANGED). |

---

## Cross-slice porting notes

1. **The frame half moves two phases earlier (#14).** In 26.2 the "before rendering the frame" moment for logic that mutates world state (portal geometry, player teleports) is **before the camera update**, not before drawing: `renderFrame` runs camera-positioning (`gameRenderer.update` → `Camera.alignWithEntity`, `Minecraft.java:1290`, `Camera.java:103`) then extraction (`:1295`) then drawing (`:1302`). `GameRenderer.render` HEAD would feed a one-frame-stale portal state AND a stale camera; even `GameRenderer.extract` HEAD is too late for the camera. Target: inside `Minecraft.renderFrame(Z)V` before the `GameRenderer.update` call (`Minecraft.java:1290`), preserving IP's contract that `StableClientTimer.update` → `ClientPortalAnimationManagement.update()` → `manageTeleportation(false)` run back-to-back before ALL of the frame's world-state reads (inventory §3.4).
2. **The tick half is untouched (#12).** Same injection point, same guaranteed ordering (entities → game-time++ → inject) — verified at `Minecraft.java:1797/1819` + `ClientLevel.java:303`.
3. **The timing-convention set survives whole (#11).** Server `tickTime()` before entity ticking is confirmed in 26.2, so `partialTicks = 1` on server, `real time = tickTime - 1 + partialTicks` in drivers, client ticking at `stableTickTime + 1`, and `canRemoveAnimation = !isTicking` port with zero change.
4. **`StableClientTimer`'s premise is intact (#3, #27).** Client gameTime still +1/tick and still snap-set by `handleSetTime` → `setTimeFromServer`; the new `WorldClock` machinery is additive and irrelevant here.
5. **NBT rewrite is broad but mechanical (#1).** Prefer `getXOr(...)` forms to reproduce vanilla-1.21.3 implicit defaults exactly; watch the two quirks the inventory flags (`sizeScalingZ` default 1, legacy `"animation"` key).
6. **Renames to apply slice-wide:** `ResourceLocation` → `Identifier` (#24), `moveTo` → `snapTo` (#19), `EntityType.create(Level)` → `create(Level, EntitySpawnReason.LOAD)` (#20), `CustomPacketPayload.Type(Identifier)` (#18).
