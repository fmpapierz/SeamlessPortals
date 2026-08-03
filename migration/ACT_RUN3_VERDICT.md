# ADJUDICATED VERDICT — IS5 dest ACT accumulation (round 3)

Log: `C:/Users/warwa/ModDev/Portals/Portal 26.2/.claude/worktrees/is5-shadow/fabric/runs/client-sodium/logs/latest.log` (16606 lines). All numbers below marked **[re-extracted]** are my own awk pass over the whole file, not any analyst's.

---

## 0. INDEPENDENT RE-CHECK OF THE LOAD-BEARING NUMBERS

**N-1 [re-extracted] — `posOffset` cross-tab, all 400 `[5] ACT DISPATCH` blocks.**

| role | `|posOffset|∞` | count |
|---|---|---|
| DEST | 132.0 / 133.0 / 134.0 | 38 / 1 / 1 |
| DEST | 1.0 | **2** |
| MAIN | 0.0 | **347** |
| MAIN | 1.0, 2.0, 59, 60, 134, 138, 552 | 5,1,1,1,1,1,1 |

42 DEST + 358 MAIN = 400. Vector form DEST: `(59,-1,132)`×28, `(60,-1,132)`×10, `(59,-1,133)`×1, `(59,-1,134)`×1, `(-1,0,1)`×2. Hand-reproduced from the logged camera pairs against `shadowcomp.glsl:80` (`posOffset = floor(previousCameraPosition) − floor(cameraPosition)`): `floor(58.249)−floor(−1.251)=60`, `68−69=−1`, `135−3=132` ✔. **CONFIRMED. The brief's R-3 refutation is OVERTURNED — it quoted the two degenerate post-teleport frames (F9548/F9549, portal `@27b48fab`, cold volume `voxel@709 nz=0`, endpoints 0.7 blocks apart). R-3 is CONFIRMED on the 40 real samples.**

**N-2 [re-extracted] — which main value lands in `destPrev`.** I joined DEST↔MAIN per frame. The main camera was stationary on 40 of 42 frames, so only **two** frames discriminate, and both say **`mainPREV`, not `mainCUR`**:

```
F8130  MAIN cam=(58.519,68.620,134.144)  prev=(58.249,68.620,135.850)
F8130  DEST cam=(-0.981,69.620,1.644)    prev=(58.249,68.620,135.850)   == mainPREV
F8131  MAIN cam=(58.586,68.620,133.722)  prev=(58.519,68.620,134.144)
F8131  DEST cam=(-0.914,69.620,1.222)    prev=(58.519,68.620,134.144)   == mainPREV
```

Verifier 1's O-1 is **ACCEPTED** (the iris analyst's "42/42 destPrev==mainCur" is 40 non-discriminating rows + a mislabel). But the correction *strengthens* the mechanism rather than weakening it, and nobody said why: `destPrev(N) = mainCam(N−1)` **forecloses two of the three candidate injectors**. A second tick inside the nested render would give `mainCam(N)`; a tick during main's `beginLevelRendering` of frame N would also give `mainCam(N)`. Only a tick that happens **after the nested render of frame N−1, with the main camera already restored**, yields `mainCam(N−1)`. That is precisely and uniquely the IS5-PH heal anchor (`IrisCompatOn262Renderer.java:288-296`).

**N-3 [re-extracted, NEW — contradicts all three analysts AND both verifiers] — the "closed window" gap was not closed. 15 dest ACT dispatches happened in it, and produced ZERO growth.**

```
21:50:13 F8147  destAct=29  ...  DEST ff@819=38  ffCopy@820=45
21:50:14 F8148  renderAll=0 act=0  destAct=29     (same-dim portal @1b9bcc09)
...
21:50:19 F8202  renderAll=0 act=0  destAct=29
21:50:20 F8224  listed=0 rendered=0               (no portal at all)
21:50:21 F8241  listed=0 rendered=0
21:50:23 F8256  renderAll=1 act=1  destAct=44  ...  DEST ff@819=37  ffCopy@820=37
```

`destAct` 29 → 44 and `renderAllInWindow` 29 → 44 across ~3 s of unsampled frames. **15 dest ACT dispatches, net change ff 38→37 / ffCopy 45→37.** This kills two claims at once: (a) the rate analyst's F-8 "the volume persists untouched across the closure, 38/45 → 37/37" — it was dispatched 15 more times, not frozen; (b) any residual rate story — 15 dispatches at ≈5/s produced **negative** growth.

**N-4 [re-extracted] — the in-log A/B, same texture, same program, same dimension, ~1.8 blocks apart.**

```
F8267 21:50:36 DEST  posOffset=(59,-1,132)   voxel@818 nz=11940  ff@819=78   ffCopy@820=81
F8268 21:50:36 MAIN  posOffset=(59,-1,134)   voxel@818 nz=11676  ff@819=79   ffCopy@820=81   (prog=264, one poisoned main frame)
F8318 21:50:37 MAIN  posOffset=(0,0,0)       voxel@818 nz=18024  ff@819=1600
F8872 21:50:47 MAIN  posOffset=(0,0,0)                            ff@819=6053  (saturates, holds 12 s)
```

