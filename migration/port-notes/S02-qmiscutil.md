# S02 port-note — q_misc_util (U1) + math harness

**Stage:** S2 (EXECUTION_PLAN.md §3 S2; instruments §1 D1/D2/D4) · **Assembled:** 2026-07-13
**IP source:** `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall` (1.21.3, Mojang mappings; tests at `src/test/java/qouteall`)
**Landed at:** `common/src/main/java/qouteall/q_misc_util/**` + `common/src/test/java/qouteall/q_misc_util/my_util/**` (final verbatim paths, D2)
**API ground truth:** `migration/api-map/q-misc-util.md` (row ids cited per hunk), 26.2 decompile `C:/Users/warwa/ModDev/mc262-ref`
**Merged from:** the three S2 translator fragments (root / mathA / mathB) + the build agent's
buildSrc carve-in (`buildSrc/src/main/groovy/com/warwa/seamlessportals/gradle/IpHeldPaths.groovy`).
Diff-gate hunk counts and the carve-in grep evidence below were **independently re-measured at
note-assembly time**, not copied from the fragments.

**Diff-gate command (D4.3), per file:**
`git diff --no-index --ignore-cr-at-eol -- <IP path> <ported path>`
The IP working tree is CRLF on disk (autocrlf artifact); ported files are LF (repo/mission
convention) — `--ignore-cr-at-eol` hides ONLY the EOL difference. Every remaining hunk is
recorded below and belongs to one of the two allowed categories: (1) mechanical 26.2 API
translation with an api-map row, (2) S0-decided loader-seam substitution.

---

## NEEDS-REVIEW (surfaced first)

**None.** Every hunk in the unit maps to an api-map row (G1, G2, G3, G8, C1–C8, C10) or the S0
event seam (F4). Two judgment calls made INSIDE a row's documented option space are flagged for
reviewer eyes (details in the per-file records):

1. **`Helper.vec3FromListTag` + `Mesh2D.fromTag` list-type guards — strict `Tag.getId()` form
   chosen over the coercing Optional form.** 26.2 `ListTag.getDouble(int)` routes through
   `Tag::asDouble`, which COERCES any `NumericTag` (`26.2:ListTag.java:294`): the Optional-empty
   route would ACCEPT an all-int list that IP 1.21.3 REJECTED. The per-element
   `get(i).getId() == Tag.TAG_DOUBLE/TAG_INT` check (G8's sanctioned form, `26.2:Tag.java:15,18,32`)
   preserves IP's exact accept/reject set (the known NBT numeric-coercion trap from the research
   corpus). In `vec3FromListTag` the size check moved FIRST because `get(i)` now throws on
   out-of-range where old `getElementType()` did not.
2. **`Helper.getCompoundList` under `getListOrEmpty` — heterogeneous-list nuance.** Old typed
   `getList(name, 10)` returned an EMPTY list on element-type mismatch; `getListOrEmpty` returns
   the actual (heterogeneous) list. Downstream `listTagDeserialize` already per-element
   type-checks (`tag.getClass() == tagClass`, logs + skips), so the only observable difference is
   an error log instead of silence on hand-corrupted NBT (C3 row: round-trip drop-in).

**Resolved item (not a hunk defect):** the S2(d) ACTIVE test payload — the authored
**DQuaternion/Plane invariant tests** — is **now present** at
`common/src/test/java/qouteall/q_misc_util/my_util/DQuaternionTest.java` (27 tests) and
`.../PlaneTest.java` (11 tests), so the S2 `:common:test` gate is live and non-vacuous. (This
item read "not yet present" at initial note-assembly, when the tree held only the two carried IP
tests + the S0 infra tests; the authored suite landed afterward — corrected here. See the
`fragments/S02-tests.md` fragment for the gate evidence.) The carried `Mesh2DTest`/`HelperTest`
ARE landed (verbatim, held — see §3).

---

## 1. File census + diff-gate summary (independently re-measured)

40 main files + 2 carried test files. Every ported file has an IP original at the mirrored path
(mirror-loop verified: zero unmatched files). Total content hunks across the unit: **38**, all in
8 files; the other 32 main files and both test files are **verbatim (0 hunks)** modulo LF.

