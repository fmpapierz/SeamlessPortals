# EXCLUSIVITY LEDGER — the committed dual-driver contract

**Status: POPULATED + ENFORCED (S13-B WIRE 1).** Created per EXECUTION_PLAN §1 D3 and §3 S0(a) /
"Immediately-next actions" item 3. Lifecycle (D3): skeleton at S0 → **fully populated + ENFORCED at
S13 step 3, BEFORE the first flag-ON run** (every block-era driver gains its `!entityPortals` gate in
the SAME commit that registers the IP mixin set) → updated at S16 (structural suppression replaces the
`handlePortal` cancel) → archived at S20 (flag + gates + old system deleted). **The S13 gate columns
below are now filled (S13-B WIRE 1, consolidated in `migration/port-notes/S13B-flip.md §4`); commit
hashes land when the stage is committed.** WIRE 2 (S13 step 5) landed the D3 unconditional registrations
+ the entity-renderer seam (`migration/port-notes/S13B-flip.md §6`). WIRE 3 burned down the post-verify
defects P1–P4 — the flag-ON AutoConfig boot blocker (shipped functional autoconfig), the 2 silently-dead
`platform_specific` _MA mixins (ported + Fabric-only registered), the alt-dim-mixin S19 deferral
(ratified + doc corrected), and the NeoForge flag hard-gate (`migration/port-notes/S13B-flip.md §7`).

**The contract this ledger makes auditable** (D3; API_RISKS verdict's shared-mutable-seams /
one-driver-per-frame prohibition): *at no point do two portal drivers run in one session.* The
`entityPortals` flag is load-time (read once at mixin-plugin time by the KEEP'd
`SeamlessMixinConfigPlugin` — current-mod-core §8); flipping requires a restart. Flag OFF =
exactly the block-era driver set below runs. Flag ON (S13+) = exactly the ported IP driver set
runs; every row in §1 is suppressed at its named gate site. §2 and §3 apply in BOTH states.

