# IP Subsystem Inventory: Portal Animation

Slice owner: `qouteall/imm_ptl/core/portal/animation/` (14 files, 2,639 LOC total).
IP source root (ground truth, MC 1.21.3 Mojang mappings): `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`.
All paths below are relative to that root unless absolute. All claims carry file:line citations from a full read of every file in the package plus its wiring call sites.

---

## 1. Overview

The animation subsystem makes a `Portal` entity's geometric state (position, orientation, size, on both sides) change smoothly over time. It has **two independent layers**:

1. **The default animation** — client-only cosmetic smoothing. Whenever the server abruptly changes a portal and re-syncs it, the client interpolates from the old `PortalState` to the new one over N ticks of wall-clock time (`DefaultPortalAnimation` + `ClientPortalAnimationManagement.RunningDefaultAnimation`). The server never sees intermediate states ("The default animation is client-only. On the server side it changes abruptly." — `PortalAnimation.java:22-26`). It cannot do relative teleportation.
2. **The real animation (driver) layer** — deterministic, runs on **both** sides, and supports *dynamic teleportation* (teleporting through a moving portal with the portal's motion imparted correctly). A portal holds lists of `PortalAnimationDriver`s per side; each driver is a pure function `gameTime → DeltaUnilateralPortalState` applied on top of a saved *reference state*. Because drivers are serialized to NBT and synced once, the client re-evaluates the identical function locally each tick **and** each frame — there is no per-frame animation network traffic. `StableClientTimer` supplies a monotonic client clock so the client evaluation time never jumps or flows backward even when the server-synced game time does.

Architecturally the slice sits between the portal core (`Portal`, `PortalState`, `PortalExtension` cluster logic) and the teleportation subsystem: `ClientTeleportationManager` consumes the four animation-produced state snapshots (last/this tick, last/current frame) to run `TeleportationUtil.checkDynamicTeleportation` (`teleportation/ClientTeleportationManager.java:241-261`). The client work is deliberately **split across the tick and the frame** (see §3.4) — this is one of the "IP splits work across tick + render" patterns the briefing warns about.

---

## 2. Class-by-class inventory

### 2.1 `PortalAnimation` (574 LOC, common; two `@Environment(CLIENT)` methods)
`core/portal/animation/PortalAnimation.java`

**Responsibility:** the per-portal animation state machine. One instance lives as a `final` field on every portal (`core/portal/Portal.java:192`). Owns the default-animation config, the driver lists for both sides, pause bookkeeping, the reference states drivers are applied against, and the four tick-/frame-relative state snapshots teleportation consumes.

**State/fields (all matter):**
- `defaultAnimation: DefaultPortalAnimation` (never null, `:27-28`).
- `thisSideAnimations`, `otherSideAnimations: List<PortalAnimationDriver>` (`:34-37`).
- `pauseTime`, `timeOffset: long` (`:39-40`) — pause bookkeeping; effective time = `(isPaused() ? pauseTime : gameTime) + timeOffset` (`:237-239`).
- `thisSideReferenceState`, `otherSideReferenceState: @Nullable UnilateralPortalState` (`:42-45`) — the base geometry drivers' deltas apply to.
- `pausedThisSideState`, `pausedOtherSideState` (private, `:47-50`) — snapshot taken at pause.
- `lastTickAnimatedState`, `thisTickAnimatedState: @Nullable PortalState` + `updateCounter: long` (`:52-56`) — tick-granularity snapshots for dynamic teleportation.
- `clientLastFramePortalState` / `clientCurrentFramePortalState` + their counters (`:58-64`) — frame-granularity snapshots, keyed by `ClientTeleportationManager.teleportationCounter`.

**Key public API:**
- `readFromTag(CompoundTag)` / `writeToTag(CompoundTag)` (`:66-134`, `:136-173`) — full NBT (de)serialization; called by `Portal.readAdditionalSaveData`/`addAdditionalSaveData` (`Portal.java:358`, `:422`). Legacy key `"animation"` accepted for `defaultAnimation` (`:67-69`).
- `isRoughlyRunningAnimation()` (`:175-177`), `hasRunningAnimationDriver()` (`:179-181`), `hasAnimationDriver()` (`:183-185`), `isPaused()` (`:187-189`).
- `setPaused(Portal, boolean)` (`:191-226`) — see §3.7.
- `setBackToPausingState(Portal)` (`:228-235`).
- `getEffectiveTime(long gameTime)` (`:237-239`).
- `tick(Portal)` (`:241-277`) — the per-tick entry, called from `Portal.tick()` (`Portal.java:955`). See §3.3.
- `swapTickRelativeStateIfNeeded(Portal)` (`:283-290`) — rotates `thisTickAnimatedState → lastTickAnimatedState` keyed on `portal.tickCount` (vanilla `Entity.tickCount`), making the logic independent of entity ticking order (comment `:279-282`).
- `updateAnimationDriver(Portal, PortalAnimation, long gameTime, float partialTicks, boolean isTicking, boolean canRemoveAnimation)` (`:310-424`) — the core evaluation algorithm, §3.2.
- `initializeReferenceStates(PortalState)` (`:426-433`), `resetReferenceState(...)` (`:435-444`).
- `clearAnimationDrivers(Portal, boolean, boolean)` (`:446-479`) — **server-only** (`Validate.isTrue(!portal.level().isClientSide())`, `:447`); jumps the portal to the drivers' ending state, clears lists/references, rectifies the cluster with sync.
- `getAnimationEndingState(Portal)` (`:507-527`) — reference states + every driver's `getEndingResult` folded in; used when appending a new animation to a running one.
- `updateClientState(Portal, long currentTeleportationCounter)` (`@Environment(CLIENT)`, `:529-542`) — captures the frame-granularity snapshots.
- `getInfo(Portal, boolean reverse)` (`:544-573`) — chat `Component` dump for the `/portal animation view` command.

**Dependencies (IP):** `Portal`, `PortalExtension` (`forClusterPortals`/`forConnectedPortals`), `PortalState`, `ClientPortalAnimationManagement` (client mark), `q_misc_util.Helper` (list⇄ListTag).
**Vanilla touched:** `CompoundTag`/`ListTag`, `Level.getGameTime()`/`isClientSide()`, `Entity.tickCount`, `Component`/`MutableComponent`/`ChatFormatting`.

### 2.2 `PortalAnimationDriver` (85 LOC, common, interface)
`core/portal/animation/PortalAnimationDriver.java`

**Responsibility:** the driver abstraction + a static string-keyed deserializer registry.
- `static Map<ResourceLocation, Function<CompoundTag, PortalAnimationDriver>> deserializerRegistry` (`:16-17`); `registerDeserializer(ResourceLocation, Function...)` (`:19-24`); `fromTag(CompoundTag)` dispatches on NBT string field `"type"`, logs + returns null for unknown types, and try-catches deserializer exceptions to null (`:27-43`).
- `CompoundTag toTag()` (`:45`).
- `@NotNull AnimationResult getAnimationResult(long tickTime, float partialTicks, AnimationContext)` (`:57-62`). Contract documented: server calls it during ticking; client during ticking **and** before rendering; **"The real time is tickTime - 1 + partialTicks"** (`:47-56`) — this off-by-one convention is load-bearing (drivers compute `passedTicks = (tickTime - 1 - startGameTime) + partialTicks`, e.g. `NormalAnimation.java:176`, `RotationAnimation.java:81`).
- `@Nullable DeltaUnilateralPortalState getEndingResult(long tickTime, AnimationContext)` (`:72-73`) — null means infinite (keep current state when stopping).
- `default PortalAnimationDriver getFlippedVersion()` returns `this` (`:78-80`); `default Component getInfo()` (`:82-84`).

### 2.3 `AnimationContext` (7 LOC, common) / `AnimationResult` (9 LOC, common)
- `record AnimationContext(boolean isClientSide, boolean isTicking)` (`AnimationContext.java:3-6`).
- `record AnimationResult(@Nullable DeltaUnilateralPortalState delta, boolean isFinished)` (`AnimationResult.java:5-8`).

### 2.4 `UnilateralPortalState` (457 LOC, common)
`core/portal/animation/UnilateralPortalState.java`

**Responsibility:** one-sided portal state — `PortalState` covers both ends; this is either the this-side or other-side half (javadoc `:23-27`).

**Record components:** `ResourceKey<Level> dimension`, `Vec3 position`, `DQuaternion orientation` (rotates local→world; **in local orientation the flat portal faces +Z**, comment `:32-33`), `double width/height/thickness`, plus precomputed JOML `Matrix3dc orientationMatrix`/`orientationMatrixReverse` built in the canonical constructor from `orientation.toMcQuaternion()` and its conjugate (`:54-65`).

**Key public API:**
- `extractThisSide(PortalState)` (`:67-76`); `extractOtherSide(PortalState)` (`:78-92`) — other-side orientation = `rotation ⨯ orientation ⨯ flipAxisW` (identity instead of flipAxisW for mirrors), sizes multiplied by `scaling`.
- `static PortalState combine(thisSide, otherSide)` (`:94-123`) — **"NOTE does not work for mirror"** (`:94`); `scale = otherSide.width / thisSide.width` and "ignore other side's aspect ratio changing" (`:105-106`); recovers the delta rotation via `PortalManipulation.computeDeltaTransformation` (`:101-103`).
- `interpolate(from, to, progress)` (`:125-138`).
- `toTag()`/`fromTag()` (`:140-161`) — dimension stored as string id.
- `subtract(other) → DeltaUnilateralPortalState` (`:163-167`); `apply(delta) → UnilateralPortalState` (`:169-171`).
- Axis/transform helpers reading matrix rows: `getAxisW/getAxisH/getNormal` (`:173-198`), `pointOnPlane` (flat-shape only, `:200-203`), `transformLocalToGlobal`/`GlobalToLocal` and vector variants (`:205-237`).
- `Builder` (mutable mirror; `:243-342`): `from`, `offset`, `rotate` (`orientation = rotation ⨯ orientation`, `:308-311`), `scaleWidth/Height/Thickness`, and `apply(DeltaUnilateralPortalState)` which applies **all three** sizeScaling components incl. thickness (`:328-341`). `build()` validates `width > 0 && height > 0` (`:251-260`).
- `enum RectInvariant` — the 8 dihedral symmetries of a rectangle (rotate 90/180/270, flip-X combinations) as orientation hamilton-products with width/height swap tracking (`:347-411`); `turnToClosestTo(DQuaternion)` picks the invariant minimizing `DQuaternion.distanceSq` to a target orientation (`:416-425`).
- `ANIMATION_TYPE_INFO: Animated.TypeInfo<UnilateralPortalState>` (`:427-456`) — generic-animation interpolation (snap start to the closest rect-invariant of the end, then lerp) used by the peripheral wand drag/copy (`peripheral/wand/ClientPortalWandPortalDrag.java:88`, `ClientPortalWandPortalCopy.java:79`).

**Vanilla touched:** `ResourceKey<Level>`, `Vec3`, `Mth.lerp`, `CompoundTag`, JOML `Matrix3d/Matrix3dc/Vector3d`, DFU `Pair`.

### 2.5 `DeltaUnilateralPortalState` (217 LOC, common)
`core/portal/animation/DeltaUnilateralPortalState.java`

**Responsibility:** a sparse (nullable-component) transform delta: `@Nullable Vec3 offset`, `@Nullable DQuaternion rotation`, `@Nullable Vec3 sizeScaling` (`:16-20`); `identity` constant has all null (`:21-22`).

**Key public API:**
- `getInverse()` (`:24-33`) — negated offset, conjugated rotation, reciprocal scaling with a divide-by-zero guard on z (`:30`).
- `combine(then)` (`:35-49`) — componentwise via `Helper.combineNullable`; rotations via `DQuaternion::hamiltonProduct`.
- `fromTag`/`toTag` (`:51-77`) — sizeScaling stored as three doubles `sizeScalingX/Y/Z`, z defaulting to 1 when absent (`:58`).
- `getPartial(double progress)` (`:79-89`) — offset scaled, rotation slerped from identity, scaling lerped from 1.
- `static interpolate(a, b, progress) = a.getPartial(1-p).combine(b.getPartial(p))` (`:91-96`).
- `getFlipped()` (`:98-104`) — `rotation ⨯ PortalManipulation.flipAxisW`; offset and scaling unchanged.
- `apply(UnilateralPortalState.Builder)` (`:106-117`) — **applies only x/y scaling (width/height), NOT thickness**; contrast with `UnilateralPortalState.Builder.apply(delta)` (`UnilateralPortalState.java:328-341`) which also scales thickness. The driver-update path uses the Builder-side method (`PortalAnimation.java:350`, `:372`), so thickness IS animated there.
- `purgeFPError()` (`:119-140`) — nulls near-identity components (offset²<1e-4, quaternion close to identity, scaling within 1e-5 of 1).
- `isIdentity()` (`:142-144`).
- `static fromDiff(before, after)` (`:162-178`) — validates same dimension; rotation = `after ⨯ before⁻¹`; **sizeScaling z is the width ratio, not a thickness ratio** (`:175`); result is `purgeFPError()`d.
- `Builder` (`:180-216`) with `scaleSize(double|Vec2d|Vec3)` overloads.

### 2.6 `NormalAnimation` (365 LOC, common) — driver `imm_ptl:normal`
`core/portal/animation/NormalAnimation.java`

**Responsibility:** the general keyframe driver: a list of `Phase(durationTicks, delta, timingFunction)` records (`:29-83`) looped `loopCount` times from `startingGameTime`. `loopCount >= INFINITE_THRESHOLD (100000)` means infinite (`:20`, `:156-162`).

**Fields:** `phases`, `startingGameTime`, `loopCount`, `isBuilding` (a no-op placeholder used only while `/portal animation build` is assembling phases — returns `AnimationResult(null, false)`; `:89-93`, `:167-169`), precomputed `ticksPerRound` = Σ durations (`:108-113`).

**Algorithm (`getAnimationResult`, `:164-216`):** `passedTicks = (tickTime - 1 - startingGameTime) + partialTicks` (`:176`); clamps at total duration and flags `ends`; negative means scheduled in the future → null delta, not finished (`:185-188`); `passedTicksInThisRound` via double modulo (`:191`); walks phases accumulating `traversedTicks`, and inside the active phase returns `DeltaUnilateralPortalState.interpolate(lastDelta, phase.delta, timingFunction.mapProgress(phaseProgress))` (`:196-213`) — i.e. each phase's `delta` is an absolute keyframe target relative to the reference state, and zero-duration phases act as instant initial offsets (used by `createSizeAnimation`'s first phase, `:293-300`).
- `getEndingResult` = last phase's delta (`:218-225`).
- `getFlippedVersion()` flips every phase delta (`:227-235`; `Phase.getFlippedVersion` `:49-51`).
- Factories: `createSizeAnimation` (`:288-316`), `createOscillationAnimation` (4 sine quarter-phases, infinite loop; `:318-352`) — the latter used by `EndPortalEntity` for the End view-box (`core/portal/EndPortalEntity.java:184-190`).
- Registered as `imm_ptl:normal` via `init()` (`:22-27`).
- **Quirk:** `deserialize` reads an `"initialState"` compound (`:116`) that is never stored, never used, and never written by `toTag` (`:144-154`) — legacy leftover; port as-is.