| File (`common/src/main/java/qouteall/…`) | Hunks | Justification categories | Held? |
|---|---|---|---|
| `q_misc_util/Helper.java` | 18 | F4 seam, G1, C1, C3, C6, C7, C8, C10, G8+C5 | HELD (→McHelper) |
| `q_misc_util/MiscGlobals.java` | 0 | verbatim | HELD (imports held MyTaskList) |
| `q_misc_util/dimension/DimIntIdMap.java` | 5 | C1, C2, C4, C8 | HELD (imports Helper) |
| `my_util/Mesh2D.java` | 2 | C3, G8+C5 | HELD (imports Helper) |
| `my_util/DQuaternion.java` | 4 | G1, C1 | carved in |
| `my_util/IntBox.java` | 3 | C10, C1 | HELD (imports Helper) |
| `my_util/IntMatrix3.java` | 2 | C10, G2 | HELD (imports Helper) |
| `my_util/AARotation.java` | 2 | C10, G2 | HELD (same-package IntMatrix3) |
| `my_util/GuiHelper.java` | 2 | G3 (R13e) | carved in |
| 26 other `my_util/*.java` + 5 `my_util/animation/*.java` | 0 each | verbatim | see §2 |
| test: `my_util/HelperTest.java`, `my_util/Mesh2DTest.java` | 0 each | verbatim | HELD (test list) |

Fragment-count reconciliation (no content discrepancy — physical hunks coalesce under default
diff context): Helper measured 18 physical hunks vs the fragment's 20 enumerated change-blocks;
DimIntIdMap 5 vs "6 blocks / 7 changes"; AARotation 2 vs 3 (the `transformDirection` +
`dirCrossProduct` G2 changes share one hunk). Re-verified change-by-change with `-U0`: every
changed line falls inside the fragments' enumerations; nothing extra, nothing missing.

Zero-hunk verbatim files (32): Access, BoxPredicate, BoxPredicateF, ChangeAccumulator, Circle,
CountDownInt, GeometryUtil, KeyedTaskList, LimitedLogger, LineSegment, LongBlockPos, MyTaskList,
ObjectBuffer, Plane, QuadTree, Range, RateStat, RayTraceResult, Signal, SignalArged,
SignalBiArged, Sphere, TriIntPredicate, TriangleConsumer, Vec2d, WithDim,
animation/{Animated, RenderedLineSegment, RenderedPlane, RenderedPoint, RenderedSphere},
MiscGlobals. (GuiHelper/LimitedLogger/MyTaskList/Signal* were recorded by BOTH the root and
mathA fragments — the records agree exactly; de-duplicated here.)

Classpath note (not NEEDS-REVIEW — no MC API involved):
`org.apache.logging.log4j.util.TriConsumer` (SignalBiArged) and the log4j-api `Logger` overloads
(LimitedLogger) assume log4j-api on the dev classpath as on 1.21.3; both files are HELD until
S13, so a miss would surface in the compile probe, never in the S2 gate. `com.mojang.logging
.LogUtils` (MyTaskList, RateStat, Mesh2DTest) is in active 26.2 vanilla use (e.g.
`26.2:net/minecraft/advancements/AdvancementTree.java`).

---

## 2. Per-file hunk records (D4.3)

### 2.1 `Helper.java` — IP 1501 LOC → port 1505 (+4, the G8 block) — 18 hunks

| Hunk site(s) | Change | Justification |
|---|---|---|
| imports @7 | `net.fabricmc.fabric.api.event.Event/EventFactory` → `com.warwa.seamlessportals.event.Event/EventFactory` | **S0 seam (F4)** — the ONLY seam substitution in the whole unit. Helper's factory BODIES (`createRunnableEvent`/`createConsumerEvent`/`createBiConsumerEvent`, IP `Helper.java:1424-1455`) are unchanged; the seam mirrors Fabric's `createArrayBacked(Class<? super T>, Function<T[],T>)` signature exactly (verified `common/src/main/java/com/warwa/seamlessportals/event/EventFactory.java:32`). |
| imports @19 | `ResourceLocation` → `Identifier`; `net.minecraft.util.Tuple` → `com.mojang.datafixers.util.Pair` | **C7**; **G1** (Tuple GONE; DFU `Pair` ctor verified `public` via javap on datafixerupper-9.0.19 — `new Pair<>(…)` is the minimal-diff form) |
| @311, @344-@351, @373-@387, @392-@399, @578-@595, @906 | `Tuple`→`Pair` (20 tokens), `.getA()`→`.getFirst()` (6), `.getB()`→`.getSecond()` (6) in `swaped`, `getAnotherTwoAxis`, `getAnotherFourDirections`, `getPerpendicularDirections`, `composeTwoStreamsWithEqualLength`, `wrapAdjacentAndMap1` | **G1** |
| @222 (`getUnitFromAxis`), @409 (`getBoxSurfaceInversed`) | `.getNormal()` → `.getUnitVec3i()` | **C10** (returned `Vec3i` values identical — enum ctor constants `Direction.java:33-38`) |
| @504 | `dimIdToKey(ResourceLocation …)` → `(Identifier …)` | **C7** (targeted, NOT a global rename: `McHelper.newResourceLocation` in the next method stays VERBATIM — the held U1→U3 edge, `Helper.java:509`) |
| @513 | `dim.location()` → `dim.identifier()` | **C8** (serialized `namespace:path` string unchanged — save/wire compatible) |
| @520 | `((StringTag) term).getAsString()` → `.value()` | **C6** (record accessor; error branch + OVERWORLD fallback untouched) |
| @634, @658, @677 | `getDouble`/`getInt` → `getDoubleOr(…, 0.0)` / `getIntOr(…, 0)` (7 + 3 sites: `getVec3d`, `getVec3i`, `getQuaternion`) | **C1** (old getters returned 0 on missing/wrong type; `-Or` twin with 0 default is the exact 1:1; the `contains(name+"X")` guards in `getVec3dOptional`/`getQuaternion` survive — untyped `contains` is a SAME row) |
| @689 | `tag.getList(name, 10)` → `tag.getListOrEmpty(name)` | **C3** — see NEEDS-REVIEW flag 2 (heterogeneous-list nuance) |
| @930 | `tag.getLong(…)` → `tag.getLongOr(…, 0L)` ×2 (`getUuid`) | **C1** (contains-guard on `key+"Most"` survives) |
| @1467-@1475 | `vec3FromListTag`: `getElementType() == TAG_DOUBLE && size() == 3` → `size() == 3 && get(i).getId() == Tag.TAG_DOUBLE` ×3; `getDouble(i)` → `getDoubleOr(i, 0.0)` ×3 | **G8 + C5** — see NEEDS-REVIEW flag 1 (strict `getId()` form; size check moved first) |

