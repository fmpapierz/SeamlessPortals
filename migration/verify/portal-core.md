# Adversarial verification: Portal entity core

Docs under review:
- `migration/inventory/portal-core.md` (inventory)
- `migration/api-map/portal-core.md` (api-map)

Ground truth: IP 1.21.3 source at `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/` and decompiled 26.2 at `C:/Users/warwa/ModDev/mc262-ref/`. Every verdict was re-derived by opening the cited files; nothing accepted on the docs' word. 40 claims checked; 37 CONFIRMED, 3 REFUTED (all minor precision errors in prose summaries — no geometry-sign, API-fate, or architecture errors).

**Severity: minor.**

Note: this replaces an earlier verify pass that concluded "clean"; that pass had already flagged R1 below as a "nit" but did not probe the two IP-behavior summaries refuted in R2/R3.

---

## 1. Scope completeness

| Claim | Verdict | Evidence |
|---|---|---|
| Slice = 21 files, 6,374 LOC (inventory:5) | **CONFIRMED** | Directory listing of `imm_ptl/core/portal/`: 14 root .java + 5 in `shape/` + 2 in `util/` = 21; `wc -l` total = 6374 exactly. Subdirs `animation/`, `custom_portal_gen/`, `global_portals/`, `nether_portal/` correctly excluded per slice definition; all 21 files have inventory sections (§2.1–§2.21). No file silently skipped. |
| Api-map verdict tally 3 GONE / 24 CHANGED / 5 FABRIC | **CONFIRMED** | Rows G1–G3, C1–C24, F1–F5 counted in the doc. |

## 2. Geometry / sign / transform claims (re-derived from IP source)

| Claim | Verdict | Evidence |
|---|---|---|
| `transformPoint(pos)` = `transformLocalVec(pos − origin) + dest`; `transformLocalVec` = nonScale then `.scale(scaling)`; `transformLocalVecNonScale` = `rotation == null ? v : rotation.rotate(v)` (inventory §2.1, §3.1) | **CONFIRMED** | `Portal.java:463-476`, `:1202-1208`. |
| Inverse chain: conjugated rotate; `inverseTransformLocalVec` divides by scaling; `inverseTransformPoint = origin + inv(p − dest)`; `transformPointRough = p + (dest − origin)` | **CONFIRMED** | `Portal.java:1194-1226`. |
| `getNormal()` = `axisW.cross(axisH).normalize()` cached; `getContentDirection()` = `transformLocalVecNonScale(getNormal().scale(-1))` cached | **CONFIRMED** | `Portal.java:482-498`. |
| `getFullSpaceTransformation()` = `translation(D)·scale(s)·rotate(R)·translate(−O)` | **CONFIRMED** | `Portal.java:1403-1412` — exact call order. |
| Mirror is **rotate before mirror**: `transformLocalVecNonScale(v) = getMirrored(super.transformLocalVecNonScale(v))`; inverse mirrors first then un-rotates; `mirroredVec(v,n) = v − 2(v·n)n`; matrix form inserts `.reflect(n,0)` between translation and scale; `setRotationTransformationForMirror = visualRotation · mirror⁻¹` | **CONFIRMED** | `Mirror.java:26-30` (comment ":26 rotate before mirror"), `:42-45` (`vec.add(normal.scale(vec.dot(normal) * -2))`), `:47-50`, `:52-63` (translation → reflect → scale → rotate → translate), `:74-82` (javadoc :65-73). |
| `PortalState.transformVec` = rotate → scale → mirror-if-isMirror; "not for mirror teleportation" notes | **CONFIRMED** | `PortalState.java:164-186`; notes `:160-163`, `:172-175`. |
| Rotation NBT quirk: written as four **doubles** `rotationA`=w, B=x, C=y, D=z; read back with `getFloat` and reassembled `new DQuaternion(B, C, D, A)` = (x,y,z,w) (inventory §2.1; api-map C4) | **CONFIRMED** | Write `Portal.java:384-389`; read `:290-297`. |
| `createReversePortal`: at destPos in dest world, destination = origin, extents × scaling, `axisW = −axisW`, `axisH = axisH`, shape `getReverse()`, if rotated `rotatePortalBody(rotation)` then rotation = conjugated, `scaling = 1/s` (inventory §2.5) | **CONFIRMED** | `PortalManipulation.java:88-117`; `rotatePortalBody` rotates both axes `:119-122`. Net reverse axes = `R·(−axisW)`, `R·axisH` — no contradiction with §3.5 (code sequence vs net effect). |
| `createFlippedPortal`: same world/pos, `axisW = −axisW`, shape `getFlipped()`, same rotation/scale | **CONFIRMED** | `PortalManipulation.java:132-156`. |
| Cluster rectify formulas — flipped: −axisW, same R/s, `getFlipped()`, inverseScale=false; reverse: `axisW = R·(−axisW)`, `axisH = R·axisH` (via `transformLocalVecNonScale`), `1/s`, conjugate, extents × s, `getReverse()`, `inverseScale = true` (exactly :396); parallel: `R·axisW`, `R·axisH`, `1/s`, conjugate, extents × s, `cloneIfNecessary()`, `inverseScale = true`; cross-dim moves via `ServerTeleportationManager.teleportRegularEntityTo` (inventory §2.4, §3.5) | **CONFIRMED** | `PortalExtension.java:325-447` (flipped :329-359, reverse :361-403, parallel :405-446; teleports :330-334, :369-373, :412-416). |
| `computeDeltaTransformation` = `otherSideOrientation · flipAxisW · thisSideOrientation⁻¹`; derivation `otherSideOrientation = rotation · thisSideOrientation · flipAxisW`; `flipAxisW` = 180° about +Y, self-inverse (inventory §2.5, §3.1) | **CONFIRMED** | `PortalManipulation.java:494-503` (comment :497-499); `flipAxisW` `:35-38` ("its inverse is itself"). |
| `adjustRotationToConnect`: `delta = b.hamiltonProduct(a.conjugated)`, flip 180° around B's axisH, A ← `flip·delta`, B ← conjugate | **CONFIRMED** | `PortalManipulation.java:416-430`. |
| Rectangular raytrace: only front→back (`localFrom.z > 0 && localTo.z < 0`); `t = −localFrom.z/Δz`; hit iff `|x| < w/2+leniency && |y| < h/2+leniency`; local surface normal (0,0,1) (inventory §2.16) | **CONFIRMED** | `RectangularPortalShape.java:82-109`. |
| `getFourVerticesLocal` vertex order "3 2 / 1 0" | **CONFIRMED** | `Portal.java:1130-1152` — 0=(+w/2,−h/2), 1=(−w/2,−h/2), 2=(+w/2,+h/2), 3=(−w/2,+h/2). |
| Cluster discovery: radius 0, position ε² < 0.01, dot thresholds ±0.9, excludes Mirror + self (inventory §2.5) | **CONFIRMED** | `PortalManipulation.java:438-478`. |

