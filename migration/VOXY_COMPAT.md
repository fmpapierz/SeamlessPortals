# Voxy compat — design + ledger (opened 2026-10-09)

Voxy 0.2.19-beta (Modrinth `fxxUqruK`, Fabric-only, requires sodium `=0.9.1` or `0.9.2`,
`fabric-api >=0.91.1`) is the LOD renderer by Cortex. This project pins sodium 0.9.1 exactly,
so Voxy's dependency resolves. NeoForge: no Voxy build exists; nothing to do there (the facade is
the no-op base invoker, the mixin never weaves — gate 2 + the presence arm).

Ground truth: the jar was decompiled with Vineflower 1.12.0 (scratchpad, not committed); every
claim below about Voxy's internals comes from that source. Re-verify against the jar when Voxy
updates — the pass-guard mixin binds to two of Voxy's merged method NAMES and fails the boot
loudly (`require=1`) if they move.

## 1. How Voxy works (the parts that touch us)

| Voxy piece | What it does | Why it collides with portal views |
|---|---|---|
| `MixinLevelRenderer` (on `LevelRenderer`) | holds ONE `VoxyRenderSystem` per `LevelRenderer` in a mixin field; `voxy$setWorld` keys it on the level's `WorldIdentifier` (dimension key + biome seed + dimension type); `voxy$createRenderer` builds it; `voxy$shutdownRenderer` frees it; `LevelRenderer.close` shuts it down | we create a `LevelRenderer` per secondary dimension |
| `MixinLevelExtractor` | `setLevel` HEAD → `voxy$setWorld(level)`; `allChanged` HEAD → shutdown + create | `createSecondaryClientWorld` calls `setLevel` → `allChanged` on every secondary extractor; the reload cascade calls `allChanged` on every secondary under `withSwitchedWorld` |
| `IVoxyRenderSystemHolder.getNullable()` | `(holder) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem()` — resolved AT CALL TIME by EVERY hook below | we repoint `mc.levelRenderer` to the dest renderer inside every portal pass (`MyGameRenderer.renderWorldNew`, `PortalContextSwitch.withSwitchedWorld`) and to secondaries in `withSwitchedWorld` / the setLevel brackets |
| sodium `VisibleChunkCollector.visit` / `FallbackVisibleChunkCollector.visit` / `RenderSectionManager.renderOutOfGraph` + `readRenderListFromTree` | push the visible sections into / reset `vrs.visbleSectionStream` (the "don't draw LOD where real terrain is" bound store) | a dest pass's cull would reset and refill the MAIN pass's store with dest sections |
| sodium `DefaultChunkRenderer.render` (CUTOUT pass) | `setupViewport(proj, modelView, fog, target.w, target.h, camera)` on the renderer's DEFAULT viewport (frameId++, HiZ, temporal occlusion state) then `renderOpaque(viewport, target.depthTex, target.colorTex)` into `TerrainRenderPass.getTarget()` = `mc.gameRenderer.mainRenderTarget()` resolved at call time | a dest pass would (a) clobber the main viewport's temporal state with the portal camera every frame, (b) draw dest LODs into the MAIN target — Voxy's final composite binds the target depth as `GL_DEPTH_ATTACHMENT` on its own scratch FBO and disables the stencil test: the stencil-direct aperture does NOT bound it (LODs would leak over the whole screen) |
| sodium `RenderSectionManager.updateSectionInfo` | on a section becoming visible, `rawIngest(vrs.getEngine(), section of this.level …)` | a cross-dim dest pass's uploads would write NETHER sections into the OVERWORLD LOD store (`vrs` = main's, `this.level` = the secondary RSM's) |
| sodium `RenderSectionManager.onChunkAdded/onChunkRemoved`, `ClientChunkCache.drop` (Bobby) | `tryAutoIngestChunk(chunk)` keyed on the CHUNK's level — correct world | the unload path reads the chunk via `voxy$cheekyGetChunk` = vanilla `ClientChunkCache.storage`, which `ImmPtlClientChunkMap` never fills → dead for every level flag-ON |
| `FogRenderer.setupFog` RETURN | pushes the render-distance fog to 1e9 when `getNullable() != null` | inert in passes under the guard → dest views keep vanilla fog |
| `MixinClientLevel.setBlocksDirty` | edge-block ingest keyed on the level's own identifier | correct for secondaries too — left alone |
| `VoxyRenderSystem` ctor | `System.gc()`, 2× `glFinish`, a geometry buffer of min(4 GB, free VRAM − 1.5 GB, ≥512 MB) from `RenderResourceReuse` (reuse cache; a SECOND live system allocates a second buffer), worker threads, `world.setDirtyCallback(nodeManager)` (one per engine — a second system on the same engine STEALS the first's) | one system per loaded dimension is not affordable; two systems on one identifier corrupt each other |
| `computeProjectionMat` | reads `mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix` | tier-2 concern only |
| Iris path | `LevelRenderer.render` HEAD captures viewport params; `IrisRenderingPipeline.beginLevelRendering` applies them to `getNullable()`; the CUTOUT hook then uses the captured viewport | under the guard a dest pass captures but never consumes; the next main `render` HEAD overwrites the capture — harmless |

## 2. Tier 1 contract: coexistence (the posture with the tier-2 feature OFF or a shaderpack ON)

**Exactly the MAIN `LevelRenderer` carries a Voxy render system, and no Voxy hook fires inside a
portal pass.** LODs render in the main world as without portals; portal views show no LODs
(their fog is the vanilla render-distance fog). The LOD system follows the player across
dimensions at each crossing (the same cost vanilla Voxy pays on a respawn-driven dimension
change: one `VoxyRenderSystem` shutdown + creation, including Voxy's own `System.gc()`).
Since tier 2 (§3) shipped, this section describes the feature-OFF behaviour; the guard
pieces below are shared by both tiers.

### 2.1 Pieces

1. `qouteall.imm_ptl.core.compat.mixin.voxy.MixinVoxyLevelRenderer_PassGuard` (compat mixin set,
   `priority = 1500` so Voxy's default-1000 mixin has merged its methods first; `remap = false`;
   config default `require = 1`):
   - `voxy$getRenderSystem` HEAD → `null` when `VoxyInterface.isSecondaryContext()`. One guard
     makes every call-time hook inert in a pass: cull-stream reset/puts, the CUTOUT draw, the fog
     push-out, the upload ingest (the wrong-store write), the fade cancel, the iris viewport apply.
   - `voxy$createRenderer` HEAD → in a secondary context: cancelled for a secondary renderer
     (creation sites: `createSecondaryClientWorld` setLevel→allChanged under
     `isCreatingClientWorld`; the reload cascade's per-dim allChanged under `withSwitchedWorld`),
     DEFERRED for the main renderer (`VoxyInterface.deferredMainCreate`, consumed by the next
     `voxy$getRenderSystem` outside any bracket). The main case is Iris: creating the pipeline
     for a dest dimension calls `allChanged()` on the main extractor from inside the dest bracket
     (log: "Creating pipeline for dimension the_nether" → Voxy "Render shutdown completed" →
     "WorldRenderer reloaded the_nether"); Voxy's allChanged HEAD shuts the main system down
     first, and creating it inside the bracket would read the switched `mc.level` for the Y
     bounds. Identity = `isMainRenderer()`: the renderer the global `Minecraft.levelExtractor`
     names (the promote re-points that field), never `mc.levelRenderer` (swapped in brackets).
   - `isSecondaryContext()` = `ClientWorldLoader.getIsCreatingClientWorld() || getIsWorldSwitched()
     || PortalRendering.isRendering() || SecondaryWorldRenderCore.isDestExtracting`.
     `PortalRendering.isRendering()` is true inside `RendererUsingStencil.doRenderPortal`'s
     push/pop (which wraps `renderPortalContent` → `MyGameRenderer.renderWorldNew` → the whole dest
     drive, cull, draws, fog) and inside every iris renderer's push/pop.
2. `VoxyInterface` (`qouteall.imm_ptl.core.compat.voxy_compatibility`): the `SodiumInterface`
   invoker shape. `OnVoxyPresent.onPlayerDimensionChangePromoted(promoted, toWorld, demoted)` runs
   at the tail of `ClientWorldLoader.promoteAndDemoteOnPlayerDimensionChange` (which skips
   `allChanged` on purpose): demoted `voxy$shutdownRenderer()` FIRST (its geometry buffer returns
   to Voxy's reuse cache), then promoted `voxy$setWorld(toWorld)` + `voxy$createRenderer()`.
   `CLIENT.level` is already `toWorld` there (`ClientTeleportationManager` sets it before calling
   in) — the ctor reads it for the Y bounds. Failures are logged, never thrown into the crossing.
   Installed by `RenderCompatGating` inside the sodium-active branch (Voxy requires sodium);
   logged as `Voxy is present` + `Seamless Portals: Voxy compat installed …`.
3. `ImmPtlClientChunkMap.voxy$cheekyGetChunk(int,int)` — a same-name, same-descriptor method on
   the subclass overrides Voxy's merged one at runtime and routes it to IP's own map, so the
   chunk-unload ingest works flag-ON. References no Voxy type (unused method when Voxy is absent).
4. Build: `voxy_version=0.2.19-beta` (gradle.properties), `compileOnly maven.modrinth:voxy` on all
   three modules (the loaders recompile :common), `-PvoxyRuntime=true` adds sodium + voxy to the
   fabric runtime, `IPCompatMixinPlugin` gained the `Voxy` substring arm (ORDER: IrisSodium →
   Iris → Sodium → Voxy → Cloth; a Voxy mixin's simple name must carry `Voxy` and must NOT carry
   `Sodium`/`Iris`).

### 2.2 Lifecycle walk-through

| Event | What happens |
|---|---|
| login | vanilla `LevelExtractor.setLevel` → Voxy creates the main system (no secondary context) |
| first look through a portal to dim X | `createSecondaryClientWorld(X)` → setLevel → allChanged → `voxy$createRenderer` cancelled (`isCreatingClientWorld`) |
| every portal pass | `isRendering()` → `getNullable()` = null → no stream reset, no LOD draw, no ingest, vanilla fog in the window |
| same-dim pass | the main renderer IS the dest renderer; the same guard hides its system for the pass |
| walk into dim X | teleport cutover: `mc.levelRenderer` = X's renderer → promote tail → hook: old main shut down, X's renderer gets a system keyed on X |
| walk back | symmetric |
| F3+A / resource reload | main `allChanged` → Voxy recreates the main's; the cascade's secondary allChanged under `withSwitchedWorld` → cancelled |
| disconnect | `Minecraft.disconnect` → `updateLevelInEngines(null)` → `setLevel(null)` on the main extractor, whose `levelRenderer` field the promote re-pointed at the current main → shutdown; `ClientWorldLoader.cleanUp` → `setLevel(null)` on secondaries → no-op |

## 3. Tier 2 (LODs inside portal views) — SHIPPED 2026-10-09, default ON

Config `voxyLodsInPortalViews` (IPConfig, client category; default true), JVM lever
`-Dseamlessportals.disableVoxyPortalLods=true` (A/B, forces the tier-1 posture), and never while
a shaderpack is active (`IrisInterface.invoker.isShaders()` — the shaders-ON renderer stamps the
dest view from its own target; the stencil bound below does not apply there).

**Mechanism.** Inside a portal pass the renderer driving the pass (`mc.levelRenderer`: the dest
dimension's secondary renderer, or the main renderer for a same-dimension portal) exposes its
render system (`VoxyInterface.shouldHideRenderSystem`), so Voxy's hooks run for that pass as
they do for the main pass: the dest sodium cull fills the visible-section stream, the dest
CUTOUT pass draws the dest LODs into the main target, the dest fog is pushed out, and the
mesh-upload ingest lands in the DEST renderer's own engine (the right LOD store). Passes are
sequential (main cull → main draw → dest cull → dest draw), so the stream is per pass.
1. **One render system per dimension, created lazily.** `createSecondaryClientWorld`'s
   setLevel→allChanged still does not create one (secondary context → PENDING on that renderer);
   the first portal pass that uses the renderer creates it — the pass's own cull hooks are the
   first callers, before any draw, and `mc.level` IS that dimension then (Voxy's constructor reads
   it for the Y bounds). Both renderers keep their systems across a crossing (no shutdown+create
   hitch at crossings any more). Cost per extra dimension ≈ its model atlas (~540 MB VRAM) +
   node buffers; the geometry buffer is a sparse reservation on NVIDIA (`Buf/Tex` went
   `[27/4398],[6/542]` → `[61/8837],[14/1086]` with the nether system alive).
2. **A viewport per (portal UUID, layer)** (`MixinVoxyViewportSelector_PortalViewports`): a Voxy
   `Viewport` is cross-frame occlusion state (HiZ, frame counter); the selector already keys extra
   viewports per Vivecraft eye / Iris shadow pass. Unused for 300 frames → deleted.
3. **Same-dimension passes never re-centre the main tracker** (`MixinVoxyRenderSystem_PortalPass`
   redirects `RenderDistanceTracker.setCenterAndProcess`): the ring of 512-block top-level nodes
   follows the player's camera only; a same-dim destination farther than that ring (~8 km at the
   default 16-section distance) shows no LODs in its window. Cross-dim passes use the dest
   system's own tracker, which rightly follows the dest camera.
4. **The composite is bound to the aperture** (`MixinVoxyAbstractRenderPipeline_StencilBound`):
   Voxy composites its LODs into a scratch FBO holding the target's depth + colour wherever the
   target depth is still far — on every sky pixel of the screen. The scratch FBO now binds the
   main target's `DEPTH24_STENCIL8` texture as `GL_DEPTH_STENCIL_ATTACHMENT` (always, so the
   attachments never alternate), and in a portal pass `transformBlitDepth`'s opening
   `glDisable(GL_STENCIL_TEST)` becomes enable + `setStencilLimitation(layer)` (EQUAL, KEEP).
5. **The limitation is re-armed after Voxy's draw** (`renderOpaque` RETURN): Voxy's state restore
   leaves `GL_STENCIL_TEST` disabled, which would unbound every later dest draw of the pass.
6. Clip planes: the mod enables `GL_CLIP_DISTANCE0` per draw inside the command encoder only, so
   Voxy's raw draws are never clipped (LODs start beyond the chunk distance anyway).

**Evidence (dev, `VoxySmoke`, 2026-10-09):** ON and OFF (lever) both ALL LEGS PASS. With the
feature on, the nether renderer's system is created "inside its first portal pass", survives the
crossings, is re-created after the render-distance change and after allChanged; total systems =
2. Pixel diff ON vs OFF (deterministic runs: ON-vs-ON = 0.0 % everywhere): the only differing
pixels are a horizon band INSIDE each portal window (the same-dim window: x 372–490; the nether
window: x 362–481; the other portal's window at the screen edge likewise) — the far side's chunk
edge replaced by its LODs — and NOTHING outside any aperture (the stencil bound holds). At RD 8
the windows are identical ON vs OFF (the dest's real chunks fill them). Deferred-creation
artefact found and fixed on the way: a probe of a non-current renderer (the test's census) must
not trigger the lazy creation; only `mc.levelRenderer` creates.

## 4. Verification

- `VoxySmoke` (fabric client gametest, `-Dseamlessportals.gametest.only=voxy`, run config
  `voxyGametest`): legs V0–V4 assert from the client's own state that exactly one render system
  exists (on `mc.levelRenderer`) before portals, while a same-dim and a cross-dim view render,
  after walking into the nether and back (promote/demote), and after `allChanged`; the walks
  assert arrival. Screenshots `voxy-1…7` are the visual pre-screen (LODs in the main world after
  the RD shrink, none in the windows, no leak outside the aperture).
  `.\gradlew.bat :fabric:runVoxyGametest -PvoxyRuntime=true -PtestClient=true`
- Log classification after the run (`runs/gametest-voxy/logs/latest.log`): `Voxy is present`,
  `Voxy compat installed`, `Creating Voxy render system` count (1 at login + 1 per crossing + 1
  per reload), `LOD render system moved to the promoted`, and NONE of `Cannot have multiple
  renderers`, `[Voxy]`-tagged errors, `Mixin apply failed`, `InjectionError`.
- `-PtestClient=true` attaches the unattended-client agent (tools/testclient: minimized window,
  never focused, prompts answered) — the user rule for every automated client.

## 5. Ledger

- 2026-10-09: recon (Voxy decompiled; the eight collision points in §1 identified), tier-1
  design, implementation of §2.1, `VoxySmoke` written.
- 2026-10-09 runs 1+2 (`runVoxyGametest -PvoxyRuntime=true -PtestClient=true`, Sodium 0.9.1 +
  Voxy 0.2.19-beta, no Iris): **ALL LEGS PASS** both times, exit 0. Log classification:
  `Voxy is present` + `Voxy compat installed` ✓; `Creating Voxy render system` ×5 = login +
  the V2 render-distance change (vanilla allChanged) + 2 crossings + V4 allChanged, each after a
  clean `Render shutdown completed` ✓; `LOD render system moved to the promoted …` ×2 ✓;
  `Cannot have multiple renderers` ×0, Voxy ERROR ×0, no mixin apply/injection error for the
  pass guard (so injecting into Voxy's MERGED methods at priority 1500 works) ✓. The secondary
  nether renderer never carried a system through portal views, crossings and the reload ✓.
  Voxy's F3 lines from the main renderer at V2/V4 (`#N` 61–66 nodes, frame runtime ≈0.5 ms)
  prove the main pass drives the LOD pipeline while the portal passes are inert.
  Screenshots: `voxy-1` same-dim window shows the dest grass through the aperture (no stone
  platform, no LOD artefact), `voxy-2`/`voxy-5` cross-dim windows show the other dimension,
  `voxy-3` at RD 3 shows grass to the horizon = LODs beyond sodium's 48-block cutoff; nothing
  outside any aperture changed. (A flat test world is a weak LOD discriminator; the F3 counts
  are the primary evidence.)
  Pre-existing noise, not Voxy's: the `IrisDestPrevCamera` "WRITE SEAM NEVER FIRED" warning
  (also in the sodium-absent crossing suite log) and the 10 s server stall right after staging
  (the 33×33 forceloaded nether chunks generating; the crossing suite has the same).
- 2026-10-09 shaders-ON leg (`-PirisRuntime=true`, Iris 1.11.2 + ComplementaryReimagined r5.8.1
  staged in `runs/gametest-voxy`): run A FAILED at V1 — `main=false … total=0`. Cause (log
  02:01:15–18): Iris "Creating pipeline for dimension the_nether" (the cross-dim view's pipeline)
  → Voxy "Render shutdown completed" → "WorldRenderer reloaded minecraft:the_nether", i.e. the
  main extractor's `allChanged()` ran INSIDE the dest bracket; Voxy's allChanged HEAD shut the
  main system down and the first guard then cancelled its recreation (secondary context). FIX:
  the deferral (§2.1) — run B: **ALL LEGS PASS**, `deferred LOD render system creation ran for
  the main renderer (present=true)` logged right after the nether pipeline reload, Voxy on
  `IrisVoxyRenderPipeline` (its own shaderpack integration live; the pack's programs patched
  for both the overworld and the nether pipelines), 9 creations (login, 3 Iris pipeline reloads
  at world open, the deferred one, RD change, 2 crossings, allChanged), 0 errors.
  **Known visual limitation, shaders ON only:** at RD 3 the overworld's LOD grass horizon shows
  as a thin band INSIDE the nether portal window (screenshot `voxy-3`, absent shaders-OFF).
  Mechanism: the shaders-ON renderer stamps the dest view (colour + depth) BEFORE the main
  terrain pass; the window's dest-SKY pixels keep far depth, and Voxy's CUTOUT-pass composite
  draws LODs exactly where the source depth is far (`setup_stencil_depth` marks far pixels), so
  the main world's LOD horizon lands on the window's sky. Shaders-OFF is immune because the
  stencil-direct portal pass runs AFTER the LOD draw and fills the aperture. Candidate fixes
  (not done — they touch the IS5 stamp): seal the aperture's dest-sky pixels at the portal-plane
  depth after the stamp, or re-stamp the aperture after sodium's CUTOUT pass when Voxy is present.
- 2026-10-09 run C (plain leg again, final code with the deferral): **ALL LEGS PASS**, 5
  creations, 0 errors — the deferral is inert without Iris. Release jars built
  (`gradlew assemble`): `build/libs/seamlessportals-fabric-1.0.0.jar` +
  `seamlessportals-neoforge-1.0.0.jar` (root mirror now release-only). NOT run this session:
  the release-jar check on a real launcher-style Fabric client with the released Sodium + Voxy
  jars (the dev gametest IS a real client with the real Sodium/Voxy jars, but launched through
  loom); nothing in this change is loader-packaging-sensitive (26.2 is unobfuscated, the compat
  mixin config is the same file, the facade classloads by presence).
- 2026-10-09 **RELEASE CHECK, Fabric** (`tools\release-test.ps1 -Loaders fabric`, written this
  session from TACZ 26.2's): a real dedicated server (official `fabric-server-launch.jar`,
  loader 0.19.3, Fabric API 0.152.1+26.2, the release jar; flat world, offline, EULA accepted)
  and a real launcher-profile client (vanilla jar from Mojang, the Fabric profile from
  meta.fabricmc.net, mods = release jar + Fabric API + **released Sodium mc26.2-0.9.1 + Voxy
  0.2.19-beta from Modrinth**, the window agent attached): **PASSED / PASSED** (server+client,
  singleplayer). The client joined over the socket, a 3×3 bi-way pair overworld↔nether was made
  with `/portal cb_make_portal` on armor-stand anchors, the portal view rendered the nether
  (`mp-1`), the player walked through into the nether and back (both crossing cutovers logged,
  `state` lines confirm the dimension), the LOD system moved at each crossing (×2), `Voxy is
  present` + `Voxy compat installed` ×1, 3 LOD systems created, NO broken-jar line (missing
  class/member, mixin failure, crash report, "Cannot have multiple renderers") in any log. The
  server's world then opened in singleplayer with cheats patched on: same legs, same result.
  Window: minimized and never in front throughout (user32 poll); one restore (not in front)
  seen right after a crossing in run 1 only. Observation, not Voxy's: the nether sky inside the
  window looked teal in the first singleplayer view (`sp-1`) and red in multiplayer (`mp-1`).
  Script pitfalls paid for: `cb_make_portal` is entered from the side OPPOSITE the anchor's
  view vector; a server world has cheats off in singleplayer (patch `allowCommands` in
  level.dat); stdout + latest.log double every count; `Copy-Item -Recurse` into an existing
  folder nests it; `$pid`/`$input` are reserved in PowerShell.
- 2026-10-09 **RELEASE CHECK, NeoForge 26.2.0.88** (the newest 26.2 NeoForge, installed by its
  own installer for both the client profile and the server; mods = the release jar only, Cloth
  Config is bundled): **PASSED / PASSED**. Server booted (`Done (0.452s)`), the client joined,
  the portal view rendered the nether (`neoforge/screenshots-mp/mp-1`), walked into the nether
  and back (2 cutovers), singleplayer the same; no broken-jar line. Voxy does not exist on
  NeoForge, so this leg is the regression proof that the compat code is inert there (the facade
  stays the no-op base, the mixin never weaves). Only pre-existing lines in the warnings: the
  IS5-MB diagnostic, IP's `Chunk loading failure` tickets, and FML rewriting the minimal
  `fml.toml` the script seeds. The window was restored once (never in front) while the user was
  Alt-Tabbing (`Task Switching` was the foreground window at that moment).
- 2026-10-09 **RELEASE CHECK of the tier-2 jars** (`tools\release-test.ps1 -Loaders fabric,neoforge
  -Work build\release-test-t2 -Port 25573`, jars built 03:59, SHA-256 fabric 7f9ab632…,
  neoforge 369e0d31…): **Fabric PASSED / PASSED, NeoForge 26.2.0.88 PASSED / PASSED.** On the
  real Fabric client with the released Sodium + Voxy: the nether's LOD system was created
  "inside its first portal pass" (1), 2 systems alive, both kept across the two walking
  crossings ("moved … demoted keeps its own=true" ×2), no broken-jar line, in multiplayer and in
  singleplayer alike. NeoForge (no Voxy) unchanged: crossings both ways, both legs. Windows
  minimized and never in front throughout.
- 2026-10-09 regression gate (`runCrossingGametest`, sodium-absent): legs 1, 2, 3, 7, 4, 6a, 6b
  PASS, then **`RS-A step-1 SEAM MAP GATE FAILED: registry hot-path gate missed section for bound
  cell (-311,-59,-319) (portal 20)`** — the leg-6b generated overworld portal. ATTRIBUTED
  PRE-EXISTING: with every Voxy source change stashed (`git stash -- common/src fabric/src`,
  build wiring kept) the suite fails at the SAME gate with the same message (cell -309,-59,-319).
  The run dir's last green suite log is dated 2026-08-02; nothing in this engagement touches
  the seam registry. Left for its own session (chip spawned).
