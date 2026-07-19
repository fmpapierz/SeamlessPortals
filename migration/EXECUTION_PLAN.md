# EXECUTION PLAN — the entity-portal migration (FINAL, synthesized 2026-07-12)

**This is the definitive staged execution plan.** It supersedes the three drafts in
`migration/plan-drafts/` (kept for provenance only — an executor needs THIS file plus the corpus,
nothing else). Structure and fidelity discipline come from the winning draft (plan-fidelity); the
judged-and-verified grafts from plan-risk (spike batch, R3 compile-closure resolution) and
plan-testability (four-rung bring-up ladder, exclusivity ledger, live R2/A4 soak stage, diff gate,
gametest smoke) are integrated where the judges proved the winner weak.

**Mission:** convert Seamless Portals from block-based portals to Immersive Portals' entity-based
portals with **COMPLETE IP fidelity — zero deviation** (user directive 2026-07-08). IP source
(1.21.3, Mojang mappings): `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`
(cited `IP:`). Decompiled MC 26.2: `C:/Users/warwa/ModDev/mc262-ref` (cited `26.2:`).

**Ground truth this plan builds on (and never re-derives):**
- `ENTITY_PORTAL_MIGRATION_BRIEFING.md` — mission, the 12-point regression checklist (§4, cited
  below as "item N"), porting discipline (§6).
- `migration/DEPENDENCY_ORDER.md` — units U0–U14, cycles §3, port order §4, runtime ordering
  constraints §4.2, compile-vs-runtime cheat sheet §4.3.
- `migration/API_RISKS.md` — risks R1–R13 + the cutover verdict (staged substrate → ATOMIC
  flag-gated driver core → trailing periphery).
- `migration/api-map/current-mod-core.md` + `current-mod-render.md` — REPLACE/DELETE/KEEP/
  PORT-FORWARD disposition tables.
- `migration/COVERAGE.md` (INFO-1..4) and `migration/inventory/*.md` for per-class LOC detail.

**Invariant for every stage (hard constraint 2):** the stage ends with

```
cd "C:/Users/warwa/ModDev/Portals/Portal 26.2"
.\gradlew.bat :common:compileJava :fabric:compileJava --console=plain --no-daemon
```

GREEN, and the game fully playable — block portals keep working until S17 flips the default.
Never `gradlew --stop`; kill only PIDs whose command line contains this project's path
(machine rule). The USER compiles, commits (`git commit -F <file>` — briefing §6), and runs
runClient between stages.

---

## 1. Plan-shaping decisions (settled at S0, applied uniformly)

### D1. Holding mechanism: property-gated held-paths filter at the compile-TASK level, in buildSrc

DEPENDENCY_ORDER §4 ground rules name the only fidelity-preserving option for the SCC problem:
"hold un-closed units in an excluded source set until their closure lands (build engineering, not
code change)". The concrete device — **verified against this repo's actual build wiring**:

- **Ported IP source lands at its FINAL path from its first commit**:
  `common/src/main/java/qouteall/**` (package policy D2). No later `git mv` — per-stage diff
  review and git history compare the same file forever, and `git diff --no-index` against the IP
  tree stays valid (D4.3).
- **A single gradle property `ip_scc_closed` (default `false` in `gradle.properties`) gates a
  held-paths exclusion applied to the COMPILE TASKS, not the source set**, defined once in
  buildSrc and wired into:
  1. `:common:compileJava` (via `multiloader-common.gradle`);
  2. **the loader modules' `compileJava`** (via `multiloader-loader.gradle`) — MANDATORY, because
     `tasks.named('compileJava'){ source(configurations.commonJava) }`
     (multiloader-loader.gradle:20-22, verified this pass) makes `:fabric:compileJava` recompile
     the RAW common source directory; a filter that lives only on common's source set does NOT
     propagate there and turns `:fabric` red the moment the first held file lands (this exact
     defect sank two of the three drafts);
  3. **`:common:compileTestJava` — with its OWN held-paths list, NOT the main list.** No single
     shared list can serve both: IP's carried tests live at `qouteall/q_misc_util/my_util/` in
     the test tree — INSIDE the my_util carve-in path — yet both are un-compilable until S13
     (`HelperTest` imports `Helper` directly, verified `IP:src/test/.../HelperTest.java:7`;
     `Mesh2DTest` imports `Mesh2D`, which itself imports the held `Helper` at
     `IP:.../Mesh2D.java:24`). A shared `my_util/**` carve-in (the refuted draft design)
     un-holds them at S2 and turns `:common:test` red on the unresolved `Helper` symbol;
     sharing the actual main list (a Helper-free FILE subset whose patterns match no test file)
     or the un-carved `qouteall/**` excludes the authored S2 tests too and makes the harness
     silently vacuous. The test-task list therefore DIVERGES: at S2 it carves in
     `qouteall/q_misc_util/my_util/**` for tests while retaining explicit
     `**/Mesh2DTest.java` + `**/HelperTest.java` exclusions until S13. "One list filters both"
     holds only for the two MAIN compile paths (this slip class sank a draft's very first gate).
- **Never add a second srcDir to common's main source set**: `common/build.gradle:35` publishes
  the source dir as `artifacts { commonJava sourceSets.main.java.sourceDirectories.singleFile }`
  (verified this pass) — a second srcDir breaks `singleFile` and the whole loader consumption
  chain. One directory + a task-level filter is the only mechanism that keeps the stated gate
  green as written.
- The exclusion is a **held-paths pattern list** (`qouteall/**` minus explicit carve-ins),
  applied as `exclude` patterns on each JavaCompile task when `ip_scc_closed != true`. The two
  MAIN compile paths (`:common:compileJava` + loader `compileJava`) see the same relative paths,
  so one list filters both of THEM; the test task carries its own divergent list (item 3 above).
  **Main-list carve-ins are monotone** — patterns only ever get narrower, each narrowing
  reviewed in its stage commit. **The stage-time import-grep is a mandatory FILTER on every
  carve-in, not a confirmation, and same-package references count** (they need no import
  statement, so import-grep alone misses them): S2 carves in the **Helper-free subset** of
  `qouteall/q_misc_util/my_util/` — NOT `my_util/**` wholesale, because eight my_util files
  import the HELD `qouteall.q_misc_util.Helper` (Mesh2D.java:24, IntBox, IntMatrix3, MyTaskList,
  Signal, SignalArged, SignalBiArged, LimitedLogger — grep-verified) and Helper cannot compile
  until S13 (Helper.java:29 imports McHelper); carving in `my_util/**` wholesale turns
  `:common:compileJava` AND `:fabric:compileJava` red at S2. DEPENDENCY_ORDER §2.1's "zero
  imm_ptl imports" is true but does not mean Helper-free. S4 carves in the 33 genuinely
  vanilla-only duck interfaces — NOT all 36 (§2.9's blanket claim is wrong: three ducks import
  held IP classes — Appendix A.6 erratum iii) — plus the zero-import `@IPVanillaCopy`
  annotation type (`qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy` — imported by 20 files
  landing across S4–S12; the S4 carve-in resolves them ALL in-probe, see S4(a)). Everything
  else stays held until S13 flips `ip_scc_closed=true`.
- **NOT stubs, NOT simplified variants** (hard constraint 2): held files are complete, final,
  verbatim-translated ports; javac simply does not see them yet. Zero throwaway IP-code variants
  are ever written (spikes are governed separately by D6).
- **The same property doubles as the per-stage compile PROBE — the machine-checked forward-ref
  debt ledger:**
  `.\gradlew.bat :common:compileJava -Pip_scc_closed=true --console=plain --no-daemon`
  compiles the whole held tree on demand (`:common` alone is enough — the loaders recompile the
  same sources). It is EXPECTED red until S13. **Triage rule:** every error must map to the
  stage's documented forward-ref debt — **the per-stage (b) ledger in THIS plan**, which starts
  from the "Forward refs (SCC debt)" column of DEPENDENCY_ORDER §4 but corrects its known
  errors (Appendix A.6 errata) and adds the refs the columns omit — a NON-EXHAUSTIVE sample:
  the S4 ~11 omitted refs (IPCGlobal→renderer family, O_O→McHelper, DimensionIntId→
  McHelper/MiscNetworking, GravityChangerInterface→CHelper/McHelper,
  SodiumInterface→FrustumCuller), the S5 CHelper→StableClientTimer edge, the S6
  CollisionHelper refs, the S8 ClientTeleportationManager→ClientWorldLoader/FrontClipping/
  WorldRenderInfo edges, the S9 ChunkLoader→FastBlockAccess edge, the S10 IPModMain U11/U12 +
  IPModMainClient refs. **The per-stage (b) ledgers are the complete PAPER lists — this
  parenthetical is illustrative only — and the paper itself is a best-effort PREDICTION: the
  probe's actual error list is the final authority (D4.2 NOTE).** Any error OUTSIDE
  the stage ledger is triaged per the D4.2 NOTE against the actual import graph (imports +
  same-package references): an import-graph-justified miss becomes a ledger amendment committed
  with the stage; anything else is a translation slip fixed at the stage that introduced it —
  never at closure, never by simplifying. Use generous
  `options.compilerArgs` error limits so cascades are fully visible. **Hard pre-closure gate:**
  at the end of S12, the probe's error list may reference ONLY the S13 closure set: the U11
  closure files (`commands/*` + `api/example/ExampleGuiPortalRendering`,
  `block_manipulation/*`, `miscellaneous/`, `debug/`, `PortalGenInfo`,
  `CustomPortalGenManager`, `IPConfigGUI` under the F21 autoconfig stub), the WAND compile
  shell (all 9 `peripheral/wand` files — `PortalWandInteraction`, `PortalWandItem`,
  `ProtoPortal`, `ProtoPortalSide`, `PortalCorner`, `WandUtil`,
  `ClientPortalWandPortalCopy`/`Creation`/`Drag`) + `CommandStickItem`, the dim_stack compile
  shell (ALL 11 `peripheral/dim_stack` files — the GUI half is compile-reachable from
  `DimStackManagement`, see S13(a)) extending into `alternate_dimension` (incl. its three
  `peripheral/mixin/common/alternate_dimension` accessor interfaces), AND the U12 closure
  slice the SCC provably extends into (`NetherPortalGeneration`, `NetherPortalMatcher`,
  `FastBlockAccess`, `FrameSearching`, `BlockTraverse`, `FastBlockPortalShape`,
  `SimpleBlockPredicate`, `PortalGenTrigger`, `BreakablePortalEntity`, `NetherPortalEntity`,
  `GeneralBreakablePortal`, `CustomPortalGeneration` + the ENTIRE 12-file `form` package —
  `PortalGenForm` codec-registers all seven remaining forms in its own static body and
  `AbstractDiligentForm` uses `DiligentMatcher`, S13(a)) —
  evidence at S13(a) and Appendix A.4). This converts the closure from a big-bang into a
  bounded, monotone-audited burn-down.
- Mixin configs are resources, held separately: ported mixin CLASSES live in held source; the
  IP-side mixin JSONs list them from the stage they land but are **not registered** with the
  loaders until S13 (then plugin-gated per D3).
- The holding machinery (property + both held lists + probe note) is deleted at S20.

### D2. Package/namespace policy: verbatim retention of `qouteall.*`

Recorded decision (hard constraint 6): **ported IP code keeps IP's original packages exactly**
(`qouteall.q_misc_util.*`, `qouteall.imm_ptl.core.*`, `qouteall.imm_ptl.peripheral.*`), including
mixin packages and ResourceLocation namespaces where IP hardcodes them. Rationale:

1. **RPC FQN strings are wire protocol** (DEPENDENCY_ORDER §4.2): `McRemoteProcedureCall`
   addresses handlers by literal FQN strings (e.g.
   `qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.RemoteCallables.updateEntityPos`).
   Cross-mod wire compat is not required (new standalone mod), but a rename would demand catching
   every hardcoded literal, whitelist entry, mixin-JSON package and reflection site — each miss a
   RUNTIME-ONLY failure. Verbatim retention reduces that hazard class to zero.
2. **The diff gate becomes mechanical** (D4.3): with paths preserved,
   `git diff --no-index <IP>/src/main/java/qouteall/<unit> common/src/main/java/qouteall/<unit>`
   is a per-stage 1:1 fidelity check where every hunk must be an API translation traceable to a
   slice-map row or a forced-deviation register entry. It works even for HELD units — giving the
   silent staging stages a real, mechanical definition-of-done before the code ever compiles.
3. Collision risk with upstream IP on 26.2 is nil (upstream stops at 1.21.3; this mod is the
   successor line — memory `seamless-portals-is-ip-port`). Coexistence was never a goal.
4. NEW code with no IP original (U0 loader seams, build machinery) lives in
   `com.warwa.seamlessportals.*` — the ported/mod-owned boundary is visible in the package name.

### D3. Flag strategy + the exclusivity ledger: `entityPortals`, STENCIL_DIRECT pattern, one driver per session

