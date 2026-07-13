# Adversarial verification: current mod, non-render core (inventory + api-map) — pass 2

Docs verified (as they stand on 2026-07-12):
- INV = `migration/inventory/current-mod-core.md`
- MAP = `migration/api-map/current-mod-core.md`

Lineage note: a prior verify pass flagged one MAJOR finding (the `ImmPtlClientChunkMap`
install point being misattributed to `createSecondaryClientWorld`). **Both docs have since
been amended** — they now cite the `ClientLevel`-constructor mixin — and this pass
independently re-derived the amended claim from IP source and confirms it (F7 below).

Method: re-opened every cited file for 68 load-bearing claim clusters — geometry/sign
claims re-derived from the mod + IP source; every "exists in 26.2" claim re-checked
against `C:/Users/warwa/ModDev/mc262-ref` with signatures compared to the IP call site;
GONE verdicts re-grepped across the whole qouteall tree; flow claims traced in IP source;
scope compared against a live directory listing.
Citations: `IP <path>:<line>` = `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`,
`26.2 <path>:<line>` = mc262-ref, bare paths = this repo.

**Verdict: MINOR — 5 refuted claims, none of which misdirects the port: four are IP-name
errors in the INVENTORY that the api-map's §0 already corrects (all four corrections were
independently re-verified as accurate), and one is a stale cross-doc provenance note in
the api-map. All operative dispositions, geometry, and API fates check out.**

---

## 1. Geometry / sign / transform claims (re-derived, highest priority)

| # | Claim (doc) | Verdict | Evidence |
|---|---|---|---|
| G1 | INV §2.1: `transformTeleportPoint` = depth-NEGATED (:46-64); `transformPoint` = same-sign depth | **CONFIRMED** | PortalTransform.java:54 `new LocalCoords(-local.depth(), local.width(), 0)`; transformPoint (:19-39) keeps `local.depth()` unnegated (:29) |
| G2 | INV: `transformVector` = depth-negated (:167-180); `transformVelocityMotion` = plain same-sign local map (:199-202) | **CONFIRMED** | PortalTransform.java:178 negates depth; :199-202 is `fromLocalCoords(dest, toLocalCoords(src, v))`, no sign change |
| G3 | INV: `transformYaw` ±90° on axis mismatch (:204-209); overshoot MIN 0.08 / MAX 0.5 (:90-91); `applyExitOvershoot` (:156-165); `crossingDepthSign` (:116-123); `crossingDepthSignFromState` (:132-139); `sourceDepthOvershoot` (:94); local decomposition (:218-230) | **CONFIRMED** | All at the exact cited lines; axis=X → depth=world Z, axis=Z → depth=world X, consistent across toLocalCoords/fromLocalCoords/applyExitOvershoot |
| G4 | INV: height mapping is floor-relative (origin Y), not center-relative | **CONFIRMED** | PortalTransform.java:32/:57 `sourcePos.y() − source.getOrigin().getY()`, re-based on dest origin Y (:36/:61) |
| G5 | INV: `applyCoordinateScale` (:232-245) is private dead code; the 8x scale is applied only at link creation, which FLOORS | **CONFIRMED** | grep: sole hit is the declaration (zero callers). PortalManager.java:514-530 `computeExpectedDestination` uses `Math.floor` on both /8 and ×8 branches |
| G6 | INV: PortalInfo deterministic UUID from `dim@x,y,z/axis`, width/height excluded (:32-36); `intersectsMovement` = plane straddle + t∈[0,1] + aperture bounds (:96-115); axis/AABB/normal conventions (:48-79); `containsPoint` inflate 0.1 (:81) | **CONFIRMED** | PortalInfo.java at exactly those lines |
| G7 | MAP §1: IP transform surface — `transformPoint` :464, `transformLocalVec` :474 (scaled), `transformLocalVecNonScale` :1202, `inverseTransformLocalVec` :1220, `inverseTransformPoint` :1224, `isMovedThroughPortal` :1231 → `rayTrace` | **CONFIRMED** | IP Portal.java:464/:474/:1202/:1219 (doc said 1220, off-by-one)/:1224/:1231-1243; transformLocalVec = nonScale rotate then `.scale(scaling)` — quaternion+scale model as claimed |
| G8 | MAP §2: `getRegularEntityTeleportedEyePos` :585-607 — eye-segment raytrace (5-block back-extension), `transformPoint(collidingPoint) + dir·0.05` | **CONFIRMED** | IP ServerTeleportationManager.java:585-607 verbatim |

