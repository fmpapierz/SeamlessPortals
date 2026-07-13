# PLAN-FIDELITY — staged execution plan for the entity-portal migration (fidelity-first)

**Author bias (assigned):** optimize for zero-deviation safety. Port units in the exact
`DEPENDENCY_ORDER.md` §4 sequence, verbatim-first translation passes, maximal verification depth
(per-stage source-diff review gates, sign-derivation checklists for every geometry/depth constant).
Later first-user-visible payoff is accepted where it buys fidelity assurance.

**Ground truth this plan builds on (and never re-derives):**
`ENTITY_PORTAL_MIGRATION_BRIEFING.md` (the mission + 12-point regression checklist §4 + porting
discipline §6), `migration/DEPENDENCY_ORDER.md` (units U0–U14, cycles §3, port order §4, runtime
constraints §4.2), `migration/API_RISKS.md` (R1–R13 + the staged-substrate/atomic-core/trailing-
periphery cutover verdict), `migration/api-map/current-mod-core.md` + `current-mod-render.md`
(disposition tables), `migration/COVERAGE.md` (INFO-1..4). Where this plan makes a structural
choice, the corpus citation is given inline. Disagreements with the corpus are NOT acted on —
they are listed in Appendix A.

**Invariant for every stage (hard constraint 2):** the stage ends with
`.\gradlew.bat :common:compileJava :fabric:compileJava --console=plain --no-daemon` GREEN from
`C:/Users/warwa/ModDev/Portals/Portal 26.2`, and the game fully playable on the existing
block-portal system until S13 flips the switch. Never `gradlew --stop` (machine rule).

---

## 1. Plan-shaping decisions (settled at U0/S0, applied uniformly)

### D1. Holding mechanism for ported-but-unclosed source: held-at-final-path compile exclusion, property-gated

DEPENDENCY_ORDER §4 ground rules name the only fidelity-preserving option: "hold un-closed units
in an excluded source set until their closure lands (build engineering, not code change)". This
plan's concrete device — chosen over a physically separate source directory — is:

- **Ported IP source lands at its FINAL path from its first commit**:
  `common/src/main/java/qouteall/**` (package policy: D2). No later `git mv`, so per-stage diff
  review and git history compare the same file forever.
- **A single gradle property `ip_scc_closed` (default `false` in `gradle.properties`) gates a
  compile exclusion** applied in the buildSrc conventions to BOTH compile paths that consume the
  common source tree:
  - `buildSrc/src/main/groovy/multiloader-common.gradle` → `:common:compileJava`
  - `buildSrc/src/main/groovy/multiloader-loader.gradle` → `:fabric:compileJava` /
    `:neoforge:compileJava`, which recompile common's whole source dir via
    `source(configurations.commonJava)` (multiloader-loader.gradle:20-22 — verified in-repo).
  The exclusion is expressed as a **held-paths list** (path-prefix closure on the compile task's
  PatternFilterable), defined once in buildSrc, e.g.
  `ipHeldPaths = ['qouteall/']` minus explicit carve-ins. Carve-ins are subtrees proven standalone
  (verified by import-grep at the stage that carves them in): S1 carves in
  `qouteall/q_misc_util/my_util/` (DEPENDENCY_ORDER §2.1: "pure-math subset … zero imm_ptl imports
  — the only genuinely standalone layer"); S2 carves in the duck interfaces
  (DEPENDENCY_ORDER §2.9: "compile against vanilla only"). Everything else stays held until S11
  flips `ip_scc_closed=true`.
- **Why exclusion-on-final-path instead of a second source set:** `common/build.gradle` publishes
  the source dir via `artifacts { commonJava sourceSets.main.java.sourceDirectories.singleFile }`
  (common/build.gradle:35 — verified in-repo); a second srcDir breaks `singleFile` and the whole
  loader-consumption chain. One directory + a filter is the smallest, most reversible device.
- **NOT stubs, NOT simplified variants** (hard constraint 2): held files are verbatim ports; the
  build simply does not compile them yet. Zero throwaway code is ever written.
- **The same property doubles as the per-stage compile PROBE** (a fidelity instrument, see D4):
  `.\gradlew.bat :common:compileJava -Pip_scc_closed=true --console=plain --no-daemon` compiles the
  whole held tree on demand. It is EXPECTED red until S11; the triage rule is that every error must
  map to that unit's documented forward-ref debt (the "Forward refs (SCC debt)" column of
  DEPENDENCY_ORDER §4) — any error that does NOT is a translation slip and is fixed at the stage
  that introduced it, not at S11. (Use `-Xmaxerrs`-style generous error limits via
  `options.compilerArgs` so cascades are visible.) The shipping-gate build never uses the override.
- Mixin configs are resources, held separately: the ported mixin classes exist in held source, and
  the IP-side mixin JSON files list them from the stage they land, but the JSONs are **not
  registered** with the loaders until S11 (and are plugin-gated from then on, see D3).
- The holding machinery (property + held-paths list + probe note) is deleted at S16.

### D2. Package/namespace policy: verbatim retention of `qouteall.*`

Recorded decision (hard constraint 6): **ported IP code keeps IP's original packages exactly**
(`qouteall.q_misc_util.*`, `qouteall.imm_ptl.core.*`, `qouteall.imm_ptl.peripheral.*`), including
mixin packages. Rationale:

1. **RPC FQN strings are wire protocol** (DEPENDENCY_ORDER §4.2): `McRemoteProcedureCall` addresses
   handlers by literal FQN strings at dozens of call sites (e.g.
   `qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.RemoteCallables.updateEntityPos`,
   teleportation-collision.md §5). Since this is a NEW standalone mod, cross-mod wire compat is not
   required — but a rename would demand catching every hardcoded FQN literal, and a missed one is a
   RUNTIME-ONLY failure. Verbatim retention reduces that hazard class to zero. This is the
   fidelity-first choice.
2. **Diff-review gates become 1:1**: every ported file diffs against its IP original with no
   package-rename noise; only genuine 26.2 API translations remain in the diff (D4).
3. Collision risk with upstream IP is nil on 26.2 (upstream stops at 1.21.3; this mod is the
   successor line — memory `seamless-portals-is-ip-port`). If upstream ever arrives on 26.2,
   coexistence was never a goal.
4. NEW code that has no IP original (the U0 loader seams, build machinery) lives in the mod's own
   namespace `com.warwa.seamlessportals.*` — the boundary between "ported IP" and "mod-owned
   substrate" is visible in the package name itself.

### D3. Flag strategy: `entityPortals` load-time flag, STENCIL_DIRECT pattern, dual system S11→S16

