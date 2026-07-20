# IRIS SHADERS-ON GOVERNING DESIGN — the compatibility renderer engagement

**Synthesized 2026-07-20 by the judge pass over three independent designs (A staging-first /
B mechanism-first / C substrate-fidelity), every load-bearing claim re-verified against the
ground-truth docs and the in-tree sources at HEAD df3ac1f.** This document governs the
engagement that replaces deviation D8 (shaders-ON → rendererDummy + one-shot notice) with a
real compatibility renderer: portal views rendering WITH an active shaderpack
(Iris 1.11.2 / Sodium 0.9.1 / MC 26.2), flag-ON substrate.

Standing rules unchanged and binding (handoff §0 chain → S19_HANDOFF §0): NO
GUESSING/instrument-first; Opus mechanical / Fable verify+design; worktree per stage;
compile gate; 8-leg suite green (the suite CANNOT exercise iris or sodium — every READY
says so; the user's shaderpack live rounds are the only iris proof; reference pack =
Complementary Reimagined/Unbound); commit+push each increment; the sodium tier's landed
machinery is touched ONLY through the ledgered seams (port-note C2-sodium-iris.md §2.5/§3.4).

---

## §0 DECISION RECORD

### 0.1 Scores (evidence-grounding / staging safety / mechanism concreteness / fidelity, of 5)

| Design | Evid. | Staging | Mechanism | Fidelity | Note |
|---|---|---|---|---|---|
| A (staging-first) | 3.5 | 4.5 | 3.5 | 3.0 | Best discriminator/lever discipline; but its pre-main + offscreen-FBO + stencil-aperture shape deviates from IP's compat contract where B/C preserve it, and puts the iris output-routing unknown (does iris honor the mc-target swap?) on the critical path |
| B (mechanism-first) | 4.5 | 4.0 | 4.5 | 4.5 | File:line anchors verified this session; the recon census (P-A1/B1/B2/PASTE/CLIP-UP) is the best in the panel; weakness: the Step-10 "mode flag" inside renderDestWorld risks mining-§8-13 double-consumption, and its pipeline-INSTALL branch contradicts IP's own null-bracket evidence |
| C (substrate-fidelity) | 5.0 | 4.5 | 4.5 | 5.0 | The member-by-member IP-contract walk + hazard ledger; correctly reads IP's own compat renderer as running under the setPipeline(null) bracket; clobber-and-restore removes the biggest unknown from the critical path; the sibling-driver structure honors §8-13 |

### 0.2 What was taken from which

- **C is the architectural spine**: the IP-faithful compat arrangement (post-main anchor;
  snapshot → full-pipeline dest render INTO MAIN → depth-tested portal-area stamp →
  blit-back; stencil-free; ONE sequential deferred buffer; the sibling full-pipeline driver
  that excludes the decomposed core's manual machinery; the B4 null-bracket kept verbatim;
  the layer-0 fallback D23; the hazard-ledger mapping; the qouteall-side flag placement
  that survives S20).
- **B supplies**: the IS0 recon census at javap level (P-A1/P-B1/P-PASTE/P-CLIP-UP/P-DIM);
  the P-B2 one-shot SOURCE-world hybrid probe (the engagement's highest-information probe,
  run BEFORE any renderer code); the mod-namespace shader-asset immunity mitigation
  (applied unconditionally); the clip-binder candidate ladder re-ranked by our substrate's
  landed uploaders (with the per-pass-freshness disqualifier for CustomUniforms); the
  dest-pipeline pre-warm contingency via the PRE_GAME_RENDER_TASK_LIST one-shot; the cost
  envelope statement.
- **A supplies**: the staging discipline artifacts — pre-registered failure discriminators
  on every probe and live-round leg, the lever/notice evolution per stage, rollback = lever
  off at every stage, the default-flip recommendation AT the clip stage (the C2-2 sodium
  precedent: default-ON came WITH clipping), the explicit "suite cannot exercise iris"
  honesty clause at every READY, worktree naming.

### 0.3 THE CENTRAL MECHANISM VERDICT — (beta), in IP's OWN compat-renderer arrangement

**Verdict: (beta).** The dest world renders through the ACTIVE iris pipeline via a DIRECT
8-arg `LevelRenderer.render(...)` full-framegraph call on the per-dim WORLD_RENDERER_MAP
secondary, inside the UNCHANGED `renderWorldNew`/`switchAndRenderTheWorld` bracket, at a
POST-main anchor (IP's `onBeforeHandRendering` slot re-expressed), drawing INTO THE MAIN
TARGET (already snapshotted), then a depth-tested portal-area stamp into the snapshot
buffer and a final blit-back. Stencil-free. One recursion layer. Evidence chain:

1. **IP's mechanism (tracer C §1A, verified against the held source in-tree,
   `IrisCompatibilityPortalRenderer.java`)**: AFTER the finished main frame
   (onBeforeHandRendering = after LevelRenderer.renderLevel returns, i.e. after iris
   finalized and wrote the main target): (a) snapshot main depth+color into ONE deferred
   buffer; (b) per portal: occlusion-test → pushPortalLayer → render the dest world
   through the FULL active iris pipeline INTO THE MAIN TARGET (iris's renderLevel-scoped
   hooks re-enter naturally — IP shipped this with ZERO iris mixins) → popPortalLayer;
   (c) stamp ONLY the portal-shaped area of the main-target image into the deferred buffer,
   depth-tested against the SNAPSHOT depth (= occlusion correct); (d) blit the deferred
   buffer back to main. The shape's genius: it never cares where iris's intermediate
   targets live — it needs only iris's presentation contract ("the final image lands in
   the main target").
2. **The 26.2 nested-render driver**: `MyGameRenderer.renderWorldNew` is LIVE
   (MyGameRenderer.java:214-476) but drives the DECOMPOSED `renderDestWorld` — the S13-H
   verdict in-code at :395-401 (a bare recursive `gameRenderer.renderLevel` re-renders the
   already-extracted MAIN state; nested-framegraph blanking; shared public-final
   `GameRenderState.levelRenderState`). So IP's "full render" re-expresses as the
   block-era-PROVEN direct call: `destRenderer.render(GraphicsResourceAllocator.UNPOOLED,
   deltaTracker, false, destCameraState, destViewMatrix, destFogBuffer, fogColor, true)` —
   the 8-arg signature verified working at FBO_PRECEDENT_MINING §1.4 step 8h (PCS:1974-1983),
   sidestepping the shared-GameRenderState disqualifier exactly as the block era did (the
   dest CameraRenderState is an argument; the LevelRenderState is the secondary's own field).
3. **Iris hooks fire on secondaries**: Iris 1.11.2 mixes into
   `net.minecraft.client.renderer.LevelRenderer` by CLASS (091 map: MixinLevelRenderer
   takes the 8-arg signature and builds FramePasses "main"/"iris_pre_translucent") — class
   weaves fire on ANY instance, including WORLD_RENDERER_MAP secondaries. This is the 26.2
   re-expression of "iris's renderLevel-scoped hooks re-enter" — IP's actual mechanism.
4. **The substrate is structurally ready**: each secondary LevelRenderer has its OWN
   FeatureRenderDispatcher over its own RenderBuffers (ClientWorldLoader.java:92-107,
   install :739-748, promote/demote re-point :1043/:1127) — a full render() on a secondary
   does not collide with the main dispatcher's PreparedFrame; at the post-main anchor the
   main PreparedFrame is closed anyway.
5. **Self-healing by construction**: because the shape snapshots the finished main frame
   FIRST and restores it LAST, any main-target clobber by the dest render is erased. What
   is NOT self-healing — iris cross-frame state (shadow targets, temporal history) — is
   IP's own shipped gap (ShadowMapSwapper dormant end-to-end, tracer B FILE 5 / tracer C
   §1D): named inherited limitation D21.