Dest cam at F8267 `(-0.914,69.620,1.222)`; main cam at F8268 `(-0.801,69.620,-0.511)` — the *same room*. 50 dispatches at `posOffset=0` → +1521 nz. ≤27 consecutive at 132 → +85 and a plateau. **CONFIRMED, and it is the strongest single piece of evidence in the log.**

**N-5 [re-extracted] — R-4 is 0/800.** `tex=n/a(no GL42)` appears **800** times; there is no other image-binding read in the file. Capability line `L797`: `OpenGL45=false OpenGL43=false OpenGL30=true ARB_get_texture_sub_image=true ARB_direct_state_access=true`. Gate confirmed at `ActDispatchProbe.java:462` (`if (!GL.getCapabilities().OpenGL42)`).

**N-6 [re-extracted, NEW] — `SystemTimeUniforms.COUNTER` advances exactly +1 per frame, including portal frames, and DEST and MAIN read the *same* value in a frame** (F8120…F8148: COUNTER 1…29, one per frame; every DEST/MAIN pair identical). This **refutes** verifier 2's C-2 sub-worry that the double `bumpPerFrameUniformCounter()` (`IrisCompatOn262Renderer.java:417,432`) desynchronises `framemod2` between the dest gbuffer pass and the dest shadowcomp. It does not.

**N-7 [derived, NEW] — the oblique-projection caveat is algebraically wrong.** For `P` with row 3 = `(0,0,−1,0)`: solving `P·u = (0,0,1,1)` gives `u = (0, 0, −1, (1+m22)/m23)` **whenever `M[0][2] = M[1][2] = 0`**. Lengyel's oblique near-clip (`PortalSlicing.java:125-175`) replaces the **z row** — JOML `m02/m12/m22/m32` — and explicitly reads `projectionMatrix.m20()` (= mathematical `M[0][2]`) as an *input*, i.e. it does not write it. So `P⁻¹(0,0,1,1)` keeps `x = y = 0` under the mod's oblique clip, and `nPlayerPos` at `shadowcomp.glsl:103-105` reduces to the camera **forward vector** taken purely from `gbufferModelViewInverse`. **Verifier 2's A-4 caveat is REJECTED with algebra; the shader analyst's F-4a is ACCEPTED and strengthened.** (Only a *sheared/off-centre* frustum — nonzero `M[0][2]`/`M[1][2]` — would break it; that is worth one cheap log line, see P5.)

---

## 1. VERDICT

**Primary cause — MEASURED, high confidence (≈0.9):**
`previousCameraPosition` uploaded to the dest pipeline's ACT compute is a **MAIN-dimension camera position from the previous frame**, giving `posOffset ≈ (59,−1,132)` on **40 of 42** cross-dim dest dispatches. Every history read in `shadowcomp.glsl` — the spread (`:132`,`:138`), the half-rate carry-forward (`:129`,`:135`), and the behind-player preserve-copy (`:109`,`:111`) — addresses `previousPos = pos − posOffset`, i.e. a point ~145 blocks from the dest camera, far outside the dest voxelisation (`frustum(terrain) distance="64.0 blocks"`, `shadowDistanceEffective=16`). The only branch that never touches `previousPos` is the light-source injection at `:140-144`. Result: emitters written, nothing propagates, and existing content is *translated out* rather than merely frozen.

**MEASURED**: the uniform values, the 40/42 distribution, the arithmetic, the 67× occupancy deficit (90 vs 6053 on the same texture), the F8267→F8318 role-swap A/B, and the 15-dispatch zero-growth micro-control (N-3).
**INFERRED**: that the *injector* is the IS5-PH heal ticking the dest pipeline's `CameraPositionTracker`. The premises are bytecode-proven (`PipelineManager.pipeline` has exactly 3 `putfield` sites — ctor, `preparePipeline`×2, `destroyPipeline` — and **no restorer**; nothing in the mod calls `preparePipeline`; `pipelineIdentity=DIFFERENT` is *measured* at `endPortal` on all 42 cross-dim captures, i.e. the manager slot demonstrably holds a non-main pipeline immediately after the nested render; nothing between `endPortal` and the anchor rewrites it) and N-2 uniquely selects a late-in-frame-N−1 tick with the restored main camera. But **no line in the log names the pipeline the heal actually ticked**. This is one log line away from MEASURED.

**Still genuinely undetermined — two things, and I will not manufacture a winner:**

