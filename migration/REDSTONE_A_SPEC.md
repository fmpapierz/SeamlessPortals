# FINAL IMPLEMENTABLE SPEC — Sub-feature (a): placeable, mirrored portal aperture

> ## ⚠ USER DECISIONS 2026-07-25 — THESE OVERRIDE THE SPEC BELOW WHERE THEY DIFFER
>
> The spec's §8 listed three items as needing the user. All three are now decided:
>
> 1. **Frame break → `SEVER_CLEAR_DEST`, NOT `KEEP_BOTH`.** The spec's default duplicates blocks; the
>    user rejected that. The originally-placed block survives in its own dimension and its mirror is
>    removed. **This adds a requirement the spec does not have: PROVENANCE.** `SeamBinding` must record
>    which half was player-placed and which was the mirror, otherwise "clear the destination half" is
>    undecidable. Add the field in staging step 2 and consume it in step 6/7.
> 2. **Non-item writes → accept best-effort**, exactly as §8 proposes. The refuse-on-conflict violation
>    is knowingly accepted for (a).
> 3. **§4.1's ignition rule → CONFIRMED.** It is no longer "a Claude-made reconciliation … confirm it
>    before step 4"; it is a user decision. Build it as written.
>
> **Two future plans, explicitly out of scope for (a)** (recorded in `REDSTONE_RECON.md` §0.9): genuine
> *fractional* seam blocks so a frame break loses nothing, and fully correct non-item write behaviour.
> Do not build either now; do not lose them.


Worktree root `W` = `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\redstone`; `REF` = `C:\Users\warwa\ModDev\mc262-ref`. All paths below are relative to `W` unless prefixed `REF/`.

Citation status of every load-bearing line is one of: **[V]** = I re-opened it this session; **[V2]** = attested by both the design and a verifier and consistent with what I opened; **[UNVERIFIED]** = asserted upstream, not opened by me.

---

## 1. SPEC SUMMARY

- The portal aperture becomes ordinary building space at every height: `PortalPlaceholderBlock` is replaceable by hand-placement, and the portal integrity predicate stops reading the opening (frame-only).
- Every aperture cell is bound, per facing, to a counterpart cell across the seam by one phase-agnostic arithmetic rule; a block placed or broken in one is mirrored into the other at end-of-tick.
- Placement that cannot be mirrored cleanly is **refused before any write** — no block, no sound, no item consumed. Fluids, block-entities and multi-cell blocks are refused outright (overturning design §11.9).
- Ignition tolerates a whitelisted redstone/rail set **plus a support cube that carries a whitelisted block directly above it**, subject to a position-aware post-check; you still cannot light a portal through a wall.
- Nothing is persisted about the bond (it is derived from live portal geometry), with **one** exception forced by verification: a small pending-clear journal so "break one half breaks the other" survives an unloaded destination and a restart.

---

## 2. THE SEAM MAPPING PRIMITIVE

This is the API (b)/(c)/(d) reuse. New file `common/src/main/java/com/warwa/seamlessportals/passthrough/SeamMap.java`.

### 2.1 Facts it rests on

| fact | citation | status |
|---|---|---|
| portal position is the aperture centre; 1-thick aperture ⇒ plane at cell mid-plane | `qouteall/imm_ptl/core/portal/nether_portal/BlockPortalShape.java:360-364`; `qouteall/q_misc_util/my_util/IntBox.java:176-182` (`(l+h+1)/2`) | [V2] |
| `transformPoint(Vec3)` = `transformLocalVec(p − getOriginPos()) + getDestPos()` | `qouteall/imm_ptl/core/portal/Portal.java:508-512` | [V2] |
| `transformLocalVecNonScale` = `rot == null ? v : rotation.rotate(v)` | `Portal.java:1267-1273` | [V2] |
| `getNormal()` = `axisW.cross(axisH).normalize()` | `Portal.java:526-531` | [V2] |
| `getContentDirection()` = `transformLocalVecNonScale(−normal)` | `Portal.java:537-542` | [V2] |
| `getOriginPos()` / `getDestPos()` / `getDestDim()` / `getScaling()` / `getRotation()` (@Nullable) | `Portal.java:631-633` / `:639-641` / `:714` / `:1530` / `:807` | [V2] |
| `getDistanceToPlane(Vec3)` = `(p − origin)·normal` | `Portal.java:1157-1159` | [V2] |
| `createFlippedPortal` negates **only** `axisW`, copying `destination`/`dimensionTo`/`rotation`/`scaling` ⇒ `f1`/`f2` share `transformPoint` exactly and have exactly opposite normals | `qouteall/imm_ptl/core/portal/PortalManipulation.java:132-156` (negation at `:144`) | [V2] |
| reverse portal `Q` satisfies `n_Q = contentDirection(P)` | `qouteall/imm_ptl/core/portal/nether_portal/BreakablePortalEntity.java:299-300` (`getContentDirection().dot(getNormal()) > 0.6`) | [V2] |

### 2.2 The rule (unchanged from the adjudicated design — both verifiers confirmed the arithmetic)

```java
public static final double STEP = 0.25;   // any value in (0, 0.5)

public static Vec3 onPlane(Portal p, Vec3 anyPointInColumn) {
    Vec3 n = p.getNormal();
    return anyPointInColumn.subtract(n.scale(p.getDistanceToPlane(anyPointInColumn)));
}
public static BlockPos seamCell(Portal p, Vec3 onPlane) {
    return BlockPos.containing(onPlane.add(p.getNormal().scale(STEP)));
}
public static BlockPos mirrorCell(Portal p, Vec3 onPlane) {
    return BlockPos.containing(p.transformPoint(onPlane).add(p.getContentDirection().scale(STEP)));
}
```

`cell(x ± n·0.25)` is the greatest-overlap cell along the normal (midpoint of a ≤1-length interval lies in the greater-overlap cell; 3-D overlap factorises per axis, so componentwise argmax = global argmax). Because `n_Q = contentDirection(P)`, `mirror_P(x) = seam_Q(T_P(x))` and `mirror_Q ∘ mirror_P = id` in every phase. **Verifier A independently re-derived and confirmed this for the coincident case; Verifier B re-derived it in general.** Neither found a defect here.

### 2.3 Verified defect fixed here — in-plane overlap (Verifier B, F7; PLAUSIBLE, adopted)

The design binds every cell any half-block lattice sample lands in, which for a non-grid-aligned wand portal binds sliver-overlap cells on equal footing with fully covered ones. §0.7's rule is an *overlap* rule and must be applied on all three axes.

**Replaces the lattice.** Enumerate the integer cells whose in-plane face intersects the aperture rectangle, compute the exact 2-D overlap area of `cellFace ∩ apertureRect` in the portal's `(axisW, axisH)` basis, and bind only cells with `overlap ≥ 0.5` of a unit face:

```java
// SeamMap.enumerateColumns(Portal p) -> List<Vec3>   (one plane point per bound column)
// For each candidate (i,j) in the integer lattice spanning the rectangle:
//   double ov = overlapLength(uLo, uHi, i, i+1) * overlapLength(vLo, vHi, j, j+1);
//   if (ov >= 0.5) emit p.getPointInPlane(uCentreOfOverlap, vCentreOfOverlap);
```
`Portal.getPointInPlane(double,double)` at `Portal.java:1175-1177` [V2]. The emitted point is the **centroid of the overlap region**, not the cell centre — that is what makes `seamCell`/`mirrorCell` pick the greatest-overlap cell on the in-plane axes too.

