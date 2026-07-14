# S6 — Portal core + shapes + animation (U4) — port-note

**Stage:** S6 (EXECUTION_PLAN §3 S6). **Effort:** XL — the geometry/NBT heart of the migration.
**Unit:** U4 — the `Portal` family (`Portal`, `Mirror`, `BreakableMirror`, `EndPortalEntity`) +
`PortalState`/`PortalExtension`/`PortalManipulation`/`PortalUtils`, `shape/*`, `util/*`, the whole
`animation/` package (14 files), `PortalPlaceholderBlock`, `LoadingIndicatorEntity`,
`PortalRenderInfo`; **co-ports** `nether_portal/BlockPortalShape` (cycle 14) and the collision trio
`CollisionHelper` + `PortalCollisionHandler` + `PortalCollisionEntry` (cycle 15).
**Discipline:** D2 verbatim `qouteall.*` paths · D4.2 probe-ledger triage (the probe, not the paper
ledger, is authoritative) · D4.3 source-diff gate · D4.4 sign-derivation ·
**R11 entity decisions (i)–(v) owned + recorded here** · §S6(a) contents · §S6(b) forward-ref debt
union. All 39 files land HELD (no carve-in change — `IpHeldPaths` already covers
`imm_ptl/core/**`), unregistered until S13.
**IP source root (1.21.3, Mojang mappings):** `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`
**26.2 evidence root (authoritative):** `C:/Users/warwa/ModDev/mc262-ref`
**API-map amended by this stage:** `migration/api-map/portal-core.md` — the **S6 amendment block**
(+1 CHANGED FixGap: `Entity.hurtServer` abstract; §5 below; post-amendment tally
**3 GONE / 25 CHANGED / 108 SAME / 5 FABRIC-API**). Two governed deviations recorded in this
note, not the api-map: the disk-NBT bridge mechanism (§5.2) and the `hurtServer` inline
cross-reference.

This note is the **S6 commit-5 deliverable**. It consolidates the four working fragments
(`migration/fragments/S06-{core,shapes,animation,blocks}.md`, deleted at stage end) plus the
FixGap/adversarial-verify evidence, and **supersedes the non-canonical intermediate draft
`S06-core.md`** (same content, wrong filename, missing the dedicated animation-quirks and
verification-tier sections, and carrying an imprecise "row-0/1/2" JOML axis phrasing this note
corrects to column-form — §3). It resolves the adversarial-verify advisories folded in as
V1/V2 (§7 records the verifier tier honestly: the pass ran on **Opus**, Fable rate-limited).

---

## 0. Stage result (build + probe evidence)

| Gate | Command | Result |
|---|---|---|
| Shipping build | `:common:compileJava :fabric:compileJava` (`ip_scc_closed=false`) | **BUILD SUCCESSFUL** — all 39 U4 files held, invisible to javac |
| Math harness | `:common:test` | **BUILD SUCCESSFUL** — S2 DQuaternion/Plane suite up-to-date/green |
| Compile probe | `:common:compileJava -Pip_scc_closed=true` | **BUILD FAILED, 405 errors** — expected; every error maps to the documented S6(b) forward-ref union or the S2/S4/S5 carry-over baseline (§6). **Zero errors are translation slips on the U4 slice.** |

Held-paths coverage required **no `IpHeldPaths` edit** (`imm_ptl/core/**` already covered by
`MAIN_HELD_PATHS`); all 39 U4 files arrive default-held, so the shipping gate stays green because
javac never sees them.

**Interim per-slice probe counts (during the slice-by-slice port, before consolidation):** slice A
(core) 406, slice C (animation) 550, all measured at different landing points and before the
`hurtServer` FixGap; the **405** figure is the final count after all four slices landed and both
FixGaps (`hurtServer`, `AbstractMinecart`) were applied. The count moves only with landing order,
never with a translation slip.

**S6 slice = 39 files:**
- **16 portal top-level + `util/`:** `Portal`, `PortalState`, `PortalExtension`,
  `PortalManipulation`, `PortalUtils`, `PortalLike`, `PortalRenderInfo`, `PortalPlaceholderBlock`,
  `LoadingIndicatorEntity`, `Mirror`, `BreakableMirror`, `EndPortalEntity`, `GeometryPortalShape`,
  `IntraClusterRelation`, `util/PortalLocalXY`, `util/PortalLocalXYNormalized`.
- **5 `shape/*`:** `PortalShape`, `RectangularPortalShape`, `BoxPortalShape`, `SpecialFlatPortalShape`,
  `PortalShapeSerialization`.
- **14 `animation/*`:** `AnimationContext`, `AnimationResult`, `AnimationView`, `TimingFunction`,
  `StableClientTimer`, `ClientPortalAnimationManagement`, `PortalAnimationDriver`, `PortalAnimation`,
  `NormalAnimation`, `RotationAnimation`, `DefaultPortalAnimation`, `DeltaUnilateralPortalState`,
  `UnilateralPortalState`, `OscillationAnimation`.
- **4 co-ports:** `collision/{CollisionHelper,PortalCollisionHandler,PortalCollisionEntry}`,
  `portal/nether_portal/BlockPortalShape`.

---

## 1. Diff-gate record (D4.3) — per file

