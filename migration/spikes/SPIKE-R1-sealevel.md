# SPIKE-R1 — seaLevel + secondary-world construction (memo)

Stage S1, D6 discipline. Branch: `spike/r1-sealevel` (worktree `wf_c463c3ac-d47-3`, spike code
commit `e6ca849`, parent `4a489405` = S0.3) — **learning-only, never merged**. Probe vehicle:
the Fabric client-gametest harness (`fabric-client-gametest` entrypoint swapped to
`com.warwa.seamlessportals.spike.SpikeR1SeaLevel` on the spike branch) — fully automated:
creates a singleplayer world, drives the probe on the client thread, exits programmatically.
Raw evidence committed on the spike branch: `spike-run.log` (run 1, incl. the hang),
`spike-run2.log` (run 2, all green), screenshot `fabric/runs/gametest/screenshots/0000_spike-r1-post-extract.png` (on-disk in the worktree, NOT committed — only the two logs are tracked).
Date: 2026-07-13. All `26.2:` citations = `C:/Users/warwa/ModDev/mc262-ref` (Mojang mappings);
`IP:` = `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`; `MOD:` = this repo.

---

## 1. What was PROVEN (captured evidence)

### 1.1 Construction of a ClientLevel for an UNVISITED dim with a synthesized seaLevel — WORKS

The End was never entered this session. The mod's existing machinery
(`MOD:common/.../client/PortalWorldManager.createRenderer`, ClientLevel ctor call at :652-663,
seaLevel arg = `mc.level.getSeaLevel()` at :662) constructed it live:

```
[20:13:34] [Render thread/INFO] (seamlessportals) [SPIKE] constructed secondary ClientLevel for
minecraft:the_end (hadBefore=false): getSeaLevel()=-63 — SYNTHESIZED from current dim
minecraft:overworld (its value -63), loadedChunks=0, sections=16
```

The ctor accepts any int; the value rides through to `getSeaLevel()` unchanged. Construction,
`setLevel`, `onResourceManagerReload` — no crash.

### 1.2 Ground truth: seaLevel is a per-GENERATOR-CONFIG value — no client-local source exists

Integrated-server query in the same session (the gametest world uses consistent/flat settings):

```
[20:13:34] ... [SPIKE] server ground truth: dim=minecraft:overworld generator seaLevel=-63
[20:13:34] ... [SPIKE] server ground truth: dim=minecraft:the_end generator seaLevel=0
[20:13:34] ... [SPIKE] server ground truth: dim=minecraft:the_nether generator seaLevel=32
[20:13:34] ... [SPIKE] client main level: dim=minecraft:overworld getSeaLevel()=-63 (from CommonPlayerSpawnInfo at login)
```

**The flat overworld reports −63, not 63.** Same dim id, same dimension type, different
generator config → different seaLevel. This is stronger than API_RISKS R1 stated: the client
cannot synthesize the value even knowing the dimension TYPE (`26.2:DimensionType.java` — no
sea-level member; grep confirms). Server-side origin chain:
`ServerPlayer.createCommonSpawnInfo(ServerLevel)` passes `level.getSeaLevel()`
(`26.2:ServerPlayer.java:2152-2166`, the arg at :2163) → `ServerLevel.getSeaLevel()` =
`chunkSource.getGenerator().getSeaLevel()` (`26.2:ServerLevel.java:1832`;
`ChunkGenerator.getSeaLevel()` abstract, `26.2:ChunkGenerator.java:641`). Wire:
`CommonPlayerSpawnInfo` record component (`26.2:CommonPlayerSpawnInfo.java:25`, written as
VarInt :52); client consumption ONLY at login (`26.2:ClientPacketListener.java:504` →
ctor :507-518) and respawn (`:1259` → ctor :1262-1274). ClientLevel stores it in a
**`private final int seaLevel`** (`26.2:ClientLevel.java:179`, ctor param :248, assigned :256,
getter :1129-1131). Final ⇒ **a wrong construction-time value is permanent for that
ClientLevel instance's whole life** — and under the IP model (and the mod's promotion path,
`MOD:HandleRespawnMixin.seamlessportals$redirectNewClientLevel` returning the cached secondary)
the same instance BECOMES `mc.level` after a crossing. Get it right at construction or never.