## 2. "Exists in 26.2" claims (file re-opened, signature checked against the IP call site)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| V1 | MAP §2: `ServerLevel.removePlayerImmediately` 26.2 :1018, `addDuringTeleport` :967, `ServerPlayer.setServerLevel` :2021, `Entity.unsetRemoved` protected :3926 | **CONFIRMED** | 26.2 ServerLevel.java:1018 `(ServerPlayer, Entity.RemovalReason)` / :967 `(Entity)`; ServerPlayer.java:2021; Entity.java:3926 `protected void unsetRemoved()` — matches IP's usage in changePlayerDimension |
| V2 | INV §2.9 / MAP §8: `Entity.setLevel(Level)` protected at 26.2 Entity.java:3962 — duck/invoker still required | **CONFIRMED** | Exact line, `protected` |
| V3 | MAP §2/§11.4: `Entity.restoreFrom` 26.2 Entity.java:3025 (NBT copy — the transient-state loss class) | **CONFIRMED** | Exact line |
| V4 | MAP §8: `ServerLevel.canSpreadFireAround` survives, 26.2 :1783 | **CONFIRMED** | Exact line |
| V5 | MAP §8/§11.5: 26.2 `TicketType` = record with PRIVATE `register(String,long,int)` (:10, :27), flag constants :12-16 | **CONFIRMED** | TicketType.java:10 record; :12-16 FLAG_PERSIST/LOADING/SIMULATION/KEEP_DIMENSION_ACTIVE/CAN_EXPIRE_IF_UNLOADED; :27 private register |
| V6 | MAP §10: `EntityType.Builder.of` :479, `.sized` :487, `.eyeHeight` :497, `.fireImmune` :550, `.clientTrackingRange` :565 **takes chunks**, `.updateInterval` :570, `.build(ResourceKey)` :590 | **CONFIRMED** | All present; the 26.2 parameter is literally named `clientChunkRange` — the blocks(96)→chunks conversion warning (§11.6) is real |
| V7 | MAP §10: no 26.2 `forceTrackedVelocityUpdates` knob, but `trackDeltas()` is hardcoded true except a fixed vanilla exclusion list (:418-426) that cannot contain modded types | **CONFIRMED** | 26.2 EntityType.java:418-431 — `this != EntityTypes.PLAYER && … && this != EntityTypes.EVOKER_FANGS`, vanilla constants only |
| V8 | MAP §2 note: IP's one-arg `entity.getType().create(toWorld)` needs 26.2 two-arg `create(Level, EntitySpawnReason)` | **CONFIRMED** | 26.2 EntityType.java:298; no one-arg `create(Level)` exists |
| V9 | INV §2.9: `FireworkRocketEntity.isAttachedToEntity` private, present on 26.2 | **CONFIRMED** | 26.2 FireworkRocketEntity.java (usages :101/:106); the mod's accessor compiles against it |

## 3. GONE verdicts (rename hunt in mc262-ref)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| N1 | MAP §8/§11.5: `TicketType.create` (IP ImmPtlChunkTickets.java:60-61) has NO 26.2 equivalent | **CONFIRMED** | grep `create` in 26.2 TicketType.java = 0 hits; only factory is the private `register`. IP side: :60-61 `TicketType.create("imm_ptl", Comparator.comparingLong(ChunkPos::toLong))` exact |
| N2 | MAP §1/§8: IP has ZERO `PortalForcer` references | **CONFIRMED** | `grep -rn PortalForcer` over the whole qouteall tree: 0 hits |

