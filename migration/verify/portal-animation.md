# Adversarial Verification: Portal Animation slice

Verifier pass over:
- `migration/inventory/portal-animation.md` (inventory)
- `migration/api-map/portal-animation.md` (api-map)

Method: every claim re-derived/re-opened from source. IP citations relative to
`C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`; 26.2 citations relative to
`C:/Users/warwa/ModDev/mc262-ref`. Line numbers pinned with direct reads/grep, not trusted from the docs.

(Supersedes the earlier verify pass on this slice. That pass refuted two inventory claims — the
getAnimationView/getAnimationHolder "same order" error and the missing fourth `inverseScale` site — and the
inventory has since been corrected: it now states the two holder methods use different orders and must be
ported verbatim (§2.13), and lists all four inverseScale sites incl. parallel `:439` (§2.10). Both
corrections were re-verified against source in this pass — see C26/C27 — and are accurate.)

**Result: 55 claims checked, 52 CONFIRMED, 2 REFUTED (both minor wording-level, neither misdirects the port), 1 PLAUSIBLE (unverifiable without a 1.21.3 vanilla decompile). Severity: minor.**

The two documents are of unusually high accuracy in their current state — every geometry/sign claim
re-derived correct, every 26.2 line citation opened landed on the exact declaration, the api-map's verdict
totals (7 CHANGED + 20 SAME + 1 FABRIC-API = 28) reconcile exactly against the inventory's §4 touchpoint
list, and the slice scope (14 files / 2,639 LOC) matches `wc -l` of the package exactly, with all 14 files
covered in inventory §2. No scope files silently skipped.

---

## REFUTED (minor)

### R1. api-map #19: "Vanilla's own client spawn does exactly IP's sequence inside `Entity.recreateFromPacket`"
- **Doc:** `migration/api-map/portal-animation.md`, #19 table row.
- **Claim:** vanilla's `recreateFromPacket` does "exactly IP's sequence".
- **Fact:** the *set* of operations matches but the *order* differs. Vanilla 26.2:
  `syncPacketPositionCodec → snapTo → setId → setUUID → setDeltaMovement`
  (`net/minecraft/world/entity/Entity.java:3834-3844`). IP: `setId → setUUID →
  syncPacketPositionCodec → moveTo` (`core/network/ImmPtlNetworking.java:214-217`).
- **Impact:** none in practice — the row's own citation lists vanilla's true order, and the migration
  instruction ("keep IP's exact call order") is the correct fidelity rule regardless. Correction: say
  "the same set of calls", not "exactly IP's sequence".

### R2. inventory §2.12: StableClientTimer "converges to the server-synced time (+1 tick)"
- **Doc:** `migration/inventory/portal-animation.md`, §2.12 responsibility line.
- **Claim:** the stable clock converges to server-synced time **plus 1 tick**.
- **Fact:** the convergence target in code is *exactly* the server-synced time with no offset:
  `Time serverSyncedTime = new Time(worldGameTime, partialTicks); Time targetTime = serverSyncedTime;`
  (`core/portal/animation/StableClientTimer.java:185-186`). The "+1 tick" exists only in IP's
  javadoc prose (`StableClientTimer.java:25`), which the inventory cites; the actual +1 is applied at
  *evaluation* time (`usedTickTime = isTicking ? stableTickTime + 1 : stableTickTime`,
  `ClientPortalAnimationManagement.java:130`) — which the inventory itself describes correctly in §2.11/§3.4.
- **Impact:** small but real misdirection risk: an implementer working from the §2.12 sentence could add a
  spurious +1 inside the timer. Correction: the timer targets server-synced time verbatim; the +1 lives in
  the consumer.

## PLAUSIBLE (not independently verifiable)

### P1. api-map #14: "in 1.21.3, `render` HEAD preceded `Camera.setup`, which ran inside `renderLevel`"
A claim about vanilla **1.21.3** internals; no 1.21.3 decompile is on hand. Corroborating evidence:
`Camera.setup(BlockGetter, Entity, ZZF)` exists as a 1.21.3 mixin target in IP
(`core/mixin/client/render/MixinCamera.java:42`) and IP calls it manually in its own cross-portal render
(`core/render/TransformationManager.java:232`), consistent with camera positioning happening at
world-render time in 1.21.3. The *consequence* (the 26.2 hook must move before `GameRenderer.update`) rests
entirely on 26.2 facts, all of which are CONFIRMED below (C34–C37).

