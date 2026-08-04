Continue the Seamless Portals iris shaders-ON work in the worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow`
(branch `iris-on/is5-shadow`, tip `626d856`, pushed).

READ FIRST, in order:
1. `migration/OCCLUDER_RING_HANDOFF.md` — THE GOVERNING DOC. The measured mechanism, the eight verified
   negatives, the instrument, the traps, and the one architectural question that remains.
2. `migration/MB_BLOOM_SEAM_HANDOFF.md` §7 — the full evidence log for that arc, §7a through §7l in
   order. Read it for provenance when you doubt a claim in the handoff; it records what was measured,
   when, and which of my conclusions were wrong.
3. Memory: `occluder-ring-mechanism`, `session-2026-08-03-lessons`, `ab-a-feature-that-cannot-bite`,
   `pack-options-drift-mid-arc`, `mb-bloom-seam-open`, `no-guessing-deep-debug-logs`,
   `measure-at-the-draw-lessons`, `temurin-c2-jit-crash`.

## THE TASK — decide whether the occluder ring is fixable, and if so fix it

**The bug, measured, not described.** A ring of SOURCE-world content appears around the silhouette of
any occluder between the camera and a portal window — a block, the obsidian frame, or the player in
third person. It is ONE artifact; motion blur amplifies it enormously but does not cause it. With MB
off the effect is at or below one pixel — a BOUND, not a null.

**The mechanism.** The stamp composites with a HARD DEPTH TEST onto an image whose COLOUR has already
been moved past its own silhouette by a pass that never touched DEPTH. The ring is exactly the pixels
the smear moved. Per-pixel proof, MB on:
`345:M ff00ff /0.988821 || 346:- 908ca4 /0.975004  347:- 574643 /0.975004`
— pixel 346 holds the log's depth BIT-IDENTICALLY to the bark at 347 while carrying terrain colour.

**The question.** The main frame's entire pack post stack runs BEFORE the portal is stamped, so
coverage is binary and depth-derived while the smear is colour-only and non-local. Is that fixable
here, or is it inherent to compositing after the pack's antialiasing? A plain, well-evidenced "this is
inherent, here is the least-bad mitigation" is a legitimate and valuable outcome — do not force a fix.

## START HERE — one keypress, before any design

**Does the ring appear with shaders OFF?** The shaders-OFF renderer `RendererUsingStencil` decides
coverage with a STENCIL, which is exact per-pixel and immune to colour filtering. If that path is
clean, that is simultaneously the proof of the mechanism and the shape of the fix (candidate 2 in the
handoff §4). If it is NOT clean, the mechanism as stated is wrong and everything downstream needs
re-deriving. It costs nothing and it discriminates "inherent to portals" from "a property of the
shaders-ON compositing strategy".

Then, before writing any code, get the user's answer to: **is a one-pixel residual with motion blur
off acceptable, if the motion-blur case is fixed?** That decides whether the target is the amplifier
or the mechanism, and it is their call, not yours.

## DO NOT RE-RUN THESE — eight candidates, each dead on a verified negative

pack bloom (reproduces with the gather compiled out) · the pack's unsharp filter (`IMAGE_SHARPENING=0`
confirmed on disk) · TAA jitter (`TAA_JITTER=0` on disk) · the stamp's own footprint (magenta leg) ·
buffer-size mismatch (geometry witness `match=true`) · the snapshot copy (`MAIN` vs `DEFER`
byte-identical over 17 samples) · the C3-BLOOM mask · IS5-REC single-slot nesting.

**And do not disturb the shipped bloom fix** (`4b913a5`, IS5-BLOOMMB, DEFAULT ON, user-confirmed both
directions, lever `-PdisableBloomMaskGathererRetarget`).

## HAZARDS THIS ARC PAID FOR

- **Pack options are user-side state with NO mod-side witness.** They drifted mid-arc and made two legs
  incomparable. `cat fabric/runs/client-sodium/shaderpacks/<pack>.zip.txt` at the START of every leg.
  **An ABSENT key means the pack DEFAULT** (look it up in `shaders/lib/common.glsl`), not "off". The
  file records only a session's FINAL state, so it cannot vindicate a mid-session toggle — if a leg
  depends on an option, set it on disk before launch.
- **Before A/B-ing a feature, check whether it is in a configuration where it CAN bite.** A null result
  from an already-inert feature is not an exoneration. This cost a wrong verdict; the proof was sitting
  in the previous leg's log.
- **A lever's conclusion is only as good as where it was AIMED.** `-PdebugStampSolid` gave opposite
  answers at the frame edge versus a block inside the window — same lever, same build.
- **PRINT THE AIM ON EVERY PROBE LINE.** Three of four defects in the new probe were aim; the only one
  that cost nothing was caught by its own aim field.
- **A resolution limit is a BOUND, not a finding.** "My instrument cannot see it" is a statement about
  the instrument. Saying otherwise split one bug into two and the user had to correct it.
- **`masks=`/`misses=` is never a gate** — `masks=10148 misses=0` was logged while the ring was plainly
  visible. Counters record draws issued, never effect achieved.
- **Declared vs executed depth state disagree here.** The stamp declares `GREATER_THAN_OR_EQUAL`; the
  draw measures small-is-near, `clipDepthMode=NEGATIVE_ONE_TO_ONE` 39/39. Never design from the
  declaration.
- `runCrossingGametest` runs **iris-ABSENT** and cannot reach any of this code — a regression gate, not
  a proof.

## DISCIPLINE (non-negotiable)

No guessing — instrument first, and prefer RAW PER-PIXEL DUMPS to any classifier at a boundary; every
summarising encoding hid this mechanism. Read the FULL `latest.log` each run, and beware that Minecraft
ROTATES it at startup. **Verify every lever on the LIVE PROCESS** via the `IS5-RC [1/3]` block, never
from config. **Verify the instrument before believing it** — ask what input would make it print the
GUILTY answer and what would make it print the INNOCENT one; if you cannot answer both, it is not
specified well enough to build. GL state only at `GlCommandEncoder.trySetup` RETURN. ONE VARIABLE PER
LEG. Every fix A/B-proven in BOTH directions with a lever that reproduces the defect on command. A leg
without its landing proof is VOID, not a refutation. **When an inference and the user's observation
disagree, the observation wins — and asking costs one message.** Four wrong conclusions in the last
session reached the user before the record; not one was corrected by more reasoning.

`.\gradlew.bat :fabric:runCrossingGametest` before every commit, capturing enough output to read the
leg lines. Push every stage commit; `git add` explicit file lists only; Java cleanup by own-project PID
only, never `gradlew --stop`.

Run the client:
`.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true`

With the coverage probe:
`.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PstampCoverageProbe=true -PdebugStampSolid=true -PdebugTintStamp=true`

## AFTER THIS

- The **C2 upstream bug report** (`migration/C2_JIT_BUG_REPORT.md`) is STALE — it predates the
  2026-08-02 findings: the on-demand reproduction recipe, the profile-dependence mechanism, the refuted
  offline reproducer, and the proof that `dontinline` fixes it. Rewrite before filing at
  https://bugreport.java.com/ (Java SE / hotspot / compiler).
- A parked `GL_INVALID_OPERATION: Invalid format` from iris `RenderTargets.copyPreHandDepth` during a
  nested cross-dim render (3 occurrences, only at nether pipeline creation).
- `maxPortalLayer` is never synced to dedicated servers, so the portal-chain chunk loader follows the
  server's value there.
