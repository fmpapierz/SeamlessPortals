# FINAL IMPLEMENTABLE SPEC — SUB-FEATURE (b), RAIL CONNECTION ACROSS THE PORTAL PLANE

**Source of truth for vanilla:** `C:\Users\warwa\ModDev\mc262-ref\net\minecraft\…` (opened this session). **Source of truth for the mod:** `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`, tip `d7b74a8` (opened this session).

**Reference-copy ruling, settled first because it decides ~40 citations.** Lens B read a NeoForge-patched decompile (its own evidence: it quotes `BaseRailBlock:82` as `getRailDirection(state, level, pos, null)`, a NeoForge patch). I verified `mc262-ref` this session: `RailState.java` is 352 lines with `pos:14`, `updateConnections:34-76`, `getRail:94-110`, `hasConnection:116-125`, `connectTo:143-206`, `place:218-347`, guard `:332`, writes `:205`/`:333`; and `Block.canSupportRigidBlock` is at **`Block.java:325`**, not `:335`. **The adjudication's line numbers are correct and are used throughout. Lens B's §10 "citation drift" list is withdrawn** — it is drift in lens B's own reference, not in the design.

---

## 0. VERDICT ON THE TWO VERIFICATIONS

Nine verifier findings are confirmed and their fixes go in. Four are refuted with source. Two are refuted-as-stated but contain a true residue that produces a different, better fix than either verifier proposed. One finding neither verifier made, but which their disagreement exposed, is the single largest correction in this fold (§0.6).

| # | finding | raised by | verdict | where the fix lands |
|---|---|---|---|---|
| B-1 | mirror observes the **pre-resolution** state last | A | **CONFIRMED** | §3.1 — `LevelChunkSetBlockStateMixin` |
| B-2 | mirror's write re-enters rail resolution under `applying` | A | **CONFIRMED, damage re-scoped** | §7 T2′ / §5 residual |
| B-3 | proxy `connectTo` flattens the far shape | A | **REFUTED** — §0.2 | — |
| B-4a | `MAX_DEPTH` is dead code as written | A | **CONFIRMED** | §2.4 `enterWrite/exitWrite` |
| B-4b | `writeLocal` can force a blocking chunk load | A | **CONFIRMED** | §2.2 per-call guards |
| B-5 / §3 | phase gate silently disables (a) for horizontal-normal portals | **A and B** | **CONFIRMED (unanimous)** | §2.3 tri-state `SeamPhase` |
| B-6a | classifier refuses lattice-preserving general phases | A | **REFUTED** — sign error, §0.3 | — |
| B-6b | a `reverse == null` first bind is **permanently** sticky | A | **CONFIRMED** | §3.5 `AperturePassthroughInit` |
| B-7 | seam slopes always destroyed | A | **REFUTED as stated**, §0.4 — but its `LevelChunk:318` observation is real and is used | §3.3 |
| B-8 | `reresolve` runs on the client | A | **CONFIRMED** | §3.4 server guard |
| B-9 | `unbind` enumerates the **new** geometry, orphaning cells | A | **CONFIRMED** | §3.5 bound-cell snapshot |
| B-10 / §5 | R1's cross-priority is wrong in the `f` slot | **A and B** | **CONFIRMED (unanimous)** | §2.5 — **R1 is inverted to R1′** |
| §2 | bi-faced pair → `findDestinationPortal` fails **open**, not closed | B | **CONFIRMED** | §3.5 `isReversePortal` dot test |
| §4 | cold far chunk → permanently wrong shape, no self-heal | B | **CONFIRMED** | §2.6 retry queue |
| §6 | R1 baked into the shared primitive; Y-window hard-coded | B | **CONFIRMED** | §2.2 — policy lifted out entirely |
| §8 | `Operation.call` boxing understates the bill | B | **CONFIRMED** | §6, stated honestly |
| §9a | `AperturePassthroughLever.DISABLED` never checked | B | **CONFIRMED** | §2.2, §3.3, §3.4 |
| §9b | `require=1` over a multi-target wrap is not a load-time guarantee | B | **CONFIRMED** | §3.2 — every wrap split per method with exact counts |
| — | **the classifier is not a mixed-phase detector at all** | *neither* | **NEW, §0.6** | §2.3 `SeamMap.latticeAligned` |

---

### 0.1 ★ B-1 CONFIRMED, and it is the most damaging thing in the fold

`LevelChunk.setBlockState` calls `onPlace` **inside its own body**:

`LevelChunk.java:326-328`
```java
if (!this.level.isClientSide() && (flags & 512) == 0) {
    state.onPlace(this.level, pos, oldState, movedByPiston);
}
```
`Level.setBlock:216-268` contains no `onPlace` — the dispatch is entirely inside the chunk.

The (a) mirror driver is `@Inject(at = @At("RETURN"))` on exactly that method, and it mirrors **its `newState` parameter**: `LevelChunkSetBlockStateMixin.java:93-100`, `:113` `SeamMirror.onSeamCellChanged(serverLevel, pos, newState)`.

Repro, obsidian aperture cell, no (b) code required:
1. Player places a rail at `S`. `Level.setBlock(S, NORTH_SOUTH)` → outer `LevelChunk.setBlockState`.
2. `:327` → `BaseRailBlock.onPlace:64-68` (`oldState` air ⇒ `:65` passes) → `updateState:70-77` → `updateDir:109-116` → `RailState.place`.
3. `place:333` issues a **nested** `Level.setBlock(S, EAST_WEST)` → nested `LevelChunk.setBlockState` → **inner** inject fires with `EAST_WEST` → mirror writes `D = EAST_WEST.rotate(R)`. Correct.
4. Stack unwinds. **Outer** inject fires with its argument `NORTH_SOUTH`; `applying` is back to `false` (`SeamMirror.java:243-245`); mirror writes `D = NORTH_SOUTH.rotate(R)`. **Wrong, and last.**

`D` cannot self-correct: `BaseRailBlock.onPlace:65` guards on `!oldState.is(state.getBlock())` and `D` is already a rail, and per §0.5 nothing else re-resolves a plain rail. This is a **pre-existing (a) defect** that would make leg B5's cross-side assertion fail deterministically, and would be misdiagnosed as a (b) bug. **It must be fixed before (b) is written.**

`Level.setBlock:234-236` (`oldState == null → return false`) and `:251` (`updateNeighborsAt`) are confirmed as the adjudication cites — but §7 T1 was arguing about the wrong method. T1 is restated in §7.

### 0.2 B-3 REFUTED — `removeSoftConnections` runs first and *does* re-read the far world

Lens A: *"`connectTo` never re-examines the far world. It knows nothing about `D+EAST`."*

Two source facts refute it.

1. `RailState.java:25-27` — the constructor derives `connections` from **the state it was handed**: `RailShape direction = state.getValue(this.block.getShapeProperty()); … this.updateConnections(direction);`. A proxy is constructed at `getRail:98/104/109` from the state H2 returned, i.e. the **far** rail's state rotated into the local frame. So a nether `SOUTH_EAST` at `D` arrives as a proxy whose `connections` are `{anchor.east(), anchor.south()}` — `anchor.east()` is the local-frame name for `D+EAST`. The far arm is present from birth.
2. `place:338` calls `neighbor.removeSoftConnections()` **before** `:340 neighbor.connectTo(this)`. `removeSoftConnections:79-88` re-runs `getRail` on each of the proxy's own connections, which routes through the shadow (`anchor.east()` → depth 1 → far; `anchor.south()` == `ownerCell` → depth 0 → source, via the walk-back). So the far arm is validated against the far world immediately before `connectTo`.

`connectTo:149-152`'s `hasConnection(north/south/west/east)` then reads that validated list, and `:163` `s && e && !n && !w → SOUTH_EAST` fires. **Leg B2's `D == SOUTH_EAST` is reachable.**

The true residue lens A found: `removeSoftConnections:83` writes `rail.pos` back into the slot and `connectTo:144` then adds the same `pos` again, producing a duplicate entry. **That is vanilla behaviour on any rail anywhere**, tolerated by `hasConnection:116-125`, and unchanged by (b). Not a (b) defect.

### 0.3 B-6a REFUTED — transform sign error

Lens A works planes `q = Z0+0.3` / `q' = Z1+0.7` and gets `raw = Z1+1`. The transform maps a point at signed distance `d` in *front* of `P` to a point at distance `d` *behind* `Q` (`Portal.getContentDirection:537-541`, `isReversePortal:1402`). Redoing it: `centre(S)+g = Z0−0.5`, `d = −0.8` (behind `P`) ⇒ `0.8` in **front** of `Q` ⇒ along `Q`'s normal `−Z` from `Z1+0.7` ⇒ `Z1−0.1` ⇒ **`raw = Z1−1`**. `dst = containing(Z1+0.7−0.25) = Z1`; `revDir = NORTH`; `dst.relative(NORTH) = Z1−1 = raw` ⇒ classified **COINCIDENT**, which is correct (`S`'s image is `(Z1, Z1+1]`, overlapping `D = [Z1, Z1+1)` almost entirely).

My sign convention is validated against the two cases both the adjudication and lens B independently derived: topology A `(Z0+0.5, Z1+0.5)` → `raw = Z1−1 = dst.relative(revDir)` ✔ coincident; topology B `(Z0, Z1)` → `raw = Z1−1 = dst` ✔ disjoint.

### 0.4 B-7 REFUTED as stated; its call-site observation is adopted

Lens A's two branches both describe geometries where removal is **correct**: a slope needs a *solid* support in the cell it climbs into (`BaseRailBlock.shouldBeRemoved:98-101`), and "far cell holds a rail" and "far cell is air" are both "no support". The intended geometry is a support cube at `D` and the continuing rail at `D.above()` — which (a) already admits (`AperturePassthroughLever.java:112-119`, the §4.1 whitelist: *"a support cube admitted only when the cell above it holds a whitelisted block"*).

What lens A got right and I adopt: `LevelChunk.java:318`
```java
if ((blockChanged || newBlock instanceof BaseRailBlock) && this.level instanceof ServerLevel serverLevel && ((flags & 1) != 0 || movedByPiston)) {
    oldState.affectNeighborsAfterRemoval(serverLevel, pos, movedByPiston);
}
```
The `newBlock instanceof BaseRailBlock` carve-out means **every** rail write, including shape-only ones, calls `affectNeighborsAfterRemoval`. The adjudication's §0.2 examined only the body. **§0.2's conclusion survives** (`RailBlock.java:24 super(false, …)` ⇒ `isStraight == false`; `affectNeighborsAfterRemoval:119-130` has only the `isSlope()` branch `:121-123` and the `isStraight` branch `:125-128`), so a **flat** rail still triggers nothing. But a **slope** at a seam gets `shouldBeRemoved`-checked on every shape write, which raises the importance of §3.3 being exactly right and is why leg **B12** exists.

### 0.5 Re-confirmed from the adjudication (spot-checked this session, both verifiers agree)

- **§0.1** `BaseRailBlock.java:87` `this.updateState(state, level, pos, block)` resolves to the **empty** `(BlockState, Level, BlockPos, Block)` overload at `:106-107`; `RailBlock.java:29-33` overrides it and only calls `updateDir` under `isSignalSource() && countPotentialConnections() == 3`. **All three original dispatch designs are inert. Nothing is added to `LevelChunk.setBlockState` for dispatch.**
- **§0.4** `crossDir = srcFacing.getOpposite()`; `SeamRegistry.lookupAcross:129-140` (`b.srcFacing() == dir`) is inverted and has **zero consumers** (grep confirms hits only at `SeamRegistry.java:63, 129, 135, 143`). Replacing it is free.
- **§0.7** `SeamRegistry.sectionHasSeam:105-109` is `isEmpty()` + one `LongOpenHashSet.contains`. `SeamBounds` rejected.
- `SeamlessMixinConfigPlugin` gates only `mixinClassName.startsWith("qouteall.")` ⇒ every new mixin needs its own runtime `isEntityPortals()` guard.
- `common/build.gradle:142` `compileOnly io.github.llamalad7:mixinextras-common:0.5.3`, loader-provided at runtime.

### 0.6 ★ THE FINDING NEITHER VERIFIER MADE — the classifier is not a mixed-phase detector

The adjudication's §0.8/§1 rest on: *"`crossPos` matching neither `destPos` nor `destPos.relative(n_Q)` **is** the mixed-phase signature. One test, three jobs."*

Counter-example, worked with the sign convention validated in §0.3. `P` boundary-phase (`q = Z0`, normal `+Z`), `Q` mid-block (`q' = Z1+0.5`, normal `−Z`). `q + q' = Z0+Z1+0.5 ∉ ℤ` ⇒ the lattice is **not** preserved (image `z' = (q+q') − z` maps cell `[Z0, Z0+1)` onto `(Z1−0.5, Z1+0.5]`, straddling two cells).