## 3. Sync / tick / lifecycle / dependency-flow claims (traced in IP source)

| Claim | Verdict | Evidence |
|---|---|---|
| `defineSynchedData(Builder)` deliberately empty — zero synched entries | **CONFIRMED** | `Portal.java:227-230` (`// nothing`). |
| Seven static events at cited lines; tick signals fired at `:944`/`:952`; dispose from `ip_onRemoved` `:455-458`; `CLIENT_PORTAL_SPAWN_EVENT` fired from packet handler at `ImmPtlNetworking.java:224`; channel `imm_ptl:spawn_portal` | **CONFIRMED** | `Portal.java:89-92, 208-219, 929-958`; `ImmPtlNetworking.java:134-147, 180-229`. |
| `getAddEntityPacket(ServerEntity)` returns `createSyncPacket()`; full-NBT `PortalSyncPacket(getId, getUUID, getType, dimInt, x, y, z, nbt)` via `ServerPlayNetworking.createS2CPacket`, contravariance cast comment at :911 | **CONFIRMED** | `Portal.java:897-919`. |
| `reloadAndSyncToClient()`: asserts non-global + server, `updateCache()`, `sendToTrackers`; javadoc "only place of syncing portal position" / 1-in-4096 rationale / `MixinServerEntity` reference | **CONFIRMED** | `Portal.java:510-529`. |
| Tick order: NULL_BOX error-log → last-tick state → server flush sync flag → client signal / server validity-check-remove else signal → `animation.tick` → `super.tick()` | **CONFIRMED** | `Portal.java:929-958`. |
| `makeBoundingBox()` NULL_BOX when `axisW == null`, else shape box expand 0.2; `shouldLimitBoundingBox = !getIsGlobal()`; `getBoundingBox()` lazy-cache override; `move()` no-op; `updateCache()` no-op until both axes non-null | **CONFIRMED** | `Portal.java:960-985`, `:572-578`, `:552-555`, NULL_BOX `:110-111`. |
| `isPortalValid()`: dims/extents/axes/dest checks, `lengthSqr() > 0.9`, `getY() > minY − 100`; server: dest level exists + world border; client: `ClientWorldLoader.getServerDimensions().contains` | **CONFIRMED** | `Portal.java:990-1030`. |
| `canTeleportEntity` chain: teleportable → not-a-Portal → specificPlayerId (NIL_UUID = non-players only) → `O_O.allowTeleportingEntity` → `imm_ptl_canTeleportThroughPortal`; `canChangeDimensions`-blocks-riding comment | **CONFIRMED** | `Portal.java:697-726`. |
| Mirror never teleports; tick re-forces `setTeleportable(false)/setInteractable(false)` every tick | **CONFIRMED** | `Mirror.java:19-24, 32-35`. |
| `MixinEntity_U`: `@Inject` into `Entity.setPosRaw(DDD)V` at `@At(INVOKE, EntityInLevelCallback.onMove()V)`; `@Inject` into `setRemoved` at RETURN | **CONFIRMED** | `mixin/common/mc_util/MixinEntity_U.java:12-33` — exact target strings and @At points. 26.2 targets survive: `this.levelCallback.onMove()` inside (now-final) `setPosRaw` at `mc262-ref/.../Entity.java:3786` call `:3800`; `setRemoved` final `:3912` (inject legal). |
| Registration: ids `immersive_portals:portal/end_portal/mirror/breakable_mirror` + block `immersive_portals:nether_portal_block`; wired `IPModEntry.onInitialize` → `IPModMain.registerEntityTypes((id,t) -> Registry.register(BuiltInRegistries.ENTITY_TYPE, id, t))`; shape `init()`s + `PortalExtension.init()` in `IPModMain.init()` | **CONFIRMED** | `IPModMain.java` (registerBlocks/registerEntityTypes ~:155-190; init ~:75-91); `platform_specific/IPModEntry.java:14-23`. |
| `createPortalEntityType` = FabricEntityTypeBuilder chain with `trackRangeBlocks(96)`, `trackedUpdateRate(20)`, `forceTrackedVelocityUpdates(true)`, dimensions fixed(0,0), "eye height should be 0" comment | **CONFIRMED** | `Portal.java:94-108`. |
| `PortalLike` "no longer needed, may be deleted" javadoc | **CONFIRMED** | `PortalLike.java:17-19`. |
| EndPortal `toObsidianPlatform` targets `END_SPAWN_POINT` + 1 up; `getBottomCenter()` IP call site at EndPortalEntity.java:105 | **CONFIRMED** | `EndPortalEntity.java:70-75` (`Vec3.atCenterOf(endSpawnPos).add(0, 1, 0)`), `:103-108`. |

