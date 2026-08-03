# IS5-MB — ADJUDICATED SPEC: per-dest previous-frame camera state for the same-dim portal composite

**Verdict in one line:** Design A's *scope* is wrong (it drops the model-view lineage argument and keeps a projection residual it did not need to keep), Design B's *frame model* is right but its §0a conclusion ("S6 rejected, regime unknown") is under-exploited, and **both** designs sourced the "current" dest state from the wrong place. The adjudicated design writes at Design B's seam pair, stores Design A's minimal per-portal record, and sources the current trio **mod-side from `CapturedRenderingState` + `CameraUniforms.getUnshiftedCameraPosition()`** — which is regime-independent, needs no shift reverse-engineering, and cuts the reflection surface to three fields.

---

## 0. INDEPENDENT RE-VERIFICATION (the three the brief demanded; nothing inherited)

### (a) The seam's offset and its ordering relative to `uniforms.update()` — **VERIFIED, and both targets are unique in the whole class**

`javap -p -c -l net.irisshaders.iris.pipeline.CompositeRenderer`:

```
Program.use():                                   CompositeRenderer.renderAll():
   0: sipush 8232                                  ...
   3: IrisRenderSystem.memoryBarrier:(I)V           414: aload 6
   6: aload_0                                       416: getfield Pass.program:Lnet/.../Program;
   7: getGlId:()I                                   419: invokevirtual Program.use:()V    <-- S1 (AFTER => 422)
  10: GlStateManager._glUseProgram:(I)V             422..431: CustomUniforms.push(pass.program)
  13: aload_0                                       434..444: _glBindBuffer(34963, ...)
  14: getfield uniforms                             447..454: iconst_4 / bipush 6 / toGl
  17: ProgramUniforms.update:()V   <-- the gate     455: GlStateManager._drawElements:(IIIJ)V
  24: ProgramSamplers.update:()V                    458: BlendModeOverride.restore:()V     <-- S4 (BEFORE)
  31: ProgramImages.update:()V                      461: GLDebug.popGroup:()V
  34: return                                        464: iinc 4, 1
                                                    467: goto 85
```

Occurrence counts, grepped over the **entire class listing** (not just `renderAll`):

| descriptor | count in class | count in `renderAll` | offset |
|---|---|---|---|
| `Lnet/irisshaders/iris/gl/program/Program;use()V` | **1** | **1** | 419 |
| `Lnet/irisshaders/iris/gl/blending/BlendModeOverride;restore()V` | **1** | **1** | 458 |
| `GlStateManager._drawElements:(IIIJ)V` | 1 | 1 | 455 |

The `use()V` at offset 169 has owner `ComputeProgram` — a **different descriptor**, never matched. **No `ordinal` is needed on either injection.**

**Ordering result:** `Shift.AFTER` on the INVOKE at 419 lands at **422**, i.e. after `use()` returns — after `_glUseProgram(getGlId())` (@10) **and** after `uniforms.update()` (@17). ⇒ *the pass program is already current and iris has finished every uniform upload for it.* 419→455→458 is **straight-line, no branch** (dumped and inspected). ⇒ S1 and S4 are 1:1 paired on exactly the same iterations, and a single static pending slot identifies the pass with no `@Local` on the restore side.

`LocalVariableTable` for `renderAll`: `slot 4 = i : I, start 76, length 394` (covers 419 and 458); the second `i` at slot 4 starts at 483 (out of range). In-scope ints at 422: slots 4 (`i`), 5 (`passesSize`), 10 (`beginWidth`), 11 (`beginHeight`); slot 7 `ranCompute` is `Z`. ⇒ **`@Local(ordinal = 0) int i` resolves to `i`** — the same resolution the shipped C3-BLOOM mixin already relies on at offset 213.

`ShadowCompositeRenderer` is a separate class in `net.irisshaders.iris.shadows` — never matched.

### (b) GL entry point via **ARB extension** on a 3.3 CORE context — **the question does not arise, and that is the finding**

Because S1/S4 sit **after** `_glUseProgram` and **before** any other `_glUseProgram` (the next one is `GlStateManager._glUseProgram(0)` at offset 477, past the loop), the pass program is **already bound**. Therefore the write uses:

- `GL20.glGetUniformLocation(int program, CharSequence)` — **core GL 2.0**
- `GL20.glGetUniformfv(int program, int location, float[])` — **core GL 2.0**
- `GL20.glUniform3f(int location, float, float, float)` — **core GL 2.0**
- `GL20.glUniformMatrix4fv(int location, boolean, float[])` — **core GL 2.0**

All four are unconditionally present on any 3.3 CORE context. **No ARB extension, no capability gate, no version flag, no re-binding, no `GlStateManager`/`ProgramUniforms.active` desync.** This is byte-for-byte iris's own idiom — `Vector3Uniform.updateValue` offset 69 `IrisRenderSystem.uniform3f(location,…)`; `MatrixUniform.updateValue` offsets 45-58 `Matrix4f.get(FloatBuffer)` → `glUniformMatrix4fv(location,false,buffer)` — both **bound-program** calls with no program id.

**`glProgramUniform*` is NOT used anywhere in this design**, so the burnt-twice trap (constraint 4) is structurally avoided rather than mitigated. Binding rule for any future variant, recorded so it is not re-derived: gate on `GL.getCapabilities().glProgramUniform3f != 0L` / `.glProgramUniformMatrix4fv != 0L` — the function-pointer idiom already shipped at `IrisBloomApertureMask.java:145-148` — **never** on `OpenGL41`/`OpenGL45`.

### (c) Which of the three previous-frame uniforms are **genuinely main-valued** at a same-dim dest pass — **re-derived from bytecode + tree + pack, not inherited**

Chain of verified facts:

