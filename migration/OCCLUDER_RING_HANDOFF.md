# OCCLUDER RING HANDOFF — the last of the three edge artifacts

**Status: MECHANISM MEASURED, FIX NOT DESIGNED.** Branch `iris-on/is5-shadow`, tip `626d856`, pushed.
Worktree `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow`.

This supersedes `MB_BLOOM_SEAM_HANDOFF.md` as the working document. That file remains the full
evidence log (§7 is this arc, §7a–§7l in order); read it for provenance, read this for what to do.

---

## §1 WHAT CLOSED, AND MUST NOT BE RE-OPENED

**The bloom light-bleed — FIXED, SHIPPED DEFAULT ON, USER-CONFIRMED BOTH DIRECTIONS** (`4b913a5`).

`IrisBloomApertureMask.buildPlan` chose `maskIndex = lastC0Writer + 1`, which assumes the last
colortex0 **writer** precedes the bloom **gatherer**. With the pack's Motion Blur on, Complementary's
`composite4` — *which is itself the gatherer* — flips `DRAWBUFFERS:3` → `DRAWBUFFERS:30`, becomes the
last c0 writer, and pushes the mask one pass **past** the gather, where it runs every frame
(`masks=10148 misses=0`) and does nothing. The fix retargets onto the gatherer, detected via
`CompositeRenderer$Pass.mipmappedBuffers` — a signal that is Motion-Blur-invariant because
`composite4.glsl:18` declares `colortex0MipmapEnabled` under no conditional.

| leg | lever (verified on the live JVM) | plan, from the log | user saw |
|---|---|---|---|
| 1A | *(default)* | `sel=gatherer pass=composite4 idx=3 reads=ALT` | bleed **gone** |
| 1B | `disableBloomMaskGathererRetarget=true` | `sel=legacy pass=composite5 idx=4 reads=MAIN` | bleed **back** |
| 1E | *(default)*, sustained fast yaw | as 1A, `masks=9187 misses=0`, no WARN | **no dark fringe** |

The feared regression (masking before `composite4` also blackens its motion-blur source) **did not
materialise**, so the level-0 restore stage was NOT built. Its design survives in the workflow record
if a future pack shape needs it.

---

## §2 THE OPEN BUG, STATED EXACTLY

**One artifact, amplified by motion blur** (the user's model, and it fits the data better than the
two-bug split I proposed and they corrected):

> A ring of **source-world** content around the silhouette of **any** occluder between the camera and
> a portal window — a block, the obsidian frame, or the player in third person. The frame was never
> special; it is simply the commonest occluder. This is what reports A, B and C all were.

**MECHANISM (measured, per pixel).** The stamp composites with a **hard depth test** onto an image
whose **colour** has already been moved past its own silhouette by a pass that never touched
**depth**. The ring is exactly the set of pixels the smear moved: colour says "not occluder", depth
says "occluder", the stamp is depth-rejected, and the source frame's content survives in the gap.

Motion blur ON, at two resolutions:
```
345:M ff00ff /0.988821 || 346:- 908ca4 /0.975004  347:- 574643 /0.975004  348:- 7e5f4b /0.975118
```
Pixel 346 carries the log's depth **bit-identically** to 347, which is plainly bark, while its colour
is the terrain's. **Bit-identical rules out a scaled resample** — this is a decoupling, not a dilation.

Motion blur verifiably OFF (`MOTION_BLUR_EFFECT` **absent** from the sidecar ⇒ pack default `-1`,
`lib/common.glsl:146`): colour and depth edges **coincide** at the strongest in-window silhouette
(`align@465`, `|dz|=0.022373`), and both aperture rims are clean single-pixel transitions with no
narrow unstamped run on the row. **⇒ the MB-off effect is at or below one pixel. That is a BOUND, not
a null result** — do not read it as "absent".

---

## §3 VERIFIED NEGATIVES — DO NOT RE-RUN ANY OF THESE

| candidate | how it died |
|---|---|
| pack **bloom** | ring reproduces with `BLOOM_ENABLED=-1`, which compiles the entire `BloomTile` gather out of `composite4.glsl:65` |
| the pack's **unsharp filter** | `IMAGE_SHARPENING=0` **confirmed written to the sidecar on disk**; unchanged |
| sub-pixel **TAA registration** | `TAA_JITTER=0` confirmed on disk; unchanged |
| the **stamp's own footprint** | magenta leg: "magenta clean to the edge" |
| **buffer-size mismatch** | geometry witness: `main=854x480 deferred=854x480 match=true` |
| the **snapshot copy** | `MAIN` vs `DEFER` **byte-identical**, 17 samples, colour *and* depth |
| the **C3-BLOOM mask** | disabling it changes nothing — and it was inert by construction anyway |
| **IS5-REC single-slot nesting** | the outer arm is already `consumed` before the inner overwrites it; an accounting defect in the miss counter only, never a pixel mechanism |

---

## §4 THE QUESTION FOR THE NEXT ENGAGEMENT