- **(U-A) Is there *any* propagation in the dest volume at all?** Verifier 2's A-1 is real and I re-verified it: at F8256–F8261 `voxel@818 nz = 11926` five times **to the unit** while `ff` goes 37 → 87 → 90 and `ffCopy` 37 → 82 → 89 (`terrainDbg="C: 72/144 (R)"` constant, `destCam` constant). An emitter-only write on a constant emitter set cannot double. **But A-1's inference ("therefore a `previousPos` read returns non-zero") does not follow.** There is a second mechanism that produces exactly this with *zero* spread: the `OPTIMIZATION_ACT_BEHIND_PLAYER` mask (`shadowcomp.glsl:100-116`) sweeps with **view direction**, which is not logged and is free to change while the camera *position* is constant. Voxels that fall behind the plane take `GetLightSample(sampler, previousPos)` at 145 blocks = 0, i.e. their emitters are **zeroed**. 26751 of the box's 32768 voxels are outside the L1≤16 diamond, so a ~1° view jitter moves ~70 voxels across the plane — the observed ±50 swings are within jitter range. Note the asymmetry that makes this attractive: in MAIN (`posOffset=0`) the same copy path is an *exact preserve*, which is why MAIN's `ff@710` sits rock-steady at 3205→3207 over the same 12 frames while DEST swings 37↔90. **The existing probe cannot discriminate** — it reports only `nz` and `max`, and `maxLum` is a *max*, permanently pinned at the lava constant 173.546 under both hypotheses. P4 below settles it in ~15 lines.
- **(U-B) R-4 (write side) and the sampler side (read side) are both UNMEASURED.** 0/800. The bytecode chains offered by the iris analyst (write side) and verifier 1 (read side) are sound and I accept them as *analysis*; I reject the iris analyst's "DEFINITIVE" header. An unmeasured leg stays unmeasured.

**What I am NOT claiming.** I am not claiming the fix will make the window look right. The whole downstream consumption path (`mainLighting.glsl:388 GetLightVolume` → `lightVoxelization.glsl:96`) is untested for the dest window, and there is a measured, independent second defect (see §2, S-8) that a `previousCameraPosition` fix does not touch.

---

## 2. WHAT IS NOW SETTLED

| # | Hypothesis | Verdict | Killing evidence |
|---|---|---|---|
| S-1 | **Dispatch rate / not enough consecutive dispatches** | **REFUTED** | N-3: 15 dispatches at ~5/s → net −1 nz. N-4: 50 dispatches at `posOffset=0` on the *same texture* → +1521. `renderAllInWindow == destAct` at every sample (2/2 … 60/60) — no dispatch is lost. The "60 vs 9549" ratio is a wall-clock artifact of the frame-rate collapse (F8120@21:49:41 → F8147@21:50:13 = **0.84 fps**; F8268@21:50:36 → F9501@21:50:58 = **56 fps**). *Correction to the rate analyst: the longest consecutive run was 27, not 38 (his own F-2 table shows 2→29 and 29→55); with the requirement he computes at 20–32, "38 is comfortably enough" was never supported. The refutation rests on N-3/N-4, not on the margin.* |
| S-2 | **Volume cleared / reset between viewings** | **REFUTED** | `clear=false` on `floodfill_img` and `floodfill_img_copy` in all 358 `[2]` blocks (`voxel_img` is the only `clear=true`). Content survives the pipeline changing role in the same second: F8267 DEST 78/81 → F8268 MAIN 79/81, same handles 819/820. |
| S-3 | **Ping-pong parity desync / read-the-half-being-written** | **REFUTED** | `framemod2` alternates strictly `1,0,1,0…` on 42/42 dest blocks. Write pairing verified independently: on 24/24 sampled frames the texture that moves is exactly the one `framemod2` selects (F8125 mod=0 → 820 moves; F8126 mod=1 → 819 moves; …). Plus N-6: COUNTER is +1/frame on portal frames and DEST/MAIN share it. |
| S-4 | **Degenerate work groups (R-2)** | **REFUTED** | `absolute=(64,32,64) localSize=[8,8,8] indirect=null` on all 400 blocks; matches `shadowcomp.glsl:24-25` at `COLORED_LIGHTING_INTERNAL=512` exactly. |
| S-5 | **Stale PER_FRAME uniforms / wrong camera / wrong program** | **REFUTED** | `lastFrame==COUNTER => FRESH` 42/42; `camMatches=DEST d=0.000` 42/42; `prog==boundProgram MATCH=YES` 400/400; `nonNullComputes=1`. |
| S-6 | **Dimension gate disabling shadowcomp in the nether** | **REFUTED** | `shaders.properties:110 program.world-1/shadowcomp.enabled=false` sits inside `#if COLORED_LIGHTING == 0` (`:108`); we run 512. Live: dest `act=1`, `nonNullComputes=1`. |
| S-7 | **Distance/radius fade (`effectiveACTdistance`)** | **REFUTED** | `shadowDistance` is `const float = 192.0` (`lib/common.glsl:19`), a compile-time constant — byte-identical in both pipelines. Three references pack-wide, none in the compute. |
| S-8 | **`gbufferProjectionInverse` (the ledgered stale-projection concern) drives the BEHIND_PLAYER mask** | **REFUTED — do not spend a probe on it** | N-7 algebra: `P⁻¹(0,0,1,1) = (0,0,−1,·)` for any projection with `M[0][2]=M[1][2]=0`, and the mod's Lengyel oblique writes only the z row. `normalize()` removes the remaining scale. **Only `gbufferModelViewInverse` steers this test.** |
| S-9 | **BEHIND_PLAYER as the *primary* cause** | **REFUTED as primary; RETAINED as amplifier and as the leading explanation of the 22↔90 oscillation** | The L1≤16 exemption is 6017 lattice points (`(2n+1)(2n²+2n+3)/3` at n=16 = `33·547/3`), of which 6014 lie inside the ±16 readback box and are computed **unconditionally every dispatch, whatever the mask says**. Observed ceiling: 90. |
| S-10 | **Same-dim windows are a mystery** | **NOT A MYSTERY, NOT IN SCOPE** | 157 armed same-dim windows read `renderAll=0 act=0` — the mod's own `NoopShadowCompositeRenderer` (IS5-FF), by design. |

