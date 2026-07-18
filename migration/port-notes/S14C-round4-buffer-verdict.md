# S14-C round 4 — buffer-lifecycle verdict + two real leaks fixed + the round-5 matrix

## Verdict

The wedge painter remains UNIDENTIFIED after round 4, but both round-4 theories are refuted with
source proofs and TWO REAL LEAKS were confirmed and fixed (S14.30):

- **Aliasing REFUTED:** blaze3d has NO Cleaner/finalizer/PhantomReference anywhere (grep-verified
  both packages); `GlBuffer.Direct.close()` is the ONLY `glDeleteBuffers` path. GC never frees a
  GL name → no free→reuse→alias step exists. (Two tracer sub-claims corrected by verifiers along
  the way; the refutation stands independent of them.)
- **Ring/Globals-UBO next-frame theory EXCLUDED; post-hook same-frame vanilla painters EXCLUDED.**
- **Branch-D-as-vanilla-sky DEMOTED on two hard grounds:** (a) the NETHER HAS NO VANILLA SKY PASS
  (`DimensionType.Skybox NONE` gates `addSkyPass`) — "wedges in both dims" cannot be vanilla sky
  geometry; (b) per-sector SOLID colors + seams exclude every corruption of the single-uniform,
  POSITION-only sky-disc draw (uniform corruption recolors coherently; vertex corruption breaks
  the user-confirmed fan alignment).
- **The 484 MiB/s allocation rate is DECOUPLED from both the wedges and the leaks:** it is
  Java-heap churn from re-rendering entire worlds N times per frame (full extract +
  `RenderRegionCache` SectionCopy[27] + discovery lists + fresh Frustums per pass) — inherent to
  the N-pass stencil-direct design. The leak fixes will NOT move that number; only pass-count
  reduction will (the occlusion/lag track).

## The two confirmed leaks (fixed in S14.30)

1. **Frame-transient UBO leak:** `writeProjectionSlice`/`writeFogSlice` (SecondaryWorldRenderCore)
   + ViewAreaRenderer's projection writer created a GpuBuffer PER CALL and never closed any —
  2–6 GL names + driver stores leaked per portal layer per frame, forever. FIX: frame-transient
  ledger — per-call DISTINCT buffers KEPT (nested layers save/restore slice REFERENCES; a shared
  rewritten buffer would corrupt the outer layer's restore) + DEFERRED close drained at
  `GameRenderer.render` TAIL (vanilla's `DynamicUniformStorage.endFrame` discipline; the drain is
  UNCONDITIONAL at the existing GameRendererMixin site so flag flips can't strand buffers), plus
  `MemoryStack` staging per vanilla's `ProjectionMatrixBuffer.writeBuffer`. Dev-runtime
  VALIDATION is the canary: a premature close crashes loudly, never silently.
2. **Aperture-mesh native-malloc leak:** `buildPortalViewAreaTrianglesBuffer`'s ByteBufferBuilder
  was never closed — ~4KB+ malloc'd per aperture draw, 2 draws/layer/frame ≈ **~1.7 GB/h RSS**,
  invisible to the Java heap, F3's alloc meter, AND GL. FIX: try-with-resources (vanilla's own
  SkyRenderer discipline); ordering safe (drawMesh copies bytes at createBuffer + draws
  synchronously; MeshData.close only drops the result ref).

S20-removal ledger: block-era `PortalShapeRenderer` carries the same latent ByteBufferBuilder
pattern (flag-ON-suppressed; no change now — dies with S20). Probe extension: the 1Hz
frame-boundary line now also reports `skybox=` (the addSkyPass gate value).

## Round-5 decision matrix (pre-registered)

| Observation (user's next run) | Conclusion → round-5 direction |
|---|---|
| Nether-side wedges persist with `debug_skip_portal_sky` ON, standing IN the nether | ALL sky-family geometry excluded (no vanilla sky pass exists there) → pivot to non-sky mechanisms (compositing/reveal/undrawn-region show-through) |
| No nether-side wedges + OW sectors count ~16 (22.5° seams) | The SUNRISE FAN (POSITION_COLOR, real per-vertex color ramp — CAN band per-sector) → targeted state trace of that one draw |
| No nether-side wedges + OW sectors count 8 (45° seams) | Sky-disc topology with draw-corruption excluded → investigate outside the draw |
| Wedges CHANGED/GONE after the S14.30 leak fixes | Driver-memory-pressure link CONFIRMED → reopen the pressure track (and re-examine the aliasing refutation) |

## Acceptance for S14.30 itself (independent of the wedges)

≥5 min of continuous portal viewing: process RSS trend FLAT (was ~1.7 GB/h+), progressive-lag
trend flattened; NO "already closed" IllegalStateException (dev VALIDATION canary); aperture,
nested portals, GUI portals visually unchanged. EXPECTATION RESET: the F3 "Allocation rate
~484 MiB/s" will NOT drop materially — that is the inherent N-pass world-re-render churn, not
the leaks.
