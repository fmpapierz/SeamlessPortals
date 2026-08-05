# IS5-PRE — stage-consistent portal compositing (the occluder-ring MECHANISM fix)

**Status: v3 — FROZEN FOR IMPLEMENTATION. Six verifiers (928k tok) + three adversarial judges
(479k tok, all APPROVE_WITH_CHANGES, zero REJECT); every judge-required change folded in below and
marked ★J. Evidence trails: the two workflow records + judge/verifier digests in the session
scratchpad.** Branch `iris-on/is5-shadow`. Bug + mandate: `OCCLUDER_RING_HANDOFF.md` §8.

## §0 THE MANDATE (user-decided 2026-08-03)

Shaders-OFF is completely clean (log-proven leg). **The mechanism must go entirely — exact coverage
everywhere, sub-pixel included.** Any post-filter binary paste re-creates the defect; occluder and
destination must be filtered TOGETHER, once.

## §1 THE SHAPE (frozen)

1. **FRAME-START LOOP** — new anchor in `GameRenderer.renderLevel` at
   `@At(INVOKE, target=LevelRenderer.render 8-arg, shift=BEFORE)` (the shipped IS0 anchor's INVOKE
   with BEFORE; runs after iris$startFrame at `GameRenderer.render` HEAD and after the frame pump).
   ★J at loop entry, in order: `switchToCorrectRenderer()` (selection is otherwise one frame stale
   at every shader toggle), gate to the compat family only (shaders-OFF path untouched), then ONE
   mechanism-wide ARM decision (§3.1). Iris's bob `mulLocal` is a @WrapOperation ON this INVOKE and
   runs after all BEFORE-injects (judge-settled, javap'd) — the anchor sees the UNBOBBED
   viewRotationMatrix, so the bob recompute (§3.3) is NECESSARY, and `capturedMainPassBobbedProjection`
   + IrisBobSync's gate are already fresh (written at the getBuffer wrap BEFORE the INVOKE).
2. **CAPTURE + CANCEL** — @Pseudo mixin on `IrisRenderingPipeline.finalizeLevelRendering` at
   `@At(INVOKE, target=CompositeRenderer.renderAll)` (matches exactly once; `isRenderingWorld=false`
   + `removePhaseIfNeeded()` run naturally before it — zero bookkeeping replication), cancellable.
   ★J fires ONLY when the frame-start loop's explicit ARM FLAG is set for the current view (not bare
   `PortalRendering.isRendering()`): cross-view-dispatched nested views therefore keep the OLD path
   (§2 cross-view). Capture = colortex0 (side: `passes.get(0).stageReadsFromAlt.contains(0)` via the
   shipped bloom-mask reflection; skip ComputeOnlyPass to first real Pass; zero-real-composites ⇒
   DISARM to old path — no FinalPassRenderer-parity fallback needed) + depthtex0, into the view's
   per-VIEW capture buffer (§3.5). ★J then unconditional turnOffMips hygiene on the cancelled
   instance's targets (renderAll's mip regen also never runs — stale mips otherwise persist on
   cross-dim instances). Then cancel.
3. **STAMP AT MAIN COMPOSITE renderAll HEAD** — ★J the inject must discriminate: identity-compare
   `this` against the MAIN pipeline's reflected `compositeRenderer` field AND
   `!PortalRendering.isRendering()` AND consume-once-per-frame — begin/prepare/deferred renderers
   invoke the SAME `renderAll` method (V2's own uniqueness proof cuts the other way here; a naive
   HEAD stamp lands in the BEGIN chain and dies silently). For each captured layer-0 view, in
   capture order: draw the portal-shaped `ViewAreaRenderer` mesh into a MOD-OWNED GlFramebuffer
   (colortex0 pass-0-READ side + `addDepthAttachment(depthtex0)`; iris factory FBOs rejected —
   lifecycle), sampling the capture, shipped-stamp compare family + depth WRITE (composites sample
   depthtex0 LIVE and never write it; no attach+sample feedback anywhere — judged), IS5-HAND cap
   carried (hand IS in depthtex0, 0.125-squashed slice), C4-SEAM depth clamp bracket. Composites
   then filter everything once.
