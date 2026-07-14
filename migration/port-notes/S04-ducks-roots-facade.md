# S04-ducks-roots-facade — Stage S4 port-note (U2: ducks, roots, platform facade)

**Stage:** S4 (Ducks, roots, platform facade — U2) · **Assembled:** 2026-07-14
**Governing plan:** `migration/EXECUTION_PLAN.md` §3 S4 (a)/(b)/(c) + §1 D1/D2/D3/D4 + §4 register
F11/F12/F21 + Appendix A.6 errata.
**Discipline:** D2 (verbatim `qouteall.*` at final paths), D4.3 (per-file `git diff --no-index`
gate), D1 (compile-task held-paths filter; the `-Pip_scc_closed=true` probe is the authoritative
forward-ref ledger).
**API ground truth:** `migration/api-map/ducks-api-misc.md`, `world-loader-root.md`,
`platform-compat-peripheral.md`, `q-misc-util.md`; 26.2 decompile `C:/Users/warwa/ModDev/mc262-ref`.
**IP source:** `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall` (1.21.3, Mojang).

**How this note was assembled (plan S4(c) commit 3).** It merges the three translator fragments —
`fragments/S04-ducks.md` (Slice A: 36 ducks + q_misc_util ducks/misc-mixin + mc_utils trio +
`@IPVanillaCopy`), `fragments/S04-roots.md` (Slice B: roots/facade/RequiemCompat/DimLib),
`fragments/S04-compat.md` (Slice C: compat invoker bases + `sodium_compatibility` trio + flywheel)
— with the **build agent's carve-in and F21 evidence** now materialized in the repository:
`buildSrc/.../IpHeldPaths.groovy` (the monotone MAIN_HELD_PATHS carve-in list), the `ipStubs`
`compileOnly` source set (`common/build.gradle` + `common/src/ipStubs/**`, 14 stub shells), and the
grown `seamlessportals.accesswidener` + `META-INF/accesstransformer.cfg` (the IEChunkMap widen — §2c —
plus the RequiemCompat `ServerPlayer.server` widen added this pass — §4.2/§8.2-5).
The fragments are deleted after this assembly (task instruction). Where a fragment claim was
load-bearing it was re-verified against the landed file (RequiemCompat, O_O diffs re-run this pass).

---

## 0. File census — what landed at S4 (61 IP files + 14 F21 stub shells)

All 61 `qouteall.*` files land VERBATIM at final paths (D2). **HELD-vs-carve-in is the build
agent's call, executed in `IpHeldPaths.MAIN_HELD_PATHS`; translators only land files.** The 14 F21
stub shells are third-party build engineering (not IP source, not diff-gated), in `common/src/ipStubs`.

| Slice | Files | Carve-in count | Held count |
|---|---|---|---|
| A — ducks | 36 `imm_ptl/core/ducks/*` | **29** (see §2) | 7 |
| A — q_misc_util ducks/misc-mixin | `q_misc_util/ducks/IEMinecraftServer_Misc`, `q_misc_util/mixin/IELevelStorageAccess_Misc` | 2 | 0 |
| A — mc_utils trio | `IPEntityEventListenableEntity`, `MyNbtTextFormatter`, `ServerTaskList` | 0 | 3 |
| A — annotation | `imm_ptl/core/miscellaneous/IPVanillaCopy` | 1 | 0 |
| B — roots/facade | `IPGlobal`, `IPCGlobal`, `MiscHelper`, `IPConfig`, `O_O`, `RequiemCompat`, `IPPerServerInfo`, `DimensionIntId`, `DimensionIdRecord`, `IPFeatureControl`, `IPMixinPlugin` | 0 | 11 |
| C — compat | `GravityChangerInterface`, `IPFlywheelCompat`, `IPPortingLibCompat`, `iris_compatibility/IrisInterface`, `sodium_compatibility/{SodiumInterface, SodiumRenderingContext, IESodiumRenderSectionManager}`, `compat/mixin/sodium/IESodiumWorldRenderer` | 0 | 8 |
| **F21 stubs** | `net/caffeinemc/**` (8), `net/irisshaders/**` (4), `gravity_changer/**` (2) | — (compileOnly artifact) | — |

Net: **32 carved in** (29 ducks + 2 q_misc_util ducks/mixin + `IPVanillaCopy`), **29 held** IP files.

---

## 1. Diff-gate record (D4.3) — NEEDS-REVIEW first, then per-file hunk table

### 1.0 NEEDS-REVIEW (consolidated — resolve before S13 closure)

These are NOT translation slips; they are seam/stub/design GAPS the S0 inventory did not cover, or
places where the plan text is superseded by the empirical build result. Ordered by load-bearing weight.

1. **Duck carve-in is 29, not the plan's 33 (§2).** Four vanilla-only ducks reference GONE/incompatible
   26.2 types no access widener can fix (`IEWorldRenderer`→`MultiBufferSource`, `IEShader`→
   `blaze3d.shaders.Uniform`, `IEGameRenderer`→`LightTexture`, `IEDistanceManager`→non-generic
   `Ticket`). They are HELD; redesign is a render/command slice (S11/S12/S13), not an S4 translation.
   The build agent encoded 29 carve-ins in `IpHeldPaths.MAIN_HELD_PATHS` — this note ratifies it as
   an amendment to plan S4 / DEPENDENCY_ORDER §2.9 (Appendix A.6 erratum iii extended).
2. **NR-1 — loader-facade Fabric imports have NO S0 seam (Slice B/C).** `FabricLoader`/`ModContainer`/
   `Version`/`SemanticVersionImpl`/`net.fabricmc.api.{EnvType,Environment}` (both the `@Environment`
   **annotation** — Slice B `IPCGlobal`/`DimensionIntId`/`RequiemCompat` AND Slice C
   `GravityChangerInterface`/`SodiumInterface`/`IPFlywheelCompat`/`IPPortingLibCompat` — and the
   `EnvType.CLIENT` **value-use** in `IPPortingLibCompat.init`)/`ClientChunkEvents`/`ClientTickEvents`
   are absent from `:common`'s classpath and have no seam (the only S0 seam is `event.Event`, B1).
   **All landed VERBATIM held** (the `@Environment` annotations are KEPT byte-for-byte, uniformly
   across Slice B and Slice C — an earlier pass mis-stripped the Slice C copies citing "F6"; that
   was an unauthorized deviation, now reverted, see §4.3); the `-Pip_scc_closed=true` probe will
   report the Fabric imports unresolved. They do NOT self-resolve by S13 — they need a mod-owned
   loader-info seam + a uniform `@Environment`-drop policy (see §4, §8). `platform-compat-peripheral.md
   §4`: O_O is "the facade to re-implement per-loader."
3. **NR-2 — F11 DimLib event-wiring stub not yet present (§5).** `DimensionIntId.init()` needs
   `qouteall.dimlib.api.DimensionAPI` (`SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT` + `addPhaseOrdering` +
   `register(phase, listener)`) AND Fabric `Event.DEFAULT_PHASE`. DimLib has no IP-tree source and no
   ported stub. Shape spec + never-firing static-dim decision in §5; the stub is not yet landed.
