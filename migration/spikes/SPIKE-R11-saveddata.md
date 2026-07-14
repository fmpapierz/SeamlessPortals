# SPIKE-R11 — SavedData silent-loss trap (EXECUTION_PLAN S1(a); API_RISKS R11; portal-generation G1)

- **Date:** 2026-07-13 · **Branch:** `spike/r11-saveddata` (worktree `wf_c463c3ac-d47-1`, commits `7bdcd34b` + `525ec12c`, never merged)
- **Method:** throwaway Fabric entrypoint (`com.warwa.seamlessportals.spike.r11.*`) registering three GlobalPortalStorage-shaped
  `SavedDataType`s; 3 headless `:fabric:runServer` boots (phase file drives one phase per boot: write+save → restart+read →
  restart+datafix/loss probes); evidence = `[SPIKE-R11]` log lines + `.dat` sha256/size + raw NBT dumps. Payload mimics IP
  `GlobalPortalStorage.save` (`IP:.../global_portals/GlobalPortalStorage.java:266-295`): `{data: ListTag of 2 entity-NBT-like
  compounds (Pos doubles, Rotation floats, entity_type string, quaternion compound, ...), version: 2}` via
  `CompoundTag.CODEC.xmap` pass-through (the fidelity route from portal-generation.md G1). Env: MC 26.2 (DataVersion 4903),
  fabric-loader 0.19.3, fabric-api 0.152.1+26.2.

## Decisions (deliverables)

**D-R11-1 — DataFixTypes constant: `DataFixTypes.SAVED_DATA_COMMAND_STORAGE`.** Round-trips the GPS-shaped payload bit-exact
through save → server restart → load, both same-version AND through a real cross-version datafixer pass (see Evidence 2/3).
Rationale for this constant over the other 10 zero-fix candidates: vanilla itself uses it for **arbitrary user NBT**
(`26.2:world/level/storage/CommandStorage.java:80` — `/data storage` containers), so Mojang can never write a shape-assuming
fix against its TypeReference; it is registered as opaque passthrough (`DSL::remainder`) in both schema declarations
(`26.2:util/datafix/schemas/V99.java:291`, `V1460.java:301`) and **zero fixes target it** in 26.2 (grep over
`util/datafix/fixes/`: the only SAVED_DATA references with fixes are MAP_DATA, MAP_INDEX, TICKETS, RAIDS, RANDOM_SEQUENCES,
SCOREBOARD, WORLD_BORDER). `SAVED_DATA_WEATHER` was tested as backup and behaves identically (same passthrough registration,
zero fixes) — but command_storage has the stronger forever-guarantee semantics.

**D-R11-2 — the null verdict is more nuanced than the corpus claim: BOTH loaders rescue null in 26.2.** The vanilla-source
NPE→silent-loss chain is real (see API facts below) **but is patched out on both loaders**:
- Fabric API `fabric-object-builder-api-v1` 24.0.6 ships `SavedDataStorageMixin.handleNullDataFixType` — bytecode
  (`javap`): `aload_1; ifnonnull …; aload_3; areturn` = if `dataFixTypes == null` return the raw tag, skipping
  `DataFixTypes.update`. Empirically confirmed: our `TYPE_NULL` (null dataFixType) loaded its full 2-portal payload after
  restart with **no error line** (phase-2 log below).
- NeoForge 26.2.0.7-beta binary-patches the same call site: `readTagFromDisk` bytecode from
  `minecraft-merged-official-at-patched.jar` shows `109: aload_2; 110: ifnull 167;` before
  `123: invokevirtual DataFixTypes.update` — an explicit null guard (bytecode-proven only; NOT runtime-tested here).
- Same lineage existed on IP's own platform: fabric-api 0.116.12+1.21.1 object-builder contains
  `PersistentStateManagerMixin.handleNullDataFixType(class_4284, DataFixer, class_2487, int, int, Operation)` — i.e. **IP's
  1.21.3 `null` was always riding the Fabric API rescue**, since 1.21.x vanilla `DimensionDataStorage` also called
  `dataFixType.update` unguarded.
