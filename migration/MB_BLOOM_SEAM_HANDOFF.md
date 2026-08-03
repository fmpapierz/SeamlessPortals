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

**★ This also re-opens C3-BLOOM as a candidate for B, for a reason the handoff never considered.**
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
