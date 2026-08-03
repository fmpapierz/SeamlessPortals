# MB / BLOOM / SEAM HANDOFF — three user-reported edge artifacts

**Status: OPEN, NOT STARTED.** Queued by the user 2026-08-02, immediately after the recursion arc and
the C2 close-out. Branch `iris-on/is5-shadow`, worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow`.

All three are **edge artifacts around the portal window**, all involve iris post-processing, and two
of the three involve Motion Blur. They may share a mechanism or may be three separate bugs — **do not
assume either.** The first job is to establish which.

---

## §1 THE THREE REPORTS (user, verbatim)

### A — third person, player silhouetted against a portal
> "third person issue with motion blur when player is viewed against a portal in the background,
> there is a border/edge around the player that looks like stuff/lighting/color from the portal
> window."

So: camera in third person, the PLAYER model in front of a portal window. A halo/border traces the
player's outline, carrying colour or light that belongs to the portal view behind them.

### B — bloom sliver at the frame edge (MB ON **and** OFF)
> "bloom issue with/without MB, there is a lighting around a very thin sliver of the edge of portal
> window when in obsidiant frame/touching/clipping blocks."

A thin band of light around the portal window's edge where it meets the obsidian frame. **Reported as
present with Motion Blur both ON and OFF**, which distinguishes it from A and C and points at the
bloom chain rather than the velocity chain.

### C — MB makes the seam visible against the frame
> "a MB issue where when a portal window in obsidian frame/touching/clipping blocks, the portal window
> kinda moves or something on the obsidian frame in a way that makes the seam visible against the
> obsidian frame, maybe there is terrain showing through the frame at the seam with MB on or
> something like that."

With MB on, the window appears to SHIFT slightly relative to its frame, opening a visible seam —
possibly showing terrain through it. The user is explicitly unsure of the mechanism; treat the "terrain
showing through" as a hypothesis to test, not a finding.

---

## §2 WHAT IS ALREADY IN THE TREE (read before designing — do not re-derive)

- **`IrisDestPrevCamera`** — the IS5-MB fix that closed the window-wide smear
  (`MB_SMEAR_HANDOFF.md` §00, user-confirmed *"FINALLY NOT BLURRY"*). Per composite chain it
  remembers `cameraPosition` + modelview/projection for one frame and adopts them from the nearest
  camera the same program held last frame. **A/B: `-PdisableIrisDestPrevCamera`.** Note its arm point
  in `doRenderPortal` is a documented NO-OP — the correction is keyed on the camera each composite
  chain carries, not on any portal bracket.
- **`IrisBloomApertureMask`** (C3-BLOOM) — masks colortex0 to the aperture footprint after its last
  writer and before the bloom-tile gather, consumed inside iris's `CompositeRenderer.renderAll`.
  Armed per portal in `doRenderPortal`, gated on `IPGlobal.isIrisBloomApertureMaskActive()`.
  **This is the most likely suspect for B**, and its arm/disarm is now nested under recursion — see §4.
- **`MB_SMEAR_HANDOFF.md`** — the closed smear arc. §1c is the original "bloom ring" commission,
  never executed; B is probably that same observation, now better characterised by the user.
- **`MB_SMEAR_VERDICT.md`** — **read this one carefully.** It records two submitted mechanisms both
  being REFUTED on their decisive step, in opposite directions. It is the clearest local example of
  how easy it is to be confidently wrong in this subsystem.
- **`IrisCompositeCensus`** (`-Dseamlessportals.compositeCensus`) — labels composite binds by portal
  window and layer. Already built; the instrument of choice for "which pass is doing this".
- The **stamp** (`IrisCompatPaste.stampPortalArea`) writes the portal-shaped area into the deferred
  buffer with depth write ON, and its declared compare is `GREATER_THAN_OR_EQUAL`. The
  window's edge geometry is the aperture mesh from `ViewAreaRenderer`.

---

## §3 FIRST INSTRUMENTS AND THE DISCRIMINATORS THAT MATTER

**Do not start by theorising. Establish these four facts first — each is one live run.**

1. **Does A survive `-PdisableIrisDestPrevCamera`?** If the player-halo persists with the MB
   correction off, it is not the velocity chain and the whole IS5-MB line is irrelevant to it.
2. **Does B survive turning Motion Blur off in the pack?** The user says yes. CONFIRM it, because it
   is the single most valuable discriminator in the set: it separates B from A and C entirely and
   points at bloom/composite rather than velocity.
3. **Does B survive `-PdisableIrisBloomApertureMask`** (or whatever the live lever for C3-BLOOM is —
   check `IPGlobal.isIrisBloomApertureMaskActive`)? If the sliver vanishes, the mask's footprint is
   off by a pixel or two at the aperture edge and this is a coverage bug, not a bloom bug.
4. **Is C the same artifact as B seen in motion?** Both are at the frame/window boundary and both
   involve blocks touching the aperture. If disabling the bloom mask also kills C, they are one bug.
   Test before treating them as two.

**A fifth, cheap and worth doing early:** all three are described at the portal EDGE where geometry
meets the frame. Check whether they reproduce on a portal in OPEN AIR with no blocks touching the
aperture. If B and C need the obsidian frame, that is a strong constraint on the mechanism.

---

## §4 HAZARDS THIS ARC INHERITS FROM THE RECURSION WORK (2026-08-02)

The renderer changed substantially today. Anyone debugging these three must know:

- **Portal recursion now goes to `maxPortalLayer` (settable, default 5, user runs 10) shaders-ON**,
  via `SecondaryWorldRenderCore.maybeRunNestedPortalLayer`. Composite chains now run PER NESTED LAYER.
  `IrisBloomApertureMask.armed` is a SINGLE SLOT and now nests — the inner layer's disarm retires the
  outer's arm. **That is a live accounting defect flagged in review and not yet fixed**; if the bloom
  mask is implicated in B, fix the nesting first or the measurements will lie.
- **`IrisCompositeCensus` and the other 1 Hz probes are NOT layer-keyed.** An outer pass and an inner
  pass share the same rate limiter, so a deep-layer sample can be filed under the outer window's
  identity. Layer-key them before trusting any per-window attribution (this was raised in review as
  Stage 1(b) and deferred).
- **Two frame-scoped probes were gated to layer 0** (`SeamDestContentProbe`,
  `SeamHandStageDiff.stageC`) precisely because of the above. Others were not audited.
- The **terminal portal at the recursion bound is now NOT DRAWN** shaders-OFF (IS5-TERM, `ecf6ee5`).
  If an artifact appears at the deepest visible layer, check that first.

---

## §5 THE DISCIPLINE (binding — this project's scar tissue)

Everything in `THIRD_PERSON_CROSSDIM_HANDOFF.md` §9.3 applies. The short form, all of it earned:

- **Measure at the draw, never infer.** `GlCommandEncoder.trySetup` RETURN is the only trustworthy
  slot for GL state. Bytecode is not ground truth — the tree contains a measured counter-example
  (`clipDepthMode` reads `NEGATIVE_ONE_TO_ONE` at the draw while `GlDevice` demonstrably calls
  `glClipControl(…, ZERO_TO_ONE)`).
- **Verify the instrument before believing the instrument.** In the last arc alone: a probe sampled
  after the state had unwound (always printed 0), a grep for an overlay that writes no log line
  (always printed 0 occurrences), a probe pattern so broad it matched every mod's lambdas, and a
  counter that was never called on the path that mattered. Ask of every probe: *what input would make
  this print the guilty answer?*
- **A leg without its landing proof is VOID, not a refutation.** Check the `RUN CONFIG` block and
  verify levers **on the live process** (`Get-CimInstance Win32_Process`, match the worktree path +
  `KnotClient`). A lever that silently never reached the JVM has voided legs here repeatedly.
- **Every fix A/B-proven in BOTH directions**, with a lever that reproduces the defect on command.
- **ONE VARIABLE PER LEG.** Broken more than once in this project, each time costing a wrong
  conclusion that reached a commit message.
- **Refuting a stated mechanism is not refuting the claim.** A prediction can be right for a reason
  its author never named — re-derive whether the conclusion survives by another route.
- `.\gradlew.bat :fabric:runCrossingGametest` before every commit; **capture enough output to read the
  leg lines** (a `tail -n` filter once discarded them and produced a meaningless gate); push every
  stage commit; `git add` explicit file lists only; Java cleanup by own-project PID only, never
  `gradlew --stop`.

---

## §6 STATE OF THE TREE AT HANDOFF (2026-08-02)

Branch `iris-on/is5-shadow`, tip `ba256ea`. 23 commits this day, all pushed, all live-confirmed.

**Shipped DEFAULT ON, do not disturb without an A/B:** recursion shaders-ON (`-PdisableIrisPortalRecursion`),
cross-view recursion (`-PdisableCrossViewRecursion`), terminal portal not drawn
(`-PdrawTerminalPortalAsBlack`), full depth for nested portals (config toggle), the portal-chain chunk
loader, the tied window/loading ranges, and — new — **one `dontinline` flag replacing the six C2
`exclude` rows** (`migration/C2_JIT_PROTECTION.md`, revert via `-Pc2LegacyExcludes`).

**Config now lives in `IPConfig`** (`config/immersive_portals.json` + the Cloth screen). Settings added
to `SeamlessConfigScreen` are UNREACHABLE at the default flag — that screen only opens when
`EntityPortalsFlag.isOn()` is false. This mistake has been made once and reverted.

**Known open, unrelated to these three:** a `GL_INVALID_OPERATION: Invalid format` from iris
`RenderTargets.copyPreHandDepth` during a nested cross-dim render (3 occurrences, only at nether
pipeline creation, parked); `maxPortalLayer` is never synced to dedicated servers.

---

## §7 LEG 1 — RUN, 2026-08-02 23:2x. THE SET IS ONE FAMILY, AND IT IS NOT WHAT THE REPORTS SAY

**Configuration (MEASURED from `shaderpacks/ComplementaryReimagined_r5.8.1.zip.txt`, mtime 22:51,
matching the last `Using shaderpack:` reload at 22:51:32 — so this is the live state, not a default):**

| option | live value | pack default | source |
|---|---|---|---|
| `BLOOM_ENABLED` | **`-1` = OFF** | `1` | `shaders/lang/en_US.lang:644` `value.BLOOM_ENABLED.-1=OFF` |
| `MOTION_BLUR_EFFECT` | `1` = ON | `-1` (off) | `shaders/lib/common.glsl:146` |
| `MOTION_BLURRING_STRENGTH` | **`2.00` (slider max)** | `1.00` | `shaders/lib/common.glsl:147` |

`composite4.glsl:65` guards the entire `BloomTile` gather behind `#if BLOOM_ENABLED == 1`. **With the
user's live options the bloom gather is not in the compiled shader at all.**