### 1.3 Extract passes on the fresh unvisited secondary — WORK (with one landmine, §1.5)

```
[20:13:34] ... [SPIKE] extracted End secondary pass 1 OK (ViewArea creation path)
[20:13:34] ... [SPIKE] extracted End secondary pass 2 OK (steady-state path)
```

Pass 1 exercises the `lastViewDistance == -1` → `allChanged()` → `invalidateCompiledGeometry`
branch (creates the ViewArea inside `extract`, `26.2:LevelExtractor.java:96-98,124-126`);
pass 2 the steady-state branch incl. `applyFrustum` (`:127-135`, method :395). Virtual-camera
recipe = the mod's proven one (`MOD:render/PortalContextSwitch.java:1051-1084`): bare
`new Camera()` + `setLevel(secondary)` + `setEntity(mc.player)` + position/rotation invokers +
`tick()` + cull frustum + `initialized=true`, then
`extractor.extract(mc.getDeltaTracker(), cam, partialTick)`.

### 1.4 Render sanity — PROVEN

```
[20:13:36] ... [SPIKE] rendered 40+ ticks of frames post-extract without crash; main level still
minecraft:overworld (seaLevel -63), End secondary still cached: true
```

Main render loop ran ~500 frames across the probe (mod frame-pump timers logged 513 calls/5s),
screenshot captured, no render-state contamination (the secondary extractor writes its OWN
`LevelRenderState`, per the mod's isolation design).

### 1.5 RUN-1 FINDING — reversed-Z frustum hangs `extract()` forever (S11 watch item)

Run 1 built the virtual camera's cull frustum the way the mod's `doFboRender` does
(`MOD:PortalContextSwitch.java:1080-1084`): `new Frustum(viewRotationMatrix, new
Matrix4f(cameraRenderState.projectionMatrix))`. Pass 2 then **hard-hung the render thread**:

```
[20:07:09] [seamless-stall-watchdog/WARN] (seamlessportals) [SEAMLESS STUCK] render thread stalled ~181ms in:
  at knot//net.minecraft.client.renderer.culling.Frustum.offsetToFullyIncludeCameraCube(Frustum.java:60)
  at knot//net.minecraft.client.renderer.SectionOcclusionGraph.offsetFrustum(SectionOcclusionGraph.java:359)
  at knot//net.minecraft.client.renderer.SectionOcclusionGraph.addSectionsInFrustum(SectionOcclusionGraph.java:121)
  at knot//net.minecraft.client.renderer.extract.LevelExtractor.applyFrustum(LevelExtractor.java:395)
  at knot//net.minecraft.client.renderer.extract.LevelExtractor.extract(LevelExtractor.java:146)
  ...
[20:07:12] [seamless-stall-watchdog/WARN] (seamlessportals) [SEAMLESS FREEZE] render thread stuck ~3004ms — dumping ALL threads:
```

`pass 2 OK` never printed in run 1; the gametest framework killed the client. Root cause,
source-verified:

- `Projection.getMatrix` **swaps near/far** — `float near = this.zFar; float far = this.zNear;`
  (`26.2:client/renderer/Projection.java:63-78`, the swap at :70-71): the 26.2 RENDER
  projection (what lands in `cameraRenderState.projectionMatrix`,
  `26.2:Camera.java:134` `this.projection.getMatrix(cameraState.projectionMatrix)`) is
  **reversed-Z**.
- `Frustum.offsetToFullyIncludeCameraCube(8)` (`26.2:Frustum.java:46-71`) steps the camera
  `viewVector * 4` per iteration until an 8-cube is fully inside the frustum; `viewVector` =
  the combined matrix's z-row (`:82`). Under reversed-Z that row's z component collapses to
  ~`far/(near−far)` ≈ +1.2e-5 instead of ≈ −1 ⇒ step size ~5e-5 blocks/iteration ⇒ the loop is
  effectively infinite. Deterministic, not a numerics fluke.
- Vanilla never feeds `SectionOcclusionGraph` such a frustum: the cull frustum is built from a
  DEDICATED conventional-Z projection `createProjectionMatrixForCulling()` (plain JOML
  `perspective(maxFov, aspect, 0.05, depthFar, zZeroToOne)`, `26.2:Camera.java:179-189`,
  wired at :106; `depthFar = max(renderDistance*4, cloudRange*16)` :95).

Run 2 replaced the projection with a culling-style conventional-Z perspective (finite far) —
pass 2 completed instantly. **Rule for S11/CUTOVER_SPEC:** any portal/secondary cull `Frustum`
that can reach `SectionOcclusionGraph.addSectionsInFrustum` (i.e. any camera without a
capturedFrustum whose extract runs `applyFrustum`) MUST be built from a conventional-Z culling
projection (mirror `Camera.createProjectionMatrixForCulling`), NEVER from
`cameraRenderState.projectionMatrix`. **The current mod carries this latently**: the
native-render path (`nativeRender=true`, no captured frustum) uses exactly the reversed-Z
recipe — a candidate cause of rare multi-second render-thread freezes (chip spawned; out of
spike scope).

### 1.6 Biome temperature cache is poisoned across seaLevels — CONFIRMED

`Biome.getTemperature(pos, seaLevel)` caches by `pos.asLong()` ONLY (LRU 1024, ThreadLocal per
Biome INSTANCE — `26.2:Biome.java:124-135`); Biome instances come from the shared registry, so
every ClientLevel on the client thread shares each biome's cache. Probe (plains biome,
y=160, two adjacent positions, honest answers RAIN@sea63 / SNOW@sea−2000):