**Also corrected, so nobody re-cites them:**
- Shader analyst F-2 "`ff nz` tracks the `voxel_img` emitter count 1:1" — **false in both directions**: F8256–8261 `voxel` constant at 11926 while `ff` 37→90; L13636→L13754 `voxel` **rises** 11830→11841 while `ffCopy` **falls** 90→22.
- Shader analyst F-5 "in-window dest:main ≈ 1:2.9" — the interval used spans the N-3 gap. In-window it is 1:1.
- Shader analyst's "DEST image units are swapped vs MAIN" — the unit assignment tracks the **program**, not the role (prog 219 → `floodfill_img unit=0` in both roles; prog 264 → `unit=1` in both roles). Per-pipeline `HashSet` order. Mild *positive* evidence for R-4, not a defect.
- Rate analyst F-8's mechanism for the OW `3207 → 0` collapse ("4 dispatches at posOffset=1 walked it out") — arithmetically impossible. The real account: `destAct` 55→59 = 4 OW-as-dest dispatches; the **first** of them necessarily paired the pre-teleport OW main camera `(58.586,68.620,133.722)` with the OW dest camera `(-0.521,69.620,-1.200)` ⇒ `|posOffset|∞ ≈ 135`; `voxel@709 nz=0` (`terrainDbg="C: 0/0 (-)"`) so nothing re-seeded. **The conclusion holds; the deciding dispatch is unsampled — grade PLAUSIBLE, not MEASURED.**

**One new calibration nobody computed, needed to read round 3 correctly:** the MAIN nether volume *saturates* at **6053/32768 (18.5 %)** and holds there for 12 s while stationary (F8872→F9548), only jumping to 26526 when the player moves. So "healthy" at this location is ~3000–6000 in the box, **not** 32768. The dest sits at 90. Target for round 3 is a four-figure `nz`, not saturation.

---

## 3. THE ROUND-3 INSTRUMENT

House rules honoured: log-only, behaviour-neutral, ≤1 Hz on the render thread, once-only `WARN` on every silent-skip/throw path, `-P` row in **both** `fabric/build.gradle` blocks (the `runClientSodium` block at ~L272 and the second block at ~L413), iris mixin simple names contain `Iris`. P6 is the one deliberate exception and is flagged as such.

### STEP 0 — the free A/B, no code, run this first
```
.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PactProbe=true -PactVolumeProbe=true ^
  -PactDispatchProbe=true -PdisablePrevUniformHeal=true
```
The lever already exists (`fabric/build.gradle:256`, `IPGlobal.isPrevUniformHealActive()`).
**DECISION RULE.** DEST `|posOffset|∞` collapses to ≤2 on a stationary dest camera ⇒ **the heal is the injector, MEASURED**, and the fix in §4 is justified on measurement. It stays at ~132 ⇒ the heal is **not** the injector; P1 becomes mandatory and the search moves to other `FrameUpdateNotifier.onNewFrame()` reachers. Expect the same-dim ghost-terrain regression to return during this run — that is expected and is not a new bug.

### P1 — heal-target identity (promotes the mechanism to MEASURED)
- **Hook:** `IrisInterface.healPreviousFrameUniforms()` (`IrisInterface.java:196-211`), i.e. the anchor at `IrisCompatOn262Renderer.java:292-294`. Pass in the pipeline identity captured **before** `renderPortals(...)` (the `IrisShadowCompositeSuppressor.install()` site, `IrisCompatOn262Renderer.java:259`, already runs pre-loop; `ActSeedProbe` already stores `mainPipeline` at `beginFrame`).
- **Lever:** `-Dseamlessportals.actHealProbe` (`-PactHealProbe`), DEFAULT-OFF, requires `-PactProbe`.
- **Log (≤1 Hz, folded into the existing 1 Hz emit as a new `[6]` section):**
  `[6] HEAL ANCHOR  preLoopPipeline=@IrisRenderingPipeline#xxxx dim=<nsid> | atAnchor=@IrisRenderingPipeline#yyyy dim=<nsid> | SAME|DIFFERENT | healCount=<IPGlobal.prevUniformHealCount> | crossIdThisFrame=<n> sameIdThisFrame=<n>`
