# S5 — MC helpers (U3) — port-note

**Stage:** S5 (EXECUTION_PLAN §3 S5). **Unit:** U3 — `McHelper`, `CHelper`, `IPMcHelper`,
`ScaleUtils` + the four `mc_util/` entity-traversal mixins.
**Discipline:** D2 verbatim `qouteall.*` paths · D4.2 probe-ledger triage · D4.3 source-diff gate ·
D4.4 sign-derivation · R13d height-bound inclusivity (owned here) · R12 EntitySection traversal
re-derivation (owned here) · §2.10 traversal mixins land with the unit, unregistered until S13.
**IP source root:** `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`
**26.2 evidence root (authoritative, Mojang mappings):** `C:/Users/warwa/ModDev/mc262-ref`
**API-map amended by this stage:** `migration/api-map/world-loader-root.md` (+3 GONE, +2 CHANGED,
+1 SAME-annotation, +1 header RECURRING note — the S5 amendment block).

This note consolidates the two working fragments (`fragments/S05-helpers.md` slice A,
`fragments/S05-mixins.md` slice B) plus the fix/build evidence from the
`-Pip_scc_closed=true` probe pass. The fragments directory is deleted at the end of this stage.

---

## 0. Stage result (build evidence)

| Gate | Command | Result |
|---|---|---|
| Shipping build | `:common:compileJava :fabric:compileJava` (`ip_scc_closed=false`) | **BUILD SUCCESSFUL** (helpers held, invisible to javac) |
| Math harness | `:common:test` | **BUILD SUCCESSFUL** (S2 DQuaternion/Plane suite unchanged) |
| Compile probe | `:common:compileJava -Pip_scc_closed=true` | **BUILD FAILED, 291 errors** — expected; every error maps to documented held-tree forward-ref debt (§8 triage). **Zero errors are translation slips on the U3 slice.** |

Held-paths coverage required **no `IpHeldPaths` edit**: `IpHeldPaths.groovy:65`
(`qouteall/imm_ptl/core/*.java`) already covers the four top-level helpers, and `:74`
(`qouteall/imm_ptl/core/mixin/**`) covers the four `mc_util/` mixins. All eight files arrive
default-held; the shipping gate stays green because javac never sees them.

---

## 1. Diff-gate record (D4.3) — per file

Each file was `cp`'d byte-for-byte from IP (CRLF preserved) and edited **only** by 26.2-translation
hunks. `git diff --no-index <IP>/<file> <DST>/<file>` shows no whitespace-only hunk on any file.

| File | LOC | numstat (+/−) | Hunk classes (all traceable to a `world-loader-root.md` row or the two global renames) |
|---|---|---|---|
| `McHelper.java` | 996 | +68 / −37 | global renames ×2; **A–H gaps** ChunkPos(BlockPos), getServer×2, ResourceKey.identifier×5, ChunkPos.x()/.z(), broadcastAndSend-cast, storageSource(AW); pre-documented GONE/CHANGED rows displayClientMessage→sendSystemMessage, lerpTo→InterpolationHandler, ChunkPos.asLong→pack, create(Level,LOAD), TagValueOutput/Input round-trip, ClickEvent.OpenUrl, manual CommandSourceStack ctor + GAMEMASTER, R13d height wrappers |
| `CHelper.java` | 193 | +12 / −12 | global renames ×2; **gap** ResourceKey.identifier×1; GameProfile.id(), gui.hud.getChat().addClientSystemMessage, gui.setScreen ×2, mainCamera().position(), getDimensionIconPath Identifier sweep; raw-GL kept **verbatim** (see below) |
| `IPMcHelper.java` | 331 | +20 / −21 | **gaps** Tuple→Pair (whole file), isClientSide()×1; GONE GlUtil→RenderSystem vendor, ClickEvent.RunCommand |
| `ScaleUtils.java` | 191 | +12 / −9 | global rename ×1; **gap** isClientSide()×2; mainCamera(), sendSystemMessage `instanceof Player` guard |
| `mixin/common/mc_util/IELevelEntityGetterAdapter.java` | — | **0 / 0** | byte-identical to IP |
| `mixin/common/mc_util/MixinEntitySection.java` | — | **0 / 0** | byte-identical to IP |
| `mixin/common/mc_util/MixinEntitySectionStorage.java` | — | **0 / 0** | byte-identical to IP |
| `mixin/common/mc_util/MixinEntity_U.java` | — | **0 / 0** | byte-identical to IP |

