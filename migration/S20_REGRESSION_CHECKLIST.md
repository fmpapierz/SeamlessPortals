# S20 CLOSE-OUT REGRESSION — the checklist to drive the live round from

**Purpose.** S20 deleted the entire block-era portal system (150 → 75 `com.warwa` java files). This
is the close-out round `EXECUTION_PLAN` §S20(d)/(e) requires: *"ALL 12"* regression items, plus the
S20-specific rows the audit rounds surfaced. Ledger of record:
`migration/port-notes/S20-block-era-deletion.md`.

**Why this document exists separately from the 12 items.** The canonical 12
(`ENTITY_PORTAL_MIGRATION_BRIEFING.md` §4) predate S20. Deleting an implementation creates its own
distinct risk class — *things that compile, boot, pass the suite, and are still broken*. §B below is
that class, and it is the part a normal regression pass would miss.

---

## §0 WHAT THE AUTOMATED GATES ALREADY PROVED (do not re-test by hand)

At `81e6f57`, before this round:

| Gate | Result | What it therefore covers |
|---|---|---|
| `compileJava` ×3 platforms | SUCCESSFUL | every deleted-class reference is gone from source |
| `:fabric:runCrossingGametest` | ALL LEGS PASS (1,2,3,4,5,6a,6b,7) | same-dim + cross-dim item crossing, transient hurt state (F3), pearls, datapack generation, ignition-generated portals both directions incl. negative coords + 8:1 scaling, >71-chunk far dests |
| the same run, mixin diagnostics | 0 apply / InvalidInjection / critical-injection failures | **the 29-entry mixin deregistration** — the suite launches a real client, so a dangling `"required": true` entry would have crashed it |
| the same run, error census | 0 mod-logic errors | no new exception class introduced by the deletion |

So the suite already discharges canonical items **1 (partly), 3, 5, 10** and the boot-crash class.

## §0.1 WHAT NO AUTOMATED GATE COVERS — the mandate for this round

The suite is first-person and headless. It cannot reach: **any GUI**, **any command**, **save/relog
cycles**, **sodium or iris**, **NeoForge**, **visual correctness of any kind** (lighting, fog,
outlines, particles, clipping), or **anything a second player would exercise**. Every row below that
is marked ✱ is reachable ONLY by a human at the keyboard.

---

## §A THE CANONICAL 12

| # | Item | How to test | S20-specific note |
|---|---|---|---|
| 1 | Crossing smooth both directions; backward/strafe crossings exit on the MOTION side; no oscillation | walk / walk-backward / strafe through, repeatedly | suite covers the item path; ✱ the *smoothness* and exit-side are visual |
| 2 | No FOV pulse / sprint loss / hand glitch / velocity zero | sprint through; watch hand + FOV | **ALREADY DISCHARGED 2026-07-26** (§G.9, user: "hand steady, sprint preserved, no FOV pulse"). Re-confirm cheaply; do not re-litigate |
| 3 | Thrown items/mobs land on the emergence side, reachable, visible immediately (no 15 s invisibility), no drift into lava | throw items + shove a mob through | suite legs 1-3 cover the mechanics; ✱ the *immediate visibility* is visual |
| 4 | Arrows fly through continuously, full speed, no 8× velocity scaling, findable far side | shoot through both ways | ✱ |
| 5 | Shot animals keep panicking after crossing | hit a cow, shove it through | suite leg 3 covers the transient hurt state carry |
| 6 | No phantom boost rocket when elytra-crossing with fireworks | elytra + firework through a portal | ✱ F2 guard; **also see §B.7 — an UNFIXED flag-ON bug is ledgered nearby** |
| 7 | Entities straddling the portal render whole in the view | stand a mob in the aperture, view from both sides | ✱ purely visual; `MixinPreparedFrame` is the clip bracket (§G.3 — it is Mechanism-A load-bearing despite B-flavoured comments) |
| 8 | Nether portal-view lighting correct BEFORE first crossing | fresh world, build a portal, look through WITHOUT crossing | ✱ **elevated risk:** the two `PortalWorldManager.lateUpdateSecondaryLight`/`endSecondaryRenderFrames` calls were excised from `GameRendererMixin` (verified flag-ON no-ops, §G.5). This item is the direct check on that reasoning |
| 9 | No chunk holes / limbo bands / distant-chunk vanish after crossings; block break/place always remeshes | cross repeatedly, break/place blocks near portals in both dims | ✱ **elevated risk:** see §B.2 (eviction driver) and §B.3 (fluid flow) |
| 10 | Large/tall portals validate crossings; negative-coordinate portals link exactly | build an oversized frame; test at negative coords | suite legs 6a/6b cover generated negative-coord + 8:1 |
| 11 | No GPU-buffer leak; no per-frame LOGGER on the render thread | hold a portal in view several minutes; watch for the multi-second paging stalls | ✱ **elevated risk:** `GameRendererMixin` is the SOLE caller of `MyGameRenderer.endFramePooled()` and was heavily edited (§G.2). This item is the direct check |
| 12 | Relog/kick/rejoin gets a clean session — BOTH lifecycle paths (`ClientLevel.disconnect` AND `updateLevelInEngines(null)`) | quit to title and rejoin; then get kicked / disconnect | ✱ |