4. **F21 must be EXTENDED beyond the plan's list (§3).** The plan's F21 names sodium/iris/autoconfig
   ONLY. S4 also requires **iris (4 types) at S4 not just S12** (FLAG A — `IrisInterface` invoker base
   lands here) and **gravity_changer (2 types)** (FLAG B — never mentioned by F21). Both are now
   landed in `ipStubs`; this note ratifies the extension (boundary defense identical — §3).
5. **`ServerTaskList` `ServerTickEvents.END_SERVER_TICK` loader-seam deferred to A6/S10 (§1.1, §8).**
   Common has no fabric-api; the S0 event seam is a `createArrayBacked` factory, not a
   `ServerTickEvents` replacement, and `IPGlobal` defines no post-server-tick event. Landed verbatim.
6. **`MyNbtTextFormatter` `getElementType()` → `element.get(0).getId()` (§7 row 6).** The one
   design-level R12 spot: `ListTag.getElementType()` is GONE and `identifyRawElementType()` is
   package-private. Faithful for the HOMOGENEOUS lists IP feeds it; confirm/replace at S13/commands.
7. **`IEClientWorld` C17 (`MapId` re-typing) applied to a HELD duck (§1.1).** Consistent with the
   S12 `MixinClientWorld` port; re-confirm when that mixin lands.
8. **`IPPortingLibCompat` stencil branch (§3 note, Slice C 2.4 H5).** Minimal field/arity translation
   applied to keep S13 compilable; IP logic left intact. `platform-compat-peripheral.md` says this
   porting_lib path "dies" — keep-translated vs delete-branch is an S12/S20 render-slice call. Dead on
   26.2 regardless (`isModLoaded("porting_lib")` always false).

### 1.1 Per-file hunk table (every non-verbatim file; verbatim files summarized)

Diff command per file: `git diff --no-index --ignore-cr-at-eol -- <IP> <ported>` (IP tree is CRLF;
ported is LF — `--ignore-cr-at-eol` hides ONLY the EOL difference). Justification category legend:
**[T]** mechanical 26.2 API translation with an api-map row · **[S]** S0 loader-seam substitution
(F12/B1) · **[V]** verbatim (0 content hunks).

| File (`qouteall/…`) | Hunks | Cat | Justification (api-map row / seam) |
|---|---|---|---|
| **Ducks (Slice A)** | | | |
| 29 carve-in ducks (§2 list) | 0 | V | all referenced vanilla types SAME; byte-identical to IP |
| `imm_ptl/core/ducks/IEClientWorld` | 3 | T | **C17** — `+import …maps.MapId;` + `Map<String,MapItemSavedData>`→`Map<MapId,…>` ×2 (`ClientLevel.mapData` key type changed, `26.2:ClientLevel.java:160`). HELD (Portal U4). |
| `IEEntity`, `IEMinecraftServer` | 0 | V | HELD (qouteall imports); duck signatures carry no changed vanilla type |
| `IEWorldRenderer`, `IEShader`, `IEGameRenderer`, `IEDistanceManager` | 0 | V | HELD — landed verbatim but reference GONE 26.2 types → NEEDS-REVIEW §2/§1.0-1 |
| **mc_utils + annotation (Slice A)** | | | |
| `mc_utils/IPEntityEventListenableEntity` | 0 | V | zero-import interface; `Entity.RemovalReason` SAME (S29) |
| `mc_utils/ServerTaskList` | 0 | V | verbatim; `ServerTickEvents` import unresolved → seam deferral §1.0-5 |
| `mc_utils/MyNbtTextFormatter` | 12 | T | R12 SnbtPrinterTagVisitor re-derivation — full per-hunk table in §7 |
| `miscellaneous/IPVanillaCopy` | 0 | V | zero-import annotation; carved in (resolves 20 files S4–S12) |
| **q_misc_util ducks/mixin (Slice A)** | | | |
| `q_misc_util/ducks/IEMinecraftServer_Misc` | 0 | V | `LevelStorageSource.LevelStorageAccess` SAME (`26.2:LevelStorageSource.java:456`) |
| `q_misc_util/mixin/IELevelStorageAccess_Misc` | 0 | V | `@Accessor("levelDirectory")`; field + `LevelDirectory` record SAME (`:458`/`:422`) |
| **Roots/facade (Slice B)** | | | |
| `imm_ptl/core/IPGlobal` | 1 | S | **F12/B1** `net.fabricmc.fabric.api.event.Event`→`com.warwa.seamlessportals.event.Event` (types `POST_CLIENT_TICK/PRE_GAME_RENDER/SERVER_CLEANUP_EVENT` assigned from ported-Helper factories) |
| `imm_ptl/core/IPCGlobal` | 1 | S | **F12/B1** identical `Event` substitution (types `CLIENT_CLEANUP/CLIENT_EXIT_EVENT`) |
| `platform_specific/O_O` | 3 | T | `ResourceLocation`→`Identifier` ×2 (global rename; import + `getModIconLocation` return) + **C40** `WorldVersion.getName()`→`.name()` (`:98`). `McHelper.newResourceLocation` at `:226` UNTOUCHED (word-boundary-aware) |
| `q_misc_util/dimension/DimensionIntId` | 2 | T | `ResourceLocation`→`Identifier` (import + `DYNAMIC_UPDATE_EVENT_EARLY_PHASE` field type). Fabric `Event`/`DimensionAPI` deliberately NOT substituted → F11 §5 |
| `q_misc_util/MiscHelper` | 1 | T | `ResourceKey.location()`→`.identifier()` at `:47` (in `serialize(ResourceKey<Level>)`; `q-misc-util.md:11,152`; `ResourceKey.java:64`). HELD; its carved-in `IELevelStorageAccess_Misc` ref resolves in-probe; `@Environment` (`:67`/`:107`) + `net.fabricmc` imports are NR-1 residue (§8.2-1) |
| `IPConfig`, `RequiemCompat`, `IPPerServerInfo`, `DimensionIdRecord`, `IPFeatureControl`, `IPMixinPlugin` | 0 | V | verbatim held; NR-1 Fabric residue where applicable (`RequiemCompat` also carries the `ServerPlayer.server` AW-growth obligation — §4.2/§8.2, not a source hunk) |
| **Compat (Slice C)** | | | |
| `compat/GravityChangerInterface` | 0 | V | verbatim (byte-identical to IP). `net.fabricmc.api.{EnvType,Environment}` imports + 2 `@Environment(EnvType.CLIENT)` annotations KEPT verbatim (NR-1); `gravity_changer.*` imports KEPT (F21 §3) |
| `compat/sodium_compatibility/SodiumInterface` | 0 | V | verbatim. `EnvType`/`Environment` imports + class-level `@Environment(EnvType.CLIENT)` KEPT verbatim (NR-1); six `net.caffeinemc.*` imports KEPT (F21 §3) |
| `compat/IPPortingLibCompat` | 2 | T | **GONE** H5: `renderTarget.resize(viewWidth,viewHeight,ON_OSX)`→`resize(width,height)` (`RenderTarget.java:20-21,36`; `Minecraft.ON_OSX` GONE) + H2 drop `Minecraft` import (now unused, consequential to H5). `EnvType`/`Environment` imports + 2 method `@Environment(EnvType.CLIENT)` annotations + the `EnvType.CLIENT` value-use (`:22`) KEPT verbatim (NR-1). NEEDS-REVIEW §1.0-8 |
| `compat/IPFlywheelCompat` | 0 | V | verbatim. `EnvType`/`Environment` imports + class-level `@Environment(EnvType.CLIENT)` KEPT verbatim (NR-1). No flywheel type imported |
| `compat/iris_compatibility/IrisInterface` | 0 | V | no `net.fabricmc`/`@Environment` in file; `net.irisshaders.*` (3) KEPT (F21 §3) |
| `compat/sodium_compatibility/SodiumRenderingContext` | 0 | V | IP source; `SortedRenderLists` (1) KEPT (F21 §3) |
| `compat/sodium_compatibility/IESodiumRenderSectionManager` | 0 | V | pure IP interface; same-package `SodiumRenderingContext` only |
| `compat/mixin/sodium/IESodiumWorldRenderer` | 0 | V | `@Mixin(SodiumWorldRenderer, remap=false)` accessor; `net.caffeinemc.*` (2) KEPT (F21 §3); mixin imports resolve against `mixin 0.8.5` compileOnly |

