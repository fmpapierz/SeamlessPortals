# SPIKE-R4 — ViewArea subclass + redirect viability (API_RISKS R4; EXECUTION_PLAN S1(a))

- **Date:** 2026-07-13
- **Branch:** `spike/r4-viewarea` (worktree-only, never merged — D6), spike commit `2fb61555`
  on top of `4a489405` (S0.3). Probe code: `fabric/src/main/java/com/warwa/seamlessportals/spike/r4/`
  (SpikeViewArea, SpikeR4, SpikeR4GameTest, mixin/SpikeLevelRendererViewAreaMixin) +
  `seamlessportals-spike-r4.mixins.json`.
- **Run:** `.\gradlew.bat :fabric:runClientGametest --console=plain --no-daemon`
  (fabric client-gametest, real client, superflat creative world, renderDistance 8).
  Result: `BUILD SUCCESSFUL in 1m 5s`, `EXIT=0`, **no crash-reports directory created**, clean
  programmatic exit. 68 `[SPIKE]` evidence lines in the run log.

## VERDICT

**CONFIRMED.** A minimal pass-through `ViewArea` subclass installs cleanly via a mixin
`@Redirect` of the `new ViewArea` construction inside `LevelRenderer.invalidateCompiledGeometry`
(26.2 retarget of IP 1.21.3's `allChanged` NEW-redirect, IP `MixinLevelRenderer.java:322-345`),
and the game runs **visually normal** through the subclass: world load, walking, /tp, F3+A
reload (via genuine simulated keypress), portal-view rendering of a secondary dimension, and a
seamless nether crossing — all five screenshot phases show fully rendered terrain, zero crashes.
The redirect fires for **both** the main renderer and the mod's secondary `LevelRenderer`s.

## 1. What was built (D6 scope: pass-through only — this is NOT ImmPtlViewArea)

`SpikeViewArea extends ViewArea` with the **public 7-arg super ctor**
(`26.2:ViewArea.java:19-27`; main-thread assert at `:31-33`) and pure super-delegating
overrides of every non-final member an ImmPtlViewArea port would lean on:
`repositionCamera(SectionPos)` (public, `:74`), `getRenderSectionAt(BlockPos)` (public, `:87`),
**protected** `getRenderSection(long)` (`:91`), `releaseAllBuffers()` (`:40`). Counters +
thread-name capture on each; rate-limited logging (per-frame paths bump atomics only —
render-thread log4j stall rule).

Install mixin — `@Redirect` with the NEW ctor-descriptor target (same annotation shape as the
shipped `HandleRespawnMixin.java:313-320` NEW-redirect):

```java
@Redirect(method = "invalidateCompiledGeometry",
    at = @At(value = "NEW",
        target = "(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;IIIII"
            + "Lnet/minecraft/client/renderer/SectionOcclusionGraph;)"
            + "Lnet/minecraft/client/renderer/ViewArea;"))
```

Re-verified against the actual file: construction is at `26.2:LevelRenderer.java:819-827`
inside `invalidateCompiledGeometry` (`:796-832`), which is invoked from
`LevelExtractor.extract` when `shouldInvalidateCompiledGeometry` is consumed
(`LevelExtractor.java:122-124`), set by `LevelExtractor.allChanged()` (`:406-416`), which fires
on first extract of any extractor (render-distance mismatch, `:96-98`), on `setLevel` (`:396`),
and from F3+A (`KeyboardHandler.java:187-191`).

## 2. Evidence

### E1 — redirect installs the subclass at world load (main renderer)

```
[20:07:29] [Render thread/INFO] (SpikeR4) [SPIKE] Redirect fired (1) in invalidateCompiledGeometry: owner=MAIN LevelRenderer@23dcf288 thread=Render thread
[20:07:29] [Render thread/INFO] (SpikeR4) [SPIKE] SpikeViewArea ctor #1 owner=MAIN LevelRenderer@23dcf288 renderDistance=8 minY=-64 maxY=319 minSectionY=-4 maxSectionY=19 size()=6936 thread=Render thread
[20:07:29] [Render thread/INFO] (SpikeR4) [SPIKE] #1 repositionCamera MOVED center to SectionPos{x=0, y=-4, z=0} (call 1, move 1, thread=Render thread)
```

`size()=6936` = (2·8+1)² × 24 sections — the private `RotatingSectionStorage` built by the
super ctor is intact under the subclass. The ctor's `isSameThread` assert passed (Render thread).

### E2 — live traffic runs through the overrides at real rates, including OFF-THREAD

Phase-1 dump after ~9s of settling:

```
[SPIKE]   #1 owner=MAIN ... | repositionCalls=977 (moved=1, threads=[Render thread]) | getRenderSectionAt=17418 (threads=[Worker-Main-5, Render thread]) | getRenderSection(long)=14756 (threads=[Worker-Main-5, Render thread]) | releaseAllBuffers=0 | size=6936 viewDistance=8
```

- `repositionCamera`: 977 calls in the ~9s window ≈ 110/s = once per (uncapped dev-client) frame (`LevelRenderer.render` → private
  `repositionCamera(CameraRenderState)`, `LevelRenderer.java:168-169, 304-312`), Render thread only.
- `getRenderSectionAt` / `getRenderSection(long)`: tens of thousands of calls, from the Render
  thread **and background workers**:

```
[20:07:29] [Worker-Main-5/INFO] (SpikeR4) [SPIKE] #1 getRenderSectionAt call 3 pos=BlockPos{x=0, y=-12, z=10} thread=Worker-Main-5
```

  IP 1.21.3's comment on its `getRenderSectionAt` override — "NOTE it may be accessed from
  another thread" (`ImmPtlViewArea.java:431`) — **survives in 26.2 and now also covers
  `getRenderSection(long)`** (SectionOcclusionGraph's full-update task runs on
  `Util.backgroundExecutor()`; callers at `SectionOcclusionGraph.java:181,212,224,381,397` and
  `:331`; also `LevelRenderer.java:625,902`). Any real ImmPtlViewArea backing store on 26.2 must
  be thread-safe on BOTH accessors.
- `getRenderSection(long)` is the **hot** accessor: 258,659 calls on the nether instance by
  run end vs 12,707 for `getRenderSectionAt`.

### E3 — movement re-enters the redirect-installed instance

Held-W walk (60 ticks, genuine `TestInput` key hold) + `/tp @p ~64 ~8 ~`:

```
[20:07:41] [Render thread/INFO] (SpikeR4) [SPIKE] #1 repositionCamera MOVED center to SectionPos{x=4, y=-4, z=0} (call 1388, move 2, thread=Render thread)
```

64 blocks = 4 sections; the center move flowed through the override into
`RotatingSectionStorage.repositionCenter` and the occlusion invalidate (`ViewArea.java:74-81`).

### E4 — F3+A reload via GENUINE SIMULATED KEYPRESS re-enters the redirect

Driver: `input.holdKey(o -> o.keyDebugModifier)` + `input.pressKey(o -> o.keyDebugReloadChunk)`
(fabric client-gametest `TestInput`; F3=key 292 at `Options.java:707`, A=65 at `:709`; gate at
`KeyboardHandler.java:532-533` → `:187-191` → `minecraft.levelExtractor.allChanged()`):

```
[20:07:44] [Test thread/INFO] (SpikeR4) [SPIKE] PHASE 3: F3+A via TestInput (ctorBefore=1)
[20:07:44] [Render thread/INFO] (SpikeR4) [SPIKE] #1 releaseAllBuffers call 1 thread=Render thread
[20:07:44] [Render thread/INFO] (SpikeR4) [SPIKE] Redirect fired (2) in invalidateCompiledGeometry: owner=MAIN LevelRenderer@23dcf288 thread=Render thread
[20:07:44] [Render thread/INFO] (SpikeR4) [SPIKE] SpikeViewArea ctor #2 owner=MAIN LevelRenderer@23dcf288 ... size()=6936 thread=Render thread
[20:07:45] [Test thread/INFO] (SpikeR4) [SPIKE] PHASE 3: after keypress ctorCount=2 (delta=1)
```

The programmatic `allChanged()` fallback was **not needed** (delta=1 from the keypress alone).
The reload lifecycle through the subclass is exactly vanilla's: old instance's
`releaseAllBuffers` (`LevelRenderer.java:814-816`) → redirect → new subclass instance →
`waitAndReset` → `repositionCamera` (`:828-831`). The phase-3 screenshot
(`0002_spike-r4-3-after-reload.png`) shows the on-screen "[Debug]: Reloading all chunks" chat
feedback (`KeyboardHandler.java:189`) with terrain fully re-rendered — the keypress traversed
the real vanilla path, not a shortcut.

### E5 — the redirect fires for the mod's SECONDARY LevelRenderer too

The instant the nether portal was built (phase 4, BEFORE crossing), the mod's portal-view
machinery created its secondary nether renderer+extractor (`PortalWorldManager.java:599,624-625`),
whose first `extract()` hit the render-distance mismatch (`LevelExtractor.java:96-98`) and
constructed its ViewArea through the redirect:

```
[20:07:54] [Render thread/INFO] (SpikeR4) [SPIKE] Redirect fired (3) in invalidateCompiledGeometry: owner=MAIN LevelRenderer@6e60c26e thread=Render thread
[20:07:54] [Render thread/INFO] (SpikeR4) [SPIKE] SpikeViewArea ctor #3 owner=MAIN LevelRenderer@6e60c26e renderDistance=8 minY=0 maxY=255 minSectionY=0 maxSectionY=15 size()=4624 thread=Render thread
```

Distinct instance (`@6e60c26e` vs main `@23dcf288`), nether world height (minY=0/maxY=255,
size 4624 = 17²×16). **SURPRISE (see §4-S1):** it is tagged `owner=MAIN` because the handler's
`mc.levelRenderer == this` identity check ran while the mod's per-portal-frame context switch
had **swapped `Minecraft.levelRenderer`** to the dest renderer (`@Mutable @Accessor("levelRenderer")`
setter, `MOD:mixin/client/MinecraftAccessorMixin.java:33-35`). The phase-4 screenshot
(`0003_spike-r4-4-portal-built.png`) shows live nether terrain rendered THROUGH the portal
window — i.e. the secondary's terrain drew through SpikeViewArea #3.

### E6 — seamless crossing, no reconstruction, both instances stay live

```
[20:07:59] [Test thread/INFO] (SpikeR4) [SPIKE] PHASE 5: reached nether after 1 ticks
[20:08:09] [SPIKE] redirectCount=3 ctorCount=3 instances=3
[20:08:09] [SPIKE]   #2 owner=MAIN ... repositionCalls=2802 ... getRenderSection(long)=27250 ...   (overworld, now demoted)
[20:08:09] [SPIKE]   #3 owner=MAIN ... repositionCalls=1746 ... getRenderSection(long)=258659 ...  (nether, now live)
```

The crossing did **not** construct a new ViewArea (the mod's promote/adoption reuses both
renderers); the demoted overworld instance #2 kept receiving per-frame traffic as a portal-view
secondary (repositionCalls 1608→2802 across the crossing). Final screenshot
(`0004_spike-r4-5-after-crossing.png`): normal in-nether terrain render. On world close the
live instance got its vanilla teardown `releaseAllBuffers` (`LevelRenderer` reset path, `:880`).

Screenshots (all visually normal, in `fabric/runs/gametest/screenshots/` on the spike worktree):
`0000` initial superflat, `0001` after move, `0002` after F3+A (with debug chat line),
`0003` portal built with live nether view, `0004` standing in nether.

## 3. Subclass-surface facts for the S11 CUTOVER_SPEC R4 decision

What ImmPtlViewArea overrode/used in 1.21.3 vs what 26.2 offers:

| IP 1.21.3 member (ImmPtlViewArea.java) | 26.2 status | Spike evidence |
|---|---|---|
| ctor `(SectionRenderDispatcher, Level, int r, LevelRenderer)` (`:97-113`) | Public 7-arg `(dispatcher, minY, maxY, minSectionY, maxSectionY, renderDistance, occlusionGraph)` (`ViewArea.java:19-27`); main-thread assert `:31-33`; **no Level, no LevelRenderer param** — subclass must source those itself if needed | PROVEN: super() from subclass works, assert passes on Render thread |
| `createSections` override (`:115-120`) | **GONE** — storage built inside the ctor via `RotatingSectionStorage` + factory lambda `(index, sectionNode) -> dispatcher.new RenderSection(index, sectionNode)` (`ViewArea.java:35-37`; `SectionRenderDispatcher.java:216`) | n/a (nothing to override; a custom store must be built beside/instead of the private one) |
| protected fields `sections`, `sectionGridSizeX/Y/Z`, `level` | **GONE** — single `private final RotatingSectionStorage<RenderSection> sections` (`ViewArea.java:15`); no Level field at all | Not reachable from the subclass without AW/accessor |
| `repositionCamera(double,double)` override (`:139-164`, presets swap) | `repositionCamera(SectionPos)` public non-final (`:74-81`); called once per frame (`LevelRenderer.java:168-169,304-312`) + once at reload (`:831`) | PROVEN overridable + reached: 977 calls/9s, center moves observed |
| `setDirty(int,int,int,boolean)` override (`:166-170`) | **GONE from ViewArea** — dirty tracking externalized to `SectionUpdateTracker.SectionDirtyState`, owned per-`LevelExtractor`, recreated in `allChanged` (`LevelExtractor.java:411`), consumed via the extract read-schedule-clear flow | NOT probed (no ViewArea seam exists; the ImmPtl equivalent must hook the tracker/extractor instead) |
| `getRenderSectionAt(BlockPos)` protected override (`:431-455`, thread-safe) | Now **public** non-final (`:87-89`); callers `SectionOcclusionGraph.java:331`, `LevelRenderer.java:902` | PROVEN overridable; **off-thread access confirmed** (Worker-Main-*) |
| — (no 1.21.3 analog) | **`getRenderSection(long)` protected (`:91-93`) is the new HOT accessor**: `LevelRenderer.java:625` + `SectionOcclusionGraph.java:181,212,224,381,397` | PROVEN overridable; 258k calls incl. off-thread — the choke point a custom store must serve |
| `releaseAllBuffers` override (`:122-132`) | Public non-final (`:40-44`); called at reload (`:815`) and teardown (`:880`) | PROVEN overridable + reached on both paths |
| `dispatcher.new RenderSection(0, x, y, z)` (`:260-263`) | `new RenderSection(int index, long sectionNode)` — identity = packed SectionPos long, index into the fixed array (`SectionRenderDispatcher.java:216`) | Observed via factory in super ctor |

**External-consumer audit (compile-time fact, grep-verified):** every external read of
ViewArea state goes through overridable instance methods — `LevelRenderer.java:307,625,815,831,880,902`
and `SectionOcclusionGraph.java` (`getRenderSection`/`getRenderSectionAt` above, plus geometry
getters `minY/maxY/minSectionY/maxSectionY/getViewDistance/size/sectionCount/getCameraSectionPos`
at `:214-216,:326,:366,:379,:427-428`). Nothing outside ViewArea touches the private
`RotatingSectionStorage`. **A subclass presenting its own backing store is therefore
architecturally reachable** — with two structural cautions:

1. **Fixed-size occlusion coupling:** `SectionOcclusionGraph.GraphStorage` sizes
   `SectionToNodeMap` from `viewArea.size()` and the `Octree` from
   `getCameraSectionPos()/getViewDistance()/sectionCount()/minY()` (`SectionOcclusionGraph.java:426-430`),
   rebuilt only at `waitAndReset` (`:68-87`, i.e. per invalidate). An "unbounded" grid must still
   advertise bounded, stable answers from these getters (IP's presets design already did: its
   grid size was fixed at `sectionGridSize*`; only the array CONTENTS swapped per camera cell).
2. **Slot-stable vs coord-stable identity:** 26.2's `repositionCenter` REUSES the RenderSection
   objects and mutates their `sectionNode` in place (`RotatingSectionStorage.java:44-71`, reassign
   at `:60-64`). IP's presets kept RenderSections **pinned to coordinates** and swapped arrays.
   This inversion — not the install mechanism — is the real design work left for the
   ImmPtlViewArea rebuild (or the argument for keeping the mod's proven pinned-bounded deviation;
   both options stay open, see API_RISKS R4 "Solved by mod?").

**IP demand surface** (who consumes ImmPtlViewArea's extra members, for the rebuild's API):
`VisibleSectionDiscovery.rawFetch` (`:155`), `MixinLevelRenderer` casts + `rawGet`
(`:253,286,297,503-512`), `ClientDebugCommand.getManagedSectionNum` (`:828-830`),
`ImmPtlClientChunkMap.clientChunkUnloadSignal → onChunkUnload` + `IPGlobal.POST_CLIENT_TICK_EVENT
→ tick/purge` (`ImmPtlViewArea.java:69-95`).

## 4. Surprises the live run exposed

- **S1 — `Minecraft.levelRenderer` identity is unreliable inside the redirect.** The mod swaps
  the field per portal-render frame (`@Mutable` accessor, `MinecraftAccessorMixin.java:33-35`),
  so ctor #3 (the nether secondary) self-identified as "MAIN". The 1:1 port is unaffected — IP
  gates its redirect on the global `IPCGlobal.useHackedChunkRenderDispatcher` flag, not on
  renderer identity (`IP:MixinLevelRenderer.java:335`) — but any S11+ diagnostics/asserts must
  key on instance identity or dimension, never on `mc.levelRenderer` equality.
- **S2 — secondary ViewArea construction is lazy and render-thread.** It happens at the
  secondary extractor's FIRST `extract()` (triggered by the `lastViewDistance` mismatch,
  `LevelExtractor.java:96-98`) during the portal-view pass — which satisfies the ctor's
  `isSameThread` assert. Any future off-thread secondary prep must not construct ViewAreas.
