# POLISH SESSION WORKING NOTES (2026-07-24) — recon verdicts as they land

Working scratch for the §2 queue. NOT a handoff; distills the recon agents' outputs.

## §2a BOBBING — recon VERDICT: single-application BY DESIGN (not a double-bob)

Bytecode-proven chain (agent recon, 2026-07-24):
- **26.2 moved bob to the PROJECTION.** `GameRenderer.renderLevel` multiplies `bobHurt`/`bobView` poses into a
  local copy of the projection (`P_base·B·SPIN`) and uploads it via `levelProjectionMatrixBuffer.getBuffer`
  (the ordinal-0 site our `MixinGameRenderer` wraps). The modelView passed to `LevelRenderer.render` is
  `cameraRenderState.viewRotationMatrix` = **bob-FREE**. `Camera` has no bob method at all.
- **Single source, single hop:** `MixinGameRenderer` captures the bobbed projection →
  `RenderStates.capturedMainPassBobbedProjection` → `RenderStates.getPortalDrawProjection` (RenderStates.java:273)
  → installed as the ambient projection at **Step 7** (SecondaryWorldRenderCore :942-943 decomposed /
  :1598-1600 full-pipeline). For unscaled portals it's byte-identical to the main bobbed projection.
- Dest camera angles, `destViewMatrix`, `destCameraState.projectionMatrix` all bob-free;
  `destCameraState.entityRenderState.bob` force-zeroed (:699, :1433). The nested draw is `LevelRenderer.render`,
  never `GameRenderer.renderLevel` → vanilla bob fires exactly once per frame (main pass only).
- **Stamp** (IrisCompatPaste:271): `combined = P·MV` positions the aperture only; fragment shader samples at
  `gl_FragCoord` 1:1 — no re-projection. Aperture + content share ONE bob → should track in lockstep.
- **IP reference:** original IP re-entered full `renderLevel` for dest views → IP's dest ALSO bobbed. The port
  reproduces this deliberately (port note `migration/port-notes/S13M-view-bob-wobble.md`, P1/P2/P3;
  `RenderStates.java:99-106` comment).
- Bob translate is scaled by `getViewBobbingOffsetMultiplier()` = `viewBobFactor · getExtraModelViewScaling()`;
  `viewBobFactor` ramps with portal PROXIMITY (`RenderStates.updateViewBobbingFactor:207-231`) — a candidate
  for perceived in-window-vs-main mismatch if anything is off.
- Flagged unverified: runtime amplitude (needs probe); sodium's consumers of `destDrawProjection`
  (`ip_driveDestTerrainSetup`/`ip_armDestChunkRenders`, SecondaryWorldRenderCore :1034-1066) not javap'd inside.

**Implication for the film pass:** the discriminating observable is NOT "does the window bob" (it should, in
lockstep — IP-faithful) but **"does the in-window bob track the surrounding view 1:1, or is it exaggerated /
out-of-phase / persisting when vanilla View Bobbing is OFF"**. If lockstep-1:1 → likely correct-as-designed →
becomes a user DESIGN CALL (IP side-by-side optional confirmation), not a bug.

**Probe points (if needed after film):** best single chokepoint = `RenderStates.getPortalDrawProjection`
(1Hz dump: baseProjection, capturedMainPassBobbedProjection, extraScaling, returned matrix — bob delta
= m30/m31/m32/m20/m21 vs base). Ready-made: `ShadowEmptinessProbe.beginPass` already receives both
destDrawProjection + main projection (SecondaryWorldRenderCore :1693-1698). Stamp cross-check at
IrisCompatPaste:271.

## §2b ENTITIES — recon VERDICT: mechanism identified, neutralizes PRESENT — loss point unknown ⇒ probe decides

Sodium's dest-entity zeroing mechanism (javap-proven, agent recon 2026-07-24):
- Vanilla extract gate: `LevelExtractor.isEntityVisible = (EntityRenderDispatcher.shouldRender || indirectPassenger)
  && (isOutsideBuildHeight || LevelRenderer.isSectionCompiledAndVisible)`. Under sodium `viewArea` =
  `IgnoringViewArea` whose `getRenderSectionAt` is `{aconst_null; areturn}` ⇒ `isSectionCompiledAndVisible`
  FALSE for all positions ⇒ every in-build-height dest entity culled AT EXTRACT.
- Second sodium cull: sodium `EntityRendererMixin.preShouldRender` @WrapOperation on `frustum.isVisible` inside
  `EntityRenderer.shouldRender` → `SodiumWorldRenderer.isEntityVisible` → `RenderSectionManager.isBoxVisible`
  → `SectionTree.isBoxVisible`.
- **BOTH have neutralizes in-tree + registered:** `LevelRendererEntityVisibilityMixin` (:43 HEAD, forces true
  iff `isSodiumPresent() && (isRenderingPortal || isDestExtracting)`; registered common.mixins.json:57) and
  D5 `MixinSodiumRenderSectionManager.ip_onIsBoxVisible` (:377-385, forces true iff
  `portalsRenderedThisFrame != 0`; registered ip-compat.mixins.json:10). Compat route uses the SAME extract
  bracket (`isDestExtracting` :1533-1538) → neutralizes fire there too. The mixin javadoc's "!iris" claim is
  STALE — code does not exclude iris.
- Entity dispatch: cross-dim `renderPortalEntities` :2120 (submitFeatures :2148, renderAllFeatures :2159);
  same-dim `renderPortalEntitiesSameDim` :2316 (isolated re-extract :2351, submit :2361, draw :2422). Compat
  route: nested full `destRenderer.render(...)` :1752-1761 does submitFeatures→submitEntities internally.