### §7a The observations (user, verbatim-derived — authoritative, outrank every inference here)

| # | observation |
|---|---|
| 1 | **B (sliver) is ROTATION-driven.** Present only when the camera is whipped around. Only *very slight* on the **VERTICAL edges** when strafing. |
| 2 | **B survives Motion Blur OFF** — "even smaller, even slighter sliver on the vertical edges". MB makes it *far* worse but does not create it. |
| 3 | **B survives pack Bloom OFF** — it was observed in the table's configuration above. |
| 4 | **A (player halo) is ROTATION-driven and 100% MB.** Present when whipping the camera, absent when strafing, **gone entirely with MB off**. |
| 5 | **OPEN AIR: the SLIVER is absent — but the PLAYER HALO IS NOT.** A needs no adjacent geometry; B does. |
| 6 | **C is not a separate report.** "The window shifts against its frame" is observation 1 seen during strafe. Discriminator 4 answered: C ≡ B. |
| 7 | **The sliver carries the PORTAL VIEW's colour**, i.e. destination content — not frame-block colour and not a generic white band. (User: "i think".) |
| 8 | **There IS a separate bloom artifact, in the same edge area.** Report B was two things wearing one description. |

### §7a-bis ★ CORRECTION TO MY OWN FIRST READING (same session, before it reached any code)

