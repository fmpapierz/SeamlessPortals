# C2 — real Sodium 0.9.1 / Iris 1.11.2 compat (stage record of record)

**Governing spec = `migration/C2_DESIGN.md` (judge-panel synthesis, commit `2587ee9`). Ground truth:
`migration/C2_IP_COMPAT_DEPTH.md` (phase 1, IP baseline) + `migration/C2_091_MAP.md` (phase 2, javap
verdicts + substrate contract) + `migration/C2_0_CENSUS.md` (the C2-0 field-level census — READ ITS
CORRECTIONS HEADER FIRST; three of its raw claims were refuted by verify lens 1). C2 was USER-DIRECTED
(2026-07-19) to run BEFORE S20 + polish.**

## §1 C2-0 — scaffold + census + probes (LANDED; zero behavior change)

Impl+census: workflow `wf_6d502c4f-7a0` (2 parallel Opus agents). Verify: 2 Fable lenses —
**lens 1 (census/D1 gate) PASS_WITH_CORRECTIONS** (3 CORRECTIONs + 4 NOTEs — the real catches, §4),
**lens 2 (scaffold/gating) PASS** (5 polish NOTEs, all folded). C2-1 is CLEARED to proceed against
the §2 swap list.

### 1.1 What landed

- **`seamlessportals-ip-compat.mixins.json`** (package `qouteall.imm_ptl.core.compat.mixin`,
  defaultRequire=1, required=true) + **NEW `IPCompatMixinPlugin`** — IP's ORDER-SENSITIVE substring
  gate (IrisSodium→both, else Iris→iris, else Sodium→sodium, else FALSE; 1:1 port of IP :27-41,
  Flywheel/Cardinal arms dropped+documented) COMPOSED with `EntityPortalsFlag.isOn()` (gate 2, every
  class). Reflective dual-path mod detection (FabricLoader/NeoForge ModList), sodium+iris cached;
  **embeddium deliberately NOT treated as sodium** (design §6.3). Registered on both loaders;
  NeoForge = benign (gate 2 force-false). THE SUBSTRING FOOTGUN javadoc'd: every compat mixin class
  keeps `Sodium`/`Iris` in its simple name or is silently dropped.
- **Registered mixins (4, all names contain "Sodium")**: the pre-existing `IESodiumWorldRenderer`
  accessor (inert; target field javap-proven) + THREE lever-gated probe mixins (require=0):
  `MixinSodiumProbe_ShaderSources` (P7a — distinct shader ids at the ShaderManager seam),
  `MixinSodiumProbe_GlCommandEncoder` (P7b — program id + `seamlessportals_ClipPlane` location at
  the trySetup seam, 1Hz), `MixinSodiumChunkRenderList_Probe` (P5 — reset/s + frameTransitions/s,
  1Hz). Plus `SodiumCompatProbe` (P1 — one-shot per-dim SWR/RSM identity at
  ClientWorldLoader.createSecondaryClientWorld:789; sodium types only behind the guarded nested
  class) and a one-shot P8 hit-log in the block-era `SodiumFogOverrideMixin`.
- **Lever**: everything behind `-Dseamlessportals.compatProbe=true`
  (`.\gradlew.bat :fabric:runClientSodium -PsodiumRuntime=true -PcompatProbe=true`; the gradle plumb
  requires `=true` exactly). ZERO behavior change without it — verified: lever-off branches are
  static-final-folded; sodium-absent = nothing woven (gate 1); flag-OFF = nothing woven (gate 2).

### 1.2 Census highlights (full record + corrections = `migration/C2_0_CENSUS.md`)

Statically SETTLED probes: **P4** (the setupTerrain `Viewport` flows BY REFERENCE into CullTask —
`protected final Viewport viewport`; submit = the happens-before edge), **P6a** (testSection args =
camera-relative section-CENTER floats; BFS gate chain isWithinFrustum→isBoxVisible(III)→testSection
confirmed at traversal offset 187), **P9** (backend auto-detected; GL on normal machines; detect via
`DrawBackend.BACKEND`), **P10** (three real sync mechanisms: the blocking `consumeCullTaskResults(true)`
future.get() path, `updateChunksImmediately` drain-loop, `renderOutOfGraph` frustum-only fallback —
NOTE the design's `RSM.update` was a PHANTOM method; the driver is `SWR.setupTerrain`), **P12** (the
draw path iterates the SortedRenderLists SNAPSHOT — zero `region.getRenderList()` re-fetches in
DefaultChunkRenderer). P1 static half: SWR is a per-LevelRenderer instance field (`new
SodiumWorldRenderer(mc)` in ctor-inject, NO `mc.levelRenderer == this` guard); the extract path
CACHES `mc.levelRenderer` via checkRenderer(); the submit path bakes `this.renderer`. Runtime halves
of P1/P2/P3/P5/P7/P8 = the baseline round (§5).

## §2 THE FINAL D1 SWAP LIST (verify-lens-1 gate verdict — C2-1 CODES AGAINST THIS)

- **REFERENCE-SWAP on RenderSectionManager** (IP-style symmetric 3-way tmp swap):
  `renderLists` (seed `SortedRenderLists.empty()`), `renderTree` (seed null), `pendingTask` (seed
  null — WITH the §4.1 race mitigation), `taskLists` (seed null; submitDeferredSectionTasks
  null-guards), `frame`, `needsRenderListUpdate`, `cameraChanged`, `cameraStableSince`,
  `cameraPosition`, `needsGraphUpdate` (swap + broadcast `markGraphDirty` to ALL contexts, or accept
  the flagged staleness — live-round watch), `renderDistance` (`@Shadow @Final @Mutable` — still
  final).