## 4. Dependency / flow claims (traced in IP source)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| F1 | MAP §0.3/§3: crossing request = `ImmPtlNetworking.TeleportPacket(int dimensionId, Vec3 eyePosBeforeTeleportation, UUID portalId)` :45-47 → `ServerTeleportationManager.onPlayerTeleportedInClient` (:76-84); no swapSeq, no exit sign | **CONFIRMED** | ImmPtlNetworking.java:45-47 record fields exact; :76-84 handle → onPlayerTeleportedInClient (declared StM.java:159-160) |
| F2 | MAP §0/§3 + INV §2.3: portal sync = dedicated `PortalSyncPacket(id, uuid, entityType, dimensionId, x, y, z, extraData)` :130-143, "this packet is redirected" | **CONFIRMED** | ImmPtlNetworking.java:129-143 — javadoc cites `ClientboundAddEntityPacket` + redirection; fields exact |
| F3 | MAP §2: `changePlayerDimension` :421-485 is IP's OWN move — removePlayerImmediately + `ip_unsetRemoved` + setServerLevel + addDuringTeleport (:438-447); NO vanilla `teleportTo`, NO respawn packet, NO forced flags-resync; vehicle crossing built in (:431-464) → `teleportVehicleAcrossDimensions` :680 | **CONFIRMED** | Read in full at :421-485; the body contains none of the vanilla-teleport machinery; teleportVehicleAcrossDimensions declared :680. Also validates MAP §8's `EntityFlagsAccessor` DELETE evidence |
| F4 | MAP §2: `teleportRegularEntity` :507-583 — proximity :522 (`getDistanceToNearestPointInPortal > 5`), gametime cooldown :527-532, passenger re-attach :541-558, post-teleport position RPC :567-575 (`McRemoteProcedureCall` — the §11.8 q_misc_util dependency), → `changeEntityDimension(..., recreateEntity=true)` :615-678, recreate branch = `create(toWorld)` + `restoreFrom` + `setId` + `remove(CHANGED_DIMENSION)` + `addDuringTeleport` (:642-661) | **CONFIRMED** | All at cited lines; `qouteall/q_misc_util/` dir exists |
| F5 | MAP §2/§8: detection is PORTAL-driven — `SERVER_PORTAL_TICK_SIGNAL` (IP Portal.java:210, invoked :952) → `getEntitiesToTeleport` (AABB `inflate(2)` + eye-segment, StM :148-158) → `startTeleportingRegularEntity` :108-146 (skips players :109, portals :112, ridden/player-cluster :115, fresh xo/yo/zo==0 :128, deferred via ServerTaskList :137); projectiles unified — `shouldEntityTeleport` :94-106 extends the segment by world velocity for `Projectile` (:100-102); **no attached-firework guard anywhere in the skip list** | **CONFIRMED** | All verified. The absence of a firework guard + the `restoreFrom` recreate soundly grounds MAP §11.3/§11.4 (hazards persist in IP → PORT-FORWARD (verify) for `FireworkRocketEntityAccessor` / `LivingEntityHurtAccessor`) |
| F6 | MAP §3: `PacketRedirection.withForceRedirect` :67, `sendRedirectedPacket` :111, `createRedirectedMessage` :135, Payload record → `PacketRedirectionClient.handleRedirectedPacket` (:280); client handler :45 (networking-thread aware, `withSwitchedWorldFailSoft`) | **CONFIRMED** | All exact (Payload record declared :246; doc's ":249-282" covers the body — immaterial) |
| F7 | MAP §5 + INV §2.5 (the amended install-point claim, load-bearing KEEP): IP installs `ImmPtlClientChunkMap` via a `ClientLevel`-CONSTRUCTOR mixin covering EVERY client world — `MixinClientLevel.onConstructed` (`@Inject(method="<init>", @At("RETURN"))`, :97-110) sets shadow `chunkSource = O_O.createMyClientChunkManager(...)` (O_O.java:88-89 → `new ImmPtlClientChunkMap`); `createSecondaryClientWorld` :389-487 contains zero chunk-map code | **CONFIRMED** | MixinClientLevel.java:96-109 exact; O_O.java:88-90 exact; ClientWorldLoader.java:389+ has only a `chunkLoadDistance` ctor arg ("my own chunk manager doesn't need it"). `ImmPtlClientChunkMap extends ClientChunkCache` (ImmPtlClientChunkMap.java:43); mod twin `SeamlessClientChunkMap extends ClientChunkCache` + `super(level, 1)` (SeamlessClientChunkMap.java:62/:88). The prior pass's MAJOR finding is fully fixed in both docs |
| F8 | MAP §5: ClientWorldLoader — WORLD_RENDERER_MAP :67, RENDER_HELPER_MAP :69, isClientRemoteTicking :80, init :84, tick :111 → tickRemoteWorld :154, cleanUp :208, getWorld :308, createSecondaryClientWorld :389, _onWorldRendererReloaded :503, withSwitchedWorld :544/:585 | **CONFIRMED** | All exact (grep -n) |
| F9 | MAP §5: ClientTeleportationManager — teleportLimitPerFrame=3 :80, acceptSynchronizationDataFromServer :102, manageTeleportation(boolean) :121, tryTeleport :211, getPlayerEyePos :316, teleportPlayer :320, changePlayerDimension :458, moveClientEntityAcrossDimension :552 | **CONFIRMED** | All exact |
| F10 | MAP §4: ImmPtlChunkTracking — updateInterval=13 :41, removePlayerFromChunkTrackersAndEntityTrackers :58, PlayerWatchRecord :78, updateForPlayer :160, isPlayerWatchingChunk :401/:427, isPlayerWatchingChunkWithinRadius :435, getPlayersViewingChunk :467, forceRemovePlayer :500, syncBlockUpdateToClientImmediately :619 | **CONFIRMED** | All exact |
| F11 | MAP §4 + INV §2.4: ImmPtlChunkTickets TICKET_TYPE :60-61 / addTicket :233 / purge :248 / getLoadingRadius :314; `ChunkVisibility.getDirectLoadingDistance` :37 = full <5 blocks, ×2⁄3 <15, else ÷3, capped by `IPGlobal.indirectLoadingRadiusCap` (:53) — the graduated model INV credits the mod with matching | **CONFIRMED** | All exact; tier thresholds/divisors read from source |
| F12 | MAP §7: IPGlobal.indirectLoadingRadiusCap :44 (default 8), netherPortalMode :170, POST_CLIENT_TICK_EVENT :26; IPConfig.indirectLoadingRadiusCap :107, clamp(1,32) :158 (identical to the mod's), applied :182 | **CONFIRMED** | All exact |
| F13 | MAP §4/§10: EntitySync init :15 / update :23 / tick :46; WorldInfoSender init :18 / sendWorldInfo :47; ImmPtlNetworking init :238 / initClient :257; StM.init :65-82 on Fabric `ServerTickEvents.END_SERVER_TICK`; IPModMainClient.init :71; DimensionRenderHelper :9; MyRenderHelper.lateUpdateLight :441; entity_sync mixin files (MixinChunkMap_E, MixinServerEntity, MixinTrackedEntity, MixinServerPlayer) + collision mixin files (MixinProjectile, MixinThrownEnderPearl, MixinAbstractArrow, MixinEntity) all exist; IPModMenuConfigEntry exists | **CONFIRMED** | All verified (grep -n / ls) |
| F14 | MAP §9: MixinClientPacketListener — handleMovePlayer :88-89, `ClientLevel.getEntity(I)` redirect in handleSetEntityData :162-166, handleAddEntity :219-220, handleLevelChunkWithLight :269-285, handleForgetLevelChunk :286+; ducks IEEntity.ip_unsetRemoved :33 / ip_setWorld :41 (consumed at StM :439/:665/:670), IEClientPlayNetworkHandler.ip_setWorld :6, IEMinecraftClient ip_setFrameBuffer :9 / ip_setWorldRenderer :13 / ip_setRenderBuffers :15; ducks/ = 36 files; MixinMinecraft exists | **CONFIRMED** | All exact (handleForgetLevelChunk at :287) |
| F15 | MAP §1/§8: NetherPortalGeneration startGeneratingPortal :110 / checkPortalGeneration :252 / findFrameShape :264 / embodyNewFrame :284 / fillInPlaceHolderBlocks :294; PortalManipulation completeBiWayPortal :79 / createReversePortal :88; PortalExtension :15; Mirror.ENTITY_TYPE :13; PortalPlaceholderBlock extends Block :30; BreakablePortalEntity.blockPortalShape :44 + validity :66; IPModMain.registerBlocks :155 / registerEntityTypes :162 (BiConsumer — loader-agnostic, grounding INV §5's registration plan); PortalState :18; PortalAPI :32; CustomPortalGeneration :33; IPMixinPlugin implements IMixinConfigPlugin :11; SodiumInterface = compile-time `net.caffeinemc.mods.sodium.*` imports (:3-8) | **CONFIRMED** | All exact — MAP §6's compile-time-vs-reflection AMBIGUOUS framing for SodiumBridge is accurate |
| F16 | MAP §1/§10: `Portal.ENTITY_TYPE` :87 built by `createPortalEntityType` :94-108 = `FabricEntityTypeBuilder.create(MISC, ctor).dimensions(fixed(0,0)).fireImmune().trackRangeBlocks(96).trackedUpdateRate(20).forceTrackedVelocityUpdates(true).build()` | **CONFIRMED** | IP Portal.java:87, :94-108 verbatim (96 is in BLOCKS via the Fabric builder — the §11.6 conversion risk is real given V6) |
| F17 | INV §2.2: `SeamlessServerTeleport` — box `inflate(8.0)` proximity (:106-111), `Math.signum` sanitize + server-derived fallback (:118-123), vanilla `teleportTo(destLevel, x,y,z, Relative.union(DELTA, ROTATION), 0,0, false)` (:222-223), explicit server-side rotation set after | **CONFIRMED** | SeamlessServerTeleport.java:107, :119-123, :222-223, :228-231 |
| F18 | INV §2.9: EntityMixin — skips ServerPlayers (:86-88), skips attached boost fireworks via `seamlessportals$isAttachedToEntity()` (:101-105), cancels `handlePortal` HEAD when seamless on (:162-167) | **CONFIRMED** | EntityMixin.java at those lines |
| F19 | INV §5: `onPlayerDisconnect`/`clearPlayer` hooks (PortalChunkTracker :748, PortalEntityTracker :481, BlockUpdateMirrorBuffer :81) have ZERO call sites | **CONFIRMED** | grep across common/fabric/neoforge: declarations only. MAP §4's "bonus fix" pointer (`removePlayerFromChunkTrackersAndEntityTrackers` :58 / `forceRemovePlayer` :500) verified in F10 |
| F20 | INV §2.3/§2.11: ModPayloads = exactly 19 payloads (661 LOC); NeoForge registers only 5 | **CONFIRMED** | grep -c `implements CustomPacketPayload` = 19; wc = 661; NeoForgePlatformHelper.java = 4 `playToClient` + 1 `playToServer` |
| F21 | INV §2.9/§2.12: mixins.json = 52 client + 16 common entries, common block at :61-78; INV's root table (16 registered + the plugin) matches disk | **CONFIRMED** | Counted: 16 entries (lines 62-77), 52 client entries; 17 root .java files = 16 mixins + SeamlessMixinConfigPlugin |
| F22 | MAP §8 (EntityMixin row): vanilla-portal suppression is STRUCTURAL — with `netherPortalMode != vanilla`, the `PortalShape.findEmptyPortalShape` redirect returns `Optional.empty()` so vanilla portal blocks never form | **CONFIRMED** | MixinAbstractFireBlock_CVB.java:27-44: disabled → empty; vanilla → passthrough; else onFireLitOnObsidian + `return Optional.empty()` |

## 5. MAP §0 corrections — independently re-verified (all four accurate)

| # | Correction | Verdict | Evidence |
|---|---|---|---|
| C1 | `McHelper.getServerPortalsNearby` does not exist; surface = `IPMcHelper.getNearbyPortals` :77/:82 + `McHelper.getEntitiesNearby` :204/:219 + `ChunkVisibility.getNearbyPortals` :68 + `PortalUtils.raytracePortals` :31 | **CONFIRMED** | grep = 0 hits for the bogus name; all replacement cites exact |
| C2 | `NetherPortalGeneration.onFireLitOnObsidian` does not exist; the ignition chain is peripheral: `MixinAbstractFireBlock_CVB` (:20-45, redirect inside `BaseFireBlock.onPlace`) → `IntrinsicPortalGeneration.onFireLitOnObsidian` :71 → `startGeneratingPortal` :110; the port must include `imm_ptl.peripheral.portal_generation` | **CONFIRMED** | grep `onFireLitOnObsidian`: only peripheral hits (MixinAbstractFireBlock_CVB.java:37, MixinFlintAndSteelItem_CVB.java:69, IntrinsicPortalGeneration.java:71). No class named `MixinFireBlock` exists anywhere in IP |
| C3 | `ImmPtlNetworking.TeleportRequest` → actually `TeleportPacket` :45-47 | **CONFIRMED** | See F1 |
| C4 | `TeleportationUtil.checkTeleportation` → actually `checkStaticTeleportation` :97 + `checkDynamicTeleportation` :146, returning `Teleportation` :61; `transformEntityVelocity` :50 | **CONFIRMED** | TeleportationUtil.java exact |

## 6. REFUTED claims

| # | Doc | Claim | Correction |
|---|---|---|---|
| R1 | INV §2.1 (PortalTracker row) | Replacement cited as "`McHelper.getServerPortalsNearby`" | Method does not exist in IP (grep = 0). Correct surface: `IPMcHelper.getNearbyPortals` (IP IPMcHelper.java:77/:82), `McHelper.getEntitiesNearby` (IP McHelper.java:204/:219), `PortalUtils.raytracePortals` (IP PortalUtils.java:31). Already corrected in MAP §0.1; correction verified accurate. |
| R2 | INV §2.1 PortalDetector row + §2.9 PortalShapeFormMixin row | Ignition hook named "`MixinFireBlock` → `NetherPortalGeneration.onFireLitOnObsidian`" | Neither name exists. Real chain: `imm_ptl.peripheral...MixinAbstractFireBlock_CVB` (:20-45) → `IntrinsicPortalGeneration.onFireLitOnObsidian` (peripheral, :71) → `NetherPortalGeneration.startGeneratingPortal` (:110). The peripheral-module consequence is real. Already corrected in MAP §0.2; verified. |
| R3 | INV §2.3 (ClientPortalCrossingPayload row) | Replacement = "`ImmPtlNetworking.TeleportRequest`" | Actual record: `ImmPtlNetworking.TeleportPacket(int dimensionId, Vec3 eyePosBeforeTeleportation, UUID portalId)` (IP ImmPtlNetworking.java:45-47). Already corrected in MAP §0.3; verified. |
| R4 | INV §2.2 (EntityPortalCollision row) | Replacement includes "`TeleportationUtil.checkTeleportation`" | No such method: `checkStaticTeleportation` (:97) and `checkDynamicTeleportation` (:146). Already corrected in MAP §0.4; verified. |
| R5 | MAP §0 (upgrade note) + §3 PortalLinkPayload row | "`PortalLinkPayload` is REPLACE, not DELETE — **Upgraded from the inventory's DELETE**" | The inventory as it stands says REPLACE for `PortalLinkPayload` (INV §2.3, with the full PortalSyncPacket citation) — the "upgraded from DELETE" provenance is stale (the inventory was evidently amended after MAP was written). The disposition itself is correct and the docs agree; only the cross-doc characterization is wrong. |

## 7. Scope completeness (silent-skip check vs live directory listing)

- `api/ chunk/ client/ compat/ config/ entity/ network/ portal/`: 36 files on disk; every one has an INV §2 row. **No skips.**
- Root `mixin/`: 17 files = 16 registered (mixins.json:62-77) + `SeamlessMixinConfigPlugin`; all 17 in INV §2.9. **No skips.**
- `mixin/client/`: every file absent from INV §2.10 was opened and confirmed render-slice (`ClearSkipMixin` = GlCommandEncoder no-op placeholder documenting the FBO-clear fix; `CameraInvokerMixin` = dest-camera positioning accessors; plus the GameRenderer*/LevelRenderer*/stencil/particle/SOG/ShaderManager/SkyRenderer/LevelExtractor* families) — consistent with the slice boundary and INV's explicit cross-reference list. **No silent skips.**
- Loader modules + build: INV §2.11-2.12 matches `fabric/src`, `neoforge/src`, buildSrc contents (NeoForge parity gaps re-verified in F20).

## 8. Non-refuting imprecision notes

- INV §2.5: the remote-tick enable flag lives on `IPCGlobal` (`IPCGlobal.isClientRemoteTickingEnabled`, used at ClientWorldLoader.java:112), not `IPGlobal`; `isClientRemoteTicking` is on ClientWorldLoader :80 as stated.
- MAP §3: `PacketRedirection.Payload` record declared at :246 (doc says :249-282, which is its body).
- MAP §1: `inverseTransformLocalVec` declared at :1219 (doc says :1220).
- INV §5 (final line): "5/18 payloads" — 19 is correct (INV itself says 19 elsewhere; grep-verified 19).

## 9. Summary

- Claims checked: **68** · Confirmed: **63** · Refuted: **5** (R1-R5)
- **Severity: MINOR.** R1-R4 are IP-name errors confined to the inventory doc and already
  corrected — accurately — by the api-map's §0 (each correction independently re-verified);
  R5 is a stale provenance note with no technical content. Nothing that survives into the
  definitive api-map misdirects the port. The highest-risk load-bearing claims — the
  ClientLevel-constructor chunk-map install (F7), the TicketType invoker necessity (V5/N1),
  chunks-vs-blocks tracking range (V6/F16), the `restoreFrom` transient-loss class and the
  absent firework guard grounding both PORT-FORWARDs (F4/F5/V3), the peripheral-module
  requirement (C2), and the `q_misc_util` hard dependency (F4) — are all confirmed at
  source level.
