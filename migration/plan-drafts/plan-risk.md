# PLAN-RISK — staged execution plan, risk-first ordering

**Author role:** RISK plan. Optimizes for front-loading the unknowns: the gating design rounds (R1, R2)
and the highest-uncertainty items (R4, R7, R11, R3-compile) are scheduled as early as the dependency
order allows, via explicit LEARNING-ONLY spikes (throwaway, never merged). Middle stages are ordered so
that a blocker invalidates the least completed work.

**Corpus this plan builds on (and never re-derives):**
`ENTITY_PORTAL_MIGRATION_BRIEFING.md` (regression checklist §4, discipline §6),
`migration/DEPENDENCY_ORDER.md` (units U0–U14, cycles §3, port order §4, runtime constraints §4.2),
`migration/API_RISKS.md` (R1–R13 + render-cutover verdict),
`migration/api-map/current-mod-core.md` + `current-mod-render.md` (disposition tables),
`migration/COVERAGE.md` (INFO-1/INFO-2). Anything here that appears to contradict the corpus is listed
in Appendix B (corpus doubts), not silently deviated from.

**Build gate for every stage:**
`Set-Location "C:\Users\warwa\ModDev\Portals\Portal 26.2"; .\gradlew.bat :common:compileJava :fabric:compileJava --console=plain --no-daemon`
must be GREEN, and the game must be fully playable (block portals keep working) at the end of every
stage until S16 flips the switch. Never `gradlew --stop` (machine-wide rule).

---

## 1. Plan-shaping decisions (settled at S0, binding for the whole plan)

### D1. Holding mechanism for ported-but-unclosed SCC source: an **excluded staging source tree** with a per-file graduation rule and a red diagnostic task

DEPENDENCY_ORDER §4 ground rules state the only fidelity-preserving option for green builds before the
U11 closure: "hold un-closed units in an excluded source set until their closure lands (build
engineering, not code change)." This plan adopts exactly that, concretized:

- **`common/src/ipport/java` (+ `common/src/ipport/resources`)** — a directory registered with the IDE
  but wired into **no** Gradle source set until the closure stage (S13). Files live at their FINAL
  package paths from day one, so the closure flip is a build-file change (add `srcDir` to `main`) plus
  mixin-json activation — no `git mv` churn, and rollback of the flip is a one-line revert. Loader-side
  staged files (Fabric init wiring) use `fabric/src/ipport/java` symmetrically.