1. `CameraUniforms.addCameraUniforms` offset 0-8 constructs **one `CameraPositionTracker` per `UniformHolder`** (= per program). `cameraPosition` PER_FRAME @40-56 (`ldc #42`), `previousCameraPosition` PER_FRAME @82-98 (`ldc #63`), suppliers `tracker::getCurrentCameraPosition` / `::getPreviousCameraPosition`.
2. `CameraPositionTracker.update()` @0-37: `previousCameraPosition = currentCameraPosition; previousCameraPositionUnshifted = currentCameraPositionUnshifted; currentCameraPosition = getUnshiftedCameraPosition().add(shift); currentCameraPositionUnshifted = getUnshiftedCameraPosition(); updateShift();`. `getUnshiftedCameraPosition()` @0-15 = `mc.gameRenderer.mainCamera().position()`, and `MyGameRenderer.switchAndRenderTheWorldFullPipeline` swaps `mainCamera()` to the dest `Camera` (`ieGameRenderer.ip_setCamera(newCamera)`, restored in the finally).
3. The tracker advances on `FrameUpdateNotifier.onNewFrame()` (ctor @59-66 registers `this::update`). A nested dest render reaches `beginLevelRendering` again (no re-entrancy guard), **and** the shipped IS5-PH heal (`IrisInterface.java:253` `irisPipeline.getFrameUpdateNotifier().onNewFrame()`, default-ON) adds a third tick after the loop. Trace on frame N: tick1(main BLR) → `prev=main_{N−1}, cur=main_N`; tick2(dest BLR) → **`prev=main_N, cur=dest_N`**; tick3(heal) → `prev=dest_N, cur=main_N`.
4. `MatrixUniforms.addMatrix` @47-55 constructs **one `MatrixUniforms$Previous` per program per matrix**. `Previous.get()` @0-38 is a self-advancing register: snapshot `parent.get()` → return the stored copy → store the snapshot. It advances **once per `updateStage(perFrame)` of the owning program**, so on a portal frame (two composite chains) it **alternates**.
5. `MixinLevelRenderer.iris$setupPipeline` @17-22 `CapturedRenderingState.INSTANCE.setGbufferModelView(arg5)` — **arg 5 by reference**; `SecondaryWorldRenderCore.java:1865-1872` passes **`destDrawViewMatrix`** to `destRenderer.render(...)`. ⇒ `gbufferModelView` **is genuinely dest-valued** during the nested render.
6. Same method @25-49 `setGbufferProjection(new Matrix4f(GameRendererStorage.sodium$getProjectionMatrix()))`. Sodium's cache is `GameRendererMixin.projection`, written **only** from `sodium$setProjection`, a `WrapOperation` on vanilla's `ProjectionMatrixBuffer` call inside `GameRenderer`. The mod's Step-7 install is `RenderSystem.setProjectionMatrix(writeProjectionSlice(destDrawProjection), …)` (`SecondaryWorldRenderCore.java:1650-1651`) and `writeProjectionSlice` (`:2785-2792`) builds its **own** UBO via `RenderSystem.getDevice().createBuffer(...)` — it never touches sodium's cache. ⇒ **`gbufferProjection` at the dest is the MAIN projection.** (Independently benign: `RenderStates.getPortalDrawProjection` at `:273-288` returns `capturedMainPassBobbedProjection` **bit-copied** unless `extraScaling != 1.0`, so `destDrawProjection == P_main` for every unscaled portal anyway.)

**Result table — same-dim dest `composite4`:**

