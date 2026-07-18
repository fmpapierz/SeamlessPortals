# S15 — Bring-up rung 3: entity traffic + F2/F3 + gametest

Stage spec: `migration/EXECUTION_PLAN.md` §S15. Resume doc: `migration/S15_HANDOFF.md`.
Unified-path state at stage open (audited 2026-07-18): `Portal.SERVER_PORTAL_TICK_SIGNAL` →
`getEntitiesToTeleport` → `startTeleportingRegularEntity` → `teleportRegularEntity` is fully
ported and LIVE-PROVEN (the S14.47 capture logged this exact path attempting firework
teleports). Flag-ON exclusivity confirmed: `EntityMixin`'s block-era non-player detection
returns early on `isEntityPortals()` (D3 row 2), so the IP scan is the sole non-player
detector. Cross-dim non-player crossings recreate via `changeEntityDimension(..., true)`
(`restoreFrom` + same-id), vehicles via `teleportVehicleAcrossDimensions` (inline recreate).

## 1. F2 REGISTER ENTRY — attached-firework skip (reproduce-then-apply: PRE-COMPLETED S14.47)

**Register row:** EXECUTION_PLAN §Deviations F2 — "Attached-firework skip in the unified
crossing path", reproduce-then-patch class, owning stage S15.

**Hazard (current-mod-core §11.3):** IP's unified path teleports the ATTACHED elytra-boost
firework when its glued segment crosses with the player's. The `restoreFrom` recreate does not
persist `attachedToEntity` (no NBT), so an in-range attempt recreates the rocket DETACHED in
the dest — a vertical free-flight phantom at the player's emergence point. Under PLAYER REUSE
a second mechanism compounds it: the source-world orphan keeps a LIVE `attachedToEntity` ref
cross-dim, gluing to the player's dest-world coordinates and injecting elytra boost every tick.

**Reproduced on the PORTED path: 2026-07-18** (S14.47 live capture, user elytra-boost
crossing). Log-proven: `"Entity is too far to teleport FireworkRocketEntity"` rejection lines
= `ServerTeleportationManager.teleportRegularEntity` attempts on the attached rocket; the
in-range attempts produced the user-visible phantom ("a phantom rocket shoots out in front of
me"); the orphan half produced the `"[ImmPtl] Skipping collision calculation because entity
moves too fast"` stack spam (60+ block per-tick glue moves).

**Applied: 2026-07-18, commit `63174f6` (S14.47):**
1. Attached-firework skip in `ServerTeleportationManager.shouldEntityTeleport` — the SHARED
   decider, so one guard covers all three IP scan sites (per-portal tick scan, global-portal
   scan, colliding-portal scan).
2. `changePlayerDimension` orphan discard — pre-move `inflate(8)` sweep of the player's
   attached rockets in the source world (vanilla's "boost rocket is lost through a portal"
   outcome re-expressed under player reuse; attached rockets are invisible → zero visual).
3. New accessor `FireworkRocketEntityAccessor` (always-active mixin set).

**Verified:** Fable adversarial verify `wf_a1b18604-e2c` PASS; user-confirmed gone at S14
close (rung-2 sign-off 2026-07-18). S15 (d).4's "BEFORE" half is therefore satisfied by the
S14.47 capture; the (d).4 live step re-confirms the "AFTER" state only.

## 2. GAMETEST — entity crossing smoke (D4.6, re-run at every later stage)

`fabric/.../gametest/CrossingSmoke.java`, run via `gradlew :fabric:runCrossingGametest`
(new Loom run config, own run dir `runs/gametest-crossing`). The run config **seeds
`entityPortals=true`** into the run dir's `config/seamlessportals.properties` before every
launch (the flag is read ONCE at mixin bootstrap — no in-game mechanism can flip it) and
**suppresses IP's one-time `IPortalInitialScreen` splash** (`immersive_portals.json`
`initialScreenShown=true` — the client-gametest framework asserts a bare title screen and
the splash sits over it; first run failed exactly there). Test selection between the mod's
client gametests is `-Dseamlessportals.gametest.only=titlecard|crossing` (the framework
runs every registered entrypoint; each class no-ops unless selected).