- `S = containing(Z0+0.25) = Z0`.
- `D = containing(Z1+0.5−0.25) = Z1`.
- `centre(S)+g = Z0−0.5`, `d = −0.5` ⇒ `0.5` in front of `Q` ⇒ `Z1+0.5−0.5 = Z1` ⇒ `raw = Z1`.
- `raw == dst` ⇒ **matches candidate 1** ⇒ `seamContinuous = true`, phase `DISJOINT`.

**The mixed-phase pair is accepted, not refused.** Leg B8 as written would fail. And "that fail-closed default is the actual safety property" — the adjudication's own answer to *the one thing most likely to be wrong* — does not hold.

**The fix is one method and it is a proof, not an enumeration.** `SeamMap.isMirrorable:193-207` already forces the **linear** part of the transform to be a signed axis permutation (scaling `:194-196`, normal `:197-199`, all three axes `:204-206`). A signed axis permutation maps ℤ³ onto ℤ³. Therefore the map preserves the block lattice **iff its translation part is integral**, and that is testable by mapping one lattice corner:

```java
/**
 * Whether this portal maps the BLOCK LATTICE onto itself — the test isMirrorable does NOT make.
 *
 * <p>isMirrorable (:193-207) constrains only the LINEAR part: scale 1, signed-unit normal, every
 * axis to a signed axis. A signed axis permutation already carries Z^3 onto Z^3, so the lattice
 * is preserved iff the TRANSLATION is integral — and one lattice corner settles that for all of
 * Z^3. Missing this term is why two planes at (Z0, Z1+0.5) — a genuinely unmappable geometry —
 * survived the two-candidate crossPos classifier: image(z) = (q+q') - z straddles two cells and
 * the classifier's first candidate matched anyway.
 */
public static boolean latticeAligned(Portal portal, BlockPos anyLocalCell) {
    Vec3 image = portal.transformPoint(Vec3.atLowerCornerOf(anyLocalCell));   // Vec3.java:45, Portal.java:508
    return nearInt(image.x) && nearInt(image.y) && nearInt(image.z);
}
private static boolean nearInt(double v) { return Math.abs(v - Math.round(v)) < 1.0e-4; }
```
Verified against all four geometries: `(Z0+0.5, Z1+0.5)` corner `Z0` → `d = −0.5` → `Z1+1` ✔; `(Z0, Z1)` → `d = 0` → `Z1` ✔; `(Z0+0.3, Z1+0.7)` → `d = −0.3` → `Z1+1` ✔; `(Z0, Z1+0.5)` → `d = 0` → `Z1+0.5` ✘ **refused**. Tolerance `1e-4` because `transformPoint` runs through `DQuaternion`; `1e-6` is too tight for a rotated portal.

This one method replaces the adjudication's entire "enumerate the degenerate geometries and hope the list is exhaustive" posture — which was its own nominated top risk — with a closed proof.

---

## 1. THE MODEL, BOTH TOPOLOGIES

Notation. `P` source portal, normal `N` (horizontal, enforced); `f = srcFacing = Direction.getApproximateNearest(N)` (`SeamRegistry.java:167-168`); **`g = f.getOpposite()` = the crossing direction**; `R = stateRotation = SeamMap.blockRotationOf(P)` (`SeamMap.java:238-259`), source→destination (fixed by `SeamMirror.java:310`); `Q` reverse portal, `n_Q = contentDirection(P)`; `S = SeamMap.seamCell(P, col)` (`:82-84`); `D = SeamRegistry.resolveDestCell(…)` (`:299-308`) — the cell `Q` itself claims.

### TOPOLOGY B — boundary-phase plane. The requirement.

```
      SOURCE DIM                    ‖ plane at Z0 ‖              DEST DIM
                                    ‖             ‖
 … [S+2f] [S+1f] [   S   ] ─────────╫─────────────╫───────── [   D   ] [D+n_Q] [D+2n_Q] …
     Z0+2   Z0+1    Z0              ‖             ‖             Z1-1     Z1-2     Z1-3
                    └ far face flush with plane ──┘ far face flush ┘
```
* `S` and `D` are **two distinct blocks in two dimensions**. Nothing is coincident, nothing is mirrored.
* **The neighbour pair is `(S, D)`.** `crossPos = D`. `localAnchor = S.relative(g) = S+g` is an ordinary source cell whose *contents are ignored* only when empty (R1′, §2.5).
* One binding per cell. A flipped twin, if one exists, binds `S.relative(g)` — a **different** cell (verified: `containing(Z0−0.25) = Z0−1`). No fork.
* Verified symmetric: running the classifier from `Q` gives `S_Q = D`, `crossPos_Q = S`, `destPos_Q = S`. **Both sides independently derive each other; the topology closes.**

### TOPOLOGY A — mid-block plane (every obsidian frame).

```
      SOURCE DIM                             DEST DIM
                       ‖ plane bisects S ‖
 … [S+2f] [S+1f] ──────╫─ [ S  ≡  D ] ─╫────── [D+n_Q] [D+2n_Q] …
    Z0+2    Z0+1       ‖   Z0    Z1    ‖        Z1-1     Z1-2
                       ‖  ONE SLOT,    ‖
                       ‖  two storage  ‖
                       ‖  cells, each  ‖
                       ‖  rendering    ‖
                       ‖  its near half‖
```
* `S` **is** `D` — one physical slot, kept byte-identical by (a)'s mirror.
* **Every cell is co-located with a cell in the other dimension**, not just the aperture: `S+f ≡ D−n_Q` (verified: `centre(S+f)` is `+1.0` in front of `P`, image is `1.0` behind `Q` = `Z1+1.5` = cell `D−n_Q`), and `S+g ≡ D+n_Q`. Only the aperture pair `(S,D)` is mirrored; the others are two independent worlds occupying one visual place. **This is the fact that decides §2.5 and §7.**
* `S` carries **two bindings** with opposite `f` (front portal and its `createFlippedPortal` twin, `PortalManipulation.java:132`), same `destPos = D`, therefore opposite `crossPos`:

| slot at `S` | binding | local cell | cross cell | resolved by R1′ |
|---|---|---|---|---|
| `g` | front `P` | `S+g` (OW, other approach) | `D+n_Q` (nether approach) | **local if occupied, else cross** |
| `f` | flipped `P'` | `S+f` (OW approach) | `D−n_Q` (nether other approach) | **local if occupied, else cross** |
| lateral | — | aperture cell (mirrored) or frame | none | local, always |

Both bindings are **real**: the obsidian portal is two-faced and a cart entering from either side exits at the corresponding nether face. Neither is "the shadow of the other". This is exactly why `topology-b-first`'s position-only `SeamPlane.shadows()` was unsound and why the anchored, per-binding form is mandatory.

### THE TOPOLOGY DISCRIMINATOR — derived, two candidates, plus the lattice proof

```
latticeAligned(P, S)  must hold                                    (§0.6 — HARD PRECONDITION)
crossPos = the cell containing transformPoint( centre(S) + unit(g) )
phase    = COINCIDENT if crossPos == destPos.relative(revDir)
         | DISJOINT   if crossPos == destPos
         | UNKNOWN    otherwise                                    (never a guess; see §2.3)
revDir   = stateRotation.rotate(crossDir)      // = dir(n_Q), needs no live reverse portal
```
Worked, `N = +Z` (so `f = SOUTH`, `g = NORTH`), `R = NONE`:
* A: `centre(S)+g = Z0−0.5`, `d = −1.0` ⇒ image `Z1+0.5 − 1.0 = Z1−0.5` ⇒ cell `Z1−1`; `destPos = Z1`; `destPos.relative(NORTH) = Z1−1` ⇒ **COINCIDENT** ✔
* B: `d = −0.5` ⇒ image `Z1−0.5` ⇒ cell `Z1−1`; `destPos = Z1−1` ⇒ **DISJOINT** ✔

**Mixed phase is refused by `latticeAligned`, not by the candidate test** (§0.6). `topology-b-first`'s `snap` vector remains rejected: it converts a geometry with no answer into a plausible wrong one and re-opens the split-brain `resolveDestCell:282-297` was written to close.

---

## 2. THE SHARED CROSS-DIMENSION NEIGHBOUR PRIMITIVE

This is the thing (c) redstone and (d) minecarts consume. **Nothing in it is rail-specific** — lens B §6's two leaks are both closed here.

### 2.1 The headline invariant

Every `BlockPos` inside `RailState` — `this.pos:14`, `connections:18`, `getRail`'s `testPos:95-108`, `place`'s `north/south/west/east:219-222`, `removeSoftConnections`'s `rail.pos:83` — stays a **local coordinate in the querying level**. The other dimension is unfolded into local coordinates around a shadow anchor; translation happens only at the `getBlockState` / `isRail` / `setBlock` boundary.

`hasConnection`'s X/Z-only equality (`RailState.java:116-125`) is therefore **never confronted**. Lens B verified this independently by tracing the topology-B proxy and confirmed no cross-level comparison arises. The Y-blindness is deliberate and preserved: `updateConnections` stores ascending targets as `pos.east().above()` (`:47,50,54,59`) while `getRail` finds the rail at `pos`, `pos.above()` or `pos.below()` (`:94-110`); comparing X/Z only is how those reconcile. `unified-neighbour-api`'s `SeamCursor` rewrite is not needed and is rejected.

### 2.2 NEW FILE — `common/src/main/java/com/warwa/seamlessportals/passthrough/SeamShadow.java`

```java
package com.warwa.seamlessportals.passthrough;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A LOCAL SHADOW COORDINATE SYSTEM for one seam crossing.
 *
 * <p>Vanilla single-level logic is handed LOCAL positions around {@code ownerCell}; this record
 * translates them into the destination level behind its back. That is how
 * {@code RailState.hasConnection}'s X/Z-only equality (RailState.java:116-125) is satisfied without
 * touching it: both sides of every comparison stay source-dimension coordinates.
 *
 * <p>NOTHING HERE IS RAIL-SPECIFIC. (c) SignalGetter/RedStoneWireBlock and (d) OldMinecartBehavior
 * consume it unchanged. In particular there is no "which side wins" policy in this class — that is
 * the CALLER's decision (see SeamShadowBridge and the R1' note in the spec's §2.5), because rails
 * want local-first while redstone needs an unconditional two-sided view.
 */
public record SeamShadow(
    ServerLevel sourceLevel,
    ServerLevel farLevel,
    BlockPos ownerCell,        // the bound seam cell the query originates from
    Direction crossDir,        // g, in SOURCE coordinates
    BlockPos localAnchor,      // = ownerCell.relative(crossDir)
    BlockPos farAnchor,        // = binding.crossPos()
    Rotation srcToDst,
    Rotation dstToSrc
) {
    /** How far past the plane a local position lies. <= 0 => still the SOURCE world. */
    public int depth(BlockPos localPos) {
        Vec3i u = crossDir.getUnitVec3i();                       // Direction.java:375
        return (localPos.getX() - ownerCell.getX()) * u.getX()
             + (localPos.getY() - ownerCell.getY()) * u.getY()
             + (localPos.getZ() - ownerCell.getZ()) * u.getZ();
    }

    /**
     * THE MAP. Rotation about +Y only. Derived from Rotation.rotate(Direction) (Rotation.java:90)
     * composed with Direction: CLOCKWISE_90 carries EAST(+X) to SOUTH(+Z) and SOUTH(+Z) to WEST(-X),
     * which is exactly the convention SeamMap.java:257 reads out of the game rather than assuming.
     */
    public BlockPos toFar(BlockPos localPos) {
        int dx = localPos.getX() - localAnchor.getX();
        int dy = localPos.getY() - localAnchor.getY();
        int dz = localPos.getZ() - localAnchor.getZ();
        int rx, rz;
        switch (srcToDst) {
            case CLOCKWISE_90        -> { rx = -dz; rz =  dx; }
            case CLOCKWISE_180       -> { rx = -dx; rz = -dz; }
            case COUNTERCLOCKWISE_90 -> { rx =  dz; rz = -dx; }
            default                  -> { rx =  dx; rz =  dz; }
        }
        return farAnchor.offset(rx, dy, rz);
    }

    /** True when the far counterpart is safe to touch WITHOUT a blocking load or a bounds fault. */
    public boolean farResident(BlockPos localPos) {
        BlockPos t = toFar(localPos);
        return farLevel.isInsideBuildHeight(t)          // LevelHeightAccessor.java:27
            && farLevel.hasChunkAt(t);                  // LevelReader.java:183
    }

    /**
     * THE WALK-BACK. A probe that has not crossed reads the SOURCE world, unrotated.
     *
     * <p>Required, not tidy: a proxy at the anchor that probes BACK toward the seam
     * ({@code anchor.relative(f) == ownerCell}) would otherwise map to {@code farAnchor - n_Q} —
     * a destination cell behind the far plane, not the source rail. Three multiply-adds.
     *
     * <p>Cold or out-of-bounds far chunk reads as AIR rather than force-loading. That degrades to
     * exactly the pre-(b) vanilla answer, and is retried by SeamRailContinuity's queue (spec §2.6);
     * SeamMirror force-loads (SeamMirror.java:264-278) because PLACEMENT is a rare player-driven
     * act, and rail resolution is neither.
     */
    public BlockState readLocal(BlockPos localPos) {
        if (depth(localPos) <= 0) return sourceLevel.getBlockState(localPos);
        if (!farResident(localPos)) { SeamRailContinuity.declinedCold(); return Blocks.AIR.defaultBlockState(); }
        return farLevel.getBlockState(toFar(localPos)).rotate(dstToSrc);
    }

    public boolean writeLocal(BlockPos localPos, BlockState localState, int flags) {
        if (depth(localPos) <= 0) return sourceLevel.setBlock(localPos, localState, flags);
        if (!farResident(localPos)) { SeamRailContinuity.declinedCold(); return false; }
        if (!SeamRailContinuity.enterWrite()) return false;     // depth cap + per-tick budget
        try {
            return farLevel.setBlock(toFar(localPos), localState.rotate(srcToDst), flags);
        } finally {
            SeamRailContinuity.exitWrite();
        }
    }
}
```

