Continue the Seamless Portals iris shaders-ON work in the worktree
C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow
(branch iris-on/is5-shadow @ 2f79017, pushed).

READ FIRST, in order:
1. migration/SEAM_FLASH_HANDOFF.md — §00a is the governing section (the OPEN hand engagement:
   the adjudicated legs, the stage-diff verdict, the two specified next instruments). §00 is the
   CLOSED band engagement (user-confirmed "no black band and bloom ring is fixed") — do NOT
   re-open it; §2's ladder is history.
2. Memory: seam-hand-slicing-open, seam-flash-open (the method lessons), mb-smear-open,
   no-guessing-deep-debug-logs, polish-rounds-lessons.

STATE: the black seam band is CLOSED (C4-SEAM: the C3-BLOOM mask's repaints now draw under the
stamp's depth clamp, DEFAULT ON) and the bloom ring is fixed — both user-confirmed live in one
leg. Shipped-and-kept from the hand hunt (harmless, verifier-passed): the stamp NDC-z 0.5 cap
(-PdisableStampHandDepthCap) and the iris-hand glDepthRange bracket
(-PdisableHandSeamDepthBracket). The V2 crossing-window clip suspension also stays (real
void/flash class).

THE OPEN PROBLEM IN ONE LINE: shaders-ON, the first-person hand slices away along the portal
seam while crossing (source→dest: progressive slice then whole-hand pop-back; dest→source:
instant vanish then part-by-part return); shaders-OFF is immune.

DO NOT RE-RUN — already adjudicated, every leg config-proven in the log:
- front_clipping disable ⇒ unchanged (clip family EXONERATED; hand programs loc==-1, store
  disarmed at every hand draw);
- shaders OFF ⇒ intact (iris bakes the hand INSIDE LevelRenderer.render pre-anchor and no-ops
  vanilla's post-anchor, depth-wiped hand draw — the immunity);
- stamp depth cap (vsh=capped proven) ⇒ unchanged;
- iris-HandRenderer depth bracket (ARMED line + census proven) ⇒ unchanged;
- four-point stage diff, 15 crossing frames ⇒ A→B and C→D byte-clean; THE HAND IS ALREADY
  ABSENT AT STAGE A on 13/15 blocks (anchor mainRT holds the grazing shell 0.981–0.994 where
  the hand slice 0.554–0.556 should be).

⇒ THE EATER ACTS INSIDE IRIS'S renderLevel, upstream of the whole compat pass, and (per the
refuted bracket) it is NOT a depth-test loss. The open split: the hand is NEVER DRAWN at those
pixels (something culls/clips its geometry pre-raster) vs DRAWN INTO COLORTEX THEN EATEN by a
pack composite (TAA/reprojection rejecting the hand at the seam) before iris finalizes.

START HERE — the next instruments are specified, do NOT re-derive:
(1) IN-renderLevel STAGE POINTS: extend the stage-diff idea inward using the ALREADY-LANDED
    MixinIrisHandRenderer_SeamDepthBracket hooks (HEAD/RETURN of renderSolid and
    renderTranslucent). Read the hand-region pixels of the LIVE DRAW TARGET right after each
    hand pass and compare with stage A: hand present post-pass but gone at A ⇒ composite-eaten
    (then bisect iris's composite/final chain); hand absent immediately post-pass ⇒ never drawn
    (then instrument HandRenderer's submit path / canRender / scissor-viewport state). NOTE the
    hand draws into iris's gbuffer targets there, NOT mainRT — resolve the live draw FBO
    (GlStateManager.getFrameBuffer) or read iris colortex0 via the IrisTemporalTargetGuard
    target-access plumbing. Chassis: SeamHandStageDiff (FBO resolver + the S14.21/IS0
    pack-state bracket — copy it exactly; an un-bracketed readback corrupts the native heap).
(2) THE CAP-VIOLATION INSTRUMENT AUDIT: stage C measured the stamp writing depth ≈0.655 ABOVE
    the 0.5 cap while the once-only line reported vsh=capped. Settle whether the built
    resources actually carried the capped .vsh into that run (jar/resource check or a live
    glGetShaderSource dump) before ANY further cap-based reasoning — a stale-shader instrument
    break here poisons every depth argument.

DISCIPLINE (non-negotiable — handoff §4, plus this arc's own additions):
no guessing, diagnose-first with instrumentation; read the FULL latest.log every run; CHECK THE
RUN CONFIG BLOCK + the relevant once-only self-report lines BEFORE adjudicating ANY leg (a leg
without its landing proof is VOID, not a refutation); never generalize from one sampled block;
a probe's failure sentinel is not a measurement; instrument every branch; A/B both directions;
ATTRIBUTE WITH THE NARROWEST LEVER THAT EXISTS and audit what else a toggle touches before
adjudicating (the front_clipping dual-effect confound cost two fix iterations); REACH FOR THE
STAGE DIFF BEFORE THE THIRD HYPOTHESIS, not after (three mechanism-consistent fixes were
refuted in a row; the hypothesis-free stage diff named the true stage in one leg); every fix
lever-gated with -P rows in BOTH fabric/build.gradle blocks, probes DEFAULT-OFF; multi-agent
panels with adversarial verification AND a final-diff pass for every non-trivial mechanism;
the 8-leg suite (:fabric:runCrossingGametest) before every commit; push every stage commit;
git add explicit file lists only; Java cleanup per the global CLAUDE.md (own-project PIDs only,
never gradlew --stop).

ALSO WORTH KNOWING:
- Queue after the hand: (a) the MB bloom-ring commission (MB_SMEAR_HANDOFF §1c — under Motion
  Blur, composite4 gains DRAWBUFFERS:30 and the mask slides AFTER the bloom gather; one look
  with MB explicitly ON judges it); (b) the sharp-window polish re-audit (the ENTIRE polish
  queue was assessed against a blurred window).
- The diagnostic kit in the tree: SeamHandStageDiff (-PhandStageDiff), SeamDestContentProbe
  (-PseamContentProbe, incl. the hand-region sampler), SeamClipArmCensus (always-on),
  IS5-SEAM census (always-on), DrawCallTrace (/imm_ptl_client_debug debug_capture_frame_enable),
  the solid/no-depth-test stamp siblings.
- Temurin 25.0.2 C2 JIT defect: three victim methods are excluded in all three run blocks. A
  crash on a "C2 CompilerThread" with pure jvm.dll frames is that defect — add the new victim
  to the excludes and move on. Discriminate from the IS0 native-heap class by victim VARIETY.
- Model tiers: Fable 5 for design/adjudication/adversarial verify; the lighter Opus for
  mechanical recon (javap, decompile, log tabulation).
