# S10-C — U8 COMMON MIXINS port-note (unit port record)

**Stage:** S10 sub-stage C · **Unit:** U8 (`qouteall/imm_ptl/core/mixin/common/**` EXCEPT `chunk_sync/` = S9, `mc_util/` = S5).
**Assembled:** 2026-07-14 from the three slice fragments (A = sync, B = collision/interaction/gui, C = debug/misc/net/portal_gen/registry) + the root-file FixGaps pass evidence. Fragments deleted at assembly (see §9).
**Governing plan:** `migration/EXECUTION_PLAN.md` §3 S10(a)/(b). **IP source (1.21.3):** `ImmersivePortalsMod/.../qouteall/imm_ptl/core/mixin/common`. **26.2 ground truth (authoritative):** `mc262-ref`. **API maps:** `api-map/mixin-common.md` (primary, §1 root · §3 entity_sync · §4 position_sync · §5 collision · §6 container_gui · §7 debug · §8 interaction · §10 miscellaneous · §11 networking · §12 other_sync · §13 portal_generation · §14 registry) + `teleportation-collision.md` (R8 §6, R12 §7) + `world-loader-root.md` §14-15/§48-49 (recurring renames).
**Discipline:** D2 verbatim `qouteall.*` held paths · D4.1 shipping-green · D4.2 probe-ledger triage (probe AUTHORITATIVE) · D4.3 `git diff --no-index` gate · **F17 R12 re-derive LINE-BY-LINE from 26.2** · recurring-rename-proactive.
**Memory lessons honoured:** `teleport-hand-glitch-chain` (nothing mutates rotation/velocity at a crossing — the R8/teleport re-derivation preserves this), `post-crossing-stutter-entity-move-flood` (Synchronizer send-path dedup preserved), `respawn-mislabel-phantom-blocks` (packet-handler isSameThread — §4).

**All 60 U8 files landed at held `qouteall.*` paths, UNREGISTERED.** The three `mixins: []` arrays
(`seamlessportals-ip-core-common.mixins.json` et al.) are untouched — S13 registers. This matches the
landed S5 (`mc_util`) / S9 (`chunk_sync`) precedent (source-only, JSON-unregistered until closure).

---

## 0. Unit accounting (the "~60 common mixins" reconciliation)

`mixin/common/**` on disk = **73** java files. Tracked (committed earlier) = 13 = `chunk_sync/` (9, S9) +
`mc_util/` (4, S5). **Untracked = 60 = the S10-C landing = unit U8** (`all mixin/common/** EXCEPT chunk_sync`,
minus the S5 `mc_util` closure). The 60 split across the three fragment slices + the root-file FixGaps pass:

| Group | # | Slice | §1 diff-gate row |
|---|---|---|---|
| root `common/*.java` | 8 | **FixGaps** (undocumented by A/B — see §7) | §1.7 |
| `collision/` | 8 | B | §1.1 |
| `interaction/` | 4 | B | §1.2 |
| `container_gui/` | 3 | B | §1.3 |
| `entity_sync/` | 6 | A | §1.4 |
| `position_sync/` | 7 | A | §1.5 |
| `other_sync/` | 4 | A | §1.6 |
| `debug/` | 9 | C | §1.8 |
| `miscellaneous/` | 5 | C | §1.9 |
| `networking/` | 1 | C | §1.10 |
| `portal_generation/` | 4 | C (lands here; S16 only registers) | §1.11 |
| `registry/` | 1 | C | §1.12 |
| **Total** | **60** | | |

`portal_generation/` double-claim (S10 vs S16) resolved per plan S10(b) "Ownership note": the 4 mixins
LAND HERE as part of U8; S16 only registers/wires them.

---

## 1. Diff-gate record per mixin group (D4.3 `git diff --no-index` vs IP)

Verdict legend: **VERBATIM** = byte-identical to IP modulo trailing-whitespace/tab normalization;
**RETARGET** = mechanical 26.2 translation, every hunk carries an api-map row; **RE-DERIVE** = R12
line-by-line re-derivation from 26.2 (§3); **NEUTRALIZED** = ANCHOR/TARGET-GONE, class shell + ducks
retained, behavior-preserving (forced-deviation register §6); **R8** = codec-wrap pair (§2).

### 1.1 `collision/` (8) — slice B
| File | Verdict | 26.2 change |
|---|---|---|
| `IEEntity_Collision` | VERBATIM | `@Invoker collideWithShapes(Vec3,AABB,List<VoxelShape>)` same descriptor, `Entity.java:1235`. |
| `MixinPlayer_Collision` | VERBATIM | `@Overwrite canPlayerFitWithinBlocksAndEntitiesWhen(Pose)` identical body, `Player.java:373-375` (`deflate(1.0E-7)`). |
| `MixinThrowableProjectile` | VERBATIM | fully inert (commented). |
| `MixinAbstractArrow` | RETARGET | inert body; `@Mixin` import repointed `projectile.AbstractArrow` → `projectile.arrow.AbstractArrow` (`arrow/AbstractArrow.java:56`). |
| `MixinEntity` | RETARGET + **R12** | `collide` redirect (target survives) + `setPosRaw` copy + `checkInsideBlocks` movement-path rework + `isClientSide()` — §3. |
| `MixinThrownEnderPearl` | RETARGET | package rename `projectile.ThrownEnderpearl` → `projectile.throwableitemprojectile.ThrownEnderpearl` (import + `discard()V` @At owner). **SEMANTICS FLAG (S14/S15):** 26.2 `onHit` teleports owner cross-dim via `TeleportTransition` (`ThrownEnderpearl.java:101-135`) BEFORE `discard()` (:147/:149); IP inject may double-fire / hit stale owner. |
| `MixinAbstractMinecartEntity` | NEUTRALIZED (F-collision-1) | debug-only `lerpTo(DDDFFI)` inject TARGET-GONE (lerp → `InterpolationHandler`; `getInterpolation()` `vehicle/minecart/AbstractMinecart.java:341`). Re-anchor recipe `setInterpolationLength(0)` recorded for S15. |
| `MixinProjectile` | NEUTRALIZED (F-collision-2) | `@Redirect ServerLevel.getEntity(UUID)` in `getOwner()` ANCHOR-GONE: 26.2 `getOwner()` = `EntityReference.getEntity(owner, level())` resolving cross-dim natively (`Projectile.java:60-62`, `EntityReference.java:78-81`). IP's all-levels loop is now vanilla → neutralized, behavior-preserving. `extends MixinEntity` retained. |

