# S10A-loader-seam — Stage S10 sub-stage A port-note (LOADER-SEAM FOUNDATION)

**Stage:** S10-A (loader-seam foundation — the S4 exit obligations NR-1/NR-2 + config-phase debt,
folded into S10 per the plan) · **Assembled:** 2026-07-14
**Governing plan:** `migration/EXECUTION_PLAN.md` §3 S10 + §1 D1 (holding machinery) + D4.3
(diff gate) + register F11 (DimLib) / F12 (loader seams) / F21 (compileOnly stub-classpath precedent).
**Debt resolved (the S4/S7 exit obligations):** `S04-ducks-roots-facade.md` §1.0-2 (NR-1 loader
facade), §1.0-3 / §5 (NR-2 F11 DimLib), §8.2-1/2 (open exit obligations); `S07-network.md` §6/§7
(config-phase held-verbatim).
**Discipline:** FIDELITY PRINCIPLE — resolve held IP code VERBATIM via a compileOnly stub classpath;
edit a held file ONLY when the reference is a RUNTIME call that must route per-loader (a documented
D4.3 loader-seam substitution). Every stub is third-party/loader scaffolding, never IP source.
**IP source:** `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall` (1.21.3, Mojang).
**26.2 evidence:** `C:/Users/warwa/ModDev/mc262-ref`.

---

## 0. Stage result (build + probe evidence)

| Gate | Command | Result |
|---|---|---|
| Shipping build | `:common:compileJava :fabric:compileJava` (`ip_scc_closed=false`) | **BUILD SUCCESSFUL, 0 errors** — every stub is `compileOnly` (never shipped, never referenced by any non-held file); shipping gate unchanged |
| Compile probe (BEFORE) | `:common:compileJava -Pip_scc_closed=true` (start of stage) | **536 errors** (228 `net.fabricmc.*` mentions, 12 `qouteall.dimlib` mentions, 7 `DimensionAPI` package errors, 73 `class Environment` + 76 `variable EnvType` symbol errors, …) |
| Compile probe (AFTER) | `:common:compileJava -Pip_scc_closed=true` (end of stage) | **206 errors** — **net.fabricmc mentions = 0, qouteall.dimlib mentions = 0, Environment/EnvType symbol errors = 0.** The remaining 206 are ONLY genuine qouteall forward-refs (U8/U9/U10/U11/U12 not-yet-ported) + autoconfig (S13 by-design) + the 2 documented Fabric interface-injection residuals |

**330 errors resolved.** The loader-facade / `@Environment` / config-phase-type / DimLib swath is
GONE from the probe; later S10 sub-stages get clean triage and the S13 flip is unblocked on this axis.

---

## 1. Classification table (STEP 1) — every non-qouteall external reference the probe could not resolve

Resolution legend: **STUB** = compileOnly shell, held file kept byte-verbatim · **SEAM** = D4.3
held-file edit (import retargeted to a mod-owned type) · **residue** = cannot be resolved on the
loader-neutral `:common` probe, resolves at the S13 fabric-loader compile.

### (a) `net.fabricmc.api.{EnvType, Environment}` — @Environment annotations — **STUB, keep verbatim**

| Symbol | Category | Resolution |
|---|---|---|
| `@Environment` annotation + `EnvType` enum (CLIENT/SERVER), ~41 held files (MiscHelper, IPCGlobal, IPMcHelper, CHelper, ScaleUtils, Portal, PortalLike, PortalRenderInfo, PortalAnimation, DefaultPortalAnimation, StableClientTimer, PortalPlaceholderBlock, EndPortalEntity, LoadingIndicatorEntity, CrossPortalSound, ClientTeleportationManager, ClientPerformanceMonitor, CollisionHelper, all 4 `portal/shape/*`, MiscNetworking, ImplRemoteProcedureCall, McRemoteProcedureCall, CustomTextOverlay, DimensionIntId, PortalAPI, GlobalPortalStorage, ImmPtlNetworkConfig, ImmPtlNetworking, PacketRedirection, PacketRedirectionClient, ImmPtlClientChunkMap, O_O, RequiemCompat, ClientPortalAnimationManagement, GravityChangerInterface, SodiumInterface, IPFlywheelCompat, IPPortingLibCompat) | compile-only annotation/enum; NO per-loader runtime behavior | **STUB** `common/src/fabricStubs/net/fabricmc/api/{EnvType,Environment}.java` |

