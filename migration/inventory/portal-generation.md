# IP Subsystem Inventory: Portal Generation (nether / custom / global)

Slice owner packages (IP 1.21.3, Mojang mappings, `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`):
- `imm_ptl/core/portal/nether_portal/` (10 files, full read)
- `imm_ptl/core/portal/custom_portal_gen/` (5 files + `form/` 12 files)
- `imm_ptl/core/portal/global_portals/` (5 files, full read)
- `imm_ptl/peripheral/portal_generation/` (4 files, full read)

All file:line citations below are into that tree unless marked otherwise. Paths are abbreviated to the package-relative name after first use.

---

## 1. Overview

This slice is the **server-side machinery that decides when and where portal ENTITIES come into existence** in IP. It replaces the vanilla nether-portal creation path (via mixins into `BaseFireBlock` and `FlintAndSteelItem`) with a pipeline that: detects a frame of blocks around an ignition point (`BlockPortalShape` flood-fill), maps the position to the destination dimension (8:1 ratio etc.), loads destination chunks asynchronously with a progress indicator entity, searches the destination for an *existing* matching frame on a background thread (`FrameSearching` over a `FastBlockAccess` section-array snapshot), and either links to the found frame or fabricates a new obsidian frame at a found air-cube placement (`NetherPortalMatcher`) — finally filling the frame interior with the invisible luminous `PortalPlaceholderBlock` and spawning four `Portal` entities (2 faces × 2 sides) cross-linked as a cluster. The same pipeline is generalized as data-driven **custom portal generation** (`CustomPortalGeneration` + `PortalGenForm` subclasses, loaded from datapack dynamic registries, triggered by item-use / item-throw / conventional dimension travel), of which the intrinsic nether portal is just a hard-coded instance (`IntrinsicPortalGeneration`).

The second half of the slice is portal *lifecycle after generation*: `BreakablePortalEntity` (base of `NetherPortalEntity` / `GeneralBreakablePortal`) owns a `BlockPortalShape`, monitors frame + placeholder integrity every tick-window or on placeholder neighbor-update notification, and kills itself (and its reverse-side twin) when the frame is broken — this is exactly what the mod's `PortalDetector`/`PortalShapeForm` must be replaced by. Finally, `global_portals/` is a separate storage mechanism: portals that are **not** world entities but live in per-dimension `SavedData` ("global_portal"), full-NBT-synced to every client (world wrapping borders, floor/ceiling dimension connections).

---

## 2. Class-by-class inventory

### 2.1 `nether_portal/` (all common/server unless noted)