**(alpha) — decomposed dest pass with iris left active: REJECTED as a product shape**
(kept as one IS0 probe leg). With a pack active, ALL sodium terrain programs are
iris-patched via `TransformPatcher.patchSodium` (port-note §3.12 P11: patchSodium ←
ShaderCreator ← IrisRenderingPipeline ONLY), and `MixinShaderManager_Overrides`
substitutes iris GlPrograms for vanilla RenderPipelines whenever the global pipeline is an
IrisRenderingPipeline. Our hand-driven Step-10 draws (mid-main-framegraph, from the
AFTER_TRANSLUCENT_TERRAIN driver) would run iris MRT programs with NO ClearPass/
beginTranslucents/finalize lifecycle scoped to them, with main-frame uniforms, writing
into iris-owned gbuffer attachments BETWEEN iris's own passes — the output either corrupts
the main frame's deferred composite or never enters the presented image's dataflow. Also
mining §7.2-A: "Iris owns its pipeline; it cannot be hand-driven that way." The IS0 probe
(program-id + GL_DRAW_FRAMEBUFFER log during one levered decomposed draw under a pack)
documents this empirically instead of leaving it asserted.

**(gamma) — vanilla programs under the B4 null-bracket: REJECTED AS INFEASIBLE**, not
merely inferior — two independent kills (B and C convergent, A concurring): (i) the B4
bracket nulls only the LevelRenderer instance FIELD `pipeline`
(MyGameRenderer.java:304/:356/:463, verified this session), while
`MixinShaderManager_Overrides` gates on the GLOBAL
`Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline`, which
stays true for the whole pack-ON session (nulling it mid-frame = destroyPipeline = the D8
timing hazard); (ii) while a pack is active, sodium's ONE compiled terrain program set IS
the patchSodium-patched set (`versionCounterForSodiumShaderReload` rebuilds it on every
pipeline change) — an unpatched program set does not exist concurrently. "Unshaded views"
would require surgery outside every ledgered seam, for a worse product. The honest floor
is the already-shipped D8 pass-through. Gamma re-enters ONLY if the IS0 probe shows a
usable image (e.g. gbuffer0 ≈ lit albedo) AND the user elects it — pre-registered, not
planned (D22).

### 0.4 Resolved conflicts (each settled with file-level evidence)