### (b) `net.fabricmc.loader.api.*` + `impl.SemanticVersionImpl` — FabricLoader family — **STUB (NOT seam)**

| File:line | Symbol / call | Resolution |
|---|---|---|
| `O_O.java:6-10,58,80,93,111,143,167,173,177,203,231,238,242,252` | `FabricLoader.getInstance()` → `getGameDir/getEnvironmentType/isModLoaded/getModContainer/isDevelopmentEnvironment/getAllMods`; `ModContainer.getMetadata()`; `ModMetadata.getVersion/getName/getId/getIconPath`; `Version.parse/compareTo`; `SemanticVersionImpl.getVersionComponentCount/getVersionComponent` | **STUB** (see §2 — decisive fidelity call) |
| `MiscHelper.java:15` | `FabricLoader` | STUB |
| `IPFeatureControl.java:4-5,12-16` | `FabricLoader.getInstance().getModContainer(...).orElseThrow()`, `ModContainer.getContainingMod()` | STUB |
| `IPMixinPlugin.java:3` | `FabricLoader` | STUB |
| `IPPortingLibCompat.java:6`, `IPFlywheelCompat.java:5`, `RequiemCompat.java:5` | `FabricLoader.getInstance().isModLoaded(...)` | STUB |

Stubs: `net/fabricmc/loader/api/{FabricLoader,ModContainer,Version,VersionParsingException}.java`,
`net/fabricmc/loader/api/metadata/ModMetadata.java`,
`net/fabricmc/loader/impl/util/version/SemanticVersionImpl.java` (fabricStubs).

### (c) config-phase networking + tick/chunk events + Fabric `Event` — **STUB** (one **SEAM**)

