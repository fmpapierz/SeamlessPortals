# S14-B — rung-2 live-defect round 1 (the user's first launch → fixes S14.21–S14.24)

## The live run (2026-07-16 23:41–23:51, tree `1376f6a`)

The crossing machinery PASSED live: clean promote/demote both directions through every crossing
(incl. 8 fast back-to-back crossings in 14s — the step-3 double-crossing stress), zero exceptions,
zero crashes, the look-back window rendering lit compiled OW terrain, rain state syncing per the F1
pre-answer. The user reported four portal-in-view-only visual defects:

- **A:** OW side, nether portal in view — whole sky+clouds sunset-orange at mid-day when the
  crosshair is on the portal; a grey trapezoid + cyan/lavender side bands when looking above it.
- **B:** nether side, OW portal in view — blue patches over DISTANT nether only.
- **C:** a brief flash at each teleport.
- **D (log):** the known U2 GL_INVALID_OPERATION flood escalated ~100/session → **51,470/session**.

## Root causes (9-agent hunt: 4 tracers + 4 Fable adversarial verifiers + Fable fix designer)

1. **The stencil-clear FBO staleness (D + B's admission substrate).** `prepareRendering` bound
   `StencilState.gameFboId` — a last-writer-wins capture of "the most recent depth FBO created
   anywhere" — whose staleness flips at RESOURCE-LIFECYCLE events (world teardown, pause-menu GUI
   target churn via CrossFrameResourcePool TTL → FBO delete), NOT at crossings. Stale-dead names
   threw GL_INVALID_OPERATION per frame AND left FBO 0 bound so the per-frame stencil clear hit the
   WINDOW; stale-live names cleared a WRONG target silently (the error count undercounts). The only
   reason rendering mostly survived is `clampStencilValue`'s end-of-frame zero — which is SKIPPED on
   the occlusion-mispredict early-return, leaving stencil==1 poison the broken clear could not
   remove → next frame's dest draws (stencil EQUAL) paint OUTSIDE the true aperture. **Not a
   cross-dim property** — same-dim rung 1 had the same flood at lower rate and invisible leakage
   (source==dest colors).
2. **GlStateManager cache desync (A's principal painter, B's distant-only shaper).** 26.2 routes
   ALL pipeline state through GlStateManager caches that SKIP the GL call when the cache matches;
   `applyPipelineState` trusts them. The flag-ON path mixed RAW GL11 into cached state — worst:
   `renderScreenTriangle`'s unconditional raw tail `glEnable(GL_BLEND)+glEnable(GL_DEPTH_TEST)`
   left real=on/cache=off after EVERY screen triangle → later no-blend pipelines drew WITH blending
   (the cyan/lavender/orange wash) and null-depth pipelines drew with the REAL GEQUAL test ON
   (= B's "blue over distant only"). Plus 10 raw glColorMask/glDepthFunc/glDepthMask sites in
   RendererUsingStencil — against the class's own ported IP header rule.
3. **Dest sky extraction gap** (never-main dims): `LevelExtractor.extract` fills skyRenderState only
   while `skyRenderer != null`, created solely by vanilla's addSkyPass which the decomposed dest
   pass never runs; OW-as-dest worked only via the accidental demotion-preserves-skyRenderer
   invariant.
4. **C (flash) has no owned mechanism yet** — cold-promote refuted (all 34 session promotes were
   warm), lightmap staleness bounded to ≤1 frame; plausibly closed by fix 1 (one-frame poison
   during crossing-frame FBO churn). Discriminators designed into the re-test.

## The fixes (commits S14.21–S14.24)

- **S14.21:** deterministic `FrameBufferCache.getFbo(color view, depth view)` resolver — the same
  key every main-pass createRenderPass computes; the 26.2 1:1 of IP's `bindWrite(false)` — via
  cache-guarded `_glBindFramebuffer` + exact read/write restore; value-change-gated landing log;
  `gameFboId` @Deprecated (S20 retirement). AW/AT grown: `GlDevice` class + `GpuDevice.backend`
  field (the facade hides the GL backend; mc262-ref's access levels are normalized — trust names,
  not access).
- **S14.22:** every raw toggle with a cached twin → the GlStateManager form; renderScreenTriangle's
  tail restores DELETED (IP has none); `debug_gl_state_assert` 1Hz lever.
- **S14.23:** explicit dest sky extraction via the core-owned portalSkyRenderer when
  `destRenderer.skyRenderer()==null` (vanilla-gate mirror, same camera as the dest extract).
- **S14.24:** levers — `debug_dye_portal_fill` (magenta Row-16 attribution),
  `debug_skip_portal_sky`, `debug_skip_portal_terrain`; promote log `(cold)/(warm)` tag. All
  default OFF, S20-removal-ledgered. Occlusion discriminator = existing `offset_occlusion_query`.

## Re-test acceptance (the user's next session)

1. **D:** ≥5 min with crossings + two Esc-pause/resume cycles + one world switch → grep
   `must be generated` = 0; the resolver log shows a stable FBO per target size.
2. **A:** crosshair on portal → sky stays blue; above portal → no trapezoid/bands.
3. **B:** no blue patches outside the window (OW sky INSIDE the window = correct).
4. **C:** cross at noon and midnight; note whether the flash tracks brightness delta.
5. Only if residue: `offset_occlusion_query` off (B discriminator), `debug_dye_portal_fill`
   (magenta ⇒ stencil content), `debug_skip_portal_sky`/`_terrain` (attribution).

Open: C unowned (watch); remote_entity_move histogram re-enable is S15 scope; S20 ledger grew
(gameFboId retirement, the four levers, Iris-shell raw-GL debt).