**Whole-slice diff-gate totals (recomputed by re-running the D4.3 diff-gate on every file this
pass):** Slice A = 15 content hunks across 2 files (`IEClientWorld` 3, `MyNbtTextFormatter` 12);
40 of 42 files byte-identical. Slice B = **8 content hunks across 5 files** (`IPGlobal` 1 [S],
`IPCGlobal` 1 [S], `O_O` 3, `DimensionIntId` 2, `MiscHelper` 1); **6 of 11 verbatim**. Slice C =
**2 content hunks across 1 file** (`IPPortingLibCompat`: H5 resize + H2 consequential
`Minecraft`-import drop); **7 of 8 verbatim**. **Grand total = 25 content hunks**, every one
category [T] (api-map row) or [S] (F12/B1 seam) — zero unjustified deviations.
*(Correction record: an earlier pass reported Slice B "6 across 4 / 7-of-11" — it undercounted by
one and omitted `MiscHelper`'s legitimate `ResourceKey.location()`→`.identifier()` [T] hunk; and
Slice C "12 across 4 / 4-of-8" counted the now-reverted `@Environment`-strip "F6" hunks
(`GravityChangerInterface` 3, `SodiumInterface` 2, `IPFlywheelCompat` 2, and 3 of
`IPPortingLibCompat`'s 5). With `@Environment` restored verbatim, only `IPPortingLibCompat`'s two
genuine api-map hunks remain in Slice C.)*

**Scope guard (from Slice A):** `mc_utils/WireRenderingHelper.java` is NOT landed here — S4(a) names
only the three mc_utils files above; `WireRenderingHelper` is owned by S11 (U9 render slice, api-map
G4 `renderLineBox` GONE). The task's `mc_utils/**` glob was over-broad; flagged so it is not double-owned.

---

## 2. Duck carve-in reconciliation — 29 of 36 (the plan said 33)

**Result: 29 carve-in-able · 7 held. This SUPERSEDES the plan's "33 carve in / 3 held" (plan S4(a),
line 518/532).** Encoded in `IpHeldPaths.MAIN_HELD_PATHS:89-114`. Two distinct hold reasons.

### 2a. The mandatory import-grep FILTER (D1): exactly 3 ducks import held `qouteall.*` classes

`grep '^import qouteall'` over all 36 ducks → EXACTLY three, confirming the plan's 3-held claim on the
QOUTEALL-import axis:

| Duck | qouteall import(s) | resolves at |
|---|---|---|
| `IEClientWorld` | `imm_ptl.core.portal.Portal` | S6 (U4), held to S13 |
| `IEEntity` | `imm_ptl.core.collision.PortalCollisionHandler` + `imm_ptl.core.portal.Portal` | S6 (U4/U6), held to S13 |
| `IEMinecraftServer` | `imm_ptl.core.IPPerServerInfo` | held U2 (THIS stage, Slice B), held to S13 |

The other 33 import ZERO `qouteall.*` — matching DEPENDENCY_ORDER §2.9 / the plan's count **on the
import axis only**.

### 2b. Why 33 is wrong: "no qouteall import" ≠ "compiles against 26.2 vanilla"

Four of those 33 reference GONE/incompatible 26.2 vanilla types that NO access widener can fix, so
they do NOT compile in the shipping `:common:compileJava` and MUST be held (their redesign is a later
render/command slice, per api-map, NOT a mechanical S4 translation). Landed VERBATIM; held.

| Duck | GONE/incompatible 26.2 type | api-map | owning redesign slice |
|---|---|---|---|
| `IEWorldRenderer` | `net.minecraft.client.renderer.MultiBufferSource` GONE (no file in mc262-ref) — used in `ip_myRenderEntity` sig | G1 (UNKNOWN-NEEDS-DESIGN) | S11/S12 (U9/U10) |
| `IEShader` | `com.mojang.blaze3d.shaders.Uniform` GONE (→ `com.mojang.blaze3d.opengl.Uniform`); the uniform-location-int purpose is itself GONE | G2 (UNKNOWN-NEEDS-DESIGN) | S11/S12 |
| `IEGameRenderer` | `net.minecraft.client.renderer.LightTexture` GONE (→ `Lightmap`); `ip_getDoRenderHand`/`ip_setIsRenderingPanorama` fields GONE too | G3 + G12 | S11/S12 |
| `IEDistanceManager` | 26.2 `Ticket` is NON-generic (`public class Ticket`, `26.2:Ticket.java:10`) → `SortedArraySet<Ticket<?>>` is a compile error; tickets moved to `TicketStorage` | C13 (re-target `TicketStorage`) | S13/commands (U11) or the S10 mixin |

### 2c. The IEChunkMap AW/AT save (why it is carved in, count = 29 not 28)

`IEChunkMap` is vanilla-only AND has NO GONE type, but the build agent found it did **not** initially
compile: 26.2 makes `ChunkMap.TrackedEntity` a **private nested class**, so
`IEChunkMap.ip_getEntityTrackerMap()`'s `Int2ObjectMap<ChunkMap.TrackedEntity>` return type is
inaccessible from `qouteall.*` (the S04-ducks fragment verified the type EXISTS but not that it is
ACCESSIBLE — the compiler caught the difference; D1 "the compiler is the authority"). IP's own
accesswidener widens exactly that type. The **faithful** fix — "grow AW/AT lists as ducks demand"
(plan S4(a)) — was applied: `seamlessportals.accesswidener:21`
`accessible class net/minecraft/server/level/ChunkMap$TrackedEntity` +
`accesstransformer.cfg:20` `public net.minecraft.server.level.ChunkMap$TrackedEntity`. After that,
`IEChunkMap` compiles and is carved in. This is a real S4 build-agent deliverable, not a paper claim.

### 2d. Net disposition

