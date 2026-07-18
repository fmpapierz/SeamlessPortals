# S14-C round 6 — the wedge verdict: ChunkCullingDebugRenderer's captured-frustum gizmos (S14.41)

**Status:** fix committed (skip-by-default + inverse A/B lever), Fable-verified (3× PASS:
mechanism / regression / weave — workflow `wf_6bce6f3d-449`; 2 comment MINORs + 1 residue catch
folded), awaiting the user's confirming run. The guard sweep (same workflow) audited every
block-era-gated mixin: NO third inert shared-state guard — the other ~10 gated mixins are
genuinely block-era render behavior (promote-bridge, cached-particle spawner, SOG-prime,
Sodium fog override, …) that correctly dies with the block era.
**Live attribution (user, round 5 lever session):** step 1 of the round-5 protocol FAILED (wedges
survived the particle fix) and **"only `debug_skip_extract_gizmos_enable` removed wedges"** — the
S14.40 insurance kit did its job: one session, exact attribution, no new code round.

## 1. The mechanism — every link source- or user-verified

1. **`SecondaryWorldRenderCore.java:388`** — the driver sets a captured frustum on the virtual
   dest camera (`seamlessportals$setCapturedFrustum(destFrustum)`), the deliberate 26.2
   re-expression of IP's setupRender HEAD-cancel: `LevelExtractor.extract` skips `applyFrustum`
   when `camera.getCapturedFrustum() != null`, keeping discovery-built visibleSections
   authoritative (and avoiding the SPIKE-R1 reversed-Z hang).
2. **`MyGameRenderer.java:301/:400`** — the shell swaps `gameRenderer`'s main camera to that
   virtual camera for the dest pass (`ieGameRenderer.ip_setCamera(newCamera)`), restoring after —
   IP's faithful camera swap.
3. **`LevelExtractor.extract` (26.2 `:207`)** — unconditionally calls
   `debugRenderer.emitGizmos(cullFrustum, camX, camY, camZ, pt)`; the dest extract therefore runs
   it every portal frame.
4. **`DebugRenderer`** registers **`ChunkCullingDebugRenderer` UNCONDITIONALLY** (outside every
   `debugEntries.isCurrentlyEnabled` gate), and its tail branch has **NO debug-screen gate**: if
   `minecraft.gameRenderer.mainCamera().getCapturedFrustum() != null` — which during the dest
   extract it IS, by links 1+2 — it emits **six frustum-plane quads at alpha 0.25, one color per
   plane: (0,1,1) cyan, (1,0,0) red, (1,1,0) yellow, (0,0,1) blue, (0,1,0) green, (1,0,1)
   magenta — plus twelve OPAQUE BLACK wireframe lines** (`ChunkCullingDebugRenderer.java:90-113`).
5. The gizmos land in the frame's thread-local collector, drain into the MAIN renderer's
   `renderThreadGizmos` (`LevelRenderer.java:989-997`), and draw in the main gizmo pass —
   **`pipeline/debug_filled_box` + lines: the EXACT pass labels the round-3 one-frame
   DrawCallTrace captured** in the main phase every frame. Translucent + depth-tested ⇒ they can
   only paint **far-depth pixels (sky/fog), never solid blocks**.
6. **The user's wedge description was literal the whole time:** cyan left sector / purplish right
   sector = the (0,1,1) and (1,0,1) plane quads; washed middle = overlapping alpha-0.25 quads;
   dark seams = the black wireframe; apex near top = the frustum's camera-origin corner; follows
   the camera = the virtual camera follows the player; only while a portal is in view = the only
   time the virtual camera (and its captured frustum) exists.
7. **Block-era precedent (the SECOND inert guard):** `DebugRendererPortalSkipMixin` fixed this
   same emission for the block driver ("ghostly camera diagnostic … rainbow frustum-quad +
   wireframe … NO debug-screen gate") — gated on `PortalContextSwitch.isRenderingPortal`, so
   **inert flag-ON**, exactly like `ParticleEnginePortalSkipMixin` in round 5. Two for two: the
   block era's substrate guards protecting vanilla shared state do NOT carry to the IP driver via
   their block-era gates. A full sweep for a third is running (workflow `wf_6bce6f3d-449`).

## 2. The fix (S14.41)

`MixinLevelExtractor_DestSubLevers`: the three gizmo-family handlers
(`DebugRenderer.emitGizmos`, `GameTestBlockHighlightRenderer.emitGizmos`,
`LevelExtractor.extractGizmos`) now **skip by default** during the dest-pass extract
(`isDestExtracting && !debugAllowDestExtractGizmos`) — this is literally the lever behavior the
user validated live. Inverse A/B lever **`debug_allow_dest_extract_gizmos`** restores the
corrupting vanilla emission on demand. The main extract is untouched (gate false outside the
Step-5 bracket), so F3/debug overlays work normally in the main view.

Skipping `extractGizmos()` additionally stops the dest extract **duplicating** client +
integrated-server per-tick gizmos into the portal view (verify-round correction:
`getPerTickGizmos()` is a non-consuming snapshot getter, so the main view never LOST gizmos —
the copies were wrong-dim diagnostics drawn inside the window; and the dest renderer's
`renderThreadGizmos` was drained by the cross-dim submit path, so no unbounded leak either).

**Verify-round catch (folded):** even with the S14.40 particle cancel, `destLRS.reset()` inside
the dest extract still ran `ParticleGroupRenderState::clear` on group-state refs RETAINED from a
demoted dim's prior main stint — the same shared accumulators the current main LRS references
(a one-frame main-world particle blank on first look-back after a crossing). Fixed: Step 5 now
drops the retained refs (empties the LIST, never calling their `clear()`) before the extract,
gated off under the particle A/B lever so vanilla corruption restores faithfully.

## 3. Round-5 reconciliation (particles)

The wedges were NOT the particle extract — step 1's failure refuted that attribution. The S14.40
particle guard **stays on its own merits**: the shared-accumulator corruption is source-proven
(`QuadParticleGroup.extractRenderState` returns the shared `particleTypeRenderState`;
`ParticlesRenderState.reset()` clears CONTAINED shared states) and its documented live symptom is
the block-era one — **main-world particles wiped while a portal view renders** — subtle at clear
noon, not the wedges. Its A/B lever `debug_allow_dest_particle_extract` remains for S15's entity
rounds (rain/particle scenes).

## 4. User protocol (one session)

1. Relaunch, portal in view → **wedges gone with no commands.**
2. `/imm_ptl_client_debug debug_allow_dest_extract_gizmos_enable` → **wedges return**;
   `_disable` → gone. Positive A/B, closes the defect.
3. Nothing else needed; the remaining sub-levers stay available if anything residual shows.

## 5. Ledger

- **S20 removal:** `debug_allow_dest_extract_gizmos` + `debug_allow_dest_particle_extract` + the
  7 remaining attribution sub-levers + `MixinLevelExtractor_DestSubLevers`'s lever plumbing (the
  two DEFAULT skips — gizmos + particles — must SURVIVE S20 as permanent substrate guards, or be
  re-homed; the block-era twins `DebugRendererPortalSkipMixin`/`ParticleEnginePortalSkipMixin`
  die with the block era).
- **Pattern rule:** every block-era mixin whose javadoc says it protects main-view/vanilla state
  during portal rendering must be audited for an IP-driver-context equivalent before S17's
  default flip (sweep in flight).
