# S20 PORT-NOTE — the block-era deletion + survivor audit + the final 12-point regression

**Ledger of record for S20.** Branch `s20/block-deletion`, based on `claude/nifty-kepler`
@ `e8e4767`. Governing docs: `migration/S20_HANDOFF.md`, `migration/S20_SESSION_PROMPT.md`
(the delta corrections), `migration/S19_HANDOFF.md` §0 (standing rules),
`migration/EXECUTION_PLAN.md` §S20.

---

## §0 MUST-DO-FIRSTS — both RESOLVED before any deletion

### §0.1 The block-era FBO precedent — DECISION: **ACCEPT GIT-HISTORY MINING** (write-up already exists)

The handoff required a decision recorded either way. Two independent findings, both verified on
this branch rather than assumed:

**Finding 1 — the write-up already exists and is adversarially verified.** Commit `df3ac1f`
(`migration/FBO_PRECEDENT_MINING.md`, 925 lines) mined the block-era mirror-FBO
"compatibility render mode" at HEAD `a375efe`, BEFORE the S20 deletion, with 5 Opus facet
miners + 1 Fable adversarial verify (1 substantive correction, 2 framing precisions). It
covers exactly what §0.1 asked for: §1 the verified `doFboRender` sequence, §2 the FBO
lifecycle (UUID-keyed full-screen `TextureTarget`, single-buffered, evict-on-leave + the
world-exit teardown gap), §3 the paste, §4 `withSwitchedWorld` as the bracket template, §5 the
SodiumBridge arm mechanics, §7 the transfer map. So the §0.1 obligation ("either extract a
reference write-up first, or accept git-history mining") is discharged by the stronger of the
two options — the write-up — and git history backstops it.

**Finding 2 — the live dependence has in fact moved on (§B.1's premise, verified not assumed).**
Read-only survey of the `iris-on/is5-shadow` doc corpus (`git show`, the worktree never
touched):

| Doc | Cites the block-era FBO precedent? |
|---|---|
| `IRIS_SHADERS_ON_HANDOFF.md` (the pre-engagement entry doc) | YES — `:38-39`, `:84`, `:100-101` |
| `PER_DEST_STATE_RECON.md` | NO — cites `MyGameRenderer:626/:690 setPipeline` bracket (FOLD-1, §3.3) and `IrisShadowCompositeSuppressor` as *"the only live in-tree precedents"* (`:721`) |
| `POLISH_ROUNDS_CLOSED_HANDOFF.md` | NO (zero hits) |
| `POLISH_SESSION_NOTES.md` | NO — cites the Fix-1 shadow-scope precedent |
| `PORTAL_VIEW_POLISH_HANDOFF.md` | NO |
| `FULLBRIGHT_HANDOFF.md` | NO — cites `ShadowEmptinessProbe`, `MixinSodiumProbe_GlCommandEncoder`, IP's `ExperimentalIrisPortalRenderer` |

Only the original entry doc names it; every doc written *during* the engagement cites different,
surviving in-tree precedents. `PER_DEST_STATE_RECON.md:721` is explicit that the live precedents
are `IrisShadowCompositeSuppressor` + the `MyGameRenderer` `getPipeline`/`setPipeline` bracket —
neither of which S20 touches.

**Recovery anchor verified working** (not merely asserted): `a375efe` is an ancestor of `HEAD`,
so it is permanent in history; `git show a375efe:<path>` returns `PortalContextSwitch.java`
(2628 lines) and `PortalWorldManager.java` (2070 lines) intact. The mining doc's own
"Post-S20 readers" recovery instruction is therefore live.

**Decision: proceed with the deletion.** Nothing in the live tree depends on the block-era FBO
path as precedent; the extraction exists as a doc; the code stays in history behind a pinned,
verified SHA.

### §0.2 SodiumFogOverride(+Mixin) — CONFIRMED **flag-ON load-bearing**, deletion off the table

Positively verified on THIS branch (the §C worked example — the danger is deleting something a
later stage quietly made load-bearing):

- **Producer:** `SecondaryWorldRenderCore.java:1524` — `SodiumFogOverride.activate(destFogData)`
  arms the override immediately before the Step-5 dest extract, inside
  `renderDestWorldFullPipeline` (method opens `:1338`, closes past `:1837`).
- **Release:** `:1554` — `SodiumFogOverride.clear()` runs FIRST in the paired `finally`,
  throw-safe, before the SOG feed, so no later work in that `finally` runs with the override armed.
- **Consumer:** `SodiumFogOverrideMixin.java:61` — `SodiumFogOverride.currentOverride()` behind
  the `sodium$getFogParameters` duck, HEAD `setReturnValue`, so sodium's `cullTerrain` reads DEST
  fog for the extract's duration.
- **Ledgered duck-ordering dependency** (in-code, `:1513-1523`): fog computed and override armed
  BEFORE the extract; the value-hoist and the belt are *independently* load-bearing — "do not
  remove EITHER believing the other covers it (IS2 fold V2-1)".

The old C2 P8 "reachable but inert" label described the pre-IS2 tree and is superseded.
The pair moves to the §3 survivor list. **No pre-deletion audit needed — deletion is off the table.**

Note for the deletion sweep: `PortalContextSwitch.java:1832/:1989` is the *block-era* caller of
this same pair. Deleting `PortalContextSwitch` leaves `SodiumFogOverride` with exactly one live
producer (`SecondaryWorldRenderCore`) and one consumer (the mixin) — it does **not** orphan it.

---

## §A CORRECTION TO THE SESSION PROMPT'S MERGE-RISK MEASUREMENT

The starter (`S20_SESSION_PROMPT.md` §A) states that of the 17 commits `iris-on/is5-shadow` is
ahead, "**zero** of them touch the S20 kill list (`PortalWorldManager`, `PortalContextSwitch`,
`StencilPortalRenderer`, `SeamlessPortalsConfig`, `SodiumBridge`, `RemoteBlockUpdater`)."

**That is falsified as literally stated.** `git diff e8e4767..iris-on/is5-shadow` contains ADDED
lines (not diff context) referencing two of the six:

| Symbol | Added-line sites on is5-shadow |
|---|---|
| `SeamlessPortalsConfig.isEntityPortals()` | `PortalParticleClip.java` (new flag-ON branch), `QuadParticleGroupMixin.java` (×2: amended gate + the §2c liveness counter) |
| `PortalContextSwitch.isRenderingPortal` | `QuadParticleGroupMixin.java` (amended to `\|\| SecondaryWorldRenderCore.isDestExtracting`) |

The other four are genuinely zero-hit. The consequence is **not** a blocker for S20 — it is a
merge-forward obligation on whoever merges `iris-on/is5-shadow` after S20 lands:

1. `PortalParticleClip`'s §2c flag-ON branch (IP portal entities via `IPMcHelper`) is the
   surviving half; its flag-OFF branch (the block-era `PortalManager`/`PortalInfo` tracker) dies
   with S20 → collapse to the flag-ON branch unconditionally.
2. `QuadParticleGroupMixin`'s two `isEntityPortals()` gates collapse to always-true.
3. `QuadParticleGroupMixin`'s `PortalContextSwitch.isRenderingPortal ||
   SecondaryWorldRenderCore.isDestExtracting` discriminator loses its first disjunct; the
   is5-shadow comment already records the second as "the flag-ON belt", so the surviving form is
   `isDestExtracting` alone.

All three are mechanical and S20 *simplifies* them (same as the `redstone/passthrough` rebase
benefit the starter notes). Recorded here so the merge is not surprised by it.

---

## §B PRE-DELETION BASELINE (so any later red is provably ours)

Worktree setup note: the root `gradlew`/`gradlew.bat`/`gradle/`/`build.gradle`/`settings.gradle`
are **untracked** infrastructure (only `buildSrc/`, `common/`, `fabric/`, `neoforge/`
`build.gradle` + `gradle.properties` are tracked), so a fresh worktree has no wrapper. Copied in
from the main checkout. They are untracked *and not gitignored* — the standing "explicit file
lists only" `git add` rule is what keeps them out of commits.

| Gate | Command | Result |
|---|---|---|
| Compile, all 3 platforms | `:common:compileJava :fabric:compileJava :neoforge:compileJava` | **BUILD SUCCESSFUL** (9s) |
| The 8-leg suite | `:fabric:runCrossingGametest` | **ALL LEGS PASS** (exit 0) |

Suite legs observed green at baseline: **1** (same-dim item), **2** (cross-dim item), **3** (hurt
cow / F3 transient hurt state), **4** (pearl), **5** (datapack custom generation), **6a**
(negative-coords OW→nether ignition), **6b** (nether-side ignition →OW, 8:1), **7** (>71-chunk
same-dim dests) = 8 legs. Full-log audit: 28 `ERROR|Exception|FAILED` lines, **all**
environmental (oshi/JNA perf-counter `Win32Exception` + null `OSProcess`, offline-auth
`InvalidCredentialsException: 401`); zero mod-logic errors.

Confirms in passing that the `ipStubs`/`fabricStubs` source sets are **live build participants**
(`compileIpStubsJava`, `compileFabricStubsJava`, `ipStubsJar`, `fabricStubsJar` all execute), so
their removal is a real build-graph change, not a no-op.

## §C SUITE-SHAPE CORRECTION — the "TITLE-CARD flag-OFF leg" is NOT a leg of the 8-leg suite

Both `S20_HANDOFF.md` §1/§4 and `S20_SESSION_PROMPT.md` §E describe the 8-leg suite as *losing
its TITLE-CARD flag-OFF leg* when the flag dies. Verified in `fabric/build.gradle`: they are two
**separate run configs with separate run dirs and separate test classes**.

| Run config | Run dir | Test class | Flag seed |
|---|---|---|---|
| `runClientGametest` | `runs/gametest` | `TitleCardCapture` (310 LOC) | pinned **`entityPortals=false`** (`fabric/build.gradle:262-268`) |
| `runCrossingGametest` | `runs/gametest-crossing` | `CrossingSmoke` (1076 LOC, the 8 legs) | seeded **`entityPortals=true`** (`:276-287`) |

So the **8-leg gate itself loses nothing** — it is flag-ON already and its seed line simply
becomes redundant. What loses its meaning is the *title-card capture run config*:
`TitleCardCapture` deliberately drives the **block-era** flow (build a `nether_portal` block
frame, let `NetherPortalBlock.entityInside` link + teleport — `:26-41`), and `build.gradle:257-261`
pins it flag-OFF precisely to preserve that, calling a flag-ON title card "a future re-shoot".

**Re-shape (the standing rule says re-shape, don't let it vanish):** retarget `TitleCardCapture`
to the entity-portal flow — `CrossingSmoke` already has the exact template, `spawnTestPortal(level,
origin, destDim, destPos)` driven through `runOnServer` (`CrossingSmoke.java:148-155`) — and drop
the flag-OFF `doFirst` pin. That is the "future re-shoot" the code comment anticipates, and it
preserves the capability instead of deleting it.

## §D CONSTRAINT-5 HARD GATE — `SeamlessClientTeleport` may NOT be deleted yet

`EXECUTION_PLAN.md` §S20(a) is explicit: `SeamlessClientTeleport` carries two PORT-FORWARD
`(verify)` sub-items and *"without that record its deletion here is a constraint-5 violation."*
`S08-teleportation.md` §10 (`:532-556`) states the requirement — *"`SeamlessClientTeleport` MAY NOT
be deleted at S20 until BOTH are on record"* — and then records both as **NOT closed at S08**:

| Sub-item | S08 re-test status | Deferred to |
|---|---|---|
| (i) lagged-rotation-field shift (`yRotO`/`xRotO`/`yBob`…) | *"**UNPROVEN** until S11"*, scoped to `TransformationManager` alone | S11/S13 |
| (ii) sprint-modifier keeper | *"provable at S13 rung-1 / S17 item-2"*, "expected green" | S13/S17 |

**Searched for the closing record and it does not exist.** Every later hit is a test *script* or a
*watch item*, never a result: `S13A-closure-sources.md:385` is under the heading *"First-light
watch items (S13 runClient — user-gated, NOT this stage)"*; `S11A-render-context.md:122-125`
forward-flags the bob-field write-set for "S12/S18 runtime verification"; `S13-FIRST-LIGHT-TEST.md:185`
and `S14-CROSS-DIM-TEST.md:112` are EXPECTED-lines in test scripts; `S17-cutover-flip.md` has zero
hits. The S18 sign-off ("ALL those things work") is a general feature sign-off, not a record of
these two named sub-items — and the standing NO-GUESSING rule forbids stretching it into one.

**Consequence for increment ordering (decided):** the two re-tests become explicit named rows in
the §4 regression, and `SeamlessClientTeleport`'s file deletion moves to the FINAL increment,
after the regression puts them on record. The flag's death (increment 3) makes it dead code
either way; deleting the file early would be the constraint-5 violation, deleting it last is free.

---

## §E THE B11 REACHABILITY AUDIT — results (4 clusters × 2 stages, 8 agents, 0 errors)

Method per `S20_SESSION_PROMPT.md` §D: cluster discovery, then an independent stage-2 agent whose
brief was to **adversarially re-derive every gate direction rather than inherit it**. Totals across
clusters: **59 safe-to-delete, 51 needs-reshape, 27 load-bearing, 2 uncertain.**

The headline: **the §1 kill list is wrong in a direction that REDUCES what dies.** Nine entries it
names, or that a package/symbol sweep would take with them, are provably load-bearing. Each below
is the §C failure mode — something a later stage quietly made load-bearing.

### E.1 REFUTED DELETIONS — named on the kill list, but load-bearing

| # | Entry | Why it must survive |
|---|---|---|
| 1 | **`compat/SodiumCompat`** (§1 names it as "SodiumCompat/SodiumBridge") | `isSodiumLoaded()` has five live survivor call sites: `ClientWorldLoader.java:343` (secondary-dim disposal repoint, stops sodium deleting the MAIN renderer's state), `:444` (head of `endFrameOnSecondaryLevelRenderers` — the C2 endFrame walk, an explicit §3 survivor), `:865` (`createSecondaryClientWorld` repoint — without it the secondary SWR's `renderSectionManager` stays null, "the swap-driver NPE class"), `SodiumCompatProbe.java:65`, `SodiumRendererRepoint.java:59`. Chain proven end to end: `GameRendererMixin.java:57` → `MyGameRenderer.java:192` → `:211` → `ClientWorldLoader.java:443-452`. **SPLIT THE PAIR** — `SodiumBridge` really is dead. `EXCLUSIVITY_LEDGER.md:154` already said KEEP; the handoff's slash-pairing is what concealed it. |
| 2 | **`ExperimentalCompatGate.ENABLE_SODIUM_IRIS_COMPAT`'s TRUE VALUE** (§1 bullet 3 kills the gate) | The IS1–IS4 merge made the gate's *value* load-bearing for the newest shipped feature. Chain: gate true → `irisActive` (`SeamlessPortalsClientFabric.java:296`) → `IrisInterface.invoker = onIrisPresent` (`:307`) → `isShaders()` can return true (`IrisInterface.java:132`; the BASE invoker hard-returns false at `:56-57`) → `IPGlobal.isShaderpackPortalViewsActive(isShaders())` (`IPGlobal.java:91-93`, flag default TRUE `:65`) → `PortalRenderer.java:454-472` selects `IrisCompatOn262Renderer`. **Delete the gate by COLLAPSING IT TO ALWAYS-TRUE.** Removing the `if (irisActive)` install block as "gate scaffolding" silently kills the IS4 default-ON shaderpack portal views and drops shaders users back to `rendererDummy`. |
| 3 | **`detectAndGateRenderCompat()`** | It is the install site for the ENTIRE C2 survivor family: `SodiumInterface.OnSodiumPresent` (`:328`), `IrisInterface.OnIrisPresent` (`:307`), the D11 feed (`:373`). Only the gate boolean, the `else if` warn branch (`:349-383`) and `warnAndForcePortalRenderingOff` (`:391-428`) are targets. **The D11 `FeedOnlyOnSodiumPresent` feed lives INSIDE the branch being deleted** and per `SodiumInterface.java:506-511` without it the MAIN WORLD IS BLANK under sodium — it must be **re-homed, not dropped**. |
| 4 | **`fabricStubs`, the `net.fabricmc.*` half** (32 shells; §1 bullet 4 kills the source sets, and `common/build.gradle:34`/`:155` literally say "Removed at S20") | **I verified this myself, not just the agent's claim.** `:common` has NO fabric-api/fabric-loader dependency (every apparent hit in `common/build.gradle` is a comment or an *exclusion*, `:130-131`); `fabricStubs` is wired onto `:common`'s compile classpath at `:157` (`compileOnly sourceSets.fabricStubs.output`); the ported `qouteall` tree imports **31 distinct** `net.fabricmc.*` types across ~40 files; and `:194-198` states the NeoForge loader "has no fabric-api, so it consumes THESE shells instead" via `fabricStubsClasspath`. Deleting the set turns `:common:compileJava` AND `:neoforge:compileJava` red. |
| 5 | **`fabricStubs` ModMenu shells** (2) | §1 bullet 4's rationale ("the real ModMenu is the `:fabric` dep") is true but insufficient: the real dep exists only at `fabric/build.gradle:42`, while the consumer `IPModMenuConfigEntry.java:3-4/:19` is compiled by `:common` and `:neoforge`, neither of which has any modmenu dependency. |
| 6 | **`ipStubs` gravity_changer shells** (2) | §1 bullet 4 pairs "gravity_changer stubs die" with "`GravityChangerInterface` stays invoker-dead like IP". Both halves are true but of DIFFERENT things: runtime-dead is confirmed (zero `GravityChangerInterface.invoker =` assignments repo-wide), but `GravityChangerInterface.java:3-4` imports the shells for `OnGravityChangerPresent` (`:89-168`) — so deleting them forces an edit to ported IP source. |
| 7 | **`dimlib/DimensionTemplate`** — a **booby-trapped file header** | `DimensionTemplate.java:13` literally ends *"Deleted with the migration scaffolding at S20."* The class is flag-ON LIVE: `AlternateDimensions.java:69/:79/:89/:100` construct four templates, `:122/:125/:128/:131` register them, `:171` calls `VOID_TEMPLATE.createLevelStem(server)` — all via `AlternateDimensions.init()` ← `PeripheralModMain.java:107` ← `SeamlessPortalsModFabric.java:133` (inside the flag-ON branch at `:113`). Deleting it destroys skyland / bright_skyland / chaos / bright_void worldgen — itself a §4 regression item. Only the member `init()` (`:78-82`) is dead. **The `:13` header must be corrected before it kills a later session.** |
| 8 | **`GameRendererHandLightMixin` + `render/HandLightSmoother`** | Not in the stage-1 inventory at all (it covered 148 of 150 files). Registered at `seamlessportals-common.mixins.json:26`, ZERO flag references, `@ModifyArg` on `GameRenderer.renderItemInHand` fires EVERY FRAME in both states calling `HandLightSmoother.smooth(...)` (`:33`). A real user-visible feature (hand light fades ~0.6s across a crossing instead of popping) and **checkpoint C6 decided KEEP**. Both files sit in packages dense with kill-list entries — a package sweep kills it silently. |
| 9 | **`GameRendererMixin`** and **`MinecraftFramePumpMixin`** | Sole hosts of the flag-ON IP frame chain. `GameRendererMixin` is the ONLY caller of four qouteall frame-end entry points: `MyGameRenderer.endFramePooled()` `:58` (omitting it leaks GPU buffers every portal frame), `SecondaryWorldRenderCore.closeFrameTransientUbos()` `:64`, `DrawCallTrace.onFrameEnd()` `:66`, `TeleportFlashProbe.onFrameEnd()` `:69`. `MinecraftFramePumpMixin` is the sole host of seven pre-render calls (`PRE_TOTAL_RENDER_TASK_LIST.processTasks()` `:66`, `RenderStates.updatePreRenderInfo` `:90`, `ClientPortalAnimationManagement.update` `:94`, `ClientTeleportationManager.manageTeleportation(false)` `:95`, `PRE_GAME_RENDER_EVENT` `:96`, `MyRenderHelper.earlyRemoteUpload` `:98`, `RenderStates.frameIndex++` `:104`). Both reference block-era classes, so a symbol-driven sweep reads them as block-era. Both files also carry **stale prose that invites the wrong read** (`MinecraftFramePumpMixin:36` "No flag is added now — none exists until S13"; `:55-56` "Flag OFF (the shipping default)"). |

Two more not on the kill list but which a `com.warwa` sweep would take:
**`LevelRendererEntityVisibilityMixin`** (registered `…mixins.json:56`, UNGATED; its condition at
`:59-60` is `PortalContextSwitch.isRenderingPortal || SecondaryWorldRenderCore.isDestExtracting` —
the second disjunct is the flag-ON dest-extract bracket, and the whole C2-1e block `:61-90` exists
for the flag-ON active-sodium path; without it `LevelExtractor.isEntityVisible` culls EVERY dest
entity in the portal view. Reshape = drop only the first disjunct + the `:3` import) and
**`SkyRendererTargetMixin`** (registered `:61`, UNGATED, writes five qouteall `TeleportFlashProbe`
fields at `:74/:77/:82/:85` keyed off `WorldRenderInfo.isRendering()` `:73`).

### E.2 THE NEOFORGE LANDMINE — the flag's death has a cross-loader consequence

`SeamlessMixinConfigPlugin.java:156` → `:172-176`: the `return false` (weave SKIP) is the
**flag-OFF** arm, so weaving the whole IP mixin set is the **flag-ON** arm. Collapsing the gate to
always-true therefore weaves every IP mixin on plain NeoForge. Confirmed independently: 7 of 8
`.mixins.json` configs declare this plugin (line 4 of each), `neoforge.mods.toml` loads SIX IP
configs (`:12-13`, `:15-16`, `:18-19`, `:28-29`, `:45-46`, `:54-55`), and the toml's own comments at
`:21-27`/`:48-53` state verbatim that they are inert there ONLY because the flag force-falses off
Fabric. NeoForge also never calls `IPModMain.init` (`SeamlessPortalsModNeoForge.java:24-49`), so the
woven mixins would run against an uninitialised engine, and the ~31 `net.fabricmc` types are
compile-only shells there. **A green NeoForge boot would NOT prove the collapse safe — the failure
mode is a first-use `NoClassDefFoundError`, not a weave-time crash.**

Also found: `EntityPortalsFlag` has **TWO** force-false sites, not one — `:98-100` in
`readFromDisk` and `:89` in `seedIfUnset` (`cached = isFabricLoaderPresent() && value;`). Any
re-homing must reproduce both, or a NeoForge config carrying an explicit `entityPortals=true` key
slips through the seed path. `isFabricLoaderPresent()` is `private static` (`:147-154`), so
re-homing means copying the body, not moving a call.

**Decision (mine, recorded): fix (a)** — copy `isFabricLoaderPresent()`'s body into
`SeamlessMixinConfigPlugin` so the loader gate outlives the flag. Safer than fix (b) (deleting the
six IP `[[mixins]]` blocks from `neoforge.mods.toml`) because it keeps the guarantee in code rather
than in a manifest that a later edit can silently re-add. **This must land BEFORE the D3 carve-out
sets are removed.**

### E.3 SCOPE CORRECTION — §1 bullet 4 splits in two

Bullet 4 ("the holding machinery: IpHeldPaths/ip_scc_closed, the ipStubs/fabricStubs source sets +
their gradle wiring …") conflates two unlike things:

- **Genuinely dead → DIES:** `IpHeldPaths` (`MAIN_HELD_PATHS`/`TEST_HELD_PATHS`/`applyHolding`) +
  the `ip_scc_closed` property + both held lists + the probe. The closure is complete; this is the
  real target of the bullet.
- **Compile-load-bearing → SURVIVES (E.1 rows 4-6):** the `ipStubs`/`fabricStubs` source sets and
  their gradle wiring. Deleting them requires giving `:common` a real fabric-api dependency (a
  multiloader build re-architecture) or editing ported IP source. `EXECUTION_PLAN.md` §S20(a) is
  explicit that S20 is **"deletion ONLY here per constraint 5"**, so the re-architecture is out of
  scope for this stage by the plan's own constraint. Note the S19-E precedent that makes this a
  *narrowing* rather than a refusal: the sodium/iris/autoconfig shells WERE already retired at
  S19-E once real 26.2 deps existed (`common/build.gradle:15-19`, `:72-86`, `:104-118`). What
  remains has no real-dependency substitute available to `:common`.

The stale `common/build.gradle:34` / `:155` "Removed at S20" comments must be corrected to say why
they cannot be, or they become the next booby trap (same class as `DimensionTemplate.java:13`).

### E.4 Two honest UNCERTAIN answers — both become regression rows

1. **R13f eviction gap (PRE-EXISTING, not caused by S20).**
   `PortalWorldManager.evictUnboundedStores` (`:413`) has exactly one caller,
   `SeamlessPortalsClientFabric.java:204-205`, in the flag-OFF else-branch. The ported counterpart
   `ImmPtlClientChunkMap.evictBeyond` (`:437`) is caller-less apart from its own internal
   `evictBeyondCenter` (`:441`). So **flag-ON already has no eviction driver** — S20 makes that
   permanent rather than creating it. Proving a growth bound needs runtime memory observation,
   not grep.
2. **Cross-dim live fluid flow.** `LevelChunkSetBlockStateMixin.java:89` is
   `if (isEntityPortals()) return;` — the body is flag-OFF-ONLY and dies with the flag. That body is
   a landed feature (it hooks `LevelChunk.setBlockState` TAIL precisely because vanilla
   `Level.markAndNotifyBlock` filters ~95% of fluid-spread `setBlock` calls before
   `sendBlockUpdated`). Whether IP's generic dimension-tagged redirect
   (`PacketRedirection.java:64`, `ImmPtlNetworking.java:238-262`) carries sub-chunk fluid-spread
   updates is asserted only architecturally in `migration/inventory/current-mod-core.md:16`, never
   proven. **Needs a live observation → explicit §4 regression row.**

### E.5 A false rationale that must not enter a deletion commit

`ClientPacketListenerLocalPlayerFallbackMixin` IS safe to delete (weave-skipped flag-ON via
`SeamlessMixinConfigPlugin.java:180`, sole member of `ENTITY_PORTALS_SUPERSEDED_MIXINS` `:54-59`,
registered `…mixins.json:46` — that json line must go with the class or boot fails). **But the
justification written at `SeamlessMixinConfigPlugin.java:55-57` — that IP's redirect is a "strict
superset" — is FALSE.** IP's `MixinClientPacketListener.java:171-186` is labelled `// for debug`,
redirects `handleSetEntityData` ONLY (`:172`), returns the same null without any local-player
fallback (`:179-185`), and never touches `handleUpdateAttributes`. So the elytra-glide-stuck bug
documented at `ClientPacketListenerLocalPlayerFallbackMixin.java:12-34` is **UNFIXED flag-ON
today**. Delete the file, but carry the true reason (superseded-by-weave-skip, not
superseded-by-superset) and ledger the open bug.

### E.6 Cross-branch collision with `iris-on/is5-shadow` (a live sibling session)

Beyond §A's two symbols, is5-shadow puts **new flag-ON work inside directories S20 deletes
wholesale**: `entity/PortalTicketDespawnSuppressor.java` (113 lines, all-qouteall imports, zero flag
refs) + the registered `mixin/MobDespawnSuppressMixin.java` calling it UNGATED, plus an appended
UNGATED `@Inject` on `Entity.setRemoved` in `mixin/EntityMixin.java` (gated on `IPGlobal.DESPAWN_PROBE`,
NOT on `entityPortals`). It also adds `mixin/client/GlProgramClipCacheMixin`,
`mixin/client/LevelExtractorEntityProbeMixin`, `render/{ActDispatchProbe,ActSeedProbe,IrisFullbrightProbe,ShadowAliasProbe}`
and modifies `GameRendererMixin`, `GlCommandEncoderClipMixin`, `LevelRendererEntityVisibilityMixin`,
`ClipUniformLocationCache`, `ShadowEmptinessProbe`. S20 deleting `entity/` and `EntityMixin.java`
produces **delete/modify merge conflicts** that could silently drop live sibling work. Not an S20
blocker — but the merge must be a conscious operation, so it is ledgered here rather than
discovered later.

---

## §F THE INCREMENT PLAN (single writer; suite green + commit + push per increment)

Ordering is driven by three constraints discovered above, not by the handoff's commit list alone:
the NeoForge loader gate must outlive the flag (§E.2), the compile blockers must be stripped in the
SAME commit as the classes they reference (§E.1), and `SeamlessClientTeleport` cannot be deleted
before the §4 regression (§D).

| # | Increment | Contents | Gate |
|---|---|---|---|
| **0** | **Recon + audit record** (this commit) | The port-note §0-§E. Plus the two BOOBY-TRAP COMMENT corrections, done now so a crash-out cannot mislead a later session: `DimensionTemplate.java:13` ("Deleted with the migration scaffolding at S20" → corrected, class is flag-ON live) and `common/build.gradle` ×3 (`fabricStubs` sourceSet note, `ipStubs.output`, `fabricStubs.output` — all three said "Removed at S20") | compile ×3 + suite |
| **1** | **Loader-gate re-home (MUST precede the flag's death)** | Copy `EntityPortalsFlag.isFabricLoaderPresent()`'s body into `SeamlessMixinConfigPlugin` so the qouteall-weave gate keeps its cross-loader guarantee after the flag dies (§E.2 fix (a)). Reproduce BOTH force-false sites' effect. No deletions yet — pure insurance. | compile ×3 + suite |
| **2** | **Core deletions** (EXECUTION_PLAN §S20(c) commit 1) | `portal/` package, `entity/` (minus the constraint-5 hold), `chunk/`, `network/ModPayloads`, `api/`, `client/{PortalWorldManager, SeamlessClientChunkMap, PortalDimensionManager}`, the dormant legacy six, `HandleRespawnMixin`, the flag-OFF-only common mixins. **ATOMIC with:** the `FrontClipping` six-member strip (`:3`, `:54`, `:61`, `:64`, `:116`, `:163`, `:211`) or `:common` goes red. | compile ×3 + suite |
| **3** | **Render deletions** (commit 2) | `render/{StencilPortalRenderer, PortalContextSwitch, PortalShapeRenderer, PortalRenderBuffersPool, PortalInnerCull, CameraTransitionHandler, PortalSlicing, PortalFrameSuppressor, VisibleSectionDiscovery(warwa), DimensionRenderHelper(warwa)}`, `compat/SodiumBridge` (NOT `SodiumCompat` — §E.1 row 1), the unregistered dead mixins, the flag-OFF-only client mixins. **ATOMIC with the survivor reshapes:** `GameRendererMixin` (delete the HEAD inject + `CameraTransitionHandler` import; excise `:47`/`:48`/`:49`; collapse `:57-59`), `LevelRendererEntityVisibilityMixin` (drop the `PortalContextSwitch.isRenderingPortal` disjunct + the `:3` import), `StencilState`'s two writer lines. | compile ×3 + suite |
| **4** | **Flag + ledger + machinery removal** (commit 3) | The `entityPortals` flag and all 46 gate sites collapsed in the proven direction — including the four qouteall-side sites, two of which are PERMISSION paths (`CommandStickItem:138`, `PortalWandInteraction:492`) pending the adversarial verdict. `SeamlessPortalsConfig`, `SeamlessConfigScreen`, `ModMenuIntegration` (+ swap `fabric.mod.json`'s "modmenu" entrypoint to `IPModMenuConfigEntry`), the D3 carve-out sets, the C2 gate scaffolding by **COLLAPSING `ENABLE_SODIUM_IRIS_COMPAT` TO ALWAYS-TRUE** (§E.1 row 2) with the D11 feed **RE-HOMED** out of the deleted warn branch (§E.1 row 3), the re-shaped D7 warn path, the C4 Mechanism-B loser cleanup, `IpHeldPaths`/`ip_scc_closed` (but NOT the stub source sets — §E.3), archive `EXCLUSIVITY_LEDGER.md`. **ATOMIC with the TITLE-CARD re-shape** (§C: retarget `TitleCardCapture` to `spawnTestPortal`, drop the flag-OFF pin) — the suite must not silently lose a leg. | compile ×3 + suite |
| **5** | **§4 regression** — ASK THE USER FIRST | The 12-point round. Named rows added on top of the standard checklist: the two constraint-5 (verify) sub-items (hand steady / sprint preserved through a crossing — §D); cross-dim live fluid flow (§E.4 item 2); alternate-dims worldgen (skyland/bright_skyland/chaos/bright_void — §E.1 row 7); the hand-light fade (§E.1 row 8, checkpoint C6); a plain-NeoForge sanity check for §E.2. Sodium/iris matrix rows scoped to THIS branch only — the six shipped polish fixes + ACT probe kit live on `iris-on/is5-shadow` and are NOT merged, so those rows may not be reported green on their strength. | live |
| **6** | **Constraint-5 close-out + survivor-audit note** (commit 4) | Delete `SeamlessClientTeleport` once increment 5 puts its two (verify) sub-items on record; final survivor-audit note + regression record. | compile ×3 + suite |