| uniform | value the shader reads | main-valued? | numerically wrong? |
|---|---|---|---|
| `cameraPosition` | `dest_N` (shifted) | no | correct |
| **`previousCameraPosition`** | **`main_N`** | **YES** | **YES — always. \|Δ\|=125.82 measured, saturates the clamp** |
| `gbufferModelView` | `destDrawViewMatrix` | no | correct |
| **`gbufferPreviousModelView`** | **`MV_main_N`** (register alternation) | **YES** | **YES — two ways: wrong lineage (full-strength on a rotated/mirrored portal) AND wrong frame (kills all rotational blur even on a translation portal)** |
| `gbufferProjection` | `P_main_N` | yes | benign — `P_dest ≡ P_main` bit-identical when `extraScaling == 1` |
| **`gbufferPreviousProjection`** | **`P_main_N`** (= the *current* frame's projection, because both register slots hold the same main matrix) | yes | **mildly — one frame of FOV/bob, and it is *inconsistent* with a corrected `prevCam`/`prevMV` pair** |

**Answer to the scope question the brief said not to hand-wave: two of the three must be made per-dest unconditionally, and the third must be made per-dest *for consistency* — all three are in scope.**

The decisive new argument, which neither design made: **on a pure-translation portal, `MV_dest ≡ MV_main`, so a camera-position-only fix leaves `gbufferPreviousModelView == gbufferModelView` ⇒ the window has ZERO rotational blur while the main view smears on every mouse turn.** That fails the user's own accept criterion ("smear matches the main-view smear across the window edge"). Design A's A/B-2 would have failed on its own default build. The matrix half is not a rotated-portal luxury — it is required for the stated goal on the *reported* geometry.

And once `prevCam` and `prevMV` describe frame N−1 while `prevProj` describes frame N, an FOV ramp (sprint / damage tilt / spyglass) mixes epochs. Storing the projection costs **one more 16-float slot in a record we are already allocating** and one more read/write/restore triple. Include it.

`previousCameraPositionInt` / `previousCameraPositionFract` (`CameraUniforms.addCameraUniforms` @133-160, `lib/uniforms.glsl:95,97`) are also tracker-backed and also main-valued at the dest — Design A missed them entirely — but `composite4.glsl` does not read them (grep: its only previous-uniform uses are lines **120, 123, 124**). **Out of scope; ledgered as residual R3.**

### (c-bis) The regime question — settled, and turned into an instrument rather than a blocker

Design B is **right** that Design A's frame model is dead code: `IPGlobal.java:275 irisPerFrameRefresh = false`, no runtime writer anywhere in the tree, so both `bumpPerFrameUniformCounter()` calls at `IrisCompatOn262Renderer.java:425,440` return at `IrisInterface.java:172`. And `SystemTimeUniforms.COUNTER.beginFrame()` is reachable from exactly one place in the 963-class jar — `MixinGameRenderer.iris$startFrame(DeltaTracker, boolean, CallbackInfo)` @13-16, whose signature is `GameRenderer.render(DeltaTracker, boolean)`, i.e. **once per game frame**. `PipelineManager.preparePipeline` @0-10 `if (containsKey) goto 109`, skipping `COUNTER.reset()` @13-16 on a same-dim re-entry. So statically the dest chain should skip `updateStage(perFrame)` (`ProgramUniforms.update` @94-122).

But the measurement forces the opposite. `MbGateProbe.onPass` filters on `TARGET_PASS.equals(name)` and fires at offset **213 — the top of composite4's own iteration**, so `glGetUniformfv` reads what the **previous chain execution** left in composite4's program storage. Under the *gated* hypothesis the dest chain writes nothing, so the next frame's MAIN row would read `(cam=main_N, prev=main_{N−1})` ⇒ `|Δ| ≈ 0` for a stationary player. The measured MAIN-labelled row is `(cam=dest, prev=main), |Δ|=125.82`. **⇒ the dest chain DID execute `updateStage(perFrame)`. De-gated. Confirmed by consequence.** The `role=` label was never inverted; the 213 seam is one-chain-stale, and both measured rows fall out exactly.

**The mechanism remains unidentified — and this design does not need it.** The chosen mechanism writes program storage *after* `uniforms.update()`, which is correct under both regimes, and §4.3 below adds a **two-line regime detector** that reports which regime it observed. That closes Design B's R0 as a by-product instead of blocking on it.

---

## 1. CHOSEN MECHANISM

> **W-restore at the S1/S4 seam pair, armed by a dedicated bracket around the nested dest render, keyed per `Portal`, with all three previous-frame uniforms sourced mod-side and neutralization as the named fallback.**

**S1 — WRITE.** `@Inject` at `INVOKE Lnet/irisshaders/iris/gl/program/Program;use()V`, `shift = AFTER`, `@Local(ordinal = 0) int i`. Program bound, `uniforms.update()` done, no ordinal needed.
**S4 — RESTORE.** `@Inject` at `INVOKE Lnet/irisshaders/iris/gl/blending/BlendModeOverride;restore()V` (default BEFORE shift = offset 458, immediately after `_drawElements`), **no `@Local`**.
**Write calls.** Bound-program `GL20.glUniform3f` / `GL20.glUniformMatrix4fv`. Core GL 2.0, no extension, no re-bind.

### Why the restore is mandatory, not a nicety
`Vector3Uniform.updateValue` @13-21 `if (newValue.equals(cachedValue)) return;` and `MatrixUniform.updateValue` @13-21 `if (cachedValue.equals(newValue)) return;` — **iris skips the GL upload when its Java-side value is unchanged.** With a stationary player `previousCameraPosition` is byte-stable frame to frame, so iris would never overwrite our write and the **main view would inherit dest values from the next frame**. W-restore keeps `cachedValue` truthful with zero reflection into iris's uniform lists. (W-invalidate — reflecting `Program.uniforms` → `ProgramUniforms.perFrame` → match by `Uniform.location` — is rejected: three more private members, no benefit.)

### Why each rejected option lost

| option | verdict |
|---|---|
| **S6 — wrap the supplier at `addCameraUniforms`/`addMatrixUniforms`** (Design A called it "structurally cleanest") | **Rejected.** It only takes effect when iris *pulls* the supplier, i.e. inside `updateStage(perFrame)`. The de-gater is unidentified and could disappear on any iris patch; in the gated regime S6 is a silent no-op that leaves the defect and reports success. It also reaches every program in the pipeline (gbuffers, shadow, final) — a far larger behavioural surface for a fix scoped to one composite pass. Design B's rejection is correct and I adopt it. |
| **S2 — `CustomUniforms.push` ordinal 1** | **Rejected as unnecessary.** Verified: the pack declares 21 customs, all `uniform.float.*` (`shaders.properties:240-276`), none named `previousCameraPosition`/`gbufferPrevious*`; the only references are `variable.float.difX/Y/Z` at `:251-253`, which are **read** Java-side from iris's own values. So nothing between 419 and 455 can clobber us, and S2 would buy an `ordinal` (drift-fragile against the compute-side sibling at 178) for nothing. |
| **S3 — before `_drawElements`** | **Rejected.** `GlStateManager._drawElements` is a **Mojang-owned member** inside a `remap = false` mixin; the descriptor is taken verbatim and will not resolve against remapped production names. |
| **S5 — `Program.use()` TAIL / engine-wide** | **Rejected.** No pass identity, ~2000 guarded binds/frame, and it fights `ProgramUniforms.active` bookkeeping. |
| **S7 — counter bump / `lastFrame` poke** | **Rejected.** §1e cache-skip: a forced re-update *skips* `previousCameraPosition` because the supplier's value is unchanged. A counter bump alone provably cannot fix this. (It is also the retired, dead mechanism.) |
| **Read the current dest state back from GL** (Design A step 6) | **Rejected.** Regime-dependent: in the gated regime `cameraPosition` is main-valued and the stored "dest camera" would be the main camera. Replaced by mod-side sourcing (§4.3). |
| **Neutralize-only** | **Rejected by the user**, and correctly: it produces zero velocity, not correct velocity. It survives here **only as the named degenerate fallback**. |
| **Storing state on `PortalRenderInfo`** (Design A) | **Rejected.** It edits a core IP class and puts an iris-only field on every portal. A `WeakHashMap<Portal, …>` inside the iris-only handler gives the same automatic disposal with zero core-class churn. |
| **Axis D — repairing the main view's poisoned `Previous` register** | **Rejected for this change.** The poison lives in a private Java field, cannot be reached by a GL write, and fixing it necessarily changes main-view pixels on portal frames — violating the binding neutrality contract. Ledgered as R1 with a named escalation trigger. |

---

## 2. WHAT IS STORED PER DEST — keying, eviction, first frame

### The record (a POJO with zero iris/GL imports)

```
final class DestPrevState {
    final double[] camUnshifted = new double[3];  // dest camera, UNSHIFTED world space
    final float[]  modelView    = new float[16];  // column-major, = CapturedRenderingState value
    final float[]  projection   = new float[16];  // column-major
    int  programId  = 0;      // the composite4 GL program this was captured against
    int  frameStamp = -1;     // RenderStates.frameIndex at capture
    boolean valid   = false;
}
```
20 floats + 3 doubles + 2 ints + a flag per live portal. No matrices are held by reference (`setGbufferModelView` takes `Matrix4fc` **by reference**, so a copy is mandatory).

### Keying

`WeakHashMap<Portal, DestPrevState>` keyed on **the `Portal` instance**, held statically in `IrisDestPrevCamera`.

- Portal identity is the right key: the value is "the camera this *specific window* was rendered from last frame". Dest dimension and camera pose are derivable from the portal; portal layer is not needed because this renderer is **one-layer-only** (`IrisCompatOn262Renderer.doRenderPortal` early-returns on `PortalRendering.isRendering()`).
- `Portal extends Entity`, so a live portal is strongly held by its level; a removed portal's entry dies with it.
- **Recursion guard (better than either design):** `arm()` records `PortalRendering.getPortalLayer()`. If it is ever `!= 1`, **skip + once-only WARN `recursion`**. Under recursion the correct key is the *ordered path* of portal UUIDs (portal A seen directly and A-through-B are two different cameras); rather than pretend, the feature declines and says so. This makes the one-layer assumption **self-reporting** instead of silently wrong if `maxPortalLayer > 1` is ever enabled here.

### Eviction

| trigger | action |
|---|---|
| portal removed / GC'd | `WeakHashMap` entry dies automatically |
| map size > 32 at disarm | one-pass sweep dropping entries with `frameIndex − frameStamp > 300`; never runs on a normal frame |
| GL program id changed (pack reload, F3+R, resize) | `rec.programId != pid` ⇒ **neutralize** this frame, re-capture |
| dimension unload / world exit | `IrisDestPrevCamera.teardown()` clears the map — registered beside `IrisBloomApertureMask.teardown()` at `IrisCompatOn262Renderer.java:511` (which is already driven by `IPCGlobal.CLIENT_CLEANUP_EVENT` at `:107-110` and by `onSwitchedAway()` at `:518`) |
| frame with no portal | bracket never fires; map untouched; zero GL |

**Uniform *locations* are cached in a one-entry cache keyed by `programId`** — never globally. A changed id forces re-resolution. This is the reload-safety discipline of `IrisBloomApertureMask`'s `WeakHashMap<CompositeRenderer, MaskPlan> planCache`.

### First frame and every other degenerate case ⇒ **NEUTRALIZE** (the documented safe state)

Neutralize = write `previousCameraPosition := cameraPosition`, `gbufferPreviousModelView := gbufferModelView`, `gbufferPreviousProjection := gbufferProjection` ⇒ `velocity ≡ 0` ⇒ **one blur-free frame in the window**. Note this is still strictly better than the defect (which saturates), so the fallback never regresses.

| case | detection | action |
|---|---|---|
| first composite for this portal | `rec == null \|\| !rec.valid` | neutralize + counter |
| program id changed | `rec.programId != pid` | neutralize |
| portal was occlusion-culled / off-screen | `(RenderStates.frameIndex − rec.frameStamp)` outside `[1, 4]` (`RenderStates.frameIndex` at `RenderStates.java:64`; the `4` tolerance exists because `MinecraftFramePumpMixin` skips `frameIndex++` on mismatch frames) | neutralize |
| shift epoch changed | **not a degenerate case here** — see §4.3, it is handled *exactly* by re-basing |
| regime detector says "gated / ambiguous" | §4.3 | **skip entirely** (leave iris's values) + once-only INFO naming the observed numbers |
| any location < 0 | `glGetUniformLocation` | skip entirely + once-only INFO `noloc` |
| restore seam unproven | §4.5 latch | probe frame: read only, **write nothing** |
| anything throws | `catch (Throwable)` | restore if pending, **permanent disarm**, once-only WARN |

---

## 3. FILE PLAN

| # | file | action |
|---|---|---|
| 1 | `common/src/main/java/qouteall/imm_ptl/core/compat/iris_compatibility/IrisDestPrevCamera.java` | **NEW** — the whole feature: record, map, arm/disarm bracket, S1/S4 handlers, reflection, regime detector, logging, teardown |
| 2 | `common/src/main/java/qouteall/imm_ptl/core/compat/mixin/iris/MixinIrisCompositeRenderer_DestPrevWrite.java` | **NEW** — S1 |
| 3 | `common/src/main/java/qouteall/imm_ptl/core/compat/mixin/iris/MixinIrisCompositeRenderer_DestPrevRestore.java` | **NEW** — S4 |
| 4 | `common/src/main/resources/seamlessportals-ip-compat.mixins.json` | **EDIT** — 2 entries |
| 5 | `common/src/main/java/qouteall/imm_ptl/core/IPGlobal.java` | **EDIT** — levers + counters |
| 6 | `common/src/main/java/qouteall/imm_ptl/core/compat/iris_compatibility/IrisCompatOn262Renderer.java` | **EDIT** — 2 lines at the bracket, 1 line in `teardown()` |
| 7 | `fabric/build.gradle` | **EDIT** — 4 rows in **each** of the two blocks |

**No core IP class is touched.** (Design A's `PortalRenderInfo` edit is dropped.)

### 3.1 `seamlessportals-ip-compat.mixins.json`

Insert immediately **after** `"iris.MixinIrisCompositeRenderer_BloomApertureMask"` in the `client` array:

```json
    "iris.MixinIrisCompositeRenderer_BloomApertureMask",
    "iris.MixinIrisCompositeRenderer_DestPrevWrite",
    "iris.MixinIrisCompositeRenderer_DestPrevRestore",
    "iris.MixinIrisComputeProgram_ActDispatch",
```
Both simple names contain `"Iris"` and neither contains `"Sodium"` ⇒ `IPCompatMixinPlugin` gate-1 routes to `isIrisPresent()` alone. `"injectors": {"defaultRequire": 1}` is overridden per-injection by `require = 0`.

### 3.2 `IrisCompatOn262Renderer.java` — exact insertion points

At **`:435-441`**, the existing bracket becomes:

```java
        IrisTemporalTargetGuard.clearForDestPass();
        // IS5-MB: arm the per-dest previous-frame camera state for THIS portal's nested dest
        // composite chain. SAME-DIM ONLY — cross-dim already runs its own per-dimension pipeline
        // whose addCameraUniforms built a FRESH CameraPositionTracker (javap offsets 0-8), so its
        // prev=dest(N-1)/cur=dest(N) is already correct; writing there would INTRODUCE a defect
        // (the IS5-ACT A/B measured exactly that: heal DISABLED => |posOffset|inf <= 2 on 89/89).
        IrisDestPrevCamera.arm(PortalRendering.getRenderingPortal());   // NEW  (line 436)
        try {
            MyGameRenderer.renderWorldFullPipeline(worldRenderInfo);
        }
        finally {
            IrisDestPrevCamera.disarmAndReport();                      // NEW  (first in finally)
            IrisInterface.invoker.bumpPerFrameUniformCounter();
        }
```
`disarmAndReport()` is **first** in the `finally` so the arm can never outlive the window even if the bump throws — the discipline of `IrisShadowCompositeSuppressor.uninstall()` at `:352`.

At **`:509-514`**, beside the existing teardowns:

```java
        try {
            IrisDestPrevCamera.teardown();
        } catch (Throwable t) {
            // disposal is best-effort
        }
```

### 3.3 `IPGlobal.java` — insert after the C3-BLOOM block (`:445-478`)

```java
    // IS5-MB PER-DEST PREVIOUS-FRAME CAMERA STATE (2026-07-26) — the same-dim portal-window
    // motion-blur smear fix. MEASURED: at the same-dim dest composite4 the shader reads
    // cameraPosition=dest_N but previousCameraPosition=main_N (|d|=125.82) => the clamp
    // velocity/(1+|velocity|)*S saturates => full-strength smear with the player stationary.
    // Fix = write the DEST's own previous-frame trio (previousCameraPosition,
    // gbufferPreviousModelView, gbufferPreviousProjection) into the composite4 program between
    // its Program.use() and its draw, then RESTORE iris's values before the next pass.
    // DEFAULT TRUE; A/B OFF via -Dseamlessportals.disableIrisDestPrevCamera.
    public static final boolean IRIS_DEST_PREV_CAMERA_DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableIrisDestPrevCamera");
    public static boolean irisDestPrevCamera = true;
    public static boolean isIrisDestPrevCameraActive() {
        return irisDestPrevCamera && !IRIS_DEST_PREV_CAMERA_DISABLED_LEVER;
    }

    /** IS5-MB matrix half (gbufferPreviousModelView + gbufferPreviousProjection). Set to force
     *  camera-position-only, which is the A/B-3 leg proving the matrices are load-bearing:
     *  -Dseamlessportals.irisDestPrevCameraNoMatrices */
    public static final boolean IRIS_DEST_PREV_NO_MATRICES =
        Boolean.getBoolean("seamlessportals.irisDestPrevCameraNoMatrices");

    /** IS5-MB guarded-pass name list, comma-separated. Default "composite4" (the pack's sole
     *  MOTION_BLURRING_STRENGTH consumer). Override only if a pack renames the pass — the
     *  first-armed-chain roster line names every pass it saw, so the value is never guesswork. */
    public static final String IRIS_DEST_PREV_PASSES =
        System.getProperty("seamlessportals.irisDestPrevCameraPass", "composite4");

    /** IS5-MB 1Hz [IS5-MB] counter probe (default OFF): -Dseamlessportals.destPrevCameraProbe */
    public static final boolean DEST_PREV_CAMERA_PROBE =
        Boolean.getBoolean("seamlessportals.destPrevCameraProbe");

    public static int irisDestPrevWriteCount = 0;
    public static int irisDestPrevNeutralizeCount = 0;
    public static int irisDestPrevMissCount = 0;
```

### 3.4 `fabric/build.gradle` — **BOTH** blocks

Block 1 — insert after line **278** (`bloomMaskProbe` row). Block 2 — insert after line **431** (the mirrored `bloomMaskProbe` row). **Identical text in both:**

```groovy
            // IS5-MB per-dest previous-frame camera state (2026-07-26) — the same-dim portal-window
            // motion-blur smear fix is DEFAULT-ON; pass the first to force it OFF and reproduce the
            // saturated stationary smear (attribution both directions) —
            //   .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PdisableIrisDestPrevCamera=true
            if (project.findProperty('disableIrisDestPrevCamera') == 'true') { vmArg('-Dseamlessportals.disableIrisDestPrevCamera=true') }
            // A/B-3: force camera-position-only (drops the matrix half) — expected FAIL on a rotated
            // portal, and expected LOSS OF ROTATIONAL BLUR on a translation portal —
            //   .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PirisDestPrevCameraNoMatrices=true
            if (project.findProperty('irisDestPrevCameraNoMatrices') == 'true') { vmArg('-Dseamlessportals.irisDestPrevCameraNoMatrices=true') }
            // Guarded-pass override (comma-separated; default "composite4") —
            //   .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PirisDestPrevCameraPass=composite4
            if (project.hasProperty('irisDestPrevCameraPass')) { vmArg("-Dseamlessportals.irisDestPrevCameraPass=${project.property('irisDestPrevCameraPass')}") }
            //   .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PdestPrevCameraProbe=true
            if (project.findProperty('destPrevCameraProbe') == 'true') { vmArg('-Dseamlessportals.destPrevCameraProbe=true') }
```

---

## 4. THE CODE SHAPE

### 4.1 The two mixins

```java
@Pseudo
@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class MixinIrisCompositeRenderer_DestPrevWrite {
    @Inject(
        method = "renderAll",
        at = @At(value = "INVOKE",
                 target = "Lnet/irisshaders/iris/gl/program/Program;use()V",
                 shift = At.Shift.AFTER),
        remap = false, require = 0
    )
    private void seamlessportals$writeDestPrev(CallbackInfo ci, @Local(ordinal = 0) int i) {
        IrisDestPrevCamera.onPassProgramBound((CompositeRenderer) (Object) this, i);
    }
}
```
```java
@Pseudo
@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class MixinIrisCompositeRenderer_DestPrevRestore {
    @Inject(
        method = "renderAll",
        at = @At(value = "INVOKE",
                 target = "Lnet/irisshaders/iris/gl/blending/BlendModeOverride;restore()V"),
        remap = false, require = 0
    )
    private void seamlessportals$restoreDestPrev(CallbackInfo ci) {
        IrisDestPrevCamera.onPassDrawn();   // no @Local => no MixinExtras apply-crash corner
    }
}
```

### 4.2 Arm / disarm (mod side, decided **before any GL call**)

```java
public static void arm(@Nullable Portal portal) {
    armed = null;                                     // always start clean
    if (portal == null || !IPGlobal.isIrisDestPrevCameraActive() || broken) return;
    if (PortalRendering.getPortalLayer() != 1) { warnOnce("recursion", …); return; }
    // CROSS-DIM EXCLUSION — mod-side, zero iris symbols, so the cross-dim path is
    // byte-identical BY CONSTRUCTION rather than by an iris symbol resolving.
    // mc.level is still the SOURCE level here: the swap happens inside
    // MyGameRenderer.switchAndRenderTheWorldFullPipeline, which runs after this call.
    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null || !portal.getDestDim().equals(mc.level.dimension())) return;
    armed = portal;
}

public static void disarmAndReport() {
    Portal p = armed; armed = null;
    Pending q = pending; pending = null;              // clear first: a throw cannot wedge it
    if (q != null) { restoreOrDisarm(q); }            // belt for a mid-chain throw
    if (p != null && !consumedThisWindow) {
        IPGlobal.irisDestPrevMissCount++;
        warnOnce("miss", …);                          // armed-but-never-consumed
    }
    consumedThisWindow = false;
    sweepIfLarge();
    maybeProbe();
}
```

### 4.3 S1 — `onPassProgramBound(CompositeRenderer renderer, int i)`

```
 1. if (armed == null) return;                     // THE byte-inert gate. One static read.
 2. if (broken) return;
 3. ensureReflection();                            // latched once: CompositeRenderer.passes,
                                                   //   Pass.name, Pass.program   (3 fields only —
                                                   //   Program.getProgramId(), CameraUniforms and
                                                   //   CapturedRenderingState are all PUBLIC)
 4. pass = ((List<?>) fPasses.get(renderer)).get(i);
    name = (String) fPassName.get(pass);
    firstArmedChainRoster(name);                   // once-only INFO listing every pass name seen
    if (!TARGET_PASSES.contains(name)) { watchdog(); return; }
 5. if (!PortalRendering.isRendering()) return;    // belt (PortalRendering.java:70)
 6. Program prog = (Program) fPassProgram.get(pass);
    if (prog == null) { warnOnce("noprog"); return; }
    int pid = prog.getProgramId();                 // public
 7. locs = locCacheFor(pid);                       // 1-entry cache keyed by pid; a pack reload /
                                                   //   resize mints a new id => re-resolve
       locCam      = glGetUniformLocation(pid, "cameraPosition")
       locPrevCam  = glGetUniformLocation(pid, "previousCameraPosition")
       locPrevMv   = glGetUniformLocation(pid, "gbufferPreviousModelView")
       locPrevProj = glGetUniformLocation(pid, "gbufferPreviousProjection")
 8. ALL-THREE GUARD (this is the MB-off byte-identity proof, see §5):
       if (locPrevCam < 0 || locPrevMv < 0 || locPrevProj < 0) { infoOnce("noloc"); return; }
       if (locCam < 0)                            { infoOnce("nocam");  return; }
 9. CURRENT TRIO — sourced MOD-SIDE, regime-independent:
       Vector3d destUnshifted = CameraUniforms.getUnshiftedCameraPosition();   // public static;
                       // == mc.gameRenderer.mainCamera().position(), and mainCamera() IS the dest
                       // camera here (MyGameRenderer ieGameRenderer.ip_setCamera(newCamera))
       Matrix4fc mvNow   = CapturedRenderingState.INSTANCE.getGbufferModelView();  // = destDrawViewMatrix
       Matrix4fc projNow = CapturedRenderingState.INSTANCE.getGbufferProjection();
10. REGIME DETECT + SHIFT DERIVE (two GL floats' worth of work):
       glGetUniformfv(pid, locCam, cam3);                 // what the shader will actually use
       d = cam3 - destUnshifted;
       if (|d| < 1.0)                               -> shift = (0,0,0);   regime = DE-GATED
       else if (|d.y| < 1.0 && d.x,d.z are within 1.0 of integer multiples of 30000.0)
                                                    -> shift = round(d/30000)*30000; regime = DE-GATED
       else                                         -> regime = GATED/AMBIGUOUS:
                                                       infoOnce("regime", d, cam3, destUnshifted);
                                                       return;            // WRITE NOTHING
11. SAVE (for the restore) — glGetUniformfv into savePrevCam[3], savePrevMv[16], savePrevProj[16]
12. rec = MAP.get(armed);
    boolean neutralize = rec == null || !rec.valid
                      || rec.programId != pid
                      || (RenderStates.frameIndex - rec.frameStamp) < 1
                      || (RenderStates.frameIndex - rec.frameStamp) > 4;
13. VALUES TO WRITE
       if (neutralize) { wCam = cam3;                       wMv = mvNow;      wProj = projNow;      }
       else            { wCam = rec.camUnshifted + shift;   wMv = rec.modelView; wProj = rec.projection; }
       // RE-BASE, not fallback: storing UNSHIFTED and adding the CURRENT shift is EXACT across a
       // tracker shift epoch. getShift (javap @0-34) fires on |pos|>30000 OR |pos-prev|>1000, so a
       // >=1000-block same-dim portal trips it every frame — this is not hypothetical, and it costs
       // nothing here because previousCameraPosition and currentCameraPosition live in the SAME
       // shifted frame (applyShift @0-49 adds to both).
14. WRITE (only if restoreSeamProven; else this is the PROBE FRAME — see §4.5)
       glUniform3f(locPrevCam, wCam.x, wCam.y, wCam.z);
       if (!IPGlobal.IRIS_DEST_PREV_NO_MATRICES) {
           glUniformMatrix4fv(locPrevMv,   false, wMv16);    // column-major, transpose=false —
           glUniformMatrix4fv(locPrevProj, false, wProj16);  //   exactly MatrixUniform's call shape
       }
15. pending = new Pending(pid, locPrevCam, savePrevCam, locPrevMv, savePrevMv,
                          locPrevProj, savePrevProj, probeOnly);
16. STORE FOR NEXT FRAME (allocate rec if absent)
       rec.camUnshifted <- destUnshifted;  rec.modelView <- mvNow;  rec.projection <- projNow;
       rec.programId = pid;  rec.frameStamp = RenderStates.frameIndex;  rec.valid = true;
17. counters + once-only liveness INFO; consumedThisWindow = true;
```

Everything above is inside one `try { … } catch (Throwable t) { broken = true; restoreOrDisarm(pending); warnOnce("throw", …); }`.

**Why step 9 beats reading the current values back from GL** (the change from both designs): `CapturedRenderingState.INSTANCE` is written by `iris$setupPipeline` at the head of the nested `destRenderer.render(...)` **regardless of the uniform-update gate**, and `mainCamera()` is the dest camera for the whole bracket. So the stored "current" is dest-valued under **both** regimes, with no dependence on the unidentified de-gater. Step 10's GL read is used only to derive the shift and to *detect* the regime — never as the value source.

### 4.4 S4 — `onPassDrawn()`

```
1. if (pending == null) return;                    // byte-inert gate
2. Pending p = pending; pending = null;            // CLEAR FIRST — a throw cannot wedge the slot
3. if (p.probeOnly) { restoreSeamProven = true; infoOnce("proven", …); return; }   // nothing was written
4. glUniform3f(p.locPrevCam, p.savePrevCam...);
   if (matrices) { glUniformMatrix4fv(p.locPrevMv, false, p.savePrevMv);
                   glUniformMatrix4fv(p.locPrevProj, false, p.savePrevProj); }
5. catch (Throwable) -> broken = true; warnOnce("throw"); permanent disarm
```

### 4.5 The write-enable latch (adopted from Design A — it is the strongest safety idea in either)

`restoreSeamProven` starts `false`. The **first** armed guarded pass of the session executes steps 1-13 and 16 but **writes nothing** (`probeOnly = true`). If `onPassDrawn()` consumes it, `restoreSeamProven = true` and writes begin on the next dest composite. If `disarmAndReport()` finds an unconsumed `probeOnly` pending, it emits once-only WARN `norestore` and **permanently disarms — with the guarantee that zero writes were ever made, so the main view is provably untouched.** Cost: one frame of latency at session start, which the first-frame neutralize already spends anyway.

### 4.6 Logging (prefix `[Seamless Portals] IS5-MB `)

**Once-only INFO on the first successful write:**
```
IS5-MB per-dest previous-camera ACTIVE (once-only liveness line): portal={} pass={} prog={}
  regime={DE-GATED|…} shift=({},{},{}) prevCam=({},{},{}) cur=({},{},{}) |d|={} matrices={on|off}
```
**Once-only INFO:** `restore seam PROVEN — writes enabled from the next dest composite`
**Once-only INFO (first armed chain):** `composite pass roster seen in an armed dest chain: [composite, composite1, …]` — kills every name-drift risk without name-pinning being fragile.

**Once-only WARN/INFO — every silent-skip and throw path has its own latch key:**

| key | condition | consequence stated in the line |
|---|---|---|
| `noreflect` | iris symbols unresolvable | permanent disarm; "the same-dim window smear is NOT fixed on this run" |
| `nopass` | 30 s watchdog: armed chains seen, no guarded pass name | "this run CANNOT apply the fix — do NOT read the silence as fixed" |
| `noloc` | any of the three previous locs < 0 | inert; "this is the expected state with Motion Blur OFF" |
| `nocam` | `cameraPosition` loc < 0 | inert; shift/regime undeterminable |
| `regime` | detector says gated/ambiguous, with the numbers | **no write**; names the unidentified de-gater's absence |
| `neutralize` | first neutralize, naming `first-frame`/`prog-change`/`stale-frame` | one blur-free frame |
| `recursion` | portal layer != 1 | declines; the key would be wrong under recursion |
| `norestore` | probe pending unconsumed | permanent disarm; **zero writes ever made** |
| `miss` | armed-but-never-consumed window | miss-counter; behaviour = pre-fix |
| `throw` | any `Throwable` | restore if pending, permanent disarm |

**≤1 Hz probe (default OFF)**, `lastNanos` gate exactly as `MbGateProbe.java:116-129`, render thread only:
`IS5-MB writes={} neutralize={} misses={} regime={} lastDestDelta={} lastMainDelta={} matrices={on|off}`

**`glGetError` is never called** (binding requirement; also `CHelper.checkGlError` depends on the queue).

### 4.7 Coexistence with the C3-BLOOM mixin on the same method — checked, not assumed

Three `@Inject`s on `renderAll` at three distinct injection points (213, 419+, 458) is a normal Mixin configuration. Per-iteration order: **213 mask → 310 setupState → 419 use → 422 our WRITE → 431 CustomUniforms.push → 455 draw → 458 our RESTORE → 461 popGroup.**

- The mask cannot see our write: it fires strictly before `use()`.
- `CustomUniforms.push` at 431 cannot clobber us: verified the pack declares no custom named `previousCameraPosition`/`gbufferPrevious*` (`shaders.properties` customs are all `uniform.float.*`, `:240-276`; the only reads are `variable.float.difX/Y/Z` at `:251-253`, computed Java-side from iris's own values).
- **The one real interaction**, checked: `IrisBloomApertureMask.runMask` ends with `GL11.glGetError()` at `:707` and **permanently disarms C3-BLOOM on a nonzero value**. If our pass index precedes `plan.maskIndex` in the same chain, an error we left would be misattributed to the mask. Our four entry points cannot raise one on the healthy path — the program is linked and current, every location is validated `>= 0` before use, and the types match the GLSL declarations (`vec3`/`mat4`). The `noloc`/`nocam` guards mean `glUniform*` is never called with `-1`. This is an explicit acceptance item for the live run, not an assumption: if C3-BLOOM's `glerr` line ever appears in a build where it did not before, IS5-MB is the first suspect and the master lever isolates it in one A/B.

---

## 5. BEHAVIOUR-NEUTRALITY, PATH BY PATH

| path | mechanism | guarantee |
|---|---|---|
| **Lever OFF** (`-Dseamlessportals.disableIrisDestPrevCamera`) | `arm()` returns before assigning `armed`; both handlers' **first statement** is `if (armed == null) return;` | **Byte-identical.** Zero GL, zero reflection, zero iris symbol touched. Cost = 2 static null reads per pass iteration, ~20-30/frame across the 4 stage instances — the figure C3-BLOOM measured for 1 hook (`IrisBloomApertureMask.java:312-320`, "byte-inert"). |
| **Motion Blur OFF in the pack** | **Proved from the pack source, not from luck.** `previousCameraPosition`, `gbufferPreviousModelView`, `gbufferPreviousProjection` appear in `composite4.glsl` at lines **120, 123, 124 only**, all inside `#if MOTION_BLUR_EFFECT == 1` (`lib/common.glsl:146`, default `-1` = off). `composite4` includes only `lib/common.glsl`, `lib/util/dither.glsl`, `lib/atmospherics/fog/bloomFog.glsl`; grep of `lib/` shows the two matrices are used **only** in `taa.glsl`, `reprojection.glsl` and `composite4.glsl` — none of which is in composite4's include closure other than composite4 itself. ⇒ with MB off both matrices are dead-code-eliminated ⇒ `loc < 0` ⇒ the **all-three guard** fails ⇒ `return` before any write. | **Byte-identical, and the guarantee is checkable** — the `noloc` line fires and names MB-off as the expected cause. |
| **Cross-dim window** | `arm()` returns on `!portal.getDestDim().equals(mc.level.dimension())` — a mod-side comparison evaluated **before any GL call and before any iris symbol resolves** | **Byte-identical by construction.** Cross-dim is already correct (fresh per-dimension `CameraPositionTracker`, `addCameraUniforms` @0-8; the IS5-ACT A/B measured `|posOffset|inf ≤ 2` on 89/89 dest samples with the heal disabled) — writing there would *introduce* a defect. |
| **Main view** | Triple-guarded: (1) `armed` is non-null only between the arm at `:436` and the disarm in the `finally` — the main composite chain runs inside `LevelRenderer.render` at the *outer* `GameRenderer.renderLevel`, strictly before the post-level anchor; (2) `PortalRendering.isRendering()` belt at S1 step 5; (3) **W-restore** means even a hypothetical stray write is undone before the next pass, and (4) the §4.5 latch means **zero writes exist at all** until S4 is proven live. | **Byte-identical.** |
| **No portal on screen** | bracket never fires | **Byte-identical**, cost = the 2 null reads. |
| **Iris absent (the 8-leg suite)** | `@Pseudo` + `require = 0` + `IPCompatMixinPlugin` gate-1 (`isIrisPresent()`) ⇒ both mixins cleanly declined; `IrisDestPrevCamera` lives in the iris-only package and is referenced only from `IrisCompatOn262Renderer`, which is itself iris-gated | **Unwoven, zero bytes.** Expect 8/8 green and zero `IS5-MB` lines. |
| **Regime turns out to be GATED** (de-gater removed by a future iris) | step 10 detector returns without writing + once-only `regime` INFO | **Byte-identical to today**, and the log says exactly why. |
| **Shaders off / no pack / plain** | the compat renderer is not selected at all | untouched. |

**The one honest exception to "byte-identical", stated plainly:** with Motion Blur **ON**, the dest window's `composite4` output *changes* — that is the fix. Nothing else in any pass, in any other chain, on the main view, or on cross-dim windows changes. The strict-letter clause "byte-identical when Motion Blur is off" **is** satisfied here, because pass scope is `composite4` and the pack's own preprocessor removes the uniforms with MB off. Design B's claim that this clause is unachievable is true **only under its rejected B2 (all-passes) scope**; under the adjudicated single-pass scope it is achievable *and proved*.

---

## 6. VERIFY PLAN

### 6.0 Protocol note — **`MbGateProbe` cannot judge this fix, and must not be read as if it could**

`MbGateProbe` reads composite4's program storage at offset **213**, one chain stale, and we **restore** at 458. Therefore, after the fix, with the feature ACTIVE and writing:

- role=`MAIN` row: **still `cam=(0.440,19.620,2.615)` dest, `prev=(112.940,67.620,-26.885)` main, `|d|=125.82`** — UNCHANGED.
- role=`DEST` row: **still `cam=main, prev=main, |d|=0.0000`** — UNCHANGED.

**Unchanged MbGateProbe numbers are the CORRECT post-fix reading and are positive evidence that the restore is working.** If those numbers ever change, the restore is broken. Judge the fix on the new feature's own probe and on the visual.

### 6.1 The number that matters, and its exact expected value

At S1, after the write, the probe prints `destDelta = |cameraPosition − previousCameraPosition|` (post-write) alongside the main pass's own delta.

**The exact accept criterion — a rigid-transform invariant, valid for *every* non-scaled portal (translation, rotation, mirror):** a portal transform is an isometry, so

> `|dest_N − dest_{N−1}| ≡ |main_N − main_{N−1}|`

for all camera motion. So:

| condition | `destDelta` expected | pre-fix `destDelta` |
|---|---|---|
| **stationary** | **< 0.005** (float noise; `Camera.position()` excludes view bob, which lives in the model-view) | **125.82** |
| **walking 4.317 b/s @ 60 fps** | **0.0720 ± 0.002**, and equal to `mainDelta` to float precision | 125.82 (unchanged by speed — the tell) |
| **walking 4.317 b/s @ 120 fps** | **0.0360 ± 0.002** | 125.82 |
| **sprinting 5.612 b/s @ 60 fps** | **0.0935 ± 0.002** | 125.82 |

**`destDelta == mainDelta` at every speed is the single crispest pass/fail in this plan.**

### 6.2 A/B legs

| # | leg | pass criterion |
|---|---|---|
| **1** | MB ON, **stationary**, translation same-dim portal, default build | window **SHARP with Motion Blur still ON**; `writes=` climbs; `regime=DE-GATED`; `lastDestDelta < 0.005`; main view visually unchanged |
| **2** | same, `-PdisableIrisDestPrevCamera=true` | saturated smear returns **identically** — attribution in both directions |
| **3** | **THE DISTINGUISHING LEG (correct blur vs neutralization).** Walk parallel to the portal plane at 4.317 b/s | window smear **tracks speed** and **matches the main-view smear across the window edge**; `lastDestDelta ≈ lastMainDelta ≈ 0.072`. *Under the rejected neutralize design the window would be sharp while walking* |
| **4** | **THE MODEL-VIEW LEG.** Translation portal, **stationary body, turn the mouse steadily** | window blurs rotationally, matching the main view. With `-PirisDestPrevCameraNoMatrices=true` the window is **sharp while the main view smears** — this is the leg that proves the matrix half is load-bearing *on the reported geometry*, which neither design tested |
| **5** | 90°-yaw same-dim portal, stationary, `-PirisDestPrevCameraNoMatrices=true` | **expected FAIL** (full-strength stationary smear) — confirms the §0 (c) prediction that the matrices carry the portal rotation |
| **6** | same, sub-lever unset | sharp |
| **7** | **two same-dim portals** on screen | both sharp; swapping which is nearer changes nothing; `writes=` = 2 per frame; each portal's record is independent (per-portal map + per-pass-execution pending) |
| **8** | cross-dim portal | `writes=0`, no `IS5-MB` line, picture identical to pre-fix |
| **9** | **MB OFF in the pack** | `writes=0` + exactly one `noloc` line naming MB-off; picture identical |
| **10** | ≥1000-block same-dim portal (trips `getShift` every frame) | window sharp; the log shows a non-zero `shift=` and **no** neutralize storm — the re-base path, not a fallback |
| **11** | pack reload (F3+R / shader options) + window resize | no crash, one `neutralize` line (`prog-change`), counters resume |
| **12** | **scaled portal** (`extraScaling != 1`) | residual smear that neither lever fixes ⇒ escalation trigger R2 fires (§7) |
| **13** | lever off / no portal / main view | `writes=0`, no `IS5-MB` lines at all |
| **14** | **8-leg gametest suite, iris ABSENT** | 8/8 green, both mixins declined, zero compat refs, zero `IS5-MB` lines |

### 6.3 Visual accept test (what the user should see)

Stand still in front of a same-dim portal with Motion Blur ON at full strength. **The window must be as sharp as the surrounding wall.** Then strafe: the window's smear must appear at the same intensity and in the same direction as the smear just outside the portal frame, with **no visible discontinuity at the aperture edge**. Then turn the mouse without moving: same test. That edge-continuity check is the whole point of choosing correct blur over neutralization, and it is what leg 3 and leg 4 instrument numerically.

---

## 7. HONEST LIMITS, AND THE NAMED ESCALATION TRIGGER FOR EACH

| # | limit | escalation trigger |
|---|---|---|
| **R1** | **The MAIN view's `gbufferPreviousModelView` is poisoned too and is NOT fixed.** `MatrixUniforms$Previous.get()` hands the main pass `MV_dest_{N−1}`. Invisible on translation portals (`MV_dest ≡ MV_main`), a **full-strength main-view smear on a rotated portal**. W-restore guarantees we do not worsen it; it cannot be fixed by a GL write (the poison is a private Java field) and fixing it necessarily changes main-view pixels on portal frames. | **Leg 5/6 while watching the MAIN view.** If the main view smears in *both* legs, R1 is confirmed live and a separate change (reflective `Previous.previous` repair, own default-OFF sub-lever, own neutrality contract) is warranted. |
| **R2** | **Scaled portals.** `gbufferProjection` (the *current* uniform) is main-valued at the dest because Step-7's `writeProjectionSlice` bypasses sodium's cache — a **pre-existing** defect, not introduced here. For `extraScaling != 1` the whole window reprojection is off in proportion to the scale. Our per-dest `gbufferPreviousProjection` cannot compensate for a wrong *current* projection. | **Leg 12.** A residual smear on a scaled portal that neither lever moves ⇒ the fix is to route the dest projection through sodium's `GameRenderer` cache (or set `CapturedRenderingState.setGbufferProjection` directly in the dest bracket), which is a *different* change with its own neutrality analysis. |
| **R3** | **`composite4` only.** `composite` (SSR reprojection, `program/composite.glsl:155-187`), `composite6` (TAA, `lib/antialiasing/taa.glsl:62-64`), `composite7`/`fxaa` (`fxaa.glsl:169`), `shadowcomp` (`:81`), `mainLighting.glsl:308` and `reflectionVoxelData.glsl:59` all keep the wrong `previousCameraPosition` in the dest chain — as do `previousCameraPositionInt/Fract` everywhere. **Unchanged from today, not made worse.** Widening would break the MB-off neutrality contract and collide with the shipped IS5-G history clear + IS5-PH heal. | A user report of dest-window **SSR/TAA** artefacts specifically. Widening is then a *scoped* decision with the neutrality contract renegotiated first. |
| **R4** | **The dest window still has no temporal accumulation.** `IrisTemporalTargetGuard.clearForDestPass()` zeroes the dest TAA history every frame, so correcting the dest reprojection lets it reproject correctly *into zeros*. No fight, no benefit, until per-dest history exists. Neither IS5-G nor IS5-PH should be touched in this change. | Only after R3 widens. |
| **R5** | **One neutralized (blur-free) frame** per portal on: first composite, re-entry after occlusion culling, and a shaderpack reload/resize. Imperceptible at ≥30 fps. | Never — this is the documented safe fallback. |
| **R6** | **One probe frame at session start** performs reads only (the restore-seam latch). | Never. |
| **R7** | **The de-gater is still unidentified.** The design is correct under both regimes and *reports which one it saw*, but the mechanism that makes the dest chain run `updateStage(perFrame)` despite an unchanged `SystemTimeUniforms.COUNTER` is not known. | A `regime=GATED` line in the log. That means the de-gater vanished, the fix goes inert (byte-identical), and the *original* defect changes shape — re-measure before designing anything. |
| **R8** | **The pack's `starter` custom uniform** (`shaders.properties:257`, driven by `variable.float.difX/Y/Z` at `:251-253`) is computed Java-side from iris's own tracker values, which we never touch, so it stays 0 during the dest chain (`difSum ≈ 125`). It is a session fade-in scalar, saturated after the first seconds. **Not repaired.** | None expected; it would take an S6-shaped supplier fix, which is rejected. |
| **R9** | **Recursion (`maxPortalLayer > 1`) is declined, not supported.** The per-`Portal` key is wrong under recursion (A-direct and A-through-B are different cameras). `arm()` refuses at layer != 1 with a once-only WARN. | Enabling multi-layer on this renderer ⇒ change the key to the ordered portal-UUID path; the record and both seams are unchanged. |
| **R10** | **`@Local(ordinal = 0) int i` at S1** carries the same hard-apply crash corner the shipped C3-BLOOM mixin documents (`MixinIrisCompositeRenderer_BloomApertureMask.java:43-51`), with the same escape hatch (replace with an `@Unique` cursor reset at HEAD). **S4 is immune** — it takes no `@Local`, which halves the exposure versus a naive two-`@Local` design. | A boot crash naming MixinExtras local resolution on a drifted iris. |