Three server-asserted legs on the UNIFIED path, staged on an obsidian platform with the
nether dest ABOVE THE BEDROCK ROOF (y≈129 — flat, lava-free, no fall damage) and all dest
chunks forceloaded:
1. Same-dim item (no recreate) — transformed-position arrival.
2. Cross-dim item (recreate: `changeEntityDimension` restoreFrom + same-id) — nether
   arrival + source-side removal.
3. **F3 hurt-state carry** — `hurtServer`-damaged cow shoved through the cross-dim portal;
   the recreated cow must still report non-null `lastDamageSource` (raw field AND getter,
   so the 40-tick getter window can never mask the verdict).

**First full run 2026-07-18 03:50 (pre-F3-fix): legs 1-2 PASS, leg 3 RED** — the intended
reproduction (see §3):
```
[03:50:41] leg 1 PASS — same-dim item arrived at (100.5, 250.0, -5.5)
[03:50:41] leg 2 PASS — cross-dim item recreated in nether at (0.5, 129.5, 0.5)
[03:50:42] leg 3: cow hurt (lastDamageSource=generic) + shoved at (4.5, -60.0, -3.5)
[03:50:42] leg 3: recreated cow at (0.5, 128.0, 0.27) in minecraft:the_nether;
           lastDamageSource raw=null getter=null
AssertionError: leg 3 (F3) FAILED: TRANSIENT HURT STATE DROPPED by the recreate
```

## 3. F3 REGISTER ENTRY — preserveTransientHurtState on the recreate branch

**Register row:** EXECUTION_PLAN §Deviations F3, reproduce-then-patch, owning stage S15.

**Hazard (current-mod-core §11.4):** `restoreFrom` is an NBT round-trip; transient fields
reset on the recreated entity. `lastDamageSource`/`lastDamageStamp` is the `PanicGoal.
shouldPanic()` trigger — a shot animal stops panicking the instant it crosses cross-dim.

**Reproduced on the PORTED path: 2026-07-18 03:50** — CrossingSmoke leg 3 RED (§2 above;
committed 306ab5d): recreated cow reported `lastDamageSource raw=null getter=null`.

**Applied: 2026-07-18** — the block-era mod's paid-for fix
(`PortalTeleporter.preserveTransientHurtState`, in production since 2026-07-08) ported 1:1
into `ServerTeleportationManager` and called at BOTH `restoreFrom` recreate sites
(`changeEntityDimension` recreate branch + `teleportVehicleAcrossDimensions` — standing
rule: per-entity-type crossing behavior exists in EVERY crossing path). Copies:
`lastDamageSource` + `lastDamageStamp` (RAW — shared server gameTime, no rebase),
`lastHurt`, `hurtTime`, `hurtDuration`, `invulnerableTime`, and the brain `HURT_BY` memory
guarded by `hasMemoryValue` (unregistered-slot `IllegalStateException` on plain goal-AI
farm animals otherwise). Reuses the always-active `LivingEntityHurtAccessor` mixin
(FireworkRocketEntityAccessor precedent for block-era-set accessors in IP-side code). The
old fix's `setPortalCooldown(2)` tail is NOT part of F3 — IP's unified path has its own
1-tick `lastTeleportGameTime` valve.

**Green run: 2026-07-18 03:54** — `leg 3: recreated cow ... lastDamageSource
raw=DamageSource (generic) getter=DamageSource (generic)` → `leg 3 PASS`; `ALL LEGS PASS`,
exit 0.

**Fable verify `wf_ef9eb4e4-d63`: PASS, zero blockers.** Three minors + one correction, all
folded 2026-07-18:
- **Gametest damage-type minor (real catch):** `minecraft:generic` is NOT in the
  `panic_causes` damage-type tag — the staged cow proved the FIELD carry but would never
  actually panic even post-fix. Leg 3 now stages with `playerAttack` (the gametest player
  as attacker) so BOTH conjuncts of `PanicGoal.shouldPanic` are exercised end-to-end.
- `hurtTime` IS serialized in 26.2 (`"HurtTime"`, LivingEntity save/read) — its copy is
  redundant-but-harmless; the block-era "kept paired" comment restored so no future reader
  thinks it load-bearing.