**Two global renames** (stated once in the api-map header, applied throughout the slice):
`net.minecraft.resources.ResourceLocation` → `net.minecraft.resources.Identifier` (class renamed,
same package: `Identifier.java:40 fromNamespaceAndPath`, `:44 parse`, `:100 getPath`,
`:104 getNamespace`) and `net.minecraft.Util` → `net.minecraft.util.Util` (package move,
`util/Util.java`; `backgroundExecutor()` now returns `TracingExecutor`, still an `Executor`).

**Do-not-drop (kept verbatim, api-map GONE verdicts are semantics notes, not compile breaks):**
CHelper's raw-GL `doCheckGlError` (`GL11.glGetError`/`GL_NO_ERROR`) and
`disableDepthClamp`/`enableDepthClamp` (`GL11.glDisable/Enable(GL32.GL_DEPTH_CLAMP)`) stay verbatim —
raw LWJGL `GL11`/`GL32` are on the classpath and the mod's stencil-direct work already relies on
raw-GL state on the GL backend (memory `stencil-direct-rework-status`). Any Vulkan-backend
clipping-fidelity decision is a later render-slice concern. IPMcHelper's netty (`Unpooled`,
`FriendlyByteBuf`), `ClipContext`/`world.clip`, `BlockHitResult.miss`, `Direction.getUnitVec3`,
`BlockPos.containing` are all SAME (api-map §5.3/§5.8), verbatim.

---

## 2. R13d — height-bound inclusivity wrapper derivation (HEADLINE; owned at S5)

26.2 `LevelHeightAccessor` (`net/minecraft/world/level/LevelHeightAccessor.java`) flipped two bounds
from **exclusive** (1.21.3 `getMaxBuildHeight`/`getMaxSection`) to **INCLUSIVE**:

- `getMinY()` :9 — replaces `getMinBuildHeight()` (both = lowest block Y, no semantic change).
- `getMaxY()` :11-13 `= getMinY() + getHeight() - 1` → **INCLUSIVE** top block Y.
- `getMinSectionY()` :19 — replaces `getMinSection()` (both = lowest section Y).
- `getMaxSectionY()` :23-25 `= SectionPos.blockToSectionCoord(getMaxY())` → **INCLUSIVE** top section Y.

McHelper's wrappers must keep their **exclusive** contract — their names (`…Exclusive`) and every
downstream call site depend on it (S16 `FastBlockAccess`/`NetherPortalMatcher` block scans). So the
**+1 is added in the WRAPPER, never at a call site** — a single audited site:

| Wrapper (`McHelper.java`) | 1.21.3 body | 26.2 body (ported) | Why |
|---|---|---|---|
| `getMinY` :893 | `world.getMinBuildHeight()` | `world.getMinY()` | rename only (both = lowest block Y) |
| `getMaxYExclusive` :897 | `world.getMaxBuildHeight()` | `world.getMaxY() + 1` | 26.2 `getMaxY()` inclusive; +1 restores exclusive |
| `getMinSectionY` :905 | `world.getMinSection()` | `world.getMinSectionY()` | rename only (both = lowest section Y) |
| `getMaxSectionYExclusive` :909 | `world.getMaxSection()` | `world.getMaxSectionY() + 1` | 26.2 `getMaxSectionY()` inclusive; +1 restores exclusive |

