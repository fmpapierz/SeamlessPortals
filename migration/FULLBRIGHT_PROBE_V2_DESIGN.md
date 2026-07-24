# FULLBRIGHT — PROBE v2 DESIGN + recon (2026-07-22, `fullbright-deferred-recon` wf_b31c00f2-e78)

Preserves the recon that followed the **probe v1 A/B** (which refuted H1/H2/H3 — see `FULLBRIGHT_HANDOFF.md`
+ memory `portal-shaders-lighting-wave`). Probe v1 proved the dest **gbuffers-terrain** uniforms are already
dest-correct; the user then characterized the fault as **GLOBALLY OVER-BRIGHT / washed out** (not missing-shadows).
So the cause is a **deferred/composite** brightness term, view-dependent.

## Recon headline (grounded, javap + pack-GLSL)
- **Complementary r5.8.1 has NO auto-exposure.** `TM_EXPOSURE` is a compile-time `#define` slider
  (`shaders.properties:67`; `composite5.glsl:40  color = TM_EXPOSURE * color;`) — NOT a uniform/buffer. The
  earlier "exposure leak" sub-hypothesis is chasing a nonexistent term. (Recon B's auto-exposure ping-pong is a
  correct mechanism aimed at the wrong pack.)
- On a **pure-translation same-dim portal** the dest shares the main view rotation (v1: `gbufferModelView`
  dest==main), so the toggling brightness **cannot be a pure in-shader view term** (washes main equally) and
  **cannot be a position uniform** like `eyeBrightness` (pan doesn't move the player). => **screen-space /
  persistent-buffer**. The three that exist in this pack:

| Candidate | GLSL (of record) | Mechanism |
|---|---|---|
| **BLOOM** | `program/composite5.glsl:113,142` | `bloom = texture2D(colortex3,…)` `color = mix(color,blur,bloomStrength)` — bloom tiles = whole-screen downsample of `colortex0`; main-frame sky/sun bleeds into the dest region, pan-toggling. |
| **VL accumulator** | `program/composite1.glsl:320,338,374` | `color += volumetricEffect.rgb;` with `vlFactor = texelFetch(colortex5, ivec2(viewWidth-1,viewHeight-1),0).a;` — a **persistent temporal scalar in `colortex5.a`** shared across passes. |
| **fog wash** | `deferred1.glsl:317 DoFog` → `lib/atmospherics/fog/mainFog.glsl:170,173,197` | `fog *= 0.2+0.8*sqrt2(eyeBrightnessM)`, `fog *= eyeBrightnessM`, `color = mix(color, fogColorM, fog)` — scaled by the main-player `eyeBrightnessM` (`uniforms.glsl:198`; `shaders.properties:260`). |

## CHEAP experiential discriminator FIRST (do before building v2)
All three have pack toggles. In Complementary's Shader Options, toggle each OFF, keep a same-dim portal in view,
pan through the correct↔overbright toggle, and note which one KILLS it:
- **Bloom** OFF (Post Processing / Bloom).
- **Volumetric Light / Volumetric Fog** OFF (Atmospherics / Lighting).
- **Fog** OFF (Atmospherics).
Whichever kills the toggle names the cause → skip straight to the fix. If none do → build probe v2.

## PROBE v2 (build only if the toggles are inconclusive) — `IrisCompositeBrightnessProbe`
Clone `IrisFullbrightProbe` (reflection-only, self-disarm, 1Hz, `PortalRendering.isRendering()` discriminator) +
a **third** `@Inject(require=0)` at `GlCommandEncoder.trySetup(...)Z` RETURN
(`MixinSodiumBrightProbe2_GlCommandEncoder`). Lever `-Dseamlessportals.brightProbe2` (default-OFF) +
`-PbrightProbe2` in both build.gradle blocks.

**Composite gate** (pack uses plain `uniform sampler2D colortexN`, so queryable): `isComposite =
_glGetUniformLocation(prog,"colortex0")>=0 || …"colortex3")>=0`. Terrain = `!isComposite &&
…"cameraPosition")>=0`. Fingerprint each program by which of `{cameraPosition, gbufferModelView, eyeBrightnessM,
colortex0, colortex3}` are present; key the main/dest compare on **equal fingerprint** (deferred1-main vs
deferred1-dest). **Frame-order: the nested dest composites bind FIRST** (inverse of v1 terrain order) — buffer a
main + a dest sample per fingerprint+COUNTER, emit when both filled; 1Hz-latch.

**Uniform leg (rule-out)** — reuse `readUniforms`, add `glGetUniformiv`: `eyeBrightnessM`(f),
`eyeBrightness`(ivec2), `skyColor`/`fogColor`(vec3), `ambientLight`(f), `gbufferModelView`(mat4),
`cameraPosition`(vec3). Assert `TM_EXPOSURE` loc==-1 (documents "exposure ruled out").

**Texture leg (decisive)** — resolve via reflection (javap-confirmed vs `iris-1.11.2+26.2-fabric.jar`):
`IrisRenderingPipeline.renderTargets:RenderTargets` (private final) → `RenderTargets.get(int):RenderTarget`,
`getCurrentWidth()/getCurrentHeight():int`; `RenderTarget.getMainTexture()/getAltTexture():int`. Read via a
**scratch read-FBO** (`glGenFramebuffers` once; `glFramebufferTexture2D` attach; `glReadBuffer`; check COMPLETE)
+ `glReadPixels(GL_RGBA, GL_FLOAT)` **GL_PACK_\* bracketed** (26.2 invariant #4 — clone
`ShadowEmptinessProbe.readbackShadowDepth:699-807`); **detach (attach 0) + restore + delete on disarm**; read
BOTH main+alt (BufferFlipper ping-pong). Targets: `colortex5.a` at `[w-1,h-1]` (VL `vlFactor`), `colortex0`
centered patch mean-lum (is dest HDR already over-bright pre-composite?), `colortex3` centered patch mean-lum
(bloom tiles).

**Decision table:** colortex3 dest HIGH + pan-swing → **bloom**; colortex5.a HIGH + pan-correlated → **VL**;
colortex0 already over-bright at deferred1 + eyeBrightnessM HIGH → **fog wash**; any uniform dest≠main →
that family; `TM_EXPOSURE` always -1 → exposure ruled out.

**Risk/fallback:** composites not caught at trySetup → self-log first-N dest `(prog,isComposite,fp)`; if none
bear colortex0 → hook `net.irisshaders.iris.pipeline.CompositeRenderer.renderAll` directly. Texture attach
fails → texture leg soft-skips, uniform leg still settles the uniform rows. `eyeBrightnessM` TINY dest≠main =
the `FrameUpdateNotifier.onNewFrame()` double-step decay artifact (Recon B §0), not the cause — only a LARGE
delta counts.

Full recon output: `tasks/w394qi0kt.output` (+ per-agent journal `subagents/workflows/wf_b31c00f2-e78/journal.jsonl`).
