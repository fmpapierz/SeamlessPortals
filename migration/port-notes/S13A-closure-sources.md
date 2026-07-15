# S13-A — SCC closure-set SOURCE PORTS (the last held translation batch)

**Stage S13-A of the entity-portal migration.** S13-A lands the SCC closure-set source ports — the
final held translation batch — **HELD-INERT**: `ip_scc_closed` stays `false`, `IpHeldPaths.groovy`
and `gradle.properties` are byte-untouched vs HEAD, and the whole ported `qouteall` tree is held out
of the shipping compile by the existing broad held-path filter. The **S13-B flip** (flip
`ip_scc_closed=true`, register mixins, gate block-era drivers, wire runtime init, unconditional
registrations, enable the math tests) is a **separate commit** and is NOT part of this stage — its
entry ticket is §7 below.

ZERO IP-logic deviation. Every source delta is a 26.2-API translation applied per the api-maps
(`MIGRATION_API_MAP.md` + `migration/api-map/`), carried in-line in each file as a `// 26.2: …`
comment. This note consolidates the two S13-A working fragments (`S13A-u11.md` core half +
`S13A-peripheral.md` peripheral half), the S12-B ratified gate census (`S12B-gate-closure.md`), and
the post-port verification evidence into the single stage record.

**Citation conventions** as in `S12B-gate-closure.md`: `IP:` = 1.21.3
`ImmersivePortalsMod/src/main/java/qouteall`, `26.2:` = `mc262-ref` (Mojang mappings), `MOD:` = the
live proven `com.warwa` substrate.

---

## 1. Closure census resolution — 165 → 2 → COMPLETE (every S12-B gate item accounted)

The ratified S12-B HARD GATE (`S12B-gate-closure.md §4`) proved the probe
(`:common:compileJava -Pip_scc_closed=true`) referenced **exactly 165 javac errors, 100% closure-set
membership** — no error outside the enumerated S13 closure. S13-A ports that set. The re-census with
the whole closure on disk:

| S12-B gate bucket (`S12B-gate-closure.md §4`) | S13-A resolution | Residual |
|---|---|---|
| **U11 autoconfig mass** — `me.shedaniel.autoconfig`(+`.annotation`/`.serializer`), `AutoConfig`/`Config`/`ConfigData`/`ConfigHolder`/`ConfigEntry.*`/`GsonConfigSerializer` (bulk of `IPConfig`=65, `IPModMain`=24, `IPGlobal`) | **F21 autoconfig stub extension** (§4) + `IPConfigGUI` port | 0 |
| **commands + argument types** — `commands/*`, `PortalCommand`, `ClientDebugCommand`, `PortalDebugCommands`, `AxisArgumentType`/`SubCommandArgumentType`/`TimingFunctionArgumentType` | U11 `commands/*` port (§2.1) | 0 |
| **block_manipulation** — `BlockManipulationClient`/`Server` | U11 `block_manipulation/*` port (§2.1) | 0 |
| **U12 generation slice** — `custom_portal_gen`, `BreakablePortalEntity`, `NetherPortalEntity`, `GeneralBreakablePortal`, `FastBlockAccess`, `BlockTraverse`, `CustomPortalGenManager`, `PortalGenInfo` | U12 slice + full 12-file `form` package (§2.3) | 0 |
| **wand / debug / misc shells** — `PortalWandInteraction`, `debug/DebugUtil`, `GcMonitor`/`DubiousThings` | wand package (peripheral, §2.2) + `debug`/`miscellaneous` (§2.1) | 0 |
| **ratified amendment** — `ShaderCodeTransformation` (`IPModMainClient:25,:77`) | Landed as **SHELL** (§3) | 0 |
| **documented residue** — `addTask`/`completeTask` (`ImmPtlNetworkConfig`) | Fabric interface-injection, resolves at `:fabric` | **2** |

**Also closed this stage (closure extensions the U11 row does not name, all proven in
`EXECUTION_PLAN` S13(a)):** the F11 `DimLib` stub extension (`DimensionAPI` + `DimensionTemplate`,
§4), the **entire 11-file `dim_stack` compile shell**, the **9-file `alternate_dimension` compile
shell** + its `@Mixin` accessor/invoker interfaces (§2.2, census amended 3→4 in §2.2.1), the
`CreativeModeTab$Output` and `NoiseBasedChunkGenerator` AW/AT pairs (§6), and the dead-file
`redstone/CrossPortalRedstoneMediumBlockEntity` disposition.

**Net census:** the S13-A closure ports resolved **162 of 165** probe errors; the residual **2**
(`ImmPtlNetworkConfig:194 completeTask`, `:218 addTask`) are Fabric interface-injection residue that
resolves only at `:fabric` (P3 in §7). The closure is COMPLETE and carries **zero translation
defects** — every ported `qouteall` file compiles against the ipStubs/fabricStubs shells except the
two documented injected methods.

---

## 2. Per-package port records (all files HELD-INERT)