### 2.7 `RotationAnimation` (196 LOC, common) — driver `imm_ptl:rotation`
`core/portal/animation/RotationAnimation.java`

**Fields:** `initialOffset`, `rotationAxis`, `degreesPerTick`, `startGameTime`, `endGameTime`, `@Nullable timingFunction` (`:21-27`).

**Algorithm (`getAnimationResult`, `:77-107`):** same `tickTime - 1 + partialTicks` convention (`:81`); clamps at `endGameTime`; optional timing function remaps progress across the whole duration (`:90-92`); delta = rotation quaternion about `rotationAxis` by `degreesPerTick * passedTicks` plus the orbit offset `rotate(initialOffset) - initialOffset` (`:94-106`) — so `initialOffset` makes the portal orbit a center at `position - initialOffset`... precisely: the pivot is displaced by `-initialOffset` from the reference position.
- `getEndingResult`: null when `endGameTime == Long.MAX_VALUE` (infinite → keep current state on stop), else the result at `endGameTime` (`:109-119`).
- `getFlippedVersion()` returns an identical copy — rotation deltas are flip-invariant (`:121-131`).
- Registered as `imm_ptl:rotation` (`:14-19`). Consumed by `EndPortalEntity` (0.5°/tick infinite spin, `EndPortalEntity.java:174-182`) and `/portal animation rotate-along` (`core/commands/PortalAnimationCommand.java:633-638`).

