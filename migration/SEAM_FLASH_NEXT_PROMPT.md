Continue the Seamless Portals iris shaders-ON work in the worktree
C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow
(branch iris-on/is5-shadow, pushed).

READ FIRST, in order:
1. migration/SEAM_FLASH_HANDOFF.md — the governing doc. §2a the seven MEASURED facts, §2c the
   contradiction that defines the next step, §2d the next measurement (already specified), §2e what is
   REFUTED, §4 the standing rules and why they are in force.
2. Memory: seam-flash-open, mb-smear-open, no-guessing-deep-debug-logs, polish-rounds-lessons.
3. migration/MB_SMEAR_HANDOFF.md §00/§00a/§00b only if you need the closed smear engagement's detail.

STATE: the Motion-Blur portal-window smear is CLOSED, shipped DEFAULT-ON, user-confirmed live
("FINALLY NOT BLURRY"). Do not re-open it. Fixing it UNMASKED a pre-existing artifact, which is the
open problem.

THE OPEN PROBLEM IN ONE LINE: crossing a portal shows pure black exactly at the seam (slow crossing =
sustained, fast = a flash). Shaders OFF kills it; no pack setting touches it; -PdebugTintStamp proves
the stamp does not cover that band; it reproduces at pre-session 082d533; it is not IrisDestPrevCamera;
and the near-plane clip is mechanically exonerated.

START HERE — the contradiction is already isolated, do NOT re-derive it:
The IS5-SEAM census proves the aperture geometry COVERS the band (with -PdisableAperturePlaneClip the
counts read kept=2 clipped=0 dropped=0 on 18/18 rows) while -PdebugTintStamp proves no fragment is
PAINTED there. Geometry covers it, nothing lands.
=> the stamp's fragments are being REJECTED, not missing. Untested so far.

(a) Cheap first probe, already wired: -PdisableStampDepthWrite=true. If the band's shape or extent
    changes at all, depth state is implicated.
(b) The real test (§2d): add a third stamp pipeline sibling with the depth test DISABLED
    (Optional.empty() depth state — the shape PORTAL_STRAIGHT_COPY already uses), behind a diagnostic
    lever, selected exactly as PORTAL_AREA_SAMPLE_NO_DEPTH_WRITE is today.
      band fills in  => the depth test rejects the stamp; find why the snapshot depth wins at the seam
      band persists  => the stamp IS drawn and then overpainted; hunt what writes after it
                        (blit-back, aperture draw, hand rendering)

DISCIPLINE (non-negotiable, and this engagement is the case study — see handoff §4):
no guessing, diagnose-first with instrumentation; read the FULL latest.log every run; CHECK THE
`RUN CONFIG` BLOCK BEFORE ADJUDICATING ANY A/B (it caught two wrong-configuration verdicts last
session, one of which had already been reported as fact); never generalize from one sampled block; a
probe's failure sentinel is not a measurement; INSTRUMENT EVERY BRANCH, not just the one your
hypothesis predicts (a full-screen fallback was built, shipped to a live run, and refuted by its own
census in a single run precisely because the census logged the alternatives); A/B both directions;
every fix lever-gated with -P rows in BOTH fabric/build.gradle blocks, probes DEFAULT-OFF; multi-agent
panels with adversarial verification AND a final-diff pass for every non-trivial mechanism (last
session's panels caught three HIGH defects, all of them in the instrument rather than the fix); the
8-leg suite (:fabric:runCrossingGametest) before every commit; push every stage commit; git add
explicit file lists only; Java process cleanup per the global CLAUDE.md (own-project PIDs only, never
gradlew --stop).

ALSO WORTH KNOWING:
- The smear fix unmasked this artifact. The ENTIRE polish queue was assessed against a blurred portal
  window, so other "known fine" items may deserve a second look now that it is sharp.
- The MB bloom-ring commission (MB_SMEAR_HANDOFF §1c) was suspended as unjudgeable while the window was
  blurred. It is now judgeable.
- Temurin 25.0.2 C2 JIT defect: three victim methods are excluded in all three run blocks. A crash on a
  "C2 CompilerThread" with pure jvm.dll frames is that defect, not mod logic — add the new victim to
  the excludes and move on. Discriminate from the IS0 native-heap-corruption class by victim VARIETY.

Model tiers: Opus 5 for design/adjudication/adversarial verify; the lighter Opus (4.8) for mechanical
recon (javap, decompile, log tabulation).