Per the API_RISKS verdict ("the mod has already proven flag-gated dual render paths on 26.2
(`STENCIL_DIRECT`) … the atomicity is in the user-facing switch, not the development process"):

- One boolean key `entityPortals` in the existing `SeamlessPortalsConfig` properties file
  (KEEP-disposition, current-mod-core §7). Default `false` until S13; `true` from S13; deleted with
  the old system at S16.
- **Load-time, not runtime**: read once at mixin-plugin time. `SeamlessMixinConfigPlugin`
  (KEEP, current-mod-core §8; the analog of `IPMixinPlugin`/`IPCompatMixinPlugin`/
  `IPPeripheralMixinPlugin`) gates EVERY mixin class in package `qouteall.*` on the flag via
  `shouldApplyMixin`. Common/client init sequences for the ported system (`IPModMain`,
  `IPModMainClient`, `MiscUtilModEntry` — DEPENDENCY_ORDER §4.2 init order) run only when the flag
  is on; the block-portal system's drivers gate on `!entityPortals` from S13 so the switch is
  two-way until S16. Flipping requires a game restart — acceptable, and it avoids every
  half-initialized-manager hazard.
- **Registry entries are unconditional** from S11 (Portal entity-type family +
  `PortalPlaceholderBlock`): registries must not differ between flag states or world saves break on
  flips. Consequence documented for the user: worlds used for flag-ON testing are DEDICATED test
  worlds; never open a main world with the flag ON before S13 (a saved Portal entity would load but
  its managers would be un-inited with the flag off).
- Rollback at any stage S11–S15 = set `entityPortals=false` (or revert the stage's commits). After
  S16 rollback is git-only, which is why S16 is gated on a full green 12-point regression at S13–S15.

### D4. Fidelity instruments (the verification depth this plan is optimized for)

Every stage's gate includes, in order:

1. **Shipping build green**: `.\gradlew.bat :common:compileJava :fabric:compileJava --console=plain --no-daemon`.
2. **Compile probe triage** (pre-closure stages only): `-Pip_scc_closed=true` probe run; the error
   list is diffed against the stage's documented forward-ref debt (DEPENDENCY_ORDER §4 unit table).
   Errors outside the debt list are translation slips — fixed now, against the owning slice map in
   `migration/api-map/`, never by simplifying (briefing directive).
3. **Source-diff review gate**: for every ported file, a recorded diff against the IP original
   (`C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/...`). Allowed differences:
   the mechanical 26.2 translations enumerated by the owning slice map (API_RISKS preamble list +
   slice-map CHANGED rows) and the loader-seam substitutions decided at S0. ANY other difference is
   either reverted or written up as a numbered entry in the stage's port-note. Notes live at
   `migration/port-notes/S<NN>-<unit>.md`.
4. **Sign-derivation notes**: every geometry/depth/winding constant or comparison the stage ports
   (transforms, clip planes, frustum windings, depth funcs, stencil ops) gets a written re-derivation
   from 26.2 source in the port-note — briefing §6: "Never trust a prior geometry-sign claim";
   API_RISKS R5 names the reversed-Z flip as the bug class that has bitten twice.
5. **JUnit math harness** (from S1): `.\gradlew.bat :common:test` runs the carried `Mesh2DTest`
   (+`HelperTest` once Helper compiles at S11) plus authored DQuaternion/Plane/IntBox invariant
   tests — COVERAGE INFO-2 calls this "a free correctness harness"; DEPENDENCY_ORDER §4.1 milestone 1
   calls it "the best regression net for the two past geometry sign errors". Tests are additive
   tooling, not shipped behavior — no deviation.
6. **User runClient test** per the stage section below, then user commits
   (`git commit -F <file>` per briefing §6).

### D5. Decisions settled by the zero-deviation directive itself

- **A1 (current-mod-render §4): PORT the full renderer family.** IP ships `RendererUsingFrameBuffer`,
  `RendererDummy`, `RendererDebug` and the four-way `renderMode` config as live code constructed at
  client init (`IPModMainClient.java:81-84`, `IPCGlobal.java:16-20` — cited in A1). Complete IP
  fidelity means they port (S10); the mod's FBO machinery is refactored INTO
  `RendererUsingFrameBuffer` rather than deleted (~600 LOC moves from DELETE to REPLACE, per A1's
  own note).
- **A2: port IP's `viewBobbingReduce` distance-scaled bob verbatim** (current-mod-render §2.1),
  replacing the mod's unconditional no-op. Flagged to the user as a visible behavior change
  (bob returns when away from portals) — see checkpoint C5.
- **FrontClipping mul idiom**: explicitly NOT "fixed" to `mulTranspose` — the current column-form is
  verified correct and the IP_DEVIATIONS note is itself wrong (current-mod-render §1.6; API_RISKS R6).
  Carried into the S9 sign-derivation note as an anti-deviation guard.
- **A3**: the one-time comparative read of IP's `ForceMainThreadRebuild` vs the mod's folded-in
  budgeted compile scheduling is a scheduled S9 deliverable (current-mod-render §4 A3).
- **A5 (HandLightSmoother)**: user checkpoint C6 — additive comfort feature, default KEEP.

---

## 2. Stage table

Effort scale (from inventory LOC + api-map changed/gone density): **S** ≤ ~300 LOC or config-only ·
**M** ~300–1,500 · **L** ~1,500–4,000 · **XL** > 4,000 LOC or high design density.

| Id | Name | Contents (unit) | Effort | Gate (beyond D4 standard) |
|---|---|---|---|---|
| S0 | Substrate + decisions | U0: holding machinery, namespace record, loader seams, mixin-config skeletons, test infra | M | build green; baseline sanity |
| S1 | q_misc_util + math harness | U1 (carve-in: my_util math; held: Helper etc.) | L | `:common:test` green (Mesh2DTest live) |
| S2 | Ducks, roots, platform facade | U2 (carve-in: 36 ducks; held: O_O/IPConfig/IPPerServerInfo…) | M | probe = U2 debt only |
| S3 | MC helpers | U3 (all held) | M | probe = U3 debt only |
| S4 | Portal core + shapes + animation | U4 + co-ports (BlockPortalShape, CollisionHelper, PortalCollisionHandler, PortalCollisionEntry) | XL | probe; R11 entity decisions recorded; transform sign-notes |
| S5 | Network, global portals, API | U5 + R7 §A shape + R1 seaLevel protocol design + R11 DataFixTypes | L | probe; protocol design doc |
| S6 | Teleportation + collision | U6 + R2 anchor reconciliation + R8 stamp design + R12/R13a re-derivations | L | probe; collision re-derivation notes |
| S7 | Chunk loading + entity sync | U7 + R10 decisions + R13j forced deviation | L | probe; ticket decision record |
| S8 | ClientWorldLoader + common mixins | U8 (74 common mixins) + R1 impl + R8 impl | XL | probe = render-context debt only |
| S9 | Render context + CUTOVER SPEC | U9 + cutover spec (R4 decision, R5 checklist, R9 fog design, A3 read) | XL | probe; CUTOVER_SPEC.md exists |
| S10 | Renderers + client mixins | U10 (renderer family incl. FBO/dummy/debug/Iris shells, ~62 client mixins) + R5 execution | XL | probe = U11 closure files only |
| S11 | SCC closure — first green ported build | U11 closure files; flip `ip_scc_closed=true`; flag-gated wiring | XL | FULL build green, whole tree; flag-ON first run |
| S12 | Portal generation | U12 (nether/custom/breakable/intrinsic + ignition mixins) | XL | flag-ON nether E2E |
| S13 | CUTOVER FLIP | `entityPortals=true` default; full 12-point regression; forced-deviation reproductions + patches | M | all 12 checklist items green |
| S14 | Trailing render periphery + R3 | R3 design round + delivery; GuiPortalRendering/OverlayRendering/mirror/renderMode verification | L | checklist item 7 fully green |
| S15 | Peripheral tail (USER DECISION) | U13: wand, dim stack, alt dims, compat On*Present, ModMenu GUI — default: core-complete (skip) | S–XL | per-feature |
| S16 | Cleanup + deletion | U14: delete old system per disposition tables; delete flag + holding machinery; final regression | L | full 12-point regression; survivor audit |

Stage count: **17** (S0–S16). First stage with user-visible NEW behavior in runClient: **S11**
(flag-ON test world; default-OFF play unchanged until S13).

---

## 3. Stage specifications

**BASELINE-SANITY script** (referenced by pre-closure stages; ~10 minutes): launch runClient →
existing block-portal test world → build/light a nether portal → cross both ways, one crossing
walking backward (checklist item 1: motion-side exit, no ping-pong) → watch FOV/hand/sprint at
crossings (item 2) → throw an item through, confirm it lands reachable + visible (item 3) → before
first crossing, look through the portal and confirm dest lighting is live (item 8) → in the nether,
break/place a block near the portal, confirm remesh (item 9) → disconnect + rejoin (item 12).
Correct = identical to pre-stage behavior; failure = ANY change (these stages must be inert).

### S0 — Substrate + decisions (U0) — effort M

**(a) Contents** (DEPENDENCY_ORDER §4 U0 row; current-mod-core §3/§10 KEEP rows):
- The D1 holding machinery in buildSrc (`ip_scc_closed`, held-paths list, probe wiring) applied to
  `multiloader-common.gradle` + `multiloader-loader.gradle`.
- The D2 namespace decision + D3 flag design recorded in `migration/port-notes/S00-U0-decisions.md`.
- Loader-neutral seams, all in `com.warwa.seamlessportals.*`:
  - Event-object answer for `Helper.createRunnableEvent`/`createConsumerEvent` (q-misc-util.md:321-323
    via DEPENDENCY_ORDER §4 ground rules) — a common-side event class so IP's own event objects
    (`IPGlobal.*_EVENT`, `Portal.*_SIGNAL`) port as plain infrastructure (API_RISKS R13h).
  - Payload registration abstraction behind the existing `PlatformHelper` (KEEP + "likely widened",
    current-mod-core §3), absorbing the Fabric v6 renames (R13h: `clientboundPlay/serverboundPlay`;
    `createS2CPacket` GONE → construct vanilla record packets directly).
  - Entity-type registration callback plumbing mirroring `IPModMain.registerEntityTypes(BiConsumer)`
    (world-loader-root.md §5; current-mod-core §10).
  - Tick/login/frame seam inventory: named hook points for every DEPENDENCY_ORDER §4.2 runtime
    ordering constraint (init sequences, `DimIdSyncPacket` mid-login slot, client tick order, frame
    order, server tick order) so later stages wire into named seams, not ad-hoc call sites.
- Three empty IP-side mixin-config JSONs (core-common / client / peripheral, mirroring
  `IPMixinPlugin`/`IPCompatMixinPlugin`/`IPPeripheralMixinPlugin`, current-mod-core §8) — created
  but NOT registered with the loaders yet (D1).
- JUnit test infrastructure for `:common` (test source set + junit dep).

**(b) Forward-ref debt:** none (U0 has none by construction).
**(c) Commits:** 1) buildSrc holding machinery + probe; 2) loader seams + mixin-config skeletons +
test infra; 3) decisions port-note.
**(d) runClient test:** nothing user-visible changes; run BASELINE-SANITY to verify normal play
unaffected (the buildSrc change is the only live surface).
**(e) Regression items:** 1,2,3,8,9,12 (sanity subset).
**(f) Rollback/flag:** no flag yet; rollback = revert commits. Holding machinery inert
(`ip_scc_closed=false`, held list empty until S1).

### S1 — q_misc_util + math harness (U1) — effort L

