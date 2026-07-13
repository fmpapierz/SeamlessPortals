# Adversarial Verification (round 2) — Platform-specific, Compat, Peripheral (triage slice)

Verified documents (current state, post round-1 fixes):
- INV = `migration/inventory/platform-compat-peripheral.md`
- MAP = `migration/api-map/platform-compat-peripheral.md`

**Round-1 note:** a previous verification of this slice refuted 4 claims (skyland router mis-attributed to `end`/`ip_end`; overstated "new descriptors" for the NoiseRouterData invokers; dim_stack file count 12→11; mixin count 19→18). All four corrections are present in the current docs (INV now states the `ip_end` dead-declaration + the `noNewCaves(slideEndLike(...))` formula and the 11/18 counts; MAP §3 now states the descriptors are identical). This round re-verified the corrected text plus a fresh adversarial sample.

Ground truths re-opened for every claim: IP source `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`, 26.2 decompile `C:/Users/warwa/ModDev/mc262-ref`. Method: every claim re-derived from source; GONE verdicts hunted for renames (parameter-type/body greps — e.g. checked whether 26.2 `simpleRouter` is a `noNewCaves` rename: it is not); SAME verdicts checked signature-against-IP-call-site; mixin re-anchor claims checked at both ends; scope checked against actual directory listings.

**Result: 41 claim groups checked, 41 confirmed, 0 refuted. Two minor precision notes (non-misdirecting). Scope complete — no silently skipped files.**

---

