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

## §R ROUND RESULTS — live round 2026-07-26 (increment 4 @ `bd551bd`), IN PROGRESS

Automated, discharged without the user:

| Row | Verdict | Evidence |
|---|---|---|
| compile ×3 / 8-leg suite / mixin diagnostics | **PASS** | BUILD SUCCESSFUL; ALL LEGS PASS (1,2,3,4,5,6a,6b,7) exit 0; 0 apply/injection failures; 0 mod errors |
| new `FABRIC_ONLY_IP_DRIVERS` inert on Fabric | **PASS** | 0 `Skipping IP-driver mixin` lines in the suite run — required, or the set would be skipping live Fabric mixins |
| **B.12** stale C4 enum in a user config | **PASS** | armed the real trap (`"crossPortalEntityClipMechanism": "ISOLATED_STORAGE_BRACKET"` in `runs/client/config`), launched: no crash, `iPortal Config Applied`, key dropped on re-save |
| **B.13** title card re-shot | **PASS** | `:fabric:runClientGametest` green, both screenshots land; window shows a live NETHER view from an overworld meadow with no teleport (2nd take — see the B.13 commit for why the 1st was useless) |
| **B.11** (static half) | **PASS** | built jar's `fabric.mod.json` names `IPModMenuConfigEntry`; zero occurrences of the 4 deleted classes in the jar |

User-observed (their observation outranks any log/screenshot reading of mine):

| Row | Verdict | Note |
|---|---|---|
| **8** portal-view lighting before first crossing | **PASS** | the direct check on the two excised `GameRendererMixin` calls (§G.5) — the no-op reasoning holds |
| **9** chunk holes / remesh | **PASS** | |
| **11** GPU-buffer leak / render-thread stalls | **PASS** | the direct check on `endFramePooled()` surviving the `GameRendererMixin` edits (§G.2) |
| **B.11** config screen | **PASS** | config visible |
| **B.6** dest entities in the view | **PASS**, one carve-out | same-dim FAR portals: hostiles >128 blocks from the player are despawned by VANILLA. Fixed on `iris-on/is5-shadow` (its `PortalTicketDespawnSuppressor` + `MobDespawnSuppressMixin`, §E.6) → merge-forward item, not S20 |
| **B.1** cross-dim fire spread | **PASS** cross-dim / **FAIL** same-dim far | see §R.1 |
| **B.3** cross-dim fluid flow | **PASS** cross-dim / **FAIL** same-dim far | see §R.1 |
| **B.2** unbounded client store growth | **PASS** | no growth signature observed. NOTE the honest limit of this verdict: §E.4 item 1 says proving a *bound* needs sustained observation, so this reads as "no climb seen in this session", not "bounded" |
| **B.5** hand-light fade across a crossing | **PASS** | "nice and smooth" — vindicates the §E.1 row 8 refusal to let a package sweep take `GameRendererHandLightMixin` + `HandLightSmoother` (they were absent from the stage-1 inventory entirely) |
| **B.7** block-outline sliver at the aperture | **PASS** | the collapsed S18.5 ternary arm is correct |
| **B.9** pre-migration (block-era) world loads | **PASS** | the D3 datafix carve-out doing its job |
| **B.4** alternate-dimension worldgen | **PARTIAL PASS** — skyland observed live | User deferred the row, but the session log settles part of it anyway: `ClientWorld immersive_portals:skyland` appears with terrain and live falling-block entities, so **skyland generated, loaded and ticked**. That is the §E.1 row 7 check passing for one of four templates — `dimlib/DimensionTemplate` is proven needed, and its old "Deleted with the migration scaffolding at S20" header proven a lie. bright_skyland / chaos / bright_void remain untested (they share the same `createLevelStem` path, so the risk is now low) |

### §R.2 Two ported-IP diagnostics seen in the session — NOT S20 regressions

Both fired in the live client and neither is ours:

| Line | Verdict |
|---|---|
| `[ImmPtlChunkTickets] Chunk loading failure ServerWorld minecraft:overworld New World [-2, 1]` (×1, at world start) | `ImmPtlChunkTickets:211` — **S20 never touched this file** (`git log 20e1670..HEAD` on it is empty) |
| `[ImmPtl] cross portal collision result too large FallingBlockEntity[…skyland…]` (×2) | `MixinEntity:137` — likewise untouched by all of S20. It is IP's own rate-limited guard (`IMM_PTL_LOG_COUNTER`) with a defined fallback: a >20-block collision result is clamped to `Vec3.ZERO`. Falling gravel in a floating-island dimension is a plausible trigger |

Recorded as inherited-IP observations for a later parity pass, not as S20 blockers. Neither
`SEAMLESS STUCK` nor `SEAMLESS FREEZE` appeared (the canonical-11 stall signature): **0 occurrences**.

### §R.0 THE CANONICAL 12 — run twice, once per portal kind (2026-07-26)

The user ran §A against ORDINARY portals and again against LONG-DISTANCE SAME-DIM portals. Running
it twice was not asked for and is the most valuable thing this round produced: it converts §R.1 from
"fluids and fire don't update" into a six-symptom family with a single discriminator.

| # | Ordinary portal | Long-distance SAME-DIM portal |
|---|---|---|
| 1 crossing smoothness / exit side | **PASS** | **PASS**, but *"sometimes flashes"* → §R.1(f) |
| 2 FOV / sprint / hand | **PASS** | **PASS** |
| 3 items + shoved mob land reachable, visible immediately | **PASS** | hostile despawn (known, is5); *"sometimes friendly mobs disappear but don't despawn"* → §R.1(d) |
| 4 arrows continuous, full speed | **mostly PASS** — see §R.3 (seam hit when the portal is boxed in by blocks) | **PASS** |
| 5 shot animals keep panicking | **PASS** | **PASS** "when not invisible" → §R.1(d) |
| 6 no phantom elytra boost | **PASS** | **PASS** |
| 7 straddling entity renders whole | **PASS** | **FAIL** — *"cut off"* → §R.1(e) |
| 10 large/tall + negative coords | **PASS** | **PASS** |
| 12 relog / kick / rejoin | **PASS** | not reported |
| 9 chunk holes / remesh | **PASS** (ordinary) | not reported |

★ The pattern: **ordinary portals are clean across the board.** Every failure in this round sits in
the same-dim distant column. That is a far sharper discriminator than the original two rows gave.

### §R.3 Arrow stops at the seam when the portal is boxed in by blocks (ordinary portals)

User: *"sometimes if the area around portal is cramped with blocks, the arrow will hit the portal
seam like a solid and fall down at the seam, but good everywhere else."* Reproduces on ORDINARY
portals, so it is NOT part of the §R.1 family. Note the live session also logged
`[ImmPtl] cross portal collision result too large` at `MixinEntity:137`, which CLAMPS an oversized
crossing result to `Vec3.ZERO` — a candidate mechanism for "hits it like a solid and drops".
Ownership under investigation; `MixinEntity` is untouched by all of S20 (§R.2), which points at
inherited IP behaviour rather than a deletion artefact.

### §R.1 ★ THE SAME-DIM DISTANT-PORTAL DEFECT FAMILY — six symptoms, one discriminator

**Symptom (SIX faces of one signature).** Through a portal whose destination is a DISTANT
SAME-DIMENSION region:

| | Symptom | First seen |
|---|---|---|
| (a) | fire does not spread / age | B.1 |
| (b) | fluids do not flow | B.3 |
| (c) | particles do not render | B.1 round |
| (d) | entities intermittently INVISIBLE — *"friendly mobs disappear but don't despawn"* | §R.0 rows 3, 5 |
| (e) | an entity straddling the aperture renders **cut off** | §R.0 row 7 |
| (f) | the crossing *"sometimes flashes"* | §R.0 row 1 |