### 2.1 U11 core half — commands / block_manipulation / custom_portal_gen / debug / miscellaneous / config-GUI / example

| Package / file | Notes |
|---|---|
| `commands/*` | `PortalCommand`, `PortalDebugCommands`, `ClientDebugCommand`, `PortalAnimationCommand`, `AxisArgumentType`, `SubCommandArgumentType`, `TimingFunctionArgumentType` (`PortalAnimationCommand` is a same-package sibling — import-invisible; joins the closure with the group) |
| `block_manipulation/*` | `BlockManipulationClient`, `BlockManipulationServer` |
| `portal/custom_portal_gen/*` | `CustomPortalGeneration`, `CustomPortalGenManager`, `PortalGenInfo`, `PortalGenTrigger`, `SimpleBlockPredicate` |
| `debug/DebugUtil`, `miscellaneous/{GcMonitor,DubiousThings,IPortalInitialScreen}` | |
| `redstone/CrossPortalRedstoneMediumBlockEntity` | dead upstream, zero in-tree referencers, zero `qouteall` imports — ported **verbatim** for fidelity, never registered (Appendix A.9 disposition; parent-sanctioned dead-file pattern) |
| `render/ShaderCodeTransformation` | **ratified S12-B gate-set amendment — landed as a SHELL** (§3) |
| `platform_specific/IPConfigGUI` | autoconfig/cloth-config GUI; F21 stub extension (§4) |
| `api/example/ExampleGuiPortalRendering` | round-3 closure adoption (`PortalDebugCommands:47` imports, `:89` uses); carries one FORCED render shell (§3.2) |

**Recurring 26.2 API translations (verbatim-per-api-map, zero logic change)** — applied uniformly and
annotated in-line; the load-bearing families:

- **`ResourceLocation` → `Identifier`; `ResourceKey.location()` → `identifier()`** (network.md C1/C10;
  cross-cutting over every dim-id log/format and `SimpleBlockPredicate` tag parse).
- **`RegistryAccess.registryOrThrow` → `lookupOrThrow`** (returns `Registry<E>`, so downstream
  `entrySet()`/`.asLookup()` folds; `CustomPortalGenManager`, `PortalDebugCommands`).
- **`CommandSourceStack`/`Player.hasPermission(int)` GONE → `.permissions().hasPermission(Permissions.COMMANDS_*)`**
  (level 2 = `COMMANDS_GAMEMASTER`, 3 = `COMMANDS_ADMIN`, 4 = `COMMANDS_OWNER`; `PortalCommand`,
  `PortalDebugCommands`).