Each file was `cp`'d byte-for-byte from IP (CRLF preserved) and edited **only** by count-asserted
26.2-translation hunks. `git diff --no-index <IP> <DST>` shows **no whitespace-only hunk** on any
file and is **symmetric N/N** (pure line-for-line substitution — zero added/removed lines) on every
non-identical file. **20 of 39 files are byte-identical to IP** (marked IDENTICAL). Every
non-identical hunk traces to a `portal-core.md` / `portal-animation.md` row (Cn/Gn/Fn or animation
touchpoint #n) or one of the two global renames stated once in the api-map header
(`ResourceLocation`→`Identifier`; `net.minecraft.Util`→`net.minecraft.util.Util`).

### Portal core + util

| File | +/− | Hunk classes (traceable rows) |
|---|---|---|
| `Portal.java` | +151 / −83 | global renames; **R11(i)** `createPortalEntityType(id)` restructure + `portalEntityTypeKey`/`ResourceKey.create` (C10/F1); **R11(iv)** `getBoundingBox()` override REMOVED → `makeBoundingBox(Vec3)` + eager `setBoundingBox(makeBoundingBox())` pushes in `updateCache`/`refreshDimensions` (G1/C2); **R11(v)** `ValueInput`/`ValueOutput` bridge overrides + NBT-getter translations `getXxxOr`/`getListOrEmpty`/`keySet` (C1/C3/C4/C5/C6/C7), rotation double-write/float-read quirk kept; **[S6] FixGap** `hurtServer` (§5.1); `((StringTag)t).getAsString()`→`.value()` ([S6-A2]); `level().getServer()` ×3 (C8); `EntitySpawnReason.LOAD` (C9); `ChunkPos.containing`/`.x()/.z()` (C21); `Direction.getApproximateNearest` ×2 (C18); `getUnitVec3i()` (C19); F3 payload→`ClientboundCustomPayloadPacket` seam + S0 `Event` seam import; `AbstractMinecart` package move `vehicle.`→`vehicle.minecart.` (C24); held forward-imports left verbatim |
| `PortalState.java` | +11 / −11 | `ResourceKey.location()`→`.identifier()` ×2 (recurring A/C13); NBT `getStringOr`/`getDoubleOr`/`getCompoundOrEmpty`/`getBooleanOr` (C7) |
| `PortalExtension.java` | +21 / −14 | UUID codec `read/store(name, UUIDUtil.CODEC)` (G2); NBT `getDoubleOr`/`getBooleanOr` (C3/C7); rides Portal's tag — no `ValueInput/Output` override here |
| `PortalManipulation.java` | +10 / −10 | Tuple→`Pair` import + `.getA()/.getB()`→`.getFirst()/.getSecond()` (G3/E); `create(world)`→`create(world, EntitySpawnReason.LOAD)` ×4 (C9); `Direction.getNormal()`→`getUnitVec3i()` ×2 (C19); held `PortalAPI`/`PortalCommand` forward-imports verbatim |
| `PortalUtils.java` | **0 / 0** | **IDENTICAL** (held `GlobalPortalStorage` forward-import verbatim) |
| `PortalLike.java` | **0 / 0** | **IDENTICAL** (`@Environment` unresolved in-probe = loader-facade debt) |
| `PortalRenderInfo.java` | +2 / −2 | **C15** `Minecraft.getInstance().getProfiler()`→`Profiler.get()` (import swap + call site) |
| `PortalPlaceholderBlock.java` | +8 / −1 | `noCollission()`→`noCollision()` (C22); `.setId(ResourceKey.create(Registries.BLOCK, Identifier…))` on the `Properties.of()` chain (C23); `BreakablePortalEntity` held forward (S13) |
| `LoadingIndicatorEntity.java` | +26 / −12 | **R11(i/iii)** `EntityType.Builder.of(...).sized(1,1).fireImmune().clientTrackingRange(6).updateInterval(20).build(ResourceKey…)` replacing IP `FabricEntityTypeBuilder…trackable(96,20).build()` (F1/C10); **[S6] FixGap** `hurtServer` (§5.1); `gui.hud.setOverlayMessage` (C16); `readAdditionalSaveData(ValueInput)`/`addAdditionalSaveData(ValueOutput)` empty-body signatures (C1, no NBT → no bridge/quirk) |
| `Mirror.java` | +2 / −1 | **R11(i)** `createPortalEntityType(Mirror::new, portalEntityTypeKey("mirror"))` (id-at-build) |
| `BreakableMirror.java` | +19 / −18 | Tuple→`Pair` (G3/E); key (R11 i); `isClientSide`→`isClientSide()` (C17); `create(...LOAD)` (C9); `getInt`→`getIntOr` ×6 / `getCompoundOrEmpty` / `getBooleanOr` (C7); `Direction.getNormal()`→`getUnitVec3i()` ×3 (C19) |
| `EndPortalEntity.java` | +11 / −9 | `Arrow` package move + `EndDragonFight`→`EnderDragonFight` (C24); key (R11 i); `getBottomCenter()`→`Vec3.atBottomCenterOf` (C20); `create(...LOAD)` (C9); `getServer()`→`level().getServer()` ×2 (C8); `IEEndDragonFight` held forward (S10) |
| `GeometryPortalShape.java` | +12 / −12 | `ListTag.getDouble(int)`→`getDoubleOr(int,0)` ×12 (C6/animation #2 — Optional element getters) |
| `IntraClusterRelation.java` | **0 / 0** | **IDENTICAL** (pure enum) |
| `util/PortalLocalXY.java` | **0 / 0** | **IDENTICAL** |
| `util/PortalLocalXYNormalized.java` | **0 / 0** | **IDENTICAL** |

### shape/

| File | +/− | Hunk classes |
|---|---|---|
| `PortalShape.java` | **0 / 0** | **IDENTICAL** (`@Environment` in-probe only) |
| `RectangularPortalShape.java` | **0 / 0** | **IDENTICAL** — the winding-carrying shape (§3) is byte-verbatim |
| `BoxPortalShape.java` | +1 / −1 | `getBoolean("facingOutwards")`→`getBooleanOr("facingOutwards", false)` (C3/C7) |
| `SpecialFlatPortalShape.java` | +1 / −1 | `getCompound("shape")`→`getCompoundOrEmpty("shape")` (C7) |
| `PortalShapeSerialization.java` | +1 / −1 | `getString("type")`→`getStringOr("type", "")` (C7) |

### animation/ (14 files)

| File | LOC | +/− | Hunk class |
|---|---|---|---|
| `AnimationContext.java` | 8 | **0 / 0** | **IDENTICAL** (pure record) |
| `AnimationResult.java` | 10 | **0 / 0** | **IDENTICAL** (pure record) |
| `AnimationView.java` | 55 | **0 / 0** | **IDENTICAL** (only Portal/IntraClusterRelation forward-imports; Component SAME #21) |
| `TimingFunction.java` | 43 | **0 / 0** | **IDENTICAL** (pure enum + math, no MC touchpoint) |
| `StableClientTimer.java` | 256 | **0 / 0** | **IDENTICAL** — every MC touchpoint SAME (#3/#6/#7/#8/#27); now **resolves the S5 `CHelper` forward-ref** |
| `ClientPortalAnimationManagement.java` | 208 | **0 / 0** | **IDENTICAL** — `System.nanoTime` SAME (#28); Fabric `Event`/`@Environment` + `ClientWorldLoader` held verbatim |
| `PortalAnimationDriver.java` | 86 | +4 / −4 | `ResourceLocation`→`Identifier` (import+map key+param, #24); `getString`→`getStringOr("type","")` (#1) |
| `PortalAnimation.java` | 575 | +10 / −10 | 6× `getCompound`→`getCompoundOrEmpty`; 2× `getList(k,10)`→`getListOrEmpty(k)`; 2× `getLong`→`getLongOr(k,0)` (#1) — all in `readFromTag` only |
| `NormalAnimation.java` | 366 | +7 / −7 | `getLong`→`getLongOr`; `getCompound("delta")`→OrEmpty; `getString`→`getStringOr`; `getCompound("initialState")`→OrEmpty (**quirk 2 read KEPT**); `getInt`→`getIntOr`; `getBoolean`→`getBooleanOr` (#1) |
| `RotationAnimation.java` | 197 | +4 / −4 | `getDouble`→`getDoubleOr`; 2× `getLong`→`getLongOr`; `getString`→`getStringOr` (#1) |
| `DefaultPortalAnimation.java` | 90 | +4 / −4 | `getString`→`getStringOr`; `getInt`→`getIntOr`; `getBoolean`→`getBooleanOr`; `getLong`→`getLongOr` (#1) |
| `DeltaUnilateralPortalState.java` | 218 | +4 / −4 | `getCompound("rotation")`→OrEmpty; `getDouble` X/Y→`getDoubleOr(k,0)`; **`getDouble("sizeScalingZ")`→`getDoubleOr("sizeScalingZ",1)`** (quirk-1 default; `contains(...) ? … : 1` ternary KEPT) (#1) |
| `UnilateralPortalState.java` | 458 | +6 / −6 | **`dimension.location()`→`dimension.identifier()`** (recurring A, `toTag:142`); `getString`→`getStringOr`; `getCompound("orientation")`→OrEmpty; 3× `getDouble`→`getDoubleOr(k,0)` (width/height/thickness) (#1, #24) — **axis-extraction math byte-verbatim** (§3) |
| `OscillationAnimation.java` | (dead) | +3 / −3 | 3× NBT read `getXxxOr` (#1); rest KEPT whole (quirk 5) |

Six animation files land byte-identical; the other eight carry ONLY NBT-getter + id-class hunks.
The 4 residual `ResourceLocation` textual matches in the animation tree are all
`McHelper.newResourceLocation` method-name false positives (IP's own held helper, returns
`Identifier` — verbatim). No `Util`/`Tuple`/`ChunkPos`/`getServer` triggers occur in animation; the
one `isClientSide` non-paren match is the `AnimationContext` record component, not a `Level` field
read.

### co-ports

| File | +/− | Hunk classes |
|---|---|---|
| `collision/CollisionHelper.java` | +5 / −4 | **C15** `world.getProfiler()`→`Profiler.get()` (import + 2 sites, push/pop stay balanced same-thread); **C16** `gui.setOverlayMessage`→`gui.hud.setOverlayMessage` ×2 (`Gui.hud.setOverlayMessage`, `Hud.java:1225`). `ServerTickEvents`/`ClientWorldLoader`/`IEEntity_Collision`/`GlobalPortalStorage` held verbatim (§6) |
| `collision/PortalCollisionHandler.java` | +3 / −2 | **C15** `entity.level().getProfiler()`→`Profiler.get()` (import + 2 sites) |
| `collision/PortalCollisionEntry.java` | **0 / 0** | **IDENTICAL** |
| `portal/nether_portal/BlockPortalShape.java` | +26 / −26 | `Tuple`→`Pair` (G3; follows `Helper.getPerpendicularDirections`/`getAnotherTwoAxis` return type) + `.getA()/.getB()`→`.getFirst()/.getSecond()`; `getList("poses",3)`→`getListOrEmpty("poses")` (C6); `getInt`→`getIntOr(k,0)` incl. `ListTag.getInt`→`getIntOr(i,0)` (C7); `direction.getNormal()`→`getUnitVec3i()` ×11 (C19); `isClientSide()` — geometry/scan math byte-verbatim |

---

## 2. R11 decision record (HEADLINE — owned at S6)

The five entity-porting hazards R11 flags are owned and resolved at this stage. Each is
source-verified against `mc262-ref` (not the api-map alone), because the probe (D4.2) is the
authoritative ledger.

### (i) `EntityType.Builder.build(ResourceKey)` — id at build time (C10 / hazard 4 / F1)

26.2 `EntityType.Builder.build(ResourceKey<EntityType<?>>)` **requires the registry key at build**
(`EntityType.java:590`); `Builder.of(EntityFactory, MobCategory)` (`:479`). IP's
`FabricEntityTypeBuilder.create(…).build()` had no id (assigned only at registration). **Resolution
— `createPortalEntityType` restructured to take the id:**

- `Portal.portalEntityTypeKey(String path)` →
  `ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("immersive_portals", path))`
  bakes the id in at class-init. Namespace `immersive_portals` kept **verbatim** (D2, from
  `IPModMain.java:165/175/180/185`).
- `createPortalEntityType(EntityFactory, ResourceKey<EntityType<?>> key)` builds vanilla
  `EntityType.Builder.of(factory, MobCategory.MISC).sized(0,0).fireImmune().clientTrackingRange(6)
  .updateInterval(20).build(key)`.
- Each static `ENTITY_TYPE` passes its own IP id: `Portal.ENTITY_TYPE` (`portal`),
  `Mirror.ENTITY_TYPE` (`mirror`), `BreakableMirror.ENTITY_TYPE` (`breakable_mirror`),
  `EndPortalEntity.ENTITY_TYPE` (`end_portal`), and `LoadingIndicatorEntity.entityType` (built
  INLINE with its own `build(ResourceKey.create(Registries.ENTITY_TYPE,
  Identifier…("loading_indicator")))`; `PortalPlaceholderBlock` gets the block analog `.setId(…)`,
  C23).

`.sized(w,h)` = `EntityDimensions.scalable` (`EntityType.java:488`), not `fixed`; at 0×0 (Portal) and
1×1 (LoadingIndicator, a `MISC` `Entity` with `getScale()`≡1.0) `scalable ≡ fixed` — bbox +
default eye height identical. `.sized` is the api-map-sanctioned F1 replacement. **No Fabric type
appears in `common/`** — the whole builder is vanilla, satisfying F1 loader-neutrally.

### (ii) `EntitySpawnReason` per call site (C9 / hazard 4)

26.2 `EntityType.create(Level)` GONE → `create(Level, EntitySpawnReason)` (`EntityType.java:298`).
**Every `.create()` site in the U4 slice is a deserialize/construction site → `EntitySpawnReason.LOAD`**
(`EntitySpawnReason.java:21`): `PortalManipulation:91/134/161/256`, `BreakableMirror:174`,
`EndPortalEntity:152` — matching the current mod's own 26.2 usage (`current-mod-core.md:342`). The
**`DIMENSION_TRAVEL`** reason (`EntitySpawnReason.java:22`) the R11 note pairs with the vanilla
recreate path (`Entity.java:3081`) is **owned/recorded here but its call site is NOT in U4**: portals
are stationary marker entities that never dimension-travel; that recreate spawn-reason lands with
`ServerTeleportationManager` (U6/S8) and the `GlobalPortalStorage` recreation path, not this slice.

### (iii) tracking range: `trackRangeBlocks(96)` → `clientTrackingRange(6)` **CHUNKS** (F1/F14, hazard 5)

**Source-verified:** `EntityType.Builder.clientTrackingRange(int clientChunkRange)`
(`EntityType.java:565`) is in **chunks** (vanilla default 5, `:461`). Fabric's `trackRangeBlocks(96)`
= 96 blocks ⁄ 16 = **6 chunks**. Ported as `.clientTrackingRange(6)` (with the explicit `96/16 = 6`
comment) on both `Portal.createPortalEntityType` and `LoadingIndicatorEntity`. **Writing `96` would
have created a 1536-block tracking radius** — the exact semantic hazard R11(iii) warns against; the
Fabric-API conversion was verified before hardcoding. `trackedUpdateRate(20)` →
`updateInterval(20)` (`:570`). `forceTrackedVelocityUpdates(true)` is the 26.2 vanilla default for
non-listed modded types (`trackDeltas()`, `EntityType.java:418-424`) so it drops with no behavioral
change (IP's `LoadingIndicatorEntity` never set it anyway).

### (iv) `getBoundingBox()` is FINAL → `makeBoundingBox(Vec3)` inversion + eager `setBoundingBox` push (G1/C2 / hazard 1)

`Entity.getBoundingBox()` is `public final` in 26.2 returning the `bb` field (`Entity.java:3426`);
`setBoundingBox(AABB)` is the setter (`:3431`); vanilla refreshes `bb` in `setPos` via
`setBoundingBox(makeBoundingBox())` (`:472-475`); the no-arg `makeBoundingBox()` is `final` →
delegates to `makeBoundingBox(Vec3)` (`:477-483`). IP's lazy-cache `getBoundingBox()` override is
impossible. **The port inverts to the eager 26.2 pattern:**

1. Override point → `makeBoundingBox(Vec3 position)` (body **byte-identical to IP's old
   `getBoundingBox()`**: `getPortalShape().getBoundingBox(getThisSideState(),
   shouldLimitBoundingBox(), 0.2)`). The `NULL_BOX` sentinel-before-axes-set window returns from here
   (`axisW == null → NULL_BOX`) — semantic hazard 1. `getThisSideState()` reads `position()`, and
   `Portal#move` is a no-op, so the passed `position` always equals `position()` — **IP's exact box
   is preserved**.
2. The `getBoundingBox()` override is **REMOVED** (final, can't override).
3. **Every geometry-field change now EAGERLY pushes** `setBoundingBox(makeBoundingBox())`: in
   `updateCache()` (after invalidating all eight caches) and in the `refreshDimensions()` override.
   `updateCache()` has ~20 call sites (axes/extents/shape setters, `ip_onEntityPositionUpdated`, NBT
   read tail) — each therefore refreshes `bb`. **A missed push leaves vanilla ticking on a stale
   `bb`** (the exact hazard-1 silent bug); the audit confirmed no geometry setter bypasses
   `updateCache()`. `updateCache`'s early-return when `axisW == null` is retained; the box is pushed
   once axes are set and `updateCache` re-runs (vanilla `setPos` pushes `NULL_BOX` in the interim).

### (v) NBT via `ValueInput`/`ValueOutput` preserving the CompoundTag wire shape + rotation quirk (C1/C4, hazard 2/3)

26.2 moved the vanilla save hooks to `readAdditionalSaveData(ValueInput)` /
`addAdditionalSaveData(ValueOutput)` (abstract, `Entity.java:2204-2206`); the `CompoundTag` getters
are now `Optional` (`CompoundTag.java:283-373`). **Resolution — canonical CompoundTag + a one-key
disk bridge** (the bridge mechanism is a governed deviation, §5.2):

- The IP `readAdditionalSaveData(CompoundTag)` / `addAdditionalSaveData(CompoundTag)` bodies are
  **KEPT as the canonical serialization** (getters translated to `…Or`/`…OrEmpty`, all **writers
  verbatim**) but **lose `@Override`** — they are no longer vanilla hooks. They remain used verbatim
  by the custom **sync path** (`createSyncPacket`/`acceptDataSync`/`writePortalDataToNbt`/
  `readPortalDataFromNbt`/`updatePortalFromNbt`) and by `BreakableMirror`'s `super.*(CompoundTag)`
  overrides — so the **PortalSyncPacket wire format is 100% IP-faithful** (top-level fields
  unchanged); this is the wire that matters for rendering + client portal state.
- Two NEW vanilla `@Override` bridges connect disk save/load to the canonical form, nesting the whole
  flat `CompoundTag` under one key `"imm_ptl_portal_data"` via `CompoundTag.CODEC`
  (`input.read("imm_ptl_portal_data", CompoundTag.CODEC).orElseGet(CompoundTag::new)` /
  `output.store(...)`). The key is namespaced because vanilla itself uses top-level `"data"` for
  `customData` (`Entity.java:2107`) — a bare `"data"` nest would collide. `BreakableMirror` needs NO
  `Value` overrides: the `Portal ← Mirror ← BreakableMirror` CompoundTag chain is reached by virtual
  dispatch on the CompoundTag overloads from Portal's bridge. `LoadingIndicatorEntity`'s
  `ValueInput/Output` bodies are **empty** (no NBT content) → no bridge, R11(v) N/A there.
- **Rotation double-write / float-read quirk (C4) preserved exactly.** Write four **doubles**:
  `rotationA=rotation.w`, `rotationB=rotation.x`, `rotationC=rotation.y`, `rotationD=rotation.z`.
  Read via `getFloatOr("rotationB",0)` etc. — `CompoundTag.getFloatOr` = `instanceof NumericTag ?
  floatValue() : default` (`CompoundTag.java:319`), reproducing the **double→float coercion and its
  precision loss** exactly. Reassemble `new DQuaternion(B, C, D, A)` = `DQuaternion(x, y, z, w)`.
  Round-trips IP's quirk 1:1.

---

## 3. Sign-derivation notes (D4.4) — re-derived from IP source + 26.2, never trusted

Every sign/winding/axis-carrying method in the slice was diffed against IP 1.21.3 and is
**byte-identical** (zero diff hunks touch any of them) — so **no sign or winding was changed by the
port**. The derivations below record the ground-truth IP forms the verbatim port carries. The one
geometry-adjacent 26.2 substitution in the whole slice is a **proven numeric identity**
(`Direction.getNormal()` → `getUnitVec3i()`, C19): `getUnitVec3i()` (`Direction.java:375`) returns
the same `Vec3i` ±1 axis unit vector `getNormal()` returned on 1.21.3, so
`Vec3.atLowerCornerOf(dir.getUnitVec3i())` is numerically identical — no sign change (verified at
`Portal`/`BreakableMirror:190/204/205`/`PortalManipulation:380/381`/`BlockPortalShape` ×11).

**JOML idiom pin (the headline anti-deviation guard):** JOML is the SAME library on 26.2 (still
shipped, used by vanilla `SectionOcclusionGraph`), and its accessors use **`m<col><row>` naming**
with `transform(v)` computing **M·v (column-vector, post-multiply)**. Every matrix/transform in the
slice therefore STAYS column-form; **the `IP_DEVIATIONS` row-vector note is WRONG** and no
`mulTranspose`/transpose regression exists (these methods use `DQuaternion.rotate` and
`Matrix3dc.transform`, not the `Vector4f.mul(Matrix4f)` idiom — and even that idiom, where it appears
elsewhere in the mod, is `M·v` and stays).

### 3.1 Portal transforms (`Portal.java`, verbatim vs IP)

| Method | Form (IP = port) | Sign facts |
|---|---|---|
| `transformPoint(pos)` | `transformLocalVec(pos.subtract(getOriginPos())).add(getDestPos())` | subtract **origin**, rotate+scale, add **dest** — no negation |
| `transformLocalVec(v)` | `transformLocalVecNonScale(v).scale(scaling)` | positive `scaling` multiply |
| `transformLocalVecNonScale(v)` | `rotation == null ? v : rotation.rotate(v)` | forward rotation |
| `inverseTransformLocalVecNonScale(v)` | `rotation.getConjugated().rotate(v)` | **conjugate = inverse** rotation (the only inverse-direction sign) |
| `inverseTransformLocalVec(v)` | `…NonScale(v).scale(1.0 / scaling)` | reciprocal scale |
| `inverseTransformPoint(p)` | `getOriginPos().add(inverseTransformLocalVec(p.subtract(getDestPos())))` | exact mirror of `transformPoint` — subtract **dest**, add **origin** |
| `getNormal()` | `axisW.cross(axisH).normalize()` | cross **order axisW × axisH** = front normal (right-handed; flipping the order flips the normal) |
| `getContentDirection()` | `transformLocalVecNonScale(getNormal().scale(-1))` | inner-view direction = **negated** normal, rotated |

Forward/inverse are exact conjugate pairs (rotate ↔ conjugate-rotate, scale ↔ 1/scale, +dest/−origin
↔ +origin/−dest). ✓ `getFullSpaceTransformation()` (JOML `Matrix4d`) =
`translation(dest)·scale(s)·rotate(rot)·translate(−origin)`, applied **right-to-left** under
column-form: translate-to-origin → rotate → scale → translate-to-dest. ✓

**`Mirror` overrides:** `getFullSpaceTransformation()` inserts `.reflect(normal, 0)` after
`translation(dest)` → `T(dest)·Reflect(n)·S·R·T(−origin)` (reflect is the outermost spatial op).
`Mirror.transformLocalVecNonScale(v)` = `getMirrored(super.…(v))` (rotate-then-mirror).
`Mirror.mirroredVec(v,n)` = `v + n·(v·n · −2)` = `v − 2(v·n)n` — standard plane reflection about
normal `n`; the `−2` is verbatim. ✓

**`PortalState.transformVec(v)`** = `rotation.rotate(v)·scaling`, then if `isMirror`
`Mirror.mirroredVec(scaled, getNormal())`; `getNormal()` = `McHelper.getNormalFromOrientation`
(held); `getContentDirection()` = `rotation.rotate(getNormal()·(−1))`;
`worldPosToPortalLocalPos`/`portalLocalPosToWorldPos` use `axisW`, `axisH`, `axisW.cross(axisH)`
(Z = normal) — right-handed, verbatim. ✓ `PortalManipulation.computeDeltaTransformation` /
`getPortalOrientationQuaternion` / `flipAxisW` (180° about `(0,1,0)`) — held-`DQuaternion` Hamilton
products, verbatim. ✓

### 3.2 `UnilateralPortalState` axes — COLUMN-FORM confirmation (the pinned trap)

**Matrix build:** `orientationMatrix = new Matrix3d().set(orientation.toMcQuaternion())`;
`orientationMatrixReverse = new Matrix3d().set(orientation.toMcQuaternion().conjugate())` (reverse =
conjugate = inverse rotation, orthonormal). Verbatim.

The three axis getters read matrix **columns** under JOML's `m<col><row>` naming — this **directly
confirms the mission's column-form pin** (corrects the imprecise "row-0/1/2" phrasing in the
superseded `S06-core.md` draft: under `m<col><row>`, `(m00,m01,m02)` is **column 0**, not a row):

- `getAxisW()` = `(m00, m01, m02)` = **column 0** = M·(1,0,0) — IP's comment "same as multiplying
  (1,0,0) to matrix" is CORRECT **only** under column-form.
- `getAxisH()` = `(m10, m11, m12)` = **column 1** = M·(0,1,0).
- `getNormal()` = `(m20, m21, m22)` = **column 2** = M·(0,0,1).

Under a row-vector reading `getAxisW` would need `(m00,m10,m20)`; IP uses `(m00,m01,m02)` = column 0 =
M·e₀ — column-form. The local frame is right-handed orthonormal (portal faces local +Z; W=+X, H=+Y).
Ported unchanged. **Transforms** (all M·v column-form, verbatim): `transformLocalToGlobal(x,y,z)` =
`orientationMatrix.transform(v)` then `+position`; `transformGlobalToLocal` = `−position` then
`orientationMatrixReverse.transform(v)` (correct translate-then-inverse-rotate ordering);
`pointOnPlane(x,y)` = `transformLocalToGlobal(x,y,0)` (flat portal, z=0). ✓

**Quaternion compositions** (held `DQuaternion`, re-derived at S2, verbatim): `extractOtherSide` =
`rotation ⊗ orientation ⊗ (isMirror ? identity : PortalManipulation.flipAxisW)` via `hamiltonProduct`
(left-to-right = outermost-first); `Builder.rotate` = `rotation ⊗ this.orientation` (left-multiply /
world-frame pre-rotation); `RectInvariant.getVariantOf` = `state.orientation ⊗
rotationByDegrees(axis, deg)` (right-multiply / local-frame post-rotation): ROTATE_90/180/270 about
local **+Z** `(0,0,1)`, FLIP_X about local **+X** `(1,0,0)` by 180°, width/height SWAP on the 90/270
+ FLIP_X_ROTATE_90/270 variants (`switchesWidthAndHeight()`). ✓ **Delta signs**
(`DeltaUnilateralPortalState`): `getInverse` = offset·(−1) / rotation·conjugated / sizeScaling
reciprocal (z guarded `==0 → 1`); `getFlipped` = `rotation ⊗ flipAxisW`; `fromDiff` (quirk 4) =
`after.orientation ⊗ before.orientation.conjugated()`, z-slot = **width** ratio (not thickness). ✓

### 3.3 Shape windings + raytrace/clip signs (`shape/*`, verbatim)

`RectangularPortalShape` and `PortalShape` are **byte-IDENTICAL**; `Box`/`SpecialFlat` differ only by
one NBT-getter hunk. All geometry APIs are SAME on 26.2 (`Vec3` `.x/.y/.z`, `AABB`, `Shapes.create/
joinUnoptimized`, `BooleanOp.AND/ONLY_FIRST`, `VoxelShape`), so verbatim reproduces IP's signs.

1. **Rect raytrace plane crossing** (`raytracePortalShapeByLocalPos`): entry iff
   `localFrom.z()>0 && localTo.z()<0` — local **+z is the front (normal) side**; `t =
   -localFrom.z()/deltaZ`; returned surface normal `(0,0,1)` = local +z. IP's preserved comment ("do
   not trust GitHub copilot. It may use z as up axis") pins z as the portal **normal** axis, not up. ✓
2. **Outer clipping** (`getOuterClipping`): `Plane(position, getNormal())` — normal to the front side;
   `Plane.isPointOnPositiveSide` keeps front geometry. **Inner clipping** (`getInnerClipping`):
   `Plane(otherSideState.position, otherSideState.getNormal())` — dest-side normal, clips behind the
   dest portal. ✓
3. **`shouldRenderInside`** (Rect): tests the box corner FURTHEST along `innerClipping.normal` —
   picks `min` when `normal.<axis> < 0` else `max`; if that furthest point is still on the positive
   side, the box may render. ✓
4. **`getBoundingBox`** (Rect ±boxExpand on z; Box ±half on all three): 8 corners via
   `transformLocalToGlobal(±halfW,±halfH,±t)`. ✓
5. **`renderViewAreaMesh` windings** (the flagged risk): Rect passes `localXAxis=axisW·(w/2)`,
   `localYAxis=axisH·(h/2)` (order X,Y). Box's 6 faces each choose the quad-axis pair by the
   `facingOutwards ? … : …` ternary — **the swap on every face IS the entire outward/inward
   orientation** (a single reordered pair inverts a face). SpecialFlat emits one triangle per mesh
   triangle in stored order `(p0,p1,p2)`; **`getFlipped` rebuilds each triangle with `x → −x`, y kept,
   SAME index order — mirroring x with unchanged vertex order REVERSES winding** (the intended
   reverse-facing shape). All verbatim. **NOTE:** the actual vertex emission order lives in
   `ViewAreaRenderer.outputFullQuad/outputTriangle` and `FrustumCuller` (U9/U10, held → S11); the
   shapes only fix the AXIS ORDER fed in — final winding is validated when those callees land at S11.

### 3.4 Collision clip signs (`CollisionHelper` / `PortalCollisionHandler`, verbatim)

6. **`clipBox`** (cut box with plane, keep normal side): `xForward = normal.x>0`; `pushedPos` = the
   BEHIND-side corner (min when forward), `staticPos` = the FRONT corner (max when forward);
   `tOfPushedPos = Helper.getCollidingT(...)`; `tOfPushedPos<0` ⇒ pushedPos already in front ⇒ box
   uncut. Else if `staticPos` not in front ⇒ fully cut → null. Else partial: push pushedPos onto the
   plane along `normal·t`; `new AABB(afterBeingPushed, staticPos)` retains the normal-facing half. ✓
7. **`isBoxFullyBehindPlane`**: `testingPos` = corner FURTHEST along normal (max when forward);
   `(testingPos−planePos)·normal < 0` ⇒ whole box behind. ✓
8. **`handleCollisionWithShapeProcessor`** (gravity-generalized `Entity.collide` copy,
   `@IPVanillaCopy`): `jumpDirection = gravity.getOpposite()`; step-up keyed by
   `Helper.getSignedCoordinate/putSignedCoordinate/getCoordinate/getDistanceSqrOnAxisPlane` (held,
   verbatim) along the gravity axis; the `+0.001` float-error tolerance and the "step → move
   horizontally → move down" ordering are verbatim. The final sweep delegates to
   `IEEntity_Collision.ip_CollideWithShapes` — the REAL vanilla `collideWithShapes` `@Invoker` — which
   keeps the (changed) 26.2 `Direction.axisStepOrder` sweep honest. **Aligning IP's copy to 26.2's
   internal `collide` changes is R12, owned at S8** — doing it here would be a deviation from IP; the
   S6 co-port contract is zero-deviation-from-IP, and the live invoker keeps the statics 26.2-faithful
   (see the S8 handoff, §5.3). ✓

No reversed-Z / depth-func / stencil-op constant exists anywhere in U4 — those live in the S10/S11
render slices. **The D4.4 obligation is discharged by verbatim carry: there is no sign in the slice
the port re-expressed, so none could have been flipped.** The only geometry constants re-expressed
are the R11(iv) `NULL_BOX` sentinel and the `0.2` box margin, both byte-identical to IP.

---

## 4. Five preserved animation quirks (verbatim — NOT "fixed")

The `animation/` port carries five IP behaviors that a naive cleanup would "correct." All are
preserved byte-verbatim; only the enclosing NBT reads were translated where noted:

1. **Two-pass client tick.** `ClientPortalAnimationManagement.tick()` runs
   `updateCustomAnimations(true)` (`usedTickTime = stableTickTime + 1`, removal disabled via
   `canRemoveAnimation = !isTicking`) then `updateCustomAnimations(false)` (immediate state). File is
   0/0 verbatim. ✓
2. **`NormalAnimation.deserialize` reads an unused `"initialState"`.** The
   `UnilateralPortalState initialState` local is dead but the read is KEPT (only the
   `getCompound("initialState")`→`getCompoundOrEmpty` getter translated). ✓
3. **`applyEndingState` uses `getGameTime()+timeOffset` ignoring the pause branch.**
   (`PortalAnimation.java:487`) — untouched; the `PortalAnimation` edits are confined to `readFromTag`
   (lines 66–134). ✓
4. **`DeltaUnilateralPortalState.fromDiff` stores the WIDTH ratio in the z/thickness slot**
   (`after.width()/before.width()` for the z component, `:173-176`) — untouched (only `fromTag`
   edited); cross-referenced by the §3.2 delta-sign derivation. ✓
5. **`OscillationAnimation` is dead code** (`@Deprecated`; `getAnimationResult` ends in
   `throw new NotImplementedException()`; `init()` "currently not called") — **KEPT whole**; only the
   three NBT reads translated. ✓

The driver NBT idiom (animation touchpoint #1): drivers round-trip via **bare `CompoundTag`** (not
`TagValueOutput/Input` — those wrap the *entity* NBT at the `Portal`/`PortalState` boundary; the
driver `toTag`/`fromTag` are plain `CompoundTag` builders IP invokes from inside that wire shape).
All writers verbatim; all readers → default-preserving `getXOr` forms (long/int/double→0, string→"",
boolean→false, compound→empty) **except** `DeltaUnilateralPortalState.sizeScalingZ`, whose IP fallback
is **1** (`getDoubleOr("sizeScalingZ",1)`, with the `contains(...) ? … : 1` ternary kept intact so
the two 1-defaults stay visibly paired).

---

## 5. Category-(c) gap resolutions + api-map amendments

The `-Pip_scc_closed=true` probe (D4.2 authoritative ledger) surfaced 26.2 changes the research-corpus
api-map omitted. Each is a governed 26.2 translation re-derived from `mc262-ref`, not a simplification.

### 5.1 `hurtServer` — the S6 api-map amendment (FixGap)

**Committed as the `[S6]` CHANGED row in `portal-core.md` §2.2** (post-amendment tally
3 GONE / 25 CHANGED / 108 SAME / 5 FABRIC-API; previously only an inline code comment, corrected per
D4.2 ledger governance — `portal-core.md` had no prior FixGaps amendment).

- 26.2 split the 1.21.3 concrete `Entity.hurt(DamageSource, float)` server path into
  `public abstract boolean hurtServer(ServerLevel, DamageSource, float)` (`Entity.java:1923`) +
  concrete `hurtClient(DamageSource)` (`:1925`). **Every direct `Entity` subclass must now implement
  `hurtServer`.** (This was the only unimplemented abstract the probe reported for Portal —
  `defineSynchedData`, and the two `ValueInput/Output` hooks were already present.)
- IP's `Portal` and `LoadingIndicatorEntity` carry **no** `hurt`/`hurtServer` override (verified: zero
  `hurt` matches in either IP file) — they inherited the concrete 1.21.3 no-op and are removed via
  validity checks, never damaged. This is a research-corpus gap, **not** an IP semantic.
- Both add `@Override public boolean hurtServer(ServerLevel, DamageSource, float) { return false; }` —
  faithful to IP's inherited no-damage behavior, matching vanilla's own non-damageable markers
  `Display#hurtServer` (`Display.java:140`, `return false`) / `Marker#hurtServer`
  (`Marker.java:67`). `Mirror`/`BreakableMirror`/`EndPortalEntity` extend `Portal` and **inherit the
  impl** — ONE override on `Portal` covers the whole Portal hierarchy; `LoadingIndicatorEntity` needs
  its own (it is not a `Portal`). Pure vanilla-abstract satisfaction — no Fabric/loader seam.

### 5.2 Governed deviation — disk-NBT bridge nests under one key (recorded here, NOT an api-map change)

`portal-core.md` C1/hazard-3 **recommends** the `TagValueOutput.createWithContext(...).buildResult()` /
`TagValueInput.create(...)` bridge. The port instead nests the verbatim IP flat `CompoundTag` under one
key `"imm_ptl_portal_data"` via `CompoundTag.CODEC` (§2(v)). This **maximizes IP fidelity, not
deviates**:

1. The recommended top-level-flat bridge would force rewriting the whole
   `addAdditionalSaveData(CompoundTag)` body into `ValueOutput` calls (no clean public API merges a
   `CompoundTag`'s heterogeneous keys into an existing `ValueOutput` at top level). Nesting keeps the
   IP CompoundTag (de)serialization body **byte-verbatim** (the D2 goal).
2. **The render-relevant wire is untouched** — `createSyncPacket`/`acceptDataSync` build/read the
   flat top-level `CompoundTag` directly; only the on-disk layout gains a one-key wrapper.
3. **Greenfield disk** — the mod is block-portal-based today; there are no legacy entity-portal disk
   saves to be compatible with, and the mod both writes and reads under the same key. IP-upstream disk
   cross-compat was never a goal. If IP-disk-format parity is ever required, the flat-bridge rewrite is
   the fallback.

C1 stays exactly as written; the api-map header cross-references **this note §5.2** for the rationale
(the reference was repointed from the superseded `S06-core.md §5` when this canonical note landed).

### 5.3 Other probe-surfaced api-map amendments

| # | Gap (IP construct, changed in 26.2) | Site | 26.2 fix (source-verified) | Kind |
|---|---|---|---|---|
| **[S6-A2]** | `StringTag.getAsString()` GONE — `StringTag` is now `record StringTag(String value)` (`StringTag.java:8`); `Tag.asString()`→`Optional<String>` (`Tag.java:51`) | `Portal.readAdditionalSaveData` commands map | `((StringTag)t).getAsString()` → `((StringTag)t).value()` (record accessor, direct `String`) | refines C6 |
| **[S6-A3]** | `AbstractMinecart` package move | `Portal.java:31` import | `vehicle.AbstractMinecart` → `vehicle.minecart.AbstractMinecart` | already in **C24** — applied |

### 5.4 Reconciliation — `net.fabricmc.api` (`@Environment`/`EnvType`) on the probe classpath

The two fragments disagreed and the probe settles it. `S06-shapes.md §1` asserted Fabric API is on the
common compile classpath (so `@Environment`/`ServerTickEvents` need no seam); `S06-blocks.md §5`
showed the `-Pip_scc_closed=true` probe reports **`package net.fabricmc.api does not exist`** at every
`@Environment(EnvType.CLIENT)` site — in ALL landed S4/S5 held files AND both sibling S6 slices,
identically. **The probe is authoritative:** `net.fabricmc.api.Environment`/`EnvType` are the
fabric-**loader** annotations (distinct from fabric-API `net.fabricmc.fabric.api.*` handled by F1–F5);
the common compile classpath does not expose them to `:common:compileJava`. This is **systemic,
pre-existing S4-exit loader-facade debt** — NOT introduced by S6, kept verbatim per the established
convention, and orthogonal to per-slice translation fidelity (like F21's stub classpath). It **must be
resolved tree-wide before S13** (when `ip_scc_closed=true` becomes the real config), e.g. by adding the
fabric-loader annotation artifact to common's compile classpath. Tracked to resolve at **S10** (task
#8) with the loader-facade cutover. `S06-shapes.md`'s optimistic classpath assertion is corrected by
this reconciliation.

---

## 6. Probe-vs-U4-union triage (D4.2)

The `-Pip_scc_closed=true` probe compiles the **entire** held tree (S2 + S4 + S5 + S6), so its 405
errors span all landed-held units. Triage decomposes cleanly; **the probe is the authoritative
ledger** and it reduces to exactly the documented forward-ref union.

**(i) U4-file errors reduce to the documented S6(b) forward-ref union ONLY — zero translation slips.**
Every error whose file is a U4 file resolves to a held forward-ref or the loader facade. Grep-verified
error *shapes* on U4 files are only "cannot find symbol" and "package … does not exist" — **zero**
"incompatible types" / "method cannot be applied" (a translation slip's signature):

| Unresolved symbol (in-probe) | On U4 files | Owning stage | Kind |
|---|---|---|---|
| `net.fabricmc.api` `EnvType`/`Environment` + `@Environment` usages | Portal, all shapes, animation, PortalLike, PortalRenderInfo, CollisionHelper, … | loader facade / **S10** (task #8) | S4-exit loader-facade debt (§5.4) |
| `net.fabricmc.fabric.api.event.Event` / `…lifecycle.v1.ServerTickEvents` | ClientPortalAnimationManagement, CollisionHelper | loader facade (F5) / **S10** | held Fabric-API verbatim |
| `api.ImmPtlEntityExtension`, `api.PortalAPI` | Portal, PortalManipulation | U5 / **S7** | forward-ref (S6(b)) |
| `network.ImmPtlNetworking` | Portal | U5 / **S7** | forward-ref (S6(b)) |
| `global_portals.GlobalPortalStorage` | CollisionHelper, PortalUtils | U5 / **S7** | forward-ref (S6(b)) |
| `teleportation.ServerTeleportationManager` | PortalExtension | U6 / **S8** | forward-ref (S6(b)) |
| `ClientWorldLoader` | Portal, CollisionHelper, ClientPortalAnimationManagement | U8 / **S10** | forward-ref (S6(b)) |
| `mixin.common.collision.IEEntity_Collision` | CollisionHelper | U8 / **S10** | held mixin (S6(b)) |
| `mixin.common.entity_sync.MixinServerEntity` | Portal (`:55`, javadoc `@link`) | **S10** | held mixin (round-3 sweep) |
| `mixin.common.miscellaneous.IEEndDragonFight` | EndPortalEntity | **S10** | held mixin (S6(b) round-3) |
| `render.renderer.PortalRenderer` | Portal | U10 / **S11** | forward-ref (S6(b)) |
| `render.GlQueryObject` | PortalRenderInfo | **S11** (distinct from QueryManager) | forward-ref (S6(b) round-3) |
| `render.QueryManager`, `render.context_management.{RenderStates,WorldRenderInfo}`, `render.FrustumCuller`, `render.ViewAreaRenderer` | PortalRenderInfo, Rect/Box/SpecialFlat shapes | U9/U10 / **S11** | forward-ref (S6(b)) |
| `commands.PortalCommand` | PortalManipulation | U11 / **S13** | forward-ref (S6(b)) |
| `nether_portal.BreakablePortalEntity` | PortalPlaceholderBlock | S13 U12 closure | forward-ref (S6(b)) |

Every entry is named in EXECUTION_PLAN §S6(b) (or is the §5.4 loader-facade debt). Notes:
`Portal.java:49 → mc_utils.IPEntityEventListenableEntity` and `MiscHelper` **resolve in-probe** —
both landed held at S4 after the round-3 sweep. `CollisionHelper`'s only diff hunks are the mandated
C15/C16 translations; its `ServerTickEvents` import is held verbatim (facade debt, not a slip).
**Progress marker:** `StableClientTimer` — an S5 `CHelper` forward-ref — now **resolves in-probe**
(it landed with U4 animation), confirming the S5→S6 edge closed.

**(ii) Non-U4 errors are the S4/S5 carry-over baseline, unchanged.** The remaining errors sit on
S4/S5-held files — `IPConfig` (cloth/autoconfig), `O_O`/`IPCGlobal`/`IPGlobal`/`RequiemCompat`/
`IPPortingLibCompat`/`IPFlywheelCompat`/`GravityChangerInterface`/`SodiumInterface`/`IPFeatureControl`
(`FabricLoader`/`Version`/`ModContainer`/`DimensionAPI`), `DimensionIntId`, `MiscHelper`/`CHelper`/
`IPMcHelper`/`ScaleUtils`/`ServerTaskList`, and the S4 render ducks
`ducks/IE{GameRenderer,WorldRenderer,Shader,DistanceManager}` (render-slice debt, S11/S12). These are
the S4/S5 triage and resolve at their owning stages; they predate this pass and are **not** S6's
concern.

**Verdict:** the U4 slice reduces to exactly the documented S6(b) forward-ref union + the loader-facade
+ S4/S5 carry-over baseline. **Gate satisfied — probe = U4 union + carry-over only, zero translation
slips.**

---

## 7. Verification tier (adversarial geometry/NBT verify) — Opus, Fable re-verify QUEUED

Per the migration model-tier policy (memory `migration-model-tier-policy`), S6 is one of the five hard
stages (S6, S8, S11, S12, S13) **scheduled to escalate its adversarial-verify + design pass to
`model:'fable'`**. **At the scheduled time Fable was rate-limited**, so the S6 adversarial
geometry/NBT verification instead **ran on Opus** — two independent verifier passes over this XL
slice:

- **Opus verifier pass 1** refuted **zero** items; its one flagged judgement-call — the disk-NBT
  nesting mechanism — is governed by §5.2 (kept as a bounded, deliberate choice, not a defect).
- **Opus verifier pass 2** raised three advisories, all resolved and folded into this note:
  **V2#1** the NBT-bridge mechanism write-up → §2(v) + §5.2; **V2#2** the `hurtServer` api-map
  amendment → §5.1 + the `portal-core.md` `[S6]` CHANGED row; **V2#3** the R11(i–v) decision record
  (§2) + the D4.4 sign-derivations (§3).

**Provenance correction (honesty note):** the `portal-core.md` S6-amendment header and the superseded
intermediate draft `S06-core.md` both label this pass "Fable verify." That label predates this tier
record and is **inaccurate** — the pass ran on **Opus** (Fable rate-limited). This note is the
corrected record. Safety for S6 comes from the workflow gates (probe ledger, diff gate, sign-notes,
math harness), **not** the tier (migration-model-tier-policy: "safety comes from the workflow gates,
not the tier"), so the Opus run satisfies the stage's fidelity instruments.

**QUEUED before the S13 flip:** a **Fable re-verify of the §3 sign-derivations specifically** — the
Portal transforms (§3.1), the `UnilateralPortalState` **column-form axis extraction** (§3.2, the
`IP_DEVIATIONS` row-vector pin), the shape windings (§3.3), and the collision clip signs (§3.4). The
sign surface is the migration's highest-risk class (API_RISKS R5 reversed-Z; the geometry-sign lesson
bitten twice — briefing §6), and it is the one area where a second adversarial tier adds real signal
before U4 first renders at S13. This is logged as an **S13 pre-flip gate item** (the geometry actually
first renders at S13 rung 1, so the re-verify lands before any live sign can bite). No other S6
deliverable is blocked on it — the port is diff-gate-clean, probe-clean, and math-harness-green now.

---

## Appendix — commit mapping (EXECUTION_PLAN §S6(c))

1. **portal root + state + extension/manipulation** — `Portal`, `PortalState`, `PortalExtension`,
   `PortalManipulation`, `PortalUtils`, `PortalLike`, `GeometryPortalShape`, `IntraClusterRelation`,
   `util/*` (R11 i–v applied here).
2. **shapes + co-ported collision trio + BlockPortalShape** — `shape/*`, `collision/*`,
   `nether_portal/BlockPortalShape`.
3. **animation package** — the 14 `animation/*` files.
4. **placeholder block + loading indicator + PortalRenderInfo** — `PortalPlaceholderBlock`,
   `LoadingIndicatorEntity`, `PortalRenderInfo`.
5. **port-note + api-map amendment** — this file + the `portal-core.md` S6 amendment block
   (`[S6]` `hurtServer` CHANGED row + header note).

The `-Pip_scc_closed=true` probe gate is evaluated after commits 1–4 (needs the full slice). No
`git commit` / `gradlew --stop` / runClient is run by this note's author — the orchestrator commits;
the user runs runClient (BASELINE-SANITY; nothing user-visible changes at S6, per §S6(d)). The four
working fragments (`migration/fragments/S06-*.md`) are deleted at stage end (this note supersedes
them); the non-canonical intermediate `S06-core.md` is likewise superseded by this file.