**29 carve-in** (vanilla-only AND 26.2-compilable, `IEChunkMap` included post-AW) · **3 qouteall-held**
(§2a) · **4 GONE-type-held** (§2b) = 36. The 29 carve-in ducks (all 0-hunk verbatim):

`IEAbstractClientPlayer, IECamera, IEChunkHolder, IEChunkMap, IEClientPlayNetworkHandler,
IEClientPlayerInteractionManager, IECustomPayloadPacket, IEEntityTrackerEntry,
IEEntityTrackingSection, IEFrameBuffer, IEFrustum, IEMinecraftClient, IEParticleManager,
IEPlayerEntity, IEPlayerListEntry, IEPlayerMoveC2SPacket, IEPlayerPositionLookS2CPacket,
IERayTraceContext, IERenderSection, IESectionedEntityCache, IEServerChunkCache,
IEServerEntityManager, IEServerPlayNetworkHandler, IEServerPlayerEntity, IEServerWorld,
IESimpleRegistry, ITrackedEntity(IETrackedEntity), IEWorld, IEWorldChunk`.

Sub-note (interfaces compile verbatim; the flagged concern is the MIXIN body's, at its later slice):
`IEMinecraftClient` mixin (S12) must re-target moved homes (`ip_getCurrentScreen`→`Gui.screen()` C3,
`ip_setFrameBuffer`/`ip_setRenderBuffers`→`GameRenderer.mainRenderTarget`/`renderBuffers` C7/G11);
`IEAbstractClientPlayer` is a DEAD duck (G9, collapses into `IEEntity.ip_setWorld`) — deletion is an
S12 mixin call, not an S4 change. All landed verbatim.

---

## 3. F21 Sodium (+iris+gravity) `compileOnly` stub-classpath — types, shapes, wiring, boundary

### 3.1 The problem and the artifact

Neither Sodium nor Iris nor gravity_changer exists for MC 26.2, yet IP's compat files reference their
types **directly, in method bodies** and cannot be dropped (imported by `ImmPtlClientChunkMap` S9,
`IPModMainClient` S10, `FrustumCuller`/`MyGameRenderer` S11, client mixins S12) or held past S13.
Resolution = forced-deviation **F21**: a `compileOnly` stub-classpath ARTIFACT — empty shells of
EXACTLY the third-party types the held files reference (correct FQN + kind + only the touched members,
bodies empty/`return null`). Landed at `common/src/ipStubs/**` (14 shells); consumed at S4.

### 3.2 SODIUM — `net.caffeinemc.mods.sodium.*` — 8 stub types (CONSUMED S4)

Referenced by `SodiumInterface` (6 imports), `IESodiumWorldRenderer` (2, overlap),
`SodiumRenderingContext` (1). 7 imported + 1 inferred:

| # | FQN | Kind | Members the shell exposes | Cited at |
|---|---|---|---|---|
| S1 | `…client.render.SodiumWorldRenderer` | class | `void scheduleTerrainUpdate()` | SodiumInterface:65-67,75; IESodiumWorldRenderer @Mixin target |
| S2 | `…client.render.chunk.RenderSectionManager` | class | (no members — local type + cast source) | SodiumInterface:69-72; IESodiumWorldRenderer @Accessor return |
| S3 | `…client.render.chunk.map.ChunkStatus` | class | `static final int FLAG_HAS_BLOCK_DATA` | SodiumInterface:86,92 |
| S4 | `…client.render.chunk.map.ChunkTrackerHolder` | class | `static ChunkTracker get(net.minecraft.world.level.Level)` | SodiumInterface:85,91 (arg is `ClientLevel`, a `Level` subtype) |
| S5 | `…client.render.chunk.map.ChunkTracker` **(inferred)** | class | `void onChunkStatusAdded(int,int,int)`; `void onChunkStatusRemoved(int,int,int)` | chained on S4.get(...) |
| S6 | `…client.render.texture.SpriteUtil` | class | `static void markSpriteActive(TextureAtlasSprite)` | SodiumInterface:80 (static call — matched to IP's call site, not real Sodium's `INSTANCE`) |
| S7 | `…client.world.LevelRendererExtension` | **interface** | `SodiumWorldRenderer sodium$getWorldRenderer()` | SodiumInterface:66 (cast on `Minecraft.getInstance().levelRenderer`) |
| S8 | `…client.render.chunk.lists.SortedRenderLists` | class | `static SortedRenderLists empty()` | SodiumRenderingContext:6 (field), :12 (static) |

Landed shells verified, e.g. `SodiumWorldRenderer.java` = `public class …{ public void
scheduleTerrainUpdate(){} }`; `ChunkTrackerHolder.get(Level)` returns `null` typed as `ChunkTracker`
(the one inferred type — never imported, only the inline return of `get(world)`). Vanilla receiver
`Minecraft.getInstance().levelRenderer` is SAME on 26.2 (`Minecraft.java:281`) — no stub, no translation.

### 3.3 IRIS — `net.irisshaders.iris.*` — 4 stub types (**consumed S4, not only S12 — FLAG A**)

The plan schedules the iris half at S12, but `IrisInterface` is the compile-mandatory invoker BASE
(imported by `Portal` S6, `FrustumCuller`/`MyGameRenderer` S11, `IPModInfoChecking` S12, client mixins)
and lands at S4 — so its 4-type subset is needed NOW:

| # | FQN | Kind | Members | Cited at |
|---|---|---|---|---|
| I1 | `net.irisshaders.iris.Iris` | class | `static Optional<?> getCurrentPack()`; `static PipelineManager getPipelineManager()`; `static String getCurrentPackName()` | IrisInterface:58,85,91 |
| I2 | `…pipeline.WorldRenderingPipeline` | interface | (cast target only) | IrisInterface:69 |
| I3 | `…shadows.ShadowRenderer` | class | `static boolean ACTIVE` | IrisInterface:63 |
| I4 | `…pipeline.PipelineManager` **(inferred)** | class | `void destroyPipeline()` | IrisInterface:85 (return of I1.getPipelineManager) |

Vanilla reflection subject `LevelRenderer` field `"pipeline"` is SAME on 26.2 — not stubbed. The FULL
iris surface (S12 renderer shells — `IrisPortalRenderer` etc. + `mixin/iris/*`) is derived separately
at S12; these 4 are the S4 requirement.

### 3.4 GRAVITY-CHANGER — `gravity_changer.*` — 2 stub types (**F21 EXTENSION — FLAG B**)

The plan's F21 type list (S00-U0-decisions F21) names sodium/iris/autoconfig ONLY. `GravityChangerInterface`
is a compile-mandatory invoker base (`McHelper` S5 imports it → lands S4) and is the ONLY file in all
of IP importing `gravity_changer.*`. F21 is EXTENDED with it (boundary defense identical):

| # | FQN | Kind | Static members | Cited at |
|---|---|---|---|---|
| G1 | `gravity_changer.api.GravityChangerAPI` | class | `getEyeOffset(Entity)`, `getGravityDirection(Entity)`, `getBaseGravityDirection(Entity)`, `setBaseGravityDirection(Entity,Direction)`, `instantlySetClientBaseGravityDirection(Player,Direction)`, `getWorldVelocity(Entity)`, `setWorldVelocity(Entity,Vec3)` | GravityChangerInterface:97,102,107,112,126,141,146 |
| G2 | `gravity_changer.util.RotationUtil` | class | `getWorldRotationQuaternion(Direction)→Quaternionf`, `vecPlayerToWorld/vecWorldToPlayer(Vec3,Direction)`, `dirPlayerToWorld/dirWorldToPlayer(Direction,Direction)` | GravityChangerInterface:136,151,155,161,165 |

`RotationUtil.getWorldRotationQuaternion` feeds `DQuaternion.fromMcQuaternion(Quaternionf)` (JOML, on
the vanilla classpath) — all params vanilla. **No stub for flywheel/porting_lib**: neither compat file
imports those mods' types (both use `FabricLoader.isModLoaded(String)` + reflection on vanilla fields).

### 3.5 How the compileOnly wiring works (`common/build.gradle` + both loader tasks)

- **`ipStubs` source set** (`common/build.gradle:15-17`) holds the 14 shells; `addModdingDependenciesTo
  sourceSets.ipStubs` (`:37`) gives it the Minecraft compile classpath so the shell signatures can
  reference vanilla `Level`/`Vec3`/`TextureAtlasSprite`/JOML.
- **`:common:compileJava`** gets the shells via `compileOnly sourceSets.ipStubs.output` (`:60`).
- **The loader tasks** recompile the RAW common source directory (`source(configurations.commonJava)`),
  so at the `-Pip_scc_closed=true` probe they ALSO see the held compat files — they consume the same
  shells via a consumable `ipStubsClasspath` configuration (`:92-95`) exposing the `ipStubsJar`
  (`:100-103`, classifier `f21-ipstubs`); wired in `multiloader-loader.gradle:28`
  `compileOnly project(path: ':common', configuration: 'ipStubsClasspath')`.
- **compileOnly ONLY** — the shipped jar packages `sourceSets.main.output` only; the shells never reach
  any runtime/implementation classpath. **Removed at S20** with the rest of the holding machinery.

### 3.6 Boundary defense (why F21 is not a hard-constraint-2 violation)

Constraint 2 bans **stub or simplified variants of IP code** held in the shipped tree. F21 shells are
the opposite category: they are **THIRD-PARTY types, not IP source** (no file under `qouteall.*` is
stubbed — every ported IP compat file is a complete verbatim port), they are `compileOnly` (never
shipped), and providing a compile classpath for absent third-party dependencies is build engineering
in the D1 sense. Runtime compat is a separate question governed by checkpoint C2 (default: shells only,
dead on 26.2 until those mods port). `IESodiumWorldRenderer` is never registered — the mixin plugin's
sodium-present gate stays false. This mirrors the SODIUM stub-header text verbatim in the landed shells.

---

## 4. F12 loader-seam substitutions — and the RequiemCompat correction

### 4.1 Applied F12/B1 substitutions (2 files, 1 hunk each)

`net.fabricmc.fabric.api.event.Event` → `com.warwa.seamlessportals.event.Event` in **`IPGlobal`** and
**`IPCGlobal`** — the ONLY two Slice-B files whose Fabric-`Event` use is a field TYPE assignable from
ported-`Helper`'s seam-returning factories (`createRunnableEvent`/`createConsumerEvent` return
`com.warwa.…event.Event<…>`, so the field type MUST be the seam type). Identical to the already-ported
`Helper.java:7-8`. This is the established S0 decision (F12/B1).