### 1.2 `interaction/` (4) — slice B
| File | Verdict | 26.2 change |
|---|---|---|
| `IEClipContext` | VERBATIM | `@Accessor block`/`fluid` private-final fields survive (`ClipContext.java:24-25`). |
| `MixinItem_Interaction` | VERBATIM | fully inert (commented; its `BlockManipulationServer` reference is inside a comment, so NOT a compile forward-ref). |
| `MixinBucketItem` | RETARGET | `Direction.getNearest(d,d,d)` → `Direction.getApproximateNearest(d,d,d)` (`Direction.java:303`). `use`/`getPlayerPOVHitResult` redirect targets intact (`BucketItem.java:43,45`). |
| `MixinServerPlayerGameMode` | RETARGET + **AW/AT** (§5) | `@WrapOperation ServerPlayer.canInteractWithBlock` → `isWithinBlockInteractionRange(BlockPos,double)` (owner `ServerPlayer`; `Player.java:1993`, call `ServerPlayerGameMode.java:151`). `redirectNewUseOnContext` builds the now-`protected` 5-arg `UseOnContext` (`UseOnContext.java:24`) → AW+AT pair. `method="useItemOn"` kept (no `doProcessUseItemOn` in 26.2 — §3/§6). |

### 1.3 `container_gui/` (3) — slice B
| File | Verdict | 26.2 change |
|---|---|---|
| `MixinAbstractContainerMenu` | RETARGET | `method="method_17696"` (dead intermediary) → `method="lambda$stillValid$0"` (javap: `private static Boolean lambda$stillValid$0(Block,Player,Level,BlockPos)`). WrapOperation `canInteractWithBlock` → `isWithinBlockInteractionRange` (owner `Player`). `@Local(argsOnly) Level` still binds. |
| `MixinContainer` | RETARGET | WrapOperation `Player.canInteractWithBlock` → `isWithinBlockInteractionRange` (owner `Player`; call `Container.java:100`). `method` descriptor `stillValidBlockEntity(…BlockEntity;…Player;F)Z` unchanged; `@Local BlockEntity` binds. |
| `MixinContainerOpenersCounter` | RE-DERIVE (F-collision-4) | `getPlayersWithContainerOpen(Level,BlockPos):List<Player>` GONE → `getEntitiesWithContainerOpen(Level,BlockPos):List<ContainerUser>` (`ContainerOpenersCounter.java:51`). HEAD-cancel re-implemented: iterate all server players (cross-dim, IP intent) with `isOwnContainer(player)` filter, collect `List<ContainerUser>` (`Player implements ContainerUser`, `Player.java:126`). `isOwnContainer` shadow protected→public (`:26`). |

### 1.4 `entity_sync/` (6) — slice A
| File | Verdict | 26.2 change |
|---|---|---|
| `MixinPersistentEntitySectionManager` | VERBATIM | marker duck. |
| `MixinChunkMap_E` | RETARGET + rename | `@Redirect addEntity`→`TrackedEntity.updatePlayers(List)` (:1152); `@Inject removeEntity/tick HEAD` cancel. `entity.getServer()`→`entity.level().getServer()` (:65). |
| `MixinServerEntity` | RETARGET | KEEP `@Inject sendChanges HEAD` + `@Redirect removePairing/addPairing`→`ServerGamePacketListenerImpl.send(Packet)` (:262/:268). **DROP `onSendToWatcherAndSelf`** — `ServerEntity.broadcastAndSend` GONE (sends flow via `synchronizer.sendToTrackingPlayersAndSelf`); coverage → `MixinTrackedEntity` (category-(c-2)). |
| `MixinServerGamePacketListenerImpl_Redirect` | RETARGET | `send(Packet,listener)` 2nd param `PacketSendListener`→`ChannelFutureListener` (:160); inner `Connection.send(Packet,ChannelFutureListener,boolean)` (:168/`Connection:281`). `@ModifyVariable HEAD`+`@Inject INVOKE` descriptors updated. |
| `MixinServerPlayer` | RETARGET | `ip_stopRidingWithoutTeleportRequest`: `super.stopRiding()`→**`super.removeVehicle()`** (bypass moved — §3); `ip_startRidingWithoutTeleportRequest`: `super.startRiding(v,true)`→**`super.startRiding(v,true,false)`** (2-arg GONE); ctor `Player(Level,GameProfile)` (:175). |
| `MixinTrackedEntity` | RETARGET (**AW/AT pre-widened S4**) | `broadcast`→**`sendToTrackingPlayers`** (:1345); `broadcastAndSend`→**`sendToTrackingPlayersAndSelf`** (:1352, self-send :1355); **NEW `onSendToFilteredNearbyPlayers`**→`sendToTrackingPlayersFiltered` (:1359/:1363 — the path `ServerEntity.sendChanges` :93 now uses; category-(c-3)). `@Overwrite updatePlayer/updatePlayers` no-op. `ip_updateEntityTrackingStatus` @IPVanillaCopy (§3). `chunkPosition().x/.z`→`.x()/.z()` (:188); `.getServer()`→`.level().getServer()` (:288). |