| File:line | Symbol | Resolution |
|---|---|---|
| `CollisionHelper:6,433`, `ImmPtlChunkTracking:8,46`, `ServerTaskList:3,11`, `ServerPerformanceMonitor:3,18`, `ServerTeleportationManager:4,68`, `WorldInfoSender:3,18`, `GlobalPortalStorage:7,84` | `ServerTickEvents.END_SERVER_TICK.register((server)->…)` | **STUB** `…event/lifecycle/v1/ServerTickEvents.java` |
| `IPGlobal:5,23` | `ClientTickEvents#END_CLIENT_TICK` (javadoc `{@link}`; import must resolve) | **STUB** `…client/event/lifecycle/v1/ClientTickEvents.java` |
| `O_O:5,68,74` | `ClientChunkEvents.CHUNK_LOAD/CHUNK_UNLOAD.invoker().onChunkLoad/Unload(ClientLevel, LevelChunk)` | **STUB** `…client/event/lifecycle/v1/ClientChunkEvents.java` |
| `ImmPtlNetworkConfig:7-12,117,123,161,208,212,216-217,250,260,265,275` | `PayloadTypeRegistry.configurationS2C/C2S().register`, `ServerConfigurationConnectionEvents.CONFIGURE.register`, `ServerConfigurationNetworking.{canSend,registerGlobalReceiver,Context.packetListener}`, `ClientConfigurationNetworking.{registerGlobalReceiver,Context.responseSender().sendPacket}`, `ClientLoginConnectionEvents.INIT.register`, `ClientPlayConnectionEvents.JOIN.register` | **STUB** (6 config-networking shells) |
| `ImmPtlNetworkConfig:194,218` | `networkHandler.completeTask(...)` / `handler.addTask(...)` — Fabric **interface-injected** methods on vanilla `ServerConfigurationPacketListenerImpl` | **residue** (§4) — resolves at S13 fabric-loader compile |
| `DimensionIntId:6,35` | `Event.DEFAULT_PHASE` (passed to `addPhaseOrdering`) | **STUB** `…api/event/Event.java` (DEFAULT_PHASE constant) |
| `ClientPortalAnimationManagement:5,21` | `Event<Consumer<Portal>>` **field type** assigned from `Helper.createConsumerEvent()` | **SEAM** — see §3 (a Fabric `Event` stub would be type-incompatible with Helper's mod-seam return) |

### (d) `qouteall.dimlib.api.DimensionAPI` — F11 DimLib — **STUB**

| File:line | Symbol | Resolution |
|---|---|---|
| `DimensionIntId:16,33,38`, `GlobalPortalStorage:38,97`, `ServerTeleportationManager:25,81`, `ImmPtlChunkTickets:20,80`, `ImmPtlChunkTracking:22,49`, `EntitySync:9,17` | `DimensionAPI.SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT.{addPhaseOrdering,register}` `(MinecraftServer, Set<ResourceKey<Level>>)`; `SERVER_PRE_REMOVE_DIMENSION_EVENT.register` `(ServerLevel)` | **STUB** `common/src/ipStubs/qouteall/dimlib/api/DimensionAPI.java` (§5) |

### (e) `net.fabricmc.fabric.impl.attachment.*` — Fabric attachment IMPL — **STUB** (category NOT in the task's (a)-(d) list; surfaced by STEP 1)

| File:line | Symbol | Resolution |
|---|---|---|
| `PlayerChunkLoading:6-7,221-225` | `((AttachmentTargetImpl) chunk).fabric_computeInitialSyncChanges(ServerPlayer, Consumer<AttachmentChange>)`; `AttachmentChange.partitionAndSendPackets(List, ServerPlayer)` | **STUB** `…impl/attachment/AttachmentTargetImpl.java` + `…impl/attachment/sync/AttachmentChange.java` |

*(`net.fabricmc.fabric.mixin.attachment.ChunkDataSenderMixin` appears twice — BOTH in javadoc
`{@link}` only (PlayerChunkLoading:213, MixinPlayerChunkSender:46), no import, no stub needed.)*

---

## 2. The @Environment POLICY decision + the FabricLoader stub-vs-seam decision

### 2.1 @Environment: keep-verbatim-via-stub (POLICY, recorded)

Every held IP file keeps its `@Environment(EnvType.CLIENT/SERVER)` annotation and `EnvType.SERVER`
value-uses **byte-for-byte**. Resolution is the compileOnly stub `net/fabricmc/api/{EnvType,
Environment}` on the `:common` probe classpath. This ratifies and closes the S4 NR-1 "uniform
`@Environment`-drop policy" as its OPPOSITE: **keep, do not drop.** Rationale: `@Environment` is a
Fabric loader annotation, not a 26.2 vanilla API change — dropping it is neither a T-translation nor
an F12 seam; and at S13 the fabric loader recompiles the held tree against the REAL fabric-loader
annotation (the stub is compileOnly, never shipped, never on the loader classpath — §6).

### 2.2 FabricLoader family → STUB, not a PlatformHelper loader-info seam (decisive)

The task's STEP 2(b) default was a `PlatformHelper` loader-info seam (isModLoaded/getEnvironment/
isClient) with held call sites retargeted. **DECISION: resolved via compileOnly stub instead; the
seam is NOT added.** Grounds (FIDELITY PRINCIPLE, which the task elevates and hedges "prefer a stub
over an edit"):

1. **O_O.java uses the FULL FabricLoader surface, far beyond isModLoaded** — `getGameDir`,
   `getEnvironmentType`, `getModContainer(...).getMetadata().getVersion()`, `Version.parse`,
   `SemanticVersionImpl.getVersionComponent(int)`, `getIconPath`, `getAllMods`,
   `isDevelopmentEnvironment`. A minimal isModLoaded/getEnvironment seam cannot cover this; full
   coverage would be a large multi-method rewrite of a held file (a big deviation) AND a large
   PlatformHelper surface replicating FabricLoader/ModContainer/Version/ModMetadata/SemanticVersion.
2. **No held call site needs per-loader RUNTIME routing** — by the fidelity test ("a RUNTIME call
   whose behavior must route per-loader"), FabricLoader calls do NOT qualify: the ported IP tree
   runs only on Fabric (NeoForge integration deliberately unwired — S07 §6), where the REAL
   FabricLoader is present at runtime. The compileOnly stub is therefore runtime-correct: it supplies
   only the compile-time signatures; at runtime the real FabricLoader executes.
3. **Consistency** — stubbing the whole family (incl. the cleanly-seamable single `isModLoaded`
   users) keeps ALL held facade files byte-verbatim; mixing seam+stub for one type would be
   inconsistent and the stub already resolves everything.
4. **No consumer for the seam** — with no held call site retargeted, a PlatformHelper loader-info
   seam would be dead code (an unused interface method forced onto both loader impls). If future
   MOD-OWNED common code needs loader info, add the seam then.

Result: **zero held-file edits for the entire FabricLoader family** (O_O and the other six facade
files land byte-verbatim; probe-confirmed — O_O's only residual errors are two `PortalGenInfo`
forward-refs, everything FabricLoader/ModContainer/Version/EnvType/ClientChunkEvents now resolves).

---

## 3. The one SEAM: ClientPortalAnimationManagement (F12/B1) — a slip-catch from S6

`ClientPortalAnimationManagement.java:21` declares
`public static final Event<Consumer<Portal>> CLIENT_PORTAL_DEFAULT_ANIMATION_FINISH =
Helper.createConsumerEvent();` with `Event` imported (`:5`) as the **Fabric** `net.fabricmc.fabric.api.event.Event`.
But `Helper.createConsumerEvent()` returns the **mod-seam** `com.warwa.seamlessportals.event.Event`
(Helper's factories were retargeted to the seam at S2 — S04 §4.1). Assigning a mod-seam `Event` to a
Fabric-`Event`-typed field is an **incompatible-types** error — a Fabric `Event` **stub would NOT fix
it** (the value is the seam type, not the stub type).

**Resolution: the F12/B1 loader-seam substitution — the SAME edit S4 applied to IPGlobal/IPCGlobal**:
`import net.fabricmc.fabric.api.event.Event;` → `import com.warwa.seamlessportals.event.Event;`
(one hunk, D4.3). The file uses `Event` only as the field type + `.invoker().accept(portal)` (`:98`),
both fully supported by the mod seam (register + invoker); no `DEFAULT_PHASE`/`addPhaseOrdering`.
This is the **only held-file edit this stage.** It is a slip-catch: the S6 landing of this file
missed the Event substitution that IPGlobal/IPCGlobal received (they were fixed at S4). Probe-verified:
after the edit, the file's only residual errors are two `ClientWorldLoader` forward-refs (U8) — the
Event line/field produce no error.

**Distinction (d) vs (3):** `DimensionIntId` KEEPS its Fabric `Event` import — it uses
`Event.DEFAULT_PHASE` (a static Identifier), which the mod seam deliberately omits; it resolves via
the Fabric `Event` STUB. `ClientPortalAnimationManagement` uses `Event` as a Helper-assigned field
type, so it MUST be the mod seam. Two different uses of the same Fabric type → two different (correct)
resolutions.

---

## 4. Config-phase decision + the irreducible addTask/completeTask residue

**Decision (task STEP 2(c)): STUB the config-phase networking types** (6 shells: PayloadTypeRegistry,
ServerConfigurationNetworking, ServerConfigurationConnectionEvents, ClientConfigurationNetworking,
ClientLoginConnectionEvents, ClientPlayConnectionEvents), typed with callback/Context signatures
matching real fabric-api (handlers typed as vanilla `ServerConfigurationPacketListenerImpl`). This
resolves ALL config PACKAGE/type errors on the `:common` probe. No runtime-routing decision is
required now (config-phase is Fabric-specific; the S0 B2 seam is play-only), so no seam.

**The irreducible residue (2 errors, documented):** `networkHandler.completeTask(ImmPtlConfigurationTask.TYPE)`
(`:194`) and `handler.addTask(new ImmPtlConfigurationTask())` (`:218`). Verified against
`26.2:ServerConfigurationPacketListenerImpl.java`: vanilla has only PRIVATE `startNextTask`/
`finishCurrentTask`/`addOptionalTasks` — **no** public `addTask`/`completeTask`. These are Fabric
**interface-injection** methods (fabric-api declares them onto the vanilla class; loom applies the
injection to the mapped MC jar). A compileOnly stub of the NETWORKING types cannot add methods to a
vanilla class, and the held code pins the receiver to the vanilla type
(`ServerConfigurationPacketListenerImpl networkHandler = context.packetListener();` at `:161`), so
completeTask cannot be re-typed without editing the held file. They are therefore irreducible on the
loader-neutral `:common` probe. This is distinct from the loader-facade TYPE debt this stage closes —
a bounded, named 2-error Fabric-injection residue, NOT a genuine qouteall forward-ref and NOT a
translation slip. (Fidelity-preserving: `ImmPtlNetworkConfig` lands byte-verbatim; NeoForge config
integration is deliberately unwired.)

**⚠ EMPIRICAL CORRECTION — they do NOT auto-resolve on the fabric loader either (verified §7).** I
ran `:fabric:compileJava -Pip_scc_closed=true` expecting real fabric-api's interface injection to
resolve addTask/completeTask. It does NOT: on the fabric probe every net.fabricmc TYPE resolves (0
unresolved — FabricLoader, EnvType, ServerTickEvents, PayloadTypeRegistry, config networking all come
from the real fabric-api on the classpath), **but addTask/completeTask STILL error** (`:194`, `:218`).
Cause: the fabric module's loom setup is minimal ("no loom mod-remapping config in use",
`fabric/build.gradle:22`) and `fabric.mod.json` declares no `injected_interfaces`, so loom provides
fabric-api's classes on the classpath but does **not** apply fabric-api's injected-interface methods
to the recompiled common source. **So these 2 methods are a genuine OPEN S13 decision, not an
auto-resolve** — the earlier optimistic "resolves at S13" is retracted. S13 options (decide then, do
NOT edit the held file now): (i) enable loom interface injection for the fabric module so fabric-api's
`ServerConfigurationPacketListenerImpl` injections apply; (ii) a mod-owned duck+`@Invoker` accessor on
the private `configurationTasks` queue / `finishCurrentTask`, with a D4.3 held-file cast in
`ImmPtlNetworkConfig` (a documented deviation); or (iii) leave `ImmPtlNetworkConfig` config-phase
unwired (Fabric-config-specific, NeoForge already unwired). This does not affect the S10-A gate — the
config-phase TYPE swath is resolved; these 2 are a characterized, bounded carry-forward.

---

## 5. F11 DimLib stub (NR-2 closed)

`qouteall.dimlib.api.DimensionAPI` landed at `common/src/ipStubs/qouteall/dimlib/api/DimensionAPI.java`.
Surface (per S04 §5 spec + the six call sites):
- `SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT` (holder) — `register(ServerDimensionsUpdateCallback)`,
  `register(Identifier phase, ServerDimensionsUpdateCallback)`, `addPhaseOrdering(Identifier,
  Identifier)`; callback SAM `run(MinecraftServer, Set<ResourceKey<Level>>)`.
- `SERVER_PRE_REMOVE_DIMENSION_EVENT` (holder) — `register(BeforeRemovingDimensionCallback)`;
  callback SAM `run(ServerLevel)`.

**Two deliberate design choices:**
1. **Home = ipStubs (BOTH classpaths), unlike the net.fabricmc stubs (§6).** DimLib genuinely has NO
   26.2 form and NO real provider anywhere — so both the `:common` probe AND the S13 loader compile
   need the stub (exactly the sodium/iris/gravity F21 situation). qouteall.dimlib is a unique
   package, so it cannot shadow anything.
2. **Self-contained (no net.fabricmc coupling).** The event holders take/return only vanilla +
   Identifier, so DimLib does not depend on the `:common`-only fabricStubs set. `Event.DEFAULT_PHASE`
   (the one Fabric value DimensionIntId feeds into `addPhaseOrdering`) is just an Identifier from the
   fabricStubs Event shell on `:common` — no coupling.

**Never-firing (S04 §5):** with static dimensions the ordering invariant `DimensionIntId.init()`
wires is preserved by the A1 init order, not these events; the static stub accepts registration but
never fires.

**S13 HANDOFF (recorded in the stub header too):** this stub is `compileOnly` (never shipped), but
`DimensionIntId.init()`/`GlobalPortalStorage.init()`/`EntitySync.init()`/… call
`DimensionAPI.*.register(...)` at RUNTIME. Since no real DimLib exists on 26.2, **at S13
`DimensionAPI` must move to SHIPPED in-tree source** (a real never-firing implementation) or the
init() calls `NoClassDefFoundError`. The net.fabricmc facade does NOT have this problem — real
fabric-api provides those types at runtime; DimLib is the one family with no runtime provider.

---

## 6. Build wiring — the `:common`-only fabricStubs source set (shadow avoidance)

**Decision: net.fabricmc.* stubs live in a NEW `fabricStubs` source set wired onto `:common`'s
compile classpath ONLY — NOT the loader classpath** (no `ipStubsClasspath` entry). The DimLib stub
stays in the existing `ipStubs` set (both classpaths).

Why split (the shadow verification the task mandated): the fabric module's OWN non-held code uses
real fabric-api — e.g. `FabricPlatformHelper` calls `PayloadTypeRegistry.clientboundPlay()`/
`serverboundPlay()`. If a `net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry` stub were on the
fabric loader COMPILE classpath (via `ipStubsClasspath`, which is always-on `compileOnly`), it could
**shadow** the real type and turn the currently-green shipping `:fabric` build red. Keeping the
net.fabricmc shells `:common`-only means the fabric build NEVER sees them → **zero shadow risk by
construction**; and at S13 the fabric loader recompiles the held tree against the REAL fabric-api for
every net.fabricmc type (best fidelity for runtime behavior, and the source of the addTask/completeTask
injections). This is a refinement of the task's "same as F21 sodium" default, driven by the task's own
"VERIFY the stub does not shadow the real annotation at the loader compile" directive.

`common/build.gradle` changes (all removed at S20):
- `sourceSets { … fabricStubs }`
- `neoForge { … addModdingDependenciesTo sourceSets.fabricStubs }` (Minecraft compile classpath for
  the shells' vanilla-typed signatures)
- `dependencies { … compileOnly sourceSets.fabricStubs.output }` (**:common only** — no loader
  `ipStubsClasspath` exposure)

**Verified:** shipping `:common:compileJava :fabric:compileJava` = BUILD SUCCESSFUL, 0 errors; both
stub source sets compiled (fabricStubs 34 classes, ipStubs DimensionAPI + 4 nested).

Stub inventory (21 shells): fabricStubs (20) = `net/fabricmc/api/{EnvType,Environment}`,
`net/fabricmc/loader/api/{FabricLoader,ModContainer,Version,VersionParsingException}`,
`net/fabricmc/loader/api/metadata/ModMetadata`,
`net/fabricmc/loader/impl/util/version/SemanticVersionImpl`, `net/fabricmc/fabric/api/event/Event`,
`…/event/lifecycle/v1/ServerTickEvents`, `…/client/event/lifecycle/v1/{ClientTickEvents,ClientChunkEvents}`,
`…/networking/v1/{PayloadTypeRegistry,ServerConfigurationNetworking,ServerConfigurationConnectionEvents}`,
`…/client/networking/v1/{ClientConfigurationNetworking,ClientLoginConnectionEvents,ClientPlayConnectionEvents}`,
`net/fabricmc/fabric/impl/attachment/AttachmentTargetImpl`, `…/impl/attachment/sync/AttachmentChange`;
ipStubs (1 new) = `qouteall/dimlib/api/DimensionAPI`.

---

## 7. Probe before/after triage (D4.2)

| Metric | BEFORE | AFTER |
|---|---|---|
| Total probe errors | 536 | **206** |
| `net.fabricmc.*` mentions | 228 | **0** |
| `qouteall.dimlib` mentions | 12 | **0** |
| `class Environment` / `variable EnvType` symbol errors | 73 / 76 | **0 / 0** |
| `package DimensionAPI does not exist` | 7 | **0** |

**The 206 remaining errors are ALL expected** (per the task's "still-red set = U9/U10/U11/U12 not yet
ported" prediction):
- **Genuine qouteall forward-refs** — `ClientWorldLoader` (U8, the NEXT S10 sub-stage: 31 refs incl.
  the S7 `dimIdToDimSeaLevel`/`dimIdToDimTypeId` handoff); render family (S11/S12): `GlQueryObject`,
  `RenderStates`, `ViewAreaRenderer`, `QueryManager`, `WorldRenderInfo`, `TransformationManager`,
  `MyGameRenderer`, `FrustumCuller`, `FrontClipping`, `FogRendererContext`, `CrossPortalEntityRenderer`,
  `Renderer{Dummy,Debug,UsingStencil,UsingFrameBuffer}`, `PortalRenderer`, + the GONE-type render
  ducks (`MultiBufferSource`/`LightTexture`/`Uniform` in IEWorldRenderer/IEGameRenderer/IEShader);
  generation/commands/wand (S13): `BreakablePortalEntity`, `FastBlockAccess`, `PortalGenInfo`,
  `CustomPortalGenManager`, `PortalWandInteraction`, `IEEndDragonFight`, `PortalDebugCommands`,
  `PortalCommand`; mixins (S10/S12): `IEServerConfigurationPacketListenerImpl` (S10 other_sync),
  `IEEntity_Collision` (S12).
- **autoconfig (cloth-config) — S13 by-design** (S04 §8.2-6): `IPConfig` (61: `@Config`,
  `ConfigData`, `ConfigHolder`, `@ConfigEntry.Gui.*`) + adjacent IPGlobal refs. NOT provisioned this
  stage (F21 autoconfig half enters the SCC only via IPConfigGUI at S13).
- **2 Fabric interface-injection residuals** — `addTask`/`completeTask` (§4).

**`:fabric:compileJava -Pip_scc_closed=true` (loader compile, real fabric-api) — 207 errors:**
`net.fabricmc.*` unresolved = **0** (real fabric-api resolves every TYPE on the loader classpath; the
`:common`-only fabricStubs never reach here — §6, no shadow); DimLib resolves via ipStubs on the
loader classpath. **addTask/completeTask STILL error** (`:194`,`:218`) — the interface injection is
not applied in this build's minimal loom setup (§4 correction). The rest are the same qouteall
forward-refs as the `:common` probe.

**Zero translation slips introduced.** Every file I touched or whose stubs I added shows only
expected forward-refs: O_O (2 PortalGenInfo forward-refs — full FabricLoader surface resolves),
ImmPtlNetworkConfig (3 IE forward-refs + 2 injection residuals — all config types resolve),
ClientPortalAnimationManagement (2 ClientWorldLoader forward-refs — the Event seam edit resolves),
SodiumInterface (2 FrustumCuller forward-refs, unchanged — no shadow).

---

## 8. Handoffs

- **S10 (next sub-stage, U8 ClientWorldLoader):** landing `ClientWorldLoader` clears the largest
  remaining forward-ref bucket (31 refs). Declare/null/consume `dimIdToDimSeaLevel` (S07 §3 handoff).
- **S13 DimLib (CRITICAL):** move `qouteall.dimlib.api.DimensionAPI` from the compileOnly ipStubs set
  to SHIPPED in-tree source (real never-firing implementation) — it is called at RUNTIME by init()
  and has no real provider on 26.2 (§5). The net.fabricmc facade needs no such move (real fabric-api
  provides it at runtime).
- **S13 config-phase (OPEN DECISION, not auto-resolve):** `addTask`/`completeTask` do NOT resolve on
  the fabric loader in this build (verified §4/§7 — minimal loom, no interface injection). Pick one at
  S13: enable loom interface injection for the fabric module; OR a mod-owned duck+`@Invoker` accessor
  with a D4.3 cast in `ImmPtlNetworkConfig`; OR leave config-phase unwired. Register
  `seamlessportals-ip-qmisc.mixins.json` etc. per S07 §8.
- **S13 fabric-loader compile:** the net.fabricmc shells are `:common`-only, so the held tree compiles
  against REAL fabric-api on the loader (fabric probe confirms every net.fabricmc type resolves, 0
  unresolved) — no ipStubs/fabric-api conflict observed at this pre-flip check.
- **S20:** delete the fabricStubs source set + its two build.gradle wirings + the DimLib ipStub, with
  the rest of the holding machinery.