**(a) Contents** (DEPENDENCY_ORDER §4 U1 row): `my_util/*` (all math: Mesh2D 1753 LOC, DQuaternion
545, IntBox 540, GeometryUtil 384, AARotation 214, QuadTree 193, Plane 146, IntMatrix3 108…),
`MyTaskList` (307), `Signal*`, loggers, `Animated`+`Rendered*` (479), `GuiHelper` (137),
`DimIntIdMap` (160), `MiscGlobals`, `Helper` (1501). LOC from q-misc-util.md per-class entries.
Translation per `migration/api-map/` q_misc_util rows (GuiHelper touches R13e GUI moves — port per
slice map).
- **Carve-in:** `qouteall/q_misc_util/my_util/**` files verified standalone by import-grep at stage
  time (DEPENDENCY_ORDER §2.1: pure-math subset has zero imm_ptl imports). `Animated`-family files
  that import beyond the subset stay held.
- **Held:** `Helper` (the ONE edge out: `Helper`→`McHelper.newResourceLocation`, Helper.java:29,509
  — zero-deviation forbids removing the import, so Helper compiles only at S11), `DimIntIdMap`
  consumers, `MiscNetworking` (owned by U5 anyway, network.md).
- **Deliverable (hard constraint 8):** carry `Mesh2DTest` + `HelperTest` from IP `src/test`
  (COVERAGE INFO-2) into `common/src/test/java/qouteall/...`; Mesh2DTest ACTIVE now; HelperTest
  lands now but held (activates S11 with Helper). Author additional DQuaternion/Plane/IntBox
  invariant tests (rotation round-trips, plane-side consistency, box unions) — the geometry-sign
  regression net (DEPENDENCY_ORDER §4.1 milestone 1).

**(b) Forward-ref debt:** exactly one — `Helper`→`McHelper` (DEPENDENCY_ORDER U1 row: "the ONLY
edge out; everything else in U1 compiles standalone"). Probe must show only this.
**(c) Commits:** 1) my_util math + carve-in + tests; 2) remaining U1 files (held); 3) port-note
`S01-qmiscutil.md` with the diff-review record + Mesh2D/DQuaternion derivation notes.
**(d) runClient test:** nothing user-visible; BASELINE-SANITY. Additionally run
`.\gradlew.bat :common:test` — all math tests green is the stage's real payload.
**(e) Regression items:** sanity subset (1,2,3,8,9,12).
**(f) Rollback/flag:** no flag; rollback = revert; carved-in classes are inert (no runtime
references from mod code).

### S2 — Ducks, roots, platform facade (U2) — effort M

**(a) Contents** (DEPENDENCY_ORDER §4 U2 row): ALL 36 duck interfaces + q_misc_util ducks +
cross-slice accessor interfaces; `IPGlobal`, `IPCGlobal`, `MiscHelper` (118), `mc_utils`
(`ServerTaskList`, `MyNbtTextFormatter` — note R12's `SnbtPrinterTagVisitor` re-derivation),
`IPConfig` (215) + `O_O` (254), `IPFeatureControl` (peripheral, 1 file — cycle 9),
compat invoker BASES (`GravityChangerInterface`, `SodiumInterface`, `IrisInterface`,
`IPPortingLibCompat` — compile-mandatory per platform-compat-peripheral.md:245),
`IPPerServerInfo`, `DimensionIntId` (129) + `DimensionIdRecord`, `IPMixinPlugin` (its role is
absorbed by the existing `SeamlessMixinConfigPlugin` per D3 — port the class, wire the gate through
the mod's plugin; record in port-note).
- **Carve-in:** duck interfaces verified vanilla-only by import-grep (DEPENDENCY_ORDER §2.9:
  "compile against vanilla only … kill dozens of forward edges"). Grow the mod's AW/AT lists as
  ducks demand (current-mod-core §10: "AW/AT lists grow substantially").
- **Held:** `O_O`, `IPConfig`, `IPPerServerInfo`, `DimensionIntId`, invoker bases that import IP
  classes (verify each by grep; carve in any that are genuinely vanilla-only).
- **DimLib note:** `DimensionIntId` imports DimLib's `DimensionAPI` events — port against the
  static-dimension EVENT-WIRING stub decided by DEPENDENCY_ORDER §2.1 (R13g/INFO-4); dimensions
  themselves are vanilla. Recorded in the forced-deviation register (F11).

**(b) Forward-ref debt** (DEPENDENCY_ORDER U2 row): `O_O`→{`Portal` U4, `ImmPtlClientChunkMap` U7,
`ImmPtlNetworkConfig` U5, `PortalGenInfo` U9/U11}; `IPConfig`→`BlockPortalShape` (U4 co-port);
`IPPerServerInfo`→{`ServerTeleportationManager` U6, `CustomPortalGenManager` U9/U11,
`PortalWandInteraction` U11}. Probe must show only these.
**(c) Commits:** 1) ducks + AW/AT (carved in); 2) roots/facade files (held); 3) port-note.
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset.
**(f) Rollback/flag:** no flag; revert-only; ducks are inert interfaces.

### S3 — MC helpers (U3) — effort M

**(a) Contents** (DEPENDENCY_ORDER §4 U3 row): `McHelper` (~965 LOC of dense vanilla-API helpers —
world-loader-root.md §2.2), `CHelper` (~193), `IPMcHelper` (~332), `ScaleUtils`, mc_util
entity-traversal mixins (`IELevelEntityGetterAdapter` et al. — mixin classes land WITH the unit per
DEPENDENCY_ORDER §2.10 but stay unregistered until S11). Translation hazards owned here: R13d
height-bound inclusivity (`getMaxYExclusive` wrappers get the +1, call sites untouched), R12's
`EntitySection`/`EntitySectionStorage` traversal re-derivation from 26.2 source.
**(b) Forward-ref debt:** `Portal` (U4), `GlobalPortalStorage` (U5), `CrossPortalEntityRenderer`
(U10) — DEPENDENCY_ORDER U3 row.
**(c) Commits:** 1) helpers (held); 2) traversal mixins (held); 3) port-note with the R13d wrapper
derivation.
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset.
**(f) Rollback/flag:** no flag; revert-only.

### S4 — Portal core + shapes + animation (U4) — effort XL

**(a) Contents** (DEPENDENCY_ORDER §4 U4 row): `Portal` family (`Mirror`, `BreakableMirror`,
`EndPortalEntity`), `PortalState`, `PortalExtension`, `PortalManipulation`, `PortalUtils`,
`shape/*` (incl. `RectangularPortalShape`, `BoxPortalShape`, `SpecialFlatPortalShape`), `util/*`,
the whole `animation/` package (14 files, 2,639 LOC — portal-animation.md; incl. `StableClientTimer`,
`ClientPortalAnimationManagement`), `PortalPlaceholderBlock`, `LoadingIndicatorEntity`,
`PortalRenderInfo`, **co-ports**: `BlockPortalShape` (~513, cycle 14) and
`CollisionHelper`+`PortalCollisionHandler`+`PortalCollisionEntry` (cycle 15 — common-side compile
edge from shapes; they need only U1–U4 material — DEPENDENCY_ORDER §3.15).
- **R11 decisions recorded here** (constraint 4, owning stage): (i) `EntityType.Builder.build(ResourceKey)`
  id-at-build restructure of `createPortalEntityType` (portal-core C10/hazard 4); (ii)
  `EntitySpawnReason` per call site — LOAD for deserialize, DIMENSION_TRAVEL for the recreate path
  (API_RISKS R11); (iii) tracking-range conversion `trackRangeBlocks(96)` → `clientTrackingRange(6)`
  CHUNKS, verified against Fabric API's conversion before hardcoding (current-mod-core §10 mapping +
  §11.6); (iv) the `makeBoundingBox(Vec3)` inversion + eager `setBoundingBox` push on EVERY
  geometry-field change (portal-core G1/C2/hazard 1 — a missed push is a silent stale-bb bug); (v)
  NBT via `TagValueOutput/TagValueInput` preserving the CompoundTag wire shape and the
  rotation double-write/float-read quirk (portal-core C1/C4).
- **Sign-derivation notes** (D4.4): every transform in `transformPoint/transformLocalVec/
  inverseTransform*`, shape windings, `UnilateralPortalState` axes — re-derived, not trusted.
**(b) Forward-ref debt** (U4 row): `ImmPtlNetworking`+`PortalAPI` (U5), `ServerTeleportationManager`
(U6), `PortalRenderer` (U10), `QueryManager`/`RenderStates`/`WorldRenderInfo`/`ViewAreaRenderer`/
`FrustumCuller` (U9/U10), `PortalCommand.raytracePortals` (U11).
**(c) Commits:** 1) portal root + state + extension/manipulation; 2) shapes + co-ported collision
pair + BlockPortalShape; 3) animation package; 4) placeholder block + loading indicator +
PortalRenderInfo; 5) port-note (R11 decision record + sign notes).
**(d) runClient test:** nothing user-visible; BASELINE-SANITY. `:common:test` still green.
**(e) Regression items:** sanity subset.
**(f) Rollback/flag:** no flag; revert-only.

### S5 — Network, global portals, API (U5) — effort L

**(a) Contents** (DEPENDENCY_ORDER §4 U5 row): `MiscNetworking` (149), RPC
(`ImplRemoteProcedureCall` 476 + `McRemoteProcedureCall` 159), `PacketRedirection` (290) +
`PacketRedirectionClient` (116), `ImmPtlNetworking` (269), `ImmPtlNetworkConfig` (318),
**co-ports**: `global_portals/*` (GlobalPortalStorage 396 + types ~700, cycle 10) and `api/`
(`PortalAPI`, `ImmPtlEntityExtension`, cycle 11). LOC per network.md/portal-generation.md.
- **R7 implemented in the order-faithful §A shape** (API_RISKS R7): netty pass schedules the outer
  `ClientboundCustomPayloadPacket` via `packetProcessor().scheduleIfPossible(...)` + cancel;
  game-thread re-invocation handles inline; `wrapRunnable`/`scheduleExecutables` narrowed. The
  redirection wire format ports 1:1 (`GameProtocols.CLIENTBOUND_TEMPLATE.bind` intact). Every
  packet-handler mixin written this stage carries the isSameThread guard (briefing §6).
