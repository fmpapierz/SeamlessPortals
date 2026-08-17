# IS5-DESTCTX — dest-context viewer-state for iris custom uniforms (DRAFT, PRE-PANEL)

## §0 THE USER CONTRACT (2026-08-17, verbatim + A/B screenshots)

**"ow side must match nether side exactly."** The window must equal the true in-dest render.

## §1 THE CONVICTION (2026-08-17 ~00:51, WASHPROBE v4 ctx leg — the decaying curve)

The dest chain's `inNetherWastes` (the pack's shaders.properties `smooth()` custom uniform
driving `netherColor` = the whole nether tint/darkening) decayed 0.55→0.01 over ~8s after
the player left the nether, while in-nether it climbs to 0.87+. dest `fogColor` stayed
correctly nether throughout — the custom smoothies are the defect, not the standard fog.
Explains: the uniform lift in the user's A/B pair, the crossing-comparator blindness
(smoothing bridges a crossing — both sides equal for the first ~1s), and the apparent
distance dependence (FARFADE masks the constant defect at range; near content is
unmaskable by design — F15 keeps near content sharp).

## §2 RECON FACTS (wf_06ca5a81-e91, verified against the extracted iris-1.11.2+mc26.2 at
C:/Users/warwa/ModDev/irisdump — decompile line numbers)

R1. **Per-pipeline smoothie state CONFIRMED (load-bearing)**: each IrisRenderingPipeline
    owns `private final CustomUniforms customUniforms;` built fresh in its ctor; dynamic
    `smooth()` functions mint a NEW `SmoothFloat` (fields `accumulator`/`hasInitialValue`)
    per call site per CustomUniforms instance (FunctionResolver.resolve maps
    Supplier::get). PipelineManager keeps `pipelinesPerDimension` — one pipeline per dim.
    NO cross-world seesaw from shared accumulators; a context swap makes each side's
    smoothies converge to ITS world's values with iris's own semantics.
R2. **The vanilla read**: BiomeUniforms.addBiomeUniforms registers biome/biome_category/
    rainfall/temperature etc. via `playerI/playerF` helpers whose lambdas read
    `Minecraft.getInstance().player` then `player.level().getBiome(player.blockPosition())`
    — the PLAYER, not the camera, not the rendered level. eyeBrightness-class reads live in
    CommonUniforms (player/camera fluid+light state).
