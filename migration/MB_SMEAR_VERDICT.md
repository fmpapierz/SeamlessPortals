MEASURED, and it settles FACT 1 outright. The log rotated between the commission and now: `latest.log` is a **new run started 01:59:37 with Motion Blur already enabled**, so `liveLogged` was fresh and the MB-ON probe line finally emitted.

---

# 1. VERDICT ON THE SMEAR — **not yet determinable; both submitted mechanisms are REFUTED as stated**

I re-verified the two load-bearing claims myself. Both recons are wrong on the decisive step, in opposite directions.

## 1a. RECON A is built on dead code — MEASURED

RECON A's entire timeline (its steps 4/6/7, and its whole verdict) requires `bumpPerFrameUniformCounter()` to advance the counter around the dest render. It does not:

`common/src/main/java/qouteall/imm_ptl/core/IPGlobal.java:275`
```java
public static boolean irisPerFrameRefresh = false;
```
`:279-281` → `isIrisPerFrameRefreshActive()` returns `irisPerFrameRefresh && !LEVER` = **false**, and `IrisInterface.java:172-174` returns immediately before ever reaching `SystemTimeUniforms.COUNTER.beginFrame()` at `:176`. Grep confirms no runtime writer of `irisPerFrameRefresh` anywhere. The `:270-273` comment records the deliberate 2026-07-23 retirement.

So the dest pass never receives `cameraPosition = DEST, previousCameraPosition = MAIN`. RECON A's 248.8-block `cameraOffset` cannot occur. Its §5 "directional fix" would *re-introduce* the exact poisoning IPGlobal retired.

## 1b. The dest composite pass is *gated*, and gating is self-consistent — MEASURED + INFERRED

Four measurements establish the real state:

1. `ProgramUniforms.update()` (javap, offsets 94-122): `if (lastFrame != SystemTimeUniforms.COUNTER.getAsInt()) { lastFrame = c; updateStage(perFrame); }` — the whole perFrame stage is frame-counter-gated.
2. `CameraUniforms.addCameraUniforms` (javap): `cameraPosition` at offset 40-56 and `previousCameraPosition` at offset 82-98 are both `UniformUpdateFrequency.PER_FRAME`; `ProgramUniforms$Builder.addUniform` routes PER_FRAME into the `perFrame` map (tableswitch 1..3). They are **not** in the ungated `dynamic` list. Same for every `gbuffer*`/`gbufferPrevious*` matrix.
3. `PipelineManager.preparePipeline` (javap, offsets 0-10): `COUNTER.reset()` at offset 16 sits **behind** `if (pipelinesPerDimension.containsKey(id)) goto 109`. A same-dim nested render takes the cached branch — **no reset**. The only other `beginFrame()` is `MixinGameRenderer`, once per frame.
4. Ordering: `finalizeLevelRendering()` is invoked from **`MixinLevelRenderer`** (javap), i.e. inside `LevelRenderer.render`; the mod's anchor is `@At(INVOKE, target="LevelRenderer;render(", shift=AFTER)` in `GameRenderer.renderLevel` (`MixinGameRenderer_IPPostLevelAnchor.java:78-92`). The **main** composite chain therefore runs first and sets `lastFrame = COUNTER`.

⇒ the nested dest composite chain re-binds the same `Program` objects at the same COUNTER and **skips perFrame entirely**. It runs composite4 with the MAIN pass's *self-consistent* `(current, previous)` set.

**This is why RECON B's conclusion also fails.** RECON B says main-valued matrices against dest-valued `z` give a "garbage unprojection ⇒ garbage velocity". They do not, because the matrix chain telescopes. In `composite4.glsl:99-127` the reprojection is

```
previousPosition = P_prev · MV_prev · ( MV_cur⁻¹ · P_cur⁻¹ · currentPosition  +  cameraOffset )
```

