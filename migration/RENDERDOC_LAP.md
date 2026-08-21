# RENDERDOC CAPTURE LAP — the definitive pixel-attribution instrument

One captured frame of the tail-sliver artifact answers, at once, every question 27 rounds of
log instruments could not: **which draw call owns every artifact pixel** (pixel history),
**the stored depth at those pixels** (and what wrote it), and **the stencil state** during
each draw. This is the single highest-information action available (SEAM_BAND_HANDOFF §4.2).

## 1. Install (user action, one-time)

Download RenderDoc (stable) from https://renderdoc.org/ and install with defaults.
Nothing else needs configuring — MC 26.2 is core-profile OpenGL, which RenderDoc supports.

## 2. Launch the dev client under RenderDoc

The game must be LAUNCHED through RenderDoc (GL cannot be hooked after startup).

**Route A — direct java relaunch (recommended):** `migration/renderdoc-launch-info.txt`
(generated from the live tint-armed client) holds the exact executable, arguments and
working directory of the dev client. In RenderDoc:

1. File → Launch Application.
2. Executable Path = the `EXE` line; Command-line Arguments = the `ARGS` line;
   Working Directory = the `CWD` line from that file.
3. Launch. The game boots normally with the RenderDoc overlay in the corner.

**Route B — through the launcher (fallback if A misbehaves):** Executable Path =
`runclient.bat` in the worktree root (it runs gradle `--no-daemon`, so the game is a child
of the launcher), Working Directory = the worktree root, and tick
**"Capture Child Processes"**. RenderDoc will hook the java process gradle spawns.
Note route B launches WITHOUT the tint/probe levers unless they are added to the bat.

## 3. Capture

1. Run the same cart+cow loop as always.
2. The instant the artifact is on screen (tail sliver, or a wrong-side bleed), press
   **F12** (or PrintScreen). Each press writes one `.rdc` capture. Take several — captures
   are cheap; the artifact moment is the only hard part. A paused/slowed crossing works too
   (the artifacts were proven speed-independent in round 26).
3. Optionally note roughly where on screen the artifact was (a screenshot alongside helps).

Captures land in RenderDoc's temp folder (default `%TEMP%\RenderDoc`, shown in the UI) —
tell Claude the paths, or just say "captures taken" and they will be found.

## 4. What happens next (Claude's side)

Pixel history at the artifact pixels, via the RenderDoc Python API or guided UI: the full
list of fragments that touched each pixel — every draw call, its pass (event browser shows
the portal stencil/clear/restore bracket), whether each fragment passed or failed the depth
test, the depth values on both sides, and the stencil state. With the tint lever armed the
draws are additionally colour-labeled per painter, so the event list is self-describing.

Every open question in the handoff maps to a fact this capture contains:
- sliver pixels: which draw wrote their final colour + what depth is stored there and who
  wrote it (the quad restamp? terrain? an entity draw?) → kills or confirms the depth-wall
  account in one look;
- bleed pixels: the owning draw call directly → entity painter vs portal quad vs seam-cell
  redraw vs particles, no inference;
- whether the band painter's fragments reached those pixels and FAILED depth (visible as
  failed fragments in pixel history) or never covered them at all.