- **R1 design round (seaLevel protocol) — BEFORE cutover per constraint 4:** extend the dim-sync
  channel (`DimIdSyncPacket`, network.md §5 login-order constraint) to carry per-dimension
  `seaLevel` for not-yet-visited dimensions (API_RISKS R1: the `ClientLevel` ctor's trailing
  `int seaLevel` has no other source). Design doc: `migration/port-notes/S05-seaLevel-protocol.md`;
  implementation lands with ClientWorldLoader at S8. This is a forced protocol EXTENSION (register F8).
- **R11 DataFixTypes decision — owning stage:** `GlobalPortalStorage`'s `SavedDataType` requires a
  non-null `DataFixTypes` and the failure mode is SILENT DATA LOSS (portal-generation G1). Decide
  the constant here, write a load-failure assertion into the port (fail loud, never swallow), record
  in the port-note.
**(b) Forward-ref debt** (U5 row): `ServerTeleportationManager` (U6), `ImmPtlChunkTracking`/
`ChunkLoader` (U7).
**(c) Commits:** 1) q_misc_util networking + RPC; 2) PacketRedirection pair + ImmPtlNetworking/
NetworkConfig; 3) global_portals + api co-ports; 4) design docs + port-note.
**(d) runClient test:** nothing user-visible; BASELINE-SANITY (wire format inert until senders run —
DEPENDENCY_ORDER U5 row).
**(e) Regression items:** sanity subset.
**(f) Rollback/flag:** no flag; revert-only.

### S6 — Teleportation + collision (U6) — effort L

**(a) Contents** (DEPENDENCY_ORDER §4 U6 row): `TeleportationUtil`, `CrossPortalSound`,
`ClientTeleportationManager`, `ServerTeleportationManager` (collision pair already co-ported at S4
per cycle 15 — REVIEWED alongside this unit per the U6 row note).
- **R2 design round — BEFORE cutover per constraint 4:** reconcile the frame-phase anchor —
  portal-animation.md #14 prescribes `Minecraft.renderFrame(Z)V` before `gameRenderer.update(...)`
  (panorama path does not fire, matching IP 1.21.3); current-mod-render §2.2 endorses the existing
  `GameRendererFrameCrossingMixin` at `GameRenderer.update` HEAD. Both precede camera update
  (API_RISKS R2: "pick one and document the panorama behavior"). Decision recorded in
  `migration/port-notes/S06-frame-anchor.md`; the mixin retarget (if any) lands with client mixins
  at S10. Also settle A4's staged-upload-flush ordering question in the same note (current-mod-render
  §4 A4: which frame position is outside the framegraph AND before extract).
- **R8 design round — owning stage:** the position-packet dimension stamp. Pick the codec-wrap idiom
  (`@ModifyExpressionValue` on the `StreamCodec.composite` call appending
  `writeResourceKey/readResourceKey`) vs a paired ImmPtl packet (API_RISKS R8); server+client halves
  change together; implementation lands with the position_sync mixins at S8.
- **R12 re-derivation** (owning stage): every `@IPVanillaCopy` in the collision/teleport path is
  re-derived line-by-line from 26.2 source, never patched from 1.21.3 copies (API_RISKS R12):
  `Entity.collide`/`collideBoundingBox`/`collideWithShapes` (dynamic `axisStepOrder`), the
  movement-path-based `checkInsideBlocks` portal clip (design needed — the bb-redirect has no
  anchor), the anticheat `isEntityCollidingWithAnythingNew` retarget, interpolation kills via
  `InterpolationHandler` + `snapTo` + `getPositionCodec().setBase` (the movement-dedup interaction —
  memory `post-crossing-stutter-entity-move-flood`), the `ServerPlayer.removeVehicle()` retarget for
  the riding bypass.
- **R13a** (owning stage): ender-pearl mixin intercepts/replaces vanilla's now-native cross-dim
  branch rather than adding a case; `teleport(TeleportTransition)` rename. **R13b**: audit each IP
  cross-dim patch for obsolescence-by-vanilla (`EntityReference`, `getEntityInAnyDimension`) before
  porting — drop-candidates documented, not silently dropped.
**(b) Forward-ref debt** (U6 row): `ImmPtlChunkTracking` (U7), `TransformationManager`+
`MyGameRenderer.vanillaTerrainSetupOverride`+`RenderStates`/`FogRendererContext` (U9/U10 client paths).
**(c) Commits:** 1) TeleportationUtil + CrossPortalSound; 2) the two managers; 3) design docs +
port-note (incl. exit-posture sign derivations — the motion-keyed-exit lesson, memory
`backward-crossing-motion-keyed-exit`, maps to IP recomputing posture server-side from the eye
segment, current-mod-core §3 `ClientPortalCrossingPayload` row).
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset.
**(f) Rollback/flag:** no flag; revert-only.

### S7 — Chunk loading + entity sync (U7) — effort L

**(a) Contents** (DEPENDENCY_ORDER §4 U7 row): `ImmPtlChunkTracking` (~669), `ChunkVisibility`
(~232), `ImmPtlChunkTickets` (~337), `PlayerChunkLoading` (~243), `EntitySync` (~79),
`WorldInfoSender` (~100), `DimensionalChunkPos`, `PerformanceLevel`, `ServerPerformanceMonitor` +
`ClientPerformanceMonitor`, `ImmPtlClientChunkMap` — **based on the repo's already-ported
`SeamlessClientChunkMap`** (PORT-FORWARD survivor, current-mod-core §5: "It already IS the IP
class … extended with the mod's 26.2 delta-tracking surface"), installed via the `ClientLevel`
CONSTRUCTOR hook covering EVERY client world including the main one (current-mod-core §5:
the faithful install point is `MixinClientLevel.onConstructed`, NOT the secondary-world factory).
- **R10 decisions — owning stage:** (i) `TicketType` registration through the KEEP'd
  `TicketTypeInvoker` at registry phase, not first-use static init (API_RISKS R10); (ii) the flag
  bits decision FLAG_LOADING vs FLAG_SIMULATION balancing the piglin-flood lesson vs IP's simulation
  semantics (chunk-loading cross-cut 3; memory `portal-view-completeness-findings` liveness gap) —
  fidelity default: IP's semantics, with the mob-spawn implication written down and tested at S13;
  (iii) the `PlayerTicketTracker` takeover's new uncovered path (`DistanceManager.addPlayer` direct
  `PLAYER_SIMULATION` ticket) covered explicitly (mixin-common §2 warning).
- **R13j FORCED DEVIATION recorded** (register F1): `WorldInfoSender` shrinks to weather-only —
  26.2's clock-map `ClientboundSetTimePacket` has no per-dim daylight boolean. The cross-dimension
  weather guard stays (rain-flip broadcast still un-dimensioned).
- chunk_sync mixin classes land here (owned at full depth by chunk-loading.md; DEPENDENCY_ORDER
  §2.10 mixins-with-subsystem), unregistered until S11.
**(b) Forward-ref debt** (U7 row): client half touches `ClientWorldLoader` per-dim renderers —
runtime-only edge to U8; probe should be near-clean apart from documented refs.
**(c) Commits:** 1) tracking + visibility + tickets + loading; 2) EntitySync + WorldInfoSender +
monitors; 3) ImmPtlClientChunkMap fusion with SeamlessClientChunkMap (the PORT-FORWARD carriage
commit — survivor explicitly re-homed per constraint 5); 4) port-note (R10 record + F1).
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset.
**(f) Rollback/flag:** no flag; revert-only.

### S8 — ClientWorldLoader + common mixins (U8) — effort XL

**(a) Contents** (DEPENDENCY_ORDER §4 U8 row): `ClientWorldLoader` (~634 — the last core-root file,
scheduled here because of cycle 7's render-context refs), `IPModMain`/`IPModMainClient` init
sequences (exactly DEPENDENCY_ORDER §4.2 order: `MiscUtilModEntry` → `IPModMain.init` chain →
`IPModMainClient` chain), ALL 74 common mixins (chunk/entity/position sync, collision, interaction
taps — mixin-common.md sections 2.1–2.13), unregistered until S11.
- **R1 implementation:** the seaLevel protocol from S5 lands in `createSecondaryClientWorld`;
  the 26.2 render-split plumbing is re-derived INTO the ported `ClientWorldLoader` from the mod's
  proven `PortalContextSwitch` mechanics (the "single biggest asset", API_RISKS R1): per-dim
  renderer+extractor+state choreography, `@Mutable` accessors, extractor-identity rules (memory
  `nether-block-freeze-orphaned-extractor`), TWO reload-listener registrations (extractor +
  cloudRenderer — R1's silent-miss item), the remote-tick placement design (R1's residue: the
  `LevelRenderer.tick()` deletion → tick the remote ClientLevel + extraction; decision in port-note).
- **R8 implementation:** position_sync mixins with the S6-designed stamp idiom, both halves together.
- **R12 vanilla-copy re-derivations** for this unit's mixins: `setPosRaw` (new waypoint updates),
  `ServerGamePacketListenerImpl.teleport` re-copy, `scheduleExecutables`,
  `ChunkMap.onChunkReadyToSend` (must preserve the NEW `ServerChunkCache.onChunkReadyToSend`
  broadcast-queue call), `doProcessUseItemOn` (API_RISKS R12 list).
- Login-order constraint wired: `DimIdSyncPacket` mid-`placeNewPlayer` BEFORE the difficulty packet;
  global-portal sync at `onPlayerLoggedIn` AFTER it (DEPENDENCY_ORDER §4.2).
**(b) Forward-ref debt** (U8 row): `DimensionRenderHelper`/`PortalRendering` (U9). Probe must show
only render-context refs.
**(c) Commits:** 1) ClientWorldLoader + init sequences; 2) chunk/entity/position-sync mixins;
3) collision/interaction mixins; 4) port-note (R1 impl notes + R8 record + re-derivation evidence).
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset.
**(f) Rollback/flag:** no flag (mixins unregistered); revert-only.