When `prev == cur` (a stationary camera), `P_prev·MV_prev·MV_cur⁻¹·P_cur⁻¹ = I` **regardless of whether that matrix pair matches the camera that actually rasterized the depth buffer**. The foreign-ness cancels; only `project(cameraOffset)` survives. A gated dest pass is accidentally *protected* — velocity ≈ 0 at rest. (This also independently corroborates IPGlobal's retirement note: the bump is what breaks the telescoping, by making `gbufferModelViewInverse` dest-framed while `Previous.get()` still returns the main matrix.)

## 1c. The stale-`gbufferProjection` candidate — REFUTED for this configuration (MEASURED)

`MixinLevelRenderer.iris$setupPipeline` offsets 41-49 do set `gbufferProjection` from `sodium$getProjectionMatrix()` (the ledgered staleness is real). But `RenderStates.getPortalDrawProjection` (`RenderStates.java:275-288`) returns `capturedMainPassBobbedProjection` **bit-unchanged** unless `extraScaling != 1.0`, and the run logs `[iris-bob-sync] LIVE (first apply): s=1.0` (latest.log:930). There is **no oblique near-clip term** in that function — I looked for one and did not find it. So the captured projection decodes the dest depth correctly, and the residual `viewPos + cameraOffset` term is at the right scale.

## Ranked surviving candidates

| # | candidate | status |
|---|---|---|
| **1** | **Not a bug: correct velocity on a near-field dest scene.** Blur displacement scales as 1/depth. The dest camera sits at `(0, 20, 0)` — if enclosed/underground, the same walking speed that barely blurs an open main view produces a huge smear through the window. | **INFERRED**, fully consistent with every measurement; requires zero defects |
| **2** | **Something de-gates the dest perFrame upload** that I did not find (would restore RECON A's saturated smear). | **UNTESTED.** Would require my §1b chain to be wrong at one link. Only this predicts smear *at rest* |
| 3 | RECON A: bump-driven prev/current poisoning | **REFUTED** — bump is dead code (IPGlobal.java:275) |
| 4 | RECON B: main matrices vs dest depth ⇒ garbage velocity | **REFUTED** — matrices telescope; projection measured equal |
| 5 | C3-BLOOM aperture mask | **REFUTED** — see §2; fires strictly after composite4 |

Candidates 1 and 2 are cleanly separated by a test that costs nothing (§4).

---

# 2. VERDICT ON THE BLOOM RING — **the premise is refuted; close it unbuilt**

The commission's FACT 1 ("idx did NOT move — the reasoning is wrong") was an **instrumentation artifact**, and the live log now proves it:

```
latest.log:864 [02:00:15] [C3-BLOOM] last colortex0 writer also writes other draw buffers (motion-blur shape)…
latest.log:865 [02:00:15] [C3-BLOOM] LIVE: pass=composite5 idx=4 reads=MAIN tex=714 3440x1369
```

**`pass=composite5 idx=4 reads=MAIN` — the pre-registered prediction, confirmed verbatim, including `reads=MAIN`.** The `idx=3 reads=ALT` line in the earlier log was an **MB-OFF** measurement taken at 01:38:53, 18 seconds *before* the MB flip (`01:39:11 Destroying pipeline` / `Profile: ULTRA (+2 → +3 options changed by user)`). It could not re-emit because `IrisBloomApertureMask.java:237` `private static boolean liveLogged = false;` is set at `:683` and — grep-confirmed — **never reset**; only `planCache.clear()` at `:376` exists. The fresh 01:59 run had the latch clear and MB already on, so it printed the true MB-ON plan.

Independently re-derived from the pack, matching exactly:

- `world0/` contains **no `composite2.fsh`** → chain = `[composite, composite1, composite3, composite4, composite5, composite6, composite7]`, indices 0-6.
- `program/composite3.glsl:160` `/* DRAWBUFFERS:0 */` (unconditional) → idx 2.
- `program/composite4.glsl:180,184` `/* DRAWBUFFERS:3 */`, and under `#if MOTION_BLUR_EFFECT == 1` `/* DRAWBUFFERS:30 */` → idx 3 becomes a c0 writer with `drawBuffers.length == 2`.
- `IrisBloomApertureMask.java:422-444` picks the **last** pass whose `drawBuffers` contains 0; `:457` fires the INFO on `lastDb.length > 1`; `:464` `int maskIndex = lastC0Writer + 1;`

MB-OFF → lastC0Writer=2, len 1 (silent), maskIndex=3=composite4. MB-ON → lastC0Writer=3, len 2 (INFO fires), maskIndex=4=composite5. Both log lines reproduced from first principles.

**Is there a defect? Yes, but it is already ledgered and correctly self-reported.** At maskIndex=4 the mask runs *after* composite4 has drawn, so composite4's `BloomTile()` gathers from unmasked colortex0 — the ring returns under MB, exactly as `IrisBloomApertureMask.java:100-104` already states ("MB-ON Complementary — the amendment-3 INFO names the `DRAWBUFFERS:30` shape … disarm or mask inertly: exactly today's ring, never worse"). The user reported a smear, not a ring. **Recommendation: close the ring-under-MB commission unbuilt.** The fix is correctly scoped, the INFO made its own inapplicability attributable, and chasing it here would be scope drift.

**The mask cannot contribute to the smear** (mechanically, not by inference): under MB it fires strictly after composite4's 9 taps have executed, and composite5's only colortex0 read is local — `program/composite5.glsl:162` `vec3 color = texture2D(colortex0, texCoord).rgb;`. `misses=0` across 2454 masks, zero disarm WARNs.

**The one genuinely justified code change from all of this** is the instrument, not the renderer: `liveLogged` and `mbShapeInfoLogged` are class-lifetime latches on a value that changes with every pipeline rebuild. They cost this investigation a full false-refutation cycle. Reset both wherever `planCache.clear()` runs (`:376`). Log-only, no render-path effect.

---

# 3. THE MINIMAL INSTRUMENT

**Zero new code. The discriminator is the player's own movement state** (§4, test A). My §1b chain predicts velocity ≈ 0 at rest; candidate 2 predicts saturation regardless. Nothing in a probe beats that for cost.

**Only if test A shows smear at rest** does a probe become justified — and then exactly one number is needed: is the dest composite pass gated or not?

> **`[IS5-MB]` — dest composite gate probe.** Default-OFF (`-Dseamlessportals.mbGateProbe`), log-only, ≤1 Hz on the render thread. At the dest chain's composite4 bind, read `ProgramUniforms.lastFrame` for that program and `SystemTimeUniforms.COUNTER.getAsInt()`, and emit one line: `[IS5-MB] pass=composite4 lastFrame=<n> counter=<n> gated=<bool> sameDim=<bool>`. Reflection targets are the ones already resolved by `IrisFullbrightProbe.java:393-398` (`SystemTimeUniforms.COUNTER` → `getAsInt()`), so no new reflection surface. Once-only WARN on every silent-skip path (reflection unresolved, pipeline null, program not found, plan not built). `-P` row added to **both** `fabric/build.gradle` blocks. Any new iris mixin simple name contains `Iris`.
>
> **Decision rule:** `gated=true` ⇒ my §1b chain holds, the uniforms are self-consistent, and the smear is candidate 1 (working as designed). `gated=false` ⇒ candidate 2 confirmed; the fix is a **dest-scoped** re-upload/restore of the camera+matrix perFrame set, *not* the retired blanket COUNTER bump (whose destructive `MatrixUniforms$Previous.get()` rotate is precisely what made it poisonous).

Do **not** add a `previousCameraPosition` value dump first — it is the organ the shipped heal already covers, and §1b shows it is not the free variable.

---

# 4. ZERO-CODE TESTS FIRST

One short session, in this order. Test A alone probably ends the investigation.

| # | observation | decision rule |
|---|---|---|
| **A** | **Stand perfectly still**, mouse untouched, look at the portal for ~5 s. | **No smear ⇒ candidates 3/4 dead (already refuted) and candidate 2 dead too. It is candidate 1 — motion blur working as designed.** Smear persists at full strength ⇒ velocity is saturated by a constant offset ⇒ candidate 2; run the §3 probe. |
| **B** | Walk **slowly**, then **sprint**, same view. | Smear magnitude scales with speed ⇒ correct velocity ⇒ candidate 1. Magnitude **unchanged** by speed ⇒ soft clamp saturated (`velocity/(1+|velocity|)*S → S`) ⇒ candidate 2. |
| **C** | While moving, compare the smear **inside** the window against the **surrounding main view**. | Portal much worse *and* the dest side is enclosed/near-walled ⇒ candidate 1 (1/depth amplification). Portal much worse with **both** sides open sky ⇒ candidate 2. Both equally smeared ⇒ the defect is in the **main** pass's `previousCameraPosition`, not the portal at all. |
| **D** | Set the pack's **`MOTION_BLURRING_STRENGTH` slider to 0.15** (it is absent from the sidecar, so 1.00 is in force — `lib/common.glsl:147`). | Reach shrinks ≈6.7× in both candidates, so this is **not** a discriminator — it is the user's immediate **workaround**, and worth having. |
| **E** | Look through a **cross-dimensional** portal with MB on. | Cross-dim uses a *separate* pipeline whose composite programs bind once per frame ⇒ `lastFrame != COUNTER` ⇒ they **do** upload, with dest-primed sources and their own `CameraPositionTracker` (correct `prev=dest(N-1)`, `cur=dest(N)`). **Predicted: cross-dim looks no worse, plausibly better.** If cross-dim is *worse*, the pipeline model is wrong and everything above needs re-derivation. |
| **F** | `-Dseamlessportals.disableIrisBloomApertureMask=true`. | Predicted **null result**: `masks=` stops climbing, frame otherwise identical. Any change to the smear falsifies §2 and must be resolved before anything else. Low priority — run only if A/B/C are ambiguous. |

Note `-PdisablePrevUniformHeal` / `-PdisableHealRetarget` are **not** informative here: §1b shows the dest pass never reads the tracker's mid-frame state, and same-dim makes the retarget a no-op (one pipeline object, so pre-loop capture and manager slot resolve identically).

---

# 5. IS A FIX JUSTIFIED YET? — **No renderer fix. One instrument fix.**

**Renderer: do not build.** Every mechanism proposed by either recon is refuted by direct measurement, and the surviving lead (candidate 1) is *not a defect*. Shipping RECON A's proposed extra notifier tick would be a fix on refuted inference that re-creates a bug the project deliberately retired three days ago. Shipping a matrix save/restore around the nested render would guard a leg that the gating already protects.

**Instrument: justified, and not on inference.** Reset `liveLogged` (`IrisBloomApertureMask.java:237`) and `mbShapeInfoLogged` (`:239`) wherever `planCache.clear()` runs (`:376`), so a pipeline rebuild re-emits the `LIVE:` line. This is directly evidenced: the stale latch produced the false "REFUTED" premise this entire commission was built on. Log-only, no render-path behavior, no new lever needed.

**User-facing now:** the `MOTION_BLURRING_STRENGTH` slider (test D) is a real, immediate mitigation whatever the outcome.

---

# 6. HONEST LIMITS

- **I could not explain the user's symptom.** My measurements positively refute both submitted mechanisms but produce **no confirmed cause**. Candidate 1 (near-field dest scene) is INFERRED from the destination coordinates `(0, 20, 0)` — I did **not** verify what is actually around that point, and I have no screenshot.
- **The symptom is under-specified and that is the binding constraint.** "Blurs the shit out of the entire portal view" does not say whether it happens **at rest**. That single bit separates "no bug" from "real bug", and no amount of static analysis substitutes for it. The user's observation outranks all of the above.
- **The telescoping argument (§1b) is INFERRED**, not measured — it is algebra over `composite4.glsl:99-127`, not a captured uniform dump. It is the load-bearing step that refutes RECON B, and it is the thing to attack first if test A contradicts me.
- **Never measured, by me or anyone:** the actual runtime *values* of `gbufferPreviousModelView` / `gbufferPreviousProjection` / `gbufferProjectionInverse`. Zero `posOffset` lines exist in the current log. Everything about the matrices is bytecode-and-algebra reasoning.
- **The portal's rotation/scale is unverified.** `[iris-bob-sync] s=1.0` (latest.log:930) bounds the *projection*, not the portal transform. I did not find a probe dumping the portal's rotation quaternion. Under gating this does not matter (§1b), but it would matter if candidate 2 is confirmed.
- **The two logs are different runs.** My §2 conclusion uses the fresh 01:59 run; the commission's FACT 1 quotes the 01:38 run. The C3-BLOOM reconciliation is now **measured in both**, but any *other* cross-run comparison in this report should be treated with that caveat.

### Files
- `C:/Users/warwa/ModDev/Portals/Portal 26.2/.claude/worktrees/is5-shadow/common/src/main/java/qouteall/imm_ptl/core/IPGlobal.java` (`:270-281` — the retired bump)
- `.../qouteall/imm_ptl/core/compat/iris_compatibility/IrisInterface.java` (`:171-177`, `:186-240`)
- `.../iris_compatibility/IrisBloomApertureMask.java` (`:100-104`, `:209`, `:237-239`, `:376`, `:422-469`, `:682-687`)
- `.../iris_compatibility/IrisCompatOn262Renderer.java` (`:425`, `:440`)
- `.../qouteall/imm_ptl/core/mixin/client/render/MixinGameRenderer_IPPostLevelAnchor.java` (`:78-92`)
- `.../qouteall/imm_ptl/core/render/context_management/RenderStates.java` (`:275-288`)
- `C:/Users/warwa/ModDev/Portals/Portal 26.2/.claude/worktrees/is5-shadow/fabric/runs/client-sodium/logs/latest.log` (`:864-865`, `:930`)
- `C:/Users/warwa/AppData/Local/Temp/claude/C--Users-warwa-ModDev-Portals-Portal-26-2/3e3137ce-c142-49e9-8adb-4ea85c17287a/scratchpad/pack/shaders/program/composite4.glsl` (`:87-186`), `program/composite3.glsl:160`, `program/composite5.glsl:162`, `lib/common.glsl` (`:147`, `:517-523`)
