# S18 — trailing render periphery + R3 runtime delivery (stage ledger)

**Stage spec: EXECUTION_PLAN §S18. Handoff: migration/S18_HANDOFF.md. This note is the S18 ledger
of record; sections land per increment.**

---

## §1 — S18.1: Mechanism B draw sites + ownRenderBuffers endFrame lifecycle (LANDED)

**The design §2.1.3 open question is DECIDED.** Ground truth that drove the decision (state-map
workflow `wf_820d4837-a6d`, 6 mappers; pass-structure findings recovered from the failed agent's
transcript — its StructuredOutput attempts had `citations` as string not array):

- **Dest (portal) passes run NO framegraph.** The flag-ON dest render is
  `SecondaryWorldRenderCore.renderDestWorld`'s decomposed sequence; entity features draw through a
  single synchronous `renderAllFeatures(storage)` (cross-dim `renderPortalEntities:1144`; same-dim
  `renderPortalEntitiesSameDim:1279`). Fabric Level render events never fire for dest passes.
- **The main pass's IP-faithful slot exists as a public Fabric event:**
  `LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN` fires inside `lambda$addMainPass$0` wrapping the
  ordinal-1 TRANSLUCENT `renderGroup` — i.e. after `executeSolid/executeTranslucent/executeOutline`,
  BEFORE translucent terrain (bytecode-verified in the resolved fabric-rendering-v1 jar by the
  verify round). IP 1.21.3 drew CASE-1/CASE-2 immediate draws at end-of-entity-rendering, before
  translucent terrain AND before portal passes — both ordering relations match. Drawing later
  (AFTER_TRANSLUCENT_TERRAIN) would depth-reject bracketed entities behind water (translucent
  terrain writes depth) — entities would VANISH through water. R13c lambda-anchor mixins avoided
  entirely.

**The three landed draw sites (all per-storage, Verifier-1 P2 preserved):**

1. **Main pass:** fabric flag-ON branch registers `BEFORE_TRANSLUCENT_TERRAIN` →
   `PerEntityClipBracket.onMainPassBeforeTranslucentTerrain()` (common-side logic, F12-clean; thin
   fabric timing driver). Storage resolved from `mc.levelRenderer` via
   `LevelRendererAccessorMixin.seamlessportals$getSubmitNodeStorage()`. Guards: defensive
   `PortalRendering.isRendering()` + the mid-packet-frame skip (rule 14). Model-view stack holds the
   view rotation there (26.2 LevelRenderer.render:170-172/:252); feature draws resolve their target
   per-draw via `OutputTarget.MAIN_TARGET` → main target (verify-confirmed at source).
2. **Cross-dim dest pass:** direct call after `renderAllFeatures(storage)` in
   `renderPortalEntities`, inside the pushed destViewMatrix + armed 10.5 inner clip + live stencil.
3. **Same-dim (loop-back) dest pass:** same shape after
   `sameDimFeatureDispatcher.renderAllFeatures(sameDimSubmitStorage)`.

**ownRenderBuffers lifecycle (the gpu-buffer-leak gap closed):** allocation in
`getOrCreateOwnDispatcher` now registers via `ClientWorldLoader.registerCoreOwnedFeatureBuffers`
(the S15 same-dim pattern; walked per frame by `endFrameOnSecondaryFeatureBuffers` from the
flag-ON GameRenderer.render TAIL). It was the ONE mod-owned RenderBuffers not in any endFrame walk.

**Seam eviction (design §1.2.5 mandate, previously unlanded):**
`PerEntityClipBracket.onClientCleanup()` (full both-map clear + throw-fence reset + dispatcher
discard) is called from `CrossPortalEntityRenderer.cleanUp()` — which is registered at BOTH
`CLIENT_CLEANUP_EVENT` and `CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT` (both fire off-frame,
client-thread; verify-confirmed loss-free — per-frame state rebuilds at each submitEntities HEAD).
`evictPassState(storage)` added for the same-dim throw fence (the replaced storage's PassState +
phase registrations would otherwise linger on a dead identity; under Mechanism A this also fixes a
would-be phaseRegistry leak).