### 1.5 `position_sync/` (7) — slice A
| File | Verdict | 26.2 change |
|---|---|---|
| `MixinServerboundMovePlayerPacket_S` | VERBATIM | duck-field holder. |
| `MixinServerboundMovePlayerPacketPos` / `PosRot` / `Rot` / `StatusOnly` | VERBATIM ×4 | `@Inject read(FriendlyByteBuf) RETURN` cancellable — 26.2 kept the hand-written `read` factories (`Packet.codec(write,read)`); non-final nested classes ⇒ direct duck cast. |
| `MixinPlayerPositionLookS2CPacket` | **R8** codec-wrap (§2) | `ClientboundPlayerPositionPacket` is a FINAL record; no `write`/`<init>(buf)` anchors → `@Redirect <clinit> StreamCodec.composite(...)`. |
| `MixinServerGamePacketListenerImpl` (priority 900) | RETARGET + **R12** + isSameThread | `@Inject handleMovePlayer` AFTER `ensureRunningOnSameThread` (:1065 — §4); `@Overwrite teleport(PositionMoveRotation,Set<Relative>)` re-derived (§3); anticheat → `isEntityCollidingWithAnythingNew` (§3); `@Inject handleAcceptTeleportPacket` `absMoveTo`→`absSnapTo` (:539); `handlePlayerCommand` PUTFIELD `awaitingPositionFromClient` (:1731); `.location()`→`.identifier()` ×4. |

### 1.6 `other_sync/` (4) — slice A
| File | Verdict | 26.2 change |
|---|---|---|
| `IEServerConfigurationPacketListenerImpl` | VERBATIM | `@Accessor gameProfile`. |
| `MixinMapItemSavedData` | VERBATIM | inert. |
| `MixinPlayer_Pose` | VERBATIM | `@Inject updatePlayerPose HEAD` cancel. |
| `MixinPlayerList` (priority 800) | RETARGET + rename | `@Inject sendLevelInfo RETURN`/`placeNewPlayer TAIL`; `@Inject broadcastAll(Packet,ResourceKey) HEAD` cancel; `@Redirect respawn`→`ServerPlayer.restoreFrom` (:396); `@Overwrite broadcast(...)`. `new ChunkPos(BlockPos)`→`ChunkPos.containing(...)` (:113); `chunkPos.x/.z`→`.x()/.z()` (:116/:125); `.getServer()`→`.level().getServer()` (:129). |

