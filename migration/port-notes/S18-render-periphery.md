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

---

## §2 — S18.2: trailing periphery — map verdicts + the CrossPortalViewRendering re-home (LANDED)

**Periphery map verdicts (state-map `wf_820d4837-a6d`, periphery mapper — 5 of 6 items are
RUNTIME-WIRED already; each needs only a live-round check):**

| Item | State | S18 live-round check |
|---|---|---|
| GuiPortalRendering | wired (tail drain at MixinGameRenderer `_onGameRenderEnd`); only the `/gui_portal` example drives it (IP-identical — it is a 3rd-party API) | drive `/gui_portal`, confirm a portal view renders into the example framebuffer |
| OverlayRendering | wired via `PortalEntityRenderer.submit:78-79`; **the plan's "submitBlockModel re-expression still needed" note is STALE** — G33/G34 landed as `submitCustomGeometry` + `putBakedQuad` (deliberately NOT submitBlockModel: it cannot express IP's per-quad opacity), `RenderTypes.translucentMovingBlock()` for `translucentCullBlockSheet` | breakable portal → overlay renders at correct opacity/offset |
| Mirror / BreakableMirror | both EntityTypes registered + PortalEntityRenderer bound (unconditional); NO dedicated renderer BY IP DESIGN (stencil path + `reflect()` transform + odd-mirror winding) | create a Mirror (`/portal` path), confirm reflection renders |
| renderMode family | all four impls instantiated + live-switchable (`switchToCorrectRenderer` per frame); only `normal` gate-proven | flip normal/compatibility/debug/none per the (d) script |
| A2 view-bob | LIVE, IP-verbatim (C5 BINDING behavior change — bob scales down near portals) | walk toward/away from a portal; bob scales and returns |
| CrossPortalViewRendering | **was compiled-but-DEAD (zero call sites)** — the S13 re-home of IP's GameRenderer handlers missed handler ④ | third-person through a portal; bob-through crossing |

**The handler-④ re-home (the §2 code work).** IP shape 1:1: `@WrapOperation` on
`GameRenderer.render`'s `renderLevel(DeltaTracker)` INVOKE
(`MixinGameRenderer.seamlessportals$redirectRenderingWorld`) — when the physical-head→camera segment
crosses a teleportable portal, `renderCrossPortalView()` REPLACES the whole world render with the
dest-side view (IP `MixinGameRenderer.redirectRenderingWorld:145-160`). Composes with the existing
shift-AFTER lifecycle inject on the same instruction (IP composed three handlers there identically).
26.2 adaptation (driver-re-home-forced): the prepare/finish bracket IP got from handlers ②/⑤ moved
INSIDE `renderCrossPortalView` (`switchToCorrectRenderer` + `prepareRendering` + invoke in try /
`finishRendering` in finally — the GuiPortalRendering trio), since those handlers now live in the
AFTER_TRANSLUCENT_TERRAIN listener inside the SKIPPED renderLevel.

**Verify round 1 `wf_b9fd9266-022` (Fable ×2) — FAIL: 2 BLOCKERs + 2 CORRECTIONs, ALL FOLDED.**
The layer-0 exposure class: `renderDestWorld` had only ever run inside `doRenderPortal`'s
`pushPortalLayer` bracket; this re-home created its first live LAYER-0 caller (and made the latent
GuiPortalRendering layer-0 path's defects reachable too):

1. **BLOCKER — EmptyStackException at Step 10.5:** `getActiveClippingPlane()` peeks the empty
   portal-layer stack. FOLDED: gated `isRendering() ? plane : null` (IP gated EVERY such site;
   `setupInnerClipping(null)` disables — a layer-0 full-frame render is unclipped, exactly IP).
2. **BLOCKER — no try/finally around `switchAndRenderTheWorld`'s restore:** a dest-render throw
   permanently stranded the swapped client context (level/renderer/camera/lightmap/particle-world/
   renderBuffers/hitResult + MV/projection). FOLDED: invoke in try, whole restore block in finally
   (re-verify confirmed byte-identical content+order via `git diff -w`; propagation-after-restore
   judged FAITHFUL — no catch anywhere in IP's chain either).
3. **CORRECTION — `mainChunkSampler` capture gate:** `==1` skipped the layer-0 entry (fresh-session
   cross view drew ZERO terrain) and a nested portal inside a cross view captured the swapped
   secondary's null sampler, poisoning the static (whole screen loses terrain while held). FOLDED:
   gate widened to `<= 1` + `captureMainChunkSampler` REFUSES null candidates (a non-null sampler is
   the true-main signature — secondaries never run `LevelRenderer.render`).
4. **CORRECTION — frozen stale projection:** the S13-M bobbed-projection capture is written only
   inside the skipped renderLevel → cross-view frames rendered with the LAST normal frame's frozen
   base·bob·spin (stale FOV/aspect + a bob the pass disables). FOLDED: null the capture before the
   trio — the getter falls back to the CURRENT frame's unbobbed `cameraState.projectionMatrix`
   (extract still runs on cross-view frames); next normal frame recaptures. Verified sole-reader.
5. Also folded: the S15 mid-packet skip guard at entry (player/level mismatch + null camera-entity/
   originalCamera → return false), and the S14.29 stencil-exit hardening (`glDisable(GL_STENCIL_TEST)`
   after `finishRendering` — the exact GuiPortalRendering precedent; without it the GUI pass drew
   stencil-tested EQUAL(0)).

**Re-verify `wf_7bd76af3-9ef` (Fable ×2) — PASS ×2.** Exhaustive layer-0 stack-peek sweep: 10.5 was
the ONLY ungated site in `renderDestWorld`'s whole call tree (`getPortalLayer()` = stack size, safe;
`setStencilLimitation(0)` over a zero-cleared buffer passes everywhere; every other peek site
isRendering()-gated). Sampler-lifecycle audit: within a session a captured sampler can never
dangle-closed (close only in the atomic recreate + teardown); cross-session cleared by cleanUp.
Known benign residuals (recorded): an options-change anisotropy reset can be dropped during a HELD
cross view (cosmetic); the pre-existing unbracketed `popRenderInfo` / GuiPortalRendering trio above
the fixed shell (moot — a propagating throw is fatal by design, IP-parallel); the renderLevel-HEAD
probes (DrawCallTrace/RenderChainProbe/frame-boundary row) do not fire on cross-view frames
(debug-kit cadence gap, accepted + ledgered).

**HONEST GATE NOTE:** the 8-leg suite runs first-person only — it CANNOT exercise the cross-view
branch (the head→camera segment is ~zero-length). The suite passing proves the decision path is
inert on normal frames; the TRUE branch is proven only at the live (d) round (third-person through a
portal + held cross view with a nested portal visible + FOV-change during hold — the re-verify's
named scenarios).

**Gates:** compile green ×2; 8-leg gametest ALL LEGS PASS post-folds.