- **CONTENT-SWAP (final fields — CANNOT reference-swap)**: `cullResults` (clear+repopulate per
  context; `SectionTree.isValidFor` gives partial cross-context protection), `cameraTimingControl`
  (reset `previousPosition`/`isSyncRendering`; low severity).
- **REFERENCE-SWAP on SodiumWorldRenderer**: `lastCameraPos`, `lastCameraPitch`, `lastCameraYaw`,
  `lastFogParameters`, `cullMatrix`. **Do NOT swap SWR.renderDistance** (differs-from-option →
  full `reload()` per pass).
- **NEVER-SWAP** (verified world/build/GPU/timing): builder, regions, sectionCache, renderSections,
  buildResults, estimators ×3, lastBlockingCollector, task counters ×3, chunkRenderer, level,
  sectionsWithGlobalEntities, occlusionCuller, sortBehavior, sortTriggering, importantTasks,
  asyncCullExecutor, renderableSectionTree, timing longs, statics; SWR: client, level,
  useEntityCulling, useTranslucencySorting, uniformBufferManager, renderSectionManager (the host).
- **Validate on swap-in**: `renderDistance != 0 && == options.getEffectiveRenderDistance()`;
  `renderLists != null`; null renderTree/pendingTask/taskLists legitimate (cold context).

## §4 C2-0 verify record (the catches)

### 4.1 THE NEW HAZARD (lens 1, the load-bearing catch): pendingTask × safe-read-phase race
`QueuedSectionStorage.startSafeReadPhase` is a plain boolean (NO refcount); `endSafeReadPhase`
flushes queued section mutations into the LIVE map. On a SAME-DIM (shared-RSM) portal: the outer
context's in-flight CullTask is swapped out; the portal pass schedules its OWN CullTask
(cameraChanged forces scheduleAsyncWork every portal pass) and its blocking consume →
endSafeReadPhase flushes WHILE the outer's swapped-out task still traverses the SHARED
SectionStorage — the exact race the phase exists to prevent. **C2-1 must pick + name one:**
(a) refcount the phase (tiny QueuedSectionStorage mixin), (b) consume/cancel the outer pendingTask
before swap-in on a shared RSM, (c) suppress async cull for portal contexts on a shared RSM
(renderOutOfGraph-only). Cross-dim per-dim RSMs unaffected.

### 4.2 REFUTED: the "#3 neutralized by swapped frame" hypothesis
The reset trigger is a value-INEQUALITY at collection time — fires under ANY frame arrangement, and
the renderOutOfGraph fallback fires it too. **#3 ships unconditionally for same-dim; P5 =
measurement only.** (The hypothesis originated in C2_DESIGN.md §3.1.4/P5 — this section is the
correction of record; the design text stands as-written otherwise.)

### 4.3 Other corrections/notes (all folded or ledgered)
`isWithinNearbySectionFrustum` → isBoxVisibleLooser (uncovered-path map corrected); P9 shorthand →
`DrawBackend.BACKEND`; SWR.renderDistance reload guard (§2); perf-telemetry watch items (frustum-only
renderOutOfGraph overdraw; prepareFrame telemetry pollution; per-iteration frame advance when armed);
probe polish folded (P8 lever cached static-final; P5 counter renamed `frameTransitions/s` with the
interleave-signal note; gradle plumb `findProperty == 'true'`); require=0 wording corrected (injector
drift cannot gate boot; a target-CLASS rename still fails loud BY DESIGN); P1 round-script caveat
(§5.3).

## §5 THE C2-0 BASELINE ROUND (user live round — the P2 answer)

Launch: `.\gradlew.bat :fabric:runClientSodium -PsodiumRuntime=true -PcompatProbe=true`
(flag-ON; gate OFF = today's committed behavior; probes armed).

1. **Boot + warn**: expect the loud boxed Sodium warning in the log + the RED one-shot chat line on
   world join ("portal views are disabled this session"). Confirm renderMode force (portals show no
   view content).
2. **P2 — THE DECISIVE CHECK**: does the MAIN world render/mesh normally? Walk around; chunks load,
   terrain appears, no blank world. (Blank/holey world → D11: the tracker feed becomes an
   unconditional-when-sodium-present carve-out, C2-1's first deliverable.)
3. **Teleportation**: walk through a nether portal both ways — "teleportation still works" is
   exactly this claim's first live test.
4. **P1 coverage (lens-2 caveat)**: approach/cross a portal so at least ONE secondary dimension gets
   created — then check the log for `[COMPAT PROBE P1]` lines (one per dim; note whether each dim's
   SWR/RSM identity differs).
5. **Probe collection**: grab the log lines tagged `[COMPAT PROBE P5]` (reset/s + frameTransitions/s
   baseline, portals off-screen and on), `[COMPAT PROBE P7a]`/`[P7b]` (shader ids + program/uniform
   pairs — the clipping-transport fork), `[COMPAT PROBE P8]` (fog override reachability flag-ON).
6. Optional A/B: relaunch WITHOUT `-PcompatProbe` — zero probe lines, identical behavior otherwise.

**What the 8-leg suite proved this stage**: compile x3 green; the suite (no sodium) = the plugin
drops every compat entry when sodium is absent; the flag-OFF title-card leg = gate-2 skip. The suite
CANNOT exercise anything sodium-present — this baseline round is that proof.
