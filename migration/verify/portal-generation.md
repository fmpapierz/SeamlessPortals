# Adversarial Verification: Portal Generation (nether/custom/global) — pass 2 (current docs)

Verified documents (as of 2026-07-12; the api-map already incorporates the pass-1 citation fix — the "NOT in ImmPtlNetworking" note in its Fabric-API networking row):
- `migration/inventory/portal-generation.md` (INV)
- `migration/api-map/portal-generation.md` (MAP)

Method: re-derived every geometry/sign claim from IP source (`C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`, abbrev. `IP:`), re-opened every cited 26.2 file (`C:/Users/warwa/ModDev/mc262-ref`, abbrev. `262:`), rename-hunted GONE verdicts by grep/glob over the full 26.2 tree, and compared the declared slice scope against actual directory listings.

**Overall verdict: MINOR — 40 load-bearing claims checked, 38 confirmed, 2 refuted (both small; neither misdirects architecture, geometry, or API fate).**

---

## 0. Scope completeness — CONFIRMED

Directory listings match the inventory's declared scope exactly: `nether_portal/` 10 files, `custom_portal_gen/` 5 files + `form/` 12 files, `global_portals/` 5 files, `peripheral/portal_generation/` 4 files (36 total). Every file has an inventory section (INV §2.1-2.5); no silently skipped scope files.

---

## 1. REFUTED claims

### R1 (INV §3.5, conventional_dimension_change) — description of `MixinServerPlayerEntity_MA` is incomplete; it omits a chunk-tracker side effect that must be ported
- **Doc claim** (INV :281): the mixin "records the pre-travel position and schedules `onAfterConventionalDimensionChange` on the task list".
- **Actual**: `onBeforeDimensionTravel` does THREE things in sequence: `customPortalGenManager.onBeforeConventionalDimensionChange(player)`, **`ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(player)`**, then schedules the after-callback on `ServerTaskList` — IP:imm_ptl/core/platform_specific/mixin/common/MixinServerPlayerEntity_MA.java:46-58 (the tracker-removal call sits between the record and the schedule). A porter following the doc verbatim would drop the chunk/entity-tracker cleanup on vanilla cross-dimension travel.
- The rest of the claim is confirmed: HEAD inject on `changeDimension(DimensionTransition)` and HEAD on `teleportTo(ServerLevel,DDDFF)` gated by `this_.level() != targetWorld` (:19-43).

### R2 (MAP GONE row 1) — "NPE on null" `dataFixType` overstates the failure mode; the real behavior is a *swallowed* exception → silent save-data loss
- **Doc claim** (MAP :19): "`SavedDataStorage.readTagFromDisk` calls `type.update(...)` unconditionally when the file exists (`SavedDataStorage.java:90,109-124` → NPE on null)".
- **Actual**: the NPE would indeed be thrown at 262:world/level/storage/SavedDataStorage.java:124 (`type.update(...)` invoked unconditionally on the file-exists path, :89-90), **but** `readSavedData` wraps the whole read in `catch (Exception e)` at :97-99 — the NPE is caught, logged ("Error loading saved data"), and the method returns null. `computeIfAbsent` then constructs a *fresh* storage (:65-73). So a null `dataFixType` would not crash: it would silently discard the existing `global_portal.dat` on every load and eventually overwrite it. The migration directive is unchanged (null is not viable; a real `DataFixTypes` constant must be chosen — and `DataFixTypes` indeed has no NONE, 262:util/datafix/DataFixTypes.java:16-47), but the failure mode is silent data loss, not an NPE crash — harder to catch in testing, so the port spec should note it.

---

## 2. CONFIRMED — geometry / sign / transform (re-derived from IP source)

