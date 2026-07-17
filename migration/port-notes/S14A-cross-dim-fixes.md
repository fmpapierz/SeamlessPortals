# S14-A — the readiness-audit fix set (reconciled design)

The S14 readiness audit (9 chain-link tracers, Fable adversarial verify per link, Fable completeness
critic; full evidence in `migration/verify/S14-readiness-audit-digest.md`) produced 4 BLOCKERs +
6 MAJORs on the never-run-live cross-dim path. The critic's binding instruction: the six
render-identity findings were filed by multiple tracks with divergent prescriptions and MUST land as
ONE reconciled change set. This document is that reconciliation — every conflict is resolved here,
and the implementation follows this doc, not the individual track prescriptions.

## Audit verdicts (post-verification)

| Link | Verdict | Launch-gating findings |
|---|---|---|
| dimsync | READY | none (dynamic-dim resync leg is dead code — C1/S19-gated; NOTEs recorded) |
| chunkloading | READY | none — **C8 recheck: no plausible cause on the IP path**; R10 ticket shape IP-correct |
| packets | READY-in-substance | one MINOR (debugSynchronizers replay → S15/ledger), 3 NOTEs |
| teleport | DEFECTS | **B1** extractor never re-pointed at crossing; M-tail `gameRenderer.setLevel`; MINOR particles |
| drivercore | DEFECTS | **B1** (dup, superset prescription); M4 FeatureRenderDispatcher shared; M5 pooled-buffer capture |
| clientworld | DEFECTS | **B2** clock corruption (dup); M3 lateUpdateLight gate (dup); latent onLightUpdate identity NOTE |
| ticklight | DEFECTS | **B2**+**B3** (dups); M8 tickBlockEntities hoist |
| fog | DEFECTS | M6 rainFogMultiplier leak; M7 stale look-back lightmap |
| invalidate | DEFECTS | M9 dead `_onWorldRendererReloaded` cascade (keep the Fabric mixin itself deferred to C2/S18 with target `lambda$static$1`) |

Two auditor reports were placeholder stubs ("test"); their Fable verifiers re-traced both links from
scratch — coverage held (process rule 10 vindicated: the verifiers flipped 5 auditor verdicts).

## The reconciled fix set (conflicts resolved per the critic)