The first draft of §7b below unified A and B as one mechanism on the strength of "open air = no issues".
**That was wrong, and the user corrected it within the hour: the player halo reproduces on an open-air
portal.** So the adjacency constraint applies to B ONLY, and it is the thing that SEPARATES them:

- **A** needs a portal and a player in front of it. No adjacent geometry. Pure MB (dies with MB off).
- **B** needs geometry touching the aperture. Survives MB off. Carries destination colour.

They are two bugs that happen to share a trigger (fast rotation) and a location (the window boundary).
Recorded because the unification was written down before it was checked — exactly the failure this
project's discipline exists to catch, and it was caught by asking the user rather than by any analysis.

### §7b What this settles, and what it kills

- **The four handoff discriminators are largely overtaken.** Discriminator 2 is answered (yes, B survives
  MB off). Discriminator 4 is answered (C ≡ B). Discriminator 3 is now near-worthless as stated: with
  bloom OFF there is no gather for the aperture mask to protect, and B is present anyway.
- **The premise of report B is wrong.** It is filed as a "bloom issue". It is not: it reproduces with the
  pack's bloom compiled out. Bloom may modulate its appearance; it does not cause it.
- **The binding constraint is observation 5.** Any candidate mechanism that would also fire on an
  open-air portal is refuted on its face. The artifact requires geometry ADJACENT to the aperture.
