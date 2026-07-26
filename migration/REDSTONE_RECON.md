# REDSTONE / RAIL / MINECART PASSTHROUGH — RECON REPORT

**Date:** 2026-07-25 · **Branch:** `redstone/passthrough` @ `e8e4767` · **MC 26.2** · `entityPortals=true`
(default ON — `common/src/main/java/com/warwa/seamlessportals/EntityPortalsFlag.java:107,111,119`)

Method: 7 parallel recon readers → 2 adversarial verifiers (distinct lenses: block-placement path /
lifecycle-persistence) → fold. Top claims re-verified by hand afterwards. Paths are worktree-relative;
`REF` = `C:\Users\warwa\ModDev\mc262-ref`, `IP` = `C:\Users\warwa\ModDev\ImmersivePortalsMod`.

---

## 0. PINNED DESIGN DECISIONS (user, 2026-07-25) — binding, supersedes the prompt where they differ

1. **Scope: full stack, all four sub-features.** Order of the back three ((b) rail connection,
   (c) redstone bridge, (d) minecart traversal) is decided AFTER (a) lands, on live evidence.
2. **The aperture is ordinary building space** — any block, any cell, at any height. This
   **supersedes** the prompt's "bottom opening row only" pin: a rail must be able to pass through at
   mid-height, not just along the floor.
