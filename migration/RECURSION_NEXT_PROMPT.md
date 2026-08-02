# PASTE PROMPT — next session (recursive portals shaders-ON)

Copy everything below the line into a fresh session.

---

Continue the Seamless Portals iris shaders-ON work in the worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow`
(branch `iris-on/is5-shadow`, pushed).

READ FIRST, in order:
1. `migration/RECURSION_SHADERS_ON_HANDOFF.md` — THE GOVERNING DOC for this engagement (the goal,
   the exact location of the one-layer cap, the three verified structural facts, the hazards, the
   first instruments, the discipline, and the queue).
2. `migration/THIRD_PERSON_CROSSDIM_HANDOFF.md` §9.2 and §9.3 — the two method sections that cost
   this project the most to learn. §9.2 is MEASURE THE DEPTH CONVENTION, DO NOT INFER IT (bytecode
   said one thing, the draw said another). §9.3 is the standing warning: five leads died in that arc
   and all five were the assistant's own.
3. Memory: `firstperson-seam-window-panning-open`, `third-person-crossdim-corruption-open`,
   `temurin-c2-jit-crash`, `no-guessing-deep-debug-logs`, `polish-rounds-lessons`.

## THE TASK

**Make portals RECURSIVELY visible with shaders ON.** Looking through portal A at portal B should
show B's view inside A's window, to the engine's depth bound. Shaders OFF this ALREADY WORKS — the
target is parity with that, not a new feature.

## WHAT IS ALREADY ESTABLISHED (verified in-tree; do not re-derive)

- The cap is one early-return: `IrisCompatOn262Renderer.doRenderPortal` ~:334,
  `if (PortalRendering.isRendering()) { /* only supports one-layer portal (IP-verbatim) */ return; }`
- `IPGlobal.maxPortalLayer = 5` is the engine bound; the shaders-OFF stencil family honours it.
- **The real blocker is not that guard** — the compat renderer owns exactly ONE
  `SecondaryFrameBuffer deferredBuffer`, and its snapshot → loop → stamp → blit-back shape assumes
  one snapshot for one layer. Remove the guard alone and layer 2 clobbers layer 1 mid-frame.
- **IP's own multi-layer shaders renderer is HELD IN-TREE AND DORMANT and already solves this:**
  `compat/iris_compatibility/IrisPortalRenderer.java` keeps `deferredFbs[maxPortalLayer + 1]`
  (:93/:98) and bounds on `innerLayer > getMaxPortalLayer()` (:287). **Read it before designing.**
  The likely shape of the work is adopting its per-layer buffer model, not inventing recursion.

## HAZARDS THE ONE-LAYER SHAPE CURRENTLY HIDES (handoff §3 has the full list)

Frame-scoped singletons that need a stack discipline under real recursion:
`IrisTemporalTargetGuard` save/restore, `IrisShadowCompositeSuppressor` install/uninstall, the IS5-PH
prev-uniform heal. Plus: the IS0 anchor fires once per frame (layer 2+ must nest inside it, not
re-enter it); a documented multi-portal occlusion-query defect that recursion multiplies; frame cost
(layer n is a full pack-shaded world render — expect to need a shaders-ON-specific bound); and the
XWIN cross-view pass's `!isRendering()` recursion floor must stay meaningful.

## START HERE

Arm `-PtpXdimCensus` and confirm the BASELINE before touching code: `portalLayerAtEnd` should read 1
shaders-ON and >1 shaders-OFF. That is the number the fix has to move, and shaders-OFF is your
oracle throughout (`K` toggles the pack in-game).

## MEASURED FACTS THAT CONTRADICT COMMENTS IN THE TREE

`clipDepthMode = NEGATIVE_ONE_TO_ONE` (39/39 at the draw) and `range=[0,1]` (26/26) ⇒ clip space is
**[-1,1]**, buffer is **small-is-near / LEQUAL**. The tree still contains reversed-Z comments; they
are WRONG. `GlDevice` really does call `glClipControl(LOWER_LEFT, ZERO_TO_ONE)` — something (most
plausibly iris) sets it back before our draws. **Bytecode is not ground truth; the draw is.**

## DISCIPLINE (non-negotiable)

No guessing — diagnose with instrumentation first. Read the FULL `latest.log` every run (and beware
that Minecraft ROTATES `latest.log` at startup: grepping too early matches the PREVIOUS run's
content — this bit me). CHECK THE `RUN CONFIG` BLOCK and the once-only self-report lines before
adjudicating ANY leg — a leg without its landing proof is VOID, not a refutation. ONE VARIABLE PER
LEG. Every fix A/B-proven in BOTH directions, with a lever that reproduces the defect on command.
Verify the instrument before believing the instrument. GL state only at `GlCommandEncoder.trySetup`
RETURN. `.\gradlew.bat :fabric:runCrossingGametest` before every commit; push every stage commit;
`git add` explicit file lists only; Java cleanup by own-project PID only, never `gradlew --stop`.

Run the client:
`.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PtpXdimCensus=true`

## THE QUEUE AFTER THIS

1. **The C2 JIT defect** (memory `temurin-c2-jit-crash`). Six victims, five in the portal
   occlusion-query / `doRenderPortal` region; crashes on BOTH Temurin 25.0.2 and Zulu 25.0.4, so it
   is a CODE-SHAPE problem, not a vendor one. Six `-XX:CompileCommand=exclude` rows are the current
   mitigation and are load-bearing — **a seventh exclude is not the answer.** NOTE the overlap: this
   recursion arc will touch `doRenderPortal`, which is victim #6.
2. **The MB bloom-ring commission** — `MB_SMEAR_HANDOFF` §1c, one look with Motion Blur explicitly ON.