## 4. 26.2 GONE verdicts (rename hunt performed)

| Claim | Verdict | Evidence (all `mc262-ref`) |
|---|---|---|
| G1: `Entity.getBoundingBox()` `public final` returning `this.bb`; `setBoundingBox` final; `setPos` does `setBoundingBox(makeBoundingBox())` — IP's lazy-cache override (`Portal.java:572-578`) impossible | **CONFIRMED** | `Entity.java:3426-3429`, `:3431-3433`, `:472-475`. |
| G2: zero UUID members on `CompoundTag`; codec route `store(String,Codec,T)` :490 / `read(String,Codec)` :518; `UUIDUtil.CODEC` = INT_STREAM (same int-array wire format as old putUUID) | **CONFIRMED** | grep "UUID" over `CompoundTag.java` → 0 member hits; `UUIDUtil.java` ~:23 `Codec.INT_STREAM.comapFlatMap(...)`. |
| G3: `net.minecraft.util.Tuple` absent entirely; `com.mojang.datafixers.util.Pair` on classpath | **CONFIRMED** | `find -name Tuple.java` → none; Pair used by vanilla. |
| C20 (GONE-shaped): `BlockPos.getBottomCenter()` gone; `Vec3.atBottomCenterOf(Vec3i)` :57 is the replacement | **CONFIRMED** | Rename hunt over `net/minecraft/core/` + `world/phys/`: only `AABB.getBottomCenter` (`AABB.java:449`) exists; nothing on BlockPos/Vec3i. `Vec3.java:57`. |
| C15 (GONE-shaped): no `Minecraft.getProfiler()` (only internal `metricsRecorder.getProfiler()` :1395); static `Profiler.get()` → `ProfilerFiller` | **CONFIRMED** | `Minecraft.java` grep; `util/profiling/Profiler.java` ~:47. |
| C8 (GONE-shaped): `Entity.getServer()` gone; `Level.getServer()` `@Nullable` at `Level.java:168` | **CONFIRMED** | grep `getServer()` in Entity.java → only `serverLevel.getServer()` :1305, `level.getServer()` :3625; `Level.java:167-170`. |

