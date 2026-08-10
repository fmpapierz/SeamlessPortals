# IS5-XDIM — cross-dim dest-chain restore (POST_FINAL capture)

**Status: DESIGNED + JUDGED (2026-08-10, 2 facts agents + 1 synthesis + 2 adversarial judges,
~578k tok, wf_97850575-4eb); TWO USER-POLICY DECISIONS PENDING before implementation.**
Defect: Complementary's nether storm (composite1) missing from cross-dim portal windows on the
IS5-PRE path — user-B-leg-confirmed present on the old path ("swirls show up through portal on
old path"). Structural: dest composites cancelled + the window composited by the SOURCE chain
(world0 wrapper has no NETHER define; all composite logic is shared `program/compositeN.glsl`
behind per-world defines).

## §1 THE SHAPE (judge-corrected; the naive candidate is REFUTED)

- REFUTED: "let renderAll run, capture colortex0 post-renderAll, cancel final" — Complementary
  migrates the image tex0→tex3 at composite5 (final reads tex3); terminal colortex0 is a
  half-processed trap (nether bloom-fog MULTIPLY baked, DIVIDE never run). No generic surface
  answers "which colortex is the image".
- ADOPTED — POST_FINAL: for cross-dim armed views cancel NOTHING; let the dest
  `finalizeLevelRendering` run to completion (composites + final) and capture at the method
  TAIL from **MC mainRenderTarget color** + dest **depthtex0**. Bytecode-pinned
  (iris 1.11.2+26.2): finalizeLevelRendering = isRenderingWorld=false → removePhaseIfNeeded →
  renderAll → renderFinalPass → return (24 bytes, single RETURN — TAIL unambiguous); final
  deposits the finished image into mainRT in BOTH branches (RenderPass draw / no-final-program
  copyTexSubImage2D) ⇒ pack-generic, ZERO flip-parity machinery; composites+final are
  depth-READ-only (no composite FBO has a depth attachment) ⇒ TAIL depthtex0 ≡ today's capture
  depth; iris's own resetRenderTarget + swap passes run ⇒ zero mip/side hygiene replication.
- Same-dim views keep the shipped pre-composite capture BYTE-IDENTICALLY (mode decided at ARM
  time from mod-owned dims: `viewDim != mainRenderDim`). Stamp at main renderAll HEAD, part4
  mechanics, all brackets: UNCHANGED — the §0 ring mandate is untouched where it was won.
- Lever `-PcrossDimDestChain=true`, static-final, dev default OFF (§3.14 pattern); new IS5-RC
  row; census `cPre=`/`cPost=`; capture-geometry witness gains `src=c0|mainRT`.

## §2 JUDGE FOLDS (both APPROVE_WITH_CHANGES on the actual design; the REJECT verdict was
issued against the refuted candidate — its surviving demands are folded here)

1. **Nested/mixed provenance** (both judges): POST parents hold FILTERED content; part4's
   "capture-to-capture against UNFILTERED content" exactness premise breaks in both nesting
   directions (soft rim at nested boundaries from dest-chain non-local passes; brightness
   discontinuity for PRE children in POST parents). DISPOSITION = USER DECISION 1 (§4).
2. **Tonemap ×2 severity honesty** (ring judge): the pack map adjudicates double-tonemap as
   SEVERE (crushed contrast, gray-washed darks — composite5 pow-2.2 misfiring on curved input);
   the pre-registered user gate must treat that outcome as the FAIL branch (retreat = lever
   off = storm stays missing). Watch items folded into the same gate: overworld light
   shafts/rainbow now ADD onto display-referred input; RGBA8 mainRT capture re-graded through
   pow-2.2 risks banding in dark nether windows.
3. **MB double-streak is a user gate, not a footnote** (ring judge): with pack MOTION_BLUR on,
   cross-dim windows get dest-chain MB + main-chain MB. The user's MB state varies by session
   (ON during the ring arc; OFF in the 2026-08-10 sidecar) — pre-register accept-as-look vs
   lever on the first MB-on leg.
