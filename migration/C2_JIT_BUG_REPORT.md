# C2 compiler crash — bug report draft (copy/paste ready)

## WHERE TO FILE IT

**Primary — the official OpenJDK/JDK bug intake (no account needed):**
> **https://bugreport.java.com/**
Choose product **"Java SE"**, component **"hotspot"**, subcomponent **"compiler"**.
This creates an incident that is triaged into JBS (`bugs.openjdk.org`) and routed to the HotSpot
compiler team. **This is the right venue because the crash reproduces on TWO independent vendor
builds** (Eclipse Temurin and Azul Zulu), which means it is upstream OpenJDK and not a packaging
issue.

**Secondary — faster expert eyes, if you're willing to use a mailing list:**
> `hotspot-compiler-dev@openjdk.org` (subscribe first at https://mail.openjdk.org/mailman/listinfo/hotspot-compiler-dev)
Compiler engineers read this directly. Attach the `hs_err_pid*.log` files.

**Do NOT file at Adoptium.** Your `hs_err` file suggests
`https://github.com/adoptium/adoptium-support/issues`, but that is for problems with *Temurin's
packaging of* the JDK. Since Zulu 25.0.4 crashes identically, this is not Temurin-specific and
Adoptium would only forward it upstream.

**Attach:** all four `hs_err_pid*.log` files from
`fabric/runs/client-sodium/` — they are the single most valuable part of the report.

---

## THE REPORT (copy everything below this line)

### Title
C2 crashes with EXCEPTION_ACCESS_VIOLATION compiling a small method tree containing a lambda passed through three call levels (JDK 25.0.2 and 25.0.4, Windows x64)

### Synopsis
The C2 compiler thread crashes with `EXCEPTION_ACCESS_VIOLATION` while compiling methods belonging
to one small inlining tree. It reproduces on **both Eclipse Temurin 25.0.2+10 and Azul Zulu
25.0.4+7**, so it is not vendor-specific. Excluding the crashing method from compilation moves the
crash to the next method in the same tree rather than stopping it — six distinct methods from the
same call chain have crashed so far.

### Environment
- **JDK builds affected (both):**
  - OpenJDK Runtime Environment Temurin-25.0.2+10 (build 25.0.2+10-LTS)
  - OpenJDK Runtime Environment Zulu25.36+15-CA (25.0.4+7) (build 25.0.4+7-LTS)
- **OS:** Windows 11, 64-bit, Build 26100 (10.0.26100.8972)
- **CPU:** AMD Ryzen 7 7800X3D, 16 cores
- **RAM:** 31 GB
- **VM mode:** mixed mode, sharing, tiered, compressed oops, compressed class ptrs, **G1 GC**,
  windows-amd64
- `CICompilerCount = 12` (ergonomic)
- **Application:** Minecraft 26.2 (Fabric) with a rendering mod, plus the Sodium and Iris rendering
  mods. The crashing methods are the mod's; the crash is inside `jvm.dll`.

### Failure signature — identical across all four crashes
```
EXCEPTION_ACCESS_VIOLATION (0xc0000005)
Current thread: JavaThread "C2 CompilerThread0" daemon [_thread_in_native]
Problematic frame: V  [jvm.dll+0x73238f]     (Temurin 25.0.2 — all three crashes on that build)
Problematic frame: V  [jvm.dll+0x741a7f]     (Zulu 25.0.4 — different build, so a different offset)
Native frames: ALL jvm.dll — no application, JNI, or driver frames
Java frames: (none)
```
The frame offset is **byte-identical across three separate crashes on the same build**, which
suggests a single deterministic fault site rather than memory corruption.

### The four captured crashes and their compile tasks
| elapsed | JDK | Current CompileTask |
|---|---|---|
| 217.2 s | Temurin 25.0.2+10 | `C2:217217 61582  4  GlQueryObject::performQuery (56 bytes)` |
| 999.9 s | Temurin 25.0.2+10 | `C2:999868 69246  4  IrisCompatOn262Renderer$$Lambda/0x…::run (12 bytes)` |
| 182.4 s | Temurin 25.0.2+10 | `C2:182445 64733  4  IrisCompatOn262Renderer::lambda$testShouldRenderPortal$0 (16 bytes)` |
| 299.1 s | **Zulu 25.0.4+7** | `C2:299084 64652 ! 4  IrisCompatOn262Renderer::doRenderPortal (215 bytes)` |

(Two further methods from the same chain, `PortalRenderInfo::renderAndDecideVisibility` and
`IOWorker::storePendingChunk`, crashed in earlier sessions before crash logs were being retained.)

### Why these are one problem, not four
All of the above belong to a single call chain, and three of the four are tiny (12–56 bytes), so C2
inlines them into one compilation unit. The observed behaviour is consistent with C2 crashing while
compiling **that merged tree**, with the reported "current task" simply being whichever member
happened to become the compilation root:

```
doRenderPortal                                   (215 bytes, has exception handlers — the "!" flag)
  -> testShouldRenderPortal
       -> PortalRenderInfo.renderAndDecideVisibility(Portal, Runnable)
            -> GlQueryObject.performQueryAnySamplePassed(Runnable)
                 -> GlQueryObject.performQuery(Runnable, int)        (56 bytes)
                      -> runnable.run()                              (12-16 bytes, capturing lambda)
                           -> ViewAreaRenderer.renderPortalArea(...)  (large leaf: GL calls + mesh building)
```
Notable shape: a **capturing lambda passed down three call levels before being invoked**, expanding
into a large leaf, with exception edges present (`org.apache.commons.lang3.Validate.isTrue` in
`performQuery`, and a `try/finally` in `doRenderPortal`).

**Evidence that it is the tree and not the individual method:** adding
`-XX:CompileCommand=exclude` for the crashing method does not stop the crash — it reappears on
another member of the same chain. This has now happened five times in sequence. In one instance the
exclusion was applied to a lambda's proxy bridge (`$$Lambda…::run`) and C2 then crashed on the
lambda's synthetic body (`lambda$testShouldRenderPortal$0`) instead — i.e. the *other* half of the
same lambda.

### Reproducibility
Not reproducible on demand, but recurs reliably within roughly 3–17 minutes of gameplay whenever the
relevant rendering code becomes hot. It has occurred at 182 s, 217 s, 299 s and 999 s of elapsed VM
time across two JDK builds. It is triggered by whatever makes that code hot (creating or looking at
more of the relevant objects), not by any specific user action.

### Current workaround
Six `-XX:CompileCommand=exclude,<method>` flags covering the members of the chain. This is effective
so far but costs JIT optimisation on a per-frame rendering path, and each new crash has required
adding another exclusion.

### What would help me narrow it further
Happy to run any of the following and supply output: `-XX:+PrintCompilation`,
`-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining`, `-XX:CompileCommand=print,<method>`, a
`-XX:CompileCommand=dontinline` variant, or a fastdebug build if one is available for Windows x64.

### Attachments
`hs_err_pid962536.log`, `hs_err_pid87244.log`, `hs_err_pid91576.log`, `hs_err_pid96624.log`