Verbatim-held edge kept: `import qouteall.imm_ptl.core.McHelper;` (`Helper.java:29`) +
`McHelper.newResourceLocation(str)` — the ONE edge out of U1 (S2(b) forward-ref ledger;
zero-deviation forbids removing the import; Helper compiles at S13).

### 2.2 `my_util/Mesh2D.java` — IP 1753 LOC → port 1765 — 2 hunks (both in `fromTag`, :1696-1740)

`toTag` untouched — the write side is SAME per the api-map "NBT parts that did NOT change", so
save/wire layout is unchanged.

- **Hunk 1** (`getList` calls): `tag.getList("pointCoords", Tag.TAG_DOUBLE)` /
  `tag.getList("triangles", Tag.TAG_INT)` → `tag.getListOrEmpty(…)` — **C3** (typed `getList`
  gone; `getListOrEmpty` is the miss-behavior drop-in, `26.2:CompoundTag.java:363`).
- **Hunk 2** (type guard + element reads):
  - Two added per-element validation loops
    `if (list.get(i).getId() != Tag.TAG_DOUBLE/TAG_INT) return null;` — **G8 + C3**: the old
    typed `getList` returned an EMPTY list on element-type mismatch (→ `fromTag` returned null
    via the isEmpty checks); 26.2 lists are heterogeneous with no whole-list type probe, so the
    guard is re-expressed per element in the strict `getId()` form (NEEDS-REVIEW flag 1 — the
    Optional form would accept an all-int `pointCoords` list IP rejected).
  - `triangles.getInt(i)` → `getIntOr(i, 0)`, `pointCoords.getDouble(idx)` →
    `getDoubleOr(idx, 0)` — **C5**. The `-Or 0` default reproduces the old getters'
    0-on-out-of-bounds behavior exactly (`26.2:ListTag.java:282,298` + `getNullable(:318)`):
    relevant because `pointCoords` is indexed by DATA-DRIVEN triangle indexes — a corrupt index
    yielded degenerate (0,0) points (rejected by `addTriangle`) in IP, and still does.
  - The unused `int pointNum` local is IP dead code — retained verbatim (no dead-code removal).

### 2.3 `my_util/DQuaternion.java` — 545 LOC → 545 — 4 hunks

| # | Location | Change | Justification |
|---|---|---|---|
| 1 | import block (:6) | `net.minecraft.util.Tuple` → `com.mojang.datafixers.util.Pair` (in-place, minimal diff) | G1 |
| 2 | `getPitchYawFromRotation` signature (:343) | return type `Tuple<Double, Double>` → `Pair<Double, Double>` | G1 |
| 3 | `getPitchYawFromRotation` body (:355) | `new Tuple<>(` → `new Pair<>(` | G1 |
| 4 | `fromTag` (:453-456) | 4× `getDouble("…")` → `getDoubleOr("…", 0.0)` | C1 |

