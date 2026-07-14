# S10-B — `ClientWorldLoader` (U8, last core-root) + R1 render-split + IPModMain/Client init cargo + `ImmPtlClientChunkMap` install

**Stage:** S10 sub-stage B (EXECUTION_PLAN §3 S10) · **Assembled:** 2026-07-14
**Governing plan:** `migration/EXECUTION_PLAN.md` §3 S10(a) (ClientWorldLoader + R1 impl +
IPModMain/IPModMainClient init sequences incl. the MiscUtilModEntry/MiscUtilModEntryClient cargo,
Appendix A.9) + S10(b) (probe = the render-context + IPModMain/IPModMainClient init-registration
union) + §1 D2 (verbatim `qouteall.*`) · D4.1 shipping-green · D4.2 probe-ledger triage · D4.3
`git diff --no-index` gate.
**Depends on / builds on:** `port-notes/S10A-loader-seam.md` (loader-facade / `@Environment` /
config-phase / DimLib debt RESOLVED — probe reduced 536 → 206 genuine `qouteall` forward-refs),
`port-notes/S00-seam-inventory.md` (hooks A1–A6, seams B1–B4), `port-notes/S09-chunk-loading.md`
(the S9-held `ImmPtlClientChunkMap` + the S9→S10 deferred `setSectionDirty` routing),
`spikes/SPIKE-R1-sealevel.md` (seaLevel protocol §3 + remote-tick placement §4).
**IP source (1.21.3, Mojang):** `ImmersivePortalsMod/.../qouteall/imm_ptl/core/ClientWorldLoader.java`
(634 LOC). **26.2 ground truth:** `mc262-ref`.
**Render-split transplant source (mod, PROVEN — API_RISKS R1 "the single biggest asset"):**
`common/.../render/PortalContextSwitch.java` (`withSwitchedWorld`, isolated `LevelRenderState`),
`common/.../client/PortalWorldManager.java` (`createRenderer` :561-702, `tickRemoteWorlds` :1368-1422),
`mixin/client/{MinecraftAccessorMixin, LevelRendererAccessorMixin, LevelExtractorAccessor,
ClientLevelExtractorAccessor, GameRendererAccessorMixin}`.
**Primary maps:** `api-map/world-loader-root.md` (ClientLevel/LevelRenderer ctor CHANGED rows, §4
world-switch fact-sheet, §5) · `current-mod-core.md` §5/§11.1 · `current-mod-render.md` §1/§2.2 ·
`chunk-loading.md` #45/#46.
**Memory lessons honoured:** `nether-block-freeze-orphaned-extractor` (EXTRACTOR-IDENTITY — the
load-bearing rule of this sub-stage), `ip-dest-loading-model`, `distant-chunk-vanish-sog-desync`,
`walking-limbo-seed-overclaim`, `portalview-light-engine-half-port`.
**Discipline note:** this port-note CONSOLIDATES the three working fragments' MEASURED gate evidence
(build/probe were run when each fragment landed). This assembly pass did NOT re-run gradle (per the
assembly task constraint); it independently re-verified the IMPLEMENTED REALITY by reading the landed
source (line cites below are from the current tree, which drifts a few lines from the fragments'
IP-relative cites because of the added inline documentation comments).

**Files landed (all HELD until S13, none registered):**

| File | Path | LOC | Diff vs IP |
|---|---|---|---|
| `ClientWorldLoader` | `common/src/main/java/qouteall/imm_ptl/core/ClientWorldLoader.java` | 823 | enumerated §1.1 (renames + R1 render-split + seaLevel; every semantic hunk mapped) |
| `MixinClientLevel` | `common/src/main/java/qouteall/imm_ptl/core/mixin/client/MixinClientLevel.java` | 201 | 9 enumerated hunks §1.2 |
| `IPModMain` | `common/src/main/java/qouteall/imm_ptl/core/IPModMain.java` | 214 | 3 rename hunks §1.3 |
| `IPModMainClient` | `common/src/main/java/qouteall/imm_ptl/core/IPModMainClient.java` | 137 | **NO DIFF** (byte-verbatim) §1.3 |
| `ImmPtlClientChunkMap` (S9-landed) | `common/.../chunk_loading/ImmPtlClientChunkMap.java` | 478 | ONE cross-file edit §4 (`getWorldRenderer`→`getWorldExtractor`, :302) |
| 2 command-callback stubs | `common/src/fabricStubs/net/fabricmc/fabric/api/{command/v2,client/command/v2}/…` | — | new compileOnly shells §5.4 |
| DimLib client-event growth | `common/src/ipStubs/qouteall/dimlib/api/DimensionAPI.java` | — | `CLIENT_DIMENSION_UPDATE_EVENT` added §6.5 |
| AW growth | `common/src/main/resources/seamlessportals.accesswidener` | — | `biomeZoomSeed J` §6.6 |

---

## 1. Diff-gate record (D4.3, per file)

Whole-file diffs are dominated by IP's trailing-whitespace padding of blank lines + the added inline
documentation comments; every SEMANTIC hunk (via `diff -w`, comments stripped) maps to a row below.
No hunk is undocumented.

### 1.1 `ClientWorldLoader.java`

**(a) Global mechanical renames (RECURRING; api-map header + S5):**

| IP | 26.2 | Cite |
|---|---|---|
| `ResourceKey.location()` / `dimension().location()` (×12) | `.identifier()` | world-loader-root §2 [S5] |
| `import …resources.ResourceLocation` (RemoteCallables) → local var | `Identifier` | header rename |
| `GameRenderer.getMainCamera()` / `Camera.getPosition()` | `mainCamera()` / `position()` | CHANGED rows |
| `RegistryAccess.registryOrThrow` (×2) | `lookupOrThrow` | CHANGED row |
| `Registry.getHolderOrThrow(key)` / `Registry.get(id)` (biome check) | `getOrThrow` / `getValue` | CHANGED row |
| `Minecraft.getProfiler().push/pop` | `Profiler.get().push/pop` | GONE row (:505 `push("create_world")`, :658 `pop`) |
| `import net.fabricmc.fabric.api.event.Event` | `com.warwa.seamlessportals.event.Event` | F12 loader-seam (Helper delegates events here; api-map §3 EventFactory row) |