## 5. 26.2 CHANGED / SAME verdicts (signatures re-opened)

| Claim | Verdict | Evidence (all `mc262-ref`) |
|---|---|---|
| C1: `readAdditionalSaveData(ValueInput)` / `addAdditionalSaveData(ValueOutput)` abstracts; bridges `TagValueOutput.createWithContext(ProblemReporter, HolderLookup.Provider)`→`buildResult()` and `TagValueInput.create(ProblemReporter, HolderLookup.Provider, CompoundTag)` | **CONFIRMED** | `Entity.java:2204-2206`; `TagValueOutput.java:27`, `:152`; `TagValueInput.java:40`. |
| C2: no-arg `makeBoundingBox()` `protected final` delegating to override point `makeBoundingBox(Vec3)` | **CONFIRMED** | `Entity.java:477-483`. |
| C3: `ValueInput` has no `contains`; doubles only `getDoubleOr` (no Optional variant); `read(String,Codec)` :10, `listOrEmpty(String,Codec)` :25; `CompoundTag.contains(String)` one-arg survives, two-arg gone | **CONFIRMED** | `ValueInput.java:10, 25, 43` (grep `Optional<Double>` → none); `CompoundTag.java` grep `contains(` → only `:275`. |
| C4: `CompoundTag.getFloatOr` = `instanceof NumericTag ? floatValue() : default`; `TagValueInput.getFloatOr` same via `getNumericTag` — rotation quirk reproduces | **CONFIRMED** | `CompoundTag.java:319-321`; `TagValueInput.java:184-188`. |
| C5/C6: `getAllKeys` → `keySet()` :193-195; `merge` :435; `getList(String)` → `Optional<ListTag>` / `getListOrEmpty` :359-365, no element-type arg | **CONFIRMED** | `CompoundTag.java` at those lines. |
| C9: `EntityType.create(Level, EntitySpawnReason)` :298; no single-arg; overloads :302/:306/:314 exactly as listed | **CONFIRMED** | `EntityType.java:294-318`. |
| C10: `Builder.build(ResourceKey<EntityType<?>>)` is the ONLY build :590; `Builder.of` :479, `sized` :487 | **CONFIRMED** | grep over `EntityType.java`. |
| C11: `setRemoved` now `public final` :3912; `unsetRemoved` still protected :3926 | **CONFIRMED** (one wording error → R1) | `Entity.java:3911-3928`. |
| C12: `getViewVector(float)` now `public final` | **CONFIRMED** | `Entity.java:1929-1931`. |
| C13/C14: `ResourceLocation` → `Identifier` (`Identifier.java:18`, no ResourceLocation.java in tree); `net.minecraft.util.Util.NIL_UUID` :121 (`net/minecraft/Util.java` absent) | **CONFIRMED** | as cited. |
| C16: `minecraft.gui.hud.setOverlayMessage(Component, boolean)`; `Gui.hud` public final :72; `Hud.java:1225-1227`; no `setOverlayMessage` left on `Gui`; vanilla call `ClientPacketListener.java:1942` | **CONFIRMED** | as cited (also `:1168`). |
| C17: `Level.isClientSide` private final :127; accessor `isClientSide()` :163 | **CONFIRMED** | `Level.java:120-165`. |
| C18: `getApproximateNearest` at :303/:307/:322 | **CONFIRMED** | `Direction.java` (also self-use at :115). |
| C21: `record ChunkPos(int x, int z)` :19; `containing(BlockPos)` :45-47 | **CONFIRMED** | `ChunkPos.java`. |
| C22/C23: `noCollision()` :1065; `setId(ResourceKey<Block>)` :1263 | **CONFIRMED** | `BlockBehaviour.java`. |
| C24: `EnderDragonFight` :68; `getDragonFight()` :1654; `Arrow` in `projectile/arrow/` (AbstractArrow/Arrow/SpectralArrow/ThrownTrident); `AbstractMinecart` in `vehicle/minecart/` | **CONFIRMED** | as cited; no `EndDragonFight.java` anywhere. |
| F1: `clientTrackingRange(int clientChunkRange)` :565 — parameter in **chunks** (96 blocks ≙ 6); `trackDeltas()` :418-428 = vanilla-type exclusion list ⇒ true for all modded types; consumed `ChunkMap.java` ~:1150 | **CONFIRMED** | as cited — the hazard-#5 unit trap is real. |
| SAME-table sample (17 rows): `getAddEntityPacket(ServerEntity)` :3672-3674, `position()` :3685-3687, `level()` :3958, `getUUID()` :3246, `broadcastToPlayer` :3422-3424, setPosRaw→`levelCallback.onMove()` :3800, `ServerEntity` class :50 / `positionCodec` :61 / `sendChanges()` :88, `EntityDataSerializers.COMPONENT` :58 / `BLOCK_POS` :94, `SynchedEntityData.defineId` :29, `END_SPAWN_POINT` :186, `getDragonFight` :1654, `EndPlatformFeature.createEndPlatform(ServerLevelAccessor, BlockPos, boolean)` :21, `ByteBufCodecs.registry` :582, `Registry.register` :111/:115, `BuiltInRegistries.ENTITY_TYPE` :186 | **CONFIRMED** | All opened at the cited lines and matched. |