### 2.4 Public API (the (b)/(c)/(d) contract)

`common/src/main/java/com/warwa/seamlessportals/passthrough/SeamRegistry.java`:

```java
public record SeamBinding(
    Direction srcFacing,
    @Nullable ResourceKey<Level> destDim,   // null => query-only, no mirror
    @Nullable BlockPos destPos,
    Direction.Axis destNormalAxis,
    Rotation stateRotation,
    UUID portalUuid,
    UUID clusterKey                          // NEW: see §5, seam-group containment
) {}
public record SeamCell(@Nullable SeamBinding a, @Nullable SeamBinding b) {
    public List<SeamBinding> bindings();
}

public static boolean anySeams();                                            // fast gate
public static @Nullable SeamCell lookup(Level level, BlockPos pos);
public static @Nullable GlobalPos lookupAcross(Level l, BlockPos p, Direction d);   // (b)/(c)/(d)
public static Direction mapDir(SeamBinding b, Direction d);                        // (c)/(d)
public static List<GlobalPos> seamGroup(Level l, BlockPos p);                      // ≤3, cluster-bounded
public static boolean isSeamCell(Level l, BlockPos p);
public static boolean isFrameCell(Level l, BlockPos p);                            // NEW, §4 D3 fix
```

`mapDir` = `Direction.getNearest(M · d.getUnitVec3i())` where `M` is the cube-group element behind `stateRotation`.

### 2.5 The grid gate — `isMirrorable`

```java
public static boolean isMirrorable(Portal p) {
    if (Math.abs(p.getScaling() - 1.0) > 1e-9) return false;      // Portal.java:1530
    if (!isSignedUnitAxis(p.getNormal())) return false;           // Portal.java:526-531
    DQuaternion r = p.getRotation();                              // Portal.java:807
    if (r == null) return true;
    return isSignedUnitAxis(p.transformLocalVecNonScale(new Vec3(1,0,0)))
        && isSignedUnitAxis(p.transformLocalVecNonScale(new Vec3(0,1,0)))
        && isSignedUnitAxis(p.transformLocalVecNonScale(new Vec3(0,0,1)))
        && blockRotationOf(p) != null;
}
```

### 2.6 `blockRotationOf` — DERIVED, with sign convention, because it was untested (Verifier B, F8 — accepted)

B is right that this was one asserted sentence with no derivation, and that `ENTITY_PORTAL_MIGRATION_BRIEFING.md §6` [UNVERIFIED line] names exactly this failure class. Derivation, stated so it can be checked in review:

```java
/** Returns the vanilla Rotation R such that state.rotate(R) faces the way the
 *  transformed geometry does, or null if the portal's rotation is not a pure yaw. */
public static @Nullable Rotation blockRotationOf(Portal p) {
    // Probe the portal's own linear map with the two horizontal unit vectors.
    Vec3 mx = p.transformLocalVecNonScale(new Vec3(1, 0, 0));
    Vec3 my = p.transformLocalVecNonScale(new Vec3(0, 1, 0));
    if (Math.abs(my.y - 1.0) > 1e-6) return null;              // any pitch/roll => refuse
    Direction xImage = Direction.getNearest(mx.x, mx.y, mx.z); // must be horizontal
    if (xImage.getAxis() == Direction.Axis.Y) return null;
    return switch (xImage) {                                   // image of EAST
        case EAST  -> Rotation.NONE;
        case SOUTH -> Rotation.CLOCKWISE_90;
        case WEST  -> Rotation.CLOCKWISE_180;
        case NORTH -> Rotation.COUNTERCLOCKWISE_90;
        default -> null;
    };
}
```
Convention: `Rotation.CLOCKWISE_90` maps NORTH→EAST→SOUTH→WEST in vanilla `Direction.getClockWise()` [UNVERIFIED — confirm against `REF/net/minecraft/world/level/block/Rotation.java` before the first commit]. **RS-A20 (§7) exists solely to falsify this table**; do not ship the mirror until it passes.

### 2.7 The seam group — cluster-bounded (Verifier A's chain-deletion finding, accepted)

A found that an unbounded transitive closure over `bind` edges can walk from portal P's aperture into an unrelated portal Q's aperture and delete a block the player never touched, and that the cap of 8 then produces a *partial* clear — precisely the half-orphaned seam §0.7 forbids.

**Fix (goes in):** closure follows only bindings whose `clusterKey` equals the originating binding's `clusterKey`. `clusterKey` = the UUID of the portal's `PortalExtension` cluster head [UNVERIFIED accessor name — if no stable cluster id exists, use `min(uuid of {f1,f2,t1,t2})` computed at seed time from `BreakablePortalEntity.findReversePortals` (`BreakablePortalEntity.java:293-303` [V2])]. Group size is then provably ≤3 (2 coincident, 3 under phase offset) and the cap is a `Validate`, not a truncation.

---

## 3. FILE-BY-FILE CHANGE LIST

### 3.1 IP-CORE EDITS — every one is a RECORDED IP DEVIATION

Each edit gets the header comment `// RECORDED IP DEVIATION — RS PASSTHROUGH (a); revert with -Dseamlessportals.disableAperturePassthrough=true`.

| # | file | method / line | change |
|---|---|---|---|
| **1** | `common/src/main/java/qouteall/imm_ptl/core/portal/PortalPlaceholderBlock.java` | new override after `getShape`, which ends at `:96` [V] | add `protected boolean canBeReplaced(BlockState, BlockPlaceContext) { return !AperturePassthroughLever.DISABLED; }` |
| **2** | same file | new override, same place | add `protected boolean canBeReplaced(BlockState state, Fluid fluid) { return AperturePassthroughLever.DISABLED && super.canBeReplaced(state, fluid); }` — **NEW, forced by Verifier B's C4/F5** |
| **3** | `common/src/main/java/qouteall/imm_ptl/core/portal/nether_portal/NetherPortalEntity.java` | `isPortalIntactOnThisSide()` `:73-104` [V2] | frame-only; IP's placeholder scan retained verbatim inside `if (AperturePassthroughLever.DISABLED) {…}`. **Delete the `SUPPRESS_TEARDOWN` read at `:84`** [V2] in the same commit — it is removed from the lever class (§6) and the build breaks otherwise. |
| **4** | `common/src/main/java/qouteall/imm_ptl/core/portal/nether_portal/GeneralBreakablePortal.java` | `isPortalIntactOnThisSide()` `:16-25` [V2] | drop `areaIntact`, keep `frameIntact`, behind the same lever |
| **5** | `common/src/main/java/qouteall/imm_ptl/peripheral/portal_generation/IntrinsicNetherPortalForm.java` | `getAreaPredicate()` `:94` [V] | `return ApertureOccupancy.areaPredicate();` |
| **6** | `common/src/main/java/qouteall/imm_ptl/peripheral/portal_generation/DiligentNetherPortalForm.java` | `getAreaPredicate()` `:52` [V] | same |
| **7** | `common/src/main/java/qouteall/imm_ptl/core/portal/custom_portal_gen/form/NetherPortalLikeForm.java` | `perform`, clear loop `:63-68` [V] | skip survivors: `if (!AperturePassthroughLever.DISABLED && !AperturePassthroughLever.DISABLE_SURVIVOR_SKIP && ApertureOccupancy.isSurvivor(fromWorld, areaPos)) continue;` — note the added `DISABLED` term (Verifier B, F3) |
| **8** | `common/src/main/java/qouteall/imm_ptl/core/portal/nether_portal/NetherPortalGeneration.java` | `fillInPlaceHolderBlocks` `:295-304` [V2], **inside** the method (5 call sites) | same guard, `return;` instead of `continue;`, same `DISABLED` term |
| **9** | `NetherPortalLikeForm.java` | `perform`, immediately after `if (fromShape == null) return false;` at `:51-53` [V] | `if (!ApertureOccupancy.ignitionAreaAcceptable(fromWorld, fromShape)) return false;` — the **position-aware** §0.3 rule (§4 F1/D5 resolution) |
| **10** | `NetherPortalGeneration.java` | `checkPortalGeneration` `:253-263` [V2] | `if (SeamRegistry.isSeamCell(fromWorld, startingPos)) return false;` — blocks re-ignition inside a live aperture (Verifier A, D6) |

