# C2 JIT PROTECTION — what runs, why, and how to change it

**Status as of 2026-08-02: the six `exclude` rows are RETIRED, replaced by ONE `dontinline` flag.**
This document is the whole record: the defect, the six victims and why they existed, the evidence
that replaced them, how to revert, and how to reproduce the crash on demand.

---

## §1 THE DEFECT — it is a HotSpot bug, not mod code

C2 (HotSpot's optimising JIT) crashes while compiling one specific call chain in the portal
occlusion-query path. The game dies instantly.

**The signature — all four must hold before attributing a crash to this:**

1. `Current thread: JavaThread "C2 CompilerThread0"` — a compiler thread, not render/main
2. `Problematic frame: V [jvm.dll+0x…]` — **stable per JVM BUILD**, not universal:
   Temurin 25.0.2 = `+0x73238f`, Zulu 25.0.4 = `+0x741a7f`
3. `Native frames:` are **all** `jvm.dll` — no mod, no LWJGL, no GPU driver
4. **Zero Java frames** — a compiler thread has none

Every crash faults reading offset `0x2c` from a null base (`RAX=0`), on **both vendors**. It is
upstream, not vendor-specific. Report draft: `migration/C2_JIT_BUG_REPORT.md` →
https://bugreport.java.com/ (Java SE / hotspot / compiler).

**Crash files land in `fabric/runs/client-sodium/hs_err_pid*.log`** — NOT in `crash-reports/`. If you
have a `crash-reports/*.txt` instead, it is a Minecraft-level crash and **not this defect**.

---

## §2 THE MECHANISM — one inlining tree, not N methods

The victims are not independent. They are one call chain of mostly tiny methods, which C2 inlines
into a single compilation unit:

```
doRenderPortal (215 B, try/finally => the "!" flag on its compile task)
  └ testShouldRenderPortal (12 B)
      └ PortalRenderInfo.renderAndDecideVisibility(Portal, Runnable)   177 B
          └ GlQueryObject.performQueryAnySamplePassed(Runnable)
              └ performQuery(Runnable, int)                             56 B
                  └ runnable.run()                                      12-16 B, capturing lambda
                      └ ViewAreaRenderer.renderPortalArea(...)          large GL + mesh leaf
```

A capturing lambda threaded through three call levels, devirtualised, expanding into a large leaf.
The failing unit measures **312–597 methods** across four captured crashes.

**It is PROFILE-DEPENDENT, which is why it seemed random.** `renderAndDecideVisibility` is 177 bytes,
which sits *between* C2's two inline-size limits — `MaxInlineSize` (35) and `FreqInlineSize` (325).
C2 decides once, when it compiles the caller, using the counts it has at that moment:

| call-site profile | decision | unit | outcome |
|---|---|---|---|
| cold (`iicount=451`) | "callee is too large" | 51 methods | no crash |
| hot (`iicount=5504`) | inlines | 385 methods | **crash at 528 s** |

---

## §3 THE SEVEN VICTIMS — and why `exclude` never converged

Each `exclude` moved the crash one frame over, because excluding a method just makes C2 re-root the
same weld on a neighbour.

| # | victim | date | note |
|---|---|---|---|
| 1 | `PortalRenderInfo::renderAndDecideVisibility` | | |
| 2 | `IOWorker::storePendingChunk` | | |
| 3 | `GlQueryObject::performQuery` | | 56 B |
| 4 | `IrisCompatOn262Renderer$$Lambda*::run` | 2026-08-01 | the lambda **proxy bridge** |
| 5 | `IrisCompatOn262Renderer::lambda$testShouldRenderPortal$0` | 2026-08-01 | the **synthetic body**, with #4 already excluded |
| 6 | `IrisCompatOn262Renderer::doRenderPortal` | 2026-08-01 | **Zulu** — disproved the vendor theory |
| 7 | `IrisCompatOn262Renderer$$Lambda*::run` again | 2026-08-02 | on a tree since rewritten by the recursion arc |

**★ A LAMBDA IS TWO COMPILABLE THINGS.** #4 excluded the `$$Lambda/0x…` proxy bridge; C2 immediately
crashed on the *synthetic body* `lambda$<method>$N`, which lives on the enclosing class. Excluding one
never covers the other — and the same trap exists on the **measurement** side: when checking inlining
unit sizes, enumerate BOTH forms or you will measure the wrong root and conclude the weld is gone.

---

## §4 WHAT RUNS NOW — `dontinline`, and why it beats six excludes

```
-XX:CompileCommand=dontinline,qouteall.imm_ptl.core.render.ViewAreaRenderer::renderPortalArea
```

1. **It sits BELOW every observed crash root**, so C2 cannot re-root on a sibling the way `exclude`
   allowed. The thing that makes the unit enormous is the large leaf; refusing to inline it splits
   one giant unit into normal-sized ones.
2. **Everything stays JIT-compiled.** `exclude` forced six per-frame render methods — including the
   occlusion query and `doRenderPortal` — to run interpreted, forever.
3. **It also covers the shaders-OFF stencil renderer**, which shares the identical call shape and was
   completely unprotected under the exclude scheme.

### The evidence (leg E2, 2026-08-02)

Run: `-Pc2NoProtection=true` equivalents removed; unprotected except for this flag, measured with
`LogCompilation`. All three pre-registered criteria passed:

| criterion | result |
|---|---|
| flag reached C2 and bit | 4 × `<inline_fail reason='disallowed by CompileCommand'>` |
| the giant unit collapsed | victim unit **385 → 0** inlines |
| survived the crash point | **1088.8 s (18 m 09 s) = 2.06×** leg C's 528.6 s crash |

Under the exact recipe that crashed leg C at 8 m 49 s, with zero excludes.

**Honest limits:** one 18-minute leg. Historical MTTF is 182–999 s, so this clears the top of the
band by ~90% — convincing, not an order of magnitude. The excludes are preserved behind a lever
rather than deleted.

---

## §5 THE LEVERS

| lever | effect |
|---|---|
| *(default)* | the `dontinline` flag above |
| `-Pc2LegacyExcludes=true` | the historical six `exclude` rows INSTEAD — the revert path |
| `-Pc2NoProtection=true` | **neither** — the reproduction config, and the only one that can prove anything about the defect |
| `-Pc2Inlining=true` | adds `LogCompilation` → `fabric/runs/<run>/c2_inlining.xml` for measuring unit sizes |

Applied to the three run blocks that carry protection: `client`, `clientSodium`, `crossingGametest`.

---

## §6 HOW TO REPRODUCE THE CRASH ON DEMAND

Established 2026-08-02 (leg C, `hs_err_pid404416`, 528.6 s, 385-method unit).

```bash
./gradlew.bat :fabric:runClientSodium -PirisRuntime=true -Pc2NoProtection=true -Pc2Inlining=true
```

Then, and the order matters more than the duration:

1. **Go straight to a dense portal scene on world load.** Do not wander first. C2 decides the inline
   ONCE using the counts it has then — warming up gently makes it decide while cold, which breaks
   the chain for the wrong reason and produces a false negative.
2. Many portals in one view, seen through each other; recursion depth high.
3. **Shaders ON throughout, no `K`.** Toggling makes the `runnable.run()` call site bimorphic, which
   can stop C2 devirtualising and prevent the weld forming at all.
4. Cross-dimension teleports, returning to the dense viewpoint — each dimension load forces
   recompiles, and every recompile is another chance.

Expect a crash in roughly 3–17 minutes.

---

## §7 TRAPS — each of these has already cost a wrong conclusion

- **`-Xcomp` / `-XX:CompileThreshold=1` give a FALSE NEGATIVE.** They destroy the receiver-type
  profile on `runnable.run()`, and that profile is what drives the inline welding the tree together.
- **A clean run is weak evidence.** Gate on the unit size, not on silence.
- **A green `runCrossingGametest` proves nothing here.** The suite runs iris-ABSENT, so it routes to
  the stencil family and never enters the compat renderer where six of seven victims live. A gate
  that cannot reach the code cannot exonerate it.
- **The offline replay reproducer does NOT work.** All four `replay_pid*.log` files fail under
  `-XX:+ReplayCompiles`: two on *"no method handle found at cpi"* (the lambda's invokedynamic site
  cannot be reconstructed — unfixable by construction) and two on *"no invoke found at bci"* (the
  recorded bytecode no longer matches, and never will again). Every A/B must be live.
- **Verify the JVM and flags on the LIVE PROCESS, not from config.** `Get-CimInstance Win32_Process`,
  match the worktree path + `KnotClient`, read the command line. A malformed `CompileCommand` is
  warned about once and then silently ignored, which leaves the crash live while looking handled.
- **Measure both lambda forms.** See §3.

---

## §8 IF IT CRASHES AGAIN

1. Confirm the four signature points in §1. If there is a `crash-reports/*.txt` and no `hs_err`, it is
   **not** this defect.
2. Record which method `Current CompileTask:` names.
3. Fall back immediately with `-Pc2LegacyExcludes=true` — that is what the lever is for.
4. Capture the unit size with `-Pc2Inlining=true` and compare against §2's table. If the unit is small
   and it still crashed, the weld is **not** the mechanism and the model in §2 is wrong.
