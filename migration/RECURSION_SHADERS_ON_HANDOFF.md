# RECURSION-ON HANDOFF — make portals RECURSIVELY visible with shaders ON

**Status: OPEN, NOT STARTED.** Queued by the user 2026-08-01 as the next engagement, ahead of the
C2 JIT defect and the MB bloom-ring commission. Branch `iris-on/is5-shadow`, worktree
`C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow`.

---

## §1 THE GOAL

Shaders ON: looking through portal A at portal B should show B's view *inside* A's window, and so on
to the engine's depth bound. Today, shaders ON, only the FIRST layer renders — a portal seen inside
a portal window renders as **pass-through** (you see the dest world with a flat, non-recursive hole
where the nested portal is).

Shaders OFF this already works. So the target is **parity with the shaders-OFF behaviour**, not a
new feature.

---

## §2 THE CAP IS DELIBERATE AND ITS LOCATION IS KNOWN (do not re-derive)

`IrisCompatOn262Renderer.doRenderPortal` (~:334):
```java
if (PortalRendering.isRendering()) {
    // this renderer only supports one-layer portal (IP-verbatim)
    return;
}
```
That early-return is the whole cap. It is not a bug — it is an inherited simplification, faithful to
the held IP source this renderer was ported from.

Three surrounding facts, all verified in-tree:

1. **The engine bound is 5, not 1.** `IPGlobal.maxPortalLayer = 5` (`IPGlobal.java:42`), runtime-
   settable via `ClientDebugCommand:1106`. `RendererUsingStencil` (the live shaders-OFF family)
   recurses against that bound normally — which is why shaders-OFF already looks right.
2. **The compat renderer owns exactly ONE deferred buffer.**
   `private final SecondaryFrameBuffer deferredBuffer = new SecondaryFrameBuffer();`
   The whole snapshot → per-portal loop → stamp → blit-back shape in `onBeforeHandRendering` assumes
   a single snapshot for a single layer. **This is the real structural blocker, not the early-return
   above** — deleting the guard without addressing the buffer would have layer 2 overwrite layer 1's
   snapshot mid-frame.
3. **IP's OWN multi-layer shaders renderer is HELD IN-TREE, DORMANT, and already solves this.**
   `IrisPortalRenderer` (`compat/iris_compatibility/IrisPortalRenderer.java`) keeps an ARRAY:
   ```java
   if (deferredFbs.length != PortalRendering.getMaxPortalLayer() + 1) {   // :93
       deferredFbs = new SecondaryFrameBuffer[PortalRendering.getMaxPortalLayer() + 1];  // :98
   }
   ...
   if (innerLayer > PortalRendering.getMaxPortalLayer()) { ... }          // :287
   ```
   **Read this class before designing anything.** It is the architecture the fix wants, written by
   IP, sitting in the repository unused. The likely shape of the work is "adopt its per-layer buffer
   model into the 26.2 compat renderer", not "invent recursion".

---

## §3 WHY THIS IS HARDER THAN DELETING THE GUARD

Each nested layer runs a full dest render, and this arc has just finished proving how much shared,
persistent state one nested render disturbs. Every item below is a live hazard the one-layer shape
currently hides:

- **The IS0 anchor fires once per frame.** The whole compat pass (snapshot, stamp, blit-back, and all
  its state hygiene) is driven from `onBeforeHandRendering`. Layer 2+ must nest *inside* that, not
  re-enter it.
- **`IrisTemporalTargetGuard` save/restore, `IrisShadowCompositeSuppressor` install/uninstall, and
  the IS5-PH prev-uniform heal are all frame-scoped singletons.** Under real recursion they need a
  stack discipline or they will unwind in the wrong order. `IrisShadowCompositeSuppressor` in
  particular must not be uninstalled by an inner layer while an outer layer still needs it.
- **The occlusion query already has a documented multi-portal defect** (`IrisCompatOn262Renderer`
  ~:590-606): for the 2nd+ portal in a frame the query tests against a main-target depth that the
  previous portal's dest render already replaced. Recursion multiplies that.
- **Frame cost.** Layer *n* is a full pack-shaded world render. `maxPortalLayer=5` shaders-ON could be
  brutal; expect to need a shaders-ON-specific bound and to justify its default.
- **The XWIN cross-view pass** (`SecondaryWorldRenderCore.maybeRunCrossViewPortalPass`, landed
  `9cf9d46`) dispatches the portal pass on cross-view frames with a `!isRendering()` recursion floor.
  Whatever recursion design lands must keep that floor meaningful.

---

## §4 FIRST INSTRUMENTS (specified — build/arm before designing)

1. **The existing census already answers "how deep did we go".** `-PtpXdimCensus` prints
   `portalLayerAtEnd` and `invoke=` per frame. Arm it and confirm the live depth is 1 shaders-ON and
   >1 shaders-OFF **before** touching anything — that is the baseline the fix must move.
2. **`ActSeedProbe.onPortalListSize`** already reports the listed (pre-occlusion) portal count. Pair
   it with the layer to see whether nested portals are being *discovered* and then dropped by the
   guard, versus never discovered.
3. **The shaders-OFF comparison is the oracle.** Same world, same portals, `K` to toggle. Any design
   should be checkable as "does shaders-ON now match what shaders-OFF has always shown here".

---

## §5 THE DISCIPLINE (binding — this arc's own scar tissue)

Everything in `THIRD_PERSON_CROSSDIM_HANDOFF.md` §9.3 applies. The short form:

- **Measure at the draw, never infer.** In this arc, five leads died and all five were mine — each
  from reasoning off something that *felt* like ground truth (an intuition, a code comment, bytecode,
  a clean run). `GlCommandEncoder.trySetup` RETURN is the only trustworthy slot for GL state.