### 1.7 root `common/*.java` (8) — **FixGaps pass** (see §7 for why this group was reconstructed)
Real change measured with `diff -w` (the raw diff-line counts are inflated by IP's trailing-whitespace
on blank lines). All three retargets are self-documented in-tree with `S10-C` markers + api-map §1 cites.
| File | Verdict | 26.2 change |
|---|---|---|
| `MixinClipContext` | VERBATIM | `diff -w` = 0 changed lines. |
| `MixinConnection_Debug` | VERBATIM | `diff -w` = 0. |
| `MixinDedicatedServer` | VERBATIM | empty marker `@Mixin(DedicatedServer.class)`. |
| `MixinMinecraftServer` | VERBATIM | `onServerClose @Inject runServer RETURN` + `ip_getPerServerInfo` duck — no anchor change. |
| `MixinServerChunkCache` | VERBATIM | `@Shadow @Final DistanceManager` + `ip_getDistanceManager` duck. |
| `MixinLivingEntity` | RETARGET | 1-arg `setLastHurtByPlayer(Player)` GONE → **`setLastHurtByPlayer((Player) null, 0)`** (2-arg `(Player,int)` `LivingEntity.java:632`; clears `lastHurtByPlayer`+time, matching vanilla's own clear idiom :1370-1371; `+import …player.Player`). IP asymmetry (test `getLastHurtMob`, clear via `setLastHurtByPlayer`) preserved. api-map §1. |
| `MixinLevel` | NEUTRALIZED (**F-common-MixinLevel-1**) | `@Inject prepareWeather()V TAIL` (zero nether rain/thunder) ANCHOR-GONE: `Level.prepareWeather()` no longer exists (moved to `private ServerLevel.prepareWeather(WeatherData)` `ServerLevel.java:702`). Role is now VANILLA: nether `canHaveWeather()`==false (`Level.java:857-858`, hasCeiling) gates the ctor call (`ServerLevel.java:272-274`) + `advanceWeatherCycle` (:713) → nether rainLevel/thunderLevel stay 0 unaided. Inject commented out; class shell + ducks + shadows retained. Behavior-preserving. api-map §1. |
| `MixinServerLevel` | RETARGET | `@Redirect List.isEmpty()` (empty-dim gate) GONE → **`ServerChunkCache.hasActiveTickets()`** (`ServerLevel.java:408`; return `true` on `shouldLoadDimension` = keep-ticking, exact 1.21.3 semantics). Unused `getDataStorage()` shadow `DimensionDataStorage`→`SavedDataStorage` (:1442). `onToString` `.location()`→`.identifier()`. api-map §1. |

### 1.8 `debug/` (9) — slice C — VERBATIM ×9 (`diff --no-index` byte-identical)
Active: `IEChunkHolder_Debug` (`@Invoker updateFutures`, `ChunkHolder.java:273`); `MixinHashMapPalette`/`MixinLinearPalette`
(`@Redirect write`→`IdMap.getId(Object)I`, signature-less `method="write"` re-binds to `write(FriendlyByteBuf,IdMap)`,
`HashMapPalette.java:83`/`LinearPalette.java:91`); `MixinServerLevel_Debug` (HEAD-cancel on `private boolean addEntity(Entity)`
`ServerLevel.java:994`; `LOGGER` :191). Inert (fully commented) ×5: `MixinChunkTaskPriorityQueue`,
`MixinClientboundSectionBlocksUpdatePacket_Debug`, `MixinDistanceManager_Debug`, `MixinPlayerTicketTracker_Debug`,
`MixinServerChunkCacheMainThreadExecutor`.

### 1.9 `miscellaneous/` (5) — slice C — 4 VERBATIM, 1 RETARGET
| File | Verdict | 26.2 change |
|---|---|---|
| `IEEndDragonFight` | RETARGET | class renamed `EndDragonFight`→**`EnderDragonFight`** (`@Mixin` + import; old filename ABSENT). Members survive: `@Accessor("needsStateScanning")` (:100), `@Invoker("scanState")` (:221). |
| `MixinBlockGetter` | VERBATIM | `@ModifyVariable HEAD argsOnly index 1` on `static traverseBlocks(Vec3 from,Vec3 to,…)` (`BlockGetter.java:112`; index 1 = `to`, unchanged; tab-indented source byte-reproduced). |
| `MixinEnderDragon` / `MixinFishingHook` | VERBATIM ×2 | `imm_ptl_canTeleportThroughPortal` override → `false`. |
| `MixinLeashable` | VERBATIM | inert TODO. |

### 1.10 `networking/` (1) — slice C — VERBATIM
`MixinClientboundCustomPayloadPacket` (HEAD-cancel on `handle(ClientCommonPacketListener)` `ClientboundCustomPayloadPacket.java:33`; `payload` record-component shadow) — isSameThread decision §4.

### 1.11 `portal_generation/` (4) — slice C — 3 VERBATIM, 1 RETARGET
| File | Verdict | 26.2 change |
|---|---|---|
| `MixinItemStack` | VERBATIM | RETURN inject on `useOn(UseOnContext)` (`ItemStack.java:357`); `world.getServer()` = `Level.getServer()` (:168) SURVIVES → no change. |
| `MixinMinecraftServer_P` / `MixinPlayerList_P` | VERBATIM ×2 | inert. |
| `MixinItemEntity_P` | RETARGET (+ api-map amendment §8) | `@Shadow UUID thrower`→`@Shadow EntityReference<Entity> thrower` (`ItemEntity.java:52`); `level().getProfiler().push/pop`→`Profiler.get().push/pop` (`Level.getProfiler()` GONE; `Profiler.java:47`); **`this_.getServer()`→`this_.level().getServer()`** (`Entity.getServer()` REMOVED — §8). `thrower==null` guard preserved (a null `EntityReference` still = never-thrown). |

### 1.12 `registry/` (1) — slice C — VERBATIM
`IERegistryDataLoader` (empty interface mixin on `RegistryDataLoader`, inert).

**Diff-gate roll-up:** 60 files — **~40 VERBATIM** (byte-identical modulo whitespace), **~16 RETARGET**
(every hunk api-map-rowed), **1 RE-DERIVE** (`MixinContainerOpenersCounter`), **3 NEUTRALIZED**
(`MixinAbstractMinecartEntity`, `MixinProjectile`, `MixinLevel`). Every non-verbatim hunk maps to an
api-map row (two rows amended — §8). No undocumented hunk.

---

## 2. R8 position-stamp PAIR (F9) — the lock-step deliverable

**File:** `position_sync/MixinPlayerPositionLookS2CPacket` (the WHOLE pair) + the send-site stamp in
`position_sync/MixinServerGamePacketListenerImpl.teleport` (§3). **Design:** S08-teleportation §6 Option (a)
codec-wrap (RECOMMENDED). 26.2's `ClientboundPlayerPositionPacket` is a **final record** serialized entirely
by a composite `STREAM_CODEC` built once in `<clinit>` — there is **no `write(FriendlyByteBuf)`** (IP 1.21.3's
server `onWrite` anchor) and **no `<init>(FriendlyByteBuf)`** (IP 1.21.3's client `onRead` anchor,
`mixin/client/sync/MixinClientboundPlayerPositionPacket`). A SINGLE `STREAM_CODEC` field serves both wire
directions ⇒ **ONE common mixin is the whole lock-step pair.**

**Idiom = vanilla-Mixin `@Redirect` (category-(c-1)).** S08 §6 wrote "@ModifyExpressionValue (MixinExtras) …
already on classpath" — FALSE at slice-A authoring time (`compileOnly mixin:0.8.5` only; S07-network.md §393
records "MixinExtras `@Local` is NOT on the common compile classpath"). Realized instead via a `@Redirect` on
the sole `StreamCodec.composite(StreamCodec,Function,StreamCodec,Function,StreamCodec,Function,Function3)` INVOKE
in `<clinit>` — `mixin-common.md §4` lists `@ModifyExpressionValue`/**`@Redirect`** on `composite` as the
alternatives. Protocol + atomicity identical to S08 (dim rides the same byte stream; no id-correlation table, no
arrival-order race, no re-queue/netty-pre-pass hazards). **Note:** slice B later added MixinExtras to
`common/build.gradle` for its own `@WrapOperation` needs (§5 build-infra), so `@ModifyExpressionValue` is now
*available*; the R8 file was NOT re-cut to it — `@Redirect` is equally faithful, already landed and probe-clean,
and re-cutting would be a churn deviation. Recorded, deliberate.

**The two halves (one `private static StreamCodec ip_wrapStreamCodec(...)` handler; `<clinit>` is static):**
rebuilds the ORIGINAL composite from the redirected args, returns a delegating `StreamCodec`:
- **ENCODE (server-write half):** `original.encode(buf, packet); buf.writeResourceKey(((IEPlayerPositionLookS2CPacket)(Object)packet).ip_getPlayerDimension())`
  — **UNCONDITIONAL** (matches IP's unconditional server `onWrite`; every sent packet is stamped by the `teleport`
  overwrite so the field is never null on the send path).
- **DECODE (client-read half):** `packet = original.decode(buf); if (ImmPtlNetworkConfig.doesServerHaveImmPtl()) { ((IEPlayerPositionLookS2CPacket)(Object)packet).ip_setPlayerDimension(buf.readResourceKey(Registries.DIMENSION)); }`
  — the gate reproduces IP's client `onRead` gate, preventing buffer underflow against a vanilla server.
  `doesServerHaveImmPtl()` (`ImmPtlNetworkConfig:310`) is `public static`, common-safe.

**The round-trip (bytes written ⇔ bytes consumed):**
1. Server `teleport` builds `ClientboundPlayerPositionPacket.of(...)`, stamps `ip_setPlayerDimension(destDim)` on it, sends.
2. `encode` writes the vanilla record fields THEN appends the dim `ResourceKey` (trailing byte-tail).
3. Client `decode` reads the vanilla record THEN, gated on `doesServerHaveImmPtl()`, reads the same trailing
   `ResourceKey` into the duck field.
4. CLIENT CONSUMER `MixinClientPacketListener.handleMovePlayer` READS `ip_getPlayerDimension()` — a **CLIENT-slice
   mixin (S12)**, out of this common slice; but the byte-level pair integrity is COMPLETE here.

**Lock-step satisfied:** encode + decode land in ONE mixin/one commit — a half-landed R8 corrupts the byte stream.
Duck casts use the `(Object)` intermediate (final record ⇒ inconvertible-types otherwise). No rotation/velocity is
touched at the crossing (`teleport-hand-glitch-chain` lesson honoured — the stamp is a trailing dim key only).

---

## 3. R12 vanilla-copy re-derivation table (F17 — LINE-BY-LINE from `mc262-ref`, NEVER patched from 1.21.3)

This slice holds **4 of the 6** `@IPVanillaCopy` common mixins (`collision/MixinEntity`,
`entity_sync/MixinChunkMap_E`, `entity_sync/MixinTrackedEntity`, `position_sync/MixinServerGamePacketListenerImpl`);
the other 2 (`mc_util/MixinEntitySection`, `mc_util/MixinEntitySectionStorage`) landed at S5. The table also
resolves each item the mission named (**collide · checkInsideBlocks · anticheat · interpolation-kill · removeVehicle · doProcessUseItemOn**).

| Item / `@IPVanillaCopy` body | 1.21.3 form | 26.2 re-derivation | 26.2 citation |
|---|---|---|---|
| **setPosRaw copy** — `MixinEntity.ip_setPositionWithoutTriggeringCallback` | `this.chunkPosition.x/.z` (fields); `new ChunkPos(this.blockPosition)` | `chunkPosition.x()/.z()` (record accessors); `ChunkPos.containing(this.blockPosition)`; **still OMIT** the three callbacks 26.2 added (`levelCallback.onMove()` + `WaypointTransmitter` + `ServerPlayer` waypoint branches) — IP's whole point is a callback-free set | `ChunkPos.java:19,45`; `Entity.java:3795-3796,3800-3809` |
| **collide** — `MixinEntity.redirectHandleCollisions` (`@Redirect` on `Entity.collide(Vec3)` inside `move(MoverType,Vec3)`) | redirect wraps `collide(Vec3)` | **TARGET SURVIVES** — `Entity.collide(Vec3)Vec3` unchanged; redirect body verbatim (cross-portal collision handler + fast-move guard). Probe-green confirms the anchor resolves | `Entity.java` `collide(Vec3)` (probe-verified) |
| **checkInsideBlocks** — `MixinEntity` (F-collision-3) | `@Redirect getBoundingBox()` inside `checkInsideBlocks()V` + `@Inject INVOKE_ASSIGN`-after cancel-when-null | movement-path rework: `@Redirect makeBoundingBox(to)` inside per-segment `checkInsideBlocks(Vec3,Vec3,StepBasedCollector,LongSet,int)I` → `ip_getActiveCollisionBox(makeBoundingBox(to))`; `@Inject HEAD` of that overload returns `0` iterations when the clip is null (guards the deflate NPE, preserves "no effects when fully ghost", now decided per-segment). Redirect handler's own `makeBoundingBox` is a different call site → no recursion | `Entity.java:948` (`applyEffectsFromBlocks`), `:1269` (list overload), `:1299/:1302` (`makeBoundingBox(to).deflate(1.0E-5F)`) |
| **isClientSide** — `MixinEntity.ip_tickCollidingPortal` | `level.isClientSide` (public field) | `level.isClientSide()` (accessor; field now `private final`) | `Level.java:127` (field), `:163` (accessor) |
| **teleport `@Overwrite`** — `MixinServerGamePacketListenerImpl.teleport` | `@Overwrite teleport(double×3,float×2,Set<RelativeMovement>)` (GONE) | RE-DERIVED onto `teleport(PositionMoveRotation destination, Set<Relative> relatives)`. Vanilla body order (`awaitingTeleportTime` → `++awaitingTeleport` → `teleportSetPosition(destination,relatives)` → `awaitingPositionFromClient = player.position()`). 1.21.3 relative-delta base block (`xBase/…` + hand-built packet) DELETED (26.2 carries relative/absolute inside `PositionMoveRotation`+`relatives`). IP's added layer preserved: removed-player HEAD guard, `serverTeleportLogging` (log arg → `destination.position()`), `ip_dimOfAwaitingPosition` stamp, **R8 dim stamp on the sent `.of(...)` packet** (§2). Duck cast `((…)(Object) lookPacket)` (final record) | `ServerGamePacketListenerImpl.java:1261-1270`; `Entity.java:3142` (`teleportSetPosition`) |
| **anticheat** — `MixinServerGamePacketListenerImpl.onIsEntityCollidingWithAnythingNew` | `@Inject isPlayerCollidingWithAnythingNew` | RENAMED + generalized → `isEntityCollidingWithAnythingNew(LevelReader, Entity, AABB oldAABB, double×3)` (called from BOTH `handleMovePlayer` AND `handleMoveVehicle`). `this.player`→`entity`; `level.getCollisions(player,box)` → `level.getPreMoveCollisions(entity, activeNewBB.deflate(1e-5), oldAABB.getBottomCenter())`. Active-box via `IEEntity.ip_getActiveCollisionBox`. **Behavior-review flag (S15):** IP's cross-portal exemption now extends to the NEW vehicle path (no 1.21.3 counterpart) | `ServerGamePacketListenerImpl.java:1243-1255`; `CollisionGetter.java:90` |
| **removeVehicle** — `entity_sync/MixinServerPlayer.ip_stopRidingWithoutTeleportRequest` | `super.stopRiding()` (bypass ServerPlayer packets) | INEFFECTIVE in 26.2: `Entity.stopRiding`(:2476) calls `this.removeVehicle()` VIRTUAL → hits `ServerPlayer.removeVehicle`(:2138-2150, sends packets). Retarget `super.removeVehicle()`: from `MixinServerPlayer extends Player`, `super`=Player INVOKESPECIAL `Player.removeVehicle`(:857, dismount + `boardingCooldown=0`, NO packets) — exactly IP's intent. `startRiding(v,true,false)` = force + no events. **Behavior-review flag (S15):** confirm old-vehicle client `SetPassengers` resync not needed (IP 1.21.3 didn't send it either) | `Entity.java:2464,2476`; `Player.java:857`; `ServerPlayer.java:2138-2150` |
| **interpolation-kill** — `getPositionCodec().setBase(...)` | (IP kills client interpolation on crossing) | **N/A THIS SLICE** — lives in the **S8-landed** `ClientTeleportationManager`/`McHelper`, not the collision/sync mixins. Listed for completeness; no U8 file carries it | S8 port-note (`teleport-hand-glitch-chain`, `post-crossing-stutter-entity-move-flood`) |
| **doProcessUseItemOn** — `interaction/MixinServerPlayerGameMode` | `@Redirect NEW UseOnContext` inside `doProcessUseItemOn` | **NO `doProcessUseItemOn` in 26.2** (grep of whole tree empty). The `new UseOnContext(player,hand,hitResult)` redirect site lives directly in `useItemOn` → faithful anchor `method="useItemOn"`. `InteractionResult.Success.swingSource()` (`InteractionResult.java:33`) is unrelated to IP's `@At NEW UseOnContext` redirect. Plan-text discrepancy, not a code change | `ServerPlayerGameMode.java:383` (`useItemOn`) |
| **updateEntityTrackingStatus** — `MixinTrackedEntity.ip_updateEntityTrackingStatus` (`@IPVanillaCopy`) | vanilla `updatePlayer` copy | VERBATIM + `.x()/.z()` rename only. 26.2 `updatePlayer`(:1383-1406) differs from 1.21.3 by (a) the visibility PREDICATE (distance+`isChunkTracked`) which IP replaces WHOLESALE with its `ImmPtlChunkTracking.recWatches` record predicate (IP's entire point), and (b) NEW `debugSynchronizers()` hooks (DEBUG game-event only) which IP's custom method intentionally omits. No correctness-critical side effect missed | `ChunkMap.java:1383-1406` |
| **ChunkMap removeEntity** — `MixinChunkMap_E` (`@IPVanillaCopy` `@Inject removeEntity HEAD cancel`) | HEAD-cancel mirrors vanilla `removeEntity` | ports clean; `entity.getServer()`→`entity.level().getServer()` (:65) | `ChunkMap.removeEntity(Entity)` |

---

## 4. isSameThread-guard inventory (`respawn-mislabel-phantom-blocks` lesson)

Server packet-handler injects are guarded by injecting AFTER vanilla's
`PacketUtils.ensureRunningOnSameThread(...)` (26.2's re-queue-throw re-runs the handler on the game thread on
pass 2, so a HEAD side-effect would fire twice).

| Site | Guard | Evidence |
|---|---|---|
| `MixinServerGamePacketListenerImpl.onProcessMovePacket` | **Explicit** — `@At AFTER ensureRunningOnSameThread` in `handleMovePlayer` (:1065) | slice A |
| `MixinServerGamePacketListenerImpl.onHandleAcceptTeleportPacket` | `@At INVOKE absSnapTo` (:539), AFTER the HEAD `ensureRunningOnSameThread` (:532) | slice A |
| `MixinServerGamePacketListenerImpl.onTeleportPlayerCancelSleeping` | `@At FIELD PUTFIELD` (:1731), AFTER `handlePlayerCommand` HEAD guard (:1718) | slice A |
| `MixinServerGamePacketListenerImpl.onIsEntityCollidingWithAnythingNew` | helper, only reached from already-guarded `handleMovePlayer`/`handleMoveVehicle` — not a top-level handler | slice A |
| C2S read mixins (`Pos/PosRot/Rot/StatusOnly.read`) | run on the **netty decode thread** by design (deserialization); only populate a duck field — no game-thread guard applies or is wanted | slice A |
| R8 codec `encode`/`decode` (`MixinPlayerPositionLookS2CPacket`) | netty pipeline (serialization) — same, no guard | §2 |
| `MixinClientboundCustomPayloadPacket` (networking) | **NO guard added — VERBATIM (deliberate).** IP has none; **cancels at HEAD** (`cancellable`) for redirect payloads, so `handle` never reaches `handleCustomPayload`→`ensureRunningOnSameThread` — no throw-to-reschedule, no second invocation. The lesson's double-fire hazard (HEAD side-effects on a `ClientPacketListener` re-pass) does not apply. Non-redirect branch is a no-op. If runtime double-dispatch ever proves a problem it becomes a forced-deviation at S13+, never a silent port-time edit | §slice C, `respawn-mislabel-phantom-blocks` |
| `MixinServerEntity` / `MixinTrackedEntity` / `MixinChunkMap_E` / `MixinPlayerList` | server-tick injects (not packet handlers) — no isSameThread concern | slices A |

---

## 5. AW+AT pairs grown this slice, and build-infra growth

**Rule (S10.1):** this repo is NeoForge ModDevGradle — `accesstransformer.cfg` is the `:common` COMPILE-time
widener; ANY access-widen needs BOTH a `seamlessportals.accesswidener` entry AND a PAIRED
`accesstransformer.cfg` entry.

### AW+AT pair GROWN this slice (exactly one)
`interaction/MixinServerPlayerGameMode.redirectNewUseOnContext` constructs the 5-arg `UseOnContext` verbatim; the
ctor was **public in 1.21.3** (no IP AW entry existed) and is **`protected` in 26.2** (`UseOnContext.java:24`).
Both files show ` M` (modified this slice); both entries carry the `Entity-portal migration S10-C` marker.
- **AW** (`seamlessportals.accesswidener:57`): `accessible method net/minecraft/world/item/context/UseOnContext <init> (Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/BlockHitResult;)V`
- **AT** (`META-INF/accesstransformer.cfg:48`): `public net.minecraft.world.item.context.UseOnContext <init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/BlockHitResult;)V`

### AW+AT PRE-EXISTING (used, not grown, this slice)
`entity_sync/MixinTrackedEntity` targets `ChunkMap$TrackedEntity` (a private nested class in 26.2). Its
`accessible class net/minecraft/server/level/ChunkMap$TrackedEntity` AW+AT pair was grown at **S4** (marked
`Entity-portal migration S4`), part of the recurring `storageSource`/`ServerPlayer.server`/`ChunkMap$TrackedEntity`
convention. This slice consumes it; it did NOT grow it.

### Build-infra growth (`common/build.gradle`, ` M` this slice; NOT `buildSrc`)
`compileOnly group:'io.github.llamalad7', name:'mixinextras-common', version:'0.5.3'` (`common/build.gradle:83`).
This slice is the FIRST held IP code to use MixinExtras (`@WrapOperation`/`@Local`/`Operation`) — the 4 real
importers are `container_gui/MixinAbstractContainerMenu`, `container_gui/MixinContainer`,
`interaction/MixinBucketItem`, `interaction/MixinServerPlayerGameMode` (the R8 file mentions the package only in a
Javadoc comment — not an import). `compileOnly` because the loader bundles MixinExtras at runtime (the neoforge run
uses 0.5.4), mirroring the existing `compileOnly org.spongepowered:mixin`. Removed at S20.

---

## 6. Forced-deviation register (this unit) + category-(c) resolutions

### Forced deviations
- **F-collision-1** `MixinAbstractMinecartEntity`: TARGET-GONE debug inject (`lerpTo`) neutralized; behavior-neutral (default flag inert). Re-anchor (`setInterpolationLength(0)`) deferred S15.
- **F-collision-2** `MixinProjectile`: ANCHOR-GONE cross-dim owner redirect neutralized; behavior-preserving (26.2 resolves owner cross-dim via `getEntityInAnyDimension`).
- **F-collision-3** `MixinEntity` checkInsideBlocks: redirect+cancel redesigned onto the 26.2 movement-path walker (§3). Behavior target preserved.
- **F-collision-4** `MixinContainerOpenersCounter`: HEAD-cancel re-implemented against renamed `getEntitiesWithContainerOpen`/`List<ContainerUser>`; IP's all-players cross-dim intent preserved.
- **F-common-MixinLevel-1** `MixinLevel`: `Level.prepareWeather()` ANCHOR-GONE + obsolete-by-vanilla; inject NEUTRALIZED (nether `canHaveWeather()`==false gate now does the job). Behavior-preserving.

### Category-(c) resolutions (D4.3 residual — written up, not slice-map rows)
- **(c-1)** R8 idiom = `@Redirect` codec-wrap, NOT S08's stated `@ModifyExpressionValue` — forced by MixinExtras absence at authoring time; same protocol/design; realizes `mixin-common.md §4`'s `@Redirect` alternative (§2).
- **(c-2)** `MixinServerEntity.onSendToWatcherAndSelf` DROPPED (`broadcastAndSend` GONE) — coverage relocated to `MixinTrackedEntity`. Forced by 26.2's Synchronizer refactor.
- **(c-3)** `MixinTrackedEntity.onSendToFilteredNearbyPlayers` ADDED (no 1.21.3 counterpart) — mandatory to keep `ServerEntity.sendChanges`'s new `sendToTrackingPlayersFiltered` path dimension-redirected (`mixin-common.md §13.3`). Mirrors the `sendToTrackingPlayers` redirect.
- **(c-4)** `teleport` overwrite is a primitive re-derivation onto `PositionMoveRotation`, not a byte-copy of vanilla `teleport(PositionMoveRotation,Set)` — vanilla `.of()` extracted to stamp the dim (§3).
- **(c-5)** anticheat vehicle-exemption extension (§3) — genuinely new decision, flagged S15.
- **(c-6)** `MixinLevel` inject neutralization (F-common-MixinLevel-1) — obsolete-by-vanilla; the residual un-dimensioned rain-flip broadcast (`ServerLevel.java:785-795`) is a SEPARATE dimension-scoped packet-path concern, not this construction-time inject.
- **(c-7)** `MixinClientboundCustomPayloadPacket` VERBATIM, no isSameThread guard — HEAD-cancel forecloses the double-fire hazard (§4).

---

## 7. Probe-vs-U8-union triage (the count reduction from the common-mixin landing)

**Union target:** the 60 untracked U8 files (§0). Before this stage, `common/**/mixin/common` held only the
13 tracked S5/S9 files; after S10-C the full U8 union is present in the working tree.

**Probe runs (D4.2, `-Pip_scc_closed=true` — AUTHORITATIVE ledger):**
- **Slice A probe:** first pass 11 slice-A errors, ALL documented recurring renames (`getServer`, `ChunkPos.x/.z`,
  `new ChunkPos(BlockPos)`, `ResourceKey.location`, `Player(Level,GameProfile)` — §world-loader-root §14-15/§48-49);
  after the mechanical fixes total dropped **260→249, ZERO on slice-A files** (no cascade). Slice A contributes
  zero forward-ref debt — it compiles against the landed held tree.
- **Slice B probe:** reduces to **ONLY** the documented `block_manipulation.BlockManipulationServer` forward-ref
  (U11 → resolves S13) at `MixinAbstractContainerMenu:12`, `MixinContainer:15`, `MixinServerPlayerGameMode:25`
  (+ their `validateReach`/`REDIRECT_CONTEXT` cascade lines). Zero other errors on slice-B files.
- **Slice C:** probe NOT run (A/B landed concurrently — a full probe would report their not-yet-landed files);
  forward-ref debt enumerated below, all documented held debt (no translation slip).
- **Root FixGaps group:** NOT covered by any slice probe run (see the FixGaps finding below). Static evidence:
  8/8 root files present; `diff -w` = 0 for the 5 VERBATIM; the 3 RETARGET carry only api-map §1 hunks and self-cite.

**Residual forward-ref debt introduced by U8 (all resolve ≤ S13):**
| Symbol | Referenced by | Lands | Ledger |
|---|---|---|---|
| `BlockManipulationServer` | `MixinAbstractContainerMenu`, `MixinContainer`, `MixinServerPlayerGameMode` | S13 (U11) | slice B / plan S10(b) |
| `CustomPortalGenManager` | `MixinItemEntity_P`, `MixinItemStack` | S13 | plan S10(b) |
| `IPPerServerInfo` | `MixinItemEntity_P`, `MixinItemStack`, `MixinMinecraftServer` | S4 (held) | S4 |
| `IPMcHelper`, `Helper` | `MixinBlockGetter` | S5 / S2 (landed) | S5/S2 |
| `ImmPtlEntityExtension` | `MixinEnderDragon`, `MixinFishingHook` | S7 (api, held) | S7 |
| `IECustomPayloadPacket`, `PacketRedirection` | `MixinClientboundCustomPayloadPacket` | S4 / S7 (held) | S4/S7 |

**Net probe reduction:** the common-mixin landing takes the U8 slice from "not present" to "present + green
against the landed held tree, with debt reduced to exactly the S13-closing forward-refs." Slice A drove the total
260→249; slice B's residue is the single `BlockManipulationServer` cluster; slice C added 20 files with zero new
slip. **No U8 symbol is a translation slip; every residual is documented held debt closing at S13.**

**FixGaps finding (this assembly) — the 8 root files were undocumented by fragments A/B.** Slice C §0 attributed
`common/*.java` to "slices A/B", but neither the slice-A fragment (17 files: `entity_sync`+`position_sync`+`other_sync`)
nor the slice-B fragment (15 files: `collision`+`interaction`+`container_gui`) actually enumerated them — a
fragment-coverage gap, NOT a code gap. The FixGaps pass verified all 8 are ported, on-disk, self-documented in-tree
with `S10-C` markers + api-map §1 citations, and match the api-map §1 decisions exactly (5 VERBATIM by `diff -w`,
3 RETARGET incl. F-common-MixinLevel-1). Their diff-gate rows are reconstructed at §1.7. **Residual risk:** these 8
were never run through a `-Pip_scc_closed=true` probe by any slice; they carry no @IPVanillaCopy/@Overwrite bodies
(no R12/R8 re-derivation risk) and their forward-refs (`IPPerServerInfo`) are held-debt already ledgered — but a
targeted probe over the full U8 union is the correct closing check (queued into §8's re-verify).

---

## 8. api-map amendments (fold into the primary map)

- **§1 (root):** the three retargeted root files match api-map §1 rows verbatim (`MixinLevel` neutralization,
  `MixinLivingEntity` 2-arg `setLastHurtByPlayer`, `MixinServerLevel` `hasActiveTickets`+`SavedDataStorage`+`identifier`).
  Recommend the map note that §1 was NOT lifted into a fragment (the FixGaps gap, §7).
- **§3/§12 recurring-rename carry:** `mixin-common.md §3/§12` marks `MixinChunkMap_E`, `MixinServerPlayer`,
  `MixinPlayerList` "PORTS-CLEAN", but they carry the governed recurring renames (`ChunkPos.x/.z`→`.x()/.z()`,
  `new ChunkPos(BlockPos)`→`ChunkPos.containing`, `Entity.getServer()`→`.level().getServer()`,
  `Player(Level,GameProfile)` — all in `world-loader-root.md §14-15/§48-49`). Recommend flagging these three as
  "carry the recurring-rename set" rather than strictly clean.
- **§13 AMENDMENT — `Entity.getServer()` removed in 26.2 (`MixinItemEntity_P`):** api-map §13 flagged the
  `thrower` retype + `getProfiler()` rewrite but MISSED a third break — IP's body calls `this_.getServer()` on an
  `ItemEntity`, i.e. `Entity.getServer()`, which **no longer exists** (grep of `Entity.java` finds only
  `Level.getServer()` calls). Faithful translation = inline exactly what IP-1.21.3's `Entity.getServer()` did
  (`return this.level().getServer();`) → `this_.level().getServer()` (`Level.java:168`, same `@Nullable MinecraftServer`
  return type). Reached only after the `!isClientSide()` guard, so `level()` is a `ServerLevel` and `getServer()`
  is non-null — semantically identical. Mechanical translation, not a deviation. `MixinItemStack`'s neighbouring
  `world.getServer()` is `Level.getServer()` and needs NO such fix.

---

## 8b. VERIFICATION TIER

**Tier: OPUS-FALLBACK (re-verify QUEUED).** Per the migration model-tier policy (memory
`migration-model-tier-policy`), workflow agents escalate to `model:'fable'` for adversarial-verify + design ONLY on
the hard stages **{S6, S8, S11, S12, S13}**. **S10 is NOT in that set**, so S10-C's port + this assembly ran on
Opus 4.8 (the default tier), i.e. the Opus-fallback path — safety comes from the workflow gates (D4.1/D4.2/D4.3),
not the tier. Consequently a **Fable adversarial re-verify of S10-C is QUEUED**, to run before S13 closure (S13 IS
a Fable stage). The queued re-verify must specifically cover:
1. The **8 root files** (never probe-run by a slice — §7 FixGaps): a full `-Pip_scc_closed=true` probe over the
   complete U8 union, confirming zero root-file errors beyond ledgered held-debt.
2. The **R8 codec-wrap round-trip** (§2) against a live crossing (byte-tail encode ⇔ gated decode) — a LIVE
   checkpoint item at S13 first-light regardless.
3. The two **S15 behavior-review flags**: anticheat vehicle-exemption (§3, c-5) and `removeVehicle` old-vehicle
   `SetPassengers` resync (§3).
4. The **`MixinThrownEnderPearl` double-fire semantics flag** (§1.1) — 26.2 native cross-dim ender-pearl teleport.

Until the Fable re-verify runs, the gate evidence in this note (three green diff-gates + slice-A 260→249 probe +
slice-B single-cluster residue + static root-file `diff -w`) is the Opus-fallback assurance of record.

---

## 9. Assembly bookkeeping
- **Sources consumed:** `migration/fragments/S10C-collision.md` (slice B), `migration/fragments/S10C-entitysync.md`
  (slice A), `migration/port-notes/fragments/S10C-misc.md` (slice C) + the root-file FixGaps evidence (this pass).
- **Both fragments directories DELETED** at assembly end (`migration/fragments/`, `migration/port-notes/fragments/`).
- **Not done (per task constraints):** no gradle run, no git commit, no runClient, no mixin-JSON registration, no
  `buildSrc` edit. All 60 U8 files remain held-UNREGISTERED for S13.