The identical scenarios all work when the destination is ANOTHER dimension, and ordinary portals
pass every row. **(d) and (e) matter more than (a)-(c) for ownership**, because they bear on a file
S20 itself reshaped: increment 3 stripped the `PortalContextSwitch.isRenderingPortal ||` disjunct
from `LevelRendererEntityVisibilityMixin`, leaving only `SecondaryWorldRenderCore.isDestExtracting`.
If that bracket does not enclose the SAME-DIM pipeline, S20 removed the only true condition there —
which would make (d)/(e) a genuine S20 REGRESSION rather than a pre-existing gap. **Under
investigation; do not classify (d)/(e) from the (a)-(c) reasoning below** — the note that S20 is not
to blame was established for the block-update family only.

**Not caused by S20 — verified, not assumed.** At `81e6f57^` the block-era mirror that used to carry
these updates (`LevelChunkSetBlockStateMixin` → `RemoteBlockUpdater`, the 2026-04-26 feature) opened
with `if (SeamlessPortalsConfig.isEntityPortals()) return;` — **flag-OFF ONLY**. The flag has
defaulted ON since the S17 cutover (2026-07-18), so it has not run on the shipping default since
months before S20. S20 deleted code that was already dormant in the shipped configuration; it did
not remove a working feature. What S20 does is make the gap PERMANENT (the same shape as B.2's
eviction gap, §E.4 item 1).

**It is §E.4 item 2, resolved by observation.** That row recorded the mixin's own claim — *"Flag ON →
IP tracking's native block sync replaces it"* — as asserted architecturally and never proven. The
live round proves it **half false**: the redirect carries cross-dim updates and does not deliver
same-dim distant ones.

**Triage the round already narrows (do not re-derive):**
- **Entity sync WORKS in the same-dim far view** (B.6: mobs visible and moving; only vanilla-despawn
  hostiles missing). Entity sync rides `PacketRedirection` exactly as block sync does.
- `MixinChunkHolder.redirectGetPlayers` routes block-update recipients through
  `ImmPtlChunkTracking.getPlayersViewingChunk(dimension, x, z, boundaryOnly)`, which is
  **dimension-agnostic** — it returns every watcher with `isLoadedToPlayer`, same-dim included.
- `PacketRedirection.createRedirectedMessage` wraps unconditionally; it does not special-case the
  player's current dimension.

⇒ "The redirect is broken" is REFUTED. The fault is specific to the block-update path for same-dim
distant regions. **Next move is a discriminating probe** (standing rule 1: instrument before
theorizing) — the open question is where the chain breaks: server-side watch record
(`isLoadedToPlayer` for the far same-dim chunk), the broadcast reaching `ChunkHolder.broadcastChanges`
at all, or the client-side apply into the main `ClientLevel`. Particles are likely a SEPARATE
mechanism in the same family (vanilla distance-filtered `ClientboundLevelParticlesPacket`), so probe
them separately rather than assuming one cause.

**Ownership: a named post-S20 work item, not an S20 blocker.** Recorded here rather than fixed
inside a deletion stage, per `EXECUTION_PLAN` §S20(a)'s deletion-only scope.

### §R.1a THE INVESTIGATION — 6 traces + 6 adversarial verifiers (12 agents, 2.5M tokens)

**Headline, and it SURVIVED adversarial verification: S20 caused none of (a)–(f), nor the arrow
seam.** The load-bearing argument is structural rather than per-symptom: every S20 deletion sat
inside the `else` arm of `if (isEntityPortals()) { IP } else { block era }`, and the shipping default
has taken the `if` arm since the S17 flip (2026-07-18). Code the default never executed cannot have
regressed the default. Verified independently per lane against `git log 20e1670..HEAD`.

**The one claim that had to be killed first**, because the port-note itself raised it: that increment
3 broke entity visibility by stripping `PortalContextSwitch.isRenderingPortal ||` from
`LevelRendererEntityVisibilityMixin`. **Refuted twice over, and re-derived by hand:**
1. The same-dim pipeline **arms the surviving disjunct itself** — `SecondaryWorldRenderCore:2337-2345`
   sets `isDestExtracting = true` around its extract in a `try/finally`. Same-dim never lost the
   condition.