**Files explicitly NOT touched** (unchanged from the adjudicated design, and Verifier B independently re-checked and endorsed the reasoning): `Portal.java` · `BreakablePortalEntity.java` · `PortalGenInfo.java` · `BlockManipulationClient.java` · `MixinClipContext.java` (which really lives at `common/src/main/java/qouteall/imm_ptl/core/mixin/common/MixinClipContext.java`, not the `client/block_manipulation/` package the recon claims) · `PortalExtension.java` · `MixinAbstractMinecartEntity.java`.

### 3.2 NEW OWNED FILES — `common/src/main/java/com/warwa/seamlessportals/passthrough/`

| file | contents |
|---|---|
| `SeamMap.java` | §2.2 arithmetic, `enumerateColumns` (§2.3), `isMirrorable` + `blockRotationOf` (§2.5/2.6) |
| `SeamRegistry.java` | §2.4 API; per-level index reached through the duck interface (§3.4); seeding + teardown (§3.3); `isFrameCell` |
| `SeamMirror.java` | veto (`mayPlace`), driver queue, end-of-tick flush, `withApplying` guard, `WRITE_COUNT` |
| `SeamJournal.java` | **NEW** — `SavedData` holding only *pending clears* (§4, unloaded-break fix) |
| `ApertureOccupancy.java` | `isOccupant`, `isSurvivor`, `areaPredicate`, `ignitionAreaAcceptable`, `isForbidden` |
| `AperturePassthroughInit.java` | wires the four signals + two cleanup events |
| `SeamProbe.java` | five probes (§6) |
| `AperturePassthroughLever.java` | **edit**: retire `SUPPRESS_TEARDOWN` (`:44`) and `TEARDOWN_TEST` (`:58`) [V], add the fields in §6 |

### 3.3 MIXINS — all in `com.warwa.seamlessportals.mixin.passthrough`, all registered in the `"mixins"` array of `common/src/main/resources/seamlessportals-common.mixins.json` [V]

`SeamlessMixinConfigPlugin.shouldApplyMixin` is at **`common/src/main/java/com/warwa/seamlessportals/mixin/SeamlessMixinConfigPlugin.java:148`** [V] — the adjudicated design's `:79-121` is wrong (Verifier B, C1; correct). Its conclusion stands: only `qouteall.*` mixins are flag-gated, so **every `com.warwa.*` mixin weaves in both flag states** and must open with `if (!SeamlessPortalsConfig.isEntityPortals()) return;`, matching `common/src/main/java/com/warwa/seamlessportals/mixin/LevelChunkSetBlockStateMixin.java:89` [V2].

| mixin | target | injection |
|---|---|---|
| `MixinBlockPlaceContext` | `net.minecraft.world.item.context.BlockPlaceContext` | `@Inject(method="canPlace()Z", at=@At("RETURN"), cancellable=true)` → `SeamMirror.mayPlace(level, ctx.getClickedPos(), null)` |
| `MixinBlockItem` | `net.minecraft.world.item.BlockItem` | `@Inject(method="canPlace(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z", at=@At("HEAD"), cancellable=true)` → `SeamMirror.mayPlace(level, pos, state)` **plus** the multi-cell / BE / fluid refusals (§4) |
| `LevelChunkSetBlockStateMixin` | existing file, **add a second `@Inject`** | `LevelChunk.setBlockState(BlockPos,BlockState,int)` `@At("RETURN")`; body in §3.5. **Must carry the `isEntityPortals()` gate** — the adjudicated design's snippet omitted it (Verifier B, minor; accepted) |
| `SeamIndexHolderMixin` | `net.minecraft.world.level.Level` | **NEW** — `@Implements` a duck interface holding `Long2ObjectOpenHashMap<SeamCell>` + `LongOpenHashSet sectionsWithSeams`, fixing the fast-path defect (§4 F12) |

### 3.4 Registry storage — fast path fixed

Verifier B (F12) is right that the design's three-stage gate folds nothing: `DISABLED` is `false` by default and `ANY_SEAMS` is `true` in any world with a lit portal, leaving **two** hash lookups on `LevelChunk.setBlockState`. Storage therefore becomes:

1. `AperturePassthroughLever.DISABLED` — `static final`, folded when the property is set.
2. `((SeamIndexHolder) level).seamlessportals$sectionsWithSeams()` — one field read, then `LongOpenHashSet.contains(SectionPos.asLong(pos))`. Empty set ⇒ one load + one hash on a tiny table.
3. Only on a section hit: `Long2ObjectOpenHashMap.get(pos.asLong())`.

No `Map<ResourceKey<Level>, …>` on the hot path at all. The fastutil precedent file is `common/src/main/java/com/warwa/seamlessportals/client/SeamlessClientChunkMap.java` [V] — the design's `SeamlessChunkMap.java` does not exist (Verifier B, C3; correct).

### 3.5 The driver body (edited `LevelChunkSetBlockStateMixin`, new `@Inject`)

```java
if (!SeamlessPortalsConfig.isEntityPortals()) return;
if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_MIRROR) return;
if (!(this.level instanceof ServerLevel sl)) return;
if (!SeamRegistry.sectionHasSeam(sl, pos)) return;              // §3.4 stage 2
BlockState oldState = cir.getReturnValue();
if (oldState == null || oldState == newState) return;
MinecraftServer srv = sl.getServer();
if (srv == null || !srv.isSameThread()) return;
if (SeamMirror.isApplying()) { SeamMirror.noteInnerWrite(sl, pos); return; }   // §4 D11
if (SeamRegistry.isFrameCell(sl, pos)) SeamRegistry.notifyClusterAt(sl, pos);  // §4 D3
if (SeamRegistry.lookup(sl, pos) == null) return;
SeamMirror.enqueue(srv, GlobalPos.of(sl.dimension(), pos.immutable()));
```

Site rationale is already written at `LevelChunkSetBlockStateMixin.java:36-72` [V2] and unanimous across the design and both verifiers: it is upstream of every filter in `Level.markAndNotifyBlock`.