**Three changes from the adjudication, all verifier-driven:**
* `farResident` is checked **per call**, not once for the shadow-creating query (lens A B-4b: `Level.setBlock:231 this.getChunkAt(pos)` is a blocking full-chunk load, and children probe `anchor.relative(crossDir)`, `.above()`, `.below()` and laterals, which can map into a *different* far chunk). `isInsideBuildHeight` also closes lens A's silent `Level.setBlock:223-225` no-op.
* `enterWrite`/`exitWrite` is a real `try/finally` — lens A B-4a proved the adjudication's `MAX_DEPTH` counter was never incremented and `depthCapTrips` was structurally always 0.
* No `ShadowPolicy`. Lens B §6: baking rail semantics into the shared primitive gives (c) two incompatible readings of one entry point.

**Rotation round-trip is exact.** `BlockState.rotate` reaches `RailBlock.rotate:41-45` (verified: touches only `SHAPE`, `WATERLOGGED` passes through) → `BaseRailBlock.rotate(RailShape, Rotation):144-226`. Hand-checked as a group action: `CW90(NORTH_EAST)=SOUTH_EAST`, `CCW90(SOUTH_EAST)=NORTH_EAST`; `CW90(ASCENDING_EAST)=ASCENDING_SOUTH`, `CCW90(ASCENDING_SOUTH)=ASCENDING_EAST`. `BlockState`s are interned, so `place:332`'s `!=` guard terminates. Leg **B7** asserts it directly.

### 2.3 NEW FILE — `SeamShadowBridge.java` — the entry point

```java
/** null = no seam crossing here; the caller falls through to vanilla, unchanged. */
@Nullable
public static SeamShadow shadowFor(Level level,
                                   @Nullable SeamRegistry.SeamCell owner,
                                   BlockPos ownerCell,
                                   BlockPos queryPos,
                                   int yWindow) {                 // rails pass 1; (d) will pass more
    if (owner == null) return null;                                            // the fold, §6
    if (AperturePassthroughLever.DISABLED) return null;                        // ← lens B §9a
    if (AperturePassthroughLever.DISABLE_SEAM_SHADOW) return null;
    if (!SeamlessPortalsConfig.isEntityPortals()) return null;
    if (!(level instanceof ServerLevel src)) return null;
    MinecraftServer server = src.getServer();
    if (server == null || !server.isSameThread()) return null;

    for (SeamRegistry.SeamBinding b : owner.bindings()) {
        if (!b.seamContinuous()) continue;
        ServerLevel far = server.getLevel(b.destDim());
        if (far == null) continue;
        BlockPos anchor = ownerCell.relative(b.crossDir());
        if (queryPos.getY() < anchor.getY() - yWindow || queryPos.getY() > anchor.getY() + yWindow) continue;
        SeamShadow s = new SeamShadow(src, far, ownerCell, b.crossDir(), anchor, b.crossPos(),
                                      b.stateRotation(), inverse(b.stateRotation()));
        if (s.depth(queryPos) <= 0) continue;      // not past the plane => not a window
        SeamRailContinuity.crossRead();
        return s;
    }
    return null;
}

/** CLOCKWISE_90 <-> COUNTERCLOCKWISE_90; NONE and CLOCKWISE_180 are self-inverse. Rotation.java:38. */
public static Rotation inverse(Rotation r) {
    return switch (r) {
        case CLOCKWISE_90        -> Rotation.COUNTERCLOCKWISE_90;
        case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
        default                  -> r;
    };
}
```

**Semantics, one sentence:** *"the logic is standing at `ownerCell` and is about to look at `queryPos`; is `queryPos` a window onto another dimension?"* The topology difference lives entirely inside `b.crossPos()` and is invisible to every caller. No policy, no content test, `yWindow` parameterised.

### 2.4 NEW FILE — `SeamRailContinuity.java` — counters, budget, reseed, retry

```java
public final class SeamRailContinuity {
    private static final int MAX_DEPTH = 4;
    private static final int MAX_CROSS_WRITES_PER_TICK = 512;
    private static int depth = 0;                    // server-thread confined (shadowFor asserts it)
    private static int writesThisTick = 0;
    private static long crossReads, crossHits, crossWrites, declinedCold,
                        latticeRefusals, ambiguousReverse, depthCapTrips, budgetTrips,
                        reseeds, retriesQueued, retriesServed;

    static boolean enterWrite() {
        if (depth >= MAX_DEPTH)                       { depthCapTrips++; return false; }
        if (writesThisTick >= MAX_CROSS_WRITES_PER_TICK) { budgetTrips++;  return false; }
        depth++; writesThisTick++; crossWrites++; return true;
    }
    static void exitWrite() { depth--; }
    static void declinedCold() { declinedCold++; }
    static void crossRead()    { crossReads++; }
    public static void onServerTickEnd() { writesThisTick = 0; depth = 0; drainRetries(); }
    public static String counters() { /* every field, SeamMirror.counters() style */ }
}
```
`onServerTickEnd()` is called from the existing `END_SERVER_TICK` handler at `AperturePassthroughInit.java:71-78`, beside `SeamJournal.drain`.

### 2.5 ★ R1 IS INVERTED — **R1′ LOCAL-FIRST, CROSS-FALLBACK**

> **RULE R1′.** For a slot whose step crosses a seam, the **local** cell is consulted first; the **cross** cell is used only when the local cell holds no rail.

**Both verifiers, independently, refuted the adjudication's R1 (cross-first).** Lens A B-10: `D−n_Q` is ordinary nether terrain one block behind the nether portal, commonly built on — *"neither hidden nor rare"*. Lens B §5: it is precisely where nether track approaching the portal from the other side sits (the flipped nether portal's own approach cell), and `hasNeighborRail:208-215` returns `neighbor.canConnectTo(this)`, so a far rail already saturated in a different direction (`canConnectTo:139-141`) makes `S` conclude it has no neighbour on a side where the player can plainly see one. **Unanimous ⇒ the fix goes in.**

The adjudication's own §1 table already derived the right answer per slot (`g` → cross, `f` → local) and then chose a rule that gets `f` wrong on the assumption that hidden cells are air. §1 above shows that in topology A **nothing is hidden** — both `S+f` and `S+g` are visible OW approach cells for their respective portal faces, and both `D±n_Q` are visible nether approach cells. So a visibility rule cannot discriminate, and a content rule must break the tie the safe way.

R1′ is that way, and it is strictly better in every enumerated configuration:

| geometry | R1 (cross-first) | **R1′ (local-first)** |
|---|---|---|
| topology B, requirement (local empty, far rail) | cross ✔ | cross ✔ (identical) |
| topology A, track ends at portal, nether continues | cross ✔ | local empty ⇒ cross ✔ (identical) |
| topology A, OW approach at `S+f`, unrelated nether track at `D−n_Q` | **cross ✘ — misread, and a destructive `connectTo` write onto the unrelated nether rail** | local ✔, cross never consulted |
| all-local geometry anywhere | may divert | **provably identical to vanilla** |

R1′ makes (b) **purely additive**: it can only add a connection vanilla would leave absent, never override one vanilla would make. Leg **B10** becomes a theorem rather than a hope, and the "cross write lands on an unrelated far rail" class is closed structurally.

**One further invariant that falls out and is load-bearing everywhere below:** `connectTo:204-205` and `place:331-333` only ever do `this.state.setValue(shapeProperty, shape)` on `this.state`, and a proxy's `this.state` is the far rail's own state (from `getRail:98` via H2). **(b) therefore only ever changes the SHAPE property of an already-existing far rail. It never creates a block, never removes one, and never touches a non-rail cell.** This answers lens A's "`writeLocal` bypasses `mayPlace`" outright — `mayPlace` governs placement and (b) never places — and it is what makes `onPlace` non-re-entrant in §7.

### 2.6 Cold-far-chunk retry (lens B §4)

Lens B is right that declining is correctness-safe at the instant and permanently wrong afterwards: `AperturePassthroughInit:89-93` suppresses re-bind behind a geometry fingerprint, so a portal that never moves never re-binds and the §3.4 reseed never fires again; and in topology B the mirror is off, so nothing rewrites the shape later. It is also right that `SeamMirror` deliberately force-loads in **both** the veto (`:142`) and the driver (`:265`) precisely because a veto/driver disagreement about loading was a recorded correctness bug (`:255-261`).

**Adopted, without overloading `SeamJournal` (which is a pending-*clear* journal, `applyToDestination:272`):** `SeamRailContinuity` keeps a bounded `ArrayDeque<GlobalPos> RETRY` (cap 256, dropping oldest with a probe warning). `SeamShadow.readLocal`/`writeLocal` enqueue `(sourceLevel.dimension(), ownerCell)` on a cold decline. `drainRetries()`, called from `onServerTickEnd`, pops entries whose far chunk is now resident and re-runs `reresolve` (§3.4). Counters `retriesQueued` / `retriesServed`. Leg **B14** asserts the self-heal.

---

## 3. FILE-BY-FILE CHANGE LIST

**IP-CORE FILES EDITED: NONE.** IP core is `common/src/main/java/qouteall/imm_ptl/core/**` and `common/src/main/java/qouteall/q_misc_util/**`; nothing in this spec touches either. **(b) records NO new IP deviation.** Every file below is `com.warwa.seamlessportals.*`, a mixin onto a *vanilla* class, or a build/resource file. Every new mixin handler opens with `SeamlessPortalsConfig.isEntityPortals()` because the config plugin gates only `qouteall.*`.

### 3.1 EDIT — `common/src/main/java/com/warwa/seamlessportals/mixin/LevelChunkSetBlockStateMixin.java` ★ **DO THIS FIRST**

**Fixes B-1.** An (a) repair that (b) cannot be tested without.

Add the shadow beside the existing `@Shadow @Final Level level` at `:79-80`:
```java
@Shadow public abstract BlockState getBlockState(BlockPos pos);
```
In `seamlessportals$driveSeamMirror` (`:98-124`), replace lines `:106-107` and the mirror call at `:113`:

```java
        BlockState oldState = cir.getReturnValue();
        if (oldState == null) return;
        // B-1 FIX. onPlace runs INSIDE this method (LevelChunk.java:326-328), so a block whose
        // placement logic rewrites its own cell — every rail, via BaseRailBlock.onPlace:64 ->
        // updateDir:109 -> RailState.place:333 — issues a NESTED setBlockState that mirrors the
        // RESOLVED state, and then THIS frame returns and mirrors its stale `newState` argument on
        // top of it. The section already holds the final state; read it instead of trusting the
        // parameter. Idempotent when nothing nested: Level.setBlock:234-236 returns false on an
        // unchanged destination, so the redundant second mirror costs nothing.
        BlockState settled = this.getBlockState(pos);
        if (oldState == settled) return;
        ...
        if (SeamRegistry.sectionHasSeam(serverLevel, pos)) {
            SeamMirror.onSeamCellChanged(serverLevel, pos, settled);
        }
        if (SeamFrameLink.hasAny(serverLevel)) {
            SeamMirror.onFrameCellChanged(serverLevel, pos, settled);
        }
```
Levered by `DISABLE_SEAM_SETTLED_STATE` so the defect can be **demonstrated**, not merely asserted (leg **B13**).

