# PLAN — TESTABILITY-FIRST staged execution (entity-portal migration)

**Author lens:** earliest and densest honest user-testable increments, without violating the verified
dependency order (DEPENDENCY_ORDER §3-4: IP is one SCC, first green build at U11). Every structural
choice below cites the corpus; nothing re-derives what the corpus settled.

**Gate command (every stage ends green on it):**
`cd "C:/Users/warwa/ModDev/Portals/Portal 26.2"; .\gradlew.bat :common:compileJava :fabric:compileJava --console=plain --no-daemon`
From stage T1 onward the gate widens to include `:common:test` (the JUnit harness). Never `gradlew --stop` (briefing §2).

---

## 1. Plan-shaping decisions (U0 outputs; constraint 6)

### D1. Holding mechanism for ported-but-unclosed source: **in-place exclude list**

All ported IP source lands at its **final** path from day one — `common/src/main/java/qouteall/**`
(and tests at `common/src/test/java/qouteall/**`) — but `common/build.gradle` carries a maintained
exclude-pattern list applied to `sourceSets.main.java` (and the test set), so `compileJava` simply
does not see un-closed units:

```groovy
// build engineering, not code change — DEPENDENCY_ORDER §4 ground rules:
// "hold un-closed units in an excluded source set until their closure lands"
def IP_HELD = [
    'qouteall/**',                       // T0 state: everything held
    // narrowed per stage; emptied at T12 (U11 closure)
]
sourceSets.main.java { IP_HELD.each { exclude it } }
```

