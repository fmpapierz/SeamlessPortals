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
- **S3 LANDED (skeleton)** — `PortalRenderer.ip_onFrameStartBeforeMainRender` (empty base; only
  the compat renderer overrides ⇒ stencil family untouched), the shift=BEFORE sibling inject in
  `MixinGameRenderer_IPPostLevelAnchor` (path-flag-gated, calls `switchToCorrectRenderer()` first
  per the judge), and the compat override whose ARM DECISION currently falls back to OLD
  unconditionally with a once-only witness. **S4 must land:** the §3.1 real arm decision
  (reflection surfaces: pass-0 `stageReadsFromAlt` side + `FrameCounter.count` write probe at mod
  init), the relocated portal loop with the brackets (§3.7 distant-offset counter, §3.8 tracker
  save/restore, §3.9 temporal guard + resize edge, §3.10 weather), per-view capture list (§3.5),
  the capture+cancel body, the stamp body with the triple discriminator (§1.3), recursion re-aim
  (§1 three mainRT dereferences), then S5 query consumption (§1.4/§3.3).