**(b) R1 render-split transplant (re-derived from the mod's proven `PortalContextSwitch` /
`PortalWorldManager.createRenderer` mechanics — see §2):**

| IP (1.21.3) | 26.2 port (landed line) | Source / cite |
|---|---|---|
| `new LevelRenderer(CLIENT, entityRD, blockEntityRD, RenderBuffers)` (4-arg) | 9-arg `new LevelRenderer(entityRD, blockEntityRD, modelMgr, texMgr, atlasMgr, shaderMgr, gameRenderer, rtW, rtH)` (:522) | world-loader-root "LevelRenderer ctor"; mod `createRenderer` :599-609 |
| *(1.21.3: LevelRenderer owns per-dim render state)* | `LevelRenderState worldRenderState = new LevelRenderState()` (:533) + `((LevelRendererAccessorMixin) r).seamlessportals$setLevelRenderState(state)` (:535) | §4.3 (secondaries need own state); mod `createRenderer` :616-617 |
| *(no analog — LevelExtractor is 26.2-only)* | `new LevelExtractor(CLIENT, worldRenderState, worldRenderer)` (:539) — bound once, never rebuilt | §4.1-4.3; mod `createRenderer` :624-625 |
| ctor arg `CLIENT::getProfiler, worldRenderer` | profiler arg dropped; `worldExtractor` passed (:603) | ClientLevel ctor CHANGED; `ClientLevel.java:238-249` |
| `worldRenderer.setLevel(newWorld)` | `worldExtractor.setLevel(newWorld)` (:617) | CHANGED "setLevel moved" |
| `worldRenderer.onResourceManagerReload(rm)` | `worldExtractor.onResourceManagerReload(rm)` (:622) **+ TWO registrations** (below) | CHANGED "onResourceManagerReload moved" |
| *(1.21.3 renderer handled clouds internally)* | `((ReloadableResourceManager) rm).registerReloadListener(worldExtractor)` (:630) **and** `.registerReloadListener(worldRenderer.cloudRenderer())` (:631) | **R1 silent-miss item** — §2.3 + `Minecraft.java:650-651` |
| `tick()`: `WORLD_RENDERER_MAP…forEach(wr -> wr.tick())` | **loop DELETED** (role-transfer comment); `LevelRenderer.tick()` is GONE on 26.2 | SPIKE-R1 §4.3; duty absorbed by `ClientLevel.tick → removeBlockBreakingProgress`, already run by `tickRemoteWorld` |
| `tick()`: `helper.lightmapTexture == CLIENT.gameRenderer.lightTexture()` | `helper.lightmapTexture == ((GameRendererAccessorMixin) CLIENT.gameRenderer).seamlessportals$getLightmap()` (:168) | GONE "LightTexture" row; SPIKE-R1 §4.4 assigns to S10 |
| `disposeWorldRenderer(LevelRenderer)` → `worldRenderer.setLevel(null)` | `disposeWorldRenderer(dim, renderer)` → renderer removed + `WORLD_EXTRACTOR_MAP.remove(dim)` (:298-301) | CHANGED "setLevel moved"; extractor identity |
| `_onWorldRendererReloaded`: `CLIENT.levelRenderer.allChanged()` | `WORLD_EXTRACTOR_MAP.get(dim).allChanged()` inside the retained `withSwitchedWorld` wrapper (:712) | CHANGED "allChanged moved" |
| *(new fields)* | `WORLD_EXTRACTOR_MAP : Map<ResourceKey<Level>, LevelExtractor>` (:86), sibling of `WORLD_RENDERER_MAP` (:75) | §4.1-4.2 |

**(c) R1 seaLevel** — see §3. **(d) `getWorldExtractor` addition** — see §4 (the divergence entry).

**Deliberately NOT transplanted (faithfulness call, category-(c) entry §7.3):** the mod's
`createRenderer` also builds an isolated `RenderBuffers(4)` + `FeatureRenderDispatcher` per secondary.
The api-map ground truth is that **IP SHARES `RenderBuffers` across dims** (GONE "Minecraft.renderBuffers"
row — "shared as long as secondaries receive the same GameRenderer"), and the 9-arg ctor delivers
exactly that by passing `CLIENT.gameRenderer`. Per-frame buffer isolation is the render slice's pooled
swap (`PortalRenderBuffersPool`, S11), not construction. So S10 transplants only the **state**
choreography (`LevelRenderState` + `LevelExtractor`) — the genuinely-required per-dim isolation.

### 1.2 `MixinClientLevel.java` — the `ImmPtlClientChunkMap` install mixin (9 hunks)

| Hunk | IP | 26.2 port | Cite |
|---|---|---|---|
| import | `client.renderer.LevelRenderer` | `client.renderer.extract.LevelExtractor` | world-loader-root §4 / #46 |
| import | *(none)* | **+** `world.level.saveddata.maps.MapId` | world-loader-root §5.2 |
| import | `java.util.function.Supplier` | **removed** (ctor drops the profiler supplier) | ClientLevel-ctor CHANGED |
| shadow field | `@Mutable @Shadow @Final private LevelRenderer levelRenderer;` | `… private LevelExtractor levelExtractor;` (`ClientLevel.java:152`) | world-loader-root §4.2 / #46 |
| `onConstructed` params | `(…, int loadDistance, int j, Supplier supplier, LevelRenderer levelRenderer, boolean bl, long l, CallbackInfo ci)` | `(…, int loadDistance, int j, LevelExtractor levelExtractor, boolean bl, long l, int seaLevel, CallbackInfo ci)` — Supplier removed, LevelRenderer→LevelExtractor, **trailing `int seaLevel` threaded** | #46; `ClientLevel.java:238-249` |
| `getAllMapData`/`addMapData` shadows | `Map<String, MapItemSavedData>` | `Map<MapId, MapItemSavedData>` | world-loader-root §5.2 |
| `ip_getAllMapData`/`ip_addMapData` | `Map<String, …>` | `Map<MapId, …>` (matches the S4 `IEClientWorld` duck) | world-loader-root §5.2 |
| `onToString` | `this_.dimension().location()` | `.identifier()` | S5 rename |
| `ip_resetWorldRendererRef` body | `levelRenderer = null;` | `levelExtractor = null;` (null the renamed back-ref on dimension disposal; callers `ClientWorldLoader.java:214,:253` in IP) | #46 |

