# S14-C — rung-2 live round 2: the surviving sky painter (mechanism model + lever protocol)

## Round-2 ground truth (user session, tree `ae46a57`)

The S14.21/S14.22 fixes PROVABLY LANDED: **zero** GL_INVALID_OPERATION all session (was 51,470),
the resolver logged ONE stable FBO (id 3), log otherwise clean. AND the painter SURVIVED, sharper:
large solid-color TRIANGULAR SECTORS with thin DARK SEAM lines converging at a zenith apex (OW
side, nether-fog-family colors per sector); nether side blue/purple/magenta over DISTANT terrain +
a bright light-blue quad; **paints ONLY on sky/distant-fog pixels, never on solid blocks, in BOTH
dims**; camera-tracking; portal-in-view-only. NEW heavy lag (clean log = real render cost).
Teleport flash slightly reduced.

## The reconciled mechanism model (7-agent hunt: 3 tracers + 3 Fable verifiers + Fable designer)

**M1 (the stable painter chain, CONFIRMED-shape):** the layer-1 aperture INCR draw
(`renderPortalViewAreaToStencil` → `ViewAreaRenderer.renderPortalArea`) rasterizes a footprint
LARGER than the portal quad — sky-spanning zenith wedges. Being GEQUAL-tested against the finished
main scene, the excess self-clips to far/sky pixels (= "never on solid blocks", no painter needs
its own depth test). The excess stencil==1 region is then photographed by: (a) the Row-16
COLOR_FILL in each portal's dest-fog color (OW-side teal/lavender/grey = per-portal nether biome
fogs), and (b) nether-side: the OW-dest sky sub-draws + fogged dest terrain (blue/purple/magenta;
the bright quad = celestial sun overlay brightening the flat fill). **The dark seams are
double-INCR pixels (stencil==2)** where the quad's two triangles' excess footprints overlap — the
EQUAL(1) painters skip them, leaving the mesh's own IP-verbatim BLACK (`Vec3.ZERO`) — near-smoking-
gun that the aperture mesh itself rasterizes the sectors. The screenquad shader (`core/screenquad`,
gl_VertexID, ignores matrices) exonerates the fill triangles from the "projection-warped" theory;
the sky-disc-fan-as-painter theory was REFUTED for the OW side (nether skybox = NONE, no dest sky
drawn there).

**Open geometry root (deliberately not fixed blind):** WHY the quad footprint spans the sky on
26.2 when the identical mesh + depth clamp ran clean on 1.21.3. Prime candidate: depth-clamp
external rasterization of triangles crossing w≤0 (`CHelper.enableDepthClamp` at the aperture
draw). The lever session settles it empirically.

**M2 (transient, FIXED here):** under `offsetOcclusionQuery` the INCR draw executes every frame
but consumes LAST frame's verdict; a stale-false verdict early-returns leaving this frame's
stencil INCR un-cleaned. 1-frame flashes only. (Correction to earlier digests: the SYNCHRONOUS
path writes nothing on false — the leak is offset-scheme-only; IP carries the same latent leak.)

**Lag model:** sky-wide wedge apertures make every occlusion query PASS (samples across the whole
wedge), defeating occlusion pruning → the bi-way recursion tree runs toward portalRenderLimit=200;
wedge-wide stencil also makes Row-7 depth-clear + fill + dest terrain rasterize sky-wide overdraw
per pass. Prediction: the M1 geometry fix collapses the lag for free. F3's "Rendered Portals" N is
the measurement.

**Exonerated:** the TRIANGLES identity index path, the fill-self-sampling theory, S14.21–26
(cost-neutral, kept), vanilla encoder stencil interference (verified: nothing in the 26.2 GL layer
touches stencil — the persistence assumption is sound).

## S14.27 (this commit)

- **F1** residue clamp on the occlusion-skip path, gated `offsetOcclusionQuery` (sync path stays
  byte-identical with IP). Deviation, ledgered task #9 (IP carries the same latent leak).
- **F2** portalSkyRenderer recreates on target IDENTITY change (object + color/depth views), not
  just size — closes the stale-target escape vector (vanilla shouldResetSkyRenderer semantics).