---

## CONFIRMED — geometry / sign / transform (all re-derived from IP source)

| # | Claim (doc) | Evidence |
|---|---|---|
| C1 | `extractOtherSide`: orientation = `rotation ⨯ orientation ⨯ flipAxisW` (identity for mirror); sizes × `scaling` | `UnilateralPortalState.java:78-92` — exact hamilton-product chain and `isMirror ? identity : flipAxisW` |
| C2 | `combine`: "does not work for mirror"; `scale = otherSide.width / thisSide.width`; rotation via `computeDeltaTransformation`; result `isMirror=false` | `UnilateralPortalState.java:94-123` (:94 comment, :105 scale, :101-103 rotation, :119 `false`) |
| C3 | `Builder.rotate`: `orientation = rotation ⨯ orientation` (rotation on the LEFT) | `UnilateralPortalState.java:308-311` |
| C4 | `UnilateralPortalState.Builder.apply(delta)` scales width, height **and thickness**; `build()` validates only `width>0 && height>0` | `UnilateralPortalState.java:328-341`, `:251-260` (:255-256) |
| C5 | `DeltaUnilateralPortalState.apply(Builder)` applies only x/y scaling, **not** thickness — the asymmetry the inventory flags; driver path uses the Builder-side (thickness-scaling) method | `DeltaUnilateralPortalState.java:106-117` vs `UnilateralPortalState.java:328-341`; driver path `PortalAnimation.java:350`, `:372` calls `thisSideState.apply(...)` = Builder-side |
| C6 | `getFlipped()`: `rotation ⨯ flipAxisW`, offset and scaling unchanged | `DeltaUnilateralPortalState.java:98-104` |
| C7 | `fromDiff`: rotation = `after ⨯ before⁻¹`; sizeScaling **z = width ratio** (quirk); result purged | `DeltaUnilateralPortalState.java:162-178` (:171 rotation, :175 z=width ratio, :177 purge) |
| C8 | `getInverse()` reciprocal scaling with z divide-by-zero guard | `DeltaUnilateralPortalState.java:24-33` (:30) |
| C9 | `interpolate(a,b,p) = a.getPartial(1-p).combine(b.getPartial(p))`; `getPartial` slerps rotation from identity, lerps scaling from 1 | `DeltaUnilateralPortalState.java:79-96` |
| C10 | RotationAnimation delta: rotation by `degreesPerTick * passedTicks` about `rotationAxis`, offset = `rotate(initialOffset) - initialOffset`; pivot displaced by `-initialOffset` from the reference position | `RotationAnimation.java:94-106`; re-derivation: newPos = ref + R(v) − v ⇒ with c = ref − v, newPos = c + R(ref − c), so pivot = ref − initialOffset; corroborated by the command building `initialOffset = originPos − rotationCenter` (`core/commands/PortalAnimationCommand.java:632-640`) |
| C11 | Time convention `real time = tickTime - 1 + partialTicks`, used identically by both drivers | `PortalAnimationDriver.java:53` (contract), `NormalAnimation.java:176`, `RotationAnimation.java:81` |
| C12 | `RectInvariant` 8 dihedral symmetries with width/height swap; `turnToClosestTo` minimizes `DQuaternion.distanceSq` | `UnilateralPortalState.java:347-411`, `:416-425` |
| C13 | `purgeFPError` thresholds: offset²<1e-4, quaternion-close, scaling within 1e-5 of 1 | `DeltaUnilateralPortalState.java:119-140` |

## CONFIRMED — flow / dependency / wiring (IP)