C1 fallback audit (savegame format): IP's deliberate identity fallback rides the untouched
`if (!compoundTag.contains("x"))` guard — untyped `contains(String)` survives
(`26.2:CompoundTag.java:275`). `getDoubleOr(name, 0.0)` reproduces the old getter's
missing/wrong-type → 0.0 exactly (per-key). Write side (`putDouble`) untouched → NBT layout
unchanged. Note: the residual `getNormal()` at :538 is DQuaternion's OWN method (rotates
(0,0,1)), not `Direction.getNormal` — verbatim, NOT a C10 site.

### 2.4 `my_util/IntBox.java` — 540 LOC → 540 — 3 hunks

| # | Location | Change | Justification |
|---|---|---|---|
| 1 | `getExpanded(Axis, int)` (:57) | `.getNormal()` → `.getUnitVec3i()` | C10 |
| 2 | `getExpanded(Direction, int)` (:67,:72) | 2× `.getNormal()` → `.getUnitVec3i()` | C10 |
| 3 | `fromTag` (:506-513) | 6× `getInt("…")` → `getIntOr("…", 0)` (no contains guard in IP; old-getter 0 default preserved exactly) | C1 |

`import qouteall.q_misc_util.Helper` stays VERBATIM (held). `Direction.get(AxisDirection, Axis)`
verified SAME (`Direction.java:361`); `BlockPos.betweenClosedStream` SAME (`BlockPos.java:369` —
the mutable-pos warning comment stays).

### 2.5 `my_util/IntMatrix3.java` — 108 LOC → 108 — 2 hunks

| # | Location | Change | Justification |
|---|---|---|---|
| 1 | `IntMatrix3(OctahedralGroup)` ctor (:35-37) | 3× `.getNormal()` → `.getUnitVec3i()` | C10 |
| 2 | `transformDirection` (:57-58) | `.getNormal()` → `.getUnitVec3i()`; `Direction.fromDelta(x,y,z)` → `Direction.getNearest(x,y,z, null)` — direct return, NO assert added (G2 instruction) | C10 + G2 |

`Helper` import verbatim (held). `OctahedralGroup.rotate(Direction)` verified SAME
(`OctahedralGroup.java:142`); `Direction.fromAxisAndDirection` SAME (`Direction.java:287`);
`Vec3.atLowerCornerOf` SAME (`Vec3.java:45`).

### 2.6 `my_util/AARotation.java` — IP 214 LOC → port 216 (+2: the two added `null` args on their own lines) — 2 hunks

| # | Location | Change | Justification |
|---|---|---|---|
| 1 | enum ctor (:67-69) | 3× `.getNormal()` → `.getUnitVec3i()` | C10 |
| 2 (coalesced) | `transformDirection` (:79-85) + `dirCrossProduct` (:90-96) | `.getNormal()` → `.getUnitVec3i()`; 2× multi-line `Direction.fromDelta(` → `Direction.getNearest(` + `null` 4th arg; direct return in `transformDirection`, NO assert added; the existing `Validate.notNull(result)` in `dirCrossProduct` KEPT (per G2 it now also guards the impossible tie case) | C10 + G2 |

`Rotation` enum constants (`NONE/CLOCKWISE_90/CLOCKWISE_180/COUNTERCLOCKWISE_90`) verified SAME.

### 2.7 `my_util/GuiHelper.java` — 137 LOC — 2 hunks (R13e touchpoints, all in `Rect.renderTextLeft`)

- **Hunk 1**: `import net.minecraft.client.gui.GuiGraphics` → `…GuiGraphicsExtractor` — **G3**
  (GuiGraphics class GONE; extractor verified `mc262-ref/…/GuiGraphicsExtractor.java:88`).
- **Hunk 2**: param type `GuiGraphics` → `GuiGraphicsExtractor`; `guiGraphics.drawString(` →
  `guiGraphics.text(` — **G3 migration note**, 1:1 including the drop-shadow default (verified
  `26.2:GuiGraphicsExtractor.java:254` delegates to `:258` with `dropShadow=true`, same default
  as old `drawString`); arg list/order unchanged (`font, text, x, y, -1`). Callers (peripheral
  GUI) are later stages' files and translate the same way.
- All layout math, `AbstractWidget.setX/setY/setWidth`, `Minecraft.getInstance().font` — SAME
  rows, untouched.

### 2.8 `dimension/DimIntIdMap.java` — 160 LOC — 5 hunks / 7 changes

- `tag.getCompound("intids")` → `getCompoundOrEmpty` — **C2** (old returned empty compound on
  miss — exact drop-in).
- `intids.getAllKeys()` → `keySet()` — **C4** (1:1 rename form chosen, not `entrySet`).
- `intids.getInt(dim)` → `getIntOr(dim, 0)` — **C1** (inside the keySet loop + IP's own
  `contains` guard; 0 default = old wrong-type behavior).
