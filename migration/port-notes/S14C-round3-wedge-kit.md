# S14-C round 3 — the wedge hunt: stencil family CLOSED, round-4 arbitration kit

## Round-3 verdict (2 tracers + 2 Fable verifiers + designer, resumed across the PC restart)

**The round-3 lead (leaked/stale GL_STENCIL_TEST clipping vanilla sky) is REFUTED with an unbroken
chain:** the flag-ON path provably ends every frame stencil-DISABLED with content 0
(`myFinishRendering` runs on the outermost `doPortalRendering` branch; the outermost
`clampStencilValue(0)` is additionally a screen-wide zeroer); vanilla 26.2 owns ZERO stencil
symbols (applyPipelineState touches only depth/cull/blend/polygon/colorMask); the sky pass
executes BEFORE the mod's mid-main-pass hook. Also refuted at the SHAPE level: per-fragment
stencil rejection geometrically cannot produce fan-aligned sectors with triangulation seams —
that requires a primitive/vertex-level kill. Stencil WRITES are likewise confined by proof: the
INCR rasterizes only the quad mesh (user-confirmed by the green dye), the restore draw is
op=KEEP everywhere, and `clampStencilValue`'s fullscreen triangle REPLACEs only where
`ref < stencil` = inside the just-INCR'd aperture.

**Surviving candidates after round 3:**
1. **GL_CLIP_DISTANCE0 family** — the second vanilla-blind persistent raw-GL capability (the
   clip-plane shader injection). Mostly refuted by the recovered shader inventory (the 2026-06-25
   root logs show ALL sky-family + terrain + entity + particle vertex shaders ARE patched with
   the clip uniform; only `core/screenquad` + `core/animate_sprite` are not — so a stale-enabled
   cap with a stale plane would clip patched TERRAIN too, which is not observed). Survives only
   as an empirically-unclosed capability-leak question — ONE probe line closes it.
2. **`renderPortalEntities`** — the ONE in-bracket full-color-write draw with NO lever,
   `!sharedState`-gated = CROSS-DIM-ONLY (matching the wedges surfacing at the cross-dim rung),
   with a silent `catch(Throwable)` swallow. Prime same-frame suspect (branch C).
3. **Branch D** — vanilla's own sky drawn with portal-frame-correlated per-draw STATE
   (fog-slice/UBO/skyRenderState/target mix-up). Only reached if the probe shows both caps OFF
   and the entities lever changes nothing.

## The S14.28 kit (this commit)

- **Frame-boundary PROBE** (`debug_frame_boundary_probe`, 1Hz, default OFF) at
  `GameRenderer.renderLevel` HEAD — logs pre-guard `GL_STENCIL_TEST` enable/func/ref +
  `GL_CLIP_DISTANCE0` + the FrontClipping plane snapshot. One session arbitrates families A/B.
- **Frame-start capability GUARD** (always-on, flag-ON mixin): `glDisable(GL_STENCIL_TEST)` +
  `FrontClipping.disable()` before the framegraph. In steady state a provable no-op; any leak is
  now bounded to one frame. LEDGERED substrate deviation — IP has no analog and needs none;
  26.2 vanilla owns neither capability, so leaks persist silently forever (same hazard family as
  the S14.21/S14.26 invariants).
- **`debug_skip_portal_entities` lever** — the complementary lever to the four that exonerated
  everything else (branch-C test).
- **One-shot swallow log** in the `renderPortalEntities` catch (first throwable per session).

## The S14.29 hardening (next commit; all ledgered)

- `doRenderPortal` push/…/pop wrapped in try/finally (an escaped throw used to freeze
  `isRendering()==true` forever = silent portal death + stencil frozen enabled — the latent
  PERMANENT-corruption mode; IP has the identical unbracketed shape → deviation, ledgered).
- GUI-portal tail: explicit `glDisable(GL_STENCIL_TEST)` after `invokeWorldRendering` (the
  confirmed benign ENABLED+EQUAL(0) leak into the HUD/next frame).
- The stale flag-OFF ordering comment in SeamlessPortalsClientFabric corrected (it seeded the
  refuted round-3 lead).
- S18 wiring notes (backlog): (a) `CrossPortalViewRendering.renderCrossPortalView` (dead, S18/S19)
  MUST get a prepare/finish-style stencil bracket when wired; (b) **dest inner-clip coverage gap
  (IP-fidelity item, do NOT bundle into the wedge hunt):** IP arms the inner clip per section
  layer + per entity phase (IP MixinLevelRenderer:192-232, CrossPortalEntityRenderer:84/:101);
  the port's Step-10.5 arm covers only OPAQUE — `CrossPortalEntityRenderer
  .onEndRenderingEntitiesAndBlockEntities`'s unconditional `disableClipping()` fires during the
  dest SUBMIT, so dest TRANSLUCENT terrain (water) + dest entities draw UNCLIPPED. Fix at
  S18-adjacent: re-arm before Step-10.9 TRANSLUCENT + bracket `renderAllFeatures`.

## Round-4 run protocol (user; next session)

0. Reproduce the wedges. BEFORE levers, three framing questions (confirm-visual-interpretations
   rule): (a) radial/fan-aligned with apex near zenith — or window-shaped? (b) flickering
   per-frame — or stable camera-tracking? (c) graphics setting (Fast/Fancy/Fabulous)?
   **If the wedges are ALREADY GONE at baseline: the always-on guard cured a cross-frame
   capability leak — still run step 1 to identify which family.**
1. `debug_frame_boundary_probe_enable` — collect the 1Hz lines while wedges show (or showed).
   Both-off + plane (0,0,0,1) = families A/B closed forever.
2. `debug_skip_portal_entities_enable` — wedges GONE = branch C (the painter is found);
   UNCHANGED = branch D. (Side-effect check that the lever worked: dest entities/particles
   vanish from the window while ON.)
3. Grep the log for `[renderPortalEntities] swallowed` — attach if present.
4. GUI-tail regression: open/close any GUI portal (if applicable), confirm no next-frame
   corruption.

## Outcome branches (pre-designed)

- **A (probe: stencil ON):** cross-frame stencil leak exists — only two enablers reachable
  (GUI-portal tail [now closed] or an escaped-throw wedge [now bracketed]); guard already cures;
  repair the site the probe implicates.
- **B (probe: clip cap ON):** read the plane values; the guilty bracket is one of Step-10.5 /
  ViewAreaRenderer / PerEntityClipBracket / CrossPortalEntityRenderer; guard cures; pair the leak.
- **C (both off; entities lever removes wedges):** target the mid-frame FeatureRenderDispatcher
  reuse against per-frame vanilla target handles; spec an isolated-submit fix before coding.
- **D (both off; lever changes nothing):** every mod-emitted draw exonerated — extend the probe
  to the sky pass's bound FBO + fog-slice identity; the painter is vanilla sky under
  portal-correlated state.