- **Verify the instrument before believing the instrument.** Three probes were lying when this arc
  reached them: one aimed where the target wasn't, one matching a string no shader contains, one
  printing `0` in the block meant to prove it ran.
- **A leg without its landing proof is VOID, not a refutation.** Check the `RUN CONFIG` block and the
  once-only self-report lines before adjudicating anything.
- **Every fix A/B-proven in BOTH directions.** The lever must reproduce the defect on command.
  "It looks better" has passed on a no-op in this project before.
- **One variable per leg.** Broken once in this arc (the JDK/excludes confound) and it cost a wrong
  conclusion that shipped into a commit message.
- `.\gradlew.bat :fabric:runCrossingGametest` before every commit; push every stage commit;
  `git add` explicit file lists only; Java cleanup by own-project PID only, never `gradlew --stop`.

---

## §6 STATE OF THE TREE AT HANDOFF (2026-08-01)

Closed and user-confirmed this session, each A/B-proven both ways:
- third-person cross-dim whole-screen terrain explosion — `4be60e0`
- no portal window inside the cross view — `9cf9d46`
- first-person seam window shape-shift (per-vertex → per-fragment depth floor) — `7fd747a`
- sharp-window polish re-audit — `69c5acc` (3 firm, 2 provisional; §6.1 there records why)

Shipped DEFAULT ON, do not disturb without an A/B: the hand depth bracket
(`-PdisableHandSeamDepthBracket`), the per-fragment near floor (`-PdisableXcutFragFloor`), the
cross-view full-pipeline route (`-PdisableCrossViewFullPipeline`), the cross-view reverse window
(`-PdisableCrossViewReverseWindow`).

**MEASURED FACTS that contradict stale comments in the tree** — trust these, not the comments:
`clipDepthMode = NEGATIVE_ONE_TO_ONE` (39/39 at the draw) and `range=[0,1]` (26/26), so clip-space z
is **[-1,1]** and the buffer is **small-is-near / LEQUAL**. The tree still contains reversed-Z
comments; they are wrong. Note `GlDevice` *does* call `glClipControl(LOWER_LEFT, ZERO_TO_ONE)` —
something (most plausibly iris) sets it back before our draws. Bytecode is not ground truth here.

---

## §7 THE QUEUE AFTER THIS (user-set 2026-08-01)

1. **THIS** — recursive portal visibility, shaders ON.
2. **The C2 JIT defect** — memory `temurin-c2-jit-crash`, report draft
   `migration/C2_JIT_BUG_REPORT.md`. Six victims, five in the portal occlusion-query /
   `doRenderPortal` region, crashes on BOTH Temurin 25.0.2 and Zulu 25.0.4, so it is a code-shape
   problem and not a vendor problem. The diagnosis: the chain compiles into ONE unit of 300-600
   inlined methods (counted from the replay files), and C2 dies at a fixed point in it — every crash
   faults reading `0x2c` with `RAX=0`. Six `CompileCommand=exclude` rows are the current mitigation
   and are load-bearing. **A seventh exclude is not the answer.**

   > ### ★ USER DECISION 2026-08-01 — THE `dontinline` EXPERIMENT IS DEFERRED UNTIL AFTER THIS ARC
   > The prepared fix is ONE flag replacing the six exclusions:
   > ```
   > -XX:CompileCommand=dontinline,qouteall.imm_ptl.core.render.ViewAreaRenderer::renderPortalArea
   > ```
   > (chosen because it sits BELOW every observed crash root, so unlike `exclude` it cannot let C2
   > re-root on a sibling; it keeps everything JIT-compiled; and it also covers the shaders-OFF
   > stencil renderer, which shares the identical call shape and is unprotected today).
   >
   > **DO NOT run it before the recursion work.** The user's reasoning, and it is right: this arc
   > will restructure `doRenderPortal` — crash victim #6 — so the inlining tree is about to change.
   > Testing the flag against a tree that is about to be rewritten would measure the wrong thing, and
   > a clean result would have to be re-earned afterwards anyway.
   >
   > **KEEP THE SIX EXCLUDES IN PLACE MEANWHILE.** They are the working mitigation. Do not "tidy"
   > them during the recursion work — that mistake has already been made once this session
   > (`8b96a3e`, reverted by `c258873`).
   >
   > **The recursion arc is itself an unplanned experiment on the tree — watch it.** If crash
   > frequency, or the victim named in `Current CompileTask`, changes while recursion work is in
   > flight, that is free evidence about the mechanism. Record any new `hs_err_pid*.log` rather than
   > dismissing it as "the known crash".
   >
   > **When the time comes, two traps:**
   > (a) **Do NOT accelerate with `-Xcomp` or `CompileThreshold=1`** — they destroy the receiver-type
   > profile on `renderingFunc.run()`, and that profile is what drives the inline welding the tree
   > together, so they would produce a FALSE NEGATIVE.
   > (b) **A clean run is weak evidence** (historical MTTF 182-999 s). Gate on the MECHANISM, not on
   > silence: with `-XX:CompileCommand=PrintInlining,...doRenderPortal`, require the literal
   > `renderPortalArea … failed to inline: disallowed by CompileCommand` line AND that the printed
   > tree shrank from ~459 nodes to ~100. If you cannot show the tree shrank, "no crash" means
   > nothing.
   >
   > **There may also be an offline reproducer already sitting on disk:** four HotSpot replay files
   > (`fabric/runs/client-sodium/replay_pid*.log`, one `compile` record each) may re-run the exact
   > failing compilation via `-XX:+ReplayCompiles` in seconds, with no game. Untested. If it works it
   > makes every A/B instant and the upstream report far more actionable.
3. **The MB bloom-ring commission** — `MB_SMEAR_HANDOFF` §1c, one look with Motion Blur explicitly ON.
