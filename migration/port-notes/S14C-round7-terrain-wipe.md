# S14-C round 7 — the far-walk terrain wipe: SOG loadedChunks net-drop (S14.42)

**Status:** fix + probe implemented, Fable verify round in flight (`wf_723f7b39-bf4`), awaiting
commit + the user's confirming run.
**Defect (user, rung-2 steps 5+7):** walk far from a portal (loaders collapse), return (portal
view perfect), cross → the promoted dim draws NO terrain (entities only), unrecoverable through
minutes of movement/rotation/200-block flights; relog fully fixes. The dim the player walked
inside keeps its terrain. Rapid crossings without the far-walk are always clean. Warm promote
logs fire normally at the wipe crossings; zero log trace otherwise.

## 1. Root cause (workflow `wf_20020335-d7c`: 3 tracers converged independently; adversarial
ranker verdict HIGH with every source link re-verified; 4 alternative candidates REFUTED with
citations, 1 MEDIUM amplifier, 1 LOW)

**The broken invariant:** vanilla flips a dim's chunk-delta double-buffer every frame
(`LevelExtractor.extract` is the ONLY flip caller), so one window can never hold both the unload
and the reload of the same chunk. A secondary dim's extractor runs only while its portal renders.

1. **Dormant window:** portal out of view → dest extract never runs → the window freezes; the
   away period's loader-collapse unloads write `removedLoaded`, the return re-arm's reloads write
   `addedLoaded` — same frozen window; chunks land in BOTH sets.
2. **Poison application:** the return look's dest extract flips; the Step-5 SOG feed applies the
   window; vanilla `SectionOcclusionGraph.updateLoadedChunks` = `addAll` THEN `removeAll`
   (SOG:406-409) → coalesced chunks NET-EVICTED from `loadedChunks` while `hasChunk` is true.
   Portal view stays perfect (dest draws gate on `hasChunk` via VisibleSectionDiscovery, never
   SOG.loadedChunks) — the poison is invisible until the crossing.
3. **The wipe:** warm promote → `sog.invalidate()` → `scheduleFullUpdate` CLONES the poisoned
   set (SOG:160-161) → the BFS seeds at the camera section (SOG:248-250) whose chunk is
   poisoned-absent → parked with NO propagation (SOG:271-272) → **empty octree** →
   `applyFrustum` yields empty `visibleSections` → `prepareChunkRenders` iterates nothing.
4. **Non-recovery is a stable loop:** an already-loaded chunk emits no future add event → the
   set never heals; empty `visibleSections` also starves the extract's dirty compile scan →
   nothing ever compiles; rotation makes it worse (each applyFrustum re-walks the empty tree).
   Relog heals via the join resend rebuilding the set. Entities keep rendering
   (`isEntityVisible` gates on viewArea mesh state, not the octree) — the exact signature.
5. **Amplifier (MEDIUM):** away-unloads reset ImmPtlViewArea meshes; the crossing's one-shot
   `vanillaTerrainSetupOverride` paints exactly one good frame before the SOG rebuild's empty
   tree takes over — the observed flash-then-blank.
6. **Refuted with citations:** emptySections corruption (emitter direction makes a solid section
   in emptySections impossible; worst case = one wasted compile), the identity-guard false-skip
   (deterministically impossible — buffer identities MUST alternate across the away gap; the
   guard *delivers* the poison, it can't cause it), the 30s flash-bridge expiry (block-era-only,
   `armPromoteBridge` unreachable flag-ON), dest-pass pre-poison ordering (fresh camera per pass;
   demoted dim's isolated extractor structurally can't touch the promoted SOG).

## 2. The fix (S14.42, `SecondaryWorldRenderCore`)

Restore the invariant at both ends:

- **`tickSecondaryDeltaPump`** (POST_CLIENT_TICK): drains every secondary dim's accumulating
  window each tick — apply (truth-resolved) + **clear IN PLACE, never flip** (a second flip
  caller would alternate buffer identities under the Step-5 feed's `lastAppliedDeltaWindow`
  identity guard → mis-skips: a new bug of the same class). Windows now stay ≤1 tick even while
  the portal is unrendered; a backward/never-re-viewed crossing can no longer hand the first
  post-promote main extract a coalesced window. No-SOG dims: drop the window (creation-time SOG
  rebuild derives loadedChunks from live storage).
- **`applyLoadedDeltasResolved`**: any chunk in added∩removed (only possible in an accumulated
  window) resolves by its CURRENT `hasChunk` state — the order-free semantics vanilla's
  one-frame windows get for free. Used by the pump (in-place) and the Step-5 feed (copy-on-
  intersection; the frozen LRS window backs the identity guard and must not be mutated).
- **emptySections leg unchanged** (vanilla order) per the ranker's benign proof.

## 3. Instrumentation shipped with it (NO-GUESSING kit, S20-ledgered)

- **`RenderChainProbe`** — self-arms on EVERY promote (~20s @1Hz): `visSec` / `rendered` /
  `ldChunks` / **`sogLoaded`** (the poison discriminator: `sogLoaded << ldChunks`) / `applyF` /
  `needsF` / extractor-renderer-LRS-level identity coherences / tracker+ext+ren identities.
- **`debug_dump_render_chain`** — one-shot dump switch for capturing WHILE a defect shows.
- Promote log enriched: `dispNull/sogNull/viewAreaNull/ldChunks` at the promote instant.
- New accessors: `LevelExtractorAccessor.getLevelRenderer`,
  `ClientLevelExtractorAccessor.getLevelExtractor`,
  `SectionOcclusionGraphAccessorMixin.getLoadedChunks`.

## 4. User protocol (after commit)

1. Fresh world, portal + bi-way. Walk 200+ blocks away, wait ~1 min (loaders collapse), return
   (view fine), **cross** → terrain should be present and stay present. Repeat the step-7 long
   walks both directions.
2. If ANY wipe recurs: `/imm_ptl_client_debug debug_dump_render_chain_enable` WHILE wiped, hand
   me the log — the `sogLoaded` vs `ldChunks` pair names the residual on the spot.

## 5. Ledger

- S20 removal: the probe + dump switch + the promote-log enrichment (keep the PUMP + RESOLVER —
  they are permanent substrate guards for the one-flip-per-frame invariant our secondary
  extractors inherently break).
- Watch item for the retest: the MEDIUM amplifier predicts the portal-cone terrain may compile a
  beat later than the rest after a far-walk crossing (self-healing with a healthy SOG) — expected,
  not a defect.
- Invariant (joins the 26.2 set): **any extractor that doesn't run every frame accumulates
  multi-frame delta windows that break vanilla's order-dependent set application** — every
  consumer of a possibly-accumulated window must be order-free (truth-resolved) or the window
  must be drained on a fixed cadence.