- `.location()` → `.identifier()` ×4 (two exception messages @59/@68, `toTag` @133, `toString`
  @154) — **C8** (NBT layout wire/save-compatible — write side `put`/`IntTag.valueOf` are SAME).

### 2.9 Carried tests — verbatim, 0 hunks

`common/src/test/java/qouteall/q_misc_util/my_util/HelperTest.java` (23 LOC) and
`Mesh2DTest.java` (88 LOC) — byte-faithful to IP `src/test` modulo LF. Both HELD until S13 via
the D1 TEST list's explicit `**/HelperTest.java` + `**/Mesh2DTest.java` exclusions (see §3.4).

---

## 3. Carve-in evidence (D1 — re-derived at note-assembly, grep transcript summarized)

Method: (i) `import qouteall\.` content-grep over the entire landed
`common/src/main/java/qouteall/` tree; (ii) `import static` grep — **zero matches**, so the
plain import-grep is complete for cross-package references; (iii) same-package identifier grep
for the nine held class names (`Mesh2D|IntBox|IntMatrix3|AARotation|MyTaskList|Signal|
SignalArged|SignalBiArged|LimitedLogger`, word-bounded) over `my_util/` — because same-package
references need no import statement and import-grep alone misses them (D1 rule).

### 3.1 Import-grep: the 8 Helper-importing my_util files (ported-tree line numbers)

```
my_util/Mesh2D.java:24        import qouteall.q_misc_util.Helper;
my_util/IntBox.java:11        import qouteall.q_misc_util.Helper;
my_util/IntMatrix3.java:9     import qouteall.q_misc_util.Helper;
my_util/MyTaskList.java:9     import qouteall.q_misc_util.Helper;
my_util/Signal.java:3         import qouteall.q_misc_util.Helper;   (uses Helper.SimpleBox)
my_util/SignalArged.java:3    import qouteall.q_misc_util.Helper;
my_util/SignalBiArged.java:4  import qouteall.q_misc_util.Helper;
my_util/LimitedLogger.java:6  import qouteall.q_misc_util.Helper;
```

`Helper` is HELD (its one edge out: `Helper.java:29` → `qouteall.imm_ptl.core.McHelper`,
resolves S13) — so all 8 are held. Matches the plan's S2(a) list exactly.

### 3.2 Same-package analysis (the import-grep blind spot)

- **AARotation.java — zero `qouteall.*` imports, HELD anyway:** same-package field
  `public final IntMatrix3 matrix;` (:58) + `new IntMatrix3(` (:66) chain it to the held
  IntMatrix3 → Helper set. (Its same-package `DQuaternion quaternion` field at :59 does NOT
  contribute — DQuaternion is carved in; one fragment's "IntMatrix3/DQuaternion fields" phrasing
  is imprecise on this point, corrected here.) Held count for my_util: 8 importers + AARotation
  = **9**.
- **Negative result for all 28 carve-in files:** the held-name grep over `my_util/` matches ONLY
  the nine held files themselves (self-references) — no carve-in file references any held class,
  by import OR same-package token (119 occurrences across 11 files total; the two non-my_util
  hits are `Helper.java` and `MiscGlobals.java`, both held — see 3.3).
- **Carve-in-internal references all target carve-in files** (import-visible, since `animation/`
  is a subpackage): GeometryUtil→{Plane, Vec2d}, Circle→{Plane}, Sphere→{Plane, Circle},
  Animated→{DQuaternion, Plane, Sphere, WithDim}, RenderedLineSegment→{LineSegment, WithDim},
  RenderedPlane→{Plane, WithDim}, RenderedPoint→{WithDim}, RenderedSphere→{DQuaternion, Sphere,
  WithDim}; LimitedLogger's javadoc `{@link CountDownInt}` (held→carved, harmless).
- **GuiHelper**: client-GUI but zero qouteall imports; all its 26.2 surfaces exist
  (`GuiGraphicsExtractor`, `AbstractWidget.setX/setY/setWidth` SAME, `Minecraft.font` SAME) —
  carve-in eligible, carved in.

### 3.3 Root + dimension files (held by subtree patterns, evidence anyway)

- `q_misc_util/Helper.java:29` → `import qouteall.imm_ptl.core.McHelper;` — the ONE edge out of
  U1 (S2(b): the probe must show only this; the held my_util files resolve against in-probe
  Helper source).
- `q_misc_util/MiscGlobals.java:4` → `import qouteall.q_misc_util.my_util.MyTaskList;` (field
  `serverTaskList` at :12) — cross-package import, grep-visible; must NOT be carved in before
  S13.