- **The second binding constraint is "vertical edges under horizontal motion".** Yaw and strafe both
  produce predominantly horizontal screen-space velocity, which smears across *vertical* edges. That is
  the signature of a **pre-existing static edge defect being dragged by a velocity-driven filter**, not of
  a defect the filter creates.
- **Working shape (INFERRED, not yet measured):** one static, near-sub-visible band at the aperture
  boundary which motion blur amplifies by dragging it along the velocity vector. ~~A is the same dragging
  at the player's silhouette edge~~ — **struck, see §7a-bis: A reproduces in open air, so it is a separate
  bug.** This is a hypothesis. It has not been instrumented.

### §7d THE ARCHITECTURAL FACT THAT FRAMES BOTH BUGS (MEASURED, two independent confirmations)

**The main frame's ENTIRE pack post stack runs BEFORE the portal is stamped.**

`MixinGameRenderer_IPPostLevelAnchor.java:79-92` anchors at `@At(INVOKE, target="LevelRenderer;render(",
shift=AFTER)` inside `GameRenderer.renderLevel`. Iris's `finalizeLevelRendering()` — the composite chain
*and* the final pass — is invoked from `MixinLevelRenderer`, i.e. **inside** `LevelRenderer.render`.
Independently established twice: by this arc's recon, and previously in `MB_SMEAR_VERDICT.md` §1b(4).

Consequences, both structural:

1. When the main chain's **non-local** operators run — motion blur (`composite4.glsl:139-171`, and note it
   has **zero depth rejection**: `mbwg += 1.0` unconditional), TAA (composite6), FXAA (composite7), and
   the unsharp sharpen in `final.glsl:63-76` — the aperture region of the main frame still holds
   **main-world** content. Under a shaderpack the mod draws nothing at the portal plane
   (`OverlayRendering.java:81-88` refuses the swirl with shaders on; `PortalPlaceholderBlock.java:150-151`
   is `RenderShape.INVISIBLE`), so it is ordinary geometry seen through the doorway.
2. `IrisCompatPaste.stampPortalArea` then replaces the aperture **interior only**
   (`:456-458`, into `deferred`, blit-back to main). Whatever those operators deposited on the
   **exterior** side of the aperture silhouette is never repainted.