- **Why this device and not a staging module:** the source never moves (no path churn corrupting
  `git diff --no-index` against the IP tree — see D2), the flip per stage is a one-line pattern edit
  reviewed in the stage commit, and the mechanism is *monotone*: patterns only ever get narrower.
  DEPENDENCY_ORDER §4 names this exact option ("excluded source set… build engineering, not code
  change"); this makes it concrete.
- **NOT stubs, NOT simplified variants** — held files are complete, final ports that javac never
  sees until their closure lands (zero-deviation rule, briefing top + constraint 2).
- **Compile-forward rule (testability device):** at every landing stage, any subset of the landed
  unit that verifiably compiles standalone is *removed from the exclude list immediately*, so the
  green gate build carries real signal instead of nothing. Corpus-verified standalone subsets:
  the `my_util/` pure-math layer ("zero imm_ptl imports — the only genuinely standalone layer",
  DEPENDENCY_ORDER §2.1) at T1, and all duck interfaces ("compile against vanilla only… cheapest
  thing in the whole graph", DEPENDENCY_ORDER §2.9) at T3. Anything that fails the gate build goes
  back on the exclude list — the build itself is the arbiter, never a claim.
- **Mixin configs:** a ported-IP mixin class is source like any other and is held by the same list.
  The new mixin config `seamlessportals-ip.mixins.json` is only *added* at T12 when its classes
  exist (a config naming missing classes crashes at load). AW/AT entries may accumulate earlier —
  they are inert without consumers.
- **Fabric module:** `:fabric` gains no `qouteall` references before T12, keeping
  `:fabric:compileJava` trivially green through the held stages.

### D2. Namespace policy: **keep `qouteall.imm_ptl` / `qouteall.q_misc_util` verbatim**

- DEPENDENCY_ORDER §4.2: RPC FQN strings are wire protocol
  (`qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.RemoteCallables.updateEntityPos`
  etc.). Keeping IP's package names means every RPC string constant ports **unchanged** — zero
  deviation in string constants, zero rename churn.
- **Testability rationale (the deciding one for this plan):** with paths preserved,
  `git diff --no-index <IP>/src/main/java/qouteall/<unit> common/src/main/java/qouteall/<unit>`
  is a per-stage mechanical fidelity check — every hunk must be an API translation traceable to a
  slice map row (`migration/api-map/*`) or a registered forced deviation (§4 below). This is the
  cheapest possible 1:1-port verification and it works even for *held* (not-yet-compiled) units,
  giving the landing stages T4–T11 a real gate despite producing no new compiled code.
- Cross-mod wire compat is not required (new standalone mod — mission statement), but keeping the
  namespace is the max-fidelity choice. Shipping classes under `qouteall.*` in a released jar is a
  cosmetic/etiquette question deferred to a user checkpoint (§5, C5) — renaming later is mechanical
  *provided* the RPC-string audit happens with it. Mod id stays `seamlessportals`; only Java
  packages are IP-verbatim.

### D3. Flag strategy: **launch-property mixin-plugin gate, STENCIL_DIRECT pattern**

One flag: system property `seamless.entityPortals` (dev launches add
`-Dseamless.entityPortals=true`), read once at startup into `MigrationFlags.ENTITY_PORTALS`, config
file override added at T17 for the default flip. Proven pattern per memory
`stencil-direct-rework-status` and explicitly endorsed by API_RISKS (verdict §2 mitigation: "Build
the entity-portal driver behind a flag and exercise it against manually-spawned Portal entities in
a test world while the block path still serves normal play — the atomicity is in the user-facing
switch, not the development process").

Gate points — **none of them inside ported IP code bodies** (zero-deviation preserved; the flag
never forces an IP-code variant):

1. **Mixin config plugin** (`SeamlessMixinConfigPlugin`, KEEP per current-mod-core §8; ≙ IP's
   `IPMixinPlugin`): the entire `seamlessportals-ip.mixins.json` set applies only when the flag is
   ON; the block-era *driver* mixins (exclusivity ledger, T12 spec) apply only when OFF. Substrate
   KEEP mixins (stencil chain, extractor plumbing, etc. — current-mod-render §1/§3) apply always.
2. **Loader entrypoints:** `SeamlessPortalsModFabric`/`ClientFabric` call
   `IPModMain.init`/`IPModMainClient.init` only when ON; the block-era managers' tick/init entries
   only when OFF (current-mod-core §10 wiring targets).
3. **Shared host mixins** (the few mixins both paths use, e.g. the relocated pre-render pump host,
   current-mod-render §2.2): the *mod-owned mixin body* dispatches on the flag to either the old
   pump or the ported IP call chain. Mod code, not IP code.

Both drivers ship in the jar until T20. Rollback at ANY stage T13–T19 = flip the flag; rollback at
T20 = git revert only. Entity types register unconditionally from T12 (registries should not vary
per launch); flag-OFF worlds simply never spawn them. Flag-ON testing uses **dedicated test
worlds** (a world saved with `qouteall` entities loses them if later opened flag-OFF — vanilla
drops unknown entity types).

### D4. Test-infrastructure decisions

- **JUnit harness (constraint 8 deliverable):** `:common:test` wired at T1. Carries IP's
  `Mesh2DTest` verbatim (verified: no `qouteall` imports beyond its own package — compiles with the
  pure-math set) per COVERAGE INFO-2 ("a free correctness harness… porting Mesh2D without carrying
  its test over would discard free verification"). `HelperTest` imports `Helper`
  (HelperTest.java:7), which is SCC-bound (`Helper`→`McHelper`, DEPENDENCY_ORDER §2.1) — it lands
  held at T1 and is un-excluded at T12. *New* tests written at T1: DQuaternion/Plane/rotation-sign
  invariants — the regression net for the twice-bitten geometry-sign bug class (briefing §6;
  DEPENDENCY_ORDER U1 row: "the best regression net for the two past geometry sign errors").
- **Structural diff gate:** every landing stage's definition-of-done includes the D2 diff review of
  the landed unit against IP source, with each hunk attributed to a slice-map row or register entry.
- **Gametest:** `gametest/TitleCardCapture` (KEEP, "regression harness; needs updating for entity
  portals", current-mod-core §10) is extended at T15 with a server-side crossing smoke test.
- **runClient sanity script for held stages** (bit-identical builds): launch, join the standing
  block-portal test world, cross a nether portal both ways, confirm no new log lines. ~2 minutes;
  it exists to catch build-engineering mistakes (a bad exclude pattern pulling half a unit in), not
  behavior.

---

## 2. Stage table

Effort scale: S < M < L < XL (from inventory LOC + api-map changed/gone density; grounding per stage section).
"Held" = lands under D1 exclude, no new compiled code. Gate = what must be true to proceed.

| Id | Name | Contents (unit refs) | Effort | Gate |
|---|---|---|---|---|
| T0 | Plan-shaping + holding rig | U0: D1–D4 rigging, PlatformHelper seam widening design, mixin-config skeletons | M | build green; play unaffected |
| T1 | Math substrate + JUnit harness | U1: `my_util/*` compiled+tested; `Helper`/loggers/dimension held; Mesh2DTest + new sign-invariant tests | M | compile + `:common:test` green |
| T2 | Pre-render pump relocation (R2) + upkeep re-home (A4) | current-mod substrate only (API_RISKS verdict stage-1) | S | runClient: block-portal crossing regression clean |
| T3 | Ducks + roots + platform facade | U2: 36+ ducks **compiled**; IPGlobal/IPCGlobal/IPConfig/O_O/IPPerServerInfo/compat invoker bases held | M | build green; ducks compile; play unaffected |
| T4 | MC helpers | U3: McHelper/CHelper/IPMcHelper/ScaleUtils (held) | M | diff gate; play unaffected |
| T5 | Portal core + shapes + animation + collision | U4 (held); R11 decisions recorded | XL | diff gate; R11 decisions in writing |
| T6 | Network + global portals + API | U5 (held); R7 §A shape; **R1 seaLevel protocol design round** | L | diff gate; R1 design doc |
| T7 | Teleportation managers | U6 (held); R12 re-derivations; R13a | L | diff gate |
| T8 | Chunk loading + entity sync | U7 (held); R10 decisions; R13j forced deviation; R13f port-forward | L | diff gate; R10 decision in writing |
| T9 | ClientWorldLoader + init + common mixins | U8 (held); **R8 design round**; R13b audit; DEPENDENCY_ORDER §4.2 orderings | L | diff gate; R8 design doc |
| T10 | Render context + world switch | U9 (held); R1 residues; R9 design; R6 re-expressions; R13k; A3/A4 resolution | XL | diff gate; cutover-spec R4 section drafted |
| T11 | Renderers + client mixins | U10 (held); R5 sign-flip checklist; R13c re-derivation; R13i; A1→port | L | diff gate; cutover-spec R5 section drafted |
| T12 | SCC closure — FIRST GREEN | U11: commands/block_manipulation/misc + wand closure files; exclude list emptied; compile burn-down; HelperTest on; ip.mixins.json added (flag-gated); exclusivity ledger | XL | full tree compiles + tests green; flag OFF; play unaffected |
| T13 | First light (flag ON, same-dim) | bring-up fixes only; dedicated test world | M | runClient script §T13 passes |
| T14 | Cross-dim bring-up (flag ON) | bring-up fixes; graduated dest loading; WorldInfoSender; R1 seaLevel exercised | L | runClient script §T14 passes |
| T15 | Entity traffic + collision bring-up (flag ON) + gametest | bring-up fixes; forced-deviation items FD2/FD3 reproduced-then-applied; gametest extension | M | runClient script §T15 + gametest green |
| T16 | Nether portal generation | U12: NetherPortalGeneration pipeline + CustomPortalGeneration + breakables + ignition mixins | XL | runClient script §T16 passes |
| T17 | CUTOVER — default flag ON | flag default flip; block driver dormant; **R4+R5 sign-off (cutover spec)**; full 12-point regression | M | all 12 checklist items pass flag-ON |
| T18 | CrossPortalEntityRenderer (R3) | design round + port (trailing periphery per API_RISKS verdict §3) | L | runClient script §T18 passes |
| T19 | Peripheral tail (user checkpoint) | U13: wand/dim-stack/alt-dims per user decision; default core-complete | M–L | per-feature scripts |
| T20 | Old-system deletion + flag removal | U14: disposition-table deletions; PORT-FORWARD survivor verification; final full regression | L | all 12 items pass; no flag left |

21 stages. The user compiles, commits, and runs between every pair (constraint 2).

---

## 3. Stage specifications

Each stage gives (a) contents, (b) forward-ref debt status, (c) commits, (d) runClient script,
(e) applicable regression-checklist items (briefing §4), (f) rollback/flag state.

---

### T0 — Plan-shaping + holding rig (U0)

**(a) Contents** (DEPENDENCY_ORDER U0 row; constraint 6):
- D1 rigging in `common/build.gradle`: the `IP_HELD` exclude list (initially `qouteall/**`), test
  source-set mirror, and a comment block naming this plan as the authority for edits.
- D2/D3 recorded in `migration/DECISIONS.md` (new): namespace, flag, holding mechanism.
- Loader-neutral seams designed and stubbed **on the mod side only** (never IP-side): widen
  `PlatformHelper` (KEEP, current-mod-core §3) with (i) event-object factory backing
  `Helper.createRunnableEvent`-family (q-misc-util.md:321-323; R13h: "never Fabric types in
  common"), (ii) payload registration abstraction for the IP payload set (R13h renames:
  `clientboundPlay/serverboundPlay`, construct vanilla record packets directly), (iii) entity-type
  registration callback mirroring `IPModMain.registerEntityTypes(BiConsumer)` (world-loader-root §5;
  current-mod-core §3 "re-bound behind this interface (likely widened)").
- Mixin-config skeleton: reserve `seamlessportals-ip.mixins.json` name + plugin gating logic in
  `SeamlessMixinConfigPlugin` (file added T12; the plugin code paths land now, inert).
- `MigrationFlags.ENTITY_PORTALS` + property read.

**(b) Forward-ref debt:** none (U0 has none; DEPENDENCY_ORDER table).
**(c) Commits:** 1: "U0: holding rig + migration flags + PlatformHelper seams + decisions doc".
**(d) runClient:** nothing user-visible changes; verify normal play unaffected (D4 sanity script).
**(e) Checklist:** none apply (no behavior surface).
**(f) Rollback/flag:** flag OFF (and nonexistent behavior-wise); revert = git revert one commit.
**Effort M** — build engineering + interface design; no IP LOC.

---

### T1 — Math substrate + JUnit harness (U1)

**(a) Contents** (DEPENDENCY_ORDER U1 row):
- **Compiled immediately** (exclude list narrowed): `qouteall/q_misc_util/my_util/**` pure math —
  `Plane` (146 LOC), `DQuaternion` (545), `AARotation` (214), `IntMatrix3` (108), `GeometryUtil`
  (384), `Mesh2D` (1753 — largest class in slice, q-misc-util.md:168), `QuadTree` (193), `IntBox`
  (540), small geometry records, `Signal*`, `ObjectBuffer`, `KeyedTaskList`, `ChangeAccumulator`,
  `MyTaskList` (307) — the corpus-verified zero-imm_ptl-import layer (DEPENDENCY_ORDER §2.1).
  `Animated`/`RenderedObject`/`GuiHelper` join the compiled set only if the gate build proves them
  standalone; otherwise held (compile-forward rule, D1).
- **Held**: `Helper` (1501 LOC; the ONLY out-edge — `Helper.java:509` → `McHelper`), loggers,
  `DimIntIdMap`/`DimensionIntId`/`DimensionIdRecord` (DimensionIntId → IPCGlobal/IPPerServerInfo/
  McHelper, DEPENDENCY_ORDER cycle 1), `MiscGlobals`.
- **Tests (explicit deliverable, constraint 8):** carry `Mesh2DTest` verbatim (compiles now);
  land `HelperTest` held; write new invariant tests for `DQuaternion` (rotation composition,
  conjugate-inverse, `fromEulerAngle` axis conventions) and `Plane`/`GeometryUtil` (signed-distance
  and normal-orientation conventions) — the regression net for the geometry-sign bug class
  (briefing §6: "never trust a prior geometry-sign claim").

**(b) Debt:** `Helper`→`McHelper` (1 call) is the unit's only debt; it is *held*, so the compiled
set has zero debt.
**(c) Commits:** 1: "U1: q_misc_util math layer (compiled) + held Helper/dimension"; 2: "U1 tests:
Mesh2DTest carried + sign-invariant suite".
**(d) runClient:** nothing user-visible changes; verify normal play unaffected. The *real* test is
headless: `.\gradlew.bat :common:test` green — pulled before any MC boot (DEPENDENCY_ORDER §4.1
milestone 1).
**(e) Checklist:** none (headless).
**(f) Rollback/flag:** flag OFF; revert = 2 commits.
**Effort M** — ~4.7k LOC mostly mechanical (pure math has near-zero 26.2 API surface) + test authoring.

---

### T2 — Pre-render pump relocation (R2) + upkeep re-home (A4) — first runClient-exercised stage

**(a) Contents** — current-mod code only; zero IP source; this is API_RISKS verdict stage-1
("preparatory refactors… can all ship and soak while block portals still serve players"):
- **R2 design round output (scheduled work, constraint 4):** reconcile the frame-phase anchor.
  Candidates per API_RISKS R2: the mod's existing `GameRendererFrameCrossingMixin` at
  `GameRenderer.update` HEAD (endorsed by current-mod-render §2.2) vs portal-animation.md #14's
  `Minecraft.renderFrame(Z)V` before the `gameRenderer.update(...)` call (26.2:Minecraft.java:1290),
  which additionally matches IP's 1.21.3 non-firing on the panorama/screenshot path
  (Minecraft.java:2779-2781). **This plan picks the `renderFrame` call-site** (the corpus's
  faithful anchor) and documents panorama behavior; the relocated host mixin later becomes the
  flag-dispatch point for the ported `manageTeleportation` chain (D3 gate point 3).
- **A4 re-home:** move the stranded per-frame upkeep (staged-upload flush, adoption prune, bridge
  repaint pump — StencilPortalRenderer.java:194-215 per current-mod-render §2.3) to the same
  pre-render anchor, verifying the GPU-upload-safe-outside-framegraph requirement
  (current-mod-render A4's deciding question — settle it here, in the commit message, not ad hoc).
- Settle the A4 ordering question *in writing* in `migration/DECISIONS.md`.

**(b) Debt:** n/a (no IP source).
**(c) Commits:** 1: "R2: crossing pump anchored at renderFrame pre-update (panorama documented)";
2: "A4: pre-render upkeep re-homed to the R2 anchor".
**(d) runClient (behavior-preserving — verify NO change):** standing block-portal world.
1. Build/light a nether portal; cross forward, backward, and strafing 10× each way. Correct: smooth
   both directions, motion-side exits, no oscillation. Failure: one-frame late camera (the R2 bug
   shape: "teleports one frame late", API_RISKS R2), hand glitch, FOV pulse.
2. Take a panorama screenshot (F2 world panorama via debug) — no crash, no crossing triggered.
3. Elytra-boost through the portal — no phantom rocket (guards untouched but path relocation could
   regress ordering).
**(e) Checklist:** 1, 2, 6, 8 (lateUpdateLight ordering untouched but same frame region), 11, 12.
**(f) Rollback/flag:** flag OFF; revert = 2 commits; block system remains sole driver.
**Effort S** — small diff, high verification density.

---

### T3 — Ducks + roots + platform facade (U2)

**(a) Contents** (DEPENDENCY_ORDER U2 row):
- **Compiled immediately:** ALL duck interfaces (36 + q_misc_util ducks + cross-slice accessor
  interfaces — "compile against vanilla only… kill dozens of forward edges", §2.9), plus any other
  gate-proven standalone members (`PerformanceLevel` enum is a candidate; the build decides).
- **Held:** `IPGlobal`, `IPCGlobal`, `MiscHelper`, `mc_utils` (`ServerTaskList`,
  `MyNbtTextFormatter` — R12: re-derive against `SnbtPrinterTagVisitor`, ducks G8), `IPConfig`+`O_O`,
  `IPFeatureControl` (the 1 peripheral closure file, cycle 9), compat invoker bases
  (`GravityChangerInterface`, `SodiumInterface`, `IrisInterface`, `IPPortingLibCompat` —
  compile-mandatory shells even with no Sodium/Iris on 26.2, platform-compat-peripheral:245),
  `IPPerServerInfo`, `DimensionIntId`+`DimensionIdRecord`, `IPMixinPlugin` (as reference; the mod's
  `SeamlessMixinConfigPlugin` is the live plugin, current-mod-core §8).
- **R13g partial:** the DimLib event-wiring answer — static-dimension stub of the *event wiring
  only* (DEPENDENCY_ORDER §2.1 R-edge; COVERAGE INFO-4) — designed here, since `DimensionIntId`
  consumes it.

**(b) Debt (held members):** `O_O`→{`Portal` U4/T5, `ImmPtlClientChunkMap` U7/T8,
`ImmPtlNetworkConfig` U5/T6, `PortalGenInfo` U9-shell/T12}; `IPConfig`→`BlockPortalShape` (U4/T5
co-port); `IPPerServerInfo`→{`ServerTeleportationManager` U6/T7, `CustomPortalGenManager` head
T12, `PortalWandInteraction` T12 co-port} (DEPENDENCY_ORDER U2 row). Compiled ducks: zero debt.
**(c) Commits:** 1: "U2: duck interfaces (compiled) + AW/AT additions"; 2: "U2: platform facade +
config shells (held) + DimLib event-wiring stub design".
**(d) runClient:** nothing user-visible changes; verify normal play unaffected (D4 sanity).
**(e) Checklist:** none.
**(f) Rollback/flag:** flag OFF.
**Effort M** — large file count, thin files; ducks are interfaces (slice total ~8.4k LOC but most
of the heavy members are held-and-small; ducks-api-misc.md:6).

---

### T4 — MC helpers (U3, held)

**(a) Contents:** `McHelper` (~965 LOC), `CHelper` (~193), `IPMcHelper` (~332), `ScaleUtils`
(~188), mc_util entity-traversal mixin *sources* (`IELevelEntityGetterAdapter` et al.)
(DEPENDENCY_ORDER U3 row; world-loader-root.md §2). Heavy mechanical-rename territory (absorbed by
slice maps, API_RISKS preamble) + R13d: `getMaxYExclusive/getMaxSectionYExclusive` wrappers get the
+1 inclusivity fix **in the wrappers, never call sites** (API_RISKS R13d).
**(b) Debt:** `Portal` (T5), `GlobalPortalStorage` (T6), `CrossPortalEntityRenderer` (T11) —
all held; nothing new compiles.
**(c) Commits:** 1: "U3: McHelper/CHelper/IPMcHelper/ScaleUtils (held) — R13d wrapper fix".
**(d) runClient:** nothing user-visible changes; verify normal play unaffected.
**(e) Checklist:** none. **Gate:** D2 structural diff review (every hunk = slice-map row).
**(f) Rollback/flag:** flag OFF.
**Effort M** — ~1.7k LOC, dense rename surface, no design questions.

---

### T5 — Portal core + shapes + animation + collision (U4, held)

**(a) Contents** (DEPENDENCY_ORDER U4 row): `Portal` family (`Mirror`, `BreakableMirror`,
`EndPortalEntity`), `PortalState`, `PortalExtension`, `PortalManipulation`, `PortalUtils`,
`shape/*`, `util/*`, whole `animation/` package (incl. `StableClientTimer`,
`ClientPortalAnimationManagement`), `PortalPlaceholderBlock`, `LoadingIndicatorEntity`,
`PortalRenderInfo`; **co-ports:** `BlockPortalShape` (cycle 14) and
`CollisionHelper`+`PortalCollisionHandler`+`PortalCollisionEntry` (cycle 15 — common-side edge,
must be in U4's closure).
**R11 decision round (owning stage, constraint 4)** — record all four in `migration/DECISIONS.md`
before porting the affected files (API_RISKS R11 "mechanical once four decisions are recorded"):
1. `DataFixTypes` constant for `SavedDataType` (silent-data-loss trap; there is no NONE) — decision
   documented here, applied at T6 (GlobalPortalStorage).
2. `EntitySpawnReason` per call site: LOAD for deserialize, DIMENSION_TRAVEL for the recreate path.
3. Entity-type id plumbing: `EntityType.Builder.build(ResourceKey)` restructure of
   `createPortalEntityType` (portal-core C10).
4. Tracking-range conversion: Fabric `trackRangeBlocks(96)` ≙ `clientTrackingRange(6)` **chunks** —
   verify against Fabric API's conversion before hardcoding (current-mod-core §11.6).
Plus the bounding-box inversion audit: every geometry-field write must push `setBoundingBox`
(portal-core hazard 1 — "a missed push leaves a stale bb vanilla happily ticks on").
**(b) Debt:** `ImmPtlNetworking`+`PortalAPI` (T6), `ServerTeleportationManager` (T7),
`PortalRenderer` + `QueryManager`/`RenderStates`/`WorldRenderInfo`/`ViewAreaRenderer`/
`FrustumCuller` (T10/T11), `PortalCommand.raytracePortals` (T12 co-port).
**(c) Commits:** 1: "U4 decisions: R11 quartet + bb-push audit rules"; 2: "U4: Portal family +
shapes + util (held)"; 3: "U4: animation package (held)"; 4: "U4 co-ports: BlockPortalShape +
collision statics (held)".
**(d) runClient:** nothing user-visible changes; verify normal play unaffected.
**(e) Checklist:** none yet (code inert). **Gate:** diff review + R11 decisions in writing.
**(f) Rollback/flag:** flag OFF.
**Effort XL** — ~10.6k LOC (portal-core 6,374; animation 2,639; collision co-port ~1,085;
BlockPortalShape ~513) + the highest hazard density in the port (portal-core §5 hazards).

---

### T6 — Network + global portals + API (U5, held)

**(a) Contents** (DEPENDENCY_ORDER U5 row): `MiscNetworking`, RPC
(`ImplRemoteProcedureCall`+`McRemoteProcedureCall` — FQN strings valid verbatim under D2),
`PacketRedirection`+`PacketRedirectionClient`, `ImmPtlNetworking`, `ImmPtlNetworkConfig`;
co-ports `global_portals/*` (cycle 10) and `api/` (`PortalAPI`, `ImmPtlEntityExtension`, cycle 11).
- **R7 implemented to the corpus's exact shape:** network.md §A order-faithful re-queue — netty
  pass schedules the outer `ClientboundCustomPayloadPacket` via
  `packetProcessor().scheduleIfPossible(...)` + cancel; game-thread re-invocation handles inline;
  narrowed `wrapRunnable`/`scheduleExecutables` role. Never the verbatim 1.21.3 port (per-frame
  cross-queue reordering hazard — the respawn-mislabel/walking-limbo bug class). isSameThread
  guards on every packet-handler mixin (briefing §6).
- **R1 seaLevel protocol design round (scheduled work; MUST be design-complete before any flag-ON
  run, API_RISKS verdict gating):** extend the dim-sync channel to carry per-dim sea level for
  not-yet-visited dimensions (world-loader-root §2 ClientLevel-ctor row). Design lands here (the
  channel is this unit's code); consumption lands T10 (`ClientWorldLoader`).
- R11 decision 1 applied: `GlobalPortalStorage` → `SavedDataType` with the chosen DataFixTypes
  constant + a **paranoia load-log** (the failure mode is silent data loss, portal-generation G1) —
  the log line is mod-side observability, not an IP-code change.
- Login/dim-id ordering preserved per DEPENDENCY_ORDER §4.2 (DimIdSyncPacket mid-login before the
  difficulty packet; global-portal sync after).
**(b) Debt:** `ServerTeleportationManager` (T7), `ImmPtlChunkTracking`/`ChunkLoader` (T8).
**(c) Commits:** 1: "U5: RPC + redirection (network.md §A shape) (held)"; 2: "U5: ImmPtlNetworking
+ global portals + PortalAPI (held)"; 3: "R1 design: seaLevel dim-sync extension".
**(d) runClient:** nothing user-visible changes; verify normal play unaffected.
**(e) Checklist:** none. **Gate:** diff review + R1 design doc committed.
**(f) Rollback/flag:** flag OFF.
**Effort L** — ~1.1k network LOC + ~1.1k global portals + PortalAPI; R7's §A shape is specified,
not open-ended.

---

### T7 — Teleportation managers (U6, held)

**(a) Contents** (DEPENDENCY_ORDER U6 row): `TeleportationUtil`, `CrossPortalSound`,
`ClientTeleportationManager` (~721 LOC), `ServerTeleportationManager` (~846) — collision statics
already co-ported at T5 (cycle 15); review them alongside this unit per the U6 row note.
- **R12 discipline:** every `@IPVanillaCopy` body **re-derived line-by-line from 26.2** (never
  patch 1.21.3 copies): `Entity.collide`/`collideBoundingBox`/`collideWithShapes` (dynamic
  `axisStepOrder`, `collectCollidersIgnoringWorldBorder`, step-up epsilon), anticheat →
  `isEntityCollidingWithAnythingNew` + `getPreMoveCollisions`, interpolation kills →
  `InterpolationHandler` + `snapTo` + `getPositionCodec().setBase` gated by
  `isLocalInstanceAuthoritative` (the movement-dedup lesson), `ServerPlayer.removeVehicle()`
  retarget for the riding bypass (IP's `stopRiding` bypass is INEFFECTIVE as written —
  teleportation-collision CHANGED row 4). The `checkInsideBlocks` movement-path clip **needs its
  design note here** (R12: "the bb-redirect has no anchor") — resolved against the per-segment
  box/path before the mixin sources land at T9.
- **R13a:** ender-pearl mixin intercepts/replaces vanilla's now-native cross-dim branch, not adds
  a case (teleportation-collision C17).
- FD2/FD3 hooks (attached-firework skip, transient-hurt carry) are **NOT applied yet** — they are
  reproduce-then-apply items at T15 (forced-deviation register discipline).
**(b) Debt:** `ImmPtlChunkTracking` (T8), `TransformationManager`+`MyGameRenderer`+`RenderStates`/
`FogRendererContext` (T10, client-only paths).
**(c) Commits:** 1: "U6: TeleportationUtil + managers (held), R12 re-derivations"; 2: "R12 design
note: checkInsideBlocks per-segment clip".
**(d) runClient:** nothing user-visible changes; verify normal play unaffected.
**(e) Checklist:** none. **Gate:** diff review; every `@IPVanillaCopy` hunk cites its 26.2 source
line in the port comment.
**(f) Rollback/flag:** flag OFF.
**Effort L** — ~2k LOC but the re-derivation rule makes it slow-per-line.

---

### T8 — Chunk loading + entity sync (U7, held)

**(a) Contents** (DEPENDENCY_ORDER U7 row): `ImmPtlChunkTracking` (~669), `ChunkVisibility`
(~232), `ImmPtlChunkTickets` (~337), `PlayerChunkLoading` (~243), `EntitySync` (~79),
`WorldInfoSender` (~100), `DimensionalChunkPos`, `PerformanceLevel`, perf monitors,
`ImmPtlClientChunkMap` — **base: the repo's already-ported `SeamlessClientChunkMap`**
(current-mod-core §5: "It already IS the IP class… extended with the mod's 26.2 delta-tracking
surface"), installed via the `ClientLevel`-constructor hook covering EVERY client world including
main (the faithful port point — NOT the secondary-world factory; current-mod-core §5).
- **R10 decision round (owning stage, constraint 4):** ticket registration through the KEEP'd
  `TicketTypeInvoker` at registry-bootstrap phase (not first-use static init); **flag-bits
  decision** (FLAG_LOADING vs FLAG_SIMULATION — piglin-flood lesson vs IP simulation semantics,
  current-mod-core §11.5) recorded in DECISIONS.md; the `PlayerTicketTracker` takeover's new
  uncovered path (`DistanceManager.addPlayer` direct PLAYER_SIMULATION ticket) covered per
  mixin-common §2 warning.
- **R13j forced deviation registered (FD1):** `WorldInfoSender` shrinks to weather-only.
- **R13f:** the SOG delta feed / store-center machinery ports FORWARD into `ImmPtlClientChunkMap`
  (chunk-loading #45/#46) — carried survivor, listed in the T20 survivor manifest.
**(b) Debt:** client half touches `ClientWorldLoader` per-dim renderers — **runtime-only** edge
(DEPENDENCY_ORDER U7 row), so no compile debt beyond the standing held set.
**(c) Commits:** 1: "U7: tracking/tickets/visibility/entity-sync (held); R10 decisions"; 2: "U7:
ImmPtlClientChunkMap from SeamlessClientChunkMap (held); FD1 registered".
**(d) runClient:** nothing user-visible changes; verify normal play unaffected.
**(e) Checklist:** none. **Gate:** diff review + R10 decision in writing.
**(f) Rollback/flag:** flag OFF.
**Effort L** — ~2.2k LOC + two decision rounds.

---

### T9 — ClientWorldLoader + init sequences + common mixins (U8, held)

**(a) Contents** (DEPENDENCY_ORDER U8 row): `ClientWorldLoader` (~634 — with the R1 residues
placeholder-commented until T10 lands the render context it needs), `IPModMain`/`IPModMainClient`
init sequences **exactly** per DEPENDENCY_ORDER §4.2 (MiscUtilModEntry → IPModMain member order
:67-:146 → IPModMainClient member order :74-:134; client tick order; server tick order), ALL 74
common mixins as held sources (mixin-common slice).
- **R8 design round + port (owning stage, constraint 4):** position-packet dimension stamp — pick
  the codec-wrap idiom (`@ModifyExpressionValue` on the `StreamCodec.composite` call appending
  `writeResourceKey`/`readResourceKey`) or the paired-packet alternative; **server+client halves
  change together** (API_RISKS R8). Decision recorded, both halves land here (client counterpart
  source held with mixin-client at T11 if IP hosts it there — the lock-step is the requirement).
- **R13b audit:** every IP cross-dim patch checked for obsolescence-by-vanilla
  (`EntityReference`/`getEntityInAnyDimension`; projectile-owner redirect = drop candidate;
  `lastHurtByMob`/`thrower` shadow retype).
- The R12 `checkInsideBlocks` design note from T7 is implemented in `MixinEntity` here.
**(b) Debt:** `DimensionRenderHelper`/`PortalRendering` (T10) — cycle 7; co-scheduled per the U8
row note.
**(c) Commits:** 1: "U8: ClientWorldLoader + IPModMain[Client] init order (held)"; 2: "U8: 74
common mixins (held); R8 stamp both halves; R13b audit results".
**(d) runClient:** nothing user-visible changes; verify normal play unaffected.
**(e) Checklist:** none. **Gate:** diff review + R8 decision doc + R13b audit table committed.
**(f) Rollback/flag:** flag OFF.
**Effort L** — the mixin volume is large but each is small; the init-order transcription is
mechanical against §4.2.

---

### T10 — Render context + world switch (U9, held)

**(a) Contents** (DEPENDENCY_ORDER U9 row — the 26.2 rewrite hotspot): `context_management/*`
(`RenderStates`, `WorldRenderInfo`, `PortalRendering`, `FogRendererContext`,
`StaticFieldsSwappingManager`, `DimensionRenderHelper`, `CloudContext`), `MyGameRenderer`,
`MyRenderHelper`, `FrontClipping`, `ShaderCodeTransformation`, `TransformationManager`,
`GlQueryObject`/`QueryManager`, `ImmPtlViewArea`, `VisibleSectionDiscovery`, `FrustumCuller`,
`ForceMainThreadRebuild`, `GLResourceCache`, `GuiPortalRendering`, `ViewAreaRenderer`.
(`CrossPortalEntityRenderer` is listed in U9's inventory but its *design+port trails the cutover*
per constraint 4 / API_RISKS verdict §3 — at this stage it lands as the **unmodified IP source,
held**, so U10/U11 forward references resolve at closure; its 26.2 re-expression is T18. Its
runtime entry points stay behind IP's own `cross_portal_entity_rendering` toggle until T18.)
- **Transplant, don't reinvent (PORT-FORWARD survivors into ported shells, constraint 5):**
  `PortalContextSwitch`'s 26.2 choreography into `MyGameRenderer`+context_management
  (current-mod-render §1.2: extract/compileSections pairing, SOG delta feed, fog-buffer/UBO
  isolation, grid pinning "inside the ported shell"); mod `DimensionRenderHelper` (already the
  prescribed 26.2 Lightmap shape, §1.8); mod `VisibleSectionDiscovery` (§1.5); `FrontClipping`
  (§1.6 — do NOT "fix" `Vector4f.mul` to `mulTranspose`; the row-vector note in
  IP_DEVIATIONS_ANALYSIS is itself wrong); mod `ShaderCodeTransformation` wiring (§1.7);
  `PortalRenderBuffersPool` + `endFramePooled` (§1.10 — GPU-leak rule); `PortalInnerCull` →
  `FrustumCuller` with IP's corner sourcing (§1.9).
- **R1 residues implemented:** per-dim `LevelRenderState`/`LevelExtractor` choreography from
  `PortalContextSwitch` into the ported `ClientWorldLoader`+`MyGameRenderer` pair (API_RISKS R1
  "solved by mod — the single biggest asset"); seaLevel consumption (T6 design); **remote-tick
  placement decision** (LevelRenderer.tick() gone — design-stage placement question, R1) recorded.
- **R9 design round:** fog UBO ownership for the second world + `FogEnvironment`/probe isolation
  (UNKNOWN-NEEDS-DESIGN, render-sub G2 + mixin-client MixinFogRenderer row); lightmap side is
  mod-solved (R9 "essentially yes").
- **R6 re-expressions:** ViewAreaRenderer/MyRenderHelper/Overlay meshes onto `PortalRenderTypes`
  `drawMesh` + pipeline registration (all patterns cited, API_RISKS R6 "every piece has a cited
  pattern"); premultiplied blend via 4-factor `BlendFunction` ctor.
- **R13k:** camera pull-model — mod `CameraInvokerMixin` `initialized` flag; view-rotation
  post-processing of `cameraState.viewRotationMatrix` after extract (never wrap
  `getViewRotationMatrix`).
- **A3 resolved here:** the one-time comparative read of `ForceMainThreadRebuild` vs the mod's
  budgeted compile scheduling (current-mod-render A3) — outcome recorded; default: keep the mod's
  scheduling as required 26.2 adaptation.
- **Cutover-spec §R4 drafted (constraint 4 — inside the cutover stage spec):** the ViewArea
  fidelity decision. Options per API_RISKS R4: rebuild `ImmPtlViewArea` on 26.2 (subclass ViewArea
  — public ctor/overridable `repositionCamera`/`getRenderSectionAt` survive — backed by a mod-owned
  unbounded store, or replace `new ViewArea` in `invalidateCompiledGeometry`) vs keep the
  pinned-bounded deviation (documented latent multi-portal >71-chunk collision bug).
  **This plan's default: rebuild ImmPtlViewArea (fidelity + kills the latent bug).** The decision
  text lives in the cutover spec (T17 section below) and is implemented here.
**(b) Debt:** `PortalRenderer` family (T11) for a few call sites (DEPENDENCY_ORDER U9 row).
**(c) Commits:** 3-5 commits: context_management; MyGameRenderer+world-switch transplant;
ViewArea/discovery/culling; R9 fog design; decisions.
**(d) runClient:** nothing user-visible changes; verify normal play unaffected (block render path
untouched — transplant *copies* mechanics into held shells; the live block path keeps its code
until T20).
**(e) Checklist:** none live yet; item 11's rules (endFrame, no render-thread LOGGER — route
diagnostics through `rlog`, COVERAGE INFO-1) are enforced in review now.
**(f) Rollback/flag:** flag OFF.
**Effort XL** — largest unit: render-sub ~2.2k + render-core bulk + three design rounds.

---

### T11 — Renderers + client mixins (U10, held)

**(a) Contents** (DEPENDENCY_ORDER U10 row): `renderer/*` — abstract `PortalRenderer` (~360),
`RendererUsingStencil` (~313), `RendererUsingFrameBuffer` (~136), debug/dummy, Iris-compat renderer
shells (cycle 12 — compile-mandatory: abstract `PortalRenderer` imports them); all ~62 client
mixins (multiworld + render halves).
- **A1 resolves to PORT by compile necessity + fidelity:** `IPCGlobal` holds all four renderer
  instances and `IPModMainClient` constructs them (current-mod-render A1) — the classes are needed
  for closure regardless; `renderMode` config ports with them. Runtime verification of
  `compatibility` mode trails the cutover (API_RISKS verdict §3).
- **Cutover-spec §R5 drafted (constraint 4):** the reversed-Z/stencil sign-flip checklist —
  every depth constant/comparison in `clearDepthOfThePortalViewArea`/`restoreDepthOfPortalViewArea`
  flipped for clear=0.0=far / `GREATER_THAN_OR_EQUAL` (API_RISKS R5 "NOT yet done"), stencil ops
  onto the proven mod substrate (GlBackendMixin chain — KEEP, runtime-proven), `QueryManager` stays
  raw GL, Vulkan gap documented with the `PortalRenderInfo` occlusion-query degrade path.
  **Discipline:** re-derive every sign from 26.2 source, never trust prior claims (briefing §6);
  each flip is a checklist row verified at T13 first light.
- **R13c:** every synthetic-lambda anchor re-derived from the compiled 26.2 jar (mapped pass
  lambdas: addMainPass :391, clear :197, weather :474, sky :329).
- **R13i:** fabulous suppression override re-anchored onto `GameRenderState.useShaderTransparency()`
  (checklist row so it is not silently dropped).
- **R2 integration point:** the ported `MixinGameRenderer`-equivalents route through the T2 anchor
  (mod-owned dispatch, D3 gate 3); IP's bobView scaling trio lands (per current-mod-render §2.1,
  REPLACE of `MainProjectionBobMixin` — a user-visible change *when flag ON*: bob returns away from
  portals; noted at T17).
**(b) Debt:** `BlockManipulationClient` + `PortalCommand`/argument types → T12 closure co-ports
(DEPENDENCY_ORDER U10 row). This is the LAST debt in the SCC.
**(c) Commits:** 1: "U10: PortalRenderer family + renderMode (held); R5 checklist"; 2: "U10: 62
client mixins (held); R13c anchors re-derived; R13i row".
**(d) runClient:** nothing user-visible changes; verify normal play unaffected.
**(e) Checklist:** none live. **Gate:** diff review + R5 checklist doc + cutover-spec §R5 committed.
**(f) Rollback/flag:** flag OFF.
**Effort L** — ~1k renderer LOC + heavy mixin re-anchoring (R13c makes it slow).

---

### T12 — SCC closure: FIRST GREEN BUILD (U11)

**(a) Contents** (DEPENDENCY_ORDER U11 row): `commands/*` (`PortalCommand` 2,853 LOC,
`PortalDebugCommands` 820, `ClientDebugCommand` 921, `PortalAnimationCommand` 655, argument types),
`block_manipulation/*`, `miscellaneous/` (GcMonitor, initial screen), `debug/`, the two peripheral
closure files `PortalWandInteraction`+`CommandStickItem` (cycle 9), generation shells
`PortalGenInfo`+`CustomPortalGenManager` head (cycles 8/14). Then:
1. **Empty the exclude list** — the whole `qouteall` tree enters `compileJava`.
2. **Compile burn-down loop:** iterate to green. Budget for real days: this is where every held
   unit's API translation meets javac for the first time. The D2 diff gates make errors local
   (each file was already reviewed); the burn-down is expected to be mostly missed renames and
   AW/AT gaps, not design faults.
3. `seamlessportals-ip.mixins.json` added, plugin-gated OFF-by-default (D3 gate 1).
4. Fabric entrypoint wiring behind the flag (D3 gate 2); entity types + placeholder block +
   argument types + payload registrations land unconditionally (D3; registries don't vary by flag).
5. `HelperTest` un-excluded; `:common:test` green including it.
6. **Exclusivity ledger committed** (the T13+ dual-driver contract): flag ON suppresses the
   block-era *drivers* — `LocalPlayerMixin` crossing detection, `EntityMixin` server detection +
   `PortalTeleporter`, `PortalManager`/`PortalDetector` scans, `PortalChunkTracker` tick,
   `PortalEntityTracker`, `SeamlessClient/ServerTeleport`, `StencilPortalRenderer`/
   `PortalContextSwitch`/`PortalWorldManager` entry, `RemoteBlockUpdater` — and leaves block-era
   *suppressions* active (the `handlePortal` cancel keeps vanilla nether portal blocks inert in
   flag-ON worlds until T16 replaces it with IP's structural suppression). Flag OFF applies none
   of the IP mixin set and skips IP init. Substrate KEEPs apply always.
**(b) Debt:** **none — SCC closed** (DEPENDENCY_ORDER U11: "⬅ FIRST GREEN BUILD").
**(c) Commits:** 1: "U11: commands/blockmanip/misc + wand closure (held)"; 2: "SCC closure:
exclude list emptied + burn-down fixes"; 3: "ip.mixins.json (flag-gated) + entrypoint wiring +
exclusivity ledger"; 4: "HelperTest enabled".
**(d) runClient (flag OFF — the default):** nothing user-visible changes; verify normal play
unaffected — full block-portal session: build nether portal, cross both ways, throw items through,
shoot arrows through, elytra-boost through. This proves the closure landed inert.
**(e) Checklist:** 1-6, 8, 11, 12 as *flag-OFF regression* (the old system must be untouched).
**(f) Rollback/flag:** flag OFF default; ON available for dev. Rollback = revert stage commits
(pre-T12 builds simply didn't compile the tree; post-T12 the flag protects users).
**Effort XL** — ~5.5k LOC of commands/misc + the burn-down + the ledger. The single longest stage.

---

### T13 — First light: flag ON, same-dimension portals

**(a) Contents:** bring-up defect fixes only (whatever the script below exposes), each committed
individually. Same-dim portals deliberately first: they exercise Portal entity spawn/sync
(`PortalSyncPacket`), `PortalRenderer`/`RendererUsingStencil`, `ViewAreaRenderer`, collision, and
client teleport — **without** cross-dim chunk loading, dim-sync, or secondary ClientLevel
construction, isolating the R5 sign flips and the driver core from R1/R10 machinery.
**(b) Debt:** none (post-closure).
**(c) Commits:** "first-light fix: <defect>" series; final "T13: first-light sign-off".
**(d) runClient (flag ON, NEW dedicated test world — creative, superflat):**
1. `/portal make_portal 3 3 minecraft:overworld shift 20` (PortalCommand utility group syntax:
   `make_portal <w> <h> <toDim> (<dest>|shift <dist>)`, ducks-api-misc.md §2.3). Correct: a 3×3
   see-through window appears in front of you showing terrain 20 blocks behind it, stable at all
   view angles; no Z-fighting, no inverted depth (R5 failure shape: portal shows sky/void or draws
   in front of everything), no curtain. Failure modes to log: black/empty view (view-area mesh or
   stencil masks broken), inside-out view (sign flip missed), crash on spawn (entity-type/NBT R11).
2. Walk through it. Correct: seamless reposition 20 blocks away, no camera snap, hand steady,
   sprint preserved. Backward + strafe crossings too (motion-keyed exits, item 1).
3. `/portal view_portal_data` pointing at it — NBT dump renders; `/portal set_portal_destination`,
   `set_portal_scale 2`, `set_portal_rotation` variants — view updates live.
4. `/portal complete_bi_way_portal` — return portal appears; cross back and forth 10×; no
   oscillation (item 1).
5. `/imm_ptl_client_debug render_mode_debug` then `_normal` — debug renderer draws; mode switch
   works (A1 ported set present).
6. F3 overlay + `/portal debug report_player_status`; relog into the world (item 12); verify a
   fresh session is clean.
7. Flip flag OFF, open the *block-portal* world: normal play unaffected (dual-driver honesty).
**(e) Checklist:** 1, 2 (same-dim form), 7 (straddle render — partial, CrossPortalEntityRenderer
absent is status quo per API_RISKS verdict §3), 11, 12.
**(f) Rollback/flag:** flag OFF default; ON only in the test world. Rollback = stop using the flag.
**Effort M** — script is cheap; the unknown is defect volume (reserved).

---

### T14 — Cross-dim bring-up: flag ON, nether portals by command

**(a) Contents:** bring-up fixes for the cross-dim path: secondary `ClientLevel` construction
(R1 seaLevel protocol *first exercised here*), `PacketRedirection` transport (R7 §A ordering),
`ImmPtlChunkTracking`/tickets (R10 flags), `WorldInfoSender` weather (FD1), graduated dest loading
(memory `ip-dest-loading-model`: dest preps over time — no instant-from-cold, ~8 chunks default;
NOT a defect).
**(b) Debt:** none.
**(c) Commits:** fix series + "T14: cross-dim sign-off".
**(d) runClient (flag ON, test world):**
1. `/portal make_portal 4 4 minecraft:the_nether 0 70 0`. Correct: nether view fades in over a few
   seconds as chunks arrive (graduated loading — the corpus says gradual is FAITHFUL); portal-view
   lighting correct BEFORE any crossing (item 8 — lateUpdateLight); fog/sky in the window is
   nether-correct, no fog bleed into the overworld frame (R9).
2. Cross into the nether; play 2 minutes; break/place blocks (remesh must work — item 9); cross
   back. Correct: no chunk holes, no limbo bands, no distant-chunk vanish either side (item 9);
   no freeze (memory: crossing-resend-storm class).
3. Make a LARGE portal (`/portal make_portal 10 6 ...`) and a portal at negative coords
   (`/portal make_portal 3 3 minecraft:the_nether -128 70 -128`); cross both (item 10).
4. `/portal debug report_chunk_loaders` + `report_per_player_chunk_loading` — loader radii sane,
   collapse after walking away (ticket lifecycle).
5. Weather: `/weather rain` in overworld, stand in nether, look through — overworld rain state
   correct through the window; time-of-day mismatch is EXPECTED (FD1, weather-only
   WorldInfoSender).
6. Relog while standing next to the nether-side portal (item 12).
**(e) Checklist:** 1, 2, 8, 9, 10, 12.
**(f) Rollback/flag:** flag OFF default. **Effort L** — this stage owns the R1/R7/R10 runtime
unknowns (API_RISKS R7: "where silent cross-dimension state corruption enters"); also the
empirical test of §11.7 (IP's no-ACK model vs the walking-limbo lesson — if drops reproduce, a
reliability layer re-enters via the register, FD-candidate).

---

### T15 — Entity traffic + collision bring-up (flag ON) + gametest

**(a) Contents:** bring-up fixes for non-player crossings via the ONE unified path
(`Portal.SERVER_PORTAL_TICK_SIGNAL` → `getEntitiesToTeleport` → `teleportRegularEntity`,
current-mod-core §2); **FD2/FD3 reproduce-then-apply** (attached-firework skip + transient-hurt
carry: reproduce the vanilla-IP bug on the ported path FIRST, then apply the documented port-local
patch — current-mod-core §11.3/§11.4); gametest extension: automated server-side crossing smoke
(spawn portal + item + assert arrival) in `gametest/` (D4).
**(b) Debt:** none.
**(c) Commits:** fix series; "FD2: attached-firework skip (reproduced <date>, applied)"; "FD3:
hurt-state carry (reproduced, applied)"; "gametest: entity crossing smoke".
**(d) runClient (flag ON, test world):**
1. Throw items through both same-dim and nether portals. Correct: land on your emergence side,
   reachable, visible through the portal immediately, no 15s invisibility, no frame-lava drift
   (item 3).
2. Shoot arrows through. Correct: continuous flight, full speed, findable (item 4).
3. Lead a cow through; shoot it first — keeps panicking after crossing (item 5; watch for the
   `Brain.getMemory` crash guard — `hasMemoryValue` first, briefing §6).
4. Elytra + firework boost through: BEFORE FD2 applied, confirm the phantom rocket reproduces
   (register evidence); AFTER, confirm gone (item 6).
5. Stand straddling the portal plane; a friend view (or F5): entity renders whole (item 7 —
   to the current status-quo standard; full two-sided render is T18).
6. Minecart/boat with passenger through (IP has vehicle crossing built in,
   `teleportVehicleAcrossDimensions` — current-mod-core §2): note results; defects here are
   backlog-grade unless crashes (briefing §5 task #11 context).
7. Run `:fabric` gametest task — crossing smoke green.
**(e) Checklist:** 3, 4, 5, 6, 7.
**(f) Rollback/flag:** flag OFF default. **Effort M.**

---### T16 — Nether portal generation (U12)

**(a) Contents** (DEPENDENCY_ORDER U12 row — genuinely layered, compiles against closed SCC):
`NetherPortalGeneration` pipeline, `NetherPortalMatcher`, `FrameSearching`, `FastBlockAccess`
(R13d sizing surface — wrapper fix from T4 pays off here), `CustomPortalGeneration` +
forms/triggers (datapack registries at server start), `BreakablePortalEntity`/
`NetherPortalEntity`/`GeneralBreakablePortal`, `IntrinsicPortalGeneration` + ignition/trigger
mixins (`MixinAbstractFireBlock_CVB` etc. — the PERIPHERAL package portion is in scope per
current-mod-core §0.2: "the port must include `imm_ptl.peripheral.portal_generation`"). IP's
structural suppression (redirected `findEmptyPortalShape`) replaces the block-era `handlePortal`
cancel in the exclusivity ledger.
**(b) Debt:** none (post-closure unit).
**(c) Commits:** 1: "U12: generation pipeline + matcher + forms"; 2: "U12: breakable portal
family + ignition mixins; ledger update".
**(d) runClient (flag ON, test world — the E2E of DEPENDENCY_ORDER §4.1 milestone 3):**
1. Build a standard obsidian frame, flint-and-steel it. Correct: `LoadingIndicatorEntity` progress
   text/particles during async dest search; then a see-through IP portal (placeholder blocks in
   frame, 4-portal cluster/bi-way behavior); NO vanilla purple blocks.
2. Cross; verify arrival frame fabricated correctly; existing-frame linking: build a matching
   frame near the expected dest first, light the origin — links instead of fabricating.
3. Odd frames: 4×5, 10×3, and a negative-coords frame (item 10 — floored dest scaling).
4. Break a frame block: portal dies cleanly (breakable revalidation), placeholder blocks drop away,
   no orphan entities (`/portal debug report_loaded_portals`).
5. First-visit stall measurement: time from ignition to usable view on a FRESH nether — expect
   graduated (no 29s residency-hold stall; briefing §5 bullet 5 context).
6. Regression re-run of the T14 script items 1-2 (generation must not disturb command portals).
**(e) Checklist:** 8, 9, 10 + generation-specific correctness above.
**(f) Rollback/flag:** flag OFF default still. **Effort XL** — ~5.5k LOC + datapack registry
wiring + async pipeline; runs on `MyTaskList` combinators landed at T1.

---

### T17 — CUTOVER: default flag ON (the atomic user-facing switch)

**(a) Contents:** flip `MigrationFlags.ENTITY_PORTALS` default to ON (config override retained);
block driver becomes dormant-but-present (rollback path); release-notes-grade summary of
user-visible changes (view-bob returns away from portals per IP `viewBobbingReduce` —
current-mod-render §2.1; nether portals are now IP-style clusters).
**This stage's spec owns R4 + R5 (constraint 4):**
- **§R4 (drafted T10, signed off here):** ImmPtlViewArea rebuilt on 26.2 subclass surface backed by
  an unbounded store (default), replacing the pinned-bounded deviation and killing the documented
  multi-portal >71-chunk latent bug (API_RISKS R4). Sign-off = T14 script item 3 (multi-portal
  same-dim far-apart dests) re-run and clean. If the rebuild proved infeasible during T10-T14, the
  pinned-bounded fallback is *promoted to the forced-deviation register* with the latent bug
  documented — not silently kept.
- **§R5 (drafted T11, signed off here):** the reversed-Z/stencil sign-flip checklist — every row
  checked against behavior at T13-T16 (clear-to-farthest = 0.0, GEQUAL flips, depth-range
  choreography, clamp); Vulkan-gap degrade documented for `PortalRenderInfo` queries.
**(b) Debt:** none. **(c) Commits:** 1: "CUTOVER: entity portals default ON (R4/R5 signed off)".
**(d) runClient (default launch, NO dev flag — the user's normal profile):** run the briefing's
**full 12-point regression checklist** (briefing §4), items 1-12, in a fresh survival-style world:
crossings all directions (1), no FOV/sprint/hand glitch (2), thrown items/mobs (3), arrows (4),
panicking animals (5), no phantom rocket (6), straddle render (7), pre-crossing lighting (8), no
chunk holes/remesh correctness (9), large/tall + negative-coord portals (10), no GPU leak /
render-thread logging discipline over a 30-min session incl. `RenderSpikeMonitor` quiet (11),
relog/kick/rejoin clean (12). Any failure blocks the stage — the default flips back OFF
(one-line revert) while the defect is fixed.
**(e) Checklist:** ALL 12 (this is the gate that defines the stage).
**(f) Rollback/flag:** rollback = flip default OFF (block system still intact until T20).
**Effort M** — verification-dominant.

---

### T18 — CrossPortalEntityRenderer: R3 design round + port (trailing periphery)

**(a) Contents** (API_RISKS R3 — "the largest unknown surface… schedule its design round early,
but note it can trail the core cutover"; constraint 4: R3 design+port trails the cutover):
design round choosing between the two candidate mechanisms (per-draw clip uniform at
`GlCommandEncoder.trySetup` RETURN keyed off submit-order metadata — the mod's clip hook already
lives there — vs one-entity `SubmitNodeStorage` + `FeatureRenderDispatcher.renderAllFeatures`
bracketed by raw-GL clip state); then the 26.2 re-expression of `CrossPortalEntityRenderer`
(source landed at T10, held behavior-off). The `shouldRender` visibility-gate mixin ports 1:1
(signature-identical, render-core S35); the renderEntity duck dies (public
`extractEntity`+`submit`).
**(b) Debt:** none. **(c) Commits:** 1: "R3 design: <chosen mechanism> + rejected alternative";
2: "CrossPortalEntityRenderer 26.2 re-expression".
**(d) runClient (default ON):**
1. Stand straddling a portal; F5 view: body renders whole on BOTH sides, no half-clip (supersedes
   item 7's status quo — briefing §5 "two-sided entity render" bullet: approach-side sink-in,
   animal threshold clip both directions, damage flash in portal view).
2. Punch a mob through the portal window; hit registers; damage flash visible through the window.
3. Walk a cow across slowly — no pop at the threshold from either viewpoint.
**(e) Checklist:** 7 (now at full IP standard), 11.
**(f) Rollback/flag:** feature-local toggle (IP's own `cross_portal_entity_rendering_enable|disable`
client debug command — ClientDebugCommand switch set); default ON when stable.
**Effort L** — design-round-dominated; the mechanism is unproven (R3 "UNKNOWN-NEEDS-DESIGN").

---

### T19 — Peripheral tail (U13) — user checkpoint C1 executes

**(a) Contents** (DEPENDENCY_ORDER U13 row; user decides scope per constraint 7 — default
core-complete): wand items + client wand code (overlays via Gizmos/`submitCustomGeometry`, R6),
dim stack + GUI (needs R13g dynamic-dimension answer — UNKNOWN on 26.2), alternate dims (needs
R13g + `NoiseBasedChunkGenerator` definalize + `noNewCaves` re-derivation), compat `On*Present`
layers + gated Sodium/Iris mixins (dead on 26.2 until those mods port — compile shells already in
from T3/T11), ModMenu config GUI (≙ `IPModMenuConfigEntry`). GUI-model work rides R13e
(Screen extract-model).
**(b) Debt:** none. **(c) Commits:** per accepted feature.
**(d) runClient:** per-feature scripts (wand: create/drag/adjust a portal by hand, cursor
alignment; dim stack: only if R13g resolved). Skipped features = no script.
**(e) Checklist:** 11, 12 on any accepted feature.
**(f) Rollback/flag:** per-feature; nothing here gates the core.
**Effort M–L per feature** — bounded by the user's C1 scope decision.

---

### T20 — Old-system deletion + flag removal + final regression (U14)

**(a) Contents** (constraint 5 — deletion ONLY here; DEPENDENCY_ORDER U14 row):
- Delete per disposition tables: current-mod-core §1-§9 DELETE rows (30) and REPLACE-BY sources
  (42) — `PortalInfo` scan chain, `SeamlessServer/ClientTeleport`, `PortalChunkTracker` ACK ledger,
  bespoke `ModPayloads` redirection set, `HandleRespawnMixin`, `PortalWorldManager`
  promote/demote…; current-mod-render DELETE column (12) — `CameraTransitionHandler`,
  `PortalSlicing`, `PortalFrameSuppressor`, dead mixins.
- **Survivor manifest verified** (the PORT-FORWARD carry, constraint 5): stencil substrate mixins
  (GlBackend/GlConst/RenderTarget/GlStateManager), `PortalRenderTypes`, `DimensionRenderHelper`,
  `ImmPtlClientChunkMap` (née SeamlessClientChunkMap) + SOG delta feed, `FrontClipping`,
  lateUpdateLight, LevelExtractor-layer mixins, `GlCommandEncoderClipMixin`,
  `ShaderManagerCompilationCacheMixin`, `endFramePooled`, diagnostics
  (PerfTimers/RenderSpikeMonitor/CrossingTracer + `rlog` gate, COVERAGE INFO-1),
  `ServerLevelFireSpreadMixin` re-keyed to `isPlayerWatchingChunkWithinRadius`, the "(verify)"
  rows re-proven on the ported path before retention (ChunkPacketGuardMixin,
  ClientPacketListenerAddEntityAdoptMixin, ClientPacketListenerLocalPlayerFallbackMixin,
  sub-items of SeamlessClientTeleport — current-mod-core §5/§9).
- Remove `MigrationFlags.ENTITY_PORTALS` + the exclusivity ledger + `seamlessportals-ip` plugin
  gating (config becomes unconditional); remove block-era mixin config entries.
**(b) Debt:** none. **(c) Commits:** 1: "U14: delete block-portal machinery (disposition tables)";
2: "U14: survivor manifest verification + (verify)-row outcomes"; 3: "U14: flag + ledger removal".
**(d) runClient:** the **full 12-point regression checklist again** (post-deletion — deletions can
break survivors via lost call sites), plus one relog + one kick/rejoin (item 12's both paths:
ClientLevel.disconnect AND updateLevelInEngines(null)).
**(e) Checklist:** ALL 12.
**(f) Rollback/flag:** git revert only — the flag no longer exists. This is deliberately the LAST
stage so that every earlier stage kept the cheap rollback.
**Effort L** — wide but mechanical; the verification pass is the substance.

---

## 4. Forced-deviation register (constraint 1)

Sanctioned translation classes — NOT deviations, listed once for clarity: (i) 1.21.3→26.2 API
translation per slice maps; (ii) Fabric→PlatformHelper loader-neutral seams (R13h: "never Fabric
types in common"); (iii) D2 package preservation; (iv) D3 flag scaffolding (temporary, removed
T20, never inside IP code bodies); (v) 26.2-substrate KEEPs with no IP analog (current-mod-render
§1/§3 KEEP rows — required enablers, e.g. stencil FBO chain, PortalRenderTypes, endFramePooled).

| Id | Deviation | Rationale | Owning stage |
|---|---|---|---|
| FD1 | `WorldInfoSender` time-half deleted (weather-only) | 26.2 `ClientboundSetTimePacket` is clock-map based, no per-dim daylight boolean; `GameRules.RULE_DAYLIGHT` gone (API_RISKS R13j — "a FORCED deviation"). Weather guard stays (rain-flip broadcast still un-dimensioned). | T8 |
| FD2 | Attached-firework skip added to `teleportRegularEntity` skip conditions | IP verifiably has the phantom-rocket hazard (restoreFrom recreate, no attached-firework guard — current-mod-core §11.3); real bug, already fixed here. **Reproduce on ported path first, then apply as documented patch.** | T15 |
| FD3 | `preserveTransientHurtState` re-applied onto the recreate branch | Same shape: restoreFrom drops lastDamageSource/hurtTime/brain HURT_BY (current-mod-core §11.4). Reproduce-then-apply. | T15 |
| FD4 | R1 seaLevel dim-sync protocol extension | 26.2 `ClientLevel` ctor requires trailing `int seaLevel`; only vanilla source is the spawn-info packet — IP has no analog because 1.21.3 had no such param (API_RISKS R1, world-loader-root §2). | T6 (design) / T10 (consume) |
| FD5 | R8 position stamp via codec wrap (or paired packet) instead of IP's write/ctor injects | 26.2 packet is a record with composite codec, "no tail slack" (API_RISKS R8). Mechanism differs; carried data identical. | T9 |
| FD6 | DimLib replaced by static-dimension stub of the event wiring only | DimLib has no 26.2 form (R13g); dimensions themselves are vanilla (DEPENDENCY_ORDER §2.1). Revisited if C2 accepts dynamic dims. | T3 |
| FD7 | Sodium/Iris layers = compile shells only, runtime dead | Neither mod exists on 26.2 (constraint 7); shells compile-mandatory (cycle 12). | T3/T11 |
| FD8 | R7 re-queue lands as network.md §A shape, not IP's verbatim 1.21.3 mechanism | Verbatim port creates per-frame cross-queue reordering 1.21 did not have (API_RISKS R7) — the §A shape IS the order-faithful translation. | T6 |
| FD9 (conditional) | Reliability layer over IP's no-ACK chunk sending | ONLY if T14 empirically reproduces the walking-limbo drop class under ported redirection (current-mod-core §11.7: "must be proven empirically post-port, not assumed away"). Empty unless proven. | T14 |
| FD10 (conditional) | Pinned-bounded ViewArea kept instead of ImmPtlViewArea rebuild | Only if the T10 default (rebuild) proves infeasible; carries the documented >71-chunk latent bug (API_RISKS R4). | T17 sign-off |
| FD11 (conditional) | `VisibleSectionDiscovery` budgeted compile scheduling kept (A3) | Default classification: required 26.2 adaptation (one-shot dirty-flag model strands sections — OW-holes root cause); confirmed or reclassified by the T10 comparative read of `ForceMainThreadRebuild`. | T10 |

Where vanilla behavior is faithful, vanilla is left alone (briefing §6: firework billboard
rotation precedent; also R13b — IP patches obsoleted by vanilla 26.2 are DROPPED, not ported,
which is fidelity to intent: the audit trail lives in T9's R13b table).

---

## 5. User-decision checkpoints (constraint 7; default = core-complete)

| Id | Decision | Default | When |
|---|---|---|---|
| C1 | Peripheral scope: wand (full feature), dim stack, alternate dimensions | Core-complete: none, beyond the compile-closure files (`PortalWandInteraction`, `CommandStickItem`, `IPFeatureControl` — ported at T3/T12 regardless) | Before T19 |
| C2 | Dynamic dimensions (R13g — UNKNOWN on 26.2; blocks dim stack + alt dims only) | Static-dimension stub (FD6) | With C1 |
| C3 | Sodium/Iris runtime compat | Compile shells only (FD7); revisit when/if those mods reach 26.2 — then COVERAGE INFO-3's 27-file full-depth pass triggers | Post-T20 backlog |
| C4 | `HandLightSmoother` + `GameRendererHandLightMixin` (A5): does zero-deviation ban additive comfort features? | KEEP (provisional, current-mod-render A5) | Before T20 (deletion stage) |
| C5 | Ship released jars with `qouteall.*` packages, or mechanical rename + RPC-string audit before release | Keep `qouteall.*` (D2) | Before first public release |
| C6 | View-bob: IP `viewBobbingReduce` verbatim means bob RETURNS away from portals (visible change vs today's unconditional no-op) | Port IP verbatim (fidelity) — flagged in T17 release notes (A2) | T17 |

---

## 6. Risk placement map (R1–R13 → stages)

| Risk | Design round | Implementation | First runtime exercise |
|---|---|---|---|
| R1 extract→render split + seaLevel | T6 (seaLevel protocol — gating, pre-cutover per API_RISKS verdict); remote-tick placement T10 | T10 (transplant into ClientWorldLoader/MyGameRenderer) | T14 (first secondary ClientLevel) |
| R2 frame-phase anchor | **T2** (gating, settled earliest) | T2 host; T11 IP-chain dispatch | T2 (block portals), T13 (entity path) |
| R3 CrossPortalEntityRenderer | T18 (trails cutover — constraint 4) | T18 | T18 |
| R4 ViewArea fidelity | Drafted T10, **inside cutover spec** (constraint 4) | T10 | T13/T14; signed off T17 |
| R5 reversed-Z/stencil signs | Drafted T11, **inside cutover spec** | T11 | T13 first light; signed off T17 |
| R6 immediate-draw re-expression | — (patterns all cited) | T10 | T13 |
| R7 packet re-queue ordering | — (§A shape specified) | T6 (FD8) | T14 |
| R8 position-packet stamp | **T9** (owning stage, both halves lock-step) | T9 (FD5) | T13 (same-dim crossing sync) |
| R9 fog/lightmap/environment | T10 (fog ownership UNKNOWN) | T10 | T14 (cross-dim fog) |
| R10 ticket flags + tracker takeover | **T8** (owning stage) | T8 | T14 |
| R11 persistence/SavedData/EntityType quartet | **T5** (owning stage) | T5 + T6 (GlobalPortalStorage) | T13 (spawn/NBT), T14 (save/load) |
| R12 collision re-derivation | T7 (checkInsideBlocks design note) | T7/T9 | T13 (collision), T15 (traffic) |
| R13a ender pearl | — | T7 | T15 |
| R13b vanilla-obsoleted patches audit | T9 | T9 | — |
| R13c lambda anchors from compiled jar | — | T10/T11 | T13 |
| R13d height inclusivity (+1 in wrappers) | — | T4 | T16 (matcher/FastBlockAccess) |
| R13e GUI extract model | — | T12 (debug text) / T19 (screens) | T13 item 6 / T19 |
| R13f ClientChunkCache delta protocol | — | T8 (port-forward) | T14 |
| R13g dynamic dimensions | C2 checkpoint | FD6 stub T3 | T19 (if accepted) |
| R13h Fabric renames / PlatformHelper routing | T0 | T0/T6/T12 | T12 |
| R13i fabulous suppression | — | T11 (checklist row) | T13 |
| R13j WorldInfoSender time-half | — | T8 (FD1) | T14 item 5 |
| R13k camera pull-model gates | — | T10 | T13 |

Render-cutover verdict compliance (API_RISKS verdict): substrate stage-1 = T2 (+ standing KEEPs);
atomic driver core = built T5–T12 under D1/D3, exercised T13–T16, user-facing switch T17;
trailing periphery stage-3 = T18/T19. Both gating designs (R1 seaLevel, R2 anchor) are complete
before any flag-ON run, strictly before T17.

---

## 7. Corpus-doubts appendix

1. **COVERAGE INFO-2 refinement (not a contradiction):** "Mesh2DTest/HelperTest are a free
   correctness harness for U1" — verified this pass: `Mesh2DTest` has no qouteall imports outside
   its package and runs at T1, but `HelperTest` imports `Helper` (HelperTest.java:7), which is
   SCC-bound via `Helper`→`McHelper` (DEPENDENCY_ORDER §2.1). So half the harness runs at U1, the
   other half only at closure (T12). The plan schedules both.
2. **DEPENDENCY_ORDER §4 "build is expected red from U1 until the SCC closes":** under D1 the
   official gate build is never red — held units are simply not compiled, which is the same
   document's own excluded-source-set option made default. Flagging only so nobody reads the two
   statements as conflicting.
3. **DEPENDENCY_ORDER U9 lists `CrossPortalEntityRenderer` in U9 contents, while API_RISKS
   verdict §3 and constraint 4 have R3 trailing the cutover.** Reconciled in T10/T18: the *source
   file* lands (held) with U9 so U10/U11 closure references resolve; its 26.2 re-expression and
   activation are T18. No contradiction, but worth stating since a naive reading of the U9 row
   would schedule the hardest unknown before first light.
4. **Minor:** current-mod-core §2 note "IP recreates via NBT-restore… the same loss class as the
   mod's TeleportTransition path" supports FD2/FD3, but those patches must not be applied
   pre-emptively — the reproduce-then-apply discipline in T15 exists because zero-deviation makes
   "IP is buggy here" a claim that needs fresh runtime evidence on the PORTED path, not inherited
   belief.