#### `BlockPortalShape` (~513 LOC, common)
The canonical description of a block-frame portal: a set of `BlockPos` forming the flat interior area plus derived frame sets.
- **State**: `anchor` (min-XYZ corner of area, BlockPortalShape.java:32,101-114), `area` (Set<BlockPos>), `innerAreaBox`/`totalAreaBox` (`IntBox`, :34-35,116-127), `axis` (the portal's NORMAL axis), `frameAreaWithoutCorner` / `frameAreaWithCorner` (:37-38,129-162), `firstFramePos` (arbitrary first frame element, :40,161). `defaultLengthLimit = 64` static (:31).
- **Key API**:
  - `findArea(BlockPos startingPos, Axis, Predicate<BlockPos> isAir, Predicate<BlockPos> isObsidian)` (:165-187) — requires starting pos be area; delegates to
  - `findShapeWithoutRegardingStartingPos(...)` (:190-230) — BFS flood fill (below).
  - `matchShape(isAir, isObsidian, newAnchor, MutableBlockPos temp)` (:281-313) — tests whether this shape translated so `anchor→newAnchor` matches predicates (frame-without-corner all obsidian, area all air); returns translated copy.
  - `matchShapeWithMovedFirstFramePos(...)` (:414-447) — same but keyed off `firstFramePos` instead of anchor (legacy path, still public).
  - `isFrameIntact(isObsidian)` (:346-350) / `isPortalIntact(isPortalBlock, isObsidian)` (:352-358) — integrity predicates over `frameAreaWithoutCorner` and `area`.
  - `initPortalPosAxisShape(Portal, AxisDirection)` (:360-365) and `initPortalAxisShape(Portal, Vec3 center, Direction facing)` (:367-412) — **the block-shape→portal-entity geometry bridge**: sets portal pos to `innerAreaBox.getCenterVec()`, `axisW`/`axisH` from `Helper.getPerpendicularDirections(facing)`, width/height from box size; if the area is a full rectangle calls `portal.setPortalShapeToDefault()`, else builds a `Mesh2D` of unit quads (offset +0.5 along positive axis, :379-406), `simplify()`s it and sets `SpecialFlatPortalShape` (:410).
  - `toTag()`/`fromTag(CompoundTag)` (:81-99) — NBT: flat int-list `poses` (x,y,z triplets) + `axis` ordinal.
  - `isSquareShape(shape, len)` (:449-457), `getSquareShapeTemplate(axis, len)` (:459-479), `getShapeWithMovedAnchor` (:334-344), `getShapeWithMovedTotalAreaBox` (:481-488), `getShapeInnerLength` (:490-493), `isRectangle` (:495-498), `equals/hashCode` on (area, axis) (:500-512).
- **Deps**: `qouteall.q_misc_util` (`Helper`, `IntBox`, `Mesh2D`), `core.portal.Portal`, `core.portal.shape.SpecialFlatPortalShape`.
- **MC touch**: `BlockPos`(+Mutable), `Direction`/`Axis`, `CompoundTag`/`ListTag`/`IntTag`, `Tuple`, `Vec3`.
- Note comment at :29 — "TODO eventually replace it with FastBlockPortalShape".

#### `FastBlockPortalShape` (~370 LOC record, common)
Allocation-free 2D-compressed mirror of `BlockPortalShape` used in hot matching loops. Plane coords compressed to `(a,b)` ints packed into longs via `ChunkPos.asLong` (:87-100). Fields: `basePosX/Y/Z` (world pos of an arbitrary frame block = "base"), `axis`, three `int[]` local coord arrays (area / frame-without-corner / frame corners) relative to base, plus world-coordinate `innerAreaBox`/`totalAreaBox` (:23-39).
- `create(axis, int[] areaBlockCoords, coordOnAxis)` (:102-238) — computes frame + corner sets, bounds, picks first frame pos as base.
- `matchShape(newBaseX/Y/Z, TriIntPredicate framePredicate, TriIntPredicate areaPredicate)` (:323-355) — frame first, then area, pure int math.
- `withNewBase(x,y,z)` (:357-369) — **caution: keeps the OLD `innerAreaBox`/`totalAreaBox`** (not recomputed); safe in IP only because every caller immediately round-trips via `toBlockPortalShape()` (NBT → recompute, :319-321).
- `fromBlockPortalShape`/`toBlockPortalShape` go through NBT tags (:315-321). `fromTag` validates axis + pos-count and returns null on malformed data (:240-290).
- **Deps**: fastutil (`IntArrayList`, `LongOpenHashSet`), `q_misc_util.my_util.TriIntPredicate`, `IntBox`.

#### `BlockTraverse` (~149 LOC, common, pure utility)
Search-iteration helpers used by placement search: `searchFromTo` (inclusive, direction-aware, :24-42), `searchOnPlane` (square spiral from center, layer 1..range-1, :44-84), `searchColumnedRaw`/`searchColumned` (spiral × Y-range with a reused `MutableBlockPos`, :86-112), `searchInBox` (:114-137), `boxAllMatch` (:140-148).
- **Upstream wart**: `boxAllMatch` returns `searchInBox(...) != null` where the lambda returns TRUE on the **first position satisfying** the predicate — i.e. it is actually **any-match**, despite the name. Its one call site NetherPortalMatcher.java:110 therefore accepts a ground box if *any* bottom-layer block is air-on-ground. Port this behavior verbatim (fidelity rule); do not "fix" silently.

#### `NetherPortalMatcher` (~275 LOC, common)
Air-cube placement search for new frames in the destination dimension.
- `findVerticalPortalPlacement(BlockPos areaSize, LevelAccessor, BlockPos searchingCenter)` (:25-75) — three staged `getAirCubeOnGround` attempts: 6-spacing/range-8/solid-ground from Y=64..maxContentY; 2-spacing/range-10/solid; 2-spacing/range-10/any-ground full height. Then `pushDownBox` if standing on solid, else `levitateBox(40)` (:64-73).
- `findHorizontalPortalPlacement` (:127-149) — for Y-axis (horizontal) portals: air column reserve 30→10→1 blocks above, radius 12, via `findCubeAirAreaAtAnywhere` then recenter.
- `findCubeAirAreaAtAnywhere(areaSize, world, center, radius)` (:192-213) — spiral+column scan for all-air box within height limits.
- `isAirCubeMediumPlace` (:215-225) rejects boxes touching build limits; `isAllAir` does 8-vertices rough test then full scan (:227-237).
- `levitateBox` (:241-256): finds max up-shift in `[1, maxOffset*3/2)` keeping air, then moves by 2/3 of it. `pushDownBox` (:258-273): max down-shift in `[0,40)`.
- **MC touch**: `LevelAccessor.isEmptyBlock/getBlockState`, `BlockState.isSolid/isAir`, height accessors via `McHelper.getMinY/getMaxYExclusive/getMaxContentYExclusive` (McHelper.java:864-874 → `getMinBuildHeight`/`getMaxBuildHeight`/`dimensionType().logicalHeight()`).

#### `FastBlockAccess` (~189 LOC record, common)
Flat `LevelChunkSection[]` snapshot of a chunk region (`[dx + dy*lX + dz*lX*lY]` indexing) for lock-free background reads; javadoc :21-26 notes it avoids `WorldGenRegion` out-of-bounds throws and `BlockPos` allocation.
- `from(Level, ChunkPos center, radiusChunks)` (:34-47) / `from(Level, six-bounds)` (:53-97) — pulls sections via `world.getChunkSource().getChunk(cx, cz, false)`, skipping `EmptyLevelChunk` and all-air sections (:76-92).
- `getBlockState(x,y,z)` (:99-113) returns AIR when out of range/absent; `getSection(cx,cy,cz)` (:115-130); `chunkPoses()`/`sectionPoses()` streams; min/max section accessors (:153-187).
- **MC touch (version-sensitive)**: `Level.getMinSection()`/`getMaxSection()` (:40-43,63-64), `LevelChunk.getSections()` (:80), `LevelChunkSection.getBlockState(local)`/`hasOnlyAir()` (:83,112), `ChunkSource.getChunk(int,int,boolean)` (:78).

#### `FrameSearching` (~153 LOC, common; runs on background executor)
- `startSearchingPortalFrameAsync(FastBlockAccess region, int regionRadius, BlockPos centerPoint, Predicate<BlockState> framePredicate, FrameSearchingFunc<T> matchShape, Consumer<T> onFound, Runnable onNotFound)` (:29-63) — `CompletableFuture.runAsync(..., Util.backgroundExecutor())`; success/not-found are posted back with `MiscHelper.getServer().execute(...)` (:46-53). **Wart:** in the `catch (Throwable)` path `onNotFound.run()` executes directly on the background thread (:55-58) — not re-scheduled to the server thread.
- `searchPortalFrame` (:67-87): orders chunk columns near→far from center (`getChunksFromNearToFar` :141-152, sorted by `chunk.getWorldPosition().distSqr(centerPoint)`), then `searchPortalFrameWithYRange` (:91-139) does hand-rolled nested loops (comment :89 "After removing the usage of stream API, it becomes 100 times faster") over sections/blocks; on each block passing `framePredicate` calls `matchShape.searchAt(access, worldX, worldY, worldZ)` — first non-null result wins.
- `FrameSearchingFunc<T>` interface (:22-24) — the pluggable matcher, always producing `PortalGenInfo` in practice.

#### `NetherPortalGeneration` (~306 LOC, server)
The orchestration core. Static methods only.
- `findFrameShape(ServerLevel, BlockPos startingPos, Predicate<BlockState> thisSideAreaPredicate, Predicate<BlockState> thisSideFramePredicate)` (:264-282) — tries all 3 axes with `BlockPortalShape.findShapeWithoutRegardingStartingPos`, first success wins.
- `startGeneratingPortal(fromWorld, toWorld, fromShape, toPos, existingFrameSearchingRadius, otherSideFramePredicate, newFrameGenerateFunc, portalEntityGeneratingFunc, newFramePlacer, portalIntegrityChecker, matchShapeByFramePos)` (:110-234) — **THE flow**; see Mechanisms §3.2.
- `findAirCubePlacement(toWorld, mappedPos, axis, neededAreaSize, allowForcePlacement)` (:42-89) — random ±1 XZ shift then axis-appropriate `NetherPortalMatcher` search; fallback `findCubeAirAreaAtAnywhere(…, 32)` + `levitateBox(50)` if floating (:66-74); if still null and `allowForcePlacement` (creative player), force `IntBox.fromBasePointAndSize(mappedPos, size)` overwriting blocks (:77-83).
- `embodyNewFrame(toWorld, toShape, frameBlockState)` (:284-292) — `setBlockAndUpdate` over `frameAreaWithCorner`.
- `fillInPlaceHolderBlocks(world, shape)` (:294-303) → `setPortalContentBlock` (:97-108): sets `PortalPlaceholderBlock.instance` with `AXIS = shape.axis` over the area.
- `isOtherGenerationRunning(fromWorld, indicatorPos)` (:236-248) — presence of a `LoadingIndicatorEntity` within 1 block aborts (dedup of concurrent generations).
- `checkPortalGeneration(fromWorld, startingPos)` (:252-262) — chunk-loaded check + `LimitedLogger(50)` attempt log.
- **Deps**: `chunk_loading.ChunkLoader`/`DimensionalChunkPos`/`ImmPtlChunkTracking`, `mc_utils.ServerTaskList`, `platform_specific.O_O`, `LoadingIndicatorEntity`, `PortalPlaceholderBlock`, `custom_portal_gen.PortalGenInfo`.
- **MC touch**: `ServerLevel.setBlockAndUpdate/getRandom/hasChunkAt/dimension/getServer`, `Entity.remove(RemovalReason.KILLED)`, `Level.addFreshEntity`, `Component.translatable`.

#### `BreakablePortalEntity` (~294 LOC, abstract, extends `Portal`; common with client bits)
Base class of block-frame-backed portal entities.
- **State**: `blockPortalShape` (NOT synched to client — server-only in practice; nbt `netherPortalShape`, :70-74,113-115), `reversePortalId` UUID (nbt `reversePortalId`; `Util.NIL_UUID` = one-way, :76-80,283-289), `unbreakable` flag (:46,82), `isNotified` (starts true → integrity check on first tick, :47), `shouldBreakPortal` (:48), `overlayInfo` (`OverlayInfo(BlockState, opacity, offset, DQuaternion rotation)` record :36-42, nbt `overlayBlockState`/`overlayOpacity`/`overlayOffset`/`overlayRotation` :84-107,119-124) — client rendering of the portal-block overlay.
- `isPortalValid()` override (:62-67): server side additionally requires `blockPortalShape != null && reversePortalId != null`.
- `tick()` (:158-177): client → `addSoundAndParticle()`; server & breakable → integrity check when `isNotified` OR every 233 ticks staggered by `getId() % 233` (:167); then `breakPortalOnThisSide()` if flagged.
- `checkPortalIntegrity()` (:179-194): invalid → `remove(KILLED)`; `!isPortalIntactOnThisSide()` (abstract, :197) → `markShouldBreak()`; `!isPortalPaired()` → break with "abnormal pairing" log.
- `isPortalPaired()` (:204-232): one-way → true; other-side chunk unloaded → true (benign); `findReversePortals` (static :271-281 — `McHelper.findEntitiesByBox` of same class at dest pos, predicate origin-within-0.1-of-destPos AND `contentDirection·normal > 0.6`); exactly 1 rev whose destPos is within 1 of our origin → paired; >1 revs → false; 0 revs → **true** (tolerated, err log commented out :229).
- `markShouldBreak()` (:234-268): flags self; flags reverse portal via `getReversePortal()` (:146-156, `getServer().getLevel(destDim).getEntity(reversePortalId)`); if reverse not resolvable, enqueues `MyTaskList.withRetryNumberLimit(30, ...)` on `ServerTaskList` retrying while other-side chunk unloaded (:246-266).
- `breakPortalOnThisSide()` (:127-140): area positions still holding `PortalPlaceholderBlock` → AIR via `setBlockAndUpdate`, then `remove(KILLED)`.
- `notifyPlaceholderUpdate()` (:142-144) sets `isNotified` — called by `PortalPlaceholderBlock.updateShape` (see §3.4).
- Client abstract `addSoundAndParticle()` (`@Environment(CLIENT)` :199-200).

#### `NetherPortalEntity` (~140 LOC, extends BreakablePortalEntity)
The intrinsic nether portal entity. `ENTITY_TYPE` static (:59-60, via `Portal.createPortalEntityType`).
- `isPortalIntactOnThisSide()` (:72-82): all area blocks are `PortalPlaceholderBlock` AND all `frameAreaWithoutCorner` pass `O_O.isObsidian` (identity check vs `Blocks.OBSIDIAN.defaultBlockState()`, O_O.java:61-65).
- `addSoundAndParticle()` client (:85-126): `ParticleTypes.PORTAL` scaled to portal area, `SoundEvents.PORTAL_AMBIENT` at 1/800 chance via `level().playLocalSound`; gated by `IPGlobal.enableNetherPortalEffect`.
- `getActualOverlay()` (:128-139): if `IPGlobal.netherPortalOverlay` config, returns one of 4 static `OverlayInfo`s of `Blocks.NETHER_PORTAL` state by shape axis (X/Y-up/Y-down/Z; Y variants rotated 90° about X, :21-56).

#### `GeneralBreakablePortal` (~31 LOC)
Custom-gen breakable portal: intact = area all placeholder + frame all **non-air** (`!level().isEmptyBlock`, :17-25). No sound/particles. Own `ENTITY_TYPE` (:9-10).

### 2.2 `custom_portal_gen/`

#### `CustomPortalGeneration` (~290 LOC, server)
One datapack entry = one generation rule. Codec-built (`codecV1` :65-80): fields `from` (dimension list; sentinels `imm_ptl:the_same_dimension` / `imm_ptl:any_dimension` :34-42), `to`, `space_ratio_from`/`space_ratio_to` (default 1), `reversible` (default true), `form` (`PortalGenForm.GENERAL_CODEC`), `trigger` (`PortalGenTrigger.triggerCodec`), `post_invoke_commands`, `commands_on_generated` (list of list, per portal). Registry keys: `immersive_portals:custom_portal_generation` (data at `/data/<ns>/immersive_portals/custom_portal_generation/`) + legacy `custom_portal_generation` (:57-63). Schema dispatch registry `imm_ptl:custom_portal_gen_schema` with single `imm_ptl:v1` entry, `MAP_CODEC` dispatches on `"schema_version"` (:53-98).
- `perform(ServerLevel world, BlockPos startPos, @Nullable Entity triggeringEntity)` (:236-269): dimension gate (`fromDimensions.contains` or first==ANY), chunk-loaded gate, resolves `toWorld` (THE_SAME_DIMENSION → same), wraps `form.perform` in profiler push `custom_portal_gen_perform`.
- `mapPosition(BlockPos from, fromWorld, toWorld)` (:164-182): `from * spaceRatioTo / spaceRatioFrom` (`Helper.divide(Helper.scale(...))`); clamps to `toWorld.getWorldBorder()` — `isWithinBounds(BlockPos)` / `clampToBounds(x,y,z)` then ×0.9 on X/Z (:170-179).
- `getReverse()` (:130-162): same-dimension → swap ratios + `form.getReverse()`, `reversible=false`; else swap to/from[0].
- `initAndCheck(server)` (:207-226) returns sealed `InitializationResult` (Ok / dst-dim-invalid / no-src-dim-valid, :184-205) checking `server.getLevel(dim) != null`.
- `onPortalsGenerated(Portal[])` (:271-289): stamps `portal.portalTag = identifier`, runs `post_invoke_commands` on every portal and `commands_on_generated[i]` on the i-th via `McHelper.invokeCommandAs` (permission-2 suppressed-output command source, McHelper.java:435-444).

#### `CustomPortalGenManager` (~257 LOC, server)
Holds active generations keyed by trigger. `Multimap<Item, CustomPortalGeneration> useItemGen / throwItemGen`, `ArrayList convGen`, `Map<UUID, WithDim<Vec3>> playerPosBeforeTravel` (:37-41).
- `init()` (:43-58): registers both dynamic registries via **Fabric `DynamicRegistries.register`**, rebuild on `ServerLifecycleEvents.END_DATA_PACK_RELOAD` + `SERVER_STARTED` → `onDataPackReloaded` (:60-100) which iterates `server.registryAccess().registryOrThrow(REGISTRY_KEY)` (+legacy) and installs a fresh manager into `IPPerServerInfo.of(server).customPortalGenManager` (:98-99). Gated by `IPGlobal.enableDatapackPortalGen` (:61).
- `addEntry` (:102-142): sets `gen.identifier = key.location()`, `initAndCheck`, `load(gen)` (dispatch into the trigger maps :144-158). **Upstream oddity: the reverse generation is created and validated (:119-141) but `load(reverse)` is NEVER called — the reverse direction of a `reversible` datapack gen is not registered into any trigger map.** (Intrinsic nether generation does not rely on this; it holds both directions statically.)
- `onItemUse(UseOnContext, InteractionResult)` (:160-189): server-side, if item has gens, defers to next tick via `ServerTaskList`; on success consumes item per `UseItemTrigger.shouldConsume` (not in creative).
- `onItemTick(ItemEntity)` (:194-221): only entities with a thrower and still in pickup-delay (:197); defers `perform(entity.level(), entity.blockPosition(), entity)`, shrinks the stack on success.
- `onBeforeConventionalDimensionChange(ServerPlayer)` (:223-229) records `WithDim<Vec3>`; `onAfterConventionalDimensionChange` (:231-256) replays `convGen` list against the recorded start world/pos with the player as triggering entity.

#### `PortalGenInfo` (~127 LOC, server)
The result object of frame matching / placement: `from`/`to` dims, `fromShape`/`toShape` (`BlockPortalShape`), optional `rotation` (`DQuaternion`) + `scale` (near-identity snapped to null/1.0 in ctor, :59-68). Also `SignalArged<PortalGenInfo> generatedSignal` (:22, emitted from `generatePlaceholderBlocks` :125).
- `createTemplatePortal(EntityType<T>)` (:71-83): `entityType.create(fromWorld)`, `fromShape.initPortalPosAxisShape(portal, POSITIVE)`, dest = `toShape.innerAreaBox.getCenterVec()`, scaling + rotation.
- `generateBiWayBiFacedPortal(EntityType<T extends BreakablePortalEntity>)` (:85-113): template f1 → `PortalManipulation.createFlippedPortal(f1)` = f2 → `createReversePortal(f1)` = t1 → flipped t2; assigns `blockPortalShape` (from-shape on f1/f2, to-shape on t1/t2) and cross `reversePortalId`s (f1↔t1, f2↔t2, :100-103); `PortalExtension.initializeClusterBind(f1,f2,t1,t2)` (bindCluster + flipped/reverse UUID links, PortalExtension.java:450-464); spawns all 4 via `McHelper.spawnServerEntity` (= `level.addFreshEntity` with error log, McHelper.java:835-843).
- `generatePlaceholderBlocks()` (:115-126): `NetherPortalGeneration.fillInPlaceHolderBlocks` on both sides.

#### `PortalGenTrigger` (~119 LOC, common)
Codec-dispatched trigger hierarchy: `UseItemTrigger(item, consume)` (:26-54), `ThrowItemTrigger(item)` (:56-67), `ConventionalDimensionChangeTrigger` (:69-77). Own `MappedRegistry` `imm_ptl:custom_portal_gen_trigger` with ids `imm_ptl:use_item` / `imm_ptl:throw_item` / `imm_ptl:conventional_dimension_change`; `triggerCodec = codecRegistry.byNameCodec().dispatchStable(...)` (:95-116). Items resolved via `BuiltInRegistries.ITEM.byNameCodec()` (:81,88).

#### `SimpleBlockPredicate` (~156 LOC, common)
String codec for a block OR block-tag predicate (name kept for re-encoding). Custom `Codec` implementation requires `RegistryOps` and pulls `HolderGetter<Block>` from `registryOps.getter(Registries.BLOCK)` (:83-99); `#`-prefix or unresolvable id → `TagKey`; literal `minecraft:air` → special `AirPredicate` matching `blockState.isAir()` (covers cave/void air, :66-76,124-130). `pass` singleton always-true (:26).

### 2.3 `custom_portal_gen/form/` (moderate depth — 2-3 sentences each)

#### `PortalGenForm` (~70 LOC, abstract)
Base of all forms; static `CODEC_REGISTRY` (`MappedRegistry` `imm_ptl:custom_portal_gen_form`) registering the 8 form codecs under `imm_ptl:classical|heterogeneous|flipping_floor_square|scaling_square|flipping_floor_square_new|try_hard_to_match|convert_conventional_portal|one_way` (:20-52); `GENERAL_CODEC` dispatches on registry name (:54-57). Abstract: `getCodec()`, `getReverse()`, `perform(cpg, fromWorld, startingPos, toWorld, triggeringEntity)` (:59-69).

#### `NetherPortalLikeForm` (~211 LOC, abstract)
The template-method form implementing the full nether-portal-like pipeline in `perform` (:31-108); see §3.2. Subclass hooks: `generateNewFrame`, `getOtherSideFramePredicate`, `getThisSideFramePredicate`, `getAreaPredicate`, `testThisSideShape` (default true), `getFrameMatchingFunc` (default: exact-translation `FastBlockPortalShape.matchShape` rejecting a self-match in same-dim, :110-143), `getNewPortalPlacement` (default: `findAirCubePlacement` with creative-player force-place + "no place to generate portal" client message, :145-188), `generatePortalEntitiesAndPlaceholder` (default: placeholders + `generateBiWayBiFacedPortal(GeneralBreakablePortal.ENTITY_TYPE)`, :190-193). Field `generateFrameIfNotFound` (:25).

#### `ClassicalForm` (~81 LOC)
Datapack form with three concrete `Block` fields (`from_frame_block`, `area_block`, `to_frame_block`). Frame generation = `embodyNewFrame` with `toFrameBlock` default state (:53-65); predicates are block-identity (:67-80). Reverse swaps from/to frame blocks (:44-51).

#### `HeterogeneousForm` (~73 LOC)
Form whose frame may be a mix: `SimpleBlockPredicate areaBlock / frameBlock` (both sides use the same frame predicate, :47-60). `generateNewFrame` **clones** the from-side frame blocks block-by-block into the destination at the inner-box offset (:36-45). Reverse = same config (:67-72).

#### `FlippingFloorSquareForm` (~216 LOC)
Standalone (non-NetherPortalLike) form for horizontal square floor portals that "flip" (destination portal 180°-rotated about X, so you fall out of the floor of the other side). `perform` (:78-137) checks area+bottom predicates, `BlockPortalShape.findArea` on Y axis, exact square-size check + up-frame/bottom-block predicates (:139-150), scans destination top-down within a 16×16 XZ window for a placement (`findPortalPlacement` :152-183, non-solid-render + fluid-free box on non-air ground), clones the frame + frame-above blocks, fills placeholders both sides, then `createPortals` (:185-214) — a 2-portal bi-way pair (pa with `DQuaternion.rotationByDegrees((1,0,0),180)` :194-197, pb = `createReversePortal`), `motionAffinity = 0.1` on both `PortalExtension`s (:207-208).

#### `FlippingFloorSquareNewForm` (~101 LOC, extends HeterogeneousForm)
NetherPortalLike-pipeline version of the flipping floor portal: overrides `testThisSideShape` to require a Y-axis full square (:51-62), `getNewPortalPlacement` to use `FlippingFloorSquareForm.findPortalPlacement` and a 180°-X-rotated `PortalGenInfo` (:65-87), and `generatePortalEntitiesAndPlaceholder` to delegate to `FlippingFloorSquareForm.createPortals` (:38-48).

#### `ScalingSquareForm` (~167 LOC)
Square portal with different side lengths on each side (`from_length`/`to_length` → scale = to/from, :107-109). `getFrameMatchingFunc` matches a *template* square of `toLength` (`BlockPortalShape.getSquareShapeTemplate`) rather than the from-shape (:72-105); `getNewPortalPlacement` places that template with force-place always allowed (:126-151); new frame fills `frameAreaWithCorner` with `toFrameBlock` (:119-123).

#### `AbstractDiligentForm` (~60 LOC, abstract)
NetherPortalLike variant whose frame matching tries **every rotated/scaled variant** of the from-shape: precomputes `DiligentMatcher.getMatchableShapeVariants(fromShape, 64)` and tests each `fastTransformedShape` at every candidate base pos, producing `PortalGenInfo` with the variant's rotation quaternion + scale (:18-59).

#### `DiligentForm` (~72 LOC)
Datapack-facing concrete `AbstractDiligentForm` (`imm_ptl:try_hard_to_match`) with three `Block` fields like ClassicalForm; new frame = `frameAreaWithCorner` of `toFrameBlock` (:52-56).

#### `DiligentMatcher` (~192 LOC, common, pure math)
Shape-variant generator: `getMatchableShapeVariants(original, maxShapeLen)` (:47-85) shrinks the shape by the GCD of the side lengths of its rectangle decomposition (`getShapeShrinkFactor` :101-117, `decomposeShape`/`splitBoxFromArea` using `Helper.expandRectangle` :139-191, `shrinkShapeBy` floor-div :119-137), then for each axis-aligned rotation in `AARotation.rotationsSortedByAngle` rotates (`rotateShape` via `IntMatrix3.transform`, :91-99), regularizes anchor to origin, dedupes by shape-equality, and adds integer upscales (`upscaleShape` :151-170) up to `maxShapeLen`. Result `TransformedShape{originalShape, transformedShape, IntMatrix3 rotation, double scale, fast*Shape}` (:27-45).

#### `ConvertConventionalPortalForm` (~264 LOC)
Converts another mod's conventional (vanilla-teleport) portal pair into IP portals after a player walks through it (trigger: `ConventionalDimensionChangeTrigger`). `perform` (:54-170) requires a ServerPlayer now standing in `toWorld`; finds a portal block within ±2 of the recorded start pos and of the player's arrival pos (`findBlockAround` :195-213), finds both `BlockPortalShape`s (area = the mod's portal block, frame = any non-air, :105-121), `tryToMatch` (:240-263) compares the to-shape against all `DiligentMatcher` variants of the from-shape (anchor-aligned equality) yielding rotation+scale; on match fills placeholders and creates either flipping-floor portals (both Y-axis, scale 1, no rotation) or a standard bi-way bi-faced `GeneralBreakablePortal` cluster (:141-167).

#### `OneWayForm` (~125 LOC)
Creates a single (optionally bi-faced flipped pair) one-way portal from a frame at the ignition point: destination = triggering entity's eye position in its current dimension (or +10Y of origin if none, :88-95); area cleared to AIR; placeholders only if `breakable`; `markOneWay()` (NIL reverse UUID) and `unbreakable = !breakable` (:96-119). Uses `PortalAPI.createFlippedPortal` for the bi-faced variant (:104).

### 2.4 `global_portals/`

#### `GlobalPortalStorage` (~396 LOC, extends `SavedData`; server + client receive path)
Per-dimension storage of global portals (portals not added into the world's entity system — see Portal.java:80-82 javadoc) + `bedrockReplacement` BlockState for dimension stack (:64-65).
- Obtained via `world.getDataStorage().computeIfAbsent(new SavedData.Factory<>(supplier, (nbt, holderLookup) -> load, null), "global_portal")` (:94-112). **26.2-sensitive: SavedData API.**
- `init()` (:67-92): `ServerTickEvents.END_SERVER_TICK` → `tick()` every tick per world (resync if `shouldReSync`, one-time v1→v2 upgrade no-op, :297-308); `IPGlobal.SERVER_CLEANUP_EVENT` → `onServerClose` removes portal entities (:376-380); DimLib `DimensionAPI.SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT` → `clearAbnormalPortals` + `syncToAllPlayers` (:81-87).
- Mutation API: `addPortal` (validates, sets `isGlobalPortal`, `myUnsetRemoved()`, :171-180), `removePortal` (:165-169), `removePortals(Predicate)` (:182-191); each `onDataChanged()` → `setDirty(true)` + resync flag (:159-163). `convertNormalPortalIntoGlobalPortal` / `convertGlobalPortalIntoNormalPortal` (:351-374) — kill + `McHelper.copyEntity` + re-add on the other track; global conversion forces `setPortalShapeToDefault` (comment: "global portal can only be square", :356).
- Persistence: `save` writes list of `portal.saveWithoutId` tags + `"entity_type"` = `EntityType.getKey(portal.getType())` (:266-295); `readPortalFromTag` does `BuiltInRegistries.ENTITY_TYPE.get(id)` → `entityType.create(currWorld)` → `e.load(tag)` → mark global → `updateCache()` (non-limited bounding box, :249-263).
- Sync: full-NBT `ImmPtlNetworking.GlobalPortalSyncPacket(dimIntId, tag)` (ImmPtlNetworking.java:93-127) built with Fabric `ServerPlayNetworking.createS2CPacket` (:148-157); sent to all players on change (:193-200) and to a joining player for every non-empty dimension (`onPlayerLoggedIn` :135-146, called from `MixinPlayerList.java:48`).
- Client receive: `receiveGlobalPortalSync(dim, tag)` (:325-349, `@Environment(CLIENT)`) — kills old list from the `IEClientWorld` duck (`ip_getGlobalPortals`/`ip_setGlobalPortals`, ducks/IEClientWorld.java:16-18), deserializes, `myUnsetRemoved`, validates, forces `ClientWorldLoader.getWorld(p.getDestDim())` to pre-init the dest client world (:343), installs list. `onClientCleanup` removes them (:119-128).
- `getGlobalPortals(Level)` static accessor for both sides (:382-395).

#### `GlobalTrackedPortal` (~19 LOC)
Marker subclass of `Portal` with its own `ENTITY_TYPE` (:8-10); note at :7 — do NOT use `instanceof GlobalTrackedPortal`, use `portal.getIsGlobal()`.

#### `VerticalConnectingPortal` (~184 LOC, extends GlobalTrackedPortal)
Floor/ceiling whole-dimension connection (dimension stack): `createConnectingPortal` (:88-156) makes a 23333333333-wide/high portal at world min/max Y (`McHelper.getMinY/getMaxContentYExclusive`), axisW/H chosen per connector type (floor: W=+Z,H=+X :107-110; ceil: W=+X,H=+Z :112-115), optional inversion (180° about X) and Y-rotation combined via `DQuaternion.hamiltonProduct` (:140-147), optional `coordinateScale`-ratio scaling with `teleportChangesScale=false` + `adjustPositionAfterTeleport=false` (:149-153). `connect`/`connectMutually` manage the `GlobalPortalStorage` entries (:39-86); `removeConnectingPortal` filters by normal-Y sign predicate (:25-33,158-174).

#### `WorldWrappingPortal` (~303 LOC, extends GlobalTrackedPortal)
World-border wrapping: per zone, 4 portals (N/S/W/E surfaces of an XZ box spanning full build height), `isInward` + `zoneId` persisted in NBT (:36-53). `initWrappingPortal` (:71-96) computes surface center/destination as opposite surface via `Helper.getBoxSurfaceInversed`. `WrappingZone` (:98-177) groups portals by zoneId, validity = exactly 4 with matching inwardness; `invokeAddWrappingZone` (:207-250) removes invalid zones then adds 4 portals to storage; view/remove commands (:252-301).

#### `BorderBarrierFiller` (~166 LOC, server)
Command helper that clears (sets AIR) all blocks in the 1-block-thick columns of a wrapping zone's border, with a two-invocation confirm-warning flow (`WeakHashMap warnedPlayers`, :23,68-104). Work runs through `McHelper.performMultiThreadedFindingTaskOnServer` (McHelper.java:130+) over column positions, calling `world.getChunk(pos)` + `chunk.setBlockState(pos, AIR, false)` + `ThreadedLevelLightEngine.checkBlock(pos)` per block (:132-146) with progress messages every 20 ticks (:147-154).

### 2.5 `peripheral/portal_generation/`

#### `IntrinsicPortalGeneration` (~137 LOC, server)
Hard-coded `CustomPortalGeneration` instances wiring the vanilla nether portal into the custom-gen pipeline: `intrinsicToNether` (OVERWORLD→NETHER, ratio 8:1, `IntrinsicNetherPortalForm`, trigger `null`, :28-34) + `intrinsicFromNether = getReverse()` (:36); `diligentToNether`/`diligentFromNether` with `DiligentNetherPortalForm` (:38-46); `portalHelper` (ANY_DIMENSION→THE_SAME_DIMENSION, `PortalHelperForm`, :48-54). `init()` only stamps identifiers (`imm_ptl:intrinsic_nether_portal` etc., :56-64).
- `onFireLitOnObsidian(fromWorld, firePos, triggeringEntity)` (:71-98): mode `normal`→intrinsic form, `adaptive`→diligent form, only from OVERWORLD or NETHER; calls `gen.perform`.
- `onCrouchingPlayerIgnite` (:107-136): crouching + config `lightVanillaNetherPortalWhenCrouching` → vanilla `PortalShape.findEmptyPortalShape(world, firePos, Axis.X)` + `createPortalBlocks()` (:127-135) — the escape hatch to make a VANILLA portal.
- `activatePortalHelper` (:100-105) → `portalHelper.perform`.

#### `IntrinsicNetherPortalForm` (~102 LOC, extends NetherPortalLikeForm)
The actual nether portal form: frame = obsidian both sides (other side additionally flags `encounteredVanillaPortalBlock` when it sees `Blocks.NETHER_PORTAL`, a `static volatile` used to show "cannot connect to vanilla portal" warning at placement time, :43-56,67-81); area = `BlockStateBase::isAir` (:88-91); new frame = obsidian over `frameAreaWithCorner` (:31-35); portal entities = `generateBiWayBiFacedPortal(NetherPortalEntity.ENTITY_TYPE)` (:58-64). `getCodec()` throws (never serialized, :93-96).

#### `DiligentNetherPortalForm` (~63 LOC, extends AbstractDiligentForm)
"Adaptive" nether portal mode: same obsidian/air predicates and obsidian frame generation, but matching allows rotation/scale variants; spawns `NetherPortalEntity` (:32-37).

#### `PortalHelperForm` (~97 LOC, extends AbstractDiligentForm)
Portal-helper-block form (same-dimension). New frame uses `PeripheralModMain.portalHelperBlock` and messages nearby players "not linked" (:29-45). On success **replaces** the standard generation: clears area, removes one frame block from each side (`firstFramePos` → AIR, :55-56), spawns a **4-portal `Portal.ENTITY_TYPE` cluster** (not breakable; `bindCluster=true` via `PortalExtension`) with `createFlippedPortal`/`createReversePortal` (:58-70).

### 2.6 Supporting classes outside the slice packages (read for wiring)

- **`core/portal/PortalPlaceholderBlock`** (~156 LOC): the invisible, light-15, no-collision, no-loot block filling portal interiors (`AXIS` = portal normal axis; per-axis thin `VoxelShape`s, :30-65). `updateShape` override (:96-122): on server, when a neighbor changes from a direction whose axis ≠ the block's AXIS (i.e. in-plane), finds `BreakablePortalEntity`s via `McHelper.findEntitiesRough(range 2)` and calls `notifyPlaceholderUpdate()`. Also `propagatesSkylightDown = true`, `RenderShape.INVISIBLE`, `getShadeBrightness = 1` (:134-155). Registered as `immersive_portals:nether_portal_block` (IPModMain.java:155-160).
- **`core/portal/LoadingIndicatorEntity`** (~156 LOC): progress-display entity used during generation; synched data `TEXT` (Component), `BOX_LOW_POS`/`BOX_HIGH_POS` (`EntityDataSerializers.COMPONENT`/`BLOCK_POS`, :34-42); server kills it if `isValid` not set (not persisted → cleans up after crash/restart, :44,57-62); client emits portal particles over the box and shows the text via `Gui.setOverlayMessage` when the player is within 16 blocks (:65-106,149-155).
- **`Portal.createPortalEntityType(factory)`** (Portal.java:94-108): `FabricEntityTypeBuilder.create(MobCategory.MISC, ctor).dimensions(EntityDimensions.fixed(0,0)).fireImmune().trackRangeBlocks(96).trackedUpdateRate(20).forceTrackedVelocityUpdates(true).build()` — every portal entity type in this slice is created through this.
- **Entity type registration** (IPModMain.java:162-213): `registerEntityTypes(BiConsumer)` registers `immersive_portals:portal`, `nether_portal_new` (NetherPortalEntity), `general_breakable_portal`, `global_tracked_portal`, `border_portal` (WorldWrappingPortal), `end_floor_portal` (VerticalConnectingPortal), `loading_indicator`, plus mirror/end-portal types owned by other slices.

---

## 3. Mechanisms

### 3.1 Frame detection: `BlockPortalShape` BFS (replaces the mod's PortalDetector)

`findShapeWithoutRegardingStartingPos(startingPos, axis, isAir, isObsidian, lengthLimit=64)` (BlockPortalShape.java:196-230):
1. Seed `area = {startingPos}`; BFS queue over the 4 in-plane directions for the given normal axis (`Helper.getAnotherFourDirections(axis)`).
2. For each dequeued pos and each direction (:245-265): neighbor already known → skip; `isAir` → enqueue + add to area; `isObsidian` → boundary, stop expanding that way; **anything else → the whole search FAILS** (return null) — the region must be sealed entirely by frame-predicate blocks.
3. Abort if `area.size() > lengthLimit²` (:246-248).
4. Construct `BlockPortalShape(area, axis)` — ctor computes anchor (lexicographic min), frame sets (all 4-neighbor offsets not in area; corners via diagonal offsets minus area, :129-162), and inner/total `IntBox`es; reject if any inner box dimension exceeds `lengthLimit` (:224-227).

`NetherPortalGeneration.findFrameShape` (NetherPortalGeneration.java:264-282) runs this for **X, then Y, then Z** axis at the fire position and takes the first non-null — so a fire position enclosed in multiple orientations resolves in that axis order.

### 3.2 The generation pipeline: `NetherPortalLikeForm.perform` → `NetherPortalGeneration.startGeneratingPortal`

Trigger entry (intrinsic path): flint&steel on obsidian → `MixinFlintAndSteelItem_CVB.onUseFlintAndSteel` (HEAD inject, MixinFlintAndSteelItem_CVB.java:28-83) or fire block placed next to obsidian (fire spread / other ignition) → `MixinAbstractFireBlock_CVB.redirectCreateAreaHelper`, a `@Redirect` of `PortalShape.findEmptyPortalShape` inside `BaseFireBlock.onPlace` (MixinAbstractFireBlock_CVB.java:20-45) which returns `Optional.empty()` (vanilla portal never forms) and calls `IntrinsicPortalGeneration.onFireLitOnObsidian`. A second redirect makes `BaseFireBlock.isPortal`'s `Optional.isPresent()` always true in non-vanilla modes so fire can sit on the side of obsidian for horizontal portals (:56-72).

`NetherPortalLikeForm.perform` (NetherPortalLikeForm.java:31-108):
1. `checkPortalGeneration` — from-chunk loaded (NetherPortalGeneration.java:252-262).
2. `findFrameShape` with the form's area/this-side-frame predicates.
3. `testThisSideShape` hook (square checks etc.).
4. `isOtherGenerationRunning` — `LoadingIndicatorEntity` within 1 block of the shape center aborts (:236-248).
5. If `generateFrameIfNotFound`: area blocks set to AIR (clears the fire that triggered us, NetherPortalLikeForm.java:63-68).
6. `toPos = cpg.mapPosition(fromShape.innerAreaBox.getCenter())` — space-ratio scaling + world-border clamp (CustomPortalGeneration.java:164-182).
7. `startGeneratingPortal(...)` with 7 callbacks (NetherPortalGeneration.java:110-234):
   - Spawns the `LoadingIndicatorEntity` at the from-shape center (`isValid=true`, `setBox(innerAreaBox)`, `addFreshEntity`, :129-138).
   - `otherSideChunkAlreadyGenerated = McHelper.getDoesRegionFileExist(toDimension, toPos)` — literally checks `<dim>/region/r.X.Z.mca` existence on disk (McHelper.java:412-421).
   - Chunk loader radius: if region exists → `floorDiv(existingFrameSearchingRadius,16)+1` chunks (config `IPGlobal.netherPortalFindingRadius`, passed from NetherPortalLikeForm.java:79); else 1 chunk (2 if the shape is ≥16 long) — the long javadoc :160-169 explains this avoids MC-170010-style lighting corruption when setBlockState follows getBlockState-triggered generation.
   - `ChunkLoader(new DimensionalChunkPos(toDim, ChunkPos(toPos)), loaderRadius)` registered via `ImmPtlChunkTracking.addGlobalAdditionalChunkLoader(server, loader)` (:173-177; ImmPtlChunkTracking.java:563-585).
   - A polled task on `ServerTaskList` (runs at END_SERVER_TICK, ServerTaskList.java:10-13; task returns true = done): each tick (a) `portalIntegrityChecker` — for NetherPortalLikeForm, all `frameAreaWithoutCorner` non-air (NetherPortalLikeForm.java:98-103) — abort + cleanup if the player broke the frame mid-generation; (b) progress `imm_ptl.loading_chunks n/m` via `indicatorEntity.inform` until `chunkLoader.getLoadedChunkNum(server) == getChunkNum()` (ChunkLoader.java:27-46, full-status chunks only).
   - When loaded: **fresh area** (no region file) → `onGenerateNewFrame` immediately; **existing area** → build `FastBlockAccess` snapshot over the search radius (`chunkLoader1.createFastBlockAccess(world)` ChunkLoader.java:108-112) and `FrameSearching.startSearchingPortalFrameAsync` on `Util.backgroundExecutor()`:
     - scans blocks near→far; each block passing `otherSideFramePredicate` is handed to the form's `matchShapeByFramePos` func — default translates the `FastBlockPortalShape` of the from-shape to that base pos and requires the full frame+area match, rejecting a same-dimension self-match (NetherPortalLikeForm.java:119-142);
     - found → back on server thread: `portalEntityGeneratingFunc(info)` + finalizer + `O_O.postPortalSpawnEventForge` (no-op on Fabric, O_O.java:83-85);
     - not found → `onGenerateNewFrame`.
   - `onGenerateNewFrame` (:140-154): indicator text `imm_ptl.generating_new_frame`; `newFramePlacer.get()` = the form's `getNewPortalPlacement` → `findAirCubePlacement` (§2.1 NetherPortalMatcher staging; creative triggering player may force-place, NetherPortalLikeForm.java:151-156) → returns `PortalGenInfo` with `toShape = fromShape.getShapeWithMovedTotalAreaBox(airCube)`; then `newFrameGenerateFunc(info.toShape)` = the form's `generateNewFrame` (obsidian `frameAreaWithCorner` for nether, IntrinsicNetherPortalForm.java:31-35); then `portalEntityGeneratingFunc(info)`.
   - Finalizer kills the indicator and removes the chunk loader (:179-182).

**How portal entities get placed ("frame lighting")** — `portalEntityGeneratingFunc` = `generatePortalEntitiesAndPlaceholder(info)` (NetherPortalLikeForm.java:84-89; IntrinsicNetherPortalForm.java:59-64):
1. `info.generatePlaceholderBlocks()` — every area position on BOTH sides becomes `PortalPlaceholderBlock` with AXIS = shape normal axis (PortalGenInfo.java:115-126; NetherPortalGeneration.java:97-108,294-303). This is the visible/luminous "portal is lit" state and the anchor for integrity checks.
2. `info.generateBiWayBiFacedPortal(entityType)` — 4 `BreakablePortalEntity`s: from-side front/back (f1 flipped→f2) and to-side front/back (t1 = reverse of f1, t2 flipped), geometry from `initPortalPosAxisShape` (center of inner box, axes from the shape), `reversePortalId` cross-links f1↔t1 f2↔t2, `PortalExtension.initializeClusterBind` marks the 4-cluster, then 4 × `McHelper.spawnServerEntity` (PortalGenInfo.java:85-113).
3. `cpg.onPortalsGenerated(portals)` — portalTag + datapack commands (CustomPortalGeneration.java:271-289).

### 3.3 Matching variants: exact vs diligent vs scaling
- Default (`NetherPortalLikeForm.getFrameMatchingFunc`): pure translation of the from-shape (same axis, same size, same hole pattern).
- `AbstractDiligentForm`: tries every `DiligentMatcher.TransformedShape` — GCD-shrunk, axis-aligned-rotated (all `AARotation`s), integer-upscaled variants (bounded by shape length 64) — carrying `rotation` (IntMatrix3→DQuaternion) + `scale` into `PortalGenInfo`, so the resulting portal pair connects differently-sized/oriented frames (AbstractDiligentForm.java:19-59; DiligentMatcher.java:47-85).
- `ScalingSquareForm`: matches/places a fixed `toLength` square template with `scale = toLength/fromLength` (ScalingSquareForm.java:72-151).
- `PortalGenInfo` snaps rotation <0.001° to null and |scale−1|<1e−5 to 1.0 (PortalGenInfo.java:59-68).

### 3.4 Frame breaking kills the portal entity
1. Any in-plane neighbor change of a `PortalPlaceholderBlock` (frame block broken, placeholder broken, block placed inside) triggers its `updateShape` (vanilla neighbor-update path), which — server-side, when the update direction's axis ≠ the placeholder's AXIS — calls `notifyPlaceholderUpdate()` on all `BreakablePortalEntity`s found within rough-range 2 of the position (PortalPlaceholderBlock.java:96-122).
2. That sets `isNotified`; the portal's next `tick()` runs `checkPortalIntegrity()` (BreakablePortalEntity.java:158-177). As a safety net an unsolicited check also runs every 233 ticks staggered by entity id (:167).
3. `isPortalIntactOnThisSide()` — NetherPortalEntity: area all placeholder + frame-without-corner all obsidian (NetherPortalEntity.java:72-82); GeneralBreakablePortal: area all placeholder + frame non-air (GeneralBreakablePortal.java:17-25). Failure → `markShouldBreak()` which also flags the reverse portal (immediately if resolvable, else a 30-retry `ServerTaskList` task waiting for the other side's chunk, BreakablePortalEntity.java:234-268).
4. Next tick, `shouldBreakPortal` → `breakPortalOnThisSide()`: remaining placeholder area blocks → AIR, entity `remove(KILLED)` (:127-140). The placeholder removals cascade neighbor updates to the paired/facing portals, which break the same way. `unbreakable` portals skip all of this (:166).
5. Independently, `isPortalPaired()` breaks portals with duplicate or displaced reverse portals (:204-232); a *missing* reverse (0 found) is tolerated.

### 3.5 Custom generation triggers (datapack)
- **use_item**: `MixinItemStack` at RETURN of `ItemStack.useOn` (server) → `CustomPortalGenManager.onItemUse` (MixinItemStack.java:16-31) → deferred one tick → `gen.perform(world, clickedPos.relative(clickedFace), player)`; consumes 1 item on success unless creative (CustomPortalGenManager.java:160-189).
- **throw_item**: `MixinItemEntity_P` at TAIL of `ItemEntity.tick`, only when `thrower != null` (shadowed field) and not removed → `onItemTick` (MixinItemEntity_P.java:24-51); manager additionally requires `hasPickUpDelay()` (fresh throw) (CustomPortalGenManager.java:194-221); shrinks the stack on success.
- **conventional_dimension_change**: `MixinServerPlayerEntity_MA` at HEAD of `ServerPlayer.changeDimension(DimensionTransition)` and of cross-dim `teleportTo` (gated `this_.level() != targetWorld`) runs `onBeforeDimensionTravel`, which does THREE things in sequence: (1) records the pre-travel position via `customPortalGenManager.onBeforeConventionalDimensionChange(player)`, (2) calls **`ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(player)`** — a chunk/entity-tracker cleanup on vanilla cross-dimension travel that must be ported along with the trigger, and (3) schedules `onAfterConventionalDimensionChange` on the task list (MixinServerPlayerEntity_MA.java:19-58; the three calls at :50, :51, :53-56); after arrival the manager replays `convGen` entries from the recorded start (CustomPortalGenManager.java:231-256). Used by `ConvertConventionalPortalForm`.

### 3.6 Global portal storage & sync
Server: per-dimension `SavedData` "global_portal"; portals are entities that are **never `addFreshEntity`'d** on the server — they are constructed, kept in `data`, saved by NBT with an `entity_type` discriminator, and ticked only implicitly (validity/geometry cache refreshed on load via `updateCache()`, GlobalPortalStorage.java:249-263). Any mutation marks dirty + `shouldReSync`; END_SERVER_TICK flushes a full-dimension `GlobalPortalSyncPacket` (dim int id + entire storage NBT) to ALL players (:193-200,297-308); joining players get one packet per non-empty dimension (:135-146 via MixinPlayerList.java:48). Client: packet handler rebuilds the whole list, kills the previous instances, stores them on the `ClientLevel` duck (`IEClientWorld.ip_setGlobalPortals`) and force-initializes the destination client world (:325-349). Missing destination dimensions are pruned server-side on load/dynamic-dimension-update (`clearAbnormalPortals`, :310-319).

---

## 4. MC API touchpoint list (dedup; anything that could move across versions)

**Blocks / block state / world access**
- `Level#getBlockState`, `#setBlockAndUpdate`, `#isEmptyBlock`, `#hasChunkAt`, `#getGameTime`, `#getRandom`, `#isClientSide()`, `#dimension()`, `#getProfiler` (push/pop), `#getWorldBorder`, `#addParticle`, `#playLocalSound`, `#holderLookup(Registries.BLOCK)`
- `LevelAccessor` as the read interface in NetherPortalMatcher (same members)
- `BlockState#isAir`, `#isSolid`, `#isSolidRender(BlockGetter,BlockPos)` (FlippingFloorSquareForm.java:166), `#getFluidState`, `#is(TagKey)`, `#getBlock`, `BlockBehaviour.BlockStateBase::isAir` method ref
- `Blocks.AIR/OBSIDIAN/NETHER_PORTAL`, `NetherPortalBlock.AXIS`, `BlockStateProperties.AXIS`
- `Block` subclassing (PortalPlaceholderBlock): `BlockBehaviour.Properties.of().noCollission().sound().strength().noOcclusion().noLootTable().lightLevel()`; overrides `getShape`, `createBlockStateDefinition`, **`updateShape(BlockState, LevelReader, ScheduledTickAccess, BlockPos, Direction, BlockPos, BlockState, RandomSource)`** (1.21.2+ signature, PortalPlaceholderBlock.java:96-122), `propagatesSkylightDown(BlockState)`, `getRenderShape`, `getShadeBrightness`; `Block.box`, `VoxelShape`, `RenderShape.INVISIBLE`
- `WorldBorder#isWithinBounds(BlockPos)`, `#clampToBounds(int,int,int)` (CustomPortalGeneration.java:170-175)

**Chunk / height / lighting**
- `ChunkSource#getChunk(int,int,boolean)` (FastBlockAccess.java:78), `LevelChunk#getSections()`, `LevelChunkSection#getBlockState(int,int,int)`, `#hasOnlyAir()`, `EmptyLevelChunk`
- `Level#getMinSection()` / `#getMaxSection()` (FastBlockAccess.java:40-43,63-64) — **renamed across versions**
- `LevelAccessor#getMinBuildHeight` / `#getMaxBuildHeight`, `DimensionType#logicalHeight()` (via McHelper.java:864-874), `DimensionType#coordinateScale()` (VerticalConnectingPortal.java:63-64)
- `Level#getChunk(BlockPos)` → `ChunkAccess#setBlockState(BlockPos, BlockState, false)` + `ThreadedLevelLightEngine#checkBlock(BlockPos)` from `ServerChunkCache#getLightEngine()` (BorderBarrierFiller.java:132-143)
- `ChunkPos` (`asLong(int,int)`, `getX/getZ(long)`, `getMinBlockX/Z`, `getWorldPosition`, `getRegionX/Z`), `SectionPos.of`
- Region file probe: `MinecraftServer#storageSource` → `LevelStorageSource.LevelStorageAccess#getDimensionPath(dim)` + literal `region/r.X.Z.mca` (McHelper.java:412-421)

**Entity lifecycle**
- `EntityType#create(Level)`, `EntityType.getKey`, `EntityType.EntityFactory`, `MobCategory.MISC`, `EntityDimensions.fixed`
- Fabric `FabricEntityTypeBuilder` chain incl. `.trackRangeBlocks(96).trackedUpdateRate(20).forceTrackedVelocityUpdates(true)` (Portal.java:94-108) and `.trackable(96,20)` (LoadingIndicatorEntity.java:26-32)
- `Entity#remove(RemovalReason.KILLED / UNLOADED_TO_CHUNK)`, `#isRemoved`, `#setPos`, `#getUUID`, `#blockPosition`, `#position`, `#level()`, `#getId`, `#tickCount`, `#saveWithoutId(CompoundTag)`, `#load(CompoundTag)`, `#getEyePosition(float)`, `#createCommandSourceStack`
- `Level#addFreshEntity`, `ServerLevel#getEntity(UUID)` (BreakablePortalEntity.java:148-149)
- `SynchedEntityData` define/set/get + `EntityDataSerializers.COMPONENT` / `BLOCK_POS` (LoadingIndicatorEntity.java:34-42,108-113) — **26.2: defineSynchedData(Builder) shape**
- `ServerPlayer#displayClientMessage`, `#isCreative`, `#getName`, `Player#getPose()==Pose.CROUCHING`, `player.connection.send(Packet)`

**Saved data / NBT / registry / codec**
- `SavedData` + `SavedData.Factory<>(Supplier, BiFunction<CompoundTag,HolderLookup.Provider,T>, /*DataFixTypes*/ null)` + `DimensionDataStorage#computeIfAbsent(factory, "global_portal")` + `#setDirty` (GlobalPortalStorage.java:94-112,159-163) — **major 26.2 rework (SavedDataType)**
- `save(CompoundTag, HolderLookup.Provider)` override signature (GlobalPortalStorage.java:266)
- `NbtUtils.readBlockState(HolderGetter, CompoundTag)` / `writeBlockState` (BreakablePortalEntity.java:85-88; GlobalPortalStorage.java:214-218,291)
- `CompoundTag`/`ListTag`/`IntTag`/`Tag.TAG_INT` accessors incl. `getList(name, 3|10)`
- `MappedRegistry` construction + `Registry.register` at class-init (PortalGenForm.java:20-52, PortalGenTrigger.java:95-116, CustomPortalGeneration.java:82-98) — mod-created registries, frozen-registry rules apply
- `Registry#byNameCodec().dispatchStable(...)` / `.dispatchMap("schema_version", ...)`, `RecordCodecBuilder.mapCodec`, `Codec.INT/BOOL/STRING`, `MapCodec.unit`
- `BuiltInRegistries.BLOCK/ITEM.byNameCodec()`, `BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation)` (GlobalPortalStorage.java:250-251)
- `server.registryAccess().registryOrThrow(key)` (CustomPortalGenManager.java:69-72) — **renamed in newer MC (lookupOrThrow)**
- `RegistryOps#getter(Registries.BLOCK)`, `HolderGetter<Block>`, `Holder.Reference`, `TagKey.create`, `ResourceLocation.read`, `Level.RESOURCE_KEY_CODEC`, `ResourceKey.create/createRegistryKey`
- Fabric `DynamicRegistries.register` (datapack dynamic registry, CustomPortalGenManager.java:44-51)

**Networking**
- `CustomPacketPayload` + `Type` + `StreamCodec.of` (`GlobalPortalSyncPacket`, ImmPtlNetworking.java:93-127), `FriendlyByteBuf#readVarInt/writeVarInt/readNbt/writeNbt`
- Fabric `PayloadTypeRegistry.playS2C().register`, `ServerPlayNetworking.createS2CPacket`, `ClientPlayNetworking.registerGlobalReceiver` (ImmPtlNetworking.java:238-267)
- `Packet<ClientCommonPacketListener>` as the send type (GlobalPortalStorage.java:140,148)

**Vanilla portal & ignition (mixin targets — verify on 26.2 decomp)**
- `PortalShape.findEmptyPortalShape(LevelAccessor, BlockPos, Direction.Axis)` + `#createPortalBlocks()` (IntrinsicPortalGeneration.java:127-135) — **26.2: createPortalBlocks takes LevelAccessor in later versions**
- `BaseFireBlock#onPlace` (redirect of findEmptyPortalShape call) and `BaseFireBlock#isPortal` (redirect of `Optional.isPresent`) (MixinAbstractFireBlock_CVB.java:20-72)
- `FlintAndSteelItem#useOn(UseOnContext)` HEAD inject (MixinFlintAndSteelItem_CVB.java:28)
- `ItemStack#useOn(UseOnContext)` RETURN inject (MixinItemStack.java:16-19)
- `ItemEntity#tick` TAIL inject + `@Shadow private UUID thrower` (MixinItemEntity_P.java:18-27) — **thrower storage changed across versions**
- `ServerPlayer#changeDimension(DimensionTransition)` + `#teleportTo(ServerLevel,double,double,double,float,float)` HEAD injects (MixinServerPlayerEntity_MA.java:19-43) — **26.2: TeleportTransition / teleport() renames**
- `UseOnContext#getLevel/getItemInHand/getClickedPos/getClickedFace/getPlayer`, `InteractionResult`

**Client-only**
- `Minecraft.getInstance().player/.gui`, `Gui#setOverlayMessage` (LoadingIndicatorEntity.java:150-155)
- `ParticleTypes.PORTAL`, `SoundEvents.PORTAL_AMBIENT`, `SoundSource.BLOCKS`, `Level#playLocalSound(double,double,double,SoundEvent,SoundSource,float,float,boolean)`
- `ClientLevel` (global portal receive), `EntityTickList` (via duck)

**Scheduling / threading**
- `Util.backgroundExecutor()` (FrameSearching.java:60), `MinecraftServer#execute(Runnable)` (FrameSearching.java:46), `Util.make`, `Util.NIL_UUID`
- Fabric `ServerTickEvents.END_SERVER_TICK` (ServerTaskList.java:11; GlobalPortalStorage.java:68), `ServerLifecycleEvents.SERVER_STARTED/END_DATA_PACK_RELOAD` (CustomPortalGenManager.java:53-57)
- `MinecraftServer#getLevel(ResourceKey)`, `#getAllLevels`, `#getCommands().performPrefixedCommand`

---

## 5. Registration & wiring

- **Entity types**: all created via `Portal.createPortalEntityType` (FabricEntityTypeBuilder) as static finals on each class; registered in `IPModMain.registerEntityTypes` (IPModMain.java:162-213) with ids `immersive_portals:nether_portal_new`, `general_breakable_portal`, `global_tracked_portal`, `border_portal`, `end_floor_portal`, `loading_indicator` (called by the platform entrypoint with a `BiConsumer<ResourceLocation, EntityType<?>>`). Client renderers are bound in `IPModEntryClient` (portal types + `LoadingIndicatorEntity`, IPModEntryClient.java:43-59).
- **Block**: `PortalPlaceholderBlock.instance` singleton registered as `immersive_portals:nether_portal_block` (IPModMain.java:155-160).
- **Custom gen**: `CustomPortalGenManager.init()` from `IPModMain.init()` (IPModMain.java:114) — Fabric `DynamicRegistries.register` for both registry keys + rebuild on datapack reload/server start into `IPPerServerInfo.of(server).customPortalGenManager` (nullable; every mixin call site null-checks). Form/trigger codec registries are static-initialized `MappedRegistry`s (not MC builtin registries).
- **Intrinsic nether gen**: `IntrinsicPortalGeneration.init()` (peripheral entrypoint) only sets identifiers; the instances are static finals invoked directly from the two ignition mixins (`MixinFlintAndSteelItem_CVB`, `MixinAbstractFireBlock_CVB` — both in `imm_ptl.peripheral.mixin.common.nether_portal`).
- **Trigger mixins (core)**: `MixinItemStack` (use_item), `MixinItemEntity_P` (throw_item), `MixinServerPlayerEntity_MA` (conventional dimension change; platform_specific package).
- **Global portals**: `GlobalPortalStorage.init()` from `IPModMain.init()` (IPModMain.java:83) — END_SERVER_TICK tick loop, server-cleanup event, DimLib dynamic-dimension event, client cleanup event; login sync from `MixinPlayerList.java:48`; packets registered in `ImmPtlNetworking.init/initClient` (ImmPtlNetworking.java:238-267).
- **Task scheduling**: all deferred/polled work goes through `ServerTaskList.of(server)` (per-server task list processed at END_SERVER_TICK; a task returning true is done, false reruns next tick; `MyTaskList.withRetryNumberLimit/withDelayCondition/oneShotTask` combinators from q_misc_util).
- **Chunk loading during generation**: `ImmPtlChunkTracking.addGlobalAdditionalChunkLoader / removeGlobalAdditionalChunkLoader` (ImmPtlChunkTracking.java:563-591) with `ChunkLoader` records; `ChunkLoader.loadChunksAndDo` (ChunkLoader.java:118-128) is the generic helper (used elsewhere; startGeneratingPortal hand-rolls the same pattern to interleave integrity checks and progress UI).

---

## 6. Porting notes / surprises (verbatim-behavior flags)

1. **`BlockTraverse.boxAllMatch` is any-match** (BlockTraverse.java:140-148) — misnamed; affects ground validation at NetherPortalMatcher.java:110. Port bit-exactly.
2. **`CustomPortalGenManager.addEntry` never `load()`s the reverse generation** (CustomPortalGenManager.java:119-141) — a `reversible` datapack gen's reverse direction is validated but not installed into any trigger map. Intrinsic nether generation is unaffected (both directions are separate static `CustomPortalGeneration`s invoked directly).
3. **`FastBlockPortalShape.withNewBase` leaves `innerAreaBox`/`totalAreaBox` stale** (FastBlockPortalShape.java:357-369); all IP call sites immediately convert via `toBlockPortalShape()` (NBT round-trip recomputes). Any new call site reading the boxes directly would be wrong.
4. **`FrameSearching` exception path runs `onNotFound` on the background thread** (FrameSearching.java:55-58) — only the success/normal-not-found paths hop back to the server thread.
5. `startGeneratingPortal` decides "existing frame search" vs "fresh generate" by raw **region file existence on disk** (NetherPortalGeneration.java:156; McHelper.java:412-421), not chunk status — anvil-format-dependent.
6. The intrinsic `CustomPortalGeneration`s are constructed with `trigger = null` (IntrinsicPortalGeneration.java:28-54) — never pass them through `CustomPortalGenManager.load`.
7. `encounteredVanillaPortalBlock` is a static volatile shared across all generations (IntrinsicNetherPortalForm.java:66-67, "not per-player, but mostly fine").
8. `BreakablePortalEntity.blockPortalShape` is NOT synched via vanilla `SynchedEntityData`; it reaches the client because IP's custom `PortalSyncPacket.extraData` carries the full `addAdditionalSaveData` NBT (`Portal.writePortalDataToNbt` → `addAdditionalSaveData`, Portal.java:1751-1755; applied client-side via `readPortalDataFromNbt`, ImmPtlNetworking.java:219 and `acceptDataSync` :202) — which includes `netherPortalShape` (BreakablePortalEntity.java:111-125). Client-side `isPortalValid()` deliberately skips the shape/reverse-id requirement (BreakablePortalEntity.java:62-67) so the portal is usable before the NBT arrives; `getActualOverlay()` reads `blockPortalShape.axis` on the client (NetherPortalEntity.java:128-139) and thus depends on that sync. Any replacement of the entity-sync path must preserve full-NBT portal sync or the breakable-portal client features break.