---

## §B S20-SPECIFIC ROWS — the "compiles, boots, still broken" class

These exist *because* S20 deleted things. Each traces to a specific audit finding.

| # | Row | Why S20 created this risk | Expected |
|---|---|---|---|
| **B.1** ✱ | **Cross-dim FIRE SPREAD through a portal view** | `ServerLevelFireSpreadMixin` got a genuine PORT-FORWARD re-key (`PortalManager` link walk → `ImmPtlChunkTracking.isPlayerWatchingChunkWithinRadius`). **Its old body was gated flag-OFF-only, so this fix has NEVER run on the shipping default** — this is its first live exercise, not a regression check | light a fire in dim A visible through a portal from dim B; standing in B, the fire must spread / age / burn out rather than freeze. Also test lava→neighbour ignition |
| **B.2** ✱ | **Unbounded client store growth** (R13f) | PRE-EXISTING, not caused by S20, but S20 makes it permanent: `evictUnboundedStores` was flag-OFF-only and `ImmPtlClientChunkMap.evictBeyond` is caller-less (§E.4 item 1). Proving a growth bound needs runtime observation | long session, many crossings, wide travel; watch memory. A slow unbounded climb is the signature |
| **B.3** ✱ | **Cross-dim live FLUID FLOW through the portal view** | `LevelChunkSetBlockStateMixin`'s body was flag-OFF-only and died with the flag. Whether IP's generic dimension-tagged redirect carries sub-chunk fluid-spread updates is asserted only architecturally, never proven (§E.4 item 2) | pour water/lava in dim A while watching through a portal from dim B; flow must animate in real time, not freeze or snap |
| **B.4** ✱ | **Alternate-dimension worldgen** — skyland / bright_skyland / chaos / bright_void | `dimlib/DimensionTemplate`'s own header said "Deleted with the migration scaffolding at S20" while being flag-ON LIVE (§E.1 row 7). The header was corrected; this row proves the class really is needed | create a world with each alternate dimension; each must generate |
| **B.5** ✱ | **Hand-light fade across a crossing** | `GameRendererHandLightMixin` + `HandLightSmoother` were absent from the stage-1 inventory entirely (it covered 148 of 150 files) and are UNGATED + C6-decided KEEP (§E.1 row 8) | hold a torch, cross overworld↔nether; the hand light should FADE over ~0.6 s, not pop |
| **B.6** ✱ | **Dest entities visible in the portal view** | `LevelRendererEntityVisibilityMixin` was reshaped — its block-era disjunct stripped, leaving `isDestExtracting` (§G.2). Without it `LevelExtractor.isEntityVisible` culls EVERY dest entity | put mobs on the far side; they must be visible through the aperture |
| **B.7** ✱ | **Block-outline sliver at the portal edge** | `LevelRendererBlockOutlineMixin`'s exclusive ternary was collapsed to its flag-ON S18.5 arm (§G.11) | target a block whose selection outline overhangs the aperture; no see-through source-colour sliver |
| **B.8** ✱ | **Particles near portals** | `QuadParticleGroupMixin` collapses to an IDENTITY redirect (§increment 4) — the block-era behind-portal cull is gone and `iris-on/is5-shadow` re-introduces the flag-ON replacement | particles near/behind a portal must not visibly overdraw the aperture. **A residual bleed here is EXPECTED and is is5-shadow's §2c fix, not an S20 regression** |
| **B.9** ✱ | **A pre-migration (block-era) world loads cleanly** | `EXECUTION_PLAN` §S20(d) requires it explicitly. The block-era system that created such worlds is gone; the datafix carve-out becomes unconditional | open a world created before the migration; it must load without loss or crash |
| **B.10** ✱ | **NeoForge: the loud notice fires and nothing crashes** | **USER-DECIDED (§G.1): NeoForge has NO portals until C7.** The audit proved this state is otherwise SILENT — weave and boot both succeed and the worst symptom is a world sending ZERO chunks with no exception. A green boot proves nothing | launch the NeoForge build, **JOIN A WORLD**, confirm terrain renders and the loud "portals NOT AVAILABLE" notice is in the log. Do not accept a boot-only check |
| **B.11** ✱ | **Config screen reaches IP's config** | The ModMenu entrypoint swaps to `IPModMenuConfigEntry`, atomically with the flag (IP's Cloth config registers only inside the flag-ON arm). `SeamlessConfigScreen` + `ModMenuIntegration` are deleted | open the config via ModMenu **and** via `/imm_ptl_client_debug config` (ModMenu is NOT a dependency, so the command is the load-bearing route) |
| **B.12** ✱ | **An existing user config with the retired C4 value loads safely** | Proven from gson bytecode: a stale `"ISOLATED_STORAGE_BRACKET"` deserializes to NULL and overwrites the `SUBMIT_ORDER_UNIFORM` initializer (§G.3) | hand-edit `immersive_portals.json` to that value, launch; must not crash and must fall back cleanly |
| **B.13** ✱ | **The TITLE-CARD capture still works** after being retargeted to the entity-portal flow | `TitleCardCapture` drove the now-deleted block-era `NetherPortalBlock.entityInside` path (§C) | run `:fabric:runClientGametest`; a screenshot must land in `fabric/runs/gametest/screenshots/` |