4. **Shared-dest-instance double-tick** (engineering judge): TWO cross-dim views into the SAME
   dest dimension share one pipeline instance ⇒ renderAll runs twice per frame on it ⇒ TAA
   history written with two cameras (mutual shimmer), auto-exposure double-ticked.
   DISPOSITION: POST for at most ONE view per dest-dim per frame; further same-dest views fall
   back PRE that frame (storm present in the nearest window, absent in the second — bounded,
   census-visible via cPre/cPost).
5. **IrisDestPrevCamera coverage at the new position** (engineering judge, MANDATORY first-leg
   check): the shared tracker holds prev=mainCam/cur=destCam while the dest chain runs — dest
   MB/TAA velocity = the portal offset unless the shipped per-dest compensator fires on
   dest-instance renderAll at THIS loop position (its write seam is woven on renderAll's
   `_glBindBuffer` INVOKE — plausibly covers; never exercised here). Kill-check, with its own
   lever as the A/B; discriminate from the KEPT window-MB feature.
6. **GPU-timed perf gate** (engineering judge): the is5.* PerfTimers are CPU-side and
   structurally blind to re-added GPU composite passes (~9 fullscreen passes + final per
   cross-dim view ≈ 1.5–5ms GPU/view on midrange). The default flip (if ever) gates on a
   GL_TIME_ELAPSED or whole-frame-FPS A/B on the scripted driver, not on PerfTimers. Consider
   leaving this a permanent quality option.
7. **framemod16+ phase** (engineering judge, disclosed): the counter bracket's 360360 offset
   preserves framemod2/4/8 only (mod 16 = +8); packs sequencing TAA jitter/dither on
   frameMod16+ get a half-period flip inside cross-dim views. Complementary r5.8.1 does not;
   un-witnessed, disclosed.
8. **History-stamp interaction** (engineering judge): the part5 out2 history write stamps
   captured content into MAIN history; a POST capture is already dest-TAA-resolved ⇒ main TAA
   re-filters it. Ring judge adjudicates the domain-match as an IMPROVEMENT (display-referred
   into post-tonemap tex2; double-TAA bounded by NeighbourhoodClamping). Disposition: keep,
   eyes on kill-check 8.
9. **Aux lemma wording** (ring judge): the pass-0-side aux read at TAIL is valid ONLY under the
   no-composite-writes-aux premise (measured true for Complementary c6); a buffer
   composite-written an odd number of times ends terminal-MAIN with NO swap pass and a stale
   pass-0 ALT. Do not inherit the "swaps normalize everything" lemma into future pack support.

## §3 KILL-CHECKS (first live leg, lever ON; any contradiction stops the stage)

Synthesis §kill-checks 1–14 stand, amended: (7) the SEVERE-branch relabel per §2.2; (8) the
DestPrevCamera discriminator per §2.5; (11) nested boundary inspected under MB whip + against a
bloom wall, rim = FAIL; new (15): two portals into the SAME dest dim — second window PRE
(storm absent there), no mutual shimmer; new (16): GPU-frame-time A/B recorded on the driver.

## §4 PENDING USER DECISIONS (implementation blocked on these)

1. **Nested disposition** — pick one: (a) RIM-ACCEPT: nested layers keep stamping into POST
   parents; disclosed soft rim + interior double-grade at nested cross-dim boundaries;
   (b) PRE-DEMOTE: any frame's nested-parenting views run PRE (storm only in childless
   cross-dim windows; recursion corridors keep status quo) — note "has nested children" is
   only knowable AFTER the parent captures (tail dispatch), so the demotion must key on
   LAST-frame nesting or on static config (maxPortalLayer), stated honestly;
   (c) SINGLE-LAYER: POST windows do not dispatch nested layers at all (part3-style deferral,
   announced once) — storm everywhere cross-dim, nested windows vanish inside them.
2. **Double-tonemap pre-acceptance** — the expected look of a POST window is the dest
   dimension's full look re-graded once by the source chain: at best slightly flatter/darker;
   the pack map predicts worse (SEVERE crushed darks). If the first leg lands in the SEVERE
   branch, the retreat is the lever (storm stays missing) — there is NO generic mitigation
   under the ring mandate + no-hacks policy. Proceed knowing that, or park the defect as
   disclosed?