Per the API_RISKS verdict ("the atomicity is in the user-facing switch, not the development
process"; the mod has already proven flag-gated dual render paths via `STENCIL_DIRECT`):

- One boolean key `entityPortals` in the existing `SeamlessPortalsConfig` properties file (KEEP,
  current-mod-core §7). Default `false` until S17; `true` from S17; deleted with the old system
  at S20. **Load-time, not runtime**: read once at mixin-plugin time by the KEEP'd
  `SeamlessMixinConfigPlugin` (≙ IP's `IPMixinPlugin` family, current-mod-core §8). Flipping
  requires a game restart — accepted; it avoids every half-initialized-manager hazard.
- **The exclusivity ledger** — `migration/EXCLUSIVITY_LEDGER.md`, a COMMITTED artifact (skeleton
  at S0, fully populated and enforced at S13 BEFORE the first flag-ON run, updated at S16,
  removed at S20) — is the explicit dual-driver contract that makes flag honesty auditable:
  - **Flag ON suppresses the block-era DRIVERS** (each gated `!entityPortals` in mod-owned code,
    landed in the SAME commit that registers the IP mixin set at S13): `LocalPlayerMixin`
    crossing detection, `EntityMixin` server detection + `PortalTeleporter`,
    `ProjectilePortalHandler`, `PortalManager`/`PortalDetector`/`PortalTracker` scans,
    `PortalChunkTracker` tick + `RedirectedPacketApplier`, `PortalEntityTracker`,
    `RemoteBlockUpdater`, `SeamlessClientTeleport`/`SeamlessServerTeleport` dispatch,
    `StencilPortalRenderer`/`PortalContextSwitch`/`PortalWorldManager` entry,
    `CameraTransitionHandler`.
  - **Block-era SUPPRESSIONS that stay active in BOTH states**: the `handlePortal` cancel keeps
    vanilla nether-portal blocks inert in flag-ON worlds until S16 replaces it with IP's
    structural suppression (redirected `findEmptyPortalShape`, ledger updated then).
  - **Substrate KEEPs that apply ALWAYS** (both flag states): the stencil FBO chain
    (`GlBackendMixin`/`GlConstMixin`/`RenderTargetMixin`/`GlStateManagerMixin`), extractor
    plumbing accessors, `GlCommandEncoderClipMixin`, `ShaderManagerCompilationCacheMixin`,
    `DimensionRenderHelper` + `GameRendererLightmapMixin`, `PortalRenderBuffersPool.endFramePooled`,
    `lateUpdateLight`, `FrontClipping`, diagnostics (F16).
  - **Shared host mixins** (e.g. the S3-relocated pre-render pump host): the MOD-OWNED mixin body
    dispatches on the flag to either the old pump or the ported IP call chain — mod code
    dispatches, IP code bodies are never flag-polluted (zero-deviation preserved).
  - **Consequence: at no point do two portal drivers run in one session** — the operational
    answer to API_RISKS' shared-mutable-seams / one-driver-per-frame prohibition. (The winning
    draft left S11–S12 flag-ON runs with both driver sets registered; this ledger + the S13
    gating commit closes that window.)
- **Registry entries are unconditional** from S13 (Portal entity-type family,
  `PortalPlaceholderBlock`, argument types, payload registrations): registries must not differ
  between flag states or world saves break on flips. Documented consequence: flag-ON testing
  happens in DEDICATED test worlds; never open a main world with the flag ON before S17.
- Rollback at any stage S13–S19 = `entityPortals=false` (or revert the stage's commits). After
  S20 rollback is git-only — which is why S20 is gated on full green regressions at S17–S18.

### D4. Fidelity instruments (every stage's gate, in order)

1. **Shipping build green**: the invariant command above (`:common` + `:fabric`).
2. **Compile-probe triage** (pre-closure stages): `-Pip_scc_closed=true` run; error list diffed
   against the stage's documented forward-ref debt (D1). Out-of-debt errors fixed NOW against the
   owning slice map in `migration/api-map/` — never by simplifying.
   **NOTE (round 3 — ledger governance):** the per-stage paper ledgers (each stage's (b)
   section) are best-effort PREDICTIONS; the `-Pip_scc_closed=true` probe's actual error list
   is the AUTHORITATIVE ledger. At each stage, diff the probe output against the paper ledger
   and triage every discrepancy against the ACTUAL import graph (import lines PLUS same-package
   references — they need no import statement): a symbol the paper ledger missed but the import
   graph justifies is a PLAN AMENDMENT — the ledger line is added and committed with the stage;
   an unexpected symbol the import graph does NOT justify is a translation slip — fixed before
   the stage closes. Ledger drift is thereby a governed process, never a silent failure.
3. **Source-diff review gate**: for every ported file,
   `git diff --no-index` against the IP original. Allowed hunks: the mechanical 26.2 translations
   enumerated by the owning slice map (API_RISKS preamble list + slice CHANGED rows) and the
   S0-decided loader-seam substitutions. ANY other hunk is reverted or written up as a numbered
   entry in the stage's port-note (`migration/port-notes/S<NN>-<unit>.md`).
4. **Sign-derivation notes**: every geometry/depth/winding constant or comparison ported
   (transforms, clip planes, frustum windings, depth funcs, stencil ops) gets a WRITTEN
   re-derivation from 26.2 source in the port-note — briefing §6 "never trust a prior
   geometry-sign claim" (bitten twice); API_RISKS R5 names reversed-Z as the live bug class.
   Anti-deviation guard list rides along (see register footer): FrontClipping's `Vector4f.mul`
   idiom STAYS; firework billboard rotation STAYS.
5. **JUnit math harness** (from S2): `.\gradlew.bat :common:test` — the S2-active payload is the
   authored DQuaternion/Plane invariant tests (NOT IntBox — it imports the held `Helper`; IntBox
   tests activate at S13). The carried `Mesh2DTest` + `HelperTest` land at S2 but are BOTH held
   via the D1 test-task list until S13: Mesh2DTest cannot activate earlier because `Mesh2D`
   itself imports `Helper`, so compiling the test requires Helper→McHelper→the whole SCC
   (COVERAGE INFO-2; DEPENDENCY_ORDER §4.1 milestone 1).
6. **Gametest crossing smoke** (from S15): the KEEP'd fabric gametest harness
   (`fabric/src/main/java/com/warwa/seamlessportals/fabric/gametest/TitleCardCapture.java`,
   verified present) extended with an automated server-side crossing test (spawn portal + entity,
   assert arrival) — the permanent automated regression net for the crossing path, run at every
   later stage.
7. **User runClient script** per the stage section, then user commits.

### D5. Decisions settled by the zero-deviation directive itself

- **A1: PORT the full renderer family** (`RendererUsingFrameBuffer`, `RendererDummy`,
  `RendererDebug`, four-way `renderMode` config — live code at IP client init). The mod's FBO
  machinery refactors INTO `RendererUsingFrameBuffer` rather than deleting (current-mod-render A1,
  flipped by the directive — recorded in Appendix A.3).
- **A2: port IP's `viewBobbingReduce` distance-scaled bob verbatim** replacing the mod's
  unconditional no-op — visible behavior change flagged at checkpoint C5.
- **A3**: the one-time comparative read of IP's `ForceMainThreadRebuild` vs the mod's folded-in
  budgeted compile scheduling is a scheduled S11 deliverable.
- **A5 (`HandLightSmoother`)**: checkpoint C6 — additive comfort feature, default KEEP.
- **FrontClipping mul idiom / firework billboard**: anti-deviation guards, see D4.4.

### D6. Spike discipline (branch-only empirical probes — and why they don't violate the no-throwaway rule)

S1 runs four never-merged probes on `spike/*` branches, each producing a memo in
`migration/spikes/`. Boundary defense, stated once: hard constraint 2 bans **stub or simplified
variants of IP code held in the shipped tree** as a holding mechanism. Spikes are the opposite
category on every axis: (i) never merged — no spike line ever enters the shipped tree; (ii) they
probe **vanilla 26.2 API surfaces** (ClientLevel ctor, PacketProcessor, ViewArea subclass surface,
SavedDataType), not IP logic — no IP class is ever written in a throwaway form; (iii) their
deliverables are DESIGN EVIDENCE (memos + decided constants), which the zero-deviation port then
consumes. SPIKE-R4 in particular is scoped to a minimal pass-through `ViewArea` subclass proving
ctor/override/redirect viability only — explicitly NOT a toy `ImmPtlViewArea` variant.

### D7. R3 constraint reconciliation (flagged to the user — resolves a real tension in the mission text)

Hard constraint 4 says "R3 design+port trails the cutover". But `IPMcHelper` imports
`CrossPortalEntityRenderer` (`IP:imm_ptl/core/IPMcHelper.java:22-24`; DEPENDENCY_ORDER §2.2), so
the class must exist in COMPILABLE 26.2 form at the S13 closure — and its hard parts live in the
class BODY, not just a client hook: `client.renderBuffers().bufferSource().endBatch()` at
`IP:imm_ptl/core/render/CrossPortalEntityRenderer.java:123,139` and `:208`, plus
`consumers.endBatch()` at `:308` (verified this pass), all targeting the GONE mid-batch-flush
model (API_RISKS R3), plus the dead `renderEntity` duck. No stub is permitted (constraint 2);
modifying `IPMcHelper` to drop the import is a deviation. **Resolution (the only coherent one):
the R3 compile-level design round + full 1:1 port happen at S11 (pre-closure, inside the render
unit); "trails the cutover" means R3's RUNTIME wiring, verification and iteration trail (S18).**
This matches the API_RISKS verdict's own rationale ("additive… its absence at cutover is status
quo, not regression"). If the user intends literal trailing, the alternatives are a stub
(forbidden) or an IPMcHelper edit (deviation) — decide at checkpoint C4.

---

## 2. Stage table

Effort scale (inventory LOC + api-map changed/gone density): **S** ≤ ~300 LOC or config-only ·
**M** ~300–1,500 · **L** ~1,500–4,000 · **XL** > 4,000 LOC or high design density.

| Id | Name | Contents (unit refs → DEPENDENCY_ORDER §4) | Effort | Gate (beyond D4 standard) |
|---|---|---|---|---|
| S0 | Substrate + decisions | U0: holding machinery (D1), namespace record (D2), flag+ledger skeleton (D3), loader seams, mixin-config skeletons, test infra | M | build green; baseline sanity; decisions note committed |
| S1 | Spike batch (learning-only) | SPIKE-R1 seaLevel, SPIKE-R7 re-queue, SPIKE-R4 ViewArea, SPIKE-R11 SavedData — branch-only (D6) | M | 4 memos in `migration/spikes/`; R1 protocol design v1; nothing merged |
| S2 | q_misc_util + math harness | U1 (carve-in: Helper-free my_util subset ONLY; held: Helper + the 8 Helper-importing my_util files + AARotation); carried Mesh2DTest+HelperTest both held to S13; authored DQuaternion/Plane tests live | XL | `:common:test` green |
| S3 | LIVE substrate: R2 anchor + A4 re-home | R2 design round + pump relocation, A4 upkeep re-home — live under block portals, soaks S3→S17 | M | live-substrate regression script passes |
| S4 | Ducks, roots, platform facade | U2 (carve-in: 33 vanilla-only ducks + `@IPVanillaCopy` + AW/AT; held: IEClientWorld/IEEntity/IEMinecraftServer + O_O/RequiemCompat/IPConfig/IPPerServerInfo + mc_utils incl. IPEntityEventListenableEntity + IELevelStorageAccess_Misc + the sodium_compatibility trio IESodiumWorldRenderer/SodiumRenderingContext/IESodiumRenderSectionManager + IPFlywheelCompat…) | M | probe = the S4(b) ledger only |
| S5 | MC helpers | U3 (all held) + R13d wrappers | M | probe = the S5(b) ledger only |
| S6 | Portal core + shapes + animation | U4 + co-ports (BlockPortalShape, CollisionHelper, PortalCollisionHandler, PortalCollisionEntry); R11 entity decisions | XL | probe = the S6(b) ledger; R11 decisions recorded; transform sign-notes |
| S7 | Network, global portals, API | U5 + R7 §A impl (spike-confirmed) + R1 seaLevel protocol impl + R11 DataFixTypes bound + the q_misc_util mixin tree (4 files incl. MixinPlayerList_Misc) + CustomTextOverlay (held) | L | probe = the S7(b) ledger; protocol notes |
| S8 | Teleportation + collision | U6 + R8 design + R12/R13a/R13b re-derivations | L | probe = the S8(b) ledger; collision re-derivation notes |
| S9 | Chunk loading + entity sync | U7 + R10 decisions + R13j forced deviation (F1) + R13f carriage | L | probe = the S9(b) ledger; ticket decision record |
| S10 | ClientWorldLoader + common mixins | U8 (74 common mixins) + R1 impl + R8 impl (both halves, one commit) + IPModMain/IPModMainClient init sequences (incl. the MiscUtilModEntry/MiscUtilModEntryClient cargo — classes seam-absorbed, Appendix A.9) | XL | probe = the S10(b) ledger only (render-context + IPModMain/IPModMainClient init-registration debt) |
| S11 | Render context + CUTOVER_SPEC + R3 compile design | U9 (+ round-3 owning-stage assignments: SharedBlockMeshBuffers, CrossPortalViewRendering, PortalEntityRenderer, OverlayRendering, LoadingIndicatorRenderer, WireRenderingHelper — all held) + CUTOVER_SPEC.md (R4 decision on spike evidence, R5 checklist, R9 design, R2/A4 restated) + **R3 compile-level design round (D7)** | XL | probe = the S11(b) ledger; CUTOVER_SPEC.md committed; R3 design doc |
| S12 | Renderers + client mixins | U10 (full renderer family + Iris shells + ~62 client mixins) + IPModInfoChecking (held, F12-seamed) + R5 execution + R13c/i/k + A2 | XL | **probe errors reference ONLY the S13 closure set: U11 files + the wand/dim_stack/alternate_dimension compile shells + the U12 closure slice** (hard gate) |
| S13 | SCC closure + FIRST LIGHT rung 1 (same-dim) | U11 closure (incl. ExampleGuiPortalRendering + IPConfigGUI under the F21 autoconfig stub) + the U12 closure slice (generation-pipeline head incl. FrameSearching/BlockTraverse/FastBlockPortalShape/PortalGenTrigger + the entire 12-file form package) + the wand/dim_stack/alternate_dimension compile shells + the redstone disposition file; `ip_scc_closed=true`; burn-down; mixin registration + flag wiring + **exclusivity ledger enforced**; first flag-ON run: same-dimension command portals | XL | full tree green; `:common:test` green incl. Mesh2DTest+HelperTest; rung-1 script passes; flag-OFF unchanged |
| S14 | Bring-up rung 2: cross-dim | flag-ON nether portals by command — R1 seaLevel, R7 transport, R9 visuals first runtime contact; R10's first ACTUALLY-LOADING cross-dim ticket contact (the ticket machinery itself first runs at S13 rung 1 — see S13 preamble) | L | rung-2 script passes |
| S15 | Bring-up rung 3: entity traffic + gametest | non-player crossings on the ONE unified path; F2/F3 reproduce-then-apply; gametest crossing smoke | M | rung-3 script + gametest green |
| S16 | Portal generation (rung 4) | U12 runtime remainder (IntrinsicPortalGeneration + `peripheral/portal_generation`, ignition mixins, datapack registries — the whole form package already compiled at S13) + runtime bring-up of the S13-compiled generation head (incl. FrameSearching); structural suppression replaces handlePortal cancel (ledger update) | XL | flag-ON nether E2E script passes |
| S17 | CUTOVER FLIP | `entityPortals` default → true; full 12-point regression; R4+R5 spec sign-off | M | all 12 checklist items green |
| S18 | Trailing periphery + R3 runtime | R3 delivery live + verification; GuiPortalRendering/OverlayRendering/mirrors/renderMode/A2 sign-off | L | item 7 fully green |
| S19 | Peripheral tail (USER DECISION) | U13: wand, dim stack, alt dims, compat On*Present, ModMenu GUI — default: core-complete (skip) | S–XL | per-feature |
| S20 | Cleanup + deletion | U14: delete old system per disposition tables; survivor audit; flag + ledger + holding machinery removal; final regression | L | full 12-point regression |

Stage count: **21** (S0–S20). First user-visible NEW behavior: **S13** (flag-ON test world;
default-OFF play unchanged until S17). The two XL render stages (S11/S12 = U9/U10) sit LAST in the
pre-closure march deliberately: if anything upstream shifts during the port, the largest and most
26.2-divergent units have the least landed work to invalidate.

**On the inert middle (S4–S12):** these stages necessarily produce no live behavior (the SCC is a
fact, not a choice). Their signal comes from the D4 instruments — probe triage, diff gate, sign
notes — plus the S3 soak: every BASELINE-SANITY run from S3 onward re-exercises the relocated
pre-render pump under real play, so the R2 anchor accumulates weeks of soak before S13 depends on
it. The bring-up ladder (S13→S16) then decomposes first contact so each risk meets reality in
isolation: R5+driver core (S13, same-dim), R1/R7/R9/R10 (S14, cross-dim), entity behavior +
F2/F3 (S15), generation (S16).

---

## 3. Stage specifications

**BASELINE-SANITY script** (referenced by inert stages; ~10 minutes): launch runClient → existing
block-portal test world → build/light a nether portal → cross both ways, one crossing walking
backward (item 1: motion-side exit, no ping-pong) → watch FOV/hand/sprint at crossings (item 2) →
throw an item through, confirm it lands reachable + visible (item 3) → before first crossing, look
through the portal and confirm dest lighting is live (item 8) → in the nether, break/place a block
near the portal, confirm remesh (item 9) → disconnect + rejoin (item 12). Correct = identical to
pre-stage behavior; failure = ANY change (these stages must be inert). From S3 onward this script
inherently also soaks the relocated pump (the R2 anchor) — a crossing regression here implicates S3.

### S0 — Substrate + decisions (U0) — effort M

**(a) Contents** (DEPENDENCY_ORDER §4 U0 row; current-mod-core §3/§10 KEEP rows):
- The D1 holding machinery in buildSrc: `ip_scc_closed` property, TWO held-paths lists (the MAIN
  list shared by the two main compile paths + the SEPARATE test-task list — D1.3), task-level
  `exclude` wiring on `:common:compileJava` (multiloader-common.gradle), the loader `compileJava`
  tasks (multiloader-loader.gradle — the `source(configurations.commonJava)` path), and
  `:common:compileTestJava` (its own list); the probe invocation documented in the buildSrc
  comment. NO change to the `artifacts { commonJava … singleFile }` block (common/build.gradle:35).
- The D2 namespace decision + D3 flag design + D7 R3 reconciliation + the F21 Sodium/Iris
  `compileOnly` stub-classpath decision (see S4(a) — the sodium half is consumed at S4, the
  iris half at S12) recorded in `migration/port-notes/S00-U0-decisions.md`.
- `migration/EXCLUSIVITY_LEDGER.md` skeleton: the block-era driver census (from current-mod-core
  §2/§8 and current-mod-render §3) and the always-on substrate KEEP list, with the S13/S16/S20
  transition columns empty until those stages fill them.
- Loader-neutral seams, all in `com.warwa.seamlessportals.*` (F12):
  - Event-object answer for `Helper.createRunnableEvent`/`createConsumerEvent`
    (q-misc-util.md:321-323) — a common-side event class so IP's own event objects
    (`IPGlobal.*_EVENT`, `Portal.*_SIGNAL`) port as plain infrastructure (R13h).
  - Payload registration abstraction behind the existing `PlatformHelper` (KEEP + widened,
    current-mod-core §3), absorbing Fabric v6 renames (R13h: `clientboundPlay/serverboundPlay`;
    `createS2CPacket` GONE → construct vanilla record packets directly).
  - Entity-type registration callback plumbing mirroring `IPModMain.registerEntityTypes(BiConsumer)`
    (world-loader-root.md §5).
  - Entity-RENDERER registration seam mirroring the Fabric `EntityRendererRegistry` calls in
    IP's `IPModEntryClient` (`:27-28` — `PortalEntityRenderer` for the Portal entity-type
    family + `LoadingIndicatorRenderer` for `LoadingIndicatorEntity`): S13 registers the entity
    types unconditionally and rung 1 renders portals, so the renderer registration must ride a
    named seam, not the unported Fabric entrypoint (Appendix A.9; wired at S13 step 5).
  - Tick/login/frame seam inventory: named hook points for every DEPENDENCY_ORDER §4.2 runtime
    ordering constraint (init sequences, `DimIdSyncPacket` mid-login slot, client tick order,
    frame order, server tick order) — later stages wire into named seams, not ad-hoc call sites.
- Three empty IP-side mixin-config JSONs (core-common / client / peripheral, mirroring IP's
  plugin split, current-mod-core §8) — created but NOT registered with the loaders (D1).
- JUnit test infrastructure for `:common` (test source set + junit dep).

**(b) Forward-ref debt:** none (U0 has none by construction).
**(c) Commits:** 1) buildSrc holding machinery + probe; 2) loader seams + mixin-config skeletons +
test infra; 3) decisions port-note + ledger skeleton.
**(d) runClient test:** nothing user-visible changes; BASELINE-SANITY (the buildSrc change is the
only live surface).
**(e) Regression items:** 1,2,3,8,9,12 (sanity subset).
**(f) Rollback/flag:** no flag yet; rollback = revert commits. Holding machinery inert
(`ip_scc_closed=false`, both held lists carve-in-free until S2).

### S1 — Spike batch (branch-only, learning-only) — effort M

**(a) Contents** (D6 discipline; each spike = one `spike/*` branch + one memo in
`migration/spikes/`; nothing merges to main):
- **SPIKE-R1 — seaLevel + secondary-world construction (GATING design round).** The 26.2
  `ClientLevel` ctor's trailing `int seaLevel` has only `CommonPlayerSpawnInfo.seaLevel()` as a
  vanilla source (API_RISKS R1). Probe: construct a ClientLevel for an unvisited dimension with a
  synthesized seaLevel on the current mod's secondary-level machinery; verify construction +
  extract + render sanity. Deliverable: **the seaLevel protocol design v1** (per-dim seaLevel
  riding the dim-id sync path S7 ports — MiscNetworking/DimIdSyncPacket) + the remote-tick role
  placement (LevelRenderer.tick gone — R1 residue). This satisfies the API_RISKS verdict's
  "design-complete BEFORE the atomic commit" for R1, with empirical evidence instead of paper.
- **SPIKE-R7 — packet re-queue ordering.** Throwaway payload wrapping a vanilla packet,
  re-submitted via `packetProcessor().scheduleIfPossible(...)` + cancel exactly as network.md §A
  specifies; verify (i) ordering against vanilla packets in the same frame, (ii) the netty
  double-invocation shape (isSameThread — briefing §6; memory `respawn-mislabel-phantom-blocks`).
  Deliverable: confirmation (or correction) of §A before S7 implements it for real.
- **SPIKE-R4 — ViewArea subclass+redirect viability.** Probe the surviving surface API_RISKS R4
  lists (public ctor, overridable `repositionCamera`/`getRenderSectionAt`) and the redirect
  retarget (`new ViewArea` inside `LevelRenderer.invalidateCompiledGeometry`,
  `26.2:LevelRenderer.java:796-832`) with a minimal pass-through subclass (D6 — NOT an
  ImmPtlViewArea variant). Deliverable: evidence for the S11 CUTOVER_SPEC R4 decision.
- **SPIKE-R11 — SavedData silent-loss trap.** Register a throwaway `SavedDataType` with candidate
  `DataFixTypes` constants (no NONE exists; null = NPE swallowed by `readSavedData` → fresh empty
  storage silently overwrites the file — API_RISKS R11; portal-generation G1). Deliverables: the
  constant that round-trips a GlobalPortalStorage-shaped codec payload without a destructive
  datafixer pass, AND a reproduction of the silent-loss failure signature so it is recognizable
  at runtime (the S13 script's global-portal relog check watches for exactly it).

**(b) Forward-ref debt:** n/a (nothing merged). **(c) Commits:** memos only, committed to
`migration/spikes/`; spike branches deleted after memo extraction.
**(d) runClient test:** none required on main (main is untouched); BASELINE-SANITY optional.
**(e) Regression items:** none. **(f) Rollback/flag:** n/a.

### S2 — q_misc_util + math harness (U1) — effort XL

**(a) Contents** (DEPENDENCY_ORDER §4 U1 row): `my_util/*` (all math: Mesh2D 1753 LOC, DQuaternion
545, IntBox 540, GeometryUtil 384, AARotation 214, QuadTree 193, Plane 146, IntMatrix3 108…),
`MyTaskList` (307), `Signal*`, loggers, `Animated`+`Rendered*` (479), `GuiHelper` (137, R13e-aware
per slice map), `DimIntIdMap` (160), `MiscGlobals`, `Helper` (1501). LOC per q-misc-util.md —
totalling ~6,500+ LOC with the authored test suite, which is **XL by this plan's own scale**
(the work is mostly pure-math verbatim translation with a JUnit net, but the volume is XL).
- **Carve-in: the Helper-free subset of `qouteall/q_misc_util/my_util/` ONLY — NOT `my_util/**`
  wholesale.** Eight my_util files import the HELD `qouteall.q_misc_util.Helper`
  (Mesh2D.java:24, IntBox, IntMatrix3, MyTaskList, Signal, SignalArged, SignalBiArged,
  LimitedLogger — grep-verified), and Helper cannot compile until S13 (Helper.java:29 imports
  McHelper); carving in `my_util/**` wholesale turns `:common:compileJava` AND
  `:fabric:compileJava` red at S2. DEPENDENCY_ORDER §2.1's "zero imm_ptl imports" is true but
  does not mean Helper-free. Grep-verified carve-in: DQuaternion, Plane, GeometryUtil, QuadTree,
  Range, Circle, LineSegment, Vec2d, the `animation/` subpackage (`Animated`+`Rendered*`), and
  the remaining import-clean utility files — the stage-time import-grep is the mandatory FILTER,
  and same-package references count: AARotation has zero qouteall imports yet its `IntMatrix3`
  field chains it to the held set.
- **Held until S13:** `Helper` (the ONE edge out: `Helper`→`McHelper.newResourceLocation`,
  `IP:q_misc_util/Helper.java:29,509` — zero-deviation forbids removing the import; Helper
  compiles at S13), the eight Helper-importing my_util files above (`Mesh2D`, `IntBox`,
  `IntMatrix3`, `MyTaskList`, `Signal`, `SignalArged`, `SignalBiArged`, `LimitedLogger`) +
  `AARotation` (same-package IntMatrix3 use), `DimIntIdMap` consumers, `MiscNetworking`
  (owned by U5).
- **Deliverable (hard constraint 8):** carry `Mesh2DTest` + `HelperTest` from IP `src/test`
  (COVERAGE INFO-2) into `common/src/test/java/qouteall/...`; **BOTH are HELD until S13** via
  the D1 test-task list. Mesh2DTest cannot activate at S2: `Mesh2D` itself imports `Helper`, so
  compiling the test requires Helper→McHelper→the whole SCC; HelperTest imports `Helper`
  directly. Both live at `qouteall/q_misc_util/my_util/` in the test tree — INSIDE the main
  carve-in path — which is exactly why the test task carries its own divergent pattern list
  (D1.3): `my_util/**` carved in for tests with explicit `**/Mesh2DTest.java` +
  `**/HelperTest.java` exclusions until S13. The ACTIVE S2 payload is the authored
  **DQuaternion/Plane** invariant tests (rotation round-trips, plane-side consistency — NOT
  IntBox: it imports Helper and is held; IntBox invariant tests activate at S13) — the
  geometry-sign regression net (DEPENDENCY_ORDER §4.1 milestone 1).

**(b) Forward-ref debt:** exactly one edge — `Helper`→`McHelper` (U1 row: "the ONLY edge out").
Probe must show only this (the held my_util files resolve against in-probe Helper source).
**(c) Commits:** 1) Helper-free my_util subset + carve-in + authored tests; 2) remaining U1 files
(held) + carried tests (held); 3) port-note `S02-qmiscutil.md` (diff-gate record +
Mesh2D/DQuaternion derivation notes + the carve-in grep evidence).
**(d) runClient test:** nothing user-visible; BASELINE-SANITY. Run `.\gradlew.bat :common:test` —
the authored DQuaternion/Plane tests green is the stage's real payload (the carried IP tests
activate at S13).
**(e) Regression items:** sanity subset. **(f) Rollback/flag:** no flag; revert-only; carved-in
classes inert (no runtime references from mod code).

### S3 — LIVE substrate: R2 frame-anchor relocation + A4 upkeep re-home — effort M

The graft that repairs the winner's biggest judged weakness: the API_RISKS verdict's stage-1
explicitly says "preparatory refactors — re-homing the `GameRendererPortalPrepareMixin` upkeep
(A4), relocating the pre-render pump (R2) … can all ship and soak while block portals still serve
players". This stage ships them LIVE, so the eventual flag-dispatch point is pre-proven and the
frame-phase anchor accumulates real soak from here to S17.