- `q_misc_util/dimension/DimIntIdMap.java:10` → `import qouteall.q_misc_util.Helper;`
  (`Helper.dimIdToKey` in `fromTag`) — held.

### 3.4 Test tree (the D1.3 divergent list's justification)

- `HelperTest.java:7` → `import qouteall.q_misc_util.Helper;` — import-visible, matches the
  plan's IP citation.
- **`Mesh2DTest.java` has NO qouteall import at all** — it references `Mesh2D` **same-package**
  (package `qouteall.q_misc_util.my_util`; uses at :15, :16, :19, :53, :71 —
  `Mesh2D.encodeToGrid`, `new Mesh2D()`). This is exactly the "same-package references count"
  class the D1 rule warns about: an import-grep alone would wrongly mark Mesh2DTest carve-in
  eligible. The plan's shorthand "Mesh2DTest imports Mesh2D" is mechanically an unimported
  same-package reference; the D1 TEST list's explicit **filename** exclusions
  (`**/Mesh2DTest.java`, `**/HelperTest.java`) are therefore the right mechanism and are what
  the build agent wired.

### 3.5 buildSrc mapping (the carve-in as landed — `IpHeldPaths.groovy`)

`MAIN_HELD_PATHS` re-expresses the original blanket `qouteall/**` as: `qouteall/imm_ptl/**` +
`qouteall/q_misc_util/*.java` (root: Helper, MiscGlobals, later landings) + the four non-my_util
q_misc_util subtrees (`api/**`, `dimension/**`, `ducks/**`, `mixin/**`) + the exact 9-file
my_util held list (AARotation, IntBox, IntMatrix3, LimitedLogger, Mesh2D, MyTaskList, Signal,
SignalArged, SignalBiArged). The enumerated subtrees cover IP's complete package universe, so
every later stage's landings arrive default-held (monotone narrowing, D1).

`TEST_HELD_PATHS` diverges per D1.3: same subtree holds, `my_util/**` carved in for tests,
explicit `**/Mesh2DTest.java` + `**/HelperTest.java` exclusions until S13.

**Census check:** my_util fully landed = 37 files (32 in `my_util/` + 5 in `my_util/animation/`);
9 held ⇒ **28 carved in** (22 from the mathA slice + DQuaternion + the 5 animation files from
mathB) — matches the buildSrc list file-for-file. Grand total landed this stage: 40 main files
(37 my_util + Helper + MiscGlobals + DimIntIdMap) + 2 carried tests.

---

## 4. D4.4 — sign/convention derivations and observations (merged)

### 4.1 The one behavioral translation in the unit: G2 (`Direction.fromDelta` → `getNearest(…, null)`) domain re-derivation

26.2 `Direction.getNearest(int x, int y, int z, @Nullable Direction orElse)`
(`Direction.java:327-340`) returns a direction only on STRICT single-axis dominance, else
`orElse`. Call-site domain audit: `transformDirection` (IntMatrix3 + AARotation) feeds a
rotation-matrix-transformed unit normal — rows of `IntMatrix3` built from `AARotation` are
orthonormal ±1 unit vectors, so the product always has exactly one ±1 component;
`dirCrossProduct` feeds the cross product of two perpendicular unit axes — also an exact unit
vector. All three component positions hand-checked against the `getNearest` branch chain:
(±1,0,0)→WEST/EAST, (0,±1,0)→DOWN/UP, (0,0,±1)→NORTH/SOUTH — behavior-identical to 1.21.3
`fromDelta` on the entire input domain. On impossible inputs (zero/tied) both old `fromDelta`
and `getNearest(…, null)` return null — failure modes coincide too.

### 4.2 C10 (`Direction.getNormal()` → `getUnitVec3i()`) — value identity

Returned `Vec3i` values identical (enum-constant vectors, `Direction.java:33-38`) — no sign flip
anywhere in the axis algebra (`Helper.getUnitFromAxis`, `getBoxSurfaceInversed` shrink
direction, IntBox expansion, IntMatrix3/AARotation constructors).

### 4.3 DQuaternion (the S2 harness target)

- **Multiplication order (unchanged, recorded for the harness):** `a.hamiltonProduct(b)` = a⊗b =
  "firstly do b, then do a" (right operand applies first, column-vector-style composition);
  `a.combine(b)` = b⊗a = "firstly a, then b". `rotate(v)` = q ⊗ v ⊗ q̄ (conjugation;
  `getConjugated` is the inverse for unit q). `getCameraRotation(pitch, yaw)` = Rx(pitch) ⊗
  Ry(yaw+180) — yaw applied first, then pitch; `getCameraRotation1` is its closed form (sign
  pattern −ss, +cc, +sc, −cs verbatim).