3. **The integrity check survives for IGNITION ONLY.** After a portal is lit, opening contents never
   tear it down. At ignition the aperture predicate must still refuse arbitrary blocks (you cannot
   light a portal through a stone wall) — *but* it must tolerate passthrough blocks (rails, redstone),
   because of decision 4. **This is a reconciliation made by Claude, not stated by the user**, of two
   user statements that otherwise conflict ("integrity check still there for initial creation" vs "the
   portal should be allowed to be re-lit" over a surviving rail). Flagged to the user; revisit if wrong.
4. **Break rule:** when a frame is broken, blocks in the aperture **survive in their own dimension**
   and the frame can be **re-lit over them**.
5. **Seam model — each dimension keeps its own ordinary block on its own grid.** No half-block
   storage, no splitting. The "half in each dimension" appearance comes from clipping that already
   exists: the stencil portal view draws the dest world for everything past the plane, so a block in
   the source aperture cell shows only its near half, and the coincident dest-side block shows only
   its near half. The two visible halves join into one continuous block across the seam.
   **PLUS mirroring:** placing a block in one aperture cell auto-places it in the coincident cell on
   the other side, so a seam block never has to be built twice from both dimensions.
   ✅ **CLIPPING CONFIRMED LIVE by the user, 2026-07-25** (dev client, both levers armed). Observed:
   the plane does sit in the middle of the block, and a block placed there **is clipped at the plane** —
   the slicing is real, no longer an inference. Same for a rail.
   ❗ **But the far half does NOT become part of the destination world.** It remains a
   **source-dimension** block living behind the portal, merely hidden from the window. There is
   nothing on the destination side for it to meet. So the clipping half of the seam model HOLDS and
   the model is *incomplete without mirroring* — mirroring is not a convenience, it is the mechanism
   that puts a counterpart in the destination's coincident cell so the two clipped halves face each
   other and read as one block. This is now a REQUIRED part of (a), not an option.
   ⚠ **Open design problem this exposes:** mirroring is well-defined when the two planes share
   sub-block phase (obsidian pairs — the coincident cell is unambiguous). Under decision 6 (general
   phase) the source block's far half can straddle TWO destination cells, and which one receives the
   mirrored block is undecided. Must be settled before (a) is implemented.
7. **MIRRORING RULES (user, 2026-07-25, after the visual round):**
   - **Conflict → REFUSE PLACEMENT.** If the destination's coincident cell is already occupied, the
     placement is refused outright — no overwrite, no source-only half-placement. A seam is either
     whole or it does not happen. (Design consequence: placement validation must consult the
     DESTINATION world, which may be unloaded — see the hold/deny question for the unloaded case.)
   - **Break one half → break the other.** Removing either half removes its counterpart. Must
     survive chunk unload, a fully unloaded destination, and the four-portals-per-frame cluster
     (a per-portal mirror driver would otherwise fire twice per side — cluster-dedupe required).
   - **Phase-offset target = GREATEST OVERLAP.** Under a sub-block phase offset the mirrored block
     goes to whichever destination cell holds the greatest overlap with the source block's far half.
     This deliberately reproduces the half-block jog the user predicted ("the blocks that get built
     through are offset by .5 blocks") rather than concealing it. Proposed by Claude, **user-approved**.
6. **General sub-block phase from day one.** Every rail/redstone/minecart decision must be
   phase-agnostic. Obsidian nether-portal pairs always have both planes mid-block, so the coincident
   case is what they produce; portals whose planes sit on a block boundary (wand/custom) produce a
   true 0.5 offset. Both are in scope and both get tested.

**Geometric fact underpinning 5 and 6** (hand-verified, §2): the portal plane sits at the **middle**
of the aperture blocks, not on a boundary. For an obsidian pair, both planes are mid-block, so the
source and destination aperture cells map onto the **same** span across the seam — they are coincident,
not offset. A true 0.5 offset arises only when the two planes differ in sub-block phase. A corollary
worth remembering: because no block face ever coincides with the plane, a rail can never "end flush at
the portal" on an obsidian frame — there is *always* a straddling block if you build through.

---

## 1. PREMISE VERDICT — **FAILS AS WRITTEN** (recoverable)

`REDSTONE_PASSTHROUGH_PROMPT.md` §2 claims: *"Entity-portal openings are not `nether_portal` blocks, so
opening cells can natively be rail or redstone. The throwaway plumbing layer is gone."*

**Half right.** Vanilla `nether_portal` blocks genuinely cannot form:
`qouteall/imm_ptl/peripheral/mixin/common/nether_portal/MixinAbstractFireBlock_CVB.java:40-57` redirects
`PortalShape.findEmptyPortalShape` to `Optional.empty()`, so `PortalShape.createPortalBlocks`
(`REF/net/minecraft/world/level/portal/PortalShape.java:175-180`) is unreachable. **Hand-verified.**

**Wrong where it matters.** The cells are not left air. Every cell of `blockPortalShape.area` is filled
with `PortalPlaceholderBlock.instance` (`immersive_portals:nether_portal_block`) —
`qouteall/imm_ptl/core/portal/nether_portal/NetherPortalGeneration.java:295-304` → `:98-109`, reached from
`PortalGenInfo.java:119-127` ← `IntrinsicNetherPortalForm.java:65`.

Three independent blockers, all confirmed by both verifiers:

| # | Blocker | Citation |
|---|---|---|
| 1 | **Cell is occupied and non-replaceable.** `Properties.of().noCollision().sound(GLASS).strength(1.0f,0).noOcclusion().noLootTable().lightLevel(15)` — no `.replaceable()`, so `BlockPlaceContext.canPlace()` is false and `BlockItem.place` fails. | `PortalPlaceholderBlock.java:60-72`; `REF/…/item/context/BlockPlaceContext.java:52-54` |
| 2 | **You cannot even aim at it.** A placeholder hit is scored distance `23333` (∞) and aim is rerouted through the aperture to the destination world. | `BlockManipulationClient.java:104-109`, `:78-93`; `MixinClipContext.java:71-85` |
| 3 | **Integrity self-destruct.** `isPortalIntactOnThisSide()` requires **every** `area` cell to still be `== PortalPlaceholderBlock.instance`. Failure → `markShouldBreak` → `breakPortalOnThisSide` → opening wiped to AIR, entity killed, **and the kill propagates to the paired portal in the other dimension**. | `NetherPortalEntity.java:72-82`; `BreakablePortalEntity.java:158-176`, `:127-140`, `:234-268`; notify path `PortalPlaceholderBlock.java:104-129` |

> **Blocker 3 CONFIRMED LIVE 2026-07-25 (RS-TEARDOWN-TEST leg, suppressor OFF).** Until this run the
> teardown was asserted from source reading and had **never been observed** — every armed probe run
> reported `intact=true` only, because the suite never puts a block in a real aperture. The user reported
> not seeing a portal break in play, which was correct *and* compatible with the mechanism being real:
> blockers 1+2 mean a hand-placed block never lands in the opening at all (aim redirects through the
> portal), so there is nothing to break over. It is only reachable via `/setblock`.
>
> Three corrections the run forced:
> 1. **It is IMMEDIATE, not "within 233 ticks."** Kill landed on the *same tick* as the `setblock`
>    (tick 549) via the `NOTIFY` path. The 233-tick sweep is only the backstop.
> 2. **All four entities die**, both coincident portals on the near side and both nether twins —
>    cross-dimension propagation observed, not inferred.
> 3. **Teardown wipes only the OPENING; the obsidian frame survives** and remains matchable by the
>    destination frame-search. (This is what makes user decision 4 — "re-light over a surviving rail" —
>    coherent, and it is also a harness hazard: a leftover test frame inside another leg's 128-block
>    match radius false-failed leg 6a.)

**The restatement that DOES hold — and it is the one that matters:** the entity architecture is
*indifferent* to what sits in the aperture. Geometry is frozen entity state, never re-derived from blocks
(`Portal.java:596-619`, `:1015-1030`, `:1175-1186` — zero `getBlockState` in any geometry path); no
crossing or collision code reads aperture blocks. **Every blocker above is a policy check in mod/IP-owned
code, not an architectural constraint.** The block era's four vanilla-`PortalShape` mixin gymnastics really
are gone; they are replaced by four *policy edits we own*. That is a genuine and large improvement — the
deferral was correct, its stated reason was not.

### Hazard the prompt did not anticipate

`breakPortalOnThisSide` only clears cells that are **still** placeholder
(`BreakablePortalEntity.java:128-136`). The aperture predicate for (re-)ignition is `BlockStateBase::isAir`
(`IntrinsicNetherPortalForm.java:95`, `DiligentNetherPortalForm.java:52-54`), and
`BlockPortalShape.findAreaBreadthFirst` returns `null` on a non-air non-frame cell (`:218-220`, `:251-263`).

> **Break the frame with a rail in the floor → the rail survives → the frame is permanently un-relightable
> until the player mines the rail.** In scope from day one, not polish.

---

## 2. GEOMETRY, PINNED (hand-verified)

Entity position = **centre of the aperture** — `BlockPortalShape.java:360-364`:
```java
Vec3 center = innerAreaBox.getCenterVec();
portal.setPos(center.x, center.y, center.z);
```
Extents from `innerAreaBox.getSize()` (`IntBox.java:94-96`), assigned `BlockPortalShape.java:376-377`.

**2×3 frame in the XY plane (normal ±Z), aperture x∈{X0,X0+1}, y∈{Y0..Y0+2}, z=Z0:**

| quantity | value |
|---|---|
| `innerAreaBox.l / .h` | `(X0,Y0,Z0)` / `(X0+1,Y0+2,Z0)` |
| `position()` | `(X0+1, Y0+1.5, Z0+0.5)` |
| `axisW / axisH` | `(1,0,0)` / `(0,1,0)` |
| `width / height` | `2 / 3` |
| **BOTTOM OPENING ROW** | `(X0,Y0,Z0)`, `(X0+1,Y0,Z0)` — i.e. `innerAreaBox.l.getY()` |
| obsidian sill | `y = Y0−1`, member of `frameAreaWithoutCorner` (`BlockPortalShape.java:128-139`) |
| **portal plane** | `z = Z0 + 0.5` |

Three consequences to design around:

- **The plane bisects the bottom-row block.** No block boundary coincides with the portal plane. A rail's
  model, its `RailShape`, and any collision straddle it. The source-side bottom-row cell and the
  destination-side bottom-row cell each have half their volume "past" the plane.
- **`width`/`height` are not horizontal/vertical.** For a YZ-plane frame `getAnotherTwoAxis(X)=(Y,Z)`, so
  `axisW` is UP and `width` is the *vertical* extent. Invariant that always holds:
  bottom-of-aperture `= position().y − verticalExtent/2 = innerAreaBox.l.getY()`.
- **Four `Portal` entities per frame pair** — two coincident opposite-normal ones per side
  (`PortalGenInfo.java:89-114`; flipped copy shares pos/width/height/axisH with `axisW` negated,
  `PortalManipulation.java:132-156`), cluster-bound at `:107`. Any per-portal feature state must be
  cluster-aware or it desyncs between the two faces.

**Support is fine ONLY on the bottom row — and that is now a REQUIREMENT GAP.** `BaseRailBlock.canSurvive`
= `canSupportRigidBlock(level, pos.below())` (`REF/…/BaseRailBlock.java:58-61`), `RedStoneWireBlock.canSurvive`
(`:259-266`). For the bottom opening row the block below is the obsidian sill, which is face-sturdy — fine.
**At any greater height the block below is another opening cell holding `PortalPlaceholderBlock`, which is
`noCollision` and therefore NOT rigid support.** OBSERVED 2026-07-25 (RS-TEARDOWN-TEST): a rail
`/setblock`-ed into a mid-height opening cell **popped instantly** — final cell state `minecraft:air`, not
`minecraft:rail` — while still tripping the teardown on its way out. Under user decision 2 ("ordinary
building space at ANY height") sub-feature (a) must therefore solve **support**, not merely placement.
Rails and dust are `noCollision`, as is the placeholder, so nothing about collision changes.

> ⚠ **This invalidates a line in the brief given to the (a) design panel**, which stated support was
> already fine, citing the sill. That is true only for the bottom row. Any design that lets a rail float
> at mid-height must say what holds it up.

---

## 3. WHAT IS LIVE

| Subsystem | Live class (flag ON) | Dead legacy (flag OFF only) |
|---|---|---|
| Geometry | `Portal.java` fields + `BlockPortalShape.java:360-377`; persisted `:389-417,458-470`, synced `:944-968` | `com/warwa/seamlessportals/portal/PortalInfo.java:13-24` (its `axis` = WIDTH axis, **opposite** of `BlockPortalShape.axis` = NORMAL axis) |
| Creation | `MixinAbstractFireBlock_CVB.java:40-57` / `MixinFlintAndSteelItem_CVB.java:35-80` → `IntrinsicPortalGeneration.java:76-103` → `IntrinsicNetherPortalForm.java:64-68` | `PortalShapeFormMixin.java:39`, `PortalForcerMixin.java:37`, `ServerLevelFireSpreadMixin.java:64` |
| Teardown | `BreakablePortalEntity.java:158-194` + `NetherPortalEntity.java:72-82`; notify `PortalPlaceholderBlock.java:104-129` | `ServerLevelBlockUpdateMixin.java:58`, `NetherPortalBlockMixin.java:84`, `LevelChunkSetBlockStateMixin.java:89` |
| Crossing | `ServerTeleportationManager.java:96-172,543`; vehicles `:717-749`, `:551-563` | `EntityMixin.java:87`, `PortalTeleporter`, `ProjectilePortalHandler` |
| Collision | `CollisionHelper` + `PortalCollisionHandler`, `@Redirect` on `Entity.collide` at `MixinEntity.java:130` | `MixinAbstractMinecartEntity.java:24-35` — registered in `seamlessportals-ip-core-common.mixins.json:27`, body fully commented out (26.2 removed `lerpTo`). **Empty shell = ready zero-cost anchor point.** |
| Render | `RendererUsingStencil` (`IPModMainClient.java:83,86`); mesh from entity fields only (`ViewAreaRenderer.java:278,366`, `RectangularPortalShape.java:142-157`) | `GameRendererPortalPrepareMixin.java:63`, `StencilPortalRenderer`, `PortalShapeRenderer`, `SectionCompilerMixin.java:49-51,74-76` |

**Prior art: none.** `qouteall/imm_ptl/core/redstone/CrossPortalRedstoneMediumBlockEntity.java` is a 6-line
empty marker interface, zero Java references, byte-identical upstream. Grep over `IP` for
`RedStoneWire|SignalGetter|getSignal|BaseRailBlock|RailState|RailShape` → **0 hits**. Greenfield, as the
prompt said.

---

## 4. THE FOUR SUB-FEATURES, SIZED

### (a) Placeable floor — **MEDIUM**, 4 forced IP-core edits

| # | Intercept | Intervention |
|---|---|---|
| 1 | `NetherPortalEntity.isPortalIntactOnThisSide()` `:72-82`; `GeneralBreakablePortal.java:18-25` | Accept a whitelist (rails / `redstone_wire`) in bottom-row cells instead of placeholder-only |
| 2 | `PortalPlaceholderBlock.java:60-72` properties, **or** the fill loop `NetherPortalGeneration.java:295-304` | Either `.replaceable()` on the placeholder (blunt, affects all cells) or carve the bottom row out of the fill (loses `lightLevel 15` there) |
| 3 | `MixinClipContext.onGetBlockShape:71-85` + `BlockManipulationClient.java:104-109`, `:78-93` | Restore targetability for bottom-row cells only, else every right-click lands in the destination dimension |
| 4 | `IntrinsicNetherPortalForm.java:95` / `DiligentNetherPortalForm.java:52-54` (`BlockStateBase::isAir`); `BlockPortalShape.findAreaBreadthFirst:218-220,251-263` | Allow the whitelist in the aperture predicate, else a frame containing a rail can never be re-lit |

All four sites are **ported IP-core** (S16 1:1) — every edit is a real IP deviation and must be recorded
as such. Novel: the whitelist and the bottom-row-only scoping.
**Hardest unknown:** whether carving the bottom row out of the placeholder fill desyncs anything — the
aperture mesh spans the full bottom cell so nothing visual changes, but the cell loses `lightLevel 15`,
the crossing ray-trace still uses it, and `breakPortalOnThisSide`'s cleanup symmetry changes.

### (b) Rail connection across the plane — **HARD**

`RailState` is structurally single-level. Hand-verified:
```java
private final Level level;                                  // RailState.java:13
private final List<BlockPos> connections = Lists.newArrayList();  // :18
private boolean hasConnection(BlockPos railPos) {           // :116-125
    ... if (pos.getX() == railPos.getX() && pos.getZ() == railPos.getZ()) return true;
}
```
- Neighbour scan `RailState.place(boolean,boolean,RailShape)` `:218-226` uses hard-coded
  `pos.north()/south()/west()/east()`, not a `Direction` loop.
- `getRail(BlockPos)` `:94-110` probes three y-levels per neighbour via the captured `this.level`.
- Entry funnel: `BaseRailBlock.updateDir(Level,BlockPos,BlockState,boolean)` `:109-116`.

**Hardest unknown — the real blocker:** `hasConnection` compares **X and Z only, Y ignored**, over a bare
`List<BlockPos>`. A cross-dimension neighbour has arbitrary coordinates that can never match. This is
plausibly a `RailState` reimplementation, not a redirect job. Ported: nothing. Novel: all of it.

### (c) Redstone signal bridge — **HARD**, but the cleanest chokepoint

Three *independent* flat-coordinate systems; no shared chokepoint:
1. **Read** — `SignalGetter.java:94` `this.getSignal(pos.relative(direction), direction)` inside
   `getBestNeighborSignal` (a loop — one interception covers all six); plus six hard-coded sites in
   `getDirectSignalTo:19-44` and six in `hasNeighborSignal:77-86`; plus
   `RedstoneWireEvaluator.getIncomingWireSignal:29-47`.
2. **Dispatch** — `NeighborUpdater.java:29` inside `updateNeighborsAtExceptFromFacing:26-30`. Without this
   the far-side wire never re-evaluates and the read redirect is inert.
3. **Connection shape** — `RedStoneWireBlock.getConnectingSide:234-257`, `getConnectionState:124`,
   `updateIndirectNeighbourShapes:216-228`.

`SignalGetter` is an interface with `default` methods reached via `LevelReader`
(`REF/…/LevelReader.java:26`) — the mixin must target `SignalGetter.class`, **not** `Level`.

**Sharpest hazard:** `RedStoneWireBlock.shouldSignal` (`:67`) is **mutable state on the block singleton**,
toggled around a `level.getBestNeighborSignal(pos)` call in `getBlockSignal:279-284`. Any cross-level
re-entrant evaluation corrupts it globally. Secondary: `Orientation`
(`REF/…/redstone/Orientation.java:16,58-79,116-120`) is direction-absolute and needs remapping through any
rotating portal (`Portal.rotation`, `Portal.java:156-157`).

Design against `DefaultRedstoneWireEvaluator` — `ExperimentalRedstoneWireEvaluator` is gated by
`FeatureFlags.REDSTONE_EXPERIMENTS` (`RedStoneWireBlock.java:350-352`), off by default.

### (d) Minecart traversal — **MEDIUM-HARD**, depends on (b)

**Crossing itself is already solved and live.** Empty cart takes the regular-entity path
(`ServerTeleportationManager.java:96-120` → `:122` → `:543`); a ridden cart is skipped there (`:129`, `:570`)
and carried by the player's crossing with dismount/move/remount (`:445-500` + `teleportVehicleAcrossDimensions:717-749`).
Detection ray-traces `getEyePosition(0)→(1)` through the shape (`Portal.java:1296-1321`), so `setPos`-driven
motion is captured. Minecart eye ≈ 0.595 above origin — inside any ≥1-tall aperture. `canTeleport` is
unoverridden. The Portal entity is not an obstacle (no `canBeCollidedWith`/`isPickable`/`isPushable`
override; vanilla defaults all `false`).

**What is missing is rail following**, and it is all `OldMinecartBehavior` — **hand-verified live**:
`FeatureFlags.java:41` `DEFAULT_FLAGS = VANILLA_SET = FeatureFlagSet.of(VANILLA)`; `MINECART_IMPROVEMENTS`
is registered but not in the set, and `AbstractMinecart.java:103-106,589-591` selects `OldMinecartBehavior`
accordingly. Five same-`Level` sites:
1. `OldMinecartBehavior.java:59-70` — on-rails test → `moveAlongTrack` vs `comeOffTrack`
2. `:105-106,126` — shape read driving direction
3. `:275-300`, `:316-322` — `getPosOffs` / `getPos` lookahead
4. `AbstractMinecart.getCurrentBlockPosOrRailBelow():292-303` — the `BlockPos` itself needs transforming
5. `:187-211` — `setPos` snapping to source-dimension lane coords. **These bypass `Entity.move`, so IP's
   `Entity.collide` redirect never sees them.**

Tick order: `AbstractMinecart.tick()` → `handlePortal()` `:265` → `behavior.tick()` `:277`.
**Hardest unknown:** whether derail can be avoided *without* atomic same-tick crossing — the cart's
`getCurrentBlockPosOrRailBelow()` resolves in the far dimension while `this.level()` is still the near one,
so `isRail` returns false and `comeOffTrack` fires on the tick *before* the crossing completes. Also,
`teleportRegularEntity` (`:621-643`) transforms velocity but does not re-align it to the destination rail axis.

**Cross-portal collision is NOT a prerequisite** — it is already live
(`MixinEntity.java:90-145`, `PortalCollisionHandler.handleOtherSideMove:165-223`) and is the wrong
mechanism anyway: it overrides *blocking* collision only, never `Level.getBlockState`. The one existing
cross-portal block-state read (`MixinEntity.onGetInBlockState:239-261`) is gated
`collidingPortal.getNormal().y > 0` — floor portals only, not a vertical aperture.

**Free win:** rails already call `level.hasNeighborSignal(pos)` (`BaseRailBlock.java:115`,
`PoweredRailBlock.java:117,130`). A correct `SignalGetter` redirect — sub-feature (c) — delivers
cross-portal rail *powering* and curve resolution with **zero rail-code changes**. That makes (c) partly a
prerequisite for a good (b)/(d), not an independent branch.

---

## 5. OPEN QUESTIONS ONLY THE LIVE CLIENT CAN SETTLE

Lever discipline: probes DEFAULT-OFF via `Boolean.getBoolean("seamlessportals.<name>Probe")` in a
**non-mixin holder class** (mixins silently drop non-constant static initializers — see
`ShaderpackViewsProbeLever.java:11-20`), with a `-P` row in **both** `fabric/build.gradle` blocks
(`client` `:98-123`, `crossingGametest` `:206-253`; format per `:242`). One-shot liveness logs follow
`PortalChunkTracker.java:272-273,469-477`.

1. Does the notify path fire same-tick on a bottom-row change, or fall through to the 233-tick sweep?
   Log at `BreakablePortalEntity.java:167` with `trigger = isNotified ? "NOTIFY" : "SWEEP"`.
2. Which cell fails the predicate, and is it always the one we touched? Log the first failing
   `blockPos` + state inside `NetherPortalEntity.java:74-77`, rate-limited.
3. After a `MixinClipContext` carve-out, does the crosshair ever resolve to a bottom-row cell, or does
   `BlockManipulationClient:78-93` still hijack it? 1 Hz-latched log.
4. Does `BlockItem.place` reach `canPlace()` at all, and with what `getClickedPos()`?
5. **Ordering test (decides §4d's hardest unknown):** does an empty cart's `shouldEntityTeleport`
   (`ServerTeleportationManager.java:111`) fire before or after `comeOffTrack`
   (`OldMinecartBehavior.java:60` else-branch)? Both log `getGameTime()` + cart id.
6. Is a rail visually sliced at the plane? Edge-on A/B — if it renders whole edge-on, the slicing is
   stencil overwrite, not model clipping.
7. Does the aperture cell go dark once the placeholder's `lightLevel 15` is removed?
8. After a legitimate frame break with a rail present, can the frame be re-lit? Instrument
   `BlockPortalShape.findAreaBreadthFirst:218-220` with a one-shot "aperture scan aborted at pos={} state={}".

**Gate:** `gradlew :fabric:runCrossingGametest` (8 legs, `CrossingSmoke.java`; hard-aborts if the flag is
OFF, `:88-93`). It has **zero** redstone/rail/minecart coverage and drives no block placement — it is a
regression gate, not a feature gate. New legs will be needed for this feature.

---

## 6. RISKS / CONTRADICTIONS

**Reader-vs-verifier conflicts, resolved (verifiers won both):**
- `crossing` recon claimed "the aperture is air, so a rail/dust in the bottom opening row is physically
  placeable there" — **false**, contradicted by four citations against zero. The rest of that dimension
  (minecart internals, collision, teleport gating) is sound and independently spot-checked.
- `creation-blocks` recon called `NetherPortalBlockMixin.updateShape` "the equivalent hazard" — it is inert
  flag-ON (`NetherPortalBlockMixin.java:84`). The live hazard is the placeholder integrity check.
- The two verifiers agreed with each other on every checked point.

**UNVERIFIED but load-bearing:**
- **JDK discrepancy.** `gradle.properties` says `java_version=25`; the runbook points `JAVA_HOME` at
  `jdk-21.0.11.10-hotspot` (which does exist — checked). No gradle run was permitted during recon, so
  which JDK actually resolves is unverified. Resolve before blaming a build. The C2 `CompileCommand=exclude`
  mitigations (`fabric/build.gradle:112,118`) are Temurin-25-specific.
- `fabric/runs/` does not exist in this worktree yet; log-path layout is inferred from the sibling checkout.
- "~60 s suite runtime" is doc-sourced (`migration/S19_HANDOFF.md:47-48`), not measured.
- The render prediction for a sliced rail is **INFERRED** from stencil mechanics
  (`RendererUsingStencil.java:362-415`, GEQUAL + depth-write, depth clear `:417-457`, restore `:459-495`),
  not observed. High confidence, still inference. Same for "redstone dust particles depth-rejected".

**Design risks not yet costed:**
- `RedStoneWireBlock.shouldSignal` singleton-mutable re-entrancy — sharpest correctness hazard in the feature.
- Four cluster entities share `fromShape`/`toShape` (`PortalGenInfo.java:89-116`); per-portal feature state
  must be cluster-aware.
- `markShouldBreak` propagates cross-dimension with a 30-retry deferred task if the far chunk is unloaded
  (`BreakablePortalEntity.java:234-268`) — a bad predicate relaxation on one side silently kills the twin.
- `isNotified = true` is a plain field initializer never reset by `readAdditionalSaveData` (`:47,:69-108`),
  so integrity re-runs on the first tick after every chunk load. There is no "smuggle it in while the chunk
  is unloaded" escape.
- `unbreakable` (`:46,166`) is the only existing bypass and disables genuine frame-break teardown entirely.
  Not a shippable lever.