**(a) Contents:**
- **R2 design round (BEFORE cutover per constraint 4) + immediate live implementation.**
  Reconcile the two corpus prescriptions: portal-animation.md #14 anchors inside
  `Minecraft.renderFrame(Z)V` BEFORE the `gameRenderer.update(...)` call
  (`26.2:Minecraft.java:1290`; panorama/screenshot path `Minecraft.java:2779-2781` bypasses it,
  matching IP 1.21.3's non-firing there); current-mod-render §2.2 endorses the existing
  `GameRendererFrameCrossingMixin` at `GameRenderer.update` HEAD. Both precede camera update.
  **Fidelity default: the `renderFrame` call-site anchor (portal-animation #14)** — it reproduces
  IP's panorama non-firing exactly. Decision + panorama-behavior documentation in
  `migration/port-notes/S03-frame-anchor.md`. The relocated host is a MOD-OWNED mixin that today
  drives the block-era pre-render pump (crossing detection + upkeep) and at S13 becomes the flag
  dispatch point (D3 shared-host rule).
- **A4 upkeep re-home, live**: the stranded upkeep hosted by `GameRendererPortalPrepareMixin`
  (staged-upload flush, adoption prune, bridge repaint pump —
  `render/StencilPortalRenderer.java:194-215`) moves to the relocated pre-render block (IP anchor
  analog: `IP:.../MixinGameRenderer.java:86-96`). Verify EMPIRICALLY here that the new frame
  position is GPU-upload-safe outside the framegraph (A4's ordering caveat) — this is the point
  of doing it live. `GameRendererPortalPrepareMixin` itself is deleted at S20 with the FBO path
  (current-mod-render §2.3), not now — only its cargo moves.
**(b) Forward-ref debt:** none (mod-owned code only; no qouteall source involved).
**(c) Commits:** 1) anchor relocation; 2) A4 upkeep re-home; 3) port-note (decision + panorama doc
+ GPU-safety evidence).
**(d) runClient test — LIVE-SUBSTRATE script (block portals):** the full BASELINE-SANITY, plus:
sprint-cross repeatedly watching FOV/hand (item 2 — the pump anchor is exactly where the
hand-glitch chain lived, memory `teleport-hand-glitch-chain`); elytra-boost crossing (item 6);
portal-view lighting before first crossing (item 8 — lateUpdateLight is frame-END, unaffected, but
verify no ordering interaction); long session watching for render-thread stalls (item 11); take a
panorama/screenshot (F1 world icon path) near a portal — no crash, no crossing fired mid-panorama
(the documented behavior). Failure = any crossing seam regression → the anchor choice is wrong;
fall back to `GameRenderer.update` HEAD (the documented alternative) and re-run.
**(e) Regression items:** 1, 2, 6, 8, 11 (+12 via baseline).
**(f) Rollback/flag:** no flag; revert-only. From here every later BASELINE-SANITY run extends
this soak.

### S4 — Ducks, roots, platform facade (U2) — effort M

**(a) Contents** (U2 row): the 36 duck interfaces (33 carved in, 3 held — see below) +
q_misc_util ducks + cross-slice accessor interfaces — incl., NAMED (round-3 sweep; previously
in no stage list): `q_misc_util/mixin/IELevelStorageAccess_Misc` (`MiscHelper.java:25` imports
and `:114` uses it — landing it here keeps the S4 probe ledger-clean); `IPGlobal`, `IPCGlobal`,
`MiscHelper` (118), `mc_utils` (`ServerTaskList`,
`MyNbtTextFormatter` — R12's `SnbtPrinterTagVisitor` re-derivation, **and the zero-import
interface `IPEntityEventListenableEntity` — previously owned by NO stage: `Portal.java:49`
imports and `:84` implements it (S6), `MixinEntity_U.java:8` (S5 traversal mixins) uses it;
landing it here resolves both in-probe**), `IPConfig` (215) + `O_O`
(254), `IPFeatureControl` (peripheral, 1 file — cycle 9), compat invoker BASES
(`GravityChangerInterface`, `SodiumInterface`, `IrisInterface`, `IPPortingLibCompat` —
compile-mandatory, platform-compat-peripheral.md:245), `IPPerServerInfo`, `DimensionIntId` (129) +
`DimensionIdRecord`, `IPMixinPlugin` (role absorbed by the KEEP'd `SeamlessMixinConfigPlugin` per
D3 — port the class, wire the gate through the mod's plugin; record in port-note).
- **Carve-in: the 33 genuinely vanilla-only duck interfaces — NOT all 36.** Three ducks import
  held IP classes (grep-verified this pass): `IEClientWorld.java:8` → `Portal` (U4),
  `IEEntity.java:8-9` → `PortalCollisionHandler` + `Portal` (U4/U6), `IEMinecraftServer.java:3`
  → `IPPerServerInfo` (held U2 file). Portal/PortalCollisionHandler don't land until S6 and stay
  held until S13, and IPPerServerInfo is held at this very stage — carving all 36 in turns the
  SHIPPING `:common:compileJava`/`:fabric:compileJava` gate red (hard constraint 2).
  DEPENDENCY_ORDER §2.9's blanket "duck interfaces compile against vanilla only" is itself wrong
  (Appendix A.6 erratum iii). The import-grep at stage time is a mandatory FILTER on the
  carve-in — exactly as already worded for the invoker bases — not a confirmation. Grow AW/AT
  lists as ducks demand (current-mod-core §10).
- **Held:** `IEClientWorld`, `IEEntity`, `IEMinecraftServer` (until S13 — their consuming mixins
  are held anyway, so nothing else moves), `O_O`, `IPConfig`, `IPPerServerInfo`,
  `DimensionIntId`, any invoker base that imports IP classes (grep each; carve in the genuinely
  vanilla-only).
- **DimLib note:** `DimensionIntId` imports DimLib's `DimensionAPI` events — port against the
  static-dimension EVENT-WIRING stub (F11; DEPENDENCY_ORDER §2.1; R13g; COVERAGE INFO-4).
- **Sodium/Iris compile reality — F21 (decided at S0, consumed here and at S12):**
  `SodiumInterface` lives in `compat/sodium_compatibility` (not `compat/`) and is NOT
  vanilla-only: it imports and USES six `net.caffeinemc.mods.sodium.*` classes in its own
  static-method bodies, and its companion `IESodiumWorldRenderer` (a `@Mixin` on
  `SodiumWorldRenderer`) cannot compile without Sodium at all; the S12 Iris renderer shells
  likewise import `net.irisshaders.iris.*` directly. Neither Sodium nor Iris exists for 26.2,
  and `SodiumInterface` cannot be quietly dropped or held forever — it is imported by
  `ImmPtlClientChunkMap` (S9), `IPModMainClient` (S10), `FrustumCuller`/`MyGameRenderer` (S11)
  and client mixins (S12). Resolution = **forced-deviation register entry F21**: a `compileOnly`
  stub-classpath ARTIFACT (empty shells of exactly the Sodium/Iris types the compat files
  reference). Boundary defense: these are THIRD-PARTY types, not IP source — constraint 2's
  no-stubs rule governs ported IP code and is untouched; this is build engineering in the D1
  sense. `IESodiumWorldRenderer` LANDS HERE (held; never registered on 26.2 — the plugin's
  sodium-present gate stays false), closing its previously unowned scheduling.
  **Two more same-package `sodium_compatibility` qouteall files land HERE held (round-3 sweep —
  F21 cannot cover them, they are IP SOURCE, not third-party types, and NO stage owned them):**
  `SodiumRenderingContext` (`SodiumInterface.java:60` constructs it —
  `new SodiumRenderingContext(renderDistance)`; zero qouteall refs) and
  `IESodiumRenderSectionManager` (`SodiumInterface.java:72-73` casts to it + calls
  `ip_swapContext`; it same-package-references `SodiumRenderingContext`). The S19-gated
  `MixinSodiumRenderSectionManager` imports both at `:14-15`. Without both held here, the S13
  `ip_scc_closed=true` flip is red.
  `IPFlywheelCompat` (compat/) also lands here held — `IPModMainClient.java:9` imports it, so
  it is compile-mandatory; stage-time import-grep decides whether it too needs F21 stub types.
- **`RequiemCompat` (platform_specific) lands HERE held (round-3 sweep — previously owned by NO
  stage, invisible to import-grep):** `O_O.java:47` (`onPlayerTeleportedClient`) and `:54`
  (`onPlayerTeleportedServer`) call it SAME-PACKAGE — no import line, the exact round-2
  blind-spot class. `IPModEntry` (unported Fabric entrypoint — Appendix A.9) also
  same-package-references it. Its own forward debt joins (b): `RequiemCompat.java:14-15` →
  `ClientTeleportationManager`/`ServerTeleportationManager` (U6, resolve S8) and `McHelper`
  (U3, resolves S5); its `net.fabricmc` loader imports take the F12 loader-seam treatment
  (documented substitution hunks per D4.3). Without it the S13 flip is red.
- **Carve-in addition — the `@IPVanillaCopy` annotation type**
  (`qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy`, zero imports): imported by 20 files
  landing across S4–S12 (`MyNbtTextFormatter` S4, `McHelper` S5, `CollisionHelper` S6,
  `PlayerChunkLoading` + `ImmPtlClientChunkMap` S9, `MyGameRenderer` + `MyRenderHelper` S11,
  and a dozen S10/S12 mixins) yet — before this correction — owned by no stage ledger; carving
  it in HERE resolves every one of those refs in-probe, so no per-stage ledger lines are needed.
**(b) Forward-ref debt** (U2 row): `O_O`→{`Portal` U4, `ImmPtlClientChunkMap` U7,
`ImmPtlNetworkConfig` U5, `PortalGenInfo` — **closure co-port, see Appendix A.6 errata**};
`IPConfig`→`BlockPortalShape` (U4 co-port); `IPPerServerInfo`→{`ServerTeleportationManager` U6,
`CustomPortalGenManager` U11 — **the literal U2 row labels this "(U9)", which is wrong; this
plan follows the U11 closure row (S13 co-port) — Appendix A.6 erratum iv**,
`PortalWandInteraction` U11} — **PLUS ~11 refs the U2 row omits entirely (grep-verified, all
documented debt):** `IPCGlobal.java:6-10` → `PortalRenderer`, `RendererDebug`, `RendererDummy`,
`RendererUsingFrameBuffer`, `RendererUsingStencil` (all U10, resolve S12); `O_O.java:25` →
`McHelper` (U3, resolves S5); `DimensionIntId.java:19-20` → `McHelper` (S5) + `MiscNetworking`
(U5, resolves S7); `GravityChangerInterface.java:15-16` → `CHelper` + `McHelper` (S5);
`MyNbtTextFormatter.java:27` → `IPVanillaCopy` (resolves in-probe — carved in this stage);
`SodiumInterface` → `FrustumCuller` (U9, resolves S11) + `IESodiumWorldRenderer` (lands here
held; its Sodium types compile via F21) + `SodiumRenderingContext`/`IESodiumRenderSectionManager`
(same-package — land here held, resolve in-probe) — **PLUS the S4-landed files' own refs the
union previously excluded (round-3 sweep):** the three HELD ducks land at S4 (commit 1) and the
probe shows `IEClientWorld.java:8` → `Portal` (U4) and `IEEntity.java:8-9` →
`PortalCollisionHandler` + `Portal` (U4/U6-co-port targets — both resolve S6;
`IEMinecraftServer`'s `IPPerServerInfo` ref resolves in-probe, its target lands held this
stage); and the co-ported `RequiemCompat` shows
`ClientTeleportationManager`/`ServerTeleportationManager` (RequiemCompat.java:14-15, resolve
S8) + `McHelper` (S5). Probe must show only the union above; when diffing
probe errors against the LITERAL U2 row, apply erratum iv so the triage rule doesn't misfire.
**(c) Commits:** 1) the 33 carved-in ducks + AW/AT + the `IPVanillaCopy` carve-in; the 3
IP-importing ducks land HELD; 2) roots/facade files + RequiemCompat + the sodium_compatibility
trio (all held) + the F21 stub-classpath artifact
(sodium half); 3) port-note.
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset. **(f) Rollback/flag:** no flag; revert-only; ducks inert.

### S5 — MC helpers (U3) — effort M

**(a) Contents** (U3 row): `McHelper` (~965), `CHelper` (~193), `IPMcHelper` (~332), `ScaleUtils`,
mc_util entity-traversal mixins (`IELevelEntityGetterAdapter` et al. — land WITH the unit per
§2.10, unregistered until S13). Translation hazards owned here: **R13d height-bound inclusivity**
(`getMaxYExclusive`/`getMaxSectionYExclusive` wrappers get the +1, call sites untouched — the
silent-corruption surface for S16's FastBlockAccess/NetherPortalMatcher), R12's
`EntitySection`/`EntitySectionStorage` traversal re-derivation from 26.2 source.
**(b) Forward-ref debt** (U3 row): `Portal` (U4), `GlobalPortalStorage` (U5),
`CrossPortalEntityRenderer` (S11 — see Appendix A.6 errata on its unit labeling) — **PLUS two
refs the U3 row drops (grep-verified):** `CHelper.java:26` → `animation.StableClientTimer` (U4
animation package, lands S6 — documented at DEPENDENCY_ORDER §2.2 but missing from the row) and
`McHelper.java:56` → `IPVanillaCopy` (resolves in-probe via the S4 carve-in) — **PLUS two
SAME-PACKAGE references to the held `ClientWorldLoader` (no import line — the exact round-2
blind-spot class):** `CHelper.java:52` (`ClientWorldLoader.getWorld(dimension)`) and
`IPMcHelper.java:183` (`ClientWorldLoader.withSwitchedWorld(...)`) — both appear in the S5
probe and resolve only at S10 (U8) — **and one traversal-mixin ref:** `MixinEntity_U.java:8` →
`mc_utils.IPEntityEventListenableEntity` (landed held at S4 after the round-3 sweep — resolves
in-probe). The probe shows the StableClientTimer ref until S6 and the two ClientWorldLoader
refs until S10; all documented debt, not translation slips.
**(c) Commits:** 1) helpers (held); 2) traversal mixins (held); 3) port-note with the R13d wrapper
derivation.
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset. **(f) Rollback/flag:** no flag; revert-only.

### S6 — Portal core + shapes + animation (U4) — effort XL

**(a) Contents** (U4 row): `Portal` family (`Mirror`, `BreakableMirror`, `EndPortalEntity`),
`PortalState`, `PortalExtension`, `PortalManipulation`, `PortalUtils`, `shape/*` (incl.
`RectangularPortalShape`, `BoxPortalShape`, `SpecialFlatPortalShape`), `util/*`, the whole
`animation/` package (14 files, 2,639 LOC — portal-animation.md; incl. `StableClientTimer`,
`ClientPortalAnimationManagement`), `PortalPlaceholderBlock`, `LoadingIndicatorEntity`,
`PortalRenderInfo`, **co-ports**: `BlockPortalShape` (~513, cycle 14) and
`CollisionHelper`+`PortalCollisionHandler`+`PortalCollisionEntry` (cycle 15 — common-side compile
edge from shapes; the co-port here is right, but §3.15's claim that they "need only U1–U4
material" is WRONG — CollisionHelper reaches U5 and U8 files, see (b) and Appendix A.6
erratum v).
- **R11 decisions recorded here** (owning stage): (i) `EntityType.Builder.build(ResourceKey)`
  id-at-build restructure of `createPortalEntityType` (portal-core C10/hazard 4); (ii)
  `EntitySpawnReason` per call site — LOAD for deserialize, DIMENSION_TRAVEL for the recreate
  path; (iii) tracking-range conversion `trackRangeBlocks(96)` → `clientTrackingRange(6)` CHUNKS,
  verified against Fabric API's conversion before hardcoding (F14; current-mod-core §11.6); (iv)
  the `makeBoundingBox(Vec3)` inversion + eager `setBoundingBox` push on EVERY geometry-field
  change (portal-core G1/C2/hazard 1 — a missed push is a silent stale-bb bug); (v) NBT via
  `TagValueOutput/TagValueInput` preserving the CompoundTag wire shape and the rotation
  double-write/float-read quirk (portal-core C1/C4).
- **Sign-derivation notes** (D4.4): every transform in
  `transformPoint/transformLocalVec/inverseTransform*`, shape windings,
  `UnilateralPortalState` axes — re-derived, not trusted.
**(b) Forward-ref debt** (U4 row): `ImmPtlNetworking`+`PortalAPI`+`ImmPtlEntityExtension` (U5 —
`Portal.java:46` imports `api.ImmPtlEntityExtension`, part of the S7 `api/` co-port; the round-2
ledger named only the first two), `ServerTeleportationManager` (U6), `PortalRenderer` (U10),
`QueryManager`/`RenderStates`/
`WorldRenderInfo`/`ViewAreaRenderer`/`FrustumCuller` (U9/U10), `PortalCommand.raytracePortals` (U11)
— **PLUS the co-ported CollisionHelper's refs that the cycle-15 note omits** (grep-verified,
CollisionHelper.java:22-33): `ClientWorldLoader` (U8, lands S10 — attribution note:
`Portal.java:43` and `ClientPortalAnimationManagement.java:6` ALSO import it, the target is
shared, not CollisionHelper-only), `GlobalPortalStorage` (U5
co-port, lands S7 — `PortalUtils.java:15` also imports it), and the MIXIN class
`qouteall.imm_ptl.core.mixin.common.collision.IEEntity_Collision` (mixin-common, U8, lands S10)
— **PLUS four refs this ledger previously missed (round-3 sweep, source-verified):**
`Portal.java:51` → `mixin.common.entity_sync.MixinServerEntity` (S10 — a SECOND
core→common-mixin import beyond IEEntity_Collision; used only in a `:517` javadoc `@link`, but
the import must still resolve); `EndPortalEntity.java:32` →
`mixin.common.miscellaneous.IEEndDragonFight` (S10; cast at `:95`); `PortalRenderInfo.java:13`
→ `render.GlQueryObject` (S11 — a DISTINCT class from the ledgered `QueryManager`;
DEPENDENCY_ORDER §2.3 lists it); `PortalPlaceholderBlock.java:28` →
`nether_portal.BreakablePortalEntity` (used `:105`; in the S13 U12 closure slice — the S6–S12
probes all show it). (`Portal.java:49` → `mc_utils.IPEntityEventListenableEntity` resolves
in-probe — landed held at S4 after the round-3 sweep; `MiscHelper`, also imported, landed held
at S4 and resolves in-probe.)
These are documented debt resolving at S7/S10/S11/S13, NOT translation slips — without this
ledger the triage rule would misclassify them as fix-now items, which is impossible without
pulling U8+ files forward.
**(c) Commits:** 1) portal root + state + extension/manipulation; 2) shapes + co-ported collision
trio + BlockPortalShape; 3) animation package; 4) placeholder block + loading indicator +
PortalRenderInfo; 5) port-note (R11 decision record + sign notes).
**(d) runClient test:** nothing user-visible; BASELINE-SANITY; `:common:test` green.
**(e) Regression items:** sanity subset. **(f) Rollback/flag:** no flag; revert-only.

### S7 — Network, global portals, API (U5) — effort L

**(a) Contents** (U5 row): `MiscNetworking` (149), RPC (`ImplRemoteProcedureCall` 476 +
`McRemoteProcedureCall` 159), `PacketRedirection` (290) + `PacketRedirectionClient` (116),
`ImmPtlNetworking` (269), `ImmPtlNetworkConfig` (318), **co-ports**: `global_portals/*`
(GlobalPortalStorage 396 + types ~700, cycle 10) and `api/` (`PortalAPI`, `ImmPtlEntityExtension`,
cycle 11).
- **R7 implemented in the network.md §A shape, as confirmed/corrected by SPIKE-R7** (F7): netty
  pass schedules the outer `ClientboundCustomPayloadPacket` via
  `packetProcessor().scheduleIfPossible(...)` + cancel; game-thread re-invocation handles inline;
  `wrapRunnable`/`scheduleExecutables` narrowed. The redirection wire format ports 1:1
  (`GameProtocols.CLIENTBOUND_TEMPLATE.bind` intact). Every packet-handler mixin written this
  stage carries the isSameThread guard (briefing §6).
- **R1 seaLevel protocol IMPLEMENTED** (F8) per the SPIKE-R1 design v1: the dim-sync channel
  (`DimIdSyncPacket` path) carries per-dimension seaLevel for not-yet-visited dimensions; login
  ordering preserved exactly (DimIdSync mid-`placeNewPlayer` BEFORE difficulty; global-portal
  sync after — DEPENDENCY_ORDER §4.2). Client consumption lands with ClientWorldLoader at S10.
- **R11 DataFixTypes BOUND** (F15): the SPIKE-R11 constant goes into `GlobalPortalStorage`'s
  `SavedDataType`; the `save()` override ports; a loud load-failure guard is added (never swallow
  — the silent-loss signature from the spike memo goes into the S13 script).
- **The `q_misc_util/mixin` tree lands HERE held (4 of its 5 files — round-3 sweep: the package
  was previously owned by NO stage; `IELevelStorageAccess_Misc` landed at S4 with MiscHelper):**
  `mixin/dimension/MixinPlayerList_Misc` — the `DimIdSyncPacket` mid-`placeNewPlayer` login
  mixin (imports `MiscNetworking`, landing this stage) that the R1 protocol above and S10's
  §4.2 login-order wiring DEPEND on; `mixin/MixinMinecraftServer_Misc` (deps: MiscGlobals S2,
  DimensionIntId + the q_misc_util duck, S4); `mixin/client/IEClientPacketListener_Misc`
  (vanilla-only accessor); and `mixin/client/MixinGui_Overlay` — which imports
  **`q_misc_util/CustomTextOverlay` at `:13` (zero qouteall imports, also previously unowned —
  lands HERE with it; the S13 wand chain references it too)**. All unregistered until S13 (D1).