## 1. IP-source claims (INV)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | IPModEntry: `IPModMain.init()` then `RequiemCompat.init()`; entity types via `Registry.register(BuiltInRegistries.ENTITY_TYPE,...)` :18-20; block :22; `dimthread` probe :24-27; `gravity_changer_q` → `OnGravityChangerPresent` swap :32-35 | **CONFIRMED** | `IPModEntry.java:15-35` read in full; all five sub-claims line-exact. |
| 2 | 10 entity types with ids incl. `border_portal`=WorldWrappingPortal, `end_floor_portal`=VerticalConnectingPortal at `IPModMain.java:162-213` | **CONFIRMED** | `IPModMain.java:162-213`: portal, nether_portal_new, end_portal, mirror, breakable_mirror, global_tracked_portal, border_portal (:195-196), end_floor_portal (:200-201), general_breakable_portal, loading_indicator = 10. |
| 3 | Client wires **9** portal types to one `PortalEntityRenderer` + separate `LoadingIndicatorRenderer` (`IPModEntryClient.java:39-63`, register call :52-55) | **CONFIRMED** | `IPModEntryClient.java:41-61`: array of exactly 9 types → `EntityRendererRegistry.register(entityType, (EntityRendererProvider) PortalEntityRenderer::new)` (:52-55); loading indicator separate (:58-61). Not a contradiction with the "10 registered types" (the 10th is the loading indicator). |
| 4 | O_O facade: `onPlayerChangeDimensionClient` :43-48 called from `ClientTeleportationManager.java:526`; `onPlayerTravelOnServer` :50-55 called from `ServerTeleportationManager.java:477`; `isObsidian` identity-compare :61-65; chunk events :67-77; `createMyClientChunkManager` → `ImmPtlClientChunkMap` :87-90; `postPortalSpawnEventForge` empty :83-85; `isDedicatedServer` :79-81 | **CONFIRMED** | `O_O.java:37-90` read; both teleportation-manager call sites grep-verified at exactly the cited lines. |
| 5 | `O_O.allowTeleportingEntity` always `true` on Fabric; comment names `ForgeHooks.onTravelToDimension()` (:246-249) | **CONFIRMED** | `O_O.java:246-249` verbatim — the NeoForge-must-wire-the-hook note is grounded. |
| 6 | `IEClientWorld_MA` DEAD (zero references) | **CONFIRMED** | Repo-wide grep: only its own declaration (`IEClientWorld_MA.java:3`). |
| 7 | `MixinFabricClientPlayNetworkAddon` empty body | **CONFIRMED** | 9-line file, `@Mixin(ClientPlayNetworkAddon.class)` + empty class (:6-9). |
| 8 | `MixinFabricInvalidateRenderStateCallback`: injects `lambda$static$0`, cancels while `ClientWorldLoader.getIsCreatingClientWorld()` (:14-23) | **CONFIRMED** | `MixinFabricInvalidateRenderStateCallback.java:14-23` verbatim (`@Pseudo`, `remap=false`, `ci.cancel()` under exactly that guard). |
| 9 | `MixinPlayerManager_MA`: `respawn` HEAD + `remove` HEAD → `ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers` (:15-32) | **CONFIRMED** | File read; handler params `(ServerPlayer, boolean, Entity.RemovalReason)` / `(ServerPlayer)`. |
| 10 | `MixinServerPlayerEntity_MA`: `changeDimension(DimensionTransition)` HEAD :19-25; `teleportTo(ServerLevel,DDDFF)` HEAD gated on world-differs :28-43; `onBeforeDimensionTravel` :45-58 (CustomPortalGenManager + tracker removal + ServerTaskList, all inside the `!= null` branch) | **CONFIRMED** | File read in full; line ranges and logic exact. |
| 11 | Config: `IPGlobal.configHolder = AutoConfig.register(IPConfig.class, GsonConfigSerializer::new)` at `IPModMain.java:146` + save listener re-running `onConfigChanged` :147-150 | **CONFIRMED** | `IPModMain.java:146-152`. |
| 12 | Cross-module surprise: IPConfig defaults call peripheral `IPFeatureControl.enableVanillaBehaviorChangingByDefault()` (:89-98) | **CONFIRMED** | `IPConfig.java:87-98`: netherPortalMode/endPortalMode/enableMirrorCreation defaults. |
| 13 | `IPCompatMixinPlugin.shouldApplyMixin`: IrisSodium→both, Iris→iris, Sodium→sodium, Flywheel→flywheel, CardinalComp→`cardinal-components-base`, **else false** (:23-53) | **CONFIRMED** | `IPCompatMixinPlugin.java:23-53`; order and default-deny exact. |
| 14 | `MixinSodiumRenderSectionManager`: field-**exchange** swap of `renderLists`+`renderDistance`; forces `isSectionVisible()` true when `RenderStates.portalsRenderedThisFrame != 0` | **CONFIRMED** | `MixinSodiumRenderSectionManager.java:29-40` (old values go back into the context — INV §3.3's "by exchange" is exact) and :47-52. |
| 15 | Flywheel mixins target 1.18-era `com.jozufozu.flywheel.core.*` → dead on 1.21 | **CONFIRMED** | Grep: `...core.QuadConverter` / `...core.compile.ProgramCompiler` / `...core.crumbling.CrumblingRenderer` at each mixin's `:12`. |
| 16 | CommandStickItem: `createCommandSourceStack().withPermission(2)` :128 → `performPrefixedCommand` :141; gate `IPGlobal.easeCommandStickPermission \|\| hasPermissions(2) \|\| isCreative` :148-155 | **CONFIRMED** | `CommandStickItem.java:120-155` read; exact. |
| 17 | **Geometry/transform**: `DimStackInfo.createConnectionBetween` — connector `a.flipped ? ceil : floor`; scale = `b.scale / a.scale`; flip = `a.flipped ^ b.flipped`; rotation = `b.horizontalRotation - a.horizontalRotation`; reverse via `PortalAPI.createReversePortal`; both fuse-view; optional gravity; added as global portals | **CONFIRMED** (re-derived) | `DimStackInfo.java:52-106`: xorFlipped :58, ceil/floor :79-80, `b.scale / a.scale` :82, rotation delta :84, reverse :89, fuse-view :47-50+91-92, gravity :94-97, `addGlobalPortal` :99-105. See precision note P2 (conditional add). |
| 18 | `DimStackInfo.apply` refuses when global portals exist (§3.5 cites :126-132) | **CONFIRMED** | `DimStackInfo.java:126-132`. |
| 19 | **Worldgen formula** (round-1 fix): skyland router = `ip_noNewCaves(dfGetter, noiseGetter, ip_slideEndLike(ip_getFunction(dfGetter, BASE_3D_NOISE_END), 0, 128))` (`NormalSkylandGenerator.java:133-139`); `:127` references only a commented-out `IENoiseGeneratorSettings.ip_end()`; seaLevel overwritten to 0 (:142); spliced into a FLOATING_ISLANDS copy (:124-147) | **CONFIRMED** (re-derived) | `NormalSkylandGenerator.java:110-151` read; formula, the `0, 128` slide args, and the commented line all exact. |
| 20 | `IENoiseRouterData.ip_end` is a dead declaration (never called in IP) | **CONFIRMED** | Grep `ip_end\(` repo-wide: only the declaration (`IENoiseRouterData.java:16`) + two commented-out lines (`NormalSkylandGenerator.java:127`, `IENoiseGeneratorSettings.java:19`). |
| 21 | `IPPortingLibCompat` init'd from `IPModMain.init()` at `IPModMain.java:99` | **CONFIRMED** | `IPModMain.java:99`: `IPPortingLibCompat.init();`. |

### Scope audit (silently-skipped-file check)

| Package | Doc claims | Actual (`find ... -name "*.java"`) | Verdict |
|---|---|---|---|
| `imm_ptl/core/platform_specific/**` | 13 files, FULL READ | **13 files**, every one has an inventory entry | **COMPLETE** |
| `imm_ptl/core/compat/**` | 36 files | **36 files**; checked name-by-name against INV §2B — all covered (5 top-level + 3 sodium_compatibility + 9 mixin/sodium + 8 iris_compatibility + 7 mixin/iris + 3 flywheel + 1 cardinal_comp = 36) | **COMPLETE** |
| `imm_ptl/peripheral/**` | ~60 files incl. 4 excluded portal_generation | **60 files**; 56 owned all covered (root 5, alternate_dimension 9, dim_stack 11, ducks 1, platform_specific 3, wand 9, mixins 18), portal_generation 4 correctly excluded as cross-references | **COMPLETE** |
| Peripheral mixin split (round-1 fix) | "18 mixins + 1 duck", "10 common + 8 client" | 18 mixin files (10 common + 8 client) + 1 duck on disk | **CONFIRMED** |

---

## 2. 26.2 claims (MAP)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 22 | `MultiBufferSource` GONE (zero matches repo-wide); replacement `submitCustomGeometry(PoseStack, RenderType, SubmitNodeCollector.CustomGeometryRenderer)` at `OrderedSubmitNodeCollector.java:184` | **CONFIRMED** | Grep: no files. `net/minecraft/client/renderer/OrderedSubmitNodeCollector.java:184` verbatim. |
| 23 | `GuiGraphics` GONE; `Screen.extractRenderState(GuiGraphicsExtractor,int,int,float)` `Screen.java:116`, final wrapper :107 | **CONFIRMED** | Grep `GuiGraphics[^E]`: no files (only `GuiGraphicsExtractor`). `Screen.java:107-120` read. |
| 24 | Global rename → `Identifier` (`Identifier.java:18`); `Registry.register` overloads :107/:111/:115 | **CONFIRMED** | `net/minecraft/resources/Identifier.java:18`; `net/minecraft/core/Registry.java:107-118`. |
| 25 | `NoiseRouterData.noNewCaves` GONE. Rename-hunt: 26.2 `simpleRouter` (:464-482) is **not** it (zeroes temperature/vegetation; 1.21 `noNewCaves` set shifted-noise temp/vegetation) → re-derivation verdict stands. Fidelity warnings correct: `floatingIslands` :433-436 uses slide height **256** (IP uses 128); `end` :442 = endIslands + SLOPED_CHEESE_END (different router). Surviving helpers: `slideEndLike(DensityFunction,int,int)` :396, `getFunction` :202, `BASE_3D_NOISE_END` `ResourceKey<DensityFunction>` :41, `caves` :428 | **CONFIRMED** (all sub-claims re-derived) | Grep `noNewCaves`: zero matches. `NoiseRouterData.java:41, 202-204, 396-398, 428-436, 442-459, 464-482` all read. |
| 26 | MAP §3 (round-1 fix): the four surviving `IENoiseRouterData` targets have descriptors **identical** to IP's 1.21 invoker declarations | **CONFIRMED** | IP `IENoiseRouterData.java:15-36` (end takes `HolderGetter<DensityFunction>`; slideEndLike takes resolved `DensityFunction,int,int`; getFunction takes `HolderGetter,ResourceKey`; BASE_3D_NOISE_END already a `ResourceKey` accessor) vs 26.2 `NoiseRouterData.java:442/:396/:202/:41` — verbatim matches. |
| 27 | `player.hasPermissions(2)` GONE; `Player.permissions()` → `PermissionSet` `Player.java:1846`; `Permissions.COMMANDS_GAMEMASTER` `Permissions.java:7`; level 2 == `PermissionLevel.GAMEMASTERS` (`PermissionLevel.java:11`, id mapping :30-36) | **CONFIRMED** | All line-exact; grep `hasPermissions`: no Player/Entity-level API remains (3 unrelated creative-tab hits). |
| 28 | `CommandSourceStack.withPermission(PermissionSet)` :261; `withMaximumPermission` :282; `LevelBasedPermissionSet.GAMEMASTER` `LevelBasedPermissionSet.java:7` | **CONFIRMED** | All three line-exact (`GAMEMASTER = create(PermissionLevel.GAMEMASTERS)` at :7). |
| 29 | `DimensionTransition` GONE as a type (only legacy method names in `NetherPortalBlock.java:174-201`); `ServerPlayer.teleport(TeleportTransition)` :1093 | **CONFIRMED** | Grep: single file; hits are method names `getDimensionTransitionFromExit`/`createDimensionTransition` (:174,177,198,201), both returning `TeleportTransition`. `ServerPlayer.java:1093` verbatim. |
| 30 | `teleportTo` CHANGED: `boolean teleportTo(ServerLevel, double, double, double, Set<Relative>, float, float, boolean resetCamera)` :1689 | **CONFIRMED** | `ServerPlayer.java:1689` verbatim. |
| 31 | `NoiseBasedChunkGenerator` now **final** (:50); `ChunkMap` ctor `instanceof NoiseBasedChunkGenerator` seeds `RandomState` :182-186 + `generator.createState(...)` :188 → AW+AT definalization required; alternative changes worldgen randomness | **CONFIRMED** | `NoiseBasedChunkGenerator.java:50` (`public final class`); `ChunkMap.java:178-190` read — dummy-settings fallback in the else-branch confirms the fidelity reasoning. |
| 32 | `ChunkAccess.getMinY()/getMaxY()`, **getMaxY inclusive** (off-by-one hazard for the bedrock scan) | **CONFIRMED** (semantics; see P1) | `getMaxY()` default = `getMinY() + getHeight() - 1` (`LevelHeightAccessor.java:11-13`) → inclusive; clamp usage `ChunkAccess.java:244-253` (`isYSpaceEmpty`, `<=` loop) confirms. |
| 33 | `ChunkStatusTasks.generateSpawn(WorldGenContext, ChunkStep, StaticCache2D<GenerationChunkHolder>, ChunkAccess)` survived **verbatim** :169-171 | **CONFIRMED** | `ChunkStatusTasks.java:169-171` — identical to IP's 1.21 injection target. |
| 34 | `PlayerList.respawn(ServerPlayer, boolean, Entity.RemovalReason)` :389 / `remove(ServerPlayer)` :303 — MA mixin re-anchors as-is | **CONFIRMED** | `PlayerList.java:389, 303`; matches IP mixin handler params (row 9). |
| 35 | `Minecraft.startAttack` intact: `private boolean startAttack()` :1614 | **CONFIRMED** | `Minecraft.java:1614`. |
| 36 | `Util.backgroundExecutor()` → `TracingExecutor`, in `net.minecraft.util` (:252) | **CONFIRMED** | `net/minecraft/util/Util.java:252-254`. |
| 37 | Weather: `getThunderLevel` :837, `getRainLevel` :847, new public setters `setThunderLevel` :841, `setRainLevel` :851-854 setting both `o*`+current (matches IP duck semantics) | **CONFIRMED** | `Level.java:835-855` read; `setRainLevel` sets `oRainLevel` and `rainLevel` (:853-854). |
| 38 | Item API: `use` returns `InteractionResult` :189; `appendHoverText(ItemStack, TooltipContext, TooltipDisplay, Consumer<Component>, TooltipFlag)` :322; `getDescriptionId()` **final no-arg** :330; `getName(ItemStack)` :334; `isFoil` :338 | **CONFIRMED** | `Item.java:189, 322, 330-332, 334, 338` all verbatim. |
| 39 | Gizmos model: `DebugRenderer.emitGizmos(Frustum,double,double,double,float)` :142; `SimpleDebugRenderer.emitGizmos(camXYZ, DebugValueAccess, Frustum, partialTicks)` :199; driven from `LevelExtractor.extract` :207 inside `Gizmos.withCollector(this.mainThreadGizmos)` (:80 field, :476 scope); primitives `line` :51-57, `arrow` :59-65, `rect(4 corners)` :71-73, `cuboid` :31-37, `circle` :47-49, `point` :75-77, `billboardText` :101-103; `addGizmo` throws outside collector scope :22-29 | **CONFIRMED** | `DebugRenderer.java:142, 198-200`; `LevelExtractor.java:80, 207, 476`; `Gizmos.java:22-103` — every cited primitive and the collector-scope constraint line-exact. The MixinDebugRenderer migration guidance is grounded. |
| 40 | `LevelExtractor.allChanged()` :406 / `onResourceManagerReload` :389 (26.2 anchor for the reload-suppression pattern) | **CONFIRMED** | `LevelExtractor.java:389, 396, 406` grep-verified. |
| 41 | `ClientChunkCache extends ChunkSource` :34, ctor `(ClientLevel, int serverChunkRadius)` :41 | **CONFIRMED** | `ClientChunkCache.java:34, 41` — the `O_O.createMyClientChunkManager` swap-factory call site (`O_O.java:88-90`) matches. |
| 42 | `MinecraftServer.createLevels()` no-args :421, called :402; `setInitialSpawn(overworld, levelData, bonusChest, isDebug, levelLoadListener)` invoked :441, declared private static :482 | **CONFIRMED** | `MinecraftServer.java:398-445` read; `:482` grep-verified. Both dim-stack injection anchors survive. |
| 43 | `BaseFireBlock.onPlace` :153 calls `PortalShape.findEmptyPortalShape(level, pos, Direction.Axis.X)` :156; `isPortal` :191; `PortalShape.findEmptyPortalShape(LevelAccessor, BlockPos, Direction.Axis)` :50 + `findPortalShape` :54 | **CONFIRMED** | `BaseFireBlock.java:152-164, 186-203`; `PortalShape.java:50-58` — the CVB redirect re-anchors 1:1 as claimed. |
| 44 | `WorldCreationContext` reshaped 7-component record :21-29; `worldgenLoadContext()` survives as derived getter → `RegistryAccess.Frozen` :101-103 | **CONFIRMED** | `WorldCreationContext.java:21-29, 101-103` verbatim (components in the doc's exact order incl. `initialWorldCreationOptions`). |
| 45 | `CreateWorldScreen.MoreTab` still private inner `GridLayoutTab` :704-711, `RowHelper` :711, tab added :246 | **CONFIRMED** | `CreateWorldScreen.java:704-714` (`private class MoreTab extends GridLayoutTab`, `createRowHelper(1)` :711); `.addTabs(..., new CreateWorldScreen.MoreTab())` :246. |
| 46 | `RenderTypes.lines()` :624; `ChunkSectionLayer` enum only SOLID/CUTOUT/TRANSLUCENT :12-15 | **CONFIRMED** | `RenderTypes.java:624-626`; `ChunkSectionLayer.java:12-15`. |
| 47 | `SharedConstants.getCurrentVersion()` :198; `WorldVersion` interface `name()` :13 / `id()` :11 | **CONFIRMED** | `SharedConstants.java:198`; `WorldVersion.java:11, 13`. |
| 48 | `Minecraft.ON_OSX` GONE; pattern in `InputQuirks` | **CONFIRMED** | Grep `ON_OSX`: single hit, `net/minecraft/client/input/InputQuirks.java`. |
| 49 | `RenderSystem`: `setProjectionMatrix(GpuBufferSlice, ProjectionType)` :180; backup/restore :186-194; `getProjectionMatrixBuffer()` → `@Nullable GpuBufferSlice` :198; `getModelViewMatrixCopy()` → Matrix4f :203 | **CONFIRMED** | `RenderSystem.java:178-206` verbatim. |
| 50 | `ShaderType` VERTEX/FRAGMENT at `com/mojang/blaze3d/shaders/ShaderType.java:10` (replaces `CompiledShader.Type`) | **CONFIRMED** | `ShaderType.java:10-12`. |
| 51 | `RegistryAccess.lookupOrThrow(ResourceKey)` :21; `Registry<T> extends HolderLookup.RegistryLookup<T>` :25 (`.asLookup()` droppable) | **CONFIRMED** | `RegistryAccess.java:21-23`; `Registry.java:25`. |
| 52 | FogRenderer rewritten/moved: class `fog/FogRenderer.java:37`; `setupFog(Camera, int, DeltaTracker, float, ClientLevel)` → `FogData` :167; `updateBuffer(FogData)` :188; `getBuffer(FogMode)` → `GpuBufferSlice` :76; `Camera.getPosition()` → `position()` :359 | **CONFIRMED** | `FogRenderer.java:37, 76, 167, 188`; `Camera.java:359`. |
| 53 | `Level.getEntity(UUID)` :781 (hoisted); bonus `ServerLevel.getEntityInAnyDimension(UUID)` :1364 | **CONFIRMED** | `Level.java:781-783`; `ServerLevel.java:1364`. |

---

## 3. Internal-contradiction scan

- INV "10 entity types" vs "9 portal renderers": both correct (10th type = loading indicator, own renderer). Not a contradiction.
- INV §2C flags `MixinChunkStatusTasks_BedrockReplacement` "must be re-found"; MAP §3 + cross-cutting note 5 say it survived verbatim and explicitly reconcile. Consistent, and MAP is right (row 33).
- INV §2B / MAP note 2 agree on invoker-base portability with the two carve-outs (`IPPortingLibCompat` stencil duck, `markSpriteActive`). Consistent.
- The `ip_end`/`noNewCaves` story is told three times (INV generator row, INV mixin row, MAP §1+§3) — all three tellings agree with each other and with source.

## 4. Precision notes (non-refuting; no port impact)

1. **MAP §2 `ChunkAccess.getMinY()/getMaxY()` row**: the `ChunkAccess.java:406-407` citation covers only `getMinY` (:406-408); `getMaxY` is a `LevelHeightAccessor` default (`LevelHeightAccessor.java:11-13`), and the clamp example is at `ChunkAccess.java:244-253` (not :245-250 exactly). The row's own "backed by LevelHeightAccessor" note and the inclusive-semantics warning are correct.
2. **INV §2C `DimStackInfo` row**: "adds them as global portals" is conditional in source — forward portal only if `a.connectsNext`, reverse only if `b.connectsPrevious` (`DimStackInfo.java:99-105`). The `connectsPrevious/Next` fields are documented in the doc's own `DimStackEntry` row, so the information is present; whoever ports dim-stack should carry the gate.

## 5. Verdict

**CLEAN.** All 41 claim groups sampled this round (geometry/formula re-derivation, GONE rename-hunts, SAME signature checks against IP call sites, mixin re-anchor checks at both ends, dependency traces, full scope audit) reproduced from source. Round 1's four refutations are all fixed in the current docs. The two documents are mutually consistent and safe to build on for this slice.