- **S3 — crossings do not reconstruct ViewAreas** under the mod's promote/adopt design; both
  the demoted and promoted renderers' instances keep receiving per-frame traffic (E6). An
  installed ImmPtlViewArea would therefore live long and see BOTH roles.
- **S4 — the client-gametest `TestInput` F3+A route works end-to-end** (`holdKey(keyDebugModifier)`
  + `pressKey(keyDebugReloadChunk)` → on-screen debug feedback + real reload). Reusable for the
  S13–S17 bring-up/regression scripts.

## 5. NOT proven (scope-honest)

- **No non-passthrough behavior was tested** (D6 scope): no custom backing store, no unbounded
  grid, no presets cache, no thread-safe wrap-around logic. §3's two structural cautions are
  compile-time facts from mc262-ref, not runtime-probed.
- **The `setDirty` replacement path** (SectionUpdateTracker/LevelExtractor) has no ViewArea seam
  and was not exercised by this spike; the ImmPtl dirty-marking port needs its own design at
  S11 (render-core C28).
- **Upload-pump necessity** (`earlyRemoteUpload` → `lock()/uploadTerrainBuffersToGpu()/unlock()`,
  render-core G26) untouched.
- **NeoForge runtime not exercised** (fabric loom dev client only). The mixin NEW-redirect shape
  itself is loader-neutral (the shipped `HandleRespawnMixin` NEW-redirect lives in `common`),
  but no neoforge run backs that here.
