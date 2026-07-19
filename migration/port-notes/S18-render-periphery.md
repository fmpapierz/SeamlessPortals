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

---

## §3 — S18.3 + S18.7: dest clouds + dest weather (the S13-J deviation + S14-step-6 item CLOSED)

**The isolation design (both items, one pattern):** mod-owned per-dest-dim renderer instances that
NEVER touch the main renderer's shared buffers — the fog-buffer/DimensionRenderHelper shape.

- **Clouds (S18.3):** per-dim `CloudRenderer` map in SecondaryWorldRenderCore; draw restored at the
  decomposition's designed Step-10.11 slot (after nested portals, vanilla clouds→weather order).
  Constraints: ONCE per dim per frame (a second same-frame render on one instance would rotate its
  utb twice in one submit — the exact fence class); texture MIRRORED from the reload-registered main
  instance at the render-TAIL `endCloudFrames` walk (mod instances are not reload listeners — their
  own texture stays null forever, the same reason secondary renderers' cloudRenderers never drew);
  per-instance `endFrame()` (ubo rotate, vanilla parity); `close()` on cleanup; FABULOUS SKIP
  (cloudsTarget() would be a framegraph-internal handle). New accessor
  `IECloudRenderer_Accessor` (texture get/set), registered in the ip-client mixin set.
- **Weather (S18.7):** per-dim `WeatherEffectRenderer` map; draw at Step 10.12, CROSS-DIM ONLY.
  No ring buffer exists in the weather path (plain `glBufferSubData`, GL implicit sync — verified at
  GlCommandEncoder.writeToBuffer:254-258), so no cap/endFrame; the isolation is PURELY DEFENSIVE
  (recorded truth per the verify round). Textures per-render via TextureManager; the bound lightmap
  is the GameRenderer's — swapped to the dest dim's during the pass, exactly right. Fabulous skip.

**Verify `wf_3217b5a7-e5d` (Fable ×2: gpu-lifecycle PASS / render-correctness FAIL) — folds:**

1. **BLOCKER (folded): the post-crossing null-texture nuke.** After ANY cross-dim crossing,
   `client.levelRenderer` is permanently the per-dim renderer whose CloudRenderer texture is null —
   the change-detection treated null as "changed", disposed every instance AND nulled the mirror →
   dest clouds never drew again for the stay in that dim (the flagship scenario!). FOLDED: null =
   NO-INFORMATION; only dispose/re-mirror on a NON-NULL identity change (TextureData is
   dim-independent CPU data — retaining last-known is exactly correct).
2. **CORRECTION (folded): same-dim weather was broken as written.** sharedState passes skip the dest
   extract — destLRS IS the main LRS, weather columns are MAIN-camera-centric; rendering them at the
   portal camera indexed the 32×32 column table out of range (AIOOBE swallowed per frame → zero
   weather + wasted build work). FOLDED: Step 10.12 gated `!sharedState`; same-dim window weather is
   LEDGERED into the same-dim re-extract gap family (block entities/particles — the §4 item; IP
   re-extracted per pass). The header's "around the PORTAL camera" claim corrected to cross-dim-only.
3. **CORRECTION (folded): cloudRange stale-utb.** Vanilla wires Cloud Distance changes to the MAIN
   instance's `markForRebuild` only; a mod instance would resize its utb and draw stale quadCount
   over UNINITIALIZED texel memory. FOLDED: `endCloudFrames` tracks the option; on change,
   `markForRebuild()` on every instance.

**Verify-confirmed (recorded):** the fence-crash class is CLOSED with proof — the throw needs ≥3
rotations of one 3-slot ring inside one submit; the cap holds each instance to ≤1 map + 1 rotate per
frame; the main CloudRenderer is byte-untouched (texture READ only). TAIL-time close() after a
same-frame draw is GL-safe (spec defers storage destruction; vanilla depends on the same property).
TextureData sharing needs no defensive copy (record + never-written cells). CLIENT_CLEANUP fires on
the render thread with the GL context current. Weather clip STATICALLY PROVEN live (WEATHER
pipelines build on particle.vsh = canonical pattern → patched → per-draw upload hits).

**Ledgered deviations/residuals (this section):** (a) 2nd+ window to the SAME dim in one frame draws
no clouds (26.2-forced rotation budget; IP's immediate-mode had no fences); (b) fabulous skips both
draws (framegraph-internal targets); (c) **clip deviation, improvement-class: IP drew dest clouds
UNCLIPPED** (its per-shader feed unset the uniform for all but cross-portal-entity + weather) — ours
clips them at the portal plane (same class as the S11-R3 §1.3 registered tighter-clip improvement);
(d) weather clip epsilon: ours inherits -ADJUSTMENT where IP armed 0 (sub-block); (e) a mid-session
flag flip to OFF strands live instances until the next cleanup (idle GPU memory only); (f) the
renderLevel-HEAD-throw frame skips the TAIL walk (identical exposure to vanilla's own endFrame walk).

**Live-round checks added:** window rain visible through a cross-dim portal in rain; dest clouds in
the window (and: same-dim windows currently cloud-capped-once/rainless — expected); clouds clipped
at the plane (the recorded improvement vs IP side-by-side).

**Gates:** compile green; 8-leg suite ALL LEGS PASS post-folds (chunk-ticket errors = the known
IP-inherited S14.52 noise class).

---

## §4 — S18.5 + S18.6-instrument: dest block outline + the dpMs top-level-only fix

**Dest targeted-block outline (S18.5a).** The pieces COMPOSED without new machinery — the missing
link was ONE boolean: `renderPortalEntities` passed `false` as `submitFeatures`' `renderOutline`
arg. Now: vanilla's own `shouldRenderBlockOutline()` per pass (new `@Invoker` on
GameRendererAccessorMixin — exactly what IP's nested renderLevel recomputed per pass; IP had ZERO
outline machinery). Verified end-to-end (`wf_8a0f8152-4d8`, PASS ×2): the shell's hit swap
(remote hit, dest coords) + shouldRenderHitResult null-out precede the dest extract; vanilla
`extractBlockOutline` (unlevered) reads the swapped hit against the DEST level;
`submitBlockOutline` positions camera-relative to the portal camera; drawn in the pass's
renderAllFeatures under clip + stencil; storage identity confirmed (the vanilla this-field quirk is
identity-neutral); Mechanism B unaffected.

**The flag-ON sliver re-bucket (S18.5b — the S17 sweep finding closed).**
`LevelRendererBlockOutlineMixin`'s trigger keyed on the block-era tracker only (never fired for
entity portals). Flag-ON branch now keys on IP's own `RenderStates.lastPortalRenderInfos`
(non-empty ⇔ a portal rendered last frame; 1-frame hysteresis invisible for a bucket choice);
flag-OFF byte-equivalent ternary; class-load neutrality verified at the bytecode level (lazy
getstatic resolution).

**dpMs instrument fix (S18.6, the S14C-round8 "KNOWN INSTRUMENT ARTIFACT").** The bracket in
`switchAndRenderTheWorld` is depth-counted: only the OUTERMOST invocation accumulates
`destPassNanosThisFrame` (nested passes were counted twice — own bracket + inside the parent's).
Verified balanced under the S18.2 shell try/finally including throw paths. **The S14.52 parity
read (dp=5 avg 21.6ms etc.) MUST BE RE-MEASURED on the corrected probe before any optimization
work** — and note dp= still counts nested passes while dpMs= is now top-level-only (never divide
old and new rows arithmetically).

**IP-inherited corners recorded (NOT defects — verified byte-identical in IP 1.21.3):**
(a) conditional-swap leak: pointing at a MAIN-dim block, the un-swapped main hit can pass
shouldRenderHitResult's cross-space plane test by coordinate coincidence → phantom outline at
main-hit coords in the dest view (IP identical; also reachable on the layer-0
GuiPortal/cross-view passes); (b) the dest outline draws before dest translucent terrain
(alpha-blended under dest water — the after-terrain deferral can't be reproduced in-pass; same
ordering class as all dest-pass features) and before nested layers (the sliver class can recur one
recursion level down); (c) `lastPortalRenderInfos` can be stale-true for ≤1-2 frames across
relog/mid-packet frames (bucket-choice-only). Same-dim passes stay outline-less (the ledgered
same-dim re-extract family).

**S18.3 folds spot-checked in place by this round** (null-as-no-information mirror, !sharedState
weather gate, markForRebuild-on-cloudRange) — the S18.3 re-verify obligation is closed.

**Gates:** compile green; 8-leg suite ALL LEGS PASS.

---

## §5 — S18.4: same-dim BLOCK ENTITIES landed; particles DESIGNED; vehicle CLASSIFIED (S18.8)

**Same-dim block entities (LANDED — the S15 F1 "per-pass visibleSections" gap closed).** The
tracer round (`wf_8e531398-86a`) found the "missing" list EXISTS: `extractVisibleBlockEntities`
iterates the renderer's `visibleSections` FIELD, and for same-dim passes that field holds the
Step-9 PORTAL-camera discovery list at the 10.8 site (the shell installs a scratch list; discovery
clears+refills it in place). Two new invokers (`extractVisibleBlockEntities`,
`submitBlockEntities`) + a four-step insert in `renderPortalEntitiesSameDim`: BERD.prepare(portal
cam) → scratch clear → isolated extract → submit into the same isolated storage before the single
renderAllFeatures; finally-cleared. Verify `wf_d69550f0-be8` PASS ×2 — the identity chain proven
for ALL FOUR pass classes (layer-1 loop-back, promoted-main, nested layer-2 return-home,
cross-view layer-0); zero one-shot state touched; the globally-rendered prune idempotent; BERD
prep self-heals next frame (ERD parity class). **Ledgered residuals:** the BE fade gate
(`getVisibility < 0.3`, LevelExtractor:276) has NO isDestExtracting bypass (the entity bypass
hooks a different method) — BEs in portal-only-revealed freshly-compiled sections pop in after the
fade window; the new BE extract sits outside the DestSubLevers attribution levers (whole-pass
`debug_skip_same_dim_entities` is its only lever); no BE probe counters yet.

**Same-dim + dest PARTICLES (DESIGNED, not landed — the remaining §4-family half).** The tracer
proved at source: `QuadParticleGroup.particleTypeRenderState` is ONE persistent per-group field —
a second mid-frame extract on the SAME engine corrupts the main pass (S14.40 confirmed at source);
`extractRenderState` cannot target caller-supplied state. **The only clean isolation = a SEPARATE
ParticleEngine** (separate groups ⇒ separate accumulators), and per-dim engines are CHEAP (maps
only; the heavy ParticleResources is shared read-only) — **the mod already builds exactly these**
(`PortalWorldManager.particleEngines` + `getOrCreateParticleEngine`, the block-era path where dest
particles DO render, swapped via `withSwitchedWorld` with `destParticlesActive=true`). The IP-path
adoption plan: swap `mc.particleEngine` to the per-dest-dim engine in `switchAndRenderTheWorld`
(replacing the plain `ip_setWorld` re-point), condition the MixinParticleEngine HEAD-cancel on
"the engine being extracted is the MAIN one", drop the S14.41 clear for isolated engines, and let
the dest extract fill `destLRS.particlesRenderState` from the isolated groups. OPEN QUESTIONS
before landing (why it did not ship this increment): flag-ON, who SPAWNS/ticks particles into the
per-dim engines (the block-era feed — tickCachedParticles — runs only in the flag-OFF client-tick
block; the flag-ON spawn routing for dest-dim level events must be traced first), and the same-dim
spatial half of IP's filter (isOnDestinationSide) has no 26.2 anchor yet. NEXT INCREMENT.

**Vehicle presentation (S18.8 — CLASSIFIED, instrument-first plan set).** The tracer settled it:
**IP-INHERITED, no port regression** — the full ridden chain is faithful (client-first seamless
move + re-seat; server recreate with reused id+uuid; remove-packet suppression; IP's ported
passenger-guard live flag-ON; the highest-risk 26.2 retarget — `super.removeVehicle()` — verified
to send NO dismount packet, exactly IP's intent). The ~1s = **IP's own EntitySync
entity-ticking-range gate** (`ip_sendChanges` withheld until the dest chunk reaches
entity-ticking level → the moved client copy freezes; the late SetPassengers eject-then-remount =
the flicker). Per NO GUESSING the fix choice needs LIVE captures first: (1) does the
"[ImmPtl] Entity already exists and has passengers" warn fire at a ridden crossing (the guard
held) or not (a discard-recreate occurred)? (2) correlate the ticking-range flip with motion
resume. Fix candidates (post-capture): broaden the block-era adopt-in-place mixin to flag-ON
vehicles with a vehicle-scoped predicate (strict superset of IP's passenger-only guard — closes
the passenger-empty window), and/or force the recreated vehicle's first sends (IP's
updateEntityPos precedent). ALSO: the user can settle the bar empirically — original IP
side-by-side should exhibit the identical ~1s vanish (the classification predicts it).

**S18.9 records:** (a) **item-9 conditional re-check: CONDITION NOT TRIGGERED** — zero S18
references to `PortalContextSwitch.isRenderingPortal`/`armPromoteBridge` across all S18 commits
(grep-verified); the two mixins' inertness stands on the S17 sweep's verdict. (b) **F18 Vulkan:**
no Vulkan-capable run available in this autonomous session (the dev gametest harness runs GL);
the F18 obligation remains the S11-spec documentation (raw-GL mechanisms silently no-op under
VulkanBackend) — a live Vulkan-backend launch is OPTIONAL at the (d) round if the user's setup
supports it; otherwise carry to polish. (c) **Row-4 fuse-view write-mask spot-check:** live-round
item (in the (d) script below).

---

## §6 — THE S18 (d) LIVE-ROUND SCRIPT (run on READY; judge against the NEW two-sided standard)

**C4 A/B (THE STAGE DECISION — rule 9, BINDING).** Both mechanisms are now FULLY wired (B's draw
sites landed §1). Flip via `config/immersive_portals.json` → `"crossPortalEntityClipMechanism":
"SUBMIT_ORDER_UNIFORM"` (A, default) ↔ `"ISOLATED_STORAGE_BRACKET"` (B) + restart (or the config
GUI). Scene: an animal + yourself (third person) halfway through a portal; inspect BOTH sides +
the projection. Judge: clip-edge correctness at the plane; translucent parts; fabulous mode;
mirrors; draw-order flicker; **the B-only named gaps (§1): a GLOWING (spectral-arrow) straddler
loses its outline under B (keeps it under A); fabulous coarseness; B-after-particles order.**
Record the verdict — C4 is re-confirmed on it.

1. **Straddle (item 7, the NEW standard):** animal halfway through — whole from BOTH sides,
   threshold clip both directions; punch/reach through the portal; damage flash visible in the
   portal view; the S15/S17 hand-item sliver.
2. **Third-person cross view (§2 — NEW code):** F5 + walk through a portal (the view should
   switch to the dest side as the camera line crosses); hold the cross view with a nested portal
   visible in the dest view; change FOV (sprint) during a held cross view; resize the window
   during hold. Bob-through: walk along a portal plane so view-bob dips the camera through.
3. **Mirrors:** create a Mirror (`/portal` path §2 table), confirm reflection; player suppressed
   when camera too close (IP behavior).
4. **renderMode family (A1):** flip normal → compatibility → debug → none via
   `/imm_ptl_client_debug render mode <mode>`; each behaves per IP (normal = the gate-proven
   stencil; compatibility = FBO (heavier); debug = diagnostic; none = no portal view).
5. **A2/C5 view-bob:** walk toward/away from a portal — bob scales down near, returns away.
6. **OverlayRendering:** breakable portal → overlay at correct opacity/offset.
7. **GuiPortalRendering:** `/gui_portal <dim> <pos>` — a portal view renders into the example
   framebuffer.
8. **Dest clouds (§3):** clouds visible through a cross-dim window (OW dest); CLIPPED at the
   plane (recorded improvement vs IP — optional side-by-side glance); second same-dim window in
   one frame is cloudless (expected cap).
9. **Dest weather (§3):** rain in the OW → window rain through a cross-dim portal (clipped);
   same-dim windows rainless (expected, ledgered).
10. **Dest block outline (§4):** point at a block THROUGH a portal — outline on the dest block in
    the window; near a portal the source-side outline no longer slivers at the window edge.
11. **Same-dim block entities (§5):** a chest/enchanting table visible through a same-dim portal
    (loop-back view) renders; watch for the fade-window pop-in residual on portal-only-revealed
    sections.
12. **Multi-portal parity RE-MEASURE (§4):** the corrected dpMs (top-level-only) at the S14.52
    scene (dp=5 cluster) + the same scene on original IP side-by-side — the parity verdict now
    has a truthful instrument. Capture kit rows.
13. **Vehicle captures (§5, instrument-first):** ride a minecart through a portal; check the log
    for "[ImmPtl] Entity already exists and has passengers" (guard fired = freeze axis; absent =
    discard-recreate axis); optional IP side-by-side (prediction: identical ~1s vanish).
14. **Row-4 fuse-view write-mask spot-check** (S17 residual): fuse-view portal scene per the
    CUTOVER_SPEC row-4 note.
15. **Watch items:** the particle lever (`debug_allow_dest_particle_extract`) during
    rain/particle scenes; the MEDIUM amplifier (portal-cone compiles a beat late after far-walk
    crossings — expected, self-healing); dest break particles still absent (the §5 designed-next
    item — expected).
