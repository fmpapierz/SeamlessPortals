# S14-C round 7 — the far-walk terrain wipe: SOG loadedChunks net-drop (S14.42)

**Status:** fix + probe implemented; verify round 1 (`wf_723f7b39-bf4`) = FAIL, 3 BLOCKERs
FOLDED (S14.43); **verify round 2 (`wf_4089f91b-610`) = PASS×2 lenses** with one MAJOR residual
folded as the promote-time pre-resolve hardening (§0b); **hardening verify round
(`wf_c9c7c2e1-b00`) = PASS×2** (§0c) — S14.44 committed, READY issued, awaiting the user's
confirming far-walk run.

## 0. Verify round 1 catches (all folded — the round paid for itself)
1. **BLOCKER — join crash:** the pump lacked the `ClientWorldLoader.getIsInitialized()` guard;
   the login-iteration POST_CLIENT_TICK can fire before the first frame's initializeIfNeeded →
   `getClientWorlds()` Validate throws inside `Minecraft.tick`. Folded: the CollisionHelper
   guard pattern.
2. **BLOCKER — NPE on never-viewed dims:** the pump applied to SOGs whose viewArea/currentGraph
   are NULL (26.2 defers their creation into the FIRST extract); `updateEmptySections`
   dereferences viewArea unguarded. Folded: per-dim readiness gate (viewArea non-null).
3. **BLOCKER — the fix re-created its own poison on the STANDARD flow:** pre-first-view window
   accumulation is LOAD-BEARING — the first extract's `shouldResetLevelRenderData` consume runs
   `waitAndReset(null)` → `loadedChunks.clear()`, and the wholesale never-flipped window applied
   by the first Step-5 feed is the ONLY re-seeder. The pump was draining that history tick by
   tick → every pre-first-view chunk permanently under-included → the same wipe, now on
   approach-then-cross. Folded: the readiness gate also requires the reset one-shot CONSUMED;
   while not ready the pump **skips WITHOUT clearing** (pre-fix lifecycle preserved; the feed's
   resolver now applies the eventual coalesced wholesale window truth-correctly).
4. MINORs: the no-SOG "drop" branch's rationale was wrong (nothing in vanilla rebuilds
   loadedChunks from storage — `waitAndReset(null)` CLEARS it) — branch replaced by the
   skip-no-clear gate; the main-dim vanilla application (addAll-then-removeAll, no resolver)
   remains a quantified-low residual (needs a forget+chunk packet same-pos in one ≤1-tick
   window; the resend-suppression/ACK discipline doesn't emit that; ledgered as an S17
   hardening candidate: route `SOG.update` through the resolver via mixin).
## 0b. Verify round 2 (`wf_4089f91b-610`, re-run of the cutoff-killed `wf_1d640a79-2aa`) — PASS

Both lenses PASS; all three round-1 BLOCKER fixes independently confirmed present + correct
(gate one-shot-permanent; viewArea non-null ⇒ currentGraph non-null, set together at
waitAndReset, so the gated pump cannot NPE). Findings and dispositions:

1. **MAJOR (lens 2) — the COLD-PROMOTE residual is REACHABLE → FOLDED NOW as
   `preResolvePromotedWindow`:** a never-rendered dest dim (pump gate never opened) keeps its
   whole delta history in ONE window; that window is the SOLE loadedChunks seeder (cold promote
   uses raw writes → reset flag stays false; `invalidateCompiledGeometry` ends in
   `waitAndReset(non-null viewArea)` which PRESERVES the — empty — set) and the first
   post-promote MAIN extract applies it UNRESOLVED (LevelRenderer:271 → SOG:406-409). One
   collapse/re-arm cycle before a blind crossing net-evicts the portal-footprint arrival chunks
   → parked-BFS wipe on the cold path. NOT a fix regression (pre-S14.42 every secondary dim had
   this) and narrow (one rendered portal frame both de-colds the dim AND permanently opens the
   pump gate), but the hardening was verdict-evaluated CORRECT + ship-now-low-risk, closes the
   warm-instant residual too, and gives the live retest clean attribution → shipped: promote-time
   in-place truth-resolution of the toDim's CURRENT window (resolution-ONLY — no SOG application,
   no clear; the wholesale-seeder contract of round-1 BLOCKER 3 is preserved), called at the top
   of `promoteAndDemoteOnPlayerDimensionChange`; idempotent for same-tick double-crossings.
2. **MINOR (lens 2) — warm promote-instant residual re-confirmed LOW** (≤1-tick window; packet
   re-delivery emits NO pair — `replaceWithPacketData` on a valid chunk takes the in-place
   branch, no removed/added emission). Closed anyway by the same hardening.