**(b) Forward-ref debt** (U5 row): `ServerTeleportationManager` (U6),
`ImmPtlChunkTracking`/`ChunkLoader` (U7) — **PLUS the refs the U5 row omits (grep-verified,
round-3 sweep — the same false-alarm class the S6/S8/S9/S10 ledgers were corrected for in
round 2, missed at S7):** `ClientWorldLoader` (U8, resolves S10) is imported by FOUR S7-landed
files — `MiscNetworking.java:28` (used `:121`), `ImmPtlNetworking.java:29`,
`PacketRedirectionClient.java:15`, `GlobalPortalStorage.java:33` (DEPENDENCY_ORDER even
documents two of these: §2.1 MiscNetworking→ClientWorldLoader; §2.5
ImmPtlNetworking.java:29-31) — **and three held MIXIN classes:** `PacketRedirection.java:34` →
`mixin.common.entity_sync.MixinServerGamePacketListenerImpl_Redirect` (S10);
`ImmPtlNetworkConfig.java:29` → `mixin.common.other_sync.IEServerConfigurationPacketListenerImpl`
(S10; used `:163`); `PacketRedirectionClient.java:16` →
`mixin.client.sync.MixinMinecraft_RedirectedPacket` (a CLIENT mixin — resolves S12, not S10).
All documented debt, not translation slips.
**(c) Commits:** 1) q_misc_util networking + RPC + the q_misc_util mixin tree +
CustomTextOverlay; 2) PacketRedirection pair +
ImmPtlNetworking/NetworkConfig; 3) global_portals + api co-ports; 4) protocol notes + port-note.
**(d) runClient test:** nothing user-visible; BASELINE-SANITY (wire format inert until senders run).
**(e) Regression items:** sanity subset. **(f) Rollback/flag:** no flag; revert-only.

### S8 — Teleportation + collision (U6) — effort L

**(a) Contents** (U6 row): `TeleportationUtil`, `CrossPortalSound`, `ClientTeleportationManager`,
`ServerTeleportationManager` (collision trio already co-ported at S6 per cycle 15 — REVIEWED
alongside this unit per the U6 row note).
- **R2 is already live (S3)** — this stage wires the ported `manageTeleportation(false)` call
  shape against the S3 anchor host (in held code; the dispatch goes live at S13).
- **R8 design round — owning stage** (F9): the position-packet dimension stamp. Pick the
  codec-wrap idiom (`@ModifyExpressionValue` on the `StreamCodec.composite` call in `<clinit>`
  appending `writeResourceKey`/`readResourceKey`) vs a paired ImmPtl packet keyed by teleport id
  (API_RISKS R8). **Lock-step rule, pinned:** BOTH halves (server write mixin + client read
  mixin) implement the chosen design in ONE commit at S10 — the client mixin lands early with its
  protocol pair rather than with the S12 client-mixin mass; recorded as a deliberate exception to
  mixins-with-their-unit, justified by protocol integrity.
- **R12 re-derivation** (owning stage; F17): every `@IPVanillaCopy` in the collision/teleport path
  re-derived line-by-line from 26.2 source, never patched from 1.21.3 copies:
  `Entity.collide`/`collideBoundingBox`/`collideWithShapes` (dynamic `axisStepOrder`), the
  movement-path-based `checkInsideBlocks` portal clip (design needed — the bb-redirect has no
  anchor; the clip must apply to the per-segment box/path), the anticheat
  `isEntityCollidingWithAnythingNew` retarget (now also guarding vehicles — behavior-review
  flag), interpolation kills via `InterpolationHandler` + `snapTo` +
  `getPositionCodec().setBase` (the movement-dedup interaction — memory
  `post-crossing-stutter-entity-move-flood`), the `ServerPlayer.removeVehicle()` retarget for the
  riding bypass (IP's `stopRiding` bypass as written is INEFFECTIVE on 26.2).
- **R13a** (owning stage): ender-pearl mixin intercepts/replaces vanilla's now-native cross-dim
  branch rather than adding a case; `teleport(TeleportTransition)` rename. **R13b**: audit each
  IP cross-dim patch for obsolescence-by-vanilla (`EntityReference`, `getEntityInAnyDimension`)
  before porting — drop-candidates documented in the port-note, never silently dropped.
- Exit-posture sign derivations in the port-note (the motion-keyed-exit lesson — memory
  `backward-crossing-motion-keyed-exit`; IP recomputes posture server-side from the eye segment).
- **PORT-FORWARD (verify) carriage from `SeamlessClientTeleport`** (current-mod-core §5,
  constraint 5): the disposition row carries two (verify) SUB-items that must be re-proven on
  the ported `ClientTeleportationManager` flow and recorded in this stage's port-note
  verification list — (i) the lagged-rotation-field shift (`yRotO`/`xRotO`/`yBob`… — the
  hand-glitch chain, memory `teleport-hand-glitch-chain`) and (ii) the sprint-modifier keeper —
  both "vanilla-26.2 interactions that may persist — re-test on the ported flow before
  deleting". S20's survivor audit names them too; `SeamlessClientTeleport` may not be deleted
  until both re-tests are on record. (Runtime symptom coverage already exists — S13 rung-1
  step 2 and S17 item 2 watch hand/sprint at crossings — but the named survivor re-test is
  the constraint-5 requirement.)
**(b) Forward-ref debt** (U6 row): `ImmPtlChunkTracking` (U7), `TransformationManager` +
`MyGameRenderer.vanillaTerrainSetupOverride` + `RenderStates`/`FogRendererContext` (U9/U10,
client-only paths) — **PLUS three `ClientTeleportationManager` refs the U6 row omits
(grep-verified):** `ClientTeleportationManager.java:22` → `qouteall.imm_ptl.core.ClientWorldLoader`
(U8, lands S10 — the exact false-alarm class the S6/S9/S10 ledgers were corrected for),
`:45` → `render.FrontClipping` (U9, lands S11), and `:50` →
`render.context_management.WorldRenderInfo` (U9, lands S11 — a DISTINCT class the
RenderStates/FogRendererContext line does not cover; the S8–S10 probes show it). All appear in
the S8 probe as documented debt. (`CrossPortalSound.java:17` → `RenderStates` is covered by the
existing RenderStates target line.)
**(c) Commits:** 1) TeleportationUtil + CrossPortalSound; 2) the two managers; 3) R8 design doc +
port-note (re-derivation evidence + exit-posture signs + the SeamlessClientTeleport (verify)
sub-item re-test record).
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset. **(f) Rollback/flag:** no flag; revert-only.

### S9 — Chunk loading + entity sync (U7) — effort L

**(a) Contents** (U7 row): `ImmPtlChunkTracking` (~669), `ChunkVisibility` (~232),
`ImmPtlChunkTickets` (~337), `PlayerChunkLoading` (~243), `EntitySync` (~79), `WorldInfoSender`
(~100), `DimensionalChunkPos`, `PerformanceLevel`, `ServerPerformanceMonitor` +
`ClientPerformanceMonitor`, `ImmPtlClientChunkMap` — **based on the repo's already-ported
`SeamlessClientChunkMap`** (PORT-FORWARD survivor, current-mod-core §5: "It already IS the IP
class… extended with the mod's 26.2 delta-tracking surface"), installed via the `ClientLevel`
CONSTRUCTOR hook covering EVERY client world including the main one (the faithful install point
is `MixinClientLevel.onConstructed`, NOT a secondary-world factory). R13f carriage: the SOG delta
feed + store-center machinery ride into the ported class (memories `distant-chunk-vanish-sog-desync`,
`walking-limbo-seed-overclaim`).
- **R10 decisions — owning stage** (F13): (i) `TicketType` registration through the KEEP'd
  `TicketTypeInvoker` at registry phase, not first-use static init; (ii) the flag-bits decision
  FLAG_LOADING vs FLAG_SIMULATION balancing the piglin-flood lesson vs IP's simulation semantics
  (chunk-loading cross-cut 3; memory `portal-view-completeness-findings`) — fidelity default:
  IP's semantics, with the mob-spawn implication written down and tested at S14/S17; (iii) the
  `PlayerTicketTracker` takeover's new uncovered path (`DistanceManager.addPlayer` direct
  `PLAYER_SIMULATION` ticket) covered explicitly (mixin-common §2 warning).
- **R13j FORCED DEVIATION recorded** (F1): `WorldInfoSender` shrinks to weather-only — 26.2's
  clock-map `ClientboundSetTimePacket` has no per-dim daylight boolean. The cross-dimension
  weather guard stays (rain-flip broadcast still un-dimensioned).
- chunk_sync mixin classes land here (§2.10), unregistered until S13.
**(b) Forward-ref debt** (U7 row): the U7 row's "runtime-only edge to U8" characterization is
FALSE at javac granularity — `ImmPtlClientChunkMap.java:23` is a COMPILE import of
`qouteall.imm_ptl.core.ClientWorldLoader` (U8, resolves S10 — ledger line added here), and the
same file adds `:25` → `SodiumInterface` (landed held at S4; its Sodium types compile via F21)
and `:27` → `IPVanillaCopy` (resolves in-probe via the S4 carve-in). PLUS one more COMPILE edge
the U7 row misses (grep-verified): `ChunkLoader.java:10` imports
`qouteall.imm_ptl.core.portal.nether_portal.FastBlockAccess` (U12). This is one of the proofs
that the SCC extends into U12; `FastBlockAccess` is therefore in the S13 U12 closure slice, and
the S9–S12 probes show this ref as documented debt. **PLUS one ref this ledger previously
missed (round-3 sweep):** `ClientPerformanceMonitor.java:9` imports
`commands.PortalDebugCommands` (used `:48`) — U11 `commands/`, resolves S13; the S9–S12 probes
show it as documented debt, not the translation slip the triage rule would otherwise call it.
**(c) Commits:** 1) tracking + visibility + tickets + loading; 2) EntitySync + WorldInfoSender +
monitors; 3) ImmPtlClientChunkMap fusion with SeamlessClientChunkMap (the PORT-FORWARD carriage
commit — survivor explicitly re-homed per constraint 5); 4) port-note (R10 record + F1).
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset. **(f) Rollback/flag:** no flag; revert-only.

### S10 — ClientWorldLoader + common mixins (U8) — effort XL

**(a) Contents** (U8 row): `ClientWorldLoader` (~634 — last core-root file, scheduled here because
of cycle 7's render-context refs), `IPModMain`/`IPModMainClient` init sequences (exactly
DEPENDENCY_ORDER §4.2 order — a sequence that STARTS from `MiscUtilModEntry`/
`MiscUtilModEntryClient`, which are NOT ported as classes: seam-absorbed per the Appendix A.9
disposition; their init CARGO wires here), ALL 74 common mixins (chunk/entity/position sync, collision,
interaction taps — mixin-common.md §2.1–2.13), unregistered until S13.
- **R1 implementation:** the S7 seaLevel protocol consumed in `createSecondaryClientWorld`; the
  26.2 render-split plumbing re-derived INTO the ported `ClientWorldLoader` from the mod's proven
  `PortalContextSwitch` mechanics (API_RISKS R1 "the single biggest asset"): per-dim
  renderer+extractor+state choreography, `@Mutable` accessors, extractor-identity rules (memory
  `nether-block-freeze-orphaned-extractor`), TWO reload-listener registrations (extractor +
  cloudRenderer — R1's silent-miss item), the remote-tick placement per the SPIKE-R1 memo (F10).
- **R8 implementation:** the position-stamp mixin PAIR (server write + client read) in ONE commit
  per the S8 lock-step rule.
- **R12 vanilla-copy re-derivations** for this unit's mixins (F17): `setPosRaw` (new waypoint
  updates), `ServerGamePacketListenerImpl.teleport` re-copy, `scheduleExecutables`,
  `ChunkMap.onChunkReadyToSend` (must preserve the NEW `ServerChunkCache.onChunkReadyToSend`
  broadcast-queue call), `doProcessUseItemOn` (`Success.swingSource()`).
- Login-order constraint wired into the S0-named seams: `DimIdSyncPacket` mid-`placeNewPlayer`
  BEFORE the difficulty packet; global-portal sync at `onPlayerLoggedIn` AFTER it.
**(b) Forward-ref debt** (U8 row): `DimensionRenderHelper`/`PortalRendering` (U9) **+
`ClientWorldLoader.java:37-38` → `IEClientLevelData` + `IEClientLevel_Accessor`
(`mixin.client.accessor` — U10, resolve S12; beyond the row's render-context pair — round-3
sweep)** — **PLUS
`IPModMain`'s init-registration refs, which the U8 row omits** (grep-verified this pass):
**U11 files** — `BlockManipulationServer` (IPModMain.java:11), `PortalCommand` +
`AxisArgumentType`/`SubCommandArgumentType`/`TimingFunctionArgumentType` (:18-21), `DebugUtil`
(:23), `GcMonitor` (:25), `CustomPortalGenManager` (:40) — **AND U12 files** —
`nether_portal.GeneralBreakablePortal` (:45), `NetherPortalEntity` (:46) — **AND
`IPModMainClient`'s refs, previously omitted from the ledger entirely**
(IPModMainClient.java:7-32, grep-verified): `ClientDebugCommand` (U11, resolves S13),
`DubiousThings` (`miscellaneous/`, resolves S13), `GcMonitor` (`miscellaneous/`,
IPModMainClient.java:12 — resolves S13; previously documented only via IPModMain.java:25),
the U9 family `CrossPortalEntityRenderer`/
`ForceMainThreadRebuild`/`GuiPortalRendering`/`ImmPtlViewArea`/`MyRenderHelper`/
`ShaderCodeTransformation`/`VisibleSectionDiscovery`/`CloudContext`/`GLResourceCache`
(resolve S11), `RendererUsingFrameBuffer` + `RendererUsingStencil` (U10, resolve S12), and two
classes that until this correction were scheduled in NO stage — now assigned owning stages so
the S13 flip can go green: `compat.IPFlywheelCompat` (:9 — lands S4 with the compat bases) and
`render.optimization.SharedBlockMeshBuffers` (:29 — absent from the corpus's U9 contents; lands
S11 with the render unit) — **AND the common-mixin mass's own U11+ refs (round-3 sweep):**
`MixinAbstractContainerMenu.java:12`, `MixinContainer.java:15`,
`MixinServerPlayerGameMode.java:25` → `block_manipulation.BlockManipulationServer` (U11,
resolve S13); `MixinItemEntity_P.java:12` + `MixinItemStack.java:12` →
`CustomPortalGenManager` (S13). **Ownership note (double-claim resolved):** the FOUR
`mixin/common/portal_generation` mixins (`MixinItemEntity_P`, `MixinItemStack`,
`MixinMinecraftServer_P`, `MixinPlayerList_P`) were claimed both by this stage ("ALL 74 common
mixins") and by S16 ("ignition/trigger mixins") — they LAND HERE as part of the 74; S16 only
registers/wires them (S16(a) reworded to match). The probe WILL show all of these beyond the
render-context refs;
they are documented debt resolving at S11/S12/S13, NOT translation slips — without this ledger
line the triage rule generates unfixable false alarms.
**(c) Commits:** 1) ClientWorldLoader + init sequences; 2) chunk/entity-sync mixins; 3) the R8
position-sync PAIR; 4) collision/interaction mixins; 5) port-note (R1 impl notes + re-derivation
evidence).
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset. **(f) Rollback/flag:** no flag (mixins unregistered);
revert-only.

### S11 — Render context + CUTOVER SPEC + R3 compile design (U9) — effort XL

**(a) Contents** (U9 row): `context_management/*` (`RenderStates`, `WorldRenderInfo`,
`PortalRendering`, `FogRendererContext`, `StaticFieldsSwappingManager`, `DimensionRenderHelper`,
`CloudContext`), `MyGameRenderer` (335), `MyRenderHelper` (621), `FrontClipping`,
`ShaderCodeTransformation`, `TransformationManager` (322), `GlQueryObject`/`QueryManager`,
`ImmPtlViewArea` (490), `VisibleSectionDiscovery` (199), `FrustumCuller` (402),
`ForceMainThreadRebuild`, `GLResourceCache`, `CrossPortalEntityRenderer` (428),
`GuiPortalRendering` (125), `ViewAreaRenderer` (224), plus
`render.optimization.SharedBlockMeshBuffers` — absent from the corpus's U9 contents but
compile-mandatory (`IPModMainClient.java:29` imports it); assigned HERE as its owning stage.
**Round-3 owning-stage assignments (previously owned by NO stage; all compile- or
runtime-mandatory by S13):** `render/CrossPortalViewRendering` (`MixinGameRenderer.java:33`
imports and `:155` calls it at S12; the S18 "IP view refinements" line is its RUNTIME
verification only — before this fix the plan never landed the class anywhere);
`render/PortalEntityRenderer` — the Portal ENTITY's renderer, registered in IP by
`IPModEntryClient:27-28` (unported entrypoint, Appendix A.9), runtime-mandatory for S13 rung 1
to render any portal, wired through the S0 renderer-registration seam at S13 step 5;
`render/OverlayRendering` (same-package ref from PortalEntityRenderer `~:37`);
`render/LoadingIndicatorRenderer` (imports only `LoadingIndicatorEntity`, S6); and
`mc_utils/WireRenderingHelper` (`PortalEntityRenderer.java:14` imports it; the S13 wand chain
references it too — its own imports are all ≤S11: CHelper, Portal/animation/shape,
RenderStates, my_util).
The 26.2 rewrite hotspot: IP classes
re-express onto the mod's proven mechanisms (API_RISKS R6 "every piece has a cited pattern"):
`PortalRenderTypes` drawMesh/pipelines, `ShaderManagerCompilationCacheMixin` GLSL injection,
`GlCommandEncoderClipMixin` per-draw clip uniform, `PortalRenderBuffersPool.endFramePooled()`,
4-factor `BlendFunction` for IP's premultiplied blend.
- **R3 COMPILE-LEVEL DESIGN ROUND (D7 — pre-closure, schedule-forced):** the class body's
  mid-batch splits (`endBatch()` at `IP:.../CrossPortalEntityRenderer.java:123,139,208`;
  `consumers.endBatch()` at `:308`) and the `renderEntity` duck target GONE APIs, so the class
  cannot compile verbatim at S13. Choose the delivery mechanism NOW from API_RISKS R3's
  candidates — (a) per-draw clip uniform at `GlCommandEncoder.trySetup` RETURN keyed off
  submit-order metadata (the mod's clip hook already lives there) or (b) a one-entity
  `SubmitNodeStorage` + `FeatureRenderDispatcher.renderAllFeatures(storage)` bracketed by raw-GL
  clip state — and port the class 1:1 onto that compile surface. Port the surviving 1:1 pieces:
  the `EntityRenderDispatcher.shouldRender` visibility-gate mixin (signature-identical,
  render-core S35) and the public `extractEntity`+`submit` path that kills the duck. Design doc:
  `migration/port-notes/S11-R3-clip-bracketing.md`. Runtime wiring/verification trail at S18
  (checkpoint C4 confirms the mechanism after live testing).
- **PORT-FORWARD carriage (constraint 5), each its own commit line:** `FrontClipping` (with the
  §1.6 anti-"fix" guard), `DimensionRenderHelper` (already the 26.2 Lightmap shape,
  current-mod-render §1.8), `VisibleSectionDiscovery` (+ the A3 comparative read of
  `ForceMainThreadRebuild` — deliverable), `ShaderCodeTransformation` (keep the mod's 26.2
  wiring), `PortalInnerCull`→`FrustumCuller` (re-source corners via
  `getRectPortalFourVerticesCounterClockwise(getThisSideState())` + `transformPoint`),
  `PortalRenderBuffersPool`→`MyGameRenderer` acquire/return, `lateUpdateLight` into
  `MyRenderHelper` (already ported, 3a2c14e).
- **CUTOVER_SPEC.md authored at stage entry** — the atomic-driver-core spec the API_RISKS verdict
  demands (constraint 4: R4 + R5 INSIDE the cutover spec):
  - **R4 decision, on SPIKE-R4 evidence — fidelity default: rebuild `ImmPtlViewArea` on 26.2**
    (subclass `ViewArea`, redirect retargeted to `invalidateCompiledGeometry`'s `new ViewArea`,
    backed by a mod-owned unbounded store), NOT the pinned-bounded deviation; this retires the
    documented latent multi-portal >71-chunk collision bug. If the spike disproved viability, the
    pinned-bounded deviation enters the register instead (conditional F19). The
    `earlyRemoteUpload` per-dispatcher pump necessity is marked needs-runtime-verification
    (render-core G26) with its S13/S14 check named.
  - **R5 sign-flip checklist**: every depth constant/comparison in IP's stencil choreography
    enumerated with its reversed-Z flip derived from 26.2 source (clear 0.0 = far,
    `GREATER_THAN_OR_EQUAL` default; `clearDepthOfThePortalViewArea`/
    `restoreDepthOfPortalViewArea`) + the Vulkan-gap degrade path for `PortalRenderInfo`'s
    occlusion-query consumer (F18). Executed at S12; signed off at S17.
  - **R9 fog/environment design**: per-dim `FogRenderer`/`FogData` buffer ownership (the single
    WORLD ring-buffer slot must not be written mid-frame for the dest),
    `EnvironmentAttributeProbe` + `FogEnvironment` isolation; lightmap already solved by
    `DimensionRenderHelper`.
  - **R2/A4 anchor** restated from S3's LIVE decision (now soak-proven); extract-vs-render phase
    assignment for every IP handler that mutated mid-render state (mixin-client cross-cut 3 —
    "the single largest semantic change in the slice").
  - The extract()/compileSections pairing rule and the SOG delta feed as spec-level invariants
    (memories `ow-holes-consumed-compile-queue`, `distant-chunk-vanish-sog-desync`).
  - The flag-ON driver-swap wiring list (which init sites flip at S13) and the R13i
    fabulous-transparency re-anchor (`GameRenderState.useShaderTransparency()` — easy to
    silently drop).
**(b) Forward-ref debt** (U9 row): `PortalRenderer` family (U10) for a few call sites — **the
row alone is nowhere near the probe's real output; full ledger (grep-verified, round-3 sweep —
this ledger had NOT been rewritten to the round-2 standard):** `MyGameRenderer.java:31` →
`block_manipulation.BlockManipulationClient` (U11, resolves S13) and `:39-40` →
`ducks.IERenderSystem` + `ducks.IESectionRenderDispatcher` (client mixin ducks, resolve S12);
`MyRenderHelper.java:31` → `mixin.client.accessor.CoreShadersAccessor` (S12 accessor; used
`:65-77`); `RenderStates.java:23` → `BlockManipulationClient` (S13) and `:27` →
`mixin.client.particle.IEParticle` (S12); `ImmPtlViewArea.java:26` → `miscellaneous.GcMonitor`
(S13; used `:278,:294`); `VisibleSectionDiscovery.java:20` → `nether_portal.BlockTraverse`
(S13 U12 closure slice; used `:132`); plus from the round-3 owning-stage assignments:
`CrossPortalViewRendering.java:15` → `commands.PortalCommand` (U11, resolves S13) and
`OverlayRendering.java:25` → `nether_portal.BreakablePortalEntity` (S13 U12 closure slice).
All documented debt resolving at S12/S13, not translation slips.
**(c) Commits:** 1) CUTOVER_SPEC.md + R3 design doc; 2) context_management; 3) MyGameRenderer +
MyRenderHelper + TransformationManager; 4) view-area/discovery/culler + ImmPtlViewArea; 5)
CrossPortalEntityRenderer (on the R3 compile surface) + GuiPortalRendering + ViewAreaRenderer;
6) port-note (sign notes, A3 read result, carriage map).
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset. **(f) Rollback/flag:** no flag; revert-only.