- **Silent paths:** once-only `WARN` if `getPipelineNullable()` is null, if it is not an `IrisRenderingPipeline`, or if the pre-loop capture was never taken. (`IrisInterface.java:220-227` already has the skip WARN; add the null-capture one.)
- **DECISION RULE.** `DIFFERENT` with `atAnchor.dim == destDim` on cross-dim frames ⇒ **F-10/F-6 MEASURED**, the heal is the injector, fix as in §4. `SAME` on cross-dim frames ⇒ the heal is exonerated; escalate to a per-tracker tick counter (P1b: increment a counter inside a `FrameUpdateNotifier` listener registered per pipeline, log `ticksThisFrame` per pipeline; expect DEST=2 today, 1 after the fix).

### P2 — R-4 capability-gate fix (the correct ARB extension on a 3.3 core context)
- **Hook:** `ActDispatchProbe.imageLine()`, `ActDispatchProbe.java:462`.
- **Lever:** none new — rides `-PactDispatchProbe`.
- **The fix.** `GL_IMAGE_BINDING_NAME` is `0x8F3A` in **core 4.2, `ARB_shader_image_load_store`, and `EXT_shader_image_load_store` alike**; the *query function* `glGetIntegeri_v` is **GL 3.0 core** (already used at `:467`), so it carries no version requirement — and the LWJGL `GL42.GL_IMAGE_BINDING_*` names are compile-time `int` constants, safe to reference on a 3.3 context. Mirror **iris's own** gate, which is authoritative because it is what binds these images and demonstrably succeeds (`IrisRenderSystem.bindImageTexture`: `OpenGL42 || GL_ARB_shader_image_load_store → GL42C.glBindImageTexture`, else `EXTShaderImageLoadStore.glBindImageTextureEXT`):
  ```java
  GLCapabilities c = GL.getCapabilities();
  boolean canQuery = c.OpenGL42 || c.GL_ARB_shader_image_load_store || c.GL_EXT_shader_image_load_store;
  ```
  This is the **third** occurrence of the core-version-vs-ARB mistake in this kit (already fixed once for the readback gate at `ActSeedProbe.ensureGl45`); make the capability line print the raw booleans so it cannot recur silently.
- **Log (once-only, alongside `L797`):**
  `[IS5-DISP] image-binding capability: OpenGL42=<b> ARB_shader_image_load_store=<b> EXT_shader_image_load_store=<b> => R-4 ARMED|n/a`
  and per `[5]` block, the existing line now populated: `floodfill_img unit=1 tex=819 fmt=0x881a layered=true access=0x88ba`.
- **Silent paths:** keep the existing once-only WARN, but only on the genuinely-unavailable path; add a once-only WARN if `glGetIntegeri` returns 0 for an active unit (that itself is a finding).
- **DECISION RULE.** `tex ∈ {819,820}` on the dest ⇒ **R-4 REFUTED by measurement**, close it. `tex ∈ {710,711}` or `0` ⇒ **R-4 CONFIRMED**, and the existing `dbind` WARN at `:471` fires — that would demote `previousCameraPosition` to a co-defect.

### P3 — the sampler (READ) side, which R-4 never covered and needs no capability gate
The write side is already answered from existing data (the `framemod2` ↔ moved-texture pairing, 24/24). The **read** side has never been measured, and a partial sampler misbinding is observationally identical to the `posOffset` story in every leg the current kit reports.
- **Hook:** the existing `MixinIrisComputeProgram_ActDispatch` HEAD (already at `ComputeProgram.dispatch(FF)`, i.e. **after** `use()` has run `uniforms/samplers/images.update()` — javap confirms `dispatch` contains no `use()` call, so the state is the state this dispatch will run with).
- **Lever:** rides `-PactDispatchProbe`.
- **Method (all GL 1.3/2.0 — no 4.2, no ARB):** for each of `floodfill_sampler`, `floodfill_sampler_copy`, `voxel_sampler`: `glGetUniformiv(pid, loc)` → unit; save `GL_ACTIVE_TEXTURE`; `glActiveTexture(GL_TEXTURE0+unit)`; `glGetIntegerv(GL_TEXTURE_BINDING_3D)`; **restore `GL_ACTIVE_TEXTURE` in a finally** (same bracket discipline as `ActSeedProbe.packBracket`). Once per armed window only.
- **Log:** `      samplers:   floodfill_sampler unit=3 tex3D=819 | floodfill_sampler_copy unit=4 tex3D=820 | voxel_sampler unit=5 tex3D=818   (ActSeedProbe destFf=819/820 destVox=818)`
- **Silent paths:** once-only WARN if any `loc<0`, if the active-texture restore throws, or if a `tex3D` is 0.
- **DECISION RULE.** All three resolve to the dest triple ⇒ read path clean, `posOffset` owns the failure. Any one resolves to `710/711/709` or `0` ⇒ **a second, independent defect**, and it must be fixed before the `posOffset` fix can be judged.

