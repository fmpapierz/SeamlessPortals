# §2f C3-BLOOM FIX — BRANCH A DESIGN: CAPTURE + TONEMAP REPLICA

**Status: DESIGN ONLY (no edits, no builds). Worktree HEAD `0d76139` (`.claude/worktrees/is5-shadow`).**
**Defect (user-confirmed via Bloom-OFF toggle): camera-dependent glow RING just inside the portal
aperture, dark environments, shaders-ON — the dest pass's own bloom bleeding across the crop
boundary.** Bright dest content just OUTSIDE the window rectangle deposits bloom energy (bilinear
low-res tiles, reach ±896px) onto pixels just INSIDE it; BLOOM_FOG amplifies ×~4 night (×~15 cave
only when CAVE_FOG=true — see §4.2).

Branch A shape: at the per-portal post-dest-render point, read colortex0's last-written side (the
bit-exact pre-bloom HDR scene, last writer composite3), convert it through a mod-owned replica of
Complementary's color tail (BLOOM_FOG divide + COLORGRADING + DoCompTonemap + LinearToRGB +
DoBSLColorSaturation — `composite5.glsl:31-104,161-237`), into an MC-owned RenderTarget the stamp
samples INSTEAD of mainRT. The bloom-add (`:198→:142`) is the ONLY step deliberately not replicated —
that omission IS the fix.

---

## 0. Ground truth this design stands on (all re-verified against HEAD 0d76139 / live jars this pass)