---

## §C THE SODIUM / IRIS MATRIX — scoped honestly to THIS branch

**Read this before filling in any sodium/iris row.** `S20_SESSION_PROMPT.md` §B.3 is explicit: the
shaders-ON stack has moved a long way on `iris-on/is5-shadow` — **six shipped polish fixes plus an
ACT probe kit, NONE of it merged here.** A row may **not** be reported green on the strength of work
that lives on another branch.

| Row | On this branch | Verdict semantics |
|---|---|---|
| plain (no sodium, no iris) | the proven stencil path | must be perfect; any defect is an S20 regression |
| sodium, no iris | C2 chains active (`SodiumCompat`, the endFrame walk, the D11 feed) | ✱ **elevated risk:** `SodiumCompat` was nearly deleted (§E.1 row 1) and the D11 feed had to be re-homed out of a deleted branch (§E.1 row 3). Without the feed **the main world renders BLANK** — that is the signature to watch for |
| sodium + iris, shaders OFF | full portal views | ✱ |
| sodium + iris, shaders ON | `IrisCompatOn262Renderer` (IS1–IS4 merged; **IS5+ is NOT**) | ✱ known-incomplete by design. Record defects as IS-era items, **not** S20 regressions |

---

## §D THE REST OF §S20(e)

| Area | Test |
|---|---|
| Wand suite ✱ | create / drag / delete a custom portal; the command stick |
| Dim stack ✱ | the dim-stack GUI from the create-world screen |
| Mirrors ✱ | place a mirror; check winding/handedness (the `Mirror` corner-reversal path) |
| Global portals ✱ | create one; **relog** and confirm it persists (empty-on-return is the swallowed-NPE signature) |
| Scaled / rotating portals ✱ | non-1:1 scale and an animated rotation |
| Save / relog cycles ✱ | canonical item 12, plus per-dimension |
| Datapack generation | suite leg 5 covers decode-at-world-open |
| The 8-leg suite | re-run at the very end, after every increment-4 edit |

---

## §E HOW TO REPORT

For each row: **PASS**, **FAIL + what you saw**, or **NOT TESTED**. "Not tested" is a valid and
useful answer — a false PASS is the expensive one. Per the standing rule, your live observation
outranks any reading I make from a log or screenshot; if we disagree, you are right and I
re-instrument.

For any FAIL, note whether it reproduces **plain** (no sodium/iris) — that single fact separates an
S20 regression from a C2/IS-era item and decides who owns the fix.