### P4 — the spread-vs-emitter discriminator (settles U-A, the only live dispute)
- **Hook:** `ActSeedProbe.readbackTriple` / `readFloodfill` (`ActSeedProbe.java:664-750`). Read the **voxel** texture a second time at `FF_BOX=32` so the two boxes align, keep both buffers, and correlate.
- **Lever:** rides `-PactVolumeProbe`.
- **New fields on the `ff@` term:** `sum`, `mean` (currently only `nz` and `maxLum` — that is exactly why 90 bright emitters and a faint 90-voxel smear are indistinguishable today), plus:
  - `emit` = count of voxels in the ±16 box with `id != 0 && id != 1 && id < 200` (the `else { // Light Sources }` set, `shadowcomp.glsl:140`);
  - `spreadNz` = count of voxels that are **ff-nonzero AND not an emitter** — i.e. light that can only have arrived by propagation.
- **Log:** `DEST  voxel@818: nz=... max=13 emit=<n> | ff@819: nz=90 sum=<f> mean=<f> maxLum=173.546 spreadNz=<n> | ffCopy@820: ...`
- **Silent paths:** the existing `volumeDisarmed` / `packBuf!=0` once-only WARNs already cover this; add one if the two boxes' dimensions disagree.
- **DECISION RULE.**
  - `spreadNz == 0` and `nz ≈ emit` ⇒ **zero propagation, exactly as the `posOffset` account predicts. A-1 is explained by the BEHIND_PLAYER mask sweeping over a constant emitter set, and U-A closes in favour of the leading account.**
  - `spreadNz ≫ 0` ⇒ propagation exists and is being *erased* rather than *never produced* ⇒ escalate: the fault is at least partly downstream of the reprojection and P5 becomes load-bearing.
  - Watch `nz` swinging while `emit` is constant — that is the mask signature, and it is the cheap corroboration for P5.

### P5 — `gbufferModelViewInverse` on the dest ACT dispatch (the amplifier, and the mask's only steering input)
- **Hook:** the existing `[5]` block; `glGetUniformfv(pid, loc, float[16])` — the same call already used by `readVec3` at `ActDispatchProbe.java:483`.
- **Lever:** rides `-PactDispatchProbe`.
- **Log:** `      mask:  fwd(gbufferModelViewInverse)=(x,y,z)  destCamFwd=(x,y,z)  dot=<f> => MATCH|MAIN-VALUED  | proj shear m02=<f> m12=<f> (0,0 => projection inert per N-7)`
  Dump **`gbufferModelViewInverse`, not `gbufferProjectionInverse`** — S-8/N-7 proves the projection cannot rotate the test. The two shear terms are logged only to close N-7 empirically at zero extra cost.
- **Silent paths:** once-only WARN if either uniform's `loc < 0` (it would mean the compute does not see them at all, which is itself a finding).
- **DECISION RULE.** `dot < 0.9` ⇒ the dest volume's behind-player mask is oriented by the **wrong camera**; roughly the wrong half of the volume is copy-only and (under `posOffset=132`) actively zeroed. Fix alongside, or the `posOffset` fix will read as a partial success and be misjudged. `dot ≈ 1` ⇒ mask is dest-correct; the oscillation in U-A is genuine micro-spread and P4's `spreadNz` will say so.

### P6 — the paint test (NOT log-only; run only on escalation)
The one measurement that proves the *downstream* path — whether the window's shading samples the dest volume at all (`mainLighting.glsl:388 GetLightVolume` → `lightVoxelization.glsl:96`).
- **Hook:** immediately after the dest ACT dispatch, ≤1 Hz: `glClearTexImage` on the dest `floodfill_img` **and** `floodfill_img_copy` to a saturated colour, e.g. `(0,0,50,1)`.
- **Lever:** `-Dseamlessportals.actPaintTest` (`-PactPaintTest`), DEFAULT-OFF. **Behaviour-ALTERING** — house precedent exists (`debugTintStamp`, `debugTintBloomMask`, `debugBloomMaskBlackout`) but it must never ship on.
- **DECISION RULE.** Window turns blue ⇒ the consumption path is proven good; all remaining work is volume-side. Window unchanged ⇒ the volume is irrelevant to what the user sees and the whole investigation must move downstream. It also independently validates leg [4].

**Priority if only some can be built:** Step 0 → P1 → P4 → P2 → P3 → P5. P6 only on escalation.

---

## 4. IS A FIX ALREADY JUSTIFIED?

**Yes — one fix, and it is unusually cheap and unusually low-risk. But it would be a fix on INFERENCE until Step 0 or P1 confirms the injector.** The 132-block `posOffset` itself is MEASURED; *what writes it* is not.

**The fix — retarget the IS5-PH heal to the pre-loop main pipeline.**

`IrisInterface.healPreviousFrameUniforms()` (`IrisInterface.java:207-211`) resolves its target as:
```java
Object pl = Iris.getPipelineManager().getPipelineNullable();
if (pl instanceof IrisRenderingPipeline irisPipeline) { irisPipeline.getFrameUpdateNotifier().onNewFrame(); }
```
Its own javadoc (`IrisInterface.java:175-181`) states the assumption out loud: *"the nested dest render reaches `beginLevelRendering` on the **SAME per-dim pipeline**"*. **That assumption is true for same-dim and false for cross-dim** — the manager slot has three writers and no restorer (bytecode), the nested render's `iris$setupPipeline` is the last writer, and the probe *measures* `pipelineIdentity=DIFFERENT` at that point on all 42 cross-dim captures.

