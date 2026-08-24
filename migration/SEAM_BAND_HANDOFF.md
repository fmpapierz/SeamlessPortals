# SEAM BAND / ENTITY-CROSSING RESIDUALS — HANDOFF (2026-08-19)

Branch `claude/particle-seam-regression-c2f79c`, worktree
`E:\Immersive Portals - Copy\.claude\worktrees\particle-seam-regression-c2f79c`.
Committed tip at writing: `14489b2` (engine stages −1/0/1/2a). UNCOMMITTED: stage 2b (the band
painter v2 + the full diagnostic arsenal + the failed tie-break bias) — commit pending the
suite run at session close; if you find it uncommitted, the tree is still the source of truth.

**Read first:** `migration/SEAM_ENTITY_ENGINE_DESIGN.md` (the adversarially-judged engine
design — stages −1..4, the painter matrix, the constitution) and the memory entry
"Seam regression sweep 2026-08-10" (the complete 27-round ledger). Do NOT re-derive their
history. This document covers ONLY the open residuals and what has been PROVEN about them.

---

## 0.0 ★ ROUND 28 — THE TINT LAP LANDED. THE ARTIFACTS ARE ATTRIBUTED.

The §4.1 instrument was built and the user ran it. **Everything below in §1–§5 was written
BEFORE attribution; read this section first, because it refutes part of §2.**

The instrument: a `seamlessportals_DebugTint` uniform injected into every vanilla FRAGMENT
shader by a wrapper-`main()` rewrite (`ShaderCodeTransformation.transformFragment` + the
FRAGMENT branch of `ShaderManagerCompilationCacheMixin`). The §4.1 caveat was correct — the
claimed `vertexColor` anchor does not exist; the real 26.2 sources are `#ifdef`-variant-dense
with `discard` paths, so the only safe transform is *rename `main` → `seamlessportals_tintRealMain`,
append a wrapper `main()` that calls it and mixes the tint*. The tint RIDES THE CLIP `Snapshot`
through the existing `capture()/restore()` pairing (`FrontClipping.Snapshot` gained 4 tint
fields), so each painter's colour is scoped to exactly its own draws by the already-proven
mechanism — no new delivery machinery. Uploaded beside the plane at the `trySetup` chokepoint.
Lever `-PseamPainterTint`, byte-inert off. **Outcome asserted: 55 fragment shaders patched,
0 skipped, 0 GL/link errors**, including `core/entity`, `core/block` and `core/particle`.

Key: RED = main-pass real body · ORANGE = main-pass projection · BLUE = in-pass projection ·
GREEN = in-pass ambient · MAGENTA = band P1 · CYAN = band P2 · YELLOW = seam-cell block redraw
(`SeamClipRenderer` — a first-class painter nobody had inventoried until this session) ·
untinted = none of them.

### The user's lap-1 observations (FACTS, not hypotheses)

1. **AWAY crossing**, user at source side A, cart moving away — TWO simultaneous artifacts:
   - **1a.** the dest-side-A **HALF MODEL** bleeds into source side A, painted **BLUE**
     (= in-pass projection). A HALF, not a 1cm sliver — from a painter whose in-pass epsilon is
     an unconditional 1cm EXTEND, so a 1cm overhang cannot explain it.
   - **1b.** *simultaneously*, a thin **RED** sliver (= main-pass real body) sits directly ON
     the seam in source side A, as the model passes from dest side A to source B — even though
     `captureOuterClipping` RETREATS the body 1cm, which should guarantee red is ABSENT there.
2. **TOWARD crossing**, user at source side B, cart source A → dest B: a thin **CYAN** sliver on
   the seam is the entire bleed (= band painter piece 2).

### What this overturns

- **§2 attribution 5 is REFUTED.** It predicted the away bleed was *unconfined MAIN-PASS
  projection* admission — that would have painted ORANGE. It is BLUE. **Its staged fix
  (the camera-side ADMIT gate in `mainPassProjectionAdmitted`) must NOT be landed.**