### S12 — Renderers + client mixins (U10) — effort XL

**(a) Contents** (U10 row): `renderer/*` — abstract `PortalRenderer`, `RendererUsingStencil`,
`RendererUsingFrameBuffer` + `SecondaryFrameBuffer` + `RendererDummy` + `RendererDebug` +
renderMode config (D5/A1), the Iris-compat renderer SHELLS (cycle 12 — compile-mandatory, but
NOT self-compiling: they import `net.irisshaders.iris.*` directly and no Iris exists for 26.2;
they compile ONLY against the F21 `compileOnly` stub classpath, whose iris half is consumed
here), and all ~62 client mixins (multiworld + render halves, mixin-client.md),
unregistered until S13.
- **R5 executed** per the CUTOVER_SPEC checklist: transplant the runtime-proven stencil substrate
  consumption (`GlBackendMixin`/`GlConstMixin`/`RenderTargetMixin`/`GlStateManagerMixin` +
  `StencilState` — KEEP rows) into `RendererUsingStencil`; every flipped constant carries its
  derivation note (F4/F5).
- **R13c:** every synthetic-lambda anchor re-derived from the COMPILED 26.2 jar, not the
  decompile (pass lambdas pre-mapped: addMainPass :391, clear :197, weather :474, sky :329).
- **R13i:** `useShaderTransparency` override re-anchored per the spec checklist line.
- **R13k:** camera pull-model gates — `initialized` flag via the existing `CameraInvokerMixin`;
  view-rotation post-processing of `cameraState.viewRotationMatrix` after extract (never wrap the
  dirty-flag-cached `getViewRotationMatrix`).
- **A2** bob-scaling trio replaces `MainProjectionBobMixin` (current-mod-render §2.1). The S3
  anchor host gains its (still-inert) IP-dispatch branch.
- **`compat/IPModInfoChecking` lands HERE held (round-3 sweep — previously owned by NO stage):**
  `PortalRenderer.java:21` imports and `:323` calls it, so it is compile-mandatory with this
  unit. It imports `net.fabricmc.loader`/`net.fabricmc` api types at `:4-8` in COMMON code — a
  multiloader problem handled by the F12 loader-seam substitution (documented hunks per D4.3);
  its qouteall imports are all ≤S5, so it resolves in-probe this stage and is NOT part of the
  S13 closure set.
**(b) Forward-ref debt** (U10 row): `BlockManipulationClient` + `PortalCommand`/argument types —
**HARD GATE: the probe at end of S12 may reference ONLY the S13 closure set** — the U11 closure
files (incl. `api/example/ExampleGuiPortalRendering` and `IPConfigGUI` under the F21 autoconfig
stub), the WAND compile shell (all 9 `peripheral/wand` files) + `CommandStickItem`, the
dim_stack compile shell (ALL 11 `peripheral/dim_stack` files, extending into
`alternate_dimension` + its three `peripheral/mixin/common/alternate_dimension` accessor
interfaces), AND the U12
closure slice (`NetherPortalGeneration`, `NetherPortalMatcher`, `FastBlockAccess`,
`FrameSearching`, `BlockTraverse`, `FastBlockPortalShape`, `SimpleBlockPredicate`,
`PortalGenTrigger`, `BreakablePortalEntity`, `NetherPortalEntity`, `GeneralBreakablePortal`,
`CustomPortalGeneration` + the ENTIRE 12-file `form` package — the SCC provably extends into
U12; evidence at S13(a)). This is the last pre-closure checkpoint; any other error is triaged
per the D4.2 NOTE — an import-graph-justified miss amends the gate set (committed with the
stage); anything else is a translation slip fixed here.
**S12-B GATE-SET AMENDMENT (ratified — import-graph-justified; committed with S12-B).** The S12-B
pre-closure probe surfaced one import-graph-justified miss OUTSIDE the enumerated set above:
`qouteall.imm_ptl.core.render.ShaderCodeTransformation`. Import chain — `IPModMainClient` (the U11
client-init closure hub, committed S10.1) imports it (`IPModMainClient.java:25`) and calls
`ShaderCodeTransformation.init()` (`:77`) exactly as IP does, so the client-init hub's own import
graph pulls it into the S13 closure. Per the D4.2 NOTE this AMENDS the S13 closure set to include
`ShaderCodeTransformation`. **S13-GREEN BLOCKER (loud):** IP's `ShaderCodeTransformation` imports the
GONE 26.2 type `com.mojang.blaze3d.shaders.CompiledShader` (api-map/mixin-client §8 `MixinCompiledShader`
row — the whole `CompiledShader`/`CompiledShaderProgram` GLSL-compile stack is TARGET-GONE), so it is
NOT verbatim-portable. **S13 MUST** land it as a SHELL with the shader-transform internals
commented / FrontClipping-deferred (mirroring the dropped `MixinCompiledShader` / `MixinShaderInstance`
/ `MixinRenderSystem_Clipping` render-shader ducks), **OR** gate/comment the `IPModMainClient.init()`
call — else `IPModMainClient` stays RED at S13 and there is no first green build. The FrontClipping
shader redesign owns the real transform. Recorded in port-note S12B. (Sibling S4-carried GONE-type
duck `IEShader` — same GONE `com.mojang.blaze3d.shaders.*` origin — was resolved THIS stage: dead
`Uniform` import removed, file held-inert until the FrontClipping redesign; port-note S12B.)
**(c) Commits:** 1) abstract PortalRenderer + stencil renderer; 2) FBO/dummy/debug renderers +
renderMode; 3) Iris shells; 4) client mixins in 2–3 grouped commits; 5) port-note (R5 executed
checklist + R13c/i/k evidence).
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset. **(f) Rollback/flag:** no flag; revert-only.

### S13 — SCC closure + FIRST LIGHT rung 1: same-dimension portals (U11 + the U12 closure slice + the wand/dim_stack/alternate_dimension shells) — effort XL

The closure and rung 1 are one stage because neither is testable without the other — but the
FIRST-LIGHT surface is deliberately the SMALLEST possible: same-dimension command portals, which
exercise Portal entity spawn/sync (`PortalSyncPacket`), the stencil driver, `ViewAreaRenderer`,
collision and client teleport **without** cross-dim chunk loading, dim-sync, or secondary
ClientLevel construction — isolating the R5 sign flips and the driver core from R1/R7/R9, which
get their own rung at S14. **The ticket machinery is NOT isolated:** IP's `ChunkVisibility`
builds portal-direct chunk loaders from `portal.getDestDim()` unconditionally
(`IP:ChunkVisibility.java:115,134,159,168`), so a same-dim command portal engages
`ImmPtlChunkTracking`/`ImmPtlChunkTickets` at rung 1 — merely MASKED because the dest chunks
are already loaded around the player. R10's ticket machinery makes first runtime contact HERE;
S14 owns its first cross-dim, actually-loading contact.

**(a) Contents** (U11 row + the closure's PROVEN extensions): `commands/*` (`PortalCommand`,
`ClientDebugCommand`, argument types) **+ `api/example/ExampleGuiPortalRendering` (round-3
sweep — previously owned by NO stage: `PortalDebugCommands.java:47` imports and `:89` uses it;
its own imports — GuiPortalRendering, MyRenderHelper, WorldRenderInfo, ChunkLoader,
DimensionalChunkPos, PortalAPI, McRemoteProcedureCall, DQuaternion — are all ≤S11, so it joins
the closure cleanly once owned)** **+ `platform_specific/IPConfigGUI` (previously unowned:
`ClientDebugCommand.java:56` imports and `:409` uses it; `IPConfigGUI.java:3` imports
`me.shedaniel.autoconfig.AutoConfig` — a cloth-config/autoconfig dependency with no 26.2
build, OUTSIDE F21's original Sodium/Iris scope — covered by the F21 EXTENSION: the stub
classpath grows an autoconfig shell, register row updated)**, `block_manipulation/*`,
`miscellaneous/` (GcMonitor, initial screen), `debug/`, **the WAND-package compile shell —
constraint 7's "PortalWandInteraction is a self-contained peripheral closure file" is WRONG:
`PortalWandInteraction` same-package-references `PortalWandItem` (`:325`), `ProtoPortal`
(`:58,:120`), `WandUtil` (`:759`); `PortalWandItem` reaches
`ClientPortalWandPortalCopy`/`Creation`/`Drag`, which import `q_misc_util.CustomTextOverlay`
(landed S7) and `mc_utils.WireRenderingHelper` (landed S11) plus `PortalCorner` and
`ProtoPortalSide` — ALL NINE `peripheral/wand` files are S13 compile-mandatory** +
`CommandStickItem`
(cycle 9), generation shells `PortalGenInfo`+`CustomPortalGenManager` head (cycles 8/14;
Appendix A.6), **and `redstone/CrossPortalRedstoneMediumBlockEntity` (dead upstream — zero
in-tree referencers, zero qouteall imports; ported verbatim here for fidelity with an explicit
disposition row in Appendix A.9; never registered — a zero-referencer file has no registration
site in IP either)**. Plus two closure extensions the U11 row does not name, both
compile-mandatory:
- **The dim_stack compile shell — ALL ELEVEN `peripheral/dim_stack` files, not a
  DimStackManagement-plus-first-hops subset** (constraint 7's original three-file list was
  wrong twice over — see also the wand shell above): `PortalCommand.java:75` imports
  `qouteall.imm_ptl.peripheral.dim_stack.DimStackManagement` (grep-verified). DimStackManagement
  in turn imports `qouteall.dimlib.api.DimensionAPI` — **so the F11 DimLib event-wiring stub
  must cover `DimensionAPI` by S13, not just S4's `DimensionIntId` use** — plus
  `GlobalPortalStorage`/`VerticalConnectingPortal` (S7) and `McRemoteProcedureCall` (S7).
  **The GUI half does NOT stay S19 at compile level (the round-2 "verified first hops" bound —
  DimStackInfo, DimStackEntry, DimensionStackAPI, DimStackGuiController — was an undertrace):**
  `DimStackManagement.java:197` constructs `DimStackGuiController`; `DimStackGuiController`
  references `DimStackScreen` (`:27,:40`), `DimStackGuiModel` (`:26,:39`), `DimEntryWidget`
  (`:62,:81-88,:149`); `DimStackScreen` pulls `DimListWidget`, `DimStackEntryEditScreen`,
  `SelectDimensionScreen` (same-package). With the four first hops that is the ENTIRE 11-file
  package as the S13 compile shell; only runtime wiring/registration (e.g.
  `MixinCreateWorldScreen_CVB`) stays S19.
  **The traced chain does NOT stop at dim_stack:** `DimStackGuiController.java:12` imports
  `qouteall.imm_ptl.peripheral.alternate_dimension.AlternateDimensions` — the R13g-blocked S19
  package — so the compile shell extends into `alternate_dimension` and its chunk-generator
  chain, which pulls the S19-listed `NoiseBasedChunkGenerator` AW/AT work forward to S13. The
  full chain re-trace to its true fixpoint is therefore a MANDATORY S12-exit/S13-entry
  deliverable (probe-governed, recorded in the port-note); `alternate_dimension`'s COMPILE
  surface joins this shell — **EXPLICITLY INCLUDING its three `@Mixin` accessor/invoker
  interfaces: `NormalSkylandGenerator.java:44-46` imports `IEChunkAccess_AlternateDim`,
  `IEChunkGenerator_AlternateDim`, `IENoiseRouterData`
  (`imm_ptl.peripheral.mixin.common.alternate_dimension` — previously bucketed with S19's
  peripheral mixins; they move HERE), plus AW/AT and the F11 DimLib-stub coverage the chain
  demands: `AlternateDimensions` imports `qouteall.dimlib.DimensionTemplate`, so the F11 stub
  must cover `DimensionTemplate` too, not just `DimensionAPI` (register row updated)** — while
  R13g's dynamic-dimension DESIGN and all runtime/GUI bring-up stay C1/S19-gated.
  The C1 "skip dim stack / skip alt dims" decision stays valid ONLY for the runtime/GUI
  halves — never the compile shell.
- **The U12 closure slice — the SCC provably extends into U12, so this stage's green build
  REQUIRES co-porting the generation pipeline HEAD** (all grep-verified this pass):
  `NetherPortalGeneration`, `NetherPortalMatcher`, `FastBlockAccess`, `FrameSearching`,
  `BlockTraverse`, `FastBlockPortalShape`, `SimpleBlockPredicate`, `PortalGenTrigger`,
  `BreakablePortalEntity`, `NetherPortalEntity`, `GeneralBreakablePortal`,
  `CustomPortalGeneration` + **the ENTIRE 12-file `form` package** (the round-2 four-file bound
  — PortalGenForm/AbstractDiligentForm/NetherPortalLikeForm/ScalingSquareForm — was WRONG:
  `PortalGenForm.java:27-48` codec-registers SEVEN S16-scheduled forms in its own STATIC BODY —
  `ClassicalForm` :27, `HeterogeneousForm` :30, `FlippingFloorSquareForm` :33,
  `FlippingFloorSquareNewForm` :39, `DiligentForm` :42, `ConvertConventionalPortalForm` :45,
  `OneWayForm` :48 — and `AbstractDiligentForm.java:22-29` uses `DiligentMatcher`; all
  same-package, INVISIBLE to import-grep).
  Why each: `ChunkLoader.java:10` (U7, landed S9) imports `FastBlockAccess`;
  `PortalCommand.java:69-70` imports `BreakablePortalEntity` + `NetherPortalMatcher`;
  `PortalGenInfo.java:15-16` imports `BreakablePortalEntity` + `NetherPortalGeneration`;
  `IPModMain.java:45-46` imports `GeneralBreakablePortal` + `NetherPortalEntity`;
  `CustomPortalGenManager` references `CustomPortalGeneration` 23× via same-package access
  (INVISIBLE to import-grep), and CustomPortalGeneration imports `form.PortalGenForm`.
  **The slice compile-closes only WITH its same-package/imported internals** — the plan's own
  "same-package references count" rule, applied here: `NetherPortalGeneration` references
  `FrameSearching` same-package at `IP:NetherPortalGeneration.java:122,217`
  (`FrameSearching.FrameSearchingFunc` type + the `startSearchingPortalFrameAsync` call);
  `NetherPortalMatcher` references `BlockTraverse` same-package at `:100,110,198`;
  `CustomPortalGeneration` references `PortalGenTrigger` at `:74,106,115` (codec field + the
  trigger field/ctor param); and the form chain explicitly imports `FrameSearching`,
  `FastBlockPortalShape`, `BlockTraverse` and `SimpleBlockPredicate`. Without those five, the
  slice does not compile and "full tree green" is red as previously specced. The pull stays
  bounded: `FrameSearching` imports only `McHelper`+`MiscHelper`, `BlockTraverse` only `IntBox`,
  `PortalGenTrigger` only `McHelper` — all U1–U3-safe; the slice's own imports overall are
  U1–U7-safe. It is the generation pipeline head, not "additional small U12 files" (the old
  A.4 hedge, now retired). S16 shrinks to RUNTIME bring-up plus `IntrinsicPortalGeneration`/
  `peripheral/portal_generation`/ignition mixins/datapack registries — its "remaining forms
  internals" line is retired, the whole form package compiles here. Then:
1. **Flip `ip_scc_closed=true` in `gradle.properties`** (committed). The whole `qouteall` tree
   compiles in `:common` AND `:fabric` (+`:common:test`) — the Sodium/Iris-importing compat
   files against the F21 `compileOnly` stub classpath. Burn down the translation-slip tail
   against the slice maps until green — never by simplifying. The per-stage probes + the S12 hard
   gate exist precisely so this tail is short and bounded to closure-set surprises (U11 files +
   the dim_stack/alternate_dimension shell + the U12 slice).
2. **Register the IP mixin configs** (S0 skeletons, now populated), ALL gated on `entityPortals`
   via `SeamlessMixinConfigPlugin` (D3).
3. **Exclusivity ledger populated + ENFORCED in the same commit**: every block-era driver gains
   its `!entityPortals` gate (D3 census); the S3 pump host gains its live flag dispatch. From
   this commit onward, exactly one driver set runs per session in either flag state.
4. **Wire runtime init behind the flag**, exactly in DEPENDENCY_ORDER §4.2 order into the
   S0-named seams (init sequences, login order, client tick order, frame order, server tick order).
5. **Register entity types + placeholder block + argument types + payloads unconditionally**
   (D3), **and wire the entity-RENDERER registrations through the S0 renderer-registration
   seam** — `PortalEntityRenderer` for the Portal entity-type family + `LoadingIndicatorRenderer`
   for `LoadingIndicatorEntity` (IP does this in `IPModEntryClient:27-28`, which is not ported —
   Appendix A.9; without this wiring, rung 1 renders no portal at all).
6. `Mesh2DTest` + `HelperTest` un-held (the D1 test-task list's explicit exclusions drop) +
   the IntBox invariant tests activate; full `:common:test` green — the carried IP math harness
   runs for the first time.
**(b) Forward-ref debt:** none — "⬅ FIRST GREEN BUILD" (DEPENDENCY_ORDER U11 — which holds only
WITH the U12 closure slice and the wand/dim_stack/alternate_dimension compile shells co-ported
above; Appendix A.4).
**(c) Commits:** 1) closure sources (U11 files + ExampleGuiPortalRendering + IPConfigGUI + the
wand/dim_stack/alternate_dimension compile
shells + the U12 closure slice incl. the full form package + the redstone disposition file
+ the `render/ShaderCodeTransformation` render-shell (S12-B gate-set amendment above): land it as a
SHELL with its GONE-`CompiledShader` transform internals commented / FrontClipping-deferred, OR gate
the `IPModMainClient.init()` call — REQUIRED for `IPModMainClient` to compile green);
2) the flip + burn-down (one or more honest commits); 3) mixin-config registration +
flag gating + block-era `!entityPortals` gates + init wiring + exclusivity ledger;
4) unconditional registrations + renderer-seam wiring; 5) Mesh2DTest/HelperTest enable;
6) port-note.
**(d) runClient test:**
- **Part 0, flag OFF (default):** BASELINE-SANITY in the normal block-portal world — must be
  UNCHANGED (proves the closure landed inert; the old system untouched).