Columns `S13 gate`, `S16`, `S20` are **transition columns — deliberately empty until the owning
stage fills them** (S0 spec: "with the S13/S16/S20 transition columns empty until those stages
fill them"). At S13 each gate cell records the gate site + commit; at S20 each removal cell
records the deletion commit.

All mod paths below are relative to `common/src/main/java/com/warwa/seamlessportals/` (loader
paths marked `fabric:` are relative to `fabric/src/main/java/com/warwa/seamlessportals/fabric/`).
**Every class named was verified present in the source tree on 2026-07-13**; entry-point line
numbers were read from source, not inferred.

---

## 1. Flag-ON-suppressed block-era DRIVERS (the D3 census)

Rows 1–17 are the D3 enumeration verbatim (EXECUTION_PLAN D3, first bullet list). Disposition
citations are to `migration/api-map/current-mod-core.md` (`core §N`) and
`migration/api-map/current-mod-render.md` (`render §N`).

**S13-B WIRE 1 enforcement (this population).** Gate mechanism = the load-time `entityPortals`
master switch (`com.warwa.seamlessportals.EntityPortalsFlag.isOn()`, exposed as
`SeamlessPortalsConfig.isEntityPortals()`). Every gate below is a PURE GATE — flag OFF (the shipping
default) falls through to the existing block-era code path byte-for-byte; flag ON early-returns so the
ported IP driver takes over. Commit column deferred (this stage does not `git commit`); the stage
record is `migration/port-notes/S13B-flip.md §4`. Verified by GREEN GATE (`:common`/`:fabric`/`:neoforge`
compileJava + `:common:test` 48/0/0/0).

| # | Driver | Path (verified) | Block-era entry point(s) = candidate gate site | Disposition | S13 gate | S20 |
|---|---|---|---|---|---|---|
| 1 | `LocalPlayerMixin` crossing detection | `mixin/client/LocalPlayerMixin.java` | client tick plane-crossing detection → `EntityPortalCollision.findPortalCrossing` (`LocalPlayerMixin.java:81`) | REPLACE-BY `ClientTeleportationManager.manageTeleportation` (core §9) | ✅ GATED — `!entityPortals` early-return at HEAD of BOTH injects (`seamlessportals$clientPortalCrossingCheck` + `seamlessportals$sprintKeeperTick`) | |
| 2 | `EntityMixin` server detection | `mixin/EntityMixin.java` | per-entity server-side detection → `EntityPortalCollision.findPortalCrossing` (`EntityMixin.java:139`). **Its `handlePortal` cancel (`:162-166`) is NOT suppressed — see §2** | REPLACE-BY IP per-portal scan (`Portal.SERVER_PORTAL_TICK_SIGNAL` → `getEntitiesToTeleport`) (core §8) | ✅ GATED — `!entityPortals` return in `seamlessportals$checkPortalCrossing` placed AFTER the §2 cooldown tick-down, BEFORE the ServerPlayer detection (cooldown tick-down + handlePortal cancel stay active both states) | |
| 3 | `PortalTeleporter` | `entity/PortalTeleporter.java` | non-player crossing executor; dispatches `SeamlessServerTeleport.performCrossing` (`PortalTeleporter.java:78`) | REPLACE-BY `ServerTeleportationManager.teleportRegularEntity` (core §2) | ✅ TRANSITIVE — sole caller is the gated row 2 (`EntityMixin:149`); idle flag-ON | |
| 4 | `ProjectilePortalHandler` | `entity/ProjectilePortalHandler.java` | `ProjectileMixin.java:22` + `ThrownEnderpearlMixin.java:23` (`handleProjectileTick`) — the gate lives in those two mixins | DELETE — IP unifies projectiles into the one regular-entity path (core §2); the two host mixins are themselves DELETE (core §8) | ✅ GATED — `!entityPortals` early-return at HEAD of `ProjectileMixin.seamlessportals$onTick` + `ThrownEnderpearlMixin.seamlessportals$onEnderPearlTick` (B8) | |
| 5 | `PortalManager` | `portal/PortalManager.java` | server singleton (`fabric: SeamlessPortalsModFabric.java:32`); per-tick pre-warm `tickPortalPreWarm` (`fabric: SeamlessPortalsModFabric.java:51-52`) | REPLACE-BY `NetherPortalGeneration` + `CustomPortalGeneration` (core §1) | ✅ GATED — the whole `SERVER_STARTING` + `END_SERVER_TICK` block-era registration lives in the `else` (`!entityPortals`) branch of `SeamlessPortalsModFabric.onInitialize`; never registered flag-ON | |
| 6 | `PortalDetector` | `portal/PortalDetector.java` | queued-formation drain once per server tick from `PortalChunkTracker.tick` (`PortalDetector.java:34`); client link-payload handler (`fabric: network/FabricPlatformHelper.java:242`) | REPLACE-BY the peripheral ignition chain (`MixinAbstractFireBlock_CVB` → `IntrinsicPortalGeneration`) (core §1, core §0.2) | ✅ TRANSITIVE (drain) via gated row 8; the queue filler (`PortalShapeFormMixin`) is gated at A1; the client link-payload handler is not registered flag-ON (`registerClientHandlers` in the gated `else` branch) | |
| 7 | `PortalTracker` | `portal/PortalTracker.java` | queried by rows 2/3/5/6 (registry scans) | REPLACE-BY entity-index queries (`IPMcHelper.getNearbyPortals` et al.) (core §1) | ✅ TRANSITIVE — all query callers (rows 2/3/5/6) gated; never scanned flag-ON | |
| 8 | `PortalChunkTracker` tick | `chunk/PortalChunkTracker.java` | `END_SERVER_TICK` (`fabric: SeamlessPortalsModFabric.java:44`); ACK handler (`fabric: network/FabricPlatformHelper.java:168`) | REPLACE-BY `ImmPtlChunkTracking` + `ImmPtlChunkTickets` + `ChunkVisibility` + `WorldInfoSender` (core §4) | ✅ GATED — `END_SERVER_TICK` registration in the `else` branch of `SeamlessPortalsModFabric`; ACK handler (`registerServerHandlers`) in the gated `SERVER_STARTING` | |
| 9 | `RedirectedPacketApplier` | `chunk/RedirectedPacketApplier.java` | payload enqueue (`fabric: network/FabricPlatformHelper.java:200`); budgeted drain per client tick (`fabric: SeamlessPortalsClientFabric.java:43-44`) | REPLACE-BY `PacketRedirectionClient.handleRedirectedPacket` (core §4) | ✅ GATED — the `END_CLIENT_TICK` drain is in the `else` branch of `SeamlessPortalsClientFabric`; the enqueue handler (`registerClientHandlers`) is also in that `else` branch | |
| 10 | `PortalEntityTracker` | `chunk/PortalEntityTracker.java` | `END_SERVER_TICK` (`fabric: SeamlessPortalsModFabric.java:45`) | REPLACE-BY vanilla tracking under the IP watch graph (`entity_sync` mixin family + `EntitySync`) (core §4) | ✅ GATED — `END_SERVER_TICK` registration in the `else` branch of `SeamlessPortalsModFabric` | |
| 11 | `RemoteBlockUpdater` | `chunk/RemoteBlockUpdater.java` | payload apply/applyBatch (`fabric: network/FabricPlatformHelper.java:282, :291`) | REPLACE-BY redirected vanilla block packets (core §4) | ✅ TRANSITIVE — its sender (A3 `LevelChunkSetBlockStateMixin`) is gated, and the apply handler (`registerClientHandlers`/`registerServerHandlers`) is not registered flag-ON | |
| 12 | `SeamlessClientTeleport` dispatch | `client/SeamlessClientTeleport.java` | per-frame `checkCameraCrossingPerFrame` from `GameRendererFrameCrossingMixin.java:33` (a §4 shared host); tick-path via row 1. Lifecycle `onDisconnect` (`ClientLevelMixin.java:153`) is teardown, not a driver — survives per core §9 | REPLACE-BY `ClientTeleportationManager` (core §5; two PORT-FORWARD (verify) sub-items recorded there) | ✅ GATED at the §4 pump host — `MinecraftFramePumpMixin` returns before `checkCameraCrossingPerFrame` flag-ON; tick-path via gated row 1. Teardown `onDisconnect` survives (both states) | |
| 13 | `SeamlessServerTeleport` dispatch | `entity/SeamlessServerTeleport.java` | `performCrossing` from `PortalTeleporter.java:78` + the `ClientPortalCrossingPayload` server handler (core §3) | REPLACE-BY `ServerTeleportationManager` (player half) (core §2) | ✅ TRANSITIVE — `performCrossing` via gated row 3; the `ClientPortalCrossingPayload` server handler is not registered flag-ON (`registerServerHandlers` gated) | |
| 14 | `StencilPortalRenderer` entry | `render/StencilPortalRenderer.java` | `renderPortals()` at `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN` (`fabric: SeamlessPortalsClientFabric.java:26-28`) | REPLACE-BY `renderer.PortalRenderer` + `renderer.RendererUsingStencil` (render §1.1) | ✅ GATED — Phase-2 `renderPortals()` registration in the `else` branch of `SeamlessPortalsClientFabric`; Phase-1 `prepareDestinationRender()` gated in `GameRendererPortalPrepareMixin`; per-frame `frameUpkeep()` gated at the pump host | |
| 15 | `PortalContextSwitch` entry | `render/PortalContextSwitch.java` | invoked from row 14's render pass (`PortalContextSwitch.java:76` documents the reentry guard) | REPLACE-BY `MyGameRenderer` + `render.context_management/*` (render §1.2; its 26.2 swap-set mechanics transplant INTO the ported shell) | ✅ TRANSITIVE — entered only from the gated row 14 render passes; `isRenderingPortal` stays false flag-ON | |
| 16 | `PortalWorldManager` entry | `client/PortalWorldManager.java` | five per-client-tick pumps: `advanceCompilePipelines` / `syncTimeToCachedLevels` / `tickRemoteWorlds` / `tickCachedParticles` / `evictUnboundedStores` (`fabric: SeamlessPortalsClientFabric.java:45-54`) + render-path lookups from rows 14/15 | REPLACE-BY `ClientWorldLoader` + `DimensionRenderHelper` (core §5; CRITICAL PORT-FORWARD: the 26.2 extractor/render-split plumbing re-derives into the port, core §11.1) | ✅ GATED — the five `END_CLIENT_TICK` pumps live in the `else` branch of `SeamlessPortalsClientFabric` (never registered flag-ON); the `GameRendererMixin` TAIL still calls `lateUpdateSecondaryLight`/`endSecondaryRenderFrames` but they iterate an EMPTY secondary-level set flag-ON (inert), while `endFramePooled` there is §3 substrate | |
| 17 | `CameraTransitionHandler` | `render/CameraTransitionHandler.java` | `tick()` at `GameRendererMixin.java:20` (HEAD — the excised half of the KEEP'd mixin, render §3.2) | DELETE — superseded by `TransformationManager.managePlayerRotationAndChangeGravity` (render §1.17) | ✅ GATED — `!entityPortals` early-return at HEAD of `GameRendererMixin.seamlessportals$beforeRender` (the TAIL `endSecondaryFrames` is §3 substrate, ungated) | |

### 1.1 Census ADDITIONS — REPLACE rows the D3 enumeration missed (⚑ flagged per S0 task)

Each row below is dispositioned REPLACE in the tables but absent from D3's driver list. "Transitive"
= suppressed automatically once its only callers (rows above) are gated; it still gets a ledger row
so S13 can verify the transitivity claim instead of assuming it.

| # | ⚑ Addition | Path (verified) | Why D3 missed it / gate treatment | Disposition | S13 gate | S20 |
|---|---|---|---|---|---|---|
| A1 | `PortalShapeFormMixin` | `mixin/PortalShapeFormMixin.java` | block-era portal-formation hook; enqueues formations drained by row 8 (`PortalShapeFormMixin.java:39`). Gating row 8 alone leaves the queue filling — **needs its own gate** | REPLACE-BY `MixinAbstractFireBlock_CVB` (core §8) | ✅ GATED — `!entityPortals` early-return at HEAD of `seamlessportals$onPortalLit` (no formations queued flag-ON) | |
| A2 | `NetherPortalBlockMixin` | `mixin/NetherPortalBlockMixin.java` | block-behavior hooks on the vanilla portal block; every hook dies with block portals — **needs its own gate** (portal blocks can still exist flag-ON pre-S16, see §2) | REPLACE-BY `PortalPlaceholderBlock` + breakable revalidation (core §8) | ✅ GATED — `!entityPortals` at HEAD of all 4 injects (`onEntityInside`, `onPortalDestroyed`, `cancelAmbientParticles`, `makeUnbreakable`) + the `getRenderShape` method-addition returns vanilla `MODEL` flag-ON | |
| A3 | `LevelChunkSetBlockStateMixin` | `mixin/LevelChunkSetBlockStateMixin.java` | block-update observation feeding row 11's send path + the prewarm probe — **needs its own gate** | REPLACE-BY IP tracking's native block sync (core §8) | ✅ GATED — `!entityPortals` early-return at HEAD of `seamlessportals$mirrorChunkBlockStateChange` | |
| A4 | `RemoteEntityApplier` | `chunk/RemoteEntityApplier.java` | client applier of the bespoke entity mirror; **transitive** — idle once row 10 (its only sender) is gated | REPLACE-BY redirected vanilla entity-packet handling (core §4) | ✅ TRANSITIVE — sender row 10 gated + its client apply handler (`registerClientHandlers`) not registered flag-ON | |
| A5 | `PortalParticleClip` | `client/PortalParticleClip.java` | called from the KEEP'd `QuadParticleGroupMixin.java:103`; flag-ON it queries an empty block-portal registry (inert but live). S13 must re-source the KEEP'd mixin's quad test from Portal entities (render §3.5) or gate the call | REPLACE-BY IP render-side clipping (`FrontClipping` + `CrossPortalEntityRenderer`) (core §5) | ✅ GATED (the call) — `QuadParticleGroupMixin.seamlessportals$cullBehindPortal` returns the vanilla frustum result flag-ON, skipping the block-era `PortalParticleClip` cull (IP owns render-side clipping) | |
| A6 | `PortalShapeRenderer` | `render/PortalShapeRenderer.java` | **transitive** — invoked only from rows 14/15 (`StencilPortalRenderer.java:306,:333,:383,:447,:455`; `PortalContextSwitch.java:770`) | REPLACE-BY `ViewAreaRenderer` via the `PortalShape` abstraction (render §1.3) | ✅ TRANSITIVE — invoked only from gated rows 14/15 | |
| A7 | `MainProjectionBobMixin` | `mixin/client/MainProjectionBobMixin.java` | unconditional bob no-op on the main projection; collides with the ported IP `viewBobbingReduce` (D5 A2, checkpoint C5) — **needs a gate or the A2 resolution** at S12/S13 | REPLACE-BY IP `bobView` scaling trio + `RenderStates.viewBobFactor` (render §2.1, §3.2) | ✅ GATED — flag-ON both `@Redirect`s now CALL the real `bobHurt`/`bobView` (added `@Shadow`), so IP's ported bob injects scale it; flag-OFF the no-op stays. The A2 collision is resolved | |
| A8 | `EntityPortalCollision` | `entity/EntityPortalCollision.java` | static helpers; **transitive** — called only from rows 1/2/12 (`EntityMixin.java:139`, `LocalPlayerMixin.java:81`, `SeamlessClientTeleport.java:238`) | REPLACE-BY per-portal crossing + `CollisionHelper` family (core §2) | ✅ TRANSITIVE — all callers (rows 1/2/12) gated; never invoked flag-ON | |
| A9 | `ClientLevelMixin` — the chunk-load portal SCAN only | `mixin/client/ClientLevelMixin.java` | client-side block-portal detection on chunk load (`onChunkLoaded` inject `:72-73` → `PortalDetector.onNetherPortalDetectedClient` `:132`) — **the SCAN needs the gate**; the particle bypass + disconnect teardown halves survive (core §9 "mixed"; render §3.5 "KEEP (excise scan)") | scan: DELETE-with-block-portals; particle bypass: REPLACE-BY IP `MixinClientLevel`; teardown: survives wired to `ClientWorldLoader.cleanUp` (core §9) | ✅ GATED (SCAN only) — `!entityPortals` at HEAD of `seamlessportals$onChunkLoaded`; the `doAddParticle` particle-bypass `@Redirect` and the `disconnect` teardown inject are NOT gated (survive both states, per disposition) | |

Passive REPLACE rows needing **no gate** (data/API surface with no tick/frame/event entry —
suppressing their callers suppresses them; listed so the census is checkably complete against
core §§1–9): `PortalInfo`, `PortalTransform`, `PortalType` (core §1); the payload records of
`network/ModPayloads.java` (core §3 — senders/handlers are gated via rows 6/8/9/11/13);
`SeamlessPortalsAPI`, `IPortalDefinition` (core §7); accessor ducks `EntityLevelAccessorMixin`
(core §8), `ClientPacketListenerAccessorMixin`, `MinecraftAccessorMixin` (core §9 — REPLACE-BY
IP ducks at their owning port stages, harmless meanwhile).

**Recorded cross-table tension** (resolve at S13 population, not silently):
`ApplyLightDataGuardMixin` + `ChunkLightLambdaGuardMixin` are REPLACE-BY IP's
`MixinClientPacketListener` family in core §9 but KEEP ("chunk-feed light infrastructure") in
render §3.5. Both remain ACTIVE in both flag states until IP's handler family is live; their
final row (suppress vs substrate) is an S13 ledger decision.

### 1.2 Census ADDITIONS — DELETE-fated active hooks D3 does not name (⚑ completeness)

These are live block-era code paths (not REPLACE rows) whose flag-ON behavior must still be
accounted for. Default treatment noted; S13 confirms or overrides each.

| # | ⚑ Hook | Path | Flag-ON treatment (default) | Disposition | S13 | S20 |
|---|---|---|---|---|---|---|
| B1 | `SpeculativePrewarm` | `chunk/SpeculativePrewarm.java` | transitive — ticked only from row 8 (`PortalChunkTracker.java:307`) | DELETE (core §4) | ✅ TRANSITIVE — ticked only from gated row 8 | |
| B2 | `BlockUpdateMirrorBuffer` | `chunk/BlockUpdateMirrorBuffer.java` | per-server-tick flush (`fabric: SeamlessPortalsModFabric.java:56`) — flush is a no-op once A3 (its filler) is gated, but the tick call itself should be gated with the loader dispatch | DELETE (core §4) | ✅ GATED — the `flush` call is inside the gated `END_SERVER_TICK` registration (`else` branch of `SeamlessPortalsModFabric`); not called flag-ON | |
| B3 | `ServerLevelBlockUpdateMixin` | `mixin/ServerLevelBlockUpdateMixin.java` | portal-destruction observation driving `PortalManager` (`:75,:99-102`) — **needs its own gate** | DELETE (core §8) | ✅ GATED — `!entityPortals` early-return at HEAD of `seamlessportals$mirrorToPortalWatchers` | |
| B4 | `HandleRespawnMixin` | `mixin/client/HandleRespawnMixin.java` | crossing-keyed paths idle flag-ON (IP's crossing never triggers `handleRespawn`), **but its player-reuse also runs on death/vanilla respawns — S13 must decide the gate scope explicitly** (core §9: death respawns revert to vanilla) | DELETE (core §9) | ✅ DECISION: **NO gate** — its crossing-keyed paths are already idle flag-ON (IP's crossing never routes through `handleRespawn`), and its player-reuse must keep serving genuine death/vanilla respawns in BOTH flag states. Gating it would break vanilla respawn handling flag-ON. Left active | |
| B5 | `ChunkMapResendSuppressMixin` | `mixin/ChunkMapResendSuppressMixin.java` | transitive — armed only by the old crossing (row 13) | DELETE (core §8) | ✅ TRANSITIVE — armed only by gated row 13 | |
| B6 | `ClientPacketListenerForgetGuardMixin`, `ClientPacketListenerTeleportToleranceMixin` (+ `ClientPacketListenerPositionInvoker`) | `mixin/client/…` | transitive — keyed to `SeamlessClientTeleport.isInPostSwapWindow()` (`ForgetGuardMixin.java:38`, `TeleportToleranceMixin.java:57`), a window that never opens flag-ON | DELETE (core §9) | ✅ TRANSITIVE — the post-swap window is opened only by the gated block-era crossing (rows 1/12); never opens flag-ON | |
| B7 | `NetherPortalUninteractableMixin` | `mixin/client/NetherPortalUninteractableMixin.java` | vanilla portal blocks CAN still form flag-ON pre-S16 (§2) — leaving it active is the conservative default; S13 decides | DELETE (core §9) | ✅ DECISION: **NO gate** (conservative KEEP) — vanilla nether-portal BLOCKS can still form flag-ON until S16's structural suppression, so keeping them client-side uninteractable in both states is the safe default. Re-checked at S16 alongside A1/A2 → **S16.2 UPDATE: GATED `!entityPortals`** (wf_91b049a9-0c1/wf_889ebd19-422): flag-ON, crouch-hatch/legacy vanilla portals are plain-vanilla per IP incl. creative breakability; flag-OFF unchanged | |
| B8 | `ProjectileMixin` + `ThrownEnderpearlMixin` | `mixin/ProjectileMixin.java`, `mixin/ThrownEnderpearlMixin.java` | the row-4 gate SITE (they host `handleProjectileTick`) — gated together with row 4 | DELETE (core §8) | ✅ GATED — `!entityPortals` early-return at HEAD of both (see row 4) | |
| B9 | `PortalForcerMixin` | `mixin/PortalForcerMixin.java` | transitive — vanilla `PortalForcer` never runs while `handlePortal` is cancelled (§2); dies at S20 (IP has zero PortalForcer references, core §1/§8) | DELETE (core §8) | ✅ TRANSITIVE flag-OFF → **S16.2 UPDATE: GATED `!entityPortals`** (wf_91b049a9-0c1): the §2 cancel demotion makes vanilla PortalForcer REACHABLE flag-ON (crouch-hatch/legacy portal travel); an ungated block-era PortalDetector feed would double-handle. Early-return added; flag-OFF rationale unchanged | |
| B10 | `LivingEntitySprintCancelDiagMixin` | `mixin/client/LivingEntitySprintCancelDiagMixin.java` | transitive — keyed to `isInPostSwapWindow()` (`:30`) | DELETE, diagnostic (core §9) | ✅ TRANSITIVE — keyed to the post-swap window that never opens flag-ON | |
| B11 | dormant legacy: `PortalDimensionManager`, `RemoteChunkManager`, `RemoteChunkData`, `RemoteClientLevel`, `SeamlessTeleportState`, `PortalLink`(data), `EntityFlagsAccessor`, `ServerPlayerMixin`(empty), `PortalSlicing`+`GameRendererObliqueClipMixin`(unregistered), `PortalFrameSuppressor`, `GlTextureViewMixin`(unregistered), `LevelRendererCullTerrainMixin`(dead target), `LevelRendererDiagMixin`(unregistered), ~~`SectionCompilerMixin`~~, `ClearSkipMixin`, `MinecraftRenderTargetMixin`, `LivingEntityRendererDiagMixin` | (various — all verified present) | no live driver path / dead or unregistered per the tables — no gating required; deleted at S20. **S16.4 CORRECTION: `SectionCompilerMixin` was MISCLASSIFIED here — it is REGISTERED (seamlessportals-common.mixins.json:15) and its `compile` redirects FIRE on 26.2** (rung-4 round-1 runtime proof: flag-ON crouch-hatch vanilla portals rendered with NO swirls — the config-gated suppressPortalSwirl was live). Both its redirects now `!entityPortals`-gated. The S14 gate-audit rule (positively verify reachability) claims its 4th scalp; the "dormant" label is no longer trusted for ANY B11 entry at S20 deletion time — each gets a reachability check before delete (this distrust note also applies to the companion current-mod-render api-map/inventory rows describing these branches as dead) | DELETE (core §2/§4/§5/§8; render §1.18-1.19, §3.1-3.5) | n/a | |

## 2. Always-active block-era SUPPRESSIONS (both flag states)

| Suppression | Mechanism (verified) | Both-states rationale | S16 replacement plan | S16 | S20 |
|---|---|---|---|---|---|
| The `handlePortal` cancel | `EntityMixin.seamlessportals$cancelVanillaPortal` — `@Inject(method="handlePortal", at=@At("HEAD"), cancellable=true)` (`mixin/EntityMixin.java:170-175` — line-drift fixed from :162-166), config-gated on `isSeamlessTeleportation`; paired vanilla-cooldown tick-down at `:70-73` | keeps vanilla nether-portal BLOCKS inert in flag-ON worlds (blocks still form from fire-on-obsidian until S16; only their teleport is cancelled) — D3 second bullet | S16 replaces it with IP's STRUCTURAL suppression: `netherPortalMode != vanilla` redirects `PortalShape.findEmptyPortalShape` to empty inside `BaseFireBlock.onPlace` (`IP: imm_ptl/peripheral/mixin/common/nether_portal/MixinAbstractFireBlock_CVB.java:27-44`; core §8 EntityMixin row; EXECUTION_PLAN S16 "structural suppression replaces handlePortal cancel (ledger update)") | **DONE 2026-07-18 (S16.2, corrected per verify wf_91b049a9-0c1):** the ported `MixinAbstractFireBlock_CVB` (flag-ON-only via the plugin's qouteall gate; `seamlessportals-ip-peripheral.mixins.json` wired into fabric.mod.json) structurally suppresses vanilla portal-block FORMATION flag-ON (`netherPortalMode` disabled/normal/adaptive → `Optional.empty()`; vanilla-mode passthrough). The `handlePortal` cancel is **DEMOTED TO FLAG-OFF-ONLY** (`!entityPortals && isSeamlessTeleportation`) — the verify REFUTED the first-cut "retained-redundant" claim: IP 1.21.3 has NO handlePortal suppression anywhere; its crouch escape hatch exists precisely to give a WORKING vanilla portal, so flag-ON crouch-hatch/legacy vanilla blocks now teleport VANILLA-STYLE (IP-authentic; the client survives the respawn packet via the S15 pump transient-frame guard). Gated with the SAME demotion: the paired cooldown tick-down (would double-decrement once vanilla handlePortal runs flag-ON), **B9 `PortalForcerMixin`** (now REACHABLE flag-ON via vanilla portal travel — its block-era PortalDetector feed would double-handle; `!entityPortals` early-return added; its B9 row's "transitive both states" rationale is superseded → "transitive flag-OFF, GATED flag-ON"), and **B7 `NetherPortalUninteractableMixin`** (creative-break suppression diverges from IP's plain-vanilla crouch portals; flag-OFF unchanged; its companion NetherPortalBlockMixin getDestroyProgress stays — redundant with vanilla's own unbreakable-in-survival portal blocks, harmless). A1 (`PortalShapeFormMixin`) DOES hook `createPortalBlocks` TAIL — the exact call the crouch hatch makes — but it is ALREADY flag-gated (`isEntityPortals() → return`, PortalShapeFormMixin.java:39), so crouch-hatch blocks flag-ON do NOT feed the block-era detector (checked at the file, not assumed). A2 unchanged. | |

## 3. Always-on substrate KEEPs (both flag states)

The D3 third-bullet list, expanded with verified paths. These serve BOTH driver sets and are never
flag-gated. Citations: render §3.1–3.5, render §1, core §8/§9.

| Substrate | Files (verified) | Role | Citation |
|---|---|---|---|
| Stencil FBO chain | `mixin/client/stencil/GlBackendMixin.java`, `GlConstMixin.java`, `RenderTargetMixin.java`, `GlStateManagerMixin.java` | 26.2 stencil-bits request, DEPTH24_STENCIL8 force, depth reattach, `lastBoundFbo` capture — the 26.2 replacement for IP's porting-lib stencil enable | render §3.1 |
| Extractor plumbing accessors | `mixin/client/LevelExtractorFlashBridgeMixin.java`, `LevelExtractorCreateRegionBudgetMixin.java`, `LevelExtractorAccessor.java`, `ClientLevelExtractorAccessor.java` | pure-26.2 layer (`LevelExtractor` does not exist in 1.21.3); extract/compile pairing + promote/demote re-pointing the ported renderer depends on | render §3.4; core §9 (`ClientLevelExtractorAccessor` KEEP) |
| `GlCommandEncoderClipMixin` | `mixin/client/GlCommandEncoderClipMixin.java` | 26.2 per-draw clip-plane upload; pairs with `FrontClipping` | render §3.5 |
| `ShaderManagerCompilationCacheMixin` | `mixin/client/ShaderManagerCompilationCacheMixin.java` (+ `render/ShaderCodeTransformation.java`, PORT-FORWARD) | 26.2 delivery of shader-code transformation | render §3.5, §1.7 |
| `DimensionRenderHelper` + `GameRendererLightmapMixin` | `render/DimensionRenderHelper.java`, `mixin/client/GameRendererLightmapMixin.java` | per-dim lightmap (PORT-FORWARD of IP's class, already 26.2-shaped) + its 26.2 binding delivery | render §1.8, §3.2 |
| `PortalRenderBuffersPool.endFramePooled` | `render/PortalRenderBuffersPool.java` | the 26.2-required per-frame fence-recycle (GPU-buffer endFrame rule); the pool itself is the IP acquire/return pattern | render §1.10 |
| `lateUpdateLight` host | `mixin/client/GameRendererMixin.java` (TAIL half — the HEAD half is driver row 17's gate site) | IP `MyRenderHelper.lateUpdateLight` at frame-render END + endFrame rules | render §3.2 |
| `FrontClipping` | `render/FrontClipping.java` | PORT-FORWARD of IP's class; **anti-deviation guard (D4.4): the JOML `Vector4f.mul` idiom STAYS** — do not "fix" to `mulTranspose` | render §1.6 |
| Diagnostics (F16) | `render/CrossingTracer.java`, `render/RenderSpikeMonitor.java`, `render/PerfTimers.java` | regression tooling for this migration; retained per forced-deviation register F16 | render §1.13–1.15; EXECUTION_PLAN §4 F16 |

**Always-on KEEP census — remaining rows (completeness; not named by D3's summary but KEEP in the
tables, all verified present):** `SeamlessMixinConfigPlugin` (the flag reader itself),
`TicketTypeInvoker` (core §8); `MinecraftMixin`, `ClientLevelChunkSourceAccessor` (core §9;
render §3.5); `GameRendererAccessorMixin`, `GameRendererFrameCrossingMixin` (shared host, §4),
`GameRendererHandLightMixin` + `render/HandLightSmoother.java` (A5/C6 default-KEEP) (render §3.2);
`LevelRendererAccessorMixin`, `LevelRendererCompileSectionsMixin`,
`LevelRendererEntityVisibilityMixin`, `LevelRendererBlockOutlineMixin`, `ViewAreaInvokerMixin`,
`SectionOcclusionGraphAccessorMixin`, `SectionOcclusionGraphPartialUpdateSkipMixin`,
`SkyRendererTargetMixin` (render §3.3); `ParticleEnginePortalSkipMixin`,
`ParticleEngineAccessorMixin`, `QuadParticleGroupMixin` (A5 re-source note, §1.1 row A5),
`DebugRendererPortalSkipMixin`, `CameraInvokerMixin` (render §3.5); `render/PortalRenderTypes.java`,
`render/StencilState.java`, `render/SodiumFogOverride.java` + `mixin/client/compat/SodiumFogOverrideMixin.java`
(render §1.4/§1.11/§1.12); `compat/SodiumCompat.java` (core §6; `SodiumBridge` stays AMBIGUOUS —
render slice decides, core §11.2); `config/SeamlessPortalsConfig.java` (KEEP-shape; hosts the
`entityPortals` key), `network/PlatformHelper.java` (KEEP + widened) (core §7/§3); loader modules
per core §10.

**PORT-FORWARD (verify) hooks — active in BOTH states pending re-proof at their named stages**
(neither suppressed drivers nor permanent substrate; each carries a verify obligation):
`ServerLevelFireSpreadMixin`, `FireworkRocketEntityAccessor` (F2), `LivingEntityHurtAccessor` (F3)
(core §8); `ChunkPacketGuardMixin`, `ClientPacketListenerAddEntityAdoptMixin`,
`ClientPacketListenerLocalPlayerFallbackMixin` (core §9); `client/SeamlessClientChunkMap.java`
(core §5 — becomes the chunk map for ALL IP client worlds via the constructor hook);
`render/VisibleSectionDiscovery.java` (A3 comparative read at S11), `render/PortalInnerCull.java`
(render §1.5/§1.9).

## 4. Shared host mixins (flag-dispatch sites)

D3 fourth bullet: the MOD-OWNED mixin body dispatches on the flag to either the old pump or the
ported IP call chain — mod code dispatches, **IP code bodies are never flag-polluted**.

| Host | Exists today? | Today's cargo (verified) | S13 flag dispatch | S13 | S20 |
|---|---|---|---|---|---|
| The S3-relocated pre-render pump host | **NOT YET — created at S3** (EXECUTION_PLAN S3(a): a mod-owned mixin at the `Minecraft.renderFrame` call-site anchor, fidelity default per portal-animation.md #14; fallback `GameRenderer.update` HEAD) | to be moved there at S3: the frame-crossing pump (today `GameRendererFrameCrossingMixin.java:33` → `SeamlessClientTeleport.checkCameraCrossingPerFrame`) + the A4 re-homed upkeep (staged-upload flush, adoption prune, bridge repaint pump — today `render/StencilPortalRenderer.java:194-215`, hosted by `GameRendererPortalPrepareMixin`) | flag OFF → the block-era pump; flag ON → IP's pre-render chain (`RenderStates.updatePreRenderInfo`, `ClientPortalAnimationManagement.update`, `manageTeleportation(false)`, `MyRenderHelper.earlyRemoteUpload` — IP anchor `MixinGameRenderer.java:86-96`) | ✅ LIVE DISPATCH — `MinecraftFramePumpMixin.seamlessportals$preRenderPump` now branches on `SeamlessPortalsConfig.isEntityPortals()`: flag ON runs the ported chain (`RenderStates.updatePreRenderInfo` → `StableClientTimer.update` → `ClientPortalAnimationManagement.update` → `ClientTeleportationManager.manageTeleportation(false)` → `PRE_GAME_RENDER_EVENT` → `MyRenderHelper.earlyRemoteUpload` if `IPCGlobal.earlyRemoteUpload`), flag OFF runs the block-era `checkCameraCrossingPerFrame` + `frameUpkeep` unchanged. Mod code dispatches; the IP body is never flag-polluted | |
| `SeamlessMixinConfigPlugin` | yes — `mixin/SeamlessMixinConfigPlugin.java` (KEEP, core §8) | mixin-set gating plumbing | reads `entityPortals` once at mixin-plugin time; registers the IP mixin configs (S0 skeleton JSONs) when ON, the block-era driver mixins when OFF (D3) | ✅ LIVE — `shouldApplyMixin` now returns `false` for any `qouteall.*` mixin when `EntityPortalsFlag.isOn()` is false. Flag OFF → the whole IP mixin set is skipped (block-era com.warwa mixins unaffected, gated at runtime). Flag ON → the IP set applies. The 3 populated IP configs (`ip-qmisc`/`ip-core-common`/`ip-client`) are referenced in `fabric.mod.json` + `neoforge.mods.toml`; a 4th **Fabric-only** config `seamlessportals-ip-fabric.mixins.json` (the 2 `platform_specific.mixin.common` _MA mixins — `MixinPlayerManager_MA`/`MixinServerPlayerEntity_MA`, WIRE 3 P2) is referenced in `fabric.mod.json` ONLY (mirrors IP's Fabric-specific `imm_ptl_fabric.mixins.json` + the NeoForge-IP deferral) | |
| Loader entrypoints | yes — `fabric: SeamlessPortalsModFabric.java`, `fabric: SeamlessPortalsClientFabric.java` (KEEP-shape, core §10) | host the driver tick/render registrations of §1 rows 5/8/10/14/16 + B2 | tick/render registrations become flag-dispatched: block-era set vs IP init set (`ServerTeleportationManager.init`, `ImmPtlChunkTracking.init`, `EntitySync.init`, `WorldInfoSender.init`, `ImmPtlNetworking.init/initClient`, `ClientWorldLoader.init` — core §10) via the S0 seams | ✅ LIVE DISPATCH — both `onInitialize`/`onInitializeClient` now `if (isEntityPortals()) { <IP init in DEPENDENCY_ORDER §4.2 order> } else { <the block-era tick/render registrations, unchanged> }`. Flag ON server/common: `ImplRemoteProcedureCall.init` → `MiscNetworking.init` → `DimensionIntId.init` → `IPModMain.init`; flag ON client: `ImplRemoteProcedureCall.initClient` → `MiscNetworking.initClient` → `IPModMainClient.init`. Config load + payload TYPE registration stay unconditional (identical both states) | |

## 5. Transition checklists (EMPTY — filled by the owning stages)

### 5.1 S13 — populate + enforce (EXECUTION_PLAN S13 step 3)

Enforced at **S13-B WIRE 1** (stage record `migration/port-notes/S13B-flip.md §4`; uncommitted — commit
hashes land when the stage is committed):

- [x] Every §1 row (1–17, A1–A9, B1–B10) has its `S13 gate` cell filled: gate site
      (`!entityPortals` branch or loader-entrypoint dispatch) or a verified transitivity proof.
- [x] The §4 pump host's live flag dispatch landed (`MinecraftFramePumpMixin` — §4 `S13` cell filled).
- [x] §1.1's cross-table tension (`ApplyLightDataGuardMixin`/`ChunkLightLambdaGuardMixin`)
      resolved: **NO gate** — both are chunk-feed light infrastructure kept ACTIVE in BOTH flag
      states (their light-data guard is a substrate function, not a portal driver; IP's
      `MixinClientPacketListener` family adds its own path flag-ON but does not conflict). Re-checked
      when that IP handler family goes live.
- [x] B4 (`HandleRespawnMixin` death-respawn scope) decided and recorded: **NO gate** — crossing
      paths idle flag-ON, player-reuse serves genuine death/vanilla respawns in both states (§1.2 B4).
- [x] Unconditional-registration list (D3: entity types, `PortalPlaceholderBlock`, argument types,
      payloads) — **LANDED at WIRE 2 (S13 step 5)** (`migration/port-notes/S13B-flip.md §6`). Entity types +
      `PortalPlaceholderBlock` (world-saved → genuine save-safety) registered UNCONDITIONALLY above
      the flag branch (`SeamlessPortalsModFabric`) via the S0 `registerEntityTypes` seam + direct
      block registration; the `PortalEntityRenderer` (×9 portal family) + `LoadingIndicatorRenderer`
      seam registered UNCONDITIONALLY in `SeamlessPortalsClientFabric` (MANDATORY — the dev/IDE
      client's `Minecraft.selfTest()`→`EntityRenderers.validateRegistrations()` throws unless every
      registered entity type has a renderer). Argument types + the IP payload TYPES are registered
      flag-ON inside `IPModMain.init`/the q_misc init methods and MIRRORED (TYPE-only, no
      handlers/behavior) in the flag-OFF `else` branch — mutually exclusive, so no double
      registration; D3 registry parity holds in both states. Wired on Fabric only (NeoForge IP
      integration stays deferred, S07 §6). Green gate `:common`/`:fabric`/`:neoforge` compileJava +
      `:common:test` (48/0/0/0).
- [x] The §6 flag-ON ADDITIVE IP mixin set registered — the render install + R3 clip mixins + the
      concrete-renderer-family + multiworld/render halves are all in the now-populated
      `seamlessportals-ip-client.mixins.json "client":[]` (57 client entries) +
      `seamlessportals-ip-core-common.mixins.json "mixins":[]` (73), gated flag-ON via
      `SeamlessMixinConfigPlugin`; the install `@Redirect` additionally self-gates on
      `IPCGlobal.useHackedChunkRenderDispatcher` (default `true`, only read flag-ON).
- [ ] Both-states verification run — **flag-OFF byte-equivalence is structurally guaranteed** (every
      gate is a pure `!entityPortals` fall-through; the IP mixin set is skipped by the plugin flag-OFF;
      the init wiring lives in never-entered `if (entityPortals)` branches). The live flag-ON rung-1
      runClient is the USER's first-light checkpoint (S13 step (d), after WIRE 2). GREEN GATE this
      stage: `:common`/`:fabric`/`:neoforge` compileJava + `:common:test` (48/0/0/0).

### 5.2 S16 — structural suppression swap (EXECUTION_PLAN S16)

- [x] §2's `S16` cell filled (2026-07-18, S16.2, corrected wf_91b049a9-0c1/wf_889ebd19-422):
      vanilla portal-block FORMATION suppressed flag-ON by the ported `findEmptyPortalShape`
      redirection; the `handlePortal` cancel **DEMOTED TO FLAG-OFF-ONLY**
      (`!entityPortals && isSeamlessTeleportation`), with the paired cooldown tick-down,
      B9 `PortalForcerMixin`, and B7 `NetherPortalUninteractableMixin` gated identically —
      flag-ON, crouch-hatch/legacy vanilla portals teleport VANILLA-STYLE per IP.
- [ ] A1/A2/B7 rows re-checked once vanilla portal blocks can no longer form.

### 5.3 S20 — deletion + archive (EXECUTION_PLAN S20)

- [ ] Every `S20` cell filled with the deletion commit (drivers, gates, flag, block-era mixin
      registrations).
- [ ] This ledger's active role ends; file archived (plan S20: "the exclusivity ledger's active
      role (archive the file)").

## 6. Flag-ON ADDITIVE IP mixin set — held-UNREGISTERED, registered at S13

Beyond the §1 driver SUPPRESSIONS and the §3 substrate KEEPs, S13 REGISTERS a set of ADDITIVE IP
mixins: inert flag-OFF (held-UNREGISTERED — `seamlessportals-ip-client.mixins.json "client":[]` empty),
driving the ported entity-portal render path flag-ON. They land held at S11/S12 so the S13 diff-gate +
mixin registration expects exactly this set (CUTOVER_SPEC §6.2 "what flips ON at S13"; EXECUTION_PLAN
S12 "~62 client mixins, unregistered until S13"). These are ADDITIVE, not driver-suppressions — no `!entityPortals`
gate on mod code; the install `@Redirect` self-gates on the global `IPCGlobal.useHackedChunkRenderDispatcher`
(the S13-bound `entityPortals` toggle), and the whole set is only registered flag-ON.

### 6.1 Render install + R3 per-entity clip mixins (S12-A Slice C — `qouteall/imm_ptl/core/mixin/client/render/`)

| Mixin | Target | Role | Landed | S13 register |
|---|---|---|---|---|
| `MixinEntityRenderDispatcher` | `EntityRenderDispatcher.shouldRender` | entity-visibility gate (render-core S35), 1:1 | S11-C | flag-ON |
| `MixinLevelRenderer` | `LevelRenderer.invalidateCompiledGeometry` | R4 `ImmPtlViewArea` install `@Redirect` (gate `IPCGlobal.useHackedChunkRenderDispatcher`) | S12-A/C | flag-ON |
| `MixinLevelRenderer_CrossPortalEntity` | `LevelRenderer.submitEntities` | R3 submit HEAD/TAIL anchors + per-entity submit `@WrapOperation` | S12-A/C | flag-ON |
| `MixinEntityRenderState` | `EntityRenderState` | R3 clip-context tag HOLDER (implements `IEEntityRenderState`) | S12-A/C | flag-ON |
| `MixinLevelExtractor` | `LevelExtractor.extractVisibleEntities` | R3 clip-context tag SETTER (`@WrapOperation` on `extractEntity`) | S12-A/C | flag-ON |
| `MixinPreparedFrame` | `FeatureRenderDispatcher.PreparedFrame.executePhase` | R3 Mechanism-A `executePhase` clip bracket | S12-A/C | flag-ON |
| `IERenderSystem` | `RenderSystem` (accessor) | `modelViewStack` accessor duck (verbatim IP; target present) | S12-A/C | flag-ON |
| `IESectionRenderDispatcher` | `SectionRenderDispatcher` (accessor) | `fixedBuffers` accessor duck (verbatim IP; target present, swap UNWIRED) | S12-A/C | flag-ON |

Supporting duck (NOT a mixin): `ducks/IEEntityRenderState` — held by name in `IpHeldPaths.MAIN_HELD_PATHS`
(carve-in-dir duck importing held `Portal`). **NOT landed:** `mixin/client/accessor/CoreShadersAccessor`
(RETIRED — its `CoreShaders.register`→`ShaderProgram` target is G9-GONE on 26.2; re-expressed onto the
`PortalRenderTypes` substrate; `fragments/S12A-installs.md` task 5).

### 6.2 The remaining S12 client-mixin set (the balance of ~62)

The concrete-renderer-family mixins (S12-A renderer slices: `PortalRenderer` / `RendererUsingStencil` /
`RendererUsingFrameBuffer` drivers + the multiworld + render mixin halves, `api-map/mixin-client.md`) land
held-UNREGISTERED across the S12 commits and register in the same S13 step. §6.1 is the render-install +
R3 anchor for the §5.1 "the IP mixin set" registration; `mixin-client.md` is the full enumeration.

## 7. WEAVE-LEVEL EXCLUSIVITY — the load-time half of the one-driver contract (S13-E)

§1's `!entityPortals` runtime gates make block-era driver BODIES inert flag-ON, but a runtime gate runs
AFTER the class is transformed. Two mixins that both attach a redirect-family injector
(`@Redirect`/`@ModifyConstant`/`@ModifyArg`/`@ModifyVariable`/`@WrapOperation`) to the **same bytecode
instruction** at equal priority collide at WEAVE time: Mixin applies the first and SKIPS the second, whose
`require = 1` (the config `injectors.defaultRequire`) then throws `InvalidInjectionException` and kills
boot — long before any runtime gate can evaluate. The exclusivity contract therefore has a **weave-layer
half**: wherever the always-on `com.warwa` set and the flag-ON IP set touch one injection site with
incompatible injector types, the block-era half must be suppressed at MIXIN-PLUGIN time.

**Mechanism.** `SeamlessMixinConfigPlugin.ENTITY_PORTALS_SUPERSEDED_MIXINS` — a set of block-era mixin
FQNs that `shouldApplyMixin` returns `false` for **when `entityPortals` is ON** (the mirror of the
`qouteall.*`-skip that runs flag-OFF). Each entry is a block-era mixin whose function flag-ON is a strict
subset of a registered IP mixin's, so suppressing it loses nothing flag-ON. This is enforced at the same
load-time seam as the IP-set gate (§4 `SeamlessMixinConfigPlugin` row) and is byte-inert flag-OFF (the set
is only consulted when the flag is ON).

### 7.1 The suppression set (weave-layer suppressions)

| # | Suppressed block-era mixin | Shared target · site | Collision | IP superseder | Coverage verdict | Landed |
|---|---|---|---|---|---|---|
| W1 | `mixin.client.ClientPacketListenerLocalPlayerFallbackMixin` | `ClientPacketListener.handleSetEntityData` → the `ClientLevel.getEntity(int)` call | **FATAL** — both `@Redirect` the SAME `getEntity` INVOKE at equal priority; second skipped → `require:1` boot kill (first-light attempt 3) | `qouteall…client.sync.MixinClientPacketListener` `redirectGetEntityById` (`@Redirect` line 171-173, same call) | **COVERED** — IP resolves entities across the per-dim client worlds (`ClientWorldLoader`), a strict superset of the block-era single-`ClientLevel` local-player fallback. The block-era mixin's own IP-parity javadoc names this counterpart. | commit `855fad5` |

**No other entry is required.** The S13-E cross-product sweep (§7.2) found this to be the ONLY fatal (or
`@Overwrite`-class) weave collision in the entire LEFT×RIGHT product; every other shared-target overlap is
`@Inject`-family that composes or is guard-inert flag-ON.

### 7.2 The full shared-target census (LEFT × RIGHT, S13-E)

LEFT = the registered `com.warwa` set (`seamlessportals-common.mixins.json`, 51 `client` + 16 `mixins`).
RIGHT = the registered flag-ON IP set (`ip-core-common` 73 + `ip-client` 57 + `ip-qmisc` 5 + `ip-fabric`
2; `ip-peripheral` empty; `MixinGameRenderer_Isometric` unregistered). Accessor/invoker-only mixins
(`@Accessor`/`@Invoker`) attach no method-body injector and cannot collide — they are folded into the rows
below only where they share a target. **18 vanilla classes are touched by both sets.** For each, the
collision verdict per Mixin weave semantics:

| # | Shared target | Both-side injection sites (cw = com.warwa, ip = IP) | Verdict |
|---|---|---|---|
| 1 | `ClientLevel` | cw `doAddParticle`@Redirect + `onChunkLoaded`/`disconnect`@Inject · ip `<init>`/`addEntity`/`hasChunk`/`toString`/`tickNonPassenger`/`playSound`@Inject | **SAFE** — disjoint methods |
| 2 | `LocalPlayer` | cw `tick`@Inject×2 · ip `suffocatesAt`@Inject | **SAFE** — disjoint |
| 3 | `ClientPacketListener` | cw 10 mixins (`handleSetEntityData`@Redirect *= W1*, `handleLevelChunkWithLight`@ModifyArg, `handleMovePlayer`@Redirect, `handleAddEntity`/`handleForgetLevelChunk`@Inject cancellable, `handleRespawn`@Inject+@Redirect, `applyLightData`/`updateLevelChunk`@Inject/@Redirect) · ip `MixinClientPacketListener` (`handleSetEntityData`@Redirect, `handleBlockChangedAck`@Redirect, `handleMovePlayer`/`handleSetEntityPassengersPacket`/`handleAddEntity`/`handleLevelChunkWithLight`/`handleForgetLevelChunk`/`handleSetTime`@Inject), `_Debug`(commented-inert), qmisc `IEClientPacketListener_Misc`(@Accessor) | **1 FATAL → W1 (FIXED)**; all other overlaps are `@ModifyArg`/`@Redirect` vs `@Inject` (different categories compose) or `@Inject`+`@Inject`; `handleRespawn` has NO IP side (B4 no-gate confirmed) |
| 4 | `Minecraft` | cw `renderFrame`@Inject(INVOKE pump), `updateLevelInEngines`/`close`@Inject · ip `renderFrame`@Inject(FIELD `fps`), `updateLevelInEngines`@Inject(HEAD), `tick`@Inject, `run`@WrapOperation, `handleKeybinds`@WrapOperation, `pick`/`shouldEntityAppearGlowing`/`wrapRunnable`@Inject | **SAFE** — `renderFrame` (different anchors) + `updateLevelInEngines` (both @Inject HEAD; IP javadoc explicitly acknowledges the mod hook; mod body operates on empty block-era state flag-ON) both compose |
| 5 | `MultiPlayerGameMode` | cw `startDestroyBlock`/`continueDestroyBlock`@Inject HEAD cancellable (B7) · ip `lambda$startDestroyBlock$1`/`continueDestroyBlock`/`performUseItemOn`@Redirect, `startPrediction`/`startDestroyBlock`/`stopDestroyBlock`@ModifyArg | **SAFE** — `@Inject` HEAD-cancel vs `@Redirect`/`@ModifyArg` are different categories → compose |
| 6 | `ParticleEngine` | cw `extract`@Inject HEAD cancellable (**KEEP** `ParticleEnginePortalSkipMixin`) · ip `extract`@Inject HEAD cancellable (`MixinParticleEngine`) | **SAFE** — `@Inject`+`@Inject` coexist; cw guard `PortalContextSwitch.isRenderingPortal` is false flag-ON (row 15) so the KEEP body never fires flag-ON — no breach |
| 7 | `Camera` | cw `CameraInvokerMixin`=@Invoker only · ip `update`/`getFluidInCamera`/`isDetached`@Inject | **SAFE** — invoker adds no injector |
| 8 | `GlStateManager` | cw `_glBindFramebuffer`@Inject (**KEEP** stencil) · ip `_enableCull`/`_glGenBuffers`/`_glGenVertexArrays`@Inject | **SAFE** — disjoint methods; KEEP substrate intact |
| 9 | `LevelExtractor` | cw `extract`@Inject/@Redirect + `applyFrustum`@Inject (**KEEP**) · ip `extractVisibleEntities`@WrapOperation | **SAFE** — disjoint (`extract` ≠ `extractVisibleEntities`) |
| 10 | `LevelRenderer` | cw `compileSections`@Redirect(FIELD `GETFIELD sectionUpdateRenderStates`), `submitBlockOutline`@ModifyArg, `isSectionCompiledAndVisible`@Inject, `cullTerrain`@Inject(sodium-gated) · ip `invalidateCompiledGeometry`@Redirect(NEW), `submitEntities`@Inject/@WrapOperation, `compileSections`@ModifyVariable(STORE `rebuildSync`), `_Optional`/`_Clouds`=empty no-op | **SAFE** — the one shared method `compileSections` has two redirect-family injectors but on DIFFERENT instructions (a `GETFIELD` node vs a local-variable `STORE`) → both weave |
| 11 | `GameRenderer` | cw `render`@Inject, `lightmap`@Inject, `renderLevel`@Inject(PortalPrepare)+@Redirect(MainProjectionBob→`bobView` call, A7), `renderItemInHand`@ModifyArg(HandLight), `sodium$getFogParameters`@Inject · ip `renderItemInHand`@Inject, `extract`@Inject, `bobView`@ModifyArg×3, `_Shaders`(commented-inert) | **SAFE** — `renderItemInHand` (@ModifyArg vs @Inject) composes; A7 (bob) resolved by design (cw @Redirect calls the real `bobView`, ip @ModifyArg scales inside it — different methods/instructions) |
| 12 | `ChunkMap` | cw `markChunkPendingToSend`@Inject (B5) · ip `applyChunkTrackingView`@Inject + `onChunkReadyToSend`**@Overwrite**; `addEntity`@Redirect/`removeEntity`/`tick`@Inject | **SAFE** — the `@Overwrite` lands on `onChunkReadyToSend`, which no cw mixin injects; methods disjoint |
| 13 | `Entity` | cw `tick`/`handlePortal`@Inject · ip `move`/`checkInsideBlocks`@Redirect + `fireImmune`/`isInWall`/`setPosRaw`/`getInBlockState`@Inject; `_U` `setPosRaw`/`setRemoved`@Inject | **SAFE** — disjoint |
| 14 | `Projectile` | cw `tick`@Inject HEAD cancellable · ip `MixinProjectile` = ALL injectors commented (inert) | **SAFE** — IP side inert |
| 15 | `ThrownEnderpearl` | cw `tick`@Inject · ip `onHit`@Inject | **SAFE** — disjoint |
| 16 | `LivingEntity` | cw `setSprinting`@Inject (B10 diag) · ip `tick`@Inject | **SAFE** — disjoint |
| 17 | `ServerLevel` | cw `sendBlockUpdated`/`canSpreadFireAround`@Inject · ip `tick`@Redirect + `toString`/`tickNonPassenger`@Inject; `_Debug` `addEntity`@Inject | **SAFE** — disjoint |
| 18 | `ServerPlayer` | cw `ServerPlayerMixin` = EMPTY (B11 dormant) · ip `MixinServerPlayer`/`MixinServerPlayerEntity_MA`@Inject | **SAFE** — cw side has no injector |

**Redirect-family-vs-redirect-family on a shared method** (the only fatal-capable pattern) occurs at
EXACTLY TWO sites in the whole product: `ClientPacketListener.handleSetEntityData` (both `@Redirect` the
same `getEntity` call → **FATAL → W1, fixed**) and `LevelRenderer.compileSections` (cw `@Redirect` a
`GETFIELD` vs ip `@ModifyVariable` a local `STORE` → **different instructions, safe**). No third exists.

**`@Overwrite` audit (IP set):** `Frustum.offsetToFullyIncludeCameraCube`, `ChunkMap.onChunkReadyToSend`,
`PlayerChunkSender`×4, `Player`(collision), `TrackedEntity`×2, `PlayerList`, `ServerGamePacketListenerImpl`
— **none** lands on a method any registered `com.warwa` mixin injects (the closest, `ChunkMap`, is a
different method than the block-era `markChunkPendingToSend`). The `com.warwa` set contains ZERO
`@Overwrite`/`@WrapMethod`, so the reverse direction is vacuous.

### 7.3 The 13 gated block-era driver mixins — weave re-check

The §4c runtime-gated drivers make their BODIES inert flag-ON, but the mission's rule is that a runtime
gate does NOT prevent a weave-time collision — the injector TYPE must also not collide. All 13 were
re-checked at the weave layer flag-ON and are **clear** (their targets either are not touched by any IP
mixin, or the two sets touch disjoint methods/instructions): `LocalPlayerMixin`·`LocalPlayer`,
`EntityMixin`·`Entity`, `ProjectileMixin`·`Projectile` (IP inert), `ThrownEnderpearlMixin`·`ThrownEnderpearl`,
`GameRendererMixin`+`GameRendererPortalPrepareMixin`+`MainProjectionBobMixin`·`GameRenderer` (A7 resolved),
`ClientLevelMixin`·`ClientLevel`, `ServerLevelBlockUpdateMixin`·`ServerLevel`, and the four whose targets
IP never touches: `PortalShapeFormMixin`·`PortalShape`, `NetherPortalBlockMixin`·`NetherPortalBlock`,
`LevelChunkSetBlockStateMixin`·`LevelChunk`, `QuadParticleGroupMixin`·`QuadParticleGroup`. None needs a
suppression-set entry; the runtime gate is sufficient because there is no weave conflict to begin with.

### 7.4 Substrate (KEEP) shared-target contact — DESIGN-BREACH check

The always-on KEEP substrate is designed to serve BOTH drivers; a KEEP mixin genuinely colliding with an
IP mixin would be a design breach (not an auto-suppress). Three KEEP mixins share a target with the IP set;
**none breaches**: `GlStateManagerMixin`·`GlStateManager` (disjoint methods), `LevelExtractor*`·
`LevelExtractor` (disjoint methods), and `ParticleEnginePortalSkipMixin`·`ParticleEngine` (same method
`extract` + same `@At("HEAD")`, but both `@Inject` so they COEXIST, and the KEEP body is guard-inert
flag-ON). The substrate is intact — no KEEP mixin needs weaving changes.