- **§2 attribution 4's genre survives with its author named** (CYAN = P2), but the mechanism
  needs correcting: P2 keeps `{d ≤ +ADJ}` at the IMAGE plane — an *unconditional* 1cm extension —
  while the main-pass projection it is supposed to "identically repaint" is CAMERA-SIDE SCOPED
  (`FrontClipping.java:426-428`) and RETREATS whenever the camera is on the wrong side. P2's
  overhang is then not a repaint but naked new paint: an ADJUSTMENT-thick cross-section of the
  image poking through the seam — precisely the round-4 artifact, re-created by the painter
  that was never given the same scoping. (Panel-verified in `wf_db290ef3-7c7`.)
- **"The painter provably changed nothing" (§1) no longer holds.** P2 is visibly authoring — or
  at minimum overpainting — the toward sliver. Lap 2 (`-PdisableSeamBandPainter` + tint)
  discriminates: sliver VANISHES ⇒ P2 authors it; a differently-coloured sliver REMAINS ⇒ P2
  only overpainted, and the remaining colour names the true author.
- **1a and 1b happen in the SAME frames**, so a common root (one twin-face/orientation-luck
  resolution error, or one sign error) is a live hypothesis for both.

### Other findings landed this session (workflow `wf_8b66fe02-bd6`, 6/10 agents)

- The **seam-cell block redraw** (`SeamClipRenderer`) is a per-pass painter that was never in
  the inventory: seam cells are excluded from compiled meshes and re-tessellated + drawn every
  pass, the dest arm under the −ADJ EXTENDED ambient clip. Now tinted YELLOW.
- **Raw `glPolygonOffset` does NOT survive** 26.2 per-draw pipeline application (pipelines carry
  depth bias natively; `applyPipelineState` installs-or-force-disables per switch). `glDepthRange`
  and stencil func/op survival re-confirmed on the true runtime jar. Two viable routes if a
  decal-style bias is ever wanted: derived pipelines carrying the bias, or a per-draw re-arm at
  the existing `trySetup@RETURN` chokepoint via the public `GlStateManager._polygonOffset`.
- **The outer retreat's original "wash" evidence is a tombstone recollection only** — no round
  number, screenshot or log line anywhere in the record — and every painter it was added to
  defend against has since been deleted by verdict work. Retreat-deletion is a cheap live lap.
- **After any nested portal pass, the outer pass's clouds/weather draw with clipping DISABLED**
  (`SecondaryWorldRenderCore.java:1207` restores nothing before the outer late passes).
- The in-pass extension **does** reach the GPU on artifact draws (reachability audit negative) —
  so the tail sliver is a submission/verdict gap or a non-entity painter's pixels, never a
  delivery failure. The `seamBand` dead-code fact is confirmed (`CrossPortalEntityRenderer:571-574`).

---

## 0.1 ★★ ROUND 28 LAP 2 — TWO ARTIFACTS DIE, THE THIRD IS MECHANISM-PROVEN

Lap 2 = tint ON, `-PdisableSeamBandPainter` (one variable). User's verdict:

- **The RED sliver is GONE. The CYAN sliver is GONE.** Both were caused by the band painter
  (stage 2b, **uncommitted**). Its §2.5 "identical repaint ⇒ invisible by construction" claim is
  now **empirically false**: it authors visible pixels.
  - The CYAN one is piece 2, unambiguously.
  - The RED one is a **GENUINE CROSS-PAINTER PERTURBATION — an OPEN DEFECT.** The "it was
    really P1's magenta, misread" reading was put to the user and **REFUTED by direct
    testimony**: they confirmed it was RED on lap 1 and gone on lap 2, and they had
    distinguished red / blue / cyan correctly in that same report. So the band painter caused
    pixels carrying the *main-pass body's* tint to appear — which §2.5 says is structurally
    impossible (its draws are bracketed capture→restore, its `glDepthRange` is balanced, and it
    runs strictly after the main pass, so it should only ever overwrite).
    **Candidate mechanism (UNVERIFIED, ranked first):** piece 1 draws the real body keeping
    `{d ≥ −ADJ}` with **depth writes ON** under the forward `glDepthRange` bias, so it writes
    depth into the ±ADJ band — the one region it uniquely covers. A main-pass entity phase that
    executes LATER than the painter (outline / late translucent phases are registered with the
    RED main-body snapshot by `PerEntityClipBracket.registerPhases`, which registers *every*
    phase the entity touched) would then pass the depth test in that band where it previously
    failed, and paint RED exactly there. Removing the painter removes the depth write, and the
    sliver with it — which matches lap 2 exactly.
    **Verification if pursued:** tint lap with the painter ON and `-PseamBandBias=0`, or a lap
    with P1's depth write suppressed; RenderDoc pixel history at the sliver settles it outright.
    **GATE: stage 2b must not be re-enabled until this is named**, because it means the painter
    can silently change what *other* painters put on screen — the property its whole
    "additive, zero regression surface when levered off" justification rests on.