**Compose-through wrappers (bodies verbatim, correct once the two `…Exclusive` carry the +1):**
`getMaxContentYExclusive` = `dimensionType().logicalHeight() + getMinY(world)` (calls the McHelper
`getMinY` wrapper; `DimensionType.logicalHeight()` intact, `DimensionType.java:37`);
`getYSectionNumber` = `getMaxSectionYExclusive(world) - getMinSectionY(world)`.

**Silent-corruption guard:** had the +1 landed at a call site (or been omitted), S16's block-scan
bounds would be short by one row/one section — exactly the R13d hazard the plan names. Fixing the
wrappers alone confines the inclusivity flip to one place. This is a geometry/bound constant, so it
also satisfies the D4.4 sign-derivation obligation; there are no depth/winding/stencil constants in
this slice (see §9).

---

## 3. R12 — `EntitySection`/`EntitySectionStorage` traversal re-derivation (owned at S5)

The R12 hazard is that IP's chunk-scoped entity traversal reaches into vanilla's private section
storage. Two layers were re-derived line-by-line from 26.2 source; both port **verbatim** (the
mixin files are byte-identical to IP — §1).

**Helper-side surface (McHelper `traverseEntities`/`foreachEntities`/`find*`)** touches only SAME
26.2 types, so **no api-translation hunk is needed inside the helpers**:
- `EntitySectionStorage<Entity>` binds against `EntitySectionStorage<T extends EntityAccess>`
  (`EntitySectionStorage.java:24`; `Entity implements EntityAccess`).
- `LevelEntityGetter<Entity>` via `((IEWorld) world).portal_getEntityLookup()` — SAME (§5.6).
- `EntityTypeTest.forClass(entityClass)` — SAME.
- Private `sectionStorage` extracted via `((IELevelEntityGetterAdapter) entityLookup).getCache()` —
  field present unchanged (`LevelEntityGetterAdapter.java:11`).

**Mixin-side actual traversal bodies** (the R12 re-derivation proper), verified structurally
identical to 1.21.3:

*`MixinEntitySection` → `EntitySection` (`ip_traverse`):*
- `@Shadow @Final ClassInstanceMultiMap<T> storage` = `EntitySection.java:14`. ✓
- `type.getBaseClass()` → `EntityTypeTest.getBaseClass()` : `Class<? extends B>`. ✓
- `storage.find(baseClass)` → `ClassInstanceMultiMap.<S>find(Class<S>)` : `Collection<S>`
  (`ClassInstanceMultiMap.java:57`); 26.2's own `getEntities(...)` uses the identical
  `this.storage.find(type.getBaseClass())` (`EntitySection.java:43`). ✓
- `type.tryCast(entity1)` → `@Nullable T tryCast(B)`. ✓
- **Deliberate IP divergence preserved:** `ip_traverse` omits the `getBoundingBox().intersects(bb)`
  check 26.2's `getEntities` has (`EntitySection.java:47`) and returns `func.apply(...)` rather than
  an `AbortableIterationConsumer.Continuation`. IP semantics — every primitive is unchanged, ports verbatim.

*`MixinEntitySectionStorage` → `EntitySectionStorage` (`ip_traverseSectionInBox`):*
- `@Shadow @Final LongSortedSet sectionIds` = `EntitySectionStorage.java:30`; `@Shadow @Final
  Long2ObjectMap<EntitySection<T>> sections` = `:29`. ✓
- Per-X-slab bounds `SectionPos.asLong(cx,0,0)` / `SectionPos.asLong(cx,-1,-1)` and the subset
  `sectionIds.subSet(xStart, xEnd + 1L)` — **identical** to vanilla's own
  `forEachAccessibleNonEmptySection` (`EntitySectionStorage.java:46-49`). ✓
