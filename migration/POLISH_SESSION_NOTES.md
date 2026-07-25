# POLISH SESSION WORKING NOTES (2026-07-24) — recon verdicts as they land

Working scratch for the §2 queue. NOT a handoff; distills the recon agents' outputs.

## ★ LIVE ROUND 1 (2026-07-24 ~12:53-13:00, film pass + probes; log READ IN FULL) — VERDICTS

User observations (authoritative): (1) View Bobbing OFF stops the portal bob; (2) portal bob visible
shaders-ON ONLY (gone shaders-off); (3) entities NOT visible through portal shaders-ON but their SHADOWS
are; (4) entities VISIBLE shaders-OFF; (5) particle bleed shaders-OFF ONLY; (6) NEW item (e): creative
inventory mangled after shader toggling — some item icons invisible but usable.

Log facts (fabric/runs/client-sodium/logs/latest.log, 794KB clean): GL census 14× known-class "Invalid
format" — NOW STACK-TRACED for the first time (GlDebug forensics): ALL are iris's own
RenderTargets.copyPreHandDepth(:239)/copyPreTranslucentDepth(:227) glCopyTexImage2D on pipeline-recreate
frames (each shader toggle adds a few; count scales with toggles — same known-minor class, now precisely
attributed). Zero dark-path WARNs (no storage-null, no dead-latch, no swallows). Fix stack ALL LIVE:
[FIX-1] suppressed>0/armed=0 ✓, [IS5-G] ✓, [IS5-PH] ~100-119 heals/s during shaders-ON portal windows ✓
(the single 0-window = a shaders-OFF stint, correct gating). Session states: ON→OFF(12:57:42)→ON(12:58:34)
→OFF(12:59:12).

**§2b ENTITY MECHANISM NAMED BY THE PROBE (same-dim, compat route):**
- Shaders-ON window: `dp≈1-2/frame, routes[x=0,f=0,sd=0], lvl=-1, cons=0, extr=0, sub=0, pes=0` — the
  nested full-pipeline render's submitEntities RUNS but receives ZERO entity states, and NO dest entity
  extract of any route ever fires. Same-dim ⇒ sharedState=true ⇒ Step-5 is `!sharedState`-gated ⇒ skipped;
  and renderDestWorldFullPipeline NEVER calls renderPortalEntitiesSameDim (that's a decomposed-route
  step-10.8 feature). The shared LRS's entityRenderStates was already DRAINED+CLEARED by the main pass's
  own submitEntities (:282 clear). **⇒ ROOT CAUSE: the compat full-pipeline route has NO same-dim entity
  path at all.** (IP's nested renderLevel naturally re-rendered same-dim entities — this is a fidelity gap
  vs IP, not a cull bug. The C2-era "sodium regression" framing is RETIRED for this symptom.)
- Shaders-OFF window (stencil, same route rig): `routes[sd=dp], lvl=101@overworld, cons≈101/pass,
  rej≈88%, hid≈62% (IP's isOnDestinationSide filter — expected), nv/nd5>0 (neutralizes live),
  extr==sub==pes≈12-23/pass` — the same-dim scratch path is LOSSLESS extract→submit→per-entity-submit,
  and the user confirms entities visible. Stencil route fully healthy, probe-proven.
- "Shadows visible" datum: trivially consistent for same-dim — the cows are in the MAIN pass's shadow
  map (same world, same map); the window samples it.
- CROSS-DIM compat entities UNTESTED (routes f=0 all session — no cross-dim portal viewed). Step-5 DOES
  run cross-dim (!sharedState) → likely functional; verify live later.

**§2a BOB — MECHANISM CONFIRMED (round-2 recon wf_365e5ee2-30f, bytecode-decisive; hypothesis corrected):
iris RELOCATES view-bob from PROJECTION → MODELVIEW under shaders, only on the main render call.**
- iris `MixinModelViewBobbing` (all @WrapOperations on GameRenderer.renderLevel, gated `areShadersOn`):
  leg 1 SKIPS the vanilla bob-multiply into the projection (saves the pose as `bobStack`); legs for spin
  fold into bobStack too; leg 3 `modelView.mulLocal(bobStack)` right before the MAIN LevelRenderer.render.
  ⇒ under a pack the projection is UNBOBBED everywhere — sodium's captured projection field, iris's
  gbufferProjection (read via `sodium$getProjectionMatrix()` at `iris$setupPipeline`, the SOLE
  setGbufferProjection caller), AND our `capturedMainPassBobbedProjection` (same getBuffer wrap — the
  field name is a MISNOMER under iris; the whole projection-bob machinery is INERT there).