1. **Anchor: pre-main (A) vs post-main (B/C) → POST-MAIN.** The compat shape REQUIRES the
   finished main frame to snapshot — which exists only post-main (tracer C §1A step 4:
   onBeforeHandRendering fires "after full LevelRenderer.renderLevel, before hand").
   Mining §8-1 forbids only MID-main-framegraph nesting; post-renderLevel is equally
   outside it. The block era's phase-1(pre-main)/phase-2 split existed because ITS paste
   needed the mid-main STENCIL mask (mining §1.0: "the composite needs the stencil mask,
   which only exists in phase 2"); the stencil-free IP shape has no such need, so both
   halves collapse into the one post-main slot — IP's own arrangement. A's pre-main
   variant additionally forces the offscreen-FBO routing unknown (its Q2) onto the
   critical path; rejected.
2. **Dest-render target: offscreen per-portal TextureTarget via ip_setFrameBuffer (A) vs
   main-target clobber-and-restore (B/C) → CLOBBER-AND-RESTORE.** IP renders dest INTO
   MAIN deliberately (tracer C §1A: "nested world into MAIN fbo"); this needs only iris's
   presentation contract and is immune to iris's internal target routing. Whether the
   proven `ip_setFrameBuffer` swap (RendererUsingFrameBuffer.java:104/:117, verified)
   would even redirect iris's final pass is UNKNOWN (OQ2) — under clobber-and-restore
   either answer ships; outcome-A (swap works) is demoted to a future optimization.
3. **Aperture mask: main-target stencil write + EQUAL(1) triangle (A) vs stencil-free
   depth-tested portal-mesh stamp (B/C) → STENCIL-FREE STAMP** (D20). This IS IP's
   `PORTAL_DRAW_FB_IN_AREA` contract (tracer C §1A: portal-shaped triangles sampling the
   main FBO at screen coords, depth-clamped), currently a documented A1 gap on 26.2
   (MyRenderHelper.drawPortalAreaWithFramebuffer:537-570 is a full-screen blit — verified).
   It dissolves the whole "who writes stencil under iris / does the main target even have
   stencil bits" question, and removes the S20 dependency A would have created on the
   block-era GlConstMixin/RenderTargetMixin stencil plumbing (that pair's S20 disposition
   belongs to the shaders-OFF stencil renderer's survivor audit — cross-noted, not ours).
   The mining §3-step-7 sliver-ring hazard was about stencil-write-vs-composite raster
   mismatch; here the stamp is the ONLY rasterization and occlusion is by depth — the
   class does not arise (a frame-edge halo is still a pre-registered IS1 discriminator).
4. **Hybrid structure: Step-10 mode flag inside renderDestWorld (B) vs sibling driver
   (C) → SIBLING DRIVER** (`renderWorldFullPipeline`), sharing the shell bracket verbatim
   and reusing the Step-1..7 PRODUCT family, with an explicit EXCLUSION list. Binding
   evidence: mining §8-13 — "any path that calls the real render() must NOT also drain";
   §5 — sodium's own LevelRendererMixin arms the ChunkSectionsToRender that render()
   builds internally; §7.2-B — a real render() gets real occlusion, the manual visible-set
   machinery must not be inherited. B's mode flag would have kept Step-5 drains and the
   Step-9 arm live on the full-render path. What the sibling KEEPS: the dest extract into
   the secondary's own LevelRenderState (26.2 splits extract from render — render() renders
   extracted state), the identity-guarded SOG delta feed (block-era FBO mode ran it too,
   PCS:1583-1594), dest CameraRenderState/viewMatrix, the standalone fog buffer + all
   three fog layers, the Globals-UBO discipline, the per-dim lightmap. What it EXCLUDES:
   the compile-queue drain (render() drains; double-drain = never-finishing compiles),
   `ip_armDestChunkRenders` (sodium self-arms the internal build), armed
   VisibleSectionDiscovery / manual visibleSections population, the SOG-feed workaround
   family beyond the identity-guarded delta feed. The Fable lens carries a one-shot
   consumer pairing table (each one-shot consumer × which path consumes it, exactly once).
5. **Pipeline field during the nested render: install the active pipeline (B's branch) vs
   keep the B4 null bracket (C) → KEEP THE NULL BRACKET VERBATIM.** IP's own compat
   renderer ran under the IDENTICAL `setPipeline(worldRenderer, null)` bracket (tracer C
   §3.2: IP MyGameRenderer line 221/273 — "iris resolves the active pipeline via its
   global PipelineManager, keyed by the CURRENT dimension, not this field"; our re-sited
   bracket at MyGameRenderer.java:304/:356/:463 verified). The 091 map confirms
   PipelineManager is authoritative on 1.11.2. B's install branch is retained ONLY as the
   P-B1 contingency if the IS0 javap shows iris's 26.2-era render path reading the
   instance field.
6. **Dest-dim pipeline: force/pre-warm up front (B default) vs let iris select (C) → LET
   IRIS SELECT (D18), pre-warm as the pre-specced contingency.** Under the world-switch
   `mc.level` IS the dest level, so iris's dimension-keyed `pipelinesPerDimension` likely
   yields the DEST pipeline (better-than-handoff fidelity), possibly CREATING it mid-frame
   — a D8-timing-family hazard. OQ7's one-shot log decides; if creation hitches/crashes,
   B's pre-warm lands: a `PRE_GAME_RENDER_TASK_LIST` one-shot preparing the dest dim's
   pipeline (NAMED facade addition `preparePipeline(dimId)`, the C2-1 7th-method pattern),
   with that portal rendering pass-through until warm.
7. **Clip binder ladder → B's ranking.** (c) FIRST: the LANDED C2-2 uploaders
   (`GlCommandEncoderClipMixin` at trySetup RETURN + the sodium GLDrawContext uploader,
   both with location caches where loc=-1 no-ops) may ALREADY reach iris-patched programs
   if iris's terrain draws still flow through those seams with the iris program bound —
   zero new binder code; probe P-CLIP-UP decides. (b) SECOND: a small iris-arm uploader
   mirroring the same body at the javap-named iris bind seam. (a) LAST: iris CustomUniforms
   → terrain UBO (the 091 map's candidate), DISQUALIFIED if its update cadence is
   per-frame — the plane varies PER PORTAL PASS. A's "(b)-first" is materially the same
   as B's (c); C's undeveloped (a)/(b) superseded.
8. **Stage where the default flips → surfaced at IS3 close, decided by the user (Q-U1).**
   A's recommendation adopted: flip shaders-ON default from D8 dummy to the new renderer
   AFTER clip lands (the C2-2 precedent — sodium's default-ON came WITH clipping). The
   ladder ships lever-only until then, so nothing blocks on the decision.
9. **P-B2 probe timing: IS0 (B) vs first live round of the iris stage (C) → IS0.** The
   one-shot SOURCE-world hybrid render probe (same dim — no cross-dim variables; snapshot/
   restore; pack ON) answers the engagement's decisive unknown before any renderer code
   exists. C's I-1 sans-iris stage remains — it de-risks everything EXCEPT this.
10. **FBO lifecycle: per-portal pool (A) vs one sequential deferred buffer (B/C) → ONE
    BUFFER.** IP processes portals sequentially into one SecondaryFrameBuffer; the mining
    §8-19 per-portal-keying warning binds per-portal FBO POOLS (A's mechanism), not this
    shape. Plus the explicit world-exit/disconnect teardown the block era forgot (§8-20)
    and a pack-off/renderer-switch teardown.
11. **Gate placement → C.** The new activation flag lives qouteall-side
    (`IPGlobal.experimentalShaderpackPortalViews`, default false, OR'd with the JVM lever)
    — NOT in `ExperimentalCompatGate` (which S20 deletes per decision C2-5(b)); the S20
    sweep cannot strand it. `ExperimentalCompatGate.ENABLE_SODIUM_IRIS_COMPAT` (default
    true at HEAD) remains the master one-boolean rollback for the whole compat tier until
    S20.

---

## §1 STAGE LADDER

Worktree per stage (`git worktree add .claude/worktrees/<name> -b <branch> HEAD` + copy
gradlew/gradlew.bat/settings.gradle/build.gradle/gradle/). Branches: `iris-on/is0-recon`,
`iris-on/is1-driver-paste`, `iris-on/is2-iris`, `iris-on/is3-clip`, `iris-on/is4-closeout`.
EVERY stage: compile ×3 → 8-leg suite green (with the statement: the suite runs
sodium/iris-ABSENT and cannot exercise them) → Fable verify lens(es) → commit+push →
user live round closes the stage. Rollback at every stage = lever off → byte-identical D8
behavior. Every run config keeps the S19 CompileCommand-exclude vmArg
(PortalRenderInfo::renderAndDecideVisibility is the known Temurin JIT crash site). All
probe logging one-shot-latched or off-thread ≤1Hz (the log4j-stall rule).

### IS0 — Recon census + instrument probes + the inert post-main anchor (zero behavior change)

**Goal**: close every javap-answerable unknown and run the decisive instrument-first
probes BEFORE any renderer code; land the post-main driver anchor as a proven no-op.

**Deliverables (call-site level):**
1. **Static census** (javap/decompile the pinned iris-1.11.2 + sodium-0.9.1 jars + one
   mc262 read), appended to the port-note:
   - **P-A1**: iris MixinGameRenderer/MixinLevelRenderer + vanilla GameRenderer — the
     exact position of hand rendering vs `iris$endLevelRender`/finalize. Picks the anchor:
     candidate 1 = a new inject in `GameRenderer.render` at the renderLevel INVOKE
     shift=AFTER (C's, IP's handler-③ slot); candidate 2 = `renderItemInHand` HEAD (B's,
     if hand is a separate call after iris's tail hook); fallback = post-hand (named
     artifact D-DS6: hand invisible where a portal overlaps — stage-(a) accepted).
     NOTE the D8 record says iris's renderLevel-TAIL hook does "finalize + hand rendering"
     — if hand is INSIDE iris's tail inject, candidate 2 does not exist.
   - **P-B1**: does iris's render path read the LevelRenderer instance field `pipeline`
     (getfield) or resolve via `Iris.getPipelineManager()` (invokestatic)? Decides
     null-bracket-stands (expected, IP-precedent) vs the install contingency.
   - **P-PASTE**: `MixinShaderManager_Overrides` keying — pipeline-identity map vs
     shader-source id. (The mod-namespace asset mitigation is applied unconditionally at
     IS1 regardless — this probe verifies the immunity claim.)
   - **P-CLIP-UP (static half)**: do iris 1.11.2's sodium terrain draws still traverse
     sodium's GLDrawContext.setContext / blaze3d trySetup with the iris program bound?
   - **P-DIM**: `PipelineManager.preparePipeline`/`getPipelineNullable`/
     `pipelinesPerDimension` signatures + what supplies the dimension key.
   - **OQ6**: iris fabric.mod.json `depends` — sodium hard-dep? (settles the
     iris-without-sodium matrix row's existence).
   - **OQ5**: mc262 `RenderTarget.blitAndBlendToTexture` blend semantics (replace vs blend).
   - **Definedness guard scope**: one static read of the C2-2 residual guard's predicate
     (port-note §3.9(3)) — does it already suppress GL_CLIP_DISTANCE0 for iris-patched
     (not-our-patched) programs during dest passes, or is it sodium-id-scoped (→ re-arm
     the D10 bracket for hybrid passes until IS3)?
   - **patchSodium GLSL symbol diff**: the fresh 1.11.2 transformed sodium vertex source
     (entry points for the IS3 injection — NEVER trust the IP-1.8 yaml's
     iris_ModelViewMatrix/getVertexPosition names).
   - **§8-14 audit**: each WORLD_RENDERER_MAP secondary's LevelRenderState field is the
     identity its extractor writes (the renderer-vs-extractor divergence class).
2. **The inert anchor**: a NEW vanilla-targeting client mixin (qouteall config, zero
   com.warwa deps — S20-safe) at the P-A1-chosen post-main slot, calling
   `IPCGlobal.renderer.onBeforeHandRendering(passingModelView)` — the base hook is empty
   (PortalRenderer.java:111) and NO existing renderer overrides it, so it is inert by
   construction for every current renderer state. Gate-audited as a no-op.
3. **Lever-gated probe round** (`-Dseamlessportals.shaderpackViewsProbe`):
   - **P-alpha/gamma leg**: one commanded decomposed dest draw under a pack, logging
     GL_CURRENT_PROGRAM + GL_DRAW_FRAMEBUFFER binding (documents the alpha rejection
     empirically; a usable-image surprise re-opens gamma per D22).
   - **P-B2 (the decisive probe)**: ONE one-shot hybrid render of the SOURCE world at the
     anchor — snapshot → world-switch-bracketed direct `render(...)` on the source dim's
     secondary (or the installed renderer's secondary twin) → restore — pack ON, full
     stencil/clip neutralize, logging pipeline object identity + pass list.
   - **P-OQ4**: one-shot post-main main-target depth readback stats under a pack (is
     scene depth there for the occlusion query + the stamp's depth test?).

**Pre-registered P-B2 discriminators** (each maps to a design consequence): clean next
frame → GO. Main-frame corruption post-restore → restore hole (fix the bracket, re-probe).
Shadow/temporal flicker only → the D21 envelope, GO with notice. Crash inside an `iris$`
hook → anchor or pipeline-resolution wrong; the stack names which fork (P-A1/P-B1
re-decide). Canary/output never lands in main → iris's final pass target differs from the
presentation contract — STOP, re-design the stamp source (stamp FROM iris's final target),
consult the deferred-ladder deep-end facts.

**Verify**: Fable recon-vs-design reconciliation lens — every §2 fork must hold its probe
answer or a named live-only residual BEFORE IS1 starts. Suite green (proves the anchor +
probes inert at default).

### IS1 — The full-pipeline driver + portal-shaped paste substrate, proven SANS iris

**Goal**: prove the entire mechanism minus iris on plain + sodium installs behind the
lever: the sibling driver (direct 8-arg render() on secondaries inside the preserved
bracket, D16) and the snapshot → stamp → blit-back composite family (D20 — incidentally
closing the A1 full-screen-composite gap for renderMode=compatibility).

**Deliverables:**
1. `MyGameRenderer.renderWorldFullPipeline(worldRenderInfo)` — the sibling invoke inside
   the byte-identical save/swap/restore bracket of `switchAndRenderTheWorld` (shared via
   an extracted private driver or a strategy branch — implementer's choice; the CONSTRAINT
   is byte-identical restore ordering and zero drift of the decomposed path, suite +
   gate-audit-proven). Body per §2.1; the §0.4-4 exclusion list is normative.
2. `IrisCompatOn262Renderer` (package `qouteall.imm_ptl.core.compat.iris_compatibility`;
   the held `IrisCompatibilityPortalRenderer` stays untouched as the fidelity reference,
   gains a doc pointer) — all members per the §2.2 contract walk, including
   `debugModeInstance` (full-screen raw view — a first-class live-round diagnostic).
3. The paste family: `portalAreaSample` RenderPipeline + mod-namespace
   (`seamlessportals:core/...`) copies of the screenquad/blit_screen shader sources
   (P-PASTE immunity, unconditional); the straight-copy pass pair (main→deferred color,
   deferred→main full-screen) with the four block-era fixes (§2.3); depth snapshot via
   `RenderTarget.copyDepthFrom` (IPIrisHelper analog, verified live); replace the
   `blitAndBlendToTexture`-based copyColor/drawScreenFrameBuffer shells on THIS path
   (OQ5 — never edit the live stencil path's siblings).
4. The LevelRenderer-scope injection INVENTORY: every flag-ON mixin + fabric listener that
   fires during a REAL nested renderLevel (they never fired for decomposed dest passes) —
   each classified benign / guarded-by-isRendering / bracket-needed. Known members: the
   AFTER_TRANSLUCENT_TERRAIN driver (guard verified at SeamlessPortalsClientFabric:135-137
   — the hybrid makes it live for the first time flag-ON), PerEntityClipBracket's
   BEFORE_TRANSLUCENT_TERRAIN listener (classify), the MixinLevelRenderer family, sodium's
   LevelRendererMixin (wanted — the arm), iris's own (wanted — the mechanism).
5. `deferredBuffer` explicit world-exit/disconnect teardown (mining §8-20) + evict on
   renderer switch-away.
6. Selection: lever-only, and at IS1 selectable even shaders-OFF on plain/sodium installs
   (proof rows); default committed behavior byte-identical.

**Live round** (plain + sodium, lever ON, A/B vs the stencil renderer): portal view
correct and CLIPPED TO THE PORTAL SHAPE; frame-edge occlusion correct (nose-to-frame;
ultrawide window resize — the RenderArea trap); same-dim pair near water/glass (the
shared-RSM row is DECISIVE on the sodium install: render() under the D1 bracket +
sodium-self-arm); walk-through; save-relog; lever-off A/B. **Discriminators**: whole
screen shows dest world after the pass → blit-back/snapshot order; frame-edge halo →
stamp depth-compare direction (R5 reversed-Z); black window w/ fog → render() produced
nothing (extract/visibility supply) vs copy defect — split via debugModeInstance; main
flicker after portal frames → §8-13 double-consumption or an inventory miss; VRAM climb /
"Resizing Sodium terrain uniforms" spam → endFrame/UBM discipline breach. Plain-row
first-frame SOG-walk stutter (the §6.3 class) is a pre-registered ACCEPTED proof-round
artifact.

**Verify**: three Fable lenses — fidelity (member walk vs the held IP source, step order
vs tracer C §1A), defect (restore ordering under throw; the one-shot pairing table;
endFrame coverage audit of every newly-exercised buffer owner — §2.4-5), gate-audit
(lever-off byte-identical; zero edits inside D1/serial/repoint/endFrame code).

### IS2 — Iris engagement: shaders-ON views live behind the lever (D8 default retained)

**Goal**: the lever routes shaders-ON to the new renderer — one-layer portal views
through the ACTIVE iris pipeline, main frame intact, pack-toggle/save-relog clean.

**Deliverables**: the D8-EVO routing branch in `switchToCorrectRenderer`
(PortalRenderer.java:438-464): levered → `IrisCompatOn262Renderer.instance`
(renderMode=debug → debugModeInstance, IP-faithful); unlevered → today's dummy+notice
VERBATIM. `switchRenderer`'s deferred reloadPipelines one-shot (:479-495) rides UNCHANGED
and now covers new↔dummy transitions. Notice v2 (lever-on only): "[Seamless Portals]
Experimental shaderpack portal views (compatibility mode): one portal layer; terrain near
the portal plane may show through; some packs may flicker near portals; expect frame cost
with portals visible." The layer-0 fallback (D23): the new renderer's
`invokeWorldRendering` detects the no-snapshot layer-0 context (not inside its own
renderPortals — CrossPortalViewRendering:158-171 / GuiPortalRendering call
prepare/invoke/finish directly) and falls back to the decomposed `renderWorldNew`. OQ7
one-shot pipeline-selection log. FPS envelope measurement.

**Live round** (`-PirisRuntime=true` + Complementary Reimagined, lever ON):
1. pack ON → cross-dim portal shows a SHADED dest view within the portal shape (headline);
   first-frames pass-through while chunks build = accepted envelope.
2. same-dim pair near water/glass (the shared-RSM + shared-pipeline decisive row).
3. mirror portal; 4. scaled portal; 5. nested portal → inner shows pass-through, NOT
   corruption (one-layer honesty). 6. walk-through crossing both ways. 7. entities in the
   dest view (CrossPortalEntityRenderer stays iris-disabled — IP-faithful). 8. pack
   ON/OFF mid-session ×2 → no crash at the toggle frame (the D8-timing scenario with the
   new renderer in the rotation). 9. save-relog with pack on. 10. lever OFF relaunch →
   exact D8 (A/B). 11. shadow-pass sanity + temporal-artifact observation vs the D21
   envelope + FPS note. 12. plain + sodium-only + iris-no-pack regression legs (P11 leg
   included).

**Pre-registered discriminators**: window BLACK/garbage but main fine → iris output-target
mismatch (the P-B2 STOP class — stamp-from-iris-target redesign). Window shows SOURCE
world → dimension/pipeline selection (OQ7 log decides) or camera transform (the S13-H
class: WorldRenderInfo not consumed). MAIN frame corrupted after portal frames →
pipeline-state bleed (isRenderingWorld/isBeforeTranslucent class) → the pre-mapped
escalation is the FILE-1-analog duck from the deferred ladder (binds confirmed-alive),
NOT ad-hoc fixes. Crash inside iris → lifecycle intolerance → lever off (D8 intact),
escalate with the stack. Whole-scene next-frame flicker → beyond the D21 envelope →
escalate. Corruption exactly at the toggle frame → pipeline-lifecycle timing breach.
All-portals-never-render → occlusion query dead under pack (OQ4 degrade: drop the query,
render all in-range — perf-only, ledgered).

**Verify**: Fable lenses — fidelity (tracer C §1A step-for-step), defect (double-frame
lifecycle: double finalize, SystemTimeUniforms, isBeforeTranslucent; the reload
discipline; anchor-state leakage incl. the stencil-disable finally per the
CrossPortalViewRendering:170 precedent), and the flag/install truth table.

### IS3 — The clip transport under shaders + the default-flip decision

**Goal**: dest terrain front-clipped at the portal plane inside iris-patched sodium
programs; the definedness suppression retired for patched programs; the notice's plane
clause drops; then the user decision on the default flip.

**Deliverables:**
1. **Source injection**: `@Pseudo @Mixin(TransformPatcher, remap=false)`,
   `@Inject(method="transformInternal", at=@At("RETURN"))` — the 3-arg shape survives
   (091 map FILE 7) with `Parameters` at
   `net.irisshaders.iris.pipeline.transform.parameter.Parameters` (descriptor must name
   the new package). VERTEX entries on the sodium-terrain path (keying from the IS0
   patchSodium symbol diff), injecting OUR `uniform vec4 seamlessportals_ClipPlane;` +
   the view-space `gl_ClipDistance[0]` write (the D3 contract; `iportal_ClippingEquation`
   is dead — D14 carried). The C2-2 idempotence guard
   (`contains("seamlessportals_ClipPlane")`) reused as the double-patch defense —
   load-bearing across pipeline reloads, and vs the C2-2 sodium-seam patch (P11
   exclusivity asserted BOTH directions in the lens). Registered under the existing
   IPCompatMixinPlugin iris arm (the "Iris" substring, order-sensitive footgun honored) —
   the FIRST live iris mixin: D9 amendment, ledgered. A one-shot loud assert that the
   transform actually fired when a pack loads (the D7 loud-not-silent family — @Pseudo
   silently no-ops on drift).
2. **The binder**, per the P-CLIP-UP verdict, in ladder order: (c) the landed uploaders
   already cover it (zero new code) → (b) a small iris-arm uploader at the javap-named
   bind seam (mirror of the GLDrawContext uploader body incl. its location cache) → (a)
   iris CustomUniforms ONLY if per-pass freshness is proven (frame-cadence disqualifies).
3. Retire the iris-case definedness suppression (or the re-armed D10 bracket); drop the
   notice clause.
4. **The default-flip decision surfaced** (Q-U1, recommended: flip HERE — the C2-2
   precedent): shaders-ON default becomes the new renderer; the lever inverts to opt-out;
   renderMode mapping per D15; D8 dummy stays reachable via renderMode=none + the master
   gate. The ladder ships lever-only regardless of timing.

**Live round** (pack ON, lever ON): wall-embedded portal — backside must NOT show through
under shaders; nose-against-aperture both sides at grazing angles; crouch-cross both
directions; pack toggle + save-relog re-run; sodium-only + iris-no-pack clip regression
legs (P11 both directions). **Discriminators**: bleed unchanged → binder not reaching
(fall down the ladder); world-wide clip artifacts → plane fed outside portal passes
(latch/reset defect) or uploaded to non-terrain programs (location-cache scope); flicker
on pack reload → double-patch or stale location cache (versionCounter rebuild seam).

**Verify**: Fable lenses — fidelity (injected GLSL vs the IS0 symbol diff + the D3
space/name contract), defect (double-patch both directions, reload/toggle cycles, loc=-1
degrade, no-match no-op safety).

### IS4 — Close-out (USER CHECKPOINT)

**Goal**: full install-matrix proof on the final posture; user decisions; ledger + S20/C7
notes; the engagement closes with the deferred ladder updated.

**Deliverables**: (1) USER DECISIONS: the default flip (if not taken at IS3), renderMode
mapping (D15), final notice wording, lever retention (recommend KEEP until S20 — the
C2-5(b) precedent). (2) Per-dim pipeline final posture per OQ7 (pre-warm polish if that
branch fired: warm-on-portal-sighting, un-warm on dim unload). (3) FULL matrix round:
plain / sodium / iris-no-pack / iris+pack(final default), each across
create/cross/recurse-honest/same-dim/mirror/relog/toggle + the touched 12-point-finale
items. (4) Ledger close-out (D8-EVO, D15-D25, probe answers, discriminator outcomes);
deferred-ladder update (multi-layer IrisPortalRenderer shape; the Experimental/stencil
deep end + FILES 1/2/3 + the executeTranslucent re-anchor D12 — UNCHANGED, defer-dormant;
the outcome-A offscreen optimization; ShadowMapSwapper stays upstream-dormant; gamma's
re-entry condition). (5) S20_HANDOFF cross-notes: the new files' survivor status; a
zero-com.warwa-TYPES grep assert over the new renderer/driver/anchor (accessor INTERFACES
only where unavoidable, each ledgered for re-homing); the stencil-plumbing NON-dependency
of this renderer recorded + the pointer that shaders-OFF stencil-direct still needs that
pair's survivor audit; the embeddium≠sodium C7 line inherits the new presence gates.
(6) EXECUTION_PLAN + memory updates.

**Verify**: matrix round + suite + one Fable lens over the full gating truth table
(install × pack × lever × renderMode × flag) + the grep assert.

---

## §2 MECHANISM SPECS (implementation-ready)

### 2.1 The full-pipeline dest render (`renderWorldFullPipeline`)

Entry: the new renderer's `invokeWorldRendering(worldRenderInfo)` (inside its own
renderPortals only — else D23 fallback) → `MyGameRenderer.renderWorldFullPipeline` →
the PRESERVED shell bracket (identical save/swap/restore set incl.: virtual Camera +
`ip_resetState` + rotation + `tick()` probe-prime (:262-269); level/levelRenderer/
lightmap/particle/hitResult/noPhysics/renderBuffers-pool swap; the sodium D1 context swap
:353-354/:433 — the ledgered seam, UNTOUCHED; the B4 pipeline null/restore :304/:356/:463
— KEPT, IP-precedent; per-invocation projection locals + model-view push; the S18.2
try/finally) — with the INVOKE body swapped for:
1. Dest-side prep reusing the renderDestWorld Step-1..7 PRODUCT family: dest extract into
   the SECONDARY's OWN LevelRenderState (assert the featureRenderDispatcher is the
   isolated per-secondary one — ClientWorldLoader:748); dest CameraRenderState + dest
   view matrix; the STANDALONE per-call dest fog GpuBuffer + the three-layer fog
   discipline (§8-3); Globals-UBO per the 8f/8i recipe if updated (§8-5); per-dim
   lightmap via the shell.
2. `worldRenderer.render(GraphicsResourceAllocator.UNPOOLED, deltaTracker, false,
   destCameraState, destViewMatrix, destFogBuffer, destFogColor, true)` — INTO THE MAIN
   TARGET (no target swap; IP-deliberate clobber; iris's hooks fire on the secondary
   instance; sodium's LevelRendererMixin arms the internally-built ChunkSectionsToRender;
   iris finalizes there naturally — for the compat shape iris finalizing the NESTED
   render is the mechanism, not a hazard; the FILE-1/2 cancels stay D9-dropped).
3. EXCLUDED (normative, §0.4-4): compile-queue drain; `ip_armDestChunkRenders`; armed
   VisibleSectionDiscovery / manual visibleSections; any SOG feed beyond the
   identity-guarded delta feed. KEPT in the outermost finally: the UBM latch reset
   (`ip_onDestTerrainDrawsFinished` — shared-SWR same-dim frames), stencil re-assert
   neutralize, source setupFog re-run, fog field restore.
Frame-transient UBOs ride `closeFrameTransientUbos` at render TAIL; the C2-1d endFrame
walk (`endFrameOnSecondaryLevelRenderers`, MyGameRenderer.endFramePooled:192-212) already
covers the secondaries — RIDE, never edit; the IS1 lens audits that every NEWLY-exercised
buffer owner (secondary cloud/sky/feature resources under a full render()) is covered by
an existing TAIL walk or joins one via a NAMED addition.

### 2.2 The renderer contract (member walk — IP member → 26.2 disposition)

1. `prepareRendering()` (fires from the AFTER_TRANSLUCENT_TERRAIN driver, MID-renderLevel;
   selection live-proven under packs — the C2-4 notice printed from it): raw
   `glDisable(GL_STENCIL_TEST)` belt ONLY; the deferred-buffer prepare/clear MOVES into
   the post-main workhorse (no mid-framegraph target touches — §8-15 adjacency).
2. `onBeforeTranslucentRendering(modelView)`: `if (!isRendering()) passingModelView =
   modelView;` + stencil-disable — IP-verbatim (the held source :68-76). The isRendering
   guard is now doubly load-bearing: fabric level-render events RE-FIRE during the nested
   real renderLevel (unlike decomposed passes).
3. `onBeforeHandRendering(modelView)` — THE WORKHORSE at the new anchor, guarded
   `!isRendering()`: deferredBuffer.prepare (auto-resize to main RT) + reversed-Z clear
   (depth FAR = 0.0, the held source's G7 re-expression) → snapshot: depth via
   `copyDepthFrom`, color via the STRAIGHT-COPY pass (§2.3) → `renderPortals
   (passingModelView)` → final full-screen straight copy deferred→main (blit-back).
4. `doRenderPortal`: one-layer guard (`isRendering()` → return) → `testShouldRenderPortal`
   (renderAndDecideVisibility around a ViewAreaRenderer portal-area draw; OQ4 degrade
   pre-decided) → pushPortalLayer → `renderPortalContent(portal)` (base builds
   WorldRenderInfo: dest world, transformed camera pos, cameraTransformation,
   doRenderHand=false — unchanged) → popPortalLayer → depth-clamp bracket → THE STAMP
   (§2.3) → colorMask restore. Debug mode: full-screen raw view instead of the stamp.
5. `finishRendering()`: stencil-disable, IP-verbatim. `onHandRenderingEnded()` /
   `renderPortalInEntityRenderer`: empty (IP-empty). `replaceFrameBufferClearing()`:
   false (iris clears normally; wanted for the nested render).
6. Fields: ONE `SecondaryFrameBuffer deferredBuffer` (sequential, IP shape) + explicit
   teardown; `passingModelView`; `isDebugMode`/`debugModeInstance`.

### 2.3 The composite under shaders — stencil-free

**Nothing writes an aperture mask.** The stamp = draw the portal's view-area MESH (real
world position, through passingModelView + the current projection — the transform family
`ViewAreaRenderer`/`PortalRenderTypes.drawMesh` already proves live) INTO THE DEFERRED
BUFFER, depth-TESTED against the snapshot depth (reversed-Z compare direction derived at
implementation from the CUTOVER_SPEC §2 table; NO depth write), fragment sampling the
MAIN target's color at `gl_FragCoord.xy / vec2(w,h)` — IP's `PORTAL_DRAW_FB_IN_AREA`
re-expressed (D20; closes the A1 gap; the full-screen FBO + main projection = the 1:1
screen-space UV law, mining §3-8). New pipeline `portalAreaSample`, built as a
clone-with-changes of the proven portalCompositeBlit/screenquad family, with
MOD-NAMESPACE shader assets (`seamlessportals:core/...`) so `MixinShaderManager_Overrides`
cannot key them (structural immunity; P-PASTE verifies). Both straight-copy passes + the
stamp carry the four block-era paste fixes VERBATIM (mining §3/§8-7): 6-arg
createRenderPass with explicit full RenderArea on texture views (never a captured FBO id);
depth `Optional.empty()` on the copies / GEQUAL-no-write on the stamp; pass-bound
InSampler NEAREST clamp-to-edge; blend OFF + raw-GL backstops (applyPipelineState
short-circuits on lastPipeline). Depth-clamp bracket kept (CHelper.enableDepthClamp).
Everything runs inside the ONE post-main slot — after iris finalized the main frame,
before hand (or post-hand fallback DS6).

### 2.4 Sodium composition (per ledgered seam; nothing else touched)

1. D1 registry + widened swap + GLOBAL_PASS_SERIAL: engage at the existing bracket seams
   exactly as today (the compat renderer is just another renderPortalContent caller with
   (portalUUID, layer) identity). NO EDITS.
2. Repoint brackets: not needed — every hybrid op runs inside the bracket where
   mc.levelRenderer is already the dest renderer (call-time-resolution invariant).
3. The arm: sodium's own LevelRendererMixin wrap arms render()'s internal build (mining §5
   — block-era-proven); `ip_armDestChunkRenders` NOT called on this path.
4. setupTerrain: render() drives sodium's own flow; whether the explicit Step-9 drive is
   also needed in hybrid mode is a javap+lens item (decided inside OUR code either way).
5. endFrame walk + UBM latch + frame-transient UBOs: ride the landed TAIL walks (§2.1);
   the "Resizing Sodium terrain uniforms" log is the pre-registered regression signal.
6. Sync-only culling guard / D5 / #3: untouched; the hybrid's internal collection paths
   are the paths those mixins already serve.
7. Recursion: the hybrid render() re-fires AFTER_TRANSLUCENT_TERRAIN from inside the
   secondary framegraph — the driver's `isRendering()` early-return
   (SeamlessPortalsClientFabric:135-137) is load-bearing and lens-asserted.

### 2.5 Per-dim pipeline posture

Default: the B4 null bracket stands; iris resolves via PipelineManager keyed off the
CURRENT dimension — under the world-switch that is the DEST dim (D18 records which
outcome OQ7 observes). Contingency (only if mid-frame creation hitches/crashes): pre-warm
via a PRE_GAME_RENDER_TASK_LIST one-shot + `preparePipeline(dimId)` facade addition
(named, C2-1 7th-method pattern); un-warmed portals render pass-through (honest,
notice-free). ALL pipeline destroy/reload stays on the deferred one-shot pattern
(PortalRenderer.java:479-495) — the new renderer never calls reloadPipelines mid-frame.

### 2.6 The clip transport under shaders

Stage-(a) posture: dest terrain UNCLIPPED at the plane — IP's own supported
`enableClippingMechanism=false` degradation, bounded to the aperture by the stamp;
definedness per the IS0 guard-scope read (re-arm the D10 disable-bracket for hybrid
passes if needed; retired at IS3). Stage (b): §1 IS3 — TransformPatcher retarget
(source) + the (c)→(b)→(a) binder ladder, OUR name/space/store
(`seamlessportals_ClipPlane`, view-space, the com.warwa FrontClipping store — D3/D14
carried), idempotence-guarded, loud-assert on bind.

### 2.7 Cost/perturbation envelope (stated up front, not discovered live)

One FULL iris pipeline render per visible portal per frame (one layer; portal count
bounded by getPortalsToRender's existing distance/frustum discipline; a portal cap config
is an IS4-era lever if the round demands it): expect ~2x frame cost with one portal under
a heavy pack — IP compat mode's own class. Shadow pass re-runs per portal render (not
suppressible without iris mixins — deep-end territory). Temporal packs (TAA/auto-exposure)
may flicker near portals (D21, IP-inherited, in the notice; user-courted vs original IP
if disputed). Complementary Reimagined is non-temporal-heavy — the right reference.

---

## §3 OPEN QUESTIONS

### Probes (instrument-first; each with discriminator + stage)

| ID | Question | Discriminator | Stage | Default for autonomous execution |
|---|---|---|---|---|
| P-B2 | Does iris 1.11.2 tolerate a second full LevelRenderer.render() on a secondary per frame at the post-main anchor (double finalize, SystemTimeUniforms, isBeforeTranslucent, shadow/temporal state)? | The IS0 one-shot source-world hybrid probe; outcomes pre-mapped in §1-IS0 | IS0 | Proceed on clean or envelope-flicker; STOP+redesign on output-miss; escalate on crash |
| P-A1 | Where does hand rendering sit vs iris finalize — does a post-finalize pre-hand vanilla anchor exist? | javap iris MixinGameRenderer/MixinLevelRenderer + vanilla GameRenderer | IS0 | Candidate 1 (post-renderLevel INVOKE shift=AFTER); DS6 post-hand fallback named |
| P-B1 | Does iris's render path read the LevelRenderer `pipeline` field or resolve via PipelineManager? | Bytecode: getfield vs invokestatic | IS0 | Keep the B4 null bracket (IP precedent); install-contingency only on getfield evidence |
| OQ7/P-DIM | Which pipeline does iris select during the bracket (source vs dest-dim), and does cross-dim CREATE one mid-frame? | IS2 one-shot selection log + P-DIM signatures | IS0/IS2 | Let iris select (D18); pre-warm one-shot if creation hitches |
| P-PASTE | Can MixinShaderManager_Overrides substitute our hand-built pipelines? | javap keying + program-id log at our paste draw | IS0/IS2 | Mod-namespace shader copies applied UNCONDITIONALLY at IS1 (immune either way) |
| P-CLIP-UP | Do iris-patched terrain draws still traverse the landed uploader seams? | javap draw path + runtime program-id log | IS0/IS3 | Ladder (c)→(b)→(a); (a) disqualified unless per-pass cadence proven |
| OQ4 | Is main-target depth scene-valid post-main under a pack (occlusion query + stamp test)? | IS0 one-shot readback | IS0 | If unusable: drop occlusion query (perf-only) + mesh-only stamp confinement, ledgered |
| OQ5 | blitAndBlendToTexture: replace or blend? | mc262 source read | IS0 | Straight-copy passes built unconditionally at IS1 |
| OQ6 | Is sodium a hard dep of iris 1.11.2 (does the iris-without-sodium row exist)? | fabric.mod.json depends | IS0 | Expect yes; if the row exists it is pre-decided D8 dummy (compat needs the sodium tier) |
| P-α/γ | Empirical documentation of the alpha rejection (and gamma's re-entry check) | One levered decomposed draw under pack: program + draw-FBO log | IS0 | Rejection stands; gamma re-enters only on usable-image + user election (D22) |
| DEF-G | Is the C2-2 residual definedness guard's predicate scoped to cover iris-patched programs? | One static read | IS0 | If sodium-id-scoped: re-arm the D10 bracket for hybrid passes until IS3 |
| SD-ROW | Same-dim/mirror shared-RSM + shared-pipeline full-render convergence | IS1 sodium same-dim leg + IS2 same-dim/mirror legs with discriminators | IS1/IS2 | Structurally covered by landed D1; the live leg is the settle |

### USER decisions (each with the recommended default so autonomous execution proceeds and flags at the READY)

- **Q-U1 — default-flip timing**: flip shaders-ON default to the new renderer at IS3
  close (clip landed — the C2-2 sodium precedent). RECOMMENDED DEFAULT: flip at IS3;
  ship lever-only until the user confirms.
- **Q-U2 — renderMode mapping under shaders**: normal AND compatibility →
  IrisCompatOn262 (D15 — IP's normal = the unbuilt multi-layer renderer; IP itself
  shipped normal→compatibility auto-fallback), debug → debugModeInstance, none → dummy.
  RECOMMENDED DEFAULT: accept D15.
- **Q-U3 — final notice wording** at the flip. RECOMMENDED DEFAULT: the IS2 notice v2
  minus whatever IS3 retires, reviewed at IS4.
- **Q-U4 — lever/gate retention**: keep the lever + ExperimentalCompatGate until S20
  (the C2-5(b) one-boolean-rollback precedent). RECOMMENDED DEFAULT: keep.
- **Q-U5 — deep-end re-entry**: multi-layer (IrisPortalRenderer shape) and the
  Experimental/stencil trio stay DEFER-DORMANT after IS4; re-entry facts ledgered
  (deferred-ladder item 8, D12, FILES 1/2/3 binds). RECOMMENDED DEFAULT: stay deferred.

---

## §4 DEVIATION LEDGER (entries this engagement creates; numbering continues the C2 ledger)

- **D8-EVO**: shaders-ON routes to IrisCompatOn262 when armed; dummy+notice remains the
  committed default until each stage is live-proven; notice text tracks stage truth.
  [Preserved: switchToCorrectRenderer's shaders-ON selection restored in compat form.]
- **D15**: renderMode=normal under shaders maps to the compat renderer (multi-layer not
  built). [Preserved: compat IS one of IP's shipped shaders-ON modes and IP's own
  auto-fallback floor.]
- **D16**: the nested full-pipeline render = direct 8-arg LevelRenderer.render on the
  per-dim secondary (vs recursive gameRenderer.renderLevel). [Preserved: "one full world
  render through the active pipeline per portal per frame, iris hooks re-entering" —
  forced by the 26.2 extract/render split + shared GameRenderState; block-era-proven
  call shape.]
- **D17**: onBeforeHandRendering re-expressed onto a new post-renderLevel anchor mixin
  (the flag-ON driver never fired it). [Preserved: IP's handler timing — after the
  finished main frame, before hand.] Sub-case **DS6** (conditional on P-A1): post-hand
  paste → hand invisible where a portal overlaps; stage-(a) accepted.
- **D18**: pipeline during the bracket = whatever iris's own selection yields (OQ7
  records which); pre-warm via frame-boundary one-shot as the named contingency.
  [Preserved: IP predates per-dim pipelines — either outcome is in-contract; the handoff
  sanctions source-dim for stage (a).]
- **D19**: snapshot copies depth only (copyDepthFrom); IP's glCopyImageSubData carried
  stencil bits implicitly. [Preserved: the compat shape consumes no stencil — zero
  contract loss; the IPIrisHelper stencil-copy gap dispositioned CLOSED-BY-SHAPE.]
- **D20**: PORTAL_DRAW_FB_IN_AREA re-expressed as the portalAreaSample pipeline (portal
  mesh, screen-space UV, snapshot-depth-tested, mod-namespace assets); closes the A1
  full-screen-composite gap. [Preserved: portal-shaped, occlusion-correct stamping.]
- **D21**: iris cross-frame state (shadow maps, temporal history) perturbed by dest
  renders — IP-inherited shipped gap (ShadowMapSwapper dormant end-to-end upstream);
  stated in the notice; not a defect.
- **D22**: (alpha) and (gamma) rejected with the §0.3 evidence; gamma's re-entry
  condition = the IS0 probe showing a usable image + a user decision. [Honesty entry.]
- **D23**: layer-0 cross-portal-view/GUI invocations fall back to the decomposed render
  (no snapshot context exists there). [Preserved: strictly better than D8's nothing;
  full fidelity deferred.]
- **D24** (D9 amendment): the TransformPatcher mixin (IS3) is the FIRST registered live
  iris mixin; the plugin's iris arm goes live.
- **D25**: clip under shaders = seamlessportals_ClipPlane view-space via the
  TransformPatcher retarget + our uploader ladder (vs iportal_ClippingEquation /
  SodiumShader — mechanism deleted upstream). [Carries D3/D14.]

---

## §5 INSTALL/FLAG MATRIX + S20/C7 INTERACTIONS

Rows (flag-ON only; flag-OFF is block-era-owned until S20) × columns (lever OFF = the
committed default at every intermediate commit / lever ON):

| Row | Lever OFF | Lever ON |
|---|---|---|
| plain | stencil renderer, unchanged | IS1 proof rows may route the compat driver (A/B; first-frame SOG stutter accepted) |
| sodium-only | C2 default-ON tier, unchanged | IS1: compat driver under sodium (the decisive render()+D1+self-arm row) |
| iris-no-pack | shaders-OFF parity, unchanged (isShaders false — the new renderer unreachable) | same; P11 clip regression leg re-run per stage |
| iris+pack | D8 dummy + notice, EXACTLY today | IS2+: one-layer compat views (unclipped until IS3) |
| iris-without-sodium | expected nonexistent (OQ6); if installable → pre-decided D8 dummy | same |
| embeddium/NeoForge | C7-deferred; the embeddium≠sodium presence-site line inherits the new driver's gates — added to the C7 list | — |

Regression legs every stage: crossing, mirror, same-dim, recursion honesty (+ shaders-OFF
recursion), save-relog, pack-toggle both directions (the D8-timing scenario, now
exercising new↔dummy renderer swaps + the deferred reload), lever A/B.

**S20 (binding notes for the sweep):**
- The new renderer/driver/anchor/pipelines carry ZERO com.warwa TYPES (accessor
  INTERFACES only where unavoidable, each ledgered) — asserted by grep at IS4.
- The activation flag lives qouteall-side (IPGlobal family), NOT in
  ExperimentalCompatGate — the sweep cannot strand it; the anchor mixin is in the
  qouteall client config (survives; its flag gate collapses to always-true at S20).
- This renderer does NOT depend on the block-era stencil plumbing
  (GlConstMixin/RenderTargetMixin) — recorded as a NON-dependency; the shaders-OFF
  stencil renderer's need for that pair remains an S20 survivor-audit item (S20_HANDOFF
  cross-note, not this engagement's dependency).
- FBO_PRECEDENT_MINING.md is the knowledge carrier for the deleted block-era code; this
  design cites it, never the code.

---

## §6 HAZARD MAP (mining §8 + the S18/S15 classes → how the design forecloses each)

| Hazard | Foreclosure |
|---|---|
| §8-1 nested render mid-main-framegraph | Post-main anchor (framegraph closed); the nested render is itself one framegraph at depth 1 (one-layer guard + driver isRendering guard) |
| §8-2 stencil neutralize | Shape is stencil-free; raw disable belts in prepare/finish + the anchor's finally (the CrossPortalViewRendering:170 precedent) + Step-10.13's existing re-asserts |
| §8-3 fog ×3 | Step-6 family reused: standalone per-call GpuBuffer, shared fogData/fogType save/restore, source setupFog re-run in the outer finally; the 8-arg call carries destFogBuffer+color explicitly |
| §8-4 persistent GPU buffers | Frame-transient UBO ledger drained at TAIL (closeFrameTransientUbos) |
| §8-5 Globals-UBO ordering | The 8f/8i recipe replicated if updated; lens item |
| §8-6 endFrame every mod RenderBuffers | The landed C2-1d walk + pool endFrame ride unchanged; IS1 lens audits newly-exercised owners; "Resizing Sodium terrain uniforms" = the regression signal |
| §8-7 composite traps ×4 | Applied verbatim to the stamp + both copies (§2.3), raw-GL backstops included |
| §8-8 captured FBO ids / fabric FBO-0 | Texture views + createRenderPass only; the anchor is a mixin at a direct call site, not a fabric handler |
| §8-9 unbracketed setLevel/dirty under sodium | No new sites; anything surfacing gets SodiumRendererRepoint (the ledgered seam) |
| §8-10 poking the chunk graph | render() uses sodium's natural flow; cold start = pass-through + next-frame retry (the snapshot always backs the opening — never a hole) |
| §8-11 silent reflective init | No new reflection planned; any gets the D7 loud-assert pattern |
| §8-12 grid/store centering + SOG-walk stutter | Sodium owns terrain on every load-bearing row (iris requires sodium); the plain proof row's stutter is a pre-registered accepted artifact with the §6.3 protective pattern ledgered |
| §8-13 one-shot consumption pairing | THE structural rule: the sibling driver excludes drain/arm/manual-visible machinery; identity-guarded delta feed kept; lens pairing table required |
| §8-14 renderer-vs-extractor LRS divergence | IS0 static identity audit + the IS1 assert |
| §8-15 mid-pass uploads | Post-main is not mid-pass; render() manages its own staging; prepare/clear moved out of the mid-frame slot |
| §8-16 log4j stalls | All probes one-shot/≤1Hz/off-thread |
| §8-17 raw-GL persistence through renderGroup | Not relied on (no hand-driven draws in this shape) |
| §8-18 camera probe priming | The shell's newCamera.tick() — inherited |
| §8-19 per-portal keying | One sequential deferred buffer = IP's shape (no pool → the warning doesn't bind; noted) |
| §8-20 world-exit teardown | Explicit deferredBuffer destroy on disconnect/quit + pack-off/switch-away eviction |
| S18 layer-0 exposure class | D23: no-snapshot contexts fall back to the decomposed render; IS2 crossing leg proves no corruption |
| S15 mid-packet frames | The nested render rides the same frame the main render validated; no new packet-boundary surface (lens note) |
| D8 timing family | ALL pipeline destroy/reload via the next-frame PRE_GAME_RENDER_TASK_LIST one-shot (existing switchRenderer path + the pre-warm contingency) |
| Temurin JIT crash class | CompileCommand-exclude vmArg stays on all run configs (renderAndDecideVisibility) |