**FIX-1 (B1) — 26.2 extract-driver promote/demote at the flag-ON cross-dim crossing.**
Drivercore's superset shape, housed in `ClientWorldLoader.promoteAndDemoteOnPlayerDimensionChange`,
invoked from `ClientTeleportationManager.changePlayerDimension` after the `client.level`/renderer
swap. PROMOTE(toDim): raw `LevelExtractorAccessor` re-points on `mc.levelExtractor`
(levelRenderer→promoted, level→toWorld, lastViewDistance→effective RD [blocks the extract
allChanged mesh-wipe], sectionUpdateTracker→the per-dim extractor's current tracker); promoted
renderer's LRS→the shared `gameRenderState().levelRenderState`; promoted renderer's
featureRenderDispatcher→the MAIN `gameRenderer.featureRenderDispatcher()` (FIX-4 interaction);
`toWorld.levelExtractor`→`mc.levelExtractor` ITSELF (ClientLevelExtractorAccessor);
`WORLD_EXTRACTOR_MAP[toDim]`→`mc.levelExtractor`; clear SecondaryWorldRenderCore per-dim state;
re-prime: `clearVisibleSections()` + SOG `invalidate()` + `needsFrustumUpdate=true` (block-era
proven combination; IP's `vanillaTerrainSetupOverride=1`, already set by both teleport callers,
covers the first frame's terrain setup). DEMOTE(fromDim): fresh isolated `LevelRenderState`;
new per-dim `LevelExtractor` bound to it (raw level/tracker/lastViewDistance writes — NEVER
`setLevel()`; fresh `SectionUpdateTracker(fromWorld, effRD)`; `onResourceManagerReload`);
`fromWorld.levelExtractor`→that extractor; `WORLD_EXTRACTOR_MAP[fromDim]`→it; isolated
featureRenderDispatcher installed (FIX-4 registry); clear core per-dim state.
PLUS the teleport-track tail in the same patch: `client.gameRenderer.setLevel(toWorld)` (camera
level + cardinal lighting — side-effect-light on 26.2, no mesh invalidation) and the MINOR:
`particleEngine` swap via `ip_setWorld` (IP-verbatim), not the clearing `setLevel`.

**FIX-2 (B2) — ClientClockManager corruption + gameTime seed.** Shape chosen: HEAD-cancel inject on
`ClientLevel.tickTime` in the existing `MixinClientLevel`, gated on
`ClientWorldLoader.isClientRemoteTicking`: replicate `clientLevelData.setGameTime(getGameTime()+1)`
and cancel — skipping the shared `clockManager().tick()` (restores IP 1.21.3 per-level tickTime
semantics exactly). PLUS the load-bearing seed (ticklight part b): in `createSecondaryClientWorld`
after the ctor, `newWorld.setTimeFromServer(CLIENT.level.getGameTime())` — the 26.2 replacement for
the F1-deleted per-dim SetTime redirect; stays in lockstep afterwards (+1/tick, shared
TickRateManager). NO double-fix: the WrapOperation variant is NOT also implemented.

**FIX-3 (B3) — lateUpdateLight gate.** Clientworld's frame-END variant (the critic's explicit
choice): gate becomes `world != CLIENT.level` and the body runs vanilla's `update()` pairing
(`pollLightUpdates()` + `runLightUpdates()`) for every non-main world at frame end, OUTSIDE any
world swap. 26.2 rationale: vanilla's rendered-dim light pair moved out of the render into
`Minecraft.renderFrame → level.update()` (main level only), so IP's `!isDimensionRendered` gate
lost the half it complemented; frame-END for ALL secondaries restores IP's total invariant ("every
dim with a live view runs light updates every frame"). The in-pass variant is NOT implemented (it
would trip the latent `getWorldExtractor` short-circuit). Hardening in the same commit: route
`ImmPtlClientChunkMap.onLightUpdate` per the S13-H DEFECT-1 pattern (prefer `WORLD_EXTRACTOR_MAP`
with `RenderStates.originalPlayerDimension` keying the main short-circuit) so a future in-swap
light-run cannot mis-route.

**FIX-4 (M4) — per-secondary FeatureRenderDispatcher isolation.** In `createSecondaryClientWorld`:
`new RenderBuffers(4)` + 5-arg `FeatureRenderDispatcher` (ctor verified: mc262
FeatureRenderDispatcher.java:37-58; identical live call PortalWorldManager.java:584-590), installed
via the existing `seamlessportals$setFeatureRenderDispatcher` accessor. The renderer's own
`renderBuffers` field stays main-shared (IP semantics — IP passed `client.renderBuffers()` to
secondaries; the block-era's own-buffers variant is NOT copied for that field). Registry
`SECONDARY_FEATURE_BUFFERS` in ClientWorldLoader; per-frame `endFrame()` walk added to
`MyGameRenderer.endFramePooled` (already wired at GameRenderer.render TAIL flag-ON — memory
gpu-buffer-leak-endframe); disposed in `disposeWorldRenderer`. Promote/demote juggling per FIX-1.
This first-brings-live PerEntityClipBracket's multi-storage PassState machinery (statically
designed for exactly this — watch item, no code change).