### S9 — Render context + THE CUTOVER SPEC (U9) — effort XL

**(a) Contents** (DEPENDENCY_ORDER §4 U9 row): `context_management/*` (`RenderStates`,
`WorldRenderInfo`, `PortalRendering`, `FogRendererContext`, `StaticFieldsSwappingManager`,
`DimensionRenderHelper`, `CloudContext`), `MyGameRenderer` (335), `MyRenderHelper` (621),
`FrontClipping`, `ShaderCodeTransformation`, `TransformationManager` (322), `GlQueryObject`/
`QueryManager`, `ImmPtlViewArea` (490), `VisibleSectionDiscovery` (199), `FrustumCuller` (402),
`ForceMainThreadRebuild`, `GLResourceCache`, `CrossPortalEntityRenderer` (428 — compiles here;
its 26.2 DELIVERY mechanism trails, see S14 and Appendix A.1), `GuiPortalRendering` (125),
`ViewAreaRenderer` (224). LOC per render-core.md. This is the 26.2 rewrite hotspot: the IP classes
re-express onto the mod's proven mechanisms (API_RISKS R6 "every piece has a cited pattern"):
`PortalRenderTypes` drawMesh/pipelines, `ShaderManagerCompilationCacheMixin` GLSL injection,
`GlCommandEncoderClipMixin` per-draw clip uniform, `PortalRenderBuffersPool.endFramePooled()`.
- **PORT-FORWARD carriage (constraint 5):** `FrontClipping` (with the §1.6 anti-"fix" guard),
  `DimensionRenderHelper` (already the 26.2 Lightmap shape, current-mod-render §1.8),
  `VisibleSectionDiscovery` (+A3 comparative read of `ForceMainThreadRebuild` — deliverable),
  `ShaderCodeTransformation` (keep the mod's 26.2 wiring), `PortalInnerCull`→`FrustumCuller`
  (re-source corners via `getRectPortalFourVerticesCounterClockwise(getThisSideState())` +
  `transformPoint`, current-mod-render §1.9), `PortalRenderBuffersPool`→`MyGameRenderer`
  acquire/return pattern, `lateUpdateLight` into `MyRenderHelper` (already ported, commit 3a2c14e).
  Each carriage is its own commit line so the survivor→shell mapping is auditable.
- **CUTOVER_SPEC.md authored at stage entry** — the atomic-driver-core spec demanded by the
  API_RISKS verdict, containing (constraint 4: R4 + R5 INSIDE the cutover spec):
  - **R4 decision — fidelity default: rebuild `ImmPtlViewArea` on 26.2** (subclass `ViewArea` —
    public ctor/overridable `repositionCamera`/`getRenderSectionAt` survive — backed by a mod-owned
    unbounded store, redirect retargeted to `invalidateCompiledGeometry`'s `new ViewArea`), NOT the
    pinned-bounded deviation; this also retires the documented latent multi-portal >71-chunk
    collision bug (API_RISKS R4; memory `walking-limbo-seed-overclaim` LATENT). The
    `earlyRemoteUpload` per-dispatcher pump necessity is marked needs-runtime-verification
    (render-core G26) with its S11/S13 check named.
  - **R5 sign-flip checklist**: every depth constant/comparison in IP's stencil choreography
    enumerated with its reversed-Z flip derived from 26.2 source (clear 0.0 = far,
    `GREATER_THAN_OR_EQUAL` default; `clearDepthOfThePortalViewArea`/`restoreDepthOfPortalViewArea`
    constants) + the Vulkan-gap degrade path for `PortalRenderInfo`'s occlusion-query consumer
    (API_RISKS R5). Executed at S10.
  - **R9 fog/environment design**: per-dim `FogRenderer`/`FogData` buffer ownership (the single
    WORLD ring-buffer slot must not be written mid-frame for the dest), `EnvironmentAttributeProbe` +
    `FogEnvironment` isolation, lightmap already solved by `DimensionRenderHelper` (API_RISKS R9).
  - **R2/A4 anchor** restated from S6's decision; extract-vs-render phase assignment for every IP
    handler that mutated mid-render state (mixin-client cross-cut 3, "the single largest semantic
    change in the slice").
  - The extract()/compileSections pairing rule and SOG delta feed as spec-level invariants
    (memories `ow-holes-consumed-compile-queue`, `distant-chunk-vanish-sog-desync`).
**(b) Forward-ref debt** (U9 row): `PortalRenderer` family (U10) for a few call sites.
**(c) Commits:** 1) CUTOVER_SPEC.md; 2) context_management; 3) MyGameRenderer + MyRenderHelper +
TransformationManager; 4) view-area/discovery/culler + ImmPtlViewArea; 5) CrossPortalEntityRenderer +
GuiPortalRendering + ViewAreaRenderer; 6) port-note (sign notes, A3 read result, carriage map).
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset.
**(f) Rollback/flag:** no flag; revert-only.

### S10 — Renderers + client mixins (U10) — effort XL

**(a) Contents** (DEPENDENCY_ORDER §4 U10 row): `renderer/*` — abstract `PortalRenderer`,
`RendererUsingStencil`, `RendererUsingFrameBuffer` + `SecondaryFrameBuffer` + `RendererDummy` +
`RendererDebug` + renderMode config (D5/A1: ported, per zero-deviation), the Iris-compat renderer
SHELLS (cycle 12 — compile-mandatory even with no Iris on 26.2, DEPENDENCY_ORDER §2.7), and all
~62 client mixins (multiworld half + render half, mixin-client.md), unregistered until S11.
- **R5 executed** per the CUTOVER_SPEC checklist: transplant the mod's runtime-proven stencil
  substrate consumption (`GlBackendMixin`/`GlConstMixin`/`RenderTargetMixin`/`GlStateManagerMixin` +
  `StencilState` — KEEP rows, current-mod-render §3.1) into `RendererUsingStencil`; every flipped
  constant carries its derivation note.
- **R13c:** every synthetic-lambda anchor re-derived from the compiled 26.2 jar, not the decompile
  (the pass lambdas are pre-mapped: addMainPass :391, clear :197, weather :474, sky :329).
- **R13i:** the `useShaderTransparency` override re-anchored onto
  `GameRenderState.useShaderTransparency()` — on the checklist so it is not silently dropped.
- **R13k:** camera pull-model gates — `initialized` flag via the existing `CameraInvokerMixin`;
  view-rotation post-processing of `cameraState.viewRotationMatrix` after extract (never wrap the
  dirty-flag-cached `getViewRotationMatrix`).
- **R2 anchor retarget** (if S6 chose `renderFrame`) lands here with the client mixins; A2 bob
  scaling trio replaces `MainProjectionBobMixin` (current-mod-render §2.1).
**(b) Forward-ref debt** (U10 row): `BlockManipulationClient` + `PortalCommand`/argument types —
the U11 closure files ONLY. Probe at end of S10 must show nothing else: this is the last
pre-closure checkpoint.
**(c) Commits:** 1) abstract PortalRenderer + stencil renderer; 2) FBO/dummy/debug renderers +
renderMode; 3) Iris shells; 4) client mixins in 2–3 grouped commits; 5) port-note (R5 executed
checklist + R13c/i/k evidence).
**(d) runClient test:** nothing user-visible; BASELINE-SANITY.
**(e) Regression items:** sanity subset.
**(f) Rollback/flag:** no flag; revert-only.

### S11 — SCC closure: first green ported build + first flag-ON run (U11) — effort XL

**(a) Contents** (DEPENDENCY_ORDER §4 U11 row): `commands/*` (`PortalCommand`, `ClientDebugCommand`,
argument types), `block_manipulation/*`, `miscellaneous/` (GcMonitor, initial screen), `debug/`,
the two peripheral closure files `PortalWandInteraction`+`CommandStickItem` (cycle 9), generation
shells `PortalGenInfo`+`CustomPortalGenManager` head (cycles 8/14). If the probe reveals the
closure needs additional small generation files (e.g. `CustomPortalGeneration` imports from the
manager), pull EXACTLY those files forward from U12 — the probe governs; scope creep recorded in
the port-note. Then:
1. **Flip `ip_scc_closed=true` in `gradle.properties`** (committed). The whole `qouteall` tree
   compiles in `:common` and `:fabric`. Fix the translation-slip tail against the slice maps until
   green — never by simplifying (briefing directive). Budget expectation: this is the stage where
   residual API-translation debt surfaces; the per-stage probes exist precisely so this tail is
   short.