**So statically entities SHOULD render — the user says they don't. Candidate live loss points:**
- FRAGILITY A: gate divergence — the two neutralizes key on DIFFERENT signals (`isSodiumPresent&&isDestExtracting`
  vs `portalsRenderedThisFrame!=0`); any extract satisfying one but not the other loses a conjunct.
- FRAGILITY B: FeedOnly state (`isSodiumPresent()==false` with sodium jar present) → vanilla gate falls through
  to IgnoringViewArea ⇒ all culled. Reachable via D23 layer-0 fallback (:348-355 IrisCompatOn262Renderer).
- SILENT DARK PATHS (Q6): `renderPortalEntities` storage==null silent return (:2126-2128); catch(Throwable)
  swallows (:2169-2180 cross-dim, :2437-2457 same-dim, one-shot log only); **same-dim DEAD-LATCH — after 3
  swallowed throws the whole same-dim entity pass silently returns for the session** (:2323-2324);
  `MixinEntityRenderDispatcher.shouldRenderEntityNow==false` silent cancel (:42-45); compat onBeforeHandRendering
  early returns (:176/:179/:187-200).
- Downstream loss: submitted-but-not-drawn (wrong target under iris / stamp overwrite / clip) — the counters
  split this from extract-side loss.

**PROBE BUILT (2026-07-24, compile green, uncommitted; suite running):** `EntityVisibilityProbe`
(qouteall.imm_ptl.core.render), lever `-Dseamlessportals.entityProbe` / `-PentityProbe` (both gradle blocks),
1Hz `[ENT-PROBE]` line driven from GameRendererMixin frame-end (beside TeleportFlashProbe). Schema:
`pf` portal frames, `dp` dest submit passes, `routes[x/f/sd]` decomposed/full-pipeline/same-dim,
`lvl=N@dim` dest-level entity census, `cons/rej` isEntityVisible calls/rejections during dest extracts
(new `LevelExtractorEntityProbeMixin`, registered common.mixins.json), `hid` shouldRenderEntityNow vetoes,
`nv/nd5` neutralize fire counts (C2-1e + D5), `extr` extract output, `sub` submitEntities input,
`pes(h=)` per-entity submits (+seam-handled), `STORAGE-NULL=`, `gates[sod pf@x]`, `latch[x sn sd/3]`.
DECISION TREE: lvl=0 ⇒ mirror/tracking gap (not render). lvl>0,cons=0 ⇒ extract skipped/not iterating.
rej≈cons with nv>0,hid=0 ⇒ frustum/distance rejects (destFrustum suspect); hid≈rej ⇒ the cross-portal hide
over-hides; nv=0 during dest extract ⇒ FRAGILITY A/B (neutralize not firing). extr>0,sub=0 ⇒ loss between
extract and submit (storage/throw — check latch + STORAGE-NULL). pes>0 & invisible ⇒ downstream of submit
(draw/target/clip/stamp) → round 2 probe.
ALWAYS-ON once-only WARNs added: storage-null skip (was silent), same-dim dead-latch trip (was silent).
Fable panel on the mechanism AFTER live probe data + the shaders-OFF A/B (checklist §1.4).

## §2c PARTICLES — recon VERDICT: depth-write hypothesis REFUTED; behind-plane bleed is impossible via the stamp

Agent recon 2026-07-24 (iris decompiled + pack GLSL + MC 26.2 ref + worktree):
- **Reversed-Z kills the handoff's premise**: behind-plane particle ⇒ farther ⇒ SMALLER depth than the
  plane ⇒ the stamp's GEQUAL (IrisCompatPaste.java:127, `GREATER_THAN_OR_EQUAL, writeDepth=true`) PASSES ⇒
  dest paints OVER it — regardless of particle depth-write. Verdict matrix rows A-C all "correctly hidden".
- **26.2 particles DO write depth** (RenderPipelines.java:136 PARTICLE_SNIPPET → DepthStencilState.DEFAULT =
  `(GEQUAL, true)`; TRANSLUCENT_PARTICLE overrides only blend). The handoff's "particles typically don't
  write depth" is WRONG for 26.2. Complementary's particle program = gbuffers_textured
  (`/* DRAWBUFFERS:063 */`, no gl_FragDepth, no depth-mask control — GLSL can't set it anyway).
- Iris 1.11.2+26.2: particles tagged WorldRenderingPhase.PARTICLES via QuadParticleFeatureRenderer hook;
  `particles.ordering = mixed` (shaders.properties:83) is stored but has NO reorder consumer in this build.
- Snapshot timing: anchor fires AFTER the full LevelRenderer.render ⇒ snapshot color+depth include particles
  (under iris, particles write the shared depthtex; the vanilla separate-particles-target wrinkle is
  stencil-route-only).
- **⇒ If the user's bleed is real, the particles are IN FRONT of / coplanar with the plane (row D — depth-
  CORRECT occlusion), most plausibly the portal's OWN ambient particles hugging the window.** The
  characterization run decides. A behind-plane confirmed sighting would contradict the matrix ⇒ new hunt
  (temporal smear candidate: LOW-MED, one-frame class).
- Row-D remedies IF the user wants them gone: geometric aperture cull. The mod HAS one —
  `QuadParticleGroupMixin`/`PortalParticleClip.isPositionBehindPortal` (:79-115) — but it's the BLOCK-ERA
  path, skipped when `isEntityPortals()` (default TRUE, the D3 gate :93-95). NOTE: that cull is
  "behind-portal" semantics, not row-D "in-front-overlapping" — read carefully before reuse.
  FrontClipping.setupOuterClipping exists but is DEAD/uncalled (old memory concurs); whether particle
  shaders carry the clip injection is UNVERIFIED.
- DO NOT touch the stamp's GEQUAL/write to "fix particles" — it would break correct in-front occlusion.
