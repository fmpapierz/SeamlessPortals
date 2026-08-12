# IS5-RESTAMP + IS5-XDIM-SG — depthtex1 pass-boundary restamp and single-grade cross-dim capture

**STATUS: DESIGNED + JUDGED 2026-08-11 (workflow wf_8d6f578e-2a0: 3 facts agents + synthesis +
2 adversarial judges — closed-arcs judge APPROVE_WITH_CHANGES (1 blocking), engineering judge
APPROVE_WITH_CHANGES (4 blocking); both independently re-derived the §2.1 impossibility from
the pack source and AGREE with the ACHIEVABLE_WITH_DISCLOSED_RESIDUALS verdict. ALL FIVE
blocking changes are folded below, marked ⟦J⟧.** Depends on: shipped IS5-DEPTHFORK
(`dce0670`), IS5-HIST (`1be3aac`), XDIM POST (dev lever `e43d32b`), tip `575fc5b`.

## §0 THE USER CONTRACT (2026-08-11, explicit, this session)

- C1 MB-off translation ghost stays fixed (TAA reprojection must see CONTENT depth in depthtex1).
- C2 Cool MB-on look restored (MB velocity pass must see PLANE depth).
- C3 Source-dimension storm/VL stops at the portal pane (composite1-class volumetrics must see
  PLANE depth) — never paints over OW-dest window content.
- C4 Nether-dest windows show the nether storm, DEFAULT ON.
- C5 C4 ships ONLY trade-off-free: NO double-grade tint, no visible cost. If impossible, say
  IMPOSSIBLE_WITHOUT_TRADEOFFS honestly with the measured reason.
- C6 No hardcoded pack pass indices (runtime-measured introspection only); ring mandate untouched
  (coverage/color/depthtex0 stamp at main composite renderAll HEAD byte-identical); closed arcs
  untouched (occluder ring, seam black band, ghost double, BLOOMMB, XFLICK/BLINK/ARRIVE/HIST/
  OUTLINE); GPU-cost changes gate on GL_TIME_ELAPSED or whole-frame FPS, never the CPU-side
  is5.* PerfTimers.

## §0.5 UN-REJECT NOTE

The mid-composite restamp was rejected in `migration/IS5_DEPTHFORK_DESIGN.md` §4 as
"pack-coupled pass indices, violates no-hacks; the only both-ways road, rejected unless the
user explicitly demands it". It is RE-OPENED 2026-08-11 on the user's explicit demand
("Yes — re-open it"), in the runtime-measured variant only: every pass index in this design is
measured per pipeline via program introspection at runtime; nothing is hardcoded.
`IS5_DEPTHFORK_DESIGN.md` §4 gets a one-line pointer to this doc.

---

# PART 1 — IS5-RESTAMP

## §1.1 Mechanism (one paragraph)

The HEAD stamp keeps writing PLANE into depthtex1 (mode 0, byte-identical to shipped PLANE,
satisfying C2 at composite4's MB read `composite4.glsl:90` and C3 at composite1's storm read
`composite1.glsl:105`). Then, mid-`renderAll` of the MAIN composite chain, immediately BEFORE
the measured reprojection anchor pass executes, a depth-only CONTENT restamp re-runs the
existing mode-1 replay (GL_ALWAYS + eps-6e-8 visibility discard vs depthtex0 +
`max(dDest, planeZ)` floor) into depthtex1. TAA (`composite6.glsl:39` → `taa.glsl:115`) then
reprojects window pixels with CONTENT depth — C1. Both looks coexist because the fork is now
IN TIME, not in mode.

## §1.2 The anchor rule — and why "last reader" is wrong on this very pack

MEASURED FACT: with pack defaults, BOTH composite6 (TAA, `composite6.glsl:39`) and composite7
(FXAA, `fxaa.glsl:174,:180` under FXAA_TAA_INTERACTION=10, included by `composite7.glsl:23-25`)
actively sample depthtex1 (facts CORRECTION vs the base-session walk: fxaa.glsl compiles into
composite7, NOT composite6). A restamp before the LAST reader (= composite7) would hand TAA
PLANE depth and re-open the C1 ghost. The naive "last reader" wording is therefore refined:

**Anchor = the FIRST real pass whose program actively samples BOTH `depthtex1` AND
`colortex{HISTORY_TARGET}` (HISTORY_TARGET=2, the already-measured history constant).**
Rationale: the reprojection-class pass consumes previous-frame state (history) plus a
reprojection depth; on this pack that is composite6 with TAA on (`taa.glsl:198/:200` samples
colortex2) and composite7 with TAA off (`fxaa.glsl:188`). Grep-verified: NO other
composite-chain program references colortex2 (declared globally in `lib/uniforms.glsl:77`,
linker-stripped where unused — stripping semantics live-measured by the census on this driver).

⟦J⟧ DISCLOSED (closed-arcs judge): the anchor rule is runtime-measured for PASS indices but
HARD-DEPENDS on the HISTORY_TARGET=2 constant, which is Complementary-measured, not
pack-measured. On a pack whose TAA history lives in a different colortex the anchor is missed —
fail-safe to PLANE(no-anchor) = byte-identical shipped behavior, silently dropping the C1 fix
there; the detector line's `hist=[]` makes it attributable.

Selection outcomes (R = active depthtex1 reader indices, A = anchor):
- A exists AND some reader < A → **RESTAMP**: PLANE at HEAD, CONTENT restamp at boundary A.
  (Complementary defaults: compiled pass list with composite2 absent is [c0,c1,c3,c4,c5,c6,c7]
  → expect R={0,1,5,6}, A=5. Expected values for the detector line only — NEVER hardcoded.)
- A exists AND no reader < A → **HEAD-CONTENT collapse**: run today's mode-1 CONTENT at HEAD,
  no restamp. Same visible semantics, zero mid-loop cost, reuses the proven path. This is also
  the single-reader-pack answer.
- A absent, R non-empty → **PLANE** (shipped): the CONTENT purpose (reprojection) has no
  consumer; PLANE-wanting readers (storm/MB/DOF/reflection class) keep the correct depth. This
  is why anchor-coupling beats "trailing contiguous run": a pack reading depthtex1 only at
  c0+c1 must NOT get CONTENT.
- R empty → **PLANE**, no restamp (HEAD replay still runs mode 0, byte-identical shipped).

DISCLOSED ASSUMPTION: everything at-or-after the anchor is the AA tail and tolerates CONTENT
(on this pack: composite7's FXAA-TAA-interaction reads operate on TAA's output; coherent
same-depth is the consistent choice). Escape levers (§3) cover packs where the assumption fails.

## §1.3 Measurement mechanics

At HEAD-stamp time, after the identity triple matches, on cache miss: iterate
`fPasses.get(compositeRenderer)`; skip ComputeOnlyPasses (`program == null`); per pass: pid via
`Pass.program` → `Program.getProgramId()` (the census recipe);
`GL20.glGetUniformLocation(pid, "depthtex1") != -1` and `... "colortex2") != -1` (needs no
bind). Also record `passes[A].drawBuffers` reflectively for Part 2's image target. ~7 passes ×
2-3 queries, once per renderer.

⟦J⟧ CACHE (engineering judge, blocking — the cache-outlives-subject rule): storage is a
**`WeakHashMap<Object, Measurement>`** keyed on the CompositeRenderer object (identity
semantics preserved — CompositeRenderer does not override equals; Measurement holds NO GL ids,
pure measured ints, so weak keys are safe and a dead pipeline's entry dies with it — the
IrisBloomApertureMask per-renderer-plan precedent, NOT the bounded strong IdentityHashMap of
the pre-judge draft). Never texture names (the two-latch rule stays for FBOs; identity-keying
is its analog here). Pipeline/pack rebuild creates a new CompositeRenderer instance →
automatic re-measure. Explicit clear on mechanismBroken.

Measurement failure (GL error, reflection throw): restamp disabled for that renderer,
mode=PLANE, once-per-renderer WARN, census-visible. NOT mechanismBroken — the HEAD stamp
semantics remain intact and shipped-correct.

## §1.4 The boundary hook — ⟦J⟧ TWO INDEPENDENT DISPATCH BRANCHES (both judges, blocking)

New mixin `MixinIrisCompositeRenderer_DepthRestamp` at the PROVEN pre-pass-i seam:
`@Inject(method="renderAll", at=@At(value="INVOKE",
target="Lnet/irisshaders/iris/gl/program/Program;unbind()V"), require=0)` +
`@Local(ordinal=0) int i` (bytecode offset 213; LVT slot 4 spans 76..470 — the
BloomApertureMask precedent; javap-re-verify on any iris bump). Fires BEFORE mipmap regen
(230-307), setupState/FBO bind (312), viewport (395), Program.use (419), draw (455) of pass i —
so pass i re-establishes FBO/viewport/scissor/blend/colorMask/program/samplers itself and NO
framebuffer save/restore is needed. Restore obligations for our draws: CULL
(query-save/restore), VAO re-bind via `FullScreenQuadRenderer.INSTANCE.bind()`, depth state
left `_depthFunc(LEQUAL)` + `_disableDepthTest()`, our OWN GL_DEPTH_CLAMP enable/disable pair
(finally-scoped — the runStampPass bracket is scoped to its own try/finally and does not cover
a mid-loop draw), blend explicitly disabled.
⟦J⟧ SETUP obligations (closed-arcs judge): the boundary draws must ASSERT their own
`_viewport(0,0,w,h)` and `_disableScissorTest()` — the previous pass iteration may leave a
ViewportData-scaled viewport; pass i re-establishes its own so no restore is needed, but our
draw inherits whatever i-1 left. (The BloomApertureMask body being cloned does both — keep them.)

⟦J⟧ THE DISPATCH (as-drafted, a single gate list would have structurally killed the SG dest
capture: the dest chain's renderAll runs INSIDE the portal view render, where
`PortalRendering.isRendering()` is TRUE and `restampArm` is necessarily null — every SG frame
would silently fall to the POST fallback while kill-checks "judged SG". Both judges found this
independently.) Handler `IrisStageConsistentComposite.onRestampBoundary(Object renderer, int i)`
evaluates, in order:

- **Gate 0 (common to both branches):** weave witness; PATH_ACTIVE; !mechanismBroken.
- **Branch (a) — DEST-CAPTURE (Part 2), evaluated FIRST and NOT gated on isRendering:** fires
  iff a pend record exists AND `renderer == pend.destRenderer` AND `i == pend.destAnchor`.
  `PortalRendering.isRendering()` is EXPECTED true here (the dest chain runs inside the view
  render). Consume-once per pend record.
- **Branch (b) — SOURCE RESTAMP + SG INJECT (Part 1 + Part 2 step 3):** `restampArm != null`
  (absent on XWIN frames structurally — never armed); `renderer == restampArm.renderer` (a
  SINGLE identity compare — excludes begin/prepare/deferred renderers and all dest pipelines
  with zero per-pass cost); `i == restampArm.anchorIndex`; consume-once latch;
  `!PortalRendering.isRendering()` belt (valid HERE: the main chain never runs inside a view).

## §1.5 Slot retention (pending is dead after HEAD)

`pending` is consumed in the HEAD-inject finally BEFORE pass 0 runs — the restamp must NOT key
on it. runStampPass, when the measured mode is RESTAMP, builds `restampArm = {renderer,
anchorIndex, entries[{slot, portal, cameraPos, partialTick}...], Matrix4f mvCopy, projCopy,
(Part-2: sgEntries, imageTarget, imageReadsAlt)}` for layer-0 slots it depth-replayed. Slot GL
textures are pooled and survive mid-renderAll (ensureSlotStorage frees only on size/format
change; beginFrame clears flags only and cannot fire mid-renderAll). Matrices are COPIED
defensively (same bob-correct values, same mesh → same planeZ per fragment → identical
discard-eps semantics as the HEAD replay). Cleared: after boundary execution; at beginFrame
with WARN + `rstOrph` census if never consumed; ⟦J⟧ AND inside breakMechanism (so the legit
mid-frame break path cannot trigger a spurious next-frame orphan WARN — the WARN is
additionally keyed on !mechanismBroken).

## §1.6 The restamp draw

Per retained entry: rebuild the mesh (`ViewAreaRenderer.buildPortalViewAreaMesh` pattern) with
the stored matrices; build `restampFboDepth1` fresh (depthtex1 attach via
`addDepthAttachmentBypass(rts.getDepthTextureNoTranslucents().glId())` + `noDrawBuffers()` —
REBUILT EVERY TIME, no caching); bind stamp program, `locDepthMode=1`, unit 3 = `slot.depthTex`,
unit 4 = main depthtex0 `rts.getDepthTexture()` glId (STILL plane-stamped from HEAD —
composites carry no depth attachment, so the visibility-discard reference is exact; O4 re-javap
on iris bumps); `_enableDepthTest` + `GL_ALWAYS` + `_depthMask(true)` + own depth-clamp
bracket + viewport/scissor assert (§1.4); draw; restore. depthtex0/depthtex2 are NOT touched
(they stay PLANE in all modes — ring mandate untouched, C6). FBO destroyed after the draw.
glGetError adjudication per the copy discipline; failure → breakMechanism (already-stamped
PLANE remains = shipped-safe degraded frame).

## §1.7 Interactions

- IS5-HIST: untouched (writes history at HEAD, never depthtex1). Under RESTAMP, TAA now
  consumes HIST history + CONTENT depth — the never-run-live XDIM+CONTENT-analog combination.
  Kill-check 5 covers it.
- d0/d1 comparator RE-KEY: it reads at HEAD time, before any composite, so under RESTAMP it
  sees the PLANE HEAD stamp. New mode token in its line: `RESTAMP-HEAD` with PLANE semantics
  (EQUAL = stamp lands); the old PLANE/CONTENT tokens keep their meanings for the lever modes.
  NEW gated readback: under `is5LiveReadbacks`, 1Hz, immediately after the boundary draw:
  center-texel d1 vs plane — DIFF expected when dest content differs from the pane. glGetError
  drain stays ATOMIC with its gate.
- BLINK/XFLICK/ARRIVE/OUTLINE: zero contact (query path is depthtex0/main-depth).
- Ring mandate: HEAD color/coverage/depthtex0 stamp byte-identical — this design only adds a
  later depthtex1-only write. C6 holds.
- Kept-MB features: MB velocity (c4) reads PLANE — the window plane-velocity MB AND the
  crossing blur burst are preserved by construction (both ride composite4's pre-anchor read).
- ⟦J⟧ Co-injection note: BloomApertureMask and DepthRestamp now inject at the SAME
  Program.unbind() seam with unspecified relative order. On this pack their firing indices
  differ (bloom-mask index vs anchor 5) and both handlers are state-self-contained, so order is
  immaterial; recorded assumption — if a foreign pack collided them at one i, the mask's c0
  clear+repaint and the restamp's depthtex1/colortex writes touch disjoint targets either way.

## §1.9 THE COOL-MB SETTING (2026-08-11, post-R1 user decision — AMENDS the kept-feature policy)

After live-verifying R1 (all four checks good), the user decided: the cool plane-velocity MB
look becomes a **Mod Menu setting, DEFAULT OFF** — "even with mb on there is no cool effect".
This supersedes the 2026-08-04 "keep both as features" policy for the window-MB half (the
crossing blur burst is ambient-wide and remains; watch it on the next MB-on leg).

Mechanics: `IPConfig.coolPortalMotionBlur` (Cloth screen, client category, runtime-apply) →
`IPGlobal.coolPortalMotionBlur` (mutable static, IS5-RC-reported). The measurement gains
`prevCamReaders` = d1 readers whose program actively references `previousCameraPosition` (the
MB-velocity class — velocity needs prev-frame camera state; measured active on Complementary
composite4 by the DestPrevCamera arc, stripped with pack-MB off). The arm gains a
`depthBoundary` decided per frame: cool ON = the anchor (MB sees PLANE = the whip); cool OFF =
min(anchor, first prevCam reader) (MB sees CONTENT = ordinary blur; storm/reflection readers
before it keep PLANE). **The SG inject index is PINNED to the anchor in both modes** — an
earlier inject would sit before the source tonemap and re-expose the double-grade. Branch (b)
therefore fires at up to TWO indices per frame (depth half, inject half), each consume-once;
the arm clears when both halves are done. DISCLOSED HEURISTIC: a foreign pack whose VL pass
consumes previousCameraPosition would move the cool-OFF boundary early there (VL marches to
content in windows on that pack); the setting itself (ON) and the force levers are the escape.

## §1.8 Detector-reads-raw (one leg proves mechanism + fix)

The MEASUREMENT runs regardless of all levers (even force-PLANE / force-CONTENT-HEAD). A 1Hz
line while armed:
`IS5-RESTAMP meas: passes=N d1=[...] hist=[...] anchor=A imgTgt=T mode=RESTAMP|HEAD-CONTENT(collapse)|HEAD-CONTENT(lever)|PLANE(lever)|PLANE(no-anchor)|PLANE(meas-fail) rst=<draws> rstOrph=<n>`
Raw sets are printed uninterpreted; the mode token states the decision. One live leg reads both
the mechanism (reader sets match the pack walk) and the fix decision (anchor + mode),
independent of which lever cell is running. ⟦J⟧ Adjudication rule (closed-arcs judge O1): a leg
is judged on the RAW `d1=[...] hist=[...]` sets against the pack walk, never on the mode token
alone — GLSL implementations MAY report a declared-but-dead uniform as active, and an inflated
colortex2 set on an early pass would move the anchor EARLIER (a C2/C3-class regression wearing
a green mode token).

Census additions: `rst=`, `rstOrph=`, and Part 2's `sgC=/sgI=/sgF=`. Counters are
draws-issued, NEVER gates.

---

# PART 2 — IS5-XDIM-SG (single-grade dest capture)

## §2.1 Adjudication: the literal pre-tonemap source-graded capture is IMPOSSIBLE at pass granularity

Measured chain, per the pack walk (both judges re-derived this independently from the source):
1. Storm is mixed into the image in GAMMA space inside composite1 (`GetNetherStorm` call
   composite1.glsl:237, mix :247-249), BEFORE that same shader linearizes (`pow(color, 2.2)`
   :313) and bakes the bloom-fog MULTIPLY (:323-325). No pass boundary exists between
   storm-apply and multiply — one shader invocation, one DRAWBUFFERS:0 write.
2. The DIVIDE (`composite5.glsl:193-195`) and the tonemap (DoCompTonemap :209) live inside
   composite5's single shader. No boundary splits divide-from-tonemap.
3. Therefore EVERY capturable boundary ≥ c1 carries the multiply baked with the divide pending,
   in BOTH worlds; and the source chain's divide at its c5 uses the SOURCE formula at PANE
   distance (`bloomFog.glsl:17-40` per-dimension; netherBloomAdd 14.0) —
   destFog(destPixel)/srcFog(pane) ≠ 1, a visible per-pixel brightness error.
4. Additionally the source HEAD stamp feeds source c1, which linearizes AGAIN (pow 2.2 on
   already-linear data) — the gamma-space blocker.
5. Undoing either in the stamp shader requires replicating GetBloomFog's per-dimension formulas
   in mod GLSL — pack-FORMULA coupling, strictly worse than pass-index coupling. Rejected under
   C6/no-hacks.

## §2.2 The achievable variant: DEST-GRADES-ONCE LATE-INJECT. Verdict: ACHIEVABLE_WITH_DISCLOSED_RESIDUALS

Insight: "graded exactly once" does not require the SOURCE to be the grader. The tonemap curve
and exposure are WORLD-INDEPENDENT (TM_*/T_SATURATION/T_VIBRANCE/GR_* defined once
unconditionally, lib/common.glsl:275-296; grep-verified no per-world redefinition), so a
dest-graded window IS graded identically to the source surroundings.

Flow (all pass indices runtime-measured, §1.3 machinery reused):
1. **Dest capture at the DEST anchor boundary.** At XDIM pend time (onFinalizeAboutToComposite,
   unchanged pend-no-cancel), additionally resolve the dest pipeline's compositeRenderer,
   measure ITS anchor + image target, store on the pend record. The shared boundary hook fires
   on the dest chain via ⟦J⟧ dispatch branch (a) (§1.4 — NOT gated on isRendering, which is
   EXPECTED true there) at `i == destAnchor`: glCopyImageSubData the image from
   `destPasses[destAnchor].drawBuffers[0]`'s READ side (per-pass flip snapshot = parity at that
   boundary) into `slot.colorTex`, and dest depthtex0 into `slot.depthTex`. Read-only copies —
   the dest chain never observes them; remaining dest passes run to completion discarded
   (side-effect ledger IDENTICAL to shipped POST: dest TAA history under the
   one-POST-per-dest-dim rule — UNCHANGED). Mid-renderAll CANCEL is rejected: ci.cancel()
   would skip renderAll's tail cleanup (bytecode 470-535) and renderFinalPass still runs —
   hand-replicating that is exactly the fragility this arc bans. GPU delta vs POST: zero pass
   savings; + two copies + one inject draw.
   ⟦J⟧ Format compatibility (engineering judge): the boundary image (colortex3-class, likely
   RGBA16F) differs from the mainRT-format slot.colorTex shipped POST allocates —
   ensureSlotStorage is format-keyed and MUST be passed the MEASURED boundary-texture internal
   format; a copy failure routes to the `sgF` fallback (census-visible), NEVER breakMechanism
   (HEAD/POST semantics remain intact).
   ⟦J⟧ imgTgt assumption (closed-arcs judge): `drawBuffers[0]` = "the image being AA'd" is
   runtime-measured but semantically ASSUMED; verified here (composite6 DRAWBUFFERS:32 →
   drawBuffers[0]=3 = the migrated image, [1]=2 = history). A foreign pack writing history
   first would make the inject overwrite the history read side — guarded by SG dev-OFF + the
   user gate; the meas line prints `imgTgt=` so a mis-measure is log-readable.
   The captured image state: dest reflections ✓ (c0 ran), dest storm ✓ C4 (c1 ran),
   linearize+fog-multiply ✓, fog DIVIDE ✓ (dest c5, cancels dest c1's multiply exactly — same
   formula, same world, same depth: the pack's own invariant), dest bloom incl. nether strength
   boost ✓, dest tonemap ✓ — **graded exactly once, self-consistently, display-referred,
   PRE-TAA/FXAA/final**.
2. **HEAD stamp unchanged** — stamps this capture (ring mandate: byte-identical mechanics; only
   the DATA in the capture differs, which is what a capture is). Source chain processes the
   stamped pixels as today (feeds reflections, fog decisions, bloom gather).
3. **Source inject at the SOURCE anchor boundary** — the SAME hook invocation as Part 1's
   restamp (one boundary, two draws), dispatch branch (b). New stamp-shader mode 2: mode-1's
   visibility discard (vs depthtex0, which still holds HEAD plane+occluders) but writing
   `fragColor = texelFetch(u_capture, tc, 0)` instead of depth. FBO: color-only, attaching
   `srcPasses[anchor].drawBuffers[0]`'s READ-side texture (parity as above; measures to
   colortex3 on this pack — measured, never written into code), no depth attach, rebuilt per
   draw. The anchor pass (TAA) then reads the clean single-graded window with CONTENT depth
   (Part 1) and HIST history — single TAA, single FXAA, single sharpen/dither via source
   c6/c7/final. Fires only for slots whose boundary capture succeeded (`sgCapture` flag);
   otherwise skip inject → full shipped-POST behavior for that slot (`sgF++`).
   Ordering: the hook precedes pass i's mipmap regen, so if the anchor pass mipmaps the image
   target our write is regenerated over — correct by construction.
   ⟦J⟧ Nested-content exclusion (engineering judge, blocking): part4's capture-to-capture
   nested stamp (fork (c), post-pop — which completes BEFORE main renderAll HEAD) can stamp a
   same-dim PRE child's PRE-COMPOSITE scene-referred pixels into an SG parent's capture AFTER
   the dest-anchor copy; the source inject would then deliver pixels that bypass
   linearize/bloom-fog/tonemap ENTIRELY (never graded — worse than POST's once-graded). The
   standard A→B→A corridor ALWAYS produces this case. Rule: runNestedStamp sets a per-slot
   `sgNestedContent` flag when the parent slot is SG-captured; the inject is SKIPPED for that
   slot+frame (POST-fallback look, `sgF++`, census-visible). Residual R11.
4. TAIL handler: when `sgCapture` set, skip the mainRT color copy; keep the pend/leak/
   pending=true bookkeeping, orphan WARNs, identity re-checks unchanged.

DEPENDENCY: SG requires Part 1's mode ∈ {RESTAMP, HEAD-CONTENT} on the source renderer
(injected content + PLANE depth at TAA = re-ghosted window). Enforced at arm time; violation →
POST fallback, census-visible. ⟦J⟧ First-armed-frame race (closed-arcs judge): the source
renderer's measurement does not exist until its first HEAD stamp, so the first armed SG frame
cannot verify the dependency — it falls back POST for that frame (`sgF++`), NO WARN.

## §2.3 Residuals (every visible one — the user gate)

R1. Bloom-halo rim: bloom spill AROUND the window is gathered (c4) from the HEAD-stamped,
    source-re-processed capture, while the window FACE is the clean inject — a subtle rim
    brightness/tint mismatch confined to the spill.
R2. Source c1 overlays over the window face are LOST: aerial/distance fog at pane depth, rain
    visuals, underwater colour — POST applies them (double-graded); SG's inject overwrites
    them. Visible as a slight "pop" on distant windows in fog; negligible near.
    ⟦J⟧ HIGHEST-IMPACT INSTANCE (engineering judge, blocking — a C3 collision): for a
    NETHER-SOURCE → OW-dest window, the source NETHER_STORM contribution accumulated between
    the camera and the pane (the pane-near slab — C3's own semantics say it REMAINS) is ERASED
    by the SG inject: a visible hole-in-the-storm exactly at the window. The nether storm is
    the dominant source volumetric, so this is NOT "negligible near". Named user-gate leg in
    kill-check 12. Not recoverable without pack-formula replication (rejected).
R3. MB-on: source c4 motion-blurs the HEAD-stamped window in colortex0, then the inject
    REPLACES those pixels with the crisp capture — the kept stronger-blur-on-window FEATURE is
    lost on SG windows while MB is enabled. MB defaults OFF in the pack; the user gates this
    cell explicitly (kill-check 13).
R4. Lens flare (non-default, OW-dest only): bakes into the dest capture; source flare may also
    draw over it.
R5. The nether-only bloom-strength boost (composite5.glsl:132-140) in the capture is DEST's —
    window bloom is dest-consistent, not source-matched. Arguably the intent; disclosed.
R6. Anchor-missing dest chains (e.g. pack TAA+FXAA-interaction both off): boundary capture
    cannot fire → POST double-grade fallback for those frames, `sgF=` census-visible.
R7. Source reflections (c0) and bloom-gather see the HEAD-stamped capture, not the final
    injected pixels — same class as shipped POST, not new.
R8. Because residuals R1-R3 exist, C4's DEFAULT ON and C5's trade-off-free CANNOT both be
    asserted before the user's live gate. SG therefore ships DEV DEFAULT OFF; the flip to ON
    (satisfying C4) happens only after kill-checks 11-14 pass the user's eye. This is the
    honest reading of C4∧C5.
R9. Dependency residual: SG without the Part-1 restamp active would re-ghost the window;
    enforced at arm time, POST fallback otherwise.
R10. GPU cost: zero dest-pass savings vs POST; net = POST + two boundary copies + one inject
    draw; gated by FPS/GL_TIME_ELAPSED A/B.
⟦J⟧ R11. Nested layers in SG parents: slot-frames carrying part4 nested-child content skip the
    inject (§2.2.3) — those frames render as shipped POST (double-graded), `sgF=`-visible. The
    A→B→A corridor makes this the COMMON nested case; the alternative (injecting never-graded
    child pixels) is strictly worse and prohibited.

## §2.4 What SG fixes vs shipped POST

The double-grade tint (dest tonemap → source pow2.2 → source tonemap) is GONE for window
pixels; double TAA/FXAA/sharpen/dither GONE (capture is pre-AA); the half-processed-colortex0
trap avoided (multiply/divide both dest, cancel exactly); storm present (C4). One grade,
world-identical curve.

---

# §3 LEVER TABLE

| Lever | Default | Semantics |
|---|---|---|
| `seamlessportals.is5DepthRestamp` (+ disable row `seamlessportals.disableDepthRestamp`) | **ON** | The Part-1 machinery: measure, then RESTAMP / HEAD-CONTENT-collapse / PLANE per §1.2. Disable row = force-PLANE escape (byte-identical shipped PLANE everywhere). |
| `seamlessportals.is5WindowContentDepth` | OFF (kept) | **Migration story:** the fork lever is NOT removed. It becomes the force-CONTENT-at-HEAD escape: when set, the HEAD replay runs mode 1 and the restamp disarms (precedence: explicit CONTENT-HEAD > restamp). Shipped semantics preserved bit-for-bit; existing BG rows untouched; IS5-RC auto-reports it as before. The PLANE-vs-CONTENT fork it carried is DISSOLVED — restamp-ON delivers both sides simultaneously. |
| `seamlessportals.is5XdimSingleGrade` | **OFF (dev)** — per verdict + §2.3 R8 | Requires `crossDimDestChain` + restamp mode RESTAMP/HEAD-CONTENT. Flip to ON only after the user residual gate (kill-checks 11-14) — that flip is the C4 delivery. |
| `seamlessportals.crossDimDestChain` | OFF (dev, unchanged) | XDIM POST as shipped; SG layers on it. |

Idiom: static-final + Boolean.getBoolean per the existing IPGlobal block. BG rows added to all
three run blocks (clientSodium, crossingGametest, is5RegressionGametest). IS5-RC self-reports
both new levers with zero registration (reflection). GPU-cost acceptance gates on whole-frame
FPS A/B or GL_TIME_ELAPSED — never is5.* CPU timers (C6).

# §4 KILL-CHECKS

1. MEASUREMENT (runs regardless of levers): on Complementary r5.8.1 defaults the 1Hz line must
   show the compiled-list reader sets matching the pack walk (expected d1=[0,1,5,6],
   hist=[5,6], anchor=5, imgTgt=3 with composite2 absent — expected values for the LOG only,
   never in code); repeat with pack MB=1 (composite4's index must join d1=) and with TAA off
   (anchor must move to the FXAA pass or vanish; either outcome logged, adjudicate per O3).
   ⟦J⟧ Judged on the RAW sets vs the pack walk, never the mode token alone. Wrong sets = STOP.
2. C1 leg (one leg proves mechanism+fix): MB off, TAA on, window, translation strafe — no
   ghost; then relaunch -PdisableDepthRestamp — the ghost's known signature returns. Detector
   line present and RAW in both runs.
3. C2 leg: pack MOTION_BLUR_EFFECT=1 — the stronger-blur-on-window feature AND the seam blur
   burst both present (kept-feature policy), visually indistinguishable from the shipped PLANE
   cell.
4. C3 leg: nether-source window with NETHER_STORM — storm halts at the pane, never paints over
   window content; identical to the shipped PLANE cell.
5. HIST × CONTENT-at-TAA (never-run cell): MB off, approach the window slowly — no
   approach-blur regression, no history-clamp shimmer on the window.
6. Comparator re-key: log shows RESTAMP-HEAD with EQUAL at HEAD; under -Pis5LiveReadbacks the
   boundary readback shows DIFF at window center against an open dest vista.
7. Escape levers: -Pis5WindowContentDepth run — mode=HEAD-CONTENT(lever), no restamp draws,
   today's CONTENT semantics; -PdisableDepthRestamp run — mode=PLANE(lever), byte-identical
   shipped look.
8. XWIN frames: cross-view frames show zero rst= increments, zero new WARNs (structural no-op —
   never armed).
9. RESTAMP perf gate: whole-frame FPS A/B (restamp on vs disable row) in a window-heavy scene —
   delta within run-to-run noise; GL_TIME_ELAPSED spot-check optional; NEVER the CPU is5.*
   timers.
10. Closed-arc sweep: occluder ring absent, seam black band absent (slow crossings ×3/direction),
    ghost double absent, BLOOMMB both directions, OUTLINE sliver absent, shaders-OFF stencil
    path untouched.
11. SG C4/C5 leg (-PcrossDimDestChain -Pis5XdimSingleGrade): OW-source → nether-dest window
    shows the nether storm; A/B against the POST cell — the double-grade tint signature GONE;
    sgC=/sgI= match armed views, sgF=0 (except nested/R11 frames).
12. SG residual gate (THE USER'S EYE, required before any default flip): R1 bloom-rim
    inspection at a bright window edge; R2 distant-window-in-fog pop inspection; R5 nether
    bloom-strength look; ⟦J⟧ R2-STORM: nether-source → OW-dest window — the user judges the
    pane-near storm-slab erasure (the hole-in-the-storm C3 collision). User pronounces
    trade-off-free or not — C4's DEFAULT ON flips only on their yes.
13. SG + MB on: confirm and show the user R3 (window loses the extra-blur feature under SG) —
    their call per the kept-feature policy.
14. SG fallback: dest chain with anchor forced absent (pack TAA off) — sgF= increments, window
    renders as shipped POST, no crash, no mechanismBroken. ⟦J⟧ Plus the R11 corridor: A→B→A
    nested scene — nested slot-frames skip the inject (sgF= increments), no never-graded pixels.
15. Suites: is5RegressionGametest + crossingGametest green with the new BG rows present in all
    three run blocks; IS5-RC block prints both new levers ([1/3] ground-truth + [2/3]
    reflection).

# §5 OPEN ITEMS (named verification steps)

O1. Linker dead-code sampler stripping is unverified for depthtex1/colortex2 SPECIFICALLY
    (evidence base is gbufferModelView/MB-trio on composite4). VERIFY via kill-check 1's raw
    reader sets (defaults + MB=1 + TAA-off legs).
O2. Compute-pass image-unit/SSBO hygiene at the unbind seam is unanalyzed. Our draws are
    graphics-only; Complementary's composite chain shows no compute passes. VERIFY: kill-check
    10 sweep; adjudicate if a compute-composite pack ever arms.
O3. Whether `fxaa.glsl:188`'s colortex2 read survives compilation with TAA off decides TAA-off
    anchoring (CONTENT-at-FXAA vs PLANE). Both outcomes acceptable; VERIFY via kill-check 1.
O4. The depth-read-only-composites claim (visibility-discard reference validity HEAD→anchor) is
    javap-pinned for iris 1.11.2+26.2. RE-VERIFY on any iris bump before trusting the mid-frame
    discard.
O5. `@Local(ordinal=0) int i` at the reused unbind-seam target is LVT-covered (slot 4 spans
    76..470); re-run the javap check on iris update per the mixin-bytecode rule.

# §6 IMPLEMENTATION PLAN

See the session ledger; order: IPGlobal levers → ISCC (reflection, measurement, runStampPass
mode decision + arm record, onRestampBoundary two-branch dispatch, stamp-shader mode 2, SG pend
extension + TAIL skip, beginFrame clears, census/detector) → new mixin + mixins.json →
build.gradle rows (three blocks) → this doc + DEPTHFORK §4 pointer → javap-verify → build →
kill-checks 1-2 first, then 3-10; SG legs 11-14 under dev levers; suites last. No default flip
for SG until the user speaks on kill-checks 12-13.