- **Euler-angle sign asymmetry is IP-intentional — do not "fix":** `fromEulerAngle` builds JOML
  `rotateZ(roll)·rotateY(−yaw)·rotateX(pitch)` with the YAW NEGATED; `toEulerAngle` negates
  `result.y` back (`getEulerAnglesZYX`). Ported bit-for-bit.
- **`matrixToQuaternion(x, y, z)` takes the 3 ROWS of the row-vector-convention matrix**
  (Shepperd-style branch on trace, euclideanspace.com reference in-comment);
  `fromFacingVecs(axisW, axisH)` = matrixToQuaternion(W, H, W×H) — right-handed frame;
  `IntMatrix3.toQuaternion` feeds its rows directly. All coefficient signs verbatim.
- **NBT identity fallback**: `fromTag` returns identity when `!contains("x")` — IP's, distinct
  from `Helper.getQuaternion`'s null-on-absent + 0-filled-components behavior (both preserved
  exactly; the `-Or` defaults are the old getters' documented miss values, NOT "sensible" ones —
  savegame contract).

### 4.4 IntMatrix3 row-vector convention + JOML column-form bridge

The class header declares row-vector convention (`p * m`, left applies first) — IP's own
convention, stays. Its `toMatrix()` writes row i into JOML COLUMN i (JOML `set(column, row,
value)`), i.e. emits the TRANSPOSE — which for a pure rotation is exactly the column-vector
(MC/JOML) matrix of the same rotation. This is the correct bridge (matches the corpus trap note:
JOML is column-form; the IP_DEVIATIONS row-vector claim about JOML is WRONG). The header
comment's "orthogonal matrices are symmetric" wording is mathematically sloppy (it means
R_col = R_rowᵀ), but comments port verbatim — recorded here instead of edited.

### 4.5 AARotation

AARotation ↔ vanilla `Rotation` mapping (SOUTH_ROT0↔NONE, WEST_ROT0↔CLOCKWISE_90,
EAST_ROT0↔COUNTERCLOCKWISE_90, NORTH_ROT0↔CLOCKWISE_180) and the 24-entry ZX-constant table,
multiplication/inverse caches, `rotationsSortedByAngle` stable sort — all verbatim; the enum
ctor's `dirCrossProduct(transformedZ, transformedX)` derives Y as Z×X (right-handed), preserved
exactly.

### 4.6 Mesh2D (held; harness activates at S13)

- **Winding: counter-clockwise is a storage INVARIANT.** `addTriangle` normalizes every triangle
  to CCW by the sign of `Helper.crossProduct2D(p1-p0, p2-p0)` (cross > 0 kept, else p1/p2
  swapped); degenerate triangles (|cross| < 1e-9) rejected as index -1; `checkStorageIntegrity`
  asserts cross > 0 for every valid triangle. `getArea`/`getBarycenter` weights rely on this
  positivity. `subtractTriangleFromMesh` REQUIRES its input triangle CCW (doc comment), as does
  `GeometryUtil.triangleIntersectsWithAABB`.
- **Domain/grid:** point domain clamped to [-1,1]² (`indexPoint`); near-point merging grid is
  2^30 cells per side; `encodeToGrid` packs the two clamped grid ints into one long with
  `& 0xFFFFFFFFL` masking (the comment about int→long sign-extension is load-bearing). `toTag`
  stores ALL points including unused ones (triangle indexes reference positions), but only valid
  triangles.

### 4.7 Helper geometry (held)

- **Plane-side convention:** `isInFrontOfPlane` = `dot(pos − planePos, planeNormal) > 0` —
  STRICTLY greater; points ON the plane are NOT "in front". Every crossing detector keying off
  this must keep the strict inequality.
- **Line/plane t:** both `getCollidingT` overloads solve
  `t = dot(planeOrigin − lineOrigin, n) / dot(lineDelta, n)`; the 12-double overload returns
  **NaN** when `|denom| < 1e-6` (near-parallel), the Vec3 overload does NOT guard (can return
  ±Inf/NaN) — asymmetry is IP's, preserved.
- **`raytraceAABB` sign machinery:** test-face selection
  `testXPosi = (lineDeltaX > 0) ^ boxFacingOutwards` and the returned surface normal
  `testXPosi ? +1 : −1` (with the derivation comment `normalXPosi = lineDirection <= 0`) ported
  byte-identical — no 26.2 surface involved, pure math.