- **Graduation rule:** any staged file with ZERO forward refs (per the unit's debt column in
  DEPENDENCY_ORDER §4) may land directly in `common/src/main/java`, live-compiled and simply unused.
  This applies to the U1 pure-math subset ("the only genuinely standalone layer",
  DEPENDENCY_ORDER §2.1) and to the 36 duck interfaces + compat invoker bases ("compile against vanilla
  only… cheapest thing in the whole graph", DEPENDENCY_ORDER §2.9, §4 ground rules). Live-graduated
  code gets real javac verification years before the flip; staged code does not — which motivates:
- **`:common:compileIpportDiag`** — a dev-only JavaCompile task over the union of `main` + `ipport`
  sources. It is EXPECTED RED until S13; its error list is the machine-checked **forward-ref debt
  ledger**. Per staging stage, the gate is: *every remaining error references only classes belonging to
  not-yet-staged units* (checked against the unit's debt column). This converts the U11 "big-bang
  compile event" into a monotonically shrinking, audited error count — by the end of S12 the ledger
  must reference only U11 files, so S13 has near-zero compile surprises. The task is diagnostic
  tooling, never a shipping gate, and is deleted at S19.
- **AW/AT entries and mixin JSON:** each unit's access-widener/AT entries land LIVE at its staging
  stage (they target vanilla classes; the loader validates them at boot, so a typo fails fast under
  normal play instead of at S13). Staged mixin CLASSES are not compiled, so their registration lives in
  a prepared `seamlessportals-ip.mixins.json` held in `ipport/resources` and activated only at S13.
- **No stubs, ever.** Nothing in `main` may reference staged classes; the zero-deviation rule
  (briefing, mission header) extends to never writing throwaway IP-code variants. Spike code (S2) lives
  on `spike/*` branches and is never merged.

### D2. Namespace policy: mechanical repackage `qouteall.*` → `com.warwa.seamlessportals.*`, scripted, with a zero-`qouteall` grep gate

DEPENDENCY_ORDER §4.2: IP's RPC FQN strings are wire protocol. This is a NEW standalone mod — both RPC
endpoints are this mod, and cross-mod wire compat with real IP is not required (mission constraint 6).
Decision:

- `qouteall.imm_ptl.**` → `com.warwa.seamlessportals.imm_ptl.**` and `qouteall.q_misc_util.**` →
  `com.warwa.seamlessportals.q_misc_util.**`, preserving every sub-package and class name, applied by a
  **scripted rewrite at file-copy time** (`migration/tools/repackage` — rewrites `package`/`import`
  lines, `qouteall.`-prefixed STRING LITERALS (the RPC call-site FQNs such as
  `…teleportation.ClientTeleportationManager.RemoteCallables.updateEntityPos`, DEPENDENCY_ORDER §4.2),
  and `ImplRemoteProcedureCall`'s package whitelist constant). Never hand-edited, so `diff` against IP
  shows only header/literal lines.
- **Enforcement:** a per-stage grep gate — zero occurrences of `qouteall` in `ipport`/`main` source
  outside comments. A missed literal is a silent runtime RPC failure; the gate makes it structural.
- **ResourceLocation namespaces** (`imm_ptl:`, `iportal:` event phases, ticket type id, datapack
  registry ids, entity-type ids): uniformly become the `seamlessportals` namespace; PATH parts keep
  IP's names verbatim. New-save-only mod, no data-compat constraint. Recorded per-id in
  `migration/DECISIONS.md` at the stage that ports each id.
- **Rationale over keeping `qouteall.*` verbatim:** mixin/package hygiene under the mod's own
  namespace, no collision if the user ever installs actual qouteall mods, and the rewrite is fully
  mechanical + grep-audited (lower residual risk than shipping a foreign namespace).

### D3. Flag strategy: ONE master flag, `ENTITY_PORTALS`, on the proven STENCIL_DIRECT pattern

Per the API_RISKS verdict (staged substrate → ATOMIC flag-gated driver core → trailing periphery) and
mission constraint 3(f):

- A single boolean (config key + `-Dseamlessportals.entityPortals` system-property override), readable
  **at mixin-plugin time** by `SeamlessMixinConfigPlugin` (the KEEP'd ≙ `IPMixinPlugin`,
  current-mod-core §8) so mixin application itself can be gated where the IP and block-era hooks would
  collide.
- Flag OFF (default until S16): block-portal system serves normal play; ported IP subsystems are
  registered but quiescent (no Portal entities exist, no /portal command registered, IP tick hooks
  no-op, IP renderer driver not selected).
- Flag ON (dev/test from S13): the whole IP driver set is active AND the block-portal client renderer,
  crossing, and streaming init sites are suppressed — the verdict's rule that the shared mutable seams
  (one `LevelRenderState`, one `visibleSections` list, one fog UBO slot, one stencil choreography)
  forbid two active portal-render drivers in one frame.
- No sub-flags (combinatorial state explosion); the atomicity is in this one switch. S16 flips the
  default; S19 deletes the flag and the OFF path.

---

## 2. Stage table

| id | name | contents (unit refs → DEPENDENCY_ORDER §4) | effort | gate |
|---|---|---|---|---|
| S0 | U0 decisions + scaffold | U0 loader plumbing; staging tree; diag task; flag skeleton; namespace script | M | green build; boot unaffected; DECISIONS.md v1 |
| S1 | U1 math live + JUnit | U1 (my_util live; Helper staged); Mesh2DTest/HelperTest carried | M | `:common:test` green; play unaffected |
| S2 | Spike batch (learning only) | SPIKE-R1 seaLevel, SPIKE-R7 re-queue, SPIKE-R4 ViewArea, SPIKE-R11 SavedData | M | 4 memos in `migration/spikes/`; R1 protocol design v1; nothing merged |
| S3 | R2 anchor + pump relocation | design round R2; relocate pre-render pump + A4 re-home (live substrate) | M | regression 1,2,6,8,11 pass under block portals |
| S4 | U2 staging | ducks live-graduated; IPGlobal/O_O/IPConfig/IPPerServerInfo/compat bases staged | M | ledger clean; play unaffected |
| S5 | U3 staging | McHelper/CHelper/IPMcHelper/ScaleUtils (+R13d wrapper fix) | M | ledger clean |
| S6 | U4 staging | portal-core + shapes + animation + collision co-ports; R11 entity decisions | XL | ledger clean; R11 decisions recorded |
| S7 | U5 staging | network + RPC + global_portals + api; R7 impl; R11 DataFixTypes; R1 protocol impl | L | ledger clean; decisions recorded |
| S8 | U6 staging | teleportation managers; R11 spawn-reason; verify-patches prepared | L | ledger clean |
| S9 | U7 staging | chunk-loading + entity-sync + ImmPtlClientChunkMap; R10 decision; R13j deviation | L | ledger clean; R10 recorded |
| S10 | U8 staging | ClientWorldLoader (+26.2 render-split plumbing) + init sequences + 74 common mixins; R8 decision | XL | ledger clean; R8 recorded |
| S11 | U9 staging | render context/worldswitch hotspot; PORT-FORWARD merges; A3 comparative read | XL | ledger clean |
| S12 | U10 staging + cutover spec | renderers + ~62 client mixins; R3 design round + compile-port; spec binds R4+R5 | XL | ledger references only U11 files; cutover spec signed |
| S13 | SCC closure (U11) | commands/blockmanip/misc + closure co-ports; build flip; FIRST GREEN BUILD | L | green; flag-OFF play unchanged; flag-ON smoke passes |
| S14 | Flag-ON stabilization | regression iteration on command portals; verify-patches applied; runtime proofs | L–XL | checklist 1–8,10–12 pass (command portals) |
| S15 | U12 generation | nether-portal pipeline + breakables + ignition mixins | L | E2E nether tests pass (flag ON) |
| S16 | CUTOVER flip | default `ENTITY_PORTALS=true`; block system dormant; soak | S | full 12-point regression in normal play |
| S17 | R3 verification + trailing periphery | CrossPortalEntityRenderer behavior; GuiPortal/Overlay/mirrors/FBO-mode (A1); view-bob (A2) | L–XL | item 7 fully passes; 1,2 re-pass |
| S18 | U13 peripheral tail | wand/dim-stack/alt-dims per user scope; compat On*Present layers | M–XL | per-feature tests |
| S19 | U14 cleanup | delete old system per disposition tables; remove flag; survivors carried; final regression | L | full 12-point + relog/kick clean |

Effort scale (relative, from inventory LOC + api-map changed/gone counts): S < M < L < XL, where U4
(6,374 + 2,639 LOC + collision co-port) and U9 ("the 26.2 rewrite hotspot", DEPENDENCY_ORDER §2.7) set
the XL bar.

**Why this order is risk-first:** every unknown capable of invalidating the cutover design (R1, R2, R4,
R7, R11) is either settled (R2, S3) or spiked-with-memo (S2) before a single SCC file is ported. The
staging march S4→S12 then follows the corpus's verified landing order — inside an SCC no order is
compile-better than another (headline finding), so the ordering criterion becomes "if a blocker
surfaces at stage N, everything staged before N is a 1:1 port whose correctness is independent of the
blocker" — and the residual translation risk is concentrated in the two XL render stages (S11–S12),
which sit LAST in the march, immediately before closure, so a render-blocker invalidates zero
downstream porting work (nothing is downstream but the closure itself).

---

## 3. Stage specifications

Common to all stages: commits via `git commit -F <file>` (briefing §6); after any behavior-affecting
change, the applicable regression-checklist rows (briefing §4) are re-run; memory updated on every root
cause found.

---

### S0 — U0 decisions + scaffold (M)

**(a) Contents** (DEPENDENCY_ORDER §4, U0 row):
- Loader-neutral seams: event-object answer for `Helper.create*Event` (IP's own event objects are NOT
  Fabric API and port as plain infrastructure once this exists — API_RISKS R13h); payload registration
  abstraction behind the widened `PlatformHelper` (KEEP, current-mod-core §3; Fabric v6 renames +
  "never Fabric types in common" per R13h); entity-type registration callback plumbing mirroring
  `IPModMain.registerEntityTypes(BiConsumer)` (current-mod-core §10).
- Build engineering per D1: `common/src/ipport/java` scaffold, `compileIpportDiag` task,
  `seamlessportals-ip.mixins.json` placeholder in staged resources.
- D3 flag skeleton: config key + system property + `SeamlessMixinConfigPlugin` read path.
- D2 namespace-rewrite script + the grep gate wired into a dev task.
- `migration/DECISIONS.md` created: D1/D2/D3 recorded; a running register for the per-stage decisions
  (R8/R10/R11 etc.) and the forced-deviation register (§4 below) seeded.

**(b) Forward-ref debt:** none (U0 depends on vanilla + loaders only).

**(c) Commits:** (1) build engineering (staging tree + diag task + grep gate); (2) loader-neutral
seams; (3) flag skeleton + mixin-config wiring; (4) DECISIONS.md.

**(d) runClient test:** nothing user-visible changes; verify normal play unaffected — boot, enter a
world, build/cross a nether portal once, quit cleanly.

**(e) Checklist items:** none formally; item 1 as a smoke test.

**(f) Rollback/flag:** flag exists, OFF, unused. Rollback = revert commits; no live behavior touched.

---

### S1 — U1 math live + JUnit harness (M)

**(a) Contents** (U1 row): `my_util/*` (all math: `DQuaternion`, `Plane`, `Mesh2D` 1,753 LOC, `IntBox`,
`Range`…), `MyTaskList`, `Signal*`, loggers, `Animated`+`Rendered*`, `GuiHelper`, `DimIntIdMap`,
`MiscGlobals` — every file with zero forward refs lands LIVE in `common/src/main/java` (D1 graduation
rule; the pure-math subset has zero imm_ptl imports, DEPENDENCY_ORDER §2.1). `Helper` STAGES (its one
edge: `Helper.java:509` → `McHelper.newResourceLocation`, cycle 1).
**Explicit deliverable (mission constraint 8):** `common/src/test/java` with the carried IP tests
`Mesh2DTest` + `HelperTest` (COVERAGE INFO-2 — "a free correctness harness"), extended with
DQuaternion/Plane/IntBox invariants targeting the two past geometry-sign error classes (briefing §6:
"never trust a prior geometry-sign claim").

**(b) Debt:** `Helper`→`McHelper` (staged, 1 call) — the ONLY U1 edge out.

**(c) Commits:** (1) live math + test harness; (2) `Helper` staged.

**(d) runClient test:** nothing user-visible; verify normal play unaffected. Off-game:
`.\gradlew.bat :common:test` green (this is DEPENDENCY_ORDER §4.1 milestone 1).

**(e) Checklist:** none.

**(f) Flag OFF. Rollback:** revert; live math is unused-by-main until later stages.

---

### S2 — Spike batch: the front-loaded unknowns (M)

All spikes are LEARNING-ONLY throwaway experiments on `spike/*` branches — never merged (mission
directive). Each produces a memo in `migration/spikes/`. They require zero ported code, which is why
they can run this early.

- **SPIKE-R1 (design round + probe) — the seaLevel protocol + secondary-world construction.**
  API_RISKS R1: the 26.2 `ClientLevel` ctor's trailing `int seaLevel` has only
  `CommonPlayerSpawnInfo.seaLevel()` as a vanilla source; secondary worlds for never-visited dims need
  the dim-sync channel extended. Design deliverable: the protocol addition (per-dim seaLevel riding the
  dim-id sync path that S7 will port — MiscNetworking/DimIdSyncPacket), plus placement of the
  remote-tick role (LevelRenderer.tick gone; "role largely absorbed by ticking the remote ClientLevel +
  extraction" — R1). Probe: construct a ClientLevel for an unvisited dimension with a synthesized
  seaLevel on the current mod's secondary-level machinery; verify construction + extract + render
  sanity. **This is gating for the cutover (verdict: "design-complete BEFORE the atomic commit").**
- **SPIKE-R7 — packet re-queue ordering.** Throwaway payload wrapping a vanilla packet, re-submitted
  via `packetProcessor().scheduleIfPossible(...)` + cancel exactly as network.md §A specifies; verify
  (i) ordering against vanilla packets in the same frame, (ii) the netty double-invocation shape
  (isSameThread — briefing §6 rule; memory: respawn-mislabel). Deliverable: confirmation (or
  correction) of §A's shape before S7 implements it for real.
- **SPIKE-R4 — ViewArea subclass viability.** API_RISKS R4 lists the surviving surface (public ctor,
  overridable `repositionCamera`/`getRenderSectionAt`) and the redirect retarget
  (`new ViewArea` inside `LevelRenderer.invalidateCompiledGeometry`). Probe both: a trivial `ViewArea`
  subclass installed via the redirect, backed by a toy unbounded store. Deliverable: evidence for the
  S12 binding decision (rebuild `ImmPtlViewArea` vs keep the pinned-bounded deviation with its
  documented latent multi-portal collision bug).
- **SPIKE-R11 — SavedData silent-loss trap.** Register a throwaway `SavedDataType` with candidate
  `DataFixTypes` constants (there is no NONE; null = NPE swallowed by `readSavedData` → fresh empty
  storage silently overwrites the file — API_RISKS R11). Deliverable: which constant round-trips the
  GlobalPortalStorage-shaped codec payload without a destructive datafixer pass, plus a reproduction of
  the silent-loss failure mode so the team recognizes it at runtime.

**(b) Debt:** n/a (nothing merged). **(c) Commits:** none to main; memos committed to `migration/`.
**(d) runClient test:** none for the user; main branch unchanged — normal play unaffected.
**(e) Checklist:** none. **(f) Flag OFF; nothing to roll back.**

---

### S3 — R2 design round + pre-render pump relocation (M) — LIVE substrate

The API_RISKS verdict names this a stage-1 preparatory refactor that "can all ship and soak while block
portals still serve players."

**(a) Contents:**
- **Design round R2 (gating for cutover):** reconcile the two corpus-endorsed anchors —
  `GameRenderer.update` HEAD (the mod's existing `GameRendererFrameCrossingMixin`, endorsed by
  current-mod-render §2.2) vs `Minecraft.renderFrame` before the `gameRenderer.update(...)` call
  (portal-animation #14's faithful anchor; the panorama/screenshot path then does not fire, matching
  IP 1.21.3). Both precede camera update; pick one, document the panorama behavior, and settle A4's
  ordering question (a GPU-upload-safe flush point outside the framegraph) in the same memo
  (API_RISKS R2: "pick one and document…; settle A4… in the spec, not ad hoc").
- **Implementation (live):** relocate the mod's crossing hook and re-home the
  `GameRendererPortalPrepareMixin` upkeep (staged-upload flush, adoption prune, bridge repaint pump —
  current-mod-render §2.3) onto the chosen anchor, in the exact future IP frame order
  (`RenderStates.updatePreRenderInfo` → animation update → `manageTeleportation(false)` —
  DEPENDENCY_ORDER §4.2 frame order). The husk mixin stays as an inert registered shell until its
  formal disposition-table deletion at S19 (constraint 5).

**(b) Debt:** none (live mod code only). **(c) Commits:** (1) decision memo; (2) relocation.

**(d) runClient test (the first real soak):** in a normal world with a nether portal —
1. Cross forward, backward, and strafing, 10+ times each way. Correct: smooth both directions, exits on
   the motion side, no oscillation. Failure: a one-frame post-teleport flash (camera positioned before
   the teleport ran = the R2 anchor is wrong), ping-pong burst.
2. Watch hand/FOV/sprint through crossings. Failure: hand off-center jump, FOV pulse.
3. Stand in the overworld and look through the portal BEFORE ever crossing: nether flames/lava lighting
   correct (lateUpdateLight still firing at its frame position).
4. Elytra-boost crossing with fireworks: no phantom rocket.
5. Take a panorama screenshot (F2-panorama path) — no crash, no teleport firing inside it.
6. Play 10 minutes; no stutter regressions, no GPU-leak symptoms.

**(e) Checklist items:** 1, 2, 6, 8, 11.

**(f) Flag OFF (unconditional substrate — it MUST soak under block portals). Rollback:** revert the
relocation commit; the old anchor is restored verbatim.

---

### S4 — U2 staging: ducks, roots, platform facade (M)

**(a) Contents** (U2 row): ALL 36 duck interfaces + q_misc_util ducks + cross-slice accessor
interfaces, and the compat invoker bases (`GravityChangerInterface`, `SodiumInterface`,
`IrisInterface`, `IPPortingLibCompat`) — **live-graduated** (vanilla-only compile, D1 rule;
platform-compat-peripheral:245 makes the invoker bases compile-mandatory regardless of Sodium/Iris
scope). STAGED: `IPGlobal`, `IPCGlobal`, `MiscHelper`, `mc_utils` (`ServerTaskList`,
`MyNbtTextFormatter` — R12's SnbtPrinterTagVisitor re-derivation applies), `IPConfig`+`O_O`,
`IPFeatureControl` (the 1-file peripheral closure member, cycle 9), `IPPerServerInfo`,
`DimensionIntId`+`DimensionIdRecord`, `IPMixinPlugin` (merged into/aligned with the KEEP'd
`SeamlessMixinConfigPlugin`).

**(b) Debt** (per U2 row): `O_O`→{`Portal` U4, `ImmPtlClientChunkMap` U7, `ImmPtlNetworkConfig` U5,
`PortalGenInfo` (closure co-port — see Appendix B doubt 1)}; `IPConfig`→`BlockPortalShape` (U4
co-port); `IPPerServerInfo`→{`ServerTeleportationManager` U6, `CustomPortalGenManager` closure,
`PortalWandInteraction` closure}.

**(c) Commits:** (1) ducks + invoker bases live (+ AW/AT entries live); (2) shells staged.

**(d) runClient test:** nothing user-visible changes; verify normal play unaffected (boot + one
crossing). Run `compileIpportDiag`; record the ledger — every error must reference U4+ classes only.

**(e) Checklist:** none. **(f) Flag OFF. Rollback:** revert; staged files have no runtime existence.

---

### S5 — U3 staging: mc-helpers (M)

**(a) Contents** (U3 row): `McHelper`, `CHelper`, `IPMcHelper`, `ScaleUtils`, mc_util entity-traversal
adapters (`IELevelEntityGetterAdapter` et al. — R12's EntitySection/EntitySectionStorage traversal
re-derivation). **R13d lands here:** `McHelper.getMaxYExclusive/getMaxSectionYExclusive` wrappers get
the +1 for 26.2's now-INCLUSIVE `getMaxY()` — fix the wrappers, never the call sites (API_RISKS R13d;
silent-corruption surface for U12's FastBlockAccess/NetherPortalMatcher).

**(b) Debt:** `Portal` (U4), `GlobalPortalStorage` (U5), `CrossPortalEntityRenderer` (render units —
Appendix B doubt 2). Also closes U1's `Helper` edge: `Helper` graduates conceptually but stays staged
until closure (its consumer set is staged).

**(c) Commits:** one, with AW entries live. **(d) runClient:** nothing user-visible; ledger check.
**(e)** none. **(f)** Flag OFF.

---

### S6 — U4 staging: portal-core + shapes + animation + collision co-ports (XL)

**(a) Contents** (U4 row): `Portal` family (`Mirror`, `BreakableMirror`, `EndPortalEntity`),
`PortalState`, `PortalExtension`, `PortalManipulation`, `PortalUtils`, `shape/*`, `util/*`, the whole
`animation/` package (2,639 LOC incl. `StableClientTimer`, `ClientPortalAnimationManagement`),
`PortalPlaceholderBlock`, `LoadingIndicatorEntity`, `PortalRenderInfo`; **co-ports:**
`BlockPortalShape` (cycle 14) and `CollisionHelper`+`PortalCollisionHandler`+`PortalCollisionEntry`
(cycle 15 — common-side edge, they need only U1–U4 material).

**R11 decisions bound at this owning stage** (constraint 4; API_RISKS R11 + current-mod-core §10/§11.6):
- Entity NBT via `TagValueOutput.createWithContext(...).buildResult()` / `TagValueInput.create(...)`
  bridging so `PortalSyncPacket` keeps carrying CompoundTag (portal-core C1; the rotation
  double-write/float-read quirk reproduces via NumericTag coercion — preserve it, C4).
- Bounding-box inversion: override moves to `makeBoundingBox(Vec3)` + **eager `setBoundingBox` push on
  every geometry-field change** (portal-core G1/C2/hazard 1 — a missed push is a stale-bb silent bug;
  enumerate every geometry setter in the port note).
- `createPortalEntityType` restructured for `EntityType.Builder.build(ResourceKey)` (id at build time,
  C10/hazard 4), registered through the U0 seam.
- Tracking range: `trackRangeBlocks(96)` → `clientTrackingRange(chunks)` replicating Fabric's exact
  block→chunk conversion (hazard 5; writing 96 would mean a 1536-block radius). Verify against Fabric
  API source; record the number.
- `EntitySpawnReason.LOAD` for the deserialize path (R11).
- Defaulted-registry lookup semantics for `entity_type` (pig-fallback matches 1.21.3,
  portal-generation note 3).

**R12 partial:** the co-ported collision statics are re-derived **line-by-line from 26.2 decompile,
never patched from the 1.21.3 copies** (API_RISKS R12 re-derivation rule; grep `@IPVanillaCopy` per
ducks-api-misc).

**(b) Debt** (U4 row): `ImmPtlNetworking`+`PortalAPI` (U5), `ServerTeleportationManager` (U6),
`PortalRenderer` (U10), `QueryManager`/`RenderStates`/`WorldRenderInfo`/`ViewAreaRenderer`/
`FrustumCuller` (U9/U10), `PortalCommand.raytracePortals` (closure).

**(c) Commits:** split for review: (1) Portal family + state + shapes; (2) animation package;
(3) manipulation/utils/placeholder; (4) collision co-ports; AW entries live per commit.

**(d) runClient test:** nothing user-visible; normal play unaffected; ledger check. Off-game: extend
the S1 JUnit harness with geometry/NBT round-trip cases that can run against staged code once it
compiles (they activate at S13; write them now while the porting context is fresh — U4 row's
"geometry/NBT round-trip unit tests once SCC closes").

**(e)** none. **(f)** Flag OFF.

---

### S7 — U5 staging: network + global portals + api (L)

**(a) Contents** (U5 row): `MiscNetworking`, RPC pair (`ImplRemoteProcedureCall` ~476 LOC +
`McRemoteProcedureCall`), `PacketRedirection`+`PacketRedirectionClient`, `ImmPtlNetworking`,
`ImmPtlNetworkConfig`; **co-ports:** `global_portals/*` (cycle 10) and `api/` (`PortalAPI`,
`ImmPtlEntityExtension`, cycle 11).

**Risk work bound here:**
- **R7 implemented for real** per network.md §A, corrected/confirmed by SPIKE-R7: netty pass schedules
  the outer `ClientboundCustomPayloadPacket` via `packetProcessor().scheduleIfPossible(...)` + cancel;
  game-thread re-invocation handles inline; `wrapRunnable`/`scheduleExecutables` keep the narrowed
  role. The redirection wire format ports 1:1 (`GameProtocols.CLIENTBOUND_TEMPLATE.bind` intact).
- **R11 SavedData decision bound:** the `DataFixTypes` constant chosen by SPIKE-R11 goes into
  `GlobalPortalStorage`'s `SavedDataType`; the save() override ports; the silent-loss failure signature
  from the spike memo goes into the S13 test script.
- **R1 protocol implemented:** the dim-sync packet carries per-dim seaLevel per the S2 design (forced
  deviation FD-2). Login/dim-id ordering constraints preserved exactly (DEPENDENCY_ORDER §4.2 login
  order: DimIdSync mid-placeNewPlayer BEFORE difficulty; global-portal sync after).
- **D2 enforcement:** RPC FQN string literals + whitelist rewritten; grep gate run.

**(b) Debt** (U5 row): `ServerTeleportationManager` (U6), `ImmPtlChunkTracking`/`ChunkLoader` (U7).

**(c) Commits:** (1) q_misc_util networking + RPC; (2) redirection pair; (3) ImmPtlNetworking + config;
(4) global_portals + api co-ports.

**(d) runClient:** nothing user-visible; ledger check. **(e)** none. **(f)** Flag OFF.

---

### S8 — U6 staging: teleportation (L)

**(a) Contents** (U6 row): `TeleportationUtil`, `CrossPortalSound`, `ClientTeleportationManager`,
`ServerTeleportationManager` (collision classes already in S6 — reviewed alongside per U6 row).

**Risk work bound here:**
- **R11:** `EntitySpawnReason.DIMENSION_TRAVEL` on the recreate path (`entity.getType().create(toWorld,
  …)`, current-mod-core §2 `PortalTeleporter` row).
- **The two PORT-FORWARD (verify) patches are PREPARED but NOT applied** (current-mod-core §11.3/§11.4:
  attached-firework skip + `preserveTransientHurtState` onto the ported `changeEntityDimension`
  recreate branch). Zero-deviation discipline: reproduce the bugs on the ported path at S14 FIRST, then
  apply as documented port-local patches (forced-deviation register FD-4/FD-5). Both patches must cover
  the ONE unified path (briefing §6 two-path rule — the migration ends at one path, which is the point).
- **R12:** interpolation-kill retarget (`InterpolationHandler` + `snapTo` +
  `getPositionCodec().setBase` gated by `isLocalInstanceAuthoritative` — without setBase the movement
  dedup drifts; memory: post-crossing-stutter). `ServerPlayer.removeVehicle()` packet-bypass retarget
  noted for S10's mixin.
- **R13a design note:** ender-pearl/vanilla-native cross-dim paths must be INTERCEPTED, not
  supplemented (mixins land S10).

**(b) Debt** (U6 row): `ImmPtlChunkTracking` (U7), `TransformationManager` +
`MyGameRenderer.vanillaTerrainSetupOverride` + `RenderStates`/`FogRendererContext` (U9/U10,
client-only paths).

**(c) Commits:** (1) util + sound; (2) client manager; (3) server manager. **(d)** nothing
user-visible; ledger. **(e)** none. **(f)** Flag OFF.

---

### S9 — U7 staging: chunk-loading + entity sync (L)

**(a) Contents** (U7 row): `ImmPtlChunkTracking` (~669 LOC), `ChunkVisibility`, `ImmPtlChunkTickets`,
`PlayerChunkLoading`, `EntitySync`, `WorldInfoSender`, `DimensionalChunkPos`, `PerformanceLevel`,
`ServerPerformanceMonitor`+`ClientPerformanceMonitor`, `ImmPtlClientChunkMap` — built ON the repo's
already-ported `SeamlessClientChunkMap` (PORT-FORWARD, current-mod-core §5: "it already IS the IP
class", extended with the 26.2 delta-tracking surface; the faithful install point is the
`ClientLevel`-CONSTRUCTOR hook covering EVERY client world including main — NOT the secondary factory).

**Risk work bound here:**
- **R10 decision bound (owning stage):** `TICKET_TYPE` registration through the KEEP'd
  `TicketTypeInvoker` at registry-bootstrap phase (multiloader bootstrap, not first-use static init —
  API_RISKS R10); the **flag-bits decision** (FLAG_LOADING vs FLAG_SIMULATION) recorded with the
  piglin-flood lesson vs IP's simulation semantics; note the paired mob-spawn-suppression requirement
  from memory (portal-view liveness). The `PlayerTicketTracker` takeover's NEW uncovered
  `DistanceManager.addPlayer` PLAYER_SIMULATION path (mixin-common §2 warning) is designed here, mixin
  lands S10.
- **R13j forced deviation (FD-1):** `WorldInfoSender` ports weather-only; the time half is documented
  as an intentional deletion (no per-dim daylight boolean on 26.2's clock-map packet). The
  cross-dimension weather guard stays relevant.
- **R13f:** the SOG delta feed / store-center machinery is port-forward material consumed at S10/S11 —
  cross-referenced, not duplicated.

**(b) Debt** (U7 row): client half touches `ClientWorldLoader` per-dim renderers (U8, runtime-only —
no compile debt).

**(c) Commits:** (1) tracking + visibility + tickets; (2) player loading + entity sync + world info;
(3) ImmPtlClientChunkMap adaptation. **(d)** nothing user-visible; ledger. **(e)** none. **(f)** Flag OFF.

---

### S10 — U8 staging: ClientWorldLoader + init sequences + 74 common mixins (XL)

**(a) Contents** (U8 row): `ClientWorldLoader` — with the **26.2 render-split plumbing re-derived INTO
it** (current-mod-core §11.1, the audit's "hardest gap": `mc.levelExtractor` re-pointing,
`SectionUpdateTracker` identity, `lastViewDistance` sync, `RenderBuffers.endFrame()` lifecycle, SOG
delta feed — all from the mod's proven `PortalContextSwitch`/`PortalWorldManager` mechanics, which stay
LIVE for block portals until S19 while their mechanics are transplanted into the staged shell);
`IPModMain`/`IPModMainClient` init sequences (exact IP order per DEPENDENCY_ORDER §4.2 — init, login,
client-tick, frame, server-tick orders are all spelled there and are runtime constraints the S13 smoke
test exercises); ALL 74 common mixins (landing WITH their driving subsystems per §2.10 — they are
compile leaves, so they stage cleanly now that U2–U7 are staged).

**Risk work bound here:**
- **R8 decision bound (owning stage):** the S2C position-packet dimension stamp — codec-wrap
  (`@ModifyExpressionValue` on the `StreamCodec.composite` call in `<clinit>`, appending
  `writeResourceKey`/`readResourceKey`) vs a separate ImmPtl packet paired by teleport id (API_RISKS
  R8). Server+client halves change in LOCK-STEP in one commit. Decision + rationale to DECISIONS.md.
- **R12 core:** `MixinEntity` collision core re-derived line-by-line from 26.2 (`checkInsideBlocks` is
  movement-path based — the bb-redirect has no anchor; the portal clip applies to the per-segment
  box/path per the design note from teleportation-collision top risk 2); anticheat re-expressed onto
  `isEntityCollidingWithAnythingNew` + `getPreMoveCollisions` (now also guarding vehicles —
  behavior-review flag); `ServerPlayer.removeVehicle()` retarget (IP's stopRiding bypass as written is
  INEFFECTIVE on 26.2).
- **R13a:** ender-pearl mixin intercepts/replaces vanilla's own cross-dim branch.
- **R13b:** every IP cross-dim patch audited for obsolescence-by-vanilla (`EntityReference`,
  `getEntityInAnyDimension`); drop-candidates RECORDED in DECISIONS.md, never silently dropped.
- **Discipline:** every packet-handler mixin gets the isSameThread guard (briefing §6).

**(b) Debt** (U8 row): `DimensionRenderHelper`/`PortalRendering` (U9) — the last core-root edge
(cycle 7); co-scheduled with S11 per the U8 row note.

**(c) Commits:** (1) ClientWorldLoader + init sequences; (2) chunk/entity/position-sync mixins;
(3) collision + interaction mixins; AW entries live per commit.

**(d)** nothing user-visible; ledger — after S10 the ledger must reference only U9/U10/U11 classes.
**(e)** none. **(f)** Flag OFF.

---

### S11 — U9 staging: render context + world-switch (XL — the 26.2 rewrite hotspot)

**(a) Contents** (U9 row): `context_management/*` (`RenderStates`, `WorldRenderInfo`,
`PortalRendering`, `FogRendererContext`, `StaticFieldsSwappingManager`, `DimensionRenderHelper`,
`CloudContext`), `MyGameRenderer`, `MyRenderHelper`, `FrontClipping`, `ShaderCodeTransformation`,
`TransformationManager`, `GlQueryObject`/`QueryManager`, `ImmPtlViewArea`, `VisibleSectionDiscovery`,
`FrustumCuller`, `ForceMainThreadRebuild`, `GLResourceCache`, `CrossPortalEntityRenderer` (compile
shell scope decided at S12's R3 round — see S12), `GuiPortalRendering`, `ViewAreaRenderer`.

**Transplants (constraint 5 — PORT-FORWARD survivors carried into the IP shells that consume them,
per current-mod-render §1 items 1–10):**
- `PortalContextSwitch`'s 26.2 swap-set mechanics (extract/compileSections pairing, SOG delta feed,
  fog-buffer/UBO isolation, grid pinning) go INSIDE the ported `MyGameRenderer.switchAndRenderTheWorld`
  + context_management shells (§1.2).
- `FrontClipping` merges as the already-faithful port — **do NOT "fix" `Vector4f.mul(Matrix4fc)` to
  `mulTranspose`** (§1.6; the row-vector note in IP_DEVIATIONS_ANALYSIS is itself wrong); re-source the
  plane from the Portal entity.
- `ShaderCodeTransformation` keeps the mod's 26.2 wiring (`ShaderManagerCompilationCacheMixin`, §1.7).
- `DimensionRenderHelper` is already the prescribed Lightmap/LightmapRenderState shape (§1.8; R9
  "lightmap: essentially yes"); verify IP's tick-side weather/skyDarken parity during the merge.
- `VisibleSectionDiscovery` port-forwards WITH the mod's budgeted compile scheduling; **A3's one-time
  comparative read of IP's `ForceMainThreadRebuild` happens HERE** and its verdict (required 26.2
  adaptation vs re-derivation) is recorded.
- `PortalRenderBuffersPool` pattern + `endFramePooled()` (the GPU-leak rule) into
  `MyGameRenderer.acquire/returnRenderBuffersObject` (§1.10).
- `PortalInnerCull` → ported `FrustumCuller`, corners re-sourced IP's way (§1.9).
- `PortalShapeRenderer` lessons (EDGE_OUTSET, flat-plane-not-box) carried into the shape-polymorphic
  `ViewAreaRenderer` port (§1.3 — the port MUST go through the `PortalShape` abstraction).

**Risk work:** R9 fog/environment design executes here (per-dim FogRenderer/FogData buffer ownership,
`EnvironmentAttributeProbe` + `FogEnvironment` isolation — the two UNKNOWN-NEEDS-DESIGN residues), R6
re-expressions onto the proven mechanisms (PortalRenderTypes pipelines, 4-factor BlendFunction for
premultiplied blend, Gizmos/submitCustomGeometry for lines), R13k camera pull-model (`initialized`
flag via the existing `CameraInvokerMixin`; view-rotation post-processing of
`cameraState.viewRotationMatrix` after extract, never wrapping `getViewRotationMatrix`), `ImmPtlViewArea`
implemented per the SPIKE-R4 evidence (binding decision text lives in S12's spec).

**(b) Debt** (U9 row): `PortalRenderer` family (U10) for a few call sites.

**(c) Commits:** (1) context_management; (2) MyGameRenderer/MyRenderHelper + transplant merges;
(3) view-area/discovery/culling; (4) fog/lightmap design memo + implementation.

**(d)** nothing user-visible; ledger. **(e)** none. **(f)** Flag OFF.

---

### S12 — U10 staging + THE CUTOVER SPEC (XL)

**(a) Contents** (U10 row): `renderer/*` — abstract `PortalRenderer` + the Iris-compat renderer SHELLS
(cycle 12 — compile-mandatory even with no Iris on 26.2) + `RendererUsingStencil` (transplanting the
mod's proven reversed-Z/stencil choreography per current-mod-render §1.1) + `RendererUsingFrameBuffer`
+ `RendererDummy`/`RendererDebug` (A1 default: port all four modes — user checkpoint UC-3) — plus all
~62 client mixins (multiworld half + render half). **R13c:** every synthetic-lambda anchor re-derived
from the compiled 26.2 jar, not the decompile.

**The cutover spec** (`migration/CUTOVER_SPEC.md`) is authored and signed here — the two items
constraint 4 places INSIDE the cutover stage spec:
- **R4 binding decision:** ImmPtlViewArea-rebuild vs pinned-bounded deviation, decided on SPIKE-R4
  evidence. Default (fidelity + it retires the documented latent multi-portal >71-chunk collision bug):
  rebuild `ImmPtlViewArea` on 26.2 via the subclass+redirect surface. If the spike disproved viability,
  the pinned-bounded deviation is kept and entered in the forced-deviation register instead.
- **R5 sign-flip table:** every IP depth constant/comparison flipped for reversed-Z
  (`clearDepthOfThePortalViewArea`/`restoreDepthOfPortalViewArea`, depth funcs, clear values), each
  re-derived from source per the briefing §6 sign rule (this bug class has bitten twice); the Vulkan
  gap documented with the `PortalRenderInfo` occlusion-query degrade path.
- Also fixed in the spec: R2 anchor confirmation (from S3), A4 flush point, R13i fabulous-transparency
  suppression re-anchor (`GameRenderState.useShaderTransparency()` — "easy to silently drop"), the
  flag-ON driver-swap wiring list (which init sites flip), and the S13 smoke script.
- **R3 design round (schedule-forced BEFORE closure — see Appendix B doubt 3):**
  `CrossPortalEntityRenderer` sits inside the compile closure (IPMcHelper imports it), so its 26.2
  re-expression must be designed now for the class to compile at S13. Deliverables: (i) mechanism
  choice from API_RISKS R3's candidates — (a) per-draw clip uniform at `GlCommandEncoder.trySetup`
  keyed off submit-order metadata (the mod's `GlCommandEncoderClipMixin` already lives there) or (b) a
  one-entity `SubmitNodeStorage` + `renderAllFeatures(storage)` bracketed by raw-GL clip state;
  (ii) the full 1:1 port of the class against that mechanism; (iii) the visibility-gate mixin
  (`shouldRender` is signature-identical — ports 1:1). Its runtime **verification and iteration
  formally trail the cutover** at S17 (verdict: additive — absence-of-correctness at cutover is status
  quo, not regression).

**(b) Debt** (U10 row): `BlockManipulationClient` + `PortalCommand`/argument types — U11 closure files
only. **Gate: the `compileIpportDiag` ledger must reference ONLY U11 files after this stage.**

**(c) Commits:** (1) renderer family; (2) client mixins (multiworld); (3) client mixins (render);
(4) CUTOVER_SPEC.md; (5) R3 design memo + port.

**(d)** nothing user-visible; ledger gate above. **(e)** none. **(f)** Flag OFF.

---

### S13 — SCC closure: U11 + the build flip — FIRST GREEN BUILD, first light (L)

**(a) Contents** (U11 row): `commands/*` (`PortalCommand` 2,853 LOC, `ClientDebugCommand`, argument
types — registered through the U0 seam, gated behind the flag), `block_manipulation/*`,
`miscellaneous/`, `debug/`, the two peripheral closure files `PortalWandInteraction`+`CommandStickItem`
(cycle 9), generation shells `PortalGenInfo`+`CustomPortalGenManager` head (cycles 8/14). **Build
flip:** `ipport` srcDirs added to `main`; `seamlessportals-ip.mixins.json` activated;
`compileIpportDiag` retired to a no-op; first full green build of the entire ported SCC. Entity types,
payloads, and RPC registered unconditionally (inert without portals); tick/frame/renderer driver hooks
and /portal registration gated by `ENTITY_PORTALS`.

**(b) Debt: none — the SCC closes here** (U11 row: "⬅ FIRST GREEN BUILD").

**(c) Commits:** (1) U11 contents; (2) the flip + compile-fix batch (expected near-zero given the S12
ledger gate); (3) flag-gated wiring.

**(d) runClient test — TWO runs (this is DEPENDENCY_ORDER §4.1 milestone 2):**
- **Run 1, flag OFF (the protection gate):** normal play + block portals byte-for-byte unaffected —
  build a nether portal, cross both ways, check lighting-before-crossing, hand/FOV. Failure here means
  the flip leaked (a registered IP mixin colliding) → fix before anything else.
- **Run 2, flag ON, FRESH disposable world:**
  1. `/portal make_portal <width> <height> <dim> <x> <y> <z>` (argument order per the ported
     `PortalCommand.register` — read the ported source or `/portal` help output at test time).
     Correct: a portal appears, the destination world is VISIBLE through it (stencil view; dest loads
     gradually per `ip-dest-loading-model` — not instantly, that is IP-correct). Failure: crash at
     entity registration (R11 build-id/tracking-range), invisible portal (PortalSyncPacket sync),
     black/empty view (R1 seaLevel or extract wiring), corrupted cross-dim state such as phantom
     blocks/mislabeled level (R7 ordering — the respawn-mislabel symptom shape).
  2. Walk through, forward and backward, repeatedly. Correct: smooth client-first teleport, exits on
     the motion side, no ping-pong. Throw items and shoot arrows through; lead an animal through.
  3. `/portal complete_bi_way_portal` / `/portal complete_bi_way_bi_faced_portal` on it; verify the
     cluster crossings.
  4. Global portal subcommands (`/portal global …`): create one, relog, verify it persisted —
     **the R11 silent-loss check: if `global_portal.dat`-equivalent comes back EMPTY after relog, that
     is the swallowed-NPE signature from the SPIKE-R11 memo.**
  5. Cross-portal block interaction (break/place through the portal — block_manipulation).
  6. Elytra-boost crossing with fireworks — expected at this stage: the phantom rocket MAY reproduce
     (the verify-patch is deliberately not applied yet; S14 applies it after reproduction).

**(e) Checklist items:** 1, 2, 7 (partial — margin-based until S17), 8, 10, 11 executable now; 3, 4, 5,
6 executable with command portals (thrown items/arrows/animals); 12 (relog into a world with live
portals). Item 9 partially (both-dim crossings).

**(f) Flag OFF by default — normal play protected. Rollback:** revert the flip commit; staging
exclusion restores; block-era behavior is untouched underneath either way.

---

### S14 — Flag-ON stabilization hold (L–XL, open-ended by findings)

**(a) Contents:** iterate the S13 script + the regression checklist under flag ON until stable; each
root cause its own commit + memory update (briefing §6 process). Scheduled work inside this hold:
- Reproduce, then apply, the two **PORT-FORWARD (verify)** patches as documented deviations
  (FD-4 firework skip, FD-5 hurt-state carry — current-mod-core §11.3/§11.4), on the ONE unified path.
- **R7 runtime proof:** double-crossing storms, packet-order corruption hunting (debug.log payload
  histogram; debug.log shows ONLY Fabric payload channels — briefing §2).
- **R8 proof:** position-sync at crossings (no ±360 wrap, no velocity zero — checklist item 2's
  numeric-rotation rule).
- **R9 proof:** dest-world fog/lightmap correctness in the view, no cross-dim fog leak.
- **§11.7 ACK-honesty empirical check:** IP has no delivery verification; if walking-limbo-class
  symptoms (chunk holes, limbo bands) reproduce under the ported world-keyed redirection, a reliability
  layer re-emerges as a NEW documented deviation → user decision UC-6. Must be proven, not assumed.
- The PORT-FORWARD (verify) client mixins (`ChunkPacketGuardMixin`,
  `ClientPacketListenerAddEntityAdoptMixin`, `ClientPacketListenerLocalPlayerFallbackMixin` —
  current-mod-core §9) compared against IP's own hooks and retained/retired with evidence.

**(b) Debt:** none. **(c) Commits:** per-fix.

**(d) runClient test:** the full S13 Run-2 script, plus soak sessions (20+ crossings, multi-portal
worlds, both dims). Exit gate: checklist items 1–8, 10–12 pass on command portals; 9 as far as
command-portal play exercises it.

**(e)** 1–8, 10–12. **(f)** Flag OFF default still; ON for all dev testing. Rollback: per-fix reverts.

---

### S15 — U12: portal generation (L)

**(a) Contents** (U12 row): `NetherPortalGeneration` pipeline, `NetherPortalMatcher`, `FrameSearching`,
`FastBlockAccess` (R13d wrappers already fixed at S5 — verify the consumers here), `CustomPortalGeneration`
+ forms/triggers (datapack registries at server start), `BreakablePortalEntity`/`NetherPortalEntity`/
`GeneralBreakablePortal`, `IntrinsicPortalGeneration` + ignition/trigger mixins — **including
`imm_ptl.peripheral.portal_generation`** (current-mod-core §0.2: the ignition hook lives in the
peripheral module; the port must include it). With `netherPortalMode != vanilla`,
`findEmptyPortalShape` is redirected empty so vanilla portal BLOCKS never form (current-mod-core §8) —
this is the structural suppression the S16 flip relies on.

**(b) Debt:** none (U12 depends on the closed U11 — genuinely layered tail).

**(c) Commits:** (1) core generation pipeline; (2) breakable family; (3) custom-gen + datapack;
(4) ignition mixins.

**(d) runClient test (flag ON, per U12 row + §4.1 milestone 3):**
1. Flint-and-steel an obsidian frame in the overworld. Correct: `LoadingIndicatorEntity` appears, async
   dest search/frame fabrication runs WITHOUT freezing the server, then a 4-portal cluster (both sides
   × both faces) links. Failure: hang (async pipeline on `MyTaskList`/`ServerTaskList` mis-wired),
   vanilla purple blocks appearing (suppression not active).
2. Existing-frame linking: build a matching frame near the dest before ignition; verify it links
   instead of fabricating.
3. Break a frame block: the portal entity dies (breakable revalidation); placeholder blocks clean up.
4. Negative-coordinate frame (e.g. around x=-128): exact linking, no off-by-one (checklist item 10,
   floored scaling).
5. First-visit worldgen: time the first crossing into fresh nether — graduated loading should have
   retired the 29s radius-32 stall class (briefing §5).

**(e) Checklist:** 1, 3, 4, 5, 6, 9, 10 (nether-portal E2E forms).

**(f)** Flag OFF default. Rollback: revert; command portals from S13 remain testable.

---

### S16 — CUTOVER: flip the default (S)

**(a) Contents:** `ENTITY_PORTALS` default → true. Block-portal system dormant (code present, init
gated off; deletion is S19's job per constraint 5). Old block-era worlds: existing portal blocks are
inert/uninteractable; document as a release note (new architecture, new saves recommended).

**(b) Debt:** none. **(c) Commits:** one (the default flip) — deliberately tiny.

**(d) runClient test:** the FULL 12-point regression checklist (briefing §4) in NORMAL play, no flags:
items 1–12 one by one, both dimensions, ignition-created portals, relog mid-session (item 12), 30+
minute soak with the off-thread spike monitor (item 11). What failure looks like per item is defined by
each item's memory file (briefing §4 lists them).

**(e) Checklist:** all 12.

**(f) Rollback: one line** (default back to false) — the entire point of the flag pattern; block
portals resume instantly.

---

### S17 — R3 verification + trailing periphery (L–XL)

**(a) Contents** (API_RISKS verdict stage 3 — each lands after the cutover without invalidating it):
- **R3:** `CrossPortalEntityRenderer` runtime verification + iteration on the S12 mechanism (item 7
  fully: straddling entities render whole in BOTH directions, damage flash in portal view, cross-portal
  punch/reach; supersedes the block-era entity clip margin).
- `GuiPortalRendering`, `OverlayRendering` activation; mirrors/`BreakableMirror` verification.
- **A1:** `RendererUsingFrameBuffer` + renderMode config live (default = port, UC-3).
- **A2:** view-bob — IP's `viewBobbingReduce` distance-scaled mechanism verbatim, replacing the mod's
  unconditional no-op (current-mod-render §2.1; a user-visible behavior RESTORATION, flagged UC-5).
- Portal-animation view refinements.

**(b) Debt:** none. **(c) Commits:** per feature.

**(d) runClient test:** stand an animal half-through a portal — whole-body render from both sides; take
damage visible through the portal; punch/place through the portal window; `/portal` a Mirror and check
reflection; switch renderMode config values (normal/compatibility/debug/none) and verify each mode
behaves (compatibility = FBO look, none = no portal view, no crash).

**(e) Checklist:** 7 (now fully), re-run 1, 2.

**(f)** Flag is default-ON; renderMode config is IP's own runtime switch. Rollback per-feature.

---

### S18 — U13 peripheral/compat tail (M–XL, user-scoped — see UC-1/UC-2)

**(a) Contents** (U13 row): wand items + client wand code (R13e/R6 Gizmos re-expression for overlays),
dim stack + GUI (R13e extract-model screens), alternate dims (R13g: dynamic dimensions
UNKNOWN-NEEDS-DESIGN at loader level — default STATIC-ONLY stub of the event wiring per
DEPENDENCY_ORDER §2.1/COVERAGE INFO-4; alt-dims worldgen additionally needs the definalized
`NoiseBasedChunkGenerator` + `noNewCaves` re-derivation), ModMenu config GUI (≙ `IPModMenuConfigEntry`),
compat `On*Present` layers + gated Sodium/Iris mixins (dormant on 26.2 until those mods port —
INFO-3: a full-depth pass on the 27 compat files is required IF that scope ever opens).

**(b) Debt:** none. **(c) Commits:** per feature. **(d) runClient test:** per feature (wand: create/
drag/sculpt a portal; dim stack GUI opens and applies; config screen round-trips values).
**(e)** none new; re-run 1–2 after each. **(f)** each feature independently revertable.

---

### S19 — U14: cleanup, deletion, final regression (L)

**(a) Contents** (U14 row + constraint 5 — deletion happens ONLY here, per the disposition tables):
- **DELETE per current-mod-core:** `PortalInfo`/`PortalLink`/`PortalManager`/`PortalTracker`/
  `PortalDetector`/`PortalType` block machinery, `SeamlessServerTeleport`/`SeamlessClientTeleport`/
  `PortalTeleporter`/`ProjectilePortalHandler`, `PortalChunkTracker` ACK ledger +
  `RedirectedPacketApplier` + `PortalEntityTracker` + `RemoteBlockUpdater` + snapshot-channel dead
  code, the bespoke `ModPayloads` rows marked DELETE/REPLACE-BY, `HandleRespawnMixin`,
  `PortalWorldManager` promote/demote (its mechanics were transplanted at S10/S11), the DELETE-marked
  mixins (§8/§9 tables).
- **DELETE per current-mod-render:** `CameraTransitionHandler`, `PortalSlicing`,
  `PortalFrameSuppressor`, the 9 DELETE mixins incl. the S3 husk (`GameRendererPortalPrepareMixin`),
  `StencilPortalRenderer`/`PortalContextSwitch`/`PortalShapeRenderer` (their proven pieces live on
  inside the ported shells since S11/S12).
- **SURVIVORS explicitly retained** (constraint 5): the stencil substrate mixins
  (`GlBackendMixin`/`GlConstMixin`/`RenderTargetMixin`/`GlStateManagerMixin`), `PortalRenderTypes`,
  `DimensionRenderHelper`, the `SeamlessClientChunkMap` machinery inside `ImmPtlClientChunkMap`,
  `FrontClipping`, `lateUpdateLight` (now homed in the ported `MyRenderHelper`), diagnostics
  (`PerfTimers`/`RenderSpikeMonitor`/`CrossingTracer`) routed through the `rlog` gate
  (`SeamlessPortalsConstants` — COVERAGE INFO-1: add its KEEP disposition line), the KEEP'd accessor
  set (`LevelExtractorAccessor`, `ClientLevelExtractorAccessor`, `ClientLevelChunkSourceAccessor`,
  `TicketTypeInvoker`…), `ServerLevelFireSpreadMixin` re-keyed to
  `ImmPtlChunkTracking.isPlayerWatchingChunkWithinRadius` (current-mod-core §8).
- **Remove the `ENTITY_PORTALS` flag** and the OFF path; delete `compileIpportDiag` + the empty
  staging tree; execute UC-4 (HandLightSmoother).
- Briefing §5 verifications: renderer-identity-on-cleanup residual (likely moot with promote/demote
  gone — verify), server entity population near portals doesn't grow.

**(b) Debt:** none. **(c) Commits:** (1) core deletions; (2) render deletions; (3) flag removal +
build cleanup; (4) survivor re-keys.

**(d) runClient test:** the FULL 12-point regression again on the deletion build, with emphasis on
item 12 (relog/kick/rejoin clean — lifecycle on BOTH `ClientLevel.disconnect` AND
`updateLevelInEngines(null)`) and item 11 (leak/logging), plus a disconnect-while-in-secondary-dim
session cycle.

**(e) Checklist:** all 12. **(f)** No flag remains; rollback = git revert of the deletion commits.

---

## 4. Forced-deviation register (constraint 1 — the exhaustive list; everything else is 1:1)

| id | deviation | forced by | owning stage | corpus cite |
|---|---|---|---|---|
| FD-1 | `WorldInfoSender` time-half deleted (weather-only) | 26.2 clock-map `ClientboundSetTimePacket`, no per-dim daylight boolean | S9 | API_RISKS R13j |
| FD-2 | Dim-sync protocol extended with per-dim seaLevel | 26.2 `ClientLevel` ctor trailing `int seaLevel`; only vanilla source is spawn info | S2 design, S7 impl | API_RISKS R1 |
| FD-3 | Namespace repackage + RPC FQN literal/whitelist rewrite + `seamlessportals` ResourceLocation namespace | new standalone mod; mixin/package hygiene | S0 (policy), every stage (gate) | DEPENDENCY_ORDER §4.2; mission constraint 6 |
| FD-4 | Attached-firework skip on the unified crossing path | IP's `restoreFrom` recreate verifiably has the phantom-rocket hazard; bug already paid for | S8 prepared, S14 applied after reproduction | current-mod-core §11.3 |
| FD-5 | `preserveTransientHurtState` onto the recreate branch | same `restoreFrom` loss class | S8/S14 | current-mod-core §11.4 |
| FD-6 | `TicketType` via private-`register` invoker at bootstrap + flag-bits choice | 26.2 registry-record TicketType; `TicketType.create` gone | S9 | API_RISKS R10; current-mod-core §11.5 |
| FD-7 | Entity-type: `build(ResourceKey)` restructure + block→chunk tracking-range conversion + no `forceTrackedVelocityUpdates` knob (inherently satisfied) | 26.2 builder API | S6 | API_RISKS R11; current-mod-core §10/§11.6 |
| FD-8 | Non-null `DataFixTypes` constant on `GlobalPortalStorage` | 26.2 forbids null; failure mode is SILENT data loss | S2 spike, S7 bound | API_RISKS R11; portal-generation G1 |
| FD-9 | The 26.2 render-split substrate layer (extractor identity, SectionUpdateTracker, `endFrame` lifecycle, SOG delta feed, extract↔compileSections pairing, budgeted compile scheduling pending A3) | no IP source exists for any of it; 26.2 frame model | S10/S11 | current-mod-core §11.1; API_RISKS R1/R13f; current-mod-render A3 |
| FD-10 | Packet re-queue re-expressed per network.md §A (`packetProcessor().scheduleIfPossible`) | `PacketProcessor` split; verbatim port reorders cross-queue | S7 | API_RISKS R7 |
| FD-11 | DimLib replaced by static-dimension stub of the event WIRING only | DimLib has no 26.2 form | S18 (full), S7 (wiring) | DEPENDENCY_ORDER §2.1; R13g; COVERAGE INFO-4 |
| FD-12 | Position-packet dimension stamp re-mechanized (codec-wrap or paired packet) | record + composite codec, no tail slack | S10 | API_RISKS R8 |
| FD-13 | (conditional) chunk-send reliability layer, ONLY if walking-limbo symptoms reproduce under IP tracking | 26.2-real client-drop lessons vs IP's no-ACK model | S14 → UC-6 | current-mod-core §11.7 |
| FD-14 | (conditional) pinned-bounded ViewArea, ONLY if SPIKE-R4 disproves the ImmPtlViewArea rebuild | 26.2 ViewArea rework | S12 | API_RISKS R4 |

Where vanilla behavior is faithful, vanilla is left alone (briefing §6: firework billboard rotation
class; R13b's obsolescence-by-vanilla audits record DROPS of IP patches vanilla now covers — those are
fidelity-to-intent, logged in DECISIONS.md, not deviations).

---

## 5. User-decision checkpoints (default = core-complete)

| id | decision | default | when |
|---|---|---|---|
| UC-1 | Peripheral features: wand items+GUI, dim stack, alternate dims (R13g dynamic dims UNKNOWN) | SKIP for now (core-complete); compile-closure files (`PortalWandInteraction`, `CommandStickItem`, `IPFeatureControl`) port regardless (cycle 9) | before S18 |
| UC-2 | Sodium/Iris compat | shells only (compile-mandatory subset per cycle 12); full compat dead on 26.2 until those mods port (INFO-3 full-depth pass then) | before S18 |
| UC-3 | A1: FBO fallback renderer + renderMode config | PORT (IP ships it as a live config mode; fidelity) — lands S17 | S12 spec |
| UC-4 | A5: HandLightSmoother + its mixin (additive, no IP analog) | KEEP until S19; user rules on whether zero-deviation bans additive comfort features | S19 |
| UC-5 | A2: view-bob — IP `viewBobbingReduce` verbatim (bob RETURNS away from portals; user-visible change vs today's no-op) | IP-verbatim (today's no-op is the deviation) | S17 |
| UC-6 | FD-13: reliability layer if limbo-class symptoms reproduce | investigate first; deviation only with evidence | S14 |
| UC-7 | Flag-flip timing: S16 executes only after the user signs off S14+S15 gates | user gate | S16 |

---

## 6. Risk placement map (where each R1–R13 lands)

| risk | design/spike | implementation | runtime proof |
|---|---|---|---|
| R1 seaLevel + re-entrant render | S2 SPIKE-R1 (gating design round) | S7 (protocol), S10 (ClientWorldLoader), S11 (MyGameRenderer) | S13 run 2, S14 |
| R2 frame-phase anchor | S3 design round (gating) | S3 (live relocation, soaks under block portals) | S3 regression; re-confirmed S13 |
| R3 per-entity clip | S12 design round (compile-forced; see Appendix B-3) | S12 port | **trails cutover: S17** |
| R4 ViewArea fidelity | S2 SPIKE-R4 | S11 (ImmPtlViewArea) | binding decision **inside S12 cutover spec**; proof S13/S14 |
| R5 reversed-Z/stencil sign flips | substrate already proven (KEEP mixins) | **inside S12 cutover spec** (sign-flip table) + S12 RendererUsingStencil | S13/S14 |
| R6 immediate-draw re-expression | mechanisms proven (PortalRenderTypes etc.) | S11–S12 | S13/S14, S17 (overlays), S18 (wand) |
| R7 packet re-queue ordering | S2 SPIKE-R7 | S7 (network.md §A) | S13/S14 (double-crossing storms) |
| R8 position stamp | S10 decision (owning stage, lock-step) | S10 | S13/S14 |
| R9 fog/lightmap/environment | S11 design (fog ownership, probe isolation) | S11 | S13/S14 |
| R10 ticket flags + registration | S9 decision (owning stage) | S9 (+S10 addPlayer-path mixin) | S13/S15 |
| R11 SavedData/EntityType/NBT | S2 SPIKE-R11 | decisions at owners: S6 (entity), S7 (DataFixTypes), S8 (spawn reasons) | S13 step 4 (silent-loss check), S15 |
| R12 collision/inside-blocks | S6 (co-port, re-derive) | S10 (MixinEntity core + anticheat + removeVehicle) | S13/S14 |
| R13a–k | at owners: a S8/S10 · b S10 audit · c S12 · d S5 (verify S15) · e S18 (+S11 debug text) · f S9/S10 · g S18 · h S0 · i S12 spec · j S9 (FD-1) · k S11 | — | S13→S17 |

---

## Appendix A — why the least work is invalidated if a blocker surfaces

- A blocker in R1/R2/R4/R7/R11 surfaces at S2–S3 (cost: a spike). Nothing is invalidated.
- A blocker in staging translation surfaces at the stage that ports it; staged units are 1:1 ports
  whose correctness is independent of later units (API_RISKS: all mechanical renames absorbed by the
  slice maps), so earlier staged work survives any later blocker. The two highest-translation-risk
  units (U9/U10) are LAST in the march — a render blocker there invalidates no downstream port work.
- A blocker at S13 is bounded by the S12 ledger gate to U11-file surprises.
- A behavioral blocker post-closure is bounded by the flag: normal play never depended on the new path
  until S16, and S16 is a one-line revert.

## Appendix B — corpus doubts (flagged, not silently deviated)

1. **`PortalGenInfo` unit assignment inconsistency:** DEPENDENCY_ORDER's U2 debt column says
   `O_O`→`PortalGenInfo` (U9), but the U11 row lists `PortalGenInfo` among the closure co-ports and U9's
   contents don't include it. This plan follows the U11 row (closure co-port at S13); if it actually
   needs staging by S11, the ledger will say so and it moves — no structural impact.
2. **`CrossPortalEntityRenderer` unit assignment:** listed in U9's contents but cited as U10 debt in
   the U3 row. Cosmetic; this plan stages the class with the render units and pins its design at S12.
3. **R3 "design+port trails the cutover" vs compile closure:** mission constraint 4 says R3 design+port
   trail, but `IPMcHelper` (U3) imports `CrossPortalEntityRenderer` (DEPENDENCY_ORDER §2.2), so the
   class must exist in compilable 26.2 form at S13, and its hard parts target GONE APIs (API_RISKS R3)
   — no stub is permitted (constraint 2). This plan resolves the tension by scheduling the R3 design
   round + 1:1 port at S12 and interpreting "trails" as *its runtime verification and correctness do
   not gate the cutover* (consistent with the verdict's own rationale: "additive… its absence at
   cutover is status quo, not regression"). If the mission authors intended literal trailing, the only
   alternatives are a stub (forbidden) or modifying IPMcHelper (deviation) — flagged for the user.
4. **Briefing §4 "test all after each migration stage":** literally inapplicable to staging stages
   (S4–S12 change no live behavior). Interpreted as: full applicable-checklist runs after every
   behavior-affecting stage (S3, S13–S19), quick item-1 smoke otherwise.
