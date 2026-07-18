# S15 — Bring-up rung 3: entity traffic + F2/F3 + gametest

Stage spec: `migration/EXECUTION_PLAN.md` §S15. Resume doc: `migration/S15_HANDOFF.md`.
Unified-path state at stage open (audited 2026-07-18): `Portal.SERVER_PORTAL_TICK_SIGNAL` →
`getEntitiesToTeleport` → `startTeleportingRegularEntity` → `teleportRegularEntity` is fully
ported and LIVE-PROVEN (the S14.47 capture logged this exact path attempting firework
teleports). Flag-ON exclusivity confirmed: `EntityMixin`'s block-era non-player detection
returns early on `isEntityPortals()` (D3 row 2), so the IP scan is the sole non-player
detector. Cross-dim non-player crossings recreate via `changeEntityDimension(..., true)`
(`restoreFrom` + same-id), vehicles via `teleportVehicleAcrossDimensions` (inline recreate).

## 1. F2 REGISTER ENTRY — attached-firework skip (reproduce-then-apply: PRE-COMPLETED S14.47)

**Register row:** EXECUTION_PLAN §Deviations F2 — "Attached-firework skip in the unified
crossing path", reproduce-then-patch class, owning stage S15.

**Hazard (current-mod-core §11.3):** IP's unified path teleports the ATTACHED elytra-boost
firework when its glued segment crosses with the player's. The `restoreFrom` recreate does not
persist `attachedToEntity` (no NBT), so an in-range attempt recreates the rocket DETACHED in
the dest — a vertical free-flight phantom at the player's emergence point. Under PLAYER REUSE
a second mechanism compounds it: the source-world orphan keeps a LIVE `attachedToEntity` ref
cross-dim, gluing to the player's dest-world coordinates and injecting elytra boost every tick.

**Reproduced on the PORTED path: 2026-07-18** (S14.47 live capture, user elytra-boost
crossing). Log-proven: `"Entity is too far to teleport FireworkRocketEntity"` rejection lines
= `ServerTeleportationManager.teleportRegularEntity` attempts on the attached rocket; the
in-range attempts produced the user-visible phantom ("a phantom rocket shoots out in front of
me"); the orphan half produced the `"[ImmPtl] Skipping collision calculation because entity
moves too fast"` stack spam (60+ block per-tick glue moves).

**Applied: 2026-07-18, commit `63174f6` (S14.47):**
1. Attached-firework skip in `ServerTeleportationManager.shouldEntityTeleport` — the SHARED
   decider, so one guard covers all three IP scan sites (per-portal tick scan, global-portal
   scan, colliding-portal scan).
2. `changePlayerDimension` orphan discard — pre-move `inflate(8)` sweep of the player's
   attached rockets in the source world (vanilla's "boost rocket is lost through a portal"
   outcome re-expressed under player reuse; attached rockets are invisible → zero visual).
3. New accessor `FireworkRocketEntityAccessor` (always-active mixin set).

**Verified:** Fable adversarial verify `wf_a1b18604-e2c` PASS; user-confirmed gone at S14
close (rung-2 sign-off 2026-07-18). S15 (d).4's "BEFORE" half is therefore satisfied by the
S14.47 capture; the (d).4 live step re-confirms the "AFTER" state only.