| # | Claim | Evidence |
|---|---|---|
| C1 | `initPortalAxisShape`: portal pos = `innerAreaBox.getCenterVec()`; axisW/axisH from `Helper.getPerpendicularDirections(facing)`; width/height from box size on the w/h axes; mesh quads offset **+0.5 along the POSITIVE axis direction**; rectangle → `setPortalShapeToDefault()`, else `Mesh2D` unit quads + `simplify()` + `SpecialFlatPortalShape` | IP:nether_portal/BlockPortalShape.java:360-411 — offset = `Vec3.atLowerCornerOf(Direction.get(POSITIVE, axis).getNormal()).scale(0.5)` (:379-382); quad local coords = `(p−center)·axisW / halfWidth` etc. (:395-405) |
| C2 | BFS flood fill: air → enqueue+add; obsidian → stop expanding; **anything else → whole search fails** (null); abort when `area.size() > lengthLimit²`; final reject if any inner-box dimension > lengthLimit | IP:BlockPortalShape.java:254-263 (three-way branch, `return false` on else), :246-248 (size abort), :218-220 (null), :224-227 (dimension reject) |
| C3 | `findFrameShape` tries axes **X → Y → Z**, first non-null wins | IP:nether_portal/NetherPortalGeneration.java:269-281 (`Arrays.stream(Direction.Axis.values())` = enum order X,Y,Z; `.findFirst()`) |
| C4 | `BlockTraverse.boxAllMatch` is actually **any-match** (true on first satisfying pos); one call site NetherPortalMatcher:110 | IP:nether_portal/BlockTraverse.java:140-148 (`searchInBox` lambda returns TRUE on first `predicate.test` hit; result = `!= null`); IP:NetherPortalMatcher.java:110 |
| C5 | `levitateBox`: max up-shift searched in `[1, maxOffset*3/2)`, box moved by `maxUpShift*2/3`; `pushDownBox` searches `[0,40)` | IP:NetherPortalMatcher.java:241-256, :258-273 |
| C6 | `findHorizontalPortalPlacement`: vertical space reserve staged 30→10→1, radius 12 | IP:NetherPortalMatcher.java:127-149 |
| C7 | `mapPosition` = `Helper.divide(Helper.scale(from, spaceRatioTo), spaceRatioFrom)`; out-of-border → `clampToBounds` then **×0.9 on X and Z only** | IP:custom_portal_gen/CustomPortalGeneration.java:164-182 (×0.9 at :176-178) |
| C8 | Intrinsic gens: OVERWORLD→NETHER with `(spaceRatioFrom=8, spaceRatioTo=1)` (dest = pos/8), reverse via `getReverse()`; `portalHelper` = ANY→SAME, 1:1, `reversible=false`; all constructed with `trigger = null` | IP:peripheral/portal_generation/IntrinsicPortalGeneration.java:28-54 |
| C9 | `VerticalConnectingPortal`: width/height 23333333333; **floor: axisW=(0,0,1), axisH=(1,0,0); ceil: axisW=(1,0,0), axisH=(0,0,1)**; floor at fromWorldMinY / ceil at fromWorldMaxY; dest = opposite extreme (swapped when `inverted`); inversion = `DQuaternion.rotationByDegrees((1,0,0),180)` combined with Y-rotation via `hamiltonProduct`; scaling sets `teleportChangesScale=false` + `adjustPositionAfterTeleport=false`; Y bounds from `McHelper.getMinY`/`getMaxContentYExclusive`; scale = fromScale/toScale coordinateScale ratio | IP:global_portals/VerticalConnectingPortal.java:97-153 (axes :106-115, dest :119-138, rotation :140-147, scaling :149-153); :56-71 (connect feeds minY/maxContentY + coordinateScale ratio) |
| C10 | `FlippingFloorSquareForm`: placement scan = 16×16 XZ window (`x,z ∈ [toPos−8, toPos+8)`) scanned **top-down** (`y ↦ maxContentY − y`); box must be non-solid-render + non-placeholder + fluid-free, layer below non-air non-placeholder; `createPortals` = 2-portal pair, `pa.setRotation(rotationByDegrees((1,0,0),180))`, `pb = createReversePortal(pa)`, `motionAffinity = 0.1` on both, cross `reversePortalId`s | IP:custom_portal_gen/form/FlippingFloorSquareForm.java:152-183, :185-214 |
| C11 | `PortalGenInfo` ctor snaps rotation `< 0.001°` → null and `|scale−1| < 1e−5` → 1.0 | IP:custom_portal_gen/PortalGenInfo.java:59-68 |
| C12 | `generateBiWayBiFacedPortal`: f1 = template; f2 = `createFlippedPortal(f1)`; t1 = `createReversePortal(f1)`; t2 = `createFlippedPortal(t1)`; from-shape on f1/f2, to-shape on t1/t2; **cross-links f1↔t1 and f2↔t2**; `PortalExtension.initializeClusterBind(f1,f2,t1,t2)`; 4× `McHelper.spawnServerEntity` | IP:PortalGenInfo.java:85-113 (cross-links :100-103); `createTemplatePortal` :71-83 matches INV (1-arg `entityType.create(fromWorld)`, dest = to-shape inner-box center) |
| C13 | `ScalingSquareForm` scale = `toLength / fromLength` (double division) | IP:custom_portal_gen/form/ScalingSquareForm.java:107-109 |
| C14 | `OneWayForm` destination = triggering entity's `getEyePosition(1)` in the entity's **current** dimension; no entity → origin +10 Y in the from-dimension; `markOneWay()`; `unbreakable = !breakable`; bi-faced via `PortalAPI.createFlippedPortal` | IP:custom_portal_gen/form/OneWayForm.java:88-120 |

