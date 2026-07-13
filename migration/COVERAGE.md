# Coverage audit of `migration/inventory/` (independent enumeration, 2026-07-12)

Method: every `.java` file under the two IP roots and the three current-mod source roots was
enumerated with `find`, assigned to exactly one owner inventory doc by the slice declarations the
docs themselves make (their `Slice:`/`Scope:` headers), and then checked for presence in that owner
doc by exact class-name match. Files that failed the exact-name check were opened and read, and the
owner doc's treatment of them was verified against the actual IP source. Depth was audited by
sampling the largest and most-depended-on entries of every doc that self-describes as "triage".

## Enumeration totals

| Tree | Files | Owner docs |
|---|---|---|
| `ImmersivePortalsMod/src/main/java/qouteall/imm_ptl` | 437 | 14 IP-side docs |
| `ImmersivePortalsMod/src/main/java/qouteall/q_misc_util` | 55 | `q-misc-util.md` + `network.md` |
| `Portal 26.2/common/src` | 128 | `current-mod-core.md`, `current-mod-render.md` |
| `Portal 26.2/fabric/src` + `neoforge/src` | 8 | `current-mod-core.md` |

The 16 docs' slice declarations form a complete partition of the 492 IP files — no file is
unclaimed, and the deliberate double-claims are consistent (chunk_sync mixins owned at full depth by
`chunk-loading.md` and re-triaged by `mixin-common.md`; `peripheral/portal_generation/` explicitly
excluded from `platform-compat-peripheral.md:10` and owned by `portal-generation.md:7`; the three
q_misc_util network files explicitly cross-referenced-only in `q-misc-util.md:8-9` and owned by
`network.md:4-5`; `render/renderer/` excluded from `render-core.md` overview and owned by
`render-sub.md:4-6`).

## Result: no BLOCKING gaps

Every core-subsystem IP file has a real per-file entry (not a name-drop) in its owner doc, with
file:line citations. The self-described "triage" docs (`mixin-client.md`, `mixin-common.md`) are in
fact per-injection depth — e.g. `mixin-client.md:92` documents all 19 `MixinLevelRenderer` handlers
individually with line ranges, and `mixin-common.md:127` gives `MixinEntity` ("the collision core")
its own detail section. No file that other subsystems depend on heavily was left at listing-only
depth.

## Files covered only via grouped entries (verified accurate — NOT gaps)

Five IP files never appear by their own full name in any doc; each is covered by a grouped entry
whose group claim I re-verified against source:

1. `imm_ptl/core/mixin/common/position_sync/MixinServerboundMovePlayerPacketPosRot.java`
2. `imm_ptl/core/mixin/common/position_sync/MixinServerboundMovePlayerPacketRot.java`
3. `imm_ptl/core/mixin/common/position_sync/MixinServerboundMovePlayerPacketStatusOnly.java`
   — covered as "`MixinServerboundMovePlayerPacketPos` / `...PosRot` / `...Rot` / `...StatusOnly` —
   four identical mixins" (`mixin-common.md:96`, mechanism at `:105` citing only the `Pos` variant).
   **Verified:** all four are structurally identical — same `@Inject` into the static
   `read(FriendlyByteBuf)` at RETURN doing `buf.readResourceKey(Registries.DIMENSION)` into the
   duck; only the `@Mixin` target inner class differs (each file, lines 14-20).
4. `imm_ptl/core/compat/mixin/flywheel/MixinFlywheelProgramCompiler.java`
5. `imm_ptl/core/compat/mixin/flywheel/MixinFlywheelQuadConverter.java`
   — covered as "`compat/mixin/flywheel/` (3 files) — DEAD even upstream"
   (`platform-compat-peripheral.md:140-141`, citing only `MixinFlywheelCrumblingRenderer`).
   **Verified:** both are `@Pseudo` mixins targeting 1.18-era `com.jozufozu.flywheel.core.*`
   class names that cannot exist on modern Flywheel (`MixinFlywheelProgramCompiler.java:11-12`,
   `MixinFlywheelQuadConverter.java:11-12`), each cancelling an invalidation callback while
   `ClientWorldLoader.getIsCreatingClientWorld()` — exactly as the doc's group entry states.