4. **OLD ANCHOR RETAINED** — occlusion queries keep being ISSUED there (finished depth exists only
   there; the query draw MUST keep routing through `ViewAreaRenderer.renderPortalArea` under that
   exact name — it is the C2 dontinline anchor, §6). The frame-start loop CONSUMES `lastFrameQuery`
   only; ★J render-if-unknown is BUDGETED (§3.7).

**Recursion**: tail dispatches survive (mod-owned call sites after render() returns). Re-aim the
three hardcoded mainRT dereferences (snapshot source :491/:519-520, stamp source :708, blit-back
:542) at the parent view's capture buffer; inner stamps are capture-to-capture against UNFILTERED
content ⇒ exact at every depth. Dispatch stays at the tail slot (probe-bracket windows close first).

## §2 CROSS-VIEW (XWIN) FRAMES — OLD PATH, DISCLOSED ★J

On a cross-view frame `GameRenderer.renderLevel` is never called (the @WrapOperation returns
early), so the frame-start anchor cannot fire and the frame has no main-composite stamp point. The
ARM-flag discriminator keeps every XWIN-dispatched nested view on the OLD post-composite path
(capture/cancel never armed there). **Consequence, disclosed: the occluder ring persists on
cross-view frames** (third-person-through-portal). Bounded, shippable; a composite-consistent
cross-view path is future work.

## §3 PIECE LIST (frozen; ★J = judge-mandated)

1. ★J ONE mechanism-wide ARM decision at loop entry, evaluated once per frame BEFORE any view:
   compat family live + reflection surfaces resolved (pass-0 side, FrameCounter write probe at mod
   init) + real composite pass 0 exists. ANY failure ⇒ the ENTIRE old path for the frame, loud
   once. No half-armed states: capture-cancelled-but-unstamped is structurally impossible.
2. Frame-start inputs: unbobbed `cameraRenderState.viewRotationMatrix`; recomputed bob+hurt+spin
   product via invoker (pure functions of extract-time state — do NOT read iris's bobStack, stale
   on bob-skipped frames); projection: `cameraRenderState.projectionMatrix` (bit-equal under
   shaders-ON).
3. Query consumption: `lastFrameQuery`-only + render-if-unknown, ★J capped per frame (default 4)
   counting against the cap only at layer 0 (the >0 layers already have the budget); beyond cap ⇒
   skip-if-unknown (bounded pop-in); census counter so a silent cut is distinguishable.
4. Capture+cancel mixin (require=1, javap the deobf target before first launch per the
   mixin-bytecode discipline; new-class weave on IrisRenderingPipeline is the one untested
   precedent — first-launch witness mandatory).
5. ★J Per-VIEW capture buffers: an ordered per-frame capture list (portal×layer keyed), lifetime =
   until consumed by the stamp pass, recycled after; layer-0 count bounded by the §3.3 cap (+
   nested by the existing budget). Memory bound stated in-code (~24MB/view at 1080p RGBA8+depth).
   Same-dim captures MUST complete before the main render (shared physical texture side).
6. Stamp inject per §1.3 with the ★J discriminator; restore per bloom-mask discipline (HEAD needs
   less: renderAll re-binds quad VAO + colorMask after HEAD; pass-0 setupState rebinds FBO/blend).
7. ★J COUNTER bracket, distant-offset variant — the judged +1-bump ALIASES (bracket-only programs
   record lastFrame=N+1 == next frame's natural count ⇒ full-frame stale-uniform flash at every
   cross-dim crossing). Instead: reflectively save N, SET `count=(N+360360)%720720` before the
   loop (no `beginFrame()` — TIMER untouched), restore N after. 360360≡0 mod 8 preserves dest
   framemod2/4/8 phase; the natural counter cannot reach the offset for ~360k frames; every main
   program re-uploads. `preparePipeline`'s first-encounter COUNTER.reset inside the window is
   erased by the restore (an improvement over today — documented in-code).