```
[20:13:34] ... [SPIKE] temp-cache probe: posA sea63→RAIN then sea-2000→RAIN | posB sea-2000→SNOW then sea63→SNOW
[20:13:34] ... [SPIKE] temp-cache poisoning CONFIRMED — cache keyed by pos only; seaLevel of the FIRST query wins
```

Both directions: whichever level queries a (biome, pos) first fixes the temperature for every
other level sharing that biome at that pos. A WRONG synthesized seaLevel in a secondary
therefore leaks into MAIN-level results wherever the same biome+pos is sampled — most relevant
for IP's `alternate_dimension`/dim-stack worlds (overworld biomes at overworld coordinates).

---

## 2. Blast radius of a wrong seaLevel on a client-side ClientLevel (exhaustive consumer map)

Repo-wide grep of `getSeaLevel()` consumers, classified for a CLIENT secondary level:

| Consumer | Cite | Client impact of a wrong value |
|---|---|---|
| `ClientLevel.getPrecipitationAt(BlockPos)` | `26.2:ClientLevel.java:401-408` | The rain-vs-snow decision. Consumed by **`WeatherEffectRenderer`** extraction (`26.2:WeatherEffectRenderer.java:81` — rain vs snow columns in the portal view) and **rain particles/sound** (`26.2:ClientLevel.java:352-360`). |
| `Biome.getHeightAdjustedTemperature` | `26.2:Biome.java:112-121` | The only math: `snowLevel = seaLevel + 17`; above it temperature falls off `0.05/40` per block (+noise). Wrong seaLevel shifts the rain/snow line; magnitude = `Δy · 0.00125` temp units (flip threshold 0.15). Overworld-63 synthesized into the nether (real 32) ⇒ error ≈ 0.04 — biomes near the threshold flip. |
| shared per-biome temperature cache | `26.2:Biome.java:124-135` | **Cross-level poisoning — CONFIRMED empirically (§1.6).** |
| `Level.getHeight` out-of-bounds fallback | `26.2:Level.java:336-349` (`:345`) | Returns `seaLevel+1` only for \|x\|,\|z\| ≥ 30,000,000 — negligible. |
| `LevelReader.canSeeSkyFromBelowWater` | `26.2:LevelReader.java:88-100` | Sole caller is Guardian spawn rules (`26.2:Guardian.java:304`) — server-side; inert on a client secondary. |
| Entity AI (Drowned/Phantom/Turtle/Ocelot/`AmphibiousNodeEvaluator`), snow/ice placement (`Biome.shouldFreeze/shouldSnow` via `ServerLevel` tick), worldgen/structures | various | Server-side only — never run on a client secondary (client entity ticking runs no goals; freezing runs in `ServerLevel.tickChunk`). |
| NOT affected: horizon height / void-darkness onset | `26.2:ClientLevel.java:1210-1216` | Hardcoded `63.0` / `isFlat` — independent of seaLevel. |

