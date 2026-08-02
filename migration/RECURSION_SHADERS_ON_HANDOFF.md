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
2. **The C2 JIT defect** — memory `temurin-c2-jit-crash`. Six victims, five in the portal
   occlusion-query / `doRenderPortal` region, crashes on BOTH Temurin 25.0.2 and Zulu 25.0.4, so it
   is a code-shape problem and not a vendor problem. Six `CompileCommand=exclude` rows are the
   current mitigation and are load-bearing. **A seventh exclude is not the answer** — the question is
   what about that region C2 cannot compile. NOTE the overlap: recursion work will touch
   `doRenderPortal`, which is victim #6, so this arc may perturb it either way.
3. **The MB bloom-ring commission** — `MB_SMEAR_HANDOFF` §1c, one look with Motion Blur explicitly ON.