8. Same-dim prev-camera: SAVE/RESTORE `CameraPositionTracker` + gbufferPrevious* matrix state
   around the loop (the relocated re-tick was judged defective — zero main MB velocity). Cross-dim:
   no heal needed (dest ticks only its own notifier; manager slot self-heals — main's
   preparePipeline is now the frame's last writer).
9. `IrisTemporalTargetGuard` brackets the loop; skip-restore on resize (nested begin may consume
   `fullClearRequired` + reallocate — dimension-check before restore). ★measured KEEP, not retire:
   Complementary's `deferred1.glsl:492` reads clear=false colortex5.a (corner-texel vlFactor) in a
   PRE-composite stage that still runs.
10. Weather render state save/restore (the one unrestored shared-LRS mutation); re-scope the
    once-only NON-EMPTY WARN.
11. Recursion re-aim per §1; `deferredPeak` reset moves to the new frame entry.
12. Dispositions ★J stated explicitly: bloom-mask `arm()` gated AT ITS CALL SITE on the path flag
    (mask internals byte-identical — 4b913a5 undisturbed; avoids a false 'ring persists' WARN);
    IrisDestPrevCamera KEPT AS-IS (defect premise dissolves; residual main-chain writes benign;
    its own lever remains the A/B); IS5-L in-render bump calls left in place (harmless under the
    `!=` gate — documented); IS5-G clearForDestPass kept LEVER-ARMED initially (its composite
    consumer is cancelled, but dest deferred stages still read history — retire only after a clean
    leg); IS5-FF suppressor kept as belt, bracket moved to wrap the loop, class doc premise
    rewritten; IS5-ACT slot-heal rationale documented as superseded on the new path.
13. Budget-gate caveat: `updatePreRenderInfo` reset skipped on mid-packet frames — loop tolerates a
    stale budget count for one frame.
14. ★J Lever: ONE static-final path flag resolved at class load (mixins cannot unload; every hook
    gates per-invocation on the same immutable value — the path can never mix mid-session);
    `IS5-RC` prints the live path. Dev default OFF (`-PstageConsistentComposite=true` to enable);
    the default flips ON + `-PdisableStageConsistentComposite` becomes the lever ONLY after both
    live directions pass.
15. First-launch WITNESSES (mandatory, log-only): anchor-order (COUNTER value at anchor vs
    startFrame), bob-order (field vs recomputed product), capture-geometry (always-on, WARN on
    mismatch — the snapshot-witness discipline retargeted), path self-report. Plus a one-frame fog
    ring-buffer probe (the nested tail setupFog re-run vs the main render's pushed slice — judged
    settle-during-impl).
16. ★J Old-path instruments (SeamHandStageDiff, SeamHandLocator, StampCoverageProbe,
    SeamDestContentProbe, ActSeedProbe, ShaderpackViewsProbe) assume post-main ordering: mark
    stale for new-path legs; never adjudicate a new-path leg on their output unscoped.

## §4 SETTLED FACTS THE IMPLEMENTATION LEANS ON (measured this arc)

- perFrame gate is `lastFrame != count` (if_icmpeq, ProgramUniforms.update bci 101-106) — NOT
  monotonic; the distant-offset bracket is sound under it.
- `BufferFlipper.flip` has exactly ONE caller in all 844 iris classes (CompositeRenderer) and it is
  constructor-only ⇒ NO runtime flips; construction-baked parity is safe to read.
- Hand is IN depthtex0 at renderAll HEAD (0.125-squashed slice); depthtex1 contains the solid hand
  (copyPreHandDepth runs before it); only depthtex2 is hand-free; depthtex1/2 never contain the
  stamp (bounded pack residual, §5).
- Query pipeline compare is GEQUAL ⇒ passes on EQUAL ⇒ no query self-test strobe from stamped
  plane depth (live A/B still planned per the declared-vs-executed depth history).
- GuiPortalRendering never enters `renderDestWorldFullPipeline` ⇒ structurally unaffected
  (verify once live).
- F1 driver's nested fires are double-guarded; the MAIN render's fire is the last passingModelView
  writer before the stamp — same last-writer invariant as today.
- Perf: net POSITIVE steady-state (≈9 full-screen passes saved per view; layer-0 snapshot+blit
  deleted; added: per-view capture copies + ~free brackets).

## §5 DISCLOSED BEHAVIOUR CHANGES (state to the user on the fix leg)

- Window content motion-blurs/TAAs coherently; aperture bloom comes from real window content.
- Cross-dim windows get SOURCE-dimension composite processing (exposure/tonemap).
- Packs reading depthtex1/2 or aux colortex (normals/materials) in composites see main-scene data
  at portal pixels (bounded; Complementary unaffected — its composites are colour+depth effects).
- One speculative render per unknown portal (≤cap/frame); after a hitch wipes query history, up to
  cap speculative renders/frame with skip-if-unknown spill (bounded pop-in), not a render storm.
- ★J Cross-view frames keep the old compositing — THE RING PERSISTS THERE (disclosed up front so
  the first cross-view observation doesn't read as a failed fix).
- Portal-plane depth near the camera can beat hand-slice texels in later composites' depth reads on
  CROSSING frames only (bounded; on the crossing-leg checklist).

## §6 C2 JIT DISPOSITION ★J (the design rewrites the protected inlining chain)

- The query-issue draw keeps routing through `ViewAreaRenderer.renderPortalArea` UNDER THAT NAME —
  it is the shipped dontinline anchor sitting below every crash root.
- Any lambda/method that moves class or name in the rewrite ⇒ refresh the `-Pc2LegacyExcludes` rows
  (a lambda is TWO compilable forms — proxy bridge AND synthetic body).
- Acceptance gate before default-ON: a `-Pc2Inlining` unit-size leg in the dense-portal repro scene
  — gate on UNIT SIZE, never on a clean run.

## §7 IMPLEMENTATION STAGES (each: crossing gametest green → commit → push)

S1 flag+lever+RC line+doc · S2 iris-facing mixins inert + reflection surfaces + witnesses ·
S3 frame-start anchor+arm+brackets (flag-gated) · S4 captures+stamp+recursion re-aim ·
S5 query consumption+cap · S6 live legs (enable lever), both directions + C2 unit-size leg →
default flip. KILL RULE: any witness contradicting §4 stops the stage, not the leg after it.

### STAGE LEDGER (update at every stage commit)

- **S1 LANDED** `1b28c3c` — `IPGlobal.STAGE_CONSISTENT_COMPOSITE` (static-final, dev default
  OFF, `-PstageConsistentComposite=true`); run-block rows in clientSodium + crossingGametest.
- **S2 LANDED** `d8acde1` — `MixinIrisRenderingPipeline_PreCompositeCapture` (cancellable INVOKE
  inject, require=1), `MixinIrisCompositeRenderer_PreCompositeStamp` (renderAll HEAD, require=0),
  `IrisStageConsistentComposite` coordinator (dormant; arm token null until S3 arms). **BOTH WEAVE
  WITNESSES PROVEN ON THE LIVE JVM** (2026-08-04 01:11:20, `latest.log:732` stamp seam,
  `:793` capture seam, `path=OLD armed=false`, zero mixin errors) — the V2 must-settle item
  (new-class weave on IrisRenderingPipeline) is CLOSED.
- **S3 LANDED (skeleton)** `506dba8` — `PortalRenderer.ip_onFrameStartBeforeMainRender` (empty
  base; only the compat renderer overrides ⇒ stencil family untouched), the shift=BEFORE sibling
  inject in `MixinGameRenderer_IPPostLevelAnchor` (path-flag-gated, calls
  `switchToCorrectRenderer()` first per the judge), and the compat override whose ARM DECISION
  currently falls back to OLD unconditionally with a once-only witness.
- **S4a LANDED** `c4ef1d4` — reflection surfaces in the coordinator (`compositeRenderer` identity,
  `renderTargets`, `passes`, `stageReadsFromAlt`, `FrameCounter.count` — all javap-pinned) +
  `proveFrameCounterWrite()` live probe + `decideArmForFrame()` real chain (falls back
  `loop-not-landed(S4b)`); frame-start witness now CONTENT-KEYED on the decision string.
- **S4b-part1 LANDED** `30fd9d4` — per-VIEW capture slots (pooled, format-matched, 16-slot hard
  bound) + `armCaptureForView` + the COMPLETE capture/cancel body (pass-0 side resolution, both
  `glCopyImageSubData` copies, unconditional turnOffMips hygiene, `ci.cancel()`), mask-idiom
  failure discipline (`breakMechanism` → old path from next frame; one-arm-one-finalize enforced).
- **S4b-part2 LANDED** `bc5cba5` — the STAMP PASS at main renderAll HEAD, complete: triple
  discriminator (consume-once + !isRendering + `this`==main `compositeRenderer` identity),
  mod-owned GL program with the SHIPPED depth semantics (nocap vsh + IS5-XCUT per-fragment floor
  `max(gl_FragCoord.z, 0.001)`, GEQUAL + depth WRITE, C4-SEAM clamp bracket), FBO =
  colortex0 pass-0-READ side + `addDepthAttachmentBypass(depthtex0)`, u_solid/mesh-tint carrying
  `-PdebugStampSolid`/`-PdebugTintStamp` identically. Layer≥1 slots deliberately not stamped here
  (part3's recursion re-aim consumes them). **Everything still dormant** — nothing calls
  `armCaptureForView` until part3's loop fork.
- **S4b-part3 + S5 LANDED (hash below)** — the loop fork per the FORK STRATEGY: frame-start loop
  with the §3.7/§3.9/§3.10 brackets (§3.8 deliberately bracket-free per the refinement; IS5-PH
  heal suppressed in query-only mode), the three doRenderPortal forks (consume-only visibility
  with SPECULATIVE_CAP=4 = S5; capture arm; old-stamp bypass), the post anchor's query-only mode,
  bob recompute + witness, STAMP-TIME matrices (post-mulLocal passingModelView via the F1
  last-writer invariant — stamp geometry never depends on the bob recompute), and the
  nested-layer deferral (part4 = capture-to-capture re-aim; single-layer new path until then,
  announced once). **The new path is END-TO-END COMPLETE, single-layer, dev-lever-gated.**
- **S6 NEXT** — live legs: `-PstageConsistentComposite=true -PirisRuntime=true`, sidecar read at
  leg start; log gates (ARMED announcement, capture geometry, stamp pass line, counter proof, bob
  witness, zero breakMechanism); user gates (ring GONE under MB yaw; §3.8 pre-registered MB
  check; hand/seam/bloom arcs intact); then the lever-off direction (ring BACK); the C2
  `-Pc2Inlining` unit-size leg in the dense scene; then part4 recursion re-aim; then default
  flip.

### S6 LEG 1 (2026-08-04 02:34-02:38, MB=1 str=2.00 on disk) — ONE DEFECT, LOG-ADJUDICATED

GREEN on the live JVM: `armDecision=ARMED`; `FrameCounter.count reflective write PROVEN (20046
preserved)`; both seams `path=NEW`; `bob witness: MUL_APPLIED(recompute proven)`; `IS5-RC [1/3]
seamlessportals.stageConsistentComposite = true`; `capture geometry 1718x1360 color=0x8c3a
depth=0x88f0`; nested-layer deferral announced; zero mechanism breaks, zero mixin errors.

**DEFECT (found from the log's two MISSING lines — capture printed, then neither "stamp pass ran"
nor the never-stamped WARN):** the stamp handler's slot-consuming `finally` was scoped to the
OUTER try, so the identity-mismatch return — the discriminator correctly rejecting
begin/prepare/deferred instances of the same `renderAll` method — consumed every pending slot on
its way out. First begin-chain invocation after any capture zeroed the pool; the main chain saw
zero pending; the WARN couldn't fire off an already-zero counter. On-screen symptom: windows
empty on armed frames (views cancelled, nothing stamped). FIXED `fa45b98`: consumption scoped to
the MATCHED main-chain invocation only. The instrument lesson stands: the CONTRADICTION of two
absent lines was the entire diagnosis — witnesses that fire on both outcomes are what made the
defect findable without a screenshot.

### S6 LEG 2 (2026-08-04 02:47) — STAMP FIRING; ONE DEFECT, USER-REPORTED

GREEN: everything from leg 1 plus `stamp pass ran at main renderAll HEAD — stamped=1` and a
`stamped=2` two-portal frame (the per-VIEW capture list working), zero breaks, zero WARNs.

**DEFECT (user: "with shaders on, portal window is completely invisible"):** the raw-GL stamp
used the shipped stamp's DECLARED compare (GEQUAL) — the exact §6 trap ("never design from the
declaration"). `stamped=N` with zero GL errors while nothing shows = draws issued, every window
fragment depth-rejected: under the MEASURED small-is-near buffer (39/39), plane (~0.5) >= far
scene (~0.98) is false. The vanilla RenderPass abstraction translates the declared compare; raw
GL does not. FIXED `84f3a31`: `GL_LEQUAL` — plane ≤ far-scene passes (window paints), plane ≤
nearer-occluder fails (occlusion correct). Depth-write semantics unchanged. `stamped=` joins
`masks=` in the never-a-gate ledger: counters record draws issued, never effect achieved.

### S6 LEGS 3-4 (2026-08-04) — WINDOW VISIBLE-BUT-FLASHING, THEN INVISIBLE; ONE STATE HOLE

Leg 3 (LEQUAL in force, user): window APPEARS but "flashing a little bit intermittently, and
disappeared when i enabled temporal filtering" (sidecar: TAA_MODE→absent=default, TAA_JITTER=2).
Leg 4 (1Hz census live, user): invisible with TAA on AND off; visible shaders-OFF.

**CENSUS ADJUDICATION — the pre-registered primary prediction CONFIRMED:** every stage perfect
across three TAA rebuilds (`frames=65 consumeT=65 armG=65 capt=65 stampPass=65 views=65`,
`writeAlt=true` stable, zero breaks) while nothing showed ⇒ the loss is DOWNSTREAM of the draw
call ⇒ write state. **THE HOLE: the stamp never asserted the COLOUR MASK.** A HEAD-position draw
is the FIRST draw of the composite stage (renderAll's `_colorMask(15)` runs after HEAD; the
mid-chain bloom mask leans on each pass's setupState — a HEAD draw cannot). The previous armed
frame's tail is the S5 query-only loop whose pipeline is colour/depth-write-free: leftover mask
OFF ⇒ silent no-op stamps. One mechanism covers BOTH reports: leg 4's persistent invisibility
(query loop always last) and leg 3's intermittent flashing (HUD/chat racing the mask back on).
FIXED (hash below): `_colorMask(15)` asserted in the stamp's state block. LESSON for the piece
list: a HEAD-seam draw must assert EVERY write-enable it needs — there is no upstream
re-establisher at a chain head.

### S6 LEGS 5-7 (2026-08-04 19:30-20:22) — THE LENS-FLARE LATCH, CORNERED BY TWO LEVERS

Leg 5 (mask fix live): window VISIBLE until the user toggled LENS FLARE (a pack-option pipeline
rebuild) — then invisible, and toggling back did NOT restore it, across a fresh world, census
all-green throughout. Leg 6 (`-PdebugStampSolid -PdebugTintStamp`): **"window is magenta"** — the
write path (parity, LEQUAL, mask, downstream chain) PROVEN. Leg 7 (`-PdebugTintStamp` alone):
**"Magenta-tinted destination"** + 1Hz capture readbacks showing live scene values — the capture
content and its sampling PROVEN.

**THE LATCH:** the stamp FBO cache was keyed on GL texture NAMES. A rebuild deletes/recreates
iris textures; drivers recycle freed names; a name-match kept the OLD GlFramebuffer whose
attachment references the ORPHANED old texture (alive via the attachment reference — the FBO
stays COMPLETE). Stamps wrote into the orphan: valid GL, zero errors, census green, window
invisible, latched until restart. The bloom mask dodges this by nuking its fboCache per plan
rebuild. FIXED (hash below): cache key gains the MAIN PIPELINE OBJECT IDENTITY. Hardening landed
alongside: capture-copy GL error check (breaks loudly, never silently swallowed) + the 1Hz
capture-content readback instrument.

### S6 LEG 8 (2026-08-04 20:31+) — "ALL GOOD" MODULO ONE RESIDUAL CLASS ⇒ PART5 WORK LIST

Window visible and STABLE through lens-flare and TAA rebuilds (the FBO identity fix holding);
screenshots show the window motion-blurring coherently with the scene. User: **"all good,
except"** three residuals — ALL ONE STRUCTURAL CLASS (the §5-disclosed "composites consult
per-pixel data the stamp doesn't rewrite"; the "Complementary unaffected" line in §5 is hereby
REFUTED by measurement):

1. **MB ghost**: a clear outline of the SOURCE terrain paints onto the window content under MB
   whips, gone with MB off ⇒ MB's velocity/reprojection consults the depthtex1/2 SNAPSHOTS,
   copied mid-render BEFORE the stamp exists — at window pixels they hold source geometry
   (V3's residual, now measured). FIX: stamp plane depth into depthtex1+depthtex2 with the same
   mesh+floor (two small depth-only draws).
2. **Water wobble**: source-side water behind the portal wobbles the dest view ⇒ the water
   effect keys on the material mask in an aux colortex written by the SOURCE gbuffers. FIX:
   capture+stamp the aux targets the composites read (pack-tuned list).
3. **Nether-light bleed** into an overworld window ⇒ same aux class + the §5 exposure trade
   (partially inherent, disclosed). Aux stamping should reduce it; full parity is not promised.

Also: `stamped=` count REMOVED from the stamp announcement's content key (bounced 1↔2 per-frame
on a two-portal scene, re-emitting constantly; the census `views=` carries the count).
STILL PENDING FOR THE ARC: the user's EXPLICIT ring verdict (leg 8 "all good" implies it), the
lever-off B-leg (ring returns), the §3.8 MB-correctness pre-registered check (implicitly
exercised by the MB whips — no wrong-scenery smear reported), the C2 unit-size leg, part4
recursion re-aim, part5 aux coherence, THEN the default flip.

### ★ S6 GHOST-DOUBLE ARC CLOSED (2026-08-05 01:01, user-confirmed)

The "MB ghost" → reframed by the user's translation signature → TAA-attributed by pack A/B →
depthtex1 stamp proven landing (comparator EQUAL 8/8) → history stamp built (refuted as the fix)
→ the tint leg's "ghost double containing a faint magenta window" identified the true mechanism:
whole-frame reprojection at cameraOffset ≈ the portal offset (nested renders ticking the shared
tracker with the dest camera) → §3.8's "no bracket needed" REFUTED exactly at its pre-registered
check → the named fallback built (listener-capture-scan tracker save/restore around the loop) →
**user: "ghost gone, motion blur still looks cool"** — defect dead, kept-feature intact. The
depthtex1/2 + history stamps stay (semantically correct depth/history hygiene at window pixels).

### ★ USER DECISIONS 2026-08-04 (record like policy — do NOT "fix" these)

1. **The stronger motion blur on the portal render is a FEATURE** — user: "Keep both of these as
   features, it is really cool!" The window participating fully in the pack's MB (stronger
   apparent blur due to plane-depth velocity) is INTENDED behavior on the new path.
2. **The blur burst when passing through the seam is a FEATURE** — same quote. The crossing's
   large apparent motion producing an MB burst is INTENDED. Any future MB-related change must
   preserve both (judge them on the live leg like closed arcs).

### §3.8 REFINEMENT (2026-08-04, measured against the jar — supersedes the tracker bracket)

The judged "save/restore CameraPositionTracker + gbufferPrevious* around the loop" has NO stable
reflection surface: both live in LAMBDA CAPTURES (`CameraPositionTracker` is a local of
`addCameraUniforms` reachable only through `FrameUpdateNotifier.listeners` (private
`List<Runnable>`) capture fields; `CapturedRenderingState` holds NO previous matrices — javap'd).
Reflecting into lambda capture fields (`arg$1`) is metafactory-shape-fragile — rejected.

INSTEAD: the shipped, user-confirmed `IrisDestPrevCamera` (DEFAULT ON, "FINALLY NOT BLURRY")
already rewrites prev-camera/prev-matrix uniforms at EVERY guarded composite draw, keyed on the
bind's OWN camera (per-dest nearest-match) — ordering-independent by construction. Under IS5-PRE
the main chain binds with current=mainCam while the tracker's previous holds destCam; the
correction rewrites to the stored main prev entry at the draw. **Expected: no bracket needed.**
PRE-REGISTERED CHECK for the first live leg: main-frame MB must be correct on portal-visible
frames (no portal-offset smear on ordinary scenery; window blurs coherently). FALLBACK if it
fails: enumerate `FrameUpdateNotifier.listeners`, save/restore recognized capture shapes — built
only on a failed check, never speculatively.

### §3.2 BOB INPUT, RESOLVED (javap'd 2026-08-04 — the invoker recompute is REPLACED)

`bobHurt`/`bobView(CameraRenderState, PoseStack)` are invokable but the SPIN is inline in
`renderLevel` (replicating its math = drift). Instead: iris's woven `MixinModelViewBobbing`
`@Unique Matrix4fc bobStack` field on GameRenderer holds THIS frame's full bob+hurt+spin product
by the BEFORE-invoke anchor (built by the earlier Matrix4f.mul wrap; `mulLocal` semantics ⇒
`bobbedView = bobStack × unbobbedView`). Reflect the woven GameRenderer for a field whose name
contains "bobStack" (mixin renaming tolerance); null/absent ⇒ unbobbed passthrough. MANDATORY
always-on WITNESS at the shift=AFTER anchor: compare `bobStack × unbobbed(BEFORE)` against the
post-mulLocal field (epsilon, content-keyed WARN, mechanism disarm on sustained mismatch) — the
recompute is trusted only while the witness holds.

### S4b FORK STRATEGY (derived from doRenderPortal :590-769, read 2026-08-04)

The new path FORKS doRenderPortal at five call sites, never rewrites it:
1. bloom-mask `arm()` at :640 — gate the CALL SITE on `!frameArmed` (judge: mask internals
   byte-identical);
2. `testShouldRenderPortal` at :626 — on the new path becomes consume-lastFrameQuery-only
   (render-if-unknown, capped); the query DRAW must never run at frame start (main depth is
   CLEARED there, V5) — the ISSUE stays in the post-main anchor's workhorse, which on the new
   path reduces to a QUERY-ONLY loop (no snapshot/blit/brackets);
3. before `renderPortalContent(portal)` at :690 — `armCaptureForView(portal, layer)`; the capture
   mixin consumes it at the nested finalize (capture colortex0 read-side + depthtex0 into the
   per-view buffer, turnOffMips hygiene, cancel);
4. the post-pop STAMP block at :735-758 — replaced by registering the capture + its stamp params
   (portal, modelView, projection) into the frame's ordered pending list; the real draw happens at
   main renderAll HEAD (coordinator, mask-runMask pattern: same ViewAreaRenderer mesh +
   `registerFrameTransientUbo` + vertexArrayCache bind + custom program; differences: target FBO =
   colortex0 pass-0-READ side + `addDepthAttachment(depthtex0)`, depth test GEQUAL-family +
   WRITE on, sampler = the capture texture, no clear, no dilation, C4-SEAM depth clamp);
5. old-path probes in the loop (SeamDestContentProbe/SeamHandStageDiff C) — skipped when armed
   (§3.16 stale-instrument rule).

The workhorse (`onBeforeHandRendering`) forks at its head: when the frame already ran the armed
loop, skip snapshot/brackets/blit and run ONLY the per-portal query-issue draws (the visibility
input for NEXT frame). Frame-start loop brackets (counter offset → temporal save → tracker save →
weather save → suppressor install → LOOP → restores in reverse, throw-safe finally) wrap the
relocated `renderPortals` call in `ip_onFrameStartBeforeMainRender`. Recursion re-aim: the three
mainRT dereferences in `renderNestedPortalLayer` (:491/:519-520 snapshot source, :708 stamp
source, :542 blit-back) re-aim at the parent view's capture buffer when armed.