## INFO gaps

### INFO-1 — `common/src/main/java/com/warwa/seamlessportals/SeamlessPortalsConstants.java` (current mod, uncovered)
The only source file in either tree covered by NO inventory doc. Content: `MOD_ID`, the shared
`LOGGER`, and the `VERBOSE_RENDER_LOG` master switch + `rlog()` gate for render-thread diagnostic
logging (`SeamlessPortalsConstants.java:7-26`). Trivial as code, but the `rlog` gate encodes the
"never log per-frame on the 26.2 render thread" rule (the ~130ms log4j stall), and the migration's
ported render code must keep routing diagnostics through it — worth one disposition line
(KEEP) in `current-mod-core.md`.

### INFO-2 — IP unit tests uncovered (2 files)
`src/test/java/qouteall/q_misc_util/my_util/HelperTest.java` and `Mesh2DTest.java` are mentioned by
no doc. Not shipped code, but `Mesh2DTest` is a ready-made correctness harness for `Mesh2D`
(1,753 LOC, the largest class in the q_misc_util slice per `q-misc-util.md:168`, load-bearing for
`SpecialFlatPortalShape` and `/portal shape sculpt`). Porting Mesh2D without carrying its test over
would discard free verification.

### INFO-3 — Iris/Sodium compat left at triage (conditional on scope)
`compat/iris_compatibility/` (8 files), `compat/sodium_compatibility/` (3), `compat/mixin/iris/` (7)
and `compat/mixin/sodium/` (9) have per-file triage rows in `platform-compat-peripheral.md` but no
injection-level depth. This matches the doc's own classification (THIRD-PARTY-COMPAT; port only if
the mod exists on 26.2, `platform-compat-peripheral.md:14-15`) and is correct for the current scope.
It becomes a real gap ONLY if Sodium/Iris support on 26.2 enters scope — in that case these 27 files
need a full-depth pass, including the alternate `PortalRenderer` subclasses
(`IrisPortalRenderer`/`ExperimentalIrisPortalRenderer`/`IrisCompatibilityPortalRenderer`) that
`PortalRenderer.switchToCorrectRenderer()` selects.

### INFO-4 — External DimLib dependency (already handled, recorded for completeness)
IP main source imports `qouteall.dimlib.DimensionTemplate` and `qouteall.dimlib.api.DimensionAPI`
(the only two dimlib symbols used, grep-verified over the whole tree); DimLib's source is NOT in the
enumerated tree. This is not an inventory gap: the event surface is documented at its call sites
(`chunk-loading.md:308`, `network.md:276`, `portal-generation.md:179`, `q-misc-util.md:32-33`,
`platform-compat-peripheral.md:315`) and the porting decision is owned by
`DEPENDENCY_ORDER.md:49` (static-dimension stub of the event wiring) and `API_RISKS.md:480-495`.

## Current-mod coverage detail

All 136 current-mod files except `SeamlessPortalsConstants.java` (INFO-1) appear in
`current-mod-core.md` or `current-mod-render.md`, including all 78 mixins, both loader modules
(`fabric/src` 6 files incl. `gametest/TitleCardCapture.java`, `neoforge/src` 2 files).

## Severity summary

| # | Item | Severity |
|---|---|---|
| 1 | `SeamlessPortalsConstants.java` in no current-mod doc | INFO |
| 2 | IP `src/test` (HelperTest, Mesh2DTest) unmentioned | INFO |
| 3 | Iris/Sodium compat at triage — adequate unless scope changes | INFO |
| 4 | DimLib external — handled in DEPENDENCY_ORDER/API_RISKS, no action | INFO |

**BLOCKING gaps: none.** Grouped-entry coverage (5 files) was adversarially re-verified against IP
source and found accurate.