**FIX-5 (M5) — pooled-RenderBuffers capture.** In `MyGameRenderer.switchAndRenderTheWorld`, skip
the DEST renderer's `ip_setRenderBuffers(pooled)` field swap while
`worldRenderer.sectionRenderDispatcher() == null` (first pass for a fresh secondary): the first
in-pass extract then constructs the SectionRenderDispatcher from the renderer's CONSTRUCTION
buffers (the main shared pool at full concurrency — IP's permanent arrangement) instead of
permanently capturing the transient `RenderBuffers(0)` 1-pack pool. The client-side
`ip_setRenderBuffers` swap is unchanged. Known accepted corner (documented, NOTE-grade): a
render-distance change mid-portal-view recreates the dispatcher under the pooled swap — transient,
self-heals on the next allChanged; pairs with the FIX-10 tracker re-read.

**FIX-6 (M6) — rainFogMultiplier bracket (CUTOVER_SPEC §3 item 3, finally implemented).** New
accessors (qouteall `mixin/client/accessor`): static `FOG_ENVIRONMENTS` on FogRenderer + instance
`rainFogMultiplier` on AtmosphericFogEnvironment (both fields verified declared-in-target). In
SecondaryWorldRenderCore Step 6, tightly bracket `fr.setupFog(...)`: save the outer value, install
the dest dim's stored value (per-dim map — IP's per-dim smoothing fidelity), run, store the updated
dest value, restore the outer value in a `finally`. Pulled INTO the pre-launch set because the S14
script's step 6 (`/weather rain` observed through the window) fires it directly (critic gap 2).

**FIX-7 (M7) — DimensionRenderHelper stale look-back lightmap.** Allocate
`renderState = new LightmapRenderState()` for EVERY helper (keep the main-dim Lightmap-identity
reuse in the ctor and the cleanUp identity guard); delete the `renderState == null` early-return.
Safety: the call-site gate `!isDimensionRendered(newDimension)` can never pass for the CURRENT main
dim (`isDimensionRendered(originalPlayerDimension)` is always true), so updateAndRender never
re-drives the Lightmap vanilla currently renders.

**FIX-8 (M8) — secondary block-entity ticking.** `newWorld.tickBlockEntities()` after
`tickEntities()` in `tickRemoteWorld` — the 26.2 re-expression of IP's `tickEntities` semantics
(26.2 hoisted the block-entity tail out; mc262 Minecraft.java:1797-1799 pairs them).

**FIX-9 (M9) — the reload cascade.** New mixin `MixinLevelExtractor_Reload`
(`allChanged` RETURN → `if (this == mc.levelExtractor) ClientWorldLoader._onWorldRendererReloaded()`)
— the planned api-map cascade retarget, replacing IP's swap-dependent Validate with the 26.2
main-extractor identity filter. IP's OTHER allChanged anchor (HEAD-cancel while
`WorldRenderInfo.isRendering`) is recorded as DESIGN-ABSORBED on 26.2 (allChanged is cheap-deferred
flag-setting; the S13-H per-frame tracker re-read rule covers the swap) — ledger entry added, no
port. The Fabric `InvalidateRenderStateCallback` mixin itself stays DEFERRED to C2/S18 with the
corrected `lambda$static$1` target + the javap-on-every-fabric-api-bump rule (verifier evidence).

**FIX-10 (NOTE, rides along) — in-pass tracker staleness.** Move the `sectionUpdateTracker` read in
SecondaryWorldRenderCore to AFTER Step 5's extract (immediately before the Step-9 arm).

## Commit plan (each gated on 3-loader compile + :common:test)

1. `S14.1 rung-2 pre-fix: secondary tick liveness` — FIX-2 + FIX-8
2. `S14.2 rung-2 pre-fix: light liveness` — FIX-3
3. `S14.3 rung-2 pre-fix: feature-render isolation` — FIX-4 + FIX-5
4. `S14.4 rung-2 pre-fix: THE CROSSING CUTOVER` — FIX-1 (promote/demote + tails)
5. `S14.5 rung-2 pre-fix: fog/lightmap/reload periphery` — FIX-6 + FIX-7 + FIX-9 + FIX-10