**`final.glsl:56-61` is anisotropic, and it points the right way.** All four unsharp taps use `viewD.x`:
```
vec2( viewD.x, 0.0), vec2( 0.0, viewD.x), vec2(-viewD.x, 0.0), vec2( 0.0, -viewD.x)
```
`viewD = 1.0/vec2(viewWidth, viewHeight)` (`:54`). The two *vertical* taps therefore sample at the
*horizontal* pixel pitch — under one pixel on a 16:9 window — so the filter responds ~1.8× more strongly
to **vertical** edges than horizontal ones. `IMAGE_SHARPENING` defaults to `5` (`lib/common.glsl:145`) and
is **absent from the user's sidecar, so the default is in force**. An unsharp mask is precisely a
"thin bright line at a contrast edge" generator, and the pack's own option text warns of it
(`lang/en_US.lang:675`: "subtle brightness changes"). This is the only measured x/y anisotropy anywhere in
the stack and it matches observation 1's vertical-edge bias without needing anything else to be anisotropic.

### §7e ★ LEG 2 — THE SLIVER IS INSIDE THE STAMP FOOTPRINT (2026-08-03 00:2x, USER-OBSERVED)

**Config:** `-PdebugStampSolid=true -PdebugTintStamp=true -PbloomMaskProbe=true`, everything else at
shipped defaults. **Levers verified on the LIVE JVM** (PID 542644, `IS5-RC [1/3]` block): all three
present; `isIrisBloomApertureMaskActive() = ACTIVE`, `isIrisDestPrevCameraActive() = ACTIVE`.

Both flags together make every stamped fragment write the vertex colour directly, ignoring the sample —
the whole window renders **flat magenta**, which is an exact visual readout of the stamp's pixel
footprint (`IPGlobal.java:777-788`, `IrisCompatPaste.java:486-490`).

> **User, whipping the camera at a framed portal: "magenta clean to the edge, no sliver".**

**What that settles, in both directions:**

- The sliver lies **INSIDE** the stamp's footprint. It is **destination content**, already wrong in the
  dest image before the stamp copies it. (It also independently corroborates observation 7 — the user's
  "I think the sliver is the portal view behind it" — from a completely different kind of evidence.)
- **The stamp is EXONERATED.** It is not under-covering, not off by a pixel, not mis-registered against
  the frame. C3 (cross-program registration) and C6 (depth-tie) are refuted for B.
