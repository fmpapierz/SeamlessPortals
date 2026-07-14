# S9 — Chunk loading + entity sync (U7) — port-note

**Stage:** S9 (EXECUTION_PLAN §3 S9). **Effort:** L — a mechanically-heavy unit (mostly SAME/rename
translations) whose weight is three OWNED design surfaces: the **R10 ticket-system** decision set
(F13), the **R13j F1 forced deviation** (`WorldInfoSender` time-half), and the **R13f carriage** of
the 26.2 `ClientChunkCache` delta protocol into the ported `ImmPtlClientChunkMap`.
**Unit:** U7 — `imm_ptl/core/chunk_loading/{ImmPtlChunkTracking, ChunkVisibility, ImmPtlChunkTickets,
PlayerChunkLoading, EntitySync, WorldInfoSender, DimensionalChunkPos, PerformanceLevel,
ServerPerformanceMonitor, ChunkLoader, ImmPtlClientChunkMap}` + `miscellaneous/ClientPerformanceMonitor`
+ the `mixin/common/chunk_sync/` group (9 of IP's 10).
**Discipline:** D2 verbatim `qouteall.*` paths · D4.2 probe-ledger triage (the `-Pip_scc_closed=true`
probe error list, not the paper ledger, is authoritative — the S5/S6 lesson) · D4.3 source-diff gate
(`git diff --no-index` vs IP; only enumerated hunks allowed) · **all U7 files land HELD** (no carve-in
change — `IpHeldPaths` already covers `imm_ptl/core/**`), **unregistered** until S13. The chunk_sync
mixin classes land this stage per §S9(a), unregistered until S13.
**IP source root (1.21.3, Mojang mappings):** `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`
**26.2 evidence root (authoritative):** `C:/Users/warwa/ModDev/mc262-ref`
**Primary map:** `migration/api-map/chunk-loading.md` (the R10-heavy map: 33 SAME · 10 CHANGED · 8 GONE).
**Recurring renames:** `migration/api-map/world-loader-root.md` §2 + S5 GONE rows.
**PORT-FORWARD disposition:** `migration/api-map/current-mod-core.md` §5.
**Memory lessons honoured:** `distant-chunk-vanish-sog-desync` (§4 SOG delta feed),
`walking-limbo-seed-overclaim` (§4 store-center PINNING), `portal-view-completeness-findings`
(§2 FLAG_SIMULATION liveness/mob-spawn), `nether-block-freeze-orphaned-extractor` (§4 extractor
identity, flagged to S10/S11).
**API-maps amended by this stage: NONE.** Unlike S7 (`portal-generation.md`) and S8
(`teleportation-collision.md` +2 GONE rows), every S9 hunk mapped to an existing `chunk-loading.md`
row — the map was complete. The category-(c) residual items (§5) are all resolutions WITHIN existing
rows, recorded here, not new api-map rows.

This note is the **S9 commit-4 deliverable** (S9(c): "port-note (R10 record + F1)"). It consolidates
the three working fragments (`fragments/S09-{tracking,entitysync,clientchunkmap}.md`, deleted at stage
end) plus the diff-gate / probe / test evidence measured this pass.

---

## 0. Stage result (build + probe + test evidence)

| Gate | Command | Result |
|---|---|---|
| Shipping build (D4.1) | `:common:compileJava :fabric:compileJava` (`ip_scc_closed=false`) | **BUILD SUCCESSFUL** — all 21 U7 held files (paths `imm_ptl/core/chunk_loading/**`, `imm_ptl/core/miscellaneous/ClientPerformanceMonitor.java`, `imm_ptl/core/mixin/common/chunk_sync/**` — all in `IpHeldPaths.MAIN_HELD_PATHS`) invisible to javac; `:fabric` uses the identical MAIN held-list wiring, stays green. |
| Compile probe (D4.2) | `:common:compileJava -Pip_scc_closed=true` | **BUILD FAILED (expected)** — 536 whole-tree errors. **U7-file errors: 33 on the 12 non-mixin files; 0 on all 9 chunk_sync mixins** (they compile clean). Every U7 error reduces to established loader/external debt (24) or the documented S9(b) forward-ref union (9) — §6. **Zero translation slips** (grep for `method … location`, `incompatible types`, `cannot be applied`, `does not override`, `no suitable method`, bad-operand over all U7 files = empty). |
| Source-diff gate (D4.3) | `git diff --no-index` / `diff` vs IP per file | Every non-identical file's diff = ONLY the hunks tabulated in §1; **11 files byte-identical to IP** (`diff -q` empty). |
| Math harness (D4.5) | `:common:test` | **BUILD SUCCESSFUL** — the S2 authored DQuaternion/Plane invariant tests stay green; S9 adds no test payload; carried Mesh2DTest/HelperTest remain held via the test-task list until S13. |

The probe error list is the AUTHORITATIVE ledger (D4.2). It was diffed against the paper S9(b) ledger
this pass; the two external-dependency families the paper "cross-unit forward-ref" wording does not
enumerate (fabric-api `ServerTickEvents` + `@Environment`, Fabric-internal `attachment`, DimLib
`DimensionAPI`) are **established debt** carried verbatim from IP and already present in this file's
S2/S4/S6/S7/S8-committed siblings — recorded as governed ledger entries in §6, not translation slips.

---

## 1. Files ported (21 held + 1 coupled S4 duck) — per-file diff-gate record

Byte-fidelity method: each IP file copied byte-for-byte (CRLF/LF and trailing-whitespace-on-blank-lines
preserved to match the held convention), then only the enumerated hunks applied by literal string
replacement. All authored/edited files are ASCII-only. `+a −r` = added/removed diff lines.

### 1a. `chunk_loading/` (11)

| File | Diff | Translations (api-map row) |
|---|---|---|
| `PerformanceLevel.java` | **IDENTICAL** | Verbatim. All server APIs SAME: `tickRateManager()`/`getAverageTickTimeNanos()` (#37 non-profiler SAME), `nanosecondsPerTick()` (#38 SAME, base `TickRateManager`). Pure enum + statics. |
| `ServerPerformanceMonitor.java` | **IDENTICAL** | Verbatim. `ServerTickEvents.END_SERVER_TICK` kept verbatim (#51 FABRIC-API — loader-seam swap deferred to registration/S13, matching every held `ServerTickEvents` user). `server.isRunning()` SAME (#37). |
| `DimensionalChunkPos.java` | +1 −1 | `ChunkPos.x/.z` field → `.x()/.z()` record accessor (#42). |
| `ChunkLoader.java` | +1 −1 | `ResourceKey.location()` → `.identifier()` (S5). `nether_portal.FastBlockAccess` (`:10`) kept verbatim = U12/S13 forward-ref (§6). |
| `MixinChunkHolder.java` *(chunk_sync — listed 1b)* | — | — |
| `EntitySync.java` | +2 −2 | `+import Profiler`; `server.getProfiler().push/pop` → `Profiler.get().push/pop` ×4 (#37 GONE); `chunkPosition().toLong()` → `.pack()` (#42). Unused `DistanceManager distanceManager` local in `update()` kept IP-verbatim. |
| `PlayerChunkLoading.java` | +2 −2 | `ServerPlayer.server` (GONE) → `player.level().getServer()` (#41); `.location()`→`.identifier()` (S5). Fabric-internal `AttachmentTargetImpl`/`AttachmentChange` (`:6,:7,:221-225`) kept verbatim (#53 Fabric-side re-verify item). |
| `ChunkVisibility.java` | +4 −4 | 4× `new ChunkPos(BlockPos)` (GONE ctor) → `ChunkPos.containing(BlockPos)` (world-loader-root S5). |
| `WorldInfoSender.java` | +14 −14 | Profiler ×2 (#37); **F1 forced deviation — the per-dim time packet DELETED**, replaced by a citing comment (§3); import removals `ClientboundSetTimePacket` + `GameRules` (fed only the deleted half); weather half ported 1:1 (#35 SAME). |
| `ImmPtlChunkTracking.java` | +25 −24 | `.server`→`level().getServer()` (#41, 3 sites) · `player.getServer()`→`player.level().getServer()` (#41) · `.location()`→`.identifier()` (S5, 3 sites) · `ChunkPos.asLong`→`pack` (#42) · `new ChunkPos(long)`→`unpack` (#42) · `new ChunkPos(BlockPos)`→`ChunkPos.containing` (S5) · `ChunkPos.x/.z` loop reads→`.x()/.z()` (#42) · `server.getProfiler().push/pop`→`Profiler.get().push/pop` (#37). `ServerTickEvents`/`DimensionAPI` registrations kept VERBATIM (loader-seam wiring deferred to S13 per S00 A6; matches landed S8 `ServerTeleportationManager`). |
| `ImmPtlChunkTickets.java` | R10 §2 | **R10 ticket re-derivation — §2.** `TicketType<ChunkPos> TICKET_TYPE = TicketType.create(...)` → mutable `TicketType TICKET_TYPE;` (registry-phase seam); `addRegionTicket`/`removeRegionTicket`/`removeAllTicketsInWorld` re-targeted onto `TicketStorage` via the `IEDistanceManager` duck; `new ChunkPos(long)`→`unpack` (#42); imports trimmed (`Ticket`, `SortedArraySet`, `Comparator`, `List`). Each hunk carries an inline `// R10 (api-map … #NN)` cite. |
| `ImmPtlClientChunkMap.java` | +461 −241 | **R13f fusion — §4.** IP verbatim + the mod's proven 26.2 delta/store surface. Mechanical: `#42` pack/`.x()/.z()`; `#45/#48` `replaceWithPacketData`/`loadChunkDataFromPacket` heightmaps `CompoundTag`→`Map<Heightmap.Types,long[]>` + `@Nullable LevelChunk` return; `#45` own `level`/`emptyChunk` fields (super's now PRIVATE); `.location()`→`.identifier()`; `@Nullable`→`org.jspecify`. `@Environment(EnvType.CLIENT)` + `@IPVanillaCopy` kept verbatim. |

### 1b. `mixin/common/chunk_sync/` (9 of IP's 10 — `IEChunkTaskPriorityQueueSorter` DROPPED per #24)

| File | Diff | Translations (api-map row) |
|---|---|---|
| `IEServerCommonPacketListenerImpl.java` | **IDENTICAL** | Verbatim (#32 SAME). |
| `MixinPlayerChunkSender.java` | **IDENTICAL** | Verbatim (all members SAME #26). |
| `MixinPlayerTicketTracker.java` | **IDENTICAL** | Verbatim (4 descriptors SAME #21). |
| `MixinServerGamePacketListenerImpl_ChunkSync.java` | **IDENTICAL** | Verbatim (#30 SAME). |
| `MixinChunkHolder.java` | +1 −1 | `chunkPos.x/.z`→`.x()/.z()` (#42); broadcast `ModifyVariable` + `broadcastChanges` getPlayers `@Redirect` SAME (#16). |
| `MixinChunkMap_C.java` | +11 −2 | **#6 `onChunkReadyToSend` — §5(c-1).** Signature `(LevelChunk)`→`(ChunkHolder, LevelChunk)` + replayed broadcast/debug tail (cancel only the player-loop marking). Other shadowed members SAME (#2,#4,#5,#25). Inline #6 javadoc. |
| `MixinDistanceManager.java` | +6 −5 | **#18/#42.** `getTickets(long): SortedArraySet<Ticket<?>>` shadow → `@Shadow @Final private TicketStorage ticketStorage;` + `ip_getTicketStorage()`; `portal_getTicketSet` removed; `sectionPos.chunk().toLong()`→`.pack()` (#42). NPE-guard on `removePlayer` kept (#20). |
| `IEDistanceManager.java` *(chunk_sync accessor)* | +6 −18 | **#18/#24.** `@Accessor "tickets"` (GONE, moved to TicketStorage) + `@Accessor "ticketThrottler"` (GONE, `ChunkTaskPriorityQueueSorter` deleted; IP never called it) DROPPED; only `@Accessor "mainThreadExecutor"` survives (SAME field). |
| `IEChunkMap_Accessor.java` | +9 −2 | **#55.** `@Invoker("getChunks") Iterable<ChunkHolder>` (GONE method) → `@Accessor("visibleChunkMap") Long2ObjectLinkedOpenHashMap<ChunkHolder> ip_getVisibleChunkMap()` (1.21.3's `getChunks()` returned `visibleChunkMap.values()`). Consumer `PortalDebugCommands` (U11→S13) reads `.values()`. |

### 1c. Coupled S4 duck touched this stage (api-map-backed, part of R10)

| File | Diff | Translation |
|---|---|---|
| `ducks/IEDistanceManager.java` | +6 −3 | **#18.** GONE `portal_getTicketSet(long): SortedArraySet<Ticket<?>>` (unresolvable on 26.2 — `Ticket` is non-generic) → `TicketStorage ip_getTicketStorage()`. It was the ONLY file in the whole tree still referencing the GONE ticket-set symbols; its sole consumer (`ImmPtlChunkTickets`) lands THIS stage. |

**Dropped file (recorded):** IP's `mixin/common/chunk_sync/IEChunkTaskPriorityQueueSorter.java` is
**NOT ported** (verified absent on disk). Per #24 both its target types (`ChunkTaskPriorityQueueSorter`,
`ProcessorMailbox<StrictQueue.IntRunnable>`) are GONE and IP referenced the accessor only in a javadoc
`{@link}` — zero functional consumers. A blindly-ported `@Invoker` on a missing target crashes at mixin
apply, so dropping it is the faithful outcome.

---

## 2. R10 decision record (F13) — the ticket system, OWNED at S9

The 26.2 ticket system was rewritten (chunk-loading.md headline 1–2). S9 is R10's OWNING stage; first
runtime contact is S13 rung-1 (machinery runs, masked by already-loaded chunks) and S14 rung-2 (first
real cross-dim loading). This stage settles three decisions and writes them into the held source.

### 2(i) `TicketType` registration at REGISTRY PHASE — not lazy static init

**26.2 fact (#23):** `TicketType` is `record TicketType(long timeout, @Flags int flags)` registered into
`BuiltInRegistries.TICKET_TYPE` (`TicketType.java:10,27-29`). `TicketType.create(String, Comparator)`
and the generic `Ticket<T>` key are GONE.

**Decision.** IP's `public static final TicketType<ChunkPos> TICKET_TYPE = TicketType.create("imm_ptl",
Comparator.comparingLong(ChunkPos::toLong))` becomes a plain mutable field
`public static TicketType TICKET_TYPE;` (no static-init registration). Registration is a real registry
op that MUST run at registry phase — a lazy static-init at first use would hit a frozen registry. Per
**D2** (`qouteall.*` holds no loader/mod glue), the registration call lives in **mod-owned** code, not
in the port (a `qouteall`→`com.warwa` import would break the diff-gate). It runs through the KEEP'd
`com.warwa.seamlessportals.mixin.TicketTypeInvoker` (`@Invoker("register")` — the proven current-mod
pattern used by `PortalChunkTracker.SEAMLESS_CHUNK_TICKET`), **UNCONDITIONALLY** in both flag states
(D3 registries-unconditional), wired at **S13** (registry phase):

```java
ImmPtlChunkTickets.TICKET_TYPE = TicketTypeInvoker.seamlessportals$invokeRegister(
    "imm_ptl", TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION); // = 6
```

The field is documented at its declaration (verified inline in the held file); it is only ever
dereferenced while `enableImmPtlChunkLoading` is on (S13+), by which point the seam has assigned it.

### 2(ii) Flag bits: `FLAG_LOADING | FLAG_SIMULATION` (=6), no PERSIST, no KEEP_DIMENSION_ACTIVE

**Fidelity default = IP's simulation semantics.** Shape `6` = exactly vanilla
`DRAGON = register("dragon", 0L, 6)`.

- **`FLAG_LOADING` (2)** drives `ChunkHolder` ticket levels/futures (`LoadingChunkTracker`) — required so
  IP's `getEntityTickingChunkFuture().getNow(...)` poll in `flushThrottling` ever completes (the future
  completes when the loading-tracker level reaches ≤31, #14).
- **`FLAG_SIMULATION` (4)** drives `inEntityTickingRange` (`SimulationChunkTracker`, #22) — required so
  portal-loaded chunks entity-tick and `EntitySync.tick`'s gate (`inEntityTickingRange(pos.pack())`,
  #12/#22) fires; without it those chunks never tick.
- **No `FLAG_PERSIST`** — `TicketStorage` is `SavedData` and persists PERSIST-flagged tickets across
  restarts; IP's tickets must not persist (`ChunkLoader.java:116` documented non-persistence).
- **No `FLAG_KEEP_DIMENSION_ACTIVE`** — the dimension keep-alive decision (#39) belongs to the
  `MixinServerLevel` keep-alive port (a separate, later mixin — NOT in the chunk_sync group); the imm_ptl
  ticket stays at 6. The two faithful #39 options (add flag-bit 8 vs. redirect `hasActiveTickets()`) are
  deferred to that mixin's owning stage; the zero-deviation port is the redirect (preserves IP's config
  gating; adding bit 8 would couple keep-alive to `enableImmPtlChunkLoading`).

**MOB-SPAWN IMPLICATION (written down; regression-watch S14/S17).** `FLAG_SIMULATION` makes
portal-loaded destination chunks entity-ticking AND spawn-eligible (natural mob spawning). This is IP's
genuine behavior. It deliberately DIVERGES from the block-era mod's
`PortalChunkTracker.SEAMLESS_CHUNK_TICKET`, which used **LOADING only (`0b0010`)** precisely to avoid the
"piglin flood / burst-tick the whole nether region" cost (`PortalChunkTracker.java:254-266`; memory
`portal-view-completeness-findings`). Under the entity-portal system the block-era residency ticket is
gone, so IP's SIMULATION ticket is the one in play. The flood/spawn behavior of a portal-loaded
destination is therefore a **REGRESSION-WATCH item for the S14 (integration) and S17 (default-flip)**
checkpoints — if it proves problematic in play, the mitigation is a documented, tested deviation THERE,
not a silent change here. (S17 §3 already cross-references this note per the plan's S17 "S9 decision's
written implication".)

### 2(iii) `PlayerTicketTracker` takeover — the uncovered `addPlayer` PLAYER_SIMULATION path

`MixinPlayerTicketTracker` cancels `DistanceManager$PlayerTicketTracker.{onLevelChange(JII),
onLevelChange(JIZZ), updateViewDistance, runAllUpdates}` (the `PLAYER_LOADING` path) when
`enableImmPtlChunkLoading` is on — disabling vanilla's per-player view-distance chunk LOADING so IP owns
it (#21).

**Separately uncovered (mixin-common §2 warning):** `DistanceManager.addPlayer(SectionPos, ServerPlayer)`
adds a **direct** `PLAYER_SIMULATION` ticket — `ticketStorage.addTicket(new Ticket(
TicketType.PLAYER_SIMULATION, getPlayerTicketLevel()), chunk)` (`DistanceManager.java:115`, flags 12 =
SIMULATION|KEEP_DIMENSION_ACTIVE). The cancelled `PlayerTicketTracker` methods do NOT touch this path.

**Decision: leave `addPlayer`'s PLAYER_SIMULATION ticket ALIVE (do NOT mixin `addPlayer`).** This
mirrors 1.21.3 exactly, where `addPlayer` fed the ticking tracker and IP left it alive (verified: zero
references to `TickingTracker`/`tickingTicketsTracker` anywhere in the IP tree; #21). It gives each
player local entity-ticking + keeps their current dimension active independently of IP's portal loading,
which is correct — IP's chunk system is **additive** over the player's own vanilla residency, not a full
replacement of it. The `MixinServerLevel` keep-alive redirect (#39) that pairs with this is the separate
later mixin noted in 2(ii).

### 2 — ticket CRUD re-derivation (the mechanical half of R10)

Storage moved off `DistanceManager` into a per-level `TicketStorage` (`DistanceManager.ticketStorage`
private final). Reached via ONE accessor: `ducks.IEDistanceManager.ip_getTicketStorage()`, implemented by
`MixinDistanceManager` shadowing the `ticketStorage` field. The `IEDistanceManager` surface shrinks to
exactly this (chunk-loading cross-cut note 1). Verified hunks (each cited inline in the held file):

| IP call (GONE) | 26.2 re-derivation |
|---|---|
| `distanceManager.addRegionTicket(TICKET_TYPE, pos, getLoadingRadius(), pos)` | `((IEDistanceManager) distanceManager).ip_getTicketStorage().addTicketWithRadius(TICKET_TYPE, pos, getLoadingRadius())` — `TicketStorage.java:138-141`; level arithmetic `33 - radius` unchanged (#17) |
| `distanceManager.removeRegionTicket(…, getLoadingRadius(), …)` (in `purge`) | `…ip_getTicketStorage().removeTicketWithRadius(TICKET_TYPE, pos, getLoadingRadius())` — `TicketStorage.java:214-217` (#17) |
| `removeAllTicketsInWorld`: iterate `chunkPosToTicketInfo`, `portal_getTicketSet`, filter, `removeRegionTicket(level)` loop | `…ip_getTicketStorage().removeTicketIf((ticket, pos) -> ticket.getType() == TICKET_TYPE, null)` — `TicketStorage.java:308`. Vanilla-blessed bulk path (#18); removes by TYPE at ANY level, so robust to `getLoadingRadius()` (2↔1) having flipped between a ticket's add and remove — matches IP's `t.getType()==TICKET_TYPE` filter exactly. |
| `IEDistanceManager.ip_getTickets` / `ip_getTicketThrottler` accessors | DROPPED (#18/#24 — fields off DistanceManager / target type GONE) |
| `IEDistanceManager.ip_getMainThreadExecutor` | KEPT — field SAME (`DistanceManager.java:46`), carried verbatim (its local is unread even in IP). |

`getDistanceManager(world)` (`IEServerChunkCache.ip_getDistanceManager`) and
`getChunkHolder`→`getVisibleChunkIfPresent` (#2) are unchanged. The class-level throttling javadoc still
names the GONE `ChunkTaskPriorityQueueSorter`/`ProcessorMailbox` — kept verbatim (comment only; class has
`@SuppressWarnings("JavadocReference")`; #24's javadoc refresh is a doc-only nicety, deferred).

---

## 3. R13j — F1 FORCED DEVIATION: `WorldInfoSender` shrinks to weather-only

**Register row (EXECUTION_PLAN §4 forced-deviation register):**
`F1 | WorldInfoSender time-half deleted (weather-only survives) | 26.2-forced | S9 | clock-map
ClientboundSetTimePacket, no per-dim daylight boolean (R13j)`.

**What IP did (1.21.3, DELETED):** `sendWorldInfo` opened with a redirected per-dimension time packet so
a player standing *outside* a skylight dimension still saw that remote dimension's day-time:

```java
ResourceKey<Level> remoteDimension = world.dimension();
PacketRedirection.sendRedirectedMessage(player, remoteDimension,
    new ClientboundSetTimePacket(world.getGameTime(), world.getDayTime(),
        world.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
```

**Why it cannot be translated (26.2 ground truth, re-derived this pass):**
- `ClientboundSetTimePacket` is now the **connection-global** record
  `ClientboundSetTimePacket(long gameTime, Map<Holder<WorldClock>, ClockNetworkState> clockUpdates)`
  (`ClientboundSetTimePacket.java:14`) — **no `dayTime` long, no daylight boolean** (#34).
- `Level.getDayTime()` is **GONE** — grep for `getDayTime` over both `Level.java` and `ServerLevel.java`
  returns empty (#40).
- `GameRules.RULE_DAYLIGHT` is **GONE** — grep `DAYLIGHT` over `world/level/gamerules/GameRules.java`
  (note the class moved to the new `gamerules` package) returns empty; replacement is the **global**
  `GameRules.ADVANCE_TIME` read connection-wide, not per-dimension (#43).
- Vanilla already syncs one global `gameTime` + every registered `WorldClock` state to **every** player
  regardless of dimension (`MinecraftServer.forceGameTimeSynchronization` →
  `ClientboundSetTimePacket(overworld().getGameTime(), Map.of())` to all; full clock sync on login via
  `PlayerList.sendLevelInfo`). Client handling is dimension-agnostic (`ClientPacketListener.handleSetTime`).
  **The "player outside overworld needs overworld time" problem the IP code existed to solve no longer
  exists.**

**Decision: the time half is DELETED, not translated** — the one deliberate deviation-from-IP at S9.
Rationale for deletion over a `Map.of()` stub: a hand-built `ClientboundSetTimePacket(gameTime, Map.of())`
would merely *duplicate* vanilla's already-global sync, and any per-dim clock-map variant risks
*desyncing* the connection-global clock manager — i.e. faithful translation is impossible AND a stub is a
net negative. The deletion is recorded **inline in `sendWorldInfo` as a citing comment** (api-map
#34/#43 — verified present in the held file), and the now-dead local `remoteDimension` (only ever the
time packet's dimension arg — the weather sends use `world.dimension()` inline) is removed with it. The
two imports that fed only the deleted half (`ClientboundSetTimePacket`, `GameRules`) are removed.

**Weather half is UNTOUCHED and stays un-dimensioned** (the preserved cross-dimension weather guard):
`init()` still walks every visible non-overworld skylight dimension (plus the overworld when the player
is elsewhere) and redirects the rain/thunder game events. Ported 1:1 — `ClientboundGameEventPacket(Type,
float)` + `START_RAINING`/`RAIN_LEVEL_CHANGE`/`THUNDER_LEVEL_CHANGE` all SAME (#35); `world.isRaining()`
/ `getRainLevel(1.0F)` / `getThunderLevel(1.0F)` SAME (#40); `dimensionType().hasSkyLight()` SAME.
The rain-flip broadcast remains un-dimensioned in 26.2 (nothing in the weather path became global), so the
guard is **still load-bearing**. **S14 weather-check** verifies rain/thunder still cross correctly; there
is nothing to verify for time (vanilla owns it globally now).

---

## 4. R13f — CARRIAGE: the `SeamlessClientChunkMap` fusion into ported `ImmPtlClientChunkMap`

R13f (risk-map owner **S9**; runtime-proof S14/S17 item 9) is the 26.2 `ClientChunkCache` rework that has
**no 1.21.3/IP analog** and must be reproduced or every renderer bound to a secondary dimension sees
phantom/missing chunks in its occlusion graph (the `distant-chunk-vanish-sog-desync` failure class).

### 4.1 Fusion decision — APPROACH (a): NEW held file at the IP path; LIVE `SeamlessClientChunkMap` UNTOUCHED

The ported `ImmPtlClientChunkMap` lands as a **new HELD file** at
`common/src/main/java/qouteall/imm_ptl/core/chunk_loading/ImmPtlClientChunkMap.java` (excluded until
`ip_scc_closed=true` at S13). It ports IP's `ImmPtlClientChunkMap` verbatim + the mechanical 26.2
translations + **REUSES the mod's runtime-proven 26.2 delta-tracking / store-center additions** (the R13f
carriage). **The live `com.warwa.seamlessportals.client.SeamlessClientChunkMap` is NOT moved, renamed, or
edited.**

Approach (b) — re-homing `SeamlessClientChunkMap` to the IP path as a LIVE change — is **structurally
impossible at S9** (three independent reasons):
1. **The IP path is a HELD path** (`qouteall/**` excluded until S13, D1) — a class there is not compiled
   and cannot be referenced by live mod code.
2. **The live class is referenced by LIVE code that must keep compiling every stage** —
   `PortalWorldManager.java:671` does `new SeamlessClientChunkMap(destLevel, destViewRadius)` (`:424`
   `instanceof`). Re-homing turns `:common:compileJava`/`:fabric:compileJava` red immediately — the exact
   gate the plan forbids breaking.
3. **`SeamlessClientChunkMap` is a substrate KEEP that must stay LIVE in BOTH flag states**
   (`EXCLUSIVITY_LEDGER.md` §3, core §5). A live change at S9 would alter block-era behavior under
   `entityPortals=false`, violating the inert-middle contract (S4–S12 must be behavior-inert).

Approach (a) is also what the plan already ASSUMES: the held `O_O.java` (landed S4) at line 26 imports
`qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap`, and its `createMyClientChunkManager` (`:88-89`)
returns `new ImmPtlClientChunkMap(world, loadDistance)` — a **held→held** edge that only resolves if a
separate held file exists at the IP path. If slice C did not create it, the probe would show `O_O.java:26`
as an unresolved import NOT in any documented ledger — a spurious triage failure. Creating the held file is
**mandatory, not optional**. Net: the held `ImmPtlClientChunkMap` is MORE faithful to IP than the live
simplified port — it restores dual-map load/unload `SignalArged` signals,
`O_O.postClientChunk{Load,Unload}Event`, `SodiumInterface.invoker`, the error-reporting
`loadChunkDataFromPacket`, `isChunkLoaded`, `getCopiedChunkList`, `IEMinecraftClient.ip_getRunningThread`
thread source — while carrying the mod's proven 26.2 delta/store surface forward.

### 4.2 What rode in (both blocks byte-identical to the mod's proven class, demarcated inline)

- **SOG delta feed (MANDATORY, #45 "critical, new vanilla surface").** Own double-buffered sets + the
  overrides `addedEmptySections`/`removedEmptySections`/`addedLoadedChunks`/`removedLoadedChunks`/
  `flipUpdateTrackingSets` + the new `ChunkSource` override `onSectionEmptinessChanged(int,int,int,boolean)`,
  plus `emit{ChunkAdded,ChunkRemoved,Refresh}` woven into `drop` (removal) and `replaceWithPacketData`
  (new-chunk add / existing-chunk refresh). The `emit*` helpers mirror vanilla
  `Storage.onChunkAdded/onChunkRemoved/refreshEmptySections` (`ClientChunkCache.java:274-312`) **minus the
  `inRange` gate** (the store is unbounded). Chain consumed at `LevelExtractor.extract` (`:138-142`) →
  `SectionOcclusionGraph` (`:146-147`). This block is non-negotiable and lives in THIS class (it is a
  `ClientChunkCache` override surface). Verified present + demarcated (`// R13f carriage — 26.2 SOG delta
  feed …`) in the held file.
- **Store-center machinery (carried; per-tick DRIVER at S10).** Tracked view center (`updateViewCenter`
  now records instead of IP's no-op) + `evictBeyond` (tracked center) / `evictAround` (explicit
  dest-origin PINNING — memory `walking-limbo-seed-overclaim` §5) / `evictAll` (grace-window release), all
  via a private `evictBeyondCenter`. These are the STORE-side methods; the per-tick recenter DRIVER is the
  ported `ClientWorldLoader` at S10.
  **PORT-FORWARD (verify) obligation (C8/F20 gate):** under IP's constructor-hook install (all client
  worlds incl. main), the SERVER's `ImmPtlChunkTracking` already prunes + drives vanilla forget packets →
  vanilla `drop`. Whether the client-side eviction sweeps remain NECESSARY for inactive secondaries under
  the ported tracking is **re-proven at S14/S17** ("proven empirically post-port, not assumed away",
  core §11.7). They are NOT assumed-away now.

`onLightUpdate` kept **verbatim IP** — `ClientWorldLoader.getWorldRenderer(level.dimension())
.setSectionDirty(x,y,z)` (verified `:291-292`). The 26.2 render-split adaptation (routing per-dim
light-dirty to the per-dim `LevelExtractor` rather than the global `mc.levelExtractor` — #45 "cross-slice
contract with the render map"; memory `nether-block-freeze-orphaned-extractor`) is owned by the
`ClientWorldLoader` port at **S10/S11** (core §11.1), where the mod's proven `PortalWorldManager.getExtractor`
+ main-level fallback routing is re-derived INTO `getWorldRenderer`. Slice C does not pre-empt that API.

### 4.3 Install point + S10/S13 reconciliation flag

- **Install point (faithful):** IP installs the chunk map from the `ClientLevel` CONSTRUCTOR mixin
  `MixinClientLevel.onConstructed` (`@Inject(method="<init>", at=@At("RETURN"))`, IP
  `mixin/client/MixinClientLevel.java:97-110`) → `chunkSource = O_O.createMyClientChunkManager(...)`,
  covering **EVERY client world including the vanilla main one** (NOT `createSecondaryClientWorld`, which
  has zero chunk-map code — core §5). `MixinClientLevel` is a U8 file that lands HELD at **S10**. Its 26.2
  install must retarget the swapped `chunkSource` descriptor (#46 — ctor now takes/stores a
  `LevelExtractor`) and coordinate with the render slice's per-dim `LevelExtractor` identity. **The
  per-tick store-center DRIVER** — the ported `ClientWorldLoader` replacing
  `PortalWorldManager.evictUnboundedStores` (core §5/§11.1) — also lands at S10 and calls
  `evictAround`/`evictBeyond`/`evictAll`.
- **S13 — registration + flag.** `MixinClientLevel` is registered (flag-gated) at S13. Under
  `entityPortals=true` the IP chunk map serves all client worlds; under `entityPortals=false` the block-era
  path (incl. the live `SeamlessClientChunkMap` on secondaries) serves. The live `SeamlessClientChunkMap` +
  its `ClientLevelChunkSourceAccessor` install + `PortalWorldManager` references are retired at **S20** with
  the rest of the block-era client stack (EXCLUSIVITY_LEDGER §3 / S20 archive).

**RECONCILIATION FLAG (for the orchestrator):** at final closure ONE class should serve as THE IP client
chunk map. Today there are two — held `ImmPtlClientChunkMap` (the IP driver) and live
`SeamlessClientChunkMap` (the block-era secondary store). They are **NOT auto-unified**: S10 wires the held
one in behind the flag; S20 deletes the live one. Confirm at S13/S14 that no code path expects BOTH under
`entityPortals=true`.

---

## 5. Category-(c) resolutions + api-map amendments

**Category-(c) is the D4.3 residual class:** a hunk or forward-ref that is NOT a single clean
api-map-row translation, written up as a numbered entry here rather than pointed at one row. **No
api-map FILE was amended this stage** — `chunk-loading.md` (33 SAME / 10 CHANGED / 8 GONE) already
carried every row S9 needed; git status confirms it is unmodified. The residual items:

- **(c-1) `MixinChunkMap_C.onChunkReadyToSend` — compound overwrite, not a one-line rename (#6 CHANGED).**
  1.21.3 `onChunkReadyToSend(LevelChunk)` did only the vanilla-sender player-loop marking; IP `@Overwrite`s
  it to a no-op. 26.2 changed the signature to `onChunkReadyToSend(ChunkHolder, LevelChunk)` **and gained a
  tail** (`ChunkMap.java:690-701`): `this.level.getChunkSource().onChunkReadyToSend(chunkHolder)` (registers
  the holder into `ServerChunkCache.chunkHoldersToBroadcast`, the set driving every `broadcastChanges`,
  `:586-590`) + `this.level.debugSynchronizers().registerChunk(chunk)`. A blind whole-method no-op with the
  new signature would (a) fail to bind (old descriptor gone → mixin apply crash) and (b) suppress the
  broadcast-set registration, so block updates accumulated while a chunk loads would never flush (IP's own
  block-update path relies on `broadcastChanges`). **Resolution:** overwrite with `onChunkProvidedDeferred(
  chunk)` (IP's intent — cancel the player-loop marking) **plus the replayed tail**, both reachable via the
  shadowed `level` (`ServerChunkCache.onChunkReadyToSend` public `:586`; `ServerLevel.debugSynchronizers()`
  public `:1841`). Recorded inline as a #6 javadoc in the held file. (Plan S10 lists this under the R12
  vanilla-copy set; applied here because the mixin lands here.)

- **(c-2) `IEChunkMap_Accessor` #55 `getChunks()` → `visibleChunkMap` accessor.** `ChunkMap.getChunks()` is
  GONE; 1.21.3's `getChunks()` returned `visibleChunkMap.values()`. A blind `@Invoker("getChunks")` on a
  missing target crashes at mixin apply. **Resolution:** `@Accessor("visibleChunkMap")
  Long2ObjectLinkedOpenHashMap<ChunkHolder> ip_getVisibleChunkMap()`; the sole consumer
  (`PortalDebugCommands.report_chunk_ticket_stat`, U11 → S13) reads `.values()` — a documented S13
  consumer-side adaptation (§6). This file was the one member of IP's 10-file chunk_sync package that the
  first api-map pass silently skipped (added by the verification pass; #55) — load-bearing despite its only
  consumer being a debug command, because a missing-target invoker is a hard startup crash.

- **(c-3) established external-dependency ledger entries (governed, D4.2 — NOT translation slips).** The
  probe shows four external-dependency families on U7 files, all **verbatim IP imports** carried forward
  from prior stages, resolved by the loader seam at registration/S13 or by the DimLib/Fabric classpath at
  the shipping build — never on the `-Pip_scc_closed=true` common-only probe classpath: Fabric-API
  `ServerTickEvents` (#51), Fabric `@Environment`/`EnvType` (held-tree convention — 40 held files keep it),
  Fabric-internal `attachment`/`attachment.sync` (#53, Fabric-side re-verify item), DimLib `DimensionAPI`
  (#54, third-party). Enumerated per-file in §6.

**api-map amendment tally this stage: 0 rows.** (Contrast S7: `portal-generation.md` amended; S8:
`teleportation-collision.md` +2 GONE rows.)

---

## 6. Probe-vs-U7-union triage (D4.2 — the authoritative ledger)

`:common:compileJava -Pip_scc_closed=true` → **536 whole-tree errors** (BUILD FAILED, expected). On the U7
slice: **33 errors across the 12 non-mixin files; 0 across all 9 chunk_sync mixins** (they compile clean —
every target is vanilla or a resolved held duck). **Zero translation slips** (the slip-shape grep is
empty: no `method … location`, `incompatible types`, `cannot be applied`, `does not override`, `no
suitable method`, or bad-operand error on any U7 file).

| File | Errs | Symbol(s) — all documented | Category |
|---|---|---|---|
| `ChunkVisibility.java` | 0 | — | clean |
| `DimensionalChunkPos.java` | 0 | — | clean |
| `PerformanceLevel.java` | 0 | — | clean |
| all 9 `chunk_sync` mixins | 0 | — | clean |
| `ImmPtlChunkTracking.java` | 4 | `ServerTickEvents` (:8,:46), `DimensionAPI` (:22,:49) | A — loader/external |
| `ImmPtlChunkTickets.java` | 2 | `DimensionAPI` (:20,:80) | A |
| `EntitySync.java` | 2 | `qouteall.dimlib.api`/`DimensionAPI` (:9,:17) | A |
| `WorldInfoSender.java` | 2 | `ServerTickEvents` (:3,:18) | A |
| `ServerPerformanceMonitor.java` | 2 | `ServerTickEvents` (:3,:18) | A |
| `PlayerChunkLoading.java` | 5 | Fabric-internal `attachment[.sync]` (:6,:7 import; :221,:222,:225 usage) | A |
| `ImmPtlClientChunkMap.java` | 6 | Fabric `@Environment`/`EnvType` (:7,:8,:31) ; **`ClientWorldLoader`** (:77×2,:292) | A + **B (U8→S10)** |
| `ClientPerformanceMonitor.java` | 6 | Fabric `@Environment`/`EnvType` (:3,:4,:14×2) ; **`PortalDebugCommands`** (:9,:48) | A + **B (U11→S13)** |
| `ChunkLoader.java` | 4 | **`FastBlockAccess`** (:10 import; :102,:108,:109 usage) | **B (U12→S13)** |

**Tally:** Category A (established loader/external debt, verbatim IP) = **24** — `ServerTickEvents` ×6,
`@Environment`/`EnvType` ×7, Fabric-internal attachment ×5, DimLib `DimensionAPI` ×6. Category B
(documented S9(b) forward-ref union) = **9** — `FastBlockAccess`→U12/S13 ×4, `PortalDebugCommands`→U11/S13
×2, `ClientWorldLoader`→U8/S10 ×3. 24 + 9 = 33. Every error accounted for.

**S9(b) forward-ref ledger — reconciled against the probe:**
- `ChunkLoader.java:10` → `portal.nether_portal.FastBlockAccess` — **U12, resolves S13.** This is the
  grep-found COMPILE edge the U7 row's "runtime-only edge to U8" characterization missed; it proves the SCC
  extends into U12, so `FastBlockAccess` sits in the S13 U12 closure slice.
- `ClientPerformanceMonitor.java:9,:48` → `commands.PortalDebugCommands` — **U11, resolves S13.** The
  round-3-sweep addition to the ledger (`ClientPerformanceMonitor.java:9` was missed by earlier passes);
  flagged so the triage rule does not misfire it as a translation slip.
- `ImmPtlClientChunkMap.java:77,:292` → `qouteall.imm_ptl.core.ClientWorldLoader` — **U8, resolves S10.**
  The U7 row's "runtime-only edge to U8" is FALSE at javac granularity — this is a COMPILE import. The
  file's other non-vanilla refs (`SodiumInterface` landed S4 via F21 stub classpath; `IPVanillaCopy` via
  S4 carve-in; `CHelper`/`McHelper`/`O_O`/`IEMinecraftClient`/`SignalArged` all held S2/S4/S5) resolve
  in-probe — confirmed by their ABSENCE from the probe error list. `ClientWorldLoader` is the only edge red
  until S10, exactly as the ledger predicts.

**Same-stage / in-probe resolutions (present, not errors):** the same-package U7 siblings referenced across
slices (`EntitySync`, `PerformanceLevel`, `ServerPerformanceMonitor`, `ImmPtlChunkTracking.getVisibleDimensions`)
all resolve at the full-SCC probe after every S9 slice lands — none appear in the error list.
`IEServerChunkCache.ip_getDistanceManager` (used by `ImmPtlChunkTickets`) is implemented by
`mixin/common/MixinServerChunkCache` (an S10 U8 file); compile-only at S9 (interface cast), runtime bind at
S10/S13 — no probe error. The mod-owned `TicketTypeInvoker` registration call site is wired at S13
(registry phase).

---

## 7. Cross-slice / disposition summary

- **Held this stage (21 files + 1 duck edit):** 11 `chunk_loading/` + `miscellaneous/ClientPerformanceMonitor`
  + 9 `chunk_sync` mixins; `ducks/IEDistanceManager` edited (R10, §1c). All unregistered until S13.
- **Dropped:** `chunk_sync/IEChunkTaskPriorityQueueSorter` (#24 — both target types GONE, no consumer).
- **Deferred to S10:** `MixinClientLevel` install of the held chunk map; the per-tick `ClientWorldLoader`
  store-center DRIVER + the per-dim `LevelExtractor` light-dirty routing (§4.3); `MixinServerChunkCache`
  (`ip_getDistanceManager` impl); the `MixinServerLevel` #39 keep-alive redirect that pairs with 2(iii).
- **Deferred to S13:** `TicketType` registration (registry phase, via `TicketTypeInvoker`, unconditional);
  mixin registration + flag wiring; `FastBlockAccess` (U12) and `PortalDebugCommands` (U11) closure.
- **Regression-watch (S14/S17):** the FLAG_SIMULATION mob-spawn/flood implication (§2(ii)); the R13f
  client-eviction necessity re-proof (§4.2, C8/F20); S14 weather-check for F1 (§3).
- **Retirement (S20):** live `SeamlessClientChunkMap` + `ClientLevelChunkSourceAccessor` +
  `PortalWorldManager` references, once the flag path is proven (§4.3 reconciliation flag).