## 6. Internal-consistency scan

- Inventory §2.5 vs §3.5 reverse-portal axes phrasings are the same fact (code sequence vs net effect) — consistent with `PortalManipulation.java:101-107`. ✓
- Api-map G1↔C2↔hazard-#1 are mutually consistent and match 26.2 source. ✓
- Inventory "Notes for the 26.2 mapping stage" items are each resolved consistently by the api-map (C15, C1/C3/C4, §3.9 block row, F1). ✓

## 7. REFUTED claims (all minor)

**R1 — api-map row C11 (`migration/api-map/portal-core.md`):**
Claim: 26.2 `setRemoved` "body still ends by calling `this.levelCallback.onRemove(reason)` (`:3922`)".
Reality: `onRemove` is the **penultimate** statement; the body ends with `this.onRemoval(reason)` at `mc262-ref/net/minecraft/world/entity/Entity.java:3923` (`:3911-3924`). The load-bearing conclusion (an `@At("RETURN")` inject still works) is unaffected, but a porter converting the inject to `@At(INVOKE, target=onRemove, shift=AFTER)` "because it's the last call" would silently run before `onRemoval(reason)`.
Correction: tail order is `levelCallback.onRemove(reason)` (:3922) **then** `onRemoval(reason)` (:3923); keep the RETURN inject.

**R2 — inventory §2.10 and §3.9 (`migration/inventory/portal-core.md`):**
Claim: blocking same-frame fetch is used "when there is no last-frame query, when frequently mispredicted (>5 total mispredicts or two mispredicts within 30s), or while `QueryManager.queryStallCounter <= 3`" (§2.10; §3.9 repeats it).
Reality (`PortalRenderInfo.java:212-251`): the frequently-mispredicted / stall-counter conditions (`noPredict`, `:228-230`) trigger a blocking fetch **only when the last-frame query result was invisible** (`if (!lastFrameVisible && noPredict)`, `:232-240`). If the last frame reported visible, the prediction is used regardless of `noPredict`. Only the no-last-frame-query branch (`:246-251`) blocks unconditionally. The doc over-states when the code stalls.
Correction: blocking fetch ⇔ (no last-frame query) OR (last-frame result invisible AND (frequently-mispredicted OR `queryStallCounter <= 3`)). The ">5 total / two within 30s" mispredict thresholds themselves are correct (`:188-196`).

**R3 — inventory §2.1 and §3.1 velocity summary (`migration/inventory/portal-core.md`):**
Claim: `transformVelocityRelativeToPortal` — "transform through `transformLocalVec`, clamp speed to 15" (§2.1); "a 15 blocks/tick cap" (§3.1).
Reality (`Portal.java:1067-1085`): the 15-check is on the **pre-transform** vector (`originalVelocityRelativeToPortal.length() > maxVelocity`, `:1074`), and when it trips, the **post-transform** result is normalized and set to exactly magnitude 15 (`result.normalize().scale(maxVelocity)`, `:1076`). Under scaling ≠ 1 this is not a clamp of the output: original speed 20 through a 0.1× portal yields output speed 15, not 2 — and a fast input through an 8× portal yields 15, not 120. A porter implementing "clamp result to 15" from the doc would deviate from IP.
Correction: `if (original.length() > 15) result = transformLocalVec(original).normalize().scale(15)` — condition on input speed, magnitude override on output. (Minecart ×2 boost when `result.lengthSqr() < 0.5` is correctly documented, `:1080-1082`.)

## 8. Verdict

No geometry sign errors, no quaternion-order errors, no wrong API fates, no missed renames behind GONE verdicts, no skipped scope files, no broken mixin targets. The three refuted items are precision errors in prose summaries whose underlying line citations are accurate; each carries a concrete correction above. **Severity: minor.**