**Summary:** on a pure portal-VIEW secondary the blast radius is cosmetic (precipitation
visuals) plus the cache-poisoning leak into other levels. But because the value is final and
the level gets PROMOTED to `mc.level` at crossing (IP model and mod alike), a wrong value
persists into normal gameplay in that dim — where the same consumers now shape the player's
own weather rendering permanently until relog. Cheap to get right; the protocol below does.

---

## 3. THE SEALEVEL PROTOCOL DESIGN V1 (for S7 implementation, client consumption at S10)

Rides the dim-id sync path S7 ports — `MiscNetworking.DimIdSyncPacket`
(`IP:q_misc_util/MiscNetworking.java:36-148`), which today carries two NBT compounds:
`dimIntIdTag` (dim id → int id) and `dimTypeTag` (dim id → dim-type id), written as two
`buf.writeNbt` calls (:87-90).

**Message shape.** Add a third compound, in the packet's own idiom:
`dimSeaLevelTag: CompoundTag` mapping `dimId.location().toString()` → `IntTag(seaLevel)`.
Wire: third `buf.writeNbt(dimSeaLevelTag)` / third `buf.readNbt()` (append-only; both sides
ship together — the payload is mod-owned `imm_ptl:dim_int_id_sync`, no cross-version concern).

**Server fill.** In `DimIdSyncPacket.createFromServer(server)`'s existing
`server.getAllLevels()` loop (`IP:MiscNetworking.java:58-76`):
`dimSeaLevelTag.putInt(dimId.location().toString(), world.getSeaLevel())`. This is
**bit-identical to vanilla's own source** for the spawn-info field
(`26.2:ServerPlayer.java:2163` passes `level.getSeaLevel()`), so the synced value can never
disagree with what vanilla sends when the player actually travels there.

**When servers send it — NO new send sites needed; both existing ones cover the requirement:**
1. **Login**: `MixinPlayerList_Misc` injects into `placeNewPlayer` at
   `@At(INVOKE, ClientboundChangeDifficultyPacket.<init>)`
   (`IP:q_misc_util/mixin/dimension/MixinPlayerList_Misc.java:15-31`) — mid-login, BEFORE the
   difficulty packet, preserving the DEPENDENCY_ORDER §4.2 login order (dim-id sync before any
   redirected packet or global-portal sync). The packet is rebuilt from `getAllLevels()` per
   send, so seaLevel rides automatically.
2. **Dynamic dimension add/remove**: `DimensionIntId.onServerDimensionChanged`
   (`IP:q_misc_util/dimension/DimensionIntId.java:103-128`) already rebuilds the packet and
   sends to ALL players on every server-dimension change (registered on the DimLib
   early-phase event, :31-44). New dims' seaLevels arrive before any portal can target them
   (the same packet is what makes the dim constructible at all — see fallback).

**Client cache lifecycle.** Mirror `dimIdToDimTypeId` exactly:
- New field `ClientWorldLoader.dimIdToDimSeaLevel: @Nullable ImmutableMap<ResourceKey<Level>, Integer>`
  beside `dimIdToDimTypeId` (`IP:ClientWorldLoader.java:72`).
- Replaced WHOLESALE in `DimIdSyncPacket.handle()` (`IP:MiscNetworking.java:99-126`, runs on
  the client thread via the play-payload receiver) — stale entries for removed dims vanish
  with the map swap, same as the dim-type map.
- Nulled on client exit exactly where `dimIdToDimTypeId` is nulled
  (`IP:ClientWorldLoader.java:98-100`, the exit-cleanup listener).
- **Consumption (S10)**: `createSecondaryClientWorld` passes
  `dimIdToDimSeaLevel.get(dimension)` as the ctor's trailing arg
  (26.2 ctor `26.2:ClientLevel.java:238-249`; the 1.21.3 call site being ported is
  `IP:ClientWorldLoader.java:445-456`).