2. **Register the IP mixin configs** (from S0 skeletons, now populated), ALL gated on
   `entityPortals` via `SeamlessMixinConfigPlugin` (D3).
3. **Wire runtime init behind the flag**, exactly in DEPENDENCY_ORDER §4.2 order (init sequences,
   login order, client tick order, frame order, server tick order — each into the S0-named seams).
4. **Register entity types + placeholder block unconditionally** (D3).
5. `HelperTest` activates; full `:common:test` green.
**(b) Forward-ref debt:** none — this IS the closure ("⬅ FIRST GREEN BUILD", DEPENDENCY_ORDER U11).
**(c) Commits:** 1) closure sources; 2) the flip + compile-fix tail (one or more honest commits);
3) mixin-config registration + flag gating + init wiring; 4) registrations; 5) port-note.
**(d) runClient test — first user-visible new behavior (flag-ON, dedicated test world):**
- Part 1, flag OFF (default): BASELINE-SANITY in the normal world — must be unchanged.
- Part 2, flag ON: set `entityPortals=true` in the mod's properties config; runClient; create a NEW
  dedicated creative test world (never a main world — D3).
  - Run `/portal make_portal` (argument shape per the ported `PortalCommand` brigadier tree — use
    in-game tab completion; DEPENDENCY_ORDER U11 row names this as the U11 exercise) to create a
    portal to another dimension.
  - CORRECT: a see-through window renders the destination world (stencil driver); walking through
    teleports seamlessly — no dim-change screen, no camera snap, sprint/FOV/hand steady; the portal
    is visible from both sides after `completeBiWayBiFacedPortal` (U11 row); cross-portal block
    interaction works (break a block through the window, U11 row).
  - FAILURE SIGNATURES: black/blank window (driver core / R5 signs), dest renders but garbled depth
    (reversed-Z flip missed), teleport rubber-band or double-teleport (R2 anchor / crossing math),
    packet-order corruption — wrong-dimension block updates (R7), crash on world construct
    (R1 seaLevel), portals invisible (tracking-range conversion, R11).
  - Console watch: no per-frame logging on the render thread (checklist 11), no
    `remote_entity_move`-class packet floods (vanilla dedup should hold under redirected tracking).
**(e) Regression items (flag-ON world):** 1, 2, 4, 7 (partial — no per-entity clip yet, R3 trails),
10, 11, 12; item 3/5/6 preliminary (regular-entity path exists; formal verification at S13).
Flag-OFF: full sanity subset.
**(f) Rollback/flag:** `entityPortals=false` restores today's behavior wholesale; revert of the flip
commit restores the held state. Both proven before the stage closes.

### S12 — Portal generation (U12) — effort XL

**(a) Contents** (DEPENDENCY_ORDER §4 U12 row): `NetherPortalGeneration` pipeline (~306),
`NetherPortalMatcher` (~275), `FrameSearching` (~153), `FastBlockAccess` (~189),
`CustomPortalGeneration` (~290) + forms/triggers (~1,600 across forms, portal-generation.md §
per-class LOC) + datapack dynamic registries at server start, `BreakablePortalEntity` (~294) /
`NetherPortalEntity` (~140) / `GeneralBreakablePortal`, `IntrinsicPortalGeneration` (~137) +
ignition/trigger mixins — **including `imm_ptl.peripheral.portal_generation` and the peripheral
ignition mixins** (`MixinAbstractFireBlock_CVB`, `MixinFlintAndSteelItem_CVB`) per the corrected
citation chain (current-mod-core §0.2: "the port must include `imm_ptl.peripheral.portal_generation`,
not just core"). All flag-gated like everything ported.
- R13d height-bound wrappers verified against `FastBlockAccess` sizing and `NetherPortalMatcher`
  staging (silent-corruption surfaces, portal-generation CHANGED 8-9).
- The defaulted-registry `entity_type` lookup semantics decision (getValue → pig fallback matches
  1.21.3, portal-generation note 3) recorded here.
**(b) Forward-ref debt:** none (post-closure; normal green builds; probes retired).
**(c) Commits:** 1) shape/matcher/search substrate; 2) NetherPortalGeneration + intrinsic + ignition
mixins; 3) custom generation + forms + registries; 4) breakable family; 5) port-note.
**(d) runClient test (flag-ON, dedicated test world):**
- Build a standard obsidian frame in the overworld, flint-and-steel it. CORRECT: NO vanilla purple
  blocks form (the structural suppression — current-mod-core §8 EntityMixin row); the
  `LoadingIndicatorEntity` progress display appears; after the async dest search/fabrication a
  seamless entity portal (4-portal cluster / bi-faced) exists; crossing it behaves as S11.
  FAILURE: vanilla portal blocks appear (redirect miss), indefinite loading indicator (async
  pipeline), portal forms but links to wrong coords (R13d/floored scaling — checklist 10).
- Existing-frame linking: build a frame on the nether side near the expected link target first,
  ignite overworld side, confirm it links to the existing frame instead of fabricating.
- Frame-break: break an obsidian block — CORRECT: portal dies (breakable revalidation);
  placeholder blocks clean up.
- Negative-coordinate frame (both coords < 0): link lands exactly (checklist 10).
- Flag OFF: BASELINE-SANITY unchanged.
**(e) Regression items (flag-ON):** adds 8 (portal-view lighting pre-crossing on a real nether
portal — lateUpdateLight carried), 9 (chunk holes/limbo watch in both dims), 10; re-run 1, 2, 12.
**(f) Rollback/flag:** flag OFF = today's game; revert commits.

### S13 — THE CUTOVER FLIP — effort M (testing-heavy)

**(a) Contents:** flip `entityPortals` default to `true` (config + fresh-install default). Old
block-portal system now gated on `!entityPortals` (dormant, still shipped — deletion is S16 ONLY,
constraint 5). Then the verification block:
- **Full 12-point regression checklist** (briefing §4) on the entity-portal system, plus the
  DEPENDENCY_ORDER §4.1 milestone-3 items (nether E2E, breakable lifecycle, global portals via
  `/portal global` variants).
- **Forced-deviation reproductions** (register F2/F3; current-mod-core §11.3/§11.4 prescribe
  reproduce-then-patch): elytra-boost with fireworks through a portal — confirm the phantom-rocket
  hazard reproduces on IP's `restoreFrom` recreate path, then apply the documented attached-firework
  skip as a port-local patch (in the ONE unified path — briefing §6 two-path rule now trivially
  satisfied); shoot an animal and chase it through — confirm hurt-state drop, apply
  `preserveTransientHurtState` onto the recreate branch. Each patch = its own commit with the
  register entry in the message.
- **R-verifications with named checks:** R7 ordering (double-crossing respawn-mislabel scenario:
  two fast crossings, watch for wrong-dim phantom blocks — memory `respawn-mislabel-phantom-blocks`),
  §11.7 ACK-honesty watch (walk long distances post-crossing in both dims hunting limbo bands —
  memory `walking-limbo-seed-overclaim`; if holes reproduce, a reliability layer re-emerges as a
  DOCUMENTED deviation — checkpoint C8), R4's `earlyRemoteUpload` necessity check, R10 flag-bits
  consequence (mob liveness in the portal view vs no piglin flood).
**(b) Forward-ref debt:** none.
**(c) Commits:** 1) default flip; 2) each reproduced-deviation patch separately; 3) regression
record `migration/port-notes/S13-regression.md` (per-item pass evidence).
**(d) runClient test:** the full checklist, scripted:
1. (item 1) Cross a nether portal forward, backward, strafing; watch for motion-side exit, no
   oscillation. 2. (item 2) Sprint-cross repeatedly; FOV/hand/velocity steady. 3. (item 3) Throw
   items + lead animals through; reachable, visible immediately, no frame-lava drift. 4. (item 4)
   Bow-shoot arrows through; continuous flight, full speed, findable. 5. (item 5) Shoot a pig,
   chase it through; still panicking. 6. (item 6) Elytra + firework through; no phantom rocket
   (post-patch). 7. (item 7) Stand straddling; whole-entity render EXCEPT per-entity clip polish
   (R3 trails — S14). 8. (item 8) Fresh world, observe nether portal-view flame/lava lighting
   BEFORE first crossing. 9. (item 9) Long walks both dims after multiple crossings; no holes/limbo;
   break/place remeshes everywhere. 10. (item 10) Large (e.g. 10×10 via command) and tall portals
   validate crossings; negative-coord linking exact. 11. (item 11) Long session; no creeping VRAM,
   no render-thread log lines. 12. (item 12) Relog, `/kick`, rejoin — clean session.
**(e) Regression items:** ALL 12 — this stage IS the checklist.
**(f) Rollback/flag:** `entityPortals=false` returns to block portals instantly (the two-way switch
is the point of keeping the old system until S16).

### S14 — Trailing render periphery + R3 — effort L