R3. **The update moment**: `customUniforms.update()` is called EXACTLY ONCE per
    `IrisRenderingPipeline.beginLevelRendering()` (the only call site in the jar), which
    runs INSIDE our nested render window (iris's woven hooks inside destRenderer.render()).
    The CUSTOM path ignores frequency tags — every update() re-polls the suppliers fresh —
    so a swap active during the window is sampled immediately.
R4. **Why fogColor/cameraPosition are already correct**: the shell swap
    (MyGameRenderer.switchAndRenderTheWorldFullPipeline :609-671) sets client.level=dest
    (iris resolves the dest pipeline), installs the portal-transformed camera
    (ip_setCamera :628), and the core computes+passes dest fog (SecondaryWorldRenderCore
    :1540-1555, :1914). What is NOT swapped: `client.player` (only noPhysics touched :618)
    and `client.getCameraEntity()` — the exact gap the biome/eye reads sample through.
R5. **Precedents**: FogRendererContext.swappingManager push/pop at :620/:712 (the paired
    context-stack idiom); IrisDestPrevCamera (the guarded uniform-swap template, S1/S4
    seam pair, disarm-on-throw); the shell finally restores everything on a mid-render
    throw (S18.2 discipline). The IS5-PH lesson: never double-advance smoothies by calling
    customUniforms.update() ourselves (IrisInterface :220) — the natural nested tick
    inside the window suffices (R3).

## §3 MECHANISM (candidate 1 from the recon, cross-dim-only)

A **dest-context override holder** + targeted iris-internal redirects:

1. Holder: `DestContextOverride { Level level; BlockPos pos; boolean active; }` (static,
   render-thread-only, per-invocation semantics). SET beside ip_setCamera
   (MyGameRenderer :628): level = dest level, pos = the virtual camera's block pos.
   CLEARED in the shell finally beside the camera restore (:710). Paired push/pop
   (recursion: nested layers — use a small stack or per-invocation save/restore local,
   the V2-DEFECT-2 rule: no single-slot statics for recursion-visible state).
2. Mixin (iris-internal, remap=false, pinned to iris 1.11.2 — precedent:
   MixinIrisCompositeRenderer_DestPrevWrite): @Redirect on the `Level.getBiome(BlockPos)`
   INVOKE inside BiomeUniforms' playerI/playerF lambda bodies — when the override is
   active, return `override.level.getBiome(override.pos)`; else call original. ONE
   redirect per helper lambda covers the whole biome family, zero pack-formula coupling
   (iris's own biomeMap/unwrap/smoothing runs unchanged).
3. eyeBrightness family (CommonUniforms): same pattern on its player light/fluid reads —
   scope TBD by the panel (it is viewer-state by IP convention too: does the pack use it
   in the nether look? Complementary uses eyeBrightnessM in OW formulas mostly — panel
   rules whether to swap it, with the F12 facts).
4. **Cross-dim only** (sharedState==false discipline): same-dim views share the MAIN
   pipeline's smoothies — a same-dim swap would decay the main chain's own look. The
   override is set only in the cross-dim full-pipeline window (which is already the only
   place client.level is swapped).
5. Convergence semantics (disclosed): the dest pipeline's smoothies converge to dest
   values over the pack's own smoothing half-life (~seconds) after the dest chain starts
   rendering — matching what a player standing there would see after arriving. First
   look through a fresh window shows the same convergence a fresh arrival would. The
   0→1 climb happens ONCE per pipeline creation/dimension entry, not per glance
   (accumulators persist on the per-dim pipeline).

## §4 VERIFICATION

1. Compile + crossing gametest green.
2. The v4 ctx probe IS the acceptance instrument: dest `netherBiome` must READ ~the
   in-nether value (0.8+) while standing in the OW with the window up, and STAY there
   (no decay). The A/B screenshot pair re-taken = the user gate ("must match exactly").
3. Closed-arc re-checks: crossing frames (the override never active outside the nested
   window), same-dim windows byte-identical (override cross-dim-only), IrisDestPrevCamera
   still healthy (its S1 write sits at renderAll 447, AFTER CustomUniforms.push at 431 —
   ordering unchanged by this design).

## §5 OPEN QUESTIONS FOR THE PANEL

Q1. The redirect targets are synthetic lambda methods in BiomeUniforms — pin exact
    signatures via javap against the irisdump jar ([[mixin-bytecode-verification]] rule);
    what is the fallback if the lambda shape differs at runtime (disarm-loud, never crash)?
Q2. eyeBrightness/isEyeInWater scope: swap or leave viewer-state? (isEyeInWater already
    reads the CAMERA fluid — the virtual camera? verify which camera object.)
Q3. Recursion/nesting: A→B→C — the override must carry the CURRENT nested dest's context
    (stack semantics); verify the nested-layer path reuses the same shell bracket.
Q4. The dest pipeline's FIRST frames after creation: smoothies start at the first-polled
    value (hasInitialValue=false → seeds with current input) — with the override active
    from frame 1, they seed CORRECT immediately (no visible climb). Verify seeding order:
    pipeline creation happens inside preparePipeline during the nested render — is the
    override already active there? (It must be — set before destRenderer.render()).
Q5. Does anything else sample player position per-frame in the custom-uniform input holder
    (playerPos-class uniforms) that would now go dest-side and break a pack formula that
    WANTS the real viewer (e.g. player-motion-driven effects)? Enumerate the full
    playerI/playerF registration list and rule per uniform.