Flush runs on `ServerTaskList.of(server).addTask(MyTaskList.oneShotTask(…))`; `ServerTaskList` registers `processTasks` on `ServerTickEvents.END_SERVER_TICK` at `common/src/main/java/qouteall/imm_ptl/core/mc_utils/ServerTaskList.java:10-13` [V2] and `forceClearTasks` on `IPGlobal.SERVER_CLEANUP_EVENT` at `:15` [V].

### 3.6 Data / resources

- `common/src/main/resources/data/seamlessportals/tags/block/aperture_passthrough.json` — rails, wire, repeater, comparator, lever, redstone torch + wall torch, buttons.
- `common/src/main/resources/data/seamlessportals/tags/block/aperture_support.json` — **NEW**: `#minecraft:stone_bricks`, `minecraft:stone`, `minecraft:obsidian`, `#minecraft:planks`, `#minecraft:slabs` (a modest starter set; a pack widens it).
- `common/src/main/resources/data/seamlessportals/tags/block/aperture_forbidden.json` — **NEW**: `#minecraft:doors`, `#minecraft:beds`, `#minecraft:tall_flowers`, `#minecraft:shulker_boxes`, `minecraft:chest`, `minecraft:trapped_chest`, `minecraft:hopper`, `minecraft:barrel`.
- `assets/seamlessportals/lang/en_us.json` — eight `Refusal` keys (§4 table).

---

## 4. FAILURE-MODE TABLE

Every row marked **[FIX]** is a verifier-found defect whose fix is now mandatory.