| # | Fact | Where verified |
|---|------|----------------|
| G1 | composite5 order: c0 read `:162` → BLOOM_FOG divide `:194` → bloom-add `:198→:142` (mix 0.12) → COLORGRADING `:201-207` → DoCompTonemap `:209` → BSL saturation `:233` → DRAWBUFFERS:3 | pack `shaders/program/composite5.glsl` (read in full) |
| G2 | colortex0 at the anchor = scene **×GetBloomFog** already baked: `composite1.glsl:323` `color *= GetBloomFog(lViewPos)` under BLOOM_FOG_COMPOSITE1 (WORLD_BLUR=0 default → COMPOSITE1 branch; `common.glsl:598-603`). The `:194` divide un-bakes it. **The divide is therefore NOT optional** (§4.2) | pack sources |
| G3 | `#if BLOOM_ENABLED == -1 → #undef BLOOM_FOG` (`common.glsl:585-587`); `#ifdef END → #undef BLOOM_FOG` (`:581-583`); NETHER keeps it with netherBloomAdd 3 (14 w/ BORDER_FOG) | pack sources |
| G4 | **The user's sidecar currently has `BLOOM_ENABLED=-1`** (the Bloom-OFF workaround, `fabric/runs/client-sodium/shaderpacks/ComplementaryReimagined_r5.8.1.zip.txt`) — the fix arms only when Bloom returns ON; live round step 0 re-enables it | sidecar read |
| G5 | iris `CompositeRenderer.Pass` fields: `name, program, drawBuffers, stageReadsFromAlt, framebuffer, viewWidth, viewHeight` (pkg-private, private static class); ctor: `flipped == stageWritesToMain` → `RenderTargets.createColorFramebuffer` attaches `stageWritesToMain.contains(b) ? main : alt` at attachment `i = indexOf(b in drawBuffers)` | decompile `bloomrecon/out/CompositeRenderer.java:137-198`, `RenderTargets.java:318-345` |
| G6 | `GlFramebuffer.getColorAttachment(int)` and `Program.getProgramId()` are **PUBLIC** — the written-side texture id and the composite5 program id are reachable without touching GL framebuffer state | javap iris 1.11.2+26.2 jar |
| G7 | `IrisRenderingPipeline` fields `deferredRenderer` / `compositeRenderer` (private final CompositeRenderer) — reflection, the IrisTemporalTargetGuard precedent | decompile `IrisRenderingPipeline.java:132-135` |
| G8 | Iris option values are PUBLIC API in memory: `Iris.getCurrentPack() → ShaderPack.getShaderPackOptions() → ShaderPackOptions.getOptionValues() → OptionValues.getStringValueOrDefault(name)` — **no sidecar file parsing anywhere in this design** (§5) | javap |
| G9 | MC 26.2 has **`GpuFormat.RG11B10_FLOAT`** — an MC-owned texture can be created in colortex0's exact sized format, so `glCopyImageSubData` iris→MC is same-view-class and bit-exact (kills the RGBA8-reinterpret-garbage trap) | javap merged jar |
| G10 | `BindGroupLayout.builder().withUniform(name, UniformType.UNIFORM_BUFFER).withSampler(name)` is public; `RenderPipeline.Builder.withBindGroupLayout` takes any instance → a custom `PortalTonemapParams` UBO + two samplers is plain public API | javap merged jar |
| G11 | Mod shader assets live at `common/src/main/resources/assets/seamlessportals/shaders/core/` (screenquad.vsh reusable as the conversion VS); pipeline registration idiom = the reflective `RenderPipelines.register` in `IrisCompatPaste.<clinit>` (:102-160) | worktree read |
| G12 | At the per-portal post-dest point, `mainRT.depth` = the DEST scene depth (the stamp's GEQUAL and the multi-portal occlusion ledger both already rely on it) — usable for per-pixel lViewPos; depth views are bindable as pass samplers (26.2 post-chain precedent) | IrisCompatOn262Renderer :400-424 javadoc |
| G13 | `Pass.name` carries the pass name (`source.getName()`, e.g. "composite5") → the shape guard can fingerprint by name+drawBuffers, not index | decompile :144 |
| G14 | Insertion surface at HEAD: `IrisCompatOn262Renderer.doRenderPortal` :282-350 (stamp call :322-328, sampleSource arg :324); workhorse :175-279; teardown :435-450. `IrisCompatPaste` :102-160 static, :177-206 drawStraightCopy (pass template), :216-307 stamp | worktree read |
| G15 | Uniform names in the composite5 program (declared via `lib/uniforms.glsl`): `isEyeInWater:27, worldTime:29, darknessFactor:34, maxBlindnessDarkness:36, far:40, sunAngle:48, cameraPosition:55, gbufferModelView:66, gbufferProjectionInverse:126, eyeBrightnessM:198, rainFactor:200` (eyeBrightnessM/rainFactor = iris **custom** uniforms, `shaders.properties:260,262` — smoothed; reading them back post-dest-render returns the exact smoothed values the dest composite used) | pack sources |
| G16 | `sunFactor` is CPU-replicable: SdotU = cos(ang)·cos(radians(sunPathRotation)); ang from the timeAngle block (`common.glsl:693-703`, driven by `sunAngle`/`worldTime` per SHADOW_QUALITY) and sunPathRotation from SUN_ANGLE/SHADER_STYLE options (`common.glsl:449-465`) | pack sources |
| G17 | No new iris @Mixin anywhere in this branch → the D9 zero-iris-mixin invariant holds; the IPCompatMixinPlugin "Iris"-substring footgun is NOT in play (it applies to mixins only) | design property |

Pass map (post-preprocessor, this pack): deferred1, composite, composite1, composite3(**last c0
writer**), composite4(bloom tiles→c3; c0MipmapEnabled — mip gen does not alter level 0),
composite5(bloom+tonemap→c3), composite6(TAA passthrough; TAA_MODE=0 for this user),
composite7(FXAA), final(→mainRT). colortex0 clear=true ⇒ excluded from FinalPassRenderer swap
passes ⇒ survives byte-identical to the anchor (seam recon, HIGH).

---

## 1. Architecture — one new class, one new shader, one new pipeline, three levers

**New class** `common/src/main/java/qouteall/imm_ptl/core/compat/iris_compatibility/IrisPreBloomReplica.java`
— all logic lives here. Consumed from exactly ONE call site (`doRenderPortal`). Shaders-OFF /
stencil / plain / flag-OFF / suite are byte-inert by construction: nothing outside
`IrisCompatOn262Renderer` (the shaders-ON-only renderer) references the class, so its `<clinit>`
never even runs in those envelopes.

**Per-portal dataflow** (all inside `doRenderPortal`, after the nested dest render, before the stamp):

```
renderPortalContent(portal)                      // nested full-pipeline dest render (existing)
  └ popPortalLayer (existing finally)
[NEW] stampSource = IrisPreBloomReplica.captureAndConvert(portal, mainRT)
  1. pipeline := Iris.getPipelineManager().getPipelineNullable()   // POST-dest ⇒ the DEST-dim pipeline
  2. entry := cache.get(pipeline identity)  (WeakHashMap; miss ⇒ fingerprint + parity walk + uniform-location scan)
  3. entry.armed? else return null                                  // fall back to today's stamp source
  4. glCopyImageSubData( c0WrittenSideTexId → intermediateRG11B10.color )   // the BRIDGE, bit-exact
  5. read ~12 uniforms from the pack's OWN composite5 program (glGetUniform*) + option floats (cached)
  6. build 256B std140 UBO (frame-transient ledger)
  7. RenderPass: fullscreen triangle, PORTAL_TONEMAP_REPLICA pipeline,
     SceneSampler=intermediate view, DepthSampler=mainRT.getDepthTextureView(),
     → replicaTarget (RGBA8 TextureTarget)                         // the tonemap-replica conversion
  8. return replicaTarget
[EXISTING] IrisCompatPaste.stampPortalArea(portal, stampSource != null ? stampSource : mainRT, …)
```

Per-portal is mandatory (each dest render overwrites colortex0) and automatic here: step 4-7 run
inside the per-portal bracket, after THIS portal's dest render, before THIS portal's stamp.
Cross-dim portals resolve the DEST dimension's pipeline at step 1 (the nested render left iris's
`PipelineManager` pointing at it; same-dim resolves the shared pipeline — both correct), and the
cache is keyed on pipeline identity, so per-dimension entries coexist and a pack/option reload
(which recreates pipelines) auto-invalidates. **That identity keying is the whole
"sidecar drift" answer — see §5.**

### 1.1 Why the bridge is copy-then-MC-pass (and not the two rejected shapes)

The stamp consumes `RenderTarget#getColorTextureView()` — an MC `GpuTextureView`. The conversion
INPUT is a raw iris GL id. Three candidate bridges:

1. **REJECTED — raw-GL fullscreen draw into the MC target's texture id**: touches program binding,
   FBO binding, viewport, active-texture, blend, VAO — six GlStateManager-cached states managed by
   hand (26.2 invariant #1 exposure on every one), plus an owned FBO id (the FrameBufferCache
   lesson). Widest possible hazard surface for zero fidelity gain.