2. The stripped disjunct was **already dead on the default**. `isRenderingPortal` had exactly two
   writers pre-S20 (`PortalContextSwitch:1531/:2035`), both inside the block-era FBO render, reachable
   only from `StencilPortalRenderer.renderPortals()`, registered at
   `SeamlessPortalsClientFabric:178` — inside the `} else {` at `:163`, the **flag-OFF arm**.

**Per-symptom verdicts after refutation:**

| | Verdict | Basis |
|---|---|---|
| (a) fire | **PRE-EXISTING GAP**, but see §R.1b — S20 wrote a same-dim skip whose stated premise the live round refutes | `ServerLevelFireSpreadMixin` |
| (b) fluids | **NOT S20**; exact break-link unresolved | block-update chain is dimension-agnostic at every link inspected |
| (c) particles | **INHERITED IP** (provisional) — `ClientWorldLoader.tick()` runs the remote `animateTick` pump only `if (CLIENT.level != world)`, so a same-dim destination structurally cannot get ambient particles; vanilla `sendParticles` filters at 32 blocks and IP redirects no particle packet at all. This *predicts* the cross-dim/same-dim split with no extra cause | `ClientWorldLoader:169-173`, `:266-294` |
| (d) invisible entities | **NOT S20 (high confidence). CAUSE UNRESOLVED — two live candidates** | see below |
| (e) straddling cut off | **UNCERTAIN, cause unknown.** No S20-attributable change on the default | the projection path never consults `isEntityVisible`, so (d)'s mechanism does not explain it |
| (f) flash | **NOT S20**; the known same-dim flash fix (`ca6e93b`) is sodium-specific and the observation was plain Fabric | |
| arrow seam (§R.3) | **INHERITED IP** — `git log 20e1670..HEAD` empty for all ten files in the projectile/collision chain | |

**(d)'s two surviving candidates — the verifier downgraded the first from "cause" to "hypothesis":**
1. **The `getRenderSectionAt` preset-wrap alias.** Same-dim deliberately does NOT reposition the dest
   ViewArea grid (`SecondaryWorldRenderCore:656-666` — "moving it would corrupt the main frame"), so
   the preset stays pinned to the player. `ImmPtlViewArea.getRenderSectionAt(BlockPos)` wraps via
   `positiveModulo` into that preset **with no occupant guard**, so a query for a far destination
   returns an unrelated near-player section and the mixin reports *its* compiled state.
   ★ **This hazard was already known and explicitly deferred to us**: `ImmPtlViewArea:553-555`
   carries, in code, *"NOTE: getRenderSectionAt (BlockPos-keyed) shares the wrap hazard — ledgered
   for the S20 audit, not changed here."* The node-keyed sibling got the vanilla-parity guard at
   IS §2.6; this one did not. **Verifier's caveat, which stands:** the aliased slot is normally an
   in-window, compiled chunk, so the alias usually returns TRUE — it can fire, but it does not
   self-evidently produce dropouts, and there is no runtime evidence yet.
2. **The more parsimonious family the trace did not exclude:** the distant same-dim destination's
   entities simply are not reaching or persisting on the client (`MixinTrackedEntity:182-271`,
   `EntitySync:47-70`). This one would also cover (a)/(b)/(c) with a single cause.

**Residual closed by hand:** a verifier flagged that if the user had `crossPortalEntityClipMechanism
= ISOLATED_STORAGE_BRACKET` selected pre-S20, S20's removal of Mechanism B could itself explain (e).
It cannot: their run-dir config read `"crossPortalEntityClipMechanism": "SUBMIT_ORDER_UNIFORM"`
before this round touched it (the value only became `ISOLATED_STORAGE_BRACKET` because B.12 armed it
deliberately, on a build where the field no longer exists). They were on Mechanism A both sides of
S20.

**THE THREE CHEAP DISCRIMINATING PROBES** (verifier-recommended; each settles one open question):
- **(b) vs the whole family:** at a far same-dim portal, **place or break one ordinary block** at the
  destination. If a plain block change *does* appear, the break is fluid/fire-specific; if it does
  not, one transport-level cause covers (a)/(b)/(c).
- **(d) candidate 1:** log when `getRenderSectionAt` returns a section whose node ≠ the queried node.
  One armed session proves or kills the alias outright.