**Verify round `wf_a5599a71-df0` (Fable ×3: gl-state / design-fidelity / flag-regression) — PASS ×3
with ONE converged CORRECTION folded:**

- **The bracket-draw throw fence (all three lenses independently):** capture→restore was not
  try/finally (a throw leaked the entry's plane — or a DISABLED_CLIP that DISARMS the dest CASE-3
  inner clip — into the ambient store), and a `prepareFrame` throw AFTER `PreparedFrame.begin()`
  (outside renderAllFeatures' try-with-resources) wedges the own dispatcher's PreparedFrame open
  FOREVER → every later bracket attempt throws "PreparedFrame already in use" — swallowed at the
  dest sites but UNCAUGHT at the main-pass site → one transient dest-side throw converts into a
  deterministic main-pass crash during the C4 A/B. FOLDED: per-entry try/finally restore; outer
  catch + one-shot log + 3-strike dead-latch (the S15 same-dim discipline); dispatcher discarded on
  throw and rebuilt around the SAME registered ownRenderBuffers (no re-registration); strands
  cleared in finally; fence reset per session in onClientCleanup.
- Stale docs folded: getMechanism's "IPConfig persistence trails to S12" (it LANDED —
  IPConfig:51/:179/:196); the fabric init's S13-G "recursively calls renderLevel / re-fires this
  event" comment (the landed decomposition never recurses renderLevel — corrected, guard is
  defensive); MixinPreparedFrame's "S13 runtime item" throw-path pointer (now: KNOWN-OPEN and
  accepted — a throw there kills the frame on the main path anyway, vanilla-equivalent; the seam's
  own path IS fenced).

**Verify-confirmed invariants (recorded, no action):** own dispatcher cannot collide with the open
main PreparedFrame (per-dispatcher instance field; per-RenderBuffers stagedVertexBuffer);
MixinPreparedFrame no-ops on scratch-storage phases (never registered); mid-frame
RenderBuffers(0)+register is byte-identical to the S15 precedent; flag-OFF byte-neutral (the event
registration is inside the flag-ON branch; the dest sites are only reachable via flag-ON
renderDestWorld; qouteall mixins weave-gated); Mechanism-A added cost = a handful of map
lookups/null checks per frame; the "MyGameRenderer inline pool" sourcing in design §2.1.1 is
DELIBERATELY superseded by the core-owned registration (safer: pool buffers get swapped as active
renderBuffers during sub-renders while the dispatcher captures its StagedVertexBuffer permanently).

**C4 A/B judging-sheet additions (B-only known gaps — judge the A/B with these NAMED, not
discovered):**

1. **B drops entity glow outlines:** `renderAllFeatures` never calls `executeOutline`, and the
   suppressed vanilla submit can leave `hasAnyOutline()` false → the outline post-chain skips.
   A glowing (spectral-arrow) straddler keeps its glow under A, loses it under B. Test one.
2. **B's fabulous/translucency coarseness** (design §2.1.4, pre-registered): under fabulous the
   bracketed entity's translucent parts miss the weighted-blend chain; afterTerrain/alwaysOnTop
   content draws at the drain slot instead of its proper later phase.
3. B-drawn entities render after feature-phase particles (draw-order class IP's endBatch splits
   perturbed similarly).

**Dormant-in-IP parity (recorded):** `hasIntersection` + `shouldRenderPlayerNormally` have zero
callers in IP 1.21.3 itself — our zero-caller state is faithful, NOT a wiring gap. The
`isRenderingEntityNormally/isRenderingEntityProjection` flags are written-but-unread on 26.2: IP's
reader (MixinRenderSystem_Clipping per-shader uniform refresh) is re-expressed by the always-on
per-draw GlCommandEncoderClipMixin upload — no consumer needed.

**Gates:** compile green; 8-leg crossing gametest ALL LEGS PASS on the full diff.