### 3.2 NEW — `common/src/main/java/com/warwa/seamlessportals/mixin/passthrough/MixinRailStateSeam.java`

Registered in `common/src/main/resources/seamlessportals-common.mixins.json`, in the `"mixins"` array immediately after `"passthrough.MixinBlockItemCanPlace"` (verified: that array begins `"passthrough.SeamIndexHolderMixin", "passthrough.MixinBlockItemCanPlace", "ChunkMapResendSuppressMixin", …`).

**Every wrap is split per target method with an exact `require` and `allow`** — lens B §9b is right that `require` is compared against the *total* across targets, so an 11-site multi-target wrap can silently match one site and still load. Site counts verified line-by-line in `RailState.java`.

```java
@Mixin(RailState.class)
public abstract class MixinRailStateSeam implements SeamShadowHolder {
    @Shadow @Final private Level level;
    @Shadow @Final private BlockPos pos;

    @Unique private SeamShadow seamlessportals$shadow;              // non-null => THIS RailState is a proxy
    @Unique private SeamRegistry.SeamCell seamlessportals$owner;    // cached ONCE — the hot-path fold
    @Unique private boolean seamlessportals$armed;

    @Override public BlockPos seamlessportals$pos()          { return this.pos; }
    @Override public SeamShadow seamlessportals$shadow()     { return this.seamlessportals$shadow; }
    @Override public void seamlessportals$setShadow(SeamShadow s) { this.seamlessportals$shadow = s; }

    // THE FOLD: one LongOpenHashSet.contains per RailState construction, then a reference-null
    // check per probe. RailState.java:20-28.
    @Inject(method = "<init>", at = @At("TAIL"), require = 1, allow = 1)
    private void seamlessportals$cacheOwner(Level lvl, BlockPos p, BlockState st, CallbackInfo ci) {
        this.seamlessportals$armed = !AperturePassthroughLever.DISABLED
            && !AperturePassthroughLever.DISABLE_SEAM_SHADOW
            && SeamlessPortalsConfig.isEntityPortals()
            && lvl instanceof ServerLevel
            && SeamRegistry.sectionHasSeam(lvl, p);              // SeamRegistry.java:105-109
        this.seamlessportals$owner =
            this.seamlessportals$armed ? SeamRegistry.lookup(lvl, p) : null;   // :112-114
    }

    @Unique private SeamShadow seamlessportals$find(Level lvl, BlockPos q) {
        if (this.seamlessportals$shadow != null) return this.seamlessportals$shadow;  // inherit, never nest
        if (!this.seamlessportals$armed) return null;
        return SeamShadowBridge.shadowFor(lvl, this.seamlessportals$owner, this.pos, q, 1);
    }

    // ── H1a: hasRail. 3 sites, ALL on RailState.java:91. ──
    @WrapOperation(method = "hasRail",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/BaseRailBlock;"
               + "isRail(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"),
        require = 3, allow = 3)
    private boolean seamlessportals$isRailHasRail(Level l, BlockPos q, Operation<Boolean> op) {
        return seamlessportals$isRail(l, q, op);
    }
    // ── H1b: connectTo. 4 sites: :181, :185, :191, :195. ──
    @WrapOperation(method = "connectTo", at = @At(value = "INVOKE", target = "…isRail(…)Z"),
        require = 4, allow = 4)
    private boolean seamlessportals$isRailConnectTo(Level l, BlockPos q, Operation<Boolean> op) {
        return seamlessportals$isRail(l, q, op);
    }
    // ── H1c: place. 4 sites: :307, :311, :317, :321. ──
    @WrapOperation(method = "place", at = @At(value = "INVOKE", target = "…isRail(…)Z"),
        require = 4, allow = 4)
    private boolean seamlessportals$isRailPlace(Level l, BlockPos q, Operation<Boolean> op) {
        return seamlessportals$isRail(l, q, op);
    }

    @Unique private boolean seamlessportals$isRail(Level l, BlockPos q, Operation<Boolean> op) {
        SeamShadow s = this.seamlessportals$shadow;
        if (s != null) return BaseRailBlock.isRail(s.readLocal(q));   // PROXY: shadow IS the frame
        if (!this.seamlessportals$armed) return op.call(l, q);
        if (op.call(l, q)) return true;                                // ── R1' LOCAL FIRST ──
        SeamShadow far = seamlessportals$find(l, q);
        if (far == null) return false;
        boolean hit = BaseRailBlock.isRail(far.readLocal(q));          // BaseRailBlock.java:37
        if (hit) SeamRailContinuity.crossHit();
        return hit;
    }

    // ── H2: getRail's three state reads, :96, :102, :108. LOCAL-FIRST. ──
    @WrapOperation(method = "getRail",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
               + "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
        require = 3, allow = 3)
    private BlockState seamlessportals$readGetRail(Level l, BlockPos q, Operation<BlockState> op) {
        SeamShadow s = this.seamlessportals$shadow;
        if (s != null) return s.readLocal(q);
        if (!this.seamlessportals$armed) return op.call(l, q);
        BlockState local = op.call(l, q);
        if (BaseRailBlock.isRail(local)) return local;                 // ── R1' LOCAL FIRST ──
        SeamShadow far = seamlessportals$find(l, q);
        return far == null ? local : far.readLocal(q);
    }

    // ── H2b: place:332, the IDEMPOTENCE GUARD. NOT local-first: for a proxy this.pos is a shadow
    //         coordinate and must map through, or the guard compares two different frames. ──
    @WrapOperation(method = "place",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(…)…"),
        require = 1, allow = 1)
    private BlockState seamlessportals$readPlaceGuard(Level l, BlockPos q, Operation<BlockState> op) {
        SeamShadow s = this.seamlessportals$shadow;
        return s != null ? s.readLocal(q) : op.call(l, q);
    }

    // ── H3a: connectTo:205. H3b: place:333. A proxy's this.pos is a LOCAL coordinate past the
    //         plane; writing it locally would spawn a rail in the SOURCE dimension behind the portal. ──
    @WrapOperation(method = "connectTo",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock"
               + "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
        require = 1, allow = 1)
    private boolean seamlessportals$writeConnectTo(Level l, BlockPos p, BlockState st, int flags,
                                                   Operation<Boolean> op) { return seamlessportals$write(l,p,st,flags,op); }
    @WrapOperation(method = "place",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(…)Z"),
        require = 1, allow = 1)
    private boolean seamlessportals$writePlace(Level l, BlockPos p, BlockState st, int flags,
                                               Operation<Boolean> op) { return seamlessportals$write(l,p,st,flags,op); }

    @Unique private boolean seamlessportals$write(Level l, BlockPos p, BlockState st, int flags,
                                                  Operation<Boolean> op) {
        SeamShadow s = this.seamlessportals$shadow;
        if (s == null || AperturePassthroughLever.DISABLE_SEAM_RAIL_WRITE) return op.call(l, p, st, flags);
        return s.writeLocal(p, st, flags);
    }

    // ── THE SHADOW HANDOFF. NOT an @At("NEW") redirect. getRail returns at :98, :104 and :109;
    //    @At("RETURN") catches all three, and the child's constructor has already run
    //    updateConnections (:27), which is pure BlockPos arithmetic (:34-76) and needs no shadow.
    //    require/allow = 3 makes a mapping drift a LOAD-TIME failure. ──
    @Inject(method = "getRail", at = @At("RETURN"), require = 3, allow = 3)
    private void seamlessportals$stamp(BlockPos queried, CallbackInfoReturnable<RailState> cir) {
        RailState child = cir.getReturnValue();
        if (child == null) return;
        SeamShadowHolder h = (SeamShadowHolder) child;
        SeamShadow s = seamlessportals$find(this.level, h.seamlessportals$pos());
        if (s != null) h.seamlessportals$setShadow(s);
    }
}
```

Stamping against the **child's** `pos` (not `queried`) matters: `getRail` may have found the rail at `queried.above()` or `queried.below()` (`RailState.java:101, 107`).

**Why per-instance and not a thread-local or a position rule.** `RailState` is constructed on ordinary local cells from `BaseRailBlock:115`, `RailBlock:30` and `DetectorRailBlock`. A position rule would mistake a genuine local rail at a shadow coordinate — topology A's `S+g` is a perfectly buildable OW cell — for a proxy, and would misclassify the approach cell because of the flipped twin.

### 3.3 NEW — `mixin/passthrough/MixinBaseRailBlockSeamSlope.java`

`BaseRailBlock.shouldBeRemoved` is `private static (BlockPos pos, Level level, RailShape shape)` at `:92-104`; `pos.below()` at `:93`; the four ascending sites at `:98-101`. `Block.canSupportRigidBlock(BlockGetter, BlockPos)` is `Block.java:325`.

```java
@WrapOperation(method = "shouldBeRemoved",
    at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;canSupportRigidBlock"
           + "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"),
    require = 5, allow = 5)                       // :93 plus the four at :98-101
private static boolean seamlessportals$slopeSupport(
        BlockGetter getter, BlockPos query, Operation<Boolean> original,
        BlockPos pos, Level level, RailShape shape)      // trailing = target's own params, in order
{
    if (original.call(getter, query)) return true;        // ── R1' LOCAL FIRST ──
    if (AperturePassthroughLever.DISABLED
        || AperturePassthroughLever.DISABLE_SEAM_RAIL_SLOPE
        || !SeamlessPortalsConfig.isEntityPortals()
        || query.equals(pos.below())                      // :93 is vertical; crossDir is horizontal
        || !SeamRegistry.sectionHasSeam(level, pos)) {
        return false;
    }
    SeamShadow s = SeamShadowBridge.shadowFor(level, SeamRegistry.lookup(level, pos), pos, query, 1);
    return s != null && s.farResident(query)
        && Block.canSupportRigidBlock(s.farLevel(), s.toFar(query));   // Block.java:325
}
```
Purely additive by construction — it can only *find* support vanilla missed, never remove support vanilla found. This is what closes lens A's B-7 both ways: the far cell holding a rail (no support) and the far cell being air (no support) both correctly yield removal, exactly as they would locally; only a genuine solid support cube at the far cell rescues the slope, which is the geometry (a)'s ignition whitelist already admits (`AperturePassthroughLever.java:112-119`).

### 3.4 NEW — `SeamRailContinuity.reresolve` (the one dispatch that is actually needed)

Per §0.5 there is **no per-block-change dispatch** and nothing is added to `LevelChunk.setBlockState` for it. The two propagation directions are already self-driving: near rail placed → `onPlace:64-68` → `updateState:70-77` → `updateDir:109-116` → `place` → `:335-343` walks its own connections and calls `neighbor.connectTo(this)`, which for a proxy is `writeLocal` writing the far rail directly; far rail placed → symmetric; either broken → nothing, matching vanilla (`affectNeighborsAfterRemoval:119-130` with `isStraight == false`).

The genuine gap none of the three designs had: **a portal lit over an existing track, or broken across one.** No block changed, so no rail update fires.

```java
/** Re-run vanilla shape resolution at one cell. Every member used is public. */
public static void reresolve(ServerLevel lvl, BlockPos p) {
    if (AperturePassthroughLever.DISABLED
        || AperturePassthroughLever.DISABLE_SEAM_RAIL_RESEED) return;
    if (lvl.isClientSide()) return;                       // ← lens B B-8. See below.
    if (!lvl.hasChunkAt(p)) { queueRetry(lvl, p); return; }
    BlockState st = lvl.getBlockState(p);
    if (!BaseRailBlock.isRail(st)) return;                            // BaseRailBlock.java:37
    BaseRailBlock b = (BaseRailBlock) st.getBlock();
    Property<RailShape> prop = b.getShapeProperty();                  // public abstract, :142
    new RailState(lvl, p, st).place(lvl.hasNeighborSignal(p), false, st.getValue(prop));  // :20, :218
    reseeds++;
}
```

**Lens A B-8 CONFIRMED and fixed.** `AperturePassthroughInit.java:58` *and* `:63` both register `onPortalTick` → `SeamRegistry.bind`, on the **server and client** signals. `reresolve` deliberately bypasses `updateDir`, and `updateDir:110-112` is the *only* client-side guard in the rail system — `place` has none and writes at `:205`/`:333`. Without the `isClientSide()` guard the reseed would mutate rail shapes on the client with no server counterpart and no correcting packet: permanent visual desync at every portal geometry change. The guard is the fix; §7's "everything runs on the server thread" is restated to name this one exception explicitly.