- **`crossProduct2D`:** positive = counter-clockwise (IP's own comment) — the 2D winding root
  used by Mesh2D consumers; untouched.
- **`getSignedCoordinate`/`putSignedCoordinate`:** DOWN/NORTH/WEST negate — matches
  `Direction.AxisDirection` signs; untouched.

### 4.8 Plane / GeometryUtil / QuadTree / Range / Sphere (carved in — live at S2)

- **Plane normal-side convention:** the canonical constructor NORMALIZES the normal; signed
  distance = `normal · (p − pos)`; the "positive side" is the side the normal points to, tested
  STRICTLY (`> 0`). `rayTraceGetT` returns NaN when |normal·lineVec| < 1e-5 (parallel);
  `rayTrace` rejects t < 0; `intersectionWithLineSegment` requires t ∈ [0,1]. Clip-equation
  form: `n·p + W > 0` with `W = −normal·pos` (`getEquationW`). `equals` is overridden (exact
  Vec3 equality) while `hashCode` stays the record default — both field-based, consistent;
  ported verbatim.
- **GeometryUtil.Line2D side convention:** sideVec = direction rotated 90° COUNTER-clockwise
  (`(−dirY, dirX)`); for a CCW triangle the side vec of each edge points INWARD, so "inside" =
  `testSideBool` true for all three edges (this drives Mesh2D's subtract-removal via centroid
  test). `testSideBool` is strict `dot > 0` with NO epsilon; `testSide` (int form) has a ±1e-5
  epsilon band mapping to 0. `getIntersectionWithLine` returns the t of THIS line, NaN when
  |det| < 1e-8.
- **`GeometryUtil.getSlicePolygonOfCube`** returns the slice polygon sorted CCW by atan2 around
  the vertex centroid; local 2D coords are `(planeX·d)/scaleX, (planeY·d)/scaleY` relative to
  `plane.pos()` — consumers (portal shape slicing) depend on this CCW output matching Mesh2D's
  CCW invariant.
- **QuadTree boundary asymmetry (IP behavior, ported verbatim):**
  `acquireNodeForBoundingBoxInternal` classifies coordinates with `>= 0` (0 counts POSITIVE)
  while `traverseInternal` selects children with `> 0` (`bbMin > 0` excludes the negative
  child, `bbMax > 0` includes the positive child) — a traverse whose bbMax is exactly 0
  therefore visits only negative-side children even though an element acquired with min exactly
  0 lives on the positive side. Zero-measure touches are otherwise INCLUSIVE downstream
  (`Range.rangeIntersects` uses `<=`). MAX_LEVEL = 8; child index layout
  `4n + (xPositive?2:0) + (yPositive?1:0)`; coordinates are node-relative, rescaled by
  `(v − ±0.5) * 2` per descent.
- **Range:** `rangeIntersects` is CLOSED (touching ranges intersect, `<=`); `intersection`
  returns null for zero-length results (`start >= end` strict).
- **Sphere.getIntersectionWithPlane uses the SIGNED plane distance** with only the one-sided
  `distance > radius` no-intersection check: when the center is on the plane's negative side
  farther than the radius (distance < −radius), it returns a Circle with NaN radius instead of
  null — IP behavior, ported verbatim (zero deviation; flagged for awareness only).

### 4.9 IntBox rounding conventions (held; tests activate S13)

`fromRealNumberBox` epsilon convention (±0.00001 floor/ceil nudges, in-comment rationale) and
`getOffsetForConfiningIntegerRange` inclusive-range math — verbatim, flagged only because they
are sign/rounding-sensitive.

---

## 5. Stage status vs S2(c)

- Diff-gate record: **complete** (§1-§2, independently re-measured; 38 hunks / 8 files; all
  hunks category-1 api-map translations except the single F4 seam import hunk in Helper).
- Mesh2D/DQuaternion derivation notes: **complete** (§4, merged from fragments + corpus traps).
- Carve-in grep evidence: **complete and re-derived** (§3; buildSrc lists match the evidence
  file-for-file).
- Forward-ref debt (S2(b)): exactly one edge — `Helper.java:29` → `McHelper` (probe expectation:
  only this symbol; held my_util files resolve against in-probe Helper source).
- Stage-gate payload: the authored DQuaternion/Plane invariant tests (S2 commit 1 deliverable,
  `:common:test` payload) are present in the tree
  (`common/src/test/java/qouteall/q_misc_util/my_util/DQuaternionTest.java` — 27 tests;
  `.../PlaneTest.java` — 11 tests) — see the NEEDS-REVIEW "Resolved item" and the
  `fragments/S02-tests.md` gate evidence (`:common:test` BUILD SUCCESSFUL: DQuaternionTest 27,
  PlaneTest 11, EventSeamTest 6, InfraSmokeTest 1; all failures=0 errors=0 skipped=0).