**Fallback when a dim's seaLevel has not arrived.** Ordering makes this
near-impossible-by-construction: `createSecondaryClientWorld` ALREADY hard-depends on the same
packet's dim-type map (`IP:ClientWorldLoader.java:420` throws via `getWorld` when the dim is
unknown), and both compounds arrive atomically. The residual windows are (a) a dynamic-dim
race where a portal targets a just-added dim before the refreshed packet lands, (b) a desync
bug. Policy: **fail-soft, never throw** (matches IP's remote-world doctrine —
`ClientWorldLoader` rate-limited swallow, LOG_LIMIT `:58`): use
`CLIENT.level.getSeaLevel()` (the mod's current, empirically benign behavior — §1.1) and WARN
once per dim. Blast radius of the fallback is the measured §2 set (cosmetic + cache leak),
and the next `DimIdSyncPacket` cannot retro-fix a constructed level (field is final) — if the
mismatch is ever observed in the wild, the S10-time option is to dispose+recreate the
secondary while it still has zero chunks (cheap); NOT in v1.

**What v1 deliberately does not do:** no per-dim seaLevel in `CommonPlayerSpawnInfo` mimicry,
no dimension-type-keyed defaults table (disproven by §1.2 — flat OW = −63), no vanilla-packet
codec extension (R8-style stamping is for position packets; this is mod-channel data).

---

## 4. Remote-tick role placement (LevelRenderer.tick is GONE)

**Decision: port `ClientWorldLoader.tick()` 1:1 minus the renderer loop; anchor unchanged;
the renderer loop's role is absorbed by work the ported tick already does.** Specifics:

1. **Anchor intact.** IP calls `ClientWorldLoader.tick()` from
   `MixinMinecraft.onAfterClientTick` — `@Inject(method="Minecraft.tick", at=@At(INVOKE,
   target=ClientLevel.tick(BooleanSupplier), shift=AFTER))`
   (`IP:imm_ptl/core/mixin/client/MixinMinecraft.java:119-141`). 26.2 still calls
   `this.level.tick(() -> true)` inside `Minecraft.tick()` (`26.2:Minecraft.java:1819`;
   api-map world-loader-root §5.1) — the injection point ports verbatim.
2. **`tickRemoteWorld` ports 1:1.** Every call survives: `ClientLevel.tick(BooleanSupplier)`
   `26.2:ClientLevel.java:299`, `tickEntities()` :453, `pollLightUpdates()` :281,
   `animateTick` :560 (api-map §4.9). Empirical support: the mod ticks its secondaries with
   exactly this triple every client tick today (`MOD:PortalWorldManager.tickRemoteWorlds`
   :1368-1423, registered at `MOD:fabric/.../SeamlessPortalsClientFabric.java:34-56`) —
   battle-proven on 26.2, timers show ~0.2ms/5s at idle (spike-run2.log `[SEAMLESS TIMERS]`).
3. **The `worldRenderer.tick()` loop (`IP:ClientWorldLoader.java:119-123`) is DELETED with a
   documented role transfer, not replaced.** 26.2 `LevelRenderer` has no `tick()` (grep:
   zero matches in `26.2:LevelRenderer.java`). Its 1.21.3 duty — expiring stale
   `BlockDestructionProgress` — now lives on the LEVEL:
   `ClientLevel.tick` → `removeBlockBreakingProgress()` (`26.2:ClientLevel.java:305`, method
   :410-424 — the same gameTime%20 / 400-tick-expiry algorithm), which the ported
   `tickRemoteWorld` ALREADY runs via `newWorld.tick(() -> true)`. The render-side consumption
   of destruction progress happens per-frame in extraction
   (`26.2:LevelExtractor.java:279,324`). Nothing renderer-side remains to tick per game tick.
4. **The rest of `ClientWorldLoader.tick()`** (DimensionRenderHelper tick + lightmap-conflict
   detection, `IP:ClientWorldLoader.java:127-145`) is not renderer-tick work; it ports against
   the Lightmap/LightmapRenderStateExtractor/LightmapRenderState triple (api-map
   world-loader-root GONE row; extractor keeps the `(GameRenderer, Minecraft)` ctor). Owned by
   S10; the conflict-identity check needs a `GameRenderer.lightmap` accessor as mapped.
5. **Pairing reminder** (memory `portalview-light-engine-half-port`): tick-side
   `pollLightUpdates` is only HALF the light pipeline; the render-end
   `runLightUpdates` (IP `lateUpdateLight`) must stay paired when S10 ports this — the mod's
   `lateUpdateSecondaryLight` (`MOD:PortalWorldManager.java:1454-1467`) is the working shape.

---

## 5. What remains UNPROVEN

- **Full `LevelRenderer.render()` of the unvisited secondary** was not driven (extract-only
  probe). Render sanity here means: two extracts + ~500 normally-rendered main frames + a
  screenshot, no crash, no main-state contamination. The stencil-direct dest-render path on a
  ZERO-chunk secondary is untested (the mod gates portal rendering on portal existence; no
  portal to the End existed).
- **Crossing into a wrong-seaLevel dim** (promotion making the synthesized value the main
  level's) is code-path-verified (`MOD:HandleRespawnMixin` redirect returns the cached
  instance; field final per `26.2:ClientLevel.java:179`) but not runtime-demonstrated in this
  spike.
- **The dynamic-dimension race window** in §3's fallback (portal targets a just-added dim
  before the refreshed DimIdSyncPacket lands) is reasoned, not reproduced — DimLib dynamic
  dims are not in the spike harness.