### 4.2 RequiemCompat — the task's "F12 substitution" premise does NOT apply (verified this pass)

The task directed "its net.fabricmc imports take the F12 loader-seam substitution." **Re-verified
against the landed file:** `RequiemCompat`'s actual `net.fabricmc` imports are `api.EnvType`,
`api.Environment` (`:3-4`) and `loader.api.FabricLoader` (`:5`) — it imports **no**
`fabric.api.event.Event`. There is therefore NO F12 target and **zero F12 substitution hunks**;
`git diff --no-index --ignore-cr-at-eol` returns EMPTY (byte-verbatim modulo EOL). RequiemCompat
landed VERBATIM held, with those three Fabric imports as NR-1 forward-seam-debt (§1.0-2), and its
qouteall refs (`ClientTeleportationManager`/`ServerTeleportationManager` → S8, `McHelper` → S5,
`Helper` held) kept verbatim exactly as specified. The `@Environment` annotation (`:62`) + `FabricLoader`
mod-query facade need the mod-owned loader-info seam of NR-1, not an import swap.

**RequiemCompat's ONE 26.2-visibility obligation — `ServerPlayer.server` AW/AT growth (verified this
pass).** `RequiemCompat.java:91` reads `player.server` verbatim
(`ServerTeleportationManager.of(player.server)`). In IP's 1.21.3 `ServerPlayer.server` was **public**,
so IP's `imm_ptl.accesswidener` has NO `ServerPlayer` entry — none was needed. 26.2 reduces it to
`private final MinecraftServer server` (`ServerPlayer.java:232`), so `player.server` is inaccessible
from `qouteall.*` and the `-Pip_scc_closed=true` probe surfaces
`RequiemCompat.java:91: error: server has private access in ServerPlayer`. This is **identical in
class to the `IEChunkMap.TrackedEntity` AW growth (§2c)** — a 26.2 visibility reduction on verbatim
IP code — and the faithful fix is the same: grow the AW/AT to restore the access IP had for free,
keeping `RequiemCompat` byte-verbatim (chosen over rewriting `player.server` to an accessor, which
would add a source hunk to a held file). Landed this pass:
`seamlessportals.accesswidener` `accessible field net/minecraft/server/level/ServerPlayer server
Lnet/minecraft/server/MinecraftServer;` + `accesstransformer.cfg`
`public net.minecraft.server.level.ServerPlayer server` (read-only, so no `mutable`/de-final).
`RequiemCompat` is HELD until S13; the growth is landed now so the S13 closure does not re-discover
it. Triaged in §8.2 as the resolution category the earlier §8 record omitted.

### 4.3 Why not more F12 substitutions here

The premise "its net.fabricmc imports take the F12 loader-seam substitution" holds ONLY for
`fabric.api.event.Event` (the one Fabric type with a landed S0 seam, B1). Every OTHER `net.fabricmc.*`
surface in Slice B/C (`FabricLoader`, `ModContainer`, `Version`, `SemanticVersionImpl`, the
`@Environment` annotation + `EnvType.CLIENT` value-use, `ClientChunkEvents`, `ClientTickEvents`) has
NO S0 seam and is NOT on `:common`'s classpath, so it can be neither substituted (no target) nor
kept-and-compiled — landed VERBATIM held as NR-1 forward-seam-debt.