3. **MINOR (lens 1) — propagation-queue accumulation on ready-but-unrendered dims:** the pump's
   `updateEmptySections` schedules propagation entries only vanilla's main path drains; bounded
   (dedup at drain via sectionToNodeMap identity), discarded at the next rebuild/promote —
   informational, no action.
4. **MINOR (lens 1) — latent lifecycle landmine (UNREACHABLE today):** any FUTURE flag-ON caller
   that re-arms `shouldResetLevelRenderData` mid-life while KEEPING the same ClientLevel would
   under-seed loadedChunks (pump already drained the pre-re-arm adds; the post-re-arm window is
   the only re-seed). Today's only armers: world creation + disposal (both safe). → S17 ledger:
   assertion or wholesale re-seed helper if a new setLevel caller ever appears.
5. **MINOR (lens 1) — never-ready dims accumulate monotonically by design:** bounded by DISTINCT
   chunks ever churned (LongOpenHashSet dedup), ~O(100KB)/10k chunks; nothing else reads the
   accumulating side. Accepted.

## 0c. Hardening verify round (`wf_c9c7c2e1-b00`) — PASS×2, 4 MINORs, no code change

Clean sweep on the load-bearing items: refactor bit-identical (incl. the copy-before-mutate
contract for the frozen Step-5 window), the hook mutates only current-side set CONTENTS via live
references (never flips/clears — identity guard + pump discipline untouched), nothing between
the hook and the first extract touches the delta sets, reset-consume → capture → application
ordering preserves the wholesale re-seed, warm promotes cost two isEmpty checks, and a repo-wide
grep found no other consumer assuming unresolved semantics. Residuals (both lenses converged,
ledgered not folded):

- **The sub-tick re-poison race (pre-existing class, strictly narrowed):** the hook resolves at
  the promote tick, but packets draining off the main-thread queue between that tick and the
  first post-promote extract's capture can append a reload for a chunk still carrying an
  accumulated `removed` entry — re-forming a pair vanilla nets to REMOVED-while-loaded. Provably
  never-worse than pre-hardening (the pair existed or re-forms either way); exposure shrank from
  the dim's ENTIRE accumulated history to this one tick→frame tail, reachable only by a re-arm
  reload landing in exactly that gap during a blind cold crossing. Full closure = resolving at
  the CAPTURE point (mixin at LevelExtractor's flip, :136-142) — the same mixin that would close
  the main-dim §5(a) residual; folded into that S17 ledger item.
- **Cold-path first-frame overhead from the unresolved emptySections leg:** every coalesced air
  section costs one wasted compile + a spurious propagation seed, one frame only;
  solid-marked-empty stays unreachable (addedE is air-only, removed wins). Benign, no action.

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
- **emptySections leg unchanged** (vanilla order) per the ranker's benign proof (unload emits
  removed for ALL sections, load emits added for AIR only ⇒ worst case one wasted compile).
- **`preResolvePromotedWindow`** (round-2 fold, §0b-1): promote-time in-place truth-resolution of
  the promoted dim's CURRENT window — covers the cold path the pump's readiness gate deliberately
  never drains, plus the warm promote-instant window. Resolution-only; the window still applies
  wholesale as the sole seeder.

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

- S20 removal: the probe + dump switch + the promote-log enrichment (keep the PUMP + RESOLVER +
  `preResolvePromotedWindow` — they are permanent substrate guards for the one-flip-per-frame
  invariant our secondary extractors inherently break).
- S17 hardening items (rounds 1–3): (a) resolve at the CAPTURE point — a mixin at
  LevelExtractor's window flip (:136-142) routing through the resolver — which closes BOTH the
  main-dim quantified-LOW unresolved-application residual (round-1 §0-4) AND the §0c sub-tick
  post-hook re-poison race in one move; (b) assertion or wholesale re-seed helper against any
  future mid-life `setLevel` re-arm of `shouldResetLevelRenderData` on a kept ClientLevel (the
  §0b-4 landmine, unreachable today).
- Watch item for the retest: the MEDIUM amplifier predicts the portal-cone terrain may compile a
  beat later than the rest after a far-walk crossing (self-healing with a healthy SOG) — expected,
  not a defect.
- Invariant (joins the 26.2 set): **any extractor that doesn't run every frame accumulates
  multi-frame delta windows that break vanilla's order-dependent set application** — every
  consumer of a possibly-accumulated window must be order-free (truth-resolved) or the window
  must be drained on a fixed cadence.