## 3. CONFIRMED — pipeline / flow / threading (traced in IP source)

| # | Claim | Evidence |
|---|---|---|
| C15 | `FrameSearching` success/normal-not-found hop back via `MiscHelper.getServer().execute(...)`; **`catch (Throwable)` path runs `onNotFound.run()` directly on the background thread**; async on `Util.backgroundExecutor()` | IP:nether_portal/FrameSearching.java:38-61 (execute :46-53; catch :55-58; executor :60) |
| C16 | `startGeneratingPortal`: fresh-vs-existing fork = raw region-file existence; loaderRadius = `floorDiv(existingFrameSearchingRadius,16)+1` when region exists, else 1 (2 if `getShapeInnerLength() ≥ 16`); polled `ServerTaskList` task runs `portalIntegrityChecker` each tick + `imm_ptl.loading_chunks n/m` progress; existing → `FastBlockAccess` snapshot + async search; found → `portalEntityGeneratingFunc` + finalizer + `O_O.postPortalSpawnEventForge`; not-found/fresh → `onGenerateNewFrame` (also fires the Forge event, :152) | IP:NetherPortalGeneration.java:140-233 (fork :156, radius :158-172, task :184-233) |
| C17 | `NetherPortalLikeForm.perform`: clears area to AIR when `generateFrameIfNotFound` (removes triggering fire); integrity checker = all `frameAreaWithoutCorner` non-air; passes `IPGlobal.netherPortalFindingRadius`; default match func = `FastBlockPortalShape.matchShape` translation **rejecting same-dim self-match** at identical base; every match round-trips `withNewBase(...).toBlockPortalShape()` — confirming INV §6.3's stale-boxes caveat is safe at all IP call sites | IP:custom_portal_gen/form/NetherPortalLikeForm.java:63-68, :98-103, :79, :119-142 |
| C18 | `CustomPortalGenManager.addEntry` validates the reverse gen but **never calls `load(reverse)`** | IP:custom_portal_gen/CustomPortalGenManager.java:102-142 — `load(gen)` :117 is the only `load` call; the `reversible` branch :119-141 only runs `initAndCheck` |
| C19 | `BreakablePortalEntity.tick`: client → sound/particles; server & breakable → integrity check when `isNotified` **or** `gameTime % 233 == getId() % 233`; then `breakPortalOnThisSide()`; `unbreakable` skips | IP:nether_portal/BreakablePortalEntity.java:158-177 (stagger :167, gate :166) |
| C20 | `PortalPlaceholderBlock.updateShape`: server-only, and only when update direction's axis ≠ block `AXIS` (in-plane) → `notifyPlaceholderUpdate()` on `BreakablePortalEntity`s rough-range 2 | IP:core/portal/PortalPlaceholderBlock.java:96-122 (gate :100-103) |
| C21 | Ignition mixins: `@Redirect` of `PortalShape.findEmptyPortalShape` inside `BaseFireBlock.onPlace` — vanilla mode delegates to vanilla, disabled returns empty, otherwise calls `IntrinsicPortalGeneration.onFireLitOnObsidian` when any 6-neighbor is obsidian and returns `Optional.empty()`; second `@Redirect` makes `isPortal`'s `Optional.isPresent()` return true whenever mode ≠ vanilla | IP:peripheral/mixin/common/nether_portal/MixinAbstractFireBlock_CVB.java:20-72 |
| C22 | Trigger mixins: `MixinItemStack` @Inject RETURN of `ItemStack.useOn(UseOnContext)`, server-side, null-checked manager; `MixinItemEntity_P` @Inject TAIL of `ItemEntity.tick()`, `@Shadow private @Nullable UUID thrower`, gates on not-removed + server + `thrower != null` | IP:core/mixin/common/portal_generation/MixinItemStack.java:16-32; MixinItemEntity_P.java (shadow, TAIL, gates) |
| C23 | Global-portal storage/sync: `SavedData.Factory<>(supplier, biFunction, **null**)` + `computeIfAbsent(..., "global_portal")`; `createSyncPacket` lives in **GlobalPortalStorage** :148-157 (call :151) wrapping `ImmPtlNetworking.GlobalPortalSyncPacket(dimIntId, tag)` (`imm_ptl:upd_glb_ptl`, varint+NBT) in `ServerPlayNetworking.createS2CPacket`; sent to all players on change and per non-empty dimension on login; registration `PayloadTypeRegistry.playS2C().register` + `ClientPlayNetworking.registerGlobalReceiver` in `init`/`initClient` — MAP's corrected location note is accurate | IP:global_portals/GlobalPortalStorage.java:94-112, :135-157, :193-200; IP:core/network/ImmPtlNetworking.java:93-127, :238-267 |
| C24 | `Portal.createPortalEntityType` = `FabricEntityTypeBuilder.create(MISC, ctor).dimensions(fixed(0,0)).fireImmune().trackRangeBlocks(96).trackedUpdateRate(20).forceTrackedVelocityUpdates(true).build()` | IP:core/portal/Portal.java:94-108 |
| C25 | IP-side premise of the off-by-one warning: `FastBlockAccess` treats 1.21.3 `getMaxSection()` as an EXCLUSIVE bound (local `maxSectionYExclusive`, `upperCYExclusive <= maxSectionYExclusive` validation, array extent `upper − lower`) | IP:nether_portal/FastBlockAccess.java:40-43 (`upperCY = world.getMaxSection()` used as `upperCYExclusive`), :59-70 |