Change: capture the pipeline **before** `renderPortals(...)` — the same discipline `IrisShadowCompositeSuppressor.install()` already uses correctly (`IrisShadowCompositeSuppressor.java:171`) — stash it, and pass it to `healPreviousFrameUniforms(Object mainPipeline)`; tick **that object only**.

Why this is low-risk: on a **same-dim** frame the pre-loop capture *is* the same object the current code resolves, so behaviour is byte-identical and the ghost-terrain fix is preserved intact. Only the cross-dim path changes — and on cross-dim frames the main pipeline's notifier is never ticked by the nested render at all (different pipeline object, `pipelinesPerDimension.size=2`, `MAIN posOffset=0.0` in 347/358), so the heal there is today **unnecessary and purely destructive**. A mixed frame (one same-dim + one cross-dim portal) is handled correctly by the retarget and incorrectly by a blanket "skip the heal on cross-dim frames" shortcut — prefer the retarget.

Falsifiable prediction to check against the existing probe line, unchanged: dest `|posOffset|∞` drops from 132 to ≤2 on a stationary dest camera, and dest `ff@819 nz` climbs past 90 toward the 3000–6000 band that the same texture reaches as MAIN.

**Two caveats to state before anyone declares victory.**
1. Even fully fixed, the dest fill is **rate-bound at ~1 block per 2 dispatches** (`OPTIMIZATION_ACT_HALF_RATE_SPREADING`, `shadowcomp.glsl:12,128-139`) and the game runs at **0.84 fps** with a cross-dim window open. A 16-block radius is ~32 seconds of continuous staring. "Correct colour, but it creeps in" is the *expected* success signature, not a second bug. (The 0.84 fps itself is an unrelated performance item.)
2. If P5 comes back `MAIN-VALUED`, roughly half the dest volume stays dark regardless, and the fix will read as partial.

---

## 5. LIVE PROTOCOL — round 3

1. **Build with the round-3 kit.** Run A (the free one, no code needed) first:
   `.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PactProbe=true -PactVolumeProbe=true -PactDispatchProbe=true -PdisablePrevUniformHeal=true`
   Then run B with the probes but the heal **on** (drop `-PdisablePrevUniformHeal`), so the two logs differ in exactly one variable.
2. **Set the scene before opening the window.** Stand so that there are **lava, magma, torches, glowstone or a campfire within ~16 blocks on BOTH sides of the portal** — the near side (your feet) and the far side (visible through the window). The ACT fill radius at these settings is ~15–20 blocks; emitters further out contribute nothing to the readback box. A nether lava lake on the far side plus torches on your side is ideal.
3. **Use a CROSS-DIMENSION portal.** Same-dim windows are suppressed by design (`renderAll=0`) and produce no dest ACT dispatch at all.
4. **Stand still, look through the window, and do not move the mouse — for a full 60 seconds.** This is the single most important instruction and it is new for round 3: at ~1 fps that is only ~60 dispatches, and the mask-sweep ambiguity (U-A) is only resolvable if the view direction is genuinely constant. Rest your hand off the mouse. Do not strafe, do not turn.
5. **Then, without leaving, rotate slowly ~180° over ~15 s and stop.** This deliberately sweeps the BEHIND_PLAYER mask so P4's `emit` / `nz` / `spreadNz` triple can be read against a known-changing mask.
6. **Walk through the portal** and stand still in the destination for **20 seconds**. This reproduces the F8267→F8318 role-swap A/B in a controlled way and gives the round-3 positive control on the same texture.
7. **Look back through the return portal for 30 seconds**, standing still. This is the case that produced the OW `3207 → 0` wipe last time.
8. Say out loud (or note the wall-clock) when the colour looks right vs wrong, so the log can be aligned to the observation. **Your live observation outranks every number in the log.**
9. Keep each run under ~3 minutes; leg [4] issues a 1 Hz DSA readback and is a visible hitch. Do not leave the probes on for gameplay.

---

## 6. HONEST LIMITS

**Cannot be answered by this log, and are not answered by anyone's analysis:**