**Everything else verbatim.** All `@Inject` target strings survive on 26.2 `ClientLevel`:
`tickNonPassenger` :470, `hasChunk(II)Z` :521, `addEntity` :529, `toString()` :980, `getEntity(int)`
:551, `removeEntity(int,RemovalReason)` :534. Shadowed fields present: `connection` :151,
`chunkSource` :169, `minecraft` :157, `tickingEntities` :149, `blockStatePredictionHandler` :172,
`tickRateManager` :155. The install body `O_O.createMyClientChunkManager(clientWorld, loadDistance)`
is byte-identical to IP (`loadDistance`/serverChunkRadius still the 5th ctor arg).

### 1.3 `IPModMain.java` (3 hunks) / `IPModMainClient.java` (NO DIFF)

`ResourceLocation`→`Identifier` is the only 26.2 rename touching these two files (world-loader-root
header note 1; §2 row flags `IPModMain.registerEntityTypes :162-213` CHANGED):
1. `import net.minecraft.resources.ResourceLocation;` → `…Identifier;`
2. `registerBlocks(BiConsumer<ResourceLocation, PortalPlaceholderBlock> regFunc)` → `<Identifier, …>`
3. `registerEntityTypes(BiConsumer<ResourceLocation, EntityType<?>> regFunc)` → `<Identifier, …>`

`McHelper.newResourceLocation(…)` call sites are UNCHANGED — the ported `McHelper` already returns
`Identifier` (S5), so `regFunc.accept(McHelper.newResourceLocation(...), …)` type-checks against the
renamed `BiConsumer`. `InteractionResult.SUCCESS` (loadConfig save listener, `IPModMain :149`) is SAME
(world-loader-root §5.9) — verbatim. `IPModMainClient` names only SAME vanilla types (`Minecraft`,
`Component`, `ChatFormatting`) → byte-verbatim, NO diff. The `@Environment` decision for
`IPModMainClient` is **VERBATIM = none** (IP left it un-annotated; category-(c) entry §7.5).

---

## 2. R1 render-split plumbing — the `PortalContextSwitch` transplant + EXTRACTOR-IDENTITY

26.2 split extraction off `LevelRenderer` onto a new `LevelExtractor`, and the render architecture went
from IP's 1.21.3 promote/demote to a per-dim real-`ClientLevel` model. IP's `ClientWorldLoader` has no
26.2-shaped code for this. The transplant re-derives the missing choreography from the mod's PROVEN
`PortalContextSwitch` / `PortalWorldManager.createRenderer` — the "single biggest asset" (API_RISKS R1),
battle-proven on 26.2 (SPIKE-R1 §1 drove it live to construct+extract+render an unvisited End secondary).

### 2.1 The construction triple (mod `createRenderer` → IP `createRenderer`)

`createSecondaryClientWorld` builds, PER SECONDARY, the isolated triple (all in `createRenderer`,
`ClientWorldLoader.java:505-658`):
1. **`LevelRenderer`** (9-arg ctor, :522) — sharing `CLIENT.gameRenderer` → shared `RenderBuffers`
   (§1.1(b) faithfulness call).
2. **its own `LevelRenderState`** (:533) installed via `LevelRendererAccessorMixin.seamlessportals$setLevelRenderState`
   (:535) — the per-dim render-state isolation that keeps a secondary's extraction from contaminating
   main render state (SPIKE-R1 §1.4 proved no contamination across ~500 main frames).
3. **`LevelExtractor(CLIENT, worldRenderState, worldRenderer)`** (:539) — **bound once, never rebuilt.**

### 2.2 EXTRACTOR-IDENTITY — the load-bearing rule (memory `nether-block-freeze-orphaned-extractor`)

Every `ClientLevel` is permanently bound to the extractor passed at construction
(`ClientLevel.levelExtractor`, final, `ClientLevel.java:152`). The writer
(`setBlocksDirty`/`sendBlockUpdated` → `level.levelExtractor`) and the reader (that extractor's
`extract`) MUST be the same object for the level's whole life, or block break/place silently stops
remeshing. Honoured three ways:

1. **Construction:** `WORLD_EXTRACTOR_MAP.put(dim, worldExtractor)` (:646) records the ONE extractor
   bound to each secondary; never rebuilt after construction.
2. **Main dim (the identity rule):** `initializeIfNeeded` seeds `WORLD_EXTRACTOR_MAP.put(playerDim,
   CLIENT.levelExtractor)` (:483) — the GLOBAL `Minecraft.levelExtractor` ITSELF (`Minecraft.java:280`,
   the one vanilla bound to `CLIENT.levelRenderer` at `Minecraft.java:649`), **never a per-dim copy for
   the active dim.** The paired `WORLD_RENDERER_MAP.put(playerDim, CLIENT.levelRenderer)` (:478) keeps
   the renderer map total over the extractor map.
3. **`withSwitchedWorld`:** the swap set is **deliberately NOT extended with `mc.levelExtractor`.** The
   mod's proven `PortalWorldManager.tickRemoteWorlds` swaps neither renderer nor extractor during remote
   tick (each `ClientLevel` routes its own dirties through its construction-bound extractor); IP's
   `mc.levelRenderer` swap (via `IEMinecraftClient`) is retained verbatim for structural fidelity but is
   **inert** on 26.2 (`mc.levelExtractor`, bound once to the ORIGINAL renderer, keeps driving that
   renderer regardless of the field swap; no `extract` runs during a tick).

### 2.3 The two reload listeners (R1 silent-miss closure)