- **(e):** put a mob straddling the aperture of a **SHORT-distance same-dim** portal. If it is cut
  off there too, distance is irrelevant and (e) is a same-dim projection defect, not a §R.1 member.

### §R.1c ★★ THE FIXES, LIVE-TESTED (2026-07-26, build `8f51bad`) — (d)+(e) FIXED, and the rest resolves to ONE cause owned elsewhere

| Symptom | Result | Owner |
|---|---|---|
| **(d) entities intermittently invisible** | ★ **FIXED** — user: *"entities good now"* | this branch, `immPtl_getRenderSectionExact` |
| **(e) straddling entity cut off** | ★ **FIXED** — user: *"mob straddling aperture is good"* | same fix |
| hostile mobs still despawn at the far dest | NOT ours — vanilla's 128-block despawn rule; fixed on `iris-on/is5-shadow` | merge-forward |
| **(a) fire / (b) fluids / (c) particles / block placement** | **NOT FIXED here, and correctly so** — one cause: *"far same dim dest stuff doesn't auto update"*; a block change is invisible **until you actually teleport to the destination** | **being fixed on the `redstone` worktree — leave it alone here** |
| regression watch (main world, cross-dim) | **PASS** — user "all good"; logs confirm 0 mod errors, **0 AIOOBE**, 0 stalls, `endSecondaryFrames` 14.2ms/545 per 5s | |

**Two things this settles.**

1. **The coord-exact entity gate was the right call, and the critic's counter-argument did not
   materialise.** Verification warned the fix could make (d) WORSE (the aliased section is usually
   compiled, so the old wrap may have biased toward SHOWING entities). Live result: strictly better,
   and it also cleared (e) — which the trace agent had explicitly failed to explain. The caveat was
   worth stating and the fix was worth shipping scoped to one consumer; both halves of that judgement
   held up.
2. **The remesh hypothesis is CONFIRMED, from the user's own framing.** *"Doesn't auto update … until
   you actually teleport"* is exactly the critic's third point: distant same-dim destination sections
   are never remeshed client-side, so fire, fluids, particles and ordinary block placement all freeze
   together. It follows that **§R.1b's fire fix is correct but not sufficient on its own** — the
   server now grants the spread (`[SEAMLESS FIRE] allow portal-watched fire spread … in
   minecraft:overworld (watcher … in minecraft:overworld)`, logged live, same dimension both sides),
   and the client simply does not redraw it. KEEP the fix: it is the server half of a two-half
   problem, and the client half is landing on `redstone`. Without it, that work would fix the redraw
   and the fire would still be frozen by the gamerule.

### §R.1b ★ A DEFECT IN S20's OWN CODE — the fire-spread same-dim skip rests on a false premise

Not a regression (the pre-S20 body was flag-OFF-only, so same-dim distant fire never worked on the
default either) — but this is a line **S20 wrote**, and the live round refutes its justification:

```java
// Same dim → vanilla's own player-proximity check already covers it.
if (player.level().dimension().equals(thisDim)) continue;
```

`ServerLevelFireSpreadMixin`'s javadoc states it outright: *"The same-dimension case is still
skipped: vanilla's own player-proximity check already covers it, and re-answering it here would
widen the gamerule."* That premise holds for a portal a few blocks away and **fails for a destination
hundreds of blocks away**, which is precisely the case the user tested. The narrow fix is to skip
only when the player is genuinely within vanilla's proximity, rather than skipping on
same-dimension-ness — with the gamerule-widening concern answered by the existing
`isPlayerWatchingChunkWithinRadius` filter, which is the same guard the cross-dim arm already trusts.

## §E HOW TO REPORT

For each row: **PASS**, **FAIL + what you saw**, or **NOT TESTED**. "Not tested" is a valid and
useful answer — a false PASS is the expensive one. Per the standing rule, your live observation
outranks any reading I make from a log or screenshot; if we disagree, you are right and I
re-instrument.

For any FAIL, note whether it reproduces **plain** (no sodium/iris) — that single fact separates an
S20 regression from a C2/IS-era item and decides who owns the fix.