| situation | behaviour | why it is safe |
|---|---|---|
| **destination unloaded at PLACE** | server returns `Refusal.DEST_UNLOADED`; client returns `NONE` (optimistic) and vanilla prediction rolls the ghost back | A place's outcome depends on an occupancy test that can change; deferring it would write a source-only half. **[FIX — Verifier B, F4]** the design's justification (`ChunkVisibility.java:24 portalLoadingRange = 48`) is **void**: that field is dead — my grep returns only its own declaration, corroborated by `migration/verify/chunk-loading.md:46` and `migration/inventory/chunk-loading.md:63` [V]. No claim is made about frequency; `seamMirrorProbe` counts it and the live round decides whether a hold is needed. |
| **destination unloaded at BREAK** | the clear is written to `SeamJournal` (a `SavedData` keyed on the destination dimension) and applied when the chunk next loads; the in-memory retry is `MyTaskList.withRetryNumberLimit(6000, …)` | **[FIX — both verifiers]** `withRetryNumberLimit` increments once per invocation (`common/src/main/java/qouteall/q_misc_util/my_util/MyTaskList.java:175-199` [V]) and `ServerTaskList` drains once per tick, so the design's 30 gave **1.5 s**, then a permanent orphan and a duplicated block; `ServerTaskList.java:15` [V] also drops pending tasks on server stop. A break's outcome is deterministic, so a journal is sound: it stores work, not a bond. The journal entry is `(GlobalPos, expected BlockState id)` and is deleted on application or on mismatch. |
| **destination unloaded, neighbour chunk ungenerated** | `mayPlace` and the flush require `McHelper.isServerChunkFullyLoaded` (`:513-525` [V2]) for **every chunk touched by `destPos` dilated by 1** (≤4 chunks in XZ); otherwise `DEST_UNLOADED` / journal | **[FIX — Verifier A, D7]** `canSurvive` and `setBlock(…, UPDATE_ALL)` read and write neighbours; a neighbour in an ungenerated chunk triggers blocking worldgen on the main thread inside a right-click. Testing only `destPos`'s own chunk did not prevent it. |
| **conflict (destination occupied by something else)** | hand placement → **refused**, no block, no sound, no item (`BlockItem.place:53-55`/`:63-65`, consume at `:89` [V2]); non-item writes → not mirrored, source half stands, logged | The refusal happens strictly before `placeBlock` and before `itemStack.consume`. Non-item writes are the design's stated §0.7 gap, unchanged, and now explicitly enumerated in §8. |
| **conflict, destination already exactly equal** | allowed, and the flush writes **once** | Required by the boundary-phase case where two source cells share one destination cell. |
| **f1 and f2 both bind the same destination cell (coincident phase)** | the flush deduplicates by destination `GlobalPos` within a seam group; `WRITE_COUNT` increments by 1 | **[FIX — Verifier A]** the design iterated `cell.bindings()` and would have written twice, contradicting its own RS-A3. |
| **overlapping portals, same cell, same facing** | slot marked ambiguous (`destDim = null`) → `Refusal.AMBIGUOUS`; the flush skips ambiguous bindings without writing | Deterministic; avoids the design's rejected `List.of(Vec3,…)` exact-double grouping key. |
| **seam group would chain across unrelated portals** | closure is restricted to one `clusterKey`; group ≤3 | **[FIX — Verifier A]** unbounded closure deleted blocks in a third party's portal, and the cap-8 truncation produced partial clears. |
| **same-dimension portals** | fully supported; `mirrorCell` uses `getDestDim()` (`Portal.java:714` [V2]) with no special case; ambiguity handled by the rule above | The self-match guard already exists at `NetherPortalLikeForm.java:126-129` [V] for generation. |
| **scaled / rotated / pitched portals** | bindings register with `destDim = null`; placement is **ALLOWED and simply not mirrored**, with a one-shot action-bar note; `-Dseamlessportals.seamStrictMirrorable=true` restores refusal | **[FIX — Verifier B, F6]** the design refused all placement, which is strictly worse than today and contradicts the binding pin "any block, any cell, at any height". There is no coherent counterpart cell across a scaled portal, so "ordinary space, no mirror" is the honest answer. Vanilla nether pairs are unaffected (`rotation == null`, `scale == 1.0`). |
| **portal removed with halves alive** | bindings vanish via `PORTAL_DISPOSE_SIGNAL` (`Portal.java:232`, emitted from `ip_onRemoved:500-502`, for every `RemovalReason` [V2]); `breakPortalOnThisSide` clears only cells still holding the placeholder (`BreakablePortalEntity.java:130-147`, guard at `:137` [V2]); **both halves survive as ordinary blocks** | Honours §0.4 — and **that is a duplication vector**: the mirrored half cost 0 items to create and drops 1 item when mined. **[FIX — both verifiers, A/D1 = B/F2]** It is not silently shipped: `SEAM_SEVER_POLICY` defaults to `KEEP_BOTH` (honours the pin) with `CLEAR_DEST` available, RS-A9 is extended to run the teardown, and §9 names it as the user-facing decision. |
| **mirror-write recursion** | `SeamMirror.withApplying` holds a `static boolean` across the whole `setBlock` + neighbour cascade; inner writes are **recorded, not enqueued**; after the guard releases, the group is reconciled **once**, non-recursively | **[FIX — Verifier A, D11]** the design left the guard's semantics unspecified and both readings were wrong: swallowing inner writes diverges silently (a torch beside the mirrored rail loses support), while not swallowing them bounces every tick. The guard is safe as a plain boolean because the driver rejects unless `level instanceof ServerLevel` **and** `server.isSameThread()`. `occ → occ` on the same block is never mirrored (rail shape resolve, wire power, waterlogging) — that is (b)/(c)'s job and is also the anti-oscillation guard. |
| **piston / dispenser / falling block / explosion / `/setblock` / `/fill`** | mirrored best-effort; on conflict, not mirrored; **no rollback** | Reverting an end-of-tick piston move leaves the piston's state machine believing it moved something that vanished. Stated as a known §0.7 gap in §9. `PushReaction.BLOCK` on the placeholder is located and specified but not shipped. |
| **water / lava reaching an aperture cell** | cannot enter a placeholder cell (edit #2); if a fluid does appear in a seam cell (bucket into an occupied cell), `isOccupant` returns **false** for any state with a non-empty `getFluidState()`, so it is never mirrored, and the flush treats the cell as non-vacant | **[FIX — Verifier B, F5/C4]** the design's §11.2 premise was wrong: `canBeReplaced(BlockState, Fluid) = state.canBeReplaced() \|\| !state.isSolid()` (`REF/net/minecraft/world/level/block/state/BlockBehaviour.java:253-255` [V]) and `calculateSolid()` returns false whenever the collision shape is empty (`BlockBehaviour.java:468-487` [V]); `PortalPlaceholderBlock` is `.noCollision()` (`PortalPlaceholderBlock.java:62` [V]) ⇒ **already fluid-replaceable today**. Without both fixes, frame-only integrity plus the driver would conjure a lava source in the other dimension, un-vetoed (`BucketItem` does not route through `BlockItem.place`). |
| **block entity placed in a seam cell** | **refused**, `Refusal.NO_BLOCK_ENTITY` (tag `aperture_forbidden` + `state.getBlock() instanceof EntityBlock`) | **[FIX — Verifier A, D2; overturns design §11.9]** `dst.setBlock` writes a *default, empty* BE. Break the mirrored empty shulker → the flush mirrors AIR back and deletes the **full** source shulker with zero drops. Unrecoverable, silent, in the other dimension. Two independent chests is not "honest" when one of them can delete the other's contents. |
| **door / bed / double plant (multi-cell)** | **refused**, `Refusal.MULTI_CELL` | **[FIX — both verifiers, A-minor = B/F9]** `BlockItem.canPlace(ctx, state)` sees only `context.getClickedPos()` (`REF/net/minecraft/world/item/BlockItem.java:132-135` [V2]); the second cell is written from `setPlacedBy` long after both gates, producing a literal silent half-place. |
| **the 233-tick sweep** | still runs (`BreakablePortalEntity.java:174` `isNotified \|\| gameTime % 233 == getId() % 233` [V]); **plus** the driver now calls `notifyPlaceholderUpdate()` on every `BreakablePortalEntity` within rough-range 2 whenever a registered **frame** cell changes | **[FIX — Verifier A, D3]** `notifyPlaceholderUpdate` has exactly **one** caller in the tree: `PortalPlaceholderBlock.updateShape` at `PortalPlaceholderBlock.java:119` [V], reached only from a cell that still *holds a placeholder*, and only when `direction.getAxis() != axis` (`:110` [V]). A fully occupied aperture therefore leaves no placeholder and no notify trigger, and the four cluster entities' sweeps are up to 233 ticks apart (`getId() % 233`). That window lets a player break and restore obsidian so that only some of `{f1,f2,t1,t2}` tear down — a permanently one-facing cluster with a half-present seam group and no self-heal. The driver's explicit notify restores today's ~0-tick window. Target is `BreakablePortalEntity.notifyPlaceholderUpdate()`, `public` at `BreakablePortalEntity.java:149` [V] — **zero IP-core edits.** |
| **teardown races the flush** | the flush skips any queued cell whose `lookup` returned null at flush time, and vacuum normalisation is gated on `lookup != null` **and** on the cluster not being mid-teardown (`shouldBreakPortal == false`) | **[FIX — Verifier A, D3 second consequence]** otherwise `breakPortalOnThisSide`'s AIR writes enqueue, and f2's still-live bindings normalise the placeholders straight back, undoing the teardown f1 just performed. |
| **re-ignition inside a live aperture** | `checkPortalGeneration` refuses when `startingPos` is a registered seam cell (IP-core edit #10) | **[FIX — Verifier A, D6]** nothing else prevents it: `NetherPortalLikeForm.perform` (`:38-53` [V]) has no existing-portal check, and the only thing stopping re-ignition today is that `getAreaPredicate()` is `isAir` and the aperture is full of placeholders. Without the guard, one empty cell in a whitelisted aperture spawns a second cluster at the same coordinates → `isPortalPaired()` sees `revs.size() > 1` → **all 8 portals destroyed** and every cell becomes permanently ambiguous. |
| **the four-entity cluster (f1,f2,t1,t2)** | dedupe is structural: `t1`/`t2` are enumerated against *their* level and never cover a source cell; `f1`/`f2` have exactly opposite normals (`PortalManipulation.java:144` [V2]) and write disjoint `(cell, facing)` slots. There is no per-portal driver — the driver is per block write | Order-independent, restart-stable, no `computeIfAbsent` race, no UUID ordering. Destination dedupe (row 6 above) closes the double-write. |
| **server stop / world switch in the same JVM** | `SeamRegistry`, `SeamMirror`'s queue and `ANY_SEAMS` are cleared on `IPGlobal.SERVER_CLEANUP_EVENT` (`common/src/main/java/qouteall/imm_ptl/core/IPGlobal.java:37` [V], invoked from `MixinMinecraftServer.java:23` [V]) | **[FIX — Verifier A, D4]** `PORTAL_DISPOSE_SIGNAL` is driven from `Entity.setRemoved` (`qouteall/imm_ptl/core/mixin/common/mc_util/MixinEntity_U.java:25-33` [V2]), which `PersistentEntitySectionManager.saveAll` only reaches for chunks that are `HIDDEN`; portals in still-visible chunks at shutdown never fire it, leaving world A's bindings live while world B loads. `ResourceKey<Level>` is value-equal across worlds, so the stale entries would produce bogus refusals and cross-world writes. |
| **client relog / disconnect** | the client registry is cleared on `IPCGlobal.CLIENT_CLEANUP_EVENT` (`common/src/main/java/qouteall/imm_ptl/core/IPCGlobal.java:44` [V], invoked from `qouteall/imm_ptl/core/mixin/client/MixinMinecraft.java:195` [V]) | **[FIX — Verifier B, F11]** regression-checklist item 12 names this exact bug class; the design added static client state with no teardown. Precedents: `ClientPortalAnimationManagement.java:30`, `CrossPortalEntityRenderer.java:105`, `GlobalPortalStorage.java:176` [V]. |
| **frame broken, support cube + rail present, player re-lights** | ignition **succeeds**: the area predicate accepts air ∪ `#aperture_passthrough` ∪ `#aperture_support`, and `ignitionAreaAcceptable` (edit #9) enforces the real rule position-awarely | See §4.1 below — this is the one place where the two verifiers disagreed, and it is the single most important correction to the design. |
| **`-Dseamlessportals.disableAperturePassthrough=true`** | every one of the 10 IP-core edits reverts to IP behaviour | **[FIX — Verifier B, F3]** the design's edits 6 and 7 read only `DISABLE_SURVIVOR_SKIP`, with no `DISABLED` term. Under master-ON a surviving rail would still be skipped by `fillInPlaceHolderBlocks`, that cell would never get a placeholder, and the restored IP area scan would tear the freshly-lit portal and its twin down on the first tick. Two characters; falsifies the design's headline lever claim and RS-A19. |

### 4.1 THE ONE PLACE THE VERIFIERS DISAGREED — ignition over a support cube

Both flagged the same conflict (§0.2 "any height" + §7 "place a solid block below the rail" + §0.3 "no full cubes on the whitelist" are jointly unsatisfiable), **with different mechanisms**:

- **Verifier A (D5)** claims the clear loop at `NetherPortalLikeForm.java:63-68` deletes the stone, the rail then pops, and the AIR write mirrors across and deletes the nether rail too.
- **Verifier B (F1)** claims the frame is **permanently un-relightable**, because `BlockPortalShape.findAreaBreadthFirst` rejects stone and `findFrameShape` returns null.

**B is right and A is wrong, and I can cite it exactly.** `NetherPortalLikeForm.perform` runs `findFrameShape` at `:46-49`, bails at `:51-53` when it returns null, and only *then* reaches the clear loop at `:63-68` [V]. The area predicate is consumed inside `findFrameShape` (`NetherPortalGeneration.java:267-268` [V] → `BlockPortalShape.findAreaBreadthFirst:232` [V]). With stone in the aperture the predicate fails, `fromShape == null`, and A's clear loop is **unreachable**. A's mechanism only becomes live once the support cube is whitelisted — at which point `isSurvivor` protects it and the deletion never happens either. So one fix kills both readings.

**Fix (goes in), and it also fixes A's mechanism:**

1. Widen `areaPredicate()` to `s -> s.isAir() || s.is(PASSTHROUGH) || s.is(SUPPORT)`.
2. Add the position-aware post-check (IP-core edit #9), which is where the real §0.3 rule lives — the predicate cannot express it because `getAreaPredicate()` returns `Predicate<BlockState>` with no position (`NetherPortalLikeForm.java:205` [V]; the wrap sites at `:123` and `AbstractDiligentForm.java:34` [V] do have coordinates, but `:48` passes the bare predicate into `findFrameShape`, so making it position-aware would mean changing `findFrameShape`, `findAreaBreadthFirst:232` and `FastBlockPortalShape.matchShape:325` [V] — rejected as a large deviation):

```java
public static boolean ignitionAreaAcceptable(ServerLevel w, BlockPortalShape shape) {
    if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_IGNITION_WHITELIST) {
        return shape.area.stream().allMatch(p -> w.getBlockState(p).isAir());
    }
    int air = 0;
    for (BlockPos p : shape.area) {
        BlockState s = w.getBlockState(p);
        if (s.isAir()) { air++; continue; }
        if (s.is(PASSTHROUGH)) continue;
        if (s.is(SUPPORT)) {
            BlockPos up = p.above();
            if (shape.area.contains(up) && w.getBlockState(up).is(PASSTHROUGH)) continue;  // rail floor
        }
        return false;
    }
    return air * 2 >= shape.area.size();   // a wall is not a portal
}
```
3. `isSurvivor(Level, BlockPos)` becomes position-aware on the same rule, so edits 7 and 8 protect the support cube as well as the rail.

This admits the §7 build, rejects a solid wall, and needs one new IP-core line. **It is still a reconciliation of three user pins that Claude made, not the user** — §9 lists it as the decision to confirm.

---

## 5. LEVERS

`common/src/main/java/com/warwa/seamlessportals/passthrough/AperturePassthroughLever.java` — plain holder class, never a mixin, never the probe class; the rationale is already in that file's javadoc [V]. Retire `SUPPRESS_TEARDOWN` and `TEARDOWN_TEST` (currently the 2nd and 3rd of its three fields [V]) in the same commit as IP-core edit #3.

**Fixes — DEFAULT-ON, disabled by the property. All eight are nested inside `DISABLED`, so `-PdisableAperturePassthrough=true` alone restores IP behaviour at all 10 edit sites.**

| field | property | reverts |
|---|---|---|
| `DISABLED` | `seamlessportals.disableAperturePassthrough` | master |
| `DISABLE_SEAM_MIRROR` | `seamlessportals.disableSeamMirror` | destination write + veto |
| `DISABLE_SEAM_VETO` | `seamlessportals.disableSeamVeto` | mirror on, refuse-on-conflict off |
| `DISABLE_IGNITION_WHITELIST` | `seamlessportals.disableIgnitionWhitelist` | area predicate + post-check back to `isAir` |
| `DISABLE_SURVIVOR_SKIP` | `seamlessportals.disableSurvivorSkip` | edits 7+8 back to unconditional wipe/fill |
| `DISABLE_VACUUM_NORMALISE` | `seamlessportals.disableVacuumNormalise` | emptied cells stay air |
| `STRICT_MIRRORABLE` | `seamlessportals.seamStrictMirrorable` | **DEFAULT-OFF**: refuse placement into scaled/rotated apertures instead of allowing-without-mirror |
| `SEVER_CLEAR_DEST` | `seamlessportals.seamSeverClearDest` | **DEFAULT-OFF**: on portal dispose, clear the destination half (ledger-exact, overrides §0.4) |

**Probes — DEFAULT-OFF**, server-thread, rate-limited through `LimitedLogger` (precedent `BreakablePortalEntity.java:224` [V2]), self-disarming on throw.

| field | property |
|---|---|
| `CENSUS_ENABLED` | `seamlessportals.apertureCensusProbe` (existing, retained) |
| `SEAM_MAP_PROBE` | `seamlessportals.seamMapProbe` — one-shot per portal: every `(cell,facing) → (destDim,destPos,rotation,mirrorable)` + seam group, as ready `/setblock` targets |
| `SEAM_MIRROR_PROBE` | `seamlessportals.seamMirrorProbe` — `WRITE`/`CONFLICT-SKIP`/`UNLOADED-DENY`/`JOURNALLED`/`NORMALISE`/`NOT-MIRRORABLE`/`AMBIGUOUS` + every veto refusal |
| `SEAM_AIM_PROBE` | `seamlessportals.seamAimProbe` — **redesigned** (Verifier A, D9): latches on the hijack firing **during an actual placement attempt**, not on 1 Hz proximity. Reads the `public static BlockManipulationClient.remotePointedDim` (`:31` [V2]) in the client tick that issues the use-item, with the hit distance and the `0.2` margin |
| `SEAM_RECONCILE_PROBE` | `seamlessportals.seamReconcileProbe` — logs, does not repair, every one-sided group |
| `SEAM_LEDGER_PROBE` | `seamlessportals.seamLedgerProbe` — **NEW**: counts items consumed vs `ItemEntity`s spawned per seam group across its whole lifetime, including severance |

**BOTH build.gradle blocks.** Every one of the 14 rows must appear in `fabric/build.gradle` in **both** the `client` block (existing RS rows at `:129-131` [V2]) and the `crossingGametest` block (`:267-273` [V2]) — 28 rows total — and the four retired rows (`apertureSuppressTeardown`, `apertureTeardownTest` × 2 blocks) must be deleted. Format:

```gradle
if (project.findProperty('disableAperturePassthrough') == 'true') { vmArg('-Dseamlessportals.disableAperturePassthrough=true') }
if (project.findProperty('disableSeamMirror')          == 'true') { vmArg('-Dseamlessportals.disableSeamMirror=true') }
if (project.findProperty('disableSeamVeto')            == 'true') { vmArg('-Dseamlessportals.disableSeamVeto=true') }
if (project.findProperty('disableIgnitionWhitelist')   == 'true') { vmArg('-Dseamlessportals.disableIgnitionWhitelist=true') }
if (project.findProperty('disableSurvivorSkip')        == 'true') { vmArg('-Dseamlessportals.disableSurvivorSkip=true') }
if (project.findProperty('disableVacuumNormalise')     == 'true') { vmArg('-Dseamlessportals.disableVacuumNormalise=true') }
if (project.findProperty('seamStrictMirrorable')       == 'true') { vmArg('-Dseamlessportals.seamStrictMirrorable=true') }
if (project.findProperty('seamSeverClearDest')         == 'true') { vmArg('-Dseamlessportals.seamSeverClearDest=true') }
if (project.findProperty('seamMapProbe')               == 'true') { vmArg('-Dseamlessportals.seamMapProbe=true') }
if (project.findProperty('seamMirrorProbe')            == 'true') { vmArg('-Dseamlessportals.seamMirrorProbe=true') }
if (project.findProperty('seamAimProbe')               == 'true') { vmArg('-Dseamlessportals.seamAimProbe=true') }
if (project.findProperty('seamReconcileProbe')         == 'true') { vmArg('-Dseamlessportals.seamReconcileProbe=true') }
if (project.findProperty('seamLedgerProbe')            == 'true') { vmArg('-Dseamlessportals.seamLedgerProbe=true') }
if (project.findProperty('apertureCensusProbe')        == 'true') { vmArg('-Dseamlessportals.apertureCensusProbe=true') }
```

---

## 6. TEST PLAN

New legs in `fabric/src/main/java/com/warwa/seamlessportals/fabric/gametest/CrossingSmoke.java` [V], driven off the portal pair leg 6a generates. They **assert**, so they run unconditionally. **Every leg stages in its own frame away from portal B's window and undoes it in a `finally`.**

| leg | observable assertion |
|---|---|
| **RS-A20** rotation table | **Run first, before any mirror code lands.** For each of the four cardinal portal orientations, place an oriented block (repeater facing NORTH) and assert the mirrored state's `FACING` equals a brute-force value computed in the test from `transformLocalVecNonScale`. Falsifies §2.6. |
| **RS-A15** phase offset, brute-forced | **Run second.** Hand-spawn a `Portal` pair, source plane mid-block, destination plane on a boundary. Assert the seam group for a source cell is exactly `{C, D₁, D₂}`, equals a brute-force per-facing overlap-integral reference, and that breaking `D₁` clears `C` and `D₂`. |
| **RS-A1** bijection | `{ mirrorCell(f1,c) : c ∈ owShape.area }` equals `netherShape.area` as a set, equal cardinality. |
| **RS-A2** involution | `mirrorCell(t1, mirrorCell(f1, c)) == c` for every cell, both directions. |
| **RS-A3** dedupe | Registry holds exactly `area.size()` **cells** for the OW, each with two bindings whose destinations are identical; `SeamMirror.WRITE_COUNT` increments by exactly **1** per source write. |
| **RS-A4** placement lands | `player.gameMode.useItemOn` with a rail against the sill's UP face resolves to the bottom aperture cell; within 5 ticks it is `minecraft:rail` (before: `immersive_portals:nether_portal_block`). |
| **RS-A5** mirror appears | Within 2 ticks, the nether cell is a rail at a position equal to a brute-force recomputation from `getOriginPos/getDestPos/getNormal/getContentDirection`. |
| **RS-A6** no teardown | 250 ticks later (> 233, `BreakablePortalEntity.java:174` [V]) all four entities alive, rail present; record `getBrightness(LightLayer.BLOCK, cell)`. |
| **RS-A6b** notify preserved | **NEW (D3).** Fill **every** aperture cell, then break one frame obsidian. Assert **all four** entities are gone within 5 ticks — not 233. Directly falsifies the notify-trigger loss. |
| **RS-A7** any height, with support | Stone against a jamb inner face at `innerAreaBox.l.getY()+1`, then a rail on it. Both present in both dims; a rail at that height without the stone is **refused**, no item consumed. |
| **RS-A8** break mirrors + normalises | `/setblock <owCell> air` → within 2 ticks the nether mirror **and** the OW cell hold the placeholder. Repeat under `-PdisableVacuumNormalise=true`, assert both air. |
| **RS-A9** item ledger, **including severance** | **EXTENDED (D1/F2).** Place 1 rail, break the frame, mine both surviving halves, count `ItemEntity`s. Assert the count matches the active `SEAM_SEVER_POLICY`: `KEEP_BOTH` ⇒ **2** (documented duplication), `-PseamSeverClearDest=true` ⇒ **1**. The leg's job is to make the number visible, not to pass a moral judgement. |
| **RS-A10** refuse on conflict | Stage `/setblock <netherMirror> stone`, attempt the player-path place → non-consuming, OW unchanged, nether still stone. |
| **RS-A11** conflict-equal allowed | Stage a rail in the destination, place a rail from the source → succeeds, `WRITE_COUNT` unchanged. |
| **RS-A12** unloaded → deny, no force-load | Destination chunk unloaded: server `mayPlace` = `DEST_UNLOADED`, client = `NONE`, source unchanged, destination chunk **still unloaded**. |
| **RS-A12b** unloaded break survives | **NEW.** Break with the destination unloaded, force **> 6000 ticks** of simulated absence plus a save/load cycle, then load the destination → the counterpart clears from `SeamJournal`. |
| **RS-A13** survivor + re-light | Break a frame obsidian → all four die, placeholder cells become AIR, rail survives in **both**. Restore obsidian, re-ignite → new cluster **and** rail intact both sides. |
| **RS-A13b** survivor + support + re-light | **NEW (F1/D5).** Same as A13 but built per §7 (stone + rail). Assert the re-light **succeeds** and that **both** the stone and the rail survive in both dimensions. The single leg that settles the verifier disagreement. |
| **RS-A13c** wall still refuses ignition | **NEW.** Fill the aperture entirely with `#aperture_support` cubes, break and re-light → ignition **fails**. Proves "you cannot light a portal through a wall" survives the widened predicate. |
| **RS-A14** ignition reconcile | Mine the nether rail while down, re-ignite → OW rail copied in (igniting side wins), one-shot log. |
| **RS-A16** grid gate | `setScaling(2.0)`: bindings register with `destDim == null`; placement **succeeds and is not mirrored** at default levers; under `-PseamStrictMirrorable=true` it is refused. |
| **RS-A17** cross-portal interaction preserved | Aim through an **empty** aperture cell at a staged destination block, drive a break, assert it is gone. Zero coverage of this shipped feature exists today; must land regardless. |
| **RS-A18** lever reverts | Under `-PdisableAperturePassthrough=true`, a `/setblock` rail into an aperture tears the portal and its twin down within 233 ticks. Replaces the retired `TEARDOWN_TEST`. |
| **RS-A19** regression gate | Existing 8 legs pass at defaults **and** under the master lever the suite is indistinguishable from the pre-change build. **Will fail without the F3 fix.** |
| **RS-A21** fluid tight | **NEW (F5).** `/setblock` lava adjacent to an aperture, tick 200: no aperture cell holds lava, no nether cell holds lava, all four portals alive. |
| **RS-A22** BE + multi-cell refused | **NEW (D2/F9).** Chest and door placements into aperture cells are refused with `NO_BLOCK_ENTITY` / `MULTI_CELL`, no item consumed, no destination write. |
| **RS-A23** no re-ignition inside a live aperture | **NEW (D6).** Whitelist-fill an aperture leaving one air cell, flint-and-steel it → no new portal entity spawns, existing four alive. |
| **RS-A24** cluster teardown clears statics | **NEW (D4/F11).** After a simulated server-stop event, `SeamRegistry.anySeams()` is false and every per-level index is empty. |

**Live-round rows** (not drivable from the harness):
1. **Aim usability, `-PseamAimProbe=true`.** Verifier A (D9) argues the hijack fires on the far half of an occupied cell's **top face** — the most common placement gesture there is — putting the block in the nether. Verifier B traced the same geometry and concluded jamb-inner-face and top-face aims are workable, with only a ~0.2-wide band lost. **They disagree and neither observed it.** The probe exists to settle it; do not ship the fallback (`&& !AperturePassthroughClient.seamBlockWinsTargeting(portal)` appended at `BlockManipulationClient.java:81` [V2]) until the log says which is right.
2. **Unloaded-destination break** with `/forceload remove`: assert a `JOURNALLED` line and a write when the chunk returns.
3. **Aperture darkness** with a rail line through the aperture — record the actual brightness, judge whether it is acceptable.

---

## 7. STAGING

Each step ends with a gate. Do not start step *n+1* until step *n*'s gate is green.

| step | work | gate |
|---|---|---|
| **0** | Lever class: retire `SUPPRESS_TEARDOWN`/`TEARDOWN_TEST`, add the 8 fix + 6 probe fields; 28 build.gradle rows; delete the 4 retired rows | Build compiles; existing 8 legs pass |
| **1** | `SeamMap` only (§2.2/2.3/2.5/2.6). No registry, no mixins, no IP-core edits | **RS-A20 and RS-A15 pass.** If RS-A20 fails, the rotation table in §2.6 is wrong — fix it before anything else |
| **2** | `SeamRegistry` + `SeamIndexHolderMixin` + seeding on `SERVER_PORTAL_TICK_SIGNAL` (`Portal.java:229`, invoked `:1001` [V2]) / `CLIENT_PORTAL_TICK_SIGNAL` (`:227`/`:993` [V2]) / `PORTAL_DISPOSE_SIGNAL` (`:232` [V2]) + both cleanup events (`IPGlobal.java:37` [V], `IPCGlobal.java:44` [V]). Probes `seamMapProbe`, `seamReconcileProbe` | RS-A1, RS-A2, RS-A3 (registry half), RS-A24 pass; `seamMapProbe` output hand-checked against a live portal |
| **3** | IP-core edits 1, 2, 3, 4 (replaceability + fluid tightness + frame-only integrity). No mirror yet | RS-A4, RS-A6, RS-A18, RS-A21 pass; **live round #1** with `seamAimProbe` armed → settles §11 R1 / D9 |
| **4** | IP-core edits 5–9 + `aperture_passthrough` / `aperture_support` / `aperture_forbidden` tags + `ignitionAreaAcceptable` | RS-A13, **RS-A13b**, RS-A13c, RS-A14 pass |
| **5** | Veto (`MixinBlockPlaceContext`, `MixinBlockItem`) including BE / multi-cell / fluid / dilated-chunk refusals | RS-A10, RS-A11, RS-A12, RS-A16, RS-A22 pass |
| **6** | Driver + deferred flush + `withApplying` + destination dedupe + vacuum normalisation + the frame-notify call | RS-A5, RS-A3 (`WRITE_COUNT`), RS-A6b, RS-A8, RS-A9 pass |
| **7** | `SeamJournal` + IP-core edit 10 (re-ignition guard) | RS-A12b, RS-A23 pass |
| **8** | RS-A17 + RS-A19 + full-suite run at defaults **and** under `-PdisableAperturePassthrough=true` | Both runs green; **live round #2** with `seamLedgerProbe` and `seamMirrorProbe` armed |

---

## 8. RESIDUAL RISK

**Still unverified — do not treat as checked:**
- `Rotation.CLOCKWISE_90`'s direction convention (§2.6). RS-A20 is the falsifier and runs first.
- Whether `AbstractDiligentForm.perform` (`common/src/main/java/qouteall/imm_ptl/core/portal/custom_portal_gen/form/AbstractDiligentForm.java:25` [V] — it has its own body, so it does **not** simply inherit `NetherPortalLikeForm.perform` as the design claimed) routes through `NetherPortalGeneration.checkPortalGeneration`. If it does not, IP-core edit #10 and edit #9 need a second insertion point in that file.
- A stable cluster identifier for `clusterKey` (§2.7). The `min(uuid)` fallback is specified but the `PortalExtension` accessor name is not confirmed.
- `TicketTypeInvoker`'s method name and the `TicketType.FLAG_*` constants — nothing in (a) depends on them; they are (c)'s problem.
- The design's `ENTITY_PORTAL_MIGRATION_BRIEFING.md` and `REDSTONE_RECON.md` line citations (§6, §4c, `:167-179`) are inherited, not re-opened.
- Whether `PersistentEntitySectionManager.saveAll`'s branch really leaves visible-chunk portals un-`setRemoved` (Verifier A, D4, cited to `REF/net/minecraft/world/level/entity/PersistentEntitySectionManager.java:269-287`). The cleanup-event fix is correct regardless of whether the specific branch analysis holds, so nothing depends on settling it.

**Knowingly-shipped requirement gaps, both surfaced rather than hidden:**
- Non-item writes (piston, dispenser, explosion, `/setblock`, `/fill`, gravity) mirror best-effort and, on conflict, leave a source-only half — the literal negation of §0.7. No rollback, because reverting an end-of-tick piston move corrupts the piston's state machine.
- A frame break with occupants alive duplicates blocks (`KEEP_BOTH` default), because §0.4 and §0.5 jointly require it. `SEVER_CLEAR_DEST` is the one-flag alternative.
- Same-tick two-sided player breaks duplicate one block (Verifier A, D10). One-tick window, two actors; the only true fix is a synchronous mirror, which (c) will build.
- The §0.3 whitelist rule in §4.1 is **a Claude-made reconciliation of three user pins**, not a user decision. Confirm it before step 4.

**The single most likely thing to be wrong: the targeting bet (§2.2 / R1 / A's D9 / B's counter-analysis).** The two verifiers examined the same geometry and reached opposite conclusions — A says half of every top-face click lands the block in the nether, B says only a ~0.2-wide band near the plane is lost. Neither observed it; both reasoned from `BlockManipulationClient.java:81`'s `distanceToPortalPointing < getCurrentTargetDistance() + 0.2`. The failure is silent and wrong-dimensional, not a crash, and it lands on the single most common gesture the feature exists to support. **Step 3's live round with `seamAimProbe` armed is the gate; if A is right, ship the located `seamBlockWinsTargeting` fallback and re-run RS-A17 to confirm cross-portal interaction still works through empty cells.**

Runner-up: the seam group under sub-block phase (§2.7). The coincident case is provable and hand-verified; the phase-offset group sizes, the ≤3 bound and the break-clears-the-rest claim rest on paper arithmetic against a geometry that does not exist anywhere in the repo today. RS-A15 settles it and runs in step 1, before any of §3.5 is written.