### 2.8 `OscillationAnimation` (81 LOC, common) — **dead code**
`core/portal/animation/OscillationAnimation.java`

`@Deprecated` (`:12`); its `init()` is never called (`core/IPModMain.java:119` is commented out) and `getAnimationResult` throws `NotImplementedException` (`:74`). Superseded by `NormalAnimation.createOscillationAnimation`. **Do not port** beyond noting its registry id `imm_ptl:oscillation` is reserved.

### 2.9 `TimingFunction` (42 LOC, common)
`core/portal/animation/TimingFunction.java`

Enum `linear, sine, sineFlipped, circle, easeInOutCubic` — **"don't rename"** (`:6`), the names are NBT-serialized via `name()`/`toString()` (`NormalAnimation.java:45`, `DefaultPortalAnimation.java:78`). `fromString` defaults unknown strings to `sine` (`:9-19`). `mapProgress` formulas at `:21-41` (sine = ease-out, sineFlipped = ease-in, circle = ease-out, easeInOutCubic = smoothstep).

### 2.10 `DefaultPortalAnimation` (89 LOC, common data + `@Environment(CLIENT)` trigger)
`core/portal/animation/DefaultPortalAnimation.java`

**Responsibility:** per-portal *configuration* of the client-side smoothing: `timingFunction`, `durationTicks` (default: `sine`, 10 ticks, `:31-33`), `inverseScale`, `disableUntil` (game-time gate, `:16`). NBT keys `curve/durationTicks/inverseScale/disableUntil` (`:35-43`, `:76-83`).

