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


## §2.5 THE C2-1 IMPLEMENTATION RECORD (LANDED; 4 verify rounds, 2 blockers + 3 corrections caught pre-ship)

Impl: `wf_558ffb7a-60e` (Fable, in the isolated worktree `c2/c2-1-core` — the main tree stayed
frozen for the user's baseline round). Fix rounds: `wf_c01de5b4-152` + `wf_d360c42c-438`.
Verify ledger: **lens A FAIL (2 BLOCKERS) → fixed → lens A′ PASS_WITH_CORRECTIONS + lens B
PASS_WITH_CORRECTIONS → final folds → focused lens PASS** (3 informational notes). Compile
green x3 + 8-leg suite ALL LEGS PASS in the worktree (sodium absent ⇒ gate 1 drops every
compat mixin; the touched light-publish path runs the helper's plain-run fallback — proven
byte-equivalent un-sodium).

### What landed (beyond the §2 swap list)

- **SodiumRenderingContext v2** (widened per §2, per-field census citations) +
  **MixinSodiumRenderSectionManager** (the symmetric 3-way swap; content-swap for the two
  finals; validate BEFORE any mutation; `@Shadow`-with-body `consumeCullTaskResults(boolean)`)
  + **IESodiumCameraTimingControl** accessor + the SWR camera-cache five on extended
  IESodiumWorldRenderer accessors. SWR.renderDistance deliberately NOT swapped.
- **D1 race mitigation (b) — consume-before-EVERY-swap**: at ip_swapContext entry any live
  pendingTask is blocking-consumed; invariant "no CullTask outstanding while its context is
  parked" proven airtight incl. registry invalidation/LRU paths. Model = TREE-PERSISTENCE
  WITH SYNCHRONOUS CONSUMPTION (results persist via the cullResults content-swap; each pass
  pays a bounded blocking consume). Option (a) refcount = the ledgered hardening if live
  rounds show frame cost. Failed-consume repair: pendingTask=null + idempotent
  endSafeReadPhase + one-shot registry-hosted log (mixin-static-initialiser gamble avoided).
- **SodiumContextRegistry** (D1 lifetime): keyed (portal UUID, layer); LRU
  max(16, maxPortalLayer*16); invalidation on world-renderer dispose (NEW 7th facade method
  `onWorldRendererDisposed` — a NAMED ADDITION vs IP's 6-method Invoker, base no-op), RD
  mismatch, dest-dim change, gate flip. Non-portal facade callers (GUI-portal API,
  CrossPortalViewRendering) get IP-original per-pass cold contexts, never registered/armed.
- **BLOCKER-1 fix (gate-INDEPENDENT, protects the un-levered baseline too)**: sodium's
  LevelExtractorMixin resolves `mc.levelRenderer` AT CALL TIME (checkRenderer, javap-proven)
  in setLevel AND the whole dirty-marking family. Repoint brackets (the MyGameRenderer :298
  pattern, finally-restored, presence-gated on the cached SodiumCompat.isSodiumLoaded) at:
  ClientWorldLoader secondary-creation setLevel + disposeWorldRenderer setLevel(null) + the
  NEW `SodiumRendererRepoint.runWithRendererRepointed` helper at
  ImmPtlClientChunkMap.onLightUpdate (the ONE seam outside any repoint — the frame-END
  lateUpdateLight caller; all other callers sit inside withSwitchedWorld which already
  repoints; the packet block-update route likewise — enumerated with per-site thread
  evidence). Defensive null-RSM guard in the swap driver (one-shot-per-dim ERROR + full
  symmetric skip — the honest mid-frame degrade).
- **BLOCKER-2 fix (strict serial)**: `GLOBAL_PASS_SERIAL = max(serial, max(this.frame,
  context.frame)) + 1; this.frame = serial` at swap-in — every swapped-in frame strictly
  exceeds ALL historical lastVisibleFrame stamps (the equal-frame collision class eliminated
  unconditionally, incl. FlawlessFrames-armed multi-increment passes); forward-only preserved.
- **#3 MixinSodiumRenderRegion** (unconditional per §4.2), **MixinSodiumFlawlessFrames** 1:1 +
  cold-context arming (n=1, next-frame latch), **D6** SpriteUtil→api INSTANCE, **D10 interim
  clip bracket** at ShaderChunkRenderer.begin/end (raw GL on the non-cached CLIP_DISTANCE0;
  triple-gated; stale-latch clear; retired at C2-2), **CORRECTION-3** validate escape =
  getPortalRenderDistance's real branches (the session-latched renderedScalingPortal escape
  replaced — that latch NEVER resets, its only reset is commented out upstream).
- **Gate restructure**: per-mod verdicts; sodiumActive = present && (gate || the
  `-Dseamlessportals.experimentalSodiumCompat` lever, gradle `-PsodiumCompatLever=true`) &&
  !irisPresent (iris ALWAYS warn+force until C2-4); one-shot GOLD experimental notice naming
  the clipping gap. Committed default = 6f4560b behavior EXACTLY (two audited exceptions:
  the BLOCKER-1 pure-fix brackets; the debug-command FlawlessFrames arm — see ledger).

### C2-1 ledger (from the verify rounds)

- **Named addition**: the 7th facade method onWorldRendererDisposed (D1 invalidation seam;
  IP's Invoker has 6 — dies/reconsiders at S20 with the facade).
- **Un-levered delta (sanctioned)**: ClientDebugCommand's forceMainThreadRebuildFor now
  reaches sodium's FlawlessFrames when woven (manual debug command only; the design §6.2
  envelope covers it).
- **IP-FAITHFUL crash class (do-not-fix)**: RD=2 + reducedPortalRendering ⇒
  getPortalRenderDistance=0 ⇒ the validate throws — IDENTICAL upstream (same /3, same
  Validate). Live-round watch item.
- **C7 line**: SodiumCompat.isSodiumLoaded counts EMBEDDIUM on NeoForge; IPCompatMixinPlugin
  deliberately does not — when C7 un-defers the client, the repoint brackets must NOT fire
  for embeddium (semantic mismatch between the two presence gates, moot until C7).
- **Flag-OFF + sodium (pre-existing, S20-dies)**: the block-era PortalWorldManager setLevel
  sites + SeamlessClientChunkMap/RemoteBlockUpdater dirty sites are unbracketed — same
  wrong-routing class on the never-supported block-era+sodium combination; enumerated with
  reachability evidence; dies with the S20 block-era deletion.
- **Perf watch**: per-pass blocking consume (mitigation b — option (a) if frame cost);
  renderOutOfGraph frustum-only overdraw; prepareFrame telemetry pollution; per-iteration
  frame advance when armed; a scale-oscillating portal would recreate+arm every frame.
- **Dest-BE 1-pass latency (C2-1d vA-NOTE fold — live watch item)**: under ACTIVE sodium the
  Step-5 dest extract collects visible block entities from the D1-swapped context's renderLists
  (javap LevelExtractorMixin.extractVisibleBlockEntities → SWR.extractBlockEntities), but the
  cull drive runs at the Step-9 slot (needs Step-6 dest FogData) — so BEs ride the PREVIOUS
  pass's renderLists: cold-context first pass shows NO dest BEs, steady-state is a bounded
  1-pass lag (sodium's own intra-pass order is same-pass-fresh). In-code ledger at the
  SecondaryWorldRenderCore Step-5 extract site. Live round: check chests/signs appear in
  cross-dim windows by the second pass.
- **Comment notes (informational, recorded not fixed)**: the GLOBAL_PASS_SERIAL javadoc says
  "above" for a map declared below; the repair comment's "147-175 null/close tail" is
  conservative (actual null@161/close@166 — covers strictly more throw points than claimed);
  the helper fast-path "zero-overhead" is approximate (a volatile-Boolean unbox + isSameThread
  virtual call).

### The C2-1 LIVE-ROUND SCRIPT (lever on) — run AFTER the §5 baseline round

Launch: `.\gradlew.bat :fabric:runClientSodium -PsodiumRuntime=true -PcompatProbe=true -PsodiumCompatLever=true`
Expect the GOLD experimental notice (NOT the red force-off line). Then:
1. Cross-dim portal shows DEST terrain (not outer-world garbage, not permanently blank;
   first-frames blank while chunks build = accepted envelope).
2. THE DECISIVE SAME-DIM CHECK: portal pair near water/glass — outer-world transparent pass
   intact with the portal on screen.
3. Nested portal (layer 2) sane.
4. Entities beside the portal stay visible on portal frames.
5. Walk-through both ways + post-crossing world renders fully (no blank curtain).
6. Dest-dim fog through the aperture (nether-from-overworld).
7. Save-relog; then lever OFF relaunch → red warn+force returns (A/B).
8. A plain runClient regression pass (no sodium).
EXPECTED-AND-ACCEPTED: terrain bleed-through at the portal plane (clipping = C2-2, the
notice says so); portal-pass frame cost unoptimized (culling = C2-3).
FAILURE DISCRIMINATORS (pre-registered): aperture shows YOUR world's terrain → P1 routing;
outer-world translucent corruption same-dim → swap/#3; main-world sections vanish one frame
after a portal pass → pending-task; portal terrain NEVER appears → convergence (P3 fork).


## §3 THE C2-0/C2-1 LIVE ROUNDS + THE C2-1b/c/d FIX CHAIN (2026-07-19, user-run, log-verified)

### 3.1 Round results (the baseline §5 script + the levered §2.5 script, first attempt)

- **ROUND 1 (gate OFF, probes on): main world BLANK** — only sky, block outlines, particles;
  teleport worked. **= P2 ANSWERED**: without the invoker the chunk-tracker feed is dead and
  sodium (which owns ALL terrain when present) meshes nothing. The pre-decided D11
  contingency fired.
- **ROUND 2 (lever ON): main world rendered NORMALLY** (the feed works under the full
  invoker). Creating a portal **CRASHED**: CCE `IgnoringViewArea → ImmPtlViewArea` at
  `SecondaryWorldRenderCore.renderDestWorld:885` (Step 9's vanilla armed-discovery). The GOLD
  experimental notice displayed correctly.
- **PROBE HARVEST (all pre-registered questions answered)**: **P1** = per-dim LevelRenderers
  get their OWN SWR+RSM instances (two nether lines, distinct identities). **P7a** =
  `sodium:blocks/block_layer_opaque` VERTEX+FRAGMENT DO flow through the ShaderManager
  source seam → **M1 is GO for C2-2**. **P7b** = trySetup sees patched (loc 0/1/2) and
  unpatched (loc=-1) programs — candidate-A upload plausible, candidate-B certain. **P5
  baseline** = reset/s ~200-320, frameTransitions/s 32-81, portals off. **P8** = the
  block-era SodiumFogOverrideMixin FIRES flag-ON (activeOverride=false — reachable, inert;
  feeds the S20 pre-deletion gate-audit).

### 3.2 C2-1b (fix round; lenses PASS / PASS_WITH_CORRECTIONS)

- **D11 LANDED**: `SodiumInterface.FeedOnlyOnSodiumPresent` — feed-only invoker installed
  whenever sodium present but not ACTIVE (iris rows included); isSodiumPresent() stays
  false on it (every consumer keeps un-levered behavior — enumerated + walked). Install
  ordering proven safe (mod init precedes any ClientLevel construction — the feed cannot
  miss a chunk).
- **THE CCE MECHANISM (javap-proven)**: sodium's `sodium$replace` @Inject(HEAD, cancellable)
  on `invalidateCompiledGeometry` ci.cancel()s UNCONDITIONALLY and installs
  IgnoringViewArea + IgnoringSectionRenderDispatcher — our S12 ImmPtlViewArea @Redirect
  targets bytecode inside the cancelled body: **sodium wins deterministically for every
  renderer; ImmPtlViewArea never exists under sodium**. Fixes: Step-2/Step-9
  instanceof-null rewrites + the Step-9 vanilla-discovery block sodium-gated (the IP
  ip_allowOverrideTerrainSetup yield precedent) + Step-5 SOG-feed/drain gates +
  tickSecondaryDeltaPump gate + ClientDebugCommand instanceof guards (also fixed a latent
  sodium-absent NPE). Full sweep with per-site dispositions in the C2-1b record.
- **TWO MATERIAL FINDINGS pre-registered by the fix walk** (the dest pass would draw
  NOTHING): sodium's cull hook anchors in the `capturedFrustum == null` branch our dest
  pass never takes; the draw instance is an un-armed @Overwrite dummy. → C2-1c.

### 3.3 C2-1c (the dest drive; lens A PASS_WITH_CORRECTIONS / lens B FAIL→C2-1d)

- **THE DRIVE**: `ip_driveDestTerrainSetup` — sodium's cullTerrain hook body replicated
  1:1 (viewport via ViewportProvider on the dest frustum; FogParameters built with
  sodium's exact capture mapping from the dest FogData; the cull matrix reconstructed
  byte-identically to vanilla `Frustum.calculateFrustum` — the FrustumAccessor mixin class
  is unloadable from mod code, a documented forced choice; smartCull from the dest camera
  state) — called at the Step-9 slot inside the swap bracket. The captured-frustum
  discipline stays (the drive is explicit).
- **THE ARM**: `ip_armDestChunkRenders` — replicates sodium's own getRenderState
  WrapOperation (SodiumChunkSection.sodium$setRendering with the dest draw projection +
  view matrix + camera pos), with ONE deliberate omission (the LevelRendererMixin.matrices
  putfield — jar-proven consumer-free; avoids clobbering the main renderer's stored
  matrices on shared-renderer frames; iris-C2-4 revisit line ledgered). canDraw gains the
  minimal `|| sodiumArmed` bypass (the dummy's maxIndices is -1). drawChunkLayer's
  OPAQUE→SOLID+CUTOUT / TRANSLUCENT slots match the S18 submit order exactly.
- **HAZARD CAUGHT MID-WALK + FIXED**: the UniformBufferManager per-frame latch — dest
  draws latch the manager onto the DEST globals slice; on shared-SWR frames the main
  translucent pass would then skip its own write → `ip_onDestTerrainDrawsFinished` resets
  the latch in the outermost finally (throw-safe, nesting self-heals); correctness also
  rests on the D1 five-swap restoring lastFogParameters (cross-referenced in-code).
- **Cold-context anatomy verified**: first pass = renderOutOfGraph sync fallback (ACTC
  null→sync + the FlawlessFrames n=1 arm); async cull consumed at swap-out; trees persist
  via the cullResults content-swap.

### 3.4 C2-1d (the convergent VRAM-leak fix; focused lens PASS)

**BOTH C2-1c lenses independently found the same leak**: the armed cross-dim dest draws
are the FIRST-ever writes into a secondary SWR's UniformBufferManager, and NOTHING flag-ON
endFrames secondary LevelRenderers (vanilla ends only the installed renderer; the block-era
walk covers the block-era map) → DynamicUniformStorage never rotates → capacity doublings
strand MappableRingBuffers forever — unbounded VRAM leak, the ledgered 2026-07-05
driver-paging freeze class, invisible in a short round. **FIX**:
`ClientWorldLoader.endFrameOnSecondaryLevelRenderers` at the existing S18 render-TAIL
walk (MyGameRenderer.endFramePooled) — presence-gated, WORLD_RENDERER_MAP walk,
identity-skip of the installed renderer (a double rotate would halve the fencing margin);
explicitly NOT per-pass (both lenses' wrong-fix warning: shared-SWR frames would
double-rotate the main storage). The S18 gpu-buffer-leak-endframe discipline, same class.

### 3.5 Verify tally for the C2-1 family (through C2-1d)

**7 rounds** (C2-1 impl 4 + C2-1b 2 + C2-1c 2 + C2-1d 1, counting the C2-1b/c pairs as
their rounds): **3 BLOCKERS** (cross-dim setLevel NPE + main-SWR corruption; frame
lockstep; the endFrame VRAM leak) **+ the CCE + the empty-dest findings + the UBM latch +
~8 corrections** — every one caught before it reached a user session (except the CCE +
blank world, which the ROUND-1/2 script caught by design in one minute each). The
convergent-independent-derivation events (the strict serial; the endFrame leak) are the
highest-confidence signals the process has produced.

### 3.6 THE RE-RUN SCRIPT (supersedes §2.5's; three parts, one sitting)

1. **Feed-only boot** (`-PsodiumRuntime=true -PcompatProbe=true`, NO lever): the main
   world must now MESH NORMALLY with portal views forced off (red warn) — closes the
   feed-only inference (the one unproven row). Teleport both ways.
2. **Levered round** (add `-PsodiumCompatLever=true`): GOLD notice; create a portal —
   NO crash, and the aperture shows REAL DEST TERRAIN (first-frames blank while chunks
   build = accepted; bleed-through at the plane = accepted until C2-2). Then: the
   decisive same-dim pair near water/glass; nested portal; entities beside the portal;
   walk-through both ways + post-crossing; a chest/sign through a FRESH cross-dim portal
   (the dest-BE latency watch: visible by the second pass); save-relog; lever-off A/B.
3. **The S19 leftover**: title screen → Mods → Seamless Portals → config = the real IP
   cloth screen, no raw translation keys.
   WATCH (report if seen): "Resizing Sodium terrain uniforms" log spam (the leak fix's
   regression signal); FPS through portals (the phase-relocation watch); fog correctness
   in the aperture (P8 interplay).

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

## §3.7 THE RE-RUN RESULTS (2026-07-19, user-run, log-confirmed) — C2-1 LIVE-PROVEN

**PART 1 (feed-only): PASS** — rendering good, teleport good, no portal views (correct).
D11 install line + red warn + chat all present in the log. The feed-only inference is CLOSED.
This round also serves as the lever-off A/B row (feed-only IS the lever-off state).

**PART 2 (levered): PASS ACROSS THE BOARD** — rendering good, LIVE PORTAL VIEWS GOOD,
teleport good, **the decisive same-dim water check GOOD**, glass good, recursive portals
good, entities beside portal good, chest good, sign good, save-relog good. Log-confirmed:
GOLD notice; ZERO "Resizing Sodium terrain uniforms" (the C2-1d leak fix clean); zero
degrade one-shots; zero mod exceptions; no crash reports. P5 under the levered session
shows the strict serial live (lastFrame ~117k, monotonic).

**FINDINGS from the round:**
1. **NO ENTITIES visible in the dest dim through the portal** (mobs/players on the other
   side don't render in the aperture; entities BESIDE the portal in the viewer's dim are
   fine). → the active C2 work item (§3.8); diagnose-first.
2. **Sign placed THROUGH a portal plants blank** (the text-edit GUI does not open on
   cross-portal placement) — **USER-ROUTED: POST-S20 POLISH** (cross-portal interaction
   GUI class; joins the polish backlog).
3. **Config screen (S19-E live item: PASSED — the real IP cloth screen works)** with one
   cosmetic: text cut off when the game window is small — **USER-ROUTED: POST-S20 POLISH**
   (cloth-config layout behavior at small widths).

With this round, **C2-1's deliverable is live-proven**: Sodium + portal views + crossing +
same-dim isolation + persistence all work; the accepted artifacts (plane bleed-through
until C2-2; first-frames cold blank) behaved as documented.

## §3.8 C2-1e — DEST-DIM ENTITIES THROUGH PORTALS (diagnose-first; lens PASS)

**THE MECHANISM (evidence-complete BEFORE the fix; BOTH prior hypotheses REFUTED):** the
mod's OWN block-era-vintage `LevelRendererEntityVisibilityMixin` HEAD-cancels
`isSectionCompiledAndVisible` during dest extracts by reading the renderer's viewArea — under
sodium ACTIVE that is `IgnoringViewArea`, whose `getRenderSectionAt` is unconditionally
`aconst_null` → the mixin cancels FALSE for every position → `extractVisibleEntities`'
final conjunct fails → destLRS.entityRenderStates stays empty → no dest entities. REFUTED
with evidence: (a) sodium's own entity cull is ALREADY D5-neutralized during every dest pass
(portalsRenderedThisFrame ≥1 — incremented in onBeginPortalWorldRendering:98, NOT
pushPortalLayer as the prose first said); (b) visibleSections never feeds entity extraction
(only BE extraction — which is why chests/signs DID render).

**THE FIX (one file, +30 lines):** in the mixin's dest-extract leg, when
`SodiumInterface.invoker.isSodiumPresent()` (TRUE only for the ACTIVE invoker), cancel TRUE
instead of consulting the foreign viewArea — IP compat file #2's exact semantics ("the
section visibility information will be wrong… just cancel this optimization") ported to the
one 0.9.1 consumer the D5 retarget does not cover. The entity's own frustum/distance cull +
the cross-portal shouldRenderEntityNow filter stay live; scope deliberately TIGHTER than
IP's (dest extracts only — the main extract keeps sodium's honest culling). Fall-through
into sodium's @Overwrite REJECTED with evidence (SWR.isSectionReady is null-guard-free →
would NPE in the ledgered null-RSM degrade state). Sodium-absent / feed-only / flag-OFF
bit-identical.

**Ledgered:** entities-before-terrain possible during cold first-frames (IP-parity, the
accepted envelope); the same-dim loop-back entity pass fixed by the same line; NeoForge +
sodium keeps the old behavior (fabric-only install site — pre-existing posture, C7 line).
Live check (rides the next round): a mob in the dest dim renders in the aperture;
entities-beside-portal + the same-dim water check re-glanced for no regression.

## §3.9 C2-2 — THE CLIPPING TRANSPORT (D3) LANDED + SODIUM DEFAULT-ON (user decision #1)

Impl wf_8668fe23-07c (Fable); lenses A PASS / B PASS_WITH_CORRECTIONS (1 CORRECTION folded:
the stale-latch heal is now STORE-GATED on both sites — an unconditional glEnable could
desync raw GL from FrontClipping's cache and re-create the undefined class for unpatched
VANILLA programs; + the four-enable-paths javadoc correction incl. restore(Snapshot) = the
qouteall-bridge path; + the VK-warn honest wording).

**LANDED:** (1) the M1 SOURCE PATCH — sibling mixin at the ShaderManager source seam,
branch strictly sodium:blocks/block_layer_* VERTEX (jar enumeration: block_layer_opaque.vsh
is the ONLY sodium GLSL; per-pass variance is defines-only); injects our
seamlessportals_ClipPlane + gl_ClipDistance[0] write, VIEW-space, anchored + formatted
byte-for-byte like the vanilla transformer; idempotence guard + anchor fail-safe (unpatched
→ WARN → the definedness guard). (2) THE UPLOADER — **RE-SITED BY BYTECODE** (the specced
candidate-B seam REFUTED: ShaderChunkRenderer.begin binds NO program — it only compiles;
the real per-pass bind is GLDrawContext.setContext) → MixinSodiumGLDrawContext_ClipUpload
at setContext RETURN, mirroring GlCommandEncoderClipMixin (keep-all when disabled,
per-program location cache). **P7b SETTLED STATICALLY en route**: candidate A (the vanilla
trySetup uploader) provably covers every sodium batch too (GLDrawBatch.draw →
RenderPass.multiDrawIndexed → executeDraws → trySetup) — double-upload same-value benign,
ledgered. (3) the D10 INTERIM BRACKET RETIRED (source-patched shaders now write
gl_ClipDistance) with a surgical residual guard: unpatched-program + armed-plane → suppress
the enable for exactly that pass (store-gated restore). (4) VK/D4: lazy DrawBackend.BACKEND
check (census-correct, never instanceof the device); non-GL → patch skipped + one-shot
honest deviation log. (5) the patched-source dump lever rides -Dseamlessportals.compatProbe.

**SODIUM COMPAT DEFAULT-ON (USER DECISION #1, 2026-07-19)**:
ExperimentalCompatGate.ENABLE_SODIUM_IRIS_COMPAT = true — sodium users get portal views out
of the box; the GOLD notice stays (reworded: clipping newly enabled, report artifacts); the
gate boolean is the one-line ROLLBACK; the JVM lever remains as an ON-override only.
Iris-present unchanged (warn+force until C2-4). Sodium-absent structurally untouched
(presence short-circuits; suite green = the proof, every commit).

Compile x3 + suite ALL LEGS PASS post-folds. LIVE ROUND (the C2-2 script §1): wall-embedded
portal backside must NOT show; nose-against-aperture both sides; slow crouch-cross both
ways (no through-pop); water/translucent portal; same-dim mirror; a MOB in the dest dim
(the C2-1e check); vanilla-clip regression leg (plain runClient, no sodium: stand at the
plane, no new artifacts); NO JVM args needed — this round tests the shipped default.

## §3.10 C2-2 LIVE ROUND (2026-07-19, user-run): ALL FOUR PASS — THE SODIUM TIER IS COMPLETE

1-4 all good: the wall-embedded portal shows NO bleed-through; nose-against-aperture clean
both sides; crouch-cross no through-pop; the dest-dim MOB renders (C2-1e live-proven);
same-dim water/glass still clean; the vanilla regression leg clean. Sodium compat is
DEFAULT-ON and live-proven end-to-end: views + clipping + entities + crossing + persistence.

**USER-ROUTED POLISH (post-S20; IP-INHERITED — the user confirmed ORIGINAL IP behaves
identically, the side-by-side court):** when a portal has solid terrain directly behind it
(on the dest side): (a) you cannot teleport INTO the portal from that side, and (b) looking
at the portal from the terrain side, the terrain visibly clips INTO the portal. Long-standing
in this port AND in upstream IP — an inherited-improvement candidate (the
melee-through-portal class), NOT a port defect. Joins the polish backlog.

## §3.11 C2-3 — THE CULLING PERF CHAIN (D2) LANDED

Impl wf_c6de6175-fc4 (Fable); lenses A + B both PASS_WITH_CORRECTIONS, folds applied.
LANDED: the IESodiumViewport duck (predicate + cave-cull override + the DORMANT D2b origin
field) + the setupTerrain-HEAD producer (ACTIVE-invoker-gated; IP file #6 body 1:1; snapshots
onto the viewport; the legacy SodiumInterface.frustumCuller static written for parity only —
consumer-free, grep-proven) + the testSection @WrapOperation predicate consumer (iris-chains-
safe) + the findVisible @ModifyVariable cave-cull consumer (worker-thread snapshot reads;
submit = the happens-before edge; P4 by-reference settled).

**THE LENS CATCHES (both folded):**
- **Lens B (spec-level)**: sodium's testSection verdict is PADDED (CHUNK_SECTION_PADDED_RADIUS
  9.125 — the 1.125 overhang margin); the design's ±8 reconstruction was TIGHTER than the
  verdict it ANDs onto → could mis-cull overhanging geometry at aperture seams. FIXED:
  ±CHUNK_SECTION_PADDED_RADIUS (margin parity with sodium's own test + IP's effective inputs).
  C2_DESIGN §7.1 records the supersession.
- **Lens A (wording)**: the A/B lever (IPCGlobal.doUseAdvancedFrustumCulling, runtime debug
  commands advanced_frustum_culling_enable/disable) gates the PREDICATE half only; the
  cave-cull override is lever-independent (IP-faithful) and AND-composes = safe-direction-only
  (can only render MORE). Javadoc corrected; FPS A/B attributes to the predicate half.
- Named deviation (documented in-code, lens-confirmed real): IP file #4 forced the MAIN pass's
  occlusion boolean too (killing sodium's main-world cave culling whenever live); our
  null-passthrough keeps sodium's main-pass default — the design's own consumer body.
- Unhooked (ledgered): isBoxVisibleLooser/testSectionExpanded + isBoxVisibleDirect/
  SectionTree-frustum-tested shortcuts (under-cull/perf-only directions); D2b dormant.

**LIVE ROUND (perf-flavored)**: a heavy dest scene through a portal; FPS with the lever
toggled A/B via the client debug commands (expect measurable gain, ZERO visual delta);
grazing-angle + aperture-edge artifact hunt (terrain vanishing at odd angles = the predicate
failure class — the padded-radius fold is its mitigation); box/scale portals expected
unchanged (D2b deferred). Suite cannot exercise any of it (no sodium).

## §3.12 C2-4 — IRIS LANDED (invoker + shaders-OFF parity + honest shaders-ON routing)

Impl wf_48760ec7-62d (Fable); lenses A PASS_WITH_CORRECTIONS / B PASS (1 CORRECTION each,
both folded). LANDED: OnIrisPresent LIVE with the D7 loud resolve assert (spec correction en
route: IP's Helper.noError RETHROWS — upstream's failure mode was an opaque hard crash, not
silence; D7 = graceful loud fallback to warn+force, sodium vetoed too, never silent); the
`!irisPresent` sodium exclusion DROPPED (one gate, both verdicts — the gate name finally
true); D8 routing in switchToCorrectRenderer (shaders-ON → rendererDummy + one-shot GOLD
notice; shaders-OFF → the normal stencil-direct path with the sodium chains underneath; IP's
iris renderer selection preserved in-comment for the C2-5 revival); the B4 pipeline
null/restore bracket live and PROVEN inert-equivalent no-pack (VanillaRenderingPipeline
never-null on the main renderer; secondary fields null; dest passes run no renderLevel);
CrossPortalEntityRenderer's iris-disable live (IP-faithful, kept); D9 asserted (zero iris
mixins registered; the plugin's Iris arms deliberately empty, revival ledgered).

**P11 SETTLED STATICALLY**: with shaders OFF iris routes NOTHING through patchSodium
(constant-pool-proven: patchSodium ← ShaderCreator ← IrisRenderingPipeline only —
VanillaRenderingPipeline never touches it) → the C2-2 sodium clipping keeps working under
iris-installed-shaders-off. The one extra iris/ShaderManager class
(MixinShaderManager_Overrides) is IrisRenderingPipeline-gated = shaders-OFF inert (lens-B
sweep; a shaders-ON re-expression must account for it — C2-5 ledger line).

**THE LENS-A CATCH (folded — the D8 timing sub-fix)**: our per-frame switchToCorrectRenderer
caller is the re-homed AFTER_TRANSLUCENT_TERRAIN driver = MID-renderLevel; IP's was BEFORE
renderLevel. A direct reloadPipelines() on the pack-ON transition frame would destroy the
pipeline iris$endLevelRender still uses at renderLevel TAIL (finalize + hand rendering on
deleted GL objects). FIX: the reload defers to the next frame's PRE_GAME_RENDER_TASK_LIST
one-shot (the frame-pump fires it before gameRenderer.render → before iris$setupPipeline) —
IP's destroy-before-prepare ordering restored exactly; one-frame delay benign.
Also folded (lens B): the stale S19-E-era ExperimentalCompatGate class javadoc rewritten to
the C2-4 truth. LEDGERED: B1's MixinRenderSystem_Clipping consumer is RE-EXPRESSED (26.2
stencil-direct + the C2-2 sodium patch replaced IP's setShader-seam uniform delegation) —
the P11 live leg is its proof; not a missed port.

**LIVE ROUND (needs -PirisRuntime=true + a user-chosen shaderpack in the run's shaderpacks
dir)**: no pack → FULL portal parity (views, crossing, mirror, the C2-2 clipping — the P11
leg); pack ON mid-session → portals switch to pass-through + the one-shot notice, NO crash
at the toggle frame (the D8 timing sub-fix's decisive check), world renders correctly under
shaders; pack OFF → views return; ShadowRenderer sanity (no portal-cull artifacts in
shadows); save-relog. Plus the C2-3b flicker re-check when that fix lands.

## §3.13 C2-4 LIVE ROUND (2026-07-20, user-run): 1-5 ALL GOOD — IRIS SHADERS-OFF PARITY LIVE-PROVEN

No pack → FULL portal views under iris+sodium (incl. the P11 clipping leg) ✓; pack ON
mid-session → pass-through + the one-shot notice, NO crash/corruption at the toggle frame
(the D8 timing sub-fix's decisive check) ✓; play under shaders normal ✓; pack OFF → views
return ✓; save-relog with pack on ✓. With this round the C2-5 INSTALL MATRIX is user-proven:
plain (every suite/live round) / sodium (§3.7/§3.10) / iris-no-pack / iris+pack (this
round). Outstanding: the C2-3b flicker fix (in flight) + its re-check; then the C2-5
close-out decisions.
