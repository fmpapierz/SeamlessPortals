# PERF + FLICKER SESSION RESULTS — 2026-08-10

**Scope: the post-flip perf arc (user: "make it less laggy") + two live shaders-ON defects
reported mid-session. Commits `ba5fff4 → 54c2e9d`, all pushed, every commit gametest-gated.**

## §1 PERF-P1 — measure first (`ba5fff4`)

- **Census mining (before any change):** 0 views/frame = 114 fps, 1 = 73, 2 = 22 —
  superlinear collapse, the user's "2 portals sucks" quantified. Census-skipped seconds
  (>1s single frames) clustered on cross-dim first-look and post-crossing windows.
- **Muted four default-on instruments** (all had answered questions in closed arcs):
  the two 1Hz GL-stall readbacks (`-Pis5LiveReadbacks` re-arms), TeleportFlashProbe's
  per-frame row build + post-crossing 10-20KB dump (`-PflashProbe`), RenderChainProbe's
  post-promote auto-arm (`-PrenderChainProbeAutoArm`). User: "feels smoother already."
- **PerfTimers extended** (hierarchical keys — never sum flat): is5.frameStart ⊃
  is5.view.L0/nested ⊃ is5.destRender; is5.capture, is5.stampPass (readback-subtracted),
  is5.nestedStamp, is5.temporalGuard, is5.queryLoop; crossing chain ip.teleportPlayer ⊃
  ip.changeDim ⊃ ip.lightDrain, ip.promoteDemote, ip.discoveryFlood, bridge.floodFill,
  pump.compile.
- **P1 verdict:** the IS5-PRE machinery is EXONERATED (<1ms/frame total: capture 0.02,
  stamp 0.18, guard 0.01, queryLoop 0.18ms). THE steady-state cost is
  **is5.destRender = 7.4ms/view CPU** (nested 11.1ms) — the full iris gbuffer+shadow render
  per view. The crossing CODE is exonerated too (ip.* all 1-2ms); the real freezes were
  (a) per-dim iris pipeline compilation on FIRST portal look (3s census gap, GL-burst
  correlated) and (b) post-crossing load. CAVEAT for future perf gates: is5.* timers are
  CPU-side and blind to GPU passes — GPU changes gate on GL_TIME_ELAPSED or whole-frame FPS.

## §2 PERF-P2 — pipeline pre-warm (`8573bcd`, user-picked from four options)

Cross-dim iris pipelines compile at the moment a main-pipeline generation appears (world
join / pack apply / K toggle) instead of on first portal look. javap-proven: iris's
NamespacedId derives purely from the dimension-key identifier, so the pre-warm hits the
exact cache slots; the sweep ends by re-preparing the current dim (slot restore). Live:
join pays 2.4-6.2s (option-set dependent); every pack APPLY re-pays the end-pipeline
compile (~0.15-5s, growing with enabled features) — a DEBOUNCE (warm N seconds after the
last generation change) is the named improvement if option-toggling feels sluggish.
Escape: `-PdisablePipelinePrewarm`. User declined (for now): portal-view shadow clamp,
alternate-frame temporal reuse, reduced view resolution — the destRender 7.4ms/view
steady-state cost is UNTOUCHED and remains the open perf item.

## §3 THE CROSSING/FAR FLICKER — two mechanisms, both closed

**User report:** "flicker of wrong dest when crossing, near portal, only with shaders on"
— later corrected (the observation that reframed the arc): "not just on crossing, even
when far away sometimes". Old-path B leg: clean (user-verified) ⇒ new-path machinery.

1. **IS5-XFLICK (`31dfddd`)** — XTRACE-adjudicated (159 rows, 29 teleports): every
   arrival frame ran a SPECULATIVE render of the just-exited reverse portal from a camera
   ON its plane (tp=true specR=1 dPl=0.00-0.11, 29/29; dCam=0 everywhere refuted the
   arm/stamp-camera hypothesis; pendingAtEntry=0 refuted slot leakage — and the
   zero-"never stamped"-WARN pre-check killed the dropped-stamp hypothesis before any
   instrument was built). Under the crossing-window clip suspension that painted one
   full-screen frame of the SOURCE world. Fix: teleport-frame near-plane (<1 block)
   unknowns are skip-if-unknown. `-PdisableTeleportSpecSkip` = B. Real, but NOT the
   headline flicker (user re-report after the fix leg).