| # | Claim | Evidence |
|---|---|---|
| C14 | Server tick half: `partialTicks = 1` with the ServerLevel-ordering comment; reference states nulled when a side's list empties; auto-unpause when no drivers; client side only marks the pump | `PortalAnimation.java:241-277` (:245-252, :255-260, :266-269, :271-276) |
| C15 | `updateAnimationDriver` 9-step algorithm exactly as inventoried §3.2 (fixed-reference stateless evaluation, removeIf fold-into-reference, dimension-change rejection + server clear, combine + setPortalState, `rectifyClusterPortals(false)`, connected-portal tick states, delayed cluster re-sync only when a driver was removed) | `PortalAnimation.java:310-424` — every step at the cited line |
| C16 | Tick-state swap keyed on `portal.tickCount`; server "Conflicting animation" → clear-all on double write | `PortalAnimation.java:283-290`, `:292-303` |
| C17 | Client tick-half mixin: `Minecraft.tick` @ INVOKE `ClientLevel.tick(BooleanSupplier)` Shift.AFTER; exact sequence `ClientWorldLoader.tick → setPartialTick(0) → StableClientTimer.tick → StableClientTimer.update → ClientPortalAnimationManagement.tick → manageTeleportation(true)`; ordering comment block | `mixin/client/MixinMinecraft.java` ~:86-103 (comment), ~:118-142 (inject + body, "must be after remote world ticking" inline) |
| C18 | Frame-half mixin: `GameRenderer.render` HEAD, guards `minecraft.level == null` / `!renderWorldIn`, `deltaTracker.getGameTimeDeltaPartialTick(true)` + "Note do not use delta tick", sequence `updatePreRenderInfo → StableClientTimer.update → ClientPortalAnimationManagement.update ("must update before teleportation") → manageTeleportation(false)` | `mixin/client/render/MixinGameRenderer.java` ~:70-100 |
| C19 | Cleanup: `Minecraft.updateLevelInEngines` HEAD → `IPCGlobal.CLIENT_CLEANUP_EVENT`; both timer + management register on it; management also on `CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT` | `MixinMinecraft.java` ~:156-175; `StableClientTimer.java:110-112`; `ClientPortalAnimationManagement.java:29-32` |
| C20 | Client pump: two-pass tick (`true` then `false`); ticking evaluates at `stableTickTime + 1`; `partialTicks = isPaused ? 0 : stablePartialTicks`; `canRemoveAnimation = !isTicking`; frame `update()` expires default animations (removed / timeout→snap+finish event / dimension mismatch) then emits `clientAnimationUpdateSignal` | `ClientPortalAnimationManagement.java:61-69`, `:84-122`, `:124-157` (:130, :146, :148-152) |
| C21 | `markRequiresCustomAnimationUpdate` also removes any default animation; `addDefaultAnimation` is `System.nanoTime`-based with `secondToNano(durationTicks/20.0)` | `ClientPortalAnimationManagement.java:34-59` |
| C22 | StableClientTimer algorithm: `tick()` +1 or `tickrate/20` when >20 (TODO noted); `update()` skips on paused/null-level/!runsNormally; refuses when `stableTimeDelta < 0`; reset when target >100 ticks behind; slow ×0.9999 / snap-between / speed ×1.0001; 0 before first update | `StableClientTimer.java:142-160`, `:163-244` (:170-177, :191-195, :208-218, :224-228, :235-240), `:114-126` |
| C23 | Four-snapshot teleportation plumbing: `teleportationCounter++` once per `manageTeleportation` (tick or frame); `updateClientState` for every cluster member of every custom-animated portal; dynamic path gated on `clientLastFramePortalStateCounter == teleportationCounter - 1` AND all snapshots non-null, else static | `teleportation/ClientTeleportationManager.java:128`, `:144-151`, `:238-278`; `PortalAnimation.java:529-542` |
| C24 | Sync: `PortalSyncPacket(id, uuid, entityType, dimensionId, x, y, z, extraData)` is both spawn packet (`getAddEntityPacket` → `createSyncPacket`, contravariant double-cast over `ServerPlayNetworking.createS2CPacket`) and update packet (`reloadAndSyncToClient` → `McHelper.sendToTrackers`); vanilla `sendChanges` cancelled for portals with the 1/4096-precision rationale; client receipt: existing → `acceptDataSync`, else `create/setId/setUUID/syncPacketPositionCodec/moveTo/readPortalDataFromNbt/addEntity` + dest-dim preload; `acceptDataSync` starts the default animation old→new when `durationTicks > 0`; per-instance NBT inside portal save data; `animation.tick(this)` from `Portal.tick()` | `Portal.java:897-919`, `:513-529` ("This is the only place of syncing portal position"), `:540-546` (tick-delay resync), `:192`, `:358`, `:422`, `:955`, `:1737-1749`; `mixin/common/entity_sync/MixinServerEntity.java` ~:93-105; `ImmPtlNetworking.java:134-236`, registration `:238-267` |
| C25 | Pause/resume: pause records `pauseTime`+snapshots; resume shifts `timeOffset -= (gameTime - pauseTime)` and applies current-vs-paused delta to reference states; both re-sync cluster; `setBackToPausingState` restores paused geometry; **`applyEndingState` ignores pause in effective time** (quirk); `clearAnimationDrivers` server-only (`Validate.isTrue(!isClientSide)`) | `PortalAnimation.java:191-226` (:196-202, :204, :207-222, :225), `:228-235`, `:446-479` (:447), `:481-505` (:487) |
| C26 | Holder asymmetry (post-correction text): `getAnimationView` = flipped→reverse→parallel then **unconditional SAME fallback** (never tests self); `getAnimationHolder` = **self first**, then flipped→reverse→parallel, returns **null**; self-fallback only in `getPossibleAnimationHolder` | `Portal.java:1828-1861` (SAME fallback :1857-1860), `:1908-1934` (self :1909-1911, null :1933), `:1936-1942` |
| C27 | `inverseScale` maintained at exactly **four** `rectifyClusterPortals` sites: driving=false, flipped=false, reverse=true, parallel=true; reverse+parallel carry `setScaling(1.0 / portal.getScaling())` | `PortalExtension.java:327`, `:352`, `:396`, `:439`; `:382`, `:425`; `forClusterPortals`/`forConnectedPortals` `:479-496` |
| C28 | Registration: `RotationAnimation.init()` + `NormalAnimation.init()` in `IPModMain` with `OscillationAnimation.init()` commented out; client `StableClientTimer.init()` + `ClientPortalAnimationManagement.init()` in `IPModMainClient`; driver registry keyed by ResourceLocation, `fromTag` dispatches on NBT `"type"` with null-on-unknown + try-catch-to-null | `core/IPModMain.java` ~:116-119; `core/IPModMainClient.java` ~:113-115; `PortalAnimationDriver.java:16-43` |
| C29 | `OscillationAnimation` dead: `@Deprecated`, `getAnimationResult` throws `NotImplementedException` | `OscillationAnimation.java:12`, `:74` |
| C30 | `NormalAnimation` quirks: deserialize reads unused `"initialState"` (:116, never passed to the constructor); `toTag` never writes it (:144-154); `INFINITE_THRESHOLD = 100000` (:20); negative passedTicks → `(null, false)` (:185-188); double modulo round-splitting (:191); phase walk with absolute-keyframe interpolation (:196-213); zero-duration phases as instant keyframes (used by `createSizeAnimation`'s 0-tick first phase :293-300); `createOscillationAnimation` = 4 quarter-phases sine/sineFlipped, infinite loop (:318-352); `getEndingResult` = last phase delta (:218-225); `isBuilding` → `(null, false)` (:167-169) | `NormalAnimation.java` at cited lines |
| C31 | `TimingFunction`: 5 names + "don't rename"; `fromString` defaults to `sine`; formulas (sine ease-out, sineFlipped ease-in, circle ease-out, easeInOutCubic = 3p²−2p³ smoothstep); NBT-serialized via `name()`/`toString()` | `TimingFunction.java:6`, `:9-19`, `:21-41`; `NormalAnimation.java:45`, `DefaultPortalAnimation.java:78` |
| C32 | `AnimationView` list-swap on `isReverse`, driver-flip on `isFlipped`, write-through adds; `IntraClusterRelation` flag table SAME/FLIPPED/REVERSE/PARALLEL; `AnimationContext`/`AnimationResult` records as described | `AnimationView.java:15-49`; `IntraClusterRelation.java:3-15`; `AnimationContext.java:3-6`; `AnimationResult.java:5-8` |
| C33 | Consumers: `EndPortalEntity` view-box 0.5°/tick infinite `RotationAnimation` + oscillation; wand tools use `ANIMATION_TYPE_INFO` and connect `clientAnimationUpdateSignal`; `Helper.createConsumerEvent` = `EventFactory.createArrayBacked`; `Signal` = synchronized listener list; `DefaultPortalAnimation` defaults (sine, 10 ticks), NBT keys `curve/durationTicks/inverseScale/disableUntil`, `startClientDefaultAnimation` abort conditions + set-back-to-old-state | `EndPortalEntity.java` ~:172-191; `ClientPortalWandPortalDrag.java` ~:87-89, ~:174-177; `ClientPortalWandPortalCopy.java` ~:78-80; `q_misc_util/Helper.java:1435-1444`; `q_misc_util/my_util/Signal.java:11+`; `DefaultPortalAnimation.java:31-33`, `:35-43`, `:49-74`, `:76-83` |

## CONFIRMED — 26.2 API map (every citation re-opened)

| # | Claim | Evidence |
|---|---|---|
| C34 | `Minecraft.renderFrame(boolean advanceGameTime)` (`(Z)V`); level-touch test `isGameLoadFinished() && advanceGameTime && this.level != null`; `gameRenderer.update` / `extract` / `render` call sites | `net/minecraft/client/Minecraft.java:1229` (signature), `:1286`, `:1290`, `:1295`, `:1302` — all exact |
| C35 | `GameRenderer.update(DeltaTracker)` :372 runs `mainCamera.update`; `extract(DeltaTracker, boolean)` :379 with `readyForLevelRendering` :380-381, `extractCamera` :388, `minecraft.levelExtractor.extract` :389; `render(DeltaTracker, boolean)` survives at :396 | `net/minecraft/client/renderer/GameRenderer.java:372-396` — line-exact |
| C36 | `Camera.update` positions the camera (`alignWithEntity` at :103, then `prepareCullFrustum`/`setupPerspective`); `extractRenderState` only copies precomputed state (`cameraState.pos = this.position()`) | `net/minecraft/client/Camera.java:93-112`, `:118-135` |
| C37 | Panorama/screenshot path calls `gameRenderer.update/extract/renderLevel` directly, bypassing `renderFrame`; vanilla frame loop passes `false` to `getGameTimeDeltaPartialTick`; `getDeltaTracker()` public getter, field | `Minecraft.java:2779-2781`, `:1291`, `:2693-2695`, `:279` |
| C38 | `ResourceLocation` → `Identifier`: zero `class ResourceLocation` declarations in the decompile; `public final class Identifier` :18; factories `fromNamespaceAndPath` :40, `parse` :44, `withDefaultNamespace` :48; `ResourceKey.create(…, Identifier)` :26, class :13 | `net/minecraft/resources/Identifier.java`, `ResourceKey.java` |
| C39 | CompoundTag: all 21 cited member lines exact (writers unchanged :223-267; Optional readers :299-371 incl. `getList(String)` with **no** type-id param :359; `getXOr` defaults; single `contains(String)` :275, typed variant gone — grep count 1) | `net/minecraft/nbt/CompoundTag.java` |
| C40 | `updateLevelInEngines` overload split: 1-arg :2191 → 2-arg :2195 (both private); call sites `setLevel` :2061 (1-arg), saving-disconnect :2146 (**2-arg direct**), clear-level :2177 (1-arg); body repoints `levelExtractor/particleEngine/gameRenderer` :2202-2204 — the "1-arg inject misses :2146" correction is real | `Minecraft.java` at cited lines |
| C41 | Entity: `moveTo` gone (only `moveTowardsClosestSpace` remains); `snapTo` overloads :1778/:1782/:1790/:1794 (+ a BlockPos overload :1786 the doc omits — harmless); `setId` :389, `setUUID` :3240, `syncPacketPositionCodec` :355, `tickCount` :247, `isRemoved` :3903 (final), `getAddEntityPacket(ServerEntity)` :3672-3674 non-final, `recreateFromPacket` :3834-3844 | `net/minecraft/world/entity/Entity.java` |
| C42 | `EntityType.create(Level, EntitySpawnReason)` :298 → `create(Level, EntitySpawnRequest)` :302; `EntitySpawnReason` enum includes `LOAD`; `EntitySpawnRequest(reason, ignoreChecks)` :3; vanilla client spawn uses `EntitySpawnReason.LOAD` | `EntityType.java`, `EntitySpawnReason.java`, `EntitySpawnRequest.java`, `ClientPacketListener.java:601` |
| C43 | Server tick ordering intact: `this.tickTime()` at `ServerLevel.java:381`, gameTime+1 in `tickTime()` :477-480, entity ticking later via `entityTickList.forEach → guardEntityTick(this::tickNonPassenger)` :425-451 — the `partialTicks = 1` convention ports unchanged | `net/minecraft/server/level/ServerLevel.java` |
| C44 | Client tick ordering intact: `Minecraft.tick()` :1758; `tickEntities` :1797 and `tickBlockEntities` :1799 **before** `this.level.tick(() -> true)` :1819; `ClientLevel.tick(BooleanSupplier)` :299 calls `tickTime()` :303; client gameTime+1 :439-442; `setTimeFromServer` :445-446 | `Minecraft.java`, `net/minecraft/client/multiplayer/ClientLevel.java` |
| C45 | `DeltaTracker` interface :8, `getGameTimeDeltaTicks` :12, `getGameTimeDeltaPartialTick(boolean)` :14 | `net/minecraft/client/DeltaTracker.java` |
| C46 | `ServerEntity` class :50, `sendChanges()` :88 (mixin-cancel target survives), `getAddEntityPacket` consumed :278 | `net/minecraft/server/level/ServerEntity.java` |
| C47 | `ClientboundCustomPayloadPacket` = public record `(CustomPacketPayload payload) implements Packet<ClientCommonPacketListener>` (:14-15, directly constructible); `CustomPacketPayload` :13, `Type(Identifier id)` :56, `TypeAndCodec` :59; `StreamCodec<B,V>` :20; `ByteBufCodecs.registry(ResourceKey<…>)` :582; `FriendlyByteBuf` :71; `RegistryFriendlyByteBuf` :7 | cited files, line-exact |
| C48 | `TickRateManager.tickrate()` :20 / `runsNormally()` :32 (same package); `Minecraft.getInstance` :2517, `isPaused` :2600, `level` field :335; `ClientLevel.tickRateManager()` :748, `addEntity` :529, `getEntity` :551 | cited files |
| C49 | `handleSetTime` :1129-1136 → `level.setTimeFromServer(gameTime)`; `ClientboundSetTimePacket(long gameTime, Map<Holder<WorldClock>, ClockNetworkState> clockUpdates)` record :14 — StableClientTimer's premise intact, WorldClock additive | `ClientPacketListener.java`, `ClientboundSetTimePacket.java` |
| C50 | Text/math/misc: `Component.literal` :135; `MutableComponent.append` :48/:52, `withStyle` :67/:72; `ChatFormatting.GOLD('6')` :14; `Mth.lerp` :550/:558 (alpha first); `Vec3` member lines :96-:228 all exact; `ListTag` :16/:143/:145/:254/:258/:332/:340/:351 (plain `List<Tag>` backing, heterogeneous allowed); `LevelAccessor.getGameTime` :41-43; `LevelData.getGameTime` :21; `Level.isClientSide` :163, `dimension()` :960 | cited files |
| C51 | Verdict bookkeeping: GONE 0 / CHANGED 7 (#1,13,14,18,19,20,24) / SAME 20 / FABRIC-API 1 (#26) = all 28 inventory touchpoints accounted for, no silent skips | both docs, cross-checked |
| C52 | Scope completeness: `core/portal/animation/` = exactly 14 files, 2,639 LOC (`wc -l` match; per-file 574/85/7/9/457/217/365/196/81/42/89/208/255/54); all 14 inventoried in §2 | directory listing |

## Notes (not defects)

- N1. api-map #14's migration target (hook `Minecraft.renderFrame` before the `gameRenderer.update` call at
  `Minecraft.java:1290`, or `renderFrame` HEAD) is fully supported by the confirmed 26.2 facts C34–C37: a
  `render`-HEAD or even `extract`-HEAD inject would indeed run after camera positioning and (for `render`)
  after world-state extraction. The only unverified link is the 1.21.3-side characterization (P1), which
  does not change the 26.2 conclusion.
- N2. `Entity.snapTo` has a fifth overload `snapTo(BlockPos, float, float)` (:1786) not listed in api-map
  #19; irrelevant to the call sites being ported.
- N3. Inventory §2.12's "(b)" sentence is the R2 item; the rest of §2.12 (three-times model, convergence
  branch structure, tick behavior, cleanup wiring) is line-exact.