- **@Redirect exclusivity:** `@Redirect` claims the instruction exclusively; if a third-party
  mod ever redirects the same `new ViewArea`, they conflict. A `@WrapOperation` variant was NOT
  tested — worth a one-line decision at S11 (IP upstream uses plain `@Redirect`).
- **Sodium/Iris coexistence** with the redirect: not probed (no such mods in the dev run).

## 6. Bottom line for S11

The R4 "can we even install and live through a ViewArea subclass on 26.2" risk is **retired**:
public ctor, overridable hot methods, working NEW-redirect at the moved construction site, both
renderers covered, full reload lifecycle exercised by real F3+A, visually normal play. The
remaining R4 work concentrates entirely in **storage semantics** (26.2 slot-stable/coord-mutable
`RotatingSectionStorage` vs IP's coord-stable presets grid) and the **fixed-size occlusion-graph
coupling** — i.e. the CUTOVER_SPEC decision between rebuilding ImmPtlViewArea's unbounded store
behind the proven subclass seam vs documenting the mod's pinned-bounded deviation.

## Review verdict (S1 adversarial review, 2026-07-13)

**PASS.** The mandated deliverables are present: an evidence-backed redirect-viability verdict (CONFIRMED) and the §3 fact table + two structural cautions that the S11 CUTOVER_SPEC R4 decision needs.
- All quoted log lines verified verbatim against `run_spike.log` in the worktree (uncommitted, as are the five screenshots — the memo's quotes are the durable record). This includes the E6 teardown claim, which the reviewer checked skeptically: the final counter dump shows `releaseAllBuffers=0` for #3, but the actual teardown line `#3 releaseAllBuffers call 1` appears AFTER it at 20:08:10 (log line 1176), and instance #1 shows `releaseAllBuffers=1` from its F3+A reload — the claim holds.
- mc262-ref re-checked line-exact: public 7-arg ctor with the main-thread `IllegalStateException` guard (`ViewArea.java:19-37`); `repositionCamera` :74 / `getRenderSectionAt` :87 public, `getRenderSection(long)` :91 protected, `releaseAllBuffers` :40, single `private final RotatingSectionStorage` :15; `new ViewArea` inside `invalidateCompiledGeometry` at `LevelRenderer.java:819-827` (method :796-832 as the plan cites). The slot-stable/coord-mutable caution is exact: `RotatingSectionStorage.repositionCenter` (:44-71) mutates `value.setSectionNode(...)` in place on reused objects.
- IP-side re-checked: `MixinLevelRenderer.java:322-345` is the 1.21.3 NEW-redirect with the `IPCGlobal.useHackedChunkRenderDispatcher` gate (~:335) — the §4-S1 "gate on the flag, not renderer identity" observation matches upstream's actual shape.
- Reviewer fix applied: E2 said "~60 calls/s" while its own quoted counter shows 977 calls in ~9s ≈ 110/s (corrected in place; the substantive once-per-frame claim is source-cited and unaffected).
- §5 honesty is appropriate: pass-through-only is exactly the D6 scope; the real remaining work (storage semantics + occlusion-graph sizing coupling) is correctly pushed to the S11 decision rather than smuggled in as settled.