**(a) Contents** (API_RISKS verdict stage 3 — "a genuine periphery can trail"):
- **R3 design round + delivery** (the scheduled design work, constraint 4: "R3 design+port trails
  the cutover"): pick between API_RISKS R3's two candidate mechanisms — (a) per-draw clip uniform at
  `GlCommandEncoder.trySetup` RETURN keyed off submit-order metadata (the mod's clip hook already
  lives there) or (b) one-entity `SubmitNodeStorage` + `FeatureRenderDispatcher.renderAllFeatures`
  bracketed by raw-GL clip state. Port the surviving 1:1 pieces regardless: the
  `EntityRenderDispatcher.shouldRender` visibility-gate mixin (signature-identical, render-core S35)
  and the public `extractEntity`+`submit` path that kills the renderEntity duck. Design doc:
  `migration/port-notes/S14-R3-clip-bracketing.md`. The ported `CrossPortalEntityRenderer` (compiled
  since S9) goes LIVE here.
- Runtime verification of the trailing set: `GuiPortalRendering`, `OverlayRendering` (breakable
  overlay via `submitBlockModel` re-expression, render-core G33/G34), Mirror/`BreakableMirror`
  rendering, `RendererUsingFrameBuffer` as `renderMode=compatibility` (A1 — switch modes in config,
  confirm all four), A2 view-bob behavior sign-off, IP view refinements (`CrossPortalViewRendering`).
**(b) Forward-ref debt:** none.
**(c) Commits:** 1) R3 design doc; 2) delivery mechanism + live wiring; 3) periphery verification
fixes; 4) port-note.
**(d) runClient test:** (item 7 completion) stand an animal straddling the portal plane — renders
whole from BOTH sides, threshold clip both directions; punch/reach through the portal; damage flash
visible in the portal view. Mirrors: `/portal make_portal` a Mirror variant, confirm reflection.
renderMode: flip config through normal/compatibility/debug/none — each behaves per IP (compatibility
shows the FBO path, none shows no portal render). View bob: walk near/away from a portal — bob
scales down near, returns away (changed behavior vs today, per D5/A2).
**(e) Regression items:** 7 (now fully), re-run 1, 2, 11.
**(f) Rollback/flag:** revert commits; `entityPortals` still flippable.

### S15 — Peripheral tail (U13) — USER DECISION — effort S–XL

**(a) Contents:** checkpoint C1 decides (default: core-complete = SKIP beyond what already ported).
If features are greenlit: wand items + client wand code (~3,900 LOC), dim stack + GUI (~2,050),
alternate dims (~1,500 — BLOCKED behind R13g dynamic-dimension design + the
`NoiseBasedChunkGenerator` AW/AT + `noNewCaves` re-derivation), compat `On*Present` layers +
gated Sodium/Iris mixins (dead on 26.2 until those mods port — checkpoint C2), ModMenu config GUI
(≙ `IPModMenuConfigEntry`, current-mod-core §10). Note the compile-closure peripheral files
(`PortalWandInteraction`, `CommandStickItem`, `IPFeatureControl`) are ALREADY in (S2/S11) —
mandated regardless (hard constraint 7). Wand overlays re-express via Gizmos/`submitCustomGeometry`
(R6). R13e GUI extract-model re-expression owns the screens.
**(b) Forward-ref debt:** none.
**(c) Commits:** per-feature.
**(d) runClient test:** per-feature (wand: create/drag a custom portal; dim stack: GUI opens from
create-world screen; etc. — scripts written at greenlight time).
**(e) Regression items:** re-run 1, 2 after any wand/interaction feature (they touch crossing paths).
**(f) Rollback/flag:** per-feature revert; features individually gated by `IPFeatureControl`
semantics as in IP.

### S16 — Cleanup + deletion (U14) — effort L

**(a) Contents** (DEPENDENCY_ORDER §4 U14 row; deletion ONLY here per constraint 5):
- Delete per `current-mod-core.md` tally (42 REPLACE-BY + 30 DELETE rows): the `PortalInfo`/
  `PortalLink`/`PortalManager`/`PortalTracker`/`PortalDetector` block machinery,
  `SeamlessServerTeleport`/`SeamlessClientTeleport`/`PortalTeleporter`/`ProjectilePortalHandler`,
  `PortalChunkTracker` ACK ledger + `RedirectedPacketApplier` + `PortalEntityTracker` +
  `RemoteBlockUpdater` + the bespoke `ModPayloads` set (per-payload rows, §3),
  `PortalWorldManager` promote/demote, `HandleRespawnMixin`, the dead/legacy classes.
- Delete per `current-mod-render.md` tally (12 DELETE): `StencilPortalRenderer`→ported renderers,
  `PortalContextSwitch`→`MyGameRenderer`+context (mechanics already transplanted at S8/S9),
  `PortalShapeRenderer`→`ViewAreaRenderer`, `CameraTransitionHandler`, `PortalSlicing`,
  `PortalFrameSuppressor`, the 9 DELETE mixins.
- **PORT-FORWARD survivor audit** (constraint 5): verify each survivor now lives inside its ported
  consumer — stencil substrate mixins (→`RendererUsingStencil`), `PortalRenderTypes`,
  `DimensionRenderHelper`, `SeamlessClientChunkMap` machinery (→`ImmPtlClientChunkMap`),
  `FrontClipping`, `lateUpdateLight` (→`MyRenderHelper`), diagnostics (`CrossingTracer`,
  `RenderSpikeMonitor`, `PerfTimers`, the `rlog` gate — COVERAGE INFO-1: add the
  `SeamlessPortalsConstants` KEEP line), `ServerLevelFireSpreadMixin` re-keyed to
  `ImmPtlChunkTracking.isPlayerWatchingChunkWithinRadius` (current-mod-core §8), the
  "(verify)" rows re-proven on the ported path before final retention (`ChunkPacketGuardMixin`,
  `ClientPacketListenerAddEntityAdoptMixin`, `ClientPacketListenerLocalPlayerFallbackMixin`,
  `FireworkRocketEntityAccessor`, `LivingEntityHurtAccessor`).
- Delete the `entityPortals` flag + `!entityPortals` branches, the holding machinery
  (`ip_scc_closed`, held-paths list, probe), the block-portal mixin registrations.
- Final full 12-point regression + memory update (briefing §6 process).
**(b) Forward-ref debt:** none.
**(c) Commits:** 1) core deletions; 2) render deletions; 3) flag + machinery removal; 4) survivor
audit note + final regression record.
**(d) runClient test:** full 12-point checklist once more (the S13 script), plus: confirm a world
created pre-migration (block-portal era) loads cleanly (old portal blocks are inert/absent per the
placeholder-block story); relog/kick (item 12) after the renderer-identity cleanup verification
(briefing §5: "likely moot once promote/demote dies, verify").
**(e) Regression items:** ALL 12.
**(f) Rollback/flag:** git revert only — which is why S13–S15 must be fully green first.

---

## 4. Forced-deviation register (hard constraint 1)