- **Part 1, flag ON — rung 1 script (NEW dedicated creative test world, ideally superflat; never
  a main world — D3):**
  1. `/portal make_portal 3 3 minecraft:overworld shift 20` (PortalCommand utility group syntax,
     ducks-api-misc.md §2.3; use tab completion). CORRECT: a 3×3 see-through window showing
     terrain 20 blocks away, stable at all view angles, no Z-fighting. FAILURE SIGNATURES →
     risks: black/empty window (view-area mesh or stencil masks — R5/R6); portal shows sky/void
     or draws in front of everything (reversed-Z flip missed — R5); inside-out view (transform
     sign — S6 notes); crash on spawn (entity-type/NBT — R11); portal entity invisible entirely
     (tracking-range conversion — F14); crash or hang in `ChunkVisibility`/`ImmPtlChunkTracking`/
     `ImmPtlChunkTickets` at portal spawn or in the following ticks (R10 — the ticket path runs
     even same-dim: ChunkVisibility builds a dest-dim chunk loader unconditionally, see the
     stage preamble).
  2. Walk through, forward AND backward AND strafing. CORRECT: seamless reposition, no camera
     snap, hand steady, sprint preserved, motion-side exits, no oscillation (items 1, 2 — the
     S3-soaked anchor now drives IP's manageTeleportation).
  3. `/portal set_portal_scale 2`, `set_portal_rotation`, `set_portal_destination` variants —
     view updates live; `/portal view_portal_data` NBT dump renders.
  4. `/portal complete_bi_way_portal` — return portal appears; cross back and forth 10× — no
     ping-pong (item 1).
  5. Cross-portal block interaction: break/place a block THROUGH the window
     (block_manipulation).
  6. Global portal: create one (`/portal global` variants), RELOG, verify it persisted —
     **the R11 silent-loss check: if it comes back EMPTY, that is the swallowed-NPE signature
     from the SPIKE-R11 memo (F15's loud guard should have fired instead).**
  7. renderMode smoke: debug renderer on/off (A1 family present).
  8. Console watch: no per-frame render-thread logging (item 11), no packet floods.
  9. Flip flag OFF, reopen the block-portal world: unchanged (dual-driver honesty per the ledger).
**(e) Regression items (flag-ON world):** 1, 2 (same-dim form), 7 (partial — CrossPortalEntity-
Renderer absence is status quo per the verdict; completes S18), 10 (partial — large portal by
command), 11, 12. Flag-OFF: full sanity subset.
**(f) Rollback/flag:** flag OFF default restores today's behavior wholesale; revert of the flip
commit restores the held state. Both proven before the stage closes.

### S14 — Bring-up rung 2: cross-dimension portals — effort L

**(a) Contents:** bring-up fixes only, each committed individually. This rung owns the FIRST
runtime contact of: secondary `ClientLevel` construction (R1 seaLevel protocol), `PacketRedirection`
transport (R7 §A ordering), the first ACTUALLY-LOADING contact of `ImmPtlChunkTracking`/tickets
(R10 flags — the ticket machinery itself first ran at S13 rung 1, where the dest chunks were
already loaded; see the S13 preamble), `WorldInfoSender` weather
(F1), graduated dest loading (memory `ip-dest-loading-model`: dest preps over time, ~8 chunks
default — gradual fade-in is FAITHFUL, not a defect), per-dim fog/lightmap (R9).
**(b) Forward-ref debt:** none. **(c) Commits:** "rung-2 fix: <defect>" series + sign-off.
**(d) runClient test (flag ON, test world):**
  1. `/portal make_portal 4 4 minecraft:the_nether 0 70 0`. CORRECT: nether view fades in over a
     few seconds as chunks arrive; portal-view lighting correct BEFORE any crossing (item 8 —
     lateUpdateLight); fog/sky in the window nether-correct with no fog bleed into the overworld
     frame (R9). FAILURE SIGNATURES: crash constructing the secondary world (R1 seaLevel);
     wrong-dimension block updates / phantom blocks (R7 ordering — the respawn-mislabel shape);
     window stays empty forever (R10 tickets not loading); fog corruption in the MAIN world after
     looking through (R9 UBO slot written mid-frame).
  2. Cross into the nether; play 2 minutes; break/place blocks (remesh — item 9); cross back.
     CORRECT: no chunk holes, limbo bands, or distant-chunk vanish either side (item 9); no
     crossing freeze (memory `crossing-resend-storm` class).
  3. Double-crossing stress: two fast back-to-back crossings — watch for wrong-dim phantom
     blocks (R7's specific historical signature).
  4. Large portal (`/portal make_portal 10 6 minecraft:the_nether …`) and negative-coords portal
     (`… -128 70 -128`); cross both (item 10).
  5. `/portal debug report_chunk_loaders` + per-player loading report — loader radii sane,
     collapse after walking away (R10 ticket lifecycle; the addPlayer PLAYER_SIMULATION path).
  6. Weather: `/weather rain` in the overworld, observe through the window from the nether —
     rain state correct; time-of-day mismatch is EXPECTED (F1, weather-only WorldInfoSender).
  7. Long-walk probe both dims post-crossing hunting limbo bands (the §11.7 empirical test of
     IP's no-ACK model — if drops reproduce, checkpoint C8 activates: hunt vanilla-path root
     cause first; a reliability layer re-enters only as a documented deviation, conditional F20).
  8. **R13k fluid-fog-in-camera check (the risk map names this stage as R13k's runtime
     proof):** submerge the camera in water (and lava, with fire resistance) directly beside
     the portal while looking through the window — fluid fog applies correctly on the near
     side, the portal view keeps the DEST dimension's fog, and no stale camera state persists
     after surfacing (exercises the S12 camera pull-model gates: `initialized` flag +
     post-extract view-rotation processing).
  9. Relog standing next to the nether-side portal (item 12).
**(e) Regression items:** 1, 2, 8, 9, 10, 12.
**(f) Rollback/flag:** flag OFF default; revert fixes individually.

**PROGRESS: S14 CLOSED — rung 2 SIGNED OFF by the user 2026-07-18** (commit series S14.1→S14.52,
final `0645a6b`; port-notes S14A → `S14C-round8-teleport-flash.md` — round 8 §1-§16 is the
defect ledger of record). All (d) steps PASS (step 8's window-through-water-fog user-verified
IP-authentic → briefing §5 deviation; step 6 window rain visuals S18). Closed live-defect
sagas, each root-caused → Fable-verified → user-confirmed: the sky wedges (gizmo trio),
far-walk terrain wipe (delta pump + readiness gate + cold pre-resolve), teleport flash (the
attributeProbe snap — IP's fog-swap 26.2 half), phantom boost rocket (third-path skip + orphan
discard under player reuse — NOTE: S15's F2 item effectively PRE-COMPLETED live:
reproduced-on-the-IP-path log-proven, applied, verified), crossing lag (warm gate + tracker
continuity; 15-35ms → 7.5-19ms), the boundary shadows (frontier deferral + the light
poll→publish adjacency restoration + main-dim arm guard). Classified IP-authentic by user
side-by-side: crossing far-chunk unload/reload, chunk-loading-failure log noise. Routed
forward: S18 gains the multi-portal parity gap + dest clouds/particles/block-outline; S15
gains the recursion entity items (player self-render, layer≥2 entities); briefing §5 gains
the full-32-RD keep-loaded toggle deviation. Bonus user-confirmed: entities visible through
windows. Resume doc: `migration/S15_HANDOFF.md`.

### S15 — Bring-up rung 3: entity traffic + F2/F3 + gametest — effort M

**(a) Contents:** bring-up fixes for non-player crossings via the ONE unified path
(`Portal.SERVER_PORTAL_TICK_SIGNAL` → `getEntitiesToTeleport` → `teleportRegularEntity` — the
briefing §6 two-path rule now structurally satisfied); **F2/F3 reproduce-then-apply** (the
fidelity-honest way to carry the mod's paid-for bug fixes): reproduce each hazard on the PORTED
path FIRST, then apply the documented port-local patch, with dated register evidence in the
commit message; **gametest extension**: automated server-side crossing smoke (spawn portal +
item entity, assert arrival position/dimension) added beside `TitleCardCapture` in
`fabric/.../gametest/` — run at every later stage (D4.6).
**(b) Forward-ref debt:** none. **(c) Commits:** fix series; "F2: attached-firework skip
(reproduced <date>, applied)"; "F3: preserveTransientHurtState (reproduced <date>, applied)";
"gametest: entity crossing smoke".
**(d) runClient test (flag ON, test world):**
  1. Throw items through both same-dim and nether portals. CORRECT: land on the emergence side,
     reachable, visible through the portal immediately, no 15s invisibility, no frame-lava drift
     (item 3 — the entity-vanish class).
  2. Bow-shoot arrows through. CORRECT: continuous flight, full speed, no 8× scaling, findable
     (item 4).
  3. Shoot a cow, lead it through — keeps panicking after crossing (item 5; watch for the
     `Brain.getMemory` unregistered-slot crash — `hasMemoryValue` first, briefing §6).
  4. Elytra + firework boost through: BEFORE F2 is applied, confirm the phantom rocket
     REPRODUCES on IP's `restoreFrom` recreate path (register evidence); AFTER, confirm gone
     (item 6).
  5. Stand straddling the portal plane (F5 view): entity renders whole to the current status-quo
     standard (item 7 partial; full two-sided render is S18).
  6. Minecart/boat with passenger through (IP has `teleportVehicleAcrossDimensions` built in —
     current-mod-core §2): note results; defects are backlog-grade unless crashes (briefing §5
     task #11 context).
  7. Run the fabric gametest task — crossing smoke green.
**(e) Regression items:** 3, 4, 5, 6, 7 (partial).
**(f) Rollback/flag:** flag OFF default.

**PROGRESS: S15 CLOSED — rung 3 USER-SIGNED-OFF 2026-07-18** (commit series S15.1→S15.5,
final `13d1ecc`; port-note of record `S15-entity-traffic.md` §1-§7). Register items BOTH
DONE with dated evidence: F2 recorded (pre-completed S14.47, `63174f6`); F3
reproduce-then-apply executed by the book (CrossingSmoke leg 3 RED `306ab5d` = the
reproduction, applied `eea9675` at BOTH restoreFrom sites, Fable verify PASS, live
"panic stayed on teleport"). NEW gametest `runCrossingGametest` (D4.6, re-run every later
stage): 4 server-asserted legs — same-dim item, cross-dim item, F3 hurt-carry, pearl
crossing + relatives net — ALL GREEN. Watch-list recursion items CLOSED live ("i see
entities and my own body"): the layer≥2/same-dim entity gap + missing render-yourself =
ONE proven root (sharedState-gated extract+submit; mutual exclusion with the ported
render-yourself gate), fixed by the isolated same-dim entity pass
(renderPortalEntitiesSameDim + trio + sde= probe + debug_skip_same_dim_entities lever;
2-lens Fable verify PASS). Round-1 crash-class pearl FREEZE root-caused from the user's
disconnect reports (26.2 vanilla pearl branch → respawn packet → 26.2 setScreenAndShow
MID-PACKET frame → pump assert) and fixed both halves: the S10-C-deferred R13a resolution
(@Redirect → seamless forceTeleportPlayer + vanilla-shaped PositionMoveRotation relatives
pass-through through the R8-stamped overwrite — verify caught the momentum-wipe blocker
in the first cut) + the 26.2-forced pump transient-frame guard (also closes the
login-window hazard; protects every vanilla respawn flag-ON). All (d) steps PASS;
classification court (user IP side-by-side): arrows-near-solid IP-AUTHENTIC; pearl
BETTER-than-IP (kept); panic-transfer BETTER-than-IP (kept — original does not transfer
panic). Verify layer track record: 2 real blockers + the E7 fidelity rework caught
pre-ship across 4 verify passes. Routed forward: portal-aware panic/escape pathfinding →
briefing §5 (NEW); vehicle recreate presentation gap (minecart ~1s vanish + passenger
flicker + fast-speed stutter) → S18/polish; straddle two-sided render → S18 (standing);
refused-owner pearl fallback drop → recorded on the deferred dead-player-fallback item.
NEW 26.2 invariant (briefing §6-grade): setScreenAndShow renders frames SYNCHRONOUSLY
mid-packet-handling — every pre-render consumer must tolerate the transient
player/level-mismatch frame.

### S16 — Portal generation (U12) — rung 4 — effort XL

**(a) Contents** (the U12 REMAINDER — the generation-pipeline HEAD already compiled at S13 as
the U12 closure slice: `NetherPortalGeneration` ~306, `NetherPortalMatcher` ~275,
`FastBlockAccess` ~189 (R13d sizing surface, the S5 wrappers pay off here), `FrameSearching`
~153, `BlockTraverse`, `FastBlockPortalShape`, `SimpleBlockPredicate`, `PortalGenTrigger`,
`CustomPortalGeneration` ~290 + the `form.PortalGenForm` chain, `BreakablePortalEntity` ~294 /
`NetherPortalEntity` ~140 / `GeneralBreakablePortal` **+ the ENTIRE 12-file form package incl.
`DiligentMatcher`** — round 3: the old "remaining forms internals" line is RETIRED, every form
file compiles at S13 (PortalGenForm codec-registers all seven remaining forms in its static
body, S13(a)); this stage brings the
WHOLE pipeline up at runtime): datapack dynamic registries at
server start, `IntrinsicPortalGeneration` (~137) + ignition/trigger mixins — **including
`imm_ptl.peripheral.portal_generation`** and the peripheral ignition mixins
(`MixinAbstractFireBlock_CVB`, `MixinFlintAndSteelItem_CVB`) per current-mod-core §0.2; the
four `mixin/common/portal_generation` mixins (`MixinItemEntity_P`, `MixinItemStack`,
`MixinMinecraftServer_P`, `MixinPlayerList_P`) LANDED at S10 with the 74 common mixins (the
S10/S16 double-claim is resolved at S10(b)) and are only REGISTERED/wired here — plus
the runtime bring-up/wiring of the S13-compiled head. All flag-gated like everything ported.
**Ledger update:** IP's structural suppression (redirected `findEmptyPortalShape`) REPLACES the
block-era `handlePortal` cancel as the thing keeping vanilla portal blocks from forming (D3).
- R13d wrappers verified against FastBlockAccess sizing + NetherPortalMatcher staging.
- The defaulted-registry `entity_type` lookup decision (getValue → pig fallback matches 1.21.3,
  portal-generation note 3) recorded here.
**(b) Forward-ref debt:** none (post-closure; probes retired).
**(c) Commits:** 1) forms/triggers runtime bring-up; 2) generation-head
runtime bring-up + intrinsic + ignition mixins + ledger update; 3) custom-generation datapack
registries; 4) breakable-family runtime fixes; 5) port-note.
**(d) runClient test (flag ON, test world):**
  1. Build a standard obsidian frame in the overworld, flint-and-steel it. CORRECT: NO vanilla
     purple blocks form (structural suppression); `LoadingIndicatorEntity` progress appears;
     after the async dest search/fabrication a seamless entity portal (4-portal cluster,
     bi-way/bi-faced) exists; crossing behaves as S13–S15. FAILURE: vanilla portal blocks appear
     (suppression miss), indefinite loading indicator (async pipeline), portal links to wrong
     coords (R13d / floored scaling — item 10).
  2. Existing-frame linking: pre-build a matching nether-side frame near the expected link
     target, ignite the overworld side — links to the existing frame instead of fabricating.
  3. Frame-break: break an obsidian block — portal dies (breakable revalidation); placeholder
     blocks clean up.
  4. Negative-coordinate frame (both coords < 0): link lands exactly (item 10).
  5. Flag OFF: BASELINE-SANITY unchanged.
**(e) Regression items (flag-ON):** adds 8 (real nether portal, pre-crossing lighting), 9 (chunk
holes/limbo watch on generated portals + first-visit worldgen), 10 (full); re-run 1, 2, 12.
**(f) Rollback/flag:** flag OFF = today's game; revert commits.

**PROGRESS: S16 CLOSED — rung 4 USER-SIGNED-OFF 2026-07-18** ("swirls confirmed working, s16
sign off"; commit series S16.1→S16.4, final `1cb7044`; port-note of record
`S16-portal-generation.md` §1-§6). The generation pipeline live end-to-end: headline
frame+flint → seamless 4-portal cluster PASS; fire spread PASS; existing-frame linking PASS;
frame-break PASS; crouch-hatch vanilla portal PASS (teleport live-proven + the swirl render
defect root-caused to the B11-misclassified SectionCompilerMixin — flag-gated, user-confirmed
fixed; the gate-audit rule's 4th scalp → ALL B11 dormant labels distrusted at S20).
Nether-side ignition + negative-coords exactness AUTOMATED as gametest legs 6a/6b (the live
round's nether-side step never actually fired per logs; item 10's 8:1 sign+magnitude now
asserted permanently). Datapack custom generation end-to-end green (leg 5; two 26.2 lessons:
mcmeta min/max_format; dynamic registries load at world open only). Flag-OFF sanity SKIPPED
by user choice (recorded; baseline green through S13-S15). Suite: 7 legs ALL GREEN. R13d
soft-open: no live tall-frame; wrappers code-complete + leg 6b roof-frame staging. Verify
track record this stage: 2 blockers + 1 ledger misclassification caught pre-ship across 4
passes; the D3 suppression swap corrected to the IP-authentic demotion form.

### S17 — THE CUTOVER FLIP — effort M (testing-heavy)

**(a) Contents:** flip `entityPortals` default to `true` (config + fresh-install default). Old
block-portal system now dormant behind `!entityPortals`, still shipped — deletion is S20 ONLY
(constraint 5). **CUTOVER_SPEC sign-off:** R4 and R5 sections formally verified against runtime
evidence from S13–S16 (the spec is the contract; sign-off recorded in the port-note). Then the
full verification block:
- **Full 12-point regression checklist** (briefing §4) on the entity-portal system, plus
  DEPENDENCY_ORDER §4.1 milestone-3 items (nether E2E, breakable lifecycle, global portals).
- **R-verifications with named checks:** R7 double-crossing scenario re-run at default-ON; §11.7
  ACK-honesty long-walk re-run (C8 if holes reproduce); R4 `earlyRemoteUpload` necessity check
  concluded; R10 flag-bits consequence (mob liveness in the portal view vs no piglin flood —
  the S9 decision's written implication).
**(b) Forward-ref debt:** none.
**(c) Commits:** 1) default flip; 2) regression record `migration/port-notes/S17-regression.md`
(per-item pass evidence); 3) spec sign-off note.
**(d) runClient test — the full checklist, scripted:**
1. (item 1) Cross a nether portal forward, backward, strafing; motion-side exit, no oscillation.
2. (item 2) Sprint-cross repeatedly; FOV/hand/velocity steady. 3. (item 3) Throw items + lead
animals through; reachable, visible immediately, no frame-lava drift. 4. (item 4) Arrows:
continuous, full speed, findable. 5. (item 5) Shot pig keeps panicking. 6. (item 6) Elytra +
firework: no phantom rocket (F2 applied at S15). 7. (item 7) Straddling entities render whole
(to the pre-S18 standard). 8. (item 8) Fresh world: nether portal-view flame/lava lighting
correct BEFORE first crossing. 9. (item 9) Long walks both dims after multiple crossings; no
holes/limbo; break/place remeshes everywhere. 10. (item 10) Large (10×6) and tall portals
validate crossings; negative-coord linking exact. 11. (item 11) Long session: no creeping VRAM
(endFrame rule), no render-thread log lines. 12. (item 12) Relog + `/kick` + rejoin — clean
session both paths. 13. **(R13i — the risk map names this stage as its runtime proof)
Fabulous/shader-transparency test:** switch Graphics to Fabulous!, look through + cross
portals, translucents (water/stained glass) in and around the portal view render correctly —
verifies the `useShaderTransparency` re-anchor executed at S12.
**(e) Regression items:** ALL 12 — this stage IS the checklist (+ the R13i step 13).
**(f) Rollback/flag:** `entityPortals=false` returns to block portals instantly (the two-way
switch is why the old system survives until S20).

**PROGRESS: S17 CLOSED — THE CUTOVER FLIP USER-SIGNED-OFF 2026-07-18** ("s17 sign off";
commits `fd06032` + `c496b40`; port-note of record `S17-cutover-flip.md` §1-§6).
`entityPortals` DEFAULT = TRUE on Fabric (missing dir/file/key paths; explicit false = the
two-way switch until S20; Throwable path falls to block-era WITH a loud ratchet-down WARN;
off-Fabric force-false unchanged). Pre-flip hardening landed (the two S14-ledgered items:
capture-point window resolution closing the main-dim LOW residual + the sub-tick re-poison
race; the setLevel re-arm assertion). The pre-flip guard sweep audited ALL 69 always-active
mixins: 3 confirmed leaks gated (NetherPortalUninteractableMixin.continueDestroyBlock — the
S16.2 fix's missed sibling; ServerLevelFireSpreadMixin self-gate; the NeoForge
onServerTick block-era scan) + 2 defensive gates (HandleRespawnMixin — B4 superseded;
ClientPacketListenerAddEntityAdoptMixin); 66 clean verdicts affirmed. CUTOVER_SPEC R4/R5
FORMALLY SIGNED OFF (port-note §4): R4 all five obligations PROVEN (incl. the §1.4
earlyRemoteUpload documented supersession); R5 all 16 rows + §2.2/§2.3 PROVEN; F18 Vulkan
→ S18. THE (d) ROUND: 13/13 PASS (items 1-13 user-verdict good incl. step 13 Fabulous =
R13i runtime-proven; logs audited — 64 IP-inherited ticket-noise errors, zero new classes;
C4 A/B deferred to S18 by design). The >71-chunk same-dim dest residual AUTOMATED as
gametest leg 7 — THE SUITE IS NOW 8 LEGS ALL GREEN (items ×2, F3 cow, pearl+relatives,
generation ×2, >71-chunk store, datapack). Resume doc: `migration/S18_HANDOFF.md`.