- iris draws from `gbufferProjection·gbufferModelView` uniforms, IGNORING the ambient RenderSystem
  projection (our Step-7 install is unread by iris geometry). The nested dest render re-enters
  `iris$setupPipeline` only: gbufferModelView := our bob-free destViewMatrix; gbufferProjection := sodium's
  STALE main value (our writeProjectionSlice path never triggers sodium's getBuffer wrap — also confirms
  the ledgered scaled-portal stale-gbufferProjection item's mechanism).
- ⇒ shaders-ON: WORLD bobs (modelview), WINDOW static → relative bob. Shaders-OFF: bob rides the shared
  projection → lockstep. View Bobbing OFF: nothing bobs. ALL THREE live observations satisfied.
- FIX DIRECTION (needs a design panel — matrix-chain family, S13-M wobble history): iris-gated
  (isShaders), capture the bob+spin pose (our MixinGameRenderer already hooks renderLevel; @Local the
  PoseStack or wrap the same mul site) and pre-multiply onto BOTH the dest modelview (destViewMatrix for
  the nested render) AND the stamp/aperture transforms (stampPortalArea modelView + the aperture/cull
  users of getCurrentProjectionMatrix) so window content AND aperture track the bobbed world; leave
  destDrawProjection unbobbed under iris. Optional zero-reach-in confirm probe: 1Hz delta of the captured
  projection vs cameraRenderState.projectionMatrix while walking (shaders-ON ⇒ ≈0; OFF ⇒ >0).

**★ LIVE ROUND 2 CLOSE-OUT (2026-07-25, user verdicts):** §2b entities LIVE-CONFIRMED + A/B BOTH
DIRECTIONS (cow visible through shaders-ON window; `-PdisableCompatSameDimEntities=true` ⇒ entities gone
= correct attribution) — **§2b CLOSED**. §2c: behavior correct both shader states, but the
`-PdisableSourceParticleCull=true` A/B could NOT reproduce the bleed — EXPLAINED, not anomalous:
options.txt shows `improvedTransparency:false` + `graphicsPreset:"custom"` = **iris's STICKY Fabulous
force-disable** (MixinDisableFabulousGraphics writes the option off permanently on first shader enable).
The bleed's precondition is gone from this rig; zero [sourceParticleCull] ACTIVE lines across the runs =
the cull has never fired live. **§2c status: mechanism-verified, delta-verified SHIP, live-DORMANT** —
protection for whenever Fabulous returns; to truly exercise it: re-enable Improved Transparency with
shaders off + behind-window campfire (user declined — acceptable). User re-routed: bob design panel next.

**§2c PARTICLES — MECHANISM CONFIRMED + FIX SHIPPED (round-2 recon wf_365e5ee2-30f, HIGH confidence):
the bleed is FABULOUS-specific.** Ordered pipeline (all file:line-verified): particles target created only
under Improved Transparency (LevelRenderer :186-192); its depth copied from main at :429-431 (BEFORE the
portal); AFTER_TRANSLUCENT_TERRAIN (fabric @WrapOperation on the TRANSLUCENT renderGroup, ordinal 1) fires
next — OUR stencil portal draw, main target only; THEN executeTranslucentAfterTerrain draws translucent
particles into the SEPARATE target against the STALE pre-portal depth; the transparency composite paints
them over the window ("composite FULL-SCREEN over the main view" — the mod's own dest-side Fabulous guard
comment documents the identical class at SWRC :2440-2444). Shaders-ON never bleeds because iris
FORCE-DISABLES Fabulous (MixinDisableFabulousGraphics — bytecode). Sodium: no role. Non-Fabulous: particles
draw into MAIN vs live plane depth ⇒ behind-plane correctly culled. ⇒ THE USER RUNS FABULOUS.
**FIX SHIPPED (2026-07-25, this commit): the D3 gate in QuadParticleGroupMixin amended** — flag-ON now also
runs the geometric cull (behind-plane-in-aperture only, never in-front), lever
`-Dseamlessportals.disableSourceParticleCull` (DEFAULT-ON) + `-P` rows, counter
IPGlobal.sourceParticleCullCount surfaced as `spc=` in [ENT-PROBE] (flag-ON-gated per verify finding 2),
once-only ACTIVE line, plus an isDestExtracting belt (flag-ON dest passes can't reach the redirect anyway —
S14.40 cancel + ip_extractIsolated bypass — belt defends the invariant).
**★ FINAL-DIFF VERIFY CATCH (BOTH verifiers independently, 2×SHIP-WITH-FIXES): the first form was a flag-ON
NO-OP** — PortalParticleClip consulted the block-era PortalManager tracker whose FIVE feeders are ALL
D3-gated OFF flag-ON (flag-ON portals are IP Portal ENTITIES, never in that tracker) ⇒ empty roster, zero
culls, a lever that discriminates nothing. **REPOINTED (implemented + compile green): flag-ON branch in
PortalParticleClip = per-frame cached roster (keyed RenderStates.frameIndex — ticks flag-ON via the
MinecraftFramePumpMixin:104 IP port — + level identity) via IPMcHelper.getNearbyPortals(level, camPos, 64)
(includes globals) filtered Portal::isVisible, predicate = portal.rayTrace(camPos, particlePos) != null
(shape-aware, 0.001 leniency). Block-era tracker path kept byte-identical flag-OFF.** RULE re-earned: a
recon's geometry analysis is NOT roster-population proof — verify the data source's feeders in the target
config. Zero-code cross-check available: Fabulous OFF ⇒ bleed gone even lever-off (mechanism is
Fabulous-specific). **Delta-verify SHIP (no edits) — bonus: the raytrace is ONE-SIDED (camera-front only)
= correct for per-side portal entities; back side of one-way portals never culls.** Ledgered residuals:
L1 roster radius 64 — far-window bleed (>64-block portals) exempt; if a live round shows it, bump
FLAG_ON_ROSTER_RANGE first, don't re-diagnose. L2 disconnect roster retention (bounded, self-healing).
L3 GeometryPortalShape per-triangle cost (fine at scale). L4 camera exactly on-plane never culls (correct
mid-crossing behavior). Plus: scaled-portal shape bounds (LOW); renderMode=none dev state still culls
(dev-only).

**§2e creative-inventory icons: DROPPED (2026-07-25)** — the user could not reproduce it; no atlas/resource
errors in the log. Reopen only on a fresh sighting with repro steps.

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

**★★ IS-BOB FIX IMPLEMENTED (2026-07-25, panel wf_22f132bb-257: recon → 2 designers → adjudication
(DERIVE-POSE core won) → 2×SOUND-WITH-FIXES → implemented with all folds; compile green).** Key panel
facts: the APERTURE side was ALREADY correct (passingModelView/anchor copies are taken AFTER iris's
in-place mulLocal ⇒ bobbed {POSE·V, P_base} = the true main clip transform) — ONLY the dest CONTENT lacked
the pose; applying to the aperture would have double-bobbed. Implementation: NEW IrisBobSync (zero iris
reach-in): per-frame V copy at extract-RETURN (post-R13k; the field is mulLocal-mutated in place later +
object-replaced every frame — never cache the ref); relocation DISCRIMINATOR at the projection-upload wrap
(uploaded bit-equals the pristine base ⟺ iris stripped the bob — causally locked, absorbs every toggle
window; an isShaders() gate would double-bob the pack-enable frame); derive POSE=(bobStack·V)·V⁻¹ at the
compat workhorse from the previously-IGNORED anchor arg; per-portal apply destDrawViewMatrix =
new Matrix4f(destViewMatrix).mulLocal(POSE_s) (FRESH copy = LOAD-BEARING: iris setGbufferModelView ALIASES
the arg) feeding the THREE draw consumers (destCameraState.viewRotationMatrix H1-dual-set, clip feed,
render arg); CULL legs stay RAW (frustum + Step-9', vanilla bob-free-cull parity + the C2 async-tree
rule); ×s translation scaling gated on viewBobbingReduce (verify FIX-1); planeW = c − dot(planeXYZ,
col3(MV)) via a shared FrontClipping helper in BOTH writers (feedViewSpacePlane + toViewSpaceSnapshot —
writer parity mandatory), lever+exact-zero-guarded (shaders-OFF bit-identical). Lever
-Dseamlessportals.disableIrisBobSync (DEFAULT-ON) + irisBobSyncApplyCount + [iris-bob-sync] LIVE once-only
+ [BOB-SYNC] 1Hz probe (-PbobSyncProbe). LIVE PROTOCOL: stand 3-6 BLOCKS BACK (viewBobFactor ramps to 0
within 1 block — closer looks falsely inert); defaults ⇒ window tracks the bobbing world; A/B lever ⇒
relative bob returns; pre-registered scaled-leg regression signature = walk-synchronized sliver of
missing/extra dest terrain hugging the portal plane (wrong W).

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

**★ FIX IMPLEMENTED (2026-07-25): Step-9.5-SD compat same-dim entity fill** — adjudicated spec (workflow
wf_365e5ee2-30f: 2 designers → adjudication → 2×SOUND-WITH-FIXES) implemented with both verifier fixes
folded: FIX-1 lastEntityRenderStateCount save/restore (the F3 "E:" reader), FIX-2 per-list null-guarded
finally restores (the sameDimCompatFillRan flag dropped entirely — a saved list is non-null iff ITS swap
ran). Pieces: IPGlobal lever `-Dseamlessportals.disableCompatSameDimEntities` (DEFAULT-ON) + active
predicate + compatSameDimEntityFillCount; SWRC swap-holder locals + the gated fill block after Step-9'
(swap-out entity/BE/particle lists + fillSameDimStatesForNestedRender: ERD prepare → isDestExtracting
extract → "fsd" probe record → BE extract post-Step-9' → isolated particle fill → liveness+counter; catch
= clear + 3-strike dead-latch + once-only WARNs) + per-list finally restores + cleanUp resets + latch fsd;
probe fsd route + spc field; build.gradle rows ×2. Honors debugSkipSameDimEntities (one lever kills both
same-dim passes). EXPECTED VISUAL (verifier FIX-2 pre-registration): entities STRADDLING the plane show
their behind-plane half in the window — compat entity draws are plane-UNCLIPPED (pre-existing accepted
class, first user-visible now). Iris shadow pass is fill-INDEPENDENT (own LRS + own extract — why shadows
showed pre-fix). Residual risk #1 (probe-discriminated): submitted-but-not-drawn downstream iris failure
⇒ fsd/extr/sub/pes healthy + still invisible ⇒ round-2 draw-phase hunt.

**PROBE BUILT (2026-07-24, committed 19f0184):** `EntityVisibilityProbe`
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