- **The BLUE bleed REMAINS on BOTH directions** — away = a half model; toward = a smaller
  leading-edge bleed (cow's face + minecart tip) that vanishes quickly. **Exactly one defect
  remains, and it is the in-pass projection.**

### The mechanism, MEASURED from the lap-2 log (not inferred)

In-pass projection draws, paired as (projecting face, pass): **41→40: 9156** · 40→41: 58 ·
41→41: 36 · 40→40: 28. The clip each face carries — same plane point, **opposite normals**
(the co-located twin pair):

```
face 41 -> clip=(34.5, 120.0, -58.5)/(+1.0, 0.0, 0.0)   9382 draws
face 40 -> clip=(34.5, 120.0, -58.5)/(-1.0, 0.0, 0.0)    126 draws
```

**98.7% of in-pass projection draws thread ONE TWIN'S clip into the OTHER TWIN'S pass.** Face 41's
clip keeps the half-space opposite to the one face 40's window shows, so the half that appears is
exactly the half that should have been cut. That is the blue bleed. It is the **fourth
orientation-luck defect of this arc** (rounds 14/16/17 are the same family: a decision keyed to
*which* co-located face got picked rather than to the pass's own geometry).

### The trap in fixing it

The cross-twin draw **exists on purpose**: round 16 added it because the in-pass
`isFlippedPortal` check silently skipped the back-image and the emerged half vanished from the
window. Naively culling those 9156 draws risks reopening that hole. Any fix must state what
draws the legitimate emerged image in each pass afterwards, per the §2.1 matrix. The design's own
answer is §1.3's **side-agreement test** `n_innerImage · passKeptNormal > 0` — implemented as
stage-1 shadow delta (b/c) and never flipped, because the capped 300-line shadow logged only
*permissive* divergences (`legacy=false(isHidden) new=true`); the live evidence is the
*restrictive* direction the cap may never have shown. Fix panel: `wf_d5679fe3-86f`.

Logs preserved on C: scratchpad — `tint-lap-1.log` (57MB, painter ON), `tint-lap-2-nopainter.log`
(31MB, painter OFF). ⚠ **The E: drive hit 0 bytes free during this session**; 94MB of regenerable
game logs were removed to unblock writes. It needs real attention before the next long lap.

---

## 0.2 ★ ROUND 29 — THE FIRST-PERSON OWN-HEAD DEFECT + TWO LIVE HAZARDS

**User report:** crossing the seam in first person, the view is obstructed by the INTERIOR of
their own head model — "like the head model is copying across the seam and landing where my
camera is." Tint answer: **UNTINTED** (normal skin colours).

### RANK 1 mechanism — `SeamBandPainter` piece 1 (panel `wf_1f2ba1bf-e80`)

`SeamBandPainter.java:154-155` submits P1 with `offset == Vec3.ZERO`, and `drawPiece:229-233`
translates by `state.pos − camPos` where `camPos = mc.gameRenderer.mainCamera().position()`
(`:82`). **In first person `camPos` IS the eye**, so the translation is `−eyeOffset ≈ (0,−1.62,0)`:
the model's feet land 1.62 blocks below the camera, putting the camera *mathematically at eye
height inside the head cube* — not near it. The far wall of the head sits ~0.25 blocks out, well
beyond the 0.05 near plane, and player layers draw on no-cull entity render types, so interior
faces rasterize. Fires only on a LITERAL straddle (`:101-104`, front ∧ back pieces both nonempty)
— matching "when I am on the seam" and nothing else in the tree. "Copying" is literal: P1 is a
second extract+submit of the same entity in the same frame (the painter's own "IDENTICAL-REPAINT"
doc, `:28-46`).

**It bypasses BOTH guards, by construction:**
- vanilla's own-player suppression is an **extraction** gate (`LevelExtractor.extractVisibleEntities:253-255`,
  `entity != camera.entity() || camera.isDetached() || …`) — `drawPiece` calls
  `dispatcher.extractEntity` DIRECTLY, so it never runs;
- IP's `shouldRenderEntityNow` never runs either — the painter draws through its OWN dispatcher
  (`PerEntityClipBracket.drawImmediateClipped` / `getOrCreateOwnDispatcher`);
- and IP's dedicated defence `shouldRenderPlayerNormally` is **dead code** (zero call sites) —
  see [[ported-ip-helpers-never-wired]]. Its body is literally the guard for this symptom.
The painter's loop (`:85`, over `collidedEntitiesView()`) contains no `LocalPlayer`,
`isFirstPerson`, `getCameraEntity` or `isDetached` test anywhere in the file, and the player IS
in that set (`CrossPortalEntityRenderer:135-144` excludes only `Portal`).

**NOT YET PROVEN — the live-status question.** RANK 1 requires the painter to have been RUNNING
when the user saw it. The tint answer was UNTINTED, but P1 was MAGENTA at that moment, so a P1
head should have read magenta. **Discriminator: does the head still appear with the painter
OFF?** Appears ⇒ RANK 1 REFUTED. Absent ⇒ RANK 1 confirmed.

### RANK 2 — a latent one-line asymmetry worth fixing regardless

`MyGameRenderer.renderWorldNew:216-228` does `WorldRenderInfo.pushRenderInfo(...)` →
`switchAndRenderTheWorld(...)` → `popRenderInfo()` with **NO try/finally**, while its sibling
`renderWorldFullPipeline:527-534` HAS one. One swallowed throw latches
`WorldRenderInfo.isRendering()` true for the rest of the session, which makes
`shouldRenderPlayerDefault()` (`CrossPortalEntityRenderer:629-656`) permanently true on a
same-dim rig — and that forces `Camera.isDetached()` true via `MixinCamera:102-107`. Different
symptom from RANK 1, but a real defect. **Follow-up, not bundled into the current lap.**

### ⚠ HAZARD 1 — the bare-flag footgun (affects every future lap)

`fabric/build.gradle:196` (and the twin row) tests `project.findProperty('x') == 'true'`. A
**bare** `-PdisableSeamBandPainter` sets the property to the EMPTY STRING, the row does not
fire, and **the painter RUNS**. Every doc in this tree writes the flag bare
(`SEAM_BAND_HANDOFF.md:63,92,238`; `SEAM_ENTITY_ENGINE_DESIGN.md:148,250`). Lap 2 proves the
lever *can* work — red and cyan vanished — so `=true` was passed correctly there. **Always write
`=true` explicitly.** Note also that `runclient.bat` passes NO flags at all, so a user-launched
client runs with the band painter ENABLED and would reproduce the head defect.

### ⚠ HAZARD 2 — P1 is WHITE now, not magenta

`SeamTint.java:107-111` — P1 was recoloured MAGENTA → WHITE (round 29) precisely because magenta
`(1,0,1)` and main-body red `(1,0.1,0.1)` are indistinguishable on a thin sliver. Magenta is now
unused; **magenta on screen would mean a stale jar.** Any briefing that says "look for magenta"
sends the reader to the wrong file.

**Timeline note:** the round-29 side-agreement edits and the WHITE recolour have NEVER been
live-run as of this writing. A painter-ON lap (the point of the WHITE recolour) would reintroduce
the head defect.

---

## 0. WHERE THIS STANDS

The engine (design stages −1, 0, 1, 2a) is LANDED and live-proven: the crossing anchor
(epoch-guarded unit-atomic FLIP — 23/23 flips, 23/23 rider-echo no-ops, 23/23 clean closes in
its proving lap), the SeamCrossingRule module (all seam verdicts behind one API, forbidden-
inputs constitution), evidence-flipped verdict deltas (a), (d), (e). Of the 21-artifact ledger,
everything EXCEPT the boundary-band family is fixed and user-confirmed: crossings are smooth,
riders solid, no couple-seconds ghost, no cowless carts, no double-transform flings, no
window flash.

**OPEN — the user's standing report (unacceptable, zero-tolerance):**
1. **Away-crossing tail-end clip/sliver** — the last bit of the body crossing shows a missing
   sliver at the plane.
2. **Wrong-side seam-cell bleed** — slivers (sometimes more) of the body visible on the wrong
   side of the plane; both directions, angle-dependent, worst toward/at the seam cell.
(The five-artifact breakdown with per-artifact attribution and confidence lives in the
wf_8a9d68af-951 verdict, quoted in §2. Artifacts 2/3 of that list may be the same defects seen
in other phases.)

---

## 1. WHAT IS PROVEN (do not re-litigate; every item has evidence)

The proof chain on the band painter (stage 2b), built over three instrumented laps:

| Fact | Evidence |
|---|---|
| The painter's draws EXECUTE (both pieces) | outcome probes `BAND-P1/P2 drawn` + zero strike lines in intact session logs |
| The draws LAND on screen | beacon lap 1: +2Y clip-DISABLED beacons visible |
| The exact-plane clip converter is CORRECT | beacon lap 2: +4Y clip-ENABLED beacon visibly cut in half at the offset plane |
| The tick-box gating gap is NOT the cause | artifacts identical at deliberately slow crossing speed |
| The transform context at the post-pass slot is CORRECT | static proof: the ambient stack holds R there; RenderType.prepare snapshots per draw (verdict wf_8a9d68af-951 §1b, mc262-ref citations) |
| A constant 1e-5 forward depth bias does NOT fix the artifacts | this session's final lap ("still see tail end clip, still see wrong side bleed") |
| The window pass unconditionally overdraws main-pass pixels in-aperture | rounds 12+18 live failures + RendererUsingStencil:362-535 (stencil write → depth clear → restamp) |
| Raw GL color/depth MASKS do not survive per-draw pipeline application; glDepthRange and stencil func/op DO | v1 band painter live post-mortem + RendererUsingStencil:59,79,447 |
| All five artifacts PREDATE the band painter | painter provably changed nothing (see above) |

**The analytic dead-end, stated honestly:** after three multi-agent verdicts
(wf_516e0df3-e27, wf_9b00d67b-a0e, wf_8a9d68af-951), every layer of the painter is
individually proven good, and the artifacts persist. The bias failure adds a NEW fact: the
artifact pixels' stored depth beats the band fragments by MORE than 1e-5 — at shallow view
angles a 1cm world offset is a large window-z difference, so "float-rounding tie" was wrong
for the visible cases; the deficit is geometric and angle-scaled. Consequence (the "depth
wall"): fragments geometrically behind the plane can NEVER win a depth test against the
restamped quad depth at any bias that preserves occlusion. If the missing sliver contains
behind-plane content in-aperture, no post-pass depth-tested painter can ever fill it —
only in-pass (stencil-confined) painters or a stencil-scoped depth-override with CONTENT
clipping can. And the outside-aperture story ("far background ⇒ band wins trivially") is
CONTRADICTED by the bias lap — meaning we do not actually know where the artifact pixels are
relative to the aperture, or what painted their stored depth. **The analysis is exhausted;
only pixel-level attribution can carry this further.**

---

## 2. THE STANDING ATTRIBUTIONS (verdict wf_8a9d68af-951 Part 3 — confidences as adjudicated)

1. Away tail sliver — HIGH: the both-retreat unowned ±ADJ slab (outer retreat
   FrontClipping:347 + camera-side retreat :426-428), outside the stencil-confined window
   cover. (But see §1's contradiction: the painter should have filled the outside-aperture
   part and did not — the attribution's LOCATION half is now in doubt, not its mechanism.)
2. Toward nose clip (~1s) — MEDIUM, phase-ambiguous; falsifiable via `PROJ masked/gated`
   lines during the nose-clip second.
3. Toward back-edge hairline — MEDIUM-HIGH; note the verified seamBand dead-code fact
   (the only submitProjectedEntityClipped caller passes false; the retreat arm is unreachable).
4. Toward wrong-side sliver — MEDIUM-HIGH genre: P2's camera-side EXTEND unpaired when no
   retreated main body drew (pre-flip). Fix candidate staged: pair-scoped extension.
5. Away growing ghost → full model — MEDIUM-HIGH signature: unconfined main-pass P2 admission
   (= backPieceExists) growing with the crossing; ends at the flip. Fix candidate staged:
   camera-side ADMIT gate in mainPassProjectionAdmitted.

Fixes 4/5 were deliberately NOT landed: the verdict gates them on tint confirmation
("post-tint-confirmation"), and the tint was never built. Do not land them blind.

---

## 3. THE DIAGNOSTIC ARSENAL (all in-tree, lever-gated)

- `-PseamCartProbe` — the entity probe family (rider-family watched, RPC arrivals unfiltered,
  outcome-side `BAND-P1/P2 drawn/FAILED` with the fed snapshot, `BAND-GL` truth line ~1/s,
  every seam verdict logs its reason).
- `-PseamBandBeacon` — the landing beacon: pieces at +2Y clip-disabled AND +4Y clip-enabled
  (the two-tier ladder; gate widened to pinned() in beacon mode).
- `-PdisableSeamBandPainter` — stage-2b kill switch.
- `-PseamResolver=shadow` — the verdict-divergence shadow logs (`SHADOW-DIVERGE`), including
  the parked delta (b/c) whose divergence direction flips per world config.
- Suite: `.\gradlew.bat :fabric:runCrossingGametest -PapertureCensusProbe=true
  -PapertureTeardownTest=true -PseamFractionalProbe=true`; green = the `ALL LEGS PASS` line in
  THAT run's own log, never exit code.

## 4. WHAT WAS NEVER BUILT (the missing instruments — build these FIRST)

1. **PER-PAINTER TINT** (verdict Part 4.2, mechanism verified in-tree, one correction): a
   debug uniform in the injected entity shaders (the ShaderCodeTransformation /
   GlCommandEncoderClipMixin / location-cache triple — the SAME proven pipeline as the clip
   uniform), set per painter: main body / main-pass PROJ / in-pass body / in-pass PROJ / band
   P1 / band P2 each a distinct color. ONE user lap then attributes EVERY artifact pixel to
   its painter by color. CAVEAT from the verdict: the claimed `vertexColor` injection anchor
   does not exist — derive the injection point from the actual patched entity shader sources
   (the launch log prints every patched program).
2. **RenderDoc frame capture** (never even discussed with the user until now): one captured
   frame of the tail-sliver artifact answers EVERYTHING — which draw call owns every pixel,
   the stored depth at the artifact pixels, the stencil state. The user runs the client under
   RenderDoc, F12 at the artifact moment. This is the definitive instrument and it costs one
   evening. Strongly consider making this the FIRST move.
3. **A bias LADDER as a cheap discriminator** (if RenderDoc is unavailable): re-run the
   depth-range bias at 1e-3 and 1e-2 (diagnostic only, never shippable). If the sliver closes
   at some rung, the deficit is measured, and the geometry (angle-scaled offset vs the quad
   restamp) is confirmed and quantified.

## 5. FRESH-DIRECTION CANDIDATES (deliberately outside the circle we have been walking)

The next session should treat these as first-class hypotheses, not afterthoughts:

- **Question the band premise itself.** Nobody has ever MEASURED the artifact: is the missing
  sliver actually ±ADJUSTMENT (2cm) thick, or is it wider/other-shaped? A tint/RenderDoc lap
  measures it. If it is wider than the epsilon band, the entire ±ADJ ownership frame is
  misdirected and the defect is elsewhere (e.g., the retreat arithmetic applying somewhere
  unexpected, a pass drawing with a stale clip, the window quad's own geometry).
- **Question the painter-set inventory.** The wrong-side bleed might not be entity paint at
  all: candidates never ruled out include the portal quad's own draw, the dest-pass terrain
  redraw of the seam cell (the block-level seam machinery), and particle/BE draws. Tint only
  colors entity draws; RenderDoc sees everything.
- **Remove the retreat instead of covering it.** The whole band saga exists because the outer
  clip retreats −ADJ to prevent the r-era z-fight ("the wash"). Fresh idea: keep the outer at
  correction 0 and kill the fight differently — glPolygonOffset on the projection draws
  (survives pipelines? verify), or a sub-epsilon retreat (1e-4 instead of 1e-2 — possibly
  invisible while still breaking coplanarity), or accepting the fight on the 1-2px line and
  measuring whether it is even visible on 26.2 (the original wash evidence predates several
  renderer changes). Any of these deletes the band problem instead of solving it.
- **The in-pass extension as the only in-aperture owner is FORCED** (the depth wall, §1).
  If the sliver is in-aperture behind-plane content, the fix is in the PASS's painters
  (the in-pass body/projection extension arithmetic), not in any post-pass painter. The
  round-11 arrangement owns that band correctly-by-ordering; check whether its extension is
  actually reaching the GPU on the artifact draws (the seamBand dead-code fact in §2.3 is
  suspicious — the in-pass PROJ extension comes from the isRendering branch default, verify
  it live with tint).
- **Cross-check with fuse-view.** `portal.fuseView` skips the depth restamp entirely
  (RendererUsingStencil:283-285). A diagnostic lap with the seam faces set fuseView=true
  (if visually tolerable) removes the depth wall — if the sliver closes, the restamp
  interaction is confirmed as the mechanism in one lap.

## 6. HARD RULES (unchanged + new, all earned)

- The user's mandate, verbatim: "no quick cheap fixes, all fixes must be well researched and
  based on evidence" and "there can be no compromises... it must all be completely
  continuous." NO fix lands before its pixel-level evidence.
- One variable per lap. The beacon ladder worked because of this; the epsilon rounds failed
  without it.
- Assert the outcome, not the request (outcome probes after draws; a request line convinced
  us the painter worked for two rounds).
- Raw GL state around FeatureRenderDispatcher draws: only clip planes (via the store),
  glDepthRange, and stencil func/op survive. Color/depth masks and depth func are per-draw
  pipeline-owned. (v1 post-mortem; verdict-verified.)
- Suite green before any commit; `ALL LEGS PASS` in that run's own log.
- Every deviation from IP gets the citation-comment treatment in code.
- Windows/PS5.1: Edit tool or .NET UTF8 only; fable/opus subagent tags; javap the deobf jar
  before new mixin targets; @Unique initializers must not make cross-class static calls.

---

## 9. ★ DEFERRED: CROSS-DIM PARITY (user decision 2026-08-20 — "we will worry about cross dim after we fix same dim, just add it to documentation")

**Status: OPEN, NOT STARTED, deliberately deferred.** The user reports real discontinuity crossing
ow↔nether (and expects ow↔end, nether↔end to share it). Do NOT start this until the same-dim
residuals (rider clip, shadow, deceleration) are closed.

**Why it is not automatic — the project's own precedent.** Every seam entity-crossing fix in this
arc was developed and verified on the SAME-DIM-FAR rig. Design §7 Q5 explicitly deferred cross-dim
and stated no design may claim ledger entry 1 "by construction" cross-dim until a live round runs.
That round never ran. And this codebase has already been bitten by exactly this shape: the (e)
DEFECT-B fix was written at `ClientTeleportationManager.moveClientEntityAcrossDimension`, and the
IDENTICAL defect survived on the same-dim path for weeks, because
`fromDimension != toDimension` gates them into separate code. **Assume nothing carries over;
prove reachability per fix.**

### What is already established (2026-08-20, before the deferral)

- **The VERDICT layer looks dimension-agnostic.** A grep for dimension-conditional branches
  (`getDestDim() ==`, `dimension() ==`, `sameDim`, `fromDimension`) across
  `CrossPortalEntityRenderer`, `SeamCrossingRule`, `SeamStraddleBracket` and `SeamCartContinuity`
  found NONE. Encouraging, not conclusive.
- **The RENDER path DOES fork:** `SecondaryWorldRenderCore:1159` `renderPortalEntities` (cross-dim)
  vs `:1164` `renderPortalEntitiesSameDim`. `PerEntityClipBracket`'s javadoc claims draw sites in
  BOTH — unverified.
- **THE MASTER GATE is the first thing to check.** `SeamCartContinuity.isSeamContinuous(portal)`
  looks up a `SeamRegistry.SeamCell` and requires a binding with
  `b.isMirrorable() && b.seamContinuous()`. EVERY seam entity verdict begins with it. Those rules
  were written for same-dim obsidian pairs under the EXACT-ONLY alignment decision (2026-07-26),
  and ow↔nether carries an 8:1 coordinate scale. **If this gate returns false cross-dim, the entire
  engine is silently disarmed there and that alone is the reported discontinuity.** Check it first;
  it is one predicate and it could explain everything.
- **R30 (the visual sweep) may be a no-op — or worse — cross-dim.** `sweptBox` unions the current
  box with the box at `McHelper.lastTickPosOf` (`entity.xo/yo/zo`). Cross-dim moves run through
  `moveClientEntityAcrossDimension`. If that path sets `xo == x` at arrival, the sweep collapses to
  the post-tick box and R30 does NOTHING cross-dim. If it leaves `xo` at the DEPARTURE coordinates
  in the other dimension, the swept box spans two worlds — far worse than the original defect.
  Determine which, and guard it.
- **Other cross-dim-specific hazards:** two live `ClientLevel`s (does `collidedEntities` / the
  anchor / the rider fan survive a mid-transfer unit?); the NO-HISTORY FLIP path (§3.2 — cross-dim
  client entities SPAWN at the destination rather than crossing, so the anchor has no history; the
  design specifies open-post-flip + nearest-to-server reconciliation, reachability unverified); and
  a KNOWN unfixed cross-dim defect from the record — `REBASE via portal 1999
  visual=(716.28,173.06,-127.5) server=(35.28,118.06,…)`, the rebase transform applied in the
  WRONG FRAME, writing garbage client visuals.

**HOW TO START THIS, per the user (2026-08-20):** do NOT open with a speculative code audit — one
was launched and the user STOPPED it as "pointless" without a symptom to aim at. They will supply the
EXACT observed cross-dim behaviour when the time comes, and that narrows the search. Wait for it.
The suspicions above are starting points to CHECK against a reported symptom, not a work plan.
(This is the same discipline that cracked the same-dim arc: the tint lap worked because it measured
a symptom; every blind panel this session had its framing overturned by the next screenshot.)

**Hard constraint when this is picked up:** same-dim crossing is USER-CONFIRMED GOOD as of
2026-08-20 ("head is gone, no blue bleed either direction"; tail clip much improved by R30).
No cross-dim change ships without a lever bounding its same-dim blast radius.