Then: static weave audit over every new/changed mixin (rule 2), Fable adversarial verify workflow
over the whole diff, test-script finalization (incl. the critic's script amendments: return
mechanism named, death-respawn pre-answer, dest-fog-radius backdrop pre-answer, PENDING-6 closed),
push, READY.

## The fix-verify round (post-implementation, 8 Fable agents — commits S14.6 + S14.7)

The adversarial verification of S14.1–S14.5 (per-commit verifiers + weave audit + return-crossing
gap audit + integration critic; full census in the workflow journal `wf_b30f6c28-27a`, summarized
here) caught **1 BLOCKER + 2 MAJORs in the fix series itself**, corrected in S14.6/S14.7:

- **BLOCKER (S14.6):** FIX-3's added `pollLightUpdates()` at frame end was WRONG — the 26.2 queued
  light lambdas resolve `this.level` through the LISTENER at execution time; a bare poll outside
  the swap applies dest nibbles to the MAIN engine and loses them to the dest. lateUpdateLight is
  back to IP's RUN-ONLY body (poll stays tick-side inside the full context swap). **Lesson
  recorded: the one deliberate step beyond IP's exact body in the whole fix series was the one
  blocker.** `ChunkLightLambdaGuardMixin` is load-bearing for the chunk-with-light path — S20 must
  not delete it without securing drain context.
- **MAJOR (S14.7):** cold promote (never-extracted dest renderer) would NPE — fixed with the
  cold branch (fresh tracker + direct `shouldInvalidateCompiledGeometry` write, deliberately NOT
  `allChanged()` which would TAIL-fire the S14.5 reload cascade mid-crossing).
- **MAJOR (S14.7):** `vanillaTerrainSetupOverride` was WRITE-ONLY on 26.2 (IP's setupRender
  consumer never re-sited) — re-sited as `MixinLevelExtractor_TerrainSetupOverride` at
  `applyFrustum` RETURN (inside extract, before the visibleSections consumption loop, so discovery
  feeds both compile queue and draw; conventional-Z cull frustum, I7-safe; IP gates preserved).
- **Hardenings (S14.6):** `isClientRemoteTicking` try/finally (the flag became load-bearing;
  documented 1-line deviation, block-era-proven form); Step-9 `viewArea` re-read (same §2.1
  identity class as FIX-10; FIX-9 widened its trigger to any reload with a portal visible).
- **Return-crossing gap CLOSED (PASS):** `complete_bi_way_portal` cross-dim leg is IP-identical
  end-to-end (reverse portal into the correct dest ServerLevel, dim-keyed watcher sync,
  self-routing PortalSyncPacket); `goback` works via the full cutover but is a designed
  position-snap escape hatch. Test script now names the canonical return path.
- **Truth-ups:** FIX-2b's seed scope corrected (the IP-verbatim `onSetTime` fan-out re-syncs
  secondaries every ~20 ticks; the seed covers the creation window — the audit digest's "never
  synced" line was too strong); FIX-5's "RD-change mid-pass recreates the dispatcher" accepted
  corner is mechanically impossible (recreation needs `shouldResetLevelRenderData`, set only by
  `setLevel`) — the guard is complete.
- The integration critic PASSED the five-commit union: FIX-1+FIX-4 lifecycle safe (idle endFrame
  no-op, close() scoped per-instance), no observable WORLD_EXTRACTOR_MAP incoherence window, the
  FIX-2 seed actively protects crossing clock continuity, FIX-7 lightmap identities can never
  alias mid-frame, the F3+A-after-crossing cascade chain is identity-correct, and the union is
  flag-OFF byte-inert (all surfaces plugin-gated `qouteall.*`; zero `com.warwa` files touched
  except the always-inert accessor row).

## Round 3 (final): S14.6/S14.7 verification → S14.9