- `Brain.setMemory` silently no-ops on an unregistered slot — noted as the extra safety
  margin behind the same-type assumption.
- **Correction (doc claim was too strong):** the copied `lastDamageSource`'s cross-dim
  entity ref IS dereferenced by vanilla consumers — `HurtBySensor.doTick` publishes it as
  HURT_BY_ENTITY (then SELF-HEALS by erasing a wrong-level ref), Axolotl.onStopAttacking /
  SculkCatalyst tolerate it — and vanilla keeps the same stale ref when an ATTACKER
  changes dimension without the victim crossing, so this is vanilla-equivalent staleness.
  "Never dereferences" replaced with "dereferenced only by self-healing/tolerant
  consumers" in the fix doc.

## 4. THE RECURSIVE-VIEW ENTITY GAP (watch-list §2 items 1+2) — MECHANISM + FIX

**The two user-spotted S14 defects:** (A) entities invisible in layer≥2 recursive portal
views (layer-1 cross-dim confirmed working); (B) the player cannot see their own body
through recursive portals (IP shows it; ours split-second at crossing only).

**Mechanism (trace workflow `wf_fb0f1fa8-a0e`, 4 Opus tracers + Fable design — PROVEN by
code reading, no probe-first needed): ONE root for both.** The dest-pass entity path is
gated on `sharedState` (dest LRS == main game-render-state LRS, i.e. dest dim == the
player's main dim) and NOTHING layer-shaped exists anywhere:
- Dest entity EXTRACT gated `!sharedState` (Step 5) and dest entity SUBMIT/DRAW gated
  `!sharedState` (Step 10.7/10.8 — the §6.1 "documented gap"). Terrain has no such gate —
  exactly the observed "world visible, entities gone" asymmetry.
- It PRESENTS as "layer≥2" because canonical A↔B recursion loops the layer-2 view back
  into the HOME dim → sharedState → zero entities. Code-made prediction (live-checkable):
  a layer-2 pass into a THIRD dim would show entities; a layer-1 SAME-DIM portal shows
  none even at layer 1 (matches the accepted S13-era same-dim gap). The S14 layer-1
  confirmations were all cross-dim windows.
- (B) is the same root plus a proven MUTUAL EXCLUSION: the render-yourself mechanism is
  ALREADY 1:1 ported and wired (MixinCamera isDetached force;
  `CrossPortalEntityRenderer.shouldRenderPlayerDefault` incl. `client.level ==
  player.level()`), but that condition is true EXACTLY in sharedState passes — the only
  pass class with no entity draw at all. The body-drawing gate and the entity-drawing
  gate could never both be open. The split-second at crossing = the transient frame where
  originalPlayerDimension briefly differs.
- Why the gap existed (not a naive bug): main entityRenderStates are consumed+cleared by
  the main pass, were extracted against the MAIN camera anyway, a full re-extract
  mid-frame corrupts one-shot trackers/particle accumulators (the S14 saga class), and
  the shared main FeatureRenderDispatcher's single PreparedFrame is open mid-framegraph —
  `PreparedFrame.begin` throws (26.2 FeatureRenderDispatcher.java:187-190).

**Fix (all in one commit, lever + probe included):** `renderPortalEntitiesSameDim` in
SecondaryWorldRenderCore — an ISOLATED entities-only re-extract under the portal camera
(invoked private vanilla `LevelExtractor.extractVisibleEntities` → scratch LRS; writes no
shared one-shot state) + the REAL `LevelRenderer.submitEntities` (every cross-portal mixin
anchor fires exactly as cross-dim) drained through a core-owned
FeatureRenderDispatcher/RenderBuffers(0)/SubmitNodeStorage trio (the PortalWorldManager
per-secondary isolation pattern), inside the armed 10.5 inner clip + live stencil. The
extract line itself IS the (B) delivery: vanilla's camera-entity check + the ported
isDetached force admit the LocalPlayer, positioned at its real home-dim location (IP's
nested pass does exactly this — vanilla entity rendering per pass, no player-specific
code, no layer gate). RenderBuffers registered in a NEW core-owned endFrame registry in
ClientWorldLoader (memory gpu-buffer-leak-endframe). Fade-gate mixin
(LevelRendererEntityVisibilityMixin) now also keys on `isDestExtracting`.

**26.2-forced deviations (fidelity ledger, from the design + verify):** F1 entities-only
isolated re-extract instead of IP's full nested renderLevel (shared-LRS extract/submit
split; full extract corrupts one-shot trackers — S14 invariants; same-dim block entities
+ particles remain omitted = pre-existing gap, S18 ladder). F2 core-owned dispatcher trio
(single reusable PreparedFrame per dispatcher). F3 invoked vanilla submitEntities (the
renderEntity duck is gone on 26.2 — maximum-fidelity option). F4 erd.prepare bracket
(26.2 shares one dispatcher; note below). F5 fade-gate keying carries the block-era
override to the flag-ON bracket.