2. **IS5-BLINK (`54c2e9d`) — THE headline fix, CLOSED user-affirmed ("flicker gone").**
   The new path consumes LAST frame's occlusion query (structural: views render at frame
   start, pre-depth); a single zero-sample query is consumed as a confident "not visible"
   ⇒ the window goes unstamped for ONE frame ⇒ raw terrain where the dest should be, at
   any distance. The old path decides same-frame and cannot blink (matches the clean B
   leg). Fix: 2-frame hysteresis (single FALSE renders on credit; census `hys=`).
   Detector: always-on rate-capped `[IS5-BLINK]` on raw T→F→T (independent of the fix
   lever ⇒ one leg carried mechanism proof AND fix proof: 23 blink lines at dPl
   1.57-12.85 + hys=29 + user's eyes). `-PdisableQueryHysteresis` = B.
   **Pre-registered residual:** 2-frame blinks (2 of 23) still drop one frame — if a
   rarer residual flicker is reported, the named next step is threshold 2→3.

## §4 IS5-XDIM — nether composite effects in cross-dim windows (DESIGNED, BLOCKED)

User-confirmed: Complementary's nether storm (composite1) missing from cross-dim windows
on the new path, present on the old. Full design + two-judge record:
`migration/IS5_XDIM_DESIGN.md` (POST_FINAL capture — dest chain runs to completion incl.
final, capture from MC mainRT + dest depthtex0 at the finalize TAIL; pack-generic by
bytecode; ring mandate untouched; naive post-renderAll candidate REFUTED on the tex0→tex3
handoff). **Implementation blocked on two §4 user decisions:** the nested-window
disposition (rim-accept / PRE-demote / single-layer) and pre-acceptance of the
double-tonemap risk (SEVERE branch possible; retreat = lever = storm stays missing).

## §4b THE SIDEWAYS-ARRIVAL RESIDUAL (late-session finding — the next arc's brief)

After BLINK closed, three refinement rounds on the ARRIVAL-frame near-plane case
(`77fe2c6` tip): forward and backward crossings are CLEAN (user-verified); SIDEWAYS
crossings still flash ("flashing only on side to side"). The trace adjudicated the final
round: sideways arrivals take the skip branch (look·N ≈ 0) and the flash is the ONE-FRAME
UNSTAMPED HOLE — half a screen of window legitimately missing for one frame
(tp=true stampRan=false specR/S=0/1 → next frame stamps at dPl 0.01-0.20).

ROOT CAUSE (read, not guessed — ViewAreaRenderer has NO screen-cover branch; the mesh is
always true clipped portal geometry): the render branch's wrongness is not the mesh, it is
the CONTENT — on arrival frames the V2 crossing-window clip SUSPENSION (the closed
seam-clip arc's mechanism, `shouldSuspendInnerClipForCrossing`) applies to the
just-arrived reverse portal's content render, so the captured view contains UNCLIPPED
wrong-side geometry. Skip = hole flash; render = wrong-content flash. Neither branch can
be right for sideways.

THE PROPER FIX (next arc, closed-arc discipline): scope the V2 suspension to the portal
being CROSSED INTO (the outgoing window it was built for — its black-void rationale) and
NOT to the just-arrived reverse portal; then the arrival frame can RENDER with the clip
active — forward clips to nothing, backward fills the screen, sideways gets the correct
half-split. Must re-verify on the leg: the seam black band (the suspension's raison
d'être), the crossing blur-burst feature, and both XFLICK directions. Until then the
shipped state is: forward/backward clean, sideways = one-frame dest-terrain blink
(much milder than the original wrong-content paint, disclosed).

## §5 OPEN AFTER THIS SESSION

- destRender 7.4ms/view steady-state (the 2-portal collapse) — the three declined levers
  remain on the table with P1 numbers.
- IS5-XDIM awaiting the two user decisions.
- Prewarm debounce (per-pack-apply compile cost) — named, unbuilt.
- The 2-frame-blink residual (threshold bump pre-registered).
- Parked (unchanged): iris copyPreHandDepth GL_INVALID at pipeline creation;
  maxPortalLayer never synced to dedicated servers; C2 upstream report rewrite.