### S18 — Trailing render periphery + R3 runtime delivery — effort L

**(a) Contents** (API_RISKS verdict stage 3; R3 runtime half per D7):
- **R3 goes LIVE:** wire the S11-designed delivery mechanism (clip-uniform or one-entity
  storage), verify, iterate. The ported `CrossPortalEntityRenderer` (compiling since S13) now
  renders. Checkpoint C4 confirms or switches the mechanism on evidence.
- Runtime verification of the trailing set: `GuiPortalRendering`, `OverlayRendering` (breakable
  overlay via `submitBlockModel` re-expression, render-core G33/G34), Mirror/`BreakableMirror`
  rendering, `RendererUsingFrameBuffer` as `renderMode=compatibility` (A1 — flip through all
  four modes), A2 view-bob behavior sign-off, IP view refinements (`CrossPortalViewRendering` —
  COMPILED since S11, its round-3 owning stage; this line is runtime verification only).
**(b) Forward-ref debt:** none.
**(c) Commits:** 1) R3 live wiring; 2) periphery verification fixes; 3) port-note.
**(d) runClient test:** (item 7 completion) an animal straddling the portal plane renders whole
from BOTH sides, threshold clip both directions; punch/reach through the portal; damage flash
visible in the portal view (the STILL-OPEN memory items this closes:
`entity-vanish-cooldown-mirror-gate` render half). Mirrors: make a Mirror variant, confirm
reflection. renderMode: normal/compatibility/debug/none each behave per IP. View bob: walk
near/away from a portal — bob scales down near, returns away (changed behavior vs today, C5).
**(e) Regression items:** 7 (now fully), re-run 1, 2, 11.
**(f) Rollback/flag:** revert commits; `entityPortals` still flippable.

**PROGRESS: S18 CODE COMPLETE — READY FOR THE (d) LIVE ROUND + THE C4 A/B** (commit series
S18.1→S18.4, 2026-07-18; ledger of record = port-note `S18-render-periphery.md` §1-§6; every
increment Fable-verified, folded, gametest-gated, pushed):
- **S18.1 (32a93b4): MECHANISM B LIVE** — the §2.1.3 draw-site decision (main pass =
  BEFORE_TRANSLUCENT_TERRAIN, IP's exact end-of-entity slot bytecode-verified; dest passes =
  direct calls after each renderAllFeatures — dest passes run NO framegraph, the state-map round's
  key discovery); ownRenderBuffers → the core-owned endFrame walk (the one unwired mod
  RenderBuffers); the §1.2.5 eviction mandate landed; the verify-converged bracket throw fence
  (3-strike + try/finally restore — a wedged PreparedFrame was a deterministic C4-A/B crash).
- **S18.2 (2259e63): CrossPortalViewRendering LIVE** — IP handler ④ re-homed (the ONE hook the
  S13 re-home missed; zero call sites before). Verify FAIL → 5 folds → re-verify PASS ×2: the
  layer-0 exposure class (Step-10.5 EmptyStackException — the ONLY ungated stack peek in the call
  tree; the un-finally'd switchAndRenderTheWorld restore; the mainChunkSampler ==1 gate + null
  poison; the frozen bobbed-projection capture; the S14.29 stencil exit leak). HONEST GATE NOTE:
  the 8-leg suite is first-person-only — the true branch is proven at the (d) round.
- **S18.3+S18.7 (2d700fa): DEST CLOUDS + DEST WEATHER** — per-dest-dim renderer isolation; the
  crash-2026-07-16 fence class closed WITH PROOF; verify caught the post-crossing null-texture
  nuke (dest clouds would have permanently vanished after any crossing) + the broken same-dim
  weather + the cloudRange stale-utb. Clip deviation (improvement-class) ledgered: IP drew dest
  clouds UNCLIPPED.
- **S18.5+S18.6-instrument (827c0ea): DEST BLOCK OUTLINE** (one boolean + vanilla's per-pass
  predicate — IP had zero outline machinery; chain verified end-to-end) + the flag-ON sliver
  re-bucket (lastPortalRenderInfos trigger) + **the dpMs top-level-only fix** (the S14C-round8
  KNOWN INSTRUMENT ARTIFACT — the S14.52 parity read must be RE-MEASURED before optimizing).
- **S18.4 (this commit): SAME-DIM BLOCK ENTITIES** — the S15 F1 "per-pass visibleSections" gap
  closed (the Step-9 discovery list IS the list; identity chain verified for all four pass
  classes). PARTICLES designed-not-landed (per-dim ParticleEngine adoption plan + named open
  questions, §5); VEHICLE classified IP-INHERITED with the instrument-first capture plan (§5).
- **S18.9:** item-9 condition NOT triggered (grep-proven); F18 Vulkan = no capable run this
  session (optional at (d)); row-4 fuse-view = (d) item.
- **S18 CLOSED — USER-SIGNED-OFF 2026-07-18 ("ALL those things work").** The (d) round ran in
  three sittings: round 1 (rain/clouds/outlines/entities good; the log audit caught + fixed the
  fabric-hook NPE `488f143`; hand sliver → polish), the C4 A/B round (`e70fe6a`: "SAME EXACT
  RESULTS" → **C4 DECIDED, SUBMIT_ORDER_UNIFORM stays**, B live-proven strike-free as fallback;
  glow-in-portal-views ledgered decomposition residual; melee-through-portal classified
  IP-inherited-blocks-only), and the final round (particles ✔ outline ✔ cross-view ✔ mirror ✔
  renderModes ✔ view-bob ✔ minecart ✔). DEST+SAME-DIM PARTICLES landed mid-round (`dcb7583` —
  the isolated world-filtered extract; the per-dim-engine plan overturned by trace). VEHICLE
  CAPTURE: the passenger-guard warn fired ZERO times → the discard-recreate axis is implicated;
  fix starts from the adopt-in-place candidate (port-note §10). Carried minors: row-4 fuse-view
  spot-check, dpMs re-measure (only-if-lag), F18 Vulkan optional. Ledger of record = port-note
  `S18-render-periphery.md` §1-§10. NEXT: S19 via `migration/S19_HANDOFF.md`.

### S19 — Peripheral tail (U13) — USER DECISION — effort S–XL

**S19 PROGRESS UPDATE (2026-07-19): A/C/D CLOSED LIVE-PROVEN, B re-opened by ground truth,
E re-directed.** S19-A live round: tab ✓ all wand modes ✓ crossing ✓ (the teleport crash =
a Temurin C2 JIT defect, mitigated with CompileCommand-exclude on both run configs — NOT
the mod). S19-C live round: "all worked" (2 live GUI defects fixed probe-first — the 26.2
cached-row-geometry class). S19-D `f0b9df1` live round: skyland biome variety ✓ (the
noNewCaves mod-side re-derivation), fog ✓, chaos ✓, stack portals ✓, regression ✓; THREE
user-routed polish items (bright-night darkness; void-not-empty; THE DIM-PERSISTENCE GAP —
alt dims don't survive reopen, the un-ported DimLib persistence half, top polish item);
runtime-add refusal = the documented R13g-PHASE-2 deviation. GROUND-TRUTH OVERTURN
(user-supplied): Sodium 0.9.1 + Iris 1.11.2 + ModMenu 20.0.0-beta.4 + cloth-config
26.2.155 ALL have real Fabric 26.2 releases — the C2 "dead on 26.2" premise is FALSE;
S19-E is user-directed to wire the REAL artifacts (no stale F21 stubs) + the cloth swap
re-opens S19-B's config screen; the C2 DEPTH question goes to the user at S19-E open.
NEXT SESSION: `migration/S19E_HANDOFF.md`. Original opening record follows:**

**S19 PROGRESS (2026-07-18): stage OPENED with the rule-9 scope question — user picked
WAND-FIRST order (1 wand+creative tab → 2 ModMenu GUI → 3 dim-stack GUI → 4 R13g+alt-dims →
5 compat layers). S19-A CODE COMPLETE + suite-green, READY issued for the wand live round:
`6ce907d` (A1 — items/components/tab registration + runtime wiring + the three D3 flag-OFF
guards; verify wf_7e348eaa-89b caught the command-stick flag-OFF GAMEMASTER privilege
escalation pre-ship) + `67fabcb` (A2 — overlay re-expression via submitFeatures-RETURN
submitCustomGeometry; verify wf_88355dbb-8f3 caught the missing-setLineWidth first-frame
crash class + the circle NaN-normal pre-ship; round-2 wf_1a0e88dd-409 PASS). Ledger of
record = port-note `S19-peripheral-tail.md` §1-§3 (incl. the NeoForge TAB class-load
landmine → C7, the same-dim overlay deviation → polish LOW, the B11 sweep additions).
S19-B CLOSED INTO C2 (corrected scout): ModMenu EXISTS for 26.2 (modmenu:20.0.0-beta.4, a
real :fabric dep; the block-era ModMenuIntegration→SeamlessConfigScreen entrypoint stays
live in both flag states — D3 baseline). The blocker is CLOTH-CONFIG only (the shipped F21
AutoConfig.getConfigScreen no-op). Landed: 1:1 IPModMenuConfigEntry (unwired — wiring =
guaranteed NPE) + fabricStubs ModMenu shells (moved OUT of ipStubs in-stage: ipStubs is on
the :fabric classpath and would shadow the real ModMenu types). C2 re-entry = cloth dep +
one entrypoint swap. Port-note §4.**

**C1 DECIDED (user, 2026-07-18, during S16): S19 WILL BE BUILT — "we are not skipping
s19."** The default-skip is overridden; the greenlit branch below is the operative one.
Per-feature ordering/priorities are surfaced at stage open (a scope question, not a
re-ask of the decision). NOTE the R13g implication: alternate-dims runtime is now
greenlit work, so the dynamic-dimension DESIGN (R13g) + the DimLib-stub (F11) runtime
surface become real S19 scope.

**(a) Contents:** checkpoint C1 decides (default: core-complete = SKIP beyond what already
ported). If greenlit: wand RUNTIME bring-up (~3,900 LOC ALREADY COMPILED — the whole 9-file
wand package shipped at S13 as compile shell, S13(a); overlays re-express via
Gizmos/`submitCustomGeometry`, R6; screens via the R13e extract model; what C1 governs here is
runtime/GUI bring-up + item registration only), dim stack GUI/runtime
wiring (~2,050 total; the COMPILE shell is ALL 11 `dim_stack` files — round 3: the GUI half
does NOT stay S19 at compile level, `DimStackManagement.java:197` constructs
`DimStackGuiController`, which pulls the screens/widgets (S13(a)) — plus the chain into
`alternate_dimension`; all already
shipped at S13, so what C1 governs here is only runtime wiring/registration, e.g.
`MixinCreateWorldScreen_CVB`), alternate dims RUNTIME
(~1,500 LOC — the COMPILE surface, incl. the `NoiseBasedChunkGenerator` AW/AT, the three
`peripheral/mixin/common/alternate_dimension` accessor interfaces (round 3 — moved OUT of
this stage's peripheral-mixin bucket to S13) and whatever
translation the ported files needed, already shipped at S13 with the dim_stack shell (S13(a));
what stays S19 and BLOCKED behind R13g is the dynamic-dimension design + all runtime
bring-up/wiring), compat `On*Present` layers + gated Sodium/Iris mixins (dead on 26.2 until
those mods port — C2; compile strategy F21; `MixinSodiumRenderSectionManager` imports
`SodiumRenderingContext` + `IESodiumRenderSectionManager` at `:14-15` — both landed held at
S4, S4(a)), ModMenu config GUI (`IPConfigGUI` itself already COMPILED at S13 under the F21
autoconfig-stub extension — `ClientDebugCommand.java:56` imports it; what stays here is the
ModMenu integration runtime). The compile-closure peripheral surface is far larger than
constraint 7's original three files —
the 9-file wand package, `CommandStickItem`, `IPFeatureControl`, ALL 11 dim_stack files +
`alternate_dimension` (via DimStackGuiController) incl. its three mixin accessors, and
`IPConfigGUI`
— ALREADY in (S4/S13), mandated regardless (constraint 7).
**(b) Forward-ref debt:** none. **(c) Commits:** per-feature.
**(d) runClient test:** per-feature scripts written at greenlight time (wand: create/drag a
custom portal; dim stack: GUI from create-world screen; etc.).
**(e) Regression items:** re-run 1, 2 after any wand/interaction feature (they touch crossing
paths). **(f) Rollback/flag:** per-feature revert; features gated by `IPFeatureControl` semantics
as in IP.

### S20 — Cleanup + deletion (U14) — effort L

**(a) Contents** (U14 row; deletion ONLY here per constraint 5):
- Delete per `current-mod-core.md` (42 REPLACE-BY + 30 DELETE): `PortalInfo`/`PortalLink`/
  `PortalManager`/`PortalTracker`/`PortalDetector` block machinery, `SeamlessServerTeleport`/
  `SeamlessClientTeleport`/`PortalTeleporter`/`ProjectilePortalHandler`, `PortalChunkTracker`
  ACK ledger + `RedirectedPacketApplier` + `PortalEntityTracker` + `RemoteBlockUpdater` + bespoke
  `ModPayloads`, `PortalWorldManager` promote/demote, `HandleRespawnMixin`, dead/legacy classes.
- Delete per `current-mod-render.md` (3 REPLACE-BY + 12 DELETE): the three REPLACE-BY classes
  `StencilPortalRenderer`, `PortalContextSwitch` (mechanics transplanted at S10/S11) and
  `PortalShapeRenderer` (§1.1–1.3) — deleted here once their IP replacements are live, the same
  audit rule as the core side's "42 REPLACE-BY + 30 DELETE" — plus the 12 DELETE rows:
  `CameraTransitionHandler`, `PortalSlicing`, `PortalFrameSuppressor` (§1.17–1.19) and the 9
  DELETE mixins (incl. `GameRendererPortalPrepareMixin`, whose cargo moved at S3).
- **PORT-FORWARD survivor audit** (constraint 5): verify each survivor lives inside its ported
  consumer — stencil substrate mixins (→`RendererUsingStencil`), `PortalRenderTypes`,
  `DimensionRenderHelper`, `SeamlessClientChunkMap` machinery (→`ImmPtlClientChunkMap`),
  `FrontClipping`, `lateUpdateLight` (→`MyRenderHelper`), diagnostics (`CrossingTracer`,
  `RenderSpikeMonitor`, `PerfTimers`, the `rlog` gate — COVERAGE INFO-1: add the
  `SeamlessPortalsConstants` KEEP disposition line), `ServerLevelFireSpreadMixin` re-keyed to
  `ImmPtlChunkTracking.isPlayerWatchingChunkWithinRadius`, and the "(verify)" rows re-proven on
  the ported path before final retention (`ChunkPacketGuardMixin`,
  `ClientPacketListenerAddEntityAdoptMixin`, `ClientPacketListenerLocalPlayerFallbackMixin`,
  `FireworkRocketEntityAccessor`, `LivingEntityHurtAccessor`, **plus `SeamlessClientTeleport`'s
  two PORT-FORWARD (verify) SUB-items** (current-mod-core §5): the lagged-rotation-field shift
  (`yRotO`/`xRotO`/`yBob`…) and the sprint-modifier keeper — re-proven per the S8 port-note
  record BEFORE `SeamlessClientTeleport` is deleted; without that record its deletion here is a
  constraint-5 violation).
- Delete the `entityPortals` flag + all `!entityPortals` branches, the exclusivity ledger's
  active role (archive the file), the holding machinery (`ip_scc_closed`, both held lists, probe), and
  the block-portal mixin registrations.
- Final full 12-point regression + memory update (briefing §6 process).
**(b) Forward-ref debt:** none.
**(c) Commits:** 1) core deletions; 2) render deletions; 3) flag + ledger + machinery removal;
4) survivor audit note + final regression record.
**(d) runClient test:** full 12-point checklist once more (deletions can break survivors via lost
call sites), plus: a pre-migration (block-era) world loads cleanly; relog/kick (item 12 — BOTH
lifecycle paths: `ClientLevel.disconnect` AND `updateLevelInEngines(null)`); renderer-identity
cleanup verified (briefing §5 "likely moot once promote/demote dies — verify").
**(e) Regression items:** ALL 12.
**(f) Rollback/flag:** git revert only — which is why S17–S18 must be fully green first.

---

## 4. Forced-deviation register (hard constraint 1)