**Fable verify `wf_b11fbd6f-f8a` (two lenses: render-state safety + mechanism/fidelity):
PASS both, zero blockers.** Folds applied 2026-07-18:
- **E7 made IP-faithful:** the initial forced-`true` bypassed vanilla's COMPILED gate too;
  IP (MixinLevelRenderer.ip_isChunkCompiled) preserves `compiled != UNCOMPILED` and skips
  only the fade. Reworked to the exact IP shape on 26.2 types (`getSectionMesh() !=
  CompiledSectionMesh.UNCOMPILED`, no fade term) — entities can no longer draw floating
  against the fog fill in uncompiled terrain.
- **Throw fence:** the catch now REPLACES sameDimSubmitStorage (a throw between submit and
  draw stranded nodes → one-frame ghosts next pass) and dead-latches the pass after 3
  swallowed throws (a stuck-open PreparedFrame otherwise throws every pass forever);
  cleanUp() resets the latch + swallow log per world session.
- **Comment corrections:** the finally's `prepare(mainCamera())` is a parity gesture, not
  a restore (ip_setCamera makes mainCamera() the portal camera; harmless — every extract
  prepares first; cross-dim has identical semantics); the lever restores pre-S15
  SAME-DIM behavior exactly but cross-dim frames may differ via the deliberately
  un-levered E7 keying (attribute cross-dim entity-pop deltas to E7); PreparedFrame throw
  cite fixed (:187-190); the accessor's "writes ONLY" claim qualified (idempotent
  setViewScale + tickCount==0 xOld re-writes).
- **Refuted minor (recorded, no change):** "mid-session flag-OFF flip strands un-fenced
  buffers" — the entityPortals flag is LOAD-TIME READ-ONCE (D3); it cannot flip
  mid-session, so the flag-gated endFrame walk runs on every frame the pipeline can
  possibly be used.
- **C4 rider (pre-existing, unchanged, MUST be said at the live test):** under Mechanism
  B (`ISOLATED_STORAGE_BRACKET`) tagged colliding entities are deferred into
  `drawBracketedEntitiesIfAny`, which has NO caller until S18 — flipping C4 to B makes
  portal-crossing entities vanish from dest passes (BOTH paths). Do not misread that as
  an S15 regression at the A/B.

**Live discrimination (the `sde=P:L:E:S:T` probe row, batched, in TeleportFlashProbe):**
(a) entities at layer 2 + own body visible → both closed, capture sde= as PASS evidence;
(b) still missing with E>0,S>0 → residue is draw-state → bracket with
`debug_skip_same_dim_entities` + `debug_skip_portal_entities`; (c) E=0 → per-entity gates
(shouldRenderEntityNow / doRenderPlayer / the E7 compiled gate) → existing entity levers;
(d) T=1 → read the one-shot swallow line. Lever: `/imm_ptl_client_debug
debug_skip_same_dim_entities enable` = pre-S15 same-dim behavior back in one flip.

**Honest risk statement (casual run):** VISUAL-ONLY expected; crash-class LOW and fenced
(new code runs only in previously-no-op sharedState branches, whole helper in the
one-shot-swallow + dead-latch shape; single-flip lever restores same-dim status quo).
Worst plausible visuals: own body with odd clip at extreme mirror proximity, a one-frame
doubled entity at a crossing transient, entity pop timing changes near fresh sections
(E7). Terrain/SOG/light/particles/cross-dim Step-5 extract untouched — the S14-closed
saga surfaces are structurally out of blast radius.