- **`EntityType.create(Level)` → `create(Level, EntitySpawnReason.LOAD)`** (network.md C11; `LOAD` is
  vanilla's own spawn-packet reason and the reason the whole ported portal subsystem uses).
- **`Direction.getNearest(double…)` → `getApproximateNearest`; `Direction.getNormal()` → `getUnitVec3i()`**
  (`BlockManipulation*`, `PortalCommand`, `FlippingFloorSquareForm`, `DiligentMatcher`).
- **`CompoundTag` Optional-accessor rework** — `getInt/getString/getCompound/getBoolean/getDouble` →
  the `*Or`/`*OrEmpty` lenient-default variants that reproduce 1.21.3's silent defaults 1:1
  (`FastBlockPortalShape`, `BreakablePortalEntity`, `CommandStickItem`); `getList(name,typeId)` →
  `getListOrEmpty(name)`.
- **`ChunkPos` is a record** — `x/z` fields → `x()/z()`; `new ChunkPos(BlockPos)` →
  `ChunkPos.containing(BlockPos)`; `ChunkPos.asLong/toLong` → `pack`.
- **`net.minecraft.util.Tuple` GONE → `com.mojang.datafixers.util.Pair`** (`getA/getB` →
  `getFirst/getSecond`; `new Tuple<>(a,b)` → `Pair.of(a,b)`, element/weight order preserved;
  `BlockManipulation*`, `DiligentMatcher`).
- **`getMinBuildHeight/getMaxBuildHeight` → `getMinY/getMaxY`** — SEMANTIC TRAP recorded in-line:
  `getMaxY()` is **inclusive** (= old exclusive − 1), so `< getMaxBuildHeight()` becomes
  `<= getMaxY()`; the MIN side has no off-by-one (`BlockManipulationServer`; parallel to the
  `getMaxSection()` → `getMaxSectionY() + 1` exclusive-bound fold in `FastBlockAccess`).
- **`InteractionResultHolder<ItemStack>` GONE → `InteractionResult`** (`Item.use`);
  **`InteractionResult.shouldSwing()` GONE** → the vanilla `Success` + `swingSource() == SERVER`
  pattern (`BlockManipulationServer`).
- **`Entity.saveWithoutId/load(CompoundTag)` → `ValueOutput`/`ValueInput`** bridged via
  `TagValueOutput`/`TagValueInput` (the `McHelper.copyEntity` pattern; `PortalCommand`).
- **`Entity.getServer()` GONE → `entity.level().getServer()`** (`CustomPortalGenManager`); server-side
  `Player.displayClientMessage(c,false)` → `sendSystemMessage(c)` (`displayClientMessage` GONE from
  `Player`/`ServerPlayer` in 26.2).
- **Client GUI extract-render model** — `Screen.render(GuiGraphics…)` → `extractRenderState(GuiGraphicsExtractor…)`,
  `keyPressed(int,int,int)` → `keyPressed(KeyEvent)`, `Minecraft.setScreen` → `setScreenAndShow`/
  `Minecraft.gui.setScreen`, `TextureTarget` ctor `(label,w,h,depth,GpuFormat)`,
  `StringWidget.alignCenter()` removed (`IPortalInitialScreen`, `ExampleGuiPortalRendering`).

### 2.2 Peripheral half — wand / dim_stack / alternate_dimension / CommandStickItem

The C1 "skip dim stack / skip alt dims" decision stays valid ONLY for the **runtime/GUI** halves — the
**compile shell** is closure-mandatory (`PortalCommand` → `DimStackManagement` → `DimStackGuiController`
→ `AlternateDimensions` → the chunk-generator chain).

| Package | Files |
|---|---|
| `peripheral/wand/*` (9) | `PortalWandInteraction`, `PortalWandItem`, `ProtoPortal`, `ProtoPortalSide`, `PortalCorner`, `WandUtil`, `ClientPortalWandPortalCopy`, `ClientPortalWandPortalCreation`, `ClientPortalWandPortalDrag` |
| `peripheral/dim_stack/*` (11) | `DimStackManagement`, `DimStackGuiController`, `DimStackScreen`, `DimStackGuiModel`, `DimEntryWidget`, `DimListWidget`, `DimStackEntryEditScreen`, `SelectDimensionScreen`, `DimStackInfo`, `DimStackEntry`, `DimensionStackAPI` |
| `peripheral/alternate_dimension/*` (9) | `AlternateDimensions`, `NormalSkylandGenerator`, `DelegatedChunkGenerator`, `ChaosBiomeSource`, `ErrorTerrainGenerator`, `RegionErrorTerrainGenerator`, `ErrorTerrainComposition`, `FormulaGenerator`, `RandomSelector` |
| `peripheral/mixin/common/alternate_dimension/*` (**4** — see §2.2.1) | `IEChunkAccess_AlternateDim`, `IEChunkGenerator_AlternateDim`, `IENoiseRouterData`, **`IENoiseGeneratorSettings`** |
| `peripheral/CommandStickItem` | cycle-9 item |

**Peripheral-specific 26.2 translations** (in addition to the §2.1 cross-cutting families):

- **`ChunkGenerator` override-signature churn** (`DelegatedChunkGenerator`): `applyCarvers` drops the
  `GenerationStep.Carving` arg; `createStructures` gains a `ResourceKey<Level>` arg;
  `getTypeNameForDataFixer()` returns `Optional<Identifier>`; `getMobsAt` returns `WeightedList` (was
  `WeightedRandomList`).
- **`MinecraftServer.getWorldData().worldGenOptions()` → `getWorldGenSettings().options()`**
  (`AlternateDimensions`, `DimStackManagement`); `registryOrThrow(..).asLookup()` → `lookupOrThrow(..)`;
  `Registry.getHolderOrThrow` → `getOrThrow`.
- **`ChunkAccess.setBlockState` 3rd arg** is now `@Block.UpdateFlags int` (not `boolean`) — the 2-arg
  convenience overload is used (`DimStackManagement`); the same inclusive-`getMaxY()` trap applies.
- **Client GUI extract-render model** (`dim_stack` screens/widgets): `AbstractSelectionList.Entry.render(...)`
  → `extractContent(...)`; `getScrollbarPosition()` → `scrollBarX()`; `renderListBackground` →
  `extractListBackground`; `mouseClicked(double,double,int)` → `mouseClicked(MouseButtonEvent,boolean)`;
  `mouseDragged(...)` → `mouseDragged(MouseButtonEvent,double,double)`; `Minecraft.setScreen` →
  `Minecraft.gui.setScreen`.
- **Item surface** (`CommandStickItem`, `PortalWandItem`): `Item.use` returns `InteractionResult`;
  `appendHoverText` gains a `TooltipDisplay` param and emits through a `Consumer<Component>`
  (`add` → `accept`); `getDescriptionId(ItemStack)` GONE → `getName(ItemStack) : Component`;
  `Player.createCommandSourceStack()` moved to `ServerPlayer`; `CommandSourceStack.withPermission(int)`
  → `withPermission(LevelBasedPermissionSet.GAMEMASTER)`.

#### 2.2.1 Unaccounted-file disposition — `IENoiseGeneratorSettings` (census amended 3 → 4)

`EXECUTION_PLAN` S13(a) (~L1118-1120) enumerates **three** `@Mixin` accessor/invoker interfaces for the
alternate_dimension shell — the three `NormalSkylandGenerator.java:44-46` *imports*
(`IEChunkAccess_AlternateDim`, `IEChunkGenerator_AlternateDim`, `IENoiseRouterData`). The package
actually contains a **fourth**, `IENoiseGeneratorSettings` — present in the working tree, absent from
the plan's census (surfaced in the S13-A defect pass as the one "unaccounted IP file"). Disposition
(parent-sanctioned dead-file pattern, mirroring `redstone/CrossPortalRedstoneMediumBlockEntity`,
Appendix A.9):

- It is a **real IP file** (`IP:imm_ptl/peripheral/mixin/common/alternate_dimension/IENoiseGeneratorSettings.java`),
  ported **byte-verbatim** — a `@Mixin(NoiseGeneratorSettings.class)` interface whose entire body
  (the `floatingIslands`/`overworld`/`end` `@Invoker`s) is **commented out in IP itself**.
- Its **sole referencer** in the whole closure is a **commented-out line** in `NormalSkylandGenerator`
  (`IP:127` `// … IENoiseGeneratorSettings.ip_end();`, ported verbatim at `WT:126`). Nothing imports or
  calls it — it is dead-in-closure, exactly as in IP. `NormalSkylandGenerator` imports only the three
  the plan enumerated (lines 44-46); this fourth is a dead sibling.
- It compiles trivially (imports only `NoiseGeneratorSettings` + `@Mixin`), is held-UNREGISTERED (no
  `mixins.json` entry — grep-verified), and carries no runtime effect.

**Resolution: keep verbatim, document.** Removing it would deviate from IP (IP ships the file) and
would require a non-carve-in `IpHeldPaths` edit; the faithful move is to port it and amend the census
here — the plan's "three" becomes **four** (three imported + one dead sibling). No code change.

> **CORRECTION (S13-B P3, `migration/port-notes/S13B-flip.md §7`):** the original parenthetical here — "no
> registration site (IP has none either)" — was **FALSE**. IP's `imm_ptl_peripheral.mixins.json`
> registers **all four** alt-dim `@Mixin` interfaces, `IENoiseGeneratorSettings` included
> (`IP:src/main/resources/imm_ptl_peripheral.mixins.json:7-10`, verified). The correct disposition is
> not "IP has no registration site" but **an S19 deferral**: the port holds all four
> **UNREGISTERED** (`seamlessportals-ip-peripheral.mixins.json` left empty + unreferenced) because
> their only consumers — the `alternate_dimension` generators (`NormalSkylandGenerator` etc.) — are
> C1/S19-gated (dynamic-dimension runtime), so no code casts a target to these accessor/invoker
> interfaces before S19. They register at S19 with the dynamic-dimension bring-up. This S19 deferral is
> **ratified** at S13-B (`migration/port-notes/S13B-flip.md §7`); `IENoiseGeneratorSettings` remains a dead sibling
> (its `@Invoker`s are commented out even in IP), so it carries no member either way.

### 2.3 U12 closure slice + the full `form` package

The SCC provably extends into U12, so this stage's green build requires co-porting the generation
pipeline HEAD (all grep-verified — `EXECUTION_PLAN` S13(a) L1127-1159):

| Files | Why in closure |
|---|---|
| `portal/nether_portal/*` (U12 slice) — `NetherPortalGeneration`, `NetherPortalMatcher`, `FastBlockAccess`, `FrameSearching`, `BlockTraverse`, `FastBlockPortalShape`, `BreakablePortalEntity`, `NetherPortalEntity`, `GeneralBreakablePortal` | `ChunkLoader:10` (U7) imports `FastBlockAccess`; `PortalCommand:69-70` imports `BreakablePortalEntity`+`NetherPortalMatcher`; `PortalGenInfo:15-16` imports `BreakablePortalEntity`+`NetherPortalGeneration`; `IPModMain:45-46` imports `GeneralBreakablePortal`+`NetherPortalEntity`; same-package internals `FrameSearching`/`BlockTraverse`/`PortalGenTrigger` pulled by `NetherPortalGeneration`/`NetherPortalMatcher`/`CustomPortalGeneration` (import-invisible) |
| The **entire 12-file `form` package** — `PortalGenForm`, `AbstractDiligentForm`, `DiligentForm`, `DiligentMatcher`, `NetherPortalLikeForm`, `ScalingSquareForm`, `ClassicalForm`, `HeterogeneousForm`, `FlippingFloorSquareForm`, `FlippingFloorSquareNewForm`, `ConvertConventionalPortalForm`, `OneWayForm` | `PortalGenForm.java:27-48` codec-registers SEVEN forms in its own **STATIC BODY** (`ClassicalForm`:27, `HeterogeneousForm`:30, `FlippingFloorSquareForm`:33, `FlippingFloorSquareNewForm`:39, `DiligentForm`:42, `ConvertConventionalPortalForm`:45, `OneWayForm`:48) — same-package, INVISIBLE to import-grep; `AbstractDiligentForm:22-29` uses `DiligentMatcher`; `CustomPortalGeneration` imports `form.PortalGenForm` |

**Codec fidelity (S13-A defect pass, ground truth `scratchpad/s13a_alldiffs.txt`):** `PortalGenForm`'s
7-form static codec-registration body is **byte-identical to IP** (sole diff: `net.minecraft.Util` →
`net.minecraft.util.Util`); the individual form CODECs and the alt-dim `MAP_CODEC`s
(`NormalSkylandGenerator` etc.) are identical. **No codec deviation.**

### 2.4 stub source sets (compile-classpath only, removed at S20)

| Set | Growth |
|---|---|
| **F21 autoconfig** (`ipStubs`, §4) | `me.shedaniel.autoconfig.{AutoConfig,ConfigData,ConfigHolder}`, `…annotation.{Config,ConfigEntry}`, `…serializer.{ConfigSerializer,GsonConfigSerializer}` |
| **F11 DimLib** (`ipStubs`, §4) | `qouteall.dimlib.DimensionTemplate` + `qouteall.dimlib.api.DimensionAPI` |
| **command/event/registry fabricStubs** (`fabricStubs`) | `client.command.v2.{ClientCommands,FabricClientCommandSource}`, `command.v2.ArgumentTypeRegistry`, `event.lifecycle.v1.ServerLifecycleEvents` (grown: `END_DATA_PACK_RELOAD`), `event.registry.DynamicRegistries` — `:common` compile classpath only |

---

## 3. `ShaderCodeTransformation` SHELL record + the FORCED shell family

### 3.1 `ShaderCodeTransformation` — the ratified S12-B gate-set amendment, executed as a SHELL

**Import chain (S12-B ratified):** `IPModMainClient` (the U11 client-init closure hub, committed
S10.1) imports `qouteall.imm_ptl.core.render.ShaderCodeTransformation` (`IPModMainClient.java:25`) and
calls `ShaderCodeTransformation.init()` (`:77`) exactly as IP does. Per the D4.2 NOTE the S13 closure
set is AMENDED to include it (plan + `S12B-gate-closure.md §2`).

**S13-GREEN BLOCKER:** IP's `ShaderCodeTransformation` imports the GONE 26.2 type
`com.mojang.blaze3d.shaders.CompiledShader` (api-map/mixin-client §8 `MixinCompiledShader` row — the
whole `CompiledShader`/`CompiledShaderProgram` GLSL-compile stack is TARGET-GONE). It is **NOT
verbatim-portable.** Per the ratified amendment it landed as a **SHELL**: the GONE-type internals are
commented out, the public surface + `init()` lifecycle are kept, so `IPModMainClient` links.

**FrontClipping-deferred internals (commented, verbatim IP body preserved):**

| Deferred internal | Reason GONE / off-classpath | Owner |
|---|---|---|
| `CompiledShader.Type` GLSL transform stack | `com.mojang.blaze3d.shaders.CompiledShader` TARGET-GONE (core-profile pipeline model; GLSL transform re-sites to the `ShaderManager` source layer) | FrontClipping shader redesign |
| cloth-config-shaded snakeyaml `Yaml` loader | off-classpath (no MC 26.2 build of the shaded snakeyaml) | FrontClipping shader redesign |

Porting `ShaderCodeTransformation` non-shell is **FORBIDDEN this stage** (S13 closure class); the
FrontClipping redesign owns the real transform. This is the same redesign owner as the S4-carried
GONE-`blaze3d.shaders` ducks (`IEShader`, dropped `MixinCompiledShader`/`MixinShaderInstance`/
`MixinRenderSystem_Clipping`).

### 3.2 The FORCED shell family (V2-2 amendment — recorded for parent ratification)

The 26.2 render/GUI-API removals force a **family** of compile shells beyond the single ratified
`ShaderCodeTransformation` sanction. All mirror the same parent-ratified pattern (S11-B immediate-mode-
blit drop + the `MixinCompiledShader`/`MixinShaderInstance` precedent): the GONE type's call is
commented out with the **verbatim IP body preserved** and deferred to the owning S19 redesign; the
public surface + non-render lifecycle are kept so the closure compiles. Every one is **inert** — no
in-tree caller until the S19 redesigns, and the IP draw sites (immediate-mode `MyRenderHelper`,
`MixinDebugRenderer`) are themselves GONE on 26.2.

| File(s) | Deferred call/surface | Owner |
|---|---|---|
| `render/ShaderCodeTransformation` | `CompiledShader.Type` transform stack + snakeyaml `Yaml` loader (§3.1) | FrontClipping shader redesign |
| `api/example/ExampleGuiPortalRendering` | `MyRenderHelper.drawFramebufferWithBounds` bounds-blit (`S11B-render-drivers.md §1.2` deferred immediate-mode blit family; the method does not exist in the committed 26.2 `MyRenderHelper`) | S19 immediate-mode blit re-enable |
| `wand/{PortalWandItem, ClientPortalWandPortalCopy, ClientPortalWandPortalCreation, ClientPortalWandPortalDrag}` | immediate-mode `MultiBufferSource.BufferSource`/`RenderType` overlay draw (GONE — immediate-mode rendering removed; re-sites to the Gizmos API) | S19 Gizmos redesign (C1) |
| `dim_stack/{DimEntryWidget, DimStackScreen, DimStackEntryEditScreen, SelectDimensionScreen}` | `Screen`/`Entry` immediate-draw bodies under the `GuiGraphics` → `GuiGraphicsExtractor` extract model (GONE draw API) | S19 dim_stack GUI runtime (C1) |

The dim_stack runtime/GUI wiring (`MixinCreateWorldScreen_CVB` et al.) and dynamic-dimension bring-up
stay C1/S19-gated. The compile shells here are only what the SCC demands.

---

## 4. Stub extensions — F21 autoconfig + F11 DimLib

### 4.1 F21 autoconfig stub (ipStubs `compileOnly`)

`IPConfig`/`IPConfigGUI` reference the `me.shedaniel.autoconfig` cloth-config/autoconfig family, which
has **no MC 26.2 build** (OUTSIDE F21's original Sodium/Iris scope). Resolution = the F21 EXTENSION:
the `ipStubs` set grows the exact surface those two files reference (65-error `IPConfig` bulk + the
GUI) — `AutoConfig`, `ConfigData`, `ConfigHolder`, `…annotation.{Config, ConfigEntry}` (incl. the
`ConfigEntry.Gui.*` family), `…serializer.{ConfigSerializer, GsonConfigSerializer}` — shelled like the
Sodium/Iris F21 shells. Compile-classpath only; removed at S20.

### 4.2 F11 DimLib stub (ipStubs)

DimLib has **no MC 26.2 build and no source in the IP tree**, so a stub on the `:common` probe / loader
classpath is the correct resolution (like the F21 sodium/iris shells). The S13 chain demands TWO types
(plan L1100-1125):

- **`qouteall.dimlib.api.DimensionAPI`** — `DimStackManagement` imports it (event-wiring); the S4
  `DimensionIntId` use alone was insufficient.
- **`qouteall.dimlib.DimensionTemplate`** — `AlternateDimensions` imports it.

Both shelled on BOTH the `:common` probe and the loader classpath.

---

## 5. Geometry sign-derivation notes (S13-A defect pass — NO sign flip found)

Per D2/D4 the geometry (`BlockPortalShape`/matcher math especially) must be SIGN-derived against IP.
The S13-A defect pass ran a whitespace-insensitive diff of every geometry file vs IP (full corpus at
`scratchpad/s13a_alldiffs.txt`):

- **`nether_portal`:** `FastBlockPortalShape` `create()`/frame-corner math; `NetherPortalMatcher`
  (identical); `BlockTraverse` (identical); `FrameSearching`; `NetherPortalGeneration`; `FastBlockAccess`.
- **`form`:** `DiligentMatcher`.
- **`alternate_dimension`:** the generator math (`NormalSkylandGenerator` etc.).
- **`wand`:** the plane math.

**Every delta is a documented 26.2 rename** — `getNormal()` → `getUnitVec3i()`, `asLong` → `pack`,
`getInt` → `getIntOr`, `getMaxSection()` → `getMaxSectionY() + 1` (exclusive-bound fold). **No sign
change anywhere; the shape/matcher/traverse math is byte-faithful to IP.** `FrameSearching`,
`NetherPortalMatcher`, `BlockTraverse` and `FastBlockPortalShape.create()` diff to IP as **whitespace +
the above renames only.**

---

## 6. AW/AT paired growths + IpHeldPaths record (D2/D4 — every widen PAIRED)

Every access-widen is PAIRED across `.accesswidener` + `accesstransformer.cfg` and verified complete:

| Target | Why | AW / AT |
|---|---|---|
| `net.minecraft.util.profiling.ActiveProfiler#WARNING_TIME_NANOS` | 26.2 makes it `private static final long` (was `public static` non-final in 1.21.3); `PortalDebugCommands` `/portal debug profile set_lag_logging_threshold` writes it verbatim. Non-constant initializer (`Duration.ofMillis(..).toNanos()`) ⇒ not inlined ⇒ write is runtime-effective. | AW `accessible`+`mutable` / AT `public-f` |
| `net.minecraft.world.item.CreativeModeTab$Output` | 26.2 demotes it to a `protected interface` (public in 1.21.3); `CommandStickItem.addIntoCreativeTag(CreativeModeTab.Output)` + `PortalWandItem`'s counterpart use it as a public method param. Fabric API's own AW widens exactly this nested type. | AW `accessible class` / AT `public` |
| `net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator` | 26.2 makes it `public final`. `NormalSkylandGenerator` **must** subclass it so `ChunkMap`'s `generator instanceof NoiseBasedChunkGenerator` seeds `RandomState`/`createState` (extending `ChunkGenerator` instead loses the instanceof seeding = worldgen-randomness fidelity violation, api-map platform-compat-peripheral §2). IP's own `imm_ptl.accesswidener` widens exactly this class. | AW `extendable` / AT `public-f` |

**IpHeldPaths / gradle.properties record:** `IpHeldPaths.groovy` is **unmodified vs HEAD** — NO new
carve-in hold. The entire closure is held by the existing broad held-path filter (proven by
shipping-green with the whole closure on disk). `gradle.properties` `ip_scc_closed=false` (unflipped).
No new IpHeldPaths edit was made or needed this stage.

**Entity-type registration pattern (defect-pass check — established committed pattern, NOT an early
S13-B mechanic):** `GeneralBreakablePortal`/`NetherPortalEntity` use
`createPortalEntityType(X::new, portalEntityTypeKey("..."))` — the same pattern already committed for
`Portal`/`Mirror`/`EndPortalEntity`/etc. (26.2 `EntityType.Builder.build` now requires a
`ResourceKey`). The 4 peripheral alt-dim mixins are held-UNREGISTERED (no `mixins.json` entry).

---

## 7. S13-B ENTRY TICKET — what the flip commit must do

S13-A is HELD-INERT. The **S13-B** commit(s) perform the flip and first light. Plan steps
(`EXECUTION_PLAN` S13(a) L1160-1193):

1. **Flip `ip_scc_closed=true` in `gradle.properties`** (committed). The whole `qouteall` tree must
   compile in `:common` AND `:fabric` (+`:common:test`) — the Sodium/Iris-importing compat files
   against the F21 `compileOnly` stub classpath. **Burn down the translation-slip tail** against the
   slice maps until green — never by simplifying (see the FLIP-BLOCKER LEDGER below).
2. **Register the IP mixin configs** (S0 skeletons, now populated), ALL gated on `entityPortals` via
   `SeamlessMixinConfigPlugin` (D3) — including the 4 held-unregistered peripheral alt-dim mixins.
3. **Exclusivity ledger populated + ENFORCED in the same commit:** every block-era driver gains its
   `!entityPortals` gate (D3 census); the S3 pump host gains its live flag dispatch. Exactly one driver
   set runs per session in either flag state from this commit onward.
4. **Wire runtime init behind the flag**, in DEPENDENCY_ORDER §4.2 order into the S0-named seams
   (init sequences, login order, client tick order, frame order, server tick order).
5. **Register entity types + placeholder block + argument types + payloads unconditionally** (D3), and
   **wire the entity-RENDERER registrations** through the S0 renderer-registration seam
   (`PortalEntityRenderer` for the Portal entity-type family + `LoadingIndicatorRenderer` — IP does
   this in `IPModEntryClient:27-28`, not ported per Appendix A.9; without it rung 1 renders no portal).
6. **Un-hold `Mesh2DTest` + `HelperTest`** (D1 exclusions drop) + activate the IntBox invariant tests;
   full `:common:test` green.

### 7.1 FLIP-BLOCKER LEDGER — documented forward-debt for the S13-B `:fabric` flip + burn-down

These are **NOT S13-A working-tree defects** and **NOT `:common` defects.** They are Fabric-API
translation slips in **committed S7/S10 files**, invisible to every `:common` probe because the
committed S10-A fabricStubs/interface-injection shells resolve the stale surface at `:common`. They
surface only at the S13-B `:fabric` flip and are exactly the "translation-slip tail" step 1 mandates
burning down. Recorded (not fixed) so the flip does not lose them — per the stage rule that a verifier
flag against documented forward-debt is resolved by correcting the ledger, not by editing held
committed source during the inert S13-A batch. (Each stub is single-consumer, grep-verified.)

| Ref | Site | 26.2 reality (api-map) | S13-B burn-down action |
|---|---|---|---|
| **P1** | `ImmPtlNetworkConfig.java:208,:212` — `PayloadTypeRegistry.configurationS2C()/configurationC2S()` | fabric-networking v6 renamed these `clientboundConfiguration()`/`serverboundConfiguration()` (**network.md headline-5 + F1**; working proof: live `FabricPlatformHelper` already uses `clientboundPlay()`/`serverboundPlay()`). Masked by the S10-A `PayloadTypeRegistry` stub, which shells the stale v5 names. | Rename the two source calls to the v6 names **and** the stub methods together (single consumer: `ImmPtlNetworkConfig`), so the `:common` probe stays honest and `:fabric` links. |
| **P2** | `PlayerChunkLoading.java:225` — `AttachmentChange.partitionAndSendPackets(List, ServerPlayer)` | fabric-data-attachment-api 2.2.16: `AttachmentChange` is now a `(targetInfo,type,value)` record with no partition/send member; the send half moved into `AttachmentSync` (`fabric_computeInitialSyncChanges` survives). **Already flagged in `chunk-loading.md` row 53** as FABRIC-internal, loader-specific, "must be re-verified against the 26.2 Fabric API." Masked by the S10-A `AttachmentChange` stub. | Re-derive the `:212-227` sync block against the real 26.2 `AttachmentSync` on the `:fabric` classpath (compile-verifiable only there); NeoForge uses its own attachment sync or none. Do **not** guess blind against the stub. |
| **P3** | `ImmPtlNetworkConfig.java:194,:218` — `ServerConfigurationPacketListenerImpl.completeTask/addTask` | Fabric interface-injection (`FabricServerConfigurationPacketListenerImpl`, api-map F8). Resolve **only** at `:fabric`. S12-B parent ruling 5 = "no action" for the pre-flip probe; these are the **sole 2 residual `:common` probe errors** at S13-A. But step 1 wants the tree to compile "in `:common` **and** `:fabric`" post-flip, which the injected methods cannot at standalone `:common`. | Add a `fabricStubs` shell of the injected interface (`FabricServerConfiguration…`-style) with `addTask`/`completeTask` + cast the two call sites through it — the honest shell the real fabric-api provides at `:fabric`. Post-flip concern; deferred out of the inert S13-A batch. |

### 7.2 Absorbed-Fabric-entrypoints ledger (P4 — doc row, so S16/S19 do not drop it)

`PeripheralModEntry` / `PeripheralModEntryClient` (`imm_ptl/peripheral/platform_specific`) are absorbed
under the **Appendix A.9** disposition (Fabric entrypoints not ported as classes; their init cargo
re-hosted through the mod's own registration seams) — same disposition as `IPModEntry`/
`IPModEntryClient`, by analogy but not previously named in A.9. Their registration cargo is **not lost,
only unwired at S13-A**: `PeripheralModMain.{registerBlocks, registerItems, registerChunkGenerators,
registerBiomeSources, registerCreativeTabs}` + `init`/`initClient`. Wire into the mod's
registration/init seams at **S16 (server/registry) / S19 (peripheral GUI+runtime)**.

### 7.3 First-light watch items (S13 runClient — user-gated, NOT this stage)

Rung 1 is same-dimension command portals only (isolates R5 sign flips + driver core from R1/R7/R9).
Flag-OFF must be UNCHANGED baseline sanity (proves the closure landed inert). Flag-ON failure
signatures → risks to watch (`EXECUTION_PLAN` S13(d)):

- black/empty window → view-area mesh or stencil masks (**R5/R6**); portal draws in front of everything
  → reversed-Z flip missed (**R5**); inside-out view → transform sign (**S6**); crash on spawn →
  entity-type/NBT (**R11**); portal entity invisible → tracking-range conversion (**F14**).
- crash/hang in `ChunkVisibility`/`ImmPtlChunkTracking`/`ImmPtlChunkTickets` at spawn or following
  ticks (**R10** — the ticket path runs even same-dim: `ChunkVisibility` builds a dest-dim chunk loader
  unconditionally, merely MASKED because the dest chunks are already loaded around the player).
- crossing (fwd/back/strafe): seamless reposition, no camera snap, hand steady, sprint preserved,
  motion-side exits, no oscillation (regression items 1, 2).
- global portal RELOG persistence — if it comes back EMPTY that is the swallowed-NPE signature from the
  SPIKE-R11 memo (**R11**; F15's loud guard should fire instead).
- **C4 rider (auto-memory):** whenever the user live-tests entities through portals (S13/S15/S17/S18),
  bring up the A/B clip-mechanism switch (BOTH clip mechanisms kept live-switchable per C4).

---

## 8. VERIFICATION TIER

Post-port, `--no-daemon`, per `multiloader-common.gradle` documented invocation; logs in the session
scratchpad (`s13a_shipping.log`, `s13a_probe.log`, `s13a_test.log`). Probe invocations are `-P` flag
only — `gradle.properties` `ip_scc_closed` stays `false`.

| Build | Result |
|---|---|
| **Shipping `:common:compileJava`** (no flag) | **BUILD SUCCESSFUL** — all S13-A files held-inert (`ip_scc_closed=false`); live block-era substrate byte-untouched (zero `com.warwa` runtime edit) |
| **Probe `:common:compileJava -Pip_scc_closed=true`** | **2 errors** (`ImmPtlNetworkConfig:194 completeTask`, `:218 addTask` — the P3 interface-injection residue), **down from S12-B's 165**. The S13-A closure ports resolved **162 of 165**; the closure now compiles with **ZERO translation defects** |
| **`:common:test`** (no flag) | **BUILD SUCCESSFUL** |

The probe re-census is the ground truth: with the whole closure on disk, every ported `qouteall` file
compiles against the ipStubs/fabricStubs shells except the two documented interface-injection methods.
**No geometry sign flip** (§5, `s13a_alldiffs.txt`), **no codec deviation** (§2.3), **no early-S13-B
mechanic** (§6 — `ip_scc_closed=false`, IpHeldPaths unmodified, established entity-type pattern, mixins
held-unregistered), **no un-held file** — confirmed by both the exhaustive IP diff and the compile.

**Net working-tree change from S13-A:** the closure source ports (held-inert, drafted across the two
dead-run + resume passes) + this port-note; **zero live-substrate code edit; `ip_scc_closed` stays
`false`; no git commit; game not run.** The flip is S13-B (§7).