- **Which object the heal ticked.** Bytecode-proven premises + a uniquely-selecting timing observation (N-2), but zero log lines. `IPGlobal.prevUniformHealCount` is incremented (`IrisInterface.java:212`) and **never emitted**; the once-only liveness line (`L2776`) names no pipeline. INFERRED, not MEASURED.
- **R-4 (write side) and the sampler (read side).** 0/800 measurements. Two independent bytecode chains say the bindings are frozen per pipeline at program-build time; I accept them as analysis and reject the "DEFINITIVE" framing.
- **Whether the dest volume propagates at all (U-A).** Two mechanisms fit the 37↔90 oscillation identically and the probe reports neither `sum` nor `emit`. P4 settles it; nothing in the current data can.
- **`gbufferModelViewInverse` on the dest ACT dispatch.** Never probed. Refuted as *primary* by the 6014-voxel unconditional core, live as an amplifier.
- **`cameraPositionBestFract`** (`lightVoxelization.glsl:17`), the write-side sibling of the confirmed defect, from the same `getUnshiftedCameraPosition()` family. Indirectly reassuring (`DEST voxel nz` 11477–11941 in a camera-centred box is healthy), unmeasured. Worst case ≪1 block.
- **The dest voxelisation is materially thinner and material-truncated**: as DEST `voxel@818 nz=11940 max=13`; as MAIN, same texture, same room, 23 s later `nz=18024 max=28`. `bes=0` on every dest frame, and the kit prints `shadowCounts` **only for the dest**, so there is no MAIN baseline to compare against. +51 % nonzero and a truncated material set is a real, independent defect that no `previousCameraPosition` fix touches. It cannot explain a 67× flood-fill deficit (the seed deficit is 1.5×), so it is not the cause — but it will degrade the window's colour after the primary fix lands.
- **The ~45× frame-rate collapse** (0.84 fps in-window vs 38–56 fps out) — out of scope here, and the entire reason "60 dispatches" looked anomalous.
- **18 of 60 dest dispatches were never sampled**, and they are the interesting ones: `destAct` 30–43 (the N-3 gap) and 56–58 (the OW `3207→0` wipe). The wipe has **no measured dispatch at all**.
- **The single most load-bearing control does not exist in this log:** there is no cross-dim dest sample with a **seeded** volume and a **small** `posOffset`. The two `posOffset≈1` captures are on a cold volume (`voxel nz=0`, `terrainDbg="C: 0/0 (-)"`). Round 3's Step 0 manufactures exactly that control.

**The single trigger that justifies going further than round 3:**
If Step 0 / P1 confirms the heal, the retarget lands, and the dest `posOffset` measurably drops to ~0 — **and the user still reports standard-coloured light in the window** — then the volume is not the problem and every further volume measurement is wasted. At that point, and only at that point, run **P6 (the paint test)**. Blue window ⇒ the consumption path is good and the remaining fault is the dest voxelisation (the `nz` 11940-vs-18024 / `max` 13-vs-28 defect). Unchanged window ⇒ the window's shading never samples the dest ACT volume, the entire IS5-ACT line of investigation is closed, and the work moves downstream to `GetLightVolume` / the dest gbuffer pass's sampler bindings.

---

# ★ ENGAGEMENT CLOSED — 2026-07-26, LIVE-CONFIRMED BY THE USER

**User verdict:** *"colored light works now, ghost still gone … colored light not creeping in, showing
up nice and smooth, good."*

**Fix shipped `e33bc4e`** — retarget the IS5-PH heal to the PRE-LOOP captured main pipeline.
Lever `-Dseamlessportals.disableHealRetarget` (DEFAULT-ON fix, rows in both gradle blocks).

## The A/B ladder, in one table

| run | heal | retarget | DEST `\|posOffset\|∞` | dest floodfill nz |
|---|---|---|---|---|
| 3 | ACTIVE | — | 132 ×38, 133, 134 | **plateau 90** |
| 5 (Step 0) | DISABLED | — | 0.0 ×85, 1.0 ×3, 2.0 ×1 | 135 → **15283** |
| 6 | ACTIVE | **ON** | 0.0 ×171, 1.0 ×15, 2.0 ×2 | **4149–7293** |
| 7 (no probes) | ACTIVE | ON | — | user: colour correct, ghost absent |

Final clean run: GL census **6** = exactly the known iris `copyPre*` baseline, zero probe output
(correctly default-off), full fix stack live (`IS5-PH`, `IS5-G`, `C3-BLOOM`, `iris-bob-sync`,
`DESPAWN-SUPPRESS`), and all 43 warnings pre-existing third-party noise.

## Residual watch items (none blocking)

1. **~16 of ~204 dest `posOffset` samples read 53–68** in run 6 — most likely genuine camera jumps at
   crossing/teleport frames. Unproven; benign so far.
2. **Dest voxelisation is thinner than main** (as DEST `voxel nz=11940 max=13`; same texture as MAIN
   23 s later `nz=18024 max=28`, and `bes=0` on every dest frame). A real, independent defect that the
   `previousCameraPosition` fix does not touch — it cannot explain a 67× flood-fill deficit (the seed
   deficit is 1.5×), but it will slightly degrade in-window colour. Not user-reported.
3. **The ~45× in-window frame-rate collapse** (0.84 fps with a cross-dim window open vs 38–56 fps out)
   — entirely separate performance item, and the reason "60 dispatches" looked anomalous in run 3.
4. **`-PactVolumeProbe` is the lag source** (1 Hz GPU→CPU readback). Never leave it on for gameplay.

## The prediction that was wrong in the good direction

The design panel predicted the fill would "creep in over ~30 s" (~1 block per 2 dispatches under
`OPTIMIZATION_ACT_HALF_RATE_SPREADING`) and pre-registered that as the success signature so it would
not be misread as a second bug. The user reports it arriving **smooth and immediate**. Conservative
prediction, better reality — no action needed.