2. **REJECTED — reflective `GlTexture`/`GlTextureView` wrap of the raw id**: both ctors are
   protected; a wrapper that must NEVER be closed (close() would delete iris's texture), format
   metadata guessing, and RenderPass usage-bit validation risk. Fragile against MC minor bumps.
3. **CHOSEN — `glCopyImageSubData` into an MC-owned `RG11B10_FLOAT` texture (G9), then a plain MC
   RenderPass**: the copy idiom is the proven IrisTemporalTargetGuard/ShadowMapSwapper family
   (binds nothing, view-class-identical ⇒ bit-exact); everything after it is vanilla-blessed
   pipeline/pass machinery identical in shape to `drawStraightCopy`. Cost: one extra 8.3MB GPU
   copy per portal per frame (§8) — the price of zero new GL-state hazard classes.

"The conversion pass IS the bridge": after step 7 the stamp needs no new capability at all — it
samples an MC target exactly as today, and `stampPortalArea`'s signature already takes the sample
source as a parameter (:216-222). **The stamp change is one argument at one call site.**

---

## 2. Exact insertion points (file:line at HEAD 0d76139)

| # | File | Line | Change |
|---|------|------|--------|
| 1 | `common/.../iris_compatibility/IrisPreBloomReplica.java` | NEW | the whole feature (§3 sketch) |
| 2 | `common/src/main/resources/assets/seamlessportals/shaders/core/portal_tonemap_replica.fsh` | NEW | the replica shader (§4 sketch); VS = existing `screenquad.vsh` (G11) |
| 3 | `common/.../render/IrisCompatPaste.java` | :146 (inside the existing static block, after PORTAL_STRAIGHT_COPY) | register `PORTAL_TONEMAP_REPLICA` pipeline + the custom `PortalTonemapParams` BindGroupLayout. **Its failure must NOT join `arePipelinesReady()`** (:167-169 stays two-pipeline) — a replica-pipeline failure only disarms the replica (null from captureAndConvert ⇒ today's stamp), never the whole compat pass |
| 4 | `common/.../render/IrisCompatPaste.java` | after :206 | new driver `drawTonemapReplica(RenderTarget intermediate, RenderTarget depthSrc, RenderTarget out, GpuBufferSlice params)` — clone of drawStraightCopy's pass shape (fixes 1-4 applied: explicit RenderArea, `Optional.empty()` depth state, sampler via pass NEAREST+clamp, blend-off + GlStateManager backstops) |
| 5 | `common/.../iris_compatibility/IrisCompatOn262Renderer.java` | :310 (between the pop-layer `finally` and the :314 depth-clamp bracket) | `RenderTarget replicaSource = isDebugMode ? null : IrisPreBloomReplica.captureAndConvert(portal, client.gameRenderer.mainRenderTarget());` |
| 6 | same | :324 (the stamp's sampleSource arg) | `replicaSource != null ? replicaSource : client.gameRenderer.mainRenderTarget()` |
| 7 | same | :435-450 `teardown()` | `IrisPreBloomReplica.teardown();` (targets + cache + latches) — rides the existing CLIENT_CLEANUP + onSwitchedAway wiring |
| 8 | `common/.../IPGlobal.java` | after :182 (the irisDestTaaClearCount field) | the lever/counter block (§6): `PRE_BLOOM_STAMP_DISABLED_LEVER` + `preBloomStamp=true` + `isPreBloomStampActive()` + `preBloomReplicaCount` |
| 9 | `common/.../IPGlobal.java` | near :366 (debugTintStamp) | `debugTintReplica` + `preBloomProbe` levers |
| 10 | `fabric/build.gradle` | runClient block (:121-122 area) AND runClientSodium block (:160-182 area) | `-P` rows: `disablePreBloomStamp`, `debugTintReplica`, `preBloomProbe` — BOTH blocks, house rule |

The debug-mode instance (:330-337 full-screen raw view) intentionally bypasses the replica (step 5
gates on `!isDebugMode`) — its diagnostic meaning is "what did the nested render produce", which
must stay raw.

---

## 3. IrisPreBloomReplica — real-Java sketch (house style; abridged where mechanical)

```java
@Environment(EnvType.CLIENT)
public final class IrisPreBloomReplica {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** GL gate: same family as the guard (copy-image + DSA queries). Absent -> permanently dormant. */
    private static final boolean GL_SUPPORTED =
        GL.getCapabilities().glCopyImageSubData != 0L && GL.getCapabilities().glCreateTextures != 0L;

    // Two-flag reflection latch (the IrisTemporalTargetGuard :98-101 precedent).
    private static boolean reflectionReady = false, reflectionAttempted = false;
    private static Field fDeferredRenderer, fCompositeRenderer;   // IrisRenderingPipeline (G7)
    private static Field fPasses;                                  // CompositeRenderer.passes
    private static Field fPassName, fPassProgram, fPassDrawBuffers, fPassFramebuffer; // Pass (G5)

    /** Per-pipeline resolution. Weak keys: destroyed pipelines (pack reload) evict themselves;
     *  cross-dim keeps one live entry per dimension pipeline. */
    private static final Map<Object, Entry> cache = new WeakHashMap<>();

    private static final class Entry {
        boolean armed;                 // fingerprint + walk both passed
        String disarmReason;           // once-only logged
        int c0TexId;                   // the last-written side of colortex0 (G5/G6 walk)
        int c0W, c0H;
        int composite5ProgramId;       // for glGetUniform reads (G6)
        int[] uniformLocs;             // glGetUniformLocation once per entry (G15 names)
        int dimMode;                   // 0 overworld / 1 nether / 2 end  (from the pack's own
                                       // per-dimension program: read via a probe uniform? NO —
                                       // derived from portal dest dim key, §4.4)
        float[] optionConsts;          // TM_*, T_*, BLOOM_STRENGTH, SUN_ANGLE-derived sunPathRotation,
                                       // NETHER_VIEW_LIMIT, flags: bloomFog/caveFog/borderFog/grading/…
    }

    private static final SecondaryFrameBufferStyle intermediate = …; // TextureTarget RG11B10_FLOAT, no-depth-not-needed
    private static final SecondaryFrameBuffer replicaOut = new SecondaryFrameBuffer(); // RGBA8+depth, proven ctor path

    /** @return the stamp sample source, or NULL for "use today's mainRT" (every failure path). Never throws. */
    public static RenderTarget captureAndConvert(Portal portal, RenderTarget mainRT) {
        if (!IPGlobal.isPreBloomStampActive() || !GL_SUPPORTED) return null;
        try {
            WorldRenderingPipeline plRaw = Iris.getPipelineManager().getPipelineNullable(); // NEVER the woven field
            if (!(plRaw instanceof IrisRenderingPipeline pipeline)) return null;
            Entry e = cache.computeIfAbsent(pipeline, p -> resolve((IrisRenderingPipeline) p, portal));
            if (!e.armed) return null;
            // Size guard: c0 must match mainRT (scale-1.0 target) — resize/disarm mismatch frames.
            if (e.c0W != mainRT.width || e.c0H != mainRT.height) { onceWarn("size-mismatch"); return null; }

            intermediate.prepare(e.c0W, e.c0H);                    // lazy + resize, SecondaryFrameBuffer semantics
            replicaOut.prepare(e.c0W, e.c0H);
            // THE BRIDGE (G9): bit-exact same-view-class copy, binds nothing.
            GL43C.glCopyImageSubData(e.c0TexId, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                ((GlTexture) intermediate.fb.getColorTexture()).glId(), GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                e.c0W, e.c0H, 1);

            GpuBufferSlice params = buildParamsUbo(e);             // §4.3 — glGetUniform reads + option consts
            IrisCompatPaste.drawTonemapReplica(intermediate.fb, mainRT, replicaOut.fb, params);
            IPGlobal.preBloomReplicaCount++;
            onceInfoActive();                                      // once-only liveness (counter-bump lesson)
            maybeProbe(e, mainRT);                                 // §7, lever-gated 1Hz
            return replicaOut.fb;
        } catch (Throwable t) {
            onceError("captureAndConvert failed; stamp falls back to the finished frame", t);
            return null;                                           // degrade-to-today, never crash
        }
    }
```

`resolve()` (per pipeline identity, runs once per pipeline lifetime):

```java
    private static Entry resolve(IrisRenderingPipeline pipeline, Portal portal) {
        Entry e = new Entry();
        if (!ensureReflection()) { e.disarm("reflection-miss"); return e; }
        // (a) OPTION FINGERPRINT (G8, public API): every replicated symbol must exist as an option
        //     or be a pack constant we re-verified: TM_EXPOSURE, TM_CONTRAST, TM_WHITE_PATH,
        //     TM_DARK_DESATURATION, T_SATURATION, T_VIBRANCE, BLOOM_ENABLED, BLOOM_STRENGTH.
        //     ANY absent -> disarm("pack-shape: option <X> missing").
        // (b) BLOOM_ENABLED == -1 -> disarm("bloom-off") — no bloom => no ring => today's path is correct (G3/G4).
        // (c) PARITY WALK (G5): passes of deferredRenderer then compositeRenderer in execution order;
        //     last Pass whose drawBuffers contains 0 wins; i = indexOf(0);
        //     e.c0TexId = pass.framebuffer.getColorAttachment(i)      // PUBLIC (G6) — the WRITTEN side,
        //     read straight off the FBO the pass draws into: parity derivation and confirmation collapse
        //     into one ground-truth query. None found -> disarm("pack-shape: no c0 writer").
        // (d) FORMAT BELT: glGetTextureLevelParameteri(e.c0TexId, 0, GL_TEXTURE_INTERNAL_FORMAT)
        //     must be GL_R11F_G11F_B10F -> else disarm("pack-shape: c0 format 0x%x").
        // (e) composite5 pass located BY NAME (G13) with drawBuffers containing 3 ->
        //     e.composite5ProgramId = pass.program.getProgramId(); glGetUniformLocation the G15 names
        //     (missing 'gbufferProjectionInverse'/'isEyeInWater' etc. -> disarm("pack-shape: uniform gone")).
        // (f) e.dimMode from the portal's DEST dimension key (§4.4); option consts snapshot (G16 sunPathRotation
        //     mapping, NETHER_VIEW_LIMIT, CAVE_FOG, BORDER_FOG, COLORGRADING + GR_* when on, sidecar-armed FXAA note).
        // Every disarm: once-only WARN with the reason + "portal windows keep today's finished-frame stamp".
        e.armed = true; return e;
    }
```

Notes bound to standing rules: all logging once-only latched (render-thread log4j stall rule);
counters plain static ints; `teardown()` destroys both targets, clears cache + latches;
`captureAndConvert` returns null on EVERY dark path (loud once, silent after) so the visible
degrade is exactly today's behavior; UBO + nothing else rides the frame-transient ledger (the
S14.30 discipline — the copy/draw are synchronous within the portal bracket).

---

## 4. The replica shader + the honest fidelity ledger

### 4.1 `portal_tonemap_replica.fsh` (sketch; every block cites its pack source line)

```glsl
#version 330
// §2f BRANCH A — mod-owned replica of Complementary r5.8.1 composite5's color tail,
// MINUS the bloom-add (the omission IS the fix). LICENSE: see the @PackMathReplica note +
// the UNRESOLVED flag in the design doc §9 — do not ship default-ON until the user settles it.

uniform sampler2D SceneSampler;   // RG11B10F intermediate == bit-exact colortex0 (pre-bloom, fog-BAKED — G2)
uniform sampler2D DepthSampler;   // mainRT depth == dest scene depth at this point (G12)

layout(std140) uniform PortalTonemapParams {
    mat4  ProjInverse;      // the pack's OWN gbufferProjectionInverse, read back from its program (G15)
    vec4  P0;  // x=sunFactor (CPU, G16)  y=eyeBrightnessM  z=rainFactor  w=isEyeInWater
    vec4  P1;  // x=cameraPosY  y=darknessFactor  z=maxBlindnessDarkness  w=renderDistanceFar
    vec4  P2;  // x=dimMode(0/1/2)  y=bloomFogOn  z=caveFogOn  w=borderFogOn
    vec4  TM0; // TM_EXPOSURE, TM_CONTRAST, TM_WHITE_PATH, TM_DARK_DESATURATION
    vec4  TM1; // T_SATURATION, T_VIBRANCE, BLOOM_STRENGTH, NETHER_VIEW_LIMIT
    vec4  DBG; // x=debugTintReplica  y=gradingOn  (z,w reserved)
    vec4  GR[9]; // COLORGRADING rows when gradingOn (composite5:201-207), else untouched
};
in vec2 texCoord; out vec4 fragColor;

float GetLuminance(vec3 c){ return dot(c, vec3(0.299,0.587,0.114)); }          // commonFunctions.glsl:23

float GetBloomFog(float lViewPos) {                                            // bloomFog.glsl, verbatim structure
    float bloomFog, bloomFogMult;
    if (P2.x < 0.5) {                        // OVERWORLD branch
        float k = 0.02 + 0.04 * float(P0.w == 1.0);
        bloomFog = 1.0 - exp(-lViewPos * k); bloomFog *= bloomFog; bloomFog *= bloomFog; // pow2(pow2())
        if (P0.w != 1.0) {
            bloomFogMult = (P0.z*P0.z * 8.0 + 3.0 * (1.0 - P0.x)) * P0.y;      // rainFactor2·rainBloomAdd + nightBloomAdd·(1-sunFactor), ·eyeBrightnessM
            if (P2.z > 0.5)                                                    // CAVE_FOG (sidecar)
                bloomFogMult += clamp(1.0 - P1.x / 61.9, 0.0, 1.0 - P0.y) * 14.0;  // caveFactor.glsl + caveBloomAdd
        } else bloomFogMult = 14.0;                                            // waterBloomAdd
    } else if (P2.x < 1.5) {                 // NETHER branch (bloomFog.glsl:29-36)
        float farM = clamp(min(P1.w, TM1.w), 96.0, 512.0);
        bloomFog = lViewPos / farM; bloomFog *= bloomFog * bloomFog;
        bloomFog = 1.0 - exp(-8.0 * bloomFog); bloomFog *= float(P0.w == 0.0);
        bloomFogMult = (P2.w > 0.5) ? 14.0 : 3.0;                              // netherBloomAdd per BORDER_FOG
    } else { bloomFog = 0.0; bloomFogMult = 0.0; }                             // END: BLOOM_FOG undef (G3)
    return 1.0 + bloomFog * bloomFogMult * (TM1.z * 8.33333);                  // ·BLOOM_STRENGTH·8.33333
}
void LinearToRGB(inout vec3 color) { /* composite5:31-34 verbatim */ }
void DoCompTonemap(inout vec3 color) { /* composite5:36-87 verbatim, TM_* from TM0 */ }
void DoBSLColorSaturation(inout vec3 color) { /* composite5:89-104 verbatim, T_* from TM1 */ }

void main() {
    vec3 color = texture(SceneSampler, texCoord).rgb;                          // composite5:162 (1:1 => texel-exact)
    if (P2.y > 0.5) {                                                          // BLOOM_FOG active
        float z0 = texture(DepthSampler, texCoord).r;                          // composite5:165 — same convention as
        vec4 viewPos = ProjInverse * (vec4(texCoord, z0, 1.0) * 2.0 - 1.0);    // the pack's own depthtex0/ProjInverse
        color /= GetBloomFog(length(viewPos.xyz / viewPos.w));                 // composite5:194 — MANDATORY (§4.2)
    }
    // composite5:197-199 DoBloom: DELIBERATELY OMITTED — the fix. No (1-strength) compensation (§4.2b).
    if (DBG.y > 0.5) { /* composite5:201-207 COLORGRADING via GR[] */ }
    DoCompTonemap(color);                                                      // composite5:209
    // :211-227 green-screen / select-outline==4: SKIPPED (needs colortex6 mask; cosmetic — §4.5)
    // :229-231 lens flare: SKIPPED (LENSFLARE_MODE default 0 — §4.5)
    DoBSLColorSaturation(color);                                               // composite5:233
    if (DBG.x > 0.5) color *= vec3(1.0, 0.0, 1.0);                             // debugTintReplica discriminator
    fragColor = vec4(color, 1.0);
}
```

### 4.2 The BLOOM_FOG divide — wrestled honestly

**(a) The divide is NOT skippable.** colortex0 as captured is `scene × GetBloomFog(lViewPos)` — the
multiply was baked upstream in composite1 (G2). Skipping `:194` leaves the window brighter than the
surroundings by exactly that per-pixel factor: night outdoors (eyeBrightnessM≈1, distance→far)
up to **×3.8**; underwater ×~15; caves ×~15 **only when CAVE_FOG=true** (this user's sidecar has
CAVE_FOG=false, which zeroes the cave term — the recon's "×14 cave" presumed CAVE_FOG on). It is
also per-pixel (distance-dependent), so no flat compensation exists — which is why the conversion
pass consumes the dest depth (G12) and the pack's own inverse projection. **Verdict: REPLICATE,
with the exact function structure, driven by the exact uniform values the pack's own composite5
program holds at that instant (G15) — not approximations.** The inputs the brief worried about
(lViewPos / sunVec / rainFactor2 / eyeBrightnessM / isEyeInWater / GetCaveFactor) are all covered:
lViewPos from DepthSampler+ProjInverse; sunFactor CPU-derived (G16, ~10 lines, exact); the rest are
literal uniform readbacks.

**(b) What we deliberately do NOT compensate.** composite5's bloom-add is `mix(color, blur, bs)`
(bs=0.12 + 0.2·darknessFactor). In flat regions blur≈color ⇒ the mix is identity ⇒ the replica
(which keeps `color`) already matches the pack there **exactly**; multiplying by (1-bs) would
darken every flat surface 12% — wrong. The true in-window deltas are confined to bloom features:
bright emitters lose their halo (the point of the fix) and read ≤ ~14% brighter at the emitter
texels (pack: 0.88·color+0.12·low-blur); deep-fog distant night scenery loses the additive fog
GLOW (the surroundings keep it) — **pre-registered residual R-1**: "distant night scenery inside
the window is darker/cleaner than the fog-glow outside it". That residual is the honest cost of
un-baking bloom from a window that bloom physically contaminated.

### 4.3 Uniform sourcing — the zero-drift trick

All scene-state inputs are read back from **the pack's own composite5 program object** after the
dest render (`glGetUniform{f,i}v(e.composite5ProgramId, loc)` — read-only GL, binds nothing):
`gbufferProjectionInverse` (mat4), `isEyeInWater`, `eyeBrightnessM`, `rainFactor`, `darknessFactor`,
`maxBlindnessDarkness`, `far`, `cameraPosition.y`, `sunAngle`, `worldTime`. Whatever iris uploaded
for the dest pass (dest camera, dest dimension, smoothed custom uniforms) is BY CONSTRUCTION what
the pack's composite5 would have used — including the iris-side smoothing of
eyeBrightnessM/rainFactor that a mod-side recomputation could never bit-match. Locations resolved
once per entry; ~12 `glGetUniform` calls per portal per frame (µs-scale).

### 4.4 Dimension branch

`dimMode` derives from the portal's destination dimension key (nether / end / else-overworld),
matching the pack's world-1/world1/world0 layout; the resolve happens on the DEST pipeline entry so
same-dim and cross-dim both get the branch their colortex0 was actually rendered with. Non-vanilla
custom dims fall to the overworld branch — same as Complementary's own dimension fallback; if the
pack's `dimension.properties` maps a custom dim elsewhere, worst case is a wrong fog branch inside
that window — bounded, and the probe (§7) exposes it.

### 4.5 The missing-tail ledger (everything after :194 that the window will not get)

| Pack step | Replica | In-window consequence | Disposition |
|---|---|---|---|
| DoBloom :198 | omitted | no halos on in-window emitters; R-1 fog-glow residual | **the fix**; pre-registered |
| composite6 TAA | bypassed | none for this user (TAA_MODE=0); with TAA on, dest history is already IS5-G-zeroed ⇒ composite6 ≈ passthrough | accepted, noted |
| composite7 FXAA | bypassed | in-window aliasing where surroundings are FXAA-smoothed — a real everywhere-delta, not just dark scenes | **companion pass recommended** (below) |
| final sharpen/aberration | bypassed | slightly softer window + few-px crop-edge effects | pre-registered acceptable (recon residual) |
| lens flare :229 | skipped | flare streaks vanish where they overlap the window (LENSFLARE default 0) | accepted, noted |
| green-screen/select-outline :211-227 | skipped | selection-outline highlight absent inside window (SELECT_OUTLINE default 1; only ==4 affected) | accepted, noted |

**FXAA companion (recommended, default-on with the fix, own sub-lever `disableReplicaFxaa`):** one
extra full-screen pass replica→replica applying standard FXAA 3.11 — implemented from NVIDIA's
public 3.11 reference (permissively licensed), NOT from the pack's copy, armed only when the pack's
own FXAA option is on. Without it the fix trades a dark-scene ring for permanent in-window jaggies
under this user's TAA-off configuration; with it the visible delta collapses to the sharpening/
bloom items. (+0.1-0.2ms/portal.)

---

## 5. "Sidecar" handling — resolved by not touching the file

The brief asked where the sidecar lives and how to survive mid-session edits. Answer: the sidecar
(`shaderpacks/<pack>.zip.txt`, G4) is **iris's** serialization; the live truth is iris's in-memory
`OptionValues`, reachable through PUBLIC API (G8) with per-option defaults resolved by
`getStringValueOrDefault`. This design never parses the file. Mid-session edits: every option
apply makes iris rebuild the ShaderPack + pipelines ⇒ new pipeline identity ⇒ our WeakHashMap
entry is gone ⇒ next portal frame re-resolves fingerprint + options + walk. There is no window in
which stale option constants can be used with a live pipeline, because the constants live on the
pipeline-identity entry itself. (Per-frame VALUES — brightness, rain, eye position — never come
from options at all; they are program-uniform readbacks, §4.3.)

---

## 6. Levers / counters / state (standing discipline)

| Item | Name | Default | Notes |
|---|---|---|---|
| master lever | `-Dseamlessportals.disablePreBloomStamp` / `IPGlobal.isPreBloomStampActive()` | fix ON (see §9 ship-gate) | composes NOTHING else off; null-return degrade |
| debug tint | `-Dseamlessportals.debugTintReplica` | off | magenta-multiplies replica output only — proves the stamp consumes the replica path |
| probe | `-Dseamlessportals.preBloomProbe` | off | §7 |
| gradle rows | `disablePreBloomStamp`, `debugTintReplica`, `preBloomProbe` | — | BOTH fabric/build.gradle run blocks (insertion #10) |
| counter | `IPGlobal.preBloomReplicaCount` | — | ++ per conversion; self-run harness readable |
| liveness | `[pre-bloom-stamp] ACTIVE …` once-only INFO on first conversion | — | counter-bump lesson |
| dark paths | every disarm/throw/reflection-miss/GL-gate: once-only WARN with reason | — | two-flag latch precedent |
| state | WeakHashMap<pipeline,Entry>; two lazy targets (RG11B10 intermediate, RGBA8+D32 out); latches | — | teardown via IrisCompatOn262Renderer.teardown (insertion #7) |
| envelopes | shaders-OFF/stencil/plain/flag-OFF/suite byte-inert (class never loaded outside compat renderer); renderMode=none master-off unchanged | — | §1 |

## 7. The flip-side runtime-confirm probe

Construction-time parity is already collapsed into ground truth by reading the written side
straight off the last writer's FBO attachment (G5/G6) — the derived-ALT/MOTION_BLUR-flips concern
cannot desync from reality because we never infer parity, we query the attachment. Two residual
doubts remain: (i) is that pass genuinely the last executed c0 writer (a later stage rewriting c0
would falsify it); (ii) is the replica actually plausible. The probe (lever-gated, 1Hz, off-thread
formatting, throwaway allocation-free):

1. `glGetTextureSubImage` one 2×2 patch from BOTH c0 sides + the same texels from the replica
   target and mainRT (post-final).
2. Log: chosen side id vs other id, c0 internal format, the 12 uniform readback values, and two
   scores: `replica-vs-mainRT` mean abs delta (should be SMALL in low-bloom scenes — the two differ
   only by bloom/FXAA/sharpen) and `otherSide-vs-mainRT` (should be LARGER/stale). WARN once if the
   chosen side scores worse than the other side across 10 consecutive samples — the "walk picked
   the wrong pass" alarm.
3. Also logs `sizeMismatch`, `dimMode`, and the entry's disarmReason histogram — one line covers
   the whole state machine for the live round.

## 8. Cost table (per portal per frame, 1920×1080; scales linearly with pixels)

| Cost | Amount | Notes |
|---|---|---|
| GPU copy (bridge) | 8.3MB copy ≈ 0.02-0.05ms | glCopyImageSubData, same-format |
| GPU conversion draw | ~2.07M frags × ~60-80 ALU ≈ 0.10-0.20ms | full-screen; scissor-to-aperture-bbox is a listed follow-up (§10) |
| GPU FXAA companion | ≈ 0.10-0.20ms | optional, recommended |
| CPU | ~12 glGetUniform + 1 UBO build (256B) + map lookup ≈ <0.05ms | locations cached per entry |
| VRAM (once, not per portal) | RG11B10 8.3MB + RGBA8 8.3MB + D32 8.3MB ≈ **25MB** (4K ≈ 100MB) | follow-up: drop the replica depth attachment if the 6-arg pass accepts a null depth view (needs a one-line probe at impl time) |
| Per-frame fixed | none beyond one map hit when no portals visible (captureAndConvert not called at all — it sits inside doRenderPortal) | |

## 9. ARR licensing posture — FLAGGED UNRESOLVED, USER DECISION REQUIRED

Honest position: `DoCompTonemap`'s base curve is the published Lottes 2016 algorithm (a fact/
algorithm, not protectable), and LinearToRGB is the textbook sRGB EOTF — but the composed tail we
replicate (darkLift/whitePath/desaturate modifications, BSL saturation, GetBloomFog's constants
and structure) is Complementary's (and upstream BSL's) creative expression under the Complementary
License 1.6 (read this pass: grants cover Normal Usage, modpack inclusion, and credited Modified
Pack redistribution — **none of which is "re-implement excerpts inside a third-party mod"**). The
mod's `@IPVanillaCopy` precedent does NOT transfer: that convention covers re-expressed MOJANG code
inside a Minecraft mod (community-normalized interop); a third-party ARR pack's math is a different
rights-holder and no interop doctrine obviously covers a functional REPLICA of its look. A
`@PackMathReplica` annotation is attribution, not a license.

Options for the user (ranked): (1) ask EminGT/Complementary Development for permission (their
license text shows active tolerance of derivative ecosystems; scope: ~70 lines, non-competing,
compat-only, credited); (2) ship the feature default-OFF (`preBloomStamp=false`) until resolved —
one line, everything else identical; (3) prefer Branch B (mask-before-bloom), which replicates ZERO
pack math and makes this entire section moot; (4) clean-room re-derivation from the Lottes paper
only — rejected, it forfeits the exactness that is this branch's whole value. **Ship-gate: do not
merge default-ON until the user picks.**

## 10. Pack portability — the honest story

**This branch is Complementary-r5.8.x-pinned by construction.** The guard (resolve() §3) arms only
when ALL hold: IrisRenderingPipeline + reflection OK; option fingerprint (8 named TM_*/T_*/BLOOM_*
options) present; BLOOM_ENABLED==1; a composite-chain c0 writer exists; c0 is R11F_G11F_B10F; a
pass named composite5 with drawBuffers∋3 exposes the G15 uniforms. Any other pack (BSL, SEUS,
Rethinking Voxels, vanilla-shader path, iris-off) fails the fingerprint ⇒ once-only WARN + today's
stamp source, zero visual delta, zero crash — degrade-to-today satisfied. What the guard CANNOT
do: distinguish a future Complementary r5.9 (or Euphoria Patches, which layers on Complementary
sources) that keeps every option name but CHANGES the tail math — that lands as silent in-window
color drift (§11). The fingerprint pins presence, not semantics; the design accepts this and says
so rather than pretending a hash of composite5's source (which iris does not expose post-
preprocessing in a stable form) buys real safety.

## 11. Failure-mode comparison — replica DRIFT vs today's ring

If the replica drifts from the pack (version bump, wrong dimension branch, one wrong uniform):
the window shows a **uniform, stable exposure/tint offset** — no spatial structure, no camera
correlation. Today's defect is a **localized, camera-tracked, motion-flickering bright ring** —
maximal visual salience, reads unambiguously as a glitch. A bounded off-tint reads as "the portal
shows a slightly different exposure", which portal windows already plausibly do (TAA-free content,
IS5-G). Verdict: drift is the LESS-BAD failure **as long as it stays bounded**; the two unbounded
drift modes (wrong BLOOM_FOG branch ⇒ ×4 night brightness error; stale/wrong-side c0 ⇒ garbage)
are exactly the ones the guard + attachment-query + probe are aimed at, and the master lever
returns any live regression to today's behavior in one flag.

## 12. LIVE-CONFIRM protocol (one session, dark-environment ring scenario)

0. **Precondition**: re-enable Bloom (sidecar currently BLOOM_ENABLED=-1, G4) via Shader Settings →
   Camera → Bloom ON. Iris reloads ⇒ new pipelines ⇒ fresh resolve.
1. **Baseline** `-PdisablePreBloomStamp=true`: night + dark interior dest, bright emitters (lava/
   glowstone) just OUTSIDE the window rect from the viewing angle; sweep the camera. EXPECT: the
   ring (defect reproduced on record).
2. **Fix run** (no lever): same scene. EXPECT: ring GONE while panning; `[pre-bloom-stamp] ACTIVE`
   once; `preBloomReplicaCount` climbing; zero new WARNs; GL census baseline.
3. **A/B re-flip** (lever back ON): ring returns ⇒ attribution both directions.
4. `-PdebugTintReplica=true`: window magenta ⇒ the stamp provably consumes the replica target.
5. **Parity checks** (fix run): flat dark scenery window-vs-direct brightness match (BLOOM_FOG
   divide correct); nether + end windows same check (dimension branches); underwater eye check.
6. **Pre-registered residuals** (do NOT count as failures): R-1 distant night fog-glow absent
   in-window; in-window emitter halos absent; softer/jaggier window if FXAA companion deferred;
   few-px crop-edge composite7/final effects; in-window select-outline/lens-flare absences.
7. **Probe row** `-PpreBloomProbe=true`: chosen-side stable across frames; replica-vs-mainRT delta
   small in low-bloom scenes; no wrong-side alarm.
8. **Mid-session option edit**: change TM_CONTRAST in-game, apply ⇒ window matches surroundings
   after reload (identity-keyed re-resolve proven live).
9. **Unknown-pack degrade**: switch to any non-Complementary pack ⇒ once-only "pack-shape" WARN,
   windows render exactly as today, no crash.
10. Suite `:fabric:runCrossingGametest` green (shaders-absent ⇒ byte-inert envelope).

## 13. Risk table

| Risk | Sev | Likelihood | Mitigation |
|---|---|---|---|
| Replica math drift on pack update / Euphoria (silent off-tint) | M | M over time | §10 honesty; probe; lever; version note in WARN (log pack name at arm) |
| Pack-shape guard false-ARM on a Complementary fork with changed tail | M | L-M | fingerprint + format + uniform checks; residual risk accepted + documented |
| Iris internals rename (Pass fields, renderer fields) on iris update | M (silent disarm, ring returns) | M on major bumps | two-flag latch + once-only WARN names the missing symbol; degrade-to-today |
| Depth-view-as-sampler rejected by 26.2 pass validation | M (feature dead) | L (post-chain precedent) | impl-time probe; fallback = disarm loudly (ring stays; no corruption) |
| Wrong c0 side (walk picks a non-final writer) | H if silent | L (attachment query = ground truth; c0 writers all in deferred/composite walk) | §7 probe alarm; garbage would also be caught by format belt |
| Stale program id after reload | L | L | pipeline-identity keying; WeakHashMap eviction; GL-error drain in readback path |
| Cross-dim pipelineNullable not the dest pipeline at the capture point | M (wrong options branch) | L (nested render's iris$setupPipeline is the last prepare) | probe logs dimMode + pipeline hash; disarm on mismatch heuristics if seen live |
| BLOOM_STRENGTH=10.0 joke value distorts fog divide | L | L | clamp mult ≥ 0; note |
| VRAM (25MB@1080p) | L | — | teardown on switch-away/cleanup; follow-ups: drop replica depth, scissor conversion |
| Licensing | ship-blocker until user decides | — | §9 ship-gate |

## 14. Top-3 self-identified weaknesses

1. **The fidelity ceiling is structural.** Everything after colortex0 is forfeited and must be
   either re-implemented (FXAA companion, maybe someday sharpening) or accepted as an
   everywhere-visible window delta — the fix converts a dark-scene defect into a small set of
   permanent all-scene deltas. Branch B keeps the frame pack-tonemapped end-to-end and pays none
   of this; if B's mask-edge semantics survive its own panel, B dominates on fidelity.
2. **A name-shaped guard protecting version-pinned math.** The arm condition proves the OPTION
   NAMES exist, not that composite5 still computes what r5.8.1 computed. A future pack update can
   silently turn exactness into drift with no log evidence unless the (lever-only) probe is
   running. I could not find a stable post-preprocessor source hash to pin against without
   reimplementing iris's include graph — acknowledged gap, not solved.
3. **Two coupling surfaces into other people's internals at once** — reflection into iris's
   private composite structures AND glGetUniform readback of a pack program's state. Both are
   read-only and latch-guarded, but each iris minor release is a fresh chance for a silent disarm
   (ring quietly returns; a user without the probe lever cannot tell why). The once-only WARN
   discipline is the only witness; there is no self-heal.

## 15. Follow-ups ledgered (not in scope)

Scissor the conversion + copy to the aperture screen bbox (cheap, per-portal RenderArea); drop the
replica target's depth attachment if the 6-arg createRenderPass tolerates it; sharpening replica;
SELECT_OUTLINE==4 mask support (needs a colortex6 side-walk); a Branch-B-style shared verdict on
which failure mode users prefer if both branches reach live rounds.