- **L1** `debug_dye_view_area_mesh` — dyes the aperture mesh GREEN (IP draws it BLACK): the direct
  photograph of the mesh footprint.
- **L2** `debug_no_aperture_depth_clamp` — skips depth clamp at the aperture draw only (the
  existing depth-clamp command gates only the dest-terrain clamp).

## The Phase-1 lever protocol (user session; append outcomes here)

0. Baseline all-off: sectors expected UNCHANGED (F1 fixes transients only); capture ONE F3
   screenshot while laggy (read "Rendered Portals" N).
1. `debug_dye_view_area_mesh` — seams turn GREEN + green beyond the quads = M1 confirmed
   (photograph the shape); no green beyond quads = mesh innocent → instrumented-dump branch.
2. `debug_dye_portal_fill` + `debug_skip_portal_terrain` + `debug_skip_portal_sky` TOGETHER —
   sectors turn MAGENTA = the fill is the painter; sectors vanish = terrain/sky was; persist
   un-dyed = unknown painter (escalate). (Dye alone is a trap: fog-colored dest terrain repaints
   over magenta.)
3. `debug_no_aperture_depth_clamp` — sectors COLLAPSE to the portal quads = depth-clamp wedge
   confirmed → the 2A fix (CPU near-plane clip of the aperture mesh, clamp kept). No collapse →
   transform/vertex chain → instrumented dump (2B).
4. `offset_occlusion_query` off — shape unchanged expected; lag INCREASES (positive control).
5. Free: `/time set noon` (nether-side pink = sunrise fan?); note whether the sun sits inside a
   sector and whether it dyes over in step 2.

## Fix branches (Phase 2 = S14.28)

- **2A** (expected): near-plane-aware CPU clip of the aperture mesh triangles (Sutherland-Hodgman
  on the straddlers, all-in-front pass-through BIT-IDENTICAL, ~1e-4 eps), depth clamp KEPT (IP
  near/far fragment semantics preserved; the clip only removes what 26.2 rasterizes as external
  wedges). Applies automatically to the Row-11/12 restore draw. Documented deviation-with-cause.
- **2B**: one-frame vertex/matrix dump instrumentation; no blind fix.
- **2C**: painter is sky/terrain-owned → structural backstop on that branch only.

Phase 3 (S14.29, only if lag persists with small N post-fix): buffer pooling for the per-pass
GpuBuffer/ByteBufferBuilder churn (behavior-neutral); optional user-side maxPortalLayer 5→2.

## Phase-1 lever session OUTCOMES (user, tree 6663709) — M1 REFUTED

- Step 2 (mesh dye GREEN): dark seams + sky wedges did **NOT** turn green → the aperture mesh is
  NOT their painter (the M1 seam mechanism fails its own photograph test).
- Step 3 (fill dye + skip terrain + skip sky together): the portal WINDOW turned purple/magenta
  (the dye lever provably works; the Row-16 fill correctly paints the window) — the wedges were
  **COMPLETELY UNCHANGED** → not the fill, not dest terrain, not dest sky.
- Step 4 (no aperture depth clamp): wedges did **NOT** collapse → not clamp rasterization.
- Net: every dest-pass draw is EXONERATED as the wedge painter. Round-3 lead: the wedges are
  VANILLA's own draws (sky-family fans) being STENCIL-CLIPPED by mod stencil state/content leaking
  beyond the portal bracket (test left enabled into later same-frame or next-frame-early vanilla
  draws — the next frame's sky draws BEFORE the mid-frame S14.21 stencil clear). Hunt r3 running.
- Session incident (CLOSED): the user's MOUSE stopped responding system-wide after the last lever
  command; no project JVM survived and no TDR/display events in the System log (checked); a PC
  restart fully restored it → USB/input-level glitch, not a persistent driver or game issue. The
  sync occlusion lever (step 5) remains a stall-storm risk on wedge-sized queries — do not re-run
  it until the wedge fix lands.

## Fidelity ruling wanted from the user (rides the lever session)

F1 is a deviation from IP-verbatim (IP has the same latent offset-scheme stencil leak). Accept as
a permanent ledgered deviation, or also report upstream-parity in the S20 ledger only? Both
mechanisms stay switchable via `offset_occlusion_query`.