The focused round (semantic adversarial + bytecode weave audit, 2 Fable agents) PASSED every
surface: the poll-removal, try/finally, viewArea re-read, cold-promote branch, and the re-sited
override consumer all re-derived correct; the new mixin + accessor row javap-verified against the
loom named jar (single applyFrustum overload, declared fields, no collisions — the block-era
flash-bridge's applyFrustum hooks are arm-unreachable flag-ON); union flag-OFF byte-inert. Three
convergent prescriptions landed verbatim as **S14.9**: (1) `armVanillaTerrainSetupOverride` —
the override now forces the current main renderer's SOG frustum update at both set sites,
restoring IP's same-frame consumption for SAME-dim teleports (the 26.2 anchor is event-driven;
IP's ran every frame); (2) warm promote explicitly clears the invalidate one-shot (mesh
preservation vs same-tick A→B→A); (3) a never-built demoted renderer gets the invalidate one-shot
(self-heals at first dest extract). READY declared at `8b7d62a`.

## Backlog entries this audit adds (→ ledger / task #9 / S15+)

- debugSynchronizers entity replay in `ip_updateEntityTrackingStatus` (MINOR, debug-only) — S15 or ledger.
- `EntitySync.tick` needsSync gate omission — IP-faithful; ledger entry (revisit S15).
- ChunkMap tick()-cancel ↔ applyChunkTrackingView-cancel hidden coupling — javadoc both mixins (S20 guard).
- Redirection wire codec first-live on dedicated server — exercise before S17.
- Reload-listener leak (per world-join, extractor+cloudRenderer) — post-S14 vanilla-accessor unregister.
- InvalidateRenderStateCallback landing (target `lambda$static$1`) + LevelExtractionEvents suppression — C2/S18.
- END-sky end-flash pass; fuse-view fog-distance transform re-attach (`writeFogSlice`); dest lightmap
  parity (boss darkening + per-tick flicker); remote tickWeatherEffects suppression — S18.
- Dest-fog-radius vs graduated loading backdrop = authentic IP (block-era smoothed-radius polish is a
  candidate approved-deviation for task #9 if the user prefers the old look).
- `DimIntIdMap.removeUnused` iterate-while-mutate (IP-verbatim latent, dead until DimLib) — S19/S20 glance.

Fix-verify round additions:
- Nested-layer (≥3-dim) FIRST creation captures the outer pooled RenderBuffers as construction
  buffers — IP-inherited byte-identically, unreachable at rung 2; S18 candidate (capture the true
  main RenderBuffers in initializeIfNeeded and re-point at creation under an active swap).
- `createSecondaryClientWorld` throw-path leak of the feature pipeline (crash-path only) — S20.
- Demote drops the outgoing dim's pending-but-uncompiled dirty marks (design-conformant,
  self-heals) — adopt-outgoing-tracker variant if live look-back shows stale meshes.
- FIX-6 per-dim rain-fog smoothing restarts at 0 per role flip (sub-second transient) — task #9 if
  exact IP smoothing continuity wanted.
- `isReloadingOtherWorldRenderers` latch (IP-verbatim, a throw mid-cascade disables cascades until
  relog) — S20 robustness glance, first-observable now that the cascade has a caller.
- Demote-created extractor not reload-registered (stale re-promoted SkyRenderer after F3+T while
  crossed) — fold into the reload-listener unregister backlog item.
- First promote of a never-main mod-created renderer draws one frame with NO sky pass — invisible
  at rung 2 (nether skybox NONE; boot renderer has sky); add a pre-answer to the S16/END script.
- PreparedFrame latch playbook line added to the S14 test script (swallowed prepare error =
  silent permanent entity loss for one dim with the pre-FIX-4 signature; relog clears).
- Crossed+death-respawn+F3-T chain drives a CLOSED boot cloudRenderer via the vanilla reload
  registration — fold into the reload-listener backlog + the respawn-residual chip.
- s14.2 verifier's flag-OFF premise (MixinClientLevel weaving flag-OFF) was WRONG — corrected by
  the integration critic (plugin skips all qouteall.* flag-OFF); the S17 flip audit must not
  inherit that premise.