- `startClientDefaultAnimation(Portal, PortalState animationStartState)` (`@Environment(CLIENT)`, `:49-74`): aborts if the new state is null, if either dimension changed, or if `disableUntil >= gameTime`; registers a `RunningDefaultAnimation` from old→new state in `ClientPortalAnimationManagement`, then **sets the portal back to the old state** so multiple syncs in one tick start from a consistent base (`:72-73`).
- `inverseScale` is maintained by cluster rectification at **four** sites in `rectifyClusterPortals`: `false` for the driving portal (`core/portal/PortalExtension.java:327`), `false` for the flipped portal (`:352`), `true` for the reverse portal (`:396`), and `true` for the parallel portal (`:439`, inside the same method's `parallelPortal != null` branch, `:405-446`). Reverse and parallel portals both sit on the far side with scaling `1.0 / portal.getScaling()` (`:382`, `:425`), so scale interpolation happens in reciprocal space there; a port that omits the parallel site leaves parallel portals' default-animation scale interpolation non-reciprocal.
- Sole trigger: `Portal.acceptDataSync` (`Portal.java:1744-1746`).

### 2.11 `ClientPortalAnimationManagement` (208 LOC, client-only)
`core/portal/animation/ClientPortalAnimationManagement.java`

**Responsibility:** the client-side central pump for both animation layers. `@Environment(EnvType.CLIENT)` (`:19`).

**State:** `Map<Portal, RunningDefaultAnimation> defaultAnimatedPortals`, `HashSet<Portal> customAnimatedPortals` (`:24-25`); Fabric `Event<Consumer<Portal>> CLIENT_PORTAL_DEFAULT_ANIMATION_FINISH` (`:21-22`, created via `Helper.createConsumerEvent` = `EventFactory.createArrayBacked`, `q_misc_util/Helper.java:1435-1444`); mod `Signal clientAnimationUpdateSignal` emitted after every frame update (`:27`, `:121`; consumed by the wand drag tool, `peripheral/wand/ClientPortalWandPortalDrag.java:175`).

**Key public API:**
- `init()` — registers `cleanup` on `IPCGlobal.CLIENT_CLEANUP_EVENT` and on `ClientWorldLoader.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT` (`:29-32`).
- `addDefaultAnimation(portal, fromState, toState, animation)` (`:34-54`) — wall-clock based: `System.nanoTime()` now → `now + secondToNano(durationTicks/20.0)`.
- `markRequiresCustomAnimationUpdate(Portal)` (`:56-59`) — adds to `customAnimatedPortals` **and removes any default animation** (a real animation overrides the cosmetic one).
- `tick()` (`:61-69`) — the tick half: `updateCustomAnimations(true)` then `updateCustomAnimations(false)` (§3.4).
- `update()` (`:84-122`) — the frame half: advances/expires default animations, then `updateCustomAnimations(false)`, then emits the signal. A default animation is dropped when the portal is removed, when time is up (snaps to `toState`, fires the finish event), or when either dimension no longer matches (`:87-117`).
- `updateCustomAnimations(boolean isTicking)` (private, `:124-157`) — for each marked portal: uses `StableClientTimer` time; **if ticking uses `stableTickTime + 1`** ("update the portal as if it's one tick later", `:128-130`); passes `partialTicks = isPaused ? 0 : stablePartialTicks` and `canRemoveAnimation = !isTicking` ("to make the animation stop smoothly, don't remove the animation during ticking", `:148-152`); removes entries whose portal is removed or has no drivers left.
- `foreachCustomAnimatedPortals(Consumer<Portal>)` (`:164-166`) — consumed by `ClientTeleportationManager.manageTeleportation` (`ClientTeleportationManager.java:144-151`).

**Inner class `RunningDefaultAnimation`** (`:168-207`): from/to `PortalState`, nano timestamps, timing function, `inverseScale`; `getCurrentState` maps nano progress through the timing function into `PortalState.interpolate(from, to, progress, inverseScale)` (`:189-206`), clamping negative progress with an error log.

**Vanilla touched:** none directly beyond `Entity.isRemoved()` (`:91`, `:134`) — deliberately; time comes from `System.nanoTime` and `StableClientTimer`.

### 2.12 `StableClientTimer` (255 LOC, client-only)
`core/portal/animation/StableClientTimer.java`

**Responsibility:** produces a client animation clock (`stableTickTime + stablePartialTicks`) that (a) never flows backward or stops, and (b) converges to **exactly the server-synced time, with no offset**: `targetTime = serverSyncedTime = new Time(worldGameTime, partialTicks)` (`:185-186`), even when `level.getGameTime()` jumps due to time-sync packets (javadoc `:13-32`, referencing `ClientPacketListener.handleSetTime`). The javadoc prose says the stable time is "close to the server synced time plus 1 tick" (`:25`), but the timer itself applies no +1 — the +1 happens only at evaluation time in the consumer (`usedTickTime = stableTickTime + 1` when ticking, `ClientPortalAnimationManagement.java:130`; see §2.11/§3.4). Do not add a +1 inside the timer when porting.

**Model:** three times — server-synced (`level.gameTime + partialTicks`), client reference (`referenceTickTime`, incremented once per client tick), stable = reference + offset (`:20-27`, `:96-105`). Inner immutable `Time(long tickTime, float partialTicks)` value with `normalized()/added()/subtracted()/subtractedLen()` arithmetic (`:36-90`).

**Key public API:** `init()` (cleanup on `IPCGlobal.CLIENT_CLEANUP_EVENT`, `:110-112`); `getStableTickTime()` / `getStablePartialTicks()` (0 before first update; `:114-126`); `tick()` (`:142-160`) — advances `referenceTickTime` by 1, or by `tickrate/20` when `TickRateManager.tickrate() > 20` (with a TODO noting the client doesn't actually accelerate; `:150-159`); `update(long worldGameTime, float partialTicks)` (`:163-244`) — called "after every tick and before every frame".

**Convergence algorithm (`update`):** skipped while `Minecraft.isPaused()`, `mc.level == null`, or `!tickRateManager().runsNormally()` (`:169-178`). Computes `projectedStableTime = referenceTime + offsetTime` and compares to target (server-synced): if stable is ahead and the target is even behind the *last* stable time — reset if the gap exceeds 100 ticks, else decay `timeFlowScale *= 0.9999` (slow down, never reverse; `:197-222`); if the target lies between last stable and projected — snap exactly to target (`:223-229`); if stable is behind — `timeFlowScale *= 1.0001` (speed up; `:231-241`). Also refuses to advance if the reference time went abnormal after unpausing (`stableTimeDelta < 0`, `:191-195`). `getDebugString()` for the debug HUD (`:246-254`).

**Vanilla touched:** `Minecraft.getInstance()/isPaused()/level`, `ClientLevel.tickRateManager()`, `TickRateManager.tickrate()/runsNormally()` — the tick-rate API is 1.20.3+; verify against 26.2.

### 2.13 `AnimationView` (54 LOC, common)
`core/portal/animation/AnimationView.java`

`record AnimationView(Portal viewedPortal, Portal animationHolder, IntraClusterRelation relationToHolder)` — the indirection that lets any portal of a cluster expose "its" animations while exactly one cluster member (the holder) physically stores them. `getThisSideAnimations()/getOtherSideAnimations()` swap lists when `relationToHolder.isReverse` (`:15-32`); `convertToHolderAnimation` applies `driver.getFlippedVersion()` when `isFlipped` (`:34-41`); `addThisSideAnimation/addOtherSideAnimation` write through both conversions (`:43-49`). `IntraClusterRelation` is `SAME/FLIPPED/REVERSE/PARALLEL` with `isFlipped`/`isReverse` flags (`core/portal/IntraClusterRelation.java:3-15`). Holder selection uses **two different orders — port each method verbatim, they are not interchangeable**: `Portal.getAnimationView()` checks flipped → reverse → parallel for drivers and falls through to self (`SAME`) **unconditionally** — it never tests self's drivers (`Portal.java:1828-1861`; unconditional SAME fallback `:1857-1860`). `Portal.getAnimationHolder()` (`Portal.java:1908-1934`) checks **self first** (`if (animation.hasAnimationDriver()) return this;`, `:1909-1911`), then flipped → reverse → parallel, and returns **null** (not self) when no cluster member has drivers (`:1933`); the self-fallback lives in the wrapper `Portal.getPossibleAnimationHolder()` (`:1936-1942`), which returns `this` only when `getAnimationHolder()` is null.

---

## 3. Mechanisms

### 3.1 The state algebra
`PortalState` (both ends: fromPos/toPos, scaling, rotation, orientation, width/height/thickness, isMirror — `core/portal/PortalState.java:18-29`) decomposes into two `UnilateralPortalState`s (`extractThisSide`/`extractOtherSide`, `UnilateralPortalState.java:67-92`) and recombines via `combine` (`:94-123`, not mirror-safe). Drivers never touch `PortalState` directly: they emit `DeltaUnilateralPortalState`s that are applied per side. The whole animated result is written back through `Portal.setPortalState(PortalState)` which decomposes into the portal entity's actual fields (width/height/thickness, origin/dest pos, orientation, rotation, scale — `Portal.java:1684-1703`).

### 3.2 Driver evaluation (`PortalAnimation.updateAnimationDriver`, `PortalAnimation.java:310-424`)
1. Bail if no drivers, paused, or portal state null (`:318-329`).
2. Lazily capture `thisSideReferenceState`/`otherSideReferenceState` from the current state (`:331-333`, `:426-433`). **Deltas are always applied to these fixed references, not to the previous frame's state** — evaluation is stateless/idempotent per time value.
3. `effectiveGameTime = getEffectiveTime(gameTime)`; `effectivePartialTicks = isPaused ? 0 : partialTicks` (`:335-336`).
4. For each side, start a `UnilateralPortalState.Builder` from the reference and iterate the driver list with `removeIf`: apply each driver's `delta()` (drivers stack in list order), and if `canRemoveAnimation && isFinished`, remove the driver and **fold its final delta into the reference state** so subsequent evaluation continues from where it ended (`:346-388`).
5. Reject dimension changes ("Portal animation driver cannot change dimension", server clears all drivers; `:390-396`).
6. `combine` the two sides, `portal.setPortalState(newPortalState)` (`:398-402`).
7. If `isTicking`, record `thisTickAnimatedState` via `provideThisTickState` (`:404-406`) — which first runs the tickCount-keyed swap, and on the server treats a second write in the same tick as "Conflicting animation" → clears all drivers (`:292-303`).
8. `portal.rectifyClusterPortals(false)` (`:408`) copies the holder's new geometry to the flipped/reverse/parallel portals **without** triggering a client sync (`PortalExtension.java:325-...`); if ticking, each connected portal also records its own `thisTickAnimatedState` (`:409-414`).
9. Server only: if any driver was removed this update, re-sync the whole cluster with a 1-tick delay — "delay a little to make client animation to stop smoother" (`:416-423`, `Portal.reloadAndSyncToClientWithTickDelay`, `Portal.java:540-546`).

### 3.3 Server tick half
`Portal.tick()` calls `animation.tick(this)` every tick (`Portal.java:955`). On the server (`PortalAnimation.java:244-270`): `updateAnimationDriver(portal, animation, gameTime, partialTicks=1, isTicking=true, canRemoveAnimation=true)`. **`partialTicks = 1` because `ServerLevel.tick` increments game time BEFORE ticking entities** — so during entity ticking, the state computed for `(gameTime - 1) + 1` = end of this tick (comment `:245-250`). Afterwards: null out a side's reference state when its driver list empties (`:255-260`), and auto-unpause when no drivers remain (`:266-269`). The server has no frame half; it evaluates once per tick and syncs geometry to clients only on driver add/remove/clear (full NBT re-sync), never per tick of a running animation.

### 3.4 Client tick + frame split (the two halves)
**Tick half** — mixin injection after `ClientLevel.tick` inside `Minecraft.tick` (`mixin/client/MixinMinecraft.java:119-142`), explicitly "must be after remote world ticking":
```
ClientWorldLoader.tick();                     // remote dimensions tick first
RenderStates.setPartialTick(0);
StableClientTimer.tick();                     // referenceTickTime += 1
StableClientTimer.update(gameTime, 0);
ClientPortalAnimationManagement.tick();       // two-pass driver update
ClientTeleportationManager.manageTeleportation(true);
```
`ClientPortalAnimationManagement.tick()` runs `updateCustomAnimations(true)` — evaluates at `stableTickTime + 1` (end-of-tick state → becomes `thisTickAnimatedState`), never removing finished drivers — then `updateCustomAnimations(false)` — re-evaluates at the immediate stable time so the portal's *actual* state is correct for the tick-side teleportation check (`ClientPortalAnimationManagement.java:61-69`, `:124-157`). The client-side `PortalAnimation.tick` itself only marks the portal into the pump (`PortalAnimation.java:271-276`, `:305-308`).

**Frame half** — mixin at `GameRenderer.render` HEAD (`mixin/client/render/MixinGameRenderer.java:70-100`), skipped when `minecraft.level == null` or not rendering the world:
```
float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);  // "do not use delta tick"
RenderStates.updatePreRenderInfo(partialTick);
StableClientTimer.update(gameTime, partialTick);
ClientPortalAnimationManagement.update();     // "must update before teleportation"
ClientTeleportationManager.manageTeleportation(false);
```
`update()` advances default (nano-clock) animations, runs `updateCustomAnimations(false)` — this non-ticking pass is where finished drivers are actually removed on the client (`canRemoveAnimation = !isTicking`) — and emits `clientAnimationUpdateSignal` (`ClientPortalAnimationManagement.java:84-122`).

The full ordering contract is spelled out in the long comment at `MixinMinecraft.java:86-103`: entity tick (collision uses last/current pos; animation swap happens lazily) → game time++ → end of tick at partialTick 0 → update portal animation as 1 tick later → manage teleportation *right after* → render interpolates. Any port must preserve "animation update immediately precedes teleportation management, in both the tick and the frame."

### 3.5 The four teleportation snapshots
Dynamic (moving-portal) teleportation intersects the player's motion segment with the portal's *swept* surface, requiring the portal state at two granularities:
- **Tick pair** `lastTickAnimatedState`/`thisTickAnimatedState` — produced by `provideThisTickState` during ticking updates; rotated by the `tickCount`-keyed `swapTickRelativeStateIfNeeded` (`PortalAnimation.java:283-290`).
- **Frame pair** `clientLastFramePortalState`/`clientCurrentFramePortalState` — captured by `updateClientState(portal, teleportationCounter)` for every cluster member of every custom-animated portal at the top of `manageTeleportation` (`ClientTeleportationManager.java:144-151`; `PortalAnimation.java:529-542`). The counter increments once per `manageTeleportation` call — tick or frame — so "last frame" means the previous invocation.
`tryTeleport` takes the dynamic path only when the frame counter is exactly `teleportationCounter - 1` and all four snapshots exist; otherwise it falls back to static teleportation (`ClientTeleportationManager.java:241-278`).

### 3.6 Sync to clients
- **Transport:** the portal's entire data (including `animation` NBT: driver lists, pauseTime, timeOffset, reference/paused states, defaultAnimation) rides `ImmPtlNetworking.PortalSyncPacket(id, uuid, entityType, dimensionId, x, y, z, extraData)` — a Fabric `CustomPacketPayload` (`core/network/ImmPtlNetworking.java:134-236`). It serves as **both** the spawn packet (`Portal.getAddEntityPacket` override returns it, `Portal.java:897-902`) and the update packet (`Portal.reloadAndSyncToClient` → `McHelper.sendToTrackers`, `Portal.java:519-529`).
- **Vanilla sync suppressed:** `ServerEntity.sendChanges` is cancelled for portals because vanilla encodes positions in 1/4096 units — "That precision is not enough for portals" (`mixin/common/entity_sync/MixinServerEntity.java:92-105`; also `Portal.java:513-517`: "This is the only place of syncing portal position").
- **Client receipt** (`ImmPtlNetworking.java:180-230`): if the entity id already exists → `acceptDataSync(pos, extraData)`; else create the entity, `setId/setUUID/syncPacketPositionCodec/moveTo`, `readPortalDataFromNbt`, `world.addEntity`, pre-load the dest dimension via `ClientWorldLoader.getWorld(portal.getDestDim())`.
- **`Portal.acceptDataSync`** (`Portal.java:1737-1749`): captures `oldState = getPortalState()`, applies the NBT, then if `defaultAnimation.durationTicks > 0` starts the client default animation old→new. This single hook smooths *every* abrupt server-side change — including the small snap when a driver finishes server-side and the delayed re-sync arrives (§3.2 step 9).
- **Determinism:** running drivers are NOT re-synced per tick. Client and server evaluate the same serialized driver against game time independently; `StableClientTimer` absorbs client game-time jitter. Sync happens only on driver add (`Portal.addThisSideAnimationDriver` → `reloadAndSyncClusterToClientNextTick`, `Portal.java:1806-1814`), removal/finish, clear, pause/resume (`setPaused` ends with a cluster re-sync, `PortalAnimation.java:225`).

### 3.7 Pause/resume
`setPaused(portal, true)` records `pauseTime = gameTime` and snapshots both side states (`PortalAnimation.java:196-202`). While paused, `getEffectiveTime` freezes at `pauseTime + timeOffset` and `effectivePartialTicks` is forced to 0 (`:336`; client passes 0 too, `ClientPortalAnimationManagement.java:146`). `setPaused(portal, false)` shifts `timeOffset -= (gameTime - pauseTime)` so the animation resumes where it stopped, and — critically — applies the delta between the *current* state and the paused snapshot to the reference states (`:203-222`), so edits made to the portal while paused (wand drags etc.) persist into the resumed animation. `setBackToPausingState` (`:228-235`) restores the exact paused geometry to avoid polluting that delta. Both transitions re-sync the whole cluster (`:225`). Caveat: `applyEndingState` computes `effectiveGameTime = gameTime + timeOffset` **without** the pause branch (`:487`) — ending states while paused use live time; port verbatim.

### 3.8 Cluster/holder model
Only one portal in a 4-portal cluster stores the drivers (the holder); the other three get their geometry overwritten by `rectifyClusterPortals` after every driver update (§3.2 step 8), which also maintains `defaultAnimation.inverseScale` on all four members (false/false/true/true for driving/flipped/reverse/parallel; `PortalExtension.java:327`, `:352`, `:396`, `:439`). `AnimationView` (§2.13) translates any member's this/other-side view into holder-list operations, flipping drivers where needed. `PortalExtension.forClusterPortals` = self + connected; `forConnectedPortals` = flipped/reverse/parallel if present (`PortalExtension.java:479-496`).

---

## 4. MC API touchpoint list (deduplicated; candidates for 26.2 breakage)

NBT / serialization:
1. `net.minecraft.nbt.CompoundTag` — `contains`, `getCompound`, `getList(String, 10)`, `getString`, `getInt`, `getLong`, `getBoolean`, `getDouble`, `put`, `putString/Int/Long/Boolean/Double` (`PortalAnimation.java:66-173`, every driver's `toTag`/`fromTag`, `UnilateralPortalState.java:140-161`, `DeltaUnilateralPortalState.java:51-77`, `DefaultPortalAnimation.java:35-83`). 26.2 moved NBT getters to Optional-returning variants — every call here needs mapping.
2. `net.minecraft.nbt.ListTag` (`PortalAnimation.java:78`, `:86`).

Time / level:
3. `net.minecraft.world.level.Level#getGameTime()` (`PortalAnimation.java:197`, `:204`, `:252`, `:487`; `DefaultPortalAnimation.java:64`; `ClientTeleportationManager.java:223`).
4. `Level#isClientSide()` (`PortalAnimation.java:244` et al.).
5. `Level#dimension()` → `ResourceKey<Level>` (`UnilateralPortalState` component; `PortalAnimation` via `PortalState`).
6. `net.minecraft.client.Minecraft` — `getInstance()`, `isPaused()`, `level` field (`StableClientTimer.java:143-176`).
7. `net.minecraft.client.multiplayer.ClientLevel#tickRateManager()` (`StableClientTimer.java:148`, `:176`).
8. `net.minecraft.world.TickRateManager` — `tickrate()`, `runsNormally()` (`StableClientTimer.java:150-158`, `:176`).

Entity lifecycle:
9. `net.minecraft.world.entity.Entity#tickCount` — the animation update counter (`PortalAnimation.java:284`).
10. `Entity#isRemoved()` (`ClientPortalAnimationManagement.java:91`, `:134`).
11. **Server tick ordering assumption**: `ServerLevel.tick` increments game time before ticking entities — the `partialTicks = 1` rationale (`PortalAnimation.java:245-250`). Must be re-verified against 26.2's `ServerLevel`.

Client tick/frame wiring (mixin injection points — highest 26.2 risk):
12. `Minecraft#tick` — injection at `INVOKE ClientLevel.tick(BooleanSupplier)` with `Shift.AFTER` (`MixinMinecraft.java:119-126`).
13. `Minecraft#updateLevelInEngines(ClientLevel)` — HEAD injection driving `CLIENT_CLEANUP_EVENT`, which resets both `StableClientTimer` and `ClientPortalAnimationManagement` (`MixinMinecraft.java:156-175`).
14. `GameRenderer#render(DeltaTracker, boolean)` — HEAD injection for the frame half (`MixinGameRenderer.java:70-100`). 26.2's renderer rewrite may have changed this method's shape.
15. `net.minecraft.client.DeltaTracker#getGameTimeDeltaPartialTick(true)` — the partial-tick source ("Note do not use delta tick", `MixinGameRenderer.java:85-87`).

Sync path (shared with the portal-core/networking slice but load-bearing for animation):
16. `net.minecraft.server.level.ServerEntity#sendChanges` — cancelled for portals (`MixinServerEntity.java:96-105`).
17. `Entity#getAddEntityPacket(ServerEntity)` override returning a custom-payload packet cast to `Packet<ClientGamePacketListener>` (`Portal.java:897-919`).
18. `net.minecraft.network.protocol.common.custom.CustomPacketPayload` + `Type`, `net.minecraft.network.codec.StreamCodec`, `FriendlyByteBuf`/`RegistryFriendlyByteBuf`, `ByteBufCodecs.registry(Registries.ENTITY_TYPE)` (`ImmPtlNetworking.java:134-174`).
19. `ClientLevel#getEntity(int)`, `ClientLevel#addEntity(Entity)`, `Entity#setId/setUUID/syncPacketPositionCodec/moveTo` (`ImmPtlNetworking.java:186-221`).
20. `net.minecraft.world.entity.EntityType#create(Level)` (`ImmPtlNetworking.java:206`) — 26.2 requires an `EntitySpawnReason` argument (per MIGRATION_API_MAP).

Text / misc:
21. `net.minecraft.network.chat.Component#literal`, `MutableComponent#append/withStyle`, `net.minecraft.ChatFormatting.GOLD` (`PortalAnimation.java:544-573`, `NormalAnimation.java:354-364`, `RotationAnimation.java:133-142`).
22. `net.minecraft.util.Mth#lerp` (`DeltaUnilateralPortalState.java:84-87`, `UnilateralPortalState.java:134-136`, `:441-443`).
23. `net.minecraft.world.phys.Vec3` — `add/scale/subtract/lengthSqr/lerp/distanceToSqr` (throughout).
24. `net.minecraft.resources.ResourceLocation` — driver registry keys (`PortalAnimationDriver.java:16-31`).
25. JOML `org.joml.Matrix3d/Matrix3dc/Vector3d` (`UnilateralPortalState.java:40-65`, `:205-237`) and DFU `com.mojang.datafixers.util.Pair` (`:416-424`) — ship with MC.
26. Fabric API: `@Environment(EnvType.CLIENT)` annotations; `net.fabricmc.fabric.api.event.Event` + `EventFactory.createArrayBacked` (via `Helper.createConsumerEvent`, `Helper.java:1435-1444`); `ServerPlayNetworking.createS2CPacket` / `ClientPlayNetworking.registerGlobalReceiver` / `PayloadTypeRegistry` (`ImmPtlNetworking.java:238-267`).
27. `ClientboundSetTimePacket` / `ClientPacketListener.handleSetTime` — **javadoc reference only** in `StableClientTimer.java:17`; no code dependency.
28. `java.lang.System#nanoTime` — the default-animation clock (`ClientPortalAnimationManagement.java:44`, `:85`); JDK, not MC, but note the design decision: default animation is wall-clock, driver animation is game-time.

---

## 5. Registration & wiring

- **Driver deserializer registration (common):** `RotationAnimation.init()` and `NormalAnimation.init()` called from `IPModMain.init` (`core/IPModMain.java:116-118`); `OscillationAnimation.init()` deliberately commented out (`:119`). Each registers a `CompoundTag → driver` function under its `imm_ptl:*` id in `PortalAnimationDriver.deserializerRegistry` (`PortalAnimationDriver.java:19-24`).
- **Client init:** `StableClientTimer.init()` and `ClientPortalAnimationManagement.init()` from `IPModMainClient.onInitializeClient` (`core/IPModMainClient.java:113-115`). Both register cleanup with `IPCGlobal.CLIENT_CLEANUP_EVENT` (fired from the `Minecraft.updateLevelInEngines` mixin on world change/exit, `MixinMinecraft.java:156-175`); the animation management additionally cleans on `ClientWorldLoader.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT` (`ClientPortalAnimationManagement.java:29-32`).
- **Per-instance:** `PortalAnimation` is a plain `final` field on `Portal` (`Portal.java:192`) — no registry; (de)serialized inside the portal's own NBT (`Portal.java:358`, `:422`); ticked from `Portal.tick()` (`Portal.java:955`).
- **Client pumps:** tick half in `MixinMinecraft.onAfterClientTick` (`MixinMinecraft.java:119-142`); frame half in `MixinGameRenderer.onFarBeforeRendering` (`MixinGameRenderer.java:70-100`). Both immediately precede `ClientTeleportationManager.manageTeleportation` — that adjacency is a hard ordering contract (§3.4).
- **Public entry points on `Portal`** (the API other subsystems/commands use): `addThisSideAnimationDriver` / `addOtherSideAnimationDriver` (route through `getAnimationView`, then cluster re-sync; `Portal.java:1806-1814`), `clearAnimationDrivers` (`:1802-1804`), `pauseAnimation`/`resumeAnimation` (`:1816-1822`), `resetAnimationReferenceState` (`:1824-1826`), `getAnimationView` (`:1828-1861`), `getAnimationHolder` (`@Nullable`, self-first, null when no cluster member has drivers; `:1907-1934`) / `getPossibleAnimationHolder` (the self-fallback wrapper; `:1936-1942`), `getAnimationEffectiveTime` (`:1944-1947`), `getAnimationEndingState` (`:1890-1892`), `getDefaultAnimation` (`:1798-1800`), `disableDefaultAnimation` (`:1949-1952`).
- **In-tree consumers:** `/portal animation ...` command suite (`core/commands/PortalAnimationCommand.java` — builds `NormalAnimation`/`RotationAnimation`, uses `getAnimationEffectiveTime`, `getAnimationView`); `EndPortalEntity`'s toObsidianPlatform view-box (infinite `RotationAnimation` + `NormalAnimation.createOscillationAnimation`, `core/portal/EndPortalEntity.java:172-191`); peripheral wand tools (consume `clientAnimationUpdateSignal` and `UnilateralPortalState.ANIMATION_TYPE_INFO` with the mod-side `Animated` helper, `q_misc_util/my_util/animation/Animated.java`).
- **Events exposed:** `ClientPortalAnimationManagement.CLIENT_PORTAL_DEFAULT_ANIMATION_FINISH` (Fabric event, fired on default-animation completion, `ClientPortalAnimationManagement.java:21-22`, `:98`); `clientAnimationUpdateSignal` (mod `Signal`, emitted per frame update, `:27`, `:121`; `Signal` is a simple synchronized listener list, `q_misc_util/my_util/Signal.java:11-60`).

### Porting notes specific to this slice
- The tick-time convention (`real time = tickTime - 1 + partialTicks`), the server `partialTicks = 1`, the client ticking evaluation at `stableTickTime + 1`, and `canRemoveAnimation = !isTicking` on client / `true` on server are a mutually consistent set — change none of them independently.
- `OscillationAnimation` is dead code (unregistered, throws `NotImplementedException`); its id stays reserved.
- Quirks to port verbatim: `NormalAnimation.deserialize` reads an unused `"initialState"` tag (`NormalAnimation.java:116`); `applyEndingState` ignores pause in its effective time (`PortalAnimation.java:487`); `DeltaUnilateralPortalState.fromDiff` puts the width ratio in the z scaling slot (`DeltaUnilateralPortalState.java:175`); `DeltaUnilateralPortalState.apply(Builder)` skips thickness while `Builder.apply(delta)` scales it (`DeltaUnilateralPortalState.java:106-117` vs `UnilateralPortalState.java:328-341`) — the driver path uses the latter.