## 4. CONFIRMED — 26.2 API fates (re-opened cited files; rename-hunted)

| # | Claim (MAP row) | Evidence in mc262-ref |
|---|---|---|
| C26 | GONE: `SavedData` = dirty flag only; `SavedDataType(Identifier, Supplier, Codec, DataFixTypes)` record; `SavedDataStorage.computeIfAbsent(type)/get/set`; read parses `tag.get("data")` with codec under `RegistryOps`; `DataFixTypes` has no NONE | 262:world/level/saveddata/SavedData.java:3-17; SavedDataType.java:8; storage/SavedDataStorage.java:65,76,104, :86-95; util/datafix/DataFixTypes.java:16-47 (31 constants, no NONE). See R1/R2 for the one nuance |
| C27 | GONE: `DimensionDataStorage` → `SavedDataStorage`; `ServerLevel.getDataStorage()` :1442 → `getChunkSource().getDataStorage()` (ServerChunkCache :565); per-dimension `data/` folder survives (`getDimensionPath(dim).resolve("data")`) | 262:server/level/ServerLevel.java:1442; ServerChunkCache.java:565, :94 |
| C28 | GONE: `Entity.createCommandSourceStack()` — Entity has only `createCommandSourceStackForNameResolution(ServerLevel)` :3616; survives on ServerPlayer :1768 + MinecraftServer :1693; `withEntity` :151, `withSuppressedOutput` :240, `withPermission(PermissionSet)` :261; `LevelBasedPermissionSet.GAMEMASTER` = `PermissionLevel.GAMEMASTERS` :7; vanilla uses exactly the proposed combo (AdvancementRewards.java:87) | full-tree grep of `createCommandSourceStack` + cited files |
| C29 | GONE: `net.minecraft.util.Tuple` deleted | grep `(class|record|interface) Tuple` over mc262-ref → 0 matches; glob `**/Tuple.java` → 0 files |
| C30 | CHANGED 8/9 (off-by-one landmine): `getMaxY()` = `getMinY() + getHeight() − 1` (**inclusive**); `getMaxSectionY()` = `blockToSectionCoord(getMaxY())` (**inclusive**); `getMinY` :9 / `getMinSectionY` :19 | 262:world/level/LevelHeightAccessor.java:9-25 — with C25, "old exclusive ≡ new +1" is correct |
| C31 | CHANGED 11: `record ChunkPos(int x, int z)` :19 (components private externally → `x()`/`z()`); `asLong` gone → `pack(int,int)` :73 with **unchanged bit layout** (x low 32, z high 32, :73-74); `pack()` :69; `unpack(long)` :49; BlockPos ctor gone → `containing(BlockPos)` :45; `getX/getZ(long)` :85-90 survive | 262:world/level/ChunkPos.java (all verified) |
| C32 | CHANGED 17/18: `ServerPlayer.teleport(TeleportTransition)` :1093; `teleportTo(ServerLevel,double×3,Set<Relative>,float,float,boolean)` :1689 | 262:server/level/ServerPlayer.java:1093,1689 |
| C33 | CHANGED 19: `ItemEntity.thrower` = `private @Nullable EntityReference<Entity>` :52; resolved via `EntityReference.getEntity(thrower, level())` in `getOwner()` :81-83; `tick()` :109 | 262:world/entity/item/ItemEntity.java |
| C34 | CHANGED 21: `Gui.hud` public final field :72; `Hud.setOverlayMessage(Component, boolean)` :1225; vanilla usage ClientPacketListener :1942 | 262:client/gui/Gui.java:72; Hud.java:1221-1225; ClientPacketListener.java:1942 |
| C35 | CHANGED 24: static `Profiler.get()` :47 returns `ProfilerFiller` | 262:util/profiling/Profiler.java:47 |
| C36 | CHANGED 26: `Util` in `net.minecraft.util`; `NIL_UUID` :121; `backgroundExecutor()` :252 returns `TracingExecutor` | 262:util/Util.java:1,121,252 |
| C37 | CHANGED 4/5: `EntityType.create(Level, EntitySpawnReason)` :298; Builder `of` :479 / `sized` :487 / `fireImmune` :550 / `clientTrackingRange(int clientChunkRange)` :565 / `updateInterval` :570 / **`build(ResourceKey<EntityType<?>>)`** :590; `EntityFactory.create(EntityType,Level)` :616; `EntitySpawnReason.EVENT` :11 / `LOAD` :21 | 262:world/entity/EntityType.java; EntitySpawnReason.java |
| C38 | CHANGED 2/3: `Entity.saveWithoutId(ValueOutput)` :2059; `Entity.load(ValueInput)` :2133; `TagValueInput.java`/`TagValueOutput.java` both exist in `world/level/storage/` | 262:world/entity/Entity.java:2059,2133; dir listing |
| C39 | CHANGED 10: `ChunkAccess.setBlockState(BlockPos, BlockState, @Block.UpdateFlags int)` abstract :127; 2-arg overload defaults flags=3 :123-124 | 262:world/level/chunk/ChunkAccess.java:123-127 |
| C40 | CHANGED 15: `BuiltInRegistries.ENTITY_TYPE` **defaulted** to `"pig"` :186-187; `Registry.getValue(@Nullable Identifier)` :68 nullable; `getOptional` :72; `get(Identifier)` → `Optional<Holder.Reference<T>>` :134 — the pig-fallback fidelity note is real | 262:core/registries/BuiltInRegistries.java:186-187; core/Registry.java:66-72,134 |
| C41 | CHANGED 22: `Player.sendSystemMessage(Component)` :1342 / `sendOverlayMessage(Component)` :1345; ServerPlayer overrides `sendOverlayMessage` (~:1786-1789) | 262:world/entity/player/Player.java; ServerPlayer.java |
| C42 | CHANGED 25: `Level.isClientSide` field private :127; `isClientSide()` method :163 | 262:world/level/Level.java:127,163 |
| C43 | S34-S36 mixin-target survival: `PortalShape.findEmptyPortalShape(LevelAccessor,BlockPos,Axis)` :50 + `createPortalBlocks(LevelAccessor)` :175; `BaseFireBlock.onPlace` 5-arg :153, `findEmptyPortalShape` call :156, `createPortalBlocks(level)` :158; `isPortal` ends `.isPresent()` :213; `FlintAndSteelItem.useOn` :26; `ItemStack.useOn` :357; `ItemEntity.tick` :109 — all match IP's @Redirect/@Inject targets (C21, C22) | 262:world/level/portal/PortalShape.java; block/BaseFireBlock.java:153-167,190-214; item/FlintAndSteelItem.java:26; item/ItemStack.java:357; entity/item/ItemEntity.java:109 |
| C44 | S6: `WorldBorder.isWithinBounds(BlockPos)` :48; `clampToBounds(double,double,double)` :84 (IP's int args widen — source-compatible as stated) | 262:world/level/border/WorldBorder.java:46-49,84-86 |
| C45 | S8: `is(TagKey)` moved to `TypedInstance.is` (default :14); `BlockStateBase implements TypedInstance<Block>` :409 — call sites compile unchanged | 262:core/TypedInstance.java:14-16; block/state/BlockBehaviour.java:409 |
| C46 | S23: `ServerLevel.addFreshEntity` :959; UUID lookup on `Level.getEntity(UUID)` :781 | 262:server/level/ServerLevel.java:959; world/level/Level.java:781 |
| C47 | Cross-cutting: `ResourceLocation` → `net.minecraft.resources.Identifier` (`public final class Identifier` :18) | 262:resources/Identifier.java:18 |
| C48 | CHANGED 13/14: `CompoundTag.getList(String)` → `Optional<ListTag>` :359, `getListOrEmpty` :363 (no NBT-type parameter) | 262:nbt/CompoundTag.java:355-365 |

---

## 5. Internal-consistency scan

- No contradictions between the two docs. Every 26.2-sensitive item bold-flagged in INV §4 has a corresponding MAP GONE/CHANGED row.
- MAP's Fabric-API networking row now correctly distinguishes GlobalPortalStorage.java:148-157 (the `createS2CPacket` call, verified C23) from ImmPtlNetworking.java:148-157 (PortalSyncPacket block) — the pass-1 refutation is resolved in the current doc.
- Fabric-API rows are correctly segregated (loader API, not verifiable against the vanilla decomp) and routed to the mod's PlatformHelper seam rather than asserted as vanilla facts.

## 6. Severity

**minor** — 2 refuted items: R1 (INV omits the `ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers` call inside `MixinServerPlayerEntity_MA`; would cause a dropped call if ported from the doc alone) and R2 (MAP's "NPE on null dataFixType" is actually a swallowed exception → silent discard of `global_portal.dat`; the must-pick-a-real-constant directive is unchanged). All geometry, sign, transform, pipeline, threading, and API-fate claims checked out, including the highest-risk items: the +0.5 positive-axis mesh offset, the floor/ceil axis assignments, the f1↔t1/f2↔t2 cross-links, the height-inclusivity flip, and all five generation-pipeline mixin targets.