Call sites: `SeamRegistry.bind` at an UNBOUND→BOUND cell transition, and `SeamRegistry.unbind` at BOUND→UNBOUND, for `(srcLevel, src)` and `(farLevel, binding.crossPos())`. **Capped** — lens A B-9's compounding case (a 21×21 custom portal ⇒ ~880 `place` calls in one tick, each with a possibly-cold far level) is bounded by the `isRail` early-out plus the per-tick write budget, and any overflow is queued via `queueRetry`.

### 3.5 EDIT — `SeamRegistry.java`

**(i) Record widened** (`:62-72`; the only construction site is `bind:181-182`):
```java
public record SeamBinding(
    Direction srcFacing, @Nullable ResourceKey<Level> destDim, @Nullable BlockPos destPos,
    Rotation stateRotation, UUID portalUuid,
    // ── NEW ──
    @Nullable BlockPos crossPos,   // the far cell that is THIS cell's neighbour across the seam
    SeamPhase phase,               // COINCIDENT | DISJOINT | UNKNOWN  (tri-state, see below)
    boolean seamContinuous         // passed every eligibility clause; (b)/(c)/(d) may cross
) {
    public boolean isMirrorable() { return destDim != null && destPos != null; }
    /** The direction in which a step from this cell CROSSES the seam, in SOURCE coordinates. */
    public Direction crossDir()     { return srcFacing.getOpposite(); }
    /** That same direction in DESTINATION coordinates. */
    public Direction destCrossDir() { return stateRotation.rotate(crossDir()); }
}
public enum SeamPhase { COINCIDENT, DISJOINT, UNKNOWN }
```

**★ `SeamPhase` is a TRI-STATE and that is lens B §3 / lens A B-5, unanimously found.** `SeamMap.isMirrorable:197` accepts a `(0,±1,0)` normal via `isSignedUnitAxis:210-215`, and `blockRotationOf:238-242` returns `Rotation.NONE` for a null rotation — so **a floor/ceiling portal is mirrored by (a) today**, and a boolean `coincident` defaulting to `false` whenever `railEligiblePortal` declines would silently switch (a) off for it. Overloading one boolean to mean "topology B", "not rail-eligible" and "could not tell" is the defect. `UNKNOWN` preserves stock (a) unconditionally.

**(ii) `lookupAcross:129-140` replaced** (zero consumers, §0.5): match on `b.crossDir() == dir && b.seamContinuous()`, return `GlobalPos.of(b.destDim(), b.crossPos())`.

**(iii) `findDestinationPortal:262-280` disambiguated — lens B §2, CONFIRMED fail-open.** It returns the **first** candidate within 0.5 of `destPos` (`:275`). On a bi-faced pair (`PortalManipulation.completeBiWayBiFacedPortal:183`, public API) two coincident opposite-normal portals qualify. I verified lens B's consequence: with the boundary-phase fixture and the wrong face `Q'` chosen, `dst' = Z1` while `raw = Z1−1 = dst'.relative(revDir)` ⇒ **classified COINCIDENT, `seamContinuous = true`, no refusal, `latticeRefusals` stays 0, and the probe logs a plausible topology-A line.** That re-enables the mirror on a boundary-phase seam and writes `destPos` one cell wrong — corrupting **(a)**, not just (b).

```java
    // Disambiguate by the SAME test IP itself uses for reverse portals (Portal.isReversePortal:1402,
    // BreakablePortalEntity.findReversePortals): the reverse portal is the one whose CONTENT
    // direction is this portal's normal. On a bi-faced pair the two coincident candidates have
    // opposite content directions, so exactly one passes — verified by hand: contentDirection(Q)
    // = normal(P) gives dot = +1, and the flipped face Q' gives -1 (createFlippedPortal:132 negates
    // axisW, which negates both the normal and the content direction).
    Portal match = null;
    for (Portal candidate : destLevel.getEntitiesOfClass(...)) {
        if (candidate.getOriginPos().distanceToSqr(destPos) >= 0.25) continue;
        if (portal.getNormal().dot(candidate.getContentDirection()) <= 0.9) continue;   // :526, :537
        if (match != null) { ambiguousReverse++; return null; }     // two claim it: refuse, don't guess
        match = candidate;
    }
    return match;
```

**(iv) `bind:177-190`** — after `resolveDestCell`:
```java
Direction crossDir = facing.getOpposite();
Direction revDir   = (rotation == null ? Rotation.NONE : rotation).rotate(crossDir);   // = dir(n_Q)
BlockPos crossPos = null;
SeamPhase phase = SeamPhase.UNKNOWN;
boolean continuous = false;

if (mirrorable && railEligiblePortal(portal, src)) {
    Vec3 stepped = Vec3.atCenterOf(src).add(Vec3.atLowerCornerOf(crossDir.getUnitVec3i()));
    if (portal.getDistanceToPlane(stepped) < -1.0e-6) {                     // Portal.java:1157
        BlockPos raw = BlockPos.containing(portal.transformPoint(stepped)); // Portal.java:508
        // ANCHOR ON destPos, NEVER ON raw. resolveDestCell (:282-308) exists precisely because a
        // raw transform drifted half a block and put the mirror one cell off what the far portal
        // claimed. Use raw ONLY for the binary question; take the coordinate from the authority.
        if      (raw.equals(dst))                  { crossPos = dst;                  phase = SeamPhase.DISJOINT;   continuous = true; }
        else if (raw.equals(dst.relative(revDir))) { crossPos = dst.relative(revDir); phase = SeamPhase.COINCIDENT; continuous = true; }
        else {
            SeamRailContinuity.latticeRefusal();
            LOGGER.warn("[RS-SEAM-SHADOW] crossPos {} is neither destPos {} nor destPos+{} — "
                      + "geometry not classifiable; seam NON-CONTINUOUS, phase UNKNOWN, (a) mirror "
                      + "left at stock behaviour (portal {})", raw, dst, revDir, portal.getUUID());
        }
    }
}

static boolean railEligiblePortal(Portal p, BlockPos anyCell) {
    if (!SeamMap.isMirrorable(p))            return false;   // SeamMap.java:193-207 — scale + linear part
    if (!SeamMap.latticeAligned(p, anyCell)) return false;   // ★ §0.6 — the TRANSLATION term
    if (SeamMap.blockRotationOf(p) == null)  return false;   // SeamMap.java:238-259 — not a yaw quarter-turn
    if (Math.abs(p.getNormal().y) > 1.0e-6)  return false;   // horizontal normal only, see §4
    Vec3 up = p.transformLocalVecNonScale(new Vec3(0, 1, 0)); // Portal.java:1267
    return SeamMap.isSignedUnitAxis(up) && up.y > 0.999;      // +Y must survive
}
```
`revDir = stateRotation.rotate(crossDir)` rather than `getApproximateNearest(reverse.getNormal())`: it needs no live reverse portal and is exactly `dir(T(−N)) = dir(contentDirection) = dir(n_Q)`. The probe logs both and asserts they agree.

**(v) `unbind:311-336` — lens A B-9, CONFIRMED.** `unbind` enumerates `SeamMap.enumerateColumns(portal)` on the **new** geometry, and `AperturePassthroughInit:95-98` calls `unbind` *after* the geometry has already moved. Old cells stay bound forever, `crossPos` and all — and with (b) present that becomes a live shadow onto a far position with no portal.
Fix: `bind` records the cells it bound in a per-portal snapshot on the `SeamIndexHolder` (`Map<UUID, long[]>`, alongside `seamlessportals$seamCells()`); `unbind` uses that snapshot when present and falls back to enumeration when absent (worlds saved before this change).

**(vi) `AperturePassthroughInit.java` — lens A B-6b, CONFIRMED sticky.** `fingerprintOf:127-139` does not record whether a reverse portal was found, so a first bind taken before the far portal ticks (`reverse == null` ⇒ `resolveDestCell:300-301` falls back to `mirrorCell`, the exact computation whose drift `:282-297` documents) is frozen in by `:92-93` and never re-evaluated. Fix: a `Set<UUID> UNRESOLVED_REVERSE` per side; `bind` adds the portal when `reverse == null` or `ambiguousReverse` fired; `onPortalTick` skips the fingerprint short-circuit for members of that set. Bounded by the number of half-open portals.

Also in `AperturePassthroughInit`: add `SeamRailContinuity.onServerTickEnd()` to the `END_SERVER_TICK` handler at `:71-78`.

### 3.6 EDIT — `SeamMirror.java` — the phase gate

Inside `mayPlace`'s binding loop (`:101-111`) and `onSeamCellChanged`'s (`:223-238`), immediately after the `isMirrorable()` check:
```java
if (binding.phase() == SeamRegistry.SeamPhase.DISJOINT
    && !AperturePassthroughLever.DISABLE_SEAM_PHASE_GATE) continue;
