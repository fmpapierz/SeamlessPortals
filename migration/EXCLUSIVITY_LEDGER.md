# EXCLUSIVITY LEDGER — the committed dual-driver contract

**Status: SKELETON (S0).** Created per EXECUTION_PLAN §1 D3 and §3 S0(a) / "Immediately-next
actions" item 3. Lifecycle (D3): skeleton at S0 → **fully populated + ENFORCED at S13 step 3,
BEFORE the first flag-ON run** (every block-era driver gains its `!entityPortals` gate in the
SAME commit that registers the IP mixin set) → updated at S16 (structural suppression replaces
the `handlePortal` cancel) → archived at S20 (flag + gates + old system deleted).

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

| # | Driver | Path (verified) | Block-era entry point(s) = candidate gate site | Disposition | S13 gate | S20 |
|---|---|---|---|---|---|---|
| 1 | `LocalPlayerMixin` crossing detection | `mixin/client/LocalPlayerMixin.java` | client tick plane-crossing detection → `EntityPortalCollision.findPortalCrossing` (`LocalPlayerMixin.java:81`) | REPLACE-BY `ClientTeleportationManager.manageTeleportation` (core §9) | | |
| 2 | `EntityMixin` server detection | `mixin/EntityMixin.java` | per-entity server-side detection → `EntityPortalCollision.findPortalCrossing` (`EntityMixin.java:139`). **Its `handlePortal` cancel (`:162-166`) is NOT suppressed — see §2** | REPLACE-BY IP per-portal scan (`Portal.SERVER_PORTAL_TICK_SIGNAL` → `getEntitiesToTeleport`) (core §8) | | |
| 3 | `PortalTeleporter` | `entity/PortalTeleporter.java` | non-player crossing executor; dispatches `SeamlessServerTeleport.performCrossing` (`PortalTeleporter.java:78`) | REPLACE-BY `ServerTeleportationManager.teleportRegularEntity` (core §2) | | |
| 4 | `ProjectilePortalHandler` | `entity/ProjectilePortalHandler.java` | `ProjectileMixin.java:22` + `ThrownEnderpearlMixin.java:23` (`handleProjectileTick`) — the gate lives in those two mixins | DELETE — IP unifies projectiles into the one regular-entity path (core §2); the two host mixins are themselves DELETE (core §8) | | |
| 5 | `PortalManager` | `portal/PortalManager.java` | server singleton (`fabric: SeamlessPortalsModFabric.java:32`); per-tick pre-warm `tickPortalPreWarm` (`fabric: SeamlessPortalsModFabric.java:51-52`) | REPLACE-BY `NetherPortalGeneration` + `CustomPortalGeneration` (core §1) | | |
| 6 | `PortalDetector` | `portal/PortalDetector.java` | queued-formation drain once per server tick from `PortalChunkTracker.tick` (`PortalDetector.java:34`); client link-payload handler (`fabric: network/FabricPlatformHelper.java:242`) | REPLACE-BY the peripheral ignition chain (`MixinAbstractFireBlock_CVB` → `IntrinsicPortalGeneration`) (core §1, core §0.2) | | |
| 7 | `PortalTracker` | `portal/PortalTracker.java` | queried by rows 2/3/5/6 (registry scans) | REPLACE-BY entity-index queries (`IPMcHelper.getNearbyPortals` et al.) (core §1) | | |
| 8 | `PortalChunkTracker` tick | `chunk/PortalChunkTracker.java` | `END_SERVER_TICK` (`fabric: SeamlessPortalsModFabric.java:44`); ACK handler (`fabric: network/FabricPlatformHelper.java:168`) | REPLACE-BY `ImmPtlChunkTracking` + `ImmPtlChunkTickets` + `ChunkVisibility` + `WorldInfoSender` (core §4) | | |
| 9 | `RedirectedPacketApplier` | `chunk/RedirectedPacketApplier.java` | payload enqueue (`fabric: network/FabricPlatformHelper.java:200`); budgeted drain per client tick (`fabric: SeamlessPortalsClientFabric.java:43-44`) | REPLACE-BY `PacketRedirectionClient.handleRedirectedPacket` (core §4) | | |
| 10 | `PortalEntityTracker` | `chunk/PortalEntityTracker.java` | `END_SERVER_TICK` (`fabric: SeamlessPortalsModFabric.java:45`) | REPLACE-BY vanilla tracking under the IP watch graph (`entity_sync` mixin family + `EntitySync`) (core §4) | | |
| 11 | `RemoteBlockUpdater` | `chunk/RemoteBlockUpdater.java` | payload apply/applyBatch (`fabric: network/FabricPlatformHelper.java:282, :291`) | REPLACE-BY redirected vanilla block packets (core §4) | | |
| 12 | `SeamlessClientTeleport` dispatch | `client/SeamlessClientTeleport.java` | per-frame `checkCameraCrossingPerFrame` from `GameRendererFrameCrossingMixin.java:33` (a §4 shared host); tick-path via row 1. Lifecycle `onDisconnect` (`ClientLevelMixin.java:153`) is teardown, not a driver — survives per core §9 | REPLACE-BY `ClientTeleportationManager` (core §5; two PORT-FORWARD (verify) sub-items recorded there) | | |
| 13 | `SeamlessServerTeleport` dispatch | `entity/SeamlessServerTeleport.java` | `performCrossing` from `PortalTeleporter.java:78` + the `ClientPortalCrossingPayload` server handler (core §3) | REPLACE-BY `ServerTeleportationManager` (player half) (core §2) | | |
| 14 | `StencilPortalRenderer` entry | `render/StencilPortalRenderer.java` | `renderPortals()` at `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN` (`fabric: SeamlessPortalsClientFabric.java:26-28`) | REPLACE-BY `renderer.PortalRenderer` + `renderer.RendererUsingStencil` (render §1.1) | | |
| 15 | `PortalContextSwitch` entry | `render/PortalContextSwitch.java` | invoked from row 14's render pass (`PortalContextSwitch.java:76` documents the reentry guard) | REPLACE-BY `MyGameRenderer` + `render.context_management/*` (render §1.2; its 26.2 swap-set mechanics transplant INTO the ported shell) | | |
| 16 | `PortalWorldManager` entry | `client/PortalWorldManager.java` | five per-client-tick pumps: `advanceCompilePipelines` / `syncTimeToCachedLevels` / `tickRemoteWorlds` / `tickCachedParticles` / `evictUnboundedStores` (`fabric: SeamlessPortalsClientFabric.java:45-54`) + render-path lookups from rows 14/15 | REPLACE-BY `ClientWorldLoader` + `DimensionRenderHelper` (core §5; CRITICAL PORT-FORWARD: the 26.2 extractor/render-split plumbing re-derives into the port, core §11.1) | | |
| 17 | `CameraTransitionHandler` | `render/CameraTransitionHandler.java` | `tick()` at `GameRendererMixin.java:20` (HEAD — the excised half of the KEEP'd mixin, render §3.2) | DELETE — superseded by `TransformationManager.managePlayerRotationAndChangeGravity` (render §1.17) | | |

### 1.1 Census ADDITIONS — REPLACE rows the D3 enumeration missed (⚑ flagged per S0 task)

Each row below is dispositioned REPLACE in the tables but absent from D3's driver list. "Transitive"
= suppressed automatically once its only callers (rows above) are gated; it still gets a ledger row
so S13 can verify the transitivity claim instead of assuming it.

| # | ⚑ Addition | Path (verified) | Why D3 missed it / gate treatment | Disposition | S13 gate | S20 |
|---|---|---|---|---|---|---|
| A1 | `PortalShapeFormMixin` | `mixin/PortalShapeFormMixin.java` | block-era portal-formation hook; enqueues formations drained by row 8 (`PortalShapeFormMixin.java:39`). Gating row 8 alone leaves the queue filling — **needs its own gate** | REPLACE-BY `MixinAbstractFireBlock_CVB` (core §8) | | |
| A2 | `NetherPortalBlockMixin` | `mixin/NetherPortalBlockMixin.java` | block-behavior hooks on the vanilla portal block; every hook dies with block portals — **needs its own gate** (portal blocks can still exist flag-ON pre-S16, see §2) | REPLACE-BY `PortalPlaceholderBlock` + breakable revalidation (core §8) | | |
| A3 | `LevelChunkSetBlockStateMixin` | `mixin/LevelChunkSetBlockStateMixin.java` | block-update observation feeding row 11's send path + the prewarm probe — **needs its own gate** | REPLACE-BY IP tracking's native block sync (core §8) | | |
| A4 | `RemoteEntityApplier` | `chunk/RemoteEntityApplier.java` | client applier of the bespoke entity mirror; **transitive** — idle once row 10 (its only sender) is gated | REPLACE-BY redirected vanilla entity-packet handling (core §4) | | |
| A5 | `PortalParticleClip` | `client/PortalParticleClip.java` | called from the KEEP'd `QuadParticleGroupMixin.java:103`; flag-ON it queries an empty block-portal registry (inert but live). S13 must re-source the KEEP'd mixin's quad test from Portal entities (render §3.5) or gate the call | REPLACE-BY IP render-side clipping (`FrontClipping` + `CrossPortalEntityRenderer`) (core §5) | | |
| A6 | `PortalShapeRenderer` | `render/PortalShapeRenderer.java` | **transitive** — invoked only from rows 14/15 (`StencilPortalRenderer.java:306,:333,:383,:447,:455`; `PortalContextSwitch.java:770`) | REPLACE-BY `ViewAreaRenderer` via the `PortalShape` abstraction (render §1.3) | | |
| A7 | `MainProjectionBobMixin` | `mixin/client/MainProjectionBobMixin.java` | unconditional bob no-op on the main projection; collides with the ported IP `viewBobbingReduce` (D5 A2, checkpoint C5) — **needs a gate or the A2 resolution** at S12/S13 | REPLACE-BY IP `bobView` scaling trio + `RenderStates.viewBobFactor` (render §2.1, §3.2) | | |
| A8 | `EntityPortalCollision` | `entity/EntityPortalCollision.java` | static helpers; **transitive** — called only from rows 1/2/12 (`EntityMixin.java:139`, `LocalPlayerMixin.java:81`, `SeamlessClientTeleport.java:238`) | REPLACE-BY per-portal crossing + `CollisionHelper` family (core §2) | | |
| A9 | `ClientLevelMixin` — the chunk-load portal SCAN only | `mixin/client/ClientLevelMixin.java` | client-side block-portal detection on chunk load (`onChunkLoaded` inject `:72-73` → `PortalDetector.onNetherPortalDetectedClient` `:132`) — **the SCAN needs the gate**; the particle bypass + disconnect teardown halves survive (core §9 "mixed"; render §3.5 "KEEP (excise scan)") | scan: DELETE-with-block-portals; particle bypass: REPLACE-BY IP `MixinClientLevel`; teardown: survives wired to `ClientWorldLoader.cleanUp` (core §9) | | |

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
| B1 | `SpeculativePrewarm` | `chunk/SpeculativePrewarm.java` | transitive — ticked only from row 8 (`PortalChunkTracker.java:307`) | DELETE (core §4) | | |
| B2 | `BlockUpdateMirrorBuffer` | `chunk/BlockUpdateMirrorBuffer.java` | per-server-tick flush (`fabric: SeamlessPortalsModFabric.java:56`) — flush is a no-op once A3 (its filler) is gated, but the tick call itself should be gated with the loader dispatch | DELETE (core §4) | | |
| B3 | `ServerLevelBlockUpdateMixin` | `mixin/ServerLevelBlockUpdateMixin.java` | portal-destruction observation driving `PortalManager` (`:75,:99-102`) — **needs its own gate** | DELETE (core §8) | | |
| B4 | `HandleRespawnMixin` | `mixin/client/HandleRespawnMixin.java` | crossing-keyed paths idle flag-ON (IP's crossing never triggers `handleRespawn`), **but its player-reuse also runs on death/vanilla respawns — S13 must decide the gate scope explicitly** (core §9: death respawns revert to vanilla) | DELETE (core §9) | | |
| B5 | `ChunkMapResendSuppressMixin` | `mixin/ChunkMapResendSuppressMixin.java` | transitive — armed only by the old crossing (row 13) | DELETE (core §8) | | |
| B6 | `ClientPacketListenerForgetGuardMixin`, `ClientPacketListenerTeleportToleranceMixin` (+ `ClientPacketListenerPositionInvoker`) | `mixin/client/…` | transitive — keyed to `SeamlessClientTeleport.isInPostSwapWindow()` (`ForgetGuardMixin.java:38`, `TeleportToleranceMixin.java:57`), a window that never opens flag-ON | DELETE (core §9) | | |
| B7 | `NetherPortalUninteractableMixin` | `mixin/client/NetherPortalUninteractableMixin.java` | vanilla portal blocks CAN still form flag-ON pre-S16 (§2) — leaving it active is the conservative default; S13 decides | DELETE (core §9) | | |
| B8 | `ProjectileMixin` + `ThrownEnderpearlMixin` | `mixin/ProjectileMixin.java`, `mixin/ThrownEnderpearlMixin.java` | the row-4 gate SITE (they host `handleProjectileTick`) — gated together with row 4 | DELETE (core §8) | | |
| B9 | `PortalForcerMixin` | `mixin/PortalForcerMixin.java` | transitive — vanilla `PortalForcer` never runs while `handlePortal` is cancelled (§2); dies at S20 (IP has zero PortalForcer references, core §1/§8) | DELETE (core §8) | | |
| B10 | `LivingEntitySprintCancelDiagMixin` | `mixin/client/LivingEntitySprintCancelDiagMixin.java` | transitive — keyed to `isInPostSwapWindow()` (`:30`) | DELETE, diagnostic (core §9) | | |
| B11 | dormant legacy: `PortalDimensionManager`, `RemoteChunkManager`, `RemoteChunkData`, `RemoteClientLevel`, `SeamlessTeleportState`, `PortalLink`(data), `EntityFlagsAccessor`, `ServerPlayerMixin`(empty), `PortalSlicing`+`GameRendererObliqueClipMixin`(unregistered), `PortalFrameSuppressor`, `GlTextureViewMixin`(unregistered), `LevelRendererCullTerrainMixin`(dead target), `LevelRendererDiagMixin`(unregistered), `SectionCompilerMixin`, `ClearSkipMixin`, `MinecraftRenderTargetMixin`, `LivingEntityRendererDiagMixin` | (various — all verified present) | no live driver path / dead or unregistered per the tables — no gating required; deleted at S20 | DELETE (core §2/§4/§5/§8; render §1.18-1.19, §3.1-3.5) | n/a | |

## 2. Always-active block-era SUPPRESSIONS (both flag states)

| Suppression | Mechanism (verified) | Both-states rationale | S16 replacement plan | S16 | S20 |
|---|---|---|---|---|---|
| The `handlePortal` cancel | `EntityMixin.seamlessportals$cancelVanillaPortal` — `@Inject(method="handlePortal", at=@At("HEAD"), cancellable=true)` (`mixin/EntityMixin.java:162-166`), config-gated on `isSeamlessTeleportation`; paired vanilla-cooldown tick-down at `:70-73` | keeps vanilla nether-portal BLOCKS inert in flag-ON worlds (blocks still form from fire-on-obsidian until S16; only their teleport is cancelled) — D3 second bullet | S16 replaces it with IP's STRUCTURAL suppression: `netherPortalMode != vanilla` redirects `PortalShape.findEmptyPortalShape` to empty inside `BaseFireBlock.onPlace` (`IP: imm_ptl/peripheral/mixin/common/nether_portal/MixinAbstractFireBlock_CVB.java:27-44`; core §8 EntityMixin row; EXECUTION_PLAN S16 "structural suppression replaces handlePortal cancel (ledger update)") | | |

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
| The S3-relocated pre-render pump host | **NOT YET — created at S3** (EXECUTION_PLAN S3(a): a mod-owned mixin at the `Minecraft.renderFrame` call-site anchor, fidelity default per portal-animation.md #14; fallback `GameRenderer.update` HEAD) | to be moved there at S3: the frame-crossing pump (today `GameRendererFrameCrossingMixin.java:33` → `SeamlessClientTeleport.checkCameraCrossingPerFrame`) + the A4 re-homed upkeep (staged-upload flush, adoption prune, bridge repaint pump — today `render/StencilPortalRenderer.java:194-215`, hosted by `GameRendererPortalPrepareMixin`) | flag OFF → the block-era pump; flag ON → IP's pre-render chain (`RenderStates.updatePreRenderInfo`, `ClientPortalAnimationManagement.update`, `manageTeleportation(false)`, `MyRenderHelper.earlyRemoteUpload` — IP anchor `MixinGameRenderer.java:86-96`) | | |
| `SeamlessMixinConfigPlugin` | yes — `mixin/SeamlessMixinConfigPlugin.java` (KEEP, core §8) | mixin-set gating plumbing | reads `entityPortals` once at mixin-plugin time; registers the IP mixin configs (S0 skeleton JSONs) when ON, the block-era driver mixins when OFF (D3) | | |
| Loader entrypoints | yes — `fabric: SeamlessPortalsModFabric.java`, `fabric: SeamlessPortalsClientFabric.java` (KEEP-shape, core §10) | host the driver tick/render registrations of §1 rows 5/8/10/14/16 + B2 | tick/render registrations become flag-dispatched: block-era set vs IP init set (`ServerTeleportationManager.init`, `ImmPtlChunkTracking.init`, `EntitySync.init`, `WorldInfoSender.init`, `ImmPtlNetworking.init/initClient`, `ClientWorldLoader.init` — core §10) via the S0 seams | | |

## 5. Transition checklists (EMPTY — filled by the owning stages)

### 5.1 S13 — populate + enforce (EXECUTION_PLAN S13 step 3)

To be completed in the SAME commit that registers the IP mixin set:

- [ ] Every §1 row (1–17, A1–A9, B1–B10) has its `S13 gate` cell filled: gate site
      (`!entityPortals` branch or mixin-plugin exclusion), commit hash, or a verified
      transitivity proof for rows claiming "transitive".
- [ ] The §4 pump host's live flag dispatch landed (`S13` cell filled).
- [ ] §1.1's cross-table tension (`ApplyLightDataGuardMixin`/`ChunkLightLambdaGuardMixin`)
      resolved and rowed.
- [ ] B4 (`HandleRespawnMixin` death-respawn scope) decided and recorded.
- [ ] Unconditional-registration list confirmed (D3: entity types, `PortalPlaceholderBlock`,
      argument types, payloads — identical in both flag states).
- [ ] Both-states verification run recorded: flag OFF = BASELINE-SANITY unchanged; flag ON =
      rung-1 script; in each state exactly one driver set ran.

### 5.2 S16 — structural suppression swap (EXECUTION_PLAN S16)

- [ ] §2's `S16` cell filled: `handlePortal` cancel replaced by the ported
      `findEmptyPortalShape` redirection; the cancel's removal (or its own `!entityPortals`
      demotion) recorded.
- [ ] A1/A2/B7 rows re-checked once vanilla portal blocks can no longer form.

### 5.3 S20 — deletion + archive (EXECUTION_PLAN S20)

- [ ] Every `S20` cell filled with the deletion commit (drivers, gates, flag, block-era mixin
      registrations).
- [ ] This ledger's active role ends; file archived (plan S20: "the exclusivity ledger's active
      role (archive the file)").
