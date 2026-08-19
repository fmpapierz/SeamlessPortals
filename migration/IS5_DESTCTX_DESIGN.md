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

## §6 ⟦J⟧ PANEL FOLDS (BINDING — wf_ed89a27c-2de, 2026-08-17; both judges APPROVE_WITH_CHANGES)

FB1 **Redirect targets corrected (BYTECODE)**: getBiome lives in `lambda$addBiomeUniforms$0..$4`
    (five per-uniform statics), NOT the playerI/playerF wrappers (§3.2 as drafted would have
    silently no-oped). IMPLEMENTED as the panel's preferred variant: @Redirect
    `LocalPlayer.level()` + `LocalPlayer.blockPosition()` across the five bodies — also
    dest-ifies $2's getPrecipitationAt(pos, seaLevel) tail. irisdump SHA-verified byte-identical
    to the runtime jar; re-javap on any iris bump.
FB2 **The cross-dim gate premise was FALSE**: the shell bracket runs for SAME-DIM views and
    NESTS. IMPLEMENTED: per-invocation push/pop (push after ip_setCamera(newCamera), pop in
    the finally after ip_setCamera(oldCamera), BOTH shell twins); ACTIVE iff
    `newDimension != RenderStates.originalPlayerDimension && client.player != null`.
    Same-dim byte-identical; A→B→A depth-2 pushes INACTIVE (overwrites B's ctx); A→B→C
    carries C. The MAIN pipeline's update() moments are structurally outside every bracket.
FB3 **Two windows, one dest dim**: sticky per-(frame, destDim) pos — first bracket captures,
    later ones reuse; cleared at the IS5 frame hook. Symmetry by construction (the
    2M-refresh lesson), never arbitration.
FB4 **Mixin config**: @Pseudo + remap=false + require=0/expect=0 (the json's defaultRequire=1
    would hard-crash on drift) + the liveness watchdog (ACTIVE pushes with zero redirect hits
    → one-shot loud WARN; degrades to the shipped decay, never crashes).
FB5 **Q2 ruled**: isEyeInWater — NO WORK (reads the swapped mainCamera, already dest-correct).
    eyeBrightness — LEAVE in v1: mixed-context today (dest level at source coords), but the
    contract case (OW→nether) is position-insensitive (nether sky=0 everywhere) so
    eyeBrightnessM/isEyeInCave converge correct; the nether→OW direction is a DISCLOSED
    residual (extend the holder into getEyeBrightness if the A/B shows it).
FB6 **Q4 corrected**: prewarm creates pipelines OUTSIDE brackets, but smoothies seed at the
    FIRST customUniforms.update() = first beginLevelRendering = inside the window ⇒ seeds
    correct for never-visited dims; a previously-mained dim converges from its stale values
    (disclosed §3.5). Unloaded-chunk seed: getBiome falls back to plains until dest chunks
    arrive — a brief climb, not the decay defect (probe-reading note).
FB7 **Closed arcs verified untouched**: IrisDestPrevCamera (push@431 uploads CACHED values;
    S1@447 ordering unchanged; keys on built-in cameraPosition — never touched);
    RESTAMP/SG/wash-bracket (location-based introspection, per-entry stampedPre — no
    uniform-value contact); crossing frames (bracket never spans the S14 pump; cross-view
    frames have no main-chain update). FARFADE COMPOSES (orthogonal defects) — but
    post-DESTCTX the blend endpoints diverge more (SG gains the corrected tint, gradedPRE
    stays source-processed): NAMED RE-GATE legs = fade-band walk-out re-run + the wMin/D1
    retune round + "window-2 PRE delta grew" adjudication note. Part of the historical
    far-wash report may have been THIS tint defect — expect retune, don't re-litigate.
FB8 **Pairing contract**: the SET/CLEAR pair is a new sanctioned full-pipeline delta —
    recorded here for the MyGameRenderer pairing-contract lens. Ledger: remap=false MC-name
    targets inside iris classes are dev-runtime-correct; an intermediary-mapped release
    would silently no-op (require=0) — same latent property as the shipped DestPrev family.