```
**Only `DISJOINT` — a positively-determined distinct pair — suppresses the mirror. `UNKNOWN` and `COINCIDENT` leave (a) exactly as shipped.**

Three independent reasons the gate is required, in order of force:
1. **It unblocks the requirement.** `mayPlace:105-110` **refuses** a placement at `S` when `D` is occupied. In topology B `D` is the far-side track the player is joining, so without the gate the player can *never* lay rail to a boundary-phase seam from both sides. That is the requirement, denied.
2. **It stops a duplicate.** Topology A's mirror makes two half-blocks read as one; topology B's makes two **whole** blocks, one either side of the plane. That is an (a) defect independent of rails.
3. **It stops an oscillator.** `S` and `D` are distinct track slots that must hold different shapes (`NORTH_EAST`/`SOUTH_EAST`, §4); mirror-locking them makes each side overwrite the other forever.

> **⚠ USER DECISION, flagged not taken unilaterally.** The gate is general (all block kinds), because reason 2 applies to every block. `REDSTONE_RECON.md` §0.5/§0.7 were written for topology A, and the user's own phrasing — *"NOT coincident and NOT already mirrored"* — states topology B is unmirrored. It is still a change to a user-approved (a) rule. `-PdisableSeamPhaseGate=true` restores stock (a) exactly. **No user-verified behaviour is lost either way: (a) was verified on obsidian frames, and every obsidian binding is `COINCIDENT`.**

### 3.7 Remaining edits

| file | change |
|---|---|
| `common/.../passthrough/SeamMap.java` | add `latticeAligned(Portal, BlockPos)` + `nearInt` (§0.6). Nothing existing changes. |
| `common/.../passthrough/SeamShadowHolder.java` | **NEW** interface: `BlockPos seamlessportals$pos(); SeamShadow seamlessportals$shadow(); void seamlessportals$setShadow(SeamShadow);` (lens B §6 — the adjudication showed the casts but never declared the members) |
| `common/.../passthrough/AperturePassthroughLever.java` | 7 fix levers + 2 probes (§7) |
| `common/src/main/resources/seamlessportals-common.mixins.json` | add `"passthrough.MixinRailStateSeam"`, `"passthrough.MixinBaseRailBlockSeamSlope"` after `"passthrough.MixinBlockItemCanPlace"` |
| `fabric/build.gradle` | 9 `-P` rows in the **client** block after `:144` (the `seamLedgerProbe` row) and 9 in the **crossingGametest** block after `:298`. Verified those are the exact last lever rows of each block; `server`/`clientSodium`/`clientGametest` legitimately carry none. |
| `fabric/.../gametest/CrossingSmoke.java` | legs B0–B15 (§9) |

---

## 4. SHAPE-RESOLUTION TABLE

Nothing in `RailState.place:218-347` changes. H1/H2/H3 make the four hard-coded probes at `:219-226` answer across the seam and vanilla's own priority table decides. `updateDir:110-112` returns early client-side, so **(b) is inherently server-only** — no prediction, no desync class. Approach from the south, crossing northward, `R = NONE` unless stated.

| configuration | topology | expected shape, source side | expected shape, dest side | mechanism |
|---|---|---|---|---|
| **straight through** | A (`crossPos = D+n_Q`) | `NORTH_SOUTH` | `NORTH_SOUTH` (rotated) | slot `f`: local `S+f` rail (R1′) → `s`. slot `g`: local `S+g` empty → cross `D+n_Q` rail → `n`. `place:230-232`. `D` computes the same unordered pair in its own frame from the co-located slots (§7 T2′). |
| **straight through** | B (`crossPos = D`) | `NORTH_SOUTH` | `NORTH_SOUTH` | slot `f`: local `S+f`. slot `g`: local `S+g` empty → cross `D`. `D` symmetric: cross → `S`, local → `D+n_Q`. |
| **curve at the seam — THE DISCRIMINATOR** | A | `NORTH_EAST` | rotated image of the same two slots | `n` from the cross cell, `e` from the lateral aperture cell — **already kept identical by (a)'s mirror** (`applyToDestination:310-311`), hence symmetric. `place:255-257`. Composite reads as one curve. |
| **curve at the seam** | B | **`NORTH_EAST`** | **`SOUTH_EAST`** | `S`: `e` local, `n` cross. `D`: `e` local, `s` cross (→ `S`). **Two different, legitimate shapes**, and no mirror forces them to agree (§3.6). Without (b) both are `EAST_WEST` (`:234-236`). |
| **ascending into the plane** | both | `ASCENDING_NORTH` | — | `place:306-313` probes `isRail(level, north.above())` → H1c → shadow at `(crossPos).above()`. Support bridged by §3.3. Requires a solid cube at `crossPos` — (a)'s ignition whitelist admits it. |
| **T-junction at the seam** | both | `defaultShape` (`place:262`) | same | `northOrSouth && westOrEast`, no curve arm matches. **Deliberately vanilla-identical.** |
| **one side only** | both | pure vanilla | pure vanilla | no far rail ⇒ R1′'s cross branch returns false ⇒ nothing changes. |
| **rotated portal (`R = CW90`)** | both | shapes *and directions* both carried | | `readLocal` applies `dstToSrc` before `RailState` sees the state; `writeLocal` applies `srcToDst` on the way out; `RailShape` travels as a whole `BlockState` through `BaseRailBlock.rotate:144-226`. |
| **both coincident faces firing** | A | one unordered `{local, cross}` pair per slot | same pair, from the other side | `S`'s two bindings have opposite `crossDir` ⇒ distinct `localAnchor` (`S∓N`) ⇒ `shadowFor` matches at most one per query. Under R1′ each slot resolves to local-or-cross of the **same physical place** (§1, co-location). |
| **both faces** | B | no interaction | | the two faces bind **different cells**; `S` has one crossing direction. |
| **powered / detector / activator** | both | shape identical to a plain rail | | all funnel through `updateDir:109-116` → `RailState`. **Power does NOT cross**: `PoweredRailBlock.findPoweredRailSignal` walks with raw `BlockPos` in one `Level` and never touches `RailState`; `DetectorRailBlock.updatePowerToConnected` notifies local positions from `rail.getConnections()`. Both are (c). |

**Accepted divergence, documented not hidden.** On a *coincident but frameless* portal (a custom portal that happens to sit mid-block), a lateral neighbour outside the aperture rectangle exists on one side only, so `S` and `D` can settle on different shapes and the composite reads half-curve/half-straight. On an obsidian frame this cannot occur: an aperture cell's lateral neighbours are either other aperture cells (mirrored, symmetric) or frame obsidian (never a rail). `topology-b-first`'s explicit `sharedSlotLateral` union stays rejected — (a)'s mirror already guarantees the answer, and a second independently-computed path is how two systems come to disagree.

**Rotation — exactly five application points**, all with `R = SeamMap.blockRotationOf(P)` (`:238-259`), source→destination per `SeamMirror.java:310`; `Rotation.rotate(Direction)` is Y-invariant (`Rotation.java:90-93`) and `railEligiblePortal` requires `T(+Y) == +Y`, so `dy` passes through `toFar` untouched: (1) `SeamShadow.toFar`'s `(dx,dz)→(rx,rz)` switch; (2) `readLocal`'s `farState.rotate(dstToSrc)`; (3) `writeLocal`'s `localState.rotate(srcToDst)`; (4) `SeamBinding.destCrossDir()` / `revDir`; (5) `SeamShadowBridge.inverse` (`Rotation.getRotated:38`). **Position mapping deliberately does not compose `R` by hand** — `crossPos` comes from `Portal.transformPoint:508` and is anchored on the reverse-portal-authoritative `destPos`.

### `hasConnection`'s X/Z-only equality — never confronted, worked check

`RailState.java:116-125` compares X and Z only. Topology B, straight through: `S` is placed → `place:223` `hasNeighborRail(S.north())` → `getRail:94` reads across → returns proxy `R` with `R.pos = S.north()` (**local**) and `R.state` = `D`'s state rotated into the local frame. `R.updateConnections:37-40` fills `{S.north().north(), S.north().south()}` = `{S.north().north(), S}`. `R.connectsTo(this):112-113` → `R.hasConnection(S)` matches the second entry on X/Z. **True.** No arithmetic ever compares an overworld X against a nether X.

The one degenerate case is designed out: with a vertical portal normal `crossDir` would be `UP`/`DOWN`, the anchor would share `S`'s X and Z, and `getRail`'s three-Y-level probe (`:94-110`) would alias the shadow onto itself. `railEligiblePortal` refuses `|normal.y| > 1e-6`. Rails cannot run vertically anyway. **Note this refusal is now independent of the mirror gate** (§3.6): a vertical-normal portal is `phase = UNKNOWN`, so (a) keeps mirroring it and only (b) declines.

---

## 5. FAILURE-MODE TABLE

Every case lens A raised, plus lens B's.

| # | situation | behaviour | why safe |
|---|---|---|---|
| **F1** | rail placed at a seam; `onPlace` rewrites the cell inside the same `LevelChunk.setBlockState` (**B-1**) | mirror reads `this.getBlockState(pos)` at RETURN, not the argument | the section already holds the settled state (`LevelChunk:279`, `:322`); the redundant second mirror is a no-op (`Level.setBlock:234-236`). §3.1 |
| **F2** | mirror's write lands on air at `D` and runs a full `place` there under `applying == true` (**B-2**) | `D` re-resolves from its own co-located occupancy; its mirror-back to `S` is eaten by `SeamMirror:203-205` | under R1′ + topology-A co-location the two sides read the **same four physical slots** and agree (§7 T2′). Stable, not oscillating: `S=X`, `D=Y`, no further event. Residual divergence only when both dimensions hold rails at a co-located non-aperture cell with mismatched arms — probe-logged, leg **B7** asserts non-oscillation. |
| **F3** | proxy `connectTo` rewrites the far rail (**B-3**) | correct: `removeSoftConnections` at `place:338` re-validates the proxy's far arms first (`:79-88`), and the proxy's arms came from the far state at `RailState:25-27` | **REFUTED**, §0.2. Leg **B2** asserts `D == SOUTH_EAST`. |
| **F4** | redstone clock beside a 3-connection seam rail (**B-4**) → `RailBlock.updateState:29-33` re-resolves per edge | full `place` per edge, cross reads are hash-probe + resident `getBlockState`; cross writes are capped | per-tick `MAX_CROSS_WRITES_PER_TICK = 512` and `MAX_DEPTH = 4` in a real `try/finally` (§2.4). Over budget → the tick degrades a track, not the server; `budgetTrips` is asserted `0` by every leg. |
| **F5** | far cell is in an unloaded chunk, or outside the far dimension's build height | `readLocal` → AIR, `writeLocal` → `false`; `declinedCold++`; cell queued for retry | no blocking `getChunkAt` on the rail path (`Level.setBlock:231`); degrades to the exact pre-(b) vanilla answer; self-heals from `END_SERVER_TICK` (§2.6). Leg **B14**. |
| **F6** | floor/ceiling portal (vertical normal), or any geometry (b) declines | `phase = UNKNOWN` ⇒ **(a) mirrors it exactly as today**; (b) declines | tri-state (§3.5). `SeamMap.isMirrorable:197` accepts `(0,±1,0)`; a boolean `coincident` would have silently killed (a) for it. Leg **B15**. |
| **F7** | mixed sub-block phase, e.g. planes at `Z0` and `Z1+0.5` | `latticeAligned` fails ⇒ `seamContinuous = false`, `phase = UNKNOWN`, both sides byte-identical to vanilla, no exception | §0.6 — the corner test is a **proof** given `isMirrorable` has already forced the linear part to a signed axis permutation, not an enumeration of degenerate cases. Leg **B8**. |
| **F8** | general sub-block phase, e.g. `Z0+0.3` / `Z1+0.7` (`q+q' ∈ ℤ`) | **accepted**, classified `COINCIDENT` | §0.3 — lens A's refusal claim rests on a transform sign error. `SeamMap`'s class javadoc promises "general sub-block phase from day one" and this keeps it. |
| **F9** | bi-faced pair; `findDestinationPortal` could pick the wrong face | `isReversePortal`-style dot test picks exactly one; two claimants ⇒ `null` ⇒ `phase = UNKNOWN`, stock (a) | §3.5(iii). Verified: `dot(normal(P), contentDirection(Q)) = +1`, `= −1` for the flipped face. Leg **B11**. |
| **F10** | first bind taken before the far portal ticks (`reverse == null`) | portal is recorded `UNRESOLVED_REVERSE` and re-evaluated on later ticks | §3.5(vi). Without it, `fingerprintOf:127-139` + `:92-93` freezes a drifted `destPos` forever. |
| **F11** | portal moves; `unbind` enumerates the **new** geometry (**B-9**) | `unbind` drops exactly the cells `bind` recorded | §3.5(v). Otherwise orphaned cells hold live `crossPos` shadows onto far positions with no portal — and (b) would write rails there. |
| **F12** | slope ascending into the plane, far cell holds a rail or air (**B-7**) | removed, exactly as a local slope with no support would be | §3.3 is purely additive; `shouldBeRemoved:98-101` demands a *solid* support and neither a rail nor air is one. The intended geometry (support cube at `crossPos`, rail at `crossPos.above()`) is admitted by (a)'s whitelist. Leg **B12**. |
| **F13** | shape-only rail write triggers `affectNeighborsAfterRemoval` via `LevelChunk:318`'s `newBlock instanceof BaseRailBlock` carve-out | for a **flat** old shape the body is a no-op (`:119-130`, `isStraight == false`); for a **slope** old shape it does `updateNeighborsAt(pos.above())` | §0.4. Bounded and vanilla-identical; it is why F12 must be right. |
| **F14** | OW approach at `S+f` **and** unrelated nether track at `D−n_Q` (**B-10 / §5**) | local wins; the cross cell is never consulted, never read, never written | **R1′**, §2.5. Under the old R1 this was a misread *and* a destructive `connectTo` onto an unrelated nether rail. |
| **F15** | `reresolve` fires on the client (**B-8**) | guarded by `lvl.isClientSide()`; `AperturePassthroughInit:63` registers the client tick signal | §3.4. `reresolve` bypasses `updateDir:110-112`, the only client guard in the rail system. |
| **F16** | (b) tries to write a cell that holds no rail | impossible | §2.5's invariant: `connectTo:204-205` / `place:331-333` only `setValue(shapeProperty, …)` on `this.state`, which for a proxy **is** the far rail's state. Answers "bypasses `mayPlace`" — (b) never places. Leg **B9** asserts it. |
| **F17** | teardown leaves a machine-touched far rail | correct in topology B: two distinct player-visible rails, one per dimension, each survives in its own | `onPortalTornDown:506` only clears cells in `mirrorCreatedCells`, and (b) never adds to it. Lens A's "permanent duplicate half" is a topology-A framing applied to topology B. |
| **F18** | duplicate entry in a proxy's `connections` (`removeSoftConnections:83` + `connectTo:144`) | tolerated by `hasConnection:116-125` | vanilla behaviour on every rail everywhere; unchanged by (b). |
| **F19** | nested seams (a portal inside another's shadow) | `shadowFor` inherits, never nests; the inner seam reads as ordinary far-world blocks | `seamlessportals$find` returns `this.shadow` before consulting `armed`. |
| **F20** | overlapping portals at one cell | first eligible binding wins, logged under `seamShadowProbe` | `SeamCell.with:83-88` already caps a cell at two bindings. |
| **F21** | `MixinRailStateSeam` fails to match after a mapping change | **load-time failure** | every wrap carries exact `require`/`allow` matching the verified site counts (§3.2), so lens B §9b's "ten of eleven silently miss" cannot happen. |

---

## 6. HOT-PATH ANALYSIS — a rail nowhere near a portal

Path: `BaseRailBlock.updateDir:109-116` → `new RailState(level, pos, state)` → `RailState:20-28`.

**Per `RailState` construction, once:** the `<init>` TAIL inject does
`AperturePassthroughLever.DISABLED` (static-final `boolean`, JIT-folded) →
`DISABLE_SEAM_SHADOW` (same) →
`SeamlessPortalsConfig.isEntityPortals()` (load-time cached flag) →
`lvl instanceof ServerLevel` →
`SeamRegistry.sectionHasSeam:105-109` = one field read + `LongOpenHashSet.isEmpty()` + one `contains(SectionPos.asLong(pos))`.
In a world with **no portal at all**, `isEmpty()` short-circuits and there is no hash. With portals, one hash probe. `armed = false`, `owner = null`.

**Per probe, thereafter:** `this.seamlessportals$shadow != null` (one reference read) → `this.seamlessportals$armed` (one boolean read) → `original.call(...)`. Two field reads plus the wrapped call.

**The honest extra bill (lens B §8, accepted).** `@WrapOperation` rewrites the bytecode for *all* `RailState` instances everywhere, permanently, and MixinExtras 0.5.3's `Operation.call(Object...)` **boxes**: every `original.call(lvl, q)` allocates an `Object[2]` and returns a boxed `Boolean`/`BlockState`. Under R1′ that call happens on **every** probe, armed or not. Sites per `RailState`: `hasRail` 3, `connectTo` 4, `place` 4, `getRail` 3, plus 2 writes = up to 16. A rail placement builds ~10–30 `RailState`s. So: **hundreds of small TLAB allocations per rail placement**, all escape-analysis-friendly and all on a path that already allocates (`Lists.newArrayList` per `RailState`, `:18`). It is not "two field reads"; saying so would be false. `@Redirect` would avoid the boxing at the cost of hard-conflicting with any future mixin on the same call — **decision: keep `@WrapOperation`, state the cost.**

**Global surface unchanged.** Nothing is added to `LevelChunk.setBlockState` (the only hook there is the pre-existing `LevelChunkSetBlockStateMixin:93-124`, whose body gains one `getBlockState` re-read), nothing to `Level.getBlockState`, nothing to `Level.setBlock`. `MixinBaseRailBlockSeamSlope` adds 5 wraps on `shouldBeRemoved`, each of which calls `original` **first** and returns immediately when the local answer is `true` — the overwhelmingly common case.

**A gate false-positive costs CPU only; a false-negative costs the feature only. Neither corrupts rails.** Correctness never depends on the fold.

---

## 7. UPDATE-LOOP SAFETY

**(T1′) Vanilla's own fixed point, correctly located.** The adjudication cited `Level.setBlock:234-236` for the mirror; the mirror is driven from `LevelChunk.setBlockState` (§0.1), so T1 as written was inapplicable. It **is** applicable to `SeamShadow.writeLocal`, which delegates to `Level.setBlock` on the far level: `LevelChunk.setBlockState:280-282` returns `null` when the state is unchanged, and `Level.setBlock:234-236` returns `false` **before** `updateNeighborsAt:251`. A cross-seam `connectTo` computing the shape the far rail already has notifies nobody. `place:332` declines to write an unchanged state at all.

**(T2′) The two sides compute the same answer.** Stronger than the adjudication's T2, and it now rests on a source-derived fact rather than assertion. In **topology A** every source cell is co-located with a destination cell (§1, verified by transform: `S+f ≡ D−n_Q`, `S+g ≡ D+n_Q`). Under R1′ each side, for each of its four horizontal slots, resolves to *local-or-cross of the same physical place*. Same occupancy in, same `place` algorithm, same shape out modulo `R`. In **topology B** the two cells are distinct and are *supposed* to differ (`NORTH_EAST`/`SOUTH_EAST`); nothing forces them to agree and nothing needs to.
**Residual, stated not hidden:** `hasNeighborRail:208-215` is not pure occupancy — it ends in `neighbor.canConnectTo(this)` (`:139-141`), which depends on the neighbour's own arms. Where a co-located pair holds rails in *both* dimensions with mismatched arms, `S` consults the OW one and `D` the nether one and they can differ. Outcome is a stable mismatch, not an oscillation (F2). Probe-logged.

**(T3′) The mirror↔resolver cycle is structurally absent.** In **topology B** the phase gate stops the mirror seeing the seam at all. In **topology A** the mirror writes `D`; a cross-seam resolution writes `crossPos = D±n_Q`, which is **not** a bound seam cell, so the mirror driver's `sectionHasSeam` + `lookup` gate (`SeamMirror:206-209`) declines. And `SeamMirror.applying` (`:175`, `:203-205`, `:243-245`) blocks the mirror's own re-entry, including the `setBlockAndUpdate:311` → `onPlace` → `updateDir` → `place` cascade, which `CollectingNeighborUpdater` keeps on the same call stack.
Note precisely what the adjudication got wrong here: *"the mirror writes D; a cross-seam resolution writes `crossPos = D+n_Q`"* is true of **(b)'s** writes but not of the destination's own `place`, which writes `this.pos == D` (lens A B-2). The guard eats that write-back deliberately — that is the design, not an accident, and T2′ is why it is safe.

**(T4′) `onPlace` cannot re-fire — the structural termination argument.** §2.5's invariant: (b) only ever changes a **shape property** of an existing rail. `BaseRailBlock.onPlace:65` guards on `!oldState.is(state.getBlock())`, so a shape-only write never reaches `updateState`/`updateDir`. The only remaining outward edge is `Level.setBlock:251 updateNeighborsAt` → `BaseRailBlock.neighborChanged:80-90`, which for a plain rail reaches the **empty** `updateState(…, Block)` overload (`:106-107`, §0.5). **A cross write cannot start a new resolution.** This is what the whole loop-safety case rests on, and it is source-proved rather than argued.

**(T5) Hard budgets, correctly implemented.** `MAX_DEPTH = 4` and `MAX_CROSS_WRITES_PER_TICK = 512` in `SeamRailContinuity.enterWrite/exitWrite` with a real `try/finally` in `SeamShadow.writeLocal` (lens A B-4a: the adjudication's counter was never incremented, so `depthCapTrips` was structurally always 0 and leg B7 proved nothing). Reset from `END_SERVER_TICK` (`AperturePassthroughInit:71-78`). **Every gametest leg asserts `depthCapTrips == 0 && budgetTrips == 0`** — a non-zero count is evidence of a design fault, not a licence to run.

**Thread safety.** `shadowFor` requires `level instanceof ServerLevel` **and** `server.isSameThread()`. `updateDir:110-112` returns on the client. The one path that skips `updateDir` is `SeamRailContinuity.reresolve`, which carries its own `isClientSide()` guard (§3.4) — that exception is now named rather than assumed.

---

## 8. LEVERS AND PROBES

Hosted on the plain holder `AperturePassthroughLever` per its `:20-28` rationale (`Boolean.getBoolean` is not a compile-time constant, and Mixin silently drops non-constant static initialisers on mixin classes). Every one needs a `-P` row in **BOTH** `fabric/build.gradle` blocks, in the established form, after `:144` (client) and `:298` (crossingGametest).

**Fix levers — DEFAULT-ON, each names what it disables:**

| field | property | effect when set |
|---|---|---|
| `DISABLE_SEAM_SHADOW` | `disableSeamShadow` | master off-switch for (b): `shadowFor` always returns null. Single A/B lever. |
| `DISABLE_SEAM_RAIL_WRITE` | `disableSeamRailWrite` | cross-seam **reads** still work, cross-seam writes do not. Isolates "is the far rail seen" from "is it rewritten" — they fail for different reasons. |
| `DISABLE_SEAM_RAIL_SLOPE` | `disableSeamRailSlope` | §3.3 off; a rail ascending into the seam reverts to vanilla and pops. |
| `DISABLE_SEAM_PHASE_GATE` | `disableSeamPhaseGate` | restores (a)'s unconditional mirroring, including on `DISJOINT` seams. Exists so the duplicate-block and requirement-denied defects §3.6 prevents can be **demonstrated**, not just asserted. |
| `DISABLE_SEAM_RAIL_RESEED` | `disableSeamRailReseed` | §3.4 off; lighting a portal over an existing track no longer re-resolves it. |
| `DISABLE_SEAM_SETTLED_STATE` | `disableSeamSettledState` | reverts §3.1 to mirroring the `newState` argument. Demonstrates B-1. |
| `DISABLE_SEAM_REVERSE_DISAMBIGUATION` | `disableSeamReverseDisambig` | reverts `findDestinationPortal` to first-match. Demonstrates lens B §2. |

`AperturePassthroughLever.DISABLED` (`:78-79`) is checked in `shadowFor`, `MixinRailStateSeam.<init>`, `MixinBaseRailBlockSeamSlope` and `reresolve` — closing lens B §9a, which correctly noted the master lever had stopped being master.

**Probes — DEFAULT-OFF:**

| field | property | output |
|---|---|---|
| `SEAM_SHADOW_PROBE` | `seamShadowProbe` | one shot per portal at bind: `srcFacing`, `crossDir`, `revDir` **and** `Direction.getApproximateNearest(reverse.getNormal())` side by side (cross-check), `S`, `D`, `raw`, `crossPos`, `phase`, `R`, `latticeAligned` verdict, and the eligibility verdict **with the failing clause named**. Plus the lattice refusal and ambiguous-reverse warnings. |
| `SEAM_RAIL_PROBE` | `seamRailProbe` | per resolution: owner cell + dim, phase, `localAnchor`, `farAnchor` + dim, per-slot `{local=…, cross=…, chose=…}`, shape in → shape out, resolved `RailShape` on a write. First 200 then 1 Hz-latched (per the `SEAM_AIM_PROBE` precedent, `Lever:146-156`: per-frame log4j costs ~130 ms stalls). |

`SeamRailContinuity.counters()` exposes `crossReads / crossHits / crossWrites / declinedCold / latticeRefusals / ambiguousReverse / depthCapTrips / budgetTrips / reseeds / retriesQueued / retriesServed`, in the style of `SeamMirror.counters():541-546`.

---

## 9. TEST PLAN

New legs in `fabric/src/main/java/com/warwa/seamlessportals/fabric/gametest/CrossingSmoke.java`, following the RS-TEARDOWN-TEST idioms (`:1068-1260`): isolated coordinates outside every other leg's 128-block frame-match radius, **preconditions waited on — never a tick count**, cleanup in a `finally`, no enclosing `catch (Throwable)` that could swallow an `AssertionError`, and state read on the server thread via the existing `runOnServer` helper (`:1622-1629`). Every leg **logs its working — actual shapes, `crossPos`, `localAnchor`, per-slot occupancy — not a verdict.**

**Topology-B fixture, exact and deterministic.** Verified: `PortalAPI.setPortalOrthodoxShape` → `Helper.getBoxSurface`, so a zero-thickness `AABB` at integer `z` puts the plane on the lattice boundary; `createOrthodoxPortal` (`PortalManipulation.java:250`) returns an **unspawned** portal.
```java
Portal p = PortalManipulation.createOrthodoxPortal(Portal.entityType, ow, nether,
    Direction.SOUTH, new AABB(X0, Y0, Z0, X0 + 1, Y0 + 3, Z0), new Vec3(NX + 0.5, NY, NZ));
McHelper.spawnServerEntity(p);
PortalManipulation.completeBiWayPortal(p, Portal.entityType);   // PortalManipulation.java:79
```
Support: stone under every rail cell.

| leg | what it does | the assertion that can only pass with (b) |
|---|---|---|
| **B0 — COVERAGE PREAMBLE, ABORTS THE GATE** | read back the real geometry on the server thread | `binding.seamContinuous()`, `phase() == DISJOINT`, `crossPos().equals(destPos())`, `crossDir() == srcFacing().getOpposite()`, and `SeamMap.latticeAligned(p, S)`. Fails as `TOPOLOGY B NOT CONSTRUCTED — every leg below is testing topology A` or `CROSSING DIRECTION INVERTED`. **Nothing runs until this passes.** Settles §0.5's direction convention empirically. |
| **B1 — TOPOLOGY B, THE DECISIVE SUB-CASE** | OW rails at `S` only; **`S+f` deliberately EMPTY**; nether rails at `D` and `D+n_Q` | `ow.getBlockState(S).getValue(RailBlock.SHAPE)` is the through-axis straight. **The only possible source of that result is `D`** — with `S+f` empty there is no local neighbour and vanilla leaves `S` at its placement default. Cannot false-pass. |
| **B2 — TOPOLOGY B, CURVE AT THE SEAM** | OW: `S` + `S+2f`,`S+1f`. Nether: `D` + `D+EAST`,`D+2EAST` | `S == NORTH_EAST` **and** `D == SOUTH_EAST`. Vanilla gives `EAST_WEST` for both (`place:234-236` vs `:255-257`). `crossHits > 0` asserted **first**, else fail with *"the bridge never fired — this leg proves nothing"*. Both shapes printed on failure. Also the direct falsifier for lens A's B-3. |
| **B3 — TOPOLOGY B, ROTATED** | same fixture, `p.setRotation(DQuaternion.rotationByDegrees(new Vec3(0,1,0), 90))`, `q` conjugated | assert **relationally** — `railArms(S.shape).contains(crossDir_S)` and `railArms(D.shape).contains(crossDir_D)`, invariant under rotation — **plus** `binding.stateRotation() == CLOCKWISE_90` to prove the rotated path was exercised. A rotation bug is invisible in every unrotated leg. |
| **B4 — TOPOLOGY B, THE MIRROR GATE** | place a rail at `S` while `D` already holds one | placement is **allowed** (`SeamMirror.mayPlace(ow, S, RAIL) == true`), `D` is **not** overwritten, `nether.getBlockState(D)` still holds the player's original. Under `-PdisableSeamPhaseGate=true` the veto refuses and the requirement is unreachable. |
| **B5 — TOPOLOGY A, OBSIDIAN, CURVE** | RS-TEARDOWN-TEST frame recipe; track through a bottom-row aperture cell, nether track leaving perpendicular from `D+n_Q` | `phase() == COINCIDENT` and `crossPos.equals(destPos.relative(revDir))`; the aperture rail is a **corner**, not `NORTH_SOUTH`; **and `ow.getBlockState(S).rotate(R) == nether.getBlockState(D)`** — a **cross-side** invariant, per the hazard that a same-side check passed while the mirror was one cell off. **This leg fails today because of B-1** and is the acceptance test for §3.1. |
| **B6 — TOPOLOGY A, THE TWIN ASSUMPTION** | at the obsidian `S`, read both `SeamCell` bindings | both agree on `destPos()` and `stateRotation()`; `crossDir()` are **opposite**; `crossPos()` are `D±n_Q`. Converts §1's premise from assumption into gate. Cheap. |
| **B7 — TERMINATION** | after B2 and B5 settle, snapshot both shapes, `waitTicks(40)`, re-read | shapes identical across two reads 20 ticks apart; `depthCapTrips == 0`; `budgetTrips == 0`; `crossHits > 0`. A per-call depth cap cannot catch a **cross-tick** oscillation — this is aimed squarely at it, and it now means something because §2.4 actually increments the counter. |
| **B8 — MIXED PHASE REFUSED** | **boundary-phase OW plane paired to a mid-block nether plane** (the §0.6 counter-example that the two-candidate classifier accepts) | `latticeAligned == false`, `seamContinuous == false`, `phase == UNKNOWN`, `shadowFor(...) == null`, both sides byte-identical to vanilla, `latticeRefusals > 0`, no exception. **This leg fails against the adjudicated design** and is the acceptance test for `SeamMap.latticeAligned`. |
| **B8b — GENERAL PHASE ACCEPTED** | planes at `Z0+0.3` / `Z1+0.7` | `latticeAligned == true`, `phase == COINCIDENT`, and B1's straight-through result reproduces. Guards against over-refusal (§0.3). |
| **B9 — (b) NEVER CREATES OR DESTROYS A BLOCK** | census every non-rail cell within 3 blocks of both seams before and after B1/B2/B5 | byte-identical; and every rail present after is present before. Asserts §2.5's invariant directly, which is what makes `mayPlace`-bypass a non-issue (F16). |
| **B10 — VANILLA NON-REGRESSION** | identical rail T-junction 3 blocks from the seam and 300 blocks away in the same dimension | every shape identical. Under R1′ this is a theorem, not a hope. |
| **B11 — BI-FACED BOUNDARY-PHASE PORTAL** (lens B §2) | build the topology-B fixture with `completeBiWayBiFacedPortal:183` | `findDestinationPortal` returns the face whose `contentDirection` dots `+1` with `P.getNormal()`; `destPos` is the same cell the single-faced fixture produced; `phase == DISJOINT`. Under `-PdisableSeamReverseDisambig=true` the leg must report `phase == COINCIDENT` — proving the fail-open exists and that the fix closes it. |
| **B12 — SLOPE AT THE SEAM** | topology B; support cube at `crossPos`, rail at `crossPos.above()`, ascending rail at `S` | `S == ASCENDING_<crossDir>` and it **survives 40 ticks** (the `LevelChunk:318` carve-out fires `shouldBeRemoved` repeatedly). Under `-PdisableSeamRailSlope=true` it pops. |
| **B13 — B-1 REGRESSION** | topology A; place a rail at `S` facing a direction whose resolved shape differs from the placement default | `nether.getBlockState(D)` equals `ow.getBlockState(S).rotate(R)`. Under `-PdisableSeamSettledState=true` it must **differ** — proving the defect was real, in the RS-TEARDOWN-TEST inversion discipline. |
| **B14 — COLD FAR CHUNK, THEN WARM** (lens B §4) | topology B; force the far chunk out of the loaded set, place at `S`, assert vanilla shape and `declinedCold > 0`; then warm it and wait | the shape self-heals to the crossing answer and `retriesServed > 0`. Without §2.6 it stays wrong forever. |
| **B15 — HORIZONTAL-PLANE PORTAL, (a) NON-REGRESSION** (lens B §3 / lens A B-5) | floor portal (normal `(0,±1,0)`); place a plain non-rail block in an aperture cell | the block is **still mirrored** and `mayPlace` still refuses on conflict, byte-identical to pre-(b); `phase == UNKNOWN`; `seamContinuous == false`. A boolean `coincident` fails this leg. |
| **B16 — LEVER INVERSION** | re-run B1 and B2 under `-PdisableSeamShadow=true` | B1's `S` reverts to its placement default; B2 inverts to `EAST_WEST`/`EAST_WEST`; `crossHits == 0`. Seeing the corner reports a **REGRESSION**. |

**Gate — all must reach `ALL LEGS PASS`:**
```
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PdisableAperturePassthrough=true
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PdisableSeamShadow=true
.\gradlew.bat :fabric:runCrossingGametest -PapertureTeardownTest=true -PdisableSeamPhaseGate=true
```

**What only a live round can judge** (`-PseamShadowProbe=true -PseamRailProbe=true`):
1. **Boundary-phase seam, edge-on:** do two whole rails meet flush at the plane — no gap, no half-block overlap, no third doubled rail? Overlap ⇒ `phase` came out `COINCIDENT` when it should be `DISJOINT`; gap ⇒ the crossing landed one cell too far.
2. **Topology B by hand:** lay rail up to the plane from *each* dimension independently. Neither placement refused, and the two read as one track. *(The requirement, verbatim.)*
3. **Obsidian seam:** does the shared slot read as **one** rail with a corner, or two mismatched halves? Does its shape **flicker**? Stand there 30 s. (This is the T2′ residual, F2.)
4. **Rotated portal, curve at the aperture** — where a sign error in the five rotation points shows as a track that turns the wrong way.
5. Placing and breaking repeatedly at a seam cell, watching for the "places and instantly disappears" family the mirror already produced once (`SeamMirror.java:288-295`).
6. **Build track behind an obsidian portal on both sides at a co-located cell.** This is R1′'s one remaining ambiguity and no test can adjudicate it — only a player can say which shape looks right.
7. Ordinary rail building 200 blocks from any portal, and a long track beside one — unchanged feel, no TPS movement (the §6 boxing bill made visible or not).

---

## 10. STAGING

Each step is independently revertible and gated. **Do not proceed past a red gate.**

| step | change | gate |
|---|---|---|
| **S1** | §3.1 B-1 fix in `LevelChunkSetBlockStateMixin`, lever `DISABLE_SEAM_SETTLED_STATE`, leg **B13** | B13 passes; B13 under `-PdisableSeamSettledState=true` **fails** (proving the defect); the existing 8-leg (a) gate still green. **No (b) code exists yet.** |
| **S2** | §3.5(iii) reverse disambiguation, (v) unbind snapshot, (vi) fingerprint stickiness; `SeamMap.latticeAligned`; legs **B11**, **B8**, **B8b** | all three pass; existing (a) gate green. Still no (b) behaviour. |
| **S3** | `SeamPhase` tri-state on `SeamBinding` + `bind` classifier + `SeamMirror` gate; legs **B0**, **B4**, **B6**, **B15** | B15 is the hard one — (a) must be **byte-identical** for horizontal-normal portals. B0 must classify the fixture `DISJOINT`. |
| **S4** | `SeamShadow`, `SeamShadowBridge`, `SeamShadowHolder`, `SeamRailContinuity` (no mixins yet) | compiles; `SeamRailContinuity.counters()` reachable; unit-level: `toFar` round-trips under all four `Rotation`s, `depth` signs correct for the six probe positions the adjudication enumerated. |
| **S5** | `MixinRailStateSeam` H1a/H1b/H1c/H2/H2b (**reads only**, writes still `original`), with `DISABLE_SEAM_RAIL_WRITE` forced on; legs **B1**, **B2**, **B10**, **B16** | B1 and B2 pass **read-side**; B10 byte-identical. This is where R1′ is proved. |
| **S6** | enable H3a/H3b writes; legs **B5**, **B7**, **B9** | B9 (no block created/destroyed) is the gate. B7 must show `depthCapTrips == 0 && budgetTrips == 0`. |
| **S7** | `MixinBaseRailBlockSeamSlope`; leg **B12** | B12 survives 40 ticks. |
| **S8** | `SeamRailContinuity.reresolve` + retry queue + `bind`/`unbind` hooks; legs **B14**, and a reseed leg (track laid through an unlit frame, then ignited) | reseed leg passes and `reseeds > 0`; B14 self-heals. |
| **S9** | rotation coverage: leg **B3** | passes with `stateRotation == CLOCKWISE_90` asserted. |
| **S10** | full four-command gate + live round (§9's seven questions) | all green, then the user's call on §3.6's flagged decision. |

---

## 11. RESIDUAL RISK

**What is UNVERIFIED (labelled, not buried):**

- **UNVERIFIED —** Mixin's `allow` semantics on `@WrapOperation` (MixinExtras 0.5.3). `require` is documented as a total across targets; I have split every wrap per method with exact counts so `require` is unambiguous, and added `allow` to make an *over*-match fatal too. If `allow` is not honoured by `@WrapOperation` the wraps still behave correctly; only the load-time strictness weakens. **Verify at S5 by deliberately breaking one descriptor and confirming a load failure.**
- **UNVERIFIED —** the `1e-4` tolerance in `latticeAligned`. `DQuaternion` round-trips at 90° should be exact to ~1e-15, so 1e-4 is generous; but I have not measured a rotated portal's actual residual. Too tight ⇒ rotated portals silently refuse (fail-safe); too loose ⇒ a near-miss phase is accepted (fail-open). **Log the actual residual under `seamShadowProbe` at S2 and tighten.**
- **UNVERIFIED —** exact `-P` insertion line numbers survive S1–S3's own edits to `AperturePassthroughLever`. The block boundaries (`client {` at `fabric/build.gradle:98`, `crossingGametest {` at `:228`) and the last lever rows (`:144`, `:298`) were read this session and are correct **at `d7b74a8`**.
- **UNVERIFIED —** that a support cube in an aperture cell survives (a)'s ignition whitelist in the exact form B12 builds it. `AperturePassthroughLever.java:112-119` describes the rule; I did not open its implementation. B12 will say.
- **UNVERIFIED —** the residual in T2′: whether a co-located pair with mismatched arms actually occurs in play often enough to matter. It is a source-derived possibility, not an observation.

**THE SINGLE MOST LIKELY THING TO BE WRONG.**

Not the direction convention (three independent derivations in `Portal.java`, plus B0 as an abort). Not the geometry refusals (§0.6 replaced enumeration with a proof). It is **R1′'s tie-break at a co-located cell in topology A — the case where both dimensions hold a rail at the same physical place.**

R1 (cross-first) was refuted by two independent verifiers, and R1′ (local-first) is unarguably better in every configuration either of them raised. But the tie itself is *genuinely undecidable from geometry*: §1 establishes that in topology A nothing is occluded — `S+f` and `S+g` are both real, visible OW approach cells and `D±n_Q` are both real, visible nether approach cells, and the two are the **same physical place seen from two dimensions**. R1′ resolves the tie in favour of the querying dimension's own block, which is the safe, non-destructive, purely-additive choice and makes vanilla non-regression a theorem. It is not obviously the choice a *player* would expect, because the player sees one place and two blocks.

The symptom if it is wrong is not a crash, not corruption, and not an oscillation: it is a track at an obsidian portal that connects to the arm the player did not mean, with no in-game feedback. It is invisible to every gametest leg (they all build only one dimension's block at each co-located cell, deliberately) and is why live-round question 6 exists. **Every counter needed to diagnose it is in `SEAM_RAIL_PROBE`'s per-slot `{local=…, cross=…, chose=…}` line, and inverting the rule is a one-line change in three places** (`seamlessportals$isRail`, `seamlessportals$readGetRail`, `MixinBaseRailBlockSeamSlope`) behind a new lever if the live round says the player expects otherwise.