- **Whether the current mod's native-render path actually hits the §1.5 hang in production**
  is unproven (the reversed-Z ingredient and the hang mechanism are proven; the mod's
  applyFrustum trigger frequency on that path is not measured). Chip spawned for a targeted
  fix/verification outside the migration.
- The gametest world is FLAT; the −63/32/0 ground-truth triple is generator-config-specific
  (that is the point of §1.2), but a default-noise overworld run (expected 63) was not
  captured in this spike's logs.

## Review verdict (S1 adversarial review, 2026-07-13)

**PASS — all three gating deliverables present** (probe evidence §1, protocol design v1 §3, remote-tick placement §4); this satisfies the API_RISKS R1 "design-complete BEFORE the atomic commit" requirement with empirical evidence.
- All quoted `[SPIKE]` lines verified verbatim against the COMMITTED `spike-run2.log`, and the run-1 hang stack against `spike-run.log` (`offsetToFullyIncludeCameraCube(Frustum.java:60)` present in both STUCK and FREEZE dumps).
- The §1.5 reversed-Z chain independently re-checked in mc262-ref and confirmed verbatim: `Projection.getMatrix` swaps near/far (`float near = this.zFar; float far = this.zNear;`); `offsetToFullyIncludeCameraCube` steps the camera by `viewVector * 4` per iteration with no iteration cap; `createProjectionMatrixForCulling` is a conventional-Z `perspective(0.05F, depthFar)` with `depthFar = max(RD*16*4, cloudRange*16)`. The derived S11 rule (portal cull frusta must mirror the culling projection, never `cameraRenderState.projectionMatrix`) follows from confirmed facts. Genuine bonus find.
- §1.2/§2 facts re-checked: `ClientLevel.seaLevel` private final (:179/:248/:256, getter :1129-1131); `spawnInfo.seaLevel()` consumed only at `ClientPacketListener.java:504` and `:1259`; `DimensionType` has no sea-level member (reviewer grep = 0 hits); `Biome` temp cache keyed by `pos.asLong()` only with `snowLevel = seaLevel + 17` falloff `0.05/40` — the poisoning probe's interpretation is correct in both directions.
- §3 protocol design verified against IP source: two `buf.writeNbt` calls at `MiscNetworking.java:87-90` (third compound is genuinely append-only in the packet's own idiom); `MixinPlayerList_Misc.java:15-31` injection shape; `ClientWorldLoader.dimIdToDimTypeId` at :72 and the `getWorld` hard-dependency (~:420); `DimensionIntId.onServerDimensionChanged` at :103. The fail-soft fallback matches the mod's measured-benign current behavior.
- §4 role transfer source-verified: `ClientLevel.tick` runs `removeBlockBreakingProgress()` (same gameTime%20 / 400-tick algorithm), `pollLightUpdates` :281 / `tickEntities` :453 / `animateTick` :560 all exist; `Minecraft.tick` still calls `this.level.tick(() -> true)` (~:1819) so the IP anchor ports verbatim. The §4.5 pairing reminder correctly guards the known half-port trap.
- §5 UNPROVEN list is honest and appropriately scoped: full secondary render and crossing-promotion runtime are S14 territory by plan; the S1 spec asked for construction + extract + render sanity, which is what was delivered (note "render sanity" = extract passes + ~500 main-loop frames + screenshot, NOT a driven secondary `LevelRenderer.render` — the memo says so itself).
- Reviewer fix applied: the header claimed the screenshot was committed; only the two logs are tracked on the branch (wording corrected in place).