Every entry is either 26.2-forced (IP's mechanism cannot exist) or a reproduce-then-patch correctness
carry. Nothing else deviates. Each lands as a documented block in its stage's port-note.

| # | Item | Kind | Owning stage | Rationale / citation |
|---|---|---|---|---|
| F1 | `WorldInfoSender` time-half deleted (weather-only survives) | 26.2-forced | S7 | clock-map `ClientboundSetTimePacket`, no per-dim daylight boolean (API_RISKS R13j; chunk-loading #34/#43) |
| F2 | Attached-firework skip in the unified crossing path | reproduce-then-patch | S13 | hazard verifiably persists in IP's `restoreFrom` recreate (current-mod-core §11.3); briefing checklist 6 |
| F3 | `preserveTransientHurtState` on the recreate branch | reproduce-then-patch | S13 | `restoreFrom` drops transient hurt state (current-mod-core §11.4); briefing checklist 5 |
| F4 | Stencil substrate chain (GlBackend/GlConst/RenderTarget/GlStateManager mixins + StencilState) replaces porting-lib stencil enable | 26.2-forced | S10 (consumed) | no stencil at any 26.2 layer (API_RISKS R5); runtime-proven substrate (current-mod-render §3.1) |
| F5 | Reversed-Z sign flips on every IP depth constant/comparison | 26.2-forced | S9 spec / S10 exec | clear 0.0 = far, GEQUAL default (API_RISKS R5); per-constant derivations mandatory |
| F6 | `PortalRenderTypes` pipeline layer + `drawMesh`; all immediate-draw/blit/overlay re-expression | 26.2-forced | S9/S10/S14 | submit→prepare→execute rewrite; no vanilla premultiplied blend constant (API_RISKS R6) |
| F7 | R7 packet re-queue lands in network.md §A shape (packetProcessor scheduleIfPossible), not IP's `minecraft.execute` | 26.2-forced (order-faithful) | S5 | verbatim port would introduce per-frame cross-queue reordering 1.21 did not have (API_RISKS R7) |
| F8 | Dim-sync channel extended with per-dim seaLevel | 26.2-forced protocol extension | S5 design / S8 impl | `ClientLevel` ctor's trailing `int seaLevel` has no other source for unvisited dims (API_RISKS R1) |
| F9 | R8 dimension stamp via codec-wrap (or paired packet) instead of write-method inject | 26.2-forced mechanical | S6 design / S8 impl | position packet is a record with composite codec, no tail slack (API_RISKS R8) |
| F10 | `LevelRenderer.tick()` role re-placed (remote ClientLevel tick + extraction) | 26.2-forced design | S8 | tick deleted; destruction progress moved (API_RISKS R1) |
| F11 | DimLib event wiring replaced by a static-dimension stub of the EVENT WIRING only | dependency-forced | S2 | DimLib has no 26.2 form; dims themselves vanilla (DEPENDENCY_ORDER §2.1; COVERAGE INFO-4; R13g) |
| F12 | Fabric `Event` objects / payload registration / entity-type registration behind loader-neutral seams | multiloader-forced | S0 | IP is Fabric-only; never Fabric types in common (DEPENDENCY_ORDER §4 ground rules; R13h) |
| F13 | `TicketType` registration via `TicketTypeInvoker` at registry phase; flag-bits decision documented | 26.2-forced | S7 | `TicketType.create` gone; private register; FLAG decision per §11.5 (API_RISKS R10) |
| F14 | `trackRangeBlocks(96)` → `clientTrackingRange(6 chunks)` replicating Fabric semantics exactly | 26.2-forced mechanical | S4 | chunks-vs-blocks trap (current-mod-core §10/§11.6) |
| F15 | `SavedDataType` DataFixTypes non-null constant + loud load-failure guard | 26.2-forced | S5 | null → SILENT DATA LOSS on global_portal.dat (API_RISKS R11; portal-generation G1) |
| F16 | Mod diagnostics retained (CrossingTracer, RenderSpikeMonitor, PerfTimers, `rlog` gate) | additive tooling | all | regression tooling for this migration (current-mod-render §1.13-1.15; COVERAGE INFO-1) |
| F17 | R12 vanilla-copies re-derived from 26.2 (collide, checkInsideBlocks clip, anticheat, setPosRaw, teleport, onChunkReadyToSend, doProcessUseItemOn, MyNbtTextFormatter) | 26.2-forced | S6/S8 | "re-derive line-by-line from 26.2, never patch the 1.21.3 copies" (API_RISKS R12) |
| F18 | Vulkan-backend degrade path documented for raw-GL mechanisms (stencil, clip, queries) | 26.2-forced documentation | S9 spec | every raw-GL mechanism silently no-ops under VulkanBackend (API_RISKS R5) |

Anti-deviation guards (things that look like fixes but must NOT be made): FrontClipping
`Vector4f.mul` idiom stays (current-mod-render §1.6); vanilla firework billboard rotation stays
(briefing §6); faithful vanilla behavior everywhere else stays (hard constraint 1).

## 5. User-decision checkpoints (default: core-complete)

| # | Decision | When | Default | Notes |
|---|---|---|---|---|
| C1 | Peripheral features: wand, dim stack, alternate dims | before S15 | SKIP (core-complete) | dim stack + alt dims additionally blocked by R13g (dynamic dimensions UNKNOWN); compile-closure files already ported regardless (constraint 7) |
| C2 | Sodium/Iris compat beyond compile shells | before S15 | shells only | dead on 26.2 until those mods port (constraint 7); if entered, COVERAGE INFO-3 requires a full-depth pass on 27 files first; SodiumBridge reflective vs compile-time decided here (current-mod-core §6) |
| C3 | R4 already defaulted to full `ImmPtlViewArea` rebuild in the cutover spec — user may override to keep the pinned-bounded deviation (documented) | S9 spec review | rebuild (fidelity) | overriding re-accepts the >71-chunk latent bug (API_RISKS R4) |
| C4 | R3 delivery mechanism (clip-uniform vs one-entity storage) | S14 design review | whichever the design round proves | both candidates cited in API_RISKS R3; unproven until designed |
| C5 | A2 view-bob: IP's `viewBobbingReduce` verbatim = visible behavior change (bob returns away from portals) | S10 | port IP verbatim | current-mod-render §2.1/A2 |
| C6 | A5 `HandLightSmoother` (+feed mixin): additive comfort feature with no IP analog | S16 audit | KEEP | zero-deviation reading: bans altering IP behavior, not additive extensions — user may override to DELETE (current-mod-render A5) |
| C7 | Dedicated-server/NeoForge parity timing | post-S16 | post-port backlog | NeoForge module stays KEEP-skeleton (current-mod-core §10) |
| C8 | If chunk holes/limbo reproduce at S13 under IP's no-ACK tracking: accept a documented reliability-layer deviation or hunt vanilla-path root cause first | S13 | hunt root cause first, deviation only with evidence | current-mod-core §11.7 — "must be proven empirically post-port, not assumed away" |

## 6. Risk placement map (R1–R13 → stages)

| Risk | Design | Implementation | Verification |
|---|---|---|---|
| R1 extract→render split + seaLevel | S5 (protocol design) | S8 (ClientWorldLoader + plumbing re-derivation) | S11 flag-ON first window; S13 item 9 |
| R2 frame-phase anchor | S6 (reconciliation note) | S8 wiring + S10 mixin anchor | S11/S13 items 1–2 (seamlessness) |
| R3 per-entity clip bracketing | S14 design round | S14 delivery | S14 item 7 |
| R4 ImmPtlViewArea fidelity | S9 CUTOVER_SPEC (decision inside spec) | S9 class + S10 redirect | S13 item 9; earlyRemoteUpload check |
| R5 reversed-Z/stencil flips | S9 CUTOVER_SPEC checklist | S10 execution | S11 window render; S13 full |
| R6 immediate-draw re-expression | S9 (onto proven mechanisms) | S9/S10; overlays S14 | S11/S14 visuals |
| R7 packet re-queue ordering | S5 (§A shape is pre-specified) | S5 | S13 double-crossing scenario |
| R8 position-packet stamp | S6 design | S8 (both halves together) | S13 items 1–2 |
| R9 fog/lightmap/environment | S9 CUTOVER_SPEC (fog ownership design) | S9 (DimensionRenderHelper carried; fog isolation) | S11/S13 dest visuals |
| R10 ticket system | S7 decisions (flags, registry phase, addPlayer path) | S7 | S13 liveness-vs-flood check |
| R11 persistence/entity-type | S4 (entity NBT/type/tracking-range/SpawnReason) + S5 (DataFixTypes) | S4/S5 | S12 save/reload portals; S13 item 12 |
| R12 collision/inside-blocks re-derivation | S6 | S6/S8 | S13 items 1, 4, 10 |
| R13a pearls/native teleports | S6 | S6 | S13 item 4-adjacent (pearl through portal) |
| R13b UUID-resolution obsolescence audit | S6 | S6/S8 | port-note evidence |
| R13c compiled-jar lambda anchors | — | S10 | mixin-apply logs at S11 |
| R13d height-bound wrappers | S3 | S3/S12 | S12 tall-frame staging |
| R13e GUI extract-model | — | S15 (screens), S11 (F3 text if ported) | per-feature |
| R13f ClientChunkCache delta protocol | — | S7 (SeamlessClientChunkMap carriage) | S13 item 9 |
| R13g dynamic dimensions | C1 gate | S15 if greenlit | per-feature |
| R13h Fabric v6 / multiloader seams | S0 | S0/S5 | build green throughout |
| R13i useShaderTransparency override | — | S10 checklist line | S13 Fabulous-setting test |
| R13j WorldInfoSender | — | S7 (F1) | S13 weather-cross check |
| R13k camera pull-model gates | — | S10 | S11 fluid-fog-in-camera check |

---

## Appendix A — corpus doubts / reconciliations (nothing here deviates silently)

1. **DEPENDENCY_ORDER U9 lists `CrossPortalEntityRenderer` inside the pre-closure render unit,
   while API_RISKS R3 declares its mechanism UNKNOWN-NEEDS-DESIGN and "explicitly allowed to
   trail".** Not a contradiction, but the plan must reconcile: the CLASS ports at S9 (it must
   compile — `IPMcHelper` imports it, DEPENDENCY_ORDER §2.2), while its 26.2 DELIVERY (the
   draw-layer bracketing whose 1.21.3 mixin target is gone) is designed and wired at S14. No stub
   is involved: the class is complete; only the client hook that has no 26.2 target awaits its
   designed replacement.
2. **"First green build at U11" (DEPENDENCY_ORDER headline) vs this plan's every-stage-green
   builds.** Refinement, not contradiction: under D1 the SHIPPING build is green at every stage
   because held source is excluded; DEPENDENCY_ORDER's "expected red" build is confined to the
   opt-in probe (`-Pip_scc_closed=true`), whose red-ness is itself the per-stage verification
   instrument. U11/S11 remains the first green build WITH the ported tree included.
3. **current-mod-render A1's provisional DELETE of the FBO path** is superseded by the
   zero-deviation directive: IP ships `RendererUsingFrameBuffer` as a live config mode, so it ports
   (D5). This follows A1's own deciding question; recorded here because it flips a provisional
   disposition in the corpus.
4. **U11 closure scope may creep**: DEPENDENCY_ORDER puts `PortalGenInfo` + `CustomPortalGenManager`
   "head" in U11; if those files' own imports pull small U12 files into the closure, S11 pulls
   exactly those files forward, probe-governed and recorded. Execution note, not a doubt about the
   order itself.
5. **COVERAGE INFO-1**: `SeamlessPortalsConstants.java` is in no disposition doc; this plan treats
   it as KEEP (the `rlog` gate encodes the render-thread logging rule) and S16's audit adds the
   missing disposition line — as COVERAGE itself recommends.