**Port decision:** pass `SAVED_DATA_COMMAND_STORAGE` anyway. Null's safety is a loader-patch artifact (a fabric-api module
someone can jar-exclude, and an unverified-at-runtime NeoForge patch); a zero-fix constant costs nothing (see D-R11-4:
same-version load doesn't even enter the fixer) and keeps behavior identical to IP-on-fabric-api (= no destructive fixing).

**D-R11-3 — the silent-loss signature for the S13 relog check (two distinct lines, both end in fresh-empty overwrite).**
Any `Exception` inside the read funnels into `readSavedData`'s `catch (Exception)` → error log → `null` → the
GlobalPortalStorage.get-style `computeIfAbsent` constructs a fresh empty AND marks it dirty (`SavedDataStorage.set`
`:104-107` calls `data.setDirty()`) → **the next save is guaranteed to clobber the old file**. Captured verbatim (phase 3):

Signature A — read/fix exception (this is where a vanilla null-NPE would land, and where file corruption lands):
```
[19:49:16] [Server thread/ERROR] (Minecraft) Error loading saved data: SavedDataType[seamlessportals:spike_r11_null]
net.minecraft.nbt.ReportedNbtException: Loading NBT data
	at knot//net.minecraft.nbt.NbtIo.readTagSafe(NbtIo.java:195)
	...
	at knot//net.minecraft.world.level.storage.SavedDataStorage.readTagFromDisk(SavedDataStorage.java:118)
	at knot//net.minecraft.world.level.storage.SavedDataStorage.readSavedData(SavedDataStorage.java:91)
	at knot//net.minecraft.world.level.storage.SavedDataStorage.get(SavedDataStorage.java:81)
```
Signature B — codec rejects the payload (layout drift; logged WITHOUT a stacktrace, easy to miss):
```
[19:49:16] [Server thread/ERROR] (Minecraft) Failed to parse saved data for 'SavedDataType[seamlessportals:spike_r11_strict]': No key must_exist in MapLike[{data:[{Pos:[100.5d,...
```
S13's global-portal relog check must grep for BOTH `Error loading saved data: SavedDataType[<ns>:` and
`Failed to parse saved data for 'SavedDataType[<ns>:`.

**D-R11-4 — same-version loads never enter the datafixer at all.** DFU 10.0.21 (the version in 26.2's dep set)
`DataFixerUpper.java:75-83`: `update(...)` is `if (version < newVersion) {…} return input;` — a file written and read on the
same DataVersion returns the input `Dynamic` untouched regardless of the constant chosen. Also note `:78-79`: even when a
cross-version rewrite fails to read the type, it logs and falls back to the input (`resultOrPartial(LOGGER::error)
.orElse(input.getValue())`) rather than destroying it.

**D-R11-5 — NEW (corrects portal-generation.md G1 row 2): the per-dimension data folder MOVED in 26.2.**
The corpus claims "per-dimension file location semantics survive … `data/global_portal.dat` inside each dimension folder is
unchanged" — WRONG on two axes, empirically observed:
1. `DimensionType.getStorageFolder` (`26.2:world/level/dimension/DimensionType.java:116-118`) is now uniformly
   `<world>/dimensions/<dimNs>/<dimPath>/` for EVERY dimension **including the overworld** (no more overworld=root /
   `DIM-1` / `DIM1` special cases). `ServerLevel.getDataStorage()` (`ServerLevel.java:1442` → `ServerChunkCache.java:94-102`,
   dataFolder = `getDimensionPath(dim).resolve("data")`) therefore writes the overworld's saved data to
   `<world>/dimensions/minecraft/overworld/data/`, observed on disk:
   `world/dimensions/minecraft/overworld/data/seamlessportals/spike_r11_cmd.dat` (vanilla per-dim data — `raids.dat`,
   `world_border.dat`, `chunk_tickets.dat` — sits next to it under `.../data/minecraft/`).
2. The file name is namespaced into a SUBDIRECTORY: `SavedDataStorage.getDataFile` (`SavedDataStorage.java:56-63`) resolves
   `Identifier.resolveAgainst` (`Identifier.java:152-161`) = `data/<typeNamespace>/<typePath>.dat` — not the old flat
   `data/<name>.dat`.
   Also: `<world>/data/` still exists but belongs to a SEPARATE server-global store — `MinecraftServer.savedDataStorage`
   (`MinecraftServer.java:330`, holds weather/game_rules/scoreboard/random_sequences...; accessor `:2142`). Do NOT confuse
   the two when porting; `world.getDataStorage()` in GlobalPortalStorage keeps per-dimension semantics, only the disk path
   changes. No legacy-name migration exists in `SavedDataStorage` (grep "legacy" = 0 hits) — an IP-1.21.3 world's old
   `data/global_portal.dat` would simply be ignored (fresh storage), which is fine for THIS mod (it never shipped
   GlobalPortalStorage) but rules out importing IP worlds' global portals without a manual migration step.

**D-R11-6 — misc port-relevant mechanics (all observed):**
- On-disk format: gzipped `{DataVersion:<int>, data:<codec output>}` (`encodeUnchecked`, `SavedDataStorage.java:198-204`).
  The `CompoundTag.CODEC.xmap(fromNbt, toNbt)` pass-through reproduces the exact GPS layout under the `data` key — raw dump:
  `{DataVersion:4903,data:{data:[{Pos:[100.5d,...],...},...],version:2}}`. Long/float/double/byte NBT types survived
  bit-exact (snbt sha `405082c0070445ff` identical across all three boots).
- A cross-version load does NOT rewrite the file: after the 3465→4903 fix pass loaded correctly, the file on disk still
  had `DataVersion:3465` (sha `8761c4aad92d4acd` unchanged) because clean data is never re-saved
  (`collectDirtyTagsToSave` filters `isDirty`, `SavedDataStorage.java:188-196`).
- Writes are async (`Util.ioPool()`), joined by `saveAndJoin()` (`:217-219`); server shutdown flushes via
  `ServerLevel.saveLevelData` (`ServerLevel.java:898-905`) and `SavedDataStorage.close()` (`ServerChunkCache.java:309`).

## Evidence log (verbatim excerpts)

**Phase 1 — boot 1, write+save** (fresh world; three types: CMD=SAVED_DATA_COMMAND_STORAGE, WEATHER=SAVED_DATA_WEATHER,
NULL=null):
```
[19:43:40] ... [SPIKE-R11] ===== PHASE 1 ===== (currentDataVersion=4903)
[19:43:40] ... [SPIKE-R11] per-dim (overworld) data dir = world\dimensions\minecraft\overworld\data
[19:43:40] ... [SPIKE-R11] P1 expected payload snbt sha256=405082c0070445ff snbt={data:[{Pos:[100.5d,64.0d,-200.25d],...],version:2}
[19:43:40] ... [SPIKE-R11] data file: seamlessportals\spike_r11_cmd.dat        (also _null, _weather)
[19:43:40] ... [SPIKE-R11] P1 after save | spike_r11_cmd.dat size=287 sha256=77224d4c4e88a056   (all three identical)
```

**Phase 2 — boot 2, restart+read** (same-version round-trip + null probe):
```
[19:44:17] ... [SPIKE-R11] P2 before read | spike_r11_cmd.dat size=287 sha256=77224d4c4e88a056
[19:44:17] ... [SPIKE-R11] P2 CMD(SAVED_DATA_COMMAND_STORAGE) same-version roundtrip: PASS (loaded sha256=405082c0070445ff, expected sha256=405082c0070445ff)
[19:44:17] ... [SPIKE-R11] P2 WEATHER(SAVED_DATA_WEATHER) same-version roundtrip: PASS (...)
[19:44:17] ... [SPIKE-R11] P2 NULL-fixtype get() returned: {data:[{Pos:[100.5d,...],...],version:2}   <- FULL payload, no error: fabric-api rescued null
```

**Phase 3 — boot 3** (DataVersion downgraded to 3465 on cmd/weather; null file corrupted with garbage; cmd payload copied
under a strict-codec type):
```
[19:49:15] ... [SPIKE-R11] downgraded spike_r11_cmd.dat DataVersion 4903 -> 3465     (file sha 8761c4aad92d4acd)
[19:49:16] ... [SPIKE-R11] P3 CMD(SAVED_DATA_COMMAND_STORAGE) cross-version (3465->current) datafixer pass: PASS (loaded sha256=405082c0070445ff, ...)
[19:49:16] ... [SPIKE-R11] P3 WEATHER(SAVED_DATA_WEATHER) cross-version (3465->current) datafixer pass: PASS (...)
[19:49:16] [Server thread/ERROR] (Minecraft) Error loading saved data: SavedDataType[seamlessportals:spike_r11_null]     <- signature A
[19:49:16] ... [SPIKE-R11] P3 corrupted-file get() returned: null
[19:49:16] ... [SPIKE-R11] P3 computeIfAbsent after failed read -> fresh empty (portals=0, version=1), dirty (GlobalPortalStorage.get analog)
[19:49:16] [Server thread/ERROR] (Minecraft) Failed to parse saved data for 'SavedDataType[seamlessportals:spike_r11_strict]': No key must_exist in MapLike[{data:[...   <- signature B
[19:49:16] ... [SPIKE-R11] P3 after save (loss end state) | spike_r11_null.dat size=59 sha256=4ad54f44b1003498      (was 21B garbage)
[19:49:16] ... [SPIKE-R11] P3 after save (loss end state) | spike_r11_strict.dat size=63 sha256=5388dd6f278c6525    (was the 287B 2-portal payload)
[19:49:16] ... [SPIKE-R11] P3 null file raw (fresh-empty overwrote garbage): {DataVersion:4903,data:{data:[],version:1}}
[19:49:16] ... [SPIKE-R11] P3 strict file raw (fresh-empty overwrote 2-portal payload): {DataVersion:4903,data:{must_exist:0}}
```
The strict-type line is the full end-to-end silent loss: a VALID 287-byte 2-portal payload silently replaced by a 63-byte
fresh-empty — two ERROR log lines are the only trace.

## API facts (26.2 source, `C:/Users/warwa/ModDev/mc262-ref`)

- `SavedDataType<T extends SavedData>(Identifier id, Supplier<T> constructor, Codec<T> codec, DataFixTypes dataFixType)` —
  record, no null-check (`world/level/saveddata/SavedDataType.java:8`); equals/hashCode by id only (`:9-17`).
- Read path: `readSavedData` (`world/level/storage/SavedDataStorage.java:86-102`): `readTagFromDisk(file,
  type.dataFixType(), currentDataVersion)` at `:90`; codec parses `tag.get("data")` at `:92-95` with
  `resultOrPartial(→ "Failed to parse saved data for '{}': {}") .orElse(null)`; the WHOLE body is wrapped in
  `catch (Exception e) → LOGGER.error("Error loading saved data: {}", type, e)` at `:97-99`, returns null.
- The vanilla null-NPE site: `readTagFromDisk` `:109-126` calls `type.update(this.fixerUpper, tag, version, newVersion)`
  UNCONDITIONALLY at `:124` (dev-runtime stacktrace shows the same frames at runtime lines :118/:91/:81 — decompile offset,
  same method). `DataFixTypes` has NO `NONE` member (`util/datafix/DataFixTypes.java:16-47`).
- Overwrite mechanics: `get` caches `Optional.empty` on failed read (`:76-84`); `computeIfAbsent` (`:65-74`) then
  constructs via `type.constructor()` and `set(type, newData)` (`:104-107`) which `setDirty()`s → `collectDirtyTagsToSave`
  (`:188-196`) picks it up on the next `scheduleSave` (`:146-186`) → `tryWrite` (`:206-215`) clobbers the file.
- Vanilla usage example: `WeatherData.TYPE` (`world/level/saveddata/WeatherData.java:19-21`).
- Zero-fix SAVED_DATA constants in 26.2 (no fix in `util/datafix/fixes/` targets their reference): COMMAND_STORAGE,
  CUSTOM_BOSS_EVENTS, ENDER_DRAGON_FIGHT, GAME_RULES, SCHEDULED_EVENTS, STOPWATCHES, STRUCTURE_FEATURE_INDICES,
  WANDERING_TRADER, WEATHER, WORLD_CLOCKS, WORLD_GEN_SETTINGS.

## Proven vs unproven

PROVEN (captured above): same-version round-trip for SAVED_DATA_COMMAND_STORAGE + SAVED_DATA_WEATHER; non-destructive REAL
cross-version pass 3465→4903 for both; null-dataFixType is load-safe on Fabric 26.2 (fabric-api 0.152.1); both loss
signatures + the fresh-empty overwrite; the per-dim data folder move + namespace subdir; format/dirty/flush mechanics.

UNPROVEN / caveats:
- **NeoForge runtime**: null guard proven in bytecode (26.2.0.7-beta patched jar) but no runServer executed on NeoForge.
- **Vanilla-unmodded NPE**: source-proven only (`:124`); cannot execute in any modded dev env because both loaders patch the
  call site. The in-situ reproduction used file corruption, which lands in the identical `catch(Exception)` funnel.
- Cross-version tested from 3465 (1.20.1) only; older DataVersions (pre-V1460 schemas) untested — irrelevant for a
  new-in-26.2 modded file, which will never carry an older version than its first write.
- fabric-api dependency: the null rescue lives in the `fabric-object-builder-api-v1` module; a distribution that excludes it
  reverts null to the vanilla silent-loss path — one more reason for D-R11-1.

Spike code (never merge): `fabric/src/main/java/com/warwa/seamlessportals/spike/r11/{SpikeR11Data,SpikeR11Init}.java`
on branch `spike/r11-saveddata` (+ a 2-line fabric.mod.json entrypoint registration).

## Review verdict (S1 adversarial review, 2026-07-13)

**PASS.** Every conclusion is evidence-backed; independent re-verification found zero citation errors.
- mc262-ref re-checked line-exact: `DataFixTypes` has no `NONE` and `SAVED_DATA_COMMAND_STORAGE` exists (`DataFixTypes.java:25`); `CommandStorage.java:80` usage; `V99.java:291` / `V1460.java:301` `DSL::remainder` registrations; reviewer's own grep of `util/datafix/fixes/` reproduces EXACTLY the memo's fixed-type list (MAP_DATA, MAP_INDEX, TICKETS, RAIDS incl. SavedDataUUIDFix, RANDOM_SEQUENCES, SCOREBOARD, WORLD_BORDER — command_storage untargeted); `readSavedData`'s whole-body `catch (Exception)` → null and `readTagFromDisk`'s unconditional `type.update(...)`; `DimensionType.getStorageFolder` uniform `dimensions/` resolution; `getDataFile` namespace-subdir via `Identifier.resolveAgainst`.
- All quoted `[SPIKE-R11]` and ERROR lines verified verbatim against the worktree server logs (phase 3 in `latest.log`; phases 1–2 in the rotated `2026-07-13-3/-4.log.gz`). The loss end-state shas and the unchanged post-datafix sha (`8761c4aad92d4acd`) are internally consistent with D-R11-6.
- Mandated deliverables present: the decided constant (D-R11-1) + a captured, greppable two-line loss signature (D-R11-3). D-R11-5 is a genuine corpus correction (portal-generation.md G1 row 2) with on-disk proof.
- Note (no defect): rotated logs show two earlier boots whose P1 reported the `.dat` ABSENT — the wrong-directory iteration that produced D-R11-5; all memo-quoted evidence comes from the final write→read→loss boot sequence.
- UNPROVEN list is honest and none of it gates anything the plan treats as settled at S1 (NeoForge runtime is a plan-wide gap; the constant decision does not depend on the null-rescue). Raw logs are uncommitted and die with the worktree — the memo's embedded quotes are the durable record, per S1(c).