`worldExtractor.onResourceManagerReload(rm)` (:622) is the direct 1.21.3 port (immediate reload so
`extract()` sees non-null sky/resource state). But 26.2 also requires registering for FUTURE
(resource-pack) reloads, mirroring `Minecraft.java:650-651`:
- `registerReloadListener(worldExtractor)` (:630)
- `registerReloadListener(worldRenderer.cloudRenderer())` (:631) — **the silent-miss item.**
  `CloudRenderer extends SimplePreparableReloadListener` with no synchronous `onResourceManagerReload`,
  so it is reachable ONLY via registration; a secondary registering only the extractor would silently
  miss cloud-resource reloads.

**Accepted limitation (category-(c) entry §7.4):** 26.2's `ReloadableResourceManager` exposes
`registerReloadListener` ONLY (private final list, no removal API). So `disposeDimensionDynamically`
cannot unregister a dynamically-removed dim's now-dead (level=null) extractor/cloudRenderer; they keep
receiving future reloads. Inert until S13, no-op for the typical fixed 2-3 dim setup, only unbounded
under dynamic-dim CHURN. NOT fixed here (neither IP nor `PortalWorldManager.createRenderer` registers
these at all — no IP precedent for cleanup; reaching the private list would need a non-IP vanilla
accessor). If dynamic-dim churn ever matters, add that accessor + a matching unregister at cutover.

### 2.4 Remote-tick placement (SPIKE-R1 §4)

`tickRemoteWorld` ports 1:1 — `ClientLevel.tick(()->true)` / `tickEntities` / `pollLightUpdates` /
`animateTick` all survive on 26.2. The `worldRenderer.tick()` loop (IP :119-123) is **DELETED with a
documented role transfer, not replaced**: 26.2 `LevelRenderer` has no `tick()`; its 1.21.3 duty
(expiring stale `BlockDestructionProgress`) now lives on `ClientLevel.tick → removeBlockBreakingProgress`
(`ClientLevel.java:305`), which the ported `tickRemoteWorld` ALREADY runs via `newWorld.tick(()->true)`.
**Pairing reminder** (memory `portalview-light-engine-half-port`): tick-side `pollLightUpdates` is only
HALF the light pipeline; the render-END `runLightUpdates` is the render slice's
`MyRenderHelper.lateUpdateLight` (S11), out of this file — it must stay paired.

---

## 3. R1 seaLevel client consumption (SPIKE-R1 §3 "Consumption (S10)")

The 26.2 `ClientLevel` ctor gained a trailing `int seaLevel` (`ClientLevel.java:248`), FINAL for the
level's whole life — and under the per-dim/promotion model that level BECOMES `mc.level` after a
crossing, so a wrong construction-time value is permanent until relog (SPIKE-R1 §1.2). The client has no
local source for it (it is a per-generator-config value; flat overworld = −63, not 63 — SPIKE-R1 §1.2).

- **Field (:101):** `public static @Nullable ImmutableMap<ResourceKey<Level>, Integer> dimIdToDimSeaLevel;`
  beside `dimIdToDimTypeId`. Assigned WHOLESALE by the S7 `MiscNetworking.DimIdSyncPacket.handle` (the
  third NBT compound, already landed at S7), mirroring the dim-type map exactly.
- **Null on exit (:130):** nulled in lockstep with `dimIdToDimTypeId` in the `IPCGlobal.CLIENT_EXIT_EVENT`
  cleanup listener.
- **Consumption (:582-607):** `createSecondaryClientWorld` passes it as the ctor's trailing arg (:606).
- **Fail-soft (:582-594):** if unsynced (dynamic-dim race / desync — near-impossible: the same packet's
  dim-type map is a hard dependency ~20 lines up) → fall back to `CLIENT.level.getSeaLevel()` +
  rate-limited WARN (`LOG_LIMIT.tryDecrement`); **never throws** (IP's remote-world doctrine). The next
  `DimIdSyncPacket` cannot retro-fix a constructed level (field final); the S10-time repair option, if a
  mismatch is ever seen in the wild, is dispose+recreate while the secondary has zero chunks — NOT in v1.

The value threads through `MixinClientLevel.onConstructed`'s inject descriptor (§1.2, the trailing
`int seaLevel` param) so the install mixin sees the same final value.

---

## 4. `ImmPtlClientChunkMap` install + the S9→S10 reconciliation

### 4.1 The install (IP-faithful, no new code) — `MixinClientLevel.onConstructed`

