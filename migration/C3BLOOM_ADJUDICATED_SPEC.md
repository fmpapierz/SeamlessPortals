# C3-BLOOM §2f — ADJUDICATED FINAL IMPLEMENTATION SPEC
## Verdict: DESIGN B (aperture-mask mutation at the bloom-capture seam), with 6 synthesis amendments. Design A REJECTED.

Adjudicator: independent re-verification pass over the worktree (HEAD `c3b4be3`, of which the brief's
`0d76139` is a verified ancestor — the only anchor-file drift is IPGlobal +29 / fabric/build.gradle +12,
the §2g despawn levers; every other cited anchor is byte-identical), the real iris
`1.11.2+26.2-fabric` jar (javap -c -l on the release classes), the decompiled iris sources
(`scratchpad/bloomrecon/out*`), the extracted Complementary r5.8.1 GLSL, and the MC 26.2 merged jar.
Every load-bearing Branch-B claim was re-derived from primary evidence before this verdict; the
verification ledger is §10. Line numbers below are at HEAD `c3b4be3` unless marked otherwise.

---

## 0. THE ADJUDICATION (why B, on the brief's axes)

| Axis | A (capture + tonemap replica) | B (aperture-mask mutation) | Winner |
|---|---|---|---|
| Visual correctness of the RESULT | Zero-drift on uniforms is clever, but the window loses ALL in-window bloom (emitter halos, the night fog-glow), FXAA, lens flare, and select-outline — A's own §4.4 delta table — and needs a NEW clean-room FXAA 3.11 companion pass just to avoid trading the ring for permanent in-window jaggies under this user's TAA-off sidecar. A new everywhere-visible edge-discontinuity class (bloom outside the window rectangle, none inside). | Frame stays 100% pack-tonemapped — zero replica drift BY CONSTRUCTION (the pack's own composite5 does all color math). In-window bloom is RETAINED, computed from window-visible content only: the semantically correct "window frame occludes" physics. Sole new look: in-window bloom slightly dimmer within tile-reach of the window edge. | **B, decisively** |
| Implementation surface + fragility | Übershader + custom UBO BindGroupLayout + `glGetUniform` readback off the pack's live composite5 program + CPU sunFactor re-derivation + 8-option pack fingerprint + in-memory OptionValues coupling + the FXAA companion — roughly 3-4× B's code, two read-only couplings into iris internals plus one into pack option state. | One `require=0` mixin at a bytecode-verified unique site + one reflection walk (two-flag-latched) + one ~20-line GL program + copy/clear/5 idempotent draws. | **B** |
| Licensing honesty | A carries an UNRESOLVED ARR ship-gate (its own §9: "do not ship default-ON until the user settles it") — a direct collision with the standing DEFAULT-ON discipline. A's own ranked option (3) reads: "prefer Branch B ... which replicates ZERO pack math and makes this entire section moot." | No pack math replicated. The mask GLSL is a trivial texelFetch passthrough. | **B — near-disqualifying for A alone** |
| Pack portability | Pinned to Complementary r5.8.1 option/uniform NAMES; guard pins names, not semantics — r5.9/Euphoria drift lands as silent off-tint (A's own weakness list). | Works for the whole "bloom gathered from colortex0 after c0's last write" family (Complementary/BSL lineage, Motion Blur off); DISARMS or masks inertly elsewhere with a proof that the result is never worse than today (§3.1 terminal-fate argument). | **B** |
| Per-frame cost | ~0.15-0.45 ms + ~25 MB VRAM @1080p, + the FXAA companion (+0.1-0.2 ms). | ~0.1-0.15 ms + 8.3 MB @1080p. | **B** |
| Testability | Replica drift needs numeric delta probes; failure mode = subtle stable off-tint. | Tint/blackout probes are visually decisive in ONE live session; failure mode = today's ring returns, WARN names the reason. | **B** |
| D9 (zero iris mixins) | Intact. | Broken (2nd in-tree iris mixin after ClipInject). | A — but priced: D9 was already amended at IS3 (`MixinIrisSodiumTransformPatcher_ClipInject`, require=1). Iris BINARY drift is already a loud boot crash via ClipInject on the same jar, so the new mixin's `require=0` graceful dormancy can never mask a version mismatch — it only covers same-version method-shape drift, and the armed-not-consumed WARN makes that dormancy non-silent. |

**Not a DEFER.** B is implementable as specced, every load-bearing symbol is bytecode-verified, its
failure envelope is degrade-to-today with attribution, and it carries no licensing gate. A is rejected
outright rather than synthesized-in: its capture/replica machinery buys nothing once the mask exists
(the mask makes the pack itself produce the correct window), and its FXAA/licensing appendages are
pure cost. One A-side artifact IS folded in: the live-protocol step-0 sidecar note (§7).

**Amendments vs Branch-B-as-submitted** (the deltas an implementer must not miss):
1. **MUTATE-LAST ordering** (§3.2): all throwing work (plan build, program ensure, mesh build, GpuBuffer
   upload, scratch ensure, copy) happens BEFORE the first destructive GL call (the clear). B-as-submitted
   allowed a thrown repaint to leave c0 black inside the window for one frame; under mutate-last the
   post-clear tail is raw non-throwing GL only, and the one-black-frame window closes to ~zero.
2. **Blend save/restore DROPPED** (§3.3): the very next pass's `setupState()` (Pass :510-519 decompile)
   unconditionally re-establishes blend (`blendModeOverride.apply()` or `restoreBlend()+_disableBlend(0)`),
   and nothing draws between the mask and that call. The mask just `_disableBlend(0)`s for its own draw.
   Cull query-save/restore KEPT — nothing downstream re-establishes cull.
3. **The `DRAWBUFFERS:30` heuristic INFO SHIPS** (was optional in B): at plan build, if the last-c0-writer
   pass also writes other buffers, once-only INFO "last colortex0 writer also writes other draw buffers
   (motion-blur shape) — if the bloom ring persists, disable the pack's Motion Blur". Converts B's top-1
   weakness (silent MB-ON regression-to-ring with a VALID plan) into an attributable log line.
4. **Draw call pinned**: `GlStateManager._drawArrays(GL_TRIANGLES, 0, drawState.vertexCount())`
   (`vertexCount` javap-confirmed on `MeshData$DrawState`; TRIANGLES topology ⇒ non-indexed draw is
   exactly equivalent to the stamp's sequential-identity `drawIndexed`).
5. **`require=0` justification corrected** (see D9 row above): it is safe BECAUSE ClipInject's
   `require=1` on the same jar already pins the iris version loudly.
6. **@Local hardening documented** (§2.A): LVT is PRESENT in the release jar; `i` slot 4 type `I`,
   `passesSize` slot 5, `ranCompute` slot 7 type `Z` (excluded from int-ordinal counting), `index`
   slot 9 out of scope at the injection offset — `@Local(ordinal = 0) int` resolves to `i` under both
   LVT and frame-analysis fallback. The @Unique-cursor variant stays the documented fallback only.

---

## 1. Design shape (one paragraph)

During each portal's nested dest render only — armed in `IrisCompatOn262Renderer.doRenderPortal`,
consumed inside iris's dest composite chain — after the chain's LAST colortex0 writer has drawn and
BEFORE the next pass regenerates c0's mipmaps and gathers its thresholdless bloom tiles, mutate the c0
texture that next pass will read: copy it to scratch (`glCopyImageSubData`), clear it to black
(`glClearTexImage`), and repaint ONLY the aperture footprint back from scratch — the same
`ViewAreaRenderer.buildPortalViewAreaMesh` geometry and the same layer-0 `P·MV` the stamp uses,
fragment = `texelFetch` at `gl_FragCoord` (idempotent per pixel), 5 NDC-offset draws dilating the
keep-region ~1.5 px so mask ⊇ stamp footprint unconditionally. composite4's ±896 px tiles then gather
ONLY window-visible energy; composite5 tonemaps normally; the frame stays 100% pack-authored; the stamp
is untouched. No capture, no color replication, no GpuTextureView bridge, no pack math.

## 2. Exact insertion points (HEAD `c3b4be3`)

**A. NEW mixin** — `common/src/main/java/qouteall/imm_ptl/core/compat/mixin/iris/MixinIrisCompositeRenderer_BloomApertureMask.java`
(simple name contains "Iris", not "IrisSodium"/"Sodium" ⇒ `IPCompatMixinPlugin.shouldApplyMixin`
:108-116 routes to `isIrisPresent()` alone — the footgun satisfied, and gate-2 `EntityPortalsFlag.isOn()`
composes). Registered in `common/src/main/resources/seamlessportals-ip-compat.mixins.json` after
line 24 (`"iris.MixinIrisSodiumTransformPatcher_ClipInject"`).

```java
@Pseudo
@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class MixinIrisCompositeRenderer_BloomApertureMask {
    // DELIBERATE require=0 override of the config's defaultRequire=1 (documented deviation from the
    // D7 loud-crash norm): iris BINARY drift is already a loud boot crash via ClipInject's require=1
    // on this same jar, so dormancy here cannot mask a version mismatch — it covers only same-version
    // method-shape drift, and IrisBloomApertureMask's armed-not-consumed once-only WARN names it.
    @Inject(
        method = "renderAll",
        at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/program/Program;unbind()V"),
        remap = false, require = 0
    )
    private void seamlessportals$maskBloomAperture(CallbackInfo ci, @Local(ordinal = 0) int i) {
        IrisBloomApertureMask.onCompositePassBoundary((CompositeRenderer) (Object) this, i);
    }
}
```

Injection-site facts (javap -c -l, release jar): `Program.unbind` invokestatic at bytecode offset 213
is the ONLY occurrence in `renderAll` (the tail cleanup uses `GlStateManager._glUseProgram(0)`, :331
decompile — NOT Program.unbind), so the un-ordinaled INVOKE match fires exactly once per pass
iteration. Loop is INDEXED (decompile :278-280: `int i = 0; for (int passesSize = ...)`), and at
offset 213 the in-scope `I`-typed locals are exactly `i` (slot 4, range covers 213) then `passesSize`
(slot 5). Iteration order per the decompile: computes → memoryBarrier → **Program.unbind() [:298 =
our hook]** → ComputeOnlyPass skip → mipmap regen (:302-310, reads the SAME alt/main side we mask —
`stageReadsFromAlt.contains(index)`) → `setupState()` (:312) → viewport/scissor (:317-319) →
`program.use()` → draw (:322-323). So a mask fired at iteration `maskIndex = L+1` lands after the
last-c0-writer's draw and before the bloom pass's own mip regeneration — **iris itself rebuilds the
mips from our masked lod0; no manual mip work.** MixinExtras `@Local` is in-tree precedent
(`@WrapOperation` in MobDespawnSuppressMixin).

**B. NEW helper** — `common/src/main/java/qouteall/imm_ptl/core/compat/iris_compatibility/IrisBloomApertureMask.java`
(beside `IrisTemporalTargetGuard`; direct iris imports are the package's precedent — the guard imports
`net.irisshaders.iris.*` at :6-12 and is loaded only from iris-gated paths).

**C. Arming** — `IrisCompatOn262Renderer.doRenderPortal`: insert at :291 (between the
`testShouldRenderPortal` early-return :288-290 and `PortalRendering.pushPortalLayer(portal)` :292 —
we are at LAYER 0, so `getCurrentProjectionMatrix()` is the same unscaled value the stamp reads
post-pop at :327; scaled portals included by construction):

```java
if (!isDebugMode && IPGlobal.isIrisBloomApertureMaskActive()) {
    // Same matrix/camera row the stamp uses (IrisCompatPaste:239-244, :271): the passing
    // modelView + the layer-0 draw projection + the current camera pos + partialTick.
    IrisBloomApertureMask.arm(
        portal, new Matrix4f(modelView), new Matrix4f(getCurrentProjectionMatrix()),
        CHelper.getCurrentCameraPos(), RenderStates.getPartialTick()
    );
}
```

(`isDebugMode` excluded: the debug instance's full-screen raw view must stay unmasked.)
**Disarm** — inside the existing `finally` at :307-309, after `PortalRendering.popPortalLayer()`:

```java
} finally {
    PortalRendering.popPortalLayer();
    // Once-only WARN + miss-counter if armed-but-never-consumed (mixin dormant after an iris
    // update, ineligible pack shape, plan disarm). Unconditional + throw-safe: the arm must
    // never outlive its portal window.
    IrisBloomApertureMask.disarmAndReport();
}
```

The renderer is one-layer-only (:283-286 guard) ⇒ a single static armed slot is sound; the nested
render's own `doRenderPortal` re-entry early-returns before the arm point. The stamp (:322-328) runs
after the finally and needs nothing from the armed state. D23 layer-0 fallback, GUI/cross-portal-view
routes, the stencil family, shaders-OFF: never armed ⇒ byte-inert (their composite invocations pay
one static null check).

**D. Teardown** — `IrisCompatOn262Renderer.teardown()` (:435-450), beside the guard call at :446:
`try { IrisBloomApertureMask.teardown(); } catch (Throwable t) { /* disposal is best-effort */ }`
(deletes the mask GL program, cached `GlFramebuffer`s, scratch texture; covered by `onSwitchedAway()`
:453-456 automatically).

**E. Levers/counters** — `IPGlobal`, appended after the IS5 lever family (house pattern :146-158):

```java
// C3-BLOOM APERTURE MASK (2026-07-25) — the §2f dark-environment bloom RING fix (user toggle-proven:
// Bloom OFF => ring GONE). The dest pass's own thresholdless bloom tiles (reach ±896px, BLOOM_FOG
// ×3 night/×14 cave) deposit energy from bright dest content just OUTSIDE the window rectangle onto
// pixels just INSIDE it. Fix = IrisBloomApertureMask: during the nested dest composite chain only,
// mask colortex0 to the aperture footprint AFTER its last writer and BEFORE the bloom-tile gather,
// so bloom is computed from window-visible content only; the frame stays pack-tonemapped; the stamp
// is untouched. DEFAULT TRUE; A/B OFF via -Dseamlessportals.disableIrisBloomApertureMask.
public static final boolean IRIS_BLOOM_MASK_DISABLED_LEVER =
    Boolean.getBoolean("seamlessportals.disableIrisBloomApertureMask");
public static boolean irisBloomApertureMask = true;

/** True when the per-portal dest composite chain should aperture-mask colortex0 before the bloom
 *  gather (the §2f ring fix). Default-on; the JVM lever forces it OFF for A/B comparison. */
public static boolean isIrisBloomApertureMaskActive() {
    return irisBloomApertureMask && !IRIS_BLOOM_MASK_DISABLED_LEVER;
}

/** Confirm-counter: incremented once per successful mask (per portal per frame). Render-thread int. */
public static int irisBloomMaskCount = 0;
/** Miss-counter: armed-but-never-consumed portal windows (dormant mixin / ineligible pack / disarm). */
public static int irisBloomMaskMissCount = 0;

/** §2f probes: magenta-tint the aperture repaint (flip-side runtime confirm — a tinted WINDOW proves
 *  the masked texture is the one the chain consumes) / skip the repaint (footprint + crop proof). */
public static final boolean debugTintBloomMask = Boolean.getBoolean("seamlessportals.debugTintBloomMask");
public static final boolean debugBloomMaskBlackout = Boolean.getBoolean("seamlessportals.debugBloomMaskBlackout");
```

**F. Gradle rows** — `fabric/build.gradle`, BOTH blocks (`runClientSodium`: after the
`disableIrisDestTaaClear` row at :244-245; `runCrossingGametest`: after its mirror at :370), house
comment style included:

```groovy
//   .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PdisableIrisBloomApertureMask=true
if (project.findProperty('disableIrisBloomApertureMask') == 'true') { vmArg('-Dseamlessportals.disableIrisBloomApertureMask=true') }
//   .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PdebugTintBloomMask=true
if (project.findProperty('debugTintBloomMask') == 'true') { vmArg('-Dseamlessportals.debugTintBloomMask=true') }
//   .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PdebugBloomMaskBlackout=true
if (project.findProperty('debugBloomMaskBlackout') == 'true') { vmArg('-Dseamlessportals.debugBloomMaskBlackout=true') }
```

## 3. The mask pass

### 3.1 Why the seam is safe — the outside-aperture consumer walk (verified against the pack GLSL)

Masking c0 before composite4 also blacks what composite5 reads for outside-aperture pixels. Consumers
of the masked side (Complementary r5.8.1 post-preprocessor; file:line-verified):

| Consumer | Reads | Post-mask behavior outside the aperture | Verdict |
|---|---|---|---|
| composite4 bloom tiles (`texture2D(colortex0, ...)` :43, `colortex0MipmapEnabled=true` :18, auto-LOD 2-8) | masked c0 INCL. mips (iris regenerates them from the masked lod0 — §2.A ordering) | tiles = window-only energy | **the fix** |
| composite5 :162 scene read + :194 `color /= GetBloomFog(lViewPos)` | scene black outside; the fog divide is DEPTH-driven (depthtex0 untouched) | outside pixels → `tonemap(≈0)` ≈ black | discarded by the stamp |
| composite5 DoLensFlare | viewPos + depth occlusion, no c0 read | unchanged | clean |
| composite6 (TAA passthrough, this user TAA_MODE=0) | c3 only | writes black-derived c2 outside aperture — c2 is clear=false ⇒ already guard-saved/restored (IrisTemporalTargetGuard) and IS5-G-zeroed per portal | no new temporal leak |
| composite7 FXAA / final sharpen+aberration | c3 only | ≤1-2 px crop-edge taps see black — the pre-existing pre-registered residual class, unchanged in kind | accepted |
| FinalPassRenderer swap passes | c0 is clear=true ⇒ excluded (recon [C]) | nothing re-reads c0 after composite5 | clean |
| centerDepthSampler | depth only | unchanged | clean |

Terminal fate: outside-aperture dest pixels reach mainRT black via `final`; the stamp
(`IrisCompatPaste.stampPortalArea`) copies ONLY the aperture-mesh footprint from mainRT into the
deferred buffer and the blit-back restores the snapshot everywhere else; the next per-portal /
next-frame `beginLevelRendering` re-clears c0 (clear=true). **Zero escape paths ⇒ on ANY pack whose
plan resolves, the mutation is invisible outside the window; on packs where the bloom source is not
post-last-write c0 (MB-ON Complementary, compute-composite packs, bloom-in-final packs) the result
degrades to exactly today's ring — never worse.** Multi-portal: each subsequent nested render's
`beginLevelRendering` re-clears c0 before accumulating, so masks never stack. Pathological
flip-parity packs need no special-casing: we mask the texture the NEXT pass's sampler binds
(`IrisSamplers.addRenderTargetSamplers` :53-56 decompile: `flipped.contains(index) ? alt : main`
where `flipped` = that pass's own `stageReadsFromAlt` snapshot) — definitionally the consumed side;
parity is never inferred, MOTION_BLUR never hardcoded.

### 3.2 Plan (per CompositeRenderer instance; WeakHashMap-cached; reflection two-flag-latched with once-only WARN → disarm)

Reflect on `CompositeRenderer` (fields decompile-confirmed :83-94): `compositePass`
(require `== CompositePass.COMPOSITE` — BEGIN/PREPARE/DEFERRED instances get a permanent fast-path
no-op plan; `ShadowCompositeRenderer` is a separate class in `net.irisshaders.iris.pipeline`, never
matched by the mixin's target), `passes` (`ImmutableList<Pass>`), `renderTargets`. Walk
`CompositeRenderer$Pass` (a PRIVATE static nested class — `Class.forName("...CompositeRenderer$Pass")`
+ `getDeclaredField` + `setAccessible`; fields decompile-confirmed :483-494: `drawBuffers`, `name`,
`computes`, `stageReadsFromAlt`, `mipmappedBuffers`): **disarm+WARN** if any pass has a non-empty
non-null-element `computes` (invisible image-writes to c0 possible); find the LAST index `L` with
`drawBuffers ∋ 0`; disarm if none or if `L == passes.size()-1` ("no safe mask point — bloom likely in
final"); `maskIndex = L+1`; `readAlt = passes[maskIndex].stageReadsFromAlt.contains(0)`
(reflected per-plan). Amendment-3 heuristic: if pass `L` writes c0 PLUS other buffers, once-only INFO
(the motion-blur shape). Plan build also NUKES the mask-FBO cache (fresh pipeline = fresh texture ids).

### 3.3 `onCompositePassBoundary(renderer, i)` — cheapest-first, MUTATE-LAST

Order: `armed == null` → return (the ONLY cost on main-pass/BEGIN/PREPARE/DEFERRED invocations: one
static null check, ~8/frame); `armed.consumed` → return; plan (cached) disarmed or
`i != plan.maskIndex` → return; belt: `PortalRendering.isRendering()` must be true. Then, inside the
guard-style try/catch (any throw ⇒ once-only WARN + permanent disarm + missCount, never propagate):

**Phase 1 — fallible, non-destructive:**
1. `RenderTarget c0 = renderTargets.get(0)` (public, javap'd); null → disarm. `tex = plan.readAlt ?
   c0.getAltTexture() : c0.getMainTexture()`; `w/h` via getters (all public, javap'd). GL-query the
   sized format (`glGetTextureLevelParameteri(GL_TEXTURE_INTERNAL_FORMAT)` — the guard's idiom);
   integer format → disarm+WARN.
2. Capability gate: the guard's `COPY_SUPPORTED`-style check (glCopyImageSubData 4.3 + glCreateTextures
   4.5) plus `CLEAR_SUPPORTED` (glClearTexImage 4.4, guard :96 precedent); absent → disarm+WARN once.
3. Ensure the mask program (lazy GL20C compile, two-flag latch; fail → disarm+WARN). Ensure scratch
   (one slot keyed (w,h,fmt), DSA `glCreateTextures`+`glTextureStorage2D`, realloc on mismatch).
   Ensure the mask `GlFramebuffer` for `tex` (public ctor + `addColorAttachment(0, tex)` +
   `drawBuffers(new int[]{0})` — all javap'd; cache `Map<texId, GlFramebuffer>`, ≤2 live entries).
4. Mesh: `ViewAreaRenderer.buildPortalViewAreaMesh(tint, portal, armed.cameraPos, armed.partialTick,
   armed.modelView, byteBuffer)` — the stamp's exact route (IrisCompatPaste :239-244), TRIANGLES /
   POSITION_COLOR, S14.36 near-plane clip included; `tint` = WHITE, or MAGENTA under
   `IPGlobal.debugTintBloomMask`. `null` (all clipped — camera on the plane) → mark consumed, count
   as mask (the stamp skips identically), return.
5. Upload: `RenderSystem.getDevice().createBuffer(() -> "seamlessportals_bloommask_mesh",
   GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())`, registered on
   `SecondaryWorldRenderCore.registerFrameTransientUbo` (the stamp's ledger discipline, :255-260 —
   freed at the render tail, draws are synchronous).
6. `glCopyImageSubData(tex → scratch)` (same queried format ⇒ view-class-compatible; binds nothing).

**Phase 2 — destructive, raw non-throwing GL only:**
7. `GL44C.glClearTexImage(tex, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null)` (zeros; binds nothing;
   alpha-less formats take the RGBA client format fine).
8. Query-save `GL11.glIsEnabled(GL_CULL_FACE)` (read-only = cache-safe). Then, under iris's own
   in-scope idiom (`ImmediateState.temporarilyIgnorePass` is true here): `maskFbo.bind()` (iris
   `GlFramebuffer.bind()` routes through `GlStateManager._glBindFramebuffer` exactly as every
   composite pass does), `GlStateManager._viewport(0, 0, w, h)`, `_disableScissorTest()` (belt —
   the previous iteration's :319 already disabled it), `_disableBlend(0)` (amendment 2: no restore —
   the next pass's `setupState()` re-establishes blend unconditionally), `_disableCull()` (the
   aperture mesh is one-sided; the stamp pipeline is `.withCull(false)` :128 — parity),
   `_glUseProgram(maskProgram)`, `_activeTexture(GL_TEXTURE0)` + `_bindTexture(scratch)` (texelFetch
   ignores sampler objects; the next pass's `program.use()` → `ProgramSamplers.update()` re-binds all
   its units, and renderAll's tail resets bindings :333-338).
9. VAO through the SAME cache iris rides — literally `FullScreenQuadRenderer.bind()`'s disassembled
   pattern: `((GlDevice)((GpuDeviceAccessor) RenderSystem.getDevice()).getBackend()).vertexArrayCache()
   .bindVertexArray(new VertexFormat[]{DefaultVertexFormat.POSITION_COLOR},
   new GpuBufferSlice[]{meshBuf.slice()}, null)` (`GpuDeviceAccessor` is iris's own mixin interface;
   `GlDevice.vertexArrayCache()` public, javap'd). Upload `u_combined = new Matrix4f(armed.projection)
   .mul(armed.modelView)` (the stamp's :271 column-form) via `glUniformMatrix4fv(loc, false, buf)`.
   **5 draws** — `u_ndcOffset ∈ {(0,0), (±dx,±dy), (±dx,∓dy)}`, `dx = 3.0f/w, dy = 3.0f/h`
   (≈1.5 px dilation) — each `GlStateManager._drawArrays(GL_TRIANGLES, 0, drawState.vertexCount())`
   (amendment 4). Fragment is idempotent ⇒ overdraw harmless; dilation guarantees mask-keep ⊇ stamp
   footprint against cross-program rasterization non-invariance and the C4 +0.01 overhang.
   `debugBloomMaskBlackout` skips all 5 draws (clear-only leg).
10. Restore: `FullScreenQuadRenderer.INSTANCE.bind()` (MANDATORY — every subsequent composite draw
    rides that VAO; same cache in, same cache out ⇒ VertexArrayCache stays coherent),
    `_glUseProgram(0)` (matches the state the injected-before `Program.unbind()` establishes anyway —
    it runs right after we return, decompile :298, and is idempotent), cull back per the saved query
    (via `_enableCull()`/`_disableCull()` — cache-coherent). FBO/viewport/scissor/element-binding need
    NO restore: the mask pass's own iteration rebinds all of them before its draw (:312-322), the
    intervening mip regen is DSA (`IrisRenderSystem.generateMipmaps` — no binding dependency), and
    the element-array binding is VAO state — the re-bound quad VAO still holds the sequential buffer
    from iteration L, and :322 re-binds it per-pass regardless. ColorMask untouched (previous
    `setupState` left `_colorMask(-1)`). Depth: the mask FBO has no depth attachment ⇒ depth test is
    a spec-level no-op.
11. `armed.consumed = true`, `IPGlobal.irisBloomMaskCount++`, once-only ACTIVE INFO
    (`[C3-BLOOM] LIVE: pass=<name> idx=<M> reads=<ALT|MAIN> tex=<id> <w>x<h> fmt=0x<hex>`),
    guard-style one-shot glGetError drain WARN.

### 3.4 The mask GL program (compiled once via GL20C; raw strings in the helper)

```glsl
// vertex — #version 330 core
layout(location = 0) in vec3 Position;   // POSITION_COLOR; Color (loc 1) deliberately unread
uniform mat4 u_combined; uniform vec2 u_ndcOffset;
void main() { vec4 p = u_combined * vec4(Position, 1.0); p.xy += u_ndcOffset * p.w; gl_Position = p; }
// fragment — #version 330 core
uniform sampler2D u_saved; uniform vec4 u_tint;  // white; debugTintBloomMask -> (1,0,1,1)
out vec4 fragColor;
void main() { fragColor = vec4(texelFetch(u_saved, ivec2(gl_FragCoord.xy), 0).rgb, 1.0) * u_tint; }
```

(Note the mesh's aperture tint ALSO flips magenta under the lever — both the geometry route's color
and `u_tint` — but the fragment ignores vertex color, so `u_tint` is the operative channel-killer.)

## 4. State table (all new state)

| State | Scope / lifetime | Reset |
|---|---|---|
| `armed` {portal, modelView, projection, cameraPos, partialTick, consumed} | static, per-portal window (arm :291 → disarm in the :307-309 finally) | `disarmAndReport()` unconditional in the finally; never nested (one-layer guard :283-286) |
| plan cache `WeakHashMap<CompositeRenderer, MaskPlan{maskIndex, readAlt, passName, disarmReason}>` | per pipeline instance (cross-dim = per-dim instances, each own plan) | GC with the pipeline; any plan BUILD nukes the FBO cache |
| mask-FBO cache `Map<Integer texId, GlFramebuffer>` (≤2 live: main+alt) | GL | nuked on plan build + `teardown()` |
| scratch texture (1×, keyed (w,h,fmt)) | GL, persistent | realloc on mismatch; `teardown()` |
| mask GL program + 4 uniform locations | GL, lazy-once | `teardown()` |
| latches: reflectAttempted/Ready, compileAttempted/Ready, activeLogged, missWarned, glErrorWarned, mbShapeInfoLogged, disarmReason | session | once-only |
| counters `irisBloomMaskCount` / `irisBloomMaskMissCount` | session (IPGlobal, render-thread ints) | none |

Frame-transient: the mesh GpuBuffer (S14.30 ledger, freed at the render tail). The only iris-visible
mutation is the intended c0 content change, erased by the next `beginLevelRendering` clear.

## 5. Cost table (per portal per frame, mask firing; 1080p, c0 = R11F_G11F_B10F = 4 B/px)

| Item | Cost |
|---|---|
| GPU: c0 → scratch copy | 8.3 MB transfer, ~0.03-0.08 ms |
| GPU: glClearTexImage | ~0.02 ms |
| GPU: 5 dilated aperture repaints | aperture-area ×5 texelFetch fill (~2.5 Mpx at a 25% window), ~0.02-0.05 ms |
| **GPU total** | **~0.1-0.15 ms/portal** (≈15% of the guard's existing 10-copy save/restore class) |
| VRAM | one scratch: 8.3 MB @1080p; up to 33-66 MB @4K if a pack sizes c0 RGBA16F (stated honestly) |
| CPU | mesh build µs-class + ~25 GL calls; steady-state reflection ZERO (plan cached) |
| Inert envelopes (shaders-OFF / stencil / plain / flag-OFF / no-portal / lever-off / main pass / suite) | one static null check per composite-stage renderAll (~8/frame shaders-ON, 0 otherwise) — **byte-inert** |
| Bloom-disabled packs | full mask cost, no benefit (undetectable generically — accepted; lever exists) |

## 6. Lever / counter / probe wiring

- `-Dseamlessportals.disableIrisBloomApertureMask` + `-PdisableIrisBloomApertureMask` rows in BOTH
  gradle blocks — DEFAULT-ON fix, A/B both directions (§2.E/F).
- Once-only `[C3-BLOOM] LIVE` INFO with pass name/index/side/texid/dims/format = liveness + the
  flip-side runtime confirm's static half.
- **Flip-side runtime-confirm probe (the recon's demand), visual + decisive:** `-PdebugTintBloomMask`
  magenta-tints the repaint. composite5's scene read shares the mask pass's read side (no c0 writer
  between L and composite5 by construction of L) ⇒ a magenta-tinted WINDOW live-proves the masked
  texture is the one the chain consumes; wrong side ⇒ window normal + ring unchanged while the LIVE
  line still logs = discriminated in one look.
- `-PdebugBloomMaskBlackout` (clear, skip repaint) → window black, surroundings pristine = footprint +
  stamp-crop-discard chain proof in one look.
- `irisBloomMaskCount` (+1/portal/frame; 2 portals ⇒ +2/frame), `irisBloomMaskMissCount` + once-only
  WARN on armed-not-consumed with the disarm reason string. EVERY silent path (reflection miss,
  compile fail, integer format, compute passes, no mask point, missing GL capability, GL error,
  caught throw) carries a once-only WARN — the two-flag latch precedent throughout. Degrade = exactly
  today's ring; the clear only ever runs after a successful copy + upload (mutate-last §3.3), so
  "paint garbage" is structurally excluded.
- Amendment-3 once-only INFO on the `DRAWBUFFERS:30` motion-blur shape.

## 7. LIVE-CONFIRM protocol

Rig: `.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true`, staged Complementary r5.8.1,
TAA_MODE=0 sidecar, **Motion Blur OFF**, night + cave legs (BLOOM_FOG ×3/×14), wand same-dim portal,
bright dest emitters (glowstone/lava cluster) placed just OUTSIDE the window rectangle's screen
footprint, dark environment.

0. **Sidecar precondition (from the branch-A fold, kept):** the user's live sidecar currently carries
   `BLOOM_ENABLED=-1` (their workaround) — the defect itself is disarmed by it. RE-ENABLE Bloom
   before judging anything.
1. Fix leg: orbit the camera (the defect was camera-dependent) — **ring GONE**. Log: one
   `[C3-BLOOM] LIVE` line, `irisBloomMaskCount` climbing, zero WARNs, GL census at baseline (no new
   invalid-format lines beyond the known iris copyPreHandDepth class).
2. A/B: `-PdisableIrisBloomApertureMask=true` ⇒ **ring RETURNS** (attribution both directions).
3. `-PdebugTintBloomMask` ⇒ window magenta-tinted, surroundings clean (flip-side correctness).
   Optional: `-PdebugBloomMaskBlackout` ⇒ window black, surroundings pristine.
4. Regression legs: shaders-OFF stencil (zero mask lines, byte-identical); cross-dim portal (mask
   fires on the dest pipeline's own composite instance — counter++, no WARN); two-portal frame
   (+2/frame, no cross-talk — per-portal arm windows + per-portal c0 re-clear); shader toggle OFF→ON
   (pipeline recreate ⇒ new plan built once, no stale-FBO GL errors); `:fabric:runCrossingGametest`
   green.
5. **Pre-registered residuals (tell the user BEFORE judging):** (a) in-window bloom slightly dimmer
   within ~tile-reach of the window edge — window-frame-occludes physics, EXPECTED, not a defect;
   (b) the pre-existing ≤2 px FXAA/sharpen crop-edge class, unchanged in kind; (c) Motion Blur ON
   re-opens the ring (composite4 becomes the last c0 writer — the amendment-3 INFO names it; the
   shipped `imm_ptl.iris_warning` advice class covers "disable Motion Blur"); (d) bloom from content
   visible IN-window is retained — correct, not a leak.

## 8. Risk table + pack-portability (honest)

| Risk | Exposure | Containment |
|---|---|---|
| iris update reshapes `renderAll` / removes the single `Program.unbind` call / strips the indexed loop | mixin dorms (`require=0`) or `@Local` fails at apply | armed-not-consumed WARN + missCount; ring returns; NO crash. Version drift is already loud via ClipInject `require=1` on the same jar. Fallback documented: @Unique cursor (HEAD-inject reset + unbind-inject post-increment) |
| Reflection field renames (`passes`, `compositePass`, `renderTargets`, Pass fields) | plan build fails | two-flag latch → disarm + once-only WARN naming the symbol |
| Cross-program rasterization non-invariance vs the stamp | <1 px black in-window fringe | 1.5 px 5-draw dilation; discriminator = a 1 px dark rim ⇒ bump the constant (calibration, cheap live) |
| Raw draw perturbs iris's composite-chain GL state | portal-frames-only corruption | restore set derived from renderAll's own per-pass re-establishment (decompile :302-338) — the ONLY two states nothing downstream re-establishes are VAO (restored via `FullScreenQuadRenderer.INSTANCE.bind()` — the same VertexArrayCache in and out, bytecode-verified) and cull (query-save, GlStateManager-restore); blend/FBO/viewport/scissor/element-binding/samplers/colorMask all per-pass rebuilt; GL census leg mandatory |
| **Portability**: the fix addresses packs that gather bloom FROM c0 AFTER c0's last write (Complementary/BSL lineage, MB off). MB-ON Complementary, compute-composite packs, non-c0-bloom packs, bloom-in-final packs | ring persists there | plan disarms (WARN) or masks inertly (§3.1 terminal-fate proof: outside-aperture-only mutation + stamp crop + c0 clear=true) — **never worse than today**; the MB-shape INFO makes the one silent-valid-plan case attributable |
| Exotic c0 formats | integer → disarmed; float → GL-queried scratch/copy/clear | guard-precedent format discipline |
| One-frame garbage on a mid-mask throw | window black for 1 frame, then permanent disarm | mutate-last ordering (§3.3): post-clear tail is non-throwing raw GL; fallible work all precedes the first mutation |

## 9. Top-3 self-identified weaknesses (adjudicator's own, superseding B's list)

1. **The mask point is "after the last c0 writer", not "before the bloom gatherer"** — those coincide
   by pack convention (true for the Complementary/BSL family, MB off), not by construction. The
   gatherer is undetectable generically; Motion-Blur-ON reverts to the ring with a VALID plan. The
   amendment-3 INFO makes it attributable but not fixable. This is the design's honest ceiling — and
   the adjudication accepts it because the alternative (A) bought generality for nobody: it was
   pack-PINNED, strictly narrower.
2. **The dilation constant is calibration, not proof.** Mask ⊇ stamp rests on 1.5 px covering
   worst-case rasterization divergence between two vertex shaders (steep-angle scaled portal @4K =
   stress case). Cheap to bump live (a 1 px dark rim is the discriminator), but it is tuned, not
   derived.
3. **First raw-GL draw inside iris's composite loop.** The restore-set audit is thorough
   (§8 row 4 — every re-established state traced to a decompile line) but hand-derived; an iris
   point-release reordering its per-pass rebinds converts an omitted restore into portal-frame-only
   corruption. The tint/blackout probes + lever make that attributable in one session, not
   preventable.

## 10. Load-bearing claims — VERIFIED ledger for downstream verifiers

Each claim below was independently re-derived by the adjudicator from primary evidence (decompile =
`scratchpad/bloomrecon/out*`; javap = release jar `iris-1.11.2+26.2-fabric.jar`; pack =
`scratchpad/pack`; mod = worktree HEAD `c3b4be3`):

1. `renderAll` contains EXACTLY ONE `Program.unbind()` invokestatic (offset 213); the tail cleanup is
   `GlStateManager._glUseProgram(0)` — javap -c. The un-ordinaled INVOKE @At is therefore
   once-per-pass-iteration.
2. The pass loop is INDEXED; LVT PRESENT in the release jar: `i` slot 4 `I` (range covers 213),
   `passesSize` slot 5 `I`, `ranCompute` slot 7 `Z`, `index` slot 9 `I` scope-starts at 282 —
   `@Local(ordinal=0) int` = `i`, LVT or frame-analysis alike.
3. Iteration order: unbind(:298) → mipmap regen(:302-310) → setupState(:312) → draw(:322-323) —
   decompile; the mask at iteration L+1 therefore precedes the bloom pass's mip regeneration, and
   `setupMipmapping` regenerates from the SAME alt/main side the plan masks
   (`stageReadsFromAlt.contains(index)` both places).
4. `IrisSamplers.addRenderTargetSamplers`: sampler side = `flipped.contains(index) ? alt : main`
   where `flipped` = the pass's construction-snapshot `stageReadsFromAlt` — decompile :53-56 +
   `createProgram` :379.
5. `FullScreenQuadRenderer.bind()` binds via `GpuDeviceAccessor.getBackend() → GlDevice
   .vertexArrayCache().bindVertexArray(formats, slices, null)` — bytecode. The mask's VAO path is the
   IDENTICAL cache ⇒ coherence in and out.
6. `Program.unbind()` = clearActiveUniforms + clearActiveSamplers + `GlStateManager._glUseProgram(0)`
   — bytecode; idempotent after the mask's own `_glUseProgram(0)`.
7. Pack: composite3 `/* DRAWBUFFERS:0 */` = last c0 writer; composite4 reads c0
   (`texture2D(colortex0...)` :43) with `colortex0MipmapEnabled=true` (:18) and writes
   `/* DRAWBUFFERS:3 */`, or `30` iff `MOTION_BLUR_EFFECT == 1` (:180-186) — pack GLSL.
8. Mod anchors: one-layer guard :283-286; arm gap :291; finally :307-309; stamp :322-328 with
   `modelView` + layer-0 `getCurrentProjectionMatrix()`; stamp mesh route + camera row
   IrisCompatPaste :239-244; `P·MV` column-form :271; frame-transient ledger :255-260; stamp
   pipeline `.withCull(false)` :128; teardown :435-450 + `onSwitchedAway` :453-456.
9. Mixin plugin gate: `contains("IrisSodium")` before `contains("Iris")` before `contains("Sodium")`;
   the new simple name routes to `isIrisPresent()` alone; json package
   `qouteall.imm_ptl.core.compat.mixin`, `defaultRequire=1` (the mixin's `require=0` is a per-injector
   override) — IPCompatMixinPlugin :101-127 + json.
10. Iris API surface (javap, all public): `RenderTargets.get(int)`, `RenderTarget
    .getMainTexture/getAltTexture/getWidth/getHeight`, `GlFramebuffer()` +
    `addColorAttachment(int,int)` + `drawBuffers(int[])` + `bind()`, `CompositePass` enum
    {BEGIN, PREPARE, DEFERRED, COMPOSITE}. `CompositeRenderer.passes/compositePass/renderTargets`
    private-final (reflection); `CompositeRenderer$Pass` PRIVATE static nested (Class.forName +
    setAccessible required).
11. MC 26.2 surface (javap, merged jar): `GlStateManager._drawArrays(int,int,int)`,
    `_disableBlend(int)/_enableBlend(int)`, `_disableCull()/_enableCull()`, `_viewport`,
    `_disableScissorTest`, `_activeTexture`, `_bindTexture`, `_glUseProgram`,
    `_glBindFramebuffer`; `GlDevice.vertexArrayCache()` public;
    `VertexArrayCache.bindVertexArray(VertexFormat[], GpuBufferSlice[], VertexArray)`;
    `MeshData$DrawState.vertexCount/indexCount/primitiveTopology`.
12. Recon [C] facts relied on but NOT re-derived here (already the §2f seam-recon's verified core):
    c0 clear=true ⇒ excluded from FinalPassRenderer swaps + re-cleared at each beginLevelRendering;
    the stamp lands after iris finalizeLevelRendering; flip parity is construction-time-only.

**Files touched (5 + 1 new):** new `MixinIrisCompositeRenderer_BloomApertureMask.java`, new
`IrisBloomApertureMask.java`, `seamlessportals-ip-compat.mixins.json` (+1 line),
`IrisCompatOn262Renderer.java` (:291 arm, :308 disarm-in-finally, teardown), `IPGlobal.java`
(lever block §2.E), `fabric/build.gradle` (both blocks, 3 rows each).