- **C1 is refuted FOR B.** The main chain's post stack does deposit main-world content outside the
  aperture silhouette — that is measured and still true — but whatever it deposits is not this sliver,
  because this sliver vanishes when the stamped region is painted over. *C1 remains live for artifact
  **A**, which was deliberately not judged on this leg (magenta destroys A's colour evidence).*
- **The search space collapses to the DEST composite chain**, and to one question: *what in the
  destination chain knows where the aperture is at all?* The dest world is rendered FULL SCREEN; the
  aperture is not a feature of that image. Exactly one thing in the dest chain draws the aperture
  footprint into it — `IrisBloomApertureMask`. That makes it the leading candidate by elimination, not
  by affinity.

**Next leg (running): `-PdisableIrisBloomApertureMask=true`.** A one-variable A/B of a DEFAULT-ON
feature. Sliver gone ⇒ the mask is the cause. Sliver unchanged ⇒ the mask is innocent and something
else in the dest chain is drawing an aperture-shaped edge, which would be a genuinely new finding.

### §7f ★★★ ROOT CAUSE OF THE BLOOM SLIVER — PROVEN IN BOTH DIRECTIONS (2026-08-03 00:44)

**Motion Blur silently relocates the C3-BLOOM aperture mask past the point where it works.**

`buildPlan` picks `maskIndex = lastC0Writer + 1` (`IrisBloomApertureMask.java:480`), where `lastC0Writer`
is the last composite pass whose `drawBuffers` contains 0 (`:439-462`). That rule assumes the last c0
**writer** sits before the bloom **gatherer**. Under Complementary Reimagined it does — until Motion Blur
is switched on. Then `composite4`, *which is itself the gatherer* (`composite4.glsl:65-84`, `BloomTile`),
flips `/* DRAWBUFFERS:3 */` → `/* DRAWBUFFERS:30 */` (`:180-184`), becomes the last c0 writer, and pushes
the mask one pass **after** the gather. The mask then runs successfully every frame and is inert.

| | `lastC0Writer` | `maskIndex` | mask lands | artifact |
|---|---|---|---|---|
| MB OFF | 2 (`composite3`, `db=[0]`) | 3 | **before** the gather | **GONE** |
| MB ON | 3 (`composite4`, `db=[3,0]`) | 4 | **after** the gather | **PRESENT** |

**User A/B, one variable (the pack's Motion Blur toggle), everything else at shipped defaults:**
*Bloom ON + MB OFF → "goes away". Bloom ON + MB ON → "comes back".*

**Log corroboration, same session, independent of the user's eyes** (run started 00:38:23):
```
00:43:35  [C3-BLOOM] last colortex0 writer also writes other draw buffers
                     (motion-blur shape, lastC0Writer=3 db=[3, 0])
00:43:35  [C3-BLOOM] LIVE: pass=composite5 idx=4 reads=MAIN     <- MB ON,  artifact PRESENT
00:44:44  Using shaderpack: ...                                    (rebuild; user set MB OFF)
00:44:47  [C3-BLOOM] LIVE: pass=composite4 idx=3 reads=ALT      <- MB OFF, artifact GONE
00:45:19  Using shaderpack: ...                                    (rebuild; user set MB ON)
00:45:20  [C3-BLOOM] LIVE: pass=composite5 idx=4 reads=MAIN     <- MB ON,  artifact BACK
throughout: masks=10148 misses=0    (the mask fires every frame in BOTH plans)
```

**★ The instrument fix from §7c is what made this leg readable.** Under the old class-lifetime
`liveLogged` latch, only the FIRST of those three lines would have printed; both toggles would have been
invisible and the leg would have rested on the user's eyes alone. It also printed `lastC0Writer=3
db=[3, 0]`, which the old message could not say at all.

**This is `MB_SMEAR_HANDOFF.md` §1c** — "the MB bloom-ring commission, premise CONFIRMED, work SUSPENDED"
— reaching the top of the queue, exactly as `MB_BLOOM_SEAM_HANDOFF` §2 guessed. And the ledger at
`IrisBloomApertureMask.java:100-104` predicted this shape in writing ("MB-ON Complementary … mask
inertly: exactly today's ring, never worse").

### §7f-bis ★ CORRECTION — LEG 3 WAS UNINFORMATIVE AND I READ IT AS A REFUTATION

Leg 3 (`-PdisableIrisBloomApertureMask=true`, sliver still present) was reported to the user as "the mask
is innocent". **That was wrong.** Leg 2's log shows the mask was landing at `idx=4`, i.e. already inert
against the ring by construction. Switching off a feature that is already inert cannot change anything,
so the leg could not have gone the other way and carried no information about the mechanism — only about
its *placement*. Two distinct claims were collapsed into one:

- *(a)* "the mask **as it currently lands** does not affect the sliver" — MEASURED, true, and predicted;
- *(b)* "the aperture-mask **mechanism** cannot fix the sliver" — never established, and now REFUTED.

**Rule earned: before running an A/B on a feature, check the log for whether that feature is in a
configuration where it could possibly bite.** The plan line was in the previous leg's log the whole time.

### §7f-ter ★ THE PACK OPTIONS DRIFTED MID-ARC — pin them in every future leg

Between leg 1 and leg 3 the sidecar was rewritten and `BLOOM_ENABLED` / `IMAGE_SHARPENING` **disappeared
from it, i.e. reverted to their defaults (Bloom ON, Sharpening 5)**, while `FXAA_STRENGTH` went 75→70,
`TAA_JITTER`→2, and lightshafts / SSAO / `DISTANT_LIGHT_BOKEH` were switched off. So leg 1 (bloom OFF)
and leg 3 (bloom ON) were **not comparable**, and the "sliver" judged in each may not be the same artifact
— which is precisely why the user's own "there is a separate bloom artifact alongside the sliver" was
right and my merge of them was not. **Pack options are user-side state the harness does not control:
read `shaderpacks/<pack>.txt` at the START of every leg and record it beside the lever block.**

### §7g THE REMAINING OPEN ITEMS (do not lose these — the bloom ring is only one of them)

1. **The MB-OFF residual sliver.** With Bloom OFF *and* MB OFF the user still reported "an even smaller
   even slighter sliver on the vertical edges". That is a different, much weaker artifact and is
   unexplained. Leading candidate remains the pack's unsharp filter (`final.glsl:56-76`, `viewD.x`
   anisotropy — see §7d), never tested; `IMAGE_SHARPENING → OFF` is a single in-game slider.
2. **Artifact A, the third-person player halo.** Untouched. MB-only, rotation-only, reproduces in OPEN
   AIR, so it does not share the sliver's adjacency constraint. Leading candidate: `composite4`'s motion
   blur has **zero depth rejection** (`:139-171`, `mbwg += 1.0` unconditional), so the player's colour is
   smeared past its silhouette, and the stamp — whose coverage is decided by depth, which MB does not
   affect — re-cuts that trail at a hard edge and refills the overhang with destination colour.
3. **The bloom-ring fix itself**, designed but not yet built. The hard part is that `composite4` reads
   colortex0 **twice**: the gather at LODs 2-8 (`:65-84`) and the motion blur at LOD 0 (`:141`, 9 taps,
   clamped to the SCREEN not the aperture, reach scaling with `MOTION_BLURRING_STRENGTH`, which the user
   runs at the slider maximum 2.00). Masking before the gather therefore also blackens the motion-blur
   source, risking a **dark MB fringe inside the window** in place of the bright ring.

**★ C3-BLOOM as a candidate for B, for a reason the handoff never considered.**
`IrisBloomApertureMask` CLEARS colortex0 to black outside the aperture (`:656`) and repaints only the
aperture (`:713-724`). That clear happens at `maskIndex`, which is **before composite6 (TAA), composite7
(FXAA) and `final`'s sharpen in both the MB-ON and MB-OFF plans**. Those three then see a hard
content-to-black edge exactly at the aperture, and unsharp's overshoot on the *bright* side of such an
edge is a **bright rim just inside the window, made of window content** — which is exactly what the user
describes, colour included. The recon ranked this candidate 5th and refuted it on two grounds ("the
dilation is isotropic" and "it can only produce a dark band"); **both refutations dissolve once the
sharpen kernel is in the picture** — the anisotropy comes from `final.glsl`, not from the mask, and the
sign is bright-on-the-bright-side, not dark. Lever: `-PdisableIrisBloomApertureMask=true`. Treat as a
live candidate, not a closed one.

### §7c Instrument fix landed before the leg (log-only)

`IrisBloomApertureMask`'s `liveLogged` / `mbShapeInfoLogged` were boolean latches reset only in
`teardown()` — which runs from `PortalRenderer.switchRenderer → onSwitchedAway`, i.e. a **renderer
switch, not a pipeline rebuild**. Every shaderpack option change is a pipeline rebuild. **MEASURED in
this worktree's own log (run of 22:47:14):** one `[C3-BLOOM] LIVE: pass=composite5 idx=4` at 22:47:36,
then FOUR `Using shaderpack:` rebuilds and ZERO re-announcements. Replaced with **content-keyed**
announcements: any change in the announced string re-emits, an identical plan never repeats, and the
newest `LIVE:` line in a log is always the plan in force. This is the same staleness class that cost a
full false-refutation cycle in `MB_SMEAR_VERDICT.md` §2; the reset added then was the right idea in the
wrong place.