The install point is IP-faithful (current-mod-core §5): the `ClientLevel` CONSTRUCTOR mixin
`onConstructed` (`@Inject method="<init>" at=@At("RETURN")`) swaps the `@Mutable @Shadow @Final
chunkSource` to `O_O.createMyClientChunkManager(world, loadDistance)` → `new ImmPtlClientChunkMap(world,
loadDistance)`. This covers **EVERY client world including the vanilla main one** (NOT a secondary
factory — IP's `createSecondaryClientWorld` has zero chunk-map code). Wiring the install into the
secondary factory alone would leave the MAIN world on a bounded vanilla `ClientChunkCache`, breaking
cross-dim retention on the active dim.

### 4.2 `onLightUpdate` per-dim `LevelExtractor` routing (the S9-deferred edit, now resolved)

**Forcing fact:** the S9-held `ImmPtlClientChunkMap.onLightUpdate` calls
`ClientWorldLoader.get…(dim).setSectionDirty(x,y,z)`, and 26.2 moved `setSectionDirty` `LevelRenderer`
→ `LevelExtractor` (`LevelExtractor.java:467`; chunk-loading #45). S9 deferred this routing to S10
(S9 note §4.2). **The one cross-file edit this sub-stage makes to a S9 file:**
`ImmPtlClientChunkMap.java:302-303` `getWorldRenderer` → `getWorldExtractor` (probe: file now 0 errors).

**Divergence from the Slice C §3 draft (category-(c) entry §7.1) — the IMPLEMENTED reality:** Slice C
proposed flipping `getWorldRenderer`'s RETURN TYPE to `LevelExtractor` + a new `getLevelRenderer` for the
renderer-needing callers. The landed code **diverges**: it keeps `getWorldRenderer(dim) : LevelRenderer`
**verbatim** (:330) and adds a dedicated `getWorldExtractor(dim) : LevelExtractor` (:374). Rationale:
**2 of 3 callers need the actual `LevelRenderer`** (`withSwitchedWorld` here + `ClientTeleportationManager`
(S8) — both feed `IEMinecraftClient.ip_setWorldRenderer(LevelRenderer)`; Slice C's ripple analysis
missed the S8 caller). Flipping `getWorldRenderer` would force an edit to the S8 file AND mislead the
name; the dedicated accessor keeps `getWorldRenderer` verbatim and edits ONE line (the actual
`setSectionDirty` caller). `getWorldExtractor` carries the same EXTRACTOR-IDENTITY fallback (:374-393):
the ACTIVE dim returns `CLIENT.levelExtractor` ITSELF (after a crossing the newly-active dim's map entry
is the STALE secondary extractor vanilla no longer drives), secondaries return their construction-bound
one. Landed verification: `onLightUpdate` (ImmPtlClientChunkMap :302) calls `getWorldExtractor`;
`getWorldRenderer` still returns `LevelRenderer` and is called by `withSwitchedWorld` (:736).

### 4.3 The store-center per-tick DRIVER — NOT implemented here (R13f carriage; flagged)

Slice C §2 delivered a splice-ready spec for a store-center per-tick driver (`refreshDestScopes` +
`evictUnboundedStores`, re-derived from the mod's `PortalWorldManager.evictUnboundedStores` +
`refreshDestScopes` onto IP entity portals, driving the S9 store methods
`evictAround`/`evictBeyond`/`evictAll`). **It is DELIBERATELY OUT OF SCOPE for S10-B and is NOT in the
landed `ClientWorldLoader`** (grep-confirmed: no `evictUnboundedStores`/`refreshDestScopes` in the file).
Reasons (category-(c) entry §7.2):
- It is **R13f carriage**, NOT R1/render-split (IP's `ClientWorldLoader` has no client-side eviction at
  all — this is a mod-originated payload, current-mod-core §5 + memory `walking-limbo-seed-overclaim` §5).
- It needs a render-slice scope-`range` constant Slice C left as an open `/* … */` marker (the mod used
  `portalRenderDistance*16`) — guessing a render-slice API pre-S11 would be a slip.
- It has **zero probe impact** (the S9 store `evict*` methods compile without a caller).
- Its NECESSITY under IP's server-driven `ImmPtlChunkTracking` forget/`drop` path is UNPROVEN (C8/F20,
  S9 §4.2) — it must be RE-PROVEN at S14 (first real cross-dim loading) / S17 (default flip) and must
  never fight the server's forget path.

**FLAGGED FOR THE ORCHESTRATOR:** land the store-center driver as a **separate R13f slice**, wired into
`ClientWorldLoader.tick()` AFTER the remote-world loop, flag/config-gated, once S11 fixes the render
scope-range constant. Keep the stable `RD+16` eviction bound (the mod deliberately reverted the
IP-graduated per-band radius — churn/`drainChunks` spike, `PortalWorldManager.java:426-433`); do NOT
re-introduce the band jump without IP delay-unload hysteresis; omit the block-era-only speculative
pre-warm merge.

### 4.4 S9→S10 reconciliation status (two client chunk maps coexist until S20)

- **Held-unregistered:** `MixinClientLevel` lands as held source; `seamlessportals-ip-client.mixins.json`
  `client` array stays EMPTY and the config is NOT registered (no `fabric.mod.json`/neoforge reference).
  Fully inert. At S13 the entry `"client.MixinClientLevel"` is added, the config registered, flag-gated
  `entityPortals` (D3).
- **Substrate KEEP invariant:** the LIVE block-era `SeamlessClientChunkMap` + `ClientLevelChunkSourceAccessor`
  + the `PortalWorldManager` install path are UNTOUCHED (retired at S20). Under `entityPortals=false` the
  block-era path is the only one that runs — **ZERO change to live block-era play** (EXCLUSIVITY_LEDGER §3).
- **Two client chunk maps coexist S13→S20** (held IP `ImmPtlClientChunkMap` driver vs live block-era
  `SeamlessClientChunkMap`). **S13/S14 confirm** no path expects BOTH under `entityPortals=true` (the S9
  §4.3 reconciliation flag carries forward). **S14/S17 re-prove** the store-driver necessity (§4.3).

---

## 5. Init-cargo wiring plan (IPModMain / IPModMainClient) — the S0-seam / §4.2-order / S13-handoff map

The two init classes land VERBATIM at their held paths so the init CARGO EXISTS; the actual invocation
wiring (calling them at the right lifecycle points, in §4.2 order, flag-gated) is an S13 registration
concern — DOCUMENTED here, NOT wired now. The internal §4.2 order INSIDE each `init()` is preserved
automatically by the verbatim port (the method body *is* the order). All hosts are inventoried in
`S00-seam-inventory.md`; this section binds the landed cargo to them.

### 5.1 Common/server init — seam A1

- **Host:** `fabric/…/SeamlessPortalsModFabric.onInitialize` (+ NeoForge deliberately unwired — module
  integration is Fabric-only, S10A §2.2). Flag-gated `entityPortals` per D3 (mod code dispatches; IP
  bodies never flag-polluted).
- **Call-site order (§4.2 bullet 1 — MiscUtilModEntry cargo FIRST):**
  1. `ImplRemoteProcedureCall.init()` → `MiscNetworking.init()` → `DimensionIntId.init()` (the
     MiscUtilModEntry cargo — that entrypoint class is NOT ported; seam-absorbed, Appendix A.9).
  2. `IPModMain.init()` (the landed cargo).
- **Internal `IPModMain.init()` order (verbatim):** `loadConfig()` → `ImmPtlNetworking.init` /
  `ImmPtlNetworkConfig.init` / `PacketRedirection.init` → `POST_CLIENT_TICK_EVENT` + `PRE_GAME_RENDER_EVENT`
  handler registration (see §5.5) → shape inits → `ImmPtlChunkTracking.init` → `WorldInfoSender.init` →
  `GlobalPortalStorage.init` → `EntitySync.init` → `ServerTeleportationManager.init` →
  `CollisionHelper.init` → `PortalExtension.init` → `GcMonitor.initCommon` → `ServerPerformanceMonitor.init`
  → `ImmPtlChunkTickets.init` → `IPPortingLibCompat.init` → `BlockManipulationServer.init` → command
  registration → `DebugUtil.init` → `ServerTaskList.init` → `CustomPortalGenManager.init` →
  animation-driver inits.

### 5.2 Client init — seam A2

- **Host:** `fabric/…/SeamlessPortalsClientFabric.onInitializeClient`.
- **Call-site order (§4.2 bullet 1):** `ImplRemoteProcedureCall.initClient()` → `MiscNetworking.initClient()`
  (MiscUtilModEntryClient cargo — seam-absorbed) → `IPModMainClient.init()`.
- **Internal `IPModMainClient.init()` order (verbatim):** `ClientWorldLoader.init` (the U8 root, landed
  this sub-stage) → `ClientTeleportationManager.init` → **RENDER-THREAD block via
  `Minecraft.getInstance().execute(...)`:** `ShaderCodeTransformation.init` / `MyRenderHelper.init` /
  `new RendererUsingStencil()` / `new RendererUsingFrameBuffer()` / `IPCGlobal.renderer = rendererUsingStencil`
  — **this render-thread hand-off is the §4.2 "renderers ON THE RENDER THREAD" constraint, kept 1:1** →
  `DubiousThings.init` → `CrossPortalEntityRenderer.init` → `GLResourceCache.init` →
  `CollisionHelper.initClient` → `PortalRenderInfo.init` → `CloudContext.init` → `SharedBlockMeshBuffers.init`
  → `GcMonitor.initClient` → client-command registration → nvidia/quilt warnings → `StableClientTimer.init`
  → `ClientPortalAnimationManagement.init` → `VisibleSectionDiscovery.init` → `ImmPtlViewArea.init` →
  `IPFlywheelCompat.init` → `GuiPortalRendering._init` → `ImmPtlNetworking.initClient` /
  `ImmPtlNetworkConfig.initClient` → `ForceMainThreadRebuild.init` → `CLIENT_CLEANUP_EVENT` register →
  `DimensionIntId.initClient`.

### 5.3 Login order — seam A3 (the DimIdSyncPacket-before-difficulty constraint)

Not an `init()` call but a §4.2 invariant the init landscape must preserve:
- `DimIdSyncPacket` sent mid-`PlayerList.placeNewPlayer`, **BEFORE** `ClientboundChangeDifficultyPacket`
  (26.2 anchor `PlayerList.java:185`) — host = ported `q_misc_util/mixin/dimension/MixinPlayerList_Misc`
  (**lands S7**, registered S13). This carries the S7 per-dim seaLevel to the client BEFORE
  `createSecondaryClientWorld` needs it (§3; R1).
- Global-portal sync at `onPlayerLoggedIn` **AFTER** the difficulty packet — host = ported
  `imm_ptl/core/mixin/common/other_sync/MixinPlayerList` (**one of S10's 74 common mixins**, registered
  S13).
- The init order (5.1) + these two mixins preserve "dim-id sync before any redirected packet or
  global-portal sync" (DEPENDENCY_ORDER §4.2 bullet 2). Both are 1:1 ports — no seam edit; the ORDER is
  the constraint.

### 5.4 Registration cargo — seams B3 (entity types / blocks) + B4 (entity renderers)

`IPModMain.registerBlocks` / `registerEntityTypes` do NOT run inside `init()`; they are `BiConsumer`-shaped
cargo consumed by a seam:
- **B3** — `PlatformHelper.registerEntityTypes(IPModMain::registerEntityTypes)`, **UNCONDITIONAL in both
  flag states** (D3 registries-unconditional rule). Block registration (`registerBlocks`, the
  `PortalPlaceholderBlock`) gets the parallel seam method at S13.
- **B4** — `PlatformHelper.registerEntityRenderer(...)`, carrying IP's UNPORTED
  `IPModEntryClient.initPortalRenderers` cargo (Appendix A.9): `PortalEntityRenderer` for the 9-type
  Portal family + `LoadingIndicatorRenderer` for `LoadingIndicatorEntity`. Client dist only, wired
  **S13 step 5** — without it rung 1 renders no portal.

### 5.5 Tick / frame handler firing — seams A4 (client tick) + A5 (frame anchor)

`init()` REGISTERS handlers into IP's mod-seam events; the per-tick/per-frame FIRING is A4/A5:
- `IPModMain.init` registers `IPGlobal.POST_CLIENT_TICK_EVENT` (→ `CLIENT_TASK_LIST::processTasks`) and
  `IPGlobal.PRE_GAME_RENDER_EVENT` (→ `PRE_GAME_RENDER_TASK_LIST::processTasks`). The mod-owned hosts fire
  `…invoker()` — POST_CLIENT_TICK at the A4 END_CLIENT_TICK host, PRE_GAME_RENDER at the A5 S3
  frame-anchor host (S3-soaked).
- `IPModMainClient.init` calls `StableClientTimer.init` + `ClientPortalAnimationManagement.init`. The
  ordered A4 firing (`ClientWorldLoader.tick()` → `StableClientTimer` → `ClientPortalAnimationManagement.tick()`
  → `manageTeleportation(true)` → POST_CLIENT_TICK) is carried by the ported `MixinMinecraft` (S12) + the
  mod END_CLIENT_TICK host, flag-dispatched at S13. The frame chain (`RenderStates.updatePreRenderInfo`
  → `ClientPortalAnimationManagement.update()` → `manageTeleportation(false)` → PRE_GAME_RENDER) is the
  A5 anchor.

### 5.6 The two new compileOnly command-callback stubs (probe hygiene)

Landing the two init classes introduced two Fabric-API command types the held tree did not previously
reference: `net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback` (IPModMain.init) and
`net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback` (IPModMainClient.init). To
preserve the S10A invariant (`net.fabricmc` probe mentions = 0), both were stubbed in the SAME
`:common`-only `fabricStubs` source set (S10A §6): faithful shells (`EVENT` field typed `Event<T>` +
`@FunctionalInterface` SAM). `:common` compile classpath ONLY (no `ipStubsClasspath`) → zero shadow
risk at the S13 fabric-loader compile where the REAL fabric-command-api-v2 types resolve. No
build.gradle change (the `fabricStubs` srcDir already picks up new `.java`). These resolve the TYPE
references only; the ACTUAL per-loader command + argument-type registration wiring is an S13 seam
concern (world-loader-root §3 rows 98-99 — Fabric `ArgumentTypeRegistry` vs NeoForge). Removed at S20.

### 5.7 S13 handoff checklist (init)

- **S13 step 4 (init wiring):** add the A1/A2 call blocks in the §4.2 order (5.1/5.2), flag-gated
  `entityPortals` (MiscUtilModEntry/Client cargo FIRST; render construction stays on the render thread).
- **S13 step 5 (registration):** wire B3 (`IPModMain::registerEntityTypes`, unconditional) + the block
  seam + B4 (`PortalEntityRenderer` + `LoadingIndicatorRenderer`).
- **S13 (commands):** decide the per-loader command + argument-type registration behind the two stubs.
- **S13 DimLib (CRITICAL, carried from S10A §5/§8):** move `qouteall.dimlib.api.DimensionAPI` from the
  compileOnly ipStubs set to SHIPPED in-tree source (real never-firing impl) — it is called at RUNTIME by
  the init() sequences and has no real provider on 26.2. Now also carries the new
  `CLIENT_DIMENSION_UPDATE_EVENT` (§6.5).
- **S13 config-phase (OPEN, carried from S10A §4/§7):** `addTask`/`completeTask` do NOT auto-resolve on
  the fabric loader (minimal loom, no interface injection). Decide then (loom interface injection / a
  duck+`@Invoker` accessor / leave config-phase unwired). Independent of this sub-stage.

---

## 6. Category-(c) resolutions (the non-mechanical decisions requiring written justification)

Beyond the mechanical renames (§1) and the documented render-split translations (§2), these are the
design decisions / divergences / enabler growths that a D4.3 review would flag for justification:

1. **`getWorldRenderer` kept verbatim + dedicated `getWorldExtractor` added** (not the Slice C §3
   return-type flip). Justification + landed verification: §4.2. Implemented reality wins; the two
   S10-B fragments' §3 are reconciled to THIS.
2. **Store-center per-tick driver NOT implemented** (R13f carriage; out of S10-B scope; flagged for a
   separate slice). Justification: §4.3.
3. **`RenderBuffers` NOT per-secondary-isolated** (shared via the 9-arg ctor's `CLIENT.gameRenderer`, per
   the api-map GONE-row ground truth). The mod's per-secondary `RenderBuffers(4)` +
   `FeatureRenderDispatcher` were deliberately NOT transplanted; per-frame isolation is the S11 pooled
   swap. Justification: §1.1(b).
4. **No unregister for the two reload listeners** (accepted; no IP precedent, no 26.2 removal API).
   Justification + the cutover-time repair recipe: §2.3.
5. **`IPModMainClient` gets NO `@Environment`** (VERBATIM = none — IP left it un-annotated; the inverse
   of "keep verbatim" per S10A §2.1). Safe: `:common` compiles against the merged client-inclusive MC
   jar, and at runtime the class is classloaded ONLY from the client entrypoint seam (A2) → no dist
   hazard. Recorded so a later reviewer does not "add the missing `@Environment`" as a fix. `IPModMain`
   likewise stays un-annotated (common/server init, runs on both dists — correct).
6. **DimLib client-event growth:** `ClientWorldLoader.init` is the FIRST consumer of the client-side
   dimension event, so `common/src/ipStubs/…/DimensionAPI.java` grew `CLIENT_DIMENSION_UPDATE_EVENT` +
   the `ClientDimensionUpdateEvent` holder + `ClientDimensionsUpdateCallback` SAM (the `SERVER_*` events
   were grown by earlier stages; same never-firing F11 pattern). Moves to SHIPPED source at S13 (§5.7).
7. **AW growth:** `accessible field …biome/BiomeManager biomeZoomSeed J` added to
   `seamlessportals.accesswidener`. IP reads `CLIENT.level.getBiomeManager().biomeZoomSeed` verbatim
   (:605; `private final long`, no getter); IP's own `imm_ptl.accesswidener` widens the exact field. Uses
   plain `accessible` per the mod's convention (read-only). **Probe note:** the probe's lone
   `biomeZoomSeed private access` line is Loom transform-CACHE staleness (byte-identical form to the
   working `storageSource`/`ServerPlayer.server` AW entries that DO resolve → the AW mechanism is applied
   to the `:common` MC jar; the widened jar was transformed BEFORE this AW edit, and plain `compileJava`
   does not re-run Loom's minecraft-transform). Clears on the user's next dependency-refresh / clean
   build (the plan's between-stage workflow). Entry verified correct against `mc262-ref` + IP's AW —
   NOT a defect.

---

## 7. Gate evidence (consolidated from the fragments; source-read re-verified this assembly)

The build/probe rows below are the MEASURED results captured when each fragment landed (this assembly
pass did not re-run gradle). The "source-read confirmation" column is this pass's independent read of the
current landed tree.

| Gate | Command | Result | Source-read confirmation (this pass) |
|---|---|---|---|
| **Shipping build (D4.1)** | `:common:compileJava :fabric:compileJava` (`ip_scc_closed=false`) | **BUILD SUCCESSFUL** — all landed files held under `qouteall/**` (+ the S9 `ImmPtlClientChunkMap` edit is inside a held file); the two new `fabricStubs` compile clean; AW/stub growths harmless (no shipping code references them) | All 5 held files present + held-path patterns match; stubs/AW are compileOnly/resource growths |
| **Compile probe (D4.2)** | `:common:compileJava -Pip_scc_closed=true` | Reduces to **exactly the documented U8 union** (below); ZERO translation slips; `net.fabricmc`/`command.v2` mentions = 0; `ImmPtlClientChunkMap` = 0 errors | `getWorldExtractor` present + routed; `dimIdToDimSeaLevel` field + consumption present; 9-arg `LevelRenderer` + `LevelExtractor` + `LevelRenderState` isolation present; no `evictUnboundedStores` (store-driver correctly absent) |
| **Diff gate (D4.3)** | `git diff --no-index` vs IP | `ClientWorldLoader` = §1.1 hunks only; `MixinClientLevel` = §1.2 (9 hunks); `IPModMain` = 3 rename hunks; `IPModMainClient` = **NO DIFF** | Rename/render-split/seaLevel hunks all map to §1 rows |

**The documented U8 union the probe reduces to (S10(b) ledger — all EXPECTED forward-refs, NOT slips):**
- `ClientWorldLoader` → `DimensionRenderHelper` + `PortalRendering` (U9/S11) and `IEClientLevelData` +
  `IEClientLevel_Accessor` (U10/S12). Every other symbol resolves in-probe — the render-split (9-arg
  `LevelRenderer`, `LevelExtractor` ctor, isolated `LevelRenderState`, `cloudRenderer()`,
  `registerReloadListener`, `Profiler.get`, `setSectionDirty`, the mod accessors, the ClientLevel 10-arg
  ctor incl. `seaLevel`) AND all client ducks (`IEClientWorld`/`IEWorld`/`IEMinecraftClient`/
  `IEParticleManager`/`IEClientPlayNetworkHandler`/`IECamera`/`IEWorldRenderer`) confirmed by their
  ABSENCE from the error list.
- `MixinClientLevel` → **exactly 3 `ClientWorldLoader` refs** (import + `getIsInitialized()` +
  `getClientWorlds()`), all resolved now that the U8 root landed.
- `IPModMain` (24 errors) / `IPModMainClient` (31 errors) → the documented init-registration debt:
  autoconfig (S13); U11 (`BlockManipulationServer`, `PortalCommand`, arg types, `DebugUtil`,
  `CustomPortalGenManager`, `ClientDebugCommand`, `GcMonitor`, `DubiousThings`); U12
  (`GeneralBreakablePortal`, `NetherPortalEntity`); U9/S11 (`CrossPortalEntityRenderer`,
  `ForceMainThreadRebuild`, `GuiPortalRendering`, `ImmPtlViewArea`, `MyRenderHelper`,
  `ShaderCodeTransformation`, `VisibleSectionDiscovery`, `CloudContext`, `GLResourceCache`,
  `SharedBlockMeshBuffers`); U10/S12 (`RendererUsingFrameBuffer`, `RendererUsingStencil`).
- Sole non-forward-ref line = `biomeZoomSeed` (Loom cache staleness — §6.7, NOT a defect).

**Forward-ref handoff to S11/S12:** the S11 `DimensionRenderHelper` port must expose `public final Level
world`, `public final Lightmap lightmapTexture` (retyped from IP's `LightTexture`), `tick()`, `cleanUp()`,
and ctor `(Level)` to satisfy this file (SCC cycle 7). S12 lands
`IEClientLevelData`/`IEClientLevel_Accessor` + the client render-duck registrations.

---

## 8. VERIFICATION TIER

**Tier: Opus (default), NOT Fable.** Per the migration model-tier policy (memory
`migration-model-tier-policy`), Fable adversarial-verify is escalated ONLY for the hard stages **S6, S8,
S11, S12, S13**. **S10 is not on that list** — its render risk lives in S11 (the U9 render context /
CUTOVER_SPEC) where the R1 spike evidence is actually consumed at runtime, and S10-B's changes are all
HELD/inert until S13. So S10-B was ported + self-verified on Opus 4.8, with safety coming from the
workflow gates (probe-ledger triage, diff gate, source-read confirmation), not the tier.

**QUEUED RE-VERIFY (Opus-fallback protocol):** because this ran on Opus rather than Fable, an adversarial
Fable re-verify is QUEUED for the S11 boundary, where it can be folded into the mandated S11 (Fable)
design pass at near-zero marginal cost. The re-verify should adversarially re-examine, against
`mc262-ref` + the mod's `PortalContextSwitch`:
1. The **EXTRACTOR-IDENTITY** seeding — that `WORLD_EXTRACTOR_MAP.put(playerDim, CLIENT.levelExtractor)`
   (:483) uses the GLOBAL `Minecraft.levelExtractor` ITSELF and never a per-dim copy for the active dim,
   and that `getWorldExtractor`'s active-dim branch returns the same (the `nether-block-freeze` regression
   surface).
2. The **two reload-listener registrations** (:630-631) against `Minecraft.java:650-651` — that the
   `cloudRenderer()` registration is present and the silent-miss is closed.
3. The **seaLevel fail-soft** (:582-594) — that it never throws and the fallback matches SPIKE-R1 §3.
4. The **store-driver deferral** (§4.3) — confirm S10-B correctly omits it and the R13f slice is tracked.

These are the runtime-risk surfaces; they cannot be exercised until the S13 flag-ON bring-up (rung 1
same-dim at S13, cross-dim at S14), so the queued re-verify is a paper adversarial pass, and the LIVE
proof is the S13→S14 bring-up ladder scripts.

---

## 9. Handoffs (summary)

- **S11 (U9 render context):** provide `DimensionRenderHelper` surface (§7 handoff); consume the SPIKE-R1
  §1.5 reversed-Z rule (portal cull frusta from a conventional-Z culling projection, never
  `cameraRenderState.projectionMatrix`); resolve the render scope-`range` constant the store-driver needs.
- **R13f slice (orchestrator):** land the store-center per-tick driver (§4.3) as its own flag/config-gated
  slice wired into `ClientWorldLoader.tick()` after the remote-world loop.
- **S12 (U10 renderers + client mixins):** land `IEClientLevelData`/`IEClientLevel_Accessor` + the
  `RendererUsing*` family; the render-thread init block (§5.2) constructs them.
- **S13:** init wiring (step 4) + registration (step 5) + move DimLib to shipped source + config-phase
  decision + register `MixinClientLevel` + `MixinPlayerList` + the other 74 common mixins, all flag-gated
  `entityPortals`; enforce the EXCLUSIVITY_LEDGER before the first flag-ON run.
- **S14/S17:** re-prove the store-driver necessity; confirm no path expects both client chunk maps under
  `entityPortals=true`.
- **S20:** delete the two command-callback fabricStubs + the DimLib ipStub + the AW growth with the rest
  of the holding machinery.