**Correction (this pass) — the Slice C `@Environment` annotations are NOT stripped.** An earlier pass
removed the `net.fabricmc.api.{EnvType,Environment}` imports and the `@Environment(EnvType.CLIENT)`
annotations from the four Slice C compat files (`GravityChangerInterface`, `SodiumInterface`,
`IPFlywheelCompat`, `IPPortingLibCompat`) and recorded it as an api-map `[T]` translation citing
**"F6"**. That was an **unauthorized deviation**, now **reverted** — the four files are restored to
byte-verbatim on those lines. Reasons: (i) `@Environment` is a Fabric loader annotation, not a 26.2
vanilla API change, so its removal is neither a "26.2 API translation with an api-map row"
(`platform-compat-peripheral.md` has none) nor an F12 loader-seam substitution (a deletion, not a
seam swap); (ii) **"F6" is a misattribution** — register F6 (`EXECUTION_PLAN.md:1481`) is the
`PortalRenderTypes` pipeline-layer / `drawMesh` render-pipeline entry; **no F-number authorizes
`@Environment` removal**; (iii) it was **internally inconsistent** — the identical annotation is KEPT
verbatim in Slice B (`IPCGlobal:13`, `DimensionIntId:46/51/59`, `RequiemCompat:62`), so the two slices
contradicted each other; (iv) **no compile benefit** — all four files are HELD, so stripping does not
help the shipping build. Uniform verbatim retention as NR-1 forward-seam-debt (exactly what Slice B
did) is the disciplined, zero-deviation path; the deferred **uniform `@Environment`-drop policy**
(NR-1, §1.0-2) resolves all of them together later. This is distinct from — and no longer conflated
with — the unresolved `@Environment` **value-use** (`EnvType.CLIENT`) in `IPPortingLibCompat.init`
(`:22`), which is also NR-1.

---

## 5. F11 DimLib event-wiring stub decision

**Decision: land `DimensionIntId` + `DimensionIdRecord` VERBATIM held; do NOT fabricate the F11 stub
this stage; specify its exact required surface + recommended shape; flag NEEDS-REVIEW (§1.0-3).**

`DimensionIntId` imports `qouteall.dimlib.api.DimensionAPI` (`:16`) and, in `init()` only (`:31-44`),
wires the DimLib dynamic-dimension update event. DimLib has no 26.2 form and **no source in the IP
tree** (`find … dimlib` = 0 hits); no stub exists anywhere in the ported tree. Per register F11, the
"static-dimension EVENT-WIRING stub" is consumed at S4 by exactly this use — but creating it is a
non-obvious design decision AND potentially a build-classpath artifact (outside the translator lane).

**Exact surface `DimensionIntId.init()` requires** the F11 stub `qouteall.dimlib.api.DimensionAPI` to
provide:
- static field `SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT` whose type exposes
  `void addPhaseOrdering(Identifier earlyPhase, Identifier defaultPhase)` (`:33-36`) and
  `void register(Identifier phase, <Callback> listener)` where the listener is a
  `(MinecraftServer, Set<ResourceKey<Level>>) -> {…}` bi-arg lambda (`:38-43`);
- the Fabric `Event.DEFAULT_PHASE` constant referenced at `:35`. **Crux:** the landed
  `com.warwa.seamlessportals.event.Event` seam DELIBERATELY omits the phase system, so the Fabric-`Event`
  import here was **NOT** substituted to the seam (doing so would break `Event.DEFAULT_PHASE`).

**Why a never-firing stub is the FAITHFUL target:** per `S00-seam-inventory.md` A3, the dynamic-dim
ordering `init()` wires is a DimLib-phase concern OUT OF SCOPE; with static dimensions the invariant
("dim-int-id updates before global-portal storage") is preserved by the A1 init order, not this event.
So the faithful static stub accepts registration + phase ordering but **never fires** — `init()`
compiles and runs, registers a listener never invoked (correct for static dims; `onServerDimensionChanged`
is driven by `onServerStarted` + the A1 init order instead).

**Recommended home (build agent / reviewer, NOT this translator):** a minimal in-tree
`qouteall.dimlib.api.DimensionAPI` package (register F11 anticipates growth — `DimensionAPI` by S4,
`+DimensionTemplate` by S13, so an in-tree package is the cleaner home) OR an F21-style `compileOnly`
artifact; either way `Event.DEFAULT_PHASE` must also resolve. `DimensionIdRecord` is a deprecated
Polymer-compat shim with no DimLib/Fabric coupling — pure verbatim (0 hunks), held only through
`DimensionIntId` + `MiscHelper` (both S4 in-probe). **The stub is not yet landed — this is the F11
decision the task asked to be surfaced.**

---

## 6. IPMixinPlugin → SeamlessMixinConfigPlugin wiring plan (D3)

**Landed:** `IPMixinPlugin.java` VERBATIM held at `qouteall/imm_ptl/core/IPMixinPlugin.java` (0 hunks;
its `FabricLoader` import `:3` is NR-1; ASM `ClassNode` + spongepowered `IMixinConfigPlugin`/`IMixinInfo`
ARE on `:common`'s classpath via the `mixin 0.8.5` + `asm-tree 9.7` compileOnly deps in
`common/build.gradle:49-53`, so those resolve).

**Runtime role (D3):** IPMixinPlugin is IP's Fabric mixin-config plugin. In this mod its role is
**absorbed by the KEEP'd `com.warwa.seamlessportals.mixin.SeamlessMixinConfigPlugin`** — the S0
mixin-config skeletons already declare THAT plugin (`S00-seam-inventory.md` Part C: every IP-side
`seamlessportals-ip-*.mixins.json` sets `"plugin": "com.warwa.seamlessportals.mixin.SeamlessMixinConfigPlugin"`).
So the ported `IPMixinPlugin` class is **never registered as a plugin**; it lands purely for
fidelity/compile-closure.

**The only gate logic to carry:** `IPMixinPlugin.shouldApplyMixin` (`:22-30`) skips IP's
`MixinRenderTarget`/`MixinMainTarget` when `porting_lib` is loaded (a Fabric-ecosystem stencil-conflict
avoidance). On 26.2 this gate is **effectively dead**: (1) `porting_lib` has no 26.2 build (the whole
`IPPortingLibCompat` stencil path is GONE); (2) IP's `MixinRenderTarget`/`MixinMainTarget` belong to
IP's render-target stencil substrate, which is REPLACED by the mod's KEEP'd stencil FBO chain
(`GlBackendMixin`/`RenderTargetMixin`/… — A1) and is not expected to be ported/registered.

**Wiring recommendation (port-note item; no code required this stage):** at S13, when the IP mixin
JSONs register against `SeamlessMixinConfigPlugin`, fold an equivalent guard into
`SeamlessMixinConfigPlugin.shouldApplyMixin` **only if** IP's `MixinRenderTarget`/`MixinMainTarget` are
actually ported+listed in a registered config — reusing the plugin's existing
`SODIUM_INCOMPATIBLE_MIXINS`-style set + its already-present reflective FabricLoader/ModList detection
(`SeamlessMixinConfigPlugin.java:55-83`) keyed on `porting_lib`. If (as expected) those IP mixins are
dropped with the porting_lib path, the faithful wiring is a **documented no-op**: nothing added to
`SeamlessMixinConfigPlugin`, and the ported `IPMixinPlugin` stays an unregistered fidelity artifact.
The add-or-noop decision belongs to whoever lands the render/stencil substrate (S12) and the S13
registration commit; recorded here so it is not lost.

---

## 7. R12 — `MyNbtTextFormatter` / `SnbtPrinterTagVisitor` re-derivation (12 hunks)