Every entry is either 26.2-forced (IP's mechanism cannot exist), dependency-forced, or a
reproduce-then-patch correctness carry. Nothing else deviates. Each lands as a documented block in
its owning stage's port-note.

| # | Item | Kind | Owning stage | Rationale / citation |
|---|---|---|---|---|
| F1 | `WorldInfoSender` time-half deleted (weather-only survives) | 26.2-forced | S9 | clock-map `ClientboundSetTimePacket`, no per-dim daylight boolean (R13j) |
| F2 | Attached-firework skip in the unified crossing path | reproduce-then-patch | S15 | hazard persists in IP's `restoreFrom` recreate (current-mod-core §11.3); item 6; commit carries "reproduced <date>, applied" — **DONE pre-completed S14.47**: reproduced 2026-07-18 (live capture, log-proven on the ported path), applied 2026-07-18 commit `63174f6`, Fable-verified + user-confirmed; register entry = port-note `S15-entity-traffic.md` §1 |
| F3 | `preserveTransientHurtState` on the recreate branch | reproduce-then-patch | S15 | `restoreFrom` drops transient hurt state (§11.4); item 5; same evidence discipline — **DONE**: reproduced 2026-07-18 (CrossingSmoke leg 3 RED, commit `306ab5d`), applied 2026-07-18 commit `eea9675` (both recreate sites), Fable-verified `wf_ef9eb4e4-d63` PASS, gametest green end-to-end (playerAttack staging); register entry = port-note `S15-entity-traffic.md` §3 |
| F4 | Stencil substrate chain (GlBackend/GlConst/RenderTarget/GlStateManager mixins + StencilState) replaces porting-lib stencil enable | 26.2-forced | S12 (consumed) | no stencil at any 26.2 layer (R5); substrate runtime-proven (current-mod-render §3.1) |
| F5 | Reversed-Z sign flips on every IP depth constant/comparison | 26.2-forced | S11 spec / S12 exec | clear 0.0 = far, GEQUAL default (R5); per-constant derivations mandatory (D4.4) |
| F6 | `PortalRenderTypes` pipeline layer + `drawMesh`; all immediate-draw/blit/overlay re-expression | 26.2-forced | S11/S12/S18 | submit→prepare→execute rewrite; no vanilla premultiplied blend constant (R6) |
| F7 | R7 packet re-queue in network.md §A shape (`packetProcessor().scheduleIfPossible`), not IP's `minecraft.execute` | 26.2-forced (order-faithful) | S1 spike / S7 impl | verbatim port would introduce cross-queue reordering 1.21 did not have (R7) |
| F8 | Dim-sync channel extended with per-dim seaLevel | 26.2-forced protocol extension | S1 design / S7 impl / S10 consume | `ClientLevel` ctor's trailing `int seaLevel` has no other source for unvisited dims (R1) |
| F9 | R8 dimension stamp via codec-wrap (or paired packet) instead of write-method inject | 26.2-forced mechanical | S8 design / S10 impl (pair, one commit) | position packet is a record with composite codec, no tail slack (R8) |
| F10 | `LevelRenderer.tick()` role re-placed (remote ClientLevel tick + extraction) | 26.2-forced design | S1 spike / S10 | tick deleted; destruction progress moved (R1) |
| F11 | DimLib event wiring replaced by a static-dimension stub of the EVENT WIRING only | dependency-forced | S4 (`DimensionIntId` use) / S13 (`qouteall.dimlib.api.DimensionAPI` — the dim_stack shell's `DimStackManagement`+`DimensionStackAPI` import it — AND `qouteall.dimlib.DimensionTemplate` — `AlternateDimensions` imports it, round 3) | DimLib has no 26.2 form; dims themselves vanilla (§2.1; INFO-4; R13g); the stub must cover DimensionAPI + DimensionTemplate by S13 |
| F12 | Fabric `Event` objects / payload / entity-type registration behind loader-neutral seams | multiloader-forced | S0 | IP is Fabric-only; never Fabric types in common (§4 ground rules; R13h) |
| F13 | `TicketType` registration via `TicketTypeInvoker` at registry phase; flag-bits decision documented | 26.2-forced | S9 | `TicketType.create` gone; private register; FLAG decision per §11.5 (R10) |
| F14 | `trackRangeBlocks(96)` → `clientTrackingRange(6 chunks)` replicating Fabric semantics exactly | 26.2-forced mechanical | S6 | chunks-vs-blocks trap (current-mod-core §11.6) |
| F15 | `SavedDataType` non-null DataFixTypes constant + loud load-failure guard | 26.2-forced | S1 spike / S7 bound | null → SILENT DATA LOSS on global_portal.dat (R11; portal-generation G1) |
| F16 | Mod diagnostics retained (CrossingTracer, RenderSpikeMonitor, PerfTimers, `rlog` gate) | additive tooling | all | regression tooling for this migration (current-mod-render §1.13-1.15; INFO-1) |
| F17 | R12 vanilla-copies re-derived from 26.2 (collide, checkInsideBlocks clip, anticheat, setPosRaw, teleport, onChunkReadyToSend, doProcessUseItemOn, MyNbtTextFormatter) | 26.2-forced | S8/S10 | "re-derive line-by-line from 26.2, never patch the 1.21.3 copies" (R12) |
| F18 | Vulkan-backend degrade path documented for raw-GL mechanisms (stencil, clip, queries) | 26.2-forced documentation | S11 spec | every raw-GL mechanism silently no-ops under VulkanBackend (R5) |
| F19 | (conditional) pinned-bounded ViewArea, ONLY if SPIKE-R4 disproved the ImmPtlViewArea rebuild | 26.2-conditional | S11 spec | R4; re-accepts the >71-chunk latent bug — needs C3 |
| F20 | (conditional) chunk-send reliability layer, ONLY if walking-limbo symptoms reproduce under IP tracking | evidence-conditional | S14/S17 → C8 | current-mod-core §11.7 — "proven empirically post-port, not assumed away" |
| F21 | Sodium/Iris/autoconfig compile strategy: `compileOnly` stub-classpath artifact (empty shells of exactly the third-party types the compat files reference) | dependency-forced | S0 decide / S4 (sodium) + S12 (iris) + S13 (autoconfig) consume | neither mod exists for 26.2; `SodiumInterface` (compat/sodium_compatibility) USES six `net.caffeinemc.mods.sodium.*` classes in its own statics + companion `@Mixin` `IESodiumWorldRenderer`; the Iris shells import `net.irisshaders.iris.*`; `SodiumInterface` is imported by S9–S12 files so it can be neither dropped nor held past S13. **Round-3 extension: the stub also covers `me.shedaniel.autoconfig.AutoConfig` (cloth-config/autoconfig, no 26.2 build) — `IPConfigGUI.java:3` imports it and `ClientDebugCommand.java:56` imports IPConfigGUI, pulling it into the S13 closure. NOTE: the same-package `SodiumRenderingContext`/`IESodiumRenderSectionManager` are IP SOURCE, not stub types — F21 cannot cover them; they land held at S4 (S4(a)).** Third-party-type shims are build engineering, NOT IP-source stubs — constraint 2 untouched. C2 governs runtime compat only |

**Anti-deviation guards** (things that look like fixes but must NOT be made): FrontClipping's
JOML `Vector4f.mul(Matrix4fc)` idiom STAYS (the "row-vector" note in IP_DEVIATIONS_ANALYSIS is
itself wrong — current-mod-render §1.6; R6); vanilla firework billboard rotation STAYS (briefing
§6); R13b obsolescence-by-vanilla DROPS of IP patches are fidelity-to-intent, logged in the S8
port-note, not silent deletions; faithful vanilla behavior everywhere else stays.

## 5. User-decision checkpoints (default: core-complete)

| # | Decision | When | Default | Notes |
|---|---|---|---|---|
| C1 | Peripheral features: wand, dim stack, alternate dims | before S19 | **DECIDED 2026-07-18: BUILD (user: "we are not skipping s19"); default-skip overridden; R13g design becomes S19 scope** | dim stack's COMPILE shell (ALL 11 dim_stack files — the GUI half compiles at S13 too, S13(a) — extending via `DimStackGuiController.java:12` into `alternate_dimension`, pulling its compile surface + `NoiseBasedChunkGenerator` AW/AT + the three alternate_dimension mixin accessors to S13) is S13-mandatory — PortalCommand imports it; the 9-file wand package's compile shell likewise ships at S13; C1 governs only the runtime/GUI wiring halves. Alt-dims RUNTIME additionally blocked by R13g; the compile-closure files already ported regardless (constraint 7) |
| C2 | Sodium/Iris compat beyond compile shells | before S19 | shells only | dead on 26.2 until those mods port; compile strategy is F21 (stub classpath, decided S0); if entered, COVERAGE INFO-3 requires a full-depth pass on 27 files first |
| C3 | R4 override: keep the pinned-bounded deviation instead of the ImmPtlViewArea rebuild | S11 spec review | rebuild (fidelity, spike-backed) | overriding re-accepts the >71-chunk latent bug (F19) |
| C4 | R3 delivery mechanism (clip-uniform vs one-entity storage) + confirmation of the D7 reading of "trails the cutover" | S11 design review; re-confirm S18 | **RE-CONFIRMED 2026-07-18 (S18 A/B round): user verdict "SAME EXACT RESULTS" under both → SUBMIT_ORDER_UNIFORM stays default; B stays wired as fallback until the S20 loser-code cleanup (port-note S18 §9)** | the compile-level half CANNOT trail (D7); only runtime verification can |
| C5 | A2 view-bob: IP verbatim = visible behavior change (bob returns away from portals) | S12 | port IP verbatim | current-mod-render §2.1/A2 |
| C6 | A5 `HandLightSmoother` (+feed mixin): additive comfort feature, no IP analog | S20 audit | KEEP | zero-deviation bans altering IP behavior, not additive extensions |
| C7 | Dedicated-server / NeoForge parity timing | post-S20 | post-port backlog | NeoForge module stays KEEP-skeleton (current-mod-core §10) |
| C8 | If chunk holes/limbo reproduce under IP's no-ACK tracking | S14/S17 | hunt vanilla-path root cause first; deviation (F20) only with evidence | current-mod-core §11.7 |

## 6. Risk placement map (R1–R13 → stages)

| Risk | Empirical de-risk / design | Implementation | Runtime proof |
|---|---|---|---|
| R1 extract→render split + seaLevel | **S1 SPIKE-R1** (gating design, evidence-backed) | S7 (protocol), S10 (ClientWorldLoader + plumbing) | **S14 rung 2** (first secondary world); S17 item 9 |
| R2 frame-phase anchor | **S3 design round** | **S3 LIVE relocation — soaks under block portals S3→S17** | S3 script; every baseline run after; S13 items 1–2 |
| R3 per-entity clip bracketing | **S11 compile-level design round (D7 — pre-closure, schedule-forced)** | S11 class port on the chosen compile surface | **S18 runtime delivery + item 7** (the half that trails) |
| R4 ImmPtlViewArea fidelity | **S1 SPIKE-R4** → S11 CUTOVER_SPEC decision | S11 class + S12 redirect | S14/S17 item 9; earlyRemoteUpload check S14 |
| R5 reversed-Z / stencil sign flips | S11 CUTOVER_SPEC checklist | S12 execution (substrate F4 consumed) | **S13 rung 1** (isolated from R1/R7/R9 — NOT R10: the ticket path runs even same-dim); S17 sign-off |
| R6 immediate-draw re-expression | mechanisms proven (PortalRenderTypes etc.) | S11/S12; overlays S18 | S13/S14 visuals; S18 |
| R7 packet re-queue ordering | **S1 SPIKE-R7** confirms §A | S7 | **S14 rung 2** double-crossing; S17 re-run |
| R8 position-packet stamp | S8 design | S10 (BOTH halves, one commit) | S13/S14 items 1–2 |
| R9 fog/lightmap/environment | S11 CUTOVER_SPEC (fog ownership design) | S11 (DimensionRenderHelper carried; fog isolation) | **S14 rung 2** dest visuals + main-world fog integrity |
| R10 ticket system | S9 decisions (flags, registry phase, addPlayer path) | S9 | **S13 rung 1** first contact (ChunkVisibility builds dest-dim loaders unconditionally — masked by already-loaded chunks; rung-1 script watches for ticket-path crashes); **S14 rung 2** first real cross-dim loading + loader lifecycle; S17 liveness-vs-flood |
| R11 persistence/entity-type | **S1 SPIKE-R11** (DataFixTypes + loss signature); S6 entity decisions | S6/S7 | S13 global-portal relog check; S16 save/reload; S17 item 12 |
| R12 collision/inside-blocks re-derivation | S8 | S8/S10 | S15 rung 3; S17 items 1, 4, 10 |
| R13a pearls/native teleports | S8 | S8 | S15 (pearl through portal) |
| R13b UUID-resolution obsolescence audit | S8 | S8/S10 | port-note evidence |
| R13c compiled-jar lambda anchors | — | S12 | mixin-apply logs at S13 |
| R13d height-bound wrappers | S5 | S5/S16 | S16 tall-frame staging |
| R13e GUI extract-model | — | S19 (screens), S13 (F3 debug text if ported) | per-feature |
| R13f ClientChunkCache delta protocol | — | S9 (SeamlessClientChunkMap carriage) | S14/S17 item 9 |
| R13g dynamic dimensions | C1 gate | S19 if greenlit | per-feature |
| R13h Fabric v6 / multiloader seams | S0 | S0/S7 | build green throughout |
| R13i useShaderTransparency override | S11 spec line | S12 | S17 Fabulous-setting test |
| R13j WorldInfoSender | — | S9 (F1) | S14 weather check |
| R13k camera pull-model gates | — | S12 | S14 fluid-fog-in-camera check |

---

## Appendix A — corpus doubts / reconciliations (nothing deviates silently)

1. **R3 vs constraint 4 (the one the drafts got wrong):** resolved by D7 — verified this pass
   that `CrossPortalEntityRenderer`'s own BODY calls `bufferSource().endBatch()` at
   :123/:139/:208 and `consumers.endBatch()` at :308, all GONE on 26.2, and `IPMcHelper` imports
   the class. Therefore the compile-level design round + 1:1 port happen at S11 (pre-closure);
   ONLY runtime verification trails (S18). Flagged to the user at C4. Any plan that lands the
   class "unmodified, held" and empties the exclude list at closure does not compile.
2. **"First green build at U11" vs every-stage-green:** refinement, not contradiction — under D1
   the SHIPPING build is green at every stage because held source is excluded from all three
   compile paths; DEPENDENCY_ORDER's "expected red" build is confined to the opt-in probe, whose
   red-ness is itself the per-stage verification instrument. S13 is the first green build WITH
   the ported tree included.
3. **current-mod-render A1's provisional DELETE of the FBO path** is superseded by the
   zero-deviation directive (D5): IP ships `RendererUsingFrameBuffer` as a live config mode, so
   it ports; ~600 LOC moves from DELETE to REPLACE (A1's own deciding question, answered).
4. **The SCC provably extends into U12 (the old "closure scope may creep" hedge is RETIRED —
   replaced by verified fact):** the closure is NOT confined to U11. Evidence (grep-verified):
   `ChunkLoader.java:10` (U7) imports `FastBlockAccess`; `PortalCommand.java:69-70` imports
   `BreakablePortalEntity`+`NetherPortalMatcher`; `PortalGenInfo.java:15-16` imports
   `BreakablePortalEntity`+`NetherPortalGeneration`; `IPModMain.java:45-46` imports
   `GeneralBreakablePortal`+`NetherPortalEntity`; `CustomPortalGenManager` references
   `CustomPortalGeneration` 23× same-package (invisible to import-grep), which imports
   `form.PortalGenForm`. AND the slice's own internals extend it further — same-package
   references count: `NetherPortalGeneration.java:122,217` reference `FrameSearching`
   (`FrameSearchingFunc` type + `startSearchingPortalFrameAsync`);
   `NetherPortalMatcher.java:100,110,198` reference `BlockTraverse`;
   `CustomPortalGeneration.java:74,106,115` reference `PortalGenTrigger` (codec field + trigger
   field/ctor param); the form package imports `FrameSearching`, `FastBlockPortalShape`,
   `BlockTraverse`, `SimpleBlockPredicate` — **and the form pull is the ENTIRE 12-file package,
   not a four-file chain (round 3):** `PortalGenForm.java:27-48` codec-registers
   `ClassicalForm`, `HeterogeneousForm`, `FlippingFloorSquareForm`,
   `FlippingFloorSquareNewForm`, `DiligentForm`, `ConvertConventionalPortalForm`, `OneWayForm`
   in its own static body, and `AbstractDiligentForm.java:22-29` uses `DiligentMatcher` — all
   same-package, invisible to import-grep. S13 therefore co-ports the named U12
   generation-pipeline head INCLUDING those internals and the full form package (bounded —
   `FrameSearching` imports only
   `McHelper`+`MiscHelper`, `BlockTraverse` only `IntBox`, `PortalGenTrigger` only `McHelper`;
   the slice's own imports are U1–U7-safe) and S16 keeps the heads'
   RUNTIME bring-up (its "remaining forms internals" line is retired); the S12 hard gate and
   the S9/S10 debt ledgers name the full set.
   DEPENDENCY_ORDER's "first green build at U11" holds only WITH this slice.
5. **COVERAGE INFO-1:** `SeamlessPortalsConstants.java` is in no disposition doc; treated as KEEP
   (the `rlog` gate encodes the render-thread logging rule); S20's audit adds the line.
6. **DEPENDENCY_ORDER errata (flagged, not smoothed — recommend fixing upstream):**
   (i) the U2 debt column says `O_O`→`PortalGenInfo` "(U9)" but U9's contents don't include it
   and the U11 row lists it among the closure co-ports — this plan follows the U11 row (S13
   closure co-port); if the probe says it's needed earlier, it moves, no structural impact.
   (ii) `CrossPortalEntityRenderer` is listed in U9's CONTENTS but cited as "U10" in U3's debt
   column — cosmetic; this plan ports the class at S11 (U9) and treats U3's debt row as pointing
   there.
   (iii) §2.9's blanket "duck interfaces compile against vanilla only" is WRONG: three ducks
   import held IP classes — `IEClientWorld.java:8` → `Portal`, `IEEntity.java:8-9` →
   `PortalCollisionHandler`+`Portal`, `IEMinecraftServer.java:3` → `IPPerServerInfo`
   (grep-verified). S4 carves in 33 of 36 and holds these three until S13.
   (iv) the U2 debt row labels `CustomPortalGenManager` "(U9)", but U9's contents don't include
   it and the U11 closure row lists its head as a co-port — same defect class as (i); this plan
   follows the U11 row (S13 closure co-port). Recorded so the S4 probe-triage diff against the
   LITERAL U2 row doesn't misfire.
   (v) cycle 15's note that `CollisionHelper`/`PortalCollisionHandler` "need only U1–U4
   material" is WRONG: `CollisionHelper.java:22-33` imports `ClientWorldLoader` (U8),
   `GlobalPortalStorage` (U5 co-port), `MiscHelper` (U2), and the mixin class
   `IEEntity_Collision` (U8). The S6 co-port itself is still right; the debt column was wrong —
   S6(b) carries the corrected ledger.
7. **Briefing §4 "test all after each migration stage":** literally inapplicable to inert staging
   stages (S4–S12 change no live behavior). Interpreted as: full applicable-checklist runs after
   every behavior-affecting stage (S3, S13–S20), BASELINE-SANITY otherwise — each stage's (e)
   line is the explicit mapping.
8. **Sodium/Iris compile shells:** mandatory subset ported at S4 (invoker bases +
   `compat/sodium_compatibility` incl. `IESodiumWorldRenderer` and `IPFlywheelCompat`) and S12
   (Iris renderer shells, cycle 12) regardless of C2 — DEPENDENCY_ORDER §2.7/§2.11 — but they
   are NOT self-compiling on 26.2: `SodiumInterface` imports and USES six
   `net.caffeinemc.mods.sodium.*` classes in its own static methods, `IESodiumWorldRenderer` is
   a `@Mixin` on `SodiumWorldRenderer`, and the Iris shells import `net.irisshaders.iris.*`
   directly, with neither mod existing for 26.2. They compile ONLY against the F21 `compileOnly`
   stub classpath (decided S0); the earlier "even with no Iris on 26.2" claim is true only
   under F21. Round 3 adds: `SodiumInterface` also references two SAME-PACKAGE qouteall files
   F21 cannot cover — `SodiumRenderingContext` (`SodiumInterface.java:60`) and
   `IESodiumRenderSectionManager` (`SodiumInterface.java:72-73`); they are IP source, land held
   at S4 (S4(a)), and `MixinSodiumRenderSectionManager` (S19/gated) imports both at `:14-15`.
9. **Round-3 unowned-file sweep (the round-2 sweep — IPFlywheelCompat + SharedBlockMeshBuffers —
   was NOT complete). Every remaining main-source `qouteall` file now has an owning stage or an
   explicit recorded disposition:**
   - **Owning stages assigned (compile or runtime edges from stages ≤S13):**
     `mc_utils/IPEntityEventListenableEntity` → S4 (Portal.java:49/:84 + MixinEntity_U.java:8);
     `q_misc_util/mixin/IELevelStorageAccess_Misc` → S4 (MiscHelper.java:25/:114);
     `compat/sodium_compatibility/SodiumRenderingContext` + `IESodiumRenderSectionManager` → S4
     (SodiumInterface.java:60/:72-73, same-package); `platform_specific/RequiemCompat` → S4
     (O_O.java:47/:54, same-package); the remaining `q_misc_util/mixin` tree
     (`MixinMinecraftServer_Misc`, `client/IEClientPacketListener_Misc`,
     `client/MixinGui_Overlay`, `dimension/MixinPlayerList_Misc`) +
     `q_misc_util/CustomTextOverlay` → S7; `render/CrossPortalViewRendering`,
     `render/PortalEntityRenderer`, `render/OverlayRendering`,
     `render/LoadingIndicatorRenderer`, `mc_utils/WireRenderingHelper` → S11;
     `compat/IPModInfoChecking` → S12 (F12-seamed);
     `api/example/ExampleGuiPortalRendering` + `platform_specific/IPConfigGUI` (F21 autoconfig
     stub) → S13.
   - **Explicit dispositions (no owning stage was ever assignable):**
     `redstone/CrossPortalRedstoneMediumBlockEntity` — DEAD upstream (zero in-tree referencers,
     zero qouteall imports); ports verbatim at S13 with the closure sources, never registered
     (a zero-referencer file has no registration site in IP either).
     `q_misc_util/MiscUtilModEntry` + `MiscUtilModEntryClient` and the `platform_specific`
     Fabric entrypoints (`IPModEntry`, `IPModEntryClient`, `IPModEntryDedicatedServer`,
     `IPModMenuConfigEntry`, `IEClientWorld_MA`, `platform_specific/mixin/*`) — NOT ported as
     classes: their roles are absorbed by the D2.4/F12 mod-owned loader seams (this disposition
     was previously implicit and nowhere recorded; it is now the recorded decision). Their
     init/registration CARGO is inventoried at S0 and wired at owning stages: the §4.2 init
     order that STARTS from `MiscUtilModEntry` (wired S10 with the IPModMain/IPModMainClient
     sequences), the `DimIdSyncPacket` mid-`placeNewPlayer` login slot (`MixinPlayerList_Misc`,
     landed S7, registered S13), and the entity-renderer registrations from
     `IPModEntryClient:27-28` (S0 seam, wired S13 step 5). Any behavior found in these
     entrypoints beyond the seam-inventoried cargo is a stage-time diff-gate item — never
     silently dropped.

---

## Immediately-next actions (Stage S0, concrete first steps)

1. **Create the holding machinery** (first commit of the migration):
   - `gradle.properties`: add `ip_scc_closed=false`.
   - In buildSrc, define TWO held-paths lists (D1.3): the MAIN list (initially `['qouteall/**']`,
     carve-ins empty) for the two main compile paths, and a SEPARATE test-task list (also
     initially `['qouteall/**']` — it diverges from the main list at S2, when it carves in
     `my_util/**` for tests while keeping explicit `**/Mesh2DTest.java` + `**/HelperTest.java`
     exclusions until S13), plus a small helper that, when `ip_scc_closed != 'true'`, applies
     `exclude(heldPaths)` to a given `JavaCompile` task.
   - Wire the MAIN list in `multiloader-common.gradle` onto `:common:compileJava` and in
     `multiloader-loader.gradle` onto the loader `compileJava` (immediately after the existing
     `source(configurations.commonJava)` block at lines 20-22); wire the TEST list onto
     `:common:compileTestJava`. Do NOT touch the
     `artifacts { commonJava … singleFile }` block (common/build.gradle:35).
   - Verify: `.\gradlew.bat :common:compileJava :fabric:compileJava --console=plain --no-daemon`
     green; then drop a scratch `common/src/main/java/qouteall/Probe.java` with a deliberate
     unresolved import, confirm the shipping build STAYS green and
     `-Pip_scc_closed=true` goes red on exactly that file, in BOTH `:common` and `:fabric`;
     delete the scratch file. This proves the D1 device end-to-end before any real port lands.
2. **Write `migration/port-notes/S00-U0-decisions.md`**: D2 namespace record, D3 flag design,
   D6 spike discipline, D7 R3 reconciliation (explicitly presented to the user for C4
   acknowledgment), F21 Sodium/Iris stub-classpath decision (register entry + the artifact's
   shape; consumed at S4/S12).
3. **Create `migration/EXCLUSIVITY_LEDGER.md` skeleton**: block-era driver census from
   current-mod-core §2/§8 + current-mod-render §3; always-on substrate list; empty S13/S16/S20
   transition columns.
4. **Land the loader-neutral seams** in `com.warwa.seamlessportals.*`: event-object class,
   `PlatformHelper` payload widening, entity-type registration callback, and the named
   tick/login/frame seam inventory (one file documenting each DEPENDENCY_ORDER §4.2 hook point
   and its future wiring stage).
5. **Create the three empty IP-side mixin-config JSONs** (unregistered) + `:common` JUnit test
   infrastructure.
6. **User gate:** run BASELINE-SANITY (block portals, ~10 min), commit the three S0 commits, then
   proceed to S1 (spike branches — no main-tree changes).