**Is this fixable in a compositor that runs AFTER the pack's post chain?**

The architecture (measured twice independently — this arc, and `MB_SMEAR_VERDICT.md` §1b): the main
frame's **entire** pack post stack runs before the portal is stamped.
`MixinGameRenderer_IPPostLevelAnchor` injects at `@At(INVOKE, target="LevelRenderer;render(",
shift=AFTER)` inside `GameRenderer.renderLevel`, and iris's `finalizeLevelRendering` — composite chain
*and* final pass — is invoked from `MixinLevelRenderer`, i.e. **inside** `LevelRenderer.render`.

So the stamp's coverage is **binary and depth-derived**, while the smear is **colour-only and
non-local**. Three candidate shapes were scoped in the design record; **none is designed**:

1. **Composite the destination content BEFORE the main post chain**, so occluder and destination are
   filtered together. The destination has already been through its own full pack pipeline including
   its own final pass, so it would be double-processed — is there a variant that avoids that?
2. **Stencil coverage written during the main gbuffer pass**, sharing the occluders' exact
   rasterisation. **This is the most interesting one: the shaders-OFF path, `RendererUsingStencil`,
   decides coverage with a stencil and does not have this bug.** Establish first whether it is really
   clean — if so, that is simultaneously the proof of the mechanism and the shape of the fix.
3. **Depth-aware feathering** — blend the stamp against the main colour near silhouettes instead of a
   binary test. Needs sub-pixel coverage information the stamp does not currently have.

**Start by asking whether shaders-OFF shows the ring.** It is one keypress, it costs nothing, and it
discriminates "inherent to portals" from "a property of the shaders-ON compositing strategy".

---

## §5 THE INSTRUMENT — `StampCoverageProbe` (IS5-COV)

```
.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PstampCoverageProbe=true -PdebugStampSolid=true -PdebugTintStamp=true
```

It **refuses to measure** without `debugStampSolid` + `debugTintStamp`, because its classifier is "is
this pixel the stamp's flat magenta" and that is exact only then; it emits a once-only WARN naming the
missing levers and the full correct invocation. Log-only, ≤1 Hz, disarms on throw, saves/restores every
pixel-store and FBO binding it touches.

What it emits:
- `row=… runs=…` — the scanline run-length encoded by stamped-ness with each run's depth range;
- `APERTURE-L` / `APERTURE-R` — the window's own rim, dumped **unconditionally**, raw per pixel;
- `edge@…` — raw `colour/depth` for six pixels either side of each coverage boundary;
- `align@… (strongest depth step within window[a..b])` — the same scanline read from **both** `mainRT`
  and `deferred` at the snapshot, printed side by side;
- `snapshot geometry main=WxH deferred=WxH match=…` — **always on, not lever-gated**, WARN on mismatch.

**It took four corrections to become trustworthy, three of them AIM.** Read
`memory/session-2026-08-03-lessons.md` before extending it.

---

## §6 TRAPS THIS ARC PAID FOR

- **Pack options are user-side state with no mod-side witness.** They drifted mid-arc and made two
  legs incomparable. `cat fabric/runs/client-sodium/shaderpacks/<pack>.zip.txt` at the START of every
  leg and record it beside the lever block. **An absent key means the pack DEFAULT** (look it up in
  `shaders/lib/common.glsl`), not "off". The sidecar records only the FINAL state of a session, so it
  cannot vindicate a mid-session toggle — set the variable on disk before launch if the leg depends on
  it.
- **Before A/B-ing a feature, check whether it is in a configuration where it could possibly bite.**
  A null from an already-inert feature is not an exoneration. The proof was in the previous leg's log.
- **A lever's conclusion is only as good as where it was AIMED.** `-PdebugStampSolid` gave opposite
  answers at the frame edge and at a block inside the window — same lever, same build.
- **`masks=`/`misses=` is never a gate.** `masks=10148 misses=0` was logged while the ring was plainly
  visible; the counter records draws issued, never effect achieved.
- **Declared vs executed depth state disagree here.** The stamp declares `GREATER_THAN_OR_EQUAL`; the
  measured buffer is small-is-near with the draw reporting `clipDepthMode=NEGATIVE_ONE_TO_ONE` 39/39.
  Do not design a fix from the declaration.
- `runCrossingGametest` runs **iris-ABSENT** and cannot reach any of this code. It is a regression
  gate, not a proof.

---

## §7 STILL OPEN, UNRELATED

- The **C2 upstream bug report** (`migration/C2_JIT_BUG_REPORT.md`) is STALE — it predates the
  2026-08-02 findings (on-demand repro recipe, profile-dependence, the refuted offline reproducer, and
  the proof that `dontinline` fixes it). Rewrite before filing at https://bugreport.java.com/.
- A parked `GL_INVALID_OPERATION: Invalid format` from iris `RenderTargets.copyPreHandDepth` during a
  nested cross-dim render (3 occurrences, only at nether pipeline creation).
- `maxPortalLayer` is never synced to dedicated servers.