IP's `MyNbtTextFormatter` is an `@IPVanillaCopy` of 1.21.3 `SnbtPrinterTagVisitor`, modified to emit
colored `Component` output. The 26.2 tag API changed the value accessors and dropped
`ListTag.getElementType()`. `TagVisitor`'s 13 visit methods are UNCHANGED (`26.2:TagVisitor.java:3-29`),
so the class structure/behavior is preserved verbatim; only the vanilla API calls are translated.
Re-derivation source read directly: `26.2:SnbtPrinterTagVisitor.java`, `ByteTag.java`, `StringTag.java`,
`ListTag.java`, `NumericTag.java`.

| # | IP call | → 26.2 | Derivation / citation |
|---|---|---|---|
| 1 | `StringTag.getAsString()` | `.value()` | `record StringTag(String value)` (`26.2:StringTag.java:8`); 26.2 visitor uses `tag.value()` (`SnbtPrinterTagVisitor.java:55`) |
| 2 | `ByteTag.getAsByte()` ×2 | `.value()` | `record ByteTag(byte value)` (`26.2:ByteTag.java:7`); `value()==0/==1` byte↔int promotion intact |
| 3 | `getAsNumber()` ×4 (byte/short/int/long) | `.box()` | `NumericTag.box()→Number` (`26.2:NumericTag.java:18`); direct replacement for removed `getAsNumber()`; `String.valueOf(Number)` = identical digits |
| 4 | `FloatTag.getAsFloat()` | `.value()` | `record FloatTag(float value)`; `String.valueOf(float)` intact |
| 5 | `DoubleTag.getAsDouble()` | `.value()` | `record DoubleTag(double value)`; `String.valueOf(double)` intact |
| 6 | `ListTag.getElementType()` | `element.get(0).getId()` | **NEEDS-REVIEW (§1.0-6) — the one design-level spot (G8).** `getElementType()` GONE; 26.2 `ListTag.identifyRawElementType()` is package-private `@VisibleForTesting` (`26.2:ListTag.java:191-205`), uncallable from `qouteall.*`. IP lists are HOMOGENEOUS (portal debug NBT), and for a homogeneous list `getElementType()==get(0).getId()`, so `get(0).getId()` reproduces IP's behavior EXACTLY on IP's inputs. Guard preserved: `if (element.isEmpty()) return;` at the top of `visitList` guarantees ≥1 element. `Tag.getId()` is public (byte); `SINGLE_LINE_ELEMENT_TYPES` is a fastutil `ByteCollection.contains(byte)`. Divergence only for a newly-possible 26.2 HETEROGENEOUS list (which `identifyRawElementType` maps to `10`, forcing multi-line, whereas `get(0).getId()` classifies by first element) — portal debug NBT never produces one. Debug-display-only; flag S13/commands to confirm or replace with an all-elements-homogeneous check |
| 7 | `CompoundTag.getAllKeys()` ×2 | `.keySet()` | C16 (`26.2:CompoundTag.java:193`); 26.2 visitor uses `tag.keySet()` (`SnbtPrinterTagVisitor.java:216`); `Set<String>`⊆`Collection<String>` — assignment + `Lists.newArrayList(...)` intact |

UNCHANGED (verified against the 26.2 visitor, NOT translated): `getAsByteArray()`/`getAsIntArray()`/
`getAsLongArray()` survive (`SnbtPrinterTagVisitor.java:91/107/124`); `ListTag.isEmpty/size/get`,
`CompoundTag.isEmpty/get`, `StringTag.quoteAndEscape` all SAME (S47/S48). `@IPVanillaCopy` (`:27`,`:40`)
resolves in-probe (carved in this stage, §1.1).

---

## 8. Probe-vs-U2-union triage record (erratum iv applied)

At `-Pip_scc_closed=true` the S4 held tree is compiled; the D4.2 rule is: every probe error maps to
documented forward-ref debt (import-graph-justified → ledger amendment) or is a translation slip (fixed
now). **When diffing probe errors against the LITERAL U2 row, apply Appendix A.6 erratum iv** so the
triage does not misfire: the U2 row labels `IPPerServerInfo`→`CustomPortalGenManager` as "(U9)", which
is WRONG — it is a U11 closure co-port (S13). The plan's S4(b) union already corrects this.

### 8.1 Qouteall forward-refs — the documented S4(b) union (resolve later, NOT slips)

Cross-checked across all landed files; matches plan S4(b) (lines 586-607):

- `O_O` → `McHelper` (S5), `ImmPtlClientChunkMap` (S9), `ImmPtlNetworkConfig` (S7), `Portal` (S6),
  `PortalGenInfo` (S13 closure) — [erratum on U2 row: `PortalGenInfo` is a closure co-port].
- `IPCGlobal` → `PortalRenderer`/`RendererDebug`/`RendererDummy`/`RendererUsingFrameBuffer`/
  `RendererUsingStencil` (all U10 → S12).
- `IPConfig` → `BlockPortalShape` (U4 co-port → S6).
- `IPPerServerInfo` → `ServerTeleportationManager` (S8), `CustomPortalGenManager` (**S13, erratum iv —
  NOT U9**), `PortalWandInteraction` (S13).
- `DimensionIntId` → `McHelper` (S5) + `MiscNetworking` (U5 → S7); `IPCGlobal`/`IPPerServerInfo` in-probe.
- `GravityChangerInterface` → `CHelper` + `McHelper` (S5).
- `SodiumInterface` → `FrustumCuller` (S11) + `IESodiumWorldRenderer` (landed held here, in-probe) +
  same-package `SodiumRenderingContext`/`IESodiumRenderSectionManager` (landed held here, in-probe).
- `MyNbtTextFormatter`/`McHelper`(S5)/`CollisionHelper`(S6)/… → `IPVanillaCopy` — **all resolve in-probe**
  (carved in this stage; the S4 carve-in resolves all 20 `@IPVanillaCopy` refs across S4–S12).
- The 3 held ducks: `IEClientWorld.java:8`→`Portal` (S6); `IEEntity.java:8-9`→`PortalCollisionHandler`
  + `Portal` (S6); `IEMinecraftServer.java:3`→`IPPerServerInfo` (in-probe, Slice B lands it here).
- `RequiemCompat` → `ClientTeleportationManager`/`ServerTeleportationManager` (S8) + `McHelper` (S5).
- `IPFlywheelCompat`/`IPPortingLibCompat`/`IrisInterface` → `Helper` (S2, in-probe);
  `IPPortingLibCompat` → `IEFrameBuffer` (ducks pkg, in-probe). `IPFlywheelCompat` itself is a
  forward-ref target of `IPModMainClient` (S10) — resolved by landing it here.

All resolve at their owning stage — documented debt, not slips. Probe must show ONLY this union.

### 8.2 Debt the S4(b) PAPER ledger does NOT list — ledger amendments (this note is the record)

The paper ledger tracks only qouteall forward-refs. **Six** additional error categories surface in
the actual probe (re-verified this pass by re-running `:common:compileJava -Pip_scc_closed=true` and
triaging all 236 `error:` lines); all are import-graph-, visibility-, or S0-schedule-justified →
**ledger amendments, triaged NOT as translation slips** — but UNLIKE qouteall refs, categories 1, 2
and 6 do NOT self-resolve by S13 without their seam/stub decisions landed:

1. **NR-1 Fabric-facade imports** (`FabricLoader`/`ModContainer`/`Version`/`SemanticVersionImpl`/
   the `@Environment` **annotation** + `EnvType.CLIENT` **value-use**/`ClientChunkEvents`/
   `ClientTickEvents`) — every Slice B/C file except `IPPerServerInfo`/`DimensionIdRecord`. The Slice C
   `@Environment` annotations (`GravityChangerInterface`/`SodiumInterface`/`IPFlywheelCompat`/
   `IPPortingLibCompat`) are held VERBATIM here too (the earlier "F6" strip is reverted — §4.3), so they
   join this bucket uniformly with Slice B. Need the NR-1 mod-owned loader-info seam + `@Environment`-drop
   policy (§4). `ServerTaskList`'s `ServerTickEvents.END_SERVER_TICK` is the same class — A6/S10 wiring.
2. **NR-2 DimLib `DimensionAPI` + Fabric `Event.DEFAULT_PHASE`** (`DimensionIntId`) — need the F11 stub
   (§5).
3. **Third-party mod types provisioned by F21 build engineering** (NOT qouteall closure): 8
   `net.caffeinemc.*` + 4 `net.irisshaders.*` + 2 `gravity_changer.*` (§3). These resolve against the
   `ipStubs` `compileOnly` classpath already landed this stage — so with the F21 artifact present the
   probe shows them RESOLVED; without it they would be unresolved. FLAG A (iris at S4) + FLAG B
   (gravity in F21) are the extensions this note ratifies.
4. **The 4 GONE-type ducks** (§2b) surface unresolved-vanilla-type errors (`MultiBufferSource`/
   `LightTexture`/`shaders.Uniform`/`Ticket<?>`) — render/command-slice redesign debt (S11/S12/S13),
   held so they do not turn the SHIPPING build red; NOT slips.
5. **26.2 vanilla visibility-reduction → AW/AT growth** (the category the original §8 record MISSED).
   `RequiemCompat.java:91`'s verbatim `player.server` read hits `server has private access in
   ServerPlayer` because 26.2 made `ServerPlayer.server` `private final` where IP's 1.21.3 had it public
   (§4.2). This fits NONE of buckets 1–4 (it is neither a Fabric-facade import, a DimLib ref, a
   third-party-mod type, nor a GONE vanilla type). It is **identical in class to the `IEChunkMap`
   `TrackedEntity` widen (§2c)** and is resolved the same way: the `ServerPlayer.server` AW/AT entry
   landed this pass — so the probe now shows this line RESOLVED, and `RequiemCompat`'s remaining probe
   errors are its qouteall forward-refs (`ClientTeleportationManager`/`ServerTeleportationManager` → S8,
   `McHelper` → S5, category §8.1) plus its NR-1 Fabric imports (category 1). Unlike categories 1–2,
   this one **does** self-resolve at S13 now that the AW/AT is landed.
6. **autoconfig (cloth-config `me.shedaniel.autoconfig` + `ConfigEntry`) — F21's autoconfig half,
   scheduled at S13 not S4 (by-design per S0).** `IPConfig` (59 errors) and `IPGlobal` (1) reference
   `me.shedaniel.autoconfig.*`/`@ConfigEntry.Gui.*`, which has no 26.2 build. Register F21 names
   sodium/iris/**autoconfig** (`EXECUTION_PLAN.md` §4; `S00-U0-decisions.md:194,210-212,230`), but S0
   scheduled the autoconfig stub for **S13** (it enters the SCC only via `IPConfigGUI`←`ClientDebugCommand`,
   round-3 extension), so — unlike the sodium/iris/gravity half landed at S4 (category 3) — the
   autoconfig shells are deliberately **NOT** landed here. Its probe errors are therefore expected and
   by-design, NOT a gate failure: the S4 "no third-party-type errors" gate is about the F21 types this
   stage committed to provisioning (sodium/iris/gravity — 0 errors), and autoconfig resolves against the
   S13 F21 extension. NOT a translation slip.

**Consequence for S13:** the qouteall forward-refs (§8.1) self-resolve as their owning stages land;
the F21 sodium/iris/gravity refs (§8.2-3) resolve against `ipStubs` now; the `ServerPlayer.server`
visibility error (§8.2-5) resolves against the AW/AT growth landed now; the autoconfig refs (§8.2-6)
resolve against the S13 F21-autoconfig extension; but the NR-1 loader-facade and NR-2 DimLib debt
(§8.2-1/2) require the seam/stub decisions in §4/§5 to be LANDED before the `ip_scc_closed=true` flip
can go "qouteall-forward-refs-only." Those are the S4 exit obligations carried forward.

---

## 9. Fidelity summary

- **32 IP files carved in** (29 ducks + `IEMinecraftServer_Misc` + `IELevelStorageAccess_Misc` +
  `IPVanillaCopy`), **29 held** — all 61 landed VERBATIM at final `qouteall.*` paths (D2).
- **25 content hunks total** (Slice A 15 + Slice B 8 + Slice C 2), every one category [T] (api-map row)
  or [S] (F12/B1 seam); zero unjustified deviations. NEEDS-REVIEW items are seam/stub GAPS or plan-count
  corrections, not slips.
- **`@Environment` retained uniformly (deviation reverted this pass).** The four Slice C compat files
  keep their `net.fabricmc.api.{EnvType,Environment}` imports + `@Environment(EnvType.CLIENT)`
  annotations VERBATIM as NR-1 debt, matching Slice B; the earlier "F6"-cited strip was unauthorized
  (F6 is `PortalRenderTypes`/`drawMesh`, `EXECUTION_PLAN.md:1481`) and is undone (§4.3, §1.1).
- **AW/AT grown twice this stage** (faithful ports of 26.2 visibility reductions, not source hunks):
  `ChunkMap$TrackedEntity` (carved-in duck `IEChunkMap`, §2c) and `ServerPlayer.server` (held facade
  `RequiemCompat.java:91`, §4.2/§8.2-5).
- **Plan correction:** duck carve-in is **29, not 33** (§2); the reality is 29 carve-in / 7 held.
- **Record corrections (this pass):** `MiscHelper` was mis-recorded as verbatim — it has one legitimate
  `[T]` `ResourceKey.location()`→`.identifier()` hunk (§1.1); the Slice B/C/grand-total hunk counts are
  recomputed accordingly (§1.1 totals).
- **F21 extended** beyond the plan's sodium/iris/autoconfig list with **gravity_changer** and **iris at
  S4** (14 `compileOnly` shells landed in `common/src/ipStubs`); boundary defense intact (third-party
  types, never shipped, removed S20).
- **Open S4 exit obligations** (do not self-resolve by S13): NR-1 loader-info seam + `@Environment`
  policy; NR-2 F11 DimLib stub; A6/S10 `ServerTickEvents` wiring; the R12 `getElementType` and
  `IPPortingLibCompat`-stencil confirmations at their later slices.