- `LongBidirectionalIterator` from `LongSortedSet.iterator()` (fastutil, MC-version-independent). ✓
- `SectionPos.y(...)` / `SectionPos.z(...)` / `sections.get(...)` — same at `:52-54`. ✓
- `entityTrackingSection.getStatus().isAccessible()` — `EntitySection.getStatus()` : `Visibility`
  (`EntitySection.java:64`); `Visibility.isAccessible()` (`Visibility.java:22`). ✓
- **Deliberate IP divergence preserved:** IP omits the `!section.isEmpty()` guard vanilla adds
  (`:58`) and walks an explicit `chunkY/Z` box rather than an `AABB`. IP semantics — ports verbatim.

---

## 4. A–H resolution table — the 8 translation-gap categories the probe surfaced on U3

The U3 helpers landed with **most** translations already applied (the original `world-loader-root.md`
GONE/CHANGED rows). The `-Pip_scc_closed=true` probe on the landed helpers surfaced **eight**
remaining translation-gap categories. Each fix is (a) a mechanical 26.2 translation re-derived from
`mc262-ref`, (b) a build-engineering AW growth, or (c) the one forced type-replacement. **No IP logic
was simplified.** Five categories are RECURRING (they hit many later files — §6); three are one-off.

| # | Gap (IP construct, GONE/CHANGED in 26.2) | Site(s) | 26.2 fix (source-verified) | Fix type | API-map amendment |
|---|---|---|---|---|---|
| **A** | `ResourceKey.location()` — member removed | `McHelper` :771/883/975-988 (dimensionTypeId/getServerWorld/getDimensionName), `CHelper` :157 | `ResourceKey.identifier()` — `ResourceKey.java:64` (field `identifier` :16; **no `location()`**) → `Identifier` | (a) rename · **RECURRING** | new CHANGED row [S5] + header RECURRING note |
| **B** | `ChunkPos.x` / `.z` field access | `McHelper.isServerChunkFullyLoaded` :512 | `.x()` / `.z()` accessors — `ChunkPos` is `record ChunkPos(int x, int z)` `ChunkPos.java:19` (components private final) | (a) accessor · **RECURRING** | new CHANGED row [S5] |
| **C** | `Level.isClientSide` field read | `IPMcHelper.withSwitchedContext` :182, `ScaleUtils.doScalingForEntity` :155/166 | `isClientSide()` method — backing field now `private final` (`Level.java:127`), method `Level.java:163` | (a) field→method · **RECURRING** | SAME §5.8 row annotated [S5] |
| **D** | `Entity.getServer()` / `ServerPlayer.getServer()` — removed | `McHelper.getPlayerLoadDistance` :251-252, `invokeCommandAs` :459/462 | `level().getServer()` — `Level.getServer()` `Level.java:168` (@Nullable; `ServerLevel` overrides non-null); vanilla idiom `Entity.java:3625`. ServerPlayer path `player.level().getServer()`; generic-Entity path `((ServerLevel) commandSender.level()).getServer()` | (a) reroute · **RECURRING** | new GONE row [S5] |
| **E** | `net.minecraft.util.Tuple<A,B>` — type removed repo-wide | `IPMcHelper.rayTracePortals`/`rayTrace` :122-274 | `com.mojang.datafixers.util.Pair` (DFU 10.0.21, always on classpath); `new Tuple<>(a,b)`→`Pair.of(a,b)`, `.getA()/.getB()`→`.getFirst()/.getSecond()` | **(c) forced type-replacement** · **RECURRING** | new GONE row [S5] (§5) |
| **F** | `new ChunkPos(BlockPos)` ctor — removed | `McHelper.getDoesRegionFileExist` :428 | inline `new ChunkPos(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))` — byte-identical to `ChunkPos.containing` :45; `SectionPos.java:81`; `SectionPos` already imported | (a) inline ctor · one-off | new GONE row [S5] |
| **G** | `ChunkMap.TrackedEntity.broadcastAndSend(Packet)` — renamed + generic tightened | `McHelper.sendToTrackers` :458 (`packet` is `Packet<?>`) | `sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener>)` `ChunkMap.java:1352`; the tightened generic **forces** an explicit `(Packet<? super ClientGamePacketListener>) packet` cast + import of `net.minecraft.network.protocol.game.ClientGamePacketListener` (IP's `Packet<?>` cannot pass directly). Unchecked cast is safe by IP's contract (all sends are game packets) | (a) rename + forced cast · one-off | maps to pre-existing §2 CHANGED row ("note the tightened packet generic") — cast is the S5-applied part |
| **H** | `MinecraftServer.storageSource` — `protected final`, cross-package read | `McHelper.getDoesRegionFileExist` :430 (`MiscHelper.getServer().storageSource`) | grow the mod access-widener: `accessible field net/minecraft/server/MinecraftServer storageSource L…/LevelStorageSource$LevelStorageAccess;` — faithful port of IP's own `imm_ptl.accesswidener` entry | **(b) build-engineering AW growth** · one-off | maps to pre-existing §5.7 SAME row; the AW file is the amendment (§5) |

**Amendment accounting:** six categories (A–F) earned new/annotated api-map rows (the S5 amendment
block: +3 GONE = D,E,F; +2 CHANGED = A,B; +1 SAME-annotation = C; +1 header RECURRING note = A).
Two categories (G,H) map to pre-existing rows — G was applied inline against §2, H is realized as an
AW growth against §5.7. All eight are governed 26.2 translations; none is a simplification of IP logic.

---

## 5. Forced type-replacement (E) + AW growth (H) — the two non-rename fixes

**(E) `Tuple` → `Pair` (the one forced type-replacement in this slice).** 26.2 removed
`net.minecraft.util.Tuple` entirely (repo-wide grep of `mc262-ref`: zero matches, no `Tuple.java`).
The least-deviation faithful substitute is `com.mojang.datafixers.util.Pair`: an immutable 2-tuple →
immutable 2-tuple, zero behavioral change, and DFU is always on the classpath (already imported by
held `q_misc_util/Helper.java` and `my_util/DQuaternion.java`). Mechanical rewrite across all of
`IPMcHelper.rayTracePortals`/`rayTrace`: `import net.minecraft.util.Tuple` →
`import com.mojang.datafixers.util.Pair`; `List<Tuple<Portal,Vec3>>` → `List<Pair<Portal,Vec3>>`;
`Tuple<BlockHitResult,List<Portal>>` → `Pair<…>`; `new Tuple<>(a,b)` → `Pair.of(a,b)`;
`.getB()` → `.getSecond()`, `.getA()` → `.getFirst()`. This is the type-replacement decision (c) in
the stage discipline — recorded so later stages that hit `Tuple` reuse the same substitution.

**(H) `storageSource` AW growth (build engineering, not code).** `McHelper.getDoesRegionFileExist`
reads `MiscHelper.getServer().storageSource` verbatim. 26.2 keeps the field but as
`protected final LevelStorageSource.LevelStorageAccess` (`MinecraftServer.java:217`), unreachable
from `qouteall.imm_ptl.core`. IP solved this on 1.21.3 with a `transitive-accessible field` entry in
its own `imm_ptl.accesswidener`; the faithful port grows this mod's
`common/src/main/resources/seamlessportals.accesswidener` with the exact same widen
(plain non-transitive `accessible`, matching the mod's convention; read-only, so no `mutable`). This
keeps `McHelper` byte-verbatim — the access is restored by build config, not by editing IP code. It
sits alongside the S4-landed `ChunkMap$TrackedEntity` and `ServerPlayer.server` growths, all three
the same "restore an access IP had for free" pattern.

---

## 6. RECURRING-RENAMES call-out (forward guidance for later stages)

Five of the eight S5 categories are **not** one-offs — they are 26.2-wide renames that will recur in
many later files. Later stages should treat these as known mechanical substitutions (re-derive once
here, apply on sight thereafter), not as fresh investigations:

| Recurring rename | Trigger to watch for | Substitution |
|---|---|---|
| `ResourceKey.location()` → `.identifier()` | any dim/registry-key id read | member rename; returns `Identifier` (`ResourceKey.java:64`) |
| `ChunkPos` field `.x`/`.z` → `.x()`/`.z()` | field-style access outside the record | record accessors (`ChunkPos.java:19`) |
| `Level.isClientSide` field → `isClientSide()` | field-style `world.isClientSide` | call the method; field is `private final` (`Level.java:127/163`) |
| `Entity/ServerPlayer.getServer()` → `level().getServer()` | any `entity.getServer()`/`player.getServer()` | route via `Level.getServer()` (`Level.java:168`); cast to `ServerLevel` if a non-null server is required |
| `net.minecraft.util.Tuple` → `com.mojang.datafixers.util.Pair` | any `Tuple` import/usage | `Pair.of` / `.getFirst()` / `.getSecond()` (§5) |

Also recurring but already absorbed by the loader facade: `net.minecraft.Util` → `net.minecraft.util.Util`
(package move) and `ResourceLocation` → `Identifier` (class rename) — the two global renames.

---

## 7. Traversal-mixin inventory (held, unregistered until S13)

The `mc_util` traversal group is exactly the **four** files in
`qouteall/imm_ptl/core/mixin/common/mc_util/` (IP dir listing verified). All land held at their final
D2 paths, all byte-identical to IP (§1), none added to any mixin-config JSON.

| File | Target | Role | 26.2 anchor verification |
|---|---|---|---|
| `IELevelEntityGetterAdapter.java` | `LevelEntityGetterAdapter` | `@Accessor` for private `sectionStorage` (`getCache`) + `visibleEntities` (`getIndex`) | both fields present + identically named (`LevelEntityGetterAdapter.java:10-11`) |
| `MixinEntitySection.java` | `EntitySection` | `@IPVanillaCopy ip_traverse` + `@Shadow @Final storage` | §3 re-derivation |
| `MixinEntitySectionStorage.java` | `EntitySectionStorage` | `@IPVanillaCopy ip_traverseSectionInBox` + two `@Shadow @Final` fields | §3 re-derivation |
| `MixinEntity_U.java` | `Entity` | `@Inject` on `setPosRaw(DDD)V` @ `EntityInLevelCallback.onMove()` INVOKE → `ip_onEntityPositionUpdated`; `@Inject` @RETURN on `setRemoved(RemovalReason)` → `ip_onRemoved` | `setPosRaw` final `Entity.java:3786`, INVOKE anchor `levelCallback.onMove()` `:3800`, `levelCallback` typed `EntityInLevelCallback` `:272`; `setRemoved` final `Entity.java:3912` (RETURN inject on final is valid) |

**Scope boundary:** `MixinPersistentEntitySectionManager` is **not** in this slice — it lives in the
`entity_sync/` package (marker duck), belongs to U7/U8, and lands at S9/S10. Only the four `mc_util/`
files are the S5 traversal group.

**Held-duck dependencies (all present from S4):** `IEEntityTrackingSection`, `IESectionedEntityCache`,
`IPVanillaCopy`, and `IPEntityEventListenableEntity` — the last landed held at S4 (round-3 sweep),
so `MixinEntity_U.java:8`'s import resolves **in-probe** (its `ip_onEntityPositionUpdated()` +
`ip_onRemoved(RemovalReason)` signatures match the mixin's two casts). Registration + flag wiring
happens at S13.

---

## 8. Probe-vs-U3-union triage (D4.2)

The `-Pip_scc_closed=true` probe compiles the **entire** held tree (S2 + S4 + S5), so its 291 errors
span all landed-held units, not just U3. Triage decomposes them cleanly:

**(i) U3 slice errors — reduce to the documented forward-ref union ONLY.** Every error whose file is
one of the four U3 helpers maps to a held forward-ref or the loader facade — **zero translation
slips remain:**

| Symbol (unresolved in-probe) | Count on U3 files | Owning stage | Kind |
|---|---|---|---|
| `Portal` (import + members incl. `PortalCollisionHandler` access) | ~48 (41 `class Portal` + 4 pkg + 3 generic + 1 ArrayList-infer) | U4 / **S6** | forward-ref (U3 row) |
| `GlobalPortalStorage` | 3 var + 1 pkg | U5 / **S7** | forward-ref (U3 row) |
| `CrossPortalEntityRenderer` | 1 var + 1 pkg | **S11** | forward-ref (U3 row; A.6 errata) |
| `StableClientTimer` (`CHelper.java:26`) | 2 var + 1 pkg | U4 animation / **S6** | forward-ref (U3-row correction — plan §S5(b)) |
| `ClientWorldLoader` (same-package, no import) | 2 var (`CHelper` :52, `IPMcHelper` :183) | U8 / **S10** | forward-ref (round-2 blind-spot; plan §S5(b)) |
| `EnvType`/`Environment` / `net.fabricmc.api` | 7 pkg | loader facade / **S10** (task #8) | S4-exit loader-facade forward-debt |

`IPVanillaCopy` (S4 carve-in) and `IPEntityEventListenableEntity` (S4 round-3 landing) both resolve
**in-probe**. The four `mc_util/` traversal mixins produce **zero** probe errors (the two `mc_util`
hits in the log are `mc_utils/ServerTaskList` — an S4 held file, note the plural package).

**(ii) Non-U3 errors are the S4(b) ledger, unchanged.** The remaining ~220 errors are on S4-held
files (`O_O`, `IPCGlobal`, `IPPerServerInfo`, `RequiemCompat`, `GravityChangerInterface`,
`SodiumInterface`, `IPPortingLibCompat`, `IPFlywheelCompat`, `IPFeatureControl`, `DimensionIntId`,
`MiscHelper`, `mc_utils/ServerTaskList` …) — `FabricLoader`/`Version`/`ModContainer`/`DimensionAPI`
and their own held forward-refs. These belong to the S4 triage and resolve at their owning stages
(loader seams S10, DimLib stub, S13 closure). They are **not** S5's concern and existed before this
pass.

**Verdict:** the U3 slice reduces to exactly the documented U3 forward-ref union (Portal,
GlobalPortalStorage, CrossPortalEntityRenderer, StableClientTimer, ClientWorldLoader) plus the
loader-facade debt — the S5(b) ledger. Gate satisfied.

---

## 9. Sign-derivation note (D4.4)

No depth / winding / stencil / frustum constants in this slice. The one geometry-bound constant is
the R13d `+1` inclusivity restoration (§2), re-derived from `LevelHeightAccessor.java:11-25`. The
only other "sign-like" values are the fastutil section-key sentinels `SectionPos.asLong(cx,-1,-1)`
and the `+ 1L` `subSet` upper-bound inclusivity (§3), both re-derived directly from
`EntitySectionStorage.java:46-49` (identical to vanilla), not carried on trust. `McHelper.adjustVehicle`
sets position via `setPos` + `setPosAndLastTickPos` (unchanged arithmetic); the removed
`lerpTo(…, 0)` carried no sign — the interpolation-kill becomes `getInterpolation()` null-check +
`cancel()`.

---

## 10. Commit mapping

1. **helpers (held)** — `McHelper`, `CHelper`, `IPMcHelper`, `ScaleUtils` + the A–H fixes + AW growth.
2. **traversal mixins (held)** — the four `mc_util/` files (byte-verbatim).
3. **port-note + api-map amendment** — this file + the `world-loader-root.md` S5 amendment block.

The `-Pip_scc_closed=true` probe gate is evaluated after commits 1+2 (needs both slices). No
`git commit` or `gradlew` is run by this note's author — the orchestrator commits; the user runs
runClient (BASELINE-SANITY, nothing user-visible changes at S5